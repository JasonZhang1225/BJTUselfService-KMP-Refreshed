package team.bjtuss.bjtuselfservice.shared.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.security.AccountPreferences
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReason
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceResult
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

class AgentCacheUpdateTest {
    private val directory: File = Files.createTempDirectory("agent-sync").toFile()
    private val key = ByteArray(32) { (it * 7).toByte() }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    // region 输出

    @Test
    fun reportKeepsLegacyResultsAndAddsPerSourceDetail() {
        val report = RefreshReport("20990001", RefreshOrigin.CLI, 1, 2, listOf(
            RefreshSourceResult(RefreshSource.GRADES, RefreshStatus.SUCCESS, fetchedAtEpochSeconds = 1_760_000_000),
            RefreshSourceResult(RefreshSource.HOMEWORK, RefreshStatus.NEEDS_USER_ACTION, RefreshReason.SESSION_EXPIRED, "请重新登录"),
            RefreshSourceResult(RefreshSource.CITEL, RefreshStatus.SKIPPED, RefreshReason.NOT_CONFIGURED),
            RefreshSourceResult(RefreshSource.EXAMS, RefreshStatus.FAILED, "network", fetchedAtEpochSeconds = 1_700_000_000),
        ))
        val output = report.toCacheUpdateOutput(json = true, executor = "headless", login = AgentLoginOutcome.Ok)
        assertEquals(AgentExit.PARTIAL, output.exitCode)
        val body = Json.parseToJsonElement(output.stdout).jsonObject
        assertEquals("headless", body["executor"]!!.jsonPrimitive.content)
        val results = body["results"]!!.jsonObject
        assertEquals("ok", results["grades"]!!.jsonPrimitive.content)
        assertEquals("session_expired", results["homework"]!!.jsonPrimitive.content)
        assertNull(results["citel"], "跳过的来源不进入旧版 results 映射")
        val sources = body["sources"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("grades", "homework", "citel", "exams"), sources.map { it["source"]!!.jsonPrimitive.content })
        // 失败来源报告上次真实同步时间，而不是本次刷新时间。
        assertTrue(sources[3]["syncedAt"]!!.jsonPrimitive.content.startsWith("2023-11-1"))
        assertFalse("20990001" in output.stdout, "输出不带学号")
        assertTrue("App 里处理" in output.stderr)
    }

    @Test
    fun allSuccessOrSkippedIsOk() {
        val report = RefreshReport("x", RefreshOrigin.CLI, 1, 2, listOf(
            RefreshSourceResult(RefreshSource.GRADES, RefreshStatus.SUCCESS),
            RefreshSourceResult(RefreshSource.PHYVLAB, RefreshStatus.SKIPPED, RefreshReason.DISABLED),
        ))
        val output = report.toCacheUpdateOutput(json = false, executor = "app", login = AgentLoginOutcome.Ok)
        assertEquals(AgentExit.OK, output.exitCode)
        assertTrue("执行者：交大自由行 App" in output.stdout)
        assertTrue("物理在线：跳过（已关闭）" in output.stdout)
    }

    @Test
    fun parsesLegacyAndNewSourceNames() {
        assertEquals(RefreshSource.entries.toSet(), parseRefreshSources(emptyList()))
        assertEquals(RefreshSource.entries.toSet(), parseRefreshSources(listOf("all")))
        assertEquals(setOf(RefreshSource.GRADES, RefreshSource.CITEL), parseRefreshSources(listOf("grades", "citel")))
        assertNull(parseRefreshSources(listOf("grades", "shell")))
    }

    // endregion

    // region 关卡（全部在联网之前返回）

    @Test
    fun appRunningBlocksUpdate() {
        writeCache()
        val appLock = assertNotNull(holdAgentAppLock(directory))
        appLock.use {
            val output = runCacheUpdate(emptyList(), platform(remember = true))
            assertEquals(AgentExit.APP_RUNNING, output.exitCode)
            assertTrue("在 App 里刷新" in output.stderr)
        }
        assertNotNull(AgentAppLock.tryAcquire(directory)).close()
    }

    @Test
    fun notRememberedNeverClearsCredentials() {
        writeCache()
        val vault = FakeVault(Credentials("20990001", "secret"))
        val output = runCacheUpdate(emptyList(), platform(remember = false, vault = vault))
        assertEquals(AgentExit.LOGIN_FAILED, output.exitCode)
        assertTrue("记住密码" in output.stderr)
        assertFalse(vault.cleared, "命令行绝不能清除凭据")
        assertFalse("secret" in output.stdout + output.stderr)
    }

    @Test
    fun rememberedAccountMustMatchCache() {
        writeCache()
        val output = runCacheUpdate(emptyList(), platform(remember = true, vault = FakeVault(Credentials("20990009", "x"))))
        assertEquals(AgentExit.LOGIN_FAILED, output.exitCode)
        assertTrue("不一致" in output.stderr)
    }

    @Test
    fun unknownTargetsAreRejectedBeforeAnyIo() {
        val output = runCacheUpdate(listOf("shell"), platform(remember = true))
        assertEquals(AgentExit.USAGE, output.exitCode)
        assertTrue("physicsLab" in output.stderr)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun missingCacheIsReportedWithoutCreatingDatabase() {
        val output = runCacheUpdate(emptyList(), platform(remember = true))
        assertEquals(AgentExit.NO_DATA, output.exitCode)
        assertFalse(File(directory, "bjtuselfservice_cache.db").exists())
    }

    @Test
    fun invalidOptionsNeverReadSecurityOrCreateLock() {
        val platform = AgentCliPlatform(directory, { error("must not read key") }, { error("must not read account") })
        assertEquals(AgentExit.USAGE, runCacheUpdate(listOf("--password", "secret"), platform).exitCode)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun failedGateReleasesLockAndPreservesCachedProfile() {
        writeCache()
        runCacheUpdate(emptyList(), platform(remember = false))
        assertNotNull(AgentAppLock.tryAcquire(directory)).close()
        val store = openExistingCacheStoreReadOnly(File(directory, "bjtuselfservice_cache.db"), key.copyOf())
        try { assertEquals("20990001", store.cachedProfile("20990001")?.studentId) } finally { store.close() }
    }

    // endregion

    private fun writeCache() {
        val handle = openDesktopCacheStore(directory, JvmAesCacheValueProtector(key.copyOf()))
        handle.store.saveCachedProfile(StudentProfile("测试同学", "20990001", "本科生", "测试学院"))
        handle.store.close()
    }

    private fun platform(remember: Boolean, vault: FakeVault = FakeVault(Credentials("20990001", "secret"))) = AgentCliPlatform(
        cacheDirectory = directory,
        loadCacheKey = { key.copyOf() },
        rememberedUsername = { "20990001" },
        securityStore = { AccountSecurityStore(vault, FakePreferences(remember)) },
        captchaRecognizer = { error("不应走到验证码识别") },
    )

    private class FakeVault(private val credentials: Credentials?) : CredentialVault {
        var cleared = false
        override suspend fun save(credentials: Credentials) = Unit
        override suspend fun load(): Credentials? = credentials
        override suspend fun clear() {
            cleared = true
        }
    }

    private class FakePreferences(private val remember: Boolean) : AccountPreferences {
        override suspend fun shouldRememberCredentials(): Boolean = remember
        override suspend fun setShouldRememberCredentials(enabled: Boolean) = Unit
        override suspend fun clearRememberCredentialsSetting() = Unit
    }
}

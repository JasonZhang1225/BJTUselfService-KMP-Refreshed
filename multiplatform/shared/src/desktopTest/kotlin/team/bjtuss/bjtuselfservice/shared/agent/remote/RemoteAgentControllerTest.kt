package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import team.bjtuss.bjtuselfservice.shared.agent.loadAgentSnapshot
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

private class MemorySecrets : RemoteSecretStore {
    var token: String? = null
    var access: String? = null
    override suspend fun loadDeviceToken() = token
    override suspend fun saveDeviceToken(token: String) { this.token = token }
    override suspend fun loadPlatformAccess() = access
    override suspend fun savePlatformAccess(value: String?) { access = value }
    override suspend fun clear() { token = null; access = null }
}

class RemoteAgentControllerTest {
    private val directory: File = Files.createTempDirectory("remote-controller").toFile()
    private val cache = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { (it * 3).toByte() })).store
    private val secrets = MemorySecrets()
    private val client = FakeSiteClient()
    private val executor = FakeExecutor()
    private val controller = RemoteAgentController(RemoteAgentStore(cache), secrets, executor,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), clock = { 1_800_000_000L }, clientFactory = { client })

    @AfterTest
    fun cleanUp() {
        controller.close()
        cache.close()
        directory.deleteRecursively()
    }

    @Test
    fun pairingRequiresHttpsCodeConsentAndLogin() = runBlocking {
        assertNotNull(controller.pair("http://site.example", "ABCD2345", null, RemoteScope.DEFAULT, consent = true))
        assertNotNull(controller.pair("https://site.example", "abc", null, RemoteScope.DEFAULT, consent = true))
        assertNotNull(controller.pair("https://site.example", "ABCD2345", null, RemoteScope.DEFAULT, consent = false))
        executor.account = null
        assertTrue(controller.pair("https://site.example", "ABCD2345", null, RemoteScope.DEFAULT, consent = true)!!.contains("登录"))
        assertNull(client.lastPair, "校验失败时不联系 Site")
        assertNull(secrets.token)
    }

    @Test
    fun successfulPairingStoresTokenOnlyInSecretsAndEnables() = runBlocking {
        assertNull(controller.pair("https://site.example/", "abcd-2345", "platform-secret", RemoteScope.DEFAULT, consent = true))
        val request = client.lastPair!!
        assertEquals("ABCD2345", request.pairingCode)
        assertTrue(request.accountBindingId.startsWith("bind_"))
        assertFalse("20990001" in request.accountBindingId, "绑定标识不含学号")
        assertEquals("bjtud_testtokentesttokentesttoken", secrets.token)
        assertEquals("platform-secret", secrets.access)
        val config = controller.config.value
        assertTrue(config.runnable)
        assertEquals("https://site.example", config.siteUrl)
        assertEquals("20990001", config.accountScope)
        val persisted = RemoteAgentStore(cache).loadConfig().toString()
        assertFalse("bjtud_" in persisted, "令牌不写进普通设置")
        assertTrue(controller.keepRunningInBackground)
    }

    @Test
    fun scopesIntervalsAndUnpair() = runBlocking {
        controller.pair("https://site.example", "ABCD2345", null, RemoteScope.DEFAULT, consent = true)
        assertNull(controller.setScopes(setOf(RemoteScope.SCHEDULE, RemoteScope.GRADES)))
        assertEquals(listOf("schedule", "grades"), client.scopeUpdates.last())
        assertNotNull(controller.setScopes(emptySet()))
        val effective = controller.setIntervals(10, 0)
        assertEquals(60, effective.uploadIntervalSeconds)
        assertEquals(1, effective.pollIntervalSeconds)
        assertTrue(controller.unpair())
        assertNull(secrets.token)
        assertFalse(controller.config.value.paired)
        assertEquals(60, controller.config.value.uploadIntervalSeconds, "解除绑定保留间隔偏好")
        assertFalse(controller.keepRunningInBackground)
    }

    @Test
    fun snapshotContentFollowsScopesAndCarriesRealSyncTimes() {
        cache.saveCachedProfile(StudentProfile("测试同学", "20990001", "本科生", "测试学院"))
        val snapshot = loadAgentSnapshot(cache, "20990001", LocalDate(2026, 10, 21))
        val content = buildRemoteSnapshotContent(snapshot, setOf(RemoteScope.SCHEDULE, RemoteScope.MAIL),
            mapOf("courses" to RemoteSourceState("failed", 1, "network")),
            { source -> if (source == RefreshSource.COURSES) 1_700_000_000 else null },
            Instant.fromEpochSeconds(1_792_540_800))
        assertEquals(setOf("schedule"), content.data.keys, "未授权范围与没有数据的 mail 不出现")
        assertEquals(RemoteSourceState("failed", 1_700_000_000, "network"), content.sources["courses"])
        assertEquals("not_refreshed", content.sources["mailUnread"]?.reason)
        assertNull(content.sources["grades"])
        assertFalse("20990001" in content.data.toString(), "快照不含学号")
    }
}

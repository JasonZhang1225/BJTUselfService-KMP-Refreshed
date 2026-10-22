package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshReport
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSourceResult
import team.bjtuss.bjtuselfservice.shared.sync.RefreshStatus

/** 手机/平板：不常驻、不轮询，只在 App 刷新后上传；配对不按 Site 模板版本拦截。 */
class MobileRemoteAgentTest {
    private val directory: File = Files.createTempDirectory("remote-mobile").toFile()
    private val cache = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { 9 })).store
    private val client = FakeSiteClient()
    private val executor = FakeExecutor()
    private var token: String? = null
    private val secrets = object : RemoteSecretStore {
        override suspend fun loadDeviceToken() = token
        override suspend fun saveDeviceToken(token: String) { this@MobileRemoteAgentTest.token = token }
        override suspend fun loadPlatformAccess(): String? = null
        override suspend fun savePlatformAccess(value: String?) {}
        override suspend fun clear() { token = null }
    }
    private val controller = RemoteAgentController(RemoteAgentStore(cache), secrets, executor,
        CoroutineScope(SupervisorJob() + Dispatchers.Default), host = object : RemoteHostPlatform {
            override val deviceName = "Pixel 8（Android）"
            override val deviceKind = RemoteDeviceKind.MOBILE
        }, clock = { 1_800_000_000L }, clientFactory = { client }, secondMillis = 1)

    @AfterTest fun cleanUp() {
        controller.close()
        cache.close()
        directory.deleteRecursively()
    }

    @Test fun pairingDoesNotGateOnSiteTemplateVersion() = runBlocking {
        for (version in listOf("0.3.0", "1.8.6-Beta2", "1.8.6-KMP", "abc")) {
            client.lastPair = null
            client.healthResponse = RemoteCall.Ok(RemoteHealth(1, version))
            assertNull(controller.pair(pairingFixture("https://site.example", "ABCD2345", "access"), RemoteScope.DEFAULT, consent = true), version)
            assertEquals(RemoteDeviceKind.MOBILE, client.lastPair?.deviceKind, version)
        }
    }

    @Test fun pairsAsMobileAndOnlyUploadsAfterRefresh() = runBlocking {
        assertNull(controller.pair(pairingFixture("https://site.example", "ABCD2345", "fixture-access"), RemoteScope.DEFAULT, consent = true))
        assertEquals(RemoteDeviceKind.MOBILE, client.lastPair!!.deviceKind)
        assertFalse(controller.keepRunningInBackground)
        controller.start()
        delay(200)
        assertEquals(0, client.polls.get(), "手机不轮询即时任务")
        assertTrue(client.snapshots.isEmpty(), "没有定时上传")
        val report = RefreshReport("20990001", RefreshOrigin.APP, 10, 20,
            listOf(RefreshSourceResult(RefreshSource.HOMEWORK, RefreshStatus.SUCCESS, fetchedAtEpochSeconds = 20)))
        assertIs<RemoteUploadOutcome.Uploaded>(controller.uploadAfterRefresh(report))
        assertEquals(1, client.snapshots.size)
        assertEquals(0, executor.refreshes.get(), "上传沿用刚完成的刷新，不再刷新一次")
    }

    @Test fun setupPromptUsesCurrentTemplateRelease() {
        assertTrue(REMOTE_SETUP_PROMPT.contains("使用模板 $REMOTE_TEMPLATE_RELEASE"))
        assertFalse(REMOTE_SETUP_PROMPT.contains("0.5.0"))
    }

    @Test fun pairAuthorizationFailureDoesNotAskForTemplateUpgrade() = runBlocking {
        client.pairResponse = RemoteCall.Failure(RemoteFailureKind.FORBIDDEN, "platform_auth_required")
        val message = controller.pair(pairingFixture("https://site.example", "ABCD2345", "fixture-access"), RemoteScope.DEFAULT, true)!!
        assertTrue(message.contains("连接授权"))
        assertFalse(message.contains("升级"))
    }
}

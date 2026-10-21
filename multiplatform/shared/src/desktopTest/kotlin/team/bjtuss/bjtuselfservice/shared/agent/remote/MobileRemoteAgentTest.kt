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

/** 手机/平板：不常驻、不轮询，只在 App 刷新后上传；Site 不支持多设备时拒绝配对，避免顶掉电脑。 */
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

    @Test fun oldSiteTemplateIsRejectedBeforePairing() = runBlocking {
        client.healthResponse = RemoteCall.Ok(RemoteHealth(1, "0.3.0"))
        val message = controller.pair("https://site.example", "ABCD2345", "access", RemoteScope.DEFAULT, consent = true)
        assertTrue(message!!.contains(REMOTE_MULTI_DEVICE_TEMPLATE))
        assertNull(client.lastPair, "旧模板会撤销电脑，不能发出配对请求")
    }

    @Test fun pairsAsMobileAndOnlyUploadsAfterRefresh() = runBlocking {
        assertNull(controller.pair("https://site.example", "ABCD2345", null, RemoteScope.DEFAULT, consent = true))
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

    @Test fun templateVersionComparison() {
        assertTrue(remoteTemplateAtLeast("0.4.0", "0.4.0"))
        assertTrue(remoteTemplateAtLeast("0.10.1", "0.4.0"))
        assertFalse(remoteTemplateAtLeast("0.3.9", "0.4.0"))
        assertFalse(remoteTemplateAtLeast(null, "0.4.0"))
        assertFalse(remoteTemplateAtLeast("abc", "0.4.0"))
    }
}

package team.bjtuss.bjtuselfservice.shared.agent.remote

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore

class RemotePairingAccessTest {
    private class Secrets : RemoteSecretStore {
        var access: String? = "fixture-access"
        var site: String? = "https://site.example"
        override suspend fun loadDeviceToken(): String? = null
        override suspend fun saveDeviceToken(token: String) {}
        override suspend fun loadPlatformAccess() = access
        override suspend fun savePlatformAccess(value: String?) { access = value }
        override suspend fun loadPlatformAccessSiteUrl() = site
        override suspend fun savePlatformAccessSiteUrl(siteUrl: String?) { site = siteUrl }
        override suspend fun clear() { access = null; site = null }
    }

    @Test fun longCodeReplacesAccessForItsSiteAndPairFailureIsNotReportedAsRevocation() = runBlocking {
        val directory = Files.createTempDirectory("pairing-access").toFile()
        val cache = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { 7 })).store
        val secrets = Secrets()
        val client = FakeSiteClient()
        val controller = RemoteAgentController(RemoteAgentStore(cache), secrets, FakeExecutor(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default), clock = { 1_800_000_000L }, clientFactory = { client })
        try {
            client.pairResponse = RemoteCall.Failure(RemoteFailureKind.UNAUTHORIZED)
            val message = controller.pair(pairingFixture("https://site.example/", "ABCD2345", "fixture-access"), RemoteScope.DEFAULT, true)!!
            assertEquals("fixture-access", secrets.access)
            assertTrue(message.contains("重新生成完整配对信息"))
            assertFalse(message.contains("撤销"))
            controller.pair(pairingFixture("https://other.example", "ABCD2345", "fixture-access"), RemoteScope.DEFAULT, true)
            assertEquals("fixture-access", secrets.access, "只使用新长码的授权")
            assertEquals("https://other.example", secrets.site)
            controller.pair(pairingFixture("https://other.example", "ABCD2345", "replacement"), RemoteScope.DEFAULT, true)
            assertEquals("replacement", secrets.access)
            assertEquals("https://other.example", secrets.site)
            client.pairResponse = RemoteCall.Failure(RemoteFailureKind.FORBIDDEN, "platform_auth_required")
            val accessError = controller.pair(pairingFixture("https://other.example", "ABCD2345", "fixture-access"), RemoteScope.DEFAULT, true)!!
            assertTrue(accessError.contains("登录 Site"))
            assertTrue(accessError.contains("完整配对信息"))
        } finally {
            controller.close(); cache.close(); directory.deleteRecursively()
        }
    }
}

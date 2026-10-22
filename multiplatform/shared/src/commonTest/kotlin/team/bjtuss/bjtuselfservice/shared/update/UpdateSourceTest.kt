package team.bjtuss.bjtuselfservice.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import kotlin.test.*

class UpdateSourceTest {
    private val github = """[{"tag_name":"v9.0.0-KMP","html_url":"https://github.com/release","prerelease":true}]"""
    private fun mirror(tag: String = "v9.0.0-KMP", ready: Boolean = true, missing: String = "", unsafe: Boolean = false): String {
        val files = listOf("apk", "ipa", "dmg", "msi").filter { it != missing }.joinToString(",") { ext ->
            val url = if (unsafe) "https://example.com/app.$ext" else "https://gitee.com/${AppUpdateChecker.GITEE_REPO}/releases/download/$tag/app.$ext"
            """{"name":"app.$ext","browser_download_url":"$url"}"""
        }
        val body = if (ready) "Notes ${AppUpdateChecker.MIRROR_READY}" else "Notes"
        return """{"tag_name":"$tag","body":"$body","created_at":"2026-10-22T15:00:00Z","assets":[$files]}"""
    }
    private class Transport(val respond: suspend (SchoolHttpRequest) -> SchoolHttpResponse) : SchoolHttpTransport {
        val urls = mutableListOf<String>()
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("Must use public client")
        override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
            urls += request.url
            return respond(request)
        }
        override fun clearSession() = Unit
    }
    private fun response(body: String, status: Int = 200) = SchoolHttpResponse(status, "https://example.com", body = body.encodeToByteArray())

    @Test fun automaticUsesGithubAndPreservesEmergencyPrerelease() = runBlocking {
        val transport = Transport { response(github) }
        val release = assertIs<AppUpdateChecker.Result.Success>(AppUpdateChecker.fetchLatest(transport)).release
        assertEquals(UpdateSource.GITHUB, release.source)
        assertTrue(release.prerelease)
        assertEquals(1, transport.urls.size)
    }

    @Test fun automaticFallsBackForHttpParseEmptyAndNetworkFailures() = runBlocking {
        for (failure in listOf<suspend () -> SchoolHttpResponse>(
            { response("rate limited", 403) }, { response("bad gateway", 502) },
            { response("<html>login</html>") }, { response("[]") }, { throw IllegalStateException("offline") },
        )) {
            val transport = Transport { if (it.url.contains("api.github.com")) failure() else response("[${mirror()}]") }
            val release = assertIs<AppUpdateChecker.Result.Success>(AppUpdateChecker.fetchLatest(transport)).release
            assertEquals(UpdateSource.GITEE, release.source)
            assertEquals("Notes", release.body)
            assertEquals("https://gitee.com/${AppUpdateChecker.GITEE_REPO}/releases/tag/v9.0.0-KMP", release.htmlUrl)
            assertEquals(2, transport.urls.size)
        }
    }

    @Test fun explicitModesNeverContactOtherSource() = runBlocking {
        for (source in listOf(UpdateSource.GITHUB, UpdateSource.GITEE)) {
            val transport = Transport { response("failure", 500) }
            assertIs<AppUpdateChecker.Result.Unavailable>(AppUpdateChecker.fetchLatest(transport, source))
            assertEquals(1, transport.urls.size)
            assertEquals(source == UpdateSource.GITHUB, transport.urls.single().contains("api.github.com"))
        }
    }

    @Test fun incompleteMirrorUnsafeLinksAndInvalidTagsAreIgnored() = runBlocking {
        for (body in listOf(mirror(ready = false), mirror(missing = "msi"), mirror(unsafe = true), mirror(tag = "not-a-version"))) {
            assertIs<AppUpdateChecker.Result.Unavailable>(AppUpdateChecker.fetchLatest(Transport { response("[$body]") }, UpdateSource.GITEE))
        }
    }

    @Test fun oldMirrorRetriedLaterCannotReplaceNewerRelease() = runBlocking {
        val transport = Transport { response("[${mirror("v8.0.0-KMP")},${mirror("v10.0.0-EmergencyFix1")},${mirror("v9.0.0-KMP")},${mirror("v11.0.0-KMP", ready = false)}]") }
        val release = assertIs<AppUpdateChecker.Result.Success>(AppUpdateChecker.fetchLatest(transport, UpdateSource.GITEE)).release
        assertEquals("v10.0.0-EmergencyFix1", release.tagName)
    }

    @Test fun githubDraftsAreSkippedAsBefore() = runBlocking {
        val transport = Transport { response("""[{"tag_name":"v99","html_url":"https://example.com","draft":true},${github.removePrefix("[").removeSuffix("]")}]""") }
        assertEquals("v9.0.0-KMP", assertIs<AppUpdateChecker.Result.Success>(AppUpdateChecker.fetchLatest(transport)).release.tagName)
    }

    @Test fun githubTimeoutFallsBackAfterFiveSeconds() = runBlocking {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val transport = Transport {
            if (it.url.contains("api.github.com")) kotlinx.coroutines.delay(60_000L)
            response("[${mirror()}]")
        }
        assertEquals(UpdateSource.GITEE, assertIs<AppUpdateChecker.Result.Success>(AppUpdateChecker.fetchLatest(transport)).release.source)
        assertEquals(2, transport.urls.size)
        assertTrue(started.elapsedNow().inWholeMilliseconds in 4_900L..10_000L)
    }

    @Test fun cancellationNeverTriggersFallback() = runBlocking {
        val transport = Transport { throw CancellationException("closed") }
        assertFailsWith<CancellationException> { AppUpdateChecker.fetchLatest(transport) }
        assertEquals(1, transport.urls.size)
    }
}

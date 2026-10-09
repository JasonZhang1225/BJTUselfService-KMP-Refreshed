package team.bjtuss.bjtuselfservice.shared.feature.citel

import kotlinx.coroutines.*
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.network.*

class CitelFetchConcurrencyTest {
    @Test fun detailReadsUseTwoSlotsAndPreserveTaskOrderWithoutLogin() = runBlocking { exercise(false) }
    @Test fun simultaneousExpiredReadsShareOneRecovery() = runBlocking { exercise(true) }
    private suspend fun exercise(expire: Boolean) = coroutineScope {
        var active = 0
        var maximum = 0
        var loginPosts = 0
        var loggedIn = !expire
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
                val loginForm = "<form action='$CITEL_BASE/login/index.php'><input type='password' name='password'><input name='logintoken' value='fixture'></form>"
                if (request.url.contains("/login/") && request.method == SchoolHttpMethod.GET) {
                    return SchoolHttpResponse(200, request.url, body = loginForm.encodeToByteArray())
                }
                var finalUrl = request.url
                var body = when {
                    request.url.contains("/my/") -> "\"sesskey\":\"fixture\""
                    request.url.contains("/lib/ajax/") -> """[{"data":{"courses":[{"id":1,"fullname":"fixture"}],"nextoffset":1}}]"""
                    request.url.contains("/course/view.php") -> "<div id='region-main'>" + (1..3).joinToString("") {
                        "<li class='activity'><a href='$CITEL_BASE/mod/assign/view.php?id=$it'><span class='instancename'>Task $it</span></a></li>"
                    } + "</div>"
                    else -> "<div id='region-main'><div data-region='activity-information'>fixture</div></div>"
                }
                if (request.url.contains("/login/") && request.method == SchoolHttpMethod.POST) {
                    loginPosts++
                    loggedIn = true
                    finalUrl = "$CITEL_BASE/my/"
                }
                if (request.url.contains("/mod/assign/view.php")) {
                    val expired = !loggedIn
                    active++
                    maximum = maxOf(maximum, active)
                    if (active == 2) bothStarted.complete(Unit)
                    try { release.await() } finally { active-- }
                    if (expired) {
                        finalUrl = "$CITEL_BASE/login/index.php"
                        body = loginForm
                    }
                }
                return SchoolHttpResponse(200, finalUrl, body = body.encodeToByteArray())
            }
            override fun clearSession() = Unit
        }
        val remote = CitelRemote(transport)
        val fetch = async { remote.fetch(Credentials("fixture", "fixture")) }
        try {
            withTimeout(3_000) { bothStarted.await() }
            assertEquals(2, active)
            release.complete(Unit)
            val tasks = withTimeout(3_000) { fetch.await() }
            assertEquals(listOf(1, 2, 3), tasks.map { it.id })
            assertEquals(2, maximum)
            assertEquals(if (expire) 1 else 0, loginPosts)
        } finally { release.complete(Unit) }
    }
}

package team.bjtuss.bjtuselfservice.shared.feature.citel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.network.*
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

class CitelTest {
    @Test fun interruptedAppEntryCanRetryTheSameGeneration() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            var calls = 0
            val remote = object : CitelDataSource {
                override suspend fun fetch(credentials: Credentials): List<CitelTask> {
                    if (++calls == 1) kotlinx.coroutines.awaitCancellation()
                    return emptyList()
                }
            }
            val model = CitelModel("fixture", cache, MemoryVault(), remote)
            model.saveAccount("fixture", "fixture", true)
            model.setEnabled(true)
            val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch { model.refreshForAppEntry(0) }
            kotlinx.coroutines.yield()
            job.cancel()
            job.join()
            model.refreshForAppEntry(0)
            assertEquals(2, calls)
            assertFalse(model.state.value.refreshing)
            assertFalse(model.state.value.failed)
            assertNotNull(model.state.value.lastSync)
        } finally { cache.close() }
    }

    @Test fun saveAndSyncReportsFailureAndKeepsFeatureSwitchOff() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            var fail = false
            val remote = object : CitelDataSource {
                override suspend fun fetch(credentials: Credentials): List<CitelTask> {
                    if (fail) throw CitelFailure("同步测试失败")
                    return emptyList()
                }
            }
            val vault = MemoryVault()
            val model = CitelModel("fixture", cache, vault, remote)
            assertTrue(model.saveAccountAndSync("fixture", "fixture"))
            assertFalse(model.state.value.enabled)
            model.clearConfiguration()
            assertFalse(model.state.value.configured)
            assertEquals("", model.state.value.username)
            assertNull(vault.value)
            assertTrue(model.saveAccountAndSync("fixture", "fixture"))
            fail = true
            assertFalse(model.saveAccountAndSync("fixture", ""))
            assertTrue(model.state.value.configured)
            assertTrue(model.state.value.failed)
            assertEquals("同步测试失败", model.state.value.message)
        } finally { cache.close() }
    }
    @Test fun appEntrySynchronizesOncePerForegroundGeneration() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            var calls = 0
            val remote = object : CitelDataSource {
                override suspend fun fetch(credentials: Credentials): List<CitelTask> { calls++; return emptyList() }
            }
            val model = CitelModel("fixture", cache, MemoryVault(), remote)
            model.saveAccount("fixture", "fixture", true)
            model.setEnabled(true)
            model.refreshForAppEntry(0)
            model.refreshForAppEntry(0)
            assertEquals(1, calls)
            model.refreshForAppEntry(1)
            model.refreshForAppEntry(1)
            assertEquals(2, calls)
            model.setEnabled(false)
            model.refreshForAppEntry(2)
            assertEquals(2, calls)
        } finally { cache.close() }
    }
    @Test fun filteringKeepsUndatedTasksAndSortsBothDirections() {
        val tasks = listOf(
            CitelTask(1, 1, "A", "Expired", "", false, dueTime = 90),
            CitelTask(2, 1, "A", "Undated", "", false),
            CitelTask(3, 2, "B", "Submitted", "", false, dueTime = 150, submitted = true),
            CitelTask(4, 1, "A", "Upcoming", "", false, dueTime = 120),
        )
        assertEquals(listOf(4, 2), filteredCitelTasks(tasks, true, true, 1, 1, 100).map { it.id })
        assertEquals(listOf(3, 4, 1, 2), filteredCitelTasks(tasks, false, false, null, 2, 100).map { it.id })
        assertEquals(listOf(1, 2, 3, 4), filteredCitelTasks(tasks, false, false, null, 0, 100).map { it.id })
    }
    @Test fun accountSettingsDoNotEnableTheFeatureAndMasterPreferenceSurvivesRestart() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            var calls = 0
            val remote = object : CitelDataSource { override suspend fun fetch(credentials: Credentials): List<CitelTask> { calls++; return emptyList() } }
            val vault = MemoryVault()
            val model = CitelModel("fixture", cache, vault, remote)
            model.initialize()
            assertFalse(model.state.value.configured)
            model.saveAccount("fixture", "fixture-password", true)
            assertFalse(model.state.value.enabled)
            assertTrue(model.state.value.configured)
            val restored = CitelModel("fixture", cache, vault, remote)
            restored.initialize()
            assertTrue(restored.state.value.configured)
            assertEquals(0, calls)
            cache.savePreferences(team.bjtuss.bjtuselfservice.shared.cache.AppPreferences(citelEnabled = true))
            assertTrue(cache.preferences().isCitelEnabled)
            model.setEnabled(true)
            model.refresh()
            assertEquals(1, calls)
            model.setEnabled(false)
            model.refresh()
            assertEquals(1, calls)
        } finally { cache.close() }
    }

    @Test fun accountSettingsHaveTheirOwnNativeRouteAndTitle() {
        assertTrue(team.bjtuss.bjtuselfservice.shared.isNativeDetailRoute("CITEL_SETTINGS"))
        assertEquals("CITEL 账号设置", team.bjtuss.bjtuselfservice.shared.feature.shell.nativeRouteTitle("CITEL_SETTINGS"))
    }
    private val course = CitelCourse(173, "测试课程")
    private val task = CitelTask(9265, 173, course.name, "测试上机作业", "$CITEL_BASE/mod/programming/view.php?id=9265", true)
    private val due = parseCitelTime("Sunday, 18 October 2026, 11:59 PM")!!
    private val courseHtml = """<section id="region-main"><li class="activity"><a href="$CITEL_BASE/mod/programming/view.php?id=9265"><span class="instancename">测试上机作业<span class="accesshide"> Programming Practice</span></span></a></li></section>"""
    private val indexHtml = """<section id="region-main"><table><tbody><tr><td>章节</td><td><a href="view.php?id=9265">测试上机作业</a></td><td>AC: Accepted</td><td>g++</td><td>10</td><td>1</td><td>26-09-15 18:08-26-10-18 23:59<br><strong>26-10-18 23:59+</strong></td></tr></tbody></table></section>"""
    private val detailHtml = """<section id="region-main"><h1 class="name">测试上机作业</h1><div>Grade: 10 / Discount: 1</div><div id="time-table"><table><tr><th>Time Discount</th><td>Sunday, 18 October 2026, 11:59 PM</td></tr><tr><th>Close Time</th><td>Sunday, 18 October 2026, 11:59 PM</td></tr></table><p>Allow late: Yes</p></div></section>"""

    @Test fun datesUseBeijingAndHandleNoonMidnightAndChinese() {
        assertEquals(due, parseCitelTime("26-10-18 23:59"))
        assertEquals(due, parseCitelTime("2026年10月18日 晚上11:59"))
        assertEquals("2026-09-09 00:00", citelDateText(parseCitelTime("Wednesday, 9 September 2026, 12:00 AM")!!))
        assertEquals("2026-09-09 12:00", citelDateText(parseCitelTime("Wednesday, 9 September 2026, 12:00 PM")!!))
        assertNull(parseCitelTime("2026年13月99日 11:59"))
    }
    @Test fun courseAndTaskSelectorsMatchObservedMoodleMarkup() {
        assertEquals(listOf(course), parseCitelCourses("""<main id="region-main"></main><nav><a href="$CITEL_BASE/course/view.php?id=173">测试课程</a></nav>"""))
        assertEquals(listOf(task), parseCitelTasks(courseHtml, course))
        assertFailsWith<CitelFailure> { parseCitelTasks("<html>error</html>", course) }
    }
    @Test fun programmingSeparatesDiscountCloseAndCurrentFactor() {
        val parsed = parseCitelTaskDetail(detailHtml, task)
        assertEquals(due, parsed.discountTime)
        assertEquals(due, parsed.dueTime)
        assertEquals(1.0, parsed.discount)
        assertEquals(true, parsed.allowLate)
        assertEquals("10", parsed.grade)
        assertFalse(parsed.submitted) // Grade 是题目分值，不能当成个人成绩。
        assertTrue(parsed.deadlineStatus(due).contains("允许迟交"))
    }
    @Test fun missingDetailTimeTableUsesProgrammingIndexWithoutInventingFactor() {
        val result = parseCitelProgrammingResults(indexHtml).getValue(task.id)
        val indexed = task.copy(openTime = result.times[0], discountTime = result.times[1], dueTime = result.times[2],
            submitted = result.accepted, status = result.status, allowLate = result.allowLate)
        val parsed = parseCitelTaskDetail("""<section id="region-main"><h1 class="name">测试上机作业</h1></section>""", indexed)
        assertEquals(due, parsed.dueTime)
        assertEquals(due, parsed.discountTime)
        assertNull(parsed.discount)
        assertTrue(parsed.submitted)
        assertEquals(true, parsed.allowLate)
    }
    @Test fun writtenAssignmentReadsDatesAndSubmissionStatus() {
        val written = task.copy(programming = false)
        val parsed = parseCitelTaskDetail("""<section id="region-main"><div data-region="activity-information"><div data-region="activity-dates"><div><strong>Due:</strong> Sunday, 18 October 2026, 11:59 PM</div></div></div><div class="submissionstatustable"><table><tr><th>Submission status</th><td>Submitted for grading</td></tr></table></div></section>""", written)
        assertEquals(due, parsed.dueTime)
        assertTrue(parsed.submitted)
        assertNull(parsed.discountTime)
    }
    @Test fun agendaPreservesDistinctNodesAndCoalescesSameInstant() {
        assertEquals(1, citelAgendaEvents(listOf(task.copy(dueTime = due, discountTime = due))).size)
        val events = citelAgendaEvents(listOf(task.copy(dueTime = due, discountTime = due - 3600, submitted = true)))
        assertEquals(2, events.size)
        assertTrue(events.all { it.submitted && it.id.startsWith("citel-") })
    }
    @Test fun agendaShowsStartDiscountAndDeadlineAsSeparateKinds() {
        val events = citelAgendaEvents(listOf(task.copy(openTime = due - 86400, discountTime = due - 3600, dueTime = due)))
        assertEquals(listOf("START", "DISCOUNT", "DEADLINE"), events.map { it.kind.name })
        assertEquals(3, events.size)
    }
    @Test fun urlValidationAndLoggingProtectCredentials() {
        assertTrue(isCitelUrl(task.url))
        listOf("http://citel.bjtu.edu.cn/mlsv3/my/", "https://citel.bjtu.edu.cn.evil.test/mlsv3/my/", "https://evil.test/mlsv3/my/", "https://citel.bjtu.edu.cn:444/mlsv3/my/").forEach { assertFalse(isCitelUrl(it)) }
        val request = SchoolHttpRequest(SchoolHttpMethod.POST, "$CITEL_BASE/login/index.php", formFields = mapOf("username" to "secret-user", "password" to "secret-password", "logintoken" to "secret-token"))
        listOf("secret-user", "secret-password", "secret-token").forEach { assertFalse(request.toString().contains(it)) }
    }

    @Test fun reloginOnceWhenAnIndividualGetExpiresAndUsesFreshToken() = runBlocking {
        val transport = FixtureTransport(expireDetail = true)
        val tasks = CitelRemote(transport).fetch(Credentials("test-user", "test-password"))
        assertEquals(1, tasks.size)
        assertEquals(due, tasks.single().dueTime)
        assertEquals(1, transport.loginCalls)
        assertEquals(2, transport.detailCalls)
        assertEquals("fresh-token", transport.loginFields["logintoken"])
    }
    @Test fun persistentSessionFailureStopsAfterOneLogin() = runBlocking {
        val transport = FixtureTransport(expireDetail = true, alwaysExpire = true)
        val error = assertFailsWith<CitelFailure> { CitelRemote(transport).fetch(Credentials("test-user", "test-password")) }
        assertTrue(error.sessionExpired)
        assertEquals(1, transport.loginCalls)
        assertEquals(2, transport.detailCalls)
    }
    @Test fun courseAjaxRecoversInvalidSesskeyOnceAndIncludesPastCourses() = runBlocking {
        val transport = FixtureTransport(expireAjax = true)
        val tasks = CitelRemote(transport).fetch(Credentials("test-user", "test-password"))
        assertEquals(1, tasks.size)
        assertEquals(1, transport.loginCalls)
        assertEquals(2, transport.ajaxCalls)
        assertTrue(transport.ajaxBodies.all { it.contains("\"classification\":\"all\"") })
    }
    @Test fun coursesAreReadAcrossPaginationBoundaries() = runBlocking {
        val transport = FixtureTransport(paginate = true)
        assertTrue(CitelRemote(transport).fetch(Credentials("test-user", "test-password")).isEmpty())
        assertEquals(2, transport.ajaxCalls)
        assertTrue(transport.ajaxBodies[1].contains("\"offset\":50"))
        assertEquals(51, transport.courseCalls)
    }
    @Test fun eightTenthsDiscountAndSeparateMidnightDatesMatchObservedPastCourse() {
        val parsed = parseCitelTaskDetail(detailHtml.replace("Discount: 1", "Discount: 0.8")
            .replaceFirst("Sunday, 18 October 2026, 11:59 PM", "Saturday, 18 July 2026, 12:00 AM")
            .replace("Sunday, 18 October 2026, 11:59 PM", "Saturday, 25 July 2026, 12:00 AM"), task)
        assertEquals(0.8, parsed.discount)
        assertEquals("2026-07-18 00:00", citelDateText(parsed.discountTime!!))
        assertEquals("2026-07-25 00:00", citelDateText(parsed.dueTime!!))
        assertEquals(2, citelAgendaEvents(listOf(parsed)).size)
    }
    @Test fun crossOriginRedirectIsRejectedBeforeSendingCredentials() = runBlocking {
        val transport = FixtureTransport(crossOrigin = true)
        assertFailsWith<CitelFailure> { CitelRemote(transport).fetch(Credentials("test-user", "test-password")) }
        assertEquals(0, transport.loginCalls)
    }
    @Test fun cacheSurvivesFailureAndIsIsolatedAcrossAccounts() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            val vault = MemoryVault()
            var fail = false
            val remote = object : CitelDataSource {
                override suspend fun fetch(credentials: Credentials): List<CitelTask> {
                    if (fail) throw CitelFailure("fixture failure")
                    return listOf(task)
                }
            }
            val model = CitelModel("account-a", cache, vault, remote)
            model.configure("test-user", "test-password", true, true)
            fail = true
            model.refresh()
            assertEquals(listOf(task), model.state.value.tasks)
            assertTrue(model.state.value.fromCache)
            assertTrue(model.state.value.failed)
            val restored = CitelModel("account-a", cache, vault, remote)
            restored.initialize()
            assertEquals(listOf(task), restored.state.value.tasks)
            val other = CitelModel("account-b", cache, MemoryVault(), remote)
            other.initialize()
            assertTrue(other.state.value.tasks.isEmpty())
            model.configure("different-user", "different-password", true, true)
            assertTrue(model.state.value.tasks.isEmpty())
            cache.clearAccount("account-a")
            assertEquals("true", cache.metadata("account-a", "citel.remember"))
            assertNull(cache.metadata("account-a", "citel.tasks"))
        } finally { cache.close() }
    }
    @Test fun passwordOptOutClearsVaultAndDisabledFeatureDoesNotFetch() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CacheDatabaseSql.Schema.create(driver)
        val cache = CacheStore(driver)
        try {
            val vault = MemoryVault()
            var calls = 0
            val remote = object : CitelDataSource { override suspend fun fetch(credentials: Credentials): List<CitelTask> { calls++; return emptyList() } }
            val model = CitelModel("scope", cache, vault, remote)
            model.configure("test-user", "test-password", true, true)
            assertNotNull(vault.value)
            val restored = CitelModel("scope", cache, vault, remote)
            restored.initialize()
            assertTrue(restored.state.value.fromCache) // 空结果也是缓存，重启不能冒充本轮已同步。
            model.configure("test-user", "", false, false)
            model.refresh()
            assertNull(vault.value)
            assertEquals(1, calls)
        } finally { cache.close() }
    }

    private class MemoryVault : CredentialVault {
        var value: Credentials? = null
        override suspend fun load() = value
        override suspend fun save(credentials: Credentials) { value = credentials }
        override suspend fun clear() { value = null }
    }
    private inner class FixtureTransport(val expireDetail: Boolean = false, val alwaysExpire: Boolean = false,
        val crossOrigin: Boolean = false, val expireAjax: Boolean = false, val paginate: Boolean = false) : SchoolHttpTransport {
        var loginCalls = 0
        var detailCalls = 0
        var loginFields: Map<String, String> = emptyMap()
        var ajaxCalls = 0
        var courseCalls = 0
        val ajaxBodies = mutableListOf<String>()
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = executeWithoutRedirects(request)
        override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse {
            assertTrue(isCitelUrl(request.url))
            if (crossOrigin) return SchoolHttpResponse(302, request.url, mapOf("Location" to listOf("https://evil.test/login")))
            val html = when {
                request.url.contains("/lib/ajax/service.php") -> {
                    ajaxCalls++
                    ajaxBodies += request.rawBody!!.decodeToString()
                    if (expireAjax && ajaxCalls == 1) """[{"error":true,"exception":{"errorcode":"invalidsesskey"}}]"""
                    else if (paginate) {
                        val ids = if (ajaxCalls == 1) (1..50) else (51..51)
                        """[{"error":false,"data":{"courses":[${ids.joinToString(",") { """{"id":$it,"fullname":"测试课程 $it"}""" }}],"nextoffset":${ids.last}}}]"""
                    } else """[{"error":false,"data":{"courses":[{"id":173,"fullname":"测试课程"}],"nextoffset":1}}]"""
                }
                request.method == SchoolHttpMethod.POST -> {
                    loginCalls++; loginFields = request.formFields
                    return SchoolHttpResponse(303, request.url, mapOf("Location" to listOf("$CITEL_BASE/my/")))
                }
                request.url.endsWith("/login/index.php") -> """<form action="$CITEL_BASE/login/index.php"><input name="logintoken" value="fresh-token"><input type="password" name="password"></form>"""
                request.url.contains("/my/") -> """<main id="region-main"><a href="$CITEL_BASE/course/view.php?id=173">测试课程</a></main><script>M.cfg={"sesskey":"fixtureKey"};</script>"""
                request.url.contains("/course/view") -> { courseCalls++; if (paginate) "<main id='region-main'></main>" else courseHtml }
                request.url.contains("/programming/index") -> indexHtml
                else -> {
                    detailCalls++
                    if (expireDetail && (alwaysExpire || detailCalls == 1)) return SchoolHttpResponse(200, "$CITEL_BASE/login/index.php", body = "<form><input type='password'></form>".encodeToByteArray())
                    detailHtml
                }
            }
            return SchoolHttpResponse(200, request.url, body = html.encodeToByteArray())
        }
        override fun clearSession() = Unit
    }
}

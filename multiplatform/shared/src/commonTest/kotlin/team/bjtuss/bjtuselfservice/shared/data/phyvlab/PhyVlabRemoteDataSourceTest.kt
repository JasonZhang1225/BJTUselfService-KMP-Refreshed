package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PhyVlabRemoteDataSourceTest {
    @Test
    fun fetchesCoursesOnlyFromAllowlistedHost() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://phyvlab.bjtu.edu.cn/my/courses.php",
                body = coursePageHtml().encodeToByteArray(),
            ),
        )
        val courses = SchoolPhyVlabRemoteDataSource(transport).fetchCourses()

        assertEquals(2, courses.size)
        assertEquals(72, courses[0].id)
        assertEquals("大学物理I_(2026春)", courses[0].name)
        assertEquals("https://phyvlab.bjtu.edu.cn/my/courses.php", transport.requests.single().url)
    }

    @Test
    fun treatsLoginRedirectAsSessionExpired() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://phyvlab.bjtu.edu.cn/login/index.php",
                body = "<html>login</html>".encodeToByteArray(),
            ),
        )
        val error = assertFailsWith<PhyVlabRemoteException> {
            SchoolPhyVlabRemoteDataSource(transport).fetchCourses()
        }
        assertEquals(PhyVlabRemoteFailure.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun treatsRenderedLoginPageAtRequestedUrlAsSessionExpired() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://phyvlab.bjtu.edu.cn/my/courses.php",
                body = """
                    <form action="/login/index.php">
                        <input placeholder="用户名或邮箱">
                        <input placeholder="密码">
                    </form>
                    <a href="/auth/oauth2/login.php?id=1">北京交通大学统一身份认证</a>
                """.trimIndent().encodeToByteArray(),
            ),
        )

        val error = assertFailsWith<PhyVlabRemoteException> {
            SchoolPhyVlabRemoteDataSource(transport).fetchCourses()
        }

        assertEquals(PhyVlabRemoteFailure.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun rejectsRedirectToForeignHost() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://example.com/not-phyvlab",
                body = "<html>external</html>".encodeToByteArray(),
            ),
        )
        val error = assertFailsWith<PhyVlabRemoteException> {
            SchoolPhyVlabRemoteDataSource(transport).fetchCourses()
        }
        assertEquals(PhyVlabRemoteFailure.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun repositoryMapsRemoteFailures() = runBlocking {
        val repository = DefaultPhyVlabRepository(FailingRemoteDataSource())
        val result = assertIs<PhyVlabCoursesResult.Failure>(repository.fetchCourses())
        assertEquals(PhyVlabSyncFailure.NETWORK, result.reason)
    }

    @Test
    fun fetchesCourseActivitiesFromCoursePage() = runBlocking {
        val course = PhyVlabCourse(
            id = 74,
            name = "物理实验I_(2026春)",
            category = "自然科学",
            progressPercent = 30,
            courseUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=74",
        )
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://phyvlab.bjtu.edu.cn/course/view.php?id=74",
                body = assignmentCoursePageHtml().encodeToByteArray(),
            ),
        )
        val activities = SchoolPhyVlabRemoteDataSource(transport).fetchCourseActivities(course)

        assertEquals(2, activities.size)
        assertEquals("01-03-分光计的调整和使用", activities[0].title)
        assertEquals(3697, activities[0].id)
        assertEquals("https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3697", activities[0].activityUrl)
        assertEquals("2026年03月14日 00:00", activities[0].openText)
        assertEquals("2026年03月19日 00:00", activities[0].dueText)
        assertEquals(1773849600L, activities[0].dueTimestamp)
        assertEquals(true, activities[0].completed)
        assertEquals("10-杨氏模量的静态法测量", activities[1].title)
        assertEquals("2026年03月21日 00:00", activities[1].dueText)
        assertEquals(false, activities[1].completed)
    }

    @Test
    fun fetchesEventsFromCalendarMonth() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://phyvlab.bjtu.edu.cn/calendar/view.php?view=month&time=1773446400",
                body = calendarMonthHtml().encodeToByteArray(),
            ),
        )
        val events = SchoolPhyVlabRemoteDataSource(transport).fetchEvents(1773446400L)

        assertEquals(3, events.size)
        assertEquals("chap1 已到期", events[0].title)
        assertEquals(1773849600L, events[0].dayTimestamp)
        assertEquals("https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689", events[0].eventUrl)
        assertEquals("03月19日", events[0].dateText)
        assertEquals("https://phyvlab.bjtu.edu.cn/calendar/view.php?view=month&time=1773446400", transport.requests.single().url)
    }

    @Test
    fun fetchesAssignmentDetailWithoutOpeningBrowser() = runBlocking {
        val activity = PhyVlabActivity(
            id = 3689,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "chap1",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
        )
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = activity.activityUrl,
                body = assignmentDetailHtml().encodeToByteArray(),
            ),
        )
        val detail = SchoolPhyVlabRemoteDataSource(transport).fetchAssignmentDetail(activity)

        assertEquals("已提交", detail.submissionStatus)
        assertEquals("88.0", detail.gradeText)
        assertEquals(listOf("chap1-report.pdf"), detail.submittedFiles.map { it.fileName })
        assertEquals(activity.activityUrl, transport.requests.single().url)
    }

    @Test
    fun followsReadOnlyEditPageToPrepareNativeUpload() = runBlocking {
        val activity = PhyVlabActivity(
            id = 3700,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap-2-3",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3700",
        )
        val transport = QueueTransport(
            SchoolHttpResponse(
                200,
                activity.activityUrl,
                body = """
                    <main><div id="intro">完成第二章练习</div>
                    <a href="/mod/assign/view.php?id=3700&amp;action=editsubmission">编辑提交</a></main>
                """.trimIndent().encodeToByteArray(),
            ),
            SchoolHttpResponse(
                200,
                "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3700&action=editsubmission",
                body = """
                    <form action="/mod/assign/view.php?id=3700&amp;action=editsubmission" method="post">
                      <input type="hidden" name="sesskey" value="abc123">
                      <input type="hidden" name="id" value="3700">
                      <input type="hidden" name="files_filemanager" value="0">
                      <div class="filemanager"></div>
                    </form>
                    <script>
                      M.form_filemanager.init({
                        "id": "id_files_filemanager",
                        "itemid": 0,
                        "contextid": 17,
                        "client_id": "client-3700",
                        "repositories": {"4": {"type": "upload"}}
                      });
                    </script>
                """.trimIndent().encodeToByteArray(),
            ),
        )

        val detail = SchoolPhyVlabRemoteDataSource(transport).fetchAssignmentDetail(activity)

        assertTrue(detail.canSubmit)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[1].url.contains("action=editsubmission"))
    }

    @Test
    fun keepsPrimaryDetailWhenOptionalEditPageIsUnavailable() = runBlocking {
        val activity = PhyVlabActivity(
            id = 3701,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap-2-3",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3701",
        )
        val transport = QueueTransport(
            SchoolHttpResponse(
                200,
                activity.activityUrl,
                body = """
                    <main>
                      <div id="intro">完成第二章练习</div>
                      <div class="submissionstatustable"><table>
                        <tr><th>提交状态</th><td>已提交</td></tr>
                      </table></div>
                    </main>
                """.trimIndent().encodeToByteArray(),
            ),
            SchoolHttpResponse(
                404,
                "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3701&action=editsubmission",
                body = "<html>not found</html>".encodeToByteArray(),
            ),
        )

        val detail = SchoolPhyVlabRemoteDataSource(transport).fetchAssignmentDetail(activity)

        assertEquals("完成第二章练习", detail.description)
        assertEquals("已提交", detail.submissionStatus)
        assertFalse(detail.canSubmit)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun submitsThroughMoodleDraftUploadAndAssignmentForm() = runBlocking {
        val activity = PhyVlabActivity(3700, 72, "测试课程", "测试作业", "作业", "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3700")
        val transport = QueueTransport(
            SchoolHttpResponse(200, activity.activityUrl, body = """<main id="region-main"><form><input name="action" value="editsubmission"></form></main>""".encodeToByteArray()),
            SchoolHttpResponse(200, "${activity.activityUrl}&action=editsubmission", body = """
                <form action="https://phyvlab.bjtu.edu.cn/mod/assign/view.php">
                    <input type="hidden" name="sesskey" value="abc123"><input type="hidden" name="id" value="3700">
                    <input type="hidden" name="action" value="savesubmission"><input type="hidden" name="files_filemanager" value="42">
                </form>
                <script>M.form_filemanager.init(Y,{"itemid":42,"client_id":"client-3700","context":{"id":17},"maxfiles":20,"maxbytes":1000,"accepted_types":[".pdf"],"list":[],"filepicker":{"repositories":{"4":{"type":"upload"}}}});</script>
                """.encodeToByteArray()),
            SchoolHttpResponse(200, "https://phyvlab.bjtu.edu.cn/repository/repository_ajax.php", body = "{\"success\":true}".encodeToByteArray()),
            SchoolHttpResponse(200, activity.activityUrl, body = "<html>success</html>".encodeToByteArray()),
            SchoolHttpResponse(200, activity.activityUrl, body = """<main id="region-main"><table class="submissionstatustable"><tr><th>Submission status</th><td>Draft (not submitted)</td></tr><tr><td><a href="/pluginfile.php/report.pdf">report.pdf</a></td></tr></table><form><input name="action" value="submit"></form></main>""".encodeToByteArray()),
        )
        SchoolPhyVlabRemoteDataSource(transport).submitAssignment(activity,
            listOf(HomeworkFileContent("report.pdf", "application/pdf", byteArrayOf(1, 2, 3))))
        assertEquals(5, transport.requests.size)
        val upload = transport.requests[2]
        assertEquals("upload", upload.formFields["action"])
        assertEquals("abc123", upload.formFields["sesskey"])
        assertEquals("4", upload.formFields["repo_id"])
        assertEquals("17", upload.formFields["ctx_id"])
        assertEquals("client-3700", upload.formFields["client_id"])
        assertEquals("repo_upload_file", upload.multipartFiles.single().fieldName)
        val save = transport.requests[3]
        assertEquals("42", save.formFields["files_filemanager"])
        assertEquals("savesubmission", save.formFields["action"])
        assertTrue(transport.requests.none { it.formFields["action"] == "confirmsubmit" })
        assertTrue("abc123" !in save.toString())
    }
    private fun coursePageHtml(): String = """
        <div data-region="course-content" data-course-id="72">
          <a class="aalink coursename" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=72">
            <span class="multiline">大学物理I_(2026春)</span>
          </a>
          <span class="categoryname">自然科学</span>
          <div class="progress-text">8% 已完成</div>
        </div>
        <div data-region="course-content" data-course-id="74">
          <a class="aalink coursename" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=74">
            <span class="multiline">物理实验I_(2026春)</span>
          </a>
          <span class="categoryname">自然科学</span>
          <div class="progress-text">30% 已完成</div>
        </div>
    """.trimIndent()

    private fun assignmentCoursePageHtml(): String = """
        <li class="activity activity-wrapper assign modtype_assign" id="module-3697" data-id="3697">
          <a href="https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3697" class="aalink stretched-link">
            <span class="instancename">01-03-分光计的调整和使用 <span class="accesshide">作业</span></span>
          </a>
          <button class="btn btn-success" data-action="toggle-manual-completion" data-toggletype="manual:undo" aria-label="完成">完成</button>
          <div data-region="activity-dates">
            <div><strong>打开：</strong> 2026年03月14日 Saturday 00:00</div>
            <div><strong>到期日：</strong> 2026年03月19日 Thursday 00:00</div>
          </div>
        </li>
        <li class="activity activity-wrapper assign modtype_assign" id="module-3700" data-id="3700">
          <a href="https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3700" class="aalink stretched-link">
            <span class="instancename">10-杨氏模量的静态法测量 <span class="accesshide">作业</span></span>
          </a>
          <button class="btn btn-outline-secondary" data-action="toggle-manual-completion" data-toggletype="manual:mark-done" aria-label="标记完成">标记完成</button>
          <div data-region="activity-dates">
            <div><strong>打开：</strong> 2026年03月14日 Saturday 00:00</div>
            <div><strong>到期日：</strong> 2026年03月21日 Saturday 00:00</div>
          </div>
        </li>
    """.trimIndent()

    private fun calendarMonthHtml(): String = """
        <table>
          <td class="day hasevent" data-day-timestamp="1773849600" data-title="03月19日 Thursday 事件">
            <li data-region="event-item">
              <a data-action="view-event" data-event-id="3115" href="https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689">
                <span class="eventname">chap1 已到期</span>
              </a>
            </li>
          </td>
          <td class="day hasevent" data-day-timestamp="1774022400" data-title="03月21日 Saturday 事件">
            <li data-region="event-item">
              <a data-action="view-event" data-event-id="3116" href="https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3690">
                <span class="eventname">Chap-2-3 已到期</span>
              </a>
            </li>
            <li data-region="event-item">
              <a data-action="view-event" data-event-id="3117" href="https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3692">
                <span class="eventname">Chap4-5 已到期</span>
              </a>
            </li>
          </td>
        </table>
    """.trimIndent()

    private fun assignmentDetailHtml(): String = """
        <main>
          <div id="intro"><p>实验报告：完成第一章测量误差分析。</p></div>
          <div class="submissionstatustable"><table>
            <tr><th>提交状态</th><td>已提交</td></tr>
            <tr><th>最后修改</th><td>2026年03月18日 Wednesday 21:30</td></tr>
          </table></div>
          <div class="gradingsummarytable"><table><tr><th>成绩</th><td><span class="grade">88.0</span></td></tr></table></div>
          <div class="submissionstatussubmitted"><div class="files"><a href="/pluginfile.php/17/chap1-report.pdf">chap1-report.pdf</a></div></div>
          <form action="/mod/assign/editsubmission.php" method="post">
            <input type="hidden" name="sesskey" value="abc123">
            <input type="hidden" name="id" value="3689">
            <input type="hidden" name="assignsubmission_file_filemanager" value="42">
            <div class="filemanager" data-itemid="42" data-contextid="17" data-clientid="client-3689" data-repositoryid="4"></div>
          </form>
        </main>
    """.trimIndent()

    private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return queue.removeFirst()
        }

        override fun clearSession() = Unit
    }
}

private class FailingRemoteDataSource : PhyVlabRemoteDataSource {
    override suspend fun fetchCourses(): List<PhyVlabCourse> =
        throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)

    override suspend fun fetchCourseActivities(course: PhyVlabCourse): List<PhyVlabActivity> =
        throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)

    override suspend fun fetchEvents(monthTimestampSeconds: Long): List<PhyVlabEvent> =
        throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)

    override suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetail =
        throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)

    override suspend fun submitAssignment(activity: PhyVlabActivity, files: List<HomeworkFileContent>) {
        throw PhyVlabRemoteException(PhyVlabRemoteFailure.NETWORK)
    }
}
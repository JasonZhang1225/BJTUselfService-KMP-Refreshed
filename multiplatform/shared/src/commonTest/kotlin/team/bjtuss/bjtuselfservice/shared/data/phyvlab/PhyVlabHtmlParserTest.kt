package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity

class PhyVlabHtmlParserTest {
    @Test
    fun parsesCourseCardsWithCategoryAndProgress() {
        val result = assertIs<PhyVlabParseResult.Success<List<team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse>>>(
            parsePhyVlabCourses(coursePageFixture()),
        )
        assertEquals(2, result.value.size)
        assertEquals(72, result.value[0].id)
        assertEquals("大学物理I_(2026春)", result.value[0].name)
        assertEquals("自然科学", result.value[0].category)
        assertEquals(8, result.value[0].progressPercent)
        assertEquals("https://phyvlab.bjtu.edu.cn/course/view.php?id=72", result.value[0].courseUrl)
        assertEquals("物理实验I_(2026春)", result.value[1].name)
        assertEquals(30, result.value[1].progressPercent)
    }

    @Test
    fun missingCardsReturnEmptyNotFailure() {
        assertEquals(
            PhyVlabParseResult.Success(emptyList<team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse>()),
            parsePhyVlabCourses("<html><body>尚未选课</body></html>"),
        )
    }

    @Test
    fun parsesServerRenderedCourseLinksWhenCardsAreMissing() {
        val result = assertIs<PhyVlabParseResult.Success<List<team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse>>>(
            parsePhyVlabCourses(
                """
                <nav>
                  <a class="list-group-item" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=72">大学物理I_(2026春)</a>
                  <a class="list-group-item" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=73">大学物理演示实验_(2026春)</a>
                  <a class="list-group-item" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=74">物理实验I_(2026春)</a>
                  <a class="dropdown-item" title="大学物理I_(2026春)" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=72">收藏此课程</a>
                </nav>
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(72, 73, 74), result.value.map { it.id })
        assertEquals("大学物理I_(2026春)", result.value.first().name)
    }

    @Test
    fun normalizesEnglishWeekdaysInActivityDates() {
        val activity = assertIs<PhyVlabParseResult.Success<List<PhyVlabActivity>>>(
            parsePhyVlabActivities(
                """
                <li id="module-3689" class="activity modtype_assign">
                  <a class="aalink" href="/mod/assign/view.php?id=3689">
                    <span class="instancename">chap1 <span class="accesshide">作业</span></span>
                  </a>
                  <div data-region="activity-dates">
                    <div><strong>打开：</strong>2026年03月12日 Thursday 00:00</div>
                    <div><strong>到期日：</strong>2026年03月19日 Thursday 00:00</div>
                  </div>
                </li>
                """.trimIndent(),
                courseId = 72,
                courseName = "大学物理I_(2026春)",
            ),
        ).value.single()

        assertEquals("2026年03月12日 00:00", activity.openText)
        assertEquals("2026年03月19日 00:00", activity.dueText)
        assertTrue(activity.openTimestamp != null)
        assertTrue(activity.dueTimestamp != null)
    }

    @Test
    fun malformedCardsFailWithoutLeakingPageText() {
        val result = assertIs<PhyVlabParseResult.Failure>(
            parsePhyVlabCourses(
                """<div data-region='course-content' data-course-id='72'></div>""",
            ),
        )
        assertEquals("courses", result.field)
    }

    @Test
    fun parsesAssignmentStatusGradeFeedbackAndSubmissionContext() {
        val activity = PhyVlabActivity(
            id = 3689,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "chap1",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689",
            dueText = "2026年03月19日 00:00",
        )
        val result = assertIs<PhyVlabParseResult.Success<PhyVlabParsedAssignmentPage>>(
            parsePhyVlabAssignmentPage(assignmentDetailFixture(), activity),
        )

        assertEquals("实验报告：完成第一章测量误差分析。", result.value.detail.description)
        assertEquals("已提交", result.value.detail.submissionStatus)
        assertEquals("2026年03月18日 21:30", result.value.detail.submissionDateText)
        assertEquals(1773840600L, result.value.detail.submissionDateTimestamp)
        assertEquals(null, result.value.detail.gradingStatus)
        assertEquals("88.0", result.value.detail.gradeText)
        assertEquals("数据处理规范，结论部分还可以更清晰。", result.value.detail.feedbackText)
        assertEquals(listOf("chap1-report.pdf"), result.value.detail.submittedFiles.map { it.fileName })
        assertTrue(result.value.detail.canSubmit)
        assertEquals("abc123", result.value.submissionContext?.sesskey)
        assertEquals("42", result.value.submissionContext?.draftItemId)
        assertEquals("17", result.value.submissionContext?.contextId)
        assertEquals("client-3689", result.value.submissionContext?.clientId)
    }

    @Test
    fun parsesLiveAssignmentStatusLabelAndFileManagerScriptMetadata() {
        val activity = PhyVlabActivity(
            id = 3700,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap-2-3",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3700",
        )
        val result = assertIs<PhyVlabParseResult.Success<PhyVlabParsedAssignmentPage>>(
            parsePhyVlabAssignmentPage(
                """
                <main>
                  <div id="intro">完成第二章练习</div>
                  <div class="submissionstatustable"><table>
                    <tr><th>作业状态</th><td>尚未批改</td></tr>
                  </table></div>
                  <form action="/mod/assign/view.php?id=3700&amp;action=editsubmission" method="post">
                    <input type="hidden" name="sesskey" value="abc123">
                    <input type="hidden" name="id" value="3700">
                    <input type="hidden" name="files_filemanager" value="42">
                    <div class="filemanager"></div>
                  </form>
                  <script>
                    M.form_filemanager.init({
                      "id": "id_files_filemanager",
                      "itemid": 42,
                      "contextid": 17,
                      "client_id": "client-3700",
                      "repositories": {
                        "1": {"type": "recent"},
                        "4": {"type": "upload"}
                      }
                    });
                  </script>
                </main>
                """.trimIndent(),
                activity,
            ),
        )

        assertEquals("尚未批改", result.value.detail.submissionStatus)
        assertEquals("42", result.value.submissionContext?.draftItemId)
        assertEquals("17", result.value.submissionContext?.contextId)
        assertEquals("client-3700", result.value.submissionContext?.clientId)
        assertEquals("4", result.value.submissionContext?.repositoryId)
        assertTrue(result.value.detail.canSubmit)
    }

    @Test
    fun doesNotUseUnrelatedActivityDateAsSubmissionDate() {
        val activity = PhyVlabActivity(
            id = 3703,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap-2-6",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3703",
        )
        val result = assertIs<PhyVlabParseResult.Success<PhyVlabParsedAssignmentPage>>(
            parsePhyVlabAssignmentPage(
                """
                <main>
                  <div data-region="activity-dates">
                    <div><strong>打开：</strong>2026年09月16日 Wednesday 00:00</div>
                    <div><strong>到期日：</strong>2026年09月24日 Thursday 00:00</div>
                  </div>
                  <div class="submissionstatustable"><table>
                    <tr><th>作业状态</th><td>尚未批改</td></tr>
                  </table></div>
                </main>
                """.trimIndent(),
                activity,
            ),
        )

        assertNull(result.value.detail.submissionDateText)
        assertNull(result.value.detail.submissionDateTimestamp)
    }

    @Test
    fun parsesFeedbackCommentsFromLiveClassNames() {
        val activity = PhyVlabActivity(
            id = 3701,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap-2-4",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3701",
        )
        val result = assertIs<PhyVlabParseResult.Success<PhyVlabParsedAssignmentPage>>(
            parsePhyVlabAssignmentPage(
                """
                <main>
                  <div id="intro">完成第二章练习</div>
                  <div class="submissionstatustable"><table>
                    <tr><th>作业状态</th><td>已提交</td></tr>
                  </table></div>
                  <div class="feedback">
                    <div class="feedbacktable"><table>
                      <tr><th>成绩</th><td>88.0/100</td></tr>
                      <tr><th>评分于</th><td>2026年03月20日 Friday 10:00</td></tr>
                      <tr><th>评分人</th><td>教师</td></tr>
                    </table></div>
                    <div class="assignfeedback_comments">请补充误差分析。</div>
                  </div>
                </main>
                """.trimIndent(),
                activity,
            ),
        )

        assertEquals("请补充误差分析。", result.value.detail.feedbackText)
    }

    @Test
    fun doesNotAdvertiseUploadWhenFileManagerContextIsMissing() {
        val activity = PhyVlabActivity(
            id = 3702,
            courseId = 72,
            courseName = "大学物理I_(2026春)",
            title = "Chap-2-5",
            activityType = "作业",
            activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3702",
        )
        val result = assertIs<PhyVlabParseResult.Success<PhyVlabParsedAssignmentPage>>(
            parsePhyVlabAssignmentPage(
                """
                <main>
                  <div id="intro">完成第二章练习</div>
                  <form action="/mod/assign/view.php?id=3702&amp;action=editsubmission" method="post">
                    <input type="hidden" name="sesskey" value="abc123">
                    <input type="hidden" name="id" value="3702">
                    <input type="hidden" name="files_filemanager" value="0">
                    <div class="filemanager"></div>
                  </form>
                </main>
                """.trimIndent(),
                activity,
            ),
        )

        assertTrue(result.value.submissionContext != null)
        assertEquals(false, result.value.detail.canSubmit)
    }

    private fun assignmentDetailFixture(): String = """
        <main>
          <div id="intro" class="box generalbox"><p>实验报告：完成第一章测量误差分析。</p></div>
          <div class="submissionstatustable">
            <table>
              <tr><th>提交状态</th><td>已提交</td></tr>
              <tr><th>最后修改</th><td>2026年03月18日 Wednesday 21:30</td></tr>
            </table>
          </div>
          <div class="gradingsummarytable">
            <table><tr><th>成绩</th><td><span class="grade">88.0</span></td></tr></table>
          </div>
          <div class="feedbacktable">
            <table><tr><th>教师评语</th><td><div class="assignfeedback_comments">数据处理规范，结论部分还可以更清晰。</div></td></tr></table>
          </div>
          <div class="submissionstatussubmitted">
            <div class="files"><a href="/pluginfile.php/17/user/private/chap1-report.pdf">chap1-report.pdf</a></div>
          </div>
          <form id="mform1" action="/mod/assign/editsubmission.php" method="post" enctype="multipart/form-data">
            <input type="hidden" name="sesskey" value="abc123">
            <input type="hidden" name="id" value="3689">
            <input type="hidden" name="assignsubmission_file_filemanager" value="42">
            <div class="filemanager" data-itemid="42" data-contextid="17" data-clientid="client-3689" data-repositoryid="4"></div>
            <input type="submit" name="submitbutton" value="保存更改">
          </form>
          <script>var M = {cfg: {sesskey: "abc123"}};</script>
        </main>
    """.trimIndent()

    private fun coursePageFixture(): String = """
        <div role="main"><div data-totalcoursecount="2">
          <div data-region="course-content" data-course-id="72">
            <a class="aalink coursename" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=72">
              <span class="multiline">大学物理I_(2026春)</span>
            </a>
            <span class="categoryname">自然科学</span>
            <div class="progress-text"><span class="sr-only">课程进度：</span><span>8</span>% 已完成</div>
          </div>
          <div data-region="course-content" data-course-id="74">
            <a class="aalink coursename" href="https://phyvlab.bjtu.edu.cn/course/view.php?id=74">
              <span class="multiline">物理实验I_(2026春)</span>
            </a>
            <span class="categoryname">自然科学</span>
            <div class="progress-text"><span class="sr-only">课程进度：</span><span>30</span>% 已完成</div>
          </div>
        </div></div>
    """.trimIndent()
}

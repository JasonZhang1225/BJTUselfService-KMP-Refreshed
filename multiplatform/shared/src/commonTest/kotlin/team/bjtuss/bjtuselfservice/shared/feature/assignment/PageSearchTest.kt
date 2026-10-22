package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkSortOrder
import team.bjtuss.bjtuselfservice.shared.domain.homework.homeworkMatchesQuery
import team.bjtuss.bjtuselfservice.shared.domain.homework.searchHomework
import team.bjtuss.bjtuselfservice.shared.feature.shell.PageSearchState

class PageSearchTest {
    @Test
    fun openingRequestsFocusOnceAndClosingClearsTheQuery() {
        val search = PageSearchState()
        search.activate()
        assertTrue(search.active)
        assertTrue(search.focusPending)
        search.consumeFocus()
        // 已经打开时再次 activate（如原生宿主回调）不会重新抢焦点。
        search.activate()
        assertFalse(search.focusPending)

        search.update("x".repeat(100))
        assertEquals(80, search.query.length)

        search.toggle()
        assertFalse(search.active)
        assertEquals("", search.query)
        assertFalse(search.focusPending)
    }

    @Test
    fun homeworkQueryMatchesCourseOrTitleAndIgnoresBlank() {
        val item = homework(courseName = "数据结构", title = "Lab 3 链表")
        assertTrue(homeworkMatchesQuery(item, "  "))
        assertTrue(homeworkMatchesQuery(item, "数据"))
        assertTrue(homeworkMatchesQuery(item, " lab "))
        assertFalse(homeworkMatchesQuery(item, "操作系统"))
    }

    @Test
    fun homeworkSearchCoversAllHomeworkAndStartsEmpty() {
        val items = listOf(
            homework(courseName = "数据结构", title = "Lab 1"),
            homework(courseName = "操作系统", title = "Lab 2"),
            homework(courseName = "数据结构", title = "期末大作业"),
        )
        assertTrue(searchHomework(items, " ", HomeworkSortOrder.ORIGINAL).isEmpty())
        assertEquals(listOf("Lab 1", "Lab 2"), searchHomework(items, "lab", HomeworkSortOrder.ORIGINAL).map { it.title })
        assertEquals(listOf("Lab 1", "期末大作业"), searchHomework(items, "数据结构", HomeworkSortOrder.ORIGINAL).map { it.title })
    }

    private fun homework(courseName: String, title: String) = Homework(
        upId = 1, idSnId = null, score = "", userId = 1, courseId = 1,
        courseName = courseName, title = title, content = "", createDate = "", endTime = "",
        openDate = "", status = 0, submitCount = 0, allCount = 0, subStatus = "", scoreId = 0, homeworkType = 0,
    )
}

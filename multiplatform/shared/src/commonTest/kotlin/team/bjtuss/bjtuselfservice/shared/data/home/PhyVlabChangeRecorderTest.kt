package team.bjtuss.bjtuselfservice.shared.data.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity

class PhyVlabChangeRecorderTest {
    @Test
    fun addedModifiedAndDeletedActivitiesAreRecorded() = runBlocking {
        val feed = FakeFeed()
        val before = listOf(
            activity(id = 1, courseId = 10, title = "实验一", completed = false),
            activity(id = 2, courseId = 10, title = "实验二", completed = false),
        )
        val after = listOf(
            activity(id = 1, courseId = 10, title = "实验一", completed = true),
            activity(id = 3, courseId = 11, title = "实验三", completed = false),
        )

        phyvlabChangeRecorder(feed).record(before, after)

        val records = feed.capturedChanges
        assertEquals(3, records.size)
        val added = records.first { it.kind == DataChangeKind.ADDED }
        val modified = records.first { it.kind == DataChangeKind.MODIFIED }
        val deleted = records.first { it.kind == DataChangeKind.DELETED }
        assertEquals("实验三", added.title)
        assertTrue(added.afterDetail.contains("未完成"))
        assertEquals("实验一", modified.title)
        assertTrue(modified.afterDetail.contains("已完成"))
        assertEquals("实验二", deleted.title)
        assertEquals(HomeChangeDomain.PHYVLAB, added.domain)
    }

    @Test
    fun identicalSnapshotsProduceNoChanges() = runBlocking {
        val feed = FakeFeed()
        val snapshot = listOf(activity(id = 1, courseId = 10, title = "实验一"))

        phyvlabChangeRecorder(feed).record(snapshot, snapshot)

        assertEquals(0, feed.capturedChanges.size)
    }

    private fun activity(
        id: Int,
        courseId: Int,
        title: String,
        completed: Boolean = false,
    ) = PhyVlabActivity(
        id = id,
        courseId = courseId,
        courseName = "物理实验",
        title = title,
        activityType = "assign",
        activityUrl = "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=$id",
        dueText = "2026-09-20 23:59",
        completed = completed,
    )

    private class FakeFeed : HomeChangeFeedRepository {
        val capturedChanges = mutableListOf<HomeChangeRecord>()
        override val records: StateFlow<List<HomeChangeRecord>> = MutableStateFlow(emptyList())
        override suspend fun acceptRefresh(
            domain: HomeChangeDomain,
            hadPreviousItems: Boolean,
            changes: List<HomeChangeRecord>,
        ): Boolean {
            capturedChanges.addAll(changes)
            return true
        }

        override suspend fun clear(domain: HomeChangeDomain?): Boolean = true
    }
}

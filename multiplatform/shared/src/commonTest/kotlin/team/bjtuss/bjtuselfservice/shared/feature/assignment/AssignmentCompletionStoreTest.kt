package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask

class AssignmentCompletionStoreTest {
    private val key = AssignmentCompletionKey(AssignmentSource.COURSE_PLATFORM, 10, 20)

    @Test fun identitiesStayDistinctAcrossSourcesCoursesTasksAndCitelKinds() {
        val physical = PhyVlabActivity(20, 10, "课程", "作业", "assign", "")
        val citel = CitelTask(20, 10, "课程", "作业", "", programming = false)
        val keys = setOf(homework().completionKey(), physical.completionKey(), citel.completionKey(),
            citel.copy(programming = true).completionKey(), homework().copy(courseId = 11).completionKey(),
            homework().copy(upId = 21).completionKey())
        assertEquals(6, keys.size)
        assertEquals(key, homework().completionKey())
    }

    @Test fun renamingReschedulingAndServerSubmissionDoNotChangeIdentity() {
        val homework = homework()
        assertEquals(homework.completionKey(), homework.copy(courseName = "新课程名", title = "新标题",
            endTime = "2027-01-01 00:00", subStatus = "已提交", idSnId = 99).completionKey())
        val physical = PhyVlabActivity(20, 10, "课程", "作业", "assign", "")
        assertEquals(physical.completionKey(), physical.copy(courseName = "新课程名", title = "新标题",
            dueTimestamp = 999, completed = true).completionKey())
        val citel = CitelTask(20, 10, "课程", "作业", "", programming = true)
        assertEquals(citel.completionKey(), citel.copy(title = "新标题", dueTime = 999,
            status = "AC: Accepted", submitted = true).completionKey())
    }

    @Test fun markingAndUndoPublishOnlyPersistedState() = runBlocking {
        val persistence = MemoryPersistence()
        val store = AssignmentCompletionStore(persistence)
        val observed = async(start = CoroutineStart.UNDISPATCHED) { store.state.drop(1).first() }
        assertTrue(store.setCompleted(key, true))
        assertEquals(setOf(key), observed.await().completedKeys)
        assertEquals(persistence.saved, store.state.value.completedKeys)
        assertTrue(store.setCompleted(key, false))
        assertTrue(store.state.value.completedKeys.isEmpty())
        assertTrue(AssignmentCompletionStore(persistence).state.value.completedKeys.isEmpty())
    }

    @Test fun repeatedMarkAndUndoDoNotWriteAgain() = runBlocking {
        val persistence = MemoryPersistence()
        val store = AssignmentCompletionStore(persistence)
        assertTrue(store.setCompleted(key, false))
        assertEquals(0, persistence.writes)
        assertTrue(store.setCompleted(key, true))
        val state = store.state.value
        assertTrue(store.setCompleted(key, true))
        assertSame(state, store.state.value)
        assertEquals(1, persistence.writes)
    }

    @Test fun failedSaveRetainsOldMarksAndCanBeRetried() = runBlocking {
        val persistence = MemoryPersistence(setOf(key))
        val store = AssignmentCompletionStore(persistence)
        persistence.saveFailure = IllegalStateException("fixture")
        assertFalse(store.setCompleted(key, false))
        assertEquals(setOf(key), store.state.value.completedKeys)
        assertEquals(setOf(key), persistence.saved)
        assertEquals(AssignmentCompletionFailure.SAVE, store.state.value.failure)
        persistence.saveFailure = null
        assertTrue(store.setCompleted(key, false))
        assertNull(store.state.value.failure)
        assertTrue(store.state.value.completedKeys.isEmpty())
    }

    @Test fun failedInitialReadCannotOverwritePreviouslySavedMarks() = runBlocking {
        val persistence = MemoryPersistence(setOf(key)).apply { loadFailure = IllegalStateException("fixture") }
        val store = AssignmentCompletionStore(persistence)
        assertFalse(store.state.value.loaded)
        assertEquals(AssignmentCompletionFailure.LOAD, store.state.value.failure)
        assertFalse(store.setCompleted(key.copy(assignmentId = 21), true))
        assertEquals(0, persistence.writes)
        persistence.loadFailure = null
        assertTrue(store.reload())
        assertEquals(setOf(key), store.state.value.completedKeys)
        assertNull(store.state.value.failure)
        assertTrue(store.setCompleted(key.copy(assignmentId = 21), true))
        assertEquals(2, persistence.saved.size)
    }

    @Test fun failedReloadKeepsLastKnownMarksAndBlocksWritesUntilRecovery() = runBlocking {
        val persistence = MemoryPersistence(setOf(key))
        val store = AssignmentCompletionStore(persistence)
        persistence.loadFailure = IllegalStateException("fixture")
        assertFalse(store.reload())
        assertEquals(setOf(key), store.state.value.completedKeys)
        assertFalse(store.setCompleted(key, false))
        persistence.loadFailure = null
        assertTrue(store.reload())
        assertTrue(store.setCompleted(key, false))
    }

    @Test fun cancellationIsPropagatedWithoutReportingSaveFailure() = runBlocking {
        val persistence = MemoryPersistence()
        val store = AssignmentCompletionStore(persistence)
        persistence.saveFailure = CancellationException("fixture")
        assertFailsWith<CancellationException> { store.setCompleted(key, true) }
        assertNull(store.state.value.failure)
        assertTrue(store.state.value.completedKeys.isEmpty())
    }

    @Test fun concurrentMarksDoNotLoseOtherUpdates() = runBlocking {
        val persistence = MemoryPersistence()
        val store = AssignmentCompletionStore(persistence)
        val keys = (1..40).map { key.copy(assignmentId = it) }.toSet()
        keys.map { async(Dispatchers.Default) { assertTrue(store.setCompleted(it, true)) } }.awaitAll()
        assertEquals(keys, store.state.value.completedKeys)
        assertEquals(keys, AssignmentCompletionStore(persistence).state.value.completedKeys)
    }

    private class MemoryPersistence(initial: Set<AssignmentCompletionKey> = emptySet()) : AssignmentCompletionPersistence {
        var saved = initial
        var writes = 0
        var loadFailure: Exception? = null
        var saveFailure: Exception? = null
        override fun load(): Set<AssignmentCompletionKey> {
            loadFailure?.let { throw it }
            return saved
        }
        override fun save(keys: Set<AssignmentCompletionKey>) {
            saveFailure?.let { throw it }
            saved = keys
            writes++
        }
    }

    private fun homework() = Homework(id = 1, upId = 20, idSnId = null, score = "", userId = 1,
        courseId = 10, courseName = "课程", title = "作业", content = "", createDate = "", endTime = "",
        openDate = "", status = 0, submitCount = 0, allCount = 0, subStatus = "未提交", scoreId = 0, homeworkType = 0)
}

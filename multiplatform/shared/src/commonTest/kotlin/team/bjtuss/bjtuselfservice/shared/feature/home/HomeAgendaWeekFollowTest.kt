package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.AcademicWeekSlot

class HomeAgendaWeekFollowTest {
    private val monday = LocalDate(2026, 9, 21)

    @Test
    fun sameDateDifferentWeekNumberStillFollows() {
        // 2026-09-26 实机 bug：启动时无校历的回退第 1 周与校历确认后的真实第 3 周
        // 落在同一天，只比开始日期就永远停在错误的第 1 周。
        val degenerateWeekOne = AcademicWeekSlot(teachingWeek = 1, startDate = monday)
        val realWeekThree = AcademicWeekSlot(teachingWeek = 3, startDate = monday)

        assertEquals(
            realWeekThree,
            nextAutoFollowedWeekSlot(
                selectedSlot = degenerateWeekOne,
                automaticSlot = realWeekThree,
                weekWasManuallySelected = false,
            ),
        )
    }

    @Test
    fun identicalSlotDoesNotFollow() {
        val slot = AcademicWeekSlot(teachingWeek = 3, startDate = monday)

        assertNull(
            nextAutoFollowedWeekSlot(
                selectedSlot = slot,
                automaticSlot = slot,
                weekWasManuallySelected = false,
            ),
        )
    }

    @Test
    fun differentDateStillFollows() {
        val selected = AcademicWeekSlot(teachingWeek = 1, startDate = LocalDate(2026, 9, 7))
        val automatic = AcademicWeekSlot(teachingWeek = 3, startDate = monday)

        assertEquals(
            automatic,
            nextAutoFollowedWeekSlot(
                selectedSlot = selected,
                automaticSlot = automatic,
                weekWasManuallySelected = false,
            ),
        )
    }

    @Test
    fun manualSelectionIsNeverOverridden() {
        val selected = AcademicWeekSlot(teachingWeek = 3, startDate = monday)
        val automatic = AcademicWeekSlot(teachingWeek = 4, startDate = LocalDate(2026, 10, 5))

        assertNull(
            nextAutoFollowedWeekSlot(
                selectedSlot = selected,
                automaticSlot = automatic,
                weekWasManuallySelected = true,
            ),
        )
    }

    @Test
    fun unresolvedCalendarDoesNotFollow() {
        val selected = AcademicWeekSlot(teachingWeek = 1, startDate = monday)

        assertNull(
            nextAutoFollowedWeekSlot(
                selectedSlot = selected,
                automaticSlot = null,
                weekWasManuallySelected = false,
            ),
        )
    }
}

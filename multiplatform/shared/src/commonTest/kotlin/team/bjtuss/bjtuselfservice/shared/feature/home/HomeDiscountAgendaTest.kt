package team.bjtuss.bjtuselfservice.shared.feature.home

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.domain.home.buildHomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind
import team.bjtuss.bjtuselfservice.shared.feature.citel.CITEL_BASE
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask
import team.bjtuss.bjtuselfservice.shared.feature.citel.citelAgendaEvents

internal class HomeDiscountAgendaFixture {
    val now = LocalDateTime(2026, 10, 30, 10, 0)
    val zone = TimeZone.of("Asia/Shanghai")
    val seconds = now.toInstant(zone).epochSeconds
    val discountTask = CitelTask(
        id = 20, courseId = 10, courseName = "示例课程", title = "即将折扣的报告",
        url = "$CITEL_BASE/lab/20", programming = false,
        openTime = seconds - 86400, discountTime = seconds + 3600, dueTime = seconds + 7 * 86400,
    )
    val deadlineTask = discountTask.copy(
        id = 21, title = "即将截止的报告", url = "$CITEL_BASE/lab/21",
        discountTime = seconds - 86400, dueTime = seconds + 7200,
    )
    val tasks = listOf(discountTask, deadlineTask)
    val events = citelAgendaEvents(tasks)
}

class HomeDiscountAgendaTest {
    private val f = HomeDiscountAgendaFixture()

    @Test fun upcomingDiscountsAreSortedSeparatelyFromExistingDeadlines() {
        val later = f.discountTask.copy(id = 22, title = "较晚折扣", discountTime = f.seconds + 47 * 3600)
        val tasks = listOf(later, f.deadlineTask, f.discountTask)
        val agenda = buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone, citelAgendaEvents(tasks))

        assertEquals(listOf("citel-discount-20", "citel-discount-22"), agenda.dueSoonDiscountEvents.map { it.id })
        assertEquals(listOf("citel-due-21"), agenda.dueSoonEvents.map { it.id })
        assertTrue(agenda.dueSoonHomework.isEmpty())
        assertTrue(agenda.dueSoonDiscountEvents.all { it.kind == PhyVlabEventKind.DISCOUNT })
    }

    @Test fun discountWindowIncludesNowAndExactly48HoursButExcludesPastAndAnythingLater() {
        val sample = f.events.single { it.id == "citel-discount-20" }
        val offsets = listOf(-1L, 0L, 1L, 48L * 3600, 48L * 3600 + 1, 49L * 3600)
        val events = offsets.mapIndexed { index, offset ->
            sample.copy(id = "citel-discount-$index", dayTimestamp = f.seconds + offset)
        }
        val agenda = buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone, events.reversed())

        assertEquals(listOf("citel-discount-1", "citel-discount-2", "citel-discount-3"),
            agenda.dueSoonDiscountEvents.map { it.id })
        assertTrue(agenda.dueSoonEvents.isEmpty())
    }

    @Test fun onlyPendingCitelDiscountEventsEnterTheDiscountList() {
        val sample = f.events.single { it.id == "citel-discount-20" }
        val events = listOf(sample,
            sample.copy(id = "citel-discount-21", submitted = true),
            sample.copy(id = "physical-discount-20"),
            sample.copy(id = "citel-start-20", kind = PhyVlabEventKind.START),
            sample.copy(id = "citel-due-20", kind = PhyVlabEventKind.DEADLINE))
        val agenda = buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone, events)

        assertEquals(listOf(sample), agenda.dueSoonDiscountEvents)
        assertEquals(listOf("citel-due-20"), agenda.dueSoonEvents.map { it.id })
        val noDiscount = citelAgendaEvents(listOf(f.discountTask.copy(discountTime = null)))
        assertTrue(buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone, noDiscount).dueSoonDiscountEvents.isEmpty())
    }

    @Test fun submittedReportsAndAcceptedProgramsAreExcludedButWrongAnswersStillRemind() {
        val report = f.discountTask.copy(submitted = true)
        val wa = f.discountTask.copy(id = 22, programming = true, submitted = true, status = "WA: Wrong Answer")
        val ac = wa.copy(id = 23, status = "AC: Accepted")
        val agenda = buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone,
            citelAgendaEvents(listOf(report, wa, ac)))

        assertEquals(listOf("citel-discount-22"), agenda.dueSoonDiscountEvents.map { it.id })
    }

    @Test fun coincidentDiscountAndDeadlineKeepTheExistingSingleDeadlineEvent() {
        val task = f.discountTask.copy(discountTime = f.deadlineTask.dueTime, dueTime = f.deadlineTask.dueTime)
        val agenda = buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone, citelAgendaEvents(listOf(task)))

        assertTrue(agenda.dueSoonDiscountEvents.isEmpty())
        assertEquals(1, agenda.dueSoonEvents.size)
        assertTrue(agenda.dueSoonEvents.single().title.endsWith("截止 / 开始折扣"))
    }

    @Test fun discountSelectionUsesTheSameInstantAcrossDeviceTimeZones() {
        val schoolAgenda = buildHomeAgenda(emptyList(), emptyList(), f.now.date, f.now, f.zone, f.events)
        val utcNow = f.now.toInstant(f.zone).toLocalDateTime(TimeZone.UTC)
        val utcAgenda = buildHomeAgenda(emptyList(), emptyList(), utcNow.date, utcNow, TimeZone.UTC, f.events)

        assertEquals(schoolAgenda.dueSoonDiscountEvents, utcAgenda.dueSoonDiscountEvents)
        assertEquals(schoolAgenda.dueSoonEvents, utcAgenda.dueSoonEvents)
    }
}

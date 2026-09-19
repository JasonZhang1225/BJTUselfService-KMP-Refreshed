package team.bjtuss.bjtuselfservice.shared.feature.grade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.feature.shell.MailboxBackTarget
import team.bjtuss.bjtuselfservice.shared.feature.shell.mailboxBackTarget
import team.bjtuss.bjtuselfservice.shared.feature.shell.shouldHandleInlineMailboxBack
import team.bjtuss.bjtuselfservice.shared.feature.shell.shouldOpenNativeSectionRoute

class GradeScreenNavigationTest {
    @Test
    fun mailboxAndClassroomRootsPushNativelyFromMore() {
        assertTrue(shouldOpenNativeSectionRoute("MAILBOX", useNativeSecondaryRoutes = true))
        assertTrue(shouldOpenNativeSectionRoute("CLASSROOMS", useNativeSecondaryRoutes = true))
        assertTrue(shouldOpenNativeSectionRoute("CLASSROOM_OCCUPANCY", useNativeSecondaryRoutes = true))
        assertTrue(shouldOpenNativeSectionRoute("EXAMS", useNativeSecondaryRoutes = true))
        // 物理在线不在原生底栏上（5 格上限），它的一级入口由宿主压栈，才能拿到系统返回。
        assertTrue(shouldOpenNativeSectionRoute("PHYVLAB", useNativeSecondaryRoutes = true))
        assertFalse(shouldOpenNativeSectionRoute("MAILBOX", useNativeSecondaryRoutes = false))
        assertFalse(shouldOpenNativeSectionRoute("EXAMS", useNativeSecondaryRoutes = false))
    }

    @Test
    fun inlineMailboxDetailUsesTheTopBarBackAction() {
        assertEquals(
            MailboxBackTarget.LIST,
            mailboxBackTarget(
                useNativeSecondaryRoutes = false,
                hasSelectedMessage = true,
                isMessageLoading = false,
            ),
        )
        assertEquals(
            MailboxBackTarget.LIST,
            mailboxBackTarget(
                useNativeSecondaryRoutes = false,
                hasSelectedMessage = false,
                isMessageLoading = true,
            ),
        )
    }

    @Test
    fun mailboxRootAndNativeDetailKeepTheParentBackAction() {
        assertEquals(
            MailboxBackTarget.PARENT,
            mailboxBackTarget(
                useNativeSecondaryRoutes = false,
                hasSelectedMessage = false,
                isMessageLoading = false,
            ),
        )
        assertEquals(
            MailboxBackTarget.PARENT,
            mailboxBackTarget(
                useNativeSecondaryRoutes = true,
                hasSelectedMessage = true,
                isMessageLoading = false,
            ),
        )
        assertEquals(
            MailboxBackTarget.LIST,
            mailboxBackTarget(
                useNativeSecondaryRoutes = false,
                hasSelectedMessage = true,
                isMessageLoading = false,
            ),
        )
    }

    @Test
    fun inlineMailboxSystemBackReturnsToTheListBeforePoppingMore() {
        assertTrue(
            shouldHandleInlineMailboxBack(
                currentRouteIsMailbox = true,
                mailboxInlineDetail = true,
                mailboxInlineCompose = false,
            ),
        )
        assertTrue(
            shouldHandleInlineMailboxBack(
                currentRouteIsMailbox = true,
                mailboxInlineDetail = false,
                mailboxInlineCompose = true,
            ),
        )
        assertFalse(
            shouldHandleInlineMailboxBack(
                currentRouteIsMailbox = false,
                mailboxInlineDetail = true,
                mailboxInlineCompose = false,
            ),
        )
        assertFalse(
            shouldHandleInlineMailboxBack(
                currentRouteIsMailbox = true,
                mailboxInlineDetail = false,
                mailboxInlineCompose = false,
            ),
        )
    }
}

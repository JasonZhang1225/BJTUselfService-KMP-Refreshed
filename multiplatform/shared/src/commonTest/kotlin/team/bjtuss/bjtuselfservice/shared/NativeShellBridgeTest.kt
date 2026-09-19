package team.bjtuss.bjtuselfservice.shared

import team.bjtuss.bjtuselfservice.shared.feature.shell.HOMEWORK_DETAIL_ROUTE_ID
import team.bjtuss.bjtuselfservice.shared.feature.shell.MAILBOX_COMPOSE_ROUTE_ID
import team.bjtuss.bjtuselfservice.shared.feature.shell.MAILBOX_DETAIL_ROUTE_ID
import team.bjtuss.bjtuselfservice.shared.feature.shell.isNativeTabRoute
import team.bjtuss.bjtuselfservice.shared.feature.shell.nativeFloatingEntryFor
import team.bjtuss.bjtuselfservice.shared.feature.shell.nativeRouteTitle
import team.bjtuss.bjtuselfservice.shared.feature.shell.shouldOpenNativeSectionRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M17 原生壳桥接：宿主容器只能看到 routeId 字符串与静态标题，这里锁定它和 Compose
 * 自绘底栏使用同一份一级入口定义，避免两端各自维护列表后漂移。
 */
class NativeShellBridgeTest {
    @Test
    fun compactBottomBarEntriesAreNativeTabs() {
        listOf("HOME", "SCHEDULE", "GRADES", "HOMEWORK", "MORE").forEach {
            assertTrue(isNativeTabRoute(it), "$it 应被识别为一级入口")
        }
    }

    @Test
    fun secondLevelAndUnknownRoutesAreNotNativeTabs() {
        // 原生 tab 容器只放得下 5 项，物理在线开启时会让底栏溢出，因此它固定收在「更多」目录里。
        listOf("PHYVLAB", "MAILBOX", "SETTINGS", "MAILBOX_DETAIL", "CLASSROOM_DETAIL", "UNKNOWN_ROUTE").forEach {
            assertFalse(isNativeTabRoute(it), "$it 不是一级入口")
        }
    }

    @Test
    fun nativeTitlesCoverHostBarWithoutWaitingForCompose() {
        assertEquals("首页", nativeRouteTitle("HOME"))
        assertEquals("更多", nativeRouteTitle("MORE"))
        assertEquals("作业详情", nativeRouteTitle(HOMEWORK_DETAIL_ROUTE_ID))
        assertEquals("邮件详情", nativeRouteTitle(MAILBOX_DETAIL_ROUTE_ID))
        // 写信页的返回要先取消草稿，标题栏仍归页面自己，宿主不得抢先显示。
        assertEquals("", nativeRouteTitle(MAILBOX_COMPOSE_ROUTE_ID))
        assertEquals("", nativeRouteTitle("UNKNOWN_ROUTE"))
    }

    @Test
    fun nativeTabOverflowIsPushedByHostSoItKeepsASystemBackEntry() {
        // 因 5 项上限被收进「更多」目录的一级项（物理在线）必须由宿主压栈：
        // 否则它会留在 tab 根的 Compose 栈里，页内与系统栏都不给返回入口。
        assertTrue(shouldOpenNativeSectionRoute("PHYVLAB", useNativeSecondaryRoutes = true))
        // 回退壳（iOS 26 以下）不开原生二级路由时仍走 Compose 栈。
        assertFalse(shouldOpenNativeSectionRoute("PHYVLAB", useNativeSecondaryRoutes = false))
        // 目录本身与真正在底栏上的入口都不该被压栈。
        assertFalse(shouldOpenNativeSectionRoute("MORE", useNativeSecondaryRoutes = true))
        assertFalse(shouldOpenNativeSectionRoute("GRADES", useNativeSecondaryRoutes = true))
    }

    @Test
    fun phyVlabMovesToAFloatingEntryWhenTheBarIsFull() {
        // 底栏只有 5 格：开启物理在线后它挤不进去，必须由宿主的悬浮圆按钮承载，
        // 否则用户拨完开关哪儿都看不到变化（2026-09-19 用户报的原始症状）。
        assertEquals("PHYVLAB", nativeFloatingEntryFor(phyVlabEnabled = true)?.routeId)
        // 关闭时 5 项刚好放得下，不该留下悬浮入口。
        assertNull(nativeFloatingEntryFor(phyVlabEnabled = false))
    }
}

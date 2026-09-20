package team.bjtuss.bjtuselfservice.shared

import team.bjtuss.bjtuselfservice.shared.feature.shell.HOMEWORK_DETAIL_ROUTE_ID
import team.bjtuss.bjtuselfservice.shared.feature.shell.MAILBOX_COMPOSE_ROUTE_ID
import team.bjtuss.bjtuselfservice.shared.feature.shell.MAILBOX_DETAIL_ROUTE_ID
import team.bjtuss.bjtuselfservice.shared.feature.shell.isNativeTabRoute
import team.bjtuss.bjtuselfservice.shared.feature.shell.nativeRouteTitle
import team.bjtuss.bjtuselfservice.shared.feature.shell.shouldOpenNativeSectionRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M17 原生壳桥接：宿主容器只能看到 routeId 字符串与静态标题，这里锁定它和 Compose
 * 自绘底栏使用同一份一级入口定义，避免两端各自维护列表后漂移。
 */
class NativeShellBridgeTest {
    @Test
    fun compactBottomBarEntriesAreNativeTabs() {
        listOf("HOME", "SCHEDULE", "GRADES", "HOMEWORK", "MORE", "PHYVLAB").forEach {
            assertTrue(isNativeTabRoute(it), "$it 应被识别为一级入口")
        }
    }

    @Test
    fun secondLevelAndUnknownRoutesAreNotNativeTabs() {
        listOf("MAILBOX", "SETTINGS", "MAILBOX_DETAIL", "CLASSROOM_DETAIL", "UNKNOWN_ROUTE").forEach {
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
    fun firstLevelPhysicalOnlineStaysInTheNativeTabBar() {
        assertFalse(shouldOpenNativeSectionRoute("PHYVLAB", useNativeSecondaryRoutes = true))
        // 回退壳（iOS 26 以下）不开原生二级路由时仍走 Compose 栈。
        assertFalse(shouldOpenNativeSectionRoute("PHYVLAB", useNativeSecondaryRoutes = false))
        // 目录本身与真正在底栏上的入口都不该被压栈。
        assertFalse(shouldOpenNativeSectionRoute("MORE", useNativeSecondaryRoutes = true))
        assertFalse(shouldOpenNativeSectionRoute("GRADES", useNativeSecondaryRoutes = true))
    }

}

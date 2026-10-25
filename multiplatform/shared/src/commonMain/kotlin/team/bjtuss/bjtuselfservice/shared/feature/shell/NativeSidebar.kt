package team.bjtuss.bjtuselfservice.shared.feature.shell

import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * macOS 原生侧栏里的一级入口。
 *
 * 与 iOS 的底部 tab 不同，macOS 侧栏展示的是完整功能目录（对齐 Mac App Store 的
 * 分类侧栏），因此这里的 [systemImage] 直接给出 SF Symbol 名，由 SwiftUI 侧渲染。
 */
data class NativeSidebarItem(
    val routeId: String,
    val title: String,
    val systemImage: String,
)

/**
 * macOS 原生侧栏的入口列表：首页 + 应用页的全部功能 + 设置。
 *
 * 与 [applicationSections] 同一份来源，避免原生侧栏与「应用」页的功能集合漂移。
 */
fun nativeSidebarItems(session: AuthenticatedSession): List<NativeSidebarItem> =
    nativeSidebarItems(session.settingsModel.state.value.preferences)

/**
 * 尚未登录时也要有一份侧栏：原生侧栏由宿主提供，不该因为还没登录就整条空着。
 * 此时用默认偏好（物理在线等未开启的功能不列出）。
 */
fun nativeSidebarItems(preferences: AppPreferences): List<NativeSidebarItem> =
    buildList {
        add(sidebarItem(AppSection.HOME))
        // 「设置」在功能目录里已有，这里单独补一份并去掉重复项。
        applicationSections(preferences)
            .filter { it != AppSection.SETTINGS }
            .forEach { add(sidebarItem(it)) }
        add(sidebarItem(AppSection.SETTINGS))
    }

private fun sidebarItem(section: AppSection): NativeSidebarItem = NativeSidebarItem(
    routeId = section.name,
    title = section.title,
    systemImage = section.systemImage,
)

/** 一级入口对应的 SF Symbol 名。找不到图标时宿主回退到默认符号。 */
private val AppSection.systemImage: String
    get() = when (this) {
        AppSection.HOME -> "house"
        AppSection.SCHEDULE -> "calendar"
        AppSection.GRADES -> "chart.bar.doc.horizontal"
        AppSection.EXAMS -> "checklist"
        AppSection.HOMEWORK -> "doc.text"
        AppSection.ASSIGNMENTS -> "square.stack.3d.up"
        AppSection.COURSEWARE -> "arrow.down.doc"
        AppSection.CLASSROOM_OCCUPANCY -> "person.2"
        AppSection.CLASSROOMS -> "building.2"
        AppSection.MAILBOX -> "envelope"
        AppSection.LIBRARY -> "books.vertical"
        AppSection.PHYVLAB -> "flask"
        AppSection.PHYSICS_LAB -> "atom"
        AppSection.CITEL -> "terminal"
        AppSection.CALENDAR -> "calendar.badge.clock"
        AppSection.REPORT_CARD_DOWNLOAD -> "square.and.arrow.down"
        AppSection.SETTINGS -> "gearshape"
        AppSection.MORE -> "square.grid.2x2"
    }

/**
 * macOS 原生侧栏把选中的一级入口交给共享壳层。
 *
 * 与 [AppCommandBus] 分开：命令总线是固定集合的枚举（桌面菜单、快捷键用），
 * 而侧栏要按 section 名称导航，需要带参数。
 */
class NativeSidebarBus {
    private val shared = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val requests: Flow<String> = shared.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = shared.subscriptionCount

    fun select(routeId: String): Boolean = shared.tryEmit(routeId)
}

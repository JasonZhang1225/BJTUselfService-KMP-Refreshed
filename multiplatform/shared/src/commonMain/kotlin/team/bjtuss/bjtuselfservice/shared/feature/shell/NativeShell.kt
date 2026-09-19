package team.bjtuss.bjtuselfservice.shared.feature.shell

import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession

/**
 * M17 原生壳桥接：把紧凑端一级入口交给系统容器（iOS UITabBarController / Android
 * Activity 宿主），标题与返回同样可由宿主导航栏承载。这里只暴露 Swift/Objective-C
 * 侧需要读取的纯数据，AppSection 等壳内类型保持 internal 不外泄。
 */
data class NativeTabItem(
    val routeId: String,
    val title: String,
)

/**
 * 宿主导航栏右侧的动作区（当前只有二级页的「同步状态 + 刷新」）。
 *
 * 二级页的刷新与同步状态原本自绘成单独一行胶囊，把内容区整整压掉一条；这两者本来就是
 * 导航栏级信息（Mail/Files 都在这里），所以在原生标题栏生效时交给宿主渲染。[onClick] 作为
 * ObjC block 导出给 Swift，点击直接回到本页的刷新闭包，不经过全局状态，pop 之后也不会串页。
 */
class NativeBarAction(
    /** 空闲时的状态文案，如「已同步」；空串表示本页没有状态要显示。 */
    val status: String,
    /** 是否提供刷新；false 时宿主只画状态，不放按钮。 */
    val canRefresh: Boolean,
    val busy: Boolean,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * 原生 tab 容器能放下的第一方入口数上限。`UITabBarController` 一旦超过 5 项就会自己插入系统
 * 「更多」溢出页，把应用自己的「更多」目录一起收走，底栏看起来「什么都没增加」。
 * 超出的入口因此改由应用自己的「更多」目录承载（见 [MoreWorkspace]）。
 */
const val NATIVE_TAB_BAR_MAX_ITEMS = 5

private fun cappedNativeTabs(sections: List<AppSection>): List<AppSection> =
    if (sections.size > NATIVE_TAB_BAR_MAX_ITEMS) sections.filterNot { it == AppSection.PHYVLAB } else sections

/** 原生底栏实际承载的一级入口；没进来的项改由「更多」目录压入，见 [shouldOpenNativeSectionRoute]。 */
internal fun nativeTabSections(): List<AppSection> = cappedNativeTabs(bottomNavSections(true))

/** 紧凑端底部导航的原生镜像；来源与 [bottomNavSections] 同一份，避免两端漂移。 */
fun nativeTabItems(session: AuthenticatedSession): List<NativeTabItem> =
    cappedNativeTabs(bottomNavSections(session.settingsModel.state.value.preferences.isPhyVlabEnabled))
        .map { NativeTabItem(routeId = it.name, title = it.title) }

/** 该 routeId 是否真的落在原生 tab 上；被收进「更多」目录的入口（物理在线）不是。 */
fun isNativeTabRoute(routeId: String): Boolean =
    routeId.toAppRoute() in cappedNativeTabs(bottomNavSections(true))

/**
 * 底栏放不下、但用户已经打开的一级入口，交给宿主在玻璃条旁以悬浮圆按钮承载。
 *
 * iPhone 紧凑端 `UITabBarController` 只给 5 格，第 6 项会触发系统自己的溢出页并把应用
 * 「更多」一起收走；而物理在线藏在「更多」目录里又太难发现（用户 2026-09-19 明确要求底栏入口）。
 * 于是它保持不占 tab 格，改由一颗悬浮圆按钮直达，点进去仍是宿主压栈、带系统返回。
 */
internal fun nativeFloatingEntryFor(phyVlabEnabled: Boolean): NativeTabItem? =
    (bottomNavSections(phyVlabEnabled) - cappedNativeTabs(bottomNavSections(phyVlabEnabled)).toSet())
        .firstOrNull()
        ?.let { NativeTabItem(routeId = it.name, title = it.title) }

/** [nativeFloatingEntryFor] 的会话视图，供 Swift 宿主直接调用。 */
fun nativeFloatingEntry(session: AuthenticatedSession): NativeTabItem? =
    nativeFloatingEntryFor(session.settingsModel.state.value.preferences.isPhyVlabEnabled)

/**
 * 目的地静态标题，供宿主在 push 动画开始时就放好标题栏（Compose 首帧回报有数百毫秒延迟）。
 * 返回空串表示这一页自己保留页内标题与返回入口（如写信，返回要先取消草稿），宿主据此不显示系统栏。
 * 动态标题（选中的教学楼、写信/回复邮件）随后由 [AuthenticatedAppShell] 回报覆盖。
 */
fun nativeRouteTitle(routeId: String): String {
    val route = routeId.toAppRoute() ?: return ""
    return when (route) {
        MailboxComposeRoute -> ""
        ClassroomDetailRoute -> AppSection.CLASSROOMS.title
        ClassroomOccupancyDetailRoute -> AppSection.CLASSROOM_OCCUPANCY.title
        HomeworkDetailRoute -> "作业详情"
        PhyVlabDetailRoute -> "物理作业详情"
        MailboxDetailRoute -> "邮件详情"
        is AppSection -> route.title
    }
}

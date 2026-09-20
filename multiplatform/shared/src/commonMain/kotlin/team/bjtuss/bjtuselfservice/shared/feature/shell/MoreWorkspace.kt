package team.bjtuss.bjtuselfservice.shared.feature.shell


import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.WindowClass
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import kotlin.math.PI
import team.bjtuss.bjtuselfservice.shared.accessibleAlpha
import team.bjtuss.bjtuselfservice.shared.data.grade.formatGradeDetailForDisplay
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppErrorBanner
import team.bjtuss.bjtuselfservice.shared.usesLegacySmartTransportFor
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeChangeFeedRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.exam.ExamScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleContentSource
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleContentSource
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkContentSource
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareContentSource
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.OtherFunctionScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.SCHOOL_CALENDAR_ARTICLE_URL
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.SchoolCalendarArticleWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.otherfunction.ReportCardDownloadWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomBuildingState
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomBuildingWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomUiState
import team.bjtuss.bjtuselfservice.shared.feature.classroom.ClassroomWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyBuildingWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyQueryState
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.ClassroomOccupancyWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.settings.AppUpdateResultDialog
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxUiState
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxFailure
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxTopBarActions
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.MailboxComposeScreen
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabDetailWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSyncFailure
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeWorkspace
import team.bjtuss.bjtuselfservice.shared.feature.home.homeIdleStatusText
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.home.homeSyncDialogTitle
import team.bjtuss.bjtuselfservice.shared.webview.openExternalUrl
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeInfoResult
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeSortOrder
import team.bjtuss.bjtuselfservice.shared.domain.grade.displayCourseName
import team.bjtuss.bjtuselfservice.shared.domain.grade.displayName
import team.bjtuss.bjtuselfservice.shared.domain.grade.scoreForSorting
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeDomain
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeChangeRecord

/**
 * 「更多」页：iOS 设置式分块列表。
 * - 学业：物理在线总开关、考试、课件
 * - 校园：教室占用查询、教室人数估计、邮箱
 * - 信息与下载：校历文章、成绩单
 * - 设置单独一块垫底
 */
@Composable
internal fun MoreWorkspace(
    phyVlabEnabled: Boolean,
    onPhyVlabEnabledChange: (Boolean) -> Unit,
    onOpenSection: (AppSection) -> Unit,
    modifier: Modifier,
) {
    val pageScrollState = rememberScrollState()
    val sections = listOf(
        MoreListSection(
            header = "校园",
            items = listOf(AppSection.CLASSROOM_OCCUPANCY, AppSection.CLASSROOMS, AppSection.MAILBOX),
        ),
        MoreListSection(
            header = "信息与下载",
            items = listOf(AppSection.CALENDAR, AppSection.REPORT_CARD_DOWNLOAD),
        ),
        MoreListSection(
            header = null,
            items = listOf(AppSection.SETTINGS),
        ),
    )
    Column(
        modifier = modifier
            .verticalScroll(pageScrollState)
            .desktopTouchScroll(pageScrollState)
            // 底部留白在滚动内容之内：玻璃 TabBar 浮在这页之上，末组要靠它让开，
            // 而列表本身要画到物理底边，玻璃才有内容可折射。
            .padding(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 12.dp + LocalBottomBarClearance.current,
            ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "学业",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        MorePhyVlabToggleRow(
            enabled = phyVlabEnabled,
            onEnabledChange = onPhyVlabEnabledChange,
        )
        MoreGroupedSection(
            header = null,
            items = listOf(AppSection.EXAMS, AppSection.COURSEWARE),
            onOpenSection = onOpenSection,
        )
        sections.forEach { section ->
            MoreGroupedSection(
                header = section.header,
                items = section.items,
                onOpenSection = onOpenSection,
            )
        }
    }
}

@Composable
internal fun MorePhyVlabToggleRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    AppSection.PHYVLAB.moreTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                )
                Text(
                    "开启后同步物理在线，并在底栏显示入口；关闭后不参与同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

internal data class MoreListSection(
    val header: String?,
    val items: List<AppSection>,
)

@Composable
internal fun MoreGroupedSection(
    header: String?,
    items: List<AppSection>,
    onOpenSection: (AppSection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        // 整块圆角容器，行间细分隔线（类似 iOS inset grouped）。
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    Surface(
                        onClick = { onOpenSection(item) },
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.moreTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            MoreEntryChevron()
                        }
                    }
                    if (index < items.lastIndex) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .height(0.5.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.accessibleAlpha(0.55f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MoreEntryChevron() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(8.dp, 14.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val mid = size.height / 2
        drawLine(
            color,
            Offset(2.dp.toPx(), 2.dp.toPx()),
            Offset(size.width - 1.dp.toPx(), mid),
            strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width - 1.dp.toPx(), mid),
            Offset(2.dp.toPx(), size.height - 2.dp.toPx()),
            strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.delay
import platform.UIKit.UIViewController
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.feature.courseware.CoursewareCoursePickerList
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter

/** Swift DEBUG-only entry point. Synthetic courses, no session or network. */
fun CoursewarePickerProbeViewController(): UIViewController {
    lateinit var controller: UIViewController
    val presenter = IosNativeSheetPresenter { controller }
    controller = ComposeUIViewController {
        PlatformAppTheme(isSystemInDarkTheme(), dynamicColorEnabled = false) {
            var visible by remember { mutableStateOf(false) }
            val courses = remember {
                (1..20).map { CoursewareCourse(it, if (it == 20) "最后一门课程 · 验收标记" else "测试课程 $it · 计算机系统导论", "fixture", "fixture", "fixture", null, emptyList()) }
            }
            LaunchedEffect(Unit) { delay(250); visible = true }
            CompositionLocalProvider(LocalNativeSheetPresenter provides presenter) {
                if (visible) {
                    AppleSheet(onDismissRequest = { visible = false }, title = "选择课程", needsFullHeight = true, scrollableBody = true) {
                        CoursewareCoursePickerList(courses, 2, emptySet(), { visible = false }, Modifier.fillMaxWidth().weight(1f))
                    }
                } else Text("课件弹窗验收")
            }
        }
    }
    return controller
}

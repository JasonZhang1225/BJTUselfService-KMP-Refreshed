package team.bjtuss.bjtuselfservice.shared.feature.grade

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.feature.course.colorCategory
import team.bjtuss.bjtuselfservice.shared.feature.course.courseCategoryColors

internal data class GradeTypeColors(
    val container: Color,
    val onContainer: Color,
    /** 描边用色：半透明的 onContainer，浅深色下都能看清。 */
    val border: Color = onContainer.copy(alpha = 0.55f),
)

/**
 * 成绩页的课程性质配色，与课程表、首页、小组件同取 [courseCategoryColors]，不另设色值。
 */
@Composable
internal fun courseTypeColors(type: CourseType): GradeTypeColors = courseCategoryColors(type.colorCategory())

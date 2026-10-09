package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AssignmentCardHeader(course: String, title: String, submitted: Boolean, source: AssignmentSource? = null,
    statusLabel: String = if (submitted) "已提交" else "未提交") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(course, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        source?.let { AssignmentSourceBadge(it) }
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Surface(shape = RoundedCornerShape(999.dp),
            color = if (submitted) { if (dark) Color(0xFF174D2A) else Color(0xFFD9F2DF) }
                else { if (dark) Color(0xFF5B4500) else Color(0xFFFFEFC2) },
            contentColor = if (submitted) { if (dark) Color(0xFF9BE7AA) else Color(0xFF1C6B35) }
                else { if (dark) Color(0xFFFFD66B) else Color(0xFF7A4F00) }) {
            Text(statusLabel, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun AssignmentSourceBadge(source: AssignmentSource) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = when (source) {
        AssignmentSource.COURSE_PLATFORM -> if (dark) Color(0xFF143B66) to Color(0xFFAFD1FF) else Color(0xFFDFEDFF) to Color(0xFF22548D)
        AssignmentSource.PHYVLAB -> if (dark) Color(0xFF4B285F) to Color(0xFFE8BCFF) else Color(0xFFF0E2F8) to Color(0xFF6B3787)
        AssignmentSource.CITEL -> if (dark) Color(0xFF154A49) to Color(0xFFA1E3DE) else Color(0xFFDDF3EF) to Color(0xFF23645F)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = colors.first, contentColor = colors.second) {
        Text(source.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

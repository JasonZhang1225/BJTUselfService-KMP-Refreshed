package team.bjtuss.bjtuselfservice.shared.feature.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.update.BundledChangelog
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalBottomBarClearance
import team.bjtuss.bjtuselfservice.shared.feature.shell.TopScrollLazyColumn

@Composable
internal fun ChangelogWorkspace(modifier: Modifier = Modifier) {
    var selectedTag by rememberSaveable { mutableStateOf(BundledChangelog.entries.first().tag) }
    var expanded by remember { mutableStateOf(false) }
    val release = BundledChangelog.entries.first { it.tag == selectedTag }
    val topClearance = LocalTopBarClearance.current
    val bottomClearance = LocalBottomBarClearance.current
    // Keep the picker inside the same viewport as the notes so native scrolling,
    // safe-area padding and glass-bar progress all describe the whole page.
    key(selectedTag) {
        TopScrollLazyColumn(
            state = rememberLazyListState(),
            modifier = modifier,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 16.dp + topClearance, bottom = 16.dp + bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择版本，查看本次更新", style = MaterialTheme.typography.bodyMedium)
                    Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(release.label) }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            BundledChangelog.entries.forEach { entry ->
                                DropdownMenuItem(text = { Text(entry.label) }, onClick = {
                                    selectedTag = entry.tag
                                    expanded = false
                                })
                            }
                        }
                    }
                }
            }
            item { Text(release.label, style = MaterialTheme.typography.titleLarge) }
            release.sections.forEach { section ->
                item { Text(section.title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }) }
                items(section.items) { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

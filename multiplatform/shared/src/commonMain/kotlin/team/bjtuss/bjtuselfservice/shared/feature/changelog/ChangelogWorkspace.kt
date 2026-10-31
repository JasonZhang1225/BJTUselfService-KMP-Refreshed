package team.bjtuss.bjtuselfservice.shared.feature.changelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable
internal fun ChangelogWorkspace(modifier: Modifier = Modifier) {
    var selectedTag by rememberSaveable { mutableStateOf(BundledChangelog.entries.first().tag) }
    var expanded by remember { mutableStateOf(false) }
    val release = BundledChangelog.entries.first { it.tag == selectedTag }
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择版本，查看本次更新", style = MaterialTheme.typography.bodyMedium)
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
        key(selectedTag) {
            LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(release.label, style = MaterialTheme.typography.titleLarge) }
                release.sections.forEach { section ->
                    item { Text(section.title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }) }
                    items(section.items) { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

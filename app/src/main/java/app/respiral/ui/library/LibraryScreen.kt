package app.respiral.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.VaultEntrySummary
import app.respiral.data.vault.VaultRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LibraryScreen(
    repository: VaultRepository,
    onEntrySelected: (VaultEntrySummary) -> Unit,
    onReflect: (Set<VaultTag>) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(repository, scope) { LibraryViewModel(repository, scope) }
    val entries by viewModel.entries.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Your library", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = viewModel.query,
                onValueChange = { viewModel.query = it },
                modifier = Modifier.fillMaxWidth().testTag("library-search"),
                label = { Text("Search your notes") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultTag.entries.forEach { tag ->
                    FilterChip(
                        selected = tag in viewModel.selectedTags,
                        onClick = { viewModel.toggleTag(tag) },
                        label = { Text(tag.displayName()) },
                    )
                }
            }
            Button(onClick = { onReflect(viewModel.selectedTags) }) {
                Text(if (viewModel.selectedTags.isEmpty()) "Reflect on a note" else "Reflect on these notes")
            }
            if (entries.isEmpty()) {
                val emptyMessage = if (viewModel.query.isBlank() && viewModel.selectedTags.isEmpty()) {
                    "Nothing is here yet. Add a small good thing whenever you are ready."
                } else {
                    "Nothing matches this search yet. You can soften the filters whenever you like."
                }
                Text(emptyMessage, style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        TimelineEntry(entry = entry, onClick = { onEntrySelected(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEntry(entry: VaultEntrySummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("timeline-entry")
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleMedium)
        Text(
            entry.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM uuuu")),
            style = MaterialTheme.typography.bodySmall,
        )
        if (entry.tags.isNotEmpty()) Text(entry.tags.joinToString(" · ") { it.displayName() })
    }
}

private fun VaultTag.displayName(): String = when (this) {
    VaultTag.ACHIEVEMENT -> "Achievement"
    VaultTag.AFFIRMATION -> "Affirmation"
    VaultTag.WHO_I_AM -> "Who I am"
}

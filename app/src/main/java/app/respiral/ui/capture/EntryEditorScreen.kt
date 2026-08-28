package app.respiral.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.VaultRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EntryEditorScreen(
    repository: VaultRepository,
    entryId: UUID?,
    prompt: String,
    initialTags: Set<VaultTag>,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val viewModel = remember(entryId, prompt, initialTags) {
        EntryEditorViewModel(repository, entryId, prompt, initialTags)
    }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        if (entryId != null && !viewModel.load()) message = viewModel.errorMessage
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (entryId == null) "Add something good" else "Edit entry",
                style = MaterialTheme.typography.headlineMedium,
            )
            OutlinedTextField(
                value = viewModel.title,
                onValueChange = { viewModel.title = it },
                modifier = Modifier.fillMaxWidth().testTag("entry-title"),
                label = { Text("Title") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            OutlinedTextField(
                value = viewModel.body,
                onValueChange = { viewModel.body = it },
                modifier = Modifier.fillMaxWidth().testTag("entry-body"),
                label = { Text("What would you like to remember?") },
                minLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Text("Tags", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultTag.entries.forEach { tag ->
                    FilterChip(
                        selected = tag in viewModel.tags,
                        onClick = {
                            viewModel.tags = if (tag in viewModel.tags) viewModel.tags - tag else viewModel.tags + tag
                        },
                        label = { Text(tag.displayName()) },
                    )
                }
            }
            PhotoPicker(
                onPhotoSelected = viewModel::addPhoto,
                onSelectionFailure = { message = "Photo wasn't added. Your note is still here." },
            )
            if (viewModel.media.isNotEmpty()) Text("${viewModel.media.size} photo${if (viewModel.media.size == 1) "" else "s"} selected")
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isLoading,
                onClick = {
                    scope.launch {
                        if (viewModel.save()) {
                            withContext(Dispatchers.Main.immediate) { onSaved(viewModel.title) }
                        } else {
                            message = viewModel.errorMessage
                        }
                    }
                },
            ) { Text("Save") }
            if (entryId != null) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("This permanently removes the entry and its photos from your private vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            if (viewModel.delete()) {
                                withContext(Dispatchers.Main.immediate) { onDeleted() }
                            } else {
                                message = viewModel.errorMessage
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun VaultTag.displayName(): String = when (this) {
    VaultTag.ACHIEVEMENT -> "Achievement"
    VaultTag.AFFIRMATION -> "Affirmation"
    VaultTag.WHO_I_AM -> "Who I am"
}

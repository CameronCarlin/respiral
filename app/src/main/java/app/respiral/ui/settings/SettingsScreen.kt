package app.respiral.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.respiral.core.model.VaultSettings
import app.respiral.data.vault.ImportMode
import app.respiral.data.vault.ImportPreview
import app.respiral.notifications.ReminderMode

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    importPreview: ImportPreview? = null,
    onDismissImportPreview: () -> Unit = {},
    onApplyImport: (ImportMode) -> Unit = {},
    feedbackMessage: String? = null,
) {
    val settings = viewModel.settings
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { TextButton(onClick = onBack) { Text("Back") } }
            item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text(
                    "Respiral stays on this phone. These choices control your private vault and gentle reminders.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { HorizontalDivider() }
            item {
                SettingSwitch(
                    title = "Lock my vault",
                    description = "Use your fingerprint or device passcode when you return.",
                    checked = settings.lockEnabled,
                    onCheckedChange = viewModel::setLockEnabled,
                    tag = "lock-vault",
                )
            }
            item { Text("Daily reminders", style = MaterialTheme.typography.titleMedium) }
            item {
                ReminderSwitch(
                    mode = ReminderMode.SAVED_ENTRY,
                    settings = settings,
                    viewModel = viewModel,
                    onRequestPermission = onRequestNotificationPermission,
                )
            }
            item {
                ReminderSwitch(
                    mode = ReminderMode.PRIVATE_NUDGE,
                    settings = settings,
                    viewModel = viewModel,
                    onRequestPermission = onRequestNotificationPermission,
                )
            }
            item {
                ReminderSwitch(
                    mode = ReminderMode.CAPTURE_PROMPT,
                    settings = settings,
                    viewModel = viewModel,
                    onRequestPermission = onRequestNotificationPermission,
                )
            }
            item {
                OutlinedTextField(
                    value = settings.reminderTime?.toString().orEmpty(),
                    onValueChange = viewModel::setReminderTime,
                    modifier = Modifier.fillMaxWidth().testTag("reminder-time"),
                    label = { Text("Reminder time (24-hour)") },
                    placeholder = { Text("08:00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                SettingSwitch(
                    title = "Show saved words on the lock screen",
                    description = "Off keeps notification text private by default.",
                    checked = settings.revealNotificationText,
                    onCheckedChange = viewModel::setRevealNotificationText,
                    tag = "reveal-notification-text",
                )
            }
            item { HorizontalDivider() }
            item { Text("Vault transfer", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = onExport) { Text("Export vault") }
                    Button(modifier = Modifier.weight(1f), onClick = onImport) { Text("Import vault") }
                }
            }
            (feedbackMessage ?: viewModel.message)?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onDismiss = onDismissImportPreview,
            onApply = onApplyImport,
        )
    }
}

@Composable
private fun ReminderSwitch(
    mode: ReminderMode,
    settings: VaultSettings,
    viewModel: SettingsViewModel,
    onRequestPermission: () -> Unit,
) {
    val enabled = mode in settings.reminderModes
    SettingSwitch(
        title = mode.title(),
        description = mode.description(),
        checked = enabled,
        onCheckedChange = { checked ->
            viewModel.setReminderMode(mode, checked)
            if (checked) onRequestPermission()
        },
        tag = "reminder-${mode.name.lowercase().replace('_', '-')}"
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onApply: (ImportMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import this vault?") },
        text = {
            Text(
                "${preview.entryCount} notes and ${preview.mediaCount} photos are ready. " +
                    "Choose how to add them to this private vault.",
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onApply(ImportMode.MERGE) }) { Text("Merge") }
                TextButton(onClick = { onApply(ImportMode.REPLACE) }) { Text("Replace") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ReminderMode.title(): String = when (this) {
    ReminderMode.SAVED_ENTRY -> "A saved entry"
    ReminderMode.PRIVATE_NUDGE -> "A private nudge"
    ReminderMode.CAPTURE_PROMPT -> "A capture prompt"
}

private fun ReminderMode.description(): String = when (this) {
    ReminderMode.SAVED_ENTRY -> "A saved reminder, without words on the lock screen by default."
    ReminderMode.PRIVATE_NUDGE -> "A quiet reminder that your vault is here."
    ReminderMode.CAPTURE_PROMPT -> "A gentle invitation to write something good down."
}

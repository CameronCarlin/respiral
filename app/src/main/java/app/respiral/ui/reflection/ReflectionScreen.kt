package app.respiral.ui.reflection

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.vault.VaultRepository
import app.respiral.data.vault.VaultHealth
import app.respiral.ui.theme.Mustard
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReflectionScreen(
    repository: VaultRepository,
    tags: Set<VaultTag>,
    onBack: () -> Unit,
) {
    val viewModel = remember(repository, tags) { ReflectionViewModel(repository, tags) }
    val health by repository.health.collectAsState(initial = VaultHealth.Loading)
    val scope = rememberCoroutineScope()
    var breathing by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableIntStateOf(30) }
    val breathScale = remember { Animatable(1f) }

    LaunchedEffect(viewModel) { viewModel.nextEntry() }
    LaunchedEffect(breathing) {
        if (!breathing) {
            breathScale.snapTo(1f)
            secondsRemaining = 30
        } else {
            coroutineScope {
                launch {
                    repeat(30) { second ->
                        secondsRemaining = 30 - second
                        delay(1_000)
                    }
                }
                repeat(4) {
                    breathScale.animateTo(1.08f, tween(3_750, easing = LinearEasing))
                    breathScale.animateTo(1f, tween(3_750, easing = LinearEasing))
                }
                breathing = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("A few words you left for yourself", style = MaterialTheme.typography.headlineMedium)
            if (viewModel.entry != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .scale(breathScale.value)
                        .background(Mustard),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Take this gently", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (viewModel.entry != null || health !is VaultHealth.NeedsAttention) {
                health.messageOrNull()?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
            }
            when {
                viewModel.isLoading -> Text("Finding a note…")
                viewModel.entry != null -> ReflectionEntry(viewModel.entry!!)
                else -> {
                    Text(viewModel.message.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    if (viewModel.shouldKeepAppData) {
                        Text(
                            "Do not uninstall Respiral or clear its app data.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isLoading,
                onClick = { scope.launch { viewModel.nextEntry() } },
            ) { Text("Next") }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { breathing = !breathing },
            ) {
                Text(if (breathing) "Breathing · ${secondsRemaining}s" else "Pause for 30 seconds")
            }
        }
    }
}

@Composable
private fun ReflectionEntry(entry: VaultEntry) {
    Column(
        modifier = Modifier.testTag("reflection-entry"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(entry.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleLarge)
        Text(entry.body, style = MaterialTheme.typography.bodyLarge)
        entry.media.firstOrNull()?.let { media -> LocalVaultImage(media.relativePath) }
    }
}

@Composable
private fun LocalVaultImage(relativePath: String) {
    val context = LocalContext.current
    val bitmap = remember(relativePath) {
        BitmapFactory.decodeFile(File(context.filesDir, "vault/$relativePath").path)
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Photo attached to this note",
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )
    }
}

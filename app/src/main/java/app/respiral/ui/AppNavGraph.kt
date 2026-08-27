package app.respiral.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultTag
import app.respiral.data.index.RespiralDatabase
import app.respiral.data.settings.DataStoreSettingsRepository
import app.respiral.data.settings.SettingsRepository
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultRepository
import app.respiral.ui.capture.EntryEditorScreen
import app.respiral.ui.onboarding.WelcomePrompt
import app.respiral.ui.onboarding.WelcomeRitualScreen
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val WELCOME_ROUTE = "welcome"
private const val ARRIVAL_ROUTE = "arrival"
private const val EDITOR_ROUTE = "editor?id={id}&prompt={prompt}&tags={tags}"

@Composable
fun AppNavGraph() {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { defaultVaultRepository(context) }
    val settingsRepository = remember(context) { DataStoreSettingsRepository.create(context) }
    AppNavGraph(repository, settingsRepository)
}

@Composable
internal fun AppNavGraph(repository: VaultRepository, settingsRepository: SettingsRepository) {
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val currentSettings = settings ?: return
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = if (currentSettings.onboardingSeen) ARRIVAL_ROUTE else WELCOME_ROUTE,
    ) {
        composable(WELCOME_ROUTE) {
            WelcomeRitualScreen(
                onPromptSelected = { prompt -> navController.navigate(editorRoute(prompt = prompt)) },
                onFreeformEntry = { navController.navigate(editorRoute()) },
                onSkip = {
                    scope.launch {
                        markOnboardingSeen(settingsRepository)
                        navController.navigate(ARRIVAL_ROUTE) {
                            popUpTo(WELCOME_ROUTE) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(
            route = EDITOR_ROUTE,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("prompt") { type = NavType.StringType; defaultValue = "" },
                navArgument("tags") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.takeIf(String::isNotBlank)?.let(UUID::fromString)
            val prompt = backStackEntry.arguments?.getString("prompt").orEmpty()
            val tags = backStackEntry.arguments?.getString("tags")
                .orEmpty()
                .split(',')
                .mapNotNull { runCatching { VaultTag.valueOf(it) }.getOrNull() }
                .toSet()
            EntryEditorScreen(
                repository = repository,
                entryId = id,
                prompt = prompt,
                initialTags = tags,
                onSaved = {
                    scope.launch {
                        markOnboardingSeen(settingsRepository)
                        navController.navigate(ARRIVAL_ROUTE) {
                            popUpTo(ARRIVAL_ROUTE) { inclusive = true }
                        }
                    }
                },
                onDeleted = {
                    navController.navigate(ARRIVAL_ROUTE) {
                        popUpTo(ARRIVAL_ROUTE) { inclusive = true }
                    }
                },
            )
        }
        composable(ARRIVAL_ROUTE) {
            ArrivalScreen(repository = repository, onAddEntry = { navController.navigate(editorRoute()) })
        }
    }
}

private suspend fun markOnboardingSeen(settingsRepository: SettingsRepository) {
    val settings = settingsRepository.settings.first()
    if (!settings.onboardingSeen) settingsRepository.update(settings.copy(onboardingSeen = true))
}

private fun editorRoute(id: UUID? = null, prompt: WelcomePrompt? = null): String = buildString {
    append("editor?id=")
    append(id?.toString().orEmpty())
    append("&prompt=")
    append(Uri.encode(prompt?.prompt.orEmpty()))
    append("&tags=")
}

private fun defaultVaultRepository(context: Context): VaultRepository = DefaultVaultRepository(
    VaultFileStore(context, CanonicalMarkdownEntryCodec()),
    RespiralDatabase.create(context),
)

@Composable
private fun ArrivalScreen(repository: VaultRepository, onAddEntry: () -> Unit) {
    val entries by repository.observeTimeline(query = "", tags = emptySet()).collectAsState(initial = emptyList())
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(text = "Respiral", style = MaterialTheme.typography.displaySmall)
            Button(modifier = Modifier.fillMaxWidth(), onClick = {}) { Text("Remind me who I am") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onAddEntry) { Text("Add something good") }
            entries.forEach { entry -> Text(entry.title, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

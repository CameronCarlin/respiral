package app.respiral.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.respiral.RespiralApplication
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultTag
import app.respiral.data.index.RespiralDatabase
import app.respiral.data.settings.SettingsRepository
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultRepository
import app.respiral.ui.arrival.ArrivalScreen
import app.respiral.ui.capture.EntryEditorScreen
import app.respiral.ui.library.LibraryScreen
import app.respiral.ui.onboarding.WelcomePrompt
import app.respiral.ui.onboarding.WelcomeRitualScreen
import app.respiral.ui.reflection.ReflectionScreen
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val WELCOME_ROUTE = "welcome"
private const val ARRIVAL_ROUTE = "arrival"
private const val LIBRARY_ROUTE = "library"
private const val REFLECTION_ROUTE = "reflection?tags={tags}"
private const val EDITOR_ROUTE = "editor?id={id}&prompt={prompt}&tags={tags}"

@Composable
fun AppNavGraph() {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { defaultVaultRepository(context) }
    val settingsRepository = remember(context) { RespiralApplication.from(context).settingsRepository }
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
                        navController.navigate(LIBRARY_ROUTE) {
                            // Saving is a terminal action for the capture flow. Remove the
                            // welcome/editor routes so Back cannot reopen a stale draft.
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    }
                },
                onDeleted = {
                    navController.navigate(LIBRARY_ROUTE) {
                        popUpTo(LIBRARY_ROUTE) { inclusive = true }
                    }
                },
            )
        }
        composable(ARRIVAL_ROUTE) {
            ArrivalScreen(
                onRemindMe = { navController.navigate(reflectionRoute()) },
                onAddEntry = { navController.navigate(editorRoute()) },
            )
        }
        composable(LIBRARY_ROUTE) {
            LibraryScreen(
                repository = repository,
                onEntrySelected = { entry -> navController.navigate(editorRoute(id = entry.id)) },
                onReflect = { tags -> navController.navigate(reflectionRoute(tags)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = REFLECTION_ROUTE,
            arguments = listOf(navArgument("tags") { type = NavType.StringType; defaultValue = "" }),
        ) { backStackEntry ->
            ReflectionScreen(
                repository = repository,
                tags = parseTags(backStackEntry.arguments?.getString("tags").orEmpty()),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private suspend fun markOnboardingSeen(settingsRepository: SettingsRepository) {
    val settings = settingsRepository.settings.first()
    if (!settings.onboardingSeen) settingsRepository.update(settings.copy(onboardingSeen = true))
}

private fun editorRoute(id: UUID? = null, prompt: WelcomePrompt? = null, tags: Set<VaultTag> = emptySet()): String = buildString {
    append("editor?id=")
    append(id?.toString().orEmpty())
    append("&prompt=")
    append(Uri.encode(prompt?.prompt.orEmpty()))
    append("&tags=")
    append(tags.sortedBy(VaultTag::ordinal).joinToString(",") { it.name })
}

private fun reflectionRoute(tags: Set<VaultTag> = emptySet()): String = buildString {
    append("reflection?tags=")
    append(tags.sortedBy(VaultTag::ordinal).joinToString(",") { it.name })
}

private fun parseTags(tags: String): Set<VaultTag> = tags
    .split(',')
    .mapNotNull { runCatching { VaultTag.valueOf(it) }.getOrNull() }
    .toSet()

private fun defaultVaultRepository(context: Context): VaultRepository = DefaultVaultRepository(
    VaultFileStore(context, CanonicalMarkdownEntryCodec()),
    RespiralDatabase.create(context),
)

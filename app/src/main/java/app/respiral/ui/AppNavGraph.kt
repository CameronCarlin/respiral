package app.respiral.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.respiral.RespiralApplication
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultSettings
import app.respiral.core.model.VaultTag
import app.respiral.core.time.SystemClock
import app.respiral.data.index.RespiralDatabase
import app.respiral.data.settings.SettingsRepository
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.ImportPreview
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultRepository
import app.respiral.data.vault.ZipVaultTransfer
import app.respiral.notifications.AndroidAlarmGateway
import app.respiral.notifications.DefaultReminderScheduler
import app.respiral.notifications.ReminderScheduler
import app.respiral.security.AndroidVaultAuthenticator
import app.respiral.security.AuthenticationResult
import app.respiral.security.VaultAuthenticator
import app.respiral.security.VaultSession
import app.respiral.ui.arrival.ArrivalScreen
import app.respiral.ui.capture.EntryEditorScreen
import app.respiral.ui.library.LibraryScreen
import app.respiral.ui.onboarding.WelcomePrompt
import app.respiral.ui.onboarding.WelcomeRitualScreen
import app.respiral.ui.reflection.ReflectionScreen
import app.respiral.ui.security.LockedVaultScreen
import app.respiral.ui.settings.SettingsScreen
import app.respiral.ui.settings.SettingsViewModel
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val WELCOME_ROUTE = "welcome"
private const val ARRIVAL_ROUTE = "arrival"
private const val LIBRARY_ROUTE = "library"
private const val SETTINGS_ROUTE = "settings"
private const val REFLECTION_ROUTE = "reflection?tags={tags}"
private const val EDITOR_ROUTE = "editor?id={id}&prompt={prompt}&tags={tags}"

@Composable
fun AppNavGraph(initialRoute: String? = null) {
    val context = LocalContext.current.applicationContext
    val application = RespiralApplication.from(context)
    val repository = remember(context) { defaultVaultRepository(context) }
    val settingsRepository = remember(context) { application.settingsRepository }
    val session = remember(context) { application.vaultSession }
    val scheduler = remember(context) { DefaultReminderScheduler(AndroidAlarmGateway(context)) }
    val transfer = remember(context, repository) {
        ZipVaultTransfer(repository, VaultFileStore(context, CanonicalMarkdownEntryCodec()), context.cacheDir)
    }
    AppNavGraph(
        repository = repository,
        settingsRepository = settingsRepository,
        initialRoute = initialRoute,
        session = session,
        authenticator = remember { AndroidVaultAuthenticator() },
        scheduler = scheduler,
        transfer = transfer,
    )
}

@Composable
internal fun AppNavGraph(
    repository: VaultRepository,
    settingsRepository: SettingsRepository,
    initialRoute: String? = null,
    session: VaultSession = remember { app.respiral.security.DefaultVaultSession(SystemClock) },
    authenticator: VaultAuthenticator = remember { AndroidVaultAuthenticator() },
    scheduler: ReminderScheduler = remember { NoOpReminderScheduler },
    transfer: ZipVaultTransfer? = null,
) {
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val currentSettings = settings ?: return
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var authenticationMessage by remember { mutableStateOf<String?>(null) }
    var authenticating by remember { mutableStateOf(false) }
    var unlockedVersion by remember { mutableStateOf(0) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var importSource by remember { mutableStateOf<Uri?>(null) }
    var transferMessage by remember { mutableStateOf<String?>(null) }

    fun authenticate() {
        if (activity == null || authenticating) return
        authenticating = true
        authenticationMessage = null
        scope.launch {
            when (authenticator.authenticate(activity)) {
                AuthenticationResult.Success -> {
                    session.unlock(SystemClock.now())
                    unlockedVersion += 1
                }
                AuthenticationResult.Cancelled -> authenticationMessage = "Vault remains locked."
                AuthenticationResult.Unavailable -> authenticationMessage = "Authentication is unavailable on this device."
            }
            authenticating = false
        }
    }

    @Composable
    fun Guard(content: @Composable () -> Unit) {
        VaultGuard(currentSettings.lockEnabled, session, keyguard, authenticationMessage, authenticating, ::authenticate, unlockedVersion, content)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        if (destination == null || transfer == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferMessage = runCatching {
                context.contentResolver.openOutputStream(destination)?.let { transfer.export(it) } ?: error("Unable to create export")
                "Vault exported privately."
            }.getOrElse { "The vault could not be exported. Nothing was changed." }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null || transfer == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { context.contentResolver.openInputStream(source)?.let { transfer.preview(it) } ?: error("Unable to open import") }
                .onSuccess { importSource = source; importPreview = it; transferMessage = null }
                .onFailure { transferMessage = "That file is not a valid Respiral vault. Nothing was changed." }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) transferMessage = "Notifications remain off until you allow them."
    }

    val startDestination = when (initialRoute) {
        "reflection" -> reflectionRoute()
        "editor" -> editorRoute()
        else -> if (currentSettings.onboardingSeen) ARRIVAL_ROUTE else WELCOME_ROUTE
    }
    val firstComposition = remember { mutableStateOf(true) }
    LaunchedEffect(initialRoute) {
        if (firstComposition.value) firstComposition.value = false
        else when (initialRoute) {
            "reflection" -> navController.navigate(reflectionRoute())
            "editor" -> navController.navigate(editorRoute())
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(WELCOME_ROUTE) {
            WelcomeRitualScreen(
                onPromptSelected = { navController.navigate(editorRoute(prompt = it)) },
                onFreeformEntry = { navController.navigate(editorRoute()) },
                onSkip = { scope.launch { markOnboardingSeen(settingsRepository); navController.navigate(ARRIVAL_ROUTE) { popUpTo(WELCOME_ROUTE) { inclusive = true } } } },
            )
        }
        composable(EDITOR_ROUTE, arguments = listOf(
            navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("prompt") { type = NavType.StringType; defaultValue = "" },
            navArgument("tags") { type = NavType.StringType; defaultValue = "" },
        )) { entry ->
            Guard {
                val id = entry.arguments?.getString("id")?.takeIf(String::isNotBlank)?.let(UUID::fromString)
                EntryEditorScreen(repository, id, entry.arguments?.getString("prompt").orEmpty(), parseTags(entry.arguments?.getString("tags").orEmpty()), onSaved = {
                    scope.launch { markOnboardingSeen(settingsRepository); navController.navigate(LIBRARY_ROUTE) { popUpTo(navController.graph.startDestinationId) { inclusive = true } } }
                }, onDeleted = { navController.navigate(LIBRARY_ROUTE) { popUpTo(LIBRARY_ROUTE) { inclusive = true } } })
            }
        }
        composable(ARRIVAL_ROUTE) {
            Guard { ArrivalScreen({ navController.navigate(reflectionRoute()) }, { navController.navigate(editorRoute()) }, { navController.navigate(SETTINGS_ROUTE) }) }
        }
        composable(LIBRARY_ROUTE) {
            Guard { LibraryScreen(repository, { navController.navigate(editorRoute(id = it.id)) }, { navController.navigate(reflectionRoute(it)) }, { navController.popBackStack() }) }
        }
        composable(REFLECTION_ROUTE, arguments = listOf(navArgument("tags") { type = NavType.StringType; defaultValue = "" })) { entry ->
            Guard { ReflectionScreen(repository, parseTags(entry.arguments?.getString("tags").orEmpty())) { navController.popBackStack() } }
        }
        composable(SETTINGS_ROUTE) {
            Guard {
                val viewModel = remember(settingsRepository, scheduler) { SettingsViewModel(settingsRepository, scheduler, scope) }
                SettingsScreen(
                    viewModel, { navController.popBackStack() },
                    onExport = { transferMessage = null; exportLauncher.launch("respiral-vault.zip") },
                    onImport = { transferMessage = null; importLauncher.launch(arrayOf("application/zip")) },
                    onRequestNotificationPermission = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    importPreview = importPreview,
                    onDismissImportPreview = { importPreview = null; importSource = null },
                    onApplyImport = { mode ->
                        val source = importSource ?: return@SettingsScreen
                        importPreview = null
                        scope.launch {
                            transferMessage = runCatching {
                                transfer?.let { t -> context.contentResolver.openInputStream(source)?.let { t.apply(it, mode) } } ?: error("Import unavailable")
                                "Vault imported privately."
                            }.getOrElse { "The vault could not be imported. Nothing was changed." }
                            importSource = null
                        }
                    },
                    feedbackMessage = transferMessage,
                )
            }
        }
    }
}

@Composable
private fun VaultGuard(lockEnabled: Boolean, session: VaultSession, keyguard: KeyguardManager?, authenticationMessage: String?, authenticating: Boolean, onAuthenticate: () -> Unit, version: Int, content: @Composable () -> Unit) {
    var allowed by remember(lockEnabled, version) { mutableStateOf((!lockEnabled || session.isUnlocked(SystemClock.now())) && keyguard?.isDeviceLocked != true) }
    LaunchedEffect(lockEnabled, version) {
        while (lockEnabled) {
            allowed = keyguard?.isDeviceLocked != true && session.isUnlocked(SystemClock.now())
            delay(1_000)
        }
        allowed = true
    }
    if (!lockEnabled || allowed) Box(Modifier.fillMaxSize().testTag("private-route")) { content() }
    else LockedVaultScreen(onAuthenticate, authenticating, authenticationMessage)
}

private suspend fun markOnboardingSeen(settingsRepository: SettingsRepository) {
    val settings = settingsRepository.settings.first()
    if (!settings.onboardingSeen) settingsRepository.update(settings.copy(onboardingSeen = true))
}

private fun editorRoute(id: UUID? = null, prompt: WelcomePrompt? = null, tags: Set<VaultTag> = emptySet()): String = buildString {
    append("editor?id="); append(id?.toString().orEmpty()); append("&prompt="); append(Uri.encode(prompt?.prompt.orEmpty())); append("&tags=")
    append(tags.sortedBy(VaultTag::ordinal).joinToString(",") { it.name })
}
private fun reflectionRoute(tags: Set<VaultTag> = emptySet()): String = "reflection?tags=" + tags.sortedBy(VaultTag::ordinal).joinToString(",") { it.name }
private fun parseTags(tags: String): Set<VaultTag> = tags.split(',').mapNotNull { runCatching { VaultTag.valueOf(it) }.getOrNull() }.toSet()
private fun defaultVaultRepository(context: Context): VaultRepository = DefaultVaultRepository(VaultFileStore(context, CanonicalMarkdownEntryCodec()), RespiralDatabase.create(context))
private object NoOpReminderScheduler : ReminderScheduler {
    override fun schedule(settings: VaultSettings) = Unit
    override fun cancel() = Unit
}

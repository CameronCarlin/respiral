package app.respiral.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.index.EntryIndexEntity
import app.respiral.data.vault.DefaultVaultRepository
import app.respiral.data.vault.ImportMode
import app.respiral.data.vault.LegacyVaultRecovery
import app.respiral.data.vault.LegacyVaultSource
import app.respiral.data.vault.PendingMedia
import app.respiral.data.vault.PersistedRecoveryState
import app.respiral.data.vault.VaultFileStore
import app.respiral.data.vault.VaultRecoveryStateStore
import app.respiral.data.vault.VaultRuntime
import app.respiral.data.vault.ZipVaultTransfer
import app.respiral.ui.reflection.ReflectionScreen
import app.respiral.ui.theme.RespiralTheme
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class VaultReadinessFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun interactive_vault_work_waits_for_bootstrap_and_reflection_starts_with_recovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = context.cacheDir.resolve("readiness-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val fileStore = VaultFileStore(
            vaultRoot = fixtureRoot.resolve("vault"),
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = context.contentResolver::openInputStream,
        )
        val repository = DefaultVaultRepository(fileStore)
        val bootstrapRelease = CompletableDeferred<Unit>()
        val stateStore = DelayedRecoveryStateStore(bootstrapRelease)
        val recovered = entry(
            id = UUID.nameUUIDFromBytes("readiness-recovered".toByteArray()),
            title = "Recovered before interaction",
            body = "Recovery finished before Reflection began.",
            tags = setOf(VaultTag.AFFIRMATION),
        )
        val runtime = VaultRuntime(
            repository = repository,
            recovery = LegacyVaultRecovery(fileStore),
            recoveryStateStore = stateStore,
            legacyDatabaseExists = { true },
            openLegacyDatabase = {
                object : LegacyVaultSource {
                    override suspend fun snapshot() = listOf(recovered.toLegacyRow())
                    override fun close() = Unit
                }
            },
        )
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saved = entry(
            id = UUID.nameUUIDFromBytes("readiness-save".toByteArray()),
            title = "Saved after readiness",
            tags = setOf(VaultTag.ACHIEVEMENT),
        )
        val imported = entry(
            id = UUID.nameUUIDFromBytes("readiness-import".toByteArray()),
            title = "Imported after readiness",
            tags = setOf(VaultTag.WHO_I_AM),
        )
        val importArchive = archiveOf(imported)
        val exported = ByteArrayOutputStream()
        val operationsResult = CompletableDeferred<Result<Unit>>()

        try {
            runtime.start(runtimeScope)
            runBlocking { withTimeout(5_000) { stateStore.readStarted.await() } }
            composeTestRule.setContent {
                RespiralTheme {
                    VaultReadyGate(runtime::awaitReady) { readyRepository ->
                        LaunchedEffect(readyRepository) {
                            operationsResult.complete(
                                runCatching {
                                    val transfer = ZipVaultTransfer(
                                        readyRepository,
                                        fileStore,
                                        fixtureRoot.resolve("cache"),
                                    )
                                    readyRepository.save(saved, emptyList<PendingMedia>())
                                    transfer.export(exported)
                                    transfer.apply(importArchive.inputStream(), ImportMode.MERGE)
                                    Unit
                                },
                            )
                        }
                        ReflectionScreen(
                            repository = readyRepository,
                            tags = setOf(VaultTag.AFFIRMATION),
                            onBack = {},
                        )
                    }
                }
            }
            composeTestRule.waitForIdle()

            assertThat(operationsResult.isCompleted).isFalse()
            assertThat(exported.size()).isEqualTo(0)
            assertThat(fixtureRoot.resolve("vault/entries/${recovered.id}.md").exists()).isFalse()
            assertThat(fixtureRoot.resolve("vault/entries/${saved.id}.md").exists()).isFalse()
            assertThat(fixtureRoot.resolve("vault/entries/${imported.id}.md").exists()).isFalse()

            bootstrapRelease.complete(Unit)
            composeTestRule.waitUntil(timeoutMillis = 10_000) { operationsResult.isCompleted }
            runBlocking { operationsResult.await() }.getOrThrow()
            composeTestRule.waitUntilAtLeastOneExists(
                hasText("Recovery finished before Reflection began."),
                5_000,
            )

            assertThat(exported.size()).isGreaterThan(0)
            assertThat(fixtureRoot.resolve("vault/entries/${saved.id}.md").isFile).isTrue()
            assertThat(fixtureRoot.resolve("vault/entries/${imported.id}.md").isFile).isTrue()
            composeTestRule.onNodeWithText("Recovered before interaction").assertExists()
            composeTestRule.onNodeWithText("Recovery finished before Reflection began.").assertExists()
        } finally {
            runtimeScope.cancel()
            fixtureRoot.deleteRecursively()
        }
    }
}

private class DelayedRecoveryStateStore(
    private val release: CompletableDeferred<Unit>,
) : VaultRecoveryStateStore {
    val readStarted = CompletableDeferred<Unit>()
    private var state = PersistedRecoveryState()

    override suspend fun readRecoveryState(): PersistedRecoveryState {
        readStarted.complete(Unit)
        release.await()
        return state
    }

    override suspend fun writeRecoveryState(state: PersistedRecoveryState) {
        this.state = state
    }
}

private fun entry(
    id: UUID,
    title: String,
    body: String = "Private fixture body.",
    tags: Set<VaultTag>,
): VaultEntry {
    val timestamp = Instant.parse("2026-08-28T07:00:00Z")
    return VaultEntry(
        id = id,
        title = title,
        body = body,
        createdAt = timestamp,
        updatedAt = timestamp,
        tags = tags,
        media = emptyList(),
    )
}

private fun VaultEntry.toLegacyRow() = EntryIndexEntity(
    id = id.toString(),
    title = title,
    bodyForSearch = body,
    createdAtEpochMs = createdAt.toEpochMilli(),
    updatedAtEpochMs = updatedAt.toEpochMilli(),
    tagNames = tags.joinToString(prefix = "|", postfix = "|", separator = "|") { it.name },
)

private fun archiveOf(entry: VaultEntry): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
        zip.writeTextEntry(
            "manifest.json",
            JSONObject()
                .put("formatVersion", 1)
                .put("createdAt", "2026-08-28T07:00:00Z")
                .put("entryCount", 1)
                .put("mediaCount", 0)
                .toString(),
        )
        zip.writeTextEntry("entries/${entry.id}.md", CanonicalMarkdownEntryCodec().encode(entry))
    }
}.toByteArray()

private fun ZipOutputStream.writeTextEntry(path: String, content: String) {
    putNextEntry(ZipEntry(path).apply { time = 0L })
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

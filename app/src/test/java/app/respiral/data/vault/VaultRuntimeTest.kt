package app.respiral.data.vault

import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.data.index.EntryIndexEntity
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultRuntimeTest {
    @Test
    fun runtime_recovers_once_then_publishes_the_file_projection() = runTest {
        val fixture = fixture(
            rows = listOf(legacyRow(title = "Recovered")),
        )

        fixture.runtime.start(backgroundScope)
        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.source.openCalls).isEqualTo(1)
        assertThat(fixture.source.snapshotCalls).isEqualTo(1)
        assertThat(fixture.source.closeCalls).isEqualTo(1)
        assertThat(fixture.repository.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("Recovered")
        assertThat(fixture.repository.health.value).isEqualTo(VaultHealth.Recovered(1))
        assertThat(fixture.stateStore.state.version).isEqualTo(1)
    }

    @Test
    fun runtime_database_failure_keeps_valid_markdown_available_and_retries_next_launch() = runTest {
        val fixture = fixture(snapshotFailure = IllegalStateException("db broken"))
        fixture.store.write(entry(title = "Already safe"))

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R03, 1))
        assertThat(fixture.repository.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("Already safe")
        assertThat(fixture.stateStore.state)
            .isEqualTo(PersistedRecoveryState(0, VaultDiagnosticCode.RSP_R03, 1))
        assertThat(fixture.source.closeCalls).isEqualTo(1)
    }

    @Test
    fun completed_recovery_is_not_opened_again_but_persisted_health_is_published() = runTest {
        val persisted = PersistedRecoveryState(1, VaultDiagnosticCode.RSP_R02, 2)
        val fixture = fixture(initialState = persisted)
        fixture.store.write(entry(title = "Canonical"))

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.source.openCalls).isEqualTo(0)
        assertThat(fixture.repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 2))
    }

    @Test
    fun absent_legacy_database_marks_recovery_complete_without_opening_room() = runTest {
        val fixture = fixture(databaseExists = false)

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.source.openCalls).isEqualTo(0)
        assertThat(fixture.stateStore.state).isEqualTo(PersistedRecoveryState(1, null, 0))
        assertThat(fixture.repository.health.value).isEqualTo(VaultHealth.Healthy)
    }

    @Test
    fun recovery_state_failure_still_publishes_valid_markdown_with_safe_health() = runTest {
        val fixture = fixture(stateReadFailure = IllegalStateException("settings broken"))
        fixture.store.write(entry(title = "Still available"))

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.repository.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("Still available")
        assertThat(fixture.repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R03, 1))
    }

    @Test
    fun already_cancelled_start_scope_completes_readiness_exceptionally_instead_of_hanging() = runTest {
        val fixture = fixture()
        val cancelledJob = Job().apply { cancel(CancellationException("already stopped")) }
        val cancelledScope = CoroutineScope(cancelledJob + StandardTestDispatcher(testScheduler))
        val awaiting = async { fixture.runtime.awaitReady() }

        fixture.runtime.start(cancelledScope)
        advanceUntilIdle()

        assertThat(awaiting.isCompleted).isTrue()
        val failure = runCatching { awaiting.await() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun cancellation_during_fallback_completes_readiness_exceptionally_instead_of_being_swallowed() = runTest {
        val fixture = fixture(
            stateReadFailure = IllegalStateException("settings broken"),
            stateWriteFailure = CancellationException("fallback cancelled"),
        )
        val awaiting = async { fixture.runtime.awaitReady() }

        fixture.runtime.start(this)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000) { awaiting.join() }
        }

        assertThat(awaiting.isCompleted).isTrue()
        val failure = runCatching { awaiting.await() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).hasMessageThat().contains("fallback cancelled")
    }

    @Test
    fun database_failure_takes_health_precedence_over_malformed_markdown() = runTest {
        val fixture = fixture(snapshotFailure = IllegalStateException("db broken"))
        fixture.store.rootDirectory.resolve("entries/broken.md").apply {
            parentFile!!.mkdirs()
            writeText("not canonical")
        }

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R03, 1))
        assertThat(fixture.stateStore.state)
            .isEqualTo(PersistedRecoveryState(0, VaultDiagnosticCode.RSP_R03, 1))
    }

    @Test
    fun database_open_failure_persists_safe_retry_state_and_keeps_markdown_available() = runTest {
        val fixture = fixture(openFailure = IllegalStateException("cannot open"))
        fixture.store.write(entry(title = "Canonical"))

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.repository.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("Canonical")
        assertThat(fixture.repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R03, 1))
        assertThat(fixture.stateStore.state)
            .isEqualTo(PersistedRecoveryState(0, VaultDiagnosticCode.RSP_R03, 1))
        assertThat(fixture.source.closeCalls).isEqualTo(0)
    }

    @Test
    fun recovery_state_write_failure_keeps_recovered_markdown_available_and_ready() = runTest {
        val fixture = fixture(
            rows = listOf(legacyRow(title = "Recovered before write failed")),
            stateWriteFailure = IllegalStateException("cannot persist"),
        )

        fixture.runtime.start(backgroundScope)
        fixture.runtime.awaitReady()

        assertThat(fixture.repository.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("Recovered before write failed")
        assertThat(fixture.repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R03, 1))
        assertThat(fixture.source.closeCalls).isEqualTo(1)
    }

    private fun fixture(
        rows: List<EntryIndexEntity> = emptyList(),
        snapshotFailure: Throwable? = null,
        initialState: PersistedRecoveryState = PersistedRecoveryState(),
        databaseExists: Boolean = true,
        stateReadFailure: Throwable? = null,
        stateWriteFailure: Throwable? = null,
        openFailure: Throwable? = null,
    ): Fixture {
        val root = Files.createTempDirectory("respiral-runtime-").toFile()
        val store = VaultFileStore(root, CanonicalMarkdownEntryCodec()) { null }
        val repository = DefaultVaultRepository(store)
        val stateStore = FakeRecoveryStateStore(initialState, stateReadFailure, stateWriteFailure)
        val source = FakeLegacySource(rows, snapshotFailure, openFailure)
        return Fixture(
            store = store,
            repository = repository,
            stateStore = stateStore,
            source = source,
            runtime = VaultRuntime(
                repository = repository,
                recovery = LegacyVaultRecovery(store),
                recoveryStateStore = stateStore,
                legacyDatabaseExists = { databaseExists },
                openLegacyDatabase = source::open,
            ),
        )
    }

    private data class Fixture(
        val store: VaultFileStore,
        val repository: DefaultVaultRepository,
        val stateStore: FakeRecoveryStateStore,
        val source: FakeLegacySource,
        val runtime: VaultRuntime,
    )

    private class FakeRecoveryStateStore(
        var state: PersistedRecoveryState,
        private val readFailure: Throwable?,
        private val writeFailure: Throwable?,
    ) : VaultRecoveryStateStore {
        override suspend fun readRecoveryState(): PersistedRecoveryState {
            readFailure?.let { throw it }
            return state
        }
        override suspend fun writeRecoveryState(state: PersistedRecoveryState) {
            writeFailure?.let { throw it }
            this.state = state
        }
    }

    private class FakeLegacySource(
        private val rows: List<EntryIndexEntity>,
        private val snapshotFailure: Throwable?,
        private val openFailure: Throwable?,
    ) {
        var openCalls = 0
        var snapshotCalls = 0
        var closeCalls = 0

        fun open(): LegacyVaultSource {
            openCalls += 1
            openFailure?.let { throw it }
            return object : LegacyVaultSource {
                override suspend fun snapshot(): List<EntryIndexEntity> {
                    snapshotCalls += 1
                    snapshotFailure?.let { throw it }
                    return rows
                }

                override fun close() {
                    closeCalls += 1
                }
            }
        }
    }

    private fun legacyRow(title: String): EntryIndexEntity {
        val now = Instant.parse("2026-08-28T07:00:00Z")
        return EntryIndexEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            bodyForSearch = "I stayed kind under pressure.",
            createdAtEpochMs = now.toEpochMilli(),
            updatedAtEpochMs = now.toEpochMilli(),
            tagNames = "|AFFIRMATION|",
        )
    }

    private fun entry(title: String) = app.respiral.core.model.VaultEntry(
        id = UUID.randomUUID(),
        title = title,
        body = "Still here.",
        createdAt = Instant.parse("2026-08-28T07:00:00Z"),
        updatedAt = Instant.parse("2026-08-28T07:00:00Z"),
        tags = emptySet(),
        media = emptyList(),
    )
}

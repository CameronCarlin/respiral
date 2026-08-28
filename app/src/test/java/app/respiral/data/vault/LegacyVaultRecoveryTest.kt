package app.respiral.data.vault

import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.markdown.MarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultMedia
import app.respiral.data.index.EntryIndexEntity
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class LegacyVaultRecoveryTest {
    private lateinit var vaultRoot: File
    private lateinit var store: VaultFileStore
    private lateinit var recovery: LegacyVaultRecovery

    @Before
    fun setUp() {
        vaultRoot = createTempDirectory("respiral-vault-").toFile()
        store = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = { null },
        )
        recovery = LegacyVaultRecovery(store)
    }

    @After
    fun tearDown() {
        vaultRoot.deleteRecursively()
    }

    @Test
    fun missing_markdown_is_reconstructed_from_the_complete_legacy_row() = runTest {
        val row = legacyRow(title = "Still here", body = "The words survived.")

        val report = recovery.recover(listOf(row))

        assertThat(report).isEqualTo(LegacyRecoveryReport(1, 0, null))
        assertThat(store.read(UUID.fromString(row.id)).body).isEqualTo("The words survived.")
    }

    @Test
    fun malformed_markdown_is_preserved_before_reconstruction() = runTest {
        val row = legacyRow(title = "Recovered")
        val source = entryFile(row.id).apply { parentFile!!.mkdirs(); writeText("broken bytes") }

        recovery.recover(listOf(row))

        assertThat(recoveryFile(row.id).readText()).isEqualTo("broken bytes")
        assertThat(store.read(UUID.fromString(row.id)).title).isEqualTo("Recovered")
    }

    @Test
    fun valid_markdown_wins_over_conflicting_legacy_content() = runTest {
        store.write(sampleEntry(title = "Canonical"))

        val report = recovery.recover(listOf(legacyRow(title = "Stale index")))

        assertThat(report).isEqualTo(LegacyRecoveryReport(0, 0, null))
        assertThat(store.read(sampleEntry().id).title).isEqualTo("Canonical")
    }

    @Test
    fun invalid_uuid_row_is_reported_without_blocking_following_rows() = runTest {
        val valid = legacyRow(id = "123e4567-e89b-12d3-a456-426614174010", title = "Survives")
        val invalid = legacyRow(id = "not-a-uuid")

        val report = recovery.recover(listOf(invalid, valid))

        assertThat(report).isEqualTo(LegacyRecoveryReport(1, 1, VaultDiagnosticCode.RSP_R02))
        assertThat(store.read(UUID.fromString(valid.id)).title).isEqualTo("Survives")
    }

    @Test
    fun invalid_tag_row_is_reported_without_blocking_following_rows() = runTest {
        val invalid = legacyRow(tagNames = "|NOT_A_TAG|")
        val valid = legacyRow(id = "123e4567-e89b-12d3-a456-426614174011", title = "Survives")

        val report = recovery.recover(listOf(invalid, valid))

        assertThat(report).isEqualTo(LegacyRecoveryReport(1, 1, VaultDiagnosticCode.RSP_R02))
        assertThat(store.read(UUID.fromString(valid.id)).title).isEqualTo("Survives")
    }

    @Test
    fun legacy_timestamps_are_preserved_in_reconstructed_markdown() = runTest {
        val row = legacyRow(createdAtEpochMs = 1_000L, updatedAtEpochMs = 2_000L)

        recovery.recover(listOf(row))

        val recovered = store.read(UUID.fromString(row.id))
        assertThat(recovered.createdAt).isEqualTo(Instant.ofEpochMilli(1_000L))
        assertThat(recovered.updatedAt).isEqualTo(Instant.ofEpochMilli(2_000L))
    }

    @Test
    fun reconstructed_markdown_uses_deterministically_discovered_media() = runTest {
        val row = legacyRow()
        val mediaDirectory = vaultRoot.resolve("media/${row.id}").apply(File::mkdirs)
        mediaDirectory.resolve("z.png").writeBytes(byteArrayOf(0x01))
        mediaDirectory.resolve("a.jpg").writeBytes(byteArrayOf(0x02))

        recovery.recover(listOf(row))

        assertThat(store.read(UUID.fromString(row.id)).media).containsExactly(
            VaultMedia("media/${row.id}/a.jpg", "image/jpeg"),
            VaultMedia("media/${row.id}/z.png", "image/png"),
        ).inOrder()
    }

    @Test
    fun second_recovery_leaves_reconstructed_markdown_unchanged() = runTest {
        val row = legacyRow()

        assertThat(recovery.recover(listOf(row))).isEqualTo(LegacyRecoveryReport(1, 0, null))

        assertThat(recovery.recover(listOf(row))).isEqualTo(LegacyRecoveryReport(0, 0, null))
    }

    @Test
    fun cancellation_from_markdown_validation_is_rethrown() = runTest {
        val cancellation = CancellationException("cancelled")
        val cancellingStore = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = object : MarkdownEntryCodec {
                override fun encode(entry: VaultEntry): String = error("not used")

                override fun decode(markdown: String): VaultEntry = throw cancellation
            },
            openInputStream = { null },
        )
        val row = legacyRow()
        entryFile(row.id).apply { parentFile!!.mkdirs(); writeText("broken bytes") }

        val failure = runCatching { LegacyVaultRecovery(cancellingStore).recover(listOf(row)) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
    }

    private fun legacyRow(
        id: String = sampleEntry().id.toString(),
        title: String = "Legacy title",
        body: String = "Legacy body",
        createdAtEpochMs: Long = sampleEntry().createdAt.toEpochMilli(),
        updatedAtEpochMs: Long = sampleEntry().updatedAt.toEpochMilli(),
        tagNames: String = "|AFFIRMATION|",
    ) = EntryIndexEntity(id, title, body, createdAtEpochMs, updatedAtEpochMs, tagNames)

    private fun entryFile(id: String): File = vaultRoot.resolve("entries/$id.md")

    private fun recoveryFile(id: String): File = vaultRoot.resolve("recovery/$id.malformed.md")
}

package app.respiral.data.vault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultMedia
import app.respiral.data.index.RespiralDatabase
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZipVaultTransferTest {
    private lateinit var vaultRoot: File
    private lateinit var cacheRoot: File
    private lateinit var sourceJpeg: File
    private lateinit var database: RespiralDatabase
    private lateinit var fileStore: VaultFileStore
    private lateinit var repository: DefaultVaultRepository
    private lateinit var transfer: ZipVaultTransfer

    @Before
    fun setUp() {
        vaultRoot = createTempDirectory("respiral-transfer-vault-").toFile()
        cacheRoot = createTempDirectory("respiral-transfer-cache-").toFile()
        sourceJpeg = File.createTempFile("respiral-transfer-source-", ".jpg").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RespiralDatabase::class.java,
        ).allowMainThreadQueries().build()
        fileStore = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = { sourceJpeg.inputStream() },
        )
        repository = DefaultVaultRepository(fileStore, database)
        transfer = ZipVaultTransfer(repository, fileStore, cacheRoot)
    }

    @After
    fun tearDown() {
        database.close()
        vaultRoot.deleteRecursively()
        cacheRoot.deleteRecursively()
        sourceJpeg.delete()
    }

    @Test
    fun export_then_preview_reports_all_markdown_and_media() = runTest {
        val saved = repository.save(
            sampleEntry(media = emptyList()),
            listOf(PendingMedia(android.net.Uri.parse("content://test/photo.jpg"), "image/jpeg")),
        )

        val zip = ByteArrayOutputStream().also { transfer.export(it) }.toByteArray()
        val preview = transfer.preview(zip.inputStream())

        assertThat(preview).isEqualTo(
            ImportPreview(entryCount = 1, mediaCount = 1, tags = saved.tags),
        )
    }

    @Test
    fun malformed_zip_does_not_change_existing_vault() = runTest {
        repository.save(sampleEntry(title = "Keep me"), emptyList())

        val error = runCatching {
            transfer.apply("not a zip".byteInputStream(), ImportMode.MERGE)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(InvalidVaultArchiveException::class.java)
        assertThat(repository.observeTimeline("", emptySet()).first().single().title).isEqualTo("Keep me")
    }

    @Test
    fun merge_keeps_existing_entry_when_ids_match() = runTest {
        val local = repository.save(sampleEntry(title = "Local"), emptyList())
        val archive = archiveWith(sampleEntry(id = local.id, title = "Imported"))

        val result = transfer.apply(archive.inputStream(), ImportMode.MERGE)

        assertThat(result.importedEntryCount).isEqualTo(0)
        assertThat(result.skippedEntryCount).isEqualTo(1)
        assertThat(repository.get(local.id).title).isEqualTo("Local")
    }

    @Test
    fun merge_imports_absent_entry_and_its_private_media() = runTest {
        val imported = sampleEntry(
            id = UUID.fromString("123e4567-e89b-12d3-a456-426614174010"),
            media = listOf(VaultMedia("media/123e4567-e89b-12d3-a456-426614174010/photo.jpg", "image/jpeg")),
        )

        val result = transfer.apply(archiveOf(imported).inputStream(), ImportMode.MERGE)

        assertThat(result.importedEntryCount).isEqualTo(1)
        assertThat(repository.get(imported.id)).isEqualTo(imported)
        assertThat(vaultRoot.resolve(imported.media.single().relativePath).readText()).isEqualTo("media")
    }

    @Test
    fun replace_swaps_vault_only_after_full_validation() = runTest {
        repository.save(sampleEntry(title = "Old"), emptyList())
        val archive = archiveWith(sampleEntry(title = "New"))

        transfer.preview(archive.inputStream())
        transfer.apply(archive.inputStream(), ImportMode.REPLACE)

        assertThat(repository.observeTimeline("", emptySet()).first().single().title).isEqualTo("New")
    }

    @Test
    fun path_traversal_entry_is_rejected_without_writing_outside_import_root() = runTest {
        val archive = zipOf(
            "manifest.json" to manifest(entryCount = 0, mediaCount = 0),
            "../escape.md" to "unsafe",
        )

        val error = runCatching { transfer.preview(archive.inputStream()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(InvalidVaultArchiveException::class.java)
        assertThat(cacheRoot.walk().none { it.name == "escape.md" }).isTrue()
    }

    @Test
    fun missing_referenced_media_is_rejected() = runTest {
        val entry = sampleEntry(
            media = listOf(VaultMedia("media/${sampleEntry().id}/missing.jpg", "image/jpeg")),
        )
        val archive = archiveOf(entry, includeMedia = false)

        val error = runCatching { transfer.preview(archive.inputStream()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(InvalidVaultArchiveException::class.java)
    }

    @Test
    fun unsupported_manifest_version_is_rejected() = runTest {
        val archive = zipOf(
            "manifest.json" to manifest(entryCount = 0, mediaCount = 0, version = 2),
        )

        val error = runCatching { transfer.preview(archive.inputStream()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(InvalidVaultArchiveException::class.java)
    }

    private fun archiveWith(entry: VaultEntry): ByteArray = archiveOf(entry)

    private fun archiveOf(entry: VaultEntry, includeMedia: Boolean = true): ByteArray {
        val codec = CanonicalMarkdownEntryCodec()
        val files = mutableListOf("entries/${entry.id}.md" to codec.encode(entry))
        if (includeMedia) {
            entry.media.forEach { media -> files += media.relativePath to "media" }
        }
        return zipOf(
            "manifest.json" to manifest(entryCount = 1, mediaCount = entry.media.size),
            *files.toTypedArray(),
        )
    }

    private fun manifest(entryCount: Int, mediaCount: Int, version: Int = 1): String =
        "{\"formatVersion\":$version,\"createdAt\":\"2026-08-26T10:00:00Z\",\"entryCount\":$entryCount,\"mediaCount\":$mediaCount}"

    private fun zipOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}

package app.respiral.data.vault

import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.markdown.MarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultMedia
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test

class VaultFileStoreRecoveryTest {
    private lateinit var vaultRoot: File
    private lateinit var entriesDirectory: File
    private lateinit var store: VaultFileStore

    @Before
    fun setUp() {
        vaultRoot = createTempDirectory("respiral-vault-").toFile()
        entriesDirectory = vaultRoot.resolve("entries")
        store = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = { null },
        )
    }

    @After
    fun tearDown() {
        vaultRoot.deleteRecursively()
    }

    @Test
    fun scan_keeps_valid_entries_when_another_markdown_file_is_malformed() {
        store.write(sampleEntry(title = "Readable"))
        entriesDirectory.resolve("broken.md").writeText("not canonical")

        val result = store.scan()

        assertThat(result.entries.map { it.title }).containsExactly("Readable")
        assertThat(result.unreadableCount).isEqualTo(1)
    }

    @Test
    fun quarantine_copies_exact_source_bytes_without_removing_the_original() {
        val source = entriesDirectory.resolve("${sampleEntry().id}.md")
        source.parentFile!!.mkdirs()
        source.writeBytes(byteArrayOf(0x00, 0x41, 0x7f))

        val preserved = store.quarantineMalformed(sampleEntry().id)

        assertThat(preserved.readBytes()).isEqualTo(byteArrayOf(0x00, 0x41, 0x7f))
        assertThat(source.readBytes()).isEqualTo(byteArrayOf(0x00, 0x41, 0x7f))
    }

    @Test
    fun entry_file_exists_requires_a_regular_markdown_file() {
        val entry = sampleEntry()
        val markdownPath = entriesDirectory.resolve("${entry.id}.md")
        markdownPath.mkdirs()

        assertThat(store.entryFileExists(entry.id)).isFalse()

        markdownPath.deleteRecursively()
        store.write(entry)

        assertThat(store.entryFileExists(entry.id)).isTrue()
    }

    @Test
    fun discover_media_returns_supported_regular_files_sorted_with_canonical_mime_types() {
        val entry = sampleEntry()
        val mediaDirectory = vaultRoot.resolve("media/${entry.id}").apply(File::mkdirs)
        mediaDirectory.resolve("z.gif").writeBytes(byteArrayOf())
        mediaDirectory.resolve("b.jpeg").writeBytes(byteArrayOf())
        mediaDirectory.resolve("a.jpg").writeBytes(byteArrayOf())
        mediaDirectory.resolve("c.png").writeBytes(byteArrayOf())
        mediaDirectory.resolve("d.webp").writeBytes(byteArrayOf())
        mediaDirectory.resolve("ignored.txt").writeBytes(byteArrayOf())
        mediaDirectory.resolve("nested.jpg").mkdirs()

        assertThat(store.discoverMedia(entry.id)).containsExactly(
            VaultMedia("media/${entry.id}/a.jpg", "image/jpeg"),
            VaultMedia("media/${entry.id}/b.jpeg", "image/jpeg"),
            VaultMedia("media/${entry.id}/c.png", "image/png"),
            VaultMedia("media/${entry.id}/d.webp", "image/webp"),
            VaultMedia("media/${entry.id}/z.gif", "image/gif"),
        ).inOrder()
    }

    @Test
    fun scan_rethrows_cancellation_exception() {
        val cancellation = CancellationException("scan cancelled")
        store = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = object : MarkdownEntryCodec {
                override fun encode(entry: VaultEntry): String = error("not used")

                override fun decode(markdown: String): VaultEntry = throw cancellation
            },
            openInputStream = { null },
        )
        entriesDirectory.resolve("cancelled.md").apply {
            parentFile!!.mkdirs()
            writeText("not canonical")
        }

        val failure = runCatching { store.scan() }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
    }
}

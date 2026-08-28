package app.respiral.data.vault

import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
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
}

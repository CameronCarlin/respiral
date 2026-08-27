package app.respiral.data.vault

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultTag
import app.respiral.data.index.RespiralDatabase
import app.respiral.laterInstant
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
import java.io.IOException
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
class VaultRepositoryTest {
    private lateinit var vaultRoot: File
    private lateinit var sourceJpeg: File
    private lateinit var sourcePng: File
    private lateinit var database: RespiralDatabase
    private lateinit var fileStore: VaultFileStore
    private lateinit var repository: VaultRepository
    private var openSource: (Uri) -> InputStream? = { sourceJpeg.inputStream() }

    private val pendingJpeg: PendingMedia
        get() = PendingMedia(Uri.parse("content://respiral-test/photo.jpg"), "image/jpeg")

    private val pendingPng: PendingMedia
        get() = PendingMedia(Uri.parse("content://respiral-test/photo.png"), "image/png")

    @Before
    fun setUp() {
        vaultRoot = createTempDirectory("respiral-vault-").toFile()
        sourceJpeg = File.createTempFile("respiral-source-", ".jpg").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        sourcePng = File.createTempFile("respiral-source-", ".png").apply {
            writeBytes(byteArrayOf(0x04, 0x05, 0x06))
        }
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RespiralDatabase::class.java,
        ).allowMainThreadQueries().build()
        fileStore = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = { uri ->
                if (uri.lastPathSegment == "photo.png") sourcePng.inputStream() else openSource(uri)
            },
        )
        repository = DefaultVaultRepository(fileStore, database)
    }

    @After
    fun tearDown() {
        database.close()
        vaultRoot.deleteRecursively()
        sourceJpeg.delete()
        sourcePng.delete()
    }

    @Test
    fun save_copies_media_writes_markdown_and_updates_timeline() = runTest {
        val saved = repository.save(sampleEntry(), listOf(pendingJpeg))

        assertThat(vaultRoot.resolve("entries/${saved.id}.md").exists()).isTrue()
        assertThat(vaultRoot.resolve("media/${saved.id}/0.jpg").exists()).isTrue()
        assertThat(vaultRoot.resolve("media/${saved.id}/0.jpg").readBytes())
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x03))
        assertThat(repository.observeTimeline("", emptySet()).first()).hasSize(1)
    }

    @Test
    fun rebuild_index_recovers_timeline_from_markdown_files() = runTest {
        val entry = sampleEntry()
        fileStore.write(entry)

        repository.rebuildIndex()

        assertThat(repository.observeTimeline("", emptySet()).first().single().id).isEqualTo(entry.id)
    }

    @Test
    fun editing_an_entry_preserves_its_id_and_replaces_its_index_row() = runTest {
        val original = repository.save(sampleEntry(title = "First"), emptyList())

        repository.save(original.copy(title = "Revised", updatedAt = laterInstant), emptyList())

        val timeline = repository.observeTimeline("", emptySet()).first()
        assertThat(timeline).hasSize(1)
        assertThat(timeline.single().id).isEqualTo(original.id)
        assertThat(timeline.single().title).isEqualTo("Revised")
    }

    @Test
    fun editing_media_replaces_the_complete_media_set_without_orphans() = runTest {
        val original = repository.save(sampleEntry(), listOf(pendingJpeg, pendingJpeg))

        val saved = repository.save(original.copy(title = "PNG replacement"), listOf(pendingPng))

        assertThat(saved.media).hasSize(1)
        assertThat(saved.media.single().relativePath).isEqualTo("media/${saved.id}/0.png")
        assertThat(vaultRoot.resolve("media/${saved.id}/0.png").readBytes())
            .isEqualTo(byteArrayOf(0x04, 0x05, 0x06))
        assertThat(vaultRoot.resolve("media/${saved.id}/0.jpg").exists()).isFalse()
        assertThat(vaultRoot.resolve("media/${saved.id}/1.jpg").exists()).isFalse()
        assertThat(vaultRoot.resolve("media/${saved.id}").listFiles().orEmpty()).hasLength(1)
        assertThat(repository.get(saved.id).media).isEqualTo(saved.media)
    }

    @Test
    fun delete_removes_markdown_media_and_index_row() = runTest {
        val saved = repository.save(sampleEntry(), listOf(pendingJpeg))

        repository.delete(saved.id)

        assertThat(vaultRoot.resolve("entries/${saved.id}.md").exists()).isFalse()
        assertThat(vaultRoot.resolve("media/${saved.id}").exists()).isFalse()
        assertThat(repository.observeTimeline("", emptySet()).first()).isEmpty()
    }

    @Test
    fun delete_keeps_media_owned_by_other_entries() = runTest {
        val deleted = repository.save(sampleEntry(), listOf(pendingJpeg))
        val retained = repository.save(
            sampleEntry(id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174002")),
            listOf(pendingJpeg),
        )

        repository.delete(deleted.id)

        assertThat(vaultRoot.resolve("media/${retained.id}/0.jpg").exists()).isTrue()
        assertThat(repository.observeTimeline("", emptySet()).first().single().id).isEqualTo(retained.id)
    }

    @Test
    fun failed_media_copy_leaves_existing_entry_and_index_unchanged() = runTest {
        val original = repository.save(sampleEntry(title = "Original"), emptyList())
        openSource = {
            object : InputStream() {
                override fun read(): Int = throw IOException("source disappeared")
            }
        }

        val failure = runCatching {
            repository.save(original.copy(title = "Replacement"), listOf(pendingJpeg))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(repository.get(original.id).title).isEqualTo("Original")
        assertThat(repository.observeTimeline("", emptySet()).first().single().title).isEqualTo("Original")
        assertThat(vaultRoot.walk().none { it.getName().contains("temporary-") }).isTrue()
    }

    @Test
    fun index_failure_after_file_promotion_restores_previous_markdown_media_and_index() = runTest {
        val original = repository.save(sampleEntry(title = "Original"), listOf(pendingJpeg))
        val failingRepository = DefaultVaultRepository.withAfterSaveIndexUpsert(fileStore, database) {
            throw IOException("index write failed")
        }

        val failure = runCatching {
            failingRepository.save(original.copy(title = "Replacement"), listOf(pendingPng))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(repository.get(original.id).title).isEqualTo("Original")
        assertThat(repository.get(original.id).media.single().relativePath)
            .isEqualTo("media/${original.id}/0.jpg")
        assertThat(vaultRoot.resolve("media/${original.id}/0.jpg").exists()).isTrue()
        assertThat(vaultRoot.resolve("media/${original.id}/0.png").exists()).isFalse()
        assertThat(repository.observeTimeline("", emptySet()).first().single().title).isEqualTo("Original")
    }

    @Test
    fun timeline_searches_title_and_body_and_filters_when_tags_are_selected() = runTest {
        repository.save(sampleEntry(title = "Bright morning", tags = setOf(VaultTag.ACHIEVEMENT)), emptyList())
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174001"),
                title = "A quiet note",
                body = "A bright small win.",
                tags = setOf(VaultTag.AFFIRMATION),
                createdAt = laterInstant,
            ),
            emptyList(),
        )

        assertThat(repository.observeTimeline("bright", emptySet()).first()).hasSize(2)
        assertThat(repository.observeTimeline("", setOf(VaultTag.AFFIRMATION)).first().single().title)
            .isEqualTo("A quiet note")
    }

    @Test
    fun timeline_treats_like_wildcards_as_literal_search_text() = runTest {
        repository.save(sampleEntry(title = "100% complete"), emptyList())
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174003"),
                title = "100X complete",
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174004"),
                title = "one_two",
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174005"),
                title = "oneXtwo",
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174006"),
                title = "path\\name",
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174007"),
                title = "pathXname",
            ),
            emptyList(),
        )

        assertThat(repository.observeTimeline("not-present", emptySet()).first()).isEmpty()
        assertThat(repository.observeTimeline("100%", emptySet()).first().map { it.title })
            .containsExactly("100% complete")
        assertThat(repository.observeTimeline("one_two", emptySet()).first().map { it.title })
            .containsExactly("one_two")
        assertThat(repository.observeTimeline("path\\name", emptySet()).first().map { it.title })
            .containsExactly("path\\name")
    }

    @Test
    fun timeline_returns_entries_newest_first() = runTest {
        repository.save(
            sampleEntry(
                title = "Old achievement",
                tags = setOf(VaultTag.ACHIEVEMENT),
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174008"),
                title = "New affirmation",
                tags = setOf(VaultTag.AFFIRMATION),
                createdAt = laterInstant,
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174009"),
                title = "Newest who I am",
                tags = setOf(VaultTag.WHO_I_AM),
                createdAt = laterInstant.plusSeconds(1),
            ),
            emptyList(),
        )

        assertThat(repository.observeTimeline("", emptySet()).first().map { it.title })
            .containsExactly("Newest who I am", "New affirmation", "Old achievement")
            .inOrder()
    }

    @Test
    fun timeline_with_multiple_tags_matches_any_selected_tag() = runTest {
        repository.save(
            sampleEntry(
                title = "Old achievement",
                tags = setOf(VaultTag.ACHIEVEMENT),
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174008"),
                title = "New affirmation",
                tags = setOf(VaultTag.AFFIRMATION),
                createdAt = laterInstant,
            ),
            emptyList(),
        )
        repository.save(
            sampleEntry(
                id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174009"),
                title = "Newest who I am",
                tags = setOf(VaultTag.WHO_I_AM),
                createdAt = laterInstant.plusSeconds(1),
            ),
            emptyList(),
        )

        assertThat(
            repository.observeTimeline(
                "",
                setOf(VaultTag.ACHIEVEMENT, VaultTag.AFFIRMATION),
            ).first().map { it.title },
        ).containsExactly("New affirmation", "Old achievement").inOrder()
    }
}

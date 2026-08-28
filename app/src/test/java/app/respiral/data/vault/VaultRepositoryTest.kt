package app.respiral.data.vault

import android.net.Uri
import app.respiral.core.markdown.CanonicalMarkdownEntryCodec
import app.respiral.core.model.VaultMedia
import app.respiral.core.model.VaultTag
import app.respiral.laterInstant
import app.respiral.sampleEntry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    private lateinit var outsideMedia: File
    private lateinit var fileStore: VaultFileStore
    private lateinit var repository: DefaultVaultRepository
    private var openSource: (Uri) -> InputStream? = { sourceJpeg.inputStream() }

    private val entryDirectory: File
        get() = vaultRoot.resolve("entries")

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
        outsideMedia = vaultRoot.resolve("outside.jpg").apply {
            writeBytes(byteArrayOf(0x07, 0x08, 0x09))
        }
        fileStore = VaultFileStore(
            vaultRoot = vaultRoot,
            codec = CanonicalMarkdownEntryCodec(),
            openInputStream = { uri ->
                if (uri.lastPathSegment == "photo.png") sourcePng.inputStream() else openSource(uri)
            },
        )
        repository = DefaultVaultRepository(fileStore)
    }

    @After
    fun tearDown() {
        vaultRoot.deleteRecursively()
        sourceJpeg.delete()
        sourcePng.delete()
        outsideMedia.delete()
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
    fun restart_rebuilds_timeline_only_from_canonical_markdown() = runTest {
        fileStore.write(sampleEntry(title = "From Markdown"))
        val restarted = DefaultVaultRepository(fileStore)

        restarted.refresh()

        assertThat(restarted.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("From Markdown")
    }

    @Test
    fun rebuild_index_recovers_timeline_from_markdown_files() = runTest {
        val entry = sampleEntry()
        fileStore.write(entry)

        repository.rebuildIndex()

        assertThat(repository.observeTimeline("", emptySet()).first().single().id).isEqualTo(entry.id)
    }

    @Test
    fun unreadable_markdown_does_not_block_valid_timeline_entries() = runTest {
        fileStore.write(sampleEntry(title = "Valid"))
        entryDirectory.resolve("broken.md").writeText("broken")

        repository.refresh()

        assertThat(repository.observeTimeline("", emptySet()).first().map { it.title })
            .containsExactly("Valid")
        assertThat(repository.health.value)
            .isEqualTo(VaultHealth.NeedsAttention(VaultDiagnosticCode.RSP_R02, 1))
    }

    @Test
    fun editing_an_entry_preserves_its_id_and_replaces_its_projection_entry() = runTest {
        val original = repository.save(sampleEntry(title = "First"), emptyList())

        repository.save(original.copy(title = "Revised", updatedAt = laterInstant), emptyList())

        val timeline = repository.observeTimeline("", emptySet()).first()
        assertThat(timeline).hasSize(1)
        assertThat(timeline.single().id).isEqualTo(original.id)
        assertThat(timeline.single().title).isEqualTo("Revised")
    }

    @Test
    fun editing_an_entry_without_new_media_preserves_existing_media() = runTest {
        val original = repository.save(sampleEntry(), listOf(pendingJpeg))
        vaultRoot.resolve("media/${original.id}/orphan.bin").writeBytes(byteArrayOf(0x09))

        val saved = repository.save(original.copy(title = "Revised"), emptyList())

        assertThat(saved.media).isEqualTo(original.media)
        assertThat(vaultRoot.resolve("media/${saved.id}/0.jpg").readBytes())
            .isEqualTo(byteArrayOf(0x01, 0x02, 0x03))
        assertThat(vaultRoot.resolve("media/${saved.id}/orphan.bin").exists()).isFalse()
    }

    @Test
    fun editing_media_appends_new_media_without_orphans() = runTest {
        val original = repository.save(sampleEntry(), listOf(pendingJpeg, pendingJpeg))

        val saved = repository.save(original.copy(title = "PNG replacement"), listOf(pendingPng))

        assertThat(saved.media).hasSize(3)
        assertThat(saved.media.map { it.relativePath }).containsExactly(
            "media/${saved.id}/0.jpg",
            "media/${saved.id}/1.jpg",
            "media/${saved.id}/2.png",
        ).inOrder()
        assertThat(vaultRoot.resolve("media/${saved.id}/2.png").readBytes())
            .isEqualTo(byteArrayOf(0x04, 0x05, 0x06))
        assertThat(vaultRoot.resolve("media/${saved.id}/0.jpg").exists()).isTrue()
        assertThat(vaultRoot.resolve("media/${saved.id}/1.jpg").exists()).isTrue()
        assertThat(vaultRoot.resolve("media/${saved.id}").listFiles().orEmpty()).hasLength(3)
        assertThat(repository.get(saved.id).media).isEqualTo(saved.media)
    }

    @Test
    fun missing_retained_media_fails_without_changing_markdown_media_or_projection() = runTest {
        val original = repository.save(sampleEntry(title = "Original"), listOf(pendingJpeg))
        val originalMarkdown = vaultRoot.resolve("entries/${original.id}.md").readBytes()
        val originalMedia = vaultRoot.resolve("media/${original.id}/0.jpg").readBytes()

        val failure = runCatching {
            repository.save(
                original.withMedia(
                    title = "Replacement",
                    media = listOf(VaultMedia("media/${original.id}/missing.jpg", "image/jpeg")),
                ),
                emptyList(),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(vaultRoot.resolve("entries/${original.id}.md").readBytes()).isEqualTo(originalMarkdown)
        assertThat(vaultRoot.resolve("media/${original.id}/0.jpg").readBytes()).isEqualTo(originalMedia)
        assertThat(repository.get(original.id)).isEqualTo(original)
        assertThat(repository.observeTimeline("", emptySet()).first().single().title).isEqualTo("Original")
    }

    @Test
    fun outside_retained_media_fails_without_changing_markdown_media_or_projection() = runTest {
        val original = repository.save(sampleEntry(title = "Original"), listOf(pendingJpeg))
        val originalMarkdown = vaultRoot.resolve("entries/${original.id}.md").readBytes()
        val originalMedia = vaultRoot.resolve("media/${original.id}/0.jpg").readBytes()

        val failure = runCatching {
            repository.save(
                original.withMedia(
                    title = "Replacement",
                    media = listOf(VaultMedia("${outsideMedia.name}", "image/jpeg")),
                ),
                emptyList(),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(vaultRoot.resolve("entries/${original.id}.md").readBytes()).isEqualTo(originalMarkdown)
        assertThat(vaultRoot.resolve("media/${original.id}/0.jpg").readBytes()).isEqualTo(originalMedia)
        assertThat(repository.get(original.id)).isEqualTo(original)
        assertThat(repository.observeTimeline("", emptySet()).first().single().title).isEqualTo("Original")
    }

    @Test
    fun delete_removes_markdown_media_and_projection_entry() = runTest {
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
    fun failed_media_copy_leaves_existing_entry_and_projection_unchanged() = runTest {
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
    fun projection_failure_after_file_promotion_restores_previous_markdown_media_and_projection() = runTest {
        val original = repository.save(sampleEntry(title = "Original"), listOf(pendingJpeg))
        val failingRepository = DefaultVaultRepository.withAfterSaveProjectionUpdate(fileStore) {
            throw IOException("projection update failed")
        }
        failingRepository.refresh()

        val failure = runCatching {
            failingRepository.save(original.copy(title = "Replacement"), listOf(pendingPng))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failingRepository.get(original.id).title).isEqualTo("Original")
        assertThat(failingRepository.get(original.id).media.single().relativePath)
            .isEqualTo("media/${original.id}/0.jpg")
        assertThat(vaultRoot.resolve("media/${original.id}/0.jpg").exists()).isTrue()
        assertThat(vaultRoot.resolve("media/${original.id}/1.png").exists()).isFalse()
        assertThat(
            vaultRoot.walk().none {
                it.name.contains("temporary-") || it.name.contains("backup-")
            },
        ).isTrue()
        assertThat(failingRepository.observeTimeline("", emptySet()).first().single().title).isEqualTo("Original")
    }

    @Test
    fun merge_on_fresh_repository_keeps_existing_canonical_entry_for_same_id() = runTest {
        val local = sampleEntry(title = "Canonical local")
        val imported = local.copy(title = "Imported collision")
        fileStore.write(local)
        val freshRepository = DefaultVaultRepository(fileStore)
        val stagedVault = stagedVault(imported)

        try {
            val importedIds = freshRepository.applyImportedVault(
                stagedVault,
                listOf(imported),
                ImportMode.MERGE,
            )

            assertThat(importedIds).isEmpty()
            assertThat(fileStore.read(local.id).title).isEqualTo("Canonical local")
            assertThat(freshRepository.get(local.id).title).isEqualTo("Canonical local")
        } finally {
            stagedVault.deleteRecursively()
        }
    }

    @Test
    fun delete_projection_failure_restores_canonical_files_and_projection() = runTest {
        val original = repository.save(sampleEntry(title = "Keep after delete"), listOf(pendingJpeg))
        val originalMarkdown = entryDirectory.resolve("${original.id}.md").readBytes()
        val originalMedia = vaultRoot.resolve("media/${original.id}/0.jpg").readBytes()
        val failingRepository = DefaultVaultRepository.withMutationHooks(
            fileStore = fileStore,
            afterDeleteProjectionUpdate = { throw IOException("delete projection failed") },
        )
        failingRepository.refresh()

        val failure = runCatching { failingRepository.delete(original.id) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(entryDirectory.resolve("${original.id}.md").readBytes()).isEqualTo(originalMarkdown)
        assertThat(vaultRoot.resolve("media/${original.id}/0.jpg").readBytes()).isEqualTo(originalMedia)
        assertThat(failingRepository.get(original.id)).isEqualTo(original)
        assertThat(failingRepository.observeTimeline("", emptySet()).first().single().title)
            .isEqualTo("Keep after delete")
        assertThat(vaultRoot.walk().none { it.name.contains("deleting-") }).isTrue()
    }

    @Test
    fun merge_refresh_failure_restores_canonical_files_and_projection() = runTest {
        val local = repository.save(sampleEntry(title = "Keep after merge"), listOf(pendingJpeg))
        val importedId = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174011")
        val imported = sampleEntry(
            id = importedId,
            title = "Rejected merge",
            media = listOf(VaultMedia("media/$importedId/imported.jpg", "image/jpeg")),
        )
        val localMarkdown = entryDirectory.resolve("${local.id}.md").readBytes()
        val stagedVault = stagedVault(imported)
        var failRefresh = false
        val failingRepository = DefaultVaultRepository.withMutationHooks(
            fileStore = fileStore,
            afterRefreshProjectionUpdate = {
                if (failRefresh) throw IOException("merge refresh failed")
            },
        )
        failingRepository.refresh()
        failRefresh = true

        try {
            val failure = runCatching {
                failingRepository.applyImportedVault(stagedVault, listOf(imported), ImportMode.MERGE)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(entryDirectory.resolve("${local.id}.md").readBytes()).isEqualTo(localMarkdown)
            assertThat(entryDirectory.resolve("${imported.id}.md").exists()).isFalse()
            assertThat(vaultRoot.resolve("media/${imported.id}/imported.jpg").exists()).isFalse()
            assertThat(failingRepository.get(local.id)).isEqualTo(local)
            assertThat(failingRepository.observeTimeline("", emptySet()).first().map { it.title })
                .containsExactly("Keep after merge")
            assertThat(failingRepository.health.value).isEqualTo(VaultHealth.Healthy)
        } finally {
            stagedVault.deleteRecursively()
        }
    }

    @Test
    fun replacement_refresh_failure_restores_canonical_files_and_projection() = runTest {
        val local = repository.save(sampleEntry(title = "Keep after replacement"), listOf(pendingJpeg))
        val importedId = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174012")
        val imported = sampleEntry(
            id = importedId,
            title = "Rejected replacement",
            media = listOf(VaultMedia("media/$importedId/imported.jpg", "image/jpeg")),
        )
        val localMarkdown = entryDirectory.resolve("${local.id}.md").readBytes()
        val localMedia = vaultRoot.resolve("media/${local.id}/0.jpg").readBytes()
        val stagedVault = stagedVault(imported)
        var failRefresh = false
        val failingRepository = DefaultVaultRepository.withMutationHooks(
            fileStore = fileStore,
            afterRefreshProjectionUpdate = {
                if (failRefresh) throw IOException("replacement refresh failed")
            },
        )
        failingRepository.refresh()
        failRefresh = true

        try {
            val failure = runCatching {
                failingRepository.applyImportedVault(stagedVault, listOf(imported), ImportMode.REPLACE)
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(entryDirectory.resolve("${local.id}.md").readBytes()).isEqualTo(localMarkdown)
            assertThat(vaultRoot.resolve("media/${local.id}/0.jpg").readBytes()).isEqualTo(localMedia)
            assertThat(entryDirectory.resolve("${imported.id}.md").exists()).isFalse()
            assertThat(vaultRoot.resolve("media/${imported.id}/imported.jpg").exists()).isFalse()
            assertThat(failingRepository.get(local.id)).isEqualTo(local)
            assertThat(failingRepository.observeTimeline("", emptySet()).first().map { it.title })
                .containsExactly("Keep after replacement")
            assertThat(failingRepository.health.value).isEqualTo(VaultHealth.Healthy)
        } finally {
            stagedVault.deleteRecursively()
        }
    }

    @Test
    fun concurrent_saves_are_serialized_until_file_and_projection_transaction_completes() = runTest {
        val firstProjectionUpdated = CompletableDeferred<Unit>()
        val secondProjectionUpdated = CountDownLatch(1)
        val releaseFirstSave = CompletableDeferred<Unit>()
        val saveUpdateCount = AtomicInteger()
        val serializedRepository = DefaultVaultRepository.withMutationHooks(
            fileStore = fileStore,
            afterSaveProjectionUpdate = {
                when (saveUpdateCount.incrementAndGet()) {
                    1 -> {
                        firstProjectionUpdated.complete(Unit)
                        releaseFirstSave.await()
                    }

                    2 -> secondProjectionUpdated.countDown()
                }
            },
        )
        val first = sampleEntry(title = "First serialized")
        val second = sampleEntry(
            id = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174013"),
            title = "Second serialized",
        )

        val firstSave = async { serializedRepository.save(first, emptyList()) }
        firstProjectionUpdated.await()
        val secondSave = async { serializedRepository.save(second, emptyList()) }
        val secondUpdatedWhileFirstHeld = withContext(Dispatchers.IO) {
            secondProjectionUpdated.await(1, TimeUnit.SECONDS)
        }

        releaseFirstSave.complete(Unit)
        firstSave.await()
        secondSave.await()
        assertThat(secondUpdatedWhileFirstHeld).isFalse()
        assertThat(entryDirectory.resolve("${first.id}.md").exists()).isTrue()
        assertThat(entryDirectory.resolve("${second.id}.md").exists()).isTrue()
        assertThat(serializedRepository.observeTimeline("", emptySet()).first().map { it.title })
            .containsExactly("First serialized", "Second serialized")
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

    private fun stagedVault(entry: app.respiral.core.model.VaultEntry): File =
        createTempDirectory("respiral-staged-vault-").toFile().also { root ->
            root.resolve("entries").mkdirs()
            root.resolve("entries/${entry.id}.md")
                .writeText(CanonicalMarkdownEntryCodec().encode(entry))
            entry.media.forEach { media ->
                root.resolve(media.relativePath).also { file ->
                    file.parentFile!!.mkdirs()
                    file.writeBytes(byteArrayOf(0x0a, 0x0b, 0x0c))
                }
            }
        }
}

private fun app.respiral.core.model.VaultEntry.withMedia(
    title: String,
    media: List<VaultMedia>,
): app.respiral.core.model.VaultEntry = app.respiral.core.model.VaultEntry(
    id = id,
    title = title,
    body = body,
    createdAt = createdAt,
    updatedAt = updatedAt,
    tags = tags,
    media = media,
)

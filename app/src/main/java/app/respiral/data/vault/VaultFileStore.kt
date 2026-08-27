package app.respiral.data.vault

import android.content.Context
import android.net.Uri
import app.respiral.core.markdown.MarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultMedia
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Owns the app-private canonical Markdown and media files under `filesDir/vault`. */
class VaultFileStore {
    private val vaultRoot: File
    private val codec: MarkdownEntryCodec
    private val openInputStream: (Uri) -> InputStream?

    constructor(context: Context, codec: MarkdownEntryCodec) : this(
        vaultRoot = context.filesDir.resolve(VAULT_DIRECTORY),
        codec = codec,
        openInputStream = context.contentResolver::openInputStream,
    )

    internal constructor(
        vaultRoot: File,
        codec: MarkdownEntryCodec,
        openInputStream: (Uri) -> InputStream?,
    ) {
        this.vaultRoot = vaultRoot
        this.codec = codec
        this.openInputStream = openInputStream
    }

    fun read(id: UUID): VaultEntry = codec.decode(markdownFile(id).readText(Charsets.UTF_8))

    fun readAll(): List<VaultEntry> {
        val directory = entriesDirectory()
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile() && it.getName().endsWith(".$MARKDOWN_EXTENSION") }
            .sortedBy { it.getName() }
            .map { codec.decode(it.readText(Charsets.UTF_8)) }
            .toList()
    }

    /** Writes canonical Markdown atomically without changing media files. */
    fun write(entry: VaultEntry) {
        val staged = stage(entry, emptyList())
        val promoted = promote(staged)
        try {
            promoted.complete()
        } catch (error: Throwable) {
            promoted.restore()
            throw error
        }
    }

    internal fun stage(entry: VaultEntry, pendingMedia: List<PendingMedia>): StagedEntry {
        ensureDirectory(entriesDirectory())
        val persistedEntry = if (pendingMedia.isEmpty()) {
            entry
        } else {
            VaultEntry(
                id = entry.id,
                title = entry.title,
                body = entry.body,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                tags = entry.tags,
                media = pendingMedia.mapIndexed { index, media ->
                    VaultMedia("media/${entry.id}/$index${extensionFor(media.mimeType)}", media.mimeType)
                },
            )
        }
        val temporaryMedia = mutableListOf<TemporaryMedia>()
        var markdownTemporary: File? = null

        try {
            pendingMedia.forEachIndexed { index, media ->
                val destination = mediaDirectory(entry.id).resolve("$index${extensionFor(media.mimeType)}")
                ensureDirectory(destination.getParentFile()!!)
                val temporary = temporarySibling(destination)
                try {
                    openInputStream(media.source)?.use { input ->
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    } ?: throw FileNotFoundException("Unable to open selected media: ${media.source}")
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
                temporaryMedia += TemporaryMedia(temporary, destination)
            }

            val markdownDestination = markdownFile(entry.id)
            markdownTemporary = temporarySibling(markdownDestination)
            markdownTemporary.writeText(codec.encode(persistedEntry), Charsets.UTF_8)
            return StagedEntry(persistedEntry, markdownTemporary, markdownDestination, temporaryMedia)
        } catch (error: Throwable) {
            temporaryMedia.forEach { it.temporary.delete() }
            markdownTemporary?.delete()
            throw error
        }
    }

    internal fun promote(staged: StagedEntry): PromotedEntry {
        val promoted = mutableListOf<PromotedFile>()
        try {
            staged.media.forEach { media ->
                promoted += promoteFile(media.temporary, media.destination)
            }
            promoted += promoteFile(staged.markdownTemporary, staged.markdownDestination)
            return PromotedEntry(promoted)
        } catch (error: Throwable) {
            promoted.asReversed().forEach(PromotedFile::restore)
            staged.media.forEach { it.temporary.delete() }
            staged.markdownTemporary.delete()
            throw error
        }
    }

    internal fun stageDelete(id: UUID): StagedDeletion {
        val moved = mutableListOf<MovedPath>()
        try {
            listOf(markdownFile(id), mediaDirectory(id)).filter { it.exists() }.forEach { original ->
                val backup = original.resolveSibling("${original.getName()}.deleting-${UUID.randomUUID()}")
                move(original, backup)
                moved += MovedPath(original, backup)
            }
            return StagedDeletion(moved)
        } catch (error: Throwable) {
            moved.asReversed().forEach(MovedPath::restore)
            throw error
        }
    }

    internal class StagedEntry(
        val entry: VaultEntry,
        val markdownTemporary: File,
        val markdownDestination: File,
        val media: List<TemporaryMedia>,
    )

    internal class TemporaryMedia(val temporary: File, val destination: File)

    internal class PromotedFile(val destination: File, val backup: File?) {
        fun restore() {
            destination.deleteRecursively()
            backup?.let {
                if (!it.renameTo(destination)) throw IOException("Unable to restore private vault file")
            }
        }
    }

    internal class MovedPath(val original: File, val backup: File) {
        fun restore() {
            if (!backup.renameTo(original)) throw IOException("Unable to restore private vault path")
        }
    }

    internal class PromotedEntry(private val files: List<PromotedFile>) {
        fun complete() {
            files.forEach { it.backup?.deleteRecursively() }
        }

        fun restore() {
            files.asReversed().forEach(PromotedFile::restore)
        }
    }

    internal class StagedDeletion(private val paths: List<MovedPath>) {
        fun complete() {
            paths.forEach { it.backup.deleteRecursively() }
        }

        fun restore() {
            paths.asReversed().forEach(MovedPath::restore)
        }
    }

    private fun promoteFile(temporary: File, destination: File): PromotedFile {
        val backup = destination.takeIf { it.exists() }?.let {
            destination.resolveSibling("${destination.getName()}.backup-${UUID.randomUUID()}").also { backup ->
                move(destination, backup)
            }
        }
        try {
            move(temporary, destination)
        } catch (error: Throwable) {
            backup?.let { move(it, destination) }
            throw error
        }
        return PromotedFile(destination, backup)
    }

    private fun temporarySibling(destination: File): File = destination.resolveSibling(
        "${destination.getName()}.temporary-${UUID.randomUUID()}",
    )

    private fun move(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            throw IOException("Unable to move ${source.getName()} into the private vault")
        }
    }

    private fun entriesDirectory(): File = vaultRoot.resolve(ENTRIES_DIRECTORY)

    private fun mediaDirectory(id: UUID): File = vaultRoot.resolve(MEDIA_DIRECTORY).resolve(id.toString())

    private fun markdownFile(id: UUID): File = entriesDirectory().resolve("$id.$MARKDOWN_EXTENSION")

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create private vault directory")
        }
        if (!directory.isDirectory()) throw IOException("Private vault path is not a directory")
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> ".bin"
    }

    private companion object {
        const val VAULT_DIRECTORY = "vault"
        const val ENTRIES_DIRECTORY = "entries"
        const val MEDIA_DIRECTORY = "media"
        const val MARKDOWN_EXTENSION = "md"
    }
}

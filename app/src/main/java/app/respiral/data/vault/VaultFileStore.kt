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
        ensureDirectory(entriesDirectory())
        val markdownDestination = markdownFile(entry.id)
        val markdownTemporary = temporarySibling(markdownDestination)
        try {
            markdownTemporary.writeText(codec.encode(entry), Charsets.UTF_8)
        } catch (error: Throwable) {
            markdownTemporary.delete()
            throw error
        }
        val staged = StagedEntry(entry, markdownTemporary, markdownDestination, media = null)
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
        val existingMedia = entry.media
        val newMediaNames = nextMediaFileNames(existingMedia, pendingMedia)
        val newMedia = pendingMedia.mapIndexed { index, media ->
            VaultMedia(
                relativePath = "media/${entry.id}/${newMediaNames[index]}",
                mimeType = media.mimeType,
            )
        }
        val persistedEntry = VaultEntry(
            id = entry.id,
            title = entry.title,
            body = entry.body,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
            tags = entry.tags,
            media = existingMedia + newMedia,
        )
        val mediaDestination = mediaDirectory(entry.id)
        var mediaTemporary: File? = null
        var markdownTemporary: File? = null

        try {
            ensureDirectory(mediaDestination.getParentFile()!!)
            mediaTemporary = temporarySibling(mediaDestination)
            ensureDirectory(mediaTemporary)

            existingMedia.forEach { media ->
                val source = vaultRoot.resolve(media.relativePath)
                val canonicalSource = source.canonicalFile
                val canonicalMediaDestination = mediaDestination.canonicalFile
                if (!canonicalSource.exists() || !canonicalSource.isFile) {
                    throw FileNotFoundException("Retained media is missing: ${media.relativePath}")
                }
                if (!canonicalSource.toPath().startsWith(canonicalMediaDestination.toPath())) {
                    throw IOException("Retained media is outside its entry directory: ${media.relativePath}")
                }
                val relativePath = canonicalSource.relativeTo(canonicalMediaDestination)
                val destination = mediaTemporary.resolve(relativePath)
                ensureDirectory(destination.getParentFile()!!)
                canonicalSource.copyTo(destination, overwrite = false)
            }
            pendingMedia.forEachIndexed { index, media ->
                val destination = mediaTemporary.resolve(newMediaNames[index])
                openInputStream(media.source)?.use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw FileNotFoundException("Unable to open selected media: ${media.source}")
            }

            val markdownDestination = markdownFile(entry.id)
            markdownTemporary = temporarySibling(markdownDestination)
            markdownTemporary.writeText(codec.encode(persistedEntry), Charsets.UTF_8)
            return StagedEntry(
                entry = persistedEntry,
                markdownTemporary = markdownTemporary,
                markdownDestination = markdownDestination,
                media = StagedMediaDirectory(mediaTemporary, mediaDestination),
            )
        } catch (error: Throwable) {
            mediaTemporary?.deleteRecursively()
            markdownTemporary?.delete()
            throw error
        }
    }

    private fun nextMediaFileNames(
        existingMedia: List<VaultMedia>,
        pendingMedia: List<PendingMedia>,
    ): List<String> {
        val usedStems = existingMedia
            .map { File(it.relativePath).nameWithoutExtension }
            .toMutableSet()
        var nextIndex = 0
        return pendingMedia.map { media ->
            while (nextIndex.toString() in usedStems) nextIndex += 1
            val name = "$nextIndex${extensionFor(media.mimeType)}"
            usedStems += nextIndex.toString()
            nextIndex += 1
            name
        }
    }

    internal fun promote(staged: StagedEntry): PromotedEntry {
        val promoted = mutableListOf<PromotedFile>()
        try {
            staged.media?.let { media -> promoted += promotePath(media.temporary, media.destination) }
            promoted += promoteFile(staged.markdownTemporary, staged.markdownDestination)
            return PromotedEntry(promoted)
        } catch (error: Throwable) {
            promoted.asReversed().forEach(PromotedFile::restore)
            staged.media?.temporary?.deleteRecursively()
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
        val media: StagedMediaDirectory?,
    )

    internal class StagedMediaDirectory(val temporary: File?, val destination: File)

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
        return promotePath(temporary, destination)
    }

    private fun promotePath(temporary: File?, destination: File): PromotedFile {
        val backup = destination.takeIf { it.exists() }?.let {
            destination.resolveSibling("${destination.getName()}.backup-${UUID.randomUUID()}").also { backup ->
                move(destination, backup)
            }
        }
        try {
            temporary?.let { move(it, destination) }
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

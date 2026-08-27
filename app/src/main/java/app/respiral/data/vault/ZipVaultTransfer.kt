package app.respiral.data.vault

import app.respiral.core.markdown.MarkdownEntryCodec
import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Explicit, offline transfer of the canonical Markdown vault and its private media. */
class ZipVaultTransfer(
    private val repository: VaultRepository,
    private val fileStore: VaultFileStore,
    private val cacheDirectory: File,
    private val codec: MarkdownEntryCodec = app.respiral.core.markdown.CanonicalMarkdownEntryCodec(),
) {
    suspend fun export(destination: OutputStream): ExportResult = withContext(Dispatchers.IO) {
        val entries = fileStore.readAll().sortedBy { it.id.toString() }
        val mediaCount = entries.sumOf { it.media.size }
        destination.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                zip.writeEntry("manifest.json", manifest(entries.size, mediaCount))
                entries.forEach { entry ->
                    zip.writeEntry("entries/${entry.id}.md", codec.encode(entry))
                    entry.media.sortedBy { it.relativePath }.forEach { media ->
                        val archivePath = validateExportMediaPath(entry, media.relativePath)
                        val source = fileStore.rootDirectory.resolve(media.relativePath)
                        val canonicalSource = source.canonicalFile
                        val canonicalRoot = fileStore.rootDirectory.canonicalFile
                        if (!canonicalSource.isFile || !canonicalSource.toPath().startsWith(canonicalRoot.toPath())) {
                            throw IOException("Vault media is missing or outside the private vault")
                        }
                        zip.putNextEntry(ZipEntry(archivePath).apply { time = 0L })
                        canonicalSource.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        ExportResult(entries.size, mediaCount)
    }

    suspend fun preview(source: InputStream): ImportPreview = withContext(Dispatchers.IO) {
        val archive = readArchive(source)
        try {
            ImportPreview(
                entryCount = archive.entries.size,
                mediaCount = archive.mediaCount,
                tags = archive.entries.flatMapTo(mutableSetOf()) { it.tags },
            )
        } finally {
            archive.root.deleteRecursively()
        }
    }

    suspend fun apply(source: InputStream, mode: ImportMode): ImportResult = withContext(Dispatchers.IO) {
        val archive = readArchive(source)
        try {
            val importedIds = repository.applyImportedVault(archive.root, archive.entries, mode)
            ImportResult(
                mode = mode,
                importedEntryCount = importedIds.size,
                skippedEntryCount = archive.entries.size - importedIds.size,
                mediaCount = archive.mediaCount,
            )
        } finally {
            archive.root.deleteRecursively()
        }
    }

    private fun readArchive(source: InputStream): ValidatedArchive {
        val root = cacheDirectory.resolve("imports").resolve(UUID.randomUUID().toString())
        if (!root.mkdirs()) throw InvalidVaultArchiveException("Unable to prepare import")
        return try {
            val paths = linkedSetOf<String>()
            var archiveEntryCount = 0
            var totalUncompressedBytes = 0L
            source.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        archiveEntryCount += 1
                        if (archiveEntryCount > MAX_ARCHIVE_ENTRY_COUNT) {
                            invalid("Archive contains too many entries")
                        }
                        val path = validateArchivePath(entry.name)
                        if (!paths.add(path)) invalid("Archive contains duplicate paths")
                        val destination = root.resolve(path)
                        destination.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) invalid("Unable to extract archive")
                        }
                        destination.outputStream().buffered().use { output ->
                            totalUncompressedBytes += copyEntryWithLimits(zip, output, totalUncompressedBytes)
                        }
                        zip.closeEntry()
                    }
                }
            }
            validateExtractedArchive(root, paths)
        } catch (error: InvalidVaultArchiveException) {
            root.deleteRecursively()
            throw error
        } catch (error: Exception) {
            root.deleteRecursively()
            throw InvalidVaultArchiveException("The selected file is not a valid Respiral vault", error)
        }
    }

    private fun validateExtractedArchive(root: File, paths: Set<String>): ValidatedArchive {
        if ("manifest.json" !in paths) invalid("Archive manifest is missing")
        val manifest = try {
            JSONObject(root.resolve("manifest.json").readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw InvalidVaultArchiveException("Archive manifest is malformed", error)
        }
        val version = manifest.intField("formatVersion")
        if (version != FORMAT_VERSION) invalid("Unsupported archive format")
        val expectedEntryCount = manifest.nonNegativeIntField("entryCount")
        val expectedMediaCount = manifest.nonNegativeIntField("mediaCount")
        val createdAt = manifest.stringField("createdAt")
        try {
            if (!createdAt.endsWith("Z")) invalid("Archive creation time must be UTC")
            Instant.parse(createdAt)
        } catch (error: InvalidVaultArchiveException) {
            throw error
        } catch (error: Exception) {
            throw InvalidVaultArchiveException("Archive creation time is malformed", error)
        }

        val entryPaths = paths.filter { it.startsWith("entries/") }
        val entries = entryPaths.map { path ->
            val id = path.removePrefix("entries/").removeSuffix(".md")
            val parsedId = try {
                UUID.fromString(id)
            } catch (error: IllegalArgumentException) {
                throw InvalidVaultArchiveException("Archive entry path is malformed", error)
            }
            if (path != "entries/${parsedId}.md") invalid("Archive entry path is not canonical")
            val entry = try {
                codec.decode(root.resolve(path).readText(Charsets.UTF_8))
            } catch (error: Exception) {
                throw InvalidVaultArchiveException("Archive contains malformed Markdown", error)
            }
            if (entry.id != parsedId) invalid("Archive entry path does not match its Markdown id")
            entry
        }.sortedBy { it.id.toString() }

        val mediaReferences = entries.flatMap { entry ->
            entry.media.map { media -> validateImportedMediaPath(entry, media.relativePath) }
        }
        if (mediaReferences.size != mediaReferences.toSet().size) {
            invalid("Archive Markdown contains duplicate media references")
        }
        val referencedMedia = mediaReferences.toSet()
        val archiveMedia = paths.filter { it.startsWith("media/") }.toSet()
        if (referencedMedia != archiveMedia) invalid("Archive media does not match Markdown references")
        archiveMedia.forEach { path ->
            if (!root.resolve(path).isFile) invalid("Archive media is missing")
        }
        if (entries.size != expectedEntryCount) invalid("Archive entry count does not match its manifest")
        if (referencedMedia.size != expectedMediaCount) invalid("Archive media count does not match its manifest")
        if (paths.size != 1 + entryPaths.size + archiveMedia.size) invalid("Archive contains an unsupported path")
        return ValidatedArchive(root, entries, archiveMedia.size)
    }

    private fun validateArchivePath(path: String): String {
        if (
            path.isBlank() || path.startsWith('/') || path.startsWith('\\') ||
            path.contains('\\') || path.contains('\u0000')
        ) invalid("Archive path is unsafe")
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) invalid("Archive path is unsafe")
        if (
            path != "manifest.json" &&
            !Regex("entries/[0-9a-fA-F-]{36}\\.md").matches(path) &&
            !Regex("media/[0-9a-fA-F-]{36}/[^/]+\\.[^/]+|media/[0-9a-fA-F-]{36}/[^/]+\\z").matches(path)
        ) invalid("Archive contains an unsupported path")
        return path
    }

    private fun validateExportMediaPath(entry: VaultEntry, path: String): String {
        val prefix = "media/${entry.id}/"
        if (!path.startsWith(prefix)) throw IOException("Vault media path is invalid")
        val filename = path.removePrefix(prefix)
        if (filename.isBlank() || filename.contains('/') || filename.contains('\\')) {
            throw IOException("Vault media path is invalid")
        }
        return path
    }

    private fun validateImportedMediaPath(entry: VaultEntry, path: String): String {
        val prefix = "media/${entry.id}/"
        if (!path.startsWith(prefix)) invalid("Markdown references media owned by another entry")
        val filename = path.removePrefix(prefix)
        if (filename.isBlank() || filename.contains('/') || filename.contains('\\')) invalid("Media path is unsafe")
        return path
    }

    private fun manifest(entryCount: Int, mediaCount: Int): String = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("createdAt", Instant.now().toString())
        .put("entryCount", entryCount)
        .put("mediaCount", mediaCount)
        .toString()

    private fun copyEntryWithLimits(input: InputStream, output: OutputStream, totalBefore: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            val readBytes = read.toLong()
            if (entryBytes > MAX_ENTRY_UNCOMPRESSED_BYTES - readBytes) {
                invalid("Archive entry exceeds the decompressed size limit")
            }
            if (totalBefore > MAX_TOTAL_UNCOMPRESSED_BYTES - entryBytes - readBytes) {
                invalid("Archive exceeds the total decompressed size limit")
            }
            output.write(buffer, 0, read)
            entryBytes += readBytes
        }
        return entryBytes
    }

    private fun invalid(message: String): Nothing = throw InvalidVaultArchiveException(message)

    private fun JSONObject.intField(name: String): Int = try {
        getInt(name)
    } catch (error: JSONException) {
        throw InvalidVaultArchiveException("Archive manifest is missing $name", error)
    }

    private fun JSONObject.nonNegativeIntField(name: String): Int = intField(name).also {
        if (it < 0) invalid("Archive manifest contains a negative count")
    }

    private fun JSONObject.stringField(name: String): String = try {
        getString(name)
    } catch (error: JSONException) {
        throw InvalidVaultArchiveException("Archive manifest is missing $name", error)
    }

    private fun ZipOutputStream.writeEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path).apply { time = 0L })
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private data class ValidatedArchive(
        val root: File,
        val entries: List<VaultEntry>,
        val mediaCount: Int,
    )

    internal companion object {
        const val FORMAT_VERSION = 1

        /** Hard limits keep malformed or compressed-bomb imports bounded in memory and on disk. */
        internal const val MAX_ARCHIVE_ENTRY_COUNT = 512
        internal const val MAX_ENTRY_UNCOMPRESSED_BYTES = 16L * 1024L * 1024L
        internal const val MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    }
}

package app.respiral.data.vault

import app.respiral.core.model.VaultTag

/** A validated summary shown before any imported archive is applied. */
data class ImportPreview(
    val entryCount: Int,
    val mediaCount: Int,
    val tags: Set<VaultTag>,
)

enum class ImportMode {
    MERGE,
    REPLACE,
}

data class ExportResult(
    val entryCount: Int,
    val mediaCount: Int,
)

data class ImportResult(
    val mode: ImportMode,
    val importedEntryCount: Int,
    val skippedEntryCount: Int,
    val mediaCount: Int,
) {
    /** Number of entries present in the validated archive. */
    val entryCount: Int get() = importedEntryCount + skippedEntryCount
}

class InvalidVaultArchiveException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

package app.respiral.data.vault

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultTag
import app.respiral.data.index.EntryIndexEntity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class LegacyRecoveryReport(
    val recoveredCount: Int,
    val failureCount: Int,
    val diagnosticCode: VaultDiagnosticCode?,
)

/** Reconstructs missing or malformed canonical Markdown from the legacy Room index. */
class LegacyVaultRecovery(private val fileStore: VaultFileStore) {
    fun recover(rows: List<EntryIndexEntity>): LegacyRecoveryReport {
        var recoveredCount = 0
        var failureCount = 0

        rows.forEach { row ->
            try {
                val id = UUID.fromString(row.id)
                if (fileStore.entryFileExists(id)) {
                    try {
                        fileStore.read(id)
                        return@forEach
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        fileStore.quarantineMalformed(id)
                    }
                }

                val entry = VaultEntry(
                    id = id,
                    title = row.title,
                    body = row.bodyForSearch,
                    createdAt = Instant.ofEpochMilli(row.createdAtEpochMs),
                    updatedAt = Instant.ofEpochMilli(row.updatedAtEpochMs),
                    tags = parseTags(row.tagNames),
                    media = fileStore.discoverMedia(id),
                )
                fileStore.write(entry)
                fileStore.read(id)
                recoveredCount += 1
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failureCount += 1
            }
        }

        return LegacyRecoveryReport(
            recoveredCount = recoveredCount,
            failureCount = failureCount,
            diagnosticCode = VaultDiagnosticCode.RSP_R02.takeIf { failureCount > 0 },
        )
    }

    private fun parseTags(tagNames: String): Set<VaultTag> {
        val names = tagNames
            .trim('|')
            .split('|')
            .filter(String::isNotEmpty)
        val tags = names.map(VaultTag::valueOf)
        require(tags.size == tags.toSet().size)
        return tags.toSet()
    }
}

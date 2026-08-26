package app.respiral.core.model

import java.time.Instant
import java.util.UUID
import kotlin.ConsistentCopyVisibility

@ConsistentCopyVisibility
data class VaultEntry internal constructor(
    val id: UUID,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    private val tagValues: Set<VaultTag>,
    private val mediaValues: List<VaultMedia>,
) {
    val tags: Set<VaultTag>
        get() = tagValues.toSet()

    val media: List<VaultMedia>
        get() = mediaValues.toList()

    companion object {
        operator fun invoke(
            id: UUID,
            title: String,
            body: String,
            createdAt: Instant,
            updatedAt: Instant,
            tags: Set<VaultTag>,
            media: List<VaultMedia>,
        ): VaultEntry = VaultEntry(
            id = id,
            title = title,
            body = body,
            createdAt = createdAt,
            updatedAt = updatedAt,
            tagValues = tags.toSet(),
            mediaValues = media.toList(),
        )
    }
}

data class VaultMedia(
    val relativePath: String,
    val mimeType: String,
)

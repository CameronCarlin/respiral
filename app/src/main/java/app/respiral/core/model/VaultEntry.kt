package app.respiral.core.model

import java.time.Instant
import java.util.UUID

data class VaultEntry(
    val id: UUID,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val tags: Set<VaultTag>,
    val media: List<VaultMedia>,
)

data class VaultMedia(
    val relativePath: String,
    val mimeType: String,
)

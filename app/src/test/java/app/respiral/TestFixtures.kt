package app.respiral

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultMedia
import app.respiral.core.model.VaultTag
import java.time.Instant
import java.util.UUID

val earlierInstant: Instant = Instant.parse("2026-08-26T09:00:00Z")
val laterInstant: Instant = Instant.parse("2026-08-26T10:00:00Z")

fun sampleEntry(
    id: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
    title: String = "A good thing",
    body: String = "I made time for what matters.",
    createdAt: Instant = earlierInstant,
    updatedAt: Instant = laterInstant,
    tags: Set<VaultTag> = setOf(VaultTag.AFFIRMATION),
    media: List<VaultMedia> = emptyList(),
): VaultEntry = VaultEntry(
    id = id,
    title = title,
    body = body,
    createdAt = createdAt,
    updatedAt = updatedAt,
    tags = tags,
    media = media,
)

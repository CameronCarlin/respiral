package app.respiral.core.markdown

import app.respiral.sampleEntry
import app.respiral.core.model.VaultMedia
import app.respiral.core.model.VaultTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MarkdownEntryCodecTest {
    private val codec: MarkdownEntryCodec = CanonicalMarkdownEntryCodec()

    @Test
    fun encode_then_decode_preserves_entry_and_relative_photo_path() {
        val entry = sampleEntry(
            tags = setOf(VaultTag.ACHIEVEMENT, VaultTag.WHO_I_AM),
            media = listOf(VaultMedia("media/a-photo.jpg", "image/jpeg")),
        )

        assertEquals(entry, codec.decode(codec.encode(entry)))
    }

    @Test
    fun encode_uses_fixed_front_matter_and_keeps_body_after_its_closing_delimiter() {
        val entry = sampleEntry(title = "A title: with punctuation", body = "First line\n\nSecond line")

        assertEquals(
            """
            ---
            id: 123e4567-e89b-12d3-a456-426614174000
            title: "A title: with punctuation"
            createdAt: 2026-08-26T09:00:00Z
            updatedAt: 2026-08-26T10:00:00Z
            tags: [AFFIRMATION]
            media: []
            ---
            First line

            Second line
            """.trimIndent(),
            codec.encode(entry),
        )
    }

    @Test
    fun decode_rejects_missing_required_front_matter() {
        assertThrows(MalformedEntryException::class.java) {
            codec.decode("# Unstructured note")
        }
    }

    @Test
    fun decode_rejects_duplicate_or_unknown_front_matter_values() {
        val duplicateId = codec.encode(sampleEntry()).replaceFirst(
            "id: 123e4567-e89b-12d3-a456-426614174000",
            "id: 123e4567-e89b-12d3-a456-426614174000\nid: 123e4567-e89b-12d3-a456-426614174000",
        )
        val unknownTag = codec.encode(sampleEntry()).replace(
            "tags: [AFFIRMATION]",
            "tags: [NOT_A_TAG]",
        )

        assertThrows(MalformedEntryException::class.java) { codec.decode(duplicateId) }
        assertThrows(MalformedEntryException::class.java) { codec.decode(unknownTag) }
    }

    @Test
    fun decode_rejects_media_path_that_escapes_the_vault_root() {
        val markdown = codec.encode(sampleEntry()).replace(
            "media: []",
            "media: [{path: \"media/../secret.jpg\", mimeType: \"image/jpeg\"}]",
        )

        assertThrows(MalformedEntryException::class.java) { codec.decode(markdown) }
    }

    @Test
    fun decode_rejects_front_matter_with_reordered_fields() {
        val markdown = codec.encode(sampleEntry()).replaceFirst(
            "id: 123e4567-e89b-12d3-a456-426614174000\ntitle: \"A good thing\"",
            "title: \"A good thing\"\nid: 123e4567-e89b-12d3-a456-426614174000",
        )

        assertThrows(MalformedEntryException::class.java) { codec.decode(markdown) }
    }

    @Test
    fun decode_rejects_tags_that_are_not_in_canonical_order() {
        val markdown = codec.encode(
            sampleEntry(tags = setOf(VaultTag.ACHIEVEMENT, VaultTag.WHO_I_AM)),
        ).replace(
            "tags: [ACHIEVEMENT, WHO_I_AM]",
            "tags: [WHO_I_AM, ACHIEVEMENT]",
        )

        assertThrows(MalformedEntryException::class.java) { codec.decode(markdown) }
    }

    @Test
    fun decode_rejects_volume_qualified_and_uri_like_media_paths() {
        val volumeQualified = codec.encode(sampleEntry()).replace(
            "media: []",
            "media: [{path: \"C:/outside.jpg\", mimeType: \"image/jpeg\"}]",
        )
        val uriLike = codec.encode(sampleEntry()).replace(
            "media: []",
            "media: [{path: \"file:///outside.jpg\", mimeType: \"image/jpeg\"}]",
        )

        assertThrows(MalformedEntryException::class.java) { codec.decode(volumeQualified) }
        assertThrows(MalformedEntryException::class.java) { codec.decode(uriLike) }
    }
}

package app.respiral.core.model

import app.respiral.sampleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultEntryTest {
    @Test
    fun construction_snapshots_caller_owned_tags_and_media() {
        val callerTags = linkedSetOf(VaultTag.AFFIRMATION)
        val callerMedia = mutableListOf(VaultMedia("media/original.jpg", "image/jpeg"))
        val entry = sampleEntry(tags = callerTags, media = callerMedia)
        val hashBeforeMutation = entry.hashCode()

        callerTags += VaultTag.ACHIEVEMENT
        callerMedia += VaultMedia("media/later.jpg", "image/jpeg")

        assertEquals(setOf(VaultTag.AFFIRMATION), entry.tags)
        assertEquals(listOf(VaultMedia("media/original.jpg", "image/jpeg")), entry.media)
        assertEquals(hashBeforeMutation, entry.hashCode())
    }

    @Test
    fun accessors_do_not_expose_mutable_collection_snapshots() {
        val entry = sampleEntry(
            tags = linkedSetOf(VaultTag.AFFIRMATION, VaultTag.WHO_I_AM),
            media = mutableListOf(
                VaultMedia("media/original.jpg", "image/jpeg"),
                VaultMedia("media/second.jpg", "image/jpeg"),
            ),
        )
        val expected = sampleEntry(
            tags = setOf(VaultTag.AFFIRMATION, VaultTag.WHO_I_AM),
            media = listOf(
                VaultMedia("media/original.jpg", "image/jpeg"),
                VaultMedia("media/second.jpg", "image/jpeg"),
            ),
        )
        val hashBeforeMutation = entry.hashCode()

        (entry.tags as MutableSet<VaultTag>) += VaultTag.ACHIEVEMENT
        (entry.media as MutableList<VaultMedia>) += VaultMedia("media/later.jpg", "image/jpeg")

        assertEquals(expected.tags, entry.tags)
        assertEquals(expected.media, entry.media)
        assertEquals(expected, entry)
        assertEquals(hashBeforeMutation, entry.hashCode())
    }
}

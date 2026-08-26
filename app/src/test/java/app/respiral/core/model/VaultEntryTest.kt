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
}

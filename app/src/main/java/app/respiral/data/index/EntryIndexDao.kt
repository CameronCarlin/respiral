package app.respiral.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: EntryIndexEntity)

    @Query("DELETE FROM entry_index WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM entry_index")
    suspend fun clear()

    @Query(
        """
        SELECT * FROM entry_index
        WHERE (
            :query = ''
            OR title LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE
            OR bodyForSearch LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE
        )
        AND (
            :hasSelectedTags = 0
            OR (:includeAchievement = 1 AND tagNames LIKE '%|ACHIEVEMENT|%')
            OR (:includeAffirmation = 1 AND tagNames LIKE '%|AFFIRMATION|%')
            OR (:includeWhoIAm = 1 AND tagNames LIKE '%|WHO_I_AM|%')
        )
        ORDER BY createdAtEpochMs DESC
        """,
    )
    fun observeTimeline(
        query: String,
        hasSelectedTags: Boolean,
        includeAchievement: Boolean,
        includeAffirmation: Boolean,
        includeWhoIAm: Boolean,
    ): Flow<List<EntryIndexEntity>>
}

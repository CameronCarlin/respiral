package app.respiral.data.index

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A rebuildable search projection of an entry's canonical Markdown file. */
@Entity(tableName = "entry_index")
data class EntryIndexEntity(
    @PrimaryKey val id: String,
    val title: String,
    val bodyForSearch: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val tagNames: String,
)

package app.respiral.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EntryIndexEntity::class], version = 1, exportSchema = false)
abstract class RespiralDatabase : RoomDatabase() {
    abstract fun entryIndexDao(): EntryIndexDao

    companion object {
        fun create(context: Context): RespiralDatabase = Room.databaseBuilder(
            context,
            RespiralDatabase::class.java,
            "respiral-index.db",
        ).build()
    }
}

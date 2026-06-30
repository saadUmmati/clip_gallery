package com.example.gallery.app.data.db


import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.gallery.app.data.db.dao.ClusterDao
import com.example.gallery.app.data.db.dao.MediaItemDao
import com.example.gallery.app.data.db.dao.RecycleBinDao
import com.example.gallery.app.data.db.entities.ClusterEntity
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.db.entities.RecycleBinEntity

@Database(
    entities = [
        MediaItemEntity::class,
        ClusterEntity::class,
        RecycleBinEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class ClipGalleryDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun clusterDao(): ClusterDao
    abstract fun recycleBinDao(): RecycleBinDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN embedding BLOB DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN isInVault INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

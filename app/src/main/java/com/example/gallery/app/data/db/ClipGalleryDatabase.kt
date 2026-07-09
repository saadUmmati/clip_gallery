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
    version = 6,
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN folder TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 5: no structural changes — this migration prevents
                // fallbackToDestructiveMigration() from wiping embeddings on upgrade.
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clusters ADD COLUMN centroidEmbedding BLOB DEFAULT NULL")
            }
        }
    }
}
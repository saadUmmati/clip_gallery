package com.example.gallery.app.data.db


import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
abstract class ClipGalleryDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun clusterDao(): ClusterDao
    abstract fun recycleBinDao(): RecycleBinDao
}

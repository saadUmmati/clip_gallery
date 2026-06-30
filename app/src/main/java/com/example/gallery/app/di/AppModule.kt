package com.example.gallery.app.di


import android.content.Context
import androidx.room.Room
import com.example.gallery.app.data.db.ClipGalleryDatabase
import com.example.gallery.app.data.db.dao.ClusterDao
import com.example.gallery.app.data.db.dao.MediaItemDao
import com.example.gallery.app.data.db.dao.RecycleBinDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ClipGalleryDatabase {
        return Room.databaseBuilder(
            context,
            ClipGalleryDatabase::class.java,
            "clipgallery.db"
        )
            .addMigrations(
                ClipGalleryDatabase.MIGRATION_1_2,
                ClipGalleryDatabase.MIGRATION_2_3
            )
            .build()
    }

    @Provides
    fun provideMediaItemDao(db: ClipGalleryDatabase): MediaItemDao = db.mediaItemDao()

    @Provides
    fun provideClusterDao(db: ClipGalleryDatabase): ClusterDao = db.clusterDao()

    @Provides
    fun provideRecycleBinDao(db: ClipGalleryDatabase): RecycleBinDao = db.recycleBinDao()
}

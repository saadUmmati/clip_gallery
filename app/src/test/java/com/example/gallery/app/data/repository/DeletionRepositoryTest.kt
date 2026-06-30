package com.example.gallery.app.data.repository

import android.net.Uri
import com.example.gallery.app.data.db.dao.ClusterDao
import com.example.gallery.app.data.db.dao.MediaItemDao
import com.example.gallery.app.data.db.dao.RecycleBinDao
import com.example.gallery.app.data.db.entities.MediaItemEntity
import com.example.gallery.app.data.db.entities.RecycleBinEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeletionRepositoryTest {

    private lateinit var mediaItemDao: MediaItemDao
    private lateinit var recycleBinDao: RecycleBinDao
    private lateinit var deletionRepository: DeletionRepository

    @Before
    fun setup() {
        mediaItemDao = mockk(relaxed = true)
        recycleBinDao = mockk(relaxed = true)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        deletionRepository = DeletionRepository(
            context = mockk(relaxed = true),
            mediaItemDao = mediaItemDao,
            recycleBinDao = recycleBinDao
        )
    }

    @Test
    fun `moveToRecycleBin inserts entries and updates media items`() = runTest {
        val items = listOf(
            createMediaItem("uri1", "file1.jpg"),
            createMediaItem("uri2", "file2.jpg")
        )

        deletionRepository.moveToRecycleBin(items)

        coVerify(exactly = 1) { mediaItemDao.moveToRecycleBin(listOf("uri1", "uri2"), any()) }
        coVerify(exactly = 1) { recycleBinDao.insertAll(any()) }
    }

    @Test
    fun `restoreFromRecycleBin removes from recycle bin and restores media`() = runTest {
        val uris = listOf("uri1", "uri2")

        deletionRepository.restoreFromRecycleBin(uris)

        coVerify(exactly = 1) { mediaItemDao.restoreFromRecycleBin(uris) }
        coVerify(exactly = 1) { recycleBinDao.deleteByUris(uris) }
    }

    @Test
    fun `confirmDeletion removes from both tables`() = runTest {
        val uris = listOf("uri1", "uri2")

        deletionRepository.confirmDeletion(uris)

        coVerify(exactly = 1) { mediaItemDao.deleteByUris(uris) }
        coVerify(exactly = 1) { recycleBinDao.deleteByUris(uris) }
    }

    @Test
    fun `purgeExpiredItems with no expired items does nothing`() = runTest {
        coEvery { recycleBinDao.getExpired(any()) } returns emptyList()

        deletionRepository.purgeExpiredItems(null)

        coVerify(exactly = 0) { mediaItemDao.deleteByUris(any()) }
    }

    @Test
    fun `purgeExpiredItems with expired items requests deletion`() = runTest {
        val expired = listOf(
            RecycleBinEntity(uri = "uri1", originalPath = "/path1", fileName = "file1.jpg")
        )
        coEvery { recycleBinDao.getExpired(any()) } returns expired
        coEvery { mediaItemDao.deleteByUris(any()) } returns Unit
        coEvery { recycleBinDao.deleteByUris(any()) } returns Unit

        deletionRepository.purgeExpiredItems(null)

        coVerify(exactly = 1) { recycleBinDao.getExpired(any()) }
    }

    private fun createMediaItem(uri: String, fileName: String) = MediaItemEntity(
        uri = uri,
        filePath = "/path/$fileName",
        fileName = fileName,
        dateAdded = System.currentTimeMillis(),
        sizeBytes = 1024L,
        width = 1920,
        height = 1080,
        mimeType = "image/jpeg"
    )
}

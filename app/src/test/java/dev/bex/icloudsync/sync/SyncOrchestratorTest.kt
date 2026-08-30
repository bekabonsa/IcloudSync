package dev.bex.icloudsync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.bex.icloudsync.data.local.AppDatabase
import dev.bex.icloudsync.data.model.BackupState
import dev.bex.icloudsync.data.model.LocalMediaEntity
import dev.bex.icloudsync.data.model.MediaKind
import dev.bex.icloudsync.data.model.SyncCursorEntity
import dev.bex.icloudsync.icloud.AuthResult
import dev.bex.icloudsync.icloud.DriveItem
import dev.bex.icloudsync.icloud.ICloudException
import dev.bex.icloudsync.icloud.ICloudGateway
import dev.bex.icloudsync.icloud.ICloudStorageUsage
import dev.bex.icloudsync.icloud.PhotoUpload
import dev.bex.icloudsync.icloud.RemotePage
import dev.bex.icloudsync.icloud.UploadResult
import dev.bex.icloudsync.media.MediaAccess
import dev.bex.icloudsync.media.MediaRepository
import dev.bex.icloudsync.media.ScanResult
import dev.bex.icloudsync.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncOrchestratorTest {
    private lateinit var database: AppDatabase
    private lateinit var settings: AppSettings
    private lateinit var mediaRepository: FakeMediaRepository
    private lateinit var gateway: FakeGateway

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = AppSettings(context).also {
            it.reset()
            it.setStagedPhotosUploadMigrationComplete(true)
        }
        mediaRepository = FakeMediaRepository()
        gateway = FakeGateway()
    }

    @After
    fun tearDown() = runBlocking {
        settings.reset()
        database.close()
    }

    @Test
    fun `permanent Drive fallback failure does not block later Photos uploads`() = runBlocking {
        val unsupported = "matroska".encodeToByteArray()
        val photo = "jpeg".encodeToByteArray()
        val first = local("first", "unsupported.mkv", "video/x-matroska", unsupported, 1)
        val second = local("second", "photo.jpg", "image/jpeg", photo, 2)
        mediaRepository.bytes[first.localId] = unsupported
        mediaRepository.bytes[second.localId] = photo
        database.syncDao().upsertLocalMedia(listOf(first, second))
        database.syncDao().upsertCursor(
            SyncCursorEntity(
                zone = ReconciliationEngine.PRIMARY_ZONE,
                token = "current-token",
                fullReconciliationComplete = true,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val orchestrator = SyncOrchestrator(
            database.syncDao(),
            gateway,
            mediaRepository,
            ReconciliationEngine(database.syncDao(), gateway, mediaRepository),
            settings,
        )

        val outcome = orchestrator.run()

        assertSame(SyncOutcome.Paused, outcome)
        val failed = database.syncDao().localMedia(first.localId)!!
        assertEquals(BackupState.FAILED, failed.backupState)
        assertEquals("Drive metadata update was rejected with HTTP 412", failed.lastError)
        assertEquals(BackupState.SYNCED_PHOTOS, database.syncDao().localMedia(second.localId)!!.backupState)
        assertEquals(listOf("unsupported.mkv", "photo.jpg"), gateway.photosAttempts)
    }

    private fun local(
        id: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        capturedOrder: Long,
    ) = LocalMediaEntity(
        localId = id,
        contentUri = "content://test/$id",
        displayName = name,
        mimeType = mimeType,
        mediaKind = if (mimeType.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE,
        sizeBytes = bytes.size.toLong(),
        width = 1,
        height = 1,
        capturedAtEpochMs = capturedOrder,
        relativePath = "DCIM/Camera/",
        generationModified = 1,
        sha256 = bytes.sha256(),
        backupState = BackupState.PENDING,
        lastSeenAtEpochMs = System.currentTimeMillis(),
    )

    private class FakeMediaRepository : MediaRepository {
        val bytes = mutableMapOf<String, ByteArray>()
        override fun access() = MediaAccess.FULL
        override suspend fun scan(excludedFolders: Set<String>) = ScanResult(bytes.size, 0, MediaAccess.FULL)
        override suspend fun sha256(media: LocalMediaEntity) = bytes.getValue(media.localId).sha256()
        override fun open(media: LocalMediaEntity): InputStream = ByteArrayInputStream(bytes.getValue(media.localId))
    }

    private class FakeGateway : ICloudGateway {
        val photosAttempts = mutableListOf<String>()
        override suspend fun uploadToPhotos(upload: PhotoUpload): UploadResult {
            photosAttempts += upload.filename
            if (upload.mimeType == "video/x-matroska") throw ICloudException.UnsupportedType()
            return UploadResult("master-${upload.filename}", "asset-${upload.filename}", false)
        }

        override suspend fun uploadToDrive(path: String, sizeBytes: Long, source: () -> InputStream): DriveItem {
            throw ICloudException.Permanent("Drive metadata update was rejected with HTTP 412")
        }

        override suspend fun validateOrRefresh() = AuthResult.Authenticated
        override suspend fun listChanges(syncToken: String) = RemotePage(emptyList(), null, syncToken)
        override suspend fun signIn(appleId: String, password: String) = AuthResult.Authenticated
        override suspend fun requestTwoFactorCode() = true
        override suspend fun verifyTwoFactor(code: String) = AuthResult.Authenticated
        override suspend fun storageUsage() = ICloudStorageUsage(1, 0, 1, false, false, emptyList())
        override suspend fun countPhotos(includeHidden: Boolean) = 0L
        override suspend fun listPhotos(continuation: String?, hidden: Boolean) = RemotePage(emptyList(), null, "token")
        override suspend fun streamRemoteOriginal(asset: dev.bex.icloudsync.icloud.RemoteAsset): InputStream = error("Not used")
        override suspend fun listFallbackDriveItems() = emptyList<DriveItem>()
        override suspend fun streamDriveItem(item: DriveItem): InputStream = error("Not used")
        override fun isConfigured() = true
        override fun logout() = Unit
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

package dev.bex.icloudsync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.bex.icloudsync.data.local.AppDatabase
import dev.bex.icloudsync.data.model.BackupState
import dev.bex.icloudsync.data.model.LocalMediaEntity
import dev.bex.icloudsync.data.model.MediaKind
import dev.bex.icloudsync.icloud.*
import dev.bex.icloudsync.media.MediaAccess
import dev.bex.icloudsync.media.MediaRepository
import dev.bex.icloudsync.media.ScanResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconciliationEngineTest {
    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeGateway
    private lateinit var engine: ReconciliationEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeGateway()
        engine = ReconciliationEngine(database.syncDao(), gateway, FakeMediaRepository())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `full scan resumes at exact item and maps identical local bytes`() = runBlocking {
        val first = "already in icloud".encodeToByteArray()
        val second = "another remote original".encodeToByteArray()
        val firstAsset = remote("master-1", "first.jpg", first)
        val secondAsset = remote("master-2", "second.jpg", second)
        gateway.normalAssets = listOf(firstAsset, secondAsset)
        gateway.bytes = mapOf(firstAsset.masterId to first, secondAsset.masterId to second)
        gateway.failOnceFor = secondAsset.masterId
        database.syncDao().upsertLocalMedia(listOf(local("phone:1", first)))

        assertThrows(ICloudException.Transient::class.java) { runBlocking { engine.full() } }
        val checkpoint = database.syncDao().cursor()!!
        assertEquals(1L, checkpoint.remoteProcessed)
        assertEquals(1, checkpoint.pageOffset)
        assertFalse(checkpoint.fullReconciliationComplete)

        engine.full()

        assertEquals(1, gateway.streamCount[firstAsset.masterId])
        assertEquals(2, gateway.streamCount[secondAsset.masterId])
        assertTrue(database.syncDao().cursor()!!.fullReconciliationComplete)
        assertEquals(BackupState.SYNCED_PHOTOS, database.syncDao().localMedia("phone:1")!!.backupState)
    }

    @Test
    fun `incremental remote deletion never resurrects the local item`() = runBlocking {
        val bytes = "protected original".encodeToByteArray()
        val asset = remote("master-delete", "photo.jpg", bytes)
        gateway.normalAssets = listOf(asset)
        gateway.bytes = mapOf(asset.masterId to bytes)
        database.syncDao().upsertLocalMedia(listOf(local("phone:delete", bytes)))
        engine.full()
        assertEquals(BackupState.SYNCED_PHOTOS, database.syncDao().localMedia("phone:delete")!!.backupState)

        gateway.changeAssets = listOf(asset.copy(masterId = asset.assetId!!, assetId = null, deleted = true, downloadUrl = null))
        engine.incremental()

        assertEquals(BackupState.REMOTE_REMOVED, database.syncDao().localMedia("phone:delete")!!.backupState)
        assertTrue(database.syncDao().uploadCandidates().none { it.localId == "phone:delete" })
    }

    @Test
    fun `only recognized 32 byte checksums are comparable`() {
        val digest = ByteArray(32) { it.toByte() }
        val raw = Base64.getEncoder().encodeToString(digest)
        val tagged = Base64.getEncoder().encodeToString(byteArrayOf(1) + digest)

        assertEquals(digest.hex(), ReconciliationEngine.comparableSha256(raw))
        assertEquals(digest.hex(), ReconciliationEngine.comparableSha256(tagged))
        assertNull(ReconciliationEngine.comparableSha256(Base64.getEncoder().encodeToString(ByteArray(20))))
        assertNull(ReconciliationEngine.comparableSha256("not-base64"))
    }

    @Test
    fun `interrupted states recover but action-needed states stay blocked`() = runBlocking {
        val bytes = "state".encodeToByteArray()
        database.syncDao().upsertLocalMedia(
            listOf(
                local("uploading", bytes).copy(backupState = BackupState.UPLOADING),
                local("hashing", bytes).copy(sha256 = null, backupState = BackupState.HASHING),
                local("failed", bytes).copy(backupState = BackupState.FAILED),
                local("removed", bytes).copy(backupState = BackupState.REMOTE_REMOVED),
            ),
        )

        database.syncDao().recoverInterruptedStates()

        assertEquals(BackupState.PENDING, database.syncDao().localMedia("uploading")!!.backupState)
        assertEquals(BackupState.DISCOVERED, database.syncDao().localMedia("hashing")!!.backupState)
        assertEquals(BackupState.FAILED, database.syncDao().localMedia("failed")!!.backupState)
        assertEquals(BackupState.REMOTE_REMOVED, database.syncDao().localMedia("removed")!!.backupState)
        val queued = database.syncDao().uploadCandidates().map { it.localId }
        assertTrue("uploading" in queued)
        assertTrue("hashing" in queued)
        assertFalse("failed" in queued)
        assertFalse("removed" in queued)
    }

    private fun local(id: String, bytes: ByteArray) = LocalMediaEntity(
        localId = id,
        contentUri = "content://test/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        mediaKind = MediaKind.IMAGE,
        sizeBytes = bytes.size.toLong(),
        width = 1,
        height = 1,
        capturedAtEpochMs = 1_700_000_000_000,
        relativePath = "DCIM/Camera/",
        generationModified = 1,
        sha256 = bytes.sha256(),
        backupState = BackupState.PENDING,
        lastSeenAtEpochMs = System.currentTimeMillis(),
    )

    private fun remote(id: String, name: String, bytes: ByteArray) = RemoteAsset(
        masterId = id,
        assetId = "asset-$id",
        filename = name,
        mediaKind = MediaKind.IMAGE,
        sizeBytes = bytes.size.toLong(),
        capturedAtEpochMs = 1_700_000_000_000,
        providerChecksum = null,
        downloadUrl = "https://example.invalid/$id",
        hidden = false,
        deleted = false,
    )

    private class FakeMediaRepository : MediaRepository {
        override fun access() = MediaAccess.FULL
        override suspend fun scan(excludedFolders: Set<String>) = ScanResult(0, 0, MediaAccess.FULL)
        override suspend fun sha256(media: LocalMediaEntity) = error("Not used")
        override fun open(media: LocalMediaEntity): InputStream = error("Not used")
    }

    private class FakeGateway : ICloudGateway {
        var normalAssets: List<RemoteAsset> = emptyList()
        var hiddenAssets: List<RemoteAsset> = emptyList()
        var changeAssets: List<RemoteAsset> = emptyList()
        var bytes: Map<String, ByteArray> = emptyMap()
        var failOnceFor: String? = null
        val streamCount = mutableMapOf<String, Int>()

        override suspend fun countPhotos(includeHidden: Boolean) =
            (if (includeHidden) hiddenAssets else normalAssets).size.toLong()

        override suspend fun listPhotos(continuation: String?, hidden: Boolean) = RemotePage(
            assets = if (hidden) hiddenAssets else normalAssets,
            continuationMarker = null,
            syncToken = "token-1",
        )

        override suspend fun listChanges(syncToken: String) =
            RemotePage(changeAssets.also { changeAssets = emptyList() }, null, syncToken)

        override suspend fun streamRemoteOriginal(asset: RemoteAsset): InputStream {
            streamCount[asset.masterId] = (streamCount[asset.masterId] ?: 0) + 1
            if (failOnceFor == asset.masterId) {
                failOnceFor = null
                throw ICloudException.Transient("simulated interruption")
            }
            return ByteArrayInputStream(bytes.getValue(asset.masterId))
        }

        override suspend fun listFallbackDriveItems() = emptyList<DriveItem>()
        override suspend fun signIn(appleId: String, password: String) = AuthResult.Authenticated
        override suspend fun requestTwoFactorCode() = true
        override suspend fun verifyTwoFactor(code: String) = AuthResult.Authenticated
        override suspend fun validateOrRefresh() = AuthResult.Authenticated
        override suspend fun storageUsage() = ICloudStorageUsage(5_000, 1_000, 4_000, false, false, emptyList())
        override suspend fun uploadToPhotos(upload: PhotoUpload) = error("Not used")
        override suspend fun uploadToDrive(path: String, sizeBytes: Long, source: () -> InputStream) = error("Not used")
        override suspend fun streamDriveItem(item: DriveItem): InputStream = error("Not used")
        override fun isConfigured() = true
        override fun logout() = Unit
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

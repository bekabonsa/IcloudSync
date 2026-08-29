package dev.bex.icloudsync.sync

import dev.bex.icloudsync.data.local.SyncDao
import dev.bex.icloudsync.data.model.*
import dev.bex.icloudsync.icloud.DriveItem
import dev.bex.icloudsync.icloud.ICloudGateway
import dev.bex.icloudsync.icloud.RemoteAsset
import dev.bex.icloudsync.media.MediaRepository
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class ReconciliationProgress(val processed: Long, val total: Long, val bytes: Long)

@Singleton
class ReconciliationEngine @Inject constructor(
    private val dao: SyncDao,
    private val gateway: ICloudGateway,
    private val mediaRepository: MediaRepository,
) {
    suspend fun full(progress: suspend (ReconciliationProgress) -> Unit = {}) {
        var cursor = dao.cursor()
        if (cursor == null || cursor.fullReconciliationComplete) {
            dao.clearRemoteAssets()
            cursor = SyncCursorEntity(
                zone = PRIMARY_ZONE,
                token = null,
                fullReconciliationComplete = false,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            dao.upsertCursor(cursor)
        }
        val normalCount = gateway.countPhotos(false)
        val hiddenCount = gateway.countPhotos(true)
        val driveItems = gateway.listFallbackDriveItems().sortedBy { it.path }
        val total = normalCount + hiddenCount + driveItems.size
        var processed = cursor.remoteProcessed
        var bytes = cursor.remoteBytesProcessed
        var token = cursor.token
        var phase = cursor.reconciliationPhase
        var continuation = cursor.continuationMarker
        var offset = cursor.pageOffset

        suspend fun scanPhotos(hidden: Boolean, currentPhase: String, nextPhase: String) {
            if (phase != currentPhase) return
            do {
                val page = gateway.listPhotos(continuation, hidden)
                page.assets.drop(offset).forEachIndexed { relativeIndex, asset ->
                    persistPhoto(asset)
                    processed++
                    bytes += asset.sizeBytes
                    token = page.syncToken ?: token
                    offset += 1
                    checkpoint(processed, total, bytes, token, currentPhase, continuation, offset, complete = false)
                    progress(ReconciliationProgress(processed, total, bytes))
                }
                continuation = page.continuationMarker
                offset = 0
                token = page.syncToken ?: token
                checkpoint(processed, total, bytes, token, currentPhase, continuation, offset, complete = false)
            } while (continuation != null)
            phase = nextPhase
            checkpoint(processed, total, bytes, token, phase, null, 0, complete = false)
        }

        scanPhotos(hidden = false, currentPhase = PHASE_NORMAL, nextPhase = PHASE_HIDDEN)
        continuation = if (phase == PHASE_HIDDEN) cursor.takeIf { it.reconciliationPhase == PHASE_HIDDEN }?.continuationMarker else null
        offset = if (phase == PHASE_HIDDEN) cursor.takeIf { it.reconciliationPhase == PHASE_HIDDEN }?.pageOffset ?: 0 else 0
        scanPhotos(hidden = true, currentPhase = PHASE_HIDDEN, nextPhase = PHASE_DRIVE)

        if (phase == PHASE_DRIVE) {
            val startIndex = if (cursor.reconciliationPhase == PHASE_DRIVE) cursor.pageOffset else 0
            driveItems.drop(startIndex).forEachIndexed { relativeIndex, item ->
                persistDrive(item)
                processed++
                bytes += item.sizeBytes
                offset = startIndex + relativeIndex + 1
                checkpoint(processed, total, bytes, token, PHASE_DRIVE, null, offset, complete = false)
                progress(ReconciliationProgress(processed, total, bytes))
            }
            phase = PHASE_CHANGES
            offset = 0
            checkpoint(processed, total, bytes, token, phase, null, 0, complete = false)
        }

        if (phase == PHASE_CHANGES && token != null) {
            while (true) {
                val currentToken = token ?: break
                val changes = gateway.listChanges(currentToken)
                changes.assets.forEach { persistPhoto(it) }
                val next = changes.syncToken
                if (changes.assets.isEmpty() || next.isNullOrBlank() || next == token) break
                token = next
                checkpoint(processed, total, bytes, token, PHASE_CHANGES, null, 0, complete = false)
            }
        }
        matchLocalMedia()
        checkpoint(processed, total, bytes, token, PHASE_COMPLETE, null, 0, complete = true)
    }

    suspend fun incremental() {
        val original = dao.cursor() ?: return
        var token = original.token ?: return
        while (true) {
            val page = gateway.listChanges(token)
            page.assets.forEach { persistPhoto(it) }
            val next = page.syncToken
            dao.upsertCursor(original.copy(token = next ?: token, updatedAtEpochMs = System.currentTimeMillis()))
            if (page.assets.isEmpty() || next.isNullOrBlank() || next == token) break
            token = next
        }
        matchLocalMedia()
    }

    suspend fun hashLocalMedia() {
        dao.currentMedia().filter { it.backupState != BackupState.EXCLUDED }.forEach { media ->
            if (media.sha256 == null) {
                dao.updateLocalState(media.localId, BackupState.HASHING)
                try {
                    val hash = mediaRepository.sha256(media)
                    dao.updateLocalHash(media.localId, hash, BackupState.PENDING)
                } catch (_: Exception) {
                    dao.updateLocalState(media.localId, BackupState.FAILED, "File is no longer readable")
                }
            }
        }
    }

    suspend fun matchLocalMedia() {
        dao.currentMedia().filter { it.backupState != BackupState.EXCLUDED }.forEach { media ->
            val hash = media.sha256 ?: return@forEach
            val remote = dao.remoteByHash(hash)
            if (remote != null && !remote.deleted) {
                val state = if (remote.destination == DESTINATION_DRIVE) BackupState.SYNCED_DRIVE else BackupState.SYNCED_PHOTOS
                dao.updateLocalState(media.localId, state)
                dao.upsertBackupRecord(
                    BackupRecordEntity(
                        localId = media.localId,
                        contentHash = hash,
                        state = state,
                        destination = remote.destination,
                        remoteId = remote.masterId,
                        remotePath = null,
                        verifiedAtEpochMs = System.currentTimeMillis(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            } else {
                val previous = dao.backupRecord(media.localId, hash)
                val mappedRemoteStillExists = previous?.remoteId?.let { dao.remoteById(it) } != null
                if (
                    media.backupState in setOf(BackupState.SYNCED_PHOTOS, BackupState.SYNCED_DRIVE) &&
                    previous?.remoteId != null &&
                    !mappedRemoteStillExists
                ) {
                    dao.updateLocalState(media.localId, BackupState.REMOTE_REMOVED, "Removed from iCloud")
                    dao.upsertBackupRecord(
                        previous.copy(state = BackupState.REMOTE_REMOVED, updatedAtEpochMs = System.currentTimeMillis()),
                    )
                } else if (media.backupState in setOf(BackupState.DISCOVERED, BackupState.HASHING, BackupState.VERIFYING)) {
                    dao.updateLocalState(media.localId, BackupState.PENDING)
                }
            }
        }
    }

    private suspend fun persistPhoto(asset: RemoteAsset) {
        if (asset.deleted) {
            (dao.remoteById(asset.masterId) ?: dao.remoteByAssetId(asset.masterId))?.let {
                dao.upsertRemoteAsset(it.copy(deleted = true, lastSeenAtEpochMs = System.currentTimeMillis()))
                dao.recordsForRemote(it.masterId).forEach { record ->
                    dao.updateLocalState(record.localId, BackupState.REMOTE_REMOVED, "Removed from iCloud")
                    dao.upsertBackupRecord(record.copy(state = BackupState.REMOTE_REMOVED, updatedAtEpochMs = System.currentTimeMillis()))
                }
            }
            return
        }
        val hash = comparableSha256(asset.providerChecksum) ?: gateway.streamRemoteOriginal(asset).use(::sha256)
        dao.upsertRemoteAsset(
            RemoteAssetEntity(
                masterId = asset.masterId,
                assetId = asset.assetId,
                destination = DESTINATION_PHOTOS,
                filename = asset.filename,
                mediaKind = asset.mediaKind,
                sizeBytes = asset.sizeBytes,
                capturedAtEpochMs = asset.capturedAtEpochMs,
                providerChecksum = asset.providerChecksum,
                sha256 = hash,
                hidden = asset.hidden,
                deleted = false,
                lastSeenAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistDrive(item: DriveItem) {
        val hash = gateway.streamDriveItem(item).use(::sha256)
        dao.upsertRemoteAsset(
            RemoteAssetEntity(
                masterId = "drive:${item.driveId}",
                assetId = null,
                destination = DESTINATION_DRIVE,
                filename = item.path.substringAfterLast('/'),
                mediaKind = MediaKind.IMAGE,
                sizeBytes = item.sizeBytes,
                capturedAtEpochMs = 0,
                providerChecksum = null,
                sha256 = hash,
                hidden = false,
                deleted = false,
                lastSeenAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun checkpoint(
        processed: Long,
        total: Long,
        bytes: Long,
        token: String?,
        phase: String,
        continuation: String?,
        pageOffset: Int,
        complete: Boolean,
    ) {
        dao.upsertCursor(
            SyncCursorEntity(
                zone = PRIMARY_ZONE,
                token = token,
                fullReconciliationComplete = complete,
                remoteProcessed = processed,
                remoteTotal = total,
                remoteBytesProcessed = bytes,
                reconciliationPhase = phase,
                continuationMarker = continuation,
                pageOffset = pageOffset,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PRIMARY_ZONE = "PrimarySync"
        const val DESTINATION_PHOTOS = "PHOTOS"
        const val DESTINATION_DRIVE = "DRIVE"
        private const val PHASE_NORMAL = "NORMAL"
        private const val PHASE_HIDDEN = "HIDDEN"
        private const val PHASE_DRIVE = "DRIVE"
        private const val PHASE_CHANGES = "CHANGES"
        private const val PHASE_COMPLETE = "COMPLETE"

        internal fun comparableSha256(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
            val digest = when {
                decoded.size == 32 -> decoded
                decoded.size == 33 && decoded.first() == 1.toByte() -> decoded.copyOfRange(1, decoded.size)
                else -> return null
            }
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

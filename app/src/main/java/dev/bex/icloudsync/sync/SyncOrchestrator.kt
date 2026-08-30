package dev.bex.icloudsync.sync

import dev.bex.icloudsync.data.local.SyncDao
import dev.bex.icloudsync.data.model.*
import dev.bex.icloudsync.icloud.*
import dev.bex.icloudsync.media.MediaAccess
import dev.bex.icloudsync.media.MediaRepository
import dev.bex.icloudsync.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncOutcome {
    data object Complete : SyncOutcome()
    data object Paused : SyncOutcome()
    data object AuthRequired : SyncOutcome()
    data object StorageFull : SyncOutcome()
    data class Retry(val afterSeconds: Long? = null) : SyncOutcome()
    data class SafeStopped(val reason: String) : SyncOutcome()
}

@Singleton
class SyncOrchestrator @Inject constructor(
    private val dao: SyncDao,
    private val gateway: ICloudGateway,
    private val mediaRepository: MediaRepository,
    private val reconciliation: ReconciliationEngine,
    private val settings: AppSettings,
) {
    private val runMutex = Mutex()

    suspend fun run(progress: suspend (String) -> Unit = {}): SyncOutcome =
        runMutex.withLock { runInternal(progress) }

    private suspend fun runInternal(progress: suspend (String) -> Unit): SyncOutcome {
        if (settings.state.first().paused) return SyncOutcome.Paused
        if (mediaRepository.access() != MediaAccess.FULL) {
            dao.currentMedia().filter { it.backupState != BackupState.EXCLUDED }.forEach {
                dao.updateLocalState(it.localId, BackupState.BLOCKED_PERMISSION, "Full library access is required")
            }
            return SyncOutcome.Paused
        }
        if (!gateway.isConfigured()) return SyncOutcome.AuthRequired
        val runId = dao.startRun(SyncRunEntity(startedAtEpochMs = System.currentTimeMillis(), stage = SyncStage.INDEXING))
        var itemCount = 0L
        var byteCount = 0L
        try {
            when (gateway.validateOrRefresh()) {
                AuthResult.RequiresTwoFactor -> return finishAuth(runId)
                AuthResult.Authenticated -> Unit
            }
            progress("Indexing phone library")
            val configured = settings.state.first()
            mediaRepository.scan(configured.excludedFolders)
            dao.recoverInterruptedStates()
            reconciliation.hashLocalMedia()
            val cursor = dao.cursor()
            if (cursor?.fullReconciliationComplete != true) {
                dao.updateRunStage(runId, SyncStage.RECONCILING)
                progress("Reconciling existing iCloud library")
                reconciliation.full { status -> progress("Reconciling ${status.processed} of ${status.total}") }
            } else {
                dao.updateRunStage(runId, SyncStage.RECONCILING)
                progress("Checking iCloud changes")
                reconciliation.incremental()
            }
            reconciliation.matchLocalMedia()
            if (!configured.stagedPhotosUploadMigrationComplete) {
                val retried = dao.retryRetiredPhotoUploadFailures()
                settings.setStagedPhotosUploadMigrationComplete(true)
                if (retried > 0) progress("Retrying $retried items with the updated Photos uploader")
            }
            dao.updateRunStage(runId, SyncStage.UPLOADING)

            for (media in dao.uploadCandidates()) {
                val hash = media.sha256 ?: continue
                if (dao.remoteByHash(hash) != null) continue
                progress("Uploading ${media.displayName}")
                dao.updateLocalState(media.localId, BackupState.UPLOADING)
                var state = BackupState.PENDING
                var destination = ReconciliationEngine.DESTINATION_PHOTOS
                var remoteId = ""
                var remotePath: String? = null
                try {
                    try {
                        mediaRepository.open(media).close()
                    } catch (_: Exception) {
                        dao.updateLocalState(media.localId, BackupState.FAILED, "File is no longer readable")
                        continue
                    }
                    val photoResult = try {
                        gateway.uploadToPhotos(
                            PhotoUpload(
                                filename = media.displayName,
                                mimeType = media.mimeType,
                                mediaKind = media.mediaKind,
                                sizeBytes = media.sizeBytes,
                                width = media.width,
                                height = media.height,
                                capturedAtEpochMs = media.capturedAtEpochMs,
                                source = { mediaRepository.open(media) },
                            ),
                        )
                    } catch (_: ICloudException.UnsupportedType) {
                        null
                    }
                    if (photoResult != null) {
                        dao.updateLocalState(media.localId, BackupState.VERIFYING)
                        destination = ReconciliationEngine.DESTINATION_PHOTOS
                        state = BackupState.SYNCED_PHOTOS
                        remoteId = photoResult.masterId
                        persistUploadedRemote(media, hash, destination, photoResult.masterId, photoResult.assetId)
                    } else {
                        remotePath = fallbackDrivePath(media.displayName, media.capturedAtEpochMs, hash)
                        val driveResult = gateway.uploadToDrive(remotePath, media.sizeBytes) { mediaRepository.open(media) }
                        destination = ReconciliationEngine.DESTINATION_DRIVE
                        state = BackupState.SYNCED_DRIVE
                        remoteId = "drive:${driveResult.driveId}"
                        persistUploadedRemote(media, hash, destination, remoteId, null)
                    }
                } catch (_: ICloudException.UnsupportedType) {
                    val message = "iCloud Drive does not support this file"
                    dao.updateLocalState(media.localId, BackupState.FAILED, message)
                    dao.upsertBackupRecord(
                        BackupRecordEntity(
                            localId = media.localId,
                            contentHash = hash,
                            state = BackupState.FAILED,
                            destination = null,
                            remoteId = null,
                            remotePath = remotePath,
                            lastError = message,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    continue
                } catch (error: ICloudException.Permanent) {
                    dao.updateLocalState(media.localId, BackupState.FAILED, error.message?.take(160))
                    dao.upsertBackupRecord(
                        BackupRecordEntity(
                            localId = media.localId,
                            contentHash = hash,
                            state = BackupState.FAILED,
                            destination = null,
                            remoteId = null,
                            remotePath = remotePath,
                            lastError = error.message?.take(160),
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    continue
                } catch (error: ICloudException.QuotaExceeded) {
                    dao.updateLocalState(media.localId, BackupState.BLOCKED_QUOTA, "iCloud storage is full")
                    dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PAUSED, itemCount, byteCount, "iCloud storage is full")
                    return SyncOutcome.StorageFull
                } catch (error: ICloudException.Authentication) {
                    dao.updateLocalState(media.localId, BackupState.BLOCKED_AUTH, "Apple ID verification required")
                    throw error
                } catch (error: ICloudException.RateLimited) {
                    dao.updateLocalState(media.localId, BackupState.PENDING, "Rate limited; waiting to retry")
                    throw error
                } catch (error: ICloudException.Transient) {
                    dao.updateLocalState(media.localId, BackupState.PENDING, "Temporary upload error")
                    throw error
                } catch (error: ICloudException.Protocol) {
                    dao.updateLocalState(media.localId, BackupState.FAILED, "Protocol response was not understood")
                    throw error
                }
                dao.updateLocalState(media.localId, state)
                dao.upsertBackupRecord(
                    BackupRecordEntity(
                        localId = media.localId,
                        contentHash = hash,
                        state = state,
                        destination = destination,
                        remoteId = remoteId,
                        remotePath = remotePath,
                        verifiedAtEpochMs = System.currentTimeMillis(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                itemCount++
                byteCount += media.sizeBytes
            }
            val unresolved = dao.currentMedia().count {
                it.backupState !in setOf(BackupState.SYNCED_PHOTOS, BackupState.SYNCED_DRIVE, BackupState.EXCLUDED)
            }
            if (unresolved > 0) {
                dao.finishRun(
                    runId,
                    System.currentTimeMillis(),
                    SyncStage.PAUSED,
                    itemCount,
                    byteCount,
                    "$unresolved item(s) require attention",
                )
                return SyncOutcome.Paused
            }
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.IDLE, itemCount, byteCount, null)
            return SyncOutcome.Complete
        } catch (_: ICloudException.TwoFactorRequired) {
            return finishAuth(runId)
        } catch (_: ICloudException.Authentication) {
            return finishAuth(runId)
        } catch (error: ICloudException.RateLimited) {
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PAUSED, itemCount, byteCount, "Rate limited")
            return SyncOutcome.Retry(error.retryAfterSeconds)
        } catch (_: ICloudException.Transient) {
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PAUSED, itemCount, byteCount, "Temporary network error")
            return SyncOutcome.Retry()
        } catch (error: ICloudException.QuotaExceeded) {
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PAUSED, itemCount, byteCount, "iCloud storage is full")
            return SyncOutcome.StorageFull
        } catch (error: ICloudException.Permanent) {
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PROTOCOL_STOPPED, itemCount, byteCount, error.message)
            return SyncOutcome.SafeStopped(error.message ?: "Apple rejected a library request")
        } catch (error: ICloudException.Protocol) {
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PROTOCOL_STOPPED, itemCount, byteCount, error.message)
            return SyncOutcome.SafeStopped(error.message ?: "Apple protocol changed")
        } catch (error: Exception) {
            dao.finishRun(runId, System.currentTimeMillis(), SyncStage.PROTOCOL_STOPPED, itemCount, byteCount, "Unexpected sync response")
            return SyncOutcome.SafeStopped("Unexpected sync response; update or diagnostics review required")
        }
    }

    private suspend fun persistUploadedRemote(
        media: LocalMediaEntity,
        hash: String,
        destination: String,
        masterId: String,
        assetId: String?,
    ) {
        dao.upsertRemoteAsset(
            RemoteAssetEntity(
                masterId = masterId,
                assetId = assetId,
                destination = destination,
                filename = media.displayName,
                mediaKind = media.mediaKind,
                sizeBytes = media.sizeBytes,
                capturedAtEpochMs = media.capturedAtEpochMs,
                providerChecksum = null,
                sha256 = hash,
                hidden = false,
                deleted = false,
                lastSeenAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun finishAuth(runId: Long): SyncOutcome {
        dao.currentMedia().filter {
            it.backupState !in setOf(BackupState.SYNCED_PHOTOS, BackupState.SYNCED_DRIVE, BackupState.EXCLUDED)
        }.forEach { dao.updateLocalState(it.localId, BackupState.BLOCKED_AUTH, "Apple ID verification required") }
        dao.finishRun(runId, System.currentTimeMillis(), SyncStage.AUTH_REQUIRED, 0, 0, "Apple ID verification required")
        return SyncOutcome.AuthRequired
    }
}

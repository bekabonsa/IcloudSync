package dev.bex.icloudsync.data.local

import androidx.room.*
import dev.bex.icloudsync.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Query("SELECT * FROM local_media WHERE present = 1 ORDER BY capturedAtEpochMs DESC")
    fun observeCurrentMedia(): Flow<List<LocalMediaEntity>>

    @Query("SELECT * FROM local_media WHERE present = 1 AND backupState = :state ORDER BY capturedAtEpochMs DESC")
    fun observeMediaByState(state: BackupState): Flow<List<LocalMediaEntity>>

    @Query("SELECT * FROM local_media WHERE localId = :localId")
    suspend fun localMedia(localId: String): LocalMediaEntity?

    @Query("SELECT * FROM local_media WHERE present = 1 ORDER BY capturedAtEpochMs ASC")
    suspend fun currentMedia(): List<LocalMediaEntity>

    @Query("SELECT * FROM local_media WHERE present = 1 AND backupState IN ('DISCOVERED', 'HASHING', 'PENDING', 'BLOCKED_AUTH', 'BLOCKED_PERMISSION', 'BLOCKED_QUOTA') ORDER BY capturedAtEpochMs ASC")
    suspend fun uploadCandidates(): List<LocalMediaEntity>

    @Upsert
    suspend fun upsertLocalMedia(items: List<LocalMediaEntity>)

    @Query("UPDATE local_media SET present = 0 WHERE lastSeenAtEpochMs < :scanStarted")
    suspend fun markMissing(scanStarted: Long)

    @Query("UPDATE local_media SET sha256 = :hash, backupState = :state, lastError = NULL WHERE localId = :localId")
    suspend fun updateLocalHash(localId: String, hash: String, state: BackupState)

    @Query("UPDATE local_media SET backupState = :state, lastError = :error WHERE localId = :localId")
    suspend fun updateLocalState(localId: String, state: BackupState, error: String? = null)

    @Query("UPDATE local_media SET backupState = CASE WHEN sha256 IS NULL THEN 'DISCOVERED' ELSE 'PENDING' END, lastError = NULL WHERE present = 1 AND backupState IN ('HASHING', 'UPLOADING', 'VERIFYING')")
    suspend fun recoverInterruptedStates()

    @Query("SELECT * FROM remote_assets WHERE sha256 = :hash AND deleted = 0 LIMIT 1")
    suspend fun remoteByHash(hash: String): RemoteAssetEntity?

    @Query("SELECT * FROM remote_assets WHERE masterId = :masterId")
    suspend fun remoteById(masterId: String): RemoteAssetEntity?

    @Query("SELECT * FROM remote_assets WHERE assetId = :assetId LIMIT 1")
    suspend fun remoteByAssetId(assetId: String): RemoteAssetEntity?

    @Upsert
    suspend fun upsertRemoteAsset(item: RemoteAssetEntity)

    @Query("UPDATE remote_assets SET deleted = 1 WHERE masterId = :masterId")
    suspend fun markRemoteDeleted(masterId: String)

    @Upsert
    suspend fun upsertBackupRecord(item: BackupRecordEntity)

    @Query("SELECT * FROM backup_records WHERE localId = :localId AND contentHash = :hash LIMIT 1")
    suspend fun backupRecord(localId: String, hash: String): BackupRecordEntity?

    @Query("SELECT * FROM backup_records WHERE remoteId = :remoteId")
    suspend fun recordsForRemote(remoteId: String): List<BackupRecordEntity>

    @Query("SELECT * FROM sync_cursors WHERE zone = :zone")
    suspend fun cursor(zone: String = "PrimarySync"): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursors WHERE zone = :zone")
    fun observeCursor(zone: String = "PrimarySync"): Flow<SyncCursorEntity?>

    @Upsert
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Insert
    suspend fun startRun(run: SyncRunEntity): Long

    @Query("UPDATE sync_runs SET finishedAtEpochMs = :finishedAt, stage = :stage, itemsProcessed = :items, bytesProcessed = :bytes, errorSummary = :error WHERE id = :id")
    suspend fun finishRun(id: Long, finishedAt: Long, stage: SyncStage, items: Long, bytes: Long, error: String?)

    @Query("UPDATE sync_runs SET stage = :stage WHERE id = :id")
    suspend fun updateRunStage(id: Long, stage: SyncStage)

    @Query("SELECT * FROM sync_runs ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeLatestRun(): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs WHERE stage = 'IDLE' AND finishedAtEpochMs IS NOT NULL ORDER BY finishedAtEpochMs DESC LIMIT 1")
    fun observeLastSuccessfulRun(): Flow<SyncRunEntity?>

    @Query("DELETE FROM remote_assets")
    suspend fun clearRemoteAssets()

    @Query("DELETE FROM sync_cursors")
    suspend fun clearCursors()

    @Query("DELETE FROM backup_records")
    suspend fun clearBackupRecords()

    @Query("DELETE FROM local_media")
    suspend fun clearLocalMedia()

    @Query("DELETE FROM sync_runs")
    suspend fun clearSyncRuns()
}

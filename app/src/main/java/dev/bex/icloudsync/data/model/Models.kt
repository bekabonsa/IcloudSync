package dev.bex.icloudsync.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class MediaKind { IMAGE, VIDEO }

enum class BackupState {
    DISCOVERED,
    HASHING,
    PENDING,
    UPLOADING,
    VERIFYING,
    SYNCED_PHOTOS,
    SYNCED_DRIVE,
    REMOTE_REMOVED,
    BLOCKED_AUTH,
    BLOCKED_PERMISSION,
    BLOCKED_QUOTA,
    FAILED,
    EXCLUDED,
}

enum class SyncStage { IDLE, INDEXING, RECONCILING, UPLOADING, PAUSED, AUTH_REQUIRED, PROTOCOL_STOPPED }

@Entity(
    tableName = "local_media",
    indices = [Index("sha256"), Index("present"), Index("backupState")],
)
data class LocalMediaEntity(
    @PrimaryKey val localId: String,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val mediaKind: MediaKind,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val capturedAtEpochMs: Long,
    val relativePath: String,
    val generationModified: Long,
    val sha256: String? = null,
    val present: Boolean = true,
    val backupState: BackupState = BackupState.DISCOVERED,
    val lastError: String? = null,
    val lastSeenAtEpochMs: Long,
)

@Entity(
    tableName = "remote_assets",
    indices = [Index("sha256"), Index("assetId")],
)
data class RemoteAssetEntity(
    @PrimaryKey val masterId: String,
    val assetId: String?,
    val destination: String,
    val filename: String,
    val mediaKind: MediaKind,
    val sizeBytes: Long,
    val capturedAtEpochMs: Long,
    val providerChecksum: String?,
    val sha256: String?,
    val hidden: Boolean,
    val deleted: Boolean,
    val lastSeenAtEpochMs: Long,
)

@Entity(
    tableName = "backup_records",
    primaryKeys = ["localId", "contentHash"],
    indices = [Index("remoteId"), Index("state")],
)
data class BackupRecordEntity(
    val localId: String,
    val contentHash: String,
    val state: BackupState,
    val destination: String?,
    val remoteId: String?,
    val remotePath: String?,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val verifiedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val zone: String,
    val token: String?,
    val fullReconciliationComplete: Boolean,
    val remoteProcessed: Long = 0,
    val remoteTotal: Long = 0,
    val remoteBytesProcessed: Long = 0,
    val reconciliationPhase: String = "NORMAL",
    val continuationMarker: String? = null,
    val pageOffset: Int = 0,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "sync_runs")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val stage: SyncStage,
    val itemsProcessed: Long = 0,
    val bytesProcessed: Long = 0,
    val errorSummary: String? = null,
)

data class DashboardStats(
    val totalItems: Long,
    val totalBytes: Long,
    val protectedItems: Long,
    val protectedBytes: Long,
    val photosItems: Long,
    val driveItems: Long,
    val pendingItems: Long,
    val failedItems: Long,
)

@Serializable
data class CookieData(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

@Serializable
data class AccountSecrets(
    val appleId: String,
    val password: String,
    val clientId: String,
    val sessionToken: String = "",
    val trustToken: String = "",
    val sessionId: String = "",
    val scnt: String = "",
    val authAttributes: String = "",
    val accountCountry: String = "",
    val dsid: String = "",
    val requiresTwoFactor: Boolean = false,
    val webservices: Map<String, String> = emptyMap(),
    val cookies: List<CookieData> = emptyList(),
)

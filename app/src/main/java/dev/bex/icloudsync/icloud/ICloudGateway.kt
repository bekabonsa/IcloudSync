package dev.bex.icloudsync.icloud

import dev.bex.icloudsync.data.model.MediaKind
import java.io.InputStream

sealed class AuthResult {
    data object Authenticated : AuthResult()
    data object RequiresTwoFactor : AuthResult()
}

data class RemoteAsset(
    val masterId: String,
    val assetId: String?,
    val filename: String,
    val mediaKind: MediaKind,
    val sizeBytes: Long,
    val capturedAtEpochMs: Long,
    val providerChecksum: String?,
    val downloadUrl: String?,
    val hidden: Boolean,
    val deleted: Boolean,
)

data class RemotePage(
    val assets: List<RemoteAsset>,
    val continuationMarker: String?,
    val syncToken: String?,
)

data class UploadResult(
    val masterId: String,
    val assetId: String?,
    val duplicate: Boolean,
)

data class PhotoUpload(
    val filename: String,
    val mimeType: String,
    val mediaKind: MediaKind,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val capturedAtEpochMs: Long,
    val source: () -> InputStream,
)

data class DriveItem(
    val driveId: String,
    val path: String,
    val sizeBytes: Long,
    val downloadUrl: String?,
)

data class ICloudStorageCategory(
    val key: String,
    val label: String,
    val usageBytes: Long,
    val displayColor: String?,
)

data class ICloudStorageUsage(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val overQuota: Boolean,
    val almostFull: Boolean,
    val categories: List<ICloudStorageCategory>,
)

sealed class ICloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Authentication(message: String) : ICloudException(message)
    class TwoFactorRequired : ICloudException("Two-factor authentication is required")
    class UnsupportedType : ICloudException("Apple Photos rejected this media type")
    class QuotaExceeded : ICloudException("iCloud storage is full")
    class RateLimited(val retryAfterSeconds: Long?) : ICloudException("Apple temporarily rate-limited requests")
    class Transient(message: String, cause: Throwable? = null) : ICloudException(message, cause)
    class Permanent(message: String) : ICloudException(message)
    class Protocol(message: String, cause: Throwable? = null) : ICloudException(message, cause)
}

interface ICloudGateway {
    suspend fun signIn(appleId: String, password: String): AuthResult
    suspend fun requestTwoFactorCode(): Boolean
    suspend fun verifyTwoFactor(code: String): AuthResult
    suspend fun validateOrRefresh(): AuthResult
    suspend fun storageUsage(): ICloudStorageUsage
    suspend fun countPhotos(includeHidden: Boolean = false): Long
    suspend fun listPhotos(continuation: String? = null, hidden: Boolean = false): RemotePage
    suspend fun listChanges(syncToken: String): RemotePage
    suspend fun streamRemoteOriginal(asset: RemoteAsset): InputStream
    suspend fun uploadToPhotos(upload: PhotoUpload): UploadResult
    suspend fun listFallbackDriveItems(): List<DriveItem>
    suspend fun uploadToDrive(path: String, sizeBytes: Long, source: () -> InputStream): DriveItem
    suspend fun streamDriveItem(item: DriveItem): InputStream
    fun isConfigured(): Boolean
    fun logout()
}

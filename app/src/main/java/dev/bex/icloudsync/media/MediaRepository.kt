package dev.bex.icloudsync.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.bex.icloudsync.data.local.SyncDao
import dev.bex.icloudsync.data.model.BackupState
import dev.bex.icloudsync.data.model.LocalMediaEntity
import dev.bex.icloudsync.data.model.MediaKind
import dev.bex.icloudsync.sync.contentMetadataUnchanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class MediaAccess { FULL, PARTIAL, DENIED }

data class ScanResult(val found: Int, val excluded: Int, val access: MediaAccess)

interface MediaRepository {
    fun access(): MediaAccess
    suspend fun scan(excludedFolders: Set<String>): ScanResult
    suspend fun sha256(media: LocalMediaEntity): String
    fun open(media: LocalMediaEntity): InputStream
}

@Singleton
class AndroidMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SyncDao,
) : MediaRepository {
    override fun access(): MediaAccess {
        if (Build.VERSION.SDK_INT >= 33) {
            val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
            val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
            if (images && videos) return MediaAccess.FULL
            if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
                return MediaAccess.PARTIAL
            }
            return MediaAccess.DENIED
        }
        return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.DENIED
    }

    override suspend fun scan(excludedFolders: Set<String>): ScanResult = withContext(Dispatchers.IO) {
        val permission = access()
        if (permission != MediaAccess.FULL) return@withContext ScanResult(0, 0, permission)

        val startedAt = System.currentTimeMillis()
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.MediaColumns.VOLUME_NAME)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }.toTypedArray()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val discovered = mutableListOf<LocalMediaEntity>()
        var excluded = 0
        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val generationColumn = if (Build.VERSION.SDK_INT >= 30) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val volume = cursor.getString(volumeColumn) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val relativePath = cursor.getString(pathColumn).orEmpty()
                val isExcluded = excludedFolders.any { excludedPath ->
                    relativePath.startsWith(excludedPath, ignoreCase = true)
                }
                if (isExcluded) excluded++
                val localId = "$volume:$id"
                val previous = dao.localMedia(localId)
                val size = cursor.getLong(sizeColumn)
                val generation = if (generationColumn >= 0) cursor.getLong(generationColumn) else {
                    cursor.getLong(addedColumn)
                }
                val unchanged = contentMetadataUnchanged(previous, size, generation)
                val state = when {
                    isExcluded -> BackupState.EXCLUDED
                    unchanged && previous?.backupState == BackupState.EXCLUDED ->
                        if (previous.sha256 == null) BackupState.DISCOVERED else BackupState.PENDING
                    unchanged -> previous?.backupState ?: BackupState.DISCOVERED
                    else -> BackupState.DISCOVERED
                }
                val base = MediaStore.Files.getContentUri(volume)
                discovered += LocalMediaEntity(
                    localId = localId,
                    contentUri = ContentUris.withAppendedId(base, id).toString(),
                    displayName = cursor.getString(nameColumn) ?: "media_$id",
                    mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                    mediaKind = if (cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                        MediaKind.VIDEO
                    } else MediaKind.IMAGE,
                    sizeBytes = size,
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    capturedAtEpochMs = cursor.getLong(takenColumn).takeIf { it > 0 }
                        ?: cursor.getLong(addedColumn) * 1_000,
                    relativePath = relativePath,
                    generationModified = generation,
                    sha256 = if (unchanged) previous?.sha256 else null,
                    present = true,
                    backupState = state,
                    lastError = if (unchanged) previous?.lastError else null,
                    lastSeenAtEpochMs = startedAt,
                )
            }
        }
        discovered.chunked(250).forEach { dao.upsertLocalMedia(it) }
        dao.markMissing(startedAt)
        ScanResult(discovered.size, excluded, permission)
    }

    override suspend fun sha256(media: LocalMediaEntity): String = withContext(Dispatchers.IO) {
        open(media).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    override fun open(media: LocalMediaEntity): InputStream =
        context.contentResolver.openInputStream(Uri.parse(media.contentUri))
            ?: error("Media is no longer readable")

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

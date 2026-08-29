package dev.bex.icloudsync.sync

import dev.bex.icloudsync.data.model.BackupState
import dev.bex.icloudsync.data.model.LocalMediaEntity
import java.time.Instant
import java.time.ZoneOffset

data class BackupProgress(
    val totalItems: Int,
    val protectedItems: Int,
    val totalBytes: Long,
    val protectedBytes: Long,
) {
    val fraction: Float
        get() = if (totalItems == 0) 0f else protectedItems.toFloat() / totalItems
}

fun calculateBackupProgress(media: List<LocalMediaEntity>): BackupProgress {
    val included = media.filter { it.present && it.backupState != BackupState.EXCLUDED }
    val protected = included.filter {
        it.backupState == BackupState.SYNCED_PHOTOS || it.backupState == BackupState.SYNCED_DRIVE
    }
    return BackupProgress(
        totalItems = included.size,
        protectedItems = protected.size,
        totalBytes = included.sumOf { it.sizeBytes },
        protectedBytes = protected.sumOf { it.sizeBytes },
    )
}

fun contentMetadataUnchanged(previous: LocalMediaEntity?, sizeBytes: Long, generationModified: Long): Boolean =
    previous != null && previous.sizeBytes == sizeBytes && previous.generationModified == generationModified

fun fallbackDrivePath(displayName: String, capturedAtEpochMs: Long, sha256: String): String {
    val date = Instant.ofEpochMilli(capturedAtEpochMs).atZone(ZoneOffset.UTC)
    val rawStem = displayName.substringBeforeLast('.', displayName)
    val stem = rawStem.replace(Regex("[\\u0000/\\\\]"), "_").take(120).ifBlank { "media" }
    val extension = displayName.substringAfterLast('.', "bin")
        .replace(Regex("[^A-Za-z0-9]"), "")
        .take(12)
        .ifBlank { "bin" }
    return "IcloudSync/Unsupported/%04d/%02d/%s_%s.%s".format(
        date.year,
        date.monthValue,
        stem,
        sha256.lowercase().take(10),
        extension,
    )
}

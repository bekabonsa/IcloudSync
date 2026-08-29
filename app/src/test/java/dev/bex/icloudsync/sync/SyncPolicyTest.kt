package dev.bex.icloudsync.sync

import dev.bex.icloudsync.data.model.BackupState
import dev.bex.icloudsync.data.model.LocalMediaEntity
import dev.bex.icloudsync.data.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun `progress counts only present included originals and both protected destinations`() {
        val media = listOf(
            item("photos", 100, BackupState.SYNCED_PHOTOS),
            item("drive", 200, BackupState.SYNCED_DRIVE),
            item("pending", 300, BackupState.PENDING),
            item("excluded", 900, BackupState.EXCLUDED),
            item("gone", 800, BackupState.SYNCED_PHOTOS, present = false),
        )

        val progress = calculateBackupProgress(media)

        assertEquals(3, progress.totalItems)
        assertEquals(2, progress.protectedItems)
        assertEquals(600, progress.totalBytes)
        assertEquals(300, progress.protectedBytes)
        assertEquals(2f / 3f, progress.fraction, 0.0001f)
    }

    @Test
    fun `changed size or MediaStore generation invalidates the old hash`() {
        val previous = item("same", 100, BackupState.SYNCED_PHOTOS).copy(generationModified = 7)

        assertTrue(contentMetadataUnchanged(previous, 100, 7))
        assertFalse(contentMetadataUnchanged(previous, 101, 7))
        assertFalse(contentMetadataUnchanged(previous, 100, 8))
        assertFalse(contentMetadataUnchanged(null, 100, 7))
    }

    @Test
    fun `fallback path is deterministic dated and safe`() {
        val path = fallbackDrivePath(
            displayName = "holiday/clip.final.WEBP",
            capturedAtEpochMs = 1_704_067_200_000,
            sha256 = "ABCDEF1234567890",
        )

        assertEquals("IcloudSync/Unsupported/2024/01/holiday_clip.final_abcdef1234.WEBP", path)
    }

    private fun item(id: String, size: Long, state: BackupState, present: Boolean = true) = LocalMediaEntity(
        localId = id,
        contentUri = "content://test/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        mediaKind = MediaKind.IMAGE,
        sizeBytes = size,
        width = 1,
        height = 1,
        capturedAtEpochMs = 0,
        relativePath = "DCIM/",
        generationModified = 1,
        sha256 = "hash-$id",
        present = present,
        backupState = state,
        lastSeenAtEpochMs = 1,
    )
}

package dev.bex.icloudsync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.bex.icloudsync.data.model.*

class DatabaseConverters {
    @TypeConverter fun fromMediaKind(value: MediaKind): String = value.name
    @TypeConverter fun toMediaKind(value: String): MediaKind = MediaKind.valueOf(value)
    @TypeConverter fun fromBackupState(value: BackupState): String = value.name
    @TypeConverter fun toBackupState(value: String): BackupState = BackupState.valueOf(value)
    @TypeConverter fun fromSyncStage(value: SyncStage): String = value.name
    @TypeConverter fun toSyncStage(value: String): SyncStage = SyncStage.valueOf(value)
}

@Database(
    entities = [
        LocalMediaEntity::class,
        RemoteAssetEntity::class,
        BackupRecordEntity::class,
        SyncCursorEntity::class,
        SyncRunEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
}


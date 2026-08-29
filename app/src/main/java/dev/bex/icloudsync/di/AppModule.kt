package dev.bex.icloudsync.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.bex.icloudsync.data.local.AppDatabase
import dev.bex.icloudsync.data.local.SyncDao
import dev.bex.icloudsync.icloud.ICloudGateway
import dev.bex.icloudsync.icloud.UnofficialICloudGateway
import dev.bex.icloudsync.media.AndroidMediaRepository
import dev.bex.icloudsync.media.MediaRepository
import dev.bex.icloudsync.security.KeystoreSecretStore
import dev.bex.icloudsync.security.SecretStore
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "icloud-sync.db").build()

    @Provides
    fun dao(database: AppDatabase): SyncDao = database.syncDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds @Singleton abstract fun gateway(value: UnofficialICloudGateway): ICloudGateway
    @Binds @Singleton abstract fun media(value: AndroidMediaRepository): MediaRepository
    @Binds @Singleton abstract fun secrets(value: KeystoreSecretStore): SecretStore
}


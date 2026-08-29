package dev.bex.icloudsync.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("app_settings")

data class SettingsState(
    val onboardingComplete: Boolean = false,
    val paused: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val completionNotifications: Boolean = false,
    val excludedFolders: Set<String> = emptySet(),
)

@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {
    val state: Flow<SettingsState> = context.dataStore.data.map { prefs ->
        SettingsState(
            onboardingComplete = prefs[ONBOARDED] ?: false,
            paused = prefs[PAUSED] ?: false,
            wifiOnly = prefs[WIFI_ONLY] ?: true,
            chargingOnly = prefs[CHARGING_ONLY] ?: false,
            completionNotifications = prefs[COMPLETION_NOTIFICATIONS] ?: false,
            excludedFolders = prefs[EXCLUDED_FOLDERS] ?: emptySet(),
        )
    }

    suspend fun setOnboarded(value: Boolean) = edit(ONBOARDED, value)
    suspend fun setPaused(value: Boolean) = edit(PAUSED, value)
    suspend fun setWifiOnly(value: Boolean) = edit(WIFI_ONLY, value)
    suspend fun setChargingOnly(value: Boolean) = edit(CHARGING_ONLY, value)
    suspend fun setCompletionNotifications(value: Boolean) = edit(COMPLETION_NOTIFICATIONS, value)
    suspend fun setExcludedFolders(value: Set<String>) { context.dataStore.edit { it[EXCLUDED_FOLDERS] = value } }

    suspend fun reset() { context.dataStore.edit { it.clear() } }

    private suspend fun edit(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val PAUSED = booleanPreferencesKey("paused")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val COMPLETION_NOTIFICATIONS = booleanPreferencesKey("completion_notifications")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
    }
}

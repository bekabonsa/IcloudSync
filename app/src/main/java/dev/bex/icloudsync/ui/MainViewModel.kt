package dev.bex.icloudsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.bex.icloudsync.data.local.SyncDao
import dev.bex.icloudsync.data.model.*
import dev.bex.icloudsync.icloud.AuthResult
import dev.bex.icloudsync.icloud.ICloudGateway
import dev.bex.icloudsync.icloud.ICloudStorageUsage
import dev.bex.icloudsync.media.MediaAccess
import dev.bex.icloudsync.media.MediaRepository
import dev.bex.icloudsync.settings.AppSettings
import dev.bex.icloudsync.settings.SettingsState
import dev.bex.icloudsync.sync.SyncScheduler
import dev.bex.icloudsync.sync.calculateBackupProgress
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val settings: SettingsState = SettingsState(),
    val media: List<LocalMediaEntity> = emptyList(),
    val cursor: SyncCursorEntity? = null,
    val latestRun: SyncRunEntity? = null,
    val lastSuccessfulRun: SyncRunEntity? = null,
    val access: MediaAccess = MediaAccess.DENIED,
    val accountConfigured: Boolean = false,
    val authBusy: Boolean = false,
    val requiresTwoFactor: Boolean = false,
    val cloudStorage: ICloudStorageUsage? = null,
    val storageLoading: Boolean = false,
    val storageError: String? = null,
    val message: String? = null,
) {
    private val progress get() = calculateBackupProgress(media)
    val included: List<LocalMediaEntity> get() = media.filter { it.backupState != BackupState.EXCLUDED }
    val protected: List<LocalMediaEntity> get() = included.filter { it.backupState in setOf(BackupState.SYNCED_PHOTOS, BackupState.SYNCED_DRIVE) }
    val protectedFraction: Float get() = progress.fraction
    val totalBytes: Long get() = progress.totalBytes
    val protectedBytes: Long get() = progress.protectedBytes
    val authenticationNeedsAttention: Boolean
        get() = !accountConfigured || requiresTwoFactor || latestRun?.stage == SyncStage.AUTH_REQUIRED
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: SyncDao,
    private val gateway: ICloudGateway,
    private val mediaRepository: MediaRepository,
    private val settings: AppSettings,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    private val transient = MutableStateFlow(Transient())

    private val persisted = combine(
        settings.state,
        dao.observeCurrentMedia(),
        dao.observeCursor(),
        dao.observeLatestRun(),
    ) { configured, media, cursor, run -> Persisted(configured, media, cursor, run) }

    val state: StateFlow<MainUiState> = combine(
        persisted,
        dao.observeLastSuccessfulRun(),
        transient,
    ) { saved, lastSuccessful, local ->
        MainUiState(
            settings = saved.settings,
            media = saved.media,
            cursor = saved.cursor,
            latestRun = saved.latestRun,
            lastSuccessfulRun = lastSuccessful,
            access = local.access,
            accountConfigured = gateway.isConfigured(),
            authBusy = local.authBusy,
            requiresTwoFactor = local.requiresTwoFactor,
            cloudStorage = local.cloudStorage,
            storageLoading = local.storageLoading,
            storageError = local.storageError,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        refreshAccess()
        if (gateway.isConfigured()) refreshStorage()
    }

    fun refreshAccess() {
        transient.update { it.copy(access = mediaRepository.access()) }
    }

    fun signIn(appleId: String, password: String) = viewModelScope.launch {
        transient.update { it.copy(authBusy = true, message = null) }
        runCatching { gateway.signIn(appleId, password) }
            .onSuccess { result ->
                transient.update {
                    it.copy(
                        authBusy = false,
                        requiresTwoFactor = result == AuthResult.RequiresTwoFactor,
                        message = if (result == AuthResult.Authenticated) "Apple account connected" else "Verification requested. Apple does not send these codes by email.",
                    )
                }
                if (result == AuthResult.Authenticated) refreshStorage()
            }
            .onFailure { error -> transient.update { it.copy(authBusy = false, message = error.message ?: "Sign-in failed") } }
    }

    fun verifyTwoFactor(code: String) = viewModelScope.launch {
        transient.update { it.copy(authBusy = true, message = null) }
        runCatching { gateway.verifyTwoFactor(code) }
            .onSuccess { result ->
                transient.update {
                    it.copy(
                        authBusy = false,
                        requiresTwoFactor = result != AuthResult.Authenticated,
                        message = if (result == AuthResult.Authenticated) "Apple account verified" else "Apple still requires verification",
                    )
                }
                if (result == AuthResult.Authenticated && state.value.settings.onboardingComplete) {
                    scheduler.enqueueImmediate()
                }
                if (result == AuthResult.Authenticated) refreshStorage()
            }
            .onFailure { error -> transient.update { it.copy(authBusy = false, message = error.message ?: "Verification failed") } }
    }

    fun requestTwoFactorCode() = viewModelScope.launch {
        transient.update { it.copy(authBusy = true, message = null) }
        val requested = runCatching { gateway.requestTwoFactorCode() }.getOrDefault(false)
        transient.update {
            it.copy(
                authBusy = false,
                message = if (requested) {
                    "A trusted-device prompt was requested. If none appears, generate a code manually on your iPhone."
                } else {
                    "Apple did not confirm delivery. Generate a code manually on your iPhone and enter it here."
                },
            )
        }
    }

    fun refreshAuthentication() = viewModelScope.launch {
        transient.update { it.copy(authBusy = true, message = null) }
        runCatching { gateway.validateOrRefresh() }
            .onSuccess { result ->
                transient.update {
                    it.copy(
                        authBusy = false,
                        requiresTwoFactor = result == AuthResult.RequiresTwoFactor,
                        message = if (result == AuthResult.Authenticated) "Apple session refreshed" else null,
                    )
                }
                if (result == AuthResult.Authenticated) scheduler.enqueueImmediate()
                if (result == AuthResult.Authenticated) refreshStorage()
            }
            .onFailure { error ->
                transient.update { it.copy(authBusy = false, message = error.message ?: "Authentication refresh failed") }
            }
    }

    fun finishOnboarding() = viewModelScope.launch {
        refreshAccess()
        if (!gateway.isConfigured()) {
            transient.update { it.copy(message = "Connect your Apple account first") }
            return@launch
        }
        if (mediaRepository.access() != MediaAccess.FULL) {
            transient.update { it.copy(message = "Full photo and video access is required") }
            return@launch
        }
        settings.setOnboarded(true)
        scheduler.ensureScheduled()
        scheduler.enqueueImmediate()
    }

    fun onResume() = viewModelScope.launch {
        refreshAccess()
        if (gateway.isConfigured()) refreshStorage()
        if (state.value.settings.onboardingComplete) scheduler.enqueueImmediate()
    }

    fun syncNow() = viewModelScope.launch {
        scheduler.enqueueImmediate()
        if (gateway.isConfigured()) refreshStorage()
    }

    fun refreshStorage() = viewModelScope.launch {
        if (!gateway.isConfigured()) return@launch
        transient.update { it.copy(storageLoading = true, storageError = null) }
        runCatching { gateway.storageUsage() }
            .onSuccess { storage ->
                transient.update { it.copy(cloudStorage = storage, storageLoading = false, storageError = null) }
            }
            .onFailure { error ->
                transient.update {
                    it.copy(
                        storageLoading = false,
                        storageError = error.message ?: "Storage information is unavailable",
                    )
                }
            }
    }

    fun setPaused(value: Boolean) = viewModelScope.launch {
        settings.setPaused(value)
        if (value) scheduler.cancelSync() else { scheduler.ensureScheduled(); scheduler.enqueueImmediate() }
    }

    fun setWifiOnly(value: Boolean) = viewModelScope.launch {
        settings.setWifiOnly(value); scheduler.ensureScheduled()
    }

    fun setChargingOnly(value: Boolean) = viewModelScope.launch {
        settings.setChargingOnly(value); scheduler.ensureScheduled()
    }

    fun setCompletionNotifications(value: Boolean) = viewModelScope.launch {
        settings.setCompletionNotifications(value)
    }

    fun toggleFolder(path: String) = viewModelScope.launch {
        val folders = state.value.settings.excludedFolders.toMutableSet()
        if (!folders.add(path)) folders.remove(path)
        settings.setExcludedFolders(folders)
        scheduler.enqueueImmediate()
    }

    fun forceReconciliation() = viewModelScope.launch {
        dao.clearCursors(); dao.clearRemoteAssets()
        transient.update { it.copy(message = "Full reconciliation queued") }
        scheduler.enqueueImmediate()
    }

    fun retryItem(localId: String) = viewModelScope.launch {
        val media = dao.localMedia(localId) ?: return@launch
        dao.updateLocalState(
            localId,
            if (media.sha256 == null) BackupState.DISCOVERED else BackupState.PENDING,
        )
        scheduler.enqueueImmediate()
    }

    fun logout() = viewModelScope.launch {
        scheduler.cancelSync()
        gateway.logout()
        settings.setOnboarded(false)
        transient.update { Transient(access = mediaRepository.access(), message = "Signed out") }
    }

    fun reconnectAccount() = viewModelScope.launch {
        settings.setOnboarded(false)
    }

    fun eraseLocalState() = viewModelScope.launch {
        scheduler.cancelSync()
        dao.clearBackupRecords(); dao.clearRemoteAssets(); dao.clearCursors(); dao.clearSyncRuns(); dao.clearLocalMedia()
        transient.update { it.copy(message = "Local sync history erased") }
    }

    fun dismissMessage() { transient.update { it.copy(message = null) } }

    fun diagnostics(): String = buildString {
        val value = state.value
        appendLine("iCloud Sync diagnostics")
        appendLine("Account configured: ${value.accountConfigured}")
        appendLine("Media access: ${value.access}")
        appendLine("Items: ${value.media.size}")
        appendLine("Protected: ${value.protected.size}")
        appendLine("Reconciled: ${value.cursor?.fullReconciliationComplete == true}")
        appendLine("Storage visible: ${value.cloudStorage != null}")
        appendLine("Last stage: ${value.latestRun?.stage ?: "none"}")
        appendLine("Last error: ${value.latestRun?.errorSummary ?: "none"}")
    }

    private data class Transient(
        val access: MediaAccess = MediaAccess.DENIED,
        val authBusy: Boolean = false,
        val requiresTwoFactor: Boolean = false,
        val cloudStorage: ICloudStorageUsage? = null,
        val storageLoading: Boolean = false,
        val storageError: String? = null,
        val message: String? = null,
    )

    private data class Persisted(
        val settings: SettingsState,
        val media: List<LocalMediaEntity>,
        val cursor: SyncCursorEntity?,
        val latestRun: SyncRunEntity?,
    )
}

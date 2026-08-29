package dev.bex.icloudsync.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.bex.icloudsync.data.model.BackupState
import dev.bex.icloudsync.data.model.LocalMediaEntity
import dev.bex.icloudsync.data.model.SyncStage
import dev.bex.icloudsync.media.MediaAccess
import java.text.DateFormat
import java.util.Date

private val CloudBlue = Color(0xFF3478F6)
private val CloudGreen = Color(0xFF2DAE75)
private val DrivePurple = Color(0xFF7656D8)
private val AppBackground = Color(0xFFF5F7FB)

@Composable
fun ICloudSyncApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshAccess()
    }
    val requestFullAccess = {
        permissions.launch(
            when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
                Build.VERSION.SDK_INT >= 33 -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        )
    }

    ICloudTheme {
        if (!state.settings.onboardingComplete) {
            OnboardingScreen(state, viewModel, requestFullAccess)
        } else {
            MainShell(state, viewModel, requestFullAccess)
        }
        state.message?.let { message ->
            AlertDialog(
                onDismissRequest = viewModel::dismissMessage,
                confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                title = { Text("iCloud Sync") },
                text = { Text(message) },
            )
        }
    }
}

@Composable
private fun OnboardingScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    requestAccess: () -> Unit,
) {
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppBackground),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(CloudBlue), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CloudUpload, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Your Android library, protected in iCloud", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Photos go to iCloud Photos. Formats Apple rejects are preserved unchanged in iCloud Drive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            NoticeCard(
                icon = Icons.Default.Warning,
                title = "Personal, unofficial integration",
                text = "Apple does not publish an Android Photos API. This app uses the same private web services as iCloud.com and may require updates when Apple changes them.",
            )
        }
        item {
            NoticeCard(
                icon = Icons.Default.Security,
                title = "Encrypted background access",
                text = "Your Apple password and session are encrypted with Android Keystore, but remain available to background backup without biometric approval.",
            )
        }
        if (!state.accountConfigured || state.requiresTwoFactor) {
            item {
                ElevatedCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. Connect Apple account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (!state.requiresTwoFactor) {
                            OutlinedTextField(appleId, { appleId = it }, label = { Text("Apple ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                password,
                                { password = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { viewModel.signIn(appleId, password) },
                                enabled = appleId.isNotBlank() && password.isNotBlank() && !state.authBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { if (state.authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in securely") }
                        } else {
                            Text(
                                "Apple does not email verification codes. Check your trusted Apple device, or on iPhone open Settings → your name → Sign-In & Security → Two-Factor Authentication → Get Verification Code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit verification code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { viewModel.verifyTwoFactor(code) }, enabled = code.length == 6 && !state.authBusy, modifier = Modifier.fillMaxWidth()) {
                                Text("Verify")
                            }
                            TextButton(onClick = viewModel::requestTwoFactorCode, enabled = !state.authBusy, modifier = Modifier.fillMaxWidth()) {
                                Text("Request another device prompt")
                            }
                        }
                    }
                }
            }
        } else {
            item { CompletedStep("1. Apple account connected") }
        }
        item {
            ElevatedCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. Allow the whole library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (state.access) {
                            MediaAccess.FULL -> "Full photo and video access granted."
                            MediaAccess.PARTIAL -> "Only selected items are available. Choose Allow all to protect the whole library."
                            MediaAccess.DENIED -> "Full access is required to calculate truthful backup progress."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.access != MediaAccess.FULL) OutlinedButton(onClick = requestAccess, modifier = Modifier.fillMaxWidth()) { Text("Grant full library access") }
                    else Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = CloudGreen); Spacer(Modifier.width(8.dp)); Text("Ready") }
                }
            }
        }
        item {
            NoticeCard(
                icon = Icons.Default.Sync,
                title = "3. Exact first scan",
                text = "Before uploading, the app hashes your phone and active iCloud library. It may stream your full iCloud library once over Wi-Fi, but stores only hashes and metadata.",
            )
        }
        item {
            ElevatedCard {
                ToggleRow(
                    "Wi-Fi only",
                    "Recommended for the first full iCloud reconciliation",
                    state.settings.wifiOnly,
                    viewModel::setWifiOnly,
                )
            }
        }
        item {
            Button(
                onClick = viewModel::finishOnboarding,
                enabled = state.accountConfigured && !state.requiresTwoFactor && state.access == MediaAccess.FULL,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Start protected setup") }
        }
    }
}

@Composable
private fun MainShell(state: MainUiState, viewModel: MainViewModel, requestAccess: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = AppBackground,
        bottomBar = {
            NavigationBar {
                listOf(Icons.Default.Home to "Overview", Icons.Default.PhotoLibrary to "Library", Icons.Default.Settings to "Settings").forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.first, null) },
                        label = { Text(item.second) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen(state, viewModel, requestAccess)
                1 -> LibraryScreen(state.media, viewModel)
                else -> SettingsScreen(state, viewModel, requestAccess)
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: MainUiState, viewModel: MainViewModel, requestAccess: () -> Unit) {
    val protected = state.protected.size
    val total = state.included.size
    var authenticationCode by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("iCloud Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (state.settings.paused) "Backup paused" else stageLabel(state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = viewModel::syncNow, enabled = !state.settings.paused) { Icon(Icons.Default.Sync, "Sync now") }
            }
        }
        if (state.access != MediaAccess.FULL) {
            item {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("Full library access needed", fontWeight = FontWeight.Bold); Text("Backup is paused to keep progress accurate.") }
                        TextButton(onClick = requestAccess) { Text("Fix") }
                    }
                }
            }
        }
        if (state.authenticationNeedsAttention) {
            item {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Apple verification required", fontWeight = FontWeight.Bold)
                        Text("Backup is paused until the private iCloud session is approved again.")
                        if (!state.accountConfigured) {
                            Button(onClick = viewModel::reconnectAccount) { Text("Reconnect Apple account") }
                        } else if (state.requiresTwoFactor) {
                            Text(
                                "No prompt? On iPhone: Settings → your name → Sign-In & Security → Two-Factor Authentication → Get Verification Code.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                authenticationCode,
                                { authenticationCode = it.filter(Char::isDigit).take(6) },
                                label = { Text("6-digit code") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { viewModel.verifyTwoFactor(authenticationCode) },
                                enabled = authenticationCode.length == 6 && !state.authBusy,
                            ) { Text("Verify and resume") }
                            TextButton(onClick = viewModel::requestTwoFactorCode, enabled = !state.authBusy) {
                                Text("Request another device prompt")
                            }
                        } else {
                            Button(onClick = viewModel::refreshAuthentication, enabled = !state.authBusy) {
                                Text(if (state.authBusy) "Contacting Apple…" else "Continue authentication")
                            }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard {
                Column(Modifier.padding(20.dp)) {
                    Text("Overall protected", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("$protected of $total", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(formatBytes(state.protectedBytes) + " of " + formatBytes(state.totalBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        state.lastSuccessfulRun?.finishedAtEpochMs?.let { "Last successful sync: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" }
                            ?: "No successful sync yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(progress = { state.protectedFraction }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DestinationPill("Photos", state.media.count { it.backupState == BackupState.SYNCED_PHOTOS }, CloudBlue, Modifier.weight(1f))
                        DestinationPill("Drive", state.media.count { it.backupState == BackupState.SYNCED_DRIVE }, DrivePurple, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            ICloudStorageCard(state, viewModel::refreshStorage)
        }
        state.cursor?.takeIf { !it.fullReconciliationComplete }?.let { cursor ->
            item {
                ElevatedCard {
                    Column(Modifier.padding(18.dp)) {
                        Text("First library reconciliation", fontWeight = FontWeight.Bold)
                        Text("${cursor.remoteProcessed} of ${cursor.remoteTotal} remote items", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${formatBytes(cursor.remoteBytesProcessed)} streamed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (cursor.remoteTotal == 0L) 0f else cursor.remoteProcessed.toFloat() / cursor.remoteTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Pending", state.media.count { it.backupState in setOf(BackupState.DISCOVERED, BackupState.HASHING, BackupState.PENDING, BackupState.UPLOADING, BackupState.VERIFYING) }, Icons.Default.Schedule, Modifier.weight(1f))
                MetricCard("Attention", state.media.count { it.backupState in setOf(BackupState.FAILED, BackupState.REMOTE_REMOVED, BackupState.BLOCKED_AUTH, BackupState.BLOCKED_PERMISSION, BackupState.BLOCKED_QUOTA) }, Icons.Default.ErrorOutline, Modifier.weight(1f))
            }
        }
        item {
            Button(
                onClick = { viewModel.setPaused(!state.settings.paused) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (state.settings.paused) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            ) { Icon(if (state.settings.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Spacer(Modifier.width(8.dp)); Text(if (state.settings.paused) "Resume backup" else "Pause backup") }
        }
    }
}

@Composable
private fun LibraryScreen(media: List<LocalMediaEntity>, viewModel: MainViewModel) {
    val filters = listOf(
        "All" to BackupState.entries.toSet(),
        "Synced to Photos" to setOf(BackupState.SYNCED_PHOTOS),
        "Drive fallback" to setOf(BackupState.SYNCED_DRIVE),
        "Pending" to setOf(BackupState.DISCOVERED, BackupState.PENDING),
        "Uploading" to setOf(BackupState.HASHING, BackupState.UPLOADING, BackupState.VERIFYING),
        "Action needed" to setOf(BackupState.REMOTE_REMOVED, BackupState.BLOCKED_AUTH, BackupState.BLOCKED_PERMISSION, BackupState.BLOCKED_QUOTA),
        "Failed" to setOf(BackupState.FAILED),
        "Excluded" to setOf(BackupState.EXCLUDED),
    )
    var selected by rememberSaveable { mutableStateOf("All") }
    val visible = filters.first { it.first == selected }.second.let { states -> media.filter { it.backupState in states } }
    Column(Modifier.fillMaxSize()) {
        Text("Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters) { filter -> FilterChip(selected = selected == filter.first, onClick = { selected = filter.first }, label = { Text(filter.first) }) }
        }
        if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items in this view", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(visible, key = { it.localId }) { item -> MediaTile(item, viewModel::retryItem) }
        }
    }
}

@Composable
private fun MediaTile(item: LocalMediaEntity, retry: (String) -> Unit) {
    ElevatedCard {
        Column {
            Box(Modifier.aspectRatio(1f).background(Color.LightGray)) {
                AsyncImage(model = item.contentUri, contentDescription = item.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(statusColor(item.backupState)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(statusIcon(item.backupState), null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
            Text(
                item.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp),
            )
            item.lastError?.let { error ->
                Text(
                    error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 7.dp),
                )
            }
            if (item.backupState in setOf(BackupState.REMOTE_REMOVED, BackupState.BLOCKED_QUOTA, BackupState.FAILED)) {
                TextButton(onClick = { retry(item.localId) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                    Text(if (item.backupState == BackupState.REMOTE_REMOVED) "Back up again" else "Retry")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, viewModel: MainViewModel, requestAccess: () -> Unit) {
    val context = LocalContext.current
    val folders = state.media.map { it.relativePath }.filter(String::isNotBlank).distinct().sorted()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            SettingsGroup("Backup conditions") {
                ToggleRow("Wi-Fi only", "Prevent cellular media transfers", state.settings.wifiOnly, viewModel::setWifiOnly)
                HorizontalDivider()
                ToggleRow("Only while charging", "Wait for external power", state.settings.chargingOnly, viewModel::setChargingOnly)
                HorizontalDivider()
                ToggleRow("Completion notifications", "Notify after a successful run", state.settings.completionNotifications, viewModel::setCompletionNotifications)
            }
        }
        item {
            SettingsGroup("Permissions and account") {
                ActionRow("Media access", state.access.name.lowercase().replaceFirstChar(Char::uppercase), Icons.Default.PhotoLibrary, requestAccess)
                HorizontalDivider()
                ActionRow(
                    "Apple account",
                    when {
                        !state.accountConfigured -> "Not connected"
                        state.authenticationNeedsAttention -> "Verification required"
                        else -> "Connected"
                    },
                    Icons.Default.AccountCircle,
                    when {
                        !state.accountConfigured -> ({ viewModel.reconnectAccount() })
                        state.authenticationNeedsAttention -> ({ viewModel.refreshAuthentication() })
                        else -> ({})
                    },
                )
                HorizontalDivider()
                ActionRow("Sign out", "Remove encrypted credentials", Icons.AutoMirrored.Filled.Logout, viewModel::logout)
            }
        }
        if (folders.isNotEmpty()) {
            item { Text("Excluded folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(folders, key = { it }) { folder ->
                ElevatedCard {
                    CheckboxRow(folder, folder in state.settings.excludedFolders) { viewModel.toggleFolder(folder) }
                }
            }
        }
        item {
            SettingsGroup("Maintenance") {
                ActionRow("Full reconciliation", "Rebuild exact remote hash catalog", Icons.Default.Refresh, viewModel::forceReconciliation)
                HorizontalDivider()
                ActionRow("Copy diagnostics", "No credentials or media URLs", Icons.Default.ContentCopy) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("iCloud Sync diagnostics", viewModel.diagnostics()))
                }
                HorizontalDivider()
                ActionRow("Erase local sync history", "Does not delete phone or iCloud media", Icons.Default.DeleteSweep, viewModel::eraseLocalState)
            }
        }
        item {
            Text("Private iCloud protocol • Personal sideload • v0.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

@Composable private fun NoticeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    ElevatedCard { Row(Modifier.padding(16.dp)) { Icon(icon, null, tint = CloudBlue); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(3.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}
@Composable private fun CompletedStep(text: String) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = CloudGreen); Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.SemiBold) } }
@Composable private fun DestinationPill(label: String, count: Int, color: Color, modifier: Modifier = Modifier) { Row(modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .10f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp)); Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(count.toString(), fontWeight = FontWeight.Bold) } } }
@Composable private fun MetricCard(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) { ElevatedCard(modifier) { Column(Modifier.padding(16.dp)) { Icon(icon, null, tint = CloudBlue); Spacer(Modifier.height(10.dp)); Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun ICloudStorageCard(state: MainUiState, refresh: () -> Unit) {
    ElevatedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = CloudBlue)
                Spacer(Modifier.width(10.dp))
                Text("iCloud storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = refresh, enabled = !state.storageLoading) { Icon(Icons.Default.Refresh, "Refresh storage") }
            }
            val usage = state.cloudStorage
            when {
                usage != null -> {
                    Text("${formatBytes(usage.usedBytes)} used of ${formatBytes(usage.totalBytes)}", fontWeight = FontWeight.SemiBold)
                    Text("${formatBytes(usage.availableBytes)} available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = { (usage.usedBytes.toDouble() / usage.totalBytes).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = if (usage.overQuota || usage.almostFull) MaterialTheme.colorScheme.error else CloudBlue,
                    )
                    if (usage.overQuota || usage.almostFull) {
                        Text(
                            if (usage.overQuota) "Storage is full. Uploads are paused until space is available."
                            else "Storage is almost full.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    usage.categories.sortedByDescending { it.usageBytes }.take(4).forEach { category ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(category.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(formatBytes(category.usageBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.storageLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    Text(state.storageError ?: "Storage information has not been loaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = refresh) { Text("Try again") }
                }
            }
        }
    }
}
@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) { Column { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)); ElevatedCard { Column(Modifier.padding(horizontal = 16.dp), content = content) } } }
@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onChecked) } }
@Composable private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) { TextButton(onClick = action, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxWidth()) { Icon(icon, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Default.ChevronRight, null) } }
@Composable private fun CheckboxRow(title: String, checked: Boolean, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { onClick() }); Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

private fun stageLabel(state: MainUiState): String = when (state.latestRun?.stage) {
    SyncStage.INDEXING -> "Indexing library"
    SyncStage.RECONCILING -> "Reconciling with iCloud"
    SyncStage.UPLOADING -> "Uploading"
    SyncStage.AUTH_REQUIRED -> "Apple verification required"
    SyncStage.PROTOCOL_STOPPED -> "Update required"
    SyncStage.PAUSED -> "Waiting to retry"
    else -> if (state.protected.size == state.included.size && state.included.isNotEmpty()) "Everything is protected" else "Monitoring for new media"
}
private fun statusColor(state: BackupState): Color = when (state) { BackupState.SYNCED_PHOTOS -> CloudBlue; BackupState.SYNCED_DRIVE -> DrivePurple; BackupState.FAILED, BackupState.REMOTE_REMOVED, BackupState.BLOCKED_AUTH, BackupState.BLOCKED_PERMISSION, BackupState.BLOCKED_QUOTA -> Color(0xFFE04B4B); BackupState.EXCLUDED -> Color.Gray; else -> Color(0xFFF39A32) }
private fun statusIcon(state: BackupState) = when (state) { BackupState.SYNCED_PHOTOS, BackupState.SYNCED_DRIVE -> Icons.Default.Check; BackupState.FAILED, BackupState.REMOTE_REMOVED, BackupState.BLOCKED_AUTH, BackupState.BLOCKED_PERMISSION, BackupState.BLOCKED_QUOTA -> Icons.Default.PriorityHigh; BackupState.EXCLUDED -> Icons.Default.Remove; else -> Icons.Default.Schedule }
private fun formatBytes(bytes: Long): String { if (bytes < 1024) return "$bytes B"; val units = arrayOf("KB", "MB", "GB", "TB"); var value = bytes / 1024.0; var index = 0; while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }; return "%.1f %s".format(value, units[index]) }

@Composable
private fun ICloudTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(primary = CloudBlue, secondary = DrivePurple, background = AppBackground, surface = Color.White)
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

package com.example.zero_touch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.audio.ambient.AmbientRecordingService
import com.example.zero_touch.audio.ambient.AmbientStatus
import kotlin.math.max
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun VoiceMemoScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    showFavoritesOnly: Boolean,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var ambientEnabled by remember { mutableStateOf(AmbientPreferences.isAmbientEnabled(context)) }
    val ambientState by AmbientStatus.state.collectAsState()
    val feedCards = uiState.feedCards
    val pendingRecordings = ambientState.recordings
    val dismissedIds = uiState.dismissedIds
    val favoriteIds = uiState.favoriteIds
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasRecordPermission = granted
            message = if (granted) null else "Microphone permission required"
        }
    val requestNotificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                message = "Notification permission required for ambient mode"
                ambientEnabled = false
                AmbientPreferences.setAmbientEnabled(context, false)
            } else if (ambientEnabled) {
                startAmbientService(context)
            }
        }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // no-op
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(ambientEnabled, hasRecordPermission, hasNotificationPermission) {
        if (!ambientEnabled) {
            return@LaunchedEffect
        }
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission
        if (!hasRecordPermission || !notificationsOk) {
            ambientEnabled = false
            AmbientPreferences.setAmbientEnabled(context, false)
            stopAmbientService(context)
            return@LaunchedEffect
        }
        startAmbientService(context)
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "ZeroTouch",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = ambientEnabled,
                    onCheckedChange = { enabled ->
                        message = null
                        if (enabled) {
                            if (!hasRecordPermission) {
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@Switch
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@Switch
                            }
                            AmbientPreferences.setAmbientEnabled(context, true)
                            ambientEnabled = true
                            startAmbientService(context)
                        } else {
                            AmbientPreferences.setAmbientEnabled(context, false)
                            ambientEnabled = false
                            stopAmbientService(context)
                        }
                    },
                )
            }
        }

        Text(
            text = formatTodayJa(),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!hasRecordPermission) {
            Text(
                text = "Microphone permission is required to record.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.semantics { testTag = "permission_button" },
            ) {
                Text("Grant Permission")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Status: ${ambientState.status}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "VAD: ${if (ambientState.speech) "speech" else "silence"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = ambientState.level,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RecordingBadge(isActive = ambientState.isRecording)
                    Text(
                        text = if (ambientState.isRecording) {
                            "Recording ${formatDuration(ambientState.recordingElapsedMs)}"
                        } else {
                            "Idle"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ambientState.lastEvent?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (uiState.isLoading) {
            Text(
                text = "読み込み中...",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val now = System.currentTimeMillis()
        val pendingCards = pendingRecordings.mapNotNull { entry ->
            val timeTitle = formatTimeOnly(entry.createdAt)
            val matchedServer = feedCards.any { card ->
                card.createdAtEpochMs > 0L &&
                    kotlin.math.abs(card.createdAtEpochMs - entry.createdAt) <= 2 * 60_000
            }
            val isRecent = now - entry.createdAt <= 10 * 60_000
            if (matchedServer || !isRecent) {
                null
            } else {
                val id = "local-${entry.createdAt}"
                if (dismissedIds.contains(id)) return@mapNotNull null
                TranscriptCard(
                    id = id,
                    createdAt = "",
                    createdAtEpochMs = entry.createdAt,
                    status = "pending",
                    displayStatus = "処理待ち",
                    isProcessing = true,
                    text = "(処理待ち)",
                    displayTitle = timeTitle
                )
            }
        }.take(5)
        val mergedCards = pendingCards + feedCards
        val visibleCards = if (showFavoritesOnly) {
            mergedCards.filter { favoriteIds.contains(it.id) }
        } else {
            mergedCards
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleCards) { card ->
                TranscriptCardItem(
                    card = card,
                    isFavorite = favoriteIds.contains(card.id),
                    onToggleFavorite = onToggleFavorite,
                    onDelete = onDeleteCard
                )
            }
        }

        if (!uiState.isLoading && visibleCards.isEmpty()) {
            Text(
                text = "まだカードがありません",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (message != null) {
            Text(
                text = message!!,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun startAmbientService(context: Context) {
    val intent = Intent(context, AmbientRecordingService::class.java).apply {
        action = AmbientRecordingService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopAmbientService(context: Context) {
    val intent = Intent(context, AmbientRecordingService::class.java).apply {
        action = AmbientRecordingService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = max(0, durationMs / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
private fun RecordingBadge(isActive: Boolean) {
    val color = if (isActive) Color.Red else Color.Gray
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun TranscriptCardItem(
    card: TranscriptCard,
    isFavorite: Boolean,
    onToggleFavorite: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (card.isProcessing) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.displayTitle,
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = card.displayStatus,
                        style = MaterialTheme.typography.labelSmall
                    )
                    ProcessingDots(isActive = card.isProcessing)
                    TextButton(
                        onClick = { onToggleFavorite(card.id) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Text(if (isFavorite) "★" else "☆")
                    }
                    TextButton(onClick = { onDelete(card.id) }) {
                        Text("×")
                    }
                }
            }
            Text(
                text = card.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ProcessingDots(isActive: Boolean) {
    if (!isActive) return
    var dots by remember { mutableStateOf(".") }
    LaunchedEffect(isActive) {
        val sequence = listOf(".", "..", "...")
        var index = 0
        while (isActive) {
            dots = sequence[index % sequence.size]
            index++
            delay(400)
        }
    }
    Text(
        text = dots,
        style = MaterialTheme.typography.labelSmall
    )
}

private fun formatTodayJa(): String {
    val formatter = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN)
    return LocalDate.now().format(formatter)
}

private fun formatTimeOnly(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
    return java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(formatter)
}

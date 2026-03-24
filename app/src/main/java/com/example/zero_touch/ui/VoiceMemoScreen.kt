package com.example.zero_touch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.audio.ambient.AmbientRecordingService
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.components.AmbientStatusBar
import com.example.zero_touch.ui.components.CardDetailSheet
import com.example.zero_touch.ui.components.ShimmerCardList
import com.example.zero_touch.ui.components.TranscriptCardView
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun VoiceMemoScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    showFavoritesOnly: Boolean,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onLoadMore: () -> Unit,
    onRetranscribeEnglish: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Permission & ambient state (preserved from original) ---
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var ambientEnabled by remember { mutableStateOf(AmbientPreferences.isAmbientEnabled(context)) }
    val ambientState by AmbientStatus.state.collectAsState()
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
        }
    val requestNotificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                ambientEnabled = false
                AmbientPreferences.setAmbientEnabled(context, false)
            } else if (ambientEnabled) {
                startAmbientService(context)
            }
        }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { /* no-op */ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ambientEnabled, hasRecordPermission, hasNotificationPermission) {
        if (!ambientEnabled) return@LaunchedEffect
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission
        if (!hasRecordPermission || !notificationsOk) {
            ambientEnabled = false
            AmbientPreferences.setAmbientEnabled(context, false)
            stopAmbientService(context)
            return@LaunchedEffect
        }
        startAmbientService(context)
    }

    // --- Data preparation ---
    val feedCards = uiState.feedCards
    val topicCards = uiState.topicCards
    val pendingRecordings = ambientState.recordings
    val dismissedIds = uiState.dismissedIds
    val favoriteIds = uiState.favoriteIds
    val now = System.currentTimeMillis()

    val pendingCards = pendingRecordings.mapNotNull { entry ->
        val timeTitle = formatTimeOnly(entry.createdAt)
        val matchedServer = if (!entry.sessionId.isNullOrBlank()) {
            feedCards.any { card -> card.id == entry.sessionId }
        } else {
            feedCards.any { card ->
                card.createdAtEpochMs > 0L &&
                    kotlin.math.abs(card.createdAtEpochMs - entry.createdAt) <= 20_000
            }
        }
        val isRecent = now - entry.createdAt <= 10 * 60_000
        if (matchedServer || !isRecent) {
            null
        } else {
            val id = if (!entry.sessionId.isNullOrBlank()) "local-${entry.sessionId}" else "local-${entry.createdAt}"
            if (dismissedIds.contains(id)) return@mapNotNull null
            TranscriptCard(
                id = id,
                createdAt = "",
                createdAtEpochMs = entry.createdAt,
                status = "pending",
                displayStatus = "Processing",
                isProcessing = true,
                text = "",
                displayTitle = timeTitle,
                durationSeconds = 0,
                displayDate = "Today"
            )
        }
    }.take(5)

    val mergedCards = pendingCards + feedCards
    val topicChildren = topicCards.flatMap { it.utterances }
    val visibleCards = if (showFavoritesOnly) {
        mergedCards.filter { favoriteIds.contains(it.id) }
    } else {
        mergedCards
    }
    val visibleTopicCards = if (showFavoritesOnly) {
        emptyList()
    } else {
        topicCards
    }

    // Group cards by date
    val groupedCards = visibleCards.groupBy { card ->
        if (card.displayDate.isNotEmpty()) card.displayDate
        else dateFromEpoch(card.createdAtEpochMs)
    }

    val listState = rememberLazyListState()

    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading, visibleCards.size) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 3
        }
            .distinctUntilChanged()
            .collect { isNearEnd ->
                if (isNearEnd && uiState.hasMore && !uiState.isLoadingMore && !uiState.isLoading) {
                    onLoadMore()
                }
            }
    }

    // --- Bottom sheet for card detail ---
    val selectedCard = uiState.selectedCardId?.let { id ->
        (mergedCards + topicChildren).distinctBy { it.id }.find { it.id == id }
    }
    if (selectedCard != null) {
        CardDetailSheet(
            card = selectedCard,
            isFavorite = favoriteIds.contains(selectedCard.id),
            onDismiss = onDismissDetail,
            onToggleFavorite = { onToggleFavorite(selectedCard.id) },
            onDelete = { onDeleteCard(selectedCard.id) },
            onCopy = {
                clipboardManager.setText(AnnotatedString(selectedCard.text))
            },
            onRetranscribeEnglish = { onRetranscribeEnglish(selectedCard.id) }
        )
    }

    // --- Main UI ---
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Header row: date + ambient toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = formatTodayJa(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtOnSurfaceVariant
                )
            }
            // Ambient toggle chip
            AmbientToggleChip(
                enabled = ambientEnabled,
                onToggle = { enabled ->
                    if (enabled) {
                        if (!hasRecordPermission) {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@AmbientToggleChip
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@AmbientToggleChip
                        }
                        AmbientPreferences.setAmbientEnabled(context, true)
                        ambientEnabled = true
                        startAmbientService(context)
                    } else {
                        AmbientPreferences.setAmbientEnabled(context, false)
                        ambientEnabled = false
                        stopAmbientService(context)
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        // Ambient status bar (recording indicator)
        AmbientStatusBar(
            ambientState = ambientState,
            isEnabled = ambientEnabled
        )

        // Permission prompt
        if (!hasRecordPermission) {
            Spacer(Modifier.height(8.dp))
            PermissionBanner(
                onGrant = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
        }

        Spacer(Modifier.height(12.dp))

        // Search bar (mock, non-functional)
        SearchBarMock()

        Spacer(Modifier.height(16.dp))

        // Feed list
        when {
            uiState.isLoading && visibleCards.isEmpty() && visibleTopicCards.isEmpty() -> {
                ShimmerCardList(count = 3)
            }
            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (visibleCards.isEmpty() && visibleTopicCards.isEmpty()) {
                        item(key = "empty_state") {
                            EmptyStateView(showFavoritesOnly = showFavoritesOnly)
                        }
                    } else {
                        groupedCards.forEach { (dateLabel, cards) ->
                            item(key = "header_$dateLabel") {
                                DateHeader(dateLabel)
                            }
                            items(cards, key = { it.id }) { card ->
                                TranscriptCardView(
                                    card = card,
                                    isFavorite = favoriteIds.contains(card.id),
                                    onClick = { onSelectCard(card.id) },
                                    onToggleFavorite = { onToggleFavorite(card.id) }
                                )
                            }
                        }

                        if (visibleTopicCards.isNotEmpty()) {
                            val groupedTopics = visibleTopicCards.groupBy { it.displayDate.ifEmpty { "Unknown" } }
                            groupedTopics.forEach { (dateLabel, topics) ->
                                item(key = "topic_header_$dateLabel") {
                                    DateHeader(dateLabel)
                                }
                                items(topics, key = { it.id }) { topic ->
                                    TopicGroupCard(
                                        topic = topic,
                                        favoriteIds = favoriteIds,
                                        onSelectCard = onSelectCard,
                                        onToggleFavorite = onToggleFavorite
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.isLoadingMore) {
                        item(key = "loading_more") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Loading more...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ZtOnSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Sub-composables ---

@Composable
private fun TopicGroupCard(
    topic: TopicFeedCard,
    favoriteIds: Set<String>,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${topic.utteranceCount} utterances",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                }
                TopicStatusBadge(topic.status)
            }

            if (topic.summary.isNotBlank()) {
                Text(
                    text = topic.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtOnSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                topic.utterances.forEach { utterance ->
                    TranscriptCardView(
                        card = utterance,
                        isFavorite = favoriteIds.contains(utterance.id),
                        onClick = { onSelectCard(utterance.id) },
                        onToggleFavorite = { onToggleFavorite(utterance.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopicStatusBadge(status: String) {
    val label = when (status) {
        "active" -> "Live"
        "cooling" -> "Cooling"
        "finalized" -> "Finalized"
        else -> status
    }
    val color = when (status) {
        "active" -> MaterialTheme.colorScheme.primary
        "cooling" -> MaterialTheme.colorScheme.tertiary
        "finalized" -> ZtOnSurfaceVariant
        else -> ZtOnSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AmbientToggleChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surfaceVariant,
        onClick = { onToggle(!enabled) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Outlined.Mic else Icons.Outlined.MicNone,
                contentDescription = "Ambient",
                modifier = Modifier.size(16.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else ZtOnSurfaceVariant
            )
            Text(
                text = if (enabled) "Listening" else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else ZtOnSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchBarMock() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "Search",
                tint = ZtCaption,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Search transcripts...",
                style = MaterialTheme.typography.bodyMedium,
                color = ZtCaption
            )
        }
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        onClick = onGrant
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "Microphone access required",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Tap to grant permission",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = ZtOnSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun EmptyStateView(showFavoritesOnly: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (showFavoritesOnly) "No saved items" else "No transcripts yet",
                style = MaterialTheme.typography.titleMedium,
                color = ZtOnSurfaceVariant
            )
            Text(
                text = if (showFavoritesOnly) {
                    "Bookmark transcripts to find them here"
                } else {
                    "Turn on ambient listening to capture conversations automatically"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ZtCaption
            )
        }
    }
}

// --- Utility functions ---

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

private fun formatTodayJa(): String {
    val formatter = DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN)
    return LocalDate.now().format(formatter)
}

private fun formatTimeOnly(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(formatter)
}

private fun dateFromEpoch(epochMs: Long): String {
    if (epochMs <= 0L) return "Unknown"
    val localDate = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val today = LocalDate.now()
    return when (localDate) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> localDate.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN))
    }
}

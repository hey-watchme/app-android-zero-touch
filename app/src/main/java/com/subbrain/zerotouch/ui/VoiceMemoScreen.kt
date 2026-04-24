package com.subbrain.zerotouch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.subbrain.zerotouch.api.SessionSummary
import com.subbrain.zerotouch.audio.ambient.AmbientRecordingEntry
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import com.subbrain.zerotouch.ui.components.AmbientStatusBar
import com.subbrain.zerotouch.ui.components.AnimatedProcessingDots
import com.subbrain.zerotouch.ui.components.CardDetailSheet
import com.subbrain.zerotouch.ui.components.ShimmerCardList
import com.subbrain.zerotouch.ui.components.SideDetailDrawer
import com.subbrain.zerotouch.ui.components.SpeakerIdentityBadge
import com.subbrain.zerotouch.ui.components.TranscriptCardView
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtCardRowDivider
import com.subbrain.zerotouch.ui.theme.ZtFilterBorder
import com.subbrain.zerotouch.ui.theme.ZtFilterSelected
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtRecording
import com.subbrain.zerotouch.ui.theme.ZtTopicSurface
import androidx.compose.runtime.snapshotFlow
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMemoScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    showFavoritesOnly: Boolean,
    ambientEnabled: Boolean,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetranscribeEnglish: (String) -> Unit,
    onRetryTranscribe: (String) -> Unit
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val ambientState by AmbientStatus.state.collectAsState()
    val context = LocalContext.current

    // --- Filter state ---
    val allLevels = remember { (0..5).toSet() }
    var enabledLevels by remember {
        mutableStateOf(AmbientPreferences.getImportanceLevels(context).ifEmpty { allLevels })
    }

    // --- Data preparation ---
    val topicCards = uiState.topicCards
    val favoriteIds = uiState.favoriteIds
    val realTopicChildren = topicCards.flatMap { it.utterances }
    val topicChildIds = realTopicChildren.map { it.id }.toSet()
    val pendingTopicCards = buildPendingTopicCards(
        recordings = ambientState.recordings,
        sessions = uiState.sessions,
        loadedCards = uiState.feedCards,
        topicChildIds = topicChildIds
    )
    val mergedTopicCards = (topicCards + pendingTopicCards)
        .sortedByDescending { it.updatedAtEpochMs }
    val today = LocalDate.now()
    var selectedDate by remember { mutableStateOf(today) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var calendarMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    val availableDates = remember(mergedTopicCards) {
        mergedTopicCards.mapNotNull { topic -> dateFromEpoch(topic.updatedAtEpochMs) }.toSet()
    }
    val earliestLoadedDate = mergedTopicCards
        .mapNotNull { topic -> dateFromEpoch(topic.updatedAtEpochMs) }
        .minOrNull()
    val visibleTopicCards = if (showFavoritesOnly) {
        emptyList()
    } else {
        mergedTopicCards.filter { topic ->
            val level = topic.importanceLevel ?: 0
            level in enabledLevels
        }
    }
    val topicsForSelectedDate = visibleTopicCards.filter { topic ->
        val date = dateFromEpoch(topic.updatedAtEpochMs) ?: return@filter false
        date == selectedDate
    }

    val listState = rememberLazyListState()

    LaunchedEffect(selectedDate, earliestLoadedDate, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading) {
        if (!uiState.hasMore || uiState.isLoadingMore || uiState.isLoading) return@LaunchedEffect
        if (mergedTopicCards.isEmpty()) {
            onLoadMore()
            return@LaunchedEffect
        }
        if (earliestLoadedDate != null && selectedDate.isBefore(earliestLoadedDate)) {
            onLoadMore()
        }
    }

    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading) {
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

    // --- Bottom sheet ---
    val mergedTopicChildren = mergedTopicCards.flatMap { it.utterances }
    val selectedCard = uiState.selectedCardId?.let { id ->
        mergedTopicChildren.distinctBy { it.id }.find { it.id == id }
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
            onRetranscribeEnglish = { onRetranscribeEnglish(selectedCard.id) },
            onRetryTranscribe = { onRetryTranscribe(selectedCard.id) }
        )
    }

    var selectedTopicId by remember { mutableStateOf<String?>(null) }
    val selectedTopic = selectedTopicId?.let { id ->
        mergedTopicCards.find { it.id == id }
    }
    val openDatePicker = {
        calendarMonth = YearMonth.from(selectedDate)
        showCalendarDialog = true
    }

    // --- Main UI ---
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(4.dp))

            // Ambient status bar
            AmbientStatusBar(
                ambientState = ambientState,
                isEnabled = ambientEnabled
            )

            Spacer(Modifier.height(8.dp))

            HomeDateHeader(
                selectedDate = selectedDate,
                onPrevious = { selectedDate = selectedDate.minusDays(1) },
                onNext = {
                    if (selectedDate.isBefore(today)) {
                        selectedDate = selectedDate.plusDays(1)
                    }
                },
                onOpenCalendar = openDatePicker,
                canGoNext = selectedDate.isBefore(today),
                topicCount = topicsForSelectedDate.size,
                isAmbientOn = ambientState.isRecording || ambientState.speech
            )

            Spacer(Modifier.height(8.dp))

            // Scrollable content: search, filters, and feed
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
            // Search and filters (scroll away together)
            item(key = "search_filters") {
                ImportanceFilterRow(
                    showFavoritesOnly = showFavoritesOnly,
                    enabledLevels = enabledLevels,
                    onToggleLevel = { level ->
                        val next = if (level in enabledLevels) {
                            enabledLevels - level
                        } else {
                            enabledLevels + level
                        }
                        enabledLevels = if (next.isEmpty()) emptySet() else next
                        AmbientPreferences.setImportanceLevels(context, enabledLevels)
                    },
                    onSelectAll = {
                        enabledLevels = allLevels
                        AmbientPreferences.setImportanceLevels(context, enabledLevels)
                    }
                )
            }

            // Feed content
            val isRecording = ambientState.isRecording

            // Placeholder topic for live recording state
            val showLiveRecordingTopic = isRecording && selectedDate == today
            val liveRecordingTopic = if (showLiveRecordingTopic) {
                TopicFeedCard(
                    id = "live_recording",
                    status = "recording",
                    title = "",
                    summary = "",
                    utteranceCount = 1,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    displayDate = "今日",
                    utterances = listOf(
                        TranscriptCard(
                            id = "live_recording_card",
                            createdAt = "",
                            createdAtEpochMs = System.currentTimeMillis(),
                            status = "recording",
                            displayStatus = "録音中",
                            isProcessing = true,
                            text = "",
                            displayTitle = "--:--",
                            durationSeconds = 0,
                            displayDate = "今日",
                            speakerSegments = emptyList()
                        )
                    )
                )
            } else null

            if (uiState.isLoading && topicsForSelectedDate.isEmpty() && liveRecordingTopic == null) {
                item(key = "shimmer") {
                    ShimmerCardList(count = 4)
                }
            } else if (topicsForSelectedDate.isEmpty() && liveRecordingTopic == null) {
                item(key = "empty_state") {
                    EmptyStateView(
                        showFavoritesOnly = showFavoritesOnly,
                        selectedDate = selectedDate
                    )
                }
            } else {
                if (liveRecordingTopic != null) {
                    item(key = "live_recording") {
                        TopicGroupCard(
                            topic = liveRecordingTopic,
                            favoriteIds = favoriteIds,
                            onOpenTopic = { selectedTopicId = it.id },
                            onSelectCard = {},
                            onToggleFavorite = {}
                        )
                    }
                }

                items(topicsForSelectedDate, key = { it.id }) { topic ->
                    TopicGroupCard(
                        topic = topic,
                        favoriteIds = favoriteIds,
                        onOpenTopic = { selectedTopicId = it.id },
                        onSelectCard = onSelectCard,
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }

            if (uiState.isLoadingMore) {
                item(key = "loading_more") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "読み込み中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtCaption
                        )
                    }
                }
            }
            }
        }
        }

        if (selectedTopic != null) {
            TopicDetailDrawer(
                topic = selectedTopic,
                onClose = { selectedTopicId = null }
            )
        }

        if (showCalendarDialog) {
            DataDateCalendarDialog(
                selectedDate = selectedDate,
                currentMonth = calendarMonth,
                availableDates = availableDates,
                onMonthChange = { calendarMonth = it },
                onDismiss = { showCalendarDialog = false },
                onSelectDate = { picked ->
                    selectedDate = picked
                    showCalendarDialog = false
                }
            )
        }
    }
}

// --- Topic Group Card (always expanded, tap for detail drawer) ---

@Composable
private fun TopicGroupCard(
    topic: TopicFeedCard,
    favoriteIds: Set<String>,
    onOpenTopic: (TopicFeedCard) -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val isLiveTopic = topic.status == "recording"
    val isFailedTopic = topic.status == "failed"
    val isUnintelligibleTopic = topic.isUnintelligible

    // Topic-level entry animation
    val topicAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(topic.id) {
        topicAlpha.animateTo(1f, animationSpec = tween(250, easing = FastOutSlowInEasing))
    }

    // Shimmer for skeleton placeholders in live topic
    val shimmerTransition = rememberInfiniteTransition(label = "topic_shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_a"
    )

    val topicContentAlpha = when {
        isFailedTopic -> 0.5f
        isUnintelligibleTopic -> 0.6f
        else -> 1f
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = topicAlpha.value * topicContentAlpha
            },
        shape = RoundedCornerShape(8.dp),
        color = ZtTopicSurface,
        shadowElevation = 0.5.dp
    ) {
        Box {
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                )
            ) {
                // Topic header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ZtTopicSurface,
                    onClick = { onOpenTopic(topic) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLiveTopic) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .background(
                                        ZtCaption.copy(alpha = shimmerAlpha),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        } else {
                            Text(
                                text = topic.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                color = if (isUnintelligibleTopic) ZtCaption else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (topic.importanceLevel != null) {
                            ImportanceLevelBadge(level = topic.importanceLevel)
                        }
                        Text(
                            text = "${topic.utteranceCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtCaption
                        )
                    }
                }

                // Keep unintelligible topics compact (single-line row).
                if (!isUnintelligibleTopic) {
                    // Summary area
                    when {
                        isLiveTopic -> {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 8.dp)
                                    .fillMaxWidth(0.55f)
                                    .height(10.dp)
                                    .background(
                                        ZtCaption.copy(alpha = shimmerAlpha * 0.6f),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                        (topic.status == "cooling" || topic.status == "processing") && topic.summary.isBlank() -> {
                            TopicAnalyzingLabel(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 8.dp)
                            )
                        }
                        topic.summary.isNotBlank() -> {
                            Text(
                                text = topic.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = ZtCaption,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 8.dp)
                            )
                        }
                    }

                    // Expanded: child cards
                    Column {
                        HorizontalDivider(
                            color = ZtCardRowDivider,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            topic.utterances.forEach { utterance ->
                                TranscriptCardView(
                                    card = utterance,
                                    isFavorite = favoriteIds.contains(utterance.id),
                                    onClick = { onSelectCard(utterance.id) },
                                    onToggleFavorite = { onToggleFavorite(utterance.id) },
                                    compact = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportanceLevelBadge(level: Int) {
    Text(
        text = "Lv.$level",
        style = MaterialTheme.typography.labelSmall,
        color = ZtCaption
    )
}


@Composable
private fun TopicDetailDrawer(
    topic: TopicFeedCard,
    onClose: () -> Unit
) {
    SideDetailDrawer(
        title = "Topic Detail",
        onClose = onClose
    ) {
        DetailSection(title = "Title") {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        DetailSection(title = "Summary") {
            Text(
                text = if (topic.summary.isBlank()) "No summary yet" else topic.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = ZtOnSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        DetailSection(title = "Metadata") {
            DetailRow(label = "ID", value = topic.id)
            DetailRow(label = "Status", value = topicStatusLabel(topic.status))
            DetailRow(
                label = "Importance",
                value = if (topic.importanceLevel != null) "Lv.${topic.importanceLevel}" else "-"
            )
            if (!topic.importanceReason.isNullOrBlank()) {
                DetailRow(label = "Reason", value = topic.importanceReason)
            }
            DetailRow(label = "Cards", value = topic.utteranceCount.toString())
            DetailRow(label = "Updated", value = formatEpochDateTime(topic.updatedAtEpochMs))
            DetailRow(
                label = "LLM",
                value = listOfNotNull(topic.llmProvider, topic.llmModel)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" / ")
                    ?: "-"
            )
        }

        DetailSection(title = "Cards") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                topic.utterances.forEach { card ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = card.displayTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ZtOnSurfaceVariant
                                )
                                Text(
                                    text = card.displayStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ZtCaption
                                )
                            }
                            if (!card.isUnintelligible) {
                                SpeakerIdentityBadge(speakerLabels = card.speakerLabels)
                            }
                            Text(
                                text = card.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (card.isUnintelligible) ZtCaption else MaterialTheme.colorScheme.onSurface,
                                maxLines = if (card.isUnintelligible) 1 else Int.MAX_VALUE,
                                overflow = if (card.isUnintelligible) TextOverflow.Ellipsis else TextOverflow.Clip
                            )
                            DetailRow(
                                label = "Duration",
                                value = if (card.durationSeconds > 0) "${card.durationSeconds}s" else "-"
                            )
                            DetailRow(
                                label = "Recorded",
                                value = formatEpochDateTime(card.createdAtEpochMs)
                            )
                            DetailRow(
                                label = "ASR",
                                value = listOfNotNull(card.asrProvider, card.asrModel, card.asrLanguage)
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(" / ")
                                    ?: "-"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = ZtCaption
        )
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZtCaption,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TopicStatusBadge(status: String) {
    val label = topicStatusLabel(status)
    val color = when (status) {
        "recording" -> ZtRecording
        "failed" -> ZtRecording
        else -> ZtCaption
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun buildPendingTopicCards(
    recordings: List<AmbientRecordingEntry>,
    sessions: List<SessionSummary>,
    loadedCards: List<TranscriptCard>,
    topicChildIds: Set<String>
): List<TopicFeedCard> {
    val pendingCards = mutableListOf<TopicFeedCard>()
    if (recordings.isEmpty() && sessions.isEmpty()) return emptyList()

    val sessionById = sessions.associateBy { it.id }
    val loadedCardById = loadedCards.associateBy { it.id }
    val recordingSessionIds = recordings.mapNotNull { it.sessionId }.toSet()

    fun addPendingCard(
        sessionId: String?,
        createdAtEpochMs: Long,
        durationSeconds: Int,
        status: String
    ) {
        if (sessionId != null && topicChildIds.contains(sessionId)) return
        val effectiveStatus = resolveStuckPendingStatus(status, createdAtEpochMs)

        val existingCard = sessionId?.let { loadedCardById[it] }
        val cardId = sessionId ?: "pending_${createdAtEpochMs}"
        val fallbackDisplayTitle = formatEpochTime(createdAtEpochMs)
        val fallbackDisplayDate = formatEpochDate(createdAtEpochMs)
        val card = existingCard ?: run {
            val displayStatus = when (effectiveStatus) {
                "pending", "uploaded", "transcribing", "generating" -> "データ取得中"
                "transcribed", "completed" -> "判別不可"
                "failed" -> "失敗"
                else -> "データ取得中"
            }
            val isProcessing = effectiveStatus in setOf("pending", "uploaded", "transcribing", "generating")
            val isUnintelligible = effectiveStatus in setOf("transcribed", "completed")
            val displayText = when (effectiveStatus) {
                "transcribed", "completed" -> UNINTELLIGIBLE_CARD_TEXT
                "failed" -> "処理に失敗しました"
                else -> "データ取得中..."
            }
            val visualStatus = when (effectiveStatus) {
                "failed" -> "failed"
                "transcribed", "completed" -> "transcribed"
                "uploaded", "transcribing", "generating" -> effectiveStatus
                else -> "transcribing"
            }

            TranscriptCard(
                id = cardId,
                createdAt = "",
                createdAtEpochMs = createdAtEpochMs,
                status = visualStatus,
                displayStatus = displayStatus,
                isProcessing = isProcessing,
                text = displayText,
                displayTitle = fallbackDisplayTitle,
                durationSeconds = durationSeconds,
                displayDate = fallbackDisplayDate,
                speakerSegments = emptyList(),
                isUnintelligible = isUnintelligible
            )
        }

        val topicId = "pending_topic_${cardId}"
        val topicStatus = when (card.status) {
            "failed" -> "failed"
            "uploaded", "transcribing", "generating", "recording", "pending" -> "processing"
            else -> "finalized"
        }
        val title = when {
            card.status == "failed" -> "処理に失敗しました"
            card.isProcessing -> "データ取得中"
            card.isUnintelligible -> UNINTELLIGIBLE_TOPIC_TITLE
            card.text.isNotBlank() ->
                card.text.take(24)
            else -> "カード"
        }
        val summary = when {
            card.isProcessing -> ""
            card.isUnintelligible -> UNINTELLIGIBLE_TOPIC_SUMMARY
            card.text.isNotBlank() && card.text != "処理に失敗しました" ->
                card.text.take(120)
            else -> ""
        }

        pendingCards.add(
            TopicFeedCard(
                id = topicId,
                status = topicStatus,
                title = title,
                summary = summary,
                utteranceCount = 1,
                updatedAtEpochMs = card.createdAtEpochMs.takeIf { it > 0 } ?: createdAtEpochMs,
                displayDate = card.displayDate.ifBlank { fallbackDisplayDate },
                utterances = listOf(card),
                isUnintelligible = card.isUnintelligible
            )
        )
    }

    recordings.forEach { entry ->
        val sessionId = entry.sessionId
        val summary = sessionId?.let { sessionById[it] }
        if (sessionId != null && summary == null) return@forEach
        val status = summary?.status ?: if (sessionId == null) "pending" else "uploaded"
        val epochFromSummary = parseIsoEpochMillis(summary?.recorded_at ?: summary?.created_at)
        val createdAtEpoch = epochFromSummary ?: entry.createdAt
        val durationSeconds = summary?.duration_seconds ?: (entry.durationMs / 1000L).toInt()
        addPendingCard(sessionId, createdAtEpoch, durationSeconds, status)
    }

    sessions.forEach { summary ->
        if (summary.id in topicChildIds) return@forEach
        if (summary.id in recordingSessionIds) return@forEach
        val status = summary.status
        if (status !in setOf("uploaded", "transcribing", "generating", "transcribed", "failed")) return@forEach
        val createdAtEpoch = parseIsoEpochMillis(summary.recorded_at ?: summary.created_at)
        val epoch = createdAtEpoch ?: return@forEach
        val durationSeconds = summary.duration_seconds ?: 0
        addPendingCard(summary.id, epoch, durationSeconds, status)
    }

    return pendingCards
}

/**
 * Stage 3 indicator: Topic is cooling / being finalized.
 * "Analyzing conversation..." with pulsing animation.
 */
@Composable
private fun TopicAnalyzingLabel(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "topic_analyze")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "analyze_alpha"
    )
    Row(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Processing",
            style = MaterialTheme.typography.bodySmall,
            color = ZtCaption
        )
        AnimatedProcessingDots()
    }
}

// --- Filter Chip Row ---

@Composable
private fun ImportanceFilterRow(
    showFavoritesOnly: Boolean,
    enabledLevels: Set<Int>,
    onToggleLevel: (Int) -> Unit,
    onSelectAll: () -> Unit
) {
    if (showFavoritesOnly) return

    val allLevels = remember { (0..5).toList() }
    val isAllSelected = enabledLevels.size == allLevels.size

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "filter_all") {
            FilterChip(
                selected = isAllSelected,
                onClick = onSelectAll,
                label = { Text(text = "All", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ZtFilterSelected,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = ZtCaption
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isAllSelected,
                    borderColor = ZtFilterBorder,
                    selectedBorderColor = ZtOnSurfaceVariant.copy(alpha = 0.4f),
                    borderWidth = 0.5.dp,
                    selectedBorderWidth = 0.5.dp
                ),
                shape = RoundedCornerShape(6.dp)
            )
        }

        items(allLevels) { level ->
            val isSelected = level in enabledLevels
            FilterChip(
                selected = isSelected,
                onClick = { onToggleLevel(level) },
                label = { Text(text = "Lv.$level", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ZtFilterSelected,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = ZtCaption
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ZtFilterBorder,
                    selectedBorderColor = ZtOnSurfaceVariant.copy(alpha = 0.4f),
                    borderWidth = 0.5.dp,
                    selectedBorderWidth = 0.5.dp
                ),
                shape = RoundedCornerShape(6.dp)
            )
        }
    }
}

// --- Sub-composables ---

@Composable
private fun HomeDateHeader(
    selectedDate: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenCalendar: () -> Unit,
    canGoNext: Boolean,
    topicCount: Int,
    isAmbientOn: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.ChevronLeft,
                    contentDescription = "前日",
                    tint = ZtOnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenCalendar,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CalendarToday,
                            contentDescription = "カレンダーを開く",
                            tint = ZtOnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = formatHomeDateLabel(selectedDate),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatHomeSubDate(selectedDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                    Text(
                        text = "トピック ${topicCount}件",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                    if (isAmbientOn) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(ZtRecording, CircleShape)
                            )
                            Text(
                                text = "Live",
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtCaption
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "翌日",
                    tint = if (canGoNext) ZtOnSurfaceVariant else ZtCaption.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DataDateCalendarDialog(
    selectedDate: LocalDate,
    currentMonth: YearMonth,
    availableDates: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val monthCells = remember(currentMonth) { buildMonthCells(currentMonth) }
    val earliestAvailable = availableDates.minOrNull()
    val minMonth = earliestAvailable?.let { YearMonth.from(it) } ?: YearMonth.from(today.minusYears(1))
    val maxMonth = YearMonth.from(today)
    val canGoPrev = currentMonth.isAfter(minMonth)
    val canGoNext = currentMonth.isBefore(maxMonth)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onMonthChange(currentMonth.minusMonths(1)) },
                        enabled = canGoPrev,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            contentDescription = "前の月",
                            tint = if (canGoPrev) ZtOnSurfaceVariant else ZtCaption.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = currentMonth.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.JAPAN)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = { onMonthChange(currentMonth.plusMonths(1)) },
                        enabled = canGoNext,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = "次の月",
                            tint = if (canGoNext) ZtOnSurfaceVariant else ZtCaption.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("日", "月", "火", "水", "木", "金", "土").forEach { label ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtCaption
                            )
                        }
                    }
                }

                monthCells.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            CalendarDateCell(
                                date = date,
                                selectedDate = selectedDate,
                                today = today,
                                hasData = date != null && availableDates.contains(date),
                                onSelectDate = onSelectDate
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "閉じる",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CalendarDateCell(
    date: LocalDate?,
    selectedDate: LocalDate,
    today: LocalDate,
    hasData: Boolean,
    onSelectDate: (LocalDate) -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (date == null) return

        val isSelected = date == selectedDate
        val isFuture = date.isAfter(today)
        val dayTextColor = when {
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            isFuture -> ZtCaption.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.onSurface
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .then(
                    if (isSelected) {
                        Modifier.background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = !isFuture) { onSelectDate(date) }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = dayTextColor
            )
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .background(
                        color = when {
                            !hasData || isFuture -> MaterialTheme.colorScheme.surface
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> ZtRecording
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun EmptyStateView(showFavoritesOnly: Boolean, selectedDate: LocalDate) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (showFavoritesOnly) "保存した項目はありません" else "まだ文字起こしがありません",
                style = MaterialTheme.typography.titleMedium,
                color = ZtOnSurfaceVariant
            )
            Text(
                text = if (showFavoritesOnly && selectedDate == LocalDate.now()) {
                    "ブックマークするとここに表示されます"
                } else if (showFavoritesOnly) {
                    "${formatHomeDateLabel(selectedDate)} の保存項目はありません"
                } else {
                    "${formatHomeDateLabel(selectedDate)} の会話記録はありません"
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
    }
}

// --- Utility functions ---

private fun dateFromEpoch(epochMs: Long): LocalDate? {
    if (epochMs <= 0L) return null
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun formatHomeDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今日"
        today.minusDays(1) -> "昨日"
        else -> date.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN))
    }
}

private fun formatHomeSubDate(date: LocalDate): String {
    return date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.JAPAN))
}

private fun buildMonthCells(month: YearMonth): List<LocalDate?> {
    val firstDay = month.atDay(1)
    val firstOffset = firstDay.dayOfWeek.value % 7 // Sunday = 0
    val totalDays = month.lengthOfMonth()
    val cells = mutableListOf<LocalDate?>()

    repeat(firstOffset) { cells.add(null) }
    for (day in 1..totalDays) {
        cells.add(month.atDay(day))
    }
    while (cells.size % 7 != 0) {
        cells.add(null)
    }
    return cells
}

private fun parseIsoEpochMillis(timestamp: String?): Long? {
    if (timestamp.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(timestamp.trim()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

private const val STUCK_PENDING_TIMEOUT_MS = 20 * 60_000L

private fun resolveStuckPendingStatus(status: String, createdAtEpochMs: Long): String {
    if (status !in setOf("pending", "uploaded", "transcribing", "generating")) {
        return status
    }
    if (createdAtEpochMs <= 0L) return status
    val ageMs = System.currentTimeMillis() - createdAtEpochMs
    return if (ageMs >= STUCK_PENDING_TIMEOUT_MS) "failed" else status
}

private fun formatEpochTime(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun formatEpochDateTime(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPAN))
}

private fun formatEpochDate(epochMs: Long): String {
    val localDate = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val today = LocalDate.now()
    return when (localDate) {
        today -> "今日"
        today.minusDays(1) -> "昨日"
        else -> localDate.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN))
    }
}

private fun topicStatusLabel(status: String): String {
    return when (status) {
        "recording" -> "Recording"
        "active" -> "Live"
        "cooling" -> "Finalizing"
        "processing" -> "Processing"
        "failed" -> "Failed"
        "finalized" -> "Done"
        else -> status
    }
}

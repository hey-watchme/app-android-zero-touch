package com.example.zero_touch.ui

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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.delay
import com.example.zero_touch.api.SessionSummary
import com.example.zero_touch.audio.ambient.AmbientRecordingEntry
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.components.AmbientStatusBar
import com.example.zero_touch.ui.components.AnimatedProcessingDots
import com.example.zero_touch.ui.components.CardDetailSheet
import com.example.zero_touch.ui.components.ShimmerCardList
import com.example.zero_touch.ui.components.SideDetailDrawer
import com.example.zero_touch.ui.components.TranscriptCardView
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtCardRowDivider
import com.example.zero_touch.ui.theme.ZtError
import com.example.zero_touch.ui.theme.ZtFilterBorder
import com.example.zero_touch.ui.theme.ZtFilterSelected
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutline
import com.example.zero_touch.ui.theme.ZtPrimary
import com.example.zero_touch.ui.theme.ZtTopicBorder
import com.example.zero_touch.ui.theme.ZtRecording
import com.example.zero_touch.ui.theme.ZtTopicSurface
import com.example.zero_touch.ui.theme.ZtWarning
import androidx.compose.runtime.snapshotFlow
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

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
    onLoadMore: () -> Unit,
    onRetranscribeEnglish: (String) -> Unit,
    onRetryTranscribe: (String) -> Unit
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val ambientState by AmbientStatus.state.collectAsState()

    // --- Filter state ---
    var activeFilter by remember { mutableStateOf("すべて") }
    val filterOptions = listOf("すべて", "今日", "ライブ", "完了")

    // --- Data preparation ---
    val topicCards = uiState.topicCards
    val favoriteIds = uiState.favoriteIds
    val realTopicChildren = topicCards.flatMap { it.utterances }
    val topicChildIds = realTopicChildren.map { it.id }.toSet()
    val pendingTopicCards = buildPendingTopicCards(
        recordings = ambientState.recordings,
        sessions = uiState.sessions,
        topicChildIds = topicChildIds
    )
    val mergedTopicCards = (topicCards + pendingTopicCards)
        .sortedByDescending { it.updatedAtEpochMs }
    val visibleTopicCards = if (showFavoritesOnly) {
        emptyList()
    } else {
        when (activeFilter) {
            "今日" -> mergedTopicCards.filter { it.displayDate == "今日" }
            "ライブ" -> topicCards.filter { it.status == "active" }
            "完了" -> topicCards.filter { it.status == "finalized" }
            else -> mergedTopicCards
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading, visibleTopicCards.size) {
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

    // --- Main UI ---
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(4.dp))

            // Ambient status bar
            AmbientStatusBar(
                ambientState = ambientState,
                isEnabled = ambientEnabled
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
                SearchAndFilterRow(
                    showFavoritesOnly = showFavoritesOnly,
                    filterOptions = filterOptions,
                    activeFilter = activeFilter,
                    onFilterSelected = { activeFilter = it },
                    topicCount = topicCards.size
                )
            }

            // Feed content
            val isRecording = ambientState.isRecording

            // Placeholder topic for live recording state
            val liveRecordingTopic = if (isRecording) {
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
                            displayDate = "今日"
                        )
                    )
                )
            } else null

            if (uiState.isLoading && visibleTopicCards.isEmpty() && !isRecording) {
                item(key = "shimmer") {
                    ShimmerCardList(count = 4)
                }
            } else if (visibleTopicCards.isEmpty() && !isRecording) {
                item(key = "empty_state") {
                    EmptyStateView(showFavoritesOnly = showFavoritesOnly)
                }
            } else {
                val groupedTopics = visibleTopicCards.groupBy { it.displayDate.ifEmpty { "不明" } }
                val hasTodayGroup = groupedTopics.containsKey("今日")

                // If recording but no "Today" group exists, create one with the live topic
                if (isRecording && !hasTodayGroup && liveRecordingTopic != null) {
                    item(key = "header_Today") {
                        DateHeader("今日", 1)
                    }
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

                groupedTopics.forEach { (dateLabel, topics) ->
                    item(key = "header_$dateLabel") {
                        DateHeader(dateLabel, topics.size + if (dateLabel == "今日" && isRecording) 1 else 0)
                    }
                    // Insert live recording topic at the top of "Today" group
                    if (dateLabel == "今日" && isRecording && liveRecordingTopic != null) {
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
                    items(topics, key = { it.id }) { topic ->
                        TopicGroupCard(
                            topic = topic,
                            favoriteIds = favoriteIds,
                            onOpenTopic = { selectedTopicId = it.id },
                            onSelectCard = onSelectCard,
                            onToggleFavorite = onToggleFavorite
                        )
                    }
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

        if (selectedTopic != null) {
            TopicDetailDrawer(
                topic = selectedTopic,
                onClose = { selectedTopicId = null }
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
    val highlightAlpha = remember(topic.id) { androidx.compose.animation.core.Animatable(0f) }
    var lastStatus by remember(topic.id) { mutableStateOf(topic.status) }

    // Topic-level entry animation
    val topicScale = remember { androidx.compose.animation.core.Animatable(0.95f) }
    val topicAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(topic.id) {
        topicAlpha.animateTo(1f, animationSpec = tween(250, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(topic.id) {
        topicScale.animateTo(1f, animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ))
    }
    LaunchedEffect(topic.status) {
        if (lastStatus != topic.status) {
            if (topic.status == "finalized" && lastStatus != "finalized") {
                highlightAlpha.snapTo(0.35f)
                highlightAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 2800, easing = FastOutSlowInEasing)
                )
            }
            lastStatus = topic.status
        }
    }

    // Shimmer for skeleton placeholders in live topic
    val shimmerTransition = rememberInfiniteTransition(label = "topic_shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_a"
    )

    val topicBg = when {
        isLiveTopic -> ZtWarning.copy(alpha = 0.06f)
        isFailedTopic -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else -> ZtTopicSurface
    }
    val topicContentAlpha = if (isFailedTopic) 0.7f else 1f
    val highlightColor = Color(0xFFFFF2BF)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = topicScale.value
                scaleY = topicScale.value
                alpha = topicAlpha.value * topicContentAlpha
            },
        shape = RoundedCornerShape(10.dp),
        color = topicBg,
        shadowElevation = 0.5.dp
    ) {
        Box {
            if (highlightAlpha.value > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(highlightColor.copy(alpha = highlightAlpha.value))
                )
            }
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                )
            ) {
                // Topic header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = topicBg,
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
                            // Skeleton title placeholder
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .background(
                                        ZtWarning.copy(alpha = shimmerAlpha),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        } else {
                            Text(
                                text = topic.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "${topic.utteranceCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtOnSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Summary area
                when {
                    // Live recording — skeleton summary
                    isLiveTopic -> {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp)
                                .fillMaxWidth(0.55f)
                                .height(10.dp)
                                .background(
                                    ZtWarning.copy(alpha = shimmerAlpha * 0.7f),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                    // Cooling = just stopped talking, topic being finalized
                    (topic.status == "cooling" || topic.status == "processing") && topic.summary.isBlank() -> {
                        TopicAnalyzingLabel(
                            modifier = Modifier.padding(horizontal = 12.dp)
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
                            modifier = Modifier.padding(horizontal = 12.dp)
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

@Composable
private fun TopicDetailDrawer(
    topic: TopicFeedCard,
    onClose: () -> Unit
) {
    SideDetailDrawer(
        title = "トピック詳細",
        onClose = onClose
    ) {
        DetailSection(title = "タイトル") {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        DetailSection(title = "説明") {
            Text(
                text = if (topic.summary.isBlank()) "説明はまだありません" else topic.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = ZtOnSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        DetailSection(title = "メタデータ") {
            DetailRow(label = "トピックID", value = topic.id)
            DetailRow(label = "ステータス", value = topicStatusLabel(topic.status))
            DetailRow(label = "発話数", value = topic.utteranceCount.toString())
            DetailRow(label = "更新日時", value = formatEpochDateTime(topic.updatedAtEpochMs))
            DetailRow(label = "解析日時", value = formatEpochDateTime(topic.updatedAtEpochMs))
            DetailRow(
                label = "LLM",
                value = listOfNotNull(topic.llmProvider, topic.llmModel)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" / ")
                    ?: "不明"
            )
        }

        DetailSection(title = "カード") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                topic.utterances.forEach { card ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    color = if (card.status == "failed") ZtError else ZtWarning
                                )
                            }
                            Text(
                                text = card.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            DetailRow(
                                label = "長さ",
                                value = if (card.durationSeconds > 0) "${card.durationSeconds}秒" else "-"
                            )
                            DetailRow(
                                label = "録音日時",
                                value = formatEpochDateTime(card.createdAtEpochMs)
                            )
                            DetailRow(
                                label = "ステータス",
                                value = card.displayStatus
                            )
                            DetailRow(
                                label = "ASR",
                                value = listOfNotNull(card.asrProvider, card.asrModel, card.asrLanguage)
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(" / ")
                                    ?: "不明"
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
        "active" -> ZtPrimary
        "cooling" -> MaterialTheme.colorScheme.tertiary
        "processing" -> MaterialTheme.colorScheme.tertiary
        "failed" -> ZtError
        "finalized" -> ZtCaption
        else -> ZtCaption
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

private fun buildPendingTopicCards(
    recordings: List<AmbientRecordingEntry>,
    sessions: List<SessionSummary>,
    topicChildIds: Set<String>
): List<TopicFeedCard> {
    val pendingCards = mutableListOf<TopicFeedCard>()
    if (recordings.isEmpty() && sessions.isEmpty()) return emptyList()

    val sessionById = sessions.associateBy { it.id }
    val recordingSessionIds = recordings.mapNotNull { it.sessionId }.toSet()

    fun addPendingCard(
        sessionId: String?,
        createdAtEpochMs: Long,
        durationSeconds: Int,
        status: String
    ) {
        if (sessionId != null && topicChildIds.contains(sessionId)) return

        val displayStatus = when (status) {
            "pending" -> "データ取得中"
            "uploaded" -> "データ取得中"
            "transcribing" -> "データ取得中"
            "generating" -> "データ取得中"
            "failed" -> "失敗"
            else -> "データ取得中"
        }
        val isProcessing = status != "failed"
        val displayText = if (status == "failed") "処理に失敗しました" else "データ取得中..."
        val cardId = sessionId ?: "pending_${createdAtEpochMs}"
        val displayTitle = formatEpochTime(createdAtEpochMs)
        val displayDate = formatEpochDate(createdAtEpochMs)
        val visualStatus = when (status) {
            "failed" -> "failed"
            "uploaded", "transcribing", "generating" -> status
            else -> "transcribing"
        }

        val card = TranscriptCard(
            id = cardId,
            createdAt = "",
            createdAtEpochMs = createdAtEpochMs,
            status = visualStatus,
            displayStatus = displayStatus,
            isProcessing = isProcessing,
            text = displayText,
            displayTitle = displayTitle,
            durationSeconds = durationSeconds,
            displayDate = displayDate
        )

        val topicId = "pending_topic_${cardId}"
        val topicStatus = if (status == "failed") "failed" else "processing"
        val title = if (status == "failed") "処理に失敗しました" else "データ取得中"

        pendingCards.add(
            TopicFeedCard(
                id = topicId,
                status = topicStatus,
                title = title,
                summary = "",
                utteranceCount = 1,
                updatedAtEpochMs = createdAtEpochMs,
                displayDate = displayDate,
                utterances = listOf(card)
            )
        )
    }

    recordings.forEach { entry ->
        val sessionId = entry.sessionId
        val summary = sessionId?.let { sessionById[it] }
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
            text = "データ取得中",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        AnimatedProcessingDots()
    }
}

// --- Filter Chip Row ---

@Composable
private fun SearchAndFilterRow(
    showFavoritesOnly: Boolean,
    filterOptions: List<String>,
    activeFilter: String,
    onFilterSelected: (String) -> Unit,
    topicCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchBar(
            modifier = Modifier.weight(if (showFavoritesOnly) 1f else 0.48f)
        )

        if (!showFavoritesOnly) {
            FilterChipRow(
                modifier = Modifier.weight(0.52f),
                options = filterOptions,
                activeFilter = activeFilter,
                onFilterSelected = onFilterSelected,
                topicCount = topicCount
            )
        }
    }
}

@Composable
private fun FilterChipRow(
    modifier: Modifier = Modifier,
    options: List<String>,
    activeFilter: String,
    onFilterSelected: (String) -> Unit,
    topicCount: Int
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(options) { option ->
            val isSelected = option == activeFilter
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(option) },
                label = {
                    Text(
                        text = if (option == "すべて") "すべて ($topicCount)" else option,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ZtFilterSelected,
                    selectedLabelColor = ZtPrimary,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = ZtOnSurfaceVariant
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ZtFilterBorder,
                    selectedBorderColor = ZtPrimary.copy(alpha = 0.3f),
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
private fun SearchBar(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "検索",
                tint = ZtCaption,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "文字起こしを検索...",
                style = MaterialTheme.typography.bodyMedium,
                color = ZtCaption,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
private fun DateHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = ZtOnSurfaceVariant
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = ZtCaption
        )
    }
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (showFavoritesOnly) "保存した項目はありません" else "まだ文字起こしがありません",
                style = MaterialTheme.typography.titleMedium,
                color = ZtOnSurfaceVariant
            )
            Text(
                text = if (showFavoritesOnly) {
                    "ブックマークするとここに表示されます"
                } else {
                    "アンビエントをオンにして会話を記録してください"
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
    }
}

// --- Utility functions ---

private fun parseIsoEpochMillis(timestamp: String?): Long? {
    if (timestamp.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(timestamp.trim()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
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
        "recording" -> "録音"
        "active" -> "ライブ"
        "cooling" -> "整理中"
        "processing" -> "分析中"
        "failed" -> "失敗"
        "finalized" -> "完了"
        else -> "不明"
    }
}

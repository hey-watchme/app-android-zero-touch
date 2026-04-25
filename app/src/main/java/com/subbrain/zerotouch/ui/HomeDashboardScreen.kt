package com.subbrain.zerotouch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subbrain.zerotouch.api.FactSummary
import com.subbrain.zerotouch.api.SessionSummary
import com.subbrain.zerotouch.api.WikiPage
import com.subbrain.zerotouch.api.ZeroTouchApi
import com.subbrain.zerotouch.audio.ambient.AmbientRecordingEntry
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import com.subbrain.zerotouch.ui.components.CardDetailSheet
import com.subbrain.zerotouch.ui.theme.ZtBackground
import com.subbrain.zerotouch.ui.theme.ZtBlack
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtError
import com.subbrain.zerotouch.ui.theme.ZtOnBackground
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtPrimary
import com.subbrain.zerotouch.ui.theme.ZtPrimaryContainer
import com.subbrain.zerotouch.ui.theme.ZtRecording
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeApi = ZeroTouchApi()

@Composable
fun HomeDashboardScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    ambientEnabled: Boolean,
    workspaceLabel: String,
    deviceLabel: String,
    onToggleAmbient: (Boolean) -> Unit,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onDismissDetail: () -> Unit
) {
    val ambientState by AmbientStatus.state.collectAsState()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val topicChildIds = remember(uiState.topicCards) {
        uiState.topicCards.flatMap { it.utterances }.map { it.id }.toSet()
    }
    val pendingTopicCards = remember(
        ambientState.recordings,
        uiState.sessions,
        uiState.feedCards,
        topicChildIds
    ) {
        buildHomePendingTopicCards(
            recordings = ambientState.recordings,
            sessions = uiState.sessions,
            loadedCards = uiState.feedCards,
            topicChildIds = topicChildIds
        )
    }
    val liveRecordingTopic = remember(ambientState.isRecording, ambientState.recordingElapsedMs) {
        if (!ambientState.isRecording) null
        else TopicFeedCard(
            id = "live_recording",
            status = "recording",
            title = "録音中",
            summary = "音声を取得しています。発話が終わるとアップロードと文字起こしに進みます。",
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
                    text = "録音中...",
                    displayTitle = "--:--",
                    durationSeconds = (ambientState.recordingElapsedMs / 1000L).toInt(),
                    displayDate = "今日"
                )
            )
        )
    }
    val displayTopics = remember(liveRecordingTopic, uiState.topicCards, pendingTopicCards) {
        (listOfNotNull(liveRecordingTopic) + uiState.topicCards + pendingTopicCards)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    var selectedTopicId by remember(displayTopics) {
        mutableStateOf(
            displayTopics.firstOrNull { it.status == "active" }?.id
                ?: displayTopics.firstOrNull()?.id
        )
    }
    val selectedTopic = selectedTopicId?.let { id ->
        displayTopics.find { it.id == id }
    } ?: displayTopics.firstOrNull()

    val selectedCard = uiState.selectedCardId?.let { id ->
        uiState.topicCards.flatMap { it.utterances }.find { it.id == id }
            ?: uiState.feedCards.find { it.id == id }
    }
    if (selectedCard != null) {
        CardDetailSheet(
            card = selectedCard,
            isFavorite = uiState.favoriteIds.contains(selectedCard.id),
            onDismiss = onDismissDetail,
            onToggleFavorite = { onToggleFavorite(selectedCard.id) },
            onDelete = { onDeleteCard(selectedCard.id) },
            onCopy = { clipboardManager.setText(AnnotatedString(selectedCard.text)) },
            onRetranscribeEnglish = {},
            onRetryTranscribe = {}
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZtBackground)
    ) {
        AmbientPerformanceStrip(
            ambientEnabled = ambientEnabled,
            isRecording = ambientState.isRecording,
            isSpeech = ambientState.speech,
            ambientLevel = ambientState.ambientLevel,
            voiceLevel = ambientState.voiceLevel,
            elapsedMs = ambientState.recordingElapsedMs,
            latestText = selectedTopic?.utterances?.firstOrNull()?.text,
            workspaceLabel = workspaceLabel,
            deviceLabel = deviceLabel,
            onToggleAmbient = onToggleAmbient
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ConversationColumn(
                modifier = Modifier.weight(1.05f).fillMaxHeight(),
                topics = displayTopics,
                selectedTopicId = selectedTopic?.id,
                favoriteIds = uiState.favoriteIds,
                isLoading = uiState.isLoading && displayTopics.isEmpty(),
                onSelectTopic = { selectedTopicId = it },
                onSelectCard = onSelectCard,
                onToggleFavorite = onToggleFavorite
            )

            IntakeColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                topic = selectedTopic,
                facts = selectedTopic?.let { uiState.factsByTopic[it.id] }.orEmpty(),
                topics = displayTopics,
                factsByTopic = uiState.factsByTopic,
                onSelectTopic = { selectedTopicId = it }
            )

            WikiCompactColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                deviceId = uiState.selectedDeviceId,
                selectedFacts = selectedTopic?.let { uiState.factsByTopic[it.id] }.orEmpty()
            )
        }
    }
}

@Composable
private fun AmbientPerformanceStrip(
    ambientEnabled: Boolean,
    isRecording: Boolean,
    isSpeech: Boolean,
    ambientLevel: Float,
    voiceLevel: Float,
    elapsedMs: Long,
    latestText: String?,
    workspaceLabel: String,
    deviceLabel: String,
    onToggleAmbient: (Boolean) -> Unit
) {
    val label = when {
        isRecording -> "録音中 ${formatElapsed(elapsedMs)}"
        ambientEnabled -> "聞き取り中"
        else -> "停止中"
    }
    val liveText = latestText?.takeIf { it.isNotBlank() } ?: "会話を待機しています"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ZtSurface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveDot(active = ambientEnabled, recording = isRecording || isSpeech)
            Column(modifier = Modifier.width(128.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRecording) ZtRecording else ZtOnBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOf(workspaceLabel, deviceLabel).filter { it.isNotBlank() }.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "Live transcript",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "「$liveText」",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = ZtOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AudioLevelBars(
                level = maxOf(ambientLevel, voiceLevel),
                active = ambientEnabled && (isSpeech || isRecording)
            )
            OutlinedButton(
                onClick = { onToggleAmbient(!ambientEnabled) },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (ambientEnabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (ambientEnabled) "停止" else "再開")
            }
        }
    }
}

@Composable
private fun ConversationColumn(
    modifier: Modifier,
    topics: List<TopicFeedCard>,
    selectedTopicId: String?,
    favoriteIds: Set<String>,
    isLoading: Boolean,
    onSelectTopic: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    DashboardPanel(
        modifier = modifier,
        eyebrow = "01 / CONVERSATION",
        title = "Cards / Topics",
        subtitle = "現在の会話構造"
    ) {
        if (isLoading && topics.isEmpty()) {
            LoadingBox("会話を読み込み中")
        } else if (topics.isEmpty()) {
            EmptyBox("まだトピックがありません")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(topics.take(12), key = { it.id }) { topic ->
                    CompactTopicCard(
                        topic = topic,
                        selected = topic.id == selectedTopicId,
                        favoriteIds = favoriteIds,
                        onSelect = { onSelectTopic(topic.id) },
                        onSelectCard = onSelectCard,
                        onToggleFavorite = onToggleFavorite
                    )
                }
            }
        }
    }
}

@Composable
private fun IntakeColumn(
    modifier: Modifier,
    topic: TopicFeedCard?,
    facts: List<FactSummary>,
    topics: List<TopicFeedCard>,
    factsByTopic: Map<String, List<FactSummary>>,
    onSelectTopic: (String) -> Unit
) {
    DashboardPanel(
        modifier = modifier,
        eyebrow = "02 / INTAKE",
        title = "Processing",
        subtitle = "構造化の現在地"
    ) {
        if (topic == null) {
            EmptyBox("処理対象のトピックがありません")
            return@DashboardPanel
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ZtSurfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(topicStatusLabel(topic.status))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${topic.utteranceCount} cards",
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtCaption
                            )
                            Spacer(Modifier.weight(1f))
                            topic.importanceLevel?.let { StatusChip("Lv.$it") }
                        }
                        Text(
                            text = topic.title.ifBlank { "Untitled topic" },
                            style = MaterialTheme.typography.titleMedium,
                            color = ZtOnBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (topic.summary.isNotBlank()) {
                            Text(
                                text = topic.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = ZtOnSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("Pipeline Flow", topics.size)
            }

            items(topics.take(8), key = { "flow_${it.id}" }) { flowTopic ->
                PipelineFlowRow(
                    topic = flowTopic,
                    facts = factsByTopic[flowTopic.id].orEmpty(),
                    selected = flowTopic.id == topic.id,
                    onClick = { onSelectTopic(flowTopic.id) }
                )
            }

            item {
                SectionTitle("Selected Topic Steps", 6)
            }

            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ZtSurface,
                    border = BorderStroke(1.dp, ZtOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProcessingStep("Capture", "音声を取得", done = topic.utterances.isNotEmpty())
                        ProcessingStep("ASR", "発話テキスト化", done = topic.utterances.any { it.text.isNotBlank() && !it.isProcessing })
                        ProcessingStep("Topic", "会話単位へ集約", done = true)
                        ProcessingStep("Fact", "重要情報の抽出", done = facts.isNotEmpty())
                        ProcessingStep("Wiki", "長期記憶へ反映", done = facts.isNotEmpty(), pendingText = "候補")
                        ProcessingStep("Action", "業務アクション候補", done = false, pendingText = "未実装")
                    }
                }
            }

            item {
                SectionTitle("Extracted Facts", facts.size)
            }

            if (facts.isEmpty()) {
                item { EmptyBox("このトピックのFactはまだありません") }
            } else {
                items(facts.take(8), key = { it.id }) { fact ->
                    FactRow(fact)
                }
            }
        }
    }
}

@Composable
private fun WikiCompactColumn(
    modifier: Modifier,
    deviceId: String?,
    selectedFacts: List<FactSummary>
) {
    var pages by remember { mutableStateOf<List<WikiPage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedPageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceId) {
        isLoading = true
        error = null
        try {
            val response = homeApi.listWikiPages(deviceId)
            pages = response.pages
            selectedPageId = response.pages.firstOrNull()?.id
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    val relatedPages = remember(pages, selectedFacts) {
        val tokens = selectedFacts
            .flatMap { fact -> fact.categories + fact.intents + fact.entities.orEmpty().mapNotNull { it["name"]?.toString() } }
            .map { it.lowercase() }
            .filter { it.length >= 2 }
            .toSet()
        if (tokens.isEmpty()) pages
        else pages.sortedByDescending { page ->
            val haystack = listOfNotNull(page.title, page.body, page.category, page.kind, page.page_key)
                .joinToString(" ")
                .lowercase()
            tokens.count { haystack.contains(it) }
        }
    }
    val selectedPage = relatedPages.find { it.id == selectedPageId } ?: relatedPages.firstOrNull()

    DashboardPanel(
        modifier = modifier,
        eyebrow = "03 / WIKI",
        title = "Knowledge",
        subtitle = "蓄積された現場知識"
    ) {
        when {
            isLoading -> LoadingBox("Wikiを読み込み中")
            !error.isNullOrBlank() -> ErrorBox(error ?: "Wikiの読み込みに失敗しました")
            pages.isEmpty() -> EmptyBox("Wikiページがありません")
            else -> Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(0.48f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(relatedPages.take(8), key = { it.id }) { page ->
                        WikiListRow(
                            page = page,
                            selected = page.id == selectedPage?.id,
                            onClick = { selectedPageId = page.id }
                        )
                    }
                }
                HorizontalDivider(color = ZtOutline)
                if (selectedPage != null) {
                    WikiPreview(page = selectedPage, modifier = Modifier.weight(0.52f))
                }
            }
        }
    }
}

@Composable
private fun DashboardPanel(
    modifier: Modifier,
    eyebrow: String,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
            }
            HorizontalDivider(color = ZtOutline)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun CompactTopicCard(
    topic: TopicFeedCard,
    selected: Boolean,
    favoriteIds: Set<String>,
    onSelect: () -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) ZtPrimaryContainer else ZtSurface,
        border = BorderStroke(1.dp, if (selected) ZtBlack else ZtOutline)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(topicStatusLabel(topic.status))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${topic.utteranceCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Spacer(Modifier.weight(1f))
                topic.importanceLevel?.let { StatusChip("Lv.$it") }
            }
            Text(
                text = topic.title.ifBlank { "Processing topic" },
                style = MaterialTheme.typography.bodyMedium,
                color = ZtOnBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (topic.summary.isNotBlank()) {
                Text(
                    text = topic.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            topic.utterances.take(3).forEach { card ->
                CompactUtteranceRow(
                    card = card,
                    isFavorite = favoriteIds.contains(card.id),
                    onSelect = { onSelectCard(card.id) },
                    onToggleFavorite = { onToggleFavorite(card.id) }
                )
            }
        }
    }
}

@Composable
private fun CompactUtteranceRow(
    card: TranscriptCard,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(7.dp),
        color = Color.White.copy(alpha = 0.76f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = card.displayTitle,
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                modifier = Modifier.width(42.dp),
                maxLines = 1
            )
            Text(
                text = card.text.ifBlank { card.displayStatus },
                style = MaterialTheme.typography.bodySmall,
                color = if (card.text.isBlank()) ZtCaption else ZtOnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isFavorite) "★" else "☆",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                modifier = Modifier.clickable(onClick = onToggleFavorite)
            )
        }
    }
}

@Composable
private fun ProcessingStep(
    title: String,
    detail: String,
    done: Boolean,
    pendingText: String = "待機"
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (done) ZtSurfaceVariant else Color.White,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                imageVector = if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (done) ZtPrimary else ZtCaption
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = ZtOnBackground, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = ZtCaption)
            }
            StatusChip(if (done) "DONE" else pendingText)
        }
    }
}

@Composable
private fun PipelineFlowRow(
    topic: TopicFeedCard,
    facts: List<FactSummary>,
    selected: Boolean,
    onClick: () -> Unit
) {
    val asrDone = topic.utterances.any { it.text.isNotBlank() && !it.isProcessing }
    val factDone = facts.isNotEmpty()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) ZtPrimaryContainer else ZtSurface,
        border = BorderStroke(1.dp, if (selected) ZtBlack else ZtOutline)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = topic.title.ifBlank { "Processing topic" },
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(topicStatusLabel(topic.status))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FlowNode(
                    label = "Cards",
                    value = "${topic.utteranceCount}",
                    done = topic.utterances.isNotEmpty(),
                    active = topic.status in setOf("recording", "processing", "active"),
                    modifier = Modifier.weight(1f)
                )
                FlowConnector(done = asrDone, active = topic.utterances.isNotEmpty())
                FlowNode(
                    label = "Intake",
                    value = if (factDone) "${facts.size} facts" else if (asrDone) "structuring" else "ASR",
                    done = factDone,
                    active = asrDone,
                    modifier = Modifier.weight(1.1f)
                )
                FlowConnector(done = factDone, active = asrDone)
                FlowNode(
                    label = "Wiki",
                    value = if (factDone) "candidate" else "waiting",
                    done = factDone,
                    active = factDone,
                    modifier = Modifier.weight(1f)
                )
            }
            val topFact = facts.firstOrNull()?.fact_text
            if (!topFact.isNullOrBlank()) {
                Text(
                    text = topFact,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FlowNode(
    label: String,
    value: String,
    done: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val lineProgress by animateFloatAsState(
        targetValue = when {
            done -> 1f
            active -> 0.55f
            else -> 0.12f
        },
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "node_progress"
    )
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = when {
            done -> ZtSurfaceVariant
            active -> Color.White
            else -> Color.White
        },
        border = BorderStroke(1.dp, if (active || done) ZtPrimary.copy(alpha = 0.35f) else ZtOutline)
    ) {
        Box {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done || active) ZtOnBackground else ZtOnSurfaceVariant,
                    fontWeight = if (done || active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
            ) {
                drawLine(
                    color = ZtPrimary.copy(alpha = if (active || done) 0.75f else 0.16f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width * lineProgress, size.height / 2f),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun FlowConnector(
    done: Boolean,
    active: Boolean
) {
    val progress by animateFloatAsState(
        targetValue = if (done) 1f else if (active) 0.68f else 0.2f,
        animationSpec = tween(durationMillis = 520, easing = LinearEasing),
        label = "connector_progress"
    )
    val transition = rememberInfiniteTransition(label = "connector_flow")
    val particle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connector_particle"
    )
    Canvas(
        modifier = Modifier
            .width(28.dp)
            .height(48.dp)
    ) {
        val y = size.height / 2f
        drawLine(
            color = ZtOutline,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = ZtPrimary.copy(alpha = if (active || done) 0.82f else 0.18f),
            start = Offset(0f, y),
            end = Offset(size.width * progress, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        if (active || done) {
            val x = size.width * particle
            drawCircle(
                color = ZtPrimary,
                radius = 3.2.dp.toPx(),
                center = Offset(x, y)
            )
        }
        drawLine(
            color = if (active || done) ZtPrimary else ZtCaption,
            start = Offset(size.width - 5.dp.toPx(), y - 4.dp.toPx()),
            end = Offset(size.width, y),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = if (active || done) ZtPrimary else ZtCaption,
            start = Offset(size.width - 5.dp.toPx(), y + 4.dp.toPx()),
            end = Offset(size.width, y),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun FactRow(fact: FactSummary) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = fact.fact_text,
                style = MaterialTheme.typography.bodySmall,
                color = ZtOnBackground,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                fact.categories.take(2).forEach { StatusChip(it) }
                fact.intents.take(2).forEach { StatusChip(it) }
                fact.importance_level?.let { StatusChip("Lv.$it") }
            }
        }
    }
}

@Composable
private fun WikiListRow(
    page: WikiPage,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ZtPrimaryContainer else ZtSurface,
        border = BorderStroke(1.dp, if (selected) ZtBlack else ZtOutline)
    ) {
        Row(
            modifier = Modifier.padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp), tint = ZtCaption)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnBackground,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(page.project_name, page.category, page.kind).joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WikiPreview(page: WikiPage, modifier: Modifier) {
    Column(
        modifier = modifier.padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = ZtCaption)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                letterSpacing = 0.6.sp
            )
        }
        Text(
            text = page.title,
            style = MaterialTheme.typography.titleSmall,
            color = ZtOnBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = page.body.trim().ifBlank { "No content" },
            style = MaterialTheme.typography.bodySmall,
            color = ZtOnSurfaceVariant,
            maxLines = 9,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionTitle(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ZtOnBackground,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = ZtCaption)
    }
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = ZtSurfaceVariant,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = ZtOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LiveDot(active: Boolean, recording: Boolean) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(
                color = when {
                    recording -> ZtRecording
                    active -> ZtPrimary
                    else -> ZtCaption
                },
                shape = CircleShape
            )
    )
}

@Composable
private fun AudioLevelBars(level: Float, active: Boolean) {
    Row(
        modifier = Modifier.height(18.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val weights = listOf(0.25f, 0.5f, 0.85f, 1f, 0.7f, 0.45f, 0.65f, 0.35f)
        weights.forEach { weight ->
            val height = 3.dp + (14f * level.coerceIn(0f, 1f) * weight).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .background(
                        color = ZtOnSurfaceVariant.copy(alpha = if (active) 0.75f else 0.2f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ZtBlack, strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodySmall, color = ZtCaption)
        }
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = ZtCaption)
    }
}

@Composable
private fun ErrorBox(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = ZtError)
    }
}

private fun formatElapsed(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun topicStatusLabel(status: String): String = when (status) {
    "active" -> "LIVE"
    "recording" -> "REC"
    "cooling" -> "ANALYZING"
    "processing" -> "PROCESSING"
    "finalized" -> "DONE"
    "failed" -> "FAILED"
    else -> status.ifBlank { "UNKNOWN" }.uppercase()
}

private fun buildHomePendingTopicCards(
    recordings: List<AmbientRecordingEntry>,
    sessions: List<SessionSummary>,
    loadedCards: List<TranscriptCard>,
    topicChildIds: Set<String>
): List<TopicFeedCard> {
    if (recordings.isEmpty() && sessions.isEmpty()) return emptyList()

    val sessionById = sessions.associateBy { it.id }
    val loadedCardById = loadedCards.associateBy { it.id }
    val recordingSessionIds = recordings.mapNotNull { it.sessionId }.toSet()
    val pendingCards = mutableListOf<TopicFeedCard>()

    fun addPendingCard(
        sessionId: String?,
        createdAtEpochMs: Long,
        durationSeconds: Int,
        status: String
    ) {
        if (sessionId != null && topicChildIds.contains(sessionId)) return
        val effectiveStatus = resolveHomePendingStatus(status, createdAtEpochMs)
        val existingCard = sessionId?.let { loadedCardById[it] }
        val cardId = sessionId ?: "pending_${createdAtEpochMs}"
        val displayTitle = formatHomeEpochTime(createdAtEpochMs)
        val displayDate = formatHomeEpochDate(createdAtEpochMs)
        val card = existingCard ?: TranscriptCard(
            id = cardId,
            createdAt = "",
            createdAtEpochMs = createdAtEpochMs,
            status = when (effectiveStatus) {
                "failed" -> "failed"
                "transcribed", "completed" -> "transcribed"
                "uploaded", "transcribing", "generating" -> effectiveStatus
                else -> "transcribing"
            },
            displayStatus = when (effectiveStatus) {
                "pending", "uploaded", "transcribing", "generating" -> "処理中"
                "transcribed", "completed" -> "判別不可"
                "failed" -> "失敗"
                else -> "処理中"
            },
            isProcessing = effectiveStatus in setOf("pending", "uploaded", "transcribing", "generating"),
            text = when (effectiveStatus) {
                "transcribed", "completed" -> UNINTELLIGIBLE_CARD_TEXT
                "failed" -> "処理に失敗しました"
                else -> "アップロード / 文字起こし中..."
            },
            displayTitle = displayTitle,
            durationSeconds = durationSeconds,
            displayDate = displayDate,
            isUnintelligible = effectiveStatus in setOf("transcribed", "completed")
        )

        val topicStatus = when (card.status) {
            "failed" -> "failed"
            "uploaded", "transcribing", "generating", "recording", "pending" -> "processing"
            else -> "finalized"
        }
        pendingCards.add(
            TopicFeedCard(
                id = "pending_topic_${cardId}",
                status = topicStatus,
                title = when {
                    card.status == "failed" -> "処理に失敗しました"
                    card.isProcessing -> "処理中の録音"
                    card.isUnintelligible -> UNINTELLIGIBLE_TOPIC_TITLE
                    card.text.isNotBlank() -> card.text.take(24)
                    else -> "録音"
                },
                summary = when {
                    card.isProcessing -> "録音がアップロードされ、文字起こしとTopic化を待っています。"
                    card.isUnintelligible -> UNINTELLIGIBLE_TOPIC_SUMMARY
                    card.text.isNotBlank() && card.text != "処理に失敗しました" -> card.text.take(120)
                    else -> ""
                },
                utteranceCount = 1,
                updatedAtEpochMs = card.createdAtEpochMs.takeIf { it > 0 } ?: createdAtEpochMs,
                displayDate = card.displayDate.ifBlank { displayDate },
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
        val createdAtEpoch = parseHomeIsoEpochMillis(summary?.recorded_at ?: summary?.created_at)
            ?: entry.createdAt
        val durationSeconds = summary?.duration_seconds ?: (entry.durationMs / 1000L).toInt()
        addPendingCard(sessionId, createdAtEpoch, durationSeconds, status)
    }

    sessions.forEach { summary ->
        if (summary.id in topicChildIds) return@forEach
        if (summary.id in recordingSessionIds) return@forEach
        val status = summary.status
        if (status !in setOf("uploaded", "transcribing", "generating", "transcribed", "failed")) return@forEach
        val createdAtEpoch = parseHomeIsoEpochMillis(summary.recorded_at ?: summary.created_at) ?: return@forEach
        val durationSeconds = summary.duration_seconds ?: 0
        addPendingCard(summary.id, createdAtEpoch, durationSeconds, status)
    }

    return pendingCards
}

private fun resolveHomePendingStatus(status: String, createdAtEpochMs: Long): String {
    if (status !in setOf("pending", "uploaded", "transcribing", "generating")) return status
    val ageMs = System.currentTimeMillis() - createdAtEpochMs
    return if (ageMs > 20 * 60_000L) "failed" else status
}

private fun parseHomeIsoEpochMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

private fun formatHomeEpochTime(epochMs: Long): String {
    return DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}

private fun formatHomeEpochDate(epochMs: Long): String {
    return DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}

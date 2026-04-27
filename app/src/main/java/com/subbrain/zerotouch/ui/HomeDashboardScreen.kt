package com.subbrain.zerotouch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subbrain.zerotouch.api.ActionCandidate
import com.subbrain.zerotouch.api.FactSummary
import com.subbrain.zerotouch.api.SessionSummary
import com.subbrain.zerotouch.api.WikiPage
import com.subbrain.zerotouch.api.ZeroTouchApi
import kotlinx.coroutines.launch
import com.subbrain.zerotouch.audio.ambient.AmbientRecordingEntry
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import com.subbrain.zerotouch.ui.components.CardDetailSheet
import com.subbrain.zerotouch.ui.theme.ZtBlack
import com.subbrain.zerotouch.ui.theme.ZtCanvasLeft
import com.subbrain.zerotouch.ui.theme.ZtCanvasMid
import com.subbrain.zerotouch.ui.theme.ZtCanvasRight
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtError
import com.subbrain.zerotouch.ui.theme.ZtOnBackground
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtPrimary
import com.subbrain.zerotouch.ui.theme.ZtPrimaryContainer
import com.subbrain.zerotouch.ui.theme.ZtRecording
import com.subbrain.zerotouch.ui.theme.ZtStageConvert
import com.subbrain.zerotouch.ui.theme.ZtStageConvertSoft
import com.subbrain.zerotouch.ui.theme.ZtStageDigital
import com.subbrain.zerotouch.ui.theme.ZtStageDigitalSoft
import com.subbrain.zerotouch.ui.theme.ZtStagePhysical
import com.subbrain.zerotouch.ui.theme.ZtStagePhysicalSoft
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeApi = ZeroTouchApi()

private enum class Stage(
    val number: String,
    val code: String,
    val title: String,
    val subtitle: String,
    val accent: Color,
    val accentSoft: Color
) {
    Physical(
        number = "01",
        code = "PHYSICAL",
        title = "現場の会話",
        subtitle = "非構造データ",
        accent = ZtStagePhysical,
        accentSoft = ZtStagePhysicalSoft
    ),
    Convert(
        number = "02",
        code = "CONVERT",
        title = "ZeroTouch Converter",
        subtitle = "意味のある単位へ抽出・分類",
        accent = ZtStageConvert,
        accentSoft = ZtStageConvertSoft
    ),
    Digital(
        number = "03",
        code = "DIGITAL",
        title = "デジタル成果物",
        subtitle = "業務システム下書き / 長期記憶",
        accent = ZtStageDigital,
        accentSoft = ZtStageDigitalSoft
    )
}

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
    val displayTopics = remember(uiState.homeLiveTopics, uiState.topicCards) {
        val liveIds = uiState.homeLiveTopics.map { it.id }.toSet()
        uiState.homeLiveTopics.sortedByDescending { it.updatedAtEpochMs } +
            uiState.topicCards
                .filterNot { it.id in liveIds }
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

    val coroutineScope = rememberCoroutineScope()
    var actionsRefreshKey by remember { mutableIntStateOf(0) }
    var actionGenerating by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf<String?>(null) }

    val selectedCard = uiState.selectedCardId?.let { id ->
        (uiState.homeLiveTopics + uiState.topicCards).flatMap { it.utterances }.find { it.id == id }
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

    val canvasBrush = Brush.horizontalGradient(
        0f to ZtCanvasLeft,
        0.55f to ZtCanvasMid,
        1f to ZtCanvasRight
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(canvasBrush)
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.Top
        ) {
            PhysicalColumn(
                modifier = Modifier.weight(1.0f).fillMaxHeight(),
                topics = displayTopics,
                selectedTopicId = selectedTopic?.id,
                favoriteIds = uiState.favoriteIds,
                isLoading = uiState.isLoading && displayTopics.isEmpty(),
                ambientEnabled = ambientEnabled,
                isRecording = ambientState.isRecording,
                isSpeech = ambientState.speech,
                voiceLevel = ambientState.voiceLevel,
                ambientLevel = ambientState.ambientLevel,
                recordingElapsedMs = ambientState.recordingElapsedMs,
                onSelectTopic = { selectedTopicId = it },
                onSelectCard = onSelectCard,
                onToggleFavorite = onToggleFavorite
            )
            FlowGutter(active = displayTopics.isNotEmpty())
            ConvertColumn(
                modifier = Modifier.weight(1.05f).fillMaxHeight(),
                topic = selectedTopic,
                facts = selectedTopic?.let { uiState.factsByTopic[it.id] }.orEmpty(),
                isGenerating = actionGenerating,
                statusMessage = actionStatus,
                onGenerateActions = { force ->
                    val topicId = selectedTopic?.id ?: return@ConvertColumn
                    if (actionGenerating) return@ConvertColumn
                    actionGenerating = true
                    actionStatus = "生成中…"
                    coroutineScope.launch {
                        try {
                            val response = homeApi.convertTopicToActions(
                                topicId = topicId,
                                force = force,
                            )
                            val r = response.result
                            actionStatus = when {
                                !r.ok -> r.reason?.let { "失敗: $it" } ?: "失敗しました"
                                r.reused == true -> "既存の候補を表示します"
                                (r.created ?: 0) == 0 -> "メール下書きの対象は見つかりませんでした"
                                else -> "${r.created}件の候補を生成しました"
                            }
                            actionsRefreshKey += 1
                        } catch (e: Exception) {
                            actionStatus = "失敗: ${e.message ?: "unknown"}"
                        } finally {
                            actionGenerating = false
                        }
                    }
                }
            )
            FlowGutter(
                active = selectedTopic?.let { uiState.factsByTopic[it.id]?.isNotEmpty() } == true
            )
            DigitalColumn(
                modifier = Modifier.weight(1.0f).fillMaxHeight(),
                deviceId = uiState.selectedDeviceId,
                selectedTopicId = selectedTopic?.id,
                actionRefreshKey = actionsRefreshKey,
                selectedFacts = selectedTopic?.let { uiState.factsByTopic[it.id] }.orEmpty(),
                onReviewAction = { candidateId, reviewAction ->
                    coroutineScope.launch {
                        try {
                            homeApi.reviewActionCandidate(
                                candidateId = candidateId,
                                action = reviewAction,
                            )
                            actionsRefreshKey += 1
                        } catch (_: Exception) {
                            actionsRefreshKey += 1
                        }
                    }
                }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Top: Ambient performance strip
// ════════════════════════════════════════════════════════════════════════════

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
    val statusLabel = when {
        isRecording -> "録音中 · ${formatElapsed(elapsedMs)}"
        ambientEnabled -> "聞き取り中"
        else -> "待機中"
    }
    val liveText = latestText?.takeIf { it.isNotBlank() } ?: "会話を待機しています"
    val level = maxOf(ambientLevel, voiceLevel).coerceIn(0f, 1f)
    val dbLabel = if (ambientEnabled) "−${(60 - (level * 38).toInt())} dB" else "—— dB"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ZtSurface.copy(alpha = 0.92f),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusPill(
                    label = statusLabel,
                    active = ambientEnabled,
                    recording = isRecording || isSpeech
                )
                MonoLabel("LIVE TRANSCRIPT")
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "「$liveText」",
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = ZtOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (ambientEnabled) BlinkingCaret()
                }
                AudioLevelBars(level = level, active = ambientEnabled && (isSpeech || isRecording))
                Text(
                    text = dbLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.width(48.dp)
                )
                ContextSummary(workspaceLabel = workspaceLabel, deviceLabel = deviceLabel)
                OutlinedButton(
                    onClick = { onToggleAmbient(!ambientEnabled) },
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, ZtOutline)
                ) {
                    Icon(
                        imageVector = if (ambientEnabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (ambientEnabled) "停止" else "再開",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZtOnBackground
                    )
                }
            }
            HorizontalDivider(color = ZtOutline)
        }
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean, recording: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            LiveDot(active = active, recording = recording)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (recording) ZtRecording else ZtOnBackground,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ContextSummary(workspaceLabel: String, deviceLabel: String) {
    val combined = listOf(workspaceLabel, deviceLabel).filter { it.isNotBlank() }.joinToString(" · ")
    if (combined.isBlank()) return
    Text(
        text = combined,
        style = MaterialTheme.typography.labelSmall,
        color = ZtCaption,
        letterSpacing = 0.3.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(180.dp)
    )
}

@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 540, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caret_alpha"
    )
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(13.dp)
            .alpha(alpha)
            .background(ZtOnSurfaceVariant)
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 01 / PHYSICAL — utterance-centric column
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun PhysicalColumn(
    modifier: Modifier,
    topics: List<TopicFeedCard>,
    selectedTopicId: String?,
    favoriteIds: Set<String>,
    isLoading: Boolean,
    ambientEnabled: Boolean,
    isRecording: Boolean,
    isSpeech: Boolean,
    voiceLevel: Float,
    ambientLevel: Float,
    recordingElapsedMs: Long,
    onSelectTopic: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    StagePanel(modifier = modifier, stage = Stage.Physical) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (ambientEnabled) {
                item {
                    LiveCaptureBanner(
                        isRecording = isRecording,
                        isSpeech = isSpeech,
                        voiceLevel = voiceLevel,
                        ambientLevel = ambientLevel,
                        elapsedMs = recordingElapsedMs
                    )
                }
            }
            when {
                isLoading && topics.isEmpty() -> item { LoadingBox("会話を読み込み中") }
                topics.isEmpty() && !ambientEnabled -> item { EmptyBox("まだ発話がありません") }
                else -> {
                    items(topics.take(8), key = { it.id }) { topic ->
                        PhysicalTopicGroup(
                            topic = topic,
                            selected = topic.id == selectedTopicId,
                            voiceLevel = voiceLevel,
                            favoriteIds = favoriteIds,
                            onSelect = { onSelectTopic(topic.id) },
                            onSelectCard = onSelectCard,
                            onToggleFavorite = onToggleFavorite
                        )
                    }
                    item { ContextNote() }
                }
            }
        }
    }
}

/**
 * Persistent banner at top of Physical column showing capture state.
 * Visible whenever ambient is enabled — gives can't-miss feedback that the
 * mic is hot, voice is detected, or a recording session is in progress.
 */
@Composable
private fun LiveCaptureBanner(
    isRecording: Boolean,
    isSpeech: Boolean,
    voiceLevel: Float,
    ambientLevel: Float,
    elapsedMs: Long
) {
    val accent = when {
        isRecording -> ZtRecording
        isSpeech -> ZtStagePhysical
        else -> ZtCaption
    }
    val label = when {
        isRecording -> "録音中 · ${formatElapsed(elapsedMs)}"
        isSpeech -> "音声検出"
        else -> "聞き取り中"
    }
    val detail = when {
        isRecording -> "現在の発話を取り込んでいます"
        isSpeech -> "発話を検出 — 録音を準備中"
        else -> "次の発話を待機しています"
    }
    val transition = rememberInfiniteTransition(label = "live_banner")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_banner_pulse"
    )
    val borderColor = if (isRecording || isSpeech) accent.copy(alpha = pulse) else ZtOutline
    val borderWidth = if (isRecording || isSpeech) 1.5.dp else 1.dp

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = ZtSurface,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PulsingRingDot(color = accent, active = isRecording || isSpeech, size = 11.dp)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRecording) ZtRecording else ZtOnBackground,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
                Spacer(Modifier.weight(1f))
                MonoLabel("AMBIENT")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AudioLevelBars(
                    level = maxOf(voiceLevel, ambientLevel),
                    active = isRecording || isSpeech
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PulsingRingDot(color: Color, active: Boolean, size: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "ring_dot")
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring"
    )
    Box(modifier = Modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(size + (10.dp * ring))
                    .alpha((1f - ring) * 0.55f)
                    .background(color, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .background(color, CircleShape)
        )
    }
}

@Composable
private fun PhysicalTopicGroup(
    topic: TopicFeedCard,
    selected: Boolean,
    voiceLevel: Float,
    favoriteIds: Set<String>,
    onSelect: () -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val isLive = topic.id == "home_live_input" ||
        topic.id == "live_recording" ||
        topic.status == "recording" ||
        topic.status == "speech_detected"
    val isProcessing = topic.status == "processing"

    if (isLive || isProcessing) {
        ActiveTopicCard(
            topic = topic,
            selected = selected,
            isLive = isLive,
            voiceLevel = voiceLevel,
            onSelect = onSelect,
            onSelectCard = onSelectCard
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Topic label header (compact)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (selected) 7.dp else 5.dp)
                    .background(
                        color = when (topic.status) {
                            "active" -> ZtStagePhysical
                            "cooling" -> ZtStageConvert
                            else -> ZtCaption
                        },
                        shape = CircleShape
                    )
            )
            Text(
                text = topic.title.ifBlank { "Untitled topic" },
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) ZtOnBackground else ZtOnSurfaceVariant,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = topicStatusLabel(topic.status),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                color = ZtCaption,
                fontWeight = FontWeight.Medium
            )
        }

        // Utterance cards
        topic.utterances.take(4).forEach { card ->
            UtteranceCard(
                card = card,
                topicSelected = selected,
                isFavorite = favoriteIds.contains(card.id),
                onSelect = { onSelectCard(card.id) },
                onToggleFavorite = { onToggleFavorite(card.id) }
            )
        }
    }
}

/**
 * Prominent card for live-recording or just-completed-uploading topics.
 * Red border + pulse for recording, amber + spinner for processing.
 */
@Composable
private fun ActiveTopicCard(
    topic: TopicFeedCard,
    selected: Boolean,
    isLive: Boolean,
    voiceLevel: Float,
    onSelect: () -> Unit,
    onSelectCard: (String) -> Unit
) {
    val accent = if (isLive) ZtRecording else ZtStageDigital
    val transition = rememberInfiniteTransition(label = "active_topic")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "active_pulse"
    )
    val firstUtterance = topic.utterances.firstOrNull()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(11.dp),
        color = ZtSurface,
        border = BorderStroke(1.5.dp, accent.copy(alpha = pulse))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLive) {
                    PulsingRingDot(color = accent, active = true, size = 9.dp)
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        color = accent,
                        strokeWidth = 1.8.dp
                    )
                }
                Text(
                    text = if (isLive) "録音中" else (firstUtterance?.displayStatus?.takeIf { it.isNotBlank() } ?: "処理中"),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
                )
                Spacer(Modifier.weight(1f))
                ActiveBadge(text = if (isLive) "REC" else "PROCESSING", accent = accent)
            }
            if (isLive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AudioLevelBars(level = voiceLevel, active = true)
                    Text(
                        text = "現在の発話を取り込んでいます",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    firstUtterance?.let {
                        Text(
                            text = formatDurationSec(it.durationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtOnBackground,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = topic.summary.ifBlank {
                            firstUtterance?.text?.takeIf { it.isNotBlank() } ?: "アップロード / 文字起こし中…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    firstUtterance?.let {
                        if (it.durationSeconds > 0) {
                            Text(
                                text = formatDurationSec(it.durationSeconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtCaption,
                                letterSpacing = 0.2.sp
                            )
                        }
                    }
                }
            }
            // Show transcript preview only when actual text exists (not status placeholders)
            firstUtterance?.let { card ->
                val isStatusText = card.text.isBlank() ||
                    card.text == "録音中..." ||
                    card.text.startsWith("音声をサーバー") ||
                    card.text.startsWith("アップロード") ||
                    card.text.startsWith("音声を文字起こし") ||
                    card.text.startsWith("発話内容を解析")
                if (!isStatusText) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCard(card.id) },
                        shape = RoundedCornerShape(8.dp),
                        color = accent.copy(alpha = 0.06f)
                    ) {
                        Text(
                            text = "「${card.text}」",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtOnBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveBadge(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = accent.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun formatDurationSec(durationSeconds: Int): String {
    val s = durationSeconds.coerceAtLeast(0)
    val minutes = s / 60
    val seconds = s % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun UtteranceCard(
    card: TranscriptCard,
    topicSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val borderColor = when {
        topicSelected -> ZtStagePhysical.copy(alpha = 0.3f)
        else -> ZtOutline
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(9.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SpeakerAvatar(label = inferSpeakerLabel(card))
                Text(
                    text = inferSpeakerName(card),
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = card.displayTitle.ifBlank { "--:--" },
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    letterSpacing = 0.3.sp
                )
                Text(
                    text = if (isFavorite) "★" else "☆",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFavorite) ZtOnBackground else ZtCaption,
                    modifier = Modifier.clickable(onClick = onToggleFavorite)
                )
            }
            Text(
                text = if (card.text.isBlank()) card.displayStatus else "「${card.text}」",
                style = MaterialTheme.typography.bodySmall,
                color = if (card.text.isBlank()) ZtCaption else ZtOnBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpeakerAvatar(label: String) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(ZtStagePhysical, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZtSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ContextNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MonoLabel("CONTEXT")
            Text(
                text = "現場のスタッフ・お客様の発話がそのまま入力されます。",
                style = MaterialTheme.typography.labelSmall,
                color = ZtOnSurfaceVariant
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 02 / CONVERT — Intent nodes derived from Facts
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun ConvertColumn(
    modifier: Modifier,
    topic: TopicFeedCard?,
    facts: List<FactSummary>,
    isGenerating: Boolean,
    statusMessage: String?,
    onGenerateActions: (Boolean) -> Unit
) {
    StagePanel(modifier = modifier, stage = Stage.Convert) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item { ConverterMetaStrip(active = topic != null) }
            if (topic == null) {
                item { EmptyConverter("対象のトピックを選択してください") }
                return@LazyColumn
            }

            item { TopicSnapshot(topic = topic, factCount = facts.size) }

            if (facts.isEmpty()) {
                item { ConverterPipelineSkeleton(topic = topic) }
            } else {
                items(facts.take(10), key = { it.id }) { fact ->
                    IntentNodeCard(fact = fact)
                }
            }

            item {
                ConverterActionFooter(
                    enabled = topic.status != "active",
                    isGenerating = isGenerating,
                    statusMessage = statusMessage,
                    onGenerateActions = onGenerateActions
                )
            }
        }
    }
}

@Composable
private fun ConverterMetaStrip(active: Boolean) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtStageConvert.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(ZtStageConvertSoft, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = ZtStageConvert,
                    modifier = Modifier.size(15.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (active) "リアルタイム処理中" else "待機中",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "classify · extract · route",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    letterSpacing = 0.4.sp
                )
            }
            if (active) PulseDots(color = ZtStageConvert)
        }
    }
}

@Composable
private fun TopicSnapshot(topic: TopicFeedCard, factCount: Int) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ZtSurfaceVariant,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageMicroChip(text = topicStatusLabel(topic.status), accent = Stage.Convert.accent)
                Text(
                    text = "${topic.utteranceCount} cards",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Spacer(Modifier.weight(1f))
                topic.importanceLevel?.let {
                    StageMicroChip(text = "Lv.$it", accent = ZtCaption)
                }
            }
            Text(
                text = topic.title.ifBlank { "Untitled topic" },
                style = MaterialTheme.typography.titleSmall,
                color = ZtOnBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (topic.summary.isNotBlank()) {
                Text(
                    text = topic.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtOnSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "→ $factCount intent / fact",
                style = MaterialTheme.typography.labelSmall,
                color = ZtStageConvert,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
        }
    }
}

@Composable
private fun IntentNodeCard(fact: FactSummary) {
    val intentLabel = fact.intents.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: fact.categories.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "fact"
    val confidence = (fact.importance_level ?: 0).coerceIn(0, 5) / 5f
    val entityPairs = remember(fact.entities) {
        fact.entities.orEmpty().take(4).mapNotNull { entity ->
            val name = entity["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = entity["type"]?.toString()?.takeIf { it.isNotBlank() } ?: "entity"
            type to name
        }
    }

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = ZtSurface,
        border = BorderStroke(1.5.dp, ZtStageConvert.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IntentBadge(label = intentLabel)
                Spacer(Modifier.weight(1f))
                DoneBadge()
            }
            Text(
                text = fact.fact_text,
                style = MaterialTheme.typography.bodySmall,
                color = ZtOnBackground,
                fontWeight = FontWeight.Medium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (entityPairs.isNotEmpty()) {
                EntityFieldGrid(pairs = entityPairs)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "重要度",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    letterSpacing = 0.3.sp
                )
                ConfidenceBar(progress = confidence)
                Text(
                    text = "Lv.${fact.importance_level ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtOnSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                fact.categories.firstOrNull()?.let { category ->
                    Text(
                        text = "→ $category",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtStageConvert,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun IntentBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = ZtStageConvertSoft,
        border = BorderStroke(0.dp, Color.Transparent)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ZtStageConvert,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DoneBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = ZtStagePhysicalSoft
    ) {
        Text(
            text = "DONE",
            style = MaterialTheme.typography.labelSmall,
            color = ZtOnBackground,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EntityFieldGrid(pairs: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = ZtStageConvertSoft.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, ZtStageConvert.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            pairs.forEach { (key, value) ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption,
                        letterSpacing = 0.3.sp,
                        modifier = Modifier.width(64.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnBackground,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBar(progress: Float) {
    Box(
        modifier = Modifier
            .width(60.dp)
            .height(4.dp)
            .background(ZtStageConvertSoft, RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(ZtStageConvert.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun PulseDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(4) { index ->
            val transition = rememberInfiniteTransition(label = "dot$index")
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, delayMillis = index * 110, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha$index"
            )
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 4.dp)
                    .alpha(alpha)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun ConverterPipelineSkeleton(topic: TopicFeedCard) {
    val asrDone = topic.utterances.any { it.text.isNotBlank() && !it.isProcessing }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MonoLabel("PIPELINE")
            Text(
                text = "ASR → 意図分類 → フィールド抽出 → ルーティング",
                style = MaterialTheme.typography.labelSmall,
                color = ZtOnSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepDot(label = "ASR", done = asrDone)
                StepBar(active = asrDone)
                StepDot(label = "Intent", done = false, pulsing = asrDone)
                StepBar(active = false)
                StepDot(label = "Field", done = false)
                StepBar(active = false)
                StepDot(label = "Route", done = false)
            }
            Text(
                text = if (asrDone) "Intent 分類待ちです。Fact が抽出されるとここに Intent カードが並びます。"
                else "ASR を待機しています。",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun StepDot(label: String, done: Boolean, pulsing: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val pulseAlpha = if (pulsing) {
            val t = rememberInfiniteTransition(label = "pulse_$label")
            t.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(700, easing = LinearEasing), RepeatMode.Reverse
                ),
                label = "pulse_alpha"
            ).value
        } else 1f
        Box(
            modifier = Modifier
                .size(11.dp)
                .alpha(pulseAlpha)
                .background(
                    color = if (done) ZtStageConvert else ZtStageConvertSoft,
                    shape = CircleShape
                )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (done) ZtOnBackground else ZtCaption,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
private fun StepBar(active: Boolean) {
    Box(
        modifier = Modifier
            .height(2.dp)
            .width(20.dp)
            .background(
                color = if (active) ZtStageConvert.copy(alpha = 0.6f) else ZtOutline,
                shape = RoundedCornerShape(1.dp)
            )
    )
}

@Composable
private fun ConverterActionFooter(
    enabled: Boolean,
    isGenerating: Boolean,
    statusMessage: String?,
    onGenerateActions: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtStageConvert.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Email,
                    contentDescription = null,
                    tint = ZtStageDigital,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "メール下書きを生成",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ZtStageConvert
                    )
                }
            }
            Text(
                text = "このトピックの会話から、メール返信や送信の下書きを抽出します。",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                maxLines = 2
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onGenerateActions(false) },
                    enabled = enabled && !isGenerating
                ) {
                    Text(
                        text = "Action 候補を生成",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(
                    onClick = { onGenerateActions(true) },
                    enabled = enabled && !isGenerating
                ) {
                    Text(
                        text = "再生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                }
            }
            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtStageConvert,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp
                )
            }
            if (!enabled && !isGenerating) {
                Text(
                    text = "トピックが finalize されるまで生成できません",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
            }
        }
    }
}

@Composable
private fun EmptyConverter(message: String) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = ZtCaption,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// 03 / DIGITAL — Action drafts (Email) + Wiki
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun DigitalColumn(
    modifier: Modifier,
    deviceId: String?,
    selectedTopicId: String?,
    actionRefreshKey: Int,
    selectedFacts: List<FactSummary>,
    onReviewAction: (String, String) -> Unit
) {
    var pages by remember { mutableStateOf<List<WikiPage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var actionCandidates by remember { mutableStateOf<List<ActionCandidate>>(emptyList()) }
    var actionsLoading by remember { mutableStateOf(false) }
    var actionsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceId) {
        isLoading = true
        error = null
        try {
            val response = homeApi.listWikiPages(deviceId)
            pages = response.pages
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedTopicId, actionRefreshKey) {
        if (selectedTopicId.isNullOrBlank()) {
            actionCandidates = emptyList()
            actionsLoading = false
            actionsError = null
            return@LaunchedEffect
        }
        actionsLoading = true
        actionsError = null
        try {
            val response = homeApi.listActionCandidates(
                topicId = selectedTopicId,
                limit = 20,
            )
            actionCandidates = response.candidates
        } catch (e: Exception) {
            actionsError = e.message
        } finally {
            actionsLoading = false
        }
    }

    val relatedPages = remember(pages, selectedFacts) {
        val tokens = selectedFacts
            .flatMap { fact ->
                fact.categories + fact.intents +
                    fact.entities.orEmpty().mapNotNull { it["name"]?.toString() }
            }
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

    val visibleCandidates = actionCandidates.filter { it.status == "pending" || it.status == "approved" }

    StagePanel(modifier = modifier, stage = Stage.Digital) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                SectionLabel(
                    text = "メール下書き",
                    suffix = if (visibleCandidates.isEmpty()) "未生成" else "${visibleCandidates.size}件",
                    accent = ZtStageDigital
                )
            }
            when {
                selectedTopicId.isNullOrBlank() ->
                    item { EmptyBox("トピックを選択すると下書きを生成できます") }
                actionsLoading ->
                    item { LoadingBox("候補を読み込み中") }
                !actionsError.isNullOrBlank() ->
                    item { ErrorBox(actionsError ?: "候補の読み込みに失敗しました") }
                visibleCandidates.isEmpty() ->
                    item { EmptyBox("中央レーンで「Action 候補を生成」を押してください") }
                else -> items(visibleCandidates, key = { it.id }) { candidate ->
                    EmailDraftCard(
                        candidate = candidate,
                        onApprove = { onReviewAction(candidate.id, "approve") },
                        onReject = { onReviewAction(candidate.id, "reject") }
                    )
                }
            }

            item { Spacer(Modifier.height(2.dp)) }
            item {
                SectionLabel(
                    text = "Wiki / 長期記憶",
                    suffix = "稼働中",
                    accent = ZtStagePhysical
                )
            }
            when {
                isLoading -> item { LoadingBox("Wikiを読み込み中") }
                !error.isNullOrBlank() -> item { ErrorBox(error ?: "Wikiの読み込みに失敗しました") }
                relatedPages.isEmpty() -> item { EmptyBox("まだWikiがありません") }
                else -> items(relatedPages.take(8), key = { it.id }) { page ->
                    WikiCompactRow(page = page)
                }
            }
        }
    }
}

@Composable
private fun EmailDraftCard(
    candidate: ActionCandidate,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val payload = candidate.payload.orEmpty()
    val subject = (payload["subject"] as? String).orEmpty()
    val body = (payload["body"] as? String).orEmpty()
    val recipient = (payload["recipient"] as? String)
        ?: (payload["recipient_name"] as? String)
    val tone = payload["tone"] as? String
    val sourceQuote = (candidate.sources.orEmpty()["source_quote"] as? String)
        ?: (payload["source_quote"] as? String)
    val isApproved = candidate.status == "approved"

    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = ZtSurface,
        border = BorderStroke(1.5.dp, ZtStageDigital.copy(alpha = if (isApproved) 0.55f else 0.30f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(ZtStageDigitalSoft, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = null,
                        tint = ZtStageDigital,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = "Email Draft",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtStageDigital,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
                )
                if (!tone.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ZtStageDigitalSoft.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = tone,
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtStageDigital,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (isApproved) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ZtStagePhysicalSoft
                    ) {
                        Text(
                            text = "APPROVED",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtOnBackground,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    candidate.confidence?.let { c ->
                        Text(
                            text = "${(c * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtCaption,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (!recipient.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "To",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(
                        text = recipient,
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnBackground,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (subject.isNotBlank()) {
                Text(
                    text = subject,
                    style = MaterialTheme.typography.titleSmall,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!sourceQuote.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = ZtStageDigitalSoft.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, ZtStageDigital.copy(alpha = 0.10f))
                ) {
                    Text(
                        text = "「$sourceQuote」",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val text = buildString {
                            if (subject.isNotBlank()) {
                                append("件名: ")
                                append(subject)
                                append("\n\n")
                            }
                            append(body)
                        }
                        clipboardManager.setText(AnnotatedString(text))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onReject,
                    enabled = !isApproved
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = ZtCaption
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "Reject",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                }
                OutlinedButton(
                    onClick = onApprove,
                    enabled = !isApproved
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = ZtStagePhysical
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Approve",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtStagePhysical,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun WikiCompactRow(page: WikiPage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(ZtStageDigitalSoft, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = ZtStageDigital,
                    modifier = Modifier.size(13.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtOnBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(page.project_name, page.category, page.kind)
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
                        .ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = ZtCaption,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Shared: stage panel scaffold + flow gutter
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun StagePanel(
    modifier: Modifier,
    stage: Stage,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ZtSurface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StageHeader(stage = stage)
            HorizontalDivider(color = ZtOutline)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 11.dp, vertical = 11.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun StageHeader(stage: Stage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StageBadge(stage = stage)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stage.title,
                style = MaterialTheme.typography.titleSmall,
                color = ZtOnBackground,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stage.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(stage.accent, CircleShape)
        )
    }
}

@Composable
private fun StageBadge(stage: Stage) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = stage.accent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stage.number,
                style = MaterialTheme.typography.labelSmall,
                color = ZtSurface,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp
            )
            Text(
                text = "·",
                style = MaterialTheme.typography.labelSmall,
                color = ZtSurface.copy(alpha = 0.5f)
            )
            Text(
                text = stage.code,
                style = MaterialTheme.typography.labelSmall,
                color = ZtSurface,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun FlowGutter(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "gutter")
    val particle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gutter_particle"
    )
    Canvas(
        modifier = Modifier
            .width(20.dp)
            .fillMaxHeight()
    ) {
        val centerY = size.height / 2f
        val lineColor = if (active) ZtStageConvert.copy(alpha = 0.45f)
        else ZtCaption.copy(alpha = 0.30f)
        // Subtle horizontal connecting line
        drawLine(
            color = lineColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Arrow head
        val arrowSize = 4.dp.toPx()
        drawLine(
            color = lineColor,
            start = Offset(size.width - arrowSize, centerY - arrowSize),
            end = Offset(size.width, centerY),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = lineColor,
            start = Offset(size.width - arrowSize, centerY + arrowSize),
            end = Offset(size.width, centerY),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Animated traveling particle
        if (active) {
            val px = size.width * particle
            drawCircle(
                color = ZtStageConvert,
                radius = 2.4.dp.toPx(),
                center = Offset(px, centerY)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Shared: small primitives
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(text: String, suffix: String?, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(width = 3.dp, height = 11.dp).background(accent, RoundedCornerShape(1.dp)))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = ZtOnBackground,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.weight(1f))
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                letterSpacing = 0.6.sp
            )
        }
    }
}

@Composable
private fun StageMicroChip(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = accent.copy(alpha = 0.10f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun MonoLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ZtCaption,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.0.sp
    )
}

@Composable
private fun LiveDot(active: Boolean, recording: Boolean) {
    val transition = rememberInfiniteTransition(label = "live_dot")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_dot_pulse"
    )
    val color = when {
        recording -> ZtRecording
        active -> ZtPrimary
        else -> ZtCaption
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(if (active || recording) pulse else 1f)
            .background(color, CircleShape)
    )
}

@Composable
private fun AudioLevelBars(level: Float, active: Boolean) {
    Row(
        modifier = Modifier.height(16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val weights = listOf(0.25f, 0.55f, 0.85f, 1f, 0.75f, 0.45f, 0.65f, 0.30f)
        weights.forEach { weight ->
            val height = 2.dp + (12f * level.coerceIn(0f, 1f) * weight).dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(height)
                    .background(
                        color = ZtOnSurfaceVariant.copy(alpha = if (active) 0.75f else 0.18f),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = ZtBlack, strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.labelSmall, color = ZtCaption)
        }
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = ZtCaption)
    }
}

@Composable
private fun ErrorBox(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = ZtError)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Helpers: speaker / status / time / pending topics
// ════════════════════════════════════════════════════════════════════════════

private fun inferSpeakerName(card: TranscriptCard): String {
    val first = card.speakerLabels.firstOrNull()
    if (!first.isNullOrBlank()) return first
    val seg = card.speakerSegments.firstOrNull()?.speakerLabel
    if (!seg.isNullOrBlank()) return seg
    return "話者"
}

private fun inferSpeakerLabel(card: TranscriptCard): String {
    val name = inferSpeakerName(card)
    return name.firstOrNull()?.toString() ?: "?"
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

// Per-step status text shown on the pending card so the user can see exactly
// which stage the recording is in (instead of a vague "処理中" for everything).
private fun pendingDisplayStatus(effectiveStatus: String): String = when (effectiveStatus) {
    "pending" -> "アップロード中"
    "uploaded" -> "文字起こし待ち"
    "transcribing" -> "文字起こし中"
    "generating" -> "解析中"
    "transcribed", "completed" -> "判別不可"
    "failed" -> "失敗"
    else -> "処理中"
}

private fun pendingProgressText(effectiveStatus: String): String = when (effectiveStatus) {
    "pending" -> "音声をサーバーに送信しています…"
    "uploaded" -> "アップロード完了 — 文字起こし開始を待機中"
    "transcribing" -> "音声を文字起こししています…"
    "generating" -> "発話内容を解析しています…"
    else -> "アップロード / 文字起こし中…"
}

private fun pendingTopicTitle(effectiveStatus: String): String = when (effectiveStatus) {
    "pending" -> "アップロード中の録音"
    "uploaded" -> "文字起こし待ちの録音"
    "transcribing" -> "文字起こし中の録音"
    "generating" -> "解析中の録音"
    else -> "処理中の録音"
}

private fun pendingTopicSummary(effectiveStatus: String): String = when (effectiveStatus) {
    "pending" -> "録音をサーバーへアップロードしています。"
    "uploaded" -> "アップロードが完了し、文字起こしの開始を待っています。"
    "transcribing" -> "音声を文字起こししています。完了次第ここに反映されます。"
    "generating" -> "発話内容を解析し、Topic として整理しています。"
    else -> "録音がアップロードされ、文字起こしと Topic 化を待っています。"
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
        val existingCard = sessionId?.let { loadedCardById[it] }
        val effectiveStatus = resolveHomePendingStatus(
            status = when {
                status in setOf("transcribed", "completed") &&
                    existingCard?.text?.isNotBlank() == true &&
                    !existingCard.isUnintelligible -> "generating"
                else -> status
            },
            createdAtEpochMs = createdAtEpochMs
        )
        val cardId = sessionId ?: "pending_${createdAtEpochMs}"
        val displayTitle = formatHomeEpochTime(createdAtEpochMs)
        val displayDate = formatHomeEpochDate(createdAtEpochMs)
        val card = existingCard?.let {
            if (effectiveStatus == "generating" && !it.isProcessing) {
                it.copy(
                    status = "generating",
                    displayStatus = pendingDisplayStatus("generating"),
                    isProcessing = true,
                    text = it.text.ifBlank { pendingProgressText("generating") }
                )
            } else {
                it
            }
        } ?: TranscriptCard(
            id = cardId,
            createdAt = "",
            createdAtEpochMs = createdAtEpochMs,
            status = when (effectiveStatus) {
                "failed" -> "failed"
                "transcribed", "completed" -> "transcribed"
                "uploaded", "transcribing", "generating" -> effectiveStatus
                else -> "transcribing"
            },
            displayStatus = pendingDisplayStatus(effectiveStatus),
            isProcessing = effectiveStatus in setOf("pending", "uploaded", "transcribing", "generating"),
            text = when (effectiveStatus) {
                "transcribed", "completed" -> UNINTELLIGIBLE_CARD_TEXT
                "failed" -> "処理に失敗しました"
                else -> pendingProgressText(effectiveStatus)
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
                    card.isProcessing -> pendingTopicTitle(effectiveStatus)
                    card.isUnintelligible -> UNINTELLIGIBLE_TOPIC_TITLE
                    card.text.isNotBlank() -> card.text.take(24)
                    else -> "録音"
                },
                summary = when {
                    card.isProcessing -> pendingTopicSummary(effectiveStatus)
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
        // Note: do NOT skip when sessionId is set but summary is missing.
        // That happens during the upload-complete → next-refreshSessions window
        // and would make the pending card visibly disappear. Fall back to
        // "uploaded" status so the card stays continuous through the gap.
        val status = summary?.status ?: entry.status
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

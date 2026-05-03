package com.subbrain.zerotouch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.ui.components.AmbientDot
import com.subbrain.zerotouch.ui.components.SideDetailDrawer
import com.subbrain.zerotouch.ui.theme.ZtBackground
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtOnSurface
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

private data class LivePipelineInfo(
    val transcriptProvider: String,
    val transcriptModel: String,
    val translationProvider: String,
    val translationModel: String
)

private data class ConversationPipelineInfo(
    val cardAsrProvider: String,
    val cardAsrModel: String,
    val topicLlmProvider: String,
    val topicLlmModel: String
)

@Composable
fun MemoLiveHomeScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    ambientEnabled: Boolean,
    isAmbientLive: Boolean,
    ambientLevel: Float,
    voiceLevel: Float,
    liveSessionId: String?,
    liveShareToken: String?,
    liveAsrModel: String?,
    liveTranslationModel: String?,
    liveTranscriptLatest: String?,
    liveTranscriptHistory: List<String>,
    liveTranslationLatest: String?,
    liveTranslationHistory: List<String>,
    onToggleAmbient: (Boolean) -> Unit
) {
    val confirmedLiveTopics = (
        uiState.topicCards.filter { topic ->
            topic.status == "active" || topic.status == "finalized"
        } + uiState.homeLiveTopics
    )
        .asSequence()
        .distinctBy { topic -> topic.id }
        .sortedByDescending { topic -> topic.updatedAtEpochMs }
        .toList()
    val liveLines = buildList {
        liveTranscriptHistory.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) add(trimmed)
        }
        liveTranscriptLatest?.trim()?.takeIf { it.isNotBlank() }?.let { latest ->
            if (isEmpty() || last() != latest) add(latest)
        }
    }
    val liveTranslationLines = buildList {
        liveTranslationHistory.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) add(trimmed)
        }
        liveTranslationLatest?.trim()?.takeIf { it.isNotBlank() }?.let { latest ->
            if (isEmpty() || last() != latest) add(latest)
        }
    }
    val context = LocalContext.current
    val configuredConversationAsrProvider = AmbientPreferences.getAsrProvider(context)
    val configuredConversationLlmProvider = AmbientPreferences.getLlmProvider(context)
    val configuredConversationLlmModel = AmbientPreferences.getLlmModel(context)
    val liveInfo = LivePipelineInfo(
        transcriptProvider = "openai",
        transcriptModel = liveAsrModel?.takeIf { it.isNotBlank() } ?: "gpt-4o-transcribe",
        translationProvider = "openai",
        translationModel = liveTranslationModel?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
    )
    val firstAsrCard = confirmedLiveTopics
        .flatMap { it.utterances }
        .firstOrNull { !it.asrProvider.isNullOrBlank() || !it.asrModel.isNullOrBlank() }
    val firstLlmTopic = confirmedLiveTopics
        .firstOrNull { !it.llmProvider.isNullOrBlank() || !it.llmModel.isNullOrBlank() }
    val conversationInfo = ConversationPipelineInfo(
        cardAsrProvider = firstAsrCard?.asrProvider?.takeIf { it.isNotBlank() } ?: configuredConversationAsrProvider,
        cardAsrModel = firstAsrCard?.asrModel?.takeIf { it.isNotBlank() } ?: "-",
        topicLlmProvider = firstLlmTopic?.llmProvider?.takeIf { it.isNotBlank() } ?: configuredConversationLlmProvider,
        topicLlmModel = firstLlmTopic?.llmModel?.takeIf { it.isNotBlank() } ?: configuredConversationLlmModel
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZtBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Keep the same two-pane structure even when sidebar is expanded.
            // The previous threshold (960.dp) switched to stacked layout when the
            // left menu opened and reduced available width.
            val tabletLayout = maxWidth >= 640.dp
            if (tabletLayout) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LiveTranscriptPanel(
                        modifier = Modifier
                            .weight(1.65f)
                            .fillMaxSize(),
                        liveInfo = liveInfo,
                        liveSessionId = liveSessionId,
                        liveShareToken = liveShareToken,
                        liveLines = liveLines,
                        liveTranslationLines = liveTranslationLines,
                        ambientEnabled = ambientEnabled,
                        isAmbientLive = isAmbientLive
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RecordingControlPanel(
                            modifier = Modifier.fillMaxWidth(),
                            ambientEnabled = ambientEnabled,
                            isAmbientLive = isAmbientLive,
                            ambientLevel = ambientLevel,
                            voiceLevel = voiceLevel,
                            onToggleAmbient = onToggleAmbient
                        )
                        ConversationListPanel(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            liveTopics = confirmedLiveTopics,
                            isLiveCapture = isAmbientLive,
                            conversationInfo = conversationInfo
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RecordingControlPanel(
                        modifier = Modifier.fillMaxWidth(),
                        ambientEnabled = ambientEnabled,
                        isAmbientLive = isAmbientLive,
                        ambientLevel = ambientLevel,
                        voiceLevel = voiceLevel,
                        onToggleAmbient = onToggleAmbient
                    )
                    LiveTranscriptPanel(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxWidth(),
                        liveInfo = liveInfo,
                        liveSessionId = liveSessionId,
                        liveShareToken = liveShareToken,
                        liveLines = liveLines,
                        liveTranslationLines = liveTranslationLines,
                        ambientEnabled = ambientEnabled,
                        isAmbientLive = isAmbientLive
                    )
                    ConversationListPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        liveTopics = confirmedLiveTopics,
                        isLiveCapture = isAmbientLive,
                        conversationInfo = conversationInfo
                    )
                }
            }
        }

    }
}

@Composable
private fun RecordingControlPanel(
    modifier: Modifier = Modifier,
    ambientEnabled: Boolean,
    isAmbientLive: Boolean,
    ambientLevel: Float,
    voiceLevel: Float,
    onToggleAmbient: (Boolean) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeAmbientLevelMeter(
                level = max(ambientLevel, voiceLevel),
                isActive = ambientEnabled && (isAmbientLive || ambientLevel > 0.01f || voiceLevel > 0.01f)
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AmbientDot(isEnabled = ambientEnabled, isRecording = isAmbientLive)
                Text(
                    text = when {
                        isAmbientLive -> "会話を取得中"
                        ambientEnabled -> "待機中"
                        else -> "停止中"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtOnSurface
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Listening",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtOnSurfaceVariant
                )
                Text(
                    text = if (ambientEnabled) "On" else "Off",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ambientEnabled) Color(0xFF2E7D32) else ZtOnSurfaceVariant
                )
            }
            Switch(
                checked = ambientEnabled,
                onCheckedChange = onToggleAmbient,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF34A853),
                    checkedBorderColor = Color(0xFF34A853),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD0D5DD),
                    uncheckedBorderColor = Color(0xFFD0D5DD)
                )
            )
        }
    }
}

@Composable
private fun HomeAmbientLevelMeter(level: Float, isActive: Boolean) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 150),
        label = "home_ambient_level"
    )

    Row(
        modifier = Modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        floatArrayOf(0.35f, 0.7f, 1f, 0.75f, 0.5f).forEach { weight ->
            val barHeight = 3.dp + (11f * animatedLevel * weight).dp
            val alpha = if (isActive) {
                (0.3f + (animatedLevel * 0.7f * weight)).coerceIn(0.25f, 0.95f)
            } else {
                0.18f
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(ZtOnSurface.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun LiveTranscriptPanel(
    modifier: Modifier,
    liveInfo: LivePipelineInfo,
    liveSessionId: String?,
    liveShareToken: String?,
    liveLines: List<String>,
    liveTranslationLines: List<String>,
    ambientEnabled: Boolean,
    isAmbientLive: Boolean
) {
    var showInfoDrawer by remember { mutableStateOf(false) }
    val japaneseListState = rememberLazyListState()
    val englishListState = rememberLazyListState()
    val hasTranscript = liveLines.isNotEmpty()
    val hasTranslation = liveTranslationLines.isNotEmpty()
    LaunchedEffect(liveLines.lastOrNull(), liveLines.size) {
        if (hasTranscript) {
            japaneseListState.scrollToItem((liveLines.size - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(liveTranslationLines.lastOrNull(), liveTranslationLines.size) {
        if (hasTranslation) {
            englishListState.scrollToItem((liveTranslationLines.size - 1).coerceAtLeast(0))
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        if (showInfoDrawer) {
            PipelineInfoDrawer(
                title = "Live Transcript Info",
                onClose = { showInfoDrawer = false }
            ) {
                PipelineInfoSection(title = "Live Session") {
                    PipelineInfoRow(
                        label = "Session",
                        value = liveSessionId?.takeIf { it.isNotBlank() } ?: "-"
                    )
                    PipelineInfoRow(
                        label = "Share Token",
                        value = liveShareToken?.takeIf { it.isNotBlank() } ?: "-"
                    )
                }
                PipelineInfoSection(title = "Transcription") {
                    PipelineInfoRow(label = "Provider", value = liveInfo.transcriptProvider)
                    PipelineInfoRow(label = "Model", value = liveInfo.transcriptModel)
                    PipelineInfoRow(label = "Role", value = "ライブ音声を句単位で文字起こし")
                }
                PipelineInfoSection(title = "Translation") {
                    PipelineInfoRow(label = "Provider", value = liveInfo.translationProvider)
                    PipelineInfoRow(label = "Model", value = liveInfo.translationModel)
                    PipelineInfoRow(label = "Role", value = "文字起こし結果を英訳して表示")
                }
                PipelineInfoSection(title = "Flow") {
                    PipelineInfoParagraph("音声チャンクをリアルタイム文字起こしし、その結果を別モデルで英訳します。Conversation パイプラインとは別軸の presentation surface です。")
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = ZtOnSurface,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Live Transcript",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ZtOnSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { showInfoDrawer = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Live transcript info",
                        tint = ZtOnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (liveLines.isEmpty() && liveTranslationLines.isEmpty()) {
                val standbyText = when {
                    !ambientEnabled -> "Listening is off. Turn it on to start live transcript."
                    isAmbientLive -> "録音を開始しました。話し始めるとここに文字起こしが表示されます。"
                    else -> "マイクを準備しています。まもなく文字起こしが始まります。"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = standbyText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ZtOnSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = ZtSurfaceVariant,
                        border = BorderStroke(1.dp, ZtOutline)
                    ) {
                        LazyColumn(
                            state = japaneseListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(liveLines) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = ZtOnSurface
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = ZtSurfaceVariant,
                        border = BorderStroke(1.dp, ZtOutline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "English Translation",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = ZtOnSurfaceVariant
                            )
                            if (liveTranslationLines.isEmpty()) {
                                Text(
                                    text = "Waiting for English translation.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ZtOnSurfaceVariant
                                )
                            } else {
                                LazyColumn(
                                    state = englishListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(liveTranslationLines) { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = ZtOnSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationListPanel(
    modifier: Modifier,
    liveTopics: List<TopicFeedCard>,
    isLiveCapture: Boolean = false,
    conversationInfo: ConversationPipelineInfo
) {
    var showInfoDrawer by remember { mutableStateOf(false) }
    // When ambient is recording/speech-detected, show a live placeholder at the top
    // regardless of ViewModel state – avoids the async chain that caused the delay.
    val hasLivePlaceholderInTopics = liveTopics.any { it.id == "home_live_input" }
    val showLivePlaceholder = isLiveCapture && !hasLivePlaceholderInTopics

    val orderedTopics = liveTopics.sortedByDescending { topic -> topic.updatedAtEpochMs }
    val listState = rememberLazyListState()
    val topItemKey = if (showLivePlaceholder) {
        "live_capture_placeholder"
    } else {
        orderedTopics.firstOrNull()?.id
    }
    val topItemVersion = if (showLivePlaceholder) {
        0L
    } else {
        orderedTopics.firstOrNull()?.updatedAtEpochMs ?: 0L
    }
    val totalItems = orderedTopics.size + if (showLivePlaceholder) 1 else 0

    LaunchedEffect(topItemKey, topItemVersion, totalItems) {
        if (totalItems > 0) {
            listState.scrollToItem(0)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        if (showInfoDrawer) {
            PipelineInfoDrawer(
                title = "Conversation Info",
                onClose = { showInfoDrawer = false }
            ) {
                PipelineInfoSection(title = "Card Generation") {
                    PipelineInfoRow(label = "ASR Provider", value = conversationInfo.cardAsrProvider)
                    PipelineInfoRow(label = "ASR Model", value = conversationInfo.cardAsrModel)
                    PipelineInfoParagraph("録音完了後に Card が作られ、ASR で文字起こしされます。")
                }
                PipelineInfoSection(title = "Topic Processing") {
                    PipelineInfoRow(label = "LLM Provider", value = conversationInfo.topicLlmProvider)
                    PipelineInfoRow(label = "LLM Model", value = conversationInfo.topicLlmModel)
                    PipelineInfoParagraph("文字起こし済み Card を active Topic に束ね、タイトルや要約などをこの LLM で整えます。")
                }
                PipelineInfoSection(title = "Flow") {
                    PipelineInfoParagraph("Capture -> Card -> Topic の順で処理します。まずカードが表示され、その後 LLM による Topic 整理へ進みます。")
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = ZtOnSurface,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Conversation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ZtOnSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { showInfoDrawer = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Conversation info",
                        tint = ZtOnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (!showLivePlaceholder && orderedTopics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZtSurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "確定したトピックはまだありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZtOnSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showLivePlaceholder) {
                        item(key = "live_capture_placeholder") {
                            val now = System.currentTimeMillis()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ZtSurfaceVariant,
                                border = BorderStroke(1.dp, ZtOutline)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AmbientDot(isEnabled = true, isRecording = true)
                                        Text(
                                            text = "会話を取得中",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ZtOnSurface
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = ZtSurface,
                                        border = BorderStroke(1.dp, ZtOutline)
                                    ) {
                                        Text(
                                            text = "録音中...",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ZtOnSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = formatConversationTimestamp(now),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ZtOnSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    items(orderedTopics.take(10), key = { it.id }) { topic ->
                        val isRealTopic = topic.status in listOf("active", "finalized")
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ZtSurfaceVariant,
                            border = BorderStroke(1.dp, ZtOutline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Topic header: real topics show LLM title; processing items show status label
                                Text(
                                    text = topic.title.ifBlank { if (isRealTopic) "Untitled topic" else "処理中" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZtOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Cards (utterances) inside this topic
                                topic.utterances.forEach { card ->
                                    val cardText = when {
                                        card.text.isNotBlank() && !card.isProcessing -> card.text
                                        card.isProcessing -> card.displayStatus.ifBlank { "処理中..." }
                                        else -> null
                                    }
                                    if (cardText != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ZtSurface,
                                            border = BorderStroke(1.dp, ZtOutline)
                                        ) {
                                            Text(
                                                text = cardText,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (card.isProcessing) ZtOnSurfaceVariant else ZtOnSurface,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                // For real topics with no utterances loaded, fall back to summary
                                if (isRealTopic && topic.utterances.isEmpty() && topic.summary.isNotBlank()) {
                                    Text(
                                        text = topic.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ZtOnSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = formatConversationTimestamp(topic.updatedAtEpochMs),
                                    style = MaterialTheme.typography.labelSmall,
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

@Composable
private fun PipelineInfoDrawer(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    SideDetailDrawer(title = title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

@Composable
private fun PipelineInfoSection(
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
private fun PipelineInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ZtOnSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = ZtOnSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PipelineInfoParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ZtOnSurface,
        lineHeight = 18.sp
    )
}

private fun formatConversationTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "JST"
    val tokyoZone = ZoneId.of("Asia/Tokyo")
    val zonedDateTime = Instant.ofEpochMilli(epochMs).atZone(tokyoZone)
    val today = LocalDate.now(tokyoZone)
    val time = zonedDateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN))
    return if (zonedDateTime.toLocalDate() == today) {
        "今日 $time JST"
    } else {
        zonedDateTime.format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPAN)) + " JST"
    }
}

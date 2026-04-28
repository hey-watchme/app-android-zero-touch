package com.subbrain.zerotouch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.subbrain.zerotouch.ui.components.AmbientDot
import com.subbrain.zerotouch.ui.theme.ZtBackground
import com.subbrain.zerotouch.ui.theme.ZtBlack
import com.subbrain.zerotouch.ui.theme.ZtOnSurface
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtPrimaryContainer
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant

@Composable
fun MemoLiveHomeScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    workspaceLabel: String,
    ambientEnabled: Boolean,
    ambientStatusLabel: String,
    isAmbientLive: Boolean,
    liveSessionId: String?,
    liveShareToken: String?,
    liveAsrModel: String?,
    liveTranscriptLatest: String?,
    liveTranscriptHistory: List<String>,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZtBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ZtSurface,
            border = BorderStroke(1.dp, ZtOutline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Home / MeMo Live",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ZtOnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoCueChip("Action")
                        MemoCueChip("Draft")
                        MemoCueChip("Knowledge")
                    }
                    if (!liveShareToken.isNullOrBlank()) {
                        Text(
                            text = "Share token: $liveShareToken",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!liveSessionId.isNullOrBlank()) {
                        Text(
                            text = "Live session: ${liveSessionId.take(8)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                MemoLanguageChip(label = "日本語", selected = true)
                Spacer(modifier = Modifier.width(8.dp))
                MemoLanguageChip(label = "English", selected = false)
                Spacer(modifier = Modifier.width(10.dp))

                OutlinedButton(
                    onClick = { onToggleAmbient(!ambientEnabled) },
                    border = BorderStroke(1.dp, ZtOutline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZtOnSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (ambientEnabled) "Listening On" else "Listening Off")
                }
            }
        }

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
                        liveAsrModel = liveAsrModel,
                        liveLines = liveLines,
                        ambientEnabled = ambientEnabled,
                        ambientStatusLabel = ambientStatusLabel,
                        isAmbientLive = isAmbientLive
                    )
                    ConversationListPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        liveTopics = confirmedLiveTopics
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LiveTranscriptPanel(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxWidth(),
                        liveAsrModel = liveAsrModel,
                        liveLines = liveLines,
                        ambientEnabled = ambientEnabled,
                        ambientStatusLabel = ambientStatusLabel,
                        isAmbientLive = isAmbientLive
                    )
                    ConversationListPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        liveTopics = confirmedLiveTopics
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ZtSurface,
            border = BorderStroke(1.dp, ZtOutline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AmbientDot(isEnabled = ambientEnabled, isRecording = isAmbientLive)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        isAmbientLive -> "Listening: 会話を取得中"
                        ambientEnabled -> "Listening: 待機中"
                        else -> "Listening: 停止中"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtOnSurface
                )
            }
        }
    }
}

@Composable
private fun LiveTranscriptPanel(
    modifier: Modifier,
    liveAsrModel: String?,
    liveLines: List<String>,
    ambientEnabled: Boolean,
    ambientStatusLabel: String,
    isAmbientLive: Boolean
) {
    val listState = rememberLazyListState()
    LaunchedEffect(liveLines.lastOrNull(), liveLines.size) {
        if (liveLines.isNotEmpty()) {
            listState.scrollToItem(liveLines.lastIndex)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
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
                Text(
                    text = ambientStatusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isAmbientLive) ZtBlack else ZtOnSurfaceVariant
                )
            }
            Text(
                text = "ASR Model: ${liveAsrModel?.takeIf { it.isNotBlank() } ?: "unknown"}",
                style = MaterialTheme.typography.labelMedium,
                color = ZtOnSurfaceVariant
            )
            if (liveLines.isEmpty()) {
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(liveLines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.displaySmall,
                            color = ZtOnSurface
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = "English (Preview): translation stream will appear here.",
                            style = MaterialTheme.typography.titleMedium,
                            color = ZtOnSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationListPanel(
    modifier: Modifier,
    liveTopics: List<TopicFeedCard>
) {
    val orderedTopics = liveTopics.sortedByDescending { topic -> topic.updatedAtEpochMs }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
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
            }

            if (orderedTopics.isEmpty()) {
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
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(orderedTopics.take(10), key = { it.id }) { topic ->
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
                                Text(
                                    text = topic.title.ifBlank { "Untitled topic" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZtOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = topic.summary.ifBlank { "..." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ZtOnSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = topic.displayDate,
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
private fun MemoLanguageChip(label: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ZtPrimaryContainer else ZtSurface,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = ZtOnSurface
        )
    }
}

@Composable
private fun MemoCueChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ZtSurfaceVariant,
        border = BorderStroke(1.dp, ZtOutline)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = ZtOnSurfaceVariant
        )
    }
}

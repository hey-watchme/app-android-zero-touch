package com.example.zero_touch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.zero_touch.ui.SpeakerSegment
import com.example.zero_touch.ui.TranscriptCard
import com.example.zero_touch.ui.UNINTELLIGIBLE_TOPIC_SUMMARY
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtCardRowDivider
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtPrimary
import com.example.zero_touch.ui.theme.ZtRecording

/**
 * Detail drawer showing full transcript detail.
 */
@Composable
fun CardDetailSheet(
    card: TranscriptCard,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRetranscribeEnglish: (() -> Unit)? = null,
    onRetryTranscribe: (() -> Unit)? = null
) {
    val context = LocalContext.current

    SideDetailDrawer(
        title = "Detail",
        onClose = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: title + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.displayTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                StatusPill(card.displayStatus, card.status)
            }

            if (!card.isUnintelligible) {
                SpeakerIdentityBadge(speakerLabels = card.speakerLabels)
            }

            // Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = card.displayDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
                if (card.durationSeconds > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = ZtCaption,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = formatDetailDuration(card.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtOnSurfaceVariant
                        )
                    }
                }
                val asrLabel = buildAsrLabel(
                    card.asrProvider,
                    card.asrModel,
                    card.asrLanguage,
                    if (card.isUnintelligible) emptyList() else card.speakerLabels
                )
                if (asrLabel.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Translate,
                            contentDescription = null,
                            tint = ZtCaption,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = asrLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtCaption
                        )
                    }
                }
            }

            HorizontalDivider(color = ZtCardRowDivider, thickness = 0.5.dp)

            // Transcription content
            if (card.isProcessing) {
                Text(
                    text = "Transcribing...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ZtCaption
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card.isUnintelligible) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = UNINTELLIGIBLE_TOPIC_SUMMARY,
                                style = MaterialTheme.typography.bodySmall,
                                color = ZtOnSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    SelectionContainer {
                        if (shouldShowSpeakerSplit(card)) {
                            SpeakerSegmentDetailList(card.speakerSegments)
                        } else {
                            Text(
                                text = card.text,
                                style = if (card.isUnintelligible) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                color = if (card.isUnintelligible) ZtOnSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                lineHeight = if (card.isUnintelligible) MaterialTheme.typography.bodyMedium.lineHeight else MaterialTheme.typography.bodyLarge.lineHeight
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = ZtCardRowDivider, thickness = 0.5.dp)

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetActionButton(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copy",
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                )
                SheetActionButton(
                    icon = if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    label = if (isFavorite) "Saved" else "Save",
                    onClick = onToggleFavorite,
                    modifier = Modifier.weight(1f),
                    tint = if (isFavorite) ZtPrimary else null
                )
                SheetActionButton(
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
                if (!card.isProcessing && onRetranscribeEnglish != null) {
                    SheetActionButton(
                        icon = Icons.Outlined.Refresh,
                        label = "EN",
                        onClick = onRetranscribeEnglish,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (card.status == "failed" && onRetryTranscribe != null) {
                    SheetActionButton(
                        icon = Icons.Outlined.Refresh,
                        label = "Retry",
                        onClick = {
                            onRetryTranscribe()
                            Toast.makeText(context, "Retrying...", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun shouldShowSpeakerSplit(card: TranscriptCard): Boolean {
    val distinct = card.speakerSegments.map { it.speakerLabel }.distinct()
    return distinct.size >= 2
}

@Composable
private fun SpeakerSegmentDetailList(segments: List<SpeakerSegment>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        segments.forEach { segment ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = segment.speakerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StatusPill(displayStatus: String, status: String) {
    val (bgColor, textColor) = when (status) {
        "transcribed", "completed" -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            ZtOnSurfaceVariant
        )
        "failed" -> Pair(
            ZtRecording.copy(alpha = 0.08f),
            ZtRecording
        )
        else -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            ZtCaption
        )
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor
    ) {
        Text(
            text = displayStatus,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SheetActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val contentColor = tint ?: ZtOnSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

private fun formatDetailDuration(seconds: Int): String {
    return when {
        seconds >= 3600 -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            "${h}h ${m}m"
        }
        seconds >= 60 -> {
            val m = seconds / 60
            val s = seconds % 60
            "${m}m ${s}s"
        }
        else -> "${seconds}s"
    }
}

private fun buildAsrLabel(
    provider: String?,
    model: String?,
    language: String?,
    speakerLabels: List<String>
): String {
    val providerLabel = provider?.trim()?.takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val modelLabel = model?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (it.contains("/")) it.substringAfter("/") else it }
    val languageLabel = language?.trim()?.takeIf { it.isNotEmpty() }
        ?.uppercase()
    val speakerLabel = buildSpeakerLabelSummary(speakerLabels)
    val asrBase = when {
        providerLabel != null && modelLabel != null && languageLabel != null -> "$providerLabel $modelLabel $languageLabel"
        providerLabel != null && modelLabel != null -> "$providerLabel $modelLabel"
        providerLabel != null && languageLabel != null -> "$providerLabel $languageLabel"
        providerLabel != null -> providerLabel
        modelLabel != null -> modelLabel
        languageLabel != null -> languageLabel
        else -> ""
    }

    return when {
        asrBase.isNotBlank() -> "$asrBase / $speakerLabel"
        else -> speakerLabel
    }
}

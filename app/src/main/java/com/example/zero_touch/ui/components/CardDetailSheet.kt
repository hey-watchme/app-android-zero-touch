package com.example.zero_touch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.zero_touch.ui.TranscriptCard
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtError
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutline
import com.example.zero_touch.ui.theme.ZtPrimary

/**
 * Bottom sheet showing full transcript detail.
 * Includes full text (selectable), metadata, and action buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailSheet(
    card: TranscriptCard,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = card.displayTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = card.displayDate,
                style = MaterialTheme.typography.bodyMedium,
                color = ZtOnSurfaceVariant
            )

            // Duration row
            if (card.durationSeconds > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = ZtOnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = formatDetailDuration(card.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtOnSurfaceVariant
                    )
                }
            }

            // Status
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(card.displayStatus, card.status)
            }

            // ASR provider/model
            val asrLabel = buildAsrLabel(card.asrProvider, card.asrModel)
            if (asrLabel.isNotBlank()) {
                Text(
                    text = "ASR: $asrLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
            }

            HorizontalDivider(color = ZtOutline)

            // Transcription content (selectable)
            if (card.isProcessing) {
                Text(
                    text = "Transcription in progress...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ZtCaption
                )
            } else {
                SelectionContainer {
                    Text(
                        text = card.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = ZtOutline)

            // Action buttons row
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
                    label = "Remove",
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    tint = ZtError
                )
            }
        }
    }
}

@Composable
private fun StatusPill(displayStatus: String, status: String) {
    val (bgColor, textColor) = when (status) {
        "transcribed", "completed" -> Pair(
            com.example.zero_touch.ui.theme.ZtSuccess.copy(alpha = 0.1f),
            com.example.zero_touch.ui.theme.ZtSuccess
        )
        "failed" -> Pair(ZtError.copy(alpha = 0.1f), ZtError)
        else -> Pair(
            com.example.zero_touch.ui.theme.ZtWarning.copy(alpha = 0.1f),
            com.example.zero_touch.ui.theme.ZtWarning
        )
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = displayStatus,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
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

private fun buildAsrLabel(provider: String?, model: String?): String {
    val providerLabel = provider?.trim()?.takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val modelLabel = model?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (it.contains("/")) it.substringAfter("/") else it }

    return when {
        providerLabel != null && modelLabel != null -> "$providerLabel · $modelLabel"
        providerLabel != null -> providerLabel
        modelLabel != null -> modelLabel
        else -> ""
    }
}

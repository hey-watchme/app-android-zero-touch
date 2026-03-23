package com.example.zero_touch.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zero_touch.ui.TranscriptCard
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtError
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtPrimary
import com.example.zero_touch.ui.theme.ZtRecording
import com.example.zero_touch.ui.theme.ZtSuccess
import com.example.zero_touch.ui.theme.ZtWarning

/**
 * Notion-style transcript card.
 * White surface, subtle shadow, clean typography hierarchy.
 */
@Composable
fun TranscriptCardView(
    card: TranscriptCard,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top row: time + duration + bookmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(card.status)
                    Text(
                        text = card.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (card.durationSeconds > 0) {
                        DurationChip(card.durationSeconds)
                    }
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Bookmark",
                            tint = if (isFavorite) ZtPrimary else ZtCaption,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Content area
            if (card.isProcessing) {
                ProcessingContent()
            } else {
                Text(
                    text = card.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtOnSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status label
            StatusLabel(card.displayStatus, card.status)
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "transcribed", "completed" -> ZtSuccess
        "failed" -> ZtError
        "uploaded", "transcribing", "generating" -> ZtWarning
        "pending" -> ZtCaption
        else -> ZtCaption
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun DurationChip(seconds: Int) {
    val text = when {
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = ZtOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusLabel(displayStatus: String, status: String) {
    val color = when (status) {
        "transcribed", "completed" -> ZtSuccess
        "failed" -> ZtError
        "uploaded", "transcribing", "generating", "pending" -> ZtWarning
        else -> ZtCaption
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = displayStatus,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
        if (status in listOf("uploaded", "transcribing", "generating", "pending")) {
            AnimatedProcessingDots()
        }
    }
}

@Composable
private fun ProcessingContent() {
    // Skeleton-like placeholder for processing cards
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ShimmerLine(widthFraction = 0.9f)
        ShimmerLine(widthFraction = 0.7f)
    }
}

@Composable
private fun ShimmerLine(widthFraction: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .background(
                ZtCaption.copy(alpha = alpha),
                RoundedCornerShape(4.dp)
            )
    )
}

@Composable
private fun AnimatedProcessingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots_progress"
    )
    val dotCount = progress.toInt().coerceIn(0, 3)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .background(
                        if (index <= dotCount) ZtWarning else ZtCaption.copy(alpha = 0.3f),
                        CircleShape
                    )
            )
        }
    }
}

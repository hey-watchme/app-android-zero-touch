package com.example.zero_touch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zero_touch.ui.SpeakerSegment
import com.example.zero_touch.ui.TranscriptCard
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtCardRowDivider
import com.example.zero_touch.ui.theme.ZtError
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtPrimary
import com.example.zero_touch.ui.theme.ZtRecording
import com.example.zero_touch.ui.theme.ZtSuccess
import com.example.zero_touch.ui.theme.ZtWarning

/**
 * Notion-style transcript card with entry animation.
 * Two modes: full (standalone) and compact (inline within a topic).
 *
 * 3-stage lifecycle:
 *   1. Recording / just created -> card appears with bounce-in animation
 *   2. Uploaded / transcribing  -> "Transcribing..." pulsing indicator
 *   3. Transcribed              -> content visible, smooth fade-in
 */
@Composable
fun TranscriptCardView(
    card: TranscriptCard,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isFailed = card.status == "failed"
    val isUnintelligible = card.isUnintelligible
    // --- Entry animation: scale + fade bounce-in ---
    val scaleAnim = remember { Animatable(0.92f) }
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(card.id) {
        // Stagger: scale springs in, alpha fades in
        alphaAnim.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(card.id) {
        scaleAnim.animateTo(
            1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.scaleX = scaleAnim.value
                this.scaleY = scaleAnim.value
                this.alpha = alphaAnim.value * when {
                    isFailed -> 0.65f
                    isUnintelligible -> 0.8f
                    else -> 1f
                }
            }
    ) {
        if (compact) {
            CompactCardRow(card, isFavorite, onClick, onToggleFavorite, isFailed = isFailed)
        } else {
            FullCardView(card, isFavorite, onClick, onToggleFavorite, isFailed = isFailed)
        }
    }
}

// ---------------------------------------------------------------
// Compact card: variable height (1-3 lines based on content)
// ---------------------------------------------------------------

@Composable
private fun CompactCardRow(
    card: TranscriptCard,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    isFailed: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = when {
            card.isUnintelligible -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
            isFailed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.surface
        },
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status dot (top-aligned with first text line)
            Box(modifier = Modifier.padding(top = 5.dp)) {
                StatusDot(card.status, size = 5)
            }

            Column(
                modifier = Modifier.width(52.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = card.displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = ZtOnSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp)
                )
                if (card.durationSeconds > 0) {
                    Text(
                        text = formatCompactDuration(card.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                }
            }

            // Content area (fill, variable height)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                when {
                    // Stage 1: Recording / just created — pulsing "new" indicator
                    card.status == "recording" || card.status == "pending" -> {
                        RecordingPulseLabel()
                    }
                    // Stage 2: Processing — transcribing animation
                    card.isProcessing -> {
                        TranscribingLabel(card.displayStatus)
                    }
                    card.isUnintelligible -> {
                        Text(
                            text = card.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Stage 3: Content ready — show 1-3 lines
                    else -> {
                        SpeakerIdentityBadge(speakerLabels = card.speakerLabels)
                        if (shouldShowSpeakerSplit(card)) {
                            SpeakerSegmentList(
                                segments = card.speakerSegments,
                                maxItems = 2,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                text = card.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// Full standalone card (Timeline tab)
// ---------------------------------------------------------------

@Composable
private fun FullCardView(
    card: TranscriptCard,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    isFailed: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = when {
            card.isUnintelligible -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
            isFailed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.surface
        },
        shadowElevation = 0.5.dp,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(card.status)
                    Text(
                        text = card.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (card.durationSeconds > 0) {
                        DurationChip(card.durationSeconds)
                    }
                    if (!card.isUnintelligible) {
                        SpeakerIdentityBadge(speakerLabels = card.speakerLabels)
                    }
                }
            }

            // Content area — 3-stage
            when {
                card.status == "recording" || card.status == "pending" -> {
                    RecordingPulseLabel()
                }
                card.isProcessing -> {
                    TranscribingLabel(card.displayStatus)
                    ProcessingContent()
                }
                card.isUnintelligible -> {
                    Text(
                        text = card.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    if (shouldShowSpeakerSplit(card)) {
                        SpeakerSegmentList(
                            segments = card.speakerSegments,
                            maxItems = 4,
                            maxLines = 2
                        )
                    } else {
                        Text(
                            text = card.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZtOnSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
private fun SpeakerSegmentList(
    segments: List<SpeakerSegment>,
    maxItems: Int,
    maxLines: Int
) {
    val visible = segments.take(maxItems)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        visible.forEach { segment ->
            Text(
                text = "${segment.speakerLabel}: ${segment.text}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        val remaining = segments.size - visible.size
        if (remaining > 0) {
            Text(
                text = "+$remaining",
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption
            )
        }
    }
}

// ---------------------------------------------------------------
// 3-stage visual indicators
// ---------------------------------------------------------------

/**
 * Stage 1: Voice detected / recording in progress.
 * Pulsing colored label that signals "something is happening right now."
 */
@Composable
private fun RecordingPulseLabel() {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(ZtRecording.copy(alpha = alpha), CircleShape)
        )
        Text(
            text = "録音中...",
            style = MaterialTheme.typography.bodySmall,
            color = ZtRecording.copy(alpha = alpha)
        )
    }
}

/**
 * Stage 2: Transcribing / generating.
 * Shows status text with animated dots + subtle pulse.
 */
@Composable
private fun TranscribingLabel(displayStatus: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "transcribe")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "transcribe_alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.graphicsLayer { this.alpha = alpha }
    ) {
        Text(
            text = displayStatus,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = ZtWarning
        )
        AnimatedProcessingDots()
    }
}

// ---------------------------------------------------------------
// Shared sub-components
// ---------------------------------------------------------------

@Composable
fun StatusDot(status: String, size: Int = 6) {
    val color = when (status) {
        "transcribed", "completed" -> ZtSuccess
        "failed" -> ZtError
        "uploaded", "transcribing", "generating" -> ZtWarning
        "recording" -> ZtRecording
        "pending" -> ZtCaption
        else -> ZtCaption
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun DurationChip(seconds: Int) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = formatCompactDuration(seconds),
            style = MaterialTheme.typography.labelSmall,
            color = ZtOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun ProcessingContent() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShimmerLine(widthFraction = 0.9f)
        ShimmerLine(widthFraction = 0.65f)
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
            .height(10.dp)
            .background(
                ZtCaption.copy(alpha = alpha),
                RoundedCornerShape(3.dp)
            )
    )
}

@Composable
fun AnimatedProcessingDots() {
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

private fun formatCompactDuration(seconds: Int): String = when {
    seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
    else -> "${seconds}秒"
}

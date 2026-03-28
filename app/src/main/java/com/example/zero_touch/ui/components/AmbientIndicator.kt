package com.example.zero_touch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.zero_touch.audio.ambient.AmbientUiState
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtRecording
import com.example.zero_touch.ui.theme.ZtSurfaceVariant
import kotlin.math.max

/**
 * Compact ambient recording status banner.
 */
@Composable
fun AmbientStatusBar(
    ambientState: AmbientUiState,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isEnabled,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val isRecording = ambientState.isRecording
        val bgColor by animateColorAsState(
            targetValue = if (isRecording) ZtRecording.copy(alpha = 0.04f) else ZtSurfaceVariant,
            label = "ambient_bg"
        )

        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = bgColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PulsingDot(
                    color = if (isRecording) ZtRecording else ZtOnSurfaceVariant,
                    isActive = isRecording,
                    size = 6
                )
                Text(
                    text = when {
                        isRecording -> "Recording ${formatDuration(ambientState.recordingElapsedMs)}"
                        else -> "Listening"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRecording) ZtRecording else ZtOnSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                LevelEqualizer(
                    level = max(ambientState.ambientLevel, ambientState.voiceLevel),
                    isActive = ambientState.speech || isRecording
                )
            }
        }
    }
}

/**
 * Small dot in the top bar that pulses when recording.
 */
@Composable
fun AmbientDot(isEnabled: Boolean, isRecording: Boolean) {
    if (!isEnabled) return
    val color = if (isRecording) ZtRecording else ZtOnSurfaceVariant
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 0.3f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRecording) 600 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun PulsingDot(
    color: Color,
    isActive: Boolean,
    size: Int = 6
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val alpha = if (isActive) animatedAlpha else 0.7f
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/**
 * Single-color level equalizer — merges ambient + voice into one visual.
 */
@Composable
private fun LevelEqualizer(
    level: Float,
    isActive: Boolean
) {
    val barColor = ZtOnSurfaceVariant
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 150),
        label = "eq_level"
    )

    Row(
        modifier = Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        EQ_PATTERN.forEach { weight ->
            val barHeight = 2.dp + (10f * animatedLevel * weight).dp
            val alpha = if (isActive) {
                (0.25f + (animatedLevel * 0.75f * weight)).coerceIn(0.2f, 0.8f)
            } else {
                0.15f
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(barColor.copy(alpha = alpha))
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = max(0, durationMs / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

private val EQ_PATTERN = floatArrayOf(0.35f, 0.7f, 1f, 0.75f, 0.5f)

package com.example.zero_touch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import com.example.zero_touch.audio.ambient.AmbientUiState
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutline
import com.example.zero_touch.ui.theme.ZtRecording
import com.example.zero_touch.ui.theme.ZtSuccess
import com.example.zero_touch.ui.theme.ZtSurfaceVariant
import kotlin.math.max

/**
 * Compact ambient recording status banner.
 * Shows a pulsing dot + status text when ambient mode is active.
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
            targetValue = if (isRecording) ZtRecording.copy(alpha = 0.06f) else ZtSurfaceVariant,
            label = "ambient_bg"
        )

        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = bgColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PulsingDot(
                    color = if (isRecording) ZtRecording else ZtSuccess,
                    isActive = isRecording
                )
                Text(
                    text = when {
                        isRecording -> "Recording ${formatDuration(ambientState.recordingElapsedMs)}"
                        ambientState.speech -> "Voice detected"
                        else -> "Listening..."
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRecording) ZtRecording else ZtOnSurfaceVariant
                )
                if (isRecording) {
                    Spacer(Modifier.weight(1f))
                    AudioLevelBar(level = ambientState.level)
                }
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
    val color = if (isRecording) ZtRecording else ZtSuccess
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 0.3f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRecording) 600 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun PulsingDot(
    color: androidx.compose.ui.graphics.Color,
    isActive: Boolean
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
    val alpha = if (isActive) animatedAlpha else 1f
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun AudioLevelBar(level: Float) {
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ZtOutline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = level.coerceIn(0f, 1f))
                .background(ZtRecording, RoundedCornerShape(2.dp))
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = max(0, durationMs / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

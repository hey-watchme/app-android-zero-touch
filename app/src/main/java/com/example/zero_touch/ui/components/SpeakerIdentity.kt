package com.example.zero_touch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zero_touch.ui.theme.ZtAvatarBg
import com.example.zero_touch.ui.theme.ZtAvatarText
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant

fun buildSpeakerLabelSummary(speakerLabels: List<String>): String {
    if (speakerLabels.isEmpty()) return "Speaker 未設定"
    return if (speakerLabels.size <= 2) {
        speakerLabels.joinToString(" / ")
    } else {
        "${speakerLabels.take(2).joinToString(" / ")} +${speakerLabels.size - 2}"
    }
}

@Composable
fun SpeakerIdentityBadge(
    speakerLabels: List<String>,
    modifier: Modifier = Modifier
) {
    val label = buildSpeakerLabelSummary(speakerLabels)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(ZtAvatarBg, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = ZtAvatarText,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ZtOnSurfaceVariant
            )
        }
    }
}

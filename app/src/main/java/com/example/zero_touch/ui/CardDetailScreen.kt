package com.example.zero_touch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zero_touch.api.SessionDetail

@Composable
fun CardDetailScreen(
    uiState: ZeroTouchUiState,
    modifier: Modifier = Modifier
) {
    val session = uiState.currentSession
    val cards = uiState.cards

    Column(modifier = modifier.padding(16.dp)) {
        if (uiState.isLoading && session == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        if (session == null) {
            Text("Session not found", style = MaterialTheme.typography.bodyLarge)
            return
        }

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Session",
                style = MaterialTheme.typography.titleLarge
            )
            StatusBadge(status = session.status)
        }

        // Processing indicator
        if (session.status !in listOf("completed", "failed")) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 4.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = when (session.status) {
                        "uploaded" -> "Preparing..."
                        "transcribing" -> "Transcribing audio..."
                        "transcribed" -> "Transcription complete"
                        "generating" -> "Generating cards..."
                        else -> session.status
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Error
        if (session.error_message != null) {
            Text(
                text = session.error_message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // Transcription preview
        if (session.transcription != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Transcription",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (session.transcription.length > 300) {
                            session.transcription.take(300) + "..."
                        } else {
                            session.transcription
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Cards
        if (cards.isNotEmpty()) {
            Text(
                text = "Cards (${cards.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cards) { card ->
                    MemoCard(card = card)
                }
            }
        } else if (session.status == "completed") {
            Text(
                text = "No cards generated",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
fun MemoCard(
    card: com.example.zero_touch.api.Card,
    modifier: Modifier = Modifier
) {
    val typeColor = when (card.type) {
        "task" -> MaterialTheme.colorScheme.primary
        "schedule" -> MaterialTheme.colorScheme.tertiary
        "issue" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.type.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = typeColor
                )
                if (card.urgency == "high") {
                    Text(
                        text = "HIGH",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = card.content,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (card.context != null) {
                Text(
                    text = card.context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

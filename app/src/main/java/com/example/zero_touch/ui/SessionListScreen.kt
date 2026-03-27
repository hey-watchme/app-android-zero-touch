package com.example.zero_touch.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zero_touch.api.SessionSummary

@Composable
fun SessionListScreen(
    sessions: List<SessionSummary>,
    isLoading: Boolean,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "セッション",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (isLoading && sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "セッションはまだありません。\n録音してアップロードしてください。",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions) { session ->
                    SessionCard(session = session, onClick = { onSessionClick(session.id) })
                }
            }
        }
    }
}

@Composable
fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = session.id.take(8) + "...",
                    style = MaterialTheme.typography.titleSmall
                )
                StatusBadge(status = session.status)
            }
            if (session.recorded_at != null) {
                Text(
                    text = session.recorded_at.take(16),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (session.duration_seconds != null && session.duration_seconds > 0) {
                Text(
                    text = "${session.duration_seconds}秒",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (session.error_message != null) {
                Text(
                    text = session.error_message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    val displayStatus = when (status) {
        "uploaded" -> "キュー待ち"
        "transcribing" -> "文字起こし中"
        "transcribed" -> "文字起こし済み"
        "completed" -> "完了"
        "failed" -> "失敗"
        "generating" -> "分析中"
        "recording" -> "録音中"
        else -> "不明"
    }
    val color = when (status) {
        "completed" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        "recording", "uploaded" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Text(
        text = displayStatus,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
    )
}

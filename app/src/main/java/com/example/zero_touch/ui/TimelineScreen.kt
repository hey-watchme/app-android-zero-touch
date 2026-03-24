package com.example.zero_touch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.components.CardDetailSheet
import com.example.zero_touch.ui.components.TranscriptCardView
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onLoadMore: () -> Unit,
    onRetranscribeEnglish: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val ambientState by AmbientStatus.state.collectAsState()

    val feedCards = uiState.feedCards
    val favoriteIds = uiState.favoriteIds

    val today = LocalDate.now()
    var selectedDate by remember { mutableStateOf(today) }

    val earliestLoadedDate = feedCards
        .mapNotNull { card -> dateFromEpoch(card.createdAtEpochMs) }
        .minOrNull()

    LaunchedEffect(selectedDate, earliestLoadedDate, uiState.hasMore, uiState.isLoadingMore) {
        if (uiState.hasMore && !uiState.isLoadingMore) {
            if (feedCards.isEmpty()) {
                onLoadMore()
            } else if (earliestLoadedDate != null && selectedDate.isBefore(earliestLoadedDate)) {
                onLoadMore()
            }
        }
    }

    val cardsForDay = feedCards.filter { card ->
        val cardDate = dateFromEpoch(card.createdAtEpochMs) ?: return@filter false
        cardDate == selectedDate
    }

    val listState = rememberLazyListState()

    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading, cardsForDay.size) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 2
        }
            .distinctUntilChanged()
            .collect { isNearEnd ->
                if (isNearEnd && uiState.hasMore && !uiState.isLoadingMore && !uiState.isLoading) {
                    onLoadMore()
                }
            }
    }

    // --- Bottom sheet for card detail ---
    val selectedCard = uiState.selectedCardId?.let { id ->
        feedCards.find { it.id == id }
    }
    if (selectedCard != null) {
        CardDetailSheet(
            card = selectedCard,
            isFavorite = favoriteIds.contains(selectedCard.id),
            onDismiss = onDismissDetail,
            onToggleFavorite = { onToggleFavorite(selectedCard.id) },
            onDelete = { onDeleteCard(selectedCard.id) },
            onCopy = { clipboardManager.setText(AnnotatedString(selectedCard.text)) },
            onRetranscribeEnglish = { onRetranscribeEnglish(selectedCard.id) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimelineHeader(
            selectedDate = selectedDate,
            onPrevious = { selectedDate = selectedDate.minusDays(1) },
            onNext = {
                if (selectedDate.isBefore(today)) {
                    selectedDate = selectedDate.plusDays(1)
                }
            },
            canGoNext = selectedDate.isBefore(today),
            statusText = if (ambientState.isRecording || ambientState.speech) "Ambient: On" else "Ambient: Off"
        )

        if (cardsForDay.isEmpty() && !uiState.isLoading && !uiState.isLoadingMore) {
            EmptyTimelineState(selectedDate = selectedDate)
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(cardsForDay, key = { it.id }) { card ->
                    TranscriptCardView(
                        card = card,
                        isFavorite = favoriteIds.contains(card.id),
                        onClick = { onSelectCard(card.id) },
                        onToggleFavorite = { onToggleFavorite(card.id) }
                    )
                }

                if (uiState.isLoadingMore) {
                    item(key = "loading_more") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Loading more...",
                                style = MaterialTheme.typography.bodySmall,
                                color = ZtOnSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineHeader(
    selectedDate: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoNext: Boolean,
    statusText: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = "Previous day",
                        tint = ZtOnSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatTimelineDate(selectedDate),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatTimelineSubDate(selectedDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtCaption
                    )
                }
                IconButton(onClick = onNext, enabled = canGoNext) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "Next day",
                        tint = if (canGoNext) ZtOnSurfaceVariant else ZtCaption
                    )
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = ZtCaption,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyTimelineState(selectedDate: LocalDate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "No recordings",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${formatTimelineDate(selectedDate)} に録音がありません",
            style = MaterialTheme.typography.bodySmall,
            color = ZtOnSurfaceVariant
        )
    }
}

private fun dateFromEpoch(epochMs: Long): LocalDate? {
    if (epochMs <= 0L) return null
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun formatTimelineDate(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN))
    }
}

private fun formatTimelineSubDate(date: LocalDate): String {
    return date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.JAPAN))
}

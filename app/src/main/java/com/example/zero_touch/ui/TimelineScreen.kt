package com.example.zero_touch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.components.CardDetailSheet
import com.example.zero_touch.ui.components.TranscriptCardView
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtPrimary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetranscribeEnglish: (String) -> Unit,
    onRetryTranscribe: (String) -> Unit
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

    // --- Bottom sheet ---
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
            onRetranscribeEnglish = { onRetranscribeEnglish(selectedCard.id) },
            onRetryTranscribe = { onRetryTranscribe(selectedCard.id) }
        )
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                cardCount = cardsForDay.size,
                isAmbientOn = ambientState.isRecording || ambientState.speech
            )

            if (cardsForDay.isEmpty() && !uiState.isLoading && !uiState.isLoadingMore) {
                EmptyTimelineState(selectedDate = selectedDate)
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
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
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "読み込み中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ZtCaption
                                )
                            }
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
    cardCount: Int,
    isAmbientOn: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.ChevronLeft,
                    contentDescription = "前日",
                    tint = ZtOnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.CalendarToday,
                        contentDescription = null,
                        tint = ZtPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = formatTimelineDate(selectedDate),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTimelineSubDate(selectedDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                    Text(
                        text = "カード ${cardCount}件",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtCaption
                    )
                }
            }

            IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "翌日",
                    tint = if (canGoNext) ZtOnSurfaceVariant else ZtCaption.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            }
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "録音はありません",
            style = MaterialTheme.typography.titleSmall,
            color = ZtOnSurfaceVariant
        )
        Text(
            text = "${formatTimelineDate(selectedDate)} の記録はありません",
            style = MaterialTheme.typography.bodySmall,
            color = ZtCaption
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
        today -> "今日"
        today.minusDays(1) -> "昨日"
        else -> date.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN))
    }
}

private fun formatTimelineSubDate(date: LocalDate): String {
    return date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.JAPAN))
}

package com.subbrain.zerotouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import com.subbrain.zerotouch.ui.components.CardDetailSheet
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtPrimary
import com.subbrain.zerotouch.ui.theme.ZtRecording
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HOUR_ROW_HEIGHT = 64.dp
private const val MIN_DURATION_MINUTES = 3
private const val DEFAULT_DURATION_MINUTES = 8

private data class DayTimelineEvent(
    val card: TranscriptCard,
    val startMinute: Int,
    val durationMinutes: Int,
    val lane: Int
)

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
    var showCalendarDialog by remember { mutableStateOf(false) }
    var calendarMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }

    val availableDates = remember(feedCards) {
        feedCards.mapNotNull { card -> dateFromEpoch(card.createdAtEpochMs) }.toSet()
    }
    val earliestLoadedDate = remember(feedCards) {
        feedCards
            .mapNotNull { card -> dateFromEpoch(card.createdAtEpochMs) }
            .minOrNull()
    }

    LaunchedEffect(selectedDate, earliestLoadedDate, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading) {
        if (!uiState.hasMore || uiState.isLoadingMore || uiState.isLoading) return@LaunchedEffect
        if (feedCards.isEmpty()) {
            onLoadMore()
            return@LaunchedEffect
        }
        if (earliestLoadedDate != null && selectedDate.isBefore(earliestLoadedDate)) {
            onLoadMore()
        }
    }

    val cardsForDay = remember(feedCards, selectedDate) {
        feedCards
            .filter { card ->
                val cardDate = dateFromEpoch(card.createdAtEpochMs) ?: return@filter false
                cardDate == selectedDate
            }
            .sortedBy { it.createdAtEpochMs }
    }
    val timelineEvents = remember(cardsForDay) { buildDayTimelineEvents(cardsForDay) }

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
                onOpenCalendar = {
                    calendarMonth = YearMonth.from(selectedDate)
                    showCalendarDialog = true
                },
                canGoNext = selectedDate.isBefore(today),
                cardCount = cardsForDay.size,
                isAmbientOn = ambientState.isRecording || ambientState.speech
            )

            if (uiState.isLoadingMore) {
                Text(
                    text = "過去データを読み込み中...",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }

            if (cardsForDay.isEmpty() && !uiState.isLoading && !uiState.isLoadingMore) {
                EmptyTimelineState(selectedDate = selectedDate)
            } else {
                DayCalendarTimeline(
                    events = timelineEvents,
                    onSelectCard = onSelectCard
                )
            }
        }
    }

    if (showCalendarDialog) {
        DataDateCalendarDialog(
            selectedDate = selectedDate,
            currentMonth = calendarMonth,
            availableDates = availableDates,
            onMonthChange = { calendarMonth = it },
            onDismiss = { showCalendarDialog = false },
            onSelectDate = { picked ->
                selectedDate = picked
                showCalendarDialog = false
            }
        )
    }
}

@Composable
private fun TimelineHeader(
    selectedDate: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenCalendar: () -> Unit,
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
                    IconButton(
                        onClick = onOpenCalendar,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CalendarToday,
                            contentDescription = "カレンダーを開く",
                            tint = ZtPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
                    if (isAmbientOn) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(ZtRecording, CircleShape)
                            )
                            Text(
                                text = "Live",
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtCaption
                            )
                        }
                    }
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
private fun DayCalendarTimeline(
    events: List<DayTimelineEvent>,
    onSelectCard: (String) -> Unit
) {
    val timelineScroll = rememberScrollState()
    val laneCount = (events.maxOfOrNull { it.lane } ?: -1) + 1
    val safeLaneCount = laneCount.coerceAtLeast(1)
    val totalHeight = HOUR_ROW_HEIGHT * 24f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(timelineScroll)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .height(totalHeight),
                verticalArrangement = Arrangement.Top
            ) {
                for (hour in 0..23) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(HOUR_ROW_HEIGHT),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Text(
                            text = hourLabel(hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtCaption,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(totalHeight)
            ) {
                for (hour in 0..23) {
                    HorizontalDivider(
                        color = ZtCaption.copy(alpha = 0.22f),
                        thickness = if (hour % 6 == 0) 1.dp else 0.5.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = HOUR_ROW_HEIGHT * hour.toFloat())
                    )
                }

                val laneGap = 6.dp
                val laneWidth = (maxWidth - (laneGap * (safeLaneCount - 1).toFloat())) / safeLaneCount.toFloat()

                events.forEach { event ->
                    val top = HOUR_ROW_HEIGHT * (event.startMinute / 60f)
                    val rawHeight = HOUR_ROW_HEIGHT * (event.durationMinutes / 60f)
                    val blockHeight = if (rawHeight < 40.dp) 40.dp else rawHeight
                    val x = (laneWidth + laneGap) * event.lane.toFloat()
                    val statusColor = when {
                        event.card.status == "failed" -> MaterialTheme.colorScheme.errorContainer
                        event.card.isProcessing -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                    val statusTextColor = when {
                        event.card.status == "failed" -> MaterialTheme.colorScheme.onErrorContainer
                        event.card.isProcessing -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                    val textLine = when {
                        event.card.isProcessing -> event.card.displayStatus
                        event.card.text.isNotBlank() -> event.card.text
                        else -> event.card.displayStatus
                    }

                    Surface(
                        modifier = Modifier
                            .offset(x = x, y = top)
                            .width(laneWidth)
                            .height(blockHeight),
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor,
                        tonalElevation = 1.dp,
                        onClick = { onSelectCard(event.card.id) }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = eventTimeRangeLabel(event),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusTextColor
                            )
                            Text(
                                text = textLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusTextColor,
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

@Composable
private fun DataDateCalendarDialog(
    selectedDate: LocalDate,
    currentMonth: YearMonth,
    availableDates: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val monthCells = remember(currentMonth) { buildMonthCells(currentMonth) }
    val earliestAvailable = availableDates.minOrNull()
    val minMonth = earliestAvailable?.let { YearMonth.from(it) } ?: YearMonth.from(today.minusYears(1))
    val maxMonth = YearMonth.from(today)
    val canGoPrev = currentMonth.isAfter(minMonth)
    val canGoNext = currentMonth.isBefore(maxMonth)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onMonthChange(currentMonth.minusMonths(1)) },
                        enabled = canGoPrev,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            contentDescription = "前の月",
                            tint = if (canGoPrev) ZtOnSurfaceVariant else ZtCaption.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = currentMonth.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.JAPAN)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = { onMonthChange(currentMonth.plusMonths(1)) },
                        enabled = canGoNext,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = "次の月",
                            tint = if (canGoNext) ZtOnSurfaceVariant else ZtCaption.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("日", "月", "火", "水", "木", "金", "土").forEach { label ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtCaption
                            )
                        }
                    }
                }

                monthCells.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            TimelineDateCell(
                                date = date,
                                selectedDate = selectedDate,
                                today = today,
                                hasData = date != null && availableDates.contains(date),
                                onSelectDate = onSelectDate
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "閉じる",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TimelineDateCell(
    date: LocalDate?,
    selectedDate: LocalDate,
    today: LocalDate,
    hasData: Boolean,
    onSelectDate: (LocalDate) -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (date == null) return

        val isSelected = date == selectedDate
        val isFuture = date.isAfter(today)
        val dayTextColor = when {
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            isFuture -> ZtCaption.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.onSurface
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .then(
                    if (isSelected) {
                        Modifier.background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = !isFuture) { onSelectDate(date) }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = dayTextColor
            )
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .background(
                        color = when {
                            !hasData || isFuture -> MaterialTheme.colorScheme.surface
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> ZtRecording
                        },
                        shape = CircleShape
                    )
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

private fun buildDayTimelineEvents(cards: List<TranscriptCard>): List<DayTimelineEvent> {
    val laneBusyUntil = mutableListOf<Int>()
    val events = mutableListOf<DayTimelineEvent>()

    cards.forEach { card ->
        val minute = minuteOfDay(card.createdAtEpochMs) ?: return@forEach
        val startMinute = minute.coerceIn(0, 1439)
        val durationMinutes = resolveEventDurationMinutes(card)
        val endMinute = (startMinute + durationMinutes).coerceAtMost(1440)

        var laneIndex = laneBusyUntil.indexOfFirst { busyUntil -> startMinute >= busyUntil }
        if (laneIndex < 0) {
            laneIndex = laneBusyUntil.size
            laneBusyUntil.add(endMinute)
        } else {
            laneBusyUntil[laneIndex] = endMinute
        }

        events.add(
            DayTimelineEvent(
                card = card,
                startMinute = startMinute,
                durationMinutes = (endMinute - startMinute).coerceAtLeast(MIN_DURATION_MINUTES),
                lane = laneIndex
            )
        )
    }

    return events
}

private fun resolveEventDurationMinutes(card: TranscriptCard): Int {
    val fromDuration = if (card.durationSeconds > 0) {
        ((card.durationSeconds + 59) / 60).coerceAtLeast(MIN_DURATION_MINUTES)
    } else {
        0
    }
    if (fromDuration > 0) return fromDuration
    return if (card.isProcessing) DEFAULT_DURATION_MINUTES * 2 else DEFAULT_DURATION_MINUTES
}

private fun eventTimeRangeLabel(event: DayTimelineEvent): String {
    val start = minuteLabel(event.startMinute)
    val endMinute = (event.startMinute + event.durationMinutes).coerceAtMost(1440)
    val end = minuteLabel(endMinute)
    return "$start - $end"
}

private fun minuteLabel(minute: Int): String {
    val normalized = minute.coerceIn(0, 1440)
    val hour = (normalized / 60) % 24
    val min = normalized % 60
    return "%02d:%02d".format(hour, min)
}

private fun hourLabel(hour: Int): String = "%02d:00".format(hour)

private fun minuteOfDay(epochMs: Long): Int? {
    if (epochMs <= 0L) return null
    val local = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return local.hour * 60 + local.minute
}

private fun dateFromEpoch(epochMs: Long): LocalDate? {
    if (epochMs <= 0L) return null
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun buildMonthCells(month: YearMonth): List<LocalDate?> {
    val firstDay = month.atDay(1)
    val firstOffset = firstDay.dayOfWeek.value % 7
    val totalDays = month.lengthOfMonth()
    val cells = mutableListOf<LocalDate?>()

    repeat(firstOffset) { cells.add(null) }
    for (day in 1..totalDays) {
        cells.add(month.atDay(day))
    }
    while (cells.size % 7 != 0) {
        cells.add(null)
    }
    return cells
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

package com.example.zero_touch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Topic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.audio.ambient.AmbientRecordingService
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.components.AmbientStatusBar
import com.example.zero_touch.ui.components.AnimatedProcessingDots
import com.example.zero_touch.ui.components.CardDetailSheet
import com.example.zero_touch.ui.components.ShimmerCardList
import com.example.zero_touch.ui.components.TranscriptCardView
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtCardRowDivider
import com.example.zero_touch.ui.theme.ZtFilterBorder
import com.example.zero_touch.ui.theme.ZtFilterSelected
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutline
import com.example.zero_touch.ui.theme.ZtPrimary
import com.example.zero_touch.ui.theme.ZtTopicBorder
import com.example.zero_touch.ui.theme.ZtTopicSurface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun VoiceMemoScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    showFavoritesOnly: Boolean,
    onDeleteCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectCard: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onLoadMore: () -> Unit,
    onRetranscribeEnglish: (String) -> Unit,
    onAmbientStopped: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Permission & ambient state ---
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var ambientEnabled by remember { mutableStateOf(AmbientPreferences.isAmbientEnabled(context)) }
    val ambientState by AmbientStatus.state.collectAsState()
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasRecordPermission = granted
        }
    val requestNotificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                ambientEnabled = false
                AmbientPreferences.setAmbientEnabled(context, false)
            } else if (ambientEnabled) {
                startAmbientService(context)
            }
        }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { /* no-op */ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ambientEnabled, hasRecordPermission, hasNotificationPermission) {
        if (!ambientEnabled) return@LaunchedEffect
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission
        if (!hasRecordPermission || !notificationsOk) {
            ambientEnabled = false
            AmbientPreferences.setAmbientEnabled(context, false)
            stopAmbientService(context)
            return@LaunchedEffect
        }
        startAmbientService(context)
    }

    // --- Filter state ---
    var activeFilter by remember { mutableStateOf("All") }
    val filterOptions = listOf("All", "Today", "Live", "Finalized")

    // --- Data preparation ---
    val topicCards = uiState.topicCards
    val favoriteIds = uiState.favoriteIds
    val topicChildren = topicCards.flatMap { it.utterances }
    val visibleTopicCards = if (showFavoritesOnly) {
        emptyList()
    } else {
        when (activeFilter) {
            "Today" -> topicCards.filter { it.displayDate == "Today" }
            "Live" -> topicCards.filter { it.status == "active" }
            "Finalized" -> topicCards.filter { it.status == "finalized" }
            else -> topicCards
        }
    }

    // --- Expand/collapse state per topic ---
    val expandedTopics = remember { mutableStateMapOf<String, Boolean>() }

    val listState = rememberLazyListState()

    LaunchedEffect(listState, uiState.hasMore, uiState.isLoadingMore, uiState.isLoading, visibleTopicCards.size) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 3
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
        topicChildren.distinctBy { it.id }.find { it.id == id }
    }
    if (selectedCard != null) {
        CardDetailSheet(
            card = selectedCard,
            isFavorite = favoriteIds.contains(selectedCard.id),
            onDismiss = onDismissDetail,
            onToggleFavorite = { onToggleFavorite(selectedCard.id) },
            onDelete = { onDeleteCard(selectedCard.id) },
            onCopy = {
                clipboardManager.setText(AnnotatedString(selectedCard.text))
            },
            onRetranscribeEnglish = { onRetranscribeEnglish(selectedCard.id) }
        )
    }

    // --- Main UI ---
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // Compact header: ambient toggle (right-aligned)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Greeting + date
            Column {
                Text(
                    text = formatTodayJa(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtCaption
                )
            }
            AmbientToggleChip(
                enabled = ambientEnabled,
                onToggle = { enabled ->
                    if (enabled) {
                        if (!hasRecordPermission) {
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@AmbientToggleChip
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@AmbientToggleChip
                        }
                        AmbientPreferences.setAmbientEnabled(context, true)
                        ambientEnabled = true
                        startAmbientService(context)
                    } else {
                        AmbientPreferences.setAmbientEnabled(context, false)
                        ambientEnabled = false
                        stopAmbientService(context)
                        onAmbientStopped()
                    }
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        // Ambient status bar
        AmbientStatusBar(
            ambientState = ambientState,
            isEnabled = ambientEnabled
        )

        // Permission prompt
        if (!hasRecordPermission) {
            Spacer(Modifier.height(6.dp))
            PermissionBanner(
                onGrant = { requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Search bar + filter icon
        SearchBar()

        Spacer(Modifier.height(8.dp))

        // Filter chips (horizontal scroll)
        if (!showFavoritesOnly) {
            FilterChipRow(
                options = filterOptions,
                activeFilter = activeFilter,
                onFilterSelected = { activeFilter = it },
                topicCount = topicCards.size
            )
            Spacer(Modifier.height(8.dp))
        }

        // Feed list
        when {
            uiState.isLoading && visibleTopicCards.isEmpty() -> {
                ShimmerCardList(count = 4)
            }
            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    if (visibleTopicCards.isEmpty()) {
                        item(key = "empty_state") {
                            EmptyStateView(showFavoritesOnly = showFavoritesOnly)
                        }
                    } else {
                        val groupedTopics = visibleTopicCards.groupBy { it.displayDate.ifEmpty { "Unknown" } }
                        groupedTopics.forEach { (dateLabel, topics) ->
                            item(key = "header_$dateLabel") {
                                DateHeader(dateLabel, topics.size)
                            }
                            items(topics, key = { it.id }) { topic ->
                                val isExpanded = expandedTopics[topic.id] ?: true
                                TopicGroupCard(
                                    topic = topic,
                                    favoriteIds = favoriteIds,
                                    isExpanded = isExpanded,
                                    onToggleExpand = {
                                        expandedTopics[topic.id] = !isExpanded
                                    },
                                    onSelectCard = onSelectCard,
                                    onToggleFavorite = onToggleFavorite
                                )
                            }
                        }
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
                                    text = "Loading...",
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

// --- Topic Group Card (collapsible, with entry animation) ---

@Composable
private fun TopicGroupCard(
    topic: TopicFeedCard,
    favoriteIds: Set<String>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectCard: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "chevron"
    )

    // Topic-level entry animation
    val topicScale = remember { androidx.compose.animation.core.Animatable(0.95f) }
    val topicAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(topic.id) {
        topicAlpha.animateTo(1f, animationSpec = tween(250, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(topic.id) {
        topicScale.animateTo(1f, animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = topicScale.value
                scaleY = topicScale.value
                alpha = topicAlpha.value
            },
        shape = RoundedCornerShape(10.dp),
        color = ZtTopicSurface,
        shadowElevation = 0.5.dp
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            )
        ) {
            // Topic header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ZtTopicSurface,
                onClick = onToggleExpand
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                        tint = ZtOnSurfaceVariant
                    )
                    Icon(
                        Icons.Outlined.Topic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = ZtCaption
                    )
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${topic.utteranceCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtOnSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    TopicStatusBadge(topic.status)
                }
            }

            // Summary or stage-3 "analyzing" indicator
            when {
                // Cooling = just stopped talking, topic being finalized
                topic.status == "cooling" && topic.summary.isBlank() -> {
                    TopicAnalyzingLabel(
                        modifier = Modifier.padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp)
                    )
                }
                topic.summary.isNotBlank() -> {
                    Text(
                        text = topic.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtCaption,
                        maxLines = if (isExpanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp)
                            .padding(bottom = if (isExpanded) 4.dp else 8.dp)
                    )
                }
            }

            // Expanded: child cards
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        color = ZtCardRowDivider,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        topic.utterances.forEach { utterance ->
                            TranscriptCardView(
                                card = utterance,
                                isFavorite = favoriteIds.contains(utterance.id),
                                onClick = { onSelectCard(utterance.id) },
                                onToggleFavorite = { onToggleFavorite(utterance.id) },
                                compact = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicStatusBadge(status: String) {
    val label = when (status) {
        "active" -> "Live"
        "cooling" -> "Cooling"
        "finalized" -> "Done"
        else -> status
    }
    val color = when (status) {
        "active" -> ZtPrimary
        "cooling" -> MaterialTheme.colorScheme.tertiary
        "finalized" -> ZtCaption
        else -> ZtCaption
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * Stage 3 indicator: Topic is cooling / being finalized.
 * "Analyzing conversation..." with pulsing animation.
 */
@Composable
private fun TopicAnalyzingLabel(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "topic_analyze")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "analyze_alpha"
    )
    Row(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Analyzing conversation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        AnimatedProcessingDots()
    }
}

// --- Filter Chip Row ---

@Composable
private fun FilterChipRow(
    options: List<String>,
    activeFilter: String,
    onFilterSelected: (String) -> Unit,
    topicCount: Int
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(options) { option ->
            val isSelected = option == activeFilter
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(option) },
                label = {
                    Text(
                        text = if (option == "All") "All ($topicCount)" else option,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ZtFilterSelected,
                    selectedLabelColor = ZtPrimary,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = ZtOnSurfaceVariant
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ZtFilterBorder,
                    selectedBorderColor = ZtPrimary.copy(alpha = 0.3f),
                    borderWidth = 0.5.dp,
                    selectedBorderWidth = 0.5.dp
                ),
                shape = RoundedCornerShape(6.dp)
            )
        }
    }
}

// --- Sub-composables ---

@Composable
private fun AmbientToggleChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surfaceVariant,
        onClick = { onToggle(!enabled) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Outlined.Mic else Icons.Outlined.MicNone,
                contentDescription = "Ambient",
                modifier = Modifier.size(14.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else ZtOnSurfaceVariant
            )
            Text(
                text = if (enabled) "Listening" else "Off",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else ZtOnSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchBar() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "Search",
                tint = ZtCaption,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Search transcripts...",
                style = MaterialTheme.typography.bodyMedium,
                color = ZtCaption,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.FilterList,
                contentDescription = "Filter",
                tint = ZtCaption,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        onClick = onGrant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Microphone access required",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Tap to grant permission",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun DateHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = ZtOnSurfaceVariant
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = ZtCaption
        )
    }
}

@Composable
private fun EmptyStateView(showFavoritesOnly: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (showFavoritesOnly) "No saved items" else "No transcripts yet",
                style = MaterialTheme.typography.titleMedium,
                color = ZtOnSurfaceVariant
            )
            Text(
                text = if (showFavoritesOnly) {
                    "Bookmark transcripts to find them here"
                } else {
                    "Turn on ambient listening to capture conversations"
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
    }
}

// --- Utility functions ---

private fun startAmbientService(context: Context) {
    val intent = Intent(context, AmbientRecordingService::class.java).apply {
        action = AmbientRecordingService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopAmbientService(context: Context) {
    val intent = Intent(context, AmbientRecordingService::class.java).apply {
        action = AmbientRecordingService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun formatTodayJa(): String {
    val formatter = DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN)
    return LocalDate.now().format(formatter)
}

package com.example.zero_touch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.AnalysisScreen
import com.example.zero_touch.ui.SettingsSheet
import com.example.zero_touch.ui.TimelineScreen
import com.example.zero_touch.ui.VoiceMemoScreen
import com.example.zero_touch.ui.ZeroTouchViewModel
import com.example.zero_touch.ui.components.AmbientDot
import com.example.zero_touch.ui.theme.ZerotouchTheme
import com.example.zero_touch.ui.theme.ZtAvatarBg
import com.example.zero_touch.ui.theme.ZtAvatarText
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
import com.example.zero_touch.ui.theme.ZtOutlineVariant
import com.example.zero_touch.ui.theme.ZtSidebarSelected
import com.example.zero_touch.ui.theme.ZtSidebarSurface
import com.example.zero_touch.ui.theme.ZtSurfaceVariant
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            ZerotouchTheme {
                ZeroTouchApp()
            }
        }
    }
}

@Composable
fun ZeroTouchApp(viewModel: ZeroTouchViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val ambientState by AmbientStatus.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var isSidebarCollapsed by remember { mutableStateOf(false) }
    var ambientEnabled by remember { mutableStateOf(AmbientPreferences.isAmbientEnabled(context)) }
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
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

    val requestNotificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                ambientEnabled = false
                AmbientPreferences.setAmbientEnabled(context, false)
                stopAmbientService(context)
            } else if (ambientEnabled) {
                startAmbientService(context)
            }
        }

    val requestRecordPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasRecordPermission = granted
            if (granted && ambientEnabled) {
                val notificationsOk =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission
                if (notificationsOk) {
                    startAmbientService(context)
                } else {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else if (!granted) {
                ambientEnabled = false
                AmbientPreferences.setAmbientEnabled(context, false)
                stopAmbientService(context)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadSessions(context)
        while (true) {
            delay(5000)
            viewModel.refreshSessions(context, showIndicator = false)
        }
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

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) {
            viewModel.loadFacts(context)
        }
    }

    if (showSettings) {
        SettingsSheet(
            deviceId = DeviceIdProvider.getDeviceId(context),
            onDismiss = { showSettings = false }
        )
    }

    val activeTopicCount = uiState.topicCards.count { it.status == "active" }
    val savedCount = uiState.favoriteIds.size
    val analysisCount = uiState.topicCards.count { (it.importanceLevel ?: -1) >= 3 }
    val currentPageTitle = when (selectedTab) {
        1 -> "タイムライン"
        2 -> "保存済み"
        3 -> "分析"
        else -> "ホーム"
    }
    val currentPageSubtitle = when (selectedTab) {
        1 -> "日付ごとにカードをたどって確認"
        2 -> if (savedCount > 0) "$savedCount 件のカードを保存中" else "あとで見返すカードをまとめて確認"
        3 -> if (analysisCount > 0) "$analysisCount 件の注目トピックを分析中" else "重要度と抽出結果の確認"
        else -> if (activeTopicCount > 0) "$activeTopicCount 件のライブトピックを監視中" else "会話カードとトピックを一覧で確認"
    }
    val ambientStatusLabel = when {
        ambientState.isRecording -> "Recording"
        ambientEnabled -> "Listening"
        else -> "Off"
    }

    val handleAmbientToggle: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            ambientEnabled = true
            AmbientPreferences.setAmbientEnabled(context, true)
            if (!hasRecordPermission) {
                requestRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startAmbientService(context)
            }
        } else {
            ambientEnabled = false
            AmbientPreferences.setAmbientEnabled(context, false)
            stopAmbientService(context)
            viewModel.evaluatePendingTopics(context, force = true, reason = "ambient_stop")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            ZeroTouchSidebar(
                isCollapsed = isSidebarCollapsed,
                selectedTab = selectedTab,
                activeTopicCount = activeTopicCount,
                savedCount = savedCount,
                analysisCount = analysisCount,
                ambientEnabled = ambientEnabled,
                isAmbientLive = ambientState.isRecording || ambientState.speech,
                onToggleSidebar = { isSidebarCollapsed = !isSidebarCollapsed },
                onSelectTab = { tab ->
                    selectedTab = tab
                    showSettings = false
                },
                onOpenSettings = { showSettings = true }
            )

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                color = Color.White
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    WorkspaceHeader(
                        title = currentPageTitle,
                        subtitle = currentPageSubtitle,
                        ambientStatusLabel = ambientStatusLabel,
                        isAmbientLive = ambientState.isRecording || ambientState.speech,
                        ambientEnabled = ambientEnabled,
                        onToggleAmbient = handleAmbientToggle
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (selectedTab) {
                            0 -> VoiceMemoScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                showFavoritesOnly = false,
                                ambientEnabled = ambientEnabled,
                                onDeleteCard = { id -> viewModel.deleteCard(context, id) },
                                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                                onSelectCard = { id -> viewModel.selectCard(id) },
                                onDismissDetail = { viewModel.clearSelection() },
                                onRefresh = { viewModel.refreshSessions(context) },
                                onLoadMore = { viewModel.loadMoreSessions(context) },
                                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
                            )
                            1 -> TimelineScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                onDeleteCard = { id -> viewModel.deleteCard(context, id) },
                                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                                onSelectCard = { id -> viewModel.selectCard(id) },
                                onDismissDetail = { viewModel.clearSelection() },
                                onRefresh = { viewModel.refreshSessions(context) },
                                onLoadMore = { viewModel.loadMoreSessions(context) },
                                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
                            )
                            2 -> VoiceMemoScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                showFavoritesOnly = true,
                                ambientEnabled = ambientEnabled,
                                onDeleteCard = { id -> viewModel.deleteCard(context, id) },
                                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                                onSelectCard = { id -> viewModel.selectCard(id) },
                                onDismissDetail = { viewModel.clearSelection() },
                                onRefresh = { viewModel.refreshSessions(context) },
                                onLoadMore = { viewModel.loadMoreSessions(context) },
                                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
                            )
                            3 -> AnalysisScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                onRefresh = { viewModel.refreshFacts(context) }
                            )
                            else -> VoiceMemoScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                showFavoritesOnly = false,
                                ambientEnabled = ambientEnabled,
                                onDeleteCard = { id -> viewModel.deleteCard(context, id) },
                                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                                onSelectCard = { id -> viewModel.selectCard(id) },
                                onDismissDetail = { viewModel.clearSelection() },
                                onRefresh = { viewModel.refreshSessions(context) },
                                onLoadMore = { viewModel.loadMoreSessions(context) },
                                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZeroTouchSidebar(
    isCollapsed: Boolean,
    selectedTab: Int,
    activeTopicCount: Int,
    savedCount: Int,
    analysisCount: Int,
    ambientEnabled: Boolean,
    isAmbientLive: Boolean,
    onToggleSidebar: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(if (isCollapsed) 72.dp else 236.dp)
            .fillMaxHeight(),
        color = ZtSidebarSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = if (isCollapsed) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = if (isCollapsed) {
                        Modifier
                            .size(40.dp)
                    } else {
                        Modifier.size(36.dp)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = ZtAvatarBg,
                    onClick = {
                        if (isCollapsed) {
                            onToggleSidebar()
                        }
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "ZT",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ZtAvatarText
                        )
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd)
                        ) {
                            AmbientDot(
                                isEnabled = isAmbientLive,
                                isRecording = isAmbientLive
                            )
                        }
                    }
                }

                if (!isCollapsed) {
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "ZeroTouch",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (ambientEnabled) "Workspace active" else "Workspace paused",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtCaption
                        )
                    }
                    IconButton(
                        onClick = onToggleSidebar,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "サイドバーを閉じる",
                            tint = ZtCaption,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.size(18.dp))

            if (!isCollapsed) {
                Text(
                    text = "メニュー",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtCaption,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.size(4.dp))
            }

            SidebarDestination(
                label = "ホーム",
                selected = selectedTab == 0,
                isCollapsed = isCollapsed,
                badgeCount = activeTopicCount,
                selectedIcon = {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "ホーム",
                        modifier = Modifier.size(20.dp)
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Home,
                        contentDescription = "ホーム",
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onSelectTab(0) }
            )
            SidebarDestination(
                label = "タイムライン",
                selected = selectedTab == 1,
                isCollapsed = isCollapsed,
                selectedIcon = {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = "タイムライン",
                        modifier = Modifier.size(20.dp)
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = "タイムライン",
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onSelectTab(1) }
            )
            SidebarDestination(
                label = "保存",
                selected = selectedTab == 2,
                isCollapsed = isCollapsed,
                badgeCount = savedCount,
                selectedIcon = {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = "保存",
                        modifier = Modifier.size(20.dp)
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkBorder,
                        contentDescription = "保存",
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onSelectTab(2) }
            )
            SidebarDestination(
                label = "分析",
                selected = selectedTab == 3,
                isCollapsed = isCollapsed,
                badgeCount = analysisCount,
                selectedIcon = {
                    Icon(
                        imageVector = Icons.Filled.Analytics,
                        contentDescription = "分析",
                        modifier = Modifier.size(20.dp)
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Analytics,
                        contentDescription = "分析",
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onSelectTab(3) }
            )

            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider(
                color = ZtOutlineVariant,
                thickness = 1.dp
            )
            Spacer(Modifier.size(10.dp))
            SidebarDestination(
                label = "設定",
                selected = false,
                isCollapsed = isCollapsed,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "設定",
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun SidebarDestination(
    label: String,
    selected: Boolean,
    isCollapsed: Boolean,
    badgeCount: Int = 0,
    selectedIcon: @Composable (() -> Unit)? = null,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(if (isCollapsed) 56.dp else 220.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) ZtSidebarSelected else Color.Transparent,
        onClick = onClick
    ) {
        val destinationIcon: @Composable () -> Unit = {
            if (selected && selectedIcon != null) {
                selectedIcon()
            } else {
                icon()
            }
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isCollapsed && badgeCount > 0) {
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(
                                text = badgeCount.toString(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                ) {
                    destinationIcon()
                }
            } else {
                destinationIcon()
            }

            if (!isCollapsed) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (badgeCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = ZtSurfaceVariant
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtOnSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    title: String,
    subtitle: String,
    ambientStatusLabel: String,
    isAmbientLive: Boolean,
    ambientEnabled: Boolean,
    onToggleAmbient: (Boolean) -> Unit
) {
    Column {
        Surface(
            color = Color.White
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtCaption
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = ZtSurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AmbientDot(
                            isEnabled = isAmbientLive || ambientEnabled,
                            isRecording = isAmbientLive
                        )
                        Text(
                            text = ambientStatusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = ZtOnSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                AmbientToggleSwitch(
                    enabled = ambientEnabled,
                    onToggle = onToggleAmbient
                )
            }
        }
        HorizontalDivider(color = ZtOutlineVariant, thickness = 1.dp)
    }
}

@Composable
private fun AmbientToggleSwitch(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val trackColor = if (enabled) MaterialTheme.colorScheme.onSurface else ZtOutlineVariant
    val alignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = trackColor,
        onClick = { onToggle(!enabled) }
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .size(width = 44.dp, height = 22.dp),
            contentAlignment = alignment
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(16.dp)
            ) {}
        }
    }
}

private fun startAmbientService(context: android.content.Context) {
    val intent = Intent(context, com.example.zero_touch.audio.ambient.AmbientRecordingService::class.java).apply {
        action = com.example.zero_touch.audio.ambient.AmbientRecordingService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopAmbientService(context: android.content.Context) {
    val intent = Intent(context, com.example.zero_touch.audio.ambient.AmbientRecordingService::class.java).apply {
        action = com.example.zero_touch.audio.ambient.AmbientRecordingService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
}

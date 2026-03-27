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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.zero_touch.ui.SettingsSheet
import com.example.zero_touch.ui.TimelineScreen
import com.example.zero_touch.ui.VoiceMemoScreen
import com.example.zero_touch.ui.ZeroTouchViewModel
import com.example.zero_touch.ui.components.AmbientDot
import com.example.zero_touch.ui.theme.ZerotouchTheme
import com.example.zero_touch.ui.theme.ZtAvatarBg
import com.example.zero_touch.ui.theme.ZtAvatarText
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOutlineVariant
import com.example.zero_touch.ui.theme.ZtSuccess
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroTouchApp(viewModel: ZeroTouchViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val ambientState by AmbientStatus.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
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
            viewModel.refreshSessions(context)
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

    if (showSettings) {
        SettingsSheet(
            deviceId = DeviceIdProvider.getDeviceId(context),
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "ZeroTouch",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // User avatar mock with ambient dot overlay
                    IconButton(onClick = { }) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                modifier = Modifier.size(30.dp),
                                shape = CircleShape,
                                color = ZtAvatarBg
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "K",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ZtAvatarText
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 2.dp, y = 2.dp)
                            ) {
                                AmbientDot(
                                    isEnabled = ambientState.isRecording || ambientState.speech,
                                    isRecording = ambientState.isRecording
                                )
                            }
                        }
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "アンビエント",
                            style = MaterialTheme.typography.labelMedium,
                            color = ZtCaption
                        )
                        Spacer(Modifier.width(8.dp))
                        AmbientToggleSwitch(
                            enabled = ambientEnabled,
                            onToggle = { enabled ->
                                if (enabled) {
                                    ambientEnabled = true
                                    AmbientPreferences.setAmbientEnabled(context, true)
                                    if (!hasRecordPermission) {
                                        requestRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@AmbientToggleSwitch
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        return@AmbientToggleSwitch
                                    }
                                    startAmbientService(context)
                                } else {
                                    ambientEnabled = false
                                    AmbientPreferences.setAmbientEnabled(context, false)
                                    stopAmbientService(context)
                                    viewModel.evaluatePendingTopics(context, force = true, reason = "ambient_stop")
                                }
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 0.dp
                ) {
                    // Home
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            showSettings = false
                        },
                        label = { Text("ホーム", style = MaterialTheme.typography.labelMedium) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    val activeCount = uiState.topicCards.count { it.status == "active" }
                                    if (activeCount > 0 && selectedTab != 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ) {
                                            Text("$activeCount", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                                    contentDescription = "ホーム",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = ZtCaption,
                            unselectedTextColor = ZtCaption,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    // Timeline
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            showSettings = false
                        },
                        label = { Text("タイムライン", style = MaterialTheme.typography.labelMedium) },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 1) Icons.Filled.Schedule else Icons.Outlined.Schedule,
                                contentDescription = "タイムライン",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = ZtCaption,
                            unselectedTextColor = ZtCaption,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    // Saved
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            showSettings = false
                        },
                        label = { Text("保存", style = MaterialTheme.typography.labelMedium) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    val savedCount = uiState.favoriteIds.size
                                    if (savedCount > 0 && selectedTab != 2) {
                                        Badge(
                                            containerColor = ZtCaption,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ) {
                                            Text("$savedCount", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (selectedTab == 2) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = "保存",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = ZtCaption,
                            unselectedTextColor = ZtCaption,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    // Settings
                    NavigationBarItem(
                        selected = showSettings,
                        onClick = { showSettings = true },
                        label = { Text("設定", style = MaterialTheme.typography.labelMedium) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "設定",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = ZtCaption,
                            unselectedTextColor = ZtCaption,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> VoiceMemoScreen(
                modifier = Modifier.padding(innerPadding),
                uiState = uiState,
                showFavoritesOnly = false,
                ambientEnabled = ambientEnabled,
                onDeleteCard = { id -> viewModel.dismissCard(id) },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onSelectCard = { id -> viewModel.selectCard(id) },
                onDismissDetail = { viewModel.clearSelection() },
                onLoadMore = { viewModel.loadMoreSessions(context) },
                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
            )
            1 -> TimelineScreen(
                modifier = Modifier.padding(innerPadding),
                uiState = uiState,
                onDeleteCard = { id -> viewModel.dismissCard(id) },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onSelectCard = { id -> viewModel.selectCard(id) },
                onDismissDetail = { viewModel.clearSelection() },
                onLoadMore = { viewModel.loadMoreSessions(context) },
                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
            )
            else -> VoiceMemoScreen(
                modifier = Modifier.padding(innerPadding),
                uiState = uiState,
                showFavoritesOnly = true,
                ambientEnabled = ambientEnabled,
                onDeleteCard = { id -> viewModel.dismissCard(id) },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onSelectCard = { id -> viewModel.selectCard(id) },
                onDismissDetail = { viewModel.clearSelection() },
                onLoadMore = { viewModel.loadMoreSessions(context) },
                onRetranscribeEnglish = { id -> viewModel.retranscribeSession(context, id, language = "en") },
                onRetryTranscribe = { id -> viewModel.retryTranscribeSession(context, id) }
            )
        }
    }
}

@Composable
private fun AmbientToggleSwitch(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val trackColor = if (enabled) ZtSuccess else ZtOutlineVariant
    val alignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = trackColor,
        onClick = { onToggle(!enabled) }
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .size(width = 46.dp, height = 24.dp),
            contentAlignment = alignment
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(18.dp)
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

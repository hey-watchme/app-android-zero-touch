package com.example.zero_touch

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.audio.ambient.AmbientStatus
import com.example.zero_touch.ui.SettingsSheet
import com.example.zero_touch.ui.TimelineScreen
import com.example.zero_touch.ui.VoiceMemoScreen
import com.example.zero_touch.ui.ZeroTouchViewModel
import com.example.zero_touch.ui.components.AmbientDot
import com.example.zero_touch.ui.theme.ZerotouchTheme
import com.example.zero_touch.ui.theme.ZtCaption
import com.example.zero_touch.ui.theme.ZtOnSurfaceVariant
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

    LaunchedEffect(Unit) {
        viewModel.loadSessions(context)
        while (true) {
            delay(5000)
            viewModel.refreshSessions(context)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Settings bottom sheet
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
                    // Ambient status dot
                    IconButton(onClick = { }) {
                        AmbientDot(
                            isEnabled = ambientState.isRecording || ambientState.speech,
                            isRecording = ambientState.isRecording
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(22.dp),
                            tint = ZtOnSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = {
                        Text(
                            "Home",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "Home",
                            modifier = Modifier.size(22.dp)
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
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = {
                        Text(
                            "Timeline",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 1) Icons.Filled.Schedule else Icons.Outlined.Schedule,
                            contentDescription = "Timeline",
                            modifier = Modifier.size(22.dp)
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
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = {
                        Text(
                            "Saved",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 2) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Saved",
                            modifier = Modifier.size(22.dp)
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
    ) { innerPadding ->
        when (selectedTab) {
            0 -> VoiceMemoScreen(
                modifier = Modifier.padding(innerPadding),
                uiState = uiState,
                showFavoritesOnly = false,
                onDeleteCard = { id -> viewModel.dismissCard(id) },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onSelectCard = { id -> viewModel.selectCard(id) },
                onDismissDetail = { viewModel.clearSelection() },
                onLoadMore = { viewModel.loadMoreSessions(context) }
            )
            1 -> TimelineScreen(
                modifier = Modifier.padding(innerPadding),
                uiState = uiState,
                onDeleteCard = { id -> viewModel.dismissCard(id) },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onSelectCard = { id -> viewModel.selectCard(id) },
                onDismissDetail = { viewModel.clearSelection() },
                onLoadMore = { viewModel.loadMoreSessions(context) }
            )
            else -> VoiceMemoScreen(
                modifier = Modifier.padding(innerPadding),
                uiState = uiState,
                showFavoritesOnly = true,
                onDeleteCard = { id -> viewModel.dismissCard(id) },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onSelectCard = { id -> viewModel.selectCard(id) },
                onDismissDetail = { viewModel.clearSelection() },
                onLoadMore = { viewModel.loadMoreSessions(context) }
            )
        }
    }
}

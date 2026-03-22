package com.example.zero_touch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zero_touch.ui.CardDetailScreen
import com.example.zero_touch.ui.SessionListScreen
import com.example.zero_touch.ui.VoiceMemoScreen
import com.example.zero_touch.ui.ZeroTouchViewModel
import com.example.zero_touch.ui.theme.ZerotouchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Load sessions on first launch
    LaunchedEffect(Unit) {
        viewModel.loadSessions(context)
    }

    // Show errors via snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Navigate to detail on upload success
    LaunchedEffect(uiState.uploadSuccess) {
        uiState.uploadSuccess?.let { sessionId ->
            viewModel.loadSessionDetail(sessionId)
            selectedTab = 2
            viewModel.clearUploadSuccess()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Record") },
                    icon = {}
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.loadSessions(context)
                    },
                    label = { Text("Sessions") },
                    icon = {}
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("Detail") },
                    icon = {}
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> VoiceMemoScreen(
                modifier = Modifier.padding(innerPadding),
                onUpload = { file ->
                    viewModel.uploadAndProcess(context, file)
                }
            )
            1 -> SessionListScreen(
                sessions = uiState.sessions,
                isLoading = uiState.isLoading,
                onSessionClick = { sessionId ->
                    viewModel.loadSessionDetail(sessionId)
                    selectedTab = 2
                },
                modifier = Modifier.padding(innerPadding)
            )
            2 -> CardDetailScreen(
                uiState = uiState,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

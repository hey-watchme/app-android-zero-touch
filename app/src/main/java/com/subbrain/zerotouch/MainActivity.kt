package com.subbrain.zerotouch

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.subbrain.zerotouch.api.DeviceIdProvider
import com.subbrain.zerotouch.api.ContextPreferences
import com.subbrain.zerotouch.api.DeviceSummary
import com.subbrain.zerotouch.api.OrganizationSummary
import com.subbrain.zerotouch.api.WorkspaceMemberSummary
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import com.subbrain.zerotouch.ui.QueryWebViewScreen
import com.subbrain.zerotouch.ui.SettingsSheet
import com.subbrain.zerotouch.ui.TimelineScreen
import com.subbrain.zerotouch.ui.HomeDashboardScreen
import com.subbrain.zerotouch.ui.MemoLiveHomeScreen
import com.subbrain.zerotouch.ui.VoiceMemoScreen
import com.subbrain.zerotouch.ui.WikiScreen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.outlined.Dashboard
import com.subbrain.zerotouch.ui.ZeroTouchUiState
import com.subbrain.zerotouch.ui.ZeroTouchViewModel
import com.subbrain.zerotouch.ui.context.ContextOnboardingScreen
import com.subbrain.zerotouch.ui.context.ContextProfileDraft
import com.subbrain.zerotouch.ui.context.toRequest
import com.subbrain.zerotouch.ui.components.AmbientDot
import com.subbrain.zerotouch.ui.components.SideDetailDrawer
import com.subbrain.zerotouch.ui.theme.ZerotouchTheme
import com.subbrain.zerotouch.ui.theme.ZtBackground
import com.subbrain.zerotouch.ui.theme.ZtAvatarBg
import com.subbrain.zerotouch.ui.theme.ZtAvatarText
import com.subbrain.zerotouch.ui.theme.ZtBlack
import com.subbrain.zerotouch.ui.theme.ZtBlackSoft
import com.subbrain.zerotouch.ui.theme.ZtError
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtGlassSurface
import com.subbrain.zerotouch.ui.theme.ZtLoginBgTop
import com.subbrain.zerotouch.ui.theme.ZtLoginBgBottom
import com.subbrain.zerotouch.ui.theme.ZtOnBackground
import com.subbrain.zerotouch.ui.theme.ZtOnSurface
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtOutlineVariant
import com.subbrain.zerotouch.ui.theme.ZtPrimary
import com.subbrain.zerotouch.ui.theme.ZtPrimaryContainer
import com.subbrain.zerotouch.ui.theme.ZtSidebarDivider
import com.subbrain.zerotouch.ui.theme.ZtSidebarSelected
import com.subbrain.zerotouch.ui.theme.ZtSidebarSurface
import com.subbrain.zerotouch.ui.theme.ZtSidebarText
import com.subbrain.zerotouch.ui.theme.ZtSidebarTextMuted
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceElevated
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.activity.SystemBarStyle

private const val AMICAL_TEST_EMAIL = "amical-test@zerotouch.local"
private const val AMICAL_TEST_PASSWORD = "AmicalTest123!"
private const val AUTO_REFRESH_INTERVAL_MS = 10_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Status bar: transparent bg with white (light) icons to match dark sidebar
        // Navigation bar: fully transparent — removes the white gap at the bottom
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
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
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showWorkspaceDrawer by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showOrgDrawer by remember { mutableStateOf(false) }
    var selectedOrgForDrawer by remember { mutableStateOf<OrganizationSummary?>(null) }
    var showDeviceDrawer by remember { mutableStateOf(false) }
    var selectedDeviceForDrawer by remember { mutableStateOf<DeviceSummary?>(null) }
    var showContextOnboarding by remember { mutableStateOf(false) }
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

    val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    val googleSignInClient = remember(googleWebClientId) {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (googleWebClientId.isNotBlank()) {
            builder.requestIdToken(googleWebClientId)
        }
        GoogleSignIn.getClient(context, builder.build())
    }
    val googleSignInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken.isNullOrBlank()) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Google IDトークンが取得できませんでした")
                    }
                    return@rememberLauncherForActivityResult
                }
                viewModel.signInWithGoogle(context, idToken, null)
            } catch (e: ApiException) {
                scope.launch {
                    snackbarHostState.showSnackbar("Googleログインに失敗しました: ${e.localizedMessage}")
                }
            }
        }

    val latestUiState by rememberUpdatedState(uiState)
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestAmbientEnabled by rememberUpdatedState(ambientEnabled)
    val latestIsRecording by rememberUpdatedState(ambientState.isRecording)
    val latestShowWorkspaceDrawer by rememberUpdatedState(showWorkspaceDrawer)
    val latestShowAccountSheet by rememberUpdatedState(showAccountSheet)
    val latestShowContextOnboarding by rememberUpdatedState(showContextOnboarding)

    LaunchedEffect(Unit) {
        viewModel.loadAuthSession(context)
    }

    LaunchedEffect(showWorkspaceDrawer) {
        if (showWorkspaceDrawer && uiState.authSession != null) {
            viewModel.loadSelection(context, force = true)
        }
    }

    LaunchedEffect(uiState.authSession?.userId) {
        if (uiState.authSession == null) return@LaunchedEffect
        viewModel.loadSelection(context, force = true, loadDataAfter = true)
        while (true) {
            delay(AUTO_REFRESH_INTERVAL_MS)
            val state = latestUiState
            val shouldAutoRefresh = latestAmbientEnabled || latestIsRecording
            if (!shouldAutoRefresh) continue
            if (latestSelectedTab != 0) continue
            if (state.isLoadingSelection || state.isLoading || state.isLoadingMore || state.isRefreshing) continue
            if (latestShowWorkspaceDrawer || latestShowAccountSheet || latestShowContextOnboarding) continue
            if (state.authSession != null) {
                viewModel.refreshSessions(context, showIndicator = false)
            }
        }
    }

    LaunchedEffect(ambientState.eventSeq) {
        val event = ambientState.lastEvent ?: return@LaunchedEffect
        viewModel.handleAmbientEvent(context, event)
    }

    LaunchedEffect(
        ambientState.speech,
        ambientState.isRecording,
        ambientState.recordingElapsedMs,
        ambientState.recordings,
        ambientState.eventSeq
    ) {
        viewModel.updateHomeLiveInput(ambientState)
    }

    LaunchedEffect(uiState.selectedWorkspaceId, uiState.isLoadingSelection) {
        val workspaceId = uiState.selectedWorkspaceId
        if (!uiState.isLoadingSelection && !workspaceId.isNullOrBlank()) {
            viewModel.loadContextProfile(workspaceId)
            val completed = ContextPreferences.isOnboardingCompleted(context, workspaceId)
            if (!completed) {
                showContextOnboarding = true
            }
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
        viewModel.loadSessions(context)
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

    val launchGoogleSignIn: () -> Unit = {
        if (googleWebClientId.isBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("GOOGLE_WEB_CLIENT_ID が未設定です")
            }
        } else {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
        Unit
    }

    if (showSettings) {
        SettingsSheet(
            deviceId = DeviceIdProvider.getDeviceId(context),
            onDismiss = { showSettings = false }
        )
    }

    val activeTopicCount = uiState.topicCards.count { it.status == "active" }
    val physicalDeviceId = DeviceIdProvider.getDeviceId(context)
    val selectedAccount = uiState.accounts.firstOrNull { it.id == uiState.selectedAccountId }
    val selectedWorkspace = uiState.workspaces.firstOrNull { it.id == uiState.selectedWorkspaceId }
    val selectedDevice = uiState.devices.firstOrNull { it.device_id == physicalDeviceId }
    val selectedWorkspaceId = uiState.selectedWorkspaceId
    val accountLabel = selectedAccount?.display_name
        ?: uiState.authSession?.displayName
        ?: uiState.authSession?.email
        ?: "未選択"
    val workspaceLabel = selectedWorkspace?.name ?: "未選択"
    val deviceLabel = selectedDevice?.display_name?.takeIf { it.isNotBlank() } ?: physicalDeviceId
    val contextDraft = ContextProfileDraft.fromProfile(
        profile = uiState.contextProfile,
        workspaceName = workspaceLabel,
        ownerName = accountLabel
    )
    val currentPageTitle = when (selectedTab) {
        1 -> "Dashboard"
        2 -> "タイムライン"
        3 -> "Wiki"
        4 -> "Query"
        else -> "Home"
    }
    val currentPageSubtitle = when (selectedTab) {
        1 -> "ZeroTouch の事後変換と業務下書きを確認"
        2 -> "日付ごとにカードをたどって確認"
        3 -> "ナレッジベースを閲覧"
        4 -> "Wikiに質問する"
        else -> if (activeTopicCount > 0) "$activeTopicCount 件のライブトピックを監視中" else "MeMo Live を開始"
    }
    val ambientStatusLabel = when {
        ambientState.isRecording -> "Recording"
        ambientEnabled -> "Listening"
        else -> "Off"
    }

    if (showWorkspaceDrawer) {
        WorkspaceDrawer(
            uiState = uiState,
            physicalDeviceId = physicalDeviceId,
            onClose = { showWorkspaceDrawer = false },
            onSelectWorkspace = { workspaceId ->
                viewModel.selectWorkspace(context, workspaceId)
                viewModel.loadSessions(context)
                viewModel.loadFacts(context, force = true)
            },
            onOpenOrgDrawer = { org ->
                selectedOrgForDrawer = org
                showOrgDrawer = true
            },
            onOpenDeviceDrawer = { device ->
                selectedDeviceForDrawer = device
                showDeviceDrawer = true
            },
            onSaveProfile = { workspaceName, workspaceDescription, deviceName, draft ->
                viewModel.saveMyPageProfile(
                    accountDisplayName = null,
                    workspaceName = workspaceName,
                    workspaceDescription = workspaceDescription,
                    deviceDisplayName = deviceName,
                    contextRequest = draft.toRequest(),
                    successMessage = "ワークスペースを保存しました"
                )
            }
        )
    }

    if (showAccountSheet) {
        AccountSheet(
            uiState = uiState,
            initialDraft = contextDraft,
            isSaving = uiState.isSavingContext,
            onClose = { showAccountSheet = false },
            onSignOut = {
                googleSignInClient.signOut()
                viewModel.signOut(context)
                showAccountSheet = false
            },
            onSaveProfile = { accountName, draft ->
                viewModel.saveMyPageProfile(
                    accountDisplayName = accountName,
                    workspaceName = null,
                    workspaceDescription = null,
                    deviceDisplayName = null,
                    contextRequest = draft.toRequest(),
                    successMessage = "プロフィールを保存しました"
                )
            }
        )
    }

    if (showOrgDrawer) {
        selectedOrgForDrawer?.let { org ->
            OrgDrawer(
                org = org,
                uiState = uiState,
                onClose = { showOrgDrawer = false }
            )
        }
    }

    if (showDeviceDrawer) {
        selectedDeviceForDrawer?.let { device ->
            DeviceDrawer(
                device = device,
                uiState = uiState,
                onClose = { showDeviceDrawer = false }
            )
        }
    }

    if (!uiState.isAuthReady) {
        AuthLoadingScreen()
        return
    }

    if (uiState.authSession == null) {
        LoginScreen(
            isAuthenticating = uiState.isAuthenticating,
            onGoogleSignIn = launchGoogleSignIn,
            onEmailSignIn = { email, password ->
                viewModel.signInWithEmailPassword(context, email, password)
            }
        )
        return
    }

    if (
        uiState.isLoadingSelection &&
        uiState.accounts.isEmpty() &&
        uiState.workspaces.isEmpty() &&
        uiState.devices.isEmpty()
    ) {
        AuthLoadingScreen()
        return
    }

    if (showContextOnboarding && !selectedWorkspaceId.isNullOrBlank()) {
        ContextOnboardingScreen(
            initialDraft = contextDraft,
            isSaving = uiState.isSavingContext,
            onSkip = {
                ContextPreferences.setOnboardingCompleted(context, selectedWorkspaceId, true)
                showContextOnboarding = false
            },
            onComplete = { draft ->
                viewModel.saveContextProfile(
                    workspaceId = selectedWorkspaceId,
                    request = draft.toRequest(),
                    successMessage = "コンテクストを保存しました"
                )
                ContextPreferences.setOnboardingCompleted(context, selectedWorkspaceId, true)
                showContextOnboarding = false
            }
        )
        return
    }

    val handleAmbientToggle: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            ambientEnabled = true
            AmbientPreferences.setAmbientEnabled(context, true)
            viewModel.loadSessions(context)
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
        // Use sidebar color as the window-wide background so the nav bar area is never white
        containerColor = ZtBlack,
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ZtBackground)
        ) {
            ZeroTouchSidebar(
                isCollapsed = isSidebarCollapsed,
                selectedTab = selectedTab,
                activeTopicCount = activeTopicCount,
                accountLabel = accountLabel,
                accountAvatarUrl = uiState.authSession?.avatarUrl,
                workspaceLabel = workspaceLabel,
                deviceLabel = deviceLabel,
                isAmbientLive = ambientState.isRecording || ambientState.speech,
                onToggleSidebar = { isSidebarCollapsed = !isSidebarCollapsed },
                onSelectTab = { tab ->
                    selectedTab = tab
                    showSettings = false
                },
                onOpenWorkspaceDrawer = { showWorkspaceDrawer = true },
                onOpenAccountSheet = { showAccountSheet = true },
                onOpenSettings = { showSettings = true }
            )

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                color = Color.White
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (selectedTab != 0) {
                        WorkspaceHeader(
                            title = currentPageTitle,
                            subtitle = currentPageSubtitle,
                            ambientStatusLabel = ambientStatusLabel,
                            isAmbientLive = ambientState.isRecording || ambientState.speech,
                            ambientEnabled = ambientEnabled,
                            onToggleAmbient = handleAmbientToggle
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (selectedTab) {
                            0 -> MemoLiveHomeScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                workspaceLabel = workspaceLabel,
                                ambientEnabled = ambientEnabled,
                                ambientStatusLabel = ambientStatusLabel,
                                isAmbientLive = ambientState.isRecording || ambientState.speech,
                                liveSessionId = ambientState.liveSessionId,
                                liveShareToken = ambientState.liveShareToken,
                                liveTranscriptLatest = ambientState.liveTranscriptLatest,
                                liveTranscriptHistory = ambientState.liveTranscriptHistory,
                                onToggleAmbient = handleAmbientToggle
                            )
                            1 -> HomeDashboardScreen(
                                modifier = Modifier.fillMaxSize(),
                                uiState = uiState,
                                ambientEnabled = ambientEnabled,
                                workspaceLabel = workspaceLabel,
                                deviceLabel = deviceLabel,
                                onToggleAmbient = handleAmbientToggle,
                                onDeleteCard = { id -> viewModel.deleteCard(context, id) },
                                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                                onSelectCard = { id -> viewModel.selectCard(id) },
                                onDismissDetail = { viewModel.clearSelection() }
                            )
                            2 -> TimelineScreen(
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
                            3 -> WikiScreen(
                                modifier = Modifier.fillMaxSize(),
                                deviceId = uiState.selectedDeviceId
                            )
                            4 -> QueryWebViewScreen(modifier = Modifier.fillMaxSize())
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

// ZT brand logo mark — replaced with real logo later
@Composable
private fun ZtLogoMark(size: Int = 48) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(ZtBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "ZT",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = (size * 0.25f).sp,
            letterSpacing = (-0.5).sp
        )
    }
}

@Composable
private fun LoginScreen(
    isAuthenticating: Boolean,
    onGoogleSignIn: () -> Unit,
    onEmailSignIn: (String, String) -> Unit
) {
    var email by remember { mutableStateOf(AMICAL_TEST_EMAIL) }
    var password by remember { mutableStateOf(AMICAL_TEST_PASSWORD) }
    @Suppress("UNUSED_VARIABLE")
    var showPassword by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(ZtLoginBgTop, ZtLoginBgBottom)
                )
            )
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // Frosted glass card
        Surface(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(24.dp),
            shape = RoundedCornerShape(28.dp),
            color = ZtGlassSurface,
            shadowElevation = 28.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ZtLogoMark(size = 52)

                Spacer(Modifier.height(2.dp))

                Text(
                    text = "Log in",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = ZtOnBackground
                )

                Text(
                    text = "ZeroTouch へようこそ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtCaption
                )

                Spacer(Modifier.height(6.dp))

                // Email field — filled style
                TextField(
                    value = email,
                    onValueChange = { email = it },
                    singleLine = true,
                    enabled = !isAuthenticating,
                    placeholder = { Text("メールアドレス", color = ZtCaption) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFFF0EFE9),
                        focusedContainerColor = Color(0xFFEAE9E2),
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    )
                )

                // Password field
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    enabled = !isAuthenticating,
                    placeholder = { Text("パスワード", color = ZtCaption) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = Color(0xFFF0EFE9),
                        focusedContainerColor = Color(0xFFEAE9E2),
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    )
                )

                Spacer(Modifier.height(4.dp))

                // Primary black button
                Button(
                    onClick = { onEmailSignIn(email, password) },
                    enabled = !isAuthenticating && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ZtBlack,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFD0CFCB),
                        disabledContentColor = Color.White
                    )
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "ログイン",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = ZtOutline)
                    Text(
                        text = "  または  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZtCaption
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = ZtOutline)
                }

                // Google outlined button
                OutlinedButton(
                    onClick = onGoogleSignIn,
                    enabled = !isAuthenticating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, ZtBlackSoft),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ZtOnBackground
                    )
                ) {
                    Text(
                        text = if (isAuthenticating) "Google ログイン中..." else "Google でログイン",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZtBackground)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ZtLogoMark(size = 52)
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = ZtBlack,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun ZeroTouchSidebar(
    isCollapsed: Boolean,
    selectedTab: Int,
    activeTopicCount: Int,
    accountLabel: String,
    accountAvatarUrl: String?,
    workspaceLabel: String,
    deviceLabel: String,
    isAmbientLive: Boolean,
    onToggleSidebar: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onOpenWorkspaceDrawer: () -> Unit,
    onOpenAccountSheet: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(if (isCollapsed) 72.dp else 236.dp)
            .fillMaxHeight(),
        color = ZtSidebarSurface
    ) {
        // All child composables inherit white as LocalContentColor
        CompositionLocalProvider(LocalContentColor provides ZtSidebarText) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 14.dp)
            ) {
                // ── Header (workspace + collapse button) ────────────
                if (isCollapsed) {
                    // Workspace letter avatar — click opens workspace drawer
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2A))
                            .clickable { onOpenWorkspaceDrawer() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = workspaceLabel.trim().firstOrNull()?.uppercase() ?: "W",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // Expand button below workspace icon
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleSidebar() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription = "Expand sidebar",
                            tint = ZtSidebarTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    // Workspace row (tappable) + collapse button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenWorkspaceDrawer() }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF2A2A2A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = workspaceLabel.trim().firstOrNull()?.uppercase() ?: "W",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = workspaceLabel.ifBlank { "Workspace" },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowLeft,
                                contentDescription = "Collapse sidebar",
                                tint = ZtSidebarTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = ZtSidebarDivider, thickness = 1.dp)
                }

                Spacer(Modifier.size(14.dp))

                if (!isCollapsed) {
                    Text(
                        text = "MENU",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtSidebarTextMuted,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                }

                SidebarDestination(
                    label = "Home",
                    selected = selectedTab == 0,
                    isCollapsed = isCollapsed,
                    badgeCount = activeTopicCount,
                    selectedIcon = {
                        Icon(imageVector = Icons.Filled.Home, contentDescription = "Home", modifier = Modifier.size(20.dp))
                    },
                    icon = {
                        Icon(imageVector = Icons.Outlined.Home, contentDescription = "Home", modifier = Modifier.size(20.dp))
                    },
                    onClick = { onSelectTab(0) }
                )
                SidebarDestination(
                    label = "Dashboard",
                    selected = selectedTab == 1,
                    isCollapsed = isCollapsed,
                    selectedIcon = {
                        Icon(imageVector = Icons.Filled.Dashboard, contentDescription = "Dashboard", modifier = Modifier.size(20.dp))
                    },
                    icon = {
                        Icon(imageVector = Icons.Outlined.Dashboard, contentDescription = "Dashboard", modifier = Modifier.size(20.dp))
                    },
                    onClick = { onSelectTab(1) }
                )
                SidebarDestination(
                    label = "Timeline",
                    selected = selectedTab == 2,
                    isCollapsed = isCollapsed,
                    selectedIcon = {
                        Icon(imageVector = Icons.Filled.Schedule, contentDescription = "Timeline", modifier = Modifier.size(20.dp))
                    },
                    icon = {
                        Icon(imageVector = Icons.Outlined.Schedule, contentDescription = "Timeline", modifier = Modifier.size(20.dp))
                    },
                    onClick = { onSelectTab(2) }
                )
                SidebarDestination(
                    label = "Wiki",
                    selected = selectedTab == 3,
                    isCollapsed = isCollapsed,
                    selectedIcon = {
                        Icon(imageVector = Icons.Filled.MenuBook, contentDescription = "Wiki", modifier = Modifier.size(20.dp))
                    },
                    icon = {
                        Icon(imageVector = Icons.Outlined.MenuBook, contentDescription = "Wiki", modifier = Modifier.size(20.dp))
                    },
                    onClick = { onSelectTab(3) }
                )
                SidebarDestination(
                    label = "Query",
                    selected = selectedTab == 4,
                    isCollapsed = isCollapsed,
                    selectedIcon = {
                        Icon(imageVector = Icons.Filled.QuestionAnswer, contentDescription = "Query", modifier = Modifier.size(20.dp))
                    },
                    icon = {
                        Icon(imageVector = Icons.Outlined.QuestionAnswer, contentDescription = "Query", modifier = Modifier.size(20.dp))
                    },
                    onClick = { onSelectTab(4) }
                )

                // ── Bottom: account avatar + settings ────────────────
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(color = ZtSidebarDivider, thickness = 1.dp)
                Spacer(Modifier.size(8.dp))

                // Avatar row
                if (isCollapsed) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2A))
                            .clickable { onOpenAccountSheet() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!accountAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(accountAvatarUrl).crossfade(true).build(),
                                contentDescription = "Account avatar",
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            val initial = accountLabel.trim().firstOrNull()?.toString() ?: "A"
                            Text(
                                text = initial.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                            AmbientDot(isEnabled = isAmbientLive, isRecording = isAmbientLive)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenAccountSheet() }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2A2A2A)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!accountAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(accountAvatarUrl).crossfade(true).build(),
                                    contentDescription = "Account avatar",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                val initial = accountLabel.trim().firstOrNull()?.toString() ?: "A"
                                Text(
                                    text = initial.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                                AmbientDot(isEnabled = isAmbientLive, isRecording = isAmbientLive)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = accountLabel.ifBlank { "未選択" },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = workspaceLabel.ifBlank { "未選択" },
                                style = MaterialTheme.typography.labelSmall,
                                color = ZtSidebarTextMuted,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(Modifier.size(4.dp))
                SidebarDestination(
                    label = "設定",
                    selected = false,
                    isCollapsed = isCollapsed,
                    icon = {
                        Icon(imageVector = Icons.Outlined.Settings, contentDescription = "設定", modifier = Modifier.size(20.dp))
                    },
                    onClick = onOpenSettings
                )
            }
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
    val iconColor = if (selected) Color.White else ZtSidebarTextMuted
    val textColor = if (selected) Color.White else ZtSidebarTextMuted
    val bgColor = if (selected) ZtSidebarSelected else Color.Transparent

    Surface(
        modifier = Modifier.width(if (isCollapsed) 56.dp else 220.dp),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val destinationIcon: @Composable () -> Unit = {
                if (selected && selectedIcon != null) selectedIcon() else icon()
            }
            // Provide icon color via CompositionLocal
            CompositionLocalProvider(LocalContentColor provides iconColor) {
                if (isCollapsed && badgeCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = Color.White, contentColor = ZtBlack) {
                                Text(badgeCount.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    ) { destinationIcon() }
                } else {
                    destinationIcon()
                }
            }

            if (!isCollapsed) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )
                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF2A2A2A))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSwitcher(
    accountLabel: String,
    workspaceLabel: String,
    deviceLabel: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = ZtSurfaceVariant,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "データソース",
                style = MaterialTheme.typography.labelMedium,
                color = ZtCaption
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Account: $accountLabel",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Workspace: $workspaceLabel",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Device: $deviceLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
            }
        }
    }
}

// ── WorkspaceDrawer ───────────────────────────────────────────────────────────
@Composable
private fun WorkspaceDrawer(
    uiState: ZeroTouchUiState,
    physicalDeviceId: String,
    onClose: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onOpenOrgDrawer: (OrganizationSummary) -> Unit,
    onOpenDeviceDrawer: (DeviceSummary) -> Unit,
    onSaveProfile: (String, String, String, ContextProfileDraft) -> Unit
) {
    val selectedWorkspaceId = uiState.selectedWorkspaceId
    val currentWorkspace = uiState.workspaces.firstOrNull { it.id == selectedWorkspaceId }
    val ownerAccount = uiState.accounts.firstOrNull { it.id == currentWorkspace?.owner_account_id }
    val workspaceDevices = uiState.devices.filter { it.workspace_id == selectedWorkspaceId }
    val physicalDevice = uiState.devices.firstOrNull { it.device_id == physicalDeviceId }
    var isEditing by remember { mutableStateOf(false) }
    var workspaceName by remember { mutableStateOf(currentWorkspace?.name.orEmpty()) }
    var workspaceDescription by remember { mutableStateOf(currentWorkspace?.description.orEmpty()) }
    var deviceName by remember { mutableStateOf(physicalDevice?.display_name.orEmpty()) }
    var draft by remember {
        mutableStateOf(
            ContextProfileDraft.fromProfile(
                uiState.contextProfile,
                currentWorkspace?.name.orEmpty(),
                ownerAccount?.display_name.orEmpty()
            )
        )
    }
    var pendingSave by remember { mutableStateOf(false) }

    LaunchedEffect(currentWorkspace?.id, physicalDevice?.id) {
        if (!isEditing) {
            workspaceName = currentWorkspace?.name.orEmpty()
            workspaceDescription = currentWorkspace?.description.orEmpty()
            deviceName = physicalDevice?.display_name.orEmpty()
        }
    }
    LaunchedEffect(uiState.isSavingContext, uiState.message, uiState.error, pendingSave) {
        if (!pendingSave || uiState.isSavingContext) return@LaunchedEffect
        if (!uiState.message.isNullOrBlank() || !uiState.error.isNullOrBlank()) {
            isEditing = false
            pendingSave = false
        }
    }

    SideDetailDrawer(title = "Workspace", onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

            // ── Current Workspace ────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("現在のワークスペース", modifier = Modifier.weight(1f))
                if (!isEditing && currentWorkspace != null) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = ZtSurfaceVariant,
                        border = BorderStroke(0.5.dp, ZtOutline),
                        onClick = { isEditing = true }
                    ) {
                        Text("編集", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (currentWorkspace == null) {
                EmptyHint("ワークスペースが選択されていません")
            } else if (isEditing) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditFieldCard {
                        SectionHeader("ワークスペース情報")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = workspaceName, onValueChange = { workspaceName = it }, enabled = !uiState.isSavingContext, label = { Text("ワークスペース名") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = workspaceDescription, onValueChange = { workspaceDescription = it }, enabled = !uiState.isSavingContext, label = { Text("説明") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(), minLines = 2)
                        if (physicalDevice != null) {
                            OutlinedTextField(value = deviceName, onValueChange = { deviceName = it }, enabled = !uiState.isSavingContext, label = { Text("デバイスのニックネーム") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    EditFieldCard {
                        SectionHeader("ワークスペースコンテクスト")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = draft.workspaceSummary, onValueChange = { draft = draft.copy(workspaceSummary = it) }, enabled = !uiState.isSavingContext, label = { Text("ワークスペースの前提") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(), minLines = 3)
                    }
                    EditFieldCard {
                        SectionHeader("デバイス・分析コンテクスト")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = draft.deviceSummary, onValueChange = { draft = draft.copy(deviceSummary = it) }, enabled = !uiState.isSavingContext, label = { Text("デバイスの設置場所と用途") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(value = draft.analysisGoal, onValueChange = { draft = draft.copy(analysisGoal = it) }, enabled = !uiState.isSavingContext, label = { Text("このアプリで把握したいこと") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(), minLines = 2)
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            pendingSave = true
                            onSaveProfile(workspaceName.trim(), workspaceDescription.trim(), deviceName.trim(), draft.copy(profileName = workspaceName.trim().ifBlank { draft.profileName }))
                        },
                        enabled = !uiState.isSavingContext && workspaceName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZtBlack, contentColor = Color.White)
                    ) { Text(if (uiState.isSavingContext) "保存中..." else "保存", style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(
                        onClick = { isEditing = false; pendingSave = false },
                        enabled = !uiState.isSavingContext,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ZtOutline)
                    ) { Text("キャンセル", style = MaterialTheme.typography.titleMedium, color = ZtOnSurface) }
                }
            } else {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(currentWorkspace.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurface)
                        if (!currentWorkspace.description.isNullOrBlank()) {
                            Text(currentWorkspace.description, style = MaterialTheme.typography.bodySmall, color = ZtCaption)
                        }
                    }
                }
            }

            // ── Workspace switcher ────────────────────────────────
            if (!isEditing && uiState.workspaces.size > 1) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("ワークスペース切り替え")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.workspaces.forEach { ws ->
                        SelectionRow(title = ws.name, subtitle = ws.description, selected = ws.id == selectedWorkspaceId, onClick = { onSelectWorkspace(ws.id) })
                    }
                }
            }

            if (!isEditing) {
                // ── Accounts ─────────────────────────────────────────
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = ZtOutline)
                Spacer(Modifier.height(16.dp))
                SectionHeader("Accounts")
                Spacer(Modifier.height(4.dp))
                Text(
                    "このワークスペースを閲覧可能なアカウント",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Spacer(Modifier.height(8.dp))
                val memberAccounts = if (uiState.workspaceMembers.isNotEmpty()) {
                    uiState.workspaceMembers.mapNotNull { it.account }
                } else {
                    listOfNotNull(ownerAccount ?: uiState.accounts.firstOrNull())
                }
                if (memberAccounts.isEmpty()) {
                    EmptyHint("アカウント情報がありません")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        memberAccounts.forEach { account ->
                            val isCurrentAccount = account.id == uiState.selectedAccountId
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrentAccount) ZtBlack else ZtSurfaceVariant,
                                border = if (!isCurrentAccount) BorderStroke(0.5.dp, ZtOutlineVariant) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(CircleShape)
                                            .background(if (isCurrentAccount) Color(0xFF2A2A2A) else ZtAvatarBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            account.display_name.trim().firstOrNull()?.uppercase() ?: "A",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCurrentAccount) Color.White else ZtAvatarText
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            account.display_name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isCurrentAccount) Color.White else ZtOnSurface
                                        )
                                        if (!account.email.isNullOrBlank()) {
                                            Text(
                                                account.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isCurrentAccount) Color(0xFF888888) else ZtCaption
                                            )
                                        }
                                    }
                                    if (isCurrentAccount) {
                                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF333333)) {
                                            Text(
                                                "ログイン中",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFAAAAAA),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else {
                                        // Show member role if available
                                        val memberRole = uiState.workspaceMembers
                                            .firstOrNull { it.account_id == account.id }?.role
                                        if (!memberRole.isNullOrBlank()) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = ZtSurfaceElevated, border = BorderStroke(0.5.dp, ZtOutline)) {
                                                Text(
                                                    memberRole,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ZtOnSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Organization ──────────────────────────────────────
                Spacer(Modifier.height(16.dp))
                SectionHeader("Organization")
                Spacer(Modifier.height(4.dp))
                Text(
                    "このワークスペースが所属する組織",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Spacer(Modifier.height(8.dp))
                if (uiState.organizations.isEmpty()) {
                    EmptyHint("所属オーガニゼーションがありません")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.organizations.forEach { org ->
                            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant), onClick = { onOpenOrgDrawer(org) }) {
                                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(ZtAvatarBg), contentAlignment = Alignment.Center) {
                                        Text(org.name.trim().firstOrNull()?.uppercase() ?: "O", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ZtAvatarText)
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(org.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurface)
                                        if (!org.slug.isNullOrBlank()) { Text(org.slug, style = MaterialTheme.typography.bodySmall, color = ZtCaption) }
                                    }
                                    if (!org.role.isNullOrBlank()) {
                                        Surface(shape = RoundedCornerShape(999.dp), color = ZtBlack) {
                                            Text(org.role, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                        }
                                    }
                                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = ZtCaption, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // ── Devices ──────────────────────────────────────────
                Spacer(Modifier.height(16.dp))
                SectionHeader("Devices")
                Spacer(Modifier.height(4.dp))
                Text(
                    "このワークスペースに配置されたデバイス一覧",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Spacer(Modifier.height(8.dp))
                val devicesToShow = if (workspaceDevices.isNotEmpty()) workspaceDevices else uiState.devices
                if (devicesToShow.isEmpty()) {
                    EmptyHint("デバイスがありません")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        devicesToShow.forEach { device ->
                            val isThisDevice = device.device_id == physicalDeviceId
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isThisDevice) ZtBlack else ZtSurfaceVariant,
                                border = if (!isThisDevice) BorderStroke(0.5.dp, ZtOutlineVariant) else null,
                                onClick = { onOpenDeviceDrawer(device) }
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(if (isThisDevice) Color(0xFF3A3A3A) else ZtSurfaceElevated), contentAlignment = Alignment.Center) {
                                        Text(device.display_name.trim().firstOrNull()?.uppercase() ?: "D", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (isThisDevice) Color.White else ZtOnSurface)
                                    }
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(device.display_name.ifBlank { device.device_id }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (isThisDevice) Color.White else ZtOnSurface)
                                        Text(device.device_id.take(12) + "…", style = MaterialTheme.typography.labelSmall, color = if (isThisDevice) Color(0xFF999999) else ZtCaption, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    }
                                    if (isThisDevice) {
                                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF333333)) {
                                            Text("この端末", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = if (isThisDevice) Color(0xFF666666) else ZtCaption, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── AccountSheet（マイページ）────────────────────────────────────────────────
@Composable
private fun AccountSheet(
    uiState: ZeroTouchUiState,
    initialDraft: ContextProfileDraft,
    isSaving: Boolean,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
    onSaveProfile: (String, ContextProfileDraft) -> Unit
) {
    val currentAccount = uiState.accounts.firstOrNull { it.id == uiState.selectedAccountId }
    var isEditing by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf(currentAccount?.display_name.orEmpty()) }
    var draft by remember { mutableStateOf(initialDraft) }
    var pendingSave by remember { mutableStateOf(false) }

    LaunchedEffect(currentAccount?.id) { if (!isEditing) accountName = currentAccount?.display_name.orEmpty() }
    LaunchedEffect(initialDraft) { if (!isEditing) draft = initialDraft }
    LaunchedEffect(isSaving, uiState.message, uiState.error, pendingSave) {
        if (!pendingSave || isSaving) return@LaunchedEffect
        if (!uiState.message.isNullOrBlank() || !uiState.error.isNullOrBlank()) { isEditing = false; pendingSave = false }
    }

    SideDetailDrawer(title = "マイページ", onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

            // ── Account card ──────────────────────────────────────
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ZtBlack) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val avatarUrl = uiState.authSession?.avatarUrl
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
                        if (!avatarUrl.isNullOrBlank()) {
                            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(avatarUrl).crossfade(true).build(), contentDescription = "Account avatar", modifier = Modifier.fillMaxSize().clip(CircleShape))
                        } else {
                            val initial = (currentAccount?.display_name ?: uiState.authSession?.displayName ?: uiState.authSession?.email ?: "A").trim().firstOrNull()?.toString() ?: "A"
                            Text(initial.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(currentAccount?.display_name ?: uiState.authSession?.displayName ?: "未設定", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        if (!uiState.authSession?.email.isNullOrBlank()) {
                            Text(uiState.authSession?.email ?: "", style = MaterialTheme.typography.bodySmall, color = Color(0xFF888888))
                        }
                        val orgRole = uiState.organizations.firstOrNull()?.role
                        if (!orgRole.isNullOrBlank()) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF333333)) {
                                Text(orgRole, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, ZtOutline), colors = ButtonDefaults.outlinedButtonColors(contentColor = ZtError)) {
                Text("ログアウト", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = ZtOutline)
            Spacer(Modifier.height(16.dp))

            // ── Context ───────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Context", modifier = Modifier.weight(1f))
                if (!isEditing) {
                    Surface(shape = RoundedCornerShape(999.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutline), onClick = { isEditing = true }) {
                        Text("編集", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (!isEditing) {
                val accountCtx = uiState.contextProfile?.account_context
                ContextCard {
                    ProfileValueLine("あなたについて", accountCtx?.identity_summary ?: "未設定")
                    HorizontalDivider(color = ZtOutlineVariant, thickness = 0.5.dp)
                    ProfileValueLine("役割", accountCtx?.primary_roles?.joinToString(" / ") ?: "未設定")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditFieldCard {
                        SectionHeader("アカウント情報")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = accountName, onValueChange = { accountName = it }, enabled = !isSaving, label = { Text("アカウント名") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                    }
                    EditFieldCard {
                        SectionHeader("アカウントコンテクスト")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = draft.roleTitle, onValueChange = { draft = draft.copy(roleTitle = it) }, enabled = !isSaving, label = { Text("役割 / 肩書き") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = draft.identitySummary, onValueChange = { draft = draft.copy(identitySummary = it) }, enabled = !isSaving, label = { Text("あなたについて") }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(), minLines = 3)
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { pendingSave = true; onSaveProfile(accountName.trim(), draft.copy(ownerName = accountName.trim().ifBlank { draft.ownerName })) },
                        enabled = !isSaving && accountName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZtBlack, contentColor = Color.White)
                    ) { Text(if (isSaving) "保存中..." else "保存", style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(
                        onClick = { isEditing = false; pendingSave = false; draft = initialDraft },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ZtOutline)
                    ) { Text("キャンセル", style = MaterialTheme.typography.titleMedium, color = ZtOnSurface) }
                }
            }
        }
    }
}

// ── OrgDrawer ─────────────────────────────────────────────────────────────────
@Composable
private fun OrgDrawer(org: OrganizationSummary, uiState: ZeroTouchUiState, onClose: () -> Unit) {
    SideDetailDrawer(title = org.name, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant)) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(ZtAvatarBg), contentAlignment = Alignment.Center) {
                        Text(org.name.trim().firstOrNull()?.uppercase() ?: "O", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ZtAvatarText)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(org.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = ZtOnSurface)
                        if (!org.slug.isNullOrBlank()) { Text(org.slug, style = MaterialTheme.typography.bodySmall, color = ZtCaption) }
                        if (!org.role.isNullOrBlank()) {
                            Surface(shape = RoundedCornerShape(999.dp), color = ZtBlack) {
                                Text(org.role, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Workspaces")
            Spacer(Modifier.height(8.dp))
            if (uiState.workspaces.isEmpty()) {
                EmptyHint("ワークスペースがありません")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.workspaces.forEach { ws ->
                        val isSelected = ws.id == uiState.selectedWorkspaceId
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = if (isSelected) ZtBlack else ZtSurfaceVariant, border = if (!isSelected) BorderStroke(0.5.dp, ZtOutlineVariant) else null) {
                            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(ws.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (isSelected) Color.White else ZtOnSurface)
                                    if (!ws.description.isNullOrBlank()) { Text(ws.description, style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color(0xFFCCCCCC) else ZtCaption) }
                                }
                                if (isSelected) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Devices")
            Spacer(Modifier.height(8.dp))
            if (uiState.devices.isEmpty()) {
                EmptyHint("デバイスがありません")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.devices.forEach { device ->
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant)) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(device.display_name.ifBlank { "デバイス" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurface)
                                val ws = uiState.workspaces.firstOrNull { it.id == device.workspace_id }
                                if (ws != null) { Text(ws.name, style = MaterialTheme.typography.labelSmall, color = ZtCaption) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Accounts")
            Spacer(Modifier.height(8.dp))
            if (uiState.accounts.isEmpty()) {
                EmptyHint("アカウントがありません")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.accounts.forEach { account ->
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant)) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(ZtAvatarBg), contentAlignment = Alignment.Center) {
                                    Text(account.display_name.trim().firstOrNull()?.uppercase() ?: "A", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ZtAvatarText)
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(account.display_name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurface)
                                    if (!account.email.isNullOrBlank()) { Text(account.email, style = MaterialTheme.typography.labelSmall, color = ZtCaption) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── DeviceDrawer ──────────────────────────────────────────────────────────────
@Composable
private fun DeviceDrawer(device: DeviceSummary, uiState: ZeroTouchUiState, onClose: () -> Unit) {
    val linkedWorkspace = uiState.workspaces.firstOrNull { it.id == device.workspace_id }

    SideDetailDrawer(title = device.display_name.ifBlank { "Device" }, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ZtBlack) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (device.display_name.isNotBlank()) {
                        Text(device.display_name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF2A2A2A)) {
                        Text(device.device_id, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!device.device_kind.isNullOrBlank()) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF333333)) { Text(device.device_kind, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
                        }
                        if (!device.platform.isNullOrBlank()) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF333333)) { Text(device.platform, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
                        }
                        Surface(shape = RoundedCornerShape(4.dp), color = if (device.is_active) Color(0xFF2D6A4F) else Color(0xFF555555)) {
                            Text(if (device.is_active) "Active" else "Inactive", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("Workspace")
            Spacer(Modifier.height(8.dp))
            if (linkedWorkspace == null) {
                EmptyHint("ワークスペースが見つかりません")
            } else {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(linkedWorkspace.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ZtOnSurface)
                        if (!linkedWorkspace.description.isNullOrBlank()) { Text(linkedWorkspace.description, style = MaterialTheme.typography.bodySmall, color = ZtCaption) }
                    }
                }
            }

            if (!device.source_type.isNullOrBlank() || device.is_virtual) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("詳細")
                Spacer(Modifier.height(8.dp))
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = ZtSurfaceVariant, border = BorderStroke(0.5.dp, ZtOutlineVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!device.source_type.isNullOrBlank()) { ProfileValueLine("ソースタイプ", device.source_type) }
                        if (device.is_virtual) { ProfileValueLine("仮想デバイス", "はい") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = ZtOnSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ZtCaption,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ContextCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ZtSurfaceVariant,
        border = BorderStroke(0.5.dp, ZtOutlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun EditFieldCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ZtSurfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(0.5.dp, ZtOutline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun ProfileValueLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = ZtCaption,
            letterSpacing = 0.4.sp
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = ZtOnSurface
        )
    }
}

@Composable
private fun SelectionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) ZtBlack else ZtSurface,
        border = if (!selected) BorderStroke(1.dp, ZtOutline) else null,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) Color.White else ZtOnSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Color(0xFFCCCCCC) else ZtCaption
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
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
        Surface(color = ZtSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ZtOnBackground
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtCaption
                        )
                    }
                }
                // Ambient status pill — black when live, soft grey when off
                val pillColor = if (isAmbientLive) ZtBlack else Color(0xFFF0EFEB)
                val pillTextColor = if (isAmbientLive) Color.White else ZtOnSurfaceVariant
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = pillColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
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
                            color = pillTextColor
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                AmbientToggleSwitch(enabled = ambientEnabled, onToggle = onToggleAmbient)
            }
        }
        HorizontalDivider(color = ZtOutline, thickness = 1.dp)
    }
}

@Composable
private fun AmbientToggleSwitch(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val trackColor = if (enabled) ZtBlack else Color(0xFFD8D7D3)
    val alignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = trackColor,
        onClick = { onToggle(!enabled) }
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp, vertical = 3.dp)
                .size(width = 44.dp, height = 24.dp),
            contentAlignment = alignment
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 1.dp,
                modifier = Modifier.size(18.dp)
            ) {}
        }
    }
}

private fun startAmbientService(context: android.content.Context) {
    val intent = Intent(context, com.subbrain.zerotouch.audio.ambient.AmbientRecordingService::class.java).apply {
        action = com.subbrain.zerotouch.audio.ambient.AmbientRecordingService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopAmbientService(context: android.content.Context) {
    val intent = Intent(context, com.subbrain.zerotouch.audio.ambient.AmbientRecordingService::class.java).apply {
        action = com.subbrain.zerotouch.audio.ambient.AmbientRecordingService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
}

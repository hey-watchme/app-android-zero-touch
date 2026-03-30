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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import com.subbrain.zerotouch.ui.AnalysisScreen
import com.subbrain.zerotouch.ui.SettingsSheet
import com.subbrain.zerotouch.ui.TimelineScreen
import com.subbrain.zerotouch.ui.VoiceMemoScreen
import com.subbrain.zerotouch.ui.ZeroTouchUiState
import com.subbrain.zerotouch.ui.ZeroTouchViewModel
import com.subbrain.zerotouch.ui.components.AmbientDot
import com.subbrain.zerotouch.ui.components.SideDetailDrawer
import com.subbrain.zerotouch.ui.theme.ZerotouchTheme
import com.subbrain.zerotouch.ui.theme.ZtAvatarBg
import com.subbrain.zerotouch.ui.theme.ZtAvatarText
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtOnBackground
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutline
import com.subbrain.zerotouch.ui.theme.ZtOutlineVariant
import com.subbrain.zerotouch.ui.theme.ZtPrimary
import com.subbrain.zerotouch.ui.theme.ZtPrimaryContainer
import com.subbrain.zerotouch.ui.theme.ZtSidebarSelected
import com.subbrain.zerotouch.ui.theme.ZtSidebarSurface
import com.subbrain.zerotouch.ui.theme.ZtSurface
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AMICAL_TEST_EMAIL = "amical-test@zerotouch.local"
private const val AMICAL_TEST_PASSWORD = "AmicalTest123!"

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
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showWorkspaceSelector by remember { mutableStateOf(false) }
    var isSidebarCollapsed by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(Unit) {
        viewModel.loadAuthSession(context)
    }

    LaunchedEffect(showWorkspaceSelector) {
        if (showWorkspaceSelector && uiState.authSession != null) {
            viewModel.loadSelection(context, force = true)
        }
    }

    LaunchedEffect(uiState.authSession?.userId) {
        if (uiState.authSession == null) return@LaunchedEffect
        viewModel.loadSelection(context, force = true, loadDataAfter = true)
        while (true) {
            delay(5000)
            if (!uiState.isLoadingSelection) {
                viewModel.refreshSessions(context, showIndicator = false)
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
        if (selectedTab == 3 && uiState.authSession != null) {
            viewModel.loadFacts(context)
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

    if (showWorkspaceSelector) {
        WorkspaceSelectorSheet(
            uiState = uiState,
            onClose = { showWorkspaceSelector = false },
            onSignOut = {
                googleSignInClient.signOut()
                viewModel.signOut(context)
                showWorkspaceSelector = false
            },
            onSelectWorkspace = { workspaceId ->
                viewModel.selectWorkspace(context, workspaceId)
                viewModel.loadSessions(context)
                viewModel.loadFacts(context, force = true)
            },
            onSelectDevice = { deviceId ->
                viewModel.selectDevice(context, deviceId)
                viewModel.loadSessions(context)
                viewModel.loadFacts(context, force = true)
                showWorkspaceSelector = false
            }
        )
    }

    val activeTopicCount = uiState.topicCards.count { it.status == "active" }
    val savedCount = uiState.favoriteIds.size
    val analysisCount = uiState.topicCards.count { (it.importanceLevel ?: -1) >= 3 }
    val allFacts = uiState.factsByTopic.values.flatten()
    val totalFactCount = allFacts.size
    val categoryCounts = allFacts
        .flatMap { fact ->
            val categories = fact.categories.map { it.trim() }.filter { it.isNotEmpty() }
            if (categories.isEmpty()) listOf("未分類") else categories
        }
        .groupingBy { it }
        .eachCount()
    val categoryEntries = categoryCounts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { CategoryEntry(name = it.key, count = it.value) }
    val selectedAccount = uiState.accounts.firstOrNull { it.id == uiState.selectedAccountId }
    val selectedWorkspace = uiState.workspaces.firstOrNull { it.id == uiState.selectedWorkspaceId }
    val selectedDevice = uiState.devices.firstOrNull { it.device_id == uiState.selectedDeviceId }
    val accountLabel = selectedAccount?.display_name
        ?: uiState.authSession?.displayName
        ?: uiState.authSession?.email
        ?: "未選択"
    val workspaceLabel = selectedWorkspace?.name ?: "未選択"
    val deviceLabel = selectedDevice?.display_name ?: "未選択"
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

    LaunchedEffect(categoryEntries) {
        if (selectedCategory != null && categoryEntries.none { it.name == selectedCategory }) {
            selectedCategory = null
        }
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
                categoryEntries = categoryEntries,
                totalFactCount = totalFactCount,
                selectedCategory = selectedCategory,
                accountLabel = accountLabel,
                accountAvatarUrl = uiState.authSession?.avatarUrl,
                workspaceLabel = workspaceLabel,
                deviceLabel = deviceLabel,
                ambientEnabled = ambientEnabled,
                isAmbientLive = ambientState.isRecording || ambientState.speech,
                onToggleSidebar = { isSidebarCollapsed = !isSidebarCollapsed },
                onSelectTab = { tab ->
                    selectedTab = tab
                    showSettings = false
                },
                onSelectCategory = { category -> selectedCategory = category },
                onOpenWorkspaceSelector = { showWorkspaceSelector = true },
                onSignOut = {
                    googleSignInClient.signOut()
                    viewModel.signOut(context)
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
                                selectedCategory = selectedCategory,
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
private fun LoginScreen(
    isAuthenticating: Boolean,
    onGoogleSignIn: () -> Unit,
    onEmailSignIn: (String, String) -> Unit
) {
    var email by remember { mutableStateOf(AMICAL_TEST_EMAIL) }
    var password by remember { mutableStateOf(AMICAL_TEST_PASSWORD) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF4F7FB),
                        Color(0xFFE8ECE6),
                        ZtSurface
                    )
                )
            )
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .padding(start = 28.dp, top = 36.dp)
                .size(160.dp)
                .clip(CircleShape)
                .background(Color(0x1A2563EB))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 54.dp)
                .size(220.dp)
                .clip(CircleShape)
                .background(Color(0x1444403C))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xF2FFFFFF)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = ZtPrimaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = ZtPrimary
                            )
                        }
                    }

                    Text(
                        text = "ZeroTouch",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ZtOnBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = onGoogleSignIn,
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = if (isAuthenticating) "Google ログイン中..." else "Google でログイン",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    HorizontalDivider(color = ZtOutlineVariant)

                    Text(
                        text = "メールアドレスでログイン",
                        style = MaterialTheme.typography.titleSmall,
                        color = ZtOnBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextField(
                        value = email,
                        onValueChange = { email = it },
                        singleLine = true,
                        enabled = !isAuthenticating,
                        label = { Text("メールアドレス") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = TextFieldDefaults.colors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        enabled = !isAuthenticating,
                        label = { Text("パスワード") },
                        visualTransformation = if (password.isEmpty()) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = TextFieldDefaults.colors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onEmailSignIn(email, password) },
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = if (isAuthenticating) "認証中..." else "メールアドレスでログイン",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
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
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF4F7FB),
                        Color(0xFFE8ECE6),
                        ZtSurface
                    )
                )
            )
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.titleMedium,
            color = ZtOnSurfaceVariant
        )
    }
}

@Composable
private fun ZeroTouchSidebar(
    isCollapsed: Boolean,
    selectedTab: Int,
    activeTopicCount: Int,
    savedCount: Int,
    analysisCount: Int,
    categoryEntries: List<CategoryEntry>,
    totalFactCount: Int,
    selectedCategory: String?,
    accountLabel: String,
    accountAvatarUrl: String?,
    workspaceLabel: String,
    deviceLabel: String,
    ambientEnabled: Boolean,
    isAmbientLive: Boolean,
    onToggleSidebar: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onOpenWorkspaceSelector: () -> Unit,
    onSignOut: () -> Unit,
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
            var showAccountMenu by remember { mutableStateOf(false) }
            Row(
                modifier = if (isCollapsed) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Surface(
                        modifier = Modifier.size(if (isCollapsed) 40.dp else 40.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = ZtAvatarBg,
                        onClick = { showAccountMenu = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (!accountAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(accountAvatarUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Account avatar",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            } else {
                                val initial = accountLabel.trim().firstOrNull()?.toString() ?: "A"
                                Text(
                                    text = initial.uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = ZtAvatarText
                                )
                            }
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
                    DropdownMenu(
                        expanded = showAccountMenu,
                        onDismissRequest = { showAccountMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("ログアウト") },
                            onClick = {
                                showAccountMenu = false
                                onSignOut()
                            }
                        )
                    }
                }

                if (!isCollapsed) {
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenWorkspaceSelector() }
                    ) {
                        Text(
                            text = accountLabel.ifBlank { "未選択" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = workspaceLabel.ifBlank { "未選択" },
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtCaption
                        )
                        Text(
                            text = deviceLabel.ifBlank { "未選択" },
                            style = MaterialTheme.typography.bodySmall,
                            color = ZtOnSurfaceVariant
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

            if (!isCollapsed && selectedTab == 3) {
                SidebarCategorySection(
                    categories = categoryEntries,
                    totalFactCount = totalFactCount,
                    selectedCategory = selectedCategory,
                    onSelectCategory = onSelectCategory
                )
            }

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

private data class CategoryEntry(
    val name: String,
    val count: Int
)

@Composable
private fun SidebarCategorySection(
    categories: List<CategoryEntry>,
    totalFactCount: Int,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit
) {
    Spacer(Modifier.size(10.dp))
    Text(
        text = "カテゴリ",
        style = MaterialTheme.typography.labelMedium,
        color = ZtCaption,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
    Spacer(Modifier.size(6.dp))

    SidebarCategoryRow(
        label = "すべて",
        count = totalFactCount,
        selected = selectedCategory == null,
        onClick = { onSelectCategory(null) }
    )

    categories.forEach { category ->
        SidebarCategoryRow(
            label = category.name,
            count = category.count,
            selected = selectedCategory == category.name,
            onClick = { onSelectCategory(category.name) }
        )
    }
}

@Composable
private fun SidebarCategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ZtSidebarSelected else Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (count > 0) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = ZtSurfaceVariant
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
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

@Composable
private fun WorkspaceSelectorSheet(
    uiState: ZeroTouchUiState,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onSelectDevice: (String) -> Unit
) {
    val selectedWorkspaceId = uiState.selectedWorkspaceId
    val filteredDevices = if (!selectedWorkspaceId.isNullOrBlank()) {
        uiState.devices.filter { it.workspace_id == selectedWorkspaceId }
    } else {
        uiState.devices
    }

    SideDetailDrawer(
        title = "Account / Workspace / Device",
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (uiState.authSession != null) {
                Text(
                    text = "ログイン中",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtCaption
                )
                Text(
                    text = "ログイン中: ${uiState.authSession.displayName ?: uiState.authSession.email ?: "User"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
                Button(onClick = onSignOut) {
                    Text("ログアウト")
                }
                HorizontalDivider(color = ZtOutline)
            }

            val currentAccount = uiState.accounts.firstOrNull { it.id == uiState.selectedAccountId }
            if (currentAccount != null) {
                Text(
                    text = "アカウント",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZtCaption
                )
                SelectionRow(
                    title = currentAccount.display_name,
                    subtitle = currentAccount.email ?: "Google ログイン中のアカウント",
                    selected = true,
                    onClick = {}
                )
                HorizontalDivider(color = ZtOutline)
            }

            Text(
                text = "ワークスペース",
                style = MaterialTheme.typography.labelMedium,
                color = ZtCaption
            )
            if (uiState.workspaces.isEmpty()) {
                Text(
                    text = "ワークスペースがありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
            } else {
                uiState.workspaces.forEach { workspace ->
                    SelectionRow(
                        title = workspace.name,
                        subtitle = workspace.description,
                        selected = workspace.id == selectedWorkspaceId,
                        onClick = { onSelectWorkspace(workspace.id) }
                    )
                }
            }

            HorizontalDivider(color = ZtOutline)

            Text(
                text = "デバイス",
                style = MaterialTheme.typography.labelMedium,
                color = ZtCaption
            )
            if (filteredDevices.isEmpty()) {
                Text(
                    text = "対象デバイスがありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtOnSurfaceVariant
                )
            } else {
                filteredDevices.forEach { device ->
                    val subtitle = listOfNotNull(
                        device.source_type?.takeIf { it.isNotBlank() },
                        device.device_kind?.takeIf { it.isNotBlank() },
                        if (device.is_virtual) "virtual" else null
                    ).joinToString(" · ").ifBlank { null }
                    SelectionRow(
                        title = device.display_name,
                        subtitle = subtitle,
                        selected = device.device_id == uiState.selectedDeviceId,
                        onClick = { onSelectDevice(device.device_id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) ZtSidebarSelected else Color.Transparent
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(1.dp, ZtOutline),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtOnSurfaceVariant
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

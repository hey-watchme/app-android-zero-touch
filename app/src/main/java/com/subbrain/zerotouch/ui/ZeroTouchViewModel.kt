package com.subbrain.zerotouch.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subbrain.zerotouch.api.DeviceIdProvider
import com.subbrain.zerotouch.api.DeviceSummary
import com.subbrain.zerotouch.api.FactSummary
import com.subbrain.zerotouch.api.AccountSummary
import com.subbrain.zerotouch.api.OrganizationSummary
import com.subbrain.zerotouch.api.AuthPreferences
import com.subbrain.zerotouch.api.AuthSession
import com.subbrain.zerotouch.api.ContextProfile
import com.subbrain.zerotouch.api.ContextProfileRequest
import com.subbrain.zerotouch.api.SessionListResponse
import com.subbrain.zerotouch.api.SessionSummary
import com.subbrain.zerotouch.api.TopicSummary
import com.subbrain.zerotouch.api.TopicUtteranceSummary
import com.subbrain.zerotouch.api.WorkspaceSummary
import com.subbrain.zerotouch.api.ZeroTouchApi
import com.subbrain.zerotouch.api.SelectionPreferences
import com.subbrain.zerotouch.api.SupabaseAuthApi
import com.subbrain.zerotouch.audio.ambient.AmbientPreferences
import com.subbrain.zerotouch.audio.ambient.AmbientStatus
import kotlin.math.max
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

const val UNINTELLIGIBLE_CARD_TEXT = "音声は検出されましたが、内容を判別できませんでした"
const val UNINTELLIGIBLE_TOPIC_TITLE = "判別できない音声"
const val UNINTELLIGIBLE_TOPIC_SUMMARY = "会話音声は検出されましたが、文字起こしできませんでした"

data class TranscriptCard(
    val id: String,
    val createdAt: String,
    val createdAtEpochMs: Long,
    val status: String,
    val displayStatus: String,
    val isProcessing: Boolean,
    val text: String,
    val displayTitle: String,
    val durationSeconds: Int = 0,
    val displayDate: String = "",
    val asrProvider: String? = null,
    val asrModel: String? = null,
    val asrLanguage: String? = null,
    val speakerCount: Int = 0,
    val speakerLabels: List<String> = emptyList(),
    val speakerSegments: List<SpeakerSegment> = emptyList(),
    val isUnintelligible: Boolean = false
)

data class SpeakerSegment(
    val speakerLabel: String,
    val text: String
)

data class TopicFeedCard(
    val id: String,
    val status: String,
    val title: String,
    val summary: String,
    val utteranceCount: Int,
    val updatedAtEpochMs: Long,
    val displayDate: String,
    val utterances: List<TranscriptCard> = emptyList(),
    val llmProvider: String? = null,
    val llmModel: String? = null,
    val isUnintelligible: Boolean = false,
    val importanceLevel: Int? = null,
    val importanceReason: String? = null
)

private data class TopicPageResult(
    val cards: List<TopicFeedCard>,
    val rawCount: Int,
    val hasMore: Boolean
)

private data class ViewScope(
    val deviceId: String?,
    val workspaceId: String?,
    val allowEvaluate: Boolean
)

data class ZeroTouchUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val feedCards: List<TranscriptCard> = emptyList(),
    val topicCards: List<TopicFeedCard> = emptyList(),
    val factsByTopic: Map<String, List<FactSummary>> = emptyMap(),
    val accounts: List<AccountSummary> = emptyList(),
    val organizations: List<OrganizationSummary> = emptyList(),
    val currentOrgRole: String? = null,
    val workspaces: List<WorkspaceSummary> = emptyList(),
    val devices: List<DeviceSummary> = emptyList(),
    val selectedAccountId: String? = null,
    val selectedWorkspaceId: String? = null,
    val selectedDeviceId: String? = null,
    val isLoadingSelection: Boolean = false,
    val authSession: AuthSession? = null,
    val isAuthReady: Boolean = false,
    val isAuthenticating: Boolean = false,
    val isUploading: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val dismissedIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet(),
    val selectedCardId: String? = null,
    val contextProfile: ContextProfile? = null,
    val isLoadingContext: Boolean = false,
    val isSavingContext: Boolean = false
)

class ZeroTouchViewModel : ViewModel() {
    companion object {
        private const val TAG = "ZeroTouchVM"
        private const val PAGE_SIZE = 10
        private const val SESSION_DETAIL_PARALLELISM = 4
        private const val OPTIMISTIC_TIMEOUT_MS = 60_000L
        private const val PROCESSING_TIMEOUT_MS = 20 * 60_000L
        private const val TOPIC_IDLE_SECONDS = 30
        private const val TOPIC_EVALUATE_COOLDOWN_MS = 60_000L
    }

    private val api = ZeroTouchApi()
    private val authApi = SupabaseAuthApi()

    private val _uiState = MutableStateFlow(ZeroTouchUiState())
    val uiState: StateFlow<ZeroTouchUiState> = _uiState

    private var isRefreshing = false
    private val dismissedIds = mutableSetOf<String>()
    private val favoriteIds = mutableSetOf<String>()
    private var currentOffset = 0
    private var hasMoreSessions = true
    private var currentTopicOffset = 0
    private var hasMoreTopics = true
    private var topicEvaluateInFlight = false
    private var lastTopicEvaluateAtMs = 0L
    private val optimisticTranscribing = mutableMapOf<String, Long>()
    private var isLoadingFacts = false
    private var isLoadingSelection = false

    private fun resetForAuthSession(session: AuthSession?) {
        dismissedIds.clear()
        favoriteIds.clear()
        currentOffset = 0
        hasMoreSessions = true
        currentTopicOffset = 0
        hasMoreTopics = true
        isRefreshing = false
        isLoadingFacts = false
        isLoadingSelection = false
        _uiState.value = ZeroTouchUiState(
            authSession = session,
            isAuthReady = true,
            isAuthenticating = false,
            isLoadingSelection = session != null,
            isLoading = session != null
        )
    }

    private fun resolveViewScope(context: Context): ViewScope {
        val selectedDeviceId = _uiState.value.selectedDeviceId?.trim().orEmpty()
        val selectedWorkspaceId = _uiState.value.selectedWorkspaceId?.trim().orEmpty()
        val physicalDeviceId = DeviceIdProvider.getDeviceId(context)

        return when {
            selectedDeviceId.isNotEmpty() -> ViewScope(
                deviceId = selectedDeviceId,
                workspaceId = null,
                allowEvaluate = selectedDeviceId == physicalDeviceId
            )
            selectedWorkspaceId.isNotEmpty() -> ViewScope(
                deviceId = null,
                workspaceId = selectedWorkspaceId,
                allowEvaluate = false
            )
            else -> ViewScope(
                deviceId = physicalDeviceId,
                workspaceId = null,
                allowEvaluate = true
            )
        }
    }

    fun loadAuthSession(context: Context) {
        val session = AuthPreferences.getSession(context)
        resetForAuthSession(session)
    }

    fun signInWithGoogle(
        context: Context,
        idToken: String,
        accessToken: String? = null
    ) {
        if (_uiState.value.isAuthenticating) return
        _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
        viewModelScope.launch {
            try {
                val response = authApi.signInWithGoogleIdToken(idToken, accessToken)
                val user = response.user ?: throw IllegalStateException("Supabase user not found")
                val metadata = user.user_metadata ?: emptyMap()
                val displayName = (metadata["full_name"] as? String)
                    ?: (metadata["name"] as? String)
                    ?: user.email
                val avatarUrl = metadata["avatar_url"] as? String
                val session = AuthSession(
                    userId = user.id,
                    email = user.email,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    accessToken = response.access_token,
                    refreshToken = response.refresh_token
                )
                AuthPreferences.setSession(context, session)
                resetForAuthSession(session)
                val account = ensureAccountForUser(session)
                if (account != null) {
                    SelectionPreferences.setSelectedAccountId(context, account.id)
                    SelectionPreferences.setSelectedWorkspaceId(context, null)
                    SelectionPreferences.setSelectedDeviceId(context, null)
                    loadSelection(context, force = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google sign-in failed", e)
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "Googleログインに失敗しました: ${e.message}"
                )
            }
        }
    }

    fun signInWithEmailPassword(
        context: Context,
        email: String,
        password: String
    ) {
        if (_uiState.value.isAuthenticating) return
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "メールアドレスとパスワードを入力してください")
            return
        }

        _uiState.value = _uiState.value.copy(isAuthenticating = true, error = null)
        viewModelScope.launch {
            try {
                val response = authApi.signInWithEmailPassword(
                    email = normalizedEmail,
                    password = password
                )
                val user = response.user ?: throw IllegalStateException("Supabase user not found")
                val metadata = user.user_metadata ?: emptyMap()
                val displayName = (metadata["full_name"] as? String)
                    ?: (metadata["name"] as? String)
                    ?: user.email
                val avatarUrl = metadata["avatar_url"] as? String
                val session = AuthSession(
                    userId = user.id,
                    email = user.email,
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    accessToken = response.access_token,
                    refreshToken = response.refresh_token
                )
                AuthPreferences.setSession(context, session)
                resetForAuthSession(session)
                val account = ensureAccountForUser(session)
                if (account != null) {
                    SelectionPreferences.setSelectedAccountId(context, account.id)
                    SelectionPreferences.setSelectedWorkspaceId(context, null)
                    SelectionPreferences.setSelectedDeviceId(context, null)
                    loadSelection(context, force = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Email sign-in failed", e)
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    error = "メールログインに失敗しました: ${e.message}"
                )
            }
        }
    }

    fun signOut(context: Context) {
        AuthPreferences.setSession(context, null)
        SelectionPreferences.setSelectedAccountId(context, null)
        SelectionPreferences.setSelectedWorkspaceId(context, null)
        SelectionPreferences.setSelectedDeviceId(context, null)
        resetForAuthSession(null)
    }

    private fun findAccountForSession(
        session: AuthSession,
        accounts: List<AccountSummary>
    ): AccountSummary? {
        val userId = session.userId.trim()
        if (userId.isNotEmpty()) {
            accounts.firstOrNull { account ->
                account.external_auth_provider == "supabase" &&
                    account.external_auth_subject == userId
            }?.let { return it }
        }

        val normalizedEmail = session.email?.trim()?.lowercase(Locale.ROOT)
        if (!normalizedEmail.isNullOrBlank()) {
            accounts.firstOrNull { account ->
                account.email?.trim()?.lowercase(Locale.ROOT) == normalizedEmail
            }?.let { return it }
        }

        return null
    }

    private suspend fun ensureAccountForUser(session: AuthSession): AccountSummary? {
        val accounts = api.listAccounts().accounts
        val existing = findAccountForSession(session, accounts)
        if (existing != null) return existing

        val displayName = session.displayName ?: session.email ?: "ZeroTouch User"
        return api.createAccount(
            displayName = displayName,
            email = session.email,
            externalAuthProvider = "supabase",
            externalAuthSubject = session.userId,
            avatarUrl = session.avatarUrl
        )
    }

    fun loadSelection(context: Context, force: Boolean = false, loadDataAfter: Boolean = false) {
        if (isLoadingSelection && !force) return
        isLoadingSelection = true
        _uiState.value = _uiState.value.copy(isLoadingSelection = true)
        viewModelScope.launch {
            try {
                val authSession = _uiState.value.authSession ?: AuthPreferences.getSession(context)
                val currentAccount = authSession?.let { ensureAccountForUser(it) }
                val accounts = listOfNotNull(currentAccount)
                val resolvedAccountId = currentAccount?.id

                val organizations = if (resolvedAccountId != null) {
                    try {
                        api.listOrganizations(accountId = resolvedAccountId).organizations
                    } catch (e: Exception) {
                        Log.w(TAG, "listOrganizations failed", e)
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val workspaces = if (resolvedAccountId != null) {
                    api.listWorkspaces(accountId = resolvedAccountId).workspaces
                } else {
                    emptyList()
                }
                val savedWorkspaceId = SelectionPreferences.getSelectedWorkspaceId(context)
                val physicalDeviceId = DeviceIdProvider.getDeviceId(context)

                val devices = if (resolvedAccountId != null) {
                    api.listDevices(accountId = resolvedAccountId).devices
                } else {
                    emptyList()
                }
                val physicalDeviceRow = devices.firstOrNull { it.device_id == physicalDeviceId }
                var resolvedWorkspaceId = when {
                    !savedWorkspaceId.isNullOrBlank() && workspaces.any { it.id == savedWorkspaceId } -> savedWorkspaceId
                    !physicalDeviceRow?.workspace_id.isNullOrBlank() &&
                        workspaces.any { it.id == physicalDeviceRow?.workspace_id } -> physicalDeviceRow?.workspace_id
                    else -> null
                }

                val savedDeviceId = SelectionPreferences.getSelectedDeviceId(context)
                var resolvedDeviceId = when {
                    !savedDeviceId.isNullOrBlank() && devices.any { it.device_id == savedDeviceId } -> savedDeviceId
                    physicalDeviceRow != null -> physicalDeviceRow.device_id
                    else -> null
                }

                if (resolvedDeviceId != null) {
                    val deviceRow = devices.firstOrNull { it.device_id == resolvedDeviceId }
                    resolvedWorkspaceId = deviceRow?.workspace_id ?: resolvedWorkspaceId
                }

                SelectionPreferences.setSelectedAccountId(context, resolvedAccountId)
                SelectionPreferences.setSelectedWorkspaceId(context, resolvedWorkspaceId)
                SelectionPreferences.setSelectedDeviceId(context, resolvedDeviceId)

                _uiState.value = _uiState.value.copy(
                    accounts = accounts,
                    organizations = organizations,
                    workspaces = workspaces,
                    devices = devices,
                    selectedAccountId = resolvedAccountId,
                    selectedWorkspaceId = resolvedWorkspaceId,
                    selectedDeviceId = resolvedDeviceId,
                    isLoadingSelection = false
                )
                if (loadDataAfter && resolvedAccountId != null) {
                    loadSessions(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadSelection failed", e)
                _uiState.value = _uiState.value.copy(isLoadingSelection = false)
            } finally {
                isLoadingSelection = false
            }
        }
    }

    fun selectWorkspace(context: Context, workspaceId: String) {
        val devices = _uiState.value.devices
        val workspaceDevices = devices.filter { it.workspace_id == workspaceId }
        val currentDeviceId = _uiState.value.selectedDeviceId
        val resolvedDeviceId = when {
            !currentDeviceId.isNullOrBlank() && workspaceDevices.any { it.device_id == currentDeviceId } -> currentDeviceId
            workspaceDevices.isNotEmpty() -> workspaceDevices.first().device_id
            else -> null
        }
        SelectionPreferences.setSelectedWorkspaceId(context, workspaceId)
        SelectionPreferences.setSelectedDeviceId(context, resolvedDeviceId)
        _uiState.value = _uiState.value.copy(
            selectedWorkspaceId = workspaceId,
            selectedDeviceId = resolvedDeviceId
        )
    }

    fun selectDevice(context: Context, deviceId: String) {
        val deviceRow = _uiState.value.devices.firstOrNull { it.device_id == deviceId }
        val workspaceId = deviceRow?.workspace_id
        SelectionPreferences.setSelectedDeviceId(context, deviceId)
        if (workspaceId != null) {
            SelectionPreferences.setSelectedWorkspaceId(context, workspaceId)
        }
        _uiState.value = _uiState.value.copy(
            selectedWorkspaceId = workspaceId ?: _uiState.value.selectedWorkspaceId,
            selectedDeviceId = deviceId
        )
    }

    fun loadContextProfile(workspaceId: String) {
        if (workspaceId.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingContext = true)
        viewModelScope.launch {
            try {
                val envelope = api.getContextProfile(workspaceId)
                _uiState.value = _uiState.value.copy(
                    contextProfile = envelope.profile,
                    isLoadingContext = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadContextProfile failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingContext = false,
                    error = "コンテクストの取得に失敗しました: ${e.message}"
                )
            }
        }
    }

    fun saveContextProfile(
        workspaceId: String,
        request: ContextProfileRequest,
        successMessage: String? = null
    ) {
        if (workspaceId.isBlank()) return
        _uiState.value = _uiState.value.copy(isSavingContext = true)
        viewModelScope.launch {
            try {
                val profile = api.upsertContextProfile(workspaceId, request)
                _uiState.value = _uiState.value.copy(
                    contextProfile = profile,
                    isSavingContext = false,
                    message = successMessage
                )
            } catch (e: Exception) {
                Log.e(TAG, "saveContextProfile failed", e)
                _uiState.value = _uiState.value.copy(
                    isSavingContext = false,
                    error = "コンテクストの保存に失敗しました: ${e.message}"
                )
            }
        }
    }

    fun saveMyPageProfile(
        accountDisplayName: String?,
        workspaceName: String?,
        workspaceDescription: String?,
        deviceDisplayName: String?,
        contextRequest: ContextProfileRequest,
        successMessage: String? = null
    ) {
        val state = _uiState.value
        val selectedAccountId = state.selectedAccountId
        val selectedWorkspaceId = state.selectedWorkspaceId
        val selectedDeviceId = state.selectedDeviceId
        val selectedDeviceRow = state.devices.firstOrNull { it.device_id == selectedDeviceId }

        _uiState.value = state.copy(isSavingContext = true)
        viewModelScope.launch {
            var nextAccounts = _uiState.value.accounts
            var nextWorkspaces = _uiState.value.workspaces
            var nextDevices = _uiState.value.devices
            val warnings = mutableListOf<String>()

            if (!selectedAccountId.isNullOrBlank() && !accountDisplayName.isNullOrBlank()) {
                runCatching {
                    api.updateAccount(
                        accountId = selectedAccountId,
                        displayName = accountDisplayName.trim()
                    )
                }.onSuccess { updated ->
                    nextAccounts = nextAccounts.map { row ->
                        if (row.id == updated.id) updated else row
                    }
                }.onFailure { e ->
                    Log.w(TAG, "updateAccount skipped: ${e.message}")
                    warnings += "アカウント名更新に失敗"
                }
            }

            if (!selectedWorkspaceId.isNullOrBlank() && !workspaceName.isNullOrBlank()) {
                runCatching {
                    api.updateWorkspace(
                        workspaceId = selectedWorkspaceId,
                        name = workspaceName.trim(),
                        description = workspaceDescription
                    )
                }.onSuccess { updated ->
                    nextWorkspaces = nextWorkspaces.map { row ->
                        if (row.id == updated.id) updated else row
                    }
                }.onFailure { e ->
                    Log.w(TAG, "updateWorkspace skipped: ${e.message}")
                    warnings += "ワークスペース更新に失敗"
                }
            }

            if (selectedDeviceRow != null && !deviceDisplayName.isNullOrBlank()) {
                runCatching {
                    api.updateDevice(
                        deviceRowId = selectedDeviceRow.id,
                        displayName = deviceDisplayName.trim()
                    )
                }.onSuccess { updated ->
                    nextDevices = nextDevices.map { row ->
                        if (row.id == updated.id) updated else row
                    }
                }.onFailure { e ->
                    Log.w(TAG, "updateDevice skipped: ${e.message}")
                    warnings += "デバイス名更新に失敗"
                }
            }

            val contextResult = if (!selectedWorkspaceId.isNullOrBlank()) {
                runCatching {
                    api.upsertContextProfile(selectedWorkspaceId, contextRequest)
                }
            } else {
                Result.failure(IllegalStateException("workspace is not selected"))
            }

            contextResult
                .onSuccess { profile ->
                    val message = if (warnings.isEmpty()) {
                        successMessage
                    } else {
                        "コンテクストは保存しました（${warnings.joinToString(" / ")}）"
                    }
                    _uiState.value = _uiState.value.copy(
                        accounts = nextAccounts,
                        workspaces = nextWorkspaces,
                        devices = nextDevices,
                        contextProfile = profile,
                        isSavingContext = false,
                        message = message
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "saveMyPageProfile context failed", e)
                    val suffix = if (warnings.isEmpty()) "" else " / ${warnings.joinToString(" / ")}"
                    _uiState.value = _uiState.value.copy(
                        accounts = nextAccounts,
                        workspaces = nextWorkspaces,
                        devices = nextDevices,
                        isSavingContext = false,
                        error = "コンテクスト保存に失敗しました: ${e.message}$suffix"
                    )
                }
        }
    }

    fun loadSessions(context: Context) {
        viewModelScope.launch {
            currentOffset = 0
            hasMoreSessions = true
            currentTopicOffset = 0
            hasMoreTopics = true
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isLoadingMore = false,
                error = null,
                hasMore = true
            )
            try {
                val viewScope = resolveViewScope(context)
                val response = api.listSessions(
                    deviceId = viewScope.deviceId,
                    workspaceId = viewScope.workspaceId,
                    limit = PAGE_SIZE,
                    offset = 0
                )
                val sorted = response.sessions.sortedByDescending { it.created_at }
                val cards = buildTranscriptCards(sorted)
                val filteredCards = filterDismissed(cards)
                val topicPage = loadTopicCards(
                    deviceId = viewScope.deviceId,
                    workspaceId = viewScope.workspaceId,
                    offset = 0
                )
                Log.d(TAG, "loadSessions success: device=${viewScope.deviceId} sessions=${sorted.size}")
                currentOffset = response.sessions.size
                hasMoreSessions = response.count == PAGE_SIZE
                currentTopicOffset = topicPage.rawCount
                hasMoreTopics = topicPage.hasMore
                _uiState.value = _uiState.value.copy(
                    sessions = sorted,
                    feedCards = filteredCards,
                    topicCards = topicPage.cards,
                    isLoading = false,
                    hasMore = hasMoreSessions || hasMoreTopics,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
                triggerTopicEvaluatePendingInBackground(
                    deviceId = viewScope.deviceId,
                    allowEvaluate = viewScope.allowEvaluate
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadSessions failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "セッションの取得に失敗しました: ${e.message}"
                )
            }
        }
    }

    fun refreshSessions(context: Context, showIndicator: Boolean = true) {
        if (isRefreshing) return
        isRefreshing = true
        val lightweightRefresh = !showIndicator
        if (showIndicator) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        }
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val viewScope = resolveViewScope(context)
                val requestedSessionLimit = if (lightweightRefresh) PAGE_SIZE else max(currentOffset, PAGE_SIZE)
                val requestedTopicLimit = if (lightweightRefresh) PAGE_SIZE else max(currentTopicOffset, PAGE_SIZE)
                val response = api.listSessions(
                    deviceId = viewScope.deviceId,
                    workspaceId = viewScope.workspaceId,
                    limit = requestedSessionLimit,
                    offset = 0
                )
                val fresh = response.sessions.sortedByDescending { it.created_at }
                val freshCards = buildTranscriptCards(fresh)
                val filteredCards = filterDismissed(freshCards)
                val topicPage = loadTopicCards(
                    deviceId = viewScope.deviceId,
                    workspaceId = viewScope.workspaceId,
                    offset = 0,
                    limit = requestedTopicLimit
                )
                Log.d(
                    TAG,
                    "refreshSessions success: device=${viewScope.deviceId} sessions=${fresh.size} topics=${topicPage.cards.size} lightweight=$lightweightRefresh"
                )
                if (lightweightRefresh) {
                    val mergedSessions = mergeSessions(currentState.sessions, fresh)
                    val mergedCards = mergeCards(currentState.feedCards, filteredCards, mergedSessions)
                    val mergedTopicCards = mergeTopicCards(currentState.topicCards, topicPage.cards)

                    if (currentOffset == 0) {
                        currentOffset = fresh.size
                        hasMoreSessions = response.count == PAGE_SIZE
                    }
                    if (currentTopicOffset == 0) {
                        currentTopicOffset = topicPage.rawCount
                        hasMoreTopics = topicPage.hasMore
                    }

                    _uiState.value = _uiState.value.copy(
                        sessions = mergedSessions,
                        feedCards = mergedCards,
                        topicCards = mergedTopicCards,
                        isRefreshing = false,
                        hasMore = hasMoreSessions || hasMoreTopics,
                        dismissedIds = dismissedIds.toSet(),
                        favoriteIds = favoriteIds.toSet()
                    )
                } else {
                    currentOffset = fresh.size
                    hasMoreSessions = response.count == requestedSessionLimit
                    currentTopicOffset = topicPage.rawCount
                    hasMoreTopics = topicPage.hasMore
                    _uiState.value = _uiState.value.copy(
                        sessions = fresh,
                        feedCards = filteredCards,
                        topicCards = topicPage.cards,
                        isRefreshing = false,
                        hasMore = hasMoreSessions || hasMoreTopics,
                        dismissedIds = dismissedIds.toSet(),
                        favoriteIds = favoriteIds.toSet()
                    )
                }
                triggerTopicEvaluatePendingInBackground(
                    deviceId = viewScope.deviceId,
                    allowEvaluate = viewScope.allowEvaluate
                )
            } catch (e: Exception) {
                Log.e(TAG, "refreshSessions failed", e)
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = if (showIndicator) "更新に失敗しました: ${e.message}" else _uiState.value.error
                )
            } finally {
                isRefreshing = false
            }
        }
    }

    fun loadFacts(context: Context, force: Boolean = false) {
        if (isLoadingFacts && !force) return
        isLoadingFacts = true
        viewModelScope.launch {
            try {
                val viewScope = resolveViewScope(context)
                val response = api.listFacts(
                    deviceId = viewScope.deviceId,
                    workspaceId = viewScope.workspaceId
                )
                val grouped = response.facts.groupBy { it.topic_id }
                _uiState.value = _uiState.value.copy(factsByTopic = grouped)
            } catch (e: Exception) {
                Log.e(TAG, "loadFacts failed", e)
            } finally {
                isLoadingFacts = false
            }
        }
    }

    fun refreshFacts(context: Context) {
        if (isLoadingFacts) return
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                val viewScope = resolveViewScope(context)
                val response = api.listFacts(
                    deviceId = viewScope.deviceId,
                    workspaceId = viewScope.workspaceId
                )
                val grouped = response.facts.groupBy { it.topic_id }
                _uiState.value = _uiState.value.copy(
                    factsByTopic = grouped,
                    isRefreshing = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "refreshFacts failed", e)
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    fun loadMoreSessions(context: Context) {
        if (_uiState.value.isLoadingMore || (!hasMoreSessions && !hasMoreTopics)) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                val viewScope = resolveViewScope(context)
                val response = if (hasMoreSessions) {
                    api.listSessions(
                        deviceId = viewScope.deviceId,
                        workspaceId = viewScope.workspaceId,
                        limit = PAGE_SIZE,
                        offset = currentOffset
                    )
                } else {
                    SessionListResponse(sessions = emptyList(), count = 0)
                }
                val nextSessions = response.sessions.sortedByDescending { it.created_at }
                val mergedSessions = mergeSessions(_uiState.value.sessions, nextSessions)
                val nextCards = buildTranscriptCards(nextSessions)
                val mergedCards = mergeCards(_uiState.value.feedCards, nextCards, mergedSessions)
                val filteredCards = filterDismissed(mergedCards)
                val topicPage = if (hasMoreTopics) {
                    loadTopicCards(
                        deviceId = viewScope.deviceId,
                        workspaceId = viewScope.workspaceId,
                        offset = currentTopicOffset
                    )
                } else {
                    TopicPageResult(cards = emptyList(), rawCount = 0, hasMore = false)
                }
                val mergedTopicCards = mergeTopicCards(_uiState.value.topicCards, topicPage.cards)

                currentOffset += response.sessions.size
                hasMoreSessions = response.count == PAGE_SIZE
                currentTopicOffset += topicPage.rawCount
                hasMoreTopics = topicPage.hasMore

                _uiState.value = _uiState.value.copy(
                    sessions = mergedSessions,
                    feedCards = filteredCards,
                    topicCards = mergedTopicCards,
                    isLoadingMore = false,
                    hasMore = hasMoreSessions || hasMoreTopics,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMore = false)
            }
        }
    }

    fun uploadAndProcess(context: Context, audioFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                Log.d(TAG, "Upload starting: file=${audioFile.name}, size=${audioFile.length()}, device=$deviceId")

                val uploadResult = api.uploadAudio(audioFile, deviceId)
                Log.d(TAG, "Upload success: session=${uploadResult.session_id}")

                val asrProvider = AmbientPreferences.getAsrProvider(context)
                api.transcribe(uploadResult.session_id, autoChain = false, provider = asrProvider)
                Log.d(TAG, "Transcribe triggered for session=${uploadResult.session_id}")

                _uiState.value = _uiState.value.copy(isUploading = false)
                loadSessions(context)

            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = "アップロードに失敗しました: ${e.message}"
                )
            }
        }
    }

    fun retranscribeSession(
        context: Context,
        sessionId: String,
        language: String = "en"
    ) {
        viewModelScope.launch {
            try {
                markCardAsTranscribing(sessionId)
                val asrProvider = AmbientPreferences.getAsrProvider(context)
                api.transcribe(
                    sessionId = sessionId,
                    autoChain = false,
                    provider = asrProvider,
                    language = language
                )
                Log.d(TAG, "Re-transcribe triggered: session=$sessionId provider=$asrProvider language=$language")
                refreshSessions(context)
            } catch (e: Exception) {
                Log.e(TAG, "Re-transcribe failed: session=$sessionId language=$language", e)
                _uiState.value = _uiState.value.copy(
                    error = "再文字起こしに失敗しました: ${e.message}"
                )
            }
        }
    }

    fun retryTranscribeSession(context: Context, sessionId: String) {
        viewModelScope.launch {
            try {
                markCardAsTranscribing(sessionId)
                val asrProvider = AmbientPreferences.getAsrProvider(context)
                api.transcribe(
                    sessionId = sessionId,
                    autoChain = false,
                    provider = asrProvider
                )
                Log.d(TAG, "Retry transcribe triggered: session=$sessionId provider=$asrProvider")
                refreshSessions(context)
            } catch (e: Exception) {
                Log.e(TAG, "Retry transcribe failed: session=$sessionId", e)
                _uiState.value = _uiState.value.copy(
                    error = "文字起こしの再試行に失敗しました: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun deleteCard(context: Context, id: String) {
        dismissedIds.add(id)
        favoriteIds.remove(id)
        val retainedRecordings = AmbientStatus.state.value.recordings.filterNot { it.sessionId == id }
        AmbientStatus.update(
            recordings = retainedRecordings,
            lastEvent = "Card deleted"
        )
        removeCardFromUi(id)
        viewModelScope.launch {
            try {
                api.deleteSession(id)
                _uiState.value = _uiState.value.copy(
                    message = "カードを削除しました",
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
                refreshSessions(context)
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed: session=$id", e)
                dismissedIds.remove(id)
                _uiState.value = _uiState.value.copy(
                    error = "カードの削除に失敗しました: ${e.message}",
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
                refreshSessions(context)
            }
        }
    }

    private fun removeCardFromUi(id: String) {
        val current = _uiState.value
        val updatedTopics = current.topicCards.map { topic ->
            topic.copy(utterances = topic.utterances.filterNot { it.id == id })
        }.filter { topic -> topic.utterances.isNotEmpty() }
        _uiState.value = current.copy(
            sessions = current.sessions.filterNot { it.id == id },
            feedCards = current.feedCards.filterNot { it.id == id },
            topicCards = updatedTopics,
            dismissedIds = dismissedIds.toSet(),
            favoriteIds = favoriteIds.toSet(),
            selectedCardId = if (current.selectedCardId == id) null else current.selectedCardId
        )
    }

    fun toggleFavorite(id: String) {
        if (favoriteIds.contains(id)) {
            favoriteIds.remove(id)
        } else {
            favoriteIds.add(id)
        }
        _uiState.value = _uiState.value.copy(
            favoriteIds = favoriteIds.toSet()
        )
    }

    fun selectCard(id: String) {
        _uiState.value = _uiState.value.copy(selectedCardId = id)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedCardId = null)
    }

    private suspend fun buildTranscriptCard(summary: SessionSummary): TranscriptCard? {
        return try {
            val detail = api.getSession(summary.id)
            val text = detail.transcription?.trim().orEmpty()
            val status = resolveProcessingStatus(
                id = summary.id,
                rawStatus = resolveOptimisticStatus(summary.id, detail.status),
                backendTimestamp = detail.updated_at
            )
            val isUnintelligible = text.isEmpty() && status in listOf("transcribed", "completed")
            val (baseDisplayStatus, isProcessing) = mapStatus(status)
            val displayStatus = if (isUnintelligible) "判別不可" else baseDisplayStatus
            val displayText = when {
                text.isNotEmpty() -> text
                isUnintelligible -> UNINTELLIGIBLE_CARD_TEXT
                status == "failed" -> "処理に失敗しました"
                else -> "データ取得中..."
            }
            val sourceTimestamp = detail.recorded_at ?: detail.created_at
            val createdAtEpoch = parseEpochMillis(sourceTimestamp)
            val displayTitle = buildTitle(sourceTimestamp)
            val displayDate = buildDisplayDate(sourceTimestamp)
            val duration = detail.duration_seconds ?: 0
            val metadata = detail.transcription_metadata ?: emptyMap()
            val asrProvider = metadata["provider"] as? String
            val asrModel = metadata["model"] as? String
            val asrLanguage = metadata["language"] as? String
            val speakerCount = extractSpeakerCount(metadata)
            val speakerLabels = extractSpeakerLabels(metadata, asrProvider)
            val speakerSegments = extractSpeakerSegments(metadata, asrProvider)
            TranscriptCard(
                id = summary.id,
                createdAt = summary.created_at,
                createdAtEpochMs = createdAtEpoch,
                status = status,
                displayStatus = displayStatus,
                isProcessing = isProcessing,
                text = displayText,
                displayTitle = displayTitle,
                durationSeconds = duration,
                displayDate = displayDate,
                asrProvider = asrProvider,
                asrModel = asrModel,
                asrLanguage = asrLanguage,
                speakerCount = speakerCount,
                speakerLabels = speakerLabels,
                speakerSegments = speakerSegments,
                isUnintelligible = isUnintelligible
            )
        } catch (e: Exception) {
            TranscriptCard(
                id = summary.id,
                createdAt = summary.created_at,
                createdAtEpochMs = 0L,
                status = summary.status,
                displayStatus = "エラー",
                isProcessing = false,
                text = "読み込みに失敗しました",
                displayTitle = "--:--",
                durationSeconds = 0,
                displayDate = "",
                asrProvider = null,
                asrModel = null,
                asrLanguage = null,
                speakerCount = 0,
                speakerLabels = emptyList(),
                speakerSegments = emptyList(),
                isUnintelligible = false
            )
        }
    }

    private suspend fun buildTranscriptCards(summaries: List<SessionSummary>): List<TranscriptCard> =
        coroutineScope {
            if (summaries.isEmpty()) return@coroutineScope emptyList()

            val limiter = Semaphore(SESSION_DETAIL_PARALLELISM)
            summaries.map { summary ->
                async {
                    limiter.withPermit {
                        buildTranscriptCard(summary)
                    }
                }
            }.awaitAll().mapNotNull { it }
        }

    private suspend fun loadTopicCards(
        deviceId: String?,
        workspaceId: String?,
        offset: Int,
        limit: Int = PAGE_SIZE
    ): TopicPageResult {
        return try {
            val response = api.listTopics(
                deviceId = deviceId,
                workspaceId = workspaceId,
                limit = limit,
                offset = offset,
                includeChildren = true
            )
            val cards = response.topics
                .mapNotNull { topic -> buildTopicCard(topic) }
                .filter { it.utterances.isNotEmpty() }
                .sortedByDescending { it.updatedAtEpochMs }
            TopicPageResult(
                cards = cards,
                rawCount = response.count,
                hasMore = response.count == PAGE_SIZE
            )
        } catch (e: Exception) {
            Log.e(TAG, "loadTopicCards failed: device=$deviceId offset=$offset", e)
            TopicPageResult(cards = emptyList(), rawCount = 0, hasMore = false)
        }
    }

    private fun triggerTopicEvaluatePendingInBackground(
        deviceId: String?,
        allowEvaluate: Boolean
    ) {
        if (!allowEvaluate || deviceId.isNullOrBlank()) return
        if (topicEvaluateInFlight) return

        val now = System.currentTimeMillis()
        if ((now - lastTopicEvaluateAtMs) < TOPIC_EVALUATE_COOLDOWN_MS) return

        topicEvaluateInFlight = true
        lastTopicEvaluateAtMs = now

        viewModelScope.launch {
            try {
                val eval = api.evaluatePendingTopics(
                    deviceId = deviceId,
                    force = false,
                    idleSeconds = TOPIC_IDLE_SECONDS,
                    maxSessions = 200
                )
                Log.d(TAG, "topic evaluate-pending (bg): device=$deviceId result=${eval.result}")
            } catch (e: Exception) {
                Log.w(TAG, "topic evaluate-pending (bg) failed: device=$deviceId", e)
            } finally {
                topicEvaluateInFlight = false
            }
        }
    }

    fun evaluatePendingTopics(
        context: Context,
        force: Boolean = true,
        idleSeconds: Int = 60,
        maxSessions: Int = 200,
        reason: String = "ambient_stop"
    ) {
        viewModelScope.launch {
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val eval = api.evaluatePendingTopics(
                    deviceId = deviceId,
                    force = force,
                    idleSeconds = idleSeconds,
                    maxSessions = maxSessions,
                    boundaryReason = when (reason) {
                        "ambient_stop" -> "ambient_stopped"
                        else -> if (force) "manual" else null
                    }
                )
                Log.d(TAG, "evaluate-pending triggered reason=$reason device=$deviceId result=${eval.result}")
                refreshSessions(context)
            } catch (e: Exception) {
                Log.w(TAG, "evaluate-pending failed reason=$reason", e)
            }
        }
    }

    private fun buildTopicCard(topic: TopicSummary): TopicFeedCard? {
        val utteranceCards = topic.utterances
            .map { utterance -> buildTranscriptCardFromTopicUtterance(utterance) }
            .filterNot { card -> dismissedIds.contains(card.id) }

        if (utteranceCards.isEmpty()) return null
        val isUnintelligibleTopic = utteranceCards.all { it.isUnintelligible }

        val status = topic.topic_status
        val title = if (isUnintelligibleTopic) {
            UNINTELLIGIBLE_TOPIC_TITLE
        } else {
            (topic.final_title ?: topic.live_title)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "無題のトピック"
        }
        val summary = if (isUnintelligibleTopic) {
            UNINTELLIGIBLE_TOPIC_SUMMARY
        } else {
            (topic.final_summary ?: topic.live_summary)?.trim().orEmpty()
        }
        val updatedAt = topic.last_utterance_at ?: topic.updated_at ?: topic.end_at ?: topic.start_at
        val updatedAtEpochMs = parseEpochMillis(updatedAt)
        val displayDate = buildDisplayDate(updatedAt)

        return TopicFeedCard(
            id = topic.id,
            status = status,
            title = title,
            summary = summary,
            utteranceCount = topic.utterance_count ?: utteranceCards.size,
            updatedAtEpochMs = updatedAtEpochMs,
            displayDate = displayDate,
            utterances = utteranceCards,
            llmProvider = topic.llm_provider,
            llmModel = topic.llm_model,
            isUnintelligible = isUnintelligibleTopic,
            importanceLevel = topic.importance_level,
            importanceReason = topic.importance_reason
        )
    }

    private fun buildTranscriptCardFromTopicUtterance(utterance: TopicUtteranceSummary): TranscriptCard {
        val status = resolveProcessingStatus(
            id = utterance.id,
            rawStatus = resolveOptimisticStatus(utterance.id, utterance.status),
            backendTimestamp = utterance.updated_at
        )
        val text = utterance.transcription?.trim().orEmpty()
        val isUnintelligible = text.isEmpty() && status in listOf("transcribed", "completed")
        val (baseDisplayStatus, isProcessing) = mapStatus(status)
        val displayStatus = if (isUnintelligible) "判別不可" else baseDisplayStatus
        val sourceTimestamp = utterance.recorded_at ?: utterance.created_at
        val metadata = utterance.transcription_metadata ?: emptyMap()
        val asrProvider = metadata["provider"] as? String
        val asrModel = metadata["model"] as? String
        val asrLanguage = metadata["language"] as? String
        val speakerCount = extractSpeakerCount(metadata)
        val speakerLabels = extractSpeakerLabels(metadata, asrProvider)
        val speakerSegments = extractSpeakerSegments(metadata, asrProvider)
        val displayText = when {
            text.isNotEmpty() -> text
            isUnintelligible -> UNINTELLIGIBLE_CARD_TEXT
            status == "failed" -> "処理に失敗しました"
            else -> "データ取得中..."
        }

        return TranscriptCard(
            id = utterance.id,
            createdAt = utterance.created_at,
            createdAtEpochMs = parseEpochMillis(sourceTimestamp),
            status = status,
            displayStatus = displayStatus,
            isProcessing = isProcessing,
            text = displayText,
            displayTitle = buildTitle(sourceTimestamp),
            durationSeconds = utterance.duration_seconds ?: 0,
            displayDate = buildDisplayDate(sourceTimestamp),
            asrProvider = asrProvider,
            asrModel = asrModel,
            asrLanguage = asrLanguage,
            speakerCount = speakerCount,
            speakerLabels = speakerLabels,
            speakerSegments = speakerSegments,
            isUnintelligible = isUnintelligible
        )
    }

    private fun extractSpeakerCount(metadata: Map<String, Any>): Int {
        val raw = metadata["speaker_count"] ?: return 0
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    private fun extractSpeakerLabels(
        metadata: Map<String, Any>,
        provider: String?
    ): List<String> {
        val utteranceLabels = (metadata["utterances"] as? List<*>)
            .orEmpty()
            .mapNotNull { item ->
                val speaker = (item as? Map<*, *>)?.get("speaker") ?: return@mapNotNull null
                normalizeSpeakerLabel(speaker, provider)
            }
            .distinct()

        if (utteranceLabels.isNotEmpty()) return utteranceLabels

        val speakerCount = extractSpeakerCount(metadata)
        return (1..speakerCount).map { "Speaker $it" }
    }

    private data class SpeakerSegmentCandidate(
        val speakerLabel: String,
        val text: String,
        val start: Double?
    )

    private fun extractSpeakerSegments(
        metadata: Map<String, Any>,
        provider: String?
    ): List<SpeakerSegment> {
        val rawUtterances = metadata["utterances"] as? List<*> ?: return emptyList()
        val candidates = rawUtterances.mapNotNull { item ->
            val row = item as? Map<*, *> ?: return@mapNotNull null
            val speaker = row["speaker"] ?: return@mapNotNull null
            val speakerLabel = normalizeSpeakerLabel(speaker, provider) ?: return@mapNotNull null
            val text = (row["text"] as? String)
                ?: (row["transcript"] as? String)
                ?: (row["utterance"] as? String)
                ?: ""
            val trimmed = text.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val start = when (val raw = row["start"]) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            SpeakerSegmentCandidate(speakerLabel = speakerLabel, text = trimmed, start = start)
        }

        if (candidates.isEmpty()) return emptyList()
        val ordered = if (candidates.any { it.start != null }) {
            candidates.sortedBy { it.start ?: Double.MAX_VALUE }
        } else {
            candidates
        }

        val merged = mutableListOf<SpeakerSegment>()
        var currentLabel: String? = null
        var currentText = StringBuilder()

        fun flush() {
            val label = currentLabel ?: return
            val text = currentText.toString().trim()
            if (text.isNotBlank()) {
                merged.add(SpeakerSegment(label, text))
            }
        }

        ordered.forEach { item ->
            if (currentLabel == null) {
                currentLabel = item.speakerLabel
                currentText.append(item.text)
                return@forEach
            }
            if (item.speakerLabel == currentLabel) {
                if (currentText.isNotEmpty()) currentText.append(" ")
                currentText.append(item.text)
            } else {
                flush()
                currentLabel = item.speakerLabel
                currentText = StringBuilder(item.text)
            }
        }
        flush()
        return merged
    }

    private fun normalizeSpeakerLabel(raw: Any, provider: String?): String? {
        val normalizedProvider = provider?.trim()?.lowercase(Locale.ROOT)
        val speakerNumber = when (raw) {
            is Number -> raw.toInt()
            is String -> Regex("""\d+""").find(raw)?.value?.toIntOrNull()
            else -> null
        } ?: return null

        val oneBased = if (normalizedProvider == "deepgram") {
            speakerNumber + 1
        } else {
            speakerNumber.coerceAtLeast(1)
        }
        return "Speaker $oneBased"
    }

    private fun mapStatus(status: String): Pair<String, Boolean> {
        val displayStatus = when (status) {
            "uploaded" -> "キュー待ち"
            "transcribing" -> "文字起こし中"
            "transcribed" -> "文字起こし済み"
            "completed" -> "完了"
            "failed" -> "失敗"
            "generating" -> "分析中"
            "active" -> "ライブ"
            "cooling" -> "整理中"
            "finalized" -> "完了"
            else -> "不明"
        }
        val isProcessing = status in listOf("uploaded", "transcribing", "generating")
        return displayStatus to isProcessing
    }

    private fun resolveOptimisticStatus(id: String, status: String): String {
        val startedAt = optimisticTranscribing[id] ?: return status
        val ageMs = System.currentTimeMillis() - startedAt
        if (status in listOf("transcribed", "completed")) {
            optimisticTranscribing.remove(id)
            return status
        }
        if (ageMs > OPTIMISTIC_TIMEOUT_MS) {
            optimisticTranscribing.remove(id)
            return status
        }
        return if (status == "failed") "transcribing" else status
    }

    private fun resolveProcessingStatus(
        id: String,
        rawStatus: String,
        backendTimestamp: String?
    ): String {
        if (rawStatus !in setOf("pending", "uploaded", "transcribing", "generating")) {
            return rawStatus
        }
        val now = System.currentTimeMillis()
        val referenceEpoch = optimisticTranscribing[id] ?: parseEpochMillis(backendTimestamp)
        if (referenceEpoch <= 0L) {
            return rawStatus
        }
        val ageMs = now - referenceEpoch
        return if (ageMs >= PROCESSING_TIMEOUT_MS) "failed" else rawStatus
    }

    private fun markCardAsTranscribing(sessionId: String) {
        val state = _uiState.value
        optimisticTranscribing[sessionId] = System.currentTimeMillis()
        val updatedSessions = state.sessions.map { session ->
            if (session.id != sessionId) {
                session
            } else {
                session.copy(status = "transcribing", error_message = null)
            }
        }
        val updatedCards = state.feedCards.map { card ->
            if (card.id != sessionId) {
                card
            } else {
                card.copy(
                    status = "transcribing",
                    displayStatus = "データ取得中",
                    isProcessing = true,
                    text = "データ取得中..."
                )
            }
        }
        val updatedTopics = state.topicCards.map { topic ->
            val updatedUtterances = topic.utterances.map { card ->
                if (card.id != sessionId) {
                    card
                } else {
                    card.copy(
                        status = "transcribing",
                        displayStatus = "データ取得中",
                        isProcessing = true,
                        text = "データ取得中..."
                    )
                }
            }
            topic.copy(utterances = updatedUtterances)
        }
        _uiState.value = state.copy(
            sessions = updatedSessions,
            feedCards = updatedCards,
            topicCards = updatedTopics
        )
    }

    private fun filterDismissed(cards: List<TranscriptCard>): List<TranscriptCard> {
        if (dismissedIds.isEmpty()) return cards
        return cards.filterNot { dismissedIds.contains(it.id) }
    }

    private fun mergeSessions(
        current: List<SessionSummary>,
        incoming: List<SessionSummary>
    ): List<SessionSummary> {
        val map = LinkedHashMap<String, SessionSummary>()
        (incoming + current).forEach { session ->
            map[session.id] = session
        }
        return map.values.sortedByDescending { it.created_at }
    }

    private fun mergeCards(
        current: List<TranscriptCard>,
        incoming: List<TranscriptCard>,
        orderedSessions: List<SessionSummary>
    ): List<TranscriptCard> {
        val map = LinkedHashMap<String, TranscriptCard>()
        current.forEach { map[it.id] = it }
        incoming.forEach { map[it.id] = it }
        return orderedSessions.mapNotNull { map[it.id] }
    }

    private fun mergeTopicCards(
        current: List<TopicFeedCard>,
        incoming: List<TopicFeedCard>
    ): List<TopicFeedCard> {
        val map = LinkedHashMap<String, TopicFeedCard>()
        current.forEach { map[it.id] = it }
        incoming.forEach { map[it.id] = it }
        return map.values.sortedByDescending { it.updatedAtEpochMs }
    }

    private fun buildTitle(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "--:--"
        val value = timestamp.trim()
        return try {
            val parsed = OffsetDateTime.parse(value)
            parsed.atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            if (value.length >= 5) value.substring(0, 5) else value
        }
    }

    private fun buildDisplayDate(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return ""
        return try {
            val parsed = OffsetDateTime.parse(timestamp.trim())
            val localDate = parsed.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
            val today = LocalDate.now()
            when (localDate) {
                today -> "今日"
                today.minusDays(1) -> "昨日"
                else -> localDate.format(DateTimeFormatter.ofPattern("M/d (E)", Locale.JAPAN))
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseEpochMillis(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return 0L
        return try {
            OffsetDateTime.parse(timestamp.trim()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

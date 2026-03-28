package com.example.zero_touch.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.api.SessionListResponse
import com.example.zero_touch.api.SessionSummary
import com.example.zero_touch.api.TopicSummary
import com.example.zero_touch.api.TopicUtteranceSummary
import com.example.zero_touch.api.ZeroTouchApi
import com.example.zero_touch.audio.ambient.AmbientPreferences
import com.example.zero_touch.audio.ambient.AmbientStatus
import kotlin.math.max
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    val isUnintelligible: Boolean = false
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

data class ZeroTouchUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val feedCards: List<TranscriptCard> = emptyList(),
    val topicCards: List<TopicFeedCard> = emptyList(),
    val isUploading: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val dismissedIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet(),
    val selectedCardId: String? = null
)

class ZeroTouchViewModel : ViewModel() {
    companion object {
        private const val TAG = "ZeroTouchVM"
        private const val PAGE_SIZE = 10
        private const val OPTIMISTIC_TIMEOUT_MS = 60_000L
        private const val TOPIC_IDLE_SECONDS = 30
    }

    private val api = ZeroTouchApi()

    private val _uiState = MutableStateFlow(ZeroTouchUiState())
    val uiState: StateFlow<ZeroTouchUiState> = _uiState

    private var isRefreshing = false
    private val dismissedIds = mutableSetOf<String>()
    private val favoriteIds = mutableSetOf<String>()
    private var currentOffset = 0
    private var hasMoreSessions = true
    private var currentTopicOffset = 0
    private var hasMoreTopics = true
    private val optimisticTranscribing = mutableMapOf<String, Long>()

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
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = api.listSessions(deviceId = deviceId, limit = PAGE_SIZE, offset = 0)
                val sorted = response.sessions.sortedByDescending { it.created_at }
                val cards = sorted.mapNotNull { summary -> buildTranscriptCard(summary) }
                val filteredCards = filterDismissed(cards)
                val topicPage = loadTopicCards(deviceId = deviceId, offset = 0)
                Log.d(TAG, "loadSessions success: device=$deviceId sessions=${sorted.size}")
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
        if (showIndicator) {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
        }
        viewModelScope.launch {
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val requestedSessionLimit = max(currentOffset, PAGE_SIZE)
                val requestedTopicLimit = max(currentTopicOffset, PAGE_SIZE)
                val response = api.listSessions(deviceId = deviceId, limit = requestedSessionLimit, offset = 0)
                val fresh = response.sessions.sortedByDescending { it.created_at }
                val freshCards = fresh.mapNotNull { summary -> buildTranscriptCard(summary) }
                val filteredCards = filterDismissed(freshCards)
                val topicPage = loadTopicCards(
                    deviceId = deviceId,
                    offset = 0,
                    limit = requestedTopicLimit
                )
                currentOffset = fresh.size
                hasMoreSessions = response.count == requestedSessionLimit
                currentTopicOffset = topicPage.rawCount
                hasMoreTopics = topicPage.hasMore
                Log.d(
                    TAG,
                    "refreshSessions success: device=$deviceId sessions=${fresh.size} topics=${topicPage.cards.size}"
                )
                _uiState.value = _uiState.value.copy(
                    sessions = fresh,
                    feedCards = filteredCards,
                    topicCards = topicPage.cards,
                    isRefreshing = false,
                    hasMore = hasMoreSessions || hasMoreTopics,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
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

    fun loadMoreSessions(context: Context) {
        if (_uiState.value.isLoadingMore || (!hasMoreSessions && !hasMoreTopics)) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = if (hasMoreSessions) {
                    api.listSessions(deviceId = deviceId, limit = PAGE_SIZE, offset = currentOffset)
                } else {
                    SessionListResponse(sessions = emptyList(), count = 0)
                }
                val nextSessions = response.sessions.sortedByDescending { it.created_at }
                val mergedSessions = mergeSessions(_uiState.value.sessions, nextSessions)
                val nextCards = nextSessions.mapNotNull { summary -> buildTranscriptCard(summary) }
                val mergedCards = mergeCards(_uiState.value.feedCards, nextCards, mergedSessions)
                val filteredCards = filterDismissed(mergedCards)
                val topicPage = if (hasMoreTopics) {
                    loadTopicCards(deviceId = deviceId, offset = currentTopicOffset)
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
            val status = resolveOptimisticStatus(summary.id, detail.status)
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
                isUnintelligible = false
            )
        }
    }

    private suspend fun loadTopicCards(
        deviceId: String,
        offset: Int,
        limit: Int = PAGE_SIZE
    ): TopicPageResult {
        return try {
            if (offset == 0) {
                try {
                    val eval = api.evaluatePendingTopics(
                        deviceId = deviceId,
                        force = false,
                        idleSeconds = TOPIC_IDLE_SECONDS,
                        maxSessions = 200
                    )
                    Log.d(TAG, "topic evaluate-pending: device=$deviceId result=${eval.result}")
                } catch (e: Exception) {
                    Log.w(TAG, "topic evaluate-pending failed: device=$deviceId", e)
                }
            }

            val response = api.listTopics(
                deviceId = deviceId,
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
        val status = resolveOptimisticStatus(utterance.id, utterance.status)
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

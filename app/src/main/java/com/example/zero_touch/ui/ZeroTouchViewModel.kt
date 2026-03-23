package com.example.zero_touch.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.api.SessionSummary
import com.example.zero_touch.api.ZeroTouchApi
import com.example.zero_touch.audio.ambient.AmbientPreferences
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

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
    val asrModel: String? = null
)

data class ZeroTouchUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val feedCards: List<TranscriptCard> = emptyList(),
    val isUploading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val dismissedIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet(),
    val selectedCardId: String? = null
)

class ZeroTouchViewModel : ViewModel() {
    companion object {
        private const val TAG = "ZeroTouchVM"
        private const val PAGE_SIZE = 10
    }

    private val api = ZeroTouchApi()

    private val _uiState = MutableStateFlow(ZeroTouchUiState())
    val uiState: StateFlow<ZeroTouchUiState> = _uiState

    private var isRefreshing = false
    private val dismissedIds = mutableSetOf<String>()
    private val favoriteIds = mutableSetOf<String>()
    private var currentOffset = 0
    private var hasMore = true

    fun loadSessions(context: Context) {
        viewModelScope.launch {
            currentOffset = 0
            hasMore = true
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
                Log.d(TAG, "loadSessions success: device=$deviceId sessions=${sorted.size}")
                currentOffset = response.sessions.size
                hasMore = response.count == PAGE_SIZE
                _uiState.value = _uiState.value.copy(
                    sessions = sorted,
                    feedCards = filteredCards,
                    isLoading = false,
                    hasMore = hasMore,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadSessions failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load sessions: ${e.message}"
                )
            }
        }
    }

    fun refreshSessions(context: Context) {
        if (isRefreshing) return
        isRefreshing = true
        viewModelScope.launch {
            try {
                val existingSessions = _uiState.value.sessions
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = api.listSessions(deviceId = deviceId, limit = PAGE_SIZE, offset = 0)
                val fresh = response.sessions.sortedByDescending { it.created_at }
                val newItems = fresh.count { freshItem -> existingSessions.none { it.id == freshItem.id } }
                if (newItems > 0) currentOffset += newItems
                val mergedSessions = mergeSessions(_uiState.value.sessions, fresh)
                val freshCards = fresh.mapNotNull { summary -> buildTranscriptCard(summary) }
                val mergedCards = mergeCards(_uiState.value.feedCards, freshCards, mergedSessions)
                val filteredCards = filterDismissed(mergedCards)
                Log.d(TAG, "refreshSessions success: device=$deviceId newItems=$newItems")
                _uiState.value = _uiState.value.copy(
                    sessions = mergedSessions,
                    feedCards = filteredCards,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
            } catch (e: Exception) {
                Log.e(TAG, "refreshSessions failed", e)
            } finally {
                isRefreshing = false
            }
        }
    }

    fun loadMoreSessions(context: Context) {
        if (_uiState.value.isLoadingMore || !hasMore) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = api.listSessions(deviceId = deviceId, limit = PAGE_SIZE, offset = currentOffset)
                val nextSessions = response.sessions.sortedByDescending { it.created_at }
                val mergedSessions = mergeSessions(_uiState.value.sessions, nextSessions)
                val nextCards = nextSessions.mapNotNull { summary -> buildTranscriptCard(summary) }
                val mergedCards = mergeCards(_uiState.value.feedCards, nextCards, mergedSessions)
                val filteredCards = filterDismissed(mergedCards)

                currentOffset += response.sessions.size
                hasMore = response.count == PAGE_SIZE

                _uiState.value = _uiState.value.copy(
                    sessions = mergedSessions,
                    feedCards = filteredCards,
                    isLoadingMore = false,
                    hasMore = hasMore,
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
                    error = "Upload failed: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissCard(id: String) {
        dismissedIds.add(id)
        val current = _uiState.value
        _uiState.value = current.copy(
            feedCards = filterDismissed(current.feedCards),
            dismissedIds = dismissedIds.toSet(),
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
            val status = detail.status
            val displayStatus = when (status) {
                "uploaded" -> "Queued"
                "transcribing" -> "Transcribing"
                "transcribed" -> "Transcribed"
                "completed" -> "Completed"
                "failed" -> "Failed"
                "generating" -> "Generating"
                else -> status
            }
            val isProcessing = status in listOf("uploaded", "transcribing", "generating")
            val displayText = when {
                text.isNotEmpty() -> text
                status in listOf("transcribed", "completed") -> "No speech detected"
                status == "failed" -> "Processing failed"
                else -> "Processing..."
            }
            val sourceTimestamp = detail.recorded_at ?: detail.created_at
            val createdAtEpoch = parseEpochMillis(sourceTimestamp)
            val displayTitle = buildTitle(sourceTimestamp)
            val displayDate = buildDisplayDate(sourceTimestamp)
            val duration = detail.duration_seconds ?: 0
            val metadata = detail.transcription_metadata ?: emptyMap()
            val asrProvider = metadata["provider"] as? String
            val asrModel = metadata["model"] as? String
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
                asrModel = asrModel
            )
        } catch (e: Exception) {
            TranscriptCard(
                id = summary.id,
                createdAt = summary.created_at,
                createdAtEpochMs = 0L,
                status = summary.status,
                displayStatus = "Error",
                isProcessing = false,
                text = "Failed to load",
                displayTitle = "--:--",
                durationSeconds = 0,
                displayDate = "",
                asrProvider = null,
                asrModel = null
            )
        }
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
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
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

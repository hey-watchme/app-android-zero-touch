package com.example.zero_touch.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.api.SessionSummary
import com.example.zero_touch.api.ZeroTouchApi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val displayTitle: String
)

data class ZeroTouchUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val feedCards: List<TranscriptCard> = emptyList(),
    val isUploading: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val dismissedIds: Set<String> = emptySet(),
    val favoriteIds: Set<String> = emptySet()
)

class ZeroTouchViewModel : ViewModel() {
    companion object {
        private const val TAG = "ZeroTouchVM"
    }

    private val api = ZeroTouchApi()

    private val _uiState = MutableStateFlow(ZeroTouchUiState())
    val uiState: StateFlow<ZeroTouchUiState> = _uiState

    private var isRefreshing = false
    private val dismissedIds = mutableSetOf<String>()
    private val favoriteIds = mutableSetOf<String>()

    fun loadSessions(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = api.listSessions(deviceId = deviceId, limit = 10)
                val sorted = response.sessions.sortedByDescending { it.created_at }
                val cards = sorted.mapNotNull { summary ->
                    buildTranscriptCard(summary)
                }
                val filteredCards = filterDismissed(cards)
                _uiState.value = _uiState.value.copy(
                    sessions = sorted,
                    feedCards = filteredCards,
                    isLoading = false,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
            } catch (e: Exception) {
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
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = api.listSessions(deviceId = deviceId, limit = 10)
                val sorted = response.sessions.sortedByDescending { it.created_at }
                val cards = sorted.mapNotNull { summary ->
                    buildTranscriptCard(summary)
                }
                val filteredCards = filterDismissed(cards)
                _uiState.value = _uiState.value.copy(
                    sessions = sorted,
                    feedCards = filteredCards,
                    dismissedIds = dismissedIds.toSet(),
                    favoriteIds = favoriteIds.toSet()
                )
            } catch (_: Exception) {
                // ignore refresh errors
            } finally {
                isRefreshing = false
            }
        }
    }

    fun uploadAndProcess(context: Context, audioFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                Log.d(TAG, "Upload starting: file=${audioFile.name}, size=${audioFile.length()}, device=$deviceId")

                // Upload
                val uploadResult = api.uploadAudio(audioFile, deviceId)
                Log.d(TAG, "Upload success: session=${uploadResult.session_id}")

                // Trigger transcription only (LLM pipeline disabled)
                api.transcribe(uploadResult.session_id, autoChain = false)
                Log.d(TAG, "Transcribe triggered for session=${uploadResult.session_id}")

                _uiState.value = _uiState.value.copy(
                    isUploading = false
                )

                // Refresh sessions list
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
            dismissedIds = dismissedIds.toSet()
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

    private suspend fun buildTranscriptCard(summary: SessionSummary): TranscriptCard? {
        return try {
            val detail = api.getSession(summary.id)
            val text = detail.transcription?.trim().orEmpty()
            val status = detail.status
            val displayStatus = when (status) {
                "uploaded" -> "Queued"
                "transcribing" -> "Processing audio"
                "transcribed" -> "Transcribed"
                "completed" -> "Completed"
                "failed" -> "Failed"
                "generating" -> "Processing audio"
                else -> status
            }
            val isProcessing = status in listOf("uploaded", "transcribing", "generating")
            val displayText = when {
                text.isNotEmpty() -> text
                status in listOf("transcribed", "completed") -> "会話が検出されませんでした"
                status == "failed" -> "(failed)"
                else -> "(processing)"
            }
            val sourceTimestamp = detail.recorded_at ?: detail.created_at
            val createdAtEpoch = parseEpochMillis(sourceTimestamp)
            val displayTitle = buildTitle(sourceTimestamp)
            TranscriptCard(
                id = summary.id,
                createdAt = summary.created_at,
                createdAtEpochMs = createdAtEpoch,
                status = status,
                displayStatus = displayStatus,
                isProcessing = isProcessing,
                text = displayText,
                displayTitle = displayTitle
            )
        } catch (e: Exception) {
            TranscriptCard(
                id = summary.id,
                createdAt = summary.created_at,
                createdAtEpochMs = 0L,
                status = summary.status,
                displayStatus = "Failed to load",
                isProcessing = false,
                text = "(failed to load)",
                displayTitle = "Unknown time"
            )
        }
    }

    private fun filterDismissed(cards: List<TranscriptCard>): List<TranscriptCard> {
        if (dismissedIds.isEmpty()) return cards
        return cards.filterNot { dismissedIds.contains(it.id) }
    }

    private fun buildTitle(timestamp: String?): String {
        if (timestamp.isNullOrBlank()) return "Unknown time"
        val value = timestamp.trim()
        return try {
            val parsed = OffsetDateTime.parse(value)
            parsed.atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            if (value.length >= 5) value.substring(0, 5) else value
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

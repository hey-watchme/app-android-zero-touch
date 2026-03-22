package com.example.zero_touch.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zero_touch.api.Card
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.api.SessionDetail
import com.example.zero_touch.api.SessionSummary
import com.example.zero_touch.api.ZeroTouchApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ZeroTouchUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val currentSession: SessionDetail? = null,
    val cards: List<Card> = emptyList(),
    val isUploading: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val uploadSuccess: String? = null
)

class ZeroTouchViewModel : ViewModel() {
    private val api = ZeroTouchApi()

    private val _uiState = MutableStateFlow(ZeroTouchUiState())
    val uiState: StateFlow<ZeroTouchUiState> = _uiState

    private var pollingJob: Job? = null

    fun loadSessions(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)
                val response = api.listSessions(deviceId = deviceId)
                _uiState.value = _uiState.value.copy(
                    sessions = response.sessions,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load sessions: ${e.message}"
                )
            }
        }
    }

    fun uploadAndProcess(context: Context, audioFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, error = null, uploadSuccess = null)
            try {
                val deviceId = DeviceIdProvider.getDeviceId(context)

                // Upload
                val uploadResult = api.uploadAudio(audioFile, deviceId)

                // Trigger transcription + card generation (auto_chain=true)
                api.transcribe(uploadResult.session_id, autoChain = true)

                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    uploadSuccess = uploadResult.session_id
                )

                // Start polling for this session
                startPolling(uploadResult.session_id)

                // Refresh sessions list
                loadSessions(context)

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploading = false,
                    error = "Upload failed: ${e.message}"
                )
            }
        }
    }

    fun loadSessionDetail(sessionId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val session = api.getSession(sessionId)
                val cards = api.parseCards(session.cards_result)
                _uiState.value = _uiState.value.copy(
                    currentSession = session,
                    cards = cards,
                    isLoading = false
                )

                // If not yet completed, start polling
                if (session.status !in listOf("completed", "failed")) {
                    startPolling(sessionId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load session: ${e.message}"
                )
            }
        }
    }

    private fun startPolling(sessionId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(5000) // 5 seconds
                try {
                    val session = api.getSession(sessionId)
                    val cards = api.parseCards(session.cards_result)
                    _uiState.value = _uiState.value.copy(
                        currentSession = session,
                        cards = cards
                    )
                    if (session.status in listOf("completed", "failed")) {
                        break
                    }
                } catch (e: Exception) {
                    // Silently retry
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearUploadSuccess() {
        _uiState.value = _uiState.value.copy(uploadSuccess = null)
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}

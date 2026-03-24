package com.example.zero_touch.audio.ambient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AmbientUiState(
    val status: String = "Listening",
    val level: Float = 0f,
    val speech: Boolean = false,
    val lastFilePath: String? = null,
    val lastDurationMs: Long? = null,
    val isRecording: Boolean = false,
    val recordingElapsedMs: Long = 0,
    val recordings: List<AmbientRecordingEntry> = emptyList(),
    val lastEvent: String? = null
)

data class AmbientRecordingEntry(
    val path: String,
    val durationMs: Long,
    val createdAt: Long,
    val sessionId: String? = null
)

object AmbientStatus {
    private val _state = MutableStateFlow(AmbientUiState())
    val state: StateFlow<AmbientUiState> = _state

    fun update(
        status: String? = null,
        level: Float? = null,
        speech: Boolean? = null,
        lastFilePath: String? = null,
        lastDurationMs: Long? = null,
        isRecording: Boolean? = null,
        recordingElapsedMs: Long? = null,
        recordings: List<AmbientRecordingEntry>? = null,
        lastEvent: String? = null
    ) {
        val current = _state.value
        _state.value = current.copy(
            status = status ?: current.status,
            level = level ?: current.level,
            speech = speech ?: current.speech,
            lastFilePath = lastFilePath ?: current.lastFilePath,
            lastDurationMs = lastDurationMs ?: current.lastDurationMs,
            isRecording = isRecording ?: current.isRecording,
            recordingElapsedMs = recordingElapsedMs ?: current.recordingElapsedMs,
            recordings = recordings ?: current.recordings,
            lastEvent = lastEvent ?: current.lastEvent
        )
    }
}

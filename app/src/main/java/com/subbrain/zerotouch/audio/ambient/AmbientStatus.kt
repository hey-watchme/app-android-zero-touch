package com.subbrain.zerotouch.audio.ambient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AmbientUiState(
    val status: String = "Listening",
    val ambientLevel: Float = 0f,
    val voiceLevel: Float = 0f,
    val speech: Boolean = false,
    val lastFilePath: String? = null,
    val lastDurationMs: Long? = null,
    val isRecording: Boolean = false,
    val recordingElapsedMs: Long = 0,
    val recordingHeartbeatAt: Long = 0,
    val recordings: List<AmbientRecordingEntry> = emptyList(),
    val lastEvent: String? = null,
    val eventSeq: Long = 0
)

data class AmbientRecordingEntry(
    val path: String,
    val durationMs: Long,
    val createdAt: Long,
    val sessionId: String? = null,
    val status: String = "pending",
    val errorMessage: String? = null
)

object AmbientStatus {
    private val _state = MutableStateFlow(AmbientUiState())
    val state: StateFlow<AmbientUiState> = _state

    fun update(
        status: String? = null,
        ambientLevel: Float? = null,
        voiceLevel: Float? = null,
        speech: Boolean? = null,
        lastFilePath: String? = null,
        lastDurationMs: Long? = null,
        isRecording: Boolean? = null,
        recordingElapsedMs: Long? = null,
        recordingHeartbeatAt: Long? = null,
        recordings: List<AmbientRecordingEntry>? = null,
        lastEvent: String? = null
    ) {
        val current = _state.value
        val nextEventSeq = if (lastEvent != null) current.eventSeq + 1 else current.eventSeq
        _state.value = current.copy(
            status = status ?: current.status,
            ambientLevel = ambientLevel ?: current.ambientLevel,
            voiceLevel = voiceLevel ?: current.voiceLevel,
            speech = speech ?: current.speech,
            lastFilePath = lastFilePath ?: current.lastFilePath,
            lastDurationMs = lastDurationMs ?: current.lastDurationMs,
            isRecording = isRecording ?: current.isRecording,
            recordingElapsedMs = recordingElapsedMs ?: current.recordingElapsedMs,
            recordingHeartbeatAt = recordingHeartbeatAt ?: current.recordingHeartbeatAt,
            recordings = recordings ?: current.recordings,
            lastEvent = lastEvent ?: current.lastEvent,
            eventSeq = nextEventSeq
        )
    }
}

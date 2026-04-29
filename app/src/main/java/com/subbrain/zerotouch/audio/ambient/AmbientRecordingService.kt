package com.subbrain.zerotouch.audio.ambient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.subbrain.zerotouch.api.DeviceIdProvider
import com.subbrain.zerotouch.api.SelectionPreferences
import com.subbrain.zerotouch.api.ZeroTouchApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class AmbientRecordingService : Service() {
    private data class PendingTranslationClause(
        val chunkIndex: Int,
        val text: String,
        val normalized: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AmbientRecorder? = null
    private var monitorRunning = false
    private var watchdogRestartCount = 0
    private var lastObservedRecordingState = false
    private var lastWatchdogRestartAt = 0L
    @Volatile private var currentLiveSessionId: String? = null
    @Volatile private var currentLiveShareToken: String? = null
    @Volatile private var nextLiveChunkIndex: Int = 0
    @Volatile private var translationCarryoverText: String = ""
    private val recentTranslatedClauseBacklog = ArrayDeque<String>()
    private val pendingTranslationClauses = ArrayDeque<PendingTranslationClause>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restorePersistedLiveTranscript()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        Log.d(
            TAG,
            "onStartCommand action=$action flags=$flags startId=$startId recorderActive=${recorder != null}"
        )
        when (action) {
            ACTION_START -> startAmbient(trigger = "action_start")
            ACTION_STOP -> stopAmbient(reason = "action_stop")
            else -> Log.d(TAG, "onStartCommand ignored unknownAction=$action")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAmbient(reason = "service_destroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAmbient(trigger: String) {
        if (recorder != null) {
            Log.d(TAG, "Ambient start ignored reason=already_running trigger=$trigger")
            return
        }
        AmbientStatus.update(
            status = "Listening",
            speech = false,
            isRecording = false,
            recordingElapsedMs = 0,
            recordingHeartbeatAt = SystemClock.elapsedRealtime()
        )
        startForeground(NOTIFICATION_ID, buildNotification("Listening"))
        val outputDir = File(filesDir, "ambient/${TimeUtils.todayString()}")
        val selectedSource = AmbientPreferences.getAmbientAudioSource(this)
        val selectedVadEngine = AmbientPreferences.getVadEngine(this)
        val resolvedVadEngine = when (selectedVadEngine) {
            AmbientPreferences.VAD_ENGINE_WEBRTC -> AmbientPreferences.VAD_ENGINE_SILERO
            else -> selectedVadEngine
        }
        val audioSource = when (selectedSource) {
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val hpfEnabled = AmbientPreferences.isHighPassFilterEnabled(this)
        val preprocessor = if (hpfEnabled) HighPassAudioPreprocessor() else NoOpAudioPreprocessor
        val detector = when (resolvedVadEngine) {
            AmbientPreferences.VAD_ENGINE_SILERO -> SileroVadDetector(applicationContext)
            else -> VadDetector()
        }
        if (selectedVadEngine == AmbientPreferences.VAD_ENGINE_WEBRTC) {
            Log.w(TAG, "WebRTC VAD is not implemented; falling back to $resolvedVadEngine")
        }
        Log.i(
            TAG,
            "Ambient start requested trigger=$trigger audioSourcePref=$selectedSource audioSourceResolved=${audioSourceName(audioSource)}($audioSource) hpfEnabled=$hpfEnabled vadEnginePref=$selectedVadEngine vadEngineResolved=$resolvedVadEngine detector=${detector.javaClass.simpleName} detectorConfig=${detector.debugConfig()} outputDir=${outputDir.absolutePath}"
        )
        recorder = AmbientRecorder(
            outputDir = outputDir,
            onSessionReady = { file, durationMs -> handleSessionReady(file, durationMs) },
            onRealtimeChunkReady = { file, durationMs -> handleRealtimeChunkReady(file, durationMs) },
            onStatusChanged = { status ->
                AmbientStatus.update(status = status)
                updateNotification(status)
                Log.d(TAG, "Recorder status changed status=$status trigger=$trigger")
            },
            onLevelChanged = { ambientLevel, voiceLevel, speech ->
                AmbientStatus.update(
                    ambientLevel = ambientLevel,
                    voiceLevel = voiceLevel,
                    speech = speech
                )
            },
            onRecordingState = { isRecording, elapsedMs ->
                AmbientStatus.update(
                    isRecording = isRecording,
                    recordingElapsedMs = elapsedMs,
                    recordingHeartbeatAt = SystemClock.elapsedRealtime()
                )
                if (lastObservedRecordingState != isRecording) {
                    Log.d(
                        TAG,
                        "Recorder recordingState changed isRecording=$isRecording elapsedMs=$elapsedMs trigger=$trigger"
                    )
                    lastObservedRecordingState = isRecording
                }
            },
            audioSource = audioSource,
            detector = detector,
            preprocessor = preprocessor
        ).also { it.start() }
        scope.launch {
            val api = ZeroTouchApi()
            ensureLiveSession(api)
        }
        startMonitor()
        Log.d(
            TAG,
            "Ambient service started trigger=$trigger audioSource=$selectedSource hpfEnabled=$hpfEnabled vadEngine=$selectedVadEngine detector=${detector.javaClass.simpleName}"
        )
    }

    private fun stopAmbient(reason: String) {
        Log.i(
            TAG,
            "Ambient stop requested reason=$reason recorderActive=${recorder != null} monitorRunning=$monitorRunning"
        )
        scope.launch {
            endLiveSession(reason = reason)
        }
        recorder?.stop()
        recorder = null
        monitorRunning = false
        lastObservedRecordingState = false
        lastWatchdogRestartAt = 0L
        AmbientStatus.update(
            status = "Stopped",
            ambientLevel = 0f,
            voiceLevel = 0f,
            speech = false,
            isRecording = false,
            recordingElapsedMs = 0,
            recordingHeartbeatAt = SystemClock.elapsedRealtime(),
            clearLiveSessionId = true,
            clearLiveShareToken = true
        )
        currentLiveSessionId = null
        currentLiveShareToken = null
        nextLiveChunkIndex = 0
        resetTranslationPipelineState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Ambient service stopped reason=$reason")
    }

    private fun handleSessionReady(file: File, durationMs: Long) {
        Log.i(
            TAG,
            "Session ready for upload file=${file.name} durationMs=$durationMs path=${file.absolutePath}"
        )
        val current = AmbientStatus.state.value
        val entry = AmbientRecordingEntry(
            localRecordingId = UUID.randomUUID().toString(),
            path = file.absolutePath,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis(),
            sessionId = null
        )
        val updated = listOf(entry) + current.recordings
        AmbientStatus.update(
            lastFilePath = file.absolutePath,
            lastDurationMs = durationMs,
            recordings = updated.take(MAX_RECORDINGS),
            lastEvent = "Recording completed"
        )
        val deviceId = DeviceIdProvider.getDeviceId(this)
        scope.launch {
            var uploadedSessionId: String? = null
            try {
                if (!ENABLE_LEGACY_BATCH_PIPELINE) {
                    val retained = AmbientStatus.state.value.recordings.map { item ->
                        if (item.path == file.absolutePath) {
                            item.copy(
                                status = "completed",
                                errorMessage = null
                            )
                        } else {
                            item
                        }
                    }
                    AmbientStatus.update(
                        recordings = retained.take(MAX_RECORDINGS),
                        lastEvent = "Live-only chunk completed"
                    )
                    runCatching { file.delete() }
                    return@launch
                }
                val api = ZeroTouchApi()
                val upload = api.uploadAudio(
                    file = file,
                    deviceId = deviceId,
                    localRecordingId = entry.localRecordingId
                )
                uploadedSessionId = upload.session_id
                val uploaded = AmbientStatus.state.value.recordings.map { item ->
                    if (item.path == file.absolutePath) {
                        item.copy(
                            sessionId = upload.session_id,
                            status = "uploaded",
                            errorMessage = null
                        )
                    } else {
                        item
                    }
                }
                AmbientStatus.update(
                    recordings = uploaded.take(MAX_RECORDINGS),
                    lastEvent = "Upload completed:${upload.session_id}"
                )
                val asrProvider = AmbientPreferences.getAsrProvider(this@AmbientRecordingService)
                api.transcribe(upload.session_id, autoChain = false, provider = asrProvider)
                val transcribing = AmbientStatus.state.value.recordings.map { item ->
                    if (item.path == file.absolutePath) {
                        item.copy(
                            sessionId = upload.session_id,
                            status = "transcribing",
                            errorMessage = null
                        )
                    } else {
                        item
                    }
                }
                AmbientStatus.update(
                    recordings = transcribing.take(MAX_RECORDINGS),
                    lastEvent = "Transcribe started:${upload.session_id}"
                )
                Log.d(
                    TAG,
                    "Uploaded session=${upload.session_id} durationMs=$durationMs provider=$asrProvider"
                )
            } catch (e: Exception) {
                val sessionId = uploadedSessionId
                if (sessionId == null) {
                    Log.e(TAG, "Upload failed: ${file.name} ${e.message}")
                } else {
                    Log.e(TAG, "Transcribe trigger failed: session=$sessionId ${e.message}")
                }
                val retained = AmbientStatus.state.value.recordings.map { item ->
                    if (item.path == file.absolutePath) {
                        item.copy(
                            sessionId = sessionId ?: item.sessionId,
                            status = "failed",
                            errorMessage = e.message
                        )
                    } else {
                        item
                    }
                }
                AmbientStatus.update(
                    recordings = retained.take(MAX_RECORDINGS),
                    lastEvent = if (sessionId == null) "Upload failed" else "Transcribe failed:$sessionId"
                )
            }
        }
    }

    private fun handleRealtimeChunkReady(file: File, durationMs: Long) {
        scope.launch {
            try {
                val api = ZeroTouchApi()
                val liveSessionId = ensureLiveSession(api)
                if (liveSessionId.isNullOrBlank()) {
                    AmbientStatus.update(lastEvent = "Realtime skipped:no_live_session")
                    return@launch
                }
                val chunkIndex = allocateLiveChunkIndex()
                val realtime = api.transcribeRealtimeChunk(
                    file = file,
                    liveSessionId = liveSessionId,
                    chunkIndex = chunkIndex
                )
                val text = realtime.text?.trim().orEmpty()
                val model = realtime.model?.trim()
                if (text.isNotBlank()) {
                    val history = mergeLiveTranscriptLines(
                        current = AmbientStatus.state.value.liveTranscriptHistory,
                        incoming = text
                    )
                    AmbientStatus.update(
                        liveAsrModel = model,
                        liveTranscriptLatest = text,
                        liveTranscriptHistory = history,
                        lastEvent = "Realtime updated:$chunkIndex"
                    )
                    persistLiveTranscriptState(history = history, model = model)
                    enqueueStableClausesForTranslation(
                        chunkIndex = chunkIndex,
                        incomingText = text
                    ).takeIf { it.isNotEmpty() }?.let { clauses ->
                        scope.launch {
                            processRealtimeTranslations(
                                api = api,
                                liveSessionId = liveSessionId,
                                clauses = clauses
                            )
                        }
                    }
                } else {
                    AmbientStatus.update(
                        liveAsrModel = model,
                        lastEvent = "Realtime empty:$chunkIndex"
                    )
                    persistLiveTranscriptState(
                        history = AmbientStatus.state.value.liveTranscriptHistory,
                        model = model
                    )
                }
                Log.d(
                    TAG,
                    "Realtime transcribe sent chunk=$chunkIndex durationMs=$durationMs textLength=${text.length}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Realtime chunk failed file=${file.name} error=${e.message}")
                AmbientStatus.update(lastEvent = "Realtime failed")
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    private suspend fun ensureLiveSession(api: ZeroTouchApi): String? {
        val existing = currentLiveSessionId
        if (!existing.isNullOrBlank()) {
            AmbientStatus.update(
                liveSessionId = existing,
                liveShareToken = currentLiveShareToken
            )
            return existing
        }
        return try {
            val deviceId = DeviceIdProvider.getDeviceId(this)
            val workspaceId = SelectionPreferences.getSelectedWorkspaceId(this)?.takeIf { it.isNotBlank() }
            val session = api.createLiveSession(
                deviceId = deviceId,
                workspaceId = workspaceId
            )
            currentLiveSessionId = session.id
            currentLiveShareToken = session.share_token
            nextLiveChunkIndex = 0
            AmbientStatus.update(
                liveSessionId = session.id,
                liveShareToken = session.share_token,
                lastEvent = "Live session started:${session.id}"
            )
            Log.i(
                TAG,
                "Live session started sessionId=${session.id} shareToken=${session.share_token} workspaceId=$workspaceId"
            )
            session.id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create live session: ${e.message}")
            null
        }
    }

    private suspend fun endLiveSession(reason: String) {
        val sessionId = currentLiveSessionId ?: return
        try {
            val api = ZeroTouchApi()
            api.endLiveSession(sessionId)
            Log.i(TAG, "Live session ended sessionId=$sessionId reason=$reason")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to end live session sessionId=$sessionId reason=$reason error=${e.message}")
        }
    }

    private fun allocateLiveChunkIndex(): Int = synchronized(this) {
        val current = nextLiveChunkIndex
        nextLiveChunkIndex += 1
        current
    }

    private fun mergeLiveTranscriptLines(current: List<String>, incoming: String): List<String> {
        val cleaned = incoming.trim()
        if (cleaned.isBlank()) return current
        if (cleaned.length <= 1) return current

        val updated = current.toMutableList()
        val last = updated.lastOrNull()
        if (last.isNullOrBlank()) {
            updated.add(cleaned)
            return updated.takeLast(MAX_LIVE_TRANSCRIPT_LINES)
        }

        val normalizedIncoming = normalizeTranscriptLine(cleaned)
        val normalizedLast = normalizeTranscriptLine(last)
        if (normalizedIncoming.isBlank() || normalizedLast.isBlank()) {
            updated.add(cleaned)
            return updated.takeLast(MAX_LIVE_TRANSCRIPT_LINES)
        }

        if (normalizedIncoming == normalizedLast) {
            return updated.takeLast(MAX_LIVE_TRANSCRIPT_LINES)
        }
        if (normalizedIncoming.startsWith(normalizedLast) || normalizedLast.startsWith(normalizedIncoming)) {
            updated[updated.lastIndex] = if (cleaned.length >= last.length) cleaned else last
            return updated.takeLast(MAX_LIVE_TRANSCRIPT_LINES)
        }

        updated.add(cleaned)
        return updated.takeLast(MAX_LIVE_TRANSCRIPT_LINES)
    }

    private suspend fun processRealtimeTranslations(
        api: ZeroTouchApi,
        liveSessionId: String,
        clauses: List<PendingTranslationClause>
    ) {
        for (clause in clauses) {
            try {
                val response = api.translateRealtime(
                    liveSessionId = liveSessionId,
                    chunkIndex = clause.chunkIndex,
                    text = clause.text
                )
                val translated = response.translated_text?.trim().orEmpty()
                if (translated.isBlank()) {
                    dequeuePendingTranslationClause(clause.normalized)
                    continue
                }
                dequeuePendingTranslationClause(clause.normalized)
                val translationHistory = mergeLiveTranslationLines(
                    current = AmbientStatus.state.value.liveTranslationHistory,
                    incoming = translated
                )
                AmbientStatus.update(
                    liveTranslationModel = response.model?.trim(),
                    liveTranslationLatest = translated,
                    liveTranslationHistory = translationHistory,
                    lastEvent = "Translation updated:${clause.chunkIndex}"
                )
                persistLiveTranslationState(
                    history = translationHistory,
                    model = response.model?.trim()
                )
            } catch (e: Exception) {
                dequeuePendingTranslationClause(clause.normalized)
                Log.w(
                    TAG,
                    "Realtime translation failed chunk=${clause.chunkIndex} error=${e.message}"
                )
            }
        }
    }

    private fun enqueueStableClausesForTranslation(
        chunkIndex: Int,
        incomingText: String
    ): List<PendingTranslationClause> = synchronized(this) {
        val stableClauses = extractStableClausesFromRealtime(incomingText)
        if (stableClauses.isEmpty()) return emptyList()

        val accepted = mutableListOf<PendingTranslationClause>()
        for (clause in stableClauses) {
            val normalized = normalizeTranslationClause(clause)
            if (normalized.isBlank()) continue
            if (normalized.length < MIN_TRANSLATION_CLAUSE_NORMALIZED_CHARS) continue
            if (recentTranslatedClauseBacklog.contains(normalized)) continue
            if (pendingTranslationClauses.any { it.normalized == normalized }) continue

            while (pendingTranslationClauses.size >= MAX_PENDING_TRANSLATION_CLAUSES) {
                pendingTranslationClauses.removeFirstOrNull()
            }
            while (recentTranslatedClauseBacklog.size >= MAX_TRANSLATION_CLAUSE_BACKLOG) {
                recentTranslatedClauseBacklog.removeFirstOrNull()
            }
            pendingTranslationClauses.addLast(
                PendingTranslationClause(
                    chunkIndex = chunkIndex,
                    text = clause,
                    normalized = normalized
                )
            )
            recentTranslatedClauseBacklog.addLast(normalized)
            accepted.add(
                PendingTranslationClause(
                    chunkIndex = chunkIndex,
                    text = clause,
                    normalized = normalized
                )
            )
        }
        accepted
    }

    private fun extractStableClausesFromRealtime(incomingText: String): List<String> = synchronized(this) {
        val cleanedIncoming = incomingText.trim()
        if (cleanedIncoming.isBlank()) return emptyList()

        val combined = buildString {
            if (translationCarryoverText.isNotBlank()) {
                append(translationCarryoverText.trim())
                append(' ')
            }
            append(cleanedIncoming)
        }.trim()
        if (combined.isBlank()) return emptyList()

        val clauses = mutableListOf<String>()
        val builder = StringBuilder()
        combined.forEach { ch ->
            builder.append(ch)
            if (!isClauseBoundary(ch)) return@forEach
            val clause = builder.toString().trim()
            if (clause.length >= MIN_TRANSLATION_CLAUSE_CHARS) {
                clauses.add(clause)
            }
            builder.clear()
        }

        translationCarryoverText = builder
            .toString()
            .trim()
            .takeLast(MAX_TRANSLATION_CARRYOVER_CHARS)

        if (clauses.isEmpty() && translationCarryoverText.length >= FORCE_FLUSH_CARRYOVER_CHARS) {
            clauses.add(translationCarryoverText)
            translationCarryoverText = ""
        }

        clauses
    }

    private fun isClauseBoundary(ch: Char): Boolean {
        return ch in CLAUSE_BOUNDARY_CHARS
    }

    private fun normalizeTranslationClause(value: String): String {
        return value
            .lowercase()
            .replace("\\s+".toRegex(), "")
            .replace("[、。！？!?・「」『』（）()\\-ー.,;:，；：\"'\\[\\]{}]".toRegex(), "")
    }

    private fun dequeuePendingTranslationClause(normalized: String) = synchronized(this) {
        val iterator = pendingTranslationClauses.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().normalized == normalized) {
                iterator.remove()
                break
            }
        }
    }

    private fun normalizeTranscriptLine(value: String): String {
        return value
            .lowercase()
            .replace("\\s+".toRegex(), "")
            .replace("[、。！？!?・「」『』（）()\\-ー]".toRegex(), "")
    }

    private fun persistLiveTranscriptState(history: List<String>, model: String?) {
        val limited = history.takeLast(MAX_LIVE_TRANSCRIPT_LINES)
        AmbientPreferences.setLiveTranscriptHistory(this, limited)
        AmbientPreferences.setLiveAsrModel(this, model)
    }

    private fun mergeLiveTranslationLines(current: List<String>, incoming: String): List<String> {
        val cleaned = incoming.trim()
        if (cleaned.isBlank()) return current
        if (cleaned.length <= 1) return current

        val updated = current.toMutableList()
        val last = updated.lastOrNull()
        if (last.isNullOrBlank()) {
            updated.add(cleaned)
            return updated.takeLast(MAX_LIVE_TRANSLATION_LINES)
        }

        val normalizedIncoming = normalizeTranscriptLine(cleaned)
        val normalizedLast = normalizeTranscriptLine(last)
        if (normalizedIncoming.isBlank() || normalizedLast.isBlank()) {
            updated.add(cleaned)
            return updated.takeLast(MAX_LIVE_TRANSLATION_LINES)
        }

        if (normalizedIncoming == normalizedLast) {
            return updated.takeLast(MAX_LIVE_TRANSLATION_LINES)
        }
        if (normalizedIncoming.startsWith(normalizedLast) || normalizedLast.startsWith(normalizedIncoming)) {
            updated[updated.lastIndex] = if (cleaned.length >= last.length) cleaned else last
            return updated.takeLast(MAX_LIVE_TRANSLATION_LINES)
        }

        updated.add(cleaned)
        return updated.takeLast(MAX_LIVE_TRANSLATION_LINES)
    }

    private fun persistLiveTranslationState(history: List<String>, model: String?) {
        val limited = history.takeLast(MAX_LIVE_TRANSLATION_LINES)
        AmbientPreferences.setLiveTranslationHistory(this, limited)
        AmbientPreferences.setLiveTranslationModel(this, model)
    }

    private fun restorePersistedLiveTranscript() {
        val history = AmbientPreferences.getLiveTranscriptHistory(this).takeLast(MAX_LIVE_TRANSCRIPT_LINES)
        val model = AmbientPreferences.getLiveAsrModel(this)
        val translationHistory = AmbientPreferences.getLiveTranslationHistory(this)
            .takeLast(MAX_LIVE_TRANSLATION_LINES)
        val translationModel = AmbientPreferences.getLiveTranslationModel(this)
        if (history.isEmpty() && model.isNullOrBlank() && translationHistory.isEmpty() && translationModel.isNullOrBlank()) {
            return
        }
        AmbientStatus.update(
            liveAsrModel = model,
            liveTranscriptLatest = history.lastOrNull(),
            liveTranscriptHistory = history,
            liveTranslationModel = translationModel,
            liveTranslationLatest = translationHistory.lastOrNull(),
            liveTranslationHistory = translationHistory
        )
    }

    private fun resetTranslationPipelineState() = synchronized(this) {
        translationCarryoverText = ""
        recentTranslatedClauseBacklog.clear()
        pendingTranslationClauses.clear()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val text = "Ambient mode: $status"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZeroTouch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZeroTouch Ambient",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startMonitor() {
        if (monitorRunning) {
            Log.d(TAG, "Watchdog monitor start ignored reason=already_running")
            return
        }
        monitorRunning = true
        Log.d(TAG, "Watchdog monitor started staleThresholdMs=$RECORDING_STALE_THRESHOLD_MS")
        scope.launch {
            while (monitorRunning) {
                delay(1000)
                val state = AmbientStatus.state.value
                if (!state.isRecording) continue
                val ageMs = SystemClock.elapsedRealtime() - state.recordingHeartbeatAt
                if (ageMs <= RECORDING_STALE_THRESHOLD_MS) continue
                val now = SystemClock.elapsedRealtime()
                if (recorder == null) {
                    Log.w(
                        TAG,
                        "Watchdog stale heartbeat but recorder=null; resetting state ageMs=$ageMs thresholdMs=$RECORDING_STALE_THRESHOLD_MS"
                    )
                    AmbientStatus.update(
                        status = "Listening",
                        isRecording = false,
                        recordingElapsedMs = 0,
                        recordingHeartbeatAt = now
                    )
                    continue
                }
                if (now - lastWatchdogRestartAt < WATCHDOG_RESTART_COOLDOWN_MS) {
                    Log.w(
                        TAG,
                        "Watchdog restart suppressed by cooldown ageMs=$ageMs cooldownMs=$WATCHDOG_RESTART_COOLDOWN_MS status=${state.status} speech=${state.speech} recordingElapsedMs=${state.recordingElapsedMs} lastEvent=${state.lastEvent}"
                    )
                    AmbientStatus.update(
                        status = "Listening",
                        isRecording = false,
                        recordingElapsedMs = 0,
                        recordingHeartbeatAt = now
                    )
                    continue
                }
                watchdogRestartCount++
                lastWatchdogRestartAt = now
                Log.w(
                    TAG,
                    "Watchdog restart triggered reason=recording_heartbeat_stale restartCount=$watchdogRestartCount ageMs=$ageMs thresholdMs=$RECORDING_STALE_THRESHOLD_MS status=${state.status} speech=${state.speech} recordingElapsedMs=${state.recordingElapsedMs} lastEvent=${state.lastEvent}"
                )
                recorder?.stop()
                recorder = null
                lastObservedRecordingState = false
                AmbientStatus.update(
                    status = "Listening",
                    isRecording = false,
                    recordingElapsedMs = 0,
                    recordingHeartbeatAt = SystemClock.elapsedRealtime()
                )
                startAmbient(trigger = "watchdog_stale_restart_$watchdogRestartCount")
                Log.w(TAG, "Watchdog restart completed restartCount=$watchdogRestartCount")
            }
        }
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "mic"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice_recognition"
            else -> "source_$source"
        }
    }

    companion object {
        private const val TAG = "AmbientService"
        private const val CHANNEL_ID = "zerotouch_ambient"
        private const val NOTIFICATION_ID = 7001
        private const val MAX_RECORDINGS = 50
        private const val RECORDING_STALE_THRESHOLD_MS = 4000L
        private const val WATCHDOG_RESTART_COOLDOWN_MS = 10_000L
        private const val ENABLE_LEGACY_BATCH_PIPELINE = false
        private const val MAX_LIVE_TRANSCRIPT_LINES = 50
        private const val MAX_LIVE_TRANSLATION_LINES = 50
        private const val MAX_TRANSLATION_CARRYOVER_CHARS = 400
        private const val FORCE_FLUSH_CARRYOVER_CHARS = 32
        private const val MAX_TRANSLATION_CLAUSE_BACKLOG = 80
        private const val MAX_PENDING_TRANSLATION_CLAUSES = 20
        private const val MIN_TRANSLATION_CLAUSE_CHARS = 5
        private const val MIN_TRANSLATION_CLAUSE_NORMALIZED_CHARS = 3
        private val CLAUSE_BOUNDARY_CHARS = setOf('。', '！', '？', '.', '!', '?', ';', '；')
        const val ACTION_START = "com.subbrain.zerotouch.AMBIENT_START"
        const val ACTION_STOP = "com.subbrain.zerotouch.AMBIENT_STOP"
    }
}

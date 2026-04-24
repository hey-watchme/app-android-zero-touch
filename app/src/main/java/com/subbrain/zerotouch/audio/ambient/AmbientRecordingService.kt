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
import com.subbrain.zerotouch.api.ZeroTouchApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class AmbientRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AmbientRecorder? = null
    private var monitorRunning = false
    private var watchdogRestartCount = 0
    private var lastObservedRecordingState = false
    private var lastWatchdogRestartAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        val audioSource = when (selectedSource) {
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val hpfEnabled = AmbientPreferences.isHighPassFilterEnabled(this)
        val preprocessor = if (hpfEnabled) HighPassAudioPreprocessor() else NoOpAudioPreprocessor
        val detector = when (selectedVadEngine) {
            AmbientPreferences.VAD_ENGINE_SILERO -> SileroVadDetector(applicationContext)
            AmbientPreferences.VAD_ENGINE_WEBRTC -> WebRtcVadDetector()
            else -> VadDetector()
        }
        Log.i(
            TAG,
            "Ambient start requested trigger=$trigger audioSourcePref=$selectedSource audioSourceResolved=${audioSourceName(audioSource)}($audioSource) hpfEnabled=$hpfEnabled vadEnginePref=$selectedVadEngine detector=${detector.javaClass.simpleName} detectorConfig=${detector.debugConfig()} outputDir=${outputDir.absolutePath}"
        )
        recorder = AmbientRecorder(
            outputDir = outputDir,
            onSessionReady = { file, durationMs -> handleSessionReady(file, durationMs) },
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
            recordingHeartbeatAt = SystemClock.elapsedRealtime()
        )
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
            try {
                val api = ZeroTouchApi()
                val upload = api.uploadAudio(file, deviceId)
                // Once upload succeeds, rely on backend sessions/topics for rendering state.
                // Keeping local entries indefinitely causes stale "processing" placeholders.
                val uploaded = AmbientStatus.state.value.recordings.filterNot { item ->
                    item.path == file.absolutePath
                }
                AmbientStatus.update(
                    recordings = uploaded,
                    lastEvent = "Upload completed"
                )
                val asrProvider = AmbientPreferences.getAsrProvider(this@AmbientRecordingService)
                api.transcribe(upload.session_id, autoChain = false, provider = asrProvider)
                Log.d(
                    TAG,
                    "Uploaded session=${upload.session_id} durationMs=$durationMs provider=$asrProvider"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${file.name} ${e.message}")
                val retained = AmbientStatus.state.value.recordings.filterNot { item ->
                    item.path == file.absolutePath
                }
                AmbientStatus.update(
                    recordings = retained,
                    lastEvent = "Upload failed"
                )
            }
        }
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
        const val ACTION_START = "com.subbrain.zerotouch.AMBIENT_START"
        const val ACTION_STOP = "com.subbrain.zerotouch.AMBIENT_STOP"
    }
}

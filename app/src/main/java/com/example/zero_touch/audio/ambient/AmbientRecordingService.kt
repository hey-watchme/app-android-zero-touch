package com.example.zero_touch.audio.ambient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.zero_touch.api.DeviceIdProvider
import com.example.zero_touch.api.ZeroTouchApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AmbientRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AmbientRecorder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAmbient()
            ACTION_STOP -> stopAmbient()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAmbient()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAmbient() {
        if (recorder != null) return
        startForeground(NOTIFICATION_ID, buildNotification("Listening"))
        val outputDir = File(filesDir, "ambient/${TimeUtils.todayString()}")
        val selectedSource = AmbientPreferences.getAmbientAudioSource(this)
        val audioSource = when (selectedSource) {
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val hpfEnabled = AmbientPreferences.isHighPassFilterEnabled(this)
        val preprocessor = if (hpfEnabled) HighPassAudioPreprocessor() else NoOpAudioPreprocessor
        recorder = AmbientRecorder(
            outputDir = outputDir,
            onSessionReady = { file, durationMs -> handleSessionReady(file, durationMs) },
            onStatusChanged = { status ->
                AmbientStatus.update(status = status)
                updateNotification(status)
            },
            onLevelChanged = { level, speech ->
                AmbientStatus.update(level = level, speech = speech)
            },
            onRecordingState = { isRecording, elapsedMs ->
                AmbientStatus.update(isRecording = isRecording, recordingElapsedMs = elapsedMs)
            },
            audioSource = audioSource,
            detector = VadDetector(),
            preprocessor = preprocessor
        ).also { it.start() }
        Log.d(
            TAG,
            "Ambient service started audioSource=$selectedSource hpfEnabled=$hpfEnabled detector=threshold"
        )
    }

    private fun stopAmbient() {
        recorder?.stop()
        recorder = null
        AmbientStatus.update(status = "Stopped", level = 0f, speech = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Ambient service stopped")
    }

    private fun handleSessionReady(file: File, durationMs: Long) {
        val current = AmbientStatus.state.value
        val entry = AmbientRecordingEntry(
            path = file.absolutePath,
            durationMs = durationMs,
            createdAt = System.currentTimeMillis()
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
                val asrProvider = AmbientPreferences.getAsrProvider(this@AmbientRecordingService)
                api.transcribe(upload.session_id, autoChain = false, provider = asrProvider)
                Log.d(TAG, "Uploaded session=${upload.session_id} durationMs=$durationMs")
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${file.name} ${e.message}")
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

    companion object {
        private const val TAG = "AmbientService"
        private const val CHANNEL_ID = "zerotouch_ambient"
        private const val NOTIFICATION_ID = 7001
        private const val MAX_RECORDINGS = 50
        const val ACTION_START = "com.example.zero_touch.AMBIENT_START"
        const val ACTION_STOP = "com.example.zero_touch.AMBIENT_STOP"
    }
}

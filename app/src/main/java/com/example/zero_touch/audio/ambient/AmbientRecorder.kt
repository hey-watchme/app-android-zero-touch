package com.example.zero_touch.audio.ambient

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import kotlin.math.ceil

class AmbientRecorder(
    private val outputDir: File,
    private val onSessionReady: (File, Long) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onLevelChanged: (Float, Boolean) -> Unit,
    private val onRecordingState: (Boolean, Long) -> Unit
) {
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameMs = 20
    private val frameSamples = (sampleRate * frameMs) / 1000

    private val startDebounceMs = 200
    private val displaySpeechHoldMs = 3_000
    private val silenceStopMs = 5_000
    private val maxSessionMs = 5 * 60_000
    private val minSessionMs = 3_000
    private val preRollSeconds = 2
    private val bitRate = 64_000

    private val vad = VadDetector()
    private val ringBuffer = PcmRingBuffer(sampleRate, preRollSeconds)
    private val frameBuffer = ShortArray(frameSamples)

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var running = false

    private var recordingActive = false
    private var recordedSamples: Long = 0
    private var silenceFrames = 0
    private var speechScore = 0
    private var sessionStartElapsed = 0L
    private var lastLevelUpdate = 0L
    private var lastSpeechAt = 0L
    private var lastRecordingTick = 0L
    private var currentFile: File? = null
    private var writer: Mp4AudioWriter? = null
    private val startDebounceFrames = (startDebounceMs / frameMs).coerceAtLeast(1)

    fun start() {
        if (running) return
        running = true
        onStatusChanged("Listening")
        startAudioRecord()
        worker = Thread { captureLoop() }.apply { start() }
    }

    fun stop() {
        running = false
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // ignore
        }
        worker?.join(1000)
        worker = null
        stopRecording(finalize = true)
        audioRecord?.release()
        audioRecord = null
        onStatusChanged("Stopped")
    }

    private fun startAudioRecord() {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuffer, frameSamples * 4)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        ).apply { startRecording() }
    }

    private fun captureLoop() {
        val record = audioRecord ?: return
        while (running) {
            val read = record.read(frameBuffer, 0, frameSamples, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue
            ringBuffer.write(frameBuffer, read)
            val result = vad.analyze(frameBuffer, read)
            val speech = result.isSpeech

            val now = SystemClock.elapsedRealtime()
            if (speech) {
                speechScore = (speechScore + 2).coerceAtMost(startDebounceFrames)
                lastSpeechAt = now
            } else {
                speechScore = (speechScore - 1).coerceAtLeast(0)
            }

            if (now - lastLevelUpdate >= 200) {
                val normalized = (result.ratio / 4.0f).coerceIn(0f, 1f)
                val displaySpeech = now - lastSpeechAt <= displaySpeechHoldMs
                onLevelChanged(normalized, displaySpeech)
                lastLevelUpdate = now
            }

            if (!recordingActive) {
                if (speechScore >= startDebounceFrames) {
                    startRecording()
                }
                continue
            }

            writer?.writePcm(frameBuffer, read)
            recordedSamples += read

            if (speech) {
                silenceFrames = 0
            } else {
                silenceFrames++
            }

            val silenceMs = silenceFrames * frameMs
            val elapsedMs = SystemClock.elapsedRealtime() - sessionStartElapsed

            if (silenceMs >= silenceStopMs || elapsedMs >= maxSessionMs) {
                stopRecording(finalize = true)
            }
            if (now - lastRecordingTick >= 500) {
                onRecordingState(true, elapsedMs)
                lastRecordingTick = now
            }
        }
    }

    private fun startRecording() {
        if (recordingActive) return
        outputDir.mkdirs()
        val file = File(outputDir, "ambient_${System.currentTimeMillis()}_${UUID.randomUUID()}.m4a")
        currentFile = file
        try {
            writer = Mp4AudioWriter(sampleRate, 1, bitRate).also { it.start(file) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder: ${e.message}")
            currentFile = null
            writer = null
            onStatusChanged("Error")
            return
        }

        val preRoll = ringBuffer.snapshot()
        if (preRoll.isNotEmpty()) {
            writer?.writePcm(preRoll, preRoll.size)
            recordedSamples = preRoll.size.toLong()
        } else {
            recordedSamples = 0
        }
        silenceFrames = 0
        speechScore = 0
        sessionStartElapsed = SystemClock.elapsedRealtime()
        lastRecordingTick = sessionStartElapsed
        recordingActive = true
        onStatusChanged("Recording")
        onRecordingState(true, 0)
    }

    private fun stopRecording(finalize: Boolean) {
        if (!recordingActive) return
        recordingActive = false
        writer?.stop()
        writer = null

        val durationMs = ceil(recordedSamples.toDouble() * 1000 / sampleRate).toLong()
        val file = currentFile
        currentFile = null

        if (file == null) return

        if (durationMs < minSessionMs) {
            Log.d(TAG, "Discard short session: ${file.name} (${durationMs}ms)")
            file.delete()
        } else if (finalize) {
            Log.d(TAG, "Session ready: ${file.name} (${durationMs}ms)")
            onSessionReady(file, durationMs)
        }
        recordedSamples = 0
        onStatusChanged("Listening")
        onRecordingState(false, durationMs)
    }

    companion object {
        private const val TAG = "AmbientRecorder"
    }
}

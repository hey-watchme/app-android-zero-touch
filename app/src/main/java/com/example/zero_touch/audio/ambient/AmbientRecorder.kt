package com.example.zero_touch.audio.ambient

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

class AmbientRecorder(
    private val outputDir: File,
    private val onSessionReady: (File, Long) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onLevelChanged: (Float, Float, Boolean) -> Unit,
    private val onRecordingState: (Boolean, Long) -> Unit,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val detector: VoiceActivityDetector = VadDetector(),
    private val preprocessor: AudioPreprocessor = NoOpAudioPreprocessor
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
    private var lastVadLogAt = 0L
    private var vadLogFrames = 0
    private var vadLogSpeechFrames = 0
    private var vadLogUnsupportedFrames = 0
    private var vadLogRmsSum = 0f
    private var vadLogRatioSum = 0f
    private var consecutiveReadErrors = 0

    fun start() {
        if (running) return
        running = true
        onStatusChanged("Listening")
        detector.reset()
        preprocessor.reset()
        Log.d(
            TAG,
            "Ambient recorder start source=$audioSource detector=${detector.javaClass.simpleName} preprocessor=${preprocessor.javaClass.simpleName}"
        )
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
        stopRecording(finalize = true, reason = "service_stop")
        detector.close()
        audioRecord?.release()
        audioRecord = null
        onStatusChanged("Stopped")
        onRecordingState(false, 0)
    }

    private fun startAudioRecord() {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuffer, frameSamples * 4)
        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        ).apply { startRecording() }
    }

    private fun captureLoop() {
        val record = audioRecord ?: return
        try {
            while (running) {
                val read = record.read(frameBuffer, 0, frameSamples, AudioRecord.READ_BLOCKING)
                val now = SystemClock.elapsedRealtime()
                if (read <= 0) {
                    consecutiveReadErrors++
                    if (recordingActive) {
                        val elapsedMs = now - sessionStartElapsed
                        if (now - lastRecordingTick >= 500) {
                            onRecordingState(true, elapsedMs)
                            lastRecordingTick = now
                        }
                    }
                    if (consecutiveReadErrors >= MAX_READ_ERRORS) {
                        Log.w(TAG, "AudioRecord read errors=$consecutiveReadErrors; stopping recording")
                        if (recordingActive) {
                            stopRecording(finalize = true, reason = "read_error")
                        }
                        consecutiveReadErrors = 0
                    }
                    continue
                }
                consecutiveReadErrors = 0
                ringBuffer.write(frameBuffer, read)
                val processed = preprocessor.process(frameBuffer, read)
                val result = detector.analyze(processed, processed.size)
                val speech = result.isSpeech

                updateVadDebugStats(result, now)
                if (speech) {
                    speechScore = (speechScore + 2).coerceAtMost(startDebounceFrames)
                    lastSpeechAt = now
                } else {
                    speechScore = (speechScore - 1).coerceAtLeast(0)
                }

                if (now - lastLevelUpdate >= 200) {
                    val displaySpeech = now - lastSpeechAt <= displaySpeechHoldMs
                    val ambientLevel = computeAmbientLevel(result)
                    val voiceLevel = computeVoiceLevel(result, displaySpeech)
                    onLevelChanged(ambientLevel, voiceLevel, displaySpeech)
                    lastLevelUpdate = now
                }

                if (!recordingActive) {
                    if (speechScore >= startDebounceFrames) {
                        startRecording(result)
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
                val elapsedMs = now - sessionStartElapsed

                if (silenceMs >= silenceStopMs || elapsedMs >= maxSessionMs) {
                    val reason = if (silenceMs >= silenceStopMs) "silence_timeout" else "max_session_timeout"
                    stopRecording(finalize = true, reason = reason)
                }
                if (now - lastRecordingTick >= 500) {
                    onRecordingState(true, elapsedMs)
                    lastRecordingTick = now
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop failed: ${e.message}", e)
            if (recordingActive) {
                stopRecording(finalize = true, reason = "capture_exception")
            }
            onStatusChanged("Error")
            onRecordingState(false, 0)
        }
    }

    private fun startRecording(trigger: VadResult) {
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
        Log.d(
            TAG,
            "Recording started reason=speech_confirmed score=$speechScore rms=${trigger.rms} ratio=${trigger.ratio} zcr=${trigger.zeroCrossingRate} engine=${trigger.engine}"
        )
    }

    private fun stopRecording(finalize: Boolean, reason: String) {
        if (!recordingActive) return
        recordingActive = false
        writer?.stop()
        writer = null

        val durationMs = ceil(recordedSamples.toDouble() * 1000 / sampleRate).toLong()
        val file = currentFile
        currentFile = null

        if (file == null) return

        if (durationMs < minSessionMs) {
            Log.d(TAG, "Discard short session reason=min_duration file=${file.name} durationMs=$durationMs")
            file.delete()
        } else if (finalize) {
            Log.d(TAG, "Session ready: ${file.name} (${durationMs}ms) stopReason=$reason")
            onSessionReady(file, durationMs)
        }
        recordedSamples = 0
        onStatusChanged("Listening")
        onRecordingState(false, durationMs)
    }

    private fun updateVadDebugStats(result: VadResult, now: Long) {
        vadLogFrames++
        if (result.isSpeech) vadLogSpeechFrames++
        if (!result.supported) vadLogUnsupportedFrames++
        vadLogRmsSum += result.rms
        vadLogRatioSum += result.ratio

        if (lastVadLogAt == 0L) {
            lastVadLogAt = now
            return
        }
        if (now - lastVadLogAt < VAD_LOG_INTERVAL_MS) return

        val frames = vadLogFrames.coerceAtLeast(1)
        val avgRms = vadLogRmsSum / frames
        val avgRatio = vadLogRatioSum / frames
        val speechRate = vadLogSpeechFrames.toFloat() / frames
        val unsupportedRate = vadLogUnsupportedFrames.toFloat() / frames
        Log.d(
            TAG,
            "VAD stats engine=${result.engine} frames=$frames speechRate=${"%.2f".format(speechRate)} unsupportedRate=${"%.2f".format(unsupportedRate)} avgRms=${"%.1f".format(avgRms)} avgRatio=${"%.2f".format(avgRatio)} recording=$recordingActive"
        )

        vadLogFrames = 0
        vadLogSpeechFrames = 0
        vadLogUnsupportedFrames = 0
        vadLogRmsSum = 0f
        vadLogRatioSum = 0f
        lastVadLogAt = now
    }

    private fun computeAmbientLevel(result: VadResult): Float {
        val rmsLevel = (result.rms / AMBIENT_RMS_NORMALIZER).coerceIn(0f, 1f)
        val ratioLevel = (result.ratio / 4f).coerceIn(0f, 1f)
        return (rmsLevel * 0.65f + ratioLevel * 0.35f).coerceIn(0f, 1f)
    }

    private fun computeVoiceLevel(result: VadResult, displaySpeech: Boolean): Float {
        return when {
            result.isSpeech -> max(result.confidence.coerceIn(0f, 1f), MIN_VOICE_ACTIVE_LEVEL)
            displaySpeech -> VOICE_TRAIL_LEVEL
            else -> 0f
        }
    }

    companion object {
        private const val TAG = "AmbientRecorder"
        private const val MAX_READ_ERRORS = 10
        private const val VAD_LOG_INTERVAL_MS = 2_000L
        private const val AMBIENT_RMS_NORMALIZER = 2_500f
        private const val MIN_VOICE_ACTIVE_LEVEL = 0.2f
        private const val VOICE_TRAIL_LEVEL = 0.1f
    }
}

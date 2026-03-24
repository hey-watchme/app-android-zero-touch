package com.example.zero_touch.audio.ambient

import android.content.Context
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlin.math.sqrt

class SileroVadDetector(
    private val context: Context
) : VoiceActivityDetector {
    private val sampleRate = SampleRate.SAMPLE_RATE_16K
    private val frameSize = FrameSize.FRAME_SIZE_512
    private val mode = Mode.NORMAL
    private val speechDurationMs = 120
    private val silenceDurationMs = 300
    private val targetFrameSamples = frameSize.value

    private var vad: VadSilero? = null
    private var initFailure: String? = null

    private var bufferedSamples = 0
    private var pendingSamples = ShortArray(targetFrameSamples * 2)

    init {
        initializeEngine()
    }

    override fun analyze(samples: ShortArray, length: Int): VadResult {
        val safeLength = length.coerceIn(0, samples.size)
        if (safeLength > 0) {
            append(samples, safeLength)
        }

        val engine = vad
        if (engine == null) {
            return unsupportedResult(
                reason = initFailure ?: "Silero VAD is unavailable",
                samples = samples,
                length = safeLength
            )
        }

        var result: VadResult? = null
        var hasNewInference = false
        while (bufferedSamples >= targetFrameSamples) {
            val frame = takeFrame()
            val speech = runCatching {
                engine.isSpeech(frame)
            }.getOrElse { error ->
                initFailure = "Silero inference failed: ${error.message ?: error.javaClass.simpleName}"
                releaseEngine()
                return unsupportedResult(
                    reason = initFailure ?: "Silero inference failed",
                    samples = frame,
                    length = frame.size
                )
            }
            result = buildSpeechResult(frame, speech)
            hasNewInference = true
        }

        return if (hasNewInference) {
            result ?: bufferingResult(samples, safeLength)
        } else {
            bufferingResult(samples, safeLength)
        }
    }

    override fun reset() {
        bufferedSamples = 0
        pendingSamples = ShortArray(targetFrameSamples * 2)
        initFailure = null
        close()
        initializeEngine()
    }

    override fun close() {
        releaseEngine()
    }

    private fun initializeEngine() {
        runCatching {
            VadSilero(
                context = context.applicationContext,
                sampleRate = sampleRate,
                frameSize = frameSize,
                mode = mode,
                speechDurationMs = speechDurationMs,
                silenceDurationMs = silenceDurationMs
            )
        }.onSuccess { created ->
            vad = created
            initFailure = null
        }.onFailure { error ->
            initFailure = "Silero VAD init failed: ${error.message ?: error.javaClass.simpleName}"
            vad = null
        }
    }

    private fun releaseEngine() {
        runCatching { vad?.close() }
        vad = null
    }

    private fun append(samples: ShortArray, length: Int) {
        ensureCapacity(bufferedSamples + length)
        System.arraycopy(samples, 0, pendingSamples, bufferedSamples, length)
        bufferedSamples += length
    }

    private fun takeFrame(): ShortArray {
        val frame = ShortArray(targetFrameSamples)
        System.arraycopy(pendingSamples, 0, frame, 0, targetFrameSamples)

        val remaining = bufferedSamples - targetFrameSamples
        if (remaining > 0) {
            System.arraycopy(
                pendingSamples,
                targetFrameSamples,
                pendingSamples,
                0,
                remaining
            )
        }
        bufferedSamples = remaining
        return frame
    }

    private fun ensureCapacity(requiredSamples: Int) {
        if (requiredSamples <= pendingSamples.size) return

        var newSize = pendingSamples.size
        while (newSize < requiredSamples) {
            newSize *= 2
        }
        pendingSamples = pendingSamples.copyOf(newSize)
    }

    private fun buildSpeechResult(frame: ShortArray, speech: Boolean): VadResult {
        val rms = computeRms(frame, frame.size)
        val zcr = computeZeroCrossingRate(frame, frame.size)
        val confidence = if (speech) 0.92f else 0.08f
        return VadResult(
            isSpeech = speech,
            rms = rms,
            ratio = (confidence * 4f).coerceIn(0f, 4f),
            noiseFloorRms = 0f,
            zeroCrossingRate = zcr,
            confidence = confidence,
            engine = "silero",
            supported = true
        )
    }

    private fun bufferingResult(samples: ShortArray, length: Int): VadResult {
        val rms = computeRms(samples, length)
        val zcr = computeZeroCrossingRate(samples, length)
        return VadResult(
            isSpeech = false,
            rms = rms,
            ratio = (rms / 2_500f).coerceIn(0f, 4f),
            noiseFloorRms = 0f,
            zeroCrossingRate = zcr,
            confidence = 0f,
            engine = "silero",
            supported = true,
            reason = "buffering $bufferedSamples/$targetFrameSamples samples"
        )
    }

    private fun unsupportedResult(reason: String, samples: ShortArray, length: Int): VadResult {
        val rms = computeRms(samples, length)
        val zcr = computeZeroCrossingRate(samples, length)
        return VadResult(
            isSpeech = false,
            rms = rms,
            ratio = (rms / 2_500f).coerceIn(0f, 4f),
            noiseFloorRms = 0f,
            zeroCrossingRate = zcr,
            confidence = 0f,
            engine = "silero",
            supported = false,
            reason = reason
        )
    }

    private fun computeRms(samples: ShortArray, length: Int): Float {
        val count = length.coerceIn(0, samples.size)
        if (count <= 0) return 0f

        var sumSquares = 0.0
        for (index in 0 until count) {
            val value = samples[index].toDouble()
            sumSquares += value * value
        }
        return sqrt(sumSquares / count).toFloat()
    }

    private fun computeZeroCrossingRate(samples: ShortArray, length: Int): Float {
        val count = length.coerceIn(0, samples.size)
        if (count <= 1) return 0f

        var crossings = 0
        var previous = samples[0]
        for (index in 1 until count) {
            val current = samples[index]
            if ((previous >= 0 && current < 0) || (previous < 0 && current >= 0)) {
                crossings++
            }
            previous = current
        }
        return crossings.toFloat() / (count - 1)
    }
}

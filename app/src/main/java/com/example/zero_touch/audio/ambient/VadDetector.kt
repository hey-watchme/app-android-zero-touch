package com.example.zero_touch.audio.ambient

import kotlin.math.abs
import kotlin.math.max

interface VoiceActivityDetector {
    fun analyze(samples: ShortArray, length: Int): VadResult

    fun reset() = Unit

    fun close() = Unit

    fun debugConfig(): String = "engine=${javaClass.simpleName}"
}

data class VadResult(
    val isSpeech: Boolean,
    val rms: Float,
    val ratio: Float,
    val noiseFloorRms: Float = 0f,
    val zeroCrossingRate: Float = 0f,
    val confidence: Float = 0f,
    val engine: String = "threshold",
    val supported: Boolean = true,
    val reason: String? = null
)

open class ThresholdVadDetector(
    private val minSpeechRatio: Float = 2.4f,
    private val minHoldRatio: Float = 1.4f,
    private val minSpeechRms: Float = 700f,
    private val minHoldRms: Float = 450f,
    private val minSpeechZcr: Float = 0.02f,
    private val maxSpeechZcr: Float = 0.25f,
    private val minHoldZcr: Float = 0.01f,
    private val maxHoldZcr: Float = 0.30f,
    private val noiseFloorDecay: Float = 0.997f
) : VoiceActivityDetector {
    private var noiseFloorRms = 200.0f
    private var inSpeech = false

    override fun analyze(samples: ShortArray, length: Int): VadResult {
        val rms = computeRms(samples, length).coerceAtLeast(1.0f)
        noiseFloorRms = noiseFloorDecay * noiseFloorRms + (1 - noiseFloorDecay) * rms
        val ratio = rms / max(noiseFloorRms, 1.0f)
        val zcr = computeZcr(samples, length)

        val wasSpeech = inSpeech
        val gatePassed = if (wasSpeech) {
            ratio >= minHoldRatio && rms >= minHoldRms && zcr in minHoldZcr..maxHoldZcr
        } else {
            ratio >= minSpeechRatio && rms >= minSpeechRms && zcr in minSpeechZcr..maxSpeechZcr
        }
        inSpeech = gatePassed

        val ratioThreshold = if (wasSpeech) minHoldRatio else minSpeechRatio
        val confidence = (ratio / ratioThreshold).coerceIn(0f, 1f)
        val reason = buildDecisionReason(wasSpeech, gatePassed, ratio, rms, zcr)

        return VadResult(
            isSpeech = inSpeech,
            rms = rms,
            ratio = ratio,
            noiseFloorRms = noiseFloorRms,
            zeroCrossingRate = zcr,
            confidence = confidence,
            engine = "threshold",
            supported = true,
            reason = reason
        )
    }

    private fun computeRms(samples: ShortArray, length: Int): Float {
        var sum = 0.0f
        val count = length.coerceAtMost(samples.size)
        for (i in 0 until count) {
            val v = abs(samples[i].toInt()).toFloat()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / count.coerceAtLeast(1))
    }

    private fun computeZcr(samples: ShortArray, length: Int): Float {
        val count = length.coerceAtMost(samples.size)
        if (count <= 1) return 0f
        var crossings = 0
        var prev = samples[0]
        for (i in 1 until count) {
            val current = samples[i]
            if ((prev >= 0 && current < 0) || (prev < 0 && current >= 0)) {
                crossings++
            }
            prev = current
        }
        return crossings.toFloat() / (count - 1)
    }

    override fun reset() {
        noiseFloorRms = 200.0f
        inSpeech = false
    }

    override fun debugConfig(): String {
        return "engine=threshold minSpeechRatio=$minSpeechRatio minHoldRatio=$minHoldRatio minSpeechRms=$minSpeechRms minHoldRms=$minHoldRms minSpeechZcr=$minSpeechZcr maxSpeechZcr=$maxSpeechZcr minHoldZcr=$minHoldZcr maxHoldZcr=$maxHoldZcr noiseFloorDecay=$noiseFloorDecay"
    }

    private fun buildDecisionReason(
        wasSpeech: Boolean,
        gatePassed: Boolean,
        ratio: Float,
        rms: Float,
        zcr: Float
    ): String {
        val gateLabel = if (wasSpeech) "hold" else "start"
        if (gatePassed) return "$gateLabel:ok"

        val failed = if (wasSpeech) {
            failedThresholds(
                ratio = ratio,
                rms = rms,
                zcr = zcr,
                minRatio = minHoldRatio,
                minRms = minHoldRms,
                minZcr = minHoldZcr,
                maxZcr = maxHoldZcr
            )
        } else {
            failedThresholds(
                ratio = ratio,
                rms = rms,
                zcr = zcr,
                minRatio = minSpeechRatio,
                minRms = minSpeechRms,
                minZcr = minSpeechZcr,
                maxZcr = maxSpeechZcr
            )
        }
        return "$gateLabel:fail(${failed.joinToString("+")})"
    }

    private fun failedThresholds(
        ratio: Float,
        rms: Float,
        zcr: Float,
        minRatio: Float,
        minRms: Float,
        minZcr: Float,
        maxZcr: Float
    ): List<String> {
        val failed = mutableListOf<String>()
        if (ratio < minRatio) failed += "ratio"
        if (rms < minRms) failed += "rms"
        if (zcr < minZcr || zcr > maxZcr) failed += "zcr"
        if (failed.isEmpty()) failed += "unknown"
        return failed
    }
}

class VadDetector(
    minSpeechRatio: Float = 2.4f,
    minHoldRatio: Float = 1.4f,
    minSpeechRms: Float = 700f,
    minHoldRms: Float = 450f,
    minSpeechZcr: Float = 0.02f,
    maxSpeechZcr: Float = 0.25f,
    minHoldZcr: Float = 0.01f,
    maxHoldZcr: Float = 0.30f,
    noiseFloorDecay: Float = 0.997f
) : ThresholdVadDetector(
    minSpeechRatio = minSpeechRatio,
    minHoldRatio = minHoldRatio,
    minSpeechRms = minSpeechRms,
    minHoldRms = minHoldRms,
    minSpeechZcr = minSpeechZcr,
    maxSpeechZcr = maxSpeechZcr,
    minHoldZcr = minHoldZcr,
    maxHoldZcr = maxHoldZcr,
    noiseFloorDecay = noiseFloorDecay
)

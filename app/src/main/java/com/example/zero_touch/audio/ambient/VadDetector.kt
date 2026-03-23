package com.example.zero_touch.audio.ambient

import kotlin.math.abs
import kotlin.math.max

data class VadResult(
    val isSpeech: Boolean,
    val rms: Float,
    val ratio: Float
)

class VadDetector(
    private val minSpeechRatio: Float = 2.4f,
    private val minHoldRatio: Float = 1.4f,
    private val minSpeechRms: Float = 700f,
    private val minHoldRms: Float = 450f,
    private val minSpeechZcr: Float = 0.02f,
    private val maxSpeechZcr: Float = 0.25f,
    private val minHoldZcr: Float = 0.01f,
    private val maxHoldZcr: Float = 0.30f,
    private val noiseFloorDecay: Float = 0.997f
) {
    private var noiseFloorRms = 200.0f
    private var inSpeech = false

    fun analyze(samples: ShortArray, length: Int): VadResult {
        val rms = computeRms(samples, length).coerceAtLeast(1.0f)
        noiseFloorRms = noiseFloorDecay * noiseFloorRms + (1 - noiseFloorDecay) * rms
        val ratio = rms / max(noiseFloorRms, 1.0f)
        val zcr = computeZcr(samples, length)
        inSpeech = if (inSpeech) {
            ratio >= minHoldRatio && rms >= minHoldRms && zcr in minHoldZcr..maxHoldZcr
        } else {
            ratio >= minSpeechRatio && rms >= minSpeechRms && zcr in minSpeechZcr..maxSpeechZcr
        }
        return VadResult(inSpeech, rms, ratio)
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
}

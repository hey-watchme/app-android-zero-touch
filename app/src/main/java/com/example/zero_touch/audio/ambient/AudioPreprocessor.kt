package com.example.zero_touch.audio.ambient

interface AudioPreprocessor {
    fun process(samples: ShortArray, length: Int): ShortArray

    fun reset() = Unit
}

object NoOpAudioPreprocessor : AudioPreprocessor {
    override fun process(samples: ShortArray, length: Int): ShortArray {
        val count = length.coerceIn(0, samples.size)
        return samples.copyOf(count)
    }
}

class HighPassAudioPreprocessor(
    private val alpha: Float = 0.97f
) : AudioPreprocessor {
    private var previousInput = 0f
    private var previousOutput = 0f

    override fun process(samples: ShortArray, length: Int): ShortArray {
        val count = length.coerceIn(0, samples.size)
        val output = ShortArray(count)

        for (i in 0 until count) {
            val input = samples[i].toFloat()
            val filtered = alpha * (previousOutput + input - previousInput)
            output[i] = filtered
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
            previousInput = input
            previousOutput = filtered
        }

        return output
    }

    override fun reset() {
        previousInput = 0f
        previousOutput = 0f
    }
}

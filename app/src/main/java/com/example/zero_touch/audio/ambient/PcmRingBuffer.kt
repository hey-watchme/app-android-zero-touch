package com.example.zero_touch.audio.ambient

class PcmRingBuffer(
    sampleRate: Int,
    seconds: Int
) {
    private val capacitySamples = (sampleRate * seconds).coerceAtLeast(1)
    private val buffer = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var filled = false

    fun write(samples: ShortArray, length: Int) {
        val count = length.coerceAtMost(samples.size)
        for (i in 0 until count) {
            buffer[writeIndex] = samples[i]
            writeIndex++
            if (writeIndex >= capacitySamples) {
                writeIndex = 0
                filled = true
            }
        }
    }

    fun snapshot(): ShortArray {
        val size = if (filled) capacitySamples else writeIndex
        val output = ShortArray(size)
        if (!filled) {
            System.arraycopy(buffer, 0, output, 0, size)
            return output
        }

        val tail = capacitySamples - writeIndex
        System.arraycopy(buffer, writeIndex, output, 0, tail)
        System.arraycopy(buffer, 0, output, tail, writeIndex)
        return output
    }

    fun reset() {
        writeIndex = 0
        filled = false
    }
}

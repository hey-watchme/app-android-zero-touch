package com.subbrain.zerotouch.audio.ambient

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class Mp4AudioWriter(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitRate: Int
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamplesWritten: Long = 0

    fun start(outputFile: File) {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun writePcm(samples: ShortArray, length: Int) {
        val encoder = codec ?: return
        var offsetSamples = 0
        val count = length.coerceAtMost(samples.size)
        while (offsetSamples < count) {
            val inputIndex = encoder.dequeueInputBuffer(0)
            if (inputIndex < 0) {
                drain(false)
                continue
            }
            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            val maxSamples = inputBuffer.capacity() / 2
            val toWrite = minOf(maxSamples, count - offsetSamples)
            writeShortsLittleEndian(inputBuffer, samples, offsetSamples, toWrite)
            val bytes = toWrite * 2
            val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
            encoder.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)
            totalSamplesWritten += toWrite
            offsetSamples += toWrite
            drain(false)
        }
    }

    fun stop() {
        val encoder = codec ?: return
        val currentMuxer = muxer
        try {
            val eosIndex = encoder.dequeueInputBuffer(10_000)
            if (eosIndex >= 0) {
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                encoder.queueInputBuffer(eosIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            codec = null

            currentMuxer?.let {
                if (muxerStarted) {
                    runCatching { it.stop() }
                }
                runCatching { it.release() }
            }
            muxer = null
            muxerStarted = false
            trackIndex = -1
        }
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val muxer = muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val outBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun writeShortsLittleEndian(
        buffer: ByteBuffer,
        samples: ShortArray,
        offset: Int,
        length: Int
    ) {
        for (i in 0 until length) {
            val value = samples[offset + i].toInt()
            buffer.put((value and 0xFF).toByte())
            buffer.put(((value shr 8) and 0xFF).toByte())
        }
    }

    companion object {
        private const val MIME_TYPE = "audio/mp4a-latm"
    }
}

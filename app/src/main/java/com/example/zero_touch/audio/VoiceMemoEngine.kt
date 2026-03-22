package com.example.zero_touch.audio

import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

class VoiceMemoEngine {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    fun startRecording(outputFile: File) {
        stopPlayback()

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val newRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        recorder = newRecorder
    }

    fun stopRecording() {
        val current = recorder ?: return
        recorder = null
        try {
            current.stop()
        } finally {
            current.release()
        }
    }

    fun startPlayback(inputFile: File, onComplete: () -> Unit) {
        stopRecording()
        stopPlayback()

        val newPlayer = MediaPlayer().apply {
            setDataSource(inputFile.absolutePath)
            setOnCompletionListener {
                onComplete()
                stopPlayback()
            }
            prepare()
            start()
        }

        player = newPlayer
    }

    fun stopPlayback() {
        val current = player ?: return
        player = null
        try {
            current.stop()
        } catch (_: IllegalStateException) {
            // ignore
        } finally {
            current.release()
        }
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }
}


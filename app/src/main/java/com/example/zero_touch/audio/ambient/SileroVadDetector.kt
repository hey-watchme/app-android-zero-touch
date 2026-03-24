package com.example.zero_touch.audio.ambient

class SileroVadDetector : VoiceActivityDetector {
    override fun analyze(samples: ShortArray, length: Int): VadResult {
        return VadResult(
            isSpeech = false,
            rms = 0f,
            ratio = 0f,
            engine = "silero",
            supported = false,
            reason = "Silero VAD is not implemented yet"
        )
    }
}

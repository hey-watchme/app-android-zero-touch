package com.subbrain.zerotouch.audio.ambient

class WebRtcVadDetector : VoiceActivityDetector {
    override fun analyze(samples: ShortArray, length: Int): VadResult {
        return VadResult(
            isSpeech = false,
            rms = 0f,
            ratio = 0f,
            engine = "webrtc",
            supported = false,
            reason = "WebRTC VAD is not implemented yet"
        )
    }

    override fun debugConfig(): String = "engine=webrtc supported=false reason=not_implemented"
}

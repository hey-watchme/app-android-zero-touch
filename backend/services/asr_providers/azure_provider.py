import os
import threading
import time
from pathlib import Path
from typing import Any, BinaryIO, Dict

from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential
import logging

logger = logging.getLogger(__name__)


class AzureASRService:
    """Azure Speech Service ASR for compressed audio such as ZeroTouch m4a uploads."""

    LANGUAGE_TO_LOCALE = {
        "ja": "ja-JP",
        "en": "en-US",
    }

    COMPRESSED_CONTAINER_BY_EXTENSION = {
        ".mp3": "MP3",
        ".flac": "FLAC",
        ".ogg": "OGG_OPUS",
        ".opus": "OGG_OPUS",
        ".mp4": "ANY",
        ".m4a": "ANY",
    }

    def __init__(self, model: str = "ja-JP", language: str = "ja"):
        self.speech_key = os.getenv("AZURE_SPEECH_KEY")
        self.service_region = os.getenv("AZURE_SERVICE_REGION")
        if not self.speech_key or not self.service_region:
            raise ValueError("AZURE_SPEECH_KEY and AZURE_SERVICE_REGION environment variables must be set")

        self.model = model or self.LANGUAGE_TO_LOCALE.get(language, "ja-JP")
        self.language = language
        logger.info(
            "Azure Speech Service initialized (locale=%s, language=%s, region=%s)",
            self.model,
            self.language,
            self.service_region,
        )

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        retry=retry_if_exception_type(Exception),
    )
    async def transcribe_audio(
        self,
        audio_file: BinaryIO,
        filename: str,
    ) -> Dict[str, Any]:
        try:
            import azure.cognitiveservices.speech as speechsdk
        except ImportError as exc:
            raise RuntimeError(
                "azure-cognitiveservices-speech is not installed. Add the dependency before using provider=azure."
            ) from exc

        start_time = time.time()
        audio_file.seek(0)
        audio_bytes = audio_file.read()

        if not audio_bytes:
            return _empty_result(processing_time=0.0, model=self.model, language=self.language)

        speech_config = speechsdk.SpeechConfig(
            subscription=self.speech_key,
            region=self.service_region,
        )
        speech_config.speech_recognition_language = self.model

        try:
            speech_config.set_property(
                speechsdk.PropertyId.SpeechServiceConnection_RecoMode,
                "DICTATION",
            )
            speech_config.set_property(
                speechsdk.PropertyId.SpeechServiceResponse_RequestDetailedResultTrueFalse,
                "true",
            )
            speech_config.set_property(
                speechsdk.PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,
                "5000",
            )
            speech_config.set_property(
                speechsdk.PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs,
                "15000",
            )
        except Exception as exc:
            logger.warning("Azure Speech advanced config partially skipped: %s", exc)

        stream_format = self._build_stream_format(speechsdk, filename)
        push_stream = speechsdk.audio.PushAudioInputStream(stream_format=stream_format)
        push_stream.write(audio_bytes)
        push_stream.close()

        audio_config = speechsdk.audio.AudioConfig(stream=push_stream)
        recognizer = speechsdk.SpeechRecognizer(
            speech_config=speech_config,
            audio_config=audio_config,
        )

        transcripts: list[str] = []
        recognition_errors: list[dict[str, str]] = []
        done = threading.Event()

        def handle_recognized(evt):
            result = evt.result
            if result.reason == speechsdk.ResultReason.RecognizedSpeech:
                text = (result.text or "").strip()
                if text:
                    transcripts.append(text)
            elif result.reason == speechsdk.ResultReason.NoMatch:
                recognition_errors.append({"type": "NoMatch", "detail": "No speech recognized"})

        def handle_canceled(evt):
            details = evt.cancellation_details
            recognition_errors.append(
                {
                    "type": "Canceled",
                    "reason": str(details.reason),
                    "error_details": details.error_details or "",
                }
            )
            done.set()

        def handle_session_stopped(_evt):
            done.set()

        recognizer.recognized.connect(handle_recognized)
        recognizer.canceled.connect(handle_canceled)
        recognizer.session_stopped.connect(handle_session_stopped)

        try:
            recognizer.start_continuous_recognition()
            if not done.wait(timeout=300):
                recognition_errors.append(
                    {"type": "Timeout", "detail": "Azure Speech recognition timed out after 300 seconds"}
                )
        finally:
            recognizer.stop_continuous_recognition()

        processing_time = time.time() - start_time

        if transcripts:
            transcript = " ".join(part for part in transcripts if part).strip()
            if transcript:
                return {
                    "transcription": transcript,
                    "processing_time": round(processing_time, 2),
                    "confidence": _estimate_confidence(transcript),
                    "word_count": len(transcript.split()),
                    "utterances": [],
                    "paragraphs": [],
                    "speaker_count": 0,
                    "no_speech_detected": False,
                    "model": f"azure/{self.model}",
                    "provider": "azure",
                    "language": self.language,
                    "estimated_duration": None,
                }

        if recognition_errors:
            if all(err.get("type") == "NoMatch" for err in recognition_errors):
                return _empty_result(processing_time=processing_time, model=self.model, language=self.language)

            summary = "; ".join(
                filter(
                    None,
                    [
                        f"{err.get('type')}: {err.get('reason') or err.get('detail') or err.get('error_details')}"
                        for err in recognition_errors
                    ],
                )
            )
            raise RuntimeError(f"Azure Speech recognition failed: {summary}")

        return _empty_result(processing_time=processing_time, model=self.model, language=self.language)

    def _build_stream_format(self, speechsdk, filename: str):
        ext = Path(filename).suffix.lower()
        container_name = self.COMPRESSED_CONTAINER_BY_EXTENSION.get(ext, "ANY")
        container_format = getattr(speechsdk.AudioStreamContainerFormat, container_name)
        return speechsdk.audio.AudioStreamFormat(compressed_stream_format=container_format)


def _estimate_confidence(transcript: str) -> float:
    text_length = len(transcript)
    if text_length > 80:
        return 0.95
    if text_length > 30:
        return 0.9
    if text_length > 10:
        return 0.82
    return 0.7


def _empty_result(processing_time: float, model: str, language: str) -> Dict[str, Any]:
    return {
        "transcription": "",
        "processing_time": round(processing_time, 2),
        "confidence": 0.0,
        "word_count": 0,
        "utterances": [],
        "paragraphs": [],
        "speaker_count": 0,
        "no_speech_detected": True,
        "model": f"azure/{model}",
        "provider": "azure",
        "language": language,
        "estimated_duration": None,
    }

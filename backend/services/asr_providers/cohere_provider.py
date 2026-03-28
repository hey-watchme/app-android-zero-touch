import os
import time
from typing import BinaryIO, Dict, Any

import requests
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
import logging

logger = logging.getLogger(__name__)


class CohereASRService:
    """Cohere Transcribe via the v2 audio transcriptions endpoint."""

    API_URL = "https://api.cohere.com/v2/audio/transcriptions"

    def __init__(self, model: str = "cohere-transcribe-03-2026", language: str = "ja"):
        api_key = os.getenv("COHERE_API_KEY")
        if not api_key:
            raise ValueError("COHERE_API_KEY environment variable not set")

        self.api_key = api_key
        self.model = model
        self.language = language
        logger.info(f"Cohere API initialized (model={model}, language={language})")

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        retry=retry_if_exception_type(Exception)
    )
    async def transcribe_audio(
        self,
        audio_file: BinaryIO,
        filename: str
    ) -> Dict[str, Any]:
        """
        Transcribe audio with Cohere.

        Official limitations: no timestamps and no speaker diarization.
        """
        try:
            start_time = time.time()
            audio_file.seek(0)
            audio_bytes = audio_file.read()

            response = requests.post(
                self.API_URL,
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Accept": "application/json",
                },
                data={
                    "model": self.model,
                    "language": self.language,
                },
                files={
                    "file": (
                        os.path.basename(filename) or "audio.m4a",
                        audio_bytes,
                        "application/octet-stream",
                    )
                },
                timeout=120,
            )

            processing_time = time.time() - start_time

            if response.status_code >= 400:
                detail = _extract_error_detail(response)
                raise ValueError(
                    f"Cohere transcription request failed ({response.status_code}): {detail}"
                )

            payload = response.json()
            transcript = (payload.get("text") or "").strip()
            if not transcript:
                return _empty_result(processing_time, self.model, self.language)

            return {
                "transcription": transcript,
                "processing_time": round(processing_time, 2),
                "confidence": 0.0,
                "word_count": len(transcript.split()),
                "utterances": [],
                "paragraphs": [],
                "speaker_count": 0,
                "no_speech_detected": False,
                "model": f"cohere/{self.model}",
                "provider": "cohere",
                "language": self.language,
                "estimated_duration": None,
            }

        except Exception as e:
            logger.error(f"Cohere API error: {str(e)}")
            raise


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
        "model": f"cohere/{model}",
        "provider": "cohere",
        "language": language,
        "estimated_duration": None,
    }


def _extract_error_detail(response: requests.Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        return response.text.strip() or response.reason

    if isinstance(payload, dict):
        for key in ("message", "error", "detail"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return str(payload)

    return str(payload)

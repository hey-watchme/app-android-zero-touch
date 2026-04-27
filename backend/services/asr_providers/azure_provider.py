import os
import time
from typing import Any, BinaryIO, Dict, Optional

import requests
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential
import logging

logger = logging.getLogger(__name__)


class AzureASRService:
    """Azure Speech batch transcription (REST) for ZeroTouch."""

    LANGUAGE_TO_LOCALE = {
        "ja": "ja-JP",
        "en": "en-US",
    }

    def __init__(self, model: str = "ja-JP", language: str = "ja"):
        self.speech_key = os.getenv("AZURE_SPEECH_KEY")
        self.service_region = os.getenv("AZURE_SERVICE_REGION")
        if not self.speech_key or not self.service_region:
            raise ValueError("AZURE_SPEECH_KEY and AZURE_SERVICE_REGION environment variables must be set")

        self.api_version = os.getenv("AZURE_SPEECH_API_VERSION", "2025-10-15").strip()
        self.model = model or self.LANGUAGE_TO_LOCALE.get(language, "ja-JP")
        self.language = language

        self.diarization_enabled = os.getenv("AZURE_BATCH_ENABLE_DIARIZATION", "true").strip().lower() == "true"
        self.max_speakers = int(os.getenv("AZURE_BATCH_MAX_SPEAKERS", "4"))
        self.poll_seconds = max(1, int(os.getenv("AZURE_BATCH_POLL_SECONDS", "3")))
        self.timeout_seconds = max(30, int(os.getenv("AZURE_BATCH_TIMEOUT_SECONDS", "600")))
        self.ttl_hours = max(1, int(os.getenv("AZURE_BATCH_TTL_HOURS", "24")))

        logger.info(
            "Azure batch transcription initialized (locale=%s, api_version=%s, region=%s)",
            self.model,
            self.api_version,
            self.service_region,
        )

    async def transcribe_audio(
        self,
        audio_file: BinaryIO,
        filename: str,
    ) -> Dict[str, Any]:
        raise RuntimeError(
            "Azure batch transcription requires a public content URL. Use transcribe_audio_from_url."
        )

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        retry=retry_if_exception_type(Exception),
    )
    async def transcribe_audio_from_url(
        self,
        source_url: str,
        filename: str,
    ) -> Dict[str, Any]:
        start_time = time.time()
        if not source_url:
            raise ValueError("Azure batch transcription requires a public content URL")

        submit_url = (
            f"https://{self.service_region}.api.cognitive.microsoft.com"
            f"/speechtotext/transcriptions:submit?api-version={self.api_version}"
        )
        headers = {
            "Ocp-Apim-Subscription-Key": self.speech_key,
            "Content-Type": "application/json",
        }

        payload: Dict[str, Any] = {
            "displayName": f"zerotouch-{os.path.basename(filename) or 'audio'}",
            "locale": self._resolve_locale(),
            "contentUrls": [source_url],
            "properties": {
                "wordLevelTimestampsEnabled": True,
                "displayFormWordLevelTimestampsEnabled": True,
                "punctuationMode": "DictatedAndAutomatic",
                "profanityFilterMode": "Masked",
                "timeToLiveHours": self.ttl_hours,
            },
        }
        if self.diarization_enabled:
            payload["properties"]["diarization"] = {
                "enabled": True,
                "maxSpeakers": max(2, self.max_speakers),
            }

        response = requests.post(submit_url, json=payload, headers=headers, timeout=30)
        if response.status_code >= 400:
            raise RuntimeError(_extract_error(response, "Azure batch submit failed"))

        response_data = response.json()
        transcription_url = (
            response_data.get("self")
            or response.headers.get("Location")
            or response_data.get("links", {}).get("self")
        )
        if not transcription_url:
            raise RuntimeError("Azure batch submit did not return transcription URL")

        status = None
        deadline = time.time() + self.timeout_seconds
        transcription_data = None
        while time.time() < deadline:
            status_response = requests.get(transcription_url, headers=headers, timeout=30)
            if status_response.status_code >= 400:
                raise RuntimeError(_extract_error(status_response, "Azure batch status failed"))
            transcription_data = status_response.json()
            status = (transcription_data.get("status") or "").lower()
            if status in {"succeeded", "failed"}:
                break
            time.sleep(self.poll_seconds)

        if status != "succeeded":
            detail = transcription_data.get("status") if transcription_data else "unknown"
            raise RuntimeError(f"Azure batch transcription failed or timed out (status={detail})")

        files_url = (
            transcription_data.get("links", {}).get("files")
            or f"{transcription_url.rstrip('/')}/files?api-version={self.api_version}"
        )
        files_response = requests.get(files_url, headers=headers, timeout=30)
        if files_response.status_code >= 400:
            raise RuntimeError(_extract_error(files_response, "Azure batch files fetch failed"))

        files_payload = files_response.json()
        transcription_file_url = _select_transcription_file_url(files_payload)
        if not transcription_file_url:
            raise RuntimeError("Azure batch transcription output file not found")

        transcript_response = requests.get(transcription_file_url, timeout=30)
        if transcript_response.status_code >= 400:
            raise RuntimeError(_extract_error(transcript_response, "Azure batch transcription download failed"))

        transcript_payload = transcript_response.json()
        transcript_text, utterances, speaker_count = _parse_transcription_payload(transcript_payload)

        processing_time = time.time() - start_time

        if not transcript_text:
            return _empty_result(processing_time, self.model, self.language)

        return {
            "transcription": transcript_text,
            "processing_time": round(processing_time, 2),
            "confidence": _estimate_confidence(transcript_text),
            "word_count": len(transcript_text.split()),
            "utterances": utterances,
            "paragraphs": [],
            "speaker_count": speaker_count,
            "no_speech_detected": False,
            "model": f"azure-batch/{self.model}",
            "provider": "azure",
            "language": self.language,
            "estimated_duration": None,
        }

    def _resolve_locale(self) -> str:
        if "-" in self.model:
            return self.model
        return self.LANGUAGE_TO_LOCALE.get(self.language, "ja-JP")


def _select_transcription_file_url(payload: Dict[str, Any]) -> Optional[str]:
    values = payload.get("values") or payload.get("files") or []
    for item in values:
        kind = (item.get("kind") or "").lower()
        if kind == "transcription":
            links = item.get("links") or {}
            return links.get("contentUrl") or item.get("contentUrl")
    return None


def _parse_transcription_payload(payload: Dict[str, Any]):
    utterances = []
    speaker_set = set()

    # Build utterances from recognizedPhrases in chronological order.
    # combinedRecognizedPhrases is intentionally skipped: when diarization is
    # enabled Azure returns one entry per speaker, so joining them produces
    # out-of-order text. recognizedPhrases sorted by offsetInTicks is the
    # canonical source for both transcript and utterances.
    phrases = payload.get("recognizedPhrases") or []
    phrases_sorted = sorted(phrases, key=lambda p: p.get("offsetInTicks", 0))

    for phrase in phrases_sorted:
        display = phrase.get("display")
        if not display and phrase.get("nBest"):
            display = phrase["nBest"][0].get("display")
        display = (display or "").strip()
        if not display:
            continue

        speaker = phrase.get("speaker")
        speaker_value = None
        if speaker is not None:
            try:
                speaker_value = int(speaker)
            except (TypeError, ValueError):
                speaker_value = None
        if speaker_value is not None:
            speaker_set.add(speaker_value)

        start = _ticks_to_seconds(phrase.get("offsetInTicks"))
        end = None
        duration = _ticks_to_seconds(phrase.get("durationInTicks"))
        if start is not None and duration is not None:
            end = start + duration

        utterances.append(
            {
                "start": start or 0.0,
                "end": end or 0.0,
                "speaker": speaker_value,
                "text": display,
            }
        )

    transcript_text = " ".join(u["text"] for u in utterances).strip()

    # Last-resort fallback: use combinedRecognizedPhrases only when
    # recognizedPhrases returned nothing and diarization is off (single entry).
    if not transcript_text:
        combined = payload.get("combinedRecognizedPhrases") or []
        if len(combined) == 1:
            transcript_text = (combined[0].get("display") or "").strip()

    return transcript_text, utterances, len(speaker_set)


def _ticks_to_seconds(value) -> Optional[float]:
    if value is None:
        return None
    try:
        return float(value) / 10_000_000.0
    except (TypeError, ValueError):
        return None


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
        "model": f"azure-batch/{model}",
        "provider": "azure",
        "language": language,
        "estimated_duration": None,
    }


def _extract_error(response: requests.Response, prefix: str) -> str:
    try:
        payload = response.json()
    except ValueError:
        return f"{prefix}: {response.status_code} {response.text.strip()}"
    if isinstance(payload, dict):
        for key in ("message", "error", "detail"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return f"{prefix}: {value.strip()}"
        return f"{prefix}: {payload}"
    return f"{prefix}: {payload}"

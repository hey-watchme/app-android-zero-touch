import os
import time
from typing import BinaryIO, Dict, Any
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
import logging

logger = logging.getLogger(__name__)


class DeepgramASRService:
    """Deepgram ASR Service using deepgram-sdk v3.x"""

    def __init__(self, model: str = "nova-3"):
        api_key = os.getenv("DEEPGRAM_API_KEY")
        if not api_key:
            raise ValueError("DEEPGRAM_API_KEY environment variable not set")
        from deepgram import DeepgramClient

        self.client = DeepgramClient(api_key=api_key)
        self.model = model
        logger.info(f"Deepgram API initialized (model={model})")

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
        """Transcribe audio file using Deepgram prerecorded API."""
        try:
            from deepgram import PrerecordedOptions

            start_time = time.time()
            audio_file.seek(0)
            audio_data = audio_file.read()

            options = PrerecordedOptions(
                model=self.model,
                language="ja",
                punctuate=True,
                diarize=True,
                smart_format=True,
                utterances=True,
            )

            response = self.client.listen.rest.v("1").transcribe_file(
                source={"buffer": audio_data},
                options=options
            )

            processing_time = time.time() - start_time

            if not response or not getattr(response, "results", None):
                return _empty_result(processing_time, self.model)

            results = response.results
            channels = getattr(results, "channels", []) or []
            if not channels or not getattr(channels[0], "alternatives", None):
                return _empty_result(processing_time, self.model)

            alternative = channels[0].alternatives[0]
            transcript = (alternative.transcript or "").strip()
            if not transcript:
                return _empty_result(processing_time, self.model)

            confidence = getattr(alternative, "confidence", 0.0) or 0.0
            word_count = len(transcript.split())

            utterances = []
            speaker_set = set()
            for utt in getattr(results, "utterances", []) or []:
                utterances.append({
                    "start": getattr(utt, "start", 0.0),
                    "end": getattr(utt, "end", 0.0),
                    "speaker": getattr(utt, "speaker", None),
                    "text": getattr(utt, "transcript", "")
                })
                speaker = getattr(utt, "speaker", None)
                if speaker is not None:
                    speaker_set.add(speaker)

            duration = 0.0
            metadata = getattr(results, "metadata", None)
            if metadata and hasattr(metadata, "duration"):
                duration = metadata.duration or 0.0

            return {
                "transcription": transcript,
                "processing_time": round(processing_time, 2),
                "confidence": round(confidence, 2),
                "word_count": word_count,
                "utterances": utterances,
                "paragraphs": [],
                "speaker_count": len(speaker_set),
                "no_speech_detected": False,
                "model": f"deepgram/{self.model}",
                "provider": "deepgram",
                "estimated_duration": round(duration, 2) if duration > 0 else None,
            }

        except Exception as e:
            logger.error(f"Deepgram API error: {str(e)}")
            raise


def _empty_result(processing_time: float, model: str) -> Dict[str, Any]:
    return {
        "transcription": "",
        "processing_time": round(processing_time, 2),
        "confidence": 0.0,
        "word_count": 0,
        "utterances": [],
        "paragraphs": [],
        "speaker_count": 0,
        "no_speech_detected": True,
        "model": f"deepgram/{model}",
        "provider": "deepgram",
        "estimated_duration": None,
    }

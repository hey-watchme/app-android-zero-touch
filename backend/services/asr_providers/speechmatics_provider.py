import os
import time
from typing import BinaryIO, Dict, Any
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
import logging

logger = logging.getLogger(__name__)


class SpeechmaticsASRService:
    """Speechmatics ASR Service using speechmatics-batch SDK"""

    def __init__(self, model: str = "batch"):
        api_key = os.getenv("SPEECHMATICS_API_KEY")
        if not api_key:
            raise ValueError("SPEECHMATICS_API_KEY environment variable not set")
        self.api_key = api_key
        self.model = model
        logger.info("Speechmatics API initialized")

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
        """Transcribe audio file using Speechmatics Batch API"""
        try:
            from speechmatics.batch import AsyncClient, TranscriptionConfig

            start_time = time.time()
            audio_file.seek(0)

            client = AsyncClient(api_key=self.api_key)

            config = TranscriptionConfig(
                language="ja",
                diarization="speaker",
                enable_entities=True,
                speaker_diarization_config={
                    "speaker_sensitivity": 0.5,
                    "prefer_current_speaker": False
                }
            )

            job = await client.submit_job(audio_file, transcription_config=config)
            result = await client.wait_for_completion(job.id)
            await client.close()

            processing_time = time.time() - start_time

            transcript_text = result.transcript_text if hasattr(result, 'transcript_text') else ""

            if not transcript_text:
                return {
                    "transcription": "",
                    "processing_time": round(processing_time, 2),
                    "confidence": 0.0,
                    "word_count": 0,
                    "utterances": [],
                    "paragraphs": [],
                    "speaker_count": 0,
                    "no_speech_detected": True,
                    "model": f"speechmatics/{self.model}",
                    "provider": "speechmatics",
                }

            utterances = []
            speaker_set = set()

            if hasattr(result, 'results'):
                for item in result.results:
                    if hasattr(item, 'alternatives') and item.alternatives:
                        alt = item.alternatives[0]
                        if hasattr(alt, 'speaker') and alt.speaker:
                            speaker_set.add(alt.speaker)

            return {
                "transcription": transcript_text,
                "processing_time": round(processing_time, 2),
                "confidence": 0.95,
                "word_count": len(transcript_text),
                "utterances": utterances,
                "paragraphs": [],
                "speaker_count": len(speaker_set),
                "no_speech_detected": False,
                "model": f"speechmatics/{self.model}",
                "provider": "speechmatics",
            }

        except Exception as e:
            logger.error(f"Speechmatics API error: {str(e)}")
            raise

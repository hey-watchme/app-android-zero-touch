import os
import time
from typing import BinaryIO, Dict, Any
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
import logging

logger = logging.getLogger(__name__)


class SpeechmaticsASRService:
    """Speechmatics ASR Service using speechmatics-batch SDK"""

    def __init__(self, model: str = "batch", language: str = "ja"):
        api_key = os.getenv("SPEECHMATICS_API_KEY")
        if not api_key:
            raise ValueError("SPEECHMATICS_API_KEY environment variable not set")
        self.api_key = api_key
        self.model = model
        self.language = language
        logger.info(f"Speechmatics API initialized (model={model}, language={language})")

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
                language=self.language,
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
                    "language": self.language,
                }

            utterances = []
            speaker_set = set()

            if hasattr(result, 'results'):
                current_speaker = None
                current_words = []
                current_start = None
                current_end = None

                def flush_utterance():
                    nonlocal current_speaker, current_words, current_start, current_end
                    if current_speaker is None or not current_words:
                        current_words = []
                        current_start = None
                        current_end = None
                        return
                    utterances.append({
                        "start": current_start or 0.0,
                        "end": current_end or current_start or 0.0,
                        "speaker": current_speaker,
                        "text": " ".join(current_words).strip()
                    })
                    current_words = []
                    current_start = None
                    current_end = None

                for item in result.results:
                    if not hasattr(item, 'alternatives') or not item.alternatives:
                        continue
                    alt = item.alternatives[0]
                    speaker = getattr(alt, 'speaker', None)
                    content = getattr(alt, 'content', None) or getattr(alt, 'lexical', None)
                    item_type = getattr(item, 'type', None) or ""

                    if speaker is not None:
                        speaker_set.add(speaker)

                    if not content:
                        continue

                    if item_type == "punctuation":
                        if current_words:
                            current_words[-1] = f"{current_words[-1]}{content}"
                        continue

                    start_time = getattr(item, 'start_time', None)
                    end_time = getattr(item, 'end_time', None)
                    try:
                        start_value = float(start_time) if start_time is not None else None
                    except (TypeError, ValueError):
                        start_value = None
                    try:
                        end_value = float(end_time) if end_time is not None else None
                    except (TypeError, ValueError):
                        end_value = None

                    if current_speaker is None:
                        current_speaker = speaker
                        current_start = start_value
                    elif speaker is not None and speaker != current_speaker:
                        flush_utterance()
                        current_speaker = speaker
                        current_start = start_value

                    current_words.append(content)
                    if end_value is not None:
                        current_end = end_value

                flush_utterance()

                return {
                    "transcription": transcript_text,
                    "processing_time": round(processing_time, 2),
                    "confidence": 0.95,
                    "word_count": len(transcript_text.split()),
                    "utterances": utterances,
                    "paragraphs": [],
                    "speaker_count": len(speaker_set),
                    "no_speech_detected": False,
                "model": f"speechmatics/{self.model}",
                "provider": "speechmatics",
                "language": self.language,
            }

        except Exception as e:
            logger.error(f"Speechmatics API error: {str(e)}")
            raise

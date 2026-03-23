import os
from typing import Optional, Tuple

from services.asr_providers.speechmatics_provider import SpeechmaticsASRService
from services.asr_providers.deepgram_provider import DeepgramASRService

DEFAULT_PROVIDER = os.getenv("ASR_PROVIDER", "speechmatics").lower()
DEFAULT_MODELS = {
    "speechmatics": "batch",
    "deepgram": "nova-3",
}


def resolve_asr_config(provider: Optional[str], model: Optional[str]) -> Tuple[str, str]:
    selected_provider = (provider or DEFAULT_PROVIDER).lower()
    if selected_provider not in DEFAULT_MODELS:
        raise ValueError(f"Unsupported ASR provider: {selected_provider}")

    selected_model = model or os.getenv("ASR_MODEL") or DEFAULT_MODELS[selected_provider]
    return selected_provider, selected_model


def get_asr_service(provider: Optional[str], model: Optional[str] = None):
    selected_provider, selected_model = resolve_asr_config(provider, model)

    if selected_provider == "speechmatics":
        return SpeechmaticsASRService(model=selected_model), selected_provider, selected_model
    if selected_provider == "deepgram":
        return DeepgramASRService(model=selected_model), selected_provider, selected_model

    raise ValueError(f"Unsupported ASR provider: {selected_provider}")

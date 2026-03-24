import os
from typing import Optional, Tuple

from services.asr_providers.speechmatics_provider import SpeechmaticsASRService
from services.asr_providers.deepgram_provider import DeepgramASRService

DEFAULT_PROVIDER = os.getenv("ASR_PROVIDER", "speechmatics").lower()
DEFAULT_LANGUAGE = os.getenv("ASR_LANGUAGE", "ja").strip().lower()
DEFAULT_MODELS = {
    "speechmatics": "batch",
    "deepgram": "nova-3",
}

SUPPORTED_LANGUAGES = {
    "ja": "ja",
    "ja-jp": "ja",
    "en": "en",
    "en-us": "en",
    "en-gb": "en",
}


def resolve_asr_config(
    provider: Optional[str],
    model: Optional[str],
    language: Optional[str]
) -> Tuple[str, str, str]:
    selected_provider = (provider or DEFAULT_PROVIDER).lower()
    if selected_provider not in DEFAULT_MODELS:
        raise ValueError(f"Unsupported ASR provider: {selected_provider}")

    selected_model = model or os.getenv("ASR_MODEL") or DEFAULT_MODELS[selected_provider]
    language_key = (language or DEFAULT_LANGUAGE or "ja").strip().lower().replace("_", "-")
    selected_language = SUPPORTED_LANGUAGES.get(language_key)
    if not selected_language:
        supported = ", ".join(sorted(SUPPORTED_LANGUAGES.keys()))
        raise ValueError(f"Unsupported language: {language_key}. Supported: {supported}")

    return selected_provider, selected_model, selected_language


def get_asr_service(
    provider: Optional[str],
    model: Optional[str] = None,
    language: Optional[str] = None
):
    selected_provider, selected_model, selected_language = resolve_asr_config(provider, model, language)

    if selected_provider == "speechmatics":
        return (
            SpeechmaticsASRService(model=selected_model, language=selected_language),
            selected_provider,
            selected_model,
            selected_language,
        )
    if selected_provider == "deepgram":
        return (
            DeepgramASRService(model=selected_model, language=selected_language),
            selected_provider,
            selected_model,
            selected_language,
        )

    raise ValueError(f"Unsupported ASR provider: {selected_provider}")

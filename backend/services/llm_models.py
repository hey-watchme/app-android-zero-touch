"""LLM model catalog and normalization utilities for ZeroTouch."""

from typing import Dict, List


SUPPORTED_LLM_MODELS: Dict[str, List[str]] = {
    "openai": [
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-5.4",
        "gpt-5.4-2026-03-05",
    ],
    "gemini": [
        "gemini-3.1-pro-preview",
        "gemini-3-pro-preview",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
    ],
}

DEFAULT_MODEL_BY_PROVIDER: Dict[str, str] = {
    "openai": "gpt-5.4-2026-03-05",
    "gemini": "gemini-3.1-pro-preview",
}

DEFAULT_PROVIDER: str = "openai"

MODEL_ALIASES: Dict[str, Dict[str, str]] = {
    "openai": {},
    "gemini": {},
}


def normalize_provider(provider: str) -> str:
    normalized = (provider or "").strip().lower()
    if normalized not in SUPPORTED_LLM_MODELS:
        supported = ", ".join(sorted(SUPPORTED_LLM_MODELS.keys()))
        raise ValueError(f"Unknown provider: {provider}. Supported: {supported}")
    return normalized


def normalize_model(provider: str, model: str) -> str:
    normalized_provider = normalize_provider(provider)
    normalized_model = (model or "").strip()
    if not normalized_model:
        raise ValueError(f"Model is required for provider: {normalized_provider}")

    if normalized_model not in SUPPORTED_LLM_MODELS[normalized_provider]:
        supported = ", ".join(SUPPORTED_LLM_MODELS[normalized_provider])
        raise ValueError(
            f"Unsupported model '{normalized_model}' for provider '{normalized_provider}'. "
            f"Supported models: {supported}"
        )

    return normalized_model


def get_model_catalog() -> Dict[str, object]:
    return {
        "default_provider": DEFAULT_PROVIDER,
        "providers": {
            provider: {
                "default_model": DEFAULT_MODEL_BY_PROVIDER[provider],
                "models": models,
                "aliases": MODEL_ALIASES.get(provider, {}),
            }
            for provider, models in SUPPORTED_LLM_MODELS.items()
        }
    }

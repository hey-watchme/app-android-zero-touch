"""
LLM provider abstraction layer for ZeroTouch

Supports multiple providers: OpenAI, Google Gemini
Provider selection via environment variables or API parameters
"""

from abc import ABC, abstractmethod
from typing import Optional
import os
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type
from services.llm_models import (
    DEFAULT_MODEL_BY_PROVIDER,
    normalize_model,
    normalize_provider,
)


def _resolve_default_provider() -> str:
    configured = os.getenv("LLM_DEFAULT_PROVIDER", "openai")
    try:
        return normalize_provider(configured)
    except ValueError:
        print(f"Invalid LLM_DEFAULT_PROVIDER='{configured}', falling back to 'openai'")
        return "openai"


def _resolve_default_model(provider: str) -> str:
    default_model = DEFAULT_MODEL_BY_PROVIDER[provider]
    configured = os.getenv("LLM_DEFAULT_MODEL", default_model)
    try:
        return normalize_model(provider, configured)
    except ValueError:
        print(
            f"Invalid LLM_DEFAULT_MODEL='{configured}' for provider='{provider}', "
            f"falling back to '{default_model}'"
        )
        return default_model


CURRENT_PROVIDER = _resolve_default_provider()
CURRENT_MODEL = _resolve_default_model(CURRENT_PROVIDER)


class LLMProvider(ABC):
    """Abstract base class for LLM providers"""

    @abstractmethod
    def generate(self, prompt: str) -> str:
        pass

    @property
    @abstractmethod
    def model_name(self) -> str:
        pass


class OpenAIProvider(LLMProvider):
    """OpenAI API provider"""

    def __init__(self, model: str = "gpt-5.4-2026-03-05"):
        from openai import OpenAI

        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise ValueError("OPENAI_API_KEY environment variable not set")

        self.client = OpenAI(api_key=api_key)
        self._model = model

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=4, max=10),
        retry=retry_if_exception_type(Exception),
        reraise=True
    )
    def generate(self, prompt: str) -> str:
        try:
            response = self.client.chat.completions.create(
                model=self._model,
                messages=[{"role": "user", "content": prompt}]
            )
            return response.choices[0].message.content
        except Exception as e:
            print(f"OpenAI API call error: {e}")
            raise

    @property
    def model_name(self) -> str:
        return f"openai/{self._model}"


class GeminiProvider(LLMProvider):
    """Google Gemini API provider"""

    def __init__(self, model: str = "gemini-3.1-pro-preview"):
        try:
            from google import genai
        except ModuleNotFoundError as exc:
            raise RuntimeError(
                "Gemini SDK not installed. Install with: pip install -r requirements.txt"
            ) from exc

        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            raise ValueError("GEMINI_API_KEY environment variable not set")

        self.client = genai.Client(api_key=api_key)
        self._model = model

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=4, max=10),
        retry=retry_if_exception_type(Exception),
        reraise=True
    )
    def generate(self, prompt: str) -> str:
        try:
            response = self.client.models.generate_content(
                model=self._model,
                contents=prompt
            )
            return response.text
        except Exception as e:
            print(f"Gemini API call error: {e}")
            raise

    @property
    def model_name(self) -> str:
        return f"gemini/{self._model}"


class LLMFactory:
    """LLM provider factory"""

    @staticmethod
    def create(provider: str, model: Optional[str] = None) -> LLMProvider:
        normalized_provider = normalize_provider(provider)
        requested_model = model or DEFAULT_MODEL_BY_PROVIDER[normalized_provider]
        normalized_model = normalize_model(normalized_provider, requested_model)

        if normalized_provider == "openai":
            return OpenAIProvider(normalized_model)
        elif normalized_provider == "gemini":
            return GeminiProvider(normalized_model)
        else:
            raise ValueError(f"Unknown provider: {provider}. Supported: openai, gemini")

    @staticmethod
    def get_current() -> LLMProvider:
        print(f"Using LLM provider: {CURRENT_PROVIDER}/{CURRENT_MODEL}")
        return LLMFactory.create(CURRENT_PROVIDER, CURRENT_MODEL)


def get_current_llm() -> LLMProvider:
    return LLMFactory.get_current()

"""`/v1/tts`: Urdu speech audio from Cloud Text-to-Speech Gemini-TTS, cached on disk (SPEC 6.7)."""

import hashlib
from pathlib import Path
from typing import Protocol

STYLE_PROMPT = (
    "Read this in Urdu, in a calm, warm and respectful female voice, "
    "a little slowly and clearly, for an elderly listener."
)


class TtsError(Exception):
    pass


class TtsEngine(Protocol):
    def synthesize(self, text: str) -> bytes:
        """Returns WAV bytes. Raises TtsError."""
        ...


class CloudTts:
    """Google Cloud Text-to-Speech with a Gemini-TTS model and `ur-PK` (a preview language)."""

    def __init__(self, model: str, voice: str = "Kore", language: str = "ur-PK"):
        self.model = model
        self.voice = voice
        self.language = language
        self._client = None

    def synthesize(self, text: str) -> bytes:
        try:
            from google.cloud import texttospeech as tts

            if self._client is None:
                self._client = tts.TextToSpeechClient()
            response = self._client.synthesize_speech(
                input=tts.SynthesisInput(text=text, prompt=STYLE_PROMPT),
                voice=tts.VoiceSelectionParams(language_code=self.language, name=self.voice, model_name=self.model),
                # LINEAR16 comes back as a WAV file (with header).
                audio_config=tts.AudioConfig(audio_encoding=tts.AudioEncoding.LINEAR16, sample_rate_hertz=24000),
                timeout=10,
            )
        except Exception as e:  # credentials, quota, network, unsupported voice
            raise TtsError(f"cloud tts failed: {e}") from e
        if not response.audio_content:
            raise TtsError("cloud tts returned no audio")
        return response.audio_content


class UnconfiguredTts:
    def __init__(self, reason: str):
        self.reason = reason

    def synthesize(self, text: str) -> bytes:
        raise TtsError(self.reason)


class CachedTts:
    """Caches audio by SHA-256 of the text (and voice settings) so fixed phrases cost nothing after the first time."""

    def __init__(self, engine: TtsEngine, cache_dir: Path, key_extra: str = ""):
        self.engine = engine
        self.cache_dir = cache_dir
        self.key_extra = key_extra

    def path_for(self, text: str) -> Path:
        digest = hashlib.sha256(f"{self.key_extra}\n{text}".encode("utf-8")).hexdigest()
        return self.cache_dir / f"{digest}.wav"

    def get(self, text: str) -> bytes:
        path = self.path_for(text)
        if path.exists():
            return path.read_bytes()
        audio = self.engine.synthesize(text)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(audio)
        tmp.replace(path)
        return audio

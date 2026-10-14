import io
import wave

from fastapi.testclient import TestClient

from app.main import create_app
from app.tts import TtsError
from tests.conftest import FakeProvider, LLMError


def make_wav(seconds: float, rate: int = 16000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * int(seconds * rate))
    return buf.getvalue()


class FakeTts:
    def __init__(self, fail: bool = False):
        self.calls: list[str] = []
        self.fail = fail

    def synthesize(self, text: str, language: str = "ur") -> bytes:
        self.calls.append(text)
        if self.fail:
            raise TtsError("no credentials")
        return make_wav(0.1, 24000)


def client_with(settings, provider=None, tts=None) -> TestClient:
    return TestClient(create_app(settings, provider=provider or FakeProvider(), tts_engine=tts or FakeTts()))


# ---- /v1/stt ----

def test_stt_transcribes_wav(settings, auth):
    fake = FakeProvider({"text": "بیٹی کو تصویر بھیجنی ہے"})
    r = client_with(settings, fake).post(
        "/v1/stt", headers=auth, files={"audio": ("speech.wav", make_wav(2.0), "audio/wav")},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["text"] == "بیٹی کو تصویر بھیجنی ہے"
    assert isinstance(body["latency_ms"], int)
    assert fake.audio[0][1] == "audio/wav"


def test_stt_masks_numbers(settings, auth):
    fake = FakeProvider({"text": "Call 0300 1234567 please"})
    r = client_with(settings, fake).post("/v1/stt", headers=auth, files={"audio": ("a.wav", make_wav(1), "audio/wav")})
    assert r.json()["text"] == "Call [number] please"


def test_stt_needs_key(settings):
    r = client_with(settings).post("/v1/stt", files={"audio": ("a.wav", make_wav(1), "audio/wav")})
    assert r.status_code == 401


def test_stt_rejects_long_audio(settings, auth):
    r = client_with(settings).post("/v1/stt", headers=auth, files={"audio": ("a.wav", make_wav(25), "audio/wav")})
    assert r.status_code == 413


def test_stt_rejects_wrong_type(settings, auth):
    r = client_with(settings).post("/v1/stt", headers=auth, files={"audio": ("a.txt", b"hello", "text/plain")})
    assert r.status_code == 415


def test_stt_rejects_broken_wav(settings, auth):
    r = client_with(settings).post("/v1/stt", headers=auth, files={"audio": ("a.wav", b"RIFFnope", "audio/wav")})
    assert r.status_code == 422


def test_stt_accepts_ogg(settings, auth):
    fake = FakeProvider({"text": "Hello"})
    r = client_with(settings, fake).post("/v1/stt", headers=auth, files={"audio": ("a.ogg", b"OggS....", "audio/ogg")})
    assert r.status_code == 200
    assert fake.audio[0][1] == "audio/ogg"


def test_stt_provider_down_is_503(settings, auth):
    fake = FakeProvider(LLMError("quota"))
    r = client_with(settings, fake).post("/v1/stt", headers=auth, files={"audio": ("a.wav", make_wav(1), "audio/wav")})
    assert r.status_code == 503


# ---- /v1/tts ----

def test_tts_returns_wav_and_caches(settings, auth):
    engine = FakeTts()
    c = client_with(settings, tts=engine)
    for _ in range(2):
        r = c.post("/v1/tts", headers=auth, json={"text": "شاباش! کام ہو گیا۔"})
        assert r.status_code == 200
        assert r.headers["content-type"] == "audio/wav"
        assert r.content[:4] == b"RIFF"
    assert engine.calls == ["شاباش! کام ہو گیا۔"]  # second call served from the disk cache
    assert len(list(settings.tts_cache_dir.glob("*.wav"))) == 1


def test_tts_failure_is_503(settings, auth):
    r = client_with(settings, tts=FakeTts(fail=True)).post("/v1/tts", headers=auth, json={"text": "سلام"})
    assert r.status_code == 503


def test_tts_needs_key_and_text(settings, auth):
    c = client_with(settings)
    assert c.post("/v1/tts", json={"text": "سلام"}).status_code == 401
    assert c.post("/v1/tts", headers=auth, json={"text": ""}).status_code == 422


def test_tts_unconfigured_is_503(settings, auth):
    c = TestClient(create_app(settings, provider=FakeProvider()))  # TTS_MODEL not set
    assert c.post("/v1/tts", headers=auth, json={"text": "سلام"}).status_code == 503


def test_tts_caches_each_language_separately(settings, auth):
    engine = FakeTts()
    c = client_with(settings, tts=engine)
    assert c.post("/v1/tts", headers=auth, json={"text": "OK", "language": "en"}).status_code == 200
    assert c.post("/v1/tts", headers=auth, json={"text": "OK", "language": "ur"}).status_code == 200
    assert len(engine.calls) == 2

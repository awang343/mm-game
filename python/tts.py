"""Local text-to-speech via Piper. Blocking playback through sounddevice."""

from pathlib import Path

import numpy as np
import sounddevice as sd
from piper.download_voices import download_voice
from piper.voice import PiperVoice


_DEFAULT_VOICE = "en_US-amy-medium"
_voice_cache: dict[str, PiperVoice] = {}


def _load(voice_name: str) -> PiperVoice:
    if voice_name in _voice_cache:
        return _voice_cache[voice_name]
    cache_dir = Path.home() / ".cache" / "piper"
    cache_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = cache_dir / f"{voice_name}.onnx"
    if not onnx_path.exists():
        print(f"[tts] downloading {voice_name} voice...")
        download_voice(voice_name, cache_dir)
    v = PiperVoice.load(str(onnx_path))
    _voice_cache[voice_name] = v
    return v


def speak(text: str, voice_name: str = _DEFAULT_VOICE) -> None:
    text = text.strip()
    if not text:
        return
    voice = _load(voice_name)
    chunks = list(voice.synthesize(text))
    if not chunks:
        return
    audio = np.concatenate([c.audio_float_array for c in chunks])
    try:
        sd.play(audio, samplerate=chunks[0].sample_rate)
        sd.wait()
    except KeyboardInterrupt:
        sd.stop()
        raise

"""Voice input: record from mic, transcribe with Whisper, parse with a local LLM."""

import ctypes
import json
import os
import site
import sys
import urllib.error
import urllib.request
from pathlib import Path


def _preload_cuda12_libs() -> None:
    """CTranslate2 expects CUDA 12 .so files; system CUDA may be a different
    major version. Pip-installed nvidia-* packages bundle the right libs but
    aren't on the linker path. Pre-load them via ctypes (and extend
    LD_LIBRARY_PATH for any later dlopen)."""
    lib_dirs: list[Path] = []
    for sp in site.getsitepackages():
        for d in (Path(sp) / "nvidia").glob("*/lib"):
            if d.is_dir():
                lib_dirs.append(d)
    if not lib_dirs:
        return

    existing = os.environ.get("LD_LIBRARY_PATH", "")
    os.environ["LD_LIBRARY_PATH"] = ":".join(
        [str(d) for d in lib_dirs] + ([existing] if existing else [])
    )

    sos = [so for d in lib_dirs for so in d.glob("lib*.so.*")]
    for _ in range(3):  # multi-pass to satisfy inter-lib deps
        unresolved = []
        for so in sos:
            try:
                ctypes.CDLL(str(so), mode=ctypes.RTLD_GLOBAL)
            except OSError:
                unresolved.append(so)
        if not unresolved or unresolved == sos:
            break
        sos = unresolved


_preload_cuda12_libs()

import numpy as np
import sounddevice as sd
from faster_whisper import WhisperModel


SAMPLE_RATE = 16000
OLLAMA_URL = "http://localhost:11434/api/chat"

SYSTEM_PROMPT = """\
You convert a market-maker's spoken quote into JSON. The trader gives a \
two-sided market: a bid price + size, and an ask price + size. They may use \
natural phrasing.

Examples:
  "thirty bid at forty, five up"            -> {"bid": 30, "bid_size": 5, "ask": 40, "ask_size": 5}
  "thirty for five, five at forty"          -> {"bid": 30, "bid_size": 5, "ask": 40, "ask_size": 5}
  "twelve hundred to thirteen hundred, ten up" -> {"bid": 1200, "bid_size": 10, "ask": 1300, "ask_size": 10}
  "one nine six five at one nine seven zero, two up" -> {"bid": 1965, "bid_size": 2, "ask": 1970, "ask_size": 2}
  "I'm out" / "clear" / "skip" / "pass"     -> {"action": "out"}
  "quit" / "exit" / "stop"                  -> {"action": "quit"}

Return ONLY one JSON object. Shape is either:
  {"bid": <number>, "bid_size": <integer>, "ask": <number>, "ask_size": <integer>}
or:
  {"action": "out"}    // skip this contract
  {"action": "quit"}   // leave the sim
  {"action": "unparsed"}  // if you cannot parse

Bid must be strictly less than ask. Sizes are positive integers.
"""


_whisper: WhisperModel | None = None


def _load_whisper(model_size: str) -> WhisperModel:
    global _whisper
    if _whisper is not None:
        return _whisper
    try:
        _whisper = WhisperModel(model_size, device="cuda", compute_type="float16")
        print(f"[voice] Whisper {model_size} loaded on CUDA.")
    except Exception as e:
        print(f"[voice] CUDA unavailable ({e}); using CPU.")
        _whisper = WhisperModel(model_size, device="cpu", compute_type="int8")
    return _whisper


def record_until_enter() -> np.ndarray:
    """Record from default mic until the user presses Enter. Returns float32 mono."""
    print("Recording — press Enter to stop.")
    chunks: list[np.ndarray] = []

    def callback(indata, _frames, _time, status):
        if status:
            print(f"[voice] stream status: {status}", file=sys.stderr)
        chunks.append(indata.copy())

    with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                        callback=callback):
        try:
            input()
        except (KeyboardInterrupt, EOFError):
            pass

    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate([c.reshape(-1) for c in chunks])


def transcribe(audio: np.ndarray, model_size: str) -> str:
    if audio.size == 0:
        return ""
    model = _load_whisper(model_size)
    segments, _info = model.transcribe(audio, language="en", beam_size=1, vad_filter=True)
    return " ".join(seg.text.strip() for seg in segments).strip()


def parse_with_llm(text: str, model: str) -> dict:
    """Send the transcribed text to Ollama, get back a structured quote."""
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": text},
        ],
        "stream": False,
        "format": "json",
        "options": {"temperature": 0},
    }
    req = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        raise RuntimeError(
            f"Cannot reach Ollama at {OLLAMA_URL}: {e}. "
            "Start it with `ollama serve` and `ollama pull <model>`."
        ) from e

    content = body.get("message", {}).get("content", "").strip()
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        return {"action": "unparsed", "raw": content}


def voice_quote(whisper_model: str, llm_model: str):
    """One round of voice input. Returns same shape as mm.parse_quote."""
    raw = input("Your market [Enter to record, or type] > ")
    if raw.strip():
        text = raw
    else:
        audio = record_until_enter()
        if audio.size == 0:
            return None
        print("Transcribing...")
        text = transcribe(audio, whisper_model)
        print(f"  heard: {text!r}")
        if not text:
            return None

    result = parse_with_llm(text, llm_model)
    action = result.get("action")
    if action == "quit":
        return "quit"
    if action == "out":
        return "out"
    if action == "unparsed":
        return None

    try:
        bid = float(result["bid"])
        bid_size = int(float(result["bid_size"]))
        ask = float(result["ask"])
        ask_size = int(float(result["ask_size"]))
    except (KeyError, TypeError, ValueError):
        return None
    return (bid, bid_size, ask, ask_size)

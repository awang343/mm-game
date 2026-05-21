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
import webrtcvad
from faster_whisper import WhisperModel


SAMPLE_RATE = 16000
VAD_FRAME_MS = 30
VAD_FRAME_SAMPLES = SAMPLE_RATE * VAD_FRAME_MS // 1000  # 480 samples
OLLAMA_URL = "http://localhost:11434/api/chat"

SYSTEM_PROMPT = """\
You convert a market-maker's spoken input into JSON. There are two shapes:

A) An ACTION (the user is NOT making a quote). Output exactly one of:
   {"action": "out"}      — skip this contract: "out", "I'm out", "clear", "skip", "pass", "next"
   {"action": "quit"}     — leave the sim:     "quit", "exit", "stop", "I'm done"
   {"action": "repeat"}   — re-read question:  "repeat", "again", "say that again", "what was the question", "can you repeat", "say it again", "one more time"
   {"action": "unparsed"} — input is unrelated to market-making and not any other action above
   For ACTION outputs, DO NOT include bid/ask/size fields.

B) A QUOTE (the user is giving a two-sided market: bid price+size, ask price+size, in natural phrasing).
   Output: {"bid": <number>, "bid_size": <integer>, "ask": <number>, "ask_size": <integer>}
   bid must be strictly less than ask. Sizes are positive integers.

PATTERN: "<X> at <Y>, <Z> up" ALWAYS means bid=<X>, ask=<Y>, bid_size=ask_size=<Z>.
You MUST extract two distinct price numbers (X and Y) — never collapse them into one.
This holds even when X is small (zero, one, two) or X and Y differ by orders of magnitude.

Quote examples (covering all common phrasings):
  "thirty bid at forty, five up"            -> {"bid": 30, "bid_size": 5, "ask": 40, "ask_size": 5}
  "thirty for five, five at forty"          -> {"bid": 30, "bid_size": 5, "ask": 40, "ask_size": 5}
  "twelve hundred to thirteen hundred, ten up" -> {"bid": 1200, "bid_size": 10, "ask": 1300, "ask_size": 10}
  "one nine six five at one nine seven zero, two up" -> {"bid": 1965, "bid_size": 2, "ask": 1970, "ask_size": 2}
  "zero at five hundred, ten up"            -> {"bid": 0, "bid_size": 10, "ask": 500, "ask_size": 10}
  "one at one thousand, three up"           -> {"bid": 1, "bid_size": 3, "ask": 1000, "ask_size": 3}
  "five at fifty, two up"                   -> {"bid": 5, "bid_size": 2, "ask": 50, "ask_size": 2}

CRITICAL: a bare command like "repeat", "again", "out", or "quit" is an ACTION — not a quote. Do NOT fabricate numbers for it.
Return ONLY one JSON object.
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


def record_with_vad(
    aggressiveness: int = 3,
    silence_ms: int = 1500,
    trigger_frames: int = 3,
    pre_buffer_ms: int = 1500,
    rms_floor: float = 0.015,
    max_duration_s: float = 30.0,
    initial_timeout_s: float = 60.0,
) -> np.ndarray:
    """Record from mic with WebRTC VAD: wait for speech, auto-stop on silence.

    aggressiveness: 0 (lax) .. 3 (strict) — higher rejects more non-speech
    silence_ms: trailing silence (after speech started) before stopping
    max_duration_s: hard cap on recording length
    initial_timeout_s: give up if no speech detected at all within this window
    """
    import queue as _queue

    vad = webrtcvad.Vad(aggressiveness)
    silence_frames_to_stop = max(1, silence_ms // VAD_FRAME_MS)
    max_frames = int(max_duration_s * 1000 / VAD_FRAME_MS)
    initial_timeout_frames = int(initial_timeout_s * 1000 / VAD_FRAME_MS)
    pre_buffer_max = max(1, pre_buffer_ms // VAD_FRAME_MS)

    audio_q: _queue.Queue = _queue.Queue()

    def callback(indata, _frames, _time, status):
        if status:
            print(f"[voice] stream status: {status}", file=sys.stderr)
        audio_q.put(indata.copy().reshape(-1))

    collected: list[np.ndarray] = []
    # ring buffer of recent pre-trigger frames so we don't lose the utterance onset
    pre_buffer: list[np.ndarray] = []
    pending = np.zeros(0, dtype=np.float32)
    speech_started = False
    consecutive_speech = 0
    silence_count = 0
    total_frames = 0
    frames_without_speech = 0

    print("Listening... (speak when ready; auto-stops on silence)")
    with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                        callback=callback, blocksize=VAD_FRAME_SAMPLES):
        while True:
            try:
                chunk = audio_q.get(timeout=0.5)
            except _queue.Empty:
                continue

            pending = np.concatenate([pending, chunk])
            while len(pending) >= VAD_FRAME_SAMPLES:
                frame_f32 = pending[:VAD_FRAME_SAMPLES]
                pending = pending[VAD_FRAME_SAMPLES:]

                frame_i16 = np.clip(frame_f32 * 32767, -32768, 32767).astype(np.int16)
                # Combine WebRTC VAD with an RMS floor — frame counts as speech only
                # if both the model AND the energy say so. Filters out quiet false
                # positives from ambient/mic self-noise.
                rms = float(np.sqrt(np.mean(frame_f32 * frame_f32)))
                is_speech = vad.is_speech(frame_i16.tobytes(), SAMPLE_RATE) and rms >= rms_floor

                if speech_started:
                    collected.append(frame_f32)
                    if is_speech:
                        silence_count = 0
                    else:
                        silence_count += 1
                    total_frames += 1

                    if silence_count >= silence_frames_to_stop:
                        return np.concatenate(collected)
                    if total_frames >= max_frames:
                        print("[voice] max duration reached")
                        return np.concatenate(collected)
                else:
                    # rolling lead-in buffer so we preserve the utterance onset
                    # (covers stream cold-start latency + the trigger threshold)
                    pre_buffer.append(frame_f32)
                    if len(pre_buffer) > pre_buffer_max:
                        pre_buffer.pop(0)

                    if is_speech:
                        consecutive_speech += 1
                        if consecutive_speech >= trigger_frames:
                            speech_started = True
                            collected.extend(pre_buffer)
                            total_frames += len(pre_buffer)
                    else:
                        consecutive_speech = 0
                        frames_without_speech += 1
                        if frames_without_speech >= initial_timeout_frames:
                            print("[voice] no speech detected within timeout")
                            return np.zeros(0, dtype=np.float32)


_WHISPER_HINT = (
    "Two-sided market quotes for trivia contracts. Examples: "
    "thirty bid at forty, five up. "
    "Twelve hundred at thirteen hundred, ten up. "
    "Nineteen sixty-five at nineteen seventy, two up. "
    "Out. Quit. Repeat."
)


def transcribe(audio: np.ndarray, model_size: str) -> str:
    if audio.size == 0:
        return ""
    model = _load_whisper(model_size)
    segments, _info = model.transcribe(
        audio,
        language="en",
        beam_size=5,
        vad_filter=True,
        initial_prompt=_WHISPER_HINT,
    )
    return " ".join(seg.text.strip() for seg in segments).strip()


_REPEAT_PHRASES = {
    "repeat", "repeat please", "please repeat",
    "again", "again please", "one more time", "say it again", "say that again",
    "can you repeat", "can you repeat please", "can you say it again",
    "what was the question", "what's the question", "whats the question",
    "i didn't hear", "i didnt hear", "i didn't catch that",
}
_OUT_PHRASES = {"out", "i'm out", "im out", "clear", "skip", "skip this", "pass", "next"}
_QUIT_PHRASES = {"quit", "exit", "stop", "i'm done", "im done", "done"}


def _shortcircuit_action(text: str) -> str | None:
    t = text.strip().lower().rstrip(".?!,")
    if t in _REPEAT_PHRASES:
        return "repeat"
    if t in _OUT_PHRASES:
        return "out"
    if t in _QUIT_PHRASES:
        return "quit"
    return None


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
    # Re-listen silently if VAD trips on noise that yields no transcription.
    while True:
        audio = record_with_vad()
        if audio.size == 0:
            return None  # initial-silence timeout — give up
        print("Transcribing...")
        text = transcribe(audio, whisper_model)
        if text:
            print(f"  heard: {text!r}")
            break
        print("  (no speech detected — listening again)")

    short = _shortcircuit_action(text)
    if short is not None:
        return short

    # Try deterministic parser first — fast and reproducible for common phrasings.
    from quote_parser import parse_quote as _det_parse
    det = _det_parse(text)
    if det is not None:
        return det

    result = parse_with_llm(text, llm_model)
    action = result.get("action")
    if action == "quit":
        return "quit"
    if action == "out":
        return "out"
    if action == "repeat":
        return "repeat"
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

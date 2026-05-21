# Market Making

Practice quoting two-sided markets on numeric-answer trivia. The app asks a
question, you quote a `bid / ask / size`, a counterparty trades against any
side that gives them edge, and your P&L is computed against the true answer.

Three components, three folders:

| Dir | What | Stack |
|---|---|---|
| [`server/`](server/) | Owns `data/contracts.json`. Exposes `GET /contracts/random`. | Python stdlib HTTP (no deps at runtime) |
| [`python/`](python/) | Desktop terminal client. Voice/TTS/LLM all local. | uv + faster-whisper + Ollama + Piper |
| [`android/`](android/) | Kotlin/Compose mobile client. | SpeechRecognizer + TextToSpeech + MediaPipe LLM Inference |

Both clients fetch one contract per round over HTTP. The contract schema is
`{id, question, answer}` where `answer` is a number — the client uses it for
grading after the round.

## Quick start

```sh
# 1. start the server (binds 0.0.0.0:7878)
( cd server && uv sync && uv run python server.py )

# 2. in another terminal — desktop client
( cd python && uv sync && uv run python mm.py )

# 2b. or open android/ in Android Studio, run on emulator
#     (the default URL 10.0.2.2:7878 routes to the host's localhost)
```

## Notes

- `QuoteParser` is implemented identically in both clients:
  `python/quote_parser.py` and `android/app/src/main/java/com/alanxw/marketmaking/QuoteParser.kt`.
- Counterparty noise (default σ = 20% of fair) is symmetric in both clients —
  it's what gives you the chance to get lucky vs. a wrong counterparty.
- Server returns `answer` in the clear; clients use it for grading. Fine for
  single-user practice; would need a server-side grade endpoint for adversarial use.

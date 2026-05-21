# Market Making — Python (desktop client)

Terminal client for the market-making practice game. Fetches contracts from
`../server/` via HTTP. The contract data + populate script live with the
server now.

> See `../android/` for the Android port.
> See `../server/` for the contract server.

## Layout

```
python/
├── mm.py                 # terminal client (HTTP fetch from server)
├── voice_input.py        # mic + VAD + Whisper + LLM parse fallback
├── quote_parser.py       # deterministic word-number + grammar parser
└── tts.py                # local Piper text-to-speech
```

## Install

This project uses [uv](https://docs.astral.sh/uv/). For a system-wide install:

```sh
uv tool install .            # from the python/ dir
# `mm-client` ends up on PATH (~/.local/bin/mm-client)
```

Update with `uv tool install --reinstall .` after pulling changes; remove with
`uv tool uninstall mm-client`.

For one-off runs without installing: `uv sync && uv run python mm.py`.

## Config

First run writes `~/.config/mm-client/config.toml` (respects `XDG_CONFIG_HOME`)
with these defaults — edit to taste; CLI flags override:

```toml
server_url = "http://127.0.0.1:7878"
cp_noise = 0.20

voice = false
whisper_model = "small.en"
llm_model = "qwen2.5:3b"

speak = false
```

## Run

```sh
# In one terminal — start the contract server
( cd ../server && cargo run --release )

# In another — the client (now globally installed)
mm-client
# or override the server URL just for this run:
mm-client --server http://192.168.1.42:7878
```

## Voice mode (optional)

Speak your quote instead of typing. Audio → Whisper (local) → Ollama LLM → structured quote.

One-time setup:

```sh
# in another terminal — Ollama daemon must be running
ollama serve

# pull an instruction-tuned model (~2 GB, fits on a 4 GB GPU)
ollama pull qwen2.5:3b
```

Then:

```sh
uv run python mm.py --voice
# override models if you like:
uv run python mm.py --voice --whisper-model small.en --llm-model qwen3:8b
```

Hands-free: each round opens the mic automatically, waits for speech (via
WebRTC VAD + an RMS floor to ignore ambient noise), and stops after ~800 ms
of trailing silence. The LLM parser understands natural phrasing like
`"thirty bid at forty, five up"` or `"twelve hundred to thirteen hundred, ten up"`.
Say `"out"` to skip a contract, `"quit"` to leave.

Whisper auto-detects CUDA. Ollama uses your GPU automatically if it fits.

## Audio readout (optional)

Have the question and the round outcome spoken aloud via local TTS (Piper, CPU):

```sh
uv run python mm.py --speak
# combine with voice input:
uv run python mm.py --voice --speak
```

First run downloads the `en_US-amy-medium` voice model (~63 MB) into
`~/.cache/piper/`. Subsequent runs use the cached copy.

## Regenerating data

See `../server/README.md` — `populate.py` and `contracts.json` live with the
server now.

## Schema

The server returns `{id, question, answer}` where `answer` is int or float.

```json
{ "id": 1, "question": "In which year was CNN founded?", "answer": 1980 }
```

## Accepted quote formats

| Form          | Example                          |
|---------------|----------------------------------|
| Size-up       | `1830 at 1840, 5 up`             |
| Full          | `1830 bid for 5, 5 at 1840`      |
| Terse         | `1830 1840 5`                    |
| Clear market  | `out` / `i'm out`                |
| Quit          | `quit`                           |

The counterparty has a *noisy* view of the answer — they sample their own
estimate from a Gaussian centred on the true answer with σ = `--cp-noise`
× answer (default 20%). They trade against you when their estimate gives them
edge: if `bid > cp_fair` they sell to you; if `ask < cp_fair` they buy from
you. Your real P&L is computed against the true answer, so a counterparty
whose estimate is far off can fill you at a price that's actually in your
favour — that's the "get lucky" mode. Pass `--cp-noise 0` for an omniscient
counterparty (pure-skill mode).

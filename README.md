# Market Making Practice

Local Python terminal app for practicing two-sided quoting. The app asks a
trivia question with a numeric answer, you quote a two-sided market, and a
counterparty trades against any positive-EV side. A market that straddles
the answer survives — that's the win condition.

## Layout

```
market-making/
├── data/
│   └── contracts.json   # numeric-answer trivia questions
├── populate_data.py     # downloads TriviaQA → contracts.json
└── mm.py                # terminal app
```

## Setup

This project uses [uv](https://docs.astral.sh/uv/):

```sh
uv sync                  # install deps from pyproject.toml
```

## Run

```sh
uv run python mm.py
# or point at a non-default data directory:
uv run python mm.py --data /path/to/data
```

## Voice mode (optional)

Speak your quote instead of typing. Audio → Whisper (local) → Ollama LLM → structured quote.

One-time setup:

```sh
# in another terminal — Ollama daemon must be running
ollama serve

# pull a small instruction-tuned model (~1 GB, fits on a 4 GB GPU)
ollama pull qwen2.5:1.5b
```

Then:

```sh
uv run python mm.py --voice
# override models if you like:
uv run python mm.py --voice --whisper-model small.en --llm-model qwen3:8b
```

Push-to-talk: press Enter to start recording, Enter again to stop. Or type
a quote at the prompt — anything non-empty bypasses the mic and goes straight
to the LLM parser, which understands natural phrasing like
`"thirty bid at forty, five up"` or `"twelve hundred to thirteen hundred, ten up"`.

Whisper auto-detects CUDA. Ollama uses your GPU automatically if it fits.

## Regenerating data

`data/contracts.json` is built from the TriviaQA `unfiltered.nocontext`
config, filtered to questions whose canonical answer is a clean number:

```sh
uv run python populate_data.py
```

## Schema

Each contract is `{id, question, answer}` where `answer` is an int or float.

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

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

The counterparty knows the answer and trades only when your quote gives them
edge: if `bid > answer` they sell to you; if `ask < answer` they buy from you.

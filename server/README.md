# Market Making — Contract Server

Tiny HTTP service that owns `data/contracts.json` and exposes one endpoint
for clients to request a question. Python stdlib only at runtime; the
populate script uses `datasets` to (re)build the JSON from TriviaQA.

## Endpoints

| Method | Path                | Returns                          |
|--------|---------------------|----------------------------------|
| GET    | `/health`           | `{"ok": true, "contracts": N}`   |
| GET    | `/contracts/random` | one contract `{id, question, answer}` |
| GET    | `/contracts`        | the full list                    |

## Run

```sh
uv sync
uv run python server.py                # binds 0.0.0.0:7878 (LAN-reachable)
uv run python server.py --host 127.0.0.1 --port 9000   # localhost only
```

The default `0.0.0.0` bind lets a phone on the same wifi reach the server.
`http://10.0.2.2:7878` is what the Android emulator uses to talk to the host.

## (Re)build contracts.json

```sh
uv run python populate.py
```

Downloads TriviaQA `unfiltered.nocontext` and keeps only questions whose
canonical answer is a clean number (~2,687 of ~95k).

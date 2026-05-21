# Market Making — Contract Server

Small Rust HTTP service that owns `data/contracts.json` and exposes one
endpoint for clients to request a question. The data-populate step is still
Python (it pulls from `datasets` / TriviaQA) and lives next to the server.

## Endpoints

| Method | Path                | Returns                               |
|--------|---------------------|---------------------------------------|
| GET    | `/health`           | `{"ok": true, "contracts": N}`        |
| GET    | `/contracts/random` | one contract `{id, question, answer}` |
| GET    | `/contracts`        | the full list                         |

## Build & run

```sh
cargo run --release
# binary lands at target/release/mm-server — runnable on its own
./target/release/mm-server
```

`cargo run` (or the binary on its own) reads
`~/.config/mm-server/config.toml`. If the file doesn't exist, it's
created with sensible defaults:

```toml
data_path = "./data/contracts.json"
host = "0.0.0.0"
port = 7878
```

Edit `data_path` to point at wherever your `contracts.json` lives — the path
is resolved relative to the server's current working directory if not
absolute. `host = "0.0.0.0"` makes the server reachable across the LAN (good
for a phone client); use `127.0.0.1` to lock it to localhost. The Android
emulator can reach the host's `127.0.0.1:7878` via `http://10.0.2.2:7878`.

## (Re)build contracts.json

The populate script remains Python — TriviaQA's `datasets` integration is
the path of least resistance:

```sh
uv sync                 # one-time
uv run python populate.py
```

Downloads TriviaQA `unfiltered.nocontext` and keeps only questions whose
canonical answer is a clean number (~2,687 of ~95k). Writes to
`data/contracts.json` next to the script.

## Layout

```
server/
├── Cargo.toml
├── src/main.rs         # the Rust HTTP server
├── populate.py         # data prep — Python (uv)
├── pyproject.toml      # populate's deps (datasets)
├── data/contracts.json # gitignored; regenerate via populate.py
└── README.md
```

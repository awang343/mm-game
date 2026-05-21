#!/usr/bin/env python3
"""Market-making practice — local terminal app."""

import argparse
import json
import random
import re
import sys
from pathlib import Path


def load_contracts(data_dir: Path) -> list[dict]:
    return json.loads((data_dir / "contracts.json").read_text(encoding="utf-8"))


# ---------- quote parsing ----------

NUM = r"(\d+(?:\.\d+)?)"
INT = r"(\d+)"

PAT_SIZE_UP = re.compile(rf"^\s*{NUM}\s+at\s+{NUM}\s*,?\s*{INT}\s+up\s*$", re.I)
PAT_FULL = re.compile(
    rf"^\s*{NUM}\s+bid\s+for\s+{INT}\s*,?\s*{INT}\s+(?:at|offered\s+at)\s+{NUM}\s*$",
    re.I,
)
PAT_TERSE = re.compile(rf"^\s*{NUM}\s+{NUM}\s+{INT}\s*$")


def parse_quote(text: str):
    """Return (bid, bid_size, ask, ask_size) or None if not a valid two-sided quote.

    Special return values:
      "out" -> trader cleared the market
      "quit" -> trader wants to exit
    """
    t = text.strip().lower()
    if t in ("out", "i'm out", "im out", "clear"):
        return "out"
    if t in ("q", "quit", "exit"):
        return "quit"

    m = PAT_SIZE_UP.match(t)
    if m:
        bid, ask, size = float(m.group(1)), float(m.group(2)), int(m.group(3))
        return (bid, size, ask, size)

    m = PAT_FULL.match(t)
    if m:
        bid = float(m.group(1))
        bid_size = int(m.group(2))
        ask_size = int(m.group(3))
        ask = float(m.group(4))
        return (bid, bid_size, ask, ask_size)

    m = PAT_TERSE.match(t)
    if m:
        bid, ask, size = float(m.group(1)), float(m.group(2)), int(m.group(3))
        return (bid, size, ask, size)

    return None


# ---------- sim mode ----------

def play_sim(
    contracts: list[dict],
    voice_cfg: dict | None = None,
    cp_noise: float = 0.20,
) -> None:
    print("\n=== Market-Making Simulator ===")
    if voice_cfg:
        print(f"Voice mode (Whisper={voice_cfg['whisper']}, LLM={voice_cfg['llm']}).")
        print("Press Enter to record, Enter again to stop. Or type a quote.")
    else:
        print("Type a two-sided market. Accepted formats:")
        print("  '1830 at 1840, 5 up'              (size-up: same size both sides)")
        print("  '1830 bid for 5, 5 at 1840'       (full form)")
        print("  '1830 1840 5'                     (terse: bid ask size)")
    print(f"Counterparty noise: σ = {cp_noise:.0%} of fair (set --cp-noise 0 for omniscient).")
    print("Special: 'out' to clear, 'quit' to exit.\n")

    score = {"trades": 0, "pnl": 0.0, "straddled": 0}

    while True:
        contract = random.choice(contracts)

        print(f"Contract: {contract['question']}")
        if voice_cfg:
            try:
                parsed = voice_cfg["module"].voice_quote(
                    voice_cfg["whisper"], voice_cfg["llm"]
                )
            except RuntimeError as e:
                print(f"[voice] {e}\n")
                return
        else:
            raw = input("Your market> ").strip()
            if not raw:
                continue
            parsed = parse_quote(raw)
        if parsed == "quit":
            break
        if parsed == "out":
            print("Market cleared. Next contract.\n")
            continue
        if parsed is None:
            print("Couldn't parse that. Try one of the formats above.\n")
            continue

        bid, bid_size, ask, ask_size = parsed
        if ask <= bid:
            print(f"Inverted/locked market (ask {ask} <= bid {bid}). Try again.\n")
            continue

        fair = float(contract["answer"])
        # Counterparty has a noisy view of fair. Trade decision uses cp_fair;
        # your real P&L is computed against the true fair, so a counterparty
        # whose estimate is far off can give you a lucky fill.
        cp_fair = fair + random.gauss(0, abs(fair) * cp_noise) if cp_noise > 0 else fair

        if bid <= fair <= ask:
            score["straddled"] += 1

        if bid > cp_fair:
            pnl = (fair - bid) * bid_size
            outcome = f"Counterparty SOLD {bid_size} to you at {bid:g}."
        elif ask < cp_fair:
            pnl = (ask - fair) * ask_size
            outcome = f"Counterparty BOUGHT {ask_size} from you at {ask:g}."
        else:
            pnl = 0.0
            outcome = "No trade — counterparty saw no edge."

        score["trades"] += 1
        score["pnl"] += pnl

        width = ask - bid
        bid_dist = fair - bid
        ask_dist = ask - fair
        cp_tag = "" if cp_noise == 0 else f"  (counterparty saw: {cp_fair:.2f})"
        print(f"-> Fair value: {fair:g}{cp_tag}")
        print(f"   Your market: {bid:g} bid for {bid_size}, {ask_size} at {ask:g}  (width {width:g})")
        print(f"   Distance from fair: bid {bid_dist:+g}, ask {ask_dist:+g}")
        print(f"   {outcome}  P&L this round: {pnl:+.2f}")
        print(f"   Session: {score['trades']} rounds | "
              f"straddled {score['straddled']} | "
              f"total P&L {score['pnl']:+.2f}\n")


def main() -> int:
    default_data = Path(__file__).resolve().parent / "data"
    ap = argparse.ArgumentParser(description="Market-making practice terminal app")
    ap.add_argument("--data", default=str(default_data),
                    help=f"Path to the data directory (default: {default_data})")
    ap.add_argument("--voice", action="store_true",
                    help="Use mic input + local LLM to parse quotes.")
    ap.add_argument("--whisper-model", default="base.en",
                    help="faster-whisper model size (e.g. tiny.en, base.en, small.en).")
    ap.add_argument("--llm-model", default="qwen2.5:1.5b",
                    help="Ollama model name to use for quote parsing.")
    ap.add_argument("--cp-noise", type=float, default=0.20,
                    help="Counterparty's view of fair has Gaussian noise with "
                         "σ = this fraction of fair (default 0.20). 0 = omniscient.")
    args = ap.parse_args()

    data_dir = Path(args.data)
    try:
        contracts = load_contracts(data_dir)
    except (OSError, json.JSONDecodeError) as e:
        print(f"Cannot load data from {data_dir}: {e}")
        return 1

    print(f"Loaded {len(contracts)} contracts from {data_dir}.")
    voice_cfg = None
    if args.voice:
        import voice_input  # local import: heavy deps only when requested
        voice_cfg = {
            "module": voice_input,
            "whisper": args.whisper_model,
            "llm": args.llm_model,
        }
    play_sim(contracts, voice_cfg, cp_noise=args.cp_noise)
    print("Bye.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

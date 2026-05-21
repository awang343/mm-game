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

def play_sim(contracts: list[dict]) -> None:
    print("\n=== Market-Making Simulator ===")
    print("Type a two-sided market. Accepted formats:")
    print("  '1830 at 1840, 5 up'              (size-up: same size both sides)")
    print("  '1830 bid for 5, 5 at 1840'       (full form)")
    print("  '1830 1840 5'                     (terse: bid ask size)")
    print("Special: 'out' to clear, 'quit' to exit.\n")

    score = {"trades": 0, "pnl": 0.0, "good_markets": 0}

    while True:
        contract = random.choice(contracts)

        print(f"Contract: {contract['question']}")
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

        # Counterparty trades against you if your market gives them positive EV
        # (assuming they know fair). Otherwise no trade and you survive.
        if bid > fair:
            edge = bid - fair
            pnl = -edge * bid_size
            outcome = f"Counterparty SOLD {bid_size} to you at {bid:g}."
        elif ask < fair:
            edge = fair - ask
            pnl = -edge * ask_size
            outcome = f"Counterparty BOUGHT {ask_size} from you at {ask:g}."
        else:
            edge = 0.0
            pnl = 0.0
            outcome = "No trade — your market straddles fair value. Solid quote."
            score["good_markets"] += 1

        score["trades"] += 1
        score["pnl"] += pnl

        width = ask - bid
        bid_dist = fair - bid
        ask_dist = ask - fair
        print(f"-> Fair value: {fair:g}")
        print(f"   Your market: {bid:g} bid for {bid_size}, {ask_size} at {ask:g}  (width {width:g})")
        print(f"   Distance from fair: bid {bid_dist:+g}, ask {ask_dist:+g}")
        print(f"   {outcome}  P&L this round: {pnl:+.2f}")
        print(f"   Session: {score['trades']} rounds | "
              f"good markets {score['good_markets']} | "
              f"total P&L {score['pnl']:+.2f}\n")


def main() -> int:
    default_data = Path(__file__).resolve().parent / "data"
    ap = argparse.ArgumentParser(description="Market-making practice terminal app")
    ap.add_argument("--data", default=str(default_data),
                    help=f"Path to the data directory (default: {default_data})")
    args = ap.parse_args()

    data_dir = Path(args.data)
    try:
        contracts = load_contracts(data_dir)
    except (OSError, json.JSONDecodeError) as e:
        print(f"Cannot load data from {data_dir}: {e}")
        return 1

    print(f"Loaded {len(contracts)} contracts from {data_dir}.")
    play_sim(contracts)
    print("Bye.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

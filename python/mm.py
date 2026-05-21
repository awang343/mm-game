#!/usr/bin/env python3
"""Market-making practice — local terminal app."""

import argparse
import json
import random
import re
import sys
import urllib.error
import urllib.request


def fetch_contract(server_url: str) -> dict:
    """GET <server>/contracts/random and return the parsed contract."""
    url = server_url.rstrip("/") + "/contracts/random"
    with urllib.request.urlopen(url, timeout=5) as resp:
        return json.loads(resp.read().decode("utf-8"))


def server_health(server_url: str) -> dict:
    url = server_url.rstrip("/") + "/health"
    with urllib.request.urlopen(url, timeout=3) as resp:
        return json.loads(resp.read().decode("utf-8"))


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
    server_url: str,
    voice_cfg: dict | None = None,
    cp_noise: float = 0.20,
    speak_module=None,
) -> None:
    print("\n=== Market-Making Simulator ===")
    if voice_cfg:
        print(f"Voice mode (Whisper={voice_cfg['whisper']}, LLM={voice_cfg['llm']}).")
        print("Just speak — recording auto-starts on speech and stops on silence.")
    else:
        print("Type a two-sided market. Accepted formats:")
        print("  '1830 at 1840, 5 up'              (size-up: same size both sides)")
        print("  '1830 bid for 5, 5 at 1840'       (full form)")
        print("  '1830 1840 5'                     (terse: bid ask size)")
    print(f"Counterparty noise: σ = {cp_noise:.0%} of fair (set --cp-noise 0 for omniscient).")
    print("Special: 'out' to clear, 'quit' to exit.\n")

    score = {"trades": 0, "pnl": 0.0, "straddled": 0}

    while True:
        try:
            contract = fetch_contract(server_url)
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"server error: {e}")
            return

        print(f"Contract: {contract['question']}")
        if speak_module:
            speak_module.speak(contract["question"])

        # Re-prompt on bad/invalid input rather than skipping the contract.
        while True:
            if voice_cfg:
                try:
                    parsed = voice_cfg["module"].voice_quote(
                        voice_cfg["whisper"], voice_cfg["llm"]
                    )
                except RuntimeError as e:
                    print(f"[voice] {e}\n")
                    return
                if parsed is None:
                    msg = "Sorry, I didn't catch that. Please re-quote your market."
                    print(msg)
                    if speak_module:
                        speak_module.speak(msg)
                    continue
            else:
                raw = input("Your market> ").strip()
                if not raw:
                    continue
                parsed = parse_quote(raw)
                if parsed is None:
                    print("Couldn't parse that. Try one of the formats above.\n")
                    continue

            if parsed == "repeat":
                if speak_module:
                    speak_module.speak(contract["question"])
                else:
                    print(f"Contract: {contract['question']}")
                continue

            if parsed in ("quit", "out"):
                break

            bid, bid_size, ask, ask_size = parsed
            if ask <= bid:
                msg = f"Inverted market (ask {ask:g} <= bid {bid:g}). Please re-quote."
                print(msg)
                if speak_module:
                    speak_module.speak("Inverted market. Please re-quote.")
                continue
            break  # valid two-sided quote

        if parsed == "quit":
            break
        if parsed == "out":
            print("Market cleared. Next contract.\n")
            continue
        bid, bid_size, ask, ask_size = parsed

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
            outcome_spoken = f"Counterparty sold {bid_size} to you at {bid:g}."
        elif ask < cp_fair:
            pnl = (ask - fair) * ask_size
            outcome = f"Counterparty BOUGHT {ask_size} from you at {ask:g}."
            outcome_spoken = f"Counterparty bought {ask_size} from you at {ask:g}."
        else:
            pnl = 0.0
            outcome = "No trade — counterparty saw no edge."
            outcome_spoken = "No trade."

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

        if speak_module:
            verb = "made" if pnl > 0 else ("lost" if pnl < 0 else "broke even,")
            amount = "" if pnl == 0 else f" {abs(pnl):.0f}"
            speak_module.speak(
                f"{outcome_spoken} True answer was {fair:g}. "
                f"You {verb}{amount}."
            )


def main() -> int:
    ap = argparse.ArgumentParser(description="Market-making practice terminal app")
    ap.add_argument("--server", default="http://127.0.0.1:7878",
                    help="Contract server base URL (default: http://127.0.0.1:7878)")
    ap.add_argument("--voice", action="store_true",
                    help="Use mic input + local LLM to parse quotes.")
    ap.add_argument("--whisper-model", default="small.en",
                    help="faster-whisper model size (e.g. tiny.en, base.en, small.en, medium.en).")
    ap.add_argument("--llm-model", default="qwen2.5:3b",
                    help="Ollama model name to use for quote parsing.")
    ap.add_argument("--cp-noise", type=float, default=0.20,
                    help="Counterparty's view of fair has Gaussian noise with "
                         "σ = this fraction of fair (default 0.20). 0 = omniscient.")
    ap.add_argument("--speak", action="store_true",
                    help="Read the question and the result aloud via local TTS (Piper).")
    args = ap.parse_args()

    try:
        h = server_health(args.server)
        print(f"Connected to {args.server} — {h.get('contracts', '?')} contracts.")
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"Cannot reach server at {args.server}: {e}")
        print("Start it from the server/ dir: `uv run python server.py`")
        return 1

    voice_cfg = None
    if args.voice:
        import voice_input  # local import: heavy deps only when requested
        voice_cfg = {
            "module": voice_input,
            "whisper": args.whisper_model,
            "llm": args.llm_model,
        }
    speak_module = None
    if args.speak:
        import tts as speak_module  # local import
    play_sim(args.server, voice_cfg, cp_noise=args.cp_noise, speak_module=speak_module)
    print("Bye.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

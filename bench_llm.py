"""Benchmark candidate Ollama models on the voice-quote parsing task."""

import time
from voice_input import parse_with_llm


def quote(bid, bid_size, ask, ask_size):
    return {"bid": bid, "bid_size": bid_size, "ask": ask, "ask_size": ask_size}


CASES = [
    ("thirty bid at forty, five up",                          quote(30, 5, 40, 5)),
    ("I will go 1830 for 5, 5 at 1840",                       quote(1830, 5, 1840, 5)),
    ("twelve hundred to thirteen hundred, ten up",            quote(1200, 10, 1300, 10)),
    ("one nine six five at one nine seven zero, two up",      quote(1965, 2, 1970, 2)),
    ("nineteen sixty-five at nineteen seventy, two up",       quote(1965, 2, 1970, 2)),
    ("two hundred at two ten, three up",                      quote(200, 3, 210, 3)),
    ("two thousand twenty at two thousand twenty-five, ten up", quote(2020, 10, 2025, 10)),
    ("I am out",                                              {"action": "out"}),
    ("quit",                                                  {"action": "quit"}),
    ("this is just nonsense",                                 {"action": "unparsed"}),
]

MODELS = ["qwen2.5:0.5b", "qwen2.5:1.5b", "llama3.2:1b", "llama3.2:3b"]


def matches(expected: dict, got: dict) -> bool:
    if "action" in expected:
        return got.get("action") == expected["action"]
    keys = ("bid", "bid_size", "ask", "ask_size")
    try:
        return all(float(got[k]) == float(expected[k]) for k in keys)
    except (KeyError, TypeError, ValueError):
        return False


def main():
    for model in MODELS:
        # warm-up call (excluded from timing)
        try:
            parse_with_llm("warm up", model)
        except Exception as e:
            print(f"\n{model}: warm-up failed: {e}")
            continue

        print(f"\n=== {model} ===")
        passes = 0
        latencies = []
        for text, expected in CASES:
            t0 = time.time()
            try:
                got = parse_with_llm(text, model)
            except Exception as e:
                print(f"  ERR  {text!r}: {e}")
                continue
            dt = time.time() - t0
            latencies.append(dt)
            ok = matches(expected, got)
            passes += int(ok)
            mark = "PASS" if ok else "FAIL"
            print(f"  {mark}  {dt:4.2f}s  {text!r:60s} -> {got}")
        avg = sum(latencies) / len(latencies) if latencies else 0
        print(f"  {passes}/{len(CASES)} pass, avg {avg:.2f}s")


if __name__ == "__main__":
    main()

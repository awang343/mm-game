#!/usr/bin/env python3
"""Populate data/contracts.json from TriviaQA (numeric-answer questions only)."""

import json
import re
from pathlib import Path

from datasets import load_dataset

NUMERIC_RE = re.compile(r"^-?\d+(?:,\d{3})*(?:\.\d+)?$")


def parse_number(s: str):
    s = s.strip()
    if not NUMERIC_RE.match(s):
        return None
    try:
        f = float(s.replace(",", ""))
    except ValueError:
        return None
    return int(f) if f.is_integer() else f


def extract_numeric_answer(answer_obj):
    # Only accept questions whose canonical answer is itself numeric —
    # alias fallback produces noise (e.g. musical-name answers whose alias
    # list happens to contain a stray digit).
    return parse_number(answer_obj.get("value", ""))


def main():
    ds = load_dataset("mandarjoshi/trivia_qa", "unfiltered.nocontext")
    seen = set()
    contracts = []
    next_id = 1
    for split_name in ("train", "validation"):
        if split_name not in ds:
            continue
        for row in ds[split_name]:
            q = row["question"].strip()
            if q in seen:
                continue
            n = extract_numeric_answer(row["answer"])
            if n is None:
                continue
            seen.add(q)
            contracts.append({"id": next_id, "question": q, "answer": n})
            next_id += 1
    out = Path(__file__).resolve().parent / "data" / "contracts.json"
    out.write_text(
        json.dumps(contracts, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {len(contracts)} numeric-answer contracts to {out}")


if __name__ == "__main__":
    main()

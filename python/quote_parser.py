"""Deterministic parser for spoken market-making quotes.

Two passes:
  1. normalize_numbers() — convert word-numbers in text to digit form,
     handling standard cardinals ("five hundred"), year form ("nineteen sixty-five"),
     and digit-by-digit reading ("one nine six five").
  2. parse_quote() — search the normalized string for known quote patterns
     using regex. Uses re.search() so a leading preamble like "let's say..."
     or "okay so..." doesn't prevent matching.

Returns (bid, bid_size, ask, ask_size) on success, None otherwise. The LLM
fallback in voice_input.voice_quote handles anything this can't match.
"""

import re


_ONES = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4,
    "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
    "oh": 0,  # year-style "oh" for zero
}
_TEENS = {
    "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14,
    "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
}
_TENS = {
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50,
    "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
}
_SCALES = {"hundred": 100, "thousand": 1000, "million": 1_000_000}
_NUMBER_WORDS = set(_ONES) | set(_TEENS) | set(_TENS) | set(_SCALES)

_TOKEN_RE = re.compile(r"\d+(?:,\d{3})*(?:\.\d+)?|[a-z]+|[,;:.!?]")


def _small_cardinal(tokens):
    """Parse 0..99 from at most two tokens. Returns int or None."""
    if not tokens:
        return None
    if len(tokens) == 1:
        t = tokens[0]
        if t in _ONES: return _ONES[t]
        if t in _TEENS: return _TEENS[t]
        if t in _TENS: return _TENS[t]
        return None
    if len(tokens) == 2:
        if tokens[0] in _TENS and tokens[1] in _ONES:
            return _TENS[tokens[0]] + _ONES[tokens[1]]
        # year shortcut "oh five" → 5
        if tokens[0] == "oh" and tokens[1] in _ONES:
            return _ONES[tokens[1]]
    return None


def _year_form(tokens):
    """'nineteen sixty-five' → 1965, 'twenty twenty-five' → 2025."""
    if not tokens:
        return None
    # Only TEENS (1100..1999) or "twenty" (2000..2099) as centuries — accepting
    # all TENS produced spurious matches like "forty five" → 4005.
    if tokens[0] in _TEENS:
        century = _TEENS[tokens[0]] * 100
    elif tokens[0] == "twenty" and len(tokens) >= 2:
        century = 2000
    else:
        return None
    rest = _small_cardinal(tokens[1:])
    if rest is None or rest >= 100:
        return None
    return century + rest


def _standard_cardinal(tokens):
    """'two thousand five hundred forty' style."""
    total = 0
    current = 0
    for t in tokens:
        if t in _ONES:
            current += _ONES[t]
        elif t in _TEENS:
            current += _TEENS[t]
        elif t in _TENS:
            current += _TENS[t]
        elif t == "hundred":
            current = (current or 1) * 100
        elif t == "thousand":
            total += (current or 1) * 1000
            current = 0
        elif t == "million":
            total += (current or 1) * 1_000_000
            current = 0
        else:
            return None
    return total + current


def _words_to_nums(tokens):
    """Parse a number-word sequence into one or more integers.

    Returns a list so a year prefix can be peeled off and the trailing
    tokens parsed separately — e.g. 'nineteen eighty ten' → [1980, 10].
    """
    if not tokens:
        return []

    # Digit-by-digit: "one nine six five" → [1965]
    if len(tokens) >= 3 and all(t in _ONES for t in tokens):
        return [int("".join(str(_ONES[t]) for t in tokens))]

    # Try year form on a 2- or 3-token prefix, then recurse.
    for prefix_len in (3, 2):
        if len(tokens) >= prefix_len:
            yr = _year_form(tokens[:prefix_len])
            if yr is not None:
                return [yr] + _words_to_nums(tokens[prefix_len:])

    # Whole-sequence standard cardinal.
    full = _standard_cardinal(tokens)
    if full is not None:
        return [full]

    # Fallback: greedy longest-cardinal prefix, then recurse.
    for split in range(len(tokens), 0, -1):
        head = _standard_cardinal(tokens[:split])
        if head is not None:
            return [head] + _words_to_nums(tokens[split:])
    return []


def normalize_numbers(text: str) -> str:
    """Replace word-numbers in text with digit form. Hybrid digit/word
    inputs like 'Zero at 500, ten up' become '0 at 500, 10 up'."""
    text = text.lower().replace("-", " ")
    tokens = _TOKEN_RE.findall(text)

    out: list[str] = []
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        if tok in _NUMBER_WORDS:
            # consume a run of number-words, allowing internal "and"
            seq = []
            j = i
            while j < len(tokens) and (tokens[j] in _NUMBER_WORDS or tokens[j] == "and"):
                if tokens[j] != "and":
                    seq.append(tokens[j])
                j += 1
            nums = _words_to_nums(seq)
            if nums:
                out.append(" ".join(str(n) for n in nums))
            else:
                out.extend(tokens[i:j])  # give up — emit raw tokens
            i = j
        elif tok[0].isdigit():
            out.append(tok.replace(",", ""))
            i += 1
        else:
            out.append(tok)
            i += 1

    # join with spaces, no space before punctuation
    result = ""
    for p in out:
        if p in (",", ";", ":", ".", "!", "?"):
            result += p
        else:
            result += (" " if result else "") + p
    return result


# Trader vocabulary kept by the filter — everything else is dropped as noise.
_TRADER_VOCAB = {"at", "to", "by", "bid", "for", "up", "offered", "offer"}


def filter_to_vocab(normalized: str) -> str:
    """Drop tokens that aren't numbers or trader keywords.

    Lets us tolerate arbitrary preamble / interjections / fillers anywhere
    in the input ('let's say', 'okay so', 'uh', 'you know'). After this we
    have a tight string of just `(\\d+|<vocab>)` tokens for the matcher.
    """
    kept = []
    for tok in re.findall(r"\d+(?:\.\d+)?|[a-z]+", normalized.lower()):
        if tok in _TRADER_VOCAB or tok[0].isdigit():
            kept.append(tok)
    return " ".join(kept)


_NUM = r"(\d+(?:\.\d+)?)"
_INT = r"(\d+)"

# After vocab filtering, the strings are tight enough that these patterns
# can use anchored search on the small token stream.

# "X (bid)? (at|to|by|offered) Y (for)? N up"  — size-up form, same size both sides
_PAT_SIZE_UP = re.compile(
    rf"{_NUM}\s+(?:bid\s+)?(?:at|to|by|offered)\s+{_NUM}\s+(?:for\s+)?{_INT}\s+up\b",
    re.I,
)

# "X bid for N M (at|offered) Y"  — full asymmetric form
_PAT_FULL = re.compile(
    rf"{_NUM}\s+bid\s+for\s+{_INT}\s+{_INT}\s+(?:at|offered)\s+{_NUM}",
    re.I,
)


def parse_quote(text: str):
    """Return (bid, bid_size, ask, ask_size) on a match, else None.

    Pipeline: word-numbers → digits, drop non-vocab tokens, match patterns.
    Robust to arbitrary filler before, within, or after the quote.
    """
    if not text:
        return None
    filtered = filter_to_vocab(normalize_numbers(text))

    m = _PAT_SIZE_UP.search(filtered)
    if m:
        bid, ask, size = float(m.group(1)), float(m.group(2)), int(m.group(3))
        if ask > bid:
            return (bid, size, ask, size)

    m = _PAT_FULL.search(filtered)
    if m:
        bid = float(m.group(1))
        bid_size = int(m.group(2))
        ask_size = int(m.group(3))
        ask = float(m.group(4))
        if ask > bid:
            return (bid, bid_size, ask, ask_size)

    return None

#!/usr/bin/env python3
"""
Build a human-review worksheet for the bundle evaluation corpus.

For every question in eval/bundle-corpus/queries.csv, resolve each gold
(canonical_id, heading_path) to the actual section text in its Markdown page and
lay it out inline, so a reviewer can judge "does this section answer the query?"
by skimming one document instead of opening ~40 files.

Deterministic: it reads the real pages; it never invents content. Re-run after
editing queries.csv to refresh the worksheet.

Usage:  python3 bin/eval/build-review-worksheet.py
Writes: eval/bundle-corpus/review-worksheet.md
"""
import csv
import glob
import os
import re

PAGES = "docs/wikantik-pages"
CSV_PATH = "eval/bundle-corpus/queries.csv"
OUT_PATH = "eval/bundle-corpus/review-worksheet.md"
BODY_CHAR_CAP = 1200  # keep the worksheet skimmable


def build_canonical_index():
    idx = {}
    for path in glob.glob(os.path.join(PAGES, "**", "*.md"), recursive=True):
        try:
            with open(path, encoding="utf-8") as f:
                head = f.read(4000)
        except OSError:
            continue
        m = re.search(r"^canonical_id:\s*(\S+)", head, re.M)
        if m:
            idx.setdefault(m.group(1).strip(), path)
    return idx


def _level(line):
    m = re.match(r"^(#{1,6})\s", line)
    return len(m.group(1)) if m else None


def _text(line):
    return re.sub(r"^#{1,6}\s+", "", line).strip()


def _norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def extract_section(path, heading_path):
    """Return (matched_heading_line, body_text) or (None, None) if not found.

    Matches the LAST segment of the '>'-separated heading_path against a heading
    in the page (normalized; tolerant of minor truncation), then captures lines
    until the next heading at the same or higher level.
    """
    segments = [s.strip() for s in heading_path.split(">") if s.strip()]
    if not segments:
        return None, None
    target = _norm(segments[-1])
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    start = lvl = None
    for i, line in enumerate(lines):
        l = _level(line)
        if l is None:
            continue
        ht = _norm(_text(line))
        if ht == target or ht.startswith(target) or target.startswith(ht):
            start, lvl = i, l
            break
    if start is None:
        return None, None
    body = []
    for j in range(start + 1, len(lines)):
        l = _level(lines[j])
        if l is not None and l <= lvl:
            break
        body.append(lines[j])
    return lines[start].rstrip(), "".join(body).strip()


def main():
    idx = build_canonical_index()
    # group rows by query_id, preserving order
    questions = {}
    order = []
    with open(CSV_PATH, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("query_id,"):
                continue
            parts = next(csv.reader([line]))
            if len(parts) < 5:
                continue
            qid, query, cat, cid, hpath = parts[0], parts[1], parts[2], parts[3], parts[4]
            if qid not in questions:
                questions[qid] = {"query": query, "cat": cat, "golds": []}
                order.append(qid)
            questions[qid]["golds"].append((cid, hpath))

    out = []
    out.append("# Bundle corpus — gold review worksheet\n")
    out.append(
        "Generated from `eval/bundle-corpus/queries.csv` by "
        "`bin/eval/build-review-worksheet.py`. For each question, confirm the cited "
        "section(s) actually answer the query. Mark each: **OK**, **DROP**, or "
        "**FIX → <page>#<heading>**. RELATIONAL = the golds must answer it *together* "
        "(genuinely multi-hop). BOUNDARY = the answer must *straddle* the two sections.\n"
    )
    missing = 0
    for qid in order:
        q = questions[qid]
        out.append(f"\n---\n\n## {qid} — {q['cat']}\n")
        out.append(f"**Query:** {q['query']}\n")
        for n, (cid, hpath) in enumerate(q["golds"], 1):
            path = idx.get(cid)
            if not path:
                out.append(f"\n**Gold {n}:** ⚠ canonical_id `{cid}` not found\n")
                missing += 1
                continue
            page = os.path.basename(path)
            heading, body = extract_section(path, hpath)
            out.append(f"\n**Gold {n}:** `{page}` → `{hpath}`\n")
            if heading is None:
                out.append(f"> ⚠ section not found for heading-path `{hpath}` — check it.\n")
                missing += 1
                continue
            snippet = body if len(body) <= BODY_CHAR_CAP else body[:BODY_CHAR_CAP] + "\n… (truncated)"
            quoted = "\n".join("> " + l for l in snippet.splitlines()) if snippet else "> (section is empty)"
            out.append(f"> **{_text(heading)}**\n>\n{quoted}\n")
        out.append("\n- [ ] verdict: ______  (OK / DROP / FIX → ____)\n")

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"wrote {OUT_PATH}: {len(order)} questions, "
          f"{sum(len(q['golds']) for q in questions.values())} gold rows, "
          f"{missing} unresolved section(s)")


if __name__ == "__main__":
    main()

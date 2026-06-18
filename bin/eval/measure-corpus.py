#!/usr/bin/env python3
"""Section recall@k over the live /api/bundle for an arbitrary corpus CSV.

Same scoring as spike-api-bundle.py (gold = canonical_id + contiguous heading-path
sublist) but takes the corpus path as an arg and reports whatever categories are present.

Usage: python3 bin/eval/measure-corpus.py <base_url> <corpus.csv>
"""
import csv, json, re, sys, urllib.parse, urllib.request
from collections import defaultdict

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
CORPUS = sys.argv[2] if len(sys.argv) > 2 else "eval/bundle-corpus/queries.csv"
NS = [5, 12]


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def load():
    qs, order = {}, []
    for raw in open(CORPUS, encoding="utf-8"):
        ln = raw.strip()
        if not ln or ln.startswith("#") or ln.startswith("query_id,"):
            continue
        p = next(csv.reader([ln]))
        if len(p) < 5:
            continue
        qid, q, cat, cid, hp = p[:5]
        qs.setdefault(qid, {"q": q, "cat": cat, "golds": []})["golds"].append(
            (cid, tuple(norm(x) for x in hp.split(">") if x.strip())))
        if qid not in order:
            order.append(qid)
    return [(o, qs[o]) for o in order]


def fetch(q):
    u = BASE + "/api/bundle?" + urllib.parse.urlencode({"q": q})
    with urllib.request.urlopen(u, timeout=60) as r:
        d = json.load(r)
    return [(s.get("canonicalId", ""), tuple(norm(x) for x in (s.get("headingPath") or [])))
            for s in d.get("sections", [])]


def sub(g, s):
    n = len(g)
    return n > 0 and any(s[i:i + n] == g for i in range(0, len(s) - n + 1))


def main():
    corpus = load()
    cat = defaultdict(lambda: {n: [] for n in NS})
    ov = {n: [] for n in NS}
    misses = []
    for qid, qd in corpus:
        try:
            secs = fetch(qd["q"])
        except Exception as e:
            print(f"  {qid} fetch fail: {e}", flush=True)
            secs = []
        for cid, ghp in qd["golds"]:
            hit12 = any(s[0] == cid and sub(ghp, s[1]) for s in secs[:12])
            if not hit12:
                misses.append(qid)
            for n in NS:
                h = 1.0 if any(s[0] == cid and sub(ghp, s[1]) for s in secs[:n]) else 0.0
                cat[qd["cat"]][n].append(h)
                ov[n].append(h)
    avg = lambda x: round(sum(x) / len(x), 4) if x else 0.0
    print(f"corpus={CORPUS} ({len(corpus)} queries)")
    for c in sorted(cat):
        d = cat[c]
        print(f"  {c:12} recall@5={avg(d[5]):.4f} recall@12={avg(d[12]):.4f} (n={len(d[5])})")
    print(f"  {'OVERALL':12} recall@5={avg(ov[5]):.4f} recall@12={avg(ov[12]):.4f}")
    if misses:
        print(f"  @12 misses: {sorted(set(misses))}")


if __name__ == "__main__":
    main()

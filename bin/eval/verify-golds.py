#!/usr/bin/env python3
"""Verify every gold (canonical_id + heading-path sublist) in a corpus CSV resolves to a
real section in chunk-section-map.tsv. Fails loudly on any unresolvable gold.
Usage: python3 bin/eval/verify-golds.py eval/bundle-corpus/queries-identifiers.csv"""
import csv, json, re, sys
CORPUS = sys.argv[1]
MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"
def norm(s): return re.sub(r"\s+", " ", s).strip().lower()
sections = set()  # (canonical_id, tuple(heading_path))
for ln in open(MAP, encoding="utf-8"):
    p = ln.rstrip("\n").split("\t")
    if len(p) < 3: continue
    try: hp = tuple(norm(x) for x in json.loads(p[2]))
    except Exception: hp = ()
    sections.add((p[1], hp))
def sub(g, full):  # g is a contiguous sublist of full
    n = len(g)
    return n > 0 and any(full[i:i+n] == g for i in range(0, len(full)-n+1))
bad = []
seen = set()
for raw in open(CORPUS, encoding="utf-8"):
    ln = raw.strip()
    if not ln or ln.startswith("#") or ln.startswith("query_id,"): continue
    qid, q, cat, cid, hp = next(csv.reader([ln]))[:5]
    seen.add(qid)
    g = tuple(norm(x) for x in hp.split(">") if x.strip())
    if not any(c == cid and sub(g, full) for (c, full) in sections):
        bad.append((qid, cid, hp))
print(f"{len(seen)} queries; {len(bad)} unresolvable golds")
for b in bad: print("  UNRESOLVABLE:", b)
sys.exit(1 if bad else 0)

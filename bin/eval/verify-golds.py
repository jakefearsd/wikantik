#!/usr/bin/env python3
"""Verify every gold (canonical_id + heading-path sublist) in a corpus CSV resolves to a
real section in chunk-section-map.tsv. Fails loudly on any unresolvable gold.
Usage: python3 bin/eval/verify-golds.py eval/bundle-corpus/queries-identifiers.csv"""
import csv, sys
from bundle_eval_common import norm, sublist, load_chunk_section_map
CORPUS = sys.argv[1]
MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"
sections = set(load_chunk_section_map(MAP).values())  # (canonical_id, tuple(heading_path))
bad = []
seen = set()
for raw in open(CORPUS, encoding="utf-8"):
    ln = raw.strip()
    if not ln or ln.startswith("#") or ln.startswith("query_id,"): continue
    qid, q, cat, cid, hp = next(csv.reader([ln]))[:5]
    seen.add(qid)
    g = tuple(norm(x) for x in hp.split(">") if x.strip())
    if not any(c == cid and sublist(g, full) for (c, full) in sections):
        bad.append((qid, cid, hp))
print(f"{len(seen)} queries; {len(bad)} unresolvable golds")
for b in bad: print("  UNRESOLVABLE:", b)
sys.exit(1 if bad else 0)

#!/usr/bin/env python3
"""Offline fusion/grouping sweep for the chunk-level BM25 hybrid bundle source.

Fetches the raw dense + BM25 chunk rankings once per corpus query (via the gated
/api/bundle?debug=rankings endpoint), then sweeps fusion weights x rrf_k x top_k x
section-grouping strategy *in-process* — no server restart per combo. Section recall@k
is scored against the bundle-corpus gold (canonical_id + contiguous heading-path sublist),
identical to spike-api-bundle.py. Two live combos are re-derived as validation anchors.

Usage: python3 bin/eval/sweep-bm25-fusion.py [base_url]
Requires: eval/bm25-chunk-spike/chunk-section-map.tsv (chunkId \t canonical_id \t heading_path_json).
"""
import json, sys, urllib.parse, urllib.request
from bundle_eval_common import load_corpus, load_chunk_section_map, rrf_fuse, group_to_sections, sublist

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
CORPUS = "eval/bundle-corpus/queries.csv"
MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"
NS = [5, 12]
FETCH_K = 800


def fetch_rankings(query, k):
    url = BASE + "/api/bundle?" + urllib.parse.urlencode({"q": query, "debug": "rankings", "k": k})
    with urllib.request.urlopen(url, timeout=90) as r:
        d = json.load(r)
    dense = [(x["chunkId"], x["score"]) for x in d.get("dense", [])]
    bm25 = [(x["chunkId"], x["score"]) for x in d.get("bm25", [])]
    return dense, bm25


def evaluate(corpus, cache, cmap, combo):
    bm25_w, dense_w, rrf_k, dense_top, bm25_top, strat = combo
    cats = {}
    overall = {n: [] for n in NS}
    for q in corpus:
        dense, bm25 = cache[q["qid"]]
        scores = rrf_fuse(dense, bm25, dense_w, bm25_w, rrf_k, dense_top, bm25_top)
        secs = group_to_sections(scores, cmap, strat)
        c = cats.setdefault(q["cat"], {n: [] for n in NS})
        for n in NS:
            top = secs[:n]
            for gcid, ghp in q["golds"]:
                h = 1.0 if any(k[0] == gcid and sublist(ghp, k[1]) for k in top) else 0.0
                c[n].append(h); overall[n].append(h)
    avg = lambda x: round(sum(x) / len(x), 4) if x else 0.0
    return {"cats": {k: {n: avg(v[n]) for n in NS} for k, v in cats.items()},
            "overall": {n: avg(overall[n]) for n in NS}}


def main():
    cmap = load_chunk_section_map(MAP)
    corpus = load_corpus(CORPUS)
    print(f"map={len(cmap)} chunks, corpus={len(corpus)} queries; fetching rankings (k={FETCH_K})...", flush=True)
    cache = {}
    for q in corpus:
        try:
            cache[q["qid"]] = fetch_rankings(q["query"], FETCH_K)
        except Exception as e:
            print(f"  fetch fail {q['qid']}: {e}", flush=True)
            cache[q["qid"]] = ([], [])

    # ---- validation anchors (must reproduce the live numbers) ----
    print("\n=== validation (offline must match live) ===")
    for label, combo, exp in [
        ("dense-only  (bm25=0, dense=1, k60, dT=300, first)", (0.0, 1.0, 60, 300, 20, "first"), "live 0.706 @12"),
        ("live hybrid (bm25=1, dense=1.5, k60, dT=20, bT=20, first)", (1.0, 1.5, 60, 20, 20, "first"), "live 0.721 @12"),
    ]:
        r = evaluate(corpus, cache, cmap, combo)
        print(f"  {label}: overall @5={r['overall'][5]} @12={r['overall'][12]}   (expect {exp})")

    # ---- sweep: key idea is asymmetric truncation (keep dense fan-out, add small BM25 boost) ----
    strategies = ["first", "max", "sum"]
    bm25_ws = [0.5, 1.0, 1.5, 2.0]
    dense_ws = [1.0, 1.5]
    rrf_ks = [20, 60]
    dense_tops = [20, 100, 300, 800]
    bm25_tops = [10, 20, 50, 100]
    results = []
    for strat in strategies:
        for bw in bm25_ws:
            for dw in dense_ws:
                for k in rrf_ks:
                    for dt in dense_tops:
                        for bt in bm25_tops:
                            combo = (bw, dw, k, dt, bt, strat)
                            results.append((combo, evaluate(corpus, cache, cmap, combo)))
    print(f"\nswept {len(results)} combos  (baseline dense-only @12 = 0.706)\n")

    def show(title, keyfn, topn=10):
        print(f"=== top {topn} by {title} ===")
        print(f"{'bm25':>5}{'dense':>6}{'rrfK':>5}{'dT':>5}{'bT':>5}  {'group':<6}{'ov@5':>7}{'ov@12':>7}{'sim@12':>8}{'rel@12':>8}{'bnd@12':>8}")
        for combo, r in sorted(results, key=lambda cr: -keyfn(cr[1]))[:topn]:
            bw, dw, kk, dt, bt, st = combo
            cs = r["cats"]
            g = lambda c, n: cs.get(c, {}).get(n, 0.0)
            print(f"{bw:>5}{dw:>6}{kk:>5}{dt:>5}{bt:>5}  {st:<6}{r['overall'][5]:>7}{r['overall'][12]:>7}"
                  f"{g('SIMILARITY',12):>8}{g('RELATIONAL',12):>8}{g('BOUNDARY',12):>8}")
        print()

    show("OVERALL recall@12", lambda r: r["overall"][12])
    show("OVERALL recall@5", lambda r: r["overall"][5])
    show("SIMILARITY recall@12", lambda r: r["cats"].get("SIMILARITY", {}).get(12, 0.0))


if __name__ == "__main__":
    main()

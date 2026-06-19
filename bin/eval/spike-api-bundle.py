#!/usr/bin/env python3
"""
End-to-end bundle recall via the LIVE /api/bundle endpoint — the real deployed pipeline
(dense-chunk source + contextual embeddings + overlap chunks), no Python reimplementation.

For each corpus query, GET /api/bundle?q=<query>, parse the returned sections, and score
gold-section recall@N (a gold is covered when a returned section shares its canonical_id and
a heading-path that equals-or-extends the gold's). The bundle returns its top-N sections in
rank order, so recall@5/@12 = coverage within the first 5/12.

Usage: python3 bin/eval/spike-api-bundle.py [BASE_URL]   (default http://localhost:8080)
"""
import sys
from bundle_eval_common import load_corpus_pairs, fetch_bundle, section_hit

CSV_PATH = "eval/bundle-corpus/queries.csv"
BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080")
NS = [5, 12]

def main():
    corpus = load_corpus_pairs(CSV_PATH)
    cat = {}; tot = {n: [] for n in NS}; empty = 0
    for qi, q in enumerate(corpus, 1):
        try: secs = fetch_bundle(BASE, q["query"])
        except Exception as e: print(f"  q{qi} fail: {e}", flush=True); secs = []
        if not secs: empty += 1
        c = cat.setdefault(q["cat"], {n: [] for n in NS})
        for cid, hp in q["golds"]:
            for n in NS:
                hit = 1.0 if section_hit(cid, hp, secs[:n]) else 0.0
                c[n].append(hit); tot[n].append(hit)
        print(f"  ...{qi}/{len(corpus)} ({len(secs)} sections)", flush=True)
    def avg(x): return sum(x)/len(x) if x else 0.0
    print(f"\nLIVE /api/bundle end-to-end recall@N (deployed dense-chunk + contextual + overlap)\n")
    print(f"{'scope':<14}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(14+12*len(NS)))
    print(f"{'prior bundle':<14}{0.472:>12.3f}{0.685:>12.3f}   (contextual, page-gated cap=20)")
    for ct in ("SIMILARITY", "RELATIONAL", "BOUNDARY"):
        if ct in cat: print(f"{ct.lower():<14}"+"".join(f"{avg(cat[ct][n]):>12.3f}" for n in NS))
    print(f"{'OVERALL':<14}"+"".join(f"{avg(tot[n]):>12.3f}" for n in NS))
    print(f"\nempty bundles: {empty}/{len(corpus)}")

if __name__ == "__main__": main()

#!/usr/bin/env python3
"""
KG-rerank RELATIONAL-slice harness — section recall@k over the live /api/bundle
endpoint for the RELATIONAL slice (or a passed query-id slice). Used to A/B the
KG graph rerank: run once with the deployment at graph.boost=0 (label off) and
once at boost>0 (label on), then diff the JSON outputs. Scoring is identical to
spike-api-bundle.py (a gold is covered when a returned section shares the gold's
canonical_id and a heading-path that contains the gold's segments as a sublist).

Usage:
  python3 bin/eval/spike-kg-rerank.py <base_url> [--slice ids.txt] [--out out.json] [--label off|on]
  default base_url: http://localhost:8080 ; default slice: all RELATIONAL questions
"""
import json
import sys
from bundle_eval_common import load_corpus_pairs, fetch_bundle, section_hit

CSV_PATH = "eval/bundle-corpus/queries.csv"
NS = [5, 12]


def _parse_args(argv):
    base, slice_file, out_json, label = "http://localhost:8080", None, None, None
    i, pos = 0, []
    while i < len(argv):
        a = argv[i]
        if a == "--slice":
            slice_file = argv[i + 1]; i += 2
        elif a == "--out":
            out_json = argv[i + 1]; i += 2
        elif a == "--label":
            label = argv[i + 1]; i += 2
        else:
            pos.append(a); i += 1
    if pos:
        base = pos[0]
    return base, slice_file, out_json, label


def main():
    base, slice_file, out_json, label = _parse_args(sys.argv[1:])
    query_ids = None
    if slice_file:
        query_ids = [ln.strip() for ln in open(slice_file)
                     if ln.strip() and not ln.startswith("#")]
    # query_ids → those query ids; otherwise the full RELATIONAL slice.
    corpus = load_corpus_pairs(CSV_PATH, query_ids if query_ids else "RELATIONAL")
    tot = {n: [] for n in NS}
    per_query = []
    for q in corpus:
        try:
            secs = fetch_bundle(base, q["query"])
        except Exception as e:  # network/endpoint failure — count as a miss, keep going
            print(f"  {q['qid']} fail: {e}", flush=True)
            secs = []
        qrec = {"qid": q["qid"], "query": q["query"], "golds": []}
        for cid, hp in q["golds"]:
            h5, h12 = section_hit(cid, hp, secs[:5]), section_hit(cid, hp, secs[:12])
            tot[5].append(1.0 if h5 else 0.0)
            tot[12].append(1.0 if h12 else 0.0)
            qrec["golds"].append({"cid": cid, "hp": hp, "hit5": h5, "hit12": h12})
        per_query.append(qrec)

    def avg(x):
        return round(sum(x) / len(x), 4) if x else 0.0

    result = {"label": label, "base": base, "slice_size": len(corpus),
              "recall_at_5": avg(tot[5]), "recall_at_12": avg(tot[12]),
              "per_query": per_query}
    print(f"RELATIONAL slice ({len(corpus)} q, label={label}): "
          f"recall@5={result['recall_at_5']} recall@12={result['recall_at_12']}")
    if out_json:
        with open(out_json, "w") as f:
            json.dump(result, f, indent=2)
        print(f"wrote {out_json}")


if __name__ == "__main__":
    main()

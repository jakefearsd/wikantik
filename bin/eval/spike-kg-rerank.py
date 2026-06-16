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
import csv
import json
import re
import sys
import urllib.parse
import urllib.request

CSV_PATH = "eval/bundle-corpus/queries.csv"
NS = [5, 12]


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def load_slice(csv_path, query_ids=None):
    """Rows {qid, query, cat, golds:[(cid, heading_path_str)]}.

    query_ids=None → the full RELATIONAL slice; otherwise only the named query ids.
    """
    want = set(query_ids) if query_ids else None
    qs, order = {}, []
    for raw in open(csv_path, encoding="utf-8"):
        ln = raw.strip()
        if not ln or ln.startswith("#") or ln.startswith("query_id,"):
            continue
        p = next(csv.reader([ln]))
        if len(p) < 5:
            continue
        qid, query, cat, cid, hp = p[:5]
        keep = (qid in want) if want is not None else (cat == "RELATIONAL")
        if not keep:
            continue
        if qid not in qs:
            qs[qid] = {"qid": qid, "query": query, "cat": cat, "golds": []}
            order.append(qid)
        qs[qid]["golds"].append((cid, hp))
    return [qs[q] for q in order]


def prefix(gold, sec):
    n = len(gold)
    return n > 0 and any(sec[i:i + n] == gold for i in range(0, len(sec) - n + 1))


def section_hit(gold_cid, gold_hp_str, sections):
    """sections = [(canonicalId, [normalised heading-path segments])]."""
    gold = [norm(x) for x in gold_hp_str.split(">") if x.strip()]
    return any(s[0] == gold_cid and prefix(gold, s[1]) for s in sections)


def fetch_bundle(base, query):
    url = base.rstrip("/") + "/api/bundle?" + urllib.parse.urlencode({"q": query})
    with urllib.request.urlopen(url, timeout=60) as r:
        d = json.load(r)
    out = []
    for s in d.get("sections", []):
        out.append((s.get("canonicalId", ""),
                    [norm(x) for x in (s.get("headingPath") or []) if x and x.strip()]))
    return out


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
    corpus = load_slice(CSV_PATH, query_ids)
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

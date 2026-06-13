#!/usr/bin/env python3
"""
Phase-1 leverage diagnostic: "how hard is the 0.42 section recall to move?"

For each gold (query, section), rank the gold *page's* chunks by dense similarity to
the query (qwen3) and find the rank of the chunk that lies in the gold section. Then
report section recall @ k chunks-per-page for k = 1,3,5,10,20,all. The shape of that
curve answers the question:

  - steep climb 0.42@5 -> ~0.9@20  => CHEAP to move (just return more / the parent section)
  - flat (still low at @all)        => HARD (the gold chunk ranks poorly / chunking issue)

Also reports the "unreachable" floor: golds whose section has NO chunk that any depth
would surface (heading mismatch or section not chunked) — the part no bundle depth fixes.

Requires PG* env vars (host/port/user/db/password) and the live qwen3 embedder.
Usage:  (export PG* …) ; python3 bin/eval/leverage-curve.py
"""
import csv
import glob
import json
import os
import re
import struct
import subprocess
import urllib.request

PAGES = "docs/wikantik-pages"
CSV_PATH = "eval/bundle-corpus/queries.csv"
MODEL_CODE = "qwen3-embedding-0.6b"
EMBED_TAG = "qwen3-embedding:0.6b"
EMBED_URL = "http://inference.jakefear.com:11434/api/embeddings"
KS = [1, 3, 5, 10, 20, 9999]


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def slug_index():
    s2c = {}
    for path in glob.glob(os.path.join(PAGES, "**", "*.md"), recursive=True):
        try:
            head = open(path, encoding="utf-8").read(4000)
        except OSError:
            continue
        m = re.search(r"^canonical_id:\s*(\S+)", head, re.M)
        if m:
            s2c.setdefault(os.path.basename(path)[:-3], m.group(1).strip())
    return {cid: slug for slug, cid in s2c.items()}  # cid -> slug


def load_corpus():
    qs, order = {}, []
    for raw in open(CSV_PATH, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("query_id,"):
            continue
        p = next(csv.reader([line]))
        if len(p) < 5:
            continue
        qid, query, cat, cid, hp = p[:5]
        if qid not in qs:
            qs[qid] = {"qid": qid, "query": query, "cat": cat, "golds": []}
            order.append(qid)
        qs[qid]["golds"].append((cid, hp))
    return [qs[q] for q in order]


def fetch_chunks(slugs):
    """page_name -> list of (heading_path_segments, vec[list of float])."""
    arr = "{" + ",".join('"%s"' % s.replace('"', '') for s in slugs) + "}"
    sql = (
        "SELECT c.page_name, array_to_string(c.heading_path,'>'), encode(e.vec,'hex') "
        "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
        "WHERE e.model_code='%s' AND c.page_name = ANY('%s'::text[]) "
        "ORDER BY c.page_name, c.chunk_index;" % (MODEL_CODE, arr)
    )
    out = subprocess.run(["psql", "-tA", "-F", "\t", "-c", sql],
                         capture_output=True, text=True, check=True).stdout
    pages = {}
    for line in out.splitlines():
        if not line.strip():
            continue
        page, hp, vhex = line.split("\t")
        vec = list(struct.unpack("<%df" % (len(vhex) // 8), bytes.fromhex(vhex)))
        segs = [norm(x) for x in hp.split(">") if x.strip()]
        pages.setdefault(page, []).append((segs, vec))
    return pages


_qcache = {}
def embed(query):
    if query in _qcache:
        return _qcache[query]
    req = urllib.request.Request(EMBED_URL,
        data=json.dumps({"model": EMBED_TAG, "prompt": query}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        v = json.load(r)["embedding"]
    _qcache[query] = v
    return v


def cosine(a, b):
    dot = sa = sb = 0.0
    for x, y in zip(a, b):
        dot += x * y; sa += x * x; sb += y * y
    return dot / ((sa ** 0.5) * (sb ** 0.5) + 1e-12)


def is_sublist(needle, hay):
    if not needle:
        return False
    n = len(needle)
    return any(hay[i:i + n] == needle for i in range(0, len(hay) - n + 1))


def gold_rank(query_vec, chunks, gold_hp):
    """Best (1-based) rank among the page's chunks whose heading_path contains the
    gold section; None if no chunk lies in the gold section."""
    gold = [norm(x) for x in gold_hp.split(">") if x.strip()]
    scored = sorted(((cosine(query_vec, vec), segs) for segs, vec in chunks),
                    key=lambda t: -t[0])
    for rank, (_, segs) in enumerate(scored, 1):
        if is_sublist(gold, segs):
            return rank
    return None


def main():
    cid2slug = slug_index()
    corpus = load_corpus()
    slugs = {cid2slug[cid] for q in corpus for cid, _ in q["golds"] if cid in cid2slug}
    pages = fetch_chunks(sorted(slugs))

    cats = {}
    ranks_all = []
    for q in corpus:
        qv = embed(q["query"])
        for cid, hp in q["golds"]:
            slug = cid2slug.get(cid)
            chunks = pages.get(slug, [])
            r = gold_rank(qv, chunks, hp) if chunks else None
            cats.setdefault(q["cat"], []).append(r)
            ranks_all.append(r)

    def recall_at(ranks, k):
        return sum(1 for r in ranks if r is not None and r <= k) / len(ranks)

    def line(name, ranks):
        unreachable = sum(1 for r in ranks if r is None) / len(ranks)
        cells = "".join(f"{recall_at(ranks, k):>8.3f}" for k in KS)
        return f"{name:<12}{len(ranks):>4}{cells}{unreachable:>11.3f}"

    hdr = "".join(f"{('@'+str(k) if k < 9999 else '@all'):>8}" for k in KS)
    print("Section recall @ k chunks-per-page (gold section's chunk ranks <= k on its page)\n")
    print(f"{'category':<12}{'n':>4}{hdr}{'unreachable':>11}")
    print("-" * (16 + 8 * len(KS) + 11))
    for cat in ("SIMILARITY", "RELATIONAL", "BOUNDARY"):
        if cat in cats:
            print(line(cat, cats[cat]))
    print("-" * (16 + 8 * len(KS) + 11))
    print(line("OVERALL", ranks_all))
    print("\n@5 is the current production cap. 'unreachable' = section has no chunk any depth surfaces.")


if __name__ == "__main__":
    main()

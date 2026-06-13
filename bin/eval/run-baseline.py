#!/usr/bin/env python3
"""
Phase-0 baseline: score the bundle evaluation corpus against the LIVE retrieval
stack (/api/search on the running deployment) and report context recall + precision,
overall and per category.

Section-level recall is measured by text containment: /api/search returns each
result page's top contributing-chunk texts, and a chunk drawn from the gold section
is (by construction) a substring of that section's text. So "did a chunk from the
gold section surface in the bundle?" is a faithful, heading-path-free coverage test.

Reports both:
  - section recall  — gold *section* covered (the bundle metric)
  - page recall     — gold *page* retrieved at all (looser, for comparison)
  - precision@5     — fraction of the top-5 result pages that are a gold page

Usage:  python3 bin/eval/run-baseline.py [SEARCH_URL]
"""
import csv
import glob
import json
import os
import re
import sys
import urllib.parse
import urllib.request

PAGES = "docs/wikantik-pages"
CSV_PATH = "eval/bundle-corpus/queries.csv"
SEARCH = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api/search"
PREFIX = 120  # chars of a chunk that must fall inside the gold section to count as covered
PREC_K = 5


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


# ---- page index: canonical_id <-> slug, and section-text extraction ----
def build_index():
    cid2path, slug2cid = {}, {}
    for path in glob.glob(os.path.join(PAGES, "**", "*.md"), recursive=True):
        try:
            head = open(path, encoding="utf-8").read(4000)
        except OSError:
            continue
        m = re.search(r"^canonical_id:\s*(\S+)", head, re.M)
        if not m:
            continue
        cid = m.group(1).strip()
        slug = os.path.basename(path)[:-3]
        cid2path.setdefault(cid, path)
        slug2cid.setdefault(slug, cid)
    return cid2path, slug2cid


def _level(line):
    m = re.match(r"^(#{1,6})\s", line)
    return len(m.group(1)) if m else None


def section_text(path, heading_path):
    target = norm([s for s in heading_path.split(">") if s.strip()][-1])
    lines = open(path, encoding="utf-8").readlines()
    start = lvl = None
    for i, line in enumerate(lines):
        l = _level(line)
        if l is None:
            continue
        ht = norm(re.sub(r"^#{1,6}\s+", "", line))
        if ht == target or ht.startswith(target) or target.startswith(ht):
            start, lvl = i, l
            break
    if start is None:
        return ""
    body = []
    for j in range(start + 1, len(lines)):
        l = _level(lines[j])
        if l is not None and l <= lvl:
            break
        body.append(lines[j])
    return "".join(body)


def search(query):
    url = SEARCH + "?" + urllib.parse.urlencode({"q": query})
    with urllib.request.urlopen(url, timeout=40) as r:
        d = json.load(r)
    out = []
    for p in d.get("results", []):
        out.append((p.get("name", ""), [c for c in (p.get("contexts") or []) if isinstance(c, str)]))
    return out


def load_corpus():
    qs, order = {}, []
    for raw in open(CSV_PATH, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("query_id,"):
            continue
        parts = next(csv.reader([line]))
        if len(parts) < 5:
            continue
        qid, query, cat, cid, hp = parts[:5]
        if qid not in qs:
            qs[qid] = {"query": query, "cat": cat, "golds": []}
            order.append(qid)
        qs[qid]["golds"].append((cid, hp))
    return [qs[q] | {"qid": q} for q in order]


def main():
    cid2path, slug2cid = build_index()
    corpus = load_corpus()

    cats = {}
    tot = {"sec": [], "page": [], "prec": []}
    for q in corpus:
        results = search(q["query"])
        result_cids = [slug2cid.get(name) for name, _ in results]
        # page-level: distinct gold pages retrieved
        gold_pages = {cid for cid, _ in q["golds"]}
        page_rec = len([g for g in gold_pages if g in result_cids]) / len(gold_pages)
        # precision@K: top-K result pages that are a gold page
        topk = result_cids[:PREC_K]
        prec = (len([c for c in topk if c in gold_pages]) / PREC_K) if topk else 0.0
        # section-level: gold section covered by a contributing chunk's text
        covered = 0
        ctx_by_cid = {}
        for name, ctxs in results:
            ctx_by_cid.setdefault(slug2cid.get(name), []).extend(ctxs)
        for cid, hp in q["golds"]:
            sec = norm(section_text(cid2path[cid], hp)) if cid in cid2path else ""
            hit = False
            for ctx in ctx_by_cid.get(cid, []):
                probe = norm(ctx)[:PREFIX]
                if probe and probe in sec:
                    hit = True
                    break
            covered += 1 if hit else 0
        sec_rec = covered / len(q["golds"])

        c = cats.setdefault(q["cat"], {"sec": [], "page": [], "prec": []})
        for k, v in (("sec", sec_rec), ("page", page_rec), ("prec", prec)):
            c[k].append(v)
            tot[k].append(v)

    def avg(xs):
        return sum(xs) / len(xs) if xs else 0.0

    print(f"Phase-0 baseline — live /api/search  (n={len(corpus)} questions)\n")
    print(f"{'category':<12}{'n':>4}{'sec_recall':>12}{'page_recall':>13}{'prec@5':>9}")
    print("-" * 50)
    for cat in ("SIMILARITY", "RELATIONAL", "BOUNDARY"):
        c = cats.get(cat)
        if not c:
            continue
        print(f"{cat:<12}{len(c['sec']):>4}{avg(c['sec']):>12.3f}{avg(c['page']):>13.3f}{avg(c['prec']):>9.3f}")
    print("-" * 50)
    print(f"{'OVERALL':<12}{len(tot['sec']):>4}{avg(tot['sec']):>12.3f}{avg(tot['page']):>13.3f}{avg(tot['prec']):>9.3f}")


if __name__ == "__main__":
    main()

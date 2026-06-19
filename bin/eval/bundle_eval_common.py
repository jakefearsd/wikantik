#!/usr/bin/env python3
"""Shared helpers for the bundle-retrieval eval harnesses in bin/eval/.

Extracted to kill the copy-paste the 2026-06-19 code-quality pass flagged
(norm 18x, the contiguous-sublist match 20x, load_corpus 18x, fetch_bundle 4x,
load_chunk_section_map 2x, RRF fuse 2x, group_to_sections 2x, cid2slug 13x).

Every function here reproduces the behaviour the harnesses already relied on:
- gold/section heading-paths are matched as *contiguous sublists* (sublist),
  list/tuple-agnostic so callers can mix the two freely;
- a "gold is covered" when a returned section shares its canonical_id AND its
  heading-path is matched by sublist.
"""
import csv
import glob
import json
import os
import re
import urllib.parse
import urllib.request

PAGES_DIR = "docs/wikantik-pages"
DEFAULT_MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"


def norm(s):
    """Whitespace-collapse + trim + lowercase — the canonical token normaliser."""
    return re.sub(r"\s+", " ", s).strip().lower()


def sublist(gold, full):
    """True iff `gold` is a non-empty contiguous sublist of `full`.

    Both operands are coerced to lists so a tuple gold matches a list section (and
    vice-versa) — the harnesses historically used whichever type was handy.
    """
    g, f = list(gold), list(full)
    n = len(g)
    return n > 0 and any(f[i:i + n] == g for i in range(0, len(f) - n + 1))


def _corpus_rows(path, cat_filter, normalise_golds):
    """Internal: parse the corpus CSV, applying the cat/qid filter and gold form.

    cat_filter: None=all rows; a str=keep only that category; a collection=keep
    only those query ids. normalise_golds: True→(cid, heading_path_tuple);
    False→(cid, raw_heading_path_string).
    """
    want_cat = cat_filter if isinstance(cat_filter, str) else None
    want_qids = None if (cat_filter is None or want_cat is not None) else set(cat_filter)
    qs, order = {}, []
    for raw in open(path, encoding="utf-8"):
        ln = raw.strip()
        if not ln or ln.startswith("#") or ln.startswith("query_id,"):
            continue
        p = next(csv.reader([ln]))
        if len(p) < 5:
            continue
        qid, query, cat, cid, hp = p[:5]
        if want_cat is not None and cat != want_cat:
            continue
        if want_qids is not None and qid not in want_qids:
            continue
        if qid not in qs:
            qs[qid] = {"qid": qid, "query": query, "cat": cat, "golds": []}
            order.append(qid)
        gold = tuple(norm(x) for x in hp.split(">") if x.strip()) if normalise_golds else hp
        qs[qid]["golds"].append((cid, gold))
    return [qs[q] for q in order]


def load_corpus(path, cat_filter=None):
    """Corpus rows with golds normalised to (canonical_id, heading_path_tuple)."""
    return _corpus_rows(path, cat_filter, normalise_golds=True)


def load_corpus_pairs(path, cat_filter=None):
    """Corpus rows with golds as raw (canonical_id, heading_path_string) — the form
    section_hit() expects (it splits/normalises the string itself)."""
    return _corpus_rows(path, cat_filter, normalise_golds=False)


def load_chunk_section_map(path=DEFAULT_MAP):
    """chunkId -> (canonical_id, heading_path_tuple) from chunk-section-map.tsv."""
    m = {}
    for ln in open(path, encoding="utf-8"):
        p = ln.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        try:
            hp = tuple(norm(x) for x in json.loads(p[2]))
        except Exception:
            hp = ()
        m[p[0]] = (p[1], hp)
    return m


def fetch_bundle(base, query, timeout=60):
    """GET /api/bundle?q=… and return [(canonicalId, [normalised heading-path])]."""
    url = base.rstrip("/") + "/api/bundle?" + urllib.parse.urlencode({"q": query})
    with urllib.request.urlopen(url, timeout=timeout) as r:
        d = json.load(r)
    return [(s.get("canonicalId", ""),
             [norm(x) for x in (s.get("headingPath") or []) if x and x.strip()])
            for s in d.get("sections", [])]


def section_hit(gold_cid, gold_hp_str, sections):
    """Covered iff some section in `sections` shares gold_cid AND its heading-path
    sublist-matches the gold (gold given as the raw '>'-joined string)."""
    gold = [norm(x) for x in gold_hp_str.split(">") if x.strip()]
    return any(s[0] == gold_cid and sublist(gold, s[1]) for s in sections)


def recall_at(sections, golds, n):
    """Per-gold hits (1.0/0.0) over the top-n sections. golds = [(cid, hp_tuple)]."""
    top = sections[:n]
    return [1.0 if any(s[0] == gc and sublist(gh, s[1]) for s in top) else 0.0
            for gc, gh in golds]


def rrf_fuse(dense, bm25, dense_w, bm25_w, rrf_k, dense_top, bm25_top=None):
    """Weighted RRF over two ranked [(chunkId, score)] lists → {chunkId: fused_score}.

    Per list, the item at rank r (0-based) contributes weight/(rrf_k + r + 1) over its
    first `*_top` entries. A zero-weight list contributes nothing. bm25_top defaults to
    dense_top (symmetric truncation).
    """
    if bm25_top is None:
        bm25_top = dense_top
    s = {}
    if dense_w > 0:
        for rank, (cid, _) in enumerate(dense[:dense_top]):
            s[cid] = s.get(cid, 0.0) + dense_w / (rrf_k + rank + 1)
    if bm25_w > 0:
        for rank, (cid, _) in enumerate(bm25[:bm25_top]):
            s[cid] = s.get(cid, 0.0) + bm25_w / (rrf_k + rank + 1)
    return s


def group_to_sections(scored, cmap, strategy="first", with_scores=False):
    """Group scored chunks to ordered section keys under a grouping strategy.

    scored: {chunkId: score} or [(chunkId, score)]. cmap: chunkId -> section key.
    strategy "first" keeps the first (best-fused) chunk per section in fused order;
    "max"/"sum"/"sum3" aggregate each section's chunk scores. Returns section keys, or
    [(key, score)] when with_scores=True.
    """
    items = scored.items() if isinstance(scored, dict) else scored
    ordered = sorted(items, key=lambda kv: -kv[1])
    if strategy == "first":
        seen, out = set(), []
        for cid, sc in ordered:
            key = cmap.get(cid)
            if key is None or key in seen:
                continue
            seen.add(key)
            out.append((key, sc) if with_scores else key)
        return out
    agg = {}
    for cid, sc in ordered:
        key = cmap.get(cid)
        if key is None:
            continue
        agg.setdefault(key, []).append(sc)

    def score(vals):
        if strategy == "sum":
            return sum(vals)
        if strategy == "sum3":
            return sum(sorted(vals, reverse=True)[:3])
        return max(vals)   # "max" and the default

    ranked = sorted(agg.items(), key=lambda kv: -score(kv[1]))
    return [(k, score(v)) for k, v in ranked] if with_scores else [k for k, _ in ranked]


def cid2slug(pages_dir=PAGES_DIR):
    """canonical_id -> page slug, read from the frontmatter of every page .md."""
    s2c = {}
    for p in glob.glob(os.path.join(pages_dir, "**", "*.md"), recursive=True):
        try:
            head = open(p, encoding="utf-8").read(4000)
        except OSError:
            continue
        m = re.search(r"^canonical_id:\s*(\S+)", head, re.M)
        if m:
            s2c.setdefault(os.path.basename(p)[:-3], m.group(1).strip())
    return {c: s for s, c in s2c.items()}

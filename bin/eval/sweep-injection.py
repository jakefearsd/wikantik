#!/usr/bin/env python3
"""Offline sweep of the lexical-injection knobs over the natural + identifier corpora.
Fetches dense + bm25_standard + bm25_code rankings once per query via /api/bundle?debug=rankings,
reconstructs the shipped dense-heavy base, then sweeps J/C/alpha/N/P. No restart per combo.
Run with the server in inject-ENABLED mode (so the debug endpoint exposes bm25_code).
Usage: python3 bin/eval/sweep-injection.py [base_url]"""
import csv, json, re, sys, urllib.parse, urllib.request
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
MAP = "eval/bm25-chunk-spike/chunk-section-map.tsv"
CORPORA = {"natural": "eval/bundle-corpus/queries.csv", "identifier": "eval/bundle-corpus/queries-identifiers.csv"}
NS = [5, 12]; FETCH_K = 300
# base fusion (shipped): dense_w=1.5, bm25_w=0.5, rrfK=20, truncate=20
BASE_DW, BASE_BW, BASE_K, BASE_TR = 1.5, 0.5, 20, 20

def norm(s): return re.sub(r"\s+", " ", s).strip().lower()
def load_map():
    m = {}
    for ln in open(MAP, encoding="utf-8"):
        p = ln.rstrip("\n").split("\t")
        if len(p) < 3: continue
        try: hp = tuple(norm(x) for x in json.loads(p[2]))
        except Exception: hp = ()
        m[p[0]] = (p[1], hp)        # chunkId -> (canonical, heading_path)
    return m
def load(path):
    qs, order = {}, []
    for raw in open(path, encoding="utf-8"):
        ln = raw.strip()
        if not ln or ln.startswith("#") or ln.startswith("query_id,"): continue
        qid, q, cat, cid, hp = next(csv.reader([ln]))[:5]
        qs.setdefault(qid, {"q": q, "golds": []})["golds"].append((cid, tuple(norm(x) for x in hp.split(">") if x.strip())))
        if qid not in order: order.append(qid)
    return [(o, qs[o]) for o in order]
def fetch(q):
    u = BASE + "/api/bundle?" + urllib.parse.urlencode({"q": q, "debug": "rankings", "k": FETCH_K})
    with urllib.request.urlopen(u, timeout=90) as r: d = json.load(r)
    g = lambda key: [(x["chunkId"], x["score"]) for x in d.get(key, [])]
    return g("dense"), g("bm25_standard"), g("bm25_code")

CAMEL = re.compile(r"[a-z][A-Za-z]*[A-Z][A-Za-z]*"); SNAKE = re.compile(r"[A-Za-z0-9]+_[A-Za-z0-9_]+")
DOTTED = re.compile(r"[A-Za-z0-9]+\.[A-Za-z0-9]+\.[A-Za-z0-9.]+")
def has_symbol(q): return bool(CAMEL.search(q) or SNAKE.search(q) or DOTTED.search(q))

def rrf(dense, bm25, dw, bw, k, tr):
    s = {}
    for r, (c, _) in enumerate(dense[:tr]): s[c] = s.get(c, 0) + dw / (k + r + 1)
    for r, (c, _) in enumerate(bm25[:tr]): s[c] = s.get(c, 0) + bw / (k + r + 1)
    return s
def to_sections(scored, cmap):   # dict{chunkId:score} OR [(chunkId,score)] -> [(sectionKey, score)] first per section
    items = scored.items() if isinstance(scored, dict) else scored
    ordered = sorted(items, key=lambda kv: -kv[1])
    seen, out = set(), []
    for cid, sc in ordered:
        key = cmap.get(cid)
        if key is None or key in seen: continue
        seen.add(key); out.append((key, sc))
    return out
def base_sections(dense, bm25std, cmap):
    return [k for k, _ in to_sections(rrf(dense, bm25std, BASE_DW, BASE_BW, BASE_K, BASE_TR), cmap)]
def dense_rank_by_section(dense, cmap, scan_k):
    rank = {}
    for r, (cid, _) in enumerate(dense[:scan_k]):
        key = cmap.get(cid)
        if key and key not in rank: rank[key] = r
    return rank

def inject(base_keys, bm25code, cmap, drank, has_sym, cfg):
    J, C, A, N, P, JB, AB, SCAN, RS = cfg
    if RS and not has_sym: return base_keys      # require_symbol: skip symbol-free queries entirely
    rankmax, alpha = (JB, AB) if has_sym else (J, A)
    code_secs = to_sections(bm25code, cmap)        # [(key, score)] best chunk per section, bm25 order
    if not code_secs: return base_keys
    top = code_secs[0][1]
    picked, basek = [], set(base_keys)
    for r, (key, score) in enumerate(code_secs):
        if len(picked) >= N: break
        if r >= rankmax: continue
        if score < alpha * top: continue
        if drank.get(key, SCAN) <= C: continue
        if key in basek: continue
        basek.add(key); picked.append(key)
    if not picked: return base_keys
    out = list(base_keys); pos = max(0, min(P, len(out)))
    out[pos:pos] = picked
    return out

def sub(g, full): n = len(g); return n > 0 and any(full[i:i+n] == g for i in range(0, len(full)-n+1))
def recall(secs, golds, n):
    top = secs[:n]
    return [1.0 if any(k[0] == gc and sub(gh, k[1]) for k in top) else 0.0 for gc, gh in golds]

def main():
    cmap = load_map()
    cache = {}  # corpus -> [(qid, qd, base_keys, bm25code, dense, has_sym)]
    for name, path in CORPORA.items():
        rows = []
        for qid, qd in load(path):
            dense, bm25std, bm25code = fetch(qd["q"])
            rows.append((qid, qd, base_sections(dense, bm25std, cmap), bm25code, dense, has_symbol(qd["q"])))
        cache[name] = rows
    def evalu(name, cfg):
        ov = {n: [] for n in NS}
        for qid, qd, basek, bm25code, dense, sym in cache[name]:
            drank = dense_rank_by_section(dense, cmap, cfg[7])
            secs = inject(basek, bm25code, cmap, drank, sym, cfg)
            for n in NS: ov[n].extend(recall(secs, qd["golds"], n))
        return {n: round(sum(ov[n]) / len(ov[n]), 4) for n in NS}
    OFF = (20, 50, 0.3, 0, 3, 50, 0.1, 300, False)   # N=0 == inject off
    base_nat, base_ide = evalu("natural", OFF), evalu("identifier", OFF)
    print(f"baseline (no inject):  natural {base_nat}  identifier {base_ide}")
    best = []
    for RS in (False, True):
      for J in (10, 20, 50):
        for C in (20, 50, 100):
          for A in (0.2, 0.3, 0.5):
            for N in (1, 2, 3):
              for P in (1, 3, 6):
                cfg = (J, C, A, N, P, max(J, 50), min(A, 0.1), 300, RS)
                best.append((cfg, evalu("natural", cfg), evalu("identifier", cfg)))
    hdr = f"{'J':>3}{'C':>4}{'a':>5}{'N':>3}{'P':>3}{'reqSym':>7}  {'nat@5':>7}{'nat@12':>7}{'ide@5':>7}{'ide@12':>7}{'sum@12':>8}"
    def show(title, rows):
        print(f"\n{title}")
        print(hdr)
        for cfg, nat, ide in rows:
            print(f"{cfg[0]:>3}{cfg[1]:>4}{cfg[2]:>5}{cfg[3]:>3}{cfg[4]:>3}{str(cfg[8]):>7}  {nat[5]:>7}{nat[12]:>7}{ide[5]:>7}{ide[12]:>7}{nat[12]+ide[12]:>8.4f}")
    # best by combined sum @12 (the user's "maximize combined" objective)
    by_sum = sorted(best, key=lambda b: -(b[1][12] + b[2][12]))
    show(f"baseline sum@12 = {base_nat[12]+base_ide[12]:.4f}; top 8 by combined (nat@12 + ide@12):", by_sum[:8])
    # best identifier@12 ignoring the natural floor (to see the tradeoff frontier)
    by_ide = sorted(best, key=lambda b: (-b[2][12], b[1][12] - b[2][12]))
    show("top 8 by identifier@12 (any natural cost):", by_ide[:8])
    # symbol-gated only: best by identifier@12 with natural preserved
    rs = [b for b in best if b[0][8]]
    rs.sort(key=lambda b: (-b[1][12], -b[2][12]))   # natural-first (preserve it), then identifier
    show("reqSym=True only, top 8 by natural@12 then identifier@12:", rs[:8])
    rs.sort(key=lambda b: (-b[2][12], -b[1][12]))
    show("reqSym=True only, top 8 by identifier@12:", rs[:8])

if __name__ == "__main__":
    main()

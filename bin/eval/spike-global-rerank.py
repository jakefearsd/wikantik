#!/usr/bin/env python3
"""
Production-frame rerank spike: does the rerank lift survive a realistic GLOBAL candidate set?

Per query: real /api/search candidate pages -> all their sections -> dense-rank globally,
take a top-K shortlist -> LLM listwise rerank the shortlist -> recall@N. Reports the reranked
order vs the dense-only shortlist (no rerank) at bundle sizes N, plus latency. This is the
honest production scenario (unlike the per-page frame, the reranker must surface the gold
section against sections from ALL retrieved pages).

Usage: python3 bin/eval/spike-global-rerank.py [MODEL] [SHORTLIST_K]
       MODEL default gemma4:e4b ; SHORTLIST_K default 30
"""
import csv, glob, json, os, re, struct, subprocess, sys, time, urllib.parse, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"; EMBED_TAG="qwen3-embedding:0.6b"
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
GEN_URL="http://inference.jakefear.com:11434/api/generate"
SEARCH="http://localhost:8080/api/search"
LLM=(sys.argv[1] if len(sys.argv)>1 else "gemma4:e4b")
K=int(sys.argv[2]) if len(sys.argv)>2 else 30
NS=[3,5,8,12]; PAGES_PER_Q=8; SNIP=240; PER_PAGE_S=5

def norm(s): return re.sub(r"\s+"," ",s).strip().lower()
def cid2slug():
    s2c={}
    for p in glob.glob(os.path.join(PAGES,"**","*.md"),recursive=True):
        try: head=open(p,encoding="utf-8").read(4000)
        except OSError: continue
        m=re.search(r"^canonical_id:\s*(\S+)",head,re.M)
        if m: s2c.setdefault(os.path.basename(p)[:-3],m.group(1).strip())
    return {c:s for s,c in s2c.items()}
def load_corpus():
    qs,order={},[]
    for raw in open(CSV_PATH,encoding="utf-8"):
        ln=raw.strip()
        if not ln or ln.startswith("#") or ln.startswith("query_id,"): continue
        p=next(csv.reader([ln]))
        if len(p)<5: continue
        qid,query,cat,cid,hp=p[:5]
        if qid not in qs: qs[qid]={"qid":qid,"query":query,"cat":cat,"golds":[]}; order.append(qid)
        qs[qid]["golds"].append((cid,hp))
    return [qs[q] for q in order]
def search_pages(query):
    url=SEARCH+"?"+urllib.parse.urlencode({"q":query})
    with urllib.request.urlopen(url,timeout=40) as r: d=json.load(r)
    return [p.get("name","") for p in d.get("results",[])][:PAGES_PER_Q]
_emb={}
def embed(q):
    if q in _emb: return _emb[q]
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":EMBED_TAG,"prompt":q}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=60) as r: v=json.load(r)["embedding"]
    _emb[q]=v; return v
def cos(a,b):
    dot=sa=sb=0.0
    for x,y in zip(a,b): dot+=x*y; sa+=x*x; sb+=y*y
    return dot/((sa**.5)*(sb**.5)+1e-12)
def fetch(slugs):
    arr="{"+",".join('"%s"'%s.replace('"','') for s in slugs)+"}"
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), encode(e.vec,'hex'), "
         "translate(c.text, chr(10)||chr(13)||chr(9), '   ') "
         "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
         "WHERE e.model_code='%s' AND c.page_name=ANY('%s'::text[]) ORDER BY c.page_name,c.chunk_index;"
         %(MODEL_CODE,arr))
    out=subprocess.run(["psql","-tA","-F","\t","-c",sql],capture_output=True,text=True,check=True).stdout
    pages={}
    for ln in out.splitlines():
        if not ln.strip(): continue
        pr=ln.split("\t")
        if len(pr)<4: continue
        page,hp,vh,txt=pr
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        vec=struct.unpack("<%df"%(len(vh)//8),bytes.fromhex(vh))
        pages.setdefault(page,{}).setdefault(segs,{"vecs":[],"txt":[]})
        pages[page][segs]["vecs"].append(vec); pages[page][segs]["txt"].append(txt)
    return pages
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))
_lat=[]
def llm_rerank(query,items):  # items: list of (page,segs,text)
    lines=[f"{i}. [{' > '.join(s)}] {t}" for i,(_,s,t) in enumerate(items,1)]
    prompt=("Query: "+query+"\n\nRank the passages below from MOST to LEAST relevant to the query. "
            "Output ONLY passage numbers separated by commas, best first.\n\nPassages:\n"+"\n".join(lines))
    body={"model":LLM,"prompt":prompt,"stream":False,"think":False,"options":{"temperature":0}}
    req=urllib.request.Request(GEN_URL,data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    t0=time.time()
    with urllib.request.urlopen(req,timeout=180) as r: resp=json.load(r)["response"]
    _lat.append(time.time()-t0)
    order=[]
    for tok in re.findall(r"\d+",resp):
        n=int(tok)
        if 1<=n<=len(items) and n not in order: order.append(n)
    return order
def covered(golds_for_q,c2s,items,topk_idx):
    hit=0
    for cid,hp in golds_for_q:
        slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
        ok=any(items[i][0]==slug and sublist(gold,items[i][1]) for i in topk_idx)
        hit+=1 if ok else 0
    return hit
def main():
    c2s=cid2slug(); corpus=load_corpus()
    cand={q["qid"]:search_pages(q["query"]) for q in corpus}
    pages=fetch(sorted({s for v in cand.values() for s in v if s}))
    dres={n:[] for n in NS}; rres={n:[] for n in NS}; ddenom=0
    for q in corpus:
        qv=embed(q["query"])
        # build candidate sections across this query's pages, score by max-chunk cosine
        items=[]  # (page,segs,text,score)
        for s in cand[q["qid"]]:
            for segs,d in pages.get(s,{}).items():
                sc=max(cos(qv,v) for v in d["vecs"])
                items.append((s,segs," ".join(d["txt"])[:SNIP],sc))
        # per-page shortlist: each retrieved page contributes its top-PER_PAGE_S sections by
        # dense (preserves the 0.97 page-recall instead of letting a flat global cut bury the
        # gold page), then one global rerank over the combined shortlist.
        bypage={}
        for it in sorted(items,key=lambda x:-x[3]):
            bypage.setdefault(it[0],[])
            if len(bypage[it[0]])<PER_PAGE_S: bypage[it[0]].append(it)
        shortlist=sorted([x for v in bypage.values() for x in v],key=lambda x:-x[3])[:K]
        sl=[(p,sg,t) for p,sg,t,_ in shortlist]
        # dense order = identity (already sorted); rerank order via LLM
        try: order=llm_rerank(q["query"],sl)
        except Exception: order=list(range(1,len(sl)+1))
        order=[i-1 for i in order]+[i for i in range(len(sl)) if (i+1) not in [o for o in order]]
        for n in NS:
            ddenom_q=len(q["golds"])
            dres[n].append(covered(q["golds"],c2s,sl,list(range(min(n,len(sl)))))/ddenom_q)
            rres[n].append(covered(q["golds"],c2s,sl,order[:n])/ddenom_q)
    def avg(x): return sum(x)/len(x) if x else 0.0
    lat=sorted(_lat); pct=lambda qq:(lat[int(qq*(len(lat)-1))] if lat else 0)
    print(f"Production-frame rerank (model={LLM}, global shortlist K={K}, {PAGES_PER_Q} pages/query)\n")
    print(f"{'method':<22}"+"".join(f"{('recall@'+str(n)):>11}" for n in NS))
    print("-"*(22+11*len(NS)))
    print(f"{'dense shortlist':<22}"+"".join(f"{avg(dres[n]):>11.3f}" for n in NS))
    print(f"{'+ LLM rerank':<22}"+"".join(f"{avg(rres[n]):>11.3f}" for n in NS))
    print(f"\nrerank latency: p50={pct(0.5):.1f}s p95={pct(0.95):.1f}s (n={len(lat)}, {K} sections/call)")
    print("(per-page-frame reference: rerank recall@5≈0.75 — global is bounded by dense shortlist recall)")
if __name__=="__main__": main()

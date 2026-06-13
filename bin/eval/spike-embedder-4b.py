#!/usr/bin/env python3
"""
First-stage embedder comparison: qwen3-embedding 0.6B vs 4B.

The binding ceiling on section recall is first-stage recall (the gold section must reach
the shortlist). This re-ranks each query's real candidate sections by 0.6B vs 4B section
embeddings (queries get the qwen3 instruction prefix, documents raw — matching production)
and reports shortlist recall@N. If 4B pulls the gold section higher, that's the latency-free
lever. Production reference (max-chunk 0.6B): ~0.24@5 / ~0.47@12.

Slow (4B is big); designed to run in the background. Requires PG* env + both embedders.
"""
import csv, glob, json, os, re, struct, subprocess, time, urllib.parse, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
SEARCH="http://localhost:8080/api/search"
M06="qwen3-embedding:0.6b"; M4="qwen3-embedding:4b"
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[3,5,8,12]; PAGES_PER_Q=8; SNIP=2000

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
_cache={}; _lat={M06:[],M4:[]}
def embed(text, model, instr=False):
    t=(INSTR+text) if instr else text
    key=(model,instr,t[:SNIP+200])
    if key in _cache: return _cache[key]
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":model,"prompt":t[:SNIP]}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    t0=time.time()
    with urllib.request.urlopen(req,timeout=120) as r: v=json.load(r)["embedding"]
    _lat[model].append(time.time()-t0); _cache[key]=v; return v
def cos(a,b):
    dot=sa=sb=0.0
    for x,y in zip(a,b): dot+=x*y; sa+=x*x; sb+=y*y
    return dot/((sa**.5)*(sb**.5)+1e-12)
def fetch(slugs):
    arr="{"+",".join('"%s"'%s.replace('"','') for s in slugs)+"}"
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), c.chunk_index, "
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
        page,hp,_,txt=pr
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        pages.setdefault(page,{}).setdefault(segs,[]).append(txt)
    out2={}
    for pg,secs in pages.items():
        out2[pg]=[(segs," ".join(t)[:SNIP]) for segs,t in secs.items()]
    return out2
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))
def main():
    c2s=cid2slug(); corpus=load_corpus()
    cand={q["qid"]:search_pages(q["query"]) for q in corpus}
    pages=fetch(sorted({s for v in cand.values() for s in v if s}))
    res={M06:{n:[] for n in NS}, M4:{n:[] for n in NS}}
    for qi,q in enumerate(corpus,1):
        items=[(s,segs,txt) for s in cand[q["qid"]] for segs,txt in pages.get(s,[])]
        if not items: continue
        for model in (M06,M4):
            qv=embed(q["query"],model,instr=True)
            scored=sorted(items,key=lambda it:-cos(qv,embed(it[2],model,instr=False)))
            for cid,hp in q["golds"]:
                slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
                rank=next((i for i,(p,sg,_) in enumerate(scored,1) if p==slug and sublist(gold,sg)),None)
                for n in NS: res[model][n].append(1.0 if (rank and rank<=n) else 0.0)
        print(f"  ...{qi}/{len(corpus)}", flush=True)
    def avg(x): return sum(x)/len(x) if x else 0.0
    print("\nFirst-stage embedder: section-level shortlist recall@N (0.6B vs 4B)\n")
    print(f"{'embedder':<22}"+"".join(f"{('recall@'+str(n)):>11}" for n in NS)+f"{'embed p50':>11}")
    print("-"*(22+11*len(NS)+11))
    for model,label in ((M06,"qwen3-emb 0.6B"),(M4,"qwen3-emb 4B")):
        lat=sorted(_lat[model]); p50=lat[len(lat)//2] if lat else 0
        print(f"{label:<22}"+"".join(f"{avg(res[model][n]):>11.3f}" for n in NS)+f"{p50*1000:>9.0f}ms")
    print("\n(production max-chunk 0.6B reference: ~0.24@5 / ~0.47@12)")
if __name__=="__main__": main()

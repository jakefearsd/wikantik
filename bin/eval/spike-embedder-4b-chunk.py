#!/usr/bin/env python3
"""
Faithful first-stage embedder test: max-chunk 0.6B vs max-chunk 4B (production granularity).

Unlike the section-level proxy, this ranks sections by their BEST CHUNK's similarity (what
production does). 0.6B uses the existing DB chunk vectors (production-exact); 4B re-embeds each
candidate chunk. Queries get the qwen3 instruction prefix, chunks raw. Reports shortlist
recall@N for both — the apples-to-apples answer on whether 4B raises the first-stage ceiling.

Production reference (max-chunk 0.6B) should reproduce ~0.24@5 / ~0.47@12 here.
Slow (4B chunk embeds); background. Requires PG* env + both embedders.
"""
import csv, glob, json, os, re, struct, subprocess, time, urllib.parse, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
SEARCH="http://localhost:8080/api/search"
M06="qwen3-embedding:0.6b"; M4="qwen3-embedding:4b"
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[3,5,8,12]; PAGES_PER_Q=8; CHUNK_CAP=1200

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
_qc={}; _cc={}; _lat=[]
def qembed(query,model):
    k=(model,query)
    if k in _qc: return _qc[k]
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":model,"prompt":INSTR+query}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: v=json.load(r)["embedding"]
    _qc[k]=v; return v
def cembed4b(text):
    k=text[:CHUNK_CAP+50]
    if k in _cc: return _cc[k]
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":M4,"prompt":text[:CHUNK_CAP]}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    t0=time.time()
    with urllib.request.urlopen(req,timeout=120) as r: v=json.load(r)["embedding"]
    _lat.append(time.time()-t0); _cc[k]=v; return v
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
    pages={}  # page -> section(segs) -> list of (vec06, text)
    for ln in out.splitlines():
        if not ln.strip(): continue
        pr=ln.split("\t")
        if len(pr)<4: continue
        page,hp,vh,txt=pr
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        vec=struct.unpack("<%df"%(len(vh)//8),bytes.fromhex(vh))
        pages.setdefault(page,{}).setdefault(segs,[]).append((vec,txt))
    return pages
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))
def main():
    c2s=cid2slug(); corpus=load_corpus()
    cand={q["qid"]:search_pages(q["query"]) for q in corpus}
    pages=fetch(sorted({s for v in cand.values() for s in v if s}))
    res={"06":{n:[] for n in NS},"4b":{n:[] for n in NS}}
    for qi,q in enumerate(corpus,1):
        q06=qembed(q["query"],M06); q4=qembed(q["query"],M4)
        secs=[]  # (page, segs, score06, score4b)
        for s in cand[q["qid"]]:
            for segs,chunks in pages.get(s,{}).items():
                s06=max(cos(q06,v) for v,_ in chunks)
                s4=max(cos(q4,cembed4b(t)) for _,t in chunks)
                secs.append((s,segs,s06,s4))
        for key,idx in (("06",2),("4b",3)):
            ranked=sorted(secs,key=lambda x:-x[idx])
            for cid,hp in q["golds"]:
                slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
                rank=next((i for i,(p,sg,_,_) in enumerate(ranked,1) if p==slug and sublist(gold,sg)),None)
                for n in NS: res[key][n].append(1.0 if (rank and rank<=n) else 0.0)
        print(f"  ...{qi}/{len(corpus)}", flush=True)
    def avg(x): return sum(x)/len(x) if x else 0.0
    lat=sorted(_lat); p50=lat[len(lat)//2] if lat else 0
    print("\nFaithful max-chunk first-stage: shortlist recall@N (0.6B vs 4B, production granularity)\n")
    print(f"{'embedder':<22}"+"".join(f"{('recall@'+str(n)):>11}" for n in NS))
    print("-"*(22+11*len(NS)))
    print(f"{'max-chunk 0.6B (prod)':<22}"+"".join(f"{avg(res['06'][n]):>11.3f}" for n in NS))
    print(f"{'max-chunk 4B':<22}"+"".join(f"{avg(res['4b'][n]):>11.3f}" for n in NS))
    print(f"\n4B chunk-embed p50={p50*1000:.0f}ms (n={len(lat)})")
if __name__=="__main__": main()

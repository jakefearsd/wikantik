#!/usr/bin/env python3
"""
Phase-1 spike: predict the lift of the parent-section bundle BEFORE building it.

Simulates the proposed RAG-as-a-Service assembly on live data:
  1. retrieve candidate pages via the real /api/search (the hybrid page set)
  2. rank ALL their chunks globally by dense (qwen3) similarity (not per-page-capped)
  3. expand each chunk to its parent SECTION (page + heading_path) and dedup
     (one entry per section, scored by its best chunk)
  4. take the top-N sections — that's the bundle
Then report section recall @ bundle size N, plus the gold-section MRR (how high the
answering section ranks), overall and per category. This says how much the number
moves and the right N — without writing the service.

Requires PG* env vars + the live deployment (/api/search) + the qwen3 embedder.
"""
import csv, glob, json, os, re, struct, subprocess, urllib.parse, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"; EMBED_TAG="qwen3-embedding:0.6b"
EMBED_URL="http://inference.jakefear.com:11434/api/embeddings"
SEARCH="http://localhost:8080/api/search"
NS=[3,5,8,12,20]
try:
    import numpy as np
except ImportError:
    np=None

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
    return [p.get("name","") for p in d.get("results",[])]

_emb={}
def embed(q):
    if q in _emb: return _emb[q]
    req=urllib.request.Request(EMBED_URL,data=json.dumps({"model":EMBED_TAG,"prompt":q}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=60) as r: v=json.load(r)["embedding"]
    _emb[q]=v; return v

def fetch_chunks(slugs):
    arr="{"+",".join('"%s"'%s.replace('"','') for s in slugs)+"}"
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), encode(e.vec,'hex') "
         "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
         "WHERE e.model_code='%s' AND c.page_name=ANY('%s'::text[]);"%(MODEL_CODE,arr))
    out=subprocess.run(["psql","-tA","-F","\t","-c",sql],capture_output=True,text=True,check=True).stdout
    chunks=[]
    for ln in out.splitlines():
        if not ln.strip(): continue
        page,hp,vh=ln.split("\t")
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        vec=struct.unpack("<%df"%(len(vh)//8),bytes.fromhex(vh))
        chunks.append((page,segs,vec))
    return chunks

def sublist(needle,hay):
    n=len(needle)
    return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def main():
    c2s=cid2slug(); corpus=load_corpus()
    # candidate pages per query (real hybrid retrieval)
    cand={q["qid"]:search_pages(q["query"]) for q in corpus}
    allslugs=sorted({s for v in cand.values() for s in v if s})
    chunks=fetch_chunks(allslugs)
    # index chunks by page; precompute normalized vectors
    by_page={}
    vecs=[]
    for i,(pg,segs,vec) in enumerate(chunks):
        by_page.setdefault(pg,[]).append(i); vecs.append(vec)
    if np is not None:
        M=np.array(vecs,dtype=np.float32); M/= (np.linalg.norm(M,axis=1,keepdims=True)+1e-9)
    def qscores(qvec,idxs):
        if np is not None:
            qv=np.array(qvec,dtype=np.float32); qv/=(np.linalg.norm(qv)+1e-9)
            return (M[idxs]@qv)
        out=[]
        for i in idxs:
            a=chunks[i][2]; dot=sa=sb=0.0
            for x,y in zip(qvec,a): dot+=x*y; sa+=x*x; sb+=y*y
            out.append(dot/((sa**.5)*(sb**.5)+1e-12))
        return out

    cats={}; tot={"mrr":[],**{n:[] for n in NS}}
    for q in corpus:
        qvec=embed(q["query"])
        idxs=[i for s in cand[q["qid"]] for i in by_page.get(s,[])]
        if not idxs:
            sections=[]
        else:
            sc=qscores(qvec,idxs)
            # dedup chunks -> sections (page,heading), score = max chunk score
            best={}
            for j,i in enumerate(idxs):
                pg,segs,_=chunks[i]; key=(pg,segs); s=float(sc[j])
                if key not in best or s>best[key]: best[key]=s
            sections=sorted(best.items(),key=lambda kv:-kv[1])  # [((page,segs),score), ...]
        # rank of each gold section in the global ranking
        def gold_rank(cid,hp):
            slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            for rank,((pg,segs),_) in enumerate(sections,1):
                if pg==slug and sublist(gold,segs): return rank
            return None
        per=[gold_rank(cid,hp) for cid,hp in q["golds"]]
        c=cats.setdefault(q["cat"],{"mrr":[],**{n:[] for n in NS}})
        for cid_hp,r in zip(q["golds"],per):
            rr=(1.0/r) if r else 0.0
            c["mrr"].append(rr); tot["mrr"].append(rr)
            for n in NS:
                hit=1.0 if (r is not None and r<=n) else 0.0
                c[n].append(hit); tot[n].append(hit)

    # --- variant B: PER-PAGE-allocated section bundle (top-S deduped sections per retrieved page) ---
    SS=[1,2,3,5]
    ppcats={}; pptot={n:[] for n in SS}
    for q in corpus:
        qvec=embed(q["query"])
        # per candidate page: dedup its chunks into sections, rank by best dense score within the page
        page_secs={}
        for s in cand[q["qid"]]:
            ix=by_page.get(s,[])
            if not ix: continue
            sc=qscores(qvec,ix); best={}
            for j,i in enumerate(ix):
                _,segs,_=chunks[i]; v=float(sc[j])
                if segs not in best or v>best[segs]: best[segs]=v
            page_secs[s]=[segs for segs,_ in sorted(best.items(),key=lambda kv:-kv[1])]
        def gold_pp_rank(cid,hp):
            slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            for rank,segs in enumerate(page_secs.get(slug,[]),1):
                if sublist(gold,segs): return rank
            return None
        c=ppcats.setdefault(q["cat"],{n:[] for n in SS})
        for cid,hp in q["golds"]:
            r=gold_pp_rank(cid,hp)
            for s in SS:
                hit=1.0 if (r is not None and r<=s) else 0.0
                c[s].append(hit); pptot[s].append(hit)

    def avg(x): return sum(x)/len(x) if x else 0.0
    hdr="".join(f"{('recall@'+str(n)):>11}" for n in NS)
    print("A) FLAT-GLOBAL bundle — top-N sections ranked globally (dense), deduped\n")
    print(f"{'category':<12}{'n':>4}{hdr}{'sec_MRR':>9}")
    print("-"*(16+11*len(NS)+9))
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in cats:
            c=cats[cat]; print(f"{cat:<12}{len(c['mrr']):>4}"+"".join(f"{avg(c[n]):>11.3f}" for n in NS)+f"{avg(c['mrr']):>9.3f}")
    print("-"*(16+11*len(NS)+9))
    print(f"{'OVERALL':<12}{len(tot['mrr']):>4}"+"".join(f"{avg(tot[n]):>11.3f}" for n in NS)+f"{avg(tot['mrr']):>9.3f}")
    print("\nB) PER-PAGE-ALLOCATED bundle — top-S deduped sections per retrieved page (the right design)\n")
    sh="".join(f"{('recall@S='+str(s)):>13}" for s in SS)
    print(f"{'category':<12}{'n':>4}{sh}")
    print("-"*(16+13*len(SS)))
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in ppcats:
            c=ppcats[cat]; print(f"{cat:<12}{len(c[SS[0]]):>4}"+"".join(f"{avg(c[s]):>13.3f}" for s in SS))
    print("-"*(16+13*len(SS)))
    print(f"{'OVERALL':<12}{len(pptot[SS[0]]):>4}"+"".join(f"{avg(pptot[s]):>13.3f}" for s in SS))
    print("\nbaseline (current per-page-5 raw chunks) section recall ~0.63; page-recall ceiling ~0.97.")

if __name__=="__main__": main()

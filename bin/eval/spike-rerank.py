#!/usr/bin/env python3
"""
Phase-1 reranking spike (zero-new-model options first).

The gold section ranks ~5th within its page by MAX-chunk similarity. Can we re-rank it
higher for FREE — without a cross-encoder — by representing the section better? Tests, in
the per-page-allocated frame (rank the gold page's sections, recall@S):

  max      : section score = max chunk cosine          (current behaviour)
  mean     : mean of the section's chunk cosines
  top2mean : mean of the section's top-2 chunk cosines
  secEmb   : cosine(query, embedding(whole-section text))   -- uses the existing qwen3 embedder

All four are ~zero query-time latency (chunk/section vectors are precomputed offline; query
time is one cosine). If any lifts recall meaningfully, no reranker is needed.

Requires PG* env + the qwen3 embedder.
"""
import csv, glob, json, os, re, struct, subprocess, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"; EMBED_TAG="qwen3-embedding:0.6b"
EMBED_URL="http://inference.jakefear.com:11434/api/embeddings"
SS=[1,2,3,5]; METHODS=["max","mean","top2mean","secEmb"]

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

_emb={}
def embed(text):
    key=text[:4000]
    if key in _emb: return _emb[key]
    req=urllib.request.Request(EMBED_URL,data=json.dumps({"model":EMBED_TAG,"prompt":key}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=60) as r: v=json.load(r)["embedding"]
    _emb[key]=v; return v

def cos(a,b):
    dot=sa=sb=0.0
    for x,y in zip(a,b): dot+=x*y; sa+=x*x; sb+=y*y
    return dot/((sa**.5)*(sb**.5)+1e-12)

def fetch(slugs):
    arr="{"+",".join('"%s"'%s.replace('"','') for s in slugs)+"}"
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), c.chunk_index, "
         "encode(e.vec,'hex'), translate(c.text, chr(10)||chr(13)||chr(9), '   ') "
         "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
         "WHERE e.model_code='%s' AND c.page_name=ANY('%s'::text[]) ORDER BY c.page_name,c.chunk_index;"
         %(MODEL_CODE,arr))
    out=subprocess.run(["psql","-tA","-F","\t","-c",sql],capture_output=True,text=True,check=True).stdout
    # page -> section(heading tuple) -> list of (chunk_index, vec, text)
    pages={}
    for ln in out.splitlines():
        if not ln.strip(): continue
        page,hp,ci,vh,txt=ln.split("\t")
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        vec=struct.unpack("<%df"%(len(vh)//8),bytes.fromhex(vh))
        pages.setdefault(page,{}).setdefault(segs,[]).append((int(ci),vec,txt))
    return pages

def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def section_score(method,qvec,chunks):
    sims=[cos(qvec,v) for _,v,_ in chunks]
    if method=="max": return max(sims)
    if method=="mean": return sum(sims)/len(sims)
    if method=="top2mean":
        t=sorted(sims,reverse=True)[:2]; return sum(t)/len(t)
    if method=="secEmb":
        text=" ".join(t for _,_,t in sorted(chunks))[:2000]
        return cos(qvec,embed(text))
    raise ValueError(method)

def main():
    c2s=cid2slug(); corpus=load_corpus()
    goldslugs=sorted({c2s[cid] for q in corpus for cid,_ in q["golds"] if cid in c2s})
    pages=fetch(goldslugs)
    res={m:{s:[] for s in SS} for m in METHODS}
    for q in corpus:
        qv=embed(q["query"])
        for cid,hp in q["golds"]:
            slug=c2s.get(cid); secs=pages.get(slug,{})
            gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            for m in METHODS:
                ranked=sorted(secs.items(),key=lambda kv:-section_score(m,qv,kv[1]))
                rank=next((i for i,(segs,_) in enumerate(ranked,1) if sublist(gold,segs)),None)
                for s in SS:
                    res[m][s].append(1.0 if (rank and rank<=s) else 0.0)
    def avg(x): return sum(x)/len(x) if x else 0.0
    print("Free section re-representation — gold-section recall@S per scoring method "
          "(per-page frame, zero query-time latency)\n")
    print(f"{'method':<10}"+"".join(f"{('recall@S='+str(s)):>13}" for s in SS))
    print("-"*(10+13*len(SS)))
    for m in METHODS:
        print(f"{m:<10}"+"".join(f"{avg(res[m][s]):>13.3f}" for s in SS))
    print("\n'max' is current behaviour. A lift here = recall gain with NO reranker, NO new model.")

if __name__=="__main__": main()

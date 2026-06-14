#!/usr/bin/env python3
"""
HyDE query-side spike — does a hypothetical-answer embedding pull the 13-100 'retrieved-but-
ranked-low' bucket up into the top-12? Query-side only (NO corpus re-embed).

For each query: an LLM writes a short hypothetical answer passage; we embed THAT as a pseudo-
document (raw, no instruction prefix — it's matched against raw document chunks) and rank all
corpus sections by it. Three conditions, scored on the SAME global section-ranking recall metric
as bin/eval/spike-recall-ceiling.py:
  (a) baseline   — instruction-prefixed query (production); should reproduce @12=0.574
  (b) hyde       — hypothetical-document embedding (raw)
  (c) hyde+query — unit-average of (a) and (b)

If hyde / hyde+query lifts @12 toward the 0.824 ceiling, query-side expansion is a cheap win.
If flat, the matching problem is document-side -> contextual chunk embeddings.

Args: [HYDE_MODEL]   (default gemma4:12b). Requires PG* env + embedder + the gen model.
"""
import csv, glob, json, math, os, re, struct, subprocess, sys, time, urllib.request
from operator import mul

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
CHAT_URL="http://inference.jakefear.com:11434/api/chat"
M06="qwen3-embedding:0.6b"
HYDE_MODEL=(sys.argv[1] if len(sys.argv)>1 else "gemma4:12b")
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[5,12,20,50,100]

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
def unit(v):
    n=math.sqrt(sum(x*x for x in v)) or 1.0
    return [x/n for x in v]
def avg_unit(a,b): return unit([x+y for x,y in zip(a,b)])
def embed(text):
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":M06,"prompt":text}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: return unit(json.load(r)["embedding"])
def hyde_passage(query):
    prompt=("Write a concise 3-4 sentence passage from a technical wiki that directly answers "
            "this question. Output ONLY the passage, no preamble, no headings.\n\nQuestion: "+query)
    body={"model":HYDE_MODEL,"stream":False,"think":False,
          "messages":[{"role":"system","content":"You write concise factual technical passages."},
                      {"role":"user","content":prompt}]}
    req=urllib.request.Request(CHAT_URL,data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=180) as r: txt=json.load(r)["message"]["content"]
    return re.sub(r"<think>.*?</think>","",txt,flags=re.S).strip()
def fetch_all():
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), encode(e.vec,'hex') "
         "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
         "WHERE e.model_code='%s';"%MODEL_CODE)
    out=subprocess.run(["psql","-tA","-F","\t","-c",sql],capture_output=True,text=True,check=True).stdout
    slugs=[]; segs=[]; vecs=[]
    for ln in out.splitlines():
        if not ln.strip(): continue
        pr=ln.split("\t")
        if len(pr)<3: continue
        page,hp,vh=pr
        slugs.append(page); segs.append(tuple(norm(x) for x in hp.split(">") if x.strip()))
        vecs.append(unit(struct.unpack("<%df"%(len(vh)//8),bytes.fromhex(vh))))
    return slugs,segs,vecs
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def rank_golds(qv,golds,c2s,slugs,segs,vecs):
    best={}
    for s,sg,v in zip(slugs,segs,vecs):
        sc=sum(map(mul,qv,v)); k=(s,sg)
        if sc>best.get(k,-2.0): best[k]=sc
    ranked=sorted(best.items(),key=lambda kv:-kv[1])
    rank_of={k:i for i,(k,_) in enumerate(ranked,1)}
    out=[]
    for cid,hp in golds:
        slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
        r=next((rank_of[(s,sg)] for (s,sg),_ in ranked if s==slug and sublist(gold,sg)),None)
        out.append(r)
    return out

def recall_at(rs,n): return sum(1 for r in rs if r is not None and r<=n)/len(rs) if rs else 0.0
def bucket(rs):
    b={"=1":0,"2-3":0,"4-5":0,"6-12":0,"13-20":0,"21-50":0,"51-100":0,">100":0,"none":0}
    for r in rs:
        if r is None: b["none"]+=1
        elif r==1: b["=1"]+=1
        elif r<=3: b["2-3"]+=1
        elif r<=5: b["4-5"]+=1
        elif r<=12: b["6-12"]+=1
        elif r<=20: b["13-20"]+=1
        elif r<=50: b["21-50"]+=1
        elif r<=100: b["51-100"]+=1
        else: b[">100"]+=1
    return b

def main():
    c2s=cid2slug(); corpus=load_corpus()
    t0=time.time(); slugs,segs,vecs=fetch_all()
    print(f"loaded {len(vecs)} chunks in {time.time()-t0:.1f}s; HyDE model={HYDE_MODEL}",flush=True)
    R={"base":[],"hyde":[],"both":[]}
    for qi,q in enumerate(corpus,1):
        qv_base=embed(INSTR+q["query"])
        try: passage=hyde_passage(q["query"])
        except Exception as e: print(f"  hyde gen fail q{qi}: {e}",flush=True); passage=q["query"]
        qv_hyde=embed(passage)
        qv_both=avg_unit(qv_base,qv_hyde)
        R["base"]+=rank_golds(qv_base,q["golds"],c2s,slugs,segs,vecs)
        R["hyde"]+=rank_golds(qv_hyde,q["golds"],c2s,slugs,segs,vecs)
        R["both"]+=rank_golds(qv_both,q["golds"],c2s,slugs,segs,vecs)
        print(f"  ...{qi}/{len(corpus)}",flush=True)
    print(f"\nHyDE query-side spike — GLOBAL section-ranking recall@N (n={len(R['base'])} golds)\n")
    print(f"{'condition':<14}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(14+12*len(NS)))
    for k,label in (("base","baseline q"),("hyde","hyde doc"),("both","hyde+query")):
        print(f"{label:<14}"+"".join(f"{recall_at(R[k],n):>12.3f}" for n in NS))
    print(f"\n(ceiling diagnostic baseline for cross-check: 0.412@5 / 0.574@12 / 0.824@100)")
    print(f"\nRank distribution shift (baseline -> hyde+query):")
    bb,bh=bucket(R["base"]),bucket(R["both"])
    for k in ["=1","2-3","4-5","6-12","13-20","21-50","51-100",">100","none"]:
        print(f"  rank {k:<7} {bb[k]:>3} -> {bh[k]:>3}")
    print(f"\nDECISION: if hyde / hyde+query @12 climbs above baseline 0.574, query-side expansion is a")
    print(f"cheap win (no re-embed). If flat, the matching gap is document-side -> contextual embeddings.")

if __name__=="__main__": main()

#!/usr/bin/env python3
"""
DECISIVE upstream diagnostic — splits the section-recall miss into two buckets that
point at completely different work:

  * retrieved-but-cut : a chunk from the gold section DOES rank high in the full-corpus
    dense ranking, but the pipeline (page pre-selection + per-page cap + top-N) drops it.
    -> lever is pool width / aggregation / ranking (cheap).
  * never-retrieved   : no chunk from the gold section ranks anywhere near the top.
    -> lever is chunk<->query MATCHING (contextual embeddings, query expansion, chunking).

Method: score EVERY chunk in the corpus (qwen3-0.6b, instruction-prefixed query) — bypassing
the page-retrieval stage and the per-page cap entirely — rank SECTIONS globally by best chunk,
and report (a) recall@N as N widens (the ceiling), (b) the rank distribution of each gold
section's best chunk. Decision rule printed at the end.

Compare against the shipped bundle (page-preselect + per-page-5 cap): 0.389@5 / 0.500@12.
Requires PG* env + the embedder on the inference host. Pure Python (no numpy).
"""
import csv, glob, json, math, os, re, struct, subprocess, sys, time, urllib.request
from operator import mul

import sys
PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE=(sys.argv[1] if len(sys.argv)>1 else "qwen3-embedding-0.6b")
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
M06="qwen3-embedding:0.6b"
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
def qembed(query):
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":M06,"prompt":INSTR+query}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: return unit(json.load(r)["embedding"])
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
        slugs.append(page)
        segs.append(tuple(norm(x) for x in hp.split(">") if x.strip()))
        vecs.append(unit(struct.unpack("<%df"%(len(vh)//8),bytes.fromhex(vh))))
    return slugs,segs,vecs
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def main():
    c2s=cid2slug(); corpus=load_corpus()
    t0=time.time(); slugs,segs,vecs=fetch_all()
    print(f"loaded {len(vecs)} chunks in {time.time()-t0:.1f}s",flush=True)
    ranks=[]      # rank of each gold section's best chunk in the global section ranking (None=no match)
    ngolds=0; never_no_chunk=0
    catranks={}
    for qi,q in enumerate(corpus,1):
        qv=qembed(q["query"])
        # score every chunk, keep best per section
        best={}  # (slug,segs) -> score
        for s,sg,v in zip(slugs,segs,vecs):
            sc=sum(map(mul,qv,v))
            k=(s,sg)
            if sc>best.get(k,-2.0): best[k]=sc
        ranked=sorted(best.items(),key=lambda kv:-kv[1])  # [( (slug,segs), score ), ...]
        # index: section -> rank (1-based)
        rank_of={k:i for i,(k,_) in enumerate(ranked,1)}
        for cid,hp in q["golds"]:
            ngolds+=1
            slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            # best (smallest) rank among sections on the gold page whose path extends the gold path
            r=None
            for (s,sg),_ in ranked:
                if s==slug and sublist(gold,sg):
                    r=rank_of[(s,sg)]; break   # ranked is sorted, first match = best rank
            if r is None: never_no_chunk+=1
            ranks.append(r)
            catranks.setdefault(q["cat"],[]).append(r)
        print(f"  ...{qi}/{len(corpus)}",flush=True)

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

    print(f"\nGLOBAL section-ranking recall ceiling (all {len(vecs)} chunks, no page-preselect, no per-page cap)\n")
    print(f"{'scope':<14}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(14+12*len(NS)))
    print(f"{'OVERALL':<14}"+"".join(f"{recall_at(ranks,n):>12.3f}" for n in NS))
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in catranks:
            print(f"{cat.lower():<14}"+"".join(f"{recall_at(catranks[cat],n):>12.3f}" for n in NS))
    print(f"\n(shipped bundle, page-preselect + per-page-5 cap, for reference: 0.389@5 / 0.500@12)")

    b=bucket(ranks)
    print(f"\nRank distribution of each gold section's BEST chunk (n={ngolds} golds):")
    for k in ["=1","2-3","4-5","6-12","13-20","21-50","51-100",">100","none"]:
        print(f"  rank {k:<7} {b[k]:>3}  ({b[k]/ngolds*100:4.1f}%)")

    cut = recall_at(ranks,100) - recall_at(ranks,12)
    print(f"\nDECISION:")
    print(f"  recall@12={recall_at(ranks,12):.3f}  recall@100={recall_at(ranks,100):.3f}  (lift from widening 12->100: +{cut:.3f})")
    print(f"  never-findable (no gold chunk ranks anywhere / no chunk at all): "
          f"{(1-recall_at(ranks,100))*100:.1f}% ; of which {never_no_chunk} golds have NO matching chunk at all.")
    print(f"  -> if the 12->100 lift is large, recall is RETRIEVED-BUT-CUT (lever: pool width/aggregation/ranking).")
    print(f"  -> if recall@100 is still low/flat, recall is NEVER-RETRIEVED (lever: chunk<->query matching:")
    print(f"     contextual embeddings, query expansion/HyDE, chunking).")

if __name__=="__main__": main()

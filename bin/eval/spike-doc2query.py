#!/usr/bin/env python3
"""
doc2query STACK test — does generating the questions each chunk answers (and embedding them as a
second signal) recover gold sections that CONTEXTUAL embeddings alone still miss?

Builds on the contextual win (0.735@12). For the competitive candidate set (union of top-K chunks
per query under contextual embeddings), an LLM generates N questions the chunk answers; those are
embedded and the chunk's score becomes max(contextual_sim, best_question_sim). Scored on the same
global section-ranking recall, so contextual-only vs contextual+doc2query is a clean A/B.

Scoped to the candidate union (not all 18k) so the LLM pass is ~minutes, while staying comparable:
non-candidate chunks keep contextual-only (they rank too low to be golds — contextual@50=0.93).

Args: [GEN_MODEL] [TOPK] [NQ]. Requires PG* env + embedder + gen model. Pure Python + threads.
"""
import csv, glob, json, math, os, re, subprocess, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from operator import mul

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB1="http://inference.jakefear.com:11434/api/embeddings"
EMBN="http://inference.jakefear.com:11434/api/embed"
GEN="http://inference.jakefear.com:11434/api/generate"
M06="qwen3-embedding:0.6b"
GEN_MODEL=(sys.argv[1] if len(sys.argv)>1 else "qwen2.5:7b-instruct-q5_K_M")
TOPK=(int(sys.argv[2]) if len(sys.argv)>2 else 50)
NQ=(int(sys.argv[3]) if len(sys.argv)>3 else 3)
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[5,12,20,50,100]

def norm(s): return re.sub(r"\s+"," ",s).strip().lower()
def unit(v):
    n=math.sqrt(sum(x*x for x in v)) or 1.0
    return [x/n for x in v]
def page_meta():
    meta={}
    for p in glob.glob(os.path.join(PAGES,"**","*.md"),recursive=True):
        try: head=open(p,encoding="utf-8").read(4000)
        except OSError: continue
        slug=os.path.basename(p)[:-3]
        def f(key):
            m=re.search(r"^%s:\s*(.+)$"%key,head,re.M); return m.group(1).strip() if m else ""
        meta[slug]=(f("canonical_id"), f("title") or slug, f("cluster"), f("summary"))
    return meta
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
def fetch_text():
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), "
         "translate(c.text, chr(10)||chr(13)||chr(9), '   ') "
         "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
         "WHERE e.model_code='%s' ORDER BY c.page_name, c.chunk_index;"%MODEL_CODE)
    out=subprocess.run(["psql","-tA","-F","\t","-c",sql],capture_output=True,text=True,check=True).stdout
    rows=[]
    for ln in out.splitlines():
        if not ln.strip(): continue
        pr=ln.split("\t")
        if len(pr)<3: continue
        rows.append((pr[0], tuple(norm(x) for x in pr[1].split(">") if x.strip()), pr[2]))
    return rows
def ctx_text(slug,segs,text,meta):
    cid,title,cluster,summary=meta.get(slug,("",slug,"",""))
    h=f"Page: {title}"
    if cluster: h+=f" | Cluster: {cluster}"
    if segs: h+=f" | Section: {' > '.join(segs)}"
    if summary: h+=f"\nSummary: {summary}"
    return h+"\n\n"+text
def embed_one(text):
    req=urllib.request.Request(EMB1,data=json.dumps({"model":M06,"prompt":text}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: return unit(json.load(r)["embedding"])
def batch_embed(texts,batch=48,tag=""):
    out=[]; t0=time.time()
    for i in range(0,len(texts),batch):
        req=urllib.request.Request(EMBN,data=json.dumps({"model":M06,"input":texts[i:i+batch]}).encode(),
            headers={"Content-Type":"application/json"},method="POST")
        with urllib.request.urlopen(req,timeout=300) as r: out.extend(unit(e) for e in json.load(r)["embeddings"])
        if (i//batch)%25==0: print(f"  embed{tag} {len(out)}/{len(texts)} ({time.time()-t0:.0f}s)",flush=True)
    return out
def gen_questions(text):
    prompt=("Generate %d diverse, specific search questions that the following passage directly and "
            "fully answers. Output ONLY the questions, one per line, no numbering.\n\nPassage:\n%s"%(NQ,text[:700]))
    body={"model":GEN_MODEL,"prompt":prompt,"stream":False,"think":False,"options":{"temperature":0.7}}
    req=urllib.request.Request(GEN,data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    try:
        with urllib.request.urlopen(req,timeout=120) as r: resp=json.load(r)["response"]
    except Exception: return []
    qs=[re.sub(r"^\s*[\d\.\-\)]+\s*","",l).strip() for l in resp.splitlines() if l.strip()]
    return [q for q in qs if len(q)>8][:NQ]
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def main():
    meta=page_meta(); c2s={cid:s for s,(cid,_,_,_) in meta.items() if cid}
    corpus=load_corpus(); rows=fetch_text()
    print(f"chunks={len(rows)} gen_model={GEN_MODEL} topK={TOPK} NQ={NQ}",flush=True)
    slugs=[r[0] for r in rows]; segs=[r[1] for r in rows]
    cvec=batch_embed([ctx_text(*r,meta) for r in rows],tag="-ctx")
    qv={q["qid"]:embed_one(INSTR+q["query"]) for q in corpus}
    # candidate union: top-K chunk indices per query by contextual sim
    cand=set()
    cscore_cache={}
    for q in corpus:
        v=qv[q["qid"]]; sims=[(i,sum(map(mul,v,cvec[i]))) for i in range(len(rows))]
        sims.sort(key=lambda x:-x[1]); cscore_cache[q["qid"]]=dict(sims)
        cand.update(i for i,_ in sims[:TOPK])
    cand=sorted(cand)
    print(f"candidate chunks for doc2query: {len(cand)}",flush=True)
    # generate questions for candidates (threaded)
    t0=time.time(); qtexts={}; done=[0]
    def work(i):
        qs=gen_questions(rows[i][2]); qtexts[i]=qs; done[0]+=1
        if done[0]%200==0: print(f"  gen {done[0]}/{len(cand)} ({time.time()-t0:.0f}s)",flush=True)
    with ThreadPoolExecutor(max_workers=6) as ex: list(ex.map(work,cand))
    allq=[(i,j,q) for i in cand for j,q in enumerate(qtexts.get(i,[]))]
    print(f"questions generated: {len(allq)}; embedding...",flush=True)
    qe=batch_embed([q for _,_,q in allq],tag="-q")
    qvecs={}
    for (i,_,_),e in zip(allq,qe): qvecs.setdefault(i,[]).append(e)
    def score(rs,n): return sum(1 for r in rs if r is not None and r<=n)/len(rs) if rs else 0.0
    def run(use_q):
        ranks=[]
        for q in corpus:
            v=qv[q["qid"]]; base=cscore_cache[q["qid"]]; best={}
            for i in range(len(rows)):
                sc=base[i]
                if use_q and i in qvecs:
                    for e in qvecs[i]:
                        s2=sum(map(mul,v,e))
                        if s2>sc: sc=s2
                k=(slugs[i],segs[i])
                if sc>best.get(k,-2.0): best[k]=sc
            ranked=sorted(best.items(),key=lambda kv:-kv[1]); ro={k:r for r,(k,_) in enumerate(ranked,1)}
            for cid,hp in q["golds"]:
                slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
                ranks.append(next((ro[(s,sg)] for (s,sg),_ in ranked if s==slug and sublist(gold,sg)),None))
        return ranks
    ctx_ranks=run(False); d2q_ranks=run(True)
    print(f"\ndoc2query stack test (gen={GEN_MODEL}, topK={TOPK}, NQ={NQ}) — recall@N\n")
    print(f"{'condition':<18}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(18+12*len(NS)))
    print(f"{'contextual':<18}"+"".join(f"{score(ctx_ranks,n):>12.3f}" for n in NS))
    print(f"{'+ doc2query':<18}"+"".join(f"{score(d2q_ranks,n):>12.3f}" for n in NS))
    print(f"\ndelta @12: {score(d2q_ranks,12)-score(ctx_ranks,12):+.3f}   @5: {score(d2q_ranks,5)-score(ctx_ranks,5):+.3f}"
          f"   @20: {score(d2q_ranks,20)-score(ctx_ranks,20):+.3f}")

if __name__=="__main__": main()

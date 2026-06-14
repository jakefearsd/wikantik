#!/usr/bin/env python3
"""
Contextual chunk embeddings spike (template version, no LLM) — does prepending each chunk's
structured context (page title + cluster + heading-path + page summary) before embedding raise
the dense score of the stuck 13-100 sections?

Re-embeds every chunk's CONTEXTUAL text in memory (qwen3-0.6b, batched /api/embed) and scores
the same GLOBAL section-ranking recall as bin/eval/spike-recall-ceiling.py, so it's directly
comparable to the raw baseline on the current fixed index: 0.397@5 / 0.603@12 / 0.912@100.

The context is built purely from frontmatter + heading_path (zero LLM calls) — the research
prediction is that our structured metadata is exactly the disambiguation missing from raw
chunk embeddings. Requires PG* env + the embedder. Pure Python.
"""
import csv, glob, json, math, os, re, subprocess, sys, time, urllib.request
from operator import mul

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB1="http://inference.jakefear.com:11434/api/embeddings"
EMBN="http://inference.jakefear.com:11434/api/embed"
M06="qwen3-embedding:0.6b"
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[5,12,20,50,100]
INCLUDE_SUMMARY=(sys.argv[1] != "nosummary") if len(sys.argv)>1 else True

def norm(s): return re.sub(r"\s+"," ",s).strip().lower()
def unit(v):
    n=math.sqrt(sum(x*x for x in v)) or 1.0
    return [x/n for x in v]
def page_meta():
    # slug -> (canonical_id, title, cluster, summary)
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
def ctx_text(slug, segs, text, meta):
    cid,title,cluster,summary=meta.get(slug,("",slug,"",""))
    head=f"Page: {title}"
    if cluster: head+=f" | Cluster: {cluster}"
    if segs: head+=f" | Section: {' > '.join(segs)}"
    if INCLUDE_SUMMARY and summary: head+=f"\nSummary: {summary}"
    return head+"\n\n"+text
def embed_one(text):
    req=urllib.request.Request(EMB1,data=json.dumps({"model":M06,"prompt":text}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: return unit(json.load(r)["embedding"])
def batch_embed(texts, batch=48):
    out=[]; t0=time.time()
    for i in range(0,len(texts),batch):
        chunk=texts[i:i+batch]
        req=urllib.request.Request(EMBN,data=json.dumps({"model":M06,"input":chunk}).encode(),
            headers={"Content-Type":"application/json"},method="POST")
        with urllib.request.urlopen(req,timeout=300) as r: embs=json.load(r)["embeddings"]
        out.extend(unit(e) for e in embs)
        if (i//batch)%20==0: print(f"  embed {len(out)}/{len(texts)} ({time.time()-t0:.0f}s)",flush=True)
    return out
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def main():
    meta=page_meta(); c2s={cid:s for s,(cid,_,_,_) in meta.items() if cid}
    corpus=load_corpus()
    rows=fetch_text()
    print(f"chunks={len(rows)}  include_summary={INCLUDE_SUMMARY}",flush=True)
    slugs=[r[0] for r in rows]; segs=[r[1] for r in rows]
    ctx=[ctx_text(r[0],r[1],r[2],meta) for r in rows]
    vecs=batch_embed(ctx)
    ranks=[]; catr={}
    for qi,q in enumerate(corpus,1):
        qv=embed_one(INSTR+q["query"])
        best={}
        for s,sg,v in zip(slugs,segs,vecs):
            sc=sum(map(mul,qv,v)); k=(s,sg)
            if sc>best.get(k,-2.0): best[k]=sc
        ranked=sorted(best.items(),key=lambda kv:-kv[1])
        rank_of={k:i for i,(k,_) in enumerate(ranked,1)}
        for cid,hp in q["golds"]:
            slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            r=next((rank_of[(s,sg)] for (s,sg),_ in ranked if s==slug and sublist(gold,sg)),None)
            ranks.append(r); catr.setdefault(q["cat"],[]).append(r)
        print(f"  ...score {qi}/{len(corpus)}",flush=True)
    def rec(rs,n): return sum(1 for r in rs if r is not None and r<=n)/len(rs) if rs else 0.0
    print(f"\nCONTEXTUAL embeddings (template: title+cluster+heading+summary={INCLUDE_SUMMARY}) — recall@N\n")
    print(f"{'scope':<14}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(14+12*len(NS)))
    print(f"{'RAW baseline':<14}{0.397:>12.3f}{0.603:>12.3f}{0.662:>12.3f}{0.853:>12.3f}{0.912:>12.3f}")
    print(f"{'CONTEXTUAL':<14}"+"".join(f"{rec(ranks,n):>12.3f}" for n in NS))
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in catr: print(f"{cat.lower():<14}"+"".join(f"{rec(catr[cat],n):>12.3f}" for n in NS))
    none=sum(1 for r in ranks if r is None)
    print(f"\nnone(no match)={none}/{len(ranks)}   12->100 lift=+{rec(ranks,100)-rec(ranks,12):.3f}")
    print(f"vs raw @12: {rec(ranks,12)-0.603:+.3f}   @5: {rec(ranks,5)-0.397:+.3f}   @100: {rec(ranks,100)-0.912:+.3f}")

if __name__=="__main__": main()

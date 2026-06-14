#!/usr/bin/env python3
"""
Chunk size + overlap probe (no Java re-index). Reconstructs each section's full text from the
existing heading-attributed chunks, re-windows it at several (size, overlap) configs, embeds each
window CONTEXTUALLY (page frontmatter prefix, like production), and scores global section recall —
directly comparable to the current contextual baseline (recall@12 0.735 / @100 0.971).

Configs isolate the two axes:
  (120,  0)  finer, no overlap
  (120, 40)  finer, WITH overlap   -> (120,0) vs (120,40) isolates overlap's effect
  (240, 60)  coarser, with overlap -> vs (120,*) tests size

A section is covered if any of its windows ranks in top-N. Requires PG* env + embedder. Pure Python.
"""
import csv, glob, json, math, os, re, subprocess, time, urllib.request
from operator import mul

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB1="http://inference.jakefear.com:11434/api/embeddings"
EMBN="http://inference.jakefear.com:11434/api/embed"
M06="qwen3-embedding:0.6b"
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[5,12,20,50,100]
CONFIGS=[(120,0),(120,40),(240,60)]   # (size_tokens, overlap_tokens)
CHARS_PER_TOK=4

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
def fetch_sections():
    # (page, segs) -> full section text (chunks concatenated in chunk_index order)
    sql=("SELECT c.page_name, array_to_string(c.heading_path,'>'), c.chunk_index, "
         "translate(c.text, chr(10)||chr(13)||chr(9), '   ') "
         "FROM kg_content_chunks c JOIN content_chunk_embeddings e ON e.chunk_id=c.id "
         "WHERE e.model_code='%s' ORDER BY c.page_name, c.chunk_index;"%MODEL_CODE)
    out=subprocess.run(["psql","-tA","-F","\t","-c",sql],capture_output=True,text=True,check=True).stdout
    sect={}
    for ln in out.splitlines():
        if not ln.strip(): continue
        pr=ln.split("\t")
        if len(pr)<4: continue
        page,hp,ci,txt=pr[0],tuple(norm(x) for x in pr[1].split(">") if x.strip()),pr[2],pr[3]
        sect.setdefault((page,hp),[]).append((int(ci),txt))
    return {k:" ".join(t for _,t in sorted(v)) for k,v in sect.items()}
def windows(text,size,ov):
    w=size*CHARS_PER_TOK; step=max(1,(size-ov)*CHARS_PER_TOK)
    if len(text)<=w: return [text]
    return [text[i:i+w] for i in range(0,len(text),step) if text[i:i+w].strip()] or [text]
def ctx(slug,segs,text,meta):
    cid,title,cluster,summary=meta.get(slug,("",slug,"",""))
    h=f"Page: {title}"
    if cluster: h+=f" | Cluster: {cluster}"
    if segs: h+=f" | Section: {' > '.join(segs)}"
    if summary: h+=f"\nSummary: {summary}"
    return h+"\n\n"+text
def embed_one(t):
    req=urllib.request.Request(EMB1,data=json.dumps({"model":M06,"prompt":t}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: return unit(json.load(r)["embedding"])
def batch_embed(texts,batch=48,tag=""):
    out=[]; t0=time.time()
    for i in range(0,len(texts),batch):
        req=urllib.request.Request(EMBN,data=json.dumps({"model":M06,"input":texts[i:i+batch]}).encode(),
            headers={"Content-Type":"application/json"},method="POST")
        with urllib.request.urlopen(req,timeout=300) as r: out.extend(unit(e) for e in json.load(r)["embeddings"])
        if (i//batch)%30==0: print(f"  embed{tag} {len(out)}/{len(texts)} ({time.time()-t0:.0f}s)",flush=True)
    return out
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

def main():
    meta=page_meta(); c2s={cid:s for s,(cid,_,_,_) in meta.items() if cid}
    corpus=load_corpus(); sect=fetch_sections()
    print(f"sections={len(sect)}",flush=True)
    qv={q["qid"]:embed_one(INSTR+q["query"]) for q in corpus}
    def rec(rs,n): return sum(1 for r in rs if r is not None and r<=n)/len(rs) if rs else 0.0
    rows=[]
    for (size,ov) in CONFIGS:
        keys=[]; texts=[]
        for (page,segs),full in sect.items():
            for w in windows(full,size,ov):
                keys.append((page,segs)); texts.append(ctx(page,segs,w,meta))
        print(f"\nconfig size={size} ov={ov}: {len(texts)} windows",flush=True)
        vecs=batch_embed(texts,tag=f"-{size}/{ov}")
        ranks=[]
        for q in corpus:
            v=qv[q["qid"]]; best={}
            for k,vv in zip(keys,vecs):
                sc=sum(map(mul,v,vv))
                if sc>best.get(k,-2.0): best[k]=sc
            ranked=sorted(best.items(),key=lambda kv:-kv[1]); ro={k:r for r,(k,_) in enumerate(ranked,1)}
            for cid,hp in q["golds"]:
                slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
                ranks.append(next((ro[(s,sg)] for (s,sg),_ in ranked if s==slug and sublist(gold,sg)),None))
        rows.append(((size,ov),len(texts),[rec(ranks,n) for n in NS]))
    print(f"\nChunk size/overlap probe — global section recall@N (contextual embeddings)\n")
    print(f"{'config':<16}{'windows':>9}"+"".join(f"{('recall@'+str(n)):>11}" for n in NS))
    print("-"*(25+11*len(NS)))
    print(f"{'CURRENT (Java)':<16}{'18437':>9}{0.471:>11.3f}{0.735:>11.3f}{0.809:>11.3f}{0.926:>11.3f}{0.971:>11.3f}")
    for (size,ov),nw,rs in rows:
        print(f"{('win '+str(size)+'/'+str(ov)):<16}{nw:>9}"+"".join(f"{r:>11.3f}" for r in rs))
    print(f"\n(overlap effect = compare 120/0 vs 120/40 @12; size effect = 120/* vs 240/60)")

if __name__=="__main__": main()

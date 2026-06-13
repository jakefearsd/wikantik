#!/usr/bin/env python3
"""
Cross-encoder reranker measurement (the shippable option).

Reranks each gold page's sections through a TEI /rerank endpoint (default a
bge-reranker on localhost:8085) and reports recall@S vs the dense baseline AND the
LLM-reranker ceiling, plus per-call latency (p50/p95). This is the table that answers
"does the fast reranker keep the recall lift?" — recall like the LLM, latency like ~ms.

  baseline dense max : 0.191 / 0.309 / 0.426 / 0.632  @ 1/2/3/5
  LLM ceiling (3-4.5s): 0.324 / 0.515 / 0.588 / 0.691

Requires PG* env + a running TEI reranker. Usage: python3 bin/eval/spike-tei-rerank.py [TEI_URL]
"""
import csv, glob, json, os, re, struct, subprocess, sys, time, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
TEI=(sys.argv[1] if len(sys.argv)>1 else "http://localhost:8085")+"/rerank"
SS=[1,2,3,5]; SNIP=1400; MAXSEC=20

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
        page,hp,_,txt=pr[0],pr[1],pr[2],pr[3]
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        pages.setdefault(page,{}).setdefault(segs,[]).append(txt)
    out2={}
    for pg,secs in pages.items():
        out2[pg]=[(segs," ".join(chs)[:SNIP]) for segs,chs in secs.items()][:MAXSEC]
    return out2
def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))
_lat=[]
def rerank(query,texts):
    body={"query":query,"texts":texts}
    req=urllib.request.Request(TEI,data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    t0=time.time()
    with urllib.request.urlopen(req,timeout=60) as r: res=json.load(r)
    _lat.append(time.time()-t0)
    # TEI returns [{"index":i,"score":s}, ...] sorted desc
    return [d["index"] for d in res]
def main():
    c2s=cid2slug(); corpus=load_corpus()
    goldslugs=sorted({c2s[cid] for q in corpus for cid,_ in q["golds"] if cid in c2s})
    pages=fetch(goldslugs)
    cats={}; tot={s:[] for s in SS}
    for q in corpus:
        cache={}
        for cid,hp in q["golds"]:
            slug=c2s.get(cid); secs=pages.get(slug,[]); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            if not secs: rank=None
            else:
                if slug not in cache:
                    # include the heading path in the passage (the LLM probe did; headings
                    # carry the signal for short/keyword queries)
                    passages=[(" > ".join(sg)+" — "+t) for sg,t in secs]
                    try: cache[slug]=rerank(q["query"],passages)
                    except Exception as e: cache[slug]=[]; print("rerank err:",e,file=sys.stderr)
                order=cache[slug]  # section indices (0-based), best first
                gi=next((i for i,(segs,_) in enumerate(secs) if sublist(gold,segs)),None)
                rank=(order.index(gi)+1) if (gi is not None and gi in order) else None
            c=cats.setdefault(q["cat"],{s:[] for s in SS})
            for s in SS:
                hit=1.0 if (rank and rank<=s) else 0.0
                c[s].append(hit); tot[s].append(hit)
    def avg(x): return sum(x)/len(x) if x else 0.0
    lat=sorted(_lat); p=lambda qq:(lat[int(qq*(len(lat)-1))] if lat else 0)
    print("Cross-encoder reranker (TEI) — gold-section recall@S, per-page frame\n")
    print(f"{'method':<20}"+"".join(f"{('recall@S='+str(s)):>13}" for s in SS))
    print("-"*(20+13*len(SS)))
    print(f"{'dense max (current)':<20}"+f"{0.191:>13.3f}{0.309:>13.3f}{0.426:>13.3f}{0.632:>13.3f}")
    print(f"{'LLM ceiling (3-4s)':<20}"+f"{0.324:>13.3f}{0.515:>13.3f}{0.588:>13.3f}{0.691:>13.3f}")
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in cats:
            c=cats[cat]; print(f"{('cross-enc '+cat[:9]).lower():<20}"+"".join(f"{avg(c[s]):>13.3f}" for s in SS))
    print(f"{'cross-enc OVERALL':<20}"+"".join(f"{avg(tot[s]):>13.3f}" for s in SS))
    print(f"\nlatency per /rerank call: p50={p(0.5)*1000:.0f}ms  p95={p(0.95)*1000:.0f}ms  (n={len(lat)})")

if __name__=="__main__": main()

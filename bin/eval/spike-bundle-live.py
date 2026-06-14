#!/usr/bin/env python3
"""
Faithful LIVE bundle measurement — the Phase-1 exit gate the always-on JUnit tier stubs.

Replicates DefaultBundleAssemblyService end-to-end against the live stack:
  retrieve (live /api/search, top-20 pages)
  -> per-page section shortlist (best chunk per heading-path, top-5 per page; SectionAssembler)
  -> listwise rerank over ALL candidate sections (gemma4:e4b, think:false, JSON ranking)
  -> dedup by (slug, heading-path) -> top-N
and scores gold-section recall@N. Reports DENSE-order vs RERANKED-order on the SAME candidate
set, so the number is the reranker's isolated contribution (the Phase-1 lever).

Baseline to beat (max-chunk 0.6B, instruction prefix): 0.412@5 / 0.544@12.
Requires PG* env + the live deployment (/api/search) + the reranker model on the inference host.
"""
import csv, glob, json, os, re, struct, subprocess, sys, time, urllib.parse, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
CHAT_URL="http://inference.jakefear.com:11434/api/chat"
SEARCH="http://localhost:8080/api/search"
M06="qwen3-embedding:0.6b"
RERANK_MODEL=(sys.argv[1] if len(sys.argv)>1 else "gemma4:e4b")
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[5,12]; PAGES_PER_Q=int(sys.argv[2]) if len(sys.argv)>2 else 20
SECTIONS_PER_PAGE=int(sys.argv[3]) if len(sys.argv)>3 else 5; MAXSEC=12; SNIP=240

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
_qc={}
def qembed(query):
    if query in _qc: return _qc[query]
    req=urllib.request.Request(EMB_URL,data=json.dumps({"model":M06,"prompt":INSTR+query}).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=120) as r: v=json.load(r)["embedding"]
    _qc[query]=v; return v
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
    pages={}  # page -> section(segs) -> list of (vec, text)
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

_lat=[]
def llm_rerank(query,cands):
    # cands: list of (slug, segs, score, text). Mirror LlmSectionReranker.buildPrompt.
    lines=[f"{i}. [{' > '.join(s[1])}] {s[3][:SNIP]}" for i,s in enumerate(cands,1)]
    prompt=("Query: "+query+"\n\nRank the passages from MOST to LEAST relevant to the query. "
            'Respond ONLY as JSON: {"ranking":[passage numbers, best first]}.\n\nPassages:\n'
            +"\n".join(lines))
    body={"model":RERANK_MODEL,"stream":False,"format":"json","think":False,
          "messages":[{"role":"system","content":""},{"role":"user","content":prompt}]}
    req=urllib.request.Request(CHAT_URL,data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    t0=time.time()
    with urllib.request.urlopen(req,timeout=180) as r: content=json.load(r)["message"]["content"]
    _lat.append(time.time()-t0)
    order=[]
    try:
        for v in json.loads(content).get("ranking",[]):
            n=int(v)
            if 1<=n<=len(cands) and n not in order: order.append(n)
    except Exception: order=[]
    # apply order (1-based), append unranked in original order (safe degrade)
    used=set(); out=[]
    for n in order: out.append(cands[n-1]); used.add(n)
    for i,c in enumerate(cands,1):
        if i not in used: out.append(c)
    return out

def dedup_topn(cands,n):
    seen=set(); out=[]
    for c in cands:
        k=(c[0],c[1])
        if k in seen: continue
        seen.add(k); out.append(c)
        if len(out)>=n: break
    return out

def recall(bundle,golds,c2s):
    if not golds: return 0.0
    cov=0
    for cid,hp in golds:
        slug=c2s.get(cid); gold=tuple(norm(x) for x in hp.split(">") if x.strip())
        if any(p==slug and sublist(gold,sg) for p,sg,_,_ in bundle): cov+=1
    return cov/len(golds)

def main():
    c2s=cid2slug(); corpus=load_corpus()
    cand_pages={q["qid"]:search_pages(q["query"]) for q in corpus}
    pages=fetch(sorted({s for v in cand_pages.values() for s in v if s}))
    catd={}; tot={"dense":{n:[] for n in NS},"rerank":{n:[] for n in NS}}
    for qi,q in enumerate(corpus,1):
        q06=qembed(q["query"])
        # per-page shortlist: best chunk per section, top-SECTIONS_PER_PAGE per page
        cands=[]
        for s in cand_pages[q["qid"]]:
            psecs=[]
            for segs,chunks in pages.get(s,{}).items():
                sc=max(cos(q06,v) for v,_ in chunks)
                txt=max(chunks,key=lambda ct:cos(q06,ct[0]))[1]
                psecs.append((s,segs,sc,txt))
            psecs.sort(key=lambda x:-x[2])
            cands.extend(psecs[:SECTIONS_PER_PAGE])
        dense=sorted(cands,key=lambda x:-x[2])
        try: reranked=llm_rerank(q["query"],dense)
        except Exception as e:
            print(f"  rerank fail q{qi}: {e}",flush=True); reranked=dense
        cd=catd.setdefault(q["cat"],{"dense":{n:[] for n in NS},"rerank":{n:[] for n in NS}})
        for n in NS:
            rd=recall(dedup_topn(dense,n),q["golds"],c2s)
            rr=recall(dedup_topn(reranked,n),q["golds"],c2s)
            cd["dense"][n].append(rd); tot["dense"][n].append(rd)
            cd["rerank"][n].append(rr); tot["rerank"][n].append(rr)
        print(f"  ...{qi}/{len(corpus)}",flush=True)
    def avg(x): return sum(x)/len(x) if x else 0.0
    lat=sorted(_lat); p=lambda qq:(lat[int(qq*(len(lat)-1))] if lat else 0)
    print(f"\nLIVE bundle measurement — gold-section recall@N (dense vs {RERANK_MODEL} rerank, same candidates)\n")
    print(f"{'method':<22}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(22+12*len(NS)))
    print(f"{'baseline (max-chunk)':<22}{0.412:>12.3f}{0.544:>12.3f}")
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in catd:
            print(f"{('dense '+cat[:8]).lower():<22}"+"".join(f"{avg(catd[cat]['dense'][n]):>12.3f}" for n in NS))
            print(f"{('rerank '+cat[:8]).lower():<22}"+"".join(f"{avg(catd[cat]['rerank'][n]):>12.3f}" for n in NS))
    print("-"*(22+12*len(NS)))
    print(f"{'dense OVERALL':<22}"+"".join(f"{avg(tot['dense'][n]):>12.3f}" for n in NS))
    print(f"{'rerank OVERALL':<22}"+"".join(f"{avg(tot['rerank'][n]):>12.3f}" for n in NS))
    print(f"\nrerank latency: p50={p(0.5):.1f}s  p95={p(0.95):.1f}s  (n={len(lat)}, model={RERANK_MODEL})")

if __name__=="__main__": main()

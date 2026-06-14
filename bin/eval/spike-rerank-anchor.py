#!/usr/bin/env python3
"""
Reranker anchoring tiebreaker — is the listwise reranker's zero-lift result real, or an
artifact of feeding it candidates already sorted by dense score (which it may just confirm)?

Three conditions on the SAME candidate set per query (per-page shortlist of live /api/search,
top-20 pages x top-5 sections), scored on gold-section recall@N:
  (a) dense            — candidates in dense-score order (no rerank)
  (b) rerank_anchored  — gemma4:e4b reranks the dense-sorted list (production behavior)
  (c) rerank_shuffled  — gemma4:e4b reranks a SHUFFLED list (judgment without input-order anchor)

If (c) != (b): the reranker's verdict depends on input order — it CAN re-rank, the bundle is
hobbling it by feeding pre-sorted input. If (c) == (b) == (a): the reranker genuinely does not
change top-N recall. Decisive either way.

Requires PG* env + live /api/search + the reranker model. Fixed shuffle seed for reproducibility.
"""
import csv, glob, json, os, random, re, struct, subprocess, sys, time, urllib.parse, urllib.request
from operator import mul

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
EMB_URL="http://inference.jakefear.com:11434/api/embeddings"
CHAT_URL="http://inference.jakefear.com:11434/api/chat"
M06="qwen3-embedding:0.6b"
RERANK_MODEL=(sys.argv[1] if len(sys.argv)>1 else "gemma4:e4b")
INSTR="Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "
NS=[5,12]; PAGES_PER_Q=20; SECTIONS_PER_PAGE=5; SNIP=240
RNG=random.Random(20260614)

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
    url="http://localhost:8080/api/search?"+urllib.parse.urlencode({"q":query})
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
    pages={}
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
    lines=[f"{i}. [{' > '.join(s[1])}] {s[3][:SNIP]}" for i,s in enumerate(cands,1)]
    prompt=("Query: "+query+"\n\nRank the passages from MOST to LEAST relevant to the query. "
            'Respond ONLY as JSON: {"ranking":[passage numbers, best first]}.\n\nPassages:\n'+"\n".join(lines))
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
    tot={"dense":{n:[] for n in NS},"anchored":{n:[] for n in NS},"shuffled":{n:[] for n in NS}}
    order_changed=0
    for qi,q in enumerate(corpus,1):
        q06=qembed(q["query"])
        cands=[]
        for s in cand_pages[q["qid"]]:
            psecs=[]
            for segs,chunks in pages.get(s,{}).items():
                sc=max(cos(q06,v) for v,_ in chunks)
                txt=max(chunks,key=lambda ct:cos(q06,ct[0]))[1]
                psecs.append((s,segs,sc,txt))
            psecs.sort(key=lambda x:-x[2]); cands.extend(psecs[:SECTIONS_PER_PAGE])
        dense=sorted(cands,key=lambda x:-x[2])
        shuf=dense[:]; RNG.shuffle(shuf)
        try: anchored=llm_rerank(q["query"],dense)
        except Exception: anchored=dense
        try: shuffled=llm_rerank(q["query"],shuf)
        except Exception: shuffled=shuf
        # did the shuffled-input rerank produce a different top-12 than the anchored one?
        if [(_s,_g) for _s,_g,_,_ in dedup_topn(anchored,12)] != [(_s,_g) for _s,_g,_,_ in dedup_topn(shuffled,12)]:
            order_changed+=1
        for n in NS:
            tot["dense"][n].append(recall(dedup_topn(dense,n),q["golds"],c2s))
            tot["anchored"][n].append(recall(dedup_topn(anchored,n),q["golds"],c2s))
            tot["shuffled"][n].append(recall(dedup_topn(shuffled,n),q["golds"],c2s))
        print(f"  ...{qi}/{len(corpus)}",flush=True)
    def avg(x): return sum(x)/len(x) if x else 0.0
    lat=sorted(_lat); p=lambda qq:(lat[int(qq*(len(lat)-1))] if lat else 0)
    print(f"\nReranker anchoring tiebreaker — gold-section recall@N (model={RERANK_MODEL})\n")
    print(f"{'condition':<22}"+"".join(f"{('recall@'+str(n)):>12}" for n in NS))
    print("-"*(22+12*len(NS)))
    for k,label in (("dense","dense (no rerank)"),("anchored","rerank dense-order"),("shuffled","rerank shuffled-in")):
        print(f"{label:<22}"+"".join(f"{avg(tot[k][n]):>12.3f}" for n in NS))
    print(f"\ntop-12 set differed (anchored vs shuffled) on {order_changed}/{len(corpus)} queries")
    print(f"rerank latency p50={p(0.5):.1f}s (n={len(lat)})")
    print(f"\nDECISION: if 'shuffled-in' != 'dense-order', the reranker re-ranks (input-order anchoring was")
    print(f"masking it) -> feed the bundle unordered candidates. If all three match, reranker is recall-dead.")

if __name__=="__main__": main()

#!/usr/bin/env python3
"""
LLM-as-reranker CEILING probe (the "don't ship this" baseline).

Question: does reranking lift the gold section's rank AT ALL here? Uses a strong LLM
already on the host (qwen3.5:9b) as a listwise reranker over each gold page's sections
(one call per query+page), in the per-page frame, and reports recall@S vs the dense
baseline (max-chunk: 0.19/0.31/0.43/0.63 @ 1/2/3/5). Also reports per-call latency so the
"too slow to ship" point is visible — a fast cross-encoder targets the same recall at ~ms.

Requires PG* env + the LLM on the inference host.
"""
import csv, glob, json, os, re, struct, subprocess, sys, time, urllib.request

PAGES="docs/wikantik-pages"; CSV_PATH="eval/bundle-corpus/queries.csv"
MODEL_CODE="qwen3-embedding-0.6b"
GEN_URL="http://inference.jakefear.com:11434/api/generate"
LLM=(sys.argv[1] if len(sys.argv)>1 else "qwen3.5:9b"); SS=[1,2,3,5]; SNIP=240; MAXSEC=15

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
        parts=ln.split("\t")
        if len(parts)<4: continue
        page,hp,ci,txt=parts[0],parts[1],parts[2],parts[3]
        segs=tuple(norm(x) for x in hp.split(">") if x.strip())
        pages.setdefault(page,{}).setdefault(segs,[]).append((int(ci),txt))
    # collapse to ordered sections with snippet text
    out2={}
    for pg,secs in pages.items():
        lst=[]
        for segs,chunks in secs.items():
            text=" ".join(t for _,t in sorted(chunks))[:SNIP]
            lst.append((segs,text))
        out2[pg]=lst[:MAXSEC]
    return out2

def sublist(needle,hay):
    n=len(needle); return n>0 and any(hay[i:i+n]==needle for i in range(0,len(hay)-n+1))

_lat=[]
def llm_rank(query,sections):
    lines=[f"{i}. [{' > '.join(s[0])}] {s[1]}" for i,s in enumerate(sections,1)]
    prompt=("Query: "+query+"\n\nRank the passages below from MOST to LEAST relevant to the query. "
            "Output ONLY the passage numbers separated by commas, best first, nothing else.\n\nPassages:\n"
            +"\n".join(lines))
    body={"model":LLM,"prompt":prompt,"stream":False,"think":False,"options":{"temperature":0}}
    req=urllib.request.Request(GEN_URL,data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json"},method="POST")
    t0=time.time()
    with urllib.request.urlopen(req,timeout=180) as r: resp=json.load(r)["response"]
    _lat.append(time.time()-t0)
    order=[]
    for tok in re.findall(r"\d+",resp):
        n=int(tok)
        if 1<=n<=len(sections) and n not in order: order.append(n)
    return order  # 1-based section indices, best first

def main():
    c2s=cid2slug(); corpus=load_corpus()
    goldslugs=sorted({c2s[cid] for q in corpus for cid,_ in q["golds"] if cid in c2s})
    pages=fetch(goldslugs)
    cats={}; tot={s:[] for s in SS}
    done=0
    for q in corpus:
        # rank each gold page once for this query
        ranking_cache={}
        for cid,hp in q["golds"]:
            slug=c2s.get(cid); secs=pages.get(slug,[])
            gold=tuple(norm(x) for x in hp.split(">") if x.strip())
            if not secs:
                rank=None
            else:
                if slug not in ranking_cache:
                    try: ranking_cache[slug]=llm_rank(q["query"],secs)
                    except Exception: ranking_cache[slug]=[]
                order=ranking_cache[slug]
                # position of the first section matching the gold
                gold_idx=next((i+1 for i,(segs,_) in enumerate(secs) if sublist(gold,segs)),None)
                rank=(order.index(gold_idx)+1) if (gold_idx in order) else None
            c=cats.setdefault(q["cat"],{s:[] for s in SS})
            for s in SS:
                hit=1.0 if (rank and rank<=s) else 0.0
                c[s].append(hit); tot[s].append(hit)
        done+=1
        print(f"  ...{done}/{len(corpus)} queries", flush=True)
    def avg(x): return sum(x)/len(x) if x else 0.0
    lat=sorted(_lat); p=lambda q:(lat[int(q*(len(lat)-1))] if lat else 0)
    print(f"\nLLM-as-reranker ({LLM}, listwise) — gold-section recall@S, per-page frame\n")
    print(f"{'method':<16}"+"".join(f"{('recall@S='+str(s)):>13}" for s in SS))
    print("-"*(16+13*len(SS)))
    print(f"{'dense max (base)':<16}"+f"{0.191:>13.3f}{0.309:>13.3f}{0.426:>13.3f}{0.632:>13.3f}")
    for cat in ("SIMILARITY","RELATIONAL","BOUNDARY"):
        if cat in cats:
            c=cats[cat]; print(f"{('llm '+cat[:8]).lower():<16}"+"".join(f"{avg(c[s]):>13.3f}" for s in SS))
    print(f"{'llm OVERALL':<16}"+"".join(f"{avg(tot[s]):>13.3f}" for s in SS))
    print(f"\nlatency per rerank call: p50={p(0.5):.1f}s  p95={p(0.95):.1f}s  (n={len(lat)}) — the 'don't ship' cost.")

if __name__=="__main__": main()

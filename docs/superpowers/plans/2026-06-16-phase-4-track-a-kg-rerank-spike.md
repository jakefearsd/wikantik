# Phase 4 Track A — KG Retrieval-Value Spike — Implementation Plan

> **For agentic workers:** This is an **agent-run experiment with decision gates and two human touchpoints**, not a feature build. Execute it **inline** (superpowers:executing-plans), pausing at the GATE and HUMAN-TOUCHPOINT markers — NOT subagent-driven (the gates need judgment + the user's input). Code tasks (T1–T3) are TDD; execution tasks (T4–T8) are exact commands + expected output + decision criteria. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Measure whether the KG rerank improves *relational* retrieval when coverage is good — a falsifiable ceiling-then-frontier experiment — and land the decision (does it help / which model / what cost) cheaply, against local/dev only.

**Architecture:** Build a tiny RELATIONAL-slice harness + cost logging, validate the slice empirically (headroom + reachability), establish the ceiling with the best extractor on the slice's pages, then sweep cheaper models for the cost frontier. The boost toggle is a deploy-time property → each on/off comparison is two redeploys.

**Tech Stack:** Python eval scripts (`bin/eval/`), `wikantik-extract-cli` (slice extraction), local Tomcat deploy (`bin/redeploy.sh`), Postgres (`kg_*` tables), Ollama @ `inference.jakefear.com:11434`, optional Anthropic API.

**Spec:** `docs/superpowers/specs/2026-06-16-phase-4-kg-quality-design.md` · **ADRs:** 0002, 0007.

**Verified facts (from recon — use these):**
- Harness `spike-api-bundle.py`: GET `/api/bundle?q=`, section recall@5/@12 (gold = `canonicalId` + `headingPath` contiguous-sublist), prints a per-category table; default base `http://localhost:8080`; no category flag.
- `wikantik.search.graph.boost` (default 0.2, currently **0 "TEMP DIAGNOSTIC"** in `tomcat/tomcat-11/lib/wikantik-custom.properties`) is read once at startup → **flip = edit the property + `bin/redeploy.sh`**. Companion props: `graph.max-hops`=2, `graph.weight.mention.floor`=0.5, tier weights. Needs `kg_edges` + `chunk_entity_mentions` + `kg_nodes` populated.
- Slice extraction: `java -jar wikantik-extract-cli/target/wikantik-extract-cli.jar --jdbc-url … --jdbc-user wikantik --jdbc-password-env POSTGRES_PASSWORD --ollama-url http://inference.jakefear.com:11434 --ollama-model <TAG> --page-pattern "<GLOB>" --concurrency 2 --report <json>`. `--page-pattern` globs `ContentChunkRepository.listDistinctPageNames()`. Admin `POST /admin/knowledge-graph/extract-mentions` is full-corpus only.
- Extractor model selection: `EntityExtractorFactory.create(config)` on `wikantik.knowledge.extractor.backend = ollama|claude|disabled`; claude path uses `ANTHROPIC_API_KEY` + `wikantik.knowledge.extractor.claude.model`. **The CLI hardwires the Ollama extractor — no `--extractor claude` flag** (T3 adds one, conditionally).
- Cost telemetry: `ExtractionBatchRunner` logs `Bootstrap extraction: page=… elapsedMs=…` (wall-time per page). **Token counts are NOT logged** (T2 adds them).
- Reachability: `kg_content_chunks(page_name, …)` ⋈ `chunk_entity_mentions(chunk_id,node_id,confidence)` ⋈ `kg_nodes(id,name)`; co-mention BFS via the `/knowledge-mcp` `traverse` tool or `MentionIndex.findRelatedPages(pageName, limit)`.
- Gold: 17 RELATIONAL rows, ~10 query IDs (r01–r10, r03 skipped), most have **2 gold rows often on 2 pages** (multi-hop). `r02` ("what config enables the KG rerank + default") spans two pages — a strong self-referential test.

---

## Files

- Create: `bin/eval/spike-kg-rerank.py` — RELATIONAL-slice recall@k runner (T1).
- Create: `bin/eval/kg-slice-validate.py` — A0 headroom + reachability → rationale table (T4).
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ClaudeEntityExtractor.java` + `OllamaEntityExtractor.java` — log token/elapsed telemetry (T2).
- Modify (conditional): `wikantik-extract-cli/.../BootstrapExtractionCli.java` + `EntityExtractorFactory` wiring — `--extractor claude` (T3).
- Create: `eval/kg-spike/` — results dir (rationale table, per-run recall + cost JSON, the final findings doc).

---

### Task 1: RELATIONAL-slice harness (`spike-kg-rerank.py`)

**Files:** Create `bin/eval/spike-kg-rerank.py`; Test `bin/eval/test_spike_kg_rerank.py`

A thin wrapper over the existing `spike-api-bundle.py` logic that (a) filters the corpus to `category == "RELATIONAL"` (or a passed slice of query_ids), (b) hits `/api/bundle`, (c) reports section recall@5/@12 over the slice, and (d) writes a JSON result `{boost_label, recall_at_5, recall_at_12, per_query:[…]}` to a path so on/off runs are diffable.

- [ ] **Step 1: Write the failing test** — `test_spike_kg_rerank.py`: a unit test of the pure pieces (no live server). Import the script's `load_slice(csv_path, query_ids=None)` and `section_hit(gold_cid, gold_hp, sections)`; assert (a) `load_slice` returns only RELATIONAL rows when `query_ids=None`, and only the named ids when given a list; (b) `section_hit` is True when a section's `canonicalId==gold` and its `headingPath` contains the gold heading-path segments as a contiguous sublist, False otherwise. (Copy the `prefix()`/sublist matcher + `norm()` from `spike-api-bundle.py` verbatim so the metric is identical.)
- [ ] **Step 2: Run it — expect FAIL** (`python3 -m pytest bin/eval/test_spike_kg_rerank.py -q`).
- [ ] **Step 3: Implement `spike-kg-rerank.py`** — reuse `spike-api-bundle.py`'s `load_corpus`/`fetch_bundle`/`norm`/`prefix` verbatim; add `load_slice(csv, query_ids)`; `main(base_url, query_ids_file=None, out_json=None, boost_label=None)` that scores the slice, prints `RELATIONAL recall@5/@12`, and writes `out_json`. CLI: `python3 bin/eval/spike-kg-rerank.py <base_url> [--slice <ids.txt>] [--out <json>] [--label off|on]`.
- [ ] **Step 4: Run the test — expect PASS.**
- [ ] **Step 5: Commit** (`feat(eval): RELATIONAL-slice KG-rerank harness`).

---

### Task 2: Extraction token/elapsed telemetry

**Files:** Modify `wikantik-main/.../knowledge/extraction/ClaudeEntityExtractor.java` (+ `OllamaEntityExtractor.java`); Test the same package's existing extractor test.

So A2's cost/quality curve has real numbers. Add a single structured INFO log per extraction call with input/output tokens (Claude: `response.usage().inputTokens()/outputTokens()`; Ollama: the response's `prompt_eval_count`/`eval_count` if present, else log `tokens=unknown`) + elapsedMs.

- [ ] **Step 1: Write the failing test** — in the extractor's test (mirror the existing extractor unit test): stub the client to return a response with a known usage (Claude) / eval counts (Ollama); capture Log4j2 output (a `ListAppender` or assert via a small seam) and assert a line containing `extractor.cost` with the token numbers is emitted. If capturing logs is heavy, instead extract a pure `static String costLine(String model, long inTok, long outTok, long elapsedMs)` helper and unit-test its format, then call it from the extractor.
- [ ] **Step 2: Run it — expect FAIL.**
- [ ] **Step 3: Implement** the `costLine(...)` helper + a `LOG.info` call at the end of each extractor's per-call path. Format: `extractor.cost model={} inTok={} outTok={} elapsedMs={}`. Never throw if usage is null — log `inTok=-1 outTok=-1`.
- [ ] **Step 4: Run the test — expect PASS; then `mvn -q -pl wikantik-main -am test-compile`.**
- [ ] **Step 5: Commit** (`feat(kg): log per-call extractor token/elapsed cost`).

---

### Task 3 *(CONDITIONAL — only if the ceiling escalates to Claude; gated on HUMAN-TOUCHPOINT #2)*: CLI `--extractor claude`

**Files:** Modify `wikantik-extract-cli/.../BootstrapExtractionCli.java` (Args + wiring); Test `BootstrapExtractionCliTest` (the Args parse).

Default ceiling uses the best **local** model (T5, no code). Only build this if best-local is inconclusive and you escalate to Claude on the slice.

- [ ] **Step 1: Write the failing test** — `Args.parse(["--extractor","claude", …])` yields `extractor=="claude"`; default is `"ollama"`; an unknown value → usage error (exit 2).
- [ ] **Step 2: FAIL.** **Step 3: Implement** — add `--extractor ollama|claude` to `Args`; when `claude`, build the extractor via `EntityExtractorFactory.create(...)` with `backend=claude` (reads `ANTHROPIC_API_KEY` from env; model from `--claude-model`, default `claude-haiku-4-5`); keep `--ollama-model` for the ollama path. Fail fast with a clear message if `claude` is chosen and `ANTHROPIC_API_KEY` is unset.
- [ ] **Step 4: PASS** (`mvn -q -pl wikantik-extract-cli -am test -Dtest=BootstrapExtractionCliTest -Dsurefire.failIfNoSpecifiedTests=false`). **Step 5: Commit** (`feat(extract-cli): --extractor claude for slice-scoped premium extraction`).

---

### Task 4: A0 — slice validation script + derive the slice

**Files:** Create `bin/eval/kg-slice-validate.py`; output `eval/kg-spike/slice-rationale.md` + `eval/kg-spike/slice-ids.txt`.

The rigor centerpiece — **mechanical**, reproducible, shown.

- [ ] **Step 1:** Set up the local deploy at the **baseline** (`boost=0`) state: ensure `tomcat/tomcat-11/lib/wikantik-custom.properties` has `wikantik.search.graph.boost = 0`, then `bin/redeploy.sh`; confirm `curl -s localhost:8080/api/health` → 200 and `/api/bundle?q=test` returns sections.
- [ ] **Step 2: Resolve gold canonical_ids → page slugs.** For the distinct `gold_canonical_id` values in the 17 RELATIONAL rows, query the slug:
  `bin/db/… ` — run `psql … -c "SELECT canonical_id, current_slug FROM page_canonical_ids WHERE canonical_id IN (…);"` (the table from the structural-spine subsystem). Write the slug set to `eval/kg-spike/slice-pages.txt`. (These ~8–10 pages are the extraction slice.)
- [ ] **Step 3: Implement `kg-slice-validate.py`** — for each RELATIONAL query:
  1. **Headroom:** call `/api/bundle?q=<query>` (boost=0) and record whether each gold (cid+heading) is hit@12. A query whose every gold is already hit → `headroom=NO` → excluded from the lift slice (kept as a labelled control).
  2. **Reachability:** resolve the query's entities (the simplest mechanical proxy: the `kg_nodes.name` values that appear as substrings in the query, OR call the deployed `QueryEntityResolver` path if exposed; otherwise SQL `SELECT name FROM kg_nodes WHERE position(lower(name) in lower(:query))>0`). For each gold page, get its mentioned entities (the recon SQL: `kg_content_chunks ⋈ chunk_entity_mentions ⋈ kg_nodes WHERE page_name=:slug`). Mark `reachable=YES` if any query-entity reaches any gold-page entity within `max-hops=2` in the co-mention graph (use `MentionIndex.findRelatedPages` semantics via SQL on `chunk_entity_mentions` shared-chunk counts, or the `/knowledge-mcp` `traverse` tool start=query-entity, max_depth=2, and check the gold page's entities appear).
  3. Emit a row: `query_id | query | gold pages | similarity hit/miss | query entities | gold-page entities | reachable? | IN/OUT + reason`.
- [ ] **Step 4: Run it** → `eval/kg-spike/slice-rationale.md` (the table) + `eval/kg-spike/slice-ids.txt` (the query_ids where `headroom=YES`). The **KG-trial slice** = those ids. Note in the table that reachability at boost=0 may be low *because coverage is low* — that's expected; the slice membership is driven by **headroom** (the objective filter), and reachability is recorded as context that A1's re-extraction will improve.
- [ ] **Step 5: Commit** (`feat(eval): KG-rerank slice validation (headroom + reachability)`). 
- [ ] **HUMAN-TOUCHPOINT #1:** present `slice-rationale.md`; ask the user to spot-check the borderline IN/OUT calls. Adjust `slice-ids.txt` per feedback.

---

### Task 5: A1 — establish the ceiling (best local model) + GATE

**Files:** outputs under `eval/kg-spike/` (`recall-off.json`, `recall-on-ceiling.json`, `extract-ceiling.json`).

- [ ] **Step 1: Baseline (boost OFF) on the slice.** With `boost=0` already deployed: `python3 bin/eval/spike-kg-rerank.py http://localhost:8080 --slice eval/kg-spike/slice-ids.txt --out eval/kg-spike/recall-off.json --label off`. Record RELATIONAL recall@12 (the rerank-off floor). *(Independent of KG coverage — rerank is off.)*
- [ ] **Step 2: Re-extract the slice's pages with the best LOCAL model.** First list available models: `curl -s http://inference.jakefear.com:11434/api/tags | grep -oE '"name":"[^"]+"'` and pick the strongest graph-extraction-capable tag (start from `gemma4-graph:12b`; prefer a larger one if available). Build the CLI jar if stale (`mvn -q -pl wikantik-extract-cli -am package -DskipTests`), then:
  `java -jar wikantik-extract-cli/target/wikantik-extract-cli.jar --jdbc-url jdbc:postgresql://localhost:5432/wikantik --jdbc-user wikantik --jdbc-password-env POSTGRES_PASSWORD --ollama-url http://inference.jakefear.com:11434 --ollama-model <BEST_LOCAL> --page-pattern "<glob covering slice-pages.txt>" --concurrency 2 --report eval/kg-spike/extract-ceiling.json`
  (Run once per slice page or use a `{a,b,c}`-style pattern if the CLI glob supports alternation; else loop per page.) Capture the per-page `elapsedMs` (+ tokens from T2's log) for the cost record.
- [ ] **Step 3: Turn the rerank ON.** Edit `wikantik-custom.properties` → `wikantik.search.graph.boost = 0.2` (leave `max-hops=2`, `mention.floor=0.5`); `bin/redeploy.sh`; confirm the startup log shows graph rerank **enabled** (grep catalina.out for "Graph rerank" — absence of "disabled").
- [ ] **Step 4: Boost-ON on the slice.** `python3 bin/eval/spike-kg-rerank.py http://localhost:8080 --slice eval/kg-spike/slice-ids.txt --out eval/kg-spike/recall-on-ceiling.json --label on`.
- [ ] **Step 5: Compute lift** = `recall-on-ceiling.json` − `recall-off.json` (RELATIONAL recall@12, and per-query). **GUARDRAIL:** confirm the bundle's candidate pool is larger than 12 (the rerank must have headroom to move a gold *into* the top-12); if `wikantik.bundle.dense.top_k` ≈ 300 this holds. If recall@12 can't move because the pool == 12, also report recall@5 over the top-12 pool.
- [ ] **GATE A1:**
  - **Lift (meaningful, e.g. ≥ +0.05 relational recall@12 and per-query improvements that aren't noise)** → the hypothesis is **real**; proceed to Task 7 (A2 frontier).
  - **No lift, but best-LOCAL is suspect** (the model may be too weak to establish the ceiling) → **escalate**: do Task 3 + **HUMAN-TOUCHPOINT #2 (Claude cost go-ahead)** + Task 6.
  - **No lift even at the true ceiling** → **STOP** (skip to Task 8 with a falsified finding; keep the KG as a human KB).
- [ ] **Step 6: Commit** the result JSONs + a one-paragraph note in `eval/kg-spike/findings.md`.

---

### Task 6 *(CONDITIONAL — A1 escalation to Claude)*: ceiling with the premium model

- [ ] **HUMAN-TOUCHPOINT #2:** confirm the user OKs the bounded Claude spend (slice = ~8–10 pages; est. cost from token counts × Anthropic pricing — state the estimate before running).
- [ ] **Step 1:** export `ANTHROPIC_API_KEY`; re-extract the slice pages with `--extractor claude --claude-model claude-haiku-4-5` (or a stronger Claude tier if justified) → `eval/kg-spike/extract-ceiling-claude.json` (token cost harvested from T2's log).
- [ ] **Step 2:** boost already ON → `python3 bin/eval/spike-kg-rerank.py … --out eval/kg-spike/recall-on-ceiling-claude.json --label on-claude`. Recompute lift vs `recall-off.json`.
- [ ] **GATE A1':** lift now → proceed to A2 (frontier includes Claude as the top rung); still no lift → **STOP**, falsified at the true ceiling. Commit findings.

---

### Task 7 *(only if a ceiling showed lift)*: A2 — cost frontier sweep

**Files:** `eval/kg-spike/frontier.csv` (model, recall@12 lift, fraction-of-ceiling, extract elapsedMs, tokens, est. $).

- [ ] For each model **below** the ceiling on the cost ladder (e.g. `gemma4-graph:12b`, a mid local tag, a low-cost API): (a) re-extract the **same slice pages** with that model (`--ollama-model` / `--extractor`), capturing cost; (b) the rerank is already ON → run `spike-kg-rerank.py` on the slice → recall@12; (c) append a `frontier.csv` row with lift, `fraction_of_ceiling = lift_model / lift_ceiling`, and cost.
- [ ] Build the **cost/quality curve** + a **projected corpus-wide rollout cost** = (median per-page tokens/$ for the chosen model) × (distinct corpus pages). Write to `findings.md`.
- [ ] **Commit** `frontier.csv` + `findings.md` update.

---

### Task 8: A3 — no-regression check + decision write-up

- [ ] **Step 1: Full-corpus no-regression.** With the candidate winning model's extraction live on the slice and boost ON, run the FULL corpus (`python3 bin/eval/spike-api-bundle.py http://localhost:8080`) and compare SIMILARITY + BOUNDARY recall@12 to the boost-OFF baseline (re-run `spike-api-bundle.py` with `boost=0` for the comparison). **The rerank must not regress non-relational categories.** Record both in `findings.md`. *(Note: only the slice pages have improved coverage, so this checks the rerank doesn't hurt; a true full-corpus number comes only after a rollout re-extraction.)*
- [ ] **Step 2: Write the decision.** `eval/kg-spike/findings.md` final section: the verdict (KG-rerank helps relational retrieval: yes/no), the ceiling lift, the cost-frontier sweet-spot model, the projected rollout cost, the no-regression result, and a clear **recommendation** (roll out / shelve the retrieval signal / re-test with a bigger eval slice). Restore `wikantik.search.graph.boost` to its pre-spike value (0) and `bin/redeploy.sh` unless rolling out.
- [ ] **Step 3: Update** the spec status line + the program memory with the outcome; if the verdict is "roll out", note the rollout (corpus-wide re-extraction with the chosen model + prod boost-on) is a **separate, gated plan** — not part of this spike.
- [ ] **HUMAN-TOUCHPOINT #3:** present `findings.md`; the user makes the roll-out / shelve call. **Commit.**

---

## Self-review notes (author)

- **Spec coverage:** A0 slice-validation (headroom + reachability + rationale + spot-check) → T4; A1 ceiling + falsifiable gate → T5 (+T3/T6 escalation); A2 cost frontier → T7; A3 decision + no-regression → T8; reuse-harness-100% → T1 wraps the existing runner; cost metric → T2; integrity guards (objective headroom, reproducible rule, shown work, pre-committed no-lift) → T4 + the GATE wording; local-not-prod → T5 step 1; recall-headroom guardrail → T5 step 5. Track B is out of scope (separate plan), per the spec. ✔
- **Type/name consistency:** `spike-kg-rerank.py` (T1) consumed by T5/T6/T7; `slice-ids.txt`/`slice-pages.txt` produced in T4, consumed in T5/T7; `extractor.cost` log (T2) harvested in T5/T6/T7; `--extractor claude` (T3) used in T6. ✔
- **Conditional tasks clearly gated:** T3/T6 fire only on the A1 escalation; T7 only if a ceiling lifts. The STOP paths are explicit. ✔
- **Confirm-at-run-time flagged:** the `page_canonical_ids` table/column names (T4 step 2), the strongest available local model tag (T5 step 2), the CLI glob's alternation support (T5 step 2), and the exact Ollama token-count fields (T2) are confirmed against the live system during execution rather than assumed.

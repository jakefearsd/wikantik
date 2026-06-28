# Corpus Internals Coverage (Theme A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six eval-surfaced corpus gaps so a grounded agent stops hallucinating Wikantik internals, verified by re-running `eval/agent-grounding`.

**Architecture:** This is **content work, not code**. Each target wiki page gets one authoritative, retrievable section added (or made retrievable). Edits are mirrored to two places: the version-controlled repo copy under `docs/wikantik-pages/<Page>.md`, and the **live prod corpus** (the eval's target) via the MCP wiki-content surface. The "test" cycle per page is: confirm the gap with `assemble_bundle`/`read_page` (failing) → edit → push to prod → re-verify with `assemble_bundle` (passing). The final task re-runs the full agent eval as the acceptance gate.

**Tech Stack:** MCP wiki-content tools (`read_page`, `update_page`, `assemble_bundle`); the Python eval harness (`eval/agent-grounding/run_eval.py` + `report.py`); git for the repo mirror.

## Global Constraints

- **MCP-only for wiki interaction.** Read/write/verify wiki content through the MCP tools (`read_page`, `update_page`, `assemble_bundle`) — never curl/REST/bash against the wiki. Per `[[feedback_wiki_content_skill_mcp_only]]`. (The eval harness in Task 6 is the one exception — it *is* the measurement tool and legitimately uses both REST `/api/bundle` and MCP.)
- **Prod is the acceptance target.** The eval hits `wiki.wikantik.com`. Every content fix MUST be pushed to prod via MCP. If the in-session `mcp__wikantik-admin__*` tools target local rather than prod, push to prod per `[[reference_prod_mcp_push_via_curl]]` (admin MCP at `https://wiki.wikantik.com/wikantik-admin-mcp`, key in `.env.prod` `MCP_ACCESS_KEYS`, `initialize`→`update_page`). Confirm the endpoint before pushing.
- **GPU/inference host must be UP.** `assemble_bundle` dense retrieval and re-embedding require `inference.jakefear.com:11434`. If it's powered off, this whole theme is blocked — escalate to the user; do not fake verification.
- **Targeted edits only.** Generic corpus enrichment has been measured to *hurt* recall (`[[reference_mcp_content_authoring_surface]]`). Add only the gap-closing facts below. After each push, confirm no recall regression on the other questions (Task 6 gate).
- **Async re-embed.** `update_page` re-embeds asynchronously. After a push, allow the re-embed to drain before `assemble_bundle` verification (poll/retry the bundle query; prod's `lucene-hnsw` backend reads the DB so no restart is needed, but the embedding write is not synchronous). Per `[[project_embedding_pipeline_async_by_design]]`.
- **Exact facts are authoritative** — copy the prose blocks in each task verbatim (they are the source-of-truth statements from `CLAUDE.md`). Do not paraphrase numbers (21 predicates, 0.60→0.74 recall, 9 entity + 5 content classes).

---

### Task 1: Baseline audit (read-only)

Establish the failing baseline for all six gaps so later tasks can prove improvement, and classify each gap as **missing-content** vs **present-but-not-retrievable**.

**Files:**
- Create: `eval/agent-grounding/runs/theme-a-baseline.md` (audit notes)
- Read (MCP): live `HybridRetrieval`, `KnowledgeGraphRerank`, `WikantikKnowledgeGraph`, `KgInclusionPolicy`

**Interfaces:**
- Produces: `theme-a-baseline.md` recording, per gap: the `assemble_bundle` query used, whether the fact appears in the returned sections (yes/no), and the relevant live page slug. Consumed by Tasks 2–5 to decide add-vs-restructure and by Task 6 as the before/after reference.

- [ ] **Step 1: Confirm the inference host is up**

Run: `curl -sS -m 10 http://inference.jakefear.com:11434/api/tags >/dev/null && echo UP || echo DOWN`
Expected: `UP`. If `DOWN`, stop and escalate — Theme A is blocked.

- [ ] **Step 2: Read each live target page via MCP**

For each of `HybridRetrieval`, `KnowledgeGraphRerank`, `WikantikKnowledgeGraph`, `KgInclusionPolicy`: call MCP `read_page` and note whether the gap fact (see Tasks 2–5) is present in the live body.

- [ ] **Step 3: Baseline each gap with assemble_bundle**

For each gap, call MCP `assemble_bundle` with the eval question's query and record whether the correct fact appears in the returned sections:

| Gap | assemble_bundle query |
|-----|------------------------|
| chunker fix | `What chunker bug was fixed to improve section recall and what config controls fragment merging?` |
| contextual embeddings | `What text prefix is prepended to sections when building dense embeddings and why?` |
| dense backends | `What are the three dense index backend options and which is the production default?` |
| rerank default | `Does hybrid search use the Knowledge Graph to rerank results by default?` |
| KG predicates | `How many KG predicates are in the wikantik.ttl T-Box and what external vocabularies do they map to?` |
| KG inclusion default | `Which pages contribute entities to the Knowledge Graph by default and how does a page override it?` |

- [ ] **Step 4: Write the baseline notes**

Record results in `eval/agent-grounding/runs/theme-a-baseline.md`: a table of `gap | live-page-has-fact? | bundle-surfaces-fact? | classification (missing-content | not-retrievable)`.

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/runs/theme-a-baseline.md
git commit -m "docs(eval): Theme A corpus-gap baseline audit"
```

---

### Task 2: HybridRetrieval — chunker fix, contextual embeddings, dense backends

Closes three gaps on one page. `docs/wikantik-pages/HybridRetrieval.md` currently has a `### Dense backend selection` section (line ~93) but **no** chunker-fix or contextual-embeddings section.

**Files:**
- Modify: `docs/wikantik-pages/HybridRetrieval.md`
- Push (MCP): live `HybridRetrieval`

**Interfaces:**
- Consumes: Task 1 classification for these three gaps.
- Produces: a live `HybridRetrieval` page whose sections surface (a) the chunker heading-fidelity fix + fragment config, (b) the contextual document-embedding prefix, (c) the three dense backends + docker1 default — each retrievable via `assemble_bundle`.

- [ ] **Step 1: Verify the gap (failing state)**

Call MCP `assemble_bundle` with `What chunker bug was fixed to improve section recall and what config controls fragment merging?`.
Expected: the returned sections do NOT state the force-emit-at-heading-boundary fix or `fragment_floor_tokens`/`overlap_tokens` (matches Task 1).

- [ ] **Step 2: Add the "Section recall levers" section to the repo page**

Append this section to `docs/wikantik-pages/HybridRetrieval.md` (after `### Dense backend selection`):

```markdown
## Section recall levers

Two changes moved global **section recall@12 from 0.60 to 0.74** (measured in
`eval/bundle-corpus/baseline-notes.md`):

### Chunker heading-fidelity fix

`ContentChunker` force-emits the merge-forward buffer **at each heading
boundary**, so early sections and the first H2 keep their own `heading_path`.
Before the fix they were mis-attributed to the *previous* heading — and
therefore mis-cited. Two config knobs tune fragment merging:

- `wikantik.chunker.fragment_floor_tokens` (default **24**) — fragments below
  this token count merge **forward** into the next chunk, adopting the
  destination heading.
- `wikantik.chunker.overlap_tokens` (default **40**) — token overlap carried
  between adjacent chunks.

### Contextual document embeddings

`EmbeddingTextBuilder.forDocument` prepends a context line before embedding
each section:

    Page: {title} | Cluster: {cluster} | Section: {heading}

followed by the frontmatter `summary`. This gives each section's vector its
page/cluster/heading context. The **query** side keeps its own instruction
prefix (the two prefixes are deliberately different).
```

> **Task 1 baseline correction:** the dense-backend gap is **already-works** —
> `HybridRetrieval` has a complete 3-option table and bundle ranks it #1. Do NOT
> add a dense-backend statement (it would risk a recall regression for zero
> gain). This task now covers ONLY the chunker fix + contextual embeddings.

- [ ] **Step 3: Push the merged body to prod via MCP**

Call MCP `read_page` for `HybridRetrieval` to get the current live body, splice in the Step 2 additions (chunker + contextual embeddings), then call MCP `update_page` with the merged content. Confirm the MCP endpoint targets prod (Global Constraints).

- [ ] **Step 4: Verify the fix (passing state)**

After allowing the async re-embed to drain (retry up to ~60s), call MCP `assemble_bundle` for the two chunker / contextual-embeddings queries from Task 1 Step 3.
Expected: each returns a section containing the new fact (force-emit + `fragment_floor_tokens`; the `Page: … | Cluster: … | Section: …` prefix).

- [ ] **Step 5: Commit**

```bash
git add docs/wikantik-pages/HybridRetrieval.md
git commit -m "docs(corpus): HybridRetrieval — chunker heading-fidelity fix + contextual embeddings"
```

---

### Task 3: Correct the "KG rerank is active" claim everywhere it appears

The grounded agent confidently said the KG reranker is "enabled by default." It is OFF (boost=0, never wired) and was shelved with zero net lift.

> **Task 1 baseline correction (expanded scope):** `KnowledgeGraphRerank`
> doesn't just *omit* the fact — it **actively asserts the wrong one**
> (`jspwiki.search.graphRerank.enabled = true`, wrong property + wrong default).
> The bundle surfaces this wrong content at ranks 1–3, AND **two other pages**
> (`WikantikSearchAndRetrieval`, `WikiSearchOptimization`) also describe KG
> rerank as active. A corrective banner on one page won't win if three pages
> contradict it, so this task fixes all three. (Edit only the rerank claims;
> leave the rest of those pages alone — targeted, no broad rewrite.)

**Files:**
- Modify: `docs/wikantik-pages/KnowledgeGraphRerank.md` (primary — add status banner)
- Push (MCP): live `KnowledgeGraphRerank`, `WikantikSearchAndRetrieval`, `WikiSearchOptimization`
- (The latter two may not have repo copies under `docs/wikantik-pages/`; if a repo copy exists, mirror the edit there, otherwise the prod MCP push is the only change. Read each live page first to find the exact stale sentence.)

**Interfaces:**
- Consumes: Task 1 classification for the rerank-default gap (missing + actively-wrong on 3 pages).
- Produces: no live page asserting KG rerank is active; `KnowledgeGraphRerank` carries the authoritative default-OFF/shelved banner that the bundle surfaces first.

- [ ] **Step 1: Verify the gap (failing state)**

Call MCP `assemble_bundle` with `Does hybrid search use the Knowledge Graph to rerank results by default?`.
Expected: the returned sections do NOT clearly state default-OFF / boost=0 / shelved (or are ambiguous enough that a model could conclude "enabled").

- [ ] **Step 2: Add the status banner to the repo page**

Insert this as the first section of the body in `docs/wikantik-pages/KnowledgeGraphRerank.md` (immediately after the H1 title):

```markdown
> **Status: OFF by default — shelved.** Knowledge-Graph reranking is **not**
> used in default hybrid search. The boost weight defaults to **0** and the
> page-level reranker was **never wired into production**. A 2026-06-16 ceiling
> spike measured **zero net lift** even with a Claude-quality KG: relational
> section relevance is not the same as entity-proximity. The rerank was shelved
> (Phase 4 Track A) and left **dormant, not removed**. Do not expect KG
> reranking to affect retrieval results unless it is explicitly re-enabled.
```

- [ ] **Step 3: Push the banner to prod via MCP**

Call MCP `read_page` for `KnowledgeGraphRerank`, splice the banner in after the H1, and (a) **remove/correct** the stale `jspwiki.search.graphRerank.enabled = true` assertion in the body so it no longer says rerank is on, then call MCP `update_page` with the merged content.

- [ ] **Step 4: Correct the two other stale pages**

For each of `WikantikSearchAndRetrieval` and `WikiSearchOptimization`: MCP `read_page`, find the sentence/section that describes KG rerank as active/enabled, and replace just that claim with: "KG reranking is **off by default** (boost=0, never wired into production; shelved 2026-06-16 after a measured zero-lift ceiling spike). See `KnowledgeGraphRerank`." Push each via MCP `update_page`. Do not otherwise rewrite the pages.

- [ ] **Step 5: Verify the fix (passing state)**

After the re-embeds drain, call MCP `assemble_bundle` with the Step 1 query.
Expected: the top sections state default-OFF / boost=0 / shelved, and NO returned section asserts rerank is active.

- [ ] **Step 6: Commit**

```bash
git add docs/wikantik-pages/KnowledgeGraphRerank.md
# include WikantikSearchAndRetrieval.md / WikiSearchOptimization.md if repo copies exist
git commit -m "docs(corpus): correct KG-rerank-is-active claim on all three pages"
```

---

### Task 4: WikantikKnowledgeGraph — 21 predicates + external vocab mappings — SKIPPED

> **Task 1 baseline correction: SKIP — already-works.** Live `WikantikKnowledgeGraph`
> already states "21 KG predicates… schema.org/SKOS/Dublin Core/PROV-O" and the
> bundle ranks that exact section #1 for the predicate query. No edit needed;
> adding more would risk a recall regression for zero gain. Task 6 will confirm
> the eval question scores well; if it still scores partial, the cause is answer
> phrasing, not corpus content — do NOT add content.

---

### Task 5: Make the KG-inclusion default retrievable (edit a page that ranks)

The eval's KG-inclusion question scored 0 on the bundle arm. `KgInclusionPolicy`
has correct content but is **not the problem to edit**:

> **Task 1 baseline correction (redirect):** the audit found `KgInclusionPolicy`
> **never appears in the bundle at all** for this query — it does not rank
> (likely `type: runbook` + `audience: humans` depressing agent retrieval).
> Therefore editing `KgInclusionPolicy` would NOT fix retrievability. Instead,
> add a concise inclusion-policy summary to a page that **already ranks #1** for
> KG queries — `WikantikKnowledgeGraph` — so the fact rides existing retrieval.
> Also bump `KgInclusionPolicy`'s own `summary`/`audience` so it has a chance to
> rank later, but the load-bearing fix is the summary on `WikantikKnowledgeGraph`.

**Files:**
- Push (MCP): live `WikantikKnowledgeGraph` (load-bearing), `KgInclusionPolicy` (metadata only)
- Modify (mirror): `docs/wikantik-pages/WikantikKnowledgeGraph.md`, `docs/wikantik-pages/KgInclusionPolicy.md`

**Interfaces:**
- Consumes: Task 1 classification for the KG-inclusion gap (not-retrievable; `KgInclusionPolicy` doesn't rank).
- Produces: a bundle that, for the inclusion query, returns a section stating default-exclude + the `kg_include` override.

- [ ] **Step 1: Verify the gap (failing state)**

Call MCP `assemble_bundle` with `Which pages contribute entities to the Knowledge Graph by default and how does a page override it?`.
Expected: no returned section answers default-exclude + `kg_include` override (and `KgInclusionPolicy` is absent from the results — matches Task 1).

- [ ] **Step 2: Add the inclusion summary to WikantikKnowledgeGraph**

In `docs/wikantik-pages/WikantikKnowledgeGraph.md`, append this section (and mirror it into the live page in Step 3):

```markdown
## Which pages contribute entities (KG inclusion)

**By default a page contributes NO entities to the Knowledge Graph** — inclusion
is **default-exclude**. A page is included only if its **cluster** has an
`include` row in `kg_cluster_policy`, OR its frontmatter sets `kg_include: true`.
A page overrides its cluster either way:

- `kg_include: true` — force the page **in**, regardless of cluster.
- `kg_include: false` — force the page **out**; this **beats** `cluster: include`.

System pages (Sandbox, Main, navigation) are always excluded. Full operator
guide: `KgInclusionPolicy`.
```

- [ ] **Step 3: Push to prod via MCP**

MCP `read_page` `WikantikKnowledgeGraph`, splice in the Step 2 section, MCP `update_page`. Then MCP `read_page` `KgInclusionPolicy` and `update_page` with an improved `summary` frontmatter line (e.g. "Which pages contribute entities to the Knowledge Graph by default, and how a page overrides inclusion with kg_include") and `audience: [humans, agents]` — metadata only, no body change.

- [ ] **Step 4: Verify the fix (passing state)**

After the re-embeds drain, call MCP `assemble_bundle` with the Step 1 query.
Expected: a returned section (from `WikantikKnowledgeGraph`) states default-exclude + the `kg_include` override semantics.

- [ ] **Step 5: Commit**

```bash
git add docs/wikantik-pages/WikantikKnowledgeGraph.md docs/wikantik-pages/KgInclusionPolicy.md
git commit -m "docs(corpus): surface KG-inclusion default on a page that ranks (WikantikKnowledgeGraph)"
```

---

### Task 6: Acceptance gate — re-run the agent eval

Prove the corpus edits moved the numbers and caused no regression.

**Files:**
- Run: `eval/agent-grounding/run_eval.py` + `eval/agent-grounding/report.py`
- Create: `eval/agent-grounding/runs/theme-a-after.md` (copy of the new scorecard + a before/after note)

**Interfaces:**
- Consumes: prior scorecard `eval/agent-grounding/runs/smoke-20260628T062534Z/scorecard.md` (baseline: cold 0.062, grounded_bundle 1.312, grounded_mcp 1.438).
- Produces: a fresh scorecard meeting the gate below.

- [ ] **Step 1: Confirm prerequisites**

`ANTHROPIC_API_KEY` set; the MCP bearer key available; inference host UP (re-run Task 1 Step 1). The six pages' re-embeds have drained (Tasks 2–5 verifications all passed).

- [ ] **Step 2: Run the full eval (dense)**

Run: `cd eval/agent-grounding && python3 run_eval.py` then `python3 report.py <new-run-dir>`
Expected: a new `runs/<ts>/scorecard.md` + `interface-findings.md`.

- [ ] **Step 3: Check the acceptance gate**

The run PASSES the gate iff ALL of:
- `grounded_mcp` mean correctness ≥ **1.438** (prior), and `grounded_bundle` mean ≥ **1.312**.
- The four questions whose content this theme actually changed each improve on the **mcp** arm vs prior (these were the hallucination regressions): `kg-rerank-default` ≥ 1 (was 0), `chunker-heading-fidelity` ≥ 1 (was 0), `contextual-embeddings-prefix` ≥ 1 (was 0), and `kg-inclusion-default` bundle ≥ 1 (was 0).
- **No regression:** no question's per-arm score drops below its prior value.

**Baseline caveat (do not chase these with more content):** Task 1 confirmed
`dense-backend-options` and `kg-predicates-count` are already correct and
bundle-rank-#1 in the live corpus (Tasks 3-dense and 4 were SKIPPED). If either
still scores below 2 in the re-run, the cause is answer phrasing / judge, NOT
missing content — record it as a finding; do **not** add corpus text (it would
risk a recall regression).

If a *content-changed* question still misses, re-open the matching Task 2/3/5, inspect `interface-findings.md` + the failing answer, strengthen the section, re-push, re-verify, then re-run.

- [ ] **Step 4: Record before/after and commit**

Write `eval/agent-grounding/runs/theme-a-after.md` with the before→after delta table (per-question + per-arm means).

```bash
git add eval/agent-grounding/runs/theme-a-after.md
git commit -m "docs(eval): Theme A after-scorecard — corpus gaps closed"
```

- [ ] **Step 5: Update memory**

Append a one-line pointer to the existing `[[reference_mcp_content_authoring_surface]]` (or a new `project_*` memory) noting the six gaps closed + the before/after numbers, so a future session knows these facts are now in the prod corpus.

---

## Self-Review

**Spec coverage (vs roadmap Theme A table; updated after Task 1 baseline):** rerank-OFF → Task 3 ✓ (expanded to 3 stale pages); chunker fix → Task 2 ✓; contextual embeddings → Task 2 ✓; dense backends → **already-works, no edit** (Task 1); KG predicates(21)+vocab → **already-works, Task 4 SKIPPED** (Task 1); KG-inclusion default → Task 5 ✓ (redirected to `WikantikKnowledgeGraph`, the page that ranks); eval-re-run gate → Task 6 ✓; targeted/no-regression constraint → Global Constraints + Task 6 gate ✓; GPU dependency → Global Constraints + Task 1 Step 1 ✓; MCP-only → Global Constraints ✓.

**Placeholder scan:** none — every content block is verbatim prose; every verification has an exact `assemble_bundle` query; the eval gate has numeric thresholds.

**Type/name consistency:** page slugs (`HybridRetrieval`, `KnowledgeGraphRerank`, `WikantikKnowledgeGraph`, `KgInclusionPolicy`) match the eval `expect_sources`; config keys (`wikantik.chunker.fragment_floor_tokens`, `wikantik.chunker.overlap_tokens`, `wikantik.search.dense.backend`) and numbers (21 predicates, 9+5 classes, 0.60→0.74) match `CLAUDE.md`.

# Knowledge-Graph Extraction Redesign — Design

**Date:** 2026-05-01
**Status:** Approved (brainstorming complete; pending implementation plan)
**Owner:** Jake Fear
**Related design docs:**
- [KnowledgeGraphConstructionPipeline](../../wikantik-pages/KnowledgeGraphConstructionPipeline.md) — the pipeline being replaced
- [KnowledgeProposals](../../wikantik-pages/KnowledgeProposals.md) — proposal lifecycle (consolidator updates this)
- [ProposingKnowledgeGraphEdges](../../wikantik-pages/ProposingKnowledgeGraphEdges.md) — agent runbook for `propose_knowledge`

## 1. Problem statement

The current entity-extraction pipeline produces too much noise, takes too long, and the proposals are uneven in quality.

**Concrete numbers from the production database (probed 2026-05-01):**
- 15,577 chunks across 923 pages.
- 43,856 pending proposals in `kg_proposals` (43,867 total — only 11 reviewed). The reviewer cannot keep up.
- 0 rows in `chunk_entity_mentions` — mention attribution is silently broken in today's pipeline.
- Top duplicate offenders: `Concept` × 341, `Agent` × 97, `LLM` × 96, `AI` × 75, `Kafka` × 71, `Redis` × 60, `PostgreSQL` × 50.
- Current KG: 1,020 nodes (917 typed `article`, i.e. page-titles), 876 edges. Effectively no proper entity-nodes exist yet, which is why every real entity the extractor finds looks "new".

**Three failure modes drive the noise:**
1. **Duplicates** — the same logical proposal arrives from N chunks/pages and lands as N rows because `kg_proposals` has zero uniqueness constraint.
2. **Hallucinated / weakly-supported relations** — the prompt asks for "reasoning" but gives the model no grounding requirement.
3. **Wrong canonical form / wrong type** — `GitHub Inc.`/`GitHub`/`github` proposed as separate entities; `Kafka` typed as `Concept` because the alphabetical-200-cap dictionary doesn't include it.

**Targets:**
- ≤500 high-quality proposals per full-corpus run (from today's tens of thousands).
- Single-digit-hour wall time on a 1000-page corpus (from today's day-or-more).
- Local-only (Ollama) for the production path; Claude available as an opt-in experimental judge.

## 2. Approach overview

Five changes, in priority order by quality impact:

1. **DB-level dedup.** Add `signature` column + partial unique index on `kg_proposals(signature) WHERE status='pending'`. Same logical proposal arriving twice becomes an upsert that merges support arrays, not a second row. This single change collapses today's volume by an estimated 3–10× before any LLM behaviour changes.
2. **Per-page extraction** instead of per-chunk. One Ollama call per page (gemma4-assist:latest, 131K context), with a hard 12-entity / 8-relation budget in the prompt. Reduces LLM calls by ~16× (15,577 chunks → 923 pages) and lets the model reason about a page's entity set holistically.
3. **Evidence-grounded output.** The prompt requires every entity and every relation to carry an `evidence_span` — a verbatim ≤200-character quote from the page. A deterministic post-LLM verifier rejects anything whose `evidence_span` isn't a substring of the source text. Bright-line filter for hallucinations; no LLM cost.
4. **Retrieval-augmented dictionary.** Replace the alphabetical-first-200 dictionary with a per-page top-K (default 50) retrieval against `bge-m3` embeddings of existing nodes. Pages get a *relevant* dictionary instead of `[A-Z]_first_200`. Materially reduces "wrong canonical form" duplicates.
5. **Pluggable `ProposalJudge` stage.** Default `NoOpProposalJudge` (production); optional `OllamaProposalJudge` and gated experimental `ClaudeProposalJudge` for an A/B comparison decided from a `bin/kg-judge-experiment.sh` side-by-side report.

A consolidator runs between extraction and upsert: groups proposals by canonical signature, votes on best display name, aggregates evidence into a `support[]` array. The reviewer sees one row per distinct claim with N supporting quotes, not N rows for the same claim.

A mention-attribution pass replaces today's broken per-chunk LLM mention extraction: deterministic exact-string-match against each chunk's text after extraction completes. No LLM cost, more accurate than today.

## 3. Architecture

```
┌────────────────┐    ┌────────────────────────────┐    ┌─────────────────────┐    ┌─────────────────────┐
│ Page source    │ →  │ PageExtractor              │ →  │ ProposalConsolidator│ →  │ ProposalJudge       │
│ (1 page, full) │    │ (LLM call, returns         │    │ (deterministic      │    │ (no-op default;     │
│                │    │  evidence-grounded         │    │  dedup + support    │    │  Claude opt-in)     │
│                │    │  entities + relations)     │    │  aggregation)       │    │                     │
└────────────────┘    └────────────────────────────┘    └─────────────────────┘    └──────────┬──────────┘
                                                                                              │
                                                                                              ▼
                                                                                  ┌───────────────────────┐
                                                                                  │ ProposalUpserter      │
                                                                                  │ (ON CONFLICT on       │
                                                                                  │  signature → merge    │
                                                                                  │  support arrays)      │
                                                                                  └───────────────────────┘
                                                                                              │
                                                ┌─────────────────────────────────────────────┘
                                                ▼
                                  ┌──────────────────────────┐
                                  │ MentionAttributor        │
                                  │ (deterministic per-chunk │
                                  │  exact-string-match;     │
                                  │  no LLM)                 │
                                  └──────────────────────────┘
                                                │
                                                ▼
                                         chunk_entity_mentions
```

### 3.1 Module placement

- New code lives in `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/` alongside the existing extractor classes.
- New interfaces in `wikantik-api/src/main/java/com/wikantik/api/knowledge/`:
  - `PageExtractor` — takes a `Page` (name, body, headings) and returns `PageExtractionResult`.
  - `ProposalJudge` — pluggable verdict step with `accept | reject | rewrite`.
- The current per-chunk `EntityExtractor` interface and `AsyncEntityExtractionListener` are deleted in Phase 3 — chunks remain the unit for embeddings, retrieval, and mention attribution; they're no longer the unit for LLM calls.
- `BootstrapEntityExtractionIndexer` is refactored in place to drive the new pipeline. The `Status` shape stays so admin UI / logs / metrics keep working.
- New SQL migration adds `kg_proposals.signature` (indexed) and a unique partial index on `(signature) WHERE status = 'pending'`.

### 3.2 Critical interface seams (testable in isolation)

```java
public interface PageExtractor {
    String code();                                            // e.g. "ollama:gemma4-assist"
    PageExtractionResult extract(Page page, ExtractionContext ctx);
}

public record Page(String name, String pageId, String body,
                   String summary, List<String> headings) {}

public record ExtractionContext(List<KgNode> dictionaryNodes,    // top-K from retrieval
                                Map<String, Object> hints) {}

public record PageExtractionResult(
        String extractorCode,
        List<ExtractedEntity> entities,    // already grounded (failed ones dropped)
        List<ExtractedRelation> relations, // already grounded
        Stats stats) {}

public record ExtractedEntity(String name, String type, String evidenceSpan, double confidence) {}
public record ExtractedRelation(String source, String target, String predicate,
                                String evidenceSpan, double confidence) {}
public record Stats(int rawEntities, int rawRelations,
                    int rejectedUngrounded, int rejectedBannedName,
                    Duration latency) {}

public interface ProposalJudge {
    String code();
    Verdict judge(ConsolidatedProposal proposal, JudgeContext ctx);
}

public sealed interface Verdict {
    record Accept(double finalConfidence, String rationale) implements Verdict {}
    record Reject(String reasonCode, String rationale) implements Verdict {}
    record Rewrite(ConsolidatedProposal rewritten, String rationale) implements Verdict {}
}
```

`ProposalConsolidator.consolidate(List<PageExtractionResult>) → List<ConsolidatedProposal>` is a pure function (the easiest test target).
`MentionAttributor.attribute(Page, List<ResolvedNode>) → List<MentionRow>` is deterministic — no LLM, just exact-string matching.

## 4. Per-page extraction

### 4.1 System prompt (frozen)

```
You extract a small, high-quality set of named entities and relations from a single
wiki page. Output STRICT JSON only:

{
  "entities": [
    { "name": str, "type": Person|Organization|Place|Event|Product|Technology|Concept,
      "evidence_span": str, "confidence": 0..1 }   // max 12
  ],
  "relations": [
    { "source": str, "target": str, "predicate": str,
      "evidence_span": str, "confidence": 0..1 }   // max 8
  ]
}

Hard rules:
- evidence_span MUST be a verbatim ≤200-char quote from the page below. No paraphrase.
- Both source and target of every relation MUST appear in entities[].
- name MUST be a proper-noun, Title-Case canonical form. NEVER emit type-labels
  ("Concept", "Agent", "Process", "System", "User", "Software", "Data") as a name.
- Prefer Known Entities (below) verbatim. Only propose a brand-new entity if it is
  clearly named, distinct, and not in the Known list.
- If the page genuinely has no proper-noun entities, return empty arrays. That is correct.
- Reasoning is implicit in evidence_span. No "reasoning" field.
```

The 7-type closed enum (Person | Organization | Place | Event | Product | Technology | Concept) was settled on after rejecting `Project` as too overlapping with `Product`/`Technology`/`Organization` to be consistently typed. The schema is the contract; output that doesn't conform is dropped at parse time.

### 4.2 Retrieval-augmented dictionary

At indexer-start: generate `bge-m3:latest` embeddings for all existing KG nodes (`name + " :: " + type + " :: " + summary_from_source_page`); persist in a new `kg_node_embeddings` table keyed by `(node_id, content_hash)` so re-runs only re-embed nodes whose name/type/source changed. Cost: ~1 minute wall-clock for 1020 nodes once.

Per page: embed the page summary (or compute it as the mean of the page's existing `content_chunk_embeddings` — already 100% covered in the DB), then `ORDER BY embedding <-> page_embedding LIMIT 50`. Those 50 nodes become the per-page Known Entities dictionary fed to the prompt.

This single change should largely eliminate the `Kafka`/`Redis`/`PostgreSQL` duplicate-of-existing-node failures once those entities exist as proper KG nodes (Phase 5+ — the first run still treats them as new because the current KG is dominated by article-typed nodes (917 of 1020), with only a handful of hubs and untyped nodes that don't align with what the extractor finds).

### 4.3 Evidence grounding verifier

After parsing the LLM response, every entity and every relation runs through:

```java
boolean isGrounded(String evidenceSpan, String pageBody) {
    String norm = whitespaceNormalize(evidenceSpan);
    return whitespaceNormalize(pageBody).contains(norm);
}
```

Anything that fails is rejected before it reaches the consolidator, with counter `wikantik_kg_extractor_rejected_ungrounded_total{kind=entity|relation}`. Spans longer than 200 characters are also rejected (`reason="span_too_long"`).

### 4.4 Per-page caps

`--max-entities-per-page` (default 12) and `--max-relations-per-page` (default 8) are enforced both in the prompt text and in the parser (truncate by descending confidence on overflow). Page-level caps in the prompt are advisory; the parser cap is the bright line.

## 5. Deterministic dedup + DB schema

### 5.1 In-memory consolidator

```java
public final class ProposalConsolidator {

    /**
     * Pure function: groups proposals by signature, aggregates support evidence,
     * picks best display name by vote, returns one ConsolidatedProposal per
     * unique node-signature and edge-signature.
     */
    public List<ConsolidatedProposal> consolidate(Stream<PageExtractionResult> pageResults) {
        Map<String, NodeBuilder> nodes = new HashMap<>();
        Map<String, EdgeBuilder> edges = new HashMap<>();

        pageResults.forEach(pr -> {
            for (ExtractedEntity e : pr.entities()) {
                String sig = NodeSignature.of(e.name(), e.type()).asHash();
                nodes.computeIfAbsent(sig, k -> new NodeBuilder(sig, e.name(), e.type()))
                     .addSupport(pr.pageName(), e.evidenceSpan(), e.confidence(),
                                 pr.extractorCode());
            }
            for (ExtractedRelation r : pr.relations()) {
                String sig = EdgeSignature.of(
                    canonicalize(r.source()), canonicalize(r.target()), r.predicate()).asHash();
                edges.computeIfAbsent(sig, k -> new EdgeBuilder(sig, r.source(), r.target(), r.predicate()))
                     .addSupport(pr.pageName(), r.evidenceSpan(), r.confidence(),
                                 pr.extractorCode());
            }
        });

        // NodeBuilder.build() and EdgeBuilder.build() each emit a ConsolidatedProposal
        // with the appropriate Kind discriminator and the chosen displayName (by vote).
        return Stream.concat(nodes.values().stream().map(NodeBuilder::build),
                             edges.values().stream().map(EdgeBuilder::build))
                     .toList();
    }
}
```

`NodeBuilder` / `EdgeBuilder` are package-private mutable accumulators; `ConsolidatedProposal` is the immutable output record. Keeping the builders package-private avoids leaking mutable state across the seam.

`NodeSignature.of(name, type)` is `(normalize(name), type.toLowerCase())` where `normalize` is: trim, collapse whitespace, strip surrounding punctuation, NFC-normalize Unicode, lower-case for comparison only. The canonical name we **store** is the most-frequent original-cased form across supporting pages (so "GitHub" beats "github" beats "Github" by vote).

`EdgeSignature.of(source, target, predicate)` does the same canonicalization on both endpoints plus a predicate normalizer (lower-case + a small synonym map: `is_a`/`is-a`, `created_by`/`created-by`, etc.).

The output:

```java
public record ConsolidatedProposal(
    Kind kind,                       // NEW_NODE or NEW_EDGE
    String signature,                // SHA-256 hex of canonical key
    String displayName,              // best-vote canonical form
    String type,                     // for nodes
    String source, target, predicate,// for edges
    List<SupportEvidence> support,   // one per page that contributed
    double aggregateConfidence) {}   // mean(confidences), penalised by single-source

public record SupportEvidence(
    String sourcePage, String evidenceSpan, double confidence, String extractorCode) {}
```

Reviewer sees ONE row for `Python --is_a--> ProgrammingLanguage` with 14 supporting pages and their best evidence quotes, instead of 14 rows.

### 5.2 Schema migration `V020__kg_proposals_signature.sql`

```sql
ALTER TABLE kg_proposals
  ADD COLUMN IF NOT EXISTS signature       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS support         JSONB DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS support_count   INT  DEFAULT 0,
  ADD COLUMN IF NOT EXISTS first_seen_at   TIMESTAMP DEFAULT NOW(),
  ADD COLUMN IF NOT EXISTS last_seen_at    TIMESTAMP DEFAULT NOW();

-- Pending proposals are deduped; reviewed ones (approved/rejected) are history
-- and may legitimately repeat if the same claim re-emerges after rejection
-- (the rejection table catches that separately for edges).
CREATE UNIQUE INDEX IF NOT EXISTS kg_proposals_pending_signature_uq
  ON kg_proposals (signature)
  WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS kg_proposals_signature_idx ON kg_proposals (signature);
```

A second migration `V021__kg_node_embeddings.sql` adds the node-embedding cache:

```sql
CREATE TABLE IF NOT EXISTS kg_node_embeddings (
    node_id      UUID         PRIMARY KEY REFERENCES kg_nodes(id) ON DELETE CASCADE,
    content_hash VARCHAR(64)  NOT NULL,    -- SHA-256 of (name || type || summary)
    embedding    vector(1024) NOT NULL,    -- bge-m3 dimensionality
    embedded_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS kg_node_embeddings_ivfflat_idx
  ON kg_node_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);
```

### 5.3 Upsert pattern

```sql
WITH merged AS (
    SELECT signature,
           -- Dedupe support entries by source_page so re-runs of the same page
           -- don't accumulate duplicate evidence rows. Newer entry wins on conflict.
           (
               SELECT jsonb_agg(s ORDER BY (s->>'sourcePage'))
               FROM (
                   SELECT DISTINCT ON (s->>'sourcePage') s
                   FROM jsonb_array_elements(
                       COALESCE(kp.support, '[]'::jsonb) || ?::jsonb
                   ) AS s
                   ORDER BY (s->>'sourcePage'), (s->>'capturedAtMs')::bigint DESC NULLS LAST
               ) deduped
           ) AS support_merged
    FROM kg_proposals kp
    WHERE kp.signature = ? AND kp.status = 'pending'
)
INSERT INTO kg_proposals
    (proposal_type, source_page, proposed_data, confidence, reasoning,
     signature, support, support_count, first_seen_at, last_seen_at)
VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, 1, NOW(), NOW())
ON CONFLICT (signature) WHERE status = 'pending' DO UPDATE
SET support       = COALESCE((SELECT support_merged FROM merged), EXCLUDED.support),
    support_count = jsonb_array_length(COALESCE((SELECT support_merged FROM merged), EXCLUDED.support)),
    confidence    = GREATEST(kg_proposals.confidence, EXCLUDED.confidence),
    last_seen_at  = NOW()
RETURNING (xmax = 0) AS inserted;
```

The `(xmax = 0)` trick lets the indexer count `inserted` vs `merged` for status reporting. The `DISTINCT ON (sourcePage)` in the merge guarantees idempotency: re-running extraction over the same page does not double-count its support entry — the latest extraction wins for that page, but the support array doesn't grow.

### 5.4 Migration of existing proposals: wipe, not backfill

Per agreement during brainstorming, the 43,856 existing pending proposals are wiped. Quoting the user: "they're a mess." A clean slate is cheaper than a backfill that mostly produces "merged garbage." Recovery is via a `pg_dump --where` snapshot taken immediately before the wipe (Phase 5).

## 6. ProposalJudge interface seam

The judge runs on **consolidated** proposals (one verdict per logical claim, not per LLM emission), so even a heavy judge model only sees a few hundred candidates per run.

### 6.1 Implementations

- **`NoOpProposalJudge`** — production default. Identity transform: every input returns `Accept`.
- **`OllamaProposalJudge`** — production-ready opt-in via `--judge ollama --judge-model qwen3.5:9b` (or any locally-served judge model). Same prompt contract as the Claude judge.
- **`ClaudeProposalJudge`** — experimental. Hard-gated behind `wikantik.kg.judge.allow_claude=false` in `wikantik.properties`. The experiment script bypasses the gate; `kg-extract.sh` does not.

`Reject.reasonCode` is a small closed enum: `ungrounded`, `redundant_with_existing_node`, `wrong_type`, `too_generic`, `weak_support`. The indexer emits `wikantik_kg_judge_rejected_total{reason=...}` counters keyed by reason.

`Rewrite` is the canonicalization escape hatch: a judge that sees `GitHub Inc.` proposed when `GitHub` already exists returns `Rewrite(proposal_with_name=GitHub, reasonCode=canonicalized)`. The indexer applies the rewrite and re-keys the signature before upsert.

### 6.2 Judge prompt (frozen for cache hits)

```
SYSTEM:
You are a strict reviewer for a small, curated knowledge graph. Reject anything
that fails ANY of these tests:

1. Ungrounded: the evidence_span doesn't actually support the claim.
2. Too generic: the entity/predicate is so general it adds no graph value
   (Concept, Agent, System, Software, "is_related_to").
3. Redundant: a near-identical node already exists in the dictionary.
4. Wrong type: the type doesn't match the entity (e.g. "Kafka" typed as Person).
5. Weak support: only one weak quote and aggregate_confidence < 0.55.

When the entity is right but the form is wrong (e.g. "GitHub Inc." but "GitHub"
exists), rewrite to the canonical form rather than reject.

Output strict JSON: { "verdict": "accept"|"reject"|"rewrite",
                      "reason_code": str, "rationale": <=30 words,
                      "rewritten": { ...same shape as input... } | null }

USER:
Candidate: <ConsolidatedProposal as JSON>
Supporting page excerpts (one per support entry, ≤500 chars each): <inline>
Nearby existing nodes: <neighborhoodNodes as compact list>
```

### 6.3 Claude experiment workflow

```bash
# 1. Run extraction with no-op judge (production behaviour)
bin/kg-extract.sh --judge none

# 2. Run the experiment harness against the resulting consolidated proposals,
#    sampling N at random, with Claude as judge
bin/kg-judge-experiment.sh \
    --sample 100 \
    --judge claude \
    --anthropic-key-env ANTHROPIC_API_KEY \
    --output reports/judge-experiment-2026-05-DD.json
```

The experiment script (`JudgeExperimentCli.java` in `wikantik-extract-cli`) reads pending proposals, runs both `NoOpProposalJudge` and the chosen judge, and writes a side-by-side report:

```json
{
  "sampled": 100,
  "total_pending": 487,
  "judge_codes": ["noop", "claude:claude-haiku-4-5"],
  "claude_verdicts": { "accept": 42, "reject": 51, "rewrite": 7 },
  "claude_reject_reasons": { "too_generic": 28, "redundant_with_existing_node": 18, "weak_support": 5 },
  "tokens_used": 184213,
  "estimated_cost_usd": 0.42,
  "examples": [...]
}
```

Decision input: read the report; if quality delta justifies cost, flip `wikantik.kg.judge.allow_claude=true` and `--judge claude` becomes available in `kg-extract.sh`.

## 7. CLI & operational surface

### 7.1 `bin/kg-extract.sh`

```
Database (unchanged):
  --jdbc-url <url>               (default jdbc:postgresql://localhost:5432/jspwiki)
  --jdbc-user <name>             (default jspwiki)
  --jdbc-password-env <VAR>      (preferred)

Extractor:
  --ollama-url <url>             (default http://inference.jakefear.com:11434)
  --ollama-model <tag>           (default gemma4-assist:latest)
  --concurrency <1..6>           (default 2 — pages in flight in parallel)
  --timeout-ms <ms>              (default 180000)

Quality knobs (defaults are recommended):
  --max-entities-per-page <N>    (default 12)
  --max-relations-per-page <N>   (default 8)
  --confidence-threshold <0..1>  (default 0.55 — slightly relaxed from today's 0.6
                                   because the consolidator's aggregateConfidence
                                   penalises single-source proposals, so the
                                   raw model confidence has more headroom)
  --dictionary-top-k <N>         (default 50)
  --node-embedding-model <tag>   (default bge-m3:latest)

Judge (default off):
  --judge none|ollama|claude     (default none)
  --judge-model <id>             (model name when --judge ollama|claude)
  --anthropic-key-env <VAR>      (Claude only; refused unless allow_claude=true)

Scope / sampling:
  --max-pages <N>                (default 0 = full corpus)
  --page-pattern <glob>          (e.g. 'Knowledge*' to extract just one cluster)
  --rebuild-node-embeddings      (force re-embed all nodes)

Run mode:
  --dry-run                      (extract+consolidate+judge, skip upsert, print summary)
  --report <path.json>           (write side-by-side run report)
  --poll-seconds <N>             (default 30)
  -h, --help
```

### 7.2 Flags retired from current CLI

- All `--prefilter-*` flags (page-level extraction makes per-chunk skipping moot).
- `--stats-only` / `--chunker-stats-only` (chunker stats move to a sibling `bin/kg-chunker-stats.sh` so the main CLI stays focused).
- `--force` (mention attribution is deterministic and re-runnable; nothing to "clear").
- `--max-existing-nodes` (replaced by `--dictionary-top-k`).
- `--backend` (Claude appears only as a judge, not as an extractor).

### 7.3 Operator log shape (per-page progress)

```
[kg-extract] Run starting: corpus=923 pages, judge=noop, dictionary_top_k=50
[kg-extract] Node embeddings: 1020 nodes, 1020 cached, 0 re-embedded (0ms)
[kg-extract] page='Kafka' tokens=1842 dict=50 entities_raw=11 grounded=10 dropped=1{ungrounded=1} relations_raw=6 grounded=6 elapsed=4.1s done=1/923
...
[kg-extract] Consolidator: 923 page-results → 487 unique candidates (5,213 raw → 487 after dedup, 90.7% reduction)
[kg-extract] Judge=noop: accept=487 reject=0 rewrite=0
[kg-extract] Upsert: inserted=412 merged=75
[kg-extract] Mention attribution: scanned 15577 chunks, wrote 8842 mention rows
[kg-extract] Done in 2h14m31s — 487 pending proposals (down from 43856)
```

### 7.4 Metrics (Prometheus, all keyed by `extractor_code`)

```
wikantik_kg_extractor_page_latency_seconds_bucket{...}     # histogram
wikantik_kg_extractor_pages_total{result="ok"|"failed"|"empty"}
wikantik_kg_extractor_entities_total{stage="raw"|"grounded"|"deduped"}
wikantik_kg_extractor_relations_total{stage="raw"|"grounded"|"deduped"}
wikantik_kg_extractor_rejected_total{reason="ungrounded"|"banned_name"|"low_confidence"}
wikantik_kg_judge_verdicts_total{judge_code,verdict,reason}
wikantik_kg_proposals_upsert_total{result="inserted"|"merged"}
wikantik_kg_node_embedding_cache_hits_total
```

## 8. Testing

### 8.1 TDD-first regression tests (commit RED before implementation)

Three load-bearing tests encode the defects we're fixing. Each lands red, the implementation turns it green:

1. **`DuplicateProposalRegressionTest`** — Insert `(new-node, "Kafka", Technology)` 5 times via `JdbcKnowledgeRepository.insertProposal`. Today: 5 rows in `kg_proposals`. After: 1 row with `support_count=5`.
2. **`UngroundedProposalRegressionTest`** — Feed a chunk "The cat sat on the mat" plus a fake extractor response asserting `Python --created_by--> Guido van Rossum` with `evidence_span="invented in 1991"`. Today: lands as proposal. After: rejected with `reason="ungrounded"`, counter incremented.
3. **`BannedNameRegressionTest`** — Fake extractor response with `entities: [{name: "Concept", type: "Concept"}]`. Today: lands as proposal. After: filtered, counter incremented.

### 8.2 Unit tests (`wikantik-main/src/test/.../knowledge/extraction/`)

| Class under test | What we prove |
|---|---|
| `PageExtractionPromptBuilder` | System prompt is byte-stable across calls (cache invariant); user prompt embeds dictionary; the seven-type enum is enforced verbatim; entity/relation caps appear in prompt text. |
| `PageExtractionResponseParser` | Parses well-formed JSON; gracefully handles malformed/truncated JSON, missing fields, extra fields; rejects entities whose `name` is in the banned-list (case-insensitive); rejects relations whose `source`/`target` aren't in `entities[]`; emits stats. |
| `EvidenceGroundingVerifier` | Verbatim quote → grounded; whitespace-normalized quote → grounded; paraphrased quote → ungrounded; quote longer than 200 chars → ungrounded with `reason="span_too_long"`; NFC-equivalent Unicode → grounded. |
| `NodeSignature` / `EdgeSignature` | `("GitHub", "Organization")` and `(" github ", "organization")` produce the same signature; `("Apache Spark", "Technology")` and `("Spark", "Technology")` produce different signatures (no fuzzy merging); predicate synonyms (`is_a` ↔ `is-a`) collapse. |
| `ProposalConsolidator` | 5 input page-results that all extract `Python :: Technology` produce 1 `ConsolidatedProposal` with `support.length == 5`; chosen `displayName` is the most-frequent original-cased form; aggregate confidence is mean penalised by single-source. |
| `ProposalUpserter` | First insert produces row with `support_count=1`; second upsert with same signature merges support arrays and bumps `support_count`; `confidence` is `GREATEST(old, new)`; reviewed rows (status != pending) are left alone. |
| `NoOpProposalJudge` | Identity transform: every input returns `Accept`. |
| `OllamaProposalJudge` | Parses verdict JSON; malformed verdict → `Accept` with `reasonCode="judge_failed"` (fail-open by design); rewrite verdict updates the proposal and re-keys signature. |
| `MentionAttributor` | Exact whole-word match per chunk; `Java` matches "Java is..." but not "JavaScript" or "javac"; case-insensitive comparison but original-case preserved in `chunk_entity_mentions.surface_form`; chunks with no matches contribute nothing. |
| `KgNodeEmbeddingService` | Generates embedding only when content hash changes; cache hit returns persisted vector unchanged; vector dimension matches model output. |

### 8.3 Contract tests

- **`PageExtractorContract`** (abstract JUnit base) — every implementation must produce schema-conforming results, ground evidence_spans, respect entity/relation caps, exclude banned names. `OllamaPageExtractor` provides its concrete subclass with a `MockHttpClient` returning canned Ollama responses.
- **`ProposalJudgeContract`** — `Verdict.Accept.finalConfidence` is in [0,1]; `Reject.reasonCode` is in the closed enum; `Rewrite.rewritten` has the same kind as the input; the same input run twice in a row produces equal verdicts (judges are deterministic *within a run*).

### 8.4 Integration tests (`wikantik-it-tests/.../knowledge/`)

- **`PageExtractionEndToEndIT.testFullPipelineWithFakeOllama()`** — boots Tomcat (Cargo + pgvector container), points the extractor at a `WireMock` Ollama returning deterministic JSON for 5 seeded pages; runs `BootstrapEntityExtractionIndexer.start()`; asserts resulting `kg_proposals` rows have expected signatures, support counts, and `chunk_entity_mentions` rows.
- **`PageExtractionIdempotencyIT.testReRunIsIdempotent()`** — runs the same extraction twice; asserts the second run produces 0 new pending rows and only `last_seen_at` updates.
- **`JudgeExperimentCliIT.testClaudeJudgeBlockedByDefault()`** — runs the experiment CLI with `--judge claude` while `allow_claude=false`; asserts non-zero exit, clear error, zero API calls (verified via WireMock recorder).

### 8.5 Out of scope

- No mocking of the HTTP layer below `OllamaPageExtractor.callOllama` — we test through `HttpClient` with `WireMock`. The Ollama wire shape is a real contract.
- No tests asserting LLM output quality — that's what the judge experiment script and manual triage cover. We test that the pipeline correctly handles whatever the LLM emits, including malformed responses.

## 9. Phased rollout

Each phase is independently buildable, testable, and shippable. Sole dev on `main`; no feature branches.

| Phase | Scope | Production effect when merged |
|---|---|---|
| 0 | Schema migrations `V020__kg_proposals_signature.sql` + `V021__kg_node_embeddings.sql`. Re-run `bin/db/migrate.sh` twice; insert duplicate test row, confirm uniqueness conflict. | None (additive). |
| 1 | New API types in `wikantik-api` (interfaces, records). | None (compile-only). |
| 2 | Components in `wikantik-main`: `EvidenceGroundingVerifier`, `PageExtractionPromptBuilder`, `PageExtractionResponseParser`, `OllamaPageExtractor`, `ProposalConsolidator`, `ProposalUpserter`, `KgNodeEmbeddingService`, `NoOpProposalJudge`, stub judges, `MentionAttributor`. All units land with TDD-red-then-green tests. | None (not wired). |
| 3 | Refactor `BootstrapEntityExtractionIndexer` internals to drive new pipeline; delete `AsyncEntityExtractionListener` and chunk-driven path. End-to-end IT lands. | Production code path is the new pipeline, but CLI flags from Phase 4 needed to use it; old CLI paths fail loudly. |
| 4 | CLI surface in `BootstrapExtractionCli` and `bin/kg-extract.sh`. Add `bin/kg-chunker-stats.sh` sibling for the chunker-stats path. | `bin/kg-extract.sh` runs the new pipeline. |
| 5 | Wipe legacy proposals (with `pg_dump` snapshot first); smoke run; first real run. | Reviewer queue resets; first batch of high-quality deduped proposals arrives. |
| 6 | `OllamaProposalJudge` (production-ready), `ClaudeProposalJudge` (gated), `bin/kg-judge-experiment.sh` (`JudgeExperimentCli`). | Optional judge stage available; Claude A/B becomes runnable. |

### 9.1 Phase 5 procedure

```bash
# Belt-and-suspenders: dump legacy pending proposals before wipe
PGPASSWORD=… pg_dump -h localhost -U jspwiki -d jspwiki \
    --data-only --table=kg_proposals --column-inserts \
    --where="status = 'pending'" \
    > backups/kg_proposals_pending_pre_redesign_$(date +%Y%m%d).sql

# Wipe (documented one-shot, NOT in a versioned migration — per CLAUDE.md)
PGPASSWORD=… psql -h localhost -U jspwiki -d jspwiki -c \
    "DELETE FROM kg_proposals WHERE status = 'pending';"

# Smoke: 50 pages, dry-run
bin/kg-extract.sh --max-pages 50 --dry-run --report reports/smoke-$(date +%Y%m%d).json

# Manually inspect smoke report. If quality is meaningfully better, do full run:
bin/kg-extract.sh --report reports/extract-$(date +%Y%m%d).json
```

### 9.2 Rollback story

`git revert` Phases 3 + 4 (CLI + indexer changes); re-run `bin/db/migrate.sh` (no DDL rollback needed — new columns are additive and inert); restore the legacy proposals from the SQL dump if desired.

## 10. Performance envelope

Measured on `gemma4-assist:latest` (8B params, Q4_K_M, 131K context) against the production Ollama host on 2026-05-01:

| Page | Words | Prompt tokens | Output tokens | Wall ms |
|---|---|---|---|---|
| RecentArticlesTemplate | 17 | 792 | 16 | 10,781 |
| RussiaUkraineWarEnergyWar | 683 | 1,772 | 864 | 27,763 |
| QuantumComputing | 1,127 | 2,606 | 1,241 | 19,367 |
| KubernetesBasics | 2,978 | 5,312 | 963 | 29,541 |
| VehicleRoutingProblem | 3,635 | 6,548 | 1,226 | 41,648 |

Mean ≈ 25.8 s/page (median ≈ 27.7 s). Cost decomposes as ~10 s warm-up + ~15 ms per output token; prompt-eval is fast (gemma's prompt-eval throughput is ~10–30K tokens/s).

**Extrapolation for 1000-page corpus:**

```
At concurrency=2 (default):  1000 * 25.8 / 2 = 12,900 s = 3.6 hours
At concurrency=3:                           ≈ 2.4 hours
At concurrency=4:                           ≈ 1.8 hours
At concurrency=6:                           ≈ 1.2 hours

Other phases (additive, all dwarfed by extraction):
  Node-embedding warm-up (1020 nodes × bge-m3): ~1 minute
  Consolidation (in-memory):                    ~5 seconds
  Upserts (~500 rows):                          ~3 seconds
  Mention attribution (15.5K chunks × strings): ~15 seconds
  Judge=noop:                                   0 seconds
```

End-to-end at default `--concurrency 2`: **~3.6 hours**. At `--concurrency 3`: **~2.4 hours**. Compared to today's per-chunk pipeline (15,577 chunks × ~5–15 s at concurrency 2 ≈ 11–22 hours), this is a **~6× speedup**.

## 11. Spot-check of output quality (from timing test)

The same timing-test run produced JSON output examined by hand:

- **No "Concept" / "Agent" / generic-noun proposals** in any of the 5 page outputs. The banned-list filter in the prompt was respected without needing the parser-level guard.
- **Every `evidence_span` was a verbatim substring of the source page** — manual grep confirmed all 8 of `RussiaUkraineWarEnergyWar`'s entities and all 12 of `QuantumComputing`'s entities had grounded quotes.
- **Caps held**: pages with rich entity sets (QuantumComputing, VehicleRoutingProblem) saturated the 12-entity / 8-relation cap, which is correct.
- **Type assignments landed in the closed enum cleanly** — no `Project` outputs, types matched intuition (`Russia` → Place, `IBM` → Organization, `Kubernetes` → Technology, `Quantum Computing` → Concept).
- **Empty-result page handled correctly**: `RecentArticlesTemplate` returned `{"entities":[],"relations":[]}` rather than hallucinating.
- **One mild concern**: the model heavily prefers `confidence: 1.0` (small-model calibration weakness). This is exactly what `support_count`-based reviewer ranking and the optional judge stage are designed to compensate for; nothing to fix at extraction time.

## 12. Open questions / out of scope

- **The 917 article-typed nodes** in the existing KG mostly aren't proper KG entities — they're page-titles imported as nodes. Cleaning that up is out of scope for this redesign, but the new pipeline will gradually grow a parallel set of properly-typed entity nodes; a separate cleanup pass to migrate or retire the article-typed nodes is a follow-up.
- **MCP `propose_knowledge` tool path** in `wikantik-admin-mcp` writes directly to `kg_proposals` via `HubProposalRepository` (and similar for other proposal sources). Phase 0's signature column applies to all of them, but this design covers only the bootstrap-extractor write path. A follow-up should ensure the MCP tool path also computes signatures and respects the partial unique index.
- **`per_page_min_interval_ms` rate limit** on the save-time async path — the new design replaces the bootstrap path; the save-time path is a separate flow that still uses the old per-chunk extractor. A follow-up should either retire that flow or migrate it to the new pipeline.
- **Confidence calibration** for small open models — deferred. The judge stage and `support_count` ranking should be sufficient mitigation; if not, a calibration step (e.g. temperature scaling against a small labeled set) is a future enhancement.

## 13. Documentation updates needed in lockstep

- `docs/wikantik-pages/KnowledgeGraphConstructionPipeline.md` — replace pipeline overview to reflect page-level extraction, dedup, judge stage.
- `docs/wikantik-pages/KnowledgeProposals.md` — add a "Consolidation" subsection explaining `support_count`, `support[]`, signature dedup.
- `docs/wikantik-pages/ProposingKnowledgeGraphEdges.md` — clarify that `propose_knowledge` MCP submissions also flow through the consolidator (after the follow-up above).
- `CLAUDE.md` — add a one-paragraph "running the extractor" section pointing at `bin/kg-extract.sh` defaults and the wipe-then-fill workflow.
- New design doc in `docs/wikantik-pages/` (`KnowledgeGraphExtractionRedesign.md`) summarising the architecture for future operators.

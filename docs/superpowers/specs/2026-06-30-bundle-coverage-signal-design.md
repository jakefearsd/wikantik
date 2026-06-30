# Bundle Coverage Signal + Tool-Routing Guidance — Design

**Date:** 2026-06-30
**Status:** Approved for implementation
**Scope:** Roadmap items #1 (coverage signal) and #2 (tool-description routing) from the
2026-06-29 grounded-eval interface-friction review.

## Motivation

The grounded-agent eval (`eval/agent-grounding/runs/baseline-3s-20260629T0530Z`) showed
that a full agentic MCP loop barely beats a single canned `assemble_bundle` call
(correctness 1.625 vs 1.562 on 0–2). Two concrete friction patterns explain the gap:

1. **Looping.** `stale-citation-grading` and `read-path-acl` re-called `assemble_bundle`
   ×3 in a single answer — the agent could not tell whether it already had enough context,
   so it re-queried and flailed.
2. **Thin-coverage synthesis/count misses.** `read-path-acl` (mcp=0, a *confidently wrong*
   security answer), `kg-predicates-count` (mcp=1 vs bundle=2), and
   `scim-group-admin-restriction` lost because the agent's selective retrieval missed
   corroborating pages that the exhaustive global bundle happened to include — and because
   count/enumeration questions were never routed to the exact structured tools
   (`sparql_query`/`get_ontology`), which the agent called ≤1 time across the whole run.

The fix is **not more tools**. It is: give the bundle a coverage signal so the agent knows
when to stop or escalate (#1), and steer count/enumeration questions to the structured tools
via tool descriptions (#2).

## Key constraint discovered

In the **default hybrid path** (`HybridChunkSectionSource`, production default since
2026-06-19), `BundleSection.score` is `1.0/(1+rank)` — a rank-reciprocal proxy whose top
value is **always 1.0** regardless of match quality. It is therefore useless as a confidence
signal. Only `DenseChunkSectionSource` carries a real cosine. A meaningful coverage signal
must surface the **true top dense cosine**, which the hybrid path computes (when embedding
the query) and then discards in `rankedIds()`.

## Design

### 1. New data type: `BundleCoverage` (wikantik-api)

```java
package com.wikantik.api.bundle;

public record BundleCoverage(
    int    sectionCount,       // sections returned (post-dedup, post-cap, post-ACL-gate)
    int    distinctPageCount,  // distinct canonical_ids — thin vs corroborated coverage
    double topSimilarity,      // true top dense cosine in [0,1]; -1.0 when unavailable
    String confidence          // "strong" | "partial" | "weak" | "unknown"
) {
    public static BundleCoverage empty();   // sectionCount=0, distinctPageCount=0,
                                            // topSimilarity=-1, confidence="unknown"
}
```

`BundleCoverage` is a **pure data record** — no threshold logic in wikantik-api. It
serializes for free on both agent surfaces (Gson on `assemble_bundle`, Jackson/Gson on
`/api/bundle`).

### 2. `ContextBundle` gains a coverage component (wikantik-api)

```java
public record ContextBundle(String query, List<BundleSection> sections, BundleCoverage coverage) {
    // Back-compat secondary constructor — keeps every existing 2-arg caller/test compiling:
    public ContextBundle(String query, List<BundleSection> sections) {
        this(query, sections, BundleCoverage.empty());
    }
}
```

### 3. Surface the top cosine: `SectionCandidates` carrier (wikantik-main)

`SectionCandidateSource.candidates()` changes return type from `List<CandidateSection>` to a
carrier so the top cosine rides along from where the `ScoredChunk` list exists:

```java
record SectionCandidates(List<CandidateSection> sections, double topSimilarity) {}
```

Per implementor:

| Source | `topSimilarity` |
|--------|-----------------|
| `DenseChunkSectionSource` | top section's real cosine (`out` already sorted desc) |
| `HybridChunkSectionSource` | max dense cosine captured **before** `rankedIds()` discards it; `-1` when embedder unavailable |
| `RetrievalSectionSource` (legacy page-gated) | `-1` → `confidence="unknown"` |

The interface stays a single-method `@FunctionalInterface`.

### 4. Confidence computation (wikantik-main)

Lives in `wikantik-main` (keeps wikantik-api logic-free). Rule:

- `topSimilarity < 0` (unavailable) → `"unknown"`
- `topSimilarity ≥ strongThreshold` AND `sectionCount ≥ 3` → `"strong"`
- `topSimilarity ≥ partialThreshold` → `"partial"`
- otherwise → `"weak"`
- `sectionCount == 0` → `"weak"` (or `"unknown"` if topSimilarity unavailable)

Thresholds are config, **provisional/tunable**, with explicit starting defaults that the
implementation confirms against the eval corpus's top-cosine distribution (a one-shot
measurement over `eval/agent-grounding/questions.json`, adjusting only if the defaults
clearly mis-split known-good vs known-bad retrievals):

- `wikantik.bundle.coverage.strong_similarity` (default `0.55`)
- `wikantik.bundle.coverage.partial_similarity` (default `0.40`)

`DefaultBundleAssemblyService.assemble()` computes coverage once over the assembled
(ungated) section list using `cand.topSimilarity()`, then returns the 3-arg `ContextBundle`.

### 5. View-gating recount (the documented imprecision)

`assemble_bundle` filters sections by guest-view ACL **after** the service builds the bundle
(the MCP surface has no caller identity). The tool therefore recomputes coverage **counts**
over the gated sections:

```java
BundleCoverage.recount(original, gatedSections)
  // recomputes sectionCount + distinctPageCount from gatedSections,
  // preserves topSimilarity + confidence (cosine is a retrieval property,
  // unaffected by ACL filtering)
```

`/api/bundle` applies the same recount post-ACL. Deliberate minor imprecision: the
`sectionCount ≥ 3` clause is not re-evaluated after gating, so confidence is not downgraded
if gating drops a bundle below 3 sections. Accepted to avoid plumbing thresholds into the
MCP/REST layer; documented here.

### 6. Item #2 — tool-description routing (wikantik-knowledge)

- `assemble_bundle` description gains one line: the response includes a `coverage` block;
  when `confidence` is `weak`/`partial`, refine the query or escalate to `sparql_query` /
  `get_ontology` for counts and enumerations.
- `sparql_query`, `get_ontology`, `discover_schema` descriptions gain a "use this when…"
  line steering exact count/enumeration/predicate-list questions to them.

These are description-string changes only — no behavior change — covered by tool-definition
assertion tests so they don't silently drift.

## Testing (TDD — test-first per CLAUDE.md)

- `BundleCoverageTest` — rule table: cosine × count → label; boundary at each threshold;
  empty bundle; `-1` → `"unknown"`; `recount` preserves cosine + fixes counts.
- Source tests — each emits the correct `topSimilarity` (dense = cosine, hybrid =
  pre-fusion max, retrieval = -1, embedder-down = -1).
- `DefaultBundleAssemblyServiceTest` — coverage populated; counts match assembled sections.
- `AssembleBundleToolTest` — `coverage` block serialized; gated recount applied.
- Tool-definition assertion tests — coverage-guidance line present on `assemble_bundle`;
  "use this when…" routing lines present on `sparql_query`/`get_ontology`/`discover_schema`.

## Out of scope (YAGNI)

- LLM self-assessment of coverage (a second model call — violates ADR-0001 retrieval-not-
  synthesis, adds cost/latency to a retrieval primitive).
- Re-deriving confidence under view-gating (see §5).
- Any change to ranking, fusion, or recall levers — this is a *signal*, not a retrieval change.

## Validation

After implementation, re-run the grounded eval (`eval/agent-grounding`) and check the
`grounded_mcp` arm for (a) reduced repeated `assemble_bundle` calls and (b) improved
`kg-predicates-count` / `read-path-acl`. Small-N (16 q) — directional, not a statistical
claim.

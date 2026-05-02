# KG Staged Validation Design

**Date:** 2026-05-02
**Status:** Draft — pending user review
**Subsystem:** Knowledge Graph (NOT Page Graph — see CLAUDE.md)
**Touches:** `wikantik-api`, `wikantik-main`, `wikantik-rest`, `wikantik-knowledge`,
`wikantik-frontend`, `wikantik-it-tests`, `bin/db/migrations/`, `bin/`.

## 1. Goal

Stage Knowledge Graph validation into two tiers:

1. **Machine tier** — an automated judge LLM reviews each pending proposal and
   votes `approved | rejected | abstain`. Approval auto-promotes the proposal
   into `kg_nodes`/`kg_edges` with `tier='machine'`. Hard rejection lands the
   triple in `kg_rejections` (negative knowledge).
2. **Human tier** — admin review remains the final gate. Promotion to human
   tier monotonically increases trust and overrides any machine verdict.
   Human approval is an *additional* quality gate; it does not add new graph
   data, only re-labels machine-tier rows as human-tier (or inserts directly
   at human-tier if a human approves before the judge runs).

Read consumers default to `min_tier='machine'` (i.e. they see the broader
view — everything human- or machine-approved). Callers that want the
stricter human-vetted-only view pass `min_tier=human` per request.

## 2. Non-goals

- Per-credential tier ceilings. Opt-in is per-call only. (A future feature can
  layer this on the unified API-key admin tracked in
  `project_api_key_admin_9b`.)
- Threshold-driven auto-rejection. Hard reject is binary on the judge's
  `verdict` field.
- Scheduled re-judging on model drift. Re-judging is operator-initiated.
- Page Graph validation. The Page Graph (wikilink edges) is a separate
  subsystem and out of scope.
- Replacing the existing `confidence` / `support` fields on `kg_proposals`.
  They remain as extractor self-reports; the judge's confidence is recorded
  separately.

## 3. Background

Today's flow:

- `AsyncEntityExtractionListener` calls `KnowledgeGraphService.submitProposal`
  for each extracted entity / edge. Rows land in `kg_proposals` with
  `status='pending'`, deduped by `signature`.
- `AdminKnowledgeResource` exposes `POST /admin/knowledge-graph/proposals/{id}/approve`
  and `…/reject`. These call `DefaultKnowledgeGraphService.approveProposal /
  rejectProposal`, which currently **only flip the status column**.
- `kg_nodes` / `kg_edges` are populated separately, primarily by admin-curated
  `upsertNode` / `upsertEdge` calls. Approval does not materialize a proposal
  into the graph today — a known gap (see the comment at
  `BootstrapEntityExtractionIndexer.java:677`: "admin approval will materialize
  nodes").
- `kg_rejections` records negative knowledge for `new-edge` proposals on
  rejection, preventing the same triple from being re-proposed.

This design fills the materialization gap as part of introducing the
machine tier.

## 4. Data Model

### 4.1 `kg_proposals` — extend in place

Add columns:

| Column | Type | Purpose |
|--------|------|---------|
| `tier` | `VARCHAR(16) NOT NULL DEFAULT 'none'` | Current promotion level: `none | machine | human`. |
| `machine_status` | `VARCHAR(16)` | Last judge verdict: `approved | rejected | abstain`; `NULL` = unjudged. |
| `machine_confidence` | `DOUBLE PRECISION` | Judge confidence in `[0, 1]`. |
| `machine_judged_at` | `TIMESTAMP` | When the judge last ran on this row. |
| `machine_model` | `VARCHAR(64)` | Model identifier the judge used (e.g. `gemma4-assist:latest`). |

The existing `status` column becomes the **human verdict** (`pending |
approved | rejected`) — semantically clarified, column not renamed to keep
the migration small.

Add `CHECK (tier IN ('none','machine','human'))` and
`CHECK (tier <> 'human' OR status IN ('approved','rejected'))`.

### 4.2 `kg_proposal_reviews` — new append-only audit table

```sql
CREATE TABLE kg_proposal_reviews (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id   UUID         NOT NULL REFERENCES kg_proposals(id) ON DELETE CASCADE,
    reviewer_kind VARCHAR(16)  NOT NULL CHECK (reviewer_kind IN ('machine','human')),
    reviewer_id   VARCHAR(100) NOT NULL,           -- model name OR username
    verdict       VARCHAR(16)  NOT NULL CHECK (verdict IN ('approved','rejected','abstain')),
    confidence    DOUBLE PRECISION,                -- nullable for human reviews
    rationale     TEXT,
    created       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX kg_proposal_reviews_proposal_idx
    ON kg_proposal_reviews (proposal_id, created DESC);
```

Every machine and human verdict appends a row. This preserves judge rationale,
supports re-judging on model upgrade without losing history, and gives a clean
record when a human overrides a machine call (a key evaluation signal).

### 4.3 `kg_nodes` / `kg_edges` — add tier and provenance

Add columns:

| Column | Type | Purpose |
|--------|------|---------|
| `tier` | `VARCHAR(16) NOT NULL DEFAULT 'human'` | Trust tier of this row. |
| `provenance_proposal_id` | `UUID` | FK back to the originating proposal; `NULL` for hand-curated rows. |

Backfill: existing rows get `tier='human'` by virtue of the column default
(they were created by the existing manual flow).

Add indexes `(tier)` and `(tier, name)` on `kg_nodes`; `(tier, source_id)`
and `(tier, target_id)` on `kg_edges`.

### 4.4 `kg_rejections` — unchanged

Hard machine-rejects of `new-edge` proposals continue to land here, same as
human rejects.

## 5. Components

### 5.1 New components

- **`KgProposalJudgeService`** (interface in `wikantik-api`, impl in
  `wikantik-main` `com.wikantik.knowledge.judge.DefaultKgProposalJudgeService`).
  - `JudgeVerdict judge(KgProposal proposal)` — calls Ollama with the judge
    system prompt and the proposal payload; returns
    `{verdict, confidence, rationale, model}`.
  - Uses the **same model as the extractor** (`gemma4-assist:latest` by default,
    re-using `wikantik.knowledge.extractor.ollama.*` connection settings) but a
    distinct **system prompt** focused on factual support and source-evidence
    consistency, returning strict JSON.
  - On HTTP / parse / timeout failure, returns `verdict='abstain'` with
    `rationale='judge_unavailable: <reason>'`.

- **`JudgeRunner`** — scheduled background task in
  `com.wikantik.knowledge.judge`.
  - Cadence: every `wikantik.kg.judge.cron.interval_min` minutes (default `5`),
    gated by `wikantik.kg.judge.cron.enabled` (default `true`).
  - Picks up `kg_proposals` rows where `machine_status IS NULL`, in batches of
    `wikantik.kg.judge.batch_size` (default `50`), with concurrency
    `wikantik.kg.judge.concurrency` (default `2`).
  - Uses `SELECT … FOR UPDATE SKIP LOCKED` to allow safe parallelism if a
    second instance ever runs.
  - For each proposal: calls the judge, persists the verdict, appends a
    `kg_proposal_reviews` row, and (on `approved`) calls
    `KgMaterializationService` to write into `kg_nodes`/`kg_edges`.
  - Bounded retry on transient failure: `abstain` rows are eligible for
    re-judging on the next pass for up to
    `wikantik.kg.judge.max_attempts` (default `3`); after that they sit until
    an operator explicitly re-runs. Attempt count is derived from the
    `kg_proposal_reviews` table (count of rows for this `proposal_id` with
    `reviewer_kind='machine'`) — no separate counter column.

- **`KgMaterializationService`** — single owner of `kg_nodes`/`kg_edges`
  writes derived from proposals. Closes the existing materialization gap.
  - `materializeMachine(KgProposal)` — upserts node(s)/edge(s) with
    `tier='machine'` and `provenance_proposal_id=proposal.id`. Idempotent:
    re-running on the same proposal is a no-op.
  - `promoteToHuman(KgProposal)` — updates the existing materialized rows to
    `tier='human'`; if no machine-tier rows exist (e.g. proposal was
    human-approved before the judge ran), inserts new rows directly at
    `tier='human'`.
  - `retract(KgProposal)` — deletes rows tagged with this
    `provenance_proposal_id`. Used when a human overrides a machine approval
    with a reject, or when the judge hard-rejects a proposal that was
    somehow already materialized.

- **`bin/kg-judge.sh`** — CLI mirroring `bin/kg-extract.sh`. Flags:
  `--max-proposals N`, `--dry-run`, `--report path.json`,
  `--proposal-id UUID` (re-judge a single row).

### 5.2 Modified components

- **`JdbcKnowledgeRepository`**
  - New methods: `recordReview(...)`, `getProposalsForJudging(int batch)`,
    `applyMachineVerdict(...)`, `applyHumanVerdict(...)`.
  - Tier-aware versions of `getAllNodes()`, `getAllEdges()`,
    `searchKnowledge(...)`, `traverseByCoMention(...)` — accept `Tier minTier`,
    SQL gains `WHERE tier = ANY(<allowed tiers>)`.
  - `clearAll()` truncates `kg_proposal_reviews` too.

- **`KnowledgeGraphService` (interface in `wikantik-api`)**
  - Read methods that today expose graph data gain a `Tier minTier` parameter
    (default `MACHINE` — the broader view). Snapshot, search, traversal.
  - `Tier` enum (`HUMAN`, `MACHINE`) lives in `wikantik-api`. `MACHINE` means
    "machine OR human" (monotonic in trust); `HUMAN` means "human only".
  - `approveProposal(UUID, String)` and `rejectProposal(UUID, String, String)`
    now also call `KgMaterializationService` and append review rows.
  - New: `JudgeVerdict judgeNow(UUID proposalId, String triggeredBy)` for
    the admin "judge this row now" path.

- **`DefaultKnowledgeGraphService`** — implements the above, owns the
  snapshot cache key change (now `(viewer, minTier)`).

- **`AdminKnowledgeResource`** (`/admin/knowledge-graph/*`)
  - `POST /admin/knowledge-graph/judge/run` — kicks off an ad-hoc judge run
    (asynchronous, returns 202 + a status token).
  - `POST /admin/knowledge-graph/proposals/{id}/judge` — judges a single
    proposal synchronously, returns the verdict.
  - `GET /admin/knowledge-graph/proposals` — gains query params
    `?tier=&machine_status=&include_machine_rejected=true|false`. Default
    review queue **hides** `machine_status='rejected'` rows; operator can
    flip the filter to evaluate judge quality.
  - `GET /admin/knowledge-graph/proposals/{id}/reviews` — returns the audit
    history.

- **`KnowledgeMcpInitializer`** — adds `min_tier` (string,
  `"human" | "machine"`, default `"human"`) to the input schemas of
  read-only tools that surface graph data: `search_knowledge`,
  `traverse_by_co_mention`, the structural-spine tools that touch KG.
  Examples in the schema show both values. (Per the agent-grade-content
  Phase 6 work, every MCP tool ships at least one example.)

- **`AdminKnowledgeResource` (frontend admin SPA)** — review queue gains:
  - A "Machine" column showing the verdict badge (✓ approved / ✗ rejected /
    ◯ abstain / – not yet judged).
  - A rationale tooltip on the badge.
  - A filter dropdown: "All", "Awaiting machine review", "Machine approved",
    "Machine rejected", "Machine abstained".
  - A per-row "Judge now" button that calls
    `POST /admin/knowledge-graph/proposals/{id}/judge`.

### 5.3 Configuration (`wikantik.properties`)

```
wikantik.kg.judge.enabled            = true
# When unset, the judge config falls back to the extractor's connection +
# model (read at startup from wikantik.knowledge.extractor.ollama.*).
# Set these explicitly only to pin the judge to a different endpoint or model.
# wikantik.kg.judge.model            =
# wikantik.kg.judge.endpoint         =
wikantik.kg.judge.cron.enabled       = true
wikantik.kg.judge.cron.interval_min  = 5
wikantik.kg.judge.batch_size         = 50
wikantik.kg.judge.concurrency        = 2
wikantik.kg.judge.timeout_seconds    = 30
wikantik.kg.judge.max_attempts       = 3
# Default tier for read paths when the caller does not pass min_tier.
# 'machine' = broader view (machine + human); 'human' = strict (human only).
# Operators can flip this to 'human' to enforce strict-only across the
# deployment without changing client code.
wikantik.kg.read.default_min_tier    = machine
```

The fallback to extractor settings is performed in
`KgJudgeConfig.fromProperties(...)` (Java side), not by properties-file
interpolation — `wikantik.properties` does not support `${…}` substitution.

## 6. Lifecycle

```
proposal created (tier=none, machine_status=null)
   │
   ▼
JudgeRunner picks up batch (FOR UPDATE SKIP LOCKED)
   │
   ├─► judge: approved
   │       → kg_proposals: tier='machine', machine_status='approved'
   │       → kg_proposal_reviews: append (machine, model, approved, conf, rationale)
   │       → KgMaterializationService.materializeMachine(proposal)
   │           → kg_nodes/kg_edges insert with tier='machine', provenance_proposal_id
   │
   ├─► judge: rejected (hard)
   │       → kg_proposals: status='rejected', machine_status='rejected', tier='none'
   │       → kg_proposal_reviews: append (machine, …, rejected, …)
   │       → kg_rejections: insert (for new-edge proposals)
   │       (Terminal unless a human explicitly overrides — see below.)
   │
   └─► judge: abstain (or transient failure)
           → kg_proposals: machine_status='abstain'
           → kg_proposal_reviews: append (machine, …, abstain, …, "rationale")
           → eligible for re-judge on next pass (up to max_attempts)

Human review (admin SPA + REST):

approveProposal(id, user)
   → kg_proposals: status='approved', tier='human'
   → kg_proposal_reviews: append (human, user, approved, null, null)
   → KgMaterializationService.promoteToHuman(proposal)
       → if machine-tier rows exist for this provenance_proposal_id:
             update them to tier='human'
         else:
             insert new rows at tier='human'
   → if a kg_rejections row blocks this triple (judge had rejected it),
     delete that row — human override removes the negative-knowledge entry.

rejectProposal(id, user, reason)
   → kg_proposals: status='rejected', tier='none'
   → kg_proposal_reviews: append (human, user, rejected, null, reason)
   → kg_rejections: insert (for new-edge proposals; ON CONFLICT updates reason)
   → KgMaterializationService.retract(proposal)
       → delete kg_nodes/kg_edges rows where provenance_proposal_id = proposal.id
```

Re-judging on model upgrade: operator runs
`bin/kg-judge.sh --proposal-id <UUID>` (or, in bulk, a SQL one-shot to clear
`machine_status` for affected rows so the runner re-fills them). The
`kg_proposal_reviews` table preserves the prior verdict.

## 7. Read Path & Opt-in

### 7.1 REST

- `GET /api/knowledge-graph/snapshot?min_tier=human|machine` — default
  `machine` (configurable via `wikantik.kg.read.default_min_tier`).
  Invalid value → 400 `{"error": "min_tier must be 'human' or 'machine'"}`.
- The snapshot cache becomes keyed by `(viewer, minTier)`. The existing
  per-viewer ACL redaction runs after the tier filter.
- Search and traversal endpoints likewise accept `min_tier`.

### 7.2 MCP (`/knowledge-mcp`)

- `search_knowledge`, `traverse_by_co_mention`, and the structural-spine tools
  that touch the KG gain a `min_tier` input property
  (`{"type": "string", "enum": ["human","machine"], "default": "machine"}`).
- Each schema ships at least one input example with `min_tier='machine'`
  (the default, broader view) and one with `min_tier='human'` (strict
  filter), matching the existing Phase 6 examples convention.
- The output schema is unchanged. The response includes only human-tier
  nodes/edges when `min_tier='human'` is passed.

### 7.3 Page Graph (`/api/page-graph/*`, `/page-graph` UI)

**Unchanged.** Page Graph and Knowledge Graph are distinct subsystems
(see CLAUDE.md). This feature does not touch wikilink-derived data.

### 7.4 Auditing

When the caller passes `min_tier` explicitly (either value), the request is
logged at `INFO` with the calling credential (API key id or session
principal), the request URL, and the resolved tier. This produces the data
needed to see which clients enforce strict (`human`) filtering vs. accept
the default broader view, and to evaluate adoption patterns over time.
Default-resolved requests (no `min_tier` parameter) are not extra-logged —
they're the baseline.

## 8. Failure Handling & Edge Cases

| Scenario | Behavior |
|----------|----------|
| Judge HTTP failure | `abstain` review row with `rationale='judge_unavailable: <reason>'`; row eligible for re-judge until `max_attempts`. `LOG.warn` with proposal id. |
| Judge returns malformed JSON | Same as HTTP failure; rationale records the parse error. |
| Materialization failure after machine-approval | Row stays `tier='none'` with `machine_status='approved'`; runner re-attempts on next pass. Idempotent upsert means retries don't double-write. |
| Human approves a proposal the judge rejected | Allowed. `kg_rejections` row for that triple is deleted. New `kg_*` row inserted at `tier='human'`. Audit trail captures the override. |
| Human rejects a machine-approved proposal | `kg_*` rows with that provenance are deleted. `kg_rejections` insert. |
| `clearAll()` | Truncates `kg_proposal_reviews` in addition to existing tables. |
| Concurrent judge runners | `SELECT … FOR UPDATE SKIP LOCKED` over the pending batch. |
| Re-extracting the same page | Existing `kg_proposals_pending_signature_uq` deduplication is unchanged; merged proposal keeps its `machine_status` until cleared. |
| Existing approved proposals at migration time | Grandfathered: `tier='human'`, `status` unchanged. No machine review row is back-filled (no judge ran on them). |

## 9. Migration

`bin/db/migrations/V023__kg_staged_validation.sql` — DDL only:

1. `ALTER TABLE kg_proposals ADD COLUMN IF NOT EXISTS tier …` and the four
   `machine_*` columns. Add the two `CHECK` constraints.
2. `CREATE TABLE IF NOT EXISTS kg_proposal_reviews …` with index.
3. `ALTER TABLE kg_nodes ADD COLUMN IF NOT EXISTS tier VARCHAR(16) NOT NULL DEFAULT 'human'`
   and `provenance_proposal_id UUID`. Same for `kg_edges`. Indexes.
4. Idempotent grants for `:app_user`.

The `tier` column on existing `kg_nodes`/`kg_edges` rows resolves to
`'human'` via the column default — this is a schema operation, not a data
backfill. Per the no-data-in-migrations rule, this stays inside `Vxxx`.

Pre-existing `kg_proposals` rows with `status='approved'` get
`tier='human'` set by a one-line `UPDATE` inside the same migration. This is
borderline — happy to lift it into a one-shot psql snippet documented in
`bin/db/migrations/README.md` if you'd prefer.

## 10. Testing Strategy

Per CLAUDE.md (TDD-first) and the project memories on MCP write-surface
pairing and `mvn test-compile` after signature changes.

### 10.1 Unit (Mockito, JUnit 5)
- `DefaultKgProposalJudgeService`: prompt assembly, JSON parsing, error →
  abstain mapping, model identifier capture.
- `KgMaterializationService`: each lifecycle transition
  (none→machine, machine→human, machine→rejected, human-override-of-reject,
  human-rejects-machine-approved). Verify idempotency on retry.
- `JudgeRunner`: batch picking, max-attempts cap, concurrency boundary.
- `DefaultKnowledgeGraphService`: tier defaulting, audit-row append on
  approve/reject.

### 10.2 Repository (PgTestBase / pgvector IT base)
- Tier-filtered reads return only the requested tier.
- `clearAll()` truncates the new table.
- `SELECT … FOR UPDATE SKIP LOCKED` does not double-pick rows under
  concurrent runners.
- Migration `V023` is idempotent (apply twice → no error).

### 10.3 Wire-level (Cargo IT, REST + MCP)
- `GET /api/knowledge-graph/snapshot` defaults to `human`-only.
- `?min_tier=machine` includes machine-tier rows.
- Invalid `min_tier` → 400.
- `POST /admin/knowledge-graph/judge/run` returns 202 and the runner picks
  up rows on the next tick (or synchronously when invoked via the
  per-proposal endpoint).
- `POST /admin/knowledge-graph/proposals/{id}/approve` materializes into
  `kg_nodes`/`kg_edges` at `tier='human'`.
- MCP `search_knowledge` honors `min_tier`.

### 10.4 End-to-end smoke
1. Insert a proposal directly.
2. Trigger the judge.
3. Assert `tier='machine'`, audit row present, `kg_node` materialized at
   `tier='machine'`.
4. Approve via REST.
5. Assert `tier='human'`, second audit row, `kg_node.tier='human'`.
6. Reject a different machine-approved proposal.
7. Assert deletion of `kg_node` rows, `kg_rejections` insert.

## 11. Open Items / Future Work

- **Per-credential tier ceilings.** Layer onto the unified API-key admin
  (`project_api_key_admin_9b`) once that ships.
- **Drift detection.** A scheduled re-judge of a sampled subset of
  human-approved proposals could measure judge calibration over time.
  Out of scope for v1.
- **Bulk re-judge on model upgrade.** Currently operator-initiated. If model
  upgrades become frequent, add a `--clear-machine-status WHERE …` flag to
  `bin/kg-judge.sh` so it's a one-liner.
- **Retrieval-quality CI integration.** The existing
  `RetrievalQualityRunner` could gain a `min_tier` axis so we can measure
  retrieval quality at human-only vs. machine-augmented graph density.

## 12. Decisions Log

| Question | Decision |
|----------|----------|
| Q1: machine-approval mechanism | B — judge LLM with second opinion. |
| Q2: lifecycle shape | C — single `tier` column + `kg_proposal_reviews` audit history. |
| Q3: where machine-approved data lives | A — materialize into `kg_nodes`/`kg_edges` with `tier` column. |
| Q4: caller opt-in | A — per-call `min_tier` parameter; default is `machine` (broader view). Callers that want the strict view pass `min_tier=human`. |
| Q5: judge timing | B + admin trigger — async runner with admin "run now" endpoint and CLI. |
| Q6: machine-rejected proposals | A — hard auto-reject, write `kg_rejections`. |
| Judge model | Same model as extractor (`gemma4-assist:latest`); distinct system prompt. |
| Existing approved rows | Grandfathered as `tier='human'`. |
| Re-judging cadence | Operator-initiated. |

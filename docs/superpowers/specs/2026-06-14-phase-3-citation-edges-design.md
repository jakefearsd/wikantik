# Phase 3 — Citation Edges + Self-Healing Grounding

**Status:** Approved design (2026-06-14). Part of the RAG-as-a-Service program
(`docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md`).
**Governing ADR:** `docs/adr/0005-persisted-citation-edges-and-stale-citation-curation.md`.
**Depends on:** Phase 1's version-pinned `CitationHandle` and the now-correct chunker
heading-fidelity (citations anchor on heading-paths).

## Why this phase exists

Phase 1 produces grounded, version-pinned context bundles. But those citations are
**point-in-time**. The knowledge base churns fast — agentic editing rewrites content
rapidly and is the steady state, not an edge case (ADR-0002). The moment a cited section
is rewritten, a prior citation can become silently false: the citation still reads as
authoritative while the ground has moved under it. Confident-but-wrong grounding is the
worst RAG failure mode, and a fast-churning base manufactures it continuously.

Phase 3 is the **integrity + self-healing layer** that makes a fast-churning base safe to
cite from. It detects when the ground moved under a claim, surfaces it as a patient
curation task, and lets agents (or humans) re-ground via RAG-as-a-Service and re-pin. The
base heals its own grounding as fast as it churns. This is what makes "agents as the
maintenance crew" sustainable instead of entropy-increasing.

## Decision 1 — Markup carries the span in the link title (reconciles link-style with ADR-0005)

Citations are **link-style** (the approved Option B), but the verbatim cited span lives in
the markdown link **title** so the table stays re-derivable from the body (ADR-0005's "body
is the self-contained source of truth, the table is a queryable index, re-derives on
reflow"):

```
[your prose claim](cite://<target_canonical_id>/<Heading%20Path> "verbatim span from the cited section")
```

| Part | Role |
|------|------|
| Visible link text (`your prose claim`) | The source-side claim. Human-readable prose; renders as a hyperlink. |
| Link target (`cite://canonical_id/heading-path`) | The rename-stable reference. Resolves to `/wiki/{slug}#{anchor}` at render. `canonical_id` (not slug) survives renames. Empty heading-path = page-level. |
| Link title (`"verbatim span"`) | The verbatim span being grounded. Hashed for staleness detection. |

Everything load-bearing is **in the body**, so the `citations` table is fully re-derivable.
The only genuinely table-resident state is the **target version at first-cite + status +
last-checked timestamps** — observability, *not* the staleness decision. This is the thin
sticky layer Option B implies, and because the span lives in the body (title), there is no
table-only span that could silently desync from the body.

**Rejected:** putting the verbatim span only in the table (Option B's most literal reading)
— it makes the table authoritative for un-derivable state, breaking ADR-0005's reflow
invariant and requiring fragile re-pin reconciliation.

## Decision 2 — Citation identity

Identity is fully body-derived:

```
(source_canonical_id, target_canonical_id, target_heading_path, span_hash[, ordinal])
```

`span_hash` is a normalized hash of the link-title span. `ordinal` disambiguates the same
span cited more than once on one page (document order among duplicates).

On every save the body is re-parsed and reconciled against existing rows for that
`source_canonical_id`:

- **Same identity present** → carry the version pin + status forward (drift clock
  preserved). Editing the visible *claim* alone does **not** change identity, because the
  span lives in the title.
- **New identity** (author edits the title span, or repoints the target) → insert a fresh
  row pinned to the target's **current** version = a deliberate re-grounding.
- **Identity no longer in body** → delete the row.

This is "re-derives on reflow" with the surviving-identity version pin preserved.

## Decision 3 — Graded, span-level staleness

Status enum: `current` · `stale` · `target_missing`.

- **current** — the pinned span is still present in the target's current `heading-path`
  section.
- **stale** — *span drift*: the pinned span no longer appears in that section (content
  changed, span moved, or the heading was renamed so the path no longer resolves). This is
  the **only** condition that means "stale."
- **target_missing** — the target `canonical_id` is no longer live (a true delete,
  disambiguated from a rename via `canonical_id` liveness — the same technique
  `OntologyEventListener` already uses).

**Version drift is ignored** — in a fast-churning base the target version moves constantly
and means nothing on its own (ADR-0005). The optional **semantic-contradiction LLM check**
(run only on span-drifted, high-value citations) is **deferred** — the enum leaves room for
it, but Phase 3 does not build it.

Staleness is a **patient curation task** — never a save-time error, never alarming, never
shown to anonymous readers. Churn is the steady state.

The check: load the target page's current body, resolve the `heading-path` section, and
test whether the normalized pinned span is present in that section's normalized text.
Heading-path no longer resolving → treat as span drift (the anchor moved) = `stale`.

## Decision 4 — Reconciler triggers (both, mirroring existing patterns)

- **Event-driven** via a `WikiEventListener` (the `OntologyEventListener`/`OntologyPageSync`
  seam — chosen over a `PageFilter` because we need `PAGE_DELETED`/rename handling the filter
  chain doesn't deliver). On **save** of page *P*: reconcile *P*'s **outbound** citations
  (extract → upsert → grade) *and* re-grade all **inbound** citations targeting *P*. On
  **delete**: mark inbound citations `target_missing` (after the `canonical_id`-liveness
  rename guard). The listener re-fetches the body via `PageManager`, exactly like
  `OntologyPageSync.onPageSaved`.
- **Full reconcile**: a `reconcileAll()` that re-grades every citation (catches missed
  events and deletes) runs off `OntologyRebuildCoordinator.onRebuildComplete` — the same
  nightly cadence the drift sweep already rides (there is no separate cron).

The event handler gives freshness; the full reconcile is the completeness safety net. The
`/admin/drift` citations view reports **live** counts from the `citations` table (status
breakdown + offender lists); persisted burn-down trend snapshots are a later refinement.

## Decision 5 — Surfaces (human–machine parity)

Each page exposes its stale citations **both ways**:

- **Outbound** — the stale citations this page makes.
- **Inbound** — citations others make to a span of this page that has since changed.

Surfaced via:

- **`/admin/drift`** — extend `AdminDriftResource` with a bidirectional Stale Citations view;
  aggregate counts feed the existing `DriftCount` / snapshot burn-down model.
- **`list_stale_citations`** — a read-only MCP tool on `/knowledge-mcp` (params: direction
  `outbound|inbound|both`, optional page filter, limit). Returns stale rows with target ref,
  pinned-vs-current, and the claim. Exact `/knowledge-mcp` tool-count bump verified against
  the live registry at build time.
- **for-agent projection** — `PageForAgentResource` (`/api/pages/for-agent/{id}`) gains a
  `stale_citations` field so an agent fetching a page sees what to re-ground.

Staleness indicators appear only in editor / admin / for-agent surfaces — **never to
anonymous readers**.

## Decision 6 — The self-healing loop

```
churn → span-drift → citation queued stale → agent/human re-grounds via /api/bundle
      → edits the body (updates span/claim or repoints) → save re-parses
      → new identity pinned to current version → status current → queue drains
```

The system **detects and surfaces**; it never silently auto-rewrites — that would hide real
drift (same principle as the advisory frontmatter validator). Because the span lives in the
body title, "healing" is always a body edit and the table re-derives — there is no
table-only re-pin that could desync body and table. This is the intended agentic workflow,
not a side effect.

## Code placement

| Concern | Location |
|---------|----------|
| Parser, reconciler, grader, repository | `com.wikantik.citation.*` (wikantik-main) — mirrors `com.wikantik.drift.*` / `com.wikantik.pagegraph.*` |
| Shared contracts (for-agent + MCP DTOs) | `com.wikantik.api.citation.*` (wikantik-api) |
| Save/rename/delete extraction + inbound re-check | `CitationEventListener` (`WikiEventListener`) + `CitationSync`, mirroring `OntologyEventListener`/`OntologyPageSync`; registered on `PageManager` + `FilterManager` (strong-ref retained) |
| Full reconcile cadence | `CitationSync.reconcileAll()` hooked off `OntologyRebuildCoordinator.onRebuildComplete` (same cadence as the drift sweep) |
| Schema | `bin/db/migrations/V040__citations.sql` — idempotent, DDL-only |
| REST surface | extend `AdminDriftResource`; add `stale_citations` to `PageForAgentResource` |
| MCP tool | `list_stale_citations` in wikantik-knowledge (`KnowledgeMcpInitializer`) |

## Schema (V040 — DDL-only, idempotent)

`citations` columns (final names settled in the plan):

- `id` bigserial PK
- `source_canonical_id` text NOT NULL — the citing page
- `target_canonical_id` text NOT NULL — the cited page
- `target_heading_path` text NOT NULL — `''` = page-level
- `span_text` text NOT NULL — verbatim cited span (from link title)
- `span_hash` text NOT NULL — normalized hash of `span_text`
- `claim_text` text — visible prose claim (denormalized for display)
- `ordinal` int NOT NULL DEFAULT 0 — duplicate disambiguation
- `pinned_target_version` int — target version at first-pin / last re-pin (observability)
- `status` text NOT NULL DEFAULT 'current' — `current|stale|target_missing`
- `first_seen`, `last_checked`, `last_status_change` timestamptz
- `UNIQUE (source_canonical_id, target_canonical_id, target_heading_path, span_hash, ordinal)`
- indices on `source_canonical_id`, `target_canonical_id`, `status`

## Testing approach (TDD — test fails first)

- `CitationMarkupParserTest` — parse `[claim](cite://cid/heading "span")`, extract identity;
  reject malformed; page-level (empty heading-path).
- `CitationReconcilerTest` — new / same / vanished identity; version-pin stickiness; claim
  edit preserves identity; span/title edit creates a new identity.
- `CitationStalenessGraderTest` — `current` vs `stale` (span gone from section) vs
  `target_missing` (canonical_id not live); heading-path no longer resolving = stale;
  version-drift-only = still `current`.
- Event-listener test — saving a target re-grades its inbound citations; deleting a target
  (no live `canonical_id`) flips them to `target_missing`; a rename does not.
- Reconcile test — `CitationSync.reconcileAll()` re-grades every citation.
- REST/MCP IT — `list_stale_citations`, the `/admin/drift` citation surface, and the
  for-agent `stale_citations` field, wire-level.

## Out of scope (deferred)

- Semantic-contradiction LLM check (enum reserves the status; not built here).
- Auto-rewriting / silent re-pin (deliberately excluded — would hide drift).
- Citation analytics beyond the burn-down counts.

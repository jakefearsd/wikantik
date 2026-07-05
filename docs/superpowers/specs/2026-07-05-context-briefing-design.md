# Context Briefing Service — Design

**Date:** 2026-07-05
**Status:** Approved (brainstorm complete)
**Goal:** Make Wikantik's RAG-as-a-Service the superior way to feed company context
(business processes, goals, runbooks) to coding agents — Claude Code and Antigravity —
at session start (A path) and on direction mid-session (C path).

## Problem

The wiki will hold the company's business processes and goals. Agents working in
company repos need that context in two moments:

- **A. Session start** — relevant standing context arrives automatically in each
  fresh context window (per `/clear` or subagent launch), before work begins.
- **C. Directed pull** — the user says "check the wiki for X" mid-session and the
  pull is one-shot and consistent across clients.

The grounded-agent evals established that the pre-assembled bundle path is the
reliable route and that agent-initiated retrieval is the weak link — so the A path
leans on deterministic injection, not agent judgment.

## Decisions (settled during brainstorm)

1. **A1 + A2 combined:** repo-declared parameters give the standing scope; the
   user's first prompt in each fresh context refines the actual retrieval query.
2. **Server-side composition** (ADR-0003 posture): one new surface composes the
   briefing; client shims stay ~10 lines each. Portability across Claude Code and
   Antigravity is achieved by keeping all logic in the wiki.
3. **Budgeted hybrid payload:** a per-call token budget filled priority-ordered,
   degrading gracefully to pointers. Lean-vs-rich is a per-repo editorial knob,
   not an architectural choice.
4. **No profile entity.** An earlier draft had `type: context-profile` wiki pages;
   rejected as redundant with CLAUDE.md/AGENTS.md (standing prose is the repo's
   job) and over-engineered (the rest is ~5 lines of config). The briefing service
   takes **parameters**; repos declare them in their own config; the wiki-side
   grouping reuses **clusters** (change what a scope means by editing cluster
   membership — no consuming repo changes).
5. **C path = consistency, not new machinery (C1):** a lean portable *consumption*
   skill, same text for both clients, MCP-only.
6. **Success = dogfooding (S2) + instrumentation (S3), no eval gate.** The eval
   harness cannot resolve small deltas (±0.06–0.19 noise floor) and the business
   corpus does not exist yet to eval against. Iterate post-launch.

Division of labor:

| Owner                        | Responsibility                                                          |
| ---------------------------- | ----------------------------------------------------------------------- |
| CLAUDE.md / AGENTS.md (repo) | Static instructions + briefing parameters (which scope this repo is in) |
| Wiki                         | Live content, cluster membership, verification, ACLs                    |
| Briefing service             | Assembly, budget, dedup, rendering at session start                     |

## Surface

Mirrors the bundle parity pattern (`assemble_bundle` / `/api/bundle`):

- **MCP:** `get_briefing` on `/knowledge-mcp` (tool #21).
- **REST:** `GET /api/briefing?pins=&clusters=&prompt=&budget=&format=json|md`.

Parameters (all optional except at least one of `pins`/`clusters`/`prompt`):

| Param        | Meaning                                                                                                                         |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `pins`       | Comma-separated page names or canonical_ids — individually load-bearing pages, included in order                                |
| `clusters`   | Comma-separated cluster names — retrieval scope + membership expansion                                                          |
| `prompt`     | The user's first message; refines retrieval via `BundleAssemblyService`                                                         |
| `budget`     | Token budget (default `wikantik.briefing.default_budget`, capped by `max_budget`)                                               |
| `scope_mode` | `prefer` (default: in-scope sections rank first) or `strict` (out-of-scope dropped)                                             |
| `format`     | REST only: `json` (structured) or `md` (injection-ready markdown — the server owns the final rendered text; shims never format) |

## Assembly algorithm

Fill the budget in priority order:

1. **Prompt-refined sections** — if `prompt` present, run through
   `BundleAssemblyService`; bias/filter by `clusters` per `scope_mode`.
   Coverage signal included (`BundleCoverage`, post-ACL recount semantics).
2. **Pinned pages** — full body while budget lasts, in `pins` order.
3. **Cluster member pages** (not already served) — full body while budget lasts,
   then degrade. Order: hub page first, then members by last-modified descending.
4. **Pointer footer** — everything that did not fit as title + one-line summary +
   fetch instructions; plus a short standing note for the C path ("deepen with
   `assemble_bundle`, fetch with `read_pages`").

Cross-source dedup by section key (a pinned page's section already served by the
refined bundle is not repeated). Token accounting reuses the chunker's estimator.
All content is ACL-gated through the same `PageViewGate` posture as existing
retrieval tools: pages the caller cannot view are silently omitted and reflected
in coverage counts, matching the bundle's recount behavior.

## Placement

Follows the bundle exactly:

- Types: `wikantik-api` → `com.wikantik.api.briefing` (`BriefingRequest`,
  `ContextBriefing`, `BriefingAssemblyService`).
- Logic: `wikantik-main` → `com.wikantik.knowledge.briefing`
  (`DefaultBriefingAssemblyService`, budget filler, markdown renderer).
- Wired at the same post-startup seam as `BundleServiceWiring`; stays off the
  `getManager` allow-list (DecompositionArchTest R-2).
- MCP tool: `wikantik-knowledge`. REST resource: `wikantik-rest`.
- Config: `wikantik.briefing.{enabled, default_budget, max_budget}`
  (enabled default true; default_budget 6000; max_budget 24000 — provisional,
  tune during dogfooding).

## Client shims

- **Claude Code:** a `UserPromptSubmit` hook firing **once per fresh context**
  (session-id-keyed state file), calling `GET /api/briefing?format=md` with the
  repo's parameters (env vars in `.claude/settings.json`) + the first prompt text,
  emitting the result as additionalContext. Hooks are shell, so REST + curl + API
  key is correct here; the MCP-only rule stays scoped to the agent-driven skill.
- **Antigravity:** no hook mechanism assumed — a rules-file snippet instructs the
  agent to call `get_briefing(clusters=…, pins=…, prompt=<first request>)` as its
  first action over the already-configured MCP endpoint. **Verifying Antigravity's
  actual injection capabilities is an explicit early implementation task**; if it
  has hook-like injection, upgrade to deterministic delivery.
- **Consumption skill:** a lean portable `wiki-context` skill (distinct from the
  authoring-oriented wiki-content skill): briefing first, `assemble_bundle` for
  depth, check coverage, escalate to `read_pages`/`traverse`. Same text ships to
  both clients. MCP-only.
- Shims live in-repo under `clients/` (exact layout decided in the plan) so both
  can be copied into consuming repos.

## Instrumentation (S3)

- New `briefing_log` table (next `V<NNN>` migration, DDL-only per house rule):
  pins/clusters (text), prompt-present flag, budget requested/used, section/pin/
  pointer counts, surface, created_at.
- The refinement query additionally flows into the existing `retrieval_query_log`
  via `JdbcQueryLogService` with `source_surface='api_briefing'` / `'mcp_get_briefing'`, so it appears in
  `list_retrieval_queries`.
- Review after a few weeks of dogfooding. No gates, no dashboards yet.

## Error handling

- Unknown pin/cluster → included in a `warnings` list in the payload (briefing
  still returns; a typo'd pin must not kill session start), logged `WARN`.
- No parameters at all → 400 / tool error naming the required params.
- Bundle-path failure → fail-soft to pins+clusters-only briefing with a
  degradation note in the payload. Never a dead session start.
- No prompt → pins + cluster content only (pure A1).
- Budget below smallest unit → pointer-only briefing.
- No empty catch blocks; every degradation logs at least `WARN` with context.

## Testing

TDD throughout (failing test first):

- Unit: budget fill order, cross-source dedup, degrade-to-pointer, `prefer` vs
  `strict` scoping, pin resolution (name, canonical_id, missing, wrong caller ACL
  → omitted + counted), markdown renderer golden test, coverage recount.
- Wire ITs (Cargo): REST `format=json|md` + MCP `get_briefing` happy path,
  ACL omission, no-param 400. (Read-only surface, but same unit+wire discipline
  as the MCP write-surface rule.)
- Dogfood gate (S2): this repo + the first company repo wired with real
  parameters; success = no manual context pasting, first actions reflect the
  briefing, directed pulls feel one-shot.

## Out of scope (post-launch backlog)

- Session dedup state ("you already have sections X, Y").
- "What changed since your last session" diffs.
- Re-refinement on later prompts (A2 fires once per fresh context only).
- An eval arm for the A path.
- Briefing admin dashboard over `briefing_log`.

All layer on this surface without breaking it.

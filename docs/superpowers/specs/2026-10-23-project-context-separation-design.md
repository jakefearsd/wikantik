# Project Context Separation — Design

**Date:** 2026-10-23
**Status:** approved 2026-10-23 (builder check same day)
**Subsystem:** context briefing (`com.wikantik.knowledge.briefing`), structural index / Page Graph
**Related:** ADR-0009 (cluster taxonomy is frontmatter projection), ADR-0001 (bundle, never synthesis),
`SimpleAgilityStackHub`, `SimpleAgilityAgentCoordination`, `docs/wikantik-pages/ClusterDeclarationDesign.md`

## Intent

Coding agents on jakemon, Roller, Stockroom and Wikantik all start their sessions from the same wiki.
Each one needs *its* project's context plus the cross-project contracts that bind it — and should not be
fed another project's implementation plans. Today the briefing can't tell the two apart, so a Roller
telemetry session gets a Stockroom photo-identification plan as "standing context".

This design makes the briefing project-aware **without declaring projects anywhere**. The separation and
the alignment both fall out of structure the corpus already has: cluster membership (a project *is* its
cluster) and wikilinks (a project cites the contracts it is bound by). The one piece of explicit
structure is a content fix — Stockroom gets the hub and cluster every other project already has.

## What simulated retrieval showed (prod, 2026-10-23)

Eight `get_briefing` calls were run against prod before this was written, one per project plus the
cross-cutting cases. Every row below is reproducible with the call shown.

| # | Call | Task-relevant sections | Standing context (after the hub) | Pointers |
|---|---|---|---|---|
| 1 | `clusters=[simple-agility]`, prompt: Roller telemetry | correct (Adoption § Roller, Handoff spec, SA-7) | **`StockroomPhase7PhotoIdentificationPlan`** | 20, 11 of them Stockroom plans |
| 2 | `clusters=[roller]`, same prompt, PREFER | **10 of 12 from one page** (`RollerTelemetryHandoffSpec`); `SimpleAgilityTelemetryContract` absent | `RollerMcp` | contract pages absent — not members |
| 3 | `clusters=[roller]`, same prompt, **STRICT** | **11 of 11 from one page**; the one cross-project section (Adoption § Roller) dropped | `RollerMcp`, `RollerW3tPlugins` | 5, no contracts |
| 4 | `clusters=[jakemon]`, prompt: new push feed | good (JakemonHub, ObservabilityPlane — a manual multi-member) | **`IncidentWebmasterProviderStale`** | 45; 42 incident runbooks ahead of the two contracts |
| 5 | `clusters=[stockroom]` | fine via prompt (CommerceDataFlow) | **none — `unknown cluster: stockroom`** | none |
| 6 | no cluster, prompt: job runner + lease guard | **11 of 12 from one page** (`BackgroundJobProcessing`) | — | — |
| 7 | `clusters=[wikantik-development]`, prompt: `trace.id` | good mix, but **`RollerTelemetryHandoffSpec` ×2 leaked in** | **`LoginHelp`** | **130**, incl. `SandBox`, `UnusedPages`, `RecentChanges` |
| 8 | any call with `scope="strict"` (wrong name; the argument is `scope_mode`) | silently ran as PREFER | — | — |

Read across the rows, the defects are:

1. **Standing context is chosen by recency** (rows 1, 2, 3, 4, 7). `ItemAssembler.clusterMembers`
   orders hub-first, then `updated` descending. "Most recently edited" is a fine signal for a
   changelog and a bad one for "what every session in this project should know": it produced a
   Stockroom plan, an MCP runbook, an incident runbook and a login-help page.
2. **A project cannot reach its contracts** (rows 2, 3). Nothing expresses "Roller is bound by these
   shared pages"; STRICT makes it worse by dropping the one cross-project section the prompt found.
   The `clusters: [simple-agility, jakemon]` multi-membership on two contract pages (row 4) is a
   hand-made version of the missing link for one project.
3. **Sibling projects leak in both directions** (rows 1, 7): Stockroom into a Roller/stack briefing,
   Roller into a Wikantik one.
4. **One page can take the whole task-relevant budget** (rows 2, 3, 6: 10/12, 11/11, 11/12). The
   bundle caps sections per page at 20; the briefing's 12-section budget has no diversity rule at all.
5. **Pointer lists are unranked and uncapped** (rows 4, 7): 45 and 130 pointers in `updated` order,
   so contracts sit under runbooks and system pages.
6. **`stockroom` is not a cluster** (row 5) — Stockroom pages are parked in `simple-agility`.
7. **Misnamed arguments are silently ignored** (row 8). The MCP tool and `/api/briefing` accept
   unknown parameters without a warning, so a caller who writes `scope` gets PREFER and no signal.

Behind defects 1–3 are two structural causes:

1. **The `simple-agility` cluster is two things at once.** 11 of its 22 pages are Stockroom-local
   designs and plans, parked there because "Stockroom has its design page until it has a hub"
   (`SimpleAgilityStackHub` § Components). Every other project has its own cluster
   (`jakemon`, `roller`, `wikantik-development`, `wikantik-platform`).
2. **Standing context is chosen by recency.** `ItemAssembler.clusterMembers` orders members hub-first,
   then by `updated` descending. "Most recently edited" is a fine signal for a changelog and a bad one
   for "what every session in this project should know". Fixing cause 1 alone would leave this: the
   next most recently edited contract page is as likely to be the Attribution RFC as the Telemetry
   Contract.

There is a third, latent gap: nothing today expresses "Roller is bound by these shared pages". The
`clusters:` multi-membership on `SimpleAgilityTelemetryContract` (`[simple-agility, jakemon]`) is a
hand-made version of it for one project. It works, but it is curation that has to be remembered per
contract per project — exactly the explicit structure this design prefers to derive.

## Goals

- A briefing scoped to a project cluster delivers that project's pages **and** the cross-project pages
  the project actually cites, in that priority order, and nothing from sibling projects as full text.
- Cross-project alignment survives: when the prompt-driven part of the briefing finds a sibling
  project's section that is relevant (Stockroom already solved the same problem), the agent still
  learns it exists — as a pointer, not as budget-consuming body text.
- Zero new frontmatter fields, zero new request parameters, zero new hub structure beyond the one
  missing hub. The corpus should not need to learn a convention for this to work.
- The behaviour is measured before and after by a repeatable evaluation, and the ranking changes are
  unit-tested against a fixture corpus.

## Non-goals

- Changing the behaviour of `assemble_bundle` / `/api/bundle`. The bundle stays global; separation is
  a briefing (session-start) concern. Cross-project reach in the bundle is a feature for answer
  grounding. (An *additive* service method that returns a larger candidate pool to the briefing is in
  scope — see "Diversity" — but the wire behaviour of both bundle surfaces is unchanged.)
- Nesting project clusters under `simple-agility`. Sub-clusters are one level deep permanently
  (ADR-0009), that level is spent on topical sub-clusters, and the hub already says hierarchy is
  expressed by links. This design is the mechanism that makes nesting unnecessary.
- A `project:` frontmatter field, or a `pattern/*` tag taxonomy. See "Why not a pattern tag".
- Knowledge Graph involvement. Entity co-mention across clusters is a plausible later source of
  "peer" pointers, but it has measured as zero-lift for retrieval before (`eval/kg-spike`) and is not
  on the critical path.
- Answer synthesis of any kind (ADR-0001).

## Anti-goals (what would make this a failure even if it worked)

- A Roller briefing that no longer mentions the Telemetry Contract because the contract moved out of
  "its" cluster. Separation must not cost alignment.
- A rule that only works for the Simple Agility clusters. The mechanism must be generic over every
  cluster (a `mathematics` briefing should also prefer its own hub and most-cited pages).
- Any step that requires editing dozens of pages to add a marker. Phase 0 moves pages; it does not
  tag them.

## Design

### Vocabulary

- **Project cluster** — any cluster. A project is simply the cluster its hub declares; `roller`,
  `jakemon`, `stockroom` (new), `wikantik-development`. No code distinguishes "project" clusters
  from topical ones; the ranking below is generic.
- **Cited page** (of a cluster C) — a page *not* a member of C that at least one member of C links to.
  Membership is `ClusterPath`-transitive (sub-cluster members count), never `startsWith`.
- **Citers** (of page P within C) — the number of distinct members of C that link to P.
- **Reach** (of page P) — the number of distinct clusters that have at least one member linking to P.
  `SimpleAgilityTelemetryContract` has inbound links from `jakemon`, `roller`, `wikantik-platform`,
  `agentic-coding`, `simple-agility` and (today) the Stockroom pages: reach ≥ 5. A Stockroom phase
  plan has reach 1.
- **In-degree** (of member P within C) — the number of other members of C that link to P.

All four are pure Page Graph derivations; the structural index already holds membership and
`DefaultReferenceManager` already holds the link table.

### Reading citations as adoption

The tiers are easiest to reason about through one sentence, which is also ADR-0011's statement:

> **A citation from a project page to a shared page is that project's adoption of it.**

A cross-cutting decision lives on one page (`SimpleAgilityDecisionLog` SA-6, or the contract page it
points at) and does not need to know whom it binds. Each bound project says so the only way content
can say anything: a page in that project cites it. Tier 2 is therefore *the set of shared decisions
this project has adopted*; **reach** is *how cross-cutting a decision actually is*; and the decision
log's **Binds** column is the declared form of the same relationship.

Consequences:

- Adoption is a link, so it is cheap and local. Roller adopts ECS logging by writing
  `RollerTelemetrySpec → SimpleAgilityTelemetryContract`. No one edits the contract, no hub grows.
- The decision page stays stable while its blast radius changes. A fourth product's hub cites the
  contract and inherits it in its first briefing; the contract is untouched.
- Drift becomes measurable: *declared binds minus observed reach* names every decision a project is
  bound by but has never cited. Today that is invisible; see Phase 4.

The limit is the same fact read the other way: a decision reaches a project **only** if the project
cites it. The briefing reports what the corpus says, not what it should say. The coordination
protocol's existing rule — adopt a contract by linking to it ("contract-first modifications" on
`SimpleAgilityAgentCoordination`) — is what keeps the two aligned, and this design is what gives that
rule teeth.

### Three tiers instead of one list

A cluster-scoped briefing currently has one scope: "slugs in the cluster". It becomes three ordered
tiers, computed once per request from the structural snapshot:

| Tier | Contents | Ordering within tier |
|---|---|---|
| 0 | Pins (unchanged) | request order |
| 1 | Members of each requested cluster | hub first, then **in-degree desc, then `updated` desc** |
| 2 | Cited pages of each requested cluster | **citers desc**, then reach desc, then `updated` desc; capped |

Tier 1's ordering change is what fixes the "most recently edited plan becomes standing context" defect
on its own, for every cluster. Tier 2 is what makes a `roller` briefing carry the Telemetry Contract,
Decision Log and Stack Hub without any page declaring that it does: Roller's own pages cite them.

**Which links count.** Every edge `DefaultReferenceManager` holds — body wikilinks *and* frontmatter
`related:` entries — counts toward in-degree, citers and reach. `related:` is deliberate curation and
is exactly how a hub names its members and its contracts; a hub's `related:` list gives each member
in-degree 1 uniformly, which does not distort ordering among them (sibling-to-sibling links do the
differentiating). No page type is excluded from tier 2: another cluster's hub is often precisely the
page a project should cite (the Stack Hub for every Simple Agility project). The per-request cap and
citers-first ordering bound what a widely-linked generic page (`Main`, a glossary) can do: it enters
tier 2 only if *this cluster's* members cite it, and ranks by how many of them do.

**Where the numbers come from.** Per request, scoped to the request: members of the requested
clusters from `StructuralIndexService` (≤ `MAX_CLUSTER_MEMBERS`), their outbound links from
`ReferenceManager`, and `findReferrers` on each candidate cited page to compute citers and reach. That
is a few hundred map lookups, not a scan of the link table; it needs no new index, no cache and no
invalidation. Precomputing at structural-snapshot build time is the optimisation if measurement ever
shows it is needed — not the v1 design. (`PageGraphSnapshot` is unsuitable as the source: it is
ACL-redacted per viewer and carries only the primary cluster.)

The tiers feed both halves of the briefing:

- **Standing context** (`ItemAssembler`) walks tier 1 then tier 2 under the existing budget ledger and
  full-body-or-pointer rule. Tier 2 items carry a new origin value `cited` (alongside `pin` and
  `cluster`) so the renderer can group them under their own heading.
- **Task-relevant sections** (`partitionByScope`) partition the global bundle into tier 1, tier 2 and
  *out-of-scope*, stable within each. `STRICT` keeps tiers 1–2 and drops the rest (a strict project
  briefing still carries the contracts — that is the point). `PREFER` keeps today's semantics with the
  extra tier.

### Out-of-scope sections become pointers, not body text

In `PREFER` mode, out-of-scope bundle sections are today appended as ordinary sections and spend budget
on body text. That is the second way a sibling project's plan reaches an agent. They become a compact
**"Elsewhere in the wiki"** list: page title › heading, canonical_id, cluster, one line each, grouped by
cluster, capped (`wikantik.briefing.elsewhere.max`, default 8). This is the cross-project alignment
channel: a Roller agent asking about a job runner sees "Stockroom › Lease guard for the identify job"
as a pointer it can `read_pages`, at the cost of a few dozen tokens rather than a section body.

Elsewhere entries are `BriefingItem`s with origin `elsewhere`, so `BriefingAclGate` filters them
exactly as it filters every other item (a restricted page's title and cluster must not reach an
anonymous caller). They are budgeted the same way existing pointers are, and they are **excluded** from
the "Available on request" rendering and from the `pointerCount` statistic, which keeps its meaning;
they get their own `elsewhereCount`.

The renderer gains two headings. Rendered order:

1. Task-relevant sections (tier 1, then tier 2)
2. Standing context — this project (tier 1 full bodies)
3. Contracts this project cites (tier 2 full bodies or pointers)
4. Elsewhere in the wiki (out-of-scope pointers)
5. Available on request (unchanged)

Headings 3 and 4 are omitted when empty, so briefings without clusters render exactly as today.

### Diversity: a per-page cap on task-relevant sections

The briefing takes the bundle's top sections in order until its section budget is spent. Three of
the eight simulated runs spent ≥ 10 of 12 on one page. The briefing gains its own per-page cap
(`wikantik.briefing.sections_per_page`, default **4**), applied after the tier partition so that
tier-1 sections of a page are preferred over its tier-2 ones.

A cap only helps if there is something to fill the vacated slots with. `BundleAssemblyService.assemble`
returns the bundle's own top-N (12 by default), so the briefing today never sees a 13th candidate.
The service gains an **additive** method — `assemble(query, options)` with a `maxSections` override,
defaulting to today's behaviour, in the same style as the existing `assemble(query, RetrievalMode)` —
and the briefing asks for a candidate pool of `3 × its section budget`. The pool is then
tier-partitioned, per-page-capped and truncated to the budget. `/api/bundle` and `assemble_bundle`
do not call the new method and are unchanged. Displaced sections of a page remain reachable through
the existing `assemble_bundle` deepening hint.

The cap applies to every briefing, including one with no clusters (row 6). "Renders exactly as today"
in this document therefore means *the same headings*, not the same section selection.

This is the one change in the design that is not about separation; it is included because the
simulation showed it degrades every project's briefing and because the same eval measures it.

### Pointers: "Available on request" is ranked and capped

Pointer items follow the same tier ordering as full items (tier 1 by in-degree, then tier 2 by
citers), capped at `wikantik.briefing.pointers.max` (default **30**). A member with neither a summary
nor any inbound link from its cluster (the `SandBox`/`UnusedPages` class) sorts last and is the first
to fall off the cap. The `MAX_CLUSTER_MEMBERS = 200` hard cap stays as the outer bound.

### Unknown arguments warn

`GetBriefingTool` and `/api/briefing` add a `unknown argument: <name>` warning for any parameter they
do not recognise. The call still runs with defaults — the existing degradation contract — but the
caller can see it. Same treatment for `assemble_bundle` is cheap and belongs in the same change.

### Degradation

- No structural index → no tiers; behaviour is today's, with the existing "structural index
  unavailable" warning. Reference data unavailable → tier 2 is empty and a warning says so.
- The cited tier is capped (`wikantik.briefing.scope.cited.max`, default 12 pages per request) and can
  be switched off (`wikantik.briefing.scope.cited.enabled`, default true) — a kill switch, not a mode.
- A hub that links to everything (the Stack Hub links 16 pages) widens its own cluster's tier 2 to the
  cap; that is the contracts cluster, so widening is correct, and the cap bounds it.

### Why not a pattern tag

A `pattern/ecs-logging`-style tag was the obvious simple mechanism. It was rejected as the backbone,
not because it is simple-minded, but because of where the work lands:

- It is per-page curation across four repositories' agents, and this corpus's tag history shows tags
  drift toward SEO keyword soup within weeks.
- It answers "which pages share a pattern" only for pages someone remembered to tag, while a wikilink
  from an implementation to the contract it implements is something agents already write because the
  content needs it.
- Everything a tag would deliver at briefing time, the citation tier delivers from links that exist.
  What a tag would add — explicit discovery (`list_pages_by_filter(tag=pattern/…)`) — is not a
  briefing concern.

The evaluation (Phase 1) is the arbiter. If it shows a class of case the citation tier structurally
cannot reach — a pattern two projects share with no page linking the two — a `pattern:` tag convention
is the recorded fallback, added at that point with the evidence, not before.

### Why not the Knowledge Graph

Shared entities across clusters (both Roller and Stockroom pages mention "lease guard") are a real
signal for peer discovery. It is deferred: it would add an inference dependency to the session-start
path, the KG measured as zero-lift when used as a retrieval signal, and the out-of-scope pointer list
already surfaces the same peers whenever the prompt names the concern. Revisit only with the Phase 1
harness showing misses.

## Phases

Each phase is independently shippable and leaves the system better than it found it.

### Phase 0 — Corpus hygiene (content only, no code)

- Create `StockroomHub` (`type: hub`, `cluster: stockroom`) carrying the "part of the Simple Agility
  stack" callout, and move the 11 Stockroom designs and plans to `cluster: stockroom`. Replace the
  Components-table note in `SimpleAgilityStackHub` and the `related:` entry with the hub.
- `simple-agility` is left with the 11 cross-project pages (hub, 6 contracts/RFCs/data flows, roadmap,
  decision log, coordination, telemetry adoption).
- Leave the two existing `clusters: [simple-agility, jakemon]` multi-memberships alone; do not add more
  for this purpose.
- **Prod is authoritative for content** — none of these pages exist in the checkout. The implementing
  agent derives the move list from the live index (`list_pages_by_filter(cluster=simple-agility)`),
  presents it together with the `StockroomHub` draft, and applies it through the admin MCP surface
  (`write_pages`, `update_page` with `expectedContentHash`) **only after an explicit yes**. Then verify
  with `get_briefing(clusters=["simple-agility"])` and `(clusters=["stockroom"])`. The Phase 1 baseline
  is read-only against prod `/api/briefing` and needs no gate.

Acceptance: in the `simple-agility` briefing from row 1, no Stockroom page appears as standing
context *or* under "Available on request" — they are no longer members. (After Phase 2, `StockroomHub`
and the design page the Stack Hub's components table cites may legitimately appear as **tier-2 cited
pages** of `simple-agility`: the contracts cluster citing each project's entry page is exactly the
relationship the mechanism is meant to surface. Stockroom *phase plans* must not — nothing in
`simple-agility` cites them — and the eval case asserts that distinction, not a blanket ban.)

### Phase 1 — Measure first

- `eval/briefing-scope/`: a case file of `(clusters, scope_mode, prompt?) → must_include_as_section,
  must_include_as_item, must_not_include_as_body, may_point_to, max_sections_per_page`. The eight
  simulated runs in the table above are the seed cases, each with its observed failure written as the
  assertion (row 2: `SimpleAgilityTelemetryContract` must be a tier-2 item; row 7:
  `RollerTelemetryHandoffSpec` may appear only under "Elsewhere"; row 4: no `Incident*` page in
  standing context; and so on). A small Python runner over `/api/briefing?format=json`, modelled on
  `eval/agent-grounding/`, prints a pass/fail table plus two scores: **separation** (fraction of body
  tokens inside tiers 0–2) and **diversity** (distinct pages among task-relevant sections). Manual,
  like the retrieval harness.
- A JUnit fixture corpus (`TestEngine`) of three tiny clusters with cross-links, and tests on the scope
  computation (tier membership, ordering, caps) and on `partitionByScope`. These are red until Phase 2.

Acceptance: the harness runs against prod and records the post-Phase-0 baseline in
`eval/briefing-scope/BASELINE.md`.

### Phase 2 — Tiered scope in the briefing (the code change)

- A `BriefingScope` value (`wikantik-main`, `com.wikantik.knowledge.briefing`) computed from the
  structural snapshot + reference data: ordered tier-1 and tier-2 descriptor lists per request, plus a
  slug→tier lookup. Reach and citers come from the snapshot; if the snapshot does not already carry
  inbound-link cluster sets, derive them at snapshot build time (`PageGraphSnapshotBuilder`), not per
  request.
- `ItemAssembler` consumes the scope's ordered lists; origin `cited` added to `BriefingItem`.
- `DefaultBriefingAssemblyService.partitionByScope` becomes three-way; out-of-scope sections are
  turned into pointer items instead of sections.
- `MarkdownBriefingRenderer` gains the two headings. JSON output gains `tier` on sections and items
  and an `elsewhere[]` array; both additive.
- Per-page section cap and ranked/capped pointers as described above; unknown-argument warnings in
  `GetBriefingTool`, `/api/briefing` and (same mechanism) `assemble_bundle`.
- New keys in `ini/wikantik.properties` first (`ConfigSurfaceDriftTest`): `wikantik.briefing.scope.cited.enabled`,
  `wikantik.briefing.scope.cited.max`, `wikantik.briefing.elsewhere.max`,
  `wikantik.briefing.sections_per_page`, `wikantik.briefing.pointers.max`.

Acceptance: Phase 1 tests green; harness separation score rises on every case; no case loses a
`must_include`.

### Phase 3 — Make it the protocol

- `briefing_log` gains `cited_count`, `elsewhere_count`, `scope_mode` (one DDL-only migration,
  `V06x__briefing_log_tiers.sql`) and `BriefingLogEntry` carries them. **No read surface is added** —
  the table has only a write side today and an operator reads it with SQL; a UI for it is a separate
  decision. The columns exist so adoption and the separation ratio can be queried after release, per
  the plane rule (functional telemetry about the briefing's own output stays a product table).
- `SimpleAgilityAgentCoordination` § Standard Repository Agent Header gains the canonical session-start
  call for a repo: `get_briefing(clusters=["<project cluster>"], prompt="<task>")`, and each repo's
  CLAUDE.md header is updated (jakemon, roller, stockroom, jspwiki).
- `SimpleAgilityDecisionLog` SA-13: *a citation from a project page to a shared page is that
  project's adoption of it; a project's cross-project context is derived from those citations, not
  declared; project hierarchy is expressed by citations, never by cluster nesting.*
  ADR-0011 in `docs/adr/` records the same for this repository, and **closes permanently** the
  "Cluster restructuring" open question on `SimpleAgilityRoadmap` (absorbing `jakemon` /
  `wikantik-development` / `web-properties` under `simple-agility`): the one sub-cluster level is
  spent on topics (ADR-0009), and the citation tier makes nesting unnecessary. The roadmap's open
  question is edited to point at SA-13.
- `get_briefing` tool description and `wikantik-mcp-instructions.txt` updated together
  (`InstructionsRegistryDriftTest`).

### Phase 4 — Conditional and follow-on

**Evidence-gated:** only if the Phase 1 harness, run after Phase 2, shows cross-project misses that
links cannot reach: either the `pattern:` tag convention or KG co-mention as an additional tier-2
source, chosen by which one the failing cases actually need. Not planned; recorded so the trigger is
explicit.

**Natural follow-on (not gated, not in this program):** an *unadopted decision* drift check for
`/admin/drift` — for each decision-log entry, the clusters its Binds column names minus the clusters
with a page citing its normative page. It needs the Binds column in a machine-readable form (today it
is prose in a table), which is a content convention to settle on `SimpleAgilityDecisionLog` first.

## Constraints and gotchas for the builder

- Cluster matching is `ClusterPath` segment-aware everywhere; `startsWith` is a defect.
- The structural index is nullable in `DefaultBriefingAssemblyService`; every new path degrades to
  today's output with a warning, never an exception.
- `BriefingItem`, `ContextBriefing` JSON and the markdown render are consumed by `/api/briefing` and the
  `get_briefing` MCP tool; changes must be additive (new fields, new headings only when non-empty).
- `wikantik.*` keys: declare in `ini/wikantik.properties` with type, default and description before
  reading them (`ConfigSurfaceDriftTest`); regenerate docs with `bin/config-reference.sh --write`.
- Schema change ⇒ numbered idempotent migration, DDL only, no data backfill.
- Phase 0 is a corpus operation against prod via MCP tools; the checkout is a mirror.
- Standing-context budget behaviour (full body if it fits, else pointer; `BUDGET_FLOOR`) is unchanged.
- Expected scale: ~90 clusters, ~1,200 pages, link table in the tens of thousands — per-snapshot
  derivation is cheap; per-request derivation over the whole link table is not.

## Left to the builder

- The exact shape of the per-request scope computation (`BriefingScope`) and where it caches within a
  request; the source (`ReferenceManager` + `StructuralIndexService`) and the per-request scoping are
  decided above.
- Tie-break details beyond those stated; the fixture tests fix the stated ones only.
- The `options` type for the additive `BundleAssemblyService.assemble(query, options)` method.
- The evaluation runner's scoring details beyond the separation score and must/must-not checks.

## Calls made in this document (check these while reading)

- The project is its cluster; no `project:` field, no nesting under `simple-agility`.
- Tier 1 ordering is in-degree over recency.
- Tier 2 is filtered by "cited from inside the cluster" and *ranked* by reach — reach is not a filter.
- Out-of-scope bundle sections become pointers in PREFER mode rather than being dropped or kept as
  body text.
- Pattern tags and KG are deferred behind an evidence gate, not rejected outright.
- Phase 0 moves Stockroom out of `simple-agility`; it does not move `jakemon`/`wikantik-*` in.
- The per-page section cap (default 4) and pointer cap (default 30) are included although they are
  diversity fixes, not separation fixes, because the simulation showed them on every project. The cap
  is fed by an additive `BundleAssemblyService` method returning a 3× candidate pool; the bundle's
  own surfaces are untouched.
- Tier 2 is ordered citers-first (reach is the tie-break); `related:` links count; no page type is
  excluded from tier 2; the numbers are derived per request, scoped to the requested clusters.
- Elsewhere entries are `BriefingItem`s with origin `elsewhere`: ACL-gated like every item, excluded
  from "Available on request" and from `pointerCount`.
- Phase 3 adds `briefing_log` columns only — no read surface.
- The `wikantik-development` cluster's system-page members (`LoginHelp`, `SandBox`, …) are *not* a
  Phase 0 item: in-degree ranking demotes them, and re-clustering system pages is a separate corpus
  decision.

## Amendments (2026-10-23)

Recorded after Phase 2 was built. Earlier text above is left as approved; where it differs, this section governs.

1. **No `tier` on sections in JSON.** Phase 2 said "JSON output gains `tier` on sections and items" and an
   `elsewhere[]` array. Sections carry no tier field: they are ordered by tier (members, then cited pages).
   Items carry no tier either; they carry `origin` (`pin`, `cluster`, `cited`, `elsewhere`) and `cluster`, and
   the out-of-scope pointers are ordinary entries of `items[]` with origin `elsewhere`.
2. **The candidate pool is an explicit key.** "Diversity" sized the pool as `3 × its section budget`. It is the
   key `wikantik.briefing.candidate_pool` (default 36), a count of sections requested through
   `BundleAssemblyService.assemble(query, BundleOptions)`, independent of the token budget. Nine
   `wikantik.briefing.*` keys were declared in `ini/wikantik.properties`: the five Phase 2 listed,
   `candidate_pool`, and `enabled`, `default_budget` and `max_budget`, which had been code-only defaults.
3. **`assemble_bundle` gets no unknown-argument warning.** "Unknown arguments warn" said the same treatment for
   `assemble_bundle` was cheap. `ContextBundle` has no warnings channel, so adding one would change the wire shape
   of both bundle surfaces, which this design holds fixed. Only `/api/briefing` and `get_briefing` warn.
4. **Per-page cap test.** The plan's `perPageCapPrefersTier1SectionsOfAPage` was replaced by
   `perPageCapKeepsFirstNSectionsOfAPageInRankOrder`: a page belongs to exactly one tier, so there is no
   within-page tier preference to test; the cap keeps a page's first N sections in rank order, applied after the
   tier partition.

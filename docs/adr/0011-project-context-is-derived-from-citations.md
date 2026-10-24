# Project context is derived from citations, not declared

**A citation from a project page to a shared page is that project's adoption of it.** A session
briefing scoped to a project cluster is therefore built from three tiers computed from the Page Graph
at request time: tier 1, the cluster's own members, ranked by how many other members link to them
(in-degree); tier 2, the pages *outside* the cluster that at least one member links to, ranked by how
many members cite them (citers), then by how many distinct clusters cite them (reach); and everything
else, which in `prefer` mode becomes a capped list of pointers and in `strict` mode is
dropped. No frontmatter field, no request parameter and no hub structure declares a project's
cross-project context. Project hierarchy is expressed by citations and is **never** expressed by
nesting project clusters under `simple-agility` (or any other cluster): ADR-0009's one sub-cluster
level is spent on topical sub-clusters and is not available for this.

Rationale: eight `get_briefing` calls against prod on 2026-10-23 (one per project plus the
cross-cutting cases) showed the briefing could not tell one project from another.

| # | Call | What went wrong |
|---|---|---|
| 1 | `clusters=[simple-agility]`, Roller telemetry prompt | Standing context was `StockroomPhase7PhotoIdentificationPlan`; 11 of 20 pointers were Stockroom plans |
| 2 | `clusters=[roller]`, same prompt, `prefer` | 10 of 12 sections from one page; `SimpleAgilityTelemetryContract` absent |
| 3 | `clusters=[roller]`, same prompt, `strict` | 11 of 11 sections from one page; the one cross-project section dropped |
| 4 | `clusters=[jakemon]`, new push feed | Standing context was an incident runbook; 42 incident runbooks ranked ahead of the two contracts in 45 pointers |
| 5 | `clusters=[stockroom]` | `unknown cluster: stockroom` — Stockroom pages were parked in `simple-agility` |
| 6 | no cluster, job-runner prompt | 11 of 12 sections from one page |
| 7 | `clusters=[wikantik-development]`, `trace.id` prompt | Standing context was `LoginHelp`; two Roller handoff sections leaked in; 130 unranked pointers |
| 8 | `scope="strict"` (the argument is `scope_mode`) | Silently ran as `prefer` |

Two structural causes sat behind the wrong standing context and the cross-project leaks. First,
`simple-agility` held both the shared contracts and 11 Stockroom-local designs and plans, because
Stockroom had no hub of its own. Second, standing
context was ordered hub first and then by `updated` descending, which is a reasonable signal for a
changelog and a poor one for "what every session in this project should know". Fixing the first
alone would have left the second: the next most recently edited contract is as likely to be an RFC as
the Telemetry Contract. The corpus already holds the signal that was missing. A project's pages
link to the contracts they implement because the content needs the link (a Roller telemetry page has
to point at `SimpleAgilityTelemetryContract`), and those links say which shared pages bind which
project more accurately than a tag a curator must remember to add. A `pattern/*` tag taxonomy and
Knowledge Graph co-mention were considered and deferred behind an evidence gate: the
`eval/briefing-scope/` harness decides, and a case the citation tier structurally cannot reach is the
trigger for revisiting either.

Decision, concretely. Tier 1 and tier 2 are computed per request by `BriefingScope` from the
structural index (membership, `ClusterPath`-transitive) and `ReferenceManager` (body wikilinks and
frontmatter `related:` alike), so they need no new index, cache or invalidation. Tier 2 is capped
(`wikantik.briefing.scope.cited.max`, 12) and has a kill switch
(`wikantik.briefing.scope.cited.enabled`). Cited pages render under their own heading, `## Contracts
this project cites`; out-of-scope retrieval hits render as pointers under `## Elsewhere in the wiki`,
grouped by cluster, so a Roller session can still learn that Stockroom solved the same problem
without spending budget on Stockroom's body text. A per-page section cap
(`wikantik.briefing.sections_per_page`, 4) fed by a larger candidate pool
(`wikantik.briefing.candidate_pool`, 36) and a ranked, capped pointer list
(`wikantik.briefing.pointers.max`, 30) fix the diversity defects in rows 2, 3, 6 and 7; unknown
request arguments now produce an `unknown argument: <name>` warning (row 8). `assemble_bundle` and
`/api/bundle` are unchanged: the bundle stays global, separation is a briefing concern. The
mechanism is generic over clusters; nothing distinguishes a "project" cluster from a topical one.

Consequences, accepted deliberately: a decision reaches a project **only if a page in that project
cites it**. The briefing reports what the corpus says, not what it should say, so the existing
coordination rule — adopt a contract by linking to it (`SimpleAgilityAgentCoordination`,
"contract-first modifications") — is now what carries cross-project alignment, and writing that link
is the whole act of adoption: no one edits the contract and no hub grows. A fourth product whose hub
cites the Telemetry Contract inherits it in its first briefing. Tier 1 order changes for every
cluster: recency is the last tie-break, so a rarely linked page is demoted however recently it was
edited. The per-page cap changes section selection for every briefing, including ones with no
clusters. `briefing_log` gained `cited_count`, `elsewhere_count` and `scope_mode` (V064) so adoption
and the separation ratio can be queried with SQL after release; no read surface was added. The
follow-on this makes possible, and which is not part of this change, is an *unadopted decision* drift
check for `/admin/drift`: for each `SimpleAgilityDecisionLog` entry, the clusters its Binds column
names minus the clusters with a page citing its normative page. It needs the Binds column in a
machine-readable form first. The same rule is recorded for the Simple Agility stack as decision
SA-13 on `SimpleAgilityDecisionLog`, which also closes the "Cluster restructuring" open question on
`SimpleAgilityRoadmap` (absorbing `jakemon`, `wikantik-development` and `web-properties` under
`simple-agility`).

Design and measurements: `docs/superpowers/specs/2026-10-23-project-context-separation-design.md`.
Baseline and harness: `eval/briefing-scope/`.

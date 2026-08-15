---
canonical_id: 01M02FKWMH48PJDTGTPXMESFXE
type: design
status: active
cluster: wikantik-development
date: '2026-08-15'
title: Cluster Declaration Design
tags:
- design
- page-graph
- taxonomy
- structural-index
- frontmatter
- clusters
summary: 'Hub pages declare clusters: consolidating three rival membership mechanisms
  (cluster, hubs, related) and rejecting directory-structured content storage.'
author: claude-opus
related:
- StructuralSpineDesign
- PageGraphVsKnowledgeGraph
- KgInclusionPolicy
- HybridRetrieval
---

# Cluster Declaration Design

> **Status: active. Phases 0, 0b, 1, 2 and 3 complete 2026-08-15; phases 4–5 not started.**
> Supersedes the informal cluster convention described in
> [StructuralSpineDesign](StructuralSpineDesign). Decision recorded as
> ADR-0009. The phases below are sequenced; nothing outside them is planned.

## Hub-declared clusters replace free-text cluster strings

A cluster is currently a free-text string typed independently onto 1,171 pages,
grouped by an exact-match map, validated only for slug *shape*, and joined to a
`type: hub` page by coincidence. It is a `groupBy`, not a partition.

This design makes the hub page the **authoritative declaration** of its cluster,
retires two competing membership mechanisms, and adds exactly one blocking
validation rule. It requires **no new frontmatter fields** — the existing
`(type: hub, cluster: <path>)` pair becomes a declaration rather than a
coincidence.

It also records, permanently, why the content store does not become a directory
tree.

## Problem: the wiki has three taxonomies

The motivating discovery is not that the taxonomy is flat. It is that there are
**three of them**, one entirely unvalidated, kept in partial sync by a
bidirectional filter that has already failed.

| Mechanism | Shape | Identifier space | Adoption | Validated |
|---|---|---|---|---|
| `cluster:` | scalar | cluster slugs (`machine-learning`) | 1,171 pages | Yes — schema, pattern, editor, drift |
| `hubs:` | **list** | page names (`MLHub`) | **473 pages (40%)** | **No — absent from `FrontmatterSchema`** |
| `related:` on hubs | list | page names | 66 of 75 hubs | Only as generic `related` |

`HubSyncFilter` bidirectionally rewrites `hubs:` ↔ `related:` on every save.
Neither is connected to `cluster:`. The three disagree almost completely.

### Measured evidence (corpus at 1,193 pages, 2026-08-15)

**Coverage is excellent; partition quality is not.**

- 1,171 / 1,193 pages carry a cluster (98.2%); 61 distinct clusters; median 13
  pages, max 98 (`wikantik-development`); 5 singletons, 14 clusters of size ≤ 3.
- **41 of 61 clusters have exactly one hub. 20 are headless. 15 have two or more.**
- **13 hub pages carry no cluster at all** (all `auto-generated: true`).
- 5 live clusters are template debris: `Test`, `my-topic-cluster`,
  `thematic-cluster-name`, `thematic-grouping`, `topic-area`.
- Near-duplicate drift: `computer-science` (30) / `computer-science-foundations`
  (13); `software-engineering` (7) / `software-engineering-practices` (38) /
  `software-architecture` (27); `numismatics` (7) / `coin-collecting` (1) /
  `american-coinage` (2); `agentic-ai` (72) / `agent-cookbook` (18).
- `blueprint` appears as a `type` value with no `PageType` constant — it maps
  silently to `UNKNOWN`. 39 pages carry no `type`.

**The hub selection is non-deterministic.** `StructuralProjectionBuilder`
resolves multiple hubs by last-writer-wins:

```java
if ( page.type() == PageType.HUB ) {
    hubByCluster.put( page.cluster(), page );   // last writer wins
}
```

Pages arrive from `AbstractFileProvider.getAllPages()` →
`wikipagedir.listFiles()` — **unsorted filesystem order**. For
`machine-learning`, `list_clusters` reports one of `MLHub`,
`MlModelDeploymentHub`, or `QuantitativeFinanceResearchHub` depending on inode
enumeration order, and never reports a conflict. `StructuralConflict.Kind` has
only `MISSING_CANONICAL_ID` and `RELATION_ISSUE`; nothing about clusters.

**The three taxonomies have fully diverged.**

- `MLHub` declares 11 `related` members. Its cluster has **44**. Ten of the
  eleven are not in the cluster at all.
- `DistributedSystemsHub`: `related` 13, actual members 54, 11 of the 13 not
  members.
- **89 pages already carry multiple `hubs:` entries.** Multi-membership is not a
  future feature — it is live, in production, in an undeclared field.
- `hubs:` points at 46 targets that do not exist as pages: `SoftwareArchitectureHub`
  (12 references), `MachineLearningHub` (11 — the real page is `MLHub`),
  `DatabasesHub` (10), `SecurityHub` (5).
- 13 pages list **themselves** as their own hub.
- `DistributedSystemsHub` — a hub — lists three other hubs as *its* hubs.

`HubSyncFilter` is fully wired (`KnowledgeSubsystemFactory:201`,
`WikiEngine:316`) and its bulk re-save cascades are load-bearing enough that two
unrelated subsystems carry explicit workarounds for them
(`ChunkProjector.java:202`, `BootstrapEmbeddingIndexer.java:187`).

### Where the partition is actually consumed

| Consumer | Use of `cluster` | Weight |
|---|---|---|
| `DefaultKgInclusionPolicy` | Authoritative gate, default-EXCLUDE, exact match | **Load-bearing** |
| Briefing (`ItemAssembler`) | Explicit fan-out scope | Load-bearing |
| BM25 (`DefaultLuceneSearcher`) | One field, boost `2.0f` | Soft signal |
| Dense (`EmbeddingTextBuilder`) | Text prefix `"Cluster: X"` | Soft signal |
| **Context bundle** | **Nothing — no reference in `knowledge/bundle/`** | **Unused** |
| SEO (`SemanticHeadRenderer`) | `articleSection`, `isPartOf`, BreadcrumbList | Cosmetic |
| Ontology (`ConceptProjector`) | `dct:subject` → SKOS concept, `skos:broader` | Structural |
| Sidebar | One-level collapsible tree | Human nav |

The asymmetry matters: **cluster is effective exactly where it is advisory and
fragile exactly where it is authoritative.** The single hard gate — Knowledge
Graph inclusion — inherits all the drift above and defaults to EXCLUDE, so a
typo'd cluster silently removes a page from the Knowledge Graph with no conflict
raised anywhere.

## Decision

### 1. The hub page declares the cluster

A cluster **exists if and only if** a page declares it. A declaration is the
unique page carrying both `type: hub` and a scalar `cluster: <path>`.

No new frontmatter fields. The declaration is implicit in a pair the corpus
already writes:

```yaml
# DECLARATION — top-level
type: hub
cluster: machine-learning

# DECLARATION — sub-cluster (also the sub-cluster's first member)
type: hub
cluster: machine-learning/quantitative-finance

# MEMBERSHIP — any non-hub page naming the path
type: article
cluster: machine-learning/quantitative-finance
```

This inversion makes four measured defects structurally impossible rather than
merely reportable:

| Defect | Why it becomes impossible |
|---|---|
| 15 clusters with 2+ hubs | Two pages declaring one path is a duplicate-key violation |
| 20 headless clusters | A cluster with no declaring page cannot exist |
| 5 template-debris clusters | Naming an undeclared cluster is a reported warning, not a silent new cluster |
| Near-duplicate drift | Merging is editing one hub page, not sweeping 38 articles |

### 2. Hierarchy is a path in the value, exactly one level

Sub-clusters are expressed as `parent/child` in the `cluster` value. This is
already legal — `CLUSTER_SLUG_PATTERN` permits exactly one optional segment, and
`ConceptProjector` already emits `skos:broader` for it:

```java
// FrontmatterSchema.java:38
"^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*)?$"
```

The path form was chosen over holding the parent edge on the hub page for two
reasons. First, **independent verification**: a raw file read with no server, no
index, and no projection still states its full position in the taxonomy.
Second, **collision avoidance**: holding only a leaf name would force cluster
leaf names to be globally unique, and `deployment`, `testing`, `security`, and
`observability` are all plausible children of several top-level clusters at
once.

**One level is permanent.** Deeper nesting is not phased, not deferred, and not
planned. The corpus asks for exactly one level — all 11 sub-cluster candidates
are one deep, and no page in 1,193 asks for two. Bounding depth bounds the
reorganization cost that the path form implies, and bounds the pathology that
makes deep taxonomies unusable: nobody can predict where a page goes.

**Implementation invariant.** The depth limit lives in exactly one place —
`CLUSTER_SLUG_PATTERN`. Every consumer is written depth-agnostic. Prefix
matching is **segment-aware, never string `startsWith`** — this is a correctness
requirement independent of depth, because `startsWith` would wrongly match
`machine-learning` against `machine-learning-ops`.

### 3. Exactly one blocking rule

| Condition | Severity | Rationale |
|---|---|---|
| A page declares `cluster: P` with `type: hub` when another live page already declares `P` | **ERROR (422)** | A true contradiction, detectable at save against the live index, rare, fixable in the same edit. This is the only rule that makes one-hub-per-cluster true by construction. |
| A page names a `cluster` that no hub declares | WARNING + drift counter | Never blocks. The cluster is *pending*: excluded from the published taxonomy, page fully retrievable. |
| A hub names a parent that does not exist | WARNING + drift counter | Hierarchy is advisory until both ends exist. |
| Headless cluster, orphan cluster, near-duplicates | Drift report only | Burn-down, not a gate. |
| *(Phase 5)* `type: hub` with a list-valued `cluster` | **ERROR (422)** | A declaration is singular by definition. Follows from the Phase 5 rule below. |

Enforcement lives in `StructuralSpinePageFilter.preSave()`, which already holds a
live `StructuralIndexService`, already rewrites frontmatter to assign
`canonical_id`, already skips system pages, and is already property-gated. The
review surface is the existing `/admin/drift` dashboard fed by
`DriftSweepService`. Neither needs to be built.

**Curation overhead scales with cluster count, not page count.** A reviewer
decides once per newly-introduced cluster — 61 exist today — not once per page
saved. The ERROR case is an authoring mistake, not a workflow.

### 4. `cluster:` is the sole membership mechanism

`hubs:` and hub `related:`-as-membership are retired.

- **`cluster:` wins** because every consumer already reads it — structural index,
  KG policy, BM25 boost, dense embedding prefix, SEO JSON-LD, ontology
  `dct:subject`, briefing scope, sidebar, search facets. `hubs:` is read by
  exactly one thing, `HubSyncFilter`, which writes it back.
- **Slug space is the stable space.** Page names move — that is why
  `canonical_id` and `page_slug_history` exist. `hubs: [MLHub]` breaks silently
  on rename with nothing to catch it; 46 dangling references prove it already
  has. A taxonomy coordinate should change only when the taxonomy changes.
- **The two-writer bidirectional sync is the disease.** It has provably
  diverged and it taxes unrelated subsystems with re-save cascades they had to
  code around.
- **`cluster:` already has the machinery** — schema entry, pattern, editor
  widget, validator, drift reporting. `hubs:` would need all of it built.

`related:` on hub pages is rescoped to **editorial highlights** — a curated,
ordered, deliberately non-exhaustive "start here" list, projected as
`relatedLink`. Hub `hasPart` is **derived from actual cluster membership**,
which incidentally fixes a live SEO defect: `MLHub` currently publishes
`hasPart` for ten pages that are not in its cluster.

`HubSyncFilter` is removed.

### 5. Rendering makes the implicit declaration explicit

Because the declaration is a *combination* of two fields rather than a named
one, the reader must never have to infer it.

The server ships a derived `cluster_status` block on the page payload —
`{path, parent, hub_slug|null, member_count}` — computed from the structural
index and placed beside `metadata` in `PageResource.toJson`. It is **not** a
client-side join: `DerivedProvenanceBanner` establishes the reader-banner
contract as *"Pure function of props — no fetching, so it is safe on
public/anonymous views"*, and whether a cluster has a hub is not in the page's
own frontmatter.

| State | Rendering |
|---|---|
| Hub | Explicit declaration line: `HUB · defines cluster machine-learning/quantitative-finance · parent machine-learning · 12 pages` |
| Member, cluster declared | `cluster` chip links to the declaring hub page (today it is dead text) |
| Member, cluster undeclared | Path chip + mild `Badge`: *"cluster not yet defined"* |
| No cluster | Mild `Badge`: *"unclustered"* |

**All four states render only when `page.permissions.edit` is true.** The
anonymous render path is unchanged, so ongoing SEO tuning cannot be confounded
by curation signals appearing in public output. Gating happens client-side in
the SPA — never in SSR — so the server response does not vary by user and edge
caching is unaffected.

`page.permissions.edit` is already in the payload
(`PageResource.java:222`) and already consumed at `PageView.jsx:468`.

## Why the content store does not become a directory tree

The obvious alternative is to give the content store real directories:
`docs/wikantik-pages/machine-learning/quantitative-finance/`. It is rejected.

### The distinction this design turns on

This design **adopts paths** in `cluster` values while **rejecting paths** as
storage locations. That is not a contradiction, and the difference is the whole
argument:

> **A path in frontmatter is data. A path in the filesystem is a location.**

Data can be validated, re-projected, multi-valued, and edited through every
surface that already exists — the page editor, `update_page`, ACLs, version
history, backlinks. A location constrains page identity, `page_slug_history`,
`OLD/`, `-att/` directories, URLs, and wikilinks — and can only ever hold one
value.

The clearest expression of the difference is what it costs to reorganize.
**Renaming a cluster does not touch page identity**: no `canonical_id` change,
no `page_slug_history` row, no `OLD/` move, no `-att/` move, no URL change, no
wikilink rewriting, no redirect. It is a frontmatter edit across N pages, fully
versioned and revertable page-by-page. The directory equivalent is a filesystem
migration across three parallel namespaces that mutates identity for every page
in the subtree.

### Mechanical costs

**Flatness is enforced, not incidental.** `AbstractFileProvider.mangleName()`
percent-encodes `/` → `%2F` deliberately. And there are *three* flat sibling
namespaces keyed on the mangled name: pages (`Foo.md`), versions
(`OLD/Foo/1.txt` + `page.properties`, 546 entries today), and attachments
(`Foo-att/bar-dir/`). A tree means all three become trees, in lockstep, in one
atomic migration.

**Routing assumes one segment.** `SpaRoutingFilter.extractPageName()` truncates
at the first `/` (line 234), so `/wiki/mathematics/Topology` resolves to page
`mathematics` today. `web.xml` mappings, `WikiPageFormatFilter`, and every
canonical-URL construction share the assumption.

**Identity coupling.** ~155 `page.getName()` and ~170 `getPage(` call sites
treat the name as an opaque key; roughly 50 tables key on page name or slug.
Most would tolerate a longer opaque string; the ones that *parse* or *construct*
names and URLs would not, and finding them is the entire job.

### Conceptual costs

1. **It makes membership a location, so there can be exactly one.**
   `QuantitativeFinanceResearchHub` is filed under `machine-learning` while its
   own summary describes *"the intersection of Machine Learning [and
   quantitative finance]"* — and a headless `computational-finance` cluster with
   2 pages sits right beside it. 89 pages already assert multiple memberships
   through `hubs:`. A tree forecloses all of it, permanently.
2. **Reorganizing becomes a rename cascade** instead of a frontmatter sweep —
   exactly when the evidence above says a great deal of reorganizing is needed.
3. **The hierarchy was already withdrawn at the UI layer.** `Breadcrumbs.jsx:11`:
   *"This replaced the former hierarchical (Home › cluster › page) breadcrumb."*

### Retrieval evidence

Directories buy retrieval nothing. The dense index does not see paths; it sees
text, and it already receives `"Cluster: X"` via `EmbeddingTextBuilder`. The only
way a tree affects retrieval is by becoming a filter — and filters are
**measured negative** in this system.

From `eval/bundle-corpus/baseline-notes.md`: *"the per-page shortlist costs a
little recall vs unbounded global dense"*, and the dense-chunk source that
retrieves top-K chunks **globally with no page pre-select** *"realises the
ceiling where the page-gated hybrid drops sections on the shortlist boundary."*
Realized bundle recall@12 moved `0.500 → 0.583 → 0.602 → 0.685 → 0.706`, with
the largest single step coming from **removing** a partition gate. The KG
page-level graph rerank told the same story and was deleted for zero lift.

Every partition that became a retrieval filter here has cost recall.

## Rejected: a materialized tree export

A softer proposal was considered and is also rejected — **permanently, not
deferred**: generate a read-only `cluster/slug.md` tree into a build directory
for agent consumption, use it for a year, and let that experience inform whether
to migrate the store.

**1. The experiment cannot produce the evidence it exists to gather.** *(decisive)*
A read-only export can only demonstrate that agents can consume a tree, which
nobody doubts. Every cost of directory storage lives on the **write** side:
rename cascade across three namespaces, identity coupling to location,
single-membership, ACL semantics, `OLD/` and `-att/` migration. A read-only
export exercises none of them. Its outcome is uncorrelated with the decision it
would inform. That is not a cheap experiment; it is a null experiment with a
maintenance bill.

**2. The "zero-risk derived artifact" acquires the constraints of a store.**
Once agents, humans, and scripts navigate `machine-learning/quantitative-finance/`,
that one axis is load-bearing, and multi-membership — which Phase 5 delivers and
which 89 pages already assert — becomes a breaking change to an established
interface.

**3. Committed-or-not is a dilemma with no good horn.** The export is only useful
to a server-less consumer if it is *in the checkout*, which means committing
1,193 duplicate files: every content edit produces two diffs, and a second
editable-looking copy exists with no write-back path. Leave it build-only and
its entire justification evaporates. ADR-0004 established machine-owned bodies
for *derived pages with a retained source*; a tree export is a machine-owned
**copy of human-owned pages**, which is precisely the drift generator that ADR
exists to prevent.

**4. It would be the weakest agent interface in the system.**
`list_pages_by_filter`, `get_page_for_agent`, `read_pages`, `get_briefing`,
`assemble_bundle`, `/api/changes`, and `/export/graph.nt` all carry confidence,
verification, summaries, freshness, and ACL awareness. A directory tree carries
none of it.

**5. The residual benefit is not wanted, and is trivial if it ever is.** The gap
a tree would close is "make `ls` describe the shape." The existing agent surface
already answers that question with more information, and a server-less consumer
already has full frontmatter in every file. Should a filesystem-shaped view ever
be wanted, it is a small post-processing pass over frontmatter, or one more
template in `GenerateMainPageCli` — which already generates `Main.md` from
`Main.pins.yaml` under CI enforcement (`MainPageRegressionTest`, `--check`
mode). **It is not wanted. No work is proposed, now or later.**

## Phases

All seven phases are planned and sequenced. Nothing outside them is in scope.

### Phase 0 — Content migration — **COMPLETE 2026-08-15**

Executable **today, before any code lands**: `cluster: parent/child` is already
legal, `(type: hub, cluster: X)` is already the shape, and every change is a
frontmatter edit revertable per page through normal version history. The
directory equivalent would require the code to land first and offer no per-page
rollback.

**Multi-hub clusters — resolve by pattern:** promote the narrower hub to a
sub-cluster (`FormalMethodsHub` → `distributed-systems/formal-methods`,
`DimensionalModelingHub` → `data-engineering/dimensional-modeling`,
`JavaMemoryManagementHub` → `java/memory-management`, `MlModelDeploymentHub` →
`machine-learning/mlops`, and so on); demote overlapping or stub indexes to
`type: article`; merge true duplicates.

**Headless clusters:** declare a hub for the real ones; fold the debris
(`Test`, `engineering`, `economics-finance`, `military-history`,
`professional-development`, `industrial-ai`, `science`, `programming-languages`,
`american-coinage`, `coin-collecting`) into their proper homes.

**Clusterless hubs:** the `auto-generated: true` pages with spaced names
(`AgentLoops Hub`, `PredicateLogic Hub`, …) become sub-cluster declarations at
their members' majority cluster, with real summaries and tags.

**`hubs:` reconciliation (473 pages, 586 entries)** — 85% mechanical:

| Bucket | Count | Action |
|---|---|---|
| Target shares this page's cluster | **415** | Drop — pure redundancy |
| Target resolves to a different cluster | 112 | Held for Phase 5 as a second membership |
| Target names no existing page | 46 | Drop + report (`SoftwareArchitectureHub` ×12, `MachineLearningHub` ×11, `DatabasesHub` ×10) |
| Self-reference | 13 | Drop |

Phase 0 lands a valid **scalar** state throughout. Pages needing two memberships
get one home now and their second in Phase 5; that sequencing is deliberate, not
churn. The `hubs:` strip itself is deferred until `HubSyncFilter` is removed —
stripping the field while its bidirectional sync is live would cascade hundreds
of unintended `related:` rewrites and invalidate in-flight edit hashes.

*Both targets must be migrated separately.* The repository and production are
**not two copies of one corpus** — see Phase 0b. Each was planned from its own
state and migrated independently.

**Outcome, validated against each target's own index:**

| Invariant | Repository | Production |
|---|---|---|
| Duplicate cluster declarations | 0 (was 15) | 0 (was 19) |
| Headless clusters | 0 (was 20) | 0 (was 11) |
| Clusterless hub pages | 0 (was 13) | 0 (was 6) |
| Orphan sub-clusters | 0 | 0 |
| Depth > 1 | 0 | 0 |
| Sub-clusters created | 24 | 28 |

Production settled at **90 hubs declaring 90 distinct clusters**.

### Phase 0b — Corpus reconciliation — **COMPLETE 2026-08-15**

Phase 0 surfaced a problem the rest of this design had assumed away: **the
repository corpus and the production page store are different corpora, not two
copies of one.**

Measured 2026-08-15: production holds pages absent from `docs/wikantik-pages/`
entirely — including `ProgrammingLanguagesHub`, which *declares production's
`computer-science` cluster* — the repository holds pages production lacks, and
frontmatter differs on pages present in both. The two disagreed on 4 of the 15
multi-hub resolutions.

This is not cosmetic. Planning a corpus-wide change from the checkout produces a
plan that is **wrong for production**. During Phase 0 it did exactly that: a hub
was created on production for a cluster that was headless *in the repository* but
already declared *in production*, introducing the precise duplicate-declaration
defect this design exists to make impossible. It was caught by validating against
the live index rather than against the plan, and reverted.

**The existing transport cannot reconcile them.** `bin/remote.sh pages-pull`
runs as the ssh user while app-written pages are container-owned, so it fails
`Permission denied (13)` across a large subset and **silently returns a partial
corpus** — 1191 of ~1200 pages, missing precisely the pages the application has
rewritten — with nothing but a non-zero exit code to signal the gap. A tool whose
failure mode is "quietly returns most of the data" is worse than no tool, because
its output looks authoritative.

**Decision: production is authoritative for content; the checkout is a mirror.**
Production is where agents write, where the structural index lives, and where the
content persists across deploys. `docs/wikantik-pages/` is the local-development
corpus and the version-controlled record — it is not, and has never been, what
seeds production. (Fresh installs seed from `wikantik-wikipages`, a separate
module.) Naming this explicitly is most of the fix.

Scope:

1. **A complete, verifiable export** (production → repository, one-way). It must
   fail loudly on partial transfer rather than return a truncated corpus. The
   reliable path is the live index — walk `list_pages_by_filter` and batch through
   `read_pages` — not the rsync that is already known to lose container-owned
   files. Incremental via `updated_since` after the first full run.
2. **A divergence report** — pages present on one side only, and frontmatter
   deltas (`cluster`, `type`, `canonical_id`) on pages present in both. This is
   the guardrail that would have prevented the Phase 0 defect. It belongs beside
   the existing `GenerateMainPageCli` in `wikantik-extract-cli`, which already
   establishes the pattern of a CLI that reads the corpus and supports a
   `--check` mode for CI.
3. **The planning rule, stated in the doc and enforced by habit:** corpus-wide
   changes are derived from the live index, never from the checkout. A page
   authored in-repo is pushed to production and then mirrored back, so the
   direction of truth stays single.

Shipped: `CorpusDivergenceCli` in `wikantik-extract-cli` compares
`docs/wikantik-pages/` against a live wiki's `/api/structure/sitemap` — one unpaginated
request, so the remote snapshot is complete by construction rather than assembled from
per-page fetches that can each fail. A `CorpusSnapshot` carries its own read errors, and
`CorpusDiff` **refuses** (exit 2, distinct from exit 1 for divergence) to compare an
incomplete one: that is the encoded lesson, since a partial corpus turns every unread page
into a phantom "missing from production". Filesystem name-mangling is reversed, so
`AgentLoops+Hub.md` compares as the page `AgentLoops Hub`.

```
java -cp wikantik-extract-cli/target/wikantik-extract-cli.jar \
     com.wikantik.extractcli.CorpusDivergenceCli docs/wikantik-pages https://wiki.wikantik.com [--check]
```

First run against production: **240 findings — 3 only-in-repo, 163 only-in-prod, 74
differing fields.** The scale of "only-in-prod" is the measurement that justifies this phase.
Among the differences: `WikantikDevelopment` and `WikantikDevelopmentHub` carry opposite
`type` values in the two corpora — precisely the disagreement that produced the Phase 0
defect.

The one-way export (production → repository) remains unbuilt; the divergence report is the
guardrail that was actually blocking, and it reuses the same `RemoteCorpusSource`.

Phase 0b does **not** block Phase 1; they are independent.

### Phase 1 — Projection — **COMPLETE 2026-08-15**

Segment-aware prefix matching in `StructuralProjection` and `StructuralFilter`;
prefix walk in `DefaultKgInclusionPolicy.lookupCluster` — **no longer latent
after Phase 0**: the lookup is exact-match, so the 28 sub-clusters now in
production inherit no policy and are silently EXCLUDE'd from the Knowledge
Graph until this lands, making it the highest-priority item in the phase;
deterministic hub selection; four new
`StructuralConflict.Kind` values — `DUPLICATE_CLUSTER_DECLARATION`,
`HEADLESS_CLUSTER`, `UNDECLARED_CLUSTER`, `CLUSTERLESS_HUB`; `cluster_status` on
the page payload; hub `hasPart` derived from membership.


Shipped: `ClusterPath` (wikantik-api) is the single segment-aware comparison used by every
consumer — a bare `startsWith` would merge `machine-learning-ops` into `machine-learning`.
Cluster membership is transitive and resolved at **query time**, so re-parenting never needs a
reindex. Hub selection breaks ties on lowest slug, because pages arrive from `listFiles()` in
unsorted order and last-writer-wins reported a different hub run to run. `cluster_status` carries
a `hub_declared` boolean alongside `hub_slug`, since Gson omits null keys and the reader must not
have to infer meaning from an absent field. Hub `hasPart` falls back to frontmatter `related`
whenever the structural index is unavailable, so SSR never blocks on a warming index.

### Phase 2 — Enforcement — **COMPLETE 2026-08-15**

The duplicate-declaration ERROR in `StructuralSpinePageFilter`, shipped dark
behind a property gate and flipped on only after Phase 0 verifies clean. The
undeclared-cluster WARNING routed to `/admin/drift`.

Shipped: the ERROR is gated by `wikantik.cluster_declaration.enforcement.enabled`,
**default false**. Enabling it against a corpus that still holds duplicates would make the
offending hub pages un-saveable — trapping exactly the content that needs editing to fix
them — so the flip is a deliberate act after a corpus verifies clean. Both corpora verified
clean in Phase 0, so the flip is now safe to make.

The incumbent hub can always re-save itself: a page is treated as the current declarant if
**either** its `canonical_id` or its slug matches, so neither an in-flight rename nor a
frontmatter-stripping save path can lock an author out of the page they need to edit.
Matching on the cluster alone would have made every hub in the wiki un-editable the moment
the gate was flipped.

The warning travels on the shared `ValidationCtx` (a new `clusterIsDeclared` predicate)
rather than being invented at the dashboard, so one signal feeds both the drift burn-down and
any future editor surface. With no structural index wired the predicate passes everything —
an index that has not warmed up must not flag the entire corpus.

### Phase 3 — Rendering — **COMPLETE 2026-08-15**

The four states above, gated on `page.permissions.edit`, client-side only.

Shipped: `ClusterStatus.jsx`, mounted in `PageMeta`. A reader who cannot edit sees exactly
what they saw before — the bare cluster chip, or nothing — so the anonymous render path is
byte-identical and ongoing SEO tuning stays unconfounded by this feature. Gating client-side
rather than in SSR also keeps the server response invariant per user, so edge caching is
untouched.

The component reads declaration from the `hub_declared` boolean rather than from the presence
of `hub_slug`, because the server omits null keys and an absent field would otherwise have to
be interpreted. A payload carrying **no** `cluster_status` at all (an older response, or a
client holding a pre-Phase-1 payload) falls back to the bare frontmatter chip rather than
inventing curation state that cannot be verified — a stale client must not report every
cluster as undeclared.

### Phase 4 — Curation tooling — **COMPLETE 2026-08-15**

`rename_cluster` — admin endpoint plus MCP tool, mirroring `rename_page` —
rewriting `cluster:` across every member in one operation. Without it, the path
form's O(pages) reorganization *is* the curation overhead this design exists to
minimize.

Shipped: `ClusterRenameService` (wikantik-main) beneath two surfaces —
`POST /admin/clusters/rename?from=&to=[&confirm=true]` and the `rename_cluster`
MCP tool on `/wikantik-admin-mcp` (taking admin-mcp to 27 tools). One subtree
query resolves the members, so a rename carries sub-clusters with it, and
`ClusterPath.reparent` keeps that segment-aware: renaming `machine-learning`
never drags `machine-learning-ops` along.

Unlike `rename_page`, an **unconfirmed call is not an error — it returns the
plan**. A bulk rewrite is precisely the operation whose blast radius a curator
should see before committing, and computing the plan writes nothing.

A target another hub already declares is refused outright (409 / MCP error
naming the incumbent), *before* any page is written. Catching it at plan time
rather than at the first failing save is what keeps a half-applied rename from
splitting the corpus across two cluster names. Past that gate the sweep never
aborts: a page that cannot be written is reported by name and the rest still
move, so the caller gets a precise retry list instead of an unknown partial
state.

The rename touches **frontmatter only** — no page names, no `canonical_id`s, no
`page_slug_history` rows, no URLs, no wikilink rewriting. Every member simply
gains one ordinary, revertable revision. That is the property a
directory-structured store could not have offered, and it is the concrete
payoff of the projection this design chose over hierarchy.

### Phase 5 — Multi-membership

Consolidation, not a new feature: 89 pages already assert it in an unvalidated
field.

- Non-hub pages may carry a **list** of `hub/qualifier` paths. Hubs remain
  scalar; a hub with a list is an ERROR.
- **First entry is primary** — it drives breadcrumbs, JSON-LD `articleSection`
  and `isPartOf`, and sidebar placement, so no tie-break field is needed.
- KG policy is **fail-closed across memberships**: most-specific policy wins per
  path, and any explicit EXCLUDE among a page's memberships wins overall. A
  second membership can never quietly pull a page into the Knowledge Graph.
- The 112 held entries from Phase 0 become second memberships.

## Permanently out of scope

Neither of these is deferred, phased, or a follow-up. They are not in the plan.

- **Cluster nesting deeper than one level.**
- **A filesystem-shaped export of the corpus, in any form.**

## Consequences

**Easier.** Reorganizing the taxonomy (one hub edit, or one `rename_cluster`
call). Merging near-duplicate clusters. Proving a cluster exists. Auditing
taxonomy health. Adding multi-membership. Answering "what is this wiki about"
from the structural index rather than prose.

**Harder.** Introducing a cluster casually — it now requires a hub page.
Renaming a hub page carries taxonomy weight it did not carry before.

**Unchanged.** Page identity, URLs, wikilinks, attachments, version history,
ACLs, retrieval behavior, and the anonymous render path.

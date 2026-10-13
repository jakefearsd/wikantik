---
audience:
- humans
- agents
type: runbook
summary: Which pages contribute entities to the Knowledge Graph by default, and how
  a page overrides inclusion with kg_include.
related:
- WikantikKnowledgeGraphAdmin
- StructuralSpineDesign
title: KG Inclusion Policy
cluster: wikantik-development
tags:
- kg-policy
- knowledge-graph
- administration
- runbook
date: '2026-04-27'
runbook:
  when_to_use:
  - When deciding whether a cluster of pages should contribute to the knowledge graph
  - when triaging "this content showed up in retrieval and shouldn't have"
  - when bootstrapping the policy on a fresh deployment
  inputs:
  - admin role on the wiki
  - access to bin/kg-policy.sh OR the /admin/kg-policy dashboard
  steps:
  - 'Check current state: open /admin/kg-policy or run bin/kg-policy.sh list'
  - Set a cluster policy on the dashboard (a CLI set writes the row but does not start
    reconciliation)
  - Wait for eager reconciliation to complete (status visible in dashboard)
  - Verify with the Explain tab (GET /admin/kg-policy/explain/{idOrName}); bin/kg-policy.sh
    explain <cluster> shows only the cluster's policy row
  pitfalls:
  - Default-exclude means new clusters are silently kept out of the KG until you act
    on them — check the pending-review queue weekly
  - Hard purges via 'kg-policy purge --confirm' delete kg_nodes/kg_edges rows for
    ALL pages excluded under cluster_policy, not just the named cluster; recovery
    requires re-extraction
  - 'Frontmatter kg_include: false beats cluster: include — useful for WIP, but easy
    to forget'
  related_tools:
  - kg-policy
  - kg-extract
  references:
  - WikantikKnowledgeGraphAdmin
  - StructuralSpineDesign
canonical_id: 01KQEDYJR57WYQCV645PKSDBMQ
status: active
---
# KG Inclusion Policy

The knowledge graph (KG) is built from a subset of wiki pages. This page is
the operator's guide to that subset: the model, the dashboard, the CLI, and
the workflows you'll use day-to-day.

## The decision model

For any page, the system evaluates four steps in order and stops at the first
one that applies:

1. **System page?** Sandbox, Main, navigation pages, etc. Always excluded.
2. **`kg_include: false` in frontmatter?** Excluded, regardless of cluster.
3. **`kg_include: true` in frontmatter?** Included, regardless of cluster.
4. **Cluster policy.** If the page's cluster has an `include` row in
   `kg_cluster_policy`, the page is included. Otherwise excluded.

The default is **exclude** — a cluster you haven't touched contributes nothing
to the KG. This is deliberate: imports of new content can't sneak into agent
retrieval before you've reviewed them.

### How the cluster lookup actually resolves

Two properties of step 4 matter in practice:

- **Ancestor walk (segment-aware).** A sub-cluster inherits its parent's policy:
  the lookup tries `a/b/c`, then `a/b`, then `a`, and the most specific row wins.
  This was previously an exact match, which silently excluded *every* sub-cluster
  from the KG because no row ever matched `parent/child`. Matching is segment-aware,
  never a string prefix — `machine-learning-ops` is not a descendant of
  `machine-learning`.
- **Fail-closed across memberships.** A non-hub page may declare several clusters
  (`cluster:` is scalar-or-list). If *any* membership resolves to an explicit
  EXCLUDE, the page is excluded outright. Inclusion is the one place cluster is
  authoritative rather than advisory — anything weaker would let a second
  membership quietly undo a curator's deliberate exclusion.

The page's cluster is read from frontmatter (`cluster:` — a single value, or a
list where the first entry is the primary); see
[StructuralSpineDesign](StructuralSpineDesign) and
[ClusterDeclarationDesign](ClusterDeclarationDesign). A page's own `kg_include`
override (steps 2 and 3) is checked first, so it beats an `exclude` on any of its
memberships.

## The dashboard

Visit `/admin/kg-policy`. The home view is a sortable table:

| Column | Meaning |
|--------|---------|
| Cluster | Cluster name as it appears in frontmatter |
| Pages | Total page count in this cluster |
| Action | `include` (green), `exclude` (gray), or `unset` (yellow) |
| Reason | Free-text reason captured when you set the policy |
| Set by | Principal who last changed the row |
| Last reviewed | Relative timestamp; >90 days renders in red |
| Actions | Edit / Clear buttons |

### Bootstrap (one-time)

The first time you visit the dashboard with no policy rows, it surfaces a
"Bootstrap" call-to-action. Visit `/admin/kg-policy/bootstrap` to run the
wizard: 27 clusters are pre-checked for `include`, 15 for `exclude`, based
on a tech / finance / lifestyle decomposition. Scan, uncheck what shouldn't
be there, fill a single shared reason ("bootstrap initial config"), confirm.
One transaction inserts all rows, and the eager reconciliation kicks off.

### Page lookup ("Why is this page in/out?")

`/admin/kg-policy/explain` takes a page title or canonical_id and prints the
four-step trace showing exactly why a page is included or excluded (the same
trace is `GET /admin/kg-policy/explain/{idOrName}`). Use this when:

- A page is showing up in retrieval and shouldn't be
- You're confirming that a frontmatter override took effect
- You want to know which cluster a page is associated with

### Pending-review queue

`/admin/kg-policy/pending` surfaces:

- **Unset clusters** — clusters in the corpus with no policy decision.
  Default-exclude is in effect; you should make a deliberate choice.
- **Stale reviews** — clusters whose `reviewed_at` is older than 90 days
  (or null). Possible drift since you last looked.
- **Recent page-count changes** — placeholder; the threshold logic will
  populate this once we capture cluster-size history.

## The CLI

Everything in the dashboard is also available via `bin/kg-policy.sh`.

```bash
bin/kg-policy.sh list                          # current policy state
bin/kg-policy.sh set java include --reason "core tech, agent retrieval"
bin/kg-policy.sh clear java                    # back to unset
bin/kg-policy.sh explain java                  # cluster's current policy + audit
bin/kg-policy.sh review                        # pending-review items
bin/kg-policy.sh mark-reviewed databases       # bump reviewed_at
bin/kg-policy.sh diff personal-finance         # excluded-pages snapshot
bin/kg-policy.sh reconcile                     # show excluded counts by reason
bin/kg-policy.sh audit --cluster java --limit 50
```

The CLI talks to PostgreSQL directly and has no handle on the running wiki, so
**`set` and `clear` write the policy row and the audit entry but do not start a
reconciliation run**, and `reconcile` is informational (it prints the
`kg_excluded_pages` counts by reason and never starts a job). Reconciliation
(`ReconciliationJobRunner`) is enqueued only when a policy is changed through
the REST API or the dashboard — nothing runs it at Tomcat startup. After a CLI
`set`, re-save the cluster from the dashboard (Edit, confirm) to enqueue the run.
Likewise `bin/kg-policy.sh explain <cluster>` shows only that cluster's policy
row; for the page-level trace use the Explain tab above.

`purge` is destructive — it hard-deletes `kg_nodes`, `kg_edges`, and
`chunk_entity_mentions` rows (and the `kg_excluded_pages` rows) for excluded
pages. Use only when you want storage back and won't be re-including the
cluster soon. **It is not cluster-scoped:** the CLI has no page-to-cluster
mapping, so `purge <cluster> --confirm` deletes the KG data for *every* page
currently excluded under the `cluster_policy` reason, whatever its cluster. The
dry run (no `--confirm`) lists the first 25 affected pages — read it.

```bash
bin/kg-policy.sh purge personal-finance              # dry-run, prints counts
bin/kg-policy.sh purge personal-finance --confirm    # actually delete
bin/kg-policy.sh purge --reason system_page --confirm  # all system-page exclusions
```

JDBC config is auto-discovered from `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`,
or override per-invocation with `--jdbc-url` / `--jdbc-user` / `--jdbc-password`.

## Common workflows

### "I just imported 50 new pages"

The structural index sees a new cluster (or a sudden 50% jump in an existing
one). The dashboard shows it in the pending-review queue. Decide include or
exclude with a one-line reason. Eager reconciliation runs immediately.

### "Why is this content showing up in retrieval?"

Use the Explain tab (or `GET /admin/kg-policy/explain/{idOrName}`); the CLI's
`explain` only reads a cluster row. One of two things shows up: the page's
cluster is included (the design intent), or the page has `kg_include: true` in
frontmatter. Adjust whichever is wrong.

### "I want to test what happens if I exclude `warehouse-automation`"

Toggle the cluster in the dashboard (a CLI `set` does not start reconciliation).
Eager reconciliation soft-excludes the pages — entities and edges remain in the
KG tables, just hidden from queries. Re-include the cluster and the rows
reappear, no LLM cost. If the experiment shows you don't want them, `kg-policy
purge --confirm` reclaims the storage, but it covers *all* cluster-policy
exclusions, not just this cluster (see the CLI section) — review the dry run
before confirming.

### "I'm shipping a new content type that shouldn't be in the KG yet"

Add `kg_include: false` to each page's frontmatter. The structural-spine
filter validates the value at save time. When the content is ready, remove
the override.

## Operations

- **Database:** `kg_cluster_policy`, `kg_policy_audit`, `kg_excluded_pages`
- **Master switch:** `wikantik.kg_policy.enabled` (default `true`; override it in
  `wikantik-custom.properties`). Setting `false` reverts to legacy behaviour (no
  policy filtering). The whole Knowledge Graph subsystem has its own switch,
  `wikantik.knowledge.enabled`.
- **REST surface (all under `/admin/kg-policy/`, Admin only):**
  `GET clusters`, `GET clusters/{cluster}`, `PUT`/`DELETE clusters/{cluster}`,
  `POST clusters/{cluster}/review`, `GET explain/{idOrName}`, `GET pending`,
  `GET audit`, `GET reconciliation`, `GET estimate`, `POST bootstrap`.
- **Audit log:** every change is recorded in `kg_policy_audit` with `actor`
  and timestamp. Append-only.
- **Permissions:** `/admin/kg-policy` and `bin/kg-policy.sh` both require
  admin role.
- **Reason precedence in `kg_excluded_pages`:** `system_page` >
  `page_override` > `cluster_policy`. The strongest applicable reason is
  recorded.

## Agent curation path

Curator agents should drive proposal triage through `/wikantik-admin-mcp` rather
than the REST surface:

- `list_proposals` — filtered listing with conflict flags
  (`node_exists`, `edge_previously_rejected`)
- `inspect_proposals` — bulk deep-dive (1..50 ids) with prior reviews
- `review_proposals` — bulk `approve | reject | judge` (1..50 ids; `reject`
  requires a top-level `reason`)
- `curate_edges` / `curate_nodes` — heterogeneous bulk ops (1..50 ops)

Bulk calls are capped at `wikantik.mcp.kg_curation.bulk_limit` ids/ops (default
50). The tool list and contracts are in `docs/admin/McpAgents.md`.

### Admin-bypass on read paths

Admin-context reads bypass the inclusion filter so curators see entities
they just created, even when the source page hasn't been admitted by the
cluster policy yet. The bypass applies to:

- REST `/admin/knowledge-graph/*` reads (already gated by `AdminAuthFilter`).
- The MCP tools registered on `/wikantik-admin-mcp` — `list_proposals`,
  `inspect_proposals`, `list_orphaned_kg_nodes`, and the admin-bypass copies
  of `query_nodes` and `search_knowledge` (29 tools on that server in total).

The agent-facing `/knowledge-mcp` server keeps the filter on, so retrieval
quality is unchanged.

## Further Reading

- [WikantikKnowledgeGraphAdmin](WikantikKnowledgeGraphAdmin) — the broader
  KG administration guide
- [StructuralSpineDesign](StructuralSpineDesign) — how clusters are tracked
- [Wikantik Development Hub](WikantikDevelopmentHub) — cluster index

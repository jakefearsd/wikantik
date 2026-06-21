---
date: '2026-04-27'
related:
- WikantikKnowledgeGraphAdmin
- StructuralSpineDesign
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
  - Set a cluster policy via the dashboard or CLI
  - Wait for eager reconciliation to complete (status visible in dashboard)
  - Verify with bin/kg-policy.sh explain <page-id>
  pitfalls:
  - Default-exclude means new clusters are silently kept out of the KG until you act
    on them — check the pending-review queue weekly
  - Hard purges via 'kg-policy purge --confirm' delete kg_nodes/kg_edges rows; recovery
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
hubs:
- WikantikDevelopmentHub
tags:
- kg-policy
- knowledge-graph
- administration
- runbook
type: runbook
status: active
summary: How to control which pages contribute to the knowledge graph — the cluster-primary
  policy model, the admin dashboard, the CLI, and the day-to-day operator workflows.
title: KG Inclusion Policy
cluster: wikantik-development
audience: humans
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

The page's cluster is read from frontmatter (`cluster: <name>`); see
[StructuralSpineDesign](StructuralSpineDesign).

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

`/admin/kg-policy/explain` takes a title or canonical_id and prints the
four-step trace showing exactly why a page is included or excluded. Use
this when:

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

Empty most days; non-empty triggers a 5-minute review session.

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

`reconcile` from the CLI is informational. Full reconciliation runs
automatically when you change policy via REST or the dashboard, or you can
restart Tomcat — both routes invoke `ReconciliationJobRunner`.

`purge` is destructive — it hard-deletes `kg_nodes`, `kg_edges`, and
`chunk_entity_mentions` rows for excluded pages. Use only when you want
storage back and won't be re-including the cluster soon.

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

Run `kg-policy explain <page-name>` (or use the Explain tab). One of two
things shows up: the page's cluster is included (the design intent), or the
page has `kg_include: true` in frontmatter. Adjust whichever is wrong.

### "I want to test what happens if I exclude `warehouse-automation`"

Toggle the cluster in the dashboard or run `kg-policy set warehouse-automation
exclude`. Eager reconciliation soft-excludes the pages — entities and edges
remain in the KG tables, just hidden from queries. Re-include with `set
warehouse-automation include` and the rows reappear, no LLM cost. If the
experiment shows you don't want them, run `kg-policy purge
warehouse-automation --confirm` to reclaim storage.

### "I'm shipping a new content type that shouldn't be in the KG yet"

Add `kg_include: false` to each page's frontmatter. The structural-spine
filter validates the value at save time. When the content is ready, remove
the override.

## Operations

- **Database:** `kg_cluster_policy`, `kg_policy_audit`, `kg_excluded_pages`
- **Master switch:** `wikantik.kg_policy.enabled` in `wikantik.properties`
  (default `true`). Setting `false` reverts to legacy behaviour (no policy
  filtering).
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

See `docs/superpowers/specs/2026-05-13-kg-curation-mcp-design.md` for the full
envelope and error contract.

### Admin-bypass on read paths

Admin-context reads bypass the inclusion filter so curators see entities
they just created, even when the source page hasn't been admitted by the
cluster policy yet. The bypass applies to:

- REST `/admin/knowledge-graph/*` reads (already gated by `AdminAuthFilter`).
- The MCP tools registered on `/wikantik-admin-mcp` — `list_proposals`,
  `inspect_proposals`, and the new admin-bypass copies of `query_nodes`
  and `search_knowledge` (24 tools total).

The agent-facing `/knowledge-mcp` server keeps the filter on, so retrieval
quality is unchanged. See
`docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md`
for the full contract.

## Further Reading

- [WikantikKnowledgeGraphAdmin](WikantikKnowledgeGraphAdmin) — the broader
  KG administration guide
- [StructuralSpineDesign](StructuralSpineDesign) — how clusters are tracked
- [Wikantik Development Hub](WikantikDevelopmentHub) — cluster index

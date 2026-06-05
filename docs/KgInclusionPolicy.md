# KG Inclusion Policy — Operator Reference

This document is the **operator reference** for the Knowledge Graph (KG) inclusion
policy: the configuration surface, REST endpoints, CLI flags, database tables, and
day-to-day runbook workflows. It complements the policy **conceptual explainer**
that lives in the wiki itself:

> **Policy explainer (for wiki readers / agents):**
> `docs/wikantik-pages/KgInclusionPolicy.md`

Read that page first for the decision model (system page → frontmatter override →
cluster policy) and the common operator workflows. This document adds the operator
details that belong outside the wiki: exact REST routes, property keys, table schemas,
and troubleshooting steps that are not appropriate for wiki content.

## Table of contents

1. [What the policy controls](#what-the-policy-controls)
2. [Configuration properties](#configuration-properties)
3. [Admin UI walkthrough](#admin-ui-walkthrough)
4. [REST endpoint reference](#rest-endpoint-reference)
5. [CLI reference (`bin/kg-policy.sh`)](#cli-reference)
6. [Database tables](#database-tables)
7. [Auth model](#auth-model)
8. [Reconciliation](#reconciliation)
9. [Troubleshooting](#troubleshooting)
10. [Cross-links](#cross-links)

---

## What the policy controls

The KG inclusion policy decides which pages are eligible for entity extraction and
Knowledge Graph indexing. Pages not admitted by the policy are recorded in
`kg_excluded_pages` and skipped by the extraction pipeline.

**Decision order** (first match wins):

1. **System page?** Always excluded.
2. **`kg_include: false` in frontmatter?** Excluded, regardless of cluster.
3. **`kg_include: true` in frontmatter?** Included, regardless of cluster.
4. **Cluster policy.** If the page's `cluster:` frontmatter maps to an `include` row
   in `kg_cluster_policy`, the page is included. Otherwise excluded (default-exclude).

**Exclusion reason precedence** recorded in `kg_excluded_pages`:
`system_page` > `page_override` > `cluster_policy`.

## Configuration properties

All properties go in `wikantik-custom.properties`
(`tomcat/tomcat-11/lib/wikantik-custom.properties`).

| Property | Default | Effect |
|----------|---------|--------|
| `wikantik.kg_policy.enabled` | `true` | Master switch. `false` disables policy filtering entirely, reverting to legacy behaviour (no cluster filter). |
| `wikantik.kg_policy.reconciliation.eager` | `true` | When `true`, changing a cluster policy via REST or the dashboard triggers an immediate reconciliation run. |
| `wikantik.kg_policy.review.staleness_days` | `90` | Number of days after which a cluster's `reviewed_at` is considered stale. Stale clusters appear in the pending-review queue. |
| `wikantik.kg_policy.review.page_count_change_pct` | `20` | (Placeholder — threshold logic not yet implemented.) |
| `wikantik.kg_policy.bootstrap.include` | 27 cluster names (see below) | Comma-separated list of clusters pre-checked for `include` in the bootstrap wizard. |
| `wikantik.kg_policy.bootstrap.exclude` | 15 cluster names (see below) | Comma-separated list of clusters pre-checked for `exclude` in the bootstrap wizard. |

Default bootstrap include clusters (from `wikantik.properties`):
`wikantik-development`, `agentic-ai`, `generative-ai`, `machine-learning`,
`devops-sre`, `databases`, `software-engineering-practices`, `mathematics`,
`security`, `distributed-systems`, `software-architecture`, `cloud-platforms`,
`frontend-development`, `java`, `warehouse-automation`, `data-engineering`,
`design-patterns`, `agent-cookbook`, `operations-research`, `web-services-and-apis`,
`data-structures`, `mechanical-engineering`, `networking`,
`computer-science-foundations`, `retirement-planning`, `index-fund-investing`,
`personal-finance`.

Default bootstrap exclude clusters: `engineering-leadership`,
`linux-for-windows-users`, `conflicts-equity-markets`, `van-life`,
`hobby-woodworking`, `philosophy`, `cooking-and-food`, `emergency-prep`,
`berlin-history`, `immigration`, `spousal-green-card`, `remote-host-management`,
`russia-ukraine-war`, `hobbies`, `american-coinage`.

## Admin UI walkthrough

### Main dashboard — `/admin/kg-policy`

Displays a sortable, filterable table of all clusters known to the structural index,
merged with any `kg_cluster_policy` rows. Columns:

| Column | Source |
|--------|--------|
| Cluster | `cluster:` frontmatter value, read from structural index |
| Pages | `articleCount` from structural index |
| Action | `kg_cluster_policy.action` (`include`/`exclude`) or `unset` (yellow) when no row exists |
| Reason | `kg_cluster_policy.reason` |
| Set by | `kg_cluster_policy.set_by` |
| Last reviewed | `kg_cluster_policy.reviewed_at`; >90 days renders in red |
| Actions | Edit / Clear buttons |

**Toolbar filters:** All / Include / Exclude / Unset, plus a cluster-name search box.

**Edit flow:** Clicking Edit opens a modal. You enter the new action and reason. The
UI first calls `GET /admin/kg-policy/estimate` to show a page-count preview. After
reviewing the estimate, confirming calls `PUT /admin/kg-policy/clusters/{cluster}`.
Eager reconciliation starts immediately if `wikantik.kg_policy.reconciliation.eager`
is `true`.

**Clear:** Removes the row from `kg_cluster_policy`, reverting the cluster to
default-exclude. Triggers a confirmation prompt before calling
`DELETE /admin/kg-policy/clusters/{cluster}`.

**Reconciliation panel:** While any cluster is in `RUNNING` or `QUEUED` state, a live
status panel appears and refreshes every 5 seconds.

**Bootstrap call-to-action:** When all clusters are `unset` (no `kg_cluster_policy`
rows at all), a banner links to `/admin/kg-policy/bootstrap`.

### Bootstrap wizard — `/admin/kg-policy/bootstrap`

One-time setup wizard. Pre-checks clusters based on
`wikantik.kg_policy.bootstrap.include` / `wikantik.kg_policy.bootstrap.exclude`.
You scan the lists, adjust checkboxes, enter a shared reason string, and confirm. One
`POST /admin/kg-policy/bootstrap` transaction inserts all rows atomically, then eager
reconciliation runs.

This endpoint is idempotent at the API level but the UI shows a 409 if any cluster
policy already exists — use the dashboard to edit individual clusters in that case.

### Explain — `/admin/kg-policy/explain`

Enter a page title or `canonical_id`. Returns the four-step policy trace. Internally
calls `GET /admin/kg-policy/explain/{idOrName}`.

### Pending-review queue — `/admin/kg-policy/pending`

Surfaces two categories:

- **Unset clusters** — clusters with no policy row; default-exclude is in effect.
- **Stale reviews** — clusters whose `reviewed_at` is older than
  `wikantik.kg_policy.review.staleness_days` (default 90) or is null.

The third category (recent page-count changes) is a placeholder and always returns
an empty array.

## REST endpoint reference

All endpoints are under `/admin/kg-policy/*` and require admin role (protected by
`AdminAuthFilter`). JDBC config not required for REST — the running Tomcat instance
provides the `DataSource`.

### Read endpoints (`GET`)

| Path | Query params | Description |
|------|-------------|-------------|
| `GET /admin/kg-policy/clusters` | — | List all clusters with policy state. |
| `GET /admin/kg-policy/clusters/{cluster}` | — | Detail + audit trail (last 50 entries) for one cluster. |
| `GET /admin/kg-policy/explain/{idOrName}` | — | Four-step policy trace for a page (title or canonical_id). 404 if not found. |
| `GET /admin/kg-policy/pending` | — | Unset clusters + stale-review clusters. |
| `GET /admin/kg-policy/audit` | `cluster` (optional), `limit` (default 100) | Append-only audit log from `kg_policy_audit`. |
| `GET /admin/kg-policy/reconciliation` | — | Current `ReconciliationStatus` per cluster. |
| `GET /admin/kg-policy/estimate` | `cluster`, `action` (`include`\|`exclude`) | Page-count preview for a proposed change. Entity/edge/LLM-cost estimates are deferred. |

**Explain response shape:**

```json
{
  "canonical_id": "01KQEDYJR57WYQCV645PKSDBMQ",
  "page_name": "KgInclusionPolicy",
  "cluster": "wikantik-development",
  "system_page": false,
  "frontmatter_override": null,
  "cluster_policy": "include",
  "effective_action": "include",
  "exclusion_reason": null
}
```

**Reconciliation state values:** `QUEUED`, `RUNNING`, `DONE`, `ERROR`.

### Write endpoints

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `PUT` | `/admin/kg-policy/clusters/{cluster}` | `{ "action": "include"\|"exclude", "reason": "..." }` | Set or update a cluster policy. Triggers eager reconciliation. |
| `DELETE` | `/admin/kg-policy/clusters/{cluster}` | — | Clear policy (revert to default-exclude). |
| `POST` | `/admin/kg-policy/clusters/{cluster}/review` | — | Bump `reviewed_at` without changing the action. |
| `POST` | `/admin/kg-policy/bootstrap` | `{ "include": [...], "exclude": [...], "reason": "..." }` | Seed initial policy in one transaction. 409 if any cluster already has a policy. |

## CLI reference

`bin/kg-policy.sh` is a thin shell wrapper around `wikantik-extract-cli.jar`.
It rebuilds the jar automatically when source files are newer than the existing jar.

**JDBC discovery order:**

1. `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` (preferred — uses live
   deployment credentials).
2. `PG_JDBC_URL` / `PG_USER` / `PG_PASSWORD` environment variables.
3. Per-invocation overrides: `--jdbc-url`, `--jdbc-user`, `--jdbc-password-env`.

**All subcommands:**

```bash
# List current policy for all clusters
bin/kg-policy.sh list

# Set a cluster policy
bin/kg-policy.sh set <cluster> include|exclude --reason "reason text"

# Clear policy (revert to default-exclude)
bin/kg-policy.sh clear <cluster>

# Explain inclusion decision for a page
bin/kg-policy.sh explain <cluster-name-or-page-id>

# Show pending-review items
bin/kg-policy.sh review

# Mark a cluster as reviewed (bumps reviewed_at)
bin/kg-policy.sh mark-reviewed <cluster>

# Show excluded-page snapshot for a cluster
bin/kg-policy.sh diff <cluster>

# Show excluded counts by reason (informational; does NOT trigger reconciliation)
bin/kg-policy.sh reconcile

# Audit log for a cluster
bin/kg-policy.sh audit --cluster <cluster> --limit 50

# Dry-run purge (prints row counts that would be deleted)
bin/kg-policy.sh purge <cluster>

# DESTRUCTIVE: hard-delete kg_nodes, kg_edges, chunk_entity_mentions for excluded pages
bin/kg-policy.sh purge <cluster> --confirm

# Purge all pages excluded by a specific reason
bin/kg-policy.sh purge --reason system_page --confirm
```

**Important:** `purge --confirm` permanently deletes KG data. Recovery requires
re-running entity extraction. Use only when you want storage back and are certain
you will not re-include the cluster.

## Database tables

Schemas are idempotent (added in V018):

**`kg_cluster_policy`** — one row per cluster with an explicit policy decision:

```
cluster      TEXT PRIMARY KEY
action       TEXT NOT NULL CHECK (action IN ('include','exclude'))
reason       TEXT
set_by       TEXT NOT NULL
set_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
reviewed_at  TIMESTAMPTZ
```

**`kg_policy_audit`** — append-only; never updated, never deleted:

```
id          BIGSERIAL PRIMARY KEY
cluster     TEXT NOT NULL
old_action  TEXT
new_action  TEXT NOT NULL
reason      TEXT
actor       TEXT NOT NULL
changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

**`kg_excluded_pages`** — soft-delete snapshot maintained by reconciliation:

```
page_name   TEXT PRIMARY KEY
reason      TEXT NOT NULL CHECK (reason IN ('system_page','cluster_policy','page_override'))
excluded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

Reason precedence: `system_page` > `page_override` > `cluster_policy`.

## Auth model

- **Dashboard and REST endpoints** — `AdminAuthFilter` (requires `AllPermission` /
  admin role). The acting admin's login is captured from `HttpServletRequest.getRemoteUser()`
  and written to `set_by` / `actor` on every mutation.
- **CLI** — connects directly to PostgreSQL; requires database credentials. The
  `set_by` / `actor` value is the OS user running the script (or the value passed via
  `--actor` if supported by the CLI jar — verify with `bin/kg-policy.sh --jar-help`).
- **`/knowledge-mcp` agent tools** — the knowledge MCP server keeps the inclusion
  filter active; retrieval tools only surface pages admitted by the policy.
- **`/wikantik-admin-mcp` tools** — admin-bypass reads (`query_nodes`,
  `search_knowledge` mirrors) skip the policy filter so curators see freshly created
  entities before their source page is admitted.

## Reconciliation

Reconciliation is the process that refreshes `kg_excluded_pages` to match the current
policy state and gates the extraction pipeline accordingly.

- **Eager reconciliation** (default) — runs automatically after every `PUT` or
  `DELETE` on a cluster policy via REST or the dashboard.
- **On restart** — reconciliation also runs when Tomcat starts and initialises the
  Knowledge Graph subsystem.
- **CLI** — `bin/kg-policy.sh reconcile` is informational (prints counts) but does
  not itself trigger a job; restart Tomcat or change a policy via REST to force a run.
- **Progress** — monitor via `GET /admin/kg-policy/reconciliation` or the live panel
  in the admin dashboard.

Soft-exclude: toggling a cluster to `exclude` hides its pages from KG queries but
does not delete `kg_nodes` / `kg_edges` rows. Re-including the cluster makes them
reappear immediately with no LLM cost. Use `purge --confirm` to reclaim storage when
you are certain.

## Troubleshooting

**Dashboard shows 503**

The KG policy components are unavailable — likely because the Knowledge Graph
subsystem failed to initialise. Check `catalina.out` for errors during startup,
and verify that the `wikantik.datasource` JNDI resource is configured in
`ROOT.xml`.

**Cluster shows as `unset` even after `set` CLI call**

The CLI writes directly to PostgreSQL; the running Tomcat holds an in-process cache.
Trigger a refresh by calling `PUT /admin/kg-policy/clusters/{cluster}` once via
the dashboard, or restart Tomcat.

**Bootstrap 409**

At least one cluster already has a policy row. Use the main dashboard to edit
individual clusters. The bootstrap wizard is only for fresh deployments with no
policy rows at all.

**Pages still appearing in retrieval after excluding a cluster**

The exclusion applies to new extraction runs and KG queries, but existing `kg_nodes`
/ `kg_edges` rows are not deleted by soft-exclude. If the KG rerank step is enabled,
entities from excluded pages may still boost results through the graph proximity
scorer. Run `purge --confirm` to hard-delete the KG data for the cluster.

**`explain` returns 404**

The page title or canonical_id is not known to the structural index. Check that the
page exists, has a `canonical_id:` frontmatter field, and that the index has been
populated (wait ~20 seconds after startup with the custom-jdbc provider).

## Cross-links

- [docs/wikantik-pages/KgInclusionPolicy.md](wikantik-pages/KgInclusionPolicy.md) — conceptual explainer, common workflows, and the agent curation path.
- [docs/KnowledgeGraphRerank.md](KnowledgeGraphRerank.md) — how the policy interacts with the graph rerank step.
- [docs/RetrievalQuality.md](RetrievalQuality.md) — measuring whether policy decisions affect retrieval scores.
- [docs/AuditLog.md](AuditLog.md) — tamper-evident admin audit log (separate from `kg_policy_audit`).
- `bin/db/migrations/V018__kg_inclusion_policy.sql` — DDL.

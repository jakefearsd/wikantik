# KG Inclusion / Exclusion Policy

A cluster-primary, page-overridable, admin-administered policy layer for which pages contribute to the knowledge graph (KG). Search remains governed by the existing `SystemPageRegistry.isSystemPage()` predicate; this design adds an orthogonal axis for KG membership and gives the admin first-class tooling to drive it.

The corpus reached 100% cluster coverage as of commit `27560b558` (940 pages, 42 clusters). With cluster as the dominant grouping, cluster-level policy is now the natural lever; per-page overrides handle the small set of cases where the cluster default is wrong.

## Goals

1. **Default-exclude.** New clusters do not contribute to the KG until the admin opts them in. Imports of fresh content can't sneak into agent retrieval.
2. **Cluster as the primary lever.** Admin makes ~42 decisions, not ~940. Per-page overrides exist but are rare.
3. **Frontmatter override always wins.** A page with `kg_include: false` is excluded even if its cluster is included. Useful for WIP, sensitive, or otherwise inappropriate content.
4. **Eager reconciliation.** When the admin changes a cluster's policy, the KG converges immediately via a background job. Admin sees progress.
5. **Soft delete; hard purge is explicit.** Excluded entities/edges are flagged but retained. Re-include = unflip flag, no LLM cost. Hard purge is a deliberate CLI command per cluster.
6. **System-page-in-KG fix.** Filter system pages (the existing `isSystemPage()` predicate) out of the KG extraction path, where they are currently leaking.
7. **Empowering admin tooling.** A dashboard showing per-cluster state and drift; a page-level explainer; a pending-review queue; CLI parity for scripting and emergency operations.

## Non-goals

- **Per-page-cluster reassignment from this UI.** Cluster is set in frontmatter; this UI does not edit page content.
- **Search-index changes.** Search continues to include all non-system pages. Excluding from the KG does not exclude from BM25 or dense retrieval.
- **Per-tag or per-author rules.** Cluster + page-override is sufficient for the foreseeable cases.
- **Time-bounded inclusion (e.g. "include until 2026-12-31").** Out of scope; if needed later, add a `valid_until` column to `kg_cluster_policy`.
- **Auto-classification of new clusters.** When a new cluster appears, it goes into the pending-review queue with default-exclude. The admin chooses.

## Decision algorithm

For any page P, `effective_kg_action(P)` returns one of `INCLUDE`, `EXCLUDE` and is computed:

```
1. If isSystemPage(P.name)             → EXCLUDE  (system pages never in KG)
2. If P.frontmatter.kg_include == false → EXCLUDE
3. If P.frontmatter.kg_include == true  → INCLUDE
4. Look up policy(P.cluster):
     action == 'include' → INCLUDE
     action == 'exclude' → EXCLUDE
     not set            → EXCLUDE  (system default)
```

Three valid frontmatter states for `kg_include`:

- absent (the common case) — defer to cluster
- `true` — force-include
- `false` — force-exclude

Any other value (e.g. `kg_include: "maybe"`) is a save-time validation error rejected by the structural-spine filter.

## Storage

```sql
-- Per-cluster policy. One row per cluster the admin has touched.
CREATE TABLE kg_cluster_policy (
  cluster      TEXT PRIMARY KEY,
  action       TEXT NOT NULL CHECK (action IN ('include','exclude')),
  reason       TEXT,
  set_by       TEXT NOT NULL,
  set_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reviewed_at  TIMESTAMPTZ
);

-- Append-only audit log. Every change recorded.
CREATE TABLE kg_policy_audit (
  id          BIGSERIAL PRIMARY KEY,
  cluster     TEXT NOT NULL,
  old_action  TEXT,                        -- NULL on first set
  new_action  TEXT NOT NULL,               -- include | exclude | cleared | purged
  reason      TEXT,
  actor       TEXT NOT NULL,
  changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Soft-delete via a single "excluded pages" table. Read paths LEFT JOIN
-- against this and filter `WHERE excluded.page_name IS NULL`. One place
-- to update on policy change; cleanly catches kg_nodes.source_page,
-- kg_edges (via either endpoint's source page), and chunk_entity_mentions
-- (via kg_content_chunks.page_name).
CREATE TABLE kg_excluded_pages (
  page_name   TEXT PRIMARY KEY,
  reason      TEXT NOT NULL CHECK (reason IN
                ('system_page','cluster_policy','page_override')),
  excluded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kg_excluded_pages_reason ON kg_excluded_pages (reason);
```

Rows in `kg_cluster_policy` are absent for clusters the admin hasn't touched — those default to exclude per §5 and surface in the pending-review queue.

The single `kg_excluded_pages` table is the soft-delete mechanism. Three reasons distinguish *why* a page is excluded so the admin can cleanly purge by reason (e.g. `wikantik kg-policy purge --reason system_page`). When a page becomes excluded, one row is inserted; when it becomes included again, the row is deleted. The KG node/edge/mention tables are untouched until an explicit hard purge.

Page-level overrides live in frontmatter only — no separate DB table. The structural-spine index already parses frontmatter at save-time, so reading `kg_include` is a one-line addition there.

A migration `V0NN__kg_inclusion_policy.sql` lives under `bin/db/migrations/`. Idempotent DDL only (`CREATE TABLE IF NOT EXISTS`).

## Hookpoints

### KG extraction pipeline

Two extraction entry points need the policy filter plus the system-page filter:

- `BootstrapEntityExtractionIndexer` (`wikantik-main/.../knowledge/extraction/`) — bulk rebuild
- `AsyncEntityExtractionListener` (same package, save-time path)

Both gain an early-out:

```java
if (systemPageRegistry.isSystemPage(page.getName())
    || !kgPolicy.shouldInclude(page)) {
    return;  // skipped, not extracted
}
```

`KgInclusionPolicy.shouldInclude(WikiPage)` implements the four-step algorithm above. It reads:

- `SystemPageRegistry` (already injected via `WikiEngine`)
- frontmatter `kg_include` (from the structural-spine cache)
- cluster policy (from a small in-memory cache backed by `kg_cluster_policy`, invalidated on policy write)

The system-page check is duplicated outside the policy object so an operator can see "skipped because system page" distinctly from "skipped because policy" in observability.

### Save-time structural-spine filter

`StructuralSpinePageFilter.preSave()` already validates frontmatter. It picks up `kg_include` parsing and rejects malformed values. No behaviour change beyond validation.

When a saved page's effective KG action changes vs. its previous version (override flipped, cluster changed, etc.), the filter enqueues a reconciliation job for that page.

### Cluster-policy change → reconciliation

When the admin flips a cluster's policy:

1. The change is written to `kg_cluster_policy` and `kg_policy_audit` in a single transaction.
2. The in-memory cache is invalidated.
3. A reconciliation job is enqueued with the affected cluster.
4. The job iterates pages in that cluster and for each:
   - Computes new `effective_kg_action`
   - If now-excluded: `INSERT INTO kg_excluded_pages (page_name, reason) VALUES (?, 'cluster_policy') ON CONFLICT DO UPDATE SET reason=...` (idempotent; an existing row from `system_page` reason is preserved as the strongest reason — see §"Reason precedence" below)
   - If now-included: `DELETE FROM kg_excluded_pages WHERE page_name = ? AND reason = 'cluster_policy'`. If the page was never extracted, the extractor is also enqueued.
5. Progress is published to a per-cluster channel; the admin UI polls (or subscribes via SSE) to render a progress bar.

The job is implemented in `wikantik-knowledge` next to the existing extraction services. Only one reconciliation job per cluster runs at a time — concurrent toggles queue.

#### Reason precedence (when multiple apply)

A page can be excluded for more than one reason simultaneously (e.g. it's both a system page AND in an excluded cluster). Only one row exists per `page_name`, with reason set to the *strongest* applicable reason in this order: `system_page` > `page_override` > `cluster_policy`. When a reason is removed (e.g. cluster flips to include), the row is downgraded to the next-strongest still-applicable reason, or deleted if none apply. This keeps the table small and makes "why is this excluded?" a single lookup.

### Read path

KG-reading code paths add `LEFT JOIN kg_excluded_pages e ON ... WHERE e.page_name IS NULL` to filter excluded pages. Three distinct join keys per affected query type:

- `kg_nodes` queries: `LEFT JOIN kg_excluded_pages ON kg_nodes.source_page = kg_excluded_pages.page_name`
- `kg_edges` queries: join twice (source endpoint and target endpoint), filter where neither endpoint's source_page is excluded
- `chunk_entity_mentions` queries: join through `kg_content_chunks.page_name`

A single `KgInclusionFilter` query helper provides the join clauses so each call site stays terse and the predicate is enforced consistently. The affected services are the knowledge MCP server and the graph-traversal endpoints; the helper is added there.

## Admin surface

Mounted at `/admin/kg-policy` behind `AdminAuthFilter` (requires `AllPermission`). The admin UI is a React component in `wikantik-frontend` consistent with the existing admin panels (`/admin/security`, `/admin/structural-conflicts`, `/admin/verification`, `/admin/retrieval-quality`).

### View A — Cluster dashboard (the home view)

Sortable, filterable table. One row per cluster present in the corpus.

| Cluster                        | Pages | Action                        | Page-level overrides | Last reviewed | Reason             |          |
| ------------------------------ | ----- | ----------------------------- | -------------------- | ------------- | ------------------ | -------- |
| wikantik-development           | 82    | include                       | 0                    | 2026-04-27    | bootstrap          | [edit]   |
| van-life                       | 32    | exclude                       | 0                    | 2026-04-27    | personal/lifestyle | [edit]   |
| *(new cluster)* climbing-trips | 6     | **unset** *(default exclude)* | 0                    | —             | —                  | [decide] |

Columns:

- **Cluster** — name, sortable
- **Pages** — count, including pages with overrides
- **Action** — `include` (green) / `exclude` (gray) / `unset` (yellow, highlighted)
- **Page-level overrides** — count of pages in this cluster with explicit `kg_include`. Click drills into a list.
- **Last reviewed** — `reviewed_at` timestamp; older than 90 days gets a soft "stale" indicator
- **Reason** — free-text, displayed inline truncated
- Inline `[edit]` opens a modal with Action toggle, Reason textarea, Confirm button.

A toolbar above the table:

- Filter by action (include / exclude / unset)
- Search by cluster name
- "Mark all reviewed" — bumps `reviewed_at` to NOW for all selected rows without changing action
- "Reconciliation status" — small panel showing any active background jobs

### View B — Page lookup ("Why is this page in / out?")

A search box. Type a title or canonical_id. Results show:

```
Page: AdapterPattern (01KQ0P44H09BE8GQQA8RJEZ5V3)
  Cluster:           design-patterns
  Cluster policy:    include (set 2026-04-27 by admin, "bootstrap")
  System page:       no
  Frontmatter override: (none)
  Effective action:  INCLUDE
  KG state:          5 entities, 12 edges (last extracted 2026-04-25)

  [Open page] [Force re-extract] [Set page-override (advanced)]
```

For diagnosing a specific symptom ("this content is showing up in retrieval and shouldn't be"), this is the entry point. The "set page-override" option opens a guarded modal that explains the override modifies frontmatter directly via the page-save flow.

### View C — Pending review queue

Auto-populated. Surfaces:

- **Unset clusters** — clusters present in the corpus with no row in `kg_cluster_policy`. The default-exclude is in effect; admin should make a decision.
- **Significant page-count change** — clusters whose page count changed by ≥20% since `reviewed_at`. Cluster could now mean something different.
- **Stale page overrides** — pages with `kg_include` set in frontmatter, where the override is older than 90 days (heuristic: file mtime). Possible bit-rot of "WIP" pages that shipped.
- **Failed reconciliations** — any reconciliation job that failed or ended with errors per page.

Each item has "review" / "dismiss until next change" buttons.

Default thresholds (90-day staleness, 20% page-count change) are constants in the `KgPolicyReviewService`; later configurable if needed.

### Bootstrap wizard (one-time)

First time `/admin/kg-policy` loads, if `kg_cluster_policy` is empty, render a wizard instead of the dashboard. The wizard shows all 42 clusters pre-checked according to the bootstrap mapping below; admin scans, unchecks any that shouldn't be, fills a single shared reason ("bootstrap initial config"), confirms. One transaction inserts all rows.

**Bootstrap mapping (27 include / 15 exclude):**

| Default     | Cluster                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **include** | wikantik-development, agentic-ai, generative-ai, machine-learning, devops-sre, databases, software-engineering-practices, mathematics, security, distributed-systems, software-architecture, cloud-platforms, frontend-development, java, warehouse-automation, data-engineering, design-patterns, agent-cookbook, operations-research, web-services-and-apis, data-structures, mechanical-engineering, networking, computer-science-foundations, retirement-planning, index-fund-investing, personal-finance |
| **exclude** | engineering-leadership, linux-for-windows-users, conflicts-equity-markets, van-life, hobby-woodworking, philosophy, cooking-and-food, emergency-prep, berlin-history, immigration, spousal-green-card, remote-host-management, russia-ukraine-war, hobbies, american-coinage                                                                                                                                                                                                                                  |

`reviewed_at` is set to NOW for all bootstrap rows so the staleness clock starts fresh.

### Dry-run preview on toggle

Before any single-cluster toggle commits, a confirmation modal shows:

```
Toggling 'personal-finance' from EXCLUDE → INCLUDE will:
  + Add 25 pages to the KG (out of 25 in cluster, 0 with kg_include:false)
  ~ Estimated entities to extract: ~150
  ~ Estimated edges:               ~600
  ~ LLM cost (current model):      ~$0.40
  ⏱  Estimated reconciliation time: 3-4 minutes

  [Cancel] [Confirm and reconcile]
```

The estimates come from a small `KgPolicyEstimator` that uses observed extraction throughput from previous bootstrap runs (`retrieval_runs` and `extraction_runs` tables expose the relevant stats). Numbers are rough and labelled as such.

### REST endpoints

```
GET    /admin/kg-policy/clusters                     # dashboard data
GET    /admin/kg-policy/clusters/{cluster}           # detail incl. recent audits
PUT    /admin/kg-policy/clusters/{cluster}           # set/change action
DELETE /admin/kg-policy/clusters/{cluster}           # clear policy (back to unset)
POST   /admin/kg-policy/clusters/{cluster}/review    # bump reviewed_at only
GET    /admin/kg-policy/explain/{canonical_id}       # view B data
GET    /admin/kg-policy/pending                      # view C data
GET    /admin/kg-policy/audit?cluster=...&limit=N    # paginated history
GET    /admin/kg-policy/reconciliation               # active job statuses
POST   /admin/kg-policy/bootstrap                    # one-time wizard commit
GET    /admin/kg-policy/estimate?cluster=...&action=include   # dry-run preview
```

All endpoints check `AllPermission` via `AdminAuthFilter`. All write endpoints accept `reason` in the body and stamp `set_by` from the authenticated principal.

## CLI parity

```
wikantik kg-policy list [--filter <include|exclude|unset>]
wikantik kg-policy set <cluster> <include|exclude> --reason "..."
wikantik kg-policy clear <cluster>
wikantik kg-policy explain <canonical_id|page-name>
wikantik kg-policy review                     # show pending-review items
wikantik kg-policy mark-reviewed <cluster>...
wikantik kg-policy diff <cluster>             # what would change if we re-evaluated this cluster now
wikantik kg-policy estimate <cluster> <include|exclude>
wikantik kg-policy reconcile [<cluster>...]   # force-reconcile (idempotent)
wikantik kg-policy purge <cluster> --confirm           # HARD-delete excluded data for cluster
wikantik kg-policy purge --reason system_page --confirm   # HARD-delete by exclusion reason
wikantik kg-policy audit [--cluster X] [--since 7d] [--limit 50]
```

Implemented as a `KgPolicyCli` main class in `wikantik-extract-cli`, alongside the existing `KgExtractCli` / `KgRebuildCli` classes. A bash launcher `bin/kg-policy` mirrors the `bin/kg-extract` and `bin/kg-rebuild` pattern (build the CLI jar, exec `java -cp` against it).

`purge` is the only destructive command. It requires `--confirm`; without it, prints exactly what would be deleted (counts of `kg_nodes`, `kg_edges`, `chunk_entity_mentions` rows) and exits 1. With it, performs:

```sql
-- per-cluster purge; system_page-reason purge is identical with a different filter
DELETE FROM chunk_entity_mentions
  WHERE chunk_id IN (
    SELECT c.id FROM kg_content_chunks c
    JOIN kg_excluded_pages e ON c.page_name = e.page_name
    WHERE e.page_name IN (<pages-in-cluster>)
  );
DELETE FROM kg_edges
  WHERE source_id IN (SELECT id FROM kg_nodes WHERE source_page IN (...))
     OR target_id IN (SELECT id FROM kg_nodes WHERE source_page IN (...));
DELETE FROM kg_nodes
  WHERE source_page IN (<pages-in-cluster-and-excluded>);
DELETE FROM kg_excluded_pages
  WHERE page_name IN (<purged>);
```

Wrapped in a transaction. The audit log records the purge as `new_action = 'purged'` with row counts in `reason`.

## System-page-in-KG fix

Audit (`grep -rEn 'isSystemPage' wikantik-main/.../knowledge/extraction/`) confirms `BootstrapEntityExtractionIndexer` and `AsyncEntityExtractionListener` do not currently call `SystemPageRegistry.isSystemPage(name)`. System pages have been eligible for KG extraction since the pipeline shipped.

The fix is the same hookpoint as the KG extraction pipeline change above: each extraction entry point gains an early-out using `KgInclusionPolicy.shouldInclude(page)`, which itself includes the system-page check as step 1 of its algorithm.

A one-shot backfill runs at first deploy: `INSERT INTO kg_excluded_pages (page_name, reason) SELECT name, 'system_page' FROM pages WHERE isSystemPage(name)`. (Phrased here as pseudo-SQL — actually executed as a small migration script that calls into `SystemPageRegistry` since `isSystemPage` is Java logic, not a SQL function.) Operator can later run `wikantik kg-policy purge --reason system_page --confirm` for hard removal.

This is in scope because (a) the same hookpoint addition works for both system-page filtering and cluster policy filtering, (b) leaving system pages in the KG while adding admin-driven exclusion would be incoherent.

## Configuration knobs

```properties
# Master switch for the policy layer. Default true.
wikantik.kg_policy.enabled = true

# Reconciliation behavior.
wikantik.kg_policy.reconciliation.eager = true        # eager (per design); set false for lazy
wikantik.kg_policy.reconciliation.parallelism = 1     # how many clusters reconcile in parallel

# Pending-review thresholds.
wikantik.kg_policy.review.staleness_days = 90
wikantik.kg_policy.review.page_count_change_pct = 20

# Bootstrap defaults — comma-separated cluster lists. Used only for the wizard's
# initial pre-checked state; subsequent changes have no effect. Full lists in
# the §"Bootstrap mapping" table above.
wikantik.kg_policy.bootstrap.include = <27 tech + finance clusters>
wikantik.kg_policy.bootstrap.exclude = <15 lifestyle + soft-tech clusters>
```

Defaults match the design above. The bootstrap properties are read once on first wizard load; subsequent changes to them have no effect.

## Observability

New Prometheus metrics:

- `wikantik_kg_policy_evaluations_total{result="include|exclude|skipped_system|skipped_override"}` — counter on every `effective_kg_action()` call
- `wikantik_kg_reconciliation_pages_total{cluster, outcome}` — counter on each page reconciled, outcome ∈ {included, excluded, error}
- `wikantik_kg_reconciliation_duration_seconds{cluster}` — histogram per reconciliation run
- `wikantik_kg_policy_pending_review_items` — gauge for pending-review queue depth

The existing structured-logging path emits a per-page log line (`event=kg_extract_skip`) when a page is skipped, with reason field ∈ {`system_page`, `cluster_policy`, `page_override`}.

## Audit and rollback

Every policy change is logged in `kg_policy_audit`. The audit table is append-only.

To roll back a single change: re-set the cluster to its previous action through the UI or CLI. Eager reconciliation re-converges. Soft-delete means the data is still there; rollback is fast and free.

To restore from a known-good policy state: the audit table contains the full history. A `wikantik kg-policy restore --as-of <timestamp>` command (cut from initial scope, but a clear extension point) replays from audit.

## Migration / first deploy

In order:

1. `V0NN__kg_inclusion_policy.sql` — creates `kg_cluster_policy`, `kg_policy_audit`, adds `kg_excluded` to `chunk_entity_mentions`. Idempotent.
2. Backfill: mark `chunk_entity_mentions` rows from system pages as `kg_excluded = TRUE`. Logged with reason `"system_page_filter_initial_backfill"`.
3. App deploys with `wikantik.kg_policy.enabled=false` initially. Confirm metrics emitting and no read-path regressions.
4. Flip the feature flag. Pages now go through the policy filter on save and on extraction.
5. Admin visits `/admin/kg-policy`, runs the bootstrap wizard, confirms.
6. Eager reconciliation runs across the touched clusters.

If anything goes wrong: flip `wikantik.kg_policy.enabled=false` and reads return to "all entities, no exclusion" (the existing behaviour). The `kg_excluded` column stays harmlessly set in the data; nothing is destroyed.

## What success looks like

- 100% of clusters have an explicit policy decision (no `unset` rows on the dashboard).
- Pending-review queue empty most days; non-empty triggers a 5-minute review session.
- Adding a new cluster (e.g. importing 10 pages of new content) surfaces in pending-review immediately and is decided within a day.
- Excluded clusters' content remains searchable (BM25/dense) — search quality unaffected.
- Agent retrieval quality (measured by the existing `RetrievalQualityRunner`) does not regress when the policy lever is exercised.
- A page that the admin wants out is one frontmatter line away from being out, regardless of cluster.

## Open extensions (out of scope here)

- **Time-bounded policy** (`valid_until`) — useful for "include this cluster for a 2-week pilot, then re-evaluate."
- **Per-relationship-type filtering** ("don't include `mentions` edges from this cluster, but do include `references`") — sophisticated; revisit if real evidence emerges that we need it.
- **Auto-classification of new clusters** based on content embeddings — possibly automatable; explicitly deferred.
- **Restore-from-audit** CLI — clear extension point on the audit table; cut from initial scope.
- **Page-level UI editor for `kg_include`** — current design treats this as advanced and routes through the page-save flow; could be elevated to a button in View B if usage warrants.

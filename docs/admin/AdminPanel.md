# Admin Panel Reference

This page is for administrators who use the admin area of the web UI. It lists every
screen under `/admin`, what each is for, what you can do on it, and which REST
endpoint backs it. Screens are listed in the order of the admin sidebar
(`AdminSidebar.jsx`). Endpoints that have no screen are listed at the end.

## Open the admin panel

Sign in with an account in the `Admin` group, then go to `/admin`. Accounts that are not administrators are redirected to
`/wiki/Main` by the SPA and get HTTP 403 from the server. How administrator status is
decided, and how to grant access to a single area, is in
[Security.md](Security.md#who-counts-as-an-administrator).

Every screen below calls a servlet under `/admin/*`, all protected by
`AdminAuthFilter`. The routes are declared in
`wikantik-frontend/src/main.jsx`; each servlet is mapped in
`wikantik-war/src/main/webapp/WEB-INF/web.xml`. The sidebar hides **Knowledge Graph**
when the Knowledge Graph capability is off.

## Overview

`/admin` shows a dashboard of live cards: health and version, request load, pending
Knowledge Graph proposals, retrieval quality (nDCG@5), LLM activity, search and index
status, users and keys, and a recent-activity feed, plus a diagnostic band (Knowledge
Graph size, extractor pipeline, judge, render cache, auth activity). Cards that have a
destination link straight to the screen that handles them.

- Backed by: `GET /admin/overview` (`AdminOverviewResource`).
- Source: `OverviewDashboard.jsx`.

## People and access

### Users

`/admin/users`. Manage accounts: create, edit, delete, lock and unlock, and bulk
actions on a selection (lock, unlock, delete, add to a group).

- Backed by: `/admin/users` and `/admin/users/{login}`, with `/lock`, `/unlock` and
  `/admin/users/bulk-action` (`AdminUserResource`).
- Source: `AdminUsersPage.jsx`.
- Notes: a user created here is flagged to change their password at first login.
  See [Security.md](Security.md#authenticate-users).

### Security

`/admin/security`. Two sections: **Groups** (create, edit membership, delete) and
**Policy Grants** (the `policy_grants` table that defines what each role may do,
including scoped `admin` area grants).

- Backed by: `/admin/groups` and `/admin/groups/{name}` (`AdminGroupResource`);
  `/admin/policy` and `/admin/policy/{id}` (`AdminPolicyResource`).
- Source: `AdminSecurityPage.jsx`.
- Deep doc: [Security.md](Security.md#authorise-access).

### API Keys

`/admin/apikeys`. Generate keys for MCP and tool clients, choose a scope, revoke keys
singly or in bulk, and show revoked keys. The plaintext token is shown once.

- Backed by: `/admin/apikeys`, `/admin/apikeys/{id}` and
  `/admin/apikeys/bulk-action` (`AdminApiKeysResource`).
- Source: `AdminApiKeysPage.jsx`.
- Deep docs: [ApiKeys.md](ApiKeys.md), [McpAgents.md](McpAgents.md).

## Content

### Content & Index

`/admin/content`. Six tabs: **Dashboard** (page and index statistics, flush render
cache), **Orphaned Pages** (list, bulk delete), **Broken Links**, **Versions** (purge
old versions of a page, keeping the newest N), **Chunk Inspector** (see how a page was
chunked and find outlier chunks), and **Index Status** (rebuild the search indexes,
reindex, reindex embeddings).

- Backed by: `/admin/content/*` (`AdminContentResource`): `stats`, `orphaned-pages`,
  `broken-links`, `bulk-delete`, `purge-versions`, `reindex`, `index-status`,
  `chunks`, `chunks/outliers`, `rebuild-indexes`, `cache/flush`,
  `reindex-embeddings`.
- Source: `AdminContentPage.jsx`, `IndexStatusTab.jsx`, `ChunkInspectorTab.jsx`.
- Deep doc: [IndexRebuild.md](IndexRebuild.md).

### Page Ownership

`/admin/page-ownership`. Two tabs: **Orphaned** (pages whose owner was deleted) and
**By Owner**. Reassign one page, or reassign every page of a user.

- Backed by: `/admin/page-ownership` and `/admin/page-ownership/reassign`,
  `/admin/page-ownership/reassign-by-user` (`AdminPageOwnershipResource`).
- Source: `AdminPageOwnershipPage.jsx`.
- Deep doc: [PageOwnership.md](PageOwnership.md).

### Connectors

`/admin/connectors`, `/admin/connectors/new`, `/admin/connectors/{id}`. The list shows
every connector, including read-only ones defined in properties. **Add Connector**
runs a wizard: pick a type, describe the source, authorise, run a dry-run test, review.
A connector's page has four tabs: **Overview** (status, recent sync runs, Sync now,
test, import a properties-defined connector into the database), **Settings**,
**Authorization** (secrets and the Google Drive consent flow) and **Pages** (the
derived pages it produced). Delete can optionally delete those pages.

- Backed by: `/admin/connectors` and `/admin/connectors/{id}` with `/sync`, `/runs`,
  `/pages`, `/import`, `/test` (`ConnectorAdminResource`);
  `/admin/connector-credentials/{id}/{name}` (`ConnectorCredentialsResource`);
  `/admin/connector-oauth/gdrive/*` (`GoogleDriveAuthResource`).
- Source: `AdminConnectorsPage.jsx`, `ConnectorDetailPage.jsx`, `AddConnectorWizard.jsx`.
- Deep docs: [Connectors.md](Connectors.md), [DerivedPagesAndIngest.md](DerivedPagesAndIngest.md).

## Knowledge and search

### Knowledge Graph

`/admin/knowledge-graph`. Eight tabs: **Proposals** (review pending node and edge
proposals), **Extraction** (run, watch and cancel the LLM entity extractor), **Node
Explorer**, **Edge Explorer**, **Content Embeddings** (the mention-centroid index and
frontmatter backfill), **Hub Proposals**, **Hub Discovery** (cluster-based discovery
of new hubs) and **LLM Activity** (about the last hour of in-flight and recent LLM
calls, held in memory only).

- Backed by: `/admin/knowledge-graph/*` (`AdminKnowledgeResource`),
  `/admin/knowledge-graph/hub-discovery/*` (`AdminHubDiscoveryResource`),
  `/admin/knowledge-graph/extract-mentions` (`AdminExtractionResource`) and
  `/admin/llm-activity` (`AdminLlmActivityResource`).
- Source: `AdminKnowledgePage.jsx` and the tab components beside it.
- Deep docs: [HubDiscovery.md](HubDiscovery.md), [OntologyManagement.md](OntologyManagement.md).

### KG Policy

`/admin/kg-policy`. Decide, per cluster, whether its pages feed the Knowledge Graph
(include, exclude, or unset, which means excluded by default). Filter by decision,
review a cluster, and clear a decision. Three sub-screens: `/admin/kg-policy/explain`
(why one page is in or out), `/admin/kg-policy/pending` (unset clusters, reviews older
than 90 days, recent page-count changes) and `/admin/kg-policy/bootstrap` (seed
decisions in bulk).

- Backed by: `/admin/kg-policy/*` (`AdminKgPolicyResource`): `clusters`,
  `clusters/{name}`, `clusters/{name}/review`, `bootstrap`, `explain/{id}`, `pending`,
  `audit`, `reconciliation`, `estimate`.
- Source: `AdminKgPolicyPage.jsx`, `AdminKgPolicyExplain.jsx`,
  `AdminKgPolicyPending.jsx`, `AdminKgPolicyBootstrap.jsx`.
- Deep doc: [KgInclusionPolicy.md](KgInclusionPolicy.md).

### Retrieval Quality

`/admin/retrieval-quality`. Nightly retrieval-evaluation runs and their trends
(recall and nDCG series), with a **Run now** button per query set and mode.

- Backed by: `/admin/retrieval-quality` and `/admin/retrieval-quality/run`
  (`AdminRetrievalQualityResource`).
- Source: `AdminRetrievalQualityPage.jsx`.
- Deep doc: [RetrievalQuality.md](RetrievalQuality.md).

### Drift

`/admin/drift`. Frontmatter and ontology-conformance drift counts across sweeps, with
deltas, trend sparklines and a per-code page list. Trigger a sweep with **Run sweep
now**.

- Backed by: `/admin/drift/summary`, `/trend`, `/pages`, `/status`, `/sweep`
  (`AdminDriftResource`).
- Source: `AdminDriftPage.jsx`.
- Deep doc: [DriftDashboard.md](DriftDashboard.md).

### Search Visibility

`/admin/insights`. Per-engine search acquisition totals (clicks, impressions, CTR,
position, trend) for the latest snapshot, and below them the ranked content-opportunity
backlog with a type filter and an include-snoozed toggle.

- Backed by: `/admin/insights/acquisition` (`InsightsResource`) and
  `/admin/insights/backlog` (`InsightsBacklogResource`). Data arrives through
  `POST /admin/insights/ingest` (`InsightsIngestResource`).
- Source: `InsightsPanel.jsx`, `BacklogPanel.jsx`.
- Deep doc: [ContentIntelligence.md](ContentIntelligence.md).

## Observability

### Audit

`/admin/audit`. Filter the tamper-evident audit log by category, outcome and date,
open a record, verify the hash chain, and export CSV.

- Backed by: `/admin/audit`, `/admin/audit/verify`, `/admin/audit/export?format=csv`
  (`AdminAuditResource`).
- Source: `AdminAuditPage.jsx`.
- Deep doc: [AuditLog.md](AuditLog.md).

## Admin endpoints without a screen

These `/admin/*` servlets are registered in `web.xml` but have no route in the SPA.
Call them with `curl` as an administrator.

| Endpoint | Servlet | Purpose |
|---|---|---|
| `/admin/ontology/*` | `AdminOntologyResource` | `POST /admin/ontology/rebuild`, `GET /admin/ontology/status`, `GET /admin/ontology/violations` (SHACL conformance); see [OntologyManagement.md](OntologyManagement.md) |
| `/admin/derived/status`, `/admin/derived/reflow` | `AdminDerivedResource` | derived-page fleet status and reflow; see [DerivedPagesAndIngest.md](DerivedPagesAndIngest.md) |
| `/admin/clusters/*` | `AdminClusterResource` | `POST /admin/clusters/rename?from=X&to=Y` returns the plan; add `confirm=true` to apply (rewrites `cluster:` frontmatter only) |
| `/admin/page-graph/conflicts` | `AdminStructuralConflictsResource` | structural conflicts; see [DriftDashboard.md](DriftDashboard.md#structural-conflicts) |
| `/admin/verification` | `AdminVerificationResource` | verification state of every page, with confidence and days since verified (filters `confidence`, `min_days_stale`) |
| `/admin/frontmatter-issues` | `AdminFrontmatterIssuesResource` | pages whose YAML frontmatter fails strict parsing, with the parser message and line and column |
| `/admin/agent-grade-audit` | `AdminAgentGradeAuditServlet` | agent-grade content audit (needs the structural index; 503 without it) |
| `/admin/profiling/jfr/*` | `AdminProfilingServlet` | Java Flight Recorder control: `POST /start`, `POST /stop`, `GET /recordings`, `GET /recordings/{id}` (downloads the `.jfr` file) |
| `/admin/drift/citations` | `AdminDriftResource` | citation status counts and per-page citation lists; see [DriftDashboard.md](DriftDashboard.md#stale-citations) |
| `/admin/insights/ingest` | `InsightsIngestResource` | search-visibility snapshot upload |

For how many servlets and URL patterns exist in total, see the module and servlet
counts in [the architecture overview](../developer/Architecture.md).

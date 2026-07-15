# Connector Admin UI — design (P2.4)

**Date:** 2026-07-15
**Status:** Approved design (grilled 2026-07-15), not yet planned into tasks
**Prereqs shipped:** connector framework P2.1–P2.3c (filesystem, web crawler, sitemap, feed,
gdrive, github, confluence), credential encryption (V047), sync state (V046),
`ConnectorAdminResource`, `ConnectorCredentialsResource`, `GoogleDriveAuthResource`.

## 1. Problem

Every connector shipped 2026-07-11..15 is operable only via properties files + curl. There is no
admin panel surface: no way to create a connector, store its secret, authorize Drive, trigger or
observe a sync, or see which wiki pages came from external sources. Reader-facing pages carry
provenance only in frontmatter; nothing is visibly marked.

## 2. Decisions (grilling record)

| # | Fork | Decision |
|---|------|----------|
| D1 | Config store | **DB-backed (`connector_configs`) + hot-apply.** Properties-defined connectors still wire at startup, shown read-only ("from config file") with one-click **import to database**. |
| D2 | Secrets | **All secrets → existing `CredentialStore`** (AES-256-GCM, V047). `connector_configs` holds no secret fields. gdrive `client_secret` moves out of properties into the store. Master key (`wikantik.connectors.crypto.key`) stays in properties as the trust root. |
| D3 | Delete semantics | **Keep derived pages by default**; delete confirmation shows the page count and offers an opt-in "also delete the N derived pages" checkbox. Kept pages are stamped `derived_orphaned: true`. Disable = pause syncing, touch nothing. |
| D4 | Scheduling | **Per-connector `sync every N hours`** (0/blank = manual only), stored in config, editable in UI. Global `wikantik.connectors.sync.interval.hours` remains only as the default for properties-defined connectors. |
| D5 | Setup flow | **Stepped wizard per type**: source settings → authorize (external walk-through + secret entry; gdrive consent redirect) → test connection → review/"what to expect" + Sync Now. |
| D6 | Derived marking | **Provenance banner on PageView + compact ↯ badge in lists/search/cluster tree.** New frontmatter: `derived_connector`, `derived_source_url`. |
| D7 | Observability | **Persist per-run history** (`connector_sync_run`): trigger, timings, upserted/deleted/skipped counts, error text. Detail page shows last ~20 runs. No per-item persistence. |
| D8 | Kill switch | `wikantik.connectors.enabled` flips to **kill-switch, default `true`** (runtime always wires; empty registry fine). `false` suppresses all syncing; UI shows "disabled by operator". |
| D9 | Filesystem type | **Not creatable from UI** (server-file-disclosure surface). Stays properties-only; shown read-only with status/sync like any other. |
| D10 | Content defaults | Optional per-connector **cluster, default tags, page-name prefix** — applied only at page creation, never overwriting later curation. |
| D11 | Scope | **All five remote types** (webcrawler, sitemap, feed, github, confluence) **+ gdrive OAuth** in the first release. |

## 3. Data model

### V048 — `connector_configs`

```sql
CREATE TABLE IF NOT EXISTS connector_configs (
    connector_id   VARCHAR(64)  PRIMARY KEY,          -- kebab/word id, no dots (matches wiring rule)
    connector_type VARCHAR(32)  NOT NULL,             -- webcrawler|sitemap|feed|gdrive|github|confluence
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    sync_interval_hours INTEGER NOT NULL DEFAULT 0,   -- 0 = manual only
    config         TEXT         NOT NULL,             -- JSON: non-secret, type-specific fields
    cluster        VARCHAR(128),                      -- D10 content defaults (nullable)
    default_tags   VARCHAR(512),                      -- comma-separated
    page_prefix    VARCHAR(64),
    created        TIMESTAMP    NOT NULL DEFAULT NOW(),
    modified       TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

`config` JSON carries exactly the non-secret fields the existing `*Config` records take
(e.g. github: `repo`, `branch`, `path_prefix`, `max_files`; gdrive adds `client_id`,
`redirect_uri`, `export_mime`, `folder_ids` — `client_secret` is a credential, D2).
Server-side validation mirrors `ConnectorWiringHelper`'s parse rules (repo shape, non-empty
seeds/urls/folder_ids, id charset) and returns field-keyed 422s.

### V049 — `connector_sync_run`

```sql
CREATE TABLE IF NOT EXISTS connector_sync_run (
    run_id       BIGSERIAL PRIMARY KEY,
    connector_id VARCHAR(64) NOT NULL,
    trigger      VARCHAR(16) NOT NULL,      -- manual | scheduled
    started      TIMESTAMP   NOT NULL,
    finished     TIMESTAMP,
    status       VARCHAR(16) NOT NULL,      -- running | ok | failed
    upserted     INTEGER NOT NULL DEFAULT 0,
    deleted      INTEGER NOT NULL DEFAULT 0,
    skipped      INTEGER NOT NULL DEFAULT 0,
    error        TEXT
);
CREATE INDEX IF NOT EXISTS idx_sync_run_connector ON connector_sync_run( connector_id, started DESC );
```

Written by `SyncOrchestrator` (row at start, updated at end — a crash leaves an honest
`running`-forever row the UI renders as "interrupted"). Pruned to the newest 100 per connector
on insert.

### Credentials (existing V047 store)

Per-type secret names (UI shows set/unset, never values):

| Type | Secret name(s) |
|------|----------------|
| github | `token` (optional — public repos work without) |
| confluence | `api_token` (required) |
| gdrive | `client_secret` (required), `refresh_token` (written by consent flow) |
| webcrawler / sitemap / feed | none |

## 4. Runtime changes (wikantik-connectors + wikantik-main)

### ConnectorConfigService (new, wikantik-main `com.wikantik.derived`)

CRUD over `connector_configs` + the **rebuild-and-swap** hot-apply: on any mutation it re-reads
DB rows + the startup properties snapshot, rebuilds `ConnectorRegistry`/`DriveAuthCoordinator`
exactly the way `ConnectorWiringHelper` does today (the builder logic is extracted so both call
one path), and swaps them into `ConnectorRuntime` atomically. Per-connector sync locks live in
`ConnectorRuntime` and survive the swap; an in-flight sync finishes against its old connector
instance, and config changes take effect on the next run.

Registry entries carry an `origin` (`db` | `properties`). Properties-origin entries reject
update/delete (409 with "defined in wikantik-custom.properties") and support **import**: copy
into `connector_configs`, after which the DB row shadows the properties definition (id match)
and becomes editable. (For gdrive imports the operator is prompted to re-enter `client_secret`
into the store since properties held it before.)

### Scheduler: fixed-rate → due-tick

`ConnectorRuntime`'s single `scheduleAtFixedRate(syncAll)` is replaced by a one-minute tick that
syncs every enabled connector whose `last_run + sync_interval_hours <= now` (interval 0 = never).
Hot-applied interval changes need no scheduler restart. Properties-origin connectors use the
global `wikantik.connectors.sync.interval.hours` as their interval (D4).

### Kill switch (D8)

`ConnectorWiringHelper` always wires the runtime (registry may be empty).
`wikantik.connectors.enabled=false` (explicitly set) suppresses the tick, rejects manual sync
with 409, and surfaces as a UI banner. **Deployment note:** flipping the default from `false`
to `true` is safe — with no properties config and an empty table, nothing syncs — but the
release notes must call it out.

## 5. REST API (wikantik-rest)

All under `AdminAuthFilter`. `ConnectorAdminResource` grows from 2 routes to the full surface;
JSON errors via `RestServletBase.sendError()` (never raw `sendError` — Tomcat HTML leak).

| Route | Purpose |
|-------|---------|
| `GET /admin/connectors` | List: id, type, origin, enabled, interval, last run summary, page count, secret status. (Exposes the existing `ConnectorRuntime.list()`.) |
| `POST /admin/connectors` | Create (DB origin). 422 field-keyed validation; 409 duplicate id. Hot-applies. |
| `GET /admin/connectors/{id}` | Detail: config JSON, content defaults, secret set/unset map, status. |
| `PUT /admin/connectors/{id}` | Update (DB origin only). Hot-applies. |
| `DELETE /admin/connectors/{id}?deletePages=false` | D3. Returns counts `{pagesKept|pagesDeleted, credentialsDeleted}`. Kept pages stamped `derived_orphaned: true`. Also removes sync state + credentials. |
| `POST /admin/connectors/{id}/sync` | Exists. Add 409-on-disabled + kill-switch. |
| `GET /admin/connectors/{id}/status` | Exists (kept for compat). |
| `GET /admin/connectors/{id}/runs?limit=20` | Sync-run history (V049). |
| `GET /admin/connectors/{id}/pages` | Derived pages from `connector_synced_item` (name, source URI, last upsert). |
| `POST /admin/connectors/test` | **Dry-run against an unsaved payload** `{type, config, credentials?}`. Reuses the connectors' own fetch paths + caps (same SSRF/size posture as a real sync); ingests nothing. Returns ok/fail + human detail ("reachable, found ~137 items; first: …"). Transient credentials in the request are used once, never stored, never logged. |
| `POST /admin/connectors/{id}/import` | Properties-origin → DB row (D1). |
| `/admin/connector-credentials/*` | Unchanged; the wizard drives it (`POST .../{id}/{name}` body = secret). |
| `/admin/connector-oauth/gdrive/{id}/authorize` + `/callback` | Reads `client_secret` from the CredentialStore instead of properties; `authorize` gains `?return_to=` (allowlisted to `/admin/connectors/...` paths) so the callback lands back in the wizard with `?oauth=ok|error`. |

Per-type test semantics: github = get repo + list tree head; confluence = list first page of
space; feed = fetch+parse each feed head; sitemap = fetch+parse sitemap(s), count URLs;
webcrawler = fetch first seed + robots probe; gdrive = mint access token from refresh token +
list first folder page (only available post-consent; the wizard tests after the OAuth step).

**Audit:** create/update/delete/import/credential-set/credential-delete/oauth-stored all emit
events into the existing tamper-evident audit log (category `connector`). Payloads carry field
names, never secret values.

## 6. Admin UI (wikantik-frontend)

New sidebar entry **Connectors** (`/admin/connectors` SPA route — remember dual registration:
web.xml + `SpaRoutingFilter.SPA_EXACT`… these are wildcard sub-routes of the existing `/admin`
SPA entry, so verify whether `/admin/*` already routes before adding entries).

### Pages

- **AdminConnectorsPage (list).** Table: type icon, name, origin chip ("config file" for
  properties origin), enabled toggle, schedule, last sync (relative + status dot), pages count,
  Sync Now. Empty state explains what connectors do and points at Add Connector.
  Banner states: credential store disabled (shows how to generate + set
  `wikantik.connectors.crypto.key`, with `openssl rand -base64 32` copy block, and states
  crawler/sitemap/feed still work without it); kill switch off.
- **ConnectorDetailPage.** Tabs: **Overview** (status, next scheduled run, run history table
  with expandable error text, "interrupted" render for stale `running` rows), **Settings**
  (edit form = wizard step 1 fields + content defaults + interval; read-only + Import button
  for properties origin), **Authorization** (secret set/unset rows with replace/delete; gdrive:
  consent status + Re-authorize), **Pages** (derived pages list → wiki links).
- **AddConnectorWizard.** Type picker (5 cards, one-line "good for…" each) → per-type steps:

| Step | Content |
|------|---------|
| 1. Source | id/name + type fields (github: repo/branch/path/max; confluence: base_url/space/email/max; feed: urls/max/fetch_full; sitemap: urls/max/same_host; crawler: seeds/depth/max/path_prefix/robots/delay) + content defaults (cluster/tags/prefix) + interval. Instruction pane explains each field with concrete examples. |
| 2. Authorize | Skipped for crawler/sitemap/feed. github: fine-grained PAT walkthrough (Settings → Developer settings → Fine-grained tokens; repository access = just this repo; Contents: Read-only; "public repos may skip this"). confluence: Atlassian token walkthrough (id.atlassian.com/manage-profile → Security → API tokens; label it; it pairs with your login email). gdrive: two-phase — (a) GCP console walkthrough (create project → OAuth consent screen → credentials → Web application; **copy-paste field** showing the exact redirect URI computed from `wikantik.baseURL`), enter client_id + client_secret; (b) **Authorize with Google** button → consent redirect → back with `?oauth=ok`. |
| 3. Test | Runs `POST /admin/connectors/test`; success shows what was found; failure shows the human reason + "check step N" hint. Retry without losing state. Skippable with a warning. |
| 4. Review | Summary + **what to expect**: "First sync will create up to N pages named `<Prefix><SourceName>` in cluster `<cluster>`, marked as synced from this source. Bodies are machine-managed — edits to them are overwritten on the next sync; frontmatter curation you add is preserved. Pages appear in search after the async index catches up (typically under a minute)." Buttons: Save, or Save & Sync Now (then routes to Overview where the run appears live). |

Wizard instruction text lives in a frontend module (`connectorGuides.js`), not the REST layer.

### Delete flow

Modal fetches `GET .../pages` count first: "This connector created **214 pages**. They will be
kept and marked 'no longer syncing'. ☐ Also delete all 214 derived pages." Typed-name
confirmation when the checkbox is on.

## 7. Derived-content marking (reader-facing)

### Frontmatter additions (ingestion)

`DerivedPageSinkAdapter`/`DerivedPageIngestionService` additionally stamp:

- `derived_connector: <id>` — which connector owns the page.
- `derived_source_url: <https url>` — human-clickable origin when the source URI isn't already
  one (`SourceItem` gains an optional `sourceUrl`; gdrive supplies `webViewLink`, github the
  `html_url`, confluence the webui link; crawler/sitemap/feed URIs are already URLs).
- On connector deletion with pages kept: `derived_orphaned: true` (bulk frontmatter update,
  body untouched).
- D10 defaults at creation only: `cluster`, `tags`, and the page-name prefix in `flatName`.

### Reader UI

- **PageView provenance banner** (distinct style from the editor's warning banner): type icon +
  "Synced from *<link>* · last synced <page modified date> · via connector *<name>*"; when
  `derived_orphaned`, appends "— source no longer syncing". Rendered purely from frontmatter +
  page metadata already in the page payload — no admin API calls from reader surfaces.
- **↯ badge** beside page titles in search results, page lists, and the cluster tree, tooltip
  "Synced from an external source". Requires the listing/search payloads to expose a boolean
  `derived` (server-side: frontmatter has `derived_from`) — small additions to those REST
  payloads rather than shipping full frontmatter to lists.
- Editor keeps its existing machine-owned-body banner.

## 8. Security posture

- Everything under `AdminAuthFilter` (AllPermission), as today.
- Secrets: write-only through the store; UI/API never echo values; test-endpoint transient
  credentials never persisted/logged (matches `ConnectorCredentialsResource` hygiene).
- Filesystem type not creatable via web (D9) — the one type that reads server paths.
- `POST /admin/connectors/test` reuses the connectors' own HTTP clients with their existing
  response caps; no new fetch primitive, no redirects-to-internal bypass beyond what a real
  sync could already do. Admin-only mitigates SSRF; caps mitigate OOM.
- `return_to` on the OAuth authorize route is allowlisted to same-origin `/admin/connectors`
  paths (open-redirect defense).
- Audit coverage per §5.

## 9. Rollout / migration

1. V048 + V049 migrate on deploy (idempotent DDL, `:app_user` grants).
2. Existing docker1 prod gdrive connector keeps working from properties (origin=properties,
   read-only) — then one-click import + re-enter `client_secret` + Re-authorize, and the
   properties lines can be removed at leisure. Import is the *only* properties-origin mutation.
3. `wikantik.connectors.enabled` default flip documented in release notes (D8).
4. No data backfill in migrations (project rule) — `derived_connector` appears on pages as
   their next sync touches them; the banner degrades gracefully when only `derived_from` exists
   (shows source, omits connector name).

## 10. Testing

- Unit: config service CRUD + hot-apply swap; due-tick scheduler (fake clock); validation
  parity with `ConnectorWiringHelper` parse rules; sink stamping of new frontmatter + create-only
  defaults; run-history writes incl. crash row.
- Wire-level IT (Cargo, per project rule for admin write surfaces): create→test→sync→runs→
  delete(keep/cascade) round-trip; properties-origin 409s + import; credential set/unset flows;
  OAuth callback with stubbed transport (the injectable OAuth transport from 1ffd5b5716);
  kill-switch 409.
- Frontend vitest: wizard step flow per type, test-step failure rendering, delete modal
  checkbox gating, provenance banner + badge rendering, prerequisite banners.
- IT gotcha: index-dependent assertions use startup fixtures, not freshly-synced pages
  (structural-index seed lag).

## 11. Out of scope

- Filesystem connector creation from UI (D9).
- Per-item sync results persistence (D7 rejected option).
- Editing properties-defined connectors in place (import instead).
- Any change to extraction/reflow semantics (ADR-0004 body-ownership unchanged).
- Retrieval/KG policy changes — derived pages follow the existing cluster-primary inclusion
  policy via their assigned cluster (D10).

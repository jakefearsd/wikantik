# External-Source Connectors

Wikantik can pull content in from external systems — websites, RSS/Atom feeds,
sitemaps, Google Drive, GitHub, and Confluence — and keep it synced into wiki
pages automatically. This is the **connector framework**: `wikantik-connectors`
(the sync engine and per-type source implementations) plus the admin UI at
`/admin/connectors` (`wikantik-main` `com.wikantik.derived.ConnectorConfigService`,
`wikantik-rest` `ConnectorAdminResource`).

Connector-synced pages are **derived pages** (see
[the derived-pages design](superpowers/specs/2026-06-14-derived-pages-design.md)
and [Frontmatter.md](Frontmatter.md#derived-page-provenance)): their bodies are
machine-owned and regenerated on every sync, curation-safe frontmatter you add
survives, and the retained source is recorded via `derived_from` in the page's
frontmatter.

---

## The six connector types

| Type | Syncs | Auth |
|---|---|---|
| `filesystem` | A local directory tree | None — properties-only, never creatable from the admin UI (server-file-disclosure surface, design decision D9) |
| `webcrawler` | Pages reachable by following links from a set of seed URLs | None |
| `sitemap` | URLs listed in one or more `sitemap.xml` files | None |
| `feed` | Entries from RSS/Atom feeds | None |
| `gdrive` | Files in configured Google Drive folders (Docs exported as markdown, native `.md`/`.txt`) | OAuth2 (client id/secret + refresh token) |
| `github` | Markdown files from a repository tree | Fine-grained PAT (optional for public repos) |
| `confluence` | Pages from a Confluence Cloud space | Atlassian API token |

`filesystem` is the one type that reads server-local paths, so it is
**properties-only** — it shows up read-only in the admin UI but cannot be
created or edited there. The other five are fully manageable from
`/admin/connectors`.

---

## Two ways to define a connector

### 1. Admin-managed DB configs (recommended)

Created through the `/admin/connectors` UI (or `POST /admin/connectors`
directly). Stored in the `connector_configs` table (migration `V048`) and
**hot-applied** — no restart required. Every mutation
(`ConnectorConfigService.rebuild()`) re-reads the DB rows plus the startup
properties snapshot and atomically swaps a fresh registry into
`ConnectorRuntime`; an in-flight sync finishes against its old connector
instance.

The `connector_configs.config` column holds only **non-secret**, type-specific
JSON fields (e.g. github's `repo`/`branch`/`path_prefix`/`max_files`). Secret
fields are rejected outright — see [Credentials](#credentials) below.

### 2. Properties-defined connectors

Declared directly in `wikantik-custom.properties` using the
`wikantik.connectors.<type>.<id>.<field>` key pattern, wired once at engine
startup. They appear in the admin UI **read-only**, labeled "config file", with
a one-click **Import** button that copies them into `connector_configs` — after
which the DB row shadows the properties definition by id and becomes editable.
Import is the *only* mutation a properties-origin connector supports directly;
attempting `PUT`/`DELETE` against an un-imported properties-origin id returns
`409` ("connector '\<id\>' is defined in wikantik-custom.properties").

Property syntax by type (from
`wikantik-main/src/main/resources/ini/wikantik.properties`, ~line 1549 on):

```properties
# Kill switch (default true) — see "Kill switch" below.
#wikantik.connectors.enabled = true
#wikantik.connectors.sync.interval.hours = 0
#wikantik.connectors.filesystem.docs.root = /data/docs

# Web crawler — each <id> needs at least .seeds
#wikantik.connectors.webcrawler.<id>.seeds = https://example.com/
#wikantik.connectors.webcrawler.<id>.same_host_only = true
#wikantik.connectors.webcrawler.<id>.path_prefix =
#wikantik.connectors.webcrawler.<id>.max_pages = 100
#wikantik.connectors.webcrawler.<id>.max_depth = 3
#wikantik.connectors.webcrawler.<id>.delay_ms = 1000
#wikantik.connectors.webcrawler.<id>.user_agent = WikantikCrawler/1.0 (+https://wiki.wikantik.com)
#wikantik.connectors.webcrawler.<id>.respect_robots = true

# Sitemap — each <id> needs at least .sitemap_urls
#wikantik.connectors.sitemap.<id>.sitemap_urls = https://example.com/sitemap.xml
#wikantik.connectors.sitemap.<id>.max_pages = 500
#wikantik.connectors.sitemap.<id>.delay_ms = 1000
#wikantik.connectors.sitemap.<id>.user_agent = WikantikCrawler/1.0 (+https://wiki.wikantik.com)
#wikantik.connectors.sitemap.<id>.respect_robots = true
#wikantik.connectors.sitemap.<id>.same_host_only = true

# RSS/Atom feed — each <id> needs at least .feed_urls
#wikantik.connectors.feed.<id>.feed_urls = https://example.com/rss.xml
#wikantik.connectors.feed.<id>.max_items = 100
#wikantik.connectors.feed.<id>.fetch_full_articles = true
#wikantik.connectors.feed.<id>.delay_ms = 1000
#wikantik.connectors.feed.<id>.user_agent = WikantikCrawler/1.0 (+https://wiki.wikantik.com)
#wikantik.connectors.feed.<id>.respect_robots = true
#wikantik.connectors.feed.<id>.same_host_only = true

# Google Drive — requires wikantik.connectors.enabled=true AND a configured
# wikantik.connectors.crypto.key (the refresh token is stored encrypted).
# Obtain the refresh token via the admin consent flow at
# /admin/connector-oauth/gdrive/<id>/authorize (redirect_uri must match the
# one registered in the Google Cloud OAuth client).
#wikantik.connectors.gdrive.<id>.folder_ids   = <comma-separated Drive folder IDs>
#wikantik.connectors.gdrive.<id>.client_id     = <OAuth2 client id>
#wikantik.connectors.gdrive.<id>.client_secret = <OAuth2 client secret>
#wikantik.connectors.gdrive.<id>.redirect_uri  = https://<host>/admin/connector-oauth/gdrive/callback
#wikantik.connectors.gdrive.<id>.max_files     = 500
#wikantik.connectors.gdrive.<id>.export_mime   = text/markdown

# GitHub — inject the token (fine-grained PAT, read-only Contents scope) once via:
#   POST /admin/connector-credentials/<id>/token
#wikantik.connectors.github.<id>.repo        = <owner>/<name>
#wikantik.connectors.github.<id>.branch      =
#wikantik.connectors.github.<id>.path_prefix =
#wikantik.connectors.github.<id>.max_files   = 500

# Confluence Cloud — inject the Atlassian API token once via:
#   POST /admin/connector-credentials/<id>/api_token
#wikantik.connectors.confluence.<id>.base_url  = https://<site>.atlassian.net
#wikantik.connectors.confluence.<id>.space_key = <KEY>
#wikantik.connectors.confluence.<id>.email     = <account email>
#wikantik.connectors.confluence.<id>.max_pages = 500
```

> **SECURITY:** do not enable Google's HTTP wire logging in production
> (`java.util.logging` on `com.google.api.client.http.HttpTransport` at
> `CONFIG`/`FINE`) — it logs request/response bodies including OAuth tokens. It
> is off by default; leave it off.

---

## The admin UI (`/admin/connectors`)

### List page

Table of every connector (both origins merged): type icon, name, origin chip
("config file" for properties-origin), enabled toggle, sync schedule, last-sync
status (relative time + status dot), derived-page count, and a **Sync Now**
button. An empty state explains what connectors do and points at **+ Add
Connector**. Two operator banners can appear above the table:

- **Syncing disabled** — `wikantik.connectors.enabled=false` is set; syncs
  won't run, but configuration stays editable.
- **Credential storage not configured** — no `wikantik.connectors.crypto.key`
  is set, so GitHub/Confluence/Google Drive connectors can't store secrets
  (web crawler, sitemap, and feed connectors are unaffected — they need no
  secrets). The banner shows the fix inline: generate a key with
  `openssl rand -base64 32`, set `wikantik.connectors.crypto.key=<key>` in
  `wikantik-custom.properties`, and restart.

### Detail page — four tabs

- **Overview** — status, next scheduled run, and a run-history table
  (expandable error text; a stale `running` row renders as "interrupted" — see
  [Sync behavior](#sync-behavior)).
- **Settings** — the same fields as wizard step 1 (source config + content
  defaults + interval). Read-only with an **Import** button for
  properties-origin connectors.
- **Authorization** — secret set/unset rows with replace/delete; for `gdrive`,
  consent status plus a **Re-authorize** action.
- **Pages** — the connector's derived pages, linked into the wiki.

### Deleting a connector

Delete is **gated**: the confirmation modal first fetches the connector's page
count (`GET /admin/connectors/{id}/pages`) and shows it — "This connector
created **214 pages**." By default the pages are **kept**, stamped
`derived_orphaned: true` (frontmatter only; the body is untouched). An opt-in
checkbox ("Also delete all 214 derived pages") switches to hard delete, gated
behind typed-name confirmation. Either way, `DELETE
/admin/connectors/{id}?deletePages=true|false` always removes the connector's
sync state, **purges its `connector_sync_run` history**, and deletes any stored
credentials — so a later same-id recreation starts clean. The response is
`{ pagesKept, pagesDeleted, credentialsDeleted }`.

Delete is DB-origin only; a properties-origin connector that was never
imported returns `409`.

### Add Connector wizard

Type picker (five creatable types — not `filesystem`, D9) → per-type steps:

1. **Source.** Connector id/name + type-specific fields (github:
   repo/branch/path/max; confluence: base_url/space/email/max; feed:
   urls/max/fetch_full; sitemap: urls/max/same_host; crawler:
   seeds/depth/max/path_prefix/robots/delay) plus **content defaults**
   (cluster / default tags / page-name prefix) and the sync interval.
2. **Authorize.** Skipped for crawler/sitemap/feed (no auth). For github: a
   fine-grained PAT walkthrough (Settings → Developer settings → Fine-grained
   tokens; repository access = just this repo; Contents: Read-only — public
   repos may skip this step). For confluence: an Atlassian API-token
   walkthrough (id.atlassian.com/manage-profile → Security → API tokens). For
   gdrive: a two-phase flow — (a) a GCP console walkthrough (OAuth consent
   screen → credentials → Web application) with a copy-paste field for the
   exact redirect URI computed from `wikantik.baseURL`, then enter
   `client_id`/`client_secret`; (b) an **Authorize with Google** button that
   redirects through the Google consent screen and back with `?oauth=ok`.
3. **Test.** Runs `POST /admin/connectors/test` (an unsaved-payload dry run) —
   success shows what was found ("reachable, found ~137 items; first: …");
   failure shows the human-readable reason with a "check step N" hint.
   Retryable without losing state; skippable with a warning.
4. **Review.** Summary + "what to expect" (first sync will create up to N
   pages named `<Prefix><SourceName>` in cluster `<cluster>`; bodies are
   machine-managed and overwritten on the next sync; frontmatter curation is
   preserved). **Save**, or **Save & Sync Now** (routes to the Overview tab
   where the run appears live).

---

## Credentials

Secrets never live in `connector_configs.config`. They live **only** in the
encrypted `connector_credentials` table (migration `V047`; AES-256-GCM,
`ciphertext` = base64 `iv‖ct‖tag`). Per type:

| Type | Secret name(s) |
|---|---|
| `github` | `token` (optional — public repos work without one) |
| `confluence` | `api_token` (required) |
| `gdrive` | `client_secret` (required), `refresh_token` (written by the OAuth consent flow) |
| `webcrawler` / `sitemap` / `feed` | none |

`ConnectorConfigService` rejects any config key that matches (case-insensitive)
`client_secret`, `token`, `api_token`, `refresh_token`, `password`, or `secret`
with a field-keyed `422`: *"secret values must be stored via the credentials
endpoint, not config."* This applies on both `create` and `update`.

### Credentials API

- `GET /admin/connector-credentials/{id}` — lists stored secret **names** for
  a connector (never values).
- `POST /admin/connector-credentials/{id}/{name}` — stores a secret; the raw
  request body is the value (max 8192 characters, leading/trailing whitespace
  stripped, rejected if blank). The response echoes only `connectorId` and
  `name`.
- `DELETE /admin/connector-credentials/{id}/{name}` — deletes a stored secret.

All three return `503` when the `CredentialStore` isn't enabled (no master key
configured). Every mutation triggers a best-effort hot-rebuild of the live
connector registry, so a rotated token or secret reaches the running connector
immediately without a restart.

### The master key

`wikantik.connectors.crypto.key` is a base64-encoded 32-byte AES-256 key.
**Absent or blank disables credential storage entirely** —
`/admin/connector-credentials/*` returns `503`, and github/confluence/gdrive
connectors can't be created or authorized (crawler/sitemap/feed are unaffected,
since they carry no secrets). Generate one with:

```bash
openssl rand -base64 32
```

Set it in `wikantik-custom.properties`:

```properties
wikantik.connectors.crypto.key = <the generated key>
```

This key is the trust root for every stored secret — losing it makes existing
ciphertext unrecoverable, so treat it like any other production secret
(back it up, never commit it).

### Google Drive OAuth consent flow

Endpoints: `/admin/connector-oauth/gdrive/{id}/authorize` and
`/admin/connector-oauth/gdrive/callback`.

1. `GET /admin/connector-oauth/gdrive/{id}/authorize?return_to=…` generates a
   random state nonce, stashes it (plus the connector id and an optional
   `return_to`) in the session, and `302`s to Google's consent screen.
   `return_to` is **allowlisted** to same-origin `/admin/connectors/...` paths
   (open-redirect defense) — the wizard uses it to land back on the right step
   after consent.
2. Google redirects back to `/admin/connector-oauth/gdrive/callback` with
   `code` and `state`. The callback validates the state (single-use — cleared
   from the session regardless of outcome), exchanges the code for tokens via
   `DriveAuthCoordinator.completeAuthorization`, and — on success — stores the
   refresh token in the credential store and triggers a best-effort registry
   rebuild.
3. If a `return_to` was captured, the callback redirects there with
   `?oauth=ok` (or `?oauth=unknown_connector|store_disabled|exchange_failed`
   on failure); otherwise it returns a plain JSON status. The authorization
   code and every token are never logged or echoed.

---

## Sync behavior

`SyncOrchestrator` (`wikantik-connectors`) drives every sync: **hash-dedup**
(an item whose content hash already matches the stored sync-state row is
skipped as `unchanged`), **tombstoning** (deleted-at-source items get their
derived page removed and their sync-state row cleared), and **cursor-resume**
(the cursor is persisted after every batch, so a crash mid-drain resumes from
the last completed batch on the next run).

Tombstoning only fires for a **fully-drained, full-corpus** sync
(`SourceConnector.reflectsFullCorpus() == true` — filesystem, crawler, sitemap,
Drive; feed and confluence's per-item listing are windowed/incremental and
handle deletions differently). As a safety guard, a full-corpus connector that
returns a completely empty snapshot while sync state still knows about prior
items is treated as a likely upstream outage, not a genuine wipe — no derived
tombstones are applied that cycle; clear the connector's sync state manually to
force removal if the corpus really did disappear.

### Scheduling

Each connector carries its own **sync interval in hours**
(`sync_interval_hours`; `0` = manual-only). A single due-tick scheduler ticks
every 60 seconds and syncs whichever enabled connectors are due (`last_run +
interval <= now`, and a never-run connector is always due). Interval changes
made in the admin UI take effect immediately — no scheduler restart needed.
Properties-origin connectors use the global
`wikantik.connectors.sync.interval.hours` as their interval.

### Run history

Every sync (manual or scheduled) writes a row to `connector_sync_run`
(migration `V049`) at start and updates it at finish: `trigger_kind`
(`manual`/`scheduled`), `started`/`finished`, `status`
(`running`/`ok`/`failed`), the full `SyncReport` breakdown
(`created`/`updated`/`unchanged`/`deleted`/`failed`), and `error` text on
failure. A row still in `running` state after the process is known to have
restarted is rendered by the UI as **"interrupted"** — the JVM died mid-sync.
History is available at `GET /admin/connectors/{id}/runs?limit=20` and pruned
to the newest 100 rows per connector on insert.

### Dry-run test

`POST /admin/connectors/test` (unsaved payload: `{type, config,
credentials?}`) and `POST /admin/connectors/{id}/test` (a saved connector's
live config) both probe the source without ingesting anything, reusing the
connector's own fetch paths and size/response caps — no new fetch primitive,
no elevated SSRF surface. Per-type semantics: github = fetch the repo + list
the tree head; confluence = list the first page of the space; feed =
fetch+parse each feed head; sitemap = fetch+parse the sitemap(s) and count
URLs; webcrawler = fetch the first seed + probe robots.txt; gdrive = mint an
access token from the refresh token and list the first folder page (only
available post-consent). Transient credentials passed to the unsaved-payload
test are used once and never stored or logged.

---

## Reader-facing provenance

Derived pages carry provenance in frontmatter (stamped by
`DerivedPageSinkAdapter`/`DerivedPageIngestionService`):

- `derived_from` — presence marks the page as derived (the retained source
  attachment filename).
- `derived_connector` — which connector id owns the page.
- `derived_source_url` — a human-clickable origin URL when the source URI
  isn't already one (Drive supplies `webViewLink`, GitHub the `html_url`,
  Confluence the web-UI link; crawler/sitemap/feed source URIs are already
  URLs).
- `derived_orphaned: true` — stamped on every kept page when its owning
  connector is deleted without cascading delete.

On the page itself, `PageView` renders a **provenance banner** (distinct style
from the editor's machine-owned-body warning) whenever `derived_from` is
present: "Synced from *\<source\>* · last synced \<page modified date\> · via
connector *\<name\>*", with "— source no longer syncing" appended when
`derived_orphaned` is set. It degrades gracefully when only `derived_from` is
present (no connector name yet — the frontmatter fills in on the page's next
sync).

Search results, page lists, and the sidebar/cluster tree show a compact **↯
badge** next to the title (tooltip "Synced from an external source"), driven
by a `derived` boolean the REST payloads compute server-side from
`derived_from` — full frontmatter isn't shipped to list/search payloads just
for this.

### Content defaults — creation-time only

A connector can carry optional **cluster**, **default tags**, and **page-name
prefix** settings (`connector_configs.cluster` / `default_tags` /
`page_prefix`). These apply **only when a page is first created** by that
connector — they never overwrite frontmatter you've since curated on that
page. Reflowed/re-synced content keeps the body machine-owned but leaves your
curation (tags, cluster reassignment, etc.) alone.

---

## Kill switch

`wikantik.connectors.enabled` (default `true`) is a pure kill switch, not an
opt-in gate — the runtime always wires (an empty connector set is a harmless
no-op either way). Setting it to `false`:

- Suppresses the due-tick scheduler entirely.
- Rejects `POST /admin/connectors/{id}/sync` with `409`
  (`ConnectorsDisabledException`: "connector syncing disabled by operator
  (wikantik.connectors.enabled=false)").
- Shows the "Connector syncing is disabled by the operator" banner on the list
  page.

Connector configuration CRUD (create/update/delete/import, credential
set/delete) keeps working even while syncing is disabled — this is meant for
pausing a read replica or similar, not for locking down configuration.

---

## Troubleshooting

**A sync run shows `running` forever ("interrupted" in the UI)**

The JVM died mid-sync (crash, redeploy without a clean shutdown). The row is
never explicitly marked failed — a crash leaves an honest `running`-forever
row. It's safe to ignore; the next sync (manual or scheduled) starts a fresh
run. No cleanup action is required.

**A connector's sync fails outright**

`SourceConnector.poll()` is contractually required to **never throw** — every
implementation degrades internal failures (a network error, a missing
credential, an API failure) to an empty/incomplete batch rather than
propagating an exception, so a broken upstream never crashes the sync loop or
the scheduler. Failures still show up: the run's `status` is `failed` with
`error` text, and the connector-specific log line explains why (e.g. "token
lookup failed — skipping sync"). Check `GET
/admin/connectors/{id}/runs?limit=20` first, then the connector's `LOG.warn`
lines in `catalina.out`.

**Creating/updating a connector returns 422 on a field named `token` (or
`client_secret`, `api_token`, `refresh_token`, `password`, `secret`)**

That field name is on the reserved secret-key denylist —
`ConnectorConfigService` refuses to store it in the plaintext `config` column.
Store it instead via `POST /admin/connector-credentials/{id}/{name}` (the
wizard's Authorize step does this for you).

**Confluence sync completes but some pages are missing**

Confluence's page listing skips malformed entries rather than failing the
whole sync: when `skippedMalformed() > 0`, the batch is marked **incomplete**
and **no tombstones are applied that cycle** (a malformed listing can't be
trusted as a full snapshot, so the orchestrator won't delete pages it simply
failed to enumerate). Check the connector's run history / logs for the skipped
count; a well-formed page not previously synced will pick up on a later run
once the upstream issue clears.

**`POST /admin/connectors/{id}/sync` returns 409**

Either syncing is disabled by the kill switch (see above), or a sync of that
same connector is already in progress (`SyncInProgressException` — a manual
trigger can't run concurrently with an in-flight scheduled sync of the same
connector; different connectors sync freely in parallel).

**Properties-origin connector: `PUT`/`DELETE` returns 409**

Properties-defined connectors can only be mutated via **Import**
(`POST /admin/connectors/{id}/import`), which copies them into
`connector_configs` and hands ownership to the DB. Until imported, they are
read-only in the admin UI by design (design D1).

**All connector-credentials endpoints return 503**

`wikantik.connectors.crypto.key` is unset or blank. Generate one with
`openssl rand -base64 32`, set it, and restart — see
[The master key](#the-master-key).

---

## Endpoint reference summary

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/admin/connectors` | List every connector (both origins) |
| `POST` | `/admin/connectors` | Create a DB-origin connector |
| `GET` | `/admin/connectors/{id}` | Connector detail |
| `PUT` | `/admin/connectors/{id}` | Update a DB-origin connector |
| `DELETE` | `/admin/connectors/{id}?deletePages=false` | Delete (default: keep pages, stamp orphaned) |
| `POST` | `/admin/connectors/{id}/sync` | Trigger an immediate sync |
| `GET` | `/admin/connectors/{id}/status` | Current status |
| `GET` | `/admin/connectors/{id}/runs?limit=20` | Sync-run history |
| `GET` | `/admin/connectors/{id}/pages` | Derived pages this connector created |
| `POST` | `/admin/connectors/test` | Dry-run an unsaved `{type, config, credentials?}` payload |
| `POST` | `/admin/connectors/{id}/test` | Dry-run a saved connector's live config |
| `POST` | `/admin/connectors/{id}/import` | Copy a properties-defined connector into the DB |
| `GET` | `/admin/connector-credentials/{id}` | List stored secret names |
| `POST` | `/admin/connector-credentials/{id}/{name}` | Store a secret |
| `DELETE` | `/admin/connector-credentials/{id}/{name}` | Delete a stored secret |
| `GET` | `/admin/connector-oauth/gdrive/{id}/authorize` | Start Google Drive OAuth consent |
| `GET` | `/admin/connector-oauth/gdrive/callback` | Google Drive OAuth callback |

All endpoints are protected by `AdminAuthFilter` (requires `AllPermission`),
same as the rest of `/admin/*`.

## Related

- [Frontmatter.md](Frontmatter.md#derived-page-provenance) — the
  `derived_from`/`derived_connector`/`derived_source_url` frontmatter fields
- [AuditLog.md](AuditLog.md) — connector create/update/delete/import and
  credential set/delete are recorded under category `connector`
- `docs/superpowers/specs/2026-07-15-connector-admin-ui-design.md` — the admin
  UI design doc
- `docs/superpowers/specs/2026-07-11-connector-framework-phase1-design.md` and
  `2026-07-11-connector-framework-phase2-runtime-design.md` — the underlying
  sync engine design

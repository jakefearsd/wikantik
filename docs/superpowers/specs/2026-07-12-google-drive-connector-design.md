# Google Drive Connector Design (P2.3b)

**Status:** approved 2026-07-12
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) — the first **authenticated** source connector, consuming the P2.2 `CredentialStore`.
**Builds on:** the fixed Phase-1 SPI (`SourceConnector.poll(SyncCursor)→SyncBatch`, `SourceItem`, `SyncStateStore`, `DerivedPageSink`), P2.1 (runtime + `/admin/connectors` + `ConnectorWiringHelper`), P2.2 (`CredentialStore` + `/admin/connector-credentials`), and the auth-free connectors in `com.wikantik.connectors.web`.

## Scope

A `DriveSourceConnector` that syncs one or more configured Google **Drive folders** (recursively)
into derived pages: each Google Doc **exported to markdown**, each native `.md`/`.txt` file fetched
directly, other binaries skipped. It is the first connector to **consume** `CredentialStore` —
resolving an OAuth2 **refresh token** at poll time to authenticate the Drive API. Full-corpus per
folder set (`reflectsFullCorpus() = true`): a file removed from a synced folder tombstones its derived
page.

v1 **includes** an in-app OAuth2 **consent flow** so an operator obtains and stores the refresh token
entirely through the admin surface — no out-of-band token dance.

**Non-goals (v1):** the Drive **Changes API** for incremental sync (v1 walks the folder each poll — a
follow-up); binaries via Tika (skip non-text/non-Doc files); per-file Drive **ACL** mapping
(`aclRefs = []`); domain-wide delegation / service-account auth (chosen model is OAuth2 user refresh
token); syncing anything outside the configured folder IDs.

## Decisions (owner-confirmed)

1. **Auth model:** OAuth2 **user refresh token** (not a service account). The app identity
   (`client_id` + `client_secret`) lives in **config**, exactly as the existing Google SSO client
   secret already does on docker1 (same trust boundary). The per-connector **refresh token** is the
   *secret* stored encrypted in `CredentialStore`, obtained via the in-app consent flow.
2. **Libraries:** use the **Google client libraries** (`google-api-services-drive`,
   `google-auth-library-oauth2-http`, `google-api-client`, gson JSON factory) rather than hand-rolling
   OAuth. A new dependency tree in `wikantik-connectors` is accepted for robustness.
3. **Content:** sync configured folder IDs recursively — export Google Docs to markdown, fetch native
   `.md`/`.txt` directly, skip other binaries.
4. **Consent flow:** built in v1 (see below).

## Architecture

### `wikantik-connectors` — new package `com.wikantik.connectors.gdrive`

The web connectors live in `com.wikantik.connectors.web` (HTML + robots); Drive is a distinct
protocol/auth, so it gets its own package. (Invariant #6 forbids new packages only in *wikantik-main*,
not in wikantik-connectors.)

```
DriveConfig          record( List<String> folderIds, int maxFiles, String clientId, String clientSecret,
                             String redirectUri, String exportMimeType /* text/markdown */ )
DriveFile            record( String id, String name, String mimeType, String modifiedTime, String webViewLink )
DriveApi (interface) List<DriveFile> listFolder( String folderId );   // children, non-trashed
                     byte[] export( String fileId, String mime );      // Google Doc → markdown bytes
                     byte[] getMedia( String fileId );                 // native file bytes
                     // injectable → tests use a fake; no network, no Google libs on the test path
DriveApiFactory      DriveApi create( String clientId, String clientSecret, String refreshToken )
GoogleDriveApi       implements DriveApi — wraps google-api-services-drive + UserCredentials
DriveItems           SourceItem toItem( DriveFile f, byte[] bytes )   — mirrors WebFetchItems
DriveSourceConnector implements SourceConnector — poll() below; reflectsFullCorpus() = true

DriveOAuthService (interface)  String authorizationUrl( String clientId, String redirectUri, String state );
                               String exchangeCodeForRefreshToken( String clientId, String clientSecret,
                                       String redirectUri, String code );  // throws on failure; NEVER logs code/token
GoogleDriveOAuthService        implements DriveOAuthService — Google client (drive.readonly scope,
                                       access_type=offline, prompt=consent)
DefaultDriveAuthCoordinator    implements DriveAuthCoordinator (api) — see below
```

**The connector depends only on** `DriveConfig`, a `Supplier<Optional<String>>` (the refresh-token
resolver = `() -> credentialStore.get(id,"refresh_token")`), and a `DriveApiFactory` — **not** on the
Google libraries or on `CredentialStore` directly. So it is fully unit-testable with a fake factory +
fake supplier; the heavy Google deps load only behind `GoogleDriveApi`/`GoogleDriveOAuthService`.

### `wikantik-api` — additive contract

```
DriveAuthCoordinator (interface, com.wikantik.api.connectors) — the seam wikantik-rest calls:
    Optional<String> authorizationUrl( String connectorId, String state );   // empty: unknown id
    boolean completeAuthorization( String connectorId, String code );        // exchange + store refresh token; false on failure
```

`DefaultDriveAuthCoordinator` (wikantik-connectors) holds `Map<String,DriveConfig>` (by id, for the
client creds + redirect URI), a `DriveOAuthService`, and the `CredentialStore`. `completeAuthorization`
exchanges the code and calls `store.put(connectorId, "refresh_token", token)`. It is registered on the
engine as a manager by `ConnectorWiringHelper` (`setManager`), resolved by the rest resource via
`getEngine() instanceof WikiEngine we ? we.getManager(DriveAuthCoordinator.class) : null` (wikantik-rest
is not scanned by the frozen `getManager` ArchUnit rule — established by `ConnectorAdminResource`).

### `wikantik-rest` — `GoogleDriveAuthResource`

Thin HTTP shell at **`/admin/connector-oauth/gdrive/*`** (a distinct sibling of
`/admin/connector-credentials/*`; deliberately NOT under `/admin/connectors/*`, which
`ConnectorAdminResource` already owns — avoids any path-prefix-precedence subtlety). `AdminAuthFilter`
already covers `/admin/*`.

- `GET /admin/connector-oauth/gdrive/{id}/authorize` → `coordinator.authorizationUrl(id, state)`;
  generates a random `state` nonce, stores `state` + `id` in the operator's `HttpSession`, and 302s to
  the returned Google consent URL. Unknown id / coordinator absent → 404 / 503.
- `GET /admin/connector-oauth/gdrive/callback?code=&state=` → verifies `state` against the session
  (single-use — cleared after; CSRF defense), then `coordinator.completeAuthorization(id, code)` →
  stores the refresh token → 200/redirect "authorized". `state` mismatch/missing → 400 (no exchange);
  exchange failure → 502; store disabled → 503. The `code` and tokens appear in NO response and NO log.

### `wikantik-main` — `ConnectorWiringHelper` growth only

`driveConfigs(Properties)` (sibling to `sitemapConfigs`/`feedConfigs`) + a wiring loop that, per gdrive
`<id>`: builds the `DriveSourceConnector` with a refresh-token supplier bound to the already-registered
`CredentialStore`, a real `GoogleDriveApiFactory`, and registers it in the `ConnectorRegistry` (type
`"gdrive"`). It also builds one `DefaultDriveAuthCoordinator` over all gdrive configs +
`GoogleDriveOAuthService` + the store, and `engine.setManager(DriveAuthCoordinator.class, coordinator)`.
The wiring early-return guard is extended to consider gdrive configs.

## `poll(SyncCursor)` data flow

```
refreshToken = refreshTokenSupplier.get()                     // () -> credentialStore.get(id,"refresh_token")
if empty → LOG.warn("no refresh_token / store disabled"), return SyncBatch([],[],cursor,complete=true)   // fail-closed
api = driveApiFactory.create( config.clientId, config.clientSecret, refreshToken )
files = []; for folderId in config.folderIds: walk( folderId, files )   // recurse subfolders, cap at maxFiles
items = []
for f in files (until maxFiles):
    if f.mimeType == "application/vnd.google-apps.document" → bytes = api.export(f.id, config.exportMimeType); ct = config.exportMimeType
    elif f.mimeType in {text/markdown, text/x-markdown, text/plain}   → bytes = api.getMedia(f.id);            ct = f.mimeType
    else → LOG.info(skip); continue
    items.add( DriveItems.toItem(f, bytes) )   // sourceUri="gdrive://{id}", ct, metadata{id,name,mimeType,modifiedTime,webViewLink}, contentHash=sha256(bytes)
if items.size() >= maxFiles → LOG.info("hit max_files, truncated")
return SyncBatch( items, [], new SyncCursor(String.valueOf(items.size())), complete=true )

reflectsFullCorpus() → true      // folder sync: a removed file tombstones its derived page
```

`walk(folderId, out)` lists the folder; for each child that is a folder
(`application/vnd.google-apps.folder`) recurse; else add to `out`. Guard against `maxFiles` and
against cycles/duplicate ids with a visited set.

## Fail-closed / error handling (poll() never throws — SPI contract)

- No master key or no stored refresh token → empty complete batch + `LOG.warn`; the factory is **never
  called** (no wasted token exchange).
- Token refresh fails (revoked/expired) or any Drive API error → caught, `LOG.warn` (id + message
  only), empty complete batch — a background sync cycle is skipped, nothing breaks.
- **Egress:** the connector needs egress to `oauth2.googleapis.com` + `www.googleapis.com`. If the host
  loses egress (cf. the SSO OIDC egress trap), poll fails gracefully that cycle — it is the scheduler,
  not a request path.
- Connector is **skipped at wiring** (with a warn) if `client_id`/`client_secret`/`folder_ids`/
  `redirect_uri` are unconfigured — same posture as the other connectors.
- **Secret hygiene:** the refresh token, access token, client secret, and OAuth `code` appear in no log
  line, no exception message, and no derived-page content. The consent `state` nonce may be logged
  (it is not a secret).

## Config (`wikantik.connectors.gdrive.<id>.`)

| Key | Default | Meaning |
|---|---|---|
| `folder_ids` | — (required) | comma-separated Drive folder IDs synced recursively |
| `client_id` | — (required) | OAuth2 app client ID |
| `client_secret` | — (required) | OAuth2 app client secret |
| `redirect_uri` | — (required) | OAuth2 callback; must equal the URI registered in the Google Cloud OAuth client **and** the deployed `/admin/connector-oauth/gdrive/callback` URL (explicit to avoid the `getBaseURL()` context-path trap) |
| `max_files` | `500` | cap on files synced per poll |
| `export_mime` | `text/markdown` | Google Docs export format |

The **refresh token is not a config key** — it is obtained via the consent flow and stored in
`CredentialStore` under name `refresh_token`. Registry `type = "gdrive"`. The commented config block is
added to `ini/wikantik.properties` (mirroring the other connectors), including the
`wikantik.connectors.crypto.key`-required note (credential storage must be enabled for the consent flow
to persist the token).

## New dependencies (wikantik-connectors; versions pinned in parent `dependencyManagement`)

`com.google.apis:google-api-services-drive`, `com.google.auth:google-auth-library-oauth2-http`,
`com.google.api-client:google-api-client`, and a gson HTTP JSON factory. All Apache-2.0 (RAT-safe).
They load only in wikantik-connectors (and transitively wikantik-main, which already depends on it). No
other module gains a Google dependency — wikantik-rest stays clean behind the `DriveAuthCoordinator`
seam.

## Testing (unit-first, injected fakes — no network, no Google libs on the test path)

| Test | Proves | Where |
|---|---|---|
| `DriveSourceConnectorTest` | fake factory + fake `DriveApi` serving a folder tree: Docs→markdown, native md/txt fetched, other mimeTypes skipped, subfolder recursion, `max_files` cap, `reflectsFullCorpus()==true`; **empty refresh-token supplier → empty batch, factory never called**; token-refresh error → empty batch, no throw | gdrive |
| `DriveItemsTest` | `SourceItem` shape (`gdrive://{id}` uri, metadata, `text/markdown`, sha256, `aclRefs=[]`) | gdrive |
| `DefaultDriveAuthCoordinatorTest` | fake `DriveOAuthService` + stub `CredentialStore`: `authorizationUrl` for a known id (unknown → empty); `completeAuthorization` exchanges + `store.put(id,"refresh_token",token)`; exchange failure → false, nothing stored; store disabled → false | gdrive |
| `GoogleDriveAuthResourceTest` | authorize → 302 with a `Location` to the consent URL + `state` stored in session; callback with matching `state` → `completeAuthorization` called → 200/redirect; **mismatched/missing `state` → 400, no exchange**; the `code`/token never in a response body or log | wikantik-rest |
| `ConnectorWiringHelper` gdrive-config test | `gdrive.<id>.*` → registered connector + `DriveAuthCoordinator` registered; missing client creds/folders/redirect → skipped; refresh-token resolver wired to the store | wikantik-main |

No live IT — the P2.1 `ConnectorAdminIT` already exercises the Cargo/runtime/admin path; the Google API
and OAuth service are faked (a live Google IT would need real credentials + network).

## Invariants respected

- **Fixed Phase-1 SPI + P2.1 runtime + P2.2 store surfaces unchanged** — the Drive connector is a
  purely additive *consumer* of `CredentialStore`; `reflectsFullCorpus()` already exists;
  `DriveAuthCoordinator` is a new additive contract.
- **#6 `wikantik-main` grows no new package** — connector + OAuth + coordinator impl in
  wikantik-connectors (new `com.wikantik.connectors.gdrive`), contract in wikantik-api, resource in
  wikantik-rest, wiring-only growth in wikantik-main's existing `com.wikantik.derived`.
- **Fail-closed** throughout; `poll()` never throws; missing/invalid credentials degrade to an empty
  batch; the consent callback fails closed on `state` mismatch.
- **PostgreSQL-first** — reuses the V046 sync-state tables; **no schema change**.
- **Default-off** — no `gdrive.*` keys ⇒ no connector, no coordinator wired.
- **Secret hygiene** — client secret, refresh token, access token, and OAuth code never logged or
  echoed; the refresh token is encrypted at rest by the P2.2 store.

## Open questions (resolve during planning, not blocking)

1. **Drive Docs→markdown export** — confirm `files.export(fileId, "text/markdown")` is supported by
   the Drive v3 API for Google Docs (fallback `text/plain` if a given file/type rejects markdown).
2. **`state` storage** — HTTP session is simplest and the operator is authenticated; confirm the admin
   session is available in the callback (top-level GET redirect from Google, under `/admin/*` →
   `AdminAuthFilter` requires the session — verify the redirect round-trip preserves it, else fall back
   to a signed/HMAC state).
3. **Version pinning** — pick a single coherent Google client BOM/version set in
   `dependencyManagement` to avoid transitive conflicts (guava/http-client).

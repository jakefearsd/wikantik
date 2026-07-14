# GitHub + Confluence Connectors (P2.3c) — Design

**Date:** 2026-07-14
**Status:** Approved (user: "Both GitHub and Confluence with same pattern"; spec approved this session)
**Depends on:** Phase 1 SPI, P2.1 runtime, P2.2 CredentialStore, and the 2026-07-14 cleanup wave
(untrusted-enumeration contract `57ab3b55eb`, size caps `2586d8fedb`, sync lock `4a0243a07e`).

## Goal

Two authenticated, full-corpus source connectors — GitHub (markdown files in a repo) and
Confluence Cloud (pages in a space) — mirroring the Drive consumer pattern minus the OAuth
consent flow: both use **static tokens** injected once via the existing
`POST /admin/connector-credentials/{id}/{name}` endpoint and resolved **lazily per-poll** from the
`CredentialStore`. One combined spec + plan: the two connectors are structural siblings.

## What is deliberately simpler than Drive

| Drive (P2.3b) | GitHub / Confluence (this) |
|---|---|
| OAuth2 refresh token + in-app consent flow | Static token (PAT / API token) — no consent flow |
| `DriveAuthCoordinator` contract in wikantik-api | **No new wikantik-api type** |
| `GoogleDriveAuthResource` in wikantik-rest | **No new REST resource** |
| Google client libraries | **Zero new dependencies** (`java.net.http` + gson, both already present) |

## Consumer pattern (normative — copied from Drive, hardened by the cleanup wave)

1. Connector depends only on: its Config record + a `Supplier<Optional<String>>` token resolver
   (`() -> credStore.get(id, "<name>")`, resolved lazily per-poll because credentials are injected
   via admin AFTER startup) + an injectable `*Api` interface (real HTTP impl behind a `*ApiFactory`,
   faked in unit tests — no network).
2. **poll() never throws** (`SyncOrchestrator.sync` calls it without try/catch). The factory call
   lives INSIDE the try block (the Drive T3 Critical).
3. **Untrusted-enumeration contract** (`SourceConnector.poll` javadoc): missing/blank token, any
   API/HTTP failure, or a truncated listing → return `complete=false` with the **input** cursor —
   never an empty/partial `complete=true` batch (would mass-tombstone derived pages). An
   authoritative per-item 404/410 does NOT taint the batch. Both connectors
   `reflectsFullCorpus()==true`, so this is load-bearing.
4. Secret hygiene: the token appears in NO log message, NO exception message that gets logged,
   NO response, NO derived-page content. HTTP-layer exceptions are rethrown with fixed strings
   when they could embed credentials (Drive T5 precedent; for Basic auth, never log request
   headers or the URL userinfo — neither impl puts credentials in URLs).
5. Response caps: every HTTP read goes through a capped body subscriber (OOM defense).
6. Empty token → empty incomplete batch, **API factory never called**.

## Components (all in `wikantik-connectors` unless noted)

### Shared: `com.wikantik.connectors.http.CappedBodySubscriber`

Extract `HttpPageFetcher`'s private `CappedByteArraySubscriber` into a package-public shared class
(new package `com.wikantik.connectors.http`); `HttpPageFetcher` and both new API impls use it.
Behavior unchanged: accumulate up to `max` bytes; exceeding the cap cancels the exchange and
completes the body exceptionally (→ `IOException` from `client.send`). Cap for both connectors:
**10 MiB per response** (constant, mirroring `HttpPageFetcher.DEFAULT_MAX_BODY_BYTES`).

### GitHub — `com.wikantik.connectors.github`

- **`GithubConfig`** record: `( String repo /* "owner/name" */, String branch /* nullable → repo
  default branch */, String pathPrefix /* nullable → whole tree */, int maxFiles /* default 500 */ )`.
- **`GithubFile`** record: `( String path, String sha, long size )` (from the tree listing).
- **`TreeListing`** record: `( List<GithubFile> files, boolean truncated )`.
- **`GithubApi`** interface (injectable seam):
  - `String defaultBranch() throws IOException` — `GET /repos/{repo}` → `default_branch`.
  - `TreeListing listTree( String branch ) throws IOException` —
    `GET /repos/{repo}/git/trees/{branch}?recursive=1` → blobs only; carries the API's
    `truncated` flag (GitHub truncates at 100k entries / 7 MB).
  - `Optional<byte[]> rawContent( String path, String branch ) throws IOException` —
    `GET /repos/{repo}/contents/{path}?ref={branch}` with `Accept: application/vnd.github.raw+json`.
    **Returns `Optional.empty()` on 404** (file deleted between listing and fetch — authoritative
    absence, skip without tainting); throws on any other non-2xx.
- **`GithubApiFactory`**: `GithubApi create( String repo, String token )`.
- **`HttpGithubApi`** (package-private, built by `HttpGithubApiFactory`): `java.net.http` + gson.
  Headers: `Authorization: Bearer {token}`, `Accept: application/vnd.github+json` (raw variant for
  contents), `X-GitHub-Api-Version: 2022-11-28`, User-Agent `Wikantik-Connector/1.0`. 20 s timeout,
  capped bodies.
- **`GithubSourceConnector`** `poll()`:
  1. Resolve token; empty/blank → warn + `SyncBatch(List.of(), List.of(), cursor, false)`, factory
     never called.
  2. Inside try: create api; resolve branch (config or `defaultBranch()`); `listTree(branch)`;
     filter to paths ending `.md` (case-insensitive) and, when `pathPrefix` set, starting with it;
     fetch up to `maxFiles` via `rawContent` (empty Optional → skip, no taint); build items.
  3. `truncated==true` → deliver fetched items but `complete=false` + input cursor (partial listing
     = untrusted snapshot).
  4. Any exception → warn (message only) + empty incomplete batch with input cursor.
  5. Success → `complete=true`, count cursor (mirrors Drive).
- **`GithubItems`** (package-private builder, mirrors `DriveItems`): URI
  `github://{owner}/{repo}/{path}` (branch is fixed per connector — not in the URI, so a branch
  reconfiguration re-syncs in place); content type `text/markdown`; sha256 content hash;
  `aclRefs=[]`; metadata keys mirror DriveItems' convention: `id`=repo-relative path,
  `name`=filename, `mimeType`=`text/markdown`, `webViewLink`=
  `https://github.com/{repo}/blob/{branch}/{path}`; no `modifiedTime` (the tree listing has none —
  key simply absent). The plan pins exact key strings against `DriveItems` at implementation time.
- Credential name: **`"token"`** (a GitHub fine-grained PAT with read-only Contents scope, or a
  classic PAT with `repo` read).

### Confluence Cloud — `com.wikantik.connectors.confluence`

- **`ConfluenceConfig`** record: `( String baseUrl /* e.g. https://acme.atlassian.net */,
  String spaceKey, String email, int maxPages /* default 500 */ )`.
- **`ConfluencePage`** record: `( String id, String title, int version, String webuiPath,
  String storageXhtml )`.
- **`ConfluenceApi`** interface:
  - `List<ConfluencePage> listPages( int maxPages ) throws IOException` — v2 REST:
    `GET {base}/wiki/api/v2/spaces?keys={spaceKey}` → space id, then
    `GET {base}/wiki/api/v2/spaces/{id}/pages?body-format=storage&limit=50` following
    `_links.next` cursors until exhausted or `maxPages`. Any non-2xx anywhere → `IOException`
    (enumeration-source failure always taints — same rule as a sitemap fetch failure).
- **`ConfluenceApiFactory`**: `ConfluenceApi create( String baseUrl, String email, String apiToken )`.
- **`HttpConfluenceApi`**: `java.net.http` + gson, `Authorization: Basic base64(email:apiToken)`,
  20 s timeout, capped bodies.
- **`ConfluenceSourceConnector`** `poll()`: same skeleton as GitHub (empty token → incomplete empty
  batch, factory inside try, exception → incomplete empty batch, success → `complete=true`).
  Items are built from the listing directly (body ships with the page — no per-item fetch, so no
  per-item 404 case).
- **`ConfluenceItems`**: URI `confluence://{spaceKey}/{pageId}`; **content type `text/html`** with
  the storage-format XHTML as the body — the existing ingest path (`TikaSourceExtractor` supports
  `text/html` since the web-crawler work) converts it to markdown. **This supersedes the earlier
  sketch of adding flexmark-html2md to wikantik-connectors: no new dependency, one conversion
  path.** Trade-off accepted for v1: Confluence `<ac:*>` macro elements degrade to their text
  content (macro fidelity is a non-goal). Metadata: `id`=page id, `name`=title,
  `mimeType`=`text/html`, `version`=page version number (no `modifiedTime` key — the v2 listing
  carries a version, not a cheap timestamp), `webViewLink`=`{base}/wiki{webuiPath}`.
- Credential name: **`"api_token"`** (an Atlassian API token; the account email lives in config —
  it is an identifier, not a secret).

### Wiring — `ConnectorWiringHelper` (wikantik-main)

- `githubConfigs(Properties)` — anchor `wikantik.connectors.github.<id>.repo`; skip an id whose
  `repo` is blank or not `owner/name` shaped. Optional `branch`, `path_prefix`, `max_files`.
- `confluenceConfigs(Properties)` — anchor `wikantik.connectors.confluence.<id>.space_key`; skip
  an id missing `base_url` or `email` (mirrors drive's skip-missing-required-fields).
- Both loops mirror the gdrive loop exactly: per-iteration `final String id = e.getKey()` capture;
  token supplier `() -> credStore.get(id, "token")` / `"api_token"`; `typeById` `"github"` /
  `"confluence"`; the nothing-to-sync emptiness guard gains both maps. `setManager`-only (no
  `getManager` — DecompositionArchTest R-2). gson becomes a direct (parent-managed, version-less)
  dependency of wikantik-connectors — it is now used directly, not just transitively via the
  Google stack.

### Config block — `ini/wikantik.properties`

```properties
# GitHub connector: markdown files from a repository tree.
# Token: POST /admin/connector-credentials/{id}/token   (fine-grained PAT, read-only Contents)
#wikantik.connectors.github.myrepo.repo = acme/handbook
#wikantik.connectors.github.myrepo.branch =            # default: the repo's default branch
#wikantik.connectors.github.myrepo.path_prefix =       # default: whole tree
#wikantik.connectors.github.myrepo.max_files = 500

# Confluence Cloud connector: pages from a space (storage XHTML -> markdown via ingest).
# Token: POST /admin/connector-credentials/{id}/api_token   (Atlassian API token)
#wikantik.connectors.confluence.acme.base_url = https://acme.atlassian.net
#wikantik.connectors.confluence.acme.space_key = ENG
#wikantik.connectors.confluence.acme.email = bot@acme.com
#wikantik.connectors.confluence.acme.max_pages = 500
```

## Error handling summary (both connectors)

| Condition | Behavior |
|---|---|
| No/blank token | warn, empty batch, `complete=false`, input cursor, factory NOT called |
| Factory throws | caught → warn (message only), empty batch, `complete=false`, input cursor |
| Any API/HTTP/parse failure | same as factory-throws |
| GitHub tree `truncated` | deliver fetched items, `complete=false`, input cursor |
| GitHub per-file 404 | skip file, NO taint (authoritative absence) |
| Response body > 10 MiB | `IOException` from capped subscriber → cycle aborts as API failure |
| `maxFiles`/`maxPages` hit | info log, truncate, still `complete=true` (deliberate cap, matches Drive) |

Note the cap-hit case: like Drive's `max_files`, hitting the configured cap keeps `complete=true` —
the orchestrator may tombstone beyond-cap items. This is existing, deliberate semantics: the cap
defines the mirrored corpus. Operators size the cap above the source.

## Testing (unit only; no live IT — same stance as Drive, real-credential ITs deferred)

Per connector, mirroring `DriveSourceConnectorTest` with a `FakeApi`:
- item shape (URI, content type, hash, metadata) and cap enforcement;
- empty-token → incomplete empty batch, factory never called;
- API-error and factory-throw → `assertDoesNotThrow`, incomplete empty batch, input cursor;
- GitHub: truncated-tree taint; per-file-404 skip without taint; `.md`/prefix filtering;
  default-branch resolution used only when `branch` unset;
- Confluence: pagination follows `next` links; enumeration failure mid-pagination taints;
- `reflectsFullCorpus()` true;
- `HttpGithubApi`/`HttpConfluenceApi` request-shaping tests against a local
  `com.sun.net.httpserver.HttpServer` (auth header present, 404 → empty Optional, non-2xx → throws,
  pagination) — no external network;
- wiring: `githubConfigs`/`confluenceConfigs` parse tests mirroring `driveConfigsParses…`/`…Skipped…`;
- `CappedBodySubscriber` extraction covered by the existing `HttpPageFetcherTest` cap tests.

## Non-goals (v1)

- GitHub wikis (no REST endpoint — separate git repo, needs a clone), issues, PRs, releases.
- Confluence Server/DC (v2 API is Cloud), attachments, blog posts, comments, restrictions→ACL
  mapping (`aclRefs=[]` like every connector so far), macro fidelity.
- Incremental sync (GitHub commits-since / Confluence CQL lastmodified) — follow-up, alongside
  Drive Changes API.
- Rate-limit backoff (5000 req/h authenticated GitHub is ample at these caps).
- Credential key rotation (still the P2.2 deferred item).

## Definition of done

1. All unit tests above green; full reactor `mvn clean install -DskipITs` (WIKANTIK_* unset) green.
2. Zero new dependencies beyond declaring gson directly (parent-managed).
3. Invariant #6 intact: no vendor/API types outside wikantik-connectors; wiring growth in
   wikantik-main only; no wikantik-api / wikantik-rest changes.
4. Secret-hygiene review point: grep-level check that no log/exception path can carry the token.
5. `/admin/connectors` lists both types with status; manual sync + 409-when-busy work unchanged.

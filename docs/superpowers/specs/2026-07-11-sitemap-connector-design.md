# Sitemap Connector Design

**Status:** approved 2026-07-11
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) — a second auth-free external connector, sibling to the web crawler.
**Builds on:** the shipped connector framework (Phase 1 SPI + orchestrator + state), P2.1 (runtime + `/admin/connectors` + `ConnectorWiringHelper`), and the web crawler (`com.wikantik.connectors.webcrawler`: `PageFetcher`/`HttpPageFetcher`/`RobotsPolicy`/`FetchResult`/`LinkExtractor`, `text/html` in `TikaSourceExtractor`).

## Scope

A `SitemapSourceConnector` that reads a site's `sitemap.xml` and syncs each listed URL into a derived page. Maximum reuse of the web crawler's fetch/robots/politeness primitives; the only new logic is sitemap-XML parsing. Auth-free, default-off, fail-closed.

**Non-goals:** BFS link-following (that's the crawler); authentication; conditional-GET/ETag incremental fetch (full re-sync + hash-dedup); sitemap `lastmod`-based skipping; JavaScript rendering; a live-network integration test.

## Architecture

### Placement (minimal churn — reuse the crawler's package)

The crawler's fetch primitives (`PageFetcher`, `HttpPageFetcher`, `RobotsPolicy`, `FetchResult`) are **package-private** in `com.wikantik.connectors.webcrawler`. The sitemap connector lives in that **same package** so it reuses them directly — no visibility changes, no moving just-shipped classes. (The package name is now generic for two web connectors + shared primitives; a `webcrawler`→`web` rename is an optional future cleanup, out of scope here.)

```
com.wikantik.connectors.webcrawler (existing package, in wikantik-connectors):
  NEW:
    ParsedSitemap        record(List<String> locs, boolean isIndex)
    SitemapParser        static ParsedSitemap parse(String xml)  — jsoup XML parser; never throws
    SitemapConfig        record(List<String> sitemapUrls, int maxPages, long delayMs,
                                String userAgent, boolean respectRobots, boolean sameHostOnly)
    SitemapSourceConnector implements SourceConnector  — poll() reads sitemaps → fetches pages → items
    WebFetchItems        static String sha256Hex(byte[]);
                         static SourceItem toItem(String url, FetchResult r)   (EXTRACTED — shared)
  REFACTORED:
    WebCrawlerSourceConnector — its private item()/sha256Hex delegate to WebFetchItems
                                (its 12 existing tests are the safety net)
  REUSED unchanged:
    PageFetcher, HttpPageFetcher, FetchResult, RobotsPolicy, LinkExtractor.title

wikantik-main (existing com.wikantik.derived.ConnectorWiringHelper — config-parse growth only):
    sitemapConfigs(Properties) + a sitemap loop building SitemapSourceConnector(s)
```

### `SitemapParser` (the one genuinely new piece)

jsoup's XML parser (jsoup is already a dependency): `Jsoup.parse(xml, "", Parser.xmlParser())`. Branch on the root element:
- root `<sitemapindex>` → `ParsedSitemap(subSitemapLocs, isIndex=true)` (the `<sitemap><loc>` entries point to sub-sitemaps).
- root `<urlset>` (or anything else with `<loc>`) → `ParsedSitemap(pageLocs, isIndex=false)` (the `<url><loc>` entries are pages).
- Extract each `<loc>`'s text, trimmed, non-blank. Malformed/empty XML → `ParsedSitemap(List.of(), false)` (never throws; `LOG.warn` on parse failure).

### `WebFetchItems` (extracted shared helper — DRY for two web connectors)

```java
static String sha256Hex( byte[] bytes );                 // moved from the connectors' private copies
static SourceItem toItem( String url, FetchResult r );   // text/html SourceItem:
    // sourceUri = url (the finalUrl the caller passes), content = r.body(), contentType = "text/html",
    // sourceMetadata = { url, title (LinkExtractor.title(body)), fetchedAt (Instant.now), httpStatus },
    // aclRefs = [], contentHash = sha256Hex(r.body())
```
`WebCrawlerSourceConnector` is refactored to call `WebFetchItems.toItem(finalUrl, r)` instead of its private `item(...)`, and `WebFetchItems.sha256Hex` instead of its private one. Behavior is identical — the crawler's 12 tests must stay green (byte-identical `SourceItem` shape).

### `SitemapSourceConnector.poll(SyncCursor)`

```
robots = new RobotsPolicy(fetcher, config.userAgent())
pageUrls = new LinkedHashSet()
for sm in config.sitemapUrls():
    collectPages(sm, robots, pageUrls, depth=0)     // fetch+parse; recurse indexes ONE level
items = []; visited = new HashSet()
for url in pageUrls:
    if items.size() >= maxPages: break
    if config.sameHostOnly() && host(url) != host(the sitemap that listed it): continue   // see note
    if config.respectRobots() && !robots.isAllowed(url): LOG.info(skip); continue
    if !visited.add(url): continue
    sleepPolitely(robots, url)                        // max(delayMs, respectRobots? robots.crawlDelayMs : 0)
    r = fetcher.fetch(url)                             // fail-closed (status 0 on error)
    if r.status()/100 != 2 || !isHtml(r.contentType()): continue
    items.add(WebFetchItems.toItem(finalUrl(r, url), r))
return SyncBatch(items, [], new SyncCursor(String.valueOf(items.size())), complete=true)

collectPages(sitemapUrl, robots, out, depth):
    if config.respectRobots() && !robots.isAllowed(sitemapUrl): return   // robots applies to sitemap fetch too
    r = fetcher.fetch(sitemapUrl); if r.status()/100 != 2: return
    parsed = SitemapParser.parse(new String(r.body(), UTF_8))
    if parsed.isIndex():
        if depth == 0:                                // recurse ONE bounded level
            for sub in parsed.locs(): collectPages(sub, robots, out, depth+1)
        else: LOG.info("nested sitemap index beyond one level ignored: {}", sitemapUrl)
    else:
        out.addAll(parsed.locs())
```

**same-host note:** "host of the sitemap that listed it" — simplest correct rule: filter page URLs whose host differs from the host of the **configured seed sitemap URL** they descend from. Implementation keeps the seed host in scope; a page `<loc>` on a foreign host is dropped when `sameHostOnly` (default true) — defends against a sitemap that lists other sites. (Sub-sitemaps from an index are expected same-host; the same filter applies to their pages.)

### Fail-closed

- `fetcher.fetch` never throws (returns status 0 on error) — non-2xx sitemap or page → skip.
- `SitemapParser.parse` never throws — malformed XML → empty loc list + `LOG.warn`.
- A sitemap-index nested beyond one level → the deeper index is logged and ignored (bounded recursion, no stack risk).
- `maxPages` truncation is logged (no silent cap).
- `poll()` has no throwing path.

### Wiring & config

`ConnectorWiringHelper` gains `sitemapConfigs(Properties)` (sibling to `webcrawlerConfigs`) reading, per `<id>`:

| Property (`wikantik.connectors.sitemap.<id>.`) | Default | Meaning |
|---|---|---|
| `sitemap_urls` | — (required) | comma-separated sitemap.xml URLs (registering `<id>` requires at least one) |
| `max_pages` | `500` | cap on pages fetched per sync (sitemaps can be large — higher than the crawler's default) |
| `delay_ms` | `1000` | politeness delay between page fetches |
| `user_agent` | `WikantikCrawler/1.0 (+https://wiki.wikantik.com)` | sent on every request |
| `respect_robots` | `true` | honor robots.txt (+ crawl-delay) for sitemap and page fetches |
| `same_host_only` | `true` | drop page `<loc>`s whose host differs from the seed sitemap's host |

The registry `type` label is `"sitemap"`; `connectorId()` = `<id>`. Runtime/scheduler/admin surface are the shipped P2.1 pieces unchanged. Update the wiring's early-return guard to also consider sitemap configs (a sitemap-only config must wire).

## Testing (unit-first, injected fetcher — no network)

| Test | Proves | Where |
|---|---|---|
| `SitemapParserTest` | `<urlset>`→page locs (isIndex=false); `<sitemapindex>`→sub-sitemap locs (isIndex=true); malformed/empty→empty; trims/drops-blank `<loc>` | webcrawler |
| `WebFetchItemsTest` | `sha256Hex` (64-hex); `toItem` produces the exact text/html SourceItem shape ({url,title,fetchedAt,httpStatus}, aclRefs=[], hash) | webcrawler |
| `SitemapSourceConnectorTest` | fake `PageFetcher` serving a sitemap + pages: emits one item per listed URL; **index recursion** (index→sub-sitemaps→pages); `same_host_only` drops foreign locs; `max_pages` cap; robots-disallowed page skipped; non-2xx/non-html page skipped; nested-index-beyond-one-level ignored; one complete batch | webcrawler |
| `WebCrawlerSourceConnectorTest` (existing 12) | unchanged + green — proves the `WebFetchItems` refactor is behavior-preserving | webcrawler |
| `ConnectorWiringHelper` sitemap-config test | `sitemap.<id>.sitemap_urls` → registered connectors; missing urls → skipped; defaults applied; sitemap-only config wires | wikantik-main |

No live-network IT (same rationale as the crawler; the P2.1 `ConnectorAdminIT` already covers the runtime/admin path).

## Invariants respected

- **Fixed Phase-1 SPI + P2.1 runtime:** the sitemap connector is only a new `SourceConnector`; no change to `SyncOrchestrator`/`SyncStateStore`/`DerivedPageSink`/`ConnectorRuntime`/`ConnectorAdminResource`.
- **#6 `wikantik-main` grows no new package:** connector + parser in `wikantik-connectors`; `wikantik-main` gains only `ConnectorWiringHelper` config-parse growth. No new deps (jsoup already present).
- **Fail-closed:** every external interaction degrades to skip + log; `poll()` never throws.
- **PostgreSQL-first / no schema change:** sync state is the existing V046 tables via the orchestrator; full re-sync + hash-dedup.
- **Default-off:** no `sitemap.*` keys ⇒ no connector registered.

## Open questions (resolve during planning, not blocking)

1. **`WebFetchItems` location** — in `com.wikantik.connectors.webcrawler` (chosen) keeps it beside the primitives it uses. A future `webcrawler`→`web` package rename would relocate it; not now.
2. **`finalUrl` vs listed URL for the item's `sourceUri`** — use the post-redirect `finalUrl` (matches the crawler) so `derived_from` reflects where content actually came from; note the crawler's known redirect-vs-visited dedup edge applies equally (mitigated by content-hash dedup).
3. **Same-host rule precision** — the seed sitemap's host is the scope anchor; confirm the parser/connector threads the seed host through index recursion so a sub-sitemap's foreign-host pages are still filtered.

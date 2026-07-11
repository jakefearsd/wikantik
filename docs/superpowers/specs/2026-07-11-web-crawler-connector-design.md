# Auth-Free Web-Crawler Connector Design

**Status:** approved 2026-07-11
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) — the first real *external* source connector (P2.3a).
**Builds on:** Phase 1 (SPI + orchestrator + sync state) and Phase 2.1 (runtime + `/admin/connectors` + `ConnectorWiringHelper`), both shipped.

## Scope

A polite, auth-free web crawler that implements the fixed Phase-1 `SourceConnector` SPI: given seed URL(s), it BFS-crawls in-scope pages (respecting robots.txt + politeness limits) and emits one `SourceItem` per page, which the existing sink turns into a derived page. Registered + scheduled by the shipped P2.1 runtime. Default-off.

**Non-goals:** authentication / login-walled sites; JavaScript rendering (fetches server HTML only); conditional-GET/ETag incremental fetch (full re-crawl + hash-dedup this phase); sitemap.xml discovery; non-HTML crawling; a live-network integration test.

## Architecture

### Component structure

```
wikantik-connectors  (module — main deps become: wikantik-api + jsoup + crawler-commons)
  com.wikantik.connectors.webcrawler:
    WebCrawlerSourceConnector  implements SourceConnector — runs the BFS crawl in poll()
    WebCrawlerConfig           record: seeds, sameHostOnly, pathPrefix, maxPages, maxDepth,
                               delayMs, userAgent, respectRobots
    PageFetcher (interface)    FetchResult fetch(String url)  — the network seam (INJECTABLE)
    HttpPageFetcher            java.net.http.HttpClient impl of PageFetcher
    FetchResult (record)       int status, String contentType, byte[] body, String finalUrl
    CrawlScope                 in-scope predicate: same-host (+ optional path-prefix), http/https only
    RobotsPolicy               per-host robots.txt (crawler-commons SimpleRobotRules), fetched once
                               per host via the same PageFetcher, cached for the crawl; crawl-delay honored
    LinkExtractor              jsoup: absolute in-scope links from an HTML body + base URL; page <title>

wikantik-ingest  (existing module — one-line extension)
    TikaSourceExtractor.SUPPORTED_TYPES += "text/html"   (Tika bundles the HTML parser;
                                                          flexmark already converts XHTML→markdown)

wikantik-main  (existing com.wikantik.derived package — thin wiring growth, no new package)
    ConnectorWiringHelper: also build WebCrawlerSourceConnector(s) from
    wikantik.connectors.webcrawler.<id>.* config keys (analogous to filesystemRoots)
```

### Crawl algorithm (`WebCrawlerSourceConnector.poll(SyncCursor)`)

```
visited = {}; queue = [(seed, depth=0) for each seed]; items = []
robots = RobotsPolicy(fetcher, userAgent)          // lazily fetches /robots.txt per host
while queue not empty and items.size < maxPages:
    (url, depth) = queue.poll()
    if url in visited or depth > maxDepth: continue
    visited.add(url)
    if respectRobots and not robots.isAllowed(url): LOG.info(skip); continue
    sleep(max(delayMs, robots.crawlDelayMs(url)))   // politeness
    FetchResult r = fetcher.fetch(url)              // fail-closed: exception → LOG.warn, continue
    if r.status/100 != 2 or not isHtml(r.contentType): continue
    items.add(SourceItem(finalUrl, r.body, "text/html", {url,title,fetchedAt,httpStatus}, [], sha256(r.body)))
    for link in LinkExtractor.links(r.body, finalUrl):
        if link not in visited and CrawlScope.inScope(link): queue.add((link, depth+1))
return SyncBatch(items, tombstonedUris=[], nextCursor=new SyncCursor(String.valueOf(items.size())), complete=true)
```

- **Single complete batch per poll** (like the filesystem connector). Tombstones are derived by the orchestrator's `knownUris`-minus-seen diff on the complete batch — a page removed from the site (no longer reachable in a crawl) gets its derived page deleted on the next sync.
- **`nextCursor`** is a crawl-summary token (page count); it advances only cosmetically. The orchestrator's cursor-advance guard is irrelevant here because `complete=true`.
- **`aclRefs=[]`** — public web content; the (unenforced) ACL column stays empty.

### Fail-closed behavior (every external interaction is guarded)

- `fetcher.fetch` throws (timeout, DNS, connection reset) → `LOG.warn(url, msg)`, skip that URL, continue the crawl.
- non-2xx status or non-HTML content-type → skip silently (not an error).
- robots.txt fetch fails → `RobotsPolicy` treats the host as **allow-all** (standard crawler behavior when robots is unreachable) and `LOG.warn`s once; a malformed robots.txt → crawler-commons' lenient parse.
- jsoup parse failure on a page body → `LOG.warn`, emit the item anyway (content extraction is the sink's job), skip link discovery for that page.
- A crawl that hits `maxPages` logs the truncation (`LOG.info`) — no silent cap.

### Wiring & config

`ConnectorWiringHelper` gains a `webcrawlerConfigs(Properties)` parser (sibling to `filesystemRoots`) that reads, per `<id>`:

| Property (`wikantik.connectors.webcrawler.<id>.`) | Default | Meaning |
|---|---|---|
| `seeds` | — (required) | comma-separated seed URLs (registering an `<id>` requires at least `seeds`) |
| `same_host_only` | `true` | restrict the crawl to each seed's host |
| `path_prefix` | (none) | optional extra restriction: only crawl URLs whose path starts with this |
| `max_pages` | `100` | hard cap on pages fetched per crawl |
| `max_depth` | `3` | BFS depth cap from the seeds |
| `delay_ms` | `1000` | politeness delay between fetches |
| `user_agent` | `WikantikCrawler/1.0 (+https://wiki.wikantik.com)` | sent on every request |
| `respect_robots` | `true` | honor robots.txt (+ crawl-delay) |

The connector `type` label in the registry is `"webcrawler"`; `connectorId()` = `<id>`. Everything else (status, admin surface, scheduler) is the shipped P2.1 runtime unchanged.

## Testing (unit-first, injected fetcher — no network)

| Test | Proves | Where |
|---|---|---|
| `LinkExtractorTest` | absolute in-scope links + title from an HTML fixture; relative→absolute against base | wikantik-connectors |
| `CrawlScopeTest` | same-host include/exclude, path-prefix, http/https-only, reject mailto/js | wikantik-connectors |
| `RobotsPolicyTest` | Disallow honored, crawl-delay parsed, unreachable robots→allow-all (fake fetcher) | wikantik-connectors |
| `WebCrawlerSourceConnectorTest` | BFS over a canned HTML graph (fake `PageFetcher`): visits in-scope pages, respects `max_pages`/`max_depth`, skips robots-disallowed + non-2xx + non-HTML, emits correct `SourceItem`s (uri/hash/contentType), dedups visited, one complete batch | wikantik-connectors |
| `TikaSourceExtractor` html test | a small HTML byte string → non-empty markdown body via the extended `SUPPORTED_TYPES` | wikantik-ingest |
| `ConnectorWiringHelper` webcrawler-config test | `webcrawler.<id>.seeds` keys → registered connectors; missing `seeds` → skipped; defaults applied | wikantik-main |

No live-network IT (flaky in CI; the P2.1 `ConnectorAdminIT` already proves the runtime/sync/admin path end-to-end with the filesystem connector, and the crawl logic is fully covered by the injected-fetcher unit tests). A real crawl is validated manually against a live site post-merge.

## Dependencies

Add to `wikantik-connectors/pom.xml`, versions pinned in `wikantik-bom` (or the module, if the BOM isn't the convention for leaf deps):
- `org.jsoup:jsoup` (~1.18.x) — HTML parsing / link extraction.
- `com.github.crawler-commons:crawler-commons` (~1.4) — robots.txt parsing (`SimpleRobotRules`, `SimpleRobotRulesParser`).

Both Apache-2.0. RAT/license checks: confirm they're on the allowed-license list (they are Apache-2.0). No transitive heavyweight pulls (jsoup is standalone; crawler-commons pulls slf4j — already present).

## Invariants respected

- **Fixed Phase-1 SPI + P2.1 runtime:** the crawler is just another `SourceConnector`; no change to `SyncOrchestrator`/`SyncStateStore`/`DerivedPageSink`/`ConnectorRuntime`/`ConnectorAdminResource`.
- **#6 `wikantik-main` grows no new package:** the crawler lives in `wikantik-connectors`; `wikantik-main` gains only the config-parse growth of the existing `ConnectorWiringHelper`; `wikantik-ingest` gains one MIME type.
- **Fail-closed:** every external interaction (fetch, robots, parse) degrades to skip-and-log; a crawl never throws out of `poll()`.
- **PostgreSQL-first:** no new tables; sync state is the existing V046 tables via the orchestrator; full re-crawl + hash-dedup needs no schema change.
- **Default-off:** no `webcrawler.*` keys ⇒ no crawler registered.

## Open questions (resolve during planning, not blocking)

1. **Version-management location** for jsoup/crawler-commons — check whether `wikantik-bom` pins leaf-module deps or modules pin their own; follow the existing convention.
2. **`RobotsPolicy` fetch reuse** — it fetches `/robots.txt` via the same `PageFetcher`; confirm the fake fetcher in tests can serve `/robots.txt` alongside page URLs (it can — keyed by URL).
3. **`SUPPORTED_TYPES` immutability** — `TikaSourceExtractor.SUPPORTED_TYPES` is a `Set.of(...)`; adding `text/html` is a one-line edit to that literal; confirm no test asserts the exact set size (a `SUPPORTED_TYPES.size()==7` assertion would need updating).

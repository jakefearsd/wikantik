# RSS/Atom Feed Connector Design

**Status:** approved 2026-07-11
**Roadmap brief:** `RoadmapConnectorFramework` (wiki) — a third auth-free external connector, sibling to the web crawler and sitemap connectors.
**Builds on:** the shipped connector framework (Phase 1 SPI + orchestrator + state), P2.1 (runtime + `/admin/connectors` + `ConnectorWiringHelper`), the web crawler + sitemap connectors (`com.wikantik.connectors.webcrawler`: `PageFetcher`/`HttpPageFetcher`/`RobotsPolicy`/`FetchResult`/`WebFetchItems`).

## Scope

A `FeedSourceConnector` that reads RSS 0.9–2.0 / Atom feeds and syncs each entry into a derived page. Parsing uses **Rome**, already a managed reactor dependency (the wiki emits Atom feeds with it) — zero new dependency. Two behaviors distinguish feeds from the other connectors and are decided below: **content mode** (fetch full articles by default) and **archive semantics** (aged-out entries are kept, not deleted).

**Non-goals:** authentication; feed-autodiscovery from a site URL; conditional-GET/ETag on the feed; per-entry `pubDate` incremental windowing; a live-network integration test.

## Two feed-specific decisions (resolved)

### 1. Content mode — fetch full articles by default (toggle for inline)

Feed entry content is often a truncated summary. So by default the connector **fetches each entry's `link` URL** (via `HttpPageFetcher` + `RobotsPolicy` politeness, exactly like the sitemap connector) for the complete article. A per-connector `fetch_full_articles=false` switches to **inline mode**: emit the entry's own HTML (`content:encoded` / `description` / `summary`) directly, with no per-article request (one fetch = the feed itself, politest).

### 2. Archive semantics — keep aged-out entries (additive orchestrator change)

A feed is a **rolling window** (the last N entries); older entries age out. The shipped orchestrator, on a *complete* batch, deletes any previously-synced URL not seen in the current poll — correct for the filesystem/crawler/sitemap connectors (their poll reflects the *full* current set), but wrong for a feed (it would delete an article once its entry scrolls off the feed).

**Fix — a minimal, additive, backward-compatible SPI + orchestrator change:**
- `SourceConnector` gains `default boolean reflectsFullCorpus() { return true; }` — "does a not-seen URI mean deleted-at-source?" Existing connectors inherit `true` (unchanged); the feed connector overrides to `false`.
- `SyncOrchestrator` gates its derived-tombstone loop on it: `if ( batch.complete() && connector.reflectsFullCorpus() )`. Explicit `SyncBatch.tombstonedUris()` are still always honored.

This is additive (a default method + one boolean gate); no existing connector, test, or caller changes behavior. It is the one place this feature touches the Phase-1 SPI + P2.1 orchestrator, and it's the correct generalization (windowed vs full-corpus sources).

## Architecture

### Placement — the existing `com.wikantik.connectors.webcrawler` package

Reuses the crawler's package-private `PageFetcher`/`HttpPageFetcher`/`RobotsPolicy`/`FetchResult`/`WebFetchItems` in-package (consistent with the sitemap connector). The package now hosts three web-fetch connectors + shared primitives; a `webcrawler`→`web` package rename is increasingly warranted but remains an optional future cleanup (out of scope).

```
wikantik-api (com.wikantik.api.connectors) — additive SPI:
    SourceConnector.reflectsFullCorpus()  default true  (feed overrides false)

wikantik-connectors:
  com.wikantik.connectors (SyncOrchestrator) — gate the derived-tombstone loop on reflectsFullCorpus()
  com.wikantik.connectors.webcrawler:
    FeedParser        static List<FeedEntry> parse(byte[] xml, String baseUrl)  — Rome; never throws
    FeedEntry         record(String title, String link, String contentHtml)
    FeedConfig        record(List<String> feedUrls, int maxItems, boolean fetchFullArticles,
                             long delayMs, String userAgent, boolean respectRobots, boolean sameHostOnly)
    FeedSourceConnector implements SourceConnector  — reflectsFullCorpus()=false; poll() below
    WebFetchItems.toItemFromContent(String url, byte[] htmlBytes, String title)  — inline-item variant

wikantik-main (com.wikantik.derived.ConnectorWiringHelper) — config-parse growth only:
    feedConfigs(Properties) + a feed loop (mirror sitemapConfigs)
```

### `FeedParser` (Rome wrapper)

`SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(xml)))` → `SyndFeed` → for each `SyndEntry`:
- `title` = `entry.getTitle()` (or "").
- `link` = `entry.getLink()` (Rome resolves RSS `<link>` text and Atom `<link href>` uniformly).
- `contentHtml` = first non-blank of: `entry.getContents()` (RSS `content:encoded` / Atom `<content>`) joined, else `entry.getDescription().getValue()` (RSS `<description>` / Atom `<summary>`), else "".
- Skip entries with a blank `link` (no stable URI). Rome parse failure (`FeedException`/`RuntimeException`) → empty list + `LOG.warn` (never throws). `XmlReader` handles the feed's charset.

### `FeedSourceConnector.poll(SyncCursor)`

```
robots = new RobotsPolicy(fetcher, config.userAgent())
allowedHosts = hosts of config.feedUrls()          // for the same-host filter in full-article mode
entries = []
for feedUrl in config.feedUrls():
    if respectRobots && !robots.isAllowed(feedUrl): continue
    r = fetcher.fetch(feedUrl); if r.status()/100 != 2: LOG.warn; continue
    entries.addAll(FeedParser.parse(r.body(), feedUrl))
items = []; visited = new HashSet()
for e in entries:
    if items.size() >= maxItems: break
    if !visited.add(e.link): continue
    if config.sameHostOnly() && hostOf(e.link) ∉ allowedHosts: continue   // full-article safety; see note
    if config.fetchFullArticles():
        if respectRobots && !robots.isAllowed(e.link): LOG.info(skip); continue
        sleepPolitely(robots, e.link)
        ar = fetcher.fetch(e.link)
        if ar.status()/100 != 2 || !isHtml(ar.contentType()): continue
        items.add(WebFetchItems.toItem(finalUrl(ar, e.link), ar))
    else:  // inline
        if e.contentHtml.isBlank(): continue
        items.add(WebFetchItems.toItemFromContent(e.link, e.contentHtml.getBytes(UTF_8), e.title))
return SyncBatch(items, [], new SyncCursor(String.valueOf(items.size())), complete=true)

reflectsFullCorpus() → false     // archive: the orchestrator will NOT tombstone aged-out entries
```

- **same-host note:** applied to entry `link`s (an entry can legitimately link off-host for a feed aggregator, so `same_host_only` defaults true but is the operator's lever). In inline mode the same-host filter is advisory (no fetch happens); it still gates which entries become pages, for consistency.
- **`toItemFromContent`** builds the text/html `SourceItem` from the entry's own HTML: `sourceUri=link`, `content=htmlBytes`, `contentType="text/html"`, metadata `{url=link, title, fetchedAt}`, `aclRefs=[]`, `contentHash=sha256(htmlBytes)`. (No `httpStatus` — there was no page fetch.)

### Fail-closed

- `fetcher.fetch` never throws (status 0 on error) — non-2xx feed or article → skip.
- `FeedParser.parse` never throws — malformed feed → empty + `LOG.warn`.
- `hostOf` catches + logs; `poll()` has no throwing path.
- `maxItems` truncation logged.

### Wiring & config

`ConnectorWiringHelper` gains `feedConfigs(Properties)` (sibling to `sitemapConfigs`) reading, per `<id>`:

| Property (`wikantik.connectors.feed.<id>.`) | Default | Meaning |
|---|---|---|
| `feed_urls` | — (required) | comma-separated RSS/Atom feed URLs |
| `max_items` | `100` | cap on entries synced per poll |
| `fetch_full_articles` | `true` | fetch each entry's link for the full article; `false` = inline entry content |
| `delay_ms` | `1000` | politeness delay between article fetches (full-article mode) |
| `user_agent` | `WikantikCrawler/1.0 (+https://wiki.wikantik.com)` | sent on every request |
| `respect_robots` | `true` | honor robots.txt for feed + article fetches |
| `same_host_only` | `true` | drop entries whose link host differs from the feed host |

Registry `type` = `"feed"`. Extend the wiring early-return guard to also consider feed configs (a feed-only config wires).

## Testing (unit-first, injected fetcher — no network)

| Test | Proves | Where |
|---|---|---|
| `FeedParserTest` | canned RSS 2.0 → entries (title/link/content:encoded); canned Atom → entries (title/link href/content); description fallback; blank-link entry skipped; malformed → empty | webcrawler |
| `WebFetchItemsTest` (extend) | `toItemFromContent` shape ({url,title,fetchedAt}, text/html, aclRefs=[], sha256) | webcrawler |
| `FeedSourceConnectorTest` | fake `PageFetcher` serving a feed + article pages: full-article mode fetches each link → items; inline mode (`fetch_full_articles=false`) emits entry content without article fetch; `max_items` cap; robots-disallowed article skipped; non-2xx/non-html article skipped; `reflectsFullCorpus()==false` | webcrawler |
| `SyncOrchestrator` archive test | a `reflectsFullCorpus()==false` connector does NOT tombstone a known-but-not-seen URI (archive); an existing `true` connector still does (regression) | wikantik-connectors |
| `ConnectorWiringHelper` feed-config test | `feed.<id>.feed_urls` → registered connectors; missing urls → skipped; defaults (incl. `fetch_full_articles=true`) applied; feed-only config wires | wikantik-main |

No live IT (same rationale as the crawler/sitemap; the P2.1 `ConnectorAdminIT` covers the runtime/admin path).

## Invariants respected

- **P2.1 runtime surface unchanged** (`ConnectorRuntime`/`ConnectorAdminResource` untouched); the only SPI/orchestrator change is the additive `reflectsFullCorpus()` gate.
- **#6 `wikantik-main` grows no new package**; connector + parser in `wikantik-connectors`; `wikantik-main` only gains `ConnectorWiringHelper` config growth. No new dependency (Rome already managed).
- **Fail-closed** throughout; `poll()` never throws.
- **PostgreSQL-first / no schema change**; sync state is the existing V046 tables; archive mode simply suppresses derived tombstones for this connector.
- **Default-off**: no `feed.*` keys ⇒ no connector registered.

## Open questions (resolve during planning, not blocking)

1. **`reflectsFullCorpus()` naming** — alternatives (`ownsFullCorpus`, `derivesTombstonesFromAbsence`); pick the clearest. The semantic is "absence in a poll implies deletion-at-source."
2. **Rome `getContents()` vs `getDescription()` precedence** — prefer `getContents()` (full `content:encoded`) when present and non-blank, else `getDescription()`. Confirm Rome returns `content:encoded` via `getContents()` for RSS (it does).
3. **`XmlReader` import** — Rome ships `com.rometools.rome.io.XmlReader`; confirm it's the one used for charset-correct feed reading.

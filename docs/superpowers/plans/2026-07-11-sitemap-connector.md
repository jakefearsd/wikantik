# Sitemap Connector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `SitemapSourceConnector` that reads a site's `sitemap.xml` (and one level of sitemap-index) and syncs each listed URL into a derived page — reusing the web crawler's fetch/robots/politeness primitives; the only new logic is sitemap-XML parsing.

**Architecture:** New classes in the existing `com.wikantik.connectors.webcrawler` package (so the sitemap connector reuses the crawler's package-private `PageFetcher`/`HttpPageFetcher`/`RobotsPolicy`/`FetchResult`/`LinkExtractor` directly). A shared `WebFetchItems` helper is extracted (the crawler's private item-builder + sha256) and used by both connectors. `ConnectorWiringHelper` (wikantik-main) gains a `sitemapConfigs` parser. No new deps (jsoup already present), no schema/SPI change, default-off, fail-closed.

**Tech Stack:** Java 25, Maven, JUnit 5, jsoup (XML parser), reused `java.net.http`/crawler-commons via the crawler primitives.

**Spec:** `docs/superpowers/specs/2026-07-11-sitemap-connector-design.md`

## Global Constraints

- **Fixed Phase-1 SPI + P2.1 runtime unchanged:** the sitemap connector is only a new `SourceConnector`. No change to `SyncOrchestrator`/`SyncStateStore`/`DerivedPageSink`/`ConnectorRuntime`/`ConnectorAdminResource`.
- **Invariant #6:** connector + parser + shared helper in `wikantik-connectors`; `wikantik-main` gains only `ConnectorWiringHelper` config-parse growth. No new package in `wikantik-main`, no new dependency.
- **Fail-closed:** `fetcher.fetch` never throws (status 0 on error); `SitemapParser.parse` never throws (malformed → empty + `LOG.warn`); index recursion bounded to one level; `poll()` has no throwing path. No empty catch.
- **Default-off:** no `wikantik.connectors.sitemap.*` keys ⇒ no connector registered.
- **Behavior-preserving refactor:** the `WebFetchItems` extraction must leave `WebCrawlerSourceConnector`'s `SourceItem` shape byte-identical — its 12 existing tests are the safety net and must stay green unmodified.
- **Unit-tested via injected `PageFetcher`** (no network); no live IT.
- **TDD:** failing test first; run only the task's targeted test.

---

### Task 1: Extract `WebFetchItems` (shared) + refactor `WebCrawlerSourceConnector`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebFetchItems.java`
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebCrawlerSourceConnector.java` (delegate `item()`/`sha256Hex` to `WebFetchItems`)
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/WebFetchItemsTest.java`

**Interfaces:**
- Consumes: `SourceItem` (Phase 1); `FetchResult`, `LinkExtractor` (webcrawler, same package).
- Produces:
  - `static String WebFetchItems.sha256Hex(byte[] bytes)` — 64-char lowercase hex.
  - `static SourceItem WebFetchItems.toItem(String url, FetchResult r)` — the text/html `SourceItem`: `sourceUri=url`, `content=r.body()`, `contentType="text/html"`, `sourceMetadata={url, title=LinkExtractor.title(body), fetchedAt=Instant.now().toString(), httpStatus=r.status()}` (LinkedHashMap, that order), `aclRefs=List.of()`, `contentHash=sha256Hex(r.body())`. Decodes `r.body()` as UTF-8 for the title.

- [ ] **Step 1: Write the failing test**
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WebFetchItemsTest {
    @Test void sha256HexIs64LowercaseHex() {
        String h = WebFetchItems.sha256Hex( "abc".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( 64, h.length() );
        assertTrue( h.matches( "[0-9a-f]{64}" ) );
    }
    @Test void toItemBuildsTextHtmlSourceItem() {
        byte[] body = "<html><head><title>Hi</title></head><body>x</body></html>".getBytes( StandardCharsets.UTF_8 );
        SourceItem i = WebFetchItems.toItem( "https://ex.com/p", new FetchResult( 200, "text/html", body, "https://ex.com/p" ) );
        assertEquals( "https://ex.com/p", i.sourceUri() );
        assertEquals( "text/html", i.contentType() );
        assertTrue( i.aclRefs().isEmpty() );
        assertEquals( 64, i.contentHash().length() );
        assertEquals( "https://ex.com/p", i.sourceMetadata().get( "url" ) );
        assertEquals( "Hi", i.sourceMetadata().get( "title" ) );
        assertEquals( 200, i.sourceMetadata().get( "httpStatus" ) );
        assertNotNull( i.sourceMetadata().get( "fetchedAt" ) );
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=WebFetchItemsTest -q`).

- [ ] **Step 3: Implement `WebFetchItems`** (Apache header; move the crawler's exact `item()` body + `sha256Hex`):
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.SourceItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared builders for web-fetched connectors (crawler + sitemap): the text/html {@link SourceItem}
 *  shape and content hashing. Keeps the item shape identical across both connectors. */
final class WebFetchItems {

    private WebFetchItems() {}

    static SourceItem toItem( final String url, final FetchResult r ) {
        final String html = new String( r.body(), StandardCharsets.UTF_8 );
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "url", url );
        md.put( "title", LinkExtractor.title( html ) );
        md.put( "fetchedAt", Instant.now().toString() );   // metadata only; not asserted on exact value
        md.put( "httpStatus", r.status() );
        return new SourceItem( url, r.body(), "text/html", md, List.of(), sha256Hex( r.body() ) );
    }

    static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );   // JVM-guaranteed; never happens
        }
    }
}
```

- [ ] **Step 4: Refactor `WebCrawlerSourceConnector`** — replace the item-building call and remove its now-duplicate private `item(...)` + `sha256Hex(...)`:
  - In `poll()`, change `items.add( item( finalUrl, r, html ) );` to `items.add( WebFetchItems.toItem( finalUrl, r ) );`.
  - Delete the private `item(String, FetchResult, String)` method and the private `sha256Hex(byte[])` method (now in `WebFetchItems`). Remove now-unused imports (`MessageDigest`, `Instant`, `LinkedHashMap`, `Map` — **only if** no longer referenced elsewhere in the file; check first). Keep `isHtml`.

- [ ] **Step 5: Run the new test AND the crawler's existing suite** (behavior-preservation proof):
`mvn test -pl wikantik-connectors -Dtest=WebFetchItemsTest,WebCrawlerSourceConnectorTest -q`
Expected: `WebFetchItemsTest` green AND all 12 `WebCrawlerSourceConnectorTest` cases still green, unmodified.

- [ ] **Step 6: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebFetchItems.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebCrawlerSourceConnector.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/WebFetchItemsTest.java
git commit -m "refactor(webcrawler): extract shared WebFetchItems (item shape + sha256) for reuse"
```

---

### Task 2: `SitemapParser` + `ParsedSitemap`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/ParsedSitemap.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/SitemapParser.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/SitemapParserTest.java`

**Interfaces:**
- Consumes: jsoup (`org.jsoup.Jsoup`, `org.jsoup.parser.Parser`).
- Produces:
  - `record ParsedSitemap(java.util.List<String> locs, boolean isIndex)`.
  - `static ParsedSitemap SitemapParser.parse(String xml)` — jsoup XML parse; `isIndex = true` when the root/document contains `<sitemapindex>`; `locs` = every `<loc>`'s trimmed non-blank text; malformed/empty → `ParsedSitemap(List.of(), false)` (never throws; `LOG.warn` on failure).

- [ ] **Step 1: Write the failing test**
```java
package com.wikantik.connectors.webcrawler;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SitemapParserTest {
    @Test void parsesUrlsetPageLocs() {
        String xml = "<?xml version='1.0'?><urlset xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>"
            + "<url><loc>https://ex.com/a</loc></url><url><loc> https://ex.com/b </loc></url>"
            + "<url><loc></loc></url></urlset>";
        ParsedSitemap p = SitemapParser.parse( xml );
        assertFalse( p.isIndex() );
        assertEquals( List.of( "https://ex.com/a", "https://ex.com/b" ), p.locs() );  // trimmed, blank dropped
    }
    @Test void parsesSitemapIndexSubSitemaps() {
        String xml = "<?xml version='1.0'?><sitemapindex xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>"
            + "<sitemap><loc>https://ex.com/sm1.xml</loc></sitemap>"
            + "<sitemap><loc>https://ex.com/sm2.xml</loc></sitemap></sitemapindex>";
        ParsedSitemap p = SitemapParser.parse( xml );
        assertTrue( p.isIndex() );
        assertEquals( List.of( "https://ex.com/sm1.xml", "https://ex.com/sm2.xml" ), p.locs() );
    }
    @Test void malformedOrEmptyIsSafe() {
        assertTrue( SitemapParser.parse( "" ).locs().isEmpty() );
        assertTrue( SitemapParser.parse( "not xml <<<" ).locs().isEmpty() );
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=SitemapParserTest -q`).

- [ ] **Step 3: Implement** (Apache headers):
```java
// ParsedSitemap.java
package com.wikantik.connectors.webcrawler;
import java.util.List;
/** Result of parsing a sitemap: either page URLs (urlset) or sub-sitemap URLs (sitemapindex). */
record ParsedSitemap( List< String > locs, boolean isIndex ) {}
```
```java
// SitemapParser.java
package com.wikantik.connectors.webcrawler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/** Parses sitemap.xml (urlset) and sitemap-index XML using jsoup's XML parser. Never throws. */
final class SitemapParser {
    private static final Logger LOG = LogManager.getLogger( SitemapParser.class );
    private SitemapParser() {}

    static ParsedSitemap parse( final String xml ) {
        if ( xml == null || xml.isBlank() ) return new ParsedSitemap( List.of(), false );
        try {
            final Document doc = Jsoup.parse( xml, "", Parser.xmlParser() );
            final boolean isIndex = !doc.select( "sitemapindex" ).isEmpty();
            final List< String > locs = new ArrayList<>();
            for ( final Element loc : doc.select( "loc" ) ) {
                final String text = loc.text().trim();
                if ( !text.isBlank() ) locs.add( text );
            }
            return new ParsedSitemap( locs, isIndex );
        } catch ( final RuntimeException e ) {
            LOG.warn( "sitemap parse failed: {}", e.getMessage() );
            return new ParsedSitemap( List.of(), false );
        }
    }
}
```
> Confirm jsoup exposes `org.jsoup.parser.Parser.xmlParser()` in 1.18.3 (it does). If `loc.text()` doesn't return the CDATA/text as expected for XML nodes, use `loc.wholeText()` — the test arbitrates.

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=SitemapParserTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/ParsedSitemap.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/SitemapParser.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/SitemapParserTest.java
git commit -m "feat(sitemap): SitemapParser — urlset + sitemapindex via jsoup XML"
```

---

### Task 3: `SitemapConfig` + `SitemapSourceConnector`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/SitemapConfig.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/SitemapSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/SitemapSourceConnectorTest.java`

**Interfaces:**
- Consumes: `SourceConnector`/`SourceItem`/`SyncBatch`/`SyncCursor` (Phase 1); `PageFetcher`/`FetchResult`/`RobotsPolicy` (webcrawler); `SitemapParser`/`WebFetchItems` (T1/T2).
- Produces:
  - `record SitemapConfig(List<String> sitemapUrls, int maxPages, long delayMs, String userAgent, boolean respectRobots, boolean sameHostOnly)`.
  - `SitemapSourceConnector(String connectorId, SitemapConfig config, PageFetcher fetcher, java.util.function.LongConsumer sleeper)` implementing `SourceConnector`. `poll` per the spec: collect page URLs from each sitemap (recursing a sitemap-index ONE level), filter to allowed hosts (when `sameHostOnly`) + robots + `maxPages`, fetch each with a politeness delay, emit `WebFetchItems.toItem`. One complete batch.

**Same-host rule:** compute `allowedHosts` = the set of hosts of `config.sitemapUrls()`; a page URL is in-scope iff its host ∈ `allowedHosts` (only checked when `sameHostOnly`). This handles multi-seed and index recursion without threading per-page provenance.

- [ ] **Step 1: Write the failing test**
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SitemapSourceConnectorTest {

    private static FetchResult xml( String url, String body ) {
        return new FetchResult( 200, "application/xml", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static FetchResult html( String url, String body ) {
        return new FetchResult( 200, "text/html", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static String urlset( String... locs ) {
        StringBuilder sb = new StringBuilder( "<urlset>" );
        for ( String l : locs ) sb.append( "<url><loc>" ).append( l ).append( "</loc></url>" );
        return sb.append( "</urlset>" ).toString();
    }
    private static SitemapConfig cfg( int maxPages, boolean sameHost ) {
        return new SitemapConfig( List.of( "https://ex.com/sitemap.xml" ), maxPages, 0,
            "WikantikCrawler/1.0", true, sameHost );
    }
    private static Set<String> uris( SyncBatch b ) {
        Set<String> s = new HashSet<>();
        for ( SourceItem i : b.items() ) s.add( i.sourceUri() );
        return s;
    }

    @Test void emitsOneItemPerListedUrl() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://ex.com/b" ) );
            case "https://ex.com/a", "https://ex.com/b" -> html( url, "<p>page</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null );
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ), uris( b ) );
        assertTrue( b.complete() );
        assertTrue( b.items().stream().allMatch( i -> "text/html".equals( i.contentType() ) ) );
    }

    @Test void recursesSitemapIndexOneLevel() {
        String index = "<sitemapindex><sitemap><loc>https://ex.com/sm-a.xml</loc></sitemap>"
            + "<sitemap><loc>https://ex.com/sm-b.xml</loc></sitemap></sitemapindex>";
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, index );
            case "https://ex.com/sm-a.xml" -> xml( url, urlset( "https://ex.com/a" ) );
            case "https://ex.com/sm-b.xml" -> xml( url, urlset( "https://ex.com/b" ) );
            case "https://ex.com/a", "https://ex.com/b" -> html( url, "<p>p</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ),
            uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) ) );
    }

    @Test void sameHostOnlyDropsForeignLocs() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt", "https://other.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://other.com/evil" ) );
            case "https://ex.com/a" -> html( url, "<p>p</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        Set<String> u = uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertTrue( u.contains( "https://ex.com/a" ) );
        assertFalse( u.contains( "https://other.com/evil" ), "foreign-host loc must be dropped when same_host_only" );
    }

    @Test void respectsMaxPages() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://ex.com/b", "https://ex.com/c" ) );
            default -> html( url, "<p>p</p>" );
        };
        assertTrue( new SitemapSourceConnector( "sm1", cfg( 2, true ), f, ms -> {} ).poll( null ).items().size() <= 2 );
    }

    @Test void skipsRobotsDisallowedAndNonHtml() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 200, "text/plain",
                "User-agent: *\nDisallow: /a\n".getBytes( StandardCharsets.UTF_8 ), url );
            case "https://ex.com/sitemap.xml" -> xml( url, urlset( "https://ex.com/a", "https://ex.com/b", "https://ex.com/img" ) );
            case "https://ex.com/b" -> html( url, "<p>b</p>" );
            case "https://ex.com/img" -> new FetchResult( 200, "image/png", new byte[]{1}, url );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        Set<String> u = uris( new SitemapSourceConnector( "sm1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertEquals( Set.of( "https://ex.com/b" ), u );   // /a robots-disallowed, /img non-html
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=SitemapSourceConnectorTest -q`).

- [ ] **Step 3: Implement** (Apache headers). `SitemapConfig` is a plain record. `SitemapSourceConnector`:
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongConsumer;

/** Reads a site's sitemap.xml (and one level of sitemap-index) and emits a SourceItem per listed page. */
public final class SitemapSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( SitemapSourceConnector.class );

    private final String connectorId;
    private final SitemapConfig config;
    private final PageFetcher fetcher;
    private final LongConsumer sleeper;

    public SitemapSourceConnector( final String connectorId, final SitemapConfig config,
                                   final PageFetcher fetcher, final LongConsumer sleeper ) {
        this.connectorId = connectorId;
        this.config = config;
        this.fetcher = fetcher;
        this.sleeper = sleeper;
    }

    @Override public String connectorId() { return connectorId; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final RobotsPolicy robots = new RobotsPolicy( fetcher, config.userAgent() );
        final Set< String > allowedHosts = new HashSet<>();
        for ( final String sm : config.sitemapUrls() ) hostOf( sm ).ifPresent( allowedHosts::add );

        final Set< String > pageUrls = new LinkedHashSet<>();
        for ( final String sm : config.sitemapUrls() ) collectPages( sm, robots, pageUrls, 0 );

        final List< SourceItem > items = new ArrayList<>();
        final Set< String > visited = new HashSet<>();
        for ( final String url : pageUrls ) {
            if ( items.size() >= config.maxPages() ) break;
            if ( config.sameHostOnly() && hostOf( url ).map( h -> !allowedHosts.contains( h ) ).orElse( true ) ) continue;
            if ( !visited.add( url ) ) continue;
            if ( config.respectRobots() && !robots.isAllowed( url ) ) {
                LOG.info( "sitemap '{}': robots-disallowed, skipping {}", connectorId, url );
                continue;
            }
            sleepPolitely( robots, url );
            final FetchResult r = fetcher.fetch( url );
            if ( r.status() / 100 != 2 || !isHtml( r.contentType() ) ) continue;
            final String finalUrl = r.finalUrl() == null ? url : r.finalUrl();
            items.add( WebFetchItems.toItem( finalUrl, r ) );
        }
        if ( items.size() >= config.maxPages() ) {
            LOG.info( "sitemap '{}': hit max_pages={}, truncated", connectorId, config.maxPages() );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private void collectPages( final String sitemapUrl, final RobotsPolicy robots,
                               final Set< String > out, final int depth ) {
        if ( config.respectRobots() && !robots.isAllowed( sitemapUrl ) ) {
            LOG.info( "sitemap '{}': robots-disallowed sitemap {}", connectorId, sitemapUrl );
            return;
        }
        final FetchResult r = fetcher.fetch( sitemapUrl );
        if ( r.status() / 100 != 2 ) {
            LOG.warn( "sitemap '{}': fetch of {} returned status {}", connectorId, sitemapUrl, r.status() );
            return;
        }
        final ParsedSitemap parsed = SitemapParser.parse( new String( r.body(), StandardCharsets.UTF_8 ) );
        if ( parsed.isIndex() ) {
            if ( depth == 0 ) {
                for ( final String sub : parsed.locs() ) collectPages( sub, robots, out, depth + 1 );
            } else {
                LOG.info( "sitemap '{}': nested index beyond one level ignored: {}", connectorId, sitemapUrl );
            }
        } else {
            out.addAll( parsed.locs() );
        }
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        final long robotsDelay = config.respectRobots() ? robots.crawlDelayMs( url ) : 0L;
        final long delay = Math.max( config.delayMs(), robotsDelay );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private static Optional< String > hostOf( final String url ) {
        try { return Optional.ofNullable( URI.create( url ).getHost() ); }
        catch ( final RuntimeException e ) { return Optional.empty(); }
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }
}
```

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=SitemapSourceConnectorTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/SitemapConfig.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/SitemapSourceConnector.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/SitemapSourceConnectorTest.java
git commit -m "feat(sitemap): SitemapSourceConnector — sitemap.xml + index → derived pages"
```

---

### Task 4: Wire sitemap connectors in `ConnectorWiringHelper` + config keys

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java` (extend)

**Interfaces:**
- Consumes: `SitemapSourceConnector`, `SitemapConfig`, `HttpPageFetcher` (webcrawler package).
- Produces: `wireConnectors` also registers sitemap connectors from `wikantik.connectors.sitemap.<id>.sitemap_urls`. Add package-visible `Map<String, SitemapConfig> sitemapConfigs(Properties)` (sibling to `webcrawlerConfigs`; reuse the existing `parseSeeds`/`parseInt`/`parseLongValue` helpers — `parseSeeds` splits comma lists, reuse it for `sitemap_urls`).

- [ ] **Step 1: Write the failing test** (extend `ConnectorWiringHelperTest`; `import com.wikantik.connectors.webcrawler.SitemapConfig;`):
```java
    @Test void parsesSitemapConfigsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.sitemap.site.sitemap_urls", "https://a.com/sitemap.xml, https://a.com/sm2.xml" );
        p.setProperty( "wikantik.connectors.sitemap.site.max_pages", "250" );
        p.setProperty( "wikantik.connectors.sitemap.site.same_host_only", "false" );
        Map< String, SitemapConfig > cfgs = ConnectorWiringHelper.sitemapConfigs( p );
        assertEquals( 1, cfgs.size() );
        SitemapConfig c = cfgs.get( "site" );
        assertEquals( List.of( "https://a.com/sitemap.xml", "https://a.com/sm2.xml" ), c.sitemapUrls() );
        assertEquals( 250, c.maxPages() );
        assertFalse( c.sameHostOnly() );
        assertTrue( c.respectRobots() );   // default
    }
    @Test void sitemapRequiresUrls() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.sitemap.nope.max_pages", "10" );  // no sitemap_urls → skipped
        assertTrue( ConnectorWiringHelper.sitemapConfigs( p ).isEmpty() );
    }
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`).

- [ ] **Step 3: Implement.** Add `sitemapConfigs(Properties)` mirroring `webcrawlerConfigs` (scan `sitemap.<id>.sitemap_urls`, skip dotted ids + empty url lists; defaults: `max_pages`=500, `delay_ms`=1000, `user_agent`="WikantikCrawler/1.0 (+https://wiki.wikantik.com)", `respect_robots`=true, `same_host_only`=true). In `wireConnectors`, compute `sitemaps = sitemapConfigs(props)` once alongside `roots`/`webcrawlers`, extend the early-return guard to `roots.isEmpty() && webcrawlers.isEmpty() && sitemaps.isEmpty()`, and add a sitemap loop:
```java
        for ( final Map.Entry< String, SitemapConfig > e : sitemaps.entrySet() ) {
            byId.put( e.getKey(), new SitemapSourceConnector( e.getKey(), e.getValue(),
                new HttpPageFetcher( e.getValue().userAgent(), java.time.Duration.ofSeconds( 20 ) ),
                ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } } ) );
            typeById.put( e.getKey(), "sitemap" );
        }
```

- [ ] **Step 4: Register config keys** in `wikantik.properties` (near the other `wikantik.connectors.*` blocks):
```properties
# Sitemap connectors (auth-free). Each <id> needs at least .sitemap_urls (comma-separated sitemap.xml URLs).
#wikantik.connectors.sitemap.<id>.sitemap_urls = https://example.com/sitemap.xml
#wikantik.connectors.sitemap.<id>.max_pages = 500
#wikantik.connectors.sitemap.<id>.delay_ms = 1000
#wikantik.connectors.sitemap.<id>.user_agent = WikantikCrawler/1.0 (+https://wiki.wikantik.com)
#wikantik.connectors.sitemap.<id>.respect_robots = true
#wikantik.connectors.sitemap.<id>.same_host_only = true
```

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`) and compile-check (`mvn -q -pl wikantik-main -am compile`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java wikantik-main/src/main/resources/ini/wikantik.properties wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java
git commit -m "feat(sitemap): wire sitemap connectors from config (ConnectorWiringHelper)"
```

---

## Post-implementation (controller)

- Full reactor unit build: `mvn clean install -DskipITs` **with `WIKANTIK_*` env UNSET**.
- Whole-branch review (opus): the `WebFetchItems` refactor is behavior-preserving (crawler's 12 tests unmodified + green); the sitemap connector is only a new `SourceConnector` (no Phase-1 SPI / P2.1 runtime change); invariant #6 + no new dep; fail-closed `poll()` (fetch/parse/robots never abort); index recursion bounded to one level (no unbounded fetch); same-host filter defends against foreign `<loc>`s; wiring early-return still permits every single-type config (filesystem-only / webcrawler-only / sitemap-only).

## Self-review notes

- **Spec coverage:** shared item helper + refactor (T1), sitemap parsing (T2), the connector incl. index recursion + same-host + caps + robots (T3), wiring+config (T4). All spec sections mapped. No live IT (per spec).
- **Behavior-preserving refactor:** T1 Step 5 runs the crawler's 12 tests unmodified as the proof.
- **Invariant #6 / no new dep:** everything in `wikantik-connectors` (existing package) + `ConnectorWiringHelper` config growth; jsoup already present.
- **Type consistency:** `WebFetchItems.toItem(String, FetchResult)` / `sha256Hex(byte[])`, `ParsedSitemap(locs, isIndex)`, `SitemapParser.parse(String)`, `SitemapConfig(sitemapUrls, maxPages, delayMs, userAgent, respectRobots, sameHostOnly)`, `SitemapSourceConnector(id, config, fetcher, sleeper)` — identical across T1→T4.

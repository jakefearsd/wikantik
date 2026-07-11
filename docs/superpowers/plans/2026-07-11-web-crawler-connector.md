# Auth-Free Web-Crawler Connector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A polite, auth-free web-crawler `SourceConnector` that BFS-crawls in-scope, robots-respecting pages from seed URLs and emits raw-HTML `SourceItem`s, which the existing sink turns into derived pages. Registered + scheduled by the shipped P2.1 runtime. Default-off.

**Architecture:** New `com.wikantik.connectors.webcrawler` package in `wikantik-connectors` (adds jsoup + crawler-commons). A one-line `text/html` addition to `TikaSourceExtractor` (wikantik-ingest) so the existing pipeline converts crawled HTML to markdown. `ConnectorWiringHelper` (wikantik-main, existing `com.wikantik.derived` package) gains a webcrawler-config parser. Full re-crawl + orchestrator hash-dedup — no schema/SPI change.

**Tech Stack:** Java 25, Maven, JUnit 5 + Mockito, jsoup (HTML), crawler-commons (robots.txt), `java.net.http.HttpClient`.

**Spec:** `docs/superpowers/specs/2026-07-11-web-crawler-connector-design.md`

## Global Constraints

- **Fixed Phase-1 SPI + P2.1 runtime:** no change to `SourceConnector`/`SyncOrchestrator`/`SyncStateStore`/`DerivedPageSink`/`ConnectorRuntime`/`ConnectorAdminResource`. The crawler is just another `SourceConnector`.
- **Invariant #6:** crawler in `wikantik-connectors`; `wikantik-main` gains only config-parse growth of the existing `ConnectorWiringHelper`; `wikantik-ingest` gains one MIME type. No new `wikantik-main` package.
- **Fail-closed:** every external interaction (fetch, robots, jsoup parse) degrades to skip + `LOG.warn`; `poll()` never throws. No empty catch (repo rule).
- **Default-off:** no `wikantik.connectors.webcrawler.*` keys ⇒ no crawler registered.
- **`wikantik-connectors` main deps** become `wikantik-api` + jsoup + crawler-commons (+ log4j). Still NOT `wikantik-main`.
- **Politeness by default:** respect robots.txt + crawl-delay; `delay_ms` between fetches; `max_pages` cap; custom User-Agent.
- **TDD:** failing test first; run only the task's targeted test. Controller runs the full build at the end.

---

### Task 1: `text/html` support in `TikaSourceExtractor`

**Files:**
- Modify: `wikantik-ingest/src/main/java/com/wikantik/ingest/TikaSourceExtractor.java` (add `"text/html"` to `SUPPORTED_TYPES`)
- Test: `wikantik-ingest/src/test/java/com/wikantik/ingest/TikaSourceExtractorTest.java` (add 2 assertions)

**Interfaces:**
- Consumes: nothing new.
- Produces: `TikaSourceExtractor.supports("text/html") == true`; `extract(htmlBytes, "text/html", name)` yields a non-empty markdown body. (No signature change.)

- [ ] **Step 1: Add the failing test** to `TikaSourceExtractorTest` (there is NO `SUPPORTED_TYPES.size()` assertion, so adding a type is safe — verified):
```java
    @Test void supportsTextHtml() {
        assertTrue( new TikaSourceExtractor().supports( "text/html" ) );
    }

    @Test void extractsMarkdownFromHtml() throws Exception {
        String html = "<html><head><title>T</title></head><body><h1>Hello</h1><p>World body.</p></body></html>";
        ExtractionResult r = new TikaSourceExtractor().extract(
            new java.io.ByteArrayInputStream( html.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ),
            "text/html", "page.html" );
        assertTrue( r.success(), "extraction should succeed" );
        assertTrue( r.markdown().toLowerCase().contains( "hello" ), "body should carry the heading text: " + r.markdown() );
    }
```
> Confirm the real `ExtractionResult` accessors (`success()`/`markdown()` or whatever they are — read `ExtractionResult.java`) and adjust the assertions to match. The point is: html → non-empty markdown containing the visible text.

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-ingest -Dtest=TikaSourceExtractorTest -q`) — `supports("text/html")` is false today.

- [ ] **Step 3: Implement** — add `"text/html"` to the `SUPPORTED_TYPES` `Set.of(...)` literal in `TikaSourceExtractor`. (Tika's `tika-parsers-standard-package` bundles the HTML parser; the existing `extract` path parses to XHTML then flexmark→markdown, so no other change is needed.)

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-ingest -Dtest=TikaSourceExtractorTest -q`) — all existing cases + the 2 new ones green.

- [ ] **Step 5: Commit**
```bash
git add wikantik-ingest/src/main/java/com/wikantik/ingest/TikaSourceExtractor.java wikantik-ingest/src/test/java/com/wikantik/ingest/TikaSourceExtractorTest.java
git commit -m "feat(ingest): TikaSourceExtractor supports text/html (crawled HTML → derived page)"
```

---

### Task 2: jsoup dep + `LinkExtractor` + `CrawlScope`

**Files:**
- Modify: `wikantik-connectors/pom.xml` (add jsoup + crawler-commons deps)
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/LinkExtractor.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/CrawlScope.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/LinkExtractorTest.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/CrawlScopeTest.java`

**Interfaces:**
- Produces:
  - `LinkExtractor` — `static java.util.List<String> links(String html, String baseUrl)` (absolute URLs from `<a href>`, deduped, order-preserving) and `static String title(String html)` (page `<title>` or `""`). jsoup-backed. Never throws (parse failure → empty list / "").
  - `CrawlScope(String seedHost, boolean sameHostOnly, String pathPrefix)` with `boolean inScope(String url)` — http/https only, host match when `sameHostOnly`, path starts with `pathPrefix` when set. Malformed URL → false (not scope).

- [ ] **Step 1: Add deps to `wikantik-connectors/pom.xml`** (pin versions directly, mirroring the Phase-1 h2 pin; confirm latest stable at implement time):
```xml
    <dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.18.3</version></dependency>
    <dependency><groupId>com.github.crawler-commons</groupId><artifactId>crawler-commons</artifactId><version>1.4</version></dependency>
```
> If the reactor already manages either version (unlikely — neither is in the repo), use the managed version instead of pinning. Confirm the RAT/license gate accepts them (both Apache-2.0). crawler-commons is used in Task 3, added here so the pom change lands once.

- [ ] **Step 2: Write the failing tests**
```java
// LinkExtractorTest
package com.wikantik.connectors.webcrawler;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LinkExtractorTest {
    @Test void extractsAbsoluteLinksAndResolvesRelative() {
        String html = "<html><body><a href='/a'>a</a><a href='https://ex.com/b'>b</a>"
            + "<a href='sub/c'>c</a><a href='mailto:x@y.z'>m</a></body></html>";
        List<String> links = LinkExtractor.links( html, "https://ex.com/dir/" );
        assertTrue( links.contains( "https://ex.com/a" ) );
        assertTrue( links.contains( "https://ex.com/b" ) );
        assertTrue( links.contains( "https://ex.com/dir/sub/c" ) );
        assertTrue( links.stream().anyMatch( l -> l.startsWith( "mailto:" ) ) ); // extracted; CrawlScope filters it
    }
    @Test void titleAndMalformedSafe() {
        assertEquals( "Hi", LinkExtractor.title( "<html><head><title>Hi</title></head></html>" ) );
        assertEquals( "", LinkExtractor.title( "" ) );
        assertTrue( LinkExtractor.links( "", "https://ex.com" ).isEmpty() );
    }
}
```
```java
// CrawlScopeTest
package com.wikantik.connectors.webcrawler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CrawlScopeTest {
    @Test void sameHostOnly() {
        CrawlScope s = new CrawlScope( "ex.com", true, null );
        assertTrue( s.inScope( "https://ex.com/x" ) );
        assertFalse( s.inScope( "https://other.com/x" ) );
        assertFalse( s.inScope( "mailto:a@b.c" ) );
        assertFalse( s.inScope( "javascript:void(0)" ) );
    }
    @Test void pathPrefixRestriction() {
        CrawlScope s = new CrawlScope( "ex.com", true, "/docs" );
        assertTrue( s.inScope( "https://ex.com/docs/page" ) );
        assertFalse( s.inScope( "https://ex.com/blog/page" ) );
    }
    @Test void crossHostAllowedWhenNotSameHostOnly() {
        assertTrue( new CrawlScope( "ex.com", false, null ).inScope( "https://other.com/x" ) );
    }
}
```

- [ ] **Step 3: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=LinkExtractorTest,CrawlScopeTest -q`).

- [ ] **Step 4: Implement** (Apache headers).
```java
// LinkExtractor.java
package com.wikantik.connectors.webcrawler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** jsoup-backed link + title extraction. Never throws — a parse failure yields an empty result. */
final class LinkExtractor {
    private static final Logger LOG = LogManager.getLogger( LinkExtractor.class );
    private LinkExtractor() {}

    static List< String > links( final String html, final String baseUrl ) {
        try {
            final Document doc = Jsoup.parse( html, baseUrl );
            final Set< String > out = new LinkedHashSet<>();
            for ( final Element a : doc.select( "a[href]" ) ) {
                final String abs = a.absUrl( "href" );   // resolves relative against baseUrl
                if ( abs != null && !abs.isBlank() ) out.add( abs );
            }
            return new ArrayList<>( out );
        } catch ( final RuntimeException e ) {
            LOG.warn( "link extraction failed for {}: {}", baseUrl, e.getMessage() );
            return List.of();
        }
    }

    static String title( final String html ) {
        try { return Jsoup.parse( html == null ? "" : html ).title(); }
        catch ( final RuntimeException e ) { return ""; }
    }
}
```
```java
// CrawlScope.java
package com.wikantik.connectors.webcrawler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.net.URI;

/** In-scope predicate: http/https only, host match (when sameHostOnly), optional path-prefix. */
final class CrawlScope {
    private static final Logger LOG = LogManager.getLogger( CrawlScope.class );
    private final String seedHost;
    private final boolean sameHostOnly;
    private final String pathPrefix;

    CrawlScope( final String seedHost, final boolean sameHostOnly, final String pathPrefix ) {
        this.seedHost = seedHost;
        this.sameHostOnly = sameHostOnly;
        this.pathPrefix = pathPrefix == null || pathPrefix.isBlank() ? null : pathPrefix;
    }

    boolean inScope( final String url ) {
        try {
            final URI u = URI.create( url );
            final String scheme = u.getScheme();
            if ( scheme == null || !( scheme.equals( "http" ) || scheme.equals( "https" ) ) ) return false;
            if ( sameHostOnly && ( u.getHost() == null || !u.getHost().equalsIgnoreCase( seedHost ) ) ) return false;
            if ( pathPrefix != null ) {
                final String path = u.getPath() == null ? "" : u.getPath();
                if ( !path.startsWith( pathPrefix ) ) return false;
            }
            return true;
        } catch ( final RuntimeException e ) {
            LOG.debug( "URL not in scope (unparseable): {}", url );
            return false;
        }
    }
}
```

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=LinkExtractorTest,CrawlScopeTest -q`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-connectors/pom.xml wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/LinkExtractor.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/CrawlScope.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/LinkExtractorTest.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/CrawlScopeTest.java
git commit -m "feat(webcrawler): jsoup link extraction + crawl-scope predicate"
```

---

### Task 3: `PageFetcher` + `HttpPageFetcher` + `RobotsPolicy`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FetchResult.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/PageFetcher.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/HttpPageFetcher.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/RobotsPolicy.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/RobotsPolicyTest.java`

**Interfaces:**
- Produces:
  - `record FetchResult(int status, String contentType, byte[] body, String finalUrl)` — `finalUrl` is the post-redirect URL.
  - `interface PageFetcher { FetchResult fetch(String url); }` — the network seam. `HttpPageFetcher` throws no checked exceptions from `fetch`; on IO error it returns `new FetchResult(0, null, new byte[0], url)` (status 0 = "fetch failed", the crawler treats non-2xx as skip). INJECTABLE — the crawler + robots take a `PageFetcher`.
  - `HttpPageFetcher(String userAgent, java.time.Duration timeout)` — `java.net.http.HttpClient` with `followRedirects(NORMAL)`, sends `User-Agent`, returns bytes.
  - `RobotsPolicy(PageFetcher fetcher, String userAgent)` — `boolean isAllowed(String url)` and `long crawlDelayMs(String url)`. Fetches `scheme://host/robots.txt` once per host (via the injected fetcher), parses with crawler-commons `SimpleRobotRulesParser`, caches per host. Unreachable/failed robots ⇒ **allow-all** (+ one `LOG.warn`).

- [ ] **Step 1: Write the failing test** (fake `PageFetcher` serving robots.txt + pages):
```java
package com.wikantik.connectors.webcrawler;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RobotsPolicyTest {
    private static PageFetcher fetcherServing( Map<String,String> robotsByHostUrl ) {
        return url -> {
            String body = robotsByHostUrl.get( url );
            if ( body == null ) return new FetchResult( 404, "text/plain", new byte[0], url );
            return new FetchResult( 200, "text/plain", body.getBytes( StandardCharsets.UTF_8 ), url );
        };
    }
    @Test void disallowHonored() {
        RobotsPolicy p = new RobotsPolicy( fetcherServing( Map.of(
            "https://ex.com/robots.txt", "User-agent: *\nDisallow: /private\n" ) ), "WikantikCrawler/1.0" );
        assertTrue( p.isAllowed( "https://ex.com/public/x" ) );
        assertFalse( p.isAllowed( "https://ex.com/private/y" ) );
    }
    @Test void crawlDelayParsed() {
        RobotsPolicy p = new RobotsPolicy( fetcherServing( Map.of(
            "https://ex.com/robots.txt", "User-agent: *\nCrawl-delay: 2\n" ) ), "WikantikCrawler/1.0" );
        assertTrue( p.crawlDelayMs( "https://ex.com/x" ) >= 2000 );
    }
    @Test void unreachableRobotsAllowsAll() {
        RobotsPolicy p = new RobotsPolicy( fetcherServing( Map.of() ), "WikantikCrawler/1.0" ); // 404 robots
        assertTrue( p.isAllowed( "https://ex.com/anything" ) );
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=RobotsPolicyTest -q`).

- [ ] **Step 3: Implement** (Apache headers). `FetchResult`/`PageFetcher` are trivial. `HttpPageFetcher` uses `java.net.http.HttpClient` (build once, `followRedirects(NORMAL)`, `connectTimeout`), sends a GET with the `User-Agent` header + per-request `timeout`, returns `new FetchResult(resp.statusCode(), resp.headers().firstValue("content-type").orElse(null), resp.body(), resp.uri().toString())` with `BodyHandlers.ofByteArray()`; any `IOException`/`InterruptedException` → `LOG.warn` + `new FetchResult(0, null, new byte[0], url)` (re-interrupt on `InterruptedException`).

`RobotsPolicy` — **confirm the crawler-commons 1.4 API before writing** (read the jar/javadoc): the shape is
```java
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
// per host, once:
FetchResult r = fetcher.fetch(scheme + "://" + host + "/robots.txt");
SimpleRobotRules rules;
if ( r.status() / 100 == 2 && r.body().length > 0 ) {
    rules = new SimpleRobotRulesParser().parseContent( robotsUrl, r.body(),
        r.contentType() == null ? "text/plain" : r.contentType(), userAgent );
} else {
    rules = new SimpleRobotRulesParser().parseContent( robotsUrl, new byte[0], "text/plain", userAgent );
    // an empty/failed robots ⇒ allow-all (SimpleRobotRules default for empty content)
    LOG.warn( "robots.txt unavailable for {} — treating as allow-all", host );
}
// isAllowed(url) → rules.isAllowed(url); crawlDelayMs(url) → rules.getCrawlDelay() (seconds; -1/UNSET → 0) * 1000
```
Cache `SimpleRobotRules` in a `Map<String,SimpleRobotRules>` keyed by host. `getCrawlDelay()` returns seconds (or a sentinel for unset — map ≤0/unset to 0). **The implementer verifies `parseContent`'s exact signature + `getCrawlDelay`'s unit/sentinel against crawler-commons 1.4 and adjusts.**

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=RobotsPolicyTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FetchResult.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/PageFetcher.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/HttpPageFetcher.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/RobotsPolicy.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/RobotsPolicyTest.java
git commit -m "feat(webcrawler): PageFetcher seam + HttpPageFetcher + robots.txt policy (crawler-commons)"
```

---

### Task 4: `WebCrawlerConfig` + `WebCrawlerSourceConnector`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebCrawlerConfig.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebCrawlerSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/WebCrawlerSourceConnectorTest.java`

**Interfaces:**
- Consumes: `SourceConnector`/`SourceItem`/`SyncBatch`/`SyncCursor` (Phase 1); `PageFetcher`/`RobotsPolicy`/`LinkExtractor`/`CrawlScope` (T2/T3).
- Produces:
  - `record WebCrawlerConfig(List<String> seeds, boolean sameHostOnly, String pathPrefix, int maxPages, int maxDepth, long delayMs, String userAgent, boolean respectRobots)`.
  - `WebCrawlerSourceConnector(String connectorId, WebCrawlerConfig config, PageFetcher fetcher, java.util.function.LongConsumer sleeper)` — the `sleeper` seam replaces `Thread.sleep` so tests don't actually wait (production passes `ms -> Thread.sleep(ms)` wrapped fail-closed). Implements `SourceConnector`. `poll` runs the BFS from the spec.

- [ ] **Step 1: Write the failing test** (fake `PageFetcher` serving a small HTML graph + robots; `sleeper` is a no-op counter):
```java
package com.wikantik.connectors.webcrawler;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WebCrawlerSourceConnectorTest {

    private static FetchResult html( String url, String body ) {
        return new FetchResult( 200, "text/html", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    /** Serves a canned site: root links to /a and /b; /a links to /c (in scope) and to other.com (out). */
    private static PageFetcher site() {
        Map<String,FetchResult> m = new HashMap<>();
        m.put( "https://ex.com/robots.txt", new FetchResult( 404, "text/plain", new byte[0], "https://ex.com/robots.txt" ) );
        m.put( "https://ex.com/", html( "https://ex.com/", "<a href='/a'>a</a><a href='/b'>b</a>" ) );
        m.put( "https://ex.com/a", html( "https://ex.com/a", "<a href='/c'>c</a><a href='https://other.com/x'>o</a>" ) );
        m.put( "https://ex.com/b", html( "https://ex.com/b", "<p>leaf b</p>" ) );
        m.put( "https://ex.com/c", html( "https://ex.com/c", "<p>leaf c</p>" ) );
        return url -> m.getOrDefault( url, new FetchResult( 404, null, new byte[0], url ) );
    }
    private static WebCrawlerConfig cfg( int maxPages, int maxDepth ) {
        return new WebCrawlerConfig( List.of( "https://ex.com/" ), true, null, maxPages, maxDepth, 0, "WikantikCrawler/1.0", true );
    }
    private static WebCrawlerSourceConnector crawler( WebCrawlerConfig c ) {
        return new WebCrawlerSourceConnector( "web1", c, site(), ms -> {} );  // no-op sleeper
    }

    @Test void crawlsInScopePagesBreadthFirst() {
        SyncBatch b = crawler( cfg( 100, 5 ) ).poll( null );
        Set<String> uris = new HashSet<>();
        for ( SourceItem i : b.items() ) uris.add( i.sourceUri() );
        assertTrue( uris.containsAll( Set.of( "https://ex.com/", "https://ex.com/a", "https://ex.com/b", "https://ex.com/c" ) ) );
        assertFalse( uris.contains( "https://other.com/x" ), "out-of-scope host must not be crawled" );
        assertTrue( b.complete() );
        assertTrue( b.items().stream().allMatch( i -> "text/html".equals( i.contentType() ) && i.contentHash().length() == 64 ) );
    }
    @Test void respectsMaxPages() {
        assertTrue( crawler( cfg( 2, 5 ) ).poll( null ).items().size() <= 2 );
    }
    @Test void respectsMaxDepth() {
        // depth 0 = root only
        SyncBatch b = crawler( cfg( 100, 0 ) ).poll( null );
        assertEquals( 1, b.items().size() );
        assertEquals( "https://ex.com/", b.items().get( 0 ).sourceUri() );
    }
    @Test void skipsRobotsDisallowed() {
        PageFetcher f = url -> {
            if ( url.equals( "https://ex.com/robots.txt" ) )
                return new FetchResult( 200, "text/plain", "User-agent: *\nDisallow: /a\n".getBytes( StandardCharsets.UTF_8 ), url );
            if ( url.equals( "https://ex.com/" ) ) return html( url, "<a href='/a'>a</a><a href='/b'>b</a>" );
            if ( url.equals( "https://ex.com/b" ) ) return html( url, "<p>b</p>" );
            return html( url, "<p>should not be fetched</p>" );
        };
        SyncBatch b = new WebCrawlerSourceConnector( "web1", cfg( 100, 5 ), f, ms -> {} ).poll( null );
        Set<String> uris = new HashSet<>();
        for ( SourceItem i : b.items() ) uris.add( i.sourceUri() );
        assertFalse( uris.contains( "https://ex.com/a" ), "/a is robots-disallowed" );
        assertTrue( uris.contains( "https://ex.com/b" ) );
    }
    @Test void skipsNon2xxAndNonHtml() {
        PageFetcher f = url -> {
            if ( url.equals( "https://ex.com/robots.txt" ) ) return new FetchResult( 404, null, new byte[0], url );
            if ( url.equals( "https://ex.com/" ) ) return html( url, "<a href='/dead'>d</a><a href='/img'>i</a>" );
            if ( url.equals( "https://ex.com/dead" ) ) return new FetchResult( 500, "text/html", new byte[0], url );
            if ( url.equals( "https://ex.com/img" ) ) return new FetchResult( 200, "image/png", new byte[]{1,2}, url );
            return new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new WebCrawlerSourceConnector( "web1", cfg( 100, 5 ), f, ms -> {} ).poll( null );
        assertEquals( 1, b.items().size() );  // only the root; /dead (500) and /img (non-html) skipped
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=WebCrawlerSourceConnectorTest -q`).

- [ ] **Step 3: Implement** (Apache headers). `WebCrawlerConfig` is a plain record. `WebCrawlerSourceConnector`:
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.LongConsumer;

/** BFS web crawler. Emits one SourceItem per in-scope, robots-allowed, 2xx HTML page. Fail-closed. */
public final class WebCrawlerSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( WebCrawlerSourceConnector.class );

    private final String connectorId;
    private final WebCrawlerConfig config;
    private final PageFetcher fetcher;
    private final LongConsumer sleeper;

    public WebCrawlerSourceConnector( final String connectorId, final WebCrawlerConfig config,
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
        final Set< String > visited = new HashSet<>();
        final Deque< Node > queue = new ArrayDeque<>();
        for ( final String seed : config.seeds() ) queue.add( new Node( seed, 0 ) );
        final List< SourceItem > items = new ArrayList<>();

        while ( !queue.isEmpty() && items.size() < config.maxPages() ) {
            final Node n = queue.poll();
            if ( !visited.add( n.url ) || n.depth > config.maxDepth() ) continue;

            final CrawlScope scope = scopeFor( n.url );
            if ( config.respectRobots() && !robots.isAllowed( n.url ) ) {
                LOG.info( "crawler '{}': robots-disallowed, skipping {}", connectorId, n.url );
                continue;
            }
            sleepPolitely( robots, n.url );

            final FetchResult r = fetcher.fetch( n.url );      // fetcher is fail-closed (status 0 on error)
            if ( r.status() / 100 != 2 || !isHtml( r.contentType() ) ) continue;

            final String finalUrl = r.finalUrl() == null ? n.url : r.finalUrl();
            final String html = new String( r.body(), java.nio.charset.StandardCharsets.UTF_8 );
            items.add( item( finalUrl, r ) );

            if ( n.depth < config.maxDepth() ) {
                for ( final String link : LinkExtractor.links( html, finalUrl ) ) {
                    if ( !visited.contains( link ) && scope != null && scope.inScope( link ) ) {
                        queue.add( new Node( link, n.depth + 1 ) );
                    }
                }
            }
        }
        if ( items.size() >= config.maxPages() ) {
            LOG.info( "crawler '{}': hit max_pages={}, crawl truncated", connectorId, config.maxPages() );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private CrawlScope scopeFor( final String url ) {
        try { return new CrawlScope( URI.create( url ).getHost(), config.sameHostOnly(), config.pathPrefix() ); }
        catch ( final RuntimeException e ) { return null; }
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        final long delay = Math.max( config.delayMs(), robots.crawlDelayMs( url ) );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private SourceItem item( final String url, final FetchResult r ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "url", url );
        md.put( "title", LinkExtractor.title( new String( r.body(), java.nio.charset.StandardCharsets.UTF_8 ) ) );
        md.put( "fetchedAt", Instant.now().toString() );      // NB: Instant.now is fine in prod; tests don't assert it
        md.put( "httpStatus", r.status() );
        return new SourceItem( url, r.body(), "text/html", md, List.of(), sha256Hex( r.body() ) );
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }

    private static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final java.security.NoSuchAlgorithmException e ) { throw new IllegalStateException( e ); }
    }

    private record Node( String url, int depth ) {}
}
```
> Note the depth semantics matching the tests: root is depth 0 and IS emitted; with `maxDepth=0` only the seed(s) are fetched (links are enqueued only when `n.depth < maxDepth`). `Instant.now()` is used for metadata only and is not asserted by tests (the workflow-script `Date.now` ban does not apply to production Java).

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=WebCrawlerSourceConnectorTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebCrawlerConfig.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebCrawlerSourceConnector.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/WebCrawlerSourceConnectorTest.java
git commit -m "feat(webcrawler): WebCrawlerSourceConnector — BFS crawl, scope/depth/robots/politeness"
```

---

### Task 5: Wire webcrawler connectors in `ConnectorWiringHelper` + config keys

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java` (extend)

**Interfaces:**
- Consumes: `WebCrawlerSourceConnector`, `WebCrawlerConfig`, `HttpPageFetcher` (T2–T4).
- Produces: `wireConnectors` also registers webcrawler connectors from `wikantik.connectors.webcrawler.<id>.seeds` keys. Add a package-visible `Map<String, WebCrawlerConfig> webcrawlerConfigs(Properties)` (sibling to `filesystemRoots`) for the test.

- [ ] **Step 1: Write the failing test** (extend `ConnectorWiringHelperTest`):
```java
    @Test void parsesWebcrawlerConfigsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.webcrawler.site.seeds", "https://a.com/, https://a.com/docs" );
        p.setProperty( "wikantik.connectors.webcrawler.site.max_pages", "50" );
        p.setProperty( "wikantik.connectors.webcrawler.site.path_prefix", "/docs" );
        Map< String, WebCrawlerConfig > cfgs = ConnectorWiringHelper.webcrawlerConfigs( p );
        assertEquals( 1, cfgs.size() );
        WebCrawlerConfig c = cfgs.get( "site" );
        assertEquals( List.of( "https://a.com/", "https://a.com/docs" ), c.seeds() );
        assertEquals( 50, c.maxPages() );
        assertEquals( "/docs", c.pathPrefix() );
        assertTrue( c.sameHostOnly() );          // default
        assertTrue( c.respectRobots() );          // default
    }
    @Test void webcrawlerRequiresSeeds() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.webcrawler.nope.max_pages", "10" );  // no seeds → skipped
        assertTrue( ConnectorWiringHelper.webcrawlerConfigs( p ).isEmpty() );
    }
```
(`import com.wikantik.connectors.webcrawler.WebCrawlerConfig;`)

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`).

- [ ] **Step 3: Implement.** Add `webcrawlerConfigs(Properties)` (parse `webcrawler.<id>.seeds` → split on comma+trim; read the per-id `max_pages`/`max_depth`/`same_host_only`/`path_prefix`/`delay_ms`/`user_agent`/`respect_robots` with the defaults from the spec; skip an `<id>` with no non-blank `seeds`). In `wireConnectors`, after the filesystem loop, add a webcrawler loop:
```java
        for ( final Map.Entry< String, WebCrawlerConfig > e : webcrawlerConfigs( props ).entrySet() ) {
            byId.put( e.getKey(), new WebCrawlerSourceConnector( e.getKey(), e.getValue(),
                new HttpPageFetcher( e.getValue().userAgent(), java.time.Duration.ofSeconds( 20 ) ),
                ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } } ) );
            typeById.put( e.getKey(), "webcrawler" );
        }
```
And update the "nothing to sync" early-return guard (line ~58): the current `if (roots.isEmpty())` must become `if (roots.isEmpty() && webcrawlerConfigs(props).isEmpty())` so a webcrawler-only config still wires. (Compute both maps once, reuse.)

- [ ] **Step 4: Register config keys** in `wikantik-main/src/main/resources/ini/wikantik.properties` (near the existing `wikantik.connectors.*` block):
```properties
# Web-crawler connectors (auth-free). Each <id> needs at least .seeds (comma-separated URLs).
#wikantik.connectors.webcrawler.<id>.seeds = https://example.com/
#wikantik.connectors.webcrawler.<id>.same_host_only = true
#wikantik.connectors.webcrawler.<id>.path_prefix =
#wikantik.connectors.webcrawler.<id>.max_pages = 100
#wikantik.connectors.webcrawler.<id>.max_depth = 3
#wikantik.connectors.webcrawler.<id>.delay_ms = 1000
#wikantik.connectors.webcrawler.<id>.user_agent = WikantikCrawler/1.0 (+https://wiki.wikantik.com)
#wikantik.connectors.webcrawler.<id>.respect_robots = true
```

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`) and compile-check (`mvn -q -pl wikantik-main -am compile`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java wikantik-main/src/main/resources/ini/wikantik.properties wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java
git commit -m "feat(webcrawler): wire webcrawler connectors from config (ConnectorWiringHelper)"
```

---

## Post-implementation (controller)

- Full reactor unit build: `mvn clean install -DskipITs` **with `WIKANTIK_*` env UNSET** (WikiTest env-count sensitivity).
- Confirm jsoup + crawler-commons pass the RAT/license gate (both Apache-2.0) and don't break the dependency-audit/OSV posture.
- Whole-branch review (opus): fail-closed (fetch/robots/parse never abort `poll`); the crawler is only a new `SourceConnector` (no Phase-1 SPI / P2.1 runtime change); invariant #6 (crawler in `wikantik-connectors`, only wiring growth in `wikantik-main`'s existing package + one MIME type in `wikantik-ingest`); politeness defaults sane; `text/html` extractor change didn't alter existing extraction; dependency direction (`wikantik-connectors` main deps = api + jsoup + crawler-commons, still no `wikantik-main`).

## Self-review notes

- **Spec coverage:** text/html extraction (T1), link+scope (T2), fetch+robots (T3), crawl BFS (T4), wiring+config (T5). All spec sections mapped. No live-network IT (per the approved spec).
- **Invariant #6:** crawler in `wikantik-connectors`; `wikantik-main` only grows `ConnectorWiringHelper`'s config parse; `wikantik-ingest` gains one MIME type.
- **Fail-closed:** `HttpPageFetcher` returns status 0 on IO error; the crawl treats non-2xx/non-HTML as skip; `RobotsPolicy` allow-all on unreachable robots; `LinkExtractor` never throws; `poll()` has no throwing path.
- **Type consistency:** `WebCrawlerConfig(seeds, sameHostOnly, pathPrefix, maxPages, maxDepth, delayMs, userAgent, respectRobots)`, `FetchResult(status, contentType, body, finalUrl)`, `PageFetcher.fetch(String)`, `RobotsPolicy(fetcher, userAgent).isAllowed/crawlDelayMs`, `WebCrawlerSourceConnector(id, config, fetcher, sleeper)` — identical across T2→T5.

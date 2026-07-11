# RSS/Atom Feed Connector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `FeedSourceConnector` that reads RSS/Atom feeds (via Rome) and syncs each entry into a derived page — fetching the full article by default (inline toggle), with **archive** semantics (aged-out entries kept, not deleted) enabled by a small additive `SourceConnector.reflectsFullCorpus()` gate.

**Architecture:** New classes in the existing `com.wikantik.connectors.webcrawler` package (reusing the crawler's `PageFetcher`/`RobotsPolicy`/`WebFetchItems`). Rome (`com.rometools:rome`, version-managed at 2.1.0 in the parent) is added to `wikantik-connectors`. `SourceConnector` gains an additive default method + the orchestrator gates its derived-tombstone loop on it. `ConnectorWiringHelper` gains a `feedConfigs` parser.

**Tech Stack:** Java 25, Maven, JUnit 5, Rome (feed parsing), reused `java.net.http`/crawler-commons/jsoup via the crawler primitives.

**Spec:** `docs/superpowers/specs/2026-07-11-rss-atom-feed-connector-design.md`

## Global Constraints

- **P2.1 runtime surface unchanged** (`ConnectorRuntime`/`ConnectorAdminResource` untouched). The ONLY Phase-1 SPI/orchestrator change is the **additive** `reflectsFullCorpus()` default method + the one-line orchestrator gate — backward-compatible (existing connectors inherit `true`, behavior unchanged).
- **Invariant #6:** connector + parser in `wikantik-connectors`; `wikantik-main` gains only `ConnectorWiringHelper` config growth. No new `wikantik-main` package.
- **Rome dep:** add `com.rometools:rome` (NO version — inherits the managed 2.1.0) to `wikantik-connectors/pom.xml`. Apache-2.0, RAT-safe.
- **Fail-closed:** `fetcher.fetch` never throws; `FeedParser.parse` never throws (malformed → empty + `LOG.warn`); `poll()` has no throwing path. No empty catch (repo rule — always `LOG.warn` in a catch).
- **Default-off:** no `wikantik.connectors.feed.*` keys ⇒ no connector registered.
- **Behavior-preserving orchestrator change:** the existing `SyncOrchestratorTest` cases stay green unmodified (existing connectors default `reflectsFullCorpus()==true`).
- **Unit-tested via injected `PageFetcher`** + canned RSS/Atom XML; no live IT.
- **TDD:** failing test first; run only the task's targeted test.

---

### Task 1: `SourceConnector.reflectsFullCorpus()` + orchestrator gate (archive enablement)

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/connectors/SourceConnector.java` (add default method)
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/SyncOrchestrator.java` (gate the derived-tombstone loop)
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/SyncOrchestratorTest.java` (extend)

**Interfaces:**
- Produces: `SourceConnector.reflectsFullCorpus()` — `default boolean reflectsFullCorpus() { return true; }`. A connector whose `poll()` reflects the *full current source set* (so a not-seen URI implies deletion-at-source) returns `true` (filesystem/crawler/sitemap inherit it); a *windowed* source (feed) overrides `false`. The orchestrator derives tombstones from absence ONLY when `true`.

- [ ] **Step 1: Write the failing test** — add to `SyncOrchestratorTest`. (Read the existing test's fakes: it has a `FakeStore`/`FakeSink`/`item(...)`/`single(batch)` helper and a `derivesTombstonesFromKnownUrisNotSeenThisScan`-style case. Mirror them.)
```java
    // A connector that does NOT reflect the full corpus (e.g. a feed window) → orchestrator must NOT
    // tombstone a previously-synced URI merely because it's absent from this poll.
    @Test void archiveConnectorDoesNotTombstoneAgedOutUris() {
        FakeStore store = new FakeStore();
        store.hash.put( "file:gone.md", "hg" ); store.page.put( "file:gone.md", "Gone" );   // previously synced
        FakeSink sink = new FakeSink();
        SourceConnector windowed = new SourceConnector() {
            public String connectorId() { return "c1"; }
            public SyncBatch poll( SyncCursor c ) {
                return new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true );
            }
            @Override public boolean reflectsFullCorpus() { return false; }   // archive
        };
        new SyncOrchestrator( store, sink ).sync( windowed );
        assertTrue( sink.deleted.isEmpty(), "windowed connector must not tombstone aged-out URIs" );
        assertTrue( store.hash.containsKey( "file:gone.md" ), "aged-out URI stays synced (archived)" );
    }
```
> Keep the existing `derivesTombstonesFromKnownUrisNotSeenThisScan` test (a default-`true` connector) UNMODIFIED — it proves the `true` path still tombstones. If the fake helpers differ from the names above, adapt to the real ones in the file.

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=SyncOrchestratorTest -q`) — `reflectsFullCorpus` not defined / tombstone still fires.

- [ ] **Step 3: Implement.**

In `SourceConnector.java`, add the default method (Javadoc):
```java
public interface SourceConnector {
    /** Stable id; namespaces item URIs and sync-state rows. */
    String connectorId();
    /** @param cursor last persisted checkpoint, or {@code null} for a full initial sync. */
    SyncBatch poll( SyncCursor cursor );
    /**
     * Whether this connector's {@link #poll} reflects the <em>full current source set</em>, so a
     * previously-synced URI that is absent from a poll means "deleted at source" (the orchestrator
     * tombstones it). Full-corpus connectors (filesystem, crawler, sitemap) return {@code true}.
     * Windowed sources (e.g. an RSS/Atom feed showing only the latest N entries) return {@code false}
     * so aged-out items are <em>archived</em>, not deleted.
     */
    default boolean reflectsFullCorpus() { return true; }
}
```

In `SyncOrchestrator.sync(...)`, gate the derived-tombstone loop (the `if ( batch.complete() )` block) on the connector:
```java
            // derived tombstones: only on a COMPLETE batch from a full-corpus connector
            if ( batch.complete() && connector.reflectsFullCorpus() ) {
                for ( final String uri : store.knownUris( id ) ) {
                    if ( !seen.contains( uri ) && !batch.tombstonedUris().contains( uri ) ) {
                        deleted += tombstone( id, uri );
                    }
                }
            }
```
(Explicit `batch.tombstonedUris()` are still processed unconditionally above — unchanged.)

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=SyncOrchestratorTest -q`) — new archive test green AND all existing orchestrator cases still green (the default-`true` tombstone path unchanged).

- [ ] **Step 5: Commit**
```bash
git add wikantik-api/src/main/java/com/wikantik/api/connectors/SourceConnector.java wikantik-connectors/src/main/java/com/wikantik/connectors/SyncOrchestrator.java wikantik-connectors/src/test/java/com/wikantik/connectors/SyncOrchestratorTest.java
git commit -m "feat(connectors): SourceConnector.reflectsFullCorpus() gate — archive windowed sources (feeds)"
```

---

### Task 2: `WebFetchItems.toItemFromContent` (inline-item variant)

**Files:**
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebFetchItems.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/WebFetchItemsTest.java` (extend)

**Interfaces:**
- Produces: `static SourceItem WebFetchItems.toItemFromContent(String url, byte[] htmlBytes, String title)` — a text/html `SourceItem` from already-in-hand HTML (no page fetch): `sourceUri=url`, `content=htmlBytes`, `contentType="text/html"`, metadata `{url, title (or ""), fetchedAt=Instant.now().toString()}` (that order, LinkedHashMap — NO `httpStatus`), `aclRefs=List.of()`, `contentHash=sha256Hex(htmlBytes)`.

- [ ] **Step 1: Write the failing test** (add to `WebFetchItemsTest`):
```java
    @Test void toItemFromContentBuildsTextHtmlItem() {
        byte[] html = "<p>inline feed content</p>".getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        com.wikantik.api.connectors.SourceItem i = WebFetchItems.toItemFromContent( "https://ex.com/post", html, "My Post" );
        assertEquals( "https://ex.com/post", i.sourceUri() );
        assertEquals( "text/html", i.contentType() );
        assertArrayEquals( html, i.content() );
        assertTrue( i.aclRefs().isEmpty() );
        assertEquals( 64, i.contentHash().length() );
        assertEquals( "My Post", i.sourceMetadata().get( "title" ) );
        assertEquals( "https://ex.com/post", i.sourceMetadata().get( "url" ) );
        assertNotNull( i.sourceMetadata().get( "fetchedAt" ) );
        assertFalse( i.sourceMetadata().containsKey( "httpStatus" ) );   // no page fetch
    }
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=WebFetchItemsTest -q`).

- [ ] **Step 3: Implement** — add to `WebFetchItems`:
```java
    static SourceItem toItemFromContent( final String url, final byte[] htmlBytes, final String title ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "url", url );
        md.put( "title", title == null ? "" : title );
        md.put( "fetchedAt", Instant.now().toString() );
        return new SourceItem( url, htmlBytes, "text/html", md, List.of(), sha256Hex( htmlBytes ) );
    }
```

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=WebFetchItemsTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/WebFetchItems.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/WebFetchItemsTest.java
git commit -m "feat(webcrawler): WebFetchItems.toItemFromContent — inline HTML source item (for feeds)"
```

---

### Task 3: Rome dep + `FeedParser` + `FeedEntry`

**Files:**
- Modify: `wikantik-connectors/pom.xml` (add `com.rometools:rome`, no version)
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedEntry.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedParser.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/FeedParserTest.java`

**Interfaces:**
- Produces:
  - `record FeedEntry(String title, String link, String contentHtml)`.
  - `static java.util.List<FeedEntry> FeedParser.parse(byte[] xml, String baseUrl)` — Rome parse; one `FeedEntry` per entry with a non-blank link; `contentHtml` = joined `getContents()` values if present, else `getDescription().getValue()`, else ""; malformed/empty → `List.of()` (never throws; `LOG.warn`).

- [ ] **Step 1: Add the Rome dependency** to `wikantik-connectors/pom.xml` (managed version, so no `<version>`):
```xml
    <dependency><groupId>com.rometools</groupId><artifactId>rome</artifactId></dependency>
```
> Confirm it resolves via the parent's `dependencyManagement` (pinned 2.1.0) — the targeted test forces the download. If the version isn't inherited, pin `<version>2.1.0</version>` explicitly.

- [ ] **Step 2: Write the failing test**
```java
package com.wikantik.connectors.webcrawler;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeedParserTest {
    private static List<FeedEntry> parse( String xml ) {
        return FeedParser.parse( xml.getBytes( StandardCharsets.UTF_8 ), "https://ex.com/feed" );
    }

    @Test void parsesRss2WithContentEncoded() {
        String rss = "<?xml version='1.0'?><rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/'>"
            + "<channel><title>Ch</title>"
            + "<item><title>A</title><link>https://ex.com/a</link>"
            + "<description>sum-a</description><content:encoded>&lt;p&gt;full a&lt;/p&gt;</content:encoded></item>"
            + "<item><title>B</title><link>https://ex.com/b</link><description>sum-b</description></item>"
            + "</channel></rss>";
        List<FeedEntry> e = parse( rss );
        assertEquals( 2, e.size() );
        assertEquals( "A", e.get( 0 ).title() );
        assertEquals( "https://ex.com/a", e.get( 0 ).link() );
        assertTrue( e.get( 0 ).contentHtml().contains( "full a" ) );   // content:encoded preferred
        assertTrue( e.get( 1 ).contentHtml().contains( "sum-b" ) );    // description fallback
    }

    @Test void parsesAtom() {
        String atom = "<?xml version='1.0'?><feed xmlns='http://www.w3.org/2005/Atom'><title>F</title>"
            + "<entry><title>E1</title><link href='https://ex.com/e1'/><content type='html'>&lt;p&gt;c1&lt;/p&gt;</content></entry>"
            + "<entry><title>E2</title><link href='https://ex.com/e2'/><summary>s2</summary></entry></feed>";
        List<FeedEntry> e = parse( atom );
        assertEquals( 2, e.size() );
        assertEquals( "https://ex.com/e1", e.get( 0 ).link() );        // Atom link href
        assertTrue( e.get( 0 ).contentHtml().contains( "c1" ) );
        assertTrue( e.get( 1 ).contentHtml().contains( "s2" ) );       // summary fallback
    }

    @Test void skipsBlankLinkAndMalformedIsSafe() {
        assertTrue( parse( "" ).isEmpty() );
        assertTrue( parse( "not a feed <<<" ).isEmpty() );
    }
}
```

- [ ] **Step 3: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=FeedParserTest -q`).

- [ ] **Step 4: Implement** (Apache headers).
```java
// FeedEntry.java
package com.wikantik.connectors.webcrawler;
/** One RSS/Atom feed entry: title, link (article URL), and its inline HTML content. */
record FeedEntry( String title, String link, String contentHtml ) {}
```
```java
// FeedParser.java
package com.wikantik.connectors.webcrawler;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Parses RSS 0.9–2.0 and Atom feeds via Rome. Never throws — a malformed feed yields an empty list. */
final class FeedParser {
    private static final Logger LOG = LogManager.getLogger( FeedParser.class );
    private FeedParser() {}

    static List< FeedEntry > parse( final byte[] xml, final String baseUrl ) {
        if ( xml == null || xml.length == 0 ) return List.of();
        try ( XmlReader reader = new XmlReader( new ByteArrayInputStream( xml ) ) ) {
            final SyndFeed feed = new SyndFeedInput().build( reader );
            final List< FeedEntry > out = new ArrayList<>();
            for ( final SyndEntry e : feed.getEntries() ) {
                final String link = e.getLink() == null ? "" : e.getLink().trim();
                if ( link.isBlank() ) continue;   // no stable URI → skip
                final String title = e.getTitle() == null ? "" : e.getTitle();
                out.add( new FeedEntry( title, link, contentOf( e ) ) );
            }
            return out;
        } catch ( final Exception ex ) {   // FeedException, IOException, IllegalArgumentException, …
            LOG.warn( "feed parse failed for {}: {}", baseUrl, ex.getMessage() );
            return List.of();
        }
    }

    private static String contentOf( final SyndEntry e ) {
        if ( e.getContents() != null && !e.getContents().isEmpty() ) {
            final StringBuilder sb = new StringBuilder();
            for ( final SyndContent c : e.getContents() ) {
                if ( c.getValue() != null ) sb.append( c.getValue() );
            }
            if ( !sb.isEmpty() ) return sb.toString();
        }
        if ( e.getDescription() != null && e.getDescription().getValue() != null ) {
            return e.getDescription().getValue();
        }
        return "";
    }
}
```
> Rome's `build(Reader)` is the entry point; `XmlReader` (`com.rometools.rome.io.XmlReader`) detects the feed's charset and is `AutoCloseable`. `catch (Exception)` is deliberate here (Rome throws checked `FeedException` + `IOException` + unchecked parse errors) — it logs, so it's not a silent swallow.

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=FeedParserTest -q`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-connectors/pom.xml wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedEntry.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedParser.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/FeedParserTest.java
git commit -m "feat(feed): Rome-backed FeedParser (RSS 0.9-2.0 + Atom)"
```

---

### Task 4: `FeedConfig` + `FeedSourceConnector`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedConfig.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/FeedSourceConnectorTest.java`

**Interfaces:**
- Consumes: `SourceConnector`/`SourceItem`/`SyncBatch`/`SyncCursor` (Phase 1); `PageFetcher`/`FetchResult`/`RobotsPolicy`/`WebFetchItems` (webcrawler); `FeedParser`/`FeedEntry` (T3).
- Produces:
  - `record FeedConfig(List<String> feedUrls, int maxItems, boolean fetchFullArticles, long delayMs, String userAgent, boolean respectRobots, boolean sameHostOnly)`.
  - `FeedSourceConnector(String connectorId, FeedConfig config, PageFetcher fetcher, java.util.function.LongConsumer sleeper)` implementing `SourceConnector`, with `reflectsFullCorpus()` overridden to `false`. `poll` per the spec: fetch each feed, parse entries; per entry (≤maxItems, visited-dedup, same-host filter): full-article mode → fetch the link (robots+politeness) → `WebFetchItems.toItem`; inline mode → `WebFetchItems.toItemFromContent(link, contentHtml.bytes, title)` (skip blank content). One complete batch.

- [ ] **Step 1: Write the failing test**
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedSourceConnectorTest {

    private static FetchResult feed( String url, String body ) {
        return new FetchResult( 200, "application/rss+xml", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static FetchResult html( String url, String body ) {
        return new FetchResult( 200, "text/html", body.getBytes( StandardCharsets.UTF_8 ), url );
    }
    private static final String RSS =
        "<rss version='2.0'><channel><title>C</title>"
        + "<item><title>A</title><link>https://ex.com/a</link><description>&lt;p&gt;inline a&lt;/p&gt;</description></item>"
        + "<item><title>B</title><link>https://ex.com/b</link><description>&lt;p&gt;inline b&lt;/p&gt;</description></item>"
        + "</channel></rss>";
    private static FeedConfig cfg( int maxItems, boolean fetchFull ) {
        return new FeedConfig( List.of( "https://ex.com/feed.xml" ), maxItems, fetchFull, 0,
            "WikantikCrawler/1.0", true, true );
    }
    private static Set<String> uris( SyncBatch b ) {
        Set<String> s = new HashSet<>();
        for ( SourceItem i : b.items() ) s.add( i.sourceUri() );
        return s;
    }

    @Test void reflectsFullCorpusIsFalse() {
        assertFalse( new FeedSourceConnector( "f1", cfg( 100, true ), u -> new FetchResult( 404, null, new byte[0], u ), ms -> {} )
            .reflectsFullCorpus() );
    }

    @Test void fetchFullArticlesFetchesEachLink() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 404, null, new byte[0], url );
            case "https://ex.com/feed.xml" -> feed( url, RSS );
            case "https://ex.com/a", "https://ex.com/b" -> html( url, "<p>full article</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new FeedSourceConnector( "f1", cfg( 100, true ), f, ms -> {} ).poll( null );
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ), uris( b ) );
        assertTrue( b.items().stream().allMatch( i -> new String( i.content() ).contains( "full article" ) ) );
        assertTrue( b.complete() );
    }

    @Test void inlineModeEmitsEntryContentWithoutArticleFetch() {
        Set<String> fetched = new HashSet<>();
        PageFetcher f = url -> {
            fetched.add( url );
            if ( url.equals( "https://ex.com/feed.xml" ) ) return feed( url, RSS );
            return new FetchResult( 404, null, new byte[0], url );
        };
        SyncBatch b = new FeedSourceConnector( "f1", cfg( 100, false ), f, ms -> {} ).poll( null );
        assertEquals( Set.of( "https://ex.com/a", "https://ex.com/b" ), uris( b ) );
        assertTrue( b.items().stream().anyMatch( i -> new String( i.content() ).contains( "inline a" ) ) );
        assertFalse( fetched.contains( "https://ex.com/a" ), "inline mode must not fetch the article link" );
    }

    @Test void respectsMaxItems() {
        PageFetcher f = url -> url.equals( "https://ex.com/feed.xml" ) ? feed( url, RSS )
            : new FetchResult( 404, null, new byte[0], url );
        assertTrue( new FeedSourceConnector( "f1", cfg( 1, false ), f, ms -> {} ).poll( null ).items().size() <= 1 );
    }

    @Test void fullArticleSkipsRobotsDisallowedAndNonHtml() {
        PageFetcher f = url -> switch ( url ) {
            case "https://ex.com/robots.txt" -> new FetchResult( 200, "text/plain",
                "User-agent: *\nDisallow: /a\n".getBytes( StandardCharsets.UTF_8 ), url );
            case "https://ex.com/feed.xml" -> feed( url, RSS );
            case "https://ex.com/b" -> html( url, "<p>b</p>" );
            default -> new FetchResult( 404, null, new byte[0], url );
        };
        Set<String> u = uris( new FeedSourceConnector( "f1", cfg( 100, true ), f, ms -> {} ).poll( null ) );
        assertEquals( Set.of( "https://ex.com/b" ), u );   // /a robots-disallowed
    }
}
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-connectors -Dtest=FeedSourceConnectorTest -q`).

- [ ] **Step 3: Implement** (Apache headers). `FeedConfig` is a plain record. `FeedSourceConnector`:
```java
package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongConsumer;

/** Reads RSS/Atom feeds and emits a SourceItem per entry. Full-article (default) or inline content.
 *  A feed is a rolling window, so {@link #reflectsFullCorpus()} is false (aged-out entries are archived). */
public final class FeedSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( FeedSourceConnector.class );

    private final String connectorId;
    private final FeedConfig config;
    private final PageFetcher fetcher;
    private final LongConsumer sleeper;

    public FeedSourceConnector( final String connectorId, final FeedConfig config,
                                final PageFetcher fetcher, final LongConsumer sleeper ) {
        this.connectorId = connectorId;
        this.config = config;
        this.fetcher = fetcher;
        this.sleeper = sleeper;
    }

    @Override public String connectorId() { return connectorId; }

    @Override public boolean reflectsFullCorpus() { return false; }   // windowed source → archive

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final RobotsPolicy robots = new RobotsPolicy( fetcher, config.userAgent() );
        final Set< String > allowedHosts = new HashSet<>();
        for ( final String feedUrl : config.feedUrls() ) hostOf( feedUrl ).ifPresent( allowedHosts::add );

        final List< FeedEntry > entries = new ArrayList<>();
        for ( final String feedUrl : config.feedUrls() ) {
            if ( config.respectRobots() && !robots.isAllowed( feedUrl ) ) {
                LOG.info( "feed '{}': robots-disallowed feed {}", connectorId, feedUrl );
                continue;
            }
            final FetchResult r = fetcher.fetch( feedUrl );
            if ( r.status() / 100 != 2 ) {
                LOG.warn( "feed '{}': fetch of {} returned status {}", connectorId, feedUrl, r.status() );
                continue;
            }
            entries.addAll( FeedParser.parse( r.body(), feedUrl ) );
        }

        final List< SourceItem > items = new ArrayList<>();
        final Set< String > visited = new HashSet<>();
        for ( final FeedEntry e : entries ) {
            if ( items.size() >= config.maxItems() ) break;
            if ( !visited.add( e.link() ) ) continue;
            if ( config.sameHostOnly() && hostOf( e.link() ).map( h -> !allowedHosts.contains( h ) ).orElse( true ) ) continue;

            if ( config.fetchFullArticles() ) {
                if ( config.respectRobots() && !robots.isAllowed( e.link() ) ) {
                    LOG.info( "feed '{}': robots-disallowed article {}", connectorId, e.link() );
                    continue;
                }
                sleepPolitely( robots, e.link() );
                final FetchResult ar = fetcher.fetch( e.link() );
                if ( ar.status() / 100 != 2 || !isHtml( ar.contentType() ) ) continue;
                final String finalUrl = ar.finalUrl() == null ? e.link() : ar.finalUrl();
                items.add( WebFetchItems.toItem( finalUrl, ar ) );
            } else {
                if ( e.contentHtml().isBlank() ) continue;
                items.add( WebFetchItems.toItemFromContent( e.link(),
                    e.contentHtml().getBytes( StandardCharsets.UTF_8 ), e.title() ) );
            }
        }
        if ( items.size() >= config.maxItems() ) {
            LOG.info( "feed '{}': hit max_items={}, truncated", connectorId, config.maxItems() );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private void sleepPolitely( final RobotsPolicy robots, final String url ) {
        final long robotsDelay = config.respectRobots() ? robots.crawlDelayMs( url ) : 0L;
        final long delay = Math.max( config.delayMs(), robotsDelay );
        if ( delay > 0 ) sleeper.accept( delay );
    }

    private static Optional< String > hostOf( final String url ) {
        try { return Optional.ofNullable( URI.create( url ).getHost() ); }
        catch ( final RuntimeException e ) {
            LOG.warn( "feed: could not derive host for {}: {}", url, e.getMessage() );
            return Optional.empty();
        }
    }

    private static boolean isHtml( final String contentType ) {
        return contentType != null && contentType.toLowerCase( Locale.ROOT ).contains( "text/html" );
    }
}
```

- [ ] **Step 4: Run — PASS** (`mvn test -pl wikantik-connectors -Dtest=FeedSourceConnectorTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedConfig.java wikantik-connectors/src/main/java/com/wikantik/connectors/webcrawler/FeedSourceConnector.java wikantik-connectors/src/test/java/com/wikantik/connectors/webcrawler/FeedSourceConnectorTest.java
git commit -m "feat(feed): FeedSourceConnector — full-article (default) or inline, archive semantics"
```

---

### Task 5: Wire feed connectors in `ConnectorWiringHelper` + config keys

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java` (extend)

**Interfaces:**
- Consumes: `FeedSourceConnector`, `FeedConfig`, `HttpPageFetcher` (webcrawler package).
- Produces: `wireConnectors` also registers feed connectors from `wikantik.connectors.feed.<id>.feed_urls`. Add package-visible `Map<String, FeedConfig> feedConfigs(Properties)` (sibling to `sitemapConfigs`; reuse `parseSeeds`/`parseInt`/`parseLongValue`/`parseBoolean` helpers — check the existing helper names; a boolean parser for `fetch_full_articles`/`respect_robots`/`same_host_only` already exists for the webcrawler configs).

- [ ] **Step 1: Write the failing test** (extend `ConnectorWiringHelperTest`; `import com.wikantik.connectors.webcrawler.FeedConfig;`):
```java
    @Test void parsesFeedConfigsById() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.feed.news.feed_urls", "https://a.com/rss, https://a.com/atom" );
        p.setProperty( "wikantik.connectors.feed.news.max_items", "40" );
        p.setProperty( "wikantik.connectors.feed.news.fetch_full_articles", "false" );
        Map< String, FeedConfig > cfgs = ConnectorWiringHelper.feedConfigs( p );
        assertEquals( 1, cfgs.size() );
        FeedConfig c = cfgs.get( "news" );
        assertEquals( List.of( "https://a.com/rss", "https://a.com/atom" ), c.feedUrls() );
        assertEquals( 40, c.maxItems() );
        assertFalse( c.fetchFullArticles() );
        assertTrue( c.respectRobots() );   // default
    }
    @Test void feedDefaultsFetchFullArticlesTrue() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.feed.news.feed_urls", "https://a.com/rss" );
        assertTrue( ConnectorWiringHelper.feedConfigs( p ).get( "news" ).fetchFullArticles() );  // default true
    }
    @Test void feedRequiresUrls() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.feed.nope.max_items", "10" );
        assertTrue( ConnectorWiringHelper.feedConfigs( p ).isEmpty() );
    }
```

- [ ] **Step 2: Run — FAIL** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`).

- [ ] **Step 3: Implement.** Add `feedConfigs(Properties)` mirroring `sitemapConfigs` (scan `feed.<id>.feed_urls`; skip dotted ids + empty url lists; defaults: `max_items`=100, `fetch_full_articles`=**true**, `delay_ms`=1000, `user_agent`="WikantikCrawler/1.0 (+https://wiki.wikantik.com)", `respect_robots`=true, `same_host_only`=true). Use the file's existing boolean parser for the three flags (if none exists, add a `parseBoolean(props, key, def)` helper next to `parseInt`). In `wireConnectors`, compute `feeds = feedConfigs(props)` ONCE alongside the other maps, extend the early-return guard to include `&& feeds.isEmpty()`, and add a feed loop:
```java
        for ( final Map.Entry< String, FeedConfig > e : feeds.entrySet() ) {
            byId.put( e.getKey(), new FeedSourceConnector( e.getKey(), e.getValue(),
                new HttpPageFetcher( e.getValue().userAgent(), java.time.Duration.ofSeconds( 20 ) ),
                ms -> { try { Thread.sleep( ms ); } catch ( final InterruptedException ie ) { Thread.currentThread().interrupt(); } } ) );
            typeById.put( e.getKey(), "feed" );
        }
```

- [ ] **Step 4: Register config keys** in `wikantik.properties`:
```properties
# RSS/Atom feed connectors (auth-free). Each <id> needs at least .feed_urls (comma-separated feed URLs).
#wikantik.connectors.feed.<id>.feed_urls = https://example.com/rss.xml
#wikantik.connectors.feed.<id>.max_items = 100
#wikantik.connectors.feed.<id>.fetch_full_articles = true
#wikantik.connectors.feed.<id>.delay_ms = 1000
#wikantik.connectors.feed.<id>.user_agent = WikantikCrawler/1.0 (+https://wiki.wikantik.com)
#wikantik.connectors.feed.<id>.respect_robots = true
#wikantik.connectors.feed.<id>.same_host_only = true
```

- [ ] **Step 5: Run — PASS** (`mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest -q`) and compile-check (`mvn -q -pl wikantik-main -am compile`).

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java wikantik-main/src/main/resources/ini/wikantik.properties wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java
git commit -m "feat(feed): wire feed connectors from config (ConnectorWiringHelper)"
```

---

## Post-implementation (controller)

- Full reactor unit build: `mvn clean install -DskipITs` **with `WIKANTIK_*` env UNSET**.
- Confirm Rome passes the RAT/license gate (Apache-2.0) and the per-module RAT on `wikantik-connectors` is clean (all new `.java` carry the ASF header).
- Whole-branch review (opus): the `reflectsFullCorpus()` change is additive + backward-compatible (existing orchestrator tombstone tests unmodified/green; existing connectors inherit `true`); the feed connector is only a new `SourceConnector` (P2.1 runtime untouched); invariant #6; fail-closed `poll()`; archive semantics correct (windowed connector never derives tombstones, but explicit `tombstonedUris` still honored); same-host filter on entry links; Rome parse never throws.

## Self-review notes

- **Spec coverage:** archive gate (T1), inline item builder (T2), Rome parser (T3), the connector incl. full/inline + reflectsFullCorpus (T4), wiring+config (T5). All spec sections mapped. No live IT (per spec).
- **Additive-only SPI change:** T1 adds a default method + one gate; existing connectors/tests unchanged (T1 Step 4 keeps existing orchestrator cases green).
- **Invariant #6 / dep:** everything in `wikantik-connectors` (existing package) + `ConnectorWiringHelper` growth; Rome is a managed dep (add, no version).
- **Type consistency:** `reflectsFullCorpus()`, `WebFetchItems.toItemFromContent(String,byte[],String)`, `FeedParser.parse(byte[],String)`, `FeedEntry(title,link,contentHtml)`, `FeedConfig(feedUrls,maxItems,fetchFullArticles,delayMs,userAgent,respectRobots,sameHostOnly)`, `FeedSourceConnector(id,config,fetcher,sleeper)` — identical across T1→T5.

# GitHub + Confluence Connectors (P2.3c) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two authenticated full-corpus source connectors — GitHub (markdown files from a repo tree) and Confluence Cloud (pages from a space) — as static-token consumers of the existing CredentialStore, mirroring the Drive pattern minus the consent flow.

**Architecture:** Each connector = Config record + injectable `*Api` seam (+`*ApiFactory`) + `*SourceConnector` (poll-never-throws, untrusted-enumeration contract) + package-private `*Items` SourceItem builder. Real API impls hand-roll REST with `java.net.http` + gson behind the seam. Wiring mirrors `driveConfigs`/gdrive loop in `ConnectorWiringHelper`. No wikantik-api or wikantik-rest changes.

**Tech Stack:** Java 25, `java.net.http.HttpClient`, gson (parent-managed), JUnit 5, `com.sun.net.httpserver.HttpServer` for HTTP-impl tests. Spec: `docs/superpowers/specs/2026-07-14-github-confluence-connectors-design.md`.

## Global Constraints

- **poll() NEVER throws** — `SyncOrchestrator.sync` calls it without try/catch. The `apiFactory.create(...)` call goes INSIDE the try block.
- **Untrusted-enumeration contract**: missing/blank token, any API/HTTP failure, or GitHub `truncated=true` → `complete=false` with the **input** cursor (never an empty/partial `complete=true` batch). GitHub per-file 404 → skip WITHOUT taint. Both connectors `reflectsFullCorpus()==true`.
- **Secret hygiene**: the token appears in NO log message, NO exception message, NO response, NO derived-page content. Exception messages may carry URLs/status codes (they never contain the token) but never headers.
- **Empty token → API factory never called.**
- **Response cap**: every HTTP read uses `CappedBodySubscriber` with `10 * 1024 * 1024` bytes.
- **Zero new dependencies**; gson becomes a direct, version-less (parent-managed) dep of wikantik-connectors.
- Credential names EXACTLY `"token"` (GitHub) and `"api_token"` (Confluence).
- Cap-hit (`max_files`/`max_pages`) keeps `complete=true` (deliberate, matches Drive).
- Confluence items are `text/html` with storage XHTML body (the existing ingest path converts) — NOT converted in the connector.
- ASF license header on every new file. Wikantik code style: `final` params, spaces inside parens, LOG = log4j2 `LogManager.getLogger`.
- Module test command: `mvn test -pl wikantik-connectors -Dtest=<ClassName>`. Never use `-T` with tests.

---

### Task 1: CappedBodySubscriber extraction

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/http/CappedBodySubscriber.java`
- Modify: `wikantik-connectors/src/main/java/com/wikantik/connectors/web/HttpPageFetcher.java` (delete the private inner `CappedByteArraySubscriber`, use the new class)
- Test: existing `wikantik-connectors/src/test/java/com/wikantik/connectors/web/HttpPageFetcherTest.java` (NO changes — its 3 cap tests are the extraction guard)

**Interfaces:**
- Consumes: nothing new.
- Produces: `public final class CappedBodySubscriber implements HttpResponse.BodySubscriber<byte[]>` with `public CappedBodySubscriber( int max )` — used by Tasks 4 and 7 as `client.send( req, info -> new CappedBodySubscriber( MAX_BODY_BYTES ) )`.

- [ ] **Step 1: Create the new class** — move the existing inner class verbatim (it lives at the bottom of `HttpPageFetcher.java`), renamed and made public:

```java
/*  <ASF license header — copy verbatim from HttpPageFetcher.java>  */
package com.wikantik.connectors.http;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/** Accumulating byte[] subscriber that cancels the exchange once the body exceeds the cap.
 *  Works for chunked responses too (no Content-Length needed) — the count is enforced as
 *  buffers arrive, so memory is bounded by the cap regardless of what the server declares.
 *  Exceeding the cap completes the body exceptionally → {@code client.send} throws
 *  {@link IOException} → the caller fails closed. Shared by HttpPageFetcher and the
 *  GitHub/Confluence API clients. */
public final class CappedBodySubscriber implements HttpResponse.BodySubscriber< byte[] > {
    private final HttpResponse.BodySubscriber< byte[] > delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int max;
    private final AtomicLong received = new AtomicLong();
    private volatile Flow.Subscription subscription;
    private volatile boolean oversized;

    public CappedBodySubscriber( final int max ) { this.max = max; }

    @Override public CompletionStage< byte[] > getBody() { return delegate.getBody(); }

    @Override public void onSubscribe( final Flow.Subscription s ) {
        this.subscription = s;
        delegate.onSubscribe( s );
    }

    @Override public void onNext( final List< ByteBuffer > item ) {
        if ( oversized ) return;
        long n = 0;
        for ( final ByteBuffer b : item ) n += b.remaining();
        if ( received.addAndGet( n ) > max ) {
            oversized = true;
            subscription.cancel();
            delegate.onError( new IOException( "response body exceeds max " + max + " bytes — dropped (fail-closed)" ) );
        } else {
            delegate.onNext( item );
        }
    }

    @Override public void onError( final Throwable t ) { if ( !oversized ) delegate.onError( t ); }

    @Override public void onComplete() { if ( !oversized ) delegate.onComplete(); }
}
```

- [ ] **Step 2: Rewire HttpPageFetcher** — in `HttpPageFetcher.java`: add `import com.wikantik.connectors.http.CappedBodySubscriber;`, change the send call to `client.send( request, info -> new CappedBodySubscriber( maxBodyBytes ) )`, DELETE the entire private inner class `CappedByteArraySubscriber` and its now-unused imports (`ByteBuffer`, `CompletionStage`, `Flow`, `AtomicLong`, `List` if unused elsewhere — check: `List` is not otherwise used in that file; `IOException` stays).

- [ ] **Step 3: Run the guard tests**

Run: `mvn test -pl wikantik-connectors -Dtest=HttpPageFetcherTest`
Expected: 6/6 PASS (3 malformed-URL + 3 cap tests — behavior unchanged).

- [ ] **Step 4: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/http/CappedBodySubscriber.java wikantik-connectors/src/main/java/com/wikantik/connectors/web/HttpPageFetcher.java
git commit -m "refactor(connectors): extract shared CappedBodySubscriber (for GitHub/Confluence API clients)"
```

---

### Task 2: GitHub records + ItemDigest + GithubItems

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/ItemDigest.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubConfig.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubFile.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/TreeListing.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubItems.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/github/GithubItemsTest.java`

**Interfaces (Produces — used verbatim by Tasks 3, 4, 8):**
- `public record GithubConfig( String repo, String branch, String pathPrefix, int maxFiles )` — `repo` is `"owner/name"`; `branch`/`pathPrefix` nullable.
- `public record GithubFile( String path, String sha, long size )`
- `public record TreeListing( List<GithubFile> files, boolean truncated )`
- `public final class ItemDigest { public static String sha256Hex( byte[] bytes ) }`
- `GithubItems` (package-private): `static SourceItem toItem( String repo, String branch, GithubFile f, byte[] bytes )`

- [ ] **Step 1: Write the failing test**

```java
/*  <ASF license header>  */
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GithubItemsTest {

    @Test void buildsUriMetadataAndHash() {
        byte[] body = "# Hello".getBytes( StandardCharsets.UTF_8 );
        GithubFile f = new GithubFile( "docs/guide/intro.md", "abc123", 7 );
        SourceItem item = GithubItems.toItem( "acme/handbook", "main", f, body );
        assertEquals( "github://acme/handbook/docs/guide/intro.md", item.sourceUri() );
        assertEquals( "text/markdown", item.contentType() );
        assertArrayEquals( body, item.content() );
        assertTrue( item.aclRefs().isEmpty() );
        assertEquals( 64, item.contentHash().length() );
        assertEquals( "docs/guide/intro.md", item.metadata().get( "id" ) );
        assertEquals( "intro.md", item.metadata().get( "name" ) );
        assertEquals( "text/markdown", item.metadata().get( "mimeType" ) );
        assertEquals( "https://github.com/acme/handbook/blob/main/docs/guide/intro.md",
            item.metadata().get( "webViewLink" ) );
        assertFalse( item.metadata().containsKey( "modifiedTime" ) );   // tree listing has none
    }

    @Test void sameContentSameHashDifferentContentDifferentHash() {
        GithubFile f = new GithubFile( "a.md", "s", 1 );
        String h1 = GithubItems.toItem( "o/r", "main", f, new byte[]{ 1 } ).contentHash();
        String h2 = GithubItems.toItem( "o/r", "main", f, new byte[]{ 1 } ).contentHash();
        String h3 = GithubItems.toItem( "o/r", "main", f, new byte[]{ 2 } ).contentHash();
        assertEquals( h1, h2 );
        assertNotEquals( h1, h3 );
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn test -pl wikantik-connectors -Dtest=GithubItemsTest` → COMPILATION ERROR (classes don't exist).

- [ ] **Step 3: Implement.** Each record in its own file with ASF header + one-line javadoc:

`GithubConfig.java`:
```java
package com.wikantik.connectors.github;

/** Per-connector GitHub config. {@code repo} = "owner/name"; {@code branch} null/blank → the repo's
 *  default branch; {@code pathPrefix} null/blank → whole tree. */
public record GithubConfig( String repo, String branch, String pathPrefix, int maxFiles ) {}
```

`GithubFile.java`:
```java
package com.wikantik.connectors.github;

/** One blob entry from the recursive tree listing. */
public record GithubFile( String path, String sha, long size ) {}
```

`TreeListing.java`:
```java
package com.wikantik.connectors.github;

import java.util.List;

/** Recursive tree listing result. {@code truncated} mirrors the GitHub API flag — a truncated
 *  listing is NOT a trustworthy full snapshot (the connector taints the batch). */
public record TreeListing( List< GithubFile > files, boolean truncated ) {}
```

`ItemDigest.java` (package `com.wikantik.connectors` — shared; body copied from `DriveItems.sha256Hex`):
```java
package com.wikantik.connectors;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content-hash helper for SourceItem builders. */
public final class ItemDigest {
    private ItemDigest() {}

    public static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) )
                                       .append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );   // JDK guarantees SHA-256
        }
    }
}
```

`GithubItems.java`:
```java
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.connectors.ItemDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link SourceItem} for a GitHub markdown file: github:// uri, metadata, content hash.
 *  Metadata keys mirror DriveItems' convention; no modifiedTime (the tree listing carries none). */
final class GithubItems {
    private GithubItems() {}

    static SourceItem toItem( final String repo, final String branch, final GithubFile f, final byte[] bytes ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "id", f.path() );
        md.put( "name", f.path().substring( f.path().lastIndexOf( '/' ) + 1 ) );
        md.put( "mimeType", "text/markdown" );
        md.put( "webViewLink", "https://github.com/" + repo + "/blob/" + branch + "/" + f.path() );
        return new SourceItem( "github://" + repo + "/" + f.path(), bytes, "text/markdown",
            md, List.of(), ItemDigest.sha256Hex( bytes ) );
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn test -pl wikantik-connectors -Dtest=GithubItemsTest` → 2/2 PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/ItemDigest.java wikantik-connectors/src/main/java/com/wikantik/connectors/github/ wikantik-connectors/src/test/java/com/wikantik/connectors/github/
git commit -m "feat(github): GithubConfig/GithubFile/TreeListing records + GithubItems builder + shared ItemDigest"
```

---

### Task 3: GithubApi seam + GithubSourceConnector

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubApi.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubApiFactory.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/GithubSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/github/GithubSourceConnectorTest.java`

**Interfaces:**
- Consumes: Task 2's records + `GithubItems.toItem`.
- Produces (used by Tasks 4, 8):
  - `public interface GithubApi { String defaultBranch() throws IOException; TreeListing listTree( String branch ) throws IOException; Optional<byte[]> rawContent( String path, String branch ) throws IOException; }`
  - `public interface GithubApiFactory { GithubApi create( String repo, String token ); }`
  - `public final class GithubSourceConnector implements SourceConnector` with constructor `( String connectorId, GithubConfig config, Supplier<Optional<String>> tokenSupplier, GithubApiFactory apiFactory )`.

- [ ] **Step 1: Write the failing tests**

```java
/*  <ASF license header>  */
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class GithubSourceConnectorTest {

    /** In-memory GitHub: fixed tree + path→bytes contents. */
    static final class FakeApi implements GithubApi {
        List< GithubFile > files = new ArrayList<>();
        Map< String, byte[] > content = new HashMap<>();
        boolean truncated = false;
        boolean failTree = false;
        String defaultBranch = "main";
        String lastBranch;
        public String defaultBranch() { return defaultBranch; }
        public TreeListing listTree( String branch ) throws IOException {
            lastBranch = branch;
            if ( failTree ) throw new IOException( "boom" );
            return new TreeListing( files, truncated );
        }
        public Optional< byte[] > rawContent( String path, String branch ) {
            return Optional.ofNullable( content.get( path ) );   // absent = 404
        }
        FakeApi with( String path, String body ) {
            files.add( new GithubFile( path, "sha-" + path, body.length() ) );
            content.put( path, body.getBytes( StandardCharsets.UTF_8 ) );
            return this;
        }
    }
    static GithubConfig cfg( String branch, String prefix, int max ) {
        return new GithubConfig( "acme/handbook", branch, prefix, max );
    }
    static Supplier< Optional< String > > token( String t ) { return () -> Optional.ofNullable( t ); }
    static GithubSourceConnector conn( GithubConfig c, Supplier< Optional< String > > t, GithubApi api ) {
        return new GithubSourceConnector( "gh", c, t, ( repo, tok ) -> api );
    }

    @Test void emitsMarkdownFilesOnlyRespectingPrefix() {
        FakeApi api = new FakeApi()
            .with( "README.md", "# readme" )
            .with( "docs/a.md", "# a" )
            .with( "docs/img.png", "binary" )
            .with( "src/Main.java", "code" );
        SyncBatch b = conn( cfg( "main", "docs/", 500 ), token( "t" ), api ).poll( null );
        Set< String > uris = new HashSet<>();
        for ( SourceItem i : b.items() ) uris.add( i.sourceUri() );
        assertEquals( Set.of( "github://acme/handbook/docs/a.md" ), uris );   // prefix + .md filter
        assertTrue( b.complete() );
    }

    @Test void noPrefixTakesAllMarkdownCaseInsensitive() {
        FakeApi api = new FakeApi().with( "README.MD", "# r" ).with( "b.md", "# b" );
        SyncBatch b = conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( 2, b.items().size() );
    }

    @Test void usesConfiguredBranchOrDefaultBranch() throws IOException {
        FakeApi api = new FakeApi().with( "a.md", "# a" );
        conn( cfg( "release", null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( "release", api.lastBranch );
        api.defaultBranch = "trunk";
        conn( cfg( null, null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( "trunk", api.lastBranch );
    }

    @Test void emptyTokenReturnsIncompleteEmptyBatchAndNeverCallsFactory() {
        boolean[] built = { false };
        GithubApiFactory f = ( repo, tok ) -> { built[0] = true; return new FakeApi(); };
        GithubSourceConnector c = new GithubSourceConnector( "gh", cfg( "main", null, 500 ), token( null ), f );
        SyncBatch b = c.poll( null );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete(), "couldn't enumerate must never read as empty source" );
        assertFalse( built[0], "factory must not be called without a token" );
    }

    @Test void apiErrorDegradesToEmptyIncompleteBatchNoThrow() {
        FakeApi api = new FakeApi();
        api.failTree = true;
        SyncBatch b = assertDoesNotThrow( () -> conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( null ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
        assertNull( b.nextCursor(), "failure returns the input cursor verbatim (null on first sync)" );
    }

    @Test void factoryThrowingDegradesToEmptyIncompleteBatchNoThrow() {
        GithubApiFactory throwing = ( repo, tok ) -> { throw new IllegalStateException( "bad" ); };
        GithubSourceConnector c = new GithubSourceConnector( "gh", cfg( "main", null, 500 ), token( "t" ), throwing );
        SyncBatch b = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
    }

    @Test void truncatedTreeDeliversItemsButMarksIncomplete() {
        FakeApi api = new FakeApi().with( "a.md", "# a" );
        api.truncated = true;
        SyncCursor in = new SyncCursor( "prev" );
        SyncBatch b = conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( in );
        assertEquals( 1, b.items().size(), "fetched items still delivered" );
        assertFalse( b.complete(), "truncated listing is not a trustworthy full snapshot" );
        assertEquals( in, b.nextCursor() );
    }

    @Test void perFile404IsSkippedWithoutTaint() {
        FakeApi api = new FakeApi().with( "a.md", "# a" );
        api.files.add( new GithubFile( "gone.md", "sha-gone", 3 ) );   // listed but no content → 404
        SyncBatch b = conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( 1, b.items().size() );
        assertTrue( b.complete(), "404 = authoritative absence, batch stays trusted" );
    }

    @Test void honorsMaxFilesAndStaysComplete() {
        FakeApi api = new FakeApi().with( "a.md", "1" ).with( "b.md", "2" ).with( "c.md", "3" );
        SyncBatch b = conn( cfg( "main", null, 2 ), token( "t" ), api ).poll( null );
        assertEquals( 2, b.items().size() );
        assertTrue( b.complete(), "cap-hit is deliberate truncation (matches Drive)" );
    }

    @Test void reflectsFullCorpusIsTrue() {
        assertTrue( conn( cfg( "main", null, 500 ), token( "t" ), new FakeApi() ).reflectsFullCorpus() );
    }
}
```

- [ ] **Step 2: Run to verify fail** — `mvn test -pl wikantik-connectors -Dtest=GithubSourceConnectorTest` → COMPILATION ERROR.

- [ ] **Step 3: Implement.**

`GithubApi.java`:
```java
package com.wikantik.connectors.github;

import java.io.IOException;
import java.util.Optional;

/** Injectable GitHub REST seam — faked in unit tests, HTTP-implemented by HttpGithubApi. */
public interface GithubApi {
    /** The repository's default branch name. */
    String defaultBranch() throws IOException;
    /** Recursive tree listing (blobs only) of the given branch. */
    TreeListing listTree( String branch ) throws IOException;
    /** Raw file content; {@code Optional.empty()} on 404 (deleted between listing and fetch —
     *  authoritative absence, the connector skips without tainting the batch). */
    Optional< byte[] > rawContent( String path, String branch ) throws IOException;
}
```

`GithubApiFactory.java`:
```java
package com.wikantik.connectors.github;

/** Builds a {@link GithubApi} for one repo + token. Called lazily per-poll, INSIDE the try block. */
public interface GithubApiFactory {
    GithubApi create( String repo, String token );
}
```

`GithubSourceConnector.java`:
```java
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Syncs markdown files from a GitHub repository tree. Static-token CredentialStore consumer
 *  (credential name "token"), resolved lazily per-poll. Fail-closed per the untrusted-enumeration
 *  contract: missing token / API failure / truncated listing → complete=false with the input cursor. */
public final class GithubSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( GithubSourceConnector.class );

    private final String connectorId;
    private final GithubConfig config;
    private final Supplier< Optional< String > > tokenSupplier;
    private final GithubApiFactory apiFactory;

    public GithubSourceConnector( final String connectorId, final GithubConfig config,
            final Supplier< Optional< String > > tokenSupplier, final GithubApiFactory apiFactory ) {
        this.connectorId = connectorId;
        this.config = config;
        this.tokenSupplier = tokenSupplier;
        this.apiFactory = apiFactory;
    }

    @Override public String connectorId() { return connectorId; }
    @Override public boolean reflectsFullCorpus() { return true; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final Optional< String > token = tokenSupplier.get();
        if ( token.isEmpty() || token.get().isBlank() ) {
            LOG.warn( "github '{}': no token available (credential store disabled or token not set) — "
                + "skipping sync", connectorId );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        final List< SourceItem > items = new ArrayList<>();
        boolean trusted = true;
        try {
            final GithubApi api = apiFactory.create( config.repo(), token.get() );
            final String branch = config.branch() == null || config.branch().isBlank()
                ? api.defaultBranch() : config.branch();
            final TreeListing tree = api.listTree( branch );
            if ( tree.truncated() ) {
                trusted = false;
                LOG.warn( "github '{}': tree listing truncated by the API — batch marked incomplete, "
                    + "no tombstones this cycle", connectorId );
            }
            for ( final GithubFile f : tree.files() ) {
                if ( items.size() >= config.maxFiles() ) {
                    LOG.info( "github '{}': hit max_files={}, truncated", connectorId, config.maxFiles() );
                    break;
                }
                if ( !wanted( f.path() ) ) continue;
                final Optional< byte[] > raw = api.rawContent( f.path(), branch );
                if ( raw.isEmpty() ) continue;             // 404 = authoritative absence, no taint
                items.add( GithubItems.toItem( config.repo(), branch, f, raw.get() ) );
            }
        } catch ( final Exception e ) {   // poll() never throws; any GitHub/HTTP error → empty INCOMPLETE batch
            LOG.warn( "github '{}': sync failed, skipping cycle: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        if ( !trusted ) {
            return new SyncBatch( items, List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private boolean wanted( final String path ) {
        if ( !path.toLowerCase( Locale.ROOT ).endsWith( ".md" ) ) return false;
        return config.pathPrefix() == null || config.pathPrefix().isBlank()
            || path.startsWith( config.pathPrefix() );
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn test -pl wikantik-connectors -Dtest=GithubSourceConnectorTest` → 10/10 PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/github/ wikantik-connectors/src/test/java/com/wikantik/connectors/github/
git commit -m "feat(github): GithubApi seam + GithubSourceConnector (fail-closed, untrusted-enumeration contract)"
```

---

### Task 4: HttpGithubApi + HttpGithubApiFactory

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/HttpGithubApi.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/github/HttpGithubApiFactory.java`
- Modify: `wikantik-connectors/pom.xml` (add gson — see Step 3)
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/github/HttpGithubApiTest.java`

**Interfaces:**
- Consumes: Task 3's `GithubApi`/`GithubApiFactory`, Task 2's records, Task 1's `CappedBodySubscriber`.
- Produces (used by Task 8): `public final class HttpGithubApiFactory implements GithubApiFactory` with a no-arg constructor; `create(repo, token)` → `new HttpGithubApi( "https://api.github.com", repo, token )`. `HttpGithubApi` has a package-private constructor `( String apiBase, String repo, String token )` for tests.

- [ ] **Step 1: Write the failing tests** (local `com.sun.net.httpserver.HttpServer`, no external network):

```java
/*  <ASF license header>  */
package com.wikantik.connectors.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class HttpGithubApiTest {

    private static HttpServer server;
    private static String base;
    private static final Map< String, String > seenAuth = new ConcurrentHashMap<>();

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/repos/acme/handbook", ex -> {
            seenAuth.put( ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst( "Authorization" ) );
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            if ( path.equals( "/repos/acme/handbook" ) ) {
                respond( ex, 200, "{\"default_branch\":\"trunk\"}" );
            } else if ( path.equals( "/repos/acme/handbook/git/trees/main" ) && "recursive=1".equals( query ) ) {
                respond( ex, 200, "{\"truncated\":false,\"tree\":["
                    + "{\"path\":\"docs/a.md\",\"type\":\"blob\",\"sha\":\"s1\",\"size\":7},"
                    + "{\"path\":\"docs\",\"type\":\"tree\",\"sha\":\"s2\"},"
                    + "{\"path\":\"b.md\",\"type\":\"blob\",\"sha\":\"s3\",\"size\":3}]}" );
            } else if ( path.equals( "/repos/acme/handbook/contents/docs/a.md" ) ) {
                respond( ex, 200, "# hello" );
            } else if ( path.equals( "/repos/acme/handbook/contents/gone.md" ) ) {
                respond( ex, 404, "{\"message\":\"Not Found\"}" );
            } else if ( path.equals( "/repos/acme/handbook/git/trees/boom" ) ) {
                respond( ex, 500, "oops" );
            } else {
                respond( ex, 404, "{}" );
            }
        } );
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterAll static void stop() { server.stop( 0 ); }

    private static void respond( final com.sun.net.httpserver.HttpExchange ex, final int code, final String body ) throws IOException {
        byte[] b = body.getBytes( StandardCharsets.UTF_8 );
        ex.sendResponseHeaders( code, b.length );
        try ( OutputStream os = ex.getResponseBody() ) { os.write( b ); }
    }

    private HttpGithubApi api() { return new HttpGithubApi( base, "acme/handbook", "PAT123" ); }

    @Test void defaultBranchParsesAndSendsBearerAuth() throws IOException {
        assertEquals( "trunk", api().defaultBranch() );
        assertEquals( "Bearer PAT123", seenAuth.get( "/repos/acme/handbook" ) );
    }

    @Test void listTreeParsesBlobsOnly() throws IOException {
        TreeListing t = api().listTree( "main" );
        assertFalse( t.truncated() );
        assertEquals( 2, t.files().size() );                                  // the "tree" entry excluded
        assertEquals( "docs/a.md", t.files().get( 0 ).path() );
        assertEquals( "s1", t.files().get( 0 ).sha() );
        assertEquals( 7, t.files().get( 0 ).size() );
    }

    @Test void rawContentReturnsBytesAnd404MapsToEmpty() throws IOException {
        assertArrayEquals( "# hello".getBytes( StandardCharsets.UTF_8 ),
            api().rawContent( "docs/a.md", "main" ).orElseThrow() );
        assertTrue( api().rawContent( "gone.md", "main" ).isEmpty() );
    }

    @Test void non2xxNon404Throws() {
        assertThrows( IOException.class, () -> api().listTree( "boom" ) );
    }
}
```

- [ ] **Step 2: Run to verify fail** — `mvn test -pl wikantik-connectors -Dtest=HttpGithubApiTest` → COMPILATION ERROR.

- [ ] **Step 3: Add gson to `wikantik-connectors/pom.xml`** — inside `<dependencies>`, version-less (parent-managed; it was previously only transitive via the Google stack, now used directly):

```xml
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
    </dependency>
```

- [ ] **Step 4: Implement.**

`HttpGithubApi.java`:
```java
package com.wikantik.connectors.github;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.connectors.http.CappedBodySubscriber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link GithubApi} over the GitHub REST v3 API with java.net.http + gson. Package-private —
 *  built by {@link HttpGithubApiFactory} (tests construct it directly with a localhost base).
 *  Secret hygiene: the token lives only in the Authorization header — never in URLs, exception
 *  messages, or logs. */
final class HttpGithubApi implements GithubApi {

    static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds( 20 );

    private final String apiBase;
    private final String repo;
    private final String token;
    private final HttpClient client;

    HttpGithubApi( final String apiBase, final String repo, final String token ) {
        this.apiBase = apiBase;
        this.repo = repo;
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout( TIMEOUT ).build();
    }

    @Override
    public String defaultBranch() throws IOException {
        final JsonObject o = JsonParser.parseString(
            new String( get( apiBase + "/repos/" + repo, "application/vnd.github+json" ),
                StandardCharsets.UTF_8 ) ).getAsJsonObject();
        return o.get( "default_branch" ).getAsString();
    }

    @Override
    public TreeListing listTree( final String branch ) throws IOException {
        final JsonObject o = JsonParser.parseString(
            new String( get( apiBase + "/repos/" + repo + "/git/trees/" + branch + "?recursive=1",
                "application/vnd.github+json" ), StandardCharsets.UTF_8 ) ).getAsJsonObject();
        final List< GithubFile > files = new ArrayList<>();
        for ( final JsonElement e : o.getAsJsonArray( "tree" ) ) {
            final JsonObject entry = e.getAsJsonObject();
            if ( !"blob".equals( entry.get( "type" ).getAsString() ) ) continue;
            files.add( new GithubFile( entry.get( "path" ).getAsString(),
                entry.get( "sha" ).getAsString(),
                entry.has( "size" ) ? entry.get( "size" ).getAsLong() : 0L ) );
        }
        return new TreeListing( files, o.has( "truncated" ) && o.get( "truncated" ).getAsBoolean() );
    }

    @Override
    public Optional< byte[] > rawContent( final String path, final String branch ) throws IOException {
        final HttpResponse< byte[] > r = send( apiBase + "/repos/" + repo + "/contents/" + path
            + "?ref=" + branch, "application/vnd.github.raw+json" );
        if ( r.statusCode() == 404 ) return Optional.empty();     // deleted between listing and fetch
        if ( r.statusCode() / 100 != 2 ) {
            throw new IOException( "GitHub API returned status " + r.statusCode() + " for contents of " + path );
        }
        return Optional.of( r.body() );
    }

    private byte[] get( final String url, final String accept ) throws IOException {
        final HttpResponse< byte[] > r = send( url, accept );
        if ( r.statusCode() / 100 != 2 ) {
            throw new IOException( "GitHub API returned status " + r.statusCode() + " for " + url );
        }
        return r.body();
    }

    private HttpResponse< byte[] > send( final String url, final String accept ) throws IOException {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .header( "Authorization", "Bearer " + token )
            .header( "Accept", accept )
            .header( "X-GitHub-Api-Version", "2022-11-28" )
            .header( "User-Agent", "Wikantik-Connector/1.0" )
            .timeout( TIMEOUT )
            .GET().build();
        try {
            return client.send( req, info -> new CappedBodySubscriber( MAX_BODY_BYTES ) );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IOException( "GitHub API request interrupted" );   // fixed string, no token
        }
    }
}
```

`HttpGithubApiFactory.java`:
```java
package com.wikantik.connectors.github;

/** Production {@link GithubApiFactory}: api.github.com. */
public final class HttpGithubApiFactory implements GithubApiFactory {
    @Override public GithubApi create( final String repo, final String token ) {
        return new HttpGithubApi( "https://api.github.com", repo, token );
    }
}
```

- [ ] **Step 5: Run to verify pass** — `mvn test -pl wikantik-connectors -Dtest=HttpGithubApiTest` → 4/4 PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-connectors/pom.xml wikantik-connectors/src/main/java/com/wikantik/connectors/github/ wikantik-connectors/src/test/java/com/wikantik/connectors/github/
git commit -m "feat(github): HttpGithubApi/Factory — REST v3 via java.net.http + gson, capped bodies"
```

---

### Task 5: Confluence records + ConfluenceItems

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluenceConfig.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluencePage.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluenceItems.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/confluence/ConfluenceItemsTest.java`

**Interfaces (Produces — used by Tasks 6, 7, 8):**
- `public record ConfluenceConfig( String baseUrl, String spaceKey, String email, int maxPages )`
- `public record ConfluencePage( String id, String title, int version, String webuiPath, String storageXhtml )`
- `ConfluenceItems` (package-private): `static SourceItem toItem( String baseUrl, String spaceKey, ConfluencePage p )`

- [ ] **Step 1: Write the failing test**

```java
/*  <ASF license header>  */
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ConfluenceItemsTest {

    @Test void buildsUriMetadataAndHtmlBody() {
        ConfluencePage p = new ConfluencePage( "12345", "Team Handbook", 7,
            "/spaces/ENG/pages/12345/Team+Handbook", "<p>hello <strong>world</strong></p>" );
        SourceItem item = ConfluenceItems.toItem( "https://acme.atlassian.net", "ENG", p );
        assertEquals( "confluence://ENG/12345", item.sourceUri() );
        assertEquals( "text/html", item.contentType() );   // existing ingest path converts to markdown
        assertArrayEquals( "<p>hello <strong>world</strong></p>".getBytes( StandardCharsets.UTF_8 ), item.content() );
        assertTrue( item.aclRefs().isEmpty() );
        assertEquals( 64, item.contentHash().length() );
        assertEquals( "12345", item.metadata().get( "id" ) );
        assertEquals( "Team Handbook", item.metadata().get( "name" ) );
        assertEquals( "text/html", item.metadata().get( "mimeType" ) );
        assertEquals( 7, item.metadata().get( "version" ) );
        assertEquals( "https://acme.atlassian.net/wiki/spaces/ENG/pages/12345/Team+Handbook",
            item.metadata().get( "webViewLink" ) );
        assertFalse( item.metadata().containsKey( "modifiedTime" ) );
    }
}
```

- [ ] **Step 2: Run to verify fail** — `mvn test -pl wikantik-connectors -Dtest=ConfluenceItemsTest` → COMPILATION ERROR.

- [ ] **Step 3: Implement.**

`ConfluenceConfig.java`:
```java
package com.wikantik.connectors.confluence;

/** Per-connector Confluence Cloud config. {@code baseUrl} e.g. {@code https://acme.atlassian.net}
 *  (no trailing slash, no /wiki suffix). The email is an identifier, not a secret — the API token
 *  lives in the CredentialStore under "api_token". */
public record ConfluenceConfig( String baseUrl, String spaceKey, String email, int maxPages ) {}
```

`ConfluencePage.java`:
```java
package com.wikantik.connectors.confluence;

/** One page from the v2 space-pages listing, body in storage (XHTML) format. */
public record ConfluencePage( String id, String title, int version, String webuiPath, String storageXhtml ) {}
```

`ConfluenceItems.java`:
```java
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.connectors.ItemDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link SourceItem} for a Confluence page: confluence:// uri, metadata, content hash.
 *  Content is the storage-format XHTML as text/html — the existing ingest path (TikaSourceExtractor)
 *  converts it to markdown; <ac:*> macros degrade to their text content (accepted v1 trade-off). */
final class ConfluenceItems {
    private ConfluenceItems() {}

    static SourceItem toItem( final String baseUrl, final String spaceKey, final ConfluencePage p ) {
        final byte[] bytes = p.storageXhtml().getBytes( StandardCharsets.UTF_8 );
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "id", p.id() );
        md.put( "name", p.title() );
        md.put( "mimeType", "text/html" );
        md.put( "version", p.version() );
        md.put( "webViewLink", baseUrl + "/wiki" + p.webuiPath() );
        return new SourceItem( "confluence://" + spaceKey + "/" + p.id(), bytes, "text/html",
            md, List.of(), ItemDigest.sha256Hex( bytes ) );
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn test -pl wikantik-connectors -Dtest=ConfluenceItemsTest` → 1/1 PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ wikantik-connectors/src/test/java/com/wikantik/connectors/confluence/
git commit -m "feat(confluence): ConfluenceConfig/ConfluencePage records + ConfluenceItems builder"
```

---

### Task 6: ConfluenceApi seam + ConfluenceSourceConnector

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluenceApi.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluenceApiFactory.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ConfluenceSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/confluence/ConfluenceSourceConnectorTest.java`

**Interfaces:**
- Consumes: Task 5's records + `ConfluenceItems.toItem`.
- Produces (used by Tasks 7, 8):
  - `public interface ConfluenceApi { List<ConfluencePage> listPages( int maxPages ) throws IOException; }`
  - `public interface ConfluenceApiFactory { ConfluenceApi create( String baseUrl, String spaceKey, String email, String apiToken ); }` — the space key travels through `create` (it scopes the listing; the factory itself stays stateless).
  - `public final class ConfluenceSourceConnector implements SourceConnector` with constructor `( String connectorId, ConfluenceConfig config, Supplier<Optional<String>> tokenSupplier, ConfluenceApiFactory apiFactory )`.

- [ ] **Step 1: Write the failing tests**

```java
/*  <ASF license header>  */
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ConfluenceSourceConnectorTest {

    static final class FakeApi implements ConfluenceApi {
        List< ConfluencePage > pages = new ArrayList<>();
        boolean fail = false;
        int lastMax = -1;
        public List< ConfluencePage > listPages( int maxPages ) throws IOException {
            lastMax = maxPages;
            if ( fail ) throw new IOException( "boom" );
            return pages.size() > maxPages ? pages.subList( 0, maxPages ) : pages;
        }
    }
    static ConfluenceConfig cfg( int maxPages ) {
        return new ConfluenceConfig( "https://acme.atlassian.net", "ENG", "bot@acme.com", maxPages );
    }
    static Supplier< Optional< String > > token( String t ) { return () -> Optional.ofNullable( t ); }
    static ConfluenceSourceConnector conn( ConfluenceConfig c, Supplier< Optional< String > > t, ConfluenceApi api ) {
        return new ConfluenceSourceConnector( "cf", c, t, ( b, s, e, k ) -> api );
    }

    @Test void emitsOneItemPerPage() {
        FakeApi api = new FakeApi();
        api.pages.add( new ConfluencePage( "1", "A", 1, "/spaces/ENG/pages/1/A", "<p>a</p>" ) );
        api.pages.add( new ConfluencePage( "2", "B", 3, "/spaces/ENG/pages/2/B", "<p>b</p>" ) );
        SyncBatch b = conn( cfg( 500 ), token( "t" ), api ).poll( null );
        assertEquals( 2, b.items().size() );
        assertTrue( b.complete() );
        assertEquals( "confluence://ENG/1", b.items().get( 0 ).sourceUri() );
        assertEquals( "text/html", b.items().get( 0 ).contentType() );
        assertEquals( 500, api.lastMax, "maxPages is delegated to the api listing" );
    }

    @Test void emptyTokenReturnsIncompleteEmptyBatchAndNeverCallsFactory() {
        boolean[] built = { false };
        ConfluenceApiFactory f = ( b, s, e, k ) -> { built[0] = true; return new FakeApi(); };
        ConfluenceSourceConnector c = new ConfluenceSourceConnector( "cf", cfg( 500 ), token( null ), f );
        SyncBatch batch = c.poll( null );
        assertTrue( batch.items().isEmpty() );
        assertFalse( batch.complete() );
        assertFalse( built[0], "factory must not be called without a token" );
    }

    @Test void apiErrorDegradesToEmptyIncompleteBatchNoThrow() {
        FakeApi api = new FakeApi();
        api.fail = true;
        SyncCursor in = new SyncCursor( "prev" );
        SyncBatch b = assertDoesNotThrow( () -> conn( cfg( 500 ), token( "t" ), api ).poll( in ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
        assertEquals( in, b.nextCursor(), "failure returns the input cursor verbatim" );
    }

    @Test void factoryThrowingDegradesToEmptyIncompleteBatchNoThrow() {
        ConfluenceApiFactory throwing = ( b, s, e, k ) -> { throw new IllegalStateException( "bad" ); };
        ConfluenceSourceConnector c = new ConfluenceSourceConnector( "cf", cfg( 500 ), token( "t" ), throwing );
        SyncBatch batch = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( batch.items().isEmpty() );
        assertFalse( batch.complete() );
    }

    @Test void reflectsFullCorpusIsTrue() {
        assertTrue( conn( cfg( 500 ), token( "t" ), new FakeApi() ).reflectsFullCorpus() );
    }
}
```

- [ ] **Step 2: Run to verify fail** — `mvn test -pl wikantik-connectors -Dtest=ConfluenceSourceConnectorTest` → COMPILATION ERROR.

- [ ] **Step 3: Implement.**

`ConfluenceApi.java`:
```java
package com.wikantik.connectors.confluence;

import java.io.IOException;
import java.util.List;

/** Injectable Confluence Cloud REST seam — faked in unit tests, HTTP-implemented by HttpConfluenceApi. */
public interface ConfluenceApi {
    /** All current pages of the configured space (paginated internally), capped at {@code maxPages},
     *  bodies in storage (XHTML) format. Any HTTP/API failure anywhere → IOException — an
     *  enumeration-source failure always taints the batch. */
    List< ConfluencePage > listPages( int maxPages ) throws IOException;
}
```

`ConfluenceApiFactory.java`:
```java
package com.wikantik.connectors.confluence;

/** Builds a {@link ConfluenceApi} for one site + space + account. Called lazily per-poll, INSIDE the
 *  try block. The space key scopes the listing; the factory itself stays stateless. */
public interface ConfluenceApiFactory {
    ConfluenceApi create( String baseUrl, String spaceKey, String email, String apiToken );
}
```

`ConfluenceSourceConnector.java`:
```java
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Syncs the pages of one Confluence Cloud space into derived pages. Static-token CredentialStore
 *  consumer (credential name "api_token" + config email, HTTP Basic), resolved lazily per-poll.
 *  Fail-closed per the untrusted-enumeration contract: missing token / API failure →
 *  complete=false with the input cursor. */
public final class ConfluenceSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( ConfluenceSourceConnector.class );

    private final String connectorId;
    private final ConfluenceConfig config;
    private final Supplier< Optional< String > > tokenSupplier;
    private final ConfluenceApiFactory apiFactory;

    public ConfluenceSourceConnector( final String connectorId, final ConfluenceConfig config,
            final Supplier< Optional< String > > tokenSupplier, final ConfluenceApiFactory apiFactory ) {
        this.connectorId = connectorId;
        this.config = config;
        this.tokenSupplier = tokenSupplier;
        this.apiFactory = apiFactory;
    }

    @Override public String connectorId() { return connectorId; }
    @Override public boolean reflectsFullCorpus() { return true; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final Optional< String > token = tokenSupplier.get();
        if ( token.isEmpty() || token.get().isBlank() ) {
            LOG.warn( "confluence '{}': no api_token available (credential store disabled or token not set) — "
                + "skipping sync", connectorId );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        final List< SourceItem > items = new ArrayList<>();
        try {
            final ConfluenceApi api = apiFactory.create( config.baseUrl(), config.spaceKey(),
                config.email(), token.get() );
            final List< ConfluencePage > pages = api.listPages( config.maxPages() );
            if ( pages.size() >= config.maxPages() ) {
                LOG.info( "confluence '{}': hit max_pages={}, truncated", connectorId, config.maxPages() );
            }
            for ( final ConfluencePage p : pages ) {
                items.add( ConfluenceItems.toItem( config.baseUrl(), config.spaceKey(), p ) );
            }
        } catch ( final Exception e ) {   // poll() never throws; any Confluence/HTTP error → empty INCOMPLETE batch
            LOG.warn( "confluence '{}': sync failed, skipping cycle: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn test -pl wikantik-connectors -Dtest=ConfluenceSourceConnectorTest` → 5/5 PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ wikantik-connectors/src/test/java/com/wikantik/connectors/confluence/
git commit -m "feat(confluence): ConfluenceApi seam + ConfluenceSourceConnector (fail-closed)"
```

---

### Task 7: HttpConfluenceApi + HttpConfluenceApiFactory

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/HttpConfluenceApi.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/HttpConfluenceApiFactory.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/confluence/HttpConfluenceApiTest.java`

**Interfaces:**
- Consumes: Task 6's `ConfluenceApi`/`ConfluenceApiFactory`, Task 5's `ConfluencePage`, Task 1's `CappedBodySubscriber`.
- Produces (used by Task 8): `public final class HttpConfluenceApiFactory implements ConfluenceApiFactory` (no-arg constructor); `create(baseUrl, spaceKey, email, apiToken)` → `new HttpConfluenceApi( baseUrl, spaceKey, email, apiToken )` (package-private 4-arg constructor). The config `baseUrl` doubles as the test seam (tests pass a localhost base).

- [ ] **Step 1: Write the failing tests**

```java
/*  <ASF license header>  */
package com.wikantik.connectors.confluence;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class HttpConfluenceApiTest {

    private static HttpServer server;
    private static String base;
    private static final Map< String, String > seenAuth = new ConcurrentHashMap<>();

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/wiki/api/v2/spaces", ex -> {
            seenAuth.put( ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst( "Authorization" ) );
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            if ( path.equals( "/wiki/api/v2/spaces" ) && query.contains( "keys=ENG" ) ) {
                respond( ex, 200, "{\"results\":[{\"id\":\"777\",\"key\":\"ENG\"}]}" );
            } else if ( path.equals( "/wiki/api/v2/spaces" ) && query.contains( "keys=NOPE" ) ) {
                respond( ex, 200, "{\"results\":[]}" );
            } else if ( path.equals( "/wiki/api/v2/spaces/777/pages" ) && !query.contains( "cursor=p2" ) ) {
                respond( ex, 200, "{\"results\":[{\"id\":\"1\",\"title\":\"A\","
                    + "\"version\":{\"number\":4},"
                    + "\"body\":{\"storage\":{\"value\":\"<p>a</p>\"}},"
                    + "\"_links\":{\"webui\":\"/spaces/ENG/pages/1/A\"}}],"
                    + "\"_links\":{\"next\":\"/wiki/api/v2/spaces/777/pages?cursor=p2\"}}" );
            } else if ( path.equals( "/wiki/api/v2/spaces/777/pages" ) ) {   // cursor=p2 — final page
                respond( ex, 200, "{\"results\":[{\"id\":\"2\",\"title\":\"B\","
                    + "\"version\":{\"number\":1},"
                    + "\"body\":{\"storage\":{\"value\":\"<p>b</p>\"}},"
                    + "\"_links\":{\"webui\":\"/spaces/ENG/pages/2/B\"}}],"
                    + "\"_links\":{}}" );
            } else {
                respond( ex, 500, "oops" );
            }
        } );
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterAll static void stop() { server.stop( 0 ); }

    private static void respond( final com.sun.net.httpserver.HttpExchange ex, final int code, final String body ) throws IOException {
        byte[] b = body.getBytes( StandardCharsets.UTF_8 );
        ex.sendResponseHeaders( code, b.length );
        try ( OutputStream os = ex.getResponseBody() ) { os.write( b ); }
    }

    @Test void listPagesFollowsPaginationAndParses() throws IOException {
        HttpConfluenceApi api = new HttpConfluenceApi( base, "ENG", "bot@acme.com", "TOK" );
        List< ConfluencePage > pages = api.listPages( 500 );
        assertEquals( 2, pages.size() );
        assertEquals( new ConfluencePage( "1", "A", 4, "/spaces/ENG/pages/1/A", "<p>a</p>" ), pages.get( 0 ) );
        assertEquals( new ConfluencePage( "2", "B", 1, "/spaces/ENG/pages/2/B", "<p>b</p>" ), pages.get( 1 ) );
        String expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString( "bot@acme.com:TOK".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( expectedAuth, seenAuth.get( "/wiki/api/v2/spaces" ) );
    }

    @Test void maxPagesStopsPagination() throws IOException {
        HttpConfluenceApi api = new HttpConfluenceApi( base, "ENG", "bot@acme.com", "TOK" );
        assertEquals( 1, api.listPages( 1 ).size() );   // stops before following the next link
    }

    @Test void unknownSpaceThrows() {
        HttpConfluenceApi api = new HttpConfluenceApi( base, "NOPE", "bot@acme.com", "TOK" );
        assertThrows( IOException.class, () -> api.listPages( 500 ) );
    }
}
```

- [ ] **Step 2: Run to verify fail** — `mvn test -pl wikantik-connectors -Dtest=HttpConfluenceApiTest` → COMPILATION ERROR.

- [ ] **Step 3: Implement.**

`HttpConfluenceApi.java`:
```java
package com.wikantik.connectors.confluence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.connectors.http.CappedBodySubscriber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** {@link ConfluenceApi} over the Confluence Cloud v2 REST API (java.net.http + gson, HTTP Basic
 *  email:apiToken). Package-private — built by {@link HttpConfluenceApiFactory} (tests construct it
 *  directly with a localhost base). Secret hygiene: the token lives only in the Authorization
 *  header — never in URLs, exception messages, or logs. */
final class HttpConfluenceApi implements ConfluenceApi {

    static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds( 20 );

    private final String baseUrl;
    private final String spaceKey;
    private final String authHeader;
    private final HttpClient client;

    HttpConfluenceApi( final String baseUrl, final String spaceKey, final String email, final String apiToken ) {
        this.baseUrl = baseUrl;
        this.spaceKey = spaceKey;
        this.authHeader = "Basic " + Base64.getEncoder()
            .encodeToString( ( email + ":" + apiToken ).getBytes( StandardCharsets.UTF_8 ) );
        this.client = HttpClient.newBuilder().connectTimeout( TIMEOUT ).build();
    }

    @Override
    public List< ConfluencePage > listPages( final int maxPages ) throws IOException {
        final String spaceId = spaceId();
        final List< ConfluencePage > out = new ArrayList<>();
        String next = "/wiki/api/v2/spaces/" + spaceId + "/pages?body-format=storage&limit=50";
        while ( next != null && out.size() < maxPages ) {
            final JsonObject o = getJson( baseUrl + next );
            for ( final JsonElement e : o.getAsJsonArray( "results" ) ) {
                if ( out.size() >= maxPages ) break;
                final JsonObject page = e.getAsJsonObject();
                out.add( new ConfluencePage(
                    page.get( "id" ).getAsString(),
                    page.get( "title" ).getAsString(),
                    page.getAsJsonObject( "version" ).get( "number" ).getAsInt(),
                    page.getAsJsonObject( "_links" ).get( "webui" ).getAsString(),
                    page.getAsJsonObject( "body" ).getAsJsonObject( "storage" ).get( "value" ).getAsString() ) );
            }
            final JsonObject links = o.getAsJsonObject( "_links" );
            next = links != null && links.has( "next" ) ? links.get( "next" ).getAsString() : null;
        }
        return out;
    }

    private String spaceId() throws IOException {
        final JsonObject o = getJson( baseUrl + "/wiki/api/v2/spaces?keys=" + spaceKey );
        final var results = o.getAsJsonArray( "results" );
        if ( results == null || results.isEmpty() ) {
            throw new IOException( "Confluence space not found: " + spaceKey );
        }
        return results.get( 0 ).getAsJsonObject().get( "id" ).getAsString();
    }

    private JsonObject getJson( final String url ) throws IOException {
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .header( "Authorization", authHeader )
            .header( "Accept", "application/json" )
            .timeout( TIMEOUT )
            .GET().build();
        final HttpResponse< byte[] > r;
        try {
            r = client.send( req, info -> new CappedBodySubscriber( MAX_BODY_BYTES ) );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IOException( "Confluence API request interrupted" );   // fixed string, no token
        }
        if ( r.statusCode() / 100 != 2 ) {
            throw new IOException( "Confluence API returned status " + r.statusCode() + " for " + url );
        }
        return JsonParser.parseString( new String( r.body(), StandardCharsets.UTF_8 ) ).getAsJsonObject();
    }
}
```

`HttpConfluenceApiFactory.java`:
```java
package com.wikantik.connectors.confluence;

/** Production {@link ConfluenceApiFactory}. */
public final class HttpConfluenceApiFactory implements ConfluenceApiFactory {
    @Override public ConfluenceApi create( final String baseUrl, final String spaceKey,
            final String email, final String apiToken ) {
        return new HttpConfluenceApi( baseUrl, spaceKey, email, apiToken );
    }
}
```

- [ ] **Step 4: Run to verify pass** — `mvn test -pl wikantik-connectors -Dtest='HttpConfluenceApiTest,ConfluenceSourceConnectorTest'` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/confluence/ wikantik-connectors/src/test/java/com/wikantik/connectors/confluence/
git commit -m "feat(confluence): HttpConfluenceApi/Factory — v2 Cloud REST, Basic auth, pagination, capped bodies"
```

---

### Task 8: Wiring + config block + full-suite verification

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (after the gdrive block, ~line 1571)
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java`

**Interfaces:**
- Consumes: `GithubConfig`, `GithubSourceConnector`, `HttpGithubApiFactory`, `ConfluenceConfig`, `ConfluenceSourceConnector`, `HttpConfluenceApiFactory` — exactly as produced by Tasks 2–7. Existing: `credStore.get( id, "<name>" )` returns `Optional<String>`; `parseSeeds`/`blankToNull`/`parseInt` private helpers already in the file.
- Produces: `githubConfigs(Properties)` / `confluenceConfigs(Properties)` package-visible static parsers; typeById `"github"` / `"confluence"`.

- [ ] **Step 1: Write the failing tests** (append to `ConnectorWiringHelperTest`, mirroring the drive tests):

```java
    @Test void githubConfigsParsesRequiredFieldsAndDefaults() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.github.handbook.repo", "acme/handbook" );
        p.setProperty( "wikantik.connectors.github.handbook.branch", "main" );
        p.setProperty( "wikantik.connectors.github.handbook.path_prefix", "docs/" );
        p.setProperty( "wikantik.connectors.github.handbook.max_files", "42" );
        p.setProperty( "wikantik.connectors.github.min.repo", "acme/min" );   // defaults only
        var cfgs = ConnectorWiringHelper.githubConfigs( p );
        assertEquals( 2, cfgs.size() );
        var c = cfgs.get( "handbook" );
        assertEquals( "acme/handbook", c.repo() );
        assertEquals( "main", c.branch() );
        assertEquals( "docs/", c.pathPrefix() );
        assertEquals( 42, c.maxFiles() );
        var m = cfgs.get( "min" );
        assertNull( m.branch() );
        assertNull( m.pathPrefix() );
        assertEquals( 500, m.maxFiles() );
    }

    @Test void githubConfigSkippedWhenRepoMalformed() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.github.bad.repo", "not-owner-slash-name" );
        p.setProperty( "wikantik.connectors.github.bad2.repo", "a/b/c" );
        assertTrue( ConnectorWiringHelper.githubConfigs( p ).isEmpty() );
    }

    @Test void confluenceConfigsParsesRequiredFieldsAndDefaults() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.confluence.acme.space_key", "ENG" );
        p.setProperty( "wikantik.connectors.confluence.acme.base_url", "https://acme.atlassian.net" );
        p.setProperty( "wikantik.connectors.confluence.acme.email", "bot@acme.com" );
        var cfgs = ConnectorWiringHelper.confluenceConfigs( p );
        assertEquals( 1, cfgs.size() );
        var c = cfgs.get( "acme" );
        assertEquals( "https://acme.atlassian.net", c.baseUrl() );
        assertEquals( "ENG", c.spaceKey() );
        assertEquals( "bot@acme.com", c.email() );
        assertEquals( 500, c.maxPages() );
    }

    @Test void confluenceConfigSkippedWhenBaseUrlOrEmailMissing() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.confluence.a.space_key", "ENG" );   // no base_url/email
        p.setProperty( "wikantik.connectors.confluence.b.space_key", "OPS" );
        p.setProperty( "wikantik.connectors.confluence.b.base_url", "https://x.atlassian.net" );  // no email
        assertTrue( ConnectorWiringHelper.confluenceConfigs( p ).isEmpty() );
    }
```

- [ ] **Step 2: Run to verify fail** — `mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest` → COMPILATION ERROR.

- [ ] **Step 3: Implement in `ConnectorWiringHelper.java`.**

Imports to add:
```java
import com.wikantik.connectors.confluence.ConfluenceConfig;
import com.wikantik.connectors.confluence.ConfluenceSourceConnector;
import com.wikantik.connectors.confluence.HttpConfluenceApiFactory;
import com.wikantik.connectors.github.GithubConfig;
import com.wikantik.connectors.github.GithubSourceConnector;
import com.wikantik.connectors.github.HttpGithubApiFactory;
```

Parsers (place after `driveConfigs`, using the existing private helpers):
```java
    /** id → config for every {@code wikantik.connectors.github.<id>.repo} key. Package-visible for
     *  testing. An id whose repo is not "owner/name" shaped is skipped. */
    static Map< String, GithubConfig > githubConfigs( final Properties props ) {
        final String p = PREFIX + "github.";
        final Map< String, GithubConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".repo" ) ) {
                final String id = key.substring( p.length(), key.length() - ".repo".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final String repo = blankToNull( props.getProperty( key ) );
                if ( repo == null || !repo.matches( "[^/\\s]+/[^/\\s]+" ) ) {
                    LOG.warn( "github '{}': repo must be \"owner/name\" — skipping", id );
                    continue;
                }
                final String idPrefix = p + id + ".";
                out.put( id, new GithubConfig( repo,
                    blankToNull( props.getProperty( idPrefix + "branch" ) ),
                    blankToNull( props.getProperty( idPrefix + "path_prefix" ) ),
                    parseInt( props, idPrefix + "max_files", 500 ) ) );
            }
        }
        return out;
    }

    /** id → config for every {@code wikantik.connectors.confluence.<id>.space_key} key. Package-visible
     *  for testing. An id missing base_url or email is skipped. */
    static Map< String, ConfluenceConfig > confluenceConfigs( final Properties props ) {
        final String p = PREFIX + "confluence.";
        final Map< String, ConfluenceConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".space_key" ) ) {
                final String id = key.substring( p.length(), key.length() - ".space_key".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final String spaceKey = blankToNull( props.getProperty( key ) );
                final String idPrefix = p + id + ".";
                final String baseUrl = blankToNull( props.getProperty( idPrefix + "base_url" ) );
                final String email = blankToNull( props.getProperty( idPrefix + "email" ) );
                if ( spaceKey == null ) continue;
                if ( baseUrl == null || email == null ) {
                    LOG.warn( "confluence '{}': missing base_url/email — skipping", id );
                    continue;
                }
                out.put( id, new ConfluenceConfig( baseUrl, spaceKey, email,
                    parseInt( props, idPrefix + "max_pages", 500 ) ) );
            }
        }
        return out;
    }
```

Wiring inside `wireConnectors` (verbatim anchors from the current file):

1. After `final Map< String, DriveConfig > drives = driveConfigs( props );` add:
```java
        final Map< String, GithubConfig > githubs = githubConfigs( props );
        final Map< String, ConfluenceConfig > confluences = confluenceConfigs( props );
```
2. Extend the emptiness guard condition to `if ( roots.isEmpty() && webcrawlers.isEmpty() && sitemaps.isEmpty() && feeds.isEmpty() && drives.isEmpty() && githubs.isEmpty() && confluences.isEmpty() )` and append `"or wikantik.connectors.github.*.repo or wikantik.connectors.confluence.*.space_key "` to the log message.
3. After the gdrive loop + `if ( !drives.isEmpty() ) { ... }` coordinator block, add:
```java
        final HttpGithubApiFactory githubApiFactory = new HttpGithubApiFactory();
        for ( final Map.Entry< String, GithubConfig > e : githubs.entrySet() ) {
            final String id = e.getKey();
            byId.put( id, new GithubSourceConnector( id, e.getValue(),
                () -> credStore.get( id, "token" ), githubApiFactory ) );
            typeById.put( id, "github" );
        }
        final HttpConfluenceApiFactory confluenceApiFactory = new HttpConfluenceApiFactory();
        for ( final Map.Entry< String, ConfluenceConfig > e : confluences.entrySet() ) {
            final String id = e.getKey();
            byId.put( id, new ConfluenceSourceConnector( id, e.getValue(),
                () -> credStore.get( id, "api_token" ), confluenceApiFactory ) );
            typeById.put( id, "confluence" );
        }
```
Constraints: per-iteration `final String id = e.getKey()` capture (NO reuse of `e` inside the lambda); NO `getManager` calls anywhere in the file.

- [ ] **Step 4: Append the config block to `wikantik.properties`** directly after the gdrive block (`#wikantik.connectors.gdrive.<id>.export_mime   = text/markdown`):

```properties
#
#  GitHub connector: markdown files from a repository tree. Inject the token (a fine-grained PAT
#  with read-only Contents scope) once via:  POST /admin/connector-credentials/<id>/token
#
#wikantik.connectors.github.<id>.repo        = <owner>/<name>
#wikantik.connectors.github.<id>.branch      =
#wikantik.connectors.github.<id>.path_prefix =
#wikantik.connectors.github.<id>.max_files   = 500
#
#  Confluence Cloud connector: pages from a space (storage XHTML, converted to markdown at ingest).
#  Inject the Atlassian API token once via:  POST /admin/connector-credentials/<id>/api_token
#
#wikantik.connectors.confluence.<id>.base_url  = https://<site>.atlassian.net
#wikantik.connectors.confluence.<id>.space_key = <KEY>
#wikantik.connectors.confluence.<id>.email     = <account email>
#wikantik.connectors.confluence.<id>.max_pages = 500
```

- [ ] **Step 5: Run to verify pass**

Run: `mvn test -pl wikantik-main -Dtest=ConnectorWiringHelperTest` → 19/19 PASS (15 existing + 4 new).
Then the whole connectors module: `mvn test -pl wikantik-connectors` → all PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "feat(connectors): wire GitHub + Confluence connectors from config (ConnectorWiringHelper)"
```

---

## Final verification (controller, after all tasks)

1. Full reactor: `mvn clean install -DskipITs` with all `WIKANTIK_*` env vars UNSET (run detached/nohup — a plain background call gets wall-killed). Expected: BUILD SUCCESS, all 30 modules.
2. Secret-hygiene grep: `grep -rn "token" wikantik-connectors/src/main/java/com/wikantik/connectors/{github,confluence}/ | grep -i "LOG\."` → the only hits must be the fixed "no token available"/"no api_token available" messages (never a token value).
3. Invariant #6: `grep -rn "com.wikantik.connectors.github\|com.wikantik.connectors.confluence" wikantik-api/src wikantik-rest/src` → no hits.
4. Whole-branch review (opus) + ledger + push.

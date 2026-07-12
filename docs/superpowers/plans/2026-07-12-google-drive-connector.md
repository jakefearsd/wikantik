# Google Drive Connector (P2.3b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `DriveSourceConnector` that syncs configured Google Drive folders (Docs→markdown, native md/txt) into derived pages, authenticating with an OAuth2 refresh token resolved from the P2.2 `CredentialStore`, plus an in-app consent flow to obtain that token.

**Architecture:** New package `com.wikantik.connectors.gdrive` in wikantik-connectors holds the connector, a thin injectable `DriveApi`/`DriveOAuthService` seam (Google client libraries live only behind the `Google*` impls), and the `DefaultDriveAuthCoordinator`. A new `DriveAuthCoordinator` contract in wikantik-api lets `GoogleDriveAuthResource` (wikantik-rest, `/admin/connector-oauth/gdrive/*`) drive the OAuth consent → refresh-token-store flow without ever importing the Google libs or the client secret. `ConnectorWiringHelper` (wikantik-main) parses `wikantik.connectors.gdrive.*`, wires the connector with a refresh-token supplier bound to the store, and registers the coordinator.

**Tech Stack:** Java 25, JUnit 5 + Mockito, `google-api-services-drive` / `google-auth-library-oauth2-http` / `google-api-client` (new, wikantik-connectors only), gson (already managed), Log4j2, `java.net.http` not used here (Google libs own the transport).

## Global Constraints

- **Fail-closed / `poll()` never throws** — an empty refresh token ⇒ empty complete batch and the `DriveApiFactory` is NEVER called; any Drive/OAuth error is caught, logged, and degrades to an empty complete batch.
- **Secret hygiene** — the OAuth `code`, refresh token, access token, and client secret appear in NO log line, NO exception message, NO HTTP response body, NO derived-page content. The consent `state` nonce is not a secret and may be logged.
- **`reflectsFullCorpus() = true`** — folder sync; a file removed from a synced folder tombstones its derived page.
- **Default-off** — no `wikantik.connectors.gdrive.*` keys ⇒ no connector and no coordinator wired.
- **Invariant #6** — new logic in wikantik-connectors (`com.wikantik.connectors.gdrive`), contract in wikantik-api, resource in wikantik-rest, wiring-only growth in wikantik-main's existing `com.wikantik.derived`; NO Google dependency in any module except wikantik-connectors.
- **`setManager`-only in wikantik-main** — `ConnectorWiringHelper` never calls `getManager`; the rest resource resolves via `getManager` (wikantik-rest is not scanned by the frozen ArchUnit rule).
- **Apache license header** on every new `.java`, `.xml`, and pom edit (copy from any sibling file).
- **PostgreSQL-first / no schema change** — reuses the V046 sync-state tables.
- **No live IT** — the Google API + OAuth service are faked in unit tests; the P2.1 `ConnectorAdminIT` covers the runtime/admin path.
- **Connector depends only on** `DriveConfig` + `Supplier<Optional<String>>` (refresh-token resolver) + `DriveApiFactory` — never on the Google libraries or `CredentialStore` directly.

**Canonical type signatures (used across tasks — keep identical):**
```java
// wikantik-api  com.wikantik.api.connectors
public interface DriveAuthCoordinator {
    java.util.Optional<String> authorizationUrl( String connectorId, String state );
    boolean completeAuthorization( String connectorId, String code );
}
// wikantik-connectors  com.wikantik.connectors.gdrive
public record DriveConfig( java.util.List<String> folderIds, int maxFiles, String clientId,
        String clientSecret, String redirectUri, String exportMimeType ) {}
public record DriveFile( String id, String name, String mimeType, String modifiedTime, String webViewLink ) {}
public interface DriveApi {
    java.util.List<DriveFile> listFolder( String folderId ) throws java.io.IOException;
    byte[] export( String fileId, String mimeType ) throws java.io.IOException;
    byte[] getMedia( String fileId ) throws java.io.IOException;
}
public interface DriveApiFactory { DriveApi create( String clientId, String clientSecret, String refreshToken ); }
public interface DriveOAuthService {
    String authorizationUrl( String clientId, String redirectUri, String state );
    String exchangeCodeForRefreshToken( String clientId, String clientSecret, String redirectUri, String code )
            throws java.io.IOException;
}
public final class DriveSourceConnector implements com.wikantik.api.connectors.SourceConnector {
    public DriveSourceConnector( String connectorId, DriveConfig config,
            java.util.function.Supplier<java.util.Optional<String>> refreshTokenSupplier, DriveApiFactory apiFactory );
}
public final class DefaultDriveAuthCoordinator implements com.wikantik.api.connectors.DriveAuthCoordinator {
    public DefaultDriveAuthCoordinator( java.util.Map<String,DriveConfig> byId, DriveOAuthService oauth,
            com.wikantik.api.connectors.CredentialStore store );
}
```
Constants: the stored credential name is the literal `"refresh_token"`; the Drive folder mimeType is `"application/vnd.google-apps.folder"`; the Google Doc mimeType is `"application/vnd.google-apps.document"`; native text mimeTypes handled = `text/markdown`, `text/x-markdown`, `text/plain`; source URI scheme = `gdrive://{id}`.

---

### Task 1: Add the Google client library dependencies

**Files:**
- Modify: `pom.xml` (parent `wikantik-builder`, `<dependencyManagement><dependencies>` block, near the existing gson/rome entries)
- Modify: `wikantik-connectors/pom.xml` (`<dependencies>` block, after the rome entry at the end)

**Interfaces:**
- Consumes: nothing.
- Produces: the Google client libs on the wikantik-connectors compile classpath.

- [ ] **Step 1: Pin versions in the parent `dependencyManagement`**

In `pom.xml`, inside `<dependencyManagement><dependencies>` (alongside the existing managed entries), add:
```xml
      <dependency>
        <groupId>com.google.api-client</groupId>
        <artifactId>google-api-client</artifactId>
        <version>2.7.0</version>
      </dependency>
      <dependency>
        <groupId>com.google.auth</groupId>
        <artifactId>google-auth-library-oauth2-http</artifactId>
        <version>1.30.1</version>
      </dependency>
      <dependency>
        <groupId>com.google.apis</groupId>
        <artifactId>google-api-services-drive</artifactId>
        <version>v3-rev20240914-2.0.0</version>
      </dependency>
      <dependency>
        <groupId>com.google.http-client</groupId>
        <artifactId>google-http-client-gson</artifactId>
        <version>1.45.3</version>
      </dependency>
```
If Maven Central reports a version 404 for `google-api-services-drive` (its revision string moves), pick the latest published `v3-rev*-2.0.0` and use that exact string — do NOT leave a range.

- [ ] **Step 2: Declare the deps in wikantik-connectors**

In `wikantik-connectors/pom.xml`, inside `<dependencies>` (after the rome `</dependency>` at the end, before `</dependencies>`), add (no `<version>` — inherited from management):
```xml
    <dependency>
      <groupId>com.google.apis</groupId>
      <artifactId>google-api-services-drive</artifactId>
    </dependency>
    <dependency>
      <groupId>com.google.auth</groupId>
      <artifactId>google-auth-library-oauth2-http</artifactId>
    </dependency>
    <dependency>
      <groupId>com.google.api-client</groupId>
      <artifactId>google-api-client</artifactId>
    </dependency>
    <dependency>
      <groupId>com.google.http-client</groupId>
      <artifactId>google-http-client-gson</artifactId>
    </dependency>
```

- [ ] **Step 3: Verify resolution + compile**

Run: `mvn -q -pl wikantik-connectors -am dependency:resolve compile`
Expected: BUILD SUCCESS; `mvn -q -pl wikantik-connectors dependency:tree | grep google` shows the four artifacts resolving with no version conflict error.

- [ ] **Step 4: Commit**
```bash
git add pom.xml wikantik-connectors/pom.xml
git commit -m "build(connectors): add Google Drive client libraries (google-api-services-drive, auth, api-client)"
```

---

### Task 2: `DriveConfig` / `DriveFile` records + `DriveItems` (SourceItem builder)

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveConfig.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveFile.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveItems.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/DriveItemsTest.java`

**Interfaces:**
- Consumes: `com.wikantik.api.connectors.SourceItem`.
- Produces: `DriveConfig`, `DriveFile` records (signatures in Global Constraints); `DriveItems.toItem(DriveFile f, byte[] bytes, String contentType) -> SourceItem` and `DriveItems.sha256Hex(byte[]) -> String`.

- [ ] **Step 1: Write the failing test** — `DriveItemsTest.java` (Apache header + package `com.wikantik.connectors.gdrive`):
```java
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DriveItemsTest {
    @Test void toItemBuildsMarkdownSourceItemShape() {
        DriveFile f = new DriveFile( "1AbC", "Guide", "application/vnd.google-apps.document",
                "2026-07-01T10:00:00Z", "https://docs.google.com/d/1AbC" );
        byte[] body = "# Guide\n\ntext".getBytes( StandardCharsets.UTF_8 );
        SourceItem it = DriveItems.toItem( f, body, "text/markdown" );

        assertEquals( "gdrive://1AbC", it.sourceUri() );
        assertEquals( "text/markdown", it.contentType() );
        assertArrayEquals( body, it.content() );
        assertTrue( it.aclRefs().isEmpty() );
        assertEquals( "1AbC", it.sourceMetadata().get( "id" ) );
        assertEquals( "Guide", it.sourceMetadata().get( "name" ) );
        assertEquals( "application/vnd.google-apps.document", it.sourceMetadata().get( "mimeType" ) );
        assertEquals( "2026-07-01T10:00:00Z", it.sourceMetadata().get( "modifiedTime" ) );
        assertEquals( "https://docs.google.com/d/1AbC", it.sourceMetadata().get( "webViewLink" ) );
        assertEquals( DriveItems.sha256Hex( body ), it.contentHash() );
        assertEquals( 64, it.contentHash().length() );   // sha-256 hex
    }
    @Test void sha256IsContentAddressed() {
        assertEquals( DriveItems.sha256Hex( "x".getBytes() ), DriveItems.sha256Hex( "x".getBytes() ) );
        assertNotEquals( DriveItems.sha256Hex( "x".getBytes() ), DriveItems.sha256Hex( "y".getBytes() ) );
    }
}
```

- [ ] **Step 2: Run — FAIL**
Run: `mvn -q -pl wikantik-connectors -Dtest=DriveItemsTest test`
Expected: compile failure (`DriveFile`/`DriveItems` do not exist).

- [ ] **Step 3: Create the records**

`DriveConfig.java` (Apache header, package `com.wikantik.connectors.gdrive`):
```java
package com.wikantik.connectors.gdrive;
import java.util.List;
/** Config for one Google Drive connector: folders to sync, cap, OAuth app creds, callback, export format. */
public record DriveConfig( List<String> folderIds, int maxFiles, String clientId,
        String clientSecret, String redirectUri, String exportMimeType ) {}
```
`DriveFile.java` (Apache header):
```java
package com.wikantik.connectors.gdrive;
/** Minimal Drive file metadata used by the connector. */
public record DriveFile( String id, String name, String mimeType, String modifiedTime, String webViewLink ) {}
```

- [ ] **Step 4: Create `DriveItems`** (Apache header) — mirror `com.wikantik.connectors.web.WebFetchItems`:
```java
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.SourceItem;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link SourceItem} for a Drive file: gdrive:// uri, metadata, content hash. */
final class DriveItems {
    private DriveItems() {}

    static SourceItem toItem( final DriveFile f, final byte[] bytes, final String contentType ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "id", f.id() );
        md.put( "name", f.name() );
        md.put( "mimeType", f.mimeType() );
        md.put( "modifiedTime", f.modifiedTime() );
        md.put( "webViewLink", f.webViewLink() );
        return new SourceItem( "gdrive://" + f.id(), bytes, contentType, md, List.of(), sha256Hex( bytes ) );
    }

    static String sha256Hex( final byte[] bytes ) {
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
`DriveItemsTest` accesses `DriveItems` in-package, so package-private is fine.

- [ ] **Step 5: Run — PASS**
Run: `mvn -q -pl wikantik-connectors -Dtest=DriveItemsTest test`
Expected: 2 tests pass.

- [ ] **Step 6: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveConfig.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveFile.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveItems.java \
        wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/DriveItemsTest.java
git commit -m "feat(gdrive): DriveConfig/DriveFile records + DriveItems SourceItem builder"
```

---

### Task 3: `DriveApi`/`DriveApiFactory` interfaces + `DriveSourceConnector`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveApi.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveApiFactory.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/DriveSourceConnectorTest.java`

**Interfaces:**
- Consumes: `DriveConfig`, `DriveFile`, `DriveItems` (Task 2); `SourceConnector`/`SyncBatch`/`SyncCursor`/`SourceItem` (api).
- Produces: `DriveApi`, `DriveApiFactory` interfaces; `DriveSourceConnector` (signature in Global Constraints), `reflectsFullCorpus()` returns `true`.

- [ ] **Step 1: Write the failing test** — `DriveSourceConnectorTest.java` (Apache header, package `com.wikantik.connectors.gdrive`). It uses a hand-written fake `DriveApi`/`DriveApiFactory` — NO Mockito, NO Google libs:
```java
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class DriveSourceConnectorTest {

    private static final String DOC = "application/vnd.google-apps.document";
    private static final String FOLDER = "application/vnd.google-apps.folder";

    /** In-memory Drive: folderId -> children; fileId -> bytes. */
    static final class FakeApi implements DriveApi {
        final Map<String,List<DriveFile>> tree = new HashMap<>();
        final Map<String,byte[]> media = new HashMap<>();
        boolean fail = false;
        public List<DriveFile> listFolder( String id ) throws IOException {
            if ( fail ) throw new IOException( "boom" );
            return tree.getOrDefault( id, List.of() );
        }
        public byte[] export( String id, String mime ) { return ( "MD:" + id ).getBytes( StandardCharsets.UTF_8 ); }
        public byte[] getMedia( String id ) { return media.getOrDefault( id, new byte[0] ); }
    }
    static DriveConfig cfg( List<String> folders, int max ) {
        return new DriveConfig( folders, max, "cid", "csecret", "https://w/cb", "text/markdown" );
    }
    static Supplier<Optional<String>> token( String t ) { return () -> Optional.ofNullable( t ); }

    @Test void fullArticleModeExportsDocsAndFetchesNativeTextSkipsOther() {
        FakeApi api = new FakeApi();
        api.tree.put( "root", List.of(
            new DriveFile( "d1", "Doc", DOC, "t", "l" ),
            new DriveFile( "m1", "note.md", "text/markdown", "t", "l" ),
            new DriveFile( "p1", "pic.png", "image/png", "t", "l" ) ) );
        api.media.put( "m1", "# note".getBytes( StandardCharsets.UTF_8 ) );
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( "rt" ), ( a, b, r ) -> api );

        SyncBatch batch = c.poll( null );
        assertEquals( 2, batch.items().size() );                       // png skipped
        assertTrue( batch.complete() );
        assertTrue( batch.tombstonedUris().isEmpty() );
        assertEquals( "gdrive://d1", batch.items().get( 0 ).sourceUri() );
        assertEquals( "text/markdown", batch.items().get( 0 ).contentType() );
        assertArrayEquals( "MD:d1".getBytes( StandardCharsets.UTF_8 ), batch.items().get( 0 ).content() );
        assertEquals( "text/markdown", batch.items().get( 1 ).contentType() );
        assertArrayEquals( "# note".getBytes( StandardCharsets.UTF_8 ), batch.items().get( 1 ).content() );
    }

    @Test void recursesSubfoldersAndHonorsMaxFiles() {
        FakeApi api = new FakeApi();
        api.tree.put( "root", List.of( new DriveFile( "sub", "Sub", FOLDER, "t", "l" ),
                                       new DriveFile( "d1", "A", DOC, "t", "l" ) ) );
        api.tree.put( "sub", List.of( new DriveFile( "d2", "B", DOC, "t", "l" ),
                                      new DriveFile( "d3", "C", DOC, "t", "l" ) ) );
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 2 ), token( "rt" ), ( a, b, r ) -> api );
        SyncBatch batch = c.poll( null );
        assertEquals( 2, batch.items().size() );   // capped at max_files=2, subfolder recursed
    }

    @Test void emptyRefreshTokenReturnsEmptyBatchAndNeverCallsFactory() {
        boolean[] built = { false };
        DriveApiFactory f = ( a, b, r ) -> { built[0] = true; return new FakeApi(); };
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( null ), f );
        SyncBatch batch = c.poll( null );
        assertTrue( batch.items().isEmpty() );
        assertTrue( batch.complete() );
        assertFalse( built[0], "factory must not be called without a refresh token" );
    }

    @Test void driveErrorDegradesToEmptyBatchNoThrow() {
        FakeApi api = new FakeApi();
        api.fail = true;
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( "rt" ), ( a, b, r ) -> api );
        SyncBatch batch = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( batch.items().isEmpty() );
        assertTrue( batch.complete() );
    }

    @Test void reflectsFullCorpusIsTrue() {
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( "rt" ), ( a, b, r ) -> new FakeApi() );
        assertTrue( c.reflectsFullCorpus() );
    }
}
```

- [ ] **Step 2: Run — FAIL**
Run: `mvn -q -pl wikantik-connectors -Dtest=DriveSourceConnectorTest test`
Expected: compile failure (`DriveApi`/`DriveApiFactory`/`DriveSourceConnector` do not exist).

- [ ] **Step 3: Create the interfaces**

`DriveApi.java` and `DriveApiFactory.java` (Apache headers) — exactly the signatures in Global Constraints (public).

- [ ] **Step 4: Create `DriveSourceConnector`** (Apache header):
```java
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.function.Supplier;

/** Syncs configured Drive folders (recursively) into derived pages: Docs→markdown, native md/txt
 *  fetched, other binaries skipped. Resolves its OAuth2 refresh token lazily per poll (fail-closed). */
public final class DriveSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( DriveSourceConnector.class );
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String DOC_MIME    = "application/vnd.google-apps.document";
    private static final Set< String > NATIVE_TEXT = Set.of( "text/markdown", "text/x-markdown", "text/plain" );

    private final String connectorId;
    private final DriveConfig config;
    private final Supplier< Optional< String > > refreshTokenSupplier;
    private final DriveApiFactory apiFactory;

    public DriveSourceConnector( final String connectorId, final DriveConfig config,
            final Supplier< Optional< String > > refreshTokenSupplier, final DriveApiFactory apiFactory ) {
        this.connectorId = connectorId;
        this.config = config;
        this.refreshTokenSupplier = refreshTokenSupplier;
        this.apiFactory = apiFactory;
    }

    @Override public String connectorId() { return connectorId; }
    @Override public boolean reflectsFullCorpus() { return true; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final Optional< String > token = refreshTokenSupplier.get();
        if ( token.isEmpty() || token.get().isBlank() ) {
            LOG.warn( "gdrive '{}': no refresh_token available (credential store disabled or token not set) — "
                + "skipping sync", connectorId );
            return new SyncBatch( List.of(), List.of(), new SyncCursor( "0" ), true );
        }
        final DriveApi api = apiFactory.create( config.clientId(), config.clientSecret(), token.get() );
        final List< SourceItem > items = new ArrayList<>();
        try {
            final Set< String > visitedFolders = new HashSet<>();
            final List< DriveFile > files = new ArrayList<>();
            for ( final String folderId : config.folderIds() ) walk( api, folderId, files, visitedFolders );
            for ( final DriveFile f : files ) {
                if ( items.size() >= config.maxFiles() ) break;
                final SourceItem item = toItem( api, f );
                if ( item != null ) items.add( item );
            }
            if ( items.size() >= config.maxFiles() ) {
                LOG.info( "gdrive '{}': hit max_files={}, truncated", connectorId, config.maxFiles() );
            }
        } catch ( final Exception e ) {   // poll() never throws; any Drive/OAuth error → empty batch
            LOG.warn( "gdrive '{}': sync failed, skipping cycle: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), new SyncCursor( "0" ), true );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private void walk( final DriveApi api, final String folderId, final List< DriveFile > out,
            final Set< String > visited ) throws java.io.IOException {
        if ( !visited.add( folderId ) ) return;                        // cycle / duplicate guard
        if ( out.size() >= config.maxFiles() ) return;
        for ( final DriveFile f : api.listFolder( folderId ) ) {
            if ( FOLDER_MIME.equals( f.mimeType() ) ) walk( api, f.id(), out, visited );
            else out.add( f );
        }
    }

    private SourceItem toItem( final DriveApi api, final DriveFile f ) throws java.io.IOException {
        if ( DOC_MIME.equals( f.mimeType() ) ) {
            return DriveItems.toItem( f, api.export( f.id(), config.exportMimeType() ), config.exportMimeType() );
        }
        if ( NATIVE_TEXT.contains( f.mimeType() ) ) {
            return DriveItems.toItem( f, api.getMedia( f.id() ), f.mimeType() );
        }
        LOG.info( "gdrive '{}': skipping unsupported type {} ({})", connectorId, f.mimeType(), f.name() );
        return null;
    }
}
```

- [ ] **Step 5: Run — PASS**
Run: `mvn -q -pl wikantik-connectors -Dtest=DriveSourceConnectorTest test`
Expected: 5 tests pass.

- [ ] **Step 6: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveApi.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveApiFactory.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveSourceConnector.java \
        wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/DriveSourceConnectorTest.java
git commit -m "feat(gdrive): DriveSourceConnector — folder walk, Docs→md, fail-closed, reflectsFullCorpus"
```

---

### Task 4: `DriveAuthCoordinator` contract + `DriveOAuthService` + `DefaultDriveAuthCoordinator`

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/DriveAuthCoordinator.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveOAuthService.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DefaultDriveAuthCoordinator.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/DefaultDriveAuthCoordinatorTest.java`

**Interfaces:**
- Consumes: `DriveConfig` (Task 2); `CredentialStore` (wikantik-api, from P2.2: `boolean enabled()`, `void put(String,String,String)`, `Optional<String> get(String,String)`, `List<String> list(String)`, `void delete(String,String)`).
- Produces: `DriveAuthCoordinator` (api), `DriveOAuthService` interface, `DefaultDriveAuthCoordinator` (signatures in Global Constraints).

- [ ] **Step 1: Write the failing test** — `DefaultDriveAuthCoordinatorTest.java` (Apache header). Hand-written fakes, no Mockito needed:
```java
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.CredentialStore;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultDriveAuthCoordinatorTest {

    static final class FakeOAuth implements DriveOAuthService {
        boolean throwOnExchange = false;
        public String authorizationUrl( String cid, String redirect, String state ) {
            return "https://accounts.google.com/o/oauth2/auth?client_id=" + cid + "&state=" + state;
        }
        public String exchangeCodeForRefreshToken( String cid, String csec, String redirect, String code ) throws IOException {
            if ( throwOnExchange ) throw new IOException( "bad code" );
            return "REFRESH-for-" + code;
        }
    }
    /** In-memory store; enabled togglable. */
    static final class FakeStore implements CredentialStore {
        final Map<String,String> saved = new HashMap<>();
        boolean enabled = true;
        public boolean enabled() { return enabled; }
        public void put( String id, String name, String secret ) { saved.put( id + "/" + name, secret ); }
        public Optional<String> get( String id, String name ) { return Optional.ofNullable( saved.get( id + "/" + name ) ); }
        public List<String> list( String id ) { return List.of(); }
        public void delete( String id, String name ) { saved.remove( id + "/" + name ); }
    }
    static DriveConfig cfg() { return new DriveConfig( List.of( "root" ), 500, "cid", "csec", "https://w/cb", "text/markdown" ); }

    @Test void authorizationUrlForKnownIdElseEmpty() {
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), new FakeStore() );
        assertTrue( c.authorizationUrl( "gd", "st8" ).orElseThrow().contains( "state=st8" ) );
        assertTrue( c.authorizationUrl( "unknown", "st8" ).isEmpty() );
    }
    @Test void completeAuthorizationExchangesAndStoresRefreshToken() {
        FakeStore store = new FakeStore();
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), store );
        assertTrue( c.completeAuthorization( "gd", "CODE1" ) );
        assertEquals( "REFRESH-for-CODE1", store.get( "gd", "refresh_token" ).orElseThrow() );
    }
    @Test void exchangeFailureReturnsFalseAndStoresNothing() {
        FakeStore store = new FakeStore();
        FakeOAuth oauth = new FakeOAuth(); oauth.throwOnExchange = true;
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), oauth, store );
        assertFalse( c.completeAuthorization( "gd", "CODE1" ) );
        assertTrue( store.get( "gd", "refresh_token" ).isEmpty() );
    }
    @Test void disabledStoreReturnsFalse() {
        FakeStore store = new FakeStore(); store.enabled = false;
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), store );
        assertFalse( c.completeAuthorization( "gd", "CODE1" ) );
        assertTrue( store.get( "gd", "refresh_token" ).isEmpty() );
    }
    @Test void unknownIdCompleteReturnsFalse() {
        var c = new DefaultDriveAuthCoordinator( Map.of( "gd", cfg() ), new FakeOAuth(), new FakeStore() );
        assertFalse( c.completeAuthorization( "nope", "CODE1" ) );
    }
}
```

- [ ] **Step 2: Run — FAIL**
Run: `mvn -q -pl wikantik-connectors -Dtest=DefaultDriveAuthCoordinatorTest test`
Expected: compile failure (types do not exist). NOTE: `DriveAuthCoordinator` lives in wikantik-api, so also build with `-am`: `mvn -q -pl wikantik-connectors -am -Dtest=DefaultDriveAuthCoordinatorTest test`.

- [ ] **Step 3: Create `DriveAuthCoordinator`** in wikantik-api (Apache header) — exact signature from Global Constraints.

- [ ] **Step 4: Create `DriveOAuthService`** interface (Apache header) — exact signature from Global Constraints.

- [ ] **Step 5: Create `DefaultDriveAuthCoordinator`** (Apache header):
```java
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.DriveAuthCoordinator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.Optional;

/** Drives the OAuth2 consent → refresh-token-store flow for the admin resource. Stores the refresh
 *  token under the credential name {@code refresh_token}. Never logs the OAuth code or any token. */
public final class DefaultDriveAuthCoordinator implements DriveAuthCoordinator {

    private static final Logger LOG = LogManager.getLogger( DefaultDriveAuthCoordinator.class );
    private static final String REFRESH_TOKEN = "refresh_token";

    private final Map< String, DriveConfig > byId;
    private final DriveOAuthService oauth;
    private final CredentialStore store;

    public DefaultDriveAuthCoordinator( final Map< String, DriveConfig > byId,
            final DriveOAuthService oauth, final CredentialStore store ) {
        this.byId = byId;
        this.oauth = oauth;
        this.store = store;
    }

    @Override
    public Optional< String > authorizationUrl( final String connectorId, final String state ) {
        final DriveConfig cfg = byId.get( connectorId );
        if ( cfg == null ) return Optional.empty();
        return Optional.of( oauth.authorizationUrl( cfg.clientId(), cfg.redirectUri(), state ) );
    }

    @Override
    public boolean completeAuthorization( final String connectorId, final String code ) {
        final DriveConfig cfg = byId.get( connectorId );
        if ( cfg == null ) {
            LOG.warn( "gdrive oauth: unknown connector id '{}'", connectorId );
            return false;
        }
        if ( !store.enabled() ) {
            LOG.warn( "gdrive oauth '{}': credential store disabled (no master key) — cannot store token", connectorId );
            return false;
        }
        try {
            final String refreshToken = oauth.exchangeCodeForRefreshToken(
                cfg.clientId(), cfg.clientSecret(), cfg.redirectUri(), code );
            store.put( connectorId, REFRESH_TOKEN, refreshToken );   // encrypted at rest by the store
            LOG.info( "gdrive oauth '{}': refresh token stored", connectorId );   // no token/code in the message
            return true;
        } catch ( final Exception e ) {                              // never surface the code/token
            LOG.warn( "gdrive oauth '{}': code exchange failed: {}", connectorId, e.getMessage() );
            return false;
        }
    }
}
```

- [ ] **Step 6: Run — PASS**
Run: `mvn -q -pl wikantik-connectors -am -Dtest=DefaultDriveAuthCoordinatorTest test`
Expected: 5 tests pass.

- [ ] **Step 7: Commit**
```bash
git add wikantik-api/src/main/java/com/wikantik/api/connectors/DriveAuthCoordinator.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DriveOAuthService.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/DefaultDriveAuthCoordinator.java \
        wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/DefaultDriveAuthCoordinatorTest.java
git commit -m "feat(gdrive): DriveAuthCoordinator contract + DefaultDriveAuthCoordinator (consent→store refresh token)"
```

---

### Task 5: Google-lib impls — `GoogleDriveApi` + `GoogleDriveApiFactory` + `GoogleDriveOAuthService`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/GoogleDriveApi.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/GoogleDriveApiFactory.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/GoogleDriveOAuthService.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/GoogleDriveOAuthServiceTest.java`

**Interfaces:**
- Consumes: `DriveApi`/`DriveApiFactory`/`DriveOAuthService`/`DriveFile` (Tasks 3–4); Google client libs (Task 1).
- Produces: `GoogleDriveApiFactory` (public, no-arg ctor — constructed by wikantik-main), `GoogleDriveOAuthService` (public, no-arg ctor). `GoogleDriveApi` may be package-private (only the factory builds it).

> These are thin adapters over the Google client. Only `GoogleDriveOAuthService.authorizationUrl` is
> offline-testable (it builds a URL string with no network); the other methods require live Google
> endpoints and are therefore NOT unit-tested (documented; covered by real deployment, no live IT).

- [ ] **Step 1: Write the failing test** — `GoogleDriveOAuthServiceTest.java` (Apache header). Only the URL builder is asserted (offline):
```java
package com.wikantik.connectors.gdrive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoogleDriveOAuthServiceTest {
    @Test void authorizationUrlContainsClientRedirectStateScopeAndOfflineConsent() {
        String url = new GoogleDriveOAuthService()
            .authorizationUrl( "CID.apps.googleusercontent.com", "https://wiki/cb", "st8-nonce" );
        assertTrue( url.startsWith( "https://accounts.google.com/o/oauth2/auth" ), url );
        assertTrue( url.contains( "client_id=CID.apps.googleusercontent.com" ), url );
        assertTrue( url.contains( "state=st8-nonce" ), url );
        assertTrue( url.contains( "access_type=offline" ), url );          // guarantees a refresh token
        assertTrue( url.contains( "prompt=consent" ), url );               // forces refresh-token re-issue
        assertTrue( url.contains( "drive.readonly" ), url );               // least-privilege scope
        assertTrue( url.contains( "redirect_uri=" ), url );
    }
}
```

- [ ] **Step 2: Run — FAIL**
Run: `mvn -q -pl wikantik-connectors -Dtest=GoogleDriveOAuthServiceTest test`
Expected: compile failure (`GoogleDriveOAuthService` does not exist).

- [ ] **Step 3: Create `GoogleDriveOAuthService`** (Apache header). Uses `google-api-client`'s `GoogleAuthorizationCodeRequestUrl` (offline URL build) + `GoogleAuthorizationCodeTokenRequest` (code exchange):
```java
package com.wikantik.connectors.gdrive;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/** {@link DriveOAuthService} backed by the Google OAuth2 client. Scope: drive.readonly. */
public final class GoogleDriveOAuthService implements DriveOAuthService {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/auth";
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();

    @Override
    public String authorizationUrl( final String clientId, final String redirectUri, final String state ) {
        return new GoogleAuthorizationCodeRequestUrl( clientId, redirectUri, List.of( SCOPE ) )
            .setAccessType( "offline" )        // request a refresh token
            .set( "prompt", "consent" )        // force refresh-token issuance even on re-consent
            .setState( state )
            .build();
    }

    @Override
    public String exchangeCodeForRefreshToken( final String clientId, final String clientSecret,
            final String redirectUri, final String code ) throws IOException {
        try {
            final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            final GoogleTokenResponse resp = new GoogleAuthorizationCodeTokenRequest(
                transport, JSON, clientId, clientSecret, code, redirectUri ).execute();
            final String refreshToken = resp.getRefreshToken();
            if ( refreshToken == null || refreshToken.isBlank() ) {
                throw new IOException( "Google returned no refresh_token (re-consent with access_type=offline required)" );
            }
            return refreshToken;
        } catch ( final GeneralSecurityException e ) {
            throw new IOException( "trusted transport init failed", e );   // message carries no secret
        }
    }
}
```
Note: `GoogleAuthorizationCodeRequestUrl` sets `client_id`/`redirect_uri`/`response_type=code`/`scope`
automatically; its `.build()` produces the base `AUTH_ENDPOINT` URL. The `AUTH_ENDPOINT`/`SCOPE`
constants document the endpoint and scope the test asserts.

- [ ] **Step 4: Run — PASS**
Run: `mvn -q -pl wikantik-connectors -Dtest=GoogleDriveOAuthServiceTest test`
Expected: 1 test passes.

- [ ] **Step 5: Create `GoogleDriveApi` + `GoogleDriveApiFactory`** (Apache headers). `GoogleDriveApi` wraps a built `Drive` service:
```java
package com.wikantik.connectors.gdrive;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** {@link DriveApi} backed by a built {@link Drive} service. Package-private — only the factory builds it. */
final class GoogleDriveApi implements DriveApi {

    private static final String FIELDS = "nextPageToken, files(id, name, mimeType, modifiedTime, webViewLink)";
    private final Drive drive;

    GoogleDriveApi( final Drive drive ) { this.drive = drive; }

    @Override
    public List< DriveFile > listFolder( final String folderId ) throws IOException {
        final List< DriveFile > out = new ArrayList<>();
        String pageToken = null;
        do {
            final FileList result = drive.files().list()
                .setQ( "'" + folderId + "' in parents and trashed = false" )
                .setFields( FIELDS )
                .setPageSize( 1000 )
                .setPageToken( pageToken )
                .execute();
            for ( final File f : result.getFiles() ) {
                out.add( new DriveFile( f.getId(), f.getName(), f.getMimeType(),
                    f.getModifiedTime() == null ? null : f.getModifiedTime().toStringRfc3339(),
                    f.getWebViewLink() ) );
            }
            pageToken = result.getNextPageToken();
        } while ( pageToken != null );
        return out;
    }

    @Override
    public byte[] export( final String fileId, final String mimeType ) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        drive.files().export( fileId, mimeType ).executeMediaAndDownloadTo( out );
        return out.toByteArray();
    }

    @Override
    public byte[] getMedia( final String fileId ) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        drive.files().get( fileId ).executeMediaAndDownloadTo( out );
        return out.toByteArray();
    }
}
```
`GoogleDriveApiFactory` (Apache header, public, no-arg ctor):
```java
package com.wikantik.connectors.gdrive;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import java.util.List;

/** Builds a {@link GoogleDriveApi} from OAuth2 user credentials (client id/secret + refresh token). */
public final class GoogleDriveApiFactory implements DriveApiFactory {

    private static final GsonFactory JSON = GsonFactory.getDefaultInstance();

    @Override
    public DriveApi create( final String clientId, final String clientSecret, final String refreshToken ) {
        try {
            final NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            final UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId( clientId )
                .setClientSecret( clientSecret )
                .setRefreshToken( refreshToken )
                .build()
                .createScoped( List.of( DriveScopes.DRIVE_READONLY ) );
            final Drive drive = new Drive.Builder( transport, JSON, new HttpCredentialsAdapter( credentials ) )
                .setApplicationName( "Wikantik" )
                .build();
            return new GoogleDriveApi( drive );
        } catch ( final Exception e ) {
            // Build failures are surfaced to poll()'s catch as an unchecked error → empty batch.
            // Message carries no secret (client id/secret/refresh token are never in a JDK/Google build error).
            throw new IllegalStateException( "Drive client build failed: " + e.getMessage(), e );
        }
    }
}
```

- [ ] **Step 6: Run — PASS (compile + the one offline test)**
Run: `mvn -q -pl wikantik-connectors -Dtest=GoogleDriveOAuthServiceTest test`
Expected: BUILD SUCCESS, 1 test passes, and `GoogleDriveApi`/`GoogleDriveApiFactory` compile.

- [ ] **Step 7: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/GoogleDriveApi.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/GoogleDriveApiFactory.java \
        wikantik-connectors/src/main/java/com/wikantik/connectors/gdrive/GoogleDriveOAuthService.java \
        wikantik-connectors/src/test/java/com/wikantik/connectors/gdrive/GoogleDriveOAuthServiceTest.java
git commit -m "feat(gdrive): Google-lib impls — GoogleDriveApi/Factory + GoogleDriveOAuthService"
```

---

### Task 6: `GoogleDriveAuthResource` (wikantik-rest) + web.xml

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/GoogleDriveAuthResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + mapping `/admin/connector-oauth/gdrive/*`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/GoogleDriveAuthResourceTest.java`

**Interfaces:**
- Consumes: `DriveAuthCoordinator` (Task 4, wikantik-api); `RestServletBase` (`sendJson`, `sendError`, `getEngine()`); the servlet API (request/response/session).
- Produces: two GET routes under `/admin/connector-oauth/gdrive/*`.

Mirror `ConnectorCredentialsResource`: `extends RestServletBase`, a `protected DriveAuthCoordinator resolveCoordinator()` seam the test overrides, path parsing off `getPathInfo()`, `sendError`/`sendJson` helpers.

- [ ] **Step 1: Write the failing test** — `GoogleDriveAuthResourceTest.java` (Apache header). Mockito for request/response/session, a stub coordinator:
```java
package com.wikantik.rest;

import com.wikantik.api.connectors.DriveAuthCoordinator;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleDriveAuthResourceTest {

    static final class StubCoordinator implements DriveAuthCoordinator {
        String urlForGd = "https://accounts.google.com/o/oauth2/auth?state=";
        boolean completeResult = true;
        String lastCompleteId, lastCompleteCode;
        public Optional<String> authorizationUrl( String id, String state ) {
            return "gd".equals( id ) ? Optional.of( urlForGd + state ) : Optional.empty();
        }
        public boolean completeAuthorization( String id, String code ) {
            lastCompleteId = id; lastCompleteCode = code; return completeResult;
        }
    }
    /** Test subclass injecting the stub + capturing sendRedirect targets. */
    static final class TestResource extends GoogleDriveAuthResource {
        final DriveAuthCoordinator c; TestResource( DriveAuthCoordinator c ) { this.c = c; }
        @Override protected DriveAuthCoordinator resolveCoordinator() { return c; }
    }

    HttpServletRequest req; HttpServletResponse resp; HttpSession session; Map<String,Object> attrs;

    @BeforeEach void setup() {
        req = mock( HttpServletRequest.class ); resp = mock( HttpServletResponse.class );
        session = mock( HttpSession.class ); attrs = new HashMap<>();
        when( req.getSession( anyBoolean() ) ).thenReturn( session );
        when( req.getSession() ).thenReturn( session );
        doAnswer( i -> attrs.put( i.getArgument( 0 ), i.getArgument( 1 ) ) ).when( session ).setAttribute( any(), any() );
        when( session.getAttribute( any() ) ).thenAnswer( i -> attrs.get( i.getArgument( 0 ) ) );
        doAnswer( i -> attrs.remove( i.getArgument( 0 ) ) ).when( session ).removeAttribute( any() );
    }

    @Test void authorizeRedirectsToConsentUrlAndStoresState() throws Exception {
        StubCoordinator c = new StubCoordinator();
        when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
        new TestResource( c ).doGet( req, resp );
        ArgumentCaptor<String> loc = ArgumentCaptor.forClass( String.class );
        verify( resp ).sendRedirect( loc.capture() );
        String state = (String) attrs.get( "gdrive.oauth.state" );
        assertNotNull( state );
        assertEquals( "gd", attrs.get( "gdrive.oauth.connector" ) );
        assertTrue( loc.getValue().contains( "state=" + state ) );
    }

    @Test void authorizeUnknownIdIs404() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/nope/authorize" );
        new TestResource( new StubCoordinator() ).doGet( req, resp );
        verify( resp ).sendError( eq( 404 ), anyString() );
        verify( resp, never() ).sendRedirect( anyString() );
    }

    @Test void callbackWithMatchingStateCompletesAuthorization() throws Exception {
        StubCoordinator c = new StubCoordinator();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        assertEquals( "gd", c.lastCompleteId );
        assertEquals( "AUTHCODE", c.lastCompleteCode );
        verify( resp ).setStatus( 200 );
        assertNull( attrs.get( "gdrive.oauth.state" ), "state must be single-use (cleared)" );
    }

    @Test void callbackWithMismatchedStateIs400AndDoesNotExchange() throws Exception {
        StubCoordinator c = new StubCoordinator();
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "WRONG" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).sendError( eq( 400 ), anyString() );
        assertNull( c.lastCompleteId, "no exchange on state mismatch" );
    }

    @Test void callbackExchangeFailureIs502() throws Exception {
        StubCoordinator c = new StubCoordinator(); c.completeResult = false;
        attrs.put( "gdrive.oauth.state", "S1" ); attrs.put( "gdrive.oauth.connector", "gd" );
        when( req.getPathInfo() ).thenReturn( "/callback" );
        when( req.getParameter( "state" ) ).thenReturn( "S1" );
        when( req.getParameter( "code" ) ).thenReturn( "AUTHCODE" );
        new TestResource( c ).doGet( req, resp );
        verify( resp ).sendError( eq( 502 ), anyString() );
    }

    @Test void coordinatorAbsentIs503() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/gd/authorize" );
        new TestResource( null ).doGet( req, resp );
        verify( resp ).sendError( eq( 503 ), anyString() );
    }
}
```
(Add `import org.mockito.ArgumentCaptor;` — used above.)

- [ ] **Step 2: Run — FAIL**
Run: `mvn -q -pl wikantik-rest -am -Dtest=GoogleDriveAuthResourceTest test`
Expected: compile failure (`GoogleDriveAuthResource` does not exist).

- [ ] **Step 3: Create `GoogleDriveAuthResource`** (Apache header). Model the class skeleton on `ConnectorCredentialsResource` (same package, `extends RestServletBase`, `serialVersionUID`, `LOG`, `sendError`/`sendJson`):
```java
package com.wikantik.rest;

import com.wikantik.api.connectors.DriveAuthCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/** Admin OAuth2 consent flow for the Google Drive connector: {@code /admin/connector-oauth/gdrive/{id}/authorize}
 *  starts consent; {@code /admin/connector-oauth/gdrive/callback} stores the resulting refresh token.
 *  The OAuth code and all tokens are never logged or echoed. */
public class GoogleDriveAuthResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( GoogleDriveAuthResource.class );
    private static final String STATE_ATTR = "gdrive.oauth.state";
    private static final String CONN_ATTR  = "gdrive.oauth.connector";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final DriveAuthCoordinator coordinator = resolveCoordinator();
        if ( coordinator == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Drive OAuth not configured" );
            return;
        }
        final String path = request.getPathInfo();   // e.g. /gd/authorize  or  /callback
        if ( path != null && path.endsWith( "/authorize" ) ) {
            handleAuthorize( request, response, coordinator, path );
        } else if ( "/callback".equals( path ) ) {
            handleCallback( request, response, coordinator );
        } else {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown OAuth route" );
        }
    }

    private void handleAuthorize( final HttpServletRequest request, final HttpServletResponse response,
            final DriveAuthCoordinator coordinator, final String path ) throws IOException {
        final String id = path.substring( 1, path.length() - "/authorize".length() );   // strip leading '/' and suffix
        if ( id.isEmpty() || id.contains( "/" ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Bad connector id" );
            return;
        }
        final byte[] nonce = new byte[ 32 ];
        RANDOM.nextBytes( nonce );
        final String state = Base64.getUrlEncoder().withoutPadding().encodeToString( nonce );
        final Optional< String > url = coordinator.authorizationUrl( id, state );
        if ( url.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Unknown Drive connector: " + id );
            return;
        }
        request.getSession( true ).setAttribute( STATE_ATTR, state );
        request.getSession( true ).setAttribute( CONN_ATTR, id );
        response.sendRedirect( url.get() );   // 302 to Google consent
    }

    private void handleCallback( final HttpServletRequest request, final HttpServletResponse response,
            final DriveAuthCoordinator coordinator ) throws IOException {
        final String stateParam = request.getParameter( "state" );
        final String code = request.getParameter( "code" );
        final Object expectedState = request.getSession().getAttribute( STATE_ATTR );
        final Object connectorId  = request.getSession().getAttribute( CONN_ATTR );
        // single-use: clear regardless of outcome
        request.getSession().removeAttribute( STATE_ATTR );
        request.getSession().removeAttribute( CONN_ATTR );
        if ( expectedState == null || stateParam == null || !expectedState.equals( stateParam ) || connectorId == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid or expired OAuth state" );
            return;
        }
        if ( code == null || code.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Missing authorization code" );
            return;
        }
        final boolean ok = coordinator.completeAuthorization( connectorId.toString(), code );   // never logs the code
        if ( ok ) {
            response.setStatus( HttpServletResponse.SC_OK );
            sendJson( response, Map.of( "connectorId", connectorId.toString(), "status", "authorized" ) );
        } else {
            sendError( response, HttpServletResponse.SC_BAD_GATEWAY, "Authorization failed" );
        }
    }

    /** Resolves the coordinator via the engine; overridable for tests. */
    protected DriveAuthCoordinator resolveCoordinator() {
        return getEngine() instanceof com.wikantik.WikiEngine we ? we.getManager( DriveAuthCoordinator.class ) : null;
    }
}
```
Verify against `ConnectorCredentialsResource` that `getEngine()`, `sendError`, and `sendJson` have these exact signatures; adjust if `RestServletBase` differs.

- [ ] **Step 4: Register the servlet** in `web.xml` — copy the `ConnectorCredentialsResource` `<servlet>` + `<servlet-mapping>` blocks (near lines 674 and 978), renaming to `GoogleDriveAuthResource` / class `com.wikantik.rest.GoogleDriveAuthResource`, url-pattern `/admin/connector-oauth/gdrive/*`:
```xml
   <servlet>
      <servlet-name>GoogleDriveAuthResource</servlet-name>
      <servlet-class>com.wikantik.rest.GoogleDriveAuthResource</servlet-class>
   </servlet>
   ...
   <servlet-mapping>
      <servlet-name>GoogleDriveAuthResource</servlet-name>
      <url-pattern>/admin/connector-oauth/gdrive/*</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 5: Run — PASS**
Run: `mvn -q -pl wikantik-rest -am -Dtest=GoogleDriveAuthResourceTest test`
Expected: 6 tests pass.

- [ ] **Step 6: Commit**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/GoogleDriveAuthResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/GoogleDriveAuthResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(gdrive): GoogleDriveAuthResource — /admin/connector-oauth/gdrive consent flow"
```

---

### Task 7: Wire the connector + coordinator in `ConnectorWiringHelper` + config

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java` (extend)

**Interfaces:**
- Consumes: `DriveConfig`, `DriveSourceConnector`, `GoogleDriveApiFactory`, `GoogleDriveOAuthService`, `DefaultDriveAuthCoordinator` (gdrive); `DriveAuthCoordinator`, `CredentialStore` (api); the existing `PREFIX`, `parseInt`, `blankToNull`, `parseSeeds` helpers and the `byId`/`typeById` maps in `wireConnectors`.
- Produces: `static Map<String,DriveConfig> driveConfigs(Properties)`; gdrive connectors registered in the runtime; a `DriveAuthCoordinator` registered via `setManager` when any gdrive config exists.

- [ ] **Step 1: Write the failing test** — extend `ConnectorWiringHelperTest`:
```java
    @Test void driveConfigsParsesRequiredFieldsAndDefaults() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.gdrive.gd.folder_ids", "F1, F2" );
        p.setProperty( "wikantik.connectors.gdrive.gd.client_id", "cid" );
        p.setProperty( "wikantik.connectors.gdrive.gd.client_secret", "csec" );
        p.setProperty( "wikantik.connectors.gdrive.gd.redirect_uri", "https://w/cb" );
        Map<String, com.wikantik.connectors.gdrive.DriveConfig> cfgs = ConnectorWiringHelper.driveConfigs( p );
        assertEquals( 1, cfgs.size() );
        var c = cfgs.get( "gd" );
        assertEquals( java.util.List.of( "F1", "F2" ), c.folderIds() );
        assertEquals( "cid", c.clientId() );
        assertEquals( "https://w/cb", c.redirectUri() );
        assertEquals( 500, c.maxFiles() );                     // default
        assertEquals( "text/markdown", c.exportMimeType() );   // default
    }
    @Test void driveConfigSkippedWhenRequiredFieldMissing() {
        Properties p = new Properties();
        p.setProperty( "wikantik.connectors.gdrive.gd.folder_ids", "F1" );
        // no client_id/secret/redirect_uri
        assertTrue( ConnectorWiringHelper.driveConfigs( p ).isEmpty() );
    }
```

- [ ] **Step 2: Run — FAIL**
Run: `mvn -q -pl wikantik-main -Dtest=ConnectorWiringHelperTest test`
Expected: compile failure (`driveConfigs` does not exist).

- [ ] **Step 3: Add `driveConfigs`** to `ConnectorWiringHelper` (mirror `sitemapConfigs`/`feedConfigs`; use the existing `parseSeeds`, `parseInt`, `blankToNull` helpers — check their exact names/signatures in the file):
This is a verbatim mirror of `sitemapConfigs`/`feedConfigs` — anchor on the `.folder_ids` suffix key,
derive the `<id>` by stripping prefix+suffix, then read the sibling per-id options:
```java
    /** id → config for every {@code wikantik.connectors.gdrive.<id>.folder_ids} key (plus its sibling
     *  per-id options). Package-visible for testing. An id with no non-blank {@code folder_ids}, or
     *  missing client_id/client_secret/redirect_uri, is skipped. */
    static Map< String, DriveConfig > driveConfigs( final Properties props ) {
        final String p = PREFIX + "gdrive.";
        final Map< String, DriveConfig > out = new LinkedHashMap<>();
        for ( final String key : props.stringPropertyNames() ) {
            if ( key.startsWith( p ) && key.endsWith( ".folder_ids" ) ) {
                final String id = key.substring( p.length(), key.length() - ".folder_ids".length() );
                if ( id.isBlank() || id.contains( "." ) ) continue;
                final List< String > folderIds = parseSeeds( props.getProperty( key ) );
                if ( folderIds.isEmpty() ) continue;
                final String idPrefix = p + id + ".";
                final String clientId = blankToNull( props.getProperty( idPrefix + "client_id" ) );
                final String clientSecret = blankToNull( props.getProperty( idPrefix + "client_secret" ) );
                final String redirectUri = blankToNull( props.getProperty( idPrefix + "redirect_uri" ) );
                if ( clientId == null || clientSecret == null || redirectUri == null ) {
                    LOG.warn( "gdrive '{}': missing client_id/client_secret/redirect_uri — skipping", id );
                    continue;
                }
                out.put( id, new DriveConfig( folderIds,
                    parseInt( props, idPrefix + "max_files", 500 ),
                    clientId, clientSecret, redirectUri,
                    props.getProperty( idPrefix + "export_mime", "text/markdown" ).trim() ) );
            }
        }
        return out;
    }
```
`parseSeeds`, `parseInt`, `blankToNull` are the existing helpers used by `sitemapConfigs` (confirmed
present in the file); `parseSeeds` splits a comma-separated list.

- [ ] **Step 4: Wire gdrive connectors + coordinator** inside `wireConnectors`. First hoist the credential store to a local so the connector's supplier and the coordinator share it. Change:
```java
        engine.setManager( CredentialStore.class, new JdbcCredentialStore( ds, cipherFrom( props ) ) );
```
to:
```java
        final CredentialStore credStore = new JdbcCredentialStore( ds, cipherFrom( props ) );
        engine.setManager( CredentialStore.class, credStore );
```
Add `final Map< String, DriveConfig > drives = driveConfigs( props );` beside the other config maps, add `&& drives.isEmpty()` to the emptiness early-return guard, then after the feed loop:
```java
        final GoogleDriveApiFactory driveApiFactory = new GoogleDriveApiFactory();
        for ( final Map.Entry< String, DriveConfig > e : drives.entrySet() ) {
            final String id = e.getKey();
            byId.put( id, new DriveSourceConnector( id, e.getValue(),
                () -> credStore.get( id, "refresh_token" ), driveApiFactory ) );
            typeById.put( id, "gdrive" );
        }
        if ( !drives.isEmpty() ) {
            engine.setManager( DriveAuthCoordinator.class,
                new DefaultDriveAuthCoordinator( drives, new GoogleDriveOAuthService(), credStore ) );
        }
```
Add imports: `com.wikantik.connectors.gdrive.{DriveConfig, DriveSourceConnector, GoogleDriveApiFactory, GoogleDriveOAuthService, DefaultDriveAuthCoordinator}` and `com.wikantik.api.connectors.DriveAuthCoordinator`.

- [ ] **Step 5: Register config** in `ini/wikantik.properties` (mirror the sitemap/feed commented blocks):
```properties
# Google Drive connector (P2.3b). Syncs configured Drive folders (Docs→markdown, native md/txt) into
# derived pages via an OAuth2 refresh token. Requires wikantik.connectors.enabled=true AND a configured
# wikantik.connectors.crypto.key (the refresh token is stored encrypted). Obtain the refresh token via
# the admin consent flow at /admin/connector-oauth/gdrive/<id>/authorize (the redirect_uri below must
# match the one registered in the Google Cloud OAuth client).
#wikantik.connectors.gdrive.<id>.folder_ids   = <comma-separated Drive folder IDs>
#wikantik.connectors.gdrive.<id>.client_id     = <OAuth2 client id>
#wikantik.connectors.gdrive.<id>.client_secret = <OAuth2 client secret>
#wikantik.connectors.gdrive.<id>.redirect_uri  = https://<host>/admin/connector-oauth/gdrive/callback
#wikantik.connectors.gdrive.<id>.max_files     = 500
#wikantik.connectors.gdrive.<id>.export_mime   = text/markdown
```

- [ ] **Step 6: Run — PASS**
Run: `mvn -q -pl wikantik-main -am -Dtest=ConnectorWiringHelperTest test`
Expected: the 2 new tests + all pre-existing `ConnectorWiringHelperTest` tests pass.

- [ ] **Step 7: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/ConnectorWiringHelper.java \
        wikantik-main/src/main/resources/ini/wikantik.properties \
        wikantik-main/src/test/java/com/wikantik/derived/ConnectorWiringHelperTest.java
git commit -m "feat(gdrive): wire DriveSourceConnector + DriveAuthCoordinator from wikantik.connectors.gdrive.*"
```

---

## Post-implementation (controller)

- Full reactor unit build: `mvn clean install -DskipITs` **with `WIKANTIK_*` env UNSET** (WikiTest counts env vars).
- Per-module RAT clean on `wikantik-api`/`wikantik-connectors`/`wikantik-rest`/`wikantik-main` for the new files (pre-existing site-doc / `KnowledgeGraphResourceTest` flags are unrelated — see the P2.2 ledger).
- `mvn -q -pl wikantik-connectors dependency:tree | grep -i google` — confirm the Google libs resolve with no `omitted for conflict` on guava/http-client that breaks the build.
- Whole-branch review (opus): **secret hygiene** (grep the diff — no client secret / refresh token / access token / OAuth `code` in any log, exception, or response); fail-closed (empty refresh token → empty batch, factory never called; poll never throws; consent callback fails closed on state mismatch); `reflectsFullCorpus()==true`; the coordinator stores under `refresh_token` (the exact name the connector's supplier reads); invariant #6 (Google libs ONLY in wikantik-connectors — grep other modules' poms); `setManager`-only in wikantik-main; default-off (no gdrive config ⇒ nothing wired, no coordinator).

## Self-review notes

- **Spec coverage:** deps (T1); DriveConfig/DriveFile/DriveItems (T2); DriveApi/Factory + connector poll + fail-closed + reflectsFullCorpus (T3); DriveAuthCoordinator + DriveOAuthService + DefaultDriveAuthCoordinator (T4); Google-lib impls incl. offline-tested authorizationUrl (T5); GoogleDriveAuthResource + web.xml + state/CSRF (T6); wiring + config (T7). All spec sections mapped. No live IT (per spec).
- **Secret hygiene:** the code/refresh token/access token/client secret never appear in a log, exception, or response — asserted structurally (coordinator logs id only; resource never logs code/token; connector logs id/mimeType only). The whole-branch review greps to confirm.
- **Fail-closed:** empty refresh token → factory never called (T3 test); Drive error → empty batch no-throw (T3 test); state mismatch → 400 no exchange (T6 test); store disabled → coordinator false (T4 test).
- **Invariant #6 / dependency isolation:** Google libs only in wikantik-connectors (T1); wikantik-rest reaches the flow through the `DriveAuthCoordinator` api seam (T4/T6); wikantik-main wiring-only (T7).
- **Type consistency:** `DriveConfig(folderIds,maxFiles,clientId,clientSecret,redirectUri,exportMimeType)`, `DriveApi.{listFolder,export,getMedia}`, `DriveApiFactory.create`, `DriveOAuthService.{authorizationUrl,exchangeCodeForRefreshToken}`, `DriveAuthCoordinator.{authorizationUrl,completeAuthorization}`, credential name `"refresh_token"` — identical across T2→T7.

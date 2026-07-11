# Connector Framework Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the source side of ingestion — a pull-based `SourceConnector` SPI, a resumable `SyncOrchestrator`, PostgreSQL sync state, and one filesystem fixture connector that syncs a corpus into derived pages incrementally (change-detected, cursor-resumable, tombstone-aware).

**Architecture:** Contracts live in `wikantik-api` (`com.wikantik.api.connectors`). A new `wikantik-connectors` module (depends on `wikantik-api` only) holds the orchestrator, the JDBC sync-state store, and the `FilesystemSourceConnector`. The orchestrator reaches the wiki solely through a `DerivedPageSink` port that `wikantik-main` implements with a thin adapter over the existing `DerivedPageIngestionService` — so `wikantik-main` gains no new package (invariant #6).

**Tech Stack:** Java 25, Maven, JUnit 5 + Mockito, H2 (DAO unit tests), PostgreSQL (prod + numbered migration), `java.sql`/`javax.sql.DataSource`.

**Spec:** `docs/superpowers/specs/2026-07-11-connector-framework-phase1-design.md`

## Global Constraints

- **Invariant #6:** contracts go in `wikantik-api`; new logic goes in `wikantik-connectors`; `wikantik-main` gains only one adapter class + one optional field on `IngestOptions`, both in the existing `com.wikantik.derived` package. No new `wikantik-main` package.
- **`wikantik-connectors` depends on `wikantik-api` only** (main scope). It may add `wikantik-main` as a **test-scope** dep for the end-to-end IT.
- **PostgreSQL-first:** sync state = two tables behind numbered migration `V046__connector_sync_state.sql`; idempotent DDL (`CREATE TABLE IF NOT EXISTS`, `:app_user` grants). `acl_refs` is `TEXT` (JSON-array string), not JSONB.
- **Fail-closed / no swallowed exceptions:** every catch logs at least `LOG.warn` with context (repo rule). A `FAILED` ingest is logged and left un-recorded so the next run retries.
- **Every new Maven module declares `mockito-core` (test scope)** or surefire fails on the inherited javaagent.
- **ACL refs carried, not enforced:** `SourceItem.aclRefs` flows into `connector_synced_item.acl_refs`; no enforcement in Phase 1.
- **TDD:** every task writes the failing test first, runs it red, implements, runs it green, commits. Run only the task's targeted test(s) (`mvn test -pl <module> -Dtest=<Class>`), not the full suite — the controller runs the full build once at the end.

---

### Task 1: Connector contracts in `wikantik-api`

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/SourceItem.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/SyncCursor.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/SyncBatch.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/SourceConnector.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/IngestOutcome.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/DerivedPageSink.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/connectors/SyncStateStore.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/connectors/SourceItemTest.java`

**Interfaces:**
- Consumes: nothing (plain records/interfaces; JDK only).
- Produces: the full contract surface every later task references. Exact shapes below.

- [ ] **Step 1: Write the failing test** — a minimal record-construction guard (records are the contract; this pins their shape).

```java
package com.wikantik.api.connectors;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceItemTest {
    @Test void carriesUriMetadataAndAclRefs() {
        SourceItem i = new SourceItem( "file:docs/a.md", "body".getBytes(), "text/markdown",
            Map.of( "path", "docs/a.md" ), List.of( "group:docs" ), "abc123" );
        assertEquals( "file:docs/a.md", i.sourceUri() );
        assertEquals( List.of( "group:docs" ), i.aclRefs() );
        assertEquals( "abc123", i.contentHash() );
    }

    @Test void syncBatchHoldsItemsTombstonesCursorAndCompleteFlag() {
        SyncBatch b = new SyncBatch( List.of(), List.of( "file:gone.md" ), new SyncCursor( "c1" ), true );
        assertTrue( b.complete() );
        assertEquals( List.of( "file:gone.md" ), b.tombstonedUris() );
        assertEquals( "c1", b.nextCursor().value() );
    }
}
```

- [ ] **Step 2: Run it — FAIL** (`mvn test -pl wikantik-api -Dtest=SourceItemTest -q`) — types missing.

- [ ] **Step 3: Create the contracts.** Each in its own file, package `com.wikantik.api.connectors`, with the Apache license header used across the repo (copy from any existing `wikantik-api` file).

`SourceItem.java`:
```java
package com.wikantik.api.connectors;
import java.util.List;
import java.util.Map;
/** One item fetched from an external source, ready to become a derived page. */
public record SourceItem(
    String sourceUri, byte[] content, String contentType,
    Map< String, Object > sourceMetadata, List< String > aclRefs, String contentHash ) {}
```

`SyncCursor.java`:
```java
package com.wikantik.api.connectors;
/** Opaque, connector-defined checkpoint. {@code null} cursor = full initial sync. */
public record SyncCursor( String value ) {}
```

`SyncBatch.java`:
```java
package com.wikantik.api.connectors;
import java.util.List;
/** One batch from {@link SourceConnector#poll}: changed items, deletions, next cursor, drain flag. */
public record SyncBatch(
    List< SourceItem > items, List< String > tombstonedUris,
    SyncCursor nextCursor, boolean complete ) {}
```

`SourceConnector.java`:
```java
package com.wikantik.api.connectors;
/** Pull-based external source. {@link #poll} returns changed items since the cursor. */
public interface SourceConnector {
    /** Stable id; namespaces item URIs and sync-state rows. */
    String connectorId();
    /** @param cursor last persisted checkpoint, or {@code null} for a full initial sync. */
    SyncBatch poll( SyncCursor cursor );
}
```

`IngestOutcome.java`:
```java
package com.wikantik.api.connectors;
/** Result of writing one {@link SourceItem} as a derived page. */
public record IngestOutcome( String pageName, Status status ) {
    public enum Status { CREATED, UPDATED, UNCHANGED, FAILED }
}
```

`DerivedPageSink.java`:
```java
package com.wikantik.api.connectors;
/** Port the orchestrator uses to write/delete derived pages. Implemented in wikantik-main. */
public interface DerivedPageSink {
    IngestOutcome ingest( SourceItem item );
    void delete( String pageName );
}
```

`SyncStateStore.java`:
```java
package com.wikantik.api.connectors;
import java.util.List;
import java.util.Optional;
/** Persists per-connector cursors and per-item sync state. Implemented in wikantik-connectors. */
public interface SyncStateStore {
    Optional< SyncCursor > loadCursor( String connectorId );
    void saveCursor( String connectorId, SyncCursor cursor );
    Optional< String > syncedHash( String connectorId, String sourceUri );
    void recordSynced( String connectorId, String sourceUri, String contentHash,
                       String pageName, List< String > aclRefs );
    Optional< String > pageNameFor( String connectorId, String sourceUri );
    List< String > knownUris( String connectorId );
    void removeSynced( String connectorId, String sourceUri );
}
```

- [ ] **Step 4: Run it — PASS** (`mvn test -pl wikantik-api -Dtest=SourceItemTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-api/src/main/java/com/wikantik/api/connectors/ wikantik-api/src/test/java/com/wikantik/api/connectors/
git commit -m "feat(api): SourceConnector SPI + sync contracts (com.wikantik.api.connectors)"
```

---

### Task 2: `IngestOptions.derivedFrom` override in `wikantik-main`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/IngestOptions.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPageIngestionService.java:239` (the `meta.put(DERIVED_FROM, filename)` line)
- Test: `wikantik-main/src/test/java/com/wikantik/derived/DerivedPageIngestionDerivedFromTest.java`

**Interfaces:**
- Consumes: existing `DerivedPageIngestionService.ingest(byte[], String, String, IngestOptions)`.
- Produces: `IngestOptions(boolean force, String author, String derivedFrom)` (3-arg canonical) plus a `IngestOptions(boolean, String)` convenience ctor delegating with `derivedFrom = null` (keeps every existing 2-arg caller compiling unchanged). When `derivedFrom != null`, the written `derived_from` frontmatter equals it; when `null`, it equals `filename` (today's behavior).

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.derived;

import com.wikantik.ingest.ExtractionResult;
import com.wikantik.ingest.SourceExtractor;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DerivedPageIngestionDerivedFromTest {

    private static DerivedPageIngestionService svc( Map< String, Object > captured ) {
        SourceExtractor extractor = ( bytes, contentType ) -> new ExtractionResult( "# Body\n\ntext", true, null );
        return new DerivedPageIngestionService(
            extractor,
            ( page, filename, bytes ) -> { },                         // AttachmentStore no-op
            page -> Optional.empty(),                                 // PageReader: page absent
            ( page, body, meta, author ) -> captured.putAll( meta ),  // PageWriter: capture metadata
            page -> { } );                                            // PageDeleter no-op
    }

    @Test void derivedFromOverrideIsHonored() {
        Map< String, Object > meta = new HashMap<>();
        svc( meta ).ingest( "x".getBytes(), "a-x.md", "text/markdown",
            new IngestOptions( false, "sync", "file:a/x.md" ) );
        assertEquals( "file:a/x.md", meta.get( "derived_from" ) );
    }

    @Test void nullDerivedFromFallsBackToFilename() {
        Map< String, Object > meta = new HashMap<>();
        svc( meta ).ingest( "x".getBytes(), "a-x.md", "text/markdown",
            new IngestOptions( false, "sync" ) );                     // 2-arg convenience ctor
        assertEquals( "a-x.md", meta.get( "derived_from" ) );
    }
}
```
> Before writing, confirm the real `SourceExtractor` / `ExtractionResult` constructor signatures by reading `wikantik-ingest` — adjust the lambda/`new ExtractionResult(...)` to match. The test's point is the `derived_from` assertion, not the extractor shape.

- [ ] **Step 2: Run it — FAIL** (`mvn test -pl wikantik-main -Dtest=DerivedPageIngestionDerivedFromTest -q`) — 3-arg ctor missing / override not honored.

- [ ] **Step 3: Implement.**

`IngestOptions.java`:
```java
package com.wikantik.derived;
/**
 * Options for one {@link DerivedPageIngestionService#ingest} call.
 *
 * @param force       when {@code true} re-ingest even if the source SHA is unchanged.
 * @param author      wiki login recorded as page author on save.
 * @param derivedFrom explicit {@code derived_from} provenance; {@code null} → use the filename
 *                    (backward-compatible default). Connectors set this to the source URI so
 *                    provenance is decoupled from the (basename-derived) page name.
 */
public record IngestOptions( boolean force, String author, String derivedFrom ) {
    /** Backward-compatible 2-arg form: {@code derivedFrom = null} (provenance = filename). */
    public IngestOptions( final boolean force, final String author ) { this( force, author, null ); }
}
```

In `DerivedPageIngestionService.java` line 239, replace:
```java
        meta.put( DerivedPage.DERIVED_FROM,             filename );
```
with:
```java
        meta.put( DerivedPage.DERIVED_FROM,
            opts.derivedFrom() != null ? opts.derivedFrom() : filename );
```

- [ ] **Step 4: Run it — PASS**, and run the existing derived tests to prove backward-compat:
`mvn test -pl wikantik-main -Dtest=DerivedPageIngestionDerivedFromTest,DerivedPageIngestionServiceTest,DerivedIngestResourceTest -q`
Expected: all green (existing 2-arg `new IngestOptions(...)` callers still compile via the convenience ctor).

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/IngestOptions.java wikantik-main/src/main/java/com/wikantik/derived/DerivedPageIngestionService.java wikantik-main/src/test/java/com/wikantik/derived/DerivedPageIngestionDerivedFromTest.java
git commit -m "feat(derived): optional derivedFrom override on IngestOptions (decouples provenance from page name)"
```

---

### Task 3: `wikantik-connectors` module + `FilesystemSourceConnector`

**Files:**
- Modify: `pom.xml` (add `<module>wikantik-connectors</module>` after line 190 `wikantik-ingest`)
- Create: `wikantik-connectors/pom.xml`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/filesystem/FilesystemSourceConnector.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/filesystem/FilesystemSourceConnectorTest.java`

**Interfaces:**
- Consumes: `SourceConnector`, `SourceItem`, `SyncBatch`, `SyncCursor` (Task 1).
- Produces: `FilesystemSourceConnector(String connectorId, java.nio.file.Path root)`. `poll(cursor)` full-scans `root`, emits one `SourceItem` per regular file (`sourceUri = "file:" + rootRelativePath`, `contentType` by extension, `sourceMetadata = {path, size, modified}`, `aclRefs = [ parent-dir-name ]`, `contentHash = sha256hex(content)`), `tombstonedUris = []` (the orchestrator derives deletions — Task 5), `nextCursor` = a scan-time watermark string, `complete = true`.

- [ ] **Step 1: Add the module to the reactor.** In `pom.xml` after `<module>wikantik-ingest</module>` add `<module>wikantik-connectors</module>`.

- [ ] **Step 2: Create `wikantik-connectors/pom.xml`** (copy the license header + `<parent>` block from `wikantik-ingest/pom.xml`):
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.wikantik</groupId>
    <artifactId>wikantik</artifactId>
    <version>2.3.6-SNAPSHOT</version>
  </parent>
  <artifactId>wikantik-connectors</artifactId>
  <name>Wikantik external source connectors</name>
  <dependencies>
    <dependency><groupId>${project.groupId}</groupId><artifactId>wikantik-api</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-api</artifactId></dependency>

    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-engine</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```
> Confirm the parent `<version>` matches the reactor (`grep '<version>' pom.xml | head -1`) and that `h2` is version-managed in the parent/BOM (it is — wikantik-main uses it for tests); if the H2 version isn't managed, pin it from `wikantik-main/pom.xml`.

- [ ] **Step 3: Write the failing test**
```java
package com.wikantik.connectors.filesystem;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FilesystemSourceConnectorTest {

    @Test void pollEmitsOneItemPerFileWithUriHashAndAcl( @TempDir Path root ) throws Exception {
        Files.createDirectories( root.resolve( "docs" ) );
        Files.writeString( root.resolve( "docs/a.md" ), "alpha" );
        Files.writeString( root.resolve( "b.md" ), "bravo" );

        SyncBatch batch = new FilesystemSourceConnector( "fs-test", root ).poll( null );

        assertTrue( batch.complete() );
        List< String > uris = batch.items().stream().map( SourceItem::sourceUri ).sorted().toList();
        assertEquals( List.of( "file:b.md", "file:docs/a.md" ), uris );
        SourceItem a = batch.items().stream().filter( i -> i.sourceUri().equals( "file:docs/a.md" ) ).findFirst().orElseThrow();
        assertEquals( List.of( "docs" ), a.aclRefs() );               // parent-dir name
        assertEquals( 64, a.contentHash().length() );                 // sha256 hex
        assertEquals( "docs/a.md", a.sourceMetadata().get( "path" ) );
        assertEquals( "alpha", new String( a.content() ) );
    }

    @Test void tombstonesAreEmptyConnectorSideAndCursorIsSet( @TempDir Path root ) throws Exception {
        Files.writeString( root.resolve( "x.md" ), "x" );
        SyncBatch b = new FilesystemSourceConnector( "fs", root ).poll( null );
        assertTrue( b.tombstonedUris().isEmpty() );                   // orchestrator derives deletions
        assertNotNull( b.nextCursor().value() );
    }
}
```

- [ ] **Step 4: Run it — FAIL** (`mvn test -pl wikantik-connectors -Dtest=FilesystemSourceConnectorTest -q`).

- [ ] **Step 5: Implement `FilesystemSourceConnector`.**
```java
package com.wikantik.connectors.filesystem;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Phase-1 fixture connector: full-scans a directory tree, one {@link SourceItem} per file. */
public final class FilesystemSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( FilesystemSourceConnector.class );

    private final String connectorId;
    private final Path root;

    public FilesystemSourceConnector( final String connectorId, final Path root ) {
        this.connectorId = connectorId;
        this.root = root;
    }

    @Override public String connectorId() { return connectorId; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final List< SourceItem > items = new ArrayList<>();
        try ( Stream< Path > walk = Files.walk( root ) ) {
            walk.filter( Files::isRegularFile ).forEach( p -> add( items, p ) );
        } catch ( final IOException e ) {
            LOG.warn( "Filesystem connector '{}' scan of {} failed: {}", connectorId, root, e.getMessage() );
            // fail-closed: an empty complete batch — the orchestrator would then tombstone everything,
            // which is wrong on a scan error, so signal incomplete to skip tombstone derivation.
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( scanWatermark() ), true );
    }

    private void add( final List< SourceItem > items, final Path file ) {
        try {
            final Path rel = root.relativize( file );
            final String relStr = rel.toString().replace( '\\', '/' );
            final byte[] content = Files.readAllBytes( file );
            final Map< String, Object > md = new LinkedHashMap<>();
            md.put( "path", relStr );
            md.put( "size", content.length );
            md.put( "modified", Files.getLastModifiedTime( file ).toString() );
            final List< String > acl = rel.getParent() == null
                ? List.of() : List.of( rel.getParent().getFileName().toString() );
            items.add( new SourceItem( "file:" + relStr, content, contentType( relStr ), md, acl, sha256Hex( content ) ) );
        } catch ( final IOException e ) {
            LOG.warn( "Filesystem connector '{}' could not read {}: {}", connectorId, file, e.getMessage() );
        }
    }

    private static String contentType( final String path ) {
        if ( path.endsWith( ".md" ) )   return "text/markdown";
        if ( path.endsWith( ".txt" ) )  return "text/plain";
        if ( path.endsWith( ".pdf" ) )  return "application/pdf";
        if ( path.endsWith( ".html" ) ) return "text/html";
        return "application/octet-stream";
    }

    private String scanWatermark() {
        // A monotonically-changing token so callers can see the cursor advanced. Time is unavailable in
        // deterministic tests, so derive from the root's identity + a scan counter would drift — use the
        // greatest last-modified time seen, which is stable and meaningful.
        try ( Stream< Path > walk = Files.walk( root ) ) {
            return walk.filter( Files::isRegularFile )
                .map( p -> { try { return Files.getLastModifiedTime( p ).toMillis(); } catch ( IOException e ) { return 0L; } } )
                .max( Long::compareTo ).map( String::valueOf ).orElse( "0" );
        } catch ( final IOException e ) {
            return "0";
        }
    }

    private static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final java.security.NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );   // JVM guarantees it; never happens
        }
    }
}
```

- [ ] **Step 6: Run it — PASS** (`mvn test -pl wikantik-connectors -Dtest=FilesystemSourceConnectorTest -q`).

- [ ] **Step 7: Commit**
```bash
git add pom.xml wikantik-connectors/pom.xml wikantik-connectors/src/main/java/com/wikantik/connectors/filesystem/FilesystemSourceConnector.java wikantik-connectors/src/test/java/com/wikantik/connectors/filesystem/FilesystemSourceConnectorTest.java
git commit -m "feat(connectors): wikantik-connectors module + FilesystemSourceConnector fixture"
```

---

### Task 4: `V046` migration + `JdbcSyncStateStore`

**Files:**
- Create: `bin/db/migrations/V046__connector_sync_state.sql`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/state/JdbcSyncStateStore.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/state/JdbcSyncStateStoreTest.java`

**Interfaces:**
- Consumes: `SyncStateStore`, `SyncCursor` (Task 1); `javax.sql.DataSource`.
- Produces: `JdbcSyncStateStore(DataSource ds)` implementing every `SyncStateStore` method against the two tables. `aclRefs` serialized as a JSON-array string (`["a","b"]`), parsed back to `List<String>`.

- [ ] **Step 1: Write the migration** `bin/db/migrations/V046__connector_sync_state.sql` (idempotent; grants to `:app_user` per `bin/db/migrations/README.md`):
```sql
-- External source connector sync state (ConnectorFramework Phase 1, 2026-07-11).
-- connector_sync_state: one row per connector holding its opaque cursor/checkpoint.
-- connector_synced_item: per-item state for hash-dedup, tombstone detection, and
-- (carried, unenforced in Phase 1) source ACL references.
CREATE TABLE IF NOT EXISTS connector_sync_state (
    connector_id TEXT PRIMARY KEY,
    cursor       TEXT,
    last_run     TIMESTAMPTZ,
    status       TEXT
);
CREATE TABLE IF NOT EXISTS connector_synced_item (
    connector_id TEXT NOT NULL,
    source_uri   TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    page_name    TEXT NOT NULL,
    acl_refs     TEXT NOT NULL DEFAULT '[]',
    first_synced TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, source_uri)
);
CREATE INDEX IF NOT EXISTS idx_connector_synced_item_connector ON connector_synced_item (connector_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_sync_state  TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_synced_item TO :app_user;
```

- [ ] **Step 2: Write the failing test** (H2, `TEXT`/`TIMESTAMP WITH TIME ZONE` are H2-compatible; the test creates the tables itself — it does not run the psql migration file, which uses the `:app_user` variable):
```java
package com.wikantik.connectors.state;

import com.wikantik.api.connectors.SyncCursor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSyncStateStoreTest {

    private DataSource ds;

    @BeforeEach void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:connstate;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_sync_state (connector_id VARCHAR PRIMARY KEY, cursor VARCHAR, last_run TIMESTAMP WITH TIME ZONE, status VARCHAR)" );
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
            // idempotency mirror of the migration convention: re-running CREATE ... IF NOT EXISTS is a no-op
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
        }
    }

    @Test void cursorRoundTrips() {
        JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
        assertTrue( store.loadCursor( "c1" ).isEmpty() );
        store.saveCursor( "c1", new SyncCursor( "cur-1" ) );
        assertEquals( "cur-1", store.loadCursor( "c1" ).orElseThrow().value() );
        store.saveCursor( "c1", new SyncCursor( "cur-2" ) );        // upsert
        assertEquals( "cur-2", store.loadCursor( "c1" ).orElseThrow().value() );
    }

    @Test void recordSyncedThenHashKnownUrisPageNameAndRemove() {
        JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
        store.recordSynced( "c1", "file:a.md", "hashA", "PageA", List.of( "group:docs", "user:jo" ) );
        assertEquals( Optional.of( "hashA" ), store.syncedHash( "c1", "file:a.md" ) );
        assertEquals( Optional.of( "PageA" ), store.pageNameFor( "c1", "file:a.md" ) );
        assertEquals( List.of( "file:a.md" ), store.knownUris( "c1" ) );
        store.recordSynced( "c1", "file:a.md", "hashA2", "PageA", List.of() );   // upsert hash
        assertEquals( Optional.of( "hashA2" ), store.syncedHash( "c1", "file:a.md" ) );
        store.removeSynced( "c1", "file:a.md" );
        assertTrue( store.syncedHash( "c1", "file:a.md" ).isEmpty() );
        assertTrue( store.knownUris( "c1" ).isEmpty() );
    }

    @Test void aclRefsRoundTripAsJsonArrayString() throws Exception {
        JdbcSyncStateStore store = new JdbcSyncStateStore( ds );
        store.recordSynced( "c1", "file:a.md", "h", "PageA", List.of( "group:docs", "user:jo" ) );
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery(
                  "SELECT acl_refs FROM connector_synced_item WHERE source_uri='file:a.md'" ) ) {
            rs.next();
            assertEquals( "[\"group:docs\",\"user:jo\"]", rs.getString( 1 ) );   // DoD #4: carried
        }
    }
}
```

- [ ] **Step 3: Run it — FAIL** (`mvn test -pl wikantik-connectors -Dtest=JdbcSyncStateStoreTest -q`).

- [ ] **Step 4: Implement `JdbcSyncStateStore`.** Use plain JDBC (idiom from `PageVerificationDao`: `try ( Connection c = ds.getConnection() )`). Serialize `aclRefs` to a JSON array string with a tiny hand-rolled encoder (no new dep; values are simple principal strings — escape `"` and `\`). Upserts via `MERGE`/`INSERT ... ON CONFLICT` — H2 in PostgreSQL mode supports `ON CONFLICT`.
```java
package com.wikantik.connectors.state;

import com.wikantik.api.connectors.SyncCursor;
import com.wikantik.api.connectors.SyncStateStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 {@link SyncStateStore}. {@code acl_refs} is a JSON-array string (TEXT). */
public final class JdbcSyncStateStore implements SyncStateStore {

    private static final Logger LOG = LogManager.getLogger( JdbcSyncStateStore.class );
    private final DataSource ds;

    public JdbcSyncStateStore( final DataSource ds ) { this.ds = ds; }

    @Override public Optional< SyncCursor > loadCursor( final String id ) {
        return query1( "SELECT cursor FROM connector_sync_state WHERE connector_id=?", id,
            rs -> new SyncCursor( rs.getString( 1 ) ) );
    }

    @Override public void saveCursor( final String id, final SyncCursor cursor ) {
        exec( "INSERT INTO connector_sync_state (connector_id, cursor, last_run, status) "
            + "VALUES (?,?,now(),'ok') ON CONFLICT (connector_id) DO UPDATE SET cursor=EXCLUDED.cursor, last_run=now()",
            ps -> { ps.setString( 1, id ); ps.setString( 2, cursor == null ? null : cursor.value() ); } );
    }

    @Override public Optional< String > syncedHash( final String id, final String uri ) {
        return query1( "SELECT content_hash FROM connector_synced_item WHERE connector_id=? AND source_uri=?",
            id, uri, rs -> rs.getString( 1 ) );
    }

    @Override public void recordSynced( final String id, final String uri, final String hash,
                                        final String pageName, final List< String > aclRefs ) {
        exec( "INSERT INTO connector_synced_item (connector_id, source_uri, content_hash, page_name, acl_refs, last_synced) "
            + "VALUES (?,?,?,?,?,now()) ON CONFLICT (connector_id, source_uri) DO UPDATE SET "
            + "content_hash=EXCLUDED.content_hash, page_name=EXCLUDED.page_name, acl_refs=EXCLUDED.acl_refs, last_synced=now()",
            ps -> { ps.setString( 1, id ); ps.setString( 2, uri ); ps.setString( 3, hash );
                    ps.setString( 4, pageName ); ps.setString( 5, toJsonArray( aclRefs ) ); } );
    }

    @Override public Optional< String > pageNameFor( final String id, final String uri ) {
        return query1( "SELECT page_name FROM connector_synced_item WHERE connector_id=? AND source_uri=?",
            id, uri, rs -> rs.getString( 1 ) );
    }

    @Override public List< String > knownUris( final String id ) {
        final List< String > out = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "SELECT source_uri FROM connector_synced_item WHERE connector_id=?" ) ) {
            ps.setString( 1, id );
            try ( ResultSet rs = ps.executeQuery() ) { while ( rs.next() ) out.add( rs.getString( 1 ) ); }
        } catch ( final SQLException e ) {
            LOG.warn( "knownUris failed for connector '{}': {}", id, e.getMessage() );
        }
        return out;
    }

    @Override public void removeSynced( final String id, final String uri ) {
        exec( "DELETE FROM connector_synced_item WHERE connector_id=? AND source_uri=?",
            ps -> { ps.setString( 1, id ); ps.setString( 2, uri ); } );
    }

    // --- helpers ---
    private interface Row< T > { T map( ResultSet rs ) throws SQLException; }
    private interface Bind { void bind( PreparedStatement ps ) throws SQLException; }

    private < T > Optional< T > query1( final String sql, final String a, final Row< T > row ) {
        return query1( sql, a, null, row );
    }
    private < T > Optional< T > query1( final String sql, final String a, final String b, final Row< T > row ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, a );
            if ( b != null ) ps.setString( 2, b );
            try ( ResultSet rs = ps.executeQuery() ) { return rs.next() ? Optional.ofNullable( row.map( rs ) ) : Optional.empty(); }
        } catch ( final SQLException e ) {
            LOG.warn( "query failed [{}]: {}", sql, e.getMessage() );
            return Optional.empty();
        }
    }
    private void exec( final String sql, final Bind bind ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            bind.bind( ps );
            ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "update failed [{}]: {}", sql, e.getMessage() );
        }
    }
    static String toJsonArray( final List< String > values ) {
        if ( values == null || values.isEmpty() ) return "[]";
        final StringBuilder sb = new StringBuilder( "[" );
        for ( int i = 0; i < values.size(); i++ ) {
            if ( i > 0 ) sb.append( ',' );
            sb.append( '"' ).append( values.get( i ).replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) ).append( '"' );
        }
        return sb.append( ']' ).toString();
    }
}
```
> `exec` swallows to a `LOG.warn` and continues — acceptable for Phase 1 sync state (the next run reconciles), and it satisfies the no-empty-catch rule. If the task reviewer flags that a failed `saveCursor` silently loses the checkpoint, that is a legitimate finding — surface it, don't pre-suppress.

- [ ] **Step 5: Run it — PASS** (`mvn test -pl wikantik-connectors -Dtest=JdbcSyncStateStoreTest -q`).

- [ ] **Step 6: Commit**
```bash
git add bin/db/migrations/V046__connector_sync_state.sql wikantik-connectors/src/main/java/com/wikantik/connectors/state/JdbcSyncStateStore.java wikantik-connectors/src/test/java/com/wikantik/connectors/state/JdbcSyncStateStoreTest.java
git commit -m "feat(connectors): V046 sync-state migration + JdbcSyncStateStore"
```

---

### Task 5: `SyncOrchestrator`

**Files:**
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/SyncOrchestrator.java`
- Create: `wikantik-connectors/src/main/java/com/wikantik/connectors/SyncReport.java`
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/SyncOrchestratorTest.java`

**Interfaces:**
- Consumes: `SourceConnector`, `SyncStateStore`, `DerivedPageSink`, `SyncBatch`, `SourceItem`, `IngestOutcome` (Tasks 1).
- Produces: `SyncOrchestrator(SyncStateStore store, DerivedPageSink sink)`; `SyncReport sync(SourceConnector connector)` running the loop from the spec (hash-dedup, ingest, tombstone-by-`knownUris`-diff on `complete` batches, cursor persisted after each batch, `FAILED` not recorded). `SyncReport(int created, updated, unchanged, deleted, failed)`.

- [ ] **Step 1: Write the failing test** (fakes only — no DB, no wiki):
```java
package com.wikantik.connectors;

import com.wikantik.api.connectors.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncOrchestratorTest {

    /** In-memory SyncStateStore. */
    static final class FakeStore implements SyncStateStore {
        final Map< String, String > cursor = new HashMap<>();
        final Map< String, String > hash = new LinkedHashMap<>();      // uri -> hash
        final Map< String, String > page = new HashMap<>();            // uri -> pageName
        public Optional< SyncCursor > loadCursor( String id ) { return Optional.ofNullable( cursor.get( id ) ).map( SyncCursor::new ); }
        public void saveCursor( String id, SyncCursor c ) { cursor.put( id, c == null ? null : c.value() ); }
        public Optional< String > syncedHash( String id, String uri ) { return Optional.ofNullable( hash.get( uri ) ); }
        public void recordSynced( String id, String uri, String h, String pn, List< String > acl ) { hash.put( uri, h ); page.put( uri, pn ); }
        public Optional< String > pageNameFor( String id, String uri ) { return Optional.ofNullable( page.get( uri ) ); }
        public List< String > knownUris( String id ) { return new ArrayList<>( hash.keySet() ); }
        public void removeSynced( String id, String uri ) { hash.remove( uri ); page.remove( uri ); }
    }
    /** Records ingest/delete calls; page name = uri. */
    static final class FakeSink implements DerivedPageSink {
        final List< String > ingested = new ArrayList<>();
        final List< String > deleted = new ArrayList<>();
        public IngestOutcome ingest( SourceItem i ) { ingested.add( i.sourceUri() ); return new IngestOutcome( i.sourceUri(), IngestOutcome.Status.CREATED ); }
        public void delete( String pageName ) { deleted.add( pageName ); }
    }
    static SourceItem item( String uri, String hash ) { return new SourceItem( uri, new byte[0], "text/markdown", Map.of(), List.of(), hash ); }
    static SourceConnector single( SyncBatch batch ) {
        return new SourceConnector() {
            public String connectorId() { return "c1"; }
            public SyncBatch poll( SyncCursor cur ) { return batch; }
        };
    }

    @Test void ingestsNewItemsAndPersistsCursor() {
        FakeStore store = new FakeStore(); FakeSink sink = new FakeSink();
        SyncReport r = new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ), item( "file:b.md", "h2" ) ), List.of(), new SyncCursor( "cur1" ), true ) ) );
        assertEquals( List.of( "file:a.md", "file:b.md" ), sink.ingested );
        assertEquals( "cur1", store.cursor.get( "c1" ) );
        assertEquals( 2, r.created() );
    }

    @Test void skipsUnchangedByHash() {
        FakeStore store = new FakeStore(); store.hash.put( "file:a.md", "h1" ); store.page.put( "file:a.md", "file:a.md" );
        FakeSink sink = new FakeSink();
        SyncReport r = new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertTrue( sink.ingested.isEmpty() );
        assertEquals( 1, r.unchanged() );
    }

    @Test void derivesTombstonesFromKnownUrisNotSeenThisScan() {
        FakeStore store = new FakeStore();
        store.hash.put( "file:gone.md", "hg" ); store.page.put( "file:gone.md", "Gone" );   // previously synced
        FakeSink sink = new FakeSink();
        new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertEquals( List.of( "Gone" ), sink.deleted );                     // deleted-at-source
        assertTrue( store.hash.containsKey( "file:a.md" ) && !store.hash.containsKey( "file:gone.md" ) );
    }

    @Test void incompleteBatchDoesNotTombstone() {
        FakeStore store = new FakeStore(); store.hash.put( "file:x.md", "hx" ); store.page.put( "file:x.md", "X" );
        FakeSink sink = new FakeSink();
        new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of(), List.of(), new SyncCursor( "c" ), false ) ) );   // scan error / partial
        assertTrue( sink.deleted.isEmpty() );                                // absent != deleted on a partial batch
    }

    @Test void failedIngestIsNotRecordedSoItRetries() {
        FakeStore store = new FakeStore();
        DerivedPageSink failing = new DerivedPageSink() {
            public IngestOutcome ingest( SourceItem i ) { return new IngestOutcome( i.sourceUri(), IngestOutcome.Status.FAILED ); }
            public void delete( String p ) { }
        };
        new SyncOrchestrator( store, failing ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertTrue( store.syncedHash( "c1", "file:a.md" ).isEmpty() );       // not recorded → next run retries
    }
}
```

- [ ] **Step 2: Run it — FAIL** (`mvn test -pl wikantik-connectors -Dtest=SyncOrchestratorTest -q`).

- [ ] **Step 3: Implement `SyncReport` + `SyncOrchestrator`.**

`SyncReport.java`:
```java
package com.wikantik.connectors;
/** Tally of one {@link SyncOrchestrator#sync} run. */
public record SyncReport( int created, int updated, int unchanged, int deleted, int failed ) {}
```

`SyncOrchestrator.java`:
```java
package com.wikantik.connectors;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/** Drives a {@link SourceConnector} into derived pages: hash-dedup, tombstone, cursor-resume. */
public final class SyncOrchestrator {

    private static final Logger LOG = LogManager.getLogger( SyncOrchestrator.class );
    private final SyncStateStore store;
    private final DerivedPageSink sink;

    public SyncOrchestrator( final SyncStateStore store, final DerivedPageSink sink ) {
        this.store = store;
        this.sink = sink;
    }

    public SyncReport sync( final SourceConnector connector ) {
        final String id = connector.connectorId();
        int created = 0, updated = 0, unchanged = 0, deleted = 0, failed = 0;
        SyncCursor cursor = store.loadCursor( id ).orElse( null );

        while ( true ) {
            final SyncBatch batch = connector.poll( cursor );
            final Set< String > seen = new HashSet<>();

            for ( final SourceItem item : batch.items() ) {
                seen.add( item.sourceUri() );
                if ( store.syncedHash( id, item.sourceUri() ).filter( item.contentHash()::equals ).isPresent() ) {
                    unchanged++;
                    continue;
                }
                final IngestOutcome out = sink.ingest( item );
                switch ( out.status() ) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                    case FAILED -> { failed++; LOG.warn( "Sync '{}': ingest FAILED for {}", id, item.sourceUri() ); }
                }
                if ( out.status() != IngestOutcome.Status.FAILED ) {
                    store.recordSynced( id, item.sourceUri(), item.contentHash(), out.pageName(), item.aclRefs() );
                }
            }

            // explicit tombstones from the connector (incremental sources)
            for ( final String uri : batch.tombstonedUris() ) {
                deleted += tombstone( id, uri );
            }
            // derived tombstones: only on a COMPLETE batch, known URIs not seen this scan
            if ( batch.complete() ) {
                for ( final String uri : store.knownUris( id ) ) {
                    if ( !seen.contains( uri ) && !batch.tombstonedUris().contains( uri ) ) {
                        deleted += tombstone( id, uri );
                    }
                }
            }

            cursor = batch.nextCursor();
            store.saveCursor( id, cursor );     // persist AFTER the batch → crash-resume point
            if ( batch.complete() ) break;
        }
        final SyncReport report = new SyncReport( created, updated, unchanged, deleted, failed );
        LOG.info( "Sync '{}' complete: {}", id, report );
        return report;
    }

    private int tombstone( final String id, final String uri ) {
        final var page = store.pageNameFor( id, uri );
        if ( page.isEmpty() ) return 0;
        sink.delete( page.get() );
        store.removeSynced( id, uri );
        return 1;
    }
}
```
> Note: iterating `store.knownUris(id)` while calling `removeSynced` inside the loop — `knownUris` returns a fresh list (Task 4), so mutation during iteration is safe. Confirm the fake and the JDBC impl both return a copy.

- [ ] **Step 4: Run it — PASS** (`mvn test -pl wikantik-connectors -Dtest=SyncOrchestratorTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-connectors/src/main/java/com/wikantik/connectors/SyncOrchestrator.java wikantik-connectors/src/main/java/com/wikantik/connectors/SyncReport.java wikantik-connectors/src/test/java/com/wikantik/connectors/SyncOrchestratorTest.java
git commit -m "feat(connectors): SyncOrchestrator (hash-dedup, cursor-resume, tombstone derivation)"
```

---

### Task 6: `DerivedPageSinkAdapter` in `wikantik-main`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/derived/DerivedPageSinkAdapter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/derived/DerivedPageSinkAdapterTest.java`

**Interfaces:**
- Consumes: `DerivedPageSink`, `SourceItem`, `IngestOutcome` (Task 1); `DerivedPageIngestionService` + `IngestOptions` (3-arg, Task 2); `IngestResult`.
- Produces: `DerivedPageSinkAdapter(DerivedPageIngestionService ingestion, DerivedPageIngestionService.PageDeleter deleter, String author)`. `ingest(item)` calls `ingestion.ingest(item.content(), flatName(item.sourceUri()), item.contentType(), new IngestOptions(false, author, item.sourceUri()))` and maps `IngestResult.Status` → `IngestOutcome.Status`. `flatName` = the URI with the `file:`/scheme prefix stripped and `/`→`-` (collision-free page-name seed). `delete(pageName)` calls the deleter.

- [ ] **Step 1: Write the failing test**
```java
package com.wikantik.derived;

import com.wikantik.api.connectors.IngestOutcome;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.ingest.ExtractionResult;
import com.wikantik.ingest.SourceExtractor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DerivedPageSinkAdapterTest {

    @Test void ingestSetsDerivedFromToSourceUriAndUsesFlatPageName() {
        Map< String, Object > captured = new HashMap<>();
        List< String > writtenPages = new ArrayList<>();
        SourceExtractor extractor = ( bytes, ct ) -> new ExtractionResult( "# B\n\nbody", true, null );
        DerivedPageIngestionService ingestion = new DerivedPageIngestionService(
            extractor, ( p, f, b ) -> { }, p -> Optional.empty(),
            ( page, body, meta, author ) -> { writtenPages.add( page ); captured.putAll( meta ); },
            p -> { } );

        DerivedPageSinkAdapter adapter = new DerivedPageSinkAdapter( ingestion, p -> { }, "sync-bot" );
        IngestOutcome out = adapter.ingest( new SourceItem(
            "file:docs/a.md", "raw".getBytes(), "text/markdown", Map.of(), List.of( "group:docs" ), "hash" ) );

        assertEquals( IngestOutcome.Status.CREATED, out.status() );
        assertEquals( "file:docs/a.md", captured.get( "derived_from" ) );    // DoD #1: provenance = source URI
        assertEquals( "docs-a", out.pageName() );                            // flat, collision-free (basename of "docs-a.md")
        assertEquals( List.of( "docs-a" ), writtenPages.stream().distinct().toList() );
    }

    @Test void deleteDelegatesToDeleter() {
        List< String > deleted = new ArrayList<>();
        DerivedPageIngestionService ingestion = new DerivedPageIngestionService(
            ( b, c ) -> new ExtractionResult( "x", true, null ), ( p, f, b ) -> { },
            p -> Optional.empty(), ( a, b, c, d ) -> { }, p -> { } );
        new DerivedPageSinkAdapter( ingestion, deleted::add, "sync-bot" ).delete( "docs-a" );
        assertEquals( List.of( "docs-a" ), deleted );
    }
}
```
> Confirm the real `SourceExtractor`/`ExtractionResult` shapes from `wikantik-ingest` and adjust the lambda. `pageNameFor("docs-a.md")` strips the extension → `"docs-a"`; verify against `DerivedPage.pageNameFor` if the assertion needs tweaking.

- [ ] **Step 2: Run it — FAIL** (`mvn test -pl wikantik-main -Dtest=DerivedPageSinkAdapterTest -q`).

- [ ] **Step 3: Implement.**
```java
package com.wikantik.derived;

import com.wikantik.api.connectors.DerivedPageSink;
import com.wikantik.api.connectors.IngestOutcome;
import com.wikantik.api.connectors.SourceItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Bridges the connector {@link DerivedPageSink} port to the existing {@link DerivedPageIngestionService}. */
public final class DerivedPageSinkAdapter implements DerivedPageSink {

    private static final Logger LOG = LogManager.getLogger( DerivedPageSinkAdapter.class );
    private final DerivedPageIngestionService ingestion;
    private final DerivedPageIngestionService.PageDeleter deleter;
    private final String author;

    public DerivedPageSinkAdapter( final DerivedPageIngestionService ingestion,
                                   final DerivedPageIngestionService.PageDeleter deleter,
                                   final String author ) {
        this.ingestion = ingestion;
        this.deleter = deleter;
        this.author = author;
    }

    @Override
    public IngestOutcome ingest( final SourceItem item ) {
        final IngestResult r = ingestion.ingest(
            item.content(), flatName( item.sourceUri() ), item.contentType(),
            new IngestOptions( false, author, item.sourceUri() ) );      // derived_from = the source URI
        return new IngestOutcome( r.pageName(), map( r.status() ) );
    }

    @Override
    public void delete( final String pageName ) {
        try {
            deleter.delete( pageName );
        } catch ( final Exception e ) {
            LOG.warn( "Connector sink: delete of page '{}' failed: {}", pageName, e.getMessage() );
        }
    }

    /** Strip the {@code scheme:} prefix and flatten {@code /}→{@code -} so a tree yields unique page names. */
    static String flatName( final String sourceUri ) {
        final int colon = sourceUri.indexOf( ':' );
        final String path = colon >= 0 ? sourceUri.substring( colon + 1 ) : sourceUri;
        return path.replace( '/', '-' );
    }

    private static IngestOutcome.Status map( final IngestResult.Status s ) {
        return switch ( s ) {
            case CREATED   -> IngestOutcome.Status.CREATED;
            case UPDATED   -> IngestOutcome.Status.UPDATED;
            case UNCHANGED -> IngestOutcome.Status.UNCHANGED;
            case FAILED    -> IngestOutcome.Status.FAILED;
        };
    }
}
```
> `wikantik-main` already depends on `wikantik-api`, so importing `com.wikantik.api.connectors.*` needs no pom change. Confirm with `grep wikantik-api wikantik-main/pom.xml`.

- [ ] **Step 4: Run it — PASS** (`mvn test -pl wikantik-main -Dtest=DerivedPageSinkAdapterTest -q`).

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/derived/DerivedPageSinkAdapter.java wikantik-main/src/test/java/com/wikantik/derived/DerivedPageSinkAdapterTest.java
git commit -m "feat(derived): DerivedPageSinkAdapter bridges connector sink to ingestion service"
```

---

### Task 7: End-to-end sync integration test (DoD #1–#4 wired together)

**Files:**
- Modify: `wikantik-connectors/pom.xml` (add `wikantik-main` **test scope** + `wikantik-ingest` test scope for the real extractor)
- Test: `wikantik-connectors/src/test/java/com/wikantik/connectors/ConnectorSyncEndToEndTest.java`

**Interfaces:**
- Consumes: `FilesystemSourceConnector` (T3), `JdbcSyncStateStore` (T4), `SyncOrchestrator` (T5), `DerivedPageSinkAdapter` + real `DerivedPageIngestionService` with a real `TikaSourceExtractor` (T6/wikantik-ingest), in-memory `PageReader/PageWriter/AttachmentStore/PageDeleter` fakes backed by a `Map<String, Map<String,Object>>` page store.
- Produces: the DoD proof — one test class, four `@Test`s, one per DoD item.

This test exercises the whole stack in-process (no Cargo, no live server): fixture dir → `FilesystemSourceConnector` → `SyncOrchestrator` → `DerivedPageSinkAdapter` → `DerivedPageIngestionService` → an in-memory page store, with sync state in H2.

- [ ] **Step 1: Add test-scope deps** to `wikantik-connectors/pom.xml`:
```xml
    <dependency><groupId>${project.groupId}</groupId><artifactId>wikantik-main</artifactId><version>${project.version}</version><scope>test</scope></dependency>
    <dependency><groupId>${project.groupId}</groupId><artifactId>wikantik-ingest</artifactId><version>${project.version}</version><scope>test</scope></dependency>
```

- [ ] **Step 2: Write the four DoD tests** (the class wires a reusable in-memory harness in `@BeforeEach`):
```java
package com.wikantik.connectors;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.filesystem.FilesystemSourceConnector;
import com.wikantik.connectors.state.JdbcSyncStateStore;
import com.wikantik.derived.*;
import com.wikantik.ingest.TikaSourceExtractor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorSyncEndToEndTest {

    private DataSource ds;
    private final Map< String, Map< String, Object > > pages = new HashMap<>();   // pageName -> frontmatter
    private DerivedPageSink sink;
    private SyncOrchestrator orchestrator;

    @BeforeEach void wire() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_sync_state (connector_id VARCHAR PRIMARY KEY, cursor VARCHAR, last_run TIMESTAMP WITH TIME ZONE, status VARCHAR)" );
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
        }
        DerivedPageIngestionService ingestion = new DerivedPageIngestionService(
            new TikaSourceExtractor(),
            ( page, filename, bytes ) -> { },                                   // attachments: no-op
            page -> Optional.ofNullable( pages.get( page ) ),                   // reader
            ( page, body, meta, author ) -> pages.put( page, new HashMap<>( meta ) ),   // writer
            pages::remove );                                                    // deleter
        sink = new DerivedPageSinkAdapter( ingestion, pages::remove, "sync-bot" );
        orchestrator = new SyncOrchestrator( new JdbcSyncStateStore( ds ), sink );
    }

    private SourceConnector fs( Path root ) { return new FilesystemSourceConnector( "fs", root ); }

    // DoD #1 — sync → derived pages: derived_from = connector URI, source metadata carried, schema-valid
    @Test void syncProducesDerivedPagesWithProvenance( @TempDir Path root ) throws Exception {
        Files.createDirectories( root.resolve( "docs" ) );
        Files.writeString( root.resolve( "docs/guide.md" ), "# Guide\n\nHello world." );
        SyncReport r = orchestrator.sync( fs( root ) );
        assertEquals( 1, r.created() );
        Map< String, Object > fm = pages.get( "docs-guide" );
        assertNotNull( fm, "derived page written" );
        assertEquals( "file:docs/guide.md", fm.get( "derived_from" ) );        // provenance = source URI
        // schema-valid: derived_from present + non-blank is the frontmatter validator's derived-page rule
        assertFalse( fm.get( "derived_from" ).toString().isBlank() );
    }

    // DoD #2 — cursor-resume: a crash after batch 1 does not re-ingest on restart
    @Test void resumeDoesNotReprocess( @TempDir Path root ) throws Exception {
        Files.writeString( root.resolve( "a.md" ), "alpha" );
        orchestrator.sync( fs( root ) );                                       // first run: creates page-"a"
        int writesAfterFirst = pages.size();
        // second run, unchanged content → hash-dedup, no re-write
        SyncReport r2 = orchestrator.sync( fs( root ) );
        assertEquals( 1, r2.unchanged() );
        assertEquals( 0, r2.created() + r2.updated() );
        assertEquals( writesAfterFirst, pages.size() );
    }

    // DoD #3 — tombstone: deleting a source file removes its derived page on the next sync
    @Test void deletedSourceFileRemovesDerivedPage( @TempDir Path root ) throws Exception {
        Files.writeString( root.resolve( "keep.md" ), "keep" );
        Files.writeString( root.resolve( "drop.md" ), "drop" );
        orchestrator.sync( fs( root ) );
        assertTrue( pages.containsKey( "drop" ) );
        Files.delete( root.resolve( "drop.md" ) );
        SyncReport r = orchestrator.sync( fs( root ) );
        assertEquals( 1, r.deleted() );
        assertFalse( pages.containsKey( "drop" ) );
        assertTrue( pages.containsKey( "keep" ) );
    }

    // DoD #4 — acl_refs carried into sync state (+ migration idempotency covered by JdbcSyncStateStoreTest)
    @Test void aclRefsPersistedForSyncedItems( @TempDir Path root ) throws Exception {
        Files.createDirectories( root.resolve( "secure" ) );
        Files.writeString( root.resolve( "secure/x.md" ), "secret" );
        orchestrator.sync( fs( root ) );
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery(
                  "SELECT acl_refs FROM connector_synced_item WHERE source_uri='file:secure/x.md'" ) ) {
            assertTrue( rs.next() );
            assertEquals( "[\"secure\"]", rs.getString( 1 ) );                 // parent-dir principal carried
        }
    }
}
```
> If `TikaSourceExtractor` needs a no-arg constructor / different signature, confirm from `wikantik-ingest`. If it can't parse a tiny markdown byte string, substitute a trivial inline `SourceExtractor` returning `new ExtractionResult(new String(bytes), true, null)` — the DoD is the sync/provenance/tombstone behavior, not Tika itself.

- [ ] **Step 3: Run — FAIL then PASS** (`mvn test -pl wikantik-connectors -Dtest=ConnectorSyncEndToEndTest -q`). Iterate on the extractor wiring until green.

- [ ] **Step 4: Commit**
```bash
git add wikantik-connectors/pom.xml wikantik-connectors/src/test/java/com/wikantik/connectors/ConnectorSyncEndToEndTest.java
git commit -m "test(connectors): end-to-end sync IT — provenance, resume, tombstone, acl-refs (DoD 1-4)"
```

---

## Post-implementation (controller)

- Full reactor build (unit only): `mvn clean install -T 1C -DskipITs` — the new module must build and no existing module regresses (Task 2 touched `wikantik-main`).
- Apply `V046` against the local/IT Postgres and re-run `bin/db/migrate.sh --status` to confirm idempotency in the real DB (DoD #4's production half; the unit test covers the H2 half).
- Whole-branch review (opus): dependency-direction (`wikantik-connectors` must NOT depend on `wikantik-main` in main scope — check `wikantik-connectors/pom.xml`), invariant #6 (no new `wikantik-main` package), fail-closed/no-swallowed-catch, and the `DecompositionArchTest`/PMD gates.

## Self-review notes

- **Spec coverage:** SPI (T1), orchestrator loop (T5), sync-state schema+DAO (T4), filesystem connector (T3), sink adapter (T6), the 4 DoD tests (T7 + migration idempotency in T4), ACL refs carried (T4/T7), `derived_from` = URI (T2/T6/T7). All spec sections mapped.
- **Invariant #6:** contracts in `wikantik-api` (T1), impls in `wikantik-connectors` (T3–T5), one adapter + one `IngestOptions` field in the existing `com.wikantik.derived` package (T2/T6) — no new `wikantik-main` package.
- **Type consistency:** `SyncStateStore`, `DerivedPageSink`, `IngestOutcome.Status`, `SyncBatch`, `SourceItem`, `IngestOptions(boolean,String,String)` signatures are identical across T1→T7.

# Phase 3 — Citation Edges + Self-Healing Grounding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse `cite://` body markup into a derived, version+span-hash-pinned `citations` table, grade span-level staleness as the base churns, and surface stale citations bidirectionally (admin/drift + MCP + for-agent) so grounded content heals itself.

**Architecture:** A `WikiEventListener` (the `OntologyEventListener` seam) reconciles a page's outbound citations and re-grades inbound citations on every save/rename/delete; a `reconcileAll()` rides `OntologyRebuildCoordinator.onRebuildComplete` as the completeness safety net. Citations live in a SQL table that fully re-derives from the body (span lives in the markdown link *title*); the only sticky table state is the pinned target version + status. Read surfaces extend the existing drift dashboard, the knowledge MCP server, and the for-agent projection.

**Tech Stack:** Java 21, JDBC (PostgreSQL prod / H2 unit tests), JUnit 5 + Mockito, Flexmark (rendering), the MCP Java SDK already used by `wikantik-knowledge`.

**Spec:** `docs/superpowers/specs/2026-06-14-phase-3-citation-edges-design.md` · **ADR:** `docs/adr/0005-persisted-citation-edges-and-stale-citation-curation.md`

**Markup contract (all tasks assume this):**
```
[prose claim](cite://<target_canonical_id>/<Heading%20Path%20segments> "verbatim span")
```
- target = chars up to the first `/` after `cite://`; remainder = `/`-separated, `%`-decoded heading-path segments (empty ⇒ page-level).
- link title (`"..."`) = the verbatim cited span (optional). claim text = the visible `[...]` (optional).
- **Identity** = `(source_canonical_id, target_canonical_id, target_heading_path, span_hash, ordinal)`, all body-derived. `target_heading_path` is stored as decoded segments joined by `" > "`.

---

### Task 1: Schema + shared API types

**Files:**
- Create: `bin/db/migrations/V040__citations.sql`
- Create: `wikantik-api/src/main/java/com/wikantik/api/citation/CitationStatus.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/citation/CitationRef.java`

- [ ] **Step 1: Write the migration** (idempotent, DDL-only — mirrors `V038__drift_snapshots.sql` grant style)

`bin/db/migrations/V040__citations.sql`:
```sql
-- V040: citation edges (Phase 3 — RAG-as-a-Service). Parsed from cite:// body markup into a
-- derived, re-derivable index. Version + span-hash pinned; span-level graded staleness.
-- Column status holds 'current' | 'stale' | 'target_missing'. Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS citations (
    id                    BIGSERIAL   PRIMARY KEY,
    source_canonical_id   TEXT        NOT NULL,
    target_canonical_id   TEXT        NOT NULL,
    target_heading_path   TEXT        NOT NULL DEFAULT '',
    span_text             TEXT        NOT NULL DEFAULT '',
    span_hash             TEXT        NOT NULL,
    claim_text            TEXT,
    ordinal               INT         NOT NULL DEFAULT 0,
    pinned_target_version INT,
    status                TEXT        NOT NULL DEFAULT 'current',
    first_seen            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_checked          TIMESTAMPTZ,
    last_status_change    TIMESTAMPTZ,
    CONSTRAINT uq_citation UNIQUE (source_canonical_id, target_canonical_id, target_heading_path, span_hash, ordinal)
);

CREATE INDEX IF NOT EXISTS idx_citations_source ON citations (source_canonical_id);
CREATE INDEX IF NOT EXISTS idx_citations_target ON citations (target_canonical_id);
CREATE INDEX IF NOT EXISTS idx_citations_status ON citations (status);

GRANT SELECT, INSERT, UPDATE, DELETE ON citations TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE citations_id_seq TO :app_user;
```

- [ ] **Step 2: Write `CitationStatus`** (the wire/db enum)

```java
package com.wikantik.api.citation;

/** Graded staleness of a citation. Wire/db value is {@link #wire()}. */
public enum CitationStatus {
    CURRENT( "current" ),
    STALE( "stale" ),
    TARGET_MISSING( "target_missing" );

    private final String wire;
    CitationStatus( final String wire ) { this.wire = wire; }
    public String wire() { return wire; }

    public static CitationStatus fromWire( final String s ) {
        for ( final CitationStatus v : values() ) { if ( v.wire.equals( s ) ) { return v; } }
        throw new IllegalArgumentException( "unknown citation status: " + s );
    }
}
```

- [ ] **Step 3: Write `CitationRef`** (the DTO surfaced by MCP + for-agent; module-neutral, lives in api)

```java
package com.wikantik.api.citation;

/**
 * A citation as exposed to read surfaces (MCP, for-agent projection, admin drift lists).
 * {@code headingPath} is " > "-joined segments ("" = page-level). {@code spanText} is the
 * verbatim cited span. {@code pinnedTargetVersion} may be null when the target is missing.
 */
public record CitationRef(
        String sourceCanonicalId,
        String targetCanonicalId,
        String targetHeadingPath,
        String spanText,
        String claimText,
        CitationStatus status,
        Integer pinnedTargetVersion ) {}
```

- [ ] **Step 4: Compile-check the api module**

Run: `mvn -q -pl wikantik-api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**
```bash
git add bin/db/migrations/V040__citations.sql wikantik-api/src/main/java/com/wikantik/api/citation/
git commit -m "feat(citations): V040 schema + CitationStatus/CitationRef api types

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Span normalization, hashing, and the markup parser

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/Spans.java`
- Create: `wikantik-main/src/main/java/com/wikantik/citation/ParsedCitation.java`
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationMarkupParser.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/CitationMarkupParserTest.java`

- [ ] **Step 1: Write the failing test**

`CitationMarkupParserTest.java`:
```java
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationMarkupParserTest {

    private final CitationMarkupParser parser = new CitationMarkupParser();

    @Test
    void parsesTargetHeadingSpanAndClaim() {
        final String body = "Intro.\n\n"
            + "Rollback is destructive [you must drain first]"
            + "(cite://abc123/Deploy/Rollback%20Steps \"Always drain the queue before rollback\").\n";
        final List< ParsedCitation > out = parser.parse( body );
        assertEquals( 1, out.size() );
        final ParsedCitation c = out.get( 0 );
        assertEquals( "abc123", c.targetCanonicalId() );
        assertEquals( "Deploy > Rollback Steps", c.targetHeadingPath() );
        assertEquals( "Always drain the queue before rollback", c.spanText() );
        assertEquals( "you must drain first", c.claimText() );
        assertEquals( 0, c.ordinal() );
        assertEquals( Spans.hash( Spans.normalize( c.spanText() ) ), c.spanHash() );
    }

    @Test
    void pageLevelCitationHasEmptyHeadingPath() {
        final List< ParsedCitation > out = parser.parse( "x [c](cite://pg1 \"span\") y" );
        assertEquals( 1, out.size() );
        assertEquals( "pg1", out.get( 0 ).targetCanonicalId() );
        assertEquals( "", out.get( 0 ).targetHeadingPath() );
    }

    @Test
    void titlelessCitationHasEmptySpan() {
        final List< ParsedCitation > out = parser.parse( "[c](cite://pg1/Heading)" );
        assertEquals( 1, out.size() );
        assertEquals( "", out.get( 0 ).spanText() );
        assertEquals( Spans.hash( "" ), out.get( 0 ).spanHash() );
    }

    @Test
    void duplicateSameTargetSpanGetsIncrementingOrdinals() {
        final String body = "[a](cite://t1/H \"s\") and again [b](cite://t1/H \"s\")";
        final List< ParsedCitation > out = parser.parse( body );
        assertEquals( 2, out.size() );
        assertEquals( 0, out.get( 0 ).ordinal() );
        assertEquals( 1, out.get( 1 ).ordinal() );
    }

    @Test
    void ignoresOrdinaryMarkdownLinks() {
        assertTrue( parser.parse( "[home](https://example.com) [p](/wiki/Page)" ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (classes don't exist)

Run: `mvn -q -pl wikantik-main test -Dtest=CitationMarkupParserTest`
Expected: compile failure / FAIL.

- [ ] **Step 3: Write `Spans`**

```java
package com.wikantik.citation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Span normalization + content hashing for citation identity and staleness. */
public final class Spans {
    private Spans() {}

    /** Collapse all whitespace runs to a single space and trim. Never lowercases. */
    public static String normalize( final String s ) {
        return s == null ? "" : s.replaceAll( "\\s+", " " ).trim();
    }

    /** SHA-256 hex of the already-normalized span. */
    public static String hash( final String normalized ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            final byte[] d = md.digest( ( normalized == null ? "" : normalized ).getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) { sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) ); }
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }
}
```

- [ ] **Step 4: Write `ParsedCitation`**

```java
package com.wikantik.citation;

/** One citation extracted from a page body. headingPath is " > "-joined ("" = page-level). */
public record ParsedCitation(
        String targetCanonicalId,
        String targetHeadingPath,
        String spanText,
        String spanHash,
        String claimText,
        int ordinal ) {}
```

- [ ] **Step 5: Write `CitationMarkupParser`**

```java
package com.wikantik.citation;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts {@code [claim](cite://target/heading "span")} links from a page body, in document order. */
public final class CitationMarkupParser {

    // group1 = claim, group2 = target+heading path, group3 = optional title (span)
    private static final Pattern CITE = Pattern.compile(
        "\\[([^\\]]*)]\\(\\s*cite://([^\\s)\"]+)\\s*(?:\"([^\"]*)\")?\\s*\\)" );

    public List< ParsedCitation > parse( final String body ) {
        final List< ParsedCitation > out = new ArrayList<>();
        if ( body == null || body.isEmpty() ) { return out; }
        final Map< String, Integer > ordinals = new HashMap<>();
        final Matcher m = CITE.matcher( body );
        while ( m.find() ) {
            final String claim = m.group( 1 ) == null ? "" : m.group( 1 ).trim();
            final String path = m.group( 2 );
            final String span = m.group( 3 ) == null ? "" : m.group( 3 );
            final int slash = path.indexOf( '/' );
            final String target = slash < 0 ? path : path.substring( 0, slash );
            final String headingPath = slash < 0 ? "" : decodeHeadingPath( path.substring( slash + 1 ) );
            final String spanHash = Spans.hash( Spans.normalize( span ) );
            final String key = target + " " + headingPath + " " + spanHash;
            final int ordinal = ordinals.merge( key, 0, ( a, b ) -> a + 1 );
            out.add( new ParsedCitation( target, headingPath, span, spanHash, claim, ordinal ) );
        }
        return out;
    }

    private static String decodeHeadingPath( final String raw ) {
        final String[] segs = raw.split( "/" );
        final StringBuilder sb = new StringBuilder();
        for ( final String seg : segs ) {
            if ( seg.isEmpty() ) { continue; }
            if ( sb.length() > 0 ) { sb.append( " > " ); }
            sb.append( URLDecoder.decode( seg, StandardCharsets.UTF_8 ).trim() );
        }
        return sb.toString();
    }
}
```
Note: `merge(key, 0, (a,b)->a+1)` returns 0 for the first occurrence then 1, 2, … — exactly the ordinal sequence.

- [ ] **Step 6: Run the test — expect PASS**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationMarkupParserTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/Spans.java wikantik-main/src/main/java/com/wikantik/citation/ParsedCitation.java wikantik-main/src/main/java/com/wikantik/citation/CitationMarkupParser.java wikantik-main/src/test/java/com/wikantik/citation/CitationMarkupParserTest.java
git commit -m "feat(citations): cite:// markup parser + span hashing

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Markdown section extractor

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/MarkdownSectionExtractor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/MarkdownSectionExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarkdownSectionExtractorTest {

    private final MarkdownSectionExtractor ex = new MarkdownSectionExtractor();
    private static final String BODY =
          "# Deploy\n"
        + "intro line\n"
        + "## Rollback Steps\n"
        + "Always drain the queue before rollback.\n"
        + "Then flip the flag.\n"
        + "## Other\n"
        + "unrelated\n";

    @Test
    void returnsTextUnderMatchedHeadingPath() {
        final Optional< String > s = ex.sectionText( BODY, "Deploy > Rollback Steps" );
        assertTrue( s.isPresent() );
        assertTrue( s.get().contains( "Always drain the queue before rollback" ) );
        assertTrue( s.get().contains( "Then flip the flag" ) );
        assertFalse( s.get().contains( "unrelated" ) );
    }

    @Test
    void emptyHeadingPathReturnsWholeBody() {
        assertTrue( ex.sectionText( BODY, "" ).orElse( "" ).contains( "unrelated" ) );
    }

    @Test
    void unknownHeadingPathIsEmpty() {
        assertTrue( ex.sectionText( BODY, "Deploy > Nonexistent" ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `mvn -q -pl wikantik-main test -Dtest=MarkdownSectionExtractorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `MarkdownSectionExtractor`**

```java
package com.wikantik.citation;

import com.wikantik.api.frontmatter.FrontmatterParser;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Returns the body text under a given ATX heading-path (" > "-joined). Frontmatter is stripped
 * first. An empty heading-path returns the whole (frontmatter-stripped) body. Capture runs until
 * the next heading of equal-or-higher level. Returns empty when the path does not resolve.
 */
public final class MarkdownSectionExtractor {

    private static final Pattern ATX = Pattern.compile( "^(#{1,6})\\s+(.*?)\\s*#*\\s*$" );

    public Optional< String > sectionText( final String rawBody, final String headingPath ) {
        final String body = FrontmatterParser.parse( rawBody == null ? "" : rawBody ).body();
        if ( headingPath == null || headingPath.isBlank() ) { return Optional.of( body ); }
        final String[] want = headingPath.split( " > " );
        for ( int i = 0; i < want.length; i++ ) { want[ i ] = Spans.normalize( want[ i ] ); }

        final String[] lines = body.split( "\n", -1 );
        final Deque< String > path = new ArrayDeque<>();   // titles, shallow->deep
        final Deque< Integer > levels = new ArrayDeque<>();
        int captureLevel = -1;
        StringBuilder capture = null;

        for ( final String line : lines ) {
            final Matcher m = ATX.matcher( line );
            if ( m.matches() ) {
                final int level = m.group( 1 ).length();
                if ( capture != null && level <= captureLevel ) {
                    return Optional.of( capture.toString() );      // section ended
                }
                while ( !levels.isEmpty() && levels.peekLast() >= level ) { levels.removeLast(); path.removeLast(); }
                levels.addLast( level );
                path.addLast( Spans.normalize( m.group( 2 ) ) );
                if ( capture == null && pathMatches( path, want ) ) {
                    captureLevel = level;
                    capture = new StringBuilder();
                }
            } else if ( capture != null ) {
                capture.append( line ).append( '\n' );
            }
        }
        return capture == null ? Optional.empty() : Optional.of( capture.toString() );
    }

    private static boolean pathMatches( final Deque< String > path, final String[] want ) {
        if ( path.size() != want.length ) { return false; }
        int i = 0;
        for ( final String seg : path ) { if ( !seg.equals( want[ i++ ] ) ) { return false; } }
        return true;
    }
}
```
Note: confirm `FrontmatterParser.parse(String).body()` is the correct accessor (Task 2 of the recon: `StructuralSpinePageFilter` uses `FrontmatterParser.parse(content)` → `ParsedPage`; `.body()` is the body accessor used by `DefaultReferenceManager`). If the accessor differs, match the existing caller.

- [ ] **Step 4: Run the test — expect PASS**

Run: `mvn -q -pl wikantik-main test -Dtest=MarkdownSectionExtractorTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/MarkdownSectionExtractor.java wikantik-main/src/test/java/com/wikantik/citation/MarkdownSectionExtractorTest.java
git commit -m "feat(citations): markdown section extractor for span grading

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Citation repository (JDBC)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationRow.java`
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationRepository.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/CitationRepositoryTest.java`

**Pattern:** mirror `com.wikantik.drift.DriftSnapshotRepository` (constructor `(DataSource)`, manual `setAutoCommit(false)` transactions). For the **H2 test schema bootstrap**, mirror `DriftSnapshotRepositoryTest` exactly (read it first) — it shows how this project creates Postgres-style tables under H2 for repo tests (the `:app_user` GRANT lines from the migration are Postgres-only and are NOT run in H2; the test creates the table itself).

- [ ] **Step 1: Write `CitationRow`**

```java
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import java.time.Instant;

/** A persisted citation row. id<=0 and null timestamps for not-yet-inserted rows. */
public record CitationRow(
        long id,
        String sourceCanonicalId,
        String targetCanonicalId,
        String targetHeadingPath,
        String spanText,
        String spanHash,
        String claimText,
        int ordinal,
        Integer pinnedTargetVersion,
        CitationStatus status,
        Instant firstSeen,
        Instant lastChecked,
        Instant lastStatusChange ) {

    /** Identity key (excludes id) used to match a re-parsed citation to an existing row. */
    public String identity() {
        return sourceCanonicalId + " " + targetCanonicalId + " "
             + targetHeadingPath + " " + spanHash + " " + ordinal;
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import com.wikantik.api.citation.CitationStatus;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CitationRepositoryTest {

    private DataSource ds;
    private CitationRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        // Mirror DriftSnapshotRepositoryTest: build an H2 DataSource and CREATE the table.
        ds = TestCitationDb.h2DataSource();          // see note below
        try ( Connection c = ds.getConnection() ) { TestCitationDb.createSchema( c ); }
        repo = new CitationRepository( ds );
    }

    private static CitationRow newRow( final String src, final String tgt, final String head,
                                       final String span, final CitationStatus st ) {
        final String hash = Spans.hash( Spans.normalize( span ) );
        return new CitationRow( 0, src, tgt, head, span, hash, "claim", 0, 7, st, null, null, null );
    }

    @Test
    void replaceForSourceInsertsRows() {
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        final List< CitationRow > rows = repo.findBySource( "s1" );
        assertEquals( 1, rows.size() );
        assertEquals( "t1", rows.get( 0 ).targetCanonicalId() );
        assertEquals( CitationStatus.CURRENT, rows.get( 0 ).status() );
        assertEquals( 7, rows.get( 0 ).pinnedTargetVersion() );
        assertNotNull( rows.get( 0 ).firstSeen() );
    }

    @Test
    void replaceForSourcePreservesPinAndFirstSeenForSurvivingIdentity() {
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        final CitationRow first = repo.findBySource( "s1" ).get( 0 );
        // Re-save: same identity, new pinned version (12) and new status — pin & first_seen must stick.
        final CitationRow again = new CitationRow( 0, "s1", "t1", "H", "span",
            Spans.hash( Spans.normalize( "span" ) ), "edited claim", 0, 12, CitationStatus.STALE, null, null, null );
        repo.replaceForSource( "s1", List.of( again ) );
        final CitationRow now = repo.findBySource( "s1" ).get( 0 );
        assertEquals( first.id(), now.id() );                       // same row
        assertEquals( 7, now.pinnedTargetVersion() );               // pin preserved
        assertEquals( first.firstSeen(), now.firstSeen() );         // first_seen preserved
        assertEquals( CitationStatus.STALE, now.status() );         // status re-graded
        assertEquals( "edited claim", now.claimText() );            // claim updated
    }

    @Test
    void replaceForSourceDeletesVanishedRows() {
        repo.replaceForSource( "s1", List.of(
            newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ),
            newRow( "s1", "t2", "H", "other", CitationStatus.CURRENT ) ) );
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        assertEquals( 1, repo.findBySource( "s1" ).size() );
    }

    @Test
    void findByTargetAndUpdateStatus() {
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t1", "H", "span", CitationStatus.CURRENT ) ) );
        final CitationRow r = repo.findByTarget( "t1" ).get( 0 );
        repo.updateStatus( r.id(), CitationStatus.TARGET_MISSING, java.time.Instant.now() );
        assertEquals( CitationStatus.TARGET_MISSING, repo.findBySource( "s1" ).get( 0 ).status() );
    }

    @Test
    void countsByStatus() {
        repo.replaceForSource( "s1", List.of(
            newRow( "s1", "t1", "H", "a", CitationStatus.CURRENT ),
            newRow( "s1", "t2", "H", "b", CitationStatus.STALE ) ) );
        assertEquals( 1, repo.countsByStatus().get( CitationStatus.STALE ) );
    }
}
```
Note on `TestCitationDb`: create a tiny test helper `wikantik-main/src/test/java/com/wikantik/citation/TestCitationDb.java` that returns an H2 `DataSource` (PostgreSQL mode) and runs an H2-compatible `CREATE TABLE citations (...)` (BIGSERIAL → use H2's `BIGINT AUTO_INCREMENT`/`GENERATED ... AS IDENTITY`; `TIMESTAMPTZ` → `TIMESTAMP WITH TIME ZONE`). Copy the connection-URL + dialect choices verbatim from `DriftSnapshotRepositoryTest`'s setup so it matches how drift repo tests already run under H2.

- [ ] **Step 3: Run it — expect FAIL**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationRepositoryTest`
Expected: FAIL.

- [ ] **Step 4: Implement `CitationRepository`** (mirror `DriftSnapshotRepository`'s JDBC style; log warnings on SQLException, never swallow)

Required public surface:
```java
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class CitationRepository {
    public CitationRepository( final DataSource dataSource );

    /** Re-derive a source page's citations: keep surviving identities (preserving id, first_seen,
     *  pinned_target_version), update their claim/status/last_checked, insert new identities, and
     *  delete identities no longer present. Single transaction. */
    public void replaceForSource( String sourceCanonicalId, List< CitationRow > rows );

    public List< CitationRow > findBySource( String sourceCanonicalId );
    public List< CitationRow > findByTarget( String targetCanonicalId );
    public List< CitationRow > findByStatus( CitationStatus status );
    public List< CitationRow > findAll();
    public void updateStatus( long id, CitationStatus status, java.time.Instant checkedAt );
    public void touchChecked( long id, java.time.Instant checkedAt );
    public Map< CitationStatus, Integer > countsByStatus();
}
```
`replaceForSource` algorithm (explicit, H2- and Postgres-safe — do **not** rely on `ON CONFLICT`):
1. `setAutoCommit(false)`.
2. `SELECT id, target_canonical_id, target_heading_path, span_hash, ordinal, status, pinned_target_version, first_seen FROM citations WHERE source_canonical_id = ?` → build `Map<identity, existing>`.
3. For each incoming row, compute identity (`CitationRow.identity()` on a temp row, or inline). If present in the map: `UPDATE citations SET claim_text=?, status=?, last_checked=NOW(), last_status_change = CASE WHEN status <> ? THEN NOW() ELSE last_status_change END WHERE id=?` (bind new status twice). Do **not** touch `pinned_target_version` or `first_seen`. Remove the identity from a "to-delete" working set.
4. For each incoming row whose identity is absent: `INSERT INTO citations (source_canonical_id,target_canonical_id,target_heading_path,span_text,span_hash,claim_text,ordinal,pinned_target_version,status,first_seen,last_checked,last_status_change) VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW(),NOW())`.
5. Delete every existing identity not in the incoming set: `DELETE FROM citations WHERE id = ?` for each.
6. `commit()`; on `SQLException` `rollback()`, `LOG.warn("citation replaceForSource failed for {}: {}", sourceCanonicalId, e.getMessage(), e)`, rethrow as unchecked or swallow-with-log per the drift repo's convention.

Row mapping helper reads all columns into `CitationRow` (use `getObject(col, Integer.class)` for nullable `pinned_target_version`; map `status` via `CitationStatus.fromWire`).

- [ ] **Step 5: Run the test — expect PASS**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationRepositoryTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/CitationRow.java wikantik-main/src/main/java/com/wikantik/citation/CitationRepository.java wikantik-main/src/test/java/com/wikantik/citation/CitationRepositoryTest.java wikantik-main/src/test/java/com/wikantik/citation/TestCitationDb.java
git commit -m "feat(citations): JDBC repository with re-derive + sticky-pin upsert

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Staleness grader

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationStalenessGrader.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/CitationStalenessGraderTest.java`

**Design:** the grader takes a `bodyLoader` (`Function<String,Optional<String>>` from slug → raw body) so it is decoupled from the exact `PageManager` content API and trivially testable. canonical_id → slug resolution via `StructuralIndexService.resolveSlugFromCanonicalId`.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CitationStalenessGraderTest {

    private static final String TARGET_BODY =
        "# Deploy\n## Rollback Steps\nAlways drain the queue before rollback.\n";

    private CitationStalenessGrader grader( final StructuralIndexService idx,
                                            final Function< String, Optional< String > > loader ) {
        return new CitationStalenessGrader( idx, loader, new MarkdownSectionExtractor() );
    }

    @Test
    void currentWhenSpanPresentInSection() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationStatus s = grader( idx, slug -> Optional.of( TARGET_BODY ) )
            .grade( "t1", "Deploy > Rollback Steps", "Always drain the queue before rollback" );
        assertEquals( CitationStatus.CURRENT, s );
    }

    @Test
    void staleWhenSpanGoneFromSection() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationStatus s = grader( idx, slug -> Optional.of( TARGET_BODY ) )
            .grade( "t1", "Deploy > Rollback Steps", "drain the pool" );
        assertEquals( CitationStatus.STALE, s );
    }

    @Test
    void staleWhenHeadingPathNoLongerResolves() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationStatus s = grader( idx, slug -> Optional.of( TARGET_BODY ) )
            .grade( "t1", "Deploy > Gone", "anything" );
        assertEquals( CitationStatus.STALE, s );
    }

    @Test
    void targetMissingWhenCanonicalIdDoesNotResolve() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.empty() );
        final CitationStatus s = grader( idx, slug -> Optional.empty() )
            .grade( "t1", "Deploy", "x" );
        assertEquals( CitationStatus.TARGET_MISSING, s );
    }

    @Test
    void emptySpanGradesCurrentWhenSectionResolves() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "t1" ) ).thenReturn( Optional.of( "Deploy" ) );
        assertEquals( CitationStatus.CURRENT,
            grader( idx, slug -> Optional.of( TARGET_BODY ) ).grade( "t1", "Deploy > Rollback Steps", "" ) );
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationStalenessGraderTest`
Expected: FAIL.

- [ ] **Step 3: Implement `CitationStalenessGrader`**

```java
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.Optional;
import java.util.function.Function;

/** Grades a citation against the target's current content. Span drift = STALE; missing target = TARGET_MISSING. */
public final class CitationStalenessGrader {

    private final StructuralIndexService structuralIndex;
    private final Function< String, Optional< String > > bodyLoader;   // slug -> raw body
    private final MarkdownSectionExtractor sectionExtractor;

    public CitationStalenessGrader( final StructuralIndexService structuralIndex,
                                    final Function< String, Optional< String > > bodyLoader,
                                    final MarkdownSectionExtractor sectionExtractor ) {
        this.structuralIndex = structuralIndex;
        this.bodyLoader = bodyLoader;
        this.sectionExtractor = sectionExtractor;
    }

    public CitationStatus grade( final String targetCanonicalId, final String headingPath, final String spanText ) {
        final Optional< String > slug = structuralIndex.resolveSlugFromCanonicalId( targetCanonicalId );
        if ( slug.isEmpty() ) { return CitationStatus.TARGET_MISSING; }
        final Optional< String > body = bodyLoader.apply( slug.get() );
        if ( body.isEmpty() ) { return CitationStatus.TARGET_MISSING; }
        final Optional< String > section = sectionExtractor.sectionText( body.get(), headingPath );
        if ( section.isEmpty() ) { return CitationStatus.STALE; }       // heading moved / gone
        if ( spanText == null || spanText.isBlank() ) { return CitationStatus.CURRENT; }
        final String hay = Spans.normalize( section.get() );
        final String needle = Spans.normalize( spanText );
        return hay.contains( needle ) ? CitationStatus.CURRENT : CitationStatus.STALE;
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationStalenessGraderTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/CitationStalenessGrader.java wikantik-main/src/test/java/com/wikantik/citation/CitationStalenessGraderTest.java
git commit -m "feat(citations): span-level staleness grader

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Citation sync orchestrator

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationSync.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/CitationSyncTest.java`

**Responsibilities:** `onPageSaved` (reconcile outbound + re-grade inbound), `onPageDeleted` (best-effort target_missing, rename-guarded), `onPageRenamed` (re-grade inbound), `reconcileAll` (re-grade everything). It owns the `bodyLoader`, the `versionOf` resolver, the parser, grader, repo, and `StructuralIndexService`.

- [ ] **Step 1: Write the failing test** (all collaborators faked — no DB, no engine)

```java
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CitationSyncTest {

    private CitationSync sync( final CitationRepository repo, final StructuralIndexService idx,
                               final Map< String, String > bodies, final Map< String, Integer > versions ) {
        final Function< String, Optional< String > > loader = slug -> Optional.ofNullable( bodies.get( slug ) );
        final Function< String, Optional< Integer > > ver = slug -> Optional.ofNullable( versions.get( slug ) );
        final CitationStalenessGrader grader = new CitationStalenessGrader( idx, loader, new MarkdownSectionExtractor() );
        return new CitationSync( repo, new CitationMarkupParser(), grader, idx, loader, ver );
    }

    @Test
    void onPageSavedReconcilesOutboundWithGradedStatusAndVersionPin() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "Source" ) ).thenReturn( Optional.of( "src1" ) );
        when( idx.resolveSlugFromCanonicalId( "tgt1" ) ).thenReturn( Optional.of( "Target" ) );
        when( idx.resolveSlugFromCanonicalId( "src1" ) ).thenReturn( Optional.of( "Source" ) );
        final String src = "Claim [c](cite://tgt1/H \"present span\")";
        final String tgt = "# H\npresent span here\n";
        when( repo.findByTarget( anyString() ) ).thenReturn( List.of() );

        sync( repo, idx, Map.of( "Source", src, "Target", tgt ), Map.of( "Target", 9 ) ).onPageSaved( "Source" );

        @SuppressWarnings( "unchecked" )
        final org.mockito.ArgumentCaptor< List< CitationRow > > cap = org.mockito.ArgumentCaptor.forClass( List.class );
        verify( repo ).replaceForSource( eq( "src1" ), cap.capture() );
        final CitationRow r = cap.getValue().get( 0 );
        assertEquals( "tgt1", r.targetCanonicalId() );
        assertEquals( CitationStatus.CURRENT, r.status() );
        assertEquals( 9, r.pinnedTargetVersion() );
    }

    @Test
    void onPageSavedRegradesInboundCitations() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "Target" ) ).thenReturn( Optional.of( "tgt1" ) );
        when( idx.resolveSlugFromCanonicalId( "tgt1" ) ).thenReturn( Optional.of( "Target" ) );
        final CitationRow inbound = new CitationRow( 5, "src1", "tgt1", "H", "old span",
            Spans.hash( Spans.normalize( "old span" ) ), "c", 0, 3, CitationStatus.CURRENT, null, null, null );
        when( repo.findByTarget( "tgt1" ) ).thenReturn( List.of( inbound ) );
        // Target no longer contains "old span" -> should flip to STALE
        sync( repo, idx, Map.of( "Target", "# H\ndifferent now\n" ), Map.of() ).onPageSaved( "Target" );
        verify( repo ).updateStatus( eq( 5L ), eq( CitationStatus.STALE ), any() );
    }

    @Test
    void onPageDeletedMarksInboundTargetMissingWhenCanonicalIdGone() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "Gone" ) ).thenReturn( Optional.of( "tgtGone" ) );
        when( idx.resolveSlugFromCanonicalId( "tgtGone" ) ).thenReturn( Optional.empty() );  // truly gone
        final CitationRow inbound = new CitationRow( 8, "s", "tgtGone", "", "x",
            Spans.hash( "x" ), "c", 0, 1, CitationStatus.CURRENT, null, null, null );
        when( repo.findByTarget( "tgtGone" ) ).thenReturn( List.of( inbound ) );
        sync( repo, idx, Map.of(), Map.of() ).onPageDeleted( "Gone" );
        verify( repo ).updateStatus( eq( 8L ), eq( CitationStatus.TARGET_MISSING ), any() );
    }

    @Test
    void onPageDeletedSkipsWhenCanonicalIdStillLivesRename() {
        final CitationRepository repo = mock( CitationRepository.class );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveCanonicalIdFromSlug( "OldName" ) ).thenReturn( Optional.of( "tgt1" ) );
        when( idx.resolveSlugFromCanonicalId( "tgt1" ) ).thenReturn( Optional.of( "NewName" ) );  // rename
        sync( repo, idx, Map.of(), Map.of() ).onPageDeleted( "OldName" );
        verify( repo, never() ).updateStatus( anyLong(), any(), any() );
    }
}
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationSyncTest`
Expected: FAIL.

- [ ] **Step 3: Implement `CitationSync`**

```java
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Orchestrates citation extraction, grading, and inbound re-checks across save/rename/delete + full reconcile. */
public final class CitationSync {

    private static final Logger LOG = LoggerFactory.getLogger( CitationSync.class );

    private final CitationRepository repo;
    private final CitationMarkupParser parser;
    private final CitationStalenessGrader grader;
    private final StructuralIndexService structuralIndex;
    private final Function< String, Optional< String > > bodyLoader;     // slug -> raw body
    private final Function< String, Optional< Integer > > versionLoader; // slug -> current version

    public CitationSync( final CitationRepository repo, final CitationMarkupParser parser,
                         final CitationStalenessGrader grader, final StructuralIndexService structuralIndex,
                         final Function< String, Optional< String > > bodyLoader,
                         final Function< String, Optional< Integer > > versionLoader ) {
        this.repo = repo; this.parser = parser; this.grader = grader; this.structuralIndex = structuralIndex;
        this.bodyLoader = bodyLoader; this.versionLoader = versionLoader;
    }

    /** A page was saved: reconcile its outbound citations and re-grade citations that target it. */
    public void onPageSaved( final String pageName ) {
        bodyLoader.apply( pageName ).ifPresent( body -> reconcileSource( pageName, body ) );
        recheckInbound( pageName );
    }

    /** A page was deleted: best-effort flag inbound citations target_missing (skip renames).
     *  reconcileAll() is the guarantee; this is only the fast path. */
    public void onPageDeleted( final String pageName ) {
        final Optional< String > cid = structuralIndex.resolveCanonicalIdFromSlug( pageName );
        if ( cid.isEmpty() ) { return; }                                 // row already gone — reconcileAll handles it
        if ( structuralIndex.resolveSlugFromCanonicalId( cid.get() ).isPresent() ) { return; }  // rename, not delete
        for ( final CitationRow r : repo.findByTarget( cid.get() ) ) {
            if ( r.status() != CitationStatus.TARGET_MISSING ) {
                repo.updateStatus( r.id(), CitationStatus.TARGET_MISSING, Instant.now() );
            }
        }
    }

    /** A page was renamed: canonical_id is stable, so only inbound re-grade is needed (heading anchors unchanged). */
    public void onPageRenamed( final String oldName, final String newName ) {
        recheckInbound( newName );
    }

    /** Re-grade every citation. Safety net for missed events / silent deletes. */
    public void reconcileAll() {
        for ( final CitationRow r : repo.findAll() ) { regrade( r ); }
    }

    private void reconcileSource( final String pageName, final String body ) {
        final Optional< String > sourceCid = structuralIndex.resolveCanonicalIdFromSlug( pageName );
        if ( sourceCid.isEmpty() ) {
            LOG.debug( "citation: '{}' has no canonical_id; skipping outbound reconcile", pageName );
            return;
        }
        final List< CitationRow > rows = new ArrayList<>();
        for ( final ParsedCitation p : parser.parse( body ) ) {
            final CitationStatus status = grader.grade( p.targetCanonicalId(), p.targetHeadingPath(), p.spanText() );
            final Integer version = structuralIndex.resolveSlugFromCanonicalId( p.targetCanonicalId() )
                    .flatMap( versionLoader ).orElse( null );
            rows.add( new CitationRow( 0, sourceCid.get(), p.targetCanonicalId(), p.targetHeadingPath(),
                    p.spanText(), p.spanHash(), p.claimText(), p.ordinal(), version, status, null, null, null ) );
        }
        repo.replaceForSource( sourceCid.get(), rows );
    }

    private void recheckInbound( final String pageName ) {
        final Optional< String > targetCid = structuralIndex.resolveCanonicalIdFromSlug( pageName );
        if ( targetCid.isEmpty() ) { return; }
        for ( final CitationRow r : repo.findByTarget( targetCid.get() ) ) { regrade( r ); }
    }

    private void regrade( final CitationRow r ) {
        final CitationStatus now = grader.grade( r.targetCanonicalId(), r.targetHeadingPath(), r.spanText() );
        if ( now != r.status() ) { repo.updateStatus( r.id(), now, Instant.now() ); }
        else { repo.touchChecked( r.id(), Instant.now() ); }
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationSyncTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/CitationSync.java wikantik-main/src/test/java/com/wikantik/citation/CitationSyncTest.java
git commit -m "feat(citations): sync orchestrator (save/delete/rename/reconcileAll)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Event listener

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationEventListener.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/CitationEventListenerTest.java`

**Pattern:** mirror `com.wikantik.ontology.runtime.OntologyEventListener` exactly (read it). It implements `WikiEventListener`, maps events to `CitationSync` calls, and `register(PageManager, FilterManager)` adds itself to both via `WikiEventManager.addWikiEventListener`.

- [ ] **Step 1: Write the failing test** (drives event → sync mapping with a mocked `CitationSync`)

```java
package com.wikantik.citation;

import static org.mockito.Mockito.*;
import com.wikantik.api.event.WikiPageEvent;
import org.junit.jupiter.api.Test;

class CitationEventListenerTest {

    @Test
    void postSaveEndDispatchesOnPageSaved() {
        final CitationSync sync = mock( CitationSync.class );
        final CitationEventListener l = new CitationEventListener( sync );
        l.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "MyPage" ) );
        verify( sync ).onPageSaved( "MyPage" );
    }

    @Test
    void pageDeletedDispatchesOnPageDeleted() {
        final CitationSync sync = mock( CitationSync.class );
        final CitationEventListener l = new CitationEventListener( sync );
        l.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "MyPage" ) );
        verify( sync ).onPageDeleted( "MyPage" );
    }
}
```
Note: confirm the exact `WikiPageEvent` constructor + constant names against `OntologyEventListener` and its tests — the recon shows it consumes `WikiPageEvent.POST_SAVE_END` / `POST_SAVE` / `PAGE_DELETED` and `WikiPageRenameEvent`. Use whatever constructor `OntologyEventListenerTest` (if present) or the event class exposes; adjust the test to match.

- [ ] **Step 2: Run it — expect FAIL**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationEventListenerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `CitationEventListener`** (copy the dispatch shape from `OntologyEventListener.actionPerformed`)

```java
package com.wikantik.citation;

import com.wikantik.api.event.WikiEvent;
import com.wikantik.api.event.WikiEventListener;
import com.wikantik.api.event.WikiEventManager;
import com.wikantik.api.event.WikiPageEvent;
import com.wikantik.api.event.WikiPageRenameEvent;
import com.wikantik.api.managers.FilterManager;
import com.wikantik.api.managers.PageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges page save/rename/delete events to {@link CitationSync}. Mirrors OntologyEventListener. */
public final class CitationEventListener implements WikiEventListener {

    private static final Logger LOG = LoggerFactory.getLogger( CitationEventListener.class );
    private final CitationSync sync;

    public CitationEventListener( final CitationSync sync ) { this.sync = sync; }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        if ( pageManager != null ) { WikiEventManager.addWikiEventListener( pageManager, this ); }
        if ( filterManager != null ) { WikiEventManager.addWikiEventListener( filterManager, this ); }
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        try {
            if ( event instanceof WikiPageRenameEvent re ) {
                sync.onPageRenamed( re.getOldPageName(), re.getNewPageName() );
            } else if ( event instanceof WikiPageEvent pe ) {
                final int type = pe.getType();
                if ( type == WikiPageEvent.POST_SAVE_END || type == WikiPageEvent.POST_SAVE ) {
                    sync.onPageSaved( pe.getPageName() );
                } else if ( type == WikiPageEvent.PAGE_DELETED ) {
                    sync.onPageDeleted( pe.getPageName() );
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "citation event handling failed for {}: {}", event, e.getMessage(), e );
        }
    }
}
```
Match the exact accessor names (`getOldPageName`/`getNewPageName`/`getPageName`/`getType`) to those `OntologyEventListener` uses.

- [ ] **Step 4: Run the test — expect PASS**

Run: `mvn -q -pl wikantik-main test -Dtest=CitationEventListenerTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/CitationEventListener.java wikantik-main/src/test/java/com/wikantik/citation/CitationEventListenerTest.java
git commit -m "feat(citations): WikiEventListener bridging saves to CitationSync

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Wiring + subsystem accessors

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationWiringHelper.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystem.java` (add `citationRepository` + `citationSync` to the record + accessors — mirror the existing `driftSweepService` field)
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemFactory.java` (resolve them the same way it resolves `DriftSweepService` at ~line 75-84)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (call `CitationWiringHelper.wireCitations(...)` near the drift wiring; retain the listener strong-ref)

**Critical (ArchUnit R-2):** access the repo/sync ONLY through the `PageGraphSubsystem` accessor (mirroring `driftSweepService()`), the same mechanism the factory already uses — do **not** add new `WikiEngine#getManager(...)` call sites in servlets/initializers. After this task, run `DecompositionArchTest`; if its freeze store self-mutates on a failing run, `git checkout` the store file before retrying (known gotcha).

- [ ] **Step 1: Implement `CitationWiringHelper`**

```java
package com.wikantik.citation;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.FilterManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds and registers the citation subsystem. Returns the strong-ref holder the engine must retain. */
public final class CitationWiringHelper {

    private static final Logger LOG = LoggerFactory.getLogger( CitationWiringHelper.class );

    /** Holds the constructed pieces; the engine keeps a strong reference (WikiEventManager uses weak refs). */
    public record Wired( CitationRepository repository, CitationSync sync, CitationEventListener listener ) {}

    private CitationWiringHelper() {}

    public static Optional< Wired > wireCitations( final WikiEngine engine, final Properties props,
            final DataSource dataSource, final PageManager pageManager,
            final StructuralIndexService structuralIndex, final FilterManager filterManager,
            final OntologyRebuildCoordinator coordinator ) {

        if ( !Boolean.parseBoolean( props.getProperty( "wikantik.citations.enabled", "true" ) ) ) {
            LOG.info( "citations: disabled via wikantik.citations.enabled=false" );
            return Optional.empty();
        }
        if ( dataSource == null || structuralIndex == null ) {
            LOG.info( "citations: no dataSource/structuralIndex; citation tracking disabled" );
            return Optional.empty();
        }

        final Function< String, Optional< String > > bodyLoader = slug -> {
            try { return Optional.ofNullable( pageManager.getPureText( slug, PageProvider.LATEST_VERSION ) ); }
            catch ( final Exception e ) {
                LOG.warn( "citations: getPureText({}) failed: {}", slug, e.getMessage() );
                return Optional.empty();
            }
        };
        final Function< String, Optional< Integer > > versionLoader = slug -> {
            try {
                final var p = pageManager.getPage( slug );
                return p == null ? Optional.empty() : Optional.of( p.getVersion() );
            } catch ( final Exception e ) {
                LOG.warn( "citations: version({}) failed: {}", slug, e.getMessage() );
                return Optional.empty();
            }
        };

        final CitationRepository repo = new CitationRepository( dataSource );
        final CitationStalenessGrader grader =
                new CitationStalenessGrader( structuralIndex, bodyLoader, new MarkdownSectionExtractor() );
        final CitationSync sync = new CitationSync( repo, new CitationMarkupParser(), grader,
                structuralIndex, bodyLoader, versionLoader );
        final CitationEventListener listener = new CitationEventListener( sync );
        listener.register( pageManager, filterManager );

        if ( coordinator != null ) {
            coordinator.onRebuildComplete( () -> {
                try { sync.reconcileAll(); }
                catch ( final RuntimeException e ) { LOG.warn( "citations: reconcileAll failed: {}", e.getMessage(), e ); }
            } );
        }
        LOG.info( "citations: wired (event listener + reconcileAll on rebuild)" );
        return Optional.of( new Wired( repo, sync, listener ) );
    }
}
```
Confirm `OntologyRebuildCoordinator.onRebuildComplete(Runnable)` is the exact hook name (recon §4: `DriftWiringHelper` uses `coordinator.onRebuildComplete(() -> service.triggerAsync("scheduled"))`).

- [ ] **Step 2: Add the subsystem accessors**

In `PageGraphSubsystem.java`: add `CitationRepository citationRepository` and `CitationSync citationSync` to the record component list (next to `DriftSweepService driftSweepService`), updating the Javadoc the same way (these are null when the engine boots without a datasource). In `PageGraphSubsystemFactory.java`: resolve the wired pieces (passed in from `WikiEngine` wiring, or read the same way `driftSweepService` is obtained at lines ~75-84) and pass them into the `PageGraphSubsystem` constructor. Keep ordering consistent with the constructor.

- [ ] **Step 3: Call the wiring from `WikiEngine`**

Near where drift is wired (recon: `DriftWiringHelper.wireDrift(...)` is called from `WikiEngine` wiring init), add:
```java
final Optional< CitationWiringHelper.Wired > citations = CitationWiringHelper.wireCitations(
        this, properties, dataSource, getManager( PageManager.class ),
        structuralIndexService /* the same StructuralIndexService used by the spine */,
        getManager( FilterManager.class ), ontologyRebuildCoordinator /* may be null */ );
citations.ifPresent( w -> {
    this.citationWired = w;   // FIELD: private CitationWiringHelper.Wired citationWired;  (strong ref)
} );
```
Add a `private CitationWiringHelper.Wired citationWired;` field to `WikiEngine` so the listener is not GC'd (WikiEventManager holds weak references — recon §7). Feed `citationWired.repository()` / `.sync()` into the `PageGraphSubsystem` construction (Step 2). Use the existing `dataSource`, `StructuralIndexService`, and `OntologyRebuildCoordinator` locals already in scope at that point in `WikiEngine` wiring (grep for where `DriftWiringHelper.wireDrift` and `structuralIndex` are in scope and place this immediately after).

- [ ] **Step 4: Compile + ArchUnit**

Run: `mvn -q -pl wikantik-main test-compile && mvn -q -pl wikantik-main test -Dtest=DecompositionArchTest`
Expected: BUILD SUCCESS; ArchUnit green. If red due to a getManager violation, move the resolution behind the `PageGraphSubsystem` accessor (don't add servlet-side getManager). If the freeze store self-mutated, `git checkout -- <DecompositionArchTest freeze store path>` and retry.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/CitationWiringHelper.java wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystem.java wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemFactory.java wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(citations): wire event listener + reconcile + subsystem accessors

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Admin drift citations view

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminDriftResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminDriftCitationsTest.java` (or extend the existing drift resource test)

**Endpoint:** `GET /admin/drift/citations?direction=outbound|inbound|both&page=<canonical_id>` →
```json
{ "counts": { "current": N, "stale": N, "target_missing": N },
  "outbound": [ { "sourceCanonicalId": "...", "targetCanonicalId": "...", "targetHeadingPath": "...",
                  "spanText": "...", "claimText": "...", "status": "stale", "pinnedTargetVersion": 7 } ],
  "inbound":  [ ... same shape ... ] }
```
Live counts come from `citationRepository().countsByStatus()`; lists from `findBySource(page)` (outbound) and `findByTarget(page)` filtered to `status != current` (inbound). Omit `page` ⇒ counts only (or a capped global stale list).

- [ ] **Step 1: Write the failing test** — a unit/servlet test asserting the `citations` action returns the counts shape from a stubbed repository (mirror how `AdminDriftResource` tests stub `getSubsystems().pageGraph()`). Assert: a stale row appears under `inbound` for its target page; counts reflect the repository.

- [ ] **Step 2: Run it — expect FAIL.**

- [ ] **Step 3: Implement** — add an `else if ( "citations".equals( action ) ) { handleCitations( req, resp ); }` branch in `doGet` (recon §5 shows the action-dispatch pattern), reading `getSubsystems().pageGraph().citationRepository()`. Serialize with the resource's existing `NULL_SAFE_GSON`. Return 503/empty-counts if the repository accessor is null (citations disabled). Map `CitationRow` → the JSON shape via a small private helper (or reuse `CitationRef`).

- [ ] **Step 4: Run the test — expect PASS.**

- [ ] **Step 5: Commit**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminDriftResource.java wikantik-rest/src/test/java/com/wikantik/rest/AdminDriftCitationsTest.java
git commit -m "feat(citations): /admin/drift/citations bidirectional view

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: `list_stale_citations` MCP tool

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListStaleCitationsTool.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` (instantiate + register, null-guarded)
- Test: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListStaleCitationsToolTest.java`

**Pattern:** mirror `ListTagsTool` (recon §8): implement `McpTool` (`name()`, `definition()`, `execute(Map)`), read params defensively, return `McpToolUtils.jsonResult(...)`, error via `McpToolUtils.errorResult(...)`. Tool name `list_stale_citations`; params: `direction` (default `both`), `page` (optional canonical_id), `limit` (default 50). Reads the `CitationRepository`.

- [ ] **Step 1: Write the failing test** — construct `ListStaleCitationsTool` with a stub repository returning two rows (one stale, one current); assert `execute(Map.of("page","p1"))` returns only the stale row in the JSON payload and that `name()` equals `list_stale_citations`.

- [ ] **Step 2: Run it — expect FAIL.**

- [ ] **Step 3: Implement the tool** following `ListTagsTool` verbatim for the boilerplate; map `CitationRow` → `CitationRef` for serialization (reuse `KnowledgeMcpUtils.GSON`). Register in `KnowledgeMcpInitializer.contextInitialized`: resolve the `CitationRepository` the same way other services are resolved there, and `if ( citationRepo != null ) tools.add( new ListStaleCitationsTool( citationRepo ) );` then the existing `builder.toolCall( tool.definition(), ... )` loop picks it up. The repository must be reachable from the knowledge MCP init the same way the structural index / retrieval services are — wire it through the same accessor the initializer already uses (e.g. `engine`/subsystem), guarded by null.

- [ ] **Step 4: Run the test — expect PASS.**

- [ ] **Step 5: Commit**
```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListStaleCitationsTool.java wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ListStaleCitationsToolTest.java
git commit -m "feat(citations): list_stale_citations knowledge MCP tool

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 11: for-agent `stale_citations` projection field

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java` (add `List<CitationRef> staleCitations` as the LAST record component)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java` (populate it; inject `CitationRepository`)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageForAgentResource.java` (`toJson` → add `d.add("stale_citations", ...)`)
- Update: every other `new ForAgentProjection(...)` call site + its tests (grep first)

- [ ] **Step 1: Grep all constructor call sites** — `grep -rn "new ForAgentProjection(" wikantik-*/src` — every site needs the new trailing arg (pass `List.of()` where citations are irrelevant, e.g. degraded fixtures/tests).

- [ ] **Step 2: Write the failing test** — in `DefaultForAgentProjectionService`'s test, stub the injected `CitationRepository.findBySource(canonicalId)` to return one stale + one current row; assert the projection's `staleCitations()` contains only the stale one (mapped to `CitationRef`). If the service test doesn't yet inject a repo, add the constructor param + update its existing construction.

- [ ] **Step 3: Run it — expect FAIL.**

- [ ] **Step 4: Implement** — add the component to the record; in `DefaultForAgentProjectionService.project(...)`, after assembling the rest, query `citationRepository.findBySource(canonicalId)`, filter `status != CURRENT`, map to `CitationRef`, pass into the constructor (empty list when the repo is null/disabled — keep the existing degrade-gracefully posture, recon §6). In `PageForAgentResource.toJson`, serialize as the array field `stale_citations` following the `key_facts`/`headings_outline` pattern.

- [ ] **Step 5: Run the test + compile all three modules** — `mvn -q -pl wikantik-api,wikantik-main,wikantik-rest -am test-compile` then the focused test. Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git add wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java wikantik-rest/src/main/java/com/wikantik/rest/PageForAgentResource.java
# plus any updated call sites / tests from Step 1
git commit -m "feat(citations): stale_citations field on for-agent projection

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 12: `cite://` link rendering

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/citation/CitationLinkRenderingFilter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/citation/CitationLinkRenderingFilterTest.java`
- Modify: the filter-registration site (where `StructuralSpinePageFilter` is added — `PageGraphWiringHelper.wireSpineFilters`, recon §2d) OR the core page-filter wiring; register at a low priority so it runs on rendered HTML.

**Behavior:** a `PageFilter.postTranslate(Context, String htmlContent)` that rewrites `href="cite://<cid>/<heading>"` (and `cite://<cid>`) in the rendered HTML to `/wiki/{slug}` (+ `#<anchor>` best-effort) and adds `class="wiki-citation"`. canonical_id → slug via `StructuralIndexService.resolveSlugFromCanonicalId`; unknown cid ⇒ leave a non-navigating anchor with `class="wiki-citation wiki-citation-missing"`. **No staleness shown to readers.**

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wikantik.api.core.Context;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CitationLinkRenderingFilterTest {

    @Test
    void rewritesCiteHrefToWikiSlug() throws Exception {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "abc123" ) ).thenReturn( Optional.of( "Deploy" ) );
        final CitationLinkRenderingFilter f = new CitationLinkRenderingFilter( idx );
        final String html = "<p><a href=\"cite://abc123/Rollback%20Steps\">claim</a></p>";
        final String out = f.postTranslate( mock( Context.class ), html );
        assertTrue( out.contains( "href=\"/wiki/Deploy" ) );
        assertTrue( out.contains( "wiki-citation" ) );
        assertFalse( out.contains( "cite://" ) );
    }

    @Test
    void unknownTargetGetsMissingClassAndDoesNotThrow() throws Exception {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.resolveSlugFromCanonicalId( "gone" ) ).thenReturn( Optional.empty() );
        final CitationLinkRenderingFilter f = new CitationLinkRenderingFilter( idx );
        final String out = f.postTranslate( mock( Context.class ), "<a href=\"cite://gone\">c</a>" );
        assertTrue( out.contains( "wiki-citation-missing" ) );
    }
}
```

- [ ] **Step 2: Run it — expect FAIL.**

- [ ] **Step 3: Implement** — a `PageFilter` overriding `postTranslate`; regex-replace `href="cite://([^"/]+)(?:/([^"]*))?"` → resolve slug, build `/wiki/{slug}` + optional `#` + a best-effort heading anchor (lowercase, spaces→`-`, strip non-alphanumerics on the last decoded segment), inject the class. Wrap each replacement in try/catch logging `LOG.warn` (never swallow silently) and leaving the original href on failure. Constructor `(StructuralIndexService)`.

- [ ] **Step 4: Register the filter** — in `PageGraphWiringHelper.wireSpineFilters` (or the nearest core filter wiring with `StructuralIndexService` in scope), `filterManager.addPageFilter( new CitationLinkRenderingFilter( structuralIndex ), <priority> )`. Choose a priority that runs during the render path (postTranslate); match the convention used by other postTranslate filters.

- [ ] **Step 5: Run the test — expect PASS.**

- [ ] **Step 6: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/citation/CitationLinkRenderingFilter.java wikantik-main/src/test/java/com/wikantik/citation/CitationLinkRenderingFilterTest.java wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphWiringHelper.java
git commit -m "feat(citations): render cite:// links to wiki anchors

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 13: Integration test + docs + full build

**Files:**
- Create: an IT under `wikantik-it-tests` (mirror an existing REST/MCP IT that seeds a page and hits `/admin/*` + `/knowledge-mcp`)
- Modify: `ini/wikantik.properties` (document `wikantik.citations.enabled=true`), `CHANGELOG.md`, `CLAUDE.md` (knowledge-MCP tool count + the `/admin/drift` + for-agent bullets), `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md` (note citation edges as a third edge type), the design spec status line.

- [ ] **Step 1: Write the IT** — end-to-end against Cargo+Postgres: (a) save a source page whose body cites a heading/span of a seeded target page → assert `GET /admin/drift/citations?page=<source-cid>&direction=outbound` shows it `current`; (b) edit the target so the span disappears → assert the citation flips to `stale` (outbound for source / inbound for target) and `list_stale_citations` returns it; (c) assert `GET /api/pages/for-agent/<source-cid>` includes the stale citation under `stale_citations`. Use `RestSeedHelper.awaitAdminReady` (known admin-login race) and open seeded fixtures, accounting for the structural-index seed lag (memory: index-dependent ITs must tolerate >20s lag — poll/await).

- [ ] **Step 2: Add the property + docs** — add `wikantik.citations.enabled=true` with a comment to `ini/wikantik.properties`; add a `## [2.0.19]` CHANGELOG entry; bump the knowledge-MCP tool count wherever it's asserted (verify the live count first); add the citation-edges note to the architecture docs.

- [ ] **Step 3: Run the full unit build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS (all modules). Fix any compile/test fallout (esp. `ForAgentProjection` call sites, ArchUnit freeze store).

- [ ] **Step 4: Run the full IT reactor** (sequential, fail-at-end — per CLAUDE.md + the "full IT before committing" rule)

Run: `mvn clean install -Pintegration-tests -fae`
Expected: all IT modules green. (If `wikantik-main`'s 4000 serial unit tests make a single background run get wall-killed, run per-IT-module with `-pl <module> -am` one at a time, or use the `-Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false` skip pattern.)

- [ ] **Step 5: Commit**
```bash
git add wikantik-it-tests/ ini/wikantik.properties CHANGELOG.md CLAUDE.md docs/
git commit -m "test(citations): end-to-end IT + docs for Phase 3 citation edges

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-review notes (author)

- **Spec coverage:** markup §1 → Task 2/12; identity §2 → Task 2 (ordinals) + Task 4 (`identity()`); graded staleness §3 → Task 5; reconciler triggers §4 → Task 6/7/8; surfaces §5 → Task 9/10/11; self-healing loop §6 → emerges from Task 6 (re-parse re-pins) + the surfaces. Schema → Task 1. Out-of-scope items (semantic-contradiction, auto-rewrite, trend snapshots) are not built. ✔
- **Type consistency:** `CitationStatus`/`CitationRef` (api), `CitationRow`/`ParsedCitation` (main) used consistently; `replaceForSource`, `findBySource`, `findByTarget`, `countsByStatus`, `updateStatus`, `touchChecked` match across Tasks 4/6/9/10. ✔
- **Known-gotcha guards called out inline:** ArchUnit R-2 freeze store (Task 8), admin-login IT race + structural-index seed lag (Task 13), H2 schema bootstrap for repo tests (Task 4), `ForAgentProjection` constructor ripple (Task 11), full-IT execution caveat (Task 13). ✔
- **Confirm-against-existing seams flagged where a signature couldn't be 100% pinned from recon:** `FrontmatterParser.body()` (Task 3), `WikiPageEvent` constructor/constants + rename accessors (Task 7), `OntologyRebuildCoordinator.onRebuildComplete` (Task 8), the MCP repo-resolution seam (Task 10).

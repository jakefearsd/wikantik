# Comment Mentions & Page Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add @-mentions inside comments (with picker + chip rendering), a per-user "My mentions" feed with unread tracking, and DB-backed page ownership with admin reassignment + delete-user integration. All in-app; no email.

**Architecture:** New tables `page_owners` (rename-stable, keyed by `canonical_id`) and `comment_mentions` (FK CASCADE off `comments`). `PageOwnerService` is find-or-create with lazy frontmatter-author bootstrap; `MentionService` parses `@<login>` tokens, resolves them against `users.login_name`, and writes one row per (comment, mentioned) pair plus an auto-owner-mention on thread creation. Three new REST resources (`MentionableUsersResource`, `MyMentionsResource`, `AdminPageOwnershipResource`) sit alongside the extended `CommentThreadResource`; the frontend gets a reusable `useMentionPicker` hook (consumed by the composer and reply textarea), a `CommentBody` chip renderer, a `MentionsPage` route, a Sidebar badge, and an admin page that mirrors the existing `AdminKgPolicyPage` shell.

**Tech Stack:** Java 21, JDBC/PostgreSQL (H2 in tests), Jakarta Servlets, Gson, JUnit 5 + Mockito, Cargo IT; React + Vite, Vitest + happy-dom + @testing-library/react.

**Reference patterns (read first):**
- Migration: `bin/db/migrations/V033__comment_threads.sql`
- DAO + H2 test: `wikantik-main/.../comments/CommentStore.java` + `CommentStoreTest.java`
- Persistence wiring: `wikantik-main/.../persistence/subsystem/PersistenceSubsystemFactory.java` + `PersistenceSubsystem.java`
- REST resource + Mockito test: `wikantik-rest/.../CommentThreadResource.java` + `CommentThreadResourceTest.java`
- REST resource that reaches the user DB: `wikantik-rest/.../AdminUserResource.java` (uses `getSubsystems().auth().users().getUserDatabase()`)
- IT pattern: `wikantik-it-tests/wikantik-it-test-rest/.../CommentThreadIT.java`
- Admin page UI: `wikantik-frontend/src/components/admin/AdminKgPolicyPage.jsx` (+ `<AdminPage>`, `<PageHeader>`, `<AdminTable>`)
- Sidebar nav: `wikantik-frontend/src/components/Sidebar.jsx` (authenticated section after the "+ New Article" link at ~line 92)
- Spec: `docs/superpowers/specs/2026-05-28-comment-mentions-and-ownership-design.md`

---

## Task 1: V034 migration (DDL only)

**Files:**
- Create: `bin/db/migrations/V034__page_owners_and_mentions.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V034: Page ownership + comment mentions.
-- DDL only — initial population of page_owners is lazy via
-- PageOwnerService.getOwner() (find-or-create from frontmatter `author`).
-- Mentions are written at comment save/edit time by MentionService.

-- ---- page ownership -------------------------------------------------------
-- canonical_id is NOT a foreign key to page_canonical_ids; the structural
-- index synthesises in-memory ULIDs for pages without an authored
-- canonical_id and does NOT persist them, so resolveCanonicalIdFromSlug can
-- legitimately return an id absent from that table. (Same rationale as V033.)
-- owner_login NULL ⇒ orphaned; PageOwnerService applies an admin fallback at
-- read time.
CREATE TABLE IF NOT EXISTS page_owners (
    canonical_id TEXT PRIMARY KEY,
    owner_login  TEXT,
    assigned_by  TEXT NOT NULL,
    assigned_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_page_owners_owner_login
    ON page_owners (owner_login);

CREATE INDEX IF NOT EXISTS idx_page_owners_orphaned
    ON page_owners (canonical_id)
    WHERE owner_login IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON page_owners TO :app_user;

-- ---- comment mentions -----------------------------------------------------
CREATE TABLE IF NOT EXISTS comment_mentions (
    id                UUID PRIMARY KEY,
    comment_id        UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    mentioned_login   TEXT NOT NULL,
    mentioning_login  TEXT NOT NULL,
    is_owner_mention  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_comment_mentions UNIQUE (comment_id, mentioned_login)
);

CREATE INDEX IF NOT EXISTS idx_comment_mentions_feed
    ON comment_mentions (mentioned_login, read_at, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_comment_mentions_comment_id
    ON comment_mentions (comment_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON comment_mentions TO :app_user;
```

Prepend the standard 18-line ASF header (copy from `bin/db/migrations/V033__comment_threads.sql`).

- [ ] **Step 2: Verify it parses and is idempotent**

Run: `bin/db/migrate.sh --status`
Expected: V034 is listed as the next-to-apply with no syntax errors.

- [ ] **Step 3: Commit**

```bash
git add bin/db/migrations/V034__page_owners_and_mentions.sql
git commit -m "feat(comments): V034 page_owners + comment_mentions tables"
```

---

## Task 2: API domain records

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/comments/PageOwnership.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/comments/Mention.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/comments/MentionFeedItem.java`

Each file needs the ASF license header (copy from `wikantik-api/.../comments/Comment.java`).

- [ ] **Step 1: `PageOwnership.java`**

```java
package com.wikantik.api.comments;

import java.time.Instant;

/** Row from {@code page_owners}; {@code ownerLogin} is null for orphaned pages. */
public record PageOwnership(
        String canonicalId,
        String ownerLogin,
        String assignedBy,
        Instant assignedAt ) {
}
```

- [ ] **Step 2: `Mention.java`**

```java
package com.wikantik.api.comments;

import java.time.Instant;
import java.util.UUID;

/** A single row in {@code comment_mentions}. */
public record Mention(
        UUID id,
        UUID commentId,
        String mentionedLogin,
        String mentioningLogin,
        boolean isOwnerMention,
        Instant createdAt,
        Instant readAt ) {
}
```

- [ ] **Step 3: `MentionFeedItem.java`**

```java
package com.wikantik.api.comments;

import java.time.Instant;
import java.util.UUID;

/** Composite of a mention with the context needed by the "My mentions" feed. */
public record MentionFeedItem(
        UUID id,
        UUID threadId,
        UUID commentId,
        String canonicalId,
        String pageName,
        String snippet,
        String mentionedBy,
        boolean isOwnerMention,
        Instant mentionedAt,
        Instant readAt ) {
}
```

- [ ] **Step 4: Compile**

Run: `mvn -q -pl wikantik-api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/comments/
git commit -m "feat(comments): API records for page ownership and mentions"
```

---

## Task 3: PageOwnerService DAO (TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/comments/PageOwnerService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/PageOwnerServiceTest.java`

The service owns ownership resolution. To keep tests free of `PageManager`, it accepts a functional seam `PageAuthorResolver`: given a canonical_id it returns the bootstrap login (or `Optional.empty()` ⇒ orphan).

- [ ] **Step 1: Write the failing test**

Mirror `PageVerificationDaoTest`'s H2 setup. The user db is also needed at lookup time (to validate the bootstrap login exists); use a small in-memory predicate.

```java
package com.wikantik.comments;

import com.wikantik.api.comments.PageOwnership;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PageOwnerServiceTest {

    private DataSource ds;
    private Set< String > users;
    private PageOwnerService svc;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:pos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE page_owners (
                    canonical_id TEXT PRIMARY KEY,
                    owner_login  TEXT,
                    assigned_by  TEXT NOT NULL,
                    assigned_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
        }
        this.users = new HashSet<>( List.of( "alice", "bob", "admin" ) );
        this.svc = new PageOwnerService( ds, users::contains, defaultAuthorResolver() );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_owners" );
        }
    }

    private Function< String, Optional< String > > defaultAuthorResolver() {
        return canonicalId -> switch ( canonicalId ) {
            case "CID-A" -> Optional.of( "alice" );
            case "CID-GHOST" -> Optional.of( "ghost" );   // user doesn't exist
            case "CID-NONE" -> Optional.empty();
            default -> Optional.empty();
        };
    }

    @Test
    void getOwner_bootstraps_from_frontmatter_when_author_exists() {
        assertEquals( "alice", svc.getOwner( "CID-A" ) );
        // Row should now exist.
        final PageOwnership row = svc.findRaw( "CID-A" ).orElseThrow();
        assertEquals( "alice", row.ownerLogin() );
        assertEquals( "system:bootstrap", row.assignedBy() );
    }

    @Test
    void getOwner_returns_admin_when_bootstrap_author_is_not_a_real_user() {
        assertEquals( "admin", svc.getOwner( "CID-GHOST" ) );
        // Row exists but owner_login is null (orphan).
        assertNull( svc.findRaw( "CID-GHOST" ).orElseThrow().ownerLogin() );
    }

    @Test
    void getOwner_returns_admin_when_no_author_at_all() {
        assertEquals( "admin", svc.getOwner( "CID-NONE" ) );
        assertNull( svc.findRaw( "CID-NONE" ).orElseThrow().ownerLogin() );
    }

    @Test
    void getOwner_returns_admin_when_owner_was_later_deleted() {
        svc.setOwner( "CID-A", "alice", "admin" );
        users.remove( "alice" );
        // owner_login still 'alice' in DB, but the user is gone — admin fallback.
        assertEquals( "admin", svc.getOwner( "CID-A" ) );
    }

    @Test
    void setOwner_records_assigned_by_and_at() {
        svc.setOwner( "CID-A", "bob", "admin" );
        final PageOwnership row = svc.findRaw( "CID-A" ).orElseThrow();
        assertEquals( "bob", row.ownerLogin() );
        assertEquals( "admin", row.assignedBy() );
        assertNotNull( row.assignedAt() );
    }

    @Test
    void bulkReassign_moves_all_pages_from_one_owner_to_another() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", "alice", "admin" );
        svc.setOwner( "CID-3", "bob",   "admin" );
        final int moved = svc.bulkReassign( "alice", "bob", "admin" );
        assertEquals( 2, moved );
        assertEquals( "bob", svc.findRaw( "CID-1" ).orElseThrow().ownerLogin() );
        assertEquals( "bob", svc.findRaw( "CID-2" ).orElseThrow().ownerLogin() );
    }

    @Test
    void orphanByOwner_nulls_out_owner_for_all_their_pages() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", "alice", "admin" );
        svc.setOwner( "CID-3", "bob",   "admin" );
        final int orphaned = svc.orphanByOwner( "alice", "system:user-deleted:alice" );
        assertEquals( 2, orphaned );
        assertNull( svc.findRaw( "CID-1" ).orElseThrow().ownerLogin() );
        assertNull( svc.findRaw( "CID-2" ).orElseThrow().ownerLogin() );
        assertEquals( "bob", svc.findRaw( "CID-3" ).orElseThrow().ownerLogin() );
    }

    @Test
    void listOrphaned_returns_only_null_owner_rows_paginated() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", null,    "admin" );
        svc.setOwner( "CID-3", null,    "admin" );
        final List< PageOwnership > orphaned = svc.listOrphaned( 100, 0 );
        assertEquals( 2, orphaned.size() );
        assertEquals( 2, svc.countOrphaned() );
    }

    @Test
    void listByOwner_returns_owned_pages_paginated() {
        svc.setOwner( "CID-1", "alice", "admin" );
        svc.setOwner( "CID-2", "alice", "admin" );
        svc.setOwner( "CID-3", "bob",   "admin" );
        assertEquals( 2, svc.listByOwner( "alice", 100, 0 ).size() );
        assertEquals( 2, svc.countByOwner( "alice" ) );
    }
}
```

- [ ] **Step 2: Run test, verify it fails to compile**

Run: `mvn -q -pl wikantik-main test-compile -Dtest=PageOwnerServiceTest`
Expected: `cannot find symbol: class PageOwnerService`.

- [ ] **Step 3: Implement `PageOwnerService`**

```java
package com.wikantik.comments;

import com.wikantik.api.comments.PageOwnership;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** JDBC gateway for {@code page_owners} plus the find-or-create lazy
 *  bootstrap that derives an initial owner from a page's frontmatter
 *  {@code author}. Applies the {@code admin} fallback at read time when the
 *  stored owner is missing or no longer a real user. */
public class PageOwnerService {

    public static final String ADMIN_FALLBACK = "admin";
    public static final String BOOTSTRAP_ASSIGNER = "system:bootstrap";

    private static final Logger LOG = LogManager.getLogger( PageOwnerService.class );

    private final DataSource ds;
    private final Predicate< String > userExists;
    private final Function< String, Optional< String > > authorResolver;

    /**
     * @param ds JDBC data source
     * @param userExists predicate: does this login_name exist in the user database?
     * @param authorResolver canonical_id → bootstrap login (typically the
     *        frontmatter {@code author}); empty when no author is available.
     */
    public PageOwnerService( final DataSource ds,
                             final Predicate< String > userExists,
                             final Function< String, Optional< String > > authorResolver ) {
        this.ds = ds;
        this.userExists = userExists;
        this.authorResolver = authorResolver;
    }

    /** Resolved owner with the admin fallback applied. Find-or-create. */
    public String getOwner( final String canonicalId ) {
        final Optional< PageOwnership > existing = findRaw( canonicalId );
        if ( existing.isPresent() ) {
            return resolveWithFallback( existing.get().ownerLogin() );
        }
        // No row → bootstrap from the resolver.
        final Optional< String > author = authorResolver.apply( canonicalId );
        final String initial = author.filter( userExists ).orElse( null );
        insertRow( canonicalId, initial, BOOTSTRAP_ASSIGNER );
        return resolveWithFallback( initial );
    }

    /** Raw row (owner_login may be null). Read-only — does not bootstrap. */
    public Optional< PageOwnership > findRaw( final String canonicalId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id, owner_login, assigned_by, assigned_at " +
                      "FROM page_owners WHERE canonical_id = ?" ) ) {
            ps.setString( 1, canonicalId );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) return Optional.empty();
                return Optional.of( readRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService.findRaw({}) failed: {}", canonicalId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    /** Upsert (no bootstrap). Pass {@code null} owner to orphan explicitly. */
    public void setOwner( final String canonicalId, final String ownerLogin, final String assignedBy ) {
        try ( Connection c = ds.getConnection() ) {
            upsert( c, canonicalId, ownerLogin, assignedBy );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService.setOwner({}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "page-owner upsert failed", e );
        }
    }

    public int bulkReassign( final String fromOwner, final String toOwner, final String assignedBy ) {
        return updateOwners(
                "UPDATE page_owners SET owner_login = ?, assigned_by = ?, assigned_at = CURRENT_TIMESTAMP " +
                "WHERE owner_login = ?",
                toOwner, assignedBy, fromOwner );
    }

    public int orphanByOwner( final String owner, final String assignedBy ) {
        return updateOwners(
                "UPDATE page_owners SET owner_login = NULL, assigned_by = ?, assigned_at = CURRENT_TIMESTAMP " +
                "WHERE owner_login = ?",
                null, assignedBy, owner );
    }

    public List< PageOwnership > listOrphaned( final int limit, final int offset ) {
        return queryList(
                "SELECT canonical_id, owner_login, assigned_by, assigned_at FROM page_owners " +
                "WHERE owner_login IS NULL ORDER BY assigned_at DESC LIMIT ? OFFSET ?",
                limit, offset );
    }

    public List< PageOwnership > listByOwner( final String owner, final int limit, final int offset ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id, owner_login, assigned_by, assigned_at FROM page_owners " +
                      "WHERE owner_login = ? ORDER BY assigned_at DESC LIMIT ? OFFSET ?" ) ) {
            ps.setString( 1, owner );
            ps.setInt( 2, limit );
            ps.setInt( 3, offset );
            return readRows( ps );
        } catch ( final SQLException e ) {
            LOG.warn( "listByOwner({}) failed: {}", owner, e.getMessage(), e );
            return List.of();
        }
    }

    public int countOrphaned() {
        return countQuery( "SELECT COUNT(*) FROM page_owners WHERE owner_login IS NULL" );
    }

    public int countByOwner( final String owner ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT COUNT(*) FROM page_owners WHERE owner_login = ?" ) ) {
            ps.setString( 1, owner );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "countByOwner({}) failed: {}", owner, e.getMessage(), e );
            return 0;
        }
    }

    // ---- internals ----

    private String resolveWithFallback( final String stored ) {
        if ( stored == null || stored.isBlank() ) return ADMIN_FALLBACK;
        if ( !userExists.test( stored ) ) return ADMIN_FALLBACK;
        return stored;
    }

    private void insertRow( final String canonicalId, final String ownerLogin, final String assignedBy ) {
        try ( Connection c = ds.getConnection() ) {
            upsert( c, canonicalId, ownerLogin, assignedBy );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService.insertRow({}) failed: {}", canonicalId, e.getMessage(), e );
            // swallow: getOwner still returns the resolved value; next call retries.
        }
    }

    private static void upsert( final Connection c, final String canonicalId,
                                final String ownerLogin, final String assignedBy ) throws SQLException {
        try ( PreparedStatement ps = c.prepareStatement(
                "MERGE INTO page_owners (canonical_id, owner_login, assigned_by, assigned_at) " +
                "KEY(canonical_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)" ) ) {
            ps.setString( 1, canonicalId );
            ps.setString( 2, ownerLogin );
            ps.setString( 3, assignedBy );
            ps.executeUpdate();
        }
    }

    private int updateOwners( final String sql, final String firstArg, final String secondArg, final String thirdArg ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, firstArg );
            ps.setString( 2, secondArg );
            ps.setString( 3, thirdArg );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService update failed: {}", e.getMessage(), e );
            throw new RuntimeException( "page-owner update failed", e );
        }
    }

    private List< PageOwnership > queryList( final String sql, final int limit, final int offset ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setInt( 1, limit );
            ps.setInt( 2, offset );
            return readRows( ps );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService list failed: {}", e.getMessage(), e );
            return List.of();
        }
    }

    private static List< PageOwnership > readRows( final PreparedStatement ps ) throws SQLException {
        final List< PageOwnership > out = new ArrayList<>();
        try ( ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) out.add( readRow( rs ) );
        }
        return out;
    }

    private int countQuery( final String sql ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            rs.next();
            return rs.getInt( 1 );
        } catch ( final SQLException e ) {
            LOG.warn( "PageOwnerService count failed: {}", e.getMessage(), e );
            return 0;
        }
    }

    private static PageOwnership readRow( final ResultSet rs ) throws SQLException {
        final Timestamp at = rs.getTimestamp( "assigned_at" );
        return new PageOwnership(
                rs.getString( "canonical_id" ),
                rs.getString( "owner_login" ),
                rs.getString( "assigned_by" ),
                at == null ? null : at.toInstant() );
    }
}
```

> **H2 note:** the test schema uses H2 in PostgreSQL mode where `MERGE INTO ... KEY(canonical_id) VALUES (...)` is the cross-DB upsert that works in both H2 and PostgreSQL (PostgreSQL accepts `MERGE` from v15+; the project uses pg18 — confirmed safe). If a portability issue surfaces, swap to a SELECT-then-INSERT-or-UPDATE branch (the existing `PageVerificationDao.upsert` shows that pattern).

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=PageOwnerServiceTest`
Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/PageOwnerService.java \
        wikantik-main/src/test/java/com/wikantik/comments/PageOwnerServiceTest.java
git commit -m "feat(comments): PageOwnerService DAO with find-or-create bootstrap"
```

---

## Task 4: MentionExtractor (pure parser, TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionExtractor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionExtractorTest.java`

A pure regex parser with a separate `resolve` step — keeps test setup trivial.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.comments.mentions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MentionExtractorTest {

    @Test
    void parse_empty_or_null_body_yields_empty_set() {
        assertTrue( MentionExtractor.parse( null ).isEmpty() );
        assertTrue( MentionExtractor.parse( "" ).isEmpty() );
        assertTrue( MentionExtractor.parse( "no at-signs here" ).isEmpty() );
    }

    @Test
    void parse_single_mention() {
        assertEquals( Set.of( "alice" ), MentionExtractor.parse( "hi @alice please look" ) );
    }

    @Test
    void parse_multiple_mentions_dedupes() {
        assertEquals( Set.of( "alice", "bob" ),
                MentionExtractor.parse( "@alice and @bob and @alice again" ) );
    }

    @Test
    void parse_strips_trailing_punctuation() {
        assertEquals( Set.of( "alice", "bob", "carol" ),
                MentionExtractor.parse( "@alice, hello — @bob. Also @carol!" ) );
    }

    @Test
    void parse_supports_dot_underscore_hyphen_in_login_names() {
        assertEquals( Set.of( "test.bot", "user_1", "alice-1" ),
                MentionExtractor.parse( "@test.bot @user_1 @alice-1" ) );
    }

    @Test
    void parse_does_not_match_inside_email_addresses() {
        // alice@example.com should NOT be treated as a mention of "alice"
        // (the @ has a non-whitespace prefix). 'example' should match though.
        final Set< String > got = MentionExtractor.parse( "email alice@example.com sent" );
        assertFalse( got.contains( "alice" ) );
        assertTrue( got.contains( "example" ) );
    }

    @Test
    void resolve_filters_to_existing_logins_only() {
        final Set< String > tokens = Set.of( "alice", "ghost", "bob" );
        final Set< String > resolved = MentionExtractor.resolve( tokens, List.of( "alice", "bob", "admin" )::contains );
        assertEquals( Set.of( "alice", "bob" ), resolved );
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `mvn -q -pl wikantik-main test-compile -Dtest=MentionExtractorTest`
Expected: `cannot find symbol: class MentionExtractor`.

- [ ] **Step 3: Implement**

```java
package com.wikantik.comments.mentions;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code @<login>} tokens out of a comment body and resolves them
 *  against the user database. The regex requires a non-word-character before
 *  the {@code @} (so {@code alice@example.com} doesn't mention {@code alice}). */
public final class MentionExtractor {

    // (?<=^|\W)  — preceded by start-of-string or a non-word character
    // @          — literal at-sign
    // ([A-Za-z0-9._-]+) — captured login (allows letters, digits, '.', '_', '-')
    private static final Pattern PATTERN =
            Pattern.compile( "(?<=^|\\W)@([A-Za-z0-9._-]+)" );

    private MentionExtractor() {}

    /** Distinct candidate logins parsed from {@code body}. */
    public static Set< String > parse( final String body ) {
        if ( body == null || body.isEmpty() ) return Set.of();
        final Set< String > out = new HashSet<>();
        final Matcher m = PATTERN.matcher( body );
        while ( m.find() ) out.add( m.group( 1 ) );
        return out;
    }

    /** Intersection of {@code candidates} with users that the predicate
     *  considers existing. */
    public static Set< String > resolve( final Set< String > candidates,
                                         final Predicate< String > userExists ) {
        final Set< String > out = new HashSet<>();
        for ( final String c : candidates ) if ( userExists.test( c ) ) out.add( c );
        return out;
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=MentionExtractorTest`
Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionExtractor.java \
        wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionExtractorTest.java
git commit -m "feat(comments): MentionExtractor parse + resolve"
```

---

## Task 5: MentionService (write/diff against DB, TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionServiceTest.java`

`MentionService` writes the side-table rows. Accepts an injected `Connection` so callers can run within their own transaction. Methods: `recordCreate(c, commentId, mentioningLogin, body, ownerForThread)`, `recordReply(c, commentId, mentioningLogin, body)`, `recordEdit(c, commentId, mentioningLogin, oldBody, newBody)`, plus reads for the feed.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.comments.mentions;

import com.wikantik.api.comments.Mention;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MentionServiceTest {

    private DataSource ds;
    private Set< String > users;
    private MentionService svc;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:msvc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            // Minimal comments/comment_mentions schema for the FK + cascade.
            s.executeUpdate( "CREATE TABLE comments (id UUID PRIMARY KEY)" );
            s.executeUpdate( """
                CREATE TABLE comment_mentions (
                    id UUID PRIMARY KEY,
                    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
                    mentioned_login TEXT NOT NULL,
                    mentioning_login TEXT NOT NULL,
                    is_owner_mention BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    read_at TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT uq_comment_mentions UNIQUE (comment_id, mentioned_login)
                )""" );
        }
        this.users = new HashSet<>( List.of( "alice", "bob", "carol", "admin" ) );
        this.svc = new MentionService( ds, users::contains );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE comment_mentions" );
            s.executeUpdate( "DROP TABLE comments" );
        }
    }

    private UUID newComment() throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comments (id) VALUES ('" + id + "')" );
        }
        return id;
    }

    @Test
    void recordCreate_writes_direct_mentions_minus_author() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "hello @bob and @alice", Optional.empty() );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertFalse( rows.get( 0 ).isOwnerMention() );
    }

    @Test
    void recordCreate_with_owner_writes_owner_mention_when_distinct() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "fyi @bob", Optional.of( "carol" ) );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 2, rows.size() );
        assertTrue( rows.stream().anyMatch( m -> m.mentionedLogin().equals( "bob" ) && !m.isOwnerMention() ) );
        assertTrue( rows.stream().anyMatch( m -> m.mentionedLogin().equals( "carol" ) && m.isOwnerMention() ) );
    }

    @Test
    void recordCreate_skips_owner_mention_when_owner_is_author() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "thinking out loud", Optional.of( "alice" ) );
        }
        assertTrue( svc.findByComment( cid ).isEmpty() );
    }

    @Test
    void recordCreate_skips_owner_mention_when_owner_already_directly_mentioned() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob ping", Optional.of( "bob" ) );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size(), "owner should not get a second row" );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertFalse( rows.get( 0 ).isOwnerMention(),
                "the existing direct mention wins; no separate owner row" );
    }

    @Test
    void recordEdit_preserves_read_state_on_survivors() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob @carol", Optional.empty() );
        }
        // Mark bob's mention as read.
        final Mention bobMention = svc.findByComment( cid ).stream()
                .filter( m -> m.mentionedLogin().equals( "bob" ) ).findFirst().orElseThrow();
        svc.markRead( bobMention.id(), "bob" );

        try ( Connection c = ds.getConnection() ) {
            svc.recordEdit( c, cid, "alice", "@bob @carol", "@bob fine" );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size(), "carol dropped, bob remains" );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
        assertNotNull( rows.get( 0 ).readAt(), "bob's read_at preserved across the edit" );
    }

    @Test
    void recordReply_does_not_write_owner_mention() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordReply( c, cid, "alice", "@bob ack" );
        }
        final List< Mention > rows = svc.findByComment( cid );
        assertEquals( 1, rows.size() );
        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
    }

    @Test
    void markAllRead_updates_only_caller_unread_rows() throws Exception {
        final UUID c1 = newComment(), c2 = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, c1, "alice", "@bob hi", Optional.empty() );
            svc.recordCreate( c, c2, "carol", "@bob too", Optional.empty() );
        }
        assertEquals( 2, svc.markAllRead( "bob" ) );
        assertEquals( 0, svc.unreadCount( "bob" ) );
    }

    @Test
    void deletion_cascades_via_fk() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "@bob", Optional.empty() );
        }
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DELETE FROM comments WHERE id = '" + cid + "'" );
        }
        assertTrue( svc.findByComment( cid ).isEmpty() );
    }
}
```

- [ ] **Step 2: Run, verify it fails to compile**

Run: `mvn -q -pl wikantik-main test-compile -Dtest=MentionServiceTest`
Expected: `cannot find symbol: class MentionService`.

- [ ] **Step 3: Implement**

```java
package com.wikantik.comments.mentions;

import com.wikantik.api.comments.Mention;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Writes/reads comment_mentions. Write methods accept a Connection so they
 *  can run inside the caller's transaction (CommentStore opens one for
 *  thread creation; we participate in it). */
public class MentionService {

    private static final Logger LOG = LogManager.getLogger( MentionService.class );

    private final DataSource ds;
    private final Predicate< String > userExists;

    public MentionService( final DataSource ds, final Predicate< String > userExists ) {
        this.ds = ds;
        this.userExists = userExists;
    }

    /** First comment of a thread. Writes direct mentions (minus the author);
     *  if {@code owner} is present, distinct from the author, and not already
     *  in the direct set, also writes an owner-mention row. */
    public void recordCreate( final Connection c, final UUID commentId,
                              final String mentioningLogin, final String body,
                              final Optional< String > owner ) throws SQLException {
        final Set< String > direct = directMentionsFor( body, mentioningLogin );
        for ( final String m : direct ) insert( c, commentId, m, mentioningLogin, false );
        if ( owner.isPresent() ) {
            final String o = owner.get();
            if ( !o.equals( mentioningLogin ) && !direct.contains( o ) ) {
                insert( c, commentId, o, mentioningLogin, true );
            }
        }
    }

    /** Reply or any non-opening comment. Direct mentions only. */
    public void recordReply( final Connection c, final UUID commentId,
                             final String mentioningLogin, final String body ) throws SQLException {
        for ( final String m : directMentionsFor( body, mentioningLogin ) ) {
            insert( c, commentId, m, mentioningLogin, false );
        }
    }

    /** Apply an edit by diffing old vs new directly-mentioned sets. Owner
     *  rows are NOT touched (the page owner doesn't depend on the body). */
    public void recordEdit( final Connection c, final UUID commentId,
                            final String mentioningLogin,
                            final String oldBody, final String newBody ) throws SQLException {
        final Set< String > oldSet = directMentionsFor( oldBody, mentioningLogin );
        final Set< String > newSet = directMentionsFor( newBody, mentioningLogin );
        final Set< String > added = new HashSet<>( newSet ); added.removeAll( oldSet );
        final Set< String > removed = new HashSet<>( oldSet ); removed.removeAll( newSet );
        for ( final String m : added ) insert( c, commentId, m, mentioningLogin, false );
        if ( !removed.isEmpty() ) {
            try ( PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM comment_mentions WHERE comment_id = ? AND mentioned_login = ? " +
                    "AND is_owner_mention = FALSE" ) ) {
                for ( final String m : removed ) {
                    ps.setObject( 1, commentId );
                    ps.setString( 2, m );
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    /** All rows on a given comment, ordered by created_at. (Read-side helper.) */
    public List< Mention > findByComment( final UUID commentId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT id, comment_id, mentioned_login, mentioning_login, is_owner_mention, " +
                      "created_at, read_at FROM comment_mentions WHERE comment_id = ? " +
                      "ORDER BY created_at" ) ) {
            ps.setObject( 1, commentId );
            return readRows( ps );
        } catch ( final SQLException e ) {
            LOG.warn( "findByComment({}) failed: {}", commentId, e.getMessage(), e );
            return List.of();
        }
    }

    /** Mark one row read iff {@code requester} owns it; returns whether updated. */
    public boolean markRead( final UUID mentionId, final String requester ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comment_mentions SET read_at = CURRENT_TIMESTAMP " +
                      "WHERE id = ? AND mentioned_login = ? AND read_at IS NULL" ) ) {
            ps.setObject( 1, mentionId );
            ps.setString( 2, requester );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "markRead({}) failed: {}", mentionId, e.getMessage(), e );
            return false;
        }
    }

    public int markAllRead( final String requester ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comment_mentions SET read_at = CURRENT_TIMESTAMP " +
                      "WHERE mentioned_login = ? AND read_at IS NULL" ) ) {
            ps.setString( 1, requester );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "markAllRead({}) failed: {}", requester, e.getMessage(), e );
            return 0;
        }
    }

    public int unreadCount( final String mentionedLogin ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT COUNT(*) FROM comment_mentions WHERE mentioned_login = ? AND read_at IS NULL" ) ) {
            ps.setString( 1, mentionedLogin );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "unreadCount({}) failed: {}", mentionedLogin, e.getMessage(), e );
            return 0;
        }
    }

    /** Resolved (existing-user-only) direct mentions in {@code body}, minus the author. */
    private Set< String > directMentionsFor( final String body, final String author ) {
        final Set< String > tokens = MentionExtractor.parse( body );
        final Set< String > resolved = MentionExtractor.resolve( tokens, userExists );
        resolved.remove( author );
        return resolved;
    }

    private static void insert( final Connection c, final UUID commentId,
                                final String mentionedLogin, final String mentioningLogin,
                                final boolean isOwnerMention ) throws SQLException {
        // ON CONFLICT via the unique constraint — H2 + PG support MERGE.
        try ( PreparedStatement ps = c.prepareStatement(
                "MERGE INTO comment_mentions (id, comment_id, mentioned_login, mentioning_login, is_owner_mention) " +
                "KEY(comment_id, mentioned_login) VALUES (?, ?, ?, ?, ?)" ) ) {
            ps.setObject( 1, UUID.randomUUID() );
            ps.setObject( 2, commentId );
            ps.setString( 3, mentionedLogin );
            ps.setString( 4, mentioningLogin );
            ps.setBoolean( 5, isOwnerMention );
            ps.executeUpdate();
        }
    }

    private static List< Mention > readRows( final PreparedStatement ps ) throws SQLException {
        final List< Mention > out = new ArrayList<>();
        try ( ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                final Timestamp createdAt = rs.getTimestamp( "created_at" );
                final Timestamp readAt    = rs.getTimestamp( "read_at" );
                out.add( new Mention(
                        (UUID) rs.getObject( "id" ),
                        (UUID) rs.getObject( "comment_id" ),
                        rs.getString( "mentioned_login" ),
                        rs.getString( "mentioning_login" ),
                        rs.getBoolean( "is_owner_mention" ),
                        createdAt == null ? null : createdAt.toInstant(),
                        readAt    == null ? null : readAt.toInstant() ) );
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=MentionServiceTest`
Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionService.java \
        wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionServiceTest.java
git commit -m "feat(comments): MentionService with transactional create/reply/edit + read state"
```

---

## Task 6: Mentions feed DAO (TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionFeedDao.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionFeedDaoTest.java`

Composes mentions with their thread + page context for the feed view. Lives next to `MentionService` for cohesion; tests use H2 with stub `comments`/`comment_threads` rows.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.comments.mentions;

import com.wikantik.api.comments.MentionFeedItem;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MentionFeedDaoTest {

    private DataSource ds;
    private MentionFeedDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:mfd;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE comment_threads (
                    id UUID PRIMARY KEY, canonical_id TEXT NOT NULL,
                    anchor_exact TEXT NOT NULL, anchor_prefix TEXT, anchor_suffix TEXT,
                    status TEXT NOT NULL DEFAULT 'open', created_by TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    resolved_by TEXT, resolved_at TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( """
                CREATE TABLE comments (
                    id UUID PRIMARY KEY,
                    thread_id UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE,
                    author TEXT NOT NULL, body TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    edited_at TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( """
                CREATE TABLE comment_mentions (
                    id UUID PRIMARY KEY,
                    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
                    mentioned_login TEXT NOT NULL, mentioning_login TEXT NOT NULL,
                    is_owner_mention BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    read_at TIMESTAMP WITH TIME ZONE
                )""" );
        }
        this.dao = new MentionFeedDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE comment_mentions" );
            s.executeUpdate( "DROP TABLE comments" );
            s.executeUpdate( "DROP TABLE comment_threads" );
        }
    }

    private UUID seedThread( final String canonicalId ) throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comment_threads (id, canonical_id, anchor_exact, created_by) " +
                    "VALUES ('" + id + "','" + canonicalId + "','quoted','alice')" );
        }
        return id;
    }

    private UUID seedComment( final UUID threadId, final String author, final String body ) throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comments (id, thread_id, author, body) " +
                    "VALUES ('" + id + "','" + threadId + "','" + author + "','" + body + "')" );
        }
        return id;
    }

    private void seedMention( final UUID commentId, final String mentioned,
                              final String mentioning, final boolean isOwner ) throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO comment_mentions (id, comment_id, mentioned_login, mentioning_login, is_owner_mention) " +
                    "VALUES ('" + UUID.randomUUID() + "','" + commentId + "','" + mentioned + "','" + mentioning + "'," + isOwner + ")" );
        }
    }

    @Test
    void list_returns_only_callers_mentions_newest_first() throws Exception {
        final UUID t = seedThread( "CID-1" );
        final UUID c1 = seedComment( t, "alice", "hello @bob friend" );
        final UUID c2 = seedComment( t, "alice", "@bob still here" );
        seedMention( c1, "bob",   "alice", false );
        seedMention( c2, "bob",   "alice", false );
        seedMention( c1, "carol", "alice", false );  // not for bob

        final List< MentionFeedItem > items = dao.list( "bob", MentionFeedDao.Status.ALL, 50, Optional.empty() );
        assertEquals( 2, items.size() );
        assertEquals( c2, items.get( 0 ).commentId(), "newest first" );
        assertTrue( items.get( 0 ).snippet().contains( "@bob still here" ) );
    }

    @Test
    void list_unread_filter_excludes_read_rows() throws Exception {
        final UUID t = seedThread( "CID-1" );
        final UUID c = seedComment( t, "alice", "@bob" );
        seedMention( c, "bob", "alice", false );

        // Mark read.
        try ( Connection c2 = ds.getConnection(); Statement s = c2.createStatement() ) {
            s.executeUpdate( "UPDATE comment_mentions SET read_at = CURRENT_TIMESTAMP WHERE mentioned_login = 'bob'" );
        }
        assertEquals( 1, dao.list( "bob", MentionFeedDao.Status.ALL, 50, Optional.empty() ).size() );
        assertEquals( 0, dao.list( "bob", MentionFeedDao.Status.UNREAD, 50, Optional.empty() ).size() );
    }

    @Test
    void list_pagination_via_before_cursor() throws Exception {
        final UUID t = seedThread( "CID-1" );
        for ( int i = 0; i < 5; i++ ) {
            final UUID c = seedComment( t, "alice", "msg " + i + " @bob" );
            seedMention( c, "bob", "alice", false );
            Thread.sleep( 5 );  // ensure ordered created_at
        }
        final List< MentionFeedItem > first = dao.list( "bob", MentionFeedDao.Status.ALL, 2, Optional.empty() );
        assertEquals( 2, first.size() );
        final Instant cursor = first.get( first.size() - 1 ).mentionedAt();
        final List< MentionFeedItem > second = dao.list( "bob", MentionFeedDao.Status.ALL, 2, Optional.of( cursor ) );
        assertEquals( 2, second.size() );
        assertNotEquals( first.get( 0 ).id(), second.get( 0 ).id() );
    }

    @Test
    void snippet_is_truncated_to_160_chars() throws Exception {
        final UUID t = seedThread( "CID-1" );
        final String body = "x".repeat( 500 ) + " @bob";
        final UUID c = seedComment( t, "alice", body );
        seedMention( c, "bob", "alice", false );
        final MentionFeedItem it = dao.list( "bob", MentionFeedDao.Status.ALL, 1, Optional.empty() ).get( 0 );
        assertTrue( it.snippet().length() <= 160 );
    }
}
```

- [ ] **Step 2: Run, verify it fails to compile**

Run: `mvn -q -pl wikantik-main test-compile -Dtest=MentionFeedDaoTest`
Expected: `cannot find symbol: class MentionFeedDao`.

- [ ] **Step 3: Implement**

```java
package com.wikantik.comments.mentions;

import com.wikantik.api.comments.MentionFeedItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-side for the per-user "My mentions" feed. Joins comment_mentions
 *  with comments + comment_threads to produce the page-aware feed item.
 *  Page-name resolution from canonical_id is left to the caller (REST). */
public class MentionFeedDao {

    public enum Status { UNREAD, ALL }

    private static final int SNIPPET_MAX = 160;
    private static final Logger LOG = LogManager.getLogger( MentionFeedDao.class );

    private final DataSource ds;

    public MentionFeedDao( final DataSource ds ) {
        this.ds = ds;
    }

    public List< MentionFeedItem > list( final String mentionedLogin, final Status status,
                                         final int limit, final Optional< Instant > before ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.created_at AS mentioned_at, m.read_at, m.mentioning_login, " +
                "       m.is_owner_mention, c.id AS comment_id, c.thread_id, c.body, t.canonical_id " +
                "FROM comment_mentions m " +
                "JOIN comments c ON c.id = m.comment_id " +
                "JOIN comment_threads t ON t.id = c.thread_id " +
                "WHERE m.mentioned_login = ?" );
        if ( status == Status.UNREAD ) sql.append( " AND m.read_at IS NULL" );
        if ( before.isPresent() )      sql.append( " AND m.created_at < ?" );
        sql.append( " ORDER BY m.created_at DESC LIMIT ?" );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
            int idx = 1;
            ps.setString( idx++, mentionedLogin );
            if ( before.isPresent() ) ps.setTimestamp( idx++, Timestamp.from( before.get() ) );
            ps.setInt( idx, limit );

            final List< MentionFeedItem > out = new ArrayList<>();
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final Timestamp at = rs.getTimestamp( "mentioned_at" );
                    final Timestamp ra = rs.getTimestamp( "read_at" );
                    final String body = rs.getString( "body" );
                    final String snippet = body == null ? ""
                            : ( body.length() <= SNIPPET_MAX ? body : body.substring( 0, SNIPPET_MAX ) );
                    out.add( new MentionFeedItem(
                            (UUID) rs.getObject( "id" ),
                            (UUID) rs.getObject( "thread_id" ),
                            (UUID) rs.getObject( "comment_id" ),
                            rs.getString( "canonical_id" ),
                            null, // pageName composed at REST layer
                            snippet,
                            rs.getString( "mentioning_login" ),
                            rs.getBoolean( "is_owner_mention" ),
                            at == null ? null : at.toInstant(),
                            ra == null ? null : ra.toInstant() ) );
                }
            }
            return out;
        } catch ( final SQLException e ) {
            LOG.warn( "MentionFeedDao.list({}) failed: {}", mentionedLogin, e.getMessage(), e );
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=MentionFeedDaoTest`
Expected: 4/4 PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionFeedDao.java \
        wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionFeedDaoTest.java
git commit -m "feat(comments): MentionFeedDao for per-user feed list"
```

---

## Task 7: Wire DAOs into PersistenceSubsystem.Services

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java`

This task adds `PageOwnerService`, `MentionService`, and `MentionFeedDao` to the persistence record, so REST resources can reach them via `getSubsystems().persistence().*`. `PageOwnerService` and `MentionService` both need a `Predicate<String> userExists`; `PageOwnerService` also needs a `Function<String, Optional<String>> authorResolver`. Both predicates need lazy access to managers built later in the boot sequence — easiest is to pass closures.

- [ ] **Step 1: Add the three components to the `Services` record (last positions)**

In `PersistenceSubsystem.java`, add imports:
```java
import com.wikantik.comments.PageOwnerService;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.comments.mentions.MentionFeedDao;
```
and append three components to the record:
```java
    PageOwnerService pageOwners,
    MentionService mentions,
    MentionFeedDao mentionFeed
```

- [ ] **Step 2: Construct them in the factory**

In `PersistenceSubsystemFactory.create(...)`, append imports + extend the construction. Because `Predicate<String> userExists` reaches the auth subsystem which is built later, take the predicate from `Deps`:

In `PersistenceSubsystem.Deps`, add component `Predicate<String> userExistsLookup` and `Function<String, Optional<String>> pageAuthorLookup` (with the imports). In `WikiEngine.initSubsystems()` (or whichever wiring file builds `Deps`), set those closures after the auth + page-graph subsystems are built (i.e., compose the persistence services *after* those, OR pass deferred suppliers).

If the bootstrap order doesn't allow composing persistence after auth, an alternative: use `Supplier<Predicate<String>>` indirection. Read `WikiEngine.initSubsystems()` to confirm the order before settling on a shape.

Concrete factory edit:
```java
        return new PersistenceSubsystem.Services(
            ds,
            // ... existing components ...
            new TrustedAuthorsDao( ds ),
            new CommentStore( ds ),
            new PageOwnerService( ds, deps.userExistsLookup(), deps.pageAuthorLookup() ),
            new MentionService( ds, deps.userExistsLookup() ),
            new MentionFeedDao( ds )
        );
```

- [ ] **Step 3: Fix every other positional `new PersistenceSubsystem.Services(` call site**

Run: `grep -rn "new PersistenceSubsystem.Services(" --include=*.java .`
For each non-factory call site (test builders, fakes), append three trailing arguments. Tests that don't exercise mentions/ownership can pass `null` for each.

- [ ] **Step 4: Test-compile**

Run: `mvn -q -pl wikantik-main test-compile`
Expected: BUILD SUCCESS.

If wiring of `Deps.userExistsLookup` / `Deps.pageAuthorLookup` causes initialization-order issues, fall back to a `Supplier`-wrapped approach: `Deps` holds `Supplier<Predicate<String>>`, the services call `supplier.get().test(...)` lazily.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java \
        wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(comments): expose PageOwnerService, MentionService, MentionFeedDao"
```

(Adjust `git add` list to actual modified files — the WikiEngine path is likely; verify with `git status`.)

---

## Task 8: PageOwnershipSaveFilter

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/comments/PageOwnershipSaveFilter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/PageOwnershipSaveFilterTest.java`

A `BasicPageFilter` that, after each page save, ensures the page has an owner row. Model after `StructuralSpinePageFilter` (read it: `wikantik-main/.../pagegraph/spine/StructuralSpinePageFilter.java`). Toggle property: `wikantik.page_ownership.enforcement.enabled`, default `true`.

- [ ] **Step 1: Write the failing test** (mock the service; assert getOwner called with the right canonical_id after a postSave hook).

The exact test code mirrors a thin filter:
```java
package com.wikantik.comments;

import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.verify;

class PageOwnershipSaveFilterTest {

    @Test
    void postSave_calls_getOwner_with_canonical_id_resolved_from_slug() {
        final PageOwnerService svc = Mockito.mock( PageOwnerService.class );
        final java.util.function.Function< String, java.util.Optional< String > > slugToCanonical =
                slug -> java.util.Optional.of( "CID-" + slug );
        final PageOwnershipSaveFilter f = new PageOwnershipSaveFilter( svc, slugToCanonical, true );

        final Page page = Mockito.mock( Page.class );
        Mockito.when( page.getName() ).thenReturn( "MyPage" );

        f.postSave( null, page, "any content" );

        verify( svc ).getOwner( "CID-MyPage" );
    }

    @Test
    void disabled_filter_does_nothing() {
        final PageOwnerService svc = Mockito.mock( PageOwnerService.class );
        final PageOwnershipSaveFilter f = new PageOwnershipSaveFilter( svc, s -> java.util.Optional.of( "CID" ), false );
        final Page page = Mockito.mock( Page.class );
        f.postSave( null, page, "x" );
        Mockito.verifyNoInteractions( svc );
    }
}
```

(Adjust `Page` and `postSave` signature to match the actual filter API in `wikantik-main` — see `StructuralSpinePageFilter` for exact ancestor + method.)

- [ ] **Step 2: Run, verify it fails**

Run: `mvn -q -pl wikantik-main test-compile -Dtest=PageOwnershipSaveFilterTest`
Expected: `cannot find symbol: class PageOwnershipSaveFilter`.

- [ ] **Step 3: Implement** following the `BasicPageFilter` template from `StructuralSpinePageFilter`. The filter calls `svc.getOwner(canonicalId)` (which find-or-creates) in `postSave`. Read the existing filter for engine/properties wiring conventions and replicate exactly. Register the filter where `StructuralSpinePageFilter` is registered (find with `grep -rn "StructuralSpinePageFilter" wikantik-main/src/main/java/`).

- [ ] **Step 4: Run, verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=PageOwnershipSaveFilterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/PageOwnershipSaveFilter.java \
        wikantik-main/src/test/java/com/wikantik/comments/PageOwnershipSaveFilterTest.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "feat(comments): PageOwnershipSaveFilter ensures every saved page has an owner row"
```

---

## Task 9: Extend CommentStore to call MentionService transactionally

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/comments/CommentStore.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/comments/CommentStoreTest.java`

`CommentStore.createThread` already opens a transaction with `setAutoCommit(false)`. Extend it to take `MentionService` + the resolved page owner, and call `recordCreate` inside the same transaction. Same idea for `addComment` (single-statement transaction implicit; wrap in explicit one). For `editComment`, wrap the UPDATE + `recordEdit` in a transaction.

- [ ] **Step 1: Update CommentStore method signatures**

Change the signatures:
```java
public CommentThread createThread( String canonicalId, TextQuoteSelector anchor,
                                   String author, String body,
                                   MentionService mentionSvc, Optional<String> ownerForMention )
```
where `ownerForMention` is `Optional.of(getOwner(canonicalId))` when `ownerForMention != author`, else `Optional.empty()` — caller computes.

```java
public Comment addComment( UUID threadId, String author, String body, MentionService mentionSvc )
public Optional<Comment> editComment( UUID commentId, String oldBody, String newBody, String mentioningLogin, MentionService mentionSvc )
```

Inside each method, call the appropriate MentionService method on the same `Connection` as the inserts/updates.

For `editComment`, the existing impl is a single UPDATE; convert to explicit transaction:
```java
c.setAutoCommit(false);
try {
    /* UPDATE comments SET body=?, edited_at=NOW() WHERE id=? */
    mentionSvc.recordEdit( c, commentId, mentioningLogin, oldBody, newBody );
    c.commit();
} catch ( SQLException e ) { c.rollback(); throw e; }
finally { c.setAutoCommit(true); }
```

- [ ] **Step 2: Update existing CommentStoreTest call sites**

Every existing call (`createThread`, `addComment`, `editComment`) now needs `MentionService` (and `oldBody` for edit). Build a real `MentionService` against the test's H2 (the test's `comment_mentions` table needs to be created in `@BeforeEach`). Owner-mention is `Optional.empty()` for these tests (mentions covered separately in their own test class).

- [ ] **Step 3: Run, verify the existing tests still pass**

Run: `mvn -q -pl wikantik-main test -Dtest=CommentStoreTest`
Expected: 8/8 PASS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/CommentStore.java \
        wikantik-main/src/test/java/com/wikantik/comments/CommentStoreTest.java
git commit -m "feat(comments): CommentStore writes mentions transactionally"
```

---

## Task 10: Extend CommentThreadResource (call MentionService on create/reply/edit)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java`

Add two protected seams: `mentionService()` and `pageOwnerService()`, mirroring the existing `commentStore()` seam.

```java
protected MentionService mentionService() {
    return getSubsystems().persistence().mentions();
}
protected PageOwnerService pageOwnerService() {
    return getSubsystems().persistence().pageOwners();
}
```

In `createThread`, after resolving canonicalId and BEFORE calling `commentStore().createThread`, compute the owner:
```java
final String owner = pageOwnerService().getOwner( canonicalId.get() );
final Optional<String> ownerForMention =
    owner.equals( currentUser(request) ) ? Optional.empty() : Optional.of( owner );

final CommentThread t = commentStore().createThread(
    canonicalId.get(), anchor, currentUser(request), text,
    mentionService(), ownerForMention );
```

`addReply`: pass `mentionService()` through.
`editComment`: fetch the existing comment's body via `findComment(commentId)`, then call the extended `editComment` with old + new.

Add 3 unit tests to CommentThreadResourceTest:

```java
@Test
void create_thread_with_an_at_mention_records_a_mention() throws Exception {
    final JsonObject body = new JsonObject();
    body.addProperty( "exact", "hello" );
    body.addProperty( "text", "ping @bob" );
    // stub the user predicate via MentionService's H2 (bob exists in setUp)
    final String threadId = post( "?page=PageOne", null, body ).get( "id" ).getAsString();
    final UUID commentId = store.findThread( UUID.fromString( threadId ) ).orElseThrow()
            .comments().get( 0 ).id();
    assertEquals( 1, mentionService.findByComment( commentId ).size() );
}

@Test
void create_thread_writes_owner_mention_when_owner_differs_from_author() throws Exception {
    // Stub pageOwnerService().getOwner -> "carol"; currentUser -> "alice".
    Mockito.doReturn( "carol" ).when( pageOwnerSvc ).getOwner( "CID1" );
    final JsonObject body = new JsonObject();
    body.addProperty( "exact", "hello" );
    body.addProperty( "text", "fyi" );
    final String threadId = post( "?page=PageOne", null, body ).get( "id" ).getAsString();
    final UUID commentId = store.findThread( UUID.fromString( threadId ) ).orElseThrow()
            .comments().get( 0 ).id();
    final var mentions = mentionService.findByComment( commentId );
    assertEquals( 1, mentions.size() );
    assertEquals( "carol", mentions.get( 0 ).mentionedLogin() );
    assertTrue( mentions.get( 0 ).isOwnerMention() );
}

@Test
void edit_diffs_mentions_preserving_read_state_on_survivors() throws Exception {
    /* create with @bob @carol, mark bob read, edit to @bob only, assert carol gone + bob read_at preserved */
}
```

In test setUp, instantiate a real `MentionService` against the existing H2 ds (add a `comment_mentions` CREATE TABLE block), stub `mentionService()` / `pageOwnerService()` on the spy.

- [ ] Run, fix, commit:

```bash
mvn -q -pl wikantik-rest test -Dtest=CommentThreadResourceTest   # expect all green
git add wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java
git commit -m "feat(comments): CommentThreadResource records mentions on create/reply/edit"
```

---

## Task 11: MentionableUsersResource (TDD)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/MentionableUsersResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/MentionableUsersResourceTest.java`

`GET /api/users/mentionable?q=&limit=` returns `{users:[{loginName,fullName}]}`. Logged-in only. Prefix-match on `login_name` + `full_name`, case-insensitive, locked users excluded, limit ≤ 10 (default 8). Uses `getSubsystems().auth().users().getUserDatabase()`.

The resource extends `RestServletBase`; mirror `CommentThreadResource` for the seam + permission patterns. There's no per-page permission to check; just require an authenticated session. Use `Wiki.session().find(engine, request).isAuthenticated()`.

```java
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MentionableUsersResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 10;

    protected UserDatabase users() {
        return getSubsystems().auth().users().getUserDatabase();
    }

    protected boolean isAuthenticated( HttpServletRequest request ) {
        final Engine engine = getEngine();
        final Session s = Wiki.session().find( engine, request );
        return s != null && s.isAuthenticated();
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        if ( !isAuthenticated( request ) ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Login required" );
            return;
        }
        final String qRaw = request.getParameter( "q" );
        final String q = qRaw == null ? "" : qRaw.trim().toLowerCase( Locale.ROOT );
        final int limit = clampLimit( request.getParameter( "limit" ) );

        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final UserProfile p : users().getWikiNames() ) {  // see note below
            if ( p.isLocked() ) continue;
            final String login = p.getLoginName().toLowerCase( Locale.ROOT );
            final String full  = p.getFullname() == null ? "" : p.getFullname().toLowerCase( Locale.ROOT );
            if ( q.isEmpty() || login.startsWith( q ) || full.contains( q ) ) {
                final Map< String, Object > row = new LinkedHashMap<>();
                row.put( "loginName", p.getLoginName() );
                row.put( "fullName", p.getFullname() );
                out.add( row );
                if ( out.size() >= limit ) break;
            }
        }
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "users", out );
        sendJson( response, body );
    }

    private int clampLimit( final String raw ) {
        if ( raw == null || raw.isBlank() ) return DEFAULT_LIMIT;
        try {
            return Math.max( 1, Math.min( MAX_LIMIT, Integer.parseInt( raw ) ) );
        } catch ( final NumberFormatException nfe ) {
            return DEFAULT_LIMIT;
        }
    }
}
```

> The exact method name for "list users" on `UserDatabase` may differ — look at `AdminUserResource.list(...)` (around lines 369-389 per the recon) and reuse the same enumeration. If the method is `getWikiNames()`/`getAll()`/etc., adapt.

Tests (Mockito):
- 401 when not authenticated.
- Prefix match on login.
- Case-insensitive match on fullName substring.
- Locked users excluded.
- Limit cap at 10; default 8.

- [ ] Run, fix, commit:

```bash
mvn -q -pl wikantik-rest test -Dtest=MentionableUsersResourceTest   # expect green
git add wikantik-rest/src/main/java/com/wikantik/rest/MentionableUsersResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MentionableUsersResourceTest.java
git commit -m "feat(comments): MentionableUsersResource for @-picker autocomplete"
```

---

## Task 12: MyMentionsResource (TDD)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/MyMentionsResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/MyMentionsResourceTest.java`

Endpoints:
- `GET /api/me/mentions?status=unread|all&limit=&before=` → list (page-name resolution via `StructuralIndexService`).
- `GET /api/me/mentions/unread-count` → `{count: N}`.
- `POST /api/me/mentions/{id}/read` → mark one (403 if not yours).
- `POST /api/me/mentions/mark-all-read` → returns `{updated: N}`.

Use the same seam pattern as `CommentThreadResource`: protected `mentionService()`, `mentionFeed()`, `currentUser(request)`, plus `slugForCanonicalId(...)` for the list endpoint (compose `pageName` from canonical_id via `StructuralIndexService.resolveSlugFromCanonicalId`).

Route in `doGet`:
- pathInfo null or `/` → list.
- pathInfo `/unread-count` → unread count.

Route in `doPost`:
- pathInfo `/{id}/read` → mark one read (verify the row's `mentioned_login == currentUser` via a guard in MentionService — already handled there with the WHERE clause).
- pathInfo `/mark-all-read` → mark all.

Provide complete Mockito test coverage for each branch (auth required, status filter, limit cap at 50, cursor pagination, mark-one 403 path, mark-all returns count).

- [ ] Run, fix, commit:

```bash
mvn -q -pl wikantik-rest test -Dtest=MyMentionsResourceTest
git add wikantik-rest/src/main/java/com/wikantik/rest/MyMentionsResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MyMentionsResourceTest.java
git commit -m "feat(comments): MyMentionsResource feed + read state endpoints"
```

---

## Task 13: AdminPageOwnershipResource (TDD)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminPageOwnershipResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminPageOwnershipResourceTest.java`

Endpoints (all under `/admin/page-ownership/*`, gated by `AdminAuthFilter` for `AllPermission`):
- `GET ?filter=orphaned&limit=&offset=` → orphaned list.
- `GET ?filter=by-owner&owner=&limit=&offset=` → by-owner list (special: `owner=<orphaned>` is equivalent to `filter=orphaned`).
- `POST /reassign` body `{pages: [canonical_id,...], newOwner: "login"}` → per-page bulk; validates `newOwner` exists.
- `POST /reassign-by-user` body `{fromOwner, toOwner}` → bulk-move all pages; special value `fromOwner="<orphaned>"` matches NULL.

Seam: `pageOwners()`, `userExists(login)`, `currentUser(req)` (for `assigned_by`).

Tests:
- 400 on missing filter / bad newOwner.
- Orphan list pagination.
- By-owner list with `<orphaned>` sentinel routes through `listOrphaned`.
- Reassign: writes new owner; 400 when newOwner doesn't exist.
- Reassign-by-user: bulk update; uses `bulkReassign` for normal `fromOwner`, `orphanByOwner` when `toOwner="<orphaned>"`.
- `<orphaned>` sentinel in fromOwner routes through a NULL match (add a tiny helper in PageOwnerService if needed: `int reassignFromOrphaned(String toOwner, String assignedBy)`).

- [ ] Run, fix, commit:

```bash
mvn -q -pl wikantik-rest test -Dtest=AdminPageOwnershipResourceTest
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminPageOwnershipResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminPageOwnershipResourceTest.java
git commit -m "feat(comments): AdminPageOwnershipResource for orphan list + reassignment"
```

---

## Task 14: Extend AdminUserResource (orphan on delete)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java`

Add a seam `pageOwners()` returning `getSubsystems().persistence().pageOwners()`. In both `handleDeleteUser(loginName)` (~line 498) and the bulk-action path `tryDeleteUser(loginName)` (~line 303), **before** calling `getUserDatabase().deleteByLoginName(loginName)`:

```java
pageOwners().orphanByOwner( loginName, "system:user-deleted:" + loginName );
```

Test: verify the orphan call happens before the delete, with the correct args. Use Mockito's `InOrder` to assert the sequence.

- [ ] Run, fix, commit:

```bash
mvn -q -pl wikantik-rest test -Dtest=AdminUserResourceTest
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java
git commit -m "feat(comments): orphan owned pages before deleting a user"
```

---

## Task 15: web.xml mappings

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

Add three new servlet declarations and three mappings:

```xml
   <servlet>
       <servlet-name>MentionableUsersResource</servlet-name>
       <servlet-class>com.wikantik.rest.MentionableUsersResource</servlet-class>
   </servlet>
   <servlet>
       <servlet-name>MyMentionsResource</servlet-name>
       <servlet-class>com.wikantik.rest.MyMentionsResource</servlet-class>
   </servlet>
   <servlet>
       <servlet-name>AdminPageOwnershipResource</servlet-name>
       <servlet-class>com.wikantik.rest.AdminPageOwnershipResource</servlet-class>
   </servlet>
```

```xml
   <servlet-mapping>
       <servlet-name>MentionableUsersResource</servlet-name>
       <url-pattern>/api/users/mentionable/*</url-pattern>
   </servlet-mapping>
   <servlet-mapping>
       <servlet-name>MyMentionsResource</servlet-name>
       <url-pattern>/api/me/mentions/*</url-pattern>
   </servlet-mapping>
   <servlet-mapping>
       <servlet-name>AdminPageOwnershipResource</servlet-name>
       <url-pattern>/admin/page-ownership/*</url-pattern>
   </servlet-mapping>
```

Use the indentation style of the surrounding servlet entries (3-space tag, 7-space inner). Place near the other admin/REST mappings.

- [ ] **Step 1: Compile**

Run: `mvn -q -pl wikantik-rest test-compile && mvn -q -pl wikantik-war package -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Commit**

```bash
git add wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(comments): register mentions + admin-page-ownership servlets"
```

---

## Task 16: Wire-level IT extension

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CommentThreadIT.java`

Append a new test (after `list_without_page_param_is_bad_request`) that exercises the mention path end-to-end:

```java
@Test
@Order( 3 )
void mention_in_comment_appears_in_my_mentions_feed() throws Exception {
    seedPage( "MentionITPage", "the quick brown fox jumps" );
    // login as janne (admin); body mentions admin so admin's feed gets a row.
    final JsonObject body = new JsonObject();
    body.addProperty( "exact", "quick brown" );
    body.addProperty( "text", "@admin please review" );
    final HttpResponse<String> create = post( "/api/comment-threads?page=MentionITPage", body.toString() );
    assertEquals( 200, create.statusCode(), create.body() );

    // login as admin and read the feed.
    login( "admin", "admin" );
    final HttpResponse<String> feed = get( "/api/me/mentions?status=unread" );
    assertEquals( 200, feed.statusCode() );
    assertTrue( feed.body().contains( "@admin please review" ),
            "the feed snippet should contain the comment body: " + feed.body() );

    // unread-count > 0.
    final HttpResponse<String> count = get( "/api/me/mentions/unread-count" );
    assertEquals( 200, count.statusCode() );
    assertTrue( count.body().matches( ".*\"count\"\\s*:\\s*[1-9].*" ),
            "unread-count should be >0: " + count.body() );
}
```

If `seedPage` and `login` helpers don't already exist in this file, lift them from `RestApiIT`. Use `await` after creates to let the structural index settle (mirror existing patterns in the file).

- [ ] **Step 1: Run the IT**

```bash
mvn install -DskipTests -pl wikantik-main,wikantik-rest,wikantik-war -am
mvn verify -pl wikantik-it-tests/wikantik-it-test-rest -Pintegration-tests -Dit.test=CommentThreadIT
```
Expected: 3 tests pass.

- [ ] **Step 2: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CommentThreadIT.java
git commit -m "test(comments): wire-level IT for @-mention -> /api/me/mentions feed"
```

---

## Task 17: Frontend API client methods

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`
- Modify: `wikantik-frontend/src/api/client.test.js`

- [ ] **Step 1: Add the new methods**

After the existing `deleteCommentThread` and inside the `admin` namespace (find the spot via `grep -n "admin\." src/api/client.js | head`):

```js
  // Mentions + mentionable users
  listMentionableUsers: (q, limit = 8) =>
    request(`/api/users/mentionable?q=${encodeURIComponent(q)}&limit=${limit}`),
  listMyMentions: ({ status = 'unread', limit = 25, before } = {}) =>
    request(`/api/me/mentions?status=${encodeURIComponent(status)}&limit=${limit}`
      + (before ? `&before=${encodeURIComponent(before)}` : '')),
  getMyMentionsUnreadCount: () =>
    request('/api/me/mentions/unread-count'),
  markMentionRead: (id) =>
    request(`/api/me/mentions/${encodeURIComponent(id)}/read`, { method: 'POST' }),
  markAllMentionsRead: () =>
    request('/api/me/mentions/mark-all-read', { method: 'POST' }),
```

In the `admin` namespace, add:

```js
    pageOwnership: {
      listOrphaned: ({ limit = 50, offset = 0 } = {}) =>
        request(`/admin/page-ownership?filter=orphaned&limit=${limit}&offset=${offset}`),
      listByOwner: (owner, { limit = 50, offset = 0 } = {}) =>
        request(`/admin/page-ownership?filter=by-owner&owner=${encodeURIComponent(owner)}&limit=${limit}&offset=${offset}`),
      reassign: (pages, newOwner) =>
        request('/admin/page-ownership/reassign', {
          method: 'POST',
          body: JSON.stringify({ pages, newOwner }),
        }),
      reassignByUser: (fromOwner, toOwner) =>
        request('/admin/page-ownership/reassign-by-user', {
          method: 'POST',
          body: JSON.stringify({ fromOwner, toOwner }),
        }),
    },
```

- [ ] **Step 2: Add client tests** mirroring the existing patterns in `client.test.js`:

```js
describe('mentions methods', () => {
  /* ... 5 tests: listMentionableUsers GET, listMyMentions GET (with/without before),
     getMyMentionsUnreadCount GET, markMentionRead POST, markAllMentionsRead POST */
});

describe('admin.pageOwnership methods', () => {
  /* 4 tests: listOrphaned, listByOwner, reassign (POST + body), reassignByUser (POST + body) */
});
```

- [ ] **Step 3: Run, verify all suites pass**

```bash
cd wikantik-frontend && npx vitest run src/api/client.test.js
```
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/api/client.test.js
git commit -m "feat(comments): client methods for mentions + admin page-ownership"
```

---

## Task 18: useMentionPicker hook (TDD)

**Files:**
- Create: `wikantik-frontend/src/hooks/useMentionPicker.js`
- Test: `wikantik-frontend/src/hooks/useMentionPicker.test.js`

A pure-logic hook. Given a textarea ref and an `onChange` callback, it tracks the in-flight `@<token>` under the caret, debounces a fetch via `api.listMentionableUsers`, and exposes `{open, candidates, selectedIndex, onKeyDown, accept, position}`.

Test cases (~8): substring detection (last `@` segment), opens when caret is in a token, closes when whitespace appears, arrow keys navigate, Enter calls `accept(login)`, Tab calls `accept(login)`, Esc closes, blur closes.

- [ ] Write test → fail → implement → pass → commit.

```bash
git add wikantik-frontend/src/hooks/useMentionPicker.js wikantik-frontend/src/hooks/useMentionPicker.test.js
git commit -m "feat(comments): useMentionPicker hook for autocomplete in @-mentions"
```

> **Implementation note:** the hook should be UI-agnostic — it returns state + handlers, not JSX. The rendering component (Task 19) is a separate piece. Keep it that way for testability.

---

## Task 19: MentionPicker UI + integrate into CommentComposer and reply textarea

**Files:**
- Create: `wikantik-frontend/src/components/MentionPicker.jsx`
- Create: `wikantik-frontend/src/components/MentionPicker.test.jsx`
- Modify: `wikantik-frontend/src/components/PageView.jsx` (CommentComposer)
- Modify: `wikantik-frontend/src/components/CommentsDrawer.jsx` (reply textarea)
- Modify: `wikantik-frontend/src/styles/article.css`

`MentionPicker` is the dumb popover: receives `candidates`, `selectedIndex`, `position`, `onSelect`, renders the list. Both call sites pass their textarea ref into `useMentionPicker` and render `<MentionPicker {...pickerState} />` alongside their textareas.

Both call sites' `onChange` becomes:
```js
onChange={(e) => { setText(e.target.value); grow(e.target); picker.onChange(e); }}
```
and `onKeyDown` delegates to the picker first; if the picker handled it (Enter/Esc/Arrow), don't propagate.

CSS (`article.css`):
```css
.mention-picker {
  position: fixed; z-index: 70;
  background: var(--bg-elevated); color: var(--text);
  border: 1px solid var(--border-strong); border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0,0,0,0.18);
  min-width: 220px; max-width: 320px; padding: 4px 0;
  font-size: 13px;
}
.mention-picker-item { padding: 5px 10px; cursor: pointer; display: flex; gap: 8px; }
.mention-picker-item.active, .mention-picker-item:hover { background: var(--sage-light); }
.mention-picker-login { font-weight: 600; }
.mention-picker-fullname { color: var(--text-secondary); }
```

Tests for `MentionPicker` (~4): renders candidates, highlights `selectedIndex`, click calls `onSelect`, hidden when `open===false`.

PageView + CommentsDrawer tests get small additions: the composer/reply text area now closes the picker on submit and accepts via Enter inside the picker without firing the comment-submit.

- [ ] Run all related tests + full suite to confirm stability. Commit:

```bash
git add wikantik-frontend/src/components/MentionPicker.jsx \
        wikantik-frontend/src/components/MentionPicker.test.jsx \
        wikantik-frontend/src/components/PageView.jsx \
        wikantik-frontend/src/components/CommentsDrawer.jsx \
        wikantik-frontend/src/styles/article.css
git commit -m "feat(comments): @-picker in composer + reply textarea"
```

---

## Task 20: CommentBody chip renderer (TDD)

**Files:**
- Create: `wikantik-frontend/src/components/CommentBody.jsx`
- Create: `wikantik-frontend/src/components/CommentBody.test.jsx`
- Modify: `wikantik-frontend/src/components/CommentsDrawer.jsx`
- Modify: `wikantik-frontend/src/styles/article.css`

`CommentBody` splits the body string on `/@([A-Za-z0-9._-]+)/` and renders each `@<login>` as `<a className="comment-mention-chip" href={'/wiki/Users/' + encodeURIComponent(login)}>@{login}</a>`. Other text renders verbatim.

Replace `<span className="comment-body">{c.body}</span>` in `CommentsDrawer.jsx` with `<CommentBody body={c.body} />`.

CSS:
```css
.comment-mention-chip {
  display: inline-block; padding: 0 6px; margin: 0 1px;
  border-radius: 4px; background: var(--sage-light);
  color: var(--text); text-decoration: none; font-weight: 600;
}
.comment-mention-chip:hover { background: var(--border-strong); }
```

Tests (~5): plain text body renders untouched; single mention renders as chip with correct href; multiple mentions in one body; punctuation after token is not part of the chip; unknown chars (e.g. `@!`) are ignored.

- [ ] Run, commit:

```bash
git add wikantik-frontend/src/components/CommentBody.jsx \
        wikantik-frontend/src/components/CommentBody.test.jsx \
        wikantik-frontend/src/components/CommentsDrawer.jsx \
        wikantik-frontend/src/styles/article.css
git commit -m "feat(comments): CommentBody renders @-mentions as chips linking to /wiki/Users/<login>"
```

---

## Task 21: useUnreadMentions hook + Sidebar badge

**Files:**
- Create: `wikantik-frontend/src/hooks/useUnreadMentions.js`
- Create: `wikantik-frontend/src/hooks/useUnreadMentions.test.js`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx`
- Modify: `wikantik-frontend/src/components/Sidebar.test.jsx` (if it exists; else create one)
- Modify: `wikantik-frontend/src/styles/article.css` (or wherever sidebar styles live — check `Sidebar.jsx`'s imports)

Hook fetches `api.getMyMentionsUnreadCount()` on mount, on `document.visibilitychange` (when visible), and on a manual refresh call. Returns `{count, refresh}`.

In `Sidebar.jsx`, in the authenticated section (after the "+ New Article" link at ~line 92), add:

```jsx
{user?.authenticated && (
  <Link to="/me/mentions" className="sidebar-mentions-link" onClick={onMobileClose}>
    My mentions
    {unreadCount > 0 && <span className="sidebar-mentions-badge">{unreadCount}</span>}
  </Link>
)}
```

where `const { count: unreadCount } = useUnreadMentions();` is called near the existing hooks at the top of Sidebar.

Test the hook (3-4 tests: initial fetch, visibility-change triggers refresh, manual refresh). Test the Sidebar conditionally rendering the badge with `count > 0`.

- [ ] Commit:

```bash
git add wikantik-frontend/src/hooks/useUnreadMentions.js \
        wikantik-frontend/src/hooks/useUnreadMentions.test.js \
        wikantik-frontend/src/components/Sidebar.jsx \
        wikantik-frontend/src/components/Sidebar.test.jsx \
        wikantik-frontend/src/styles/article.css
git commit -m "feat(comments): unread-mentions badge on the Sidebar 'My mentions' link"
```

---

## Task 22: MentionsPage component + route

**Files:**
- Create: `wikantik-frontend/src/components/MentionsPage.jsx`
- Create: `wikantik-frontend/src/components/MentionsPage.test.jsx`
- Modify: `wikantik-frontend/src/main.jsx` (add `<Route path="/me/mentions" element={<MentionsPage />} />` under the authenticated routes block)

MentionsPage:
- Calls `api.listMyMentions({status, limit, before})` based on a status filter chip (Unread / All).
- Renders a list of feed items: `[•] @{mentionedBy} on {pageName} — "{snippet}" — {when}` + "View in context" link to `/wiki/{pageName}?thread={threadId}&comment={commentId}`.
- Owner-mention rows get a subtle "(your page)" tag.
- "Mark all read" button at top; per-row "×" mark-read.
- On every action, recompute the list + the unread count.

Tests (~5): renders feed items; filter chip toggle; mark-one updates state; mark-all updates state; deep-link href is correct.

- [ ] Commit:

```bash
git add wikantik-frontend/src/components/MentionsPage.jsx \
        wikantik-frontend/src/components/MentionsPage.test.jsx \
        wikantik-frontend/src/main.jsx
git commit -m "feat(comments): MentionsPage route + UI for the 'My mentions' feed"
```

---

## Task 23: Deep-link handling in PageView

**Files:**
- Modify: `wikantik-frontend/src/components/PageView.jsx`
- Modify: `wikantik-frontend/src/components/PageView.test.jsx`

When PageView mounts (or its URL params change), read `?thread=` and `?comment=`. After threads load:
- If `thread` matches a thread in `threads`, set `statusFilter='all'`, open the drawer, call `focusThread(thread)`.
- Strip the query params via `history.replaceState` so a refresh doesn't re-focus.

Use `useLocation` + `useSearchParams` from `react-router-dom` (the file already imports `useNavigate`).

Add a test in `PageView.test.jsx`:
```js
it('deep link ?thread=<id> opens the drawer and focuses the thread', async () => {
  // renderPageView with initial location '/wiki/Foo?thread=T1'
  // await stable loaded; assert drawer is open + thread T1 is focused
});
```

- [ ] Run full suite + 5x stability:

```bash
for i in 1 2 3 4 5; do npx vitest run src/components/PageView.test.jsx || echo "flake on $i"; done
```

- [ ] Commit:

```bash
git add wikantik-frontend/src/components/PageView.jsx wikantik-frontend/src/components/PageView.test.jsx
git commit -m "feat(comments): deep-link ?thread=&comment= opens drawer + focuses thread"
```

---

## Task 24: AdminPageOwnershipPage + admin nav

**Files:**
- Create: `wikantik-frontend/src/components/admin/AdminPageOwnershipPage.jsx`
- Create: `wikantik-frontend/src/components/admin/AdminPageOwnershipPage.test.jsx`
- Modify: `wikantik-frontend/src/main.jsx` (add `<Route path="page-ownership" element={<AdminPageOwnershipPage />} />` under the `/admin` block)
- Modify: `wikantik-frontend/src/components/admin/AdminSidebar.jsx` (add `{to: '/admin/page-ownership', label: 'Page Ownership'}`)

Mirror `AdminKgPolicyPage.jsx` exactly:
- `<AdminPage loading error>` shell.
- `<PageHeader title="Page Ownership" description="..." />`.
- Filter chips: Orphaned | By Owner.
- For "By Owner": text input that submits with Enter, calls `api.admin.pageOwnership.listByOwner`.
- `<AdminTable>` with columns: Page name (link), Current owner, Assigned by, Assigned at, Reassign action.
- Per-row "Reassign" opens a small in-app modal with the same user-picker behavior as the mentions picker (reuse `useMentionPicker` without the `@` trigger — call it with a freeform query input).
- Above the table, a "Reassign by user" form (two pickers + Submit).

Tests (~5): orphaned filter loads and renders rows; by-owner filter posts query; per-row reassign opens modal → submit → api call + reload; bulk reassign-by-user form.

- [ ] Commit:

```bash
git add wikantik-frontend/src/components/admin/AdminPageOwnershipPage.jsx \
        wikantik-frontend/src/components/admin/AdminPageOwnershipPage.test.jsx \
        wikantik-frontend/src/main.jsx \
        wikantik-frontend/src/components/admin/AdminSidebar.jsx
git commit -m "feat(comments): /admin/page-ownership page with reassignment UI"
```

---

## Task 25: Final verification

- [ ] **Step 1: Frontend full suite + build**

```bash
cd wikantik-frontend && npx vitest run && npm run build
```
Expected: all suites green, build succeeds.

- [ ] **Step 2: Backend unit build (parallel OK)**

```bash
cd .. && mvn clean install -T 1C -DskipITs
```
Expected: BUILD SUCCESS, RAT passes.

- [ ] **Step 3: Full IT reactor (sequential, no -T)**

```bash
mvn clean install -Pintegration-tests -fae
```
Expected: BUILD SUCCESS across all modules. Note that wikantik-main has known parallel-test flakes (e.g., XMLUserDatabaseTest); re-run any flaky test in isolation per CLAUDE.md.

- [ ] **Step 4: Manual smoke**

```bash
mvn clean install -Dmaven.test.skip -T 1C && bin/redeploy.sh
```
At `http://localhost:8080/`: log in, comment on a page mentioning `@admin`, log out, log in as admin, see the mention in `/me/mentions` (sidebar badge increments). Reassign ownership of the page via `/admin/page-ownership`. Delete a non-admin test user and confirm their pages become orphaned.

- [ ] **Step 5: Commit the plan document**

```bash
git add docs/superpowers/plans/2026-05-28-comment-mentions-and-ownership.md
git commit -m "docs(comments): implementation plan for mentions + page ownership"
```

---

## Self-review notes (for the implementer)

- **Spec coverage:** every section of the spec maps to a task — V034 (T1), API records (T2), PageOwnerService (T3), MentionExtractor (T4), MentionService (T5), MentionFeedDao (T6), persistence wiring (T7), save-time enforcement (T8), CommentStore transactional integration (T9), CommentThreadResource extension (T10), MentionableUsersResource (T11), MyMentionsResource (T12), AdminPageOwnershipResource (T13), delete-user integration (T14), web.xml (T15), wire-level IT (T16), client.js (T17), picker hook (T18), picker UI + integration (T19), chip renderer (T20), unread badge (T21), MentionsPage (T22), deep-link (T23), admin page (T24), final verification (T25).
- **Type consistency:** `PageOwnerService.getOwner` always returns a non-null login (admin fallback applied). `MentionService.recordCreate` takes `Optional<String> owner` — caller decides whether to pass it (only on opening comments). `CommentStore.editComment` now takes the old body string for diff'ing; callers must fetch it first.
- **Stability:** PageView's parallel-pool flakiness is now well-understood (auth-refetch transient + leaked timers). The new picker tests are pure-hook + dumb component, so they don't intersect with that hazard. Keep deep-link integration changes minimal in PageView.
- **Known-flake handling:** wikantik-main XMLUserDatabaseTest may flake under the parallel build per the memory note; re-run in isolation rather than chase it.

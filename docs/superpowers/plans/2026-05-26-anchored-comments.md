# Anchored Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the page-level markdown-appended comment feature with Google-Docs-style anchored comment threads (reply + resolve/reopen), stored in PostgreSQL and re-anchored into rendered HTML via a W3C TextQuoteSelector.

**Architecture:** New `comment_threads` + `comments` tables keyed by `canonical_id`. A `CommentStore` JDBC DAO (mirrors `PageVerificationDao`) is exposed through `PersistenceSubsystem.Services`. A new `CommentThreadResource` servlet at `/api/comment-threads/*` replaces `CommentResource`. The React reader captures a `{exact,prefix,suffix}` selector on text selection, POSTs a thread, and on load re-anchors threads into `<mark class="comment-highlight">` using Hypothesis's `dom-anchor-text-quote`; a toggleable `CommentsDrawer` lists/replies/resolves.

**Tech Stack:** Java 21, JDBC/PostgreSQL (H2 in tests), Jakarta Servlets, Gson, JUnit 5 + Mockito, Cargo IT; React + Vite, Vitest + @testing-library/react, `dom-anchor-text-quote`.

**Reference patterns (read before starting):**
- DAO shape: `wikantik-main/.../pagegraph/spine/PageVerificationDao.java`
- DAO H2 test: `wikantik-main/.../pagegraph/spine/PageVerificationDaoTest.java`
- Persistence wiring: `wikantik-main/.../persistence/subsystem/PersistenceSubsystemFactory.java` + `PersistenceSubsystem.java` (the `Services` record)
- REST base + test harness: `wikantik-rest/.../RestServletBase.java`, `wikantik-rest/.../CommentResourceTest.java`
- canonical_id resolution: `getSubsystems().pageGraph().structuralIndexService()` → `resolveCanonicalIdFromSlug` / `resolveSlugFromCanonicalId`
- Frontend reader: `wikantik-frontend/src/components/PageView.jsx`, `src/api/client.js`

---

## Task 1: Database migration V033

**Files:**
- Create: `bin/db/migrations/V033__comment_threads.sql`

- [ ] **Step 1: Write the migration**

Use the next free number (highest existing is V032). Match the idempotent style of `V014`/`V003` (`IF NOT EXISTS`, `:app_user` grants).

```sql
-- V033: Anchored comment threads (Google-Docs-style comments).
-- Threads carry one TextQuoteSelector anchor; comments are the thread body + replies.

CREATE TABLE IF NOT EXISTS comment_threads (
    id            UUID PRIMARY KEY,
    canonical_id  TEXT NOT NULL,
    anchor_exact  TEXT NOT NULL,
    anchor_prefix TEXT,
    anchor_suffix TEXT,
    status        TEXT NOT NULL DEFAULT 'open',
    created_by    TEXT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by   TEXT,
    resolved_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_comment_threads_canonical_id
    ON comment_threads (canonical_id);

CREATE TABLE IF NOT EXISTS comments (
    id         UUID PRIMARY KEY,
    thread_id  UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE,
    author     TEXT NOT NULL,
    body       TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_comments_thread_id
    ON comments (thread_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON comment_threads TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON comments TO :app_user;
```

- [ ] **Step 2: Verify it parses and is idempotent (manual reasoning + status check)**

Run: `bin/db/migrate.sh --status`
Expected: lists migrations through V033 as the next-to-apply (no syntax error). Re-applying must be a no-op — every statement uses `IF NOT EXISTS`/`OR REPLACE`-style guards. (Full apply happens against PostgreSQL during the IT in Task 7; the DAO test in Task 3 exercises the schema shape on H2.)

- [ ] **Step 3: Commit**

```bash
git add bin/db/migrations/V033__comment_threads.sql
git commit -m "feat(comments): V033 comment_threads + comments tables"
```

---

## Task 2: API domain types

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/comments/TextQuoteSelector.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/comments/Comment.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/comments/CommentThread.java`

These are immutable records shared by the DAO (wikantik-main) and the resource (wikantik-rest). Field names are snake-case-free Java; Gson default serialization is fine for the resource (it already pretty-prints). Use the standard ASF license header (copy from any existing file in the module).

- [ ] **Step 1: TextQuoteSelector**

```java
package com.wikantik.api.comments;

/** W3C Web Annotation TextQuoteSelector: the exact selected text plus context. */
public record TextQuoteSelector( String exact, String prefix, String suffix ) {
    public TextQuoteSelector {
        if ( exact == null || exact.isBlank() ) {
            throw new IllegalArgumentException( "exact selection text is required" );
        }
    }
}
```

- [ ] **Step 2: Comment**

```java
package com.wikantik.api.comments;

import java.time.Instant;
import java.util.UUID;

/** A single comment row: the opening comment of a thread or a reply. */
public record Comment(
        UUID id,
        UUID threadId,
        String author,
        String body,
        Instant createdAt,
        Instant editedAt ) {
}
```

- [ ] **Step 3: CommentThread**

```java
package com.wikantik.api.comments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A comment thread: one anchor, a status, and an ordered list of comments. */
public record CommentThread(
        UUID id,
        String canonicalId,
        TextQuoteSelector anchor,
        String status,          // "open" | "resolved"
        String createdBy,
        Instant createdAt,
        String resolvedBy,
        Instant resolvedAt,
        List< Comment > comments ) {

    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
}
```

- [ ] **Step 4: Compile the module**

Run: `mvn -q -pl wikantik-api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/comments/
git commit -m "feat(comments): API domain records for threads/comments/selector"
```

---

## Task 3: CommentStore DAO (TDD)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/comments/CommentStore.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/CommentStoreTest.java`

- [ ] **Step 1: Write the failing test**

Mirror `PageVerificationDaoTest`'s H2 setup (PostgreSQL mode, inline DDL). H2 has a `UUID` type and `RANDOM_UUID()`.

```java
package com.wikantik.comments;

import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommentStoreTest {

    private DataSource ds;
    private CommentStore store;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:cstore;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE comment_threads (
                    id UUID PRIMARY KEY,
                    canonical_id TEXT NOT NULL,
                    anchor_exact TEXT NOT NULL,
                    anchor_prefix TEXT,
                    anchor_suffix TEXT,
                    status TEXT NOT NULL DEFAULT 'open',
                    created_by TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    resolved_by TEXT,
                    resolved_at TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( """
                CREATE TABLE comments (
                    id UUID PRIMARY KEY,
                    thread_id UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE,
                    author TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    edited_at TIMESTAMP WITH TIME ZONE
                )""" );
        }
        this.store = new CommentStore( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE comments" );
            s.executeUpdate( "DROP TABLE comment_threads" );
        }
    }

    @Test
    void createThread_persists_thread_with_first_comment() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "hello", "say ", " world" ), "alice", "what does this mean?" );

        assertNotNull( t.id() );
        assertEquals( "CID1", t.canonicalId() );
        assertEquals( CommentThread.OPEN, t.status() );
        assertEquals( "hello", t.anchor().exact() );
        assertEquals( 1, t.comments().size() );
        assertEquals( "alice", t.comments().get( 0 ).author() );
        assertEquals( "what does this mean?", t.comments().get( 0 ).body() );
    }

    @Test
    void listByCanonicalId_returns_threads_with_comments_ordered() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "x", null, null ), "alice", "first" );
        store.addComment( t.id(), "bob", "reply" );

        final List< CommentThread > threads = store.listByCanonicalId( "CID1", "all" );
        assertEquals( 1, threads.size() );
        assertEquals( 2, threads.get( 0 ).comments().size() );
        assertEquals( "first", threads.get( 0 ).comments().get( 0 ).body() );
        assertEquals( "reply", threads.get( 0 ).comments().get( 1 ).body() );
    }

    @Test
    void listByCanonicalId_filters_by_status() {
        final CommentThread open = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "open one" );
        final CommentThread done = store.createThread( "CID1",
                new TextQuoteSelector( "b", null, null ), "alice", "to resolve" );
        store.resolve( done.id(), "bob" );

        assertEquals( 1, store.listByCanonicalId( "CID1", "open" ).size() );
        assertEquals( 1, store.listByCanonicalId( "CID1", "resolved" ).size() );
        assertEquals( 2, store.listByCanonicalId( "CID1", "all" ).size() );
        assertEquals( open.id(), store.listByCanonicalId( "CID1", "open" ).get( 0 ).id() );
    }

    @Test
    void resolve_then_reopen_toggles_status() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "x" );
        assertTrue( store.resolve( t.id(), "bob" ) );
        assertEquals( CommentThread.RESOLVED, store.findThread( t.id() ).orElseThrow().status() );
        assertTrue( store.reopen( t.id() ) );
        final CommentThread reopened = store.findThread( t.id() ).orElseThrow();
        assertEquals( CommentThread.OPEN, reopened.status() );
        assertNull( reopened.resolvedBy() );
    }

    @Test
    void editComment_sets_body_and_edited_at() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "typo" );
        final UUID cid = t.comments().get( 0 ).id();
        final Comment edited = store.editComment( cid, "fixed" ).orElseThrow();
        assertEquals( "fixed", edited.body() );
        assertNotNull( edited.editedAt() );
    }

    @Test
    void deleteComment_removes_single_comment() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first" );
        final Comment reply = store.addComment( t.id(), "bob", "reply" );
        assertTrue( store.deleteComment( reply.id() ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "all" ).get( 0 ).comments().size() );
    }

    @Test
    void deleteThread_cascades_comments() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first" );
        assertTrue( store.deleteThread( t.id() ) );
        assertTrue( store.listByCanonicalId( "CID1", "all" ).isEmpty() );
    }

    @Test
    void findComment_returns_author_for_permission_checks() {
        final CommentThread t = store.createThread( "CID1",
                new TextQuoteSelector( "a", null, null ), "alice", "first" );
        final UUID cid = t.comments().get( 0 ).id();
        assertEquals( "alice", store.findComment( cid ).orElseThrow().author() );
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile/run**

Run: `mvn -q -pl wikantik-main test-compile -Dtest=CommentStoreTest`
Expected: compile failure — `CommentStore` does not exist yet.

- [ ] **Step 3: Implement `CommentStore`**

```java
package com.wikantik.comments;

import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
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

/** JDBC gateway for {@code comment_threads} and {@code comments}. */
public class CommentStore {

    private static final Logger LOG = LogManager.getLogger( CommentStore.class );

    private final DataSource ds;

    public CommentStore( final DataSource ds ) {
        this.ds = ds;
    }

    public CommentThread createThread( final String canonicalId, final TextQuoteSelector anchor,
                                       final String author, final String body ) {
        final UUID threadId = UUID.randomUUID();
        final UUID commentId = UUID.randomUUID();
        try ( Connection c = ds.getConnection() ) {
            c.setAutoCommit( false );
            try {
                try ( PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO comment_threads (id, canonical_id, anchor_exact, anchor_prefix, " +
                        "anchor_suffix, status, created_by) VALUES (?, ?, ?, ?, ?, 'open', ?)" ) ) {
                    ps.setObject( 1, threadId );
                    ps.setString( 2, canonicalId );
                    ps.setString( 3, anchor.exact() );
                    ps.setString( 4, anchor.prefix() );
                    ps.setString( 5, anchor.suffix() );
                    ps.setString( 6, author );
                    ps.executeUpdate();
                }
                insertComment( c, commentId, threadId, author, body );
                c.commit();
            } catch ( final SQLException e ) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit( true );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "createThread(canonicalId={}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "comment thread create failed", e );
        }
        return findThread( threadId ).orElseThrow(
                () -> new RuntimeException( "thread vanished after insert: " + threadId ) );
    }

    public Comment addComment( final UUID threadId, final String author, final String body ) {
        final UUID commentId = UUID.randomUUID();
        try ( Connection c = ds.getConnection() ) {
            insertComment( c, commentId, threadId, author, body );
        } catch ( final SQLException e ) {
            LOG.warn( "addComment(threadId={}) failed: {}", threadId, e.getMessage(), e );
            throw new RuntimeException( "comment add failed", e );
        }
        return findComment( commentId ).orElseThrow(
                () -> new RuntimeException( "comment vanished after insert: " + commentId ) );
    }

    private static void insertComment( final Connection c, final UUID id, final UUID threadId,
                                       final String author, final String body ) throws SQLException {
        try ( PreparedStatement ps = c.prepareStatement(
                "INSERT INTO comments (id, thread_id, author, body) VALUES (?, ?, ?, ?)" ) ) {
            ps.setObject( 1, id );
            ps.setObject( 2, threadId );
            ps.setString( 3, author );
            ps.setString( 4, body );
            ps.executeUpdate();
        }
    }

    public Optional< Comment > editComment( final UUID commentId, final String newBody ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comments SET body = ?, edited_at = CURRENT_TIMESTAMP WHERE id = ?" ) ) {
            ps.setString( 1, newBody );
            ps.setObject( 2, commentId );
            if ( ps.executeUpdate() == 0 ) return Optional.empty();
        } catch ( final SQLException e ) {
            LOG.warn( "editComment(id={}) failed: {}", commentId, e.getMessage(), e );
            throw new RuntimeException( "comment edit failed", e );
        }
        return findComment( commentId );
    }

    public boolean deleteComment( final UUID commentId ) {
        return executeUpdate( "DELETE FROM comments WHERE id = ?", commentId, "deleteComment" );
    }

    public boolean deleteThread( final UUID threadId ) {
        return executeUpdate( "DELETE FROM comment_threads WHERE id = ?", threadId, "deleteThread" );
    }

    public boolean resolve( final UUID threadId, final String resolvedBy ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comment_threads SET status = 'resolved', resolved_by = ?, " +
                      "resolved_at = CURRENT_TIMESTAMP WHERE id = ?" ) ) {
            ps.setString( 1, resolvedBy );
            ps.setObject( 2, threadId );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "resolve(threadId={}) failed: {}", threadId, e.getMessage(), e );
            throw new RuntimeException( "thread resolve failed", e );
        }
    }

    public boolean reopen( final UUID threadId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comment_threads SET status = 'open', resolved_by = NULL, " +
                      "resolved_at = NULL WHERE id = ?" ) ) {
            ps.setObject( 1, threadId );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "reopen(threadId={}) failed: {}", threadId, e.getMessage(), e );
            throw new RuntimeException( "thread reopen failed", e );
        }
    }

    private boolean executeUpdate( final String sql, final UUID id, final String op ) {
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "{}(id={}) failed: {}", op, id, e.getMessage(), e );
            throw new RuntimeException( op + " failed", e );
        }
    }

    public List< CommentThread > listByCanonicalId( final String canonicalId, final String statusFilter ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT id, canonical_id, anchor_exact, anchor_prefix, anchor_suffix, status, " +
                "created_by, created_at, resolved_by, resolved_at FROM comment_threads WHERE canonical_id = ?" );
        final boolean byStatus = "open".equals( statusFilter ) || "resolved".equals( statusFilter );
        if ( byStatus ) sql.append( " AND status = ?" );
        sql.append( " ORDER BY created_at" );

        final List< CommentThread > threads = new ArrayList<>();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
            ps.setString( 1, canonicalId );
            if ( byStatus ) ps.setString( 2, statusFilter );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final UUID threadId = (UUID) rs.getObject( "id" );
                    threads.add( new CommentThread(
                            threadId,
                            rs.getString( "canonical_id" ),
                            new TextQuoteSelector( rs.getString( "anchor_exact" ),
                                    rs.getString( "anchor_prefix" ), rs.getString( "anchor_suffix" ) ),
                            rs.getString( "status" ),
                            rs.getString( "created_by" ),
                            toInstant( rs.getTimestamp( "created_at" ) ),
                            rs.getString( "resolved_by" ),
                            toInstant( rs.getTimestamp( "resolved_at" ) ),
                            loadComments( c, threadId ) ) );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "listByCanonicalId({}) failed: {}", canonicalId, e.getMessage(), e );
        }
        return threads;
    }

    public Optional< CommentThread > findThread( final UUID threadId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT canonical_id FROM comment_threads WHERE id = ?" ) ) {
            ps.setObject( 1, threadId );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) return Optional.empty();
                final String canonicalId = rs.getString( "canonical_id" );
                return listByCanonicalId( canonicalId, "all" ).stream()
                        .filter( t -> t.id().equals( threadId ) ).findFirst();
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findThread({}) failed: {}", threadId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    public Optional< Comment > findComment( final UUID commentId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT id, thread_id, author, body, created_at, edited_at " +
                      "FROM comments WHERE id = ?" ) ) {
            ps.setObject( 1, commentId );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) return Optional.empty();
                return Optional.of( readComment( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "findComment({}) failed: {}", commentId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    private static List< Comment > loadComments( final Connection c, final UUID threadId ) throws SQLException {
        final List< Comment > out = new ArrayList<>();
        try ( PreparedStatement ps = c.prepareStatement(
                "SELECT id, thread_id, author, body, created_at, edited_at " +
                "FROM comments WHERE thread_id = ? ORDER BY created_at" ) ) {
            ps.setObject( 1, threadId );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( readComment( rs ) );
            }
        }
        return out;
    }

    private static Comment readComment( final ResultSet rs ) throws SQLException {
        return new Comment(
                (UUID) rs.getObject( "id" ),
                (UUID) rs.getObject( "thread_id" ),
                rs.getString( "author" ),
                rs.getString( "body" ),
                toInstant( rs.getTimestamp( "created_at" ) ),
                toInstant( rs.getTimestamp( "edited_at" ) ) );
    }

    private static Instant toInstant( final Timestamp ts ) {
        return ts == null ? null : ts.toInstant();
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn -q -pl wikantik-main test -Dtest=CommentStoreTest`
Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/ wikantik-main/src/test/java/com/wikantik/comments/
git commit -m "feat(comments): CommentStore JDBC DAO with H2 tests"
```

---

## Task 4: Wire CommentStore into the persistence subsystem

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java` (the `Services` record)
- Modify: `wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java`

- [ ] **Step 1: Add a `comments` component to the `Services` record**

In `PersistenceSubsystem.java`, add `import com.wikantik.comments.CommentStore;` and add a new record component `CommentStore comments` to the `Services` record (append it as the last component so positional constructor changes are localized). Add a short doc comment matching the style of the existing components.

- [ ] **Step 2: Construct it in the factory**

In `PersistenceSubsystemFactory.create(...)`, add `import com.wikantik.comments.CommentStore;` and append `new CommentStore( ds )` as the final argument to the `new PersistenceSubsystem.Services( ... )` call (it must match the new component's position).

```java
        return new PersistenceSubsystem.Services(
            ds,
            kgNodes,
            // ... all existing args unchanged ...
            new TrustedAuthorsDao( ds ),
            new CommentStore( ds )      // <-- new, last
        );
```

- [ ] **Step 3: Find and fix every other `new PersistenceSubsystem.Services(` call site**

Run: `grep -rn "new PersistenceSubsystem.Services(" --include=*.java .`
For each hit outside the factory (test builders, fakes), add the trailing `CommentStore` argument — pass `null` in tests that don't exercise comments, or `new CommentStore(ds)` where a DataSource is available.

- [ ] **Step 4: Compile main + test sources (signature change — test-compile is required)**

Run: `mvn -q -pl wikantik-main test-compile`
Expected: BUILD SUCCESS. (Per project convention, `compile` alone won't catch broken test call sites.)

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/persistence/subsystem/
git commit -m "feat(comments): expose CommentStore via PersistenceSubsystem.Services"
```

---

## Task 5: CommentThreadResource (REST, TDD)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java`

The resource uses two narrow protected seams so unit tests can stub backends without a live DB: `commentStore()` and `resolveCanonicalId(String slug)` / `resolveSlug(String canonicalId)`. Permission denials send 403 with an explicit message.

- [ ] **Step 1: Write the failing test**

Mirror `CommentResourceTest`'s harness (TestEngine, HttpMockFactory, spy + stubbed `checkPagePermission`). Stub the seams via `Mockito.doReturn(...)`. Use an H2-backed `CommentStore` for realism.

```java
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.comments.CommentStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommentThreadResourceTest {

    private final Gson gson = new Gson();
    private CommentStore store;
    private CommentThreadResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:ctr;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        try ( Connection c = h2.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "CREATE TABLE IF NOT EXISTS comment_threads (id UUID PRIMARY KEY, " +
                "canonical_id TEXT NOT NULL, anchor_exact TEXT NOT NULL, anchor_prefix TEXT, " +
                "anchor_suffix TEXT, status TEXT NOT NULL DEFAULT 'open', created_by TEXT NOT NULL, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "resolved_by TEXT, resolved_at TIMESTAMP WITH TIME ZONE)" );
            s.executeUpdate( "CREATE TABLE IF NOT EXISTS comments (id UUID PRIMARY KEY, " +
                "thread_id UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE, " +
                "author TEXT NOT NULL, body TEXT NOT NULL, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "edited_at TIMESTAMP WITH TIME ZONE)" );
        }
        this.store = new CommentStore( h2 );

        // Spy with the seams + permission stubbed; identity user "alice".
        this.servlet = Mockito.spy( new CommentThreadResource() );
        Mockito.doReturn( store ).when( servlet ).commentStore();
        Mockito.doReturn( Optional.of( "CID1" ) ).when( servlet ).resolveCanonicalId( "PageOne" );
        Mockito.doReturn( Optional.of( "PageOne" ) ).when( servlet ).resolveSlug( "CID1" );
        Mockito.doReturn( true ).when( servlet ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
    }

    @Test
    void create_then_list_roundtrips() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "prefix", "say " );
        body.addProperty( "suffix", " world" );
        body.addProperty( "text", "what does this mean?" );

        final JsonObject created = post( "?page=PageOne", null, body );
        assertFalse( created.has( "error" ), created.toString() );
        assertTrue( created.has( "id" ) );
        assertEquals( "open", created.get( "status" ).getAsString() );

        final JsonObject list = get( "?page=PageOne&status=all" );
        final JsonArray threads = list.getAsJsonArray( "threads" );
        assertEquals( 1, threads.size() );
        assertEquals( "hello", threads.get( 0 ).getAsJsonObject()
                .getAsJsonObject( "anchor" ).get( "exact" ).getAsString() );
    }

    @Test
    void reply_resolve_reopen_cycle() throws Exception {
        final String threadId = createThread();

        // reply
        final JsonObject reply = new JsonObject();
        reply.addProperty( "text", "a reply" );
        assertFalse( post( null, "/" + threadId + "/comments", reply ).has( "error" ) );

        // resolve
        assertFalse( post( null, "/" + threadId + "/resolve", new JsonObject() ).has( "error" ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "resolved" ).size() );

        // reopen
        assertFalse( post( null, "/" + threadId + "/reopen", new JsonObject() ).has( "error" ) );
        assertEquals( 1, store.listByCanonicalId( "CID1", "open" ).size() );
    }

    @Test
    void edit_by_non_author_is_forbidden_with_reason() throws Exception {
        final String threadId = createThread();
        final String commentId = store.listByCanonicalId( "CID1", "all" )
                .get( 0 ).comments().get( 0 ).id().toString();

        // current user is now "mallory", not the author "alice"
        Mockito.doReturn( "mallory" ).when( servlet ).currentUser( Mockito.any() );
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "hijack" );

        final JsonObject res = patch( "/" + threadId + "/comments/" + commentId, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 403, res.get( "status" ).getAsInt() );
        assertTrue( res.get( "message" ).getAsString().toLowerCase().contains( "author" ),
                "refusal must cite the reason: " + res );
    }

    @Test
    void create_missing_text_is_400() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );   // no text
        final JsonObject res = post( "?page=PageOne", null, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 400, res.get( "status" ).getAsInt() );
    }

    @Test
    void create_unknown_page_is_404() throws Exception {
        Mockito.doReturn( Optional.empty() ).when( servlet ).resolveCanonicalId( "Ghost" );
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "hi" );
        final JsonObject res = post( "?page=Ghost", null, body );
        assertTrue( res.get( "error" ).getAsBoolean() );
        assertEquals( 404, res.get( "status" ).getAsInt() );
    }

    // ---- helpers ----

    private String createThread() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "exact", "hello" );
        body.addProperty( "text", "first" );
        return post( "?page=PageOne", null, body ).get( "id" ).getAsString();
    }

    private JsonObject get( final String query ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/comment-threads" + query );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        Mockito.doReturn( "page=" + paramOf( query, "page" ) ).when( req ).getQueryString();
        Mockito.doReturn( paramOf( query, "page" ) ).when( req ).getParameter( "page" );
        Mockito.doReturn( paramOf( query, "status" ) ).when( req ).getParameter( "status" );
        return invoke( req, "GET", null );
    }

    private JsonObject post( final String query, final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
                "/api/comment-threads" + ( query == null ? "" : query ) );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        if ( query != null ) Mockito.doReturn( paramOf( query, "page" ) ).when( req ).getParameter( "page" );
        return invoke( req, "POST", body );
    }

    private JsonObject patch( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/comment-threads" );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return invoke( req, "PATCH", body );
    }

    private JsonObject invoke( final HttpServletRequest req, final String method, final JsonObject body )
            throws Exception {
        Mockito.doReturn( method ).when( req ).getMethod();
        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( req ).getReader();
        }
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        servlet.service( req, resp );
        return gson.fromJson( sw.toString().isBlank() ? "{}" : sw.toString(), JsonObject.class );
    }

    private static String paramOf( final String query, final String key ) {
        if ( query == null ) return null;
        for ( final String pair : query.replaceFirst( "^\\?", "" ).split( "&" ) ) {
            final String[] kv = pair.split( "=", 2 );
            if ( kv.length == 2 && kv[ 0 ].equals( key ) ) return kv[ 1 ];
        }
        return null;
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -q -pl wikantik-rest test-compile -Dtest=CommentThreadResourceTest`
Expected: compile failure — `CommentThreadResource` does not exist.

- [ ] **Step 3: Implement `CommentThreadResource`**

Route on `getMethod()` + `getPathInfo()`. List/create use `?page=`; thread-scoped ops parse `/{threadId}/...`. Resolve permission from the thread's `canonical_id` → slug for thread-scoped ops.

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.comments.CommentStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST servlet for anchored comment threads. Mapped to {@code /api/comment-threads/*}.
 * Replaces the legacy {@code CommentResource}.
 */
public class CommentThreadResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( CommentThreadResource.class );

    // ---- seams (overridable in tests) ----

    protected CommentStore commentStore() {
        return getSubsystems().persistence().comments();
    }

    protected Optional< String > resolveCanonicalId( final String slug ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveCanonicalIdFromSlug( slug );
    }

    protected Optional< String > resolveSlug( final String canonicalId ) {
        return getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
    }

    protected String currentUser( final HttpServletRequest request ) {
        final Engine engine = getEngine();
        return Wiki.session().find( engine, request ).getUserPrincipal().getName();
    }

    // ---- routing ----

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String pageName = request.getParameter( "page" );
        if ( pageName == null || pageName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "page query parameter is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "view" ) ) return;
        final Optional< String > canonicalId = resolveCanonicalId( pageName );
        if ( canonicalId.isEmpty() ) {
            sendNotFound( response, "Page not found or not indexed: " + pageName );
            return;
        }
        final String status = normalizeStatus( request.getParameter( "status" ) );
        final List< CommentThread > threads = commentStore().listByCanonicalId( canonicalId.get(), status );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "page", pageName );
        result.put( "threads", threads.stream().map( CommentThreadResource::threadToMap ).toList() );
        sendJson( response, result );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] seg = segments( request );
        if ( seg.length == 0 ) {            // POST /api/comment-threads?page=... -> create thread
            createThread( request, response );
        } else if ( seg.length == 2 && "comments".equals( seg[ 1 ] ) ) {  // /{id}/comments -> reply
            addReply( request, response, seg[ 0 ] );
        } else if ( seg.length == 2 && "resolve".equals( seg[ 1 ] ) ) {
            setStatus( request, response, seg[ 0 ], true );
        } else if ( seg.length == 2 && "reopen".equals( seg[ 1 ] ) ) {
            setStatus( request, response, seg[ 0 ], false );
        } else {
            sendNotFound( response, "Unknown comment route" );
        }
    }

    @Override
    protected void doPatch( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] seg = segments( request );
        if ( seg.length == 3 && "comments".equals( seg[ 1 ] ) ) {
            editComment( request, response, seg[ 0 ], seg[ 2 ] );
        } else {
            sendNotFound( response, "Unknown comment route" );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String[] seg = segments( request );
        if ( seg.length == 3 && "comments".equals( seg[ 1 ] ) ) {
            deleteComment( request, response, seg[ 0 ], seg[ 2 ] );
        } else {
            sendNotFound( response, "Unknown comment route" );
        }
    }

    // ---- handlers ----

    private void createThread( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String pageName = request.getParameter( "page" );
        if ( pageName == null || pageName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "page query parameter is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "comment" ) ) return;
        final Optional< String > canonicalId = resolveCanonicalId( pageName );
        if ( canonicalId.isEmpty() ) {
            sendNotFound( response, "Page not found or not indexed: " + pageName );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String exact = getJsonString( body, "exact" );
        final String text  = getJsonString( body, "text" );
        if ( exact == null || exact.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "exact (selected text) is required" );
            return;
        }
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }
        final TextQuoteSelector anchor = new TextQuoteSelector(
                exact, getJsonString( body, "prefix" ), getJsonString( body, "suffix" ) );
        final CommentThread t = commentStore().createThread(
                canonicalId.get(), anchor, currentUser( request ), text );
        sendJson( response, threadToMap( t ) );
    }

    private void addReply( final HttpServletRequest request, final HttpServletResponse response,
                           final String threadIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String text = getJsonString( body, "text" );
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }
        final Comment c = commentStore().addComment( ctx.threadId, currentUser( request ), text );
        sendJson( response, commentToMap( c ) );
    }

    private void setStatus( final HttpServletRequest request, final HttpServletResponse response,
                            final String threadIdRaw, final boolean resolve ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final boolean ok = resolve
                ? commentStore().resolve( ctx.threadId, currentUser( request ) )
                : commentStore().reopen( ctx.threadId );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "id", ctx.threadId.toString() );
        result.put( "status", resolve ? CommentThread.RESOLVED : CommentThread.OPEN );
        result.put( "updated", ok );
        sendJson( response, result );
    }

    private void editComment( final HttpServletRequest request, final HttpServletResponse response,
                              final String threadIdRaw, final String commentIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final UUID commentId = parseUuid( commentIdRaw, response );
        if ( commentId == null ) return;
        final Optional< Comment > existing = commentStore().findComment( commentId );
        if ( existing.isEmpty() ) { sendNotFound( response, "Comment not found" ); return; }
        if ( !existing.get().author().equals( currentUser( request ) ) ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN,
                    "Only the comment author can edit this comment" );
            return;
        }
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String text = getJsonString( body, "text" );
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required and must not be blank" );
            return;
        }
        final Comment updated = commentStore().editComment( commentId, text ).orElse( null );
        if ( updated == null ) { sendNotFound( response, "Comment not found" ); return; }
        sendJson( response, commentToMap( updated ) );
    }

    private void deleteComment( final HttpServletRequest request, final HttpServletResponse response,
                                final String threadIdRaw, final String commentIdRaw ) throws IOException {
        final ThreadCtx ctx = authorizeThread( request, response, threadIdRaw, "comment" );
        if ( ctx == null ) return;
        final UUID commentId = parseUuid( commentIdRaw, response );
        if ( commentId == null ) return;
        final Optional< Comment > existing = commentStore().findComment( commentId );
        if ( existing.isEmpty() ) { sendNotFound( response, "Comment not found" ); return; }
        final boolean isAuthor = existing.get().author().equals( currentUser( request ) );
        final boolean canModerate = hasPagePermission( request, response, ctx.slug, "delete" );
        if ( !isAuthor && !canModerate ) {
            sendError( response, HttpServletResponse.SC_FORBIDDEN,
                    "Only the comment author or a page moderator can delete this comment" );
            return;
        }
        commentStore().deleteComment( commentId );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "deleted", true );
        result.put( "id", commentId.toString() );
        sendJson( response, result );
    }

    /** Loads the thread, resolves its page, and enforces {@code action}. Returns null on any failure (response sent). */
    private ThreadCtx authorizeThread( final HttpServletRequest request, final HttpServletResponse response,
                                       final String threadIdRaw, final String action ) throws IOException {
        final UUID threadId = parseUuid( threadIdRaw, response );
        if ( threadId == null ) return null;
        final Optional< CommentThread > thread = commentStore().findThread( threadId );
        if ( thread.isEmpty() ) { sendNotFound( response, "Comment thread not found" ); return null; }
        final Optional< String > slug = resolveSlug( thread.get().canonicalId() );
        if ( slug.isEmpty() ) { sendNotFound( response, "Page for thread no longer exists" ); return null; }
        if ( !checkPagePermission( request, response, slug.get(), action ) ) return null;
        return new ThreadCtx( threadId, slug.get() );
    }

    private record ThreadCtx( UUID threadId, String slug ) {}

    private UUID parseUuid( final String raw, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( raw );
        } catch ( final IllegalArgumentException e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid id: " + raw );
            return null;
        }
    }

    private static String normalizeStatus( final String raw ) {
        if ( "open".equals( raw ) || "resolved".equals( raw ) ) return raw;
        return "all";
    }

    /** path segments after the servlet mapping, e.g. "/{id}/comments" -> ["{id}","comments"]. */
    private static String[] segments( final HttpServletRequest request ) {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || pathInfo.isBlank() || "/".equals( pathInfo ) ) return new String[ 0 ];
        final String trimmed = pathInfo.startsWith( "/" ) ? pathInfo.substring( 1 ) : pathInfo;
        return trimmed.split( "/" );
    }

    // ---- serialization ----

    private static Map< String, Object > threadToMap( final CommentThread t ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", t.id().toString() );
        m.put( "status", t.status() );
        final Map< String, Object > anchor = new LinkedHashMap<>();
        anchor.put( "exact", t.anchor().exact() );
        anchor.put( "prefix", t.anchor().prefix() );
        anchor.put( "suffix", t.anchor().suffix() );
        m.put( "anchor", anchor );
        m.put( "createdBy", t.createdBy() );
        m.put( "createdAt", t.createdAt() == null ? null : t.createdAt().toString() );
        m.put( "resolvedBy", t.resolvedBy() );
        m.put( "resolvedAt", t.resolvedAt() == null ? null : t.resolvedAt().toString() );
        final List< Map< String, Object > > comments = new ArrayList<>();
        for ( final Comment c : t.comments() ) comments.add( commentToMap( c ) );
        m.put( "comments", comments );
        return m;
    }

    private static Map< String, Object > commentToMap( final Comment c ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "id", c.id().toString() );
        m.put( "threadId", c.threadId().toString() );
        m.put( "author", c.author() );
        m.put( "body", c.body() );
        m.put( "createdAt", c.createdAt() == null ? null : c.createdAt().toString() );
        m.put( "editedAt", c.editedAt() == null ? null : c.editedAt().toString() );
        return m;
    }
}
```

> **Note on `doPatch`:** `HttpServlet` has no `doPatch`. Confirm `RestServletBase.service(...)` dispatches PATCH (grep for `"PATCH"` in `RestServletBase.java`). If it does not, add a `protected void doPatch(...)` hook there that other resources can override and route PATCH to it from `service()`, mirroring how PageResource handles PATCH. (PageResource already supports PATCH per the spec, so the dispatch likely exists — verify and reuse it; do not add a second mechanism.)

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn -q -pl wikantik-rest test -Dtest=CommentThreadResourceTest`
Expected: all 5 tests PASS. If PATCH routing is missing, fix per the note above and re-run.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java
git commit -m "feat(comments): CommentThreadResource REST endpoint with unit tests"
```

---

## Task 6: Register the new servlet, remove the old one

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Delete: `wikantik-rest/src/main/java/com/wikantik/rest/CommentResource.java`
- Delete: `wikantik-rest/src/test/java/com/wikantik/rest/CommentResourceTest.java`

- [ ] **Step 1: Swap the servlet declaration in web.xml**

Replace the `<servlet>` block at the CommentResource declaration (around lines 456-457):

```xml
   <servlet>
       <servlet-name>CommentThreadResource</servlet-name>
       <servlet-class>com.wikantik.rest.CommentThreadResource</servlet-class>
   </servlet>
```

Replace the `<servlet-mapping>` block (around lines 662-663):

```xml
   <servlet-mapping>
       <servlet-name>CommentThreadResource</servlet-name>
       <url-pattern>/api/comment-threads/*</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 2: Delete the legacy resource and its test**

```bash
git rm wikantik-rest/src/main/java/com/wikantik/rest/CommentResource.java
git rm wikantik-rest/src/test/java/com/wikantik/rest/CommentResourceTest.java
```

- [ ] **Step 3: Confirm nothing else references CommentResource**

Run: `grep -rn "CommentResource\|api/comments" --include=*.java --include=*.xml wikantik-rest wikantik-war wikantik-it-tests`
Expected: no hits (other than your new `CommentThreadResource`). Fix any stragglers.

- [ ] **Step 4: Compile the REST module**

Run: `mvn -q -pl wikantik-rest test-compile`
Expected: BUILD SUCCESS (no dangling references to the deleted class).

- [ ] **Step 5: Commit**

```bash
git add wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(comments): mount /api/comment-threads, remove legacy CommentResource"
```

---

## Task 7: Wire-level integration test (Cargo + PostgreSQL)

**Files:**
- Create: a REST IT under `wikantik-it-tests` (locate the existing REST IT suite — e.g. the package containing other `*ResourceIT` / REST round-trip tests — and add `CommentThreadIT.java` beside them, reusing that suite's base class and `RestSeedHelper`).

Per project convention, MCP/REST write surfaces ship a unit test **and** a Cargo-launched wire-level IT. The IT exercises the full create → reply → resolve → reopen cycle over HTTP against the migrated PostgreSQL schema (V033 applied by `migrate.sh` during IT setup).

- [ ] **Step 1: Write the IT (model on the existing REST IT base)**

Reuse the suite's authenticated admin client and `RestSeedHelper.awaitAdminReady` (see memory: admin login race). Pseudostructure — fill in with the suite's actual helper calls:

```java
// Seed a page so it has a canonical_id, then:
// 1. POST /api/comment-threads?page=<seeded> with {exact,prefix,suffix,text} -> 200, capture id + assert status "open"
// 2. GET  /api/comment-threads?page=<seeded>&status=open -> assert 1 thread, anchor.exact matches
// 3. POST /api/comment-threads/{id}/comments {text:"reply"} -> 200, assert comments size 2 on next GET
// 4. POST /api/comment-threads/{id}/resolve -> GET ?status=resolved returns 1, ?status=open returns 0
// 5. POST /api/comment-threads/{id}/reopen  -> GET ?status=open returns 1
// 6. (negative) GET without ?page -> 400
```

Use the seeded page's real slug; assert HTTP status codes and JSON bodies exactly as the resource produces them (`threads`, `anchor.exact`, `status`).

- [ ] **Step 2: Run the IT suite (sequential — never with -T)**

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests -am`
Expected: the new `CommentThreadIT` passes; existing ITs unaffected.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/
git commit -m "test(comments): wire-level IT for thread create/reply/resolve/reopen"
```

---

## Task 8: Frontend — selector capture utility (TDD)

**Files:**
- Modify: `wikantik-frontend/package.json` (add deps)
- Create: `wikantik-frontend/src/utils/commentAnchor.js`
- Test: `wikantik-frontend/src/utils/commentAnchor.test.js`

- [ ] **Step 1: Add dependencies**

In `wikantik-frontend/package.json` `dependencies`, add:

```json
    "dom-anchor-text-quote": "^4.0.2",
    "dom-anchor-text-position": "^5.0.0"
```

Run: `cd wikantik-frontend && npm install`
Expected: lockfile updates, no errors.

- [ ] **Step 2: Write the failing test**

```js
import { describe, it, expect } from 'vitest';
import { selectorFromRange, selectionTouchesMath } from './commentAnchor';

function makeRoot(html) {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

function rangeOver(textNode, start, end) {
  const r = document.createRange();
  r.setStart(textNode, start);
  r.setEnd(textNode, end);
  return r;
}

describe('selectorFromRange', () => {
  it('captures exact text plus surrounding context', () => {
    const root = makeRoot('<p>say hello world to everyone</p>');
    const tn = root.querySelector('p').firstChild;
    // "hello" is chars 4..9
    const sel = selectorFromRange(root, rangeOver(tn, 4, 9));
    expect(sel.exact).toBe('hello');
    expect(sel.prefix.endsWith('say ')).toBe(true);
    expect(sel.suffix.startsWith(' world')).toBe(true);
  });

  it('returns null for a collapsed/empty range', () => {
    const root = makeRoot('<p>abc</p>');
    const tn = root.querySelector('p').firstChild;
    expect(selectorFromRange(root, rangeOver(tn, 1, 1))).toBeNull();
  });
});

describe('selectionTouchesMath', () => {
  it('detects a selection inside KaTeX output', () => {
    const root = makeRoot('<p>see <span class="katex"><span>x</span></span> here</p>');
    const inner = root.querySelector('.katex span').firstChild;
    expect(selectionTouchesMath(rangeOver(inner, 0, 1))).toBe(true);
  });

  it('is false for plain text', () => {
    const root = makeRoot('<p>plain text</p>');
    const tn = root.querySelector('p').firstChild;
    expect(selectionTouchesMath(rangeOver(tn, 0, 5))).toBe(false);
  });
});
```

- [ ] **Step 3: Run the test, verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/utils/commentAnchor.test.js`
Expected: FAIL — module not found.

- [ ] **Step 4: Implement `commentAnchor.js`**

```js
import * as textQuote from 'dom-anchor-text-quote';

const MATH_SELECTOR = '.katex, .katex-display, .math-rendered';

/** True if either endpoint of the range sits inside KaTeX-rendered math. */
export function selectionTouchesMath(range) {
  const ends = [range.startContainer, range.endContainer];
  return ends.some((node) => {
    const el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    return !!(el && el.closest && el.closest(MATH_SELECTOR));
  });
}

/**
 * Build a W3C TextQuoteSelector {exact, prefix, suffix} from a DOM Range
 * relative to `root`. Returns null if the range is empty or outside root.
 */
export function selectorFromRange(root, range) {
  if (!range || range.collapsed) return null;
  if (!root.contains(range.commonAncestorContainer)) return null;
  const selector = textQuote.fromRange(root, range);
  if (!selector || !selector.exact || !selector.exact.trim()) return null;
  return { exact: selector.exact, prefix: selector.prefix || '', suffix: selector.suffix || '' };
}

/** Capture from the live window selection within `root`. Returns
 *  {selector, rect} | {error:'math'} | null. */
export function captureSelection(root) {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
  const range = sel.getRangeAt(0);
  if (!root.contains(range.commonAncestorContainer)) return null;
  if (selectionTouchesMath(range)) return { error: 'math' };
  const selector = selectorFromRange(root, range);
  if (!selector) return null;
  return { selector, rect: range.getBoundingClientRect() };
}
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/utils/commentAnchor.test.js`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/package.json wikantik-frontend/package-lock.json wikantik-frontend/src/utils/commentAnchor.js wikantik-frontend/src/utils/commentAnchor.test.js
git commit -m "feat(comments): frontend selector capture util + text-quote deps"
```

---

## Task 9: Frontend — re-anchoring and highlighting (TDD)

**Files:**
- Create: `wikantik-frontend/src/utils/commentHighlight.js`
- Test: `wikantik-frontend/src/utils/commentHighlight.test.js`

- [ ] **Step 1: Write the failing test**

```js
import { describe, it, expect, beforeEach } from 'vitest';
import { anchorThreads, highlightRange } from './commentHighlight';

function makeRoot(html) {
  document.body.innerHTML = '';
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

describe('highlightRange', () => {
  it('wraps the ranged text in a comment-highlight mark', () => {
    const root = makeRoot('<p>say hello world</p>');
    const tn = root.querySelector('p').firstChild;
    const range = document.createRange();
    range.setStart(tn, 4);
    range.setEnd(tn, 9); // "hello"
    const marks = highlightRange(range, 'T1');
    expect(marks.length).toBe(1);
    const mark = root.querySelector('mark.comment-highlight');
    expect(mark.textContent).toBe('hello');
    expect(mark.dataset.threadId).toBe('T1');
  });
});

describe('anchorThreads', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('highlights an open thread by its exact text', () => {
    const root = makeRoot('<p>The quick brown fox</p>');
    const threads = [{ id: 'T1', status: 'open', anchor: { exact: 'quick brown', prefix: 'The ', suffix: ' fox' } }];
    const { detached } = anchorThreads(root, threads);
    expect(detached).toEqual([]);
    expect(root.querySelector('mark[data-thread-id="T1"]').textContent).toBe('quick brown');
  });

  it('disambiguates between duplicate text using prefix/suffix', () => {
    const root = makeRoot('<p>red cat then red dog</p>');
    const threads = [{ id: 'T1', status: 'open', anchor: { exact: 'red', prefix: 'then ', suffix: ' dog' } }];
    anchorThreads(root, threads);
    const mark = root.querySelector('mark[data-thread-id="T1"]');
    expect(mark.nextSibling.textContent).toContain('dog');
  });

  it('marks a thread detached when its text is gone', () => {
    const root = makeRoot('<p>nothing matches here</p>');
    const threads = [{ id: 'T1', status: 'open', anchor: { exact: 'absent phrase', prefix: '', suffix: '' } }];
    const { detached } = anchorThreads(root, threads);
    expect(detached).toEqual(['T1']);
    expect(root.querySelector('mark')).toBeNull();
  });

  it('does not highlight resolved threads', () => {
    const root = makeRoot('<p>quick brown fox</p>');
    const threads = [{ id: 'T1', status: 'resolved', anchor: { exact: 'quick', prefix: '', suffix: '' } }];
    const { detached } = anchorThreads(root, threads);
    expect(root.querySelector('mark')).toBeNull();
    expect(detached).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/utils/commentHighlight.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `commentHighlight.js`**

```js
import * as textQuote from 'dom-anchor-text-quote';

/** Wrap every text-node segment inside `range` in <mark class="comment-highlight">. */
export function highlightRange(range, threadId) {
  const marks = [];
  const root = range.commonAncestorContainer.nodeType === Node.ELEMENT_NODE
    ? range.commonAncestorContainer
    : range.commonAncestorContainer.parentNode;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const textNodes = [];
  let n;
  while ((n = walker.nextNode())) {
    if (range.intersectsNode(n)) textNodes.push(n);
  }
  for (const textNode of textNodes) {
    let start = 0;
    let end = textNode.data.length;
    if (textNode === range.startContainer) start = range.startOffset;
    if (textNode === range.endContainer) end = range.endOffset;
    if (start >= end) continue;
    const r = document.createRange();
    r.setStart(textNode, start);
    r.setEnd(textNode, end);
    const mark = document.createElement('mark');
    mark.className = 'comment-highlight';
    mark.dataset.threadId = threadId;
    try {
      r.surroundContents(mark);
      marks.push(mark);
    } catch {
      // surroundContents throws if the range partially selects a non-text node;
      // skip that segment rather than corrupting the DOM.
    }
  }
  return marks;
}

/**
 * Re-anchor each OPEN thread into `root`. Resolved threads are skipped (no
 * highlight). Returns { detached: [threadId,...] } for threads whose text
 * could not be located.
 */
export function anchorThreads(root, threads) {
  const detached = [];
  for (const t of threads) {
    if (t.status !== 'open') continue;
    const range = textQuote.toRange(root, {
      exact: t.anchor.exact,
      prefix: t.anchor.prefix || '',
      suffix: t.anchor.suffix || '',
    });
    if (!range) { detached.push(t.id); continue; }
    const marks = highlightRange(range, t.id);
    if (marks.length === 0) detached.push(t.id);
  }
  return { detached };
}

/** Remove all existing comment highlights (call before re-anchoring). */
export function clearHighlights(root) {
  root.querySelectorAll('mark.comment-highlight').forEach((mark) => {
    const parent = mark.parentNode;
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
    parent.removeChild(mark);
    parent.normalize();
  });
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/utils/commentHighlight.test.js`
Expected: PASS. (If `textQuote.toRange` behaves differently than happy-dom expects for the disambiguation case, adjust the test's prefix/suffix to unambiguous context — the library, not our code, owns the fuzzy match.)

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/commentHighlight.js wikantik-frontend/src/utils/commentHighlight.test.js
git commit -m "feat(comments): re-anchoring + highlightRange util with tests"
```

---

## Task 10: Frontend — API client methods

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (replace the `getComments`/`addComment` block at lines ~118-125)

- [ ] **Step 1: Replace the comment client methods**

Remove `getComments` and `addComment`. Add:

```js
  // Anchored comment threads
  listCommentThreads: (page, status = 'all') =>
    request(`/api/comment-threads?page=${encodeURIComponent(page)}&status=${encodeURIComponent(status)}`),

  createCommentThread: (page, { exact, prefix, suffix, text }) =>
    request(`/api/comment-threads?page=${encodeURIComponent(page)}`, {
      method: 'POST',
      body: JSON.stringify({ exact, prefix, suffix, text }),
    }),

  addCommentReply: (threadId, text) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/comments`, {
      method: 'POST',
      body: JSON.stringify({ text }),
    }),

  editComment: (threadId, commentId, text) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/comments/${encodeURIComponent(commentId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ text }),
    }),

  deleteComment: (threadId, commentId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/comments/${encodeURIComponent(commentId)}`, {
      method: 'DELETE',
    }),

  resolveCommentThread: (threadId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/resolve`, { method: 'POST' }),

  reopenCommentThread: (threadId) =>
    request(`/api/comment-threads/${encodeURIComponent(threadId)}/reopen`, { method: 'POST' }),
```

(Match the surrounding object's formatting and the existing `request(...)` helper signature — confirm how `method`/`body` are passed by reading the neighbouring methods.)

- [ ] **Step 2: Confirm nothing else calls the removed methods**

Run: `grep -rn "getComments\|addComment\b" wikantik-frontend/src`
Expected: only the (about-to-be-deleted) `CommentsPanel.jsx` references them — handled in Task 12. Fix any other hits.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat(comments): client methods for anchored comment threads"
```

---

## Task 11: Frontend — CommentsDrawer component (TDD)

**Files:**
- Create: `wikantik-frontend/src/components/CommentsDrawer.jsx`
- Test: `wikantik-frontend/src/components/CommentsDrawer.test.jsx`

`CommentsDrawer` is presentational + data-driven via props/callbacks so it is unit-testable without the reader. Props:
`{ open, threads, detachedIds, statusFilter, onStatusFilter, onReply, onResolve, onReopen, onFocusThread, onClose }`.

- [ ] **Step 1: Write the failing test**

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CommentsDrawer from './CommentsDrawer';

const threads = [
  { id: 'T1', status: 'open', anchor: { exact: 'alpha' }, createdBy: 'alice',
    comments: [{ id: 'C1', author: 'alice', body: 'first note' }] },
  { id: 'T2', status: 'resolved', anchor: { exact: 'beta' }, createdBy: 'bob',
    comments: [{ id: 'C2', author: 'bob', body: 'done note' }] },
];

function setup(extra = {}) {
  const props = {
    open: true, threads, detachedIds: [], statusFilter: 'open',
    onStatusFilter: vi.fn(), onReply: vi.fn(), onResolve: vi.fn(),
    onReopen: vi.fn(), onFocusThread: vi.fn(), onClose: vi.fn(), ...extra,
  };
  render(<CommentsDrawer {...props} />);
  return props;
}

describe('CommentsDrawer', () => {
  it('shows only open threads when filter is open', () => {
    setup({ statusFilter: 'open' });
    expect(screen.getByText('first note')).toBeTruthy();
    expect(screen.queryByText('done note')).toBeNull();
  });

  it('shows resolved threads when filter is resolved', () => {
    setup({ statusFilter: 'resolved' });
    expect(screen.getByText('done note')).toBeTruthy();
    expect(screen.queryByText('first note')).toBeNull();
  });

  it('fires onResolve for an open thread', () => {
    const props = setup({ statusFilter: 'open' });
    fireEvent.click(screen.getByRole('button', { name: /resolve/i }));
    expect(props.onResolve).toHaveBeenCalledWith('T1');
  });

  it('fires onReply with thread id and text', () => {
    const props = setup({ statusFilter: 'open' });
    fireEvent.change(screen.getByPlaceholderText(/reply/i), { target: { value: 'me too' } });
    fireEvent.click(screen.getByRole('button', { name: /^reply$/i }));
    expect(props.onReply).toHaveBeenCalledWith('T1', 'me too');
  });

  it('renders a Detached group for orphaned threads', () => {
    setup({ statusFilter: 'all', detachedIds: ['T1'] });
    expect(screen.getByText(/detached/i)).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/CommentsDrawer.test.jsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `CommentsDrawer.jsx`**

```jsx
import { useState } from 'react';

function ThreadCard({ thread, detached, onReply, onResolve, onReopen, onFocusThread }) {
  const [reply, setReply] = useState('');
  return (
    <div className="comment-thread" onClick={() => onFocusThread(thread.id)}>
      <div className="comment-thread-anchor">“{thread.anchor?.exact}”</div>
      {thread.comments.map((c) => (
        <div key={c.id} className="comment-item">
          <span className="comment-author">{c.author}</span>
          <span className="comment-body">{c.body}</span>
        </div>
      ))}
      {thread.status === 'open' && !detached && (
        <div className="comment-reply-row" onClick={(e) => e.stopPropagation()}>
          <input
            className="comment-reply-input"
            placeholder="Reply…"
            value={reply}
            onChange={(e) => setReply(e.target.value)}
          />
          <button
            onClick={() => { if (reply.trim()) { onReply(thread.id, reply.trim()); setReply(''); } }}
          >
            Reply
          </button>
          <button onClick={() => onResolve(thread.id)}>Resolve</button>
        </div>
      )}
      {thread.status === 'resolved' && (
        <div className="comment-reply-row" onClick={(e) => e.stopPropagation()}>
          <button onClick={() => onReopen(thread.id)}>Reopen</button>
        </div>
      )}
    </div>
  );
}

export default function CommentsDrawer({
  open, threads, detachedIds = [], statusFilter,
  onStatusFilter, onReply, onResolve, onReopen, onFocusThread, onClose,
}) {
  if (!open) return null;
  const isDetached = (id) => detachedIds.includes(id);
  const visible = threads.filter((t) => {
    if (statusFilter === 'open') return t.status === 'open';
    if (statusFilter === 'resolved') return t.status === 'resolved';
    return true;
  });
  const attached = visible.filter((t) => !isDetached(t.id));
  const detached = visible.filter((t) => isDetached(t.id));

  return (
    <aside className="comments-drawer">
      <div className="comments-drawer-header">
        <span>Comments</span>
        <select value={statusFilter} onChange={(e) => onStatusFilter(e.target.value)}>
          <option value="open">Open</option>
          <option value="resolved">Resolved</option>
          <option value="all">All</option>
        </select>
        <button aria-label="Close comments" onClick={onClose}>✕</button>
      </div>
      <div className="comments-drawer-body">
        {attached.map((t) => (
          <ThreadCard key={t.id} thread={t} detached={false}
            onReply={onReply} onResolve={onResolve} onReopen={onReopen} onFocusThread={onFocusThread} />
        ))}
        {detached.length > 0 && (
          <div className="comments-detached">
            <div className="comments-detached-label">Detached</div>
            {detached.map((t) => (
              <ThreadCard key={t.id} thread={t} detached
                onReply={onReply} onResolve={onResolve} onReopen={onReopen} onFocusThread={onFocusThread} />
            ))}
          </div>
        )}
        {visible.length === 0 && <p className="comments-empty">No comments.</p>}
      </div>
    </aside>
  );
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/CommentsDrawer.test.jsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/CommentsDrawer.jsx wikantik-frontend/src/components/CommentsDrawer.test.jsx
git commit -m "feat(comments): CommentsDrawer component with tests"
```

---

## Task 12: Frontend — integrate into PageView, remove CommentsPanel, add CSS

**Files:**
- Modify: `wikantik-frontend/src/components/PageView.jsx`
- Delete: `wikantik-frontend/src/components/CommentsPanel.jsx`
- Modify: the global stylesheet PageView uses (find it: `grep -rn "comment\|article" wikantik-frontend/src/**/*.css` and follow PageView's existing class conventions — add styles in the same file the reader already imports)

- [ ] **Step 1: Remove the old panel**

```bash
git rm wikantik-frontend/src/components/CommentsPanel.jsx
```
In `PageView.jsx` remove `import CommentsPanel from './CommentsPanel';` (line 11) and the `<CommentsPanel pageName={name} />` usage (line ~204).

- [ ] **Step 2: Wire the drawer, selection button, and highlight effect into PageView**

Add imports:

```jsx
import { useState, useEffect, useCallback } from 'react';
import CommentsDrawer from './CommentsDrawer';
import { captureSelection } from '../utils/commentAnchor';
import { anchorThreads, clearHighlights } from '../utils/commentHighlight';
import { api } from '../api/client';
```

Add state + loaders inside the component:

```jsx
  const [threads, setThreads] = useState([]);
  const [detachedIds, setDetachedIds] = useState([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [statusFilter, setStatusFilter] = useState('open');
  const [selection, setSelection] = useState(null); // {selector, rect} | {error}

  const loadThreads = useCallback(async () => {
    try {
      const res = await api.listCommentThreads(name, 'all');
      setThreads(res.threads || []);
    } catch (e) {
      console.warn('Failed to load comment threads', e);
    }
  }, [name]);

  useEffect(() => { loadThreads(); }, [loadThreads]);
```

Re-anchor whenever HTML or threads change — run AFTER `renderMath` (extend the existing math effect or add one that depends on `[page?.contentHtml, threads]`):

```jsx
  useEffect(() => {
    const root = articleRef.current;
    if (!root || !page?.contentHtml) return;
    clearHighlights(root);
    const { detached } = anchorThreads(root, threads);
    setDetachedIds(detached);
  }, [page?.contentHtml, threads]);
```

Capture selection on mouseup within the article:

```jsx
  const onArticleMouseUp = () => {
    const root = articleRef.current;
    if (!root) return;
    const cap = captureSelection(root);
    setSelection(cap);
  };
```

Add a floating "Comment" button when a valid selection exists, and a "math" hint when `selection?.error === 'math'`; on click create the thread:

```jsx
  const createThread = async (text) => {
    if (!selection?.selector || !text.trim()) return;
    await api.createCommentThread(name, { ...selection.selector, text: text.trim() });
    setSelection(null);
    window.getSelection()?.removeAllRanges();
    await loadThreads();
    setDrawerOpen(true);
  };
```

Add the toggle button to the existing top-right action bar (the flex container at lines ~136-152, beside Edit/Rename/Delete):

```jsx
  <button className="btn btn-ghost" onClick={() => setDrawerOpen((o) => !o)}>
    💬 Comments{threads.length ? ` (${threads.length})` : ''}
  </button>
```

Attach `onMouseUp={onArticleMouseUp}` to the article element that holds `ref={articleRef}` (line ~198). Render the drawer and selection affordance near the end of the returned JSX:

```jsx
  <CommentsDrawer
    open={drawerOpen}
    threads={threads}
    detachedIds={detachedIds}
    statusFilter={statusFilter}
    onStatusFilter={setStatusFilter}
    onReply={async (threadId, text) => { await api.addCommentReply(threadId, text); await loadThreads(); }}
    onResolve={async (threadId) => { await api.resolveCommentThread(threadId); await loadThreads(); }}
    onReopen={async (threadId) => { await api.reopenCommentThread(threadId); await loadThreads(); }}
    onFocusThread={(threadId) => {
      const mark = articleRef.current?.querySelector(`mark[data-thread-id="${threadId}"]`);
      if (mark) { mark.scrollIntoView({ block: 'center', behavior: 'smooth' });
                  mark.classList.add('comment-highlight-pulse');
                  setTimeout(() => mark.classList.remove('comment-highlight-pulse'), 1200); }
    }}
    onClose={() => setDrawerOpen(false)}
  />

  {selection?.selector && (
    <button
      className="comment-add-floating"
      style={{ position: 'fixed', top: selection.rect.bottom + 6, left: selection.rect.left }}
      onClick={() => {
        const text = window.prompt('Add a comment');
        if (text) createThread(text);
      }}
    >
      💬 Comment
    </button>
  )}
  {selection?.error === 'math' && (
    <div className="comment-math-hint"
         style={{ position: 'fixed', top: 8, right: 8 }}>
      Can’t comment on math expressions — select plain text.
    </div>
  )}
```

> Clicking a highlight should open the drawer + focus its thread. Add a click handler on the article (or extend the existing internal-link click handler at lines ~94-102) that checks `e.target.closest('mark.comment-highlight')`, and if present calls `setDrawerOpen(true)` and the same focus logic with that mark's `dataset.threadId`. Keep the composer minimal (`window.prompt`) for v1; a styled inline composer can come later.

- [ ] **Step 3: Add styles**

In the stylesheet the reader imports, add (tune colours to existing CSS variables):

```css
mark.comment-highlight { background: #fde68a; cursor: pointer; border-radius: 2px; }
mark.comment-highlight-pulse { animation: comment-pulse 1.2s ease-out; }
@keyframes comment-pulse { 0% { background: #f59e0b; } 100% { background: #fde68a; } }
.comments-drawer { position: fixed; top: 0; right: 0; width: 340px; height: 100vh;
  background: var(--surface, #fff); border-left: 1px solid var(--border, #e2e8f0);
  box-shadow: -2px 0 8px rgba(0,0,0,.08); display: flex; flex-direction: column; z-index: 50; }
.comments-drawer-header { display: flex; align-items: center; gap: 8px;
  justify-content: space-between; padding: 12px; border-bottom: 1px solid var(--border, #e2e8f0); }
.comments-drawer-body { overflow-y: auto; padding: 12px; gap: 12px; display: flex; flex-direction: column; }
.comment-thread { border: 1px solid var(--border, #e2e8f0); border-left: 3px solid #f59e0b;
  border-radius: 6px; padding: 8px; cursor: pointer; }
.comment-thread-anchor { font-size: 12px; color: #64748b; font-style: italic; margin-bottom: 6px; }
.comment-item { font-size: 13px; margin-bottom: 4px; }
.comment-author { font-weight: 600; margin-right: 6px; }
.comment-reply-row { display: flex; gap: 6px; margin-top: 6px; }
.comment-reply-input { flex: 1; min-width: 0; }
.comments-detached-label { font-size: 11px; text-transform: uppercase; color: #94a3b8; margin: 8px 0 4px; }
.comment-add-floating { z-index: 60; }
@media (max-width: 640px) { .comments-drawer { width: 100vw; } }
```

- [ ] **Step 4: Run frontend tests + build**

Run: `cd wikantik-frontend && npx vitest run && npm run build`
Expected: all Vitest suites PASS; Vite build succeeds with no unresolved imports (confirms `CommentsPanel` removal left no dangling references).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/PageView.jsx wikantik-frontend/src/  # include the modified CSS file
git commit -m "feat(comments): anchored-comment reader UI (drawer, highlights, selection)"
```

---

## Task 13: Legacy comment export (operator one-shot)

**Files:**
- Create: `backups/legacy_comments_2026-05-26.md` (gitignored output — see note)

Per the spec, existing markdown-appended comments are exported once, then the live system runs clean (the markers are inert once `CommentResource` is gone — they remain as HTML comments in page bodies and render to nothing).

- [ ] **Step 1: Export existing markdown comments to a backup file**

Run:
```bash
grep -rIl '<!-- comment:author=' docs/wikantik-pages \
  | while read -r f; do
      echo "===== $f ====="
      grep -nA6 '<!-- comment:author=' "$f"
    done > backups/legacy_comments_2026-05-26.md
wc -l backups/legacy_comments_2026-05-26.md
```
Expected: a backup file listing every legacy comment with its source page. (If `backups/` is gitignored, that's fine — this is an operator artifact, not a tracked file. Confirm with `git check-ignore backups/`.)

- [ ] **Step 2: Decide on marker cleanup (optional, document only)**

The inert `<!-- comment:... -->` markers and their `>` blockquotes can stay (they render to nothing) or be stripped page-by-page later. Do NOT bulk-rewrite page files in this task — leave a note in the PR/commit body that markers remain in-body and can be cleaned per-page on next edit. No code change.

- [ ] **Step 3: Commit (note only — no tracked files if backups/ is ignored)**

If `backups/` is tracked, do not commit the dump (it may contain content); otherwise nothing to commit. Record the export in the final commit message of Task 14.

---

## Task 14: Final verification

- [ ] **Step 1: Full unit build (parallel OK)**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS across all modules; RAT license check passes (new Java files carry the ASF header).

- [ ] **Step 2: Full integration reactor (sequential — never -T)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: all IT suites pass, including the new `CommentThreadIT`. (Per project convention, gate the work on the full IT reactor, not just targeted runs.)

- [ ] **Step 3: Frontend test + build**

Run: `cd wikantik-frontend && npx vitest run && npm run build`
Expected: all suites PASS, build succeeds.

- [ ] **Step 4: Manual smoke (deploy locally)**

Run: `mvn clean install -Dmaven.test.skip -T 1C && bin/redeploy.sh`
Then in the browser at `http://localhost:8080/`: open a page, select text → "💬 Comment" → add a comment → confirm a highlight appears and the drawer lists the thread; reply; resolve (highlight disappears, thread moves under Resolved filter); reopen; edit the page to remove the anchored text and confirm the thread shows under "Detached". Verify a selection inside a math expression shows the "can't comment on math" hint.

- [ ] **Step 5: Final commit**

```bash
git add -- docs/superpowers/plans/2026-05-26-anchored-comments.md
git commit -m "docs(comments): implementation plan for anchored comments"
```
(Stage files by name; do not `git add -A`.)

---

## Self-review notes (for the implementer)

- **Spec coverage:** data model (T1), API types (T2), DAO (T3), wiring (T4), REST + permissions incl. author-only edit and moderator delete (T5), servlet swap + legacy removal (T6), wire-level IT (T7), selector capture + math rejection (T8), re-anchoring + orphans + resolved-not-highlighted (T9), client (T10), drawer with filter/reply/resolve/detached (T11), reader integration + cross-focus + CSS + CommentsPanel removal (T12), legacy export (T13), full verification (T14). Every spec section maps to a task.
- **Type consistency:** `CommentStore` method names (`createThread`, `addComment`, `editComment`, `deleteComment`, `deleteThread`, `resolve`, `reopen`, `listByCanonicalId`, `findThread`, `findComment`) are identical across T3/T5. JSON wire shape (`threads[]`, `anchor.{exact,prefix,suffix}`, `comments[]`, `status`) is identical across T5/T9/T11.
- **Known verification points flagged inline:** PATCH dispatch in `RestServletBase.service` (T5 note); the exact `request(...)` body/method convention in client.js (T10); the stylesheet the reader imports (T12).

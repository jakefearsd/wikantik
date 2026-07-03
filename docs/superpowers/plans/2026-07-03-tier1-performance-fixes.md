# Tier-1 Performance Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the five Tier-1 performance bottlenecks found in the 2026-07-03 audit: per-request full-corpus RDFS snapshot builds on `/sparql`, the brute-force default dense backend + O(corpus) in-memory upsert, the `/api/pages` full-wiki ACL scan under a global lock, the `GET /admin/users` N+1 query storm, and the rendering/SSR waste (throwaway flexmark parsers, triple frontmatter parses, `no-store` on every `/wiki/*` response).

**Architecture:** Every fix is invalidate-on-write caching or batch-instead-of-loop, applied at the narrowest seam: snapshot cache inside `OntologyModelManager` (all callers benefit, zero caller changes); Caffeine caches replacing a broken `synchronized(WeakHashMap)` and per-minute ACL body re-scans; a batch viewability method on `AuthorizationManager` with a blanket-grant fast path; a single-query `findAllProfiles()` on `UserDatabase`; static reuse of flexmark machinery; one `ParsedPage` threaded through the SSR path plus ETag/304 revalidation.

**Tech Stack:** Java 21, Maven multi-module, JUnit 5 + Mockito, Caffeine (already a wikantik-main dependency — see `JDBCUserDatabase`), Apache Jena (wikantik-ontology), flexmark, Lucene HNSW.

## Global Constraints

- TDD: every task writes a failing test before the fix (CLAUDE.md house rule).
- Never swallow exceptions with an empty catch — at minimum `LOG.warn()` with context.
- Sole developer, work directly on `main`. Stage files **by name** — never `git add -A`.
- Commit messages 1–3 lines.
- After any constructor/interface signature change, run `mvn test-compile -pl <module>` — `mvn compile` skips test sources.
- `DecompositionArchTest` freezes `engine.getManager(...)` call sites (ArchUnit store at `wikantik-main`). Do **not** add new `getManager` call sites; if a run goes red, restore the freeze store from git before retrying (`git checkout -- wikantik-main/src/test/resources/archunit-store/` or wherever the store lives — check `git status` after a red run).
- One full build at the end, not per-edit: `mvn clean install -DskipITs` (no `-T 1C` — known flaky), then the full IT reactor `mvn clean install -Pintegration-tests -fae` (never parallel) before the final commit is considered done.
- Unit-test single classes with `mvn test -pl <module> -Dtest=ClassName`.

## Task Order & Independence

Tasks 1, 5, 6, 7, 8 are fully independent. Task 2 → 3 → 4 must run in order (4 builds on 2+3). Task 9 (changelog + final verification) runs last.

---

### Task 1: Ontology snapshot caching in `OntologyModelManager`

**Files:**
- Modify: `wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyModelManager.java`
- Test (create): `wikantik-ontology/src/test/java/com/wikantik/ontology/OntologyModelManagerSnapshotCacheTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `inferenceSnapshot()` / `unionSnapshot()` keep their exact signatures but now return a **shared, cached** `Model` that callers must treat as read-only (all five production callers — `OntologySparqlResource`, `SparqlQueryTool`, `JenaOntologyQueryService`, `AdminOntologyResource`, `DriftWiringHelper`, `OntologyExportResource` — only read; no caller changes needed).

**Why:** `inferenceSnapshot()` copies every named graph into a fresh in-memory model and eagerly materializes all RDFS entailments — on **every** call, including every hit to the public, CORS-open `/sparql` endpoint. All dataset mutations already funnel through this class's four write methods, so an invalidate-on-write cache is exact.

- [ ] **Step 1: Write the failing test**

```java
/* (standard ASF license header — copy from OntologyModelManagerTest.java) */
package com.wikantik.ontology;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyModelManagerSnapshotCacheTest {

    private OntologyModelManager mgr;

    @BeforeEach
    void setUp() {
        mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
    }

    @Test
    void inferenceSnapshotIsCachedBetweenWrites() {
        assertSame( mgr.inferenceSnapshot(), mgr.inferenceSnapshot(),
            "repeated reads with no intervening write must return the cached snapshot" );
    }

    @Test
    void unionSnapshotIsCachedBetweenWrites() {
        assertSame( mgr.unionSnapshot(), mgr.unionSnapshot() );
    }

    @Test
    void replaceNamedGraphInvalidatesSnapshots() {
        final Model before = mgr.inferenceSnapshot();
        final Model unionBefore = mgr.unionSnapshot();

        final Model g = ModelFactory.createDefaultModel();
        g.add( g.createResource( "urn:test:subject" ), RDFS.label, "cache-invalidation-probe" );
        mgr.replaceNamedGraph( "urn:test:graph", g );

        final Model after = mgr.inferenceSnapshot();
        assertNotSame( before, after );
        assertTrue( after.contains( after.createResource( "urn:test:subject" ), RDFS.label ) );
        assertNotSame( unionBefore, mgr.unionSnapshot() );
    }

    @Test
    void removeNamedGraphInvalidatesSnapshots() {
        final Model g = ModelFactory.createDefaultModel();
        g.add( g.createResource( "urn:test:subject" ), RDFS.label, "to-be-removed" );
        mgr.replaceNamedGraph( "urn:test:graph", g );

        final Model withGraph = mgr.inferenceSnapshot();
        mgr.removeNamedGraph( "urn:test:graph" );
        final Model without = mgr.inferenceSnapshot();

        assertNotSame( withGraph, without );
        assertFalse( without.contains( without.createResource( "urn:test:subject" ), RDFS.label ) );
    }

    @Test
    void clearAboxInvalidatesSnapshots() {
        final Model g = ModelFactory.createDefaultModel();
        g.add( g.createResource( "urn:test:subject" ), RDFS.label, "abox" );
        mgr.replaceNamedGraph( "urn:test:graph", g );
        final Model before = mgr.inferenceSnapshot();

        mgr.clearAbox();
        assertNotSame( before, mgr.inferenceSnapshot() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-ontology -Dtest=OntologyModelManagerSnapshotCacheTest`
Expected: FAIL — `inferenceSnapshotIsCachedBetweenWrites` and `unionSnapshotIsCachedBetweenWrites` fail on `assertSame` (current code builds a fresh model per call). The invalidation tests pass already (fresh build each time) — that's fine; they are the regression net for the cache.

- [ ] **Step 3: Implement the cache**

In `OntologyModelManager.java`:

Add fields after `private final Dataset dataset;`:

```java
    /**
     * Cached detached snapshots, invalidated by every successful write
     * ({@link #loadTBox}, {@link #replaceNamedGraph}, {@link #removeNamedGraph},
     * {@link #clearAbox}). Callers receive a SHARED model and must treat it as
     * read-only; all production callers only run read queries against it.
     */
    private volatile Model cachedInferenceSnapshot;
    private volatile Model cachedUnionSnapshot;
    private final Object snapshotBuildLock = new Object();
```

Add a private invalidation helper:

```java
    private void invalidateSnapshots() {
        cachedInferenceSnapshot = null;
        cachedUnionSnapshot = null;
    }
```

Call `invalidateSnapshots();` immediately **after** `dataset.commit();` in all four write methods (`loadTBox`, `replaceNamedGraph`, `removeNamedGraph`, `clearAbox`). Do not invalidate on abort — an aborted write changes nothing.

Replace the bodies of `inferenceSnapshot()` and `unionSnapshot()` with double-checked lazy builds (rename the existing bodies to private `buildInferenceSnapshot()` / `buildUnionSnapshot()`):

```java
    /**
     * Detached RDFS inference model over (T-Box default graph union all named graphs).
     * Cached until the next write; the returned model is shared across callers and
     * MUST be treated as read-only.
     */
    public Model inferenceSnapshot() {
        Model snap = cachedInferenceSnapshot;
        if ( snap == null ) {
            synchronized ( snapshotBuildLock ) {
                snap = cachedInferenceSnapshot;
                if ( snap == null ) {
                    snap = buildInferenceSnapshot();
                    cachedInferenceSnapshot = snap;
                }
            }
        }
        return snap;
    }

    /** Detached union of the T-Box + all named graphs — NO inference. Cached; read-only. */
    public Model unionSnapshot() {
        Model snap = cachedUnionSnapshot;
        if ( snap == null ) {
            synchronized ( snapshotBuildLock ) {
                snap = cachedUnionSnapshot;
                if ( snap == null ) {
                    snap = buildUnionSnapshot();
                    cachedUnionSnapshot = snap;
                }
            }
        }
        return snap;
    }

    private Model buildInferenceSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            final Model union = ModelFactory.createDefaultModel();
            union.add( dataset.getDefaultModel() );
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                union.add( dataset.getNamedModel( it.next() ) );
            }
            final InfModel inf = ModelFactory.createRDFSModel( union );
            return ModelFactory.createDefaultModel().add( inf );
        } finally {
            dataset.end();
        }
    }

    private Model buildUnionSnapshot() {
        dataset.begin( ReadWrite.READ );
        try {
            final Model union = ModelFactory.createDefaultModel();
            union.add( dataset.getDefaultModel() );
            for ( final Iterator< String > it = dataset.listNames(); it.hasNext(); ) {
                union.add( dataset.getNamedModel( it.next() ) );
            }
            return union;
        } finally {
            dataset.end();
        }
    }
```

- [ ] **Step 4: Run the module's tests**

Run: `mvn test -pl wikantik-ontology`
Expected: PASS — the new cache test AND all existing `OntologyModelManagerTest` / `OntologyRebuildServiceTest` tests (they call `inferenceSnapshot()` after writes; a broken invalidation would turn them red).

- [ ] **Step 5: Compile dependents and run their ontology-touching tests**

Run: `mvn test-compile -pl wikantik-main,wikantik-rest,wikantik-knowledge -am -q -DskipTests`
Then: `mvn test -pl wikantik-main -Dtest=SemanticHeadOntologyAgreementTest`
Expected: BUILD SUCCESS / PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-ontology/src/main/java/com/wikantik/ontology/OntologyModelManager.java \
        wikantik-ontology/src/test/java/com/wikantik/ontology/OntologyModelManagerSnapshotCacheTest.java
git commit -m "perf(ontology): cache inference/union snapshots, invalidate on write

/sparql, sparql_query MCP, and ontology query expansion no longer rebuild the full RDFS materialization per call."
```

---

### Task 2: `PermissionFactory` — lock-free, collision-free permission cache

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/permissions/PermissionFactory.java`
- Test (create): `wikantik-main/src/test/java/com/wikantik/auth/permissions/PermissionFactoryTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `PermissionFactory.getPagePermission(Page, String)` / `(String, String)` — unchanged signatures, now lock-free on cache hits. Task 4 relies on this being cheap.

**Why:** The current cache is a process-global `synchronized(WeakHashMap)` acquired on **every** permission check in the JVM, keyed by XOR'd hashcodes — the code's own FIXME admits two different pages can collide and return the *wrong permission object*. That is a latent correctness bug, not just contention.

- [ ] **Step 1: Write the failing test**

```java
/* (standard ASF license header) */
package com.wikantik.auth.permissions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PermissionFactoryTest {

    /**
     * "Aa" and "BB" have identical String hashCodes. The legacy XOR-hashcode key
     * made the second lookup return the FIRST page's cached permission — a real
     * authorization-object mixup, admitted by the old FIXME.
     */
    @Test
    void collidingHashCodesYieldDistinctPermissions() {
        assertEquals( "Aa".hashCode(), "BB".hashCode(), "fixture precondition" );

        final PagePermission pa = PermissionFactory.getPagePermission( "Aa", "view" );
        final PagePermission pb = PermissionFactory.getPagePermission( "BB", "view" );

        assertEquals( "Aa", pa.getPage() );
        assertEquals( "BB", pb.getPage() );
    }

    @Test
    void sameKeyReturnsCachedInstance() {
        assertSame( PermissionFactory.getPagePermission( "PermissionFactoryTestPage", "view" ),
                    PermissionFactory.getPagePermission( "PermissionFactoryTestPage", "view" ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=PermissionFactoryTest`
Expected: FAIL — `collidingHashCodesYieldDistinctPermissions` asserts `"BB"` but gets `"Aa"` (cache collision returns the wrong permission).

- [ ] **Step 3: Replace the cache**

Rewrite the caching part of `PermissionFactory.java` (keep the class shell, javadoc, and the two public overloads' signatures):

```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wikantik.api.core.Page;

public final class PermissionFactory
{
    private PermissionFactory() {}

    /**
     * Bounded, lock-free-on-hit cache of immutable PagePermission objects.
     * Replaces the legacy synchronized(WeakHashMap) keyed by XOR'd hashcodes,
     * which (a) took a process-global monitor on every permission check and
     * (b) could return the wrong permission on a hashcode collision.
     */
    private static final Cache< String, PagePermission > CACHE =
        Caffeine.newBuilder().maximumSize( 50_000 ).build();

    public static PagePermission getPagePermission( final Page page, final String actions )
    {
        return getPagePermission( page.getWiki(), page.getName(), actions );
    }

    public static PagePermission getPagePermission( final String page, final String actions )
    {
        return getPagePermission( "", page, actions );
    }

    private static PagePermission getPagePermission( final String wiki, final String page, final String actions )
    {
        final String key = wiki + ' ' + page + ' ' + actions;
        return CACHE.get( key, k -> {
            final String qualified = wiki.isEmpty() ? page : wiki + ":" + page;
            return new PagePermission( qualified, actions );
        } );
    }
}
```

- [ ] **Step 4: Run the auth permission test suite**

Run: `mvn test -pl wikantik-main -Dtest='PermissionFactoryTest,PagePermissionTest,PermissionFilterTest,AllPermissionTest,GroupPermissionTest,WikiPermissionTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/permissions/PermissionFactory.java \
        wikantik-main/src/test/java/com/wikantik/auth/permissions/PermissionFactoryTest.java
git commit -m "fix(auth): replace PermissionFactory synchronized WeakHashMap with Caffeine

Kills the process-global lock on every permission check and the XOR-hashcode key collision that could return the wrong page's permission."
```

---

### Task 3: `DefaultAclManager` — version-keyed ACL cache

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/acl/DefaultAclManager.java`
- Test (create): `wikantik-main/src/test/java/com/wikantik/auth/acl/DefaultAclManagerCacheTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `getPermissions(Page)` unchanged signature; repeated calls for the same page **version** no longer re-read the page body. Task 4 relies on this.

**Why:** `getPermissions()` falls back to `extractAclFromPageText()` — a `getPureText()` body read plus regex scan — whenever the `Page` instance has no parsed ACL. `Page` instances are recycled every 60s by the page cache, so bulk viewability filtering re-scans every page body every minute. A cache keyed by (name, version, lastModified) survives Page-instance churn and needs no event wiring.

**Note on sharing:** the cached `Acl` object is shared across `Page` instances. That matches existing behavior (the same `Acl` is already shared by all requests holding the same cached `Page`), including `DefaultAuthorizationManager.decide()`'s idempotent in-place principal resolution.

- [ ] **Step 1: Write the failing test**

Model the engine setup on the existing `DefaultAclManagerTest.java` in the same package (same `TestEngine` construction, same way it obtains the `AclManager` — copy its `@BeforeEach` verbatim and adapt). The test logic:

```java
/* (standard ASF license header; imports/setup copied from DefaultAclManagerTest) */
package com.wikantik.auth.acl;

// ... same imports/bootstrap as DefaultAclManagerTest, plus:
import com.wikantik.api.core.Page;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultAclManagerCacheTest {

    // engine + aclManager fields initialised exactly like DefaultAclManagerTest

    @Test
    void aclSurvivesPageInstanceChurn() throws Exception {
        engine.saveText( "AclCacheProbe", "[{ALLOW view Admin}]\n\nBody." );

        final Page first = engine.getManager( com.wikantik.api.managers.PageManager.class ).getPage( "AclCacheProbe" );
        final var acl1 = aclManager.getPermissions( first );

        // Simulate the page cache handing out a FRESH Page instance for the same
        // stored version (what happens every 60s TTL expiry): same name/version/
        // lastModified, no parsed ACL attached yet.
        final Page second = com.wikantik.api.spi.Wiki.contents().page( engine, "AclCacheProbe" );
        second.setVersion( first.getVersion() );
        second.setLastModified( first.getLastModified() );

        final var acl2 = aclManager.getPermissions( second );
        assertSame( acl1, acl2, "same page version must be served from the ACL cache, not re-extracted" );
    }

    @Test
    void newPageVersionInvalidatesCachedAcl() throws Exception {
        engine.saveText( "AclCacheProbe2", "[{ALLOW view Admin}]\n\nBody." );
        final Page v1 = engine.getManager( com.wikantik.api.managers.PageManager.class ).getPage( "AclCacheProbe2" );
        final var acl1 = aclManager.getPermissions( v1 );

        engine.saveText( "AclCacheProbe2", "[{ALLOW view Admin,Authenticated}]\n\nBody v2." );
        final Page v2raw = engine.getManager( com.wikantik.api.managers.PageManager.class ).getPage( "AclCacheProbe2" );
        final Page v2 = com.wikantik.api.spi.Wiki.contents().page( engine, "AclCacheProbe2" );
        v2.setVersion( v2raw.getVersion() );
        v2.setLastModified( v2raw.getLastModified() );

        final var acl2 = aclManager.getPermissions( v2 );
        assertNotSame( acl1, acl2, "a new version must be re-extracted, not served stale" );
    }
}
```

(If `Wiki.contents().page(engine, name)` differs in this codebase, use whatever `DefaultAclManagerTest` or `WikiPage` construction the neighboring tests use to build a detached `Page` — the essential property is: same name/version/lastModified, `getAcl() == null`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=DefaultAclManagerCacheTest`
Expected: FAIL — `aclSurvivesPageInstanceChurn` fails `assertSame` (fresh extraction builds a new `Acl` object).

- [ ] **Step 3: Implement the cache**

In `DefaultAclManager.java`, add:

```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

    /** Parsed-ACL cache surviving Page-instance churn. Keyed by page name; entry valid only for a matching (version, lastModified). */
    private record CachedAcl( int version, long lastModified, Acl acl ) {}

    private final Cache< String, CachedAcl > aclCache =
        Caffeine.newBuilder().maximumSize( 20_000 ).build();
```

Rewrite the non-attachment branch of `getPermissions(Page)`:

```java
    @Override
    public Acl getPermissions( final Page page ) {
        Acl acl = page.getAcl();
        LOG.debug( "page={}\n{}", page.getName(), acl );
        if( acl != null ) {
            return acl;
        }
        if( page instanceof Attachment att ) {
            final Page parent = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPage( att.getParentName() );
            return getPermissions( parent );
        }
        final long lastModified = page.getLastModified() == null ? 0L : page.getLastModified().getTime();
        final CachedAcl hit = aclCache.getIfPresent( page.getName() );
        if( hit != null && hit.version() == page.getVersion() && hit.lastModified() == lastModified ) {
            page.setAcl( hit.acl() );
            return hit.acl();
        }
        acl = extractAclFromPageText( page );
        page.setAcl( acl );
        aclCache.put( page.getName(), new CachedAcl( page.getVersion(), lastModified, acl ) );
        return acl;
    }
```

(Keep `extractAclFromPageText` exactly as-is. Preserve the original attachment-recursion semantics — attachments delegate to the parent page, which then hits the cache.)

- [ ] **Step 4: Run the ACL test suite**

Run: `mvn test -pl wikantik-main -Dtest='DefaultAclManagerCacheTest,DefaultAclManagerTest,DefaultAclManagerCITest,DefaultAclManagerCodeSpanTest,AclImplTest,AclEntryImplTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/acl/DefaultAclManager.java \
        wikantik-main/src/test/java/com/wikantik/auth/acl/DefaultAclManagerCacheTest.java
git commit -m "perf(auth): version-keyed ACL cache in DefaultAclManager

Stops re-reading page bodies for ACL extraction every time the 60s page cache recycles Page instances."
```

---

### Task 4: Batch viewability filter with blanket-grant fast path

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/AuthorizationManager.java` (add default method)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java` (add fast-path override)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/permissions/PermissionFilter.java` (add batch delegate)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/RestServletBase.java:452-464` (`filterViewable` delegates to batch)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java:133,164-183` (cluster map restricted to the returned slice)
- Test: extend `wikantik-main/src/test/java/com/wikantik/auth/permissions/PermissionFilterTest.java`

**Interfaces:**
- Consumes: Task 2 (cheap `PermissionFactory`), Task 3 (cheap `getPermissions`).
- Produces: `Set<String> AuthorizationManager.filterViewable(Session, Collection<String>)`; `Set<String> PermissionFilter.filterViewableQuietly(Session, Collection<String>)`. Both silent (no audit events) — this mirrors the existing `isPermitted` twin; do NOT route through `checkPermission` (see the audit-speculative-check memory: bulk visibility filtering is speculative, not enforcement).

**Why:** `/api/pages` runs a per-page `canAccessQuietly` over the entire wiki before paginating. Keeping filter-before-pagination is deliberate (stable `total`, no restricted-name/count leakage), so the fix is making the scan near-free: one blanket `view` check per request; pages without ACLs (the overwhelming majority) short-circuit to viewable; only ACL-carrying pages take the full per-page decision.

- [ ] **Step 1: Write the failing test**

Add to `PermissionFilterTest.java` (reuse its existing engine/session fixtures — it already constructs sessions and saves pages; follow its patterns exactly). Beware the mock-session-pollution gotcha: create anonymous sessions fresh, don't reuse the admin mock session:

```java
    @Test
    void batchViewabilityMatchesPerPageChecks() throws Exception {
        engine.saveText( "BatchPublicPage", "No ACL here." );
        engine.saveText( "BatchRestrictedPage", "[{ALLOW view Admin}]\n\nSecret." );

        final java.util.List< String > names = java.util.List.of(
            "BatchPublicPage", "BatchRestrictedPage", "BatchNoSuchPage" );

        final PermissionFilter pf = new PermissionFilter( engine );

        for ( final Session session : java.util.List.of( anonymousSession(), adminSession() ) ) {
            final java.util.Set< String > expected = new java.util.HashSet<>();
            for ( final String n : names ) {
                if ( pf.canAccessQuietly( session, n, "view" ) ) {
                    expected.add( n );
                }
            }
            assertEquals( expected, pf.filterViewableQuietly( session, names ),
                "batch filter must be decision-for-decision identical to canAccessQuietly" );
        }
    }
```

(`anonymousSession()` / `adminSession()`: use whatever helpers `PermissionFilterTest` already uses to build its sessions; if it has none for anonymous, `WikiSession.guestSession(engine)` is the established request-free guest session.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=PermissionFilterTest`
Expected: FAIL to **compile** (`filterViewableQuietly` does not exist). A compile failure is the correct "red" for a new-API test.

- [ ] **Step 3: Add the interface default method**

In `AuthorizationManager.java` add (with imports `java.util.Collection`, `java.util.HashSet`, `java.util.Set`, and `com.wikantik.auth.permissions.PagePermission`):

```java
    /**
     * Returns the subset of {@code pageNames} the session may {@code view}.
     * Silent — evaluates via {@link #isPermitted} semantics, never fires audit
     * events (bulk visibility filtering is speculative, not enforcement).
     * <p>
     * This default is a naive per-name loop for alternate implementations;
     * {@code DefaultAuthorizationManager} overrides it with a blanket-grant
     * fast path that skips per-page policy evaluation for ACL-less pages.
     */
    default Set< String > filterViewable( final Session session, final Collection< String > pageNames ) {
        final Set< String > out = new HashSet<>();
        for ( final String name : pageNames ) {
            if ( isPermitted( session, new PagePermission( name, "view" ) ) ) {
                out.add( name );
            }
        }
        return out;
    }
```

- [ ] **Step 4: Add the fast-path override in `DefaultAuthorizationManager`**

```java
    /** {@inheritDoc}
     * <p>Fast path: one blanket {@code <wiki>:*} view check per call; pages with
     * no ACL are then viewable without any per-page policy evaluation. Pages
     * carrying an ACL (and callers without the blanket grant) fall through to
     * the exact same per-page {@link #isPermitted} decision as before. */
    @Override
    public Set< String > filterViewable( final Session session, final Collection< String > pageNames ) {
        final Set< String > out = new HashSet<>();
        if ( session == null ) {
            return out;
        }
        final boolean blanketView = isPermitted( session,
            new PagePermission( engine.getApplicationName() + ":*", "view" ) );
        for ( final String name : pageNames ) {
            final Page page = pageManager().getPage( name );
            if ( blanketView && page != null ) {
                final Acl acl = aclManager().getPermissions( page );
                if ( acl == null || acl.isEmpty() ) {
                    out.add( name );
                    continue;
                }
            }
            final Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, "view" )
                : new PagePermission( engine.getApplicationName() + ":" + name, "view" );
            if ( isPermitted( session, perm ) ) {
                out.add( name );
            }
        }
        return out;
    }
```

(Use the class's existing `pageManager()` / `aclManager()` private accessors — do NOT add new `engine.getManager(...)` call sites; the ArchUnit freeze applies. Match existing imports; `PermissionFactory` is already imported by `decide()`'s neighborhood or add the import.)

**Correctness argument to preserve in the javadoc:** `decide()` allows a page iff (AllPermission ∨ bootstrap ∨ static-grant) ∧ (no-ACL ∨ ACL-match). `blanketView == true` means the session statically holds view on `<wiki>:*`, which implies the static grant for every concrete page (PagePermission implication), so for ACL-less pages the outcome is "allowed" — identical to `decide()`. Every other combination falls through to the unmodified per-page path.

- [ ] **Step 5: Add the `PermissionFilter` delegate**

```java
    /**
     * Batch form of {@link #canAccessQuietly} for {@code view}: returns the
     * subset of {@code pageNames} the session may view. Silent (no audit).
     */
    public java.util.Set< String > filterViewableQuietly( final Session session,
                                                          final Collection< String > pageNames ) {
        return AuthSubsystemBridge.fromLegacyEngine( engine ).authorization()
                .filterViewable( session, pageNames );
    }
```

- [ ] **Step 6: Compile + run the failing test**

Run: `mvn test-compile -pl wikantik-main && mvn test -pl wikantik-main -Dtest=PermissionFilterTest`
Expected: PASS (equivalence holds for anonymous and admin).

- [ ] **Step 7: Wire `RestServletBase.filterViewable` to the batch method**

Replace the loop body of `filterViewable` (keep signature + javadoc, note the delegation):

```java
    protected java.util.Set< String > filterViewable( final HttpServletRequest request,
                                                       final java.util.Collection< String > pageNames ) {
        final Engine eng = getEngine();
        final Session session = Wiki.session().find( eng, request );
        return new PermissionFilter( eng ).filterViewableQuietly( session, pageNames );
    }
```

- [ ] **Step 8: Restrict `PageListResource`'s cluster map to the returned slice**

In `PageListResource.doGet`, move the `loadClusterBySlug()` call **after** pagination and pass the slice names; change the helper to only retain those:

```java
        final java.util.Set< String > sliceNames = filtered.stream()
                .map( Page::getName )
                .collect( java.util.stream.Collectors.toSet() );
        final Map< String, String > clusterBySlug = loadClusterBySlug( sliceNames );
```

```java
    private Map< String, String > loadClusterBySlug( final java.util.Set< String > wanted ) {
        final Map< String, String > clusterBySlug = new HashMap<>();
        if ( wanted.isEmpty() ) {
            return clusterBySlug;
        }
        try {
            final StructuralIndexService idx = getSubsystems().pageGraph().structuralIndexService();
            if ( idx == null ) {
                return clusterBySlug;
            }
            for ( final PageDescriptor d : idx.sitemap().pages() ) {
                if ( wanted.contains( d.slug() ) && d.cluster() != null && !d.cluster().isBlank() ) {
                    clusterBySlug.put( d.slug(), d.cluster() );
                }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not load cluster metadata for page list: {}", e.getMessage() );
        }
        return clusterBySlug;
    }
```

- [ ] **Step 9: Run the REST tests**

Run: `mvn test-compile -pl wikantik-rest && mvn test -pl wikantik-rest -Dtest='PageListResourceTest'`
Then the module: `mvn test -pl wikantik-rest -q`
Expected: PASS — `PageListResourceTest` asserts response shape/ACL filtering and must be untouched behaviorally (same `total`, same restricted-page hiding, same cluster fields on returned entries).

- [ ] **Step 10: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/AuthorizationManager.java \
        wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java \
        wikantik-main/src/main/java/com/wikantik/auth/permissions/PermissionFilter.java \
        wikantik-main/src/test/java/com/wikantik/auth/permissions/PermissionFilterTest.java \
        wikantik-rest/src/main/java/com/wikantik/rest/RestServletBase.java \
        wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java
git commit -m "perf(auth,rest): batch viewability filter with blanket-grant fast path

/api/pages keeps filter-before-pagination semantics but ACL-less pages no longer pay a per-page policy evaluation; cluster map now built only for the returned slice."
```

---

### Task 5: `GET /admin/users` — single-query `findAllProfiles()`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java` (default method)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java` (override + extracted row mapper)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java:429-449`
- Test (create): `wikantik-main/src/test/java/com/wikantik/auth/user/UserDatabaseFindAllProfilesDefaultTest.java`
- Test: extend the existing JDBC user database test class (find it: `grep -rl "JDBCUserDatabase" wikantik-main/src/test/java | head`) with a `findAllProfiles` assertion.

**Interfaces:**
- Consumes: nothing.
- Produces: `Collection<UserProfile> UserDatabase.findAllProfiles() throws WikiSecurityException`.

**Why:** `handleListUsers` runs `SELECT * FROM users`, keeps only `wiki_name`, then re-fetches every row one-at-a-time via uncached `findByWikiName` — 1+N pool checkouts against `maxTotal=90`.

- [ ] **Step 1: Write the failing default-method test**

Model on the existing `UserDatabaseCountLockedUsersDefaultTest.java` (same package — it builds a minimal inline `UserDatabase` impl to exercise the interface default). Create `UserDatabaseFindAllProfilesDefaultTest.java` with the same inline-impl pattern:

```java
    @Test
    void defaultImplementationResolvesEveryWikiName() throws Exception {
        // inline UserDatabase stub with two profiles, exactly like
        // UserDatabaseCountLockedUsersDefaultTest builds one
        final Collection< UserProfile > all = db.findAllProfiles();
        assertEquals( 2, all.size() );
        assertEquals( Set.of( "u1", "u2" ),
            all.stream().map( UserProfile::getLoginName ).collect( Collectors.toSet() ) );
    }

    @Test
    void vanishedProfileIsSkippedNotFatal() throws Exception {
        // stub whose findByWikiName throws NoSuchPrincipalException for one of
        // three enumerated wiki names
        assertEquals( 2, db.findAllProfiles().size() );
    }
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `mvn test-compile -pl wikantik-main`
Expected: compile FAILURE — `findAllProfiles()` undefined.

- [ ] **Step 3: Add the interface default method**

In `UserDatabase.java` (mirror the `countLockedUsers` default-method style, including its logging discipline — no empty catch):

```java
    /**
     * Returns every stored {@link UserProfile}. This default implementation
     * iterates {@link #getWikiNames()} and resolves each profile via
     * {@link #findByWikiName(String)}; profiles that vanish between the
     * enumeration and the lookup are skipped. Implementations backed by a
     * database should override this with a single bulk query.
     *
     * @return all user profiles; never {@code null}
     * @throws WikiSecurityException if the profiles cannot be enumerated
     */
    default Collection< UserProfile > findAllProfiles() throws WikiSecurityException {
        final List< UserProfile > profiles = new ArrayList<>();
        for ( final Principal wikiName : getWikiNames() ) {
            try {
                profiles.add( findByWikiName( wikiName.getName() ) );
            } catch ( final NoSuchPrincipalException e ) {
                LogManager.getLogger( UserDatabase.class ).warn(
                    "Profile for wiki name {} vanished during enumeration: {}", wikiName.getName(), e.getMessage() );
            }
        }
        return profiles;
    }
```

(Add the needed imports; match however `countLockedUsers` already imports/loggs — if it uses a different logging pattern, copy that pattern instead.)

- [ ] **Step 4: Run the default-method test**

Run: `mvn test -pl wikantik-main -Dtest=UserDatabaseFindAllProfilesDefaultTest`
Expected: PASS.

- [ ] **Step 5: Extract the JDBC row mapper and add the bulk override**

In `JDBCUserDatabase.java`: extract the row-mapping block inside `findByPreparedStatement` (the `profile = newProfile(); profile.setUid(...) ... attributes` section) into:

```java
    private UserProfile mapProfileRow( final ResultSet rs ) throws SQLException {
        final UserProfile profile = newProfile();
        profile.setUid( rs.getString( "uid" ) );
        if ( profile.getUid() == null ) {
            profile.setUid( generateUid( this ) );
        }
        profile.setCreated( rs.getTimestamp( "created" ) );
        profile.setEmail( rs.getString( "email" ) );
        profile.setFullname( rs.getString( "full_name" ) );
        profile.setLastModified( rs.getTimestamp( "modified" ) );
        profile.setLastLogin( rs.getTimestamp( "last_login" ) );
        final Date lockExpiryDate = rs.getDate( "lock_expiry" );
        profile.setLockExpiry( rs.wasNull() ? null : lockExpiryDate );
        profile.setLoginName( rs.getString( "login_name" ) );
        profile.setPassword( rs.getString( "password" ) );
        profile.setBio( rs.getString( "bio" ) );
        profile.setPasswordMustChange( rs.getBoolean( "password_must_change" ) );
        final String rawAttributes = rs.getString( "attributes" );
        if ( rawAttributes != null ) {
            try {
                final Map< String, ? extends Serializable > userAttributes = Serializer.deserializeFromBase64( rawAttributes );
                profile.getAttributes().putAll( userAttributes );
            } catch ( final IOException e ) {
                LOG.error( "Could not parse user profile attributes!", e );
            }
        }
        return profile;
    }
```

Rewrite `findByPreparedStatement`'s loop to call `mapProfileRow( rs )` (keep the found/unique logic identical). Add the override:

```java
    /**
     * {@inheritDoc}
     * <p>Overrides the interface default with a single {@code SELECT * FROM users}
     * pass — the default's enumerate-then-refetch pattern costs 1+N pool
     * checkouts on every admin user-list view.
     */
    @Override
    public Collection< UserProfile > findAllProfiles() throws WikiSecurityException {
        final List< UserProfile > profiles = new ArrayList<>();
        try ( Connection conn = ds.getConnection();
              PreparedStatement ps = conn.prepareStatement( FIND_ALL );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                profiles.add( mapProfileRow( rs ) );
            }
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
        return profiles;
    }
```

Add a test to the existing JDBC user-database test class (it runs against in-memory H2 — follow its fixture):

```java
    @Test
    void findAllProfilesMatchesPerNameLookups() throws Exception {
        final Set< String > viaBulk = db.findAllProfiles().stream()
            .map( UserProfile::getLoginName ).collect( Collectors.toSet() );
        final Set< String > viaLoop = new HashSet<>();
        for ( final Principal p : db.getWikiNames() ) {
            viaLoop.add( db.findByWikiName( p.getName() ).getLoginName() );
        }
        assertEquals( viaLoop, viaBulk );
        assertFalse( viaBulk.isEmpty(), "fixture must contain seeded users" );
    }
```

- [ ] **Step 6: Switch `AdminUserResource.handleListUsers`**

```java
    private void handleListUsers( final HttpServletResponse response ) throws IOException {
        try {
            final UserDatabase db = getUserDatabase();
            final List< Map< String, Object > > users = new ArrayList<>();
            for ( final UserProfile profile : db.findAllProfiles() ) {
                users.add( profileToMap( profile ) );
            }
            users.sort( Comparator.comparing( m -> String.valueOf( m.get( "loginName" ) ) ) );
            sendJson( response, Map.of( "users", users ) );
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Failed to list users", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to list users" );
        }
    }
```

(Check `profileToMap`'s actual key for login name — if it's not `"loginName"`, sort on the real key. The old `HashSet` order was random, so deterministic sorting is a strict improvement; if `AdminUserResourceTest` asserts a specific order, align with it.)

- [ ] **Step 7: Run tests**

Run: `mvn test-compile -pl wikantik-main,wikantik-rest && mvn test -pl wikantik-main -Dtest='UserDatabaseFindAllProfilesDefaultTest,*JDBCUserDatabase*' && mvn test -pl wikantik-rest -Dtest=AdminUserResourceTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java \
        wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java \
        wikantik-main/src/test/java/com/wikantik/auth/user/UserDatabaseFindAllProfilesDefaultTest.java \
        <the modified JDBC test file>
git commit -m "perf(admin): findAllProfiles bulk query kills GET /admin/users N+1

1+N pool checkouts (N = user count) collapse to a single SELECT."
```

---

### Task 6: Rendering — stop rebuilding flexmark machinery per render

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/MarkupParser.java` (static pattern accessor + constant)
- Modify: `wikantik-main/src/main/java/com/wikantik/render/markdown/MarkdownRenderer.java` (drop throwaway parser)
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java` (shared stock extensions)
- Tests: existing renderer suite is the safety net; run it before AND after.

**Interfaces:**
- Consumes: nothing.
- Produces: `public static List<Pattern> MarkupParser.inlineImagePatterns(Engine)`; `public static final boolean MarkupParser.DEFAULT_IMAGE_INLINING = true`. Task 7 does not depend on these.

**Why:** `MarkdownRenderer`'s constructor reflectively instantiates a complete throwaway `MarkdownParser` (which itself builds a full flexmark `Parser` and does two auth-bridge lookups) solely to read two values that are (a) a field whose value at that point is always the default `true`, and (b) a list already cached on the engine. Additionally, `MarkdownDocument.options()` allocates 6 stock flexmark extension objects per call; they are stateless and safe to share.

- [ ] **Step 1: Green baseline**

Run: `mvn test -pl wikantik-main -Dtest='Markdown*Test' -q`
Expected: PASS. Record the test count — this refactor must end at the same count, all green. (This is a behavior-preserving refactor; the existing renderer tests ARE the characterization tests.)

- [ ] **Step 2: Add static accessors to `MarkupParser`**

Near the existing `initInlineImagePatterns()` (keep the engine-attribute cache exactly — same `INLINE_IMAGE_PATTERNS` key):

```java
    /**
     * Image inlining default for renderers. A parser reports {@code false} only
     * after an explicit {@link #enableImageInlining} call; the value read off a
     * freshly-constructed parser (the old MarkdownRenderer pattern) is always
     * this default.
     */
    public static final boolean DEFAULT_IMAGE_INLINING = true;

    /**
     * Engine-cached compiled inline-image patterns. Identical cache and
     * semantics to the parser's own {@link #initInlineImagePatterns()}; callable
     * without constructing a parser.
     */
    public static List< Pattern > inlineImagePatterns( final Engine engine ) {
        List< Pattern > compiledpatterns = engine.getAttribute( INLINE_IMAGE_PATTERNS );
        if( compiledpatterns == null ) {
            compiledpatterns = new ArrayList<>( 20 );
            final Collection< String > ptrns = engine.getAllInlinedImagePatterns();
            for( final String pattern : ptrns ) {
                try {
                    compiledpatterns.add( compileGlobPattern( pattern ) );
                } catch( final PatternSyntaxException e ) {
                    LOG.error( "Malformed pattern [{}] in properties: ", pattern, e );
                }
            }
            engine.setAttribute( INLINE_IMAGE_PATTERNS, compiledpatterns );
        }
        return Collections.unmodifiableList( compiledpatterns );
    }
```

Rewrite the instance method to delegate:

```java
    protected final void initInlineImagePatterns() {
        inlineImagePatterns = inlineImagePatterns( engine );
    }
```

(Ensure `protected boolean inlineImages = true;` at line 60 is updated to `protected boolean inlineImages = DEFAULT_IMAGE_INLINING;` so the two can never drift. If `LOG` in `MarkupParser` is not static, make the static method use its own `LogManager.getLogger( MarkupParser.class )` handle.)

- [ ] **Step 3: Rewrite `MarkdownRenderer`'s constructor**

```java
	public MarkdownRenderer( final Context context, final WikiDocument doc ) {
		super( context, doc );
		this.allowHtml = context.getBooleanWikiProperty( MarkupParser.PROP_ALLOWHTML, false );
		// The previous implementation instantiated a full throwaway MarkdownParser
		// (building a second flexmark Parser) just to read the image-inlining
		// default and the engine-cached pattern list. Read both directly.
		renderer = HtmlRenderer.builder( MarkdownDocument.options( context,
				MarkupParser.DEFAULT_IMAGE_INLINING,
				MarkupParser.inlineImagePatterns( context.getEngine() ) ) ).build();
	}
```

Remove the now-unused `getParser` call and `RenderingSubsystemBridge`/`StringUtils` imports if orphaned. Check for sibling renderers with the same pattern: `grep -rn "getParser( context" wikantik-main/src/main/java/com/wikantik/render/` — apply the same change to any WYSIWYG variant found.

- [ ] **Step 4: Share the stock flexmark extensions in `MarkdownDocument.options()`**

```java
    // Stock flexmark extensions are stateless configuration carriers — flexmark's
    // own guidance is to create them once and share across parsers/renderers.
    // Only MarkdownForWikantikExtension is context-bound and must stay per-call.
    private static final com.vladsch.flexmark.util.misc.Extension ATTRIBUTES_EXT = AttributesExtension.create();
    private static final com.vladsch.flexmark.util.misc.Extension DEFINITION_EXT = DefinitionExtension.create();
    private static final com.vladsch.flexmark.util.misc.Extension FOOTNOTE_EXT = FootnoteExtension.create();
    private static final com.vladsch.flexmark.util.misc.Extension GITLAB_EXT = GitLabExtension.create();
    private static final com.vladsch.flexmark.util.misc.Extension TABLES_EXT = TablesExtension.create();
    private static final com.vladsch.flexmark.util.misc.Extension TOC_EXT = TocExtension.create();
```

and in `options()`:

```java
        options.set( Parser.EXTENSIONS, Arrays.asList( new MarkdownForWikantikExtension( context, isImageInlining, inlineImagePatterns ),
                                                       ATTRIBUTES_EXT, DEFINITION_EXT, FOOTNOTE_EXT,
                                                       GITLAB_EXT, TABLES_EXT, TOC_EXT ) );
```

(If the `Extension` interface lives at a different FQN in the pinned flexmark version, use whatever `AttributesExtension.create()` declares as its return supertype.)

- [ ] **Step 5: Run the renderer + parser suites**

Run: `mvn test -pl wikantik-main -Dtest='Markdown*Test,*Renderer*Test,*Parser*Test' -q`
Expected: PASS, same count as the Step-1 baseline. If any WYSIWYG/preview test fails on image inlining, the failing context genuinely toggles `enableImageInlining` — inspect and thread the real value through instead of the constant (and note it in the commit).

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/parser/MarkupParser.java \
        wikantik-main/src/main/java/com/wikantik/render/markdown/MarkdownRenderer.java \
        wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java
git commit -m "perf(render): stop building a throwaway MarkdownParser per render; share stock flexmark extensions

Each render-cache miss previously constructed two extra flexmark Parsers and 6 fresh extension objects."
```

**Explicitly out of scope (documented follow-up):** the second full parse in `MarkdownParser.collectLinks()` (bare `LINK_SCANNER` pass to capture pre-mutation link URLs). Eliminating it requires re-plumbing the link mutator chains through `MarkdownForWikantikExtension`'s post-processors — a ReferenceManager-correctness-sensitive change that deserves its own plan. The bare parse is also the cheaper of the two passes (no extensions).

---

### Task 7: SSR `/wiki/*` — single frontmatter parse + ETag/304 revalidation

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java` (ParsedPage overloads)
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/PageSeoModel.java` (ParsedPage overload)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java`
- Test: extend `wikantik-main/src/test/java/com/wikantik/ui/` renderer tests + `wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `SemanticHeadRenderer.renderHead(String, ParsedPage, String, String, Date)`, `SemanticHeadRenderer.renderBodyFragment(String, ParsedPage)`, `PageSeoModel.from(String, ParsedPage, String, String, Date)`; package-private `SpaRoutingFilter.etagFor(String, int, long)`.

**Why:** every `/wiki/{page}` navigation parses the same frontmatter 2–3× (renderHead → PageSeoModel, buildPageIsland, plus the render on cache miss) and ships `Cache-Control: no-store` + `Vary: *`, so no client can ever revalidate. The SSR output for a page is fully determined by (shell build, page version, lastModified) — the render cache is already content-hash-shared across users — so a weak ETag with `private, no-cache` is safe and turns repeat navigations into 304s that skip the body read, render, and injection entirely.

- [ ] **Step 1: Write the failing overload-equivalence test**

Add to the existing `SemanticHeadRenderer` test class (find it: `ls wikantik-main/src/test/java/com/wikantik/ui/`):

```java
    @Test
    void parsedPageOverloadMatchesRawTextPath() {
        final String raw = "---\ntitle: T\nsummary: A fifty-plus character summary for the head renderer test.\ntags: [a, b]\n---\n# Heading\n\nBody text.";
        final java.util.Date modified = new java.util.Date( 1_700_000_000_000L );
        final com.wikantik.api.frontmatter.ParsedPage parsed = com.wikantik.api.frontmatter.FrontmatterParser.parse( raw );

        assertEquals(
            SemanticHeadRenderer.renderHead( "TestPage", raw, "https://w.example", "Wikantik", modified ),
            SemanticHeadRenderer.renderHead( "TestPage", parsed, "https://w.example", "Wikantik", modified ) );

        assertEquals(
            SemanticHeadRenderer.renderBodyFragment( "TestPage", raw ),
            SemanticHeadRenderer.renderBodyFragment( "TestPage", parsed ) );
    }
```

Add to `SpaRoutingFilterTest.java`:

```java
    @Test
    void etagIsStableAndVersionSensitive() {
        final String a = SpaRoutingFilter.etagFor( "abc123", 4, 1_700_000_000_000L );
        assertEquals( a, SpaRoutingFilter.etagFor( "abc123", 4, 1_700_000_000_000L ) );
        assertNotEquals( a, SpaRoutingFilter.etagFor( "abc123", 5, 1_700_000_000_000L ) );
        assertNotEquals( a, SpaRoutingFilter.etagFor( "abc123", 4, 1_700_000_000_001L ) );
        assertNotEquals( a, SpaRoutingFilter.etagFor( "zzz999", 4, 1_700_000_000_000L ) );
        assertTrue( a.startsWith( "W/\"" ) && a.endsWith( "\"" ) );
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test-compile -pl wikantik-main,wikantik-rest`
Expected: compile FAILURE (new overloads + `etagFor` missing).

- [ ] **Step 3: Add the ParsedPage overloads**

`PageSeoModel.java` — split `from`:

```java
    static PageSeoModel from( final String pageName, final String rawPageText, final String baseUrl,
                               final String appName, final Date modified ) {
        return from( pageName, FrontmatterParser.parse( orEmpty( rawPageText ) ), baseUrl, appName, modified );
    }

    static PageSeoModel from( final String pageName, final ParsedPage parsed, final String baseUrl,
                               final String appName, final Date modified ) {
        final Map< String, Object > meta = parsed.metadata();
        // ... existing body from the old method, starting at the line after the parse ...
    }
```

`SemanticHeadRenderer.java` — same split for `renderHead(…, Date)` (the 4-arg overload keeps delegating as today) and for `renderBodyFragment`:

```java
    public static String renderHead( final String pageName, final String rawPageText,
                                      final String baseUrl, final String appName, final Date modified ) {
        return renderHead( pageName, FrontmatterParser.parse( rawPageText == null ? "" : rawPageText ),
                baseUrl, appName, modified );
    }

    public static String renderHead( final String pageName, final ParsedPage parsed,
                                      final String baseUrl, final String appName, final Date modified ) {
        final PageSeoModel model = PageSeoModel.from( pageName, parsed, baseUrl, appName, modified );
        // ... existing body unchanged ...
    }

    public static String renderBodyFragment( final String pageName, final String rawPageText ) {
        return renderBodyFragment( pageName, FrontmatterParser.parse( rawPageText == null ? "" : rawPageText ) );
    }

    public static String renderBodyFragment( final String pageName, final ParsedPage parsed ) {
        final String body = parsed.body() == null ? "" : parsed.body();
        // ... existing body from the first line after the parse ...
    }
```

- [ ] **Step 4: Thread one ParsedPage through `SpaRoutingFilter.injectSemantic`**

After `rawText` is loaded:

```java
        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
        final String head = SemanticHeadRenderer.renderHead( pageName, parsed, baseUrl, appName, modified );
```

...fallback body:

```java
        final String rootBody = ( contentHtml != null && !contentHtml.isBlank() )
                ? contentHtml
                : SemanticHeadRenderer.renderBodyFragment( pageName, parsed );
```

...and change `buildPageIsland` to accept the `ParsedPage` instead of re-parsing:

```java
    private static String buildPageIsland( final String pageName, final ParsedPage parsed,
                                           final String contentHtml ) {
        try {
            final java.util.Map< String, Object > island = new java.util.LinkedHashMap<>();
            island.put( "name", pageName );
            island.put( "content", parsed.body() );
            island.put( "contentHtml", contentHtml );
            island.put( "metadata", parsed.metadata() );
            island.put( "exists", true );
            return "<script>window.__WIKANTIK_PAGE__=" + ISLAND_GSON.toJson( island ) + ";</script>";
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: data-island build failed for '{}': {}", pageName, ex.getMessage() );
            return null;
        }
    }
```

- [ ] **Step 5: Add ETag/304 to `serveIndexHtml`**

Add helpers to `SpaRoutingFilter`:

```java
    private volatile String shellFingerprint;

    /** SHA-256-prefix fingerprint of the deployed index.html; stable per deployment. */
    private String shellFingerprint( final ServletContext ctx ) {
        String fp = shellFingerprint;
        if ( fp == null ) {
            try ( InputStream in = ctx != null ? ctx.getResourceAsStream( "/index.html" ) : null ) {
                if ( in == null ) {
                    return "0";
                }
                final byte[] digest = java.security.MessageDigest.getInstance( "SHA-256" )
                        .digest( in.readAllBytes() );
                final StringBuilder sb = new StringBuilder( 12 );
                for ( int i = 0; i < 6; i++ ) {
                    sb.append( String.format( "%02x", digest[ i ] ) );
                }
                fp = sb.toString();
                shellFingerprint = fp;
            } catch ( final Exception e ) {
                LOG.warn( "SpaRoutingFilter: shell fingerprint failed: {}", e.getMessage() );
                return "0";
            }
        }
        return fp;
    }

    /** Weak ETag for a served /wiki/{page} document: shell build + page version + mtime. */
    static String etagFor( final String shellFp, final int version, final long lastModifiedMillis ) {
        return "W/\"" + shellFp + '-' + version + '-' + lastModifiedMillis + '"';
    }

    /** Null-safe page lookup mirroring injectSemantic's ladder; null = engine/pm/page unavailable. */
    private Page resolvePageForCaching( final String pageName ) {
        final Engine eng = resolveEngine();
        if ( eng == null || pageName == null || pageName.isEmpty() ) {
            return null;
        }
        try {
            final PageManager pm = PageSubsystemBridge.fromLegacyEngine( eng ).pages();
            if ( pm == null || !pm.wikiPageExists( pageName ) ) {
                return null;
            }
            return pm.getPage( pageName );
        } catch ( final RuntimeException ex ) {
            return null;
        }
    }
```

In `serveIndexHtml`, replace the unconditional `setNoCacheHeaders( resp );` with:

```java
        final Page cacheablePage = pageName != null && !pageName.isEmpty()
                ? resolvePageForCaching( pageName ) : null;
        if ( cacheablePage != null ) {
            // The SSR output for a page is a pure function of (shell build, page
            // version, mtime) — the underlying render cache is already shared
            // across users by content hash. private+no-cache forces revalidation
            // on every navigation; matching validators short-circuit to a 304
            // before any body read, render, or injection work.
            final long lm = cacheablePage.getLastModified() == null
                    ? 0L : cacheablePage.getLastModified().getTime();
            final String etag = etagFor( shellFingerprint( req.getServletContext() ),
                    Math.max( cacheablePage.getVersion(), 1 ), lm );
            resp.setHeader( "ETag", etag );
            resp.setHeader( "Cache-Control", "private, no-cache" );
            if ( etag.equals( req.getHeader( "If-None-Match" ) ) ) {
                resp.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
                return;
            }
        } else {
            setNoCacheHeaders( resp );
        }
```

(Missing pages keep the existing `no-store` + 404-status path; non-page SPA routes are unchanged.)

- [ ] **Step 6: Run unit tests**

Run: `mvn test -pl wikantik-main -Dtest='SemanticHead*Test,PageSeoModel*'` and `mvn test -pl wikantik-rest -Dtest=SpaRoutingFilterTest`
Expected: PASS.

- [ ] **Step 7: Add a wire-level IT for the 304 path**

Create `WikiSsrConditionalGetIT` in the REST-API IT module under `wikantik-it-tests` (place it next to the existing IT that fetches `/wiki/` SSR pages — find it with `grep -rln "wiki/Main" wikantik-it-tests/*/src/test/java | head`; copy that class's base-URL/bootstrap helpers):

```java
    @Test
    void secondNavigationRevalidatesWith304() throws Exception {
        final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        final java.net.URI uri = java.net.URI.create( baseUrl() + "/wiki/Main" );

        final var first = http.send(
            java.net.http.HttpRequest.newBuilder( uri ).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, first.statusCode() );
        final String etag = first.headers().firstValue( "ETag" ).orElseThrow();
        assertTrue( first.headers().firstValue( "Cache-Control" ).orElse( "" ).contains( "no-cache" ) );

        final var second = http.send(
            java.net.http.HttpRequest.newBuilder( uri ).header( "If-None-Match", etag ).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString() );
        assertEquals( 304, second.statusCode() );
        assertTrue( second.body().isEmpty() );
    }
```

(Remember the IT-overlay gotcha: the IT modules overlay wikantik-war — run with `clean` so the overlay picks up the filter change.)

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ui/SemanticHeadRenderer.java \
        wikantik-main/src/main/java/com/wikantik/ui/PageSeoModel.java \
        wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java \
        <the modified/added test files>
git commit -m "perf(ssr): parse frontmatter once per /wiki/* request; ETag+304 revalidation

Replaces no-store/Vary:* with private,no-cache + weak ETag (shell fingerprint, page version, mtime); repeat navigations skip body read, render, and injection."
```

---

### Task 8: Dense backend default → `lucene-hnsw`; cheap in-memory upsert

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties:1255-1263`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java:149-155`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/InMemoryChunkVectorIndex.java`
- Test: existing `InMemoryChunkVectorIndex` test class (find it: `ls wikantik-main/src/test/java/com/wikantik/search/hybrid/ | grep -i inmemory`).

**Interfaces:**
- Consumes: nothing.
- Produces: no API changes. Config default changes from `inmemory` to `lucene-hnsw`.

**Why (default flip):** the shipped default runs an O(corpus) brute-force scan per query and an O(corpus) snapshot rebuild per page save. `lucene-hnsw` is already the prod (docker1) choice, is RAM-backed (`ByteBuffersDirectory` — no new deploy requirements), has true incremental `upsertChunks`, and reads embeddings from the DB (no reload-after-reindex gotcha).
**Why (upsert fix):** even as a non-default backend, `upsertChunks` currently makes ~3 full copies of the corpus per save (per-row copy-out into `List<float[]>`, re-flatten, then `Snapshot`'s constructor defensively clones all three arrays — pure waste, since every constructor call site passes freshly-built arrays).

- [ ] **Step 1: Green baseline for the in-memory index**

Run: `mvn test -pl wikantik-main -Dtest='InMemory*'` — record count, expect PASS. These tests define the upsert contract (replace/append/remove) and are the net for Step 3.

- [ ] **Step 2: Flip the default backend**

`ini/wikantik.properties` — change line 1263 and the comment block above it:

```properties
# Dense retrieval backend. One of:
#   lucene-hnsw  — in-process Lucene HNSW ANN index (RAM), rebuilt on boot from
#                  content_chunk_embeddings; incremental upserts on save. Default.
#   pgvector     — pgvector HNSW lookup against content_chunk_embeddings.embedding.
#                  Requires V032 + the one-shot backfill at
#                  bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh.
#   inmemory     — brute-force float[] scan. O(corpus) per query and per save;
#                  dev/small-corpus fallback only.
wikantik.search.dense.backend = lucene-hnsw
```

`SearchWiringHelper.java` — update the fallback and the stale comment:

```java
        // Pick the dense retrieval backend up front so DenseRetriever (constructed
        // below) actually holds the configured impl. Default lucene-hnsw: true ANN
        // (sub-linear queries), RAM-backed, incremental upserts — matches prod.
        // 'inmemory' remains available for dev/small corpora.
        final String denseBackend = props.getProperty(
            "wikantik.search.dense.backend", "lucene-hnsw" ).toLowerCase( java.util.Locale.ROOT );
```

Check for tests pinned to the old default: `grep -rn "dense.backend\|inmemory" wikantik-main/src/test/java --include="*.java" -l` — any wiring test asserting the default backend must be updated to expect `lucene-hnsw` (that assertion change is the "failing test first" for this flip: update the expectation, watch it fail, then flip the default).

- [ ] **Step 3: Rewrite `upsertChunks` merge + remove `Snapshot` defensive clones**

`Snapshot` constructor — take ownership instead of cloning (all call sites pass freshly-built arrays; document it):

```java
        /** Takes ownership of the arrays — callers must pass freshly-built, never-shared arrays. */
        Snapshot( final UUID[] chunkIds, final String[] pageNames,
                  final float[] flatVectors, final int dim ) {
            this.chunkIds = chunkIds;
            this.pageNames = pageNames;
            this.flatVectors = flatVectors;
            this.dim = dim;
        }
```

Replace the merge section of `upsertChunks` (everything from `// Build the new snapshot` through the `this.snapshot = ...` publish) with a single-allocation direct build:

```java
        // Build the new snapshot with exactly one corpus-sized allocation: bulk-copy
        // retained rows straight from the previous flat buffer, then append the
        // freshly loaded rows. (The old path boxed every retained row into a
        // List<float[]>, re-flattened, and then cloned all three arrays again in
        // the Snapshot constructor — ~3 full corpus copies per page save.)
        final int prevSize = prev.size();
        final boolean[] keep = new boolean[ prevSize ];
        int retained = 0;
        for( int i = 0; i < prevSize; i++ ) {
            keep[ i ] = !targetIds.contains( prev.chunkIds[ i ] );
            if ( keep[ i ] ) retained++;
        }
        final int n = retained + loadedVecs.size();
        final UUID[] idArr = new UUID[ n ];
        final String[] pageArr = new String[ n ];
        final float[] flat = new float[ n * dim ];
        int w = 0;
        for( int i = 0; i < prevSize; i++ ) {
            if ( !keep[ i ] ) continue;
            idArr[ w ] = prev.chunkIds[ i ];
            pageArr[ w ] = prev.pageNames[ i ];
            System.arraycopy( prev.flatVectors, i * prevDim, flat, w * dim, dim );
            w++;
        }
        for( final Map.Entry< UUID, float[] > e : loadedVecs.entrySet() ) {
            idArr[ w ] = e.getKey();
            pageArr[ w ] = loadedPages.get( e.getKey() );
            System.arraycopy( e.getValue(), 0, flat, w * dim, dim );
            w++;
        }
        this.snapshot = new Snapshot( idArr, pageArr, flat, dim );
        this.lastRefreshMillis = System.currentTimeMillis();
        LOG.debug( "ChunkVectorIndex upserted: model={} touched={} rows={} dim={}",
            modelCode, targetIds.size(), n, dim );
```

(Note: when `prevSize > 0`, the earlier dim-consistency guard guarantees `prevDim == dim`, so the retained-row `arraycopy` stride is safe. `loadFromDatabase()` needs no change beyond benefiting from the clone removal.)

- [ ] **Step 4: Run the index tests**

Run: `mvn test -pl wikantik-main -Dtest='InMemory*,*ChunkVectorIndex*,SearchWiring*,Hybrid*' -q`
Expected: PASS, same behavior contract (replace/append/remove, topK ordering).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties \
        wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/search/hybrid/InMemoryChunkVectorIndex.java \
        <any updated test files>
git commit -m "perf(search): default dense backend lucene-hnsw; single-copy in-memory upsert

Brute-force O(corpus)-per-query scan is no longer the shipped default; inmemory upsert drops from ~3 corpus copies per save to 1."
```

---

### Task 9: Changelog + full verification

**Files:**
- Modify: `CHANGELOG.md` (unreleased section — follow the existing entry style)

- [ ] **Step 1: Add unreleased changelog entries** (one line per task, matching existing format).

- [ ] **Step 2: Full unit build**

Run: `mvn clean install -DskipITs`
Expected: BUILD SUCCESS. (No `-T 1C` — known to cause spurious security-policy-init cascades.) If `DecompositionArchTest` goes red, check `git status` for a mutated freeze store, restore it from git, and fix the actual violation instead.

- [ ] **Step 3: Full IT reactor**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: all IT modules green. Known flake: `EditIT#createPageAndTestEditPermissions` — re-run in isolation before treating as a regression. Index-dependent Selenide ITs must use startup fixtures, not fresh pages.

- [ ] **Step 4: Commit changelog**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): tier-1 performance fixes"
```

---

## Self-Review Notes

- **Coverage:** audit Tier-1 items → Task 1 (`/sparql` snapshot), Task 8 (dense backend + upsert), Tasks 2–4 (`/api/pages` scan, global lock, ACL body re-reads, cluster map), Task 5 (`/admin/users` N+1), Tasks 6–7 (flexmark waste, frontmatter triple-parse, `no-store`). Explicitly deferred with rationale: `collectLinks` double parse (Task 6 note).
- **Semantics preserved:** `/api/pages` keeps filter-before-pagination (`total` over the viewable set; restricted names/counts still hidden); the batch filter is proven equivalent by test; SSR ETag shares nothing across users that the render cache didn't already share.
- **Discovered along the way, flagged for the maintainer (not in this plan's scope):** the SSR `/wiki/*` path (`SpaRoutingFilter.injectSemantic`) appears to serve page content with **no view-ACL check** — worth a security review pass; the ETag change neither depends on nor worsens this.

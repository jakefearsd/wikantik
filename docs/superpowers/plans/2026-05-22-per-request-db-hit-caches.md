# First-Pass: Eliminate Per-Request DB Connections via Short-TTL Caches

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Remove the per-request database-connection acquisitions that a 1200-VU saturation diagnostic traced to connection-pool exhaustion (400/400 Tomcat threads parked in `borrowObject`), by adding short-TTL caches in front of three hot-path lookups. All three classes live in `wikantik-main`, which already depends on Caffeine.

**Architecture:** Caffeine caches with short `expireAfterWrite` TTLs. Auth-sensitive caches use short TTLs per the operator's "short TTL only" decision (no eviction hooks required, though local mutations evict where trivial). No schema, no new dependency.

**Diagnostic basis:** thread dump showed all workers blocked acquiring DB connections; `ApiKeyService.verify` (2 connections/authed request), the basic-auth user lookup, and `MentionIndex` mention joins were the uncached per-request offenders. Policy grants, groups, and `QueryEntityResolver` are already cached — leave them alone.

---

## Task 1: Cache `ApiKeyService.verify` + async `touchLastUsed`

**File:** `wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java`
**Test:** `wikantik-main/src/test/java/com/wikantik/auth/apikeys/ApiKeyServiceTest.java` (extend if present; else create)

Every authenticated MCP/tools request calls `verify()`, which does a SELECT then a second connection in `touchLastUsed()`. Cache the verification by token hash (short TTL); only touch `last_used_at` on a cache MISS, asynchronously — so cache hits do zero DB work and misses (~once per TTL per key) do one SELECT + one off-thread UPDATE.

- [ ] **Step 1: Write/extend the failing test**

Add to `ApiKeyServiceTest` (use a mocked or H2-backed `DataSource` consistent with the existing test's setup — inspect the file first; if none exists, create one mirroring the project's JDBC test pattern):
```java
    @Test
    void verify_isCachedAcrossCalls() throws Exception {
        // First verify hits the DB; second verify of the same token is served from cache.
        // Arrange a DataSource spy/mock that counts getConnection() calls (or use a
        // GenericObjectPool-free counting wrapper). Insert one active key, verify twice
        // with the same plaintext, assert the SELECT ran once (connection obtained once
        // for the lookup), and both calls return the same present Record.
    }

    @Test
    void verify_unknownTokenCachedAsEmpty() throws Exception {
        // Verifying an unknown token twice queries the DB once (negative cache), both empty.
    }
```
Fill the bodies using the test file's existing DataSource/fixture helpers (do not invent helpers). If the existing test uses an H2 schema, count queries via a wrapping `DataSource` that increments an `AtomicInteger` in `getConnection()`.

- [ ] **Step 2: Run, confirm failure**

`mvn -q -pl wikantik-main test -Dtest=ApiKeyServiceTest` → the new cases fail (no caching yet: DB queried twice).

- [ ] **Step 3: Implement**

Add imports:
```java
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
```
Add fields (after `rng`):
```java
    /** Short TTL: a revoked key keeps working at most this long. Operator chose short-TTL-only. */
    private static final long VERIFY_TTL_SECONDS = 60L;

    private final Cache< String, Optional< Record > > verifyCache = Caffeine.newBuilder()
            .expireAfterWrite( Duration.ofSeconds( VERIFY_TTL_SECONDS ) )
            .maximumSize( 10_000 )
            .recordStats()
            .build();

    /** Off-request-thread last_used_at writer so the metadata UPDATE never blocks a worker. */
    private final ExecutorService touchExecutor = Executors.newSingleThreadExecutor( r -> {
        final Thread t = new Thread( r, "apikey-touch" );
        t.setDaemon( true );
        return t;
    } );
```
Replace the body of `verify(...)` with a cache-loading version (keep the existing `sha256Hex`, `readRow`, `touchLastUsed` helpers). The loader does the SELECT and, only on a present result, schedules an async touch — so it runs once per cache miss, not per request:
```java
    public Optional< Record > verify( final String plaintext ) {
        if ( plaintext == null || plaintext.isEmpty() ) {
            return Optional.empty();
        }
        final String hash = sha256Hex( plaintext );
        return verifyCache.get( hash, h -> {
            final Optional< Record > looked = lookupByHash( h );
            looked.ifPresent( rec -> touchExecutor.submit( () -> touchLastUsed( rec.id() ) ) );
            return looked;
        } );
    }

    /** DB lookup for a token hash; no caching, no touch. */
    private Optional< Record > lookupByHash( final String hash ) {
        final String sql = "SELECT id, key_hash, principal_login, label, scope,"
                + " created_at, created_by, last_used_at, revoked_at, revoked_by"
                + " FROM " + TABLE + " WHERE key_hash = ? AND revoked_at IS NULL";
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, hash );
            try ( ResultSet rs = ps.executeQuery() ) {
                if ( !rs.next() ) {
                    return Optional.empty();
                }
                return Optional.of( readRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "API key verify failed: {}", e.getMessage() );
            return Optional.empty();
        }
    }
```
`touchLastUsed` is unchanged (still opens its own connection, but now only on the rare miss path, off the request thread).

- [ ] **Step 4: Run tests green**

`mvn -q -pl wikantik-main test -Dtest=ApiKeyServiceTest` → all pass.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java \
        wikantik-main/src/test/java/com/wikantik/auth/apikeys/ApiKeyServiceTest.java
git commit -m "perf: cache ApiKeyService.verify (60s TTL), async last_used_at touch

Every authenticated MCP/tools request opened two DB connections (SELECT key +
UPDATE last_used_at). Cache verification by token hash; touch only on a cache
miss, off the request thread. Drops auth from 2 connections/request to ~2 per
key per minute."
```

---

## Task 2: Cache `MentionIndex.findRelatedPages` / `findRelatedPagesBatch`

**File:** `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java`
**Test:** `wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java` (extend; else create)

Per-search mention joins, uncached. Add a Caffeine `pageName → List<RelatedByMention>` cache (5-min TTL). `findRelatedPagesBatch` should serve cached pages from the cache and query only the uncached pages in its single batched connection, then populate the cache.

- [ ] **Step 1: Write the failing test**

Inspect `MentionIndexTest` for its DataSource/fixture pattern, then add (fill bodies with the file's existing helpers):
```java
    @Test
    void findRelatedPages_isCachedPerPage() {
        // Same (pageName, limit) queried twice → DB hit once; identical results.
    }

    @Test
    void findRelatedPagesBatch_servesCachedPagesAndQueriesOnlyMisses() {
        // Pre-warm cache for page A via findRelatedPages(A). Then batch([A, B]):
        // only B is queried from the DB; A comes from cache; both present in the result map.
    }
```

- [ ] **Step 2: Run, confirm failure** — `mvn -q -pl wikantik-main test -Dtest=MentionIndexTest`.

- [ ] **Step 3: Implement**

Add imports (`Cache`, `Caffeine`, `Duration`). Add a field:
```java
    private static final long RELATED_TTL_SECONDS = 300L;
    private final Cache< String, List< RelatedByMention > > relatedCache = Caffeine.newBuilder()
            .expireAfterWrite( Duration.ofSeconds( RELATED_TTL_SECONDS ) )
            .maximumSize( 50_000 )
            .recordStats()
            .build();
```
> Cache key must encode the `limit` too if `limit` varies across callers. Inspect call sites: if `limit` is effectively constant, key by `pageName`; otherwise key by `pageName + ":" + limit`. Choose based on what you find and note it in the commit.

In `findRelatedPages(pageName, limit)`: wrap the existing DB body so it populates/reads `relatedCache` (via `relatedCache.get(key, k -> <existing DB query returning List>)`). Keep the existing query as the loader.

In `findRelatedPagesBatch(pageNames, limit)`: partition `pageNames` into cached (served from `relatedCache.getIfPresent`) and uncached; run the existing batched query for ONLY the uncached set; put each queried page's list into `relatedCache`; merge cached + queried into the returned map. Preserve the existing single-connection batching for the uncached set. Pages with no mentions must still cache an empty list (so they don't re-query every time).

- [ ] **Step 4: Run tests green** — `mvn -q -pl wikantik-main test -Dtest=MentionIndexTest`.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java
git commit -m "perf: cache MentionIndex related-pages lookups (5m TTL)

Per-search mention joins were uncached. Memoise per page; batch queries only
the uncached pages. Empty results are cached to avoid re-querying pages with
no mentions."
```

---

## Task 3: Cache `JDBCUserDatabase.findByLoginName`

**File:** `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java`
**Test:** `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java` (extend; else create)

Cookie-less basic-auth (`/api/*`, admin) re-runs `authMgr.login()` per request, which calls `findByLoginName` → a DB connection per request. Cache the user record by login name (60s TTL); evict on the local mutations so an admin edit reflects immediately. `login()` and bcrypt still run (session semantics + password check unchanged) — only the DB read is cached.

- [ ] **Step 1: Write the failing test**

Inspect the test's DataSource pattern, then add:
```java
    @Test
    void findByLoginName_isCached() {
        // Look up the same login twice → DB queried once; same profile returned.
    }

    @Test
    void saveEvictsCachedLogin() {
        // findByLoginName(x) (caches), then saveProfile(x') with changed data,
        // then findByLoginName(x) returns the updated profile (cache evicted on save).
    }
```

- [ ] **Step 2: Run, confirm failure** — `mvn -q -pl wikantik-main test -Dtest=JDBCUserDatabaseTest`.

- [ ] **Step 3: Implement**

Add imports (`Cache`, `Caffeine`, `Duration`). Add a field:
```java
    private static final long USER_TTL_SECONDS = 60L;
    private final Cache< String, UserProfile > byLoginCache = Caffeine.newBuilder()
            .expireAfterWrite( Duration.ofSeconds( USER_TTL_SECONDS ) )
            .maximumSize( 10_000 )
            .recordStats()
            .build();
```
Change `findByLoginName` to read through the cache. `findByPreparedStatement` throws `NoSuchPrincipalException` for misses; the cache stores only present profiles, so a miss falls through to the DB each time (acceptable — unknown logins are not the hot path). Use a manual get/put rather than caching the exception:
```java
    public UserProfile findByLoginName( final String index ) throws NoSuchPrincipalException {
        final UserProfile cached = byLoginCache.getIfPresent( index );
        if ( cached != null ) {
            return cached;
        }
        final UserProfile profile = findByPreparedStatement( FIND_BY_LOGIN_NAME, index );
        byLoginCache.put( index, profile );
        return profile;
    }
```
Evict in the mutating methods so edits reflect immediately. In `saveProfile`/`save`, `deleteByLoginName`/`delete`, and `rename` (the methods that change a user — inspect their exact names/signatures), add `byLoginCache.invalidate( <affectedLoginName> )` (and both old+new names in rename) at the point the mutation succeeds. If a save can change the login name, invalidate both.

> Inspect the actual mutator method names (the file has delete at ~line 228, save at ~306, rename at ~447). Add the matching `invalidate(...)` calls. Do not cache `findByUid`/`findByEmail`/etc. — only `findByLoginName` is the per-request basic-auth path.

- [ ] **Step 4: Run tests green** — `mvn -q -pl wikantik-main test -Dtest=JDBCUserDatabaseTest`.

- [ ] **Step 5: Commit**
```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
        wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java
git commit -m "perf: cache JDBCUserDatabase.findByLoginName (60s TTL, evict on mutation)

Cookie-less basic-auth re-runs login() per request → a user-table SELECT per
request. Cache the user record by login name; evict on save/delete/rename so
edits reflect immediately. login()/bcrypt still run; only the DB read is cached."
```

---

## Task 4: Verify

- [ ] **Step 1: Full IT reactor** — clear stale pgvector container, then `mvn clean install -Pintegration-tests -fae` → BUILD SUCCESS, 0 failures.
- [ ] **Step 2: Re-run the saturation diagnostic** (controller): redeploy to docker1, drive 1200 VUs, capture thread dumps, confirm worker threads are no longer dominated by `borrowObject` on the auth/user paths (the search-path DB usage remains — that's the PgBouncer story next).

---

## Self-Review

**Spec coverage:** ApiKeyService verify cache + async touch (Task 1); MentionIndex per-page cache with batch-aware miss-only querying (Task 2); JDBCUserDatabase.findByLoginName cache with mutation eviction (Task 3); verification (Task 4). Matches the audit's three uncached per-request offenders. Already-cached paths (policy grants, groups, QueryEntityResolver) untouched.

**Placeholder note:** test bodies reference each file's existing DataSource/fixture helpers (which differ per test and must be read at implementation time) rather than inventing them; all production code is concrete.

**Type consistency:** `Cache<String, Optional<Record>>` / `Cache<String, List<RelatedByMention>>` / `Cache<String, UserProfile>`; Caffeine `expireAfterWrite(Duration)` + `maximumSize` + `recordStats`; `verify`/`findByLoginName`/`findRelatedPages[Batch]` signatures unchanged.

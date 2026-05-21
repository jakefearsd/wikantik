# Eliminate per-search `getPageText` contention — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover throughput at N=650 from 371 RPS → ~500+ RPS by removing the synchronized-method contention on `VersioningFileProvider.getPageText` that surfaced once the per-search DB hits were cached away.

**Architecture:** Three phases, strictly sequential for load-test attribution. Phase 3 first (mmap revert — zero code, isolates baseline). Phase 1 second (`refreshMetadata` cache fix — eliminates bulk of calls). Phase 2 third (`ReentrantReadWriteLock` on the residual miss path). Each phase independently revertable.

**Tech Stack:** Java 21, EhCache (existing `wikantik.pageCache`), `java.util.concurrent.locks.ReentrantReadWriteLock`, JUnit 5, Cargo IT, ArchUnit, k6 (load gating).

**Spec:** `docs/superpowers/specs/2026-05-21-versioning-provider-contention-fix-design.md`

**Predecessor evidence:** the JFR sweep at N=650 with caches + mmap (5,365 monitor-enter events on `VersioningFileProvider.getPageText`, contention path traced 6 frames deep).

---

## File Structure

**New tests:**
- `wikantik-main/src/test/java/com/wikantik/providers/CachingProviderRefreshMetadataTest.java` — unit test: `getPageText` fires once across two `getPageInfo` calls on the same page
- `wikantik-main/src/test/java/com/wikantik/providers/VersioningFileProviderConcurrencyTest.java` — concurrency test: 32 reader threads + 1 writer thread, asserts no torn reads and no deadlock

**Modified:**
- `.env.prod` — Phase 3 flag flip (gitignored; operator change, no commit)
- `wikantik-main/src/main/java/com/wikantik/providers/CachingProvider.java` — Phase 1: set `hasMetadata` flag after parse + audit invalidation
- `wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java` — Phase 2: replace `synchronized` with `ReentrantReadWriteLock`
- `wikantik-main/src/test/java/com/wikantik/providers/CachingProviderTest.java` — Phase 1 unit-test extension (file may already exist; check first)

**Not modified (intentionally out of scope):**
- `Page` interface / `AbstractPage` impl — `setHasMetadata()` already exists per `Page.java:135`
- `AbstractFileProvider` / `FileSystemProvider` — Phase 2 scope is `VersioningFileProvider` only
- `wikantik-rest`, `wikantik-it-tests` — no surface change

---

### Task 1: Phase 3 — flip mmap → nio + measure baseline

**Files:**
- Modify: `.env.prod` (gitignored)

This is an operator change, not a code change. No commit. The goal is to isolate the baseline before the bigger Phase 1 + 2 changes land, so we can attribute throughput delta cleanly.

- [ ] **Step 1: Flip the flag in `.env.prod`**

```bash
sed -i 's/^WIKANTIK_LUCENE_DIRECTORY=mmap/WIKANTIK_LUCENE_DIRECTORY=nio/' .env.prod
grep WIKANTIK_LUCENE_DIRECTORY .env.prod
```

Expected: line reads `WIKANTIK_LUCENE_DIRECTORY=nio`.

- [ ] **Step 2: Redeploy (no rebuild)**

```bash
bin/remote.sh deploy --skip-build
```

Expected: `Deploy healthy: http://docker1:8080/api/health returned 200.` within ~30 s.

- [ ] **Step 3: Verify nio is active**

```bash
ssh docker1 'docker logs repo-wikantik-1 2>&1 | grep "Lucene directory backend"' | head -1
```

Expected: log line `Lucene directory backend: NIOFSDirectory (wikantik.search.lucene.directory.kind=nio)`.

- [ ] **Step 4: Take Phase-3 N=650 baseline**

```bash
# 2 min warmup at low VUs to seed JIT + caches
bin/loadtest.sh smoke --duration 2m --vus 8

# 3 min sustained N=650
bin/loadtest.sh smoke --duration 3m --vus 650
```

Record:
- RPS
- p50, p90, p95, max
- failure rate
- (separately) cache hit rates from `/metrics`
- (separately) host CPU + container CPU split via the jakemon Prom query pattern used earlier in the session

This is the Phase-3 reference point. Throughput recovery beyond this baseline is attributable to Phase 1 + 2.

- [ ] **Step 5: NO commit. Move on to Task 2.**

`.env.prod` is gitignored; the operator change persists on disk and on the docker1 host via the deploy.

---

### Task 2: Verify `Page.setHasMetadata` API + ensure cached Page survives EhCache without losing the flag

**Files:**
- Read-only verification: `wikantik-api/src/main/java/com/wikantik/api/core/Page.java`, `wikantik-main/src/main/java/com/wikantik/pages/AbstractPage.java`, `wikantik-cache/src/main/java/com/wikantik/cache/EhcacheCachingManager.java`, the `ehcache-wikantik.xml` config

This is an investigation task — no code change. The output is a written-up confirmation (committed as part of Task 3's commit message or as a comment in the touched code) that:

1. `Page.setHasMetadata()` exists, takes no args, sets a flag to true
2. `Page.hasMetadata()` returns that flag
3. The EhCache `wikantik.pageCache` stores the `Page` instance by reference, not a defensive copy or serialized form
4. Cache invalidation on page save (via `putPageText`) currently drops the cached `Page` so the next `getPageInfo` re-parses

- [ ] **Step 1: Read the four files above**

Confirm the four facts. Note any surprise in a scratch file (`/tmp/page-cache-audit-2026-05-21.md`) — discard after Task 3 lands.

- [ ] **Step 2: Run an exploratory unit test (do NOT commit) to verify cache identity**

Write a quick test that constructs a Page, puts it in the EhCache via `CachingManager.get(..., supplier)`, mutates a field, retrieves it again, and asserts the mutation is visible (proving by-reference storage). Run, then delete.

If the EhCache returns a copy: STOP and report — Phase 1 needs a different approach (e.g., a parallel Caffeine cache keyed by page name → parsed metadata).

If the EhCache returns by reference: proceed to Task 3.

- [ ] **Step 3: NO commit. Move on to Task 3.**

---

### Task 3: Phase 1 — set `hasMetadata` after parse in `CachingProvider.refreshMetadata`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/CachingProvider.java`
- Create or modify: `wikantik-main/src/test/java/com/wikantik/providers/CachingProviderRefreshMetadataTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void refreshMetadata_callsGetPageText_onceAcrossRepeatedGetPageInfo() throws Exception {
    final PageProvider underlying = mock(PageProvider.class);
    final Page page = new com.wikantik.pages.AbstractPage("Main");
    page.setVersion(1);
    when(underlying.getPageInfo("Main", PageProvider.LATEST_VERSION)).thenReturn(page);
    when(underlying.getPageText("Main", 1)).thenReturn("body text");

    final CachingProvider cp = newCachingProviderUnderTest(underlying);

    cp.getPageInfo("Main", PageProvider.LATEST_VERSION);  // first call — parses + sets hasMetadata
    cp.getPageInfo("Main", PageProvider.LATEST_VERSION);  // second call — must NOT re-parse

    verify(underlying, times(1)).getPageText("Main", 1);
}
```

The `newCachingProviderUnderTest` helper:
- Constructs a `CachingProvider` with the mock underlying
- Wires a `CachingManager` (the real `EhcacheCachingManager` or a simple test double — match what existing `CachingProviderTest` does)
- Wires an `Engine` (a Mockito mock returning a real `RenderingManager` whose `getParser` returns a no-op MarkupParser is fine — the parser doesn't need to actually do anything, just not throw)

If `CachingProviderTest` already exists, extend it with the new test method instead of creating a new file.

- [ ] **Step 2: Run the test, confirm it fails**

```bash
mvn -pl wikantik-main test -Dtest=CachingProviderRefreshMetadataTest -q
```

Expected: FAIL — the test asserts `times(1)` but actually `times(2)` happens.

- [ ] **Step 3: Implement the fix**

In `CachingProvider.refreshMetadata`:

```java
private void refreshMetadata( final Page page ) {
    if( page != null && !page.hasMetadata() ) {
        final RenderingManager mgr = RenderingSubsystemBridge.fromLegacyEngine( engine ).renderingManager();
        try {
            final String data = provider.getPageText( page.getName(), page.getVersion() );
            final Context ctx = Wiki.context().create( engine, page );
            final MarkupParser parser = mgr.getParser( ctx, data );
            parser.parse();
            page.setHasMetadata();   // ← NEW: mark the Page so the next getPageInfo short-circuits
        } catch( final Exception ex ) {
            LOG.debug( "Failed to retrieve variables for wikipage {}", page );
        }
    }
}
```

One line added. The guard already exists; we just weren't setting the flag.

- [ ] **Step 4: Run the test, confirm it passes**

```bash
mvn -pl wikantik-main test -Dtest=CachingProviderRefreshMetadataTest -q
```

Expected: PASS.

- [ ] **Step 5: Verify nothing else breaks in the module**

```bash
mvn -pl wikantik-main test -q
```

Expected: all green (3928+ tests). If any test depends on `refreshMetadata` re-running, audit — likely a test that mocks page caching incorrectly; fix the test to match real behaviour.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/providers/CachingProvider.java \
        wikantik-main/src/test/java/com/wikantik/providers/CachingProviderRefreshMetadataTest.java
git commit -m "providers: set Page.hasMetadata() after CachingProvider parse

The refreshMetadata kludge (its own source comment, verbatim) reads the
full page text via the underlying provider just to populate Flexmark
variables on the Page. The hasMetadata() guard at the top exists to
prevent re-running on already-parsed pages — but we never set the flag,
so the guard never triggers and refreshMetadata fires on every
getPageInfo, every getPage, every search-result hydration.

JFR at N=650 traced 5,365 jdk.JavaMonitorEnter events on
VersioningFileProvider.getPageText (its only entry point under that
load is via refreshMetadata at line 446 in CachingProvider). The
synchronized-method contention serializes 650 threads through one
mutex. Setting the flag after a successful parse breaks the cycle —
the cached Page advertises hasMetadata()=true and refreshMetadata
short-circuits on the next call.

One-line fix; the rest of the kludge stays (deferred replacement).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Audit + verify cache invalidation on page save

**Files:**
- Read-only: `wikantik-main/src/main/java/com/wikantik/providers/CachingProvider.java` (the `putPageText` override + listeners)
- Create: `wikantik-main/src/test/java/com/wikantik/providers/CachingProviderInvalidationTest.java`

- [ ] **Step 1: Trace the invalidation path**

In `CachingProvider.java`, find the `putPageText` method. Confirm it invalidates the page cache entry for the saved page (or that a listener does — the codebase uses `WikiEventListener` for page-change events; the cache may listen for those).

Expected (one of):
- A direct `cachingManager.remove(CACHE_PAGES, pageName)` call inside `putPageText`
- A listener registration that does the same in response to `PageEvent.PAGE_SAVED`

If neither is present, add the explicit invalidation in `putPageText`.

- [ ] **Step 2: Write the invalidation test**

```java
@Test
void putPageText_evictsCachedPage_soNextGetPageInfoReParses() throws Exception {
    final PageProvider underlying = mock(PageProvider.class);
    final Page page = new com.wikantik.pages.AbstractPage("Main");
    page.setVersion(1);
    when(underlying.getPageInfo("Main", PageProvider.LATEST_VERSION)).thenReturn(page);
    when(underlying.getPageText("Main", 1)).thenReturn("v1 body").thenReturn("v2 body");

    final CachingProvider cp = newCachingProviderUnderTest(underlying);

    cp.getPageInfo("Main", PageProvider.LATEST_VERSION);     // parses "v1 body"
    cp.putPageText(page, "v2 body");                          // invalidation must drop the cached Page
    cp.getPageInfo("Main", PageProvider.LATEST_VERSION);     // must re-parse with "v2 body"

    verify(underlying, times(2)).getPageText("Main", 1);
}
```

- [ ] **Step 3: Confirm test outcome**

If the existing code already invalidates correctly, the test passes immediately. Document that fact in the commit message.

If the test fails, add the missing invalidation in `CachingProvider.putPageText`:

```java
@Override
public void putPageText( final Page page, final String text ) throws ProviderException {
    provider.putPageText( page, text );
    cachingManager.remove( CachingManager.CACHE_PAGES, page.getName() );
    cachingManager.remove( CachingManager.CACHE_PAGES_TEXT, page.getName() );
    cachingManager.remove( CachingManager.CACHE_PAGES_HISTORY, page.getName() );
}
```

Match the existing method's exact contract (look at the file before writing).

- [ ] **Step 4: Run test + full module**

```bash
mvn -pl wikantik-main test -Dtest=CachingProviderInvalidationTest -q
mvn -pl wikantik-main test -q
```

Both expected PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/providers/CachingProvider.java \
        wikantik-main/src/test/java/com/wikantik/providers/CachingProviderInvalidationTest.java
git commit -m "providers: harden CachingProvider page-save invalidation [+ regression test]

Pairs with the prior commit that makes refreshMetadata short-circuit on
hasMetadata(). Cache stickiness is now a correctness contract: if a
page is edited but the cached Page+metadata snapshot stays, readers
see stale text. The invalidation path is verified by an explicit test
that writes a page twice and asserts re-parse on the second read.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

Adjust the commit message based on what Step 3 actually found (whether invalidation was already there, or whether you added it).

---

### Task 5: Deploy Phase 1 + take measurement

**No files.** Operator + verification work.

- [ ] **Step 1: Full IT reactor before push**

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: BUILD SUCCESS. If anything fails, fix it before proceeding (per `feedback_full_it_after_targeted_fix`).

- [ ] **Step 2: Push to main**

```bash
git push origin main
```

- [ ] **Step 3: Deploy to docker1**

```bash
bin/remote.sh deploy
```

Expected: healthy.

- [ ] **Step 4: Warmup + N=650 heat**

```bash
bin/loadtest.sh smoke --duration 2m --vus 8
bin/loadtest.sh smoke --duration 3m --vus 650
```

- [ ] **Step 5: Compare**

Record the same metrics shape as Task 1 Step 4 baseline. Specifically:
- RPS now vs Phase-3 baseline
- `wikantik_cache_*` hit rates (chunk_text + page_mentions + the new ones)
- Host + container CPU split

Expected: significant RPS recovery (toward 500+) because `getPageText` calls drop dramatically. If RPS stays flat or regresses, **STOP** — the `hasMetadata` flag isn't persisting through the EhCache, and Task 2's audit was wrong. Investigate before proceeding to Task 6.

---

### Task 6: Phase 2 — replace `synchronized` on `VersioningFileProvider` with `ReentrantReadWriteLock` (read paths)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java`

- [ ] **Step 1: Add the lock field**

At the top of the class (near other field declarations around line 100):

```java
/**
 * Read-write lock guarding the page-text / page-metadata read & write paths.
 * Replaces the previous method-level {@code synchronized} so concurrent reads
 * of different pages (or the same page) proceed without serialising through
 * one mutex. Writers ({@link #putPageText}, {@link #deletePage},
 * {@link #deleteVersion}, {@link #movePage}) acquire the exclusive write lock;
 * readers acquire the shared read lock. Non-fair — wiki workloads are
 * dominated by reads, and non-fair gives better throughput under that mix.
 */
private final java.util.concurrent.locks.ReentrantReadWriteLock rwLock =
    new java.util.concurrent.locks.ReentrantReadWriteLock( /*fair*/ false );
```

- [ ] **Step 2: Replace `synchronized` on `getPageText` with explicit read-lock**

```java
@Override
public String getPageText( final String page, int version ) throws ProviderException {
    final java.util.concurrent.locks.Lock readLock = rwLock.readLock();
    readLock.lock();
    try {
        final File dir = findOldPageDir( page );

        version = realVersion( page, version );
        if( version == -1 ) {
            return super.getPageText( page, PageProvider.LATEST_VERSION );
        }

        final File pageFile = new File( dir, ""+version+FILE_EXT );
        if( !pageFile.exists() ) {
            throw new NoSuchVersionException("Version "+version+"does not exist.");
        }

        return readFile( pageFile );
    } finally {
        readLock.unlock();
    }
}
```

Notes:
- The `synchronized` keyword is GONE from the signature.
- The body is otherwise unchanged.
- `try-finally` is mandatory — never let an exception leak with the lock held.

- [ ] **Step 3: Wrap `getVersionHistory` with read-lock**

`getVersionHistory` walks the OLD/ directory + reads `getPageProperties`. Under the old `synchronized`, this method WAS NOT protected (it's not declared `synchronized`). Adding the read-lock makes its view consistent with concurrent writers without serialising read traffic:

```java
@Override
public List< Page > getVersionHistory( final String page ) throws ProviderException {
    final java.util.concurrent.locks.Lock readLock = rwLock.readLock();
    readLock.lock();
    try {
        // ... existing body unchanged ...
    } finally {
        readLock.unlock();
    }
}
```

- [ ] **Step 4: Wrap `getAllPages` with read-lock**

Same pattern. It calls `getPageInfo` per page in a loop — wrapping the whole loop with one acquire is more efficient than re-locking per iteration.

- [ ] **Step 5: Compile-check**

```bash
mvn -pl wikantik-main compile -q
```

Expected: clean. No new dependencies.

- [ ] **Step 6: Run existing provider tests**

```bash
mvn -pl wikantik-main test -Dtest='VersioningFileProvider*' -q
```

Expected: all green. The behavioural contract is unchanged from a single-threaded view; existing tests don't exercise concurrency.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java
git commit -m "providers: VersioningFileProvider read paths use shared read-lock

Replaces the public synchronized on getPageText with explicit
ReentrantReadWriteLock.readLock() acquisition (non-fair, read-dominated
workload). getVersionHistory and getAllPages also take the read lock
to defend against concurrent writers — they were previously unprotected
(pre-existing race, low-impact under wiki write rates).

Writes still go via the old synchronized on putPageText for now — the
next commit replaces those with the exclusive write lock and adds
locks to the three currently-unsynchronised mutators (deletePage,
deleteVersion, movePage).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Phase 2 — replace `synchronized` on `putPageText` and lock the three previously-unprotected mutators

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java`

- [ ] **Step 1: Replace `synchronized` on `putPageText` with write-lock**

```java
@Override
public void putPageText( final Page page, final String text ) throws ProviderException {
    final java.util.concurrent.locks.Lock writeLock = rwLock.writeLock();
    writeLock.lock();
    try {
        // ... existing body unchanged ...
    } finally {
        writeLock.unlock();
    }
}
```

- [ ] **Step 2: Wrap `deletePage` with write-lock**

Currently has no synchronization. Pre-existing race with concurrent readers. The write-lock fixes both.

```java
@Override
public void deletePage( final String page ) throws ProviderException {
    final java.util.concurrent.locks.Lock writeLock = rwLock.writeLock();
    writeLock.lock();
    try {
        super.deletePage( page );
        // ... existing body ...
    } finally {
        writeLock.unlock();
    }
}
```

- [ ] **Step 3: Wrap `deleteVersion` with write-lock**

Same pattern.

- [ ] **Step 4: Wrap `movePage` with write-lock**

Same pattern.

- [ ] **Step 5: Verify no other mutator is missed**

```bash
grep -nE "public (synchronized )?(void|boolean|String|int|List)" \
    wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java
```

Walk the list. Any method that **writes** to the page directory or properties — wrap with write-lock. Any method that **reads** — wrap with read-lock. `initialize(...)` is one-shot setup and doesn't need a lock.

- [ ] **Step 6: Compile + targeted test**

```bash
mvn -pl wikantik-main test -Dtest='VersioningFileProvider*' -q
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java
git commit -m "providers: VersioningFileProvider write paths use exclusive write-lock

Completes the RWLock conversion. putPageText, deletePage, deleteVersion,
movePage now acquire rwLock.writeLock() — the latter three were
previously un-synchronised (pre-existing race against concurrent
readers, surfaces only under high write concurrency which is rare).
The 'synchronized' keyword is now absent from the entire class.

Read locks (Task 6) + write locks (this commit) compose: N concurrent
readers OR one exclusive writer, never a reader during a writer's
mutation.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Add a concurrency unit test for the RWLock-protected provider

**Files:**
- Create: `wikantik-main/src/test/java/com/wikantik/providers/VersioningFileProviderConcurrencyTest.java`

- [ ] **Step 1: Write the concurrency test**

```java
@Test
void thirtyTwoConcurrentReaders_seeConsistentText() throws Exception {
    final VersioningFileProvider provider = newProviderWithSeedPage(
        "TestPage", "expected body content" );

    final int threads = 32;
    final int iterationsPerThread = 200;
    final java.util.concurrent.ExecutorService exec =
        java.util.concurrent.Executors.newFixedThreadPool( threads );
    final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch( 1 );
    final java.util.List< java.util.concurrent.Future< Boolean > > futures = new java.util.ArrayList<>();

    for( int t = 0; t < threads; t++ ) {
        futures.add( exec.submit( () -> {
            start.await();
            for( int i = 0; i < iterationsPerThread; i++ ) {
                final String text = provider.getPageText( "TestPage", PageProvider.LATEST_VERSION );
                if( !"expected body content".equals( text ) ) return false;
            }
            return true;
        } ) );
    }
    start.countDown();
    for( final java.util.concurrent.Future< Boolean > f : futures ) {
        assertTrue( f.get( 30, java.util.concurrent.TimeUnit.SECONDS ),
            "Reader saw inconsistent content" );
    }
    exec.shutdown();
}

@Test
void readDuringWrite_neverSeesTornContent() throws Exception {
    final VersioningFileProvider provider = newProviderWithSeedPage(
        "TestPage", "v1" );

    final int readers = 16;
    final int writes  = 50;
    final java.util.Set< String > validValues = java.util.concurrent.ConcurrentHashMap.newKeySet();
    validValues.add( "v1" );

    final java.util.concurrent.ExecutorService exec =
        java.util.concurrent.Executors.newFixedThreadPool( readers + 1 );
    final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch( 1 );
    final java.util.concurrent.atomic.AtomicReference< String > sawInvalid = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean writerDone = new java.util.concurrent.atomic.AtomicBoolean( false );

    // Writer
    exec.submit( () -> {
        start.await();
        for( int w = 0; w < writes; w++ ) {
            final String value = "v" + ( w + 2 );
            validValues.add( value );
            final Page p = new com.wikantik.pages.AbstractPage( "TestPage" );
            p.setVersion( w + 2 );
            provider.putPageText( p, value );
            Thread.sleep( 5 );
        }
        writerDone.set( true );
        return null;
    } );

    // Readers
    for( int r = 0; r < readers; r++ ) {
        exec.submit( () -> {
            start.await();
            while( !writerDone.get() ) {
                final String got = provider.getPageText( "TestPage", PageProvider.LATEST_VERSION );
                if( !validValues.contains( got ) ) {
                    sawInvalid.set( got );
                    return null;
                }
            }
            return null;
        } );
    }
    start.countDown();
    exec.shutdown();
    exec.awaitTermination( 60, java.util.concurrent.TimeUnit.SECONDS );

    final String invalid = sawInvalid.get();
    assertNull( invalid, "Reader saw a value never written: " + invalid );
}
```

The `newProviderWithSeedPage` helper constructs a `VersioningFileProvider` against a `@TempDir` page directory and writes one initial version. Look at the existing `VersioningFileProvider` tests (if any) for the construction pattern; otherwise initialise a fresh provider directly.

- [ ] **Step 2: Run the tests**

```bash
mvn -pl wikantik-main test -Dtest=VersioningFileProviderConcurrencyTest -q
```

Expected: both PASS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/providers/VersioningFileProviderConcurrencyTest.java
git commit -m "providers: concurrency test for the RWLock-protected VersioningFileProvider

32 concurrent readers + writer asserts no torn reads and no deadlock.
Pairs with the prior two commits that converted the public synchronized
to RWLock. The read-during-write test specifically guards against the
torn-state risk acknowledged in the spec: putPageText writes in place
(not via temp+rename), so a concurrent reader would have a brief window
to see partial content without the lock.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: Full IT reactor before push

**No files.** Verification only.

- [ ] **Step 1: Run the full integration test reactor**

Per CLAUDE.md: NEVER use `-T` parallelism on integration tests (port conflicts). Always `-fae`.

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: BUILD SUCCESS across all five IT modules.

- [ ] **Step 2: Address any failures**

If the IT reactor fails, investigate per the memory `feedback_full_it_after_targeted_fix`. Common suspects after this kind of change:

- A Cargo-launched test that relies on a specific timing because of the old `synchronized` behaviour. Unlikely but possible.
- A test that mocks `CachingProvider.refreshMetadata` and expected the second call (now optimised away).
- The flaky provider parallel tests per memory `reference_provider_test_flakes` — pass in isolation, don't chase.

Fix categorically: real breakage → fix; known flake → re-run in isolation; new flake → file an issue, don't block.

- [ ] **Step 3: NO commit if the reactor was already green.**

If you had to fix anything, commit each fix individually with a message naming the failure mode (e.g. `it-tests: ...`).

---

### Task 10: Deploy Phase 1 + 2 + measure

**No files.** Operator + verification.

- [ ] **Step 1: Push**

```bash
git push origin main
```

- [ ] **Step 2: Deploy**

```bash
bin/remote.sh deploy
```

Expected: healthy.

- [ ] **Step 3: Confirm RWLock is live (sanity check)**

```bash
ssh docker1 'docker exec repo-wikantik-1 javap /usr/local/tomcat/webapps/ROOT/WEB-INF/lib/wikantik-main-*.jar com.wikantik.providers.VersioningFileProvider | grep getPageText'
```

Expected: NO `synchronized` modifier on the method signature.

- [ ] **Step 4: Warmup + N=650 heat**

```bash
bin/loadtest.sh smoke --duration 2m --vus 8
bin/loadtest.sh smoke --duration 3m --vus 650
```

- [ ] **Step 5: Pull a fresh JFR + analyse**

Same JFR protocol as the predecessor sweep — 220 s recording started just before the N=650 run; pull via `docker cp` + `scp`; analyse for `JavaMonitorEnter` events on `VersioningFileProvider.getPageText` (should be near-zero now).

- [ ] **Step 6: Compare to baselines**

| | Pre-Phase-3 (was) | Post-Phase-3 (Task 1) | Post-Phase-1+2 (Task 10) |
|---|---|---|---|
| RPS | 371 | (from Task 1) | (from Task 10) |
| p50 | 76 ms | … | … |
| p95 | 3.55 s | … | … |
| `VersioningFileProvider.getPageText` monitor-enters | 5,365 | … | should be ~0 |

If RPS recovered to ≥500: **success**, move on to whatever's next. If it's flat: the contention is elsewhere; capture another JFR and start the next investigation.

---

## Self-Review

**Spec coverage:**
- ✅ Phase 3 mmap revert → Task 1
- ✅ Phase 1 refreshMetadata → Tasks 2, 3, 4
- ✅ Phase 1 deploy + measure → Task 5
- ✅ Phase 2 read-lock → Task 6
- ✅ Phase 2 write-lock + previously-unprotected mutators → Task 7
- ✅ Phase 2 concurrency test → Task 8
- ✅ Full IT reactor gate → Task 9
- ✅ Phase 2 deploy + measure → Task 10

**Placeholder scan:** Every step has concrete code, a concrete command, and an expected output. No TBDs.

**Type consistency:** `ReentrantReadWriteLock` referenced consistently across Tasks 6, 7, 8. The `setHasMetadata()` call referenced in Task 3 matches the API confirmed in Task 2's audit.

**Ambiguity check:** Task 2's audit has a deterministic path: if EhCache copies, STOP and report. If not, proceed. No "use judgement" branches that could go either way.

**Risk reminder:** If Task 5 shows no throughput recovery after Phase 1, STOP — the `hasMetadata` flag isn't persisting and Task 2's audit was wrong. Do not proceed to Phase 2 until that's understood.

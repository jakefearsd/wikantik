# Eliminate per-search `getPageText` contention — design

**Date:** 2026-05-21
**Status:** Design (no implementation started)
**Author:** Jake Fear
**Predecessor:** the JFR sweep captured by `wikantik-2026-05-21T06-54-55…n650-with-caches-mmap.jfr` (analysed in the same session). See "Evidence" below.

## Goal

Eliminate the synchronized-method contention that pins steady-state throughput at ~370 RPS / N=650 on docker1 despite ~1.5 cores of host headroom. The bottleneck is `VersioningFileProvider.getPageText` (a `public synchronized` method) being hit on every page metadata refresh, which fires on every page hydration the search path triggers.

## Evidence

JFR capture at N=650 (caches landed, mmap on, in-memory dense, DBCP at 90):

| Signal | Count | Implication |
|---|---|---|
| `jdk.JavaMonitorEnter` events with `getPageText` at frame[0] | **5,365** | Threads queueing on the `VersioningFileProvider` instance monitor |
| `jdk.ThreadPark` events on AQS `acquire` | 1,585 | Lock waits beyond the monitor (RWLock, DBCP borrow) |
| `MemorySession.checkValidStateRaw` CPU samples | 1,831 | mmap per-read overhead (#2 hotspot — secondary, not the gate) |
| Host CPU | 89% | ~1.5 of 16 cores idle |
| Throughput at N=650 | 371 RPS | Was 498 RPS at the same VU count yesterday with smaller DBCP — confirming the contention is now the gate |
| Disk reads | 0 MB/s | Page cache satisfies everything; no I/O pressure |

The contention call chain, exact from the JFR depth-6 trace:

```
com.wikantik.providers.VersioningFileProvider.getPageText:343          ← synchronized
com.wikantik.providers.CachingProvider.refreshMetadata:446              ← // FIXME: Kludge (verbatim from source)
com.wikantik.providers.CachingProvider.getPageInfo:471
com.wikantik.page.subsystem.lifecycle.DefaultPageRepository.getPageInfo:205
com.wikantik.page.subsystem.lifecycle.DefaultPageRepository.getPage:184
com.wikantik.page.subsystem.lifecycle.DefaultPageRepository.getPage:178
```

The CachingProvider's page-info cache IS working — but every cache HIT still triggers `refreshMetadata`, which reads the full page text just to populate Flexmark variables. The cache is structurally insufficient at this layer.

## Problem statement

Three accidental couplings stack on top of each other:

1. **`CachingProvider.refreshMetadata` calls `getPageText` to populate `Page` metadata.** The kludge predates the current cache structure; the comment in the source explicitly flags it. Its purpose is to parse wiki variables (Flexmark `[{$varname}]`-style refs) and attach them to the `Page` object. The work is real, but it's being redone on every `getPageInfo` call where `page.hasMetadata()` is false.
2. **`VersioningFileProvider.getPageText` is `synchronized` on the instance.** The whole method holds the instance monitor, including the file-read step. With 650 concurrent threads doing page hydration via `getPage()`, requests serialize through one mutex.
3. **mmap's `MemorySessionImpl.checkValidStateRaw` runs on every Lucene index read.** Secondary cost, surfaces only because the primary gate above keeps so much else fast.

The fix isn't a single change — it's three orthogonal interventions in the right order.

## Architecture diagram

```
                Today
                ─────
   getPage(name)
     → CachingProvider.getPageInfo            ← cache hit on Page object
         → refreshMetadata(page)              ← FIRES on every hit when !hasMetadata
             → VersioningFileProvider.getPageText  ← synchronized! all threads serialize
                 → realVersion + findOldPageDir + File.read
             → MarkupParser.parse              ← also expensive

                Target
                ──────
   getPage(name)
     → CachingProvider.getPageInfo
         → metadata snapshot cached alongside Page    ← Phase 1: no refresh on hit
   --- residual miss path (rare) ---
     → VersioningFileProvider.getPageText  (RWLock shared)   ← Phase 2: concurrent reads
         → File.read

   Lucene reads → NIOFSDirectory (not MMapDirectory)         ← Phase 3: flag flip
```

## Phase 1 — Cache parsed metadata alongside Page in CachingProvider

**Highest impact, lowest risk.** Eliminates the bulk of `getPageText` calls at the source.

### Approach

Persist the result of `refreshMetadata`'s parse into the `Page` object stored in the page-info cache. The next `getPageInfo` call retrieves the Page from cache with `hasMetadata() == true`, so `refreshMetadata` short-circuits at line 443's guard:

```java
if (page != null && !page.hasMetadata()) { ... }   // already in place
```

The change is structural — make sure the parse result IS preserved in the cached Page:

1. **`Page.hasMetadata()` returns false until `setHasMetadata()` is called.** After `MarkupParser.parse()` succeeds, call `page.setHasMetadata()` so the cached Page advertises its parsed state.
2. **Verify the EhCache `wikantik.pageCache` stores the same Page instance** (not a defensive copy). If it copies, the metadata flag is lost — switch to storing the post-parse instance.
3. **Invalidate on page edit.** Existing `CachingManager` invalidates `wikantik.pageCache` on save (via the page change listeners). Confirm by tracing `putPageText` → cache invalidation chain. Add explicit invalidation if missing.

### Cache shape

Already a `wikantik.pageCache` EhCache exists; key = pageName, value = Page. The change is to make sure the cached Page carries its parsed metadata. **No new cache instance needed.**

### Safety

- **Stale metadata after edit:** mitigated by the existing cache invalidation on save. Audit and confirm.
- **Metadata size:** Page already carries `Map<String, Object>` attributes; metadata adds at most a few KB per page. With ~12K pages and a cache cap of 5,000, headroom is fine.
- **Variable references that depend on runtime state:** Flexmark variables resolve at parse time. If any variable depends on per-request state (current user, etc.), caching at this layer would freeze that. **Verification needed** — read what `MarkupParser.parse()` populates and confirm it's safe to cache.

### Expected impact

Bulk of contention disappears: `getPageText` from `refreshMetadata` stops firing on cache hits. Only true cache misses (cold start, first-N pages, recently-edited pages) reach the synchronized method.

## Phase 2 — De-synchronize `VersioningFileProvider` with `ReentrantReadWriteLock`

**Medium impact, low risk.** Makes the residual miss path concurrent.

### Approach

Replace the method-level `synchronized` with explicit lock acquisition via a `ReentrantReadWriteLock` field:

```java
private final java.util.concurrent.locks.ReentrantReadWriteLock rwLock =
    new java.util.concurrent.locks.ReentrantReadWriteLock(/*fair*/ false);
```

**Reader paths (acquire shared `readLock`):**

| Method | Currently |
|---|---|
| `getPageText(page, version)` | `synchronized` (the gate) |
| `getPageInfo` (inherited or via super) | not synchronized today, but consumers expect a stable view |
| `pageExists(pageName, version)` | not synchronized — keep that way, no shared state |
| `getVersionHistory(page)` | not synchronized — but reads `getPageProperties` + file listings; should be read-lock'd for consistency with concurrent writers |
| `getAllPages` | calls `getPageInfo` per page in a loop — wrapping with one read lock instead of N is correct |

**Writer paths (acquire exclusive `writeLock`):**

| Method | Currently |
|---|---|
| `putPageText(page, text)` | `synchronized` |
| `deletePage(page)` | NOT synchronized today (pre-existing race) |
| `deleteVersion(page, version)` | NOT synchronized today (pre-existing race) |
| `movePage(from, to)` | NOT synchronized today (pre-existing race) |

Phase 2 fixes the three pre-existing races as a side effect.

### Fairness

Default to **non-fair** (`new ReentrantReadWriteLock(false)`). Reads dominate by 1000:1 in a wiki workload; non-fair gives better throughput. If we observe writer starvation in load tests, switch to fair.

### Lock granularity decision

**Instance-level lock**, not per-page. Per-page striped locks (`Guava.Striped`) would allow concurrent reads of different pages, but:
- Read-of-the-same-page is already fine under a shared lock — concurrent reads are the point
- Striped adds complexity for a marginal win at this corpus size
- We can revisit if Phase 2 still shows contention at higher load

### Safety review (per the JFR + source audit)

Read path uses:
- `findOldPageDir(page)` — pure path math, no shared state. **No lock needed.**
- `realVersion(page, v)` → `findLatestVersion(page)` → `getPageProperties(page)` — goes through `propertyCache` (LruPropertyCache, already thread-safe with its own synchronization). **No additional lock needed; the read lock prevents only writer interference.**
- `Files.newInputStream(pageFile.toPath())` — OS-level file read. **Safe under POSIX-style concurrent reads.**

Write path uses:
- File creation (atomic create or open-for-write)
- `Files.newOutputStream` — replaces file contents
- Directory mutations (`mkdirs`, `rename`, `delete`)
- `propertyCache.put` after `putPageProperties` — thread-safe inside the cache

The only correctness gap is **reader observing a writer's in-progress state**: `putPageText` writes the .txt in place (not via temp+rename). A concurrent reader during a multi-write step could see torn content. The RWLock closes this gap explicitly.

### Expected impact

Cache misses now run concurrently. Combined with Phase 1's hit-rate boost, the synchronized-method contention goes from the gate to a non-event.

## Phase 3 — Revert `WIKANTIK_LUCENE_DIRECTORY` to `nio`

**Lowest impact, zero risk.** Drop the secondary CPU cost.

### Approach

```bash
sed -i 's/^WIKANTIK_LUCENE_DIRECTORY=mmap/WIKANTIK_LUCENE_DIRECTORY=nio/' .env.prod
bin/remote.sh deploy --skip-build
```

Container restarts in ~30 s. No code change. The flag is already in place from the earlier work.

### Rationale

JFR showed `MemorySessionImpl.checkValidStateRaw` at 1,831 samples — Java 21's per-access validity check on mmap MemorySegment. nio doesn't pay this cost. We adopted mmap on the theory it would reduce per-read syscalls, but our workload doesn't have I/O pressure (page cache satisfies everything, disk reads = 0 MB/s). mmap's strength is irrelevant here; its overhead is real.

### Expected impact

~1,800 JFR samples (out of ~10K) shift from mmap-check to plain JIT'd Lucene reads. Modest throughput recovery; cleaner CPU profile.

## Implementation order

Phases are **strictly sequential** for the test program:

1. Phase 3 (mmap revert) FIRST — zero code change, isolates baseline before Phase 1's bigger change
2. Take an N=650 baseline post-Phase-3
3. Phase 1 (refreshMetadata caching)
4. Take an N=650 baseline post-Phase-1
5. Phase 2 (RWLock)
6. Take an N=650 baseline post-Phase-2

This ordering lets us attribute throughput gains to each phase cleanly. If Phase 1 alone recovers throughput, Phase 2 becomes optional.

## Testing strategy

### Phase 1

- **Unit:** new test in `CachingProviderTest` that loads a page twice via `getPageInfo`. Mock the underlying provider; assert `getPageText` fires exactly once.
- **IT:** existing PG-backed test that hits `/api/search`; verify the search response shape is unchanged (Flexmark variables resolve correctly).
- **Page-save invalidation IT:** edit a page, assert subsequent `getPageInfo` parses fresh metadata.

### Phase 2

- **Concurrency unit:** spin 32 threads each calling `getPageText` on a seeded provider; verify no exceptions, no data corruption, no deadlock.
- **Read-during-write IT:** one thread spamming `putPageText`, 16 threads spamming `getPageText`. Assert no torn reads (response equals one of the written values, never partial).
- **ArchUnit:** ensure the `synchronized` keyword is gone from `getPageText` (a one-shot check, freezable).

### Phase 3

- **Smoke probe** post-flag-flip: `/api/search?q=cloud` returns expected results. Already covered by the existing harness.

### Load-test gating

Full IT reactor (`mvn clean install -Pintegration-tests -fae`) before each push. N=650 load test against docker1 after each deploy to attribute the throughput delta.

## Rollback

| Phase | Rollback |
|---|---|
| 1 | Revert the commit; Page cache returns to its prior shape. No data migration. |
| 2 | Revert the commit; `synchronized` returns. **However**, the three pre-existing races on `deletePage`/`deleteVersion`/`movePage` are now visible — keep them un-synchronized as before, or land a focused follow-up that just synchronizes those without RWLock'ing the read path. |
| 3 | Flip `WIKANTIK_LUCENE_DIRECTORY=mmap` + `bin/remote.sh deploy --skip-build`. ~30 s. |

All three phases are independently revertable. Phase 1 doesn't require Phase 2 to be in place; Phase 3 is orthogonal.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Phase 1: variables that depend on per-request state get frozen by the cache | Low — wiki variables resolve at parse time, not request time | High if it triggers | Audit `MarkupParser.parse()` to confirm no `Wiki.context()`-derived state ends up cached. Pre-emptive: cache only the variables that have no request-dependence. |
| Phase 1: stale metadata after an edit | Low — existing cache invalidation on save | Medium | Audit `putPageText` → cache invalidation chain; add explicit invalidation if missing |
| Phase 2: missed-write path that bypasses the RWLock | Low | High | Audit every `synchronized` method on `VersioningFileProvider`, `FileSystemProvider`, `AbstractFileProvider`. Classify as reader or writer. Lock accordingly. |
| Phase 2: writer starvation under read-heavy load | Low for wiki workload (writes are rare) | Medium | Default to non-fair RWLock; switch to fair if observed |
| Phase 2: deadlock if a writer-holding thread re-enters as reader | Medium without care | High | `ReentrantReadWriteLock` supports a write→read downgrade pattern but NOT read→write upgrade. Audit any code paths that take both. |
| Phase 3: nio is slower than mmap for our actual workload | Low — JFR shows mmap is a net loss in this config | Low | Flag flip back, ~30 s |
| ArchUnit baseline drift | Medium | Low — frozen baseline rejects new violations, not the existing pattern | Refresh the freeze if needed (per CLAUDE.md, with intent documented) |

## Out of scope

- **Page-text cache layer.** Tempting to add a Caffeine LRU around `getPageText` directly, but Phase 1 already removes the bulk of calls — adding a redundant cache is YAGNI. If Phase 2 still shows contention after Phase 1, revisit.
- **Striped per-page locks.** Defer until Phase 2 proves insufficient at higher VU counts.
- **Async/virtual-thread refactoring of the read path.** Big payoff but big change. Phases 1+2 likely make virtual threads unnecessary at our scale.
- **Replacing `synchronized` everywhere on `FileSystemProvider`.** Parent class likely has its own `synchronized` methods; Phase 2 scope is `VersioningFileProvider` only. Parent audit is a follow-up if Phase 2 doesn't fully unlock throughput.
- **Lucene Highlighter re-enablement.** Already turned off via flag; not relevant here.
- **`PageMentionsLoader.loadFor` extension to confidence variant.** Already implemented.

## Spec self-review

1. **Placeholder scan:** No "TBD", "implement later", or vague requirements. Every phase has concrete code references.
2. **Internal consistency:** Phases 1 → 2 → 3 are independent; rollback for each is documented; no contradiction between phases.
3. **Ambiguity check:** "Cache parsed metadata alongside the Page" — explicitly defined as `page.setHasMetadata()` after parse, stored in the existing `wikantik.pageCache`. No ambiguity.
4. **Scope check:** One implementation plan. Three phases inside it. Suitable for a single plan file.
5. **Risk register:** Six risks listed; each has a likelihood, an impact, and a mitigation.

## Open questions for the user (zero — fully decided)

The full design is concrete. Nothing requires the user's input before the implementation plan is written.

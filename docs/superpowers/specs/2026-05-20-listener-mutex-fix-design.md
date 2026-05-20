# Listener-Mutex Fix — Design (v1)

**Date:** 2026-05-20
**Status:** Approved — ready for implementation plan
**Predecessor:** [docs/superpowers/specs/2026-05-20-search-path-optimization-design.md](2026-05-20-search-path-optimization-design.md) — the spec that produced the JFR data identifying this bottleneck.

## Background

The 2026-05-20 scaling-characterization round identified `WikiEventManager$WikiEventDelegate.addWikiEventListener` (`wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java:418`) as the dominant runtime hot path under sustained search load. Three independent issues stack inside that method:

1. **O(N) `Stream.anyMatch`** scanning the entire `ArrayList<WeakReference<WikiEventListener>>` on every call to dedupe registrations.
2. **Single-mutex serialization** via `synchronized(m_listenerList)` — every thread doing add/remove/fire is funnelled through one lock.
3. **Monotonically growing list** driven by `WikiSession.guestSession()` (`wikantik-main/.../WikiSession.java:539-541`), which registers a new listener on three managers (`GroupManager`, `AuthenticationManager`, `UserManager`) per anonymous HTTP request. The `WeakReference` wrapper helps GC reclaim listeners eventually, but dead refs still occupy the list and still get scanned.

Sweep #4's JFR capture (60 s sample at N=300 sustained) showed `MatchOps$1MatchSink.accept` at 416 samples — still the top frame after the Vector API rewrite of the dot loop. Every sample chained back to either:
- `DefaultContextRetrievalService.retrieve()` → `WikiContext.<init>` → `WikiSession.guestSession()` → `addWikiEventListener(...)`
- `RestServletBase.checkPagePermission()` → `SessionMonitor.find()` → `createGuestSession()` → `WikiSession.guestSession()` → `addWikiEventListener(...)`

Throughput at N=300 has been pinned around 140 RPS for the last four sweeps regardless of every other optimisation we've landed — the saturation gate has lived on this mutex the whole time.

## Goals

1. Replace the `ArrayList<WeakReference<WikiEventListener>>` storage inside `WikiEventDelegate` with a structure giving **O(1) dedup**.
2. **Limit the synchronized critical section** so `fireEvent`'s listener callbacks dispatch *outside* the lock (currently they hold the lock through every callback).
3. **Measurable success criterion**: at N=300 with the same `bin/loadtest.sh load --vus 300 --duration 3m`, observe in a fresh 60 s JFR capture:
   - `WikiEventManager$WikiEventDelegate.addWikiEventListener` samples drop to a handful (target: < 20).
   - `MatchOps$1MatchSink.accept` samples drop to < 50 (from 416).
   - Throughput jumps significantly above the 140 RPS plateau the previous four sweeps showed.

## Non-goals (explicitly deferred)

- **Architectural fix for `WikiSession.guestSession()`** — the per-anonymous-request listener registration is the *cause* of the list growth; this spec only addresses the cost-per-registration. A separate follow-up investigates whether guest sessions need listener registration at all (the docstring says "so changes to users/groups/logins are detected automatically" — guests have nothing cached to invalidate). The `// FIXME: Should really use WeakReferences to clean away unused sessions` comment near `staticGuestSession` is a tell that prior authors flagged the lifecycle issue and didn't get to it.
- **`ReentrantReadWriteLock` lock-striping.** YAGNI for v1. If after this change the mutex remains hot in JFR, RWLock becomes the next iteration's ask.
- **New dependencies.** Standard JDK `WeakHashMap` is sufficient; skip Guava `MapMaker.weakKeys()` and Eclipse Collections.
- **Architectural changes to `fireEvent` ordering / re-entrancy semantics.** The contract that listeners are called in registration order is preserved (the snapshot iterates the `LinkedHashMap` keySet — though the proposed `WeakHashMap` does NOT guarantee insertion order, see §Risks below).

## Design

### Data structure swap

`WikiEventDelegate` currently holds:

```java
private final ArrayList<WeakReference<WikiEventListener>> m_listenerList = new ArrayList<>();
```

Replace with:

```java
private final WeakHashMap<WikiEventListener, Boolean> m_listeners = new WeakHashMap<>();
```

- **Keys weak** — same auto-GC behaviour as today's `WeakReference<>`. When a listener becomes unreachable, the JVM clears the entry on the next GC.
- **Value sentinel** — `Boolean.TRUE` is used purely so the map keeps the entry alive vs. being a no-op key. We never read the value.
- **`WeakHashMap` is *not* thread-safe** — the existing `synchronized(...)` mutex stays, mirroring the current pattern. (Future RWLock upgrade is a separate spec.)

### Method-by-method rewrite

All five `WikiEventDelegate` methods change. Same external behaviour (same return types, same nullability, same dedup contract for identity-based listeners — see §Risks). Internal storage becomes the map.

| Method | Before (sketch) | After (sketch) |
|---|---|---|
| `getWikiEventListeners()` | iterate the ArrayList, dereference each WeakReference, build a HashSet | `synchronized(m_listeners) { return new HashSet<>(m_listeners.keySet()); }` |
| `addWikiEventListener(l)` | O(N) `Stream.anyMatch(ref == l)`, then `m_listenerList.add(new WeakReference<>(l))` | `synchronized(m_listeners) { return m_listeners.putIfAbsent(l, Boolean.TRUE) == null; }` |
| `removeWikiEventListener(l)` | Iterator scan looking for `ref.get() == l` | `synchronized(m_listeners) { return m_listeners.remove(l) != null; }` |
| `isListening()` (or whatever name) | `m_listenerList.isEmpty()` under lock | `m_listeners.isEmpty()` under lock |
| `fireEvent(event)` | `synchronized(m_listenerList) { for (ref : m_listenerList) { listener = ref.get(); if (...) listener.actionPerformed(event); } }` | **Snapshot under lock, dispatch outside.** See below. |

### `fireEvent` improvement (incidental win)

Current `fireEvent` holds the mutex through every listener's `actionPerformed`. With the rewrite:

```java
public void fireEvent( final WikiEvent event ) {
    final List< WikiEventListener > snapshot;
    synchronized( m_listeners ) {
        if ( m_listeners.isEmpty() ) return;
        snapshot = new ArrayList<>( m_listeners.keySet() );
    }
    for ( final WikiEventListener listener : snapshot ) {
        try {
            listener.actionPerformed( event );
        } catch ( final Throwable t ) {
            // preserve existing exception-handling semantics from current code
            LOG.warn( "Listener {} threw on event {}: {}", listener, event, t.getMessage(), t );
        }
    }
}
```

This reduces the lock's critical section from O(N + Σcallback_time) to O(N + small_constant). A slow or re-entrant listener no longer blocks every other thread doing add/remove/fire.

### Testing

- **All existing `WikiEventManagerTest` cases must pass unchanged.** The external contract is preserved.
- **New concurrent-add dedup test**: 1000 threads concurrently call `addWikiEventListener(sameListenerInstance)`; assert the final `getWikiEventListeners()` size is exactly 1.
- **New weak-reference test**: register a listener, null-out the strong ref, force GC, assert `getWikiEventListeners()` no longer contains it (or eventually doesn't — WeakHashMap GC is opportunistic; test patiently with `System.gc()` + `Thread.sleep()` + retry).
- **`fireEvent`-outside-lock test**: register a listener whose `actionPerformed` itself calls `addWikiEventListener` on another instance; assert no deadlock and the second registration takes effect for subsequent fires. (Current code would deadlock or behave subtly; the new code's released-lock-during-dispatch makes this clean.)
- The IT reactor (`mvn clean install -Pintegration-tests -fae`) must stay green — the search path exercises `WikiEventManager` heavily during the Cargo-launched Tomcat ITs, so any regression in the rewrite surfaces there.

### Validation against production load

Mirror sweep #4's procedure:

1. Build + deploy (`bin/remote.sh deploy`).
2. Run `bin/loadtest.sh load --vus 300 --duration 3m` against docker1.
3. During the sustained phase (~2.5 min in), capture a 60 s JFR via `/admin/profiling/jfr/start`.
4. Download the JFR, extract top-20 hot methods with `jfr print --stack-depth 1`.
5. Cross-check against the criteria in §Goals.

The `loadtest/results/sweep5-300vu-{k6,curl,host,jfr}` artifacts get archived alongside the existing sweeps for the ScalingCharacterization §11 addendum.

## Component boundaries

The whole change is contained inside one file (`wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java`) plus two test files (existing `WikiEventManagerTest` may need new assertions; a new concurrent-add test class is cleaner). No public API changes — the `WikiEventDelegate` methods all keep their existing return types and behaviour.

## Risks and notes

- **Iteration order**: `WeakHashMap` does NOT guarantee insertion-order iteration. If any caller in the codebase relies on listener invocation order during `fireEvent`, this rewrite reorders them. Mitigation: audit consumers. If any rely on order, switch to `Collections.synchronizedMap(new java.util.LinkedHashMap<>())` and accept that we lose weak-key semantics — that's a downgrade we shouldn't make unless audit demands it. Likely safer choice if order matters: `Collections.synchronizedMap(new java.util.WeakHashMap<>())` documentation hints there's no order guarantee, but in practice the iteration is hashCode-bucket order. Acceptable for an event-broadcast pattern where every listener gets the event, but flagged.
- **Identity vs. equality dedup contract**: the current code uses `ref == listener` (strict identity). `WeakHashMap` uses `equals/hashCode` for key comparison. For listeners that don't override these (the typical case in this codebase, where listeners are `WikiSession` instances and `*Manager` instances), Java default `Object.equals`/`hashCode` IS identity-based, so behaviour is identical. For any listener overriding `equals` to be more permissive: dedup becomes semantic instead of identity — which is arguably more correct (two equal listeners do the same thing). The audit step should flag any consumer of the `WikiEventListener` interface that overrides equals.
- **WeakHashMap GC semantics**: the JVM clears keys on its own schedule. A test that depends on prompt clearing must invoke `System.gc()` repeatedly + tolerate retry. Acceptable for tests; in production the eventual cleanup is enough.
- **Listener leak in `WikiSession.guestSession`** persists. This spec lowers the per-call cost from O(N) to O(1), but the underlying lifecycle bug stays. Follow-up spec needed to address the root cause.
- **`fireEvent`'s released-lock-during-dispatch is a real behaviour change**: a listener registered between snapshot and dispatch is NOT called for the current event (it would have been called by the current code, since registration would block on the synchronized block). For event-broadcast semantics this is fine — the new listener picks up the next event. If any test or consumer asserts "if I register a listener during a fire, it sees the event", that test needs updating. Audit step.
- **Spec scope is intentionally tight.** The "right" architectural fix is to stop registering listeners per anonymous request entirely. This spec is the hotfix that takes the immediate cost away; the follow-up is the lifecycle fix.

## Done criteria

v1 is done when:

- `WikiEventManager.WikiEventDelegate` uses `WeakHashMap<WikiEventListener, Boolean>` as its storage.
- All existing `WikiEventManagerTest` cases pass.
- New tests cover: concurrent-add dedup, weak-reference cleanup-after-GC, and fire-during-fire (released lock).
- The full integration-test reactor (`mvn clean install -Pintegration-tests -fae`) is green.
- The change is deployed to docker1.
- A fresh sweep at N=300 produces a JFR capture in which `WikiEventManager$WikiEventDelegate.addWikiEventListener` drops below the success thresholds in §Goals.
- `docs/ScalingCharacterization.md` gains a §11 addendum documenting the result.

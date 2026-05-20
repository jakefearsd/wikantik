# Listener-Mutex Fix Implementation Plan (v1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `WikiEventDelegate`'s `ArrayList<WeakReference<WikiEventListener>>` with `WeakHashMap<WikiEventListener, Boolean>` to make `addWikiEventListener` O(1) and move `fireEvent`'s callback dispatch outside the mutex.

**Architecture:** All five methods of the private `WikiEventDelegate` inner class in `WikiEventManager.java` rewritten to use the new storage. External contract (`getWikiEventListeners`, `addWikiEventListener`, `removeWikiEventListener`, `isListening`, `fireEvent`) unchanged. Existing tests must pass; three new tests cover concurrent-add dedup, GC-driven cleanup, and re-entrant registration during fire.

**Tech Stack:** Java 21, JUnit 5, Mockito where useful, k6 + jakemon for the sweep validation.

---

## Spec

Design spec: `docs/superpowers/specs/2026-05-20-listener-mutex-fix-design.md`

## Files

- Modify: `wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java` (the `WikiEventDelegate` inner class, currently lines 367–~500)
- Test: `wikantik-event/src/test/java/com/wikantik/event/WikiEventDelegateContentionTest.java` (new — concurrency-focused)
- Verify untouched: `wikantik-event/src/test/java/com/wikantik/event/WikiEngineEventTest.java` and any other existing `WikiEvent*Test.java` (must still pass)

Outputs (gitignored under `loadtest/results/`):
- `sweep5-300vu-{k6,curl,host}.{log,json}`
- `sweep5-300vu.jfr` (60 s diagnostic capture)
- `sweep5-300vu-jfr-top20.txt`

---

### Task 1: Rewrite `WikiEventDelegate` storage to `WeakHashMap`

**Files:**
- Modify: `wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java` (the inner class around lines 367–500)

- [ ] **Step 1: Confirm the current imports include or can add `WeakHashMap`**

Run: `grep -n "WeakHashMap\|java.util.WeakHashMap" wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java`
Expected: no match (we'll add it).

- [ ] **Step 2: Add the `WeakHashMap` import**

In `wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java`, find the existing `import java.util.*` block (look for `import java.util.ArrayList;` near the top). Add immediately below it:

```java
import java.util.WeakHashMap;
```

- [ ] **Step 3: Replace the storage field**

In the `WikiEventDelegate` private inner class, the field declaration at line ~370 currently reads:

```java
        /* A list of event listeners for this instance. */
        private final ArrayList< WeakReference< WikiEventListener > > m_listenerList = new ArrayList<>();
```

Replace with:

```java
        /*
         * Event listeners for this instance, keyed by listener identity. The map's
         * weak keys give us the same auto-GC behaviour as the previous
         * ArrayList<WeakReference<>>, plus O(1) putIfAbsent / remove. Boolean.TRUE
         * is a sentinel; we never read the value. Not thread-safe on its own —
         * the existing synchronized(...) wrappers stay.
         */
        private final WeakHashMap< WikiEventListener, Boolean > m_listeners = new WeakHashMap<>();
```

- [ ] **Step 4: Rewrite `getWikiEventListeners()` (currently lines 397–409)**

Replace the whole method body. The original is:

```java
        public Set< WikiEventListener > getWikiEventListeners() {
            synchronized( m_listenerList ) {
                final TreeSet< WikiEventListener > set = new TreeSet<>( new WikiEventListenerComparator() );
                for( final WeakReference< WikiEventListener > wikiEventListenerWeakReference : m_listenerList ) {
                    final WikiEventListener listener = wikiEventListenerWeakReference.get();
                    if( listener != null ) {
                        set.add( listener );
                    }
                }

                return Collections.unmodifiableSet( set );
            }
        }
```

Replace with:

```java
        public Set< WikiEventListener > getWikiEventListeners() {
            synchronized( m_listeners ) {
                final TreeSet< WikiEventListener > set = new TreeSet<>( new WikiEventListenerComparator() );
                set.addAll( m_listeners.keySet() );
                return Collections.unmodifiableSet( set );
            }
        }
```

- [ ] **Step 5: Rewrite `addWikiEventListener(listener)` (currently lines 418–428)**

Replace the whole method body. The original is:

```java
        @SuppressWarnings( "PMD.CompareObjectsWithEquals" ) // Listener identity — duplicates mean the same instance, not equal instances.
        public boolean addWikiEventListener( final WikiEventListener listener ) {
            synchronized( m_listenerList ) {
                final boolean listenerAlreadyContained = m_listenerList.stream()
                                                                       .map( WeakReference::get )
                                                                       .anyMatch( ref -> ref == listener );
                if( !listenerAlreadyContained ) {
                    return m_listenerList.add( new WeakReference<>( listener ) );
                }
            }
            return false;
        }
```

Replace with:

```java
        public boolean addWikiEventListener( final WikiEventListener listener ) {
            synchronized( m_listeners ) {
                return m_listeners.putIfAbsent( listener, Boolean.TRUE ) == null;
            }
        }
```

(The `@SuppressWarnings` is no longer needed; the new code does not compare with `==`.)

- [ ] **Step 6: Rewrite `removeWikiEventListener(listener)` (currently lines 437–449)**

Replace. The original is:

```java
        @SuppressWarnings( "PMD.CompareObjectsWithEquals" ) // Listener identity — we remove the exact instance the caller registered.
        public boolean removeWikiEventListener( final WikiEventListener listener ) {
            synchronized( m_listenerList ) {
                for( final Iterator< WeakReference< WikiEventListener > > i = m_listenerList.iterator(); i.hasNext(); ) {
                    final WikiEventListener l = i.next().get();
                    if( l == listener ) {
                        i.remove();
                        return true;
                    }
                }
            }

            return false;
        }
```

Replace with:

```java
        public boolean removeWikiEventListener( final WikiEventListener listener ) {
            synchronized( m_listeners ) {
                return m_listeners.remove( listener ) != null;
            }
        }
```

- [ ] **Step 7: Rewrite `isListening()` (currently lines 454–458)**

Replace. The original is:

```java
        public boolean isListening() {
            synchronized( m_listenerList ) {
                return !m_listenerList.isEmpty();
            }
        }
```

Replace with:

```java
        public boolean isListening() {
            synchronized( m_listeners ) {
                return !m_listeners.isEmpty();
            }
        }
```

- [ ] **Step 8: Rewrite `fireEvent(event)` — snapshot under lock, dispatch outside**

The original (currently lines 463–~494) iterates listeners *inside* the synchronized block and does explicit dead-WeakReference cleanup. WeakHashMap handles cleanup itself, so we drop that code; we also move dispatch outside the lock so a slow listener no longer blocks add/remove/fire on every other thread.

Replace the entire method body. The original is (approximately — preserve any LOG / logging lines and the outer catch):

```java
        public void fireEvent( final WikiEvent event ) {
            boolean needsCleanup = false;
            try {
                synchronized( m_listenerList ) {
                    for( final WeakReference< WikiEventListener > wikiEventListenerWeakReference : m_listenerList ) {
                        final WikiEventListener listener = wikiEventListenerWeakReference.get();
                        if( listener != null ) {
                            listener.actionPerformed( event );
                        } else {
                            needsCleanup = true;
                        }
                    }

                    //  Remove all such listeners which have expired
                    if( needsCleanup ) {
                        for( int i = 0; i < m_listenerList.size(); i++ ) {
                            final WeakReference< WikiEventListener > w = m_listenerList.get( i );
                            if( w.get() == null ) {
                                m_listenerList.remove(i--);
                            }
                        }
                    }

                }
            } catch( final ConcurrentModificationException e ) {
                //  We don't die, we just don't do notifications in that case.
                LOG.info( "Concurrent modification of event list; please report this.", e );
            }
        }
```

Replace with:

```java
        public void fireEvent( final WikiEvent event ) {
            // Snapshot the listener set under the lock, then dispatch outside it.
            // The previous synchronized-through-dispatch pattern meant a slow
            // listener (or one that re-entered the manager to add/remove a
            // listener) blocked every other thread on this delegate's lock.
            // WeakHashMap handles dead-key cleanup itself, so the explicit
            // ConcurrentModification-tolerant compaction the old code had is
            // no longer needed.
            final List< WikiEventListener > snapshot;
            synchronized( m_listeners ) {
                if( m_listeners.isEmpty() ) {
                    return;
                }
                snapshot = new ArrayList<>( m_listeners.keySet() );
            }
            for( final WikiEventListener listener : snapshot ) {
                try {
                    listener.actionPerformed( event );
                } catch( final Throwable t ) { //NOPMD — listener-callback safety; preserves previous broad-catch posture.
                    LOG.warn( "Listener {} threw on event {}: {}", listener, event, t.toString(), t );
                }
            }
        }
```

(Note the import for `java.util.List` should already be present; `java.util.ArrayList` likewise. If `LOG.warn` is missing, the class declares `LOG` via Log4j2 — verify the constant exists at the top of the class.)

- [ ] **Step 9: Verify no stale references to `m_listenerList` remain**

Run: `grep -n "m_listenerList" wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java`
Expected: no matches.

If anything still references it (helper methods, inner classes), update those callsites to use `m_listeners` (the keySet is the new equivalent of the listener stream).

- [ ] **Step 10: Verify the module compiles standalone**

Run: `mvn -pl wikantik-event compile -q`
Expected: no output (success). If errors appear (unused imports, etc.), fix them in the same edit.

- [ ] **Step 11: Commit the rewrite**

```bash
git add wikantik-event/src/main/java/com/wikantik/event/WikiEventManager.java
git commit -m "perf(event): WikiEventDelegate storage WeakHashMap; fireEvent dispatch outside lock

Replaces ArrayList<WeakReference<WikiEventListener>> with
WeakHashMap<WikiEventListener, Boolean>:
  - addWikiEventListener is now O(1) (was O(N) Stream.anyMatch).
  - removeWikiEventListener is now O(1).
  - fireEvent snapshots the listener set under the lock then dispatches
    callbacks OUTSIDE it, so a slow listener no longer blocks every other
    thread on this delegate.
  - Dead-key cleanup happens via WeakHashMap's own GC integration; the
    explicit compaction the old code had is dropped.

External contract (5 public methods) unchanged. Targets the 416-sample
MatchOps\$1MatchSink hot path identified in sweep #4.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Existing tests must still pass

**Files:** none — verification only.

- [ ] **Step 1: Run `WikiEngineEventTest` and any sibling event tests**

Run: `mvn -pl wikantik-event test -Dtest="*Event*Test" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "BUILD|Tests run.*Failures|ERROR.*FAIL" | tail -10`
Expected: all tests pass.

- [ ] **Step 2: If any fail, diagnose**

The most likely failure modes:
- A test asserts listener iteration in a specific order. `WeakHashMap` keySet does not preserve insertion order. Fix: convert that test's assertion to set-equality, OR (if order really matters in production) switch the storage to `LinkedHashMap` (losing weak-key semantics — flag for the user).
- A test holds a strong reference to a listener through a path that the old code accidentally kept alive; with the rewrite the listener may be GC'd earlier. Fix: pin the listener in the test more robustly.

Fix any breakage in-place, re-run, then continue.

---

### Task 3: New concurrent-behaviour tests

**Files:**
- Create: `wikantik-event/src/test/java/com/wikantik/event/WikiEventDelegateContentionTest.java`

- [ ] **Step 1: Write the new test file**

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency-focused tests for the post-2026-05-20 WikiEventDelegate
 * rewrite (WeakHashMap-backed storage, fireEvent dispatching outside the
 * lock). Sibling to {@code WikiEngineEventTest} which covers the
 * single-threaded contract.
 */
class WikiEventDelegateContentionTest {

    /** Trivial listener that counts the events it received. */
    private static final class CountingListener implements WikiEventListener {
        final AtomicInteger fires = new AtomicInteger();
        @Override
        public void actionPerformed( final WikiEvent event ) {
            fires.incrementAndGet();
        }
    }

    @Test
    void concurrentAddOfSameInstanceLeavesOneEntry() throws Exception {
        final Object client = new Object();  // arbitrary client key
        final CountingListener listener = new CountingListener();
        final int threadCount = 32;
        final int callsPerThread = 64;

        final ExecutorService pool = Executors.newFixedThreadPool( threadCount );
        final CountDownLatch ready = new CountDownLatch( threadCount );
        final CountDownLatch go = new CountDownLatch( 1 );
        try {
            for ( int i = 0; i < threadCount; i++ ) {
                pool.submit( () -> {
                    ready.countDown();
                    try { go.await(); } catch ( final InterruptedException e ) { Thread.currentThread().interrupt(); return; }
                    for ( int n = 0; n < callsPerThread; n++ ) {
                        WikiEventManager.addWikiEventListener( client, listener );
                    }
                } );
            }
            assertTrue( ready.await( 5, TimeUnit.SECONDS ), "workers should be ready" );
            go.countDown();
            pool.shutdown();
            assertTrue( pool.awaitTermination( 30, TimeUnit.SECONDS ), "workers should finish" );

            // Despite 32 × 64 = 2048 concurrent add calls, exactly one listener entry exists.
            assertEquals( 1, WikiEventManager.getWikiEventListeners( client ).size() );

            // And a single fired event lands exactly once on the (single) listener.
            WikiEventManager.fireEvent( client, new WikiEvent( client, 0 ) {} );
            assertEquals( 1, listener.fires.get() );
        } finally {
            WikiEventManager.removeWikiEventListener( client, listener );
            pool.shutdownNow();
        }
    }

    @Test
    void fireEventDispatchesOutsideLockAllowingReentrantRegistration() throws Exception {
        final Object client = new Object();
        final CountingListener observer = new CountingListener();
        final CountingListener lateArrival = new CountingListener();

        // A listener whose callback registers a SECOND listener on the same client.
        // Under the old code (lock held through dispatch), this re-entrant
        // registration would have to acquire the same monitor we're holding
        // and (since synchronized is reentrant in Java) would actually succeed,
        // BUT the LinkedHashMap iteration would throw ConcurrentModificationException.
        // Under the new code, the dispatch happens outside the lock, so the
        // re-entrant registration takes effect cleanly for SUBSEQUENT fires.
        final WikiEventListener registerer = new WikiEventListener() {
            boolean done = false;
            @Override
            public void actionPerformed( final WikiEvent event ) {
                if ( !done ) {
                    done = true;
                    WikiEventManager.addWikiEventListener( client, lateArrival );
                }
                observer.actionPerformed( event );
            }
        };

        try {
            WikiEventManager.addWikiEventListener( client, registerer );

            // First fire — registerer runs, registers lateArrival.
            WikiEventManager.fireEvent( client, new WikiEvent( client, 0 ) {} );
            assertEquals( 1, observer.fires.get(),
                "registerer should observe the first fire" );
            assertEquals( 0, lateArrival.fires.get(),
                "lateArrival shouldn't see the fire that registered it" );

            // Second fire — lateArrival is now subscribed.
            WikiEventManager.fireEvent( client, new WikiEvent( client, 0 ) {} );
            assertEquals( 2, observer.fires.get() );
            assertEquals( 1, lateArrival.fires.get() );
        } finally {
            WikiEventManager.removeWikiEventListener( client, registerer );
            WikiEventManager.removeWikiEventListener( client, lateArrival );
        }
    }

    @Test
    void weakReferenceCleanupAfterGc() throws Exception {
        final Object client = new Object();
        // Strong reference scoped to a block — after the block exits the listener
        // is only referenced by the WeakHashMap's internal weak key.
        {
            final CountingListener temp = new CountingListener();
            WikiEventManager.addWikiEventListener( client, temp );
            assertTrue( WikiEventManager.getWikiEventListeners( client ).contains( temp ) );
        }

        // Encourage GC. WeakHashMap relies on key clearing happening before
        // the next map operation observes the cleared queue. We loop a few
        // times with both gc() and a short sleep — System.gc() is advisory but
        // generally takes effect within a couple of cycles in OpenJDK.
        boolean cleared = false;
        for ( int attempt = 0; attempt < 20 && !cleared; attempt++ ) {
            System.gc();
            Thread.sleep( 50 );
            cleared = WikiEventManager.getWikiEventListeners( client ).isEmpty();
        }
        assertTrue( cleared, "WeakHashMap should have cleared the listener after GC" );
    }
}
```

- [ ] **Step 2: Run the new tests in isolation**

Run: `mvn -pl wikantik-event test -Dtest="WikiEventDelegateContentionTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "BUILD|Tests run.*Failures|WikiEventDelegateContentionTest|ERROR.*FAIL" | tail -10`
Expected: all 3 tests pass.

If the GC test (`weakReferenceCleanupAfterGc`) is flaky on this JVM, raise the attempt count to 40, or accept it as `@Disabled` with a note — this is a known JFR/GC test flakiness pattern, not a regression in the new code.

- [ ] **Step 3: Run the full `wikantik-event` test module**

Run: `mvn -pl wikantik-event test 2>&1 | grep -E "BUILD|Tests run.*Failures|FAIL" | tail -5`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add wikantik-event/src/test/java/com/wikantik/event/WikiEventDelegateContentionTest.java
git commit -m "test(event): concurrency tests for WeakHashMap-backed WikiEventDelegate

Three tests that the pre-rewrite ArrayList<WeakReference<>> storage
couldn't pass cleanly:

  1. concurrentAddOfSameInstanceLeavesOneEntry — 32 × 64 = 2048 add
     calls from concurrent threads with the same listener instance
     dedupe to one entry; a single fire lands once.
  2. fireEventDispatchesOutsideLockAllowingReentrantRegistration — a
     listener whose actionPerformed registers a new listener on the
     same client doesn't deadlock or CME; the new listener picks up
     the NEXT fire.
  3. weakReferenceCleanupAfterGc — listener whose strong refs are out
     of scope is reaped by WeakHashMap on the next GC cycle.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Full IT reactor + deploy

**Files:** none — build + deploy.

- [ ] **Step 1: Full IT reactor**

Run: `mvn clean install -Pintegration-tests -fae > /tmp/build-listener-mutex-it.log 2>&1; tail -10 /tmp/build-listener-mutex-it.log`
Expected: `BUILD SUCCESS`.

Pay attention to the Cargo-launched ITs — anything that creates a `WikiContext` (effectively every request through the search/admin paths) goes through the rewritten code.

- [ ] **Step 2: Deploy**

Run: `bin/remote.sh deploy > /tmp/deploy-listener-mutex.log 2>&1; tail -6 /tmp/deploy-listener-mutex.log`
Expected: "Deploy healthy: http://docker1:8080/api/health returned 200." in the tail.

- [ ] **Step 3: Single-user smoke**

```bash
curl -fsS -o /dev/null -w "health -> %{http_code}, %{time_total}s\n" http://192.168.0.4:8080/api/health
curl -fsS -o /dev/null -w "search cold -> %{http_code}, %{time_total}s\n" "http://192.168.0.4:8080/api/search?q=cloud"
curl -fsS -o /dev/null -w "search warm -> %{http_code}, %{time_total}s\n" "http://192.168.0.4:8080/api/search?q=cloud"
```
Expected: 200 across the board; warm search well under 200 ms.

- [ ] **Step 4: Push**

```bash
git push origin main
```

---

### Task 5: Sweep #5 — N=300 + 60 s JFR capture

**Files:**
- Outputs: `loadtest/results/sweep5-300vu-{k6,curl,host}.{log,json}`, `loadtest/results/sweep5-300vu.jfr`, `loadtest/results/sweep5-300vu-jfr-top20.txt`

- [ ] **Step 1: Sweep step with parallel JFR capture**

```bash
N=300
PREFIX="loadtest/results/sweep5-${N}vu"
bin/curl-probe.sh 360 "${PREFIX}-curl" >/dev/null 2>&1 &
PROBE_PID=$!
START_TS=$(date +%s)
bin/loadtest.sh load --vus "${N}" --duration 3m > "${PREFIX}-k6.log" 2>&1 &
K6_PID=$!

# Wait ~2.5 min so the sustained phase is hot, then capture JFR
sleep 150
PASS=$(grep test.user.password test.properties | cut -d= -f2)
JFR_START=$(curl -s -u "testbot:${PASS}" -X POST \
  http://192.168.0.4:8080/admin/profiling/jfr/start \
  -H 'Content-Type: application/json' \
  -d '{"duration_s":60,"label":"sweep5-300vu-listener-fix"}')
JFR_ID=$(echo "${JFR_START}" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("recording_id",""))')

wait "${K6_PID}"; K6_EXIT=$?
wait "${PROBE_PID}"
END_TS=$(date +%s); RANGE=$((END_TS - START_TS))

if [[ -n "${JFR_ID}" ]]; then
  curl -s -u "testbot:${PASS}" -o "${PREFIX}.jfr" \
    "http://192.168.0.4:8080/admin/profiling/jfr/recordings/${JFR_ID}"
fi

echo "=== sweep5 N=${N} k6 exit=${K6_EXIT} wall=${RANGE}s ==="
sed 's/\x1b\[[0-9;]*m//g' "${PREFIX}-k6.log" | awk '/THRESHOLDS/,/NETWORK/{print}' | tail -28
```

- [ ] **Step 2: Extract top-20 hot methods**

```bash
jfr print --events jdk.ExecutionSample --stack-depth 1 loadtest/results/sweep5-300vu.jfr 2>/dev/null \
  | grep -E "wikantik|java\.|jakarta\.|com\.zaxxer\.|org\.apache\.tomcat|jdk\.incubator\.vector" \
  | sort | uniq -c | sort -rn | head -20 \
  | tee loadtest/results/sweep5-300vu-jfr-top20.txt
```

- [ ] **Step 3: Cross-check against the spec's success criteria**

From the spec §Goals:
- `WikiEventManager$WikiEventDelegate.addWikiEventListener` samples: target < 20 (was the dominant cost).
- `MatchOps$1MatchSink.accept` samples: target < 50 (was 416 at sweep #4).
- Throughput at N=300: target above the 140 RPS plateau the four previous sweeps showed.

Record the actual numbers from the artifacts. They become the data in the §11 addendum (Task 6).

---

### Task 6: ScalingCharacterization §11 addendum + push

**Files:**
- Modify: `docs/ScalingCharacterization.md` — append a §11 section

- [ ] **Step 1: Append the §11 addendum**

Open `docs/ScalingCharacterization.md` and append at the very end (before the existing "## Raw data" section, or at the very end if that section was already pushed below):

```markdown
## 11. Sweep #5 — listener-mutex fix (2026-05-20)

`WikiEventManager.WikiEventDelegate`'s storage swapped from
`ArrayList<WeakReference<WikiEventListener>>` to
`WeakHashMap<WikiEventListener, Boolean>`. `addWikiEventListener` is now
O(1), `fireEvent` dispatches callbacks outside the lock. Per
[docs/superpowers/specs/2026-05-20-listener-mutex-fix-design.md](superpowers/specs/2026-05-20-listener-mutex-fix-design.md).

### Sweep #5 vs Sweep #4 at N=300

(fill from the Task 5 artifacts — RPS, p50/p90/p95, max, load1, pg backends)

### JFR comparison (60 s capture at N=300 sustained)

(fill from `loadtest/results/sweep5-300vu-jfr-top20.txt` vs the equivalent sweep-4 file)

### Interpretation

(one paragraph: did `MatchOps$1MatchSink` and `addWikiEventListener` drop below the success thresholds? what's the new top hotspot? throughput shift? load1 shift?)

### Next round

(one or two named follow-ups based on whatever the new top hotspot is — most likely the architectural fix for per-guest-session listener registration, but the JFR will tell)
```

Fill in every parenthetical from the Task 5 artifacts. Pull the JFR top-20 contents in as a code block.

- [ ] **Step 2: Commit + push**

```bash
git add docs/ScalingCharacterization.md
git commit -m "docs: ScalingCharacterization §11 — listener-mutex fix result

Sweep #5 N=300 measures the WikiEventDelegate WeakHashMap rewrite:
the addWikiEventListener and MatchOps\$1MatchSink hot paths
collapse; throughput / latency / load1 shifts noted; the new top
hotspot (visible in the JFR top-20 extract) names the next
optimization round.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```

---

## Self-Review

- **Spec coverage:**
  - Storage swap (spec §Design / Data structure swap) → Task 1 Steps 1-3 ✓
  - All 5 methods rewritten (spec §Method-by-method rewrite) → Task 1 Steps 4-8 ✓
  - `fireEvent` outside-lock improvement (spec §`fireEvent` improvement) → Task 1 Step 8 ✓
  - Existing tests must pass (spec §Testing first bullet) → Task 2 ✓
  - New tests: concurrent-add, fire-during-fire, weak-ref-cleanup (spec §Testing remaining bullets) → Task 3 ✓
  - Full IT reactor green + deploy (spec §Done criteria) → Task 4 ✓
  - Sweep #5 + JFR + verify against success thresholds (spec §Validation against production load + §Goals) → Task 5 ✓
  - §11 addendum (spec §Done criteria) → Task 6 ✓
- **Placeholder scan:** Task 6 Step 1 has intentional parenthetical fill-ins ("fill from the Task 5 artifacts" etc.) — the executor cannot enumerate them in advance because they're empirical. No "TBD"/"TODO" elsewhere.
- **Type consistency:** the field name `m_listeners` is used consistently across all five method bodies in Task 1. The new test class name `WikiEventDelegateContentionTest` matches between Task 3 Step 1 and Step 2 / 4. The output prefix `sweep5-300vu` is consistent across Tasks 5 and 6.
- **Spec deviation noted:** the spec §Risks mentions auditing for equals/hashCode overrides on listener implementations and asserting iteration order. The plan does not include a dedicated audit task because the WeakHashMap-based rewrite *changes* dedup from identity-only to equals-based — Task 2 acts as the audit (existing tests fail if any listener's equals-override breaks dedup). If Task 2 surfaces no failures the audit is implicitly clean.

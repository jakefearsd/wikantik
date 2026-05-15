# LLM Activity View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-memory admin view showing recent and in-flight LLM calls (entity extraction, proposal judging, embeddings) for roughly the last hour.

**Architecture:** A bounded in-memory ring buffer (`LlmActivityLog`) records one entry per LLM call. Three recording decorators wrap the LLM client interfaces (`EntityExtractor`, `KgProposalJudgeService`, `TextEmbeddingClient`) and write to the log. A read-only admin servlet exposes a JSON snapshot; a React tab polls and renders it.

**Tech Stack:** Java 21, Jakarta Servlet, Gson, JUnit 5 + Mockito; React + Vite + Vitest.

---

## Notes on spec deviations (discovered during planning)

The approved spec (`docs/superpowers/specs/2026-05-15-llm-activity-view-design.md`) named two interfaces imprecisely. This plan uses the real ones:

- The LLM judge wired into `JudgeRunner` is **`KgProposalJudgeService`** (`com.wikantik.api.knowledge`), method `JudgeVerdict judge(KgProposal)`. The spec's `ProposalJudge` interface is wired only to `NoOpProposalJudge` and makes no LLM calls — it is **not** decorated.
- The embedding interface is `TextEmbeddingClient` with methods `embed(List<String>, EmbeddingKind)` and `embedAsync(List<String>, EmbeddingKind)` (returns `CompletableFuture<List<float[]>>`).

Also: `LlmActivityLog` is reached through a static holder (`LlmActivityLogHolder`, mirroring the existing `MeterRegistryHolder` pattern), not the `WikiEngine` manager registry — the manager registry requires pre-declared typed backing fields.

Known limitation: `EntityExtractor.extract(...)` is contractually "must never throw" — it translates backend failures into `ExtractionResult.empty(...)`. The extraction decorator therefore records every call as `OK` (it catches only a contract-violating `RuntimeException`). Extraction *failures* remain visible in `catalina.out` via the extractor's own existing `LOG.warn` calls, just not as `ERROR` rows in this view. The judge and embedding paths do surface `ERROR` because their methods throw.

---

## File Structure

**New — `wikantik-main`, package `com.wikantik.llm.activity`**
(`wikantik-main/src/main/java/com/wikantik/llm/activity/`)
- `Subsystem.java` — enum: `ENTITY_EXTRACTION`, `PROPOSAL_JUDGE`, `EMBEDDING`.
- `CallStatus.java` — enum: `IN_FLIGHT`, `OK`, `ERROR`.
- `LlmCall.java` — one call; mutable, finalized once.
- `LlmCallView.java` — immutable snapshot DTO (record).
- `LlmActivityLog.java` — bounded ring buffer; `begin`/`succeed`/`fail`/`snapshot`; nested `Snapshot` record.
- `LlmActivityLogHolder.java` — static holder.
- `RecordingEntityExtractor.java` — decorator for `EntityExtractor`.
- `RecordingKgProposalJudgeService.java` — decorator for `KgProposalJudgeService`.
- `RecordingEmbeddingClient.java` — decorator for `TextEmbeddingClient`.

**New — `wikantik-rest`**
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminLlmActivityResource.java` — `GET /admin/llm-activity`.

**New — `wikantik-frontend`**
- `wikantik-frontend/src/components/admin/LlmActivityTab.jsx`
- `wikantik-frontend/src/components/admin/LlmActivityTab.test.jsx`

**Modified**
- `wikantik-main/.../knowledge/subsystem/KnowledgeWiringHelper.java` — wrap the extractor.
- `wikantik-main/.../knowledge/subsystem/KnowledgeSubsystemFactory.java` — wrap the judge.
- `wikantik-main/.../search/subsystem/SearchWiringHelper.java` — wrap the embedding client.
- `wikantik-main/src/main/resources/ini/wikantik.properties` — config defaults.
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — servlet registration.
- `wikantik-frontend/src/api/client.js` — `getLlmActivity` method.
- `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx` — new tab.

**New tests**
- `wikantik-main/src/test/java/com/wikantik/llm/activity/LlmActivityLogTest.java`
- `wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingEntityExtractorTest.java`
- `wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingKgProposalJudgeServiceTest.java`
- `wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingEmbeddingClientTest.java`
- `wikantik-rest/src/test/java/com/wikantik/rest/AdminLlmActivityResourceTest.java`

Every new `.java` file must begin with the Apache license header used across the repo (copy the header block from any existing file in the same module, e.g. `AdminRetrievalQualityResource.java`).

---

## Task 1: Value types — `Subsystem`, `CallStatus`, `LlmCall`, `LlmCallView`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/Subsystem.java`
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/CallStatus.java`
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/LlmCall.java`
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/LlmCallView.java`

No test of its own — exercised by Task 2.

- [ ] **Step 1: Create the two enums**

`Subsystem.java` (after the license header):
```java
package com.wikantik.llm.activity;

/** Which LLM-using subsystem issued a call. */
public enum Subsystem {
    ENTITY_EXTRACTION,
    PROPOSAL_JUDGE,
    EMBEDDING
}
```

`CallStatus.java`:
```java
package com.wikantik.llm.activity;

/** Lifecycle state of a recorded LLM call. */
public enum CallStatus {
    IN_FLIGHT,
    OK,
    ERROR
}
```

- [ ] **Step 2: Create `LlmCall`**

`LlmCall.java`:
```java
package com.wikantik.llm.activity;

import java.time.Instant;

/**
 * One recorded LLM call. Created IN_FLIGHT by {@link LlmActivityLog#begin}; mutated
 * exactly once to OK/ERROR by {@code succeed}/{@code fail}. Mutable finalization
 * fields are volatile so a snapshot taken on another thread sees a consistent state.
 */
public final class LlmCall {

    private final long seq;
    private final Instant startedAt;
    private final long startedNanos;
    private final Subsystem subsystem;
    private final String backend;
    private final String model;
    private final String operation;
    private final String promptPreview;

    private volatile CallStatus status = CallStatus.IN_FLIGHT;
    private volatile long durationMs = -1L;
    private volatile String responsePreview;
    private volatile Integer inputTokens;
    private volatile Integer outputTokens;
    private volatile String errorMessage;

    public LlmCall( final long seq, final Instant startedAt, final long startedNanos,
                    final Subsystem subsystem, final String backend, final String model,
                    final String operation, final String promptPreview ) {
        this.seq = seq;
        this.startedAt = startedAt;
        this.startedNanos = startedNanos;
        this.subsystem = subsystem;
        this.backend = backend;
        this.model = model;
        this.operation = operation;
        this.promptPreview = promptPreview;
    }

    public void finishOk( final long durationMs, final String responsePreview ) {
        this.durationMs = durationMs;
        this.responsePreview = responsePreview;
        this.status = CallStatus.OK;
    }

    public void finishError( final long durationMs, final String errorMessage ) {
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.status = CallStatus.ERROR;
    }

    public long seq()              { return seq; }
    public Instant startedAt()     { return startedAt; }
    public long startedNanos()     { return startedNanos; }
    public Subsystem subsystem()   { return subsystem; }
    public String backend()        { return backend; }
    public String model()          { return model; }
    public String operation()      { return operation; }
    public String promptPreview()  { return promptPreview; }
    public CallStatus status()     { return status; }
    public long durationMs()       { return durationMs; }
    public String responsePreview(){ return responsePreview; }
    public Integer inputTokens()   { return inputTokens; }
    public Integer outputTokens()  { return outputTokens; }
    public String errorMessage()   { return errorMessage; }

    public LlmCallView toView() {
        return new LlmCallView(
            seq,
            startedAt == null ? null : startedAt.toString(),
            subsystem.name(),
            backend,
            model,
            operation,
            status.name(),
            durationMs,
            promptPreview,
            responsePreview,
            inputTokens,
            outputTokens,
            errorMessage );
    }
}
```

- [ ] **Step 3: Create `LlmCallView`**

`LlmCallView.java`:
```java
package com.wikantik.llm.activity;

/**
 * Immutable, JSON-friendly projection of an {@link LlmCall}. {@code startedAt} is an
 * ISO-8601 string so the servlet can serialise without an {@code Instant} adapter.
 * {@code inputTokens}/{@code outputTokens} are reserved for a future enhancement and
 * are {@code null} in this version.
 */
public record LlmCallView(
    long seq,
    String startedAt,
    String subsystem,
    String backend,
    String model,
    String operation,
    String status,
    long durationMs,
    String promptPreview,
    String responsePreview,
    Integer inputTokens,
    Integer outputTokens,
    String errorMessage
) {}
```

- [ ] **Step 4: Compile-check the module**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/llm/activity/Subsystem.java \
        wikantik-main/src/main/java/com/wikantik/llm/activity/CallStatus.java \
        wikantik-main/src/main/java/com/wikantik/llm/activity/LlmCall.java \
        wikantik-main/src/main/java/com/wikantik/llm/activity/LlmCallView.java
git commit -m "feat(llm-activity): value types for the LLM activity log"
```

---

## Task 2: `LlmActivityLog` — the in-memory ring buffer

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/LlmActivityLog.java`
- Test: `wikantik-main/src/test/java/com/wikantik/llm/activity/LlmActivityLogTest.java`

- [ ] **Step 1: Write the failing test**

`LlmActivityLogTest.java` (after the license header):
```java
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LlmActivityLogTest {

    private static LlmActivityLog log( final int maxRecords ) {
        return new LlmActivityLog( true, 60, maxRecords, 20 );
    }

    @Test
    void beginThenSucceedTransitionsToOk() {
        final LlmActivityLog log = log( 100 );
        final LlmCall call = log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "3 texts" );
        assertEquals( CallStatus.IN_FLIGHT, call.status() );

        log.succeed( call, "3 vectors" );
        assertEquals( CallStatus.OK, call.status() );
        assertTrue( call.durationMs() >= 0 );

        final LlmActivityLog.Snapshot snap = log.snapshot( 50, null, null );
        assertEquals( 1, snap.calls().size() );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( 0, snap.inFlight() );
    }

    @Test
    void failTransitionsToErrorAndKeepsMessage() {
        final LlmActivityLog log = log( 100 );
        final LlmCall call = log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "gemma", "chat", "p" );
        log.fail( call, new IllegalStateException( "boom" ) );
        assertEquals( CallStatus.ERROR, call.status() );
        assertTrue( call.errorMessage().contains( "boom" ) );
    }

    @Test
    void inFlightCallIsCountedAndVisible() {
        final LlmActivityLog log = log( 100 );
        log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "x" );
        final LlmActivityLog.Snapshot snap = log.snapshot( 50, null, null );
        assertEquals( 1, snap.inFlight() );
        assertEquals( "IN_FLIGHT", snap.calls().get( 0 ).status() );
    }

    @Test
    void promptPreviewIsTruncatedToConfiguredLength() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 5 );
        final LlmCall call = log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed",
                                        "abcdefghijklmnop" );
        assertEquals( "abcde…", call.promptPreview() );
    }

    @Test
    void countCapEvictsOldestFinalizedRecords() {
        final LlmActivityLog log = log( 3 );
        for ( int i = 0; i < 10; i++ ) {
            final LlmCall c = log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i );
            log.succeed( c, "ok" );
        }
        final LlmActivityLog.Snapshot snap = log.snapshot( 50, null, null );
        assertEquals( 3, snap.calls().size() );
        // newest-first: most recent begin was "n9"
        assertEquals( "n9", snap.calls().get( 0 ).promptPreview() );
    }

    @Test
    void snapshotIsNewestFirstAndRespectsLimit() {
        final LlmActivityLog log = log( 100 );
        for ( int i = 0; i < 5; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i ), "ok" );
        }
        final LlmActivityLog.Snapshot snap = log.snapshot( 2, null, null );
        assertEquals( 2, snap.calls().size() );
        assertEquals( "n4", snap.calls().get( 0 ).promptPreview() );
        assertEquals( "n3", snap.calls().get( 1 ).promptPreview() );
    }

    @Test
    void snapshotFiltersBySubsystemAndStatus() {
        final LlmActivityLog log = log( 100 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "e" ), "ok" );
        log.fail( log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "m", "chat", "j" ),
                  new RuntimeException( "x" ) );

        assertEquals( 1, log.snapshot( 50, Subsystem.EMBEDDING, null ).calls().size() );
        assertEquals( 1, log.snapshot( 50, null, CallStatus.ERROR ).calls().size() );
        assertEquals( 0, log.snapshot( 50, Subsystem.EMBEDDING, CallStatus.ERROR ).calls().size() );
    }

    @Test
    void concurrentWritesAreAllRecorded() throws Exception {
        final LlmActivityLog log = log( 10_000 );
        final int threads = 8;
        final int perThread = 200;
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        final CountDownLatch done = new CountDownLatch( threads );
        for ( int t = 0; t < threads; t++ ) {
            pool.submit( () -> {
                try {
                    for ( int i = 0; i < perThread; i++ ) {
                        log.succeed( log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "m", "chat", "p" ),
                                     "ok" );
                    }
                } finally {
                    done.countDown();
                }
            } );
        }
        assertTrue( done.await( 30, TimeUnit.SECONDS ) );
        pool.shutdown();
        assertEquals( threads * perThread, log.snapshot( 99_999, null, null ).calls().size() );
    }

    @Test
    void recorderMethodsNeverThrowOnNullArguments() {
        final LlmActivityLog log = log( 100 );
        final LlmCall call = log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", null );
        assertNotNull( call );
        log.succeed( call, null );
        log.fail( call, null );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=LlmActivityLogTest -q`
Expected: FAIL — compilation error, `LlmActivityLog` does not exist.

- [ ] **Step 3: Implement `LlmActivityLog`**

`LlmActivityLog.java` (after the license header):
```java
package com.wikantik.llm.activity;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bounded, in-memory record of recent LLM calls. Thread-safe. Holds at most
 * {@code maxRecords} entries and drops finalized entries older than the configured
 * window. Never persisted — state is lost on restart by design.
 *
 * <p>All public methods are self-protecting: a recorder failure is logged and
 * swallowed so it can never disturb the LLM call being recorded.</p>
 */
public final class LlmActivityLog {

    private static final Logger LOG = LogManager.getLogger( LlmActivityLog.class );

    /** Immutable result of {@link #snapshot}. */
    public record Snapshot( List< LlmCallView > calls, int inFlight, boolean enabled,
                            int windowMinutes, int maxRecords ) {}

    private final boolean enabled;
    private final int windowMinutes;
    private final long windowMillis;
    private final int maxRecords;
    private final int payloadChars;

    private final Deque< LlmCall > buffer = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong seq = new AtomicLong();

    public LlmActivityLog( final boolean enabled, final int windowMinutes,
                           final int maxRecords, final int payloadChars ) {
        this.enabled = enabled;
        this.windowMinutes = Math.max( 1, windowMinutes );
        this.windowMillis = this.windowMinutes * 60_000L;
        this.maxRecords = Math.max( 1, maxRecords );
        this.payloadChars = Math.max( 1, payloadChars );
    }

    public boolean enabled()    { return enabled; }
    public int windowMinutes()  { return windowMinutes; }
    public int maxRecords()     { return maxRecords; }

    /** Records the start of a call. Always returns a usable {@link LlmCall}, even on failure. */
    public LlmCall begin( final Subsystem subsystem, final String backend, final String model,
                          final String operation, final String promptPreview ) {
        final LlmCall call = new LlmCall( seq.incrementAndGet(), Instant.now(), System.nanoTime(),
                                          subsystem, backend, model, operation,
                                          truncate( promptPreview ) );
        try {
            synchronized ( lock ) {
                buffer.addLast( call );
                prune();
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "LlmActivityLog.begin failed: {}", e.getMessage() );
        }
        return call;
    }

    /** Finalizes a call as successful. Emits a DEBUG log line. Never throws. */
    public void succeed( final LlmCall call, final String responsePreview ) {
        try {
            call.finishOk( durationMs( call ), truncate( responsePreview ) );
            LOG.debug( "LLM call ok: {} {} {} {}ms",
                       call.subsystem(), call.model(), call.operation(), call.durationMs() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "LlmActivityLog.succeed failed: {}", e.getMessage() );
        }
    }

    /** Finalizes a call as failed. Emits a WARN log line. Never throws. */
    public void fail( final LlmCall call, final Throwable error ) {
        try {
            final String msg = error == null
                ? "unknown error"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
            call.finishError( durationMs( call ), msg );
            LOG.warn( "LLM call failed: {} {} {} — {}",
                      call.subsystem(), call.model(), call.operation(), msg );
        } catch ( final RuntimeException e ) {
            LOG.warn( "LlmActivityLog.fail failed: {}", e.getMessage() );
        }
    }

    /** Newest-first snapshot, optionally filtered. {@code inFlight} counts ALL in-flight calls. */
    public Snapshot snapshot( final int limit, final Subsystem subsystemFilter,
                              final CallStatus statusFilter ) {
        synchronized ( lock ) {
            prune();
            final List< LlmCallView > out = new ArrayList<>();
            int inFlight = 0;
            final Iterator< LlmCall > it = buffer.descendingIterator();
            while ( it.hasNext() ) {
                final LlmCall c = it.next();
                if ( c.status() == CallStatus.IN_FLIGHT ) {
                    inFlight++;
                }
                if ( subsystemFilter != null && c.subsystem() != subsystemFilter ) {
                    continue;
                }
                if ( statusFilter != null && c.status() != statusFilter ) {
                    continue;
                }
                if ( out.size() < limit ) {
                    out.add( c.toView() );
                }
            }
            return new Snapshot( out, inFlight, enabled, windowMinutes, maxRecords );
        }
    }

    private long durationMs( final LlmCall call ) {
        return Math.max( 0L, ( System.nanoTime() - call.startedNanos() ) / 1_000_000L );
    }

    private String truncate( final String s ) {
        if ( s == null ) {
            return null;
        }
        return s.length() <= payloadChars ? s : s.substring( 0, payloadChars ) + "…";
    }

    /** Caller must hold {@code lock}. Evicts only finalized records; in-flight is never dropped. */
    private void prune() {
        final long cutoff = System.currentTimeMillis() - windowMillis;
        final Iterator< LlmCall > it = buffer.iterator(); // head = oldest
        while ( it.hasNext() ) {
            final LlmCall c = it.next();
            if ( c.status() == CallStatus.IN_FLIGHT ) {
                continue;
            }
            final boolean tooOld = c.startedAt().toEpochMilli() < cutoff;
            final boolean overCap = buffer.size() > maxRecords;
            if ( tooOld || overCap ) {
                it.remove();
            } else {
                break;
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=LlmActivityLogTest -q`
Expected: PASS — 9 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/llm/activity/LlmActivityLog.java \
        wikantik-main/src/test/java/com/wikantik/llm/activity/LlmActivityLogTest.java
git commit -m "feat(llm-activity): in-memory ring buffer for LLM calls"
```

---

## Task 3: `LlmActivityLogHolder`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/LlmActivityLogHolder.java`

No dedicated test — exercised by the servlet test (Task 8) and wiring.

- [ ] **Step 1: Implement the holder**

`LlmActivityLogHolder.java` (after the license header):
```java
package com.wikantik.llm.activity;

import java.util.Properties;
import com.wikantik.util.TextUtil;

/**
 * Process-wide handle for the single {@link LlmActivityLog}. Mirrors the
 * {@code MeterRegistryHolder} pattern: the log is a cross-cutting observability
 * singleton reached both by the recording decorators (during subsystem wiring)
 * and by the admin servlet. Not persisted.
 */
public final class LlmActivityLogHolder {

    /** Returned before any log is installed, so callers never see {@code null}. */
    private static final LlmActivityLog DISABLED = new LlmActivityLog( false, 60, 1, 500 );

    private static volatile LlmActivityLog instance;

    private LlmActivityLogHolder() {}

    /**
     * Returns the installed log, creating it from {@code props} on first call.
     * Idempotent — later calls return the same instance and ignore {@code props}.
     */
    public static synchronized LlmActivityLog getOrCreate( final Properties props ) {
        if ( instance == null ) {
            final boolean enabled = TextUtil.getBooleanProperty(
                props, "wikantik.llm_activity.enabled", true );
            final int window = TextUtil.getIntegerProperty(
                props, "wikantik.llm_activity.window_minutes", 60 );
            final int maxRecords = TextUtil.getIntegerProperty(
                props, "wikantik.llm_activity.max_records", 5000 );
            final int payloadChars = TextUtil.getIntegerProperty(
                props, "wikantik.llm_activity.payload_chars", 500 );
            instance = new LlmActivityLog( enabled, window, maxRecords, payloadChars );
        }
        return instance;
    }

    /** The installed log, or a permanently-disabled fallback if none is installed yet. */
    public static LlmActivityLog get() {
        final LlmActivityLog i = instance;
        return i != null ? i : DISABLED;
    }

    /** Test-only: install a specific log (or {@code null} to reset). */
    static void setForTesting( final LlmActivityLog log ) {
        instance = log;
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS. If `TextUtil.getIntegerProperty` does not resolve, check the exact name in `com.wikantik.util.TextUtil` and use the integer-with-default accessor it provides.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/llm/activity/LlmActivityLogHolder.java
git commit -m "feat(llm-activity): static holder for the activity log"
```

---

## Task 4: `RecordingEntityExtractor` decorator

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/RecordingEntityExtractor.java`
- Test: `wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingEntityExtractorTest.java`

Reference signatures (verbatim from the codebase):
- `com.wikantik.api.knowledge.EntityExtractor` — `String code();` and `ExtractionResult extract(ExtractionChunk chunk, ExtractionContext context);`
- `com.wikantik.api.knowledge.ExtractionChunk` — record with `UUID id, String pageName, int chunkIndex, List<String> headingPath, String text`.
- `com.wikantik.api.knowledge.ExtractionResult` — record with `List<ProposedNode> nodes, List<ProposedEdge> edges, List<ExtractedMention> mentions, String extractorCode, Duration latency`.

- [ ] **Step 1: Write the failing test**

`RecordingEntityExtractorTest.java` (after the license header):
```java
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingEntityExtractorTest {

    private static ExtractionChunk chunk() {
        return new ExtractionChunk( UUID.randomUUID(), "PageA", 0, List.of(), "the chunk text" );
    }

    @Test
    void recordsOkOnSuccessfulExtraction() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final ExtractionResult result = ExtractionResult.empty( "ollama", Duration.ofMillis( 5 ) );
        final EntityExtractor delegate = new EntityExtractor() {
            public String code() { return "ollama"; }
            public ExtractionResult extract( final ExtractionChunk c, final ExtractionContext ctx ) {
                return result;
            }
        };
        final RecordingEntityExtractor rec =
            new RecordingEntityExtractor( delegate, log, "ollama", "gemma4-assist" );

        assertSame( result, rec.extract( chunk(), null ) );

        final LlmActivityLog.Snapshot snap = log.snapshot( 10, null, null );
        assertEquals( 1, snap.calls().size() );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( "ENTITY_EXTRACTION", snap.calls().get( 0 ).subsystem() );
        assertEquals( "gemma4-assist", snap.calls().get( 0 ).model() );
    }

    @Test
    void recordsErrorAndRethrowsWhenDelegateThrows() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final EntityExtractor delegate = new EntityExtractor() {
            public String code() { return "ollama"; }
            public ExtractionResult extract( final ExtractionChunk c, final ExtractionContext ctx ) {
                throw new IllegalStateException( "contract violation" );
            }
        };
        final RecordingEntityExtractor rec =
            new RecordingEntityExtractor( delegate, log, "ollama", "gemma4-assist" );

        assertThrows( IllegalStateException.class, () -> rec.extract( chunk(), null ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void delegatesCode() {
        final EntityExtractor delegate = new EntityExtractor() {
            public String code() { return "claude"; }
            public ExtractionResult extract( final ExtractionChunk c, final ExtractionContext ctx ) {
                return ExtractionResult.empty( "claude", Duration.ZERO );
            }
        };
        final RecordingEntityExtractor rec = new RecordingEntityExtractor(
            delegate, new LlmActivityLog( true, 60, 100, 500 ), "claude", "claude-x" );
        assertEquals( "claude", rec.code() );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=RecordingEntityExtractorTest -q`
Expected: FAIL — `RecordingEntityExtractor` does not exist.

- [ ] **Step 3: Implement the decorator**

`RecordingEntityExtractor.java` (after the license header):
```java
package com.wikantik.llm.activity;

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;

/**
 * {@link EntityExtractor} decorator that records each extraction call into an
 * {@link LlmActivityLog}. {@code extract} is contractually "must never throw", so
 * in practice every call records OK; the catch clause only fires on a
 * contract-violating {@link RuntimeException}, which is still rethrown.
 */
public final class RecordingEntityExtractor implements EntityExtractor {

    private final EntityExtractor delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    public RecordingEntityExtractor( final EntityExtractor delegate, final LlmActivityLog log,
                                     final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public String code() {
        return delegate.code();
    }

    @Override
    public ExtractionResult extract( final ExtractionChunk chunk, final ExtractionContext context ) {
        final String prompt = chunk == null ? "" : chunk.text();
        final LlmCall call = log.begin( Subsystem.ENTITY_EXTRACTION, backend, model, "chat", prompt );
        try {
            final ExtractionResult result = delegate.extract( chunk, context );
            log.succeed( call, summarise( result ) );
            return result;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    private static String summarise( final ExtractionResult r ) {
        if ( r == null ) {
            return "null result";
        }
        return "nodes=" + r.nodes().size()
             + " edges=" + r.edges().size()
             + " mentions=" + r.mentions().size();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=RecordingEntityExtractorTest -q`
Expected: PASS — 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/llm/activity/RecordingEntityExtractor.java \
        wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingEntityExtractorTest.java
git commit -m "feat(llm-activity): recording decorator for EntityExtractor"
```

---

## Task 5: `RecordingKgProposalJudgeService` decorator

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/RecordingKgProposalJudgeService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingKgProposalJudgeServiceTest.java`

Reference signatures (verbatim):
- `com.wikantik.api.knowledge.KgProposalJudgeService` — `JudgeVerdict judge(KgProposal proposal);`
- `com.wikantik.api.knowledge.JudgeVerdict` — record `String verdict, double confidence, String rationale, String model`; constants `APPROVED`/`REJECTED`/`ABSTAIN`.
- `com.wikantik.api.knowledge.KgProposal` — record whose first components are `UUID id, String proposalType, String sourcePage, Map<String,Object> proposedData, double confidence, String reasoning, ...`.

- [ ] **Step 1: Write the failing test**

`RecordingKgProposalJudgeServiceTest.java` (after the license header):
```java
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingKgProposalJudgeServiceTest {

    private static KgProposal proposal() {
        return new KgProposal( UUID.randomUUID(), "NEW_NODE", "PageA",
            Map.of( "name", "Docker" ), 0.8, "reasoning",
            "pending", null, null, null, "none", null, null, null, null );
    }

    @Test
    void recordsOkOnVerdict() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final JudgeVerdict verdict = new JudgeVerdict( JudgeVerdict.APPROVED, 0.9, "looks good", "gemma" );
        final KgProposalJudgeService delegate = p -> verdict;
        final RecordingKgProposalJudgeService rec =
            new RecordingKgProposalJudgeService( delegate, log, "ollama", "gemma" );

        assertSame( verdict, rec.judge( proposal() ) );
        assertEquals( "OK", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
        assertEquals( "PROPOSAL_JUDGE", log.snapshot( 10, null, null ).calls().get( 0 ).subsystem() );
    }

    @Test
    void recordsErrorAndRethrowsWhenDelegateThrows() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final KgProposalJudgeService delegate = p -> { throw new RuntimeException( "judge down" ); };
        final RecordingKgProposalJudgeService rec =
            new RecordingKgProposalJudgeService( delegate, log, "ollama", "gemma" );

        assertThrows( RuntimeException.class, () -> rec.judge( proposal() ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }
}
```

Note: if the 15-arg `KgProposal` canonical constructor in the test does not match the current record (component count/order drift), construct it to match the actual record header in `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgProposal.java` — the only fields the decorator reads are `id` and `proposedData`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=RecordingKgProposalJudgeServiceTest -q`
Expected: FAIL — `RecordingKgProposalJudgeService` does not exist.

- [ ] **Step 3: Implement the decorator**

`RecordingKgProposalJudgeService.java` (after the license header):
```java
package com.wikantik.llm.activity;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;

/**
 * {@link KgProposalJudgeService} decorator that records each judge call into an
 * {@link LlmActivityLog}. The underlying judge throws on backend failure, so failed
 * calls are recorded as ERROR; transient-unavailable verdicts return normally and
 * are recorded OK (the rationale text surfaces in the response preview).
 */
public final class RecordingKgProposalJudgeService implements KgProposalJudgeService {

    private final KgProposalJudgeService delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    public RecordingKgProposalJudgeService( final KgProposalJudgeService delegate,
                                            final LlmActivityLog log,
                                            final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public JudgeVerdict judge( final KgProposal proposal ) {
        final LlmCall call = log.begin( Subsystem.PROPOSAL_JUDGE, backend, model, "chat",
                                        describe( proposal ) );
        try {
            final JudgeVerdict verdict = delegate.judge( proposal );
            log.succeed( call, summarise( verdict ) );
            return verdict;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    private static String describe( final KgProposal p ) {
        if ( p == null ) {
            return "null proposal";
        }
        return p.proposalType() + " " + p.id() + " " + p.proposedData();
    }

    private static String summarise( final JudgeVerdict v ) {
        if ( v == null ) {
            return "null verdict";
        }
        return "verdict=" + v.verdict() + " confidence=" + v.confidence()
             + " — " + v.rationale();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=RecordingKgProposalJudgeServiceTest -q`
Expected: PASS — 2 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/llm/activity/RecordingKgProposalJudgeService.java \
        wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingKgProposalJudgeServiceTest.java
git commit -m "feat(llm-activity): recording decorator for KgProposalJudgeService"
```

---

## Task 6: `RecordingEmbeddingClient` decorator

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/llm/activity/RecordingEmbeddingClient.java`
- Test: `wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingEmbeddingClientTest.java`

Reference signatures (verbatim) — `com.wikantik.search.embedding.TextEmbeddingClient`:
- `List<float[]> embed(List<String> texts, EmbeddingKind kind);`
- `default CompletableFuture<List<float[]>> embedAsync(List<String> texts, EmbeddingKind kind);`
- `int dimension();`
- `String modelName();`

- [ ] **Step 1: Write the failing test**

`RecordingEmbeddingClientTest.java` (after the license header):
```java
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class RecordingEmbeddingClientTest {

    private static TextEmbeddingClient delegate( final boolean fail ) {
        return new TextEmbeddingClient() {
            public List< float[] > embed( final List< String > texts, final EmbeddingKind kind ) {
                if ( fail ) {
                    throw new RuntimeException( "embed backend down" );
                }
                return List.of( new float[] { 1f }, new float[] { 2f } );
            }
            public int dimension() { return 1; }
            public String modelName() { return "nomic-embed"; }
        };
    }

    @Test
    void recordsOkOnSyncEmbed() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( false ), log, "ollama", "nomic-embed" );

        assertEquals( 2, rec.embed( List.of( "a", "b" ), EmbeddingKind.QUERY ).size() );
        final LlmActivityLog.Snapshot snap = log.snapshot( 10, null, null );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( "EMBEDDING", snap.calls().get( 0 ).subsystem() );
    }

    @Test
    void recordsErrorAndRethrowsOnSyncEmbedFailure() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( true ), log, "ollama", "nomic-embed" );

        assertThrows( RuntimeException.class,
                      () -> rec.embed( List.of( "a" ), EmbeddingKind.QUERY ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void recordsOkOnAsyncEmbed() throws Exception {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( false ), log, "ollama", "nomic-embed" );

        rec.embedAsync( List.of( "a", "b" ), EmbeddingKind.QUERY ).get();
        assertEquals( "OK", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void recordsErrorOnAsyncEmbedFailure() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final RecordingEmbeddingClient rec =
            new RecordingEmbeddingClient( delegate( true ), log, "ollama", "nomic-embed" );

        final CompletableFuture< List< float[] > > f =
            rec.embedAsync( List.of( "a" ), EmbeddingKind.QUERY );
        assertThrows( ExecutionException.class, f::get );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }

    @Test
    void delegatesDimensionAndModelName() {
        final RecordingEmbeddingClient rec = new RecordingEmbeddingClient(
            delegate( false ), new LlmActivityLog( true, 60, 100, 500 ), "ollama", "nomic-embed" );
        assertEquals( 1, rec.dimension() );
        assertTrue( rec.modelName().contains( "nomic" ) );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=RecordingEmbeddingClientTest -q`
Expected: FAIL — `RecordingEmbeddingClient` does not exist.

- [ ] **Step 3: Implement the decorator**

`RecordingEmbeddingClient.java` (after the license header):
```java
package com.wikantik.llm.activity;

import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.TextEmbeddingClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link TextEmbeddingClient} decorator that records each embedding call into an
 * {@link LlmActivityLog}. Both the synchronous {@code embed} path and the native
 * async {@code embedAsync} path are recorded; the async path finalizes its record
 * when the returned future completes.
 */
public final class RecordingEmbeddingClient implements TextEmbeddingClient {

    private final TextEmbeddingClient delegate;
    private final LlmActivityLog log;
    private final String backend;
    private final String model;

    public RecordingEmbeddingClient( final TextEmbeddingClient delegate, final LlmActivityLog log,
                                     final String backend, final String model ) {
        this.delegate = delegate;
        this.log = log;
        this.backend = backend;
        this.model = model;
    }

    @Override
    public List< float[] > embed( final List< String > texts, final EmbeddingKind kind ) {
        final LlmCall call = log.begin( Subsystem.EMBEDDING, backend, model, "embed",
                                        size( texts ) + " texts" );
        try {
            final List< float[] > result = delegate.embed( texts, kind );
            log.succeed( call, size( result ) + " vectors" );
            return result;
        } catch ( final RuntimeException e ) {
            log.fail( call, e );
            throw e;
        }
    }

    @Override
    public CompletableFuture< List< float[] > > embedAsync( final List< String > texts,
                                                            final EmbeddingKind kind ) {
        final LlmCall call = log.begin( Subsystem.EMBEDDING, backend, model, "embed",
                                        size( texts ) + " texts" );
        return delegate.embedAsync( texts, kind ).whenComplete( ( result, error ) -> {
            if ( error != null ) {
                log.fail( call, error );
            } else {
                log.succeed( call, size( result ) + " vectors" );
            }
        } );
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    private static int size( final List< ? > list ) {
        return list == null ? 0 : list.size();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=RecordingEmbeddingClientTest -q`
Expected: PASS — 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/llm/activity/RecordingEmbeddingClient.java \
        wikantik-main/src/test/java/com/wikantik/llm/activity/RecordingEmbeddingClientTest.java
git commit -m "feat(llm-activity): recording decorator for TextEmbeddingClient"
```

---

## Task 7: Wire decorators into subsystem construction + config defaults

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeWiringHelper.java` (~lines 220-227)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java` (~line 134)
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java` (~lines 111-121)

This task has no new unit test — it is verified by the module build and the final integration build. Wiring code is glue between already-tested units.

- [ ] **Step 1: Add config defaults to `wikantik.properties`**

Append to `wikantik-main/src/main/resources/ini/wikantik.properties`:
```properties

# --- LLM activity view -------------------------------------------------------
# In-memory record of recent LLM calls, surfaced at /admin/llm-activity.
# Not persisted; state is lost on restart.
wikantik.llm_activity.enabled = true
wikantik.llm_activity.window_minutes = 60
wikantik.llm_activity.max_records = 5000
wikantik.llm_activity.payload_chars = 500
```

- [ ] **Step 2: Wrap the entity extractor in `KnowledgeWiringHelper`**

Find this block (around line 225):
```java
        final Optional< com.wikantik.api.knowledge.EntityExtractor > extractorOpt =
            EntityExtractorFactory.create( extractorCfg );
        if ( extractorOpt.isEmpty() ) {
            LOG.warn( "Entity extraction configured ({}), but no usable backend; skipping wiring",
                      extractorCfg.backend() );
            return;
        }
```

Replace it with:
```java
        Optional< com.wikantik.api.knowledge.EntityExtractor > extractorOpt =
            EntityExtractorFactory.create( extractorCfg );
        if ( extractorOpt.isEmpty() ) {
            LOG.warn( "Entity extraction configured ({}), but no usable backend; skipping wiring",
                      extractorCfg.backend() );
            return;
        }
        final com.wikantik.llm.activity.LlmActivityLog activityLog =
            com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
        if ( activityLog.enabled() ) {
            final String exBackend = extractorCfg.backend();
            final String exModel = EntityExtractorConfig.BACKEND_CLAUDE.equalsIgnoreCase( exBackend )
                ? extractorCfg.claudeModel() : extractorCfg.ollamaModel();
            extractorOpt = extractorOpt.map( e ->
                new com.wikantik.llm.activity.RecordingEntityExtractor( e, activityLog, exBackend, exModel ) );
            LOG.info( "LLM activity recording enabled for entity extraction" );
        }
```

Note: the original `extractorOpt` is declared `final`; the replacement drops `final` so it can be reassigned via `map`. Verify `props` is in scope in this method (it is — `EntityExtractorConfig.fromProperties( props )` is called a few lines above).

- [ ] **Step 3: Wrap the judge in `KnowledgeSubsystemFactory`**

Find this line (around line 134):
```java
            kgJudge = new DefaultKgProposalJudgeService( http, judgeCfg, judgeTimeoutRepo );
```

Replace it with:
```java
            final com.wikantik.api.knowledge.KgProposalJudgeService rawJudge =
                new DefaultKgProposalJudgeService( http, judgeCfg, judgeTimeoutRepo );
            final com.wikantik.llm.activity.LlmActivityLog judgeActivityLog =
                com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
            kgJudge = judgeActivityLog.enabled()
                ? new com.wikantik.llm.activity.RecordingKgProposalJudgeService(
                      rawJudge, judgeActivityLog, "ollama", judgeCfg.model() )
                : rawJudge;
```

Verify `props` is in scope (it is — `KgJudgeConfig.fromProperties( props )` is called in the same method). The local `kgJudge` is declared as `KgProposalJudgeService` further up; the decorator implements that interface so the assignment type-checks.

- [ ] **Step 4: Wrap the embedding client in `SearchWiringHelper`**

Find this block (around line 111):
```java
        final Optional< TextEmbeddingClient > clientOpt = EmbeddingClientFactory.create( cfg );
        if ( clientOpt.isEmpty() ) {
            // Master flag off — nothing to wire.
            return;
        }
        final TextEmbeddingClient client = clientOpt.get();
        final String modelCode = cfg.model().code();
```

Replace it with:
```java
        final Optional< TextEmbeddingClient > clientOpt = EmbeddingClientFactory.create( cfg );
        if ( clientOpt.isEmpty() ) {
            // Master flag off — nothing to wire.
            return;
        }
        final String modelCode = cfg.model().code();
        final com.wikantik.llm.activity.LlmActivityLog embedActivityLog =
            com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
        final TextEmbeddingClient client = embedActivityLog.enabled()
            ? new com.wikantik.llm.activity.RecordingEmbeddingClient(
                  clientOpt.get(), embedActivityLog, cfg.backend(), modelCode )
            : clientOpt.get();
```

Verify `props` is in scope (it is — `EmbeddingConfig.fromProperties( props )` is called a few lines above).

- [ ] **Step 5: Compile-check the module**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS. If a `props` variable is not in scope in one of the three methods, locate the `Properties` parameter actually passed to that method and use its name.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties \
        wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java \
        wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java
git commit -m "feat(llm-activity): install recording decorators during subsystem wiring"
```

---

## Task 8: `AdminLlmActivityResource` servlet

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminLlmActivityResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminLlmActivityResourceTest.java`

The servlet mirrors `AdminRetrievalQualityResource`: extends `RestServletBase`, builds the JSON response with `JsonObject` (the static `GSON` is inherited from `RestServletBase`). It is read-only — `GET` only.

- [ ] **Step 1: Write the failing test**

`AdminLlmActivityResourceTest.java` (after the license header):
```java
package com.wikantik.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.llm.activity.LlmActivityLog;
import com.wikantik.llm.activity.LlmActivityLogHolder;
import com.wikantik.llm.activity.Subsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdminLlmActivityResourceTest {

    @AfterEach
    void reset() throws Exception {
        // Reset the holder so tests do not leak state into each other.
        final var m = LlmActivityLogHolder.class.getDeclaredMethod( "setForTesting", LlmActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, ( LlmActivityLog ) null );
    }

    private static void installHolder( final LlmActivityLog log ) throws Exception {
        final var m = LlmActivityLogHolder.class.getDeclaredMethod( "setForTesting", LlmActivityLog.class );
        m.setAccessible( true );
        m.invoke( null, log );
    }

    private static JsonObject doGet( final String limit ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "limit" ) ).thenReturn( limit );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        new AdminLlmActivityResource().doGetForTesting( req, resp );
        return JsonParser.parseString( sw.toString() ).getAsJsonObject();
    }

    @Test
    void returnsSnapshotEnvelopeWithRecordedCalls() throws Exception {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "x" ), "ok" );
        installHolder( log );

        final JsonObject body = doGet( null );
        final JsonObject data = body.getAsJsonObject( "data" );
        assertTrue( data.get( "enabled" ).getAsBoolean() );
        assertEquals( 1, data.getAsJsonArray( "calls" ).size() );
        assertEquals( 0, data.get( "inFlight" ).getAsInt() );
        assertEquals( 60, data.get( "windowMinutes" ).getAsInt() );
    }

    @Test
    void reportsDisabledWhenLogDisabled() throws Exception {
        installHolder( new LlmActivityLog( false, 60, 100, 500 ) );
        final JsonObject data = doGet( null ).getAsJsonObject( "data" );
        assertEquals( false, data.get( "enabled" ).getAsBoolean() );
    }

    @Test
    void limitParameterCapsResultCount() throws Exception {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        for ( int i = 0; i < 5; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i ), "ok" );
        }
        installHolder( log );
        assertEquals( 2, doGet( "2" ).getAsJsonObject( "data" ).getAsJsonArray( "calls" ).size() );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=AdminLlmActivityResourceTest -q`
Expected: FAIL — `AdminLlmActivityResource` does not exist.

- [ ] **Step 3: Implement the servlet**

`AdminLlmActivityResource.java` (after the license header):
```java
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.llm.activity.CallStatus;
import com.wikantik.llm.activity.LlmActivityLog;
import com.wikantik.llm.activity.LlmActivityLogHolder;
import com.wikantik.llm.activity.LlmCallView;
import com.wikantik.llm.activity.Subsystem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * {@code GET /admin/llm-activity} — read-only snapshot of recent and in-flight LLM
 * calls (entity extraction, proposal judging, embeddings). Backed by the in-memory
 * {@link LlmActivityLog}; nothing is persisted. Auth is via the shared
 * {@code AdminAuthFilter}.
 *
 * <p>Query parameters: {@code limit} (default 200), {@code subsystem}
 * (entity_extraction|proposal_judge|embedding), {@code status} (in_flight|ok|error).</p>
 */
public class AdminLlmActivityResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_LIMIT = 200;

    /** Test seam — {@code doGet} is protected on the servlet base. */
    void doGetForTesting( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        doGet( req, resp );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final LlmActivityLog log = LlmActivityLogHolder.get();

        int limit = DEFAULT_LIMIT;
        final String limitRaw = req.getParameter( "limit" );
        if ( limitRaw != null && !limitRaw.isBlank() ) {
            try {
                limit = Integer.parseInt( limitRaw.trim() );
            } catch ( final NumberFormatException e ) {
                limit = DEFAULT_LIMIT;
            }
        }
        limit = Math.max( 1, Math.min( limit, log.maxRecords() ) );

        final Subsystem subsystem = parseSubsystem( req.getParameter( "subsystem" ) );
        final CallStatus status = parseStatus( req.getParameter( "status" ) );

        final LlmActivityLog.Snapshot snap = log.snapshot( limit, subsystem, status );

        final JsonArray calls = new JsonArray();
        for ( final LlmCallView c : snap.calls() ) {
            calls.add( callToJson( c ) );
        }
        final JsonObject data = new JsonObject();
        data.add( "calls", calls );
        data.addProperty( "inFlight", snap.inFlight() );
        data.addProperty( "enabled", snap.enabled() );
        data.addProperty( "windowMinutes", snap.windowMinutes() );
        data.addProperty( "capacity", snap.maxRecords() );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    private static JsonObject callToJson( final LlmCallView c ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "seq",             c.seq() );
        o.addProperty( "startedAt",       c.startedAt() );
        o.addProperty( "subsystem",       c.subsystem() );
        o.addProperty( "backend",         c.backend() );
        o.addProperty( "model",           c.model() );
        o.addProperty( "operation",       c.operation() );
        o.addProperty( "status",          c.status() );
        o.addProperty( "durationMs",      c.durationMs() );
        o.addProperty( "promptPreview",   c.promptPreview() );
        o.addProperty( "responsePreview", c.responsePreview() );
        o.add( "inputTokens",  c.inputTokens()  == null ? JsonNull.INSTANCE
                                                        : json( c.inputTokens() ) );
        o.add( "outputTokens", c.outputTokens() == null ? JsonNull.INSTANCE
                                                        : json( c.outputTokens() ) );
        o.addProperty( "errorMessage",    c.errorMessage() );
        return o;
    }

    private static com.google.gson.JsonPrimitive json( final Integer i ) {
        return new com.google.gson.JsonPrimitive( i );
    }

    private static Subsystem parseSubsystem( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        try {
            return Subsystem.valueOf( raw.trim().toUpperCase( java.util.Locale.ROOT ) );
        } catch ( final IllegalArgumentException e ) {
            return null;
        }
    }

    private static CallStatus parseStatus( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        try {
            return CallStatus.valueOf( raw.trim().toUpperCase( java.util.Locale.ROOT ) );
        } catch ( final IllegalArgumentException e ) {
            return null;
        }
    }
}
```

Note: `GSON` is the inherited static from `RestServletBase` (used the same way by `AdminRetrievalQualityResource`). If `RestServletBase` does not expose `GSON` with that visibility, build the response string with `envelope.toString()` instead.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=AdminLlmActivityResourceTest -q`
Expected: PASS — 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminLlmActivityResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminLlmActivityResourceTest.java
git commit -m "feat(llm-activity): GET /admin/llm-activity snapshot endpoint"
```

---

## Task 9: Register the servlet in `web.xml`

**Files:**
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Add the servlet definition**

In `web.xml`, next to the other `Admin*Resource` `<servlet>` definitions, add:
```xml
   <servlet>
       <servlet-name>AdminLlmActivityResource</servlet-name>
       <servlet-class>com.wikantik.rest.AdminLlmActivityResource</servlet-class>
   </servlet>
```

- [ ] **Step 2: Add the servlet mapping**

Next to the other `/admin/*` `<servlet-mapping>` blocks (e.g. near `AdminRetrievalQualityResource`), add:
```xml
   <servlet-mapping>
       <servlet-name>AdminLlmActivityResource</servlet-name>
       <url-pattern>/admin/llm-activity</url-pattern>
   </servlet-mapping>
```

Only the exact path is needed — the servlet takes no sub-paths. The existing `<filter-mapping>` for `AdminAuthFilter` on `/admin/*` already covers it; no filter change is required.

- [ ] **Step 3: Verify the WAR builds**

Run: `mvn compile -pl wikantik-war -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(llm-activity): map /admin/llm-activity servlet"
```

---

## Task 10: Add `getLlmActivity` to the API client

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add the client method**

In `client.js`, inside the `knowledge: { ... }` object, next to `getExtractionStatus`, add:
```javascript
    getLlmActivity: ({ limit = 200, subsystem, status } = {}) => {
      const params = new URLSearchParams({ limit });
      if (subsystem) params.set('subsystem', subsystem);
      if (status) params.set('status', status);
      return request(`/admin/llm-activity?${params}`);
    },
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat(llm-activity): getLlmActivity API client method"
```

---

## Task 11: `LlmActivityTab` React component

**Files:**
- Create: `wikantik-frontend/src/components/admin/LlmActivityTab.jsx`
- Test: `wikantik-frontend/src/components/admin/LlmActivityTab.test.jsx`

- [ ] **Step 1: Write the failing test**

`LlmActivityTab.test.jsx`:
```javascript
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import LlmActivityTab from './LlmActivityTab';

vi.mock('../../api/client', () => ({
  api: { knowledge: { getLlmActivity: vi.fn() } },
}));
import { api } from '../../api/client';

const call = (overrides = {}) => ({
  seq: 1,
  startedAt: '2026-05-15T14:32:07Z',
  subsystem: 'ENTITY_EXTRACTION',
  backend: 'ollama',
  model: 'gemma4-assist',
  operation: 'chat',
  status: 'OK',
  durationMs: 1240,
  promptPreview: 'the chunk text',
  responsePreview: 'nodes=12 edges=5 mentions=30',
  inputTokens: null,
  outputTokens: null,
  errorMessage: null,
  ...overrides,
});

const payload = (calls, extra = {}) => ({
  data: { calls, inFlight: 0, enabled: true, windowMinutes: 60, capacity: 5000, ...extra },
});

describe('LlmActivityTab', () => {
  beforeEach(() => {
    api.knowledge.getLlmActivity.mockReset();
  });

  it('renders a row per recorded call', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([call(), call({ seq: 2, model: 'nomic' })]));
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText('gemma4-assist')).toBeInTheDocument());
    expect(screen.getByText('nomic')).toBeInTheDocument();
  });

  it('shows an error row distinctly', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([call({ status: 'ERROR', errorMessage: 'timeout after 30s', responsePreview: null })]),
    );
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText(/timeout after 30s/)).toBeInTheDocument());
  });

  it('shows a disabled notice when the log is disabled', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([], { enabled: false }));
    render(<LlmActivityTab />);
    await waitFor(() =>
      expect(screen.getByText(/wikantik\.llm_activity\.enabled/)).toBeInTheDocument(),
    );
  });

  it('shows an empty state when there are no calls', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(payload([]));
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText(/No LLM calls/i)).toBeInTheDocument());
  });

  it('summarises in-flight and error counts in the header', async () => {
    api.knowledge.getLlmActivity.mockResolvedValue(
      payload([call(), call({ seq: 2, status: 'ERROR', errorMessage: 'x' })], { inFlight: 3 }),
    );
    render(<LlmActivityTab />);
    await waitFor(() => expect(screen.getByText(/3 in-flight/)).toBeInTheDocument());
    expect(screen.getByText(/1 error/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/LlmActivityTab.test.jsx`
Expected: FAIL — cannot resolve `./LlmActivityTab`.

- [ ] **Step 3: Implement the component**

`LlmActivityTab.jsx`:
```javascript
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/client';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

const SUBSYSTEM_LABELS = {
  ENTITY_EXTRACTION: 'extraction',
  PROPOSAL_JUDGE: 'judge',
  EMBEDDING: 'embedding',
};

function fmtTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString();
}

function fmtDuration(ms, status) {
  if (status === 'IN_FLIGHT') return '…';
  if (ms == null || ms < 0) return '—';
  return `${ms.toLocaleString()}ms`;
}

export default function LlmActivityTab() {
  const [snapshot, setSnapshot] = useState(null);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null);
  const pollRef = useRef(null);
  const activeRef = useRef(false);

  const fetchActivity = async () => {
    try {
      const res = await api.knowledge.getLlmActivity({ limit: 200 });
      const data = res?.data || {};
      setSnapshot(data);
      setError(null);
      activeRef.current = (data.inFlight || 0) > 0;
    } catch (e) {
      setError(e.message || 'Failed to load LLM activity');
    }
  };

  useEffect(() => {
    let cancelled = false;
    fetchActivity();
    const tick = async () => {
      if (cancelled) return;
      await fetchActivity();
      if (cancelled) return;
      clearInterval(pollRef.current);
      pollRef.current = setInterval(tick, activeRef.current ? FAST_POLL_MS : SLOW_POLL_MS);
    };
    pollRef.current = setInterval(tick, FAST_POLL_MS);
    return () => {
      cancelled = true;
      if (pollRef.current) clearInterval(pollRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) return <div className="admin-error">{error}</div>;
  if (!snapshot) return <div className="admin-loading">Loading LLM activity…</div>;

  if (!snapshot.enabled) {
    return (
      <div className="admin-notice">
        LLM activity recording is disabled. Set{' '}
        <code>wikantik.llm_activity.enabled = true</code> to enable it.
      </div>
    );
  }

  const calls = snapshot.calls || [];
  const errorCount = calls.filter((c) => c.status === 'ERROR').length;

  return (
    <div>
      <div className="admin-subhead" style={{ marginBottom: 'var(--space-md)' }}>
        <strong>{snapshot.inFlight || 0} in-flight</strong>
        {' · '}
        {calls.length} calls in the last {snapshot.windowMinutes} min
        {' · '}
        {errorCount} error{errorCount === 1 ? '' : 's'}
      </div>

      {calls.length === 0 ? (
        <div className="admin-empty">No LLM calls recorded in the current window.</div>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Subsystem</th>
              <th>Model</th>
              <th>Op</th>
              <th>Duration</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {calls.map((c) => (
              <ActivityRow
                key={c.seq}
                call={c}
                expanded={expanded === c.seq}
                onToggle={() => setExpanded(expanded === c.seq ? null : c.seq)}
              />
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ActivityRow({ call, expanded, onToggle }) {
  const rowClass =
    call.status === 'ERROR'
      ? 'row-error'
      : call.status === 'IN_FLIGHT'
        ? 'row-inflight'
        : '';
  return (
    <>
      <tr className={rowClass} onClick={onToggle} style={{ cursor: 'pointer' }}>
        <td>{fmtTime(call.startedAt)}</td>
        <td>{SUBSYSTEM_LABELS[call.subsystem] || call.subsystem}</td>
        <td>{call.model}</td>
        <td>{call.operation}</td>
        <td>{fmtDuration(call.durationMs, call.status)}</td>
        <td>{call.status}</td>
      </tr>
      {expanded && (
        <tr className="row-detail">
          <td colSpan={6}>
            <div>
              <strong>Prompt:</strong> {call.promptPreview || '—'}
            </div>
            <div>
              <strong>Response:</strong> {call.responsePreview || '—'}
            </div>
            {call.errorMessage && (
              <div>
                <strong>Error:</strong> {call.errorMessage}
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/LlmActivityTab.test.jsx`
Expected: PASS — 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/LlmActivityTab.jsx \
        wikantik-frontend/src/components/admin/LlmActivityTab.test.jsx
git commit -m "feat(llm-activity): LlmActivityTab component"
```

---

## Task 12: Wire the tab into `AdminKnowledgePage`

**Files:**
- Modify: `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`

- [ ] **Step 1: Import the component**

At the top of `AdminKnowledgePage.jsx`, with the other tab-component imports, add:
```javascript
import LlmActivityTab from './LlmActivityTab';
```

- [ ] **Step 2: Add the tab to the `TABS` array**

Append this entry to the end of the `TABS` array (after the `hub-discovery` entry):
```javascript
  { id: 'llm-activity', label: 'LLM Activity',
    description: 'Live view of recent and in-flight LLM calls — entity extraction, proposal judging, and embeddings — for roughly the last hour. Held in memory only; cleared on restart.' },
```

- [ ] **Step 3: Add the conditional render**

Next to the other `{activeTab === '...' && <...Tab />}` lines, add:
```javascript
{activeTab === 'llm-activity' && <LlmActivityTab />}
```

- [ ] **Step 4: Run the existing admin-page tests to confirm nothing broke**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/`
Expected: PASS — all admin component tests green, including the new `LlmActivityTab.test.jsx`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx
git commit -m "feat(llm-activity): add LLM Activity tab to the admin Knowledge page"
```

---

## Task 13: Full verification build

**Files:** none — verification only.

- [ ] **Step 1: Run the full unit-test build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS across all modules, including the React build (`npm install` + `vite build`) inside `wikantik-war`.

- [ ] **Step 2: Run the integration-test reactor**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. (Per repo convention — no `-T` flag for integration tests; `-fae` so every IT submodule runs.)

- [ ] **Step 3: Manual smoke test (optional but recommended)**

```bash
mvn clean install -Dmaven.test.skip -T 1C
bin/redeploy.sh
```
Then log in as the test admin (`cat test.properties`), open the admin Knowledge page, select the **LLM Activity** tab, and confirm: the table renders, a save to any wiki page produces extraction/embedding rows within ~10 s, and an in-flight row shows briefly during a call.

- [ ] **Step 4: Final commit (only if Steps 1-2 surfaced fixes)**

```bash
git add <files>
git commit -m "fix(llm-activity): address verification-build findings"
```

---

## Self-review

**Spec coverage:** Live polled view (Tasks 11-12); per-call record with all spec fields (Task 1); `LlmActivityLog` ring buffer with count + age bounds, in-flight never age-evicted (Task 2); three recording decorators via Approach A (Tasks 4-6); per-call DEBUG/WARN logging inside `succeed`/`fail` (Task 2); read-only `GET /admin/llm-activity` (Tasks 8-9); config flags (Task 7); recorder self-protection + decorators rethrow (Tasks 2, 4-6); no DB/migration (confirmed — none added); tab placement avoiding a new SPA route (Task 12). The spec's `ProposalJudge`/embedding-method naming and the manager-vs-holder choice are corrected in the "spec deviations" note above, and the extraction-never-throws limitation is documented there.

**Placeholder scan:** No TBD/TODO; every code step shows complete code; commands have expected output.

**Type consistency:** `Subsystem`/`CallStatus` enums, `LlmCall`/`LlmCallView` fields, and `LlmActivityLog` method signatures (`begin`/`succeed`/`fail`/`snapshot`, nested `Snapshot`) are used identically across Tasks 2, 4-6, 8. Decorator constructors are all `(delegate, log, backend, model)`. The client method name `getLlmActivity` matches between Tasks 10 and 11.

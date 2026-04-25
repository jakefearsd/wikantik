# Chunk Extraction Pre-filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in chunk-level pre-filter that skips pure-code and no-proper-noun chunks before LLM extraction, drops corpus runtime by 20-50% depending on content mix, and is reversible via a single config flag.

**Architecture:** New `ChunkExtractionPrefilter` (pure-function class) with two boolean predicates, four config flags on `EntityExtractorConfig`, two wiring changes (bootstrap indexer pre-submission filter; per-chunk filter inside the listener's run loop). Defaults to disabled; opt-in via property file or CLI flag.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven, log4j2, Apache JSPWiki manager pattern.

**Spec:** [docs/superpowers/specs/2026-04-25-extraction-prefilter-design.md](../specs/2026-04-25-extraction-prefilter-design.md)

---

## File Structure

**New files:**
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilter.java` — pure-function filter, ~60 LOC
- `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilterTest.java` — predicate unit tests

**Modified files:**
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EntityExtractorConfig.java` — add 4 boolean fields, add `getBoolean` helper
- `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/EntityExtractorConfigTest.java` — assertions for new fields
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java` — fetch chunks per page, run filter before submitting, track `skippedChunks` + `skipReasons`
- `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexerTest.java` — new tests for filter wiring (prefilter tests live in same file to keep test fixture reuse cheap)
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/AsyncEntityExtractionListener.java` — per-chunk filter inside `runExtraction`'s loop
- `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java` — four new CLI flags that route into the existing `Properties` bag

**No schema, frontmatter, frontend, or migration changes.**

---

### Task 1: Add `getBoolean` helper to `EntityExtractorConfig`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EntityExtractorConfig.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/EntityExtractorConfigTest.java`

- [ ] **Step 1: Write failing test for `getBoolean` parsing semantics**

Add to `EntityExtractorConfigTest.java`:

```java
@Test
void parsesBooleanFlagsCaseInsensitively() {
    final Properties p = new Properties();
    p.setProperty( "wikantik.knowledge.extractor.prefilter.enabled", "TRUE" );
    p.setProperty( "wikantik.knowledge.extractor.prefilter.dry_run", "yes" );
    p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_pure_code", "1" );
    p.setProperty( "wikantik.knowledge.extractor.prefilter.skip_no_proper_noun", "no" );

    final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );
    assertTrue( cfg.prefilterEnabled() );
    assertTrue( cfg.prefilterDryRun() );
    assertTrue( cfg.prefilterSkipPureCode() );
    assertFalse( cfg.prefilterSkipNoProperNoun() );
}

@Test
void prefilterFlagsDefaultToReadyState() {
    final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( new Properties() );
    assertFalse( cfg.prefilterEnabled(),     "feature is opt-in" );
    assertFalse( cfg.prefilterDryRun(),      "dry-run only kicks in when explicitly requested" );
    assertTrue(  cfg.prefilterSkipPureCode(), "sub-predicates default on so the master switch alone enables both rules" );
    assertTrue(  cfg.prefilterSkipNoProperNoun() );
}
```

- [ ] **Step 2: Run tests; verify both fail with compile errors on `prefilterEnabled()` etc.**

Run: `mvn test -pl wikantik-main -Dtest=EntityExtractorConfigTest -q`
Expected: compile failure ("cannot find symbol: method prefilterEnabled()")

- [ ] **Step 3: Add `getBoolean` helper and four boolean fields to the record**

Edit `EntityExtractorConfig.java`. Update the record header to add four fields after `concurrency`:

```java
public record EntityExtractorConfig(
    String backend,
    String claudeModel,
    String ollamaModel,
    String ollamaBaseUrl,
    long timeoutMs,
    double confidenceThreshold,
    int maxExistingNodes,
    long perPageMinIntervalMs,
    int concurrency,
    boolean prefilterEnabled,
    boolean prefilterDryRun,
    boolean prefilterSkipPureCode,
    boolean prefilterSkipNoProperNoun
) {
```

Update `fromProperties` to read the four flags:

```java
public static EntityExtractorConfig fromProperties( final Properties props ) {
    return new EntityExtractorConfig(
        getString( props, "backend", BACKEND_DISABLED ),
        getString( props, "claude.model", "claude-haiku-4-5" ),
        getString( props, "ollama.model", "gemma4-assist:latest" ),
        getString( props, "ollama.base_url", "http://inference.jakefear.com:11434" ),
        getLong( props, "timeout_ms", 120_000L ),
        getDouble( props, "confidence_threshold", 0.6 ),
        getInt( props, "max_existing_nodes", 200 ),
        getLong( props, "per_page_min_interval_ms", 5_000L ),
        clampConcurrency( getInt( props, "concurrency", 2 ) ),
        getBoolean( props, "prefilter.enabled", false ),
        getBoolean( props, "prefilter.dry_run", false ),
        getBoolean( props, "prefilter.skip_pure_code", true ),
        getBoolean( props, "prefilter.skip_no_proper_noun", true )
    );
}
```

Add `getBoolean` next to the other helpers:

```java
private static boolean getBoolean( final Properties p, final String suffix, final boolean def ) {
    final String v = p == null ? null : p.getProperty( PREFIX + suffix );
    if( v == null || v.isBlank() ) {
        return def;
    }
    final String trimmed = v.trim();
    if( trimmed.equalsIgnoreCase( "true" ) || trimmed.equals( "1" ) || trimmed.equalsIgnoreCase( "yes" ) ) {
        return true;
    }
    if( trimmed.equalsIgnoreCase( "false" ) || trimmed.equals( "0" ) || trimmed.equalsIgnoreCase( "no" ) ) {
        return false;
    }
    LOG.info( "Ignoring non-boolean value '{}' for property {}{} — using default {}",
        v, PREFIX, suffix, def );
    return def;
}
```

- [ ] **Step 4: Run config tests; verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=EntityExtractorConfigTest -q`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Run full module compile to surface other call sites**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS. No call sites construct `EntityExtractorConfig` directly — only `fromProperties` — so the record header change is invisible to callers.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EntityExtractorConfig.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/EntityExtractorConfigTest.java
git commit -m "feat(extraction): add prefilter flags to EntityExtractorConfig"
```

---

### Task 2: Create `ChunkExtractionPrefilter` (predicates + Decision)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilter.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilterTest.java`

- [ ] **Step 1: Write failing predicate tests**

Create `ChunkExtractionPrefilterTest.java`:

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
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkExtractionPrefilterTest {

    private static final List< String > NO_HEADINGS = List.of();

    private ChunkExtractionPrefilter both() {
        return new ChunkExtractionPrefilter( /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
    }

    @Test
    void disabledFlagAlwaysReturnsPassthrough() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ false, /*dryRun*/ false, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx = 1\n```", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "disabled", d.reason() );
    }

    @Test
    void pureFencedCodeBlockIsSkipped() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "```python\ndef foo():\n    return 1\n```", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "pure_code", d.reason() );
    }

    @Test
    void fenceWithProseAfterIsKept() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "```\ncode\n```\nThis paragraph mentions PostgreSQL.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void proseWithNoCapsIsSkipped() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "this is just lowercase prose with no proper nouns at all.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void proseWithRealProperNounIsKept() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "PostgreSQL stores rows in heap files.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void twoLetterCapsLikeOfDoNotCountAsProperNouns() {
        // \b[A-Z][a-z]{2,}\b excludes "Of", "In", "On" — sentence-initial articles
        // shouldn't keep an otherwise-empty chunk alive.
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "Of all the things on offer, none stood out.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void compoundCaseLikeIPadIsTreatedAsNoProperNoun() {
        // Documented v1 false-negative; iPad does not match \b[A-Z][a-z]{2,}\b.
        // If the chunk has no other capitalised noun, it gets skipped.
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "the iPad is a tablet", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void skipPureCodeFlagOffPassesCodeThrough() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false, /*skipPureCode*/ false, /*skipNoProperNoun*/ true );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx\n```", NO_HEADINGS );
        // pure code -> caught by no_proper_noun instead since flag #1 is off
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void bothSubFlagsOffNeverSkips() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false, /*skipPureCode*/ false, /*skipNoProperNoun*/ false );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx\n```", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void dryRunReturnsTrueButReportsSkipReason() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "lower case only", NO_HEADINGS );
        assertTrue( d.shouldExtract(), "dry-run never blocks extraction" );
        assertEquals( "dry_run:no_proper_noun", d.reason() );
    }

    @Test
    void passthroughFactoryShortcutAlwaysExtracts() {
        final ChunkExtractionPrefilter f = ChunkExtractionPrefilter.passthrough();
        assertTrue( f.evaluate( "```\nx\n```", NO_HEADINGS ).shouldExtract() );
        assertTrue( f.evaluate( "lower case only", NO_HEADINGS ).shouldExtract() );
        assertFalse( f.isEnabled() );
    }

    @Test
    void isEnabledReflectsMasterFlag() {
        assertTrue( both().isEnabled() );
        assertFalse( ChunkExtractionPrefilter.passthrough().isEnabled() );
        final ChunkExtractionPrefilter dry = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
        assertTrue( dry.isEnabled(), "dry-run is still 'enabled' from the wiring perspective" );
    }
}
```

- [ ] **Step 2: Run tests; verify all fail (class does not exist)**

Run: `mvn test -pl wikantik-main -Dtest=ChunkExtractionPrefilterTest -q`
Expected: compile failure ("cannot find symbol: class ChunkExtractionPrefilter").

- [ ] **Step 3: Implement `ChunkExtractionPrefilter`**

Create `ChunkExtractionPrefilter.java`:

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
package com.wikantik.knowledge.extraction;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-extraction predicate. Decides whether a chunk is worth handing to the
 * LLM extractor. Pure function: no I/O, no state beyond the four config flags
 * passed at construction.
 *
 * <p>Two predicates currently:
 * <ul>
 *   <li><b>pure code block</b> — chunk text is one fenced code block with no
 *       prose around it. The extractor's prompt asks for named entities in
 *       prose; identifiers inside code aren't useful to it.</li>
 *   <li><b>no proper-noun candidate</b> — chunk text contains no token
 *       matching {@code \b[A-Z][a-z]{2,}\b}. Without one, the extractor has
 *       nothing to ground a named entity on.</li>
 * </ul>
 *
 * <p>Operates on chunk text only; heading_path is intentionally not inspected
 * (the extractor's prompt already includes it, so a chunk whose only caps are
 * in its heading still has nothing extractable in the body).
 */
public final class ChunkExtractionPrefilter {

    /** A non-skipping filter; used when the feature is off or in tests. */
    public static ChunkExtractionPrefilter passthrough() {
        return new ChunkExtractionPrefilter(
            /*enabled*/ false, /*dryRun*/ false,
            /*skipPureCode*/ false, /*skipNoProperNoun*/ false );
    }

    private static final Pattern PROPER_NOUN = Pattern.compile( "\\b[A-Z][a-z]{2,}\\b" );
    private static final Pattern PURE_CODE_BLOCK = Pattern.compile(
        "(?s)\\A\\s*```[^\\n]*\\n.*?\\n```\\s*\\z" );

    private final boolean enabled;
    private final boolean dryRun;
    private final boolean skipPureCode;
    private final boolean skipNoProperNoun;

    public ChunkExtractionPrefilter( final boolean enabled,
                                     final boolean dryRun,
                                     final boolean skipPureCode,
                                     final boolean skipNoProperNoun ) {
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.skipPureCode = skipPureCode;
        this.skipNoProperNoun = skipNoProperNoun;
    }

    /** True iff the master switch is on; cheap predicate so callers can skip
     *  pre-extraction work (e.g. {@code findByIds}) when the filter is off. */
    public boolean isEnabled() {
        return enabled;
    }

    public Decision evaluate( final String text, final List< String > headingPath ) {
        if( !enabled ) {
            return new Decision( true, "disabled" );
        }
        final String body = text == null ? "" : text;
        final String reason;
        if( skipPureCode && isPureCodeBlock( body ) ) {
            reason = "pure_code";
        } else if( skipNoProperNoun && !hasProperNoun( body ) ) {
            reason = "no_proper_noun";
        } else {
            return new Decision( true, "ok" );
        }
        if( dryRun ) {
            return new Decision( true, "dry_run:" + reason );
        }
        return new Decision( false, reason );
    }

    private static boolean isPureCodeBlock( final String text ) {
        return PURE_CODE_BLOCK.matcher( text ).matches();
    }

    private static boolean hasProperNoun( final String text ) {
        return PROPER_NOUN.matcher( text ).find();
    }

    public record Decision( boolean shouldExtract, String reason ) {}
}
```

- [ ] **Step 4: Run tests; verify all pass**

Run: `mvn test -pl wikantik-main -Dtest=ChunkExtractionPrefilterTest -q`
Expected: BUILD SUCCESS, 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilter.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilterTest.java
git commit -m "feat(extraction): ChunkExtractionPrefilter with code/proper-noun predicates"
```

---

### Task 3: Wire prefilter into `BootstrapEntityExtractionIndexer`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexerTest.java`

This task expands `Status` with `skippedChunks` and `skipReasons`, and adds a new constructor that accepts a filter. Existing constructors default to `passthrough()` so existing tests keep passing.

- [ ] **Step 1: Write failing test for filter wiring**

Add to `BootstrapEntityExtractionIndexerTest.java` (after the existing tests):

```java
@Test
void prefilterSkipsChunksAndIncrementsCounters() {
    final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
    final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
    final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

    final UUID keep = UUID.randomUUID();
    final UUID skipCode = UUID.randomUUID();
    final UUID skipNoProper = UUID.randomUUID();

    when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "PageOne" ) );
    when( chunkRepo.listChunkIdsForPage( "PageOne" ) )
            .thenReturn( List.of( keep, skipCode, skipNoProper ) );
    when( chunkRepo.findByIds( List.of( keep, skipCode, skipNoProper ) ) ).thenReturn( List.of(
        new ContentChunkRepository.MentionableChunk( keep, "PageOne", 0, List.of(),
            "PostgreSQL is a database." ),
        new ContentChunkRepository.MentionableChunk( skipCode, "PageOne", 1, List.of(),
            "```\nselect 1;\n```" ),
        new ContentChunkRepository.MentionableChunk( skipNoProper, "PageOne", 2, List.of(),
            "lower case prose only." )
    ) );
    when( listener.runExtractionSync( List.of( keep ) ) )
            .thenReturn( new AsyncEntityExtractionListener.RunResult( 1, 0 ) );

    final ChunkExtractionPrefilter filter = new ChunkExtractionPrefilter(
        /*enabled*/ true, /*dryRun*/ false, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );

    final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            listener, chunkRepo, mentionRepo, directExecutor(), filter );
    assertTrue( indexer.start( /*forceOverwrite*/ false ) );

    final BootstrapEntityExtractionIndexer.Status s = indexer.status();
    assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
    assertEquals( 1, s.processedChunks(), "only one chunk reaches the extractor" );
    assertEquals( 2, s.skippedChunks() );
    assertEquals( 1, s.skipReasons().getOrDefault( "pure_code", 0 ).intValue() );
    assertEquals( 1, s.skipReasons().getOrDefault( "no_proper_noun", 0 ).intValue() );
    verify( listener, times( 1 ) ).runExtractionSync( List.of( keep ) );
    verify( listener, never() ).runExtractionSync( List.of( skipCode ) );
    verify( listener, never() ).runExtractionSync( List.of( skipNoProper ) );
}

@Test
void prefilterDisabledByDefaultMatchesLegacyBehaviour() {
    final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
    final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
    final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

    final UUID c = UUID.randomUUID();
    when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
    when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of( c ) );
    when( listener.runExtractionSync( List.of( c ) ) )
            .thenReturn( new AsyncEntityExtractionListener.RunResult( 0, 0 ) );

    final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            listener, chunkRepo, mentionRepo, directExecutor() );
    assertTrue( indexer.start( /*forceOverwrite*/ false ) );

    final BootstrapEntityExtractionIndexer.Status s = indexer.status();
    assertEquals( 0, s.skippedChunks() );
    assertTrue( s.skipReasons().isEmpty() );
    // No findByIds call when filter is passthrough — legacy path is byte-identical.
    verify( chunkRepo, never() ).findByIds( any() );
    verify( listener ).runExtractionSync( List.of( c ) );
}
```

- [ ] **Step 2: Run tests; verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=BootstrapEntityExtractionIndexerTest -q`
Expected: compile failure ("cannot find symbol: method skippedChunks()" / "skipReasons()" / constructor).

- [ ] **Step 3: Modify `BootstrapEntityExtractionIndexer.java`**

Add to imports:

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
```

Add a field next to the existing counters:

```java
private final ChunkExtractionPrefilter prefilter;
private final AtomicInteger skippedChunks = new AtomicInteger();
private final Map< String, Integer > skipReasonCounts = new ConcurrentHashMap<>();
```

Update the `Status` record header to add the two new fields (preserve order — append, don't insert mid-list):

```java
public record Status(
    State state,
    int totalPages,
    int processedPages,
    int failedPages,
    int totalChunks,
    int processedChunks,
    int failedChunks,
    int mentionsWritten,
    int proposalsFiled,
    Instant startedAt,
    Instant finishedAt,
    long elapsedMs,
    String lastError,
    boolean forceOverwrite,
    int concurrency,
    int skippedChunks,
    Map< String, Integer > skipReasons
) {}
```

Update every existing public constructor to default `prefilter = ChunkExtractionPrefilter.passthrough()`. Add one new public constructor that accepts a filter:

```java
public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                         final ContentChunkRepository chunkRepo,
                                         final ChunkEntityMentionRepository mentionRepo,
                                         final ExecutorService executor,
                                         final ChunkExtractionPrefilter prefilter ) {
    this( listener, chunkRepo, mentionRepo, executor, /*ownsExecutor*/ false,
          executor, /*ownsWorkerPool*/ false, /*concurrency*/ 1, prefilter );
}
```

Update the *private* terminal constructor signature to accept and store `prefilter`:

```java
private BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                          final ContentChunkRepository chunkRepo,
                                          final ChunkEntityMentionRepository mentionRepo,
                                          final ExecutorService executor,
                                          final boolean ownsExecutor,
                                          final ExecutorService workerPool,
                                          final boolean ownsWorkerPool,
                                          final int concurrency,
                                          final ChunkExtractionPrefilter prefilter ) {
    if( listener == null ) throw new IllegalArgumentException( "listener must not be null" );
    if( chunkRepo == null ) throw new IllegalArgumentException( "chunkRepo must not be null" );
    if( mentionRepo == null ) throw new IllegalArgumentException( "mentionRepo must not be null" );
    if( executor == null ) throw new IllegalArgumentException( "executor must not be null" );
    if( workerPool == null ) throw new IllegalArgumentException( "workerPool must not be null" );
    if( prefilter == null ) throw new IllegalArgumentException( "prefilter must not be null" );
    this.listener = listener;
    this.chunkRepo = chunkRepo;
    this.mentionRepo = mentionRepo;
    this.executor = executor;
    this.ownsExecutor = ownsExecutor;
    this.workerPool = workerPool;
    this.ownsWorkerPool = ownsWorkerPool;
    this.concurrency = concurrency;
    this.prefilter = prefilter;
}
```

For each *existing* delegating public constructor, append `ChunkExtractionPrefilter.passthrough()` as the last argument forwarded to the private constructor. Example for the 4-arg public constructor:

```java
public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                         final ContentChunkRepository chunkRepo,
                                         final ChunkEntityMentionRepository mentionRepo,
                                         final ExecutorService executor ) {
    this( listener, chunkRepo, mentionRepo, executor, /*ownsExecutor*/ false,
          executor, /*ownsWorkerPool*/ false, /*concurrency*/ 1,
          ChunkExtractionPrefilter.passthrough() );
}
```

Apply the same `passthrough()` append to the 3-arg, 4-arg-with-concurrency, and 6-arg public constructors. Confirm by counting `this( listener,` references — every one must end with the prefilter argument.

In `start()`, reset the new counters:

```java
skippedChunks.set( 0 );
skipReasonCounts.clear();
```

Update `runBatch` to fetch chunks and apply the filter. Replace the existing chunk-submission loop:

```java
final List< Future< ChunkOutcome > > futures = new ArrayList<>( chunkIds.size() );
boolean cancelledMidPage = false;
for( final UUID chunkId : chunkIds ) {
    if( cancelRequested.get() ) {
        cancelledMidPage = true;
        break;
    }
    final String pageForTask = page;
    futures.add( workerPool.submit( () -> runChunk( chunkId, pageForTask ) ) );
}
```

…with a fetch-then-filter version:

```java
final List< ContentChunkRepository.MentionableChunk > hydrated;
final boolean useFilter = prefilter.isEnabled();
if( useFilter ) {
    try {
        hydrated = chunkRepo.findByIds( chunkIds );
    } catch( final RuntimeException e ) {
        failed++;
        failedPages.incrementAndGet();
        LOG.warn( "Bootstrap extraction: failed to hydrate chunks for page '{}': {}",
            page, e.getMessage() );
        continue;
    }
} else {
    hydrated = List.of();
}

final List< Future< ChunkOutcome > > futures = new ArrayList<>( chunkIds.size() );
final Map< String, Integer > pageSkipReasons = new HashMap<>();
boolean cancelledMidPage = false;

if( !useFilter ) {
    for( final UUID chunkId : chunkIds ) {
        if( cancelRequested.get() ) { cancelledMidPage = true; break; }
        final String pageForTask = page;
        futures.add( workerPool.submit( () -> runChunk( chunkId, pageForTask ) ) );
    }
} else {
    final Map< UUID, ContentChunkRepository.MentionableChunk > byId = new HashMap<>();
    for( final ContentChunkRepository.MentionableChunk c : hydrated ) {
        byId.put( c.id(), c );
    }
    for( final UUID chunkId : chunkIds ) {
        if( cancelRequested.get() ) { cancelledMidPage = true; break; }
        final ContentChunkRepository.MentionableChunk mc = byId.get( chunkId );
        if( mc == null ) {
            // Chunk vanished between listChunkIdsForPage and findByIds — skip and count as failed.
            failedChunks.incrementAndGet();
            processedChunks.incrementAndGet();
            continue;
        }
        final ChunkExtractionPrefilter.Decision d = prefilter.evaluate( mc.text(), mc.headingPath() );
        if( !d.shouldExtract() ) {
            skippedChunks.incrementAndGet();
            processedChunks.incrementAndGet();
            skipReasonCounts.merge( d.reason(), 1, Integer::sum );
            pageSkipReasons.merge( d.reason(), 1, Integer::sum );
            continue;
        }
        final String pageForTask = page;
        futures.add( workerPool.submit( () -> runChunk( chunkId, pageForTask ) ) );
    }
}
```

Update the per-page summary log to include the page-local skip count (after the existing `LOG.info` for the page summary):

```java
if( !pageSkipReasons.isEmpty() ) {
    LOG.info( "Bootstrap extraction: page='{}' prefilter skipped {} chunks: {}",
        page, pageSkipReasons.values().stream().mapToInt( Integer::intValue ).sum(),
        pageSkipReasons );
}
```

Update `status()` to populate the two new fields:

```java
return new Status(
    state.get(),
    totalPages.get(),
    processedPages.get(),
    failedPages.get(),
    totalChunks.get(),
    processedChunks.get(),
    failedChunks.get(),
    mentionsWritten.get(),
    proposalsFiled.get(),
    startedAt.get(),
    finishedAt.get(),
    Math.max( 0L, elapsedMs ),
    lastError.get(),
    forceOverwrite.get(),
    concurrency,
    skippedChunks.get(),
    Map.copyOf( skipReasonCounts )
);
```

Update `logFinalSummary` to include the skip totals:

```java
LOG.info( "Bootstrap entity extraction {}: processedPages={}/{}, failedPages={}, "
        + "processedChunks={}/{}, failedChunks={}, skippedChunks={}, skipReasons={}, "
        + "mentionsWritten={}, proposalsFiled={}, totalMs={}, meanPerPageMs={}, meanPerChunkMs={}",
    state.get(), donePages, totalPages.get(), failedPages.get(),
    doneChunks, totalChunks.get(), failedChunks.get(),
    skippedChunks.get(), Map.copyOf( skipReasonCounts ),
    mentionsWritten.get(), proposalsFiled.get(), elapsedMs, meanPerPageMs, meanPerChunkMs );
```

- [ ] **Step 4: Run tests; verify all pass**

Run: `mvn test -pl wikantik-main -Dtest=BootstrapEntityExtractionIndexerTest -q`
Expected: BUILD SUCCESS — both new tests plus all existing tests pass.

- [ ] **Step 5: Run module-wide compile to flush out other call-site breakage**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS. The `Status` record now has 16 components — confirm no caller destructures it positionally (the existing CLI builds the progress string via getter methods, not pattern matching, so this should be safe; spot-check `BootstrapExtractionCli.formatProgress`).

If the compile fails on `BootstrapExtractionCli` because it constructs `Status` somewhere, switch to method-call access; do not regenerate it positionally. (Spec note: the CLI only *reads* status via getters in the current code.)

- [ ] **Step 6: Update CLI progress line to surface skipped count**

Modify `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java#formatProgress` to append skip information:

```java
private static String formatProgress( final BootstrapEntityExtractionIndexer.Status s ) {
    final int totalChunks = s.totalChunks();
    final int doneChunks  = s.processedChunks();
    final double pct = totalChunks > 0
        ? ( 100.0 * doneChunks / totalChunks )
        : 0.0;
    final long elapsedSec = s.elapsedMs() / 1000L;
    final long perChunkMs = doneChunks > 0 ? s.elapsedMs() / doneChunks : 0L;
    final String skipSuffix = s.skippedChunks() > 0
        ? String.format( Locale.ROOT, " skipped=%d %s", s.skippedChunks(), s.skipReasons() )
        : "";
    return String.format( Locale.ROOT,
        "state=%s pages=%d/%d chunks=%d/%d (%.1f%%) failedChunks=%d "
      + "mentions=%d proposals=%d elapsed=%ds perChunkMs=%d%s",
        s.state(), s.processedPages(), s.totalPages(),
        doneChunks, totalChunks, pct, s.failedChunks(),
        s.mentionsWritten(), s.proposalsFiled(), elapsedSec, perChunkMs, skipSuffix );
}
```

- [ ] **Step 7: Compile the CLI module**

Run: `mvn compile -pl wikantik-extract-cli -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexerTest.java \
        wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java
git commit -m "feat(extraction): wire prefilter into bootstrap indexer + CLI status"
```

---

### Task 4: Wire prefilter into `AsyncEntityExtractionListener` per-chunk loop

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/AsyncEntityExtractionListener.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/extraction/AsyncEntityExtractionListenerPrefilterTest.java`

The listener path is the save-time path. The bootstrap pre-filters before submitting, so the listener's filter is mostly redundant during a bootstrap run — but it's the only enforcement on save-time saves. We add it inside the per-chunk loop in `runExtraction`.

- [ ] **Step 1: Write failing per-chunk filter test**

Create `AsyncEntityExtractionListenerPrefilterTest.java`:

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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncEntityExtractionListenerPrefilterTest {

    @Test
    void filterSkipsChunksWithNoProperNoun() {
        final EntityExtractor extractor = Mockito.mock( EntityExtractor.class );
        when( extractor.code() ).thenReturn( "test" );
        when( extractor.extract( any(), any() ) )
            .thenReturn( ExtractionResult.empty( "test", Duration.ZERO ) );

        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final JdbcKnowledgeRepository kgRepo = Mockito.mock( JdbcKnowledgeRepository.class );
        when( kgRepo.findAllNodes() ).thenReturn( List.of() );

        final UUID keep = UUID.randomUUID();
        final UUID skip = UUID.randomUUID();
        when( chunkRepo.findByIds( List.of( keep, skip ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( keep, "P", 0, List.of(),
                "PostgreSQL is a database." ),
            new ContentChunkRepository.MentionableChunk( skip, "P", 1, List.of(),
                "lower case only." )
        ) );

        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.prefilter.enabled", "true" );
        final EntityExtractorConfig cfg = EntityExtractorConfig.fromProperties( p );

        final ExecutorService inline = Executors.newSingleThreadExecutor();
        try( AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
                 extractor, cfg, chunkRepo, mentionRepo, kgRepo,
                 new SimpleMeterRegistry(), inline ) ) {

            final AsyncEntityExtractionListener.RunResult res =
                listener.runExtractionSync( List.of( keep, skip ) );

            assertEquals( 0, res.mentionsWritten() );
            verify( extractor, times( 1 ) ).extract( any(), any() );
            // The kept chunk's id matches what was extracted; the skipped one was never seen.
            verify( extractor ).extract(
                Mockito.argThat( ec -> ec != null && ec.id().equals( keep ) ),
                any() );
        } finally {
            inline.shutdown();
        }
    }
}
```

**Note on the test:** `JdbcKnowledgeRepository.findAllNodes()` may have a different exact name in this codebase. Check by reading `loadExistingNodes()` in `AsyncEntityExtractionListener.java`; mock whichever method that calls (it's the one that returns existing nodes for the dictionary). Adjust `when(...)` accordingly. Worst case: pass an empty `Properties` so `loadExistingNodes` short-circuits — but we want the listener fully configured for this test.

- [ ] **Step 2: Run the test; verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=AsyncEntityExtractionListenerPrefilterTest -q`
Expected: FAIL — extractor is called twice (no filter wired), test asserts once.

- [ ] **Step 3: Wire the filter into `AsyncEntityExtractionListener.runExtraction`**

Open `AsyncEntityExtractionListener.java`. Add a field next to the others:

```java
private final ChunkExtractionPrefilter prefilter;
```

Initialise it in the private terminal constructor (around line 130, after `this.ownsExecutor = ownsExecutor;`):

```java
this.prefilter = new ChunkExtractionPrefilter(
    config.prefilterEnabled(),
    config.prefilterDryRun(),
    config.prefilterSkipPureCode(),
    config.prefilterSkipNoProperNoun() );
```

Modify the per-chunk loop inside `runExtraction` (around line 220). Add the filter check at the top of the loop body:

```java
for( final ContentChunkRepository.MentionableChunk c : chunks ) {
    final ChunkExtractionPrefilter.Decision d = prefilter.evaluate( c.text(), c.headingPath() );
    if( !d.shouldExtract() ) {
        if( LOG.isDebugEnabled() ) {
            LOG.debug( "Prefilter dropped chunk {} on page '{}': reason={}",
                c.id(), c.pageName(), d.reason() );
        }
        continue;
    }
    final ExtractionChunk ec = new ExtractionChunk(
        c.id(), c.pageName(), c.chunkIndex(), c.headingPath(), c.text() );
    final ExtractionResult result;
    try {
        result = extractor.extract( ec, ctx );
    } catch( final RuntimeException e ) {
        // ... unchanged
```

(Leave the rest of the loop body unchanged.)

- [ ] **Step 4: Run the test; verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=AsyncEntityExtractionListenerPrefilterTest -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the full extraction-package test suite to confirm no regressions**

Run: `mvn test -pl wikantik-main -Dtest='com.wikantik.knowledge.extraction.*' -q`
Expected: BUILD SUCCESS, all previously-passing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/extraction/AsyncEntityExtractionListener.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/extraction/AsyncEntityExtractionListenerPrefilterTest.java
git commit -m "feat(extraction): apply prefilter inside save-time listener loop"
```

---

### Task 5: Wire prefilter into the bootstrap CLI and `WikiEngine` factory

**Files:**
- Modify: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (or wherever the indexer is constructed for in-process operation)

The CLI is what runs the long bootstrap. It needs to (a) accept the four flags from the command line, (b) construct a `ChunkExtractionPrefilter` from the config, and (c) pass it into the new indexer constructor.

- [ ] **Step 1: Locate the indexer construction in `WikiEngine` (or equivalent)**

Run: `grep -n "new BootstrapEntityExtractionIndexer" wikantik-main/src/main/java/com/wikantik/WikiEngine.java`

Note the line number(s). The in-process construction needs the same prefilter-aware constructor as the CLI. If the indexer isn't built in `WikiEngine` (it may be built lazily in `KnowledgeGraphServiceFactory` or `WikiEngine.createKnowledgeServices`), use `grep -rn "new BootstrapEntityExtractionIndexer" wikantik-main/src/main/java/` to find every site.

- [ ] **Step 2: Add CLI flags to `BootstrapExtractionCli.Args`**

Edit `BootstrapExtractionCli.java`. Add to `Args`:

```java
boolean prefilterEnabled       = false;
boolean prefilterDryRun        = false;
boolean prefilterSkipCode      = true;
boolean prefilterSkipNoProper  = true;
```

Add new switch arms in `parse()`:

```java
case "--prefilter"               -> a.prefilterEnabled = true;
case "--prefilter-dry-run"       -> { a.prefilterEnabled = true; a.prefilterDryRun = true; }
case "--no-prefilter-skip-code"  -> a.prefilterSkipCode = false;
case "--no-prefilter-skip-nopn"  -> a.prefilterSkipNoProper = false;
```

(`--prefilter-dry-run` implies `--prefilter`; this is the most ergonomic shape.)

In `toExtractorConfig`, add the four properties:

```java
p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.enabled", Boolean.toString( prefilterEnabled ) );
p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.dry_run", Boolean.toString( prefilterDryRun ) );
p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.skip_pure_code", Boolean.toString( prefilterSkipCode ) );
p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.skip_no_proper_noun", Boolean.toString( prefilterSkipNoProper ) );
```

In `printUsage()`, document the four flags under a new "Pre-filter" section:

```java
            Pre-filter (optional, opt-in):
              --prefilter                        enable chunk prefilter (skip pure code + no-proper-noun chunks)
              --prefilter-dry-run                enable filter but log decisions only — no chunks are skipped
              --no-prefilter-skip-code           keep the prefilter on but disable the pure-code rule
              --no-prefilter-skip-nopn           keep the prefilter on but disable the no-proper-noun rule
```

- [ ] **Step 3: Construct the `ChunkExtractionPrefilter` and pass it to the indexer in `BootstrapExtractionCli.run`**

Replace the indexer construction:

```java
try( BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
         listener, chunkRepo, mentionRepo, cfg.concurrency() ) ) {
```

with a prefilter-aware build. Add an explicit constructor that accepts both worker pool and prefilter (or pass via a thin wrapper). Simplest: add another public constructor on `BootstrapEntityExtractionIndexer` that accepts `(listener, chunkRepo, mentionRepo, concurrency, prefilter)`:

```java
public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                         final ContentChunkRepository chunkRepo,
                                         final ChunkEntityMentionRepository mentionRepo,
                                         final int concurrency,
                                         final ChunkExtractionPrefilter prefilter ) {
    this( listener, chunkRepo, mentionRepo, defaultExecutor(), /*ownsExecutor*/ true,
          defaultWorkerPool( concurrency ), /*ownsWorkerPool*/ true,
          EntityExtractorConfig.clampConcurrency( concurrency ), prefilter );
}
```

Then in `BootstrapExtractionCli.run`:

```java
final ChunkExtractionPrefilter prefilter = new ChunkExtractionPrefilter(
    cfg.prefilterEnabled(),
    cfg.prefilterDryRun(),
    cfg.prefilterSkipPureCode(),
    cfg.prefilterSkipNoProperNoun() );

try( BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
         listener, chunkRepo, mentionRepo, cfg.concurrency(), prefilter ) ) {
    LOG.info( "Extract-CLI starting (backend={}, model={}, concurrency={}, force={}, prefilter={}{})",
        cfg.backend(), modelLabel( cfg ), cfg.concurrency(), a.force,
        cfg.prefilterEnabled(), cfg.prefilterDryRun() ? " dry-run" : "" );
    // ... rest unchanged
```

- [ ] **Step 4: Update `WikiEngine` (or factory) construction site**

Wherever `new BootstrapEntityExtractionIndexer(...)` is built in `WikiEngine`, build the prefilter from the same `EntityExtractorConfig` and pass it through. Preserve any executor-sharing arrangement.

If the construction is currently:

```java
new BootstrapEntityExtractionIndexer( listener, chunkRepo, mentionRepo, concurrency )
```

change it to:

```java
new BootstrapEntityExtractionIndexer( listener, chunkRepo, mentionRepo, concurrency,
    new ChunkExtractionPrefilter(
        cfg.prefilterEnabled(),
        cfg.prefilterDryRun(),
        cfg.prefilterSkipPureCode(),
        cfg.prefilterSkipNoProperNoun() ) )
```

- [ ] **Step 5: Compile end-to-end**

Run: `mvn compile -pl wikantik-extract-cli -am -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Run the full extraction package test suite again**

Run: `mvn test -pl wikantik-main -Dtest='com.wikantik.knowledge.extraction.*' -q`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/extraction/BootstrapEntityExtractionIndexer.java
git commit -m "feat(extraction): expose prefilter via CLI flags and engine wiring"
```

---

### Task 6: Final integration build + smoke

- [ ] **Step 1: Run a full unit-test build to confirm no regressions in adjacent modules**

Run: `mvn clean install -T 1C -DskipITs -q`
Expected: BUILD SUCCESS.

If this surfaces a Mockito-strict or RAT licence failure, fix it inline; do not push the failure to the next phase.

- [ ] **Step 2: Eyeball the dry-run path locally**

Build the CLI jar:

```bash
mvn package -pl wikantik-extract-cli -am -DskipTests -q
```

Then, with the bootstrap stopped (you'll need to halt the in-flight Gemma run for a moment), run the dry-run pass on a single page's chunks. This is a manual smoke; expected output is one INFO line per page summarising "would skip N (pure_code=X no_proper_noun=Y)" without actually skipping. Feel free to skip this step if the in-flight run can't be paused — the unit tests already cover the wiring.

```bash
PG_PASSWORD='...' java -jar wikantik-extract-cli/target/wikantik-extract-cli.jar \
    --jdbc-password-env PG_PASSWORD \
    --backend disabled \
    --prefilter-dry-run \
    --poll-seconds 10
```

(With `--backend disabled`, no extractor calls happen — the run still loads chunks per page and exercises the filter so you can see the would-skip counts in the log.)

- [ ] **Step 3: No commit needed** (build artefacts only).

---

## Self-Review Notes

- **Spec coverage:** every spec section has at least one task —
  - Predicates → Task 2
  - Config flags → Task 1
  - `ChunkExtractionPrefilter` class → Task 2
  - `BootstrapEntityExtractionIndexer` modification → Task 3
  - `AsyncEntityExtractionListener` modification → Task 4
  - `EntityExtractorConfig` modification → Task 1
  - Dry-run mode → covered in Task 2 (predicate) + Task 5 (CLI exposure) + Task 6 (smoke)
  - Tests → Task 2, Task 3, Task 4
  - Build & ship → Task 6
- **No placeholders.** Every code block is complete.
- **Type consistency:** `Decision(boolean shouldExtract, String reason)`, `passthrough()` factory, four config field names (`prefilterEnabled`, `prefilterDryRun`, `prefilterSkipPureCode`, `prefilterSkipNoProperNoun`) appear identically in tests, helper, and wiring tasks.
- **Open question** (compound-case identifiers like `iPad`) is captured in the test fixture as a documented v1 false-negative; no plan task reverses the decision.

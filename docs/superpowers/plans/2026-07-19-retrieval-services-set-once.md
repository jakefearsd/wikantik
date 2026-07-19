# Set-Once RetrievalServices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three late-bound nullable components of `KnowledgeSubsystem.Services` (`contextRetrievalService`, `bundleAssemblyService`, `briefingAssemblyService`) with a single set-once `AtomicReference<RetrievalServices>` component, eliminating the 25-arg positional record rebuild in `WikiEngine.patchContextRetrievalService` and making the patch seam idempotent (a duplicate call can no longer leak a second `BundleEvalScheduler`).

**Architecture:** `KnowledgeSubsystem.Services` shrinks from 25 to 23 positional components. The three retrieval-derived services move into a new nested record `KnowledgeSubsystem.RetrievalServices`, held by `Services` as an `AtomicReference` that is installed exactly once via CAS (`Services.installRetrieval`). Custom accessor methods with the *same names as the old record accessors* (`contextRetrievalService()`, `bundleAssemblyService()`, `briefingAssemblyService()`) read through the reference, so every consumer (`SearchResource`, `BundleResource`, `BriefingResource`, `KnowledgeMcpInitializer`, `ContextRetrievalServiceInitializer`, `KnowledgeSubsystemFactoryTest:146`, etc.) stays source-compatible and is NOT modified. Hot-swap rebuilds (`rebuildFromExisting`, WikiEngine boot rebuild) carry the *same* `AtomicReference` instance forward, so a patch installed after a rebuild is visible everywhere automatically — the invariant that was previously maintained by hand-copying three fields in four places. `WikiEngine.patchContextRetrievalService` no longer rebuilds the `Services` record or the `WikiSubsystems` servlet-context stash at all: it builds the trio, CASes it in, and only on a *successful first* install starts the eval scheduler.

**Tech Stack:** Java 25, JUnit 5, Mockito (plain unit tests — no Testcontainers/Docker needed for the new tests), Maven.

## Global Constraints

- Sole developer on this repo — work directly on `main`, no feature branches. **Never commit a red state**: the single commit for this refactor happens only in Task 3 after the full IT reactor passes (standing repo feedback: gate prod-code commits on `mvn clean install -Pintegration-tests -fae`).
- Every **new** `.java` file MUST start with the ASF license header (copy the exact 18-line block from `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java` lines 1–18) or `mvn apache-rat:check` fails the build.
- Never swallow exceptions with empty catch blocks — always at least `LOG.warn()` with context.
- Integration tests MUST run sequentially — **no `-T` flag** with `-Pintegration-tests`. Also do not use `-T 1C` for the wikantik-main unit suite (known parallel flakiness).
- After any signature change, run `mvn test-compile` (plain `mvn compile` skips test sources and hides test breakage).
- Match the existing code style: `final` on parameters/locals, spaces inside parens `( like this )`, `/*fieldName=*/` inline comments on long positional arg lists.
- Commit messages 1–3 lines, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Do NOT touch: `BundleServiceWiring`, `BriefingServiceWiring`, `BundleEvalWiring`, `ContextRetrievalServiceInitializer`, or any consumer that only *reads* the three accessors. The accessor names and return types are unchanged.

---

### Task 1: Reshape `KnowledgeSubsystem.Services` around a set-once `RetrievalServices` (TDD, all wikantik-main sites)

The record shape change is compile-atomic across wikantik-main: the record, four factory sites, the bridge, and both WikiEngine sites must change together or the module does not compile. Tests are written FIRST and fail (compile error), then the reshape makes them pass.

**Files:**
- Test (create): `wikantik-main/src/test/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemServicesTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java` (record reshape, ~lines 105–171)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java` (4 construction sites: `create` ~280, `knowledgeDisabledServices` ~325, `rebuildFromExisting` ~391, `readFromManagerRegistry` ~540)
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemBridge.java` (~line 60)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (`rebuildKnowledgeSubsystemWithPostConstructionServices` ~1560, `patchContextRetrievalService` ~1620–1720)

**Interfaces:**
- Consumes: existing interfaces `com.wikantik.api.knowledge.ContextRetrievalService`, `com.wikantik.api.bundle.BundleAssemblyService`, `com.wikantik.api.briefing.BriefingAssemblyService` (all already imported by `KnowledgeSubsystem.java`).
- Produces (Task 2 and all future callers rely on these exact signatures):
  - `public record KnowledgeSubsystem.RetrievalServices( ContextRetrievalService contextRetrievalService, BundleAssemblyService bundleAssemblyService, BriefingAssemblyService briefingAssemblyService )`
  - `KnowledgeSubsystem.Services` — 23 positional components, order: the unchanged 16 core fields (`kgService` … `hubSyncFilter`), then `forAgentProjectionService`, `bootstrapEntityExtractionIndexer`, `kgInclusionPolicy`, `reconciliationJobRunner`, `retrievalQualityRunner`, `kgCurationOps`, and **last** `AtomicReference<RetrievalServices> retrieval`. (Relative to today: component 17 `contextRetrievalService` and trailing components 24 `bundleAssemblyService` / 25 `briefingAssemblyService` are removed; `retrieval` is appended.)
  - `public boolean installRetrieval( RetrievalServices services )` on `Services` — CAS, returns `false` and changes nothing if already installed.
  - `public ContextRetrievalService contextRetrievalService()`, `public BundleAssemblyService bundleAssemblyService()`, `public BriefingAssemblyService briefingAssemblyService()` on `Services` — null-safe read-through (same names/signatures as the old record accessors).
  - Compact constructor normalizes a `null` `retrieval` argument to `new AtomicReference<>()` — passing `null` is always safe.

- [ ] **Step 1: Write the failing test class**

Create `wikantik-main/src/test/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemServicesTest.java` with exactly this content (prepend the 18-line ASF license header copied verbatim from `KnowledgeSubsystem.java` lines 1–18):

```java
package com.wikantik.knowledge.subsystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.WikiEngine;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.knowledge.ContextRetrievalService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the set-once retrieval trio on {@link KnowledgeSubsystem.Services}:
 * null-safe accessors before install, CAS install-exactly-once semantics, null-reference
 * normalization, and the carry-the-same-reference invariant on the factory rebuild paths.
 */
class KnowledgeSubsystemServicesTest {

    /** All-null Services — the compact constructor must normalize the null retrieval ref. */
    private static KnowledgeSubsystem.Services allNullServices() {
        return new KnowledgeSubsystem.Services(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null );
    }

    @Test
    void retrievalAccessorsAreNullBeforeInstall() {
        final KnowledgeSubsystem.Services services = allNullServices();
        assertNull( services.contextRetrievalService() );
        assertNull( services.bundleAssemblyService() );
        assertNull( services.briefingAssemblyService() );
    }

    @Test
    void installExposesTheTrioThroughTheAccessors() {
        final KnowledgeSubsystem.Services services = allNullServices();
        final ContextRetrievalService ctx = Mockito.mock( ContextRetrievalService.class );
        final BundleAssemblyService bundle = Mockito.mock( BundleAssemblyService.class );
        final BriefingAssemblyService briefing = Mockito.mock( BriefingAssemblyService.class );
        assertTrue( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( ctx, bundle, briefing ) ) );
        assertSame( ctx, services.contextRetrievalService() );
        assertSame( bundle, services.bundleAssemblyService() );
        assertSame( briefing, services.briefingAssemblyService() );
    }

    @Test
    void secondInstallIsRefusedAndTheOriginalIsRetained() {
        final KnowledgeSubsystem.Services services = allNullServices();
        final ContextRetrievalService first = Mockito.mock( ContextRetrievalService.class );
        assertTrue( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( first, null, null ) ) );
        final ContextRetrievalService second = Mockito.mock( ContextRetrievalService.class );
        assertFalse( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( second, null, null ) ),
            "a second install must be refused" );
        assertSame( first, services.contextRetrievalService(),
            "the originally installed service must be retained" );
    }

    @Test
    void nullRetrievalReferenceIsNormalizedToAnEmptyOne() {
        final KnowledgeSubsystem.Services services = allNullServices();
        assertNotNull( services.retrieval(),
            "compact constructor must normalize a null retrieval reference" );
        assertTrue( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( null, null, null ) ) );
    }

    @Test
    void rebuildFromExistingCarriesTheSameRetrievalReference() {
        final WikiEngine engine = Mockito.mock( WikiEngine.class );
        final KnowledgeSubsystem.Services existing = allNullServices();
        final ContextRetrievalService ctx = Mockito.mock( ContextRetrievalService.class );
        existing.installRetrieval( new KnowledgeSubsystem.RetrievalServices( ctx, null, null ) );
        final KnowledgeSubsystem.Services rebuilt =
            KnowledgeSubsystemFactory.rebuildFromExisting( engine, existing );
        assertSame( existing.retrieval(), rebuilt.retrieval(),
            "hot-swap rebuilds must share the set-once reference, not copy its value" );
        assertSame( ctx, rebuilt.contextRetrievalService() );
    }

    @Test
    void readFromManagerRegistrySeedsContextRetrievalFromTheRegistry() {
        final WikiEngine engine = Mockito.mock( WikiEngine.class );
        final ContextRetrievalService ctx = Mockito.mock( ContextRetrievalService.class );
        Mockito.when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctx );
        final KnowledgeSubsystem.Services services =
            KnowledgeSubsystemFactory.readFromManagerRegistry( engine );
        assertSame( ctx, services.contextRetrievalService() );
        assertNull( services.bundleAssemblyService(),
            "bundle/briefing are only ever built at the patch seam, never on the cold path" );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test-compile -pl wikantik-main -q 2>&1 | tail -30`
Expected: COMPILE ERROR — `cannot find symbol: class RetrievalServices` / `method installRetrieval` (the 23-arg constructor also does not exist yet). This is the TDD red state.

- [ ] **Step 3: Reshape `KnowledgeSubsystem.java`**

3a. Add the import (alphabetical position, after `import javax.sql.DataSource;`):

```java
import java.util.concurrent.atomic.AtomicReference;
```

(Note: `javax.sql.DataSource` sorts before `java.util...` in some styles — match the file's existing import ordering; if checkstyle complains, place `java.util.concurrent.atomic.AtomicReference` before `javax.sql.DataSource`.)

3b. In the `Services` javadoc (currently lines ~124–142), delete the `<li>` bullets for `contextRetrievalService` and `bundleAssemblyService` (the ones reading "null until the ContextRetrievalServiceInitializer servlet listener fires…" and "DERIVED from contextRetrievalService (RAG-as-a-Service)…"), keeping the other bullets, and add this paragraph immediately before the closing `*/` of the javadoc:

```java
     * <p>{@code retrieval} — the set-once holder for the three retrieval-derived
     * services that cannot exist at engine-boot time. It is installed exactly once
     * by {@code WikiEngine.patchContextRetrievalService} via {@link #installRetrieval};
     * every construction path passes {@code null} (normalized to an empty reference)
     * except the rebuild paths, which carry the <em>same</em> reference forward so a
     * patch installed before or after a hot-swap rebuild is visible everywhere.
     * Read it through {@link #contextRetrievalService()}, {@link #bundleAssemblyService()}
     * and {@link #briefingAssemblyService()} — never dereference the raw
     * {@code AtomicReference} at call sites.</p>
```

3c. Replace the `Services` record declaration (the block from `public record Services(` through its closing `) {}` — currently lines 144–170) with:

```java
    public record Services(
        KnowledgeGraphService kgService,
        KgProposalJudgeService judgeService,
        JudgeRunner judgeRunner,
        KgMaterializationService kgMaterialization,
        KgJudgeTimeoutRepository judgeTimeoutRepository,
        HubProposalService hubProposalService,
        HubDiscoveryService hubDiscoveryService,
        HubOverviewService hubOverviewService,
        HubProposalRepository hubProposalRepository,
        HubDiscoveryRepository hubDiscoveryRepository,
        ContentChunkRepository contentChunkRepository,
        ChunkProjector chunkProjector,
        MentionIndex mentionIndex,
        NodeMentionSimilarity nodeMentionSimilarity,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        ForAgentProjectionService forAgentProjectionService,
        BootstrapEntityExtractionIndexer bootstrapEntityExtractionIndexer,
        KgInclusionPolicy kgInclusionPolicy,
        ReconciliationJobRunner reconciliationJobRunner,
        RetrievalQualityRunner retrievalQualityRunner,
        KgCurationOps kgCurationOps,
        AtomicReference< RetrievalServices > retrieval
    ) {

        /** Normalizes a null retrieval holder so accessors and installs are always safe. */
        public Services {
            retrieval = retrieval != null ? retrieval : new AtomicReference<>();
        }

        /** The live retrieval service, or {@code null} until the patch seam installs it. */
        public ContextRetrievalService contextRetrievalService() {
            final RetrievalServices rs = retrieval.get();
            return rs == null ? null : rs.contextRetrievalService();
        }

        /** The live bundle assembler, or {@code null} until the patch seam installs it. */
        public BundleAssemblyService bundleAssemblyService() {
            final RetrievalServices rs = retrieval.get();
            return rs == null ? null : rs.bundleAssemblyService();
        }

        /** The live briefing assembler, or {@code null} until the patch seam installs it. */
        public BriefingAssemblyService briefingAssemblyService() {
            final RetrievalServices rs = retrieval.get();
            return rs == null ? null : rs.briefingAssemblyService();
        }

        /**
         * Installs the retrieval trio exactly once (compare-and-set against an empty
         * holder). Returns {@code false} — changing nothing — if a trio was already
         * installed; the caller must not start any side-effecting collaborator (e.g.
         * the bundle-eval scheduler) when this returns {@code false}.
         */
        public boolean installRetrieval( final RetrievalServices services ) {
            return retrieval.compareAndSet( null, services );
        }
    }

    /**
     * The three retrieval-derived services that cannot exist at engine-boot time:
     * {@code contextRetrievalService} is wired by {@code ContextRetrievalServiceInitializer}
     * (a {@code ServletContextListener} that fires after {@code Engine#initialize} returns),
     * and the bundle + briefing assemblers are derived from it at that same seam
     * ({@code WikiEngine.patchContextRetrievalService}). Grouped so the whole trio is
     * installed atomically, exactly once, via {@link Services#installRetrieval}.
     */
    public record RetrievalServices(
        ContextRetrievalService contextRetrievalService,
        BundleAssemblyService bundleAssemblyService,
        BriefingAssemblyService briefingAssemblyService
    ) {}
```

- [ ] **Step 4: Update the four `KnowledgeSubsystemFactory` construction sites**

4a. Add the import: `import java.util.concurrent.atomic.AtomicReference;` (same ordering note as 3a).

4b. In `create(…)` (site ~line 280): the arg list currently ends with seven lines for positions 17–25. Replace this exact block:

```java
            /*contextRetrievalService=*/     null,
            /*forAgentProjectionService=*/   null,
            /*bootstrapEntityExtractionIndexer=*/ null,
            /*kgInclusionPolicy=*/           null,
            /*reconciliationJobRunner=*/     null,
            /*retrievalQualityRunner=*/      null,
            /*kgCurationOps=*/               curation,
            // Derived from contextRetrievalService (null here); built post-startup at the
            // same seam — see BundleServiceWiring / WikiEngine.patchContextRetrievalService.
            /*bundleAssemblyService=*/       null,
            // Derived from the bundle service; built at the same post-startup seam —
            // see BriefingServiceWiring / WikiEngine.patchContextRetrievalService.
            /*briefingAssemblyService=*/     null
        );
```

with:

```java
            /*forAgentProjectionService=*/   null,
            /*bootstrapEntityExtractionIndexer=*/ null,
            /*kgInclusionPolicy=*/           null,
            /*reconciliationJobRunner=*/     null,
            /*retrievalQualityRunner=*/      null,
            /*kgCurationOps=*/               curation,
            // Empty set-once holder; the retrieval trio (context retrieval + bundle +
            // briefing) is installed post-startup by WikiEngine.patchContextRetrievalService.
            /*retrieval=*/                   new AtomicReference<>()
        );
```

Also update the "Phase 8 Ckpt 1.5" comment block just above this return (the one saying "They start null here; WikiEngine rebuilds the Services record with the live instances…"): change its last sentence to say the five post-construction managers are filled in by the boot rebuild while the retrieval trio is installed later into the shared `retrieval` holder by `patchContextRetrievalService`.

4c. In `knowledgeDisabledServices(…)` (site ~line 325): replace the tail

```java
            /*contextRetrievalService=*/     null,
            /*forAgentProjectionService=*/   null,
            /*bootstrapEntityExtractionIndexer=*/ null,
            /*kgInclusionPolicy=*/           null,
            /*reconciliationJobRunner=*/     null,
            /*retrievalQualityRunner=*/      null,
            /*kgCurationOps=*/               null,
            /*bundleAssemblyService=*/       null,
            /*briefingAssemblyService=*/     null
        );
```

with:

```java
            /*forAgentProjectionService=*/   null,
            /*bootstrapEntityExtractionIndexer=*/ null,
            /*kgInclusionPolicy=*/           null,
            /*reconciliationJobRunner=*/     null,
            /*retrievalQualityRunner=*/      null,
            /*kgCurationOps=*/               null,
            /*retrieval=*/                   new AtomicReference<>()
        );
```

4d. In `rebuildFromExisting(…)` (site ~line 391): three edits inside the arg list.
Delete the field-17 block (comment + arg):

```java
            // 17. contextRetrievalService — intentionally excluded from setManager rebuilds
            //     (audit row 17; WikiEngine.SNAPSHOT_REBUILDERS comment at line 463).
            //     Always reuse the existing instance; the ContextRetrievalServiceInitializer
            //     servlet listener owns its lifecycle and re-wires it directly.
            existing.contextRetrievalService(),
```

and replace the trailing fields 24–25 block:

```java
            // 24. bundleAssemblyService — DERIVED from contextRetrievalService and built once
            //     at the retrieval-patch seam (WikiEngine.patchContextRetrievalService). Reuse
            //     the existing instance; this side-effect-free rebuild never reconstructs it.
            existing.bundleAssemblyService(),
            // 25. briefingAssemblyService — DERIVED from the bundle service and built once at
            //     the same retrieval-patch seam. Reuse the existing instance; this
            //     side-effect-free rebuild never reconstructs it.
            existing.briefingAssemblyService()
        );
```

with:

```java
            // retrieval — carry the SAME set-once holder forward (not a copy of its value):
            // the retrieval trio is excluded from setManager rebuilds by invariant
            // (audit row 17; WikiEngine.SNAPSHOT_REBUILDERS), and sharing the reference
            // means a patch installed before OR after this rebuild is visible in both
            // snapshots with no field-copying to forget.
            existing.retrieval()
        );
```

Renumber nothing else — the `// 18.`–`// 23.` comments may keep their numbers or be renumbered 17–22; if you renumber, renumber consistently.

4e. In `readFromManagerRegistry(…)` (site ~line 540): first, immediately after the existing `final KnowledgeGraphService kgSvc = …` / `final PageManager pm = …` locals, add:

```java
        // Registry fallback for the retrieval service (test fixtures that setManager it);
        // bundle/briefing are never synthesized on this cold path — patch-seam only.
        final ContextRetrievalService ctxFromRegistry =
            engine.getManager( ContextRetrievalService.class );
```

Then in the arg list replace:

```java
            engine.getManager( ContextRetrievalService.class ),
```

(position 17) with nothing (delete the line), and replace the trailing block:

```java
            kgCurationOps,
            // bundleAssemblyService — built at the retrieval-patch seam, not on this cold
            // no-snapshot path (the stashed snapshot, patched with the bundle, is what
            // production reads). Null here is correct: surfaces degrade until the patch fires.
            null,
            // briefingAssemblyService — likewise built at the retrieval-patch seam, not on
            // this cold no-snapshot path. Null here is correct.
            null
        );
```

with:

```java
            kgCurationOps,
            // retrieval — seeded with the registry's ContextRetrievalService when present
            // (mirrors the old field-17 fallback); bundle/briefing stay null until the
            // patch seam builds them. Null seed collapses to an empty holder.
            new AtomicReference<>( ctxFromRegistry == null
                ? null
                : new KnowledgeSubsystem.RetrievalServices( ctxFromRegistry, null, null ) )
        );
```

- [ ] **Step 5: Update `KnowledgeSubsystemBridge.fromLegacyEngine` (~line 60)**

Replace the fully-null record:

```java
            return new KnowledgeSubsystem.Services(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null );
```

with (23 args; the null `retrieval` is normalized by the compact constructor):

```java
            return new KnowledgeSubsystem.Services(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null );
```

- [ ] **Step 6: Update `WikiEngine.rebuildKnowledgeSubsystemWithPostConstructionServices` (~line 1560)**

In the arg list, delete the line:

```java
            /* contextRetrievalService — set by servlet listener post-boot */      null,
```

and replace the trailing block:

```java
            base.kgCurationOps(),
            /* bundleAssemblyService — derived from contextRetrievalService, which is null
               here (wired post-boot); built later at patchContextRetrievalService */ null,
            /* briefingAssemblyService — derived from the bundle service, likewise null
               here; built later at patchContextRetrievalService */ null
        );
```

with:

```java
            base.kgCurationOps(),
            /* retrieval — carry the factory's empty set-once holder; the trio is
               installed into it later by patchContextRetrievalService */ base.retrieval()
        );
```

Also update the method's javadoc: "copying the original 16 core fields and filling in the six post-construction services" → "filling in the five post-construction services"; and the sentence about `ContextRetrievalService` being "intentionally left null here" → "the retrieval trio is installed later into the shared set-once holder by {@code patchContextRetrievalService}".

- [ ] **Step 7: Rewrite `WikiEngine.patchContextRetrievalService` (~lines 1604–1720)**

Replace the method javadoc (the block starting "Patches the live {@link WikiSubsystems} stash…") with:

```java
    /**
     * Installs the retrieval trio ({@code ContextRetrievalService} plus the bundle and
     * briefing assemblers derived from it) into the live Knowledge subsystem's set-once
     * holder.
     *
     * <p>{@code ContextRetrievalService} cannot be present at engine-boot time because it
     * is wired by {@code ContextRetrievalServiceInitializer} (a {@code ServletContextListener})
     * that fires after {@code initialize()} returns. Call this method from that listener
     * once the service is available. Because the {@code Services} record and the
     * {@code WikiSubsystems} stash hold the same {@code AtomicReference}, no record rebuild
     * or stash refresh is needed — servlet callers see the trio the moment it is installed.</p>
     *
     * <p>Idempotent: a duplicate call is refused by the set-once install (logged at WARN)
     * and, in particular, can no longer start a second {@code BundleEvalScheduler}.</p>
     *
     * <p>If the knowledge subsystem is not present (no datasource) this is a no-op.</p>
     */
```

Keep the method signature, the early return, the `confidenceOf` block, and the `bundleSvc` build EXACTLY as they are today (from `public synchronized void patchContextRetrievalService(` down through the `BundleServiceWiring.build( … confidenceOf );` statement ending at ~line 1648). Then replace EVERYTHING from the JNDI comment block (`// Scheduled retrieval-quality eval …`, ~line 1650) through the end of the method (the closing brace after the `WikiSubsystems` stash rebuild, ~line 1720) with:

```java
        final com.wikantik.api.briefing.BriefingAssemblyService briefingSvc =
            com.wikantik.knowledge.briefing.BriefingServiceWiring.build(
                bundleSvc,
                structuralIndexOrNull(),
                pageSubsystem != null ? pageSubsystem.pages() : null,
                properties );

        // Set-once install: the trio lands atomically in the live Services record (and the
        // WikiSubsystems stash, which holds the same instance — no rebuild, no re-stash).
        if ( !knowledgeSubsystem.installRetrieval(
                new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.RetrievalServices(
                    svc, bundleSvc, briefingSvc ) ) ) {
            LOG.warn( "patchContextRetrievalService called more than once; keeping the originally installed retrieval services" );
            return;
        }

        // Scheduled retrieval-quality eval (disabled unless wikantik.bundle.eval.interval.hours > 0).
        // Same JNDI datasource lookup as initKnowledgeGraph()/initAuditSubsystem() — fail-soft to
        // null (the scheduler is still built, just persists nowhere until a run tries to write).
        // Built only after a successful FIRST install, so a duplicate call cannot leak a scheduler.
        javax.sql.DataSource evalDs = null;
        try {
            final String datasource = properties.getProperty(
                    AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
            final javax.naming.Context initCtx = new javax.naming.InitialContext();
            final javax.naming.Context ctx = ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
            evalDs = ( javax.sql.DataSource ) ctx.lookup( datasource );
        } catch ( final javax.naming.NamingException e ) {
            LOG.warn( "bundle-eval scheduler: no JNDI DataSource ({}); eval persistence disabled", e.getMessage() );
        }
        final com.wikantik.knowledge.eval.BundleEvalScheduler bundleEvalScheduler =
            com.wikantik.knowledge.eval.BundleEvalWiring.build( bundleSvc, evalDs, properties );
        if ( bundleEvalScheduler != null ) {
            bundleEvalScheduler.start();
        }
    }
```

DELETED by this step (verify none of it remains): the old `// Assumes patchContextRetrievalService is called once at startup…` comment, the 25-arg `knowledgeSubsystem = new …Services( … )` reassignment, the inline `BriefingServiceWiring.build` call that lived inside that arg list, and the trailing `WikiSubsystems` stash-rebuild block (`final WikiSubsystems current = …` through `current.pageGraph() ) );` and its closing `}`). The `structuralIndexOrNull()` helper method that follows the old method stays — it is still called by the new code.

- [ ] **Step 8: Compile and run the new tests**

Run: `mvn test-compile -pl wikantik-main -q 2>&1 | tail -20`
Expected: BUILD SUCCESS (any remaining 25-arg call site will fail here — fix it before proceeding; the exhaustive site list is: factory ×4, bridge ×1, WikiEngine ×2, plus the two wikantik-rest tests handled in Task 2).

Run: `mvn test -pl wikantik-main -Dtest=KnowledgeSubsystemServicesTest -q 2>&1 | tail -20`
Expected: PASS — 6 tests, 0 failures.

- [ ] **Step 9: Run the neighboring subsystem tests**

Run: `mvn test -pl wikantik-main -Dtest='KnowledgeSubsystemFactoryTest,KnowledgeSubsystemBridge*' -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: PASS (or SKIPPED if Docker is unavailable — `KnowledgeSubsystemFactoryTest` is `@Testcontainers( disabledWithoutDocker = true )`; note which happened in your report).

Do NOT commit yet — wikantik-rest test sources still reference the 25-arg constructor; Task 2 fixes them and Task 3 commits everything after full verification.

---

### Task 2: Update the two wikantik-rest test construction sites

**Files:**
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/PageKnowledgeResourceTest.java` (~lines 503–529, `buildSubsystems`)
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceFullTest.java` (~lines 216–218)

**Interfaces:**
- Consumes: the 23-component `KnowledgeSubsystem.Services` constructor from Task 1 — order: 16 core fields, then `forAgentProjectionService`, `bootstrapEntityExtractionIndexer`, `kgInclusionPolicy`, `reconciliationJobRunner`, `retrievalQualityRunner`, `kgCurationOps`, `AtomicReference<RetrievalServices> retrieval` (a `null` retrieval is normalized to empty).
- Produces: nothing new — behavior-preserving test fixture updates.

- [ ] **Step 1: Update `PageKnowledgeResourceTest.buildSubsystems`**

Replace:

```java
            null,        // contextRetrievalService
            null,        // forAgentProjectionService
            null,        // bootstrapEntityExtractionIndexer
            null,        // kgInclusionPolicy
            null,        // reconciliationJobRunner
            null,        // retrievalQualityRunner
            ops,         // kgCurationOps
            null,        // bundleAssemblyService
            null         // briefingAssemblyService
        );
```

with:

```java
            null,        // forAgentProjectionService
            null,        // bootstrapEntityExtractionIndexer
            null,        // kgInclusionPolicy
            null,        // reconciliationJobRunner
            null,        // retrievalQualityRunner
            ops,         // kgCurationOps
            null         // retrieval (normalized to an empty set-once holder)
        );
```

- [ ] **Step 2: Update `AdminOverviewResourceFullTest`**

Replace:

```java
                new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services(
                        kg, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, runner, null, null, null ),
```

with (`runner` is `retrievalQualityRunner` — it sits at position 21 of 23 in the new shape; the trailing two nulls are `kgCurationOps` and `retrieval`):

```java
                new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services(
                        kg, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, runner, null, null ),
```

- [ ] **Step 3: Reactor-wide test-compile (catches any site this plan missed)**

Run: `mvn test-compile -q 2>&1 | tail -30` (from the repo root; no `-T`)
Expected: BUILD SUCCESS. If any module fails on a 25-arg `Services` call, apply the same mechanical transform (drop position 17 and the trailing two, append one trailing `null`) and note the extra site in your report.

- [ ] **Step 4: Run the two updated test classes**

Run: `mvn test -pl wikantik-rest -Dtest='PageKnowledgeResourceTest,AdminOverviewResourceFullTest' -q 2>&1 | tail -20`
Expected: PASS — in particular `AdminOverviewResourceFullTest` asserts `degraded.size() == 0`, its self-described "strongest possible regression signal" for wrong record positions.

Do NOT commit yet — Task 3 verifies and commits.

---

### Task 3: Full verification and the single commit

**Files:** none created/modified (fixups only if verification finds breakage).

**Interfaces:** consumes everything from Tasks 1–2; produces the final commit.

- [ ] **Step 1: Full unit build**

Run: `mvn clean install -DskipITs -q 2>&1 | tail -30` (repo root, NO `-T` — wikantik-main is parallel-flaky)
Expected: BUILD SUCCESS (this also runs `apache-rat:check` — a missing ASF header on the new test file fails here).

- [ ] **Step 2: Full integration-test reactor**

Run (sequential, from repo root; this skips re-running the ~4000 wikantik-main unit tests already covered by Step 1):

```bash
mvn clean install -Pintegration-tests -fae -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false
```

Execution notes: a single foreground call in one Bash invocation risks a wall-clock kill in this repo — run it in a background task (or delegate to a subagent) and poll. Expected: BUILD SUCCESS across all IT modules. Known acceptable flake: `EditIT#createPageAndTestEditPermissions` (CodeMirror sendKeys race) — if it is the ONLY failure, re-run that module alone to confirm it passes isolated before treating the reactor as green.

- [ ] **Step 3: Commit (exact files, no `git add -A`)**

```bash
git add \
  wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemBridge.java \
  wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
  wikantik-main/src/test/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemServicesTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageKnowledgeResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceFullTest.java \
  docs/superpowers/plans/2026-07-19-retrieval-services-set-once.md
git commit -m "refactor: set-once RetrievalServices holder replaces the 3 late-bound nullable Services fields — patch seam no longer rebuilds the 25-arg record/stash and a duplicate patch can't leak a second BundleEvalScheduler

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Risks / explicitly out of scope

- **`Services.equals()` semantics change** for the retrieval component (`AtomicReference` has identity equality). Grep found no call site comparing `Services` instances; record equality on this type is not load-bearing. No action.
- **`SNAPSHOT_REBUILDERS` invariant** (contextRetrievalService excluded from setManager rebuilds, WikiEngine ~line 463): preserved and strengthened — the shared reference makes the exclusion automatic. The comment at line ~463 may still say "audit row 17"; updating that comment text is optional polish, not required.
- Consumers of the three accessors are intentionally untouched; their source compatibility is the design's central promise and is proven by Task 2 Step 3's reactor-wide test-compile.
- No DB schema change → no migration file needed.

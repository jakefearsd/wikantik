# WikiEngine ServiceRegistry Extraction — Implementation Plan (rev. 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Move WikiEngine's late-bound service *storage* (78 `mgr_*` fields + the `TYPED_FIELD_WRITERS`/`TYPED_FIELD_READERS` dispatch maps) into a generic, map-backed `EngineServiceRegistry`, cutting WikiEngine's efferent coupling and giving new services a zero-field registration path — guarded by an ArchUnit test so the god-class cannot silently re-accrete.

**Architecture:** `WikiEngine` keeps `getManager(Class)`/`setManager(Class,T)` as public delegators (the 107 ArchUnit-frozen callers and every bridge path are untouched). Storage moves to `EngineServiceRegistry` (a single `Map<Class<?>,Object>` that references *zero* concrete service types). The hand-curated per-class snapshot-rebuild map (`SNAPSHOT_REBUILDERS`) **stays in WikiEngine verbatim** — it encodes multi-subsystem, cross-package, and deliberate-no-op behavior that no package heuristic can reproduce — and `setManager` keeps firing it. New services register via `engine.setManager(Type.class, impl)` from the owning WiringHelper: no field, no reader, no writer entry. No Guice or DI framework.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, ArchUnit, PMD (coupling ruleset already wired into the `complexity-report` profile).

## Why rev. 2 (design correction)
The original plan proposed a `SubsystemKind` package classifier to genericize the rebuild dispatch. Ground truth (`WikiEngine.SNAPSHOT_REBUILDERS`, lines 405–497) shows the mapping is hand-curated in ways package inference cannot reproduce: `ReferenceManager` rebuilds **two** subsystems (Page + PageGraph); `admin`/`ontology.runtime`/`drift`/`citation` classes are grouped under **PageGraph** and `api.eval.RetrievalQualityRunner` under **Knowledge** (cross-package); and **7 entries are deliberate no-ops** (`e -> {}`). Replacing this with inference would silently change rebuild behavior. So rev. 2 keeps `SNAPSHOT_REBUILDERS` verbatim and extracts only the storage — simpler, lower-risk, same coupling win.

## Global Constraints

- **`getManager(Class<T>)` and `setManager(Class<T>,T)` MUST remain public methods on the `WikiEngine` concrete class** with identical signatures and identical observable behavior (null-return, boot-vs-populated warn/debug distinction, post-boot rebuild firing). 107 ArchUnit-frozen callers and all bridge paths depend on them. Do NOT touch `wikantik-main/src/test/resources/archunit_store/`.
- **`SNAPSHOT_REBUILDERS` stays byte-for-byte as-is** (WikiEngine.java 386–497). `setManager` must fire it exactly as today. `ContextRetrievalService` remains excluded from it (see the existing `setManager` comment) — a rebuild would clobber the post-boot patch.
- **`EngineServiceRegistry` lives in `wikantik-main`** (`com.wikantik.core.registry`) and MUST NOT reference any concrete service type — it stores `Object` keyed by `Class<?>`.
- **No Guice / DI framework.** Plain Java wiring only.
- Every task ends green on its focused test; the final task gates on the full reactor + IT build.
- Work on `main` (repo convention). Commit after every task; stage files by exact name. End commit messages with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## File Structure

- **Create** `wikantik-main/src/main/java/com/wikantik/core/registry/EngineServiceRegistry.java` — generic map-backed store. No concrete service-type references.
- **Modify** `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — delete the 78 `mgr_*` fields (507–596), the `TYPED_FIELD_WRITERS`/`TYPED_FIELD_READERS` maps + their static block (206–384) and `readTypedField`/`writeTypedField`; add one `EngineServiceRegistry serviceRegistry` field; make `getManager`/`setManager`/`initComponent` delegate. **Keep** `SNAPSHOT_REBUILDERS` (386–497) and the constructor.
- **Modify** the 3 WiringHelpers — replace `engine.registerX(svc)` with `engine.setManager(X.class, svc)`.
- **Modify** `DecompositionArchTest.java` — anti-drift rule + stale-comment fix.
- **Create** `docs/adr/0008-late-bound-service-registration.md`; append to `CONTEXT.md`.

---

## Task 1: `EngineServiceRegistry` (generic storage)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/core/registry/EngineServiceRegistry.java`
- Test: `wikantik-main/src/test/java/com/wikantik/core/registry/EngineServiceRegistryTest.java`

**Interfaces (Produces):**
- `<T> void put( Class<T> type, T impl )` — store (overwrites; tracks the key as known even if `impl` is null).
- `<T> T get( Class<T> type )` — unchecked-cast lookup; `null` if absent.
- `boolean isKnownType( Class<?> type )` — has this type ever been written.

**Design notes:** `IdentityHashMap<Class<?>, Object>` (exact-class identity, matching the former `IdentityHashMap` dispatch tables — no subtype lookup). References no concrete service type. No rebuild logic (that stays in WikiEngine's `SNAPSHOT_REBUILDERS`).

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.core.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineServiceRegistryTest {

    interface Foo {}
    static final class FooImpl implements Foo {}

    @Test
    void getReturnsRegisteredInstanceAndNullWhenAbsent() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        final FooImpl impl = new FooImpl();
        reg.put( Foo.class, impl );
        assertSame( impl, reg.get( Foo.class ) );
        assertNull( reg.get( Runnable.class ) );
    }

    @Test
    void putOverwritesPriorValue() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        final FooImpl a = new FooImpl();
        final FooImpl b = new FooImpl();
        reg.put( Foo.class, a );
        reg.put( Foo.class, b );
        assertSame( b, reg.get( Foo.class ) );
    }

    @Test
    void isKnownTypeTracksEverWrittenIncludingNullValue() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        assertFalse( reg.isKnownType( Foo.class ) );
        reg.put( Foo.class, null );      // known even though value is null
        assertTrue( reg.isKnownType( Foo.class ) );
        assertNull( reg.get( Foo.class ) );
    }

    @Test
    void identitySemanticsNoSubtypeLookup() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        final FooImpl impl = new FooImpl();
        reg.put( FooImpl.class, impl );
        assertNull( reg.get( Foo.class ) );   // interface key was never written
        assertSame( impl, reg.get( FooImpl.class ) );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-main -Dtest=EngineServiceRegistryTest test -q`
Expected: FAIL — `EngineServiceRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation** (copy the Apache license header from WikiEngine.java lines 1–18)

```java
package com.wikantik.core.registry;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Generic, map-backed store for WikiEngine's late-bound services. Replaces the
 * 78 typed {@code mgr_*} fields and the two 78-entry static dispatch maps
 * (TYPED_FIELD_WRITERS/TYPED_FIELD_READERS) that used to live on
 * {@link com.wikantik.WikiEngine}.
 *
 * <p>Holds {@code Object} keyed by exact {@code Class<?>} (identity semantics,
 * matching the former IdentityHashMap dispatch). Deliberately references no
 * concrete service type — the coupling this extraction removes does not come
 * back here. Snapshot-rebuild policy stays in WikiEngine (SNAPSHOT_REBUILDERS).</p>
 */
public final class EngineServiceRegistry {

    private final Map<Class<?>, Object> services = new IdentityHashMap<>( 128 );
    private final Map<Class<?>, Boolean> everWritten = new IdentityHashMap<>( 128 );

    /** Stores {@code impl} under {@code type}, overwriting any prior value; marks the key known. */
    public <T> void put( final Class<T> type, final T impl ) {
        services.put( type, impl );
        everWritten.put( type, Boolean.TRUE );
    }

    /** Returns the instance registered under {@code type}, or {@code null} if none. */
    @SuppressWarnings( "unchecked" )
    public <T> T get( final Class<T> type ) {
        return ( T ) services.get( type );
    }

    /** Whether {@code type} has ever been written (even with a null value). */
    public boolean isKnownType( final Class<?> type ) {
        return everWritten.containsKey( type );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl wikantik-main -Dtest=EngineServiceRegistryTest test -q`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/core/registry/EngineServiceRegistry.java \
        wikantik-main/src/test/java/com/wikantik/core/registry/EngineServiceRegistryTest.java
git commit -m "feat(engine): add generic EngineServiceRegistry (map-backed service storage)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Route WikiEngine storage through the registry (delete 78 fields + 2 maps)

The mechanical core: delete the 78 `mgr_*` fields, the `TYPED_FIELD_WRITERS`/`TYPED_FIELD_READERS` fields + their static block, and `readTypedField`/`writeTypedField`; delegate `getManager`/`setManager`/`initComponent` to the registry. **`SNAPSHOT_REBUILDERS` and everything about it stays untouched.**

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`
- Test: new parity test + existing `WikiEngineTest`, `*SubsystemBridgeTest`, `*SubsystemFactoryTest`.

**Interfaces:**
- Consumes: `EngineServiceRegistry` (Task 1).
- Produces: `public com.wikantik.core.registry.EngineServiceRegistry serviceRegistry()` accessor (used by Task 3).

**Pre-req verification (do this first):** confirm the former `TYPED_FIELD_READERS` keyset equals the `SNAPSHOT_REBUILDERS` keyset plus exactly `ContextRetrievalService`. This underpins the warn/debug preservation below.
```bash
cd wikantik-main/src/main/java/com/wikantik
grep -oE "r\.put\( ([A-Za-z0-9_.]+)\.class" WikiEngine.java | sed 's/r.put( //' | sort > /tmp/readers.txt
grep -oE "s\.put\( ([A-Za-z0-9_.]+)\.class" WikiEngine.java | sed 's/s.put( //' | sort > /tmp/rebuilders.txt
diff /tmp/readers.txt /tmp/rebuilders.txt
```
Expected: the only difference is `com.wikantik.api.knowledge.ContextRetrievalService` present in readers, absent in rebuilders. If other differences appear, add each extra reader-only class to the `isKnownManagerType` helper below.

- [ ] **Step 1: Write the parity test (guards the refactor)**

```java
package com.wikantik.core.registry;

import com.wikantik.WikiEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the getManager/setManager contract across the storage refactor: a value
 * set via setManager is returned by getManager; unknown types return null.
 * This is a characterization test — it must pass on the pre-refactor code and
 * stay green through the change.
 */
class WikiEngineRegistryParityTest {

    interface Probe {}

    @Test
    void setManagerThenGetManagerRoundTrips() {
        final WikiEngine engine = com.wikantik.TestEngine.build();
        final Probe p = new Probe() {};
        engine.setManager( Probe.class, p );
        assertSame( p, engine.getManager( Probe.class ) );
    }

    @Test
    void getManagerUnknownReturnsNull() {
        final WikiEngine engine = com.wikantik.TestEngine.build();
        assertNull( engine.getManager( Runnable.class ) );
    }

    @Test
    void managerRegisteredDuringBootIsRetrievable() {
        final WikiEngine engine = com.wikantik.TestEngine.build();
        // PageManager is wired during boot; must resolve after the refactor too.
        assertNotNull( engine.getManager( com.wikantik.api.managers.PageManager.class ) );
    }
}
```
Note: match `TestEngine.build()` to how `WikiEngineTest` obtains an engine; `TestEngine.build()` exists (verified). If a probe interface set via setManager isn't retrievable pre-refactor because `Probe` has no typed field, use a *real* known manager type for the round-trip assertion instead (e.g. set and get `com.wikantik.diff.DifferenceManager.class` with a mock) — verify which behavior the current code exhibits in Step 2 and keep the test that reflects pre-refactor truth.

- [ ] **Step 2: Run the parity test on CURRENT code (baseline green)**

Run: `mvn -pl wikantik-main -Dtest=WikiEngineRegistryParityTest test -q`
Expected: PASS on pre-refactor code. **If `setManagerThenGetManagerRoundTrips` fails** (because `Probe` isn't in the static typed-field table, so pre-refactor `setManager`/`getManager` can't store an arbitrary type), that reveals a real semantic: the *old* code only stored known types. Decide: the new generic registry stores ANY type (a superset — safe). Adjust the test to use a known manager type for the round-trip so it characterizes real pre-refactor behavior, re-run, confirm green, then proceed.

- [ ] **Step 3: Add the registry field + accessor + known-type helper**

```java
private final com.wikantik.core.registry.EngineServiceRegistry serviceRegistry =
        new com.wikantik.core.registry.EngineServiceRegistry();

/** The late-bound service registry. New services register here via setManager — never as a WikiEngine field. */
public com.wikantik.core.registry.EngineServiceRegistry serviceRegistry() { return serviceRegistry; }

/**
 * The set of manager types WikiEngine knows about — used only to distinguish
 * "known type, not yet populated" (debug, expected during boot) from "genuinely
 * unknown type" (warn). Equals the former TYPED_FIELD_READERS keyset:
 * every SNAPSHOT_REBUILDERS key plus ContextRetrievalService (excluded from
 * rebuilders by design; see setManager).
 */
private static boolean isKnownManagerType( final Class<?> t ) {
    return SNAPSHOT_REBUILDERS.containsKey( t )
        || t == com.wikantik.api.knowledge.ContextRetrievalService.class;
}
```

- [ ] **Step 4: Rewrite getManager / setManager / initComponent to delegate**

`getManager` (preserve the coreSubsystem fallthrough and the warn/debug distinction verbatim):
```java
public < T > T getManager( final Class< T > manager ) {
    final T fromRegistry = serviceRegistry.get( manager );
    if ( fromRegistry != null ) return fromRegistry;

    if ( coreSubsystem != null ) {
        if ( manager.isInstance( coreSubsystem.systemPageRegistry() ) )    return ( T ) coreSubsystem.systemPageRegistry();
        if ( manager.isInstance( coreSubsystem.recentArticlesManager() ) ) return ( T ) coreSubsystem.recentArticlesManager();
        if ( manager.isInstance( coreSubsystem.blogManager() ) )           return ( T ) coreSubsystem.blogManager();
    }

    if ( ! isKnownManagerType( manager ) ) {
        LOG.warn( "getManager({}) returned null — class has no registered service", manager.getName() );
    } else {
        LOG.debug( "getManager({}) returned null — typed service not yet populated (called before its initComponent)", manager.getName() );
    }
    return null;
}
```
`setManager` (store in registry, then fire the UNCHANGED SNAPSHOT_REBUILDERS — keep the existing explanatory comment block verbatim):
```java
public < T > void setManager( final Class< T > clazz, final T manager ) {
    serviceRegistry.put( clazz, manager );
    final java.util.function.Consumer<WikiEngine> rebuilder = SNAPSHOT_REBUILDERS.get( clazz );
    if ( rebuilder != null ) rebuilder.accept( this );
}
```
`initComponent` (~1113): replace `writeTypedField( componentClass, component );` with `serviceRegistry.put( componentClass, component );`. (The old `writeTypedField` did NOT fire a rebuild; `put` doesn't either — behavior identical.)

- [ ] **Step 5: Delete the dead machinery**

Delete from `WikiEngine.java`: the `TYPED_FIELD_WRITERS` and `TYPED_FIELD_READERS` field declarations + the static block that populates them (lines ~205–384, i.e. the FIRST static block ending at `TYPED_FIELD_READERS = r;`), all `private ... mgr_* ;` field declarations (507–596), and `readTypedField`/`writeTypedField`. **Do NOT delete the second static block that builds `SNAPSHOT_REBUILDERS` (386–497), the `SNAPSHOT_REBUILDERS` field, or its javadoc.** Confirm:
```bash
grep -nE "mgr_|TYPED_FIELD_|readTypedField|writeTypedField" wikantik-main/src/main/java/com/wikantik/WikiEngine.java
```
Expected: no matches.
```bash
grep -c "SNAPSHOT_REBUILDERS" wikantik-main/src/main/java/com/wikantik/WikiEngine.java
```
Expected: still present (field + static block + getManager helper + setManager use).

- [ ] **Step 6: Run parity + bridge/factory suites**

Run: `mvn -pl wikantik-main -Dtest=WikiEngineRegistryParityTest,WikiEngineTest,'*SubsystemBridgeTest','*SubsystemFactoryTest' test -q`
Expected: PASS. A `*SubsystemBridgeTest` failure means a hot-swap rebuild broke — recheck that `SNAPSHOT_REBUILDERS` and `setManager`'s firing of it were left intact.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/test/java/com/wikantik/core/registry/WikiEngineRegistryParityTest.java
git commit -m "refactor(engine): store late-bound services in EngineServiceRegistry

Delete 78 mgr_* fields + the TYPED_FIELD_WRITERS/READERS dispatch maps;
getManager/setManager/initComponent delegate to the registry. SNAPSHOT_REBUILDERS
and setManager's rebuild firing are unchanged, preserving hot-swap behavior.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Retire the `register*` / `setHybrid*` setters into the WiringHelpers

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — delete the 33 `register*` methods (~1209–1345) and the 5 `setHybrid*`/`setEntityExtractionListener` late-bind setters (~629–657). Verify each forwards to `setManager` and has only WiringHelper callers before deleting.
- Modify: `search/subsystem/SearchWiringHelper.java`, `pagegraph/subsystem/PageGraphWiringHelper.java`, `knowledge/subsystem/KnowledgeWiringHelper.java`.

**Mechanical rule:** `engine.registerX(svc)` → `engine.setManager(X.class, svc)`, using the *same* class key(s) the old setter passed to `setManager`. Multi-key setters (`registerChunkVectorIndex` → `ChunkVectorIndex` + `InMemoryChunkVectorIndex`; `registerGraphNeighborIndex` → `GraphNeighborIndex` + `InMemoryGraphNeighborIndex`) become two `setManager` calls — copy both keys from the old method body (WikiEngine.java 1213–1217, 1236–1240). No `SubsystemKind` — `setManager` already fires the correct per-class rebuild via `SNAPSHOT_REBUILDERS`.

- [ ] **Step 1: Enumerate call sites**

```bash
grep -rnE "engine\.(register[A-Z]|setHybrid|setEntityExtractionListener)" \
  wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java \
  wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphWiringHelper.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeWiringHelper.java
```
Record each. For each, open the matching `registerX`/`setHybridX` method in WikiEngine (1209–1345, 629–657) and copy the exact `setManager(KEY.class, …)` call(s) it makes.

- [ ] **Step 2: Rewrite the call sites**

Example (SearchWiringHelper):
```java
// before:
engine.registerHybridSearchService( hybridSearchService );
engine.registerChunkVectorIndex( inMemoryChunkVectorIndex );
// after:
engine.setManager( com.wikantik.search.hybrid.HybridSearchService.class, hybridSearchService );
engine.setManager( com.wikantik.search.hybrid.ChunkVectorIndex.class, inMemoryChunkVectorIndex );
engine.setManager( com.wikantik.search.hybrid.InMemoryChunkVectorIndex.class, inMemoryChunkVectorIndex );
```
Do the same in PageGraphWiringHelper and KnowledgeWiringHelper, copying keys verbatim from each old setter body. `setManager` is already public on WikiEngine — no new import needed.

- [ ] **Step 3: Delete the setters + verify no callers remain**

```bash
grep -rnE "\.(register(EmbeddingIndexService|ChunkVectorIndex|QueryEmbedder|HybridSearchService|BootstrapEmbeddingIndexer|GraphNeighborIndex|GraphProximityScorer|QueryEntityResolver|PageMentionsLoader|GraphRerankStep|RetrievalQualityRunner|PageVerificationDao|TrustedAuthorsDao|StructuralIndexService|StructuralIndexEventListener|PageGraphService|KgInclusionPolicy|KgClusterPolicyRepository|KgExcludedPagesRepository|ReconciliationJobRunner|ForAgentProjectionService|ContentIndexRebuildService|ChunkEntityMentionRepository|AsyncEntityExtractionListener|BootstrapEntityExtractionIndexer)|setHybridIndexListener|setHybridQueryEmbedder|setHybridBootstrapIndexer|setEntityExtractionListener)" --include=*.java .
```
Expected: zero `engine.`-qualified callers. Then delete the methods from WikiEngine. (If a `getHybridIndexListener`-style getter reads a former setter's field, it now reads via `getManager` — check and adjust; `getHybridIndexListener` at 633 should become `return getManager(AsyncEmbeddingIndexListener.class);` if it referenced the deleted field.)

- [ ] **Step 4: Compile + test wiring + search/knowledge/pagegraph suites**

Run: `mvn -pl wikantik-main -Dtest='*WiringHelper*','Search*Test','*KnowledgeSubsystem*','*PageGraphSubsystem*',WikiEngineRegistryParityTest test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphWiringHelper.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeWiringHelper.java
git commit -m "refactor(engine): retire register*/setHybrid* setters; WiringHelpers call setManager

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Anti-drift ArchUnit guard + stale-comment fix

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/architecture/DecompositionArchTest.java`

- [ ] **Step 1: Add the guard rules**

```java
/**
 * R-5: WikiEngine must not re-accrete late-bound service state.
 *
 * <p>After the 2026-07-02 storage extraction, late-bound services live in
 * {@link com.wikantik.core.registry.EngineServiceRegistry}. New services
 * register via {@code engine.setManager(Type.class, impl)} from the owning
 * *WiringHelper — never as a {@code mgr_*} field or {@code register*} setter on
 * WikiEngine. This freezes that surface so the god class cannot silently regrow
 * (it grew 1909 -> 2337 LOC after the original decomposition "completed").
 * See docs/adr/0008-late-bound-service-registration.md.</p>
 */
@ArchTest
static final ArchRule wikiengine_holds_no_mgr_fields =
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields()
        .that().areDeclaredInClassesThat().haveSimpleName( "WikiEngine" )
        .should().haveNameMatching( "mgr_.*" )
        .as( "WikiEngine must hold no mgr_* service fields — store late-bound "
           + "services in EngineServiceRegistry. See ADR-0008." );

@ArchTest
static final ArchRule wikiengine_has_no_register_setters =
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods()
        .that().areDeclaredInClassesThat().haveSimpleName( "WikiEngine" )
        .should().haveNameMatching( "register[A-Z].*" )
        .as( "WikiEngine must expose no register* service setters — call "
           + "engine.setManager(Type.class, impl) from a *WiringHelper. See ADR-0008." );
```

- [ ] **Step 2: Run to verify it passes**

Run: `mvn -pl wikantik-main -Dtest=DecompositionArchTest test -q`
Expected: PASS (Tasks 2–3 removed all `mgr_*` fields and `register*` methods). A FAILURE means one was missed — remove it, don't weaken the rule.

- [ ] **Step 3: Fix the stale Phase-9 comment + dead doc refs**

In `DecompositionArchTest`'s R-2 javadoc, replace "the store shrinks monotonically until Phase 9 deletes both the registry and this rule (replacing it with one that bans the API entirely)" with: "The migration concluded at Phase 12 (2026-05-14) with getManager/setManager retained as the stable service-locator API on the WikiEngine concrete class; this freeze is permanent, not transitional. Service storage moved to EngineServiceRegistry on 2026-07-02 (ADR-0008)." Replace the two dead spec/plan path references (`docs/superpowers/specs/2026-05-05-...`, `docs/superpowers/plans/2026-05-08-decomposition-phase-10.md`, deleted in 794aee0114) with `docs/adr/0008-late-bound-service-registration.md`.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/architecture/DecompositionArchTest.java
git commit -m "test(arch): R-5 guard against WikiEngine service-field/setter re-accretion; fix stale Phase-9 comment

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Document the path forward + measure + full gate

**Files:**
- Create: `docs/adr/0008-late-bound-service-registration.md`
- Modify: `CONTEXT.md`

- [ ] **Step 1: Write ADR-0008**

```markdown
# ADR-0008: Late-bound service registration via EngineServiceRegistry

**Status:** accepted (2026-07-02)

## Context
The wikantik-main decomposition (Phases 0–12, concluded 2026-05-14) left
getManager/setManager on WikiEngine as a stable service-locator, backed by 78
typed mgr_* fields + two 78-entry static dispatch maps. Each new late-bound
service needed 4–5 coordinated WikiEngine edits, and WikiEngine regrew
1909 -> 2337 LOC post-"completion". PMD flagged WikiEngine as the top efferent-
coupling hotspot (CBO 143).

## Decision
Service *storage* moves to `com.wikantik.core.registry.EngineServiceRegistry` —
a generic `Map<Class<?>,Object>` referencing no concrete service type.
getManager/setManager stay on WikiEngine as thin delegators (the 107 ArchUnit-
frozen callers and bridge paths are unchanged). The hand-curated per-class
snapshot-rebuild map (SNAPSHOT_REBUILDERS) stays in WikiEngine verbatim — it
encodes multi-subsystem, cross-package, and deliberate-no-op behavior that no
package heuristic reproduces. No Guice / DI framework; revisit only on a
demonstrated large benefit.

## How to add a late-bound service (the path forward)
1. Construct it in the owning *WiringHelper (as today).
2. Register it: `engine.setManager(MyService.class, impl);`
3. Consume via `engine.getManager(MyService.class)` or, preferably, the subsystem
   Services record.
Do NOT add a mgr_* field or register* setter to WikiEngine — ArchUnit R-5
(wikiengine_holds_no_mgr_fields, wikiengine_has_no_register_setters) fails the
build. Only add a SNAPSHOT_REBUILDERS entry if the service is hot-swapped in
tests and its subsystem snapshot must refresh (most services need nothing).

## Consequences
- WikiEngine CBO/LOC drop; new services need no field/reader/writer edit.
- Retiring the 8 bridges entirely remains a conditional future step gated on a
  Maven split of wikantik-main — out of scope here.
```

- [ ] **Step 2: Append to CONTEXT.md**

```markdown
### Adding a late-bound service
Register via `engine.setManager(Type.class, impl)` from the owning *WiringHelper.
Never add a `mgr_*` field or `register*` setter to WikiEngine — ArchUnit R-5
enforces this. Storage lives in EngineServiceRegistry; the per-class hot-swap
rebuild policy is WikiEngine.SNAPSHOT_REBUILDERS. See docs/adr/0008-late-bound-service-registration.md.
```

- [ ] **Step 3: Re-measure coupling (record in the ADR)**

```bash
java -cp "$(cat /tmp/claude-1000/-home-jakefear-source-jspwiki/*/scratchpad/pmd.cp)" \
  net.sourceforge.pmd.cli.PmdCli check -d wikantik-main/src/main/java \
  -R build-support/pmd-coupling-ruleset.xml -f text --no-cache 2>/dev/null | grep -i WikiEngine
```
Record the new WikiEngine CBO in ADR-0008 (baseline was 143).

- [ ] **Step 4: Full reactor gate (unit + IT)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. Mandatory cross-module gate (rest/tools/admin-mcp/knowledge all call `engine.getManager` — delegation must hold at the wire level).

- [ ] **Step 5: Commit**

```bash
git add docs/adr/0008-late-bound-service-registration.md CONTEXT.md
git commit -m "docs(adr): ADR-0008 late-bound service registration + measured coupling drop

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review Notes
- **Behavior preservation:** SNAPSHOT_REBUILDERS + setManager's firing of it are untouched (Task 2 Steps 4–5); getManager keeps the coreSubsystem fallthrough + warn/debug distinction via `isKnownManagerType` (= former reader keyset); initComponent's no-rebuild write preserved. Parity test brackets the change (Task 2 Steps 1–2, 6).
- **Coupling win:** removes 78 typed fields + 78 casts (writer lambdas) + 156 class-literal map entries; only SNAPSHOT_REBUILDERS' class literals remain. Field/cast removal is the dominant CBO driver.
- **Anti-drift:** generic storage (no per-service field/reader/writer) + setManager path + ArchUnit R-5 + ADR/CONTEXT convention. Residual: an optional 1-line SNAPSHOT_REBUILDERS entry only for hot-swapped services.
- **No SubsystemKind, no Guice.** Registry references zero concrete types.
- **Type consistency:** registry API (`put`/`get`/`isKnownType`) used consistently across Tasks 1–2; `serviceRegistry()` accessor produced in Task 2, consumed nowhere else needed (Task 3 uses `setManager`).

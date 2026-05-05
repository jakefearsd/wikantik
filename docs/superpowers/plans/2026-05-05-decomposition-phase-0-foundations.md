# Phase 0: Foundations — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** ready
**Estimated effort:** ~2 days
**Goal:** put the rails down before the migration starts. After Phase 0, regressions are caught at build time and progress is measurable.

## Scope

In this phase:
1. Adopt **ArchUnit** as a test-scoped dependency in `wikantik-main`.
2. Add three frozen ArchUnit rules that lock in current state:
   - **R-1 (later)** Layered architecture — **deferred to Phase 1** when Knowledge becomes the first defined layer. (The spec calls for this in Phase 0 but the rule is vacuous without at least one extracted subsystem; setting it up now would be empty noise.)
   - **R-2** No new `engine.getManager(...)` outside approved bridge classes.
   - **R-3** No new `TestEngine` references in unit-test packages.
3. Create `bin/metrics/measure.sh` to capture decomposition progress as JSON.
4. Capture the baseline at `bin/metrics/decomposition-progress.json`.
5. Document the **bridge pattern** as a runnable example (an appendix to the spec doc).

Out of scope:
- Any actual extraction of subsystems. That's Phase 1.
- Adding `wikantik-archtest` as its own Maven module. The single-developer cost/benefit favours putting tests in `wikantik-main/src/test/java/com/wikantik/architecture/` for now. If we later need cross-module rules we promote.
- ArchUnit's "freeze" feature with on-disk state files. We use `FreezingArchRule` with a properties-driven store (the standard pattern); the violation store lives at `wikantik-main/src/test/resources/archunit_store/`.

## File-by-file walk

### 1. Maven dependency

**`wikantik-bom/pom.xml`** — add ArchUnit to dependency-management so versions are pinned in one place:

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.4.1</version>
  <scope>test</scope>
</dependency>
```

(Confirm 1.4.1 is the current stable. Check before adding.)

**`wikantik-main/pom.xml`** — declare the dep without version (BOM resolves):

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <scope>test</scope>
</dependency>
```

### 2. ArchUnit test class

**New file:** `wikantik-main/src/test/java/com/wikantik/architecture/DecompositionArchTest.java`

Structure:

```java
@AnalyzeClasses(
    packages = "com.wikantik",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
class DecompositionArchTest {

    /** R-2: only approved bridge classes may call WikiEngine#getManager. */
    @ArchTest
    static final ArchRule no_new_get_manager_callers = freeze(
        noClasses()
            .that().resideOutsideOfPackages(
                "com.wikantik",                    // WikiEngine itself
                "com.wikantik.rest"                // RestServletBase + servlet bridges
            )
            .should().callMethod(WikiEngine.class, "getManager", Class.class)
            .as("Outside the bridge layer, services receive collaborators via "
              + "constructor parameters; do not reach back through "
              + "WikiEngine.getManager(). See "
              + "docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md")
    );

    /** R-3: production-side classes must not depend on TestEngine. */
    @ArchTest
    static final ArchRule no_test_engine_in_production_imports = ...;
        // (R-3 narrows to: subsystem-internal tests should not need TestEngine.
        //  In Phase 0 there are no extracted subsystems, so this rule is
        //  scoped to: production code does not reference TestEngine.)
}
```

Key design choices:
- `freeze(...)` wraps each rule so existing violations are baselined into a `wikantik-main/src/test/resources/archunit_store/<rule-name>.txt` file. New violations fail; resolved violations require explicit removal from the store (so we can't accidentally lower the bar).
- Bridge classes are `com.wikantik.WikiEngine` itself and the abstract `com.wikantik.rest.RestServletBase` (which servlets extend; servlets reach for managers via the engine until ApiSubsystem is extracted in Phase 8).
- `ImportOption.DoNotIncludeTests.class` means tests don't trip these rules. Test code is fine to use whatever is convenient until subsystem-internal tests start landing in Phase 1.

### 3. Metrics script

**New file:** `bin/metrics/measure.sh`

Outputs a JSON object capturing:
- `loc_main` — `find wikantik-main/src/main/java -name '*.java' | xargs cat | wc -l`
- `registered_managers` — count of unique types in `WikiEngine.managers.put(...)` calls
- `get_manager_callers` — count of `engine.getManager` / `getEngine().getManager` callsites in `--include='*.java'` excluding `WikiEngine.java` and `RestServletBase.java`
- `god_classes_over_800` — count of `*.java` files in `wikantik-main/src/main/java` with > 800 lines
- `god_class_top10` — array of `{path, loc}` for top 10 by line count
- `test_engine_references` — count of `TestEngine` class references in production code only

Reads existing JSON if present and merges, so successive phases append rather than overwrite. Output schema:

```json
{
  "baseline_2026_05_05": {
    "loc_main": 76212,
    "registered_managers": 42,
    "get_manager_callers": 1070,
    "god_classes_over_800": 9,
    "god_class_top10": [...],
    "test_engine_references_in_production": 0
  }
}
```

### 4. Baseline capture

Run `bin/metrics/measure.sh > bin/metrics/decomposition-progress.json` once, commit.

### 5. Bridge pattern appendix

Add to the spec doc (already written) a worked example showing how an in-flight Phase 1 (Knowledge) wiring keeps both lookup mechanisms alive:

```java
// In WikiEngine.initialize() during Phase 1:
final KnowledgeSubsystem.Services kg = KnowledgeSubsystemFactory.create(deps);

// Bridge: legacy callers still work...
managers.put(KnowledgeGraphService.class, kg.kgService());
managers.put(KgProposalJudgeService.class, kg.judgeService());
// ...

// New callers use kg.kgService() directly via injection.
```

When the last `getManager(KgXxx.class)` callsite is migrated, the bridge `managers.put(...)` line is deleted in the same commit.

## Test plan

1. After adding the ArchUnit dep + test class, run `mvn test -pl wikantik-main -Dtest='DecompositionArchTest'`. Expected: all rules **pass** (because `freeze` baselines the current state).
2. Manually verify the baseline by introducing a bogus violation in a non-bridge class (e.g., add `getEngine().getManager(SomeClass.class)` in `wikantik-main/src/main/java/com/wikantik/util/TextUtil.java` temporarily). Re-run the test; expected: **fail** with a clear message naming the new violation.
3. Revert the bogus change.
4. Run `bin/metrics/measure.sh` and confirm output matches the baseline numbers in §"Concrete starting position" of the spec (76,212 / 42 / 1,070 / 9).
5. Run the full IT suite: `mvn clean install -Pintegration-tests -fae`. Expected: BUILD SUCCESS. Phase 0 must not regress anything.

## Rollback strategy

Phase 0 is additive — no production code changes, no test deletions. Rollback is `git revert` of the Phase 0 commit(s). The risk is that ArchUnit catches a violation we can't immediately address; in that case `freeze` should accommodate it without manual intervention.

## Commit shape

One commit, conventional-commits style:

```
test(arch): adopt ArchUnit + baseline decomposition metrics (phase 0)

ArchUnit test-scoped dep added to wikantik-bom + wikantik-main.
DecompositionArchTest in wikantik-main/src/test/java/com/wikantik/
architecture/ holds the rules:

  R-2: no new engine.getManager() callers outside the bridge classes
       (WikiEngine, RestServletBase). Frozen at the current 1,070
       call sites; new violations fail the build.
  R-3: production code must not reference TestEngine.

bin/metrics/measure.sh captures decomposition progress as JSON.
bin/metrics/decomposition-progress.json holds the baseline.

Spec: docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md
```

## Done when

- `mvn test -pl wikantik-main -Dtest='DecompositionArchTest'` is green.
- `mvn clean install -Pintegration-tests -fae` is green (zero regression).
- `bin/metrics/decomposition-progress.json` matches the spec's baseline numbers.
- The bogus-violation manual test described above demonstrates the build now fails on new offenders.
- Phase 1 plan (`docs/superpowers/plans/2026-XX-YY-decomposition-phase-1-knowledge-subsystem.md`) can begin against this foundation.

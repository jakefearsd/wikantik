# Browser-Suite Safe Parallelism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run an audited cluster of read-only/smoke Selenide browser test classes in parallel to shave ~15–30s off the `custom-jdbc` IT module, with zero added flakiness (opt-in; everything else stays serial).

**Architecture:** Add a `junit-platform.properties` that *enables* JUnit-5 parallel execution but defaults every class to `same_thread` (suite unchanged). Then annotate only audited-safe read-only classes with `@Execution(CONCURRENT)`; Selenide's thread-local WebDriver gives each its own browser. Bounded at parallelism 3.

**Tech Stack:** JUnit 5 parallel execution, Selenide (thread-local WebDriver), Maven failsafe (`custom-jdbc` module runs the `wikantik-selenide-tests` classes), `bin/run-tests.sh --module custom-jdbc`.

**Spec:** `docs/superpowers/specs/2026-06-04-browser-suite-safe-parallelism-design.md`

**Verified context:**
- The browser classes live in `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/` and are executed by the `custom-jdbc` IT module (one Cargo Tomcat + one Postgres, shared).
- They extend `WithIntegrationTestSetup`, whose `@BeforeAll` calls `Selenide.closeWebDriver()` (thread-local — won't disturb a sibling running on another thread).
- `src/main/resources` already exists in the selenide module; there is NO existing `junit-platform.properties`.
- **No concurrent Maven builds** — run verification one command at a time (concurrent Maven corrupts surefire/failsafe forks).

---

## Task 1: Add the parallel-execution config (inert — default serial)

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/resources/junit-platform.properties`

- [ ] **Step 1: Create the properties file**

```properties
# JUnit 5 parallel execution for the Selenide browser suite.
# Parallel execution is ENABLED but every class defaults to same_thread, so the
# suite stays fully sequential UNLESS a class opts in via @Execution(CONCURRENT).
# This lets us parallelize only an audited cluster of read-only/smoke classes
# (they share one Cargo Tomcat + Postgres, so state-mutating classes must NOT
# opt in). Parallelism is fixed at 3 to bound concurrent headless-Chrome
# instances against the shared app.
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = same_thread
junit.jupiter.execution.parallel.mode.classes.default = same_thread
junit.jupiter.execution.parallel.config.strategy = fixed
junit.jupiter.execution.parallel.config.fixed.parallelism = 3
```

- [ ] **Step 2: Verify the module still runs green (everything still serial — config is inert)**

Run: `bin/run-tests.sh --module custom-jdbc`
Expected: BUILD SUCCESS; the same test count as before, all passing. Because no class is marked `CONCURRENT` yet, behavior is unchanged — this step proves the config file is valid and parsed without altering execution. (Long run: Cargo + browser. If a background run is wall-killed before a definitive BUILD line, re-run. If `clean` fails on a lock: `pkill -9 -f "surefire.*booter|plexus.classworlds|cargo"` then retry — never kill `java -jar app.jar` or a `tomcat/tomcat-11` process.)

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/resources/junit-platform.properties
git commit -m "test(it): enable JUnit-5 parallel execution (default same_thread — inert until opt-in)"
```

---

## Task 2: Audit the candidate classes and opt-in the safe ones

**Files:**
- Modify: each audited-safe class under `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/` — add a class-level `@Execution(ExecutionMode.CONCURRENT)` and the two imports. No other change.

**Audit criteria — a class may be marked `@Execution(CONCURRENT)` ONLY if reading it confirms ALL of:**
1. No page mutation — no `saveText` / `savePage` / `deletePage` / rename / write-page calls.
2. No Knowledge-Graph mutation — no `curate*` / `propose*` / `reviewProposals` / edge/node create/delete.
3. No admin-config mutation — no policy/user/group/api-key writes, no settings changes.
4. No global-count / global-list assertions (e.g. "fetch all pages and assert exactly N", "assert proposal count == X") that another concurrent test could perturb.
5. It does not depend on a specific shared fixture page that a concurrently-running class might modify (read-only classes trivially pass).
If a candidate fails ANY criterion, leave it **unmarked** (serial) and note why.

**Candidate list (with the quick mutation-signal grep already run — but READ each to apply the criteria):**
- `AnonymousViewIT` (0 signals — strong) 
- `AdminAuthRedirectIT` (0 signals — strong)
- `KnowledgeGraphVisualizationIT` (0 signals — likely render-smoke; confirm no seeding)
- `LoginIT` (0 signals — confirm concurrent logins as the same admin user don't assert single-session state)
- `AdminNavigationIT` (1 signal — the metric-card *navigation* click; confirm it's navigation only, no data write)
- `KnowledgeGraphViewerIT` (1 signal — the analysis noted a tier-dropdown that may require seeding; if it seeds/mutates, leave it serial)

- [ ] **Step 1: Audit each candidate and mark the safe ones**

For each candidate that passes the audit, add to the class (mirror this exactly):
```java
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
// ... existing imports ...

@Execution(ExecutionMode.CONCURRENT)
public class AnonymousViewIT extends WithIntegrationTestSetup {
```
(Place `@Execution(ExecutionMode.CONCURRENT)` directly above the `class` declaration, alongside any existing class annotations like `@TestMethodOrder`.)

- [ ] **Step 2: Compile-check the test sources**

Run: `mvn -q -pl wikantik-it-tests/wikantik-selenide-tests test-compile`
Expected: BUILD SUCCESS (annotations + imports resolve).

- [ ] **Step 3: Run the module once with the cluster parallelized**

Run: `bin/run-tests.sh --module custom-jdbc`
Expected: BUILD SUCCESS; all tests pass (same count). The marked classes now run concurrently (up to 3 at a time). If any marked class fails, it has a hidden shared-state dependency — UNMARK it (back to serial) and re-run.

- [ ] **Step 4: Commit**

```bash
# stage exactly the classes you marked, by name
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/AnonymousViewIT.java # … and each other marked class
git commit -m "test(it): parallelize audited read-only browser classes (@Execution(CONCURRENT))"
```

---

## Task 3: Verification campaign + record the gain

**Files:** none (verification + optional unmark).

- [ ] **Step 1: Run the module 3 times and confirm no flakiness**

Run `bin/run-tests.sh --module custom-jdbc` **three times in a row** (serially — one at a time, never concurrent). Record for each: BUILD SUCCESS + the failsafe `Tests run: … Failures: 0, Errors: 0` line + the wall-clock (from the run output / `.test-suite-logs/report.txt`). All three MUST be green.

- [ ] **Step 2: If any marked class flaked even once — unmark it**

If a parallelized class failed in any of the 3 runs (timeout, state collision, stale element), remove its `@Execution(CONCURRENT)` (and now-unused imports), re-run the module once to confirm green, and amend/extend the commit. Reliability wins over the saved second.

- [ ] **Step 3: Record before→after wall-clock**

Compare the module wall-clock to a pre-change baseline (the `custom-jdbc` time from a recent full run, ~1.5 min build + browser tests). Note the realized saving in the commit message or a one-line note.

- [ ] **Step 4: Commit any unmark + a docs note**

If Step 2 unmarked anything, commit it. Optionally add a one-line note to `CHANGELOG.md` (Unreleased): `test: parallelize an audited cluster of read-only browser ITs (JUnit-5 @Execution(CONCURRENT)) to trim custom-jdbc wall-clock`.

```bash
git add -- <any unmarked class files> CHANGELOG.md
git commit -m "test(it): finalize browser-suite parallel cluster after verification campaign"
```

---

## Self-review

- **Spec coverage:** mechanism/`junit-platform.properties` (default same_thread, fixed 3) → Task 1; opt-in `@Execution(CONCURRENT)` on the audited safe cluster + the audit criteria → Task 2; the ≥3-run verification campaign, unmark-on-flake guardrail, and before→after recording → Task 3. Non-goals (data-isolation rewrite, parallelizing stateful classes, new infra, module-level parallelism) are correctly absent.
- **Placeholder scan:** Task 1 has the full properties file; Task 2 gives the exact annotation + imports and concrete per-candidate audit criteria (the candidate set is explicit, not a "TBD"); Task 3 is a concrete run-3×-and-measure protocol. No TODO/TBD.
- **Consistency:** `@Execution(ExecutionMode.CONCURRENT)` + the two `org.junit.jupiter.api.parallel.*` imports used identically; `bin/run-tests.sh --module custom-jdbc` is the verification command throughout; parallelism fixed at 3 matches the spec.

# Browser-Suite Safe Parallelism Design

**Status:** approved 2026-06-04
**Scope:** shave wall-clock off the `custom-jdbc` IT module (the ~28-class Selenide browser suite — the IT wall-clock floor) by running a small, *audited* cluster of read-only/smoke test classes in parallel. Minimal-and-safe: nothing parallelizes unless explicitly opted in.

## Goal

The browser suite runs fully sequentially today (no `junit-platform.properties`, no failsafe parallelism), one browser against one shared Cargo Tomcat + PostgreSQL. It is the slowest IT tier and the wall-clock floor. Run a handful of clearly-independent read-only classes concurrently to recover a modest slice of time **with zero added flakiness risk** — the aggressive full-isolation rewrite was deliberately declined.

## Non-goals (out of scope)

- Per-test data-isolation rewrites (unique namespaces everywhere) to enable full-suite parallelism.
- Parallelizing state-mutating classes (page/cluster/user/KG edits, admin actions, global-count assertions).
- Any new infrastructure (Selenium Grid / Selenoid).
- Parallelizing the IT *modules* against each other (separate, deferred effort).

## Mechanism

1. **`junit-platform.properties`** in `wikantik-it-tests/wikantik-selenide-tests/src/main/resources/` (the module whose classes the `custom-jdbc` module executes):
   ```
   junit.jupiter.execution.parallel.enabled = true
   junit.jupiter.execution.parallel.mode.default = same_thread
   junit.jupiter.execution.parallel.mode.classes.default = same_thread
   junit.jupiter.execution.parallel.config.strategy = fixed
   junit.jupiter.execution.parallel.config.fixed.parallelism = 3
   ```
   Parallel execution is *enabled* but the **default mode is `same_thread`**, so the entire suite stays sequential exactly as today. The fixed parallelism of **3** bounds concurrent headless-Chrome instances against the shared Tomcat.
2. **Opt-in only:** a class runs in parallel **only** if it carries `@Execution(ExecutionMode.CONCURRENT)`. Everything unmarked remains serial — zero behavior change for the bulk of the suite.
3. **Selenide under parallel:** Selenide's WebDriver is thread-local, so each `CONCURRENT` class runs on its own worker thread with its own browser. `WithIntegrationTestSetup`'s `@BeforeAll Selenide.closeWebDriver()` closes only the *current thread's* driver, so it cannot disturb a sibling class running concurrently.

## The safe cluster (audited, opt-in)

`@Execution(CONCURRENT)` is applied **only** to classes confirmed by reading them to be:
- **read-only / smoke** — they navigate and assert rendering/visibility,
- with **no mutations** of shared state (no page save/rename/delete, no KG proposal/edge/node changes, no admin config changes, no user/group changes),
- and **no global-count assertions** (e.g. "list all pages and assert N").

Starting candidates (the implementation's first task audits each and finalizes the list — a candidate that fails the audit stays serial):
`AnonymousViewIT`, `AdminNavigationIT` (the merged nav/smoke — pure navigation + render/metric-card checks), `AdminAuthRedirectIT`, `LoginIT`, and the Knowledge-Graph **viewer render-smoke** classes (`KnowledgeGraphViewerIT` / `KnowledgeGraphVisualizationIT`) *only* if they assert the graph renders without seeding or mutating data.

Each marked class must also be self-contained on data it does not share with a concurrently-running sibling (read-only classes trivially satisfy this).

## Guardrails & verification

- **Default-serial:** anything not explicitly marked `CONCURRENT` is unaffected.
- **Verification campaign:** run the `custom-jdbc` module via `bin/run-tests.sh --module custom-jdbc` **at least 3 times** after marking the cluster; the parallel classes must pass every run with no flakiness, and the wall-clock before→after is recorded.
- **Reliability over speed:** if a marked class flakes even once across the campaign, remove its `@Execution(CONCURRENT)` (back to serial). A second saved is not worth an intermittent red.
- **Concurrency cap:** parallelism fixed at 3 so resource contention (3 browsers + Tomcat + Postgres) stays bounded; the existing surefire/failsafe fork-node fix is unaffected (this is JUnit-level test parallelism, not Maven `-T`).

## Expected gain (honest)

Small — a handful of ~10s read-only classes overlapping 3-wide instead of serially saves roughly **~15–30s** off the browser module. This is the safe slice of the floor; larger gains would require the declined data-isolation rewrite.

## Components / files

- **Create:** `wikantik-it-tests/wikantik-selenide-tests/src/main/resources/junit-platform.properties` (the config above).
- **Modify:** add `@Execution(ExecutionMode.CONCURRENT)` (import `org.junit.jupiter.api.parallel.Execution` / `ExecutionMode`) to each audited safe class — no other change to those classes.
- **No change** to `WithIntegrationTestSetup`, the poms, the runner, or any test logic/data.

## Testing approach

The change *is* test infrastructure; its correctness is proven by the verification campaign (≥3 green `custom-jdbc` runs with the cluster parallelized, plus the recorded wall-clock reduction). No new product tests.

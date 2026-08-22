# One Way To Touch The Database — Implementation Plan

> **For agentic workers:** This plan is executed by an orchestrator dispatching subagents at high
> concurrency (see "Execution mechanics"). Every task is self-contained; follow the **Standard
> migration procedure** unless the task says otherwise. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make `com.wikantik.jdbc.Jdbc` the only way production code executes SQL, with one
transaction primitive that rolls back on *any* failure; make `bin/db/migrations/` the only schema
definition tests run against (no hand-written H2/PG test DDL); enforce both with a baseline-then-shrink
ratchet in the style of `DecompositionArchTest`, and drive the baseline to empty.

**Why (evidence, 2026-08-22 audit):** 56 production classes execute raw SQL in four coexisting
transaction idioms; the shared `JdbcSupport` is used by 8. 2.4.19 shipped three transaction-boundary
fixes, each written differently, and its comments fix *added* a fourth private helper instead of
converging. The same latent shape (`catch (SQLException)` only → an unchecked exception skips
`rollback()` → `finally { setAutoCommit(prev) }` **commits** the partial transaction per the JDBC
contract) is still live in `CitationRepository.replaceForSource`, `DriftSnapshotRepository:91`,
`KgProposalRepository:413`. The schema is defined in four places (57 migrations, four legacy
`bin/db/postgresql*.ddl`, `wikantik-main/src/test/resources/postgresql-test.sql` with 24 of 59
tables, and 30 test files with hand-written `CREATE TABLE`), with no parity check; H2 cannot run the
`ON CONFLICT`/`::vector` paths at all.

**Architecture:** New tiny module **`wikantik-jdbc`** (deps: JDBC + `log4j-api` only, so
`wikantik-insights` and `wikantik-connectors` — which do not depend on `wikantik-main` — can use it)
holding the primitive and, in its **test-jar**, the Postgres test fixture that applies the real
migrations. A ratchet test in `wikantik-war` (the one module whose classpath sees every runtime
module) freezes today's violators and only ever shrinks. Repositories migrate in parallel waves of
disjoint file ownership; the orchestrator merges, refreezes, and gates each wave.

**Tech stack:** Java 25 / Maven multi-module, JUnit 5, ArchUnit 1.5 (`FreezingArchRule`),
Testcontainers (`pgvector/pgvector:pg17`), log4j2.

**Spec:** This plan is the spec. The audit numbers above are the baseline; "Done" is defined at the end.

---

## Global constraints

- Work lands on `main`. Agents commit in **their own worktree branch**; the orchestrator rebases and
  fast-forwards onto `main` and deletes the branch. No PRs.
- TDD per `CLAUDE.md`: where a task fixes a transaction boundary, the failing test comes first.
- Never swallow exceptions — `LOG.warn()` minimum with context. A failing rollback never replaces the
  exception that caused it (`Transactions.rollbackQuietly` already encodes this).
- **SQL text is not rewritten during migration.** Move statements into the primitive verbatim
  (same SQL, same bind order). Dialect fixes are a separate, reported finding — not a drive-by.
- **Public API of every migrated class stays identical** (signatures, checked exceptions, return
  types). Callers must not change. If a class's exception contract is `SQLException`, keep it; if
  it wraps into a runtime/`WikiSecurityException`, keep that.
- Long Maven runs go through `bin/agent-build.sh`. Agents run *targeted* tests only
  (`mvn -q -pl <module> test -Dtest=… -Dwikantik.surefire.forkCount=1`); the orchestrator runs full
  builds between waves. `mvn compile` does not compile tests — run `test-compile` after signature
  changes.
- Stage specific files by name. Commit messages 1–3 lines. No "formerly X" comments, no shims, no
  compatibility aliases left behind (the single exception is noted in Task 0.3).
- **Agents never edit** `wikantik-war/src/test/resources/archunit_store/*`,
  `wikantik-war/src/test/resources/test-ddl-baseline.txt`, `CHANGELOG.md`, or `CLAUDE.md`. The
  orchestrator owns those (Phase 3 / between waves).
- Docker: at most **6** agents run Postgres-backed tests concurrently (Colima sizing — see memory
  `it_gate_contention`). Every agent passes `-Dwikantik.surefire.forkCount=1` so one JVM = one
  container.

---

## Phase 0 — Foundation (orchestrator-sequenced; blocks every wave)

### Task 0.1 — Create `wikantik-jdbc` and the `Jdbc` primitive

**Owner:** one agent (sonnet). **Files:** new module `wikantik-jdbc/`; `pom.xml` (reactor + BOM);
`wikantik-main/pom.xml`; move `wikantik-main/src/main/java/com/wikantik/jdbc/{JdbcSupport,Transactions}.java`;
import fix-ups in the 8 existing `JdbcSupport`/`KgJdbcSupport` subclasses.

- [ ] Add module `wikantik-jdbc` after `wikantik-util` in the root reactor `<modules>`; parent
      `wikantik-builder`, artifact pinned in `wikantik-bom`. Dependencies: `log4j-api` (compile),
      `spotbugs-annotations` (provided), and test scope: junit-jupiter, mockito, `testcontainers-postgresql`,
      `testcontainers-junit-jupiter`, `postgresql` driver. Configure `maven-jar-plugin` `test-jar`
      goal exactly as `wikantik-main/pom.xml` does (the fixture in Task 0.3 ships in it).
- [ ] **Move** (git mv) `JdbcSupport.java` and `Transactions.java` to
      `wikantik-jdbc/src/main/java/com/wikantik/jdbc/` — same package, so the 8 subclasses keep
      compiling apart from the nested-interface change below.
- [ ] Promote `SqlBinder`, `RowMapper`, `TransactionBody` to **top-level public interfaces** in
      `com.wikantik.jdbc`. Update the 8 subclasses' references (`KgJdbcSupport`, `KgEdgeRepository`,
      `KgNodeRepository`, `KgProposalRepository`, `ContentChunkRepository`, `PageCanonicalIdsDao`,
      `TrustedAuthorsDao`, `PageVerificationDao`).
- [ ] Write `com.wikantik.jdbc.Jdbc` — **final class, composition** (`new Jdbc(DataSource)`), public:
  - `<T> List<T> query(String sql, SqlBinder, RowMapper<T>)` and the `(Connection, …)` overload
  - `<T> Optional<T> queryOne(…)` + `(Connection, …)`
  - `int update(String sql, SqlBinder)` + `(Connection, …)`
  - `int[] batch(Connection, String sql, List<SqlBinder>)` (for the `addBatch`/`executeBatch` users)
  - `void forEachRow(String sql, SqlBinder, int fetchSize, RowConsumer)` + `(Connection, …)`
    (streaming reads: backfill/indexers)
  - `void execute(String sql)` + `(Connection, String)` (DDL/no-bind: audit partitions)
  - `boolean ping()` (`Connection.isValid`)
  - `<T> T inTransaction(TransactionBody<T>)`: `setAutoCommit(false)`; on **any `Throwable`**
    (`Exception` *and* `Error`) → `Transactions.rollbackQuietly` then rethrow the original; restore
    the connection's *previous* auto-commit state in `finally` — only after commit/rollback has run,
    so the restore can never commit an open transaction.
  - `DataSource dataSource()` accessor (for the rare caller that must hand a DataSource onward).
- [ ] Re-implement `JdbcSupport` as a thin `extends` convenience over a private `Jdbc` field
      (protected delegators, same method names) so the 8 subclasses are source-compatible. Make its
      `inTransaction` delegate to `Jdbc.inTransaction` (fixes the missing `Error` branch + the
      unconditional `setAutoCommit(true)`).
- [ ] Unit tests in `wikantik-jdbc` (H2 is fine *here* and only here — this module tests the
      primitive's control flow, not dialect): `query/queryOne/update/batch/forEachRow/execute` happy
      paths; `inTransaction` commits on success; rolls back on `SQLException`, on `RuntimeException`,
      on `Error`; rollback failure is logged and does not mask the cause; previous auto-commit state
      restored; connection always closed (use a counting `DataSource` wrapper).
- [ ] `wikantik-main/pom.xml` depends on `wikantik-jdbc`. `mvn -q -pl wikantik-jdbc,wikantik-main
      test-compile` green. Run `mvn -q -pl wikantik-main test -Dtest='KgEdgeRepositoryFilterCountTest,
      PageCanonicalIdsDaoTest,PageVerificationDaoTest,TrustedAuthorsDaoTest'`.
- [ ] Commit: `build(jdbc): wikantik-jdbc module — Jdbc primitive, JdbcSupport/Transactions moved`.

### Task 0.2 — The ratchet (`JdbcAccessArchTest` + `TestSchemaSingleSourceTest`)

**Owner:** one agent (sonnet). **Depends on 0.1.** **Files:** `wikantik-war/pom.xml`,
`wikantik-war/src/test/java/com/wikantik/architecture/*`, `wikantik-war/src/test/resources/{archunit.properties,archunit_store/,test-ddl-baseline.txt}`.

- [ ] `wikantik-war/pom.xml`: add test-scope `archunit-junit5` (version from root `archunit.version`),
      and test-scope `wikantik-extract-cli` (its three CLIs are otherwise off the WAR classpath).
      Verify `wikantik-connectors`/`wikantik-insights`/`wikantik-observability`/`wikantik-ontology`
      are reachable (they are transitively via rest/knowledge/main).
- [ ] `JdbcAccessArchTest` with `@AnalyzeClasses(packages = "com.wikantik", importOptions =
      DoNotIncludeTests)`. Rule **J-1** (frozen via `FreezingArchRule.freeze`, store under
      `src/test/resources/archunit_store`, `archunit.properties` copied from wikantik-main's):
      *no class outside `com.wikantik.jdbc..` may call* `javax.sql.DataSource.getConnection`,
      `java.sql.DriverManager.getConnection`, or `java.sql.Connection.{prepareStatement,
      prepareCall, createStatement, setAutoCommit, commit, rollback}`.
      Permanent allowlist, in the rule with a justification comment: `com.wikantik.plugin.JDBCPlugin`
      (page-authored SQL over its own `DriverManager` lifecycle; disabled by default — ADR-worthy, but
      out of scope here). **The `.as()` text is the store key — pick it once, never edit it.**
- [ ] Sanity-check the store after the first freeze: it must list the ~55 production violators and
      **no test classes** (`TestEngine`, `*Test`). If test-jars leak in, add an `ImportOption`
      excluding `-tests.jar` locations.
- [ ] `TestSchemaSingleSourceTest` (plain JUnit, in the same package): walk `<repoRoot>/*/src/test/java`
      and `*/src/test/resources` (locate repo root like
      `PolicyGrantConvergenceMigrationTest.repoRoot()`), collect files containing `CREATE TABLE`
      (case-insensitive, excluding `wikantik-jdbc/src/test`), and fail on any file not listed in
      `test-ddl-baseline.txt`. Generate the baseline (the 30 files + `postgresql-test.sql` +
      `wikantik-extract-cli/src/test/resources/*.sql`) — entries only ever come out.
- [ ] `mvn -q -pl wikantik-war test -Dtest='JdbcAccessArchTest,TestSchemaSingleSourceTest'` green.
- [ ] Commit: `test(arch): J-1 JDBC-access ratchet + test-DDL single-source ratchet (baselined)`.

### Task 0.3 — Migration-applied Postgres fixture (`PostgresTestDb`)

**Owner:** one agent (sonnet; escalate to opus if the 67-test run surfaces schema drift).
**Depends on 0.1.** Can run **concurrently with 0.2**. **Files:** `wikantik-jdbc/src/test/java/com/wikantik/jdbc/testing/*`,
`wikantik-main/src/test/java/com/wikantik/PostgresTestContainer.java` (delete),
`wikantik-main/src/test/resources/postgresql-test.sql` → `postgresql-test-seed.sql`, the 67 test
files importing `com.wikantik.PostgresTestContainer` (import-line sed only), `wikantik-main/pom.xml`
(+ `wikantik-jdbc` test-jar dep), and `wikantik-rest`/`wikantik-admin-mcp`/`wikantik-knowledge`/
`wikantik-extract-cli` poms if they reach the fixture through the main test-jar (they keep working —
the main test-jar carries the transitive test-jar dep; verify, don't assume).

- [ ] `com.wikantik.jdbc.testing.MigrationApplier`: locate repo root (walk up from `user.dir` to the
      dir containing `bin/db/migrations`); read `V*.sql` sorted by number; substitute the psql
      variable `:app_user` (the only form in use — 75 occurrences) with the container user; **skip
      files containing psql meta-commands** (`^\\` — today only `V031__monitoring_role.sql`) and log
      which were skipped; execute each file as one `Statement.execute` per file inside a transaction
      (the `DO $$ … $$` blocks and `CREATE EXTENSION` in V004 run fine over JDBC). A migration that
      fails must fail loudly with its file name.
- [ ] `com.wikantik.jdbc.testing.PostgresTestDb`: per-JVM singleton over
      `pgvector/pgvector:pg17` (same as today's `PostgresTestContainer`), applies `MigrationApplier`
      on start, then applies the optional classpath resource `postgresql-test-seed.sql` if present
      (wikantik-main ships it; other modules don't). Same static API as today
      (`createDataSource()/getJdbcUrl()/getUsername()/getPassword()`) plus `truncate(String…
      tables)` (`TRUNCATE … RESTART IDENTITY CASCADE`) for per-test isolation. When Docker is
      unavailable: if `-Dwikantik.test.requireDocker=true` → throw (CI); else → a JUnit
      `TestAbortedException` (local skip with a visible reason).
- [ ] `@RequiresPostgres` — a JUnit 5 extension (in the same testing package) that evaluates the
      above once per JVM. It is the replacement for `@Testcontainers(disabledWithoutDocker = true)`
      on container-managed tests, but **do not sweep the 71 annotations in this task** (Phase 3,
      Task 3.3 — avoids file conflicts with the waves).
- [ ] `com.wikantik.jdbc.testing.FaultInjectingDataSource`: wraps a DataSource; `failOn(n,
      RuntimeException)` makes the n-th `prepareStatement`/`createStatement` across the next
      connection throw that exception. This is the standard rollback test harness for the waves.
- [ ] Split `postgresql-test.sql`: everything that is seed *data* (the "test seed data" section —
      users/groups/roles etc.) moves to `postgresql-test-seed.sql`; the schema part is **deleted**
      (migrations own it). Delete `com.wikantik.PostgresTestContainer`; sed the 67 imports to
      `com.wikantik.jdbc.testing.PostgresTestDb`.
- [ ] Run the full Postgres-backed suite in wikantik-main: `bin/agent-build.sh start pgfix -- mvn
      -pl wikantik-main test -Dtest='*Postgres*Test,*Repository*Test,*Dao*Test,JDBC*Test,
      EmbeddingIndexServiceTest,PgVector*Test,MentionIndexTest,DefaultKnowledgeGraphService*Test,
      DatabasePolicyTest,JdbcAuditRepositoryTest,ApiKeyService*Test' -Dsurefire.failIfNoSpecifiedTests=false`
      and poll. Triage every failure into exactly one of: (a) test assumed the *old* hand-written
      schema (fix the test), (b) **genuine drift between `postgresql-test.sql` and the migrations**
      (report it in the commit body verbatim — column, type, table — and fix the test to match
      *migrations*; if migrations look wrong, stop and report, do not add a migration here).
- [ ] Commit: `test(jdbc): PostgresTestDb applies bin/db/migrations; replaces PostgresTestContainer`
      (body lists any drift findings).

### Task 0.4 — Orchestrator gate before waves

- [ ] `bin/agent-build.sh start unit -- mvn clean install -DskipITs` → SUCCESS (this also installs
      `2.4.20-SNAPSHOT` artifacts **including both test-jars** into `~/.m2`, which the worktree
      agents resolve `-pl` builds against).
- [ ] `docker pull pgvector/pgvector:pg17` warm; Colima up (`colima status`; memory
      `colima_stale_disk_lock` if start fails).

---

## Standard migration procedure (every wave task follows this)

For each production class in the task's ownership list:

1. **Read** the class and its tests (see the coverage map below). Note every transaction block
   (`setAutoCommit`/`commit`/`rollback`) and every `catch` around it.
2. **If the class has a hand-rolled transaction:** write the failing test first using
   `FaultInjectingDataSource` — inject a `RuntimeException` after the first write inside the
   transaction and assert **no partial rows are visible** afterwards (query the table(s) directly).
   Run it; it must fail (today it commits the partial work). Then migrate; it must pass.
3. **Migrate**: class holds `private final Jdbc jdbc` (or extends `JdbcSupport` if it already
   does / has no other superclass and that reads better); each statement becomes
   `query/queryOne/update/batch/forEachRow/execute`; each transaction becomes one `inTransaction`
   body using the `(Connection, …)` overloads. Delete private `inTransaction`/`rollbackQuietly`/
   `runInTransaction` helpers that are now redundant. SQL strings move **verbatim**. Exception
   contract unchanged (wrap at the same boundary as before).
4. **Tests**: if the class's tests use **H2** (`jdbc:h2`, hand-written `CREATE TABLE`): convert them
   to `PostgresTestDb.createDataSource()` + `@RequiresPostgres`; delete the hand-written DDL; use
   `PostgresTestDb.truncate(...)` in `@BeforeEach` for isolation. If both an H2 test and a
   `*PostgresTest` exist for the same class, fold the H2 cases into the PG test and delete the H2
   file. (Yes: every DB test becomes a Postgres test. That is the point. Runtime cost is one
   container per JVM, already paid by the existing PG tests.)
5. **Verify**: `mvn -q -pl <module> test-compile` then `mvn -q -pl <module> test -Dtest='<the
   class's tests>' -Dwikantik.surefire.forkCount=1`. Do **not** run the ArchUnit tests (the
   orchestrator refreezes after the wave; a disappearing violation never fails the frozen rule).
6. **Commit** in the worktree: `refactor(<area>): <Class> on Jdbc primitive; tests on PostgresTestDb`
   (+ `fix(<area>): <Class> rolls back on unchecked failure` when step 2 applied, as a separate
   first commit so the fix is visible in history).
7. **Report back** (final message): files changed, tests run + result, any SQL that looked
   dialect-specific or wrong, any schema drift, anything you deliberately did not do.

Grep checks before reporting: `grep -nE 'setAutoCommit|prepareStatement|createStatement|getConnection\(' <owned files>` must be empty (except `Jdbc`/`JdbcSupport`).

### Coverage map (from the 2026-08-22 audit) — `H2`/`PG` = how its tests talk to a DB today

| Class | Module | Tx? | Tests |
|---|---|---|---|
| `citation/CitationRepository` | main | **yes (latent)** | `CitationRepositoryTest` (mock), `TestCitationDb` + `CitationEdgesIT` (H2 DDL) |
| `drift/DriftSnapshotRepository` | main | **yes (latent)** | H2 |
| `knowledge/KgProposalRepository` | main | **yes (latent, :413)** | PG |
| `comments/CommentStore` | main | yes (private helper) | H2 ×3 (`CommentStoreTransactionTest` exists) |
| `comments/PageOwnerService`, `MentionService`, `mentions/MentionFeedDao` | main | no | H2 |
| `auth/AbstractJDBCDatabase` (`runInTransaction`), `JDBCUserDatabase` (5), `JDBCGroupDatabase` | main | yes | PG |
| `auth/DatabasePolicy`, `auth/apikeys/ApiKeyService` | main | no | PG / H2 |
| `audit/JdbcAuditRepository` | main | yes (+ partition DDL) | PG |
| `search/embedding/EmbeddingIndexService` (5), `PgVectorBackfillCli`, `BootstrapEmbeddingIndexer`; `search/hybrid/PgVectorChunkVectorIndex` | main | yes | PG |
| `knowledge/extraction/ChunkEntityMentionRepository` | main | yes | PG |
| `extractcli/KgPolicyCli` | extract-cli | yes (:346 — verify the 2.4.19 fix widened the catch; if not, it is a step-2 case) | PG |
| connectors `JdbcConnectorConfigStore`, `JdbcCredentialStore`, `ConnectorStatusReader`, `JdbcSyncRunStore`, `JdbcSyncStateStore` | connectors | no | H2 ×5 |
| `insights/JdbcInsightsStore` (22 stmts) | insights | no | H2 + PG (fold) |
| `knowledge/{HubDiscoveryRepository,HubProposalRepository,KgEdgeAuditRepository,KgRejectionRepository,MentionIndex,DefaultKnowledgeGraphService}` | main | no | PG |
| `knowledge/embedding/{KgNodeEmbeddingRepository,NodeMentionSimilarity}`, `knowledge/judge/JdbcKgJudgeTimeoutRepository`, `knowledge/eval/{BundleEvalRunDao,RetrievalQualityDao}` | main | no | PG / H2 (`RetrievalQualityDao`) |
| `knowledge/querylog/{JdbcQueryLogService,JdbcQueryLogReader,JdbcBriefingLogService}`, `kgpolicy/{KgClusterPolicyRepository,KgExcludedPagesRepository}` | main | no | H2+PG / H2 / mock ; PG |
| `search/hybrid/{InMemoryChunkVectorIndex,LuceneBm25ChunkIndex,LuceneHnswChunkVectorIndex}` | main | no | PG |
| `pagegraph/spine/{PageCanonicalIdsDao,PageVerificationDao,TrustedAuthorsDao}` (already `JdbcSupport`) | main | no | H2 → convert only |
| `rest/AdminPolicyResource` (4 stmts in a servlet) | rest | no | `AdminPolicyResourceDbTest` |
| `observability/health/DatabaseHealthCheck`, `ontology/projection/EdgeProjector`, `extractcli/{BootstrapExtractionCli,JudgeExperimentCli}` | various | no | unit |
| Remaining H2-DDL tests with no owning repository: `derived/ConnectorWiringHelperTest`, `derived/ConnectorConfigServiceTest`, `connectors/ConnectorSyncEndToEndTest`, `search/subsystem/BundleSourcesWithoutEmbedderTest`, `knowledge/eval/{RetrievalQualitySmokeTest,DefaultRetrievalQualityRunnerTest}` | main | — | convert only |
| `rest/CommentThreadResourceTest` | rest | — | convert only |

---

## Phase 1 — Wave 1: the transaction owners (≤6 concurrent; model: sonnet)

Each task = one agent, one worktree, disjoint files. Step 2 of the procedure applies.

- [ ] **T1.1** `citation/CitationRepository` + its tests (`TestCitationDb`/`CitationEdgesIT` → PostgresTestDb).
- [ ] **T1.2** `drift/DriftSnapshotRepository` + test.
- [ ] **T1.3** `knowledge/KgProposalRepository` (already `KgJdbcSupport`; replace the hand-rolled block at ~413 with `inTransaction`) + test.
- [ ] **T1.4** `comments/*`: `CommentStore` (delete private `inTransaction`), `PageOwnerService`, `MentionService`, `mentions/MentionFeedDao` + their H2 tests (6 files). `CommentStoreTransactionTest` becomes the FaultInjecting test.
- [ ] **T1.5** `auth/*`: `AbstractJDBCDatabase.runInTransaction` delegates to `Jdbc.inTransaction` (keep `WikiSecurityException` wrapping + `supportsCommits`); `JDBCUserDatabase`, `JDBCGroupDatabase`, `DatabasePolicy`, `apikeys/ApiKeyService` + tests (ApiKeyService H2 → PG).
- [ ] **T1.6** `search/embedding/{EmbeddingIndexService,PgVectorBackfillCli,BootstrapEmbeddingIndexer}` + `search/hybrid/PgVectorChunkVectorIndex` + tests (streaming reads use `forEachRow`; batches use `batch`).
- [ ] **T1.7** `audit/JdbcAuditRepository` (partition DDL via `execute(conn, ddl)` inside the same transaction) + test.
- [ ] **T1.8** `knowledge/extraction/ChunkEntityMentionRepository` + `extractcli/KgPolicyCli` + tests (two modules, disjoint).

**Orchestrator after Wave 1:** merge all 8 branches (rebase → ff); refreeze (`freeze.refreeze=true`
one run of `JdbcAccessArchTest`, then back to `false`; commit the shrunken store); regenerate
`test-ddl-baseline.txt`; `bin/agent-build.sh start unit -- mvn clean install -DskipITs` → SUCCESS.

## Phase 2 — Wave 2: mechanical repositories + H2 retirement (≤6 concurrent; sonnet; haiku OK for T2.6/T2.7)

- [ ] **T2.1** `wikantik-connectors`: the 5 stores + 5 H2 tests; pom gets `wikantik-jdbc` (compile) and its test-jar + testcontainers (test). Update the connectors module's own README/Javadoc if it claims H2.
- [ ] **T2.2** `wikantik-insights/JdbcInsightsStore`: pom gets `wikantik-jdbc`; fold `JdbcInsightsStoreTest` (H2, self-limited) into `JdbcInsightsStorePostgresTest` (switch it from its private `@Container` to `PostgresTestDb`), delete the H2 file. Report in the commit body that insights now depends on `wikantik-jdbc` (CLAUDE.md's "depends on neither wikantik-api nor wikantik-main" sentence stays true; orchestrator adds the jdbc note in Phase 3).
- [ ] **T2.3** `knowledge/{HubDiscoveryRepository,HubProposalRepository,KgEdgeAuditRepository,KgRejectionRepository,MentionIndex,DefaultKnowledgeGraphService}` + tests.
- [ ] **T2.4** `knowledge/embedding/*`, `knowledge/judge/JdbcKgJudgeTimeoutRepository`, `knowledge/eval/{BundleEvalRunDao,RetrievalQualityDao}` + tests (`RetrievalQualityDaoTest` H2 → PG).
- [ ] **T2.5** `knowledge/querylog/*` (3) + `kgpolicy/*` (2) + tests (fold `JdbcQueryLogServiceTest` into the Postgres one; `JdbcQueryLogReaderTest` H2 → PG).
- [ ] **T2.6** `search/hybrid/{InMemoryChunkVectorIndex,LuceneBm25ChunkIndex,LuceneHnswChunkVectorIndex}` + tests (read-only JDBC; pure mechanical).
- [ ] **T2.7** `pagegraph/spine/*` tests only: `PageCanonicalIdsDaoTest`, `PageVerificationDaoTest`, `TrustedAuthorsDaoTest` H2 → PostgresTestDb (the DAOs are already on `JdbcSupport`).
- [ ] **T2.8** `rest/AdminPolicyResource`: move its four statements into `com.wikantik.auth.DatabasePolicy` (which already owns `policy_grants` reads) as `listGrants/insertGrant/updateGrant/deleteGrant` on `Jdbc` (`INSERT … RETURNING id` via `queryOne` replaces `RETURN_GENERATED_KEYS`); servlet calls them; `AdminPolicyResourceDbTest` follows. Plus `observability/DatabaseHealthCheck` (`Jdbc.ping()`), `ontology/projection/EdgeProjector`, `extractcli/{BootstrapExtractionCli,JudgeExperimentCli}` (one statement each).
- [ ] **T2.9** Orphan H2-DDL tests → PostgresTestDb: `derived/ConnectorWiringHelperTest`, `derived/ConnectorConfigServiceTest`, `connectors/ConnectorSyncEndToEndTest` (main), `search/subsystem/BundleSourcesWithoutEmbedderTest`, `knowledge/eval/{RetrievalQualitySmokeTest,DefaultRetrievalQualityRunnerTest}`, `rest/CommentThreadResourceTest`.

**Orchestrator after Wave 2:** same merge/refreeze/baseline/full-build gate as Wave 1. Expected
state: J-1 store contains only `JDBCPlugin` (allowlisted → actually zero entries);
`test-ddl-baseline.txt` contains only `wikantik-extract-cli/src/test/resources/*.sql` (assess in 3.2).

## Phase 3 — Lock-in (orchestrator, sequential)

- [ ] **T3.1** Convert J-1 from `freeze(...)` to a plain rule (delete the store entry/file) — the
      ratchet has reached zero and must stay there. Keep the allowlist comment for `JDBCPlugin`.
- [ ] **T3.2** Schema single-source: delete `bin/db/postgresql.ddl`, `postgresql-hub.ddl`,
      `postgresql-knowledge.ddl`, `postgresql-permissions.ddl` after fixing the references
      (`CLAUDE.md` "Permission migration DDL" line, `bin/db/migrations/README.md`,
      `V002__core_users_groups.sql` comment, Javadoc in `DefaultAuthorizationManager` /
      `JDBCGroupDatabase`, `install-fresh.sh` already uses migrations). Convert the two
      `wikantik-extract-cli/src/test/resources/*-cli-test.sql` to seed-only (schema from
      `PostgresTestDb`) and empty `test-ddl-baseline.txt`; `TestSchemaSingleSourceTest` becomes
      zero-tolerance. Remove the `h2` test dependency from every pom that no longer uses it
      (`grep -rl 'jdbc:h2'` must be confined to `wikantik-jdbc`).
- [ ] **T3.3** Sweep the 71 `@Testcontainers(disabledWithoutDocker = true)` annotations to
      `@RequiresPostgres` (sed + compile). CI: add `-Dwikantik.test.requireDocker=true` to the
      `unit-tests` job in `.github/workflows/quality-gates.yml` and to `ci-cd.yml`'s `mvn clean
      test` so an absent Docker daemon **fails** the run instead of silently skipping 70+ tests.
      `bin/run-tests.sh` phase 1 sets it too.
- [ ] **T3.4** Docs: `CLAUDE.md` — module table entry for `wikantik-jdbc`; a one-paragraph
      "Adding a repository" rule (use `Jdbc`; tests use `PostgresTestDb`; schema changes are
      migrations — the fixture applies them, so a test needing a table *is* a reason to write the
      migration first); update the Testing Approach bullet ("unit tests use in-memory H2" → no
      longer true for DB tests); `docs/CodeQuality.md` baseline note; `ADR-0010-one-data-access-primitive.md`
      (decision, the 2.4.19 evidence, the JDBCPlugin carve-out, the "no H2" consequence);
      `CHANGELOG.md` Unreleased: *Fixed* (the three latent rollback defects, each named), *Changed*
      (one primitive; tests run against migrations), *Tests* (Docker required in CI).
- [ ] **T3.5** Canonical gate: `bin/run-tests.sh --parallel 4` → green (fresh log mtimes).
- [ ] **T3.6** Memory note (`duplication_hides_defects` already exists): link this plan as the
      worked example.

---

## Execution mechanics (orchestrator)

- **Isolation:** every wave agent runs with `isolation: "worktree"`. Each commits on its worktree
  branch; it never pushes, never touches `main`, never runs `install`. `~/.m2` is shared read-only
  (`-pl <module>` resolves siblings from the Task 0.4 install).
- **Dispatch:** one `Agent` call per task, ≤6 in flight. Prompt = this plan's Global constraints +
  Standard procedure + the task's ownership list + the coverage-map row(s) + "report back" format.
  Model: sonnet (T2.6/T2.7 haiku acceptable). Reserve opus for triage if an agent reports schema drift
  it cannot classify.
- **Merge:** per finished branch, in its worktree: `git rebase main` → on main `git merge --ff-only
  <branch>` → delete worktree/branch. File ownership is disjoint by construction, so conflicts mean a
  task strayed — reject and re-dispatch rather than hand-resolving.
- **Between waves:** refreeze J-1 store (one run with `freeze.refreeze=true`, then revert the property),
  regenerate `test-ddl-baseline.txt`, commit both as `test(arch): ratchet after wave N`, full unit
  build via `bin/agent-build.sh`. Green is the precondition for the next wave.
- **Docker budget:** ≤6 concurrent PG-backed test runs; every agent passes
  `-Dwikantik.surefire.forkCount=1`. If Colima starts refusing connections, halve concurrency.
- **Stop conditions:** an agent that needs to change SQL semantics, a public signature, or a
  migration stops and reports instead. The orchestrator decides.

## Risks & mitigations

- **Schema drift surfaced by Task 0.3** (tests written against `postgresql-test.sql` meet the real
  migrations). Expected and desirable; budget for it. Drift in *migrations* is reported, not patched.
- **Test wall-clock** rises (H2 → container). Container start is once per JVM; with `0.5C` forks
  that is ~N containers per full build — already the case for 67 tests today. Measure phase-1 unit
  time before/after and record it in T3.4.
- **`DoNotIncludeTests` leakage** of test-jars into the J-1 import (Task 0.2 checks the store).
- **Agents "improving" SQL** while migrating — forbidden by the global constraint; the
  report-back format asks for dialect findings instead.
- **`wikantik-insights` purity** — it gains a dependency on a JDBC-only module with no wiki types;
  its rule engine stays engine-free. Documented in T3.4.

## Done

- `grep -rnE 'setAutoCommit|prepareStatement\(|createStatement\(|\.getConnection\(' --include='*.java'
  wikantik-*/src/main/java` hits only `wikantik-jdbc/` and `JDBCPlugin`.
- `JdbcAccessArchTest` J-1 is an unfrozen, zero-violation rule; `TestSchemaSingleSourceTest` is
  zero-tolerance; no `jdbc:h2` outside `wikantik-jdbc`; `bin/db/postgresql*.ddl` gone;
  `postgresql-test.sql` is seed-only.
- Each of the three latent rollback sites has a test that injects a `RuntimeException`
  mid-transaction and asserts no partial rows — and it passed only after the migration.
- CI fails, rather than skips, when Docker is absent.
- `bin/run-tests.sh --parallel 4` green; CHANGELOG + ADR-0010 + CLAUDE.md updated.

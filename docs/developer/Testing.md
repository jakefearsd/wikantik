# Testing Wikantik

This page is for developers who write or run Wikantik tests. It describes how the unit and integration tests are laid out, the database test helpers, the canonical pre-commit gate, Docker and embedder requirements, the quality ratchets, and how to handle flaky tests. It was written from the poms, `bin/run-tests.sh --help` and `bin/agent-build.sh`; run those for the current flags. For build commands see [Building.md](Building.md).

## Know the two test layers

- **Unit tests** are JUnit 5 and live under each module's `src/test`. `com.wikantik.TestEngine` builds a real engine for tests, and most components have mocks. `wikantik-main` and `wikantik-rest` run partitioned across forked JVMs (`wikantik.surefire.forkCount`, default `0.5C`).
- **Integration tests** live under `wikantik-it-tests` and run only under the `integration-tests` profile. Cargo boots Tomcat and a pgvector container per module. The five default modules are `wikantik-it-test-rest`, `wikantik-it-test-sso`, `wikantik-it-test-knowledge-disabled`, `wikantik-it-test-custom-jdbc` and `wikantik-it-test-dense`. `wikantik-selenide-tests` is a shared support module with the Selenide browser tests and fixtures, not a suite of its own. `wikantik-it-test-scim-fullloop` is an opt-in Authentik suite built only under `-Pscim-fullloop`. More detail is in `wikantik-it-tests/README.md`.

## Test against a real database

There is no H2 schema. Tests that touch a database run against a real `pgvector/pgvector:pg18` container started through Docker. The helpers ship in the `wikantik-jdbc` test-jar under `com.wikantik.jdbc.testing`:

- `@RequiresPostgres` marks a test class that needs the database. Without Docker the test skips locally with a visible reason. Pass `-Dtests.requireDocker=true` and an absent daemon fails the run instead; CI does this.
- `PostgresTestDb.createDataSource()` returns a data source on one container per JVM with every migration in `bin/db/migrations/` applied. Call `PostgresTestDb.truncate(tables...)` in `@BeforeEach`. The container is shared across test classes, so never `DROP` or `ALTER` shared tables.
- `FaultInjectingDataSource` throws a `RuntimeException` on the n-th statement. Use it to prove a multi-statement write rolls back with no partial rows.
- Do not hand-write `CREATE TABLE` in a test. Write the migration instead. `TestSchemaSingleSourceTest` in `wikantik-war` fails on a `CREATE TABLE` under any `src/test` that is not in its `test-ddl-baseline.txt`.

To add a repository: hold a `Jdbc`, write the migration first, and test against `PostgresTestDb`.

## Run the canonical gate

Run this before you commit:

```bash
bin/run-tests.sh --parallel 4
```

Phase 1 runs every unit test once (`-T 1C -DskipITs`) and installs the artifacts, including the WAR and the `wikantik-selenide-tests` test-jar. Phase 2 runs the five default IT modules in one `-T 4` reactor with per-module reserved ports and uniquely named pgvector containers. The IT phase does not re-run the unit tests. The script takes a per-checkout run lock (`.run.lock`) and writes a summary to `target/test-suite-report.txt`. Parallel IT runs are supported only through this script; do not add `-T` to a raw `mvn ... -Pintegration-tests` command.

Other modes from `bin/run-tests.sh --help`:

```bash
bin/run-tests.sh                    # default gate with a sequential IT phase
bin/run-tests.sh --all              # default gate plus the opt-in scim-fullloop
bin/run-tests.sh --unit             # unit phase only
bin/run-tests.sh --it --parallel 4  # IT phase only (needs a prior --unit)
bin/run-tests.sh --module rest      # one IT module: rest|sso|knowledge-disabled|custom-jdbc|dense|scim-fullloop
bin/run-tests.sh --list             # modules and their gate
bin/run-tests.sh --all -o both      # stream build output to console and log
```

Because a full run exceeds an agent's tool timeout, start it detached:

```bash
bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4
bin/agent-build.sh wait gate 540
```

The summary's "Tests run: N" line for a phase is the last module's count, not the aggregate. Sum the per-module results in `.test-suite-logs/phase1-unit.log` if you need a total.

## Meet the Docker and embedder requirements

- Docker (with compose) must be running for the IT phase and for every database-backed unit test.
- `wikantik-it-test-dense` is the only module with embeddings enabled. `bin/run-tests.sh` starts one shared CPU ollama container from `docker/docker-compose.embeddings.yml` on port 11435 (not 11434, so it never borrows a dev instance) and tears it down afterwards. The first run on a machine pulls the `qwen3-embedding:0.6b` model, which takes several minutes and is not a hang.
- Run only that module with `bin/run-tests.sh --module dense`. A bare `mvn -pl ... ` on its failsafe run skips the embedder setup and fails at `@BeforeAll` with the compose command it wanted.

## Run the quality ratchets

- **Coverage:** `mvn clean install -Pcoverage -DskipITs` makes JaCoCo fail any module below its `wikantik.coverage.line.minimum`. Floors only go up and never above 0.90. See [CodeQuality.md](CodeQuality.md).
- **Complexity:** `mvn pmd:check -Pcomplexity-gate` fails on any new PMD design-rule violation not in `build-support/pmd-complexity-baseline.properties`. Entries only ever come out.
- **Architecture and drift tests** in `wikantik-war` and `wikantik-main`: `JdbcAccessArchTest`, `TestSchemaSingleSourceTest`, `ConfigSurfaceDriftTest` and `DecompositionArchTest`.
- **Frontend:** `npm run lint` (ESLint) and `npm run test:coverage` (Vitest with coverage thresholds in `wikantik-frontend/vite.config.js`), both run from `wikantik-frontend`.

## Handle flaky tests

- Re-run the failing test alone first: `mvn test -pl <module> -Dtest=<Class>#<method>`.
- If a class fails differently on each run, suspect order-dependent shared state: `wikantik-main` sets surefire `runOrder` to `random`. Force a sequential run with `-Dwikantik.surefire.forkCount=1`.
- Vitest hook tests can fail under concurrency; re-run the file in isolation before you chase the failure.
- A red gate is fixed in the session, not set aside as pre-existing.
- The weekly CI job is the only CI run of the Java unit suite and the coverage floors; see [CI.md](CI.md). Mirror it locally with `mvn clean install -DskipITs -Pcoverage` before you dispatch it.

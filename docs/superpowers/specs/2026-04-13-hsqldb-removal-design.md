# HSQLDB Removal — Integration Tests on PostgreSQL + pgvector

**Date:** 2026-04-13
**Status:** Approved for implementation planning

## Goal

Remove HSQLDB entirely from the wikantik project. Replace every test-time use
of HSQLDB (integration tests and unit tests) with PostgreSQL 16 + pgvector,
running in Docker. Docker becomes a hard prerequisite for
`mvn -Pintegration-tests`; there is no in-memory fallback.

After this cutover, `grep -r hsqldb` against the tree returns nothing
meaningful: no dependencies, no plugin configs, no Java imports, no JDBC URLs.

## Why

1. **Production parity.** Prod runs on PostgreSQL. HSQLDB-backed ITs miss
   dialect differences, migration-script bugs, and pgvector-reliant code paths
   entirely.
2. **pgvector coverage.** `HubOverviewAdminIT` and `HubDiscoveryAdminIT` are
   currently `@Disabled("Requires a PostgreSQL+pgvector datasource…")`. The
   `ContentEmbeddingRepository` logs `getLatestModelVersion()` WARNs during
   every IT run because HSQLDB has no pgvector schema. Moving to PG gives
   these paths real coverage.
3. **One DB story.** Two DB dialects in tests means two sets of SQL quirks to
   keep working. Collapsing to one removes a maintenance axis.
4. **Docker is already a dependency.** `sso-it` already requires Docker for
   mock-oauth2-server. Accepting Docker for PG too costs us nothing we
   haven't already paid.

## Non-Goals

- No change to production deployment topology or `migrate.sh`.
- No matrix testing against multiple PG versions (single pinned image).
- No refactor of the JDBC user/group auth code itself — only its tests move.
- No change to unit-test JVM model — `PostgresTestContainer` singleton stays.

## Architecture

### Per-IT-module container lifecycle

Every `wikantik-it-test-*` module and `wikantik-selenide-tests` follows the
same Maven lifecycle, driven by `io.fabric8:docker-maven-plugin`:

```
pre-integration-test:
  1. docker-maven-plugin: start pgvector/pgvector:pg17
     - port 55432:5432
     - env: POSTGRES_USER=${it.db.user}, POSTGRES_PASSWORD=${it.db.password},
            POSTGRES_DB=wikantik
     - autoRemove=true, removeVolumes=true
     - wait for pg_isready (built-in healthcheck)
  2. exec-maven-plugin: run wikantik-war/src/main/config/db/migrate.sh
     - DB_NAME=wikantik, DB_APP_USER=${it.db.user},
       PGHOST=localhost, PGPORT=55432,
       PGUSER=${it.db.user}, PGPASSWORD=${it.db.password}
     - Applies V001-V007 idempotent migrations — same path prod uses
  3. Cargo: start Tomcat 11 with ROOT.xml JNDI DataSource pointing at
     jdbc:postgresql://localhost:55432/wikantik

integration-test:
  failsafe runs ITs against Cargo

post-integration-test:
  1. Cargo: stop Tomcat
  2. docker-maven-plugin: stop container
     (autoRemove + removeVolumes — zero residue)
```

**Per-module containers, not shared.** Follows the existing `sso-it`
pattern. Port 55432 reused across modules — no collision because
CLAUDE.md already forbids parallel IT execution.

**SSO-IT retains its mock-oauth2-server container.** The PG container is
added alongside, not instead of, the existing Docker setup.

### Unit-test container lifecycle (unchanged)

`PostgresTestContainer` continues to provide a JVM-wide singleton PG+pgvector
container for unit tests, at a random port. Independent of IT containers.
The image tag is centralized to the root-pom `<pgvector.image>` property.

### Image pinning

Root `pom.xml` gets `<pgvector.image>pgvector/pgvector:pg17</pgvector.image>`.
Every consumer — `PostgresTestContainer` (via system property),
docker-maven-plugin configs — references this property. Single source of
truth.

### Credentials

Credentials live in `wikantik-it-tests/it-db.properties`, **gitignored**.
Values:

```properties
it.db.user=jspwiki
it.db.password=jspwiki-it
```

- `wikantik-it-tests/it-db.properties.template` is checked in as the sample,
  documented in `wikantik-it-tests/README.md`.
- Root `.gitignore` adds `wikantik-it-tests/it-db.properties`.
- A Maven `initialize`-phase step (antrun or similar) copies the template to
  `it-db.properties` if the real file is absent — so a fresh checkout can
  `mvn -Pintegration-tests` without manual setup.
- `properties-maven-plugin` reads `it-db.properties` in `initialize`. The
  properties flow into:
  - docker-maven-plugin env vars
  - exec-maven-plugin `migrate.sh` args
  - resource-filtered Cargo `ROOT.xml` and `wikantik-custom.properties`
- No literal password appears in any checked-in file. GitHub secret-scanning
  stays quiet.

### Profile consolidation

- The `sso-it` opt-in profile is **deleted**. `wikantik-it-test-sso` becomes
  an unconditional `<module>` in `wikantik-it-tests/pom.xml`.
- The `pgvector-it` profile is **not created** — Hub ITs are re-enabled in
  place inside `wikantik-selenide-tests`, not moved to a new module.
- `integration-tests` profile runs everything: custom, custom-jdbc, rest,
  sso, and the (now PG-backed) selenide-tests with Hub ITs included.

## Module Inventory

After the cutover, IT modules remain:

| Module | Purpose | Changes |
|---|---|---|
| `wikantik-selenide-tests` | Default IT scenarios (admin, editing, permissions, **Hub ITs**) | Add PG container, enable `HubOverviewAdminIT` and `HubDiscoveryAdminIT`, swap JNDI URL |
| `wikantik-it-test-custom` | Custom-properties IT | Swap HSQLDB → PG |
| `wikantik-it-test-custom-jdbc` | JDBC user/group auth variant | Swap HSQLDB → PG (keeps module; see rationale below) |
| `wikantik-it-test-rest` | REST API IT | Swap HSQLDB → PG |
| `wikantik-it-test-sso` | SSO/OIDC IT | Add PG container next to existing mock-oauth2-server; promote out of `sso-it` profile |

### Why `wikantik-it-test-custom-jdbc` stays

This module's reason-to-exist is the *configuration variant* (JDBC-backed
user/group DB vs. the default file-backed XML), not the backend database.
That distinction is a real production deployment choice. Keep the module,
port HSQLDB → PG, no structural change.

## File-by-File Change Set

### Root

- **`pom.xml`**
  - Add `<pgvector.image>pgvector/pgvector:pg17</pgvector.image>` property
  - Remove `hsqldb` from `<dependencyManagement>`
- **`.gitignore`**
  - Add `wikantik-it-tests/it-db.properties`

### `wikantik-it-tests/`

- **`pom.xml`**
  - Delete `inmemdb-maven-plugin` plugin block
  - Delete `sso-it` profile wrapper; promote `wikantik-it-test-sso` to
    unconditional `<module>`
  - Add reusable docker-maven-plugin + exec-maven-plugin + properties-maven-
    plugin configuration (pluginManagement) so child modules inherit it
- **`it-db.properties.template`** (new, checked in)
- **`it-db.properties`** (new, gitignored, generated from template on first
  build)
- **`README.md`** (new or updated): documents the `it-db.properties` bootstrap,
  the Docker prerequisite, and how to run ITs

### Each IT module

For `wikantik-it-test-custom`, `-custom-jdbc`, `-rest`, `-sso`,
`wikantik-selenide-tests`:

- **`pom.xml`**
  - Inherit docker-maven-plugin + exec-maven-plugin from parent; activate them
  - Swap Cargo JNDI resource URL from `jdbc:hsqldb:…` → `jdbc:postgresql://localhost:55432/wikantik`
  - Swap JDBC driver from `org.hsqldb.jdbc.JDBCDriver` → `org.postgresql.Driver`
  - Remove any HSQLDB-specific plugin config
- **`src/main/resources/wikantik-custom.properties`**
  - Swap `jdbc:hsqldb:…` URLs to PG
  - Ensure `wikantik.datasource` points at the JNDI name so DB-backed policy
    grants activate
- **`src/main/resources/cargo-context.xml`** (or wherever Cargo's context is)
  - Swap JNDI DataSource config to PG + `${it.db.user}` / `${it.db.password}`
    (resource-filtered)

### `wikantik-it-test-sso` specifics

- Already has Docker (mock-oauth2-server). Add the PG container in the same
  docker-maven-plugin config.
- Module is no longer gated behind `sso-it` profile — it runs whenever
  `integration-tests` runs.

### `wikantik-selenide-tests`

- **`src/test/java/com/wikantik/its/HubOverviewAdminIT.java`** — remove
  `@Disabled`
- **`src/test/java/com/wikantik/its/HubDiscoveryAdminIT.java`** — remove
  `@Disabled`
- **`src/main/resources/wikantik-custom.properties`** — swap HSQLDB → PG

### Unit tests (HSQLDB removal)

For each Java test file identified by `grep -rl hsqldb --include='*.java' wikantik-*/src/test/`:

- **Port to `PostgresTestContainer`** if the test exercises real wikantik
  behavior (JDBC user/group DB, JDBCPlugin against a live DB, etc.).
  Delete assertions that only verified HSQLDB-quirk behavior (per scope-B
  decision).
- **Delete entirely** if the test was HSQLDB-only and equivalent PG coverage
  already exists via `PostgresTestContainer`.
- Expected files (verify during implementation):
  - `JDBCPluginTest` and any JDBCPlugin variants
  - Any `wikantik-auth` tests that stand up an inline HSQLDB for user/group DB
  - Any other hits from the grep

### Other resource files

- **`wikantik-util/src/test/resources/wikantik-custom.properties`** — swap
  HSQLDB URL for PG, or delete if the test using it no longer needs a JDBC URL
- **Any other `wikantik-custom.properties` in `src/test/resources/` or
  `src/main/resources/`** that reference HSQLDB — swap or delete per usage
- **`wikantik-selenide-tests/src/main/resources/wikantik-custom.properties`**
  — covered above

### Local scripts

- **`fulltestsuite.sh`** (user's local script, not in git): drop any
  `-Psso-it` addition, since that profile is going away

### Migration scripts

No changes. `migrate.sh` and `V001-V007` already work against PG — that's
now the only target they need to support.

## Rollout Plan

**Big-bang cutover in a single commit.** No HSQLDB/PG coexistence period.
The commit touches many files but reaches a single verifiable end state:

- `mvn clean install -Pintegration-tests -fae` passes with Docker running.
- `mvn clean install -DskipITs -T 1C` still passes (unit tests).
- `grep -rn hsqldb` against the tree returns only this design doc and
  maybe historical CHANGELOG entries.
- `mvn dependency:tree | grep -i hsqldb` returns nothing.

## Error Handling

- **Docker not available.** docker-maven-plugin fails fast in
  `pre-integration-test` with a clear error. CLAUDE.md and the IT README
  document Docker as a hard prerequisite.
- **Port 55432 already bound.** docker-maven-plugin fails fast. Users who
  have a local PG on 55432 need to stop it (standard PG runs on 5432, so
  this is rare).
- **Migration failure.** `migrate.sh` is idempotent and exits non-zero on
  failure; Maven halts. The failing migration is visible in the Maven log
  exactly as it would appear in a prod deploy.
- **Container left running after `Ctrl-C`.** `autoRemove=true` on
  docker-maven-plugin means Docker cleans the container up when it stops,
  even if Maven died mid-run.

## Testing the Migration Itself

1. **Unit-test pass:** `mvn clean install -DskipITs -T 1C`. All ported unit
   tests green.
2. **Integration-test pass:** `mvn clean install -Pintegration-tests -fae`.
   All 5 IT modules green, including the previously-`@Disabled` Hub ITs.
3. **HSQLDB residue check:**
   - `grep -rn hsqldb .` (excluding this design doc) returns nothing
     load-bearing.
   - `mvn dependency:tree | grep -i hsqldb` returns nothing.
4. **Credential hygiene:** `git status` after a fresh clone +
   `mvn -Pintegration-tests` shows no accidentally-staged
   `it-db.properties`.
5. **SSO still works:** The `SSOLoginIT` and `callbackWithoutCodeRedirectsToLoginError`
   scenarios still pass — they now share the per-module PG container.

## Risks & Open Questions

- **Cargo context XML Maven-resource-filtering gotcha.** Cargo's `ROOT.xml`
  has historically been finicky about filtering. Implementation plan should
  verify filtering works end-to-end before claiming it done; fall back to
  `<configfiles>` with Cargo's own token replacement if needed.
- **Parallel Maven reactor builds and port 55432.** CLAUDE.md already
  forbids `-T` with ITs; this design assumes that rule holds. If someone
  ever needs parallel ITs, randomized ports (docker-maven-plugin's
  `<portPropertyFile>`) is the documented migration path.
- **`migrate.sh` portability.** The script uses bash + psql. Windows CI is
  already excluded by `@DisabledOnOs(OS.WINDOWS)` on the ITs, so this is
  consistent with the current constraints.
- **First-run bootstrap of `it-db.properties`.** If the Maven
  auto-copy-from-template step proves awkward, we can fall back to
  documenting a one-liner `cp` in the README. Not worth over-engineering.

## Success Criteria

- Every IT module boots a real PG+pgvector container, applies real
  `migrate.sh` migrations, and runs its tests against a real PG-backed
  Tomcat.
- The previously-`@Disabled` Hub ITs run and pass.
- No HSQLDB anywhere in the build, dependencies, or test sources.
- Credentials live in a gitignored file; no secrets reach the repo.
- `integration-tests` profile covers SSO, pgvector, Hub, and every prior IT
  without opt-in profile flags.

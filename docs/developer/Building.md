# Building Wikantik

This page is for developers who build Wikantik from source. It covers the tool versions you need, the Maven build variants, long-build handling for agents and scripts, the frontend build, and the local deploy loop. For the test commands see [Testing.md](Testing.md).

## Check the prerequisites

| Tool | Version | Source |
|------|---------|--------|
| Java (JDK) | 25 | `jdk.version` in the root `pom.xml`; the `Dockerfile` builds on `maven:3.9-eclipse-temurin-25` |
| Maven | 3.9+ recommended; the enforcer floor is 3.5 (`maven.version` in the root `pom.xml`) | `mvn -version` |
| Node.js + npm | 20.19+ (or 22.12+) for Vite 8; CI uses Node 22 for the gates and Node 20 for releases | `.github/workflows/` |
| PostgreSQL + pgvector | 15+ for local deployment; tests and `docker-compose.yml` use the `pgvector/pgvector:pg18` image | `PostgresTestDb`, `docker-compose.yml` |
| Docker | any recent version; needed for database-backed unit tests, the IT phase and the embedder | see [Testing.md](Testing.md) |
| Tomcat | 11.0.26 (pinned in the `Dockerfile`; `bin/deploy-local.sh` downloads it into the gitignored `tomcat/` directory) | `Dockerfile` |

## Build the project

Run these from the repository root.

```bash
# Standard build: compiles, runs the unit tests, installs artifacts
mvn clean install

# Skip running tests but still compile them and build the test-jars
mvn clean install -DskipTests

# Unit tests only, parallel build (do not use -T for integration tests)
mvn clean install -T 1C -DskipITs
```

The root pom's `defaultGoal` is `verify apache-rat:check`, so a bare `mvn` runs `verify` without an implicit `clean`.

### Use -DskipTests, not -Dmaven.test.skip

Use `-DskipTests`. `-Dmaven.test.skip` also skips building `wikantik-main`'s test-jar, which `wikantik-tools`, `wikantik-admin-mcp`, `wikantik-knowledge` and the IT modules depend on. A full-reactor build then fails with "could not resolve ...:jar:tests", most often right after a version bump when no test-jar is cached in `~/.m2`.

### Control unit-test forking

Unit tests in `wikantik-main` and `wikantik-rest` run across forked JVMs. `wikantik.surefire.forkCount` defaults to `0.5C` in the root pom. Force a sequential run when you suspect a cross-test interaction:

```bash
mvn test -Dwikantik.surefire.forkCount=1
```

### Run a single test

```bash
mvn test -Dtest=MarkdownRendererTest
mvn test -Dtest=MarkdownRendererTest#testMarkupSimpleMarkdown
mvn test -pl wikantik-main -Dtest=MarkdownRendererTest -q   # one module only
```

## Run long builds through agent-build.sh

Any Maven run that can exceed about five minutes (the full unit build, the IT reactor) goes through `bin/agent-build.sh`. A bare foreground call is killed at the agent tool's roughly ten-minute cap, and a bare `nohup mvn -q ... &` leaves a log where success and a crash look the same.

```bash
bin/agent-build.sh start unit -- mvn clean install -DskipITs
bin/agent-build.sh status unit      # RUNNING | SUCCESS | FAILED | KILLED
bin/agent-build.sh wait unit 540    # bounded block; exit 0 = success, 1 = failed/killed, 2 = still running
bin/agent-build.sh tail unit 30     # last 30 log lines
```

The script detaches the build into its own session, writes `.build-logs/<name>.log`, appends an `EXIT=<code>` sentinel, and unsets `WIKANTIK_*` environment variables in the child (the test suite requires them unset). Poll `status` or `wait` until the build ends; nothing resumes an idle agent.

## Build the frontend

The WAR build builds the React SPA for you: `wikantik-war/pom.xml` runs `npm ci --no-audit --no-fund --ignore-scripts` and then `npm run build` in `wikantik-frontend` through `exec-maven-plugin`, and bundles `dist/` into the WAR. To work on the SPA alone:

```bash
cd wikantik-frontend
npm ci
npm run dev        # Vite dev server on http://localhost:5173/, proxying /api, /attach, /admin to localhost:8080
npm run build
npm run lint
npm run test:coverage
```

See [FrontendArchitecture.md](FrontendArchitecture.md) for the SPA layout.

## Deploy and iterate locally

`bin/deploy-local.sh` bootstraps the gitignored `tomcat/tomcat-11` directory: it downloads Tomcat if absent, renders `ROOT.xml` and `wikantik-custom.properties` from `.env`, runs `bin/db/migrate.sh`, and deploys the WAR. The first run copies `.env.example` to `.env` and exits so you can set `POSTGRES_PASSWORD`. The database setup is in [PostgreSQL](../admin/PostgreSQL.md).

```bash
mvn clean install -DskipTests -T 1C
bin/deploy-local.sh                  # first time, Tomcat upgrades, or property/template changes
tomcat/tomcat-11/bin/startup.sh      # http://localhost:8080/
```

For the routine loop after the first deploy, `bin/redeploy.sh` shuts Tomcat down, rotates `catalina.out`, swaps the WAR, applies pending migrations and starts Tomcat. It does not re-render templates or validate secrets, so run `bin/deploy-local.sh` when you change `wikantik-custom.properties` or `ROOT.xml`.

```bash
mvn clean install -DskipTests -T 1C
bin/redeploy.sh
```

## Run other build tools

```bash
mvn clean install -Pcoverage -DskipITs     # JaCoCo report plus the per-module line-coverage floor check
mvn pmd:check -Pcomplexity-gate            # complexity ratchet (see CodeQuality.md)
mvn javadoc:javadoc                        # Javadocs
bin/site.sh                                # code-health site into target/staging/index.html
```

`mvn apache-rat:check` is informational only; a red result is not a regression to chase.

---
title: Debugging Failing Integration Tests
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ485C06BMPZ28QRZK8NM8K1
audience:
- agents
- humans
runbook:
  when_to_use:
  - A bin/run-tests.sh integration-test module (or mvn -Pintegration-tests) fails
    and you can't immediately tell why
  - The same test passes locally in isolation but fails in the full IT run
  - You see "Port number NNNN is in use", "address already in use", or a pgvector
    container that will not start
  inputs:
  - The failing IT module (one of rest, sso, knowledge-disabled, custom-jdbc, dense,
    scim-fullloop)
  - The error message from the module log or the failsafe report
  steps:
  - 'Confirm how you ran it. Parallel IT execution is supported only through `bin/run-tests.sh
    --parallel N`; do not add `-T` to a raw `mvn clean install -Pintegration-tests`.
    Re-run through the script: `bin/run-tests.sh --module rest` (rest|sso|knowledge-disabled|custom-jdbc|dense|scim-fullloop)'
  - 'Make sure the ITs test your current code: the IT phase uses the WAR the unit
    phase installed, so run `bin/run-tests.sh --unit` (or `mvn clean install -DskipTests`,
    never `-Dmaven.test.skip`) after any code change'
  - 'Check prerequisites: the Docker daemon is running, `psql` is on the PATH, and
    `wikantik-it-tests/it-db.properties` was copied from `it-db.properties.template`'
  - 'Clear stale containers from earlier runs: `docker ps -a | grep wikantik-pg`,
    then `docker rm -f -v <name>`; or sweep them with `bin/docker-cleanup.sh` (dry
    run) and `bin/docker-cleanup.sh --apply`. bin/run-tests.sh already applies that
    sweep at the start of every run'
  - 'Re-run only the failing module, detached so the harness cannot kill it: `bin/agent-build.sh
    start it -- bin/run-tests.sh --module rest`, then `bin/agent-build.sh wait it
    540` (exit 0 success, 1 failed, 2 still running) and `bin/agent-build.sh tail
    it 40`'
  - 'Read the log the script wrote for the module: `.test-suite-logs/it-<module>.log`
    (`it-parallel.log` for `--parallel`), and the summary in `.test-suite-logs/report.txt`'
  - 'Then read the module''s own output under `wikantik-it-tests/wikantik-it-test-<module>/target/`:
    `failsafe-reports/*.txt` for the failing test, and `cargo.log` and `tomcat.log`
    for the container and the application''s own stack trace'
  - For an intermittent browser (Selenide) failure, re-run the module once; the browser
    runs headless by default, and `-Dit-wikantik.config.headless=false` shows it (see
    the comment in `wikantik-it-tests/pom.xml`). If the red test is a `wikantik-main`
    unit test rather than an IT, re-run it alone with `mvn test -pl wikantik-main
    -Dtest=Class#method` and add `-Dwikantik.surefire.forkCount=1` to rule out cross-test
    interaction
  pitfalls:
  - Putting `-T` on a raw `mvn -Pintegration-tests` run — port and container collisions;
    `bin/run-tests.sh --parallel N` is the supported route
  - Running an IT module against a WAR built before your change — the IT phase does
    not rebuild, so re-run the unit phase first
  - Ignoring leftover `wikantik-pg-*` containers or a stopped Docker daemon — the
    module fails at start with an unrelated-looking error
  - Reading only the Maven console output — the actual exception is usually in failsafe-reports,
    `cargo.log` or `tomcat.log`
  - Re-running the whole gate when only one module is red — use `--module <name>`;
    it is faster and keeps the signal clean
  - Treating the `dense` module's first run as a hang — `bin/run-tests.sh --module
    dense` starts a shared CPU ollama container on port 11435 and the first run downloads
    the ~600 MB `qwen3-embedding:0.6b` model; a bare `mvn -pl` on that module skips
    the setup and fails at `@BeforeAll`
  references:
  - BuildingAndDeployingLocally
  - ContinuousIntegration
summary: 'First aid for a red Wikantik integration-test run: run it via bin/run-tests.sh,
  clear stale wikantik-pg containers, and find the real error in the logs.'
tags:
- testing
- integration
- runbook
- agent-context
---
# Debugging Failing Integration Tests

The integration tests (ITs) live under `wikantik-it-tests/`. Each IT module uses Cargo to start its own Tomcat 11 against its own PostgreSQL + pgvector container (`pgvector/pgvector:pg18`), with every migration in `bin/db/migrations/` applied and the fixtures in `it-test-seed.sql` loaded. Most IT failures are environmental, not code defects — a stale container, a stale WAR, a missing Docker daemon, or the wrong way of invoking Maven.

## When to use this runbook

When you see red and don't yet know whether it's your code or the test harness.

## Context

The default IT modules are `rest` (REST + SCIM), `sso` (Keycloak OIDC and SAML), `knowledge-disabled`, `custom-jdbc` (the Selenide browser suite) and `dense` (embeddings enabled). `scim-fullloop` is an opt-in Authentik suite, and `wikantik-selenide-tests` is shared support code, not a suite of its own.

Under `bin/run-tests.sh` every module reserves its own free ports and its own container, named `wikantik-pg-<module artifactId>`, so modules do not collide with each other, with a development Tomcat on 8080, or with a development database. Collisions come back when that is bypassed: `-T` on a raw `mvn -Pintegration-tests`, or two runs in one checkout (the script takes a per-checkout lock for exactly this reason). The pgvector container is deliberately stopped rather than removed at the end of a run so its logs stay readable, which is also why a killed run leaves containers behind.

The IT phase does not rebuild anything. It runs each module with `-pl <module>` and no `-am`, against the artifacts, including the WAR, that the unit phase installed to `~/.m2`. A `wikantik-main` unit test that fails differently on each run is a separate problem (suspect order-dependent shared state); it shows up in the unit phase, not here.

## Walkthrough

The frontmatter `steps` are the canonical first-aid sequence. Steps 1–4 catch the environmental issues; step 5 narrows the scope; steps 6–8 locate the actual error.

## Pitfalls

The frontmatter `pitfalls` are the recurring traps. Adding `-T` to a raw `mvn -Pintegration-tests` command is the most common cause of collisions — agents copy unit-test commands into IT contexts without editing. The second most common is running an IT module against a WAR built before the code change.

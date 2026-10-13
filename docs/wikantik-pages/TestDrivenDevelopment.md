---
title: Test Driven Development (TDD)
cluster: wikantik-development
date: '2026-05-04'
verified_at: '2026-05-04T21:10:44.598011331Z'
type: article
status: active
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD4FGSZVWM8WR7685RNNVQ
summary: The mandatory TDD workflow, testing levels, and best practices for developing
  in Wikantik.
tags:
- test-driven-development
- tdd
- testing
- wikantik-development
---
# Test Driven Development (TDD) in Wikantik

**Test Driven Development (TDD)** is not just a practice in Wikantik; it is a mandatory mandate. As recorded in the repository's agent instructions (`GEMINI.md` and `CLAUDE.md`), a defect gets a test that demonstrates it before the fix is written, so we know the test actually detects the issue.

## The TDD First Mandate
The core rule for all developers and AI agents is:
> **TDD First:** Always write or update a test before fixing a bug or refactoring.

This ensures that:
1. **Requirements are clear:** Writing the test forces a clear definition of what "success" looks like.
2. **Regressions are caught:** The suite provides a safety net for future changes.
3. **Design is improved:** Code that is hard to test is usually code that is poorly designed.

## The Testing Pyramid

### 1. Unit Tests (Fast & Isolated)
Unit tests are JUnit 5 and live under each module's `src/test`. They target individual classes in isolation, which is made possible by the use of **Stubs**.
- **Example:** Testing a new MCP tool using `StubPageManager` rather than bootstrapping a full `WikiEngine`. `TestEngine` builds a real engine for the tests that genuinely need one.
- **Database-backed tests use a real database, not H2.** Mark the class `@RequiresPostgres` and call `PostgresTestDb.createDataSource()` (from the `wikantik-jdbc` test-jar): you get one `pgvector/pgvector:pg18` container per JVM with every migration in `bin/db/migrations/` applied. Without Docker these tests skip locally with a visible reason; CI passes `-Dtests.requireDocker=true` so an absent daemon fails the run. Write a migration, never a hand-written `CREATE TABLE`, when a test needs a table.
- **Target:** line coverage above 90% is the team's goal. It is enforced per module by JaCoCo floors (`wikantik.coverage.line.minimum` in each module's pom, for example 0.90 for `wikantik-main` and 0.88 for `wikantik-api`) when you build with `mvn clean install -Pcoverage -DskipITs`. Floors only ever go up and are never set above 0.90.

### 2. Integration Tests (Component Interplay)
Found in the `wikantik-it-tests` module and run only under the `integration-tests` profile, these tests verify that multiple modules work together correctly.
- **Cargo:** The build uses the Cargo plugin to launch a real Tomcat 11 instance for each IT module.
- **PostgreSQL:** Each module gets its own pgvector container with the schema applied by the production `migrate.sh` and fixtures loaded from `it-test-seed.sql`.
- **Modules:** `rest` (REST + SCIM), `sso`, `knowledge-disabled`, `custom-jdbc` and `dense` run by default; `scim-fullloop` is opt-in.

### 3. End-to-End Tests (User Flows)
Using **Selenide**, the project automates browser-based flows (login, page creation, admin actions) to ensure the React SPA and the Java backend are in sync. The browser runs headless by default.

## TDD Workflow for AI Agents

1. **Research:** Understand the current behavior and existing tests.
2. **Reproduction:** Write a failing test case that demonstrates the bug or the missing feature, and run it to watch it fail — `mvn test -pl <module> -Dtest=ClassName#methodName`.
3. **Execution:** Implement the minimal code change needed to make the test pass.
4. **Validation:** Run the module's tests (`mvn test -pl <module>`) to ensure no regressions, then the canonical gate before committing: `bin/run-tests.sh --parallel 4` (every unit test once, then the integration modules in parallel). Anything that can exceed about five minutes runs detached through `bin/agent-build.sh`.

## See Also
- [Test Stub Conversion](TestStubConversion) — How the project moved to faster, more isolated unit testing.
- [Constructor Injection](ConstructorInjection) — Why Wikantik's code is so easy to test.
- [Agentic Content Quality CI](AgentGradeContentDesign) — The automated loop that measures retrieval quality.

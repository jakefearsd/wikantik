---
date: '2026-05-04 inspiried_by: "GEMINI.md"'
summary: The mandatory TDD workflow, testing levels, and best practices for developing
  in Wikantik.
cluster: wikantik-development
verified_by: gemini-cli-mcp-client
verified_at: '2026-05-04T21:10:44.598011331Z'
canonical_id: 01KQTD4FGSZVWM8WR7685RNNVQ
type: article
title: Test Driven Development (TDD)
status: active
tags:
- test-driven-development
- tdd
- testing
- wikantik-development
hubs:
- WikantikDevelopment
---
# Test Driven Development (TDD) in Wikantik

**Test Driven Development (TDD)** is not just a practice in Wikantik; it is a mandatory mandate. As defined in `GEMINI.md`, every code change must be preceded or accompanied by a test case that verifies the intended behavior.

## The TDD First Mandate
The core rule for all developers and AI agents is:
> **TDD First:** Always write or update a test before fixing a bug or refactoring.

This ensures that:
1. **Requirements are clear:** Writing the test forces a clear definition of what "success" looks like.
2. **Regressions are caught:** The suite provides a safety net for future changes.
3. **Design is improved:** Code that is hard to test is usually code that is poorly designed.

## The Testing Pyramid

### 1. Unit Tests (Fast & Isolated)
Unit tests target individual classes in isolation. In Wikantik, these are made possible by the use of **Stubs**.
- **Example:** Testing a new MCP tool using `StubPageManager` rather than bootstrapping a full `WikiEngine`.
- **Target:** 80% coverage for core logic in `wikantik-api` and `wikantik-main`.

### 2. Integration Tests (Component Interplay)
Found in the `wikantik-it-tests` module, these tests verify that multiple modules work together correctly.
- **Cargo:** The build uses the Cargo plugin to launch a real Tomcat instance for testing.
- **PostgreSQL:** Integration tests run against a real PostgreSQL instance with a scratch schema.

### 3. End-to-End Tests (User Flows)
Using **Selenide**, the project automates browser-based flows (login, page creation, admin actions) to ensure the React SPA and the Java backend are in sync.

## TDD Workflow for AI Agents

1. **Research:** Understand the current behavior and existing tests.
2. **Reproduction:** Write a failing test case that demonstrates the bug or the missing feature.
3. **Execution:** Implement the minimal code change needed to make the test pass.
4. **Validation:** Run the full module test suite (`mvn test -pl <module>`) to ensure no regressions.

## See Also
- [Test Stub Conversion](TestStubConversion) — How the project moved to faster, more isolated unit testing.
- [Constructor Injection](ConstructorInjection) — Why Wikantik's code is so easy to test.
- [Agentic Content Quality CI](AgentGradeContentDesign) — The automated loop that measures retrieval quality.

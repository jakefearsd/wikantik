---
date: '2026-05-04 inspiried_by: "docs/ci-cd-step-by-step.md"'
summary: An overview of the Wikantik CI pipeline, including automated builds, integration
  tests, and retrieval-quality benchmarking.
cluster: wikantik-development
verified_by: gemini-cli-mcp-client
verified_at: '2026-05-04T21:10:44.598011331Z'
canonical_id: 01KQTD5WE6WZ7VXARBJHXSZZH5
type: article
title: Continuous Integration (CI) in Wikantik
status: active
tags:
- continuous-integration
- ci-cd
- testing
- wikantik-development
hubs:
- WikantikDevelopment
---
# Continuous Integration (CI) in Wikantik

**Continuous Integration (CI)** is the heartbeat of the Wikantik development lifecycle. It ensures that every change to the codebase is automatically built, tested, and validated against the project's high quality standards.

## CI Philosophy
The Wikantik CI process is designed to be:
- **Fast:** Providing rapid feedback to developers.
- **Exhaustive:** Testing not just the code, but also the content and metadata.
- **Automated:** No human intervention is required to run the standard suite.

## The CI Pipeline

### 1. Build and Unit Testing
On every commit to `main`, the CI runner executes:
```bash
mvn clean install -Dmaven.test.skip -T 1C
```
This is followed by the full unit test suite for each module. The build fails if any test is red.

### 2. Integration Testing (IT)
The `wikantik-it-tests` module runs complex integration tests using a real Tomcat 11 container and a PostgreSQL database.
- **Cargo:** Launches the container.
- **Liquibase:** Applies all migrations to a fresh schema.
- **Selenide:** Runs browser-based end-to-end tests for the React SPA.

### 3. Structural Spine Validation
The CI pipeline runs a specialized check to ensure that all pages in `docs/wikantik-pages/` comply with the [FrontmatterConventions](FrontmatterConventions). 
- Rejects pages with missing `canonical_id`.
- Validates `relations:` integrity.
- Ensures the auto-generated `Main.md` is in sync with `Main.pins.yaml`.

### 4. Retrieval-Quality Benchmarking
A unique feature of the Wikantik CI is the nightly retrieval-quality run.
- **Harness:** Uses the `search-eval` tool.
- **Metrics:** Measures Recall, MRR, and nDCG for the hybrid search engine.
- **Regressions:** If search quality drops below the established baseline, the build is flagged for manual review.

## CI/CD Stack
- **Runner:** Self-hosted runner (documented in `docs/ci-cd-step-by-step.md`).
- **Environment:** Docker-based execution to ensure parity with production.
- **Reporting:** Prometheus metrics (`wikantik_retrieval_ndcg_at_5`) are pushed to a central monitoring instance.

## See Also
- [Test Driven Development (TDD)](TestDrivenDevelopment) — The developer workflow that feeds the CI.
- [Agentic Content Quality CI](AgentGradeContentDesign) — Details on the retrieval benchmarking loop.
- [Deployment Guide](PostgreSQLLocalDeployment) — How to stand up the environment that CI mimics.

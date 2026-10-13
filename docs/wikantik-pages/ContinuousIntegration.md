---
title: Continuous Integration (CI) in Wikantik
cluster: wikantik-development
date: '2026-05-04'
verified_at: '2026-05-04T21:10:44.598011331Z'
type: article
status: active
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD5WE6WZ7VXARBJHXSZZH5
summary: 'What runs in GitHub Actions for Wikantik and when: push-time static gates,
  the weekly unit suite, the tag-driven release build, and the local IT gate.'
tags:
- continuous-integration
- ci-cd
- testing
- wikantik-development
---
# Continuous Integration (CI) in Wikantik

Wikantik's CI is deliberately thin, to conserve GitHub Actions minutes: a set of fast **static gates** runs on every push to `main`, the heavy **Java unit suite** runs weekly (or on demand), the **integration tests** never run in CI, and a **tag push** builds and publishes the release image. The full gate is a local one (`bin/run-tests.sh --parallel 4`). This page summarizes what runs where; the workflow files in `.github/workflows/` and `docs/developer/CI.md` in the repository are authoritative.

## What runs when

| Workflow | Trigger | Runner | Role |
|----------|---------|--------|------|
| `quality-gates.yml` | push to `main`, weekly schedule (Mondays 04:00 UTC), manual | GitHub-hosted | `static-gates`, `shell-tests` and `osv-scan` on every push; the Java unit suite (`unit-tests`) only weekly and on manual dispatch; `report-failures` opens one issue per red job |
| `release.yml` | push of a `v*.*.*` tag | GitHub-hosted | The release pipeline: WAR, multi-arch image to GHCR, GitHub Release |
| `dependency-review.yml` | pull requests | GitHub-hosted | Dependency diff review |
| `codeql.yml` | manual (`workflow_dispatch`) | GitHub-hosted | Security scanning on demand |
| `ci-cd.yml`, `staging-deploy.yml` | manual (`workflow_dispatch`) | `self-hosted` | Legacy build-and-deploy and staging flows; dormant, no self-hosted runner has been registered since about May 2026 |

## On every push to main: Quality Gates

- **`static-gates`**: a full-reactor build without running tests (`mvn -B -T 1C clean install -DskipTests`); the **complexity ratchet** (`mvn -B -fn pmd:check -Pcomplexity-gate`, which passes only if the reactor prints `BUILD SUCCESS`, and fails on any new PMD design-rule violation not already in `build-support/pmd-complexity-baseline.properties`; baseline entries only ever come out); frontend ESLint; `npm audit signatures`; `npm audit` (high severity blocks); a check that `package-lock.json` is unchanged; and Vitest with its coverage thresholds.
- **`shell-tests`**: seeds `.env` from `.env.example`, runs every `bin/tests/test-*.sh` (the deploy and ops tooling suites such as `remote.sh`, `container.sh` and the backup scripts, dry-run and stub style), then the **Markdown relative links** check, `python3 bin/check-md-links.py`.
- **`osv-scan`**: a CycloneDX SBOM of the Maven tree (all scopes, including test) plus the npm lockfile, scanned against OSV.dev with `osv-scanner.toml`.
- **`report-failures`**: after the jobs above, opens or comments on one GitHub issue per red job and closes it when that job next passes.

## Weekly: the Java unit suite

The `unit-tests` job runs only on the Monday schedule and on manual dispatch, never on push: `mvn -B clean install -DskipITs -Dtests.requireDocker=true -Pcoverage` (sequential, 120-minute timeout). That is the full unit suite plus the per-module JaCoCo line-coverage floors, with a missing Docker daemon failing the run instead of skipping the database tests. Because it is weekly, **a push to `main` never runs the Java unit suite or the coverage floors**; mirror it locally with `mvn clean install -DskipITs -Pcoverage` before dispatching it, and run the unit tests yourself before you push.

## Not in CI: integration tests

The integration reactor (Cargo-launched Tomcat 11, PostgreSQL with pgvector, Selenide browser tests) needs Docker and is not run on hosted runners. It is a local pre-commit gate: `bin/run-tests.sh --parallel 4` runs the unit phase and then the five default IT modules in one 4-wide reactor with per-module ports and containers. See [Wikantik Testing Guide](WikantikTestingGuide) and [Test Driven Development (TDD)](TestDrivenDevelopment). The database schema is created by the `bin/db/migrations/V*.sql` migrations (there is no Liquibase).

## The release pipeline

Pushing a tag matching `v*.*.*` triggers `release.yml`, which builds the WAR with `-DskipTests`, builds the multi-arch (amd64 and arm64) image, publishes `ghcr.io/jakefearsd/wikantik:X.Y.Z` and `:latest`, and creates a GitHub Release with the WAR attached. It skips the test suite by design, so the local gate before tagging is the only test gate. Deployment is not a CI job: it is driven from the developer box with `bin/deploy-release.sh`. See [Wikantik Release and Deploy Process](WikantikReleaseAndDeployProcess).

## Retrieval quality is a runtime job, not a CI job

The nightly retrieval-quality run is not part of any workflow. It is scheduled inside the running wiki (default 03:00 UTC, `wikantik.retrieval.cron.hour_utc`; switch off with `wikantik.retrieval.cron.enabled=false`), computes nDCG, Recall and MRR for the BM25 and hybrid modes against a curated query set, stores each run in the `retrieval_runs` table, and publishes Prometheus gauges such as `wikantik_retrieval_ndcg_at_5` for the monitoring stack to scrape. Nothing blocks a build on those numbers; a regression shows up on the admin dashboard and in the gauges. See [Running the Retrieval Quality Harness](RunningTheRetrievalQualityHarness) and [Evaluating Retrieval Quality](EvaluatingRetrievalQuality).

## Content checks

Page-level rules are enforced where pages are written, not by a CI job: the save-time structural-spine filter and the frontmatter schema validator (see [Frontmatter Conventions](FrontmatterConventions) and [Agent-Grade Content Design](AgentGradeContentDesign)). The repository's generated `Main.md` is guarded by `MainPageRegressionTest` (in `wikantik-extract-cli`), which fails if it drifts from `Main.pins.yaml`; like the rest of the unit suite it runs weekly and locally, not per push.

## See Also

- [Test Driven Development (TDD)](TestDrivenDevelopment) — The developer workflow that feeds the CI.
- [Wikantik Testing Guide](WikantikTestingGuide) — Test layers, the canonical gate and the quality ratchets.
- [Wikantik Release and Deploy Process](WikantikReleaseAndDeployProcess) — From tag to production.
- [Deployment Guide](PostgreSQLLocalDeployment) — How to stand up the environment locally.

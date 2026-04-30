---
canonical_id: 01KQ0P44N8EYGZDNWNF37FQQSV
title: CI/CD Pipelines
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: How modern CI/CD pipelines work — stages, deployment strategies, the tools
  that have aged well, and the patterns that scale to large teams.
tags:
- ci-cd
- continuous-integration
- continuous-delivery
- pipelines
- automation
related:
- DevOpsFundamentals
- TrunkBasedDevelopment
- ReleaseEngineering
- FeatureToggleManagement
hubs:
- DevOpsAndSreHub
---
# CI/CD Pipelines

Continuous Integration (CI): every change goes through automated build and test.
Continuous Delivery (CD): the code is always ready to ship.
Continuous Deployment: shipping happens automatically.

Modern software teams almost universally use CI/CD. The patterns and tools have stabilized; the differences are operational.

## The stages

A typical pipeline:

1. **Trigger**: code push, PR, schedule, manual
2. **Build**: compile, package; produce artifact
3. **Unit test**: fast tests, run in parallel
4. **Static analysis**: linting, type checking, security scanning
5. **Integration test**: tests with real dependencies (DB, cache)
6. **Artifact storage**: the build output, versioned
7. **Deploy to environments**: dev → staging → production
8. **Post-deploy verification**: smoke tests, canary checks

Each stage gates the next. Failures stop the pipeline.

## Pipeline-as-code

Modern CI/CD: pipelines defined in code, in the repo:

```yaml
# .github/workflows/build.yml
name: Build and test
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./mvnw clean test
```

Pros: version controlled with the code; reviewable; reproducible.
Cons: tied to the CI tool's syntax.

GitHub Actions, GitLab CI, CircleCI, Jenkins (declarative pipelines) all support this. Avoid older "configure in UI" CI systems.

## Deployment strategies

### Direct (push and pray)

Just deploy. Simplest. Risky for production.

### Rolling

Replace instances one at a time. New version coexists with old briefly.

### Blue-green

Two identical environments. Switch traffic from blue to green. Easy rollback.

### Canary

Route a small percentage of traffic to the new version. Monitor; expand or roll back.

### Feature flags

Deploy code; turn features on/off via flag. Decouple deploy from release. See [FeatureToggleManagement](FeatureToggleManagement).

For most production systems, canary or blue-green are the standards. Feature flags add another layer of control.

## The CI tools

### GitHub Actions

The most popular. Well-integrated with GitHub. YAML pipeline definition; reusable workflows.

### GitLab CI

Tightly integrated with GitLab. Mature; full-featured.

### CircleCI

Independent CI. Fast; good developer experience.

### Jenkins

Old school but still common. More flexible than the others; more operational overhead.

### Buildkite

Hybrid (orchestrator in cloud; agents you run). Popular for performance-sensitive needs.

### Cloud-native

AWS CodePipeline, GCP Cloud Build, Azure Pipelines. Useful when integrated with cloud workflows.

For most teams, GitHub Actions if on GitHub, GitLab CI if on GitLab. The tool matters less than the pipeline design.

## Patterns that scale

### Fast feedback

Developers want their tests to fail fast. Order stages by speed:
- Lint first (seconds)
- Unit tests next (minutes)
- Integration tests (slower)
- Deploy stages (slowest)

Failure at lint stage takes seconds; failure at deploy takes much longer.

### Parallelization

Independent test groups run in parallel. Reduces total time dramatically.

### Caching

Build dependencies (npm packages, Maven .m2, Docker layers). Don't re-download every build.

### Matrix builds

Test against multiple versions (Node 18, 20, 22; Linux + macOS). Run matrix in parallel.

### Incremental builds

Only rebuild what changed. Modern monorepo tools (Nx, Turborepo, Bazel) support this. See [MonorepoVsPolyrepo](MonorepoVsPolyrepo).

### Required checks

Pipeline must pass before merge. Enforced via branch protection.

### Deployment automation

Production deploys triggered by main branch merges (or manual approvals for sensitive systems). The path from code to production is automatic.

## Specific patterns

### Versioned artifacts

Build artifacts tagged with git SHA or semver. Same artifact deployed to dev, staging, prod — no rebuild between environments.

### Environment promotion

Deploy to dev → run tests → promote to staging → run more tests → promote to production. Same artifact through the chain.

### Rollback as fast as deploy

Rolling back to the previous version should be a single command or button click. Slow rollback compounds incidents.

### Smoke tests post-deploy

Quick health checks after deploy: is the service responding? Can it connect to dependencies? Catches some failures before they affect users.

### Canary analysis

Automatic comparison of canary metrics to baseline. If canary error rate is higher, auto-rollback. Manual canaries are slow.

## Common failure patterns

- **Slow pipelines.** Developers wait; productivity drops.
- **Flaky tests in CI.** Trust erodes; failures get ignored.
- **Different paths per environment.** "Works in dev; broken in prod."
- **No rollback capability.** Disasters compound.
- **Manual deployment steps.** Human error; slow.
- **No staged rollouts.** Bad deploys hit all users.
- **CI runs only on schedule, not per-commit.** Slow feedback.

## Further Reading

- [DevOpsFundamentals](DevOpsFundamentals) — Broader practice
- [TrunkBasedDevelopment](TrunkBasedDevelopment) — Branching strategy
- [ReleaseEngineering](ReleaseEngineering) — Adjacent practice
- [FeatureToggleManagement](FeatureToggleManagement) — Flags as control
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

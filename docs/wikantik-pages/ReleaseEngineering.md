---
cluster: devops-sre
date: '2026-04-26'
title: Release Engineering
hubs:
- DevOpsAndSreHub
tags:
- release-engineering
- deployment
- devops
- automation
summary: Getting code from main to production safely — release artifacts, signing,
  rollback, and deployment strategies for high-performing teams.
related:
- CiCdPipelines
- FeatureToggleManagement
- ReleasePlanning
- TrunkBasedDevelopment
canonical_id: 01KQ0P44VD2G5RCTN8NVS7DA41
type: article
status: active
---
# Release Engineering

Release engineering is the discipline of taking code from main and getting it to production safely. The "code is on main" to "users can use it" gap involves real engineering: artifacts, signing, deployment strategies, rollback, validation.

This page covers the practices that distinguish good release engineering.

## Build artifacts

The output of CI: a deployable thing.

### What an artifact is

- **Container image**: Docker tagged with build SHA
- **JAR/WAR**: Java deployment artifact
- **Native binary**: Go, Rust output
- **Static files**: SPA build output
- **Helm chart**: Kubernetes deployment

The artifact is what gets deployed. Same artifact through dev → staging → prod.

### Versioning

Artifacts tagged with:
- Git SHA (for traceability)
- Semantic version (for human consumption)
- Build number (for ordering)

Common: `v1.2.3-build.456-abc1234`

### Storage

Artifacts in a registry: Docker Hub, ECR, Artifactory, GitHub Packages. Versioned; immutable; auditable.

## Signing and provenance

For supply-chain security:

### Sign artifacts

Cosign, Sigstore, or vendor-specific. The artifact has a signature proving it came from your CI.

### Provenance

Records of how the artifact was built: from what source; with what dependencies; on what infrastructure. SLSA (Supply-chain Levels for Software Artifacts) provides a framework.

For sensitive software, provenance is required. For others, it's emerging best practice.

## Deployment strategies

### Rolling

Replace instances one at a time. Standard Kubernetes default.

Pros: simple; minimal extra resources.
Cons: brief co-existence of versions; rollback is another rolling deploy.

### Blue-green

Two identical environments. Switch traffic from blue (current) to green (new).

Pros: instant cutover; instant rollback.
Cons: 2x resources during deploy; two environments to maintain.

### Canary

Deploy to small subset; verify; expand.

Pros: limits blast radius; auto-rollback possible.
Cons: more complex orchestration.

### Feature-flag-driven

Deploy code disabled; toggle via flag.

Pros: total control over release timing; instant rollback via flag.
Cons: requires flag infrastructure; code includes both paths.

For most modern deploys, canary + feature flags is the gold standard.

## Rollback

When deploys go wrong, rollback fast.

### Rollback as a button

The same automation that deploys should rollback. Click button; previous version restored.

### Rollback should not require fixing

Don't require a forward fix during an incident. Rollback first; debug later.

### Rollback should not lose data

Schema migrations need backwards compatibility. New code should work with old schema; old code should work with new schema (during transition).

The expand-and-contract pattern: add new schema columns (expand), deploy code that uses both, remove old usage (contract). Allows rollback at every step.

### Forward fix vs. rollback

For minor issues: forward fix.
For major issues: rollback first.
The decision criterion: time to safety.

## Specific patterns

### Smoke tests post-deploy

After deploy, run automated checks: critical endpoints respond; key dependencies reachable. If they fail, auto-rollback.

### Canary analysis

Compare canary metrics to baseline. If error rate or latency is worse on canary, auto-rollback.

### Database migrations separately

Don't deploy schema changes with code changes. Deploy migrations first; verify; deploy code that uses them. Each step is reversible.

### Environment promotion

Same artifact through dev → staging → prod. No rebuilds between environments.

### Deployment windows

For high-risk changes, deploy during business hours when on-call is fully staffed. Don't deploy Friday afternoon.

For mature CD, this matters less; for systems with manual response, it matters a lot.

## Specific environments

### Development

Continuous deployment from main. Engineers see their changes immediately.

### Staging

Production-like environment for final verification. Should mirror production closely.

### Production

The actual user-facing environment. Deploy from artifacts that passed staging.

## Operational practices

### Release notes

Every production release has notes: what changed, who approved, links to PRs/tickets. Useful when investigating issues.

### Change management

For regulated environments: change requests, approval workflows, change windows. Heavy but required for compliance.

For unregulated: lighter weight; auto-deploy from main with audit trail.

### Deploy frequency tracking

DORA metrics: deploy frequency, lead time, change failure rate, MTTR. Track them; improve them.

### On-call awareness

Who's on call during this deploy? They should know what's deploying and have rollback access.

## Security in releases

- **Signed artifacts**: covered above
- **Secrets management**: secrets injected at deploy, not baked in
- **Vulnerability scanning**: in CI; block deploys with known CVEs
- **SBOM**: Software Bill of Materials; what dependencies are in this build

## Common failure patterns

- **Manual deployment.** Slow; error-prone; not reproducible.
- **No rollback capability.** Disasters compound.
- **Schema migrations with code changes.** Can't rollback.
- **Deploys not tied to specific artifacts.** "What's actually running?" is unanswerable.
- **No post-deploy validation.** Bad deploys reach users.
- **Deploys after-hours.** When something goes wrong, no one's awake.

## Further Reading

- [CiCdPipelines](CiCdPipelines) — Pipeline that produces artifacts
- [FeatureToggleManagement](FeatureToggleManagement) — Flag-driven release
- [ReleasePlanning](ReleasePlanning) — Coordination across releases
- [TrunkBasedDevelopment](TrunkBasedDevelopment) — Branching for release
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

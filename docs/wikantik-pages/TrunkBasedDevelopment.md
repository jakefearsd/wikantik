---
canonical_id: 01KQ0P44Y2T10DNP9M0W1SFFQA
title: Trunk-Based Development
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: Why trunk-based development beats GitFlow for most teams — short-lived branches,
  feature flags, the practices that make it work, and the cases where long-lived
  branches are actually right.
tags:
- trunk-based-development
- git
- branching
- devops
- continuous-integration
related:
- GitWorkflows
- CiCdPipelines
- FeatureToggleManagement
- MonorepoVsPolyrepo
hubs:
- DevOpsAndSreHub
---
# Trunk-Based Development

Trunk-based development: developers work in short-lived branches, integrate to main (trunk) frequently, ideally multiple times per day. Main is always deployable.

It's the dominant pattern in modern high-performing teams. The DORA research identifies trunk-based development as one of the practices that distinguishes elite from low-performing teams.

This page covers why it works and the practices that make it work.

## The practices

### Short-lived branches

Branches live for hours or days, not weeks. A long-lived branch is the failure mode.

Why: short branches merge cleanly; long branches diverge; merging long branches is painful.

### Frequent integration

Each developer integrates to main multiple times per day. CI runs on every push. Conflicts are caught immediately.

Why: integration problems are constant tiny problems instead of occasional huge problems.

### Main is always deployable

Main always passes CI. Always shippable. If it breaks, fixing main is the team's top priority.

Why: continuous delivery requires deployable main. Otherwise "deploy" becomes "fix-and-deploy."

### Feature flags for in-progress work

Code merges to main even when the feature isn't ready. The feature is hidden behind a flag.

Why: lets you merge frequently without users seeing half-done features. See [FeatureToggleManagement](FeatureToggleManagement).

### Pair programming or fast review

Code is reviewed quickly; doesn't sit waiting for days.

Why: long review cycles defeat short branches. The branch ages while waiting.

## Why it beats GitFlow

GitFlow uses long-lived develop, feature, release, and hotfix branches. The complexity:

- Multiple long-lived branches diverge
- Releases require coordinated merges
- Hotfixes require multi-branch updates
- New developers find the model confusing

Trunk-based has:
- One long-lived branch (main)
- Short-lived feature branches
- Releases are tags or deploys, not branches

For most modern teams with CI/CD, GitFlow is overkill. Trunk-based is simpler and shipping practices favor it.

## When long-lived branches are right

- **Mobile apps with App Store releases.** Multi-week review cycles. Branch per release version makes sense.
- **Library development with semver releases.** Major version branches.
- **Embedded software with hardware sync.** Long release cycles.

For typical web/cloud software, none of these apply.

## Specific patterns

### Squash merge

When a feature branch merges, squash to one commit. Main has clean history.

### Merge directly vs. PR

Some teams merge directly to main without PRs (with paired review or post-merge review). Others require PRs but keep them short-lived.

PRs add review structure but extend branch lifespan. The trade-off depends on team culture.

### CI guarding main

Required CI checks. Cannot merge to main if tests fail. Main quality is non-negotiable.

### Reverts as first response

If main is broken, revert the bad commit. Fix forward later. Don't try to fix in place under pressure.

### Continuous deployment

Some teams deploy main automatically. Others deploy main with manual approval. Either way, main is always ready.

## Implementation considerations

### Test coverage

Without good test coverage, trunk-based is risky. Bad code merges to main; production breaks. Invest in tests before going trunk-based.

### Feature flags

Required for in-progress features. Build the flag system before relying on it.

### Code review

Fast turnaround required. Hour or two, not days. May require culture change.

### Tooling

CI/CD that gates main. Required. Branch protection rules.

## Migration from GitFlow

For teams moving from GitFlow:

1. Audit branch lifecycle: how long do branches really live?
2. Strengthen CI: catch problems before merge
3. Build feature flag infrastructure
4. Train team on the new flow
5. Remove the develop branch when ready
6. Iterate

The cultural shift takes longer than the technical shift.

## Common failure patterns

- **Long-lived branches called "trunk-based."** Just GitFlow with different names.
- **Trunk-based without feature flags.** Half-done features visible.
- **Trunk-based without good CI.** Main breaks; team scrambles.
- **Slow review.** Branches age waiting.
- **No revert culture.** Broken main fixed in place; takes longer; risks more.
- **Trunk-based forced on team that needs GitFlow.** Some products genuinely need branch-per-version.

## Further Reading

- [GitWorkflows](GitWorkflows) — Specific git practices
- [CiCdPipelines](CiCdPipelines) — Required for trunk-based
- [FeatureToggleManagement](FeatureToggleManagement) — Required for in-progress work
- [MonorepoVsPolyrepo](MonorepoVsPolyrepo) — Affects branching strategy
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

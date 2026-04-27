---
canonical_id: 01KQ0P44QPSBJJPYWSJSZKMMEK
title: Git Workflows
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: The git practices that scale — branch strategies, merge vs. rebase, commit
  hygiene, and the workflows that have aged well across large and small teams.
tags:
- git
- version-control
- workflow
- branching
- devops
related:
- TrunkBasedDevelopment
- CiCdPipelines
- MonorepoVsPolyrepo
hubs:
- DevOpsAndSre Hub
---
# Git Workflows

Git is the universal version control system. The tool is fixed; how teams use it varies. Some workflows are dramatically more productive than others.

This page covers the practices that work.

## Branch strategies

### Trunk-based

Main + short-lived feature branches. The modern default. See [TrunkBasedDevelopment](TrunkBasedDevelopment).

### GitFlow

Develop + feature + release + hotfix branches. Heavy; for software with formal release cycles.

### GitHub Flow

Main + feature branches; PR; merge. Simple variant of trunk-based.

For most modern teams, GitHub Flow or pure trunk-based.

## Merge vs. rebase

Two ways to integrate changes:

### Merge

```
A---B---C---F (main)
     \     /
      D---E (feature)
```

Preserves the branch history. Merge commit (F) joins the branches.

### Rebase

```
A---B---C---D'---E' (main)
```

Rewrites the feature branch's commits onto main. Linear history; no merge commits.

### When to use which

- **Rebase before merge** (or "merge with rebase strategy"): clean linear history
- **Merge commit**: preserves the branch shape
- **Squash merge**: collapses the branch to one commit on main

For team workflows, pick one strategy. Mixing them produces messy history.

The dominant modern preference: squash merge for feature branches. Main has one commit per feature; clean history; PRs map cleanly.

## Commit hygiene

### Atomic commits

One commit = one logical change. Not "added user feature, fixed unrelated bug, formatted some code."

Easier to review, revert, cherry-pick.

### Good commit messages

```
Subject line in imperative mood (50 chars max)

Body explaining why this change was made (72 char wrap).
What does this commit fix? Why is it needed?

Refs #123
```

The subject says what; the body says why. Future engineers (including future you) will thank you.

### Don't commit broken code

Each commit should pass tests. Bisecting (`git bisect`) requires this.

### Atomic commits even in feature branches

Within a feature branch, atomic commits help review. Squash on merge to main.

## Pull requests

The modern code review unit. Practices:

### Small PRs

PRs under 400 lines get reviewed. Larger PRs get rubber-stamped. Break large changes into multiple PRs.

### Self-review first

Read your own diff before requesting review. Catches half the "why is this here?" questions.

### Description matters

Why this change? What did you consider and reject? How did you test? The description guides the review.

### Respond to review

Don't just commit fixes silently. Reply to comments; explain decisions; mark as addressed.

### Don't merge on red CI

CI failed for a reason. Fix it. Don't override.

## Specific git operations

### `git rebase -i`

Interactive rebase. Reorder, squash, edit commits in your branch before merging.

### `git cherry-pick`

Apply a specific commit from one branch to another. Useful for hotfixes.

### `git revert`

Create a new commit that undoes a previous one. Safe for shared branches (doesn't rewrite history).

### `git reset`

Move the branch pointer. Powerful and dangerous. Don't use on shared branches.

### `git stash`

Temporarily save uncommitted changes. Pop them later.

### `git bisect`

Find the commit that introduced a bug. Binary search through commit history. Requires that commits compile and tests run.

## What to NOT do

### Force-push to shared branches

`git push --force` to main rewrites history that others have. Their checkouts break.

`--force-with-lease` is safer (fails if remote has changed). Still risky on shared branches.

For your own feature branch before merging: force push freely. After merge, never.

### Commit secrets

Passwords, API keys, certificates. Once committed, they're in history forever (even after deletion).

Use:
- `.gitignore` for files containing secrets
- `git-secrets` or pre-commit hooks
- GitHub secret scanning
- Rotate any secret that gets committed

### Long-lived branches

After a few weeks, branches diverge enough that merging is painful. Keep branches short.

### Merge without testing

CI exists for a reason. Manual "looks good" doesn't catch what tests catch.

### Mass-rebase old branches

Trying to clean up an old branch by rebasing dozens of commits. Usually produces conflict resolution that's worse than the original.

## Git in monorepos

For large monorepos, specific tools help:

- **Sparse checkout**: only check out the parts you work on
- **Partial clone**: clone without full history
- **Shallow clone**: only recent history

Default git becomes slow at scale; these features help.

## Common failure patterns

- **Long-lived branches.** Painful merges.
- **Inconsistent merge strategy.** Messy history.
- **Force pushes to shared branches.** Broken checkouts.
- **Committed secrets.** Security incidents.
- **Massive PRs.** Rubber-stamp review.
- **No commit message discipline.** History becomes useless.
- **Working in main directly.** No code review.

## Further Reading

- [TrunkBasedDevelopment](TrunkBasedDevelopment) — Branching strategy
- [CiCdPipelines](CiCdPipelines) — CI integration
- [MonorepoVsPolyrepo](MonorepoVsPolyrepo) — Where git workflows differ
- [DevOpsAndSre Hub](DevOpsAndSre+Hub) — Cluster index

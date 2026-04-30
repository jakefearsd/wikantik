---
canonical_id: 01KQ0P44SRFH3BQMFDWAYWKY3J
title: Monorepo vs. Polyrepo
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: When monorepos pay off, when polyrepos do, and the tooling that makes monorepos
  practical at scale.
tags:
- monorepo
- polyrepo
- repository-strategy
- devops
- bazel
related:
- GitWorkflows
- CiCdPipelines
- JavaBuildToolComparison
hubs:
- DevOpsAndSreHub
---
# Monorepo vs. Polyrepo

Monorepo: all your code in one repository. Polyrepo: code split across many repositories.

Both work. Both have failure modes. The choice has more impact than expected.

## The trade-offs

### Monorepo

**Pros**:
- Atomic cross-project changes
- Shared tooling (one CI config, one build, one set of dependencies)
- Easy code sharing
- Consistent practices
- One place to look

**Cons**:
- Scaling git operations (clone, status, log)
- Build tools that handle the size
- Permission model (everyone sees everything)
- CI complexity (run only affected tests)

### Polyrepo

**Pros**:
- Repository-scoped permissions
- Independent deployment cadences
- Smaller checkouts
- Tool ecosystem is simpler

**Cons**:
- Cross-repo changes require coordination
- Code duplication or shared libraries
- Inconsistent practices across repos
- Versioning shared dependencies is painful

## When monorepo wins

- Large organization with extensive code sharing
- Tight coupling between services
- Infrastructure/platform teams that need atomic changes
- Strong tooling investment available
- Teams prefer consistency over autonomy

Famous examples: Google, Meta, Microsoft Office, Twitter (now X) — all run massive monorepos.

## When polyrepo wins

- Independent teams owning independent services
- Open-source projects with separate licensing
- External contractors with limited scope
- Smaller orgs without monorepo tooling investment
- Teams want autonomy

Most open-source projects are polyrepo (each library has its own repo). Most startups start polyrepo.

## The tooling problem

Monorepos at scale need specific tools:

### Build systems

Make/Maven/Gradle work but slow on large monorepos. Tools designed for scale:

- **Bazel** (Google): hermetic, content-addressed, incremental
- **Buck** (Meta): similar to Bazel
- **Pants**: Python-focused; similar concepts
- **Nx** (TypeScript): JavaScript/TS monorepos
- **Turborepo**: simpler; for Node-heavy projects

These tools rebuild only what changed; cache across users; scale to thousands of modules.

### Git scaling

For very large repos:
- Shallow clones
- Partial clones
- Sparse checkouts
- Git LFS for large files

Microsoft's Scalar and Git's own improvements have made large monorepos more practical.

### CI

Running all tests on every change doesn't scale. Need:
- Affected-detection: which tests depend on changed files
- Caching: don't rebuild what hasn't changed
- Distributed: parallel across many machines

Bazel and similar tools provide this; CI systems integrate.

### Code ownership

`CODEOWNERS` files (GitHub native) or similar. Different teams own different paths; PRs route to right reviewers.

## Hybrid approaches

### Small set of large repos

Not one monorepo, not many small ones. Maybe 5-10 repos for major systems. Each is a "small monorepo."

### Multi-repo with shared libraries

Polyrepo with conventions: shared lib repos, service repos, deployment repos. Independent but coordinated.

### Repo per language

Each language gets its own repo. Polyglot orgs sometimes do this.

### "Modern monorepo"

JS-heavy projects use Nx or Turborepo as a "monorepo lite" — works at most company scales without going to Bazel.

## The cultural side

Monorepo vs. polyrepo isn't just technical. It shapes how teams work:

### Monorepo culture

- "We see each other's code"
- Cross-team contributions easier
- Shared standards more enforceable
- Stronger central platform

### Polyrepo culture

- "My repo, my rules"
- Team autonomy
- Diverse practices
- Service-oriented thinking

Neither is universally better. Match the structure to how you want teams to operate.

## Migration

Switching is hard:

### Polyrepo → Monorepo

Combine repos one at a time. Use git subtree or git filter-repo to preserve history. Re-tool CI.

### Monorepo → Polyrepo

Extract services. Each becomes its own repo with relevant history.

Both migrations take months. Don't switch lightly.

## Common failure patterns

- **Monorepo without tooling.** Slow builds; long CI; team frustration.
- **Polyrepo without coordination.** Drift; duplicated effort; cross-repo changes painful.
- **Choosing based on others' choices.** Google has monorepo; doesn't mean you should.
- **Switching strategies repeatedly.** Each migration is expensive.
- **Heavy build system for small org.** Bazel for 10 engineers is overkill.

## A reasonable starter position

For new orgs:

- Small (<20 engineers): polyrepo, simple
- Medium (20-200 engineers): hybrid — small set of larger repos
- Large (200+ engineers, lots of sharing): monorepo with proper tooling

Re-evaluate as you grow. The right answer at 10 engineers may not be right at 100.

## Further Reading

- [GitWorkflows](GitWorkflows) — Workflow within either
- [CiCdPipelines](CiCdPipelines) — CI strategies differ
- [JavaBuildToolComparison](JavaBuildToolComparison) — Tools at different scales
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

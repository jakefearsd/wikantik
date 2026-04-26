---
title: Dark Launch Patterns
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- dark-launch
- feature-flags
- canary
- shadow-traffic
- progressive-delivery
summary: Dark launching — running new code with real traffic but no user impact —
  via shadow traffic, hidden features, and feature flags. The patterns and
  tooling that make safe deploys routine.
related:
- BlueGreenDeployments
- CanaryDeployments
- ChaosEngineering
- AgentTesting
hubs:
- SoftwareArchitecture Hub
---
# Dark Launch Patterns

Dark launching is "shipping the code without exposing the feature." The new code runs in production; real traffic exercises it; users don't see the result. Lets you test changes under realistic conditions before committing to the user-facing rollout.

It's distinct from canary (small percentage of users get the feature) and blue-green (cutover between environments). Dark launches are fundamentally about decoupling deployment from release.

## The patterns

### Shadow traffic

The new code runs alongside the old; both receive the same input; only the old's output is returned to the user. The new's output is discarded but logged for analysis.

```
       request
         ↓
    ┌─────────┐
    │ old API │ ─→ response → user
    └────┬────┘
         │ (mirror request)
         ↓
    ┌─────────┐
    │ new API │ ─→ logged, compared, dropped
    └─────────┘
```

Tools / patterns:

- **Service mesh mirroring** — Istio's `mirror` percentage; Envoy's mirror config.
- **Application-level forking** — call both; return one; log the other.
- **Diff tools** — Twitter's Diffy (open source); compares new vs old responses; surfaces differences.

Use cases:

- Validating refactored services produce identical output.
- Performance-testing new code with real traffic before swap.
- Verifying new ML model outputs against current.

Cost: 2× compute for the mirrored portion; plumbing overhead. Worth it for high-stakes migrations.

### Hidden feature flag

The new feature is fully built and deployed; gated behind a flag. Initially off for everyone. Toggled on for select users, then expanded.

```python
if feature_enabled("new_checkout_flow", user):
    return new_checkout(...)
else:
    return old_checkout(...)
```

Distinct from a "dark launch in shadow" — here, real users hit the new code path; just selectively.

This is the dominant pattern for safe rollout in 2026. Tools: LaunchDarkly, Statsig, Flagsmith, GrowthBook, ConfigCat, Unleash, or homegrown.

### Compute without surface

For changes to internal systems (a new caching layer, a rewritten queue), deploy the change but don't yet route traffic to it. Validate it can handle production load without depending on it.

```
Deploy the new cache layer. Old cache still serves all reads.
Backfill: warm the new cache from production traffic (background).
Validate: spot-checks that new cache returns same answers.
Switch: route reads to new cache. Old cache becomes hot standby.
Retire: remove the old cache.
```

This is "infrastructure dark launch" — safer than big-bang switches.

### Off-by-default feature with internal access

Feature is shipped but only employees / internal users see it. They use it in production with real data. After they validate, gradually expose to external users.

Pattern: feature flag whose evaluation depends on `user.email.endswith("@yourcompany.com")` or `user.has_role("staff")`.

Cheap; effective; catches real-world issues that staging never reproduces.

## Why dark launching matters

The benefit isn't engineering; it's *risk management*. Specifically:

- **Reduces blast radius.** When something goes wrong, the impact is bounded.
- **Decouples deployment from release.** You can deploy daily, release weekly, with confidence.
- **Enables A/B testing.** Same machinery that gates a feature can split traffic for testing.
- **Allows "instant rollback".** Toggle the flag off; problem gone.
- **Surfaces real-world bugs.** Staging never quite matches production.

Companies that dark-launch routinely deploy more often, with less drama, with shorter time to detect issues.

## Feature flag discipline

Feature flags are the primary dark-launch tool. They have their own pitfalls.

### Naming and ownership

Every flag has a clear name and an owner. `new_checkout_v2_2026q2_owner_jane` beats `flag_24`.

A flag without an owner becomes permanent. Worse, a flag *with* an owner who left the company is also permanent until someone audits.

### Lifecycle

Flags fall into two categories:

- **Release flags** (short-lived). Used to ship new code safely. Removed once the feature is fully rolled out.
- **Operational flags** (long-lived). Kill switches, gradual capacity adjustments. Stay forever.

Release flags accumulate as cruft if not removed. Quarterly audits; remove flags whose features have shipped.

### Evaluation overhead

Every flag check is a function call. For high-traffic paths, the latency adds up.

Solutions:
- **Cache flag evaluations** within a request scope.
- **Local flag evaluation** (LaunchDarkly's client-side SDK runs entirely in your service).
- **Bulk evaluations** when you need many flags at once.

For 99% of use cases this isn't a concern; for sub-millisecond critical paths, profile.

### Dependency between flags

Flag A depends on flag B being on. Combinatorial complexity grows. Mitigate by:

- Testing all enabled-flag combinations relevant to your service.
- Treating flags as cohorts, not orthogonal axes.
- Removing flags promptly once features stabilise.

### Flag-induced staleness

A flag toggled "on" is the new code path; "off" is the old. Both must work indefinitely. Without discipline, the off-path bit-rots; turning the flag off means hitting code that hasn't worked for months.

Mitigation: include flag-off paths in regular automated testing. Treat the flag-off path as production code, even if it's stale.

## A/B testing on top

The same machinery enables A/B tests. Flag varies treatment; you measure outcomes.

```
Variant A (control): old algorithm
Variant B: new algorithm v1
Variant C: new algorithm v2

Track: conversion, latency, error rate, user satisfaction.
```

Statistical significance, sample-size calculations, etc. — see proper A/B testing literature. The infrastructure is what enables it.

## Real-time kill switches

For operational safety, every risky feature has an off switch:

```python
if circuit_breaker.is_tripped("payments_v2"):
    return fallback_payments(...)
else:
    return new_payments(...)
```

When the new payments system starts erroring, an operator (or automation) trips the breaker. Traffic falls back to the old. No deploy needed.

This is feature flagging used for operational resilience. Critical for high-stakes systems.

## Testing-in-production tradeoffs

Dark launching is part of "testing in production" — using real production traffic to validate code. Some sub-techniques:

- **Synthetic monitoring** — simulated traffic constantly exercising your endpoints.
- **Staged rollout** — 0% → 1% → 10% → 100% over days.
- **Region-by-region rollout** — release in less-critical regions first.
- **Tenant-by-tenant rollout** — for multi-tenant SaaS, one customer at a time.

These all interact with dark-launch infrastructure. Build the foundation once; reuse for many patterns.

## When dark launching is overkill

- **Tiny applications** with few users; cost of infrastructure exceeds risk of bad deploys.
- **Internal-only tools** where downtime is acceptable.
- **Fully reversible operations** (e.g., a stateless API with simple rollback) — flags add complexity without commensurate benefit.

For mature production systems with non-trivial users, dark launching is operational hygiene. For everything else, judge case by case.

## Tools

- **LaunchDarkly** — most polished commercial.
- **Statsig** — strong on experimentation alongside flags.
- **Flagsmith, GrowthBook, Unleash** — open source / lower cost.
- **ConfigCat, Optimizely** — established alternatives.
- **Homegrown on Postgres / Redis** — for simple flag systems; not too hard to build.

For most teams in 2026: pick a hosted feature-flag service unless you have specific reason to self-host. The category is mature; competitive prices; integrations exist.

## A pragmatic baseline

For a team adopting dark launching:

1. **Adopt a feature flag tool.** Hosted is fine.
2. **Wrap risky changes** in flags from day one.
3. **Roll out** internal → 1% → 10% → 50% → 100% with metrics gates.
4. **Build a flag audit habit** — quarterly cleanup.
5. **Use shadow traffic** for high-stakes refactors (service rewrites, ML model swaps).
6. **Define kill switches** for high-stakes features.

A few weeks of investment; weekly deployment confidence forever.

## Further reading

- [BlueGreenDeployments] — alternative deployment strategy
- [CanaryDeployments] — gradient rollout pattern
- [ChaosEngineering] — testing reliability deliberately
- [AgentTesting] — task-fixture testing for agents

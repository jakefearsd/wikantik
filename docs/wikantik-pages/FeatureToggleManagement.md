---
canonical_id: 01KQ0P44QB1V5TB35VNAG1ZBF2
title: Feature Toggle Management
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: How feature flags work in practice — flag types, lifecycle, retirement,
  and the patterns that prevent flags from becoming permanent technical debt.
tags:
- feature-flags
- feature-toggles
- launchdarkly
- continuous-deployment
- ab-testing
related:
- TrunkBasedDevelopment
- CiCdPipelines
- ReleaseEngineering
hubs:
- DevOpsAndSre Hub
---
# Feature Toggle Management

A feature toggle (flag) is a runtime switch that controls whether a feature is enabled. Lets you deploy code without releasing the feature.

Feature flags are essential for trunk-based development, gradual rollouts, A/B testing, and operational kill switches. Done well, they're a powerful tool. Done poorly, they accumulate as permanent technical debt.

## Flag types

Different uses; different lifecycles.

### Release flags

Wrap in-progress features. Enable for testing, gradual rollout. Remove after the feature is stable.

Lifetime: weeks to months.

### Experiment flags (A/B test)

Compare variants for product experiments. Statistical analysis decides winner.

Lifetime: weeks for the experiment.

### Permission flags

Premium features, customer-specific access. Persistent state per customer.

Lifetime: indefinite (driven by business).

### Operational flags / kill switches

Quickly disable a problematic feature. Circuit breakers.

Lifetime: indefinite (kept for emergencies).

These are different tools. Treating them all the same causes problems.

## Implementation

### Boolean flag

```javascript
if (featureFlag.isEnabled('new-checkout')) {
    showNewCheckout();
} else {
    showOldCheckout();
}
```

Simple, common.

### Multivariate flag

```javascript
const variant = featureFlag.variant('checkout-experiment');
if (variant === 'A') ...
else if (variant === 'B') ...
```

For experiments with multiple options.

### Targeting

Flags evaluated based on user, group, percentage:

```javascript
if (featureFlag.isEnabled('new-feature', { userId, groups: user.groups })) {...}
```

The flag service evaluates rules: this user, this group, this percentage.

## Tools

### Hosted services

- **LaunchDarkly**: market leader; full-featured
- **Split**: comparable
- **Statsig**: experimentation focus
- **PostHog**: open-source; product analytics + flags
- **Flagsmith**: open-source; self-hostable

### Self-built

For small teams, a database table + simple service can suffice. The "build vs. buy" depends on scale and feature needs.

### Open-source self-hosted

Unleash, GrowthBook, OpenFeature standardization.

## The lifecycle

Each flag should have:

1. **Creation**: with intent, owner, expected lifetime
2. **Active use**: while the feature is gated
3. **Cleanup**: remove the flag from code; the feature is permanent

The cleanup step is where most teams fail. Flags accumulate; old code paths persist; the codebase gets crufty.

## Retirement discipline

Practices that prevent flag rot:

### Owner per flag

Every flag has an owner. The owner is responsible for retirement.

### Expected lifetime

Set when created. "Remove by end of Q3 2026." Not a hard deadline but a reminder.

### Periodic flag audits

Quarterly: list all flags. Which are old? Owner explains why or removes.

### Cleanup PRs

When retiring a flag, the PR removes both the flag check and the deprecated code path. The "if/else" becomes one path.

### Tools that warn

Some flag services flag (heh) old flags as candidates for removal.

## What flag is for vs. not

### Flag is good for

- Releasing features behind a kill switch
- Gradual rollout (1% → 10% → 50% → 100%)
- A/B experiments
- Customer-specific features
- Emergency kill switches

### Flag is misused for

- Long-term configuration (use config, not flags)
- Permanent feature differentiation (use proper architecture)
- Avoiding decisions ("we'll figure it out later")
- Hiding bad code ("we'll just keep the old path forever")

## Common patterns

### Gradual rollout

Start with internal users, then 1%, then 10%, then 100%. Watch metrics at each step.

### Kill switch

For risky features, the kill switch is a flag that disables in <1 minute. Used in incidents.

### Sticky bucketing

For experiments, ensure a user always sees the same variant. Random per-call assignment ruins experiment quality.

### Override per environment

Different defaults in dev, staging, production.

### Targeting attribute caching

Flag evaluation can be expensive (network call). Cache results for short windows.

## Anti-patterns

### Flags that become "configuration"

A flag that says "feature is enabled for all customers" forever. That's not a flag; it's the feature being live. Remove the flag.

### Flag explosion

Hundreds of flags; no one knows what they all do. Audit periodically.

### Flag-driven branching that re-creates if/else hell

Code that's `if (flag1) ... if (flag2) ... if (flag3) ...`. Refactor to clean abstractions.

### Flag service as single point of failure

Production code depends on flag service being up. If it's down, what happens? Either fall back to a default or cache the last known values.

## Common failure patterns

- **Flags never cleaned up.** Permanent debt.
- **Flag service outage = production outage.** Cache fall-back.
- **Targeting rules accumulate.** "Everyone except customer X except their team Y except..."
- **No flag visibility.** Engineers don't know which flags are active.
- **Flag for wrong purpose.** Configuration as flag; never retired.

## Further Reading

- [TrunkBasedDevelopment](TrunkBasedDevelopment) — Flags enable trunk-based
- [CiCdPipelines](CiCdPipelines) — CI/CD with flags
- [ReleaseEngineering](ReleaseEngineering) — Adjacent practice
- [DevOpsAndSre Hub](DevOpsAndSre+Hub) — Cluster index

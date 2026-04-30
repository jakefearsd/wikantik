---
canonical_id: 01KQ0P44V9G8XCDZ50GTT4V4VB
title: Refactoring Strategies
type: article
cluster: software-engineering-practices
status: active
date: '2026-04-26'
summary: The techniques for changing code structure without changing behavior — strangler
  pattern, expand-and-contract, parallel implementations — and the trade-offs that
  determine when each is right.
tags:
- refactoring
- strangler-pattern
- code-evolution
- software-engineering
- migration
related:
- CleanCodePrinciples
- LegacyCodeModernization
- TechnicalDebtManagement
- DebuggingStrategies
- JavaCollectionsFramework
hubs:
- SoftwareEngineeringPracticesHub
---
# Refactoring Strategies

Refactoring is changing code structure without changing behavior. The classic Fowler definition. In practice, real-world refactoring lives between two failure modes: the "big bang" rewrite that is supposed to take six weeks and takes six months, and the "leave it alone" approach that lets entropy compound.

The strategies below are the structured alternatives — how to evolve code incrementally, with each step verifiable, while still ending up at the destination.

## The strategies that work at scale

### Strangler fig pattern

Named after the strangler fig tree that grows around a host tree until eventually the host is gone. In code: build the new system *next to* the old one, gradually route traffic from old to new, eventually retire the old.

Steps:
1. Add a routing layer that can direct calls to old or new
2. Build the new implementation, pass the old behavior tests
3. Route a small percentage of traffic to the new implementation; verify identical behavior
4. Increase the percentage as confidence grows
5. When the new implementation handles 100%, remove the routing layer and the old implementation

Strangler is the safest pattern for high-stakes systems. It is also slow — running both systems in parallel costs effort. The trade-off: maximum safety, maximum cost.

### Expand-and-contract (parallel change)

For changes to interfaces or data structures that have many callers:

1. **Expand**: add the new interface or new shape, keeping the old. Both work simultaneously.
2. **Migrate**: update callers to use the new interface, one at a time. Each migration is a small, reversible change.
3. **Contract**: once all callers use the new interface, remove the old.

Each step is independently safe. The change can take days or months without breaking anything in between.

### Branch by abstraction

When the change is structural (replacing one component with another):

1. Introduce an abstraction layer between callers and the component
2. Implement the new component behind the same abstraction
3. Switch callers to the new implementation via configuration
4. Remove the old implementation

Similar to strangler but at smaller scale, internal to a service rather than across services.

### The "make the change easy, then make the easy change" pattern

When a change is hard, the first refactor is the one that makes it easy. Often this means:

- Extract a function that does the related work, so the change has one place to live
- Add a parameter or configuration point at the right level
- Decouple the affected code from things that are not actually related

Then make the actual change. The two-step approach often produces a cleaner result than trying to make the structural change and the behavior change at once.

## The trade-offs that come up

### Big bang vs. incremental

Big bang refactors fail more often than they succeed. The reasons:
- Time to delivery extends past initial estimates (always)
- Business needs change during the rewrite
- The new system's correctness on edge cases takes longer than the green-field development
- Deploying the rewrite is risky (no graceful path back)

Incremental refactoring solves these but takes longer and requires sustained discipline. Most successful "rewrites" are actually long sequences of incremental refactors.

### Behavior preservation vs. behavior fixing

Pure refactoring preserves behavior — including bugs. Refactoring that fixes bugs at the same time blurs the line between refactor and feature change.

The discipline: do them separately. Refactor, verify behavior is identical, then fix the bug as a separate change. Mixing them makes either step harder to review.

### Tests as scaffolding

Refactoring is dramatically safer with good test coverage of the affected behavior. Without tests, refactoring is guess-and-pray.

The chicken-and-egg: legacy code without tests cannot be refactored safely, but cannot be tested without first refactoring. The escape: characterization tests (snapshot the current behavior, treat that as a temporary spec) that lock in observable behavior, then refactor under that net.

## Specific refactor types

### Extract function

The most common, lowest-risk refactor. Take a chunk of code that does one thing and pull it into a named function. Often makes the calling code more readable.

Tools support this directly in modern IDEs (with name verification, callsite updates). Use the tool, not manual extraction.

### Rename

Renaming a function, variable, or type. Trivial-looking but high-leverage if done at scale. A bad name appearing 200 times is 200 readers stumbling.

### Move method

A method belongs on a different class than where it lives. Move it. The original class often has weaker dependencies after the move.

### Inline function

The opposite of extract. A function that adds nothing — just calls another function with no transformation — is noise. Inline it.

### Replace primitive with object

A "currency amount" represented as a number is fragile. Currency conversions get done by hand; mistakes hide. Replacing the number with a Money object that carries currency and amount eliminates the class of bug.

### Replace conditional with polymorphism

A switch statement that varies behavior by type can sometimes be cleaner as polymorphism. Sometimes — the switch is often clearer for small cases. Use polymorphism when the cases are stable and the behavior varies substantially.

## Common failure patterns

- **Refactoring without tests.** Risky; produces undetected behavior changes.
- **Refactoring that doesn't end.** "Cleanup" that turns into rewriting half the codebase. Set scope; finish or revert.
- **Big bang refactors.** Almost always fail at scale.
- **Refactoring during feature work.** Mixing refactor with feature change makes both harder to review.
- **Premature abstraction in the name of "future flexibility."** Abstractions designed for hypothetical needs usually don't fit when the real need arrives.
- **Avoiding refactoring because "it's not the priority."** Debt compounds. Small, regular refactoring is cheaper than periodic big rewrites.

## Further Reading

- [CleanCodePrinciples](CleanCodePrinciples) — What clean looks like at the destination
- [LegacyCodeModernization](LegacyCodeModernization) — Refactoring code you did not write
- [TechnicalDebtManagement](TechnicalDebtManagement) — When refactoring is debt repayment
- [DebuggingStrategies](DebuggingStrategies) — Refactoring for debuggability
- [JavaCollectionsFramework](JavaCollectionsFramework) — Specific Java refactoring opportunities
- [SoftwareEngineeringPractices Hub](SoftwareEngineeringPracticesHub) — Cluster index

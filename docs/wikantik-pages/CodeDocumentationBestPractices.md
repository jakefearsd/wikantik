---
canonical_id: 01KQ0P44NJCTP347WN3VRC82AC
title: Code Documentation Best Practices
type: article
cluster: software-engineering-practices
status: active
date: '2026-04-26'
summary: When comments earn their place vs. when they decay into noise — the principle
  of documenting why, not what, and the documentation that compounds value vs. the
  documentation that turns into lies.
tags:
- documentation
- comments
- code-quality
- software-engineering
- maintenance
related:
- CleanCodePrinciples
- TechnicalWritingGuide
- RefactoringStrategies
- LegacyCodeModernization
hubs:
- SoftwareEngineeringPracticesHub
---
# Code Documentation Best Practices

Code documentation has two failure modes: too little (the next reader has no chance) and too much (comments decay into lies as code changes around them). The balanced approach is principle-driven: document the things that the code itself cannot say.

This page is about which comments earn their place, which decay into noise, and the practices that produce documentation that survives.

## The core principle

Code says *what* is happening. Comments should say *why*.

Good comments answer: why this approach? Why this constraint? What would surprise a reader? What is the non-obvious invariant?

Bad comments answer: what does this code do? (The code already shows that.)

A worked example:

```
// Loop through orders and find total amount.
for order in orders:
    total += order.amount
```

The comment restates the code. It is noise.

```
// Use atomic increment because this can be called from multiple threads;
// see incident #1234 for what happens with non-atomic.
counter.atomic_add(1)
```

The comment explains the why — a constraint not visible in the code. It earns its place.

## Comments that earn their place

### Constraints and invariants

"This list must never be modified after construction" is a real constraint. The code may not visibly enforce it; the comment makes it visible to future readers.

### References to external systems

"This format must match the schema in /docs/api-spec.json" links to information not in the codebase.

### Workarounds for known issues

"Workaround for bug in library X version Y; remove when we upgrade past Z." This is the kind of comment that prevents another engineer from "improving" the workaround away without realizing what they're undoing.

### Performance notes

"Using map instead of dict for ordering guarantees" — explains a non-default choice.

### Surprising decisions

"Sorting before filtering — counterintuitive but X% faster on the typical input." Without the comment, a future reader will refactor "for clarity" and lose the perf.

### Deprecation paths

"This API is being replaced; new code should use Y." Helps the next reader avoid extending the deprecated path.

## Comments that decay

### Restated code

"Increment counter by 1" before `counter += 1`. The code shows it; the comment is duplication.

### Sectioning ASCII art

Banners and section headers in comments. Often a sign the function is too long; sub-functions would be a better answer than visual division.

### "Changed by Bob on 2019-03-15"

Version control already tracks this. Inline change history is noise that decays as the codebase evolves.

### Wishful comments

"Will eventually do X." If it does not yet do X, do not pretend it does. Either implement X, or remove the comment, or note honestly that X is not implemented.

### Marketing language

"This elegant solution efficiently handles the use case." Subjective claims are not documentation.

## Function-level documentation

The format depends on the language and tooling.

### Java / JavaDoc

JavaDoc is appropriate for public APIs. Document the contract — what the function does, the parameter meaning, the return semantics, the exception types. Skip the obvious; document the non-obvious.

```
/**
 * Validates an order and returns true if it can be processed.
 *
 * <p>The validation includes credit check (calls credit service), inventory
 * availability (calls inventory service), and shipping address verification.
 * Returns false on any failure; the specific cause is logged.
 *
 * @param order The order to validate. Must not be null.
 * @return true if the order can be processed, false otherwise.
 * @throws CreditServiceException If the credit service is unreachable.
 */
```

### Python / docstrings

Similar role to JavaDoc. PEP 257 covers conventions. The principle: document the contract, not the implementation.

### Inline comments

Most useful for the why-not-what cases described above. Should be short, specific, and tied to a specific line.

### Module-level documentation

The top of a file or module is the right place for context that applies to everything in the file: what this module does, what it depends on, what callers of this module need to know.

## Documentation outside code

Some things belong in code; some belong outside.

### In code

- Function contracts (parameters, returns, exceptions)
- Inline why-not-what for specific lines
- Module-level overview

### Outside code (e.g., README, docs/)

- Architecture overview
- How to set up the project locally
- Deployment process
- The reasoning behind major decisions (sometimes called Architecture Decision Records, ADRs)

The choice depends on audience. A developer setting up the project for the first time benefits from a README. A developer modifying a specific function benefits from comments at that function.

## The half-life problem

Documentation decays. Code changes; comments stay. Eventually the comment is wrong, often more harmful than no comment at all (the reader trusts the comment, which describes obsolete behavior).

Strategies that combat decay:

1. **Tie documentation to code shape.** Function-level documentation that lives next to the function is harder to forget than separate documentation files.
2. **Keep documentation minimal.** Less documentation = less to maintain = less to decay.
3. **Treat documentation changes as part of code changes.** A change to a function should update its documentation in the same commit.
4. **Periodic review.** Periodic reading of documentation to find drift is one of the few ways to catch it before it lies.

## Specific documentation patterns

### Architecture Decision Records (ADRs)

Short documents that capture a single decision: context, decision, consequences. Stored in the repository. Useful for "why did we do it this way?" questions years later. See [TechnicalLeadershipSkills](TechnicalLeadershipSkills) for the broader practice.

### Examples in API documentation

Worked input/output examples are dramatically more useful than parameter descriptions for most APIs. A user can match an example to their case faster than they can synthesize from a contract description.

### Runbooks

Operational documentation: "what to do when X happens." Live alongside code that produces specific errors or alerts. See [RunbookAutomation](RunbookAutomation).

### Postmortems

Documentation of incidents: what happened, why, how it was resolved, what would prevent recurrence. Specifically not blaming individuals; specifically identifying systemic causes.

## Common failure patterns

- **Documenting code that documents itself.** Restating clear code in English is noise.
- **Maintaining stale documentation.** The lie is worse than no documentation.
- **Heavy commenting in lieu of clear naming.** Better names usually beat more comments.
- **Documentation that is too generic to be useful.** "This function processes data" is useless.
- **One-time-only documentation.** Documentation written for the original engineer that becomes useless to anyone else.
- **No documentation of the why.** Code without "why" comments forces future engineers to re-derive every decision.

## Further Reading

- [CleanCodePrinciples](CleanCodePrinciples) — Code that needs less documentation
- [TechnicalWritingGuide](TechnicalWritingGuide) — Document writing that survives
- [RefactoringStrategies](RefactoringStrategies) — When refactoring updates documentation
- [LegacyCodeModernization](LegacyCodeModernization) — Adding documentation to undocumented code
- [SoftwareEngineeringPractices Hub](SoftwareEngineeringPracticesHub) — Cluster index

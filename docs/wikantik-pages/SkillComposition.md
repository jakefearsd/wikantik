---
canonical_id: 01KQ0P44WGM57JQEM98T9PQ9KT
title: Skill Composition
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How Claude composes multiple skills for complex tasks — invocation patterns,
  passing state, and the design choices that make skills work well together.
tags:
- skills
- composition
- agentic-ai
- claude
related:
- CustomSkillsArchitecture
- SkillIntegration
- SkillLibraries
hubs:
- AgenticAiHub
---
# Skill Composition

A single skill does one thing. Real tasks often require many skills. Composition is how Claude combines skills to handle complex requests.

This page covers how composition works and the design patterns that make it reliable.

## How invocation works in sequence

Claude can invoke multiple skills during a conversation:

```
User: "Implement feature X"
↓
Claude invokes: brainstorming skill
↓
[design discussion]
↓
Claude invokes: writing-plans skill
↓
[plan written]
↓
Claude invokes: test-driven-development skill
↓
[implementation]
↓
Claude invokes: requesting-code-review skill
↓
[review]
```

Each skill handles its phase. Together they produce the outcome.

## State across skills

Each skill is loaded into context when invoked. Earlier skill outputs inform later skills implicitly — they're in the conversation.

Patterns:
- Earlier skill produces a plan; later skill executes it
- Earlier skill identifies a problem; later skill fixes it
- Earlier skill writes design; later skill writes code

The "state" is the conversation history. No special passing mechanism needed.

## Composition patterns

### Linear chain

Skill A → Skill B → Skill C. Each completes before the next.

The most common pattern. Each step depends on the previous.

### Branching

Skill A determines the path:

```
Skill A: detect language
├── If Python: Skill B (Python style)
├── If Java: Skill C (Java style)
└── If TypeScript: Skill D (TS style)
```

The first skill's output drives which skill comes next.

### Parallel (rare)

Skills don't usually run in parallel within Claude's flow. Workflows that branch then converge are usually serialized: do A, do B (which uses A's result), do C.

Spawning subagents is one way to parallelize. See multi-agent patterns.

### Recursive

A skill may invoke itself for sub-problems. "Decompose into sub-tasks; solve each; combine."

Common in planning skills.

## Design for composition

### Single responsibility per skill

If a skill does multiple things, it composes poorly. Other skills can't cleanly invoke a piece of it.

Smaller, focused skills compose better than monolithic ones.

### Clear inputs/outputs

What does the skill expect from prior context? What does it produce?

Even informally — "this skill assumes a plan has been written" — helps composition.

### Don't repeat work

Skill A wrote tests; Skill B shouldn't write the same tests. Each builds on prior; doesn't redo.

### Idempotent invocation

Re-invoking a skill should be safe. If composing involves backtracking ("redo this step"), idempotency matters.

## Specific patterns

### Process skills first

Some skills determine *how* to approach a task (brainstorming, planning, debugging). Implementation skills come after.

The system prompt guides this: brainstorming/planning before implementation.

### Verification skills after action

After significant work, verification:
- Verification before completion (run tests, check builds)
- Code review skills
- Spec review

Catches problems before declaring success.

### Skill awareness of other skills

Some skills explicitly reference others:

```markdown
After completing the implementation, invoke the requesting-code-review skill.
```

Builds workflows from individual skills.

### Decomposition skills

Some skills' job is breaking a problem into smaller pieces, then invoking other skills for each piece.

Common in plan execution.

## When composition is hard

### Conflicting instructions

Two skills may disagree. "Always TDD" vs. "no tests for this kind of code" — which wins?

Resolution: explicit ordering or clearer scope per skill.

### Skill discovery

Claude knows about installed skills. If a skill that would help isn't installed, composition fails.

User-facing: skills should be discoverable and well-described.

### Implicit dependencies

Skill B assumes Skill A ran first. If invoked alone, B may produce surprises.

Mitigation: B's instructions check for needed state.

### Long workflows

Many skills in sequence; long contexts. Skills that work alone may not work in long compositions.

Mitigation: skills should be context-light when possible.

## A worked example

Building a feature with the superpowers system:

```
User: "Add a search feature to the wiki"

Step 1: brainstorming skill
  - Clarify scope, identify constraints
  - User confirms direction

Step 2: writing-plans skill
  - Detailed implementation plan
  - User reviews

Step 3: writing-skills skill (if creating new abstractions)
  - Optional; creates reusable skill if pattern emerges

Step 4: test-driven-development skill
  - Write failing tests
  - Implement to pass

Step 5: requesting-code-review skill
  - Review against requirements
  - Address feedback

Step 6: finishing-a-development-branch skill
  - Decide merge strategy
  - Complete the work
```

Six skills compose for one feature. Each handles its piece.

## Common failure patterns

- **Skills that overlap.** Claude can't decide which to invoke.
- **Skills with hidden dependencies.** Workflow breaks when skill invoked out of order.
- **Long sequences without context cleanup.** Compositional weight grows.
- **Skills that don't compose.** Built for solo use; awkward in workflows.
- **Trying to do everything in one skill.** Skill bloat.

## Further Reading

- [CustomSkillsArchitecture](CustomSkillsArchitecture) — Skill basics
- [SkillIntegration](SkillIntegration) — Skills in workflows
- [SkillLibraries](SkillLibraries) — Distributing skill collections
- [AgenticAi Hub](AgenticAiHub) — Cluster index

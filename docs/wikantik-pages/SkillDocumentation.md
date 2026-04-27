---
canonical_id: 01KQ0P44WHDY2QRXCW5CDE3364
title: Skill Documentation
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How to document skills so other users (and your future self) can understand
  and use them — the SKILL.md description, examples, and the patterns that age well.
tags:
- skills
- documentation
- claude
- agentic-ai
related:
- CustomSkillsArchitecture
- SkillComposition
- SkillDebugging
- TechnicalWritingGuide
hubs:
- AgenticAi Hub
---
# Skill Documentation

A skill's documentation is its description, instructions, and examples. Written for Claude (who reads it to decide invocation and behavior) and for humans (who maintain it and decide whether to use it).

This page covers what good skill documentation looks like.

## What needs documenting

### Description

The frontmatter description is the most-read piece. Brief; specific; matches the user's mental model of when this skill is needed.

```yaml
description: Reviews code for adherence to project guidelines, style guides, and best practices. Use proactively after writing or modifying code.
```

Specific (code review); contextualized (after writing/modifying); proactive trigger (when to use without being asked).

### Instructions

The body of SKILL.md. Tells Claude what to do.

Structure:
- Purpose / when to use
- Workflow / steps
- Examples
- Common pitfalls

Length: long enough to be specific; short enough to be applicable.

### Examples

Real input/output examples. Usually the most-useful piece.

```markdown
Example: User asks "review this PR"

Steps:
1. git diff main..HEAD
2. Identify changes
3. Apply review checklist
4. Report findings

Output:
- Style: 2 issues
- Logic: 1 issue
- ...
```

Concrete examples disambiguate better than abstract description.

### When NOT to use

Some skills should NOT be invoked in certain cases. Document these:

```markdown
SKIP when:
- The user just wants to commit (no review needed)
- The change is trivial (one-line typo fix)
- The file is auto-generated
```

## Documentation for humans

In addition to Claude-facing documentation, skills often need human docs:

### Installation

How to install/enable the skill. Path; configuration; dependencies.

### What it does

Brief explanation for users browsing skills.

### Examples of use

When would I use this? Sample interactions.

### Limitations

What doesn't this skill handle? Where's it not the right tool?

### Maintenance

Who maintains it; how to report issues; how to contribute.

## Patterns that age well

### Short primary description

The frontmatter description is the most-read piece. One or two sentences.

### Hierarchical detail

SKILL.md has the essentials. References folder has depth. Claude reads SKILL.md always; references when relevant.

```
my-skill/
├── SKILL.md           (overview, key points)
└── references/
    ├── api-spec.md    (read when API details needed)
    └── examples.md    (read for examples)
```

This keeps SKILL.md scannable.

### Concrete language

"Use parameterized queries" beats "ensure security best practices."

### Structured sections

Headings, bullet points, code blocks. Skim-friendly.

### Living examples

Examples that match real usage. Update when usage changes.

### Versioning

For skills shared widely:

```yaml
---
name: my-skill
description: ...
version: 2.1.0
---
```

Helps users know if a behavior change is intentional.

## What ages poorly

### Marketing language

"Powerful, intelligent, comprehensive..." Useless.

### Vague advice

"Follow best practices." What practices?

### Self-referential

"This skill is awesome." Tells nothing about behavior.

### Outdated examples

Code samples for an API that's changed. Misleading.

### Too much theory

Page on what skills are; user wants to know what this specific skill does.

## Specific patterns

### Trigger documentation

Make triggers explicit:

```markdown
## When to use

USE when:
- User asks for [specific request]
- Code shows [specific pattern]
- Workflow includes [specific step]

DON'T USE when:
- [explicit exclusion]
- [another exclusion]
```

### Workflow steps

For multi-step skills:

```markdown
## Workflow

1. **Identify scope**: what's being changed?
2. **Plan**: write design doc
3. **Implement**: TDD
4. **Verify**: tests pass; build succeeds
5. **Review**: invoke code-review skill

Each step has its own subsection if needed.
```

### Common pitfalls

A "Common Failure Patterns" section. Helps Claude avoid known issues.

### Examples gallery

Multiple examples covering different scenarios:

```markdown
## Examples

### Simple case
[example]

### Complex case
[example]

### Edge case: when X
[example]
```

### Cross-references

Link to related skills:

```markdown
## See Also

- `brainstorming` — invoke before this for new features
- `writing-plans` — invoke before implementation
- `requesting-code-review` — invoke after this
```

## Scaling documentation

For organizations with many skills:

### Skill catalog

Central index of all skills. What each does; how to use.

### Conventions

Standardized format for SKILL.md across the organization. Consistency helps Claude apply them similarly.

### Reviews

Skill changes should be reviewed like code. Skills affect Claude's behavior; reviews catch issues.

### Versioning and changelog

Track changes. Especially important for skills used in production workflows.

## Common failure patterns

- **Description that doesn't match what users say.** Skill not invoked.
- **No examples.** Behavior unclear.
- **Too much abstract description.** Not actionable.
- **No human-facing documentation.** Users can't discover; can't troubleshoot.
- **Outdated content.** Misleading.
- **No versioning.** Changes affect users without notice.

## Further Reading

- [CustomSkillsArchitecture](CustomSkillsArchitecture) — Skill basics
- [SkillComposition](SkillComposition) — Composition needs documentation
- [SkillDebugging](SkillDebugging) — Diagnostic adjacent
- [TechnicalWritingGuide](TechnicalWritingGuide) — General writing principles
- [AgenticAi Hub](AgenticAi+Hub) — Cluster index

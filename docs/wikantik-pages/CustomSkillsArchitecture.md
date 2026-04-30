---
canonical_id: 01KQ0P44P5CT60RTSMCKA1C4GD
title: Custom Skills Architecture
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How Claude Skills work — markdown-driven skill files, the SKILL.md format,
  invocation semantics, and the architecture that makes skills composable across
  conversations.
tags:
- skills
- claude-skills
- agentic-ai
- skill-architecture
related:
- SkillComposition
- SkillDocumentation
- SkillIntegration
- ToolOutputOptimization
hubs:
- AgenticAiHub
---
# Custom Skills Architecture

Claude Skills are file-based extensions that teach Claude how to do specific tasks. Each skill is a self-contained directory with a SKILL.md file describing what it does and how to do it.

This page covers the architecture and the patterns for designing skills.

## The skill structure

A skill lives in a directory:

```
my-skill/
├── SKILL.md           (required: name, description, instructions)
├── scripts/           (optional: executable helpers)
├── templates/         (optional: file templates)
├── references/        (optional: detailed reference material)
└── examples/          (optional: worked examples)
```

The SKILL.md frontmatter:

```yaml
---
name: my-skill
description: Brief description for Claude to decide when to invoke
---
```

Description is critical — Claude reads it to decide whether to invoke the skill. Specific descriptions invoke when relevant; vague descriptions don't.

## How invocation works

When a user asks Claude to do something:

1. Claude reads the available skill list (names + descriptions)
2. If a skill matches the task, Claude invokes it via the `Skill` tool
3. The skill content is loaded into Claude's context
4. Claude follows the instructions in the skill

The skill author's job: write instructions that produce reliable behavior.

## Design principles

### Specific descriptions

Bad: "helps with development"
Good: "creates Java records with proper validation, builders, and equals/hashCode for domain types"

The description filters when Claude invokes. Specific = invoked at the right time.

### Trigger and skip conditions

```markdown
TRIGGER when: user asks for X, code uses pattern Y
SKIP: file is .test, language is Python
```

Explicit triggers help Claude pick the right skill.

### Concrete instructions

Bad: "follow best practices"
Good: "use parameterized queries; validate input at the boundary; emit structured logs"

Specific instructions produce specific behavior.

### Examples

Worked input/output examples are dramatically more useful than abstract descriptions. Real example > paragraph of description.

### Composable

A skill should do one thing. Multiple skills compose for complex tasks. See [SkillComposition](SkillComposition).

## Patterns

### Reference docs

For information Claude doesn't need every time, put it in `references/`. Skill instructions can say "see `references/api-spec.md` for details" — Claude reads when needed.

This keeps SKILL.md short while having depth available.

### Scripts

Some operations are better as code than instructions. Skills can include scripts:

```markdown
Run this script: `./scripts/format-output.sh`
```

Claude executes; scripts encapsulate logic that's awkward in natural language.

### Templates

For generating files: provide templates. Claude fills them in.

### Multi-file skills

Complex skills may have multiple instruction files. SKILL.md is the entry point; other files provide depth.

## Skill development workflow

### Identify the pattern

What does Claude do repeatedly that would benefit from systematization? Common needs: code review style, file conventions, deployment steps.

### Draft the skill

Write SKILL.md with clear description, instructions, examples.

### Test

Try the skill in conversations. Does Claude invoke it at the right times? Does it produce expected results?

### Iterate

Skills evolve. Real-world usage exposes gaps; refine.

### Document

When the skill is stable, document it for other users. See [SkillDocumentation](SkillDocumentation).

## Specific patterns

### Workflows that span multiple steps

Some skills define multi-step workflows:

```markdown
1. First, do A
2. Then, do B
3. Finally, do C
```

Claude follows step by step. Useful for procedures with required ordering.

### Decision trees

```markdown
If situation X: do A
If situation Y: do B
Otherwise: do C
```

Encodes branching logic.

### Pre-conditions

```markdown
Before starting:
- Verify the file exists
- Check the user's permissions
```

Ensures the skill operates on valid state.

### Post-conditions / verification

```markdown
After completing:
- Verify the build passes
- Confirm tests added
```

Verification before declaring success.

## Limits

### Skills are not code

The instructions are read; not executed. Subtle requirements (timing, side effects) are hard to express in markdown.

For complex logic, scripts (executed by the skill) are more reliable than long markdown instructions.

### Skills can conflict

Multiple skills may match a task. Claude picks one. Disambiguation via specific descriptions.

### Skills are local context

Each conversation loads them fresh. Skill changes affect future conversations, not running ones.

## Common failure patterns

- **Vague descriptions.** Skill not invoked when relevant.
- **Long unstructured instructions.** Hard for Claude to follow.
- **No examples.** Behavior less predictable.
- **Trying to do everything.** Skill that handles 5 different tasks does each poorly.
- **No iteration.** First version of a skill rarely the best version.

## Further Reading

- [SkillComposition](SkillComposition) — Combining skills
- [SkillDocumentation](SkillDocumentation) — Documenting skills
- [SkillIntegration](SkillIntegration) — Skills in workflows
- [ToolOutputOptimization](ToolOutputOptimization) — Adjacent agent concern
- [AgenticAi Hub](AgenticAiHub) — Cluster index

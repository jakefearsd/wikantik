---
canonical_id: 01KQ0P44WH61CKXHGPC05BW1AK
title: Skill Integration
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How skills integrate with workflows, tools, and user instructions — the
  layering of behavior and the precedence rules that determine what Claude does.
tags:
- skills
- integration
- workflows
- claude
- agentic-ai
related:
- CustomSkillsArchitecture
- SkillComposition
- SkillLibraries
- SkillPerformance
hubs:
- AgenticAi Hub
---
# Skill Integration

Skills don't operate in isolation. They integrate with: user-provided instructions (CLAUDE.md), tools (bash, file ops, MCP), other skills, and the conversation context.

This page covers how the layers fit together.

## Instruction precedence

When skill instructions conflict with user instructions, who wins?

Per the standard system prompt: **user instructions always take precedence**.

Order:
1. User's explicit instructions (CLAUDE.md, direct requests)
2. Skill instructions
3. Default system prompt

If a skill says "always TDD" and CLAUDE.md says "this project doesn't use TDD," CLAUDE.md wins.

This matters: skills should defer to user context when they conflict.

## Skills + tools

Skills tell Claude what to do; tools (Bash, Read, Edit, etc.) are how Claude does it.

A skill might say:
> Use git diff to see changes; use grep to find patterns; edit files via Edit tool.

The skill's instructions inform tool usage. The tools are the actuators.

For some skills, specific tools are essential:
- Code review skills use Read, Grep, Bash
- Code modification skills use Edit, Write
- Investigation skills use multiple tools

## Skills + MCP servers

MCP (Model Context Protocol) servers expose external functionality. Skills can recommend or require specific MCP tools.

A "Slack notification" skill might say "use the slack MCP server's send_message tool."

The MCP layer is the interface; the skill orchestrates.

## Skills + CLAUDE.md

CLAUDE.md is project-specific instructions. Often:
- Code style preferences
- Build/test commands
- Project conventions
- Things to avoid

Skills should respect CLAUDE.md. A skill can suggest TDD; CLAUDE.md saying "no TDD here" overrides.

Skills can reference CLAUDE.md:

```markdown
Check CLAUDE.md for project-specific conventions before applying default style.
```

## Skills + memory

Some Claude environments have memory systems (auto-memory, persistent storage). Skills interact:

- Skills may read memory for relevant context
- Skills may write to memory (preferences, learnings)
- Skill behavior may adapt based on memory

For environments without memory, skills are stateless across conversations.

## Workflow integration

Skills can chain in workflows:

```
1. brainstorming → 2. writing-plans → 3. test-driven-development → 4. requesting-code-review
```

Each is its own skill; together they form a workflow.

Some workflows are encoded in a "process" skill that coordinates others:

```markdown
The development workflow:
1. Invoke brainstorming
2. Invoke writing-plans
3. Invoke test-driven-development
4. Invoke requesting-code-review
```

A meta-skill orchestrates the others.

## Specific integrations

### Skills in CI

Some skills suggest running CI commands:

```bash
mvn clean test
```

The skill says when to run; the user runs (or the bash tool runs if authorized).

### Skills modifying files

Edit/Write tools modify files. Skills guide what changes; tools execute.

For shared codebases, skills should be careful — modifications affect users.

### Skills with subagents

Skills can suggest spawning subagents for parallel work. Sub-agents have their own context; results return to the main conversation.

```markdown
For independent investigations, spawn parallel subagents with the Agent tool.
```

### Skills with hooks

Some Claude environments support hooks (events on tool calls, conversation start, etc.). Skills can suggest hook configurations:

```markdown
Set up a hook to run linting after every Edit:
{ "hooks": [...] }
```

## Discoverability

For skills to be useful, Claude must know they exist.

In Claude Code: skills are listed in system reminders by name + description. Claude scans this list when deciding invocation.

For new skills: announcing in CLAUDE.md helps if discoverability is an issue.

## Conflicts between skills

Multiple skills may match a request. Claude picks one (usually based on description specificity).

For predictable behavior:
- Specific descriptions
- Explicit triggers
- Don't overlap

If conflicts persist, user can be explicit ("use the X skill").

## Cross-organization skills

Some skills are personal; some shared in teams; some published as open-source.

For shared skills:
- Documentation matters more
- Versioning matters
- Maintenance ownership

See [SkillLibraries](SkillLibraries).

## Integration testing

For complex skill integrations:

1. Test the skill alone (does it work?)
2. Test composition (does it work with the skills it expects to follow?)
3. Test conflicts (what happens when two skills could apply?)
4. Test in different projects (does CLAUDE.md affect it?)

## Common failure patterns

- **Skill that overrides user instructions.** Should defer; not override.
- **Skill that requires unavailable tools.** Won't work in all environments.
- **Skill assumptions about CLAUDE.md.** Brittle if missing.
- **Heavy MCP server requirements.** Limits portability.
- **Skill that bypasses user oversight.** "Just do it" without confirmation for risky actions.

## A reasonable approach

For new skills:

1. Respect the precedence (user > skill > default)
2. Use available tools deliberately
3. Compose with related skills
4. Document integration assumptions
5. Test in real environments

## Further Reading

- [CustomSkillsArchitecture](CustomSkillsArchitecture) — Skill basics
- [SkillComposition](SkillComposition) — Skill chains
- [SkillLibraries](SkillLibraries) — Sharing skills
- [SkillPerformance](SkillPerformance) — Adjacent concern
- [AgenticAi Hub](AgenticAi+Hub) — Cluster index

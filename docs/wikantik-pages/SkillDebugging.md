---
canonical_id: 01KQ0P44WGNMNYTWEPMG96H112
title: Skill Debugging
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How to diagnose skills that aren't working as expected — invocation issues,
  instruction ambiguity, and the iterative testing process that produces reliable
  skills.
tags:
- skills
- debugging
- agentic-ai
- claude
related:
- CustomSkillsArchitecture
- SkillComposition
- SkillDocumentation
hubs:
- AgenticAiHub
---
# Skill Debugging

A skill exists; Claude isn't using it correctly. The skill doesn't get invoked when it should; gets invoked when it shouldn't; or produces wrong output.

This page covers diagnosis and fixes.

## Common symptoms and causes

### Skill not invoked when expected

The user asks for something the skill should handle; Claude doesn't invoke it.

Causes:
- **Description too vague**: doesn't match the user's request
- **Missing keywords**: skill description doesn't mention what user said
- **Conflicting skills**: another skill matched first
- **Skill not enabled**: in user's config but not loaded

Fix: refine the description. Add explicit triggers ("when user asks for X").

### Skill invoked at wrong times

Claude invokes the skill for unrelated requests.

Causes:
- **Description too broad**: matches too many things
- **Generic keywords**: triggers on common words

Fix: narrow the description. Add explicit skip conditions ("not for Python files").

### Wrong output despite invocation

Claude invokes the skill; produces unexpected results.

Causes:
- **Ambiguous instructions**: multiple interpretations
- **Conflicting guidance**: skill says X but user instructions say Y
- **Missing context**: skill assumes information not present
- **Examples mismatched**: examples don't match the task

Fix: clarify instructions. Add explicit examples. Specify pre-conditions.

## The diagnostic process

### Reproduce

Get a clear test case. The user request that should invoke the skill; the actual behavior.

If reproduction is intermittent, the issue may be in description matching — sometimes Claude invokes; sometimes doesn't.

### Inspect the skill

Read SKILL.md. Pretend you're Claude:
- When would I invoke this?
- What would I do if invoked?
- What's ambiguous?

### Test in isolation

Invoke the skill explicitly; see what happens. If invocation produces wrong output, the issue is in instructions. If invocation never happens via natural language, the issue is in description.

### Iterate

Skills rarely work perfectly first time. Adjust; test; adjust.

## Specific debugging patterns

### Description matching

Description: "helps with code style"
User: "make this more readable"

Did the description match? If no: what would have matched? Add those terms.

### Trigger conditions

Add explicit triggers:

```markdown
TRIGGER when:
- User asks to refactor for readability
- User says "clean up this code"
- File contains code-smell patterns
```

### Skip conditions

Add explicit skips:

```markdown
SKIP when:
- File is configuration (yaml, json without code)
- User explicitly says "don't refactor"
```

### Examples that disambiguate

If users invoke the skill in unexpected ways, add examples:

```markdown
## Examples

User: "make this prettier" → invoke this skill
User: "format this JSON" → DON'T invoke; use formatter instead
```

### Cross-references

If the skill's behavior depends on other skills:

```markdown
This skill assumes brainstorming has happened. If not, invoke brainstorming first.
```

## Versioning

When a skill changes, you may want to track versions. Skill files don't have built-in versioning, but you can:

- Add a version field to frontmatter (informal)
- Date the SKILL.md
- Use git history

For widely-distributed skills, semantic versioning helps users know about breaking changes.

## Testing skills

### Manual testing

Try the skill in real conversations. Does it work for the cases you care about?

### Test cases

Document expected behavior:

```markdown
## Tests

Case 1: User asks "do X"
  Expected: skill invokes; produces Y

Case 2: User asks "do unrelated thing"
  Expected: skill does NOT invoke
```

Run through them periodically.

### Edge cases

Specifically test:
- Ambiguous requests
- Requests that should NOT invoke (negative tests)
- Compositions with other skills
- Long conversations where context is full

## Common pitfalls

### Over-specifying

A skill with 2000 lines of instructions has problems. Too much for Claude to apply consistently. Refactor: shorter primary instructions; references to detail.

### Under-specifying

A skill with 5 lines of "use best practices" doesn't constrain behavior. Specific instructions produce specific outputs.

### Skill instructions that conflict with user instructions

User CLAUDE.md says one thing; skill says another. Per system prompt, user instructions win — but the skill author may not have known.

Document explicit precedence in skill if relevant.

### Skills that update assumed state

Skill says "do A then verify" but doesn't actually verify. User assumes verification happened. False sense of completion.

Make verification explicit and traceable.

### Test-only skills shipping to production

Skills used during development that shouldn't run in normal use. Either don't ship them, or guard with explicit triggers.

## A debugging checklist

When a skill misbehaves:

1. ✓ Is the description specific to when I want it invoked?
2. ✓ Does it have explicit triggers and skip conditions?
3. ✓ Are instructions concrete or abstract?
4. ✓ Are there examples?
5. ✓ Does it assume context that may not exist?
6. ✓ Does it conflict with other skills or user instructions?
7. ✓ Have I tested it in real conversations?

Most issues fall into one of these.

## Further Reading

- [CustomSkillsArchitecture](CustomSkillsArchitecture) — Skill basics
- [SkillComposition](SkillComposition) — Where issues compound
- [SkillDocumentation](SkillDocumentation) — Adjacent practice
- [AgenticAi Hub](AgenticAiHub) — Cluster index

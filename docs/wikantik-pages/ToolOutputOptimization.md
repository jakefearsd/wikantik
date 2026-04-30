---
canonical_id: 01KQ0P44XVNB8EWNENAJF1JM0Q
title: Tool Output Optimization
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How to design tool outputs that work well in agent contexts — concise, structured,
  agent-friendly — and the patterns that distinguish tools agents use well from tools
  agents struggle with.
tags:
- tool-output
- agent-design
- tools
- mcp
- agentic-ai
related:
- TokenMetrics
- SkillPerformance
- CustomSkillsArchitecture
hubs:
- AgenticAiHub
---
# Tool Output Optimization

When agents (Claude or others) call tools, the output goes into the context. Verbose tool output bloats context; missing information requires more tool calls. Designing tool output for agent consumption is a real skill.

This page covers the patterns.

## What tools an agent uses

Agents typically have access to:

- Built-in tools: Bash, Read, Edit, Write
- MCP server tools: domain-specific
- Custom tools: built for specific workflows

Each tool has output. The output appears in the agent's context. Quality matters.

## Principles

### Concise

Tool output should be the smallest amount of useful information for the task.

A `git status` for a clean repo: "Working tree clean" beats a 5-line "no changes to be committed" with formatting.

### Structured

Agents parse better when output is structured. JSON, YAML, table, or consistent line-by-line format.

```
file1.txt: 200 lines
file2.txt: 150 lines
file3.txt: 300 lines
```

is easier for an agent to use than free-form prose describing the same.

### Predictable

Same input → same output format. Agents learn to parse; predictability helps.

### Truthful about errors

Errors shouldn't be hidden. "Failed: file not found" is better than "no results."

### Includes enough context

Sometimes the agent needs more than just the result:

```
File: /path/to/file.txt
Line 42: foo
Line 43: bar (matched)
Line 44: baz
```

Including surrounding context helps the agent understand without re-reading.

## Specific patterns

### Pagination

Long output should be paginated, not dumped:

```
Showing 10 of 5,000 matches. To see more, use --page 2 or filter.
```

Better than 5000 lines.

### Summarization

```
Found 47 issues:
- Critical: 3
- High: 12
- Medium: 18
- Low: 14

Showing top 10 critical and high...
```

The agent can drill into details if needed.

### Filtering

Tools that take filters help agents request only what's needed:

```
list_files(pattern="*.md", limit=20)
```

Better than listing 5000 files.

### Multiple modes

Same tool, different output for different needs:

- Summary: brief; "10 issues found"
- Detail: per-issue specifics
- Verbose: with context

Agents can pick the right mode.

### Agent-friendly format

JSON for structured data; line-oriented for streaming; tables for tabular.

Avoid: heavily-formatted prose; ASCII art; lots of decorative elements.

## What hurts agent performance

### Verbose preamble

```
Welcome to the awesome tool!
We're going to do amazing things.
Let me set everything up for you.
... (actual output buried)
```

Cut the chatter.

### Inconsistent format

Same tool produces different formats based on options. Agent has to handle multiple cases.

### Hidden errors

Tool returns 200 OK with "no results" when actually failing. Agent thinks it worked.

### Ambiguous output

"Done" — done with what? Successfully? With caveats? Be specific.

### Heavy ASCII formatting

Tables drawn with `╔═══╗` characters look nice in terminals; in agent context they're noise.

### Long log dumps

When a tool has internal logging, agents don't need it. Separate logs from results.

## Agent-aware tool design

### Two-stage interaction

For exploratory tools:

```
Stage 1: query → "47 results match. Top 5: A, B, C, D, E. Use detail() for more."
Stage 2: detail(A) → full information about A
```

Agent doesn't pull all 47; explores only what's needed.

### Tool descriptions

The tool's description in the tool-list helps the agent decide when to use it. Specific descriptions are better.

```
search_wiki(query: string) - search wiki content; returns titles and brief excerpts
```

Better than:

```
search_wiki(query) - searches
```

### Examples in description

The agent benefits from worked examples:

```
search_wiki: e.g., search_wiki("python imports") returns relevant wiki pages
```

### Error responses

Errors should be structured:

```json
{
    "error": "not_found",
    "message": "No file at /path",
    "suggestion": "Did you mean /pathh?"
}
```

The agent can respond to specific error types.

## MCP-specific concerns

For tools served via MCP:

### Schema clarity

Each tool has an input schema. Clear, specific schemas help agents generate correct calls.

### Output schema

If output is structured, an output schema helps consumers.

### Example payloads

Including example inputs and outputs in the schema dramatically helps first-call success.

### Sensible defaults

Optional parameters with good defaults reduce required input.

## Common failure patterns

### Tool output too verbose

Bloats context. Agent can't fit much else.

### Tool output too sparse

Forces multiple calls to get needed information.

### Inconsistent output format

Agent has to handle multiple cases; flaky.

### Errors as success

Hard to detect failures programmatically.

### Decorative formatting

Looks nice for humans; noisy for agents.

### No filtering

All-or-nothing output; agent gets too much or too little.

## A reasonable design pattern

For new tools:

1. Default output: concise summary
2. Options for detail/verbose
3. Structured format (JSON or consistent text)
4. Clear error messages with codes
5. Pagination/limits for potentially-large outputs
6. Examples in tool description

## Further Reading

- [TokenMetrics](TokenMetrics) — Measuring efficiency
- [SkillPerformance](SkillPerformance) — Adjacent concern
- [CustomSkillsArchitecture](CustomSkillsArchitecture) — How tools compose with skills
- [AgenticAi Hub](AgenticAiHub) — Cluster index

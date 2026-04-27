---
canonical_id: 01KQ0P44JXAB43ME5MS8TV0AKK
title: Agent Prompt Engineering
type: article
cluster: generative-ai
status: active
date: '2026-04-26'
summary: How to write prompts for LLM agents that actually work — system prompts,
  tool descriptions, error recovery, and the practical patterns that distinguish
  agents that complete tasks from agents that flail.
tags:
- prompt-engineering
- agents
- llm
- generative-ai
related:
- PromptCaching
- TransformerArchitecture
- OpenSourceLlmEcosystem
hubs:
- Generative AI Hub
---
# Agent Prompt Engineering

Agent prompt engineering is the practice of writing prompts that turn LLMs into reliable autonomous workers. Unlike chat prompting, agent prompts must produce structured tool calls, recover from errors, and stay on task across many turns.

Most agent failures are prompt failures. This page covers what works.

## What an agent prompt does

An agent prompt typically defines:
- The agent's role and goals
- Available tools and how to use them
- Output format
- Error handling expectations
- Stopping conditions
- Constraints (what not to do)

The system prompt is loaded once; the conversation evolves with tool calls and results.

## System prompt structure

Effective agent system prompts have a consistent structure:

```
[Role]
You are an agent that ___.

[Tools]
Available tools: ___ (with descriptions)

[Process]
For each task:
1. ___
2. ___
3. ___

[Output format]
Tool calls in format: ___
Final answer in format: ___

[Constraints]
Never: ___
Always: ___

[Error handling]
If a tool fails: ___
If you're stuck: ___
```

Each section serves a specific purpose. Skipping any creates failure modes.

## Tool descriptions

Tools are the agent's hands. Bad descriptions = bad usage.

### Good tool description includes:

- **Purpose**: when to use this tool
- **Parameters**: what each does
- **Returns**: what to expect
- **Examples**: concrete input/output pairs
- **Failure modes**: what to do if it fails

### Bad tool description:

```
search_wiki(query): Searches the wiki.
```

### Better:

```
search_wiki(query: str)
  Searches the wiki for pages matching the query.
  Use for: finding existing pages on a topic.
  Don't use for: getting full content (use get_page).
  Returns: list of {title, snippet, url}.
  Example: search_wiki("authentication") →
    [{"title": "AuthOverview", "snippet": "...", "url": "..."}]
```

The verbose version saves tokens overall by reducing failed tool calls.

## Reasoning prompts

Many agents benefit from "think before acting" patterns:

- **ReAct**: Thought, Action, Observation cycles
- **Chain-of-thought**: think step by step before answering
- **Plan-and-execute**: plan all steps, then execute

Modern LLMs do this naturally with a hint. Explicit reasoning helps for complex tasks.

## Output formats

### JSON

Most reliable for tool calls. Use schemas.

Risk: malformed JSON. Mitigate with constrained generation (Outlines, Instructor) or repair logic.

### Function calling

Native function calling APIs (OpenAI, Anthropic) handle JSON formatting reliably.

### Markdown / structured text

For human-readable outputs. Less reliable for parsing.

### XML tags

Anthropic's models work well with XML-tagged outputs:
```
<thinking>...</thinking>
<answer>...</answer>
```

Pick a format and be consistent.

## Error recovery

Agents must handle failures:
- Tool returns error
- Tool returns unexpected format
- Tool times out
- LLM produces invalid output

### Patterns

**Retry with backoff**: transient errors.

**Reformulate**: if a tool fails, try different parameters.

**Fallback tool**: if primary fails, use alternative.

**Ask for help**: explicit "I need clarification" path.

**Fail loudly**: don't silently swallow errors.

The system prompt should describe these patterns explicitly.

## Stopping conditions

Agents need clear stop signals:
- Goal achieved
- Maximum turns reached
- Stuck in loop
- User asked to stop

### Detecting stuck agents

Common patterns:
- Same tool call repeated with same arguments
- "I'm not sure" loops
- Hallucinating tools that don't exist

Many agent frameworks include detection for these.

## Constraints

Tell the agent what NOT to do:
- "Never modify production data without confirmation"
- "Never create files outside /tmp"
- "Always preserve existing user data"

Constraints are tested at every decision point. Be specific.

## Few-shot examples

For complex tasks, include examples:
```
Example task: Fix the broken authentication
Example trace:
  read_file('auth.py') → ...
  detect bug
  edit_file('auth.py', fix)
  run_tests() → pass
  Done.
```

Few-shot examples in agent prompts dramatically improve quality.

## Context management

Long agent runs blow context windows.

### Strategies

- **Summarization**: condense history periodically
- **Selective retention**: keep important context, drop noise
- **Memory tools**: store and retrieve as needed
- **Sub-agents**: delegate sub-tasks to fresh contexts

Each adds complexity. Start simple.

## Multi-agent patterns

When tasks need multiple specialized agents:

### Hierarchical

Manager agent delegates to worker agents.

### Pipeline

Sequential specialists (research → write → review).

### Debate / consensus

Multiple agents propose; vote.

Adds reliability but multiplies cost. Use only when needed.

## Token budget

Long system prompts cost on every turn.

Trim:
- Redundant instructions
- Examples that don't pull weight
- Verbose tool descriptions for unused tools

But: don't trim what's actually needed. Quality regressions from prompt cuts are common.

## Testing agent prompts

Without tests, prompts drift.

### Approaches

- **Eval suite**: representative tasks; pass/fail rate
- **A/B testing**: compare prompt versions on real traffic
- **Adversarial tests**: edge cases, ambiguous inputs
- **Regression suite**: tasks that previously worked

Build evals before iterating.

## Common failure patterns

### Vague role

"You are a helpful assistant" → undefined behavior.

### Missing constraints

Agent does what wasn't intended because nothing forbade it.

### Tool descriptions too sparse

Agent uses tools wrong; wastes turns.

### No error handling guidance

Agent gives up or hallucinates after first failure.

### Inconsistent output format

Mix of JSON and markdown. Parsing breaks.

### Prompt drift

Prompt updated without testing. Quality regression unnoticed.

### Over-instructed

Hundreds of rules; agent loses the goal.

### No stopping condition

Agent loops forever or until token budget exhausted.

## Specific examples

### Tool-use agent

```
You complete tasks by calling tools.

For each request:
1. Identify which tool(s) you need
2. Call them with appropriate arguments
3. Observe results
4. Iterate or return final answer

Tools:
- search(query): full-text search, returns top 10 results
- read(url): get content of URL
- write(url, content): create or update document

Format tool calls as JSON.
Format final answer as plain text after all tool calls.

Never call write without first reading.
Stop after 10 turns or when goal complete.
```

### Coding agent

```
You write and modify code to complete tasks.

Process:
1. Read relevant files
2. Plan changes
3. Make edits
4. Run tests
5. Iterate until tests pass

Tools: read_file, edit_file, run_command

Never:
- Modify files outside the project
- Skip tests
- Force-push

If tests fail after 3 fix attempts, ask for help.
```

## Iteration

Agent prompts evolve. Track:
- What changed
- Why
- Effect on eval suite

Treat prompts as code: versioned, tested, reviewed.

## Further Reading

- [PromptCaching](PromptCaching) — Caching long prompts
- [TransformerArchitecture](TransformerArchitecture) — How LLMs work
- [OpenSourceLlmEcosystem](OpenSourceLlmEcosystem) — Open models
- [Generative AI Hub](Generative+AI+Hub) — Cluster index

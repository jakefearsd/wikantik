---
canonical_id: 01KQ0P44SSWB07FFCJMDKH5N5H
title: Multi-Agent Orchestration
type: article
cluster: agentic-ai
status: active
date: '2026-04-26'
summary: How multiple agents coordinate — subagent dispatch, parallel work, result
  aggregation — and the patterns that distinguish effective multi-agent systems
  from wasted compute.
tags:
- multi-agent
- orchestration
- subagents
- parallel-work
- agentic-ai
related:
- CustomSkillsArchitecture
- SkillComposition
- TokenMetrics
hubs:
- AgenticAi Hub
---
# Multi-Agent Orchestration

A single agent has its own context, its own attention, its own work to do. For complex tasks, multiple agents working in parallel can be dramatically faster than sequential. Or dramatically wasteful — depending on how the orchestration is designed.

This page covers the patterns.

## When multi-agent helps

### Independent investigation

Three different things to research; each can be a subagent. Results return; main agent synthesizes.

For research-heavy tasks, parallelism is real speedup.

### Parallel implementation

Three independent files to modify; three subagents in parallel. Each completes; main agent moves on.

For tasks that decompose cleanly, real parallel work.

### Different specializations

One subagent handles backend; another handles frontend; another handles tests. Each has its own focus.

### Context isolation

Some work is exploratory and produces lots of intermediate context. Doing it in a subagent keeps the parent context clean.

The "research in subagent; report in main" pattern.

## When multi-agent doesn't help

### Sequential dependencies

Step B needs Step A's results. Spawning subagents doesn't help if work is inherently sequential.

### Small tasks

For 2-minute tasks, the orchestration overhead exceeds the gain.

### Tightly-coupled work

If subagents constantly need to communicate, they're not really parallel.

### Subagents replicating the main agent

If the subagent is doing the same thing the main agent could, just sequentially, it's overhead.

## Patterns

### Fan-out, fan-in

Main agent decomposes the task; spawns N subagents; collects results; synthesizes.

Common for: research, multi-file refactors, parallel analysis.

### Specialist subagents

Different subagents for different specializations. Each has its own skill set.

Common for: code review (different reviewers); multi-stage pipelines; complex workflows.

### Hierarchical

Subagents may spawn their own sub-subagents. Tree of work.

Rarely needed; usually two levels are enough. Too deep = coordination overhead.

### Serial via subagents (non-parallel)

Sometimes a subagent is used not for parallelism but for context isolation. The parent dispatches; waits; gets a clean result.

Useful when the work would be context-heavy but the result is concise.

## Designing for multi-agent

### Self-contained tasks

Each subagent's task should be self-contained. The prompt has everything needed; no implicit context from the parent.

### Concise results

Subagents return their work as text. Should be concise — long results bloat parent context.

### Clear scope

What can each subagent decide? When does it return for parent decision?

### Failure handling

If a subagent fails, what does the parent do? Retry? Different approach? Report?

## Specific patterns

### Research fan-out

```
Main agent: "Research X across these 3 sources"
↓ Spawn 3 subagents, one per source
↓ Each subagent investigates its source
↓ Returns summary
↓ Main agent synthesizes
```

### Multi-file refactor

```
Main agent: identify files needing changes
↓ Spawn subagent per file
↓ Each modifies its file
↓ Returns "done" or specific issues
↓ Main agent verifies
```

### Code review with multiple aspects

```
Main agent: "Review this PR"
↓ Spawn:
  - Subagent for security review
  - Subagent for style review
  - Subagent for test coverage
↓ Each returns its findings
↓ Main agent aggregates
```

### Parallel option exploration

```
Main agent: "Should we do A, B, or C?"
↓ Spawn subagent for each
↓ Each explores its option in depth
↓ Returns trade-offs
↓ Main agent recommends
```

## Specific operational concerns

### Tool access per subagent

Subagents have their own tool access. Configure per subagent type if needed.

### Cost per subagent

Each subagent has its own context, its own tokens. Multi-agent uses more tokens than sequential. Worth it for the speedup; not free.

### Coordination

Some patterns need subagents to coordinate. Anthropic SDK has some support; specific tools (CrewAI, AutoGen) provide more.

For most needs in Claude Code: simple fan-out without inter-subagent communication.

### Result format

Subagents return text. Structured output makes synthesis easier:

```
Subagent reports:
- Key finding: X
- Supporting evidence: Y
- Recommended action: Z
```

## Frameworks

### Claude Code's Agent tool

Built-in support for spawning subagents. Each subagent has its own subagent_type and prompt.

### Anthropic Agent SDK

For building multi-agent systems on the Claude API. Programmatic agent construction.

### LangGraph

LangChain's multi-agent framework. Graph-based agent orchestration.

### CrewAI

Multi-agent framework with role-based agents.

### AutoGen (Microsoft)

Multi-agent framework with conversation patterns.

For Claude Code, the built-in Agent tool covers most needs. For complex agent systems, the SDK or one of the frameworks.

## Common failure patterns

### Spawning subagents reflexively

Subagents for everything; even tiny tasks. Overhead exceeds benefit.

### Sequential disguised as parallel

Subagents called serially, one after another. No real parallelism.

### Subagents needing constant coordination

Defeats parallelism; turns into expensive serialization.

### Result aggregation overhead

Subagents return huge results; parent agent spends most of its tokens parsing them.

### No error handling

Subagent fails; parent doesn't notice; produces wrong result.

### Recursion without bounds

Subagents spawning subagents spawning subagents. Coordination overhead explodes.

## A reasonable approach

For multi-agent design:

1. Identify genuinely parallel work
2. Use subagents for that
3. Keep tasks self-contained
4. Concise result formats
5. Single level of subagents when possible
6. Measure: faster than sequential? worth the cost?

## Further Reading

- [CustomSkillsArchitecture](CustomSkillsArchitecture) — Skill basics
- [SkillComposition](SkillComposition) — Single-agent skill chains
- [TokenMetrics](TokenMetrics) — Cost measurement
- [AgenticAi Hub](AgenticAi+Hub) — Cluster index

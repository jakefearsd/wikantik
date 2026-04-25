---
canonical_id: 01KQ4MEJDNZGZERT87862DAKXX
title: Writing a New MCP Tool
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: Procedure for adding a new MCP tool to either `/wikantik-admin-mcp` or `/knowledge-mcp` — interface, registration site, description convention, tests, and the registry-test count bump.
tags:
  - mcp
  - extension
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You need to expose a new operation to MCP-speaking agents
    - You are migrating an existing internal helper to be agent-callable
    - You are following the design pattern set by GetPageForAgentTool, MarkPageVerifiedTool, or similar
  inputs:
    - The tool's intent in one sentence (matches the eventual `description` field)
    - Whether it's read-only (knowledge-mcp) or write-capable (wikantik-admin-mcp)
    - The argument schema (a small `Map<String, Object>` is the convention)
  steps:
    - Implement the McpTool interface in the appropriate package — knowledge-mcp tools live in wikantik-knowledge/.../mcp; admin tools in wikantik-admin-mcp/.../tools
    - Write the JSON schema in `definition()` — required, optional fields, and a one-sentence description that says when to use it (not what it does)
    - Wire constructor injection via the manager classes — call engine.getManager(YourService.class) at the McpInitializer
    - Register the tool in either KnowledgeMcpInitializer or McpServerInitializer alongside the existing tools-list block
    - Add a unit test (mock the service, call execute(), assert isError plus the JSON shape) — pattern from GetPageForAgentToolTest
    - If McpToolRegistryTest asserts a tool count, bump it
    - Run mvn test on the affected module(s), then a full build
  pitfalls:
    - Forgetting to register the tool in the initializer — the test passes but the tool never shows up over MCP
    - Using `description` to describe the implementation instead of the trigger — agents pick tools by description; "when" beats "how"
    - Mixing read and write semantics on one tool — split if necessary so the read tool can land on /knowledge-mcp
    - Skipping the per-tool unit test — silent regressions at registration time are easy to miss; the test catches them on the next build
    - Hard-coding the GSON instance instead of reusing KnowledgeMcpUtils.GSON / the admin-mcp utilities — produces inconsistent serialisation across tools
  related_tools:
    - /knowledge-mcp/get_page_for_agent
    - /wikantik-admin-mcp/mark_page_verified
  references:
    - GoodMcpDesign
    - AgentGradeContentDesign
    - FindingTheRightMcpTool
---

# Writing a New MCP Tool

The MCP tool surface is the agent-facing API. Every tool added here is
callable by any agent that has access to the relevant endpoint, so the
bar for landing one is "is this useful enough to justify a slot in the
decision tree on `FindingTheRightMcpTool`?".

## When to use this runbook

When you have a concrete new agent operation in mind. Don't write
speculative tools — the cost of a never-called tool is a slot wasted
in the agent's decision space.

## Context

A tool implements `com.wikantik.mcp.tools.McpTool`:

```java
public interface McpTool {
    String name();
    McpSchema.Tool definition();
    McpSchema.CallToolResult execute( Map< String, Object > arguments );
}
```

Read tools live in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/`
alongside `GetPageByIdTool`, `SearchKnowledgeTool`, and the others.
Write tools live in `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/`
alongside `MarkPageVerifiedTool` and `WritePagesTool`.

Registration happens in the matching initializer:

- `wikantik-knowledge/.../mcp/KnowledgeMcpInitializer` — adds tools to
  the assembled `tools` list when the relevant manager
  (`StructuralIndexService`, `ForAgentProjectionService`, etc.) is
  configured.
- `wikantik-admin-mcp/.../McpServerInitializer` (or the registry it
  uses) — adds tools and classifies them as read-only or
  author-configurable.

## Walkthrough

The frontmatter `steps` are the canonical sequence. A few elaborations:

- **Description style.** The description field is what agents read when
  picking tools. Phrase it as a *trigger condition*, not an
  implementation summary. Bad: "Returns a JSON object containing the
  page's metadata." Good: "Resolve a canonical_id to the current page
  descriptor. Prefer this over get_page when citing sources."
- **JSON schema strictness.** Required vs. optional matters — agents
  pick required-only paths first. Don't mark a field required unless
  it's truly required.
- **Test pattern.** `GetPageForAgentToolTest` and
  `MarkPageVerifiedToolTest` both follow the same template: mock the
  underlying service, assert `name()`, `definition()` shape, error
  paths (missing argument, unknown id), and a happy-path
  serialisation check.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes. The most common
in practice is the registration omission — the tool compiles, the test
passes, but the initializer never adds it. Cross-check by running the
relevant `McpToolRegistryTest` (or the equivalent for knowledge-mcp).

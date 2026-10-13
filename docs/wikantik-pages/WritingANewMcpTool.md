---
type: runbook
cluster: agent-cookbook
tags:
- mcp
- extension
- runbook
- agent-context
title: Writing a New MCP Tool
canonical_id: 01KQ4MEJDNZGZERT87862DAKXX
summary: 'How to add an MCP tool to /wikantik-admin-mcp or /knowledge-mcp: base class,
  registration site, instructions file, and the drift tests it trips.'
audience:
- agents
- humans
runbook:
  when_to_use:
  - You need to expose a new operation to MCP-speaking agents
  - You are migrating an existing internal helper to be agent-callable
  - You are following the design pattern set by GetPageForAgentTool, MarkPageVerifiedTool,
    or similar
  inputs:
  - The tool's intent in one sentence (matches the eventual `description` field)
  - Whether it's read-only (knowledge-mcp) or write-capable (wikantik-admin-mcp)
  - The argument schema (a small `Map<String, Object>` is the convention)
  steps:
  - Extend AbstractMcpTool (wikantik-mcp-core, com.wikantik.mcp) and implement name(),
    definition() and doExecute(Map) — the McpTool interface is com.wikantik.mcp.tools.McpTool,
    and AbstractMcpTool.execute() is final and owns the error envelope. Knowledge
    tools live in wikantik-knowledge/.../knowledge/mcp, admin tools in wikantik-admin-mcp/.../mcp/tools
  - Write the JSON schema in `definition()` — required and optional fields, worked
    examples, annotations (READ_ONLY_ANNOTATIONS for read tools), and a one-sentence
    description that says when to use it (not what it does)
  - 'Register a knowledge tool in the matching add*Tools method of KnowledgeMcpInitializer.assembleTools,
    gated on the service it needs being non-null (add the service to KnowledgeToolDeps
    if it is new, and take the PageViewGate if the tool returns page content). Register
    an admin tool in McpToolRegistry: readOnlyList, or authorConfigurableList when
    it extends DefaultAuthorTool'
  - 'Admin tools only: describe the tool in wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt
    and add its name to McpProtocolIT.EXPECTED_TOOLS; if its registration depends
    on wiring, also add its TOOL_NAME to expectedToolNames() in InstructionsRegistryDriftTest,
    or register it unconditionally and refuse at call time as rename_cluster does'
  - Add a unit test (mock the service, call execute(), assert isError plus the JSON
    shape) — pattern from GetPageForAgentToolTest and MarkPageVerifiedToolTest; for
    a knowledge tool also extend KnowledgeMcpToolAssemblyTest to prove the gating
  - Update the tool counts and catalogue in docs/admin/McpAgents.md (it owns the numbers)
    and the wiki page MCP Integration
  - Run mvn test on the affected module(s), then bin/run-tests.sh --parallel 4 — McpProtocolIT
    and McpInstructionsDriftIT are integration tests and only run there
  pitfalls:
  - Forgetting to register the tool in the initializer or registry — it compiles and
    its unit test passes but it never shows up over MCP
  - Adding an admin tool without mentioning it in wikantik-mcp-instructions.txt, or
    without adding it to McpProtocolIT.EXPECTED_TOOLS — InstructionsRegistryDriftTest,
    McpInstructionsDriftIT and McpProtocolIT go red
  - Using `description` to describe the implementation instead of the trigger — agents
    pick tools by description; "when" beats "how"
  - Mixing read and write semantics on one tool — split if necessary so the read tool
    can land on /knowledge-mcp
  - Returning page content from a knowledge tool without the PageViewGate — /knowledge-mcp
    enforces view ACLs as an anonymous guest, so an ungated tool leaks restricted
    pages
  - Writing an admin write tool that skips the SystemPageRegistry refusal, WikiPageNameValidator
    or McpAudit.logWrite — admin MCP tools run with no per-user permission checks,
    so these are the only guards and the only audit trail
  - Hard-coding a Gson instance — use McpToolUtils.SHARED_GSON, or override gson()
    to return KnowledgeMcpUtils.GSON in the knowledge module
  related_tools:
  - /knowledge-mcp/get_page_for_agent
  - /wikantik-admin-mcp/mark_page_verified
  references:
  - GoodMcpDesign
  - AgentGradeContentDesign
  - FindingTheRightMcpTool
  - McpAuditTools
  - McpIntegration
---
# Writing a New MCP Tool

The MCP tool surface is the agent-facing API. Every tool added here is
callable by any agent that has access to the relevant endpoint, so the
bar for landing one is "is this useful enough to justify a slot in the
decision tree on [Finding the Right MCP Tool](FindingTheRightMcpTool)?".

## When to use this runbook

When you have a concrete new agent operation in mind. Don't write
speculative tools — the cost of a never-called tool is a slot wasted
in the agent's decision space.

## Context

A tool implements `com.wikantik.mcp.tools.McpTool` (in `wikantik-mcp-core`):

```java
public interface McpTool {
    String name();
    McpSchema.Tool definition();
    McpSchema.CallToolResult execute( Map< String, Object > arguments );
}
```

In practice you extend `com.wikantik.mcp.AbstractMcpTool` (also in `wikantik-mcp-core`) and implement
`doExecute( Map )` instead of `execute`: the base class makes `execute` final, logs unexpected exceptions
and returns the standard error envelope, and supplies the `READ_ONLY_ANNOTATIONS` constant. Write tools
that attribute saves to the calling agent extend `DefaultAuthorTool` (admin module), which implements
`AuthorConfigurable`; the server injects the MCP client's `clientInfo.name` as the default author before
each call.

Read tools live in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/`
alongside `GetPageByIdTool`, `ListClustersTool`, and the others.
Write tools live in `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/`
alongside `MarkPageVerifiedTool` and `WritePagesTool`. `QueryNodesTool` and `SearchKnowledgeTool`
are shared: they live in `wikantik-mcp-core` and both endpoints register them (the admin copy with
the inclusion-policy bypass on).

Registration happens in the matching place:

- `/knowledge-mcp` — `KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps )` calls five
  `add*Tools` methods (Knowledge Graph, retrieval, structural spine, projection and bundle,
  ontology and citation). Each adds its tools only when the service they need is configured, so the
  endpoint advertises fewer tools on a partial deployment. The tools take a `PageViewGate`, because this
  endpoint enforces page view ACLs as an anonymous guest.
- `/wikantik-admin-mcp` — `McpToolRegistry` builds two lists: `readOnlyTools()` (no author
  injection) and `authorConfigurableTools()`. `McpServerInitializer` registers both with the MCP
  server. Tools that need the Knowledge Graph or the query-log reader are added only when those are
  wired; `rename_cluster`, `list_content_opportunities` and `snooze_opportunity` are registered
  unconditionally and refuse at call time instead.

## The checks a new tool trips

These apply to the **admin** endpoint, whose instructions text is a file
(`wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`):

| Check | Fails when |
|---|---|
| `InstructionsRegistryDriftTest` (unit) | a registered tool is not mentioned by name in the instructions file, or the file mentions a tool-shaped name that is not registered. It adds the KG-conditional tools to its expected set by hand, because the unit-test engine does not wire them |
| `McpInstructionsDriftIT` (integration) | the same two-way diff, against a deployed server's `instructions` and `tools/list` |
| `McpProtocolIT.EXPECTED_TOOLS` (integration) | `tools/list` does not equal that set exactly |
| `McpToolRegistryTest` (unit) | names are not unique, a definition is missing, or fewer than 16 tools register (it does not pin an exact count) |

A new tool-name prefix (say `archive_`) must be added to `TOOL_NAME_PREFIXES` in both
`McpServerInitializer` and `McpInstructionsDriftIT`, or the drift checks will not see it. The
knowledge endpoint has no instructions file (its `instructions` string is inline in
`KnowledgeMcpInitializer`) and no exact-count test; `KnowledgeMcpToolAssemblyTest` covers the
gating and `ToolDescriptionRoutingTest` locks routing hints into descriptions. See
[MCP Audit Tools](McpAuditTools) for the full list of checks.

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
- **Counts.** `docs/admin/McpAgents.md` is the one document that owns the tool counts; other
  documents link to it rather than repeating the numbers.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes. The most common
in practice is the registration omission — the tool compiles, the unit
test passes, but the initializer or registry never adds it. Cross-check
by calling `tools/list` on a running server, which is what
`McpProtocolIT` does for the admin endpoint.

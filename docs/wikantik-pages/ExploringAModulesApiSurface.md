---
title: Exploring a Module's API Surface
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ4ASQXJM6XG48K38W1GSQPR
audience:
- agents
- humans
runbook:
  when_to_use:
  - You are about to make a non-trivial change in a module you haven't worked in before
  - A user asks how a feature is implemented and you need to orient yourself
  - You are deciding where a new piece of functionality belongs across the module
    set
  inputs:
  - The module name (e.g. `wikantik-knowledge`, `wikantik-rest`)
  - 'Optional: the feature you''re trying to land'
  steps:
  - 'Start from the module map: the "Module Structure" section of `CLAUDE.md` and
    `docs/developer/Architecture.md` list every module in the root `pom.xml` with
    its dependency rules (for example `wikantik-insights` depends only on `wikantik-jdbc`,
    and both MCP servers share `wikantik-mcp-core`)'
  - Read `<module>/pom.xml` for the dependency map — what the module imports tells
    you what it can use; shared versions are pinned in `wikantik-bom` and the parent
    pom
  - 'List the exported contracts: `grep -rn "public interface" <module>/src/main/java/`.
    In `wikantik-api` these are the ports: managers in `com.wikantik.api.managers`,
    providers in `com.wikantik.api.providers`, `Plugin` in `com.wikantik.api.plugin`,
    `PageFilter` in `com.wikantik.api.filters`'
  - 'Find the integration points: `grep -rnE "implements .*PageFilter|extends RestServletBase|extends
    AbstractMcpTool|addPageFilter\(|setManager\(" <module>/src/main/java/` — page
    filters (`preSave`, `postSave`), REST resources, MCP tools, and the `*WiringHelper`
    / `*SubsystemFactory` classes that register services with `engine.setManager(Type.class,
    impl)` (ADR-0008)'
  - 'For an endpoint module (`wikantik-rest`, `wikantik-admin-mcp`, `wikantik-knowledge`,
    `wikantik-tools`, `wikantik-scim`), read `wikantik-war/src/main/webapp/WEB-INF/web.xml`
    — the only production web.xml, where servlets, filters and the `McpServerInitializer`
    / `KnowledgeMcpInitializer` listeners are registered. Re-derive counts rather
    than trusting docs: `McpProtocolIT.EXPECTED_TOOLS` for admin MCP tools, the `new
    *Tool(` sites in `KnowledgeMcpInitializer` for knowledge tools'
  - 'Read intent where it is written down: `docs/adr/` and the `CONTEXT.md` glossary
    at the repository root, and the design pages on the wiki. Only `wikantik-it-tests`
    has a README, and `package-info.java` files are rare, so do not expect them'
  - Confirm against the wiki's narrative articles with `/knowledge-mcp` `retrieve_context`
    or `assemble_bundle`, and list a cluster with `list_pages_by_filter`. `search_knowledge`
    queries Knowledge Graph entities, not articles
  pitfalls:
  - Reading the entire module top-to-bottom — most modules have a clear public API
    and a long tail of internal helpers (`wikantik-main` alone has more than 700 classes);
    the latter aren't relevant to most tasks
  - Trusting tests as documentation — tests show edge cases, not intent; the ADRs
    and design docs do that better
  - Forgetting the BOM — `wikantik-bom/pom.xml` pins shared dependency versions; module
    poms inherit from it
  - Looking for a web.xml inside the endpoint modules — there is none; the one production
    web.xml is in `wikantik-war`
  - Treating `getManager` call sites as a feature map — they are frozen by `DecompositionArchTest`,
    and new services register through `setManager` from a wiring helper
  - Copying counts or names from a doc without re-deriving them — tool, servlet and
    module counts drift
  related_tools:
  - /knowledge-mcp/retrieve_context
  - /knowledge-mcp/assemble_bundle
  - /knowledge-mcp/list_pages_by_filter
  references:
  - AnsweringRestApiQuestions
  - WritingANewMcpTool
  - WikantikArchitecture
summary: 'Map an unfamiliar Wikantik module''s public API in five minutes: its dependencies,
  exported interfaces, registered filters and endpoints, and where to read more.'
tags:
- exploration
- module
- runbook
- agent-context
---
# Exploring a Module's API Surface

Wikantik has 24 Maven modules (the `<modules>` block of the root `pom.xml`). Most of them have a small, focused public API and a long tail of internal helpers; `wikantik-main` alone holds more than 700 classes. The trick to fast orientation is reading the public-API surface first.

## When to use this runbook

Before non-trivial work in an unfamiliar module. Five minutes here saves an hour of search-and-grep later.

## Context

The module map is the "Module Structure" section of `CLAUDE.md` and `docs/developer/Architecture.md` in the repository; the wiki's version is [WikantikArchitecture](WikantikArchitecture). Each module is one Maven artifact, and dependencies follow strict layering: `wikantik-api` holds the contracts, `wikantik-main` implements them, and `wikantik-rest`, `wikantik-knowledge`, `wikantik-admin-mcp` and the other edge modules sit on top. A few modules deliberately depend on almost nothing (`wikantik-jdbc`, `wikantik-ingest`, `wikantik-insights`), and the build enforces it.

There is no AST or reflection tooling behind this procedure. The practice is reading poms, grepping for the right shapes, and reading the design documents.

## Walkthrough

The frontmatter `steps` are the canonical procedure: the module map and POMs first (dependency rules), then the exported interfaces, then the registration points that tell you how the module plugs into the engine. The wiki's narrative articles and the ADRs give intent.

## Pitfalls

The frontmatter `pitfalls` are the recurring time-wasters. The "reading the whole module" antipattern is especially expensive on `wikantik-main`, and looking for a `web.xml` inside an endpoint module wastes a search: the only production `web.xml` is in `wikantik-war`.

---
canonical_id: 01KQ4ASQXJM6XG48K38W1GSQPR
title: Exploring a Module's API Surface
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: How to map an unfamiliar Wikantik module's public API in five minutes — what classes are exported, what manager interfaces it implements, what filters it registers, and where its tests live.
tags:
  - exploration
  - module
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are about to make a non-trivial change in a module you haven't worked in before
    - A user asks how a feature is implemented and you need to orient yourself
    - You are deciding where a new piece of functionality belongs across the module set
  inputs:
    - The module name (e.g. `wikantik-knowledge`, `wikantik-rest`)
    - Optional: the feature you're trying to land
  steps:
    - Read `<module>/pom.xml` for the dependency map — knowing what the module imports tells you what it can use
    - Grep for `public interface` in `<module>/src/main/java/` — these are the export points other modules consume
    - Grep for `@Override\s+public\s+\\w+\\s+preSave\\|getManager\\|register` — the lifecycle integration points
    - Read the `<module>/README.md` if present and the package-info.java files for high-level intent
    - Confirm with /knowledge-mcp/search_knowledge — the wiki has narrative articles for most subsystems
  pitfalls:
    - Reading the entire module top-to-bottom — most modules have a clear public API and a long tail of internal helpers; the latter aren't relevant to most agent tasks
    - Trusting tests as documentation — tests show edge cases, not intent; the design docs do that better
    - Forgetting the BOM — `wikantik-bom/pom.xml` pins shared dependency versions; module poms inherit from it
    - Skipping web.xml when the module is wikantik-rest, wikantik-admin-mcp, wikantik-knowledge, or wikantik-tools — those modules ship endpoints, not just classes
  related_tools:
    - /knowledge-mcp/search_knowledge
    - /knowledge-mcp/list_pages_by_filter
  references:
    - AnsweringRestApiQuestions
    - WritingANewMcpTool
---

# Exploring a Module's API Surface

Wikantik has ~20 Maven modules. Most of them have a small, focused
public API and a long tail of internal helpers. The trick to fast
orientation is reading the public-API surface first.

## When to use this runbook

Before non-trivial work in an unfamiliar module. Five minutes here saves
an hour of search-and-grep later.

## Context

The module structure documented in `CLAUDE.md` (the "Module Structure"
section) is the high-level map. Each module is one Maven artifact;
inter-module dependencies follow strict layering (api ← main ← rest /
knowledge / admin-mcp / etc.).

## Walkthrough

The frontmatter `steps` are the canonical procedure. POMs first
(dependency map), then public interfaces, then lifecycle hooks. README
and package-info give intent; the wiki's narrative articles fill in the
rest.

## Pitfalls

The frontmatter `pitfalls` are the recurring time-wasters. The
"reading the whole module" antipattern is especially expensive on
`wikantik-main`, which is over a hundred classes — most of them
internal.

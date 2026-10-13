---
title: What To Do When a Non-Existent Function Is Cited
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ4CB7EGZZKQ1EQMYWFXKSHF
audience:
- agents
- humans
runbook:
  when_to_use:
  - You are about to recommend a function/class/flag and aren't 100% sure it exists
  - A prior agent's output references something you can't immediately locate
  - A memory entry names an API and you want to confirm before acting on it
  inputs:
  - The cited symbol (function name, class name, flag, file path)
  steps:
  - 'Grep the working tree across the file types a Wikantik name can live in: `grep
    -rn ''symbolName'' --include=''*.java'' --include=''*.md'' --include=''*.xml''
    --include=''*.properties'' --include=''*.sh'' --include=''*.jsx'' .`'
  - 'If the symbol is a config key, MCP tool, endpoint, script or migration, check
    its source of truth instead of prose: `wikantik-main/src/main/resources/ini/wikantik.properties`
    for `wikantik.*` keys (every key read in code is declared there), `McpProtocolIT.EXPECTED_TOOLS`
    for admin MCP tools, `wikantik-war/src/main/webapp/WEB-INF/web.xml` for servlets,
    `ls bin/` for scripts, `ls bin/db/migrations/` for migrations'
  - If the grep is empty, search the release notes and git history — `grep -n 'symbolName'
    CHANGELOG.md` (removals and renames are logged under Removed and Changed), then
    `git log --all -S'symbolName' --oneline` and `git log --all -p -S'symbolName'
    | head -100` to find adds and removes
  - If still empty, the symbol either never existed or was renamed; search for likely
    renamings (snake_case ↔ camelCase, plural / singular, the legacy `jspwiki.*` key
    names that became `wikantik.*`)
  - If you can't find any trace, do not recommend it — say "I cannot confirm that
    symbol exists in the current codebase" and ask the user
  - When the symbol does exist, cite the file path and line number (`grep -n`) alongside
    the name, and note the release if it was added or changed recently
  pitfalls:
  - Trusting an LLM's confidence as evidence — confidence is uncorrelated with existence
  - Trusting prior memory entries, CLAUDE.md or a wiki page's counts and names without
    re-verifying — methods get renamed and the docs drift
  - Rephrasing the cite to make it sound more authoritative — that doubles down on
    a hallucination
  - Grepping only `*.java` when the cited thing is a config key, script, tool name
    or route
  - Skipping git history when grep is empty — recently-removed symbols are still in
    history and the user may want to restore them
  related_tools:
  - /knowledge-mcp/retrieve_context
  references:
  - CitingAWikiPage
  - ExploringAModulesApiSurface
summary: 'Verify a cited method, class, flag or tool before recommending it: grep,
  check the source of truth, then CHANGELOG.md and git history; ask only if empty.'
tags:
- verification
- hallucination
- runbook
- agent-context
---
# What To Do When a Non-Existent Function Is Cited

The most expensive class of agent error is recommending a function that doesn't exist. Users follow the recommendation, hit the wall, and distrust the tool for the rest of the session. The defence is simple: verify before recommending.

## When to use this runbook

Whenever you're about to type a function name and your confidence isn't backed by a recent grep.

## Context

LLM training data is a snapshot. Real codebases drift. Memory is ephemeral. The only authoritative source for "does this symbol exist" is the working tree (and, for recently-removed symbols, git history).

This codebase has a lot of recent churn that makes stale citations likely. Examples that are real: the `hubs:` frontmatter field and `HubSyncFilter` were deleted in 2.4.0, `HybridSearchService.rerank()` and the whole KG page-level rerank are gone, the unused page-locking API (`PageManager.lockPage` and friends) was removed in 2.4.29, `getJSP()` became `getRoutePath()`, the `jspwiki.*` property keys became `wikantik.*`, and the MCP API-key scope `mcp` is now `mcp_admin`. `CHANGELOG.md` records removals and renames under its "Removed" and "Changed" headings, so it is the fastest way to find out what happened to a name.

## Walkthrough

The frontmatter `steps` are the canonical verification ladder: grep, then the right source of truth for that kind of name, then `CHANGELOG.md` and `git log -S`, then suspect a rename, then admit defeat. Each rung adds confidence; reaching the bottom rung without a hit is itself the answer.

## Pitfalls

The frontmatter `pitfalls` are the recurring failure modes. The "rephrasing to sound more authoritative" antipattern is especially damaging — it converts a low-confidence guess into high-confidence output without adding any evidence.

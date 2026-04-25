---
canonical_id: 01KQ4CB7EGZZKQ1EQMYWFXKSHF
title: What To Do When a Non-Existent Function Is Cited
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: Verification protocol when an agent (yourself or another) cites a method, class, or flag that may have been hallucinated, renamed, or removed — grep first, ask the user only after both code and git history come up empty.
tags:
  - verification
  - hallucination
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are about to recommend a function/class/flag and aren't 100% sure it exists
    - A prior agent's output references something you can't immediately locate
    - A memory entry names an API and you want to confirm before acting on it
  inputs:
    - The cited symbol (function name, class name, flag, file path)
  steps:
    - Grep the codebase — `grep -r 'symbolName' --include='*.java' --include='*.md' --include='*.xml'`
    - If the grep is empty, search git history — `git log --all -p -S'symbolName' | head -100` finds adds and removes
    - If still empty, the symbol either never existed or was renamed; search for likely renamings (snake_case ↔ camelCase, plural / singular)
    - If you can't find any trace, do not recommend it — say "I cannot confirm that symbol exists in the current codebase" and ask the user
    - When the symbol does exist, cite the file path and line number alongside the function name
  pitfalls:
    - Trusting an LLM's confidence as evidence — confidence is uncorrelated with existence
    - Trusting prior memory entries without re-verifying — methods get renamed and the memory drifts
    - Rephrasing the cite to make it sound more authoritative — that doubles down on a hallucination
    - Skipping git history when grep is empty — recently-removed symbols are still in history and the user may want to restore them
  related_tools:
    - /knowledge-mcp/search_knowledge
  references:
    - CitingAWikiPage
    - ExploringAModulesApiSurface
---

# What To Do When a Non-Existent Function Is Cited

The most expensive class of agent error is recommending a function that
doesn't exist. Users follow the recommendation, hit the wall, and
distrust the tool for the rest of the session. The defence is simple:
verify before recommending.

## When to use this runbook

Whenever you're about to type a function name and your confidence isn't
backed by a recent grep.

## Context

LLM training data is a snapshot. Real codebases drift. Memory is
ephemeral. The only authoritative source for "does this symbol exist"
is the working tree (and, for recently-removed symbols, git history).

## Walkthrough

The frontmatter `steps` are the canonical verification ladder: grep,
then git log -S, then suspect rename, then admit defeat. Each rung
adds confidence; reaching the bottom rung without a hit is itself the
answer.

## Pitfalls

The frontmatter `pitfalls` are the recurring failure modes. The
"rephrasing to sound more authoritative" antipattern is especially
damaging — it converts a low-confidence guess into high-confidence
output without adding any evidence.

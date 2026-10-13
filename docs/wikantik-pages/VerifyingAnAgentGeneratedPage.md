---
date: ''
runbook:
  when_to_use:
  - An AI-generated page has been spot-checked and is ready to mark verified
  - You are working through a stale-page triage list and want to mark several at once
  - A trusted author wants to flag an old page as known-stale rather than re-read
    it
  inputs:
  - A list of page slugs to mark
  - The verifier's login name (defaults to the MCP client's name, which is rarely
    a trusted author)
  - Optional explicit confidence override (`stale` is the common one)
  - Optional change note recorded with the save
  steps:
  - Spot-check each page — read it end-to-end and verify the claims against the codebase
    or external sources
  - Confirm the verifier's login is in the trusted_authors table (there is no admin
    UI for it) — only a recent stamp by a trusted verifier gives `authoritative`;
    a stamp by anyone else gives `provisional`
  - Call /wikantik-admin-mcp/mark_page_verified with `slugs` set to the slug list
    and `verifier` set to the trusted login (without it the verifier defaults to the
    MCP client name)
  - 'Read /admin/verification to confirm the new state — `confidence: authoritative`
    with a recent `verified_at`'
  - 'For known-stale pages, pass `confidence: stale` explicitly so the rule engine
    doesn''t over-promote them'
  pitfalls:
  - Marking pages verified without actually reading them — "mass-verify" is an antipattern
    that destroys the signal
  - Forgetting the trusted author — the stamp lands but confidence stays `provisional`;
    the verifier defaults to the MCP client name, so pass `verifier` explicitly and
    check the `trusted_authors` table
  - 'Pinning `confidence: stale` for a page that''s actually fine — the field is for
    known-stale, not for "I''m not sure"'
  - Passing `pageNames` — the tool takes `slugs` only, and a call without it is rejected
  - Mixing batch sizes — verify_pages handles structural/SEO checks separately; mark_page_verified
    is the verification-flag write
  related_tools:
  - /wikantik-admin-mcp/mark_page_verified
  - /wikantik-admin-mcp/verify_pages
  - /admin/verification
  references:
  - AgentGradeContentDesign
  - StructuralSpineDesign
title: Verifying an Agent-Generated Page
audience:
- agents
- humans
canonical_id: 01KQ4EMR2624F7E7F8PM46AEHG
cluster: agent-cookbook
type: runbook
tags:
- verification
- confidence
- runbook
- agent-context
summary: 'End-to-end verification workflow: spot-check a page, stamp it with mark_page_verified
  on /wikantik-admin-mcp, and confirm its confidence at /admin/verification.'
---

# Verifying an Agent-Generated Page

Verification metadata is the agent's confidence signal. A page marked
`authoritative` was verified within the last 90 days by a trusted author;
`provisional` means it was verified within that window by someone who is
not in the trusted registry; `stale` means it was never verified, or was
verified more than 90 days ago (the window is `wikantik.verification.stale_days`).
An explicit `confidence:` in the frontmatter overrides all of this.

## When to use this runbook

After spot-checking a page, when the act of marking it verified is the
next step.

## Context

`mark_page_verified` writes `verified_at = NOW()` and
`verified_by = <verifier>` into the page's frontmatter; `verifier` defaults to
the MCP client's name, so pass a login explicitly. The structural-index
post-save event syncs the values to the `page_verification` table, where
`ConfidenceComputer` reads them along with the `trusted_authors` registry to
compute the effective `confidence`, in this order: an explicit override wins;
otherwise no `verified_at`, or one older than the stale window, is `stale`;
otherwise a verifier in `trusted_authors` is `authoritative`; anything else is
`provisional`.

The trusted registry is the `trusted_authors` table (keyed by `login_name`).
It is separate from the `wikantik.frontmatter.trustedAuthors` property, which
only drives an advisory `verified_by.untrusted` warning when a page is saved
and never changes confidence.

## Walkthrough

The frontmatter `steps` are the canonical sequence. The trusted-author
check (step 2) is the most-overlooked: a verification call from a
non-trusted author still lands the timestamp but confidence stays
provisional.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes. Mass-verifying
without reading is the most damaging — once the signal is poisoned,
agents stop trusting it.

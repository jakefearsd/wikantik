---
canonical_id: 01KQ4EMR2624F7E7F8PM46AEHG
title: Verifying an Agent-Generated Page
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: End-to-end verification workflow — spot-check the page, mark it verified via `mark_page_verified` on `/wikantik-admin-mcp`, watch confidence transition from `provisional` to `authoritative`, and triage the rest at `/admin/verification`.
tags:
  - verification
  - confidence
  - runbook
  - agent-context
runbook:
  when_to_use:
    - An AI-generated page has been spot-checked and is ready to mark verified
    - You are working through a stale-page triage list and want to mark several at once
    - A trusted author wants to flag an old page as known-stale rather than re-read it
  inputs:
    - A list of page slugs to mark
    - Optional explicit confidence override (`stale` is the common one)
    - Optional change note recorded with the save
  steps:
    - Spot-check each page — read it end-to-end and verify the claims against the codebase or external sources
    - Confirm your login is on the trusted_authors list — only trusted-author saves promote to `authoritative`; others stay `provisional`
    - Call /wikantik-admin-mcp/mark_page_verified with `pageNames` set to the slug list
    - Read /admin/verification to confirm the new state — `confidence: authoritative` with a recent `verified_at`
    - For known-stale pages, pass `confidence: stale` explicitly so the rule engine doesn't over-promote them
  pitfalls:
    - Marking pages verified without actually reading them — "mass-verify" is an antipattern that destroys the signal
    - Forgetting to register as a trusted author — the save lands but confidence stays `provisional`; check `trusted_authors` table
    - Pinning `confidence: stale` for a page that's actually fine — the field is for known-stale, not for "I'm not sure"
    - Mixing batch sizes — verify_pages handles structural/SEO checks separately; mark_page_verified is the verification-flag write
  related_tools:
    - /wikantik-admin-mcp/mark_page_verified
    - /wikantik-admin-mcp/verify_pages
    - /admin/verification
  references:
    - AgentGradeContentDesign
    - StructuralSpineDesign
---

# Verifying an Agent-Generated Page

Verification metadata is the agent's confidence signal. A page marked
`authoritative` was vetted recently by a trusted author; `provisional`
means AI-generated or unverified; `stale` means verified once but more
than 90 days ago.

## When to use this runbook

After spot-checking a page, when the act of marking it verified is the
next step.

## Context

`mark_page_verified` writes `verified_at = NOW()` and
`verified_by = <exchange author>` into the page's frontmatter. The
structural-index post-save event syncs the values to the
`page_verification` table, where `ConfidenceComputer` reads them along
with the `trusted_authors` registry to compute the effective
`confidence`. Trusted-author + recent verification = `authoritative`.
Either condition missing = `provisional`. > 90 days old = `stale`.

## Walkthrough

The frontmatter `steps` are the canonical sequence. The trusted-author
check (step 2) is the most-overlooked: a verification call from a
non-trusted author still lands the timestamp but confidence stays
provisional.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes. Mass-verifying
without reading is the most damaging — once the signal is poisoned,
agents stop trusting it.

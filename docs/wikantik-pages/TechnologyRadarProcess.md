---
canonical_id: 01KQ0P44XFTB6VYT1HESK0KM2X
title: Technology Radar Process
type: article
cluster: engineering-leadership
status: active
date: '2026-04-26'
summary: Tech radars as a tool for shared technology decisions — what they are, how
  to run a radar process, and the cases where the radar is genuinely useful vs. a
  ceremony.
tags:
- technology-radar
- engineering-leadership
- decision-making
- standards
related:
- EngineeringDecisionFrameworks
- TechnicalLeadershipSkills
- TechnicalDebtManagement
hubs:
- EngineeringLeadership Hub
---
# Technology Radar Process

ThoughtWorks popularized the technology radar — a periodically-updated assessment of technologies across rings: Adopt, Trial, Assess, Hold. Many engineering organizations have adopted similar tools internally as a way to make shared technology decisions visible.

The honest assessment: useful for some organizations, ceremony for others.

## What a tech radar is

A tech radar lists technologies (languages, frameworks, libraries, tools, platforms) and their current organizational stance:

- **Adopt**: actively using; recommended for new work
- **Trial**: trying it; promising; not yet default
- **Assess**: evaluating; not yet ready
- **Hold**: don't start using; deprecated or replaced

The radar is published periodically (quarterly or biannually). Engineers consult it when making technology choices for new projects.

## What it's actually for

The real value:

### Shared signal

Without a radar, every engineer makes independent decisions. With it, the org has a shared view: "we're standardizing on Postgres; don't introduce MongoDB without a strong reason."

This reduces fragmentation.

### Forcing a discussion

The radar process — meetings to update the rings — surfaces disagreements about technology direction. Better to have those discussions explicitly than to let fragmentation happen by accident.

### Onboarding aid

New engineers can read the radar to understand what the company uses and recommends.

## When the radar is useful

- **Mid-to-large organizations** (50+ engineers) where fragmentation is a real risk
- **Heterogeneous codebases** where shared decisions help
- **Teams with high autonomy** and a shared platform
- **Organizations actively managing technology choices** rather than letting them drift

## When the radar is ceremony

- **Small teams** (<20 engineers) — informal coordination works
- **Single product, single stack** — fewer technology choices
- **Mature organizations with stable choices** — radar updates rarely
- **Organizations that don't actually enforce the radar** — published but ignored

## Running the process

For organizations adopting a radar:

### Roles

- **Radar maintainers**: small group (2-5 people, often architects) who curate and publish
- **Contributors**: anyone proposing entries or changes
- **Approvers**: usually the maintainers, with input from broader engineering leadership

### Cadence

Quarterly or biannual. More often is too noisy; less often misses real shifts.

### Sources of input

- Engineers proposing technologies they want to use
- Architects recommending standardization
- Operations flagging technologies that are causing problems
- New external trends (AI tools, new languages, etc.)

### The publishing

A document or web page with rings and brief justification per entry. Not just lists — explain *why* something is in each ring.

## Specific patterns

### "Hold" needs migration paths

When something moves to Hold, existing usage needs a path forward. Just saying "we don't use this anymore" doesn't help teams already on it.

### "Adopt" doesn't mean mandatory

The radar is guidance. Sometimes the right answer is to use a technology in Hold for a specific reason. Document the reason.

### Trial is the interesting ring

Adopt is settled; Hold is settled; Assess is too early. Trial is where actual decisions happen. Most radar discussion is about which technologies belong in Trial and what would move them to Adopt.

### The radar should change

If the radar looks the same year-over-year, it's either an unusually stable stack or the radar isn't being maintained.

## Common failure patterns

- **Radar nobody reads.** Published but not actually consulted.
- **Radar with no consequences.** Engineers ignore it; no shared standardization.
- **Radar maintained by people too far from engineering.** Out of touch with reality.
- **Pure technology focus.** Misses operational concerns (how easy is this to run, monitor, debug?)
- **Recency bias.** New things get prematurely promoted.
- **No retirement of "Adopt" entries.** Everything ever adopted stays in Adopt; the ring becomes meaningless.

## A reasonable approach

If you're considering a radar:

1. Decide if your org is large/heterogeneous enough to need it
2. Pick maintainers who actually use the technologies
3. Start small: maybe 30-50 entries
4. Quarterly cadence; be willing to skip a quarter if nothing changed
5. Use the radar in real decisions; if it's not consulted, kill it

## Common failure patterns

- **Radar copied from public examples without adaptation.** Generic; not useful.
- **No process for proposing entries.** Maintainers' preferences only.
- **No process for retiring entries.** Radar bloat.
- **Radar conflicts with actual practice.** Document acknowledges what people don't actually do.
- **Radar as substitute for actual technical leadership.** Decisions still need people making them.

## Further Reading

- [EngineeringDecisionFrameworks](EngineeringDecisionFrameworks) — Adjacent decision tools
- [TechnicalLeadershipSkills](TechnicalLeadershipSkills) — Where this fits
- [TechnicalDebtManagement](TechnicalDebtManagement) — Hold ring often signals debt
- [EngineeringLeadership Hub](EngineeringLeadership+Hub) — Cluster index

---
canonical_id: 01KQ0P44Q57HHK59PX68ZBGPMM
title: Engineering Decision Frameworks
type: article
cluster: engineering-leadership
status: active
date: '2026-04-26'
summary: Frameworks for making and recording engineering decisions — RFC, ADR, DACI,
  RACI — when each fits, and how to use them without descending into bureaucracy.
tags:
- decision-making
- rfc
- adr
- daci
- engineering-management
related:
- TechnicalLeadershipSkills
- TechnicalProjectManagement
- TechnicalWritingGuide
- TechnologyRadarProcess
hubs:
- EngineeringLeadershipHub
---
# Engineering Decision Frameworks

Engineering teams make many decisions. Some are obvious; many aren't. Without a framework, decisions are made informally — in Slack threads, in conversations, in someone's head — and then forgotten or contested later. Frameworks provide structure: who decides, who's consulted, what's recorded.

This page covers the major frameworks and when each fits.

## RFC (Request for Comments)

A document proposing a change. Written by the proposer; commented on by the team; eventually accepted, rejected, or revised.

Structure:
- Summary
- Motivation
- Proposed approach
- Alternatives considered
- Trade-offs and risks
- Decision (after discussion)

When to use:
- Significant technical changes
- Cross-team work
- Decisions where the rationale should be preserved

The Rust language community popularized this in software. Many companies have adopted similar processes.

Length: 2-10 pages typically. Long RFCs lose readers; very short RFCs miss the point.

## ADR (Architecture Decision Record)

A short document recording a single architectural decision.

Structure:
- Title
- Status (Proposed, Accepted, Deprecated, Superseded)
- Context (why we needed to decide)
- Decision (what we chose)
- Consequences (what this commits us to and what it precludes)

ADRs are 1-2 pages. They live in the code repository (often `docs/adr/`).

When to use:
- Architecture decisions worth preserving
- "Why is this like this?" questions you'll answer in 6 months

ADRs are write-once-rarely-update. If a decision is superseded, write a new ADR explaining the change.

## DACI (Driver, Approver, Contributors, Informed)

A role model for decisions:

- **Driver**: makes the decision happen
- **Approver**: makes the final call
- **Contributors**: provide input
- **Informed**: notified of the outcome

For each meaningful decision, identify who's in each role. Avoids the "who decides" confusion.

Useful for:
- Cross-team decisions
- Decisions involving non-engineering stakeholders
- Anything with potential for "I thought we were deciding together"

## RACI (Responsible, Accountable, Consulted, Informed)

Similar to DACI:
- **Responsible**: does the work
- **Accountable**: signs off (one person)
- **Consulted**: provides input before decision
- **Informed**: notified after

Used widely in project management; less common in engineering than DACI.

## When to use which

| Situation | Framework |
|-----------|-----------|
| Significant technical change | RFC |
| Architecture decision worth preserving | ADR |
| Cross-team or stakeholder decision | DACI |
| Long-running project with many decisions | All three at different stages |

For day-to-day technical decisions, no framework is needed. Slack thread, code review comment, quick chat.

## Levels of formality

The right amount of process scales with stakes:

### Trivial decisions

Pick and move on. "Should this method be public or package-private?" Don't write an ADR.

### Local technical decisions

Quick discussion in code review or design doc. No formal record needed if the code is the record.

### Team-level architecture decisions

ADR. Record for future reference.

### Cross-team or strategic decisions

RFC. Discussion across the affected teams. Time-boxed comment period.

### Major technical changes

RFC + DACI. Multiple stakeholders; explicit roles.

Skipping levels in either direction is wrong:
- Process for trivial decisions = bureaucracy
- No process for major decisions = chaos

## What makes decisions actually good

The framework is structure; the decision quality comes from elsewhere:

### Specific options

"Adopt Kubernetes" is not an option. "Migrate the order service to Kubernetes by Q3 with these specific milestones" is. Vague decisions don't constrain action.

### Honest trade-offs

Most decisions have downsides. Acknowledge them. The decision that "has no downsides" is rare; usually the trade-offs are hidden, not absent.

### Reversibility

Distinguish reversible from irreversible decisions:
- **Reversible**: lightweight process; just decide
- **Irreversible**: heavy process; gather information; consider alternatives

Bezos's "Type 1 vs Type 2" decisions. Most decisions are Type 2 (reversible); spend less time on them.

### Time-boxing

Decisions don't take more time because you give them more time. Set a deadline; decide.

### Closing

The decision is made; communicate; move on. Reopening every decision because someone wasn't there is a recipe for paralysis.

## Common failure patterns

- **Process for everything.** Bureaucracy.
- **No process for anything.** Re-litigation.
- **Long-running open decisions.** Team is blocked.
- **Decisions without recording.** Lost in 6 months.
- **No clear decider.** Group paralysis.
- **Approver overruling without explanation.** Demoralizing.
- **Frameworks as substitutes for thought.** Following templates without engaging.

## Further Reading

- [TechnicalLeadershipSkills](TechnicalLeadershipSkills) — Why decisions matter
- [TechnicalProjectManagement](TechnicalProjectManagement) — Where decisions fit in projects
- [TechnicalWritingGuide](TechnicalWritingGuide) — Writing decisions clearly
- [TechnologyRadarProcess](TechnologyRadarProcess) — Tech-decision-specific framework
- [EngineeringLeadership Hub](EngineeringLeadershipHub) — Cluster index

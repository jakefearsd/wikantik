---
canonical_id: 01KQ0P44XDSMA8TVGCH2ZNCQHR
title: Technical Leadership Skills
type: article
cluster: engineering-leadership
status: active
date: '2026-04-26'
summary: The non-coding work that determines tech-lead effectiveness — design judgment,
  delegation, mentoring, communication, and the difference between being a senior
  engineer and a tech lead.
tags:
- tech-lead
- leadership
- engineering-management
- mentoring
related:
- EngineeringDecisionFrameworks
- TechnicalProjectManagement
- RemoteTeamManagement
- BurnoutPreventionInTech
- PairProgrammingPractices
hubs:
- EngineeringLeadership Hub
---
# Technical Leadership Skills

A tech lead is partly an engineer, partly a leader. The transition from "best engineer on the team" to "tech lead" requires skills that aren't built by writing code: judgment about which problems to solve, delegation, mentoring, communication. Many strong engineers fail in tech-lead roles because the skills are different.

This page covers what those skills are.

## What changes

The senior engineer's work: solve hard technical problems with code. Output is direct and visible.

The tech lead's work: ensure the team solves the right problems. Output is indirect — it's the team's output, shaped by your decisions.

The transition is subtle. Many tech leads keep coding heroically while neglecting leadership work. The team's effectiveness eventually constrains theirs.

## The core skills

### Design judgment

Choosing which problems to solve, which approaches to take, what's worth building vs. buying vs. ignoring.

Specific practices:
- **Identifying the actual problem**: not the first problem reported, but what's underneath
- **Comparing alternatives**: not just "this works" but "what are the trade-offs"
- **Saying no**: most ideas should not be built. Filtering matters more than building.
- **Recognizing scope creep**: the project that grows from a feature into a rewrite

Design judgment is largely pattern recognition built from experience. Reading existing systems, understanding why decisions were made, watching projects fail — these inform judgment.

### Delegation

Letting other engineers do work you could do faster. The hard part: it's faster *now*; the team is faster *over time*.

Delegation principles:
- **Assign the work and the authority**: not "do exactly this"; "solve this; ask if you need help"
- **Give context**: why this work matters; what success looks like
- **Don't take it back at the first sign of struggle**: that's where learning happens
- **Adjust based on capability**: more guidance for newer engineers; less for senior

The discomfort of watching someone do it slower or differently than you would is the central challenge.

### Mentoring

Helping engineers grow. Different from delegation — mentoring is the explicit growth work.

Practices:
- **Code review as teaching**: explanations, not just approvals
- **Pairing with intent**: drive sometimes; navigate other times; let them drive when they're ready
- **Honest feedback**: kind but specific; not vague encouragement
- **Career conversations**: where do they want to be in 2 years; what work helps

Most mentoring isn't formal. It's how you spend the 30 minutes after standup, how you write code review comments, what you do when someone gets stuck.

### Communication

Writing and speaking that other people understand. Tech leads communicate constantly:
- Design proposals
- Status updates
- Cross-team negotiations
- Stakeholder explanations
- Code reviews

Specific:
- **Adapt to audience**: engineering vs. PM vs. executive
- **Lead with conclusions**: bottom line first
- **Make trade-offs visible**: there's always a trade-off
- **Brevity is respect**: shorter is usually better

See [TechnicalWritingGuide](TechnicalWritingGuide).

### Decision-making

Tech leads make decisions. Some are obvious; many aren't.

Frameworks help. RFC, ADR, DACI — see [EngineeringDecisionFrameworks](EngineeringDecisionFrameworks). The framework is not the decision; it's the structure for making and recording it.

The pattern that fails: postponing decisions because they're hard. The team is paralyzed; the cost of delay exceeds the cost of being wrong.

### Standing up to authority

Sometimes the right technical answer isn't what your manager or the org wants. Tech leads have to push back.

Specific:
- **Know what you're sure about and what you're not**: don't push back on every decision
- **Make the case in their language**: business impact, not "this is bad architecture"
- **Pick battles**: not every disagreement is worth pushing on
- **Yes, but here are the costs**: more effective than flat "no"

Cowardly tech leads who agree with everything do their teams a disservice.

## What tech leads should not do

### Code everything

The engineer-instinct: when the team is slow, write the code yourself. This is wrong.

You're not the bottleneck for the team's overall output once you're more than 1-2 engineers. Your time is better spent unblocking, designing, delegating.

### Approve every PR personally

Becomes a bottleneck. Trust the team; review what you must.

### Be the only one who knows X

Bus factor of 1 is dangerous. Pair, document, mentor — distribute knowledge.

### Defend incumbent technical choices reflexively

The "we built it this way" instinct. Sometimes the right answer is to change.

## The week-to-week work

A typical tech lead's week:

- 30-50% coding (less than as a senior engineer)
- 20-30% reviews and 1:1s
- 10-20% design and planning
- 10-20% communication and meetings
- 10% the unexpected

The exact mix varies. The principle: the role isn't "senior engineer with extra meetings"; it's a different role.

## Common failure patterns

- **Doing all the hard problems alone.** Team can't grow.
- **Avoiding the people side.** Resentment builds.
- **Saying yes to everything.** Team gets overloaded; nothing finishes.
- **Hands-off too far.** Team flounders without guidance.
- **Status reports as substitute for actual leadership.** Bureaucracy.
- **Treating the role as a promotion to enjoy.** It's harder than the previous role; not a reward.

## Further Reading

- [EngineeringDecisionFrameworks](EngineeringDecisionFrameworks) — Decision tools
- [TechnicalProjectManagement](TechnicalProjectManagement) — Adjacent practice
- [RemoteTeamManagement](RemoteTeamManagement) — Remote-specific
- [BurnoutPreventionInTech](BurnoutPreventionInTech) — Self and team
- [PairProgrammingPractices](PairProgrammingPractices) — Mentoring through pairing
- [EngineeringLeadership Hub](EngineeringLeadership+Hub) — Cluster index

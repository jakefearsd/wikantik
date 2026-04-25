---
canonical_id: 01KQ12YDSP6Z603EZW3ST677A9
title: Blameless Post Mortems
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- post-mortem
- incident-response
- sre
- learning-from-incidents
- reliability
summary: Blameless post-mortems that actually improve the system — what to write,
  what to skip, and the failure modes (theatre, blame in disguise, action items
  that never close) that make them dead documents.
related:
- IncidentResponse
- ChaosEngineering
- ServiceLevelAgreements
- SecurityIncidentResponse
hubs:
- SoftwareArchitecture Hub
---
# Blameless Post-Mortems

A post-mortem is the discipline that turns "we had an incident" into "the system is now better." Blameless means: the question is "what about the system allowed this to happen," not "who screwed up." The framing matters because if engineers are afraid of being named, they hide what actually happened, and you learn nothing.

Most teams claim to do blameless post-mortems and don't. The signals that tell you which kind your team actually runs are below.

## What a post-mortem document looks like

Useful structure:

```
# Incident: User logins failing in EU - 2026-04-12

## Summary
2-3 sentences. What broke, who was affected, when, how was it fixed.

## Impact
- Users affected: 38,000 EU customers, ~6% of total active
- Duration: 14:32-15:18 UTC = 46 minutes
- SLO impact: error budget for the month dropped 22%
- Revenue impact: estimated $X (or "negligible")
- Trust impact: 47 support tickets, mostly Twitter chatter

## Timeline
14:30 - Deploy of v2.4.7 begins
14:32 - First 5xx spikes in eu-west-1 (alert: "p95 latency > 2s")
14:35 - On-call (X) acknowledges, opens incident channel
14:38 - First hypothesis: cache regression. Investigation begins.
14:51 - Hypothesis revised: auth service circuit breaker tripped
14:55 - Mitigation: roll back v2.4.7
15:08 - Rollback complete; latency normalising
15:18 - Verified clean; incident closed

## Root cause(s)
Detailed technical narrative of what actually happened.
Multiple causes if relevant; "contributing factors" is fine.

## What went well
- Alerting fired within 2 minutes of customer impact
- Rollback was one button press; took 13 minutes end-to-end
- Communication in #incidents was clear and pace was good

## What went badly  
- Hypothesis hunting took 13 minutes; the right tool to diagnose
  was a runbook we didn't follow
- The pre-deploy load test missed this because it only ran in us-east

## Action items (concrete, owned, dated)
- [ ] AI-1: Add EU-specific load test stage to deploy pipeline (X, 2026-04-26)
- [ ] AI-2: Update auth-service runbook to include circuit-breaker check (Y, 2026-04-19)
- [ ] AI-3: Reduce auth-service circuit breaker timeout from 60s to 15s (Z, 2026-04-23)
- [ ] AI-4: Add unit test for the specific code path that regressed (X, 2026-04-19)
```

That's the form. The discipline is in the content.

## What "blameless" actually means in practice

The bad version: the post-mortem says "X deployed bad code" and that's framed as "blameless because we didn't *blame* X." It's blame in a fig leaf.

The good version: the post-mortem says "the deploy pipeline accepted code that was unit-tested but not integration-tested in EU; the pre-deploy review was a rubber stamp because the reviewer didn't know what to look for; the test framework didn't have an EU stage." X is mentioned as "the engineer on duty" if at all; the *system* (pipeline + review + test infrastructure) is what's analysed.

The test for whether your team is actually blameless: would the engineer who made the mistake be willing to write the post-mortem themselves? If yes, you're there. If no, the culture is performative.

## Why blameless is the right framing

Blame creates fear. Fear creates lying. Lying creates worse incidents.

Concrete: if engineers fear being named, they:
- Don't speak up early in an incident ("maybe it's nothing").
- Tidy logs before sharing them.
- Skip writing post-mortems for "small" incidents.
- Avoid changes to risky systems because "if it breaks I'll be blamed."

Each of these directly degrades reliability. A blameless culture is faster to recover, faster to learn, and produces engineers who own systems instead of avoiding them.

This isn't ideology; it's reproducible empirical observation across SRE-mature organisations.

## What to write down

The narrative is the most important thing. Good post-mortems read like a story:

- "We deployed v2.4.7 at 14:30. The deploy completed normally."
- "At 14:32 the first 5xx spikes appeared in eu-west-1."
- "The on-call engineer initially suspected the cache layer because of the recent migration."
- "After 13 minutes of investigation, the engineer noticed the auth service was reporting elevated circuit-breaker trips."

The reader should be able to follow how the team came to understand what was happening. That narrative is where the learning lives — for everyone who reads it later.

Avoid:

- **Passive voice everywhere.** "The deploy was rolled back" — by whom? Concrete agency makes the timeline reproducible.
- **Pure technical autopsy without the human side.** "The cache hit rate dropped to 0.4 and the latency exceeded the threshold" tells you nothing about how the team responded.
- **Action items without owners or dates.** They never happen.
- **Redactions or omissions** of "embarrassing" details. The embarrassing details are usually the load-bearing learning.

## The five whys, used carefully

Toyota's "five whys" — keep asking why until you get to a root cause. Useful technique but can mislead.

Trap 1: there's rarely one root cause. Most incidents are several contributing factors that aligned. "Five whys" can artificially focus on one branch and miss the others.

Trap 2: you can ask "why" until the answer is "because the universe exists." Stop at actionable.

Better framing: ask "what conditions had to exist for this to happen" and list them all. Each is a leverage point for prevention.

## Action items: where post-mortems die

Most post-mortems produce action items. Most action items don't ship. The pattern:

- Item created with "TBD owner."
- No owner ever volunteers.
- Item moves from sprint to sprint.
- 6 months later, item silently dropped.

Defences:

- **Every action item has a named owner before the document is published.** No exceptions.
- **Every action item has a date.** "This sprint" or "by 2026-05-01."
- **Action items are tracked in your issue tracker, not just the post-mortem doc.** Post-mortem doc links to the tickets; tickets get worked.
- **Open action items review monthly.** Stale items either get a new date or get closed as "won't do" with reasoning.

If your action items don't close, your post-mortems aren't producing improvement; they're producing paperwork.

## Severity calibration

Not every incident needs a full post-mortem. A reasonable scale:

- **SEV-1 (major outage, customer-facing).** Mandatory full post-mortem. Reviewed by leadership.
- **SEV-2 (degraded performance, partial outage).** Post-mortem, possibly lighter. Reviewed by team.
- **SEV-3 (minor, internal).** Decision: post-mortem if there's learning, otherwise a brief incident note. Don't bureaucratise.
- **SEV-4 (transient).** Track as a metric; no document needed unless pattern emerges.

The risk is post-mortem fatigue — every minor blip gets a 5-page document, nobody reads them, nobody acts. Calibrate.

## Reading post-mortems is also the job

A post-mortem you wrote helps your team learn. A post-mortem you read helps you learn from someone else's incident.

Practices that work:

- **All post-mortems searchable in one place.** Wiki, Notion, dedicated tool.
- **Post-mortem review meeting** monthly across teams. Read 1-2; discuss what's transferable.
- **Tagging by system or category** so you can find "all incidents involving auth" or "all caching incidents."
- **Cross-org publication** of redacted-as-needed post-mortems. The Cloudflare and Stripe public ones are templates worth studying.

## Failure modes of the post-mortem culture

- **Post-mortem theatre.** Documents are written for show; nothing changes. Audit by tracking action-item completion rates.
- **Blame disguised as analysis.** "The on-call engineer should have noticed sooner" — that's blame. "The runbook didn't include the diagnostic step" — that's analysis.
- **Premature root-causing.** "The root cause was a bad deploy" — what about the deploy was bad, what allowed the bad deploy, what would have prevented it.
- **Skipping incidents that didn't impact users.** Near-misses are gold. Document the close calls; the next one might not miss.
- **The same incident, again, six months later.** A failure to follow up on the previous post-mortem's action items.

## A post-mortem template

For a team that doesn't have one, the structure at the top of this page works. Adapt the headers; keep the discipline of timeline + narrative + named action items.

## Further reading

- [IncidentResponse] — the muscle being practised
- [ChaosEngineering] — proactive incident generation
- [ServiceLevelAgreements] — the SLO framework that defines incident severity
- [SecurityIncidentResponse] — security-specific incident handling

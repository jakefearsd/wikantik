---
canonical_id: 01KQ0P44T93791XXN6WF0T0ZD1
title: On-Call Practices
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: How to run on-call rotations that work — escalation, alerting discipline,
  postmortems, and the patterns that prevent on-call burnout while still catching
  real incidents.
tags:
- on-call
- sre
- incidents
- alerting
- runbooks
related:
- RunbookAutomation
- ToilReductionStrategies
- StatusPageBestPractices
- CloudMonitoring
hubs:
- DevOpsAndSreHub
---
# On-Call Practices

On-call: someone is responsible for production at all times. When something breaks, they're paged. They diagnose; they mitigate; they involve others if needed.

Done well, on-call is sustainable. Done poorly, it's miserable and people quit.

This page covers the practices that work.

## The on-call function

The on-call's job:

1. **Acknowledge alerts**: someone is on it
2. **Triage**: severity? blast radius? customer impact?
3. **Mitigate**: stop the bleeding; revert, scale, kill switch
4. **Investigate**: find root cause (often after mitigating)
5. **Communicate**: status updates; involve others if needed
6. **Postmortem**: capture lessons after

The first priority is mitigation. Root cause investigation comes after.

## Rotation patterns

### Single-person rotation

One person at a time. Common for small teams.

Pros: simple; one point of contact.
Cons: long shifts hurt; primary alone if complex incident.

### Primary + secondary

Primary takes pages; secondary backs up. Both rotate.

Pros: complex incidents have backup; reduced pressure on primary.
Cons: more rotation slots needed.

### Follow-the-sun

Different time zones cover different hours. EU primary 8am-5pm; US primary 5pm-2am; APAC overnight.

Pros: no one paged at 3am.
Cons: requires global team; handoffs are weak points.

For most teams, primary + secondary is the right model.

## Rotation length

### Weekly

Sunday to Sunday, or Monday to Monday. Common.

### Daily

Each person on call for one day. Reduces fatigue but more handoffs.

### Multi-week

Two-week rotations. Spreads incidents across more time but increases per-rotation burden.

For most teams, weekly is the sweet spot.

## Alerting discipline

The hardest part. Alarms must:

### Fire only when human action is needed

If automation can handle it (auto-scaling, auto-restart), let automation handle it. Don't page humans for things they can't actually do.

### Be actionable

Each alert has a runbook. The on-call knows what to do.

### Match severity

Page-worthy: real customer impact; high severity.
Non-paging: warning; investigate during business hours.
Tickets: low priority; backlog.

The default should be ticket; escalate as needed.

### Have ownership

Each alert has an owning team. Stray alerts that nobody owns get ignored.

### Be tuned

Alarms that fire often without action become noise. Tune until each alert is actionable.

## Alert fatigue

The single biggest on-call failure mode. Symptoms:

- "I'll check it in the morning"
- Alerts ignored for non-urgent issues
- Real incidents missed in the noise

Causes:
- Too many alerts
- Alerts on causes instead of symptoms
- Alerts without runbooks
- Inherited alerts no one owns

Fix: ruthless tuning. Every fired alert should have led to action. If not, remove or downgrade.

The 80/20 rule: a few alarm types cause most of the noise. Eliminate them and on-call quality dramatically improves.

## Runbooks

Each alert points to a runbook. The runbook says: when this happens, here's how to respond.

Good runbooks:
- Specific commands
- Common causes
- Escalation criteria
- Links to dashboards

See [RunbookAutomation](RunbookAutomation).

## Escalation

When to escalate:
- Beyond your expertise
- Severity higher than expected
- Customer-visible impact growing
- Stuck on an issue

Escalation paths defined in advance: secondary on-call, manager, specific subject-matter experts. Not "Bob, but he's on vacation."

## Mitigation playbook

Common mitigation tools:

- **Rollback**: revert the recent deploy
- **Kill switch / feature flag**: disable the broken feature
- **Scale up**: add capacity
- **Failover**: switch to backup region
- **Restart**: when the symptom is "service stuck"

Practice these during quiet times. Don't first try them during incidents.

## Communication during incidents

For high-severity incidents:

### Incident commander

Owns the incident. Coordinates. Not the same as the technical lead.

### Status updates

Periodic updates (every 15-30 min for active incidents). Even if nothing has changed: "still investigating." Silence is worse than slow progress.

### Customer-facing communication

Status page; sometimes targeted emails. See [StatusPageBestPractices](StatusPageBestPractices).

### Internal communication

Slack channel for the incident. Everyone involved. After-action report.

## Postmortems

After any meaningful incident:

### Blameless

Focus on system causes, not individual blame. People made decisions with the information they had. The system shouldn't have allowed the failure.

### Timeline

What happened, when, in what order. Reconstruct.

### Root cause analysis

Not just "Bob deployed the bad code." Why did the bad code pass review? Why didn't tests catch it? Why was monitoring late?

### Action items

Specific changes with owners. Not "we should improve testing"; specific tests, specific tools, specific timeline.

### Sharing

Postmortems shared widely. Other teams learn from the incident.

## Compensation and time-off

On-call is real work; compensate appropriately.

- **On-call pay**: stipend or extra compensation
- **Comp time**: time off after busy on-call shifts
- **Right to disconnect**: no expectations of work after rotation ends
- **Vacation coverage**: arranged in advance

Companies that don't compensate on-call lose engineers to companies that do.

## Common failure patterns

- **Alerts ignored.** Real incidents missed.
- **No runbooks.** On-call invents response in the moment.
- **Hero on-call.** One person handles everything; burnout.
- **Blame culture.** Postmortems find scapegoats; people hide mistakes.
- **No escalation path.** On-call alone with hard problems.
- **No compensation.** Engineers leave for better deals.
- **No alert tuning.** Noise drowns signal forever.

## Further Reading

- [RunbookAutomation](RunbookAutomation) — Automate the manual response
- [ToilReductionStrategies](ToilReductionStrategies) — Reduce on-call load
- [StatusPageBestPractices](StatusPageBestPractices) — Customer communication
- [CloudMonitoring](CloudMonitoring) — Where alerts come from
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

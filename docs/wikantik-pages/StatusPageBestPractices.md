---
canonical_id: 01KQ0P44WY3QKK4CKQFRV5QCFW
title: Status Page Best Practices
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: How to run a status page that builds trust — clear status definitions, timely
  updates, postmortem disclosure, and the patterns that distinguish honest status
  pages from cover-up theater.
tags:
- status-page
- incidents
- communication
- transparency
related:
- OnCallPractices
- RunbookAutomation
- CloudMonitoring
hubs:
- DevOpsAndSreHub
---
# Status Page Best Practices

A status page is the customer-facing record of service availability. Done well, it builds trust during incidents — customers see the company is on it, communicating clearly, and recovering quickly. Done poorly, it's a tool for cover-up that erodes trust faster than the incident itself.

This page covers what makes status pages actually work.

## What a status page shows

### Component status

Each major service or feature has a status:
- **Operational**: working normally
- **Degraded**: some users affected; functionality reduced
- **Partial outage**: significant impact; many users affected
- **Major outage**: service unavailable

The granularity matters. "API is down" is more useful than "everything is down" or "subsystem 47b is down."

### Active incidents

Current issues with timeline of updates. Most recent on top.

### Past incidents

History of resolved issues. Useful for customers evaluating reliability.

### Scheduled maintenance

Planned downtime announced in advance.

### Subscriptions

Customers can subscribe to email/SMS/RSS updates.

## What good status pages look like

### Truthful

The status reflects reality. If the API is down, the page says so within minutes.

The temptation: keep it green to avoid bad metrics. Customers notice; trust erodes.

### Timely

Updates posted within minutes of incident start. Customers shouldn't have to call support to find out something's wrong — they should already know from the status page.

### Specific

"We are investigating elevated error rates on the orders API. ~30% of requests affected. ETA unknown; updating in 15 minutes."

Better than:
"We are investigating an issue."

### Honest about uncertainty

"We don't yet know the cause" is OK. "We are confident the issue is resolved" with a cause stated is fine. Hedging when it isn't appropriate erodes trust.

## What bad status pages look like

### Marketing-driven

"We are committed to providing world-class service while we investigate this temporary connectivity blip."

Customer translation: "Something is broken; they're using marketing words."

### Vague

"We are experiencing intermittent issues with some services."

Useless. Which services? What issues? What's affected?

### Late

The page goes red 30 minutes after customers start calling support. Clearly wasn't actively monitored.

### Always green

Customers know the service is broken; status page is green. Confidence destroyed.

### Postmortems hidden

Resolved incidents disappear from the page. No record; no learning visible.

## Tools

### Statuspage.io (Atlassian)

The dominant choice. Established; full-featured.

### Better Stack, Hund, Statuspal

Modern alternatives. Comparable features; sometimes better pricing.

### Self-hosted (Cachet, Statping)

Open-source. For privacy-sensitive companies.

### Custom

Built on top of monitoring data. Reasonable for specific needs.

For most companies, hosted services are right. The page itself is rarely a competitive differentiator.

## The update process

During an incident:

### Initial post (within 5-15 minutes)

"We are investigating reports of [specific symptom]. Affected: [scope]. We will update in 15 minutes."

Even if you don't have details. Acknowledge; commit to next update.

### Periodic updates

Every 15-30 minutes during active incidents. Even if nothing has changed: "still investigating; ETA unknown."

### Mitigation

When the immediate impact is contained: "We have applied a mitigation; users should see normal behavior. We continue to investigate root cause."

### Resolution

"The incident is resolved. We will publish a postmortem within [N business days]."

### Postmortem

The full report: timeline, root cause, what we're doing differently. Customer-facing version may be shorter than internal version.

## Severity calibration

Match status to actual impact:

- **Operational**: nothing's wrong
- **Degraded**: some users seeing issues; functionality available
- **Partial outage**: significant subset affected; major features unavailable
- **Major outage**: most/all users; service unavailable

Don't over-grade (every issue is "major") or under-grade (everything is "degraded" until resolved).

## Internal vs. external status

Some companies have:
- **Public status page**: customer-facing; broader strokes
- **Internal status page**: detailed; for support/engineering teams

The internal page can have more detail, more components, more granular status.

## Common failure patterns

- **Status page out of date.** Customers know more than the page.
- **Marketing language during incidents.** Erodes trust.
- **Hiding incidents.** Customers see; trust evaporates.
- **No postmortem disclosure.** Pattern of incidents but no learning visible.
- **Vague updates.** "Issues with services" doesn't help.
- **No subscription option.** Customers can't get updates pushed.
- **Component definitions don't match customer mental model.** "Subsystem foo is down" — what's that?

## The trust dimension

Status pages are about trust as much as information. Customers extend trust to companies that:

- Communicate honestly during failures
- Take responsibility
- Demonstrate they understand and improve

A company that hides outages is presumed less trustworthy than one that shares them and explains. Even if the latter has more outages.

This is counterintuitive but important. Cover-up costs more than the original incident.

## Further Reading

- [OnCallPractices](OnCallPractices) — Source of incidents
- [RunbookAutomation](RunbookAutomation) — Faster response
- [CloudMonitoring](CloudMonitoring) — What feeds the status
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

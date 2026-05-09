---
cluster: devops-sre
canonical_id: 01KQ0P44R28P11K576MM618FZX
title: Incident Management
type: article
tags:
- devops
- sre
- incident-response
- on-call
- post-mortem
status: active
date: 2025-05-15
summary: Technical guide to incident response and on-call operations. Covers severity levels, communication protocols, and blameless post-mortems.
auto-generated: false
---

# Incident Management: Operational Resilience

Incident Management is the process used by DevOps and SRE teams to respond to unplanned events or service interruptions and restore service as quickly as possible.

## 1. Incident Lifecycle and Severities

Every incident must be categorized to dictate the response level:
*   **SEV-1 (Critical):** Core service is down for all users (e.g., Checkout is broken). Immediate "War Room" required.
*   **SEV-2 (High):** Significant degradation for a subset of users. Response within 30 minutes.
*   **SEV-3 (Medium):** Minor bug or non-critical feature failure. Resolved during business hours.

## 2. The Response Role: Incident Commander (IC)

The IC is the single source of truth during a SEV-1. They do not write code; they manage the **Flow of Information**.
*   **Scribe:** Documents the timeline in real-time (Slack/Zoom).
*   **Communications Lead:** Handles updates to status pages and executive stakeholders.
*   **Operations Lead:** Coordinates the engineers actually applying the fix.

## 3. The PACE Communication Plan

Relying on one channel is an operational risk.
*   **Primary:** Slack / Microsoft Teams.
*   **Alternate:** Zoom / Google Meet Bridge.
*   **Contingency:** Phone bridge / SMS.
*   **Emergency:** StatusPage.io (External to infrastructure).

## 4. Blameless Post-Mortems (RCA)

After the service is restored, the team must perform a Root Cause Analysis.
*   **The Five Whys:** Keep asking "Why?" until you reach the systemic failure, not the human error.
    *   *Human Error:* "Engineer ran the wrong command."
    *   *Systemic Failure:* "The command-line tool lacked a confirmation prompt for destructive actions in production."
*   **Action Items:** Every post-mortem must result in tickets (e.g., "Add confirmation prompt to CLI," "Add unit test for X").

---
**See Also:**
- [Game Day Exercises](GameDayExercises) — Practicing the response.
- [Monitoring and Alerting](MonitoringAndAlerting) — Detecting the incident.
- [Graceful Degradation](GracefulDegradation) — Limiting incident impact.

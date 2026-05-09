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
- forensics
- rca
status: active
date: 2025-05-15
summary: Professional SRE guide to incident response, emphasizing 'Golden Hour' forensics, technical root-cause analysis (RCA) patterns, and systemic remediation.
auto-generated: false
---

# Incident Management: Operational Resilience

Incident Management is the process used by DevOps and SRE teams to respond to unplanned events or service interruptions and restore service as quickly as possible. In a high-maturity SRE organization, this isn't just about "fixing the bug"—it's about forensic precision and systemic immunity.

## 1. Incident Lifecycle and Severities

Every incident must be categorized to dictate the response level:
*   **SEV-1 (Critical):** Core service is down for all users (e.g., Checkout is broken). Immediate "War Room" required.
*   **SEV-2 (High):** Significant degradation for a subset of users. Response within 30 minutes.
*   **SEV-3 (Medium):** Minor bug or non-critical feature failure. Resolved during business hours.

---

## 2. The 'Golden Hour' Forensics

In SRE, the **Golden Hour** refers to the first 60 minutes of a major incident. Actions taken here determine the Mean Time to Recovery (MTTR) and the quality of the subsequent Root Cause Analysis (RCA).

### A. Non-Destructive Mitigation
The primary goal is restoration, but the secondary goal is **Preservation**. 
*   **Avoid 'The Big Reboot':** Indiscriminately restarting all pods/instances destroys volatile state (memory leaks, thread deadlocks, cache corruption) that is essential for RCA.
*   **Isolation First:** Use traffic shifting (canary bypass) or circuit breakers to isolate the failing component rather than killing it.

### B. Forensic Data Capture Checklist
Before applying a patch or restarting, capture the following state:
1.  **Thread Dumps & Heap Dumps:** For JVM/Go/Node processes, capture the state of concurrency and memory.
2.  **Kernel/Network State:** Use `ss -atp` (socket state), `dmesg`, and `conntrack` tables to identify network saturation or resource leaks.
3.  **eBPF Probes:** Use tools like `bpftrace` to capture syscall latency or file I/O spikes that metrics might aggregate away.
4.  **Log Snapshot:** Tail and preserve the last 10,000 lines of application and system logs before they are rotated or lost.

---

## 3. The Response Role: Incident Commander (IC)

The IC is the single source of truth during a SEV-1. They do not write code; they manage the **Flow of Information**.
*   **Scribe:** Documents the timeline in real-time (Slack/Zoom).
*   **Communications Lead:** Handles updates to status pages and executive stakeholders.
*   **Operations Lead:** Coordinates the engineers actually applying the fix.

## 4. The PACE Communication Plan

Relying on one channel is an operational risk.
*   **Primary:** Slack / Microsoft Teams.
*   **Alternate:** Zoom / Google Meet Bridge.
*   **Contingency:** Phone bridge / SMS.
*   **Emergency:** StatusPage.io (External to infrastructure).

---

## 5. Root Cause Analysis (RCA) Patterns

A "Blameless" culture is the prerequisite for effective RCA. We move from "Who did it?" to "Why did the system allow this?".

### A. The Five Whys (Systemic Depth)
*   **Human Error:** "Engineer ran a destructive SQL command."
*   **Technical Root Cause:** "The staging and production environments had identical CLI prompts."
*   **Systemic Failure:** "The database access layer lacks a 'read-only by default' policy for interactive shells."

### B. RCA Methodology: The Three Perspectives
1.  **Proximate Cause:** The immediate trigger (e.g., a deployment, a configuration change, a spike in external traffic).
2.  **Underlying Cause:** The vulnerability that made the trigger catastrophic (e.g., missing retry limits, lack of timeout on a dependency).
3.  **Systemic Cause:** The organizational or architectural debt that allowed the vulnerability to exist (e.g., lack of automated integration testing for failure modes).

### C. Common RCA Anti-Patterns to Avoid
*   **Correlation Fallacy:** Assuming two spikes on a dashboard are causal without verifying the sequence of events.
*   **Pointing to 'The Human':** Stopping at "human error" is the ultimate failure of incident management. It leaves the trap set for the next engineer.
*   **Dashboard Bias:** Looking only at existing metrics. If the metrics were sufficient, the incident might have been predicted or automatically mitigated.

---

## 6. Closing the Loop: Action Items

Every post-mortem must result in prioritized tickets.
*   **Detection:** "Add alert for P99 latency on Auth service."
*   **Mitigation:** "Implement auto-circuit breaker on downstream DB."
*   **Prevention:** "Update CI/CD pipeline to reject deployments with missing health checks."

---
**See Also:**
- [Game Day Exercises](GameDayExercises) — Practicing the response.
- [Monitoring and Alerting](MonitoringAndAlerting) — Detecting the incident.
- [Graceful Degradation](GracefulDegradation) — Limiting incident impact.
- [Blameless Post-Mortems](BlamelessPostMortems) — Cultural foundations.

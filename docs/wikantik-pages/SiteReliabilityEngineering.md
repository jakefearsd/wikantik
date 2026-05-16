---
cluster: devops-sre
canonical_id: 01KQ0P44WE0GVZNGYFJ0NT6TXJ
title: Site Reliability Engineering
type: article
tags:
- SRE
- SLO
- SLI
- error-budget
date: 2025-05-15
summary: A technical guide to the SRE triad—SLIs, SLOs, and Error Budgets—focusing on the math of reliability and the policy of feature freezes.
auto-generated: false
---

# Site Reliability Engineering: SLO Math and Error Budgets

Site Reliability Engineering (SRE) is the discipline of treating operations as an engineering problem. This article focuses on the core mathematical and policy framework that governs reliability: Service Level Indicators (SLIs), Service Level Objectives (SLOs), and Error Budgets.

## I. The Triad of Reliability

1.  **SLI (Service Level Indicator):** A quantitative measure of some aspect of the level of service that is provided. 
    *   *Examples:* Request latency, error rate, system throughput, availability.
2.  **SLO (Service Level Objective):** A target value or range of values for a service level that is measured by an SLI.
    *   *Example:* "99.9% of requests will have a latency < 200ms."
3.  **Error Budget:** The difference between perfect reliability (100%) and the SLO.
    *   *Calculation:* $\text{Error Budget} = 100\% - \text{SLO}$.

## II. The Math of SLOs

Reliability is measured over a specific **compliance window** (e.g., 28 or 30 days).

### A. Availability SLOs
Availability is often expressed in "nines."
*   **99.9% (Three Nines):** Allows for 43 minutes and 12 seconds of downtime per 30 days.
*   **99.99% (Four Nines):** Allows for only 4 minutes and 19 seconds of downtime per 30 days.

### B. Latency SLOs (Percentiles)
Average latency is a poor metric because it hides outliers. SREs use percentiles:
*   **p50 (Median):** The typical user experience.
*   **p99:** The "tail" experience (the slowest 1% of requests).
*   **SLO Target:** "99th percentile latency for `GET /api/v1/resource` must be < 500ms over a 28-day rolling window."

## III. The Error Budget Policy

The Error Budget is the "operational currency" shared between product and engineering teams.

### A. The Burn Rate
The burn rate is the speed at which the error budget is being consumed relative to the time remaining in the window.$$\text{Burn Rate} = \frac{\text{Budget Consumed}}{\text{Time Elapsed}} / \frac{\text{Total Budget}}{\text{Total Window}}$$
*   **Burn Rate = 1:** You will hit the SLO exactly at the end of the window.
*   **Burn Rate > 1:** You are on track to violate the SLO.

### B. The Feature Freeze
When the error budget is exhausted (reaches 0), a **Feature Freeze** is automatically triggered.
1.  **Stop:** All new feature deployments are halted.
2.  **Focus:** Engineering effort is redirected entirely to reliability improvements, technical debt reduction, and bug fixes.
3.  **Resume:** Deployments resume only after the rolling window "recovers" sufficient budget.

## IV. Monitoring and Alerting

Alerting should be based on **Symptoms**, not Causes.
*   **Good Alert:** "Burn rate is 14.4 (on track to exhaust budget in 36 hours)."
*   **Bad Alert:** "CPU is high on server X." (This may or may not impact the SLO).

### A. Multi-Window, Multi-Burn-Rate Alerts
To reduce noise, use two windows:
1.  **Short Window (e.g., 1 hour):** Detects sudden, catastrophic failures.
2.  **Long Window (e.g., 6 hours):** Detects slow, persistent "leaks" that threaten the long-term SLO.

## V. Conclusion: Reliability as a Product Feature

In SRE, reliability is the most important feature of any system. By using SLOs to define acceptable failure and Error Budgets to manage risk, teams can maintain a high velocity of innovation without compromising the stability that users depend on.

For deeper technical implementation, see [ChaosEngineering](ChaosEngineering) and [MonitoringAndAlerting](MonitoringAndAlerting).

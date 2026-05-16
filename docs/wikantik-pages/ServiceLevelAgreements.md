---
canonical_id: 01KQ12YDWJ5RQ66GYJ6G0VHKQN
title: Service Level Agreements
type: article
cluster: software-architecture
status: active
date: '2026-05-15'
tags:
- slo
- sla
- sli
- sre
- reliability
- math
summary: Rigorous definition of SLIs, SLOs, and SLAs with the underlying mathematics of availability, error budgets, and burn rate calculations.
auto-generated: false
---

# Service Level Agreements (SLI / SLO / SLA)

Reliability is the most important feature of any system. To manage it, we use a tiered framework of indicators, objectives, and agreements.

- **SLI (Service Level Indicator):** A quantitative measure of some aspect of the level of service that is provided (e.g., Latency, Throughput, Availability).
- **SLO (Service Level Objective):** A target value or range of values for a service level that is measured by an SLI (e.g., 99.9% of requests succeed).
- **SLA (Service Level Agreement):** A legal contract that defines what happens if the SLO is not met (e.g., financial credits to the customer).

## The Math of Availability

Availability is typically expressed in "nines." The difference between three nines and four nines is an order of magnitude in operational rigor.

### The "Nines" Table (30-Day Window)

| Availability % | Downtime per Month | Downtime per Year | Description |
|---|---|---|---|
| **99%** (Two) | 7.2 hours | 3.65 days | Standard for internal/non-critical tools. |
| **99.9%** (Three) | 43.8 minutes | 8.77 hours | Typical for high-quality SaaS products. |
| **99.95%** (3.5) | 21.9 minutes | 4.38 hours | Standard for critical enterprise services. |
| **99.99%** (Four) | 4.38 minutes | 52.6 minutes | "Gold Standard" — requires full automation. |
| **99.999%** (Five) | 26.3 seconds | 5.26 minutes | Global infrastructure (Carrier Grade). |

### Availability Calculation Formula
$$Availability = \frac{\text{Total Time} - \text{Downtime}}{\text{Total Time}} \times 100$$## Error Budgets: The Discipline of Risk

An Error Budget is the amount of unreliability you are willing to tolerate in a given window. It is the bridge between Product (feature velocity) and Engineering (reliability).

### Calculation
For a 99.9% SLO over a 30-day window:
-   **Total Requests:**$1,000,000$-   **Allowed Failures:**$1,000,000 \times (1 - 0.999) = 1,000$If you have used 800 failures, you have **20% of your error budget remaining**.

## Burn Rate: The Proactive Signal

Burn rate is how fast you are consuming your error budget relative to the time window. It is the primary signal used for SRE paging.

### Burn Rate Formula$$\text{Burn Rate} = \frac{\frac{\text{Budget Consumed}}{\text{Time Window Consumed}}}{\frac{\text{Total Budget}}{\text{Total Time Window}}}$$
-   **Burn Rate = 1:** You will consume exactly your entire budget by the end of the window.
-   **Burn Rate > 1:** You will violate your SLO unless you take action.
-   **Burn Rate = 14.4:** You will consume 100% of your monthly budget in 2 days (48 hours). This usually triggers a **Critical Page**.

## Implementing SLOs

### 1. Identify "Golden Signals"
-   **Latency:** Time it takes to service a request.
-   **Traffic:** Demand placed on the system.
-   **Errors:** Rate of requests that fail.
-   **Saturation:** How "full" the service is (e.g., CPU/Memory).

### 2. Define the Window
A **28-day rolling window** is often preferred over a calendar month to ensure that "bad Tuesdays" are always compared against the same day of the week, and it avoids the 28/30/31 day math variance.

### 3. Set Alerting Thresholds
-   **Fast Burn:** Consume 2% of budget in 1 hour (Page).
-   **Slow Burn:** Consume 5% of budget in 24 hours (Ticket/Slack).

## Further Reading
- [SiteReliabilityEngineering](SiteReliabilityEngineering)
- [IncidentManagement](IncidentManagement)
- [MonitoringAndAlerting](MonitoringAndAlerting)
- [ObservabilityDesign](ObservabilityDesign)

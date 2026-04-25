---
canonical_id: 01KQ12YDWJ5RQ66GYJ6G0VHKQN
title: Service Level Agreements
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- slo
- sla
- sli
- sre
- reliability
summary: SLI / SLO / SLA in practice — picking measurable indicators, setting
  realistic targets, and the error-budget mechanics that make reliability work
  conserve resources rather than burn them.
related:
- BlamelessPostMortems
- IncidentResponse
- ChaosEngineering
- DistributedTracing
hubs:
- SoftwareArchitecture Hub
---
# Service Level Agreements (SLI / SLO / SLA)

The terms are routinely conflated; they aren't the same:

- **SLI (Service Level Indicator).** A measured metric — "fraction of HTTP requests with status < 500."
- **SLO (Service Level Objective).** An internal target on the SLI — "99.9% of requests succeed over a 30-day window."
- **SLA (Service Level Agreement).** A *contractual* commitment to a customer with consequences (refund, credit) for violation. Almost always weaker than the internal SLO.

Most teams need SLOs and rarely SLAs. The discipline is identical; the difference is who you owe the promise to.

## The mental model

SLO discipline is about **error budgets**. If your SLO is 99.9% availability, your error budget is 0.1% — about 43 minutes per month. As long as you have budget left, you can take risks: ship faster, deploy on Fridays, run experiments. When you've burned the budget, you can't: focus on reliability work until you earn the budget back.

This frames the eternal tension between feature work and reliability work as a measurable trade-off. It also keeps reliability investment proportional to actual reliability needs — you don't over-invest in five nines for a service that's fine at three.

## Picking SLIs

Good SLIs:

- **Measure the user's experience.** "Did the user's request succeed quickly?" Not "is the database CPU below 70%."
- **Are observable from the right vantage point.** External (synthetic checks from outside) for customer-facing claims; internal (server-side logs) for tighter loops.
- **Are aggregations of events.** Per-request success or failure, per-query latency. Aggregate to "fraction good over window."
- **Are dimensional.** Per-endpoint, per-region, per-tenant. Average latency hides bimodal pain.

Two canonical SLI types:

- **Availability** — `(good requests) / (valid requests)`. "Good" is your definition: HTTP < 500, no app-level error, latency below threshold.
- **Latency** — `(requests faster than X) / (valid requests)`. "X" is what your user notices.

For most user-facing services, you want both. Availability says "did it work"; latency says "did it feel fast."

## Setting SLOs

Common mistake: aspirational targets ("we want five nines") with no path to deliver. The result is constant SLO violation, the team ignores the alerts, and the SLO stops working as a control.

Better process:

1. **Measure current performance for 4-8 weeks.** What's your actual availability and latency distribution?
2. **Set the SLO at or slightly above current.** If you're at 99.92% availability, SLO is 99.9%. Don't promise what you don't deliver.
3. **Tighten over time.** Once the team is comfortably meeting it, tighten. Don't tighten faster than you can deliver reliability work.

For B2B SaaS, common targets:

- **Critical user-facing endpoints**: 99.9% availability, 95% requests below 500ms.
- **Internal services**: 99.5% availability often fine.
- **Background processing**: time-based "complete within 5 minutes of submission" SLOs work better than availability.

For consumer-grade services, 99.9% is common; "five nines" (99.999%, 5 minutes/year) is for genuinely critical infrastructure (telecom, payment networks, life-safety).

## Error budget mechanics

Error budget = `(1 - SLO) × time window`.

For 99.9% over 30 days: 0.001 × 30 × 24 × 60 = ~43 minutes of "downtime equivalent" allowed.

Practical use:

- **Budget burn rate alerts.** If the last hour burned 5% of the monthly budget, that's a fire — pages someone immediately. If the last 24 hours burned 10%, that's a slow-burn alert.
- **Release gates.** If the budget is exhausted, deploys to production stop until the budget recovers (or until an explicit override). Forces reliability work after a bad month.
- **Risk tolerance.** With budget remaining, the team can take controlled risks (run a new experiment, ship a Friday release). With no budget, they can't.

Teams that adopt error budgets seriously report more honest conversations between product and engineering. "We can't ship feature X this week because we're 80% through the error budget" beats vague "the system is fragile right now."

## SLA-specific concerns

When the customer can sue you:

- **The contractual SLA must be looser than your internal SLO.** If you SLA 99.9% but internally aim for 99.95%, you have a 0.05% safety margin.
- **The measurement methodology in the contract matters.** "Calculated over 30-day rolling window, excluding scheduled maintenance windows" — define every term. Customers will read it carefully.
- **Service credits, not refunds.** Most SLAs offer service credits (extension of subscription) rather than cash refunds. Cheaper to honour.
- **Excluded events.** "Force majeure," "customer's own negligence," "third-party dependencies you don't control." All standard.

For internal SLOs, you control the definition; for SLAs, lawyers do, and the relationship between the two becomes a coordination problem.

## What to instrument

To compute SLIs, you need:

- **Request-level logs** with status, latency, endpoint, region, tenant, timestamp.
- **A streaming metric pipeline** that can compute `count_good / count_valid` over rolling windows.
- **Dashboards** showing SLI vs SLO in real time.
- **Alerting** on burn rate (not just instantaneous SLI dips).

Tools:

- **Prometheus + Grafana** — for the metric layer and dashboards. SLO-specific dashboards via grafana-dashboards or sloth.
- **OpenSLO** — an open spec for defining SLOs as code; growing tool ecosystem.
- **Pyrra, Sloth, Nobl9** — open source / commercial tools for managing SLOs.
- **Cloud provider SLO products** (Google Cloud Monitoring SLOs, Datadog SLOs).

Most teams build the SLI computation in their existing observability stack. Specialised tools become useful past ~10 SLOs to manage; below that, dashboards and alert rules are enough.

## Common failure modes

**SLO never updated.** The service grew; the SLO didn't. The team is constantly above target with no signal. Tighten when you can.

**SLO too tight.** Constant violations; alerts ignored; the SLO loses its meaning. Loosen until it's achievable, then tighten incrementally.

**No alert on burn rate, only on instantaneous SLI.** A 5-minute outage doesn't blow the monthly SLO; alerts don't fire; team thinks everything's fine. Burn-rate alerts catch this.

**Average latency as SLI.** Hides the bimodal distribution where 95% are fast and 5% are catastrophic. Use percentiles (p95, p99) or the "fraction below threshold" form.

**SLOs without ownership.** No one feels responsible for the SLO; missing it has no consequence; the SLO drifts to whatever the system happens to deliver. Each SLO has an owning team.

**SLAs without internal SLOs.** Contractual promises with no internal mechanism to keep them. Eventually you violate the SLA and discover this.

## When SLOs are overkill

- **Pre-product-market-fit.** When the product is changing weekly, SLO discipline is overhead. Ship; learn; come back to reliability.
- **Internal tools with no users.** A nightly batch job nobody depends on doesn't need an SLO.
- **Tiny teams.** A 3-person team running 5 services doesn't need 25 SLOs. Focus on the user-facing critical paths.

For mature teams running production services that customers care about, SLO discipline is non-negotiable. It's the framework that lets you reason about reliability quantitatively instead of by gut.

## Further reading

- [BlamelessPostMortems] — the loop that closes after SLO violations
- [IncidentResponse] — what triggers when burn-rate alerts fire
- [ChaosEngineering] — proactive verification of reliability claims
- [DistributedTracing] — the data feeding SLI computation

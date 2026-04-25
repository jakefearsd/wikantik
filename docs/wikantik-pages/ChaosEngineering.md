---
canonical_id: 01KQ12YDSXRNMGVVBHNMP885XP
title: Chaos Engineering
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- chaos-engineering
- reliability
- resilience-testing
- disaster-recovery
summary: Practical chaos engineering — what to break, when to break it, and the
  pre-conditions that distinguish a useful experiment from a self-inflicted
  outage.
related:
- BlamelessPostMortems
- ServiceLevelAgreements
- IncidentResponse
- DistributedTracing
hubs:
- SoftwareArchitecture Hub
---
# Chaos Engineering

The premise: you don't actually know your system is reliable until you break it on purpose. You can read the architecture document, you can stare at the dependency graph, but the only honest way to verify "this fails over within 30 seconds" is to fail it over and time it.

Most teams hear "chaos engineering" and imagine Netflix's Chaos Monkey randomly killing prod EC2 instances. That's the headline; the discipline behind it is more careful, more boring, and worth doing.

## What it is and isn't

It is:

- **Hypothesis-driven experiments** that test specific failure responses ("when service X is unavailable, the user-facing API should respond within 500ms with a degraded result").
- **Measured outcomes**, with clear success and abort criteria.
- **Run in a controlled blast radius**, ideally not in production until you've earned trust.

It isn't:

- Random destruction. "Let's see what happens" is not an experiment; it's a tantrum.
- A substitute for actual reliability work. Breaking things doesn't fix things; it surfaces what needs fixing.
- Required for every system. If your team has 3 engineers and a single-region monolith, basic load testing covers what chaos engineering would.

## Pre-conditions you actually need

Don't run chaos experiments until:

- **You have observability.** If you can't tell the difference between "system handled the failure" and "system silently dropped 2% of requests," chaos experiments produce no signal.
- **You have on-call.** Experiments that produce alerts produce alerts; someone needs to be ready to respond.
- **You have an SLO.** Experiments need a success criterion; SLOs are usually it. ("Did latency exceed our 99th-percentile target?")
- **You have rollback.** If the experiment goes wrong, you need a one-button "stop the chaos" mechanism. Faster than figuring out which knob to turn during the incident the experiment caused.

A team that can't articulate its SLO has nothing to learn from chaos experiments. Build that first.

## A useful experiment, structured

Every chaos experiment should be written down with this structure before you run it:

```
Hypothesis: When the recommendations service returns 5xx for 100% of requests,
            the homepage will continue to render with no recommendations within
            500ms p95, error rate < 1%.

Method:     Inject HTTP 503 responses for all recommendations.api/v1/* calls.

Blast radius: 10% of traffic, region us-east-1 only, between 14:00 and 16:00 UTC,
              for 5 minutes maximum.

Abort if:   - Homepage error rate > 5%
            - Latency p95 > 2s
            - Any related downstream service alerts page
            - On-call requests abort

Owner: ...
Approver: ...
```

If you can't fill in any of those fields, you're not ready to run the experiment.

## What to break, in priority order

1. **Single-instance failure.** Kill one pod / instance / process. Does the service continue? In Kubernetes-land, this is what `kubectl delete pod` should do uneventfully if your deployment is properly configured.
2. **Single-AZ failure.** Lose all instances in one availability zone. Does the cluster heal?
3. **Dependency failure.** A downstream service returns 5xx, hangs, returns slow. Does your service degrade gracefully?
4. **Network slow.** Add 200ms of latency between services. Do timeouts kick in correctly? Do retries amplify the problem?
5. **Disk full / out of memory.** Bring a process to OOM. Does the orchestrator restart it? Does the load balancer notice?
6. **Region failure.** The big one. If your DR plan is "fail over to another region," prove the plan works.

Most teams stop at #1 or #2. The interesting bugs live at #3 and beyond.

## Tools

- **Chaos Mesh** — Kubernetes-native chaos. Pod kills, network delays, IO faults, time skews. Works without modifying the application.
- **AWS Fault Injection Simulator** — managed chaos for AWS resources, integrated with their alarms.
- **Gremlin** — commercial, broad scope, polished UX.
- **LitmusChaos** — CNCF, Kubernetes-focused, more granular than Chaos Mesh in some ways.
- **Toxiproxy** — TCP-layer fault injection, good for development and integration tests.
- **Pumba** — simple Docker-based chaos tool.

For most teams, Chaos Mesh + a handful of bash scripts is enough. Avoid commercial tools until you've outgrown the open-source options, which most teams won't.

## The continuous-chaos hypothesis

Run small chaos experiments continuously rather than rare large ones. Monthly "kill an instance" is more useful than yearly "fail over a region" because:

- Small frequent experiments catch regressions when something breaks (a new pod misconfiguration, a new dependency that doesn't fail over correctly).
- Engineers stay calibrated; the system stays known.
- The experiment results compose: a region failover that works is partly a series of instance failures that all work.

Production chaos every weekday lunchtime, blast radius 1% of traffic, with automatic abort: the kind of thing that catches more reliability issues than any other practice short of running the actual disaster.

## What the experiments teach you

Categories of finding:

- **Configuration errors.** Most experiments fail because something was misconfigured (timeout too long, retries set wrong, alerts pointed at the wrong inbox). These fixes are cheap once you've found them.
- **Cascading dependencies.** "When X is slow, Y eats memory because retries pile up, then Z dies." Surfaces architecture-level issues that are otherwise invisible.
- **Documentation drift.** The runbook says the alert page goes here. The alert actually goes there. Found out the easy way.
- **People issues.** No one knows what to do when alarm A fires. The chaos experiment surfaces this; you fix the runbook.

After every experiment, hold a small post-mortem. Record what you learned. Most experiments produce a small but real fix. The cumulative effect is large.

## Anti-patterns

- **Random chaos in production with no plan.** Hostile to your colleagues; produces incidents, not learning.
- **Experiments without abort criteria.** "We'll just see what happens" — the experiment becomes the incident.
- **No rollback.** "How do we stop the chaos?" "I don't know" → real outage.
- **Fixing without retesting.** A chaos experiment surfaced a bug; you fixed it; you didn't run the experiment again to verify the fix. Verify.
- **One-off experiments.** Run once, never again. The system changes; the experiment's findings stale.

## The reliability dividend

Teams that run chaos engineering well report fewer real incidents (because failures are anticipated and handled gracefully) and shorter MTTR when incidents do happen (because operators have practiced the response). The Netflix data here is anecdotal but consistent across many other organisations.

It is not, however, a free lunch. Chaos engineering takes a meaningful fraction of an SRE-equivalent's time. Budget for it. The ROI is good but the input is real.

## Further reading

- [BlamelessPostMortems] — how to actually learn from incidents
- [ServiceLevelAgreements] — SLO discipline; precondition for useful experiments
- [IncidentResponse] — the muscle chaos engineering builds
- [DistributedTracing] — observability that makes experiments interpretable

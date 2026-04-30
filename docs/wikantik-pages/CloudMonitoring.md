---
canonical_id: 01KQ0P44NFST15NVWTXRXWPJM7
title: Cloud Monitoring
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: How to monitor cloud workloads — metrics, logs, traces, alarms — and the
  trade-offs between native cloud tools (CloudWatch) and dedicated observability platforms
  (Datadog, New Relic, Grafana stack).
tags:
- monitoring
- observability
- cloudwatch
- metrics
- logs
- traces
related:
- DevOpsAndSreHub
- AwsFundamentals
- AwsLambdaPatterns
- StatusPageBestPractices
hubs:
- CloudPlatformsHub
---
# Cloud Monitoring

Cloud workloads need monitoring beyond traditional server metrics. The combination of managed services, ephemeral infrastructure, and distributed systems makes "is the server up?" insufficient.

Modern monitoring uses three pillars: metrics, logs, traces. Plus alarming on top. This page covers what to instrument and how to choose tooling.

## The three pillars

### Metrics

Numerical measurements over time: request rate, error rate, latency, CPU, memory, queue depth.

Time-series databases (CloudWatch, Prometheus, Datadog) store metrics. Dashboards visualize. Alarms fire on threshold crossings.

For cloud workloads, monitor at multiple layers:
- **Infrastructure**: CPU, memory, disk, network on instances
- **Service**: request rate, error rate, latency on endpoints
- **Business**: orders/minute, revenue/hour, signups/day

The four golden signals (Google SRE):
1. Latency
2. Traffic (requests/sec)
3. Errors (error rate)
4. Saturation (resource utilization)

If you have these for each service, you cover most operational concerns.

### Logs

Discrete events with detail. Application logs, access logs, error logs.

Modern logs are structured (JSON) so they can be queried:

```json
{
    "timestamp": "2026-04-26T12:00:00Z",
    "level": "ERROR",
    "service": "orders",
    "request_id": "abc123",
    "user_id": "u456",
    "message": "Order validation failed",
    "error": "amount must be positive"
}
```

Log aggregators (CloudWatch Logs Insights, Datadog Logs, ELK stack) index and query at scale. Without aggregation, logs across many instances are unmanageable.

### Traces

Records of requests across multiple services. Each service contributes spans; the trace is the assembled tree.

Distributed tracing tools (AWS X-Ray, Datadog APM, Jaeger, OpenTelemetry) link spans across services. Essential for debugging in microservices.

A trace shows:
- Total request time
- Time per service
- Time per database call
- Time per external API call
- Errors with stack traces

For services that span multiple components, traces are the tool that makes debugging tractable.

## Alarming

Alarms convert metrics to notifications. The hard part: making alarms actionable, not noisy.

Good alarms:
- Page only when human action is needed
- Have a clear runbook
- Are set on the symptom, not the cause
- Wake people up only for genuinely urgent issues

Bad alarms:
- Fire constantly on routine variation
- Page on metrics nobody understands
- Alert on causes ("disk full") instead of symptoms ("requests failing")
- Wake people up for non-urgent issues

Alarm fatigue is real; teams ignore alarms that fire too often. Tune aggressively.

## CloudWatch (the AWS native)

CloudWatch covers metrics, logs, alarms, dashboards, basic tracing (X-Ray).

Pros:
- Native AWS integration
- No external dependencies
- Pay-per-use

Cons:
- Less powerful query capabilities than dedicated platforms
- Multi-cloud awkward
- UI/UX is dated

For pure-AWS workloads, CloudWatch covers a lot. For multi-cloud or sophisticated needs, dedicated platforms are better.

## Dedicated observability platforms

### Datadog

The premium option. Comprehensive: metrics, logs, traces, RUM, security. Excellent UX. Expensive.

Use when you have the budget and need the breadth of features.

### Grafana stack (Loki, Tempo, Prometheus)

Open-source stack for self-hosting. Loki for logs, Tempo for traces, Prometheus for metrics, Grafana for visualization.

Use when self-hosting is feasible and budget is constrained.

### New Relic, Dynatrace, others

Mature alternatives to Datadog. Each has different strengths; evaluate based on specific needs.

## OpenTelemetry

OpenTelemetry (OTel) is the emerging standard for instrumentation. Vendor-neutral SDKs and protocols.

The shift: instrument code with OTel; send to any compatible backend (Datadog, Grafana, Honeycomb, etc.). Switching backends doesn't require re-instrumenting.

For new projects, instrument with OTel from day one.

## Cost management

Monitoring costs grow with:
- Custom metrics (per metric per hour)
- Log volume (per GB ingested + per GB stored)
- Trace samples
- Dashboards and alarms

At scale, monitoring can be 5-15% of cloud spend. Manage by:

- **Sampling**: don't trace 100% of requests; sample
- **Log levels**: don't INFO-log every operation in production
- **Metric cardinality**: high-cardinality dimensions multiply cost
- **Retention**: shorter for verbose logs

## Common failure patterns

- **No monitoring at all.** Production failures are surprises.
- **Monitoring everything; nothing actionable.** Dashboards full of metrics nobody acts on.
- **Alarm noise.** Alarms get ignored; real incidents missed.
- **Metric without context.** Latency went up — what changed? Need correlation across signals.
- **Logs without structure.** Can't query effectively.
- **No traces in distributed systems.** Debugging across services is guesswork.
- **High retention without need.** Pay for logs nobody reads after a week.

## A reasonable starter

For a new cloud workload:

1. Four golden signals on every service
2. Structured logs from day one
3. Distributed tracing (OpenTelemetry → CloudWatch or Datadog)
4. Dashboards covering golden signals + key business metrics
5. Alarms on symptoms (error rate, latency p99) only
6. Runbooks for each alarm

Skip the rest until needed.

## Further Reading

- [DevOps and SRE Hub](DevOpsAndSreHub) — Operating cloud workloads
- [AwsFundamentals](AwsFundamentals) — AWS context
- [AwsLambdaPatterns](AwsLambdaPatterns) — Lambda-specific monitoring
- [StatusPageBestPractices](StatusPageBestPractices) — User-facing status
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index

---
canonical_id: 01KQ12YDTP6JTFQEKQZT19ACQZ
title: Distributed Tracing
type: article
cluster: observability
status: active
date: '2026-04-25'
tags:
- observability
- tracing
- opentelemetry
- jaeger
- microservices
summary: How distributed tracing actually works (W3C trace context, spans, sampling),
  what to instrument, and when the trace tells you what no log or metric can.
related:
- AgentObservability
- ServiceLevelAgreements
- BlamelessPostMortems
- MicroservicesArchitecture
hubs:
- Observability Hub
---
# Distributed Tracing

A trace is the story of a single request as it crosses every service, queue, database, and external API in your system. Each hop is a span, with a start time, end time, attributes, and a parent. The collection of spans for one request is the trace.

Without traces, debugging a "the user-facing request was slow" complaint in a distributed system is detective work over logs from N services. With traces, you click the trace ID and see the timeline.

## How it works

Three pieces:

1. **Trace context propagation.** A header (`traceparent`) carries the trace ID and parent span ID across every call. Standard format: W3C Trace Context. Every service receiving a request reads it; every outgoing call passes it forward.
2. **Span emission.** Each service creates spans for the work it does — an HTTP handler, a database query, a tool call. Spans get exported to a collector.
3. **Trace assembly.** The collector receives spans from all services, joins them by trace ID, builds the timeline, makes it queryable.

The standard for instrumentation in 2026 is **OpenTelemetry** (OTel). Almost every major language has an OTel SDK; almost every major framework has auto-instrumentation. Use it. Don't reinvent.

## The W3C trace context, briefly

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             ^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^
             ver trace-id (32 hex)             span-id (16 hex)  flags
```

`trace-id` is unique per request. Every span emitted for that request carries the trace ID. Span IDs link parent-child relationships within the trace.

A `tracestate` header carries vendor-specific extensions; you don't usually have to touch it.

Every HTTP, gRPC, and message-queue call between your services should propagate `traceparent`. The frameworks handle this if you instrument with OTel; the bug surfaces when you have a hand-rolled HTTP client that drops headers.

## What to instrument

The default OTel instrumentation gives you HTTP servers, HTTP clients, popular DB clients, popular message brokers. Out of the box, that catches most of what you need.

Add manual spans for:

- **Business operations** that cross multiple framework calls. "Process order" should be a span containing the DB and external-API spans.
- **Background work** triggered by a request. The async job should continue the trace if it's logically the same request.
- **Loops over external resources.** A loop calling N external services should produce N child spans, not one merged span.
- **Anything that takes meaningful time and isn't already spanned.** ML inference, large in-memory computation.

Don't instrument every function call. Spans cost storage, query time, and human attention. Aim for ~10–50 spans per request as a healthy density.

## Span attributes that matter

A span without attributes is a duration with a name. Useful attributes:

- **Inputs** — request parameters, query parameters (sanitised), key IDs.
- **Outputs** — response status, row count, result hash.
- **Errors** — error type and message; mark the span as errored.
- **Backend identity** — DB instance, table name, queue name, service version.
- **Business context** — user ID, account ID, tenant ID. Crucial for "show me all traces for user X."

Avoid attributes with high cardinality you don't query (full request bodies, raw SQL with literals). They balloon storage cost.

## Sampling: the cost lever

A trace with 30 spans at 10kB per span = 300kB. At 1000 RPS, that's 26 TB/day of trace data. Most teams sample.

Two main strategies:

- **Head-based sampling.** Decide at request entry whether to trace. Coin flip; 1% is typical. Predictable cost. Misses interesting low-rate failures because you decide before knowing the request was interesting.
- **Tail-based sampling.** Buffer all spans for a trace; decide whether to keep based on outcome. Always keep errors and slow requests; sample successes at 1%. Better signal; more operational complexity (the collector needs RAM to buffer; OTel Collector with the tail-sampling processor handles this).

Tail-based sampling is the right answer for most production systems. The collector sees every span anyway; throwing away the boring ones at the egress is cheap.

## Backends

| Backend | Strengths | When to pick |
|---|---|---|
| **Jaeger** | Open source, simple to deploy, good UI | Self-hosted, simple stacks |
| **Tempo (Grafana)** | Object-storage backend, cheap at scale, Grafana UI | Already on Grafana stack |
| **Honeycomb** | Best UI for trace exploration, fast filtering | Commercial, willing to pay; debugging-heavy teams |
| **Datadog APM** | Full APM suite; tight integration with their other products | Already on Datadog |
| **Elastic APM** | If you're on the Elastic stack already | Elastic users |
| **AWS X-Ray** | AWS-native | All-in on AWS |

OpenTelemetry exports to all of the above. Pick based on your existing observability stack and preferences; the data format is portable.

## Reading a trace, in practice

A typical reading flow:

1. **A user reports slow response or an error.** Find their trace by user ID + timestamp.
2. **Open the timeline view.** Spans are bars; gaps between bars are unaccounted-for time.
3. **Find the long span.** "DB query took 4s of the 5s response." Click in.
4. **See the SQL.** Or the external API URL. Or the lock-wait in the database.
5. **Correlate with logs and metrics.** Trace gives you the where; logs give you the why.

The most common discoveries:

- **Sequential calls that should be parallel.** N round-trips in series add up; one parallel batch saves time.
- **Surprising downstream calls.** "I didn't know my service called external API X." Often: an SDK calling home or a stale dependency.
- **Long DB queries.** N+1, missing index, lock wait.
- **Network spikes.** Slow upstream service.

## Errors and traces

A span has a `status` (ok or error) and optional error details. When an exception escapes, the framework should mark the span as errored automatically. Manual instrumentation should mark it explicitly.

Error trace queries are how you find the next bug to fix:

- "Show me all traces from the last hour where any span errored, grouped by error type."
- "Show me traces where the root span succeeded but a child span errored." (Silent failures.)

Tail-based sampling that always keeps errors makes these queries reliable.

## Cost control

Beyond sampling:

- **Drop noisy spans.** Healthchecks, probes, internal metrics scrapes. Filter at the collector.
- **Trim attributes.** Long string attributes (full SQL with parameters, full request bodies) blow up storage. Truncate or hash.
- **Tier storage.** Recent traces in hot storage; older traces in object storage. Tempo and Honeycomb handle this; managed services charge you accordingly.

Budget for traces explicitly; some teams spend more on traces than on the application infrastructure.

## When tracing is overkill

A small monolith doesn't need distributed tracing. Application-level profiling (e.g. `py-spy`, Chrome DevTools, language-native profilers) tells you the same things faster. Reach for distributed tracing when you have multiple services.

A short-lived agent loop (see [AgentObservability]) needs the same kind of tracing but with LLM-call and tool-call semantics. The mechanics carry over; the attribute conventions are different.

## A starter setup

For a team starting today:

```
- Adopt OpenTelemetry SDK in every language you use
- Auto-instrument HTTP and DB
- Add manual spans for business operations
- Run OTel Collector with tail-based sampling
- Send to Tempo (self-hosted) or Honeycomb (commercial)
- Add user_id, tenant_id, correlation_id as attributes on root spans
- Wire trace IDs into your structured logs (so you can jump from log to trace)
```

A week of work; permanent debugging dividend.

## Further reading

- [AgentObservability] — LLM-specific extension of these patterns
- [ServiceLevelAgreements] — SLO discipline traces help measure
- [BlamelessPostMortems] — traces fuel post-incident analysis
- [MicroservicesArchitecture] — the architecture style that makes tracing essential

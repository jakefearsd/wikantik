---
canonical_id: 01KQ0P44WAAH701D2MEHT1602C
title: Serverless Architecture
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: Where serverless fits and where it doesn't — the cold-start problem, the
  cost model that flips at scale, the operational story, and the architectures that
  serverless does well vs. those it doesn't.
tags:
- serverless
- lambda
- cloud-native
- functions
related:
- AwsLambdaPatterns
- AwsFundamentals
- CloudNativeApplicationDesign
- CloudMonitoring
hubs:
- CloudPlatforms Hub
---
# Serverless Architecture

Serverless — function-as-a-service plus managed everything-else — sounds like the future. In practice, it's a tool that fits some workloads beautifully and others poorly. The marketing pitches "no servers to manage"; the reality includes specific operational complexity that traditional architectures don't have.

This page is about when serverless wins, when it loses, and the architectures that have actually shipped successfully.

## The serverless model

The core idea: code runs only when invoked. No idle compute cost; the platform manages everything below the function.

The major implementations:
- **AWS Lambda**: the dominant FaaS
- **Cloudflare Workers**: edge-first; V8 isolates instead of containers
- **Google Cloud Functions / Cloud Run**: GCP equivalents
- **Azure Functions**: Azure equivalent
- **Vercel / Netlify Functions**: developer-focused; built on AWS Lambda underneath

## Where serverless wins

### Sporadic or bursty workloads

Functions that run infrequently or with high variance. A scheduled job that runs once an hour. An API endpoint that gets 100 requests/day. The cost of dedicated compute for these is wasted; serverless pays only for actual invocations.

### Event processing

S3 upload triggers a thumbnail generator. SQS message triggers an order processor. Webhooks trigger a notification. The event-driven model fits serverless naturally.

### Glue code

Connecting other cloud services. Read from one, transform, write to another. Serverless functions are perfect for these "no-server-needed" connectors.

### Quick prototyping

Stand up an HTTP endpoint without provisioning servers. Useful for experiments, internal tools, MVP backends.

## Where serverless loses

### Steady high-throughput workloads

A service that handles 10,000 requests/second consistently. The per-invocation cost adds up fast. EC2 or container hosting is dramatically cheaper at sustained load.

### Long-running operations

Lambda has a 15-minute timeout. Workloads that need to run for hours don't fit. Step Functions can decompose long workflows but the serverless story for long compute is awkward.

### Latency-sensitive paths

Cold starts add 100ms-2s on infrequent invocations. For p99-sensitive APIs, this is unacceptable. Provisioned concurrency mitigates but at higher cost.

### Stateful workloads

Functions are stateless; state lives elsewhere. For workloads that benefit from in-memory caching or session state, serverless is awkward.

### Heavy dependencies

Large dependencies inflate cold-start time and deployment package size. Heavy frameworks fit poorly.

## The cold-start problem

When a function hasn't run recently, the platform needs to spin up a fresh execution environment. This adds latency:

- Init container: ~100ms-500ms
- Initialize runtime: ~50-200ms
- Initialize your code: depends on imports

Total cold start: typically 200ms-2s. For latency-sensitive paths, this matters.

Mitigations:
- **Provisioned concurrency** (AWS): keeps functions warm at extra cost
- **Smaller deployment packages**: faster init
- **Lazy initialization**: don't load everything at module level
- **Different runtime**: Go and Rust cold-start faster than JVM or .NET

For most use cases, cold starts are fine. For p99-critical APIs, evaluate carefully.

## The cost model

Serverless cost has two factors:

1. **Per-invocation**: small fixed cost per function call
2. **Per-millisecond × memory**: compute cost while running

A short, infrequent function: nearly free.
A long, frequent function: expensive.

The crossover is workload-dependent. A useful comparison:

```
Lambda: $0.20 per million invocations + memory*duration cost
EC2 t3.medium: ~$30/month
```

If you're getting <30M invocations/month, Lambda is probably cheaper. Above that, calculate carefully.

## Architectures that work

### API + Lambda + DynamoDB

A common pattern:
- API Gateway as HTTP entry
- Lambda handlers per endpoint
- DynamoDB for storage

Fully serverless. Scales automatically. Pay-per-use. Good fit for low-to-medium traffic APIs.

### Event-driven processing

S3 → Lambda → DynamoDB. SQS → Lambda → external API. The "every event triggers a small function" pattern.

### Step Functions for orchestration

For workflows that span multiple Lambdas, Step Functions provides state-machine orchestration. Long workflows decomposed into short Lambda invocations.

### Lambda + RDS (with caveats)

Lambda + RDS hits connection-pool issues. Lambda invocations spike concurrent connections; RDS Proxy or careful pool sizing is required.

### Provisioned vs. on-demand

For p99-sensitive paths, provisioned concurrency. For everything else, on-demand. Mix is fine.

## Architectures that don't work

- **Lambda for steady high-traffic APIs.** Move to ECS/Fargate or EKS.
- **Lambda calling Lambda calling Lambda.** Latency stacks; cost stacks; debugging is harder.
- **Heavy initialization on every cold start.** Death by 1000 cuts.
- **Stateful Lambdas via /tmp.** /tmp persists between invocations on the same container but isn't reliable; use external storage.

## Common failure patterns

- **Treating Lambda as a microservice.** Functions are smaller than services; granularity matters.
- **Ignoring cold starts in latency budget.** Surprise p99 issues.
- **No structured logging or tracing.** Debugging serverless requires good observability from day one.
- **Vendor lock-in surprises.** Lambda code is portable in theory; in practice, it depends on AWS-specific services.
- **Over-decomposing.** 50 tiny functions for what one service would do well.

## Further Reading

- [AwsLambdaPatterns](AwsLambdaPatterns) — Lambda specifics
- [AwsFundamentals](AwsFundamentals) — Service context
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — Broader pattern
- [CloudMonitoring](CloudMonitoring) — Observability for serverless
- [CloudPlatforms Hub](CloudPlatforms+Hub) — Cluster index

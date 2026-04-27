---
canonical_id: 01KQ0P44M78CMR11YT041AD48Y
title: AWS Lambda Patterns
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: Lambda design patterns that work — handler structure, cold starts, layers,
  environment variables, dead-letter queues, and the operational patterns for production
  Lambda code.
tags:
- aws-lambda
- serverless
- functions
- cloud
related:
- ServerlessArchitecture
- AwsFundamentals
- CloudMonitoring
- IdempotencyPatterns
hubs:
- CloudPlatforms Hub
---
# AWS Lambda Patterns

Lambda is AWS's flagship serverless compute. Beyond "deploy a function," real production Lambda usage has its own patterns — for cold starts, dependencies, configuration, error handling, and observability.

This page is about the patterns that hold up in production.

## Handler structure

Lambda invokes a specific handler function:

```python
def handler(event, context):
    # event: input data (HTTP request, S3 event, etc.)
    # context: Lambda runtime info
    return { "statusCode": 200, "body": "ok" }
```

The shape varies by language and trigger source. Key patterns:

### Fast cold starts

Anything at module-level runs once per cold start. Heavy initialization (database connections, ML models) at module level costs cold-start time but warms the function for subsequent invocations.

The trade-off: lazy initialization (in handler) makes cold starts faster but every cold start pays the cost.

```python
# Module-level: runs once per cold start
db = initialize_db_connection()

def handler(event, context):
    # Reuses db across warm invocations
    return process(db, event)
```

For DB connections specifically, Lambda's connection model is awkward — see "RDS Proxy" below.

### Configuration via environment variables

```python
import os
TABLE_NAME = os.environ['TABLE_NAME']
```

Set environment variables in the Lambda configuration. Different per environment (dev/staging/prod). Don't put secrets here directly; reference Secrets Manager or Parameter Store instead.

### Idempotency

Lambda invocations can be retried. Same SQS message delivered twice. Same SNS notification multiple times.

Always design Lambda handlers to be idempotent. Use idempotency keys, deduplication logic, or idempotent target operations (UPSERTs instead of INSERTs).

See [IdempotencyPatterns](IdempotencyPatterns).

## Triggers

Lambda is invoked by various sources:

- **API Gateway**: HTTP requests
- **Application Load Balancer**: HTTP via ALB
- **S3**: object created/deleted
- **DynamoDB Streams**: table changes
- **SQS**: messages
- **SNS**: topic publications
- **EventBridge**: scheduled or event-driven
- **CloudWatch Events**: cron-like schedules
- **Direct invocation**: from other code

Each trigger has a different event shape. Code your handler to match.

## Memory and timeout

Memory: 128 MB - 10 GB. CPU scales linearly with memory; bigger Lambdas are faster, but cost more per ms.

Timeout: max 15 minutes. Tune for your workload.

For most workloads, 256-1024 MB is the sweet spot. Increase memory if CPU-bound; decrease if just orchestrating I/O.

## Layers

Lambda layers package shared code/dependencies separately from handler code:

```
Function code (small)
  + Layer 1 (shared dependencies)
  + Layer 2 (more shared deps)
```

Useful for:
- Common dependencies across many Lambdas
- Native libraries that are platform-specific
- Reducing deployment package size

Trade-off: layers add complexity; for one-off functions, just include the code in the deployment package.

## Lambda + RDS

Connection pooling is hard with Lambda:
- Each warm Lambda holds connections
- Concurrent invocations multiply connections
- RDS connection limits get hit fast

Solutions:
- **RDS Proxy**: AWS-managed pool that Lambdas connect through
- **Limit Lambda concurrency**: cap reserved concurrency
- **Use DynamoDB instead** if access patterns allow

For production Lambda + RDS, RDS Proxy is essentially mandatory.

## Error handling

Lambda errors trigger automatic retries (for some triggers):
- **Async invocations**: 2 retries
- **SQS**: per queue retry policy
- **API Gateway**: depends on configuration

After retries are exhausted, the message goes to:
- **Dead-letter queue**: for failed async invocations
- **DLQ on the source**: for SQS failures
- **Caller error**: for API Gateway

Always configure DLQs. Without them, failed invocations disappear silently.

## Observability

CloudWatch is the default:

- **CloudWatch Logs**: every Lambda invocation logs
- **CloudWatch Metrics**: invocations, errors, duration, throttles
- **X-Ray**: distributed tracing (opt-in)

Enable X-Ray for non-trivial Lambdas. The trace shows time spent in your code vs. AWS service calls vs. cold start.

Structured JSON logs let CloudWatch Insights query them effectively. Avoid plain-text logs.

## Cost considerations

Per-invocation cost: $0.20 per million.
Per-ms cost: depends on memory.

Common cost surprises:
- High-memory functions invoked frequently
- Long-running functions (timeout cost adds up)
- API Gateway costs separate from Lambda
- CloudWatch Logs costs for high-volume logging

For workloads with steady high traffic, ECS/Fargate is usually cheaper than Lambda.

## Deployment patterns

### Single function per repository

Small, focused. Deployment via SAM, Serverless Framework, or AWS CDK.

### Many functions in one repository

Shared code, common deployment. Larger but easier to maintain consistency.

### Functions defined in Terraform/CloudFormation

For infrastructure-as-code shops. Lambda is just another resource.

For most teams, a deployment framework (SAM, Serverless Framework, CDK) over raw IaC is more productive for Lambda-heavy work.

## Common failure patterns

- **Heavy initialization in handler.** Move to module level for warm-invocation reuse.
- **No DLQ.** Failed invocations vanish.
- **Connection pool exhaustion.** Use RDS Proxy or DynamoDB.
- **Cold-start surprises in production.** Test cold-start latency.
- **Massive deployment packages.** Slow cold starts; use layers or trim.
- **No idempotency.** Retries cause duplicate side effects.
- **Lambda for everything.** Some workloads belong elsewhere.

## Further Reading

- [ServerlessArchitecture](ServerlessArchitecture) — Broader serverless context
- [AwsFundamentals](AwsFundamentals) — Lambda's place in AWS
- [CloudMonitoring](CloudMonitoring) — Observability
- [IdempotencyPatterns](IdempotencyPatterns) — Critical for retry-safe handlers
- [CloudPlatforms Hub](CloudPlatforms+Hub) — Cluster index

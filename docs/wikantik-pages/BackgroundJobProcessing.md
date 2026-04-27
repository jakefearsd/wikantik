---
canonical_id: 01KQ0P44M8PKP617G8864N6T46
title: Background Job Processing
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: How to design background job systems — queues, workers, retries, idempotency
  — and the cases where in-process tasks suffice vs. where dedicated job systems
  earn their place.
tags:
- background-jobs
- workers
- queues
- async-processing
related:
- MessageQueuePatterns
- DeadLetterQueuePatterns
- BatchProcessingPatterns
- IdempotencyPatterns
---
# Background Job Processing

Background jobs are tasks that don't fit in a request-response cycle: send email, generate PDF, process upload, run report. The HTTP handler enqueues the job; a worker process executes it later.

This page covers the patterns for reliable background job systems.

## When you need background jobs

### Long-running tasks

Email sending, file processing, ML inference. Anything that takes more than a couple seconds shouldn't block the HTTP response.

### Retry-able failures

Tasks that need retries (third-party API failures, eventual consistency).

### Decoupling

The web tier and worker tier scale independently. Heavy job processing doesn't slow web responses.

### Scheduled work

Periodic tasks: nightly cleanup, daily reports.

## The basic architecture

```
HTTP request → Enqueue job → 200 OK (immediate)

Background:
Worker → Pull job from queue → Execute → Mark done (or retry)
```

Components:
- **Queue**: Redis, RabbitMQ, SQS, Kafka
- **Workers**: long-running processes that consume from the queue
- **Job results**: stored separately if needed

## Queue technology

### Redis-backed (Sidekiq, BullMQ, RQ)

Simple; fast; relatively easy to operate. Limited durability if Redis fails.

### Database-backed

The job table is in your application database. Simpler architecture; no extra infrastructure.

Limitations: doesn't scale to high job volume; database load.

### Dedicated message queue (RabbitMQ, NATS)

Built for queueing. More features (routing, dead-letter, etc.). More infrastructure to run.

### Cloud-managed (SQS, Pub/Sub, Service Bus)

Managed by cloud provider. SQS for AWS; Pub/Sub for GCP; Service Bus for Azure.

For most cloud-native shops, the managed option is right.

### Stream-based (Kafka)

For high throughput with replay needs. More complex than queues.

## Worker design

### Long-running

Worker process pulls job; executes; pulls next. Not per-job process.

### Concurrency

Workers process many jobs in parallel. Configurable concurrency per worker.

### Multiple workers

Scale horizontally. Many workers consuming the same queue.

### Worker isolation

Each worker process is independent. One worker dying doesn't affect others.

## Reliability patterns

### Idempotency

Jobs may be retried. The same job running twice should produce the same result.

Use idempotency keys, dedup logic, or idempotent operations. See [IdempotencyPatterns](IdempotencyPatterns).

### Retries with backoff

Job fails; retry with exponential backoff. Don't retry forever; eventually give up.

Typical: 3-5 retries; backoff 1m, 5m, 15m, 1h, 6h.

### Dead letter queues

Jobs that fail all retries go to a DLQ. Investigate; either fix and re-run, or accept failure.

See [DeadLetterQueuePatterns](DeadLetterQueuePatterns).

### Visibility timeout

When a worker pulls a job, it has a timeout to complete. If timeout exceeded, job becomes available again — another worker picks it up.

Prevents lost jobs from worker crashes. But: jobs longer than the timeout get processed twice (which is why idempotency matters).

### At-least-once vs. exactly-once

Most queues are at-least-once. Jobs may run more than once; consumers handle duplicates.

"Exactly-once" is rare and expensive. Don't promise it; design for at-least-once.

## Job design

### Small jobs

Each job does one thing. Easier to retry; easier to reason about; easier to debug.

### Pass IDs, not data

Job: `{type: "process_upload", upload_id: "abc"}` not the entire upload data. The worker fetches the data fresh; data doesn't go stale in queue.

### Fast jobs

Long jobs are problematic: visibility timeouts; lost progress on crash; harder to retry.

If a job naturally takes hours, decompose into smaller jobs.

### Versioning

Jobs in the queue may run with newer or older worker code. Design for compatibility:
- Add new fields; don't remove
- Default values for missing fields
- Reject incompatible jobs explicitly

## Specific frameworks

### Sidekiq (Ruby)

Dominant for Ruby. Redis-backed.

### BullMQ (Node.js)

Modern Node.js. Redis-backed.

### RQ, Celery (Python)

Celery is more feature-rich; RQ is simpler. Both Redis-backed by default.

### Spring Batch (Java)

For batch processing. See [BatchProcessingPatterns](BatchProcessingPatterns).

### Quartz (Java)

Java scheduling. Older but mature.

### Cloud-native

AWS SQS + Lambda; GCP Pub/Sub + Cloud Functions. Serverless workers.

## Common patterns

### Job classes vs. raw queue

Frameworks define job classes:

```ruby
class ProcessUploadJob
  def perform(upload_id)
    # work
  end
end

ProcessUploadJob.perform_async(upload_id)
```

Cleaner than manually serializing/deserializing.

### Priority queues

Some jobs are higher priority. Multiple queues with different priorities; workers pull from high-priority first.

### Concurrency limits per type

Don't run 1000 video transcodes simultaneously; not 1000 emails at once. Per-job-type concurrency limits.

### Job lifecycle hooks

Before/after job hooks. Logging; metrics; cleanup.

### Cron-style schedules

Periodic jobs. Many job frameworks have cron-like scheduling. See [ScheduledTaskManagement](ScheduledTaskManagement).

## Common failure patterns

- **Slow jobs without segmentation.** A few slow jobs hold all worker capacity.
- **No idempotency.** Retries cause duplicates.
- **No DLQ.** Failed jobs lost forever.
- **Workers not monitored.** Jobs queued; never processed; nobody notices.
- **Job data in queue not service.** Stale data when job runs.
- **Long-running jobs without checkpointing.** Crash means restart from scratch.

## A reasonable starter

For new applications:

1. Pick a queue technology (cloud-managed for most cases)
2. Pick a job framework for your language
3. All jobs idempotent
4. Standard retry policy (3-5 retries, exponential backoff)
5. DLQ for failed-completely jobs
6. Monitoring on queue depth, worker count, job latency
7. Alerting on DLQ growth and stuck jobs

## Further Reading

- [MessageQueuePatterns](MessageQueuePatterns) — Underlying primitive
- [DeadLetterQueuePatterns](DeadLetterQueuePatterns) — Failed-job handling
- [BatchProcessingPatterns](BatchProcessingPatterns) — Adjacent pattern
- [IdempotencyPatterns](IdempotencyPatterns) — Critical for jobs

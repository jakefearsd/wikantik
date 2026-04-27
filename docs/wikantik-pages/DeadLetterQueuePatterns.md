---
canonical_id: 01KQ0P44PFPJB7WC1M7EC8E2V9
title: Dead Letter Queue Patterns
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: How dead letter queues work — where messages that fail repeatedly go, how
  to triage them, and the patterns that prevent silent message loss.
tags:
- dead-letter-queue
- dlq
- queue
- error-handling
- distributed-systems
related:
- BackgroundJobProcessing
- MessageQueuePatterns
- IdempotencyPatterns
- StatusPageBestPractices
---
# Dead Letter Queue Patterns

A dead letter queue (DLQ) is where messages go when they fail repeatedly. Without a DLQ, repeatedly-failing messages either retry forever (consuming resources) or get dropped silently (data loss).

The DLQ is the safety net. Done well, it surfaces failures for human investigation. Done poorly, it accumulates without anyone looking, hiding the same problems.

## How it works

```
Queue → Worker tries to process → Failure → Retry → ... → 
Max retries exceeded → Message goes to DLQ
```

The original queue keeps moving; the failed message is preserved separately for investigation.

## What goes in a DLQ

### Messages with permanent errors

Bug in worker code that always fails on this message. Retry won't help.

### Messages that timeout

Worker takes too long; visibility timeout fires; eventually exhausts retries.

### Messages with malformed data

Worker can't parse; rejects; retries fail the same way.

### Resource-exhaustion failures

Worker runs out of memory; retries hit the same wall.

## DLQ implementation

### Cloud-managed queues

AWS SQS:
```
Source queue → max_receives = 5 → DLQ
```

After 5 failed receives, message moves to DLQ automatically.

Similar in GCP Pub/Sub, Azure Service Bus.

### Self-managed

Implement in worker code:

```python
try:
    process(message)
except Exception as e:
    if message.retry_count < MAX_RETRIES:
        requeue(message, delay=backoff(message.retry_count))
    else:
        dlq.send(message, error=str(e))
```

Less convenient; more flexible.

### Database-backed

The "DLQ" is a database table for failed jobs:

```sql
INSERT INTO failed_jobs (job_data, error, attempts, failed_at)
VALUES (...);
```

Queryable; investigatable; retentioned.

## What to do with DLQ contents

### Investigate

Why did this message fail? Look at:
- The message content
- The error message
- When it happened
- How many retries

Common causes:
- Bug in worker code
- Bad message data
- External dependency failure

### Categorize

Group DLQ messages by error pattern. Many similar errors usually mean one bug; one fix resolves many messages.

### Replay

After fixing the bug, re-process the messages.

```python
for message in dlq.read_all():
    main_queue.send(message)
```

For some failures, replay won't help (message is fundamentally bad). Discard those after deliberate decision.

### Discard

Some messages should never have been processed. Discard explicitly with logging:

```python
log.info(f"Discarding bad message: {message.id}")
dlq.delete(message)
```

## Operational practices

### Alert on DLQ growth

A DLQ that's accumulating is signaling a problem. Alert when:
- DLQ depth exceeds threshold
- DLQ grew significantly in the last hour
- DLQ has messages older than X days

Alert noise: alert on patterns, not on every individual message.

### Periodic review

Weekly or daily: how many messages in DLQ? Investigate. Fix or discard.

A DLQ left to accumulate becomes useless — too many messages to triage.

### Retention policy

DLQ messages have storage cost. Set retention:
- Keep for N days
- Periodically archive
- Eventually delete

Without retention, DLQ grows forever.

### Documentation

Each DLQ has documentation: what's its source queue, what does success vs. failure look like, who owns triage?

## Specific patterns

### Tiered DLQs

Multiple DLQs by failure type:
- Transient errors: retry-able later
- Permanent errors: needs investigation
- Malformed: parsing failed

Different triage process per tier.

### DLQ with context

Don't just store the failed message. Store also:
- Error message
- Stack trace
- Worker version
- Timestamp
- Retry count

Investigation is much easier with context.

### Replay after fix

After fixing the bug, replay all messages in DLQ. Be careful: re-running them may have side effects (notifications, billing). Verify before mass replay.

### Selective replay

Sometimes only some messages should replay. Filter:

```python
for message in dlq.read_all():
    if message.error_type == "transient_network_error":
        main_queue.send(message)
    else:
        log.info(f"Skipping non-replayable: {message.id}")
```

### Notification on first failure of new type

When a new error pattern appears in DLQ, page the on-call. New errors usually mean new bugs.

## Common failure patterns

- **No DLQ.** Failed messages retry forever or get lost.
- **DLQ but no monitoring.** Accumulates silently.
- **No triage process.** DLQ has 100K messages; nobody looks.
- **Retention too short.** Messages deleted before investigation.
- **Replay without thought.** Side effects multiplied.
- **DLQ for non-DLQ purposes.** "Error queue" used as a general dump.

## A reasonable approach

For new queue-based systems:

1. DLQ on every queue (typically max_receives = 5)
2. Monitoring: depth, growth rate, message age
3. Alerting on growth
4. Documented triage process
5. Retention policy (typically 14-30 days)
6. Periodic review (weekly minimum)

## Further Reading

- [BackgroundJobProcessing](BackgroundJobProcessing) — Where DLQs fit
- [MessageQueuePatterns](MessageQueuePatterns) — Queue context
- [IdempotencyPatterns](IdempotencyPatterns) — Affects retry safety
- [StatusPageBestPractices](StatusPageBestPractices) — DLQ growth often correlates with incidents

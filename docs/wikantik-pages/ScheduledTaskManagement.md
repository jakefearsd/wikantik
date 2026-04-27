---
canonical_id: 01KQ0P44W30F9NP4R8RK3BFM72
title: Scheduled Task Management
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: Cron, Kubernetes CronJobs, cloud schedulers — how to run scheduled tasks
  that survive failures, retries, and the operational realities that simple cron
  tabs don't handle.
tags:
- scheduled-tasks
- cron
- kubernetes
- automation
- batch
related:
- RunbookAutomation
- DevOpsFundamentals
- AwsLambdaPatterns
hubs:
- DevOpsAndSre Hub
---
# Scheduled Task Management

Scheduled tasks: scripts or jobs that run on a schedule. Daily reports, hourly cleanup, weekly billing, periodic reconciliation. Almost every system has them.

The simple version: a cron job on a server. Works for tiny systems. For real production, you need more.

## The simple case: cron

```bash
# crontab -e
0 2 * * * /path/to/script.sh
```

Runs the script at 2am daily. Cron is fine for:

- Low-stakes tasks
- Single-machine deployments
- Tasks that can tolerate occasional failures

For production at scale, cron has problems:

- Single point of failure (the server with the crontab)
- No retry on failure
- No alerting on failure
- No history of runs
- Hard to audit ("did this run yesterday?")

## Kubernetes CronJobs

For Kubernetes-deployed apps:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: nightly-cleanup
spec:
  schedule: "0 2 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: cleanup
            image: my-cleanup:latest
          restartPolicy: OnFailure
```

Pros: integrated with cluster; automatic restart on failure; logs available.
Cons: Kubernetes-specific; cluster needs to be running.

## Cloud-native schedulers

### AWS EventBridge / CloudWatch Events

Cloud-native cron equivalent. Triggers Lambda, Step Functions, or other AWS services.

```yaml
ScheduleExpression: rate(1 hour)  # or cron-like expression
Target: arn:aws:lambda:...
```

Serverless; managed; pay-per-invocation.

### GCP Cloud Scheduler

Similar; GCP-native.

### Azure Logic Apps / Functions

Azure equivalent.

For cloud-native deploys, the cloud scheduler is usually the right choice. Less operational overhead than self-hosted.

## What scheduled tasks need

### Idempotency

Tasks may run twice (network retry, infrastructure restart). The same task running twice should produce the same result.

Don't:
- Append to a log without deduplication
- Send notifications without a "did I send this already?" check
- Insert without ON CONFLICT DO NOTHING

See [IdempotencyPatterns](IdempotencyPatterns).

### Locking / single execution

Some tasks should run only once even if scheduled twice. Distributed lock (Redis, database row) ensures single execution.

### Observability

- Did the task run?
- Did it succeed?
- How long did it take?
- What did it process?

Logs to a central system; metrics on success/failure; alerts on missed runs.

### Retry policy

Network blip = retry. But not all errors should retry. Decide:
- Transient errors: retry with backoff
- Permanent errors: alert; don't retry

### Alerting on failure

If the cleanup job fails, someone needs to know. Email, Slack, page — depends on severity.

### Alerting on missed runs

The job didn't run at all (scheduler down). Some monitoring detects this.

## Specific patterns

### Heartbeat checks

Job sends a heartbeat to a monitoring service after success. Service alerts if no heartbeat.

Tools: Healthchecks.io, Cronitor, Better Stack heartbeats.

### Dead letter handling

Job processes a queue. Failed messages go to a dead letter queue for investigation.

### Distributed locks for clusters

Multiple instances might try to run the job. Lock prevents duplicate execution:

```python
with redis_lock("nightly-cleanup", timeout=300):
    do_cleanup()
```

### Schedules in code, not config

Define schedules in deployable code (Terraform, Kubernetes manifests). Not in someone's crontab on a specific server.

### Time zones

Cron schedules in what timezone? Server time? UTC? Match user expectations or document explicitly.

## Common failure patterns

### Server-specific cron

Crontab on one server. Server dies; tasks stop. Nobody notices for weeks.

### No alerting on failure

Job fails silently. Real impact only visible when something downstream breaks.

### No idempotency

Retries cause duplicates. Daily report sent twice.

### Long-running jobs without checkpointing

Job runs for 2 hours; fails 1.5 hours in; restart from scratch. With checkpointing, restart from where it failed.

### Tight schedules

Job scheduled every 5 minutes; sometimes takes 10. Multiple copies run simultaneously; conflict.

### No history

"Did the cleanup run yesterday?" — nobody knows.

### Manual re-runs

When a job fails, manual "kick it off again." Should be one click; ideally automatic.

## A reasonable starter pattern

For new scheduled tasks:

1. Schedule defined in code (Terraform, Kubernetes manifest, or cloud scheduler)
2. Idempotent execution
3. Distributed lock if multiple instances might run
4. Heartbeat to monitoring service
5. Alert on failure
6. Logs to central system

For an existing chaotic cron-based setup, migrate one task at a time to a structured framework.

## Common failure patterns

- **Tasks that just stop running.** No detection.
- **Tasks that run on the wrong schedule.** Wrong timezone; off-by-one.
- **Tasks that fail without alerting.** Silent rot.
- **Tasks that run twice.** No idempotency or locking.
- **Tasks that take too long.** Schedule mismatch.

## Further Reading

- [RunbookAutomation](RunbookAutomation) — Adjacent automation
- [DevOpsFundamentals](DevOpsFundamentals) — Broader practice
- [AwsLambdaPatterns](AwsLambdaPatterns) — Cloud function scheduling
- [DevOpsAndSre Hub](DevOpsAndSre+Hub) — Cluster index

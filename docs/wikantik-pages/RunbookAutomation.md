---
canonical_id: 01KQ0P44VYTXCN0DMFDZR9Q4ZJ
title: Runbook Automation
type: article
cluster: devops-sre
status: active
date: '2026-04-26'
summary: How to write runbooks that actually help — and how to automate the recoverable
  parts so on-call doesn't have to do everything manually.
tags:
- runbooks
- automation
- sre
- on-call
- incident-response
related:
- OnCallPractices
- ToilReductionStrategies
- ScheduledTaskManagement
- CodeDocumentationBestPractices
hubs:
- DevOpsAndSre Hub
---
# Runbook Automation

A runbook is operational documentation: when this alert fires, do these steps. The on-call engineer at 3am doesn't have to think; they follow the runbook.

Beyond writing runbooks, the next step is automating the recoverable parts. Why have a human run the same sequence of commands when the automation can?

This page covers runbook design and automation patterns.

## Anatomy of a good runbook

```markdown
# Alert: Database connection pool exhausted

## Symptoms
- Alert: db.connection_pool.in_use > 90%
- Customer impact: API errors, latency spikes

## Initial actions
1. Check current connection count: `bin/db-stats.sh`
2. Look for runaway query: `bin/long-queries.sh`
3. Check application metrics for unusual patterns

## Common causes
1. Long-running query holding connections
2. Application bug leaking connections
3. Genuine load spike

## Resolution

### If long-running query
```sql
SELECT pid, query, state, age(now(), xact_start) FROM pg_stat_activity
WHERE state != 'idle' ORDER BY xact_start;
```
Kill the offending query: `SELECT pg_cancel_backend(<pid>);`

### If connection leak
Restart the affected service:
`kubectl rollout restart deployment/api`

### If load spike
Scale up:
`kubectl scale deployment/api --replicas=10`

## Escalation
- After 30 minutes if not resolved: page secondary
- After 60 minutes: page database team

## Related
- Dashboard: ...
- Recent changes: ...
```

The runbook has specific commands; common causes; escalation criteria. The on-call doesn't invent the response.

## Writing principles

### Specific commands

Not "check the database connections." Specific: `psql -c "SELECT count(*) FROM pg_stat_activity"`.

### Tested

Runbooks rot. The command that worked last year doesn't now. Test runbooks periodically — game days, dry runs.

### Linked from alerts

Each alert has a link to its runbook. On-call gets the link in the alert payload.

### Maintained

Runbooks that nobody updates become wrong. Make updates part of incident postmortems.

## What to automate

### Recoverable failures

If the response is "restart the service," automate the restart. Liveness probes in Kubernetes do this for free.

### Auto-scaling

Load spikes? Scale up automatically. CPU-based, queue-depth-based, custom metrics.

### Failover

Primary region down? Route traffic to secondary. Health checks + DNS failover.

### Rollback

Recent deploy is causing errors? Auto-rollback if error rate exceeds threshold.

### Cleanup

Stuck jobs? Old logs? Dead resources? Scheduled cleanup tasks.

## What not to automate

### Decisions requiring judgment

"Is this a real customer impact or a flaky monitoring blip?" Humans decide. Automation paging the human is fine; automation deciding the response usually isn't.

### Destructive actions

"Drop the database table" — never automate. Even with confidence.

### High-impact actions

Cross-region failover, data migration, etc. Manual approval required.

### Untested automation

Automation that hasn't been tested in production might do worse than nothing.

## Specific patterns

### Self-healing systems

Health checks → automatic restarts → automatic scaling → fewer pages.

For workloads where this fits, the on-call gets paged less.

### Auto-rollback on canary failure

Deploy canary; monitor metrics for 10 minutes; auto-rollback if errors exceed baseline.

### Circuit breakers

Service fails repeatedly → circuit opens → traffic stops hitting it for a period → tries again.

Application-level resilience that doesn't need on-call involvement.

### ChatOps for response

`@bot, restart api in production` runs the restart. The bot logs the action; team sees what was done. Cleaner than SSH-ing in.

### Kill switches

Feature flags that disable problematic functionality. On-call can flip without code change.

## The progression

Mature operations follows this progression:

1. **Manual response**: human follows runbook
2. **Automated diagnosis**: tools tell you what's wrong faster
3. **Automated recovery for common cases**: alert fires; automation acts; human reviews
4. **Self-healing for known patterns**: alert doesn't even fire because system recovered

Each step reduces on-call load. The investment pays back over time.

## Common failure patterns

- **Runbooks that are stale.** Misleading worse than missing.
- **Runbooks no one wrote.** Tribal knowledge.
- **Alerts without runbooks.** On-call invents in the moment.
- **Too aggressive automation.** Auto-rollback during normal load fluctuation.
- **Automation that fails silently.** Things go wrong; nobody knows.
- **No escalation criteria.** On-call doesn't know when to call for help.

## A starter pattern

For a service with a new on-call rotation:

1. Document each alert's runbook (manual response)
2. Automate trivially recoverable cases (auto-restart on liveness fail)
3. Add canary deployment with auto-rollback
4. Ensure escalation paths are defined
5. Game day: simulate incident; test runbooks
6. Iterate based on real incidents

The runbook coverage and automation grow over months, not weeks.

## Further Reading

- [OnCallPractices](OnCallPractices) — On-call rotations
- [ToilReductionStrategies](ToilReductionStrategies) — SRE concept
- [ScheduledTaskManagement](ScheduledTaskManagement) — Adjacent automation
- [CodeDocumentationBestPractices](CodeDocumentationBestPractices) — Documentation parallels
- [DevOpsAndSre Hub](DevOpsAndSre+Hub) — Cluster index

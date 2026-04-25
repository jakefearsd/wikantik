---
canonical_id: 01KQ12YDRVHVES5SVP5N14DQ5X
title: Ai Observability In Production
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- llm
- observability
- monitoring
- evaluation
- drift-detection
summary: What to instrument, alert on, and visualise for production LLM systems —
  beyond trace logging into eval-in-prod, drift detection, and the dashboards
  that catch quiet quality regressions.
related:
- AgentObservability
- LlmEvaluationMetrics
- AgentTesting
- AiHallucinationMitigation
- DistributedTracing
hubs:
- AgenticAi Hub
---
# AI Observability in Production

Standard observability — traces, logs, metrics — covers whether your LLM system is *running*. AI observability adds whether it's *behaving*. The question shifts from "is the API up" (easy) to "are responses still accurate, faithful, calibrated, fast, and cheap" (harder, more important).

This page is what to add on top of standard observability for LLM systems specifically. For the standard stuff, see [AgentObservability]; for the eval methodology, [AgentTesting].

## The four signals to track

| Signal | Question | Source |
|---|---|---|
| **Cost & latency** | Is each call efficient? | LLM call telemetry |
| **Quality** | Are responses still as good? | Eval-in-prod, sampled human labels |
| **Drift** | Has the input distribution changed? | Embedding drift + statistical tests |
| **Safety** | Are we generating things we shouldn't? | Output classifiers, guardrails |

Standard APM covers the first; the other three are LLM-specific and are usually neglected in v1.

## Cost and latency

The basics:

- Per-call: input tokens (cached + uncached), output tokens, model, latency, cost.
- Per-task: total tokens, total cost, total wall time.
- Per-user / per-tenant: cumulative cost; basis for fair-use enforcement and cost attribution.

Dashboards:

- Cost trend per model per day/week.
- Cache hit rate per system prompt version.
- p50/p95/p99 latency per model.
- Cost per task type (debugging "why is task X expensive").

Alerts:

- Cost per task p95 > 2× last week's baseline (catches prompt regressions).
- Cache hit rate drops > 10 percentage points (catches prompt-prefix instability).
- Latency p95 > target (model degradation, provider issue, network).

These are easy to implement and almost always worth the effort. See [LlmTokenEconomicsAndPricing] for the cost side.

## Quality monitoring (the hard one)

LLM output quality is hard to measure automatically. Three complementary approaches:

### Sampled human labels

A small percentage (1-5%) of production outputs go to human reviewers who grade them on a fixed rubric. Slow, expensive, gold-standard.

Practical setup:

- Sampling: random for baseline; targeted for specific concerns ("flag any task that took > 20 steps").
- Rubric: 3-5 dimensions max (accuracy, helpfulness, format adherence, safety). Yes/no or 5-point scale.
- Calibration: 2 reviewers per item for a subset; track inter-rater agreement.
- Cadence: weekly aggregate; monthly trend.

Most teams under-invest here because it's expensive. It's also the only ground-truth source of "is the system actually good." Budget for it.

### LLM-as-judge in production

A second LLM call grades each production output. Cheap (compared to human review), reasonably correlated with human judgement once calibrated.

Caveats from [LlmEvaluationMetrics]: judge bias, requires calibration against humans, drifts when the judge model updates.

Practical use: judge every Nth response (1-10%); aggregate scores by task type, time window, prompt version. The trend matters more than absolute values.

### Eval set replay in production

Your fixed eval set ([AgentTesting]) doesn't have to be eval-only. Run it nightly on production-deployed prompts. Catches regressions immediately rather than waiting for users to notice.

Set up: eval task fixtures stored as JSON; nightly cron triggers rollouts against the live system; results sent to the same dashboard as production telemetry; alert on drop > 5%.

Cost: $1-50 per nightly run depending on eval set size and model. Compare to the cost of a quality regression in production for a day before someone noticed.

## Drift detection

Production input distribution drifts. Users ask new things; data sources change; competitors launch features that shift behaviour. Drift detection surfaces this before it becomes a quality regression.

Two forms:

### Embedding drift

Compute embeddings of incoming queries (and/or retrieved docs). Compare distribution to a baseline window.

Methods:

- **MMD (Maximum Mean Discrepancy)** — measures distance between distributions in embedding space. Statistical test.
- **Population stability index (PSI)** — bin embeddings (clusters or bins), compare frequencies.
- **Embedding-cluster outlier rate** — fit clusters on baseline; alert when too many new queries are far from any cluster.

Implement as a daily batch job; alert when drift score exceeds threshold; investigate (new use case? broken upstream filter? attack pattern?).

### Token-level drift

For specific high-value features, track distributions of:

- Query length (sudden jumps signal usage pattern change).
- Top tokens / top n-grams (new vocabulary appearing).
- Language detection (new languages appearing).
- Tool call frequency (which tools are agents calling, in what proportions).

Tools like Arize Phoenix, Fiddler, and Whylogs (open source) handle this. For small teams, a custom batch job is fine.

## Safety / guardrails monitoring

Most production LLM systems have output guardrails — classifiers that flag inappropriate, off-policy, or dangerous outputs. Track:

- **Guardrail trip rate per category** (e.g. % of outputs flagged for "personal advice," "potential PII").
- **False positive rate** (sampled, human-validated).
- **Override rate** (when humans inspect a flagged output and approve it).

Sudden spikes in trip rate often signal:

- A new attack pattern (prompt injection, jailbreak attempts).
- A model update with shifted behaviour.
- A bug in the guardrail itself.

For safety-critical deployments, also track:

- **Refusal rate** — how often the model declines. Sudden drops can mean the safety training has been undone (regression after a model update); sudden spikes can mean overly conservative behaviour limiting usefulness.

## Dashboards that matter

Build these and monitor them. Without them, problems hide.

1. **Per-model dashboard.** Latency, cost, cache hit, error rate, request volume. One row per model.
2. **Per-task-type dashboard.** Success rate, cost per task, latency, eval scores. One row per task type.
3. **Production eval dashboard.** Eval set scores by date; trend; regressions highlighted.
4. **Quality sampling dashboard.** Human-labelled and judge-labelled quality scores; trend.
5. **Drift dashboard.** Embedding drift, cluster outlier rate, query-length distribution.
6. **Safety dashboard.** Guardrail trip rates, refusal rate, false-positive estimate.

Most teams have #1 and call it done. Adding 2-6 catches the issues #1 misses.

## Alerts that matter

Pager-worthy:

- Production task success rate < baseline - 5% over a 1-hour window.
- Cost per task p95 > 2× baseline over 1 hour.
- Cache hit rate drops > 20% over 24 hours.
- Tool error rate > baseline + 10 percentage points.
- Eval set score drops > 5 points (nightly run).

Ticket-worthy (not pager):

- Drift metric exceeds threshold.
- Guardrail trip rate up > 50% week-over-week.
- New top tokens appearing in queries (might be legitimate; might be a new attack class).

## Tools

| Tool | Strengths | When to pick |
|---|---|---|
| **Langfuse** | Open source, model-agnostic, traces + evals + drift | Default starting point |
| **LangSmith** | Polished UI, deep LangChain integration | LangChain users |
| **Arize Phoenix** | Strong on drift detection, eval workflow | Drift-heavy use cases |
| **Fiddler** | Commercial AI observability, ML-ops focus | Larger orgs, traditional ML alongside LLM |
| **Helicone** | Lightweight, easy onboarding | Smaller teams |
| **OpenLLMetry + your existing stack** | OpenTelemetry-native | Teams with mature OTel |

Many teams start with Langfuse self-hosted; graduate to commercial tools when the operational overhead exceeds the cost.

## Privacy and observability

Storing every prompt and completion captures whatever users put in. Compliance implications:

- **PII redaction** at log time, not just at display time.
- **Per-user retention** — your retention policy applies to traces too.
- **Deletion propagation** — DSAR has to find and delete trace records.
- **Sampling reduces exposure** — don't store 100% of prompts.

See [AiDataPrivacyAndCompliance].

## Observability for agentic systems

Agents add structure that raw LLM traces don't capture:

- Per-step trace, not just per-call.
- Working memory snapshots.
- Tool call validity / retry counts.
- Termination reason (success / max steps / error).

The standard tooling above is catching up; expect agentic-specific instrumentation to be a first-class feature in observability tools by late 2026.

## Further reading

- [AgentObservability] — agentic-system observability in depth
- [LlmEvaluationMetrics] — the metrics fed to dashboards
- [AgentTesting] — eval methodology that production replays use
- [AiHallucinationMitigation] — quality interventions
- [DistributedTracing] — traces underneath the LLM-specific telemetry

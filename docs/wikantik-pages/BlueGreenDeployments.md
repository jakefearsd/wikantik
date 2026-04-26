---
title: Blue Green Deployments
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- deployment
- continuous-delivery
- blue-green
- canary
- progressive-delivery
summary: Blue-green deployment compared to canary, rolling, and recreate —
  what each guarantees, what each costs, and the patterns most production
  systems actually use.
related:
- ContainerOrchestration
- DarkLaunchPatterns
- CanaryDeployments
- ChaosEngineering
hubs:
- SoftwareArchitecture Hub
---
# Blue-Green Deployments

Blue-green is "run two parallel environments; switch traffic from one to the other." It's a deployment strategy with strong rollback guarantees — if the new version misbehaves, switch back, instantly.

The strategy has lost ground to canary / progressive delivery as the dominant pattern, but it's still the right answer in specific cases.

## How it works

Two production environments — "blue" and "green" — both capable of serving real traffic. Only one is live at a time.

```
Today:    Blue (v1.0) → live traffic
          Green (v2.0) → idle, deployed

Switch:   Blue (v1.0) → idle  
          Green (v2.0) → live traffic   ← cut over via load balancer

Tomorrow: Green (v2.0) → live traffic
          Blue (v3.0) → idle, deployed
```

Mechanism: a load balancer or DNS swap routes traffic. The "switch" is fast (seconds to minutes); rollback is the same swap in reverse.

## What it gets right

- **Instant rollback.** If green is broken, switch back to blue. No deploy queue; no rebuild.
- **Pre-warmed environment.** Green has been running, caches are warm, JIT has compiled hot paths.
- **Testing in production-like conditions.** Smoke-test green before switching; the environment is identical to production.
- **Zero-downtime cutover.** Brief overlap window; both serve briefly; then full cutover.

## What it costs

- **2× infrastructure** (briefly). Both environments run during deployment. For a stateful, expensive system, this is real money.
- **State coordination.** Both environments share the database; schema must be compatible with both versions. See expand-contract pattern.
- **Limited granularity.** Either everyone's on green or nobody is. No "10% canary, validate, expand."
- **Operational discipline.** Two environments to keep in sync; database migrations must work across both.

## When blue-green wins

- **Releases require a fast, atomic, validated cutover.** Heavily regulated systems where staged rollout is hard to justify.
- **Workloads with significant warmup cost.** JVMs that take 10 minutes to JIT-compile to peak performance; ML inference services with cache warmup.
- **Stateful systems where canary is hard.** Two environments are cleaner than slicing traffic with shared state.

## When canary wins

For most modern web applications, canary deployment beats blue-green:

- **Roll out to 1% of users, monitor, expand to 10%, monitor, expand to 100%.** Catches bad versions before they hit everyone.
- **Cheaper** — no full duplicate environment.
- **Reversible** — drain the canary if it's bad; users on the canary may have brief impact, but it's bounded.
- **Better metrics** — the canary's behaviour is observable separately from the main fleet.

Canary requires:

- Traffic-routing infrastructure (service mesh, load balancer with weighted targeting).
- Feature flags or version-aware code.
- Observability per version.
- Automated rollback triggers (error rate, latency).

Most modern teams use canary or some progressive delivery system (LaunchDarkly, Argo Rollouts, Flagger). Blue-green has become a niche.

## Hybrid: blue-green for infrastructure, canary for code

A common shape:

- **Blue-green for infrastructure changes** (Kubernetes upgrades, database major versions, network changes). Two clusters; switch over; blue-green semantics for things you can't easily slice traffic against.
- **Canary for application releases**. Within a single environment, progressive rollout via service mesh or feature flags.

This combines the strengths. The infrastructure swap is rare and atomic; application changes are gradual and reversible.

## Database considerations

Blue-green is hardest on databases. Both environments share the same database; schema changes affect both.

The discipline:

- **Migrations are forward-compatible.** Old code (still on blue) and new code (on green) both work against the new schema.
- **Use expand-contract.** Add new columns; backfill; switch traffic; later remove old. See [DatabaseMigrationStrategies].
- **Don't deploy schema changes during the cutover window.** Migrate in advance; roll out the application that uses the new schema.

A naive blue-green where the database migrates during the switch produces broken state. Plan migrations to land before the application change.

## Cutover patterns

The "switch" can be:

- **DNS swap.** Slow (DNS TTLs); some clients keep stale records. Use only with very low TTLs and acceptance that brief overlap is fine.
- **Load balancer reconfig.** Fast (seconds); precise. Most common.
- **Service mesh routing.** Same as LB but with more control.
- **Feature flag** controlling which environment to route to. Enables more nuanced rollback.

For Kubernetes specifically, blue-green is implemented via two Deployments and a Service whose selector switches. Tools (Argo Rollouts, Flagger) automate the switch and the validation.

## Traffic-shifting strategies

Variations on the cutover:

- **All-at-once.** Switch 100% of traffic at once. Maximum risk.
- **Stepped.** Switch in increments (10%, 25%, 50%, 100%). Each step is a checkpoint.
- **Header-based.** Internal users hit green first; only after they validate, switch external traffic.
- **Geo-based.** Roll out by region. EU first; if good, US.

Stepped switching with automated rollback on bad metrics is the closest blue-green gets to canary's gradient.

## Failure modes

- **Schema not migrated before switch.** Green starts; queries against new columns; columns don't exist. Outage.
- **Asymmetric warmup.** Blue had warm caches; green is cold; switching produces a latency spike. Pre-warm green before switching.
- **Sticky sessions.** Users mid-session on blue suddenly hit green; lose session state. Either drain blue (slow) or share session state (database, Redis).
- **Long-running connections / WebSockets.** Blue's open connections don't migrate. Drain time can be hours. Plan for it.
- **Queue / scheduled work.** A background worker on blue picked up a job; you switched to green; the work continues on blue. Coordinate with workers.

## Tools

- **Argo Rollouts** — Kubernetes; handles blue-green and canary; integrates with metrics.
- **Flagger** — similar; tighter Istio / Linkerd integration.
- **AWS CodeDeploy** — supports blue-green for ECS, EKS, Lambda.
- **Cloudflare Pages / Vercel / Netlify** — built-in blue-green-like deployment for static / serverless.

For new Kubernetes deployments: pick Argo Rollouts or Flagger; both work; pick based on your service mesh.

## What I'd actually recommend

For a typical modern team:

1. **Start with rolling deployments** — Kubernetes' default. Cheap, decent, good enough for most cases.
2. **Add canary for high-stakes services** — payment, auth, anything customer-facing. Argo Rollouts or Flagger.
3. **Reserve blue-green for cases where canary doesn't fit** — major infrastructure changes, environments where partial rollouts don't work.

Blue-green isn't dead; it's just not the default anymore.

## Further reading

- [ContainerOrchestration] — Kubernetes deployment primitives
- [DarkLaunchPatterns] — release without traffic
- [CanaryDeployments] — the gradient version
- [ChaosEngineering] — testing the deployment doesn't break things

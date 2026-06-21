---
cluster: devops-sre
date: '2026-04-26'
title: DevOps Fundamentals
hubs:
- DevOpsAndSreHub
tags:
- devops
- sre
- automation
- culture
summary: What DevOps actually changed — from siloed dev/ops to shared responsibility,
  and the practices that distinguish it from traditional release engineering.
related:
- CiCdPipelines
- OnCallPractices
- ToilReductionStrategies
- CloudNativeApplicationDesign
canonical_id: 01KQ0P44PKG4DMYKKQZX58NC8V
type: article
status: active
---
# DevOps Fundamentals

DevOps started as a cultural movement: developers and operations sharing responsibility for production. The term has been diluted — every tool calls itself "DevOps" — but the underlying ideas are real and have transformed how software ships.

This page covers what DevOps actually changed and the practices that matter.

## The original problem

Traditional split:
- **Developers**: write code; throw it over the wall
- **Operations**: run the code in production; "stop us breaking your stuff"

Result: misaligned incentives. Developers move fast; ops slow them down. Production failures fall in the gap. Each side blames the other.

## What DevOps proposed

Shared responsibility. Developers care about production; ops cares about delivery. Tools that bridge the gap. Cultural shift away from blame.

The CAMS framing (Damon Edwards, John Willis, 2010):
- **Culture**: shared ownership, blameless culture
- **Automation**: deployment, testing, infrastructure
- **Measurement**: metrics shared between dev and ops
- **Sharing**: knowledge, tools, responsibility

The cultural part matters more than the tools, but tools are what's visible.

## Practices that emerged

### Continuous integration / continuous delivery

Every change goes through automated build, test, deploy. See [CiCdPipelines](CiCdPipelines).

### Infrastructure as code

Servers, networks, load balancers — all defined in code. Reproducible; version-controlled. See [TerraformFundamentals](TerraformFundamentals).

### Monitoring and observability

Production state visible to developers. Metrics, logs, traces all accessible. See [CloudMonitoring](CloudMonitoring).

### On-call rotations

Developers handle production issues alongside ops. Sometimes called "you build it, you run it." See [OnCallPractices](OnCallPractices).

### Postmortems

Incidents get analyzed; lessons captured. Blameless culture: focus on system causes, not individual blame.

### Trunk-based development

Short-lived branches; frequent integration. Avoids the "release in 6 months" pattern. See [TrunkBasedDevelopment](TrunkBasedDevelopment).

## SRE: Google's variant

Site Reliability Engineering (SRE) is Google's specific implementation. Treats operations as an engineering problem.

Key concepts:
- **SLOs (Service Level Objectives)**: target reliability (99.9%)
- **Error budgets**: how much downtime is acceptable
- **Toil**: manual operational work; reduce it
- **Blameless postmortems**: learning, not blame

SRE is a flavor of DevOps. Many companies adopt SRE titles without the underlying practices.

## What "DevOps" doesn't mean

The term has been diluted. Some misuses:

### "DevOps engineer" as a job title

Often means "ops person who can also write some scripts." Not bad work, but doesn't capture the cultural change.

### "DevOps tools"

Tools (Kubernetes, Terraform, GitLab CI) are useful but aren't the practice. Buying tools doesn't make you DevOps.

### "DevOps team"

A team named DevOps that handles deployments doesn't bridge the dev/ops gap; it just relocates it.

The cultural change is what matters. The tools support it.

## What DevOps actually changed

For most modern software organizations:

1. **Deploy frequency**: from monthly/quarterly to multiple per day
2. **Lead time**: from weeks/months to hours
3. **Change failure rate**: through better testing
4. **MTTR**: faster recovery via better tooling and observability

The DORA metrics (DevOps Research and Assessment) measure these. Elite teams deploy multiple times per day; low performers, less than monthly.

## What DevOps didn't fix

- **Bad architecture**: still bad; just deployed faster
- **Poor product decisions**: still poor; faster delivery doesn't help
- **Burnout**: faster pace can increase it; needs deliberate management
- **Skill gaps**: full-stack expectations are real; not everyone can or should

## A reasonable adoption path

For traditional organizations adopting DevOps practices:

1. **Start with CI/CD**: automated build and test on every change
2. **Add IaC**: infrastructure version-controlled
3. **Improve monitoring**: developers can see production
4. **Cultural shift**: shared responsibility; blameless postmortems
5. **On-call rotation**: when the team is ready

Each step is meaningful on its own. Don't try to adopt everything simultaneously.

## Common failure patterns

- **Tooling without culture.** All the tools, none of the shared responsibility.
- **DevOps team as a wall.** Just the new name for ops.
- **Blameless theatre.** Postmortems that say "blameless" but find scapegoats.
- **DevOps for tiny apps.** Heavy practices for small projects; overhead.
- **Burnout from full-stack expectations.** "You build it, you run it" without enough resources.

## Further Reading

- [CiCdPipelines](CiCdPipelines) — Pipeline automation
- [OnCallPractices](OnCallPractices) — Operations rotation
- [ToilReductionStrategies](ToilReductionStrategies) — SRE concept
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — Cloud-native fits DevOps
- [DevOpsAndSre Hub](DevOpsAndSreHub) — Cluster index

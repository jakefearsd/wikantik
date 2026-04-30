---
canonical_id: 01KQ0P44STVNKF97FYZ81YPBTJ
title: Multi-Cloud Strategies
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: Why multi-cloud is harder than it sounds — the hidden costs, the patterns
  that actually work, and the cases where multi-cloud is worth it vs. where it's
  cargo-cult.
tags:
- multi-cloud
- cloud
- vendor-lock-in
- portability
related:
- AwsFundamentals
- CloudNativeApplicationDesign
- CloudMigrationStrategies
- TerraformFundamentals
hubs:
- CloudPlatformsHub
---
# Multi-Cloud Strategies

"Multi-cloud" sounds like a strategy. Often it's an aspiration, sometimes a religion, occasionally a real architectural choice. The reality: multi-cloud is harder than it sounds, the benefits are smaller than promoted, and the costs are larger.

This page is the honest assessment.

## What "multi-cloud" actually means

Several different things, often conflated:

### Single workload across multiple clouds

The hardest version. One application running on AWS *and* GCP simultaneously, capable of failover or load distribution.

### Different workloads on different clouds

Workload A on AWS; workload B on GCP. Each is single-cloud; the company is multi-cloud.

### Cloud-portable architecture

Could run on any cloud, currently runs on one. Theoretical multi-cloud.

### Multi-region within one cloud

Sometimes called "multi-cloud" but isn't. AWS us-east-1 + AWS us-west-2 is multi-region.

The first is technically demanding; the others are mostly just "we use multiple clouds."

## The case for multi-cloud

### Avoid vendor lock-in

The classic argument. If AWS raises prices or has problems, having alternatives provides leverage.

The honest counter: AWS rarely raises prices for existing customers; the lock-in argument is more theoretical than empirical. The cost of multi-cloud often exceeds any reasonable benefit from leverage.

### Best-of-breed services

GCP's BigQuery and ML services are arguably better than AWS equivalents. Azure dominates for Microsoft-stack integrations. Different clouds have different strengths.

This is the strongest practical argument: use the best service for each job, not the same vendor for everything.

### Regulatory requirements

Some regulations require multi-cloud. Financial services, healthcare, government. Compliance trumps technical preference.

### Resilience against cloud failure

If AWS has a major outage, GCP keeps you running. This is theoretical for most workloads — full cloud failures are extremely rare and brief.

## The hidden costs

### Cognitive overhead

Each cloud has different services, IAM, networking, billing. Operating multi-cloud means knowing all of them at depth. Most teams are good at one cloud and adequate at others.

### Tooling fragmentation

CloudFormation only does AWS. Bicep only does Azure. Terraform handles all but with provider-specific resources. Most operational tools have favorites.

### Network costs

Cross-cloud traffic is expensive. Egress fees from cloud A; ingress costs to cloud B (sometimes free); latency between them. Architectures that cross cloud boundaries pay this constantly.

### Duplicated investment

Monitoring, logging, security, deployment all need to work in both clouds. Twice the integration work, twice the licensing if vendors charge per cloud.

### Data gravity

Your data lives in one cloud. Moving it costs egress fees. Replicating to multiple clouds means double storage cost and ongoing replication work.

## Where multi-cloud actually pays

### Service-specific best-of-breed

Use BigQuery for analytics on GCP; serve user traffic from AWS. The cross-cloud is for analytics, where the workload tolerates higher latency.

### Acquired companies

Acquired company runs on Azure; primary infrastructure on AWS. Migrating costs more than coexisting.

### Different regulatory zones

EU operations on EU clouds; US operations on US clouds.

### Disaster recovery

For ultra-critical workloads, DR in a different cloud. The complexity cost is real but the resilience benefit is real for the right workloads.

## What usually doesn't work

### "Cloud-portable architecture from day one"

Designing every component to abstract over cloud differences. The abstractions are leaky; the cost is large; the portability rarely gets exercised.

Better: pick one cloud; build well; reconsider only if a specific reason emerges.

### "We'll switch clouds easily"

The companies that have actually switched primary clouds report years of work. The promise of easy portability is largely fiction.

### "Multi-cloud Kubernetes makes everything portable"

Kubernetes APIs are similar across providers but the surrounding infrastructure (load balancers, storage, identity, etc.) is provider-specific. Full portability doesn't materialize.

## Patterns that work

### Containers as portability layer

Container images run anywhere. The application is portable; the surrounding infrastructure (load balancer, secrets, logging) is per-cloud but commodity.

### Terraform with cloud-specific modules

Single tool; per-cloud modules. Doesn't make code portable but makes infrastructure code consistent across clouds.

### Cloud-agnostic abstractions where they're cheap

Object storage: S3, GCS, Azure Blob are similar. A small abstraction layer hides differences.

Databases: PostgreSQL is PostgreSQL. RDS, Cloud SQL, Azure Database for PostgreSQL — all just hosted PostgreSQL.

These abstractions are cheap; use them.

### Cloud-specific where it pays

Don't abstract away DynamoDB to "any key-value store." The abstraction loses what makes DynamoDB valuable.

## Common failure patterns

- **Multi-cloud as religion.** Adoption without clear business case.
- **Lowest-common-denominator design.** Avoiding cloud-specific features means missing the cloud's value.
- **Underestimating cross-cloud network costs.** Surprise bills.
- **Operational debt.** Multiple clouds means multiple operations stacks; team can't be deep in any.
- **"Avoid lock-in" as primary goal.** Multi-cloud creates its own lock-in to portability layers.

## A reasonable position

For most companies:

- Pick one cloud; go deep
- Use best-of-breed services from one cloud
- Multi-cloud only when there's a specific business need
- Don't pre-pay for portability you won't use

For specific cases multi-cloud earns its place. For most companies, it's overhead pretending to be strategy.

## Further Reading

- [AwsFundamentals](AwsFundamentals) — Single-cloud focus
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — Cloud-native vs. cloud-portable
- [CloudMigrationStrategies](CloudMigrationStrategies) — Migration context
- [TerraformFundamentals](TerraformFundamentals) — Tool that handles multi-cloud
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index

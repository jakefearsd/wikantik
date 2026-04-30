---
canonical_id: 01KZHC6PVV4SBQM9R0F3T7K8Z8
title: DevOpsAndSreHub
type: hub
cluster: devops-sre
status: active
date: '2026-04-26'
summary: Index of pages on DevOps and SRE practices — CI/CD, deployment patterns,
  feature flags, on-call discipline, incident response, and the operational disciplines
  that keep production running.
tags:
- devops
- sre
- ci-cd
- hub
- operations
related:
- CloudPlatformsHub
- SoftwareEngineeringPracticesHub
- WebServicesAndApisHub
---
# DevOpsAndSre Hub

This cluster covers the operational discipline of running software in production — automated delivery, deployment patterns, observability, on-call practice, and the SRE core principles. The orientation is concrete: practices that make the difference between a stable production system and an unstable one.

## Delivery

- [DevOpsFundamentals](DevOpsFundamentals) — What DevOps actually changed; what it did not
- [CiCdPipelines](CiCdPipelines) — Pipeline design, stages, the patterns that scale
- [TrunkBasedDevelopment](TrunkBasedDevelopment) — Trunk vs. GitFlow, the case for trunk
- [GitWorkflows](GitWorkflows) — Branch strategies, merge vs. rebase, commit hygiene
- [MonorepoVsPolyrepo](MonorepoVsPolyrepo) — The trade-offs at scale
- [FeatureToggleManagement](FeatureToggleManagement) — Flag types, lifecycle, retirement
- [ReleaseEngineering](ReleaseEngineering) — Release artifacts, signing, rollback
- [ReleasePlanning](ReleasePlanning) — Sequencing, dependencies, communication

## Operations

- [OnCallPractices](OnCallPractices) — Rotation, escalation, blameless postmortems
- [RunbookAutomation](RunbookAutomation) — Runbooks that work; automating the recoverable
- [StatusPageBestPractices](StatusPageBestPractices) — Public status pages, customer communication
- [ToilReductionStrategies](ToilReductionStrategies) — Identifying and eliminating operational toil
- [ScheduledTaskManagement](ScheduledTaskManagement) — Cron, scheduled jobs, the patterns that survive

## Service infrastructure

- [ServiceMeshArchitecture](ServiceMeshArchitecture) — When the mesh is worth the complexity

## Adjacent clusters

- [Cloud Platforms Hub](CloudPlatformsHub) — Where DevOps practices land in cloud
- [Software Engineering Practices Hub](SoftwareEngineeringPracticesHub) — Code-side disciplines
- [Web Services and APIs Hub](WebServicesAndApisHub) — Service-level concerns

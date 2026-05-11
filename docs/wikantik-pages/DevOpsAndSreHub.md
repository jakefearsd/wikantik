---
summary: Index of pages on DevOps and SRE practices — CI/CD, deployment patterns,
  feature flags, on-call discipline, incident response, and the operational disciplines
  that keep production running.
date: '2026-04-26'
cluster: devops-sre
related:
- CloudPlatformsHub
- SoftwareEngineeringPracticesHub
- WebServicesAndApisHub
canonical_id: 01KZHC6PVV4SBQM9R0F3T7K8Z8
type: hub
title: DevOpsAndSreHub
tags:
- devops
- sre
- ci-cd
- hub
- operations
status: active
hubs:
- NetworkingHub
- SoftwareEngineeringPracticesHub
- DataEngineeringHub
- CloudPlatformsHub
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

## Operations and Resiliency

- [OnCallPractices](OnCallPractices) — Rotation, escalation, blameless postmortems
- [RunbookAutomation](RunbookAutomation) — Runbooks that work; automating the recoverable
- [StatusPageBestPractices](StatusPageBestPractices) — Public status pages, customer communication
- [ToilReductionStrategies](ToilReductionStrategies) — Identifying and eliminating operational toil
- [ScheduledTaskManagement](ScheduledTaskManagement) — Cron, scheduled jobs, the patterns that survive
- [Auto Scaling Strategies](AutoScalingStrategies) — Horizontal vs. Vertical, predictive scaling, and cost control
- [Health Check Patterns](HealthCheckPatterns) — Liveness, readiness, and deep-health checks in distributed systems

## Observability Implementation

Technical standards for monitoring and insight across the project ecosystem.

- [Observability and Monitoring Blueprint](ObservabilityAndMonitoringBlueprint) — Unified standard for OTel, Prometheus, and Grafana
- [Monitoring and Alerting](MonitoringAndAlerting) — The architecture of insight: metrics, logs, and traces
- [AI Observability in Production](AiObservabilityInProduction) — Monitoring LLM drift, safety, and evaluation metrics

## Infrastructure and Tooling

- [Kubernetes Basics](KubernetesBasics) — Pods, Deployments, Services, and the K8s object model
- [Docker Deployment](DockerDeployment) — Containerizing applications for portable production
- [Secrets Management](SecretsManagement) — Storing and rotating credentials in a secure pipeline
- [Rate Limiting and Throttling](RateLimitingAndThrottling) — Protecting services from resource exhaustion
- [ServiceMeshArchitecture](ServiceMeshArchitecture) — When the mesh is worth the complexity
- [Container Security](ContainerSecurity) — Hardening the runtime and the image supply chain

## Adjacent clusters

- [Cloud Platforms Hub](CloudPlatformsHub) — Where DevOps practices land in cloud
- [Software Engineering Practices Hub](SoftwareEngineeringPracticesHub) — Code-side disciplines
- [Web Services and APIs Hub](WebServicesAndApisHub) — Service-level concerns

---
date: '2026-04-26'
summary: Index of pages on cloud platform topics — AWS specifics, multi-cloud strategy,
  cloud-native patterns, the services that matter, and the operational disciplines
  that keep cloud spend and reliability under control.
cluster: cloud-platforms
related:
- DevOpsAndSreHub
- DistributedSystemsHub
- WebServicesAndApisHub
canonical_id: 01KQEKBKX2QVEW6716V6Y8N5QT
title: CloudPlatformsHub
type: hub
tags:
- cloud
- aws
- platform
- hub
- infrastructure
hubs:
- NetworkingHub
- DevOpsAndSreHub
- DataEngineeringHub
status: active
---
# CloudPlatforms Hub

This cluster covers cloud computing platforms — the patterns, services, and operational disciplines that make cloud workloads run well. The orientation is decision-oriented: which cloud services are the right tool for which job, and the trade-offs that come with each choice.

## Foundations

- [AwsFundamentals](AwsFundamentals) — Core AWS services, IAM, VPC, the mental model
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — 12-factor revisited; designing for the cloud-native model
- [TerraformFundamentals](TerraformFundamentals) — Infrastructure as code, state management, modules

## Compute models

- [ServerlessArchitecture](ServerlessArchitecture) — Where serverless fits and where it doesn't
- [AwsLambdaPatterns](AwsLambdaPatterns) — Lambda design patterns, cold starts, layers

## Data and storage

- [CloudDatabases](CloudDatabases) — Managed RDS vs. NoSQL services vs. self-managed
- [CloudStorageOptions](CloudStorageOptions) — S3 patterns, EBS, EFS, the storage classes
- [CdnArchitecture](CdnArchitecture) — Edge caching, invalidation, dynamic content at the edge

## Operations

- [CloudMonitoring](CloudMonitoring) — Metrics, logs, traces, alarms in the cloud context
- [CloudMigrationStrategies](CloudMigrationStrategies) — Lift-shift vs. re-architect, the realistic patterns
- [CloudDisasterRecovery](CloudDisasterRecovery) — RTO/RPO, multi-region, backup strategies
- [MultiCloudStrategies](MultiCloudStrategies) — Why multi-cloud is harder than it sounds

## Security and compliance

- [CloudSecurityFundamentals](CloudSecurityFundamentals) — IAM, network security, secrets management
- [CloudComplianceFrameworks](CloudComplianceFrameworks) — SOC 2, HIPAA, PCI in the cloud context

## Adjacent clusters

- [DevOps and SRE Hub](DevOpsAndSreHub) — Operating cloud workloads
- [Web Services and APIs Hub](WebServicesAndApisHub) — APIs running on cloud platforms

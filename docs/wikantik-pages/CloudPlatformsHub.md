---
date: '2026-05-10'
summary: AWS, Azure, GCP specifics, multi-cloud strategy, cloud-native patterns, and
  ROI frameworks for keeping cloud spend under control.
cluster: cloud-platforms
related:
- DevOpsAndSreHub
- DistributedSystemsHub
- WebServicesAndApisHub
- CdnArchitecture
canonical_id: 01KQEKBKX2QVEW6716V6Y8N5QT
type: hub
title: Cloud Platforms Hub
status: active
hubs:
- NetworkingHub
- DevOpsAndSreHub
- DataEngineeringHub
tags:
- cloud
- aws
- azure
- gcp
- platform
- hub
- infrastructure
---
# CloudPlatforms Hub

This cluster covers cloud computing platforms — the patterns, services, and operational disciplines that make cloud workloads run well. The orientation is decision-oriented: which cloud services are the right tool for which job, and the trade-offs that come with each choice.

## Platform Foundations

- [AwsFundamentals](AwsFundamentals) — Core AWS services, IAM, VPC, the mental model
- [AzureFundamentals](AzureFundamentals) — Enterprise integration, Entra ID, and Azure AI
- [GcpFundamentals](GcpFundamentals) — Global VPCs, BigQuery, and ML specialization
- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — 12-factor revisited; designing for the cloud-native model

## Adoption and Maturity Models

- [AwsMaturityModel](AwsMaturityModel) — **New**: Phased guide from Landing Zones to Autonomous Governance
- [GcpMaturityModel](GcpMaturityModel) — **New**: Phased guide from Shared VPCs to AI-Native Operations
- [CloudMigrationStrategies](CloudMigrationStrategies) — The 6 R's: Lift-shift vs. re-architect

## Financial Engineering (FinOps)

- [CloudRoiFramework](CloudRoiFramework) — Framework for predictive economics and ROI lifecycle
- [CloudCostOptimization](CloudCostOptimization) — Technical levers: Spot orchestration and egress mitigation
- [CapacityPlanning](CapacityPlanning) — CPU/Memory requests, IOPS, and throughput modeling

## Operational Disciplines

- [TerraformFundamentals](TerraformFundamentals) — Infrastructure as code, state management, modules
- [CloudMonitoring](CloudMonitoring) — Metrics, logs, traces, alarms in the cloud context
- [CloudDisasterRecovery](CloudDisasterRecovery) — RTO/RPO, multi-region, backup strategies
- [MultiCloudStrategies](MultiCloudStrategies) — Why multi-cloud is harder than it sounds

## Compute Models

- [ServerlessArchitecture](ServerlessArchitecture) — Where serverless fits and where it doesn't
- [AwsLambdaPatterns](AwsLambdaPatterns) — Lambda design patterns, cold starts, layers

## Data and Storage

- [CloudDatabases](CloudDatabases) — Managed RDS vs. NoSQL services vs. self-managed
- [CloudStorageOptions](CloudStorageOptions) — S3 patterns, EBS, EFS, the storage classes
- [CdnArchitecture](CdnArchitecture) — Edge caching, invalidation, dynamic content at the edge

## Security and Compliance

- [CloudSecurityFundamentals](CloudSecurityFundamentals) — IAM, network security, secrets management
- [CloudComplianceFrameworks](CloudComplianceFrameworks) — SOC 2, HIPAA, PCI in the cloud context

## Adjacent Clusters

- [DevOps and SRE Hub](DevOpsAndSreHub) — Operating cloud workloads
- [Web Services and APIs Hub](WebServicesAndApisHub) — APIs running on cloud platforms

---
status: active
date: '2026-05-10'
summary: 'Google Cloud Platform mental model: BigQuery serverless data warehouse,
  Vertex AI, GKE, global VPCs, and why GCP leads in AI/ML and data-intensive workloads.'
tags:
- gcp
- google-cloud
- bigquery
- machine-learning
- data-analytics
type: article
canonical_id: 01KR88FWK96SF9XVZSSXA6FP67
cluster: cloud-platforms
related:
- CloudPlatformsHub
- CloudCostOptimization
- AwsFundamentals
- AzureFundamentals
- MultiCloudStrategies
title: GCP Fundamentals
---
# GCP Fundamentals

Google Cloud Platform (GCP) has evolved from a "developer-focused niche" into a profit-making enterprise power (17%+ margins in 2026). It holds ~14% market share but leads the market in specialized **AI/ML** and **Data Analytics** performance.

## The "Google Scale" Differentiator

GCP was built from the tools Google uses internally (Borg, Spanner, Colossus). This means their networking is traditionally faster (global VPCs) and their managed services (GKE) are often considered the most stable in the market.

## Core Services Mapping

| Service | AWS Equivalent | What it is |
|---------|----------------|------------|
| **Compute Engine** | EC2 | Virtual machines |
| **VPC (Global)** | VPC (Regional) | Private network with global reach |
| **Cloud IAM** | IAM | Identity and access |
| **Cloud Storage** | S3 | Object storage |
| **Cloud SQL** | RDS | Managed MySQL, Postgres, SQL Server |
| **BigQuery** | Redshift | Serverless data warehouse (Best-of-breed) |
| **Cloud Functions** | Lambda | Serverless functions |
| **Google Kubernetes Engine (GKE)** | EKS | The gold standard for managed K8s |
| **Vertex AI** | SageMaker | Unified AI/ML platform |

## Networking: Global by Default

Unlike AWS, where VPCs are regional and require "Transit Gateways" to talk efficiently across regions, GCP VPCs are **Global**. A single VPC can span all Google regions. This significantly reduces the architectural complexity of multi-region applications.

## Data and ML Leadership

**BigQuery** is GCP's "killer app." It is truly serverless (no clusters to manage) and separates compute from storage. In 2026, it is the primary reason enterprises adopt GCP for their data lake/warehouse.

**Vertex AI** provides a unified interface for the Gemini family of models and custom training, often outperforming SageMaker in "time-to-first-inference" for developers.

## Cost and Adoption ROI

Google offers **Committed Use Discounts (CUDs)** that are often more flexible than AWS RIs. However, data egress costs from BigQuery and specialized ML training costs can scale rapidly if not monitored.

**Best Fit**: High-scale analytics, container-heavy workloads (GKE), and "AI-First" startups or divisions within larger enterprises.

## Further Reading
- [CloudPlatforms Hub](CloudPlatformsHub)
- [MultiCloudStrategies](MultiCloudStrategies)
- [AwsFundamentals](AwsFundamentals)
- [AzureFundamentals](AzureFundamentals)

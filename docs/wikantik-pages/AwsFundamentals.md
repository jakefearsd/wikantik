---
canonical_id: 01KQ0P44M7KAV7YS4D1M261D3F
title: AWS Fundamentals
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: The AWS services that matter for typical applications — EC2, VPC, IAM, S3,
  RDS, Lambda, the foundational pieces — and the mental model for navigating the
  hundreds of services without getting lost.
tags:
- aws
- cloud
- ec2
- s3
- iam
related:
- CloudNativeApplicationDesign
- TerraformFundamentals
- ServerlessArchitecture
- AwsLambdaPatterns
- CloudSecurityFundamentals
hubs:
- CloudPlatforms Hub
---
# AWS Fundamentals

AWS has 200+ services. Most applications use a small subset; the rest are noise unless you have a specific need. This page covers the core services and the mental model for understanding the catalog.

## The core dozen

The services most workloads touch:

| Service | What it is |
|---------|-----------|
| **EC2** | Virtual machines |
| **VPC** | Private networks |
| **IAM** | Identity and access |
| **S3** | Object storage |
| **RDS** | Managed relational databases |
| **DynamoDB** | Managed key-value/document store |
| **Lambda** | Serverless functions |
| **API Gateway** | HTTP/REST/WebSocket entry point |
| **CloudWatch** | Metrics, logs, alarms |
| **Route 53** | DNS |
| **CloudFront** | CDN |
| **SQS / SNS** | Queues and topics |

Knowing these well covers most use cases. The other 188 services are specializations — you'll learn them when you need them.

## IAM is the foundation

Every AWS interaction is gated by IAM (Identity and Access Management). Users, roles, policies, permissions.

The model:
- **Users** represent people; have credentials.
- **Roles** are assumed by services or users for specific work.
- **Policies** describe permissions (what action on what resource).
- **Trust policies** describe who can assume a role.

Best practice: workloads use roles, not user credentials. EC2 instances have instance profiles; Lambda functions have execution roles. Code calling AWS doesn't need keys — it gets temporary credentials from the role.

## VPC: the network

A VPC is a private network in AWS. By default each region has one; you can have many.

Inside:
- **Subnets** (public, private, isolated) — IP ranges in availability zones
- **Security groups** — instance-level firewall rules
- **NACLs** — subnet-level firewall rules
- **Internet Gateway** — public internet access for public subnets
- **NAT Gateway** — outbound internet for private subnets

Most application architecture decisions land in VPC: which subnets, what security group rules, how do services talk to each other.

## EC2 vs. Lambda vs. Fargate

The compute decision:

- **EC2**: virtual machines you manage. Most flexible; most operational work.
- **Fargate (ECS/EKS)**: container compute without managing EC2. You define container; AWS runs it.
- **Lambda**: function as a service. Truly serverless; AWS manages everything below the function.

For typical applications, the trend is away from EC2 toward Fargate or Lambda. EC2 still wins for: long-running stateful workloads, GPU/special-hardware, tight control over the runtime, predictable steady-state load.

## S3 patterns

S3 is the universal AWS storage. Cheap, durable, scales to petabytes. Common patterns:

- **Static asset hosting**: with CloudFront in front
- **Backups**: lifecycle to Glacier for cost
- **Data lakes**: parquet files, queried by Athena or Redshift Spectrum
- **Application data**: user uploads, generated documents
- **Build artifacts**: signed JARs, container images (via ECR which uses S3)

Storage classes are a real choice: Standard for hot data, Standard-IA for warm, Glacier tiers for cold. Lifecycle rules automate the transition.

See [CloudStorageOptions](CloudStorageOptions).

## RDS vs. DynamoDB vs. Aurora

The database decision:

- **RDS**: managed PostgreSQL, MySQL, etc. Familiar relational; full SQL.
- **Aurora**: AWS's clone of MySQL/PostgreSQL with cloud-native storage. Similar API, different economics, better at scale.
- **DynamoDB**: NoSQL key-value/document. Auto-scaling; pay per request; very different access patterns.

For a typical web app, RDS PostgreSQL is the right default. DynamoDB wins for high-scale, simple-access-pattern workloads. Aurora wins for relational workloads that have outgrown standard RDS.

See [CloudDatabases](CloudDatabases).

## Cost: where the bill comes from

Most AWS bills are dominated by:

1. **Compute** (EC2 hours, Lambda invocations × duration)
2. **Data transfer** (egress to internet, cross-AZ traffic)
3. **Storage** (S3, EBS, RDS)
4. **Specific premium services** (NAT Gateway, ALB, KMS)

Surprises are usually data transfer (cross-region or out-to-internet) or NAT Gateway (per-GB charges add up).

## Common failure patterns

- **Storing credentials in code or config files.** Use IAM roles.
- **Open security groups (0.0.0.0/0:22).** SSH open to internet; bots find it within hours.
- **Single-AZ deployments for production.** AZ failure = downtime.
- **No cost monitoring.** Surprises at end of month.
- **Lift-and-shift without refactoring.** "Cloud" without "cloud-native" — still expensive on-prem patterns.
- **Treating AWS as a single service.** Each service has its own quirks; learn the ones you use deeply.

## Further Reading

- [CloudNativeApplicationDesign](CloudNativeApplicationDesign) — How to design for cloud
- [TerraformFundamentals](TerraformFundamentals) — IaC for AWS
- [ServerlessArchitecture](ServerlessArchitecture) — Lambda-centric design
- [AwsLambdaPatterns](AwsLambdaPatterns) — Lambda specifics
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — IAM, encryption, network security
- [CloudPlatforms Hub](CloudPlatforms+Hub) — Cluster index

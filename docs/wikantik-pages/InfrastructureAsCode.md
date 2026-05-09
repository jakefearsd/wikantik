---
cluster: cloud-platforms
canonical_id: 01KQ0P44R4GVG4MQVMMEVADRKY
title: Infrastructure as Code
type: article
tags:
- cloud
- terraform
- pulumi
- devops
- iac
status: active
date: 2025-05-15
summary: Technical guide to Infrastructure as Code (IaC). Covers declarative vs imperative paradigms, state management, and multi-cloud orchestration.
auto-generated: false
---

# Infrastructure as Code (IaC): Systems Automation

IaC is the practice of managing and provisioning infrastructure through machine-readable definition files, rather than manual hardware configuration or interactive web consoles.

## 1. Declarative vs. Imperative Paradigms

*   **Declarative (Terraform, CloudFormation):** You define the **End State** (e.g., "I want 3 servers and a database"). The tool calculates the "diff" and applies the necessary changes.
    *   *Benefit:* Idempotent and easier to reason about in large scales.
*   **Imperative (Pulumi, AWS CDK):** You write **Code** (TypeScript, Python) that describes the steps to create resources.
    *   *Benefit:* Full power of programming (loops, conditionals, testing libraries).

## 2. State Management

The tool must know what exists in the cloud to calculate changes.
*   **The State File:** A JSON document mapping your code to real resource IDs. 
*   **State Locking:** Essential for team collaboration. Use a remote backend (S3 + DynamoDB) to prevent two developers from applying changes simultaneously, which leads to state corruption.

## 3. Concrete Implementation: Terraform

Terraform uses HCL (HashiCorp Configuration Language).
```hcl
resource "aws_s3_bucket" "data" {
  bucket = "my-secure-data-bucket"
  
  versioning {
    enabled = true
  }

  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        sse_algorithm = "AES256"
      }
    }
  }
}
```
*   **Modules:** Reusable blocks of code (e.g., a "Standard VPC Module") that enforce organizational standards.

## 4. Policy as Code (PaC)

Integrate security into the IaC pipeline.
*   **Sentinel / OPA:** Run automated checks before `apply`. 
*   **Concrete Rule:** *Reject any terraform plan that creates an S3 bucket with public-read permissions.*

---
**See Also:**
- [Configuration Management](ConfigurationManagement) — Managing the settings inside the infrastructure.
- [Cloud Networking](CloudNetworking) — The primary target for IaC.
- [Cloud Cost Optimization](CloudCostOptimization) — Tracking resources created by code.

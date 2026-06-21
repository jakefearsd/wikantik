---
date: '2026-04-26'
summary: The foundations of cloud security — IAM, encryption, network security, secrets
  management, and the operational practices that prevent the most common cloud security
  incidents.
cluster: cloud-platforms
related:
- AwsFundamentals
- CloudComplianceFrameworks
- VulnerabilityManagement
- WebApplicationFirewalls
canonical_id: 01KQ0P44NGWEMX0BF3Y86T9WYR
type: article
title: Cloud Security Fundamentals
status: active
hubs:
- CloudPlatformsHub
- AuthenticationAndAuthorizationHub
tags:
- cloud-security
- iam
- encryption
- secrets-management
- shared-responsibility
---
# Cloud Security Fundamentals

Cloud security is shared responsibility — the cloud provider secures the platform; you secure what you run on it. Most cloud security incidents come from customer mistakes, not cloud-provider failures. Misconfigured S3 buckets, exposed credentials, overly permissive IAM, missing encryption.

This page covers the foundations: IAM, encryption, network security, secrets, and the operational practices.

## Shared responsibility

The provider secures:
- Physical infrastructure
- Hypervisor / host OS
- Network infrastructure
- Service-level availability and patching

You secure:
- Your applications and data
- IAM and access controls
- Network configuration (security groups, VPC)
- Encryption keys and key management
- Secrets management
- Operating systems on EC2 instances (less for managed services)

The boundary varies by service. Lambda: provider does more. EC2: customer does more. Understand the boundary for each service you use.

## IAM is the foundation

Most cloud security failures involve IAM mistakes. The principles:

### Least privilege

Grant only permissions that are needed. Refuse "AdministratorAccess" unless genuinely required.

```json
{
    "Effect": "Allow",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::my-bucket/data/*"
}
```

Specific service, specific action, specific resource. Not `s3:*` and not `*:*`.

### Roles, not users

Workloads should authenticate via roles, not user credentials. EC2 has instance profiles; Lambda has execution roles; ECS has task roles. Code calling AWS gets temporary credentials from the role.

This is dramatically more secure than embedded user credentials.

### MFA everywhere

Every human IAM user with console access should have MFA. The root user especially. The cost is minimal; the risk reduction is large.

### Separate accounts for separation of concerns

Production, staging, dev, security tools, billing — separate accounts. Cross-account access via roles. Limits blast radius of compromise.

AWS Organizations lets you manage many accounts coherently.

## Encryption

### At rest

All data at rest should be encrypted. Most managed services encrypt by default; some don't and need explicit configuration.

- S3: SSE-S3 or SSE-KMS
- EBS: encrypted volumes
- RDS: encrypted at rest
- DynamoDB: encrypted by default
- EFS: encrypted at rest

The choice between provider-managed keys and customer-managed (KMS) keys is real:
- Provider-managed: easier; one less thing to lose
- Customer-managed: more control; can revoke access; required by some compliance regimes

### In transit

All data in transit should be encrypted with TLS 1.2+.

Most cloud services support this; some require explicit configuration. Check that your application is using TLS, not falling back to plaintext.

### Key management

KMS (AWS) handles encryption keys at scale. CMKs (Customer Master Keys) encrypt data keys; data keys encrypt actual data.

Key practices:
- Rotate keys (KMS handles automatic rotation for managed keys)
- Restrict key access via IAM
- Enable key usage logging via CloudTrail
- Plan for key compromise (you can't decrypt without the key)

## Network security

### Security groups

Instance-level firewall. Default deny; allow specific traffic.

```
Inbound: TCP 443 from ALB security group only
Outbound: TCP 443 to anywhere
```

The reference-by-security-group pattern (ALB SG can talk to backend SG) is more secure than CIDR blocks for internal traffic.

### NACLs

Subnet-level firewall. Stateless. Used for broad rules; security groups for specific.

### Public subnets

Limit what's in public subnets — only load balancers and bastion hosts. Application servers and databases in private subnets.

### NAT Gateway

For private subnets that need outbound internet (downloads, API calls). Doesn't allow inbound; private resources stay private.

### VPC endpoints

For accessing AWS services without going through the internet. S3 endpoint, DynamoDB endpoint, etc. Faster and more secure than route through internet.

## Secrets management

### Don't put secrets in code

Database passwords, API keys, encryption keys. Never committed to git.

### Don't put secrets in environment variables (raw)

Lambda environment variables, ECS task definitions. These are stored unencrypted in cloud control plane. Reference Secrets Manager / Parameter Store instead.

### Use a secrets service

- AWS Secrets Manager
- AWS Parameter Store
- HashiCorp Vault
- Kubernetes Secrets (with encryption)

Application reads secrets at startup or on-demand. Secrets are rotated; service handles the mechanics.

## Logging and monitoring

### CloudTrail (AWS)

Audit log of all API calls. Enable; ship to a separate account for tamper-resistance.

### VPC Flow Logs

Network traffic logs. Useful for incident investigation.

### Application logs

Structured; centralized; not in CloudTrail.

### GuardDuty (AWS) / equivalent

Anomaly detection. Catches known-bad patterns (compromised credentials, crypto-mining, data exfiltration). Enable.

### Config

Tracks configuration changes. Useful for compliance and post-incident review.

## Common cloud security failures

### Public S3 buckets

The classic. Bucket made public for some reason; sensitive data exposed. Use:
- Bucket policies that deny public access
- S3 Block Public Access at account level
- Tools that scan for public buckets (AWS Config, third-party)

### Exposed access keys

Access keys in code or in public git repos. Bots scan GitHub continuously for AWS keys.

Use:
- IAM roles instead of user keys
- git-secrets (pre-commit hook)
- GitHub secret scanning
- Rotate keys; investigate exposed keys immediately

### Over-permissioned IAM

Too-broad policies because narrowing is hard. The fix:
- IAM Access Analyzer (suggests policy refinements)
- Review policies regularly
- Lock down by default; broaden when needed

### Misconfigured security groups

0.0.0.0/0:22 (SSH from anywhere). Bots find these; brute-force or use leaked keys. Fix:
- Limit SSH to specific IPs (VPN, office network)
- Use SSM Session Manager instead of SSH
- Bastion hosts for legitimate access

### Outdated dependencies

Vulnerable libraries, vulnerable AMIs, vulnerable base images. Scan continuously; patch promptly.

## Common failure patterns

- **Treating cloud as more secure by default.** It can be, but only with deliberate configuration.
- **Relying on perimeter.** Defense in depth; assume breach.
- **No audit logging.** Investigation impossible.
- **No anomaly detection.** Compromise goes unnoticed for months.
- **Slow patching.** Time-to-patch on critical CVEs matters.
- **No incident response plan.** Surprise during the incident.

## Further Reading

- [AwsFundamentals](AwsFundamentals) — Service context
- [CloudComplianceFrameworks](CloudComplianceFrameworks) — Compliance angle
- [VulnerabilityManagement](VulnerabilityManagement) — Operational security
- [WebApplicationFirewalls](WebApplicationFirewalls) — Edge security
- [CloudPlatforms Hub](CloudPlatformsHub) — Cluster index

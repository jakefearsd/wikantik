---
canonical_id: 01KQ0P44NB405YK1AM109H79XY
title: Cloud Compliance Frameworks
type: article
cluster: cloud-platforms
status: active
date: '2026-04-26'
summary: SOC 2, HIPAA, PCI-DSS, GDPR, and the cloud compliance landscape — what each
  requires, how cloud providers help, and the work that remains for you regardless.
tags:
- compliance
- soc2
- hipaa
- pci-dss
- gdpr
related:
- CloudSecurityFundamentals
- AwsFundamentals
- SecurityComplianceFrameworks
- VulnerabilityManagement
hubs:
- CloudPlatforms Hub
---
# Cloud Compliance Frameworks

Compliance frameworks define security and operational requirements for specific industries or use cases. Cloud providers help by being compliant themselves and by providing tools that aid customer compliance, but the customer's compliance work is real and unavoidable.

This page covers the major frameworks and the cloud-specific aspects.

## SOC 2

The most-asked-about framework. SOC 2 is an attestation about controls around security, availability, processing integrity, confidentiality, and privacy.

Two types:
- **Type 1**: controls existed at a point in time
- **Type 2**: controls existed and operated effectively over a period (typically 6-12 months)

Type 2 is what customers usually want.

### What SOC 2 requires

The Trust Services Criteria — broadly:
- Logical and physical access controls
- Change management
- Incident response
- Vendor management
- Backup and recovery
- Monitoring and logging
- Risk management

The criteria are general; the implementation is specific to your environment. SOC 2 is more about "do you have a process and follow it" than "do you do specific things."

### Cloud help

AWS, GCP, Azure are themselves SOC 2 compliant. They publish reports. Customers use the providers' compliance as part of their own (the controls the provider handles count toward your compliance).

### What you still do

- Implement application-level controls
- Document processes
- Audit logs and monitoring
- Vendor management for third-party services
- Annual external audit

Most companies use a compliance platform (Vanta, Drata, Secureframe) to automate evidence collection.

## HIPAA

US healthcare data regulation. Required for handling Protected Health Information (PHI).

### What HIPAA requires

- Encryption (at rest and in transit)
- Access controls
- Audit logging
- Business Associate Agreements (BAAs) with all vendors
- Breach notification procedures
- Risk assessments
- Workforce training

### Cloud help

AWS, GCP, Azure offer HIPAA-eligible service lists. Sign a BAA with the provider; only use HIPAA-eligible services for PHI.

The list is not all services. Specific limitations: some services can't be used for PHI; some can with specific configurations.

### What you still do

- Configure services correctly (encryption, access controls)
- BAAs with all subcontractors
- Application-level audit logging
- Breach detection and notification
- Workforce training

HIPAA violations are expensive. Be deliberate.

## PCI-DSS

Payment Card Industry Data Security Standard. Required for handling credit card data.

### What PCI-DSS requires

12 requirements covering:
- Network security
- Encryption
- Vulnerability management
- Access controls
- Monitoring
- Information security policy

Levels (1-4) based on transaction volume; higher levels need external audits.

### Cloud help

Major clouds are PCI-compliant. Use compliant services; configure correctly.

### What you still do

- Tokenize or vault payment data (don't store raw card numbers)
- Use a payment processor (Stripe, etc.) — they handle most PCI
- Network segmentation
- Application security
- Annual assessment

Most modern apps avoid most PCI scope by using payment processors. The processor handles cards; you handle tokens.

## GDPR

EU privacy regulation. Applies if you handle EU resident data.

### What GDPR requires

- Lawful basis for data processing
- Data subject rights (access, deletion, portability)
- Privacy by design
- Data Protection Officer (in some cases)
- Breach notification (72 hours)
- Data Processing Agreements (DPAs) with vendors

### Cloud help

Major clouds offer DPAs. They're compliant as data processors; you're the controller and bear most obligations.

### What you still do

- Build deletion workflows
- Build data export workflows
- Document lawful bases
- Privacy notices
- Cookie consent (separate from GDPR but related)
- Data residency if required

GDPR is broad and the EU enforcement is real (4% of global revenue penalties).

## Other frameworks

- **ISO 27001**: international information security standard. Similar level of scope to SOC 2.
- **FedRAMP**: US government cloud authorization. Required for federal customers.
- **CCPA**: California privacy law; similar to GDPR but narrower.
- **PIPEDA**: Canadian privacy law.
- **Industry-specific**: NERC CIP (energy), GLBA (financial), FERPA (education), etc.

## The compliance practice

Compliance is ongoing, not one-time:

### Continuous monitoring

Most frameworks require ongoing monitoring, not just point-in-time audits. Tools (Vanta, Drata) automate evidence collection.

### Risk assessments

Annual or more frequent. Identify risks; document mitigations.

### Vendor management

Every third-party service has compliance implications. Track them; renew BAAs and DPAs.

### Audits

External audits annually for most frameworks. Document everything; provide evidence on request.

### Updates

Frameworks evolve. SOC 2 added new criteria; GDPR has clarifications; PCI-DSS 4.0 changes things. Stay current.

## Common patterns

### Use compliance-aware cloud services

Provider services that are listed as in-scope for your framework. Avoid services that aren't.

### Centralized logging

Most frameworks require audit logging. Centralize from day one; you'll need it for evidence.

### Encryption everywhere

Encrypt at rest and in transit by default. Some frameworks require it; even when not required, it's good practice.

### Access reviews

Quarterly or annual reviews of who has access to what. Required by most frameworks.

### Separation of environments

Production separate from dev/staging. Required for some frameworks.

## Common failure patterns

- **"We're compliant because the cloud is compliant."** No. Cloud helps but you do most of the work.
- **Compliance theater.** Going through motions without actual security improvement.
- **Surprise during audit.** Ongoing monitoring prevents this.
- **No documentation.** Compliance requires evidence; if it's not written down, it didn't happen.
- **Treating compliance as one-time.** It's ongoing.
- **Compliance instead of security.** Compliant systems can still be insecure; security goes beyond compliance.

## Further Reading

- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Security foundations
- [AwsFundamentals](AwsFundamentals) — Cloud context
- [SecurityComplianceFrameworks](SecurityComplianceFrameworks) — Adjacent topic
- [VulnerabilityManagement](VulnerabilityManagement) — Operational practice
- [CloudPlatforms Hub](CloudPlatforms+Hub) — Cluster index

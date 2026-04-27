---
canonical_id: 01KQ0P44W607X28HSHE50HJAS9
title: Security Compliance Frameworks
type: article
cluster: security
status: active
date: '2026-04-26'
summary: SOC 2, ISO 27001, NIST CSF, FedRAMP, and related security frameworks —
  what each requires, how they relate, and the practical work of getting and maintaining
  certifications.
tags:
- compliance
- soc2
- iso27001
- nist
- fedramp
related:
- CloudComplianceFrameworks
- VulnerabilityManagement
- SecurityAwarenessTraining
---
# Security Compliance Frameworks

Security compliance frameworks define standards for how organizations protect information. Each has slightly different focus; some overlap heavily.

This page covers the major frameworks and the practical work of compliance.

## SOC 2

The most-asked-for framework in B2B SaaS. AICPA-developed. Five Trust Service Criteria:

- **Security**: foundational; required for all
- **Availability**: optional; for SLA commitments
- **Processing integrity**: optional
- **Confidentiality**: optional
- **Privacy**: optional

Most companies start with Security only.

Type 1: controls existed at a point in time. Type 2: controls operated effectively over a period (typically 12 months).

Type 2 is what enterprise customers want. Type 1 is sometimes a starting point.

Process: external auditor; ongoing evidence collection; report.

## ISO 27001

International equivalent of SOC 2. Required by some non-US enterprises.

Defines an Information Security Management System (ISMS). Annex A controls (114 of them) describe specific safeguards.

Process: external auditor; certification; annual surveillance audits; full re-certification every 3 years.

Compared to SOC 2: more prescriptive about specific controls; documentation-heavy; international recognition.

## NIST Cybersecurity Framework (CSF)

US-government-developed. Voluntary; widely adopted.

Five functions:
- **Identify**: assets, risks
- **Protect**: controls
- **Detect**: monitoring
- **Respond**: incident response
- **Recover**: business continuity

Less prescriptive than ISO 27001; useful as an organizing framework.

Common as an organizational tool even when not seeking certification.

## FedRAMP

US federal cloud authorization. Required for federal customers.

Levels:
- **Low**: low-impact data
- **Moderate**: most government data
- **High**: classified or critical data

Process: extensive; expensive; takes years for new vendors. Worth it for federal market access; not worth it otherwise.

## HIPAA

US healthcare data regulation. See [CloudComplianceFrameworks](CloudComplianceFrameworks).

## PCI-DSS

Payment card industry. See [CloudComplianceFrameworks](CloudComplianceFrameworks).

## GDPR

EU privacy regulation. See [CloudComplianceFrameworks](CloudComplianceFrameworks).

## CIS Controls

Center for Internet Security. Prioritized controls for common security weaknesses.

20 controls organized into Implementation Groups (IG1, IG2, IG3) by maturity.

Useful as a practical checklist independent of certification.

## How frameworks relate

Significant overlap. SOC 2 + ISO 27001 share most controls. NIST CSF organizes; specific frameworks implement.

Mapping tools (Vanta, Drata, Secureframe) show which controls satisfy which frameworks. One control often satisfies multiple frameworks.

For organizations seeking multiple certifications, the marginal cost of adding frameworks decreases — most controls already in place.

## The practical work

### Initial certification

1. **Gap assessment**: where are you vs. requirements
2. **Remediation**: fix gaps
3. **Documentation**: policies, procedures, evidence
4. **Operating period**: SOC 2 Type 2 needs 6+ months of evidence; ISO needs less
5. **Audit**: external auditor reviews
6. **Report / certificate**: result

Initial certification: 6-12 months for SOC 2 Type 2; longer for ISO.

### Ongoing maintenance

- Quarterly access reviews
- Annual risk assessments
- Continuous monitoring
- Incident response exercises
- Vendor management
- Training (employee awareness)

Maintenance is the larger workload long-term.

### Annual audits

External auditor renews the certification. Process:
- Provide evidence
- Auditor walkthroughs
- Findings (if any)
- Remediation
- Final report

Compliance platforms (Vanta, Drata, Secureframe) automate most evidence collection.

## Common controls across frameworks

The controls that show up everywhere:

- Logical access controls
- MFA
- Encryption at rest and in transit
- Backup and recovery
- Change management
- Vulnerability management
- Incident response
- Vendor risk management
- Physical security (sometimes)
- Personnel security (background checks, training)

If you implement these well, you're 80% toward compliance with most frameworks.

## Compliance platforms

The tools that make modern compliance practical:

- **Vanta**: dominant; SOC 2 first; expanding
- **Drata**: comparable
- **Secureframe**: comparable
- **Sprinto, Tugboat Logic, others**: alternatives

These integrate with cloud providers, HR systems, identity providers. Continuously check for compliance; collect evidence automatically.

For B2B SaaS pursuing SOC 2, one of these is essentially required to be efficient.

## When to pursue compliance

### Customer-driven

Enterprise customers ask for SOC 2. If they're large enough to matter, you'll get the certification.

### Industry-driven

Healthcare → HIPAA. Payments → PCI. Government → FedRAMP. Fixed costs of doing business in regulated industries.

### Voluntary / leadership

Some companies pursue certifications proactively for trust signals. Less driven by specific customer demands.

## Common failure patterns

- **Treating compliance as one-time.** It's ongoing; certifications expire.
- **Compliance theater.** Documents say one thing; reality is different.
- **Compliance instead of security.** Compliant systems can still be insecure.
- **Pursuing too many frameworks.** Each has overhead; pick what customers actually need.
- **No compliance platform.** Manual evidence collection is brutal.

## A reasonable starter

For B2B SaaS:

1. Customer demand drives framework choice
2. Pursue SOC 2 Type 2 first (most common ask)
3. Use a compliance platform from day one
4. Implement controls deliberately; not just for the audit
5. Maintain continuously; don't sprint before audits

For other industries: pursue what's actually required.

## Further Reading

- [CloudComplianceFrameworks](CloudComplianceFrameworks) — Cloud-specific
- [VulnerabilityManagement](VulnerabilityManagement) — Adjacent practice
- [SecurityAwarenessTraining](SecurityAwarenessTraining) — Required by most frameworks

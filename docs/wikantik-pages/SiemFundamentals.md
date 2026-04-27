---
canonical_id: 01KQ0P44WDEPWFAG4XJM8JN0GY
title: SIEM Fundamentals
type: article
cluster: security
status: active
date: '2026-04-26'
summary: What SIEM tools actually do — log aggregation, correlation, alerting — and
  the cases where they're worth deploying vs. simpler alternatives.
tags:
- siem
- security-monitoring
- splunk
- log-aggregation
- detection
related:
- VulnerabilityManagement
- CloudMonitoring
- WebApplicationFirewalls
---
# SIEM Fundamentals

Security Information and Event Management (SIEM) systems aggregate logs from across an organization, correlate events, and detect security issues. The market is large (Splunk, IBM QRadar, Microsoft Sentinel, Elastic Security, Datadog Security Monitoring). The use cases are real but the cost is also real.

This page covers what SIEMs actually do and when they make sense.

## What a SIEM does

### Log aggregation

Collect logs from many sources: firewalls, servers, applications, identity providers, cloud platforms.

Without aggregation, security investigation requires logging into each system individually. With aggregation, all logs in one searchable place.

### Normalization

Logs from different systems have different formats. SIEMs normalize them into a common schema. A "user login" event from Active Directory and from Okta look the same in the SIEM.

### Correlation

Combine events from multiple sources to detect patterns. "User logged in from Russia 10 minutes after logging in from California" — uses logs from both VPN and identity provider.

### Detection rules

Pre-built or custom rules that match attack patterns. "Multiple failed logins followed by successful login" might indicate credential stuffing.

### Alerting

When rules match, alert. Either notification, ticket, or workflow.

### Forensics

When an incident happens, investigate. Search across all logs; reconstruct the attack timeline.

## When SIEM is worth deploying

### Compliance requirements

PCI-DSS, HIPAA, others require log retention and monitoring. SIEM checks the box.

### Mature security team

Has analysts who can write rules, tune detections, investigate alerts.

### Attack surface that requires aggregation

Many systems generating security-relevant events. Without aggregation, no one sees the whole picture.

### Threat hunting

Proactive search for indicators of compromise. SIEM is the search tool.

## When it's not worth it

### Small organization

Few systems; few security events. Cloud provider's native logging is enough.

### No analysts

A SIEM without people to investigate alerts is just a log archive. Worse: noisy alerts that nobody triages.

### Pure cloud-native shop

Cloud provider security tools (AWS GuardDuty, Azure Sentinel, Google Chronicle) often suffice.

## The major SIEMs

### Splunk

The dominant traditional SIEM. Powerful query language; extensive ecosystem; expensive.

### Microsoft Sentinel

Cloud-native; integrated with Azure/M365. Strong if you're already in the Microsoft ecosystem.

### IBM QRadar

Enterprise SIEM. Common in regulated industries.

### Elastic Security

Built on Elasticsearch. Open-source core; commercial enterprise features.

### Datadog Security Monitoring

For Datadog customers. Less SIEM-focused but covers many use cases.

### Sumo Logic, Exabeam, others

Various market positions.

For most cloud-native modern shops, the cloud provider's offering or Elastic is the practical choice.

## Detection rules

### Rule types

- **Signature**: specific patterns ("login from this IP indicates malware")
- **Threshold**: count-based ("more than 10 failed logins in 5 minutes")
- **Behavioral**: deviation from baseline ("user accessing files they never accessed before")
- **Correlation**: combinations across sources ("VPN login + impossible travel from previous login")

### Rule tuning

The hardest part. Out-of-the-box rules generate noise. Tuning:
- Remove false positives
- Adjust thresholds
- Add context (whitelisted IPs, expected patterns)

A well-tuned rule fires rarely but accurately. A noisy rule gets ignored.

### Detection-as-code

Some teams version-control detection rules in git. Test rules; review changes; deploy through CI/CD.

This is mature SIEM operations.

## Costs

SIEMs are expensive at scale. Costs come from:

- **Ingestion**: per GB of logs
- **Retention**: per GB stored
- **Licensing**: enterprise contracts can be six to seven figures
- **Operations**: people running it, tuning it

Most organizations underestimate cost. The economics matter.

Specifically log volume: high-cardinality logs (every HTTP request, every database query) become expensive fast. Aggregate or filter before sending; don't ingest everything.

## SOAR adjacent

Security Orchestration, Automation, and Response (SOAR) tools take SIEM output and automate response. Phishing email reported → SOAR auto-quarantines, scans, etc.

For mature security operations, SOAR + SIEM together is the platform. For most teams, SIEM alone is plenty.

## Cloud-native alternatives

For pure cloud-native shops:

- **AWS GuardDuty**: managed threat detection. Lighter than SIEM but covers many use cases.
- **AWS Security Hub**: aggregates findings from multiple AWS services.
- **Microsoft Sentinel**: cloud-native SIEM.
- **GCP Chronicle**: Google's SIEM.

These integrate with cloud platforms; less operational overhead than self-hosted SIEM.

## Common failure patterns

- **SIEM without analysts.** Logs collected; nobody investigates.
- **All rules; no tuning.** Alert fatigue.
- **Too much log volume.** Cost spirals; signal lost in noise.
- **No rule maintenance.** Rules age; miss new attacks.
- **Compliance-only deployment.** Box checked; security not improved.
- **Ignoring native cloud tools.** Build expensive SIEM on top when cloud provider has it cheaper.

## A reasonable approach

For organizations considering SIEM:

1. Determine if you actually need it (compliance, scale, attack surface)
2. If pure cloud, evaluate native tools first
3. Pick a SIEM that fits the org (Splunk for enterprise; Elastic for budget-conscious; cloud-native for cloud shops)
4. Start with limited scope; expand as detection capability grows
5. Invest in analysts and rule tuning, not just the platform
6. Measure: are you catching real threats?

## Further Reading

- [VulnerabilityManagement](VulnerabilityManagement) — Adjacent operational practice
- [CloudMonitoring](CloudMonitoring) — Operations parallel
- [WebApplicationFirewalls](WebApplicationFirewalls) — Source of security events

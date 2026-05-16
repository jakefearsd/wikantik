---
canonical_id: 01KQEKGDFH9MR7HT4KP35HZYGS
title: Security Incident Response
type: article
cluster: security
status: active
date: '2026-05-24'
tags:
- security
- incident-response
- forensics
- breach
- runbook
summary: Operational framework for security breaches, moving from detection to containment and forensic recovery using the NIST SP 800-61 model.
auto-generated: false
---
# Security Incident Response

A security incident is a race against an adversary. The goal of Incident Response (IR) is to minimize the **Blast Radius** and **Time to Containment**. Without a practiced runbook, teams often perform "Eradication" before "Containment," which tips off the attacker and leads to data destruction.

## The NIST IR Lifecycle

1. **Preparation:** Hardening the environment and establishing communication channels (e.g., an out-of-band Signal group).
2. **Detection & Analysis:** Identifying the "Indicator of Compromise" (IoC) via SIEM or EDR.
3. **Containment:** Isolating the affected systems. **This is the priority.**
4. **Eradication:** Removing the attacker's persistence (backdoors, web shells).
5. **Recovery:** Restoring services from known-clean backups.
6. **Post-Incident Activity:** The "Blameless Post-Mortem" and root cause analysis.

## Containment Strategies

| Type | Action | When to use? |
|---|---|---|
| **Network Isolation** | Modify security groups to block all ingress/egress. | Active C2 (Command & Control) beaconing. |
| **Account Suspension** | Revoke all OAuth tokens and force password resets. | Compromised credentials / Phishing. |
| **System Shutdown** | Hard power-off of virtual machines. | Last resort; destroys volatile memory (RAM). |
| **Process Suspension** | `kill -STOP` the malicious PID. | Preserves memory for forensic analysis. |

## Forensic Evidence Collection

Never "clean" a compromised server until you have captured the **volatile evidence**.
1. **Memory Dump:** Capture RAM for rootkits and fileless malware.
2. **Disk Image:** Bit-for-bit copy of the storage.
3. **Network Logs:** VPC Flow Logs or PCAPs showing data exfiltration.

```bash
# Example: capturing a memory dump on Linux (using LiME)
insmod lime.ko "path=/mnt/usb/mem_dump.bin format=raw"
```

## The "Golden Hour" Checklist

- **Identify the IC:** One person is the Incident Commander; everyone else reports to them.
- **Open a War Room:** Create a dedicated, locked Slack/Teams channel.
- **Snapshot Everything:** Before you touch the system, take a cloud provider snapshot of the VM.
- **Log Everything:** Assign a "Scribe" to record every decision and timestamp. This is critical for legal compliance and post-mortems.

## Common Failure: The "Whack-a-Mole" Error
The most common IR failure is killing an attacker's process as soon as you see it. Advanced persistent threats (APTs) often have 3-4 different persistence mechanisms. If you kill one, they will use the others to hide deeper. 
**Fix:** Observe and map the attacker's footprint before initiating a coordinated wipe of all entry points simultaneously.

## Metrics that Matter
- **MTTD (Mean Time to Detect):** How long was the attacker inside before you noticed?
- **MTTC (Mean Time to Contain):** How long did it take to stop the bleeding once noticed?
- **MTTR (Mean Time to Recover):** Total time until the system was back to a "clean" production state.

## Further Reading
- [ThreatModeling](ThreatModeling) — Proactive security design to prevent incidents.
- [SecurityLoggingAndAuditTrails](SecurityLoggingAndAuditTrails) — What you need to have in place *before* the breach.
- [BlamelessPostMortems](BlamelessPostMortems) — Learning from the incident without finger-pointing.

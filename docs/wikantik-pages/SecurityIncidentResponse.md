---
title: Security Incident Response
type: article
cluster: security
status: active
date: '2026-04-25'
tags:
- security
- incident-response
- breach
- forensics
- runbook
summary: The phases of incident response (detect, contain, eradicate, recover,
  learn), the decisions you'll make under pressure, and the artefacts that
  make a 3am page survivable.
related:
- BlamelessPostMortems
- ThreatModeling
- IncidentResponse
- ApplicationSecurityFundamentals
hubs:
- Security Hub
---
# Security Incident Response

A security incident is when an attacker has done or is doing something they shouldn't be able to. The response is what you do between detection and a return to safe operation. Most teams handle their first real security incident badly because they've never run the drill — and security incidents happen at 3am with imperfect information.

This page is the working framework. For non-security incidents (outage, performance), see [IncidentResponse]; for the post-mortem, [BlamelessPostMortems].

## The five phases

NIST's model is broadly accepted:

1. **Preparation** — what you do before the incident.
2. **Detection and analysis** — figuring out something is wrong.
3. **Containment** — stopping the bleeding.
4. **Eradication and recovery** — removing the attacker; restoring.
5. **Post-incident** — learning, hardening.

Most amateur incident response goes from "detection" straight to "recovery" without proper containment. The result is the attacker still has access; the rebuild gets compromised; round two is worse than round one.

## Preparation: the work that pays off later

Things to have in place *before* you need them:

- **An incident response plan.** Who leads, who communicates, who decides. Written down. Practised.
- **Contact lists.** Phone numbers for the key people. Don't rely on email when email might be compromised.
- **Out-of-band communication.** Slack might be compromised. Have a backup (Signal group, phone tree).
- **Forensic snapshots / logging in place.** If the only audit trail is the system the attacker controls, you have nothing. Off-host immutable logs.
- **Pre-authorised actions.** "Take production offline" is a major decision; pre-authorise it under specific conditions so the on-call doesn't have to wake up the CEO.
- **Backup-restore tested.** Not just that backups exist; that they restore to working state. Ransomware tests this every day; many companies fail.
- **Legal and PR contacts.** Counsel for breach notification obligations; communications for external messaging.
- **Tabletop exercises.** Run scenarios. Even a 2-hour exercise per quarter changes how the team responds when the real thing happens.

## Detection: distinguishing real from false

Signals:

- Authentication anomalies (logins from unusual countries, impossible-travel patterns).
- Unusual outbound traffic (data exfiltration, C2 callbacks).
- Privilege escalation events.
- Configuration changes you didn't make.
- Endpoint detection (EDR) alerts.
- External notification (researcher, customer, law enforcement).

Most security operations have hundreds of alerts per day. The skill is triage: which require investigation now, which can wait, which are noise.

When in doubt: investigate. The cost of investigating false positives is much lower than the cost of missing a real incident.

## Containment: stop the bleeding

Once you've confirmed an incident, the priority is to limit damage. Concrete actions:

- **Isolate compromised systems.** Network segmentation; remove from production.
- **Rotate credentials.** Anything the attacker might have seen — passwords, API keys, SSH keys, OAuth tokens, session tokens.
- **Revoke active sessions.** Force re-auth.
- **Preserve evidence.** Before wiping, snapshot disk, memory, logs. The forensic value of a compromised system is highest before you "clean" it.
- **Disable affected accounts.** Not just suspected attackers; any account that might be involved.
- **Alert dependent services.** If your service is breached, downstream services that trusted you need to know.

Containment vs eradication: containment stops the attacker from doing more damage; eradication removes them. You contain first because you don't fully understand the scope yet.

The classic mistake: cleaning the obvious malware before discovering all the persistence mechanisms. The attacker is still there; the cleaning is theatre.

## Eradication and recovery

Once contained, build a complete picture:

- **Initial access** — how did they get in?
- **Persistence** — what mechanisms did they leave to come back?
- **Lateral movement** — what other systems did they touch?
- **Data accessed / exfiltrated** — what did they take?
- **Actions** — what did they do?

Forensic evidence drives this. EDR logs, network logs, authentication logs, command history. Tools: Velociraptor, KAPE, GRR for endpoint forensics; Wireshark / Zeek for network; YARA for malware identification.

Eradication: remove every persistence mechanism, every backdoor, every modified configuration. If unsure, rebuild from clean images. The cost of incomplete eradication is the attacker comes back through a mechanism you missed.

Recovery: bring services back. Verify clean state before each restoration. Monitor closely for the first weeks; the attacker may try to re-enter through new vectors.

## Communication

External communication is its own discipline. Key principles:

- **Get legal counsel involved early.** Breach notification rules vary (GDPR's 72 hours, US state-by-state). Counsel decides what's reportable when.
- **Be honest with affected users.** Cover-ups produce worse outcomes than transparent disclosure. Fact-pattern descriptions, not minimisation.
- **Coordinate with PR for external statements.** Customer communications, press, regulators all happen through agreed channels.
- **Maintain a single source of truth.** Internal "what we know" doc updated as facts emerge. Don't have parallel narratives in different chat threads.

Famous case studies (Equifax, SolarWinds, Maersk) all illustrate that the post-incident period is shaped as much by communications as by technical response. Plan for it.

## Specific incident classes

### Credential compromise

Indicator: legitimate-looking login from an unusual context. Or just a credential found on a paste site.

Response:

1. Force password reset on affected account.
2. Revoke active sessions.
3. Audit account activity since first suspected compromise.
4. Check for downstream actions (data accessed, configurations changed, secondary credentials minted).
5. Notify the user (likely required by law).

If the credential gave access to admin or service-account scope, treat as much wider incident.

### Ransomware

Indicator: files encrypted, ransom note. Often preceded by lateral movement and data exfiltration.

Response:

1. Isolate affected systems immediately. Disconnect from network.
2. Do not pay ransom without legal counsel — paying may violate sanctions in some jurisdictions.
3. Assume data was exfiltrated before encryption; this is now also a data breach.
4. Restore from backups (this is when "tested backups" becomes critical).
5. Forensic analysis of initial access vector.

Modern ransomware is increasingly "exfil first, encrypt second" — the encryption is leverage; the data theft is the primary monetisation.

### Insider threat

Indicator: legitimate user doing things outside normal scope; data access patterns mismatch role.

Response is harder because the user has legitimate credentials. Forensic investigation; HR involvement; legal consultation. Don't tip off the suspect; preserve evidence.

### Supply-chain compromise

Indicator: trusted dependency or vendor sends malicious update; or your build system is compromised and ships malicious code.

Response:

1. Determine which builds are affected.
2. Rebuild from known-clean components.
3. Notify customers / users of affected versions.
4. Audit your build pipeline for further compromise.

SolarWinds (2020) is the canonical example. The attack vector is "we trust a vendor to ship clean code"; defence requires reproducible builds, signed dependencies, transitive review.

### DDoS

Often used as cover for other attacks (the DDoS distracts ops; the real attack happens during the chaos). Treat as security incident, not just availability incident.

## Forensics: do and don't

Do:

- Take memory snapshots before reboot. Most useful evidence is in RAM.
- Take disk images before forensic analysis. Work on copies, not originals.
- Preserve chain of custody if law enforcement might be involved.
- Hash everything for integrity.

Don't:

- Reboot the compromised system unless you must. Volatile evidence is lost.
- Run software on the compromised system that wasn't there. Modify nothing.
- Delete the attacker's artefacts before analysis.

For most incidents, you'll work with an incident response firm (or your own dedicated team) for forensics. Knowing the basics lets you preserve evidence until they arrive.

## A standardised response runbook

Pre-built artefacts for the team to use during incidents:

- Slack templates for incident channels (#sec-inc-2026-04-25-suspicious-login).
- A roles checklist (Incident Commander, Investigator, Communicator, Scribe).
- A communication template for affected users.
- A contact list for legal, PR, leadership, vendors.
- The pre-authorised actions list.
- The forensic evidence collection checklist.

These artefacts, ready to use, are the difference between a 1-hour response and a 6-hour scramble.

## Metrics worth tracking

- **Mean time to detect (MTTD).** From compromise to detection. Lower is better.
- **Mean time to contain (MTTC).** From detection to containment. Lower is better.
- **Mean time to recover (MTTR).** From containment to back in operation.
- **Number of incidents per month/quarter.** Track but recognise that this is an outcome, not always your fault.

If MTTD is in days/weeks, your detection pipeline isn't catching attacks. Improve before optimising other metrics.

## Post-incident: actually learn

A blameless post-mortem after every significant incident. See [BlamelessPostMortems]. Specific to security:

- What detection signal would have caught this earlier?
- What architectural change would have prevented it?
- What capability gap surfaced (forensics, automation, communication)?
- Update the runbook with what you learned.
- Test the new defence with chaos / red team.

Action items must close. The next incident is partly defended by the lessons of the last.

## Further reading

- [BlamelessPostMortems] — post-incident discipline
- [ThreatModeling] — anticipate before responding
- [IncidentResponse] — non-security incident response
- [ApplicationSecurityFundamentals] — broader security context

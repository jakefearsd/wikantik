---
canonical_id: 01KQ0P44W5ZHHYJYP02JG08WZY
title: Security Awareness Training
type: article
cluster: security
status: active
date: '2026-04-26'
summary: What security awareness training actually accomplishes — phishing simulations,
  what works, what doesn't, and the cultural patterns that make security training
  effective vs. compliance theater.
tags:
- security-awareness
- phishing
- training
- security-culture
related:
- VulnerabilityManagement
- IdentityTheftProtection
- SecurityComplianceFrameworks
---
# Security Awareness Training

Most security incidents involve a human element: phishing, credential reuse, social engineering, accidental data exposure. Awareness training tries to teach humans to recognize and avoid these.

Done well, it works. Done poorly, it's compliance theater.

This page covers what actually works.

## What awareness training is for

Specific attack patterns to defend against:

### Phishing

Email or message that tricks users into clicking malicious links, providing credentials, or downloading malware. The dominant initial attack vector.

### Spear phishing

Targeted phishing aimed at specific individuals (CEO, finance team, IT admin). More personalized; harder to detect.

### Social engineering

Manipulation outside email: phone calls pretending to be IT support, USB drops in parking lots, tailgating into buildings.

### Credential reuse

Users using the same password across services. Breach of one site compromises others.

### Misconfiguration

Users sharing files publicly when they meant private; emailing sensitive data to wrong addresses.

## What works

### Phishing simulations

Send fake phishing emails to employees. Track who clicks. Provide immediate education to those who do.

Frequency: monthly is reasonable. Variety: different types of phish (credential capture, malware, financial fraud).

Outcomes:
- Click rate trends over time
- Specific user remediation
- Identification of high-risk groups

For most companies, this is the highest-impact training intervention.

### Targeted training based on role

Different roles face different risks:
- Engineers: malicious dependencies, leaked credentials
- Finance: invoice fraud, wire transfer scams
- Executives: spear phishing, business email compromise
- Customer service: social engineering for credentials

Generic training that treats everyone the same misses these.

### Real incident debriefs

When a real attack happens, share details (without blaming). "Last month, an attacker tried X. Here's how it worked. Here's how to recognize it."

Concrete examples beat abstract advice.

### Culture of reporting

People should report suspicious emails, weird behavior, accidental clicks. Fast.

Pre-requisites:
- No blame for honest mistakes
- Easy reporting (one click in email client)
- Visible response from security team

If reporting feels punitive, people hide problems.

## What doesn't work

### Annual compliance training

The 30-minute video everyone clicks through to satisfy SOC 2. Most people don't retain it.

Effective only if there's a compliance requirement and you're checking the box. Don't expect behavior change.

### Generic content

"Don't click suspicious links" — too vague to act on.

### Punishing users

Public shaming for clicking phishing simulations. People stop reporting; problems hide.

### Long sessions

Hour-long training sessions; people zone out. Short, frequent micro-trainings work better.

### Training only

Training without other controls. Even well-trained people make mistakes. Defense in depth: training + technical controls (MFA, email filtering, etc.).

## Specific topics that matter

### MFA

Use MFA everywhere. Authenticator app preferred over SMS. Hardware keys for high-value accounts.

The "MFA fatigue" attack: attacker triggers MFA prompts repeatedly until user approves. Train people to recognize this.

### Password manager

Use one. Don't share passwords. Don't reuse passwords. The password manager handles unique passwords without burdening memory.

### Email vigilance

- Check sender carefully (display name vs. actual address)
- Hover over links before clicking
- Be suspicious of urgency
- Verify out-of-band for sensitive requests (call the person)

### Wire transfer fraud

"CEO wants you to wire $100K to this account immediately." Always verify by another channel; never act on email alone for wire transfers.

### Public WiFi

Use VPN on untrusted networks. Don't enter credentials over coffee shop WiFi without it.

### Lost devices

Report immediately. Modern device management can remote-wipe.

### USB drives

Don't plug in unknown USB drives. The "USB drop in parking lot" attack is real.

## The compliance angle

Many frameworks (SOC 2, HIPAA, ISO 27001) require security awareness training. Annual minimum for most.

Compliance training and effective training overlap but aren't identical:
- Compliance: cover specific topics; document completion
- Effective: actually change behavior

Try to do both. Compliance training that's also effective is the goal; compliance theater that doesn't change behavior is the failure mode.

## Common failure patterns

- **Compliance theater.** Annual click-through; no behavior change.
- **Generic training.** Doesn't address specific role risks.
- **No metrics.** Don't know if it's working.
- **Punishing reports.** People stop reporting.
- **Training without controls.** Even trained people fail; need MFA, filtering, etc.
- **No phishing simulations.** Real attacks come from outside; simulated attacks teach better.

## A reasonable program

For most companies:

1. **Phishing simulations**: monthly; targeted; with immediate education for clickers
2. **Role-based training**: different content for engineers, finance, executives
3. **Real incident shares**: when something happens, debrief publicly
4. **Easy reporting**: one click in email client to report suspicious mail
5. **MFA enforcement**: technical control, not just training
6. **Annual compliance training**: meet requirements; don't expect behavior change from this

The combination of training + technical controls + culture is what reduces incidents.

## Further Reading

- [VulnerabilityManagement](VulnerabilityManagement) — Adjacent practice
- [IdentityTheftProtection](IdentityTheftProtection) — Personal version
- [SecurityComplianceFrameworks](SecurityComplianceFrameworks) — Frameworks that require training

---
canonical_id: 01KQ12YDX2678Q9PB2Q64FZCB6
title: Threat Modeling
type: article
cluster: security
status: active
date: '2026-04-25'
tags:
- security
- threat-modeling
- stride
- attack-tree
- security-design
summary: STRIDE, attack trees, and the threat-modeling habits that catch the
  vulnerabilities that show up in production — without turning into a
  bureaucratic dead document.
related:
- ApplicationSecurityFundamentals
- EncryptionFundamentals
- SecurityIncidentResponse
- ZeroTrustArchitecture
hubs:
- Security Hub
---
# Threat Modeling

Threat modeling is "what could go wrong, and what are we doing about it" — applied systematically. Done well, it's the highest-ROI security activity available: each hour spent finds vulnerabilities that would take days to find via penetration testing or to fix in production. Done as ceremony, it produces a 40-page document nobody reads and false confidence.

The difference is whether the team treats it as a thinking tool or a deliverable.

## The minimum useful threat model

Four questions, in this order:

1. **What are we building?** Diagram the system. Boxes for components, lines for trust boundaries, labels for the data that crosses them.
2. **What can go wrong?** What are the threats against each component and each boundary?
3. **What are we going to do about it?** For each significant threat, a mitigation, an acceptance, or a transfer.
4. **Did we do a good enough job?** Review with someone who didn't draw the diagram.

That's the whole framework. STRIDE, PASTA, attack trees — all elaborations on this. Don't overdo it.

## STRIDE: the most useful taxonomy

STRIDE classifies threats into six categories:

- **Spoofing** — pretending to be someone you aren't.
- **Tampering** — modifying data you shouldn't.
- **Repudiation** — denying you did something.
- **Information disclosure** — exposing data you shouldn't.
- **Denial of service** — making the system unavailable.
- **Elevation of privilege** — getting permissions you shouldn't have.

For each component in your diagram, walk through STRIDE. "Could an attacker spoof here? Tamper here? See what they shouldn't?" Most threats become obvious once you systematically ask.

Worked example for a "user uploads a file" feature:

- **Spoofing** — can the user upload as someone else? (Auth check on upload endpoint.)
- **Tampering** — can the user replace someone else's file? (Authorization on the storage path; signed URLs.)
- **Repudiation** — does the system log who uploaded what? (Audit trail.)
- **Info disclosure** — can the user read other users' files? (Same auth pattern.)
- **DoS** — can a user exhaust storage with huge files? (Per-user quota; max file size.)
- **EoP** — can a malicious file (uploaded executable, polyglot) escalate when processed? (Sandbox; never execute uploaded content; type validation; AV scan if applicable.)

Half the threats you'll discover in any feature are straightforward applications of STRIDE.

## Attack trees: when STRIDE isn't enough

For high-value assets, draw an attack tree. Root: the attacker's goal ("read all customer data"). Branches: ways to achieve it. Leaves: specific exploits.

```
Read all customer data
├── Compromise database directly
│   ├── Steal DB credentials
│   │   ├── Phish admin
│   │   ├── Exploit credential leak
│   │   └── Bribe insider
│   ├── SQL injection
│   └── Backup theft
├── Compromise application
│   ├── RCE in app server
│   ├── Privilege escalation via API
│   └── Stolen admin session
└── Compromise infrastructure
    ├── Cloud account takeover
    └── CI/CD pipeline compromise
```

For each leaf, estimate cost-to-exploit and existing mitigations. Aim defences at the cheapest leaves.

This is bigger work than STRIDE; reserve it for genuinely high-value targets (your crown-jewel database, your auth system, your payment flow). Don't draw an attack tree for every feature.

## The data-flow diagram

Your threat-model diagram has a specific shape:

- **Boxes for components** (services, databases, queues).
- **Lines for data flows**, labelled with what the data is.
- **Trust boundaries** drawn as dashed lines crossing the diagram. Anywhere a request crosses from less-trusted to more-trusted is a boundary.

Trust boundaries are where most vulnerabilities live. The boundary between the public internet and your API. The boundary between your API and your internal services. The boundary between your application and your database. Threats focus there.

Common omission: the developer who deploys the system is also a trust boundary. CI/CD compromise is a real and growing class of attacks; include the pipeline in the diagram.

## Adversaries, briefly

Threat models are stronger if you specify who the attacker is:

- **External unauthenticated** — anyone on the internet. Cheapest attacks; broadest scope.
- **External authenticated** — has an account. Can use it to probe further than unauthenticated.
- **Insider — employee** — has SSO. Different and often broader access.
- **Insider — contractor / vendor** — narrower access but often less audited.
- **Supply chain** — your CI/CD, your package registry, your container images.
- **Nation-state** — sophisticated, persistent. Most products don't need to model these; high-value targets do.

For each significant boundary, ask which adversary classes can cross it and what they can do once across.

## When in the lifecycle

Threat models are cheapest when done early — at design time, before code is written. Most expensive once deployed.

Practical cadence:

- **Initial design** — short threat-modelling session as part of the design review. Output: list of threats and mitigations baked into the design.
- **Major architectural changes** — re-do the relevant section.
- **Annually** — review existing threat models for currency.
- **Post-incident** — update the threat model with what you learned. The threat that caused the incident wasn't on it; why?

A "live" threat model is a wiki page that changes; a "dead" one is the original Word document nobody touched after launch.

## What goes wrong with threat modeling in practice

- **Too detailed.** A 40-component system has 240 STRIDE pairs; nobody finishes. Bound the scope.
- **Too abstract.** "Could be vulnerable to injection." That's a threat category, not a finding. Get specific.
- **Done by security alone.** Threat modeling without the engineers who build the thing produces irrelevant findings. The engineers must participate.
- **No follow-through.** Threats identified, never mitigated, never tracked. Attach each threat to a ticket; close the loop.
- **No re-modeling.** The system changed; the threat model didn't. Treat it as living documentation.
- **Solo activity.** Threat modeling alone misses what a fresh pair of eyes catches. Pair it.

## Tools

You don't need a tool. A whiteboard and a markdown file are the most useful threat-modeling tools. Some teams find these helpful:

- **OWASP Threat Dragon** — open source diagramming + threat tracking.
- **Microsoft Threat Modeling Tool** — older, Windows-focused, still useful.
- **IriusRisk, ThreatModeler** — commercial; suit large enterprises with compliance frameworks.
- **PyTM** — code-based threat modeling. Diagram-as-code; some teams like it.

For a 5-engineer team, Excalidraw + a Markdown file in the repo is usually enough. The discipline matters more than the tool.

## Threat modeling for AI / agentic systems

LLM-based systems have specific threat shapes that don't map cleanly onto STRIDE:

- **Prompt injection.** Attacker-controlled content in the prompt manipulates the model. Critical for any system that retrieves user-controlled content (RAG, browsing agents, tool-using agents).
- **Data exfiltration via the model.** Asking the model to repeat training data or summarise things it shouldn't. Important if your model has been exposed to sensitive data.
- **Jailbreaking** — bypassing the model's safety training. Often a chain of prompts.
- **Tool abuse.** An LLM-controlled agent calls a tool with malicious arguments. The tool is the attack surface.
- **Excessive agency.** An agent with broad permissions takes actions the user didn't intend. Permission scoping per step.

OWASP LLM Top 10 catalogues these. Worth reading; substantively expands STRIDE for agentic systems.

## A pragmatic checklist

For a typical feature design review, these questions catch most issues:

- Is every input validated? Where?
- Is every authentication/authorization decision made server-side?
- Is sensitive data encrypted at rest and in transit?
- Are secrets rotated and never logged?
- Are uploaded files / external data treated as untrusted?
- Are rate limits in place for any endpoint that does work?
- Is authorization checked at every layer or just the API gateway?
- Is there an audit trail for sensitive operations?
- What happens if a dependency is compromised?
- What happens if a credential leaks?

Yes/no answers for each. Anything "no" or "I don't know" → discuss in the review.

## Further reading

- [ApplicationSecurityFundamentals] — broader app-sec context
- [EncryptionFundamentals] — what crypto is for in your mitigations
- [SecurityIncidentResponse] — when threats become incidents
- [ZeroTrustArchitecture] — threat-modeling implications of zero-trust

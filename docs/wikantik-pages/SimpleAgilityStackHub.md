---
kg_include: true
summary: The Simple Agility stack — jakemon, Wikantik, Roller, Stockroom, and agents
  — what each owns and how they coordinate across one data backbone.
type: hub
date: '2026-10-24'
audience:
- humans
- agents
canonical_id: 01M09QF2RWTWZ89YPXX0SAHY1J
status: active
tags:
- simple-agility
- architecture
- jakemon
- wikantik
- roller
- stockroom
- agents
- hub
cluster: simple-agility
title: Simple Agility Stack Hub
related:
- SimpleAgilityAgentCoordination
- SimpleAgilityObservabilityPlane
- SimpleAgilityTelemetryContract
- SimpleAgilityCommerceDataFlow
- SimpleAgilityAttributionRFC
- SimpleAgilityFeedbackLoopPattern
- SimpleAgilityOnCallAutomation
- SimpleAgilityRoadmap
- SimpleAgilityDecisionLog
- JakemonHub
- WikantikPlatformHub
- RollerBlogPlatform
- StockroomHub
- SoftwareProjectsHub
- RollerHub
- HomeLabFleetAndIngress
verified_at: '2026-10-24T15:40:21.014087794Z'
verified_by: jakefear
---
# Simple Agility Stack Hub

**Simple Agility** is a small-business software stack that lets a company use Generative Artificial Intelligence (GenAI) in a handful of concrete capacities — Search Engine Optimization (SEO) first, proactive customer communication next, with more to follow. It is assembled from separately-developed pieces that already exist and share one data backbone. This hub is the entry point for any human or agent working on *any* piece of the stack: read it before deciding where a capability belongs.

> **Ownership principle (the one sentence to remember):** every product in the stack optimizes itself by **joining its own data against whatever the observability plane makes visible** — external signals (search engines, edge analytics) *and* the product's own error and behavioral events, aggregated back to it. Products do not build their own metrics stores, log stores, alerting, or third-party pollers; the plane does not build product logic.

## If you are an agent, start here

| You are about to… | Read | Why |
|---|---|---|
| Coordinate a cross-repo initiative, start an agent session, or check active tasks | [SimpleAgilityAgentCoordination](SimpleAgilityAgentCoordination) | Mandatory protocol and active ledger for coding agents (agy, Claude Code) |
| Implement inventory, marketplace orders, or financial reconciliation | [SimpleAgilityCommerceDataFlow](SimpleAgilityCommerceDataFlow) | End-to-end data flow contract: intake batch splits, 4 event types, marketplace accrual clearing, and COA mapping |
| Design marketing attribution or correlate blog spotlights with sales | [SimpleAgilityAttributionRFC](SimpleAgilityAttributionRFC) | RFC for tracking customer conversion from Roller to Stockroom via EPN, coded coupons, and Umami ([SA-12](SimpleAgilityDecisionLog)) |
| Add logging, metrics, health, or events to any product | [SimpleAgilityTelemetryContract](SimpleAgilityTelemetryContract) | The stack logs in **Elastic Common Schema (ECS)** JSON. The page covers the required fields, the `trace.id` rules, and the one mistake that damages the whole Loki instance. |
| Make a choice another product will have to live with (a format, a label, a repository boundary) | [SimpleAgilityDecisionLog](SimpleAgilityDecisionLog) | Check whether it is already decided; if you decide it, record it there |
| Bring a product onto the logging and labelling standard | [SimpleAgilityTelemetryAdoption](SimpleAgilityTelemetryAdoption) | Per-team checklists, the coordination rules, and status |
| Build a table for analytics, usage, or "user did X" | [SimpleAgilityObservabilityPlane](SimpleAgilityObservabilityPlane) § *The rule that decides where data lives* | Some such tables are correct and some are contract violations; the rule tells them apart |
| Poll a third-party API (search, analytics, anything) | [SimpleAgilityObservabilityPlane](SimpleAgilityObservabilityPlane) § *Requesting a new feed* | Products never poll; the plane captures and ships |
| Build a ranking, recommendation, or optimization loop | [SimpleAgilityFeedbackLoopPattern](SimpleAgilityFeedbackLoopPattern) | Stage ownership plus three mandatory properties that are not optional |
| Add an alert, a runbook, or an operational action | [SimpleAgilityOnCallAutomation](SimpleAgilityOnCallAutomation) | The alert→runbook binding convention and the scoped-surface requirement |
| Deploy a service, give it a port, or put it on the internet | [HomeLabFleetAndIngress](HomeLabFleetAndIngress) | One ingress (cloudflared on host `cloudflare`); origins serve plain HTTP on high ports; an origin's proxy must trust the connector's forwarded headers |
| Query the plane, find a dashboard, or see what is already collected | [JakemonHub](JakemonHub) | Endpoints, labels (`host`, `deployment`, `service`, `service_instance`, `job`, `instance`) and the dashboard inventory. Check `host` before diagnosing production. |
| Look for a capability the contracts describe | [SimpleAgilityRoadmap](SimpleAgilityRoadmap) | Several are **not built**; this says which, in what order, and what blocks them |
| Verify a product actually conforms | `bin/simple-agility-conformance.sh` (Wikantik repo) | Machine-checked, not a prose checklist |

## Components

| Role in the stack | Implemented by | Repository | Status |
|---|---|---|---|
| **Observability plane** — capture, retention, alerting, source-generic signals | **jakemon** | `~/source/jakemon` | Live; release `v1.0.3` (2026-10-24); central stack on [`inference`](HostInference) since 2026-10-09 |
| **Knowledge and content** — agentic wiki; software documentation, support content, SEO content; also the runbook store for on-call | **Wikantik** | `~/source/jspwiki` | Live (`wiki.wikantik.com`, docker1); more than one instance expected |
| **Customer communication** — blog/newsletter for proactive customer contact; dedicated reselling weblog; native agent publishing via API tokens and `/api/mcp` ([SA-7](SimpleAgilityDecisionLog)) | **Roller** (Apache Roller fork) | see [RollerHub](RollerHub) | Live v0.2.0 on docker2 (`roller-prod`); blogger-agent migration in progress |
| **Inventory and listing preparation** — intake, inventory, per-route pricing and triage, channel-neutral listing drafts, barcode-driven physical tracking, eBay integration, and operator-assisted customer spotlights ([SA-8](SimpleAgilityDecisionLog), [SA-11](SimpleAgilityDecisionLog)) | **Stockroom** | `~/source/stockroom` | Phases 1–7 built (intake, pricing, listing prep, physical WMS, sales, eBay adapter, photo ID); operator acceptance underway. Hub: [StockroomHub](StockroomHub); contracts: [SimpleAgilityCommerceDataFlow](SimpleAgilityCommerceDataFlow), [ResellingHub](ResellingHub) |
| **Small business accounting** — inventory COGS, marketplace fee reconciliations, sales tax, and P&L reporting; decoupled from personal retirement planning ([SA-10](SimpleAgilityDecisionLog), [SA-11](SimpleAgilityDecisionLog)) | *dedicated component* (`simple-agility-accounting`) | *future repository* | Planned; event schemas and reconciliation rules specified on [SimpleAgilityCommerceDataFlow](SimpleAgilityCommerceDataFlow) |
| **Agent coordination plane** — cross-repo architectural governance, dated ADRs, standard repo headers, and active task ledger ([SA-9](SimpleAgilityDecisionLog)) | **Wikantik Coordination Hub** | `~/source/jspwiki` + `~/source/wiki-content-agent` | Live on [SimpleAgilityAgentCoordination](SimpleAgilityAgentCoordination) and [SimpleAgilityDecisionLog](SimpleAgilityDecisionLog) |
| **Agent runtime** — scheduling, ledgers, circuit breakers, blast-radius limits and agent identities for the agents that close the loops (SEO actuator, on-call agent) | *not yet built* — a **stack-level** component, deliberately **not** part of jakemon | — | Planned ([roadmap](SimpleAgilityRoadmap) phase 4) |

jakemon, Wikantik, Roller and Stockroom are **sub-concepts of the Simple Agility domain**: each has its own entry page for its internals ([JakemonHub](JakemonHub), [WikantikPlatformHub](WikantikPlatformHub), [RollerHub](RollerHub), [StockroomHub](StockroomHub)), and each of those pages carries a "part of the Simple Agility stack" callout back here. The hierarchy is expressed by **links, not by cluster nesting** — the existing `jakemon`, `wikantik-development` and `web-properties` clusters stay where they are; a cluster restructure is available later if the company framing solidifies.

## Core contracts, data flows, and backlog

1. **[SimpleAgilityObservabilityPlane](SimpleAgilityObservabilityPlane)** — what the plane owns, what it deliberately does not, the rule that decides where data lives, how products get data **out** of it (push feeds versus pull queries), how to request a new feed, and its roadmap toward an Operational Data Store (ODS) with Service Level Objective (SLO) / Service Level Agreement (SLA) Key Performance Indicators (KPIs).
2. **[SimpleAgilityTelemetryContract](SimpleAgilityTelemetryContract)** — how a product gets data **into** the plane: the `/metrics` endpoint, ECS JSON logs with `trace.id` for correlation, events-as-log-lines, and the health endpoint. Read this before writing a line of logging in a new product.
3. **[SimpleAgilityFeedbackLoopPattern](SimpleAgilityFeedbackLoopPattern)** — the sense → signal → decide → act → measure loop, who owns each stage, the three properties every product loop must have, Wikantik's Content Intelligence as the worked example, and a Roller sketch.
4. **[SimpleAgilityOnCallAutomation](SimpleAgilityOnCallAutomation)** — how alerts will reach an agent, the alert-to-runbook binding, the escalation ladder (scoped surfaces first; ssh/docker permitted with concurrent human escalation), agent identities, and the agentic interfaces every product must expose.
5. **[SimpleAgilityCommerceDataFlow](SimpleAgilityCommerceDataFlow)** — the end-to-end lifecycle from inbound goods intake and landed cost allocation, to physical WMS inventory states, marketplace order events, settlement clearing, and general ledger journal entries.
6. **[SimpleAgilityRoadmap](SimpleAgilityRoadmap)** — **what is not built yet**, sequenced, with owners, blockers and acceptance tests. The contracts above describe intent; this one describes reality. Consult it before going looking for a capability.

Alongside them, the **[SimpleAgilityDecisionLog](SimpleAgilityDecisionLog)** records each decision that binds more than one product, such as the logging standard and the label names, together with its reasons.

## Standing assumptions

- **Instanced per tenant.** A Simple Agility deployment is the *whole* stack — its own jakemon, its own Wikantik instance(s), its own Roller — per customer. Inside one deployment, `service`, `service_instance` and `site_host` distinguish products, product instances and web properties. Multi-tenant observability (shared Prometheus/Loki with per-tenant isolation) is **not** designed and no product should pre-empt it by putting tenant identifiers into its telemetry.
- **One tenant may run several instances of one product.** Feeds are shipped point-to-point to a named instance. Metrics and logs carry the product-instance identity because there the plane is genuinely shared. The identity is `service_instance="<product>-<env>"` (`wiki-prod`), attached by the plane ([SA-5](SimpleAgilityDecisionLog)) alongside `deployment` and `host`. The two rules pull in opposite directions on purpose — the plane page explains why.
- **Signal logic shifts left over time.** jakemon currently computes some source-generic SEO signals (`visibility/opportunities.py`); as products mature that logic tends to move into the product that has the domain context, while the *capture* stays in the plane.
- **Prod is authoritative for content; the wiki is canonical for these documents.** They are not mirrored into the Wikantik repository corpus. The Wikantik `CLAUDE.md` and jakemon `CLAUDE.md` point here.

## See also

- [SoftwareProjectsHub](SoftwareProjectsHub) — the portfolio these projects sit in
- [ContentIntelligenceDesign](ContentIntelligenceDesign) — the first fully-built Simple Agility feedback loop
- [SearchVisibilityExporter](SearchVisibilityExporter) — the plane's search-engine capture pipeline

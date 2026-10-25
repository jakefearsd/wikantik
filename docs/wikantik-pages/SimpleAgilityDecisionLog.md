---
tags:
- simple-agility
- architecture
- decisions
- adr
- logging
- ecs
- telemetry
- jakemon
- wikantik
- roller
- stockroom
- agents
type: design
status: active
summary: 'Dated, reasoned decisions binding Simple Agility products: ECS logging,
  commerce reconciliation, outreach on Roller, agent coordination, project context.'
title: Simple Agility Decision Log
verified_at: '2026-10-24T15:40:21.014087794Z'
cluster: simple-agility
audience:
- humans
- agents
date: '2026-10-24'
related:
- SimpleAgilityStackHub
- SimpleAgilityAgentCoordination
- SimpleAgilityCommerceDataFlow
- SimpleAgilityAttributionRFC
- SimpleAgilityTelemetryContract
- SimpleAgilityObservabilityPlane
- SimpleAgilityOnCallAutomation
- SimpleAgilityRoadmap
- ProjectContextSeparationDesign
- RollerHub
- ResellingHub
- JakemonHub
- StructuredLogging
verified_by: jakefear
canonical_id: 01M4SSHSWHGTAK2HY5Z4TYY18K
---
# Simple Agility Decision Log

This page records the decisions that bind more than one product in the [Simple Agility stack](SimpleAgilityStackHub). Read it before you design something that another product, or the observability plane, will have to agree with. Logging formats, label names and repository boundaries are all decided here.

**How to use this page.**

- Each entry states the decision, why it was made, and what it changes. The **normative rule** is on the page linked from each entry. This log only records that the rule was decided, and when.
- A decision is changed by adding a new entry that supersedes it. Old entries are never edited away, so the reasoning stays visible.
- Status is tracked on [SimpleAgilityRoadmap](SimpleAgilityRoadmap), and each team's instructions for SA-5 and SA-6 are on [SimpleAgilityTelemetryAdoption](SimpleAgilityTelemetryAdoption). A decision here can be made long before every product conforms to it.

## Index

| ID | Date | Decision | Binds | Normative page |
|---|---|---|---|---|
| SA-13 | 2026-10-24 | A project's cross-project context is derived from the pages it cites; hierarchy is expressed by citations, never by cluster nesting | all coding agents, every product | [ProjectContextSeparationDesign](ProjectContextSeparationDesign) |
| SA-12 | 2026-10-17 | Marketing attribution and customer sales flywheel (Roller to Stockroom) | Roller, Stockroom, JakeMon, accounting | [SimpleAgilityAttributionRFC](SimpleAgilityAttributionRFC) |
| SA-11 | 2026-10-14 | Commerce and financial reconciliation lifecycle (Inbound to General Ledger) | Stockroom, accounting | [SimpleAgilityCommerceDataFlow](SimpleAgilityCommerceDataFlow) |
| SA-10 | 2026-10-14 | Small business accounting is a dedicated suite component; WealthView remains personal finance | accounting, WealthView | [SimpleAgilityStackHub](SimpleAgilityStackHub) |
| SA-9 | 2026-10-14 | Cross-repo agent coordination and architectural decisions run through the wiki's Agent Coordination Ledger | all coding agents | [SimpleAgilityAgentCoordination](SimpleAgilityAgentCoordination) |
| SA-8 | 2026-10-14 | Stockroom-to-Roller customer spotlight flywheel is operator-assisted | Stockroom, Roller | [ResellingHub](ResellingHub), [RollerHub](RollerHub) |
| SA-7 | 2026-10-14 | Roller is the canonical customer communication platform; customer outreach blogs use Roller native agent publishing | Roller, Blogger Agent | [RollerHub](RollerHub) |
| SA-6 | 2026-10-12 | Elastic Common Schema (ECS) is the stack's logging standard | every product | [SimpleAgilityTelemetryContract](SimpleAgilityTelemetryContract) §2 |
| SA-5 | 2026-10-12 | Product instances are named `<product>-<env>` in a `service_instance` label | every product, jakemon | [SimpleAgilityTelemetryContract](SimpleAgilityTelemetryContract) §1 |
| SA-4 | 2026-10-12 | jakemon may hold application code only when it serves the whole stack and belongs with the plane | jakemon | [JakemonHub](JakemonHub) |
| SA-3 | 2026-08-18 | Every alert names its runbook | jakemon, Wikantik | [SimpleAgilityOnCallAutomation](SimpleAgilityOnCallAutomation) |
| SA-2 | 2026-08-18 | The stack deployment is the `deployment` label, never `instance` | jakemon | [SimpleAgilityObservabilityPlane](SimpleAgilityObservabilityPlane) |
| SA-1 | 2026-08-18 | Telemetry goes to the plane; product inputs stay in the product | every product | [SimpleAgilityObservabilityPlane](SimpleAgilityObservabilityPlane) |

## SA-13: Project context is derived from citations, not declared

**Decided 2026-10-24.**

**Decision.** A citation from a project page to a shared page is that project's adoption of it. A project *is* its cluster (`roller`, `jakemon`, `stockroom`, `wikantik-development`). The session-start briefing (`get_briefing` on the knowledge MCP, `/api/briefing` over HTTP) scoped to a project cluster delivers, in order: the project's own pages ranked by how many sibling pages link to them; then the shared pages the project's pages cite ("Contracts this project cites"), ranked by how many project pages cite them; and finally — only as one-line pointers — relevant sections from other projects ("Elsewhere in the wiki"). Nothing is declared for this to work: no `project:` field, no pattern tag, no hub registry. Project hierarchy is expressed by citations, **never** by nesting project clusters under `simple-agility`; the roadmap's "cluster restructuring" question is closed.

**Why.**

- **Eight simulated briefings on 2026-10-23 showed the defect directly.** Standing context was chosen by recency, so a Stockroom phase plan, an incident runbook and a login-help page surfaced as "what every session should know"; a `roller` briefing could not reach `SimpleAgilityTelemetryContract` at all, because it is not a `roller` member; strict scoping made that worse; and Stockroom had no cluster of its own, its designs and plans being parked in `simple-agility`.
- **Adoption is a link, so it is cheap and local.** Roller adopts ECS logging by writing `RollerTelemetryHandoffSpec → SimpleAgilityTelemetryContract`. No one edits the contract, no hub grows, and no registry is maintained across four repositories.
- **The decision page stays stable while its blast radius changes.** A fourth product's hub cites the contract and inherits it in its first briefing; the contract is untouched.
- **Nesting would spend the one sub-cluster level** (ADR-0009 in the Wikantik repository) on an org chart instead of topics, and the citation tier makes it unnecessary.

**Consequences.**

- The contract-first rule on [SimpleAgilityAgentCoordination](SimpleAgilityAgentCoordination) gains teeth: a decision reaches a project's briefing **only** if a project page cites it. When you adopt a contract, link to it.
- Every repository's agent header gains a session-start call: `get_briefing(clusters=["<project cluster>"], prompt="<your first task>")`.
- Stockroom now has [StockroomHub](StockroomHub) and the `stockroom` cluster; its eleven designs and plans moved there on 2026-10-24, and `simple-agility` holds only cross-project pages.
- This log's **Binds** column is the declared form of the relationship; the number of clusters citing a page is the observed form. *Declared minus observed* names every decision a project is bound by but has never cited — a drift check to build later, not part of this decision.
- Normative page: [ProjectContextSeparationDesign](ProjectContextSeparationDesign). Wikantik-side record: ADR-0011 in the Wikantik repository.

## SA-12: Marketing attribution and customer sales flywheel (Roller to Stockroom)

**Decided 2026-10-17 (Architectural RFC).**

**Decision.** Customer outreach on Roller weblogs will be attributed to realized inventory sales in Stockroom and the general ledger using four structured vectors: (1) eBay Partner Network (EPN) affiliate tracking URLs with structured `customid` payloads (`roller_<weblog>_<slug>_<sku>`); (2) marketplace coded coupons (e.g. `ROLLER5`) extracted from order promotional metadata (`appliedPromotions`); (3) direct sovereign inquiries and Stripe Payment Links (`client_reference_id`) bypassing marketplace fees; and (4) a zero-code single-quantity SKU temporal heuristic joining Umami outbound `cta-click` events with Stockroom sales in Grafana. Stockroom's `sale` schema will be extended in a future phase with nullable attribution columns (`attribution_channel`, `attribution_campaign`, `promo_code`, `attribution_confidence`).

**Why.**

- **Marketplace sanitization.** Third-party marketplaces (eBay, Amazon) strip third-party UTM query parameters from seller order fulfillment APIs. Passing UTMs alone creates an attribution blind spot where click-throughs are logged in Umami but lost before order ingestion in Stockroom.
- **Affiliate fee rebate.** EPN tracking provides transaction-level reporting while paying a 1% to 4% commission rebate, offsetting platform selling fees.
- **Sovereign margin expansion.** Steering blog readers to direct inquiries or sovereign checkout saves marketplace final value fees (~13.25% on eBay), providing significant margin expansion on high-value estate inventory.
- **Zero-code immediate visibility.** Because reselling inventory consists of unique, single-quantity items with custom SKUs, temporal correlation between blog outbound clicks and marketplace purchases provides immediate directional attribution without requiring application code changes.

**Consequences.**

- The normative specification, mathematical models, and schema migrations are defined on [SimpleAgilityAttributionRFC](SimpleAgilityAttributionRFC).
- Stockroom and Roller maintain decoupled development roadmaps; initial attribution is achieved via Grafana join queries across Umami and Stockroom PostgreSQL tables before schema changes are scheduled.
- The `blogger-agent` pipeline will incorporate EPN campaign parameters and coupon callouts when authoring spotlight drafts.

## SA-11: Commerce and financial reconciliation lifecycle (Inbound to General Ledger)

**Decided 2026-10-14.**

**Decision.** Stockroom serves as the authoritative physical Warehouse Management System (WMS) and transaction pipeline, emitting four immutable, append-only JSON event types (`ItemAcquired`, `ItemSold`, `ItemShipped`, `ItemDisposed`) with integer-cent monetary values. Financial reconciliation into a double-entry General Ledger is decoupled into a dedicated accounting engine (`simple-agility-accounting`). Cost allocation on inbound acquisition lots defaults to uniform per-unit splits across batch items with manual unit overrides for high-value standouts. Marketplace disbursements (eBay Managed Payments, Amazon FBA) settle via two-tier accrual clearing accounts (`1110 Marketplace Clearing`), while non-sale inventory exits are booked explicitly via disposition reason codes.

**Why.**

- **Domain decoupling.** Physical inventory tracking, barcoding, bin management, and shipping operations require high operational speed and responsive UI, whereas financial reconciliation and tax filing require immutable audit ledgers and GAAP compliance. Coupling these into a single monolithic database creates rigid schemas and operational friction.
- **Accrual vs cash clearing.** Marketplace payouts are delayed, consolidated, and net of platform commissions, shipping label fees, and dispute reserves. An order cannot be directly posted to a cash checking account upon sale; an intermediary clearing account is structurally necessary to reconcile bank disbursements against individual order accruals.
- **Auditability and tax compliance.** Non-sale inventory reductions (scrap, donations, owner personal use) have distinct IRS tax treatments (Schedule C inventory adjustments vs. charitable contributions vs. equity draws). Explicit disposition reason codes prevent inventory shrinkage leakage and unrecorded write-offs.

**Consequences.**

- Stockroom models acquisition lots with total landed cost (`lot_acquisition_cost_cents`) and calculates base item costs, allowing operators to enter line-item overrides before finalizing an intake batch.
- Stockroom emits schema-validated event payloads across its lifecycle for ingestion by `simple-agility-accounting`.
- The formal specification, event schemas, Chart of Accounts mapping, and worked numerical examples are normatively defined on [SimpleAgilityCommerceDataFlow](SimpleAgilityCommerceDataFlow).

## SA-10: Small business accounting is a dedicated suite component; WealthView remains personal finance

**Decided 2026-10-14.**

**Decision.** WealthView remains strictly dedicated to personal finance, household portfolio accounting, rental properties, and retirement modeling. It will **not** become the accounting or general ledger backbone for the reselling business. Instead, a dedicated, purpose-built small business accounting tool will be developed as a future component of the Simple Agility suite.

**Why.**

- **Domain separation.** Personal retirement planning (Monte Carlo simulations, guardrail withdrawal strategies, Social Security, RMDs) operates on different accounting abstractions than business cost-of-goods-sold (COGS), inventory tax depreciation, marketplace fee reconciliations, and sales tax filings.
- **Architectural focus.** Forcing double-entry inventory ledger accounting into WealthView would compromise WealthView's clean domain boundaries and bloat its tenant model.
- **Sequencing.** Immediate development focus is on physical inventory operations (Stockroom) and customer outreach (Roller). Business accounting is slated for subsequent suite phases once sales and inventory pipelines are operational.

**Consequences.**

- WealthView requires no schema changes for Stockroom's internal transaction ledger.
- A future Simple Agility component (`simple-agility-accounting`) will ingest Stockroom's realized sales and acquisition costs.
- WealthView models the reselling business strictly as an external cash-flow or business asset entity when reporting net worth.

## SA-9: Cross-repo agent coordination and architectural decisions run through the wiki's Agent Coordination Ledger

**Decided 2026-10-14.**

**Decision.** Coding agents across all repositories (including Antigravity `agy`, Claude Code, and autonomous scripts) must use Wikantik as the authoritative asynchronous coordination plane. Cross-repo initiatives must be registered in the Active Initiatives Ledger on [SimpleAgilityAgentCoordination](SimpleAgilityAgentCoordination), cross-cutting decisions must be recorded here, and all repository `CLAUDE.md` and `.agents/AGENTS.md` files must carry the standard Simple Agility Agent Header.

**Why.**

- **Disjoint agent sessions.** Coding agents operate in separate terminal contexts, isolated worktrees, and ephemeral conversation threads. Without a shared persistent ledger, agents risk generating conflicting cross-repo contracts, duplicated efforts, or obsolete assumptions.
- **Contract-first governance.** Grounding agents on the wiki prevents silent drift in telemetry conventions, database schemas, and inter-product communication.
- **Auditability.** Human operators can inspect the entire system's architectural evolution and active agent work in one place.

**Consequences.**

- [SimpleAgilityAgentCoordination](SimpleAgilityAgentCoordination) is established as the normative protocol and active ledger for coding agents.
- Agents must check the decision log and ledger before implementing cross-boundary changes.
- Every repository's instruction files (`CLAUDE.md`, `.agents/AGENTS.md`) must include the standard Simple Agility header.

## SA-8: Stockroom-to-Roller customer spotlight flywheel is operator-assisted

**Decided 2026-10-14.**

**Decision.** Transitioning notable inventory items from Stockroom into customer-facing blog posts on Roller is **operator-assisted**. During intake, cataloging, or pricing triage, the operator may flag an item as "Feature on Blog". This action packages item facts, condition notes, curated photos, and channel links into a draft spotlight entry staged in Roller for final operator review and scheduled publication.

**Why.**

- **Curated brand voice.** While automated summaries work for standard marketplace listings, customer-facing content for collectors, estate sales, and vintage goods requires human editorial discretion and tone alignment.
- **Quality over noise.** Fully automated publishing risks spamming the customer blog with routine items. An operator-assisted flag ensures only genuine highlights, rare collectibles, and compelling estate stories are broadcast.
- **Operational efficiency.** The agent automates 90% of the friction (extracting dimensions, photos, historical context, condition text, and eBay/store permalinks) while leaving the final publish trigger with the operator.

**Consequences.**

- Stockroom will add a "Feature on Blog" action/state to its intake and pricing workflows (`SA-INIT-02`).
- The Roller publishing agent will ingest flagged item payloads and stage draft posts via Roller's `/api/mcp` or REST API.
- Customer outreach posts will link directly to active eBay listings or store inquiry pages.

## SA-7: Roller is the canonical customer communication platform; customer outreach blogs use Roller native agent publishing

**Decided 2026-10-14.**

**Decision.** Apache Roller (self-hosted fork, Spring Boot, Java 25, PostgreSQL) is the **exclusive, canonical customer communication platform** for the Simple Agility business. A dedicated weblog for the reselling business will be hosted directly on Roller. The existing `blogger-agent` system is to be refactored into a native Roller publisher utilizing Roller v0.2.0 API tokens and the `/api/mcp` endpoint, deprecating third-party Google Blogger dependencies for business outreach.

**Why.**

- **Sovereign customer relationship.** Self-hosting on Roller guarantees complete control over content, brand presentation, subscriber privacy, and newsletter integration (via listmonk) without third-party platform risk or arbitrary account suspensions.
- **Native agent publishing.** Roller v0.2.0 natively provides agent accounts, scoped API tokens, and a dedicated `/api/mcp` endpoint (`RollerMcp`), enabling autonomous agents to draft, stage, and schedule entries without fragile browser scraping or third-party OAuth flows.
- **Infrastructure consolidation.** Roller runs locally on the `docker2` host within the Simple Agility container fleet, logging via ECS and monitored directly by jakemon.

**Consequences.**

- A dedicated reselling weblog is created on Roller (`roller-prod`).
- `blogger-agent` is refactored (`SA-INIT-01`) to replace Google Blogger API calls with Roller REST/MCP calls.
- `blog-assets` continues to serve as the high-availability media CDN for featured images and photograph hosting.

## SA-6: ECS is the stack logging standard

**Decided 2026-10-12.**

**Decision.** Every product writes its logs to stdout as one JSON object per line, using **Elastic Common Schema (ECS)** field names. The required fields are:

- `@timestamp`
- `log.level`
- `message`
- `service.name`

`trace.id` carries the cross-product correlation identifier, `transaction.id` carries the per-hop request identifier, and `event.action` names a business or behavioural event. This **replaces** the stack's earlier home-grown envelope (`ts`, `level`, `msg`, `service`, `event`, `request_id`, `correlation_id`).

**Why.**

- **No product had adopted the old envelope.** Wikantik already logs ECS through log4j2, and Roller is plain text. Changing now breaks no product; changing later would mean a migration.
- **ECS is a published standard with ready-made layouts.** log4j2's `JsonTemplateLayout` ships an ECS template, and Elastic ships ECS loggers for most languages. A new product gets conformant logs from configuration, not code.
- **It lines up with W3C Trace Context.** The contract already took the correlation identifier from `traceparent`. ECS names that value `trace.id`, which is also what OpenTelemetry and Elastic agents fill in, so adopting either later is additive.
- **Other systems we run into speak it.** Logs from third-party components that already emit ECS need no translation.

**Consequences.**

- The [telemetry contract](SimpleAgilityTelemetryContract)'s §2 now specifies the ECS fields. Its evolution rules apply from this version on. The rename was a one-time move, made while nothing emitted the old keys.
- In LogQL, `| json` turns dotted keys into underscored labels: `log.level` becomes `log_level` and `trace.id` becomes `trace_id`. So a query reads `{service="wikantik"} | json | trace_id="…"`.
- **Wikantik** is close. It must:
  - emit `service.name` (it currently uses a custom `application` key);
  - rename its MDC keys to the ECS names (`requestId` becomes `transaction.id`);
  - add `trace.id`.
- **Roller** adopts ECS from the start.
- The conformance script (`bin/simple-agility-conformance.sh`, Wikantik repository) must check the ECS keys rather than the old envelope.

## SA-5: Product instances are named `service_instance="<product>-<env>"`

**Decided 2026-10-12.**

**Decision.** Each running copy of a product is identified on metrics and log streams by two labels:

- `service`: the product (`wikantik`, `roller`), matching its metric prefix and its `service.name` log field.
- `service_instance`: that copy, named **`<product>-<env>`** in lowercase with a hyphen. Examples: `wiki-prod`, `wiki-support`, `wiki-dev`, `roller-prod`.

The plane attaches both labels at collection time, from the host's `apps.alloy` in jakemon, to the product's scrape target and to its container's log stream. Products do not label themselves.

**Why.**

- **`instance` is taken.** Prometheus sets `instance` to the scrape target (`host.docker.internal:8080`). jakemon's dashboards rely on that meaning, and a product-emitted `instance` is renamed to `exported_instance`.
- **`deployment` means something else.** It names the whole stack deployment, the tenant, and stays that way (see SA-2).
- **`<product>-<env>` reads well in an alert and generalizes.** It is the form the plane contract already used in its examples. It also separates development copies from production ones without relying on `host`. Today minisforum runs development copies of production containers, and `host` is the only thing that tells them apart.
- **Attaching labels in the plane keeps products configuration-free.** The name is decided where the deployment is decided, so a product image can be run as `wiki-prod` or `wiki-support` unchanged.

**Consequences.**

- The [telemetry contract](SimpleAgilityTelemetryContract) §1 states the rule. The log line does not need to carry the instance name.
- **Built in jakemon on 2026-10-12.** Each host's `apps.alloy` sets the labels on the product's scrape target, and on its containers' log streams through a `discovery.relabel "products"` hook. Live since 2026-10-12: `wiki-prod` (docker1, metrics and logs), `crafter-prod` (docker2, metrics and logs), `roller-prod` (docker2, logs only, because Roller has no `/metrics` yet), and `wiki-dev` and `crafter-dev` (minisforum, logs). Alerts on those series inherit the labels.
- **`service` means the product everywhere on the plane.** jakemon's per-systemd-unit resource series (`host_service:*`) label the unit `unit`, not `service`.
- On Loki, the `service` label also becomes Loki's automatic `service_name`.
- The `<product>-<env>` pattern is the default wherever a product deployment is named: feed shipper targets, backup names, Grafana variables. The product short names are `wiki` (Wikantik), `roller`, `crafter` (CrafterCMS) and `stockroom`.

## SA-4: jakemon may hold application code that serves the whole stack as the plane

**Decided 2026-10-12.**

**Decision.** jakemon keeps its rule of no application code, with **one exception**: an application whose purpose is global across the stack's applications **and** that belongs with the observability plane. The **agent-facing query server** qualifies. It is a read-only MCP surface over alert state, PromQL, LogQL and dashboard links, used by every product's agents, and it lives in the jakemon repository.

**Why.** The query server is the plane's read interface. Splitting it into another repository would separate it from the label conventions, rules and dashboards it explains. Product-specific logic would not pass the test, and neither would a general-purpose service that happens to be convenient to host in jakemon.

**Consequences.**

- [Roadmap](SimpleAgilityRoadmap) phase 3 is no longer blocked on this question. It still depends on phases 1 and 2.
- The **agent runtime** (ledger, circuit breakers, identities, acting agents) is **not** covered. It acts on products rather than observing them, and it stays a separate, stack-level component. See [SimpleAgilityStackHub](SimpleAgilityStackHub).
- jakemon's existing stdlib-Python exporters were always treated as configuration-adjacent glue. This decision does not change their status.

## SA-3: Every alert names its runbook

**In force since 2026-08-18.**

**Decision.** Every alert rule carries `runbook: Incident<AlertName>`, naming a wiki page. Incident runbooks are `type: runbook` pages tagged `incident-runbook`, with an `alert:` field. Their source is in the jakemon repository, and they are published to this wiki.

**Why.** The on-call agent must get from a firing alert to its procedure without guessing. A label that points at a missing page is worse than no label, so both ends are checked: jakemon's commit gate rejects an unlabelled rule, and `bin/runbook-check.sh` verifies that every page exists.

**Normative page:** [SimpleAgilityOnCallAutomation](SimpleAgilityOnCallAutomation).

## SA-2: The stack deployment is `deployment`, never `instance`

**In force since 2026-08-18.**

**Decision.** Every agent-shipped series and log stream carries `deployment`, naming the whole stack deployment (one tenant's jakemon, Wikantik and Roller; today `jakemon-home`), and `host`. Prometheus's `instance` keeps its own meaning, the scrape target.

**Why.** Overwriting `instance` would make the targets within one deployment indistinguishable and break the dashboards that rely on it. Product instances got their own label later (SA-5).

## SA-1: Telemetry goes to the plane; product inputs stay in the product

**In force since 2026-08-18.**

**Decision.** Anything emitted so that a human or an agent can *observe* the system belongs on the plane (Prometheus and Loki). Anything that is a functional *input to the product's own behaviour* is a product table, not a metric.

**The test:** if this data vanished, would the product behave differently, or would only a dashboard go blank?

**Normative page:** [SimpleAgilityObservabilityPlane](SimpleAgilityObservabilityPlane), including its carve-outs for compliance audit logs.

## See also

- [SimpleAgilityStackHub](SimpleAgilityStackHub): the stack and its components
- [SimpleAgilityTelemetryContract](SimpleAgilityTelemetryContract): what products must emit
- [SimpleAgilityRoadmap](SimpleAgilityRoadmap): which decisions are implemented, and where
- [ProjectContextSeparationDesign](ProjectContextSeparationDesign): the normative page behind SA-13
- [StructuredLogging](StructuredLogging): background on structured logging

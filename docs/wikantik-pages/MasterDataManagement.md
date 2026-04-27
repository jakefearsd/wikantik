---
canonical_id: 01KQ0P44S9XYAYZ5WF57AQ9EWM
title: Master Data Management
type: article
cluster: data-engineering
status: active
date: '2026-04-26'
summary: MDM as the discipline of maintaining canonical entity records across systems
  — what it actually requires, why it's hard, and the patterns that work in practice
  vs. the heavy MDM platforms that often don't.
tags:
- master-data-management
- mdm
- data-quality
- data-governance
related:
- DataModelingFundamentals
- DataCatalogTools
- DataPipelineDesign
hubs:
- DataEngineering Hub
---
# Master Data Management

Master Data Management (MDM) is the discipline of maintaining canonical records for key business entities — customers, products, locations, employees — across systems that all want to know about them.

The problem: every system has its own customer record. Customer support, billing, marketing, the data warehouse all have "the customer." None of them quite agree.

The solution: a master record that's the source of truth.

The hard part: making it work in practice.

## What MDM actually requires

### Identification

Match records across systems. The customer in CRM, in billing, and in the warehouse — is it the same person?

Identification uses:
- **Deterministic matching**: same email, same SSN, same customer ID
- **Probabilistic matching**: similar name + similar address + similar phone

Real customer matching is hard. Names misspelled, addresses formatted differently, emails change.

### Survivorship rules

When two records describe the "same" customer with different attributes, which value wins?

- Most recent? Most complete? From the most-trusted source? Manually approved?

Different attributes have different rules. Email might come from CRM; address from billing.

### Distribution

Once you have the master record, push it to all systems that need it.

### Governance

Who decides on identification rules? Who resolves matches manually? Who has access to master data?

## Why it's hard

### Data is messy

Real customer data has duplicates, typos, missing fields, conflicting values. Cleaning all of it is impossible; clean enough is the goal.

### Systems disagree

Each system was built to serve its own needs. Their data shapes differ; their semantics differ. Reconciling is delicate.

### Politics

MDM touches every team's data. Who decides? Who's the canonical source for customer email — the billing team or the CRM team?

### Constant change

New systems join the picture. Old systems retire. Customers change. The MDM solution that worked last year doesn't fit this year.

## Approaches

### Heavy MDM platform

Vendors: Informatica MDM, Reltio, Stibo, IBM, etc. Comprehensive platforms for matching, governance, distribution.

Pros: handles the full problem.
Cons: expensive (six to seven figures); long implementation; often becomes shelfware.

For most organizations, heavy MDM platforms are over-engineered. The problem is real but the platform isn't proportional.

### Customer Data Platform (CDP)

Segment, mParticle, RudderStack. Customer-focused MDM for marketing/analytics. Lighter than full MDM platforms.

Pros: easier to deploy; focused on customer data.
Cons: limited to customers (not products, locations, etc.); marketing-centric.

For consumer companies, often the right answer.

### Warehouse-based MDM

dbt models that build canonical entity tables in the warehouse. Matching logic in SQL; survivorship in transformations.

```sql
-- Build canonical customer
SELECT
    customer_id,
    COALESCE(crm.email, billing.email) AS email,
    COALESCE(billing.address, crm.address) AS address,
    GREATEST(crm.last_updated, billing.last_updated) AS last_updated
FROM raw_crm crm
FULL OUTER JOIN raw_billing billing USING (customer_id)
```

Pros: cheap; uses tooling you already have; flexible.
Cons: customer-id matching across systems still hard; doesn't push back to operational systems.

For analytics-only MDM, this is enough. For operational MDM (the customer record in CRM updates billing), it isn't.

### Custom service

A dedicated service that other systems call: "give me the canonical customer for this email."

Pros: tailored to needs; centralized logic.
Cons: build-and-maintain cost.

Useful when MDM needs are specific and fit-for-purpose tools don't exist.

## Patterns that work

### Single source per attribute

Don't try to merge "email" from three systems. Pick one as canonical for email; one for address; etc. Each attribute has one master.

### Identity resolution as a separate problem

Identifying that records are the same is one problem. Reconciling their attributes is a different problem. Solve them separately.

### Observable matching

When matches happen, log who matched to whom and why. Wrong matches are debugging nightmares without visibility.

### Iterative

Start with the highest-value entities (customers, usually). Add others later. Don't try to MDM everything at once.

### Stewardship

Every master entity has a steward — a person responsible for resolving conflicts and approving merges. Without stewards, the data drifts.

## Common patterns to avoid

- **MDM as IT project.** Without business buy-in, IT-driven MDM fails.
- **Trying to merge everything.** Some attributes fundamentally conflict.
- **Heavy MDM platform without governance.** Tool without process is a fancy database.
- **MDM that doesn't update operational systems.** Master record exists; operational systems don't know.

## Common failure patterns

- **Identification rules too strict.** Same person seen as different across systems.
- **Identification rules too loose.** Different people merged.
- **Survivorship rules unclear.** Inconsistent values; nobody knows which is right.
- **No history.** Can't trace why a master record has its current values.
- **No retention rules.** Old data lingers; compliance issues.

## Further Reading

- [DataModelingFundamentals](DataModelingFundamentals) — Modeling context
- [DataCatalogTools](DataCatalogTools) — Adjacent governance
- [DataPipelineDesign](DataPipelineDesign) — How master data flows
- [DataEngineering Hub](DataEngineering+Hub) — Cluster index

---
date: 2026-05-03T00:00:00Z
status: active
summary: Central index for Project Wealthview — a self-hosted, multi-tenant personal
  finance platform for tracking investment portfolios, rental properties, and retirement
  projections.
tags:
- wealthview
- fintech
- wealthtech
- portfolio-tracking
- multi-tenant
- retirement-planning
- hub
- '{''type'':-''part-of'',-''target-id'':-''01kzhc6pvn4sbqm9r0f3t7k8y2''}'
type: hub
cluster: wealthview
canonical_id: 01KQR44WKHVES95QKN9731B09
related:
- WealthviewArchitectureBlueprint
- FintechDataIngestionBlueprint
- PersonalFinanceHub
- DataEngineeringHub
title: Wealthview Hub
---

# Wealthview Hub: Personal Finance Platform

Wealthview is a professional-grade, self-hosted platform designed for high-fidelity financial tracking and modeling. It bridges the gap between simple spreadsheets and complex SaaS platforms, giving users full ownership of their financial data.

## Platform Architecture

- [Wealthview Architecture Blueprint](WealthviewArchitectureBlueprint) — Module structure, dependency rules, and tech stack
- [Fintech Data Ingestion Blueprint](FintechDataIngestionBlueprint) — Aggregator integration (Plaid) and transaction normalization

## Core Domains

### Investment Portfolio Tracking
- **Automated Cost Basis**: Multi-lot accounting computed from transaction history.
- **Multi-Currency Support**: Tenant-managed exchange rates for global portfolios.
- **Price Feeds**: Daily sync with Finnhub and historical backfill.

### Rental Property Management
- **Performance Analytics**: Hold-vs-sell ROI and cash flow reporting.
- **Tax-Aware Modeling**: Cost-segregation depreciation and loan amortization.

### Retirement Projections
- **Monte Carlo Simulations**: Guardrail spending optimization with block-bootstrap returns.
- **Tax Strategies**: Roth conversion modeling and per-pool withdrawal transparency.

## Deployment & Operations

- **Multi-Tenant Design**: JWT-based isolation with invite-gated registration.
- **Self-Hosted**: Docker-first deployment for private infrastructure.

## See Also
- [PersonalFinanceHub](PersonalFinanceHub) — Foundational financial concepts.
- [DataEngineeringHub](DataEngineeringHub) — Generic pipeline and modeling patterns.
- [GenerativeAIHub](GenerativeAIHub) — Building agentic features on top of Wealthview data.

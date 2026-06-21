---
type: hub
status: active
date: '2026-04-26'
cluster: data-engineering
title: Data Engineering Hub
hubs:
- GenerativeAIHub
- WealthviewHub
tags:
- data-engineering
- pipelines
- etl
- hub
- analytics
summary: Index of data engineering pages — pipelines, modeling, ETL/ELT, transformation
  layers, catalogs, and patterns for sustainable analytics infrastructure.
related:
- CloudPlatformsHub
- DevOpsAndSreHub
canonical_id: 01KZHC6PVX4SBQM9R0F3T7K8ZA
---
# DataEngineering Hub

This cluster covers the engineering side of data — pipelines, modeling, transformation, and the catalog layer that turns raw data into something usable. The focus is the operational and architectural patterns; modeling and analysis are adjacent topics.

## Strategy and Lifecycle

- [Data Maturity Lifecycle](DataMaturityLifecycle) — A structural roadmap from fragmented silos to Data Mesh.
- [Shift Left Data Engineering](ShiftLeftDataEngineering) — Moving data quality upstream via contracts.

## Pipeline design

- [DataPipelineDesign](DataPipelineDesign) — Sources, transforms, sinks; idempotency and observability
- [EtlVsElt](EtlVsElt) — When transform belongs early vs. late
- [MapReduceParadigm](MapReduceParadigm) — The paradigm that defined the batch era
- [DbtAndAnalyticsEngineering](DbtAndAnalyticsEngineering) — dbt as transformation tool, the analytics-engineering role

## Vertical-Specific Pipelines

- [Fintech Data Ingestion Blueprint](FintechDataIngestionBlueprint) — Ingesting, normalizing, and storing third-party financial data

## Modeling

- [Data Modeling Fundamentals](DataModelingFundamentals) — Star, snowflake, dimensional, the fact-and-dim mental model
- [NoSQL Database Types](NoSqlDatabaseTypes) — When and why to move beyond relational
- [Jsonb In Postgresql](JsonbInPostgresql) — Handling semi-structured data in a relational engine
- [Master Data Management](MasterDataManagement) — MDM as the discipline; tools as the implementation

## Catalogs and metadata

- [Data Catalog Tools](DataCatalogTools) — DataHub, Amundsen, Atlan; what they actually do
- [Data Lake Architecture](DataLakeArchitecture) — Organizing massive unstructured datasets

## Adjacent clusters

- [Cloud Platforms Hub](CloudPlatformsHub) — Where pipelines and warehouses run
- [DevOps and SRE Hub](DevOpsAndSreHub) — Operating data pipelines

---
tags:
- fintech
- data-ingestion
- wealthtech
- plaid
- pydantic
- schema
- wealthview
relations:
- type: part-of
  target_id: 01KQR44WKHVES95QKN9731B09
- type: implements
  target_id: 01KQ0P44SZE8KANR12S3W8QDHS
- type: derived-from
  target_id: 01KQEKGD9XWDSFGH7TWHH63NZT
date: 2026-05-03T00:00:00Z
title: Fintech Data Ingestion Blueprint
cluster: wealthview
hubs:
- WealthviewHub
type: blueprint
status: active
canonical_id: 01KQRMQ9A89VW5A4ZZH7G29FDC
summary: A high-density technical implementation spec for ingesting, normalizing,
  and storing fintech data from third-party aggregators (e.g., Plaid, MX, Yodlee).
  Designed for RAG agents building WealthTech applications like wealthview.
---

# Fintech Data Ingestion: Technical Blueprint

This blueprint provides the structural and operational requirements for building a production-grade data ingestion pipeline for [WealthTech](WealthviewHub) applications. It focuses on the transition from third-party raw JSON to a normalized, query-optimized internal model.

## 1. Domain Model (Pydantic Schemas)

To ensure RAG agents generate valid code, use these strictly typed schemas for normalization.

### 1.1 The Account Schema
Represents a standardized financial account across institutions (Checking, Savings, Investment, Credit).

```python
from enum import Enum
from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime

class AccountType(str, Enum):
    depository = "depository"
    investment = "investment"
    credit = "credit"
    loan = "loan"

class NormalizedAccount(BaseModel):
    account_id: str = Field(..., description="Unique internal ID")
    provider_id: str = Field(..., description="ID from aggregator (e.g., Plaid)")
    institution_name: str
    official_name: Optional[str]
    type: AccountType
    balance_current: float
    balance_available: Optional[float]
    iso_currency_code: str = "USD"
    last_synced: datetime = Field(default_factory=datetime.utcnow)
```

### 1.2 The Transaction Schema
Normalized schema for bank and brokerage transactions.

```python
class TransactionCategory(str, Enum):
    income = "income"
    transfer = "transfer"
    expense = "expense"
    tax = "tax"
    investment_buy = "investment_buy"
    investment_sell = "investment_sell"

class NormalizedTransaction(BaseModel):
    transaction_id: str
    account_id: str
    date: datetime
    amount: float = Field(..., description="Positive for outflow, negative for inflow (Standardized)")
    merchant_name: Optional[str]
    category: TransactionCategory
    raw_category: List[str]
    is_pending: bool = False
```

## 2. Ingestion Pipeline Architecture

### 2.1 The "Idempotent Sync" Pattern
To prevent duplicate transactions, implement a multi-key hash check:
1.  **Generate Sync Hash**: `hash(account_id + provider_transaction_id + date + amount)`.
2.  **Upsert Logic**: Use the `provider_transaction_id` as the primary key or a unique constraint in the `transactions` table.
3.  **State Management**: Store a `last_cursor` for each account to perform incremental fetches (e.g., Plaid `/transactions/sync`).

### 2.2 Normalization Logic (The "Mapper" Layer)
Aggregators return heterogeneous categories. Your mapper must:
- **Clean Merchant Names**: Strip noise like "COFFEE SHOP #1234 NY" to "Coffee Shop".
- **Map to Internal Taxonomy**: Use a deterministic mapping table (or an LLM-router for unknowns) to convert `["Food and Drink", "Coffee Shop"]` to `TransactionCategory.expense`.

## 3. Operational Resilience

### 3.1 Error Handling Protocols
| Error Code | Strategy | User Action |
| :--- | :--- | :--- |
| `ITEM_LOGIN_REQUIRED` | Pause Sync | Trigger "Repair Link" UI flow |
| `RATE_LIMIT_EXCEEDED` | Exponential Backoff | None (Automated) |
| `INSTITUTION_DOWN` | Circuit Breaker | Notify user of delay |

### 3.2 Security and Compliance
- **PII Stripping**: Do not store full account numbers. Only store the `mask` (last 4 digits).
- **Encryption at Rest**: Encrypt the `access_token` and `link_session_id` using AES-256 (GCM) with a separate KMS-managed key.

## 4. RAG Implementation Hook

For an agent building **wealthview**, the prompt should be:
> "Using the `FintechDataIngestionBlueprint`, implement a Python FastAPI endpoint that receives a Plaid `public_token`, exchanges it for an `access_token`, and performs the initial `NormalizedAccount` sync using Pydantic validation."

## See Also
- [[WealthviewHub]] — Central project index.
- [[NetWorthTracking]] — The primary consumer of this data.
- [[DataEngineeringHub]] — Foundational pipeline patterns.
- [[ApplicationSecurityFundamentals]] — For KMS and encryption details.

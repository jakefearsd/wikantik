---
canonical_id: 01KQ0P44XGBAGJW390S9ZNSV42
title: Temporal Tables
tags:
- sql
- databases
- auditing
- temporal-logic
cluster: databases
type: article
date: 2024-05-16T00:00:00Z
auto-generated: false
summary: SQL:2011 system-versioned temporal tables — AS OF query syntax, immutable
  history logs for auditing, point-in-time recovery, and storage trade-offs.
---
# Temporal Tables: The SQL:2011 Standard

Temporal tables allow a database to store and query the history of data changes automatically. Unlike standard tables that only store the "current" state, temporal tables maintain an immutable log of every previous version of a record.

## 1. System-Versioned Architecture

A temporal table consists of two physical tables:
1.  **Current Table:** Optimized for standard OLTP operations; contains the latest data.
2.  **History Table:** An append-only log containing every previous version, including the timestamps of when each version was valid.

The engine manages two hidden columns (e.g., `SysStartTime` and `SysEndTime`) to track the validity period of each row.

## 2. The 'AS OF' Query Syntax

The power of temporal tables lies in the ability to "travel back in time" using the standard SQL:2011 syntax.

### 2.1 Point-in-Time Queries
To see the state of the `Products` table as it existed on January 1st, 2024:
```sql
SELECT * FROM Products
FOR SYSTEM_TIME AS OF '2024-01-01 00:00:00';
```
The database engine automatically scans both the current and history tables to find rows where:
`SysStartTime <= '2024-01-01'` AND `SysEndTime > '2024-01-01'`.

### 2.2 Range Queries
To find all versions of a specific record between two dates:
```sql
SELECT * FROM Products
FOR SYSTEM_TIME BETWEEN '2023-01-01' AND '2023-12-31'
WHERE ProductID = 123;
```

## 3. Immutability and Auditing

Temporal tables provide a mathematically sound audit trail.
*   **Tamper Evidence:** Since the `History` table is system-managed, application code cannot modify historical records. This is critical for financial compliance (SOX/GDPR).
*   **Point-in-Time Recovery:** If a "fat-finger" error deletes data, the `AS OF` syntax allows for surgical recovery of the specific records without restoring the entire database from a backup.

## 4. Performance Considerations

*   **Write Amplification:** Every `UPDATE` to the current table triggers an `INSERT` into the history table.
*   **Indexing:** The history table should be indexed on `(SysEndTime, SysStartTime)` to optimize for point-in-time lookups.
*   **Storage Tiers:** Because history grows indefinitely, many systems move the history table to cheaper, slower storage (e.g., HDD or Object Storage) while keeping the current data on SSDs.

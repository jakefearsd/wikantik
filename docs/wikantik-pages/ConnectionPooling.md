---
cluster: databases
canonical_id: 01KQ0P44NWS5PXSNGM4DFMM55M
title: Connection Pooling
type: article
tags:
- connection-pooling
- databases
- pgbouncer
- hikaricp
- performance
summary: Technical analysis of multi-layered connection pooling (HikariCP and PgBouncer) in high-throughput distributed systems.
auto-generated: false
---

Connection pooling is a critical optimization for managing the finite resource of database connections in high-throughput environments. In a typical microservice stack, this involves two distinct layers: the **Application-level pool** (e.g., HikariCP) and the **Network-level pooler** (e.g., PgBouncer).

## Layer 1: HikariCP (Application-Level)

HikariCP manages persistent JDBC connections within the JVM process. Its primary goal is to minimize the latency of acquiring a connection by keeping a warm pool of validated sockets.

**Key Parameters:**
- `maximumPoolSize`: The ceiling for active connections. Formula: $N_{\text{threads}} + \text{buffer}$.
- `minimumIdle`: The floor for warm connections.
- `connectionTimeout`: Max time to wait for a connection before throwing `SQLException`.
- `idleTimeout`: Max time a connection can sit idle before retirement.

## Layer 2: PgBouncer (Network-Level)

PgBouncer sits between the application and PostgreSQL, multiplexing many client connections onto a small number of backend connections.

### Operational Modes
1.  **Session Mode:** Connection is dedicated to the client for its entire lifecycle. Safest, but lowest reuse.
2.  **Transaction Mode:** Connection is returned to the pool immediately after `COMMIT` or `ROLLBACK`. **Recommended for microservices.**
3.  **Statement Mode:** Connection is returned after every query. Breaks multi-statement transactions.

## The Interaction: Double Pooling

When combining HikariCP and PgBouncer, synchronization of timeouts is mandatory to prevent "phantom connections"—where HikariCP thinks it has a valid connection but PgBouncer has already terminated the backend link.

### Timeout Synchronization Rule
$$T_{\text{PgBouncer Client Idle}} > T_{\text{HikariCP Max Lifetime}}$$
This ensures HikariCP proactively retires connections before PgBouncer forcibly kills them.

## Sizing the Stack

The total connection capacity is a function of the bottleneck—usually the PostgreSQL `max_connections`.

| Layer | Limit Parameter | Recommended Sizing |
|---|---|---|
| **PostgreSQL** | `max_connections` | $\text{Hardware Limit}$ (e.g., 500) |
| **PgBouncer** | `max_db_conn` | $0.8 \times \text{PostgreSQL Limit}$ |
| **Microservices** | $\sum \text{HikariCP MaxPoolSize}$ | $2 \times \text{PgBouncer max\_db\_conn}$ (Oversubscription) |

**Note:** Oversubscription is safe in Transaction Mode because most application connections are idle between transaction boundaries.

## Failure Modes

1.  **Transaction Leakage:** If the application fails to close a transaction (`BEGIN` without `COMMIT`), PgBouncer cannot return the connection to the pool, leading to starvation.
2.  **Session-State Pollution:** Commands like `SET search_path` or `SET timezone` persist on the backend connection in PgBouncer. Mitigation: Use `server_reset_query` or reset state explicitly.
3.  **Connection Storms:** If PgBouncer restarts, all application pools will simultaneously attempt to reconnect, potentially overwhelming the network or PgBouncer's listener. Use **Connection Backoff** in HikariCP.

## Comparative Summary

| Metric | HikariCP | PgBouncer |
|---|---|---|
| **Location** | Application JVM | Middleware (Sidecar/Service) |
| **Protocol** | JDBC / Java Objects | PostgreSQL Wire Protocol |
| **Multiplexing** | No (1:1 Client-Socket) | Yes (N:M Client-Socket) |
| **Benefit** | Acquisition Latency | Resource (Memory/FD) Savings |

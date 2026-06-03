---
cluster: wikantik-development
canonical_id: 01KT6453VWTT2K0XS4Z410G176
title: Audit Log Design
type: design
status: active
date: '2026-06-03'
author: claude-opus
summary: Tamper-evident, append-only audit log for Wikantik. Captures authN/authZ, content changes, admin/security-config changes, and opt-in sensitive page reads via a hash-chain (SHA-256) plus locked DB grants, written by a single async writer under a Postgres advisory lock, with an admin query/export/verify surface. Compliance-first; keep-forever in v1.
tags:
- design
- audit
- compliance
- security
- enterprise
---

# Audit Log Design

## Purpose

A tamper-evident, append-only audit trail that records who did what, when, and
from where across Wikantik. The primary driver is **compliance evidence**
(SOC 2 / ISO 27001 / HIPAA readiness): defined coverage, cryptographic
tamper-evidence, and a clean export and verification path for auditors. Operational
forensics ("who deleted this page?") falls out of the same data for free.

This is the first of the enterprise-readiness capabilities. It is deliberately
self-contained: it subscribes to events that already fire and adds a small number
of direct instrumentation points, with no external infrastructure dependency.

## Scope

### In scope (v1)

Four event categories:

1. **AuthN / AuthZ** — login success/failure, logout, session expiry, access
   denied. Sourced from the existing `WikiSecurityEvent` vocabulary
   (`LOGIN_AUTHENTICATED`, `LOGIN_FAILED`, `LOGOUT`, `SESSION_EXPIRED`,
   `ACCESS_DENIED`, etc.).
2. **Content changes** — page create / edit / delete / rename, attachment
   upload / delete. Sourced from `WikiPageEvent` / `WikiPageRenameEvent` and the
   attachment path.
3. **Admin & security config** — permission / policy-grant changes, group
   membership changes, user create / enable / disable, API-key issuance, role
   changes. The highest-value compliance evidence; some of these need new direct
   instrumentation (see "Coverage mapping").
4. **Sensitive page reads** — read access to flagged content. **Gated, default
   off** (see "The page-view problem").

### Out of scope (v1, deferred to v2+)

- **Retention purge.** v1 is keep-forever (pure append-only). The table is
  partitioned monthly from day one so the v2 partition-drop purge is a schema
  no-op, but no purge job ships in v1.
- **External WORM / SIEM forwarding.** Belongs with the future outbound-webhook
  work, not here.
- **Per-field before/after diffs of page bodies.** `detail` stores metadata only.

## Architecture

The audit log is primarily a **sink on the existing event bus**. Most of what
auditors want already fires as `WikiSecurityEvent`, `WikiPageEvent`, or
`WikiPageRenameEvent`; the audit subsystem translates those into canonical audit
records and persists them through a single serialized writer.

```
event emitters ──► AuditEventListener ──► AuditService.record(AuditEntry)
  (existing)         (translates           │
                      events → entries)     ▼
                                     bounded in-memory queue
                                            │
                                  AuditWriterThread (single consumer)
                                            │  pg_advisory_xact_lock(chain)
                                            ▼
                                   JdbcAuditRepository ──► audit_log (partitioned)
```

### Components

- **`AuditEntry`** — immutable value type; the canonical audit record. The unit
  of everything downstream (hashing, persistence, query results).
- **`AuditService`** — the façade everything depends on. Two responsibilities:
  `record(AuditEntry)` (non-blocking enqueue) and `verifyChain(fromSeq, toSeq)`.
  Depending on this interface (not the writer or repository) lets emitters be
  unit-tested without a database and lets the writer be swapped.
- **`AuditEventListener`** — a `WikiEventListener` that maps event constants to
  `AuditEntry` instances. This is the single place the event→audit vocabulary
  lives.
- **`AuditWriterThread`** — single consumer modelled on the existing
  `WikiBackgroundThread`. Owns the chain head, drains the queue in batches, and
  batch-inserts under a Postgres advisory lock.
- **`JdbcAuditRepository`** — insert, paged query, and chain read, modelled on
  `JdbcKnowledgeRepository`.
- **Direct `AuditService.record()` calls** for actions that do **not** currently
  fire a suitable event: policy-grant / ACL edits, API-key issuance, user
  enable/disable. Enumerated against the code during planning.

### Module placement

Core (`com.wikantik.audit.*`) lives in **wikantik-main**, alongside the
`knowledge` and `pagegraph` cores, because it must depend on main's event
emitters regardless. REST endpoints live in **wikantik-rest**; the admin UI lives
in **wikantik-frontend**. A standalone `wikantik-audit` module was rejected: it
would still depend on main for the emitters, adding build ceremony without real
isolation.

## Data model

Table `audit_log`, **RANGE-partitioned by month on `created_at`**.

| column | type | purpose |
|---|---|---|
| `seq` | `BIGINT` | global sequence; **chain order** |
| `created_at` | `TIMESTAMPTZ` | writer insert time; partition key; monotonic with `seq` |
| `event_time` | `TIMESTAMPTZ` | when the action actually occurred |
| `category` | `TEXT` | `authn` / `authz` / `content` / `admin` / `read` |
| `event_type` | `TEXT` | e.g. `login.failed`, `page.delete`, `policy.grant.update` |
| `actor_id` | `TEXT` | actor uid |
| `actor_principal` | `TEXT` | actor display/login name |
| `actor_type` | `TEXT` | `user` / `apikey` / `sso` / `anonymous` / `system` |
| `target_type` | `TEXT` | `page` / `user` / `group` / `policy` / `apikey` / … |
| `target_id` | `TEXT` | stable id of the target |
| `target_label` | `TEXT` | human-readable target (slug, name) |
| `outcome` | `TEXT` | `success` / `failure` / `denied` |
| `source_ip` | `TEXT` | request origin IP |
| `user_agent` | `TEXT` | request user agent |
| `correlation_id` | `TEXT` | ties to `wikantik-observability` request correlation |
| `detail` | `JSONB` | structured extras — **metadata only, never page bodies** |
| `prev_hash` | `CHAR(64)` | previous row's `row_hash` |
| `row_hash` | `CHAR(64)` | SHA-256 of this row (see "Tamper-evidence") |

Notes:

- The **single writer assigns both `seq` and `created_at` at insert time**, so
  sequence order, insertion order, and partition-key order are always consistent
  (no clock-skew reordering across a month boundary). `event_time` preserves the
  original event timestamp separately.
- `seq` comes from a global Postgres `SEQUENCE`. Postgres requires the partition
  key in any unique constraint, so global `seq` uniqueness is guaranteed by the
  sequence rather than a unique index; a plain index on `seq` supports chain
  walks.
- Indexes: `seq`, `created_at`, `actor_id`, `event_type`, `target_id`.
- Delivered as a new numbered migration under `bin/db/migrations/`
  (`V<NNN>__audit_log.sql`), idempotent, creating the partitioned parent, an
  initial set of monthly partitions, the sequence, the indexes, and the locked
  grants below. DDL-only — no data backfill in the versioned migration.

## Tamper-evidence

Two independent layers (defense in depth):

### Hash chain (application-level cryptographic proof)

```
row_hash = SHA256( prev_hash ‖ canonical(entry) )
```

- `canonical(entry)` is a fixed-field-order serialization of every immutable
  column of the row. The serialization is versioned so future column additions
  remain verifiable.
- The genesis row uses `prev_hash` = 64 zeros.
- Editing or deleting any historical row breaks every subsequent `row_hash`,
  which the verify pass detects.

### Locked DB grants (database-level prevention)

- The application DB role is granted **`INSERT, SELECT` only** on `audit_log`;
  `UPDATE` and `DELETE` are **revoked**.
- A separate privileged maintenance role (used only by the future v2 partition
  purge) is the only principal able to remove data.

### Verification

`GET /admin/audit/verify` walks rows in `seq` order, recomputes the chain, and
returns `OK` or the `seq` of the first break. This is the artifact auditors run.

## Write path

Asynchronous single-writer:

1. `AuditService.record()` enqueues an `AuditEntry` on a **bounded** in-memory
   queue and returns immediately — request threads never block on audit I/O.
2. `AuditWriterThread` drains the queue in batches. Per batch, in one
   transaction: acquire `pg_advisory_xact_lock(<chain-key>)`, read the current
   chain head (`max(seq)` + its `row_hash`), compute `seq`/`prev_hash`/`row_hash`
   for each entry, batch-`INSERT`, commit.

The advisory lock keeps the chain correct **even if a second app node is ever
introduced** (the HA trajectory), at negligible single-node cost.

**Durability tradeoff (accepted for v1):** entries sitting in the in-memory queue
at a hard crash are lost. Mitigated by keeping the queue shallow and flushing
frequently; documented as a known limit. The durable-staging alternative is a v2
option if zero-loss becomes a requirement.

**Failure handling:** an audit write failure is **never swallowed** — it logs at
`LOG.warn` with full context (per project rules) and increments a dropped-event
Prometheus counter so the gap is observable.

## Coverage mapping

| Category | Source | Notes |
|---|---|---|
| authN/authZ | `WikiSecurityEvent` via `AuditEventListener` | login/logout/expiry/denied already fire |
| content | `WikiPageEvent`, `WikiPageRenameEvent`, attachment path | create/edit/delete/rename/upload |
| admin | direct `AuditService.record()` | policy grants, group membership, user enable/disable, API-key issuance, role changes — instrumented at the call sites |
| read | content filter / view path, **gated** | see below |

### The page-view problem

Page reads are high volume. v1 gates read auditing behind a flag, **default
off**: a page is audited for reads only when it opts in via frontmatter
(`audit_reads: true`) or belongs to a space/cluster flagged for read auditing.
The common path writes nothing; only regulated content pays the cost.

## Admin surface

- `GET /admin/audit` — paged, filterable by actor, category, event_type, target,
  date range, and outcome. Behind `AdminAuthFilter`.
- `GET /admin/audit/export?format=csv|json` — auditor export.
- `GET /admin/audit/verify` — chain integrity check.
- **SPA:** a new **Audit** tab in the admin panel — filter table, export buttons,
  and a "Verify integrity" action. The audit page is a client-side sub-route of
  the existing `/admin` panel; because `SpaRoutingFilter.SPA_PREFIXES` already
  contains `/admin/`, **no `web.xml` / `SPA_EXACT` change is required** (this
  corrects the original dual-registration note). The `/admin/audit*` REST
  endpoints are servlets registered in `web.xml` alongside the other admin
  resources.

Access to the audit log is itself an `admin`-category audit event.

## Security & PII

The audit log itself contains PII (IP addresses, principals, user agents).
Mitigations:

- `detail` holds metadata only — never page bodies or attachment contents.
- Admin-only access, enforced by `AdminAuthFilter`; viewing the log is audited.
- Inherits the existing off-box NAS backup for durability.

## Testing approach (TDD)

**Unit:**
- Chain hash determinism: identical entries → identical `row_hash`; mutate any
  field → different hash.
- Tamper detection: build a chain, mutate one row, assert `verifyChain` reports
  the correct first-broken `seq`.
- Event→entry mapping: each `WikiSecurityEvent` / `WikiPageEvent` constant maps
  to the expected category/event_type/outcome.
- Writer ordering: enqueued entries persist in enqueue order with a contiguous
  `seq` and a valid chain.
- Locked-grant enforcement: `INSERT` succeeds, `UPDATE`/`DELETE` by the app role
  are rejected.

**Integration (Cargo Tomcat + PostgreSQL):**
- Perform a login, a page delete, and a policy-grant change; assert the expected
  rows exist and `GET /admin/audit/verify` returns `OK`.
- Per project rules: run the full IT reactor (`mvn clean install
  -Pintegration-tests -fae`) before committing prod-code changes.

## As-built notes (deltas from the original design)

Three issues surfaced only under the wired Cargo + PostgreSQL integration test and
were fixed during implementation:

- **Listener strong reference.** `WikiEventManager` holds listeners in a
  `WeakHashMap`; the `AuditEventListener` must be retained in a `WikiEngine` field
  or it is garbage-collected and silently records nothing.
- **Frontmatter source for read-gating.** `AuditReadPolicy`'s frontmatter lookup
  parses the raw page text via `FrontmatterParser` — the `Page.FRONTMATTER_METADATA`
  attribute is not reliably populated by providers.
- **Timestamp precision.** `event_time` is truncated to **microseconds** before
  both hashing (`AuditEntry.canonical()`) and storage, because PostgreSQL
  `TIMESTAMPTZ` rounds sub-microsecond nanoseconds — otherwise `verifyChain` would
  report a false tamper on clean data.

## Open items / v2

- **CI does not yet prove the locked grant.** The integration test's PostgreSQL
  runs the app role as a superuser, for which `REVOKE UPDATE/DELETE` is a no-op, so
  the IT skips the immutability assertion. Production uses a non-superuser app role
  where the grant is enforced. Follow-up: add a dedicated non-superuser role to the
  IT to actually exercise the `REVOKE`.
- **`ensurePartition` needs schema `CREATE`.** Runtime partition creation requires
  the app role to hold `CREATE` on the schema; pre-created partitions cover through
  Aug 2026. The v2 privileged retention job should own partition management (and
  pre-create future partitions).
- **Audit init is nested in `initKnowledgeGraph`.** A RuntimeException earlier in KG
  init would skip audit init. Acceptable for v1 (datasource always present); v2
  should hoist audit init to its own top-level step.
- Retention purge via privileged partition-drop, archive-to-NAS first.
- Optional durable staging for zero event loss.
- Outbound forwarding to external WORM / SIEM (with the webhook work).
- Per-space read-audit policy UI (v1 ships frontmatter + cluster flag only).

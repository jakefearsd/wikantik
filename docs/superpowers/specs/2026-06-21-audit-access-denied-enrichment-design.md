# Enrich `access.denied` audit records for forensics

**Date:** 2026-06-21
**Status:** Approved — ready for implementation plan
**Module(s):** `wikantik-main` (`com.wikantik.audit`, `com.wikantik.auth`), `wikantik-observability`, `wikantik-it-tests`

## Problem

On the production **OBSERVABILITY → Audit** page, `access.denied` rows show a
null `target`, so an operator cannot tell *what resource* was being accessed,
*which action* was attempted, or *from where*.

### Root cause

The audit pipeline is event-driven. `DefaultAuthorizationManager.checkPermission()`
fires `WikiSecurityEvent.ACCESS_DENIED` and passes the **`java.security.Permission`
object** as the event's `target` (typically a `PagePermission` carrying the page
name *and* the denied action). But `AuditEventListener.mapSecurity()` copies only
`category`, `eventType`, `outcome`, and the actor principal — it **never reads
`se.getTarget()`**, so the permission (the actual resource + action) is dropped.

The same method also leaves four already-supported columns empty for *every*
security event: `sourceIp`, `userAgent`, `correlationId`, and `detail`. The DB
schema, `JdbcAuditRepository`, and the admin UI (`AdminAuditResource`) already
persist and render these columns — they are simply never populated on this path.

Everything we need is already reachable, with **no event/signature/schema changes**:

- **Resource + action** — already in the event as `se.getTarget()` (the `Permission`).
- **Source IP / correlation ID / URI / method** — already in the Log4j2
  `ThreadContext` (MDC) set by `RequestCorrelationFilter`, and security events
  dispatch **synchronously on the request thread** (`WikiEventManager.fireEvent`
  calls `listener.actionPerformed(event)` inline), so the listener can read them.
- **User-agent** — not yet captured in the MDC; a one-line addition to
  `RequestCorrelationFilter`.

## Goal

Full security forensics for denials. Each `access.denied` record answers: *who*,
*what resource*, *which action*, *from what IP / user-agent*, *which request*
(correlation ID), and *which endpoint* (URI/method). Request-context enrichment
(`sourceIp` / `userAgent` / `correlationId`) is applied uniformly to **all**
audited events, which also gives `login.failed` brute-force visibility for free.

### Out of scope

- Token / API-key denials in `McpAccessFilter` and `ToolsAccessFilter`. These do
  not use the JAAS permission model and never fire `WikiSecurityEvent`. Auditing
  them is a separate, larger effort.
- Purging or backfilling existing audit rows (see "Existing records" below).

## Coverage (no extra wiring needed)

The main authorization paths already route through
`AuthorizationManager.checkPermission()`, which fires the enriched event:

- `/api/*` page-permission checks → `RestServletBase.hasPagePermission()` →
  `PermissionFilter.canAccess()` → `checkPermission()`.
- `/admin/*` → `AdminAuthFilter` → `checkPermission(session, AllPermission)`.

So enriching the event listener enriches REST and admin denials automatically.

## Components

Each component has one purpose, a narrow interface, and is unit-testable in
isolation.

### 1. `AuditRequestContext` (new — `com.wikantik.audit`)

A pure reader over the Log4j2 `ThreadContext` (MDC). Exposes:

- `sourceIp()` — from the `remoteAddr` MDC key
- `userAgent()` — from the `userAgent` MDC key (new, see component 2)
- `correlationId()` — from the `requestId` MDC key
- `uri()` — from the `uri` MDC key
- `method()` — from the `method` MDC key

Every getter returns `null` when its key is absent. On background / non-request
threads (e.g. a scheduler-triggered permission check) the MDC is empty, so the
corresponding audit columns are simply `null` — graceful degradation, no special
casing.

**Test:** `AuditRequestContextTest` — set `ThreadContext` entries, assert getter
values; clear them, assert `null`s.

### 2. MDC key single-source-of-truth + `userAgent` capture

`RequestCorrelationFilter` currently publishes `requestId`, `method`, `uri`,
`remoteAddr`. Add one key, `userAgent`, populated from
`httpRequest.getHeader("User-Agent")` and removed in the `finally` block alongside
the others.

The MDC key-name strings are lifted into **one shared constant holder** referenced
by both `RequestCorrelationFilter` and `AuditRequestContext`, so the two sides
cannot drift on a key rename. The exact module placement of the holder (a low-level
module both `wikantik-main` and `wikantik-observability` already depend on) is
resolved in the implementation plan; the requirement here is a single source of
truth, not duplicated string literals.

**Test:** `RequestCorrelationFilterTest` — assert the `userAgent` MDC key is set
during `doFilter` and removed afterward.

### 3. `AuditEventListener` changes

- **New `permissionTarget(Permission)` helper** maps a denied permission to target
  fields per this table:

  | Permission        | `targetType`        | `targetId`          | `targetLabel`              |
  |-------------------|---------------------|---------------------|----------------------------|
  | `PagePermission`  | `page`              | page name           | `edit → SecretPage`        |
  | `WikiPermission`  | `wiki`              | action (`createPages`) | `createPages`           |
  | `GroupPermission` | `group`             | group name          | `edit → Admins`            |
  | `AllPermission`   | `all`               | `*`                 | `admin (AllPermission)`    |
  | `null` / unknown  | `permission` / null | `getName()` or null | `getActions() → getName()` |

  The action-qualified `targetLabel` (e.g. `edit → SecretPage`) surfaces the
  attempted action at a glance in the admin grid without expanding `detail`.

- **`detail` JSON for `access.denied`** — built with the existing `escape()`
  helper, of the form:

  ```json
  {"permission":"*:SecretPage","uri":"/api/pages/SecretPage","method":"PUT"}
  ```

  (`permission` is `Permission.getName()`; `uri`/`method` come from
  `AuditRequestContext`. Omit keys whose source value is null.)

- **Uniform request-context enrichment** — every `AuditEntry` the listener builds
  (security, page, rename) is populated with `sourceIp` / `userAgent` /
  `correlationId` from `AuditRequestContext`. One code path, applied to all mapped
  entries.

**Test:** `AuditEventListenerTest` — new cases:
- `ACCESS_DENIED` with `PagePermission` → assert `targetType=page`,
  `targetId=<page>`, `targetLabel=<action> → <page>`, `detail` contains permission.
- `ACCESS_DENIED` with `WikiPermission` → assert `targetType=wiki`,
  `targetId=<action>`.
- `ACCESS_DENIED` with `null` permission (the `session == null` branch) → no NPE,
  target fields null/`permission`, entry still recorded.
- With `ThreadContext` populated, assert `sourceIp` / `userAgent` / `correlationId`
  land on the entry.

## Data flow

```
checkPermission() denies
  → fireEvent(ACCESS_DENIED, user, permission)        [request thread, synchronous]
    → AuditEventListener.mapSecurity(se)
        ├─ permissionTarget(se.getTarget())  → targetType/targetId/targetLabel + detail
        └─ AuditRequestContext (MDC)          → sourceIp/userAgent/correlationId
    → AuditService.record(entry)
      → AuditWriterThread (async persist)
```

No changes to `WikiSecurityEvent`, the audit DB schema, `JdbcAuditRepository`, or
`AuditEntry` (all target/context columns already exist).

## Error handling

- `null` permission (the `session == null` deny branch) must not NPE — target
  fields degrade to null / `permission` and the entry is still recorded.
- Missing MDC keys → null columns (non-request threads).
- `detail` JSON omits keys whose value is null; page/permission names are escaped
  via the existing `escape()` helper.

## Existing records

Forward-only. Old `access.denied` rows keep their null `target`; they remain valid
history and the hash chain stays intact. **No purge** — the audit log is
tamper-evident and a wipe is a heavier statement than this change warrants. Keeping
history adds zero implementation cost. (A clean-slate `DELETE` remains available as
an ad-hoc operation outside this change if ever wanted.)

## Testing summary

| Test | Level | Asserts |
|------|-------|---------|
| `AuditRequestContextTest` | unit | MDC present → values; absent → nulls |
| `AuditEventListenerTest` (new cases) | unit | target/detail for Page/Wiki/null perms; request-context fields |
| `RequestCorrelationFilterTest` | unit | `userAgent` MDC set during request, removed after |
| `AuditLogIT` (extended) | integration | anonymous REST 403 on a restricted page → `/admin/audit` row has `target` + `sourceIp` |

TDD: each test is written to fail against current behavior before the
corresponding implementation lands.

# Audit `access.denied` Context + Speculative-Check Suppression — Design

**Date:** 2026-06-23
**Status:** Approved (design)
**Area:** Authorization / Audit (`com.wikantik.auth`, `com.wikantik.audit`, `wikantik-event`, `wikantik-rest`, `wikantik-frontend`)

## Problem

Production "Observability → Audit" `access.denied` (outcome `DENIED`) records are hard to
diagnose. Two distinct causes:

1. **The audit log is polluted by speculative permission checks.** `checkPermission()` fires
   `ACCESS_DENIED` on *every* deny, including checks that are not access attempts — search-result
   filtering, sitemap/graph visibility, `[{InsertPage}]` inclusion, and UI capability hints. The
   single worst offender: `PageResource` (lines 218–222) fires **five** `hasPagePermission` checks
   per page load purely to build the client's edit/comment/upload/rename/delete affordance map; each
   denial writes an audit row for an action nobody attempted. This burns storage + CPU and makes the
   real signal hard to find.

2. **The few real (enforced) denials are under-described, and the UI hides what is recorded.** The
   backend already stores `targetLabel` (e.g. `"edit → Main"`), a `detail` JSON
   (`{permission, uri, method}`), `sourceIp`, `userAgent`, and `correlationId` — but
   `AdminAuditPage.jsx` renders only 7 lean columns and drops all of it. Separately, the three deny
   branches in `DefaultAuthorizationManager.checkPermission` (null-session, policy-grant-missing,
   ACL-denied) collapse into one undifferentiated `access.denied` with no recorded *reason*.

## Goals

- Stop speculative permission checks from producing audit rows (zero storage, zero added CPU, no
  security-log line) — they become pure boolean queries.
- Enrich the remaining **enforced** denials with the diagnostic context needed to answer "who/what
  tried to do what, and why was it denied": deny `reason`, authentication status, and roles held.
- Surface the full record to the operator: audit rows become clickable, opening a record-level
  detail modal that renders every recorded field (the table stays lean).

## Non-Goals (YAGNI)

- **No database migration.** Enrichment rides in the existing `detail` JSON, which is already part of
  the `canonical()` hash and already returned by the REST API. Old rows still verify; new rows just
  carry a richer `detail`.
- No new audit columns and no `reason` filter in v1 (a `reason` column is a clean future follow-up if
  filtering is ever wanted).
- No change to the CSV-export column set.
- No change to `ACCESS_ALLOWED` auditing — the listener already drops those events
  (`ACCESS_ALLOWED` is absent from `AuditEventListener.SECURITY_AUDITS`).
- Not adopting "fire only at enforcement points" (see Approaches → Model 2).

## Approaches Considered

**Where to draw the audit line.**
- **Model 1 — evaluate-vs-enforce split (chosen).** `checkPermission` keeps firing events (audited);
  a new pure `isPermitted` fires nothing. Callers choose. *Fails safe:* a missed migration leaves
  noise, never drops a real denial.
- **Model 2 — fire only at enforcement points.** Make the evaluator always-pure and instrument each
  403/redirect site to fire. Conceptually tidy but *fails unsafe* — miss one enforcement site and you
  silently lose audit coverage (a security regression). **Rejected** for an audit feature.

**Deny-reason transport.** Extend `WikiSecurityEvent` with an optional attributes map (it is `final`,
so no subclassing) **vs.** a thread-local. **Chosen:** event attributes — explicit, travels with the
event, no parallel state.

**Enrichment storage.** New DB columns (filterable; needs migration + `canonical()` bump) **vs.** fold
into the existing `detail` JSON. **Chosen:** `detail` JSON — zero migration, hash chain untouched.

## Design

### 1. Pure evaluator + audited entry point — `DefaultAuthorizationManager`

Extract the existing allow/deny logic into a private `decide(session, permission)` that returns a
small result `{allowed, reason}` and fires **nothing**. Then:

- `checkPermission(session, permission)` = `decide(...)` then fire exactly one `ACCESS_ALLOWED` or one
  `ACCESS_DENIED` (audited). Same allow/deny outcomes as today; this consolidates today's five
  scattered fire-sites into one allow + one deny path.
- `isPermitted(session, permission)` — **new method on the `AuthorizationManager` interface** =
  `decide(...).allowed()` only. No `WikiSecurityEvent`, no audit row, no security-log line.

`reason` values:

| reason | branch (current line) | meaning |
|---|---|---|
| `no-session` | `checkPermission` null guard (≈144) | null session or null permission — no/ malformed auth context |
| `policy-denied` | policy check fails (≈173) | the actor's roles lack even the base policy grant |
| `acl-denied` | ACL loop falls through (≈216) | base grant present, but the page `[{ALLOW}]` ACL excludes the principal |

The `ACCESS_ALLOWED` bootstrap-admin and AllPermission branches keep firing the plain (un-enriched)
allowed event and are unaffected.

### 2. Enrich the denial (deny branches only)

Only the deny path computes enrichment (allows stay lean — `ACCESS_ALLOWED` fires on every successful
page view and must not pay for role/status extraction). At the deny fire-site, attach an attributes
map to the event:

- `reason` — from `decide`.
- `authStatus` — `session.getStatus()` (`anonymous` / `asserted` / `authenticated`), or `none` when
  the session is null.
- `roles` — comma-joined `session.getRoles()` principal names, or empty when the session is null.

**Transport:** add to `WikiSecurityEvent` an optional `transient Map<String,String> attributes` field,
one additive constructor overload, and a `getAttributes()` getter. Existing constructors pass an empty
map (backward compatible). `DefaultAuthorizationManager`'s deny branch builds the map and uses the new
overload; every other caller is untouched.

**Composition:** `AuditEventListener.deniedDetail(...)` already builds the `detail` JSON from
`{permission}` + MDC `{uri, method}`. Extend it to merge the event's `attributes`
(`reason`/`authStatus`/`roles`) into the same JSON object. JSON escaping reuses the existing `escape`
helper. No other listener consumes `ACCESS_DENIED` (verified: producer
`DefaultAuthorizationManager`, consumer `AuditEventListener` only).

### 3. Route speculative callers to the silent path

`PermissionFilter` gains `canAccessQuietly(session, page, action)` → `isPermitted`. The shared REST
wrappers split by intent:

- `RestServletBase.hasPagePermission(...)` — a *query* (its name says so) → becomes **quiet**
  (`canAccessQuietly`). Used by capability-hint builders.
- `RestServletBase.checkPagePermission(...)` — *enforces* (sends 403) → calls the audited `canAccess`
  directly so the denial still audits. (It no longer delegates to `hasPagePermission`, which is now
  quiet.)

Classification of every current call site:

| Call site(s) | Verdict | Change |
|---|---|---|
| `RestServletBase.checkPagePermission` (403), `WikiPageFormatFilter:160` (404), `AdminAuthFilter:134`, `AttachmentServlet:273` (+ `:607`, confirm), `DerivedIngestResource:256`, `DefaultUserManager:189`, `DefaultAuthorizationManager.hasAccess()` redirect | **Enforce** | keep `checkPermission` / `canAccess` (audited) |
| `PageResource:218-222`, `CommentThreadResource:282` (capability hints via `hasPagePermission`) | **Speculative** | `hasPagePermission` → quiet |
| `DefaultLuceneSearcher:319`, `SitemapServlet:177`, `KgGraphSnapshotBuilder:162`, `DefaultPageGraphService:275`, `InsertPage:139`, `WikiContext:798` (`hasAdminPermissions`), `OntologyWiringHelper:78` (guest visibility filter) | **Speculative** | → `isPermitted` / `canAccessQuietly` |

Guiding principle, applied (and re-confirmed) per site during implementation: **a check whose result
gates a request the caller explicitly made (denial → 403/redirect/blocked action) is enforcement and
must audit; a check whose result only shapes a response the caller is assembling (filtering a list,
toggling a UI affordance, deciding inclusion/visibility) is speculative and must be silent.**

`WikiContext.hasAdminPermissions()` (speculative probe) is consumed by `SpamFilter`,
`RecentChangesPlugin`, `JDBCPlugin`, and `AttachmentServlet` — switching its single internal
`checkPermission` to `isPermitted` silences all of them at once.

### 4. Clickable rows → record detail modal (frontend only)

The REST endpoint already serializes every field (`AdminAuditResource.toJson`, lines 205–227:
`detail`, `sourceIp`, `userAgent`, `correlationId`, `targetLabel`, `actorType`, `rowHash`,
`prevHash`, …). The frontend already fetches all of it and renders 7 columns. So this is **pure
frontend**:

- Table rows in `AdminAuditPage.jsx` become clickable (keyboard-accessible: `role`/`tabIndex`/Enter),
  opening a modal for that record.
- The modal renders the full record, grouped:
  - **Event** — seq, time, category, eventType, outcome
  - **Actor** — actorPrincipal, actorType, actorId
  - **Target** — targetType, targetId, targetLabel
  - **Request / why** — parsed `detail` (permission, uri, method, **reason**, **authStatus**,
    **roles**) + sourceIp, userAgent, correlationId
  - **Integrity** — rowHash, prevHash (secondary/collapsed)
- `detail` is a JSON string parsed defensively (try/catch; render raw text on parse failure).
- Reuse the existing `src/components/ui` modal/dialog pattern (e.g. the one `EdgeExplorer` uses) per
  the shared-UI-layer convention; the lean table columns are unchanged.

## Data Flow (enforced denial, end to end)

```
request → enforcement caller (e.g. checkPagePermission)
  → PermissionFilter.canAccess → AuthorizationManager.checkPermission
    → decide() = {allowed:false, reason:"acl-denied"}
    → fire ACCESS_DENIED with attributes {reason, authStatus, roles}, target=Permission
  → caller sends 403
WikiSecurityEvent → AuditEventListener.mapSecurity
  → applyPermissionTarget (target/label) + deniedDetail (permission + uri/method + reason/authStatus/roles)
  → AuditService.record → JdbcAuditRepository (hash-chained append)
GET /admin/audit → AdminAuditResource.toJson (all fields) → AdminAuditPage row → click → modal
```

Speculative path: caller → `isPermitted`/`canAccessQuietly` → `decide()` → boolean. No event, no row.

## Testing (TDD)

Unit:
- `DefaultAuthorizationManagerTest`: `isPermitted` returns the same boolean as `checkPermission` for
  allow, policy-deny, acl-deny, and null-session cases — **and fires zero `WikiSecurityEvent`s**
  (captured via an event-manager/listener spy). Write the "fires nothing" assertion first (red).
- `checkPermission` deny fires `ACCESS_DENIED` carrying `{reason, authStatus, roles}` correct for each
  of the three branches.
- `AuditEventListenerTest`: an `ACCESS_DENIED` event with attributes yields an `AuditEntry` whose
  `detail` JSON contains `reason`/`authStatus`/`roles` alongside `permission`/`uri`/`method`; absence
  of attributes degrades cleanly to today's output.
- Caller migration: a `PageResource` capability-map request produces **no** audit rows for denied
  capabilities; an enforced `checkPagePermission` denial produces exactly one enriched row.

Frontend:
- `AdminAuditPage.test.jsx`: clicking a row opens the modal; the modal renders `detail` fields
  (sourceIp, userAgent, reason, …); Escape/close hides it; defensive render on malformed `detail`.

Integration:
- A search (and a sitemap fetch) over a corpus containing a restricted page adds **zero** new
  `access.denied` rows; a direct enforced denial adds exactly one row whose `detail` carries the new
  fields. Run via the standard sequential IT profile (`-Pintegration-tests -fae`).

## Risks / Verification Notes

- **Fail-safe by construction:** mis-classifying an enforcement site as speculative is the only
  dangerous error (lost audit coverage); the principle + the per-site review + the "real 403 still
  audits" tests guard it. Mis-classifying the other way only leaves noise.
- Confirm during implementation that nothing consumes `ACCESS_ALLOWED` for state (only the audit
  listener was found, and it ignores `ACCESS_ALLOWED`), so suppressing speculative allows is safe.
- `WikiSecurityEvent` lives in `wikantik-event`; the added field/overload is additive and must not
  change existing constructor signatures.
- Arch tests exist (`DecompositionArchTest`); adding a method to `AuthorizationManager` and a helper
  to `PermissionFilter` should be neutral, but run the full unit suite before committing.

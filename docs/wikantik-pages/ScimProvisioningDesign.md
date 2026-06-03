---
cluster: wikantik-development
canonical_id: 01KT79SMWW6FSVEKQ89RJ4PT4N
title: SCIM Provisioning Design
type: design
status: active
date: '2026-06-03'
author: claude-opus
summary: SCIM 2.0 (RFC 7643/7644) user provisioning for Wikantik. Adds /scim/v2/Users + discovery endpoints so an IdP (Okta/Entra) can automate onboarding and — the priority — offboarding via a single unified, audited decommission path. Soft-decommission (active:false and DELETE both deactivate via the existing indefinite-lock mechanism) preserves audit-actor and page-ownership references. Groups are a deferred fast-follow.
tags:
- design
- scim
- provisioning
- enterprise
- auth
---

# SCIM Provisioning Design

## Purpose

SSO (Google OIDC, live) solved *authentication* but not *lifecycle*. Enterprises
run Okta / Microsoft Entra and expect **automated** user provisioning and,
critically, **deprovisioning on offboard**. Today a departed employee's local
account and group memberships persist until an admin manually locks them — itself
an audit finding. This adds SCIM 2.0 so the IdP drives the user lifecycle, with
**decommission as standard and uniform as possible**: one canonical, audited
deactivation path shared by SCIM and the admin UI.

## Scope

### In scope (this increment)

- `POST/GET/PUT/PATCH/DELETE /scim/v2/Users` (RFC 7643/7644 `User` core schema).
- Discovery endpoints IdPs probe: `/scim/v2/ServiceProviderConfig`,
  `/scim/v2/Schemas`, `/scim/v2/ResourceTypes`.
- The unified **`UserLifecycleService`** decommission path (and refactoring the
  existing admin lock/unlock onto it).
- Bearer-token auth; SCIM-format errors; audit integration.

### Out of scope (deferred)

- ~~`/scim/v2/Groups`~~ — **SHIPPED** (membership sync). See
  [SCIM Groups design](../superpowers/specs/2026-06-03-scim-groups-design.md): hard
  delete, externalId keyed on displayName, member PATCH (incl. `members[value eq …]`),
  and the hard invariant that **SCIM Groups never grant the Wikantik `Admin` role**
  (groups and the role table are separate stores; enforced by an IT assertion).
- **Physical user deletion via SCIM** — `DELETE` performs a *soft* decommission;
  hard removal stays a separate, explicit admin-only action.
- **Full SCIM filter / PATCH grammar** — only the subsets IdPs actually use ship
  now (see "Supported subsets").

## Decommission: the unified path (priority)

There is no boolean `active` column; account disable is expressed by
`lockExpiry` — `setLockExpiry(INDEFINITE_LOCK_EXPIRY)` (a year-9999 sentinel)
disables, `setLockExpiry(null)` re-enables, and `UserProfile.isLocked()` reads it.
The existing admin `/admin/users/{login}/lock|unlock` endpoints already do exactly
this **inline**.

This increment extracts that into a single service so decommission behaves
identically regardless of trigger:

```
UserLifecycleService
  deactivate( uid, actor, reason )  → setLockExpiry(INDEFINITE_LOCK_EXPIRY); db.save; audit "user.deactivate"
  reactivate( uid, actor, reason )  → setLockExpiry(null);                   db.save; audit "user.reactivate"
```

- **`AdminUserResource` `/lock` and `/unlock` are refactored to call this
  service** (removing their inline lock logic) — the admin UI and SCIM now share
  one audited mechanism.
- **SCIM mapping:** `active:false` (via PATCH or PUT) **and** `DELETE /Users/{id}`
  both call `deactivate`; `active:true` calls `reactivate`. This is a **soft**
  decommission: the user row is retained, so audit-log actor references and
  page-ownership rows stay intact. SCIM permits soft-delete, and Okta/Entra
  default to deactivate-not-delete, so this is standard-conformant and safe.

## Architecture

A dedicated **`wikantik-scim`** module (mirrors the `wikantik-admin-mcp` /
`wikantik-knowledge` protocol-server pattern; isolates spec-heavy code from
`wikantik-rest`). Depends on `wikantik-main` (user managers, `UserLifecycleService`,
`AuditService`) and `wikantik-http` (filter base).

```
IdP (Okta/Entra) ──Bearer──► ScimAccessFilter ──► ScimUserResource
                                                      │
                          ┌───────────────────────────┼───────────────────────────┐
                          ▼                           ▼                           ▼
                   ScimUserMapper            ScimFilterParser /            UserLifecycleService
              (UserProfile ↔ SCIM JSON)      ScimPatchApplier          (deactivate / reactivate)
                          │                                                       │
                          ▼                                                       ▼
                    UserManager / UserDatabase  (users table)              AuditService (audit_log)
```

### Components

- **`ScimAccessFilter`** — bearer-token auth mirroring `McpAccessFilter`
  (constant-time compare of the IdP token against `wikantik.scim.token`; 401 on
  miss). Guards all `/scim/v2/*` paths.
- **`ScimUserResource`** — servlet surface (registered in `web.xml`): `POST`
  create, `GET /{id}`, `GET` list+filter, `PUT /{id}` replace, `PATCH /{id}`,
  `DELETE /{id}`, plus the discovery endpoints.
- **`ScimUserMapper`** — bidirectional `UserProfile ↔ SCIM User JSON`. One
  responsibility (the schema table below).
- **`ScimFilterParser`** + **`ScimPatchApplier`** — the small spec subsets.
- **`UserLifecycleService`** (new, `wikantik-main`) — the unified decommission
  path above.
- **Audit integration** — every op emits an `admin`-category audit event.

## Schema mapping (`User` core → `users`)

| SCIM attribute | Internal |
|---|---|
| `id` | `uid` (stable internal id) |
| `userName` | `loginName` |
| `externalId` | `sso.subject` attribute (SSO reconciliation) |
| `name.givenName` / `familyName` / `formatted` | `fullName` |
| `emails[primary].value` | `email` |
| `displayName` | `wikiName` |
| `active` | `!isLocked()` (mutated only via `UserLifecycleService`) |
| `meta.created` / `lastModified` / `location` / `resourceType` | derived |

**Password:** SCIM-provisioned users authenticate via **SSO**, so create stamps
`sso.subject = externalId` and sets a random unusable password; a `password`
supplied in the request is honored if present.

## Supported subsets (YAGNI)

- **Filter:** only `userName eq "…"` and `externalId eq "…"` (what IdPs use to
  de-duplicate before create). Any other filter → `400` with `scimType =
  invalidFilter` (never silently return wrong results).
- **PATCH (RFC 7644 §3.5.2):** `add` / `remove` / `replace` on simple paths
  (`active`, `name.*`, `emails`, `displayName`). The load-bearing case —
  `replace` of `active:false` — is first-class. Complex value-path filters in a
  PATCH path → `400 invalidPath`.
- **List:** SCIM pagination (`startIndex` / `count`) wrapped in the
  `ListResponse` envelope.

## SSO reconciliation (fail-closed)

SCIM create stamps `sso.subject = externalId`, so a later SSO login (keyed on the
IdP `sub`) adopts the same account rather than creating a duplicate. SCIM **must
not** adopt or overwrite a pre-existing *non-SSO* local account of the same
`userName` that lacks the marker — it returns `409` with `scimType = uniqueness`
(fail closed), mirroring the existing SSO auto-provision collision rule.

## Auth, audit, errors

- **Auth:** bearer token via `wikantik.scim.token` (the standard Okta/Entra
  OAuth-bearer scheme); `ScimAccessFilter` enforces it on `/scim/v2/*`.
- **Audit:** every operation emits an audit event via the existing `AuditService`.
  SCIM-resource ops use SCIM-specific types (`scim.user.create`,
  `scim.user.update`). Deactivate / reactivate fire through the shared
  `UserLifecycleService`, so they use **trigger-agnostic** types (`user.deactivate`
  / `user.reactivate`) — identical whether the admin UI or SCIM initiated them —
  with the trigger captured in the audit `detail` (e.g. `{"source":"scim"}` vs
  `{"source":"admin-ui"}`). A SCIM `DELETE` records a `user.deactivate` with
  `{"source":"scim","via":"delete"}`.
- **Errors:** SCIM error schema
  (`urn:ietf:params:scim:api:messages:2.0:Error` with `status`, `scimType`,
  `detail`); content type `application/scim+json`.

## Testing

**Unit:**
- `ScimUserMapper` round-trip (UserProfile ↔ SCIM JSON), including `active ↔
  isLocked` and `externalId ↔ sso.subject`.
- `ScimFilterParser`: `userName eq` / `externalId eq` parse; unsupported →
  rejected.
- `ScimPatchApplier`: `replace active:false`, attribute add/remove/replace;
  complex path → rejected.
- `UserLifecycleService`: `deactivate` sets the indefinite lock + emits the audit
  event; `reactivate` clears it; admin lock/unlock now route through it.
- SSO fail-closed: SCIM create against a colliding non-SSO `userName` → `409`.

**Integration (running app + PostgreSQL):**
- Full cycle: create → list + filter by `userName` and `externalId` → `PATCH
  active:false` → assert the account is locked **and** an audit row exists →
  `active:true` reactivates → `DELETE` soft-deactivates (the row is still present)
  → bad/absent bearer token → `401`.

## Decisions (recorded)

1. **Dedicated `wikantik-scim` module** (vs folding into `wikantik-rest`) — keeps
   the spec surface isolated, consistent with other protocol-server modules.
2. **Bearer token via `wikantik.scim.token`** config (vs a DB-stored credential).
3. **Minimal filter / PATCH subsets** — full grammar deferred until an IdP needs
   it.
4. **SCIM-created users are SSO-auth** (random password + `sso.subject` stamp).

## As-built notes

- The SCIM bearer token reaches the deployed app via the `wikantik.scim.token`
  system property (prod) or the filter `<init-param>` (empty in the shipped
  web.xml). The integration test injects it into the **Cargo container JVM** via
  `cargo.jvmargs` (`-Dwikantik.scim.token=it-scim-token`).
- The SCIM IT (the first chain member carrying a `detail` payload) surfaced two
  pre-existing audit bugs, both fixed alongside this work: `audit_log.detail` is now
  stored as TEXT not JSONB (migration V037) so the hash chain verifies for
  detail-bearing rows, and `page.rename` events are now actually audited (the
  listener is registered against the `PageRenamer`, which fires the rename event).

## Open items / next

- `/scim/v2/Groups` membership sync — **shipped** (see above).
- `externalId` persistence for groups, and member paging for very large groups — only
  if an IdP requires them.
- Optional rotating/DB-stored SCIM credentials and multiple-IdP tokens.
- `ServiceProviderConfig` advertising of supported features must stay in sync if
  the filter/PATCH subsets are later widened.

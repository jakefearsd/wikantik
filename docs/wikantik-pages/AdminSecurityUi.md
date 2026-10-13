---
audience:
- humans
depends-on:
- DatabaseBackedPermissions
- JspToReactMigration
status: active
title: 'Admin Security UI: Groups and Policy Grants'
type: article
canonical_id: 01KQ0P44JKW93WGGG83BXCHYMZ
kg_include: true
date: '2026-03-28'
enables:
- KnowledgeAdminUi
tags:
- security
- admin-ui
- permissions
- groups
- policy-grants
- wikantik-development
related:
- WikantikDevelopment
- DatabaseBackedPermissions
- JspToReactMigration
summary: 'Admin Security screen at /admin/security: manage wiki groups and the database-backed
  policy grants that decide what each role may do.'
cluster: wikantik-development
---
# Admin Security UI

**Admin → Security** (`/admin/security`) is the React admin screen for the two things that decide what an account may do: wiki **groups** and **policy grants**. It replaced the legacy JSP admin pages. It is part of the SPA (`wikantik-frontend/src/components/admin/AdminSecurityPage.jsx`), and every action calls a REST servlet under `/admin/*` that `AdminAuthFilter` protects.

## What the screen does

The page has two sections, switched by the buttons at the top.

**Groups** (`/admin/groups` and `/admin/groups/{name}`, `AdminGroupResource`)

- List, search and sort groups by name or member count; create or edit a group and its membership in a modal; delete a group after a confirmation.
- Members must be **login names** of existing accounts. The server rejects any other entry with HTTP 400 and names the bad entries, because a full name or wiki name never matches at authorization time.
- A change applies immediately; no restart is needed.

**Policy Grants** (`/admin/policy` and `/admin/policy/{id}`, `AdminPolicyResource`)

- A table of the `policy_grants` rows showing the principal (type badge and name), permission type, target and actions, with Edit and Delete on each row and **+ Create Grant** above it.
- The form takes a principal type (`role`, `user` or `group`), a principal name (for a `user` grant, the account's login name), a permission type (`page`, `wiki` or `group`), a target and the actions as checkboxes. The **Grant AllPermission (full admin access)** checkbox writes the canonical `all` / `*` / `*` row.
- The server refuses a wildcard `*` action on a `page`, `wiki` or `group` grant, and refuses `AllPermission` for the built-in roles `All`, `Anonymous`, `Asserted` and `Authenticated`. See [Database-Backed Permissions](DatabaseBackedPermissions) for the table, the seeded defaults and the full rules.
- Every save refreshes the in-memory policy, so the new grant applies at once.
- Scoped admin-area grants (permission type `admin`, action `access`, target set to the first path segment after `/admin/`, for example `insights`) appear in the grid but are created by `POST /admin/policy`, not by the form. They let an account reach one `/admin/*` area without `AllPermission`.

## Who can open it

`AdminAuthFilter` lets a request through when the session holds `AllPermission`, or a scoped `admin` grant for the area it targets. `AllPermission` comes from membership of the `Admin` **group**. An account that has only a row in the `roles` table authenticates but gets HTTP 403 on `/admin/*`. The SPA also redirects a signed-in user without the `Admin` role to `/wiki/Main`, but that is a convenience; the server-side filter is the control.

## Neighbouring admin screens

These sit beside Security in the admin sidebar and cover the rest of the access picture:

| Screen | Route | What it manages |
|---|---|---|
| Users | `/admin/users` | create, edit, delete, lock and unlock accounts, and bulk actions (`AdminUserResource`) |
| API Keys | `/admin/apikeys` | issue, scope (`mcp_read`, `mcp_admin`, `tools`, `all`) and revoke the bearer keys used by MCP and `/tools/*` clients; the plaintext token is shown once |
| Audit | `/admin/audit` | search the tamper-evident, hash-chained audit log, verify its chain, export CSV |

Group-membership changes are audited as `group.member.add` and `group.member.remove`, and grant changes as `policy.grant.update` and `policy.grant.delete`. Issuing a key is audited as `apikey.issue`; rotating or revoking a key through the self-service Preferences page is audited as `apikey.rotate` and `apikey.revoke`, whereas a revoke made on `/admin/apikeys` is written only to the application log.

## What it does not have

There is no session monitor or "kill switch", no token-lifecycle screen for OAuth2/OIDC tokens (single sign-on through pac4j only establishes a normal login session; Wikantik does not store the identity provider's tokens), no JWT claims cache, no Redis broadcast and no dual-control approval step. Revoking an API key is a soft delete on `/admin/apikeys`, and the in-process verification cache (60-second TTL) is evicted on the node that handled the revoke.

For the operator reference, see `docs/admin/Security.md`, `docs/admin/AdminPanel.md`, `docs/admin/ApiKeys.md` and `docs/admin/AuditLog.md` in the repository.

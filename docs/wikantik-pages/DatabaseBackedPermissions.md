---
kg_include: true
type: article
summary: How Wikantik stores role permissions in the PostgreSQL policy_grants table,
  seeds defaults, and lets admins change them without a restart.
status: active
audience:
- humans
date: '2026-03-28'
canonical_id: 01KQ0P44PBJAD0YB3EB2MHX9AH
tags:
- permissions
- policy-grants
- postgresql
- authorization
- acl
- wikantik-development
cluster: wikantik-development
title: 'Database-Backed Permissions: The policy_grants Table and Admin UI'
related:
- WikantikDevelopment
- AdminSecurityUi
- KnowledgeGraphCore
---
# Database-Backed Permissions

Wikantik keeps its role-based permission policy in PostgreSQL. The `policy_grants` table replaced the flat `WEB-INF/wikantik.policy` file that JSPWiki used, so an administrator can change what a role, group or user may do while the wiki is running, from **Admin → Security** (`/admin/security`, see [Admin Security UI](AdminSecurityUi)) or the `/admin/policy` API, with no redeploy or restart.

`policy_grants` answers "what may this role do". Two other mechanisms narrow or extend it and are *not* stored there: group membership (the `groups` and `group_members` tables) and `[{ALLOW ...}]` ACLs written in a page body. The [Wiki Permission Model Guide](WikiPermissionModelGuide) shows how the three fit together. Page visibility is decided in application code (`DefaultAuthorizationManager`, with ACLs read from the page text), not by PostgreSQL row-level security or an external policy engine.

## How it works

- `DatabasePolicy` (`wikantik-main/src/main/java/com/wikantik/auth/DatabasePolicy.java`) loads every row of `policy_grants` into an immutable in-memory snapshot at startup and answers permission checks from it.
- The snapshot is rebuilt by `DatabasePolicy.refresh()`, which `/admin/policy` calls after every create, update or delete. Nothing refreshes it on a timer, so a grant changed with raw SQL does not take effect until the next restart. Always change grants through the admin UI or `/admin/policy`.
- The policy is database-backed whenever `wikantik.datasource` is configured, which is the default. `WEB-INF/wikantik.policy` is read only when no datasource is configured (the unit-test case). If the database policy cannot be initialised, startup throws a `WikiException` instead of falling back to the file.
- Rows match by principal *type*. `role` and `group` rows match role and group principals (interchangeably). `user` rows match only the session's **login name**; a full name, wiki name or role never matches a `user` row. A row with any other `principal_type` is skipped with a warning.

## Tables

They are created by migrations in `bin/db/migrations/`, applied by `bin/db/migrate.sh` (which `bin/deploy-local.sh` and `bin/redeploy.sh` run for you). There is no separate DDL script.

- `policy_grants` (`V003__policy_grants.sql`): one row per grant with `principal_type` (`role`, `group` or `user`), `principal_name`, `permission_type` (`page`, `wiki`, `group`, `admin` or `all`), `target` and `actions`. It is unique on the first four columns.
- `users`, `roles`, `groups` and `group_members` (`V002__core_users_groups.sql`): accounts and wiki groups. Group members are stored as login names. Administrator status comes from membership of the `Admin` *group*, not from a row in `roles`.

## Default grants

`V003` seeds the baseline and never overwrites operator changes (`ON CONFLICT DO NOTHING`). `V043__admin_allpermission_canonical.sql` later collapsed the two Admin wildcard rows into one `all` row, and `V060__wiki_export_permission.sql` added `export` to `Authenticated`.

| Role | Seeded permissions |
|---|---|
| `All` (everyone, including anonymous visitors) | view any page; `editPreferences`, `editProfile`, `login` |
| `Authenticated` | `modify` and `rename` pages; `createPages`, `createGroups`, `export`; view groups; edit groups they belong to |
| `Asserted` | view groups |
| `Admin` | `AllPermission` |

Page actions form a hierarchy: `delete` implies `modify`, `modify` implies `edit` and `upload`, `rename` implies `edit`, `edit` implies `comment`, and `comment` and `upload` imply `view`. So a seeded `Authenticated` user can edit, comment and upload attachments, but only `Admin` can delete pages.

## Guardrails on editing grants

`/admin/policy` validates every write (`AdminPolicyResource`):

- A wildcard `*` action is refused on a `page`, `wiki` or `group` grant. To grant everything, use the explicit `all` permission type, which must have target `*` and actions `*`.
- `AllPermission`, and the scoped `admin` permission type, cannot be granted to the built-in roles `All`, `Anonymous`, `Asserted` or `Authenticated`, because that would make every visitor or every signed-in user an administrator.
- Valid page actions are `view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete`; wiki actions are `createPages`, `createGroups`, `editPreferences`, `editProfile`, `login`, `export`; group actions are `view` and `edit`.
- Creating, updating and deleting a grant is recorded in the tamper-evident audit log (`/admin/audit`) as `policy.grant.update` and `policy.grant.delete`.

## Impact

Database-backed groups and policy were a prerequisite for both the [Admin Security UI](AdminSecurityUi) and the Knowledge Graph, which needed database-backed group membership. For the operator view of the whole model, including scoped `admin` area grants, see `docs/admin/Security.md` in the repository.

---

**Cluster Hub:** [[WikantikDevelopmentHub|Wikantik Platform Development Hub: Architecture, MCP Protocols, and Agent Briefings]] (`wikantik-development`)

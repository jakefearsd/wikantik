---
status: active
type: article
date: '2026-03-28'
cluster: wikantik-development
title: Database-Backed Permissions
tags:
- development
- security
- postgresql
- permissions
- authorization
summary: Migration from file-based wikantik.policy XML to database-backed policy_grants
  table with admin UI management
related:
- WikantikDevelopment
- AdminSecurityUi
- KnowledgeGraphCore
- DatabaseBackedPermissions
canonical_id: 01KQ0P44PBJAD0YB3EB2MHX9AH
enables:
- AdminSecurityUi
---
# Database-Backed Permissions

The legacy JSPWiki security model used a flat XML file (`wikantik.policy`) to define role-based permissions. This was replaced with a PostgreSQL `policy_grants` table, enabling runtime permission management through the admin UI without server restarts.

## Migration

The `postgresql-permissions.ddl` script creates the `policy_grants` and `groups`/`group_members` tables. Policy is always database-backed when `wikantik.datasource` is configured (the default); there is no file-based fallback.

## Key Tables

- `policy_grants` — Maps roles to permissions (view, edit, upload, etc.)
- `groups` — Wiki groups (Admin, Authenticated, etc.)
- `group_members` — Group membership assignments

## Impact

This change was a prerequisite for both the [Admin Security UI](AdminSecurityUi) and the Knowledge Graph, which needed database-backed group membership for its `knowledge-admin` role plan.

[{Relationships}]

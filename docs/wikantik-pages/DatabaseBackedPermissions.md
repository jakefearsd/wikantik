---
type: article
related:
- WikantikDevelopment
- AdminSecurityUi
- KnowledgeGraphCore
- DatabaseBackedPermissions
tags:
- development
- security
- postgresql
- permissions
- authorization
date: '2026-03-28'
summary: Migration from file-based wikantik.policy XML to database-backed policy_grants
  table with admin UI management
status: deployed
cluster: wikantik-development
enables:
- AdminSecurityUi
---
# Database-Backed Permissions

The legacy JSPWiki security model used a flat XML file (`wikantik.policy`) to define role-based permissions. This was replaced with a PostgreSQL `policy_grants` table, enabling runtime permission management through the admin UI without server restarts.

## Migration

The `postgresql-permissions.ddl` script creates the `policy_grants` and `groups`/`group_members` tables. A property switch (`wikantik.policy.datasource`) controls whether the engine reads permissions from the database or falls back to the XML file.

## Key Tables

- `policy_grants` — Maps roles to permissions (view, edit, upload, etc.)
- `groups` — Wiki groups (Admin, Authenticated, etc.)
- `group_members` — Group membership assignments

## Impact

This change was a prerequisite for both the Admin Security UI and the Knowledge Graph, which needed database-backed group membership for its `knowledge-admin` role plan.

[{Relationships}]

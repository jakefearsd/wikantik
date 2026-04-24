---
canonical_id: 01KQ0P44JKW93WGGG83BXCHYMZ
related:
- WikantikDevelopment
- DatabaseBackedPermissions
- JspToReactMigration
cluster: wikantik-development
type: article
tags:
- development
- security
- admin
- ui
- react
summary: React admin panel for managing users, groups, permissions, and security policies
  through a unified interface
depends-on:
- DatabaseBackedPermissions
- JspToReactMigration
enables:
- KnowledgeAdminUi
status: deployed
date: '2026-03-28'
---
# Admin Security UI

The admin security UI replaced the legacy JSP-based admin pages with a React-based panel accessible at `/admin/security`. It provides unified management of users, groups, and permission policies.

## Features

- **User management** — List, search, create, edit, delete users
- **Group management** — Create groups, manage membership
- **Permission policies** — Edit role-based permissions from the database-backed `policy_grants` table
- **Role assignment** — Assign users to groups with immediate effect (no restart required)

## Architecture

The admin panel is served as part of the React SPA. Admin routes are protected by `AdminAuthFilter` which requires `AllPermission`. The REST endpoints in `wikantik-rest` handle CRUD operations against the PostgreSQL database.

[{Relationships}]

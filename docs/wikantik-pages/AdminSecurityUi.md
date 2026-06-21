---
cluster: wikantik-development
title: Admin Security UI
related:
- WikantikDevelopment
- DatabaseBackedPermissions
- JspToReactMigration
type: article
summary: React admin panel for managing users, groups, permissions, and security policies
  through a unified interface
status: active
date: '2026-03-28'
canonical_id: 01KQ0P44JKW93WGGG83BXCHYMZ
enables:
- KnowledgeAdminUi
depends-on:
- DatabaseBackedPermissions
- JspToReactMigration
tags:
- development
- security
- admin
- ui
- react
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

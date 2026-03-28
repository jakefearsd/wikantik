# Database-Backed Permissions and Group Management

## Problem

The wiki's authorization system relies on two static files loaded at startup:

- **`wikantik.policy`** — a Java security policy file defining which roles get which permissions (e.g., "Authenticated users can modify all pages")
- **`groupdatabase.xml`** — an XML file defining groups and their members

These files cannot be managed through the admin UI. Changes require editing files on disk and restarting the application. Since the wiki is always backed by PostgreSQL, these should live in the database and be administrable through the existing admin panel.

## Scope

**In scope:**
- Move group storage from XML to PostgreSQL
- Move default policy grants from the policy file to PostgreSQL
- Admin UI endpoints for managing groups and policy grants
- Bootstrap admin safety mechanism
- Migration script with seed data matching current defaults

**Out of scope:**
- Page-level ACLs — the inline `[{ALLOW ...}]` syntax in page content is unchanged
- Cluster-level permission inheritance — deferred to a future design
- Changes to the permission model itself — all existing actions (`view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete`, `createPages`, `createGroups`, `editPreferences`, `editProfile`, `login`) and their implication hierarchy remain as-is

## Architecture

The change swaps two backing stores behind existing interfaces. The authorization flow (`DefaultAuthorizationManager.checkPermission()`) and page-level ACL system (`DefaultAclManager`) are unchanged.

### What changes

| Component | Before | After |
|-----------|--------|-------|
| Group storage | `XMLGroupDatabase` (reads `groupdatabase.xml`) | `JDBCGroupDatabase` (reads `groups` + `group_members` tables) |
| Default policy | `LocalPolicy` from freshcookies library (reads `wikantik.policy`) | `DatabasePolicy` (reads `policy_grants` table) |
| Admin UI | No group/policy management | REST endpoints under `/admin/groups/*` and `/admin/policy/*` |

### What stays unchanged

- `DefaultAuthorizationManager.checkPermission()` — same flow and ACL evaluation logic; internal change to delegate policy lookups to `DatabasePolicy` instead of `LocalPolicy`, and to check the bootstrap admin override
- `DefaultAclManager` — inline `[{ALLOW}]` parsing untouched
- `PagePermission` / `WikiPermission` — same classes, same implication hierarchy
- All page-level ACL behavior
- The `GroupDatabase` interface — `JDBCGroupDatabase` implements it

## Data Model

### Tables

```sql
CREATE TABLE groups (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) UNIQUE NOT NULL,
    created     TIMESTAMP NOT NULL DEFAULT NOW(),
    modified    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE group_members (
    group_id    INTEGER NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    principal   VARCHAR(255) NOT NULL,
    UNIQUE(group_id, principal)
);

CREATE TABLE policy_grants (
    id              SERIAL PRIMARY KEY,
    principal_type  VARCHAR(10) NOT NULL,   -- 'role' or 'group'
    principal_name  VARCHAR(255) NOT NULL,  -- e.g., 'Authenticated', 'Editors'
    permission_type VARCHAR(10) NOT NULL,   -- 'page', 'wiki', or 'group'
    target          VARCHAR(255) NOT NULL,  -- e.g., '*' for all pages
    actions         VARCHAR(255) NOT NULL,  -- e.g., 'modify,rename'
    UNIQUE(principal_type, principal_name, permission_type, target)
);
```

### Valid actions

Page actions (defined as constants in `PagePermission`):
`view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete`

Wiki actions (defined as constants in `WikiPermission`):
`createPages`, `createGroups`, `editPreferences`, `editProfile`, `login`

Group actions (defined as constants in `GroupPermission`):
`view`, `edit`

The `permission_type` column accepts `page`, `wiki`, or `group`. The `actions` column holds a comma-separated subset of the corresponding action set, matching the format that the existing permission constructors already parse. Validation is at the application level, not via database constraints.

A special value of `*` in actions represents `AllPermission` (used for the Admin role/group).

For `GroupPermission`, the `target` column supports the special token `<groupmember>` meaning "groups the user belongs to" — matching the existing policy file convention.

## Implementation Components

### 1. JDBCGroupDatabase

**Package:** `com.wikantik.auth.authorize`

Implements the existing `GroupDatabase` interface, replacing `XMLGroupDatabase`. Methods:

- `initialize(Engine, Properties)` — obtains JNDI DataSource
- `groups()` — queries `groups` + `group_members`, returns `Group[]`
- `find(String name)` — finds a single group by name
- `save(Group)` — inserts or updates group and members (upsert pattern)
- `delete(Group)` — deletes group by name (cascade removes members)

Selected via property: `wikantik.groupdatabase=com.wikantik.auth.authorize.JDBCGroupDatabase`

Uses the same JNDI DataSource (`jdbc/WikiantikDS`) already configured for the wiki.

### 2. DatabasePolicy

**Package:** `com.wikantik.auth`

Replaces the `LocalPolicy` (freshcookies library) inside `DefaultAuthorizationManager`. Responsibilities:

- Loads all `policy_grants` rows on initialization
- Builds `PagePermission` and `WikiPermission` objects from the rows
- Caches grants in memory as a `Map<String, List<Permission>>` keyed by principal name
- Provides an `implies(Principal, Permission)` method matching the contract of `allowedByLocalPolicy()`
- Refreshes cache when notified of admin changes (via method call from admin endpoints)

**Integration point:** `DefaultAuthorizationManager.initialize()` is modified to instantiate `DatabasePolicy` instead of loading `LocalPolicy` from the policy file. The `allowedByLocalPolicy()` method delegates to `DatabasePolicy.implies()`. The `wikantik.authorizer.policy` property becomes unused.

### 3. Bootstrap Admin Override

**Property:** `wikantik.admin.bootstrap` (value: a login name, e.g., `admin`)

On startup, `DefaultAuthorizationManager` checks this property. If set:

- The named user always receives `AllPermission`, regardless of database state
- A log message at WARN level is emitted: `BOOTSTRAP ADMIN OVERRIDE IS ACTIVE — user 'admin' has AllPermission regardless of database grants. Remove the wikantik.admin.bootstrap property for production use.`
- This is checked in `checkPermission()` before the normal policy evaluation — if the session's login principal matches the bootstrap user, return `true` immediately

This prevents the chicken-and-egg problem where all admins are accidentally removed from the Admin group.

### 4. Admin REST Endpoints

**AdminGroupResource** — mapped to `/admin/groups/*`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/groups` | List all groups with members |
| GET | `/admin/groups/{name}` | Get a single group |
| PUT | `/admin/groups/{name}` | Create or update a group (JSON body with `members` array) |
| DELETE | `/admin/groups/{name}` | Delete a group |

**AdminPolicyResource** — mapped to `/admin/policy/*`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/policy` | List all policy grants |
| POST | `/admin/policy` | Create a new grant (JSON body) |
| PUT | `/admin/policy/{id}` | Update a grant |
| DELETE | `/admin/policy/{id}` | Delete a grant |

Both are protected by the existing `AdminAuthFilter` (requires `AllPermission`). After mutations, the endpoint calls `DatabasePolicy.refresh()` to update the in-memory cache.

The admin UI presents:
- **Groups screen:** list of groups, click to edit members (add/remove principals)
- **Policy screen:** table of grants. Each row shows role/group, permission type (page/wiki), and actions as checkboxes. Add/remove rows.

## Migration

### DDL Script

A new migration file added to `wikantik-war/src/main/config/db/`:

1. `CREATE TABLE` statements for `groups`, `group_members`, `policy_grants`
2. Seed data for `policy_grants` matching current `wikantik.policy` defaults:

```sql
-- All users
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'page', '*', 'view');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'wiki', '*', 'editPreferences,editProfile,login');

-- Anonymous
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'page', '*', 'modify');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'wiki', '*', 'createPages');

-- Asserted
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'page', '*', 'modify');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'wiki', '*', 'createPages');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'group', '*', 'view');

-- Authenticated
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'page', '*', 'modify,rename');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'wiki', '*', 'createPages,createGroups');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '*', 'view');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '<groupmember>', 'edit');

-- Admin (AllPermission)
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'page', '*', '*');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'wiki', '*', '*');
```

3. Seed data for the Admin group with the `admin` user:

```sql
INSERT INTO groups (name) VALUES ('Admin');
INSERT INTO group_members (group_id, principal)
VALUES ((SELECT id FROM groups WHERE name = 'Admin'), 'admin');
```

### Switchover

- `wikantik-custom.properties`: set `wikantik.groupdatabase=com.wikantik.auth.authorize.JDBCGroupDatabase`
- `wikantik-custom.properties`: set `wikantik.admin.bootstrap=admin` (recommended for initial deployment, remove once confirmed working)
- `deploy-local.sh`: updated to run the new migration script
- The `wikantik.authorizer.policy` property becomes unused; `DefaultAuthorizationManager` detects `DatabasePolicy` and skips policy file loading

## Testing Strategy

### Existing tests to adapt

- **`AuthorizationManagerTest`** (18 tests) — update setup to seed `policy_grants` table via H2 instead of loading the policy file. Test assertions stay identical. These validate the full authorization flow end-to-end with the new backing store.
- **`JDBCGroupDatabaseTest`** (4 tests) — already exists and validates the JDBC group database contract. Update schema to match new table structure if needed.

### New tests

**`DatabasePolicyTest`:**
- Grants loaded from database match expected permissions
- Role-to-permission mapping works for all 5 built-in roles (All, Anonymous, Asserted, Authenticated, Admin)
- `implies()` correctly evaluates permission implication chains (e.g., modify implies view)
- Group-based grants work (not just role-based)
- Cache refresh picks up changes after mutations
- Bootstrap admin override grants AllPermission when property is configured
- Bootstrap admin override is inactive when property is absent
- Startup log warning emitted when bootstrap override is active

**`AdminGroupResourceTest`:**
- List all groups returns seeded data
- Create group with members
- Update group membership (add/remove members)
- Delete group
- Non-admin access returns 403

**`AdminPolicyResourceTest`:**
- List all grants returns seeded data
- Create new grant with valid actions
- Reject grant with invalid action strings
- Update grant actions
- Delete grant
- Non-admin access returns 403
- Cache is refreshed after mutations

### Unchanged tests

- `DefaultAclManagerTest` — inline `[{ALLOW}]` parsing is unchanged
- `RestAuthorizationSecurityTest` — page-level permission enforcement is unchanged
- `GroupManagerTest` — tests the manager layer above the database; should pass with either backing store

## Key Files

| File | Change |
|------|--------|
| `wikantik-main/.../auth/authorize/JDBCGroupDatabase.java` | Modify — update to new schema |
| `wikantik-main/.../auth/DatabasePolicy.java` | New — database-backed policy provider |
| `wikantik-main/.../auth/DefaultAuthorizationManager.java` | Modify — use DatabasePolicy, add bootstrap override |
| `wikantik-rest/.../rest/AdminGroupResource.java` | New — group admin endpoints |
| `wikantik-rest/.../rest/AdminPolicyResource.java` | New — policy admin endpoints |
| `wikantik-war/.../config/db/postgresql-permissions.ddl` | New — migration DDL + seed data |
| `deploy-local.sh` | Modify — run new migration |
| `wikantik-custom.properties` template | Modify — add new properties |

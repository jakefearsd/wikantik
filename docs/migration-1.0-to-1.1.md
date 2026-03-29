# Migration Guide: Wikantik 1.0 → 1.1

## Overview

Wikantik 1.1 is a major update covering security hardening, database-backed permissions, scalability improvements, and full React SPA feature parity with the JSP UI. This guide covers the required migration steps for production deployments.

## Breaking Changes

### 1. Database Migration Required

Version 1.1 introduces a new `policy_grants` table that replaces the file-based `wikantik.policy` for authorization. You **must** run the migration script before deploying.

### 2. New Properties Required

The `wikantik-custom.properties` file needs a new property to enable database-backed policy:
```properties
wikantik.policy.datasource = jdbc/GroupDatabase
```

Without this property, the wiki falls back to the file-based `wikantik.policy` (1.0 behavior).

### 3. Anonymous Users Restricted

The 1.1 production migration restricts anonymous users to **view-only** access. In 1.0, anonymous users could edit pages and create new pages by default. After migration, only authenticated users can edit.

### 4. REST API Permission Enforcement

All REST API endpoints (`/api/pages/*`, `/api/attachments/*`) now enforce page-level ACL permissions. Previously, the REST API did not check permissions. Any API clients that relied on unauthenticated access to restricted pages will receive 403 responses.

### 5. Session Timeout Changed

HTTP session timeout increased from 10 minutes to 60 minutes.

---

## Migration Steps

### Step 1: Back Up the Database

```bash
pg_dump -U postgres wikantik > wikantik_backup_$(date +%Y%m%d).sql
```

### Step 2: Run the Database Migration

The migration script creates the `policy_grants` table and seeds it with production-appropriate defaults (anonymous = view-only).

```bash
sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/migration-1.0-to-1.1.sql
```

This script:
- Creates the `policy_grants` table (idempotent — `IF NOT EXISTS`)
- Grants table permissions to the application database user
- Seeds default policy grants with **anonymous restricted to view-only**
- Ensures the Admin group exists with the `admin` user

### Step 3: Update Properties

Add to `wikantik-custom.properties` (typically in `$TOMCAT_HOME/lib/`):

```properties
# Enable database-backed authorization policy (replaces wikantik.policy file)
wikantik.policy.datasource = jdbc/GroupDatabase
```

Optional — enable bootstrap admin override for initial deployment safety:
```properties
# Remove after confirming admin access works
wikantik.admin.bootstrap = admin
```

### Step 4: Deploy the WAR

```bash
# Build from source
mvn clean install -Dmaven.test.skip -T 1C

# Stop Tomcat
$TOMCAT_HOME/bin/shutdown.sh

# Deploy
rm -rf $TOMCAT_HOME/webapps/ROOT
cp wikantik-war/target/Wikantik.war $TOMCAT_HOME/webapps/ROOT.war

# Start Tomcat
$TOMCAT_HOME/bin/startup.sh
```

### Step 5: Verify

1. Access `http://your-wiki/app/wiki/Main` — page should load
2. Log in as admin — admin panel should appear (Users, Content, Security tabs)
3. Navigate to Admin → Security → Policy Grants — verify grants are loaded from database
4. Open an incognito/private window — verify anonymous users can VIEW pages but NOT edit (no Edit button)
5. Remove the `wikantik.admin.bootstrap` property if you set it in Step 3

---

## What's New in 1.1

### Security

- **REST API ACL enforcement** — all `/api/*` endpoints check page permissions and ACLs
- **Deserialization filtering** — `ObjectInputFilter` whitelist on all `ObjectInputStream` usage, blocking arbitrary class instantiation (RCE mitigation)
- **CSP hardened** — removed `unsafe-inline` from `script-src` directive
- **Admin CORS restricted** — `/admin/*` endpoints no longer send `Access-Control-Allow-Origin: *`
- **Error message sanitization** — admin endpoint 500 errors no longer expose internal details
- **NIST 800-63B password validation** — password strength enforcement with common-password blocklist

### Database-Backed Permissions

- **Policy grants in PostgreSQL** — default role permissions stored in `policy_grants` table, manageable via admin UI
- **Groups in PostgreSQL** — wiki groups manageable via admin UI at `/app/admin/security`
- **Admin group protection** — cannot delete the Admin group or save it with zero members
- **Bootstrap admin override** — `wikantik.admin.bootstrap` property guarantees admin access during setup

### Admin Panel (React SPA)

- **Security tab** — manage groups (create/edit/delete members) and policy grants (role-to-permission mappings with context-sensitive checkboxes)
- **User management** — create/edit/delete users, lock/unlock accounts, NIST password validation
- **Content management** — orphaned pages, broken links, version purging, cache management, search reindex

### React SPA — Full Feature Parity

- **Page delete** — delete button with confirmation modal, permission-gated
- **Page rename** — rename modal with reference update, permission-gated
- **Diff/version viewer** — side-by-side version comparison at `/app/diff/:name`
- **Comments** — collapsible comment panel on every page with add-comment form
- **User preferences** — self-service profile and password change at `/app/preferences`
- **Lost password recovery** — email-based password reset with rate limiting
- **Conflict resolution** — 3-option modal when two users edit simultaneously (overwrite, discard, copy to clipboard)
- **Clickable tags** — tags in Properties panel and page footer link to search results
- **Client-side navigation** — internal wiki links use React Router (no full-page reloads)
- **Login autocomplete** — browser credential suggestions on the login form
- **Permission-aware UI** — Edit, Rename, Delete buttons only appear when the user has the corresponding permission

### Scalability

- **Render cache** — HTML cache TTL increased from 60 seconds to 1 hour with event-driven invalidation on page save
- **CachingProvider** — replaced `synchronized(this)` with `ReentrantReadWriteLock` for concurrent reads
- **Search optimization** — Lucene search skips full permission check for pages without inline ACLs
- **Session management** — `SessionMonitor` replaced `WeakHashMap` + synchronized with lock-free `ConcurrentHashMap`
- **Cache capacity** — EhCache heap increased from 1,000 to 10,000 entries for render and page caches

### Code Quality

- **SpotBugs** — 380 bugs resolved to zero across all 9 modules (concurrency, encoding, null-safety, mutable collections)
- **Cyclomatic complexity** — 3 MCP tool classes refactored (CC 29→12, 21→7, 29→22)
- **Test coverage** — 7 of 9 modules at 80%+ line coverage; hundreds of new tests including plugin, security, concurrency, and event coverage
- **Code deduplication** — `PageDirectoryWatcher` cache invalidation extracted

### New REST Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/pages/{name}/rename` | Rename a page |
| GET | `/api/comments/{name}` | List comments on a page |
| POST | `/api/comments/{name}` | Add a comment |
| GET | `/api/auth/profile` | Get own user profile |
| PUT | `/api/auth/profile` | Update own profile/password |
| POST | `/api/auth/reset-password` | Email-based password recovery |
| GET/PUT/DELETE | `/admin/groups/*` | Group management |
| GET/POST/PUT/DELETE | `/admin/policy/*` | Policy grant management |

### Documentation

- README.md fully rewritten to reflect current architecture
- CLAUDE.md updated with security model, module structure, and permissions
- 6 linked docs updated (PostgreSQL setup, Docker, observability, React UI, relational DB, semantic wiki)
- JSP dead code catalog: 228 files (~2.2 MB) identified for removal in a future release

---

## Rollback

If you need to roll back to 1.0:

1. Restore the database backup: `psql -d wikantik < wikantik_backup_YYYYMMDD.sql`
2. Remove `wikantik.policy.datasource` from `wikantik-custom.properties`
3. Deploy the 1.0 WAR file
4. The `policy_grants` table will be ignored (the wiki falls back to `wikantik.policy` file)

---

## Known Limitations

- **JSP UI still present** — the legacy JSP pages are still in the WAR but all functionality is available via the React SPA at `/app/`. JSP removal is planned for 1.2.
- **Context path** — the React SPA is served at `/app/`. Moving to `/` is planned for after JSP removal.
- **wikantik-main test coverage** — at 57% due to legacy JSP tag/plugin/UI code that will be removed with JSP cleanup.

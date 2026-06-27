# PostgreSQL User and Group Database

Wikantik stores all user profiles, group memberships, roles, and authorization
policy grants in PostgreSQL. This document is the operator reference for that
schema.

> **MySQL is not supported.** No MySQL JDBC driver is declared in any module POM
> or config template. The connection pool, context template, and migration scripts
> are PostgreSQL-only.

---

## IMPORTANT — Tables are created automatically

> **Do NOT hand-create any of these tables.**
>
> All schema objects (users, roles, groups, group\_members, policy\_grants) are
> created and kept current by the numbered migration scripts in
> `bin/db/migrations/` (V001 through the current highest-numbered migration).
> `bin/db/migrate.sh` runs automatically on every `bin/deploy-local.sh` call,
> and on every production deploy. Running it again on an already-migrated
> database is a no-op — every migration is idempotent.
>
> The old "Step 4: create the tables manually" instruction in earlier versions of
> this document was wrong and must not be followed.

---

## 1. Overview

`JDBCUserDatabase` and `JDBCGroupDatabase` persist wiki users and groups to
PostgreSQL via a JNDI `DataSource`. The same `DataSource` is shared by:

- User profiles and password hashes (`users`, `roles`)
- Wiki groups (`groups`, `group_members`)
- Authorization policy grants (`policy_grants`)
- The Knowledge Graph, Page Graph, page provider, and all other subsystems

Everything goes through the single `jdbc/WikiDatabase` JNDI resource.

---

## 2. Configuration

### 2.1 `wikantik-custom.properties`

These are the **default** values set in `ini/wikantik.properties` and do not
need to be overridden in `wikantik-custom.properties` unless you are changing
the implementation:

```properties
# JDBC-backed user database (default — no override needed)
wikantik.userdatabase = com.wikantik.auth.user.JDBCUserDatabase

# JDBC-backed group database (default — no override needed)
wikantik.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase

# JNDI name of the shared DataSource (default — no override needed)
wikantik.datasource = jdbc/WikiDatabase
```

The `wikantik.datasource` property is consumed by `AbstractJDBCDatabase`
(constant `PROP_DATASOURCE`; default `jdbc/WikiDatabase`). Both
`JDBCUserDatabase` and `JDBCGroupDatabase` read it at initialization time.

### 2.2 JNDI DataSource (Tomcat `ROOT.xml`)

The DataSource is declared in
`tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`. The template is at
`wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template` and is
expanded by `bin/deploy-local.sh` from the values in `.env`.

The canonical shape of the resource element (from the template):

```xml
<Resource name="jdbc/WikiDatabase"
          auth="Container"
          type="javax.sql.DataSource"
          factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
          driverClassName="org.postgresql.Driver"
          url="jdbc:postgresql://HOST:PORT/DBNAME"
          username="DBUSER"
          password="DBPASSWORD"
          maxTotal="90"
          maxIdle="30"
          maxWaitMillis="5000"
          validationQuery="SELECT 1"
          testOnBorrow="true"/>
```

For first-time setup instructions, including database creation and the full
JNDI wiring, see **[docs/DevelopingWithPostgresql.md](DevelopingWithPostgresql.md)**
and the **First-time setup** section in `CLAUDE.md`.

---

## 3. Schema

### 3.1 `users` table

Source: `bin/db/migrations/V002__core_users_groups.sql`

```sql
CREATE TABLE IF NOT EXISTS users (
    uid         VARCHAR(100),
    email       VARCHAR(100),
    full_name   VARCHAR(100),
    login_name  VARCHAR(100) NOT NULL PRIMARY KEY,
    password    VARCHAR(100),
    wiki_name   VARCHAR(100),
    created     TIMESTAMP,
    modified    TIMESTAMP,
    lock_expiry TIMESTAMP,
    bio         VARCHAR(1000),
    attributes  TEXT
);
```

Key points:

- **Primary key is `login_name`**, not `uid`. `uid` is a legacy opaque string
  identifier but is not the PK.
- `password` stores the password hash. New and re-hashed passwords use bcrypt (`{bcrypt}$2a$…`). Legacy SHA-256 (`{SHA-256}`) and SHA-1 (`{SSHA}`) hashes are verifiable and transparently migrated to bcrypt on first login (since 2.1.4).
- `lock_expiry` is `NULL` when the account is unlocked; a future timestamp
  means the account is locked until that time. `lock_expiry` and `bio` were
  added after the original baseline by the `ADD COLUMN IF NOT EXISTS` guards in
  V002, so the migration is safe to run against older databases.
- `attributes` is a base64-serialized map of custom profile attributes (see
  `com.wikantik.util.Serializer`).
- `wiki_name` is the display-style wiki name (e.g. `Administrator`) and must
  be distinct from `login_name`.

`JDBCUserDatabase` queries and writes exactly these columns. The SQL constants
inside the class confirm the column list:

```
INSERT: uid, email, full_name, password, wiki_name, modified, login_name, attributes, bio, created
UPDATE: uid, email, full_name, password, wiki_name, modified, login_name, attributes, bio, lock_expiry
```

### 3.2 `roles` table

Source: `bin/db/migrations/V002__core_users_groups.sql`

```sql
CREATE TABLE IF NOT EXISTS roles (
    login_name VARCHAR(100) NOT NULL,
    role       VARCHAR(100) NOT NULL
);
```

Each row maps one login to one role (e.g. `admin` → `Admin`,
`admin` → `Authenticated`). When a new user is created, `JDBCUserDatabase`
inserts an `Authenticated` role row if no rows exist yet for that login.

There is no foreign key constraint from `roles` to `users` in the migration —
this is intentional; the application enforces referential integrity (delete user
deletes roles in the same transaction).

### 3.3 `groups` table

Source: `bin/db/migrations/V002__core_users_groups.sql`

```sql
CREATE TABLE IF NOT EXISTS groups (
    name     VARCHAR(100) NOT NULL PRIMARY KEY,
    creator  VARCHAR(100),
    created  TIMESTAMP,
    modifier VARCHAR(100),
    modified TIMESTAMP
);
```

Key points:

- **Primary key is `name` (a `VARCHAR`).** There is no numeric `id` column.
  The old MySQL/PostgreSQL DDL in earlier versions of this document that used
  `id SERIAL PRIMARY KEY` was wrong.
- `creator` and `modifier` store the `login_name` of whoever created or last
  modified the group.
- The built-in `Admin` group is seeded by V002 and cannot be deleted
  (`JDBCGroupDatabase.delete()` throws if `group.getName().equals("Admin")`).

### 3.4 `group_members` table

Source: `bin/db/migrations/V002__core_users_groups.sql`

```sql
CREATE TABLE IF NOT EXISTS group_members (
    name   VARCHAR(100) NOT NULL,
    member VARCHAR(100) NOT NULL,
    CONSTRAINT group_members_pk PRIMARY KEY (name, member)
);
```

Key points:

- Composite primary key `(name, member)`. There are no integer surrogate keys
  and no foreign key constraints in the DDL. The old schema in earlier versions
  of this document (which used `group_id INT` referencing `groups(id)` and
  `user_id VARCHAR` referencing `users(uid)`) was entirely wrong.
- `name` is the group name (matches `groups.name`).
- `member` is a login name or wiki name string. The application stores
  `WikiPrincipal.getName()` here.
- `JDBCGroupDatabase.save()` deletes all member rows for the group and
  re-inserts the current set in a single transaction — there is no
  individual-row add/remove path.

### 3.5 `policy_grants` table

Source: `bin/db/migrations/V003__policy_grants.sql`

```sql
CREATE TABLE IF NOT EXISTS policy_grants (
    id              SERIAL       PRIMARY KEY,
    principal_type  VARCHAR(10)  NOT NULL,
    principal_name  VARCHAR(255) NOT NULL,
    permission_type VARCHAR(10)  NOT NULL,
    target          VARCHAR(255) NOT NULL,
    actions         VARCHAR(255) NOT NULL,
    UNIQUE (principal_type, principal_name, permission_type, target)
);
```

Key differences from the old doc:

- `target` is `NOT NULL` (old doc had `target VARCHAR(255)` nullable).
- `actions` is `NOT NULL` (old doc had it nullable).
- There is a `UNIQUE` constraint on `(principal_type, principal_name,
  permission_type, target)` — this is what makes the seed `ON CONFLICT DO
  NOTHING` safe.

Default seed rows (from V003):

| principal_type | principal_name | permission_type | target | actions |
|---|---|---|---|---|
| role | All | page | * | view |
| role | All | wiki | * | editPreferences,editProfile,login |
| role | Asserted | group | * | view |
| role | Authenticated | page | * | modify,rename |
| role | Authenticated | wiki | * | createPages,createGroups |
| role | Authenticated | group | * | view |
| role | Authenticated | group | `<groupmember>` | edit |
| role | Admin | page | * | * |
| role | Admin | wiki | * | * |

Policy grants are active whenever `wikantik.datasource` is configured — which
it always is in a standard deployment. The file-based `WEB-INF/wikantik.policy`
is a fallback for environments where no DataSource is configured.

---

## 4. Managing Users, Groups, and Policy

### Admin UI

Groups and policy grants are managed via the admin panel:

- **`/admin/security`** — view and edit role-based policy grants and group
  memberships
- **`/admin/users`** — manage user accounts, lock/unlock accounts

### SCIM provisioning

The SCIM 2.0 server at `/scim/v2/*` supports IdP-driven user and group
lifecycle management. User decommission routes through
`UserLifecycleService`; group membership sync routes through `GroupManager`.
SCIM groups never grant the Admin role. See `CLAUDE.md` for the agent-facing
surface summary.

### Direct SQL (break-glass)

To create a user manually (e.g. to recreate the `testbot` account after a
database reset), hash the password first using `CryptoUtil` and then insert
directly. See the **Manual Testing Credentials** section in `CLAUDE.md` for the
full example.

---

## 5. Security Model Cross-Reference

See `CLAUDE.md` → **Security Model** for the full description of:

- JAAS-based authentication and authorization
- Fine-grained page and wiki permissions
- Page-level ACLs via inline `[{ALLOW view Admin}]` syntax
- Bootstrap admin override (`wikantik.admin.bootstrap`)
- SSO identity binding and session fixation defense
- NIST 800-63B password validation

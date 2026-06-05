# Developing with PostgreSQL as the Wikantik User Database

This document provides a guide for configuring PostgreSQL as the backing database for
Wikantik's user, group, and wiki-data storage. It covers development setup, testing,
validation, and production deployment.

---

## Supported (automated) path — start here

**All local setup is handled by two scripts.** The manual sections below (§3 schema
creation, §4 JDBC driver, §5 context/properties, §6 deploy) are preserved as
"manual / advanced override" reference but are not the normal path.

### Step 1 — Install PostgreSQL + pgvector

| Component | Minimum | Notes |
|-----------|---------|-------|
| PostgreSQL | 15 | pgvector requires 15+ |
| pgvector | 0.5.x | `postgresql-<MAJOR>-pgvector` (apt) or `pgvector_<MAJOR>` (yum) |
| Java JDK | 21 | Build target |
| Apache Tomcat | 11.0.22 | Pinned by `bin/deploy-local.sh` and the `Dockerfile` |
| PostgreSQL JDBC Driver | 42.7.x | Auto-downloaded into `tomcat/tomcat-11/lib/` by `bin/deploy-local.sh` |

```bash
# Ubuntu/Debian example
sudo apt update
sudo apt install postgresql postgresql-contrib postgresql-15-pgvector
sudo systemctl enable --now postgresql
```

### Step 2 — Bootstrap the database (once per environment)

`bin/db/install-fresh.sh` creates the `wikantik` database, the `jspwiki` application
role, installs the `pgvector` extension, and runs every migration in
`bin/db/migrations/` (V001 through V037 as of this writing). Idempotent — safe to
re-run against an already-bootstrapped database.

```bash
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' \
    bin/db/install-fresh.sh
```

**pg_hba.conf**: Ensure the `jspwiki` role can connect. Typical entry:

```
# TYPE  DATABASE   USER      ADDRESS         METHOD
host    wikantik   jspwiki   127.0.0.1/32    scram-sha-256
host    wikantik   jspwiki   ::1/128         scram-sha-256
```

Reload after editing:

```bash
sudo systemctl reload postgresql
```

### Step 3 — Copy `.env.example` and set your password

`bin/deploy-local.sh` reads `.env` (gitignored) for PostgreSQL credentials and uses
them to render `conf/Catalina/localhost/ROOT.xml` from the template. The script
will copy `.env.example` for you if `.env` is absent, but you must set
`POSTGRES_PASSWORD` before re-running.

```bash
cp .env.example .env
# edit .env — set POSTGRES_PASSWORD (and other values if needed)
```

### Step 4 — Build and deploy

```bash
# Build (this also builds the React frontend via npm automatically)
mvn clean install -DskipTests -T 1C

# First-time deploy: downloads Tomcat 11.0.22, renders config templates from .env,
# deploys ROOT.war, runs migrate.sh, seeds dev users, starts Tomcat.
bin/deploy-local.sh
```

Access at **http://localhost:8080/** — default login: `admin` / `admin123`.

### Routine redeploy (after first-time setup)

```bash
mvn clean install -DskipTests -T 1C
bin/redeploy.sh   # shutdown + rotate catalina.out + swap WAR + run migrations + startup
```

`bin/redeploy.sh` is the fast iteration path. It skips template rendering, Tomcat
upgrade logic, and dev-user seeding — run `bin/deploy-local.sh` again for those.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [Manual Schema Reference (legacy / advanced)](#3-manual-schema-reference-legacy--advanced)
4. [Manual JDBC Driver Installation (legacy)](#4-manual-jdbc-driver-installation-legacy)
5. [Tomcat JNDI Configuration (manual path)](#5-tomcat-jndi-configuration-manual-path)
6. [Wikantik Configuration (manual path)](#6-wikantik-configuration-manual-path)
7. [Testing the Configuration](#7-testing-the-configuration)
8. [Validation Procedures](#8-validation-procedures)
9. [Production Deployment](#9-production-deployment)
10. [Troubleshooting](#10-troubleshooting)
11. [Appendix: Password Hashing](#appendix-password-hashing)

---

## 1. Overview

### What This Document Covers

Wikantik stores user profiles, group memberships, policy grants, Knowledge Graph data,
and wiki metadata in PostgreSQL. The database and schema are fully managed by
`bin/db/install-fresh.sh` + `bin/db/migrate.sh`; direct DDL is never applied by hand.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Wikantik (ROOT context)                  │
├─────────────────────────────────────────────────────────────────┤
│   JDBCUserDatabase              JDBCGroupDatabase               │
│         │                              │                        │
│         └──────────┬───────────────────┘                        │
│                    │                                            │
│              JNDI Lookup                                        │
│        (java:comp/env/jdbc/WikiDatabase)                        │
└────────────────────┼────────────────────────────────────────────┘
                     │
┌────────────────────┼────────────────────────────────────────────┐
│                    ▼                                            │
│         Tomcat DataSource Pool                                  │
│           (jdbc/WikiDatabase)                                   │
└────────────────────┼────────────────────────────────────────────┘
                     │
┌────────────────────┼────────────────────────────────────────────┐
│                    ▼                                            │
│              PostgreSQL Server                                  │
│   database: wikantik   app role: jspwiki                       │
│   ┌────────┬───────┬──────────┬──────────────┬──────────┐      │
│   │ users  │ roles │  groups  │ group_members │ kg_nodes │ …   │
│   └────────┴───────┴──────────┴──────────────┴──────────┘      │
└─────────────────────────────────────────────────────────────────┘
```

### Key Implementation Details

- **JNDI Lookup**: Wikantik uses JNDI to look up DataSources, not direct JDBC connections
- **Password Hashing**: Passwords are stored using salted SHA-256 (`{SHA-256}`) or SHA-1 (`{SSHA}`, legacy)
- **Transaction Support**: Automatic detection and use of database transactions
- **Prepared Statements**: All SQL uses prepared statements (immune to SQL injection)
- **Context file**: `conf/Catalina/localhost/ROOT.xml` with `path="/"` (root context)
- **Custom properties**: `tomcat/tomcat-11/lib/wikantik-custom.properties` (bare-metal) or `${CATALINA_HOME}/lib/` (container)

---

## 2. Prerequisites

See the table in the Supported path section above. `bin/deploy-local.sh` handles
Tomcat download and JDBC driver download automatically.

---

## 3. Manual Schema Reference (legacy / advanced)

> **SUPERSEDED for normal use.** The supported path is `bin/db/install-fresh.sh`
> which runs all migrations (V001 through V037). Use the manual SQL below only for
> external review, adapting to a different RDBMS, or understanding what the schema
> contains. **Never apply this DDL directly to a Wikantik database** — use
> `bin/db/install-fresh.sh` so the `schema_migrations` ledger stays in sync.

The legacy `wikantik-war/src/main/config/db/postgresql*.ddl` files are historical
reference only. [`docs/DatabaseUpdates.md`](DatabaseUpdates.md) lists every migration
and what it adds.

### Core tables (simplified — actual schema comes from migrations)

```sql
-- ============================================================================
-- Users Table
-- ============================================================================
CREATE TABLE users (
    uid VARCHAR(100),
    email VARCHAR(100),
    full_name VARCHAR(100),
    login_name VARCHAR(100) NOT NULL,
    password VARCHAR(100),
    wiki_name VARCHAR(100),
    created TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    modified TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    lock_expiry TIMESTAMP WITH TIME ZONE,
    attributes TEXT,
    PRIMARY KEY (login_name)
);

CREATE INDEX idx_users_email    ON users(email);
CREATE INDEX idx_users_wiki_name ON users(wiki_name);
CREATE INDEX idx_users_uid       ON users(uid);

-- ============================================================================
-- Roles Table
-- ============================================================================
CREATE TABLE roles (
    login_name VARCHAR(100) NOT NULL,
    role VARCHAR(100) NOT NULL,
    PRIMARY KEY (login_name, role)
);

CREATE INDEX idx_roles_login_name ON roles(login_name);

-- ============================================================================
-- Groups Table
-- ============================================================================
CREATE TABLE groups (
    name VARCHAR(100) NOT NULL,
    creator VARCHAR(100),
    created TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    modifier VARCHAR(100),
    modified TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (name)
);

-- ============================================================================
-- Group Members Table
-- ============================================================================
CREATE TABLE group_members (
    name VARCHAR(100) NOT NULL,
    member VARCHAR(100) NOT NULL,
    PRIMARY KEY (name, member)
);

CREATE INDEX idx_group_members_member ON group_members(member);
```

### Table permissions (applied automatically by migrations)

```sql
-- Applied by migrations — reference only
GRANT SELECT, INSERT, UPDATE, DELETE ON users         TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON roles         TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON groups        TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON group_members TO jspwiki;
```

### Create an admin user manually

```sql
-- Generate the hash with CryptoUtil first (see Appendix), then:
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, created, modified)
VALUES ('-6852820166199419346', 'admin@localhost', 'Administrator', 'admin',
        '{SHA-256}your_generated_hash_here',
        'Administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO roles (login_name, role) VALUES ('admin', 'Admin');
INSERT INTO roles (login_name, role) VALUES ('admin', 'Authenticated');

INSERT INTO groups (name, created, modified)
VALUES ('Admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO group_members (name, member) VALUES ('Admin', 'Administrator');
```

> In practice, `bin/deploy-local.sh` runs `bin/db/seed-users.sql` automatically to
> create the `admin` and `jakefear@gmail.com` accounts.

### Verify schema

```sql
\dt
\d users
SELECT login_name, full_name, email FROM users;
SELECT * FROM roles;
```

---

## 4. Manual JDBC Driver Installation (legacy)

> **SUPERSEDED.** `bin/deploy-local.sh` automatically downloads
> `postgresql-42.7.4.jar` into `tomcat/tomcat-11/lib/postgresql.jar` on first run.
> The steps below apply only to a hand-rolled Tomcat install outside `deploy-local.sh`.

```bash
# Download PostgreSQL JDBC Driver 42.7.x (check for latest version)
curl -L -o postgresql-42.7.4.jar \
    https://jdbc.postgresql.org/download/postgresql-42.7.4.jar

# For the project's bundled Tomcat
cp postgresql-42.7.4.jar tomcat/tomcat-11/lib/postgresql.jar

# For a system Tomcat installation
cp postgresql-42.7.4.jar "${CATALINA_HOME}/lib/"
```

Restart Tomcat after adding the driver.

---

## 5. Tomcat JNDI Configuration (manual path)

> **SUPERSEDED for the local Tomcat.** `bin/deploy-local.sh` renders
> `conf/Catalina/localhost/ROOT.xml` from
> `wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template` by
> substituting values from `.env`. The file ends up at
> `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` with `path="/"` (root context).
>
> The manual steps below apply to a system Tomcat or container where you are
> supplying the context file yourself.

### Application-specific context (root context)

Create `${CATALINA_HOME}/conf/Catalina/localhost/ROOT.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Context path="/">
    <Resource
        name="jdbc/WikiDatabase"
        auth="Container"
        type="javax.sql.DataSource"
        driverClassName="org.postgresql.Driver"
        url="jdbc:postgresql://localhost:5432/wikantik"
        username="jspwiki"
        password="your_secure_password_here"

        maxTotal="30"
        maxIdle="10"
        minIdle="5"
        maxWaitMillis="10000"

        validationQuery="SELECT 1"
        validationQueryTimeout="5"
        testOnBorrow="true"
        testOnReturn="false"
        testWhileIdle="true"
        timeBetweenEvictionRunsMillis="30000"
        minEvictableIdleTimeMillis="60000"

        removeAbandonedOnBorrow="true"
        removeAbandonedOnMaintenance="true"
        removeAbandonedTimeout="300"
        logAbandoned="true"
    />
    <!-- Additional DataSources for Knowledge Graph etc. are in the template -->
</Context>
```

See `wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template` for the
full set of DataSource entries used by the application.

### Connection Pool Settings

| Setting | Description | Recommended Value |
|---------|-------------|-------------------|
| `maxTotal` | Maximum active connections | 50-100 |
| `maxIdle` | Maximum idle connections | 10-20 |
| `minIdle` | Minimum idle connections | 5-10 |
| `maxWaitMillis` | Max wait time for connection | 10000 (10 sec) |
| `validationQuery` | Query to validate connections | `SELECT 1` |
| `testOnBorrow` | Validate before using | `true` |

---

## 6. Wikantik Configuration (manual path)

> **SUPERSEDED.** `bin/deploy-local.sh` renders `wikantik-custom.properties` from
> `wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template`
> (with `@@REPO_ROOT@@` substituted) and places it at
> `tomcat/tomcat-11/lib/wikantik-custom.properties`.
> In container deployments, the equivalent lives at `${CATALINA_HOME}/lib/`.
> Do **not** place it in `WEB-INF/` — it must be in the Tomcat lib directory so it
> is on the container classloader path, not inside the WAR.

Key properties (excerpt from the template):

```properties
# Enable JDBC User Database (instead of default XML)
wikantik.userdatabase = com.wikantik.auth.user.JDBCUserDatabase

# Enable JDBC Group Database (instead of default XML)
wikantik.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase

# JNDI DataSource Name — must match the Resource name in ROOT.xml
wikantik.datasource = jdbc/WikiDatabase
```

---

## 7. Testing the Configuration

### Build and deploy

```bash
# Build (do NOT use -Dmaven.test.skip — use -DskipTests instead)
mvn clean install -DskipTests -T 1C

# Deploy (fast path after first-time setup)
bin/redeploy.sh

# Tail logs
tail -f tomcat/tomcat-11/logs/catalina.out
```

### Verify database connectivity

```bash
grep -E "(JDBCUserDatabase|JDBCGroupDatabase)" tomcat/tomcat-11/logs/catalina.out
```

Expected output:
```
JDBCUserDatabase initialized from JNDI DataSource: jdbc/WikiDatabase
JDBCUserDatabase supports transactions. Good; we will use them.
JDBCGroupDatabase initialized from JNDI DataSource: jdbc/WikiDatabase
JDBCGroupDatabase supports transactions. Good; we will use them.
```

### Test login

1. Open **http://localhost:8080/**
2. Click "Login"
3. Enter credentials: `admin` / `admin123` (seeded by `bin/db/seed-users.sql`)
4. Verify successful login

### Test group management

```sql
SELECT g.name, g.creator, gm.member
FROM groups g
LEFT JOIN group_members gm ON g.name = gm.name
ORDER BY g.name;
```

---

## 8. Validation Procedures

### Database connection validation

```bash
psql -h localhost -U jspwiki -d wikantik -c "SELECT 1 AS connection_test;"
psql -h localhost -U jspwiki -d wikantik -c "\dt"
psql -h localhost -U jspwiki -d wikantik -c "SELECT COUNT(*) AS user_count FROM users;"
```

### Application health check (SQL)

```sql
-- Check table existence
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('users', 'roles', 'groups', 'group_members', 'schema_migrations');

-- Orphaned roles (roles without users)
SELECT r.login_name, r.role
FROM roles r
LEFT JOIN users u ON r.login_name = u.login_name
WHERE u.login_name IS NULL;

-- Recent user activity
SELECT login_name, modified
FROM users
WHERE modified > CURRENT_TIMESTAMP - INTERVAL '7 days'
ORDER BY modified DESC;
```

### Unit tests

```bash
mvn test -Dtest=JDBCUserDatabaseTest  -pl wikantik-main
mvn test -Dtest=JDBCGroupDatabaseTest -pl wikantik-main
```

---

## 9. Production Deployment

### Security hardening

```sql
-- Strong password for the app role
ALTER USER jspwiki WITH PASSWORD 'use_a_very_strong_password_min_32_chars';

-- Limit connection privileges
REVOKE ALL ON DATABASE wikantik FROM PUBLIC;
GRANT CONNECT ON DATABASE wikantik TO jspwiki;

-- Limit schema privileges
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO jspwiki;
```

```
# pg_hba.conf — production: only allow connections from application server
hostssl wikantik   jspwiki   10.0.0.5/32    scram-sha-256
```

SSL JDBC URL:

```xml
url="jdbc:postgresql://db-server:5432/wikantik?ssl=true&amp;sslmode=verify-full&amp;sslrootcert=/path/to/ca.crt"
```

### Backup and recovery

```bash
# Daily backup
pg_dump -h localhost -U jspwiki -d wikantik | gzip > \
    "/var/backups/postgresql/wikantik_$(date +%Y%m%d_%H%M%S).sql.gz"

# Keep only last 30 days
find /var/backups/postgresql -name "wikantik_*.sql.gz" -mtime +30 -delete
```

```bash
# Restore
gunzip -c /var/backups/postgresql/wikantik_20250101_120000.sql.gz \
    | psql -h localhost -U jspwiki -d wikantik
```

### Monitoring queries

```sql
-- Active connections
SELECT count(*) FROM pg_stat_activity WHERE datname = 'wikantik';

-- Connection state breakdown
SELECT state, count(*)
FROM pg_stat_activity
WHERE datname = 'wikantik'
GROUP BY state;

-- Table sizes
SELECT
    relname AS table_name,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
    pg_size_pretty(pg_relation_size(relid)) AS data_size
FROM pg_catalog.pg_statio_user_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(relid) DESC;

-- Long-running queries
SELECT pid, now() - query_start AS duration, query, state
FROM pg_stat_activity
WHERE datname = 'wikantik'
  AND state != 'idle'
  AND now() - query_start > interval '5 seconds';

-- Lock check
SELECT pg_stat_activity.pid, pg_class.relname, pg_locks.mode, pg_locks.granted
FROM pg_locks
JOIN pg_class          ON pg_locks.relation        = pg_class.oid
JOIN pg_stat_activity  ON pg_locks.pid              = pg_stat_activity.pid
WHERE pg_stat_activity.datname = 'wikantik';
```

---

## 10. Troubleshooting

### Common errors

**DataSource not found**
```
javax.naming.NameNotFoundException: Name [jdbc/WikiDatabase] is not bound
```
- Verify the Resource is defined in `ROOT.xml` (`tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`)
- Check the resource name matches exactly
- Restart Tomcat completely

**Driver not found**
```
java.sql.SQLException: No suitable driver found for jdbc:postgresql://
```
- Ensure `postgresql.jar` is in `tomcat/tomcat-11/lib/` (or `${CATALINA_HOME}/lib/`)
- Restart Tomcat after adding the driver

**Authentication failed**
```
FATAL: password authentication failed for user "jspwiki"
```
- Verify username/password in `ROOT.xml`
- Check `pg_hba.conf` allows the connection: `\du jspwiki`

**Connection refused**
```
java.net.ConnectException: Connection refused
```
- Verify PostgreSQL is running: `systemctl status postgresql`
- Check it is listening: `ss -tlnp | grep 5432`
- Check `listen_addresses` in `postgresql.conf`

### Debug logging

```xml
<!-- Add to tomcat/tomcat-11/lib/log4j2.xml -->
<Logger name="com.wikantik.auth.user.JDBCUserDatabase"      level="DEBUG"/>
<Logger name="com.wikantik.auth.authorize.JDBCGroupDatabase" level="DEBUG"/>
<Logger name="org.apache.tomcat.jdbc.pool"                   level="DEBUG"/>
```

---

## Appendix: Password Hashing

### Password format

Wikantik stores passwords using RFC 2307-compliant salted hashing:
- **{SHA-256}**: Salted SHA-256 (recommended)
- **{SSHA}**: Salted SHA-1 (legacy, still accepted)

### Generating password hashes

```bash
# Build first, then:
java -cp wikantik-util/target/wikantik-util-*.jar \
    com.wikantik.util.CryptoUtil --hash "mypassword"
```

### Updating a user's password via SQL

```sql
-- Generate a new hash using CryptoUtil first, then:
UPDATE users
SET password = '{SHA-256}your_generated_hash_here',
    modified = CURRENT_TIMESTAMP
WHERE login_name = 'username';
```

---

## References

- [Wikantik Source: JDBCUserDatabase.java](../wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java)
- [Wikantik Source: JDBCGroupDatabase.java](../wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java)
- [PostgreSQL JDBC Driver Documentation](https://jdbc.postgresql.org/documentation/)
- [Apache Tomcat JNDI Resources](https://tomcat.apache.org/tomcat-11.0-doc/jndi-resources-howto.html)
- [Migration history: docs/DatabaseUpdates.md](DatabaseUpdates.md)

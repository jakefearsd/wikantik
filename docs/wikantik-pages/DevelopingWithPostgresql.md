---
cluster: databases
type: article
tags:
- postgresql
- database
- setup
- development
title: Developing with PostgreSQL
canonical_id: 01KQ0P44PMEYZGGZCXDSXGA3XR
summary: Using PostgreSQL as the Wikantik backend — schema, JNDI DataSource config,
  JDBC driver setup, connection pooling, production hardening, and troubleshooting.
status: active
date: '2026-04-30'
---
# Developing with PostgreSQL

This document is a step-by-step guide to setting up and administering PostgreSQL as Wikantik's database. PostgreSQL is the only database Wikantik supports. It covers development setup, testing, validation, and production deployment. For the shortest path on a developer machine, use `bin/db/install-fresh.sh` and `bin/deploy-local.sh`; the sections below explain what those scripts do. See also [PostgreSQL Local Deployment](PostgreSQLLocalDeployment) for an end-to-end fresh install and [Wikantik on Docker](WikantikOnDocker) for the container stack, which bundles its own PostgreSQL.

## Table of Contents

1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [PostgreSQL Server Setup](#3-postgresql-server-setup)
4. [Database Schema Creation](#4-database-schema-creation)
5. [JDBC Driver Installation](#5-jdbc-driver-installation)
6. [Tomcat JNDI Configuration](#6-tomcat-jndi-configuration)
7. [Wikantik Configuration](#7-wikantik-configuration)
8. [Testing the Configuration](#8-testing-the-configuration)
9. [Validation Procedures](#9-validation-procedures)
10. [Production Deployment](#10-production-deployment)
11. [Troubleshooting](#11-troubleshooting)
12. [Appendix: Password Hashing](#appendix-password-hashing)

---

## 1. Overview

### What This Document Covers

Wikantik is PostgreSQL-only. The XML user and group files that older JSPWiki-derived versions defaulted to have been removed; there is no file-based alternative, and no other database driver ships in any module. Everything that is not page text lives in one PostgreSQL database, reached through one JNDI DataSource, `jdbc/WikiDatabase`: user profiles and password hashes, roles and wiki groups, database-backed policy grants (`policy_grants`), API keys, the Knowledge Graph and its pgvector embeddings, the Page Graph structural index, the audit log, and the `schema_migrations` ledger. Page text and attachments stay on the filesystem (`VersioningFileProvider`, `wikantik.fileSystemProvider.pageDir`).

This guide uses the user and group tables (`users`, `roles`, `groups`, `group_members`) as its worked example, because they are what the login and group-management checks in sections 8 and 9 exercise. The same server, role, driver, and DataSource serve every other subsystem.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Wikantik                                 │
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
│    ┌─────────┬─────────┬────────────────┬──────────────┐       │
│    │  users  │  roles  │    groups      │ group_members│       │
│    └─────────┴─────────┴────────────────┴──────────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

The diagram shows only the user and group repositories. Every other repository (policy grants, API keys, Knowledge Graph, and so on) shares the same DataSource.

### Key Implementation Details

From the source code (`JDBCUserDatabase.java` and `JDBCGroupDatabase.java`):

- **JNDI Lookup**: Wikantik uses JNDI to look up DataSources, not direct JDBC connections
- **Password Hashing**: Passwords are stored as bcrypt (`{bcrypt}`); legacy salted SHA-256 (`{SHA-256}`) hashes remain verifiable and transparently migrate to bcrypt on next login. Salted SHA-1 (`{SSHA}`) is no longer supported.
- **Transaction Support**: Automatic detection and use of database transactions
- **Prepared Statements**: All SQL uses prepared statements (immune to SQL injection)
- **User Attributes**: Custom attributes are serialized as Base64-encoded Java objects

---

## 2. Prerequisites

### Software Requirements

| Component | Minimum Version | Notes |
|-----------|----------------|-------|
| PostgreSQL | 15 | Production runs 18; the container stack uses `pgvector/pgvector:pg18` |
| pgvector extension | 0.5 | Required: migration V004 runs `CREATE EXTENSION vector` and V032 builds an HNSW index (HNSW needs pgvector 0.5+). Install the OS package before running the migrations |
| Java JDK | 25 | `jdk.version` in the root `pom.xml`; the build enforcer fails on an older JDK, and `bin/deploy-local.sh` and `bin/redeploy.sh` refuse to start Tomcat on one |
| Apache Tomcat | 11.0.27 | Pinned as `TOMCAT_VERSION` in `bin/deploy-local.sh` and in the `Dockerfile`; `deploy-local.sh` downloads it for you |
| PostgreSQL JDBC Driver | 42.7.14 | Pinned in the root `pom.xml`; see section 5 |
| Maven / Node.js | 3.9+ / 20.19+ | Only to build the WAR from source (the WAR build runs `npm install` and `vite build`) |

### Required Knowledge

- Basic PostgreSQL administration (creating databases, users, grants)
- Tomcat configuration (context files, JNDI resources)
- Wikantik properties configuration

---

## 3. PostgreSQL Server Setup

### 3.1 Install PostgreSQL

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install postgresql postgresql-contrib
sudo systemctl enable postgresql
sudo systemctl start postgresql
```

**RHEL/CentOS/Fedora:**
```bash
sudo dnf install postgresql-server postgresql-contrib
sudo postgresql-setup --initdb
sudo systemctl enable postgresql
sudo systemctl start postgresql
```

**macOS (Homebrew):**
```bash
brew install postgresql@15
brew services start postgresql@15
```

**Install pgvector** (the migrations fail without it). On Debian or Ubuntu with PostgreSQL 16:

```bash
sudo apt install -y postgresql-16-pgvector
sudo systemctl restart postgresql

# Verify: you should see a `vector` row
sudo -u postgres psql -c "SELECT name, default_version FROM pg_available_extensions WHERE name='vector';"
```

### 3.2 Create the Wikantik Database

`bin/db/install-fresh.sh` (section 4.1) performs this step and 3.3 for you. Do them by hand only if you want to customize the result.

Connect to PostgreSQL as the superuser:

```bash
sudo -u postgres psql
```

Create the database:

```sql
-- Create the database
CREATE DATABASE wikantik
    WITH
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'
    TEMPLATE = template0;

-- Verify creation
\l wikantik
```

### 3.3 Create the Application User

Create a dedicated database user for Wikantik. The project uses `wikantik` for the role and for the database (`POSTGRES_USER` and `POSTGRES_DB` in `.env`):

```sql
-- Create the user with a strong password
CREATE USER wikantik WITH
    ENCRYPTED PASSWORD 'your_secure_password_here'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE;

-- Grant connection privileges
GRANT CONNECT ON DATABASE wikantik TO wikantik;

-- Connect to the wikantik database
\c wikantik

-- Grant schema usage
GRANT USAGE ON SCHEMA public TO wikantik;
```

**Important:** The application role never creates tables. The migrations run as a superuser (`--no-migrate-role`) or as the dedicated `migrate` role, and each migration grants DML on the tables it creates to the application role (the `:app_user` psql variable). The application role therefore needs only `CONNECT` and `USAGE`; do not grant it `CREATE` on the `public` schema, which PostgreSQL 15+ no longer grants to every user by default.

**Security Note:** In production, use a strong, randomly generated password. Consider using a password manager or secrets management system.

### 3.4 Configure PostgreSQL Authentication

Edit `pg_hba.conf` to allow the wikantik user to connect:

```bash
# Find the pg_hba.conf location
sudo -u postgres psql -c "SHOW hba_file;"
```

Add the following line (adjust for your network):

```
# TYPE  DATABASE    USER      ADDRESS         METHOD
host    wikantik    wikantik  127.0.0.1/32    scram-sha-256
host    wikantik    wikantik  ::1/128         scram-sha-256
```

Reload PostgreSQL configuration:

```bash
sudo systemctl reload postgresql
```

---

## 4. Database Schema Creation

### 4.1 Apply the migrations

Wikantik's schema is defined **only** by the numbered, idempotent migrations in
`bin/db/migrations/V*.sql` (the legacy `postgresql*.ddl` snapshot files were retired on
2026-08-22). You never hand-write `CREATE TABLE` — not for production, not for a local
database, and not in a test (the unit-test fixture `PostgresTestDb` applies these very same
files to a throwaway container).

For a fresh local database, one command creates the database, the application role and the
whole schema, and seeds the initial `admin` account (V002, flagged `password_must_change` by V039):

```bash
# Idempotent — safe to re-run. DB_APP_USER/DB_APP_PASSWORD must match POSTGRES_USER/
# POSTGRES_PASSWORD in .env, or the application cannot connect.
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role
```

`--no-migrate-role` runs the migrations as the superuser, which is the simplest local setup;
omit it and pass `DB_MIGRATE_PASSWORD='…'` for a least-privilege migrate role. Grants to the
application role are part of every migration (the `:app_user` psql variable), so there is no
separate "grant permissions" step.

To bring an existing database forward (pending migrations only), or to see what has been
applied:

```bash
DB_NAME=wikantik DB_APP_USER=wikantik PGUSER=postgres PGPASSWORD='…' bin/db/migrate.sh
bin/db/migrate.sh --status
```

`bin/deploy-local.sh` and `bin/redeploy.sh` both run `migrate.sh` for you on every deploy.

**Adding a schema change** means adding the next `V<NNN>__description.sql` — see
`bin/db/migrations/README.md` for the convention (idempotent DDL, `:app_user` for grants,
never edit a migration that has been applied in production).

### 4.2 Verify Schema

Verify the tables were created correctly:

```sql
-- List all tables
\dt

-- Describe the users table
\d users

-- Verify the admin user
SELECT login_name, full_name, email, wiki_name FROM users;

-- Verify roles
SELECT * FROM roles;

-- Verify groups
SELECT * FROM groups;
SELECT * FROM group_members;
```

---

## 5. JDBC Driver Installation

The WAR declares the PostgreSQL JDBC driver `provided`, so Wikantik runs whichever driver jar is in Tomcat's `lib/` directory. Use **42.7.14**: it is the `<postgresql-jdbc.version>` pinned in the root `pom.xml`, the version every unit and integration test runs against, and it fixes CVE-2026-107314 and CVE-2026-107315.

### 5.1 Local Development Tomcat

`bin/deploy-local.sh` installs the driver as `tomcat/tomcat-11/lib/postgresql.jar`. On every run it reads the installed jar's version and, when it differs from the pinned one, downloads the pinned version from Maven Central, so re-running the script is all an upgrade needs. If the download fails or is not the expected driver, the script exits with an error and leaves the existing jar in place.

The container images (`Dockerfile`, `Dockerfile.dev`) install the same version at build time. `bin/tests/test-entrypoint.sh` fails CI if the pom, either Dockerfile, or `deploy-local.sh` names a different version.

Check the installed version:

```bash
unzip -p tomcat/tomcat-11/lib/postgresql.jar META-INF/MANIFEST.MF | grep Implementation-Version
```

### 5.2 Other Tomcat Installations

Download the driver from Maven Central (the same artifact the test suites resolve) and copy it into Tomcat's `lib/` directory:

```bash
JDBC_VERSION=42.7.14
curl -fL -o postgresql-${JDBC_VERSION}.jar \
    https://repo1.maven.org/maven2/org/postgresql/postgresql/${JDBC_VERSION}/postgresql-${JDBC_VERSION}.jar

# Remove any older driver first: with two on the classpath, either one may load
rm -f $CATALINA_HOME/lib/postgresql*.jar
cp postgresql-${JDBC_VERSION}.jar $CATALINA_HOME/lib/
```

**Important:** Restart Tomcat after installing or replacing the driver.

---

## 6. Tomcat JNDI Configuration

### 6.1 Configure the DataSource Resource

Wikantik is deployed as Tomcat's ROOT context (`http://localhost:8080/`), so its context file is `conf/Catalina/localhost/ROOT.xml`. You normally do not write it by hand: `bin/deploy-local.sh` renders it from `wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template`, substituting `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` from the gitignored `.env`. The script refuses to run while `POSTGRES_PASSWORD` is unset or still the `CHANGEME` placeholder from `.env.example`. Rendering happens once: an existing `ROOT.xml` is never overwritten, so later edits (and later template changes) are not reapplied. Edit the deployed file by hand, or delete it and re-run `bin/deploy-local.sh`.

**Option A: ROOT.xml (what `deploy-local.sh` produces)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Context reloadable="true">
    <!-- One shared DataSource for every Wikantik subsystem -->
    <Resource name="jdbc/WikiDatabase"
              auth="Container"
              type="javax.sql.DataSource"
              factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
              driverClassName="org.postgresql.Driver"
              url="jdbc:postgresql://localhost:5432/wikantik"
              username="wikantik"
              password="your_secure_password_here"
              maxTotal="90"
              maxIdle="30"
              maxWaitMillis="5000"
              validationQuery="SELECT 1"
              testOnBorrow="true"/>
</Context>
```

**Option B: Global context (`conf/context.xml`)**

On a Tomcat that hosts only Wikantik you can instead put the same `<Resource .../>` element inside the `<Context>` of `$CATALINA_HOME/conf/context.xml`. Every web application on that Tomcat will then see the DataSource, so prefer Option A on a shared server.

The pool is Tomcat's bundled Apache Commons DBCP2, so use DBCP2 attribute names (`maxTotal`, `maxWaitMillis`, `testOnBorrow`, ...). The tomcat-jdbc names (`maxActive`, `maxWait`, `jmxEnabled`) belong to a different pool factory and are not read here.

### 6.2 Connection Pool Settings Explained

| Setting | Description | Bare-metal template | Container (`docker/entrypoint.sh`) |
|---------|-------------|---------------------|-----------|
| `maxTotal` | Maximum active connections. Keep it just under PostgreSQL's default `max_connections` (100) | 90 | 90 |
| `maxIdle` | Maximum idle connections | 30 | 30 |
| `minIdle` | Minimum idle connections | not set | not set |
| `maxWaitMillis` | Max wait for a connection before the request fails. 5 s keeps a worker thread from parking when the pool is exhausted | 5000 | 5000 |
| `validationQuery` | Query to validate connections | `SELECT 1` | `SELECT 1` |
| `testOnBorrow` | Validate before using | `true` | `false` |
| `testWhileIdle` | Validate idle connections in the evictor | not set | `true` |
| `timeBetweenEvictionRunsMillis` | Eviction check interval | not set | 30000 |

The reasoning behind these values is in [Wikantik Operations](WikantikOperations) (section 1.5, "Performance & concurrency tuning").

---

## 7. Wikantik Configuration

### 7.1 Configure wikantik-custom.properties

Wikantik reads `wikantik-custom.properties` from the classpath: put it in `$CATALINA_HOME/lib/` (where `bin/deploy-local.sh` renders it from `wikantik-custom-postgresql.properties.template`) or in `WEB-INF/classes/`. A file directly in `WEB-INF/` is not found.

The JDBC user and group databases are the shipped defaults in the bundled `ini/wikantik.properties` (`wikantik-main/src/main/resources/ini/wikantik.properties`), so you only set the keys below to override them. All property names are `wikantik.*`; the old `jspwiki.userdatabase` and `jspwiki.groupdatabase` names are not read by anything.

```properties
# ============================================================================
# Wikantik JDBC User/Group Database Configuration (these are the defaults)
# ============================================================================

wikantik.userdatabase  = com.wikantik.auth.user.JDBCUserDatabase
wikantik.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase

# ============================================================================
# JNDI DataSource Name
# Must match the Resource name in ROOT.xml
# ============================================================================
wikantik.datasource = jdbc/WikiDatabase
```

`wikantik.datasource` is required: user accounts, groups, database-backed policy grants and API keys have no other storage path, and there is no supported no-datasource mode.

### 7.2 web.xml Resource References (Not Needed)

The shipped `WEB-INF/web.xml` declares no `resource-ref` for the DataSource, and none is needed: Tomcat exposes the `<Resource>` from `ROOT.xml` under `java:comp/env/jdbc/WikiDatabase`, which is the lookup `JDBCUserDatabase` and `JDBCGroupDatabase` perform.

---

## 8. Testing the Configuration

### 8.1 Development Build and Deploy

Build Wikantik and deploy it to the development Tomcat from your checkout:

```bash
# Build the WAR (skip tests for quick iteration). Use -DskipTests, NOT
# -Dmaven.test.skip: the latter skips building wikantik-main's test-jar,
# which other modules need, and breaks a full-reactor build.
mvn clean install -DskipTests -T 1C

# First time (or after changing templates, secrets or Tomcat version):
# downloads Tomcat 11, renders ROOT.xml and wikantik-custom.properties from .env,
# deploys the WAR as ROOT, applies migrations, seeds the admin account, starts Tomcat.
# It also starts a CPU embeddings container through Docker (the first run downloads a
# ~600 MB model) unless .env sets WIKANTIK_LOCAL_EMBEDDINGS=false.
bin/deploy-local.sh

# Every later code change: stop, swap the WAR, apply pending migrations, start
bin/redeploy.sh

# Tail the logs
tail -f tomcat/tomcat-11/logs/catalina.out
```

The application log is `tomcat/tomcat-11/logs/wikantik/wikantik.log`.

### 8.2 Verify Database Connectivity

Check the logs for successful JDBC initialization:

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

### 8.3 Test Login

1. Open http://localhost:8080/ (the wiki is the ROOT context; there is no `/Wikantik` prefix)
2. Click "Login"
3. Enter the seeded credentials:
   - Username: `admin`
   - Password: `admin123`
4. You are required to choose a new password on first login (migration V039 sets `password_must_change`). Change it before exposing the deployment to anyone.
5. Verify successful login

### 8.4 Test User Creation

Wikantik has no self-registration screen; accounts are created by an administrator, by SCIM provisioning, or by SSO auto-provisioning.

1. Log in as admin
2. Open `/admin/users` and create a user
3. Verify the new user appears in the database:

```sql
SELECT login_name, full_name, email, created FROM users ORDER BY created DESC LIMIT 5;
```

### 8.5 Test Group Management

1. Log in as admin
2. Open `/admin/security` and use the Groups section
3. Create a new group
4. Add members
5. Verify in database:

```sql
SELECT g.name, g.creator, gm.member
FROM groups g
LEFT JOIN group_members gm ON g.name = gm.name
ORDER BY g.name;
```

---

## 9. Validation Procedures

### 9.1 Database Connection Validation

Create a simple test script to validate connectivity:

```bash
#!/bin/bash
# test_postgres_connection.sh

echo "Testing PostgreSQL connection..."
psql -h localhost -U wikantik -d wikantik -c "SELECT 1 as connection_test;"

echo ""
echo "Checking table structure..."
psql -h localhost -U wikantik -d wikantik -c "\dt"

echo ""
echo "Checking user count..."
psql -h localhost -U wikantik -d wikantik -c "SELECT COUNT(*) as user_count FROM users;"

echo ""
echo "Checking group count..."
psql -h localhost -U wikantik -d wikantik -c "SELECT COUNT(*) as group_count FROM groups;"
```

### 9.2 Application Health Check

Create a SQL script to validate the database state:

```sql
-- health_check.sql
-- Run this periodically to validate database health

-- Check table existence
SELECT
    table_name,
    CASE WHEN table_name IS NOT NULL THEN 'EXISTS' ELSE 'MISSING' END as status
FROM information_schema.tables
WHERE table_schema = 'public'
AND table_name IN ('users', 'roles', 'groups', 'group_members');

-- Check for orphaned roles (roles without users)
SELECT r.login_name, r.role
FROM roles r
LEFT JOIN users u ON r.login_name = u.login_name
WHERE u.login_name IS NULL;

-- Check for orphaned group members
SELECT gm.name, gm.member
FROM group_members gm
LEFT JOIN groups g ON gm.name = g.name
WHERE g.name IS NULL;

-- Check for users without roles
SELECT u.login_name
FROM users u
LEFT JOIN roles r ON u.login_name = r.login_name
WHERE r.login_name IS NULL;

-- Recent user activity
SELECT login_name, modified
FROM users
WHERE modified > CURRENT_TIMESTAMP - INTERVAL '7 days'
ORDER BY modified DESC;
```

The running application also reports engine, database and search-index health at `/api/health`, which answers only to loopback and private-network clients.

### 9.3 Unit Test Execution

Run the JDBC-related unit tests. They run against a real pgvector container (`PostgresTestDb`, started through Docker) with every migration applied, so Docker must be running; without it they skip locally with a visible reason.

```bash
# Run JDBCUserDatabase tests
mvn test -Dtest=JDBCUserDatabaseTest -pl wikantik-main

# Run JDBCGroupDatabase tests
mvn test -Dtest=JDBCGroupDatabaseTest -pl wikantik-main
```

---

## 10. Production Deployment

### 10.1 Security Hardening

**Database Security:**

```sql
-- Use a strong, unique password
ALTER USER wikantik WITH PASSWORD 'use_a_very_strong_password_here_min_32_chars';

-- Limit connection privileges
REVOKE ALL ON DATABASE wikantik FROM PUBLIC;
GRANT CONNECT ON DATABASE wikantik TO wikantik;

-- Limit schema privileges
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO wikantik;
```

Do not hand-grant table privileges. Every migration grants DML on the tables it creates to the application role, and the application uses far more tables than `users`, `roles`, `groups` and `group_members` (policy grants, API keys, the Knowledge Graph, the audit log, and so on). A table-by-table `GRANT` list copied from an old guide would leave newer tables inaccessible.

Run schema changes as a dedicated `migrate` role rather than the application role: pass `DB_MIGRATE_PASSWORD` to `bin/db/install-fresh.sh` (see [Wikantik Database Migrations](WikantikDatabaseMigrations)).

**Network Security:**

```bash
# pg_hba.conf - Production settings
# Only allow connections from application server
host    wikantik    wikantik  10.0.0.5/32    scram-sha-256

# Or use SSL
hostssl wikantik    wikantik  10.0.0.5/32    scram-sha-256 clientcert=verify-ca
```

**SSL/TLS Configuration:**

Update the JDBC URL in `ROOT.xml` for SSL:

```xml
url="jdbc:postgresql://db-server:5432/wikantik?ssl=true&amp;sslmode=verify-full&amp;sslrootcert=/path/to/ca.crt"
```

### 10.2 Connection Pool Tuning

The shipped pool (section 6.2) is already sized for production: `maxTotal` 90 sits just under PostgreSQL's default `max_connections` of 100, and `maxWaitMillis` 5000 makes a request fail fast instead of parking a worker thread when the pool is exhausted. If you raise `maxTotal`, raise PostgreSQL's `max_connections` with it. The container image uses the shape below, which validates idle connections in the background instead of on every borrow:

```xml
<Resource name="jdbc/WikiDatabase"
          auth="Container"
          type="javax.sql.DataSource"
          factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
          driverClassName="org.postgresql.Driver"
          url="jdbc:postgresql://db-server:5432/wikantik"
          username="wikantik"
          password="production_password"
          maxTotal="90"
          maxIdle="30"
          maxWaitMillis="5000"
          validationQuery="SELECT 1"
          testOnBorrow="false"
          testWhileIdle="true"
          timeBetweenEvictionRunsMillis="30000"/>
```

Optional DBCP2 settings for leak diagnosis, if you suspect connections are not being returned:

```xml
          removeAbandonedOnBorrow="true"
          removeAbandonedOnMaintenance="true"
          removeAbandonedTimeout="300"
          logAbandoned="true"
```

**Do not set `defaultAutoCommit="false"`.** Wikantik's single-statement writes (`Jdbc.update`) rely on the connection being in auto-commit mode; only `Jdbc.inTransaction` turns auto-commit off and commits explicitly. With auto-commit off in the pool, those writes would be rolled back when the connection is returned.

### 10.3 Backup and Recovery

The database holds users, groups, policy grants, API keys, the Knowledge Graph and the structural index, but **page text and attachments are files** on the filesystem. A backup must capture both: the database dump below and the page directory (`wikantik.fileSystemProvider.pageDir`). The container stack runs this as a backup sidecar with tiered retention, checksums and a restore script; see [Wikantik on Docker](WikantikOnDocker) and [Wikantik Backup and Recovery](WikantikBackupAndRecovery).

**Daily Backup Script:**

```bash
#!/bin/bash
# backup_wikantik_db.sh

BACKUP_DIR="/var/backups/postgresql"
DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/wikantik_${DATE}.sql.gz"

# Create backup directory if needed
mkdir -p "${BACKUP_DIR}"

# Dump and compress
pg_dump -h localhost -U wikantik -d wikantik | gzip > "${BACKUP_FILE}"

# Keep only last 30 days of backups
find "${BACKUP_DIR}" -name "wikantik_*.sql.gz" -mtime +30 -delete

echo "Backup completed: ${BACKUP_FILE}"
```

**Restore Procedure:**

Restore into an empty database, as a PostgreSQL superuser (the dump re-creates the `vector` extension, which needs one). The `wikantik` application role must already exist, because the dump carries its ownership and grants.

```bash
# Stop Wikantik/Tomcat first
tomcat/tomcat-11/bin/shutdown.sh     # or: systemctl stop tomcat

# Recreate an empty database (this destroys the current contents)
sudo -u postgres dropdb wikantik
sudo -u postgres createdb wikantik

# Restore from backup
gunzip -c /var/backups/postgresql/wikantik_20250101_120000.sql.gz \
    | sudo -u postgres psql -v ON_ERROR_STOP=1 -d wikantik

# If the dump predates the deployed version, apply newer migrations first:
#   DB_NAME=wikantik DB_APP_USER=wikantik PGUSER=postgres bin/db/migrate.sh
# Start Wikantik/Tomcat
tomcat/tomcat-11/bin/startup.sh      # or: systemctl start tomcat
```

### 10.4 Monitoring

**PostgreSQL Queries for Monitoring:**

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
    relname as table_name,
    pg_size_pretty(pg_total_relation_size(relid)) as total_size,
    pg_size_pretty(pg_relation_size(relid)) as data_size
FROM pg_catalog.pg_statio_user_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(relid) DESC;

-- Index usage
SELECT
    indexrelname as index_name,
    idx_scan as index_scans,
    idx_tup_read as tuples_read,
    idx_tup_fetch as tuples_fetched
FROM pg_stat_user_indexes
WHERE schemaname = 'public';
```

Metrics scraping uses the `wikantik_exporter` role created by migration V031.

---

## 11. Troubleshooting

### 11.1 Common Errors

**Error: DataSource not found**
```
javax.naming.NameNotFoundException: Name [jdbc/WikiDatabase] is not bound
```

**Solution:**
- Verify `conf/Catalina/localhost/ROOT.xml` exists and defines the Resource (re-run `bin/deploy-local.sh`)
- Check the resource name matches `wikantik.datasource` exactly
- Restart Tomcat completely

**Error: Driver not found**
```
java.sql.SQLException: No suitable driver found for jdbc:postgresql://
```

**Solution:**
- Ensure postgresql-X.X.X.jar (or `postgresql.jar`) is in $CATALINA_HOME/lib/
- Restart Tomcat after adding the driver

**Error: Authentication failed**
```
FATAL: password authentication failed for user "wikantik"
```

**Solution:**
- Verify username/password in ROOT.xml
- Check pg_hba.conf allows the connection
- Verify the user exists: `\du wikantik`

**Error: Connection refused**
```
java.net.ConnectException: Connection refused
```

**Solution:**
- Verify PostgreSQL is running: `systemctl status postgresql`
- Check PostgreSQL is listening: `ss -tlnp | grep 5432`
- Verify postgresql.conf has `listen_addresses = '*'` if the database is on another host

**Error: extension "vector" is not available** (during the migrations)

**Solution:**
- Install the pgvector package for your PostgreSQL version (section 3.1) and restart PostgreSQL

### 11.2 Debug Logging

Enable JDBC debug logging in `tomcat/tomcat-11/lib/log4j2.xml` (it is honored once `wikantik.use.external.logconfig = true`; see [Logging Configuration](LoggingConfig)):

```xml
<Logger name="com.wikantik.auth.user.JDBCUserDatabase" level="DEBUG"/>
<Logger name="com.wikantik.auth.authorize.JDBCGroupDatabase" level="DEBUG"/>
<Logger name="org.apache.tomcat.dbcp" level="DEBUG"/>
```

### 11.3 Database Diagnostics

```sql
-- Check for locks
SELECT
    pg_stat_activity.pid,
    pg_class.relname,
    pg_locks.transactionid,
    pg_locks.mode,
    pg_locks.granted
FROM pg_locks
JOIN pg_class ON pg_locks.relation = pg_class.oid
JOIN pg_stat_activity ON pg_locks.pid = pg_stat_activity.pid
WHERE pg_stat_activity.datname = 'wikantik';

-- Check for long-running queries
SELECT
    pid,
    now() - pg_stat_activity.query_start AS duration,
    query,
    state
FROM pg_stat_activity
WHERE datname = 'wikantik'
AND state != 'idle'
AND now() - pg_stat_activity.query_start > interval '5 seconds';
```

---

## Appendix: Password Hashing

### A.1 Password Format

Wikantik stores passwords as bcrypt, with one legacy RFC 2307-compliant salted
format retained for verification only:

- **{bcrypt}**: bcrypt (current default for new and re-hashed passwords)
- **{SHA-256}**: Salted SHA-256 (legacy; transparently re-hashed to bcrypt on next login)

Salted SHA-1 (`{SSHA}`) and unsalted SHA-1 (`{SHA}`) are no longer supported — a
stored value in either format never verifies.

Format: `{bcrypt}` followed by a standard `$2a$` bcrypt string, or `{SHA-256}Base64(hash + salt)`.

### A.2 Generating Password Hashes

Use the `CryptoUtil` command-line tool. It needs the bcrypt libraries on its classpath, which the deployed WAR carries in `WEB-INF/lib`; running it from `wikantik-util/target/classes` alone fails with `NoClassDefFoundError`.

```bash
# Build and deploy first (see section 8.1), then, from the repository root:
LIB='tomcat/tomcat-11/webapps/ROOT/WEB-INF/lib/*'

# Generate a SHA-256 hash (accepted at login and auto-upgraded to bcrypt on first use).
# There is no flag to emit bcrypt directly.
java -cp "$LIB" com.wikantik.util.CryptoUtil --hash "mypassword"

# Verify a password against a hash
java -cp "$LIB" com.wikantik.util.CryptoUtil --verify "mypassword" "{SHA-256}xyz123..."
```

### A.3 Updating a User's Password via SQL

```sql
-- Generate a new hash using CryptoUtil first, then:
UPDATE users
SET password = '{SHA-256}your_generated_hash_here',
    modified = CURRENT_TIMESTAMP
WHERE login_name = 'username';
```

To reset a forgotten local `admin` password back to `admin123`, use the `UPDATE` documented in the header of `bin/db/seed-users.sql`.

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-11-30 | Claude | Initial comprehensive PostgreSQL setup guide |
| 1.1 | 2026-04-17 | Claude | Repaired heading/list markup damage; fixed ToC anchors; corrected DB/user naming to wikantik/jspwiki |
| 1.2 | 2026-09-14 | Claude | Schema comes from `bin/db/migrations`; passwords are bcrypt ({SSHA} removed) |
| 1.3 | 2026-10-13 | Claude | JDBC driver 42.7.14 from Maven Central; `bin/deploy-local.sh` installs and upgrades it |
| 1.4 | 2026-10-13 | Claude | Accuracy pass against the code: PostgreSQL-only (XML store removed), Java 25 / Tomcat 11.0.26 / pgvector prerequisites, `wikantik.*` property names and `lib/` location, ROOT.xml and the DBCP2 pool as rendered by `deploy-local.sh`, `-DskipTests` build and `bin/deploy-local.sh` / `bin/redeploy.sh` flow, `admin` / `admin123` with forced change, DB role renamed to `wikantik`, no hand-written table grants, no `defaultAutoCommit=false`, CryptoUtil classpath, backup scope |

---

## References

- Source: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java`
- Source: `wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java`
- Context template: `wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template`
- Repository guide: `docs/admin/PostgreSQL.md` (bare-metal PostgreSQL setup, the same flow as this page)
- [Wikantik Database Migrations](WikantikDatabaseMigrations) (migrate role, schema-change convention)
- [Wikantik Operations](WikantikOperations) and [Wikantik Configuration Reference](WikantikConfigurationReference)
- [PostgreSQL Local Deployment](PostgreSQLLocalDeployment), [Wikantik on Docker](WikantikOnDocker), [Logging Configuration](LoggingConfig)
- [PostgreSQL JDBC Driver Documentation](https://jdbc.postgresql.org/documentation/)
- [Apache Tomcat JNDI Resources](https://tomcat.apache.org/tomcat-11.0-doc/jndi-resources-howto.html)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)

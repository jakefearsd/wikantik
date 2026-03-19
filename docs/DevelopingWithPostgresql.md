# Developing with PostgreSQL as the Wikantik User Database

This document provides a complete, step-by-step guide for configuring PostgreSQL as the backing database for Wikantik's user and group management system. It covers development setup, testing, validation, and production deployment.

## Table of Contents

1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [PostgreSQL Server Setup](#3-postgresql-server-setup)
4. [Database Schema Creation](#4-database-schema-creation)
5. [JDBC Driver Installation](#5-jdbc-driver-installation)
6. [Tomcat JNDI Configuration](#6-tomcat-jndi-configuration)
7. [Wikantik Configuration](#7-jspwiki-configuration)
8. [Testing the Configuration](#8-testing-the-configuration)
9. [Validation Procedures](#9-validation-procedures)
10. [Production Deployment](#10-production-deployment)
11. [Troubleshooting](#11-troubleshooting)
12. [Appendix: Password Hashing](#appendix-password-hashing)

---

## 1. Overview

### What This Document Covers

Wikantik can store user profiles and group memberships in either:
- **XML files** (default) - Simple, file-based storage suitable for small installations
- **Relational database** - Scalable, performant storage for production environments

This guide focuses on using **PostgreSQL** as the relational database backend, which is recommended for:
- Production environments with multiple users
- High-availability deployments
- Integration with existing PostgreSQL infrastructure
- Enhanced security through database-level access controls

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
│          (java:comp/env/jdbc/*)                                 │
└────────────────────┼────────────────────────────────────────────┘
                     │
┌────────────────────┼────────────────────────────────────────────┐
│                    ▼                                            │
│         Tomcat DataSource Pool                                  │
│           (Connection Pooling)                                  │
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

### Key Implementation Details

From the source code (`JDBCUserDatabase.java` and `JDBCGroupDatabase.java`):

- **JNDI Lookup**: Wikantik uses JNDI to look up DataSources, not direct JDBC connections
- **Password Hashing**: Passwords are stored using salted SHA-1 (`{SSHA}`) or SHA-256 (`{SHA-256}`) format
- **Transaction Support**: Automatic detection and use of database transactions
- **Prepared Statements**: All SQL uses prepared statements (immune to SQL injection)
- **User Attributes**: Custom attributes are serialized as Base64-encoded Java objects

---

## 2. Prerequisites

### Software Requirements

| Component | Minimum Version | Recommended |
|-----------|----------------|-------------|
| PostgreSQL | 12.0 | 15.0+ |
| Java JDK | 11 | 17+ |
| Apache Tomcat | 9.0 | 11.0 |
| PostgreSQL JDBC Driver | 42.2.x | 42.7.x |

### Required Knowledge

- Basic PostgreSQL administration (creating databases, users, grants)
- Tomcat configuration (context.xml, JNDI resources)
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

### 3.2 Create the Wikantik Database

Connect to PostgreSQL as the superuser:

```bash
sudo -u postgres psql
```

Create the database:

```sql
-- Create the database
CREATE DATABASE jspwiki
    WITH
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'
    TEMPLATE = template0;

-- Verify creation
\l jspwiki
```

### 3.3 Create the Application User

Create a dedicated database user for Wikantik:

```sql
-- Create the user with a strong password
CREATE USER wikantik WITH
    ENCRYPTED PASSWORD 'your_secure_password_here'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE;

-- Grant connection privileges
GRANT CONNECT ON DATABASE wikantik TO jspwiki;

-- Connect to the wikantik database
\c jspwiki

-- Grant schema usage and creation privileges
-- NOTE: PostgreSQL 15+ restricts CREATE on public schema by default
GRANT USAGE ON SCHEMA public TO jspwiki;
GRANT CREATE ON SCHEMA public TO jspwiki;
```

**Important (PostgreSQL 15+):** Starting with PostgreSQL 15, the `CREATE` privilege on the `public` schema is no longer granted to all users by default. You must explicitly grant it as shown above, or create the tables as a superuser and only grant DML permissions to the application user.

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
host    wikantik     wikantik   127.0.0.1/32    scram-sha-256
host    wikantik     wikantik   ::1/128         scram-sha-256
```

Reload PostgreSQL configuration:

```bash
sudo systemctl reload postgresql
```

---

## 4. Database Schema Creation

### 4.1 Complete PostgreSQL Schema

**Important:** The schema creation script must be run as a PostgreSQL **superuser** (e.g., `postgres`) because it:
- Creates/drops the `jspwiki` application user
- Grants permissions on tables

Connect to the wikantik database as the superuser:

```bash
# Option 1: Run the DDL file directly
sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql.ddl

# Option 2: Connect interactively as superuser
sudo -u postgres psql -d jspwiki
```

Execute the following DDL:

```sql
-- ============================================================================
-- Wikantik PostgreSQL Schema
-- Based on: wikantik-war/src/main/config/db/postgresql.ddl
-- Compatible with: JDBCUserDatabase.java and JDBCGroupDatabase.java
-- ============================================================================

-- Drop existing tables if recreating (BE CAREFUL IN PRODUCTION!)
-- DROP TABLE IF EXISTS group_members CASCADE;
-- DROP TABLE IF EXISTS groups CASCADE;
-- DROP TABLE IF EXISTS roles CASCADE;
-- DROP TABLE IF EXISTS users CASCADE;

-- ============================================================================
-- Users Table
-- Stores user profiles for authentication and personalization
-- ============================================================================
CREATE TABLE users (
    -- Unique identifier for the user (auto-generated by Wikantik)
    uid VARCHAR(100),

    -- User's email address (used for password recovery, notifications)
    email VARCHAR(100),

    -- User's full display name
    full_name VARCHAR(100),

    -- Login name (username) - PRIMARY KEY, must be unique
    login_name VARCHAR(100) NOT NULL,

    -- Hashed password in {SSHA} or {SHA-256} format
    password VARCHAR(100),

    -- Wiki-formatted name (typically CamelCase of full_name)
    wiki_name VARCHAR(100),

    -- Profile creation timestamp
    created TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Last modification timestamp
    modified TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Account lock expiry (NULL = not locked)
    lock_expiry TIMESTAMP WITH TIME ZONE,

    -- Serialized user attributes (Base64-encoded Java Map)
    -- Used for: OAuth metadata, custom profile fields, preferences
    attributes TEXT,

    PRIMARY KEY (login_name)
);

-- Create indexes for common lookup patterns
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_wiki_name ON users(wiki_name);
CREATE INDEX idx_users_uid ON users(uid);

-- ============================================================================
-- Roles Table
-- Stores container-managed authentication roles
-- Each user can have multiple roles (one row per role)
-- ============================================================================
CREATE TABLE roles (
    -- References users.login_name
    login_name VARCHAR(100) NOT NULL,

    -- Role name (e.g., 'Authenticated', 'Admin', 'Editor')
    role VARCHAR(100) NOT NULL,

    -- Composite key to prevent duplicate role assignments
    PRIMARY KEY (login_name, role)
);

-- Create index for role lookups
CREATE INDEX idx_roles_login_name ON roles(login_name);

-- ============================================================================
-- Groups Table
-- Stores wiki group definitions
-- ============================================================================
CREATE TABLE groups (
    -- Group name - PRIMARY KEY, must be unique
    name VARCHAR(100) NOT NULL,

    -- User who created the group
    creator VARCHAR(100),

    -- Group creation timestamp
    created TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- User who last modified the group
    modifier VARCHAR(100),

    -- Last modification timestamp
    modified TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (name)
);

-- ============================================================================
-- Group Members Table
-- Stores group membership (many-to-many relationship)
-- ============================================================================
CREATE TABLE group_members (
    -- References groups.name
    name VARCHAR(100) NOT NULL,

    -- Member name (wiki name of the user)
    member VARCHAR(100) NOT NULL,

    -- Composite primary key
    PRIMARY KEY (name, member)
);

-- Create index for member lookups
CREATE INDEX idx_group_members_member ON group_members(member);
```

### 4.2 Grant Table Permissions

After creating the tables, grant the necessary permissions:

```sql
-- Grant DML permissions on all tables
GRANT SELECT, INSERT, UPDATE, DELETE ON users TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON roles TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON groups TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON group_members TO jspwiki;
```

### 4.3 Create Initial Admin User

Create an initial administrator account:

```sql
-- Insert the admin user
-- Password: 'admin' hashed with {SSHA}
-- You should change this password immediately after first login!
INSERT INTO users (
    uid,
    email,
    full_name,
    login_name,
    password,
    wiki_name,
    created,
    modified
) VALUES (
    '-6852820166199419346',
    'admin@localhost',
    'Administrator',
    'admin',
    '{SSHA}6YNKYMwXICUf5pMvYUZumgbFCxZMT2njtUQtJw==',
    'Administrator',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Assign the Admin role
INSERT INTO roles (login_name, role) VALUES ('admin', 'Admin');
INSERT INTO roles (login_name, role) VALUES ('admin', 'Authenticated');

-- Create the Admin group
INSERT INTO groups (name, created, modified)
VALUES ('Admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Add Administrator to the Admin group
INSERT INTO group_members (name, member) VALUES ('Admin', 'Administrator');
```

### 4.4 Verify Schema

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

### 5.1 Download the PostgreSQL JDBC Driver

Download the PostgreSQL JDBC driver from the official website:
- https://jdbc.postgresql.org/download/

Choose the JDBC 4.2 driver (for Java 8+) or JDBC 4.3 driver (for Java 11+).

Example download using curl:

```bash
# Download PostgreSQL JDBC Driver 42.7.4 (check for latest version)
curl -L -o postgresql-42.7.4.jar \
    https://jdbc.postgresql.org/download/postgresql-42.7.4.jar
```

### 5.2 Install in Tomcat

Copy the JDBC driver to Tomcat's lib directory:

```bash
# For the project's bundled Tomcat
cp postgresql-42.7.4.jar /home/jakefear/source/jspwiki/tomcat/tomcat-11/lib/

# For a system Tomcat installation
cp postgresql-42.7.4.jar $CATALINA_HOME/lib/
```

**Important:** Restart Tomcat after adding the driver.

---

## 6. Tomcat JNDI Configuration

### 6.1 Configure DataSource Resources

Edit Tomcat's `context.xml` or create an application-specific context file.

**Option A: Global Context (conf/context.xml)**

Edit `$CATALINA_HOME/conf/context.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <!-- Default session configuration -->
    <WatchedResource>WEB-INF/web.xml</WatchedResource>
    <WatchedResource>WEB-INF/tomcat-web.xml</WatchedResource>
    <WatchedResource>${catalina.base}/conf/web.xml</WatchedResource>

    <!-- Wikantik User Database DataSource -->
    <Resource
        name="jdbc/UserDatabase"
        auth="Container"
        type="javax.sql.DataSource"
        driverClassName="org.postgresql.Driver"
        url="jdbc:postgresql://localhost:5432/jspwiki"
        username="jspwiki"
        password="your_secure_password_here"

        <!-- Connection Pool Settings -->
        maxTotal="50"
        maxIdle="10"
        minIdle="5"
        maxWaitMillis="10000"

        <!-- Connection Validation -->
        validationQuery="SELECT 1"
        validationQueryTimeout="5"
        testOnBorrow="true"
        testOnReturn="false"
        testWhileIdle="true"
        timeBetweenEvictionRunsMillis="30000"
        minEvictableIdleTimeMillis="60000"

        <!-- Connection Settings -->
        removeAbandonedOnBorrow="true"
        removeAbandonedOnMaintenance="true"
        removeAbandonedTimeout="300"
        logAbandoned="true"
    />

    <!-- Wikantik Group Database DataSource -->
    <Resource
        name="jdbc/GroupDatabase"
        auth="Container"
        type="javax.sql.DataSource"
        driverClassName="org.postgresql.Driver"
        url="jdbc:postgresql://localhost:5432/jspwiki"
        username="jspwiki"
        password="your_secure_password_here"

        <!-- Connection Pool Settings -->
        maxTotal="50"
        maxIdle="10"
        minIdle="5"
        maxWaitMillis="10000"

        <!-- Connection Validation -->
        validationQuery="SELECT 1"
        validationQueryTimeout="5"
        testOnBorrow="true"
        testOnReturn="false"
        testWhileIdle="true"
        timeBetweenEvictionRunsMillis="30000"
        minEvictableIdleTimeMillis="60000"

        <!-- Connection Settings -->
        removeAbandonedOnBorrow="true"
        removeAbandonedOnMaintenance="true"
        removeAbandonedTimeout="300"
        logAbandoned="true"
    />
</Context>
```

**Option B: Application-Specific Context**

Create `$CATALINA_HOME/conf/Catalina/localhost/Wikantik.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Context docBase="/path/to/Wikantik.war" path="/Wikantik">
    <Resource
        name="jdbc/UserDatabase"
        auth="Container"
        type="javax.sql.DataSource"
        driverClassName="org.postgresql.Driver"
        url="jdbc:postgresql://localhost:5432/jspwiki"
        username="jspwiki"
        password="your_secure_password_here"
        maxTotal="50"
        maxIdle="10"
        minIdle="5"
        maxWaitMillis="10000"
        validationQuery="SELECT 1"
        testOnBorrow="true"
    />

    <Resource
        name="jdbc/GroupDatabase"
        auth="Container"
        type="javax.sql.DataSource"
        driverClassName="org.postgresql.Driver"
        url="jdbc:postgresql://localhost:5432/jspwiki"
        username="jspwiki"
        password="your_secure_password_here"
        maxTotal="50"
        maxIdle="10"
        minIdle="5"
        maxWaitMillis="10000"
        validationQuery="SELECT 1"
        testOnBorrow="true"
    />
</Context>
```

### 6.2 Connection Pool Settings Explained

| Setting | Description | Recommended Value |
|---------|-------------|-------------------|
| `maxTotal` | Maximum active connections | 50-100 |
| `maxIdle` | Maximum idle connections | 10-20 |
| `minIdle` | Minimum idle connections | 5-10 |
| `maxWaitMillis` | Max wait time for connection | 10000 (10 sec) |
| `validationQuery` | Query to validate connections | `SELECT 1` |
| `testOnBorrow` | Validate before using | `true` |
| `timeBetweenEvictionRunsMillis` | Eviction check interval | 30000 (30 sec) |
| `minEvictableIdleTimeMillis` | Min idle time before eviction | 60000 (1 min) |

---

## 7. Wikantik Configuration

### 7.1 Configure wikantik-custom.properties

Create or edit `WEB-INF/wikantik-custom.properties`:

```properties
# ============================================================================
# Wikantik JDBC User/Group Database Configuration
# ============================================================================

# Enable JDBC User Database (instead of default XML)
jspwiki.userdatabase = com.wikantik.auth.user.JDBCUserDatabase

# Enable JDBC Group Database (instead of default XML)
jspwiki.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase

# ============================================================================
# JNDI DataSource Names
# These must match the Resource names in Tomcat's context.xml
# ============================================================================
jspwiki.userdatabase.datasource = jdbc/UserDatabase
jspwiki.groupdatabase.datasource = jdbc/GroupDatabase

# ============================================================================
# User Database Table and Column Mappings
# Default values match the schema in Section 4
# Only uncomment and modify if using a different schema
# ============================================================================
jspwiki.userdatabase.table = users
jspwiki.userdatabase.uid = uid
jspwiki.userdatabase.email = email
jspwiki.userdatabase.fullName = full_name
jspwiki.userdatabase.loginName = login_name
jspwiki.userdatabase.password = password
jspwiki.userdatabase.wikiName = wiki_name
jspwiki.userdatabase.created = created
jspwiki.userdatabase.modified = modified
jspwiki.userdatabase.lockExpiry = lock_expiry
jspwiki.userdatabase.attributes = attributes
jspwiki.userdatabase.roleTable = roles
jspwiki.userdatabase.role = role

# ============================================================================
# Group Database Table and Column Mappings
# Default values match the schema in Section 4
# Only uncomment and modify if using a different schema
# ============================================================================
jspwiki.groupdatabase.table = groups
jspwiki.groupdatabase.membertable = group_members
jspwiki.groupdatabase.created = created
jspwiki.groupdatabase.creator = creator
jspwiki.groupdatabase.name = name
jspwiki.groupdatabase.member = member
jspwiki.groupdatabase.modified = modified
jspwiki.groupdatabase.modifier = modifier
```

### 7.2 Verify web.xml Resource References (Optional)

If using resource-ref declarations, ensure `WEB-INF/web.xml` includes:

```xml
<resource-ref>
    <description>User Database Connection</description>
    <res-ref-name>jdbc/UserDatabase</res-ref-name>
    <res-type>javax.sql.DataSource</res-type>
    <res-auth>Container</res-auth>
</resource-ref>

<resource-ref>
    <description>Group Database Connection</description>
    <res-ref-name>jdbc/GroupDatabase</res-ref-name>
    <res-type>javax.sql.DataSource</res-type>
    <res-auth>Container</res-auth>
</resource-ref>
```

---

## 8. Testing the Configuration

### 8.1 Development Build and Deploy

Build Wikantik and deploy to the development Tomcat:

```bash
cd /home/jakefear/source/jspwiki

# Build the project (skip tests for quick iteration)
mvn clean install -Dmaven.test.skip

# Copy the WAR to Tomcat
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/

# Start Tomcat
./tomcat/tomcat-11/bin/startup.sh

# Tail the logs
tail -f tomcat/tomcat-11/logs/catalina.out
```

### 8.2 Verify Database Connectivity

Check the logs for successful JDBC initialization:

```bash
grep -E "(JDBCUserDatabase|JDBCGroupDatabase)" tomcat/tomcat-11/logs/catalina.out
```

Expected output:
```
JDBCUserDatabase initialized from JNDI DataSource: jdbc/UserDatabase
JDBCUserDatabase supports transactions. Good; we will use them.
JDBCGroupDatabase initialized from JNDI DataSource: jdbc/GroupDatabase
JDBCGroupDatabase supports transactions. Good; we will use them.
```

### 8.3 Test Login

1. Open http://localhost:8080/Wikantik
2. Click "Login"
3. Enter credentials:
   - Username: `admin`
   - Password: `admin` (or whatever you set)
4. Verify successful login

### 8.4 Test User Registration

1. Log out
2. Click "Register"
3. Fill in the registration form
4. Complete registration
5. Verify the new user appears in the database:

```sql
SELECT login_name, full_name, email, created FROM users ORDER BY created DESC LIMIT 5;
```

### 8.5 Test Group Management

1. Log in as admin
2. Go to Group Management (Admin > Group Management)
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

### 9.3 Unit Test Execution

Run the JDBC-related unit tests:

```bash
cd /home/jakefear/source/jspwiki

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
GRANT CONNECT ON DATABASE wikantik TO jspwiki;

-- Limit schema privileges
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO jspwiki;

-- Only grant required table permissions
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON users TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON roles TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON groups TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON group_members TO jspwiki;
```

**Network Security:**

```bash
# pg_hba.conf - Production settings
# Only allow connections from application server
host    wikantik     wikantik   10.0.0.5/32    scram-sha-256

# Or use SSL
hostssl wikantik     wikantik   10.0.0.5/32    scram-sha-256 clientcert=verify-ca
```

**SSL/TLS Configuration:**

Update the JDBC URL in context.xml for SSL:

```xml
url="jdbc:postgresql://db-server:5432/jspwiki?ssl=true&amp;sslmode=verify-full&amp;sslrootcert=/path/to/ca.crt"
```

### 10.2 Connection Pool Tuning

For production workloads, tune the connection pool:

```xml
<Resource
    name="jdbc/UserDatabase"
    auth="Container"
    type="javax.sql.DataSource"
    driverClassName="org.postgresql.Driver"
    url="jdbc:postgresql://db-server:5432/jspwiki"
    username="jspwiki"
    password="production_password"

    <!-- Production Pool Settings -->
    maxTotal="100"
    maxIdle="20"
    minIdle="10"
    maxWaitMillis="30000"

    <!-- Connection Validation -->
    validationQuery="SELECT 1"
    validationQueryTimeout="5"
    testOnBorrow="true"
    testOnReturn="false"
    testWhileIdle="true"
    timeBetweenEvictionRunsMillis="30000"
    minEvictableIdleTimeMillis="60000"
    numTestsPerEvictionRun="5"

    <!-- Abandoned Connection Handling -->
    removeAbandonedOnBorrow="true"
    removeAbandonedOnMaintenance="true"
    removeAbandonedTimeout="300"
    logAbandoned="true"

    <!-- Performance Settings -->
    defaultAutoCommit="false"
    defaultTransactionIsolation="READ_COMMITTED"
    poolPreparedStatements="true"
    maxOpenPreparedStatements="100"

    <!-- Connection Factory -->
    factory="org.apache.tomcat.jdbc.pool.DataSourceFactory"
    jmxEnabled="true"
/>
```

### 10.3 Backup and Recovery

**Daily Backup Script:**

```bash
#!/bin/bash
# backup_jspwiki_db.sh

BACKUP_DIR="/var/backups/postgresql"
DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/jspwiki_${DATE}.sql.gz"

# Create backup directory if needed
mkdir -p "${BACKUP_DIR}"

# Dump and compress
pg_dump -h localhost -U wikantik -d wikantik | gzip > "${BACKUP_FILE}"

# Keep only last 30 days of backups
find "${BACKUP_DIR}" -name "jspwiki_*.sql.gz" -mtime +30 -delete

echo "Backup completed: ${BACKUP_FILE}"
```

**Restore Procedure:**

```bash
# Stop Wikantik/Tomcat first
systemctl stop tomcat

# Restore from backup
gunzip -c /var/backups/postgresql/jspwiki_20250101_120000.sql.gz | psql -h localhost -U wikantik -d jspwiki

# Start Wikantik/Tomcat
systemctl start tomcat
```

### 10.4 Monitoring

**PostgreSQL Queries for Monitoring:**

```sql
-- Active connections
SELECT count(*) FROM pg_stat_activity WHERE datname = 'jspwiki';

-- Connection state breakdown
SELECT state, count(*)
FROM pg_stat_activity
WHERE datname = 'jspwiki'
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

---

## 11. Troubleshooting

### 11.1 Common Errors

**Error: DataSource not found**
```
javax.naming.NameNotFoundException: Name [jdbc/UserDatabase] is not bound
```

**Solution:**
- Verify the Resource is defined in context.xml
- Check the resource name matches exactly
- Restart Tomcat completely

**Error: Driver not found**
```
java.sql.SQLException: No suitable driver found for jdbc:postgresql://
```

**Solution:**
- Ensure postgresql-X.X.X.jar is in $CATALINA_HOME/lib/
- Restart Tomcat after adding the driver

**Error: Authentication failed**
```
FATAL: password authentication failed for user "jspwiki"
```

**Solution:**
- Verify username/password in context.xml
- Check pg_hba.conf allows the connection
- Verify the user exists: `\du jspwiki`

**Error: Connection refused**
```
java.net.ConnectException: Connection refused
```

**Solution:**
- Verify PostgreSQL is running: `systemctl status postgresql`
- Check PostgreSQL is listening: `ss -tlnp | grep 5432`
- Verify postgresql.conf has `listen_addresses = '*'`

### 11.2 Debug Logging

Enable JDBC debug logging in `log4j2.xml`:

```xml
<Logger name="com.wikantik.auth.user.JDBCUserDatabase" level="DEBUG"/>
<Logger name="com.wikantik.auth.authorize.JDBCGroupDatabase" level="DEBUG"/>
<Logger name="org.apache.tomcat.jdbc.pool" level="DEBUG"/>
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
WHERE pg_stat_activity.datname = 'jspwiki';

-- Check for long-running queries
SELECT
    pid,
    now() - pg_stat_activity.query_start AS duration,
    query,
    state
FROM pg_stat_activity
WHERE datname = 'jspwiki'
AND state != 'idle'
AND now() - pg_stat_activity.query_start > interval '5 seconds';
```

---

## Appendix: Password Hashing

### A.1 Password Format

Wikantik stores passwords using RFC 2307-compliant salted hashing:

- **{SSHA}**: Salted SHA-1 (legacy, still supported)
- **{SHA-256}**: Salted SHA-256 (recommended)

Format: `{ALGORITHM}Base64(hash + salt)`

### A.2 Generating Password Hashes

Use the `CryptoUtil` command-line tool:

```bash
cd /home/jakefear/source/jspwiki

# Build the project first
mvn clean install -Dmaven.test.skip

# Generate a SHA-256 hash (recommended)
java -cp jspwiki-util/target/classes com.wikantik.util.CryptoUtil --hash "mypassword" "{SHA-256}"

# Generate a SSHA hash (legacy)
java -cp jspwiki-util/target/classes com.wikantik.util.CryptoUtil --hash "mypassword" "{SSHA}"

# Verify a password against a hash
java -cp jspwiki-util/target/classes com.wikantik.util.CryptoUtil --verify "mypassword" "{SHA-256}xyz123..."
```

### A.3 Updating a User's Password via SQL

```sql
-- Generate a new hash using CryptoUtil first, then:
UPDATE users
SET password = '{SHA-256}your_generated_hash_here',
    modified = CURRENT_TIMESTAMP
WHERE login_name = 'username';
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-11-30 | Claude | Initial comprehensive PostgreSQL setup guide |

---

## References

- [Wikantik Source: JDBCUserDatabase.java](../wikantik-main/src/main/java/org/apache/wiki/auth/user/JDBCUserDatabase.java)
- [Wikantik Source: JDBCGroupDatabase.java](../wikantik-main/src/main/java/org/apache/wiki/auth/authorize/JDBCGroupDatabase.java)
- [PostgreSQL JDBC Driver Documentation](https://jdbc.postgresql.org/documentation/)
- [Apache Tomcat JNDI Resources](https://tomcat.apache.org/tomcat-11.0-doc/jndi-resources-howto.html)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)

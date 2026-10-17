---
type: reference
tags:
- uncategorized
canonical_id: 01KQ0P44VDF3BAZ7T7KC5T0QS6
cluster: databases
title: Relational User Database
summary: 'How Wikantik stores users and groups in PostgreSQL via JNDI: DataSource
  config, JDBC user/group databases, schema from migrations, password hashing.'
---
# Using a Relational Database for User Management

This guide explains how Wikantik stores user accounts and groups in a relational database, and what has to be in place for that to work. In Wikantik the relational database is not an option you switch on: it is **PostgreSQL**, it is the only supported user and group store, and the XML user and group files of the JSPWiki era are gone.

## 1. Overview

User profiles, password hashes, roles, wiki groups, database-backed policy grants, API keys and the rest of the application state all live in one PostgreSQL database, reached through a single JNDI `DataSource`. Three properties wire it up, and the defaults in `ini/wikantik.properties` already point at the JDBC implementations:

```properties
wikantik.userdatabase  = com.wikantik.auth.user.JDBCUserDatabase
wikantik.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase
wikantik.datasource    = jdbc/WikiDatabase
```

You only need to set these in `wikantik-custom.properties` if you are replacing an implementation or renaming the JNDI resource. There is no supported deployment without a datasource. MySQL and other databases are not supported: no other JDBC driver ships in any module, and the schema uses PostgreSQL features such as the pgvector extension.

## 2. Prerequisites

- **PostgreSQL 15 or later with the pgvector extension.** Migration V004 runs `CREATE EXTENSION vector`, which succeeds only if the pgvector binaries are installed on the server.
- **Java 25 or later and Tomcat 11** (the project pins Tomcat 11.0.27 in `bin/deploy-local.sh` and in the `Dockerfile`).
- **The PostgreSQL JDBC driver, version 42.7.14** (`<postgresql-jdbc.version>` in the root `pom.xml`). The WAR declares the driver `provided`, so the container must supply it.

## 3. Configuration Steps

Most installs never do these by hand. `bin/db/install-fresh.sh` creates the database, the application role and the full schema; `bin/deploy-local.sh` downloads Tomcat and the driver, renders the DataSource and properties from `.env`, deploys the WAR and applies pending migrations; and the Docker image's `docker/entrypoint.sh` does the same rendering on every start. The steps below describe what those scripts produce, for anyone wiring up their own Tomcat. For a full walkthrough see [Developing with PostgreSQL](DevelopingWithPostgresql).

### Step 1: Add the JDBC driver

Put `postgresql-42.7.14.jar` in `$CATALINA_HOME/lib`. `bin/deploy-local.sh` installs it as `tomcat/tomcat-11/lib/postgresql.jar` and replaces a jar at any other version; the Docker image downloads the same version from Maven Central at build time (`ARG POSTGRES_JDBC_VERSION`).

### Step 2: Configure the JNDI DataSource in Tomcat

Wikantik is deployed as the ROOT context, so the resource goes in `conf/Catalina/localhost/ROOT.xml`. This is the shape `deploy-local.sh` and the Docker entrypoint render:

```xml
<Context>
  <Resource name="jdbc/WikiDatabase"
            auth="Container"
            type="javax.sql.DataSource"
            factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
            driverClassName="org.postgresql.Driver"
            url="jdbc:postgresql://localhost:5432/wikantik"
            username="wikantik"
            password="your_password"
            maxTotal="90"
            maxIdle="30"
            maxWaitMillis="5000"
            validationQuery="SELECT 1"
            testOnBorrow="true"/>
</Context>
```

`maxTotal` is pressed just under PostgreSQL's default `max_connections` of 100, and `maxWaitMillis` is 5 seconds so a request fails fast instead of parking a worker thread when the pool is exhausted. Replace the URL, username and password with your own; in the local and Docker paths they come from `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_HOST` and `POSTGRES_PORT` in `.env`.

### Step 3: Wikantik properties

Nothing is required: the defaults shown in the overview apply. Put `wikantik-custom.properties` in `$CATALINA_HOME/lib` (on the container classpath, not inside `WEB-INF`) if you need any override.

### Step 4: Create the schema with migrations

Do not create the tables by hand. The numbered files in `bin/db/migrations/` (`V001` onward) are the only schema definition, and `bin/db/migrate.sh` applies the pending ones and records them in `schema_migrations`. `bin/db/install-fresh.sh` does this for a new database, `bin/deploy-local.sh` and `bin/redeploy.sh` run it on every deploy, and the Docker entrypoint runs it before Tomcat starts. Hand-written DDL would leave the ledger out of step and make the next migration fail. See [Developing with PostgreSQL](DevelopingWithPostgresql) for the surrounding setup.

## 4. What the Schema Contains

The user and group tables come from `V002__core_users_groups.sql`, with later columns added by V039 and V042.

| Table | Columns and notes |
|-------|-------------------|
| `users` | `login_name` (primary key), `uid`, `email`, `full_name`, `password`, `wiki_name` (unique, V015), `created`, `modified`, `lock_expiry`, `bio`, `attributes` (Base64-serialized custom profile attributes), `password_must_change` (V039), `last_login` (V042). SSO-provisioned accounts have a `NULL` password. |
| `roles` | `login_name`, `role` — one row per login and role, with no foreign key to `users`; the application keeps the two consistent. |
| `groups` | `name` (primary key), `creator`, `created`, `modifier`, `modified`. |
| `group_members` | `name`, `member`, with a composite primary key on `(name, member)`. |

A fresh database is seeded with an `admin` account in the built-in `Admin` group. Its password is flagged `password_must_change`, so the first login forces a new password. Note that `/admin/*` access requires membership of the `Admin` **group**; an account that only has a row in `roles` can authenticate but is refused admin pages with a 403.

## 5. Password Hashing

New and re-hashed passwords are stored as bcrypt with a `{bcrypt}` prefix. Legacy salted SHA-256 (`{SHA-256}`) hashes still verify and are re-hashed to bcrypt on the owner's next successful login. The SHA-1 formats `{SHA}` and `{SSHA}` were removed in 2.4.24 and never verify, so an account still holding one has to reset its password.

## 6. Verifying the Wiring

On startup the Tomcat log confirms both databases bound to the DataSource:

```bash
grep -E "JDBC(User|Group)Database" tomcat/tomcat-11/logs/catalina.out
# JDBCUserDatabase initialized from JNDI DataSource: jdbc/WikiDatabase
# JDBCGroupDatabase initialized from JNDI DataSource: jdbc/WikiDatabase
```

Users, groups, roles and policy grants are then managed from the admin panel (`/admin/users`, `/admin/security`) or provisioned by an identity provider over SCIM (`/scim/v2/*`).

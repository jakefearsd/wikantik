# PostgreSQL

This guide is for operators and developers who run Wikantik against a PostgreSQL
server they manage directly: a local development box or a single-host install, with
Tomcat 11 deployed as the ROOT context by `bin/deploy-local.sh`. It covers installing
PostgreSQL and pgvector, bootstrapping the database, deploying and redeploying,
how the application connects to the database, the user/group/policy schema,
performance knobs, production hardening, and troubleshooting.

The container path (recommended for production) is [DockerDeployment.md](DockerDeployment.md),
where the stack bundles its own PostgreSQL. Schema changes and the `migrate` role are
covered in [DatabaseMigrations.md](DatabaseMigrations.md). The operator handbook is
[WikantikOperations.md](WikantikOperations.md).

Wikantik is PostgreSQL-only. No other database driver ships in any module, and the
connection pool, context template and migration scripts all assume PostgreSQL.

## What ends up where

- **Tomcat 11** at `tomcat/tomcat-11/` (gitignored). `bin/deploy-local.sh` downloads
  it on first run.
- **PostgreSQL** with a `wikantik` database and a `wikantik` application role.
- **Operator scripts** under `bin/`:
  - `bin/deploy-local.sh` bootstraps Tomcat, renders config templates, deploys the
    WAR, runs migrations and seeds the default admin account.
  - `bin/redeploy.sh` is the fast path after the first deploy: shut down, rotate
    `catalina.out`, swap the WAR, run pending migrations, start.
  - `bin/db/install-fresh.sh` creates the database, the application role and the
    full schema (idempotent).
  - `bin/db/migrate.sh` applies pending migrations (see [DatabaseMigrations.md](DatabaseMigrations.md)).
- **Configuration templates** in `wikantik-war/src/main/config/tomcat/` (git-tracked).

The wiki is served at the root context (`/`): the React SPA at `/`, the Page Graph
viewer at `/page-graph`, the Knowledge Graph viewer at `/knowledge-graph`, and admin
tools under `/admin/*`. There is no `/Wikantik/` prefix.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 25+ | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js + npm | 20.19+ (or 22.12+) | The WAR build runs `npm install` and `vite build` |
| PostgreSQL | 15+ | Listening on `localhost:5432` |
| pgvector extension | 0.5+ | Required: the Knowledge Graph and embedding tables use it |
| Apache Tomcat | 11.0.26 | Pinned as `TOMCAT_VERSION` in `bin/deploy-local.sh` and in the `Dockerfile`; downloaded for you |
| PostgreSQL JDBC driver | 42.7.4 | Downloaded into `tomcat/tomcat-11/lib/postgresql.jar` by `bin/deploy-local.sh` |

### Install pgvector

`install-fresh.sh` runs migration V004, which issues `CREATE EXTENSION vector`. That
succeeds only when the pgvector binaries are already installed on the server. The
per-platform instructions are in the project README under
[Installing pgvector](../../README.md#installing-pgvector). On Debian or Ubuntu with
PostgreSQL 16:

```bash
sudo apt install -y postgresql-16-pgvector
sudo systemctl restart postgresql
```

Verify:

```bash
psql -h localhost -U postgres -c \
    "SELECT name, default_version FROM pg_available_extensions WHERE name='vector';"
```

You should see a `vector` row.

If the application role connects over TCP, make sure `pg_hba.conf` lets it in, then
reload PostgreSQL:

```
# TYPE  DATABASE   USER       ADDRESS         METHOD
host    wikantik   wikantik   127.0.0.1/32    scram-sha-256
host    wikantik   wikantik   ::1/128         scram-sha-256
```

```bash
sudo systemctl reload postgresql
```

## Set up a local deployment (first time)

### 1. Create the database, role and schema

`install-fresh.sh` is idempotent. It creates the `wikantik` database and application
role, grants `CONNECT`/`USAGE`, runs `bin/db/migrate.sh` (every `V*.sql` in
`bin/db/migrations/`) and, when `DB_MIGRATE_PASSWORD` is set, provisions the dedicated
`migrate` role. Run it as a PostgreSQL superuser:

```bash
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role
```

Without `--no-migrate-role` the script refuses to run unless `DB_MIGRATE_PASSWORD` is
set. `--no-migrate-role` runs migrations as the superuser, which is the simplest choice
for local development. For the least-privilege role, see
[DatabaseMigrations.md](DatabaseMigrations.md#provision-the-migrate-role).

If your `~/.pgpass` already authenticates the `postgres` superuser you can drop the
`sudo -u postgres` prefix.

A default `admin` account (password `admin123`) is created by migration V002 and
`bin/db/seed-users.sql`, and is flagged `password_must_change` (migration V039), so the
first login forces you to choose a new password. Change it before exposing the
deployment to anyone.

### 2. Build

```bash
mvn clean install -DskipTests -T 1C
```

Use `-DskipTests`, not `-Dmaven.test.skip`; the latter skips building the test-jar
that other modules depend on. Do not add `-T` to a raw `-Pintegration-tests`
invocation: parallel integration tests are supported only through
`bin/run-tests.sh --parallel N`.

### 3. Configure secrets

Copy `.env.example` to `.env` and set `POSTGRES_PASSWORD` to the value you used for
`DB_APP_PASSWORD`. `bin/deploy-local.sh` refuses to run while the password is the
literal `CHANGEME`, and renders `ROOT.xml` from it. There is no manual `ROOT.xml`
edit step.

```bash
cp .env.example .env
$EDITOR .env
```

### 4. Deploy

```bash
bin/deploy-local.sh
```

The script:

1. Checks that `npm` is on `PATH` and that `wikantik-war/target/Wikantik.war` exists.
2. Sources `.env` and refuses to continue if `POSTGRES_PASSWORD` is unset or `CHANGEME`.
3. Downloads Tomcat 11.0.26 if `tomcat/tomcat-11/` is missing. Pass `--upgrade-tomcat`
   for an in-place upgrade that preserves managed configs and data.
4. Downloads the PostgreSQL JDBC driver if it is missing.
5. Renders the templates described under
   [Configuration files](#configuration-files) into the Tomcat tree.
6. Stops Tomcat if it is running, rotates `catalina.out`, and replaces `webapps/ROOT/`
   with the freshly built WAR.
7. Runs `bin/db/migrate.sh` against the database named in the rendered `ROOT.xml`.
8. Seeds the default admin through `bin/db/seed-users.sql` (insert-if-absent, so an
   existing operator account is never overwritten), then `bin/db/seed-users.local.sql`
   if that gitignored file exists.
9. Starts Tomcat.

Templated config is **write-once**: each file is rendered on the first deploy and never
overwritten afterwards (`server.xml` and `context.xml` are replaced only while they are
still stock). Your edits survive redeploys, but so does everything else: changes to the
git-tracked templates, including the performance knobs below, do not reach an existing
install. Edit the deployed file by hand, or delete it and re-run `bin/deploy-local.sh`.

### 5. Verify

```bash
tail -f tomcat/tomcat-11/logs/catalina.out
```

1. Open <http://localhost:8080/>; the React SPA loads.
2. Log in as `admin` / `admin123` and choose a new password when prompted.
3. Open <http://localhost:8080/page-graph> and <http://localhost:8080/knowledge-graph>.
4. Check database connectivity from the shell:

   ```bash
   psql -h localhost -U wikantik -d wikantik -c "SELECT login_name FROM users;"
   ```

5. Check `/api/health` (it reports engine, database and search index; the endpoint
   answers only to loopback and private-network clients):

   ```bash
   curl -s http://localhost:8080/api/health | jq
   ```

## Redeploy after a code change

```bash
mvn clean install -DskipTests -T 1C
bin/redeploy.sh
```

`bin/redeploy.sh` swaps the WAR and runs pending migrations. Use `bin/deploy-local.sh`
instead when you need secrets re-validated against `.env`, a Tomcat upgrade
(`--upgrade-tomcat`), or regenerated config files.

## How the application connects

Every subsystem shares one JNDI `DataSource`, `jdbc/WikiDatabase`: user profiles and
password hashes, groups, policy grants, the Knowledge Graph, the Page Graph structural
index, API keys, the audit log and the rest.

### Properties

These are the defaults in `ini/wikantik.properties`; override them in
`wikantik-custom.properties` only if you are changing the implementation.

```properties
wikantik.userdatabase  = com.wikantik.auth.user.JDBCUserDatabase
wikantik.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase
wikantik.datasource    = jdbc/WikiDatabase
```

`AbstractJDBCDatabase` reads `wikantik.datasource` at initialization, for both the user
and the group database. Policy grants are active whenever `wikantik.datasource` is
configured, which it is in a standard deployment; the file-based `WEB-INF/wikantik.policy`
is only a fallback for installs with no DataSource.

### The `ROOT.xml` DataSource

`bin/deploy-local.sh` renders `Wikantik-context.xml.template` into
`tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`, substituting the `POSTGRES_*`
values from `.env`:

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

If you supply your own Tomcat and context file, the same shape applies. Put
`postgresql.jar` in `${CATALINA_HOME}/lib/` and `wikantik-custom.properties` in the
same `lib/` directory (on the container classpath, not inside `WEB-INF/`).

On startup the log confirms the wiring:

```bash
grep -E "JDBC(User|Group)Database" tomcat/tomcat-11/logs/catalina.out
# JDBCUserDatabase initialized from JNDI DataSource: jdbc/WikiDatabase
# JDBCUserDatabase supports transactions. Good; we will use them.
# JDBCGroupDatabase initialized from JNDI DataSource: jdbc/WikiDatabase
# JDBCGroupDatabase supports transactions. Good; we will use them.
```

## Configuration files

### Git-tracked templates

In `wikantik-war/src/main/config/tomcat/`:

| File | Purpose |
|------|---------|
| `Wikantik-context.xml.template` | JNDI DataSource for PostgreSQL (rendered to `conf/Catalina/localhost/ROOT.xml`) |
| `wikantik-custom-postgresql.properties.template` | Wikantik runtime settings (rendered to `lib/wikantik-custom.properties`) |
| `wikantik-mcp.properties.template` | MCP server config (rendered to `lib/wikantik-mcp.properties`) |
| `Tomcat-context.xml.template` | Adds `<CookieProcessor sameSiteCookies="lax"/>` to `conf/context.xml`. `lax` is required for SSO: a `strict` cookie is withheld on the IdP's cross-site redirect back to `/sso/callback`, which looks like random logouts. |
| `Tomcat-server.xml.template` | Adds the Cloudflare `RemoteIpValve` and the access-log valve to `conf/server.xml` |
| `setenv.sh.template` | Tomcat launch environment: enables the JDK incubator Vector API for Lucene, and documents `WIKANTIK_MAX_INFLIGHT_REQUESTS` |
| `log4j2-local.xml.template` | Local logging config (rendered to `lib/log4j2.xml`) |

### Local files (gitignored)

In `tomcat/tomcat-11/`:

| File | Purpose |
|------|---------|
| `lib/postgresql.jar` | PostgreSQL JDBC driver |
| `lib/wikantik-custom.properties` | Your Wikantik settings; edit here, not in the template |
| `lib/wikantik-mcp.properties` | MCP server config; edit directly after the first deploy |
| `lib/log4j2.xml` | Effective log config |
| `conf/Catalina/localhost/ROOT.xml` | JNDI context holding the real DB password |
| `bin/setenv.sh` | Tomcat launch environment (Vector API flags, backpressure cap) |

For automated or manual API testing, the repo expects a gitignored `test.properties`
with a `testbot` admin account; the format and the commands to recreate the user after a
database reset are in the "Manual Testing Credentials" section of `CLAUDE.md`.

## Tune performance and concurrency

The knobs and the reasoning behind each value are documented once, in
[WikantikOperations.md](WikantikOperations.md#15-performance--concurrency-tuning). On
bare metal they come from the templates above and land in these files:

| Knob | Deployed file | Template |
|------|---------------|----------|
| Tomcat `maxThreads=400` / `acceptCount=200` | `conf/server.xml` | `Tomcat-server.xml.template` |
| DBCP `maxTotal=90` / `maxWaitMillis=5000` / `maxIdle=30` | `conf/Catalina/localhost/ROOT.xml` | `Wikantik-context.xml.template` |
| Backpressure cap `WIKANTIK_MAX_INFLIGHT_REQUESTS` (default 390) | `bin/setenv.sh` | `setenv.sh.template` |
| Dense backend and HNSW (`wikantik.search.dense.*`) | `lib/wikantik-custom.properties` | `wikantik-custom-postgresql.properties.template` |

Keep the backpressure cap below `maxThreads`: with stock Tomcat (`maxThreads=200`) a cap
of 390 can never fire. `BackpressureFilter` reads the cap from a system property or
environment variable only; there is no `wikantik.*` property for it. Because templated
config is write-once, apply an updated knob by editing the deployed file and restarting
Tomcat. Then confirm the cap is live:

```bash
curl -s http://localhost:8080/metrics | grep backpressure_permits_max
# wikantik_backpressure_permits_max 390.0
```

## Schema: users, roles, groups and policy grants

All tables are created and kept current by the numbered migrations in
`bin/db/migrations/`. Never create them by hand: the `schema_migrations` ledger would
fall out of step and the next migration could fail. The legacy `postgresql*.ddl`
snapshots are retired; the migrations are the only schema definition.

### `users`

Source: `V002__core_users_groups.sql`, plus `V039__password_must_change.sql` and
`V042__user_last_login.sql`.

| Column | Notes |
|--------|-------|
| `login_name` | Primary key (`VARCHAR(100)`). `uid` is a legacy opaque identifier, not the key. |
| `uid`, `email`, `full_name` | Profile fields |
| `password` | Password hash (see [Password hashing](#password-hashing)) |
| `wiki_name` | Display-style wiki name (for example `Administrator`); distinct from `login_name` |
| `created`, `modified` | `TIMESTAMP` |
| `lock_expiry` | `NULL` when unlocked; a future timestamp locks the account until then |
| `bio` | `VARCHAR(1000)` |
| `attributes` | Base64-serialized map of custom profile attributes (`com.wikantik.util.Serializer`) |
| `password_must_change` | `BOOLEAN NOT NULL DEFAULT FALSE`; forces a password change at next login (set when an admin resets a password or seeds the default admin) |
| `last_login` | Stamped by the application on every `LOGIN_AUTHENTICATED` event |

SSO users are auto-provisioned with a `NULL` password.

### `roles`

`roles(login_name, role)`, one row per login and role (for example `admin` / `Admin`).
There is no foreign key to `users`; the application enforces integrity (deleting a user
deletes its role rows). When a user is created, `JDBCUserDatabase` inserts an
`Authenticated` role row.

### `groups` and `group_members`

`groups(name PRIMARY KEY, creator, created, modifier, modified)` and
`group_members(name, member)` with a composite primary key `(name, member)`. There are
no numeric ids or foreign keys. `member` holds the `WikiPrincipal` name.
`JDBCGroupDatabase.save()` deletes every member row for the group and re-inserts the
current set, so there is no single-row add or remove path. The built-in `Admin` group is
seeded by V002; it cannot be deleted and cannot be saved with zero members.

### `policy_grants`

Source: `V003__policy_grants.sql`. Columns: `id SERIAL`, `principal_type`,
`principal_name`, `permission_type`, `target`, `actions` (all `NOT NULL`), with a
`UNIQUE (principal_type, principal_name, permission_type, target)` constraint that
makes the seed `ON CONFLICT DO NOTHING` safe. The default grants:

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

Migration V060 later adds the `export` wiki permission to the `Authenticated` row, but
only while that row still holds the stock actions, so a customised row is never changed.

## Manage users, groups and policy

- **`/admin/security`**: view and edit role-based policy grants and group memberships.
- **`/admin/users`**: manage accounts, lock and unlock them.
- **SCIM**: the SCIM 2.0 server at `/scim/v2/*` handles IdP-driven user and group
  lifecycle; see [ScimProvisioning.md](ScimProvisioning.md). SCIM groups never grant
  the Admin role.
- **Break-glass SQL**: to create a user directly (for example to recreate `testbot`
  after a database reset), hash the password first and insert; the commands are in
  `CLAUDE.md` under "Manual Testing Credentials".

`/admin/*` authorization requires membership in the `Admin` group, not just a row in
`roles`: an account with only a `roles` row authenticates but gets 403 on admin pages.

### Bootstrap admin override

`wikantik.admin.bootstrap` names a login that bypasses all policy checks while it is
set. The override is time-boxed: `wikantik.admin.bootstrap.maxAgeSeconds` (blank means
86400 seconds, 24 hours) caps how long it is honored after startup. Use it only to
recover access, and remove the property once a real admin account works.

### Password policy

`PasswordValidator` follows NIST 800-63B: length plus a bundled common-password
blocklist, with no arbitrary complexity rules. The knobs are
`wikantik.password.minLength` (default `8`), `wikantik.password.maxLength` (default
`128`) and `wikantik.password.blocklist.enabled` (default `true`).

## Password hashing

New and re-hashed passwords use bcrypt (`{bcrypt}`). Legacy salted SHA-256
(`{SHA-256}`) hashes remain verifiable and are re-hashed to bcrypt on the owner's next
successful login. Salted SHA-1 (`{SSHA}`) is no longer supported: a stored `{SSHA}`
value never verifies.

`CryptoUtil --hash` emits a `{SHA-256}` hash, which login accepts and upgrades. There is
no flag to emit bcrypt directly.

```bash
java -cp wikantik-util/target/wikantik-util-*.jar \
    com.wikantik.util.CryptoUtil --hash "mypassword"
```

To set a password by SQL, use that output and let the next login upgrade it:

```sql
UPDATE users
   SET password = '{SHA-256}your_generated_hash_here',
       modified = CURRENT_TIMESTAMP
 WHERE login_name = 'username';
```

To reset a forgotten local `admin` password to `admin123`, use the `UPDATE` documented
in the header of `bin/db/seed-users.sql`.

## Harden a production database

For a PostgreSQL server you run yourself in production (the container stack has its own
posture; see [DockerDeployment.md](DockerDeployment.md)):

```sql
ALTER USER wikantik WITH PASSWORD 'a-strong-password-of-at-least-32-characters';
REVOKE ALL ON DATABASE wikantik FROM PUBLIC;
GRANT CONNECT ON DATABASE wikantik TO wikantik;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO wikantik;
```

Restrict `pg_hba.conf` to the application server and require TLS:

```
hostssl wikantik   wikantik   10.0.0.5/32    scram-sha-256
```

```xml
url="jdbc:postgresql://db-server:5432/wikantik?ssl=true&amp;sslmode=verify-full&amp;sslrootcert=/path/to/ca.crt"
```

Run schema changes as a dedicated role, not the application role; see
[DatabaseMigrations.md](DatabaseMigrations.md). For backups and restore, see
[BackupAndRecovery.md](BackupAndRecovery.md).

### Monitoring queries

```sql
-- Connection state
SELECT state, count(*) FROM pg_stat_activity WHERE datname = 'wikantik' GROUP BY state;

-- Largest tables
SELECT relname AS table_name,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size
  FROM pg_catalog.pg_statio_user_tables
 WHERE schemaname = 'public'
 ORDER BY pg_total_relation_size(relid) DESC;

-- Queries running longer than 5 seconds
SELECT pid, now() - query_start AS duration, query, state
  FROM pg_stat_activity
 WHERE datname = 'wikantik' AND state != 'idle'
   AND now() - query_start > interval '5 seconds';
```

Metrics scraping uses the `wikantik_exporter` role created by migration V031; see
[DatabaseMigrations.md](DatabaseMigrations.md#roles).

## Troubleshoot

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Cannot create JDBC driver` or `No suitable driver found` | `tomcat/tomcat-11/lib/postgresql.jar` is missing | Re-run `bin/deploy-local.sh`, then restart Tomcat |
| `javax.naming.NameNotFoundException: Name [jdbc/WikiDatabase] is not bound` | `ROOT.xml` is missing from `conf/Catalina/localhost/`, or the resource name differs | Re-run `bin/deploy-local.sh`; restart Tomcat fully |
| `password authentication failed for user "wikantik"` | Wrong DB password in `ROOT.xml`, or `pg_hba.conf` rejects the connection | Edit `ROOT.xml` and restart Tomcat; check `pg_hba.conf` and `\du wikantik` |
| `Connection refused` | PostgreSQL is not running or not listening | `systemctl status postgresql`; `ss -tlnp \| grep 5432`; check `listen_addresses` |
| `extension "vector" is not available` during install | pgvector package not installed | See [Install pgvector](#install-pgvector) |
| Login fails with the correct password | Wrong hash format in `users` | Recreate the user with `CryptoUtil --hash` (see `CLAUDE.md`) |
| WAR file not found | The build has not run | `mvn clean install -DskipTests -T 1C` |
| `permission denied to create role` on V031, `must have admin option on role "pg_monitor"`, or `permission denied for table schema_migrations` | The role running `migrate.sh` is under-privileged | See [DatabaseMigrations.md](DatabaseMigrations.md#troubleshoot) |
| `slug 'X' is already claimed by canonical_id …` (WARN, repeated at boot) | `page_canonical_ids` rows drifted from frontmatter (handled gracefully) | Delete the stale rows so the rebuild re-inserts them; see `bin/db/one-shots/reconcile_page_canonical_ids.sh` |
| `Match [Context] failed to set property [cachingAllowed]` (Tomcat WARNING) | `cachingAllowed` is invalid on `<Context>` in Tomcat 11 | Remove the attribute from `<Context>` in `ROOT.xml` |
| 404 at `/Wikantik/` | Stale URL | Wikantik serves at `/` |

### Check logs

```bash
tail -f tomcat/tomcat-11/logs/catalina.out                 # Tomcat
tail -f tomcat/tomcat-11/logs/wikantik/wikantik.log        # application
grep -i "jdbc\|datasource\|jndi" tomcat/tomcat-11/logs/catalina.out
sudo tail -f /var/log/postgresql/postgresql-*-main.log     # PostgreSQL (path varies)
```

To log the JDBC databases at debug level, add to `tomcat/tomcat-11/lib/log4j2.xml`:

```xml
<Logger name="com.wikantik.auth.user.JDBCUserDatabase"       level="DEBUG"/>
<Logger name="com.wikantik.auth.authorize.JDBCGroupDatabase" level="DEBUG"/>
```

### Reset local Tomcat configuration

```bash
rm tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
rm tomcat/tomcat-11/lib/wikantik-custom.properties
bin/deploy-local.sh   # re-renders both from .env and the templates
```

### Reset the database

There is no destructive reset command. Drop and recreate the database, then bootstrap it
again:

```bash
sudo -u postgres psql -c 'DROP DATABASE wikantik;'
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role
```

## Relationship to the test suite

This deployment has no effect on the build or the tests. Unit tests that touch a
database run against a per-JVM pgvector container (`PostgresTestDb`, via Docker) with
every migration applied, for example:

```bash
mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest
mvn test -pl wikantik-main -Dtest=JDBCGroupDatabaseTest
```

Integration tests start their own per-module pgvector container; see
[`wikantik-it-tests/README.md`](../../wikantik-it-tests/README.md). `bin/deploy-local.sh`
is operator-only and is never invoked from Maven.

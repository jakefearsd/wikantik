# Getting Started with Wikantik

This guide walks a first-time deployer from a fresh clone to a running wiki you
can log into. It covers two paths:

- **[Path A — Docker Compose](#path-a--docker-compose-fastest)** — the fastest way
  to a running instance. One config file, one command. Best for evaluating
  Wikantik or running it as an appliance.
- **[Path B — Bare-metal Tomcat](#path-b--bare-metal-tomcat)** — runs the WAR in a
  local Tomcat against your own PostgreSQL. Best for development, debugging, and
  customization.

Both paths end at the same place: a wiki at **http://localhost:8080/** where you
log in as **`admin` / `admin123`** and are immediately required to choose a new
password. See [First login](#first-login-set-your-admin-password).

> **Which should I pick?** If you just want to see Wikantik running, use Path A —
> it needs only Docker. If you intend to change code, use Path B.

---

## Before you start

Clone the repository and `cd` into it:

```bash
git clone <your-fork-or-mirror-url> wikantik
cd wikantik
```

### Prerequisites

| Path | You need |
|------|----------|
| **A — Docker** | Docker Engine 24+ with the Compose plugin (`docker compose version`). Nothing else. |
| **B — Bare metal** | Java JDK **21+**, Maven **3.9+**, Node.js **18+** + npm, PostgreSQL **15+**. |

Why Node for a Java app? The WAR build compiles the React frontend with Vite;
`mvn` runs `npm install` + `vite build` automatically, so npm must be on your
`PATH`. Verify the bare-metal toolchain in one shot:

```bash
java -version && mvn -version && node --version && npm --version && psql --version
```

---

## Path A — Docker Compose (fastest)

The stack is two containers: PostgreSQL (with the `pgvector` extension, required
by search and the knowledge graph) and the Wikantik app. Compose builds the app
image from the `Dockerfile`, runs database migrations on first start, seeds the
admin account, and starts Tomcat.

### 1. Create your `.env`

The compose files read a gitignored `.env`. Copy the template and set a real
database password:

```bash
cp .env.example .env
```

Edit `.env` and change **one** required value:

```ini
POSTGRES_PASSWORD=CHANGEME   # ← set this to a real password
```

Everything else has a working default (`POSTGRES_DB=wikantik`,
`POSTGRES_USER=wikantik`, `WIKANTIK_BASE_URL=http://localhost:8080/`). Leave them
unless you have a reason to change them.

> **Heads-up:** the same `.env` drives both deployment paths. The app connects to
> PostgreSQL as `POSTGRES_USER` / `POSTGRES_PASSWORD`, so whatever you set here is
> what the database is created with — keep them consistent.

### 2. Build and start

```bash
docker compose up -d --build
```

This builds the image (first time: several minutes — it compiles Java + the React
frontend), starts PostgreSQL, waits for it to be healthy, runs migrations, seeds
the admin account, and starts the app. Watch progress with:

```bash
docker compose logs -f wikantik
```

On a fresh database the startup log ends with a first-login banner:

```
============================================================
 Wikantik first start: log in at http://localhost:8080/
   Username: admin
   Password: admin123
 You will be required to choose a new password on first login.
============================================================
```

### 3. Verify and log in

```bash
curl -fsS http://localhost:8080/api/health    # → 200 with a JSON health body
```

Open **http://localhost:8080/** and continue to
[First login](#first-login-set-your-admin-password).

### Managing the stack

`bin/container.sh` wraps `docker compose` for the canonical service set:

```bash
bin/container.sh ps                 # container status
bin/container.sh logs -f            # follow app logs
bin/container.sh down               # stop (keeps data volumes)
bin/container.sh down --volumes     # stop AND delete the database — full reset
```

Running alongside a bare-metal Tomcat already on 8080? Set
`WIKANTIK_HOST_PORT=18080` in `.env` and the container publishes there instead.

For production container deployment (the backup sidecar, remote hosts, reverse
proxy, TLS), see **[DockerDeployment.md](DockerDeployment.md)**.

---

## Path B — Bare-metal Tomcat

This path creates a PostgreSQL database, builds the WAR, and deploys it into a
local Tomcat 11 that `deploy-local.sh` downloads and manages for you under
`tomcat/` (gitignored).

### 1. Create the database

`bin/db/install-fresh.sh` creates the database, the application role, installs
`pgvector`, and runs all migrations (which seed the `admin` account). Run it as a
PostgreSQL superuser. **Choose an application password and remember it — you will
put the same value in `.env` in step 3.**

```bash
sudo -u postgres \
  DB_NAME=wikantik \
  DB_APP_USER=wikantik \
  DB_APP_PASSWORD='choose-a-real-password' \
  bin/db/install-fresh.sh --no-migrate-role
```

Notes:
- **`DB_APP_USER=wikantik`** matches the `POSTGRES_USER` default in `.env.example`.
  Keep these the same or the app cannot connect.
- **`--no-migrate-role`** runs migrations as the superuser and is the simplest
  choice for local development. For a production-grade least-privilege setup where
  a dedicated `migrate` role owns the schema, omit the flag and pass
  `DB_MIGRATE_PASSWORD='…'` instead. The script **fails fast** if you provide
  neither — that is intentional, so you make the ownership decision up front rather
  than hitting a "must be owner of table" error on a later migration.
- The script is idempotent — safe to re-run.

### 2. Build the WAR

```bash
mvn clean install -DskipTests -T 1C
```

This builds every module and the React frontend (via npm). Use `-DskipTests` for
the build itself; do **not** use `-Dmaven.test.skip` (that omits a test-jar other
modules need and breaks the reactor).

### 3. Configure and deploy

`bin/deploy-local.sh` renders the Tomcat config (`ROOT.xml`,
`wikantik-custom.properties`) from `.env`, downloads Tomcat on first run, deploys
the WAR, applies migrations, seeds the admin account, and starts Tomcat.

On the **very first run** with no `.env`, it copies `.env.example` → `.env` and
stops so you can set the password:

```bash
bin/deploy-local.sh
# → "POSTGRES_PASSWORD is unset or still the .env.example placeholder."
```

Edit `.env` and set `POSTGRES_PASSWORD` to **the same value you used for
`DB_APP_PASSWORD` in step 1**, and confirm `POSTGRES_USER=wikantik`. Then re-run:

```bash
bin/deploy-local.sh
```

It finishes by starting Tomcat and printing a first-login banner:

```
===========================================
 Wikantik is starting at http://localhost:8080/

   First login:  admin / admin123
   You will be required to choose a new password on first login.
===========================================
```

### 4. Verify and log in

```bash
curl -fsS http://localhost:8080/api/health
```

Open **http://localhost:8080/** and continue to
[First login](#first-login-set-your-admin-password).

### Iterating

After the first setup, the fast redeploy loop is:

```bash
mvn clean install -DskipTests -T 1C
bin/redeploy.sh        # shutdown → swap WAR → startup (no template/migration re-run)
```

Use `bin/deploy-local.sh` again only when you change `.env`, upgrade Tomcat, or
need migrations/templates re-applied. Tomcat lifecycle:

```bash
tomcat/tomcat-11/bin/shutdown.sh
tomcat/tomcat-11/bin/startup.sh
tail -f tomcat/tomcat-11/logs/catalina.out
```

For the deeper reference (config file locations, JNDI, manual migration commands),
see **[PostgreSQLLocalDeployment.md](PostgreSQLLocalDeployment.md)**.

---

## First login: set your admin password

A fresh install seeds exactly one account — **`admin` / `admin123`** — flagged so
that the first login **requires** choosing a new password before you can do
anything else. This is by design: no Wikantik install ever runs with a known
default credential past first contact.

1. Open **http://localhost:8080/** and log in with `admin` / `admin123`.
2. You are immediately routed to a **Set a new password** screen. Until you
   complete it, the API rejects every other request with
   `403 PASSWORD_CHANGE_REQUIRED` — this is expected, not a bug.
3. Enter `admin123` as the current password and choose a new one. Password rules
   follow NIST 800-63B (length-based, with a common-password blocklist), so
   `admin123` itself is rejected.
4. After the change you land on the wiki. That password is now yours; redeploys
   and restarts never reset it.

The same forced-change flow applies whenever an administrator sets or resets a
user's password, and when the "forgot password" email issues a temporary one.

> **Forgot the local admin password during development?** Reset it back to the
> seeded default with the one-liner documented at the top of
> `bin/db/seed-users.sql`, then log in and change it again.

---

## Common first-build pitfalls

| Symptom | Cause & fix |
|---------|-------------|
| `install-fresh.sh` exits: *"DB_MIGRATE_PASSWORD is not set"* | You passed neither `--no-migrate-role` nor `DB_MIGRATE_PASSWORD`. For local dev, add `--no-migrate-role`. |
| App starts but can't connect to the database / login fails for everyone | `POSTGRES_USER`/`POSTGRES_PASSWORD` in `.env` don't match the `DB_APP_USER`/`DB_APP_PASSWORD` you created the database with. Make them identical. |
| `deploy-local.sh` exits: *"POSTGRES_PASSWORD is unset or still the .env.example placeholder"* | First run created `.env`; edit it, set a real `POSTGRES_PASSWORD`, re-run. This guard is what stops a config from shipping with a known default. |
| `npm not found` during the Maven build | Node.js 18+/npm aren't installed or not on `PATH`. The WAR build needs them for the React frontend. |
| Port 8080 already in use | Container: set `WIKANTIK_HOST_PORT=18080` in `.env`. Bare metal: stop the other service, or change the Tomcat connector port. |
| `403 PASSWORD_CHANGE_REQUIRED` on every API call after first login | Working as designed — finish the forced password change (see [First login](#first-login-set-your-admin-password)). |
| Container won't start: pgvector / `vector` extension errors | The DB image must be `pgvector/pgvector` (the base compose pins it). Don't substitute a plain `postgres` image. |
| Login works, then random logouts | In production behind a proxy, set `wikantik.cookieAuthentication=true` (remember-me) and ensure cookies aren't `SameSite=Strict`. Not an issue for plain localhost. |

---

## Next steps

Now that you're running:

- **Add users and groups** — admin panel at `/admin` (Users, Security).
- **Single Sign-On (Google/OIDC/SAML)** — [SingleSignOn.md](SingleSignOn.md).
- **Backups & disaster recovery** — [BackupAndRecovery.md](BackupAndRecovery.md).
- **Production database workflow & migrations** — [DatabaseUpdates.md](DatabaseUpdates.md),
  [ProductionDBWorkflow.md](ProductionDBWorkflow.md).
- **Agent/AI surfaces** (MCP servers, REST API, SCIM) — the architecture map and
  endpoint table in [ProjectReference.md](ProjectReference.md) and the repo
  `CLAUDE.md`.
- **Sending email** (password resets, notifications) — [SendingEmailFromTheWiki.md](SendingEmailFromTheWiki.md).

Welcome to Wikantik.

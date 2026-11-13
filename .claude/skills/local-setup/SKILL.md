---
name: local-setup
description: Use when setting up Wikantik on a fresh clone (database, WAR build, first Tomcat deploy) or applying database migrations by hand, e.g. against production
---

# Local setup and manual migrations

Moved from the root CLAUDE.md. Routine redeploys (`bin/redeploy.sh`) and the schema-change rule stay there.

## First-time setup (fresh clone)

```bash
# 1. Create the database, application role, and full schema in one step
#    (idempotent — safe to re-run). DB_APP_USER/DB_APP_PASSWORD must match
#    POSTGRES_USER/POSTGRES_PASSWORD in .env (step 4) or the app can't connect.
#    --no-migrate-role runs migrations as the superuser (simplest for local dev);
#    omit it and pass DB_MIGRATE_PASSWORD='…' for a least-privilege migrate role.
#    The script fails fast if you provide neither.
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_APP_PASSWORD='choose-a-real-password' \
    bin/db/install-fresh.sh --no-migrate-role

# 2. Build the WAR (also builds the React frontend via npm automatically)
mvn clean install -DskipTests -T 1C

# 3. Bootstrap — downloads Tomcat if absent, copies and patches config templates,
#    removes stale files, deploys the WAR. deploy-local.sh also runs migrate.sh
#    so any pending migrations get applied on every redeploy.
bin/deploy-local.sh

# 4. deploy-local.sh renders ROOT.xml and wikantik-custom.properties from .env —
#    on the very first run it copies .env.example to .env and exits; edit
#    POSTGRES_PASSWORD in .env and re-run bin/deploy-local.sh

# 5. Start Tomcat
tomcat/tomcat-11/bin/startup.sh
# Access at http://localhost:8080/ — first login: admin / admin123 (a new password is required on first login)
# React SPA at http://localhost:8080/
```

## Applying database migrations manually

`deploy-local.sh` runs `migrate.sh` automatically on every deploy. To run
migrations by hand (e.g. against production):

```bash
DB_NAME=wikantik DB_APP_USER=wikantik PGHOST=db.example.com PGUSER=postgres \
    PGPASSWORD='…' bin/db/migrate.sh

# Check what has been applied
bin/db/migrate.sh --status
```


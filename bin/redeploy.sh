#!/usr/bin/env bash
#
# bin/redeploy.sh — fast routine redeploy of the bare-metal Tomcat install.
#
# Use this for "edit code, see it running" iteration. It does:
#   1. shuts down Tomcat (if running) and waits for port 8080 to release
#   2. rotates catalina.out so the next run's log starts clean
#   3. swaps the deployed WAR
#   4. runs bin/db/migrate.sh against the database named in ROOT.xml
#      (idempotent — only applies pending V*.sql; sources .env and runs as
#      the app role POSTGRES_USER, falling back to postgres — mirrors
#      deploy-local.sh's flow; there is no separate `migrate` role locally)
#   5. starts Tomcat
#
# Skipped (handled by deploy-local.sh): template materialisation, Tomcat
# upgrade snapshot logic, secrets validation, dev-user seeding.
#
# Builds are NOT run — assume the operator has already produced
# wikantik-war/target/Wikantik.war via `mvn ... package`. That separation is
# deliberate: a typo at the build step shouldn't leave Tomcat half-stopped.
#
# For first-time setup, Tomcat upgrades, or anything that touches templates,
# secrets, or seed users, run bin/deploy-local.sh instead.

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOMCAT_DIR="${REPO_ROOT}/tomcat/tomcat-11"
WAR_SOURCE="${REPO_ROOT}/wikantik-war/target/Wikantik.war"
CONTEXT_XML="${TOMCAT_DIR}/conf/Catalina/localhost/ROOT.xml"

if [[ ! -d "${TOMCAT_DIR}" ]]; then
    echo "ERROR: ${TOMCAT_DIR} does not exist." >&2
    echo "       Run bin/deploy-local.sh for first-time setup." >&2
    exit 1
fi

if [[ ! -f "${WAR_SOURCE}" ]]; then
    echo "ERROR: ${WAR_SOURCE} not found." >&2
    echo "       Run: mvn clean install -DskipTests -T 1C" >&2
    exit 1
fi

# The WAR is Java 25 bytecode (class-file v69); Tomcat must launch on a JDK 25+
# runtime or every class fails with UnsupportedClassVersionError. startup.sh uses
# $JAVA_HOME/bin/java when set, else the java on PATH — check that same one.
JAVA_BIN="java"
[[ -n "${JAVA_HOME:-}" ]] && JAVA_BIN="${JAVA_HOME}/bin/java"
if ! command -v "${JAVA_BIN}" &>/dev/null; then
    echo "ERROR: java not found (${JAVA_BIN}). A JDK 25+ runtime is required." >&2
    exit 1
fi
JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)"
if [[ -z "${JAVA_MAJOR}" || "${JAVA_MAJOR}" -lt 25 ]]; then
    echo "ERROR: JDK ${JAVA_MAJOR:-unknown} will run Tomcat, but the WAR is Java 25 bytecode." >&2
    echo "       Point JAVA_HOME at a JDK 25+ (e.g. 'sdk use java 25.0.3-tem')." >&2
    exit 1
fi

# Shutdown
if [[ -f "${TOMCAT_DIR}/bin/shutdown.sh" ]]; then
    echo "Stopping Tomcat..."
    "${TOMCAT_DIR}/bin/shutdown.sh" 2>/dev/null || true

    # Wait for port 8080 to actually release. shutdown.sh returns immediately
    # but the JVM holds the listener for several seconds; starting a new
    # instance into a still-bound port leaves Tomcat running but unable to
    # serve requests (silent failure mode that bit us in real life).
    echo -n "Waiting for port 8080 to release"
    for _ in $(seq 1 30); do
        if ! ss -tlnp 2>/dev/null | grep -q ':8080 '; then
            echo " released."
            break
        fi
        echo -n "."
        sleep 1
    done
    if ss -tlnp 2>/dev/null | grep -q ':8080 '; then
        echo
        echo "ERROR: port 8080 still bound after 30s — old Tomcat process didn't exit." >&2
        echo "       Identify and kill it manually:" >&2
        echo "         pgrep -af 'org.apache.catalina.startup.Bootstrap'" >&2
        exit 1
    fi
fi

# Rotate catalina.out — bypass aliases / -i prompts.
CATALINA_OUT="${TOMCAT_DIR}/logs/catalina.out"
if [[ -f "${CATALINA_OUT}" ]]; then
    \rm -f "${CATALINA_OUT}.old"
    \mv -f "${CATALINA_OUT}" "${CATALINA_OUT}.old"
    echo "Rotated catalina.out → catalina.out.old"
fi

# Swap WAR
if [[ -d "${TOMCAT_DIR}/webapps/ROOT" ]]; then
    rm -rf "${TOMCAT_DIR}/webapps/ROOT"
fi
cp "${WAR_SOURCE}" "${TOMCAT_DIR}/webapps/ROOT.war"
echo "WAR redeployed."

# Apply pending migrations. Idempotent — no-op when there are none. We
# discover the database name from the rendered ROOT.xml so we never drift
# from whatever .env the operator deployed against, and run migrate.sh as
# the app role (POSTGRES_USER from .env, default wikantik) exactly like
# deploy-local.sh — the local setup has no separate `migrate` role.
MIGRATE_SH="${REPO_ROOT}/bin/db/migrate.sh"
if [[ -f "${MIGRATE_SH}" && -f "${CONTEXT_XML}" ]]; then
    # Source .env for DB credentials (gitignored; the same file deploy-local.sh reads).
    ENV_FILE="${REPO_ROOT}/.env"
    if [[ -f "${ENV_FILE}" ]]; then
        set -a; source "${ENV_FILE}"; set +a
    fi
    POSTGRES_USER="${POSTGRES_USER:-wikantik}"
    POSTGRES_HOST="${POSTGRES_HOST:-localhost}"

    WIKI_DB=$(grep -oE 'jdbc:postgresql://[^/]+/[^"?]+' "${CONTEXT_XML}" \
                | head -1 | sed 's|.*postgresql://[^/]*/||')
    WIKI_DB="${WIKI_DB:-wikantik}"
    echo "Applying any pending migrations to ${WIKI_DB} as ${POSTGRES_USER}..."
    # Prefer the app role (it owns the tables); fall back to the postgres
    # superuser for the rare migration that needs it. Only a genuine failure
    # of both aborts the redeploy.
    if DB_NAME="${WIKI_DB}" PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD:-}" \
           PGHOST="${POSTGRES_HOST}" "${MIGRATE_SH}"; then
        :
    elif DB_NAME="${WIKI_DB}" PGUSER=postgres "${MIGRATE_SH}"; then
        echo "Migrations applied as postgres (app-role attempt failed)."
    else
        echo "ERROR: migrate.sh failed — Tomcat NOT started. Investigate the migration error then re-run." >&2
        exit 1
    fi
fi

# Startup
"${TOMCAT_DIR}/bin/startup.sh"
echo "Tomcat starting. Tail logs with:"
echo "  tail -f ${CATALINA_OUT}"

#!/bin/bash
#
# deploy-local.sh - Deploy Wikantik to local Tomcat 11 with PostgreSQL configuration
#
# Prerequisites:
#   1. Run 'mvn clean install' (or 'mvn clean install -DskipTests' — not
#      -Dmaven.test.skip, which omits wikantik-main's test-jar and breaks the
#      full-reactor build) first
#   2. PostgreSQL running with 'wikantik' database created (use bin/db/install-fresh.sh)
#   3. Edit tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml to set your password
#
# Usage:
#   bin/deploy-local.sh                     # Deploy WAR + configure Tomcat
#   bin/deploy-local.sh --upgrade-tomcat    # In-place upgrade Tomcat to ${TOMCAT_VERSION}
#                                           #   (preserves all managed configs + data)
#   bin/deploy-local.sh --help              # Show this help
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TOMCAT_DIR="${PROJECT_ROOT}/tomcat/tomcat-11"
WAR_SOURCE="${PROJECT_ROOT}/wikantik-war/target/Wikantik.war"
CONFIG_DIR="${PROJECT_ROOT}/wikantik-war/src/main/config/tomcat"
SEED_SQL="${SCRIPT_DIR}/db/seed-users.sql"
CONTEXT_DEST="${TOMCAT_DIR}/conf/Catalina/localhost/ROOT.xml"
PROPS_DEST="${TOMCAT_DIR}/lib/wikantik-custom.properties"
MCP_PROPS_DEST="${TOMCAT_DIR}/lib/wikantik-mcp.properties"
LOG4J2_DEST="${TOMCAT_DIR}/lib/log4j2.xml"
SETENV_DEST="${TOMCAT_DIR}/bin/setenv.sh"
TOMCAT_CONTEXT_DEST="${TOMCAT_DIR}/conf/context.xml"
TOMCAT_SERVER_DEST="${TOMCAT_DIR}/conf/server.xml"
LOG_DIR="${TOMCAT_DIR}/logs/wikantik"
JDBC_DRIVER="${TOMCAT_DIR}/lib/postgresql.jar"
JDBC_URL="https://jdbc.postgresql.org/download/postgresql-42.7.4.jar"

# Files preserved across an --upgrade-tomcat run. The list is the contract
# between this script and the operator: any path here survives a Tomcat
# version bump verbatim. Anything else gets replaced from the freshly
# unpacked tarball (and, where we have a template, from the template).
PRESERVED_PATHS=(
    "lib/wikantik-custom.properties"      # DB pool, page provider, custom props
    "lib/wikantik-mcp.properties"         # MCP rate limits, endpoints
    "lib/log4j2.xml"                      # logging config
    "lib/postgresql.jar"                  # JDBC driver
    "conf/Catalina/localhost/ROOT.xml"    # context with DB password
    "bin/setenv.sh"                       # JAVA_OPTS additions (Vector API, etc.)
    "data"                                 # jspwiki-files / attachments / workdir
    "logs/wikantik"                        # app logs
)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${GREEN}[OK]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

show_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
    exit 0
}

# Reads the version of an installed Tomcat from its catalina.jar's
# ServerInfo.properties. Returns empty if catalina.jar isn't present
# (fresh checkout — no Tomcat installed yet).
installed_tomcat_version() {
    local cat_jar="${TOMCAT_DIR}/lib/catalina.jar"
    [[ -f "${cat_jar}" ]] || return 0
    unzip -p "${cat_jar}" org/apache/catalina/util/ServerInfo.properties 2>/dev/null \
        | awk -F'=' '/^server\.number=/ { print $2 }' \
        | head -1
}

# Snapshots PRESERVED_PATHS to ${1}. Used by the upgrade flow to capture
# user state before the old Tomcat dir is moved aside. Missing paths are
# silently skipped (e.g. logs/wikantik may not exist on a fresh install).
#
# For directories we copy CONTENTS via cp -aT so re-restore into a
# pre-existing dir of the same name doesn't nest (the
# download_and_extract_tomcat step pre-creates logs/wikantik + data/
# subdirs; cp -a src_dir existing_dst would nest src INTO dst as
# `dst/src_basename`).
snapshot_preserved() {
    local snapshot_dir="$1"
    mkdir -p "${snapshot_dir}"
    for rel in "${PRESERVED_PATHS[@]}"; do
        local src="${TOMCAT_DIR}/${rel}"
        [[ ! -e "${src}" ]] && continue
        local dst="${snapshot_dir}/${rel}"
        mkdir -p "$(dirname "${dst}")"
        if [[ -d "${src}" ]]; then
            mkdir -p "${dst}"
            cp -aT "${src}" "${dst}"
        else
            cp -a "${src}" "${dst}"
        fi
    done
}

# Restores from a snapshot dir into the current ${TOMCAT_DIR}. New Tomcat
# install must already be in place. Files copied with -a so timestamps +
# permissions are preserved. Directory restores use -T to copy CONTENTS,
# avoiding the `logs/wikantik/wikantik` double-nesting failure mode.
restore_preserved() {
    local snapshot_dir="$1"
    for rel in "${PRESERVED_PATHS[@]}"; do
        local src="${snapshot_dir}/${rel}"
        [[ ! -e "${src}" ]] && continue
        local dst="${TOMCAT_DIR}/${rel}"
        if [[ -d "${src}" ]]; then
            mkdir -p "${dst}"
            cp -aT "${src}" "${dst}"
        else
            mkdir -p "$(dirname "${dst}")"
            cp -a "${src}" "${dst}"
        fi
    done
}

# Performs an in-place Tomcat version upgrade. Snapshots user state, moves
# the old install aside, downloads the new version, restores user state,
# and re-applies the conf/ templates. The old Tomcat dir is left at
# ${TOMCAT_DIR}.bak.${old_version}.${ts} for rollback.
do_tomcat_upgrade() {
    local old_version="$1"
    local new_version="$2"
    local ts; ts="$(date +%Y%m%d-%H%M%S)"
    local backup_dir="${TOMCAT_DIR}.bak.${old_version}.${ts}"

    echo ""
    echo "=== Tomcat upgrade: ${old_version} → ${new_version} ==="

    # Stop running Tomcat first so we can move its dir without conflicts
    if [[ -f "${TOMCAT_DIR}/bin/shutdown.sh" ]]; then
        "${TOMCAT_DIR}/bin/shutdown.sh" 2>/dev/null || true
        sleep 2
    fi

    print_status "Stopping any running Tomcat (graceful)"

    # Snapshot inside old TOMCAT_DIR via a sibling staging dir, then move
    # the whole old install out of the way as a backup.
    local stage; stage="$(mktemp -d "${PROJECT_ROOT}/tomcat/.upgrade-stage-XXXXX")"
    snapshot_preserved "${stage}"
    print_status "Snapshotted ${#PRESERVED_PATHS[@]} preserved paths to ${stage}"

    mv "${TOMCAT_DIR}" "${backup_dir}"
    print_status "Old Tomcat ${old_version} moved aside → ${backup_dir}"

    download_and_extract_tomcat "${new_version}"

    # Restore preserved paths from the staging dir, then drop the stage.
    restore_preserved "${stage}"
    rm -rf "${stage}"
    print_status "Restored preserved paths into fresh ${new_version}"

    echo ""
    echo "    Rollback: rm -rf ${TOMCAT_DIR} && mv ${backup_dir} ${TOMCAT_DIR}"
    echo ""
}

# Parse arguments
UPGRADE_MODE=0
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    show_help
fi
if [[ "$1" == "--upgrade-tomcat" ]]; then
    UPGRADE_MODE=1
fi

echo "==========================================="
echo "Wikantik Local Deployment Script"
echo "==========================================="
echo ""

# Check npm prerequisite (needed for React frontend build)
if ! command -v npm &>/dev/null; then
    print_error "npm not found. Node.js (v18+) and npm are required to build the React frontend."
    echo "  Install from https://nodejs.org/ or via your package manager."
    exit 1
fi
print_status "npm found: $(npm --version)"

# Check Java prerequisite. The WAR is compiled to Java 25 bytecode (class-file
# v69), so the JDK that launches Tomcat MUST be 25+ or every class fails to load
# with UnsupportedClassVersionError. Tomcat's startup.sh uses $JAVA_HOME/bin/java
# when JAVA_HOME is set, otherwise the java on PATH — check the same one here.
JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
fi
if ! command -v "${JAVA_BIN}" &>/dev/null; then
    print_error "java not found (${JAVA_BIN}). A JDK 25+ runtime is required — the WAR is Java 25 bytecode."
    echo "  Install JDK 25 (e.g. 'sdk install java 25.0.3-tem') and/or set JAVA_HOME."
    exit 1
fi
JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)"
if [[ -z "${JAVA_MAJOR}" || "${JAVA_MAJOR}" -lt 25 ]]; then
    print_error "JDK ${JAVA_MAJOR:-unknown} will run Tomcat, but the WAR is Java 25 bytecode — it will fail with UnsupportedClassVersionError."
    echo "  Point JAVA_HOME at a JDK 25+ (e.g. 'sdk use java 25.0.3-tem' or export JAVA_HOME)."
    exit 1
fi
print_status "Java runtime: JDK ${JAVA_MAJOR} (${JAVA_BIN})"

# Check if WAR exists
if [[ ! -f "${WAR_SOURCE}" ]]; then
    print_error "WAR file not found: ${WAR_SOURCE}"
    echo "  Please run 'mvn clean install' first"
    exit 1
fi
print_status "WAR file found: ${WAR_SOURCE}"

# Tomcat target version. Bump this and re-run with --upgrade-tomcat to
# pick up a new release. Fresh-clone installs always pin to this version.
TOMCAT_VERSION="11.0.22"

# Downloads + extracts the requested Tomcat version into ${TOMCAT_DIR}.
# Tries the live Apache mirror first; falls back to archive.apache.org for
# versions that have already rotated out of the active mirror. The live
# mirror typically only carries the most recent few releases.
download_and_extract_tomcat() {
    local v="$1"
    local primary="https://downloads.apache.org/tomcat/tomcat-11/v${v}/bin/apache-tomcat-${v}.tar.gz"
    local archive="https://archive.apache.org/dist/tomcat/tomcat-11/v${v}/bin/apache-tomcat-${v}.tar.gz"

    mkdir -p "$(dirname "${TOMCAT_DIR}")"
    local tmp_tar; tmp_tar="$(mktemp /tmp/tomcat-XXXXXX.tar.gz)"

    echo "Downloading Tomcat ${v}..."
    if ! curl -fL -o "${tmp_tar}" "${primary}" 2>/dev/null; then
        echo "  Active mirror miss — falling back to archive.apache.org"
        curl -fL -o "${tmp_tar}" "${archive}"
    fi
    tar -xzf "${tmp_tar}" -C "$(dirname "${TOMCAT_DIR}")"
    mv "$(dirname "${TOMCAT_DIR}")/apache-tomcat-${v}" "${TOMCAT_DIR}"
    rm -f "${tmp_tar}"
    chmod +x "${TOMCAT_DIR}"/bin/*.sh
    mkdir -p "${TOMCAT_DIR}/data/jspwiki-files" \
             "${TOMCAT_DIR}/data/attachments"   \
             "${TOMCAT_DIR}/data/workdir"        \
             "${TOMCAT_DIR}/logs/wikantik"
    print_status "Tomcat ${v} downloaded and unpacked"
}

# Detect installed version and upgrade if requested.
if [[ ! -d "${TOMCAT_DIR}" ]]; then
    echo "Tomcat not found — performing fresh install of ${TOMCAT_VERSION}..."
    download_and_extract_tomcat "${TOMCAT_VERSION}"
else
    INSTALLED_VERSION="$(installed_tomcat_version)"
    if [[ -n "${INSTALLED_VERSION}" && "${INSTALLED_VERSION}" != "${TOMCAT_VERSION}.0" \
          && "${INSTALLED_VERSION}" != "${TOMCAT_VERSION}" ]]; then
        # The .0 suffix in server.number (e.g. 11.0.22.0) is an internal
        # build counter — strip it before display and comparison.
        INSTALLED_DISPLAY="${INSTALLED_VERSION%.0}"
        if [[ "${UPGRADE_MODE}" -eq 1 ]]; then
            do_tomcat_upgrade "${INSTALLED_DISPLAY}" "${TOMCAT_VERSION}"
        else
            print_warning "Installed Tomcat is ${INSTALLED_DISPLAY}; script targets ${TOMCAT_VERSION}."
            echo "         To perform an in-place upgrade preserving all configs + data, run:"
            echo "           bin/deploy-local.sh --upgrade-tomcat"
            echo "         The current ${INSTALLED_DISPLAY} install will continue to be used for this run."
        fi
    fi
fi
print_status "Tomcat directory found: ${TOMCAT_DIR}"

# Download PostgreSQL JDBC driver if not present
if [[ ! -f "${JDBC_DRIVER}" ]]; then
    echo ""
    echo "Downloading PostgreSQL JDBC driver..."
    curl -fL -o "${JDBC_DRIVER}" "${JDBC_URL}"
    print_status "PostgreSQL JDBC driver downloaded"
else
    print_status "PostgreSQL JDBC driver already present"
fi

# Remove stale versioned copies of the JDBC driver (e.g. postgresql-42.7.4.jar).
# Historically the driver was dropped in manually under its versioned name; the
# script now canonically manages ${JDBC_DRIVER}, so any sibling postgresql-*.jar
# is a duplicate that risks classloader ambiguity.
STALE_JDBC=( "${TOMCAT_DIR}"/lib/postgresql-*.jar )
if [[ -e "${STALE_JDBC[0]}" ]]; then
    rm -f "${STALE_JDBC[@]}"
    print_status "Removed stale versioned postgresql-*.jar copies"
fi

# --- Source .env so we can substitute @@POSTGRES_*@@ into the context template ---
# The .env file is gitignored — copy from .env.example on first install and
# edit the password (and any other values you want to override). Same .env
# the container path uses for env_file: directives, so a single edit
# governs both install paths.
ENV_FILE="${PROJECT_ROOT}/.env"
if [[ -f "${ENV_FILE}" ]]; then
    # shellcheck disable=SC1090
    set -a; source "${ENV_FILE}"; set +a
    print_status "Sourced ${ENV_FILE}"
elif [[ -f "${PROJECT_ROOT}/.env.example" ]]; then
    print_warning "${ENV_FILE} missing — copying from .env.example. Edit POSTGRES_PASSWORD before re-running."
    cp "${PROJECT_ROOT}/.env.example" "${ENV_FILE}"
    set -a; source "${ENV_FILE}"; set +a
fi

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-wikantik}"
POSTGRES_USER="${POSTGRES_USER:-wikantik}"

# CHANGEME is the .env.example placeholder. If we still see it after
# sourcing, the operator hasn't picked a real password yet — surface that
# loudly rather than silently materialising a Tomcat config with a known
# default credential.
if [[ -z "${POSTGRES_PASSWORD:-}" || "${POSTGRES_PASSWORD}" == "CHANGEME" ]]; then
    print_error "POSTGRES_PASSWORD is unset or still the .env.example placeholder."
    echo "         Edit ${ENV_FILE} and set POSTGRES_PASSWORD to your real DB password,"
    echo "         then re-run this script. (The same .env is read by the container path.)"
    exit 1
fi

# Create context directory if needed
mkdir -p "${TOMCAT_DIR}/conf/Catalina/localhost"

# Render context.xml template if destination doesn't exist. Token
# substitution is bash parameter-substitution against env vars sourced
# from .env above — no hand-edit step.
if [[ ! -f "${CONTEXT_DEST}" ]]; then
    sed -e "s|@@POSTGRES_HOST@@|${POSTGRES_HOST}|g" \
        -e "s|@@POSTGRES_PORT@@|${POSTGRES_PORT}|g" \
        -e "s|@@POSTGRES_DB@@|${POSTGRES_DB}|g" \
        -e "s|@@POSTGRES_USER@@|${POSTGRES_USER}|g" \
        -e "s|@@POSTGRES_PASSWORD@@|${POSTGRES_PASSWORD}|g" \
        "${CONFIG_DIR}/Wikantik-context.xml.template" > "${CONTEXT_DEST}"
    print_status "Rendered ${CONTEXT_DEST} from .env"
else
    print_status "Context file already exists (not overwritten)"
fi

# Copy properties template if destination doesn't exist (substituting @@REPO_ROOT@@ tokens)
if [[ ! -f "${PROPS_DEST}" ]]; then
    sed "s|@@REPO_ROOT@@|${PROJECT_ROOT}|g" \
        "${CONFIG_DIR}/wikantik-custom-postgresql.properties.template" \
        > "${PROPS_DEST}"
    print_status "Created ${PROPS_DEST} (paths substituted for ${SCRIPT_DIR})"
else
    print_status "Properties file already exists (not overwritten)"
fi

# Remove stale jspwiki-custom.properties if present
OLD_PROPS="${TOMCAT_DIR}/lib/jspwiki-custom.properties"
if [[ -f "${OLD_PROPS}" ]]; then
    rm -f "${OLD_PROPS}"
    print_status "Removed stale jspwiki-custom.properties (superseded by wikantik-custom.properties)"
fi

# Copy log4j2.xml template if destination doesn't exist
if [[ ! -f "${LOG4J2_DEST}" ]]; then
    cp "${CONFIG_DIR}/log4j2-local.xml.template" "${LOG4J2_DEST}"
    print_status "Created ${LOG4J2_DEST}"
    echo "         Uses portable path: \${sys:catalina.base}/logs/wikantik"
else
    print_status "Log4j2 config already exists (not overwritten)"
fi

# Copy MCP properties template if destination doesn't exist. This file overrides
# the classpath defaults from wikantik-mcp.jar — mainly rate limits tuned low
# for interactive testing.
if [[ ! -f "${MCP_PROPS_DEST}" ]]; then
    cp "${CONFIG_DIR}/wikantik-mcp.properties.template" "${MCP_PROPS_DEST}"
    print_status "Created ${MCP_PROPS_DEST}"
else
    print_status "MCP properties file already exists (not overwritten)"
fi

# Copy bin/setenv.sh template if destination doesn't exist. Tomcat's catalina.sh
# sources this on every launch — primary use is enabling the JDK incubator
# Vector API for Lucene 10. Preserved across --upgrade-tomcat.
if [[ ! -f "${SETENV_DEST}" ]]; then
    cp "${CONFIG_DIR}/setenv.sh.template" "${SETENV_DEST}"
    chmod +x "${SETENV_DEST}"
    print_status "Created ${SETENV_DEST} (Vector API enabled)"
else
    print_status "setenv.sh already exists (not overwritten)"
fi

# Tomcat's own conf/context.xml template — adds the SameSite=lax cookie
# processor on top of stock. After --upgrade-tomcat the stock file from the
# new tarball is in place; the customization gets re-applied here.
if [[ ! -f "${CONFIG_DIR}/Tomcat-context.xml.template" ]]; then
    print_warning "Tomcat-context.xml.template missing — skipping conf/context.xml customization"
elif ! diff -q "${TOMCAT_CONTEXT_DEST}" "${CONFIG_DIR}/Tomcat-context.xml.template" >/dev/null 2>&1; then
    # Either the file was just untar'd from a fresh Tomcat (no customization
    # yet) or the user has hand-edited it. We only overwrite when the current
    # contents are clearly the stock file — any other state means the operator
    # has it the way they want — EXCEPT a stale sameSiteCookies value: a Strict
    # (or any non-template) SameSite policy silently breaks browser sessions
    # (random logouts), so we always correct it to the template's value. This
    # guard previously matched any sameSiteCookies line and so never fixed a
    # stale Strict left over from an older template.
    tmpl_samesite="$(grep -oP 'sameSiteCookies="\K[^"]+' "${CONFIG_DIR}/Tomcat-context.xml.template" 2>/dev/null || true)"
    dest_samesite="$(grep -oP 'sameSiteCookies="\K[^"]+' "${TOMCAT_CONTEXT_DEST}" 2>/dev/null || true)"
    if ! grep -q "CookieProcessor sameSiteCookies" "${TOMCAT_CONTEXT_DEST}" 2>/dev/null; then
        cp "${CONFIG_DIR}/Tomcat-context.xml.template" "${TOMCAT_CONTEXT_DEST}"
        print_status "Applied Tomcat-context.xml.template (CookieProcessor sameSiteCookies=${tmpl_samesite})"
    elif [[ -n "${tmpl_samesite}" && "${dest_samesite}" != "${tmpl_samesite}" ]]; then
        cp "${CONFIG_DIR}/Tomcat-context.xml.template" "${TOMCAT_CONTEXT_DEST}"
        print_warning "conf/context.xml had stale sameSiteCookies=\"${dest_samesite}\" — corrected to \"${tmpl_samesite}\" (Strict causes random logouts)"
    else
        print_status "conf/context.xml already customized (not overwritten)"
    fi
else
    print_status "conf/context.xml already matches template"
fi

# Tomcat's own conf/server.xml template — adds Cloudflare RemoteIpValve +
# custom AccessLogValve on top of stock. Same overwrite-only-if-stock guard.
if [[ ! -f "${CONFIG_DIR}/Tomcat-server.xml.template" ]]; then
    print_warning "Tomcat-server.xml.template missing — skipping conf/server.xml customization"
elif ! diff -q "${TOMCAT_SERVER_DEST}" "${CONFIG_DIR}/Tomcat-server.xml.template" >/dev/null 2>&1; then
    if grep -q "RemoteIpValve" "${TOMCAT_SERVER_DEST}" 2>/dev/null; then
        print_status "conf/server.xml already customized (not overwritten)"
    else
        cp "${CONFIG_DIR}/Tomcat-server.xml.template" "${TOMCAT_SERVER_DEST}"
        print_status "Applied Tomcat-server.xml.template (RemoteIpValve + custom AccessLogValve)"
    fi
else
    print_status "conf/server.xml already matches template"
fi

# Create log directory if needed
if [[ ! -d "${LOG_DIR}" ]]; then
    mkdir -p "${LOG_DIR}"
    print_status "Created log directory: ${LOG_DIR}"
else
    print_status "Log directory already exists: ${LOG_DIR}"
fi

# Stop Tomcat if running
if [[ -f "${TOMCAT_DIR}/bin/shutdown.sh" ]]; then
    echo ""
    echo "Stopping Tomcat (if running)..."
    "${TOMCAT_DIR}/bin/shutdown.sh" 2>/dev/null || true
    sleep 1
fi

# Rotate catalina.out (keep one previous version). Force-overwrite any
# existing .old file and bypass shell aliases so nothing prompts.
CATALINA_OUT="${TOMCAT_DIR}/logs/catalina.out"
if [[ -f "${CATALINA_OUT}" ]]; then
    \rm -f "${CATALINA_OUT}.old"
    \mv -f "${CATALINA_OUT}" "${CATALINA_OUT}.old"
    print_status "Rotated catalina.out → catalina.out.old"
fi

# Remove old deployment
if [[ -d "${TOMCAT_DIR}/webapps/ROOT" ]]; then
    echo "Removing old Wikantik deployment..."
    rm -rf "${TOMCAT_DIR}/webapps/ROOT"
fi

# Deploy WAR
echo ""
echo "Deploying WAR file..."
cp "${WAR_SOURCE}" "${TOMCAT_DIR}/webapps/ROOT.war"
print_status "WAR deployed to ${TOMCAT_DIR}/webapps/ROOT.war"

# Detect the actual database name from the deployed context file (may differ from template default)
WIKI_DB="wikantik"
if [[ -f "${CONTEXT_DEST}" ]]; then
    _db=$(grep -oE 'jdbc:postgresql://[^/]+/[^"]+' "${CONTEXT_DEST}" | head -1 | sed 's|.*jdbc:postgresql://[^/]*/||')
    [[ -n "${_db}" ]] && WIKI_DB="${_db}"
fi

# Run database migrations (tracked in schema_migrations, idempotent).
# Prefer the app role (POSTGRES_USER, sourced from .env above): it owns the
# tables, so ALTER/owner-restricted DDL applies cleanly with no "must be owner"
# failure-then-postgres-fallback noise. The postgres elif remains for the rare
# migration that genuinely needs superuser (e.g. CREATE EXTENSION).
MIGRATE_SH="${SCRIPT_DIR}/db/migrate.sh"
echo ""
echo "Running database migrations..."
if DB_NAME="${WIKI_DB}" PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}" \
       PGHOST="${POSTGRES_HOST:-localhost}" "${MIGRATE_SH}"; then
    print_status "Database migrations applied"
elif DB_NAME="${WIKI_DB}" PGUSER=postgres "${MIGRATE_SH}"; then
    print_status "Database migrations applied (as postgres)"
else
    print_warning "Could not run database migrations automatically."
    echo "         Run manually: DB_NAME=${WIKI_DB} ${MIGRATE_SH}"
fi

# Seed dev user accounts (idempotent upsert — safe to run every deploy).
# Uses the credentials sourced from .env above, so a fresh clone with a
# correctly-populated .env has zero hand-tuning. Falls back to peer-auth
# (psql -d) and postgres-superuser (psql -U postgres) for setups that
# don't keep the password in env.
echo ""
echo "Seeding user accounts..."
if PGPASSWORD="${POSTGRES_PASSWORD}" psql \
       -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" \
       -U "${POSTGRES_USER}" -d "${WIKI_DB}" \
       -f "${SEED_SQL}" -q 2>/dev/null; then
    print_status "Admin account ensured in ${WIKI_DB} (admin/admin123 — first login requires choosing a new password)"
elif psql -d "${WIKI_DB}" -f "${SEED_SQL}" -q 2>/dev/null; then
    print_status "User accounts seeded via peer-auth in ${WIKI_DB}"
elif psql -U postgres -d "${WIKI_DB}" -f "${SEED_SQL}" -q 2>/dev/null; then
    print_status "User accounts seeded as postgres in ${WIKI_DB}"
else
    print_warning "Could not seed user accounts automatically."
    echo "         Run manually: PGPASSWORD=\"\${POSTGRES_PASSWORD}\" \\"
    echo "             psql -h \${POSTGRES_HOST} -U \${POSTGRES_USER} -d ${WIKI_DB} -f ${SEED_SQL}"
fi

# Optional local-only accounts (personal logins, testbot). Gitignored; absent
# on fresh clones — that's fine.
LOCAL_SEED_SQL="${SCRIPT_DIR}/db/seed-users.local.sql"
if [[ -f "${LOCAL_SEED_SQL}" ]]; then
    if PGPASSWORD="${POSTGRES_PASSWORD}" psql \
           -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" \
           -U "${POSTGRES_USER}" -d "${WIKI_DB}" \
           -f "${LOCAL_SEED_SQL}" -q 2>/dev/null \
       || psql -d "${WIKI_DB}" -f "${LOCAL_SEED_SQL}" -q 2>/dev/null \
       || psql -U postgres -d "${WIKI_DB}" -f "${LOCAL_SEED_SQL}" -q 2>/dev/null; then
        print_status "Local user accounts seeded from seed-users.local.sql"
    else
        print_warning "seed-users.local.sql present but could not be applied — run it manually."
    fi
fi

# Wiki pages now live in docs/wikantik-pages/ (version-controlled)
# No copy step needed — Tomcat serves directly from docs/wikantik-pages/

# Summary
echo ""
echo "==========================================="
echo "Deployment Complete!"
echo "==========================================="
echo ""
echo "Starting Tomcat..."
"${TOMCAT_DIR}/bin/startup.sh"
print_status "Tomcat started — open http://localhost:8080/"
echo ""

# First-login guidance: tell the operator exactly what to expect. The flag
# query degrades gracefully (empty = unknown, e.g. password auth refused).
ADMIN_MUST_CHANGE="$(PGPASSWORD="${POSTGRES_PASSWORD}" psql \
    -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" \
    -U "${POSTGRES_USER}" -d "${WIKI_DB}" -tAc \
    "SELECT password_must_change FROM users WHERE login_name='admin'" 2>/dev/null || true)"
echo ""
echo "==========================================="
echo " Wikantik is starting at http://localhost:8080/"
if [[ "${ADMIN_MUST_CHANGE// /}" == "t" ]]; then
    echo ""
    echo "   First login:  admin / admin123"
    echo "   You will be required to choose a new password on first login."
elif [[ "${ADMIN_MUST_CHANGE// /}" == "f" ]]; then
    echo ""
    echo "   Admin password already set — log in with your chosen password."
fi
echo "==========================================="

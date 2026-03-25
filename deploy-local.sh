#!/bin/bash
#
# deploy-local.sh - Deploy Wikantik to local Tomcat 11 with PostgreSQL configuration
#
# Prerequisites:
#   1. Run 'mvn clean install' (or 'mvn clean install -Dmaven.test.skip') first
#   2. PostgreSQL running with 'wikantik' database created
#   3. Run the DDL: sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql.ddl
#   4. Edit tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml to set your password
#
# Usage:
#   ./deploy-local.sh          # Deploy WAR and configure Tomcat
#   ./deploy-local.sh --help   # Show this help
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOMCAT_DIR="${SCRIPT_DIR}/tomcat/tomcat-11"
WAR_SOURCE="${SCRIPT_DIR}/wikantik-war/target/Wikantik.war"
CONFIG_DIR="${SCRIPT_DIR}/wikantik-war/src/main/config/tomcat"
SEED_SQL="${SCRIPT_DIR}/wikantik-war/src/main/config/db/seed-users.sql"
CONTEXT_DEST="${TOMCAT_DIR}/conf/Catalina/localhost/ROOT.xml"
PROPS_DEST="${TOMCAT_DIR}/lib/wikantik-custom.properties"
LOG4J2_DEST="${TOMCAT_DIR}/lib/log4j2.xml"
LOG_DIR="${TOMCAT_DIR}/logs/wikantik"
JDBC_DRIVER="${TOMCAT_DIR}/lib/postgresql.jar"
JDBC_URL="https://jdbc.postgresql.org/download/postgresql-42.7.4.jar"

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
    head -20 "$0" | tail -15
    exit 0
}

# Parse arguments
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    show_help
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

# Check if WAR exists
if [[ ! -f "${WAR_SOURCE}" ]]; then
    print_error "WAR file not found: ${WAR_SOURCE}"
    echo "  Please run 'mvn clean install' first"
    exit 1
fi
print_status "WAR file found: ${WAR_SOURCE}"

# Download Tomcat 11 if not present
TOMCAT_VERSION="11.0.18"
TOMCAT_URL="https://downloads.apache.org/tomcat/tomcat-11/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz"

if [[ ! -d "${TOMCAT_DIR}" ]]; then
    echo "Tomcat not found — downloading Tomcat ${TOMCAT_VERSION}..."
    mkdir -p "$(dirname "${TOMCAT_DIR}")"
    TMP_TAR=$(mktemp /tmp/tomcat-XXXXXX.tar.gz)
    curl -fL -o "${TMP_TAR}" "${TOMCAT_URL}"
    tar -xzf "${TMP_TAR}" -C "$(dirname "${TOMCAT_DIR}")"
    mv "$(dirname "${TOMCAT_DIR}")/apache-tomcat-${TOMCAT_VERSION}" "${TOMCAT_DIR}"
    rm -f "${TMP_TAR}"
    chmod +x "${TOMCAT_DIR}"/bin/*.sh
    mkdir -p "${TOMCAT_DIR}/data/jspwiki-files" \
             "${TOMCAT_DIR}/data/attachments"   \
             "${TOMCAT_DIR}/data/workdir"        \
             "${TOMCAT_DIR}/logs/wikantik"
    print_status "Tomcat ${TOMCAT_VERSION} downloaded and unpacked"
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

# Create context directory if needed
mkdir -p "${TOMCAT_DIR}/conf/Catalina/localhost"

# Copy context.xml template if destination doesn't exist
if [[ ! -f "${CONTEXT_DEST}" ]]; then
    cp "${CONFIG_DIR}/Wikantik-context.xml.template" "${CONTEXT_DEST}"
    print_warning "Created ${CONTEXT_DEST}"
    echo "         >>> IMPORTANT: Edit this file to set your PostgreSQL password! <<<"
else
    print_status "Context file already exists (not overwritten)"
fi

# Copy properties template if destination doesn't exist (substituting @@REPO_ROOT@@ tokens)
if [[ ! -f "${PROPS_DEST}" ]]; then
    sed "s|@@REPO_ROOT@@|${SCRIPT_DIR}|g" \
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
    sleep 2
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

# Seed dev user accounts (idempotent upsert — safe to run every deploy)
echo ""
echo "Seeding user accounts..."
# Detect the actual database name from the deployed context file (may differ from template default)
WIKI_DB="wikantik"
if [[ -f "${CONTEXT_DEST}" ]]; then
    _db=$(grep -oE 'jdbc:postgresql://[^/]+/[^"]+' "${CONTEXT_DEST}" | head -1 | sed 's|.*jdbc:postgresql://[^/]*/||')
    [[ -n "${_db}" ]] && WIKI_DB="${_db}"
fi
if psql -d "${WIKI_DB}" -f "${SEED_SQL}" -q 2>/dev/null; then
    print_status "User accounts seeded in ${WIKI_DB} (admin/admin123, jakefear@gmail.com/passw0rd)"
elif psql -U postgres -d "${WIKI_DB}" -f "${SEED_SQL}" -q 2>/dev/null; then
    print_status "User accounts seeded in ${WIKI_DB} (admin/admin123, jakefear@gmail.com/passw0rd)"
else
    print_warning "Could not seed user accounts automatically."
    echo "         Run manually: psql -d ${WIKI_DB} -f ${SEED_SQL}"
fi

# Wiki pages now live in docs/wikantik-pages/ (version-controlled)
# No copy step needed — Tomcat serves directly from docs/wikantik-pages/

# Summary
echo ""
echo "==========================================="
echo "Deployment Complete!"
echo "==========================================="
echo ""
echo "To start the wiki:"
echo "  ${TOMCAT_DIR}/bin/startup.sh"
echo "  then open http://localhost:8080/"
echo ""

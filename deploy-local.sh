#!/bin/bash
#
# deploy-local.sh - Deploy JSPWiki to local Tomcat 11 with PostgreSQL configuration
#
# Prerequisites:
#   1. Run 'mvn clean install' (or 'mvn clean install -Dmaven.test.skip') first
#   2. PostgreSQL running with 'jspwiki' database created
#   3. Run the DDL: sudo -u postgres psql -d jspwiki -f jspwiki-war/src/main/config/db/postgresql.ddl
#   4. Edit tomcat/tomcat-11/conf/Catalina/localhost/JSPWiki.xml to set your password
#
# Usage:
#   ./deploy-local.sh          # Deploy WAR and configure Tomcat
#   ./deploy-local.sh --help   # Show this help
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOMCAT_DIR="${SCRIPT_DIR}/tomcat/tomcat-11"
WAR_SOURCE="${SCRIPT_DIR}/jspwiki-war/target/JSPWiki.war"
CONFIG_DIR="${SCRIPT_DIR}/jspwiki-war/src/main/config/tomcat"
CONTEXT_DEST="${TOMCAT_DIR}/conf/Catalina/localhost/JSPWiki.xml"
PROPS_DEST="${TOMCAT_DIR}/lib/jspwiki-custom.properties"
JDBC_DRIVER="${TOMCAT_DIR}/lib/postgresql-42.7.4.jar"
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
echo "JSPWiki Local Deployment Script"
echo "==========================================="
echo ""

# Check if WAR exists
if [[ ! -f "${WAR_SOURCE}" ]]; then
    print_error "WAR file not found: ${WAR_SOURCE}"
    echo "  Please run 'mvn clean install' first"
    exit 1
fi
print_status "WAR file found: ${WAR_SOURCE}"

# Check if Tomcat directory exists
if [[ ! -d "${TOMCAT_DIR}" ]]; then
    print_error "Tomcat directory not found: ${TOMCAT_DIR}"
    exit 1
fi
print_status "Tomcat directory found: ${TOMCAT_DIR}"

# Download PostgreSQL JDBC driver if not present
if [[ ! -f "${JDBC_DRIVER}" ]]; then
    echo ""
    echo "Downloading PostgreSQL JDBC driver..."
    wget -q -O "${JDBC_DRIVER}" "${JDBC_URL}"
    print_status "PostgreSQL JDBC driver downloaded"
else
    print_status "PostgreSQL JDBC driver already present"
fi

# Create context directory if needed
mkdir -p "${TOMCAT_DIR}/conf/Catalina/localhost"

# Copy context.xml template if destination doesn't exist
if [[ ! -f "${CONTEXT_DEST}" ]]; then
    cp "${CONFIG_DIR}/JSPWiki-context.xml.template" "${CONTEXT_DEST}"
    print_warning "Created ${CONTEXT_DEST}"
    echo "         >>> IMPORTANT: Edit this file to set your PostgreSQL password! <<<"
else
    print_status "Context file already exists (not overwritten)"
fi

# Copy properties template if destination doesn't exist
if [[ ! -f "${PROPS_DEST}" ]]; then
    cp "${CONFIG_DIR}/jspwiki-custom-postgresql.properties.template" "${PROPS_DEST}"
    print_status "Created ${PROPS_DEST}"
    echo "         You may want to review and adjust paths in this file"
else
    print_status "Properties file already exists (not overwritten)"
fi

# Stop Tomcat if running
if [[ -f "${TOMCAT_DIR}/bin/shutdown.sh" ]]; then
    echo ""
    echo "Stopping Tomcat (if running)..."
    "${TOMCAT_DIR}/bin/shutdown.sh" 2>/dev/null || true
    sleep 2
fi

# Remove old deployment
if [[ -d "${TOMCAT_DIR}/webapps/JSPWiki" ]]; then
    echo "Removing old JSPWiki deployment..."
    rm -rf "${TOMCAT_DIR}/webapps/JSPWiki"
fi

# Deploy WAR
echo ""
echo "Deploying WAR file..."
cp "${WAR_SOURCE}" "${TOMCAT_DIR}/webapps/"
print_status "WAR deployed to ${TOMCAT_DIR}/webapps/"

# Summary
echo ""
echo "==========================================="
echo "Deployment Complete!"
echo "==========================================="
echo ""

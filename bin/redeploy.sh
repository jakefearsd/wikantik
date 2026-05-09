#!/usr/bin/env bash
#
# bin/redeploy.sh — fast routine redeploy of the bare-metal Tomcat install.
#
# Use this for "edit code, see it running" iteration — it skips the heavier
# work deploy-local.sh does (template materialisation, Tomcat upgrade snapshot
# logic, secrets validation) and just:
#   1. shuts down Tomcat (if running)
#   2. rotates catalina.out so the next run's log starts clean
#   3. swaps the deployed WAR
#   4. starts Tomcat
#
# Builds are NOT run — assume the operator has already produced
# wikantik-war/target/Wikantik.war via `mvn ... package`. That separation is
# deliberate: a typo at the build step shouldn't leave Tomcat half-stopped.
#
# For first-time setup, Tomcat upgrades, or anything that touches templates
# or DB migrations, run bin/deploy-local.sh instead.

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

if [[ ! -d "${TOMCAT_DIR}" ]]; then
    echo "ERROR: ${TOMCAT_DIR} does not exist." >&2
    echo "       Run bin/deploy-local.sh for first-time setup." >&2
    exit 1
fi

if [[ ! -f "${WAR_SOURCE}" ]]; then
    echo "ERROR: ${WAR_SOURCE} not found." >&2
    echo "       Run: mvn clean install -Dmaven.test.skip -T 1C" >&2
    exit 1
fi

# Shutdown
if [[ -f "${TOMCAT_DIR}/bin/shutdown.sh" ]]; then
    echo "Stopping Tomcat..."
    "${TOMCAT_DIR}/bin/shutdown.sh" 2>/dev/null || true
    sleep 1
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

# Startup
"${TOMCAT_DIR}/bin/startup.sh"
echo "Tomcat starting. Tail logs with:"
echo "  tail -f ${CATALINA_OUT}"

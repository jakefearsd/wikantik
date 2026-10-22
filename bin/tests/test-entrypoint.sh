#!/usr/bin/env bash
# Regression guard for docker/entrypoint.sh: with every optional env var unset
# (or set but empty) the container must render exactly the baseline config, so
# a new opt-in feature can never change an existing deployment's output.
#
# Runs the real entrypoint against a scratch CATALINA_HOME with `true` as the
# exec target (no Tomcat, no DB: /opt/wikantik/db/migrate.sh is absent here).
# ENTRYPOINT=<path> overrides the script under test (used to prove the guard
# goes red on a mutated copy).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENTRYPOINT="${ENTRYPOINT:-${REPO_ROOT}/docker/entrypoint.sh}"
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

render() { # render <outdir> [VAR=val ...]
    local out="$1"; shift
    mkdir -p "${out}/lib" "${out}/conf" "${out}/webapps"
    env -i PATH="${PATH}" CATALINA_HOME="${out}" \
        POSTGRES_HOST=db POSTGRES_DB=w POSTGRES_USER=u POSTGRES_PASSWORD=p "$@" \
        bash "${ENTRYPOINT}" true >/dev/null 2>"${out}/stderr" \
        || fail "entrypoint exited non-zero: $(cat "${out}/stderr")"
}

# Every optional WIKANTIK_*/PROXY_* variable the script reads, minus the ones
# that carry required/base values (they appear in both renders identically).
mapfile -t OPTIONAL < <(grep -o '\${\(WIKANTIK_[A-Z0-9_]*\|PROXY_REMOTE_IP_HEADER\)[:}-]' "${ENTRYPOINT}" \
    | sed 's/^\${//; s/[:}-]$//' | sort -u)
[[ ${#OPTIONAL[@]} -gt 10 ]] || fail "found only ${#OPTIONAL[@]} optional vars; extraction broke"

EMPTY_ARGS=()
for v in "${OPTIONAL[@]}"; do EMPTY_ARGS+=("${v}="); done

render "${TMP}/unset"
render "${TMP}/empty" "${EMPTY_ARGS[@]}"

for f in lib/wikantik-custom.properties lib/wikantik-mcp.properties conf/Catalina/localhost/ROOT.xml; do
    [[ -s "${TMP}/unset/${f}" ]] || fail "baseline did not render ${f}"
    diff -u "${TMP}/unset/${f}" "${TMP}/empty/${f}" >&2 \
        || fail "${f} differs between all-unset and all-empty optional env"
done
ok "all-unset and all-empty optional env render identical ${#OPTIONAL[@]}-var output"

# Baseline must not carry any opt-in block.
PROPS="${TMP}/unset/lib/wikantik-custom.properties"
for key in 'embedding.batch-size' 'embedding.commit-batch-size' 'wikantik.sso.' \
           'wikantik.search.dense.backend' 'mail.smtp.host'; do
    if grep -qF -- "${key}" "${PROPS}"; then fail "baseline properties unexpectedly contain ${key}"; fi
done
ok "baseline properties carry no opt-in blocks"

# Tomcat pin agreement: container image, bare-metal installer and the pom's
# Cargo/IT Tomcat must all name the same patch release.
DOCKER_V="$(sed -n 's/^FROM tomcat:\([0-9.]*\)-jdk.*/\1/p' "${REPO_ROOT}/Dockerfile")"
LOCAL_V="$(sed -n 's/^TOMCAT_VERSION="\([0-9.]*\)"/\1/p' "${REPO_ROOT}/bin/deploy-local.sh")"
POM_V="$(sed -n 's#.*<tomcat.version>\(.*\)</tomcat.version>.*#\1#p' "${REPO_ROOT}/pom.xml")"
[[ -n "${DOCKER_V}" && "${DOCKER_V}" == "${LOCAL_V}" && "${LOCAL_V}" == "${POM_V}" ]] \
    || fail "Tomcat drift: Dockerfile=${DOCKER_V} deploy-local=${LOCAL_V} pom=${POM_V}"
ok "Tomcat pinned consistently at ${POM_V}"

# JDBC driver pin agreement. The WAR declares the driver `provided`, so the jar in
# Tomcat's lib/ is the one production runs; it must be the version the unit and IT
# suites resolve from the pom, or the tested driver is not the shipped driver.
POM_JDBC="$(sed -n 's#.*<postgresql-jdbc.version>\(.*\)</postgresql-jdbc.version>.*#\1#p' "${REPO_ROOT}/pom.xml")"
DOCKER_JDBC="$(sed -n 's/^ARG POSTGRES_JDBC_VERSION=\([0-9.]*\)$/\1/p' "${REPO_ROOT}/Dockerfile")"
DEV_JDBC="$(sed -n 's/^ARG POSTGRES_JDBC_VERSION=\([0-9.]*\)$/\1/p' "${REPO_ROOT}/Dockerfile.dev")"
LOCAL_JDBC="$(sed -n 's/^JDBC_VERSION="\([0-9.]*\)"$/\1/p' "${REPO_ROOT}/bin/deploy-local.sh")"
[[ -n "${POM_JDBC}" && "${DOCKER_JDBC}" == "${POM_JDBC}" && "${DEV_JDBC}" == "${POM_JDBC}" \
    && "${LOCAL_JDBC}" == "${POM_JDBC}" ]] \
    || fail "JDBC driver drift: pom=${POM_JDBC} Dockerfile=${DOCKER_JDBC} Dockerfile.dev=${DEV_JDBC} deploy-local=${LOCAL_JDBC}"
ok "PostgreSQL JDBC driver pinned consistently at ${POM_JDBC}"

# JVM heap sizing: WIKANTIK_JVM_MAX_RAM_PERCENTAGE is appended to CATALINA_OPTS
# after the image default (Dockerfile ENV CATALINA_OPTS), and HotSpot takes the
# last occurrence of a -XX flag — so the override must land last, and must leave
# the image default untouched when unset or empty.
IMAGE_OPTS="$(sed -n 's/^ENV CATALINA_OPTS="\(.*\)"$/\1/p' "${REPO_ROOT}/Dockerfile")"
[[ "${IMAGE_OPTS}" == *-XX:MaxRAMPercentage=* ]] || fail "Dockerfile CATALINA_OPTS lost its MaxRAMPercentage default"
heap_pct() { # heap_pct <outdir> [VAR=val ...] — the MaxRAMPercentage the JVM would apply
    local out="$1"; shift
    mkdir -p "${out}/lib" "${out}/conf" "${out}/webapps"
    env -i PATH="${PATH}" CATALINA_HOME="${out}" CATALINA_OPTS="${IMAGE_OPTS}" \
        POSTGRES_HOST=db POSTGRES_DB=w POSTGRES_USER=u POSTGRES_PASSWORD=p "$@" \
        bash "${ENTRYPOINT}" bash -c 'printf "%s" "${CATALINA_OPTS}" > "$1"' _ "${out}/opts" \
        >/dev/null 2>"${out}/stderr" || return 1
    grep -o -- '-XX:MaxRAMPercentage=[^ ]*' "${out}/opts" | tail -1 | cut -d= -f2
}
DEFAULT_PCT="$(heap_pct "${TMP}/heap-unset")" || fail "entrypoint failed: $(cat "${TMP}/heap-unset/stderr")"
[[ "${DEFAULT_PCT}" == "70.0" ]] || fail "unset override changed the heap percentage to '${DEFAULT_PCT}'"
EMPTY_PCT="$(heap_pct "${TMP}/heap-empty" WIKANTIK_JVM_MAX_RAM_PERCENTAGE=)" || fail "empty override failed to boot"
[[ "${EMPTY_PCT}" == "70.0" ]] || fail "empty override changed the heap percentage to '${EMPTY_PCT}'"
SET_PCT="$(heap_pct "${TMP}/heap-set" WIKANTIK_JVM_MAX_RAM_PERCENTAGE=50)" || fail "override 50 failed to boot"
[[ "${SET_PCT}" == "50" ]] || fail "WIKANTIK_JVM_MAX_RAM_PERCENTAGE=50 did not win; JVM would apply '${SET_PCT}'"
ok "WIKANTIK_JVM_MAX_RAM_PERCENTAGE overrides the image heap default and is inert when unset/empty"

i=0
for bad in abc 0 100 150 '50 -Dx=y' '5.' '.5' '50%'; do
    i=$((i + 1))
    if heap_pct "${TMP}/heap-bad-${i}" "WIKANTIK_JVM_MAX_RAM_PERCENTAGE=${bad}" >/dev/null; then
        fail "entrypoint booted with WIKANTIK_JVM_MAX_RAM_PERCENTAGE='${bad}'"
    fi
    grep -q 'WIKANTIK_JVM_MAX_RAM_PERCENTAGE' "${TMP}/heap-bad-${i}/stderr" \
        || fail "rejection of '${bad}' did not name the variable: $(cat "${TMP}/heap-bad-${i}/stderr")"
done
ok "malformed WIKANTIK_JVM_MAX_RAM_PERCENTAGE values refuse to boot"

# Connection-pool hygiene: a half-open TCP connection or hung query must not pin a pooled
# connection forever (driver connect/socket timeouts + keepalive), and validation must run
# on idle connections rather than on every borrow. Container and bare-metal templates agree.
TEMPLATE="${REPO_ROOT}/wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template"
for ctx in "${TMP}/unset/conf/Catalina/localhost/ROOT.xml" "${TEMPLATE}"; do
    for want in 'connectTimeout=10' 'socketTimeout=300' 'tcpKeepAlive=true' \
                'testOnBorrow="false"' 'testWhileIdle="true"' 'timeBetweenEvictionRunsMillis="30000"'; do
        grep -qF -- "${want}" "${ctx}" || fail "${ctx} lacks ${want}"
    done
    if grep -qi 'removeAbandoned="true"' "${ctx}"; then fail "${ctx} must not enable removeAbandoned"; fi
done
ok "container and bare-metal DataSource carry driver timeouts and idle-time validation"

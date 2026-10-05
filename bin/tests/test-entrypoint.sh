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

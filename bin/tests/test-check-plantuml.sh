#!/usr/bin/env bash
# bin/tests/test-check-plantuml.sh — exercises bin/check-plantuml.sh's launch logic (class jar, AOT cache,
# JVM flags, fallbacks) in a throwaway tree with `java` and `jar` stubbed on PATH. No real JVM runs.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
pass() { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fail=1; }

tree="$(mktemp -d)"; trap 'rm -rf "${tree}"' EXIT
mkdir -p "${tree}/bin" "${tree}/stubs" "${tree}/wikantik-main/target/classes/com"
cp "${HERE}/check-plantuml.sh" "${tree}/bin/"
echo 'class' > "${tree}/wikantik-main/target/classes/com/A.class"
echo '/deps/a.jar:/deps/b.jar' > "${tree}/wikantik-main/target/plantuml-check.classpath"
CALLS="${tree}/calls.log"
T="${tree}/wikantik-main/target"

# java stub: logs its argv; `-XX:AOTCacheOutput=F` writes F (or fails when STUB_TRAIN_FAIL=1);
# a check run prints a JSON report on stdout and noise on stderr.
cat > "${tree}/stubs/java" <<'EOF'
#!/usr/bin/env bash
echo "java $*" >> "${CALLS}"
for a in "$@"; do
  case "$a" in
    -XX:AOTCacheOutput=*) cat >/dev/null; [ "${STUB_TRAIN_FAIL:-0}" = 1 ] && { echo "training boom" >&2; exit 1; }
                          echo aot > "${a#-XX:AOTCacheOutput=}"; exit 0 ;;
  esac
done
cat >/dev/null
echo "plantuml noise" >&2
echo '[]'
EOF
cat > "${tree}/stubs/jar" <<'EOF'
#!/usr/bin/env bash
echo "jar $*" >> "${CALLS}"
[ "$1" = cf ] && echo jarred > "$2"
EOF
chmod +x "${tree}/stubs/java" "${tree}/stubs/jar"
export PATH="${tree}/stubs:${PATH}" CALLS

check() { "${tree}/bin/check-plantuml.sh" --json - <<<'x' 2>"${tree}/err.log"; }

# 1. First run: builds the class jar, trains the AOT cache, then runs the check with it.
: > "${CALLS}"
out="$(check)"; rc=$?
[ "${rc}" = 0 ] && [ "${out}" = '[]' ] && pass "first run reports on stdout only" || bad "first run rc=${rc} out=${out}"
[ -s "${T}/plantuml-check.jar" ] && pass "builds the class jar" || bad "no class jar"
[ -s "${T}/plantuml-check.aot" ] && pass "trains the AOT cache" || bad "no AOT cache: $(cat "${CALLS}")"
last="$(tail -1 "${CALLS}")"
grep -q -- "-XX:AOTCache=${T}/plantuml-check.aot" <<<"${last}" && pass "check runs with the AOT cache" || bad "no AOTCache flag: ${last}"
grep -q -- "-cp ${T}/plantuml-check.jar:/deps/a.jar:/deps/b.jar" <<<"${last}" && pass "classpath leads with the class jar" || bad "classpath: ${last}"
grep -q -- "-XX:TieredStopAtLevel=1" <<<"${last}" && pass "short-run JIT flag" || bad "no TieredStopAtLevel: ${last}"
grep -q -- "-Xlog:all=warning:stderr" <<<"${last}" && pass "JVM log kept off stdout" || bad "JVM log may reach stdout: ${last}"
grep -q "plantuml noise" "${tree}/err.log" && pass "checker stderr passes through" || bad "stderr lost"

# 2. Warm run: nothing is rebuilt or retrained.
: > "${CALLS}"
check >/dev/null
[ "$(grep -c . "${CALLS}")" = 1 ] && pass "warm run is a single JVM launch" || bad "warm run rebuilt: $(cat "${CALLS}")"

# 3. A newer class file rebuilds the jar and retrains the cache.
sleep 1; touch "${tree}/wikantik-main/target/classes/com/A.class"
: > "${CALLS}"
check >/dev/null
grep -q '^jar cf' "${CALLS}" && pass "stale classes rebuild the jar" || bad "jar not rebuilt"
grep -q 'AOTCacheOutput' "${CALLS}" && pass "a rebuilt jar retrains the cache" || bad "cache not retrained"

# 4. A newer dependency classpath retrains the cache.
sleep 1; touch "${T}/plantuml-check.classpath"
: > "${CALLS}"
check >/dev/null
grep -q 'AOTCacheOutput' "${CALLS}" && pass "a changed classpath retrains the cache" || bad "cache not retrained on classpath change"

# 5. Training that fails never fails the check: it runs without a cache and says why on stderr.
rm -f "${T}/plantuml-check.aot"
: > "${CALLS}"
out="$(STUB_TRAIN_FAIL=1 check)"; rc=$?
[ "${rc}" = 0 ] && [ "${out}" = '[]' ] && pass "failed training still checks" || bad "failed training rc=${rc} out=${out}"
grep -q -- "-XX:AOTCache=" <<<"$(tail -1 "${CALLS}")" && bad "used a cache that was never written" || pass "runs without a cache"
grep -q "AOT cache" "${tree}/err.log" && pass "failed training is reported on stderr" || bad "silent training failure: $(cat "${tree}/err.log")"
[ -e "${T}/plantuml-check.aot" ] && bad "left a partial cache" || pass "no partial cache left behind"

# 6. The jar tool fails: falls back to the classes directory, without a cache, and leaves no partial jar.
rm -f "${T}/plantuml-check.jar" "${T}/plantuml-check.aot"
printf '#!/usr/bin/env bash\necho "jar $*" >> "${CALLS}"\necho partial > "$2"; exit 1\n' > "${tree}/stubs/jar"
: > "${CALLS}"
out="$(check)"; rc=$?
[ "${rc}" = 0 ] && grep -q -- "-cp ${T}/classes:/deps/a.jar" <<<"$(tail -1 "${CALLS}")" \
  && pass "a failing jar tool falls back to the classes directory" || bad "jar fallback rc=${rc}: $(cat "${CALLS}")"
grep -q -- "-XX:AOTCache" "${CALLS}" && bad "trained or used a cache over the classes directory" || pass "no cache without a class jar"
[ -e "${T}/plantuml-check.jar" ] && bad "left a partial jar" || pass "no partial jar left behind"

exit "${fail}"

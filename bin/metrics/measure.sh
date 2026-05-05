#!/usr/bin/env bash
# Captures decomposition-progress metrics as JSON.
#
# See docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md.
# Designed to be re-runnable per phase; writes a single JSON object with one
# top-level key per snapshot. Usage:
#
#   bin/metrics/measure.sh                              # print to stdout
#   bin/metrics/measure.sh --label phase_1 > out.json   # named snapshot
#
# Requires: bash, find, grep, awk, sort, wc. No jq dependency.
# `head -n` legitimately closes its upstream early; pipefail would turn that
# into a spurious SIGPIPE failure. Individual `set -e` paths still apply.
set -eu

# Resolve repo root regardless of where the script is invoked from.
SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
REPO_ROOT="$( cd "${SCRIPT_DIR}/../.." && pwd )"
cd "${REPO_ROOT}"

label="baseline_$(date +%Y_%m_%d)"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --label) label="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

# --- measurements ----------------------------------------------------------

loc_main=$( find wikantik-main/src/main/java -name '*.java' \
    -exec cat {} + 2>/dev/null | wc -l )

registered_managers=$( grep -E "managers\.put\(\s*[A-Za-z\.]+\.class" \
    wikantik-main/src/main/java/com/wikantik/WikiEngine.java 2>/dev/null \
    | sed -E 's/.*managers\.put\(\s*([A-Za-z\.]+)\.class.*/\1/' \
    | sort -u | wc -l )

# All modules' main + test sources, excluding bridge classes
get_manager_callers=$( grep -rEn "engine\.getManager\(|getEngine\(\)\.getManager\(" \
    --include='*.java' . 2>/dev/null \
    | grep -v target \
    | grep -v "/WikiEngine\.java:" \
    | grep -v "/RestServletBase\.java:" \
    | wc -l )

# wikantik-main only (matches ArchUnit's analysis scope)
get_manager_callers_main=$( grep -rEn "engine\.getManager\(|getEngine\(\)\.getManager\(" \
    --include='*.java' wikantik-main/src/main/java 2>/dev/null \
    | grep -v "/WikiEngine\.java:" \
    | wc -l )

# God-classes >= 800 lines in wikantik-main/src/main
god_classes_over_800=$( find wikantik-main/src/main/java -name '*.java' \
    -exec wc -l {} \; 2>/dev/null \
    | awk '$1 >= 800 {n++} END {print n+0}' )

# Top 10 largest classes — emitted as a JSON array
god_class_top10_json=$( find wikantik-main/src/main/java -name '*.java' \
    -exec wc -l {} \; 2>/dev/null \
    | sort -rn | head -10 \
    | awk '{
        # strip leading wikantik-main/src/main/java/ prefix for readability
        path=$2; sub("^wikantik-main/src/main/java/", "", path);
        printf "%s{\"path\":\"%s\",\"loc\":%d}", (NR>1 ? "," : ""), path, $1
      }' )

test_engine_references_in_production=$( grep -rln "TestEngine" \
    --include='*.java' wikantik-main/src/main 2>/dev/null | wc -l )

archunit_frozen_violations=$( find wikantik-main/src/test/resources/archunit_store \
    -type f ! -name 'stored.rules' 2>/dev/null \
    -exec wc -l {} \; \
    | awk '{ sum += $1 } END { print sum+0 }' )

# --- emit JSON -------------------------------------------------------------

cat <<JSON
{
  "${label}": {
    "captured_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
    "loc_main": ${loc_main},
    "registered_managers": ${registered_managers},
    "get_manager_callers_repo_wide": ${get_manager_callers},
    "get_manager_callers_in_main": ${get_manager_callers_main},
    "god_classes_over_800": ${god_classes_over_800},
    "god_class_top10": [${god_class_top10_json}],
    "test_engine_references_in_production": ${test_engine_references_in_production},
    "archunit_frozen_violations": ${archunit_frozen_violations}
  }
}
JSON

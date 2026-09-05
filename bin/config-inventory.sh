#!/usr/bin/env bash
# config-inventory.sh — list every "wikantik.*" property literal in production
# Java, its status in ini/wikantik.properties, reference count, and any literal
# default(s) used at read sites.
#
# Usage: bin/config-inventory.sh [--missing|--commented|--explicit] [REPO_ROOT]
#   Output TSV: key  status  refs  literal-defaults
#
# This is a developer aid for the config-surface burn-down; the authoritative
# gate is ConfigSurfaceDriftTest (wikantik-war).
set -euo pipefail
filter=""
case "${1:-}" in
  -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
  --missing) filter=MISSING; shift ;;
  --commented) filter=COMMENTED; shift ;;
  --explicit) filter=EXPLICIT; shift ;;
esac
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
INI="$ROOT/wikantik-main/src/main/resources/ini/wikantik.properties"
# Strip // and /* */ comments so javadoc mentions do not count as reads.
strip() { sed -E 's#//.*$##' "$1" | perl -0777 -pe 's{/\*.*?\*/}{}gs'; }
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
find "$ROOT"/wikantik-*/src/main/java -name '*.java' -print0 | while IFS= read -r -d '' f; do strip "$f"; done > "$tmp"
grep -oE '"wikantik\.[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*"' "$tmp" | tr -d '"' | sort | uniq -c | awk '{print $2"\t"$1}' |
while IFS=$'\t' read -r key refs; do
  if grep -qE "^${key//./\\.}\s*=" "$INI"; then st=EXPLICIT
  elif grep -qE "^#\s*${key//./\\.}\s*=" "$INI"; then st=COMMENTED
  else st=MISSING; fi
  defaults=$(grep -oE "Property\(\s*[^,()]*,?\s*\"${key//./\\.}\"\s*,\s*(\"[^\"]*\"|-?[0-9.]+[LlDdFf]?|true|false)\s*\)" "$tmp" \
             | sed -E 's/.*",\s*//; s/\s*\)$//' | sort -u | paste -sd'|' -)
  [[ -n "$filter" && "$st" != "$filter" ]] && continue
  printf '%s\t%s\t%s\t%s\n' "$key" "$st" "$refs" "$defaults"
done

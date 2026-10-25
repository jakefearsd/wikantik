#!/bin/bash
# check-plantuml.sh — corpus checker for PlantUML fences (counterpart of
# wikantik-frontend/scripts/check-mermaid.mjs; same contract, used by the content agent's linter).
#
# Usage: bin/check-plantuml.sh [--json] <file|dir|->...
#   file  a Markdown file     dir  every *.md under it (recursive)     -  Markdown on stdin
# Output: one line per diagram, `OK|FAIL <file>:<line> <error>` (line = opening fence, 1-based);
#   with --json, ONLY a JSON array of { file, line, ok, error } on stdout; noise goes to stderr.
# Exit:   0 all OK (or no diagrams), 1 any FAIL, 2 usage/checker error.
#
# Needs wikantik-main/target/classes (mvn compile -pl wikantik-main); the dependency classpath is
# cached in wikantik-main/target/plantuml-check.classpath on first use.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/wikantik-main/target/classes"
CPFILE="$ROOT/wikantik-main/target/plantuml-check.classpath"

if [[ ! -d "$CLASSES" ]]; then
    echo "check-plantuml: $CLASSES is missing; run: mvn compile -pl wikantik-main -am -DskipTests" >&2
    exit 2
fi

if [[ ! -s "$CPFILE" ]]; then
    (cd "$ROOT" && mvn -q dependency:build-classpath -pl wikantik-main \
        -Dmdep.outputFile="$CPFILE") >&2 || { echo "check-plantuml: could not build the classpath" >&2; exit 2; }
fi

exec java -cp "$CLASSES:$(cat "$CPFILE")" \
    com.wikantik.markdown.extensions.fences.PlantUmlCheckCli "$@"

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
#
# Start-up cost dominates a run (the linter checks one page per launch), so the classes are jarred
# (target/plantuml-check.jar) and a JDK 25 AOT cache (target/plantuml-check.aot) is trained once on a
# built-in sample; both are rebuilt when the classes, the classpath file or the JVM are newer. Any
# failure there only costs speed: the check then runs from target/classes without a cache.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$ROOT/wikantik-main/target/classes"
CPFILE="$ROOT/wikantik-main/target/plantuml-check.classpath"

if [[ ! -d "$CLASSES" ]]; then
    echo "check-plantuml: $CLASSES is missing; run: mvn compile -pl wikantik-main -am -DskipTests" >&2
    exit 2
fi

if [[ ! -s "$CPFILE" ]]; then
    # </dev/null: a piped `-` input must never be consumed by Maven.
    # runtime scope: the shipped (shaded, stripped) PlantUML only, never the test-scope plantuml-mit jar.
    (cd "$ROOT" && mvn -q dependency:build-classpath -pl wikantik-main -DincludeScope=runtime \
        -Dmdep.outputFile="$CPFILE" </dev/null) >&2 || { echo "check-plantuml: could not build the classpath" >&2; exit 2; }
fi

if ! command -v java >/dev/null 2>&1; then
    echo "check-plantuml: java not found on PATH (JDK 25+ required)" >&2
    exit 2
fi

JAR="$ROOT/wikantik-main/target/plantuml-check.jar"
AOT="$ROOT/wikantik-main/target/plantuml-check.aot"
MAIN=com.wikantik.markdown.extensions.fences.PlantUmlCheckCli
# C1 only: a check is a short run, where the C2 compiler costs more CPU than it saves. JVM log output goes
# to stderr, since stdout is reserved for the report (an AOT cache mismatch warning defaults to stdout).
JFLAGS=(-XX:TieredStopAtLevel=1 -Xlog:disable -Xlog:all=warning:stderr)

# Atomic replace (tmp + mv), so concurrent runs never see a half-written jar or cache.
if [[ ! -s "$JAR" ]] || [[ -n "$(find "$CLASSES" -newer "$JAR" -print -quit)" ]]; then
    tmp="$JAR.$$.tmp"
    if jar cf "$tmp" -C "$CLASSES" . >&2 && [[ -s "$tmp" ]]; then
        mv -f "$tmp" "$JAR"
    else
        rm -f "$tmp" "$JAR"
        echo "check-plantuml: could not jar $CLASSES; running without the AOT cache" >&2
    fi
fi

if [[ -s "$JAR" ]]; then
    CP="$JAR:$(cat "$CPFILE")"
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    if [[ ! -s "$AOT" || "$JAR" -nt "$AOT" || "$CPFILE" -nt "$AOT" || "$JAVA_BIN" -nt "$AOT" ]]; then
        tmp="$AOT.$$.tmp"
        TRAINLOG="$(mktemp)"
        # Training sample: the diagram kinds pages use, so their code paths are in the cache.
        if java "${JFLAGS[@]}" -XX:AOTCacheOutput="$tmp" -cp "$CP" "$MAIN" - >/dev/null 2>"$TRAINLOG" <<'SAMPLE' && [[ -s "$tmp" ]]; then
```plantuml
interface Shape
abstract class Base<T> implements Shape {
  +T value
}
class Circle extends Base
Circle --> Shape : draws
```

```plantuml
start
if (ok?) then (yes)
  :go;
else (no)
  :stop;
endif
stop
```

```plantuml
Alice -> Bob : hi
Bob --> Alice : ok
```
SAMPLE
            mv -f "$tmp" "$AOT"
        else
            rm -f "$tmp" "$AOT"
            echo "check-plantuml: could not train the AOT cache (running without it): $(tail -1 "$TRAINLOG")" >&2
        fi
        rm -f "$TRAINLOG"
    fi
    [[ -s "$AOT" ]] && JFLAGS+=(-XX:AOTCache="$AOT")
else
    CP="$CLASSES:$(cat "$CPFILE")"
fi

# Not exec: a missing-classpath failure (stale cache) is mapped to the documented exit 2 with a hint.
ERRLOG="$(mktemp)"
trap 'rm -f "$ERRLOG"' EXIT
rc=0
java "${JFLAGS[@]}" -cp "$CP" "$MAIN" "$@" 2> >(tee "$ERRLOG" >&2) || rc=$?
wait
if [ "$rc" -ne 0 ] && grep -qE 'NoClassDefFoundError|ClassNotFoundException' "$ERRLOG"; then
    echo "check-plantuml: delete $CPFILE if dependencies changed, then re-run" >&2
    exit 2
fi
exit "$rc"

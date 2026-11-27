#!/usr/bin/env bash
# Mutation-test one module with PIT (-Pmutation). Opt-in analysis, never part of the gate.
#
#   bin/mutation-test.sh <module> [targetClasses glob] [extra mvn args...]
#   bin/mutation-test.sh wikantik-work
#   bin/mutation-test.sh wikantik-main 'com.wikantik.auth.*'
#
# Upstream modules must already be installed (mvn install -DskipTests). Reports land in
# <module>/target/pit-reports; summarise them with bin/mutation-report.py <module>.
# Large modules take longer than an agent Bash call allows: run through bin/agent-build.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
module="${1:?usage: bin/mutation-test.sh <module> [targetClasses glob] [mvn args...]}"
shift
target="com.wikantik.*"
if [[ $# -gt 0 && "$1" != -* ]]; then
  target="$1"
  shift
fi
# Tests default to the same packages as the mutated classes, so a package run does not pay for the whole
# module's coverage pass. Widen with -Dwikantik.mutation.targetTests='com.wikantik.*'.
tests="$target"
# A package run gets its own report directory (target/pit-reports-<pkg>) so several can be compared;
# a whole-module run stays in target/pit-reports.
reports="target/pit-reports"
if [[ "$target" != "com.wikantik.*" ]]; then
  reports="target/pit-reports-$(echo "$target" | sed 's/^com\.wikantik\.//; s/[^A-Za-z0-9]\+/-/g; s/-$//')"
fi
exec mvn -o -Pmutation -pl "$module" \
  -Dwikantik.mutation.targetClasses="$target" \
  -Dwikantik.mutation.targetTests="$tests" \
  -DreportsDirectory="$reports" \
  test-compile org.pitest:pitest-maven:mutationCoverage "$@"

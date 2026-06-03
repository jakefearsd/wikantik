#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# Tests for bin/db/audit-retention.sh. Pure filesystem + stubbed
# psql/pg_dump/pg_restore on PATH — no real PostgreSQL. The stubs log every
# invocation to $CMDLOG so assertions can inspect what SQL/commands were issued.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT}/bin/db/audit-retention.sh"
TMPS=()
cleanup() { local d; for d in "${TMPS[@]+"${TMPS[@]}"}"; do [[ -d "$d" ]] && rm -rf "$d"; done; return 0; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# Build a sandbox: stub psql/pg_dump/pg_restore on PATH.
# - psql: SELECT (list partitions) prints $PARTLIST; CREATE/DROP are logged.
# - pg_dump: writes a non-empty fake dump file + logs.
# - pg_restore --list: exits per $PGRESTORE_RC (default 0).
make_sandbox() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("$tmp"); mkdir -p "${tmp}/bin" "${tmp}/archive"
    cat > "${tmp}/bin/psql" <<'STUB'
#!/usr/bin/env bash
args="$*"
# The -c command is the last arg.
cmd=""; prev=""
for a in "$@"; do if [ "$prev" = "-c" ]; then cmd="$a"; fi; prev="$a"; done
echo "psql:${cmd}" >> "$CMDLOG"
case "$cmd" in
  *pg_inherits*) printf '%s\n' $PARTLIST ;;   # list_partitions
  *) : ;;
esac
exit 0
STUB
    cat > "${tmp}/bin/pg_dump" <<'STUB'
#!/usr/bin/env bash
out=""; prev=""
for a in "$@"; do if [ "$prev" = "-f" ]; then out="$a"; fi; prev="$a"; done
echo "pg_dump:$*" >> "$CMDLOG"
[ -n "$out" ] && echo "FAKEDUMP" > "$out"
exit 0
STUB
    cat > "${tmp}/bin/pg_restore" <<'STUB'
#!/usr/bin/env bash
echo "pg_restore:$*" >> "$CMDLOG"
exit "${PGRESTORE_RC:-0}"
STUB
    chmod +x "${tmp}/bin/psql" "${tmp}/bin/pg_dump" "${tmp}/bin/pg_restore"
    echo "$tmp"
}

run() {  # run the script with the sandbox PATH + a fresh CMDLOG
    local sb="$1"; shift
    export CMDLOG="${sb}/cmdlog"; : > "$CMDLOG"
    PATH="${sb}/bin:$PATH" AUDIT_ARCHIVE_DIR="${sb}/archive" "$SCRIPT" "$@"
}

# Old partitions (well before any plausible 84-month cutoff) + a near-current one.
export PARTLIST="audit_log_2000_01 audit_log_2000_02 audit_log_2099_01"

# --- Case 1: pre-create issues CREATE for current + lookahead months ---
sb="$(make_sandbox)"
AUDIT_PARTITION_LOOKAHEAD=2 run "$sb" >/dev/null
creates=$(grep -c 'CREATE TABLE IF NOT EXISTS audit_log_' "$CMDLOG" || true)
[ "$creates" -ge 3 ] || fail "expected >=3 CREATE statements (current + 2 ahead), got $creates"

# --- Case 2: archive-then-drop ordering for an over-age partition ---
sb="$(make_sandbox)"
run "$sb" >/dev/null
grep -q 'pg_dump:.*audit_log_2000_01' "$CMDLOG" || fail "expected pg_dump of audit_log_2000_01"
# pg_dump line must appear BEFORE the DROP line for that partition
dump_ln=$(grep -n 'pg_dump:.*audit_log_2000_01' "$CMDLOG" | head -1 | cut -d: -f1)
drop_ln=$(grep -n 'DROP TABLE audit_log_2000_01' "$CMDLOG" | head -1 | cut -d: -f1)
[ -n "$drop_ln" ] || fail "expected DROP of audit_log_2000_01"
[ "$dump_ln" -lt "$drop_ln" ] || fail "pg_dump must precede DROP for audit_log_2000_01"

# --- Case 3: in-window partition (2099) is NOT dropped ---
grep -q 'DROP TABLE audit_log_2099_01' "$CMDLOG" && fail "in-window partition 2099_01 must not be dropped"

# --- Case 4: --dry-run issues no CREATE/DROP/pg_dump side effects ---
sb="$(make_sandbox)"
run "$sb" --dry-run >/dev/null
grep -qE 'CREATE TABLE IF NOT EXISTS audit_log_|DROP TABLE audit_log_' "$CMDLOG" \
    && fail "--dry-run must not issue CREATE/DROP"
grep -q 'pg_dump:' "$CMDLOG" && fail "--dry-run must not pg_dump"

# --- Case 5: guardrail — AUDIT_RETENTION_MONTHS=0 skips the drop phase ---
sb="$(make_sandbox)"
AUDIT_RETENTION_MONTHS=0 run "$sb" >/dev/null
grep -q 'DROP TABLE audit_log_' "$CMDLOG" && fail "retention=0 must skip drop phase"
grep -q 'CREATE TABLE IF NOT EXISTS audit_log_' "$CMDLOG" || fail "retention=0 should still pre-create"

# --- Case 6: archive-verify failure (pg_restore fails) => NOT dropped ---
sb="$(make_sandbox)"
PGRESTORE_RC=1 run "$sb" >/dev/null || true
grep -q 'pg_dump:.*audit_log_2000_01' "$CMDLOG" || fail "expected pg_dump attempt"
grep -q 'DROP TABLE audit_log_2000_01' "$CMDLOG" && fail "must not DROP when pg_restore verify fails"

echo "PASS: test-audit-retention.sh (6 cases)"

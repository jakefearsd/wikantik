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
# Wikantik audit_log retention purge.
#
# Pre-creates upcoming monthly partitions and archives-then-drops partitions
# older than the retention window. Runs as the privileged `migrate` role (the
# app role is INSERT/SELECT-only and cannot drop). No application code involved;
# the audit hash chain re-anchors on the oldest surviving row automatically.
#
# Usage: audit-retention.sh [--dry-run] [--status] [--help]
#
# Env (defaults):
#   DB_NAME=wikantik  PGHOST=localhost  PGPORT=5432  PGUSER=migrate  PGPASSWORD=
#   AUDIT_RETENTION_MONTHS=84      keep this many months; older partitions purged
#   AUDIT_PARTITION_LOOKAHEAD=3    months of future partitions to pre-create
#   AUDIT_ARCHIVE_DIR=            dir the off-box backup captures (required to drop)
set -euo pipefail

DB_NAME="${DB_NAME:-wikantik}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-migrate}"
RETENTION_MONTHS="${AUDIT_RETENTION_MONTHS:-84}"
LOOKAHEAD="${AUDIT_PARTITION_LOOKAHEAD:-3}"
ARCHIVE_DIR="${AUDIT_ARCHIVE_DIR:-}"

DRY_RUN=0; STATUS=0
case "${1:-}" in
  --dry-run) DRY_RUN=1 ;;
  --status)  STATUS=1 ;;
  --help) sed -n '18,35p' "$0"; exit 0 ;;
  "" ) ;;
  *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
esac

psql_q()   { PGPASSWORD="${PGPASSWORD:-}" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -At -c "$1"; }
psql_exec(){ PGPASSWORD="${PGPASSWORD:-}" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "$1"; }

# Enumerate audit_log partitions from the catalog (robust), filter by name below.
list_partitions() {
  psql_q "SELECT c.relname FROM pg_inherits i \
          JOIN pg_class c ON c.oid = i.inhrelid \
          JOIN pg_class p ON p.oid = i.inhparent \
          WHERE p.relname = 'audit_log' ORDER BY c.relname;"
}

CUR="$(date -u +%Y-%m-01)"   # first of the current month, UTC

if [ "$STATUS" = 1 ]; then
  echo "audit_log retention: keep ${RETENTION_MONTHS} months; pre-create ${LOOKAHEAD} ahead."
  if [[ "$RETENTION_MONTHS" =~ ^[0-9]+$ ]] && [ "$RETENTION_MONTHS" -ge 1 ]; then
    echo "Cutoff (drop partitions before): $(date -u -d "$CUR -${RETENTION_MONTHS} months" +%Y_%m)"
  else
    echo "Cutoff: (drop phase disabled — AUDIT_RETENTION_MONTHS < 1)"
  fi
  echo "Existing partitions:"; list_partitions
  exit 0
fi

# ---- 1. Pre-create upcoming partitions (idempotent) ----
echo "Pre-creating partitions (current + ${LOOKAHEAD} months)..."
for i in $(seq 0 "$LOOKAHEAD"); do
  start="$(date -u -d "$CUR +${i} months" +%Y-%m-01)"
  end="$(date -u -d "$CUR +$((i + 1)) months" +%Y-%m-01)"
  name="audit_log_$(date -u -d "$start" +%Y_%m)"
  sql="CREATE TABLE IF NOT EXISTS ${name} PARTITION OF audit_log FOR VALUES FROM ('${start}') TO ('${end}');"
  if [ "$DRY_RUN" = 1 ]; then echo "[dry-run] ${sql}"; else psql_exec "$sql"; fi
done

# ---- guardrail ----
if ! [[ "$RETENTION_MONTHS" =~ ^[0-9]+$ ]] || [ "$RETENTION_MONTHS" -lt 1 ]; then
  echo "AUDIT_RETENTION_MONTHS unset or < 1 — skipping drop phase (pre-create only)." >&2
  exit 0
fi

# ---- 2. Archive-then-drop over-age partitions ----
cutoff_name="audit_log_$(date -u -d "$CUR -${RETENTION_MONTHS} months" +%Y_%m)"
echo "Archiving+dropping partitions strictly before ${cutoff_name}..."
while IFS= read -r part; do
  [ -z "$part" ] && continue
  [[ "$part" =~ ^audit_log_[0-9]{4}_[0-9]{2}$ ]] || continue   # only managed monthly partitions
  # Zero-padded names sort chronologically, so a plain string compare is correct.
  [[ "$part" < "$cutoff_name" ]] || continue

  if [ "$DRY_RUN" = 1 ]; then echo "[dry-run] archive+drop ${part}"; continue; fi
  if [ -z "$ARCHIVE_DIR" ]; then
    echo "AUDIT_ARCHIVE_DIR not set — cannot archive ${part}; skipping (not dropped)." >&2
    continue
  fi
  mkdir -p "$ARCHIVE_DIR"
  out="${ARCHIVE_DIR}/${part}_$(date -u +%Y%m%dT%H%M%SZ).dump"
  echo "Archiving ${part} -> ${out}"
  PGPASSWORD="${PGPASSWORD:-}" pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" \
      -Fc --table="${part}" -f "$out"
  if [ ! -s "$out" ] || ! pg_restore --list "$out" >/dev/null 2>&1; then
    echo "Archive verify FAILED for ${part}; NOT dropping." >&2
    continue
  fi
  echo "Dropping ${part}"
  psql_exec "DROP TABLE ${part};"
done < <(list_partitions)

echo "Audit retention run complete."

# Audit Retention-Purge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scheduled, out-of-band job that pre-creates upcoming `audit_log` monthly partitions and archives-then-drops partitions older than a configured window (default 84 months / 7 years).

**Architecture:** Pure ops — no Java changes. A `bin/db/audit-retention.sh` script (mirroring `bin/db/migrate.sh` conventions; runs as the privileged `migrate` role), a stub-based bash test (mirroring `bin/tests/test-backup.sh`), and a `systemd` timer installer (mirroring `bin/backup/nas-install-timer.sh`). The hash chain survives a purge with no code change (`verifyChain` anchors on the oldest surviving row).

**Tech Stack:** Bash (`set -euo pipefail`), GNU `date`, `psql`/`pg_dump`/`pg_restore`, `systemd`.

**Spec:** `docs/superpowers/specs/2026-06-03-audit-retention-purge-design.md`

**Verified context:**
- Partition naming/range from V036: `audit_log_YYYY_MM PARTITION OF audit_log FOR VALUES FROM ('YYYY-MM-01') TO ('<next-month>-01')`. Zero-padded names → lexicographic ordering matches chronological ordering.
- `migrate.sh` env convention: `DB_NAME` (default `wikantik`), `PGHOST` (`localhost`), `PGPORT` (`5432`), `PGUSER` (the privileged `migrate` role), `PGPASSWORD` (from env, honored by psql/pg_dump). Flags `--help`/`--status`.
- The `migrate` role has `CREATE` on schema `public` and owns the partitions → can CREATE and DROP them. The app role (`jspwiki`) is INSERT/SELECT-only and is NOT used here.
- `bin/tests/test-backup.sh` pattern: `set -euo pipefail`, `fail()` + `trap cleanup EXIT`, a sandbox dir with stub `pg_dump`/`psql` on `PATH`, pure-filesystem assertions (no real PG).
- `bin/backup/nas-install-timer.sh` pattern: `tee`s a `.service` + `.timer` under `/etc/systemd/system`, `OnCalendar`, `systemctl daemon-reload` + `enable --now`.

---

## Task 1: `audit-retention.sh` + its stub-based test

**Files:**
- Create: `bin/db/audit-retention.sh`
- Create: `bin/tests/test-audit-retention.sh`

TDD: write the test (stubs + assertions) first, watch it fail (script absent), then write the script to pass.

- [ ] **Step 1: Write the failing test**

```bash
#!/usr/bin/env bash
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
PARTLIST="audit_log_2000_01 audit_log_2000_02 audit_log_2099_01"

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
```

- [ ] **Step 2: Run to verify it fails**

Run: `bash bin/tests/test-audit-retention.sh`
Expected: FAIL — `bin/db/audit-retention.sh` does not exist (`No such file`).

- [ ] **Step 3: Write `audit-retention.sh`**

```bash
#!/usr/bin/env bash
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
  --help) sed -n '2,16p' "$0"; exit 0 ;;
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
```

Make it executable: `chmod +x bin/db/audit-retention.sh`. Add the ASF license header as a comment block (copy the header style from `bin/db/migrate.sh`).

- [ ] **Step 4: Run the test to verify it passes**

Run: `bash bin/tests/test-audit-retention.sh`
Expected: `PASS: test-audit-retention.sh (6 cases)`.
Also confirm `bash -n bin/db/audit-retention.sh` (syntax) and `bin/db/audit-retention.sh --help` print usage.

- [ ] **Step 5: Commit**

```bash
chmod +x bin/db/audit-retention.sh bin/tests/test-audit-retention.sh
git add bin/db/audit-retention.sh bin/tests/test-audit-retention.sh
git commit -m "feat(audit): retention-purge script (pre-create + archive-then-drop) + test"
```

---

## Task 2: systemd timer installer

**Files:**
- Create: `bin/db/audit-retention-install-timer.sh`

Mirror `bin/backup/nas-install-timer.sh`.

- [ ] **Step 1: Read the reference + write the installer**

Read `bin/backup/nas-install-timer.sh` fully, then create `bin/db/audit-retention-install-timer.sh` with the same structure (ASF header; `set -euo pipefail`; a `--status` branch printing `systemctl list-timers`/`status`; `SUDO` handling). It must `tee` two unit files and enable the timer:

```bash
# (key body — mirror the reference's variable + SUDO conventions)
UNIT_NAME="wikantik-audit-retention"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ON_CALENDAR="${ON_CALENDAR:-*-*-01 04:00:00}"   # first of each month, 04:00
ENV_FILE="${ENV_FILE:-/etc/wikantik/audit-retention.env}"

${SUDO} tee "/etc/systemd/system/${UNIT_NAME}.service" >/dev/null <<EOF
[Unit]
Description=Wikantik audit_log retention purge
After=network-online.target

[Service]
Type=oneshot
EnvironmentFile=-${ENV_FILE}
ExecStart=${REPO_DIR}/bin/db/audit-retention.sh
EOF

${SUDO} tee "/etc/systemd/system/${UNIT_NAME}.timer" >/dev/null <<EOF
[Unit]
Description=Monthly Wikantik audit_log retention purge

[Timer]
OnCalendar=${ON_CALENDAR}
Persistent=true

[Install]
WantedBy=timers.target
EOF

${SUDO} systemctl daemon-reload
${SUDO} systemctl enable --now "${UNIT_NAME}.timer"
```

Include a header comment documenting the `EnvironmentFile` keys (`DB_NAME`, `PGHOST`,
`PGPORT`, `PGUSER`, `PGPASSWORD`, `AUDIT_RETENTION_MONTHS`, `AUDIT_ARCHIVE_DIR`, …) and
the "run once now to test" hint (`systemctl start ${UNIT_NAME}.service`), exactly as the
backup installer does.

- [ ] **Step 2: Syntax-check**

Run: `bash -n bin/db/audit-retention-install-timer.sh`
Expected: no output (valid). (Do NOT actually install on this machine.)

- [ ] **Step 3: Commit**

```bash
chmod +x bin/db/audit-retention-install-timer.sh
git add bin/db/audit-retention-install-timer.sh
git commit -m "feat(audit): systemd timer installer for monthly retention purge"
```

---

## Task 3: Docs, RAT, CHANGELOG, and the open-items update

**Files:**
- Modify: `docs/wikantik-pages/AuditLogDesign.md` ("Open items / v2" — mark retention purge + the `ensurePartition` CREATE concern resolved)
- Modify: `CHANGELOG.md` (Unreleased → Added)
- Modify: `docs/BackupAndRecovery.md` (a short "Audit log retention" runbook subsection: how to configure the env file, run `--status`/`--dry-run`, install the timer, and restore an archived partition with `pg_restore`)
- Possibly modify: the Apache RAT config if `.sh` files under `bin/db`/`bin/tests` need a license exclusion (check how existing `bin/db/*.sh` are handled — they carry header comments or are RAT-excluded).

- [ ] **Step 1: Update the audit design open items**

In `AuditLogDesign.md` "Open items / v2", change the "Retention purge…" bullet and the
"`ensurePartition` needs schema `CREATE`" bullet to **RESOLVED**: retention is now a
scheduled `bin/db/audit-retention.sh` (archive-then-drop, default 84 months, run by the
`migrate` role via a systemd timer) which also pre-creates upcoming partitions, removing
the runtime `CREATE`-privilege dependency.

- [ ] **Step 2: CHANGELOG + runbook**

Add a CHANGELOG "Added" entry: `feat: audit log retention purge — scheduled archive-then-drop of audit_log partitions older than a configurable window (default 7 years), with month-ahead partition pre-creation`. Add the BackupAndRecovery runbook subsection.

- [ ] **Step 3: RAT / license check**

Run: `mvn -q apache-rat:check` (root) OR confirm how `bin/db/*.sh` pass RAT (they have header comments or an exclusion in the rat plugin config). If the new `.sh` files trip RAT, add the same header/exclusion the existing `bin/db`/`bin/tests` scripts use.
Expected: RAT passes.

- [ ] **Step 4: Commit**

```bash
git add docs/wikantik-pages/AuditLogDesign.md CHANGELOG.md docs/BackupAndRecovery.md
# include the RAT config file only if you had to change it
git commit -m "docs(audit): retention purge runbook, changelog, resolved open items"
```

---

## Final verification

- [ ] **Run the script test + a build sanity check**
  - `bash bin/tests/test-audit-retention.sh` → `PASS (6 cases)`.
  - `bash -n bin/db/audit-retention.sh && bash -n bin/db/audit-retention-install-timer.sh` → valid.
  - `mvn -q apache-rat:check` → RAT passes (no missing-license failures from the new scripts).
  - (No Java changed, so no unit/IT reactor run is required for this feature. If you want belt-and-suspenders, a `git grep -n "verifyChain" wikantik-main` confirms the chain code is untouched.)

---

## Self-review

- **Spec coverage:** script (pre-create + archive-then-drop + flags + guardrail) → T1; the stub test (6 cases incl. ordering, dry-run, guardrail, verify-failure) → T1; systemd timer → T2; config defaults (84mo, lookahead 3, enabled-by-default, `migrate` role) → baked into the script (T1) + documented (T3); chain-integrity-no-code-change → confirmed in the spec + final verification note; archive-to-NAS dir → `AUDIT_ARCHIVE_DIR` (T1) + runbook (T3); docs/open-items → T3. Out-of-scope (per-category retention, admin UI, auto-restore) correctly absent.
- **Placeholder scan:** T1 ships full script + full test; T2 gives the full key body + the reference to mirror; T3 names exact files/edits. No TODO/TBD.
- **Type/name consistency:** env vars (`AUDIT_RETENTION_MONTHS`, `AUDIT_PARTITION_LOOKAHEAD`, `AUDIT_ARCHIVE_DIR`, `PGUSER=migrate`), partition name pattern `audit_log_YYYY_MM`, the `list_partitions`/`psql_exec`/`psql_q` helpers, and the `--dry-run`/`--status` flags are used identically across the script, the test, and the installer. The test's stub `psql` returns `$PARTLIST` for the `pg_inherits` query that `list_partitions` issues — matched.

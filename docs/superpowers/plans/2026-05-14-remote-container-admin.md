# Remote Container Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `bin/remote.sh` — a single bash entry point that ssh-drives the existing `bin/container.sh` on a remote host, with a host-path bind mount for `pages/` and `--dry-run` support for every state-changing subcommand. See `docs/superpowers/specs/2026-05-14-remote-container-admin-design.md`.

**Architecture:** Thin bash orchestrator that wraps `ssh` + `rsync` + remote re-invocation of `bin/container.sh`. The dev box builds locally (`mvn` + `docker compose build`), then streams the image via `docker save | ssh 'docker load'`. The remote host runs no JDK/Maven/Node toolchain. ControlMaster keeps ssh fast; `flock` on the remote prevents concurrent deploys.

**Tech Stack:** Bash 5+, ssh OpenSSH ControlMaster, rsync, docker compose, shellcheck (static analysis). No Maven/Java/Python additions — this is a pure shell deliverable. Tests are dry-run assertions wired into a `bin/tests/test-remote.sh` smoke script (no bats/junit dependency).

**Spec:** `docs/superpowers/specs/2026-05-14-remote-container-admin-design.md`

---

## File Structure

**Created files:**

- `bin/remote.sh` — main entry point with all subcommands and helper functions (target size ~600 lines; split internal functions logically inside the file but keep one file for ergonomics — matches `bin/container.sh` convention).
- `remote.env.example` — committed template documenting all required + optional vars.
- `bin/tests/test-remote.sh` — bash smoke script that drives `bin/remote.sh --dry-run …` invocations and greps the output. Runs in CI-equivalent local checks.

**Modified files:**

- `docker-compose.prod.yml` — replace `wikantik-pages` named volume usage with a host bind mount on the `wikantik` and `backup` services. Keep `wikantik-work` / `wikantik-logs` as named volumes (regeneratable).
- `.gitignore` — add `/remote.env` (the existing `.env.*` glob does **not** match it; `remote.env` does not start with `.env.`).
- `CLAUDE.md` — add a short "Remote container deployment" subsection under "Local Deployment (Tomcat 11)" pointing at `bin/remote.sh` and the design doc.

---

## Task 1: Compose change — host bind-mount for prod pages

**Files:**
- Modify: `docker-compose.prod.yml`

- [ ] **Step 1: Read current prod compose**

Run: `cat docker-compose.prod.yml`

Confirm the `wikantik` service has no `volumes:` block and that `backup` mounts `wikantik-pages` as a named volume.

- [ ] **Step 2: Add a smoke-test assertion (TDD)**

Append to `bin/tests/test-remote.sh` (create the file if absent — it must start with `#!/usr/bin/env bash` and `set -euo pipefail`):

```bash
#!/usr/bin/env bash
# Smoke tests for bin/remote.sh and the prod compose change.
# Pure dry-run + grep style — no real ssh, no real docker.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# --- compose: prod pages must bind-mount a host path ---
test_prod_pages_bind_mount() {
    local out
    out="$(WIKANTIK_PAGES_DIR=/srv/wikantik/pages BACKUP_DIR=/srv/wikantik/backups \
           docker compose -f docker-compose.yml -f docker-compose.prod.yml config 2>/dev/null)" \
        || fail "docker compose config rejected prod overlay"
    echo "${out}" | grep -E '^\s+source: /srv/wikantik/pages' >/dev/null \
        || fail "prod overlay did not expand WIKANTIK_PAGES_DIR into a bind mount"
    echo "${out}" | grep -E 'wikantik-pages' >/dev/null \
        && fail "prod overlay still references the wikantik-pages named volume"
    ok "prod compose binds host pages dir"
}

test_prod_pages_bind_mount
```

Make it executable: `chmod +x bin/tests/test-remote.sh`.

- [ ] **Step 3: Run the test, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL because `docker-compose.prod.yml` still uses the named volume.

- [ ] **Step 4: Update `docker-compose.prod.yml`**

Replace the file's contents with:

```yaml
# Prod overrides: resource limits, host bind mounts, backup service
# Usage: WIKANTIK_PAGES_DIR=/srv/wikantik/pages docker compose \
#          -f docker-compose.yml -f docker-compose.prod.yml up -d
services:
  db:
    restart: always
    ports: !reset []
    deploy:
      resources:
        limits:
          memory: 512M

  wikantik:
    # Build from source if no pre-built image exists (fallback before CI/CD is set up)
    build: .
    restart: always
    deploy:
      resources:
        limits:
          memory: 1G
    volumes:
      # Pages live on the host so they can be moved with rsync independently
      # of the container lifecycle. wikantik-work / wikantik-logs stay as
      # named volumes (regeneratable, no operator interest in their contents).
      - ${WIKANTIK_PAGES_DIR:-/srv/wikantik/pages}:/var/wikantik/pages
      - wikantik-work:/var/wikantik/work
      - wikantik-logs:/var/wikantik/logs
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 10s
      start_period: 90s
      retries: 5

  backup:
    image: postgres:17-alpine
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    environment:
      POSTGRES_HOST: db
      POSTGRES_DB: ${POSTGRES_DB:-wikantik}
      POSTGRES_USER: ${POSTGRES_USER:-wikantik}
      PGPASSWORD: ${POSTGRES_PASSWORD:-CHANGEME}
      BACKUP_RETENTION_DAYS: ${BACKUP_RETENTION_DAYS:-30}
    volumes:
      - ${WIKANTIK_PAGES_DIR:-/srv/wikantik/pages}:/var/wikantik/pages
      - ${BACKUP_DIR:-/srv/wikantik/backups}:/backups
      - ./docker/backup/backup.sh:/usr/local/bin/backup.sh:ro
      - ./docker/backup/restore.sh:/usr/local/bin/restore.sh:ro
      - ./docker/backup/crontab:/etc/crontabs/root:ro
    entrypoint: ["crond", "-f", "-d", "8"]
```

The `volumes:` top-level section is intentionally absent in prod — pgdata + wikantik-work + wikantik-logs are inherited from the base compose; `wikantik-pages` is replaced with a bind mount and so no longer needs a top-level declaration.

- [ ] **Step 5: Run the test, expect PASS**

Run: `bin/tests/test-remote.sh`
Expected: `ok: prod compose binds host pages dir`

Also verify dev is unchanged:
Run: `docker compose -f docker-compose.yml -f docker-compose.dev.yml config | grep wikantik-pages || echo "dev still uses named volume? -- no"`
Expected: prints `- wikantik-pages` (dev unchanged) — actually base compose declares the named volume and dev does NOT override it, so this should appear. If it doesn't, the test command in Step 2 missed the right service section; recheck the wikantik volumes list in dev. (Dev uses a different bind-mount `./docs/wikantik-pages:/var/wikantik/pages` — confirm that's still there.)

- [ ] **Step 6: Commit**

```bash
git add docker-compose.prod.yml bin/tests/test-remote.sh
git commit -m "feat(deploy): bind-mount pages dir in prod compose overlay

WIKANTIK_PAGES_DIR (default /srv/wikantik/pages) is now a host path bind
mount on both wikantik and backup services. Dev overlay unchanged."
```

---

## Task 2: `remote.env.example` + `.gitignore`

**Files:**
- Create: `remote.env.example`
- Modify: `.gitignore`

- [ ] **Step 1: Add a smoke-test assertion**

Append to `bin/tests/test-remote.sh`:

```bash
# --- remote.env.example documents all required vars ---
test_remote_env_example_complete() {
    [ -f remote.env.example ]( -f remote.env.example ) || fail "remote.env.example missing"
    for var in REMOTE_HOST REMOTE_USER REMOTE_REPO_DIR REMOTE_PAGES_DIR REMOTE_BACKUP_DIR; do
        grep -q "^${var}=" remote.env.example \
            || fail "remote.env.example missing required var ${var}"
    done
    ok "remote.env.example documents required vars"
}

# --- .gitignore must ignore remote.env (not matched by .env.* glob) ---
test_gitignore_blocks_remote_env() {
    grep -qE '^/?remote\.env$' .gitignore \
        || fail ".gitignore does not block remote.env (the .env.* glob does NOT match it)"
    ok ".gitignore blocks remote.env"
}

test_remote_env_example_complete
test_gitignore_blocks_remote_env
```

- [ ] **Step 2: Run test, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL at `remote.env.example missing`.

- [ ] **Step 3: Create `remote.env.example`**

```bash
# remote.env.example — copy to remote.env and edit. remote.env is gitignored.
# Loaded by bin/remote.sh on every invocation.

# --- Required ---
REMOTE_HOST=wiki.example.com
REMOTE_USER=wikantik
REMOTE_REPO_DIR=/home/wikantik/wikantik
REMOTE_PAGES_DIR=/srv/wikantik/pages
REMOTE_BACKUP_DIR=/srv/wikantik/backups

# --- Optional ---

# SSH key to use. Empty = ssh agent / ~/.ssh/config decide.
SSH_KEY=

# Directory for ssh ControlMaster sockets (created with mode 0700 if absent).
SSH_CONTROL_DIR=~/.ssh/cm

# Health URL the deploy command polls after 'up -d'. Empty falls back to
# http://${REMOTE_HOST}:8080/api/health. Set to your reverse-proxy URL
# (e.g. https://wiki.example.com/api/health) if TLS terminates in front
# of Tomcat.
HEALTH_URL=

# Health-poll timeout in seconds (default 90). Override on the CLI with
# 'deploy --health-timeout=N'.
HEALTH_TIMEOUT=90
```

- [ ] **Step 4: Update `.gitignore`**

Edit `.gitignore`. Find the block near `.env`:

```
.env
.env.*
!.env.example
```

Add immediately after:

```
/remote.env
```

- [ ] **Step 5: Run test, expect PASS**

Run: `bin/tests/test-remote.sh`
Expected: all three `ok:` lines.

- [ ] **Step 6: Commit**

```bash
git add remote.env.example .gitignore bin/tests/test-remote.sh
git commit -m "feat(deploy): add remote.env template and gitignore the live file"
```

---

## Task 3: `bin/remote.sh` skeleton — help, env loading, missing-var errors

**Files:**
- Create: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertions**

Append to `bin/tests/test-remote.sh`:

```bash
# --- remote.sh exists, is executable, passes bash -n ---
test_remote_sh_present_and_parses() {
    [ -x bin/remote.sh ]( -x bin/remote.sh ) || fail "bin/remote.sh missing or not executable"
    bash -n bin/remote.sh || fail "bin/remote.sh has syntax errors"
    ok "bin/remote.sh present and parses"
}

# --- --help prints the subcommand list ---
test_remote_sh_help_lists_subcommands() {
    local out
    out="$(bin/remote.sh --help 2>&1)" || fail "remote.sh --help returned non-zero"
    for sub in bootstrap deploy rollback up down restart logs shell psql migrate \
               status pages-push pages-pull backup-trigger backup-pull restore; do
        echo "${out}" | grep -qw "${sub}" \
            || fail "--help did not mention subcommand ${sub}"
    done
    ok "--help lists all subcommands"
}

# --- missing remote.env produces a clear error (no ssh attempt) ---
test_missing_env_clear_error() {
    local tmp; tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    cp bin/tests/test-remote.sh "${tmp}/" 2>/dev/null || true
    # No remote.env in tmp. Calling a subcommand must error clearly.
    local out exit_code=0
    out="$(cd "${tmp}" && ./remote.sh status 2>&1)" || exit_code=$?
    [ "${exit_code}" -ne 0 ]( "${exit_code}" -ne 0 ) || fail "status with no remote.env returned 0; expected non-zero"
    echo "${out}" | grep -q "remote.env" \
        || fail "missing-env error did not mention remote.env: ${out}"
    rm -rf "${tmp}"
    ok "missing remote.env yields a clear error"
}

# --- required var missing → clear error naming the var ---
test_missing_required_var_clear_error() {
    local tmp; tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    # Empty remote.env: nothing defined.
    : > "${tmp}/remote.env"
    local out exit_code=0
    out="$(cd "${tmp}" && ./remote.sh status 2>&1)" || exit_code=$?
    [ "${exit_code}" -ne 0 ]( "${exit_code}" -ne 0 ) || fail "expected non-zero on empty remote.env"
    echo "${out}" | grep -q "REMOTE_HOST" \
        || fail "missing-var error did not name REMOTE_HOST: ${out}"
    rm -rf "${tmp}"
    ok "missing REMOTE_HOST yields clear error"
}

test_remote_sh_present_and_parses
test_remote_sh_help_lists_subcommands
test_missing_env_clear_error
test_missing_required_var_clear_error
```

- [ ] **Step 2: Run test, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL on `bin/remote.sh missing`.

- [ ] **Step 3: Create `bin/remote.sh` skeleton**

```bash
#!/usr/bin/env bash
#
# bin/remote.sh — ssh-driven admin tool for a remote Wikantik deployment.
# Wraps bin/container.sh on the remote host plus adds image transfer
# (docker save | ssh), rsync-based content sync, ControlMaster, and a
# deploy lock. See docs/superpowers/specs/2026-05-14-remote-container-admin-design.md.
#
# Subcommands:
#   bootstrap                       first-time remote setup (no up -d)
#   deploy   [--skip-build] [--health-timeout=N]
#                                   build, push image, up -d, health-poll, auto-rollback
#   rollback                        re-promote :rollback tag to :latest
#   up | down | restart             pass-through to remote container.sh -e prod
#   status                          health + container ps + disk free + pages size
#   logs     [-f] [SERVICE]         tail logs
#   shell    [SERVICE]              interactive shell in a remote container
#   psql     -- ARGS...             psql pass-through
#   migrate  [--status]             ad-hoc migration run
#   pages-push LOCAL_DIR [--mirror] rsync local pages → remote
#   pages-pull LOCAL_DIR            rsync remote pages → local
#   backup-trigger [TIER]           invoke prod backup sidecar
#   backup-pull   [DATE]            rsync a backup snapshot back to the dev box
#   restore       REMOTE_PATH       sidecar restore + restart
#
# Global flags:
#   --dry-run       print commands instead of running them
#   -h | --help     this help (or, after a subcommand, that subcommand's help)
#
# Configuration: remote.env at the repo root. Copy from remote.env.example.
#
# Exit codes:
#   0   success
#   1   subcommand error (build failed, deploy failed, health timeout, …)
#   2   usage error (unknown subcommand, missing required env, lock held)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

print_main_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

# ---------- Environment loading ----------

REQUIRED_VARS=(REMOTE_HOST REMOTE_USER REMOTE_REPO_DIR REMOTE_PAGES_DIR REMOTE_BACKUP_DIR)

load_env() {
    if [ ! -f remote.env ]( ! -f remote.env ); then
        echo "remote.sh: remote.env not found in $(pwd)." >&2
        echo "           copy remote.env.example to remote.env and edit it." >&2
        exit 2
    fi
    # shellcheck disable=SC1091
    set -a; . ./remote.env; set +a

    local missing=()
    for v in "${REQUIRED_VARS[@]}"; do
        if [ -z "${!v:-}" ]( -z "${!v:-}" ); then
            missing+=("${v}")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        echo "remote.sh: required vars unset in remote.env: ${missing[*]}" >&2
        exit 2
    fi

    : "${SSH_CONTROL_DIR:=${HOME}/.ssh/cm}"
    : "${HEALTH_URL:=http://${REMOTE_HOST}:8080/api/health}"
    : "${HEALTH_TIMEOUT:=90}"
}

# ---------- Argument parsing (global flags) ----------

DRY_RUN=0

# Help with no args: print usage and exit before loading env, so users
# without a remote.env can still read --help.
if [| "${1:-}" == "-h" || "${1:-}" == "--help" ]( $# -eq 0 ); then
    print_main_help
    exit 0
fi

# Strip global flags
ARGS=()
while [ $# -gt 0 ]( $# -gt 0 ); do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]}"

if [ $# -eq 0 ]( $# -eq 0 ); then
    print_main_help
    exit 0
fi

SUBCOMMAND="$1"; shift

# Per-subcommand --help is wired up inside each cmd_* function. We load
# env up-front because every real subcommand needs it; --help paths
# above already exited.
load_env

# ---------- Subcommand dispatch ----------

case "${SUBCOMMAND}" in
    *) echo "remote.sh: unknown subcommand: ${SUBCOMMAND}" >&2
       echo "           run: bin/remote.sh --help" >&2
       exit 2 ;;
esac
```

`chmod +x bin/remote.sh`.

- [ ] **Step 4: Run test, expect PASS**

Run: `bin/tests/test-remote.sh`
Expected: all `ok:` lines including the four new ones.

- [ ] **Step 5: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): bin/remote.sh skeleton with --help and env loading"
```

---

## Task 4: Internal helpers — `_ssh`, `_rsync`, `_run`, `_remote_lock`

**Files:**
- Modify: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertion**

Append to `bin/tests/test-remote.sh`:

```bash
# --- dry-run for an arbitrary helper emits the ssh command without running ---
make_fake_remote_env() {
    local dir="$1"
    cat > "${dir}/remote.env" <<EOF
REMOTE_HOST=test.example.invalid
REMOTE_USER=tester
REMOTE_REPO_DIR=/tmp/repo
REMOTE_PAGES_DIR=/tmp/pages
REMOTE_BACKUP_DIR=/tmp/backups
SSH_CONTROL_DIR=/tmp/cm
EOF
}

test_helpers_emit_ssh_with_controlmaster() {
    local tmp; tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    local out exit_code=0
    out="$(cd "${tmp}" && ./remote.sh --dry-run __selftest 2>&1)" || exit_code=$?
    [ "${exit_code}" -eq 0 ]( "${exit_code}" -eq 0 ) || fail "__selftest dry-run exit ${exit_code}: ${out}"
    echo "${out}" | grep -q "ControlMaster=auto" \
        || fail "_ssh did not include ControlMaster=auto: ${out}"
    echo "${out}" | grep -q "tester@test.example.invalid" \
        || fail "_ssh did not target REMOTE_USER@REMOTE_HOST: ${out}"
    rm -rf "${tmp}"
    ok "_ssh helper emits ControlMaster and target"
}
test_helpers_emit_ssh_with_controlmaster
```

- [ ] **Step 2: Run test, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL — unknown subcommand `__selftest`.

- [ ] **Step 3: Add helpers to `bin/remote.sh`**

Insert before the `# ---------- Subcommand dispatch ----------` line:

```bash
# ---------- Internal helpers ----------

# _ssh ARGS...      — run a command on the remote with ControlMaster
# _ssh -t ARGS...   — same, with a tty (for interactive shell/psql)
_ssh_opts() {
    local opts=(
        -o "ControlMaster=auto"
        -o "ControlPath=${SSH_CONTROL_DIR}/%C"
        -o "ControlPersist=10m"
        -o "StrictHostKeyChecking=accept-new"
    )
    if [ -n "${SSH_KEY:-}" ]( -n "${SSH_KEY:-}" ); then
        opts+=(-i "${SSH_KEY}")
    fi
    printf '%s\0' "${opts[@]}"
}

_ssh() {
    mkdir -p "${SSH_CONTROL_DIR}" && chmod 700 "${SSH_CONTROL_DIR}"
    local opts=()
    while IFS= read -r -d '' opt; do opts+=("${opt}"); done < <(_ssh_opts)
    _run ssh "${opts[@]}" "${REMOTE_USER}@${REMOTE_HOST}" "$@"
}

# _rsync ARGS...    — rsync with ControlMaster-aware ssh
_rsync() {
    mkdir -p "${SSH_CONTROL_DIR}" && chmod 700 "${SSH_CONTROL_DIR}"
    local ssh_inline="ssh -o ControlMaster=auto -o ControlPath=${SSH_CONTROL_DIR}/%C -o ControlPersist=10m -o StrictHostKeyChecking=accept-new"
    if [ -n "${SSH_KEY:-}" ]( -n "${SSH_KEY:-}" ); then
        ssh_inline+=" -i ${SSH_KEY}"
    fi
    _run rsync -e "${ssh_inline}" "$@"
}

# _run CMD ARGS...  — execute (or, under --dry-run, print) the command.
_run() {
    if [ "${DRY_RUN}" -eq 1 ]( "${DRY_RUN}" -eq 1 ); then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    "$@"
}

# _acquire_deploy_lock — non-blocking probe of the deploy lock. Fails with
# exit 2 if another deploy/rollback/restore is in progress. Used by all
# three state-mutating top-level subcommands.
#
# Caveat: the probe holds the lock only for the duration of this single
# ssh call. Subsequent ssh calls inside the same subcommand do not re-
# acquire. This is sufficient for the common "two terminals at once"
# case the spec calls out; it does not protect against finer races,
# which are acceptable in a sole-developer environment.
_acquire_deploy_lock() {
    local lockfile="${REMOTE_REPO_DIR}/.deploy.lock"
    if ! _ssh "mkdir -p $(printf '%q' "${REMOTE_REPO_DIR}") && flock --nonblock --conflict-exit-code 75 ${lockfile} -c 'true'"; then
        echo "remote.sh: deploy lock held on ${REMOTE_HOST} (${lockfile})." >&2
        echo "           Wait for the running operation, or remove the lockfile" >&2
        echo "           if you are certain no deploy/rollback/restore is in progress." >&2
        exit 2
    fi
}

# Selftest subcommand — visible only via dry-run; greps in tests.
cmd_selftest() {
    _ssh true
}
```

Update the dispatch switch at the bottom of the file:

```bash
case "${SUBCOMMAND}" in
    __selftest) cmd_selftest "$@" ;;
    *) echo "remote.sh: unknown subcommand: ${SUBCOMMAND}" >&2
       echo "           run: bin/remote.sh --help" >&2
       exit 2 ;;
esac
```

- [ ] **Step 4: Run test, expect PASS**

Run: `bin/tests/test-remote.sh`
Expected: all green including `ok: _ssh helper emits ControlMaster and target`.

- [ ] **Step 5: shellcheck**

Run: `shellcheck bin/remote.sh`
Expected: no findings (or only `info`-level. Fix any `warning`/`error`).

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): internal helpers (_ssh, _rsync, _run, _remote_lock)"
```

---

## Task 5: Pass-through subcommands (`up`, `down`, `restart`, `logs`, `shell`, `psql`, `migrate`)

**Files:**
- Modify: `bin/remote.sh`

These are thin wrappers around `bin/container.sh -e prod <cmd>` running on the remote.

- [ ] **Step 1: Add smoke-test assertions**

Append to `bin/tests/test-remote.sh`:

```bash
test_passthrough_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"

    for cmd in up down restart "logs -f" "shell" "psql -- -c \\dt" "migrate"; do
        out="$(cd "${tmp}" && eval "./remote.sh --dry-run ${cmd}" 2>&1)" \
            || fail "dry-run ${cmd} non-zero"
        echo "${out}" | grep -q "container.sh -e prod" \
            || fail "dry-run ${cmd} did not invoke remote container.sh -e prod: ${out}"
    done

    # logs default service: wikantik
    out="$(cd "${tmp}" && ./remote.sh --dry-run logs 2>&1)"
    echo "${out}" | grep -q "logs wikantik" \
        || fail "default logs target should be wikantik: ${out}"

    rm -rf "${tmp}"
    ok "pass-through subcommands invoke remote container.sh"
}
test_passthrough_dry_run
```

- [ ] **Step 2: Run test, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL — unknown subcommand `up`.

- [ ] **Step 3: Add the pass-through functions**

Insert before the dispatch switch:

```bash
# ---------- Pass-through subcommands ----------

_remote_container() {
    # All pass-through subcommands run bin/container.sh -e prod on the remote.
    # Quote each argument so spaces and special chars survive the ssh hop.
    local quoted=""
    local a
    for a in "$@"; do
        quoted+=" $(printf '%q' "${a}")"
    done
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod${quoted}"
}

cmd_up()      { _remote_container up "$@"; }
cmd_down()    { _remote_container down "$@"; }
cmd_restart() { _remote_container restart "$@"; }
cmd_logs() {
    if [ $# -eq 0 ]( $# -eq 0 ); then
        _remote_container logs wikantik
    else
        # Detect whether the user passed -f / --follow. If so and they did not
        # pass a service, append wikantik so default behavior matches container.sh.
        local has_service=0
        local a
        for a in "$@"; do
            case "${a}" in
                -*) ;;
                *) has_service=1 ;;
            esac
        done
        if [ "${has_service}" -eq 0 ]( "${has_service}" -eq 0 ); then
            _remote_container logs "$@" wikantik
        else
            _remote_container logs "$@"
        fi
    fi
}
cmd_shell() {
    local svc="${1:-wikantik}"
    _remote_container shell "${svc}"
}
cmd_psql() {
    _remote_container psql "$@"
}
cmd_migrate() {
    _remote_container migrate "$@"
}
```

Update the dispatch switch:

```bash
case "${SUBCOMMAND}" in
    __selftest) cmd_selftest "$@" ;;
    up)         cmd_up "$@" ;;
    down)       cmd_down "$@" ;;
    restart)    cmd_restart "$@" ;;
    logs)       cmd_logs "$@" ;;
    shell)      cmd_shell "$@" ;;
    psql)       cmd_psql "$@" ;;
    migrate)    cmd_migrate "$@" ;;
    *) echo "remote.sh: unknown subcommand: ${SUBCOMMAND}" >&2
       echo "           run: bin/remote.sh --help" >&2
       exit 2 ;;
esac
```

- [ ] **Step 4: Run test, expect PASS**

Run: `bin/tests/test-remote.sh`
Expected: green.

- [ ] **Step 5: shellcheck**

Run: `shellcheck bin/remote.sh`

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): pass-through subcommands (up/down/restart/logs/shell/psql/migrate)"
```

---

## Task 6: `bootstrap` — first-time remote setup

**Files:**
- Modify: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertions**

Append to `bin/tests/test-remote.sh`:

```bash
test_bootstrap_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"

    out="$(cd "${tmp}" && ./remote.sh --dry-run bootstrap 2>&1)" \
        || fail "bootstrap dry-run non-zero: ${out}"

    # Must check for docker on remote
    echo "${out}" | grep -q "command -v docker" \
        || fail "bootstrap did not check for remote docker: ${out}"
    # Must create the three remote dirs
    echo "${out}" | grep -q "mkdir -p" \
        || fail "bootstrap did not create remote dirs: ${out}"
    echo "${out}" | grep -q "/tmp/pages" \
        || fail "bootstrap did not reference REMOTE_PAGES_DIR: ${out}"
    echo "${out}" | grep -q "/tmp/backups" \
        || fail "bootstrap did not reference REMOTE_BACKUP_DIR: ${out}"
    # Must rsync compose files
    echo "${out}" | grep -q "docker-compose.yml" \
        || fail "bootstrap did not rsync compose files: ${out}"
    echo "${out}" | grep -q "docker-compose.prod.yml" \
        || fail "bootstrap did not rsync prod overlay: ${out}"
    # Must NOT run up -d
    echo "${out}" | grep -q "container.sh -e prod up" \
        && fail "bootstrap must NOT run up -d (that's deploy's job): ${out}"

    rm -rf "${tmp}"
    ok "bootstrap dry-run does the right things, does not up -d"
}
test_bootstrap_dry_run
```

- [ ] **Step 2: Run test, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL — unknown subcommand `bootstrap`.

- [ ] **Step 3: Implement `cmd_bootstrap`**

Add before the dispatch switch:

```bash
cmd_bootstrap() {
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
bootstrap — first-time remote setup. Idempotent; safe to re-run.

Usage: bin/remote.sh bootstrap [--dry-run]

Steps:
  1. Verify `docker` and `docker compose` are present on REMOTE_HOST.
  2. Create REMOTE_REPO_DIR, REMOTE_PAGES_DIR, REMOTE_BACKUP_DIR on the remote.
  3. Create local SSH_CONTROL_DIR (mode 0700) if absent.
  4. rsync docker-compose.yml + docker-compose.prod.yml + docker/ + bin/ + .env
     to REMOTE_REPO_DIR.

Does NOT:
  - Install docker (distro-specific; if step 1 fails the script tells you what to install).
  - Run `up -d` — that happens on the first `deploy` invocation, which is when the
    wikantik image first lands on the remote.
EOF
            return 0
            ;;
    esac

    # Local: ensure the ControlMaster dir exists with 0700 (the _ssh helper also
    # does this, but doing it up-front keeps bootstrap self-contained).
    if [ "${DRY_RUN}" -eq 0 ]( "${DRY_RUN}" -eq 0 ); then
        mkdir -p "${SSH_CONTROL_DIR}" && chmod 700 "${SSH_CONTROL_DIR}"
    else
        echo "[dry-run] mkdir -p ${SSH_CONTROL_DIR} && chmod 700 ${SSH_CONTROL_DIR}"
    fi

    # 1. Verify docker on remote
    _ssh "command -v docker >/dev/null 2>&1 || { echo 'docker not found on ${REMOTE_HOST} — install docker + docker compose, then re-run bootstrap.' >&2; exit 2; }"
    _ssh "docker compose version >/dev/null 2>&1 || { echo 'docker compose plugin not found on ${REMOTE_HOST} — install it, then re-run bootstrap.' >&2; exit 2; }"

    # 2. Create remote directories
    _ssh "mkdir -p $(printf '%q' "${REMOTE_REPO_DIR}") $(printf '%q' "${REMOTE_PAGES_DIR}") $(printf '%q' "${REMOTE_BACKUP_DIR}")"

    # 3. rsync compose stack + bin + docker/ + .env (if present)
    local files=(docker-compose.yml docker-compose.prod.yml)
    # bin/ and docker/ contain helper scripts the remote container.sh invokes
    _rsync -avz --update --chmod=F644 \
        "${files[@]}" \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/"
    _rsync -avz --update --chmod=F755 \
        --include='*/' --include='*.sh' --include='*.sql' --exclude='*' \
        bin/ "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/bin/"
    _rsync -avz --update \
        docker/ "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/docker/"

    if [ -f .env ]( -f .env ); then
        _rsync -avz --chmod=F600 .env "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
    else
        echo "remote.sh: warning — no local .env; remote will not start without one." >&2
        echo "           Create .env locally (copy from .env.example) and re-run bootstrap." >&2
    fi

    echo "Bootstrap complete on ${REMOTE_HOST}."
    echo "Next: bin/remote.sh deploy"
}
```

Add `bootstrap)` to the dispatch switch:

```bash
    bootstrap)  cmd_bootstrap "$@" ;;
```

- [ ] **Step 4: Run test, expect PASS**

Run: `bin/tests/test-remote.sh`

- [ ] **Step 5: shellcheck**

Run: `shellcheck bin/remote.sh`

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): bootstrap subcommand for first-time remote setup"
```

---

## Task 7: `deploy` — the main event

**Files:**
- Modify: `bin/remote.sh`

`deploy` is the biggest subcommand. It:
1. Optionally rebuilds locally (`--skip-build` to skip).
2. Acquires a remote flock.
3. rsyncs compose + .env updates.
4. Tags current `wikantik:latest` as `wikantik:rollback` on the remote.
5. Streams the new image via `docker save | ssh 'docker load'`.
6. `up -d` via remote `container.sh`.
7. Polls `HEALTH_URL` for up to `HEALTH_TIMEOUT` seconds.
8. On failure, auto-rollback + print last 50 log lines + exit 1.

- [ ] **Step 1: Add smoke-test assertions**

Append to `bin/tests/test-remote.sh`:

```bash
test_deploy_dry_run_shape() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"

    # --skip-build avoids running mvn / docker build in dry-run mode.
    out="$(cd "${tmp}" && ./remote.sh --dry-run deploy --skip-build 2>&1)" \
        || fail "deploy dry-run non-zero: ${out}"

    # Tag-rollback step
    echo "${out}" | grep -q "docker tag wikantik:latest wikantik:rollback" \
        || fail "deploy did not tag prior image as :rollback: ${out}"
    # Image stream
    echo "${out}" | grep -q "docker save wikantik:latest" \
        || fail "deploy did not docker-save the image: ${out}"
    echo "${out}" | grep -q "docker load" \
        || fail "deploy did not docker-load on the remote: ${out}"
    # Remote up
    echo "${out}" | grep -q "container.sh -e prod up" \
        || fail "deploy did not up -d on remote: ${out}"
    # Lock acquisition
    echo "${out}" | grep -q "flock" \
        || fail "deploy did not acquire flock: ${out}"
    # rsync .env + compose
    echo "${out}" | grep -q "docker-compose.prod.yml" \
        || fail "deploy did not rsync prod overlay: ${out}"

    rm -rf "${tmp}"
    ok "deploy dry-run includes lock, tag-rollback, image stream, up, sync"
}

test_deploy_help() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    out="$(cd "${tmp}" && ./remote.sh deploy --help 2>&1)" \
        || fail "deploy --help non-zero"
    echo "${out}" | grep -q "health-timeout" \
        || fail "deploy --help does not document --health-timeout"
    rm -rf "${tmp}"
    ok "deploy --help documents flags"
}
test_deploy_dry_run_shape
test_deploy_help
```

- [ ] **Step 2: Run tests, expect FAIL**

Run: `bin/tests/test-remote.sh`
Expected: FAIL — unknown subcommand `deploy`.

- [ ] **Step 3: Implement `cmd_deploy`**

Add before the dispatch switch:

```bash
cmd_deploy() {
    local skip_build=0
    local health_timeout="${HEALTH_TIMEOUT}"

    while [ $# -gt 0 ]( $# -gt 0 ); do
        case "$1" in
            -h|--help)
                cat <<'EOF'
deploy — build locally, push image over ssh, up -d on remote, health-poll, auto-rollback on failure.

Usage: bin/remote.sh [--dry-run] deploy [--skip-build] [--health-timeout=N]

Options:
  --skip-build           skip mvn + docker compose build (use existing wikantik:latest)
  --health-timeout=N     seconds to wait for /api/health (default: ${HEALTH_TIMEOUT}, from remote.env)

Flow:
  1. mvn clean install -T 1C -DskipITs   (unless --skip-build)
  2. docker compose build wikantik
  3. flock --nonblock on the remote
  4. rsync compose + .env to REMOTE_REPO_DIR
  5. tag remote wikantik:latest as wikantik:rollback   (silent on first deploy)
  6. docker save | ssh 'docker load'
  7. container.sh -e prod up -d
  8. poll HEALTH_URL every 3s up to --health-timeout
  9. on failure: re-promote :rollback, print last 50 wikantik log lines, exit 1
EOF
                return 0 ;;
            --skip-build) skip_build=1; shift ;;
            --health-timeout=*) health_timeout="${1#*=}"; shift ;;
            --health-timeout)   health_timeout="$2"; shift 2 ;;
            *) echo "deploy: unknown flag: $1" >&2; exit 2 ;;
        esac
    done

    # ---------- 1+2: local build ----------
    if [ "${skip_build}" -eq 0 ]( "${skip_build}" -eq 0 ); then
        _run mvn clean install -T 1C -DskipITs
        _run docker compose -f docker-compose.yml build wikantik
    else
        echo "remote.sh: --skip-build set; reusing wikantik:latest from local docker daemon."
    fi

    # ---------- 3: acquire lock ----------
    _acquire_deploy_lock

    # ---------- 4: rsync compose + .env ----------
    _rsync -avz --update --chmod=F644 \
        docker-compose.yml docker-compose.prod.yml \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/"
    if [ -f .env ]( -f .env ); then
        _rsync -avz --chmod=F600 .env "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
    fi

    # ---------- 5: tag prior image as :rollback (silent on first deploy) ----------
    _ssh "docker tag wikantik:latest wikantik:rollback 2>/dev/null || true"

    # ---------- 6: stream image ----------
    # No gzip — 1–10 Gb LAN, CPU > wire-time at compress=1. Reachable
    # in dry-run by composing the commands and routing through _run.
    if [ "${DRY_RUN}" -eq 1 ]( "${DRY_RUN}" -eq 1 ); then
        echo "[dry-run] docker save wikantik:latest | ssh ... 'docker load'"
        # Emit a faux load line for tests/grep:
        echo "[dry-run] (remote) docker load"
    else
        local ssh_inline="ssh -o ControlMaster=auto -o ControlPath=${SSH_CONTROL_DIR}/%C -o ControlPersist=10m -o StrictHostKeyChecking=accept-new"
        if [ -n "${SSH_KEY:-}" ]( -n "${SSH_KEY:-}" ); then ssh_inline+=" -i ${SSH_KEY}"; fi
        docker save wikantik:latest | ${ssh_inline} "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
    fi

    # ---------- 7: up -d via remote container.sh ----------
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod up -d"

    # ---------- 8: health poll ----------
    if [ "${DRY_RUN}" -eq 1 ]( "${DRY_RUN}" -eq 1 ); then
        echo "[dry-run] poll ${HEALTH_URL} every 3s up to ${health_timeout}s"
        return 0
    fi
    local deadline=$(( $(date +%s) + health_timeout ))
    while (( $(date +%s) < deadline )); do
        if curl -sfo /dev/null --max-time 5 "${HEALTH_URL}"; then
            echo "Deploy healthy: ${HEALTH_URL} returned 200."
            return 0
        fi
        sleep 3
    done

    # ---------- 9: failure → auto-rollback ----------
    echo "remote.sh: ${HEALTH_URL} did not return 200 within ${health_timeout}s; rolling back." >&2
    _ssh "docker image inspect wikantik:rollback >/dev/null 2>&1 \
          && docker tag wikantik:rollback wikantik:latest \
          && cd $(printf '%q' "${REMOTE_REPO_DIR}") \
          && bin/container.sh -e prod up -d --force-recreate wikantik \
          || echo 'no :rollback image present — manual recovery required.' >&2"
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod logs --tail=50 wikantik" >&2 || true
    exit 1
}
```

Add to the dispatch switch:

```bash
    deploy)     cmd_deploy "$@" ;;
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `bin/tests/test-remote.sh`

- [ ] **Step 5: shellcheck**

Run: `shellcheck bin/remote.sh`

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): deploy subcommand with tag-rollback, image stream, health-poll, auto-rollback"
```

---

## Task 8: `rollback`

**Files:**
- Modify: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertion**

Append to `bin/tests/test-remote.sh`:

```bash
test_rollback_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    out="$(cd "${tmp}" && ./remote.sh --dry-run rollback 2>&1)" \
        || fail "rollback dry-run non-zero: ${out}"
    echo "${out}" | grep -q "docker tag wikantik:rollback wikantik:latest" \
        || fail "rollback did not re-promote :rollback: ${out}"
    echo "${out}" | grep -q "force-recreate wikantik" \
        || fail "rollback did not force-recreate wikantik: ${out}"
    echo "${out}" | grep -q "flock" \
        || fail "rollback did not acquire deploy lock: ${out}"
    rm -rf "${tmp}"
    ok "rollback dry-run re-promotes :rollback, recreates, acquires lock"
}
test_rollback_dry_run
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement `cmd_rollback`**

Insert before the dispatch switch:

```bash
cmd_rollback() {
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
rollback — re-promote wikantik:rollback to wikantik:latest, force-recreate the service.

Usage: bin/remote.sh [--dry-run] rollback

Fails if no :rollback image exists on the remote (means no successful prior
deploy has been recorded). In that case, recovery is manual: re-deploy a
known-good build or restore from backup. Acquires the same deploy lock
as `deploy` and `restore`.
EOF
            return 0 ;;
    esac

    _acquire_deploy_lock
    _ssh "docker image inspect wikantik:rollback >/dev/null 2>&1 \
          || { echo 'no wikantik:rollback image on ${REMOTE_HOST} — nothing to roll back to.' >&2; exit 1; }"
    _ssh "docker tag wikantik:rollback wikantik:latest"
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod up -d --force-recreate wikantik"
    echo "Rollback complete on ${REMOTE_HOST}."
}
```

Add to dispatch:

```bash
    rollback)   cmd_rollback "$@" ;;
```

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: shellcheck**

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): rollback subcommand"
```

---

## Task 9: `pages-push` and `pages-pull`

**Files:**
- Modify: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertions**

Append to `bin/tests/test-remote.sh`:

```bash
test_pages_push_no_delete_by_default() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    mkdir -p "${tmp}/some-pages"
    out="$(cd "${tmp}" && ./remote.sh --dry-run pages-push some-pages 2>&1)" \
        || fail "pages-push dry-run non-zero: ${out}"
    echo "${out}" | grep -q "rsync" \
        || fail "pages-push did not call rsync: ${out}"
    echo "${out}" | grep -q -- "--delete" \
        && fail "pages-push must NOT pass --delete by default: ${out}"
    echo "${out}" | grep -q ":/tmp/pages" \
        || fail "pages-push did not target REMOTE_PAGES_DIR: ${out}"
    rm -rf "${tmp}"
    ok "pages-push (default) does not pass --delete"
}

test_pages_push_mirror_passes_delete() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    mkdir -p "${tmp}/some-pages"
    # --mirror --yes skips the confirmation prompt in scripted use.
    out="$(cd "${tmp}" && ./remote.sh --dry-run pages-push some-pages --mirror --yes 2>&1)" \
        || fail "pages-push --mirror dry-run non-zero: ${out}"
    echo "${out}" | grep -q -- "--delete" \
        || fail "pages-push --mirror must pass --delete: ${out}"
    rm -rf "${tmp}"
    ok "pages-push --mirror --yes passes --delete"
}

test_pages_pull_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    out="$(cd "${tmp}" && ./remote.sh --dry-run pages-pull "${tmp}/dest" 2>&1)" \
        || fail "pages-pull dry-run non-zero: ${out}"
    echo "${out}" | grep -q ":/tmp/pages/" \
        || fail "pages-pull did not read from REMOTE_PAGES_DIR: ${out}"
    echo "${out}" | grep -q -- "--delete" \
        && fail "pages-pull must never pass --delete (read-only): ${out}"
    rm -rf "${tmp}"
    ok "pages-pull is read-only"
}
test_pages_push_no_delete_by_default
test_pages_push_mirror_passes_delete
test_pages_pull_dry_run
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

Insert before the dispatch switch:

```bash
cmd_pages_push() {
    local local_dir=""
    local mirror=0
    local assume_yes=0
    while [ $# -gt 0 ]( $# -gt 0 ); do
        case "$1" in
            -h|--help)
                cat <<'EOF'
pages-push — rsync a local pages directory to REMOTE_PAGES_DIR.

Usage:
  bin/remote.sh [--dry-run] pages-push LOCAL_DIR
  bin/remote.sh [--dry-run] pages-push LOCAL_DIR --mirror [--yes]

Default: no --delete. Files present on the remote but missing locally survive.
--mirror: opts in to rsync --delete. By default, prompts for confirmation
          showing the files that would be deleted; --yes skips the prompt.
EOF
                return 0 ;;
            --mirror) mirror=1; shift ;;
            --yes)    assume_yes=1; shift ;;
            -*) echo "pages-push: unknown flag: $1" >&2; exit 2 ;;
            *) if [ -z "${local_dir}" ]( -z "${local_dir}" ); then local_dir="$1"; shift
               else echo "pages-push: unexpected arg: $1" >&2; exit 2
               fi ;;
        esac
    done
    [ -n "${local_dir}" ]( -n "${local_dir}" ) || { echo "pages-push: missing LOCAL_DIR" >&2; exit 2; }
    [ -d "${local_dir}" ]( -d "${local_dir}" ) || { echo "pages-push: not a directory: ${local_dir}" >&2; exit 2; }

    local rsync_args=(-avz --update)
    if [ "${mirror}" -eq 1 ]( "${mirror}" -eq 1 ); then
        if [ "${assume_yes}" -ne 1 && "${DRY_RUN}" -ne 1 ]( "${assume_yes}" -ne 1 && "${DRY_RUN}" -ne 1 ); then
            echo "pages-push --mirror would --delete files on ${REMOTE_HOST}:${REMOTE_PAGES_DIR}."
            echo "Preview of deletions:"
            _rsync -avzn --delete "${local_dir%/}/" \
                "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PAGES_DIR}/" \
                | grep -E '^deleting ' || echo "  (none — remote is already a subset of local)"
            read -r -p "Proceed? [y/N] " yn
            [[ "${yn}" =~ ^[Yy]$ ]] || { echo "Aborted."; return 0; }
        fi
        rsync_args+=(--delete)
    fi

    _rsync "${rsync_args[@]}" "${local_dir%/}/" \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PAGES_DIR}/"
}

cmd_pages_pull() {
    local local_dir=""
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
pages-pull — rsync REMOTE_PAGES_DIR to a local directory. Read-only (never deletes locally).

Usage: bin/remote.sh [--dry-run] pages-pull LOCAL_DIR
EOF
            return 0 ;;
    esac
    local_dir="${1:-}"
    [ -n "${local_dir}" ]( -n "${local_dir}" ) || { echo "pages-pull: missing LOCAL_DIR" >&2; exit 2; }
    mkdir -p "${local_dir}"
    _rsync -avz --update \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PAGES_DIR}/" \
        "${local_dir%/}/"
}
```

Add to dispatch:

```bash
    pages-push) cmd_pages_push "$@" ;;
    pages-pull) cmd_pages_pull "$@" ;;
```

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: shellcheck**

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): pages-push (no --delete default) and pages-pull"
```

---

## Task 10: `backup-trigger`, `backup-pull`, `restore`

**Files:**
- Modify: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertions**

Append to `bin/tests/test-remote.sh`:

```bash
test_backup_subcommands_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"

    out="$(cd "${tmp}" && ./remote.sh --dry-run backup-trigger 2>&1)" \
        || fail "backup-trigger dry-run non-zero: ${out}"
    echo "${out}" | grep -q "container.sh -e prod backup" \
        || fail "backup-trigger did not invoke remote backup: ${out}"
    echo "${out}" | grep -q "daily" \
        || fail "backup-trigger default tier should be daily: ${out}"

    out="$(cd "${tmp}" && ./remote.sh --dry-run backup-pull 2026-05-14 2>&1)" \
        || fail "backup-pull dry-run non-zero: ${out}"
    echo "${out}" | grep -q "/tmp/backups/" \
        || fail "backup-pull did not source REMOTE_BACKUP_DIR: ${out}"

    out="$(cd "${tmp}" && ./remote.sh --dry-run restore /backups/daily/2026-05-14 2>&1)" \
        || fail "restore dry-run non-zero: ${out}"
    echo "${out}" | grep -q "container.sh -e prod restore" \
        || fail "restore did not invoke remote restore: ${out}"
    echo "${out}" | grep -q "flock" \
        || fail "restore did not acquire deploy lock: ${out}"

    rm -rf "${tmp}"
    ok "backup-trigger / backup-pull / restore dry-runs are well-formed"
}
test_backup_subcommands_dry_run
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

Insert before the dispatch switch:

```bash
cmd_backup_trigger() {
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
backup-trigger — invoke the prod backup sidecar.

Usage: bin/remote.sh [--dry-run] backup-trigger [TIER]
  TIER: daily | weekly | monthly  (default: daily)
EOF
            return 0 ;;
    esac
    local tier="${1:-daily}"
    _remote_container backup "${tier}"
}

cmd_backup_pull() {
    local date_arg=""
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
backup-pull — rsync a backup snapshot from REMOTE_BACKUP_DIR back to the dev box.

Usage: bin/remote.sh [--dry-run] backup-pull [DATE]
  DATE: YYYY-MM-DD subdir under REMOTE_BACKUP_DIR/daily/  (default: latest)

The snapshot is rsynced into ./backups/<DATE>/ locally.
EOF
            return 0 ;;
    esac
    date_arg="${1:-}"
    local remote_src="${REMOTE_BACKUP_DIR}/daily/"
    if [ -n "${date_arg}" ]( -n "${date_arg}" ); then
        remote_src="${REMOTE_BACKUP_DIR}/daily/${date_arg}/"
    fi
    mkdir -p backups
    _rsync -avz --update \
        "${REMOTE_USER}@${REMOTE_HOST}:${remote_src}" \
        "backups/${date_arg:-}"
}

cmd_restore() {
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
restore — invoke the prod backup sidecar's restore.sh with a remote backup path.

Usage: bin/remote.sh [--dry-run] restore REMOTE_PATH
  REMOTE_PATH: e.g. /backups/daily/2026-05-14  (path inside the backup sidecar)

The wikantik container is brought down for restore and back up afterward.
Acquires the same deploy lock as `deploy` and `rollback`.
EOF
            return 0 ;;
    esac
    local path="${1:-}"
    [ -n "${path}" ]( -n "${path}" ) || { echo "restore: missing REMOTE_PATH" >&2; exit 2; }
    _acquire_deploy_lock
    _remote_container down
    _remote_container restore "${path}"
    _remote_container up -d
}
```

Dispatch additions:

```bash
    backup-trigger) cmd_backup_trigger "$@" ;;
    backup-pull)    cmd_backup_pull "$@" ;;
    restore)        cmd_restore "$@" ;;
```

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: shellcheck**

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): backup-trigger, backup-pull, restore subcommands"
```

---

## Task 11: `status` — one-screen summary

**Files:**
- Modify: `bin/remote.sh`

- [ ] **Step 1: Add smoke-test assertion**

Append to `bin/tests/test-remote.sh`:

```bash
test_status_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    cp bin/remote.sh "${tmp}/remote.sh"
    make_fake_remote_env "${tmp}"
    out="$(cd "${tmp}" && ./remote.sh --dry-run status 2>&1)" \
        || fail "status dry-run non-zero: ${out}"
    for needle in "container.sh -e prod ps" "/api/health" "df " "du " "logs --tail"; do
        echo "${out}" | grep -qF "${needle}" \
            || fail "status missing '${needle}': ${out}"
    done
    rm -rf "${tmp}"
    ok "status dry-run gathers ps + health + df + pages-size + log tail"
}
test_status_dry_run
```

- [ ] **Step 2: Run, expect FAIL**

- [ ] **Step 3: Implement**

Insert before dispatch:

```bash
cmd_status() {
    case "${1:-}" in
        -h|--help)
            cat <<'EOF'
status — one-screen summary of the remote deployment.

Usage: bin/remote.sh status

Prints:
  - docker compose ps                                    (container state)
  - HEALTH_URL → curl status                             (app health)
  - df -h on the REMOTE_PAGES_DIR partition              (disk free)
  - du -sh REMOTE_PAGES_DIR REMOTE_BACKUP_DIR            (data size)
  - last 10 wikantik log lines                           (recent activity)
EOF
            return 0 ;;
    esac

    echo "=== ${REMOTE_HOST} — container state ==="
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod ps"

    echo
    echo "=== health (${HEALTH_URL}) ==="
    if [ "${DRY_RUN}" -eq 1 ]( "${DRY_RUN}" -eq 1 ); then
        echo "[dry-run] curl -sfo /dev/null -w 'HTTP %{http_code}\\n' ${HEALTH_URL}"
    else
        curl -sfo /dev/null -w 'HTTP %{http_code}\n' --max-time 5 "${HEALTH_URL}" \
            || echo "(no response)"
    fi

    echo
    echo "=== disk + data size ==="
    _ssh "df -h $(printf '%q' "${REMOTE_PAGES_DIR}") || df -h /"
    _ssh "du -sh $(printf '%q' "${REMOTE_PAGES_DIR}") $(printf '%q' "${REMOTE_BACKUP_DIR}") 2>/dev/null || true"

    echo
    echo "=== last 10 wikantik log lines ==="
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod logs --tail=10 wikantik"
}
```

Dispatch:

```bash
    status)     cmd_status "$@" ;;
```

- [ ] **Step 4: Run, expect PASS**

- [ ] **Step 5: shellcheck**

- [ ] **Step 6: Commit**

```bash
git add bin/remote.sh bin/tests/test-remote.sh
git commit -m "feat(deploy): status subcommand for at-a-glance remote health"
```

---

## Task 12: shellcheck clean + CLAUDE.md note + final regression

**Files:**
- Modify: `bin/remote.sh` (if shellcheck flags anything)
- Modify: `CLAUDE.md`

- [ ] **Step 1: Full shellcheck pass**

Run: `shellcheck bin/remote.sh bin/tests/test-remote.sh`
Expected: zero warnings/errors. Fix anything that fires. Use targeted `# shellcheck disable=SCxxxx` only with a comment explaining why.

- [ ] **Step 2: Add CLAUDE.md note**

Open `CLAUDE.md`. Find the "Local Deployment (Tomcat 11)" section. Append a new subsection after the container deployment section, before "`bin/` script conventions":

```markdown
### Remote container deployment over ssh

`bin/remote.sh` is the single entry point for deploying and administering
Wikantik on a remote host over ssh. It wraps `bin/container.sh` on the
remote and adds image transfer (`docker save | ssh 'docker load'`), pages
rsync, and a deploy lock. Configuration lives in `remote.env` at the repo
root (copy from `remote.env.example`; gitignored). Every state-changing
subcommand accepts `--dry-run`.

```bash
bin/remote.sh --help                          # subcommand list
bin/remote.sh bootstrap                       # first-time remote setup
bin/remote.sh deploy                          # local build → ssh push → up -d → health-poll
bin/remote.sh status                          # container ps + health + disk
bin/remote.sh pages-push docs/wikantik-pages  # rsync pages to remote (no --delete by default)
bin/remote.sh rollback                        # re-promote :rollback image
```

Prod content lives at `${WIKANTIK_PAGES_DIR}` on the remote host as a
bind mount — so `rsync` is the source of truth for the page tree,
independent of container lifecycle. Design doc:
[docs/superpowers/specs/2026-05-14-remote-container-admin-design.md](docs/superpowers/specs/2026-05-14-remote-container-admin-design.md).
```

- [ ] **Step 3: Final test sweep**

Run: `bin/tests/test-remote.sh`
Expected: all `ok:` lines, zero `FAIL:`.

Run: `bash -n bin/remote.sh && bash -n bin/tests/test-remote.sh`
Expected: silent (no syntax errors).

Run: `shellcheck bin/remote.sh bin/tests/test-remote.sh`
Expected: silent.

Run (compose still valid in dev too):
```
docker compose -f docker-compose.yml -f docker-compose.dev.yml config >/dev/null
WIKANTIK_PAGES_DIR=/srv/wikantik/pages docker compose -f docker-compose.yml -f docker-compose.prod.yml config >/dev/null
```
Expected: both silent (zero exit).

- [ ] **Step 4: Commit**

```bash
git add bin/remote.sh CLAUDE.md
git commit -m "docs: note bin/remote.sh in CLAUDE.md; final shellcheck pass"
```

---

## Acceptance — manual end-to-end (out of TDD loop)

These steps cannot be automated without a throwaway VM. Run them manually once before declaring the feature done:

1. Stand up a temporary remote (LAN VM, container, or VPS). Install docker + docker compose. Make sure ssh works key-only.
2. `cp remote.env.example remote.env`; fill in the test host.
3. `cp .env.example .env`; fill in DB credentials.
4. `bin/remote.sh bootstrap` — verify the three remote dirs exist (`ssh remote 'ls -la /srv/wikantik/pages /srv/wikantik/backups'`).
5. `bin/remote.sh deploy` — verify `/api/health` returns 200 from the remote inside the timeout.
6. `bin/remote.sh status` — confirm output shape.
7. `bin/remote.sh pages-push docs/wikantik-pages` — confirm files appear under `${REMOTE_PAGES_DIR}` on the remote.
8. Force a failure: `ssh remote 'docker rm -f $(docker ps -q --filter name=wikantik)' && bin/remote.sh deploy --skip-build` — verify health-poll catches it and auto-rollback runs (rollback image promoted; last 50 logs printed).
9. `bin/remote.sh rollback` (manually, no failure) — verify it re-promotes and recreates.
10. `bin/remote.sh down` — verify the stack stops cleanly.

Mark complete when all 10 steps pass.

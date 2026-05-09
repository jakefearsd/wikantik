#!/usr/bin/env bash
#
# bin/container.sh — top-level wrapper around docker compose for the
# Wikantik container stack. Drives build, run, logs, exec, backup, and
# restore against the canonical service set (db, wikantik, backup) defined
# under the four docker-compose*.yml files at the repo root.
#
# Subcommands:
#   build [--no-cache] [--pull]   build the wikantik image
#   up [-d|--detach]              start the stack (foreground by default)
#   down [--volumes]              stop and remove containers
#   restart [SERVICE]             recreate one service (default: wikantik)
#   ps                            show container status
#   logs [-f|--follow] [SERVICE]  stream logs (default: wikantik)
#   shell [SERVICE]               exec /bin/bash (default: wikantik)
#   psql [-- PSQL_ARGS...]        exec psql in the db service as POSTGRES_USER
#   migrate                       run bin/db/migrate.sh inside the wikantik container
#   backup [TIER]                 trigger a backup via the prod backup sidecar
#                                 (TIER ∈ daily|weekly|monthly, default daily)
#   restore PATH                  restore from /backups/<tier>/<date>/ via sidecar
#   smoke-test                    bring up docker-compose.test.yml, hit /api/health,
#                                 tear down. Verifies the build before a release.
#
# Environments (-e / --env):
#   dev    (default) base + docker-compose.dev.yml — local dev overlays
#   prod   base + docker-compose.prod.yml          — backup sidecar enabled
#   test   docker-compose.test.yml                 — alt ports, ephemeral DB
#   base   base only                               — no overlays
#
# Usage:
#   bin/container.sh --help                         # this help
#   bin/container.sh <subcommand> --help            # subcommand-specific help
#   bin/container.sh -e prod up -d                  # production-style start
#   bin/container.sh logs -f wikantik               # tail wikantik logs
#   bin/container.sh psql -- -c '\dt'               # list tables
#   bin/container.sh smoke-test                     # ephemeral up/health/down
#
# Configuration:
#   .env at repo root provides POSTGRES_*, WIKANTIK_*, MCP_* variables that
#   docker compose substitutes into the compose files. Copy from
#   .env.example for a starting point.
#
# Exit codes:
#   0   success
#   1   subcommand error (build failed, container not found, etc.)
#   2   usage error (unknown subcommand, missing args)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

# ---------- Help / usage --------------------------------------------------

print_main_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

print_subhelp() {
    case "$1" in
        build)      cat <<'EOF'
build — build the wikantik image (multi-stage: Maven + Vite + Tomcat).

Usage: bin/container.sh [-e ENV] build [--no-cache] [--pull]

Options:
  --no-cache    discard the build cache (slow; full rebuild)
  --pull        pull base images before build

Notes:
  - The build is independent of -e ENV (the same Wikantik.war ships to all
    environments) — this command always uses the base docker-compose.yml.
  - Subsequent `up` invocations pick up the image automatically.
EOF
            ;;
        up)         cat <<'EOF'
up — start the stack.

Usage: bin/container.sh [-e ENV] up [-d|--detach]

Options:
  -d, --detach  start in the background (default: foreground, Ctrl-C to stop)

Equivalent to:
  docker compose -f docker-compose.yml -f docker-compose.<env>.yml up [-d]
EOF
            ;;
        down)       cat <<'EOF'
down — stop and remove containers (volumes preserved by default).

Usage: bin/container.sh [-e ENV] down [--volumes]

Options:
  --volumes     also remove pgdata, wikantik-pages, wikantik-work, etc.
                ⚠ DESTROYS local content. Operator confirmation prompt.
EOF
            ;;
        restart)    cat <<'EOF'
restart — recreate a service without rebuilding the image.

Usage: bin/container.sh [-e ENV] restart [SERVICE]
  SERVICE: db | wikantik | backup (default: wikantik)
EOF
            ;;
        ps)         echo "ps — show container status. Aliases docker compose ps." ;;
        logs)       cat <<'EOF'
logs — show container logs.

Usage: bin/container.sh [-e ENV] logs [-f|--follow] [SERVICE]
  SERVICE: db | wikantik | backup (default: wikantik)
  -f, --follow:  stream new lines as they arrive (Ctrl-C to stop).
EOF
            ;;
        shell)      cat <<'EOF'
shell — exec /bin/bash in a running container.

Usage: bin/container.sh [-e ENV] shell [SERVICE]
  SERVICE: db | wikantik | backup (default: wikantik)
EOF
            ;;
        psql)       cat <<'EOF'
psql — exec psql in the db container as POSTGRES_USER.

Usage: bin/container.sh [-e ENV] psql [-- PSQL_ARGS...]

Examples:
  bin/container.sh psql -- -c '\dt'              # list tables
  bin/container.sh psql -- -c 'SELECT version()' # PG version
  bin/container.sh psql                          # interactive prompt
EOF
            ;;
        migrate)    cat <<'EOF'
migrate — run bin/db/migrate.sh inside the wikantik container.

Usage: bin/container.sh [-e ENV] migrate [--status]

Notes:
  - entrypoint.sh runs migrations on container start automatically. This
    subcommand is for ad-hoc runs against a live container without
    restarting it (e.g. when a new V*.sql is added during a long-running
    deployment).
  - --status reports the applied-migration list without applying anything.
EOF
            ;;
        backup)     cat <<'EOF'
backup — trigger a backup via the prod backup sidecar.

Usage: bin/container.sh [-e ENV] backup [TIER]
  TIER: daily | weekly | monthly (default: daily)

Requires:
  - -e prod (the backup sidecar is only defined in docker-compose.prod.yml).
  - The backup sidecar must be running (it's started by `up` under -e prod).

Output lands in ./backups/<tier>/<YYYY-MM-DD>/ on the host.
EOF
            ;;
        restore)    cat <<'EOF'
restore — restore both the database and page tree from a backup snapshot.

Usage: bin/container.sh [-e ENV] restore /backups/<tier>/<YYYY-MM-DD>

Procedure (this command runs steps 3 + 4):
  1. Ensure the wikantik container is stopped (operator responsibility).
  2. Identify the snapshot path: ls ./backups/daily/
  3. Run this command with the path inside the sidecar's mount.
  4. Start wikantik back up: bin/container.sh up -d.

Requires -e prod (sidecar lives in the prod compose file).
EOF
            ;;
        smoke-test) cat <<'EOF'
smoke-test — bring up the test stack, hit /api/health, tear down.

Usage: bin/container.sh smoke-test [--keep]

Procedure:
  1. docker compose -f docker-compose.test.yml up -d --build
  2. Poll http://localhost:18080/api/health until UP (or 60s timeout)
  3. docker compose -f docker-compose.test.yml down -v   (unless --keep)

Returns 0 if the wikantik container reports healthy; 1 if anything in
build / start / health-check fails. Pure verification — does not affect
the dev or prod stacks (test uses port 18080, db on 15432).
EOF
            ;;
        *)
            echo "Unknown subcommand: $1" >&2
            return 2
            ;;
    esac
}

# ---------- Compose file selection ---------------------------------------

# Default environment is "dev" — matches the operator's most-common case.
ENV=dev
COMPOSE_BASE=(-f docker-compose.yml)

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            if [[ -n "${2:-}" && "${2:-}" != "-"* ]]; then
                # `bin/container.sh --help <subcommand>` — show subcommand help.
                shift
                print_subhelp "$1"
                exit 0
            fi
            print_main_help
            exit 0
            ;;
        -e|--env)
            ENV="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

case "${ENV}" in
    dev)
        COMPOSE_FILES=("${COMPOSE_BASE[@]}" -f docker-compose.dev.yml)
        ;;
    prod)
        COMPOSE_FILES=("${COMPOSE_BASE[@]}" -f docker-compose.prod.yml)
        ;;
    test)
        COMPOSE_FILES=(-f docker-compose.test.yml)
        ;;
    base)
        COMPOSE_FILES=("${COMPOSE_BASE[@]}")
        ;;
    *)
        echo "Unknown env: ${ENV} (expected dev | prod | test | base)" >&2
        exit 2
        ;;
esac

# ---------- Subcommand dispatch ------------------------------------------

if [[ $# -eq 0 ]]; then
    print_main_help
    exit 0
fi

SUBCOMMAND="$1"
shift

# Per-subcommand --help
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    print_subhelp "${SUBCOMMAND}"
    exit 0
fi

case "${SUBCOMMAND}" in
    build)
        # Always build against the base compose (one image fits all envs).
        exec docker compose "${COMPOSE_BASE[@]}" build "$@"
        ;;
    up)
        exec docker compose "${COMPOSE_FILES[@]}" up "$@"
        ;;
    down)
        exec docker compose "${COMPOSE_FILES[@]}" down "$@"
        ;;
    restart)
        service="${1:-wikantik}"
        exec docker compose "${COMPOSE_FILES[@]}" restart "${service}"
        ;;
    ps)
        exec docker compose "${COMPOSE_FILES[@]}" ps "$@"
        ;;
    logs)
        # Default to following wikantik if no service given and -f not specified.
        if [[ $# -eq 0 ]]; then
            set -- wikantik
        fi
        exec docker compose "${COMPOSE_FILES[@]}" logs "$@"
        ;;
    shell)
        service="${1:-wikantik}"
        exec docker compose "${COMPOSE_FILES[@]}" exec "${service}" /bin/bash
        ;;
    psql)
        # Pop a leading `--` so callers can pass psql flags transparently.
        if [[ "${1:-}" == "--" ]]; then shift; fi
        exec docker compose "${COMPOSE_FILES[@]}" exec db \
            psql -U "${POSTGRES_USER:-wikantik}" -d "${POSTGRES_DB:-wikantik}" "$@"
        ;;
    migrate)
        exec docker compose "${COMPOSE_FILES[@]}" exec wikantik \
            /opt/wikantik/db/migrate.sh "$@"
        ;;
    backup)
        if [[ "${ENV}" != "prod" ]]; then
            echo "backup requires -e prod (sidecar is only in docker-compose.prod.yml)" >&2
            exit 2
        fi
        tier="${1:-daily}"
        exec docker compose "${COMPOSE_FILES[@]}" exec backup \
            /usr/local/bin/backup.sh "${tier}"
        ;;
    restore)
        if [[ "${ENV}" != "prod" ]]; then
            echo "restore requires -e prod (sidecar is only in docker-compose.prod.yml)" >&2
            exit 2
        fi
        if [[ $# -eq 0 ]]; then
            echo "restore: missing path argument (e.g. /backups/daily/2026-03-21)" >&2
            exit 2
        fi
        exec docker compose "${COMPOSE_FILES[@]}" exec backup \
            /usr/local/bin/restore.sh "$1"
        ;;
    smoke-test)
        keep=0
        if [[ "${1:-}" == "--keep" ]]; then keep=1; fi
        echo "Bringing up test stack (docker-compose.test.yml)..."
        docker compose -f docker-compose.test.yml up -d --build
        echo "Waiting for /api/health (port 18080)..."
        for _ in $(seq 1 30); do
            if curl -sfo /dev/null http://localhost:18080/api/health; then
                echo "  health UP"
                if [[ "${keep}" -eq 0 ]]; then
                    echo "Tearing down test stack..."
                    docker compose -f docker-compose.test.yml down -v
                else
                    echo "Test stack left running (--keep). Tear down with:"
                    echo "  docker compose -f docker-compose.test.yml down -v"
                fi
                exit 0
            fi
            sleep 2
        done
        echo "  health did NOT come up within 60s — see logs:" >&2
        docker compose -f docker-compose.test.yml logs --tail=80 wikantik >&2
        if [[ "${keep}" -eq 0 ]]; then
            docker compose -f docker-compose.test.yml down -v >/dev/null 2>&1 || true
        fi
        exit 1
        ;;
    *)
        echo "Unknown subcommand: ${SUBCOMMAND}" >&2
        echo "Run: bin/container.sh --help" >&2
        exit 2
        ;;
esac

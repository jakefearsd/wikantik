---
title: Remote container admin tooling
date: 2026-05-14
status: draft
---

# Remote container admin tooling

## Problem

Wikantik already runs cleanly inside Docker locally via `bin/container.sh`
and the four `docker-compose*.yml` files. Production deployment to a
separate host currently has no first-class tooling: there is no documented
path for pushing builds to a remote, no way to move the page corpus
independently of container lifecycle, and no consolidated admin surface
for routine operations against the remote host.

The sole developer needs a small, opinionated set of `bin/` scripts that
make every routine admin task against a remote host a single command,
without introducing a configuration management framework, a CI/CD
pipeline, or any orchestration layer beyond `ssh` and `docker compose`.

## Goals

1. Single-host, ssh-driven deployment and administration of Wikantik on
   a remote VPS or homelab box.
2. Wiki content (`pages/`) lives on the remote host's filesystem,
   bind-mounted into the container, so it can be moved with `rsync`
   independently of the container image.
3. All admin commands live under `bin/` as bash, following the existing
   `bin/container.sh` conventions (subcommands, `--help` per subcommand,
   `set -euo pipefail`).
4. Image transfer happens over ssh (`docker save | docker load`). The
   remote host runs no Maven, no Node, no JDK build toolchain.
5. Every state-changing command is idempotent and lockable so accidental
   double-invocation does not corrupt state.

## Non-goals

- Multi-host orchestration, blue/green, zero-downtime swap.
- CI/CD pipeline integration (GitHub Actions, etc.).
- Container registry workflows (left as a future upgrade path).
- Secret rotation tooling beyond `chmod 600` on `.env`.
- Remote provisioning of docker itself or firewall configuration.
- Rewrite in Python. Bash is sufficient at this scope.

## Architecture

```
┌──── dev workstation ────┐         ┌─────── remote host ────────┐
│                          │   ssh   │                            │
│  bin/remote.sh           │ ──────▶ │  ${REMOTE_REPO_DIR}/        │
│    ↳ local mvn+build     │ rsync   │    docker-compose*.yml      │
│    ↳ docker save │ ssh ──┼─────────┼─▶ docker load               │
│      │ docker load       │         │    .env  (chmod 600)        │
│    ↳ rsync pages         │ ──────▶ │  ${REMOTE_PAGES_DIR}/       │
│    ↳ ssh container.sh    │ ──────▶ │  ${REMOTE_BACKUP_DIR}/      │
│                          │         │  docker engine              │
└──────────────────────────┘         └────────────────────────────┘
```

The dev box performs all builds. The remote host runs `docker compose`
against pre-built images and a host-path-mounted pages directory.
`bin/remote.sh` is a thin orchestrator that re-invokes the existing
`bin/container.sh` on the remote over ssh for most operations, plus
handles ssh-specific concerns (image transfer, content rsync,
ControlMaster, locking).

### Why ssh image transfer over a registry

The user explicitly opted for `docker save | ssh | docker load` over a
container registry. Rationale:

- Deploys happen on a 1–10 Gb LAN, so a ~500 MB image transfer is
  bounded to seconds, not minutes.
- No registry account, credentials, or rotation policy to manage.
- One less external dependency in the deploy path.

The cost is full-image transfers each deploy (no layer-diffing). If
deploy time becomes painful, a private registry is the documented
upgrade path; this design intentionally avoids it now.

## Components

### `bin/remote.sh`

Single bash entry point. Mirrors `bin/container.sh` conventions:
top-level `--help` lists subcommands; each subcommand has its own
`--help` docstring. All state-changing subcommands accept `--dry-run` to
print the commands they would run.

#### Subcommand inventory

**Setup**

| Subcommand  | Description                                                                                                                                                                                                                                                                                                                                   |
| ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `bootstrap` | First-time remote setup. Verifies docker is present, creates `${REMOTE_REPO_DIR}`, `${REMOTE_PAGES_DIR}`, `${REMOTE_BACKUP_DIR}`, and `${SSH_CONTROL_DIR}` (local), rsyncs compose files and a starter `.env`. Does **not** run `up -d` — that happens on first `deploy`, which is also when the image first lands on the remote. Idempotent. |

**Deploy lifecycle**

| Subcommand                                    | Description                                                                                                                                                                                                                |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `deploy [--skip-build] [--health-timeout=90]` | Local `mvn` + `docker compose build` → stream image to remote via `docker save \| ssh 'docker load'` → tag prior `wikantik:latest` as `wikantik:rollback` first → `up -d` → poll `/api/health` → auto-rollback on failure. |
| `rollback`                                    | Re-tag `wikantik:rollback` as `wikantik:latest`, `up -d --force-recreate wikantik`.                                                                                                                                        |
| `up` / `down` / `restart`                     | Pass-through to remote `container.sh -e prod`.                                                                                                                                                                             |

**Operations**

| Subcommand            | Description                                                                                                                                                                  |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `status`              | One-screen summary: `docker compose ps`, `/api/health`, last 10 wikantik log lines, disk free on the `${REMOTE_PAGES_DIR}` partition, pages dir size, last backup timestamp. |
| `logs [-f] [service]` | Tail logs over ssh + ControlMaster. Default service: `wikantik`.                                                                                                             |
| `shell [service]`     | Interactive `exec /bin/bash` in a remote container.                                                                                                                          |
| `psql -- args...`     | Pass-through to remote `container.sh -e prod psql`.                                                                                                                          |
| `migrate [--status]`  | Ad-hoc migration run against the live remote container. (`entrypoint.sh` already runs migrations on container start, so this is for in-place re-runs without restart.)       |

**Content + backups**

| Subcommand                          | Description                                                                                                                                                              |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `pages-push <local-dir> [--mirror]` | `rsync` local pages → `${REMOTE_PAGES_DIR}`. **Default: no `--delete`** (remote-only files survive). `--mirror` opts into `--delete` after explicit confirmation prompt. |
| `pages-pull <local-dir>`            | `rsync` remote pages → local. Read-only, never deletes locally.                                                                                                          |
| `backup-trigger [tier]`             | Invoke the prod backup sidecar (`daily` \| `weekly` \| `monthly`, default `daily`).                                                                                      |
| `backup-pull [date]`                | rsync a backup snapshot from `${REMOTE_BACKUP_DIR}/<tier>/<date>` back to the dev box.                                                                                   |
| `restore <remote-path>`             | Stop wikantik, invoke sidecar restore, restart wikantik.                                                                                                                 |

### `docker-compose.prod.yml` change

Replace the named-volume `wikantik-pages` reference with a host bind
mount in both the `wikantik` and `backup` services:

```yaml
wikantik:
  volumes:
    - ${WIKANTIK_PAGES_DIR:-/srv/wikantik/pages}:/var/wikantik/pages
    # wikantik-work and wikantik-logs stay as named volumes (regeneratable).
backup:
  volumes:
    - ${WIKANTIK_PAGES_DIR:-/srv/wikantik/pages}:/var/wikantik/pages
    - ${BACKUP_DIR:-/srv/wikantik/backups}:/backups
    - ./docker/backup/backup.sh:/usr/local/bin/backup.sh:ro
    - ./docker/backup/restore.sh:/usr/local/bin/restore.sh:ro
    - ./docker/backup/crontab:/etc/crontabs/root:ro
```

The base `docker-compose.yml` and `docker-compose.dev.yml` are unchanged
— local development keeps the named-volume workflow with zero friction.

### `remote.env` / `remote.env.example`

`remote.env.example` committed to the repo as the canonical template.
`remote.env` is gitignored (alongside `test.properties` and `.env`).

```bash
# Required
REMOTE_HOST=wiki.example.com
REMOTE_USER=wikantik
REMOTE_REPO_DIR=/home/wikantik/wikantik
REMOTE_PAGES_DIR=/srv/wikantik/pages
REMOTE_BACKUP_DIR=/srv/wikantik/backups

# Optional
SSH_KEY=                              # default: ssh agent / config
SSH_CONTROL_DIR=~/.ssh/cm             # ControlMaster socket directory
HEALTH_URL=                           # default: http://${REMOTE_HOST}:8080/api/health
                                      # set to https://wiki.example.com/api/health if a
                                      # reverse proxy / TLS fronts the wikantik container
```

`bin/remote.sh` sources `remote.env` from the repo root. Missing required
variables produce a clear "set X in remote.env" error before any ssh
attempt.

## Cross-cutting concerns

### SSH ControlMaster

Every `ssh` and `rsync` invocation uses:

```
-o ControlMaster=auto
-o ControlPath=${SSH_CONTROL_DIR}/%C
-o ControlPersist=10m
```

The first remote subcommand performs the TCP+TLS handshake; subsequent
calls within 10 minutes reuse it. `${SSH_CONTROL_DIR}` is created with
mode `0700` if absent.

### Remote lock

`deploy`, `rollback`, and `restore` acquire `flock --nonblock
/var/lock/wikantik-deploy.lock` on the remote (or
`${REMOTE_REPO_DIR}/.lock` if `/var/lock` is not writable). Concurrent
attempts fail fast with:

```
remote.sh: another deploy is running on ${REMOTE_HOST} (lock held).
           wait for it to complete, or remove /var/lock/wikantik-deploy.lock
           if you are certain no deploy is in progress.
```

### Rollback semantics

`deploy` tags `wikantik:latest → wikantik:rollback` on the remote
*before* loading the new image. If `docker load` or the subsequent `up
-d` fails, the `:rollback` tag still points at the last-known-good
image. `rollback` re-promotes it to `:latest` and force-recreates the
wikantik service.

This trades one extra docker tag operation per deploy for safety.
Acceptable.

### Health gating

After `up -d`, `deploy` polls `${HEALTH_URL}` every 3 seconds for up to
90 seconds (override via `--health-timeout=N`). 200 OK is success;
anything else (timeout, non-200, network error) triggers auto-rollback.

The poll runs from the dev box, not from inside the remote — so DNS,
TLS, and any reverse-proxy in front of Tomcat are exercised at deploy
time.

### Idempotency

Every subcommand must be safely re-runnable:

- `bootstrap` uses `mkdir -p`, `rsync --update`, and skips `up -d` if
  containers are already healthy.
- `deploy` is naturally idempotent — re-running just rebuilds and
  re-uploads.
- `migrate`, `pages-push`, `backup-trigger`, `restore` are all wrappers
  around already-idempotent operations.

### Secrets handling

- `.env` is rsynced to the remote with `--chmod=F600`. Ownership is the
  ssh user (`${REMOTE_USER}`) by virtue of the transfer — no `--chown`
  needed.
- `.env` contents are never echoed to stdout/stderr.
- `remote.env` is gitignored.
- SSH key management is user-controlled — `remote.sh` neither generates
  nor distributes keys.

### `--dry-run`

State-changing subcommands accept `--dry-run`. With the flag set, the
script prints every `ssh` / `rsync` / `docker` command it would run,
prefixed with `[dry-run]`, and exits 0 without side effects.

## Data flow: `deploy` walkthrough

1. **Local build.** `mvn clean install -T 1C -DskipITs` (unless
   `--skip-build`). Then `docker compose -f docker-compose.yml build
   wikantik`.
2. **Acquire remote lock.** `ssh remote 'flock -n /var/lock/wikantik-deploy.lock -c true' || exit 2`.
3. **Sync compose files + `.env`.** `rsync -avz --chmod=F600
   docker-compose.yml docker-compose.prod.yml .env
   ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/`.
4. **Tag prior image as rollback.** `ssh remote 'docker tag wikantik:latest
   wikantik:rollback 2>/dev/null || true'` (failure on first deploy is
   expected and silent).
5. **Stream new image.** `docker save wikantik:latest | ssh remote
   'docker load'`. No temp file, no gzip — on a 1–10 Gb LAN the CPU
   cost of compression exceeds the wire-time savings. (If a future
   slower link forces it, add `--compress` to opt back in to
   `gzip -1` on both ends.)
6. **Up.** `ssh remote "cd ${REMOTE_REPO_DIR} && bin/container.sh -e
   prod up -d"`.
7. **Health-poll.** Loop: `curl -sf ${HEALTH_URL}` every 3s × 30
   iterations. On 200, mark success.
8. **Failure path.** If health never returns 200: `ssh remote 'docker
   tag wikantik:rollback wikantik:latest && cd ${REMOTE_REPO_DIR} &&
   bin/container.sh -e prod up -d --force-recreate wikantik'`. Print
   captured last-50 lines of wikantik logs. Exit 1.
9. **Release lock.** Implicit when the ssh session holding `flock`
   exits.

## Testing

- **Syntax-level.** `bash -n bin/remote.sh` runs in CI alongside other
  shellcheck-style checks.
- **Dry-run smoke.** A make/script target invokes every subcommand with
  `--dry-run` against a fake `remote.env` (pointing at localhost or
  127.0.0.2) and asserts no real ssh is attempted.
- **Manual end-to-end.** First real `deploy` is a manual verification
  step — no automated VM/Vagrant target in v1.
- **Image-itself testing.** Already covered by `bin/container.sh
  smoke-test`. `remote.sh` is just the transport layer; it does not
  need to re-verify the image works.

## Failure modes considered

| Scenario                                    | Behavior                                                                                                                                                                                                                   |
| ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Docker not installed on remote              | `bootstrap` prints "docker not found on ${REMOTE_HOST} — install docker and docker compose, then re-run." Exits 2.                                                                                                         |
| ssh unreachable                             | `ssh` returns non-zero on the first call; `remote.sh` prints the underlying error and exits 1.                                                                                                                             |
| Image transfer succeeds, `up -d` fails      | Lock still held in current ssh session; rollback path runs; lock released on session exit.                                                                                                                                 |
| `up -d` succeeds, health never returns 200  | Auto-rollback. Last 50 log lines printed. Exit 1.                                                                                                                                                                          |
| Two `deploy` invocations from two terminals | Second one fails fast on `flock -n`. Clear message points at the lock file.                                                                                                                                                |
| `pages-push` would delete remote files      | Without `--mirror`, rsync runs without `--delete` — no deletion occurs. With `--mirror`, an interactive confirm prompt prints the list of files that would be deleted. `--mirror --yes` skips the prompt for scripted use. |
| `.env` accidentally committed               | Gitignored already; design adds `remote.env` to the same `.gitignore` block.                                                                                                                                               |

## Open questions

None at design time. All three explicit questions raised during
brainstorming were resolved:

1. Image transfer: `docker save \| ssh \| docker load`, full-image,
   acceptable on a 1–10 Gb LAN.
2. `pages-push` default: no `--delete`; `--mirror` is the opt-in flag.
3. `bootstrap` does not install docker; it checks and tells the user.

## Future extensions (explicitly deferred)

- Container registry workflow (GHCR or private) — straight upgrade path
  when ssh transfer becomes painful.
- Layer-diff transfer (`docker save` per-layer or rsync of image dirs)
  — only worth it if registry is rejected and transfer is painful.
- Multi-host fan-out — would warrant a different tool (Ansible, Nomad).
  Out of scope until there is a second host.
- Automated end-to-end test against a throwaway VM — useful once
  `remote.sh` stops changing weekly.

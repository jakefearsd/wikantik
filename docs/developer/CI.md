# CI/CD — GitHub Actions Workflows

This page is for developers who need to know what runs in GitHub Actions, when
it runs, and how a release flows from a tag to a running container. The
workflows live in `.github/workflows/`; read the `on:` block of each file for
its triggers.

## The workflows

| Workflow | Trigger | Runner | Role |
|----------|---------|--------|------|
| `release.yml` | push of a `v*.*.*` tag | GitHub-hosted | **The release pipeline** — builds, publishes, releases |
| `quality-gates.yml` | push to `main`, weekly schedule (Mondays 04:00 UTC), manual | GitHub-hosted | `static-gates`, `shell-tests` (includes the Markdown relative-links step) and `osv-scan` on every push; the Java unit suite (`unit-tests`) only on the weekly schedule and on manual dispatch; `report-failures` opens one issue per red job |
| `codeql.yml` | `workflow_dispatch` (manual) | GitHub-hosted | Security scanning, on demand |
| `ci-cd.yml` | `workflow_dispatch` (manual) | `self-hosted` | Legacy build-and-deploy — dormant (no runner registered) |
| `staging-deploy.yml` | `workflow_dispatch` (manual) | `self-hosted` | Legacy staging deploy — dormant (no runner registered) |
| `dependency-review.yml` | pull requests | GitHub-hosted | Dependency diff review |

`release.yml` and `quality-gates.yml` run automatically (on a tag push and on
every push to `main`, respectively). `ci-cd.yml`, `codeql.yml`, and
`staging-deploy.yml` are `workflow_dispatch`-only to conserve GitHub Actions
minutes. The full IT reactor (Cargo/Postgres/pgvector) is not run in CI, so it
stays a local pre-commit gate — the canonical command is
`bin/run-tests.sh --parallel 4` (see [Testing.md](Testing.md)).

`ci-cd.yml` and `staging-deploy.yml` additionally declare `runs-on: self-hosted`
and describe an older "CI builds the image and SSHes it to production" model.
No self-hosted runner is currently registered, so they stay queued and do not
execute even if dispatched. They are kept as historical reference; the live deployment
path is `release.yml` + the `bin/` wrappers below.

## What quality-gates.yml runs

| Job | Runs on | What it does |
|-----|---------|--------------|
| `static-gates` | push, manual | `mvn -B -T 1C clean install -DskipTests`; the complexity ratchet (`mvn -B -fn pmd:check -Pcomplexity-gate`, passing only if the reactor prints `BUILD SUCCESS`); frontend ESLint; `npm audit signatures`; `npm audit`; a check that `package-lock.json` is unchanged; Vitest with the coverage thresholds |
| `shell-tests` | push, manual | Seeds `.env` from `.env.example`, runs every `bin/tests/test-*.sh`, then the **Markdown relative links** step: `python3 bin/check-md-links.py` |
| `osv-scan` | push, manual | CycloneDX SBOM (all scopes, including test) plus the npm lockfile, scanned with OSV-Scanner using `osv-scanner.toml` |
| `unit-tests` | weekly schedule, manual (skipped on push) | `mvn -B clean install -DskipITs -Dtests.requireDocker=true -Pcoverage`: the full unit suite plus the per-module coverage floors; 120-minute timeout |
| `report-failures` | after the jobs above, on `main` | Opens or comments on one issue per red job and closes it when that job next passes; a job skipped by the event leaves its issue untouched |

Because `unit-tests` is weekly, a push to `main` never runs the Java unit suite
or the coverage floors. Before you dispatch it, mirror it locally with
`mvn clean install -DskipITs -Pcoverage`. Run the Markdown link check locally
with `python3 bin/check-md-links.py`; it prints nothing when every relative
link resolves.

## The release pipeline (`release.yml`)

Fires when a tag matching `v*.*.*` is pushed. On a GitHub-hosted runner it:

1. Builds the WAR (`mvn clean package -DskipTests -T 1C -B` — tests are run
   locally before tagging; `-DskipTests`, not `-Dmaven.test.skip`, so the
   `wikantik-main` test-jar is still produced for the reactor).
2. Builds the multi-stage Docker image.
3. Publishes it to GHCR — `ghcr.io/jakefearsd/wikantik:X.Y.Z` and `:latest`.
4. Creates a GitHub Release with the WAR attached and notes from `CHANGELOG.md`.

## Cutting and deploying a release

Two wrappers capture the happy path (full detail in
[DockerDeployment.md](../admin/DockerDeployment.md) §3):

```bash
# 1. Cut the release — version bump, CHANGELOG, tag, push.
#    Pushing the tag triggers release.yml.
bin/cut-release.sh X.Y.Z

# 2. Once release.yml is green, deploy the published image to the host.
bin/deploy-release.sh X.Y.Z
```

`bin/deploy-release.sh` pulls `ghcr.io/jakefearsd/wikantik:X.Y.Z` and runs
`bin/remote.sh deploy --skip-build`, which transfers the image over ssh,
swaps it, health-polls `/api/health`, and auto-rolls-back on failure.
Deployment is driven from the developer box — it is not a CI job.

## Watching a run

```bash
gh run watch "$(gh run list --workflow=release.yml -L1 --json databaseId -q '.[0].databaseId')"
gh run list --workflow=release.yml          # recent release runs
```

## Cost

`release.yml` runs only on a tag push, and the manual workflows run only
when dispatched. Routine pushes to `main` do trigger `quality-gates.yml`
(static gates, shell tests, OSV scan), which runs on GitHub-hosted runners so
it does not depend on the unregistered self-hosted runner. `dependency-review.yml`
runs on pull requests only.
Dependabot version-update runs continue on their own schedule.

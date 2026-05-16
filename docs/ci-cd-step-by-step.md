# CI/CD — GitHub Actions Workflows

Wikantik's automation lives in `.github/workflows/`. This describes what each
workflow does today and how a release flows from a tag to a running
container.

## The workflows

| Workflow | Trigger | Runner | Role |
|----------|---------|--------|------|
| `release.yml` | push of a `v*.*.*` tag | GitHub-hosted | **The release pipeline** — builds, publishes, releases |
| `codeql.yml` | `workflow_dispatch` (manual) | GitHub-hosted | Security scanning, on demand |
| `ci-cd.yml` | `workflow_dispatch` (manual) | `self-hosted` | Legacy build-and-deploy — dormant |
| `staging-deploy.yml` | `workflow_dispatch` (manual) | `self-hosted` | Legacy staging deploy — dormant |
| `dependency-review.yml` | pull requests | GitHub-hosted | Dependency diff review |

Only `release.yml` runs automatically. `ci-cd.yml`, `codeql.yml`, and
`staging-deploy.yml` were switched to `workflow_dispatch`-only to conserve
GitHub Actions minutes — the test suite is run locally before a release
(`mvn clean install -Pintegration-tests -fae`, per `CLAUDE.md`).

`ci-cd.yml` and `staging-deploy.yml` additionally declare `runs-on: self-hosted`
and describe an older "CI builds the image and SSHes it to production" model.
No self-hosted runner is currently registered, so they will not execute even
if dispatched. They are kept as historical reference; the live deployment
path is `release.yml` + the `bin/` wrappers below.

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
[DockerDeployment.md](DockerDeployment.md) §3):

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
when dispatched — so routine pushes to `main` consume no Actions minutes.
Dependabot version-update runs continue on their own schedule.

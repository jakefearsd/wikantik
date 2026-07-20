#!/usr/bin/env bash
#
# cut-release.sh — cut a Wikantik release: version bump, CHANGELOG, tag, push.
#
# Captures the release-cutting sequence as one command. Pushing the tag
# triggers .github/workflows/release.yml, which builds the WAR + Docker image,
# publishes ghcr.io/jakefearsd/wikantik:X.Y.Z (+ :latest), and creates the
# GitHub Release. Afterwards, deploy with: bin/deploy-release.sh X.Y.Z
#
# Run a full green `bin/run-tests.sh --all` first — the complete gate (unit +
# all default IT modules) plus the opt-in Authentik SCIM full-loop, which is
# not part of the per-commit gate, so a release is the checkpoint that keeps
# it from rotting. This script does not build — it only versions, tags, and
# pushes.
#
# Usage:
#   bin/cut-release.sh X.Y.Z
#   bin/cut-release.sh --help
#
# What it does:
#   1. Guards: arg is X.Y.Z, on main, clean tree, tag vX.Y.Z does not exist.
#   2. mvn versions:set X.Y.Z across all modules.
#   3. CHANGELOG.md: [Unreleased] -> [X.Y.Z] - <today>, fresh [Unreleased] above.
#   4. Commit "release: X.Y.Z"; annotated tag vX.Y.Z.
#   5. mvn versions:set to the next -SNAPSHOT; commit "build: bump ...".
#   6. Confirm, then push main + the tag (this is the publish trigger).
#
# Exit codes: 0 success · 1 step failure · 2 usage / guard failure
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

VERSION="${1:-}"
if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "cut-release.sh: expected a X.Y.Z version argument (got '${VERSION:-}')." >&2
    exit 2
fi
TAG="v${VERSION}"

# ---------- 1: guards ----------
branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "${branch}" == "main" ]] || { echo "cut-release.sh: not on main (on '${branch}')." >&2; exit 2; }
[[ -z "$(git status --porcelain)" ]] || { echo "cut-release.sh: working tree is dirty — commit or stash first." >&2; exit 2; }
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "cut-release.sh: tag ${TAG} already exists." >&2
    exit 2
fi
if grep -qE "^## \[${VERSION//./\\.}\]" CHANGELOG.md; then
    echo "cut-release.sh: CHANGELOG.md already has a [${VERSION}] section." >&2
    exit 2
fi

IFS=. read -r major minor patch <<< "${VERSION}"
NEXT_SNAPSHOT="${major}.${minor}.$((patch + 1))-SNAPSHOT"
TODAY="$(date +%Y-%m-%d)"

echo "==> Releasing ${VERSION}; main will continue at ${NEXT_SNAPSHOT}."

# ---------- 2: version bump ----------
mvn -q versions:set -DnewVersion="${VERSION}" -DgenerateBackupPoms=false -DprocessAllModules=true

# ---------- 3: CHANGELOG ----------
sed -i "s/^## \[Unreleased\]$/## [Unreleased]\n\n## [${VERSION}] - ${TODAY}/" CHANGELOG.md
grep -qE "^## \[${VERSION//./\\.}\] - ${TODAY}" CHANGELOG.md \
    || { echo "cut-release.sh: CHANGELOG rewrite failed — is the '## [Unreleased]' header present?" >&2; exit 1; }

# ---------- 4: release commit + tag ----------
# shellcheck disable=SC2046
git add $(git ls-files '*pom.xml') CHANGELOG.md
git commit -m "release: ${VERSION}"
git tag -a "${TAG}" -m "Wikantik ${VERSION}"

# ---------- 5: bump main to the next -SNAPSHOT ----------
mvn -q versions:set -DnewVersion="${NEXT_SNAPSHOT}" -DgenerateBackupPoms=false -DprocessAllModules=true
# shellcheck disable=SC2046
git add $(git ls-files '*pom.xml')
git commit -m "build: bump main to ${NEXT_SNAPSHOT} post-release"

# ---------- 6: push (this triggers release.yml) ----------
echo
echo "Local state ready:"
git --no-pager log --oneline -2
echo
read -r -p "Push main + tag ${TAG} now? This publishes the release. [y/N] " ans
if [[ "${ans}" == "y" || "${ans}" == "Y" ]]; then
    git push origin main "${TAG}"
    echo "Pushed. release.yml is building — watch: gh run watch \$(gh run list --workflow=release.yml -L1 --json databaseId -q '.[0].databaseId')"
    echo "When it is green: bin/deploy-release.sh ${VERSION}"
else
    echo "Not pushed. To finish later:  git push origin main ${TAG}"
fi

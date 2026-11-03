#!/usr/bin/env bash
# reader-text-diff.sh <git-ref> <pagesDir>
#
# The corpus check before a comment-mask (or tag) change ships: shows every page whose reader-visible
# text differs between <git-ref> (the release in production) and HEAD. Exit 1 when any page GROWS --
# the reader sees MORE text at HEAD than at <git-ref>, i.e. the change newly exposes it.
#
# How: <git-ref> and HEAD are each built (mvn -o package, so ~/.m2 is never overwritten) in their own
# temporary git worktree, so the working tree is never touched (uncommitted changes are NOT included in
# the HEAD side). The one source file ReaderTextSnapshotCli.java is then compiled against EACH build's
# shaded jar -- an old ref has no ReaderTextSnapshotCli, and the snapshot code looks up
# ReaderComments (new) or ObsidianComments (old) by reflection, so the same file runs against both.
# Worktrees and temp files are removed on exit, success or failure.
#
# Example:  bin/reader-text-diff.sh v2.5.7 docs/wikantik-pages
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "usage: $0 <git-ref> <pagesDir>" >&2
    exit 64
fi
REF="$1"
PAGES="$(cd "$2" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/wikantik-extract-cli/src/main/java/com/wikantik/extractcli/ReaderTextSnapshotCli.java"
WORK="$(mktemp -d)"
WT_OLD="$WORK/old"
WT_NEW="$WORK/new"

cleanup() {
    git -C "$ROOT" worktree remove --force "$WT_OLD" >/dev/null 2>&1 || true
    git -C "$ROOT" worktree remove --force "$WT_NEW" >/dev/null 2>&1 || true
    git -C "$ROOT" worktree prune >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

snapshot_with() {   # <worktree> <label>
    local wt="$1" label="$2" jar classes
    git -C "$ROOT" worktree add --detach "$wt" "${3}" >/dev/null
    echo "building $label ($3) ..." >&2
    ( cd "$wt" && mvn -q -o package -DskipTests -pl wikantik-extract-cli -am >&2 )
    jar="$wt/wikantik-extract-cli/target/wikantik-extract-cli.jar"
    classes="$WORK/classes-$label"
    mkdir -p "$classes"
    javac -cp "$jar" -d "$classes" "$SRC"
    java -cp "$classes:$jar" com.wikantik.extractcli.ReaderTextSnapshotCli snapshot "$PAGES" "$WORK/$label.json"
}

snapshot_with "$WT_OLD" old "$REF"
snapshot_with "$WT_NEW" new HEAD

# the diff itself needs only Gson + the CLI class: reuse the new build
java -cp "$WORK/classes-new:$WT_NEW/wikantik-extract-cli/target/wikantik-extract-cli.jar" \
    com.wikantik.extractcli.ReaderTextSnapshotCli diff "$WORK/old.json" "$WORK/new.json"

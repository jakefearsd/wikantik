#!/usr/bin/env bash
# reader-text-diff.sh <git-ref> <pagesDir>
#
# The corpus check before a comment-mask (or tag) change ships: shows every page whose reader-visible
# text differs between <git-ref> (the release in production) and HEAD. Exit 3 when any page EXPOSES
# (a character hidden at <git-ref> is visible at HEAD; exact, by comment ranges, when the body is unchanged)
# or GROWS (the reader sees MORE text at HEAD, the fallback when the body differs).
#
# Exit codes: 0 nothing exposes or grows; 3 at least one page EXPOSES or GROWS; 1 or 2 usage / build / IO failure (64 bad
# arguments). A final line `reader-text-diff: N changed, E expose, G grow, S shrink` is printed on 0 and 3.
#
# How: <git-ref> and HEAD are each built (mvn -o package, so ~/.m2 is never overwritten) in their own
# temporary git worktree, so the working tree is never touched (uncommitted changes are NOT included in
# the HEAD side). The two source files ReaderTextSnapshotCli.java + ReaderTextParsers.java are then compiled against EACH build's
# shaded jar -- an old ref has no ReaderTextSnapshotCli, and ReaderTextParsers looks up
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
SRC_DIR="$ROOT/wikantik-extract-cli/src/main/java/com/wikantik/extractcli"
SRCS=( "$SRC_DIR/ReaderTextSnapshotCli.java" "$SRC_DIR/ReaderTextParsers.java" )
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
    javac -cp "$jar" -d "$classes" "${SRCS[@]}"
    java -cp "$classes:$jar" com.wikantik.extractcli.ReaderTextSnapshotCli snapshot "$PAGES" "$WORK/$label.json"
}

snapshot_with "$WT_OLD" old "$REF"
snapshot_with "$WT_NEW" new HEAD

# the diff itself needs only Gson + the CLI class: reuse the new build
rc=0
java -cp "$WORK/classes-new:$WT_NEW/wikantik-extract-cli/target/wikantik-extract-cli.jar" \
    com.wikantik.extractcli.ReaderTextSnapshotCli diff "$WORK/old.json" "$WORK/new.json" > "$WORK/diff.txt" || rc=$?
cat "$WORK/diff.txt"
if [ "$rc" -ne 0 ] && [ "$rc" -ne 3 ]; then
    echo "reader-text-diff: diff failed (exit $rc)" >&2
    exit "$rc"
fi
echo "reader-text-diff: $(grep -cE '^(EXPOSES|GROWS|SHRINKS|SAME-LENGTH) ' "$WORK/diff.txt" || true) changed," \
     "$(grep -c '^EXPOSES ' "$WORK/diff.txt" || true) expose, $(grep -c '^GROWS ' "$WORK/diff.txt" || true) grow, $(grep -c '^SHRINKS ' "$WORK/diff.txt" || true) shrink"
exit "$rc"

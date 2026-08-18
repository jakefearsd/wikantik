#!/usr/bin/env bash
# agent-build.sh — run long builds so harness-constrained agents can drive them
# with repeated short calls and never face an ambiguous completion state.
#
#   bin/agent-build.sh start <name> -- <command ...>   detach build, log to .build-logs/<name>.log
#   bin/agent-build.sh status <name>                   one line: RUNNING | SUCCESS | FAILED | KILLED
#   bin/agent-build.sh wait <name> [timeout=540]       bounded block; exit 0=SUCCESS 1=FAILED/KILLED 2=still RUNNING
#   bin/agent-build.sh tail <name> [lines=20]          tail the build log
#
# Why this exists: a bare foreground Maven call dies at the agent tool's ~10-minute
# cap, and a bare `nohup mvn -q ... &` leaves a log where success and a crashed
# build look identical (-q suppresses the BUILD SUCCESS banner). This wrapper
# runs the build in its own session (survives process-group kills) and
# appends an EXIT=<code> sentinel to the log, so completion is one grep.
# It also unsets WIKANTIK_* env vars in the child (WikiTest counts them).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/.build-logs"
mkdir -p "$DIR"

usage() { grep '^#   ' "$0" | sed 's/^#   //'; exit 2; }

name_ok() { [[ "$1" =~ ^[a-zA-Z0-9._-]+$ ]]; }

# Run "$@" in a new session so a process-group kill aimed at the caller can't
# reach it. setsid(1) is util-linux and is NOT present on macOS, so fall back to
# perl's POSIX::setsid (ships with macOS). If neither exists we still run the
# build — it just stays in the caller's process group.
# NOTE: this MUST stay an array expanded inline at the call site, not a shell
# function. Backgrounding a function call forks a subshell that bash cannot
# exec-optimize away, so $! would record that throwaway subshell instead of the
# detached build — and a process-group kill would then make status report a
# false KILLED while the build was still running.
if command -v setsid >/dev/null 2>&1; then
  DETACH=(setsid)
elif command -v perl >/dev/null 2>&1; then
  DETACH=(perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV or die "exec failed: $!\n";' --)
else
  DETACH=()
fi

# Epoch mtime of a file. GNU (Linux) and BSD (macOS) stat take different flags:
# GNU wants -c %Y, BSD wants -f %m. Try GNU first, then BSD, and validate that
# what came back is actually an integer before returning it -- on GNU stat, -f
# means --file-system, so the BSD-style call "succeeds" far enough to print
# filesystem text on stdout. Falling back to "now" (elapsed 0) guarantees the
# caller's arithmetic can never break under set -e.
file_mtime() {
  local t
  t="$(stat -c %Y "$1" 2>/dev/null)" || t=""
  [[ "$t" =~ ^[0-9]+$ ]] || t="$(stat -f %m "$1" 2>/dev/null)" || t=""
  [[ "$t" =~ ^[0-9]+$ ]] || t="$(date +%s)"
  printf '%s\n' "$t"
}

cmd="${1:-}"
[[ -n "$cmd" ]] || usage
shift

case "$cmd" in

  start)
    name="${1:?usage: start <name> -- <command...>}"; shift
    name_ok "$name" || { echo "invalid name: $name (use [a-zA-Z0-9._-])"; exit 2; }
    [[ "${1:-}" == "--" ]] || { echo "missing '--' before command"; exit 2; }
    shift
    [[ $# -gt 0 ]] || { echo "missing command after '--'"; exit 2; }
    log="$DIR/$name.log"; pidf="$DIR/$name.pid"
    if [[ -f "$pidf" ]] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
      echo "REFUSED: build '$name' already RUNNING (pid $(cat "$pidf")) — pick another name or wait"
      exit 2
    fi
    rm -f "$log" "$pidf"
    { printf 'CMD:'; printf ' %q' "$@"; printf '\n'; } > "$log"
    # Launcher stdout/stderr go to the LOG, never /dev/null: if the detach helper
    # itself fails (missing binary, exec error) that message must be visible,
    # otherwise the build silently never starts and status reports a bogus KILLED.
    LOG="$log" ${DETACH[@]+"${DETACH[@]}"} bash -c '
      for v in $(compgen -v | grep "^WIKANTIK_" || true); do unset "$v"; done
      "$@" >> "$LOG" 2>&1
      echo "EXIT=$?" >> "$LOG"
    ' _ "$@" < /dev/null >> "$log" 2>&1 &
    echo $! > "$pidf"
    echo "STARTED name=$name pid=$(cat "$pidf") log=$log"
    ;;

  status)
    name="${1:?usage: status <name>}"
    log="$DIR/$name.log"; pidf="$DIR/$name.pid"
    [[ -f "$log" ]] || { echo "UNKNOWN: no build named '$name' (no $log)"; exit 2; }
    exitline="$(grep -E '^EXIT=[0-9]+$' "$log" | tail -1 || true)"
    if [[ -n "$exitline" ]]; then
      code="${exitline#EXIT=}"
      if [[ "$code" == "0" ]]; then
        echo "SUCCESS exit=0 log=$log"
      else
        echo "FAILED exit=$code log=$log"
        echo "--- last 15 log lines ---"
        tail -15 "$log"
      fi
      exit 0
    fi
    if [[ -f "$pidf" ]] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
      echo "RUNNING pid=$(cat "$pidf") elapsed=$(( $(date +%s) - $(file_mtime "$pidf") ))s log_bytes=$(wc -c < "$log") log=$log"
      exit 0
    fi
    echo "KILLED: process gone with no EXIT sentinel (crashed / OOM-killed / host reboot) log=$log"
    echo "--- last 15 log lines ---"
    tail -15 "$log"
    exit 0
    ;;

  wait)
    name="${1:?usage: wait <name> [timeout-seconds]}"
    timeout="${2:-540}"
    deadline=$(( $(date +%s) + timeout ))
    while true; do
      # Capture the whole status output and split in-shell. Piping into `head -1`
      # under `set -o pipefail` makes status die of SIGPIPE on its own trailing
      # log lines, so wait exited 141 instead of the documented 0/1/2.
      out="$("$0" status "$name" || true)"
      line="${out%%$'\n'*}"
      case "$line" in
        SUCCESS*)         echo "$line"; exit 0 ;;
        FAILED*|KILLED*)  printf '%s\n' "$out"; exit 1 ;;
        UNKNOWN*)         echo "$line"; exit 2 ;;
      esac
      if (( $(date +%s) >= deadline )); then
        echo "$line"
        echo "STILL-RUNNING after ${timeout}s — call wait (or status) again"
        exit 2
      fi
      sleep 5
    done
    ;;

  tail)
    name="${1:?usage: tail <name> [lines]}"
    tail -"${2:-20}" "$DIR/$name.log"
    ;;

  *)
    usage
    ;;
esac

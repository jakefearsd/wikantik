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
# setsids the build into its own session (survives process-group kills) and
# appends an EXIT=<code> sentinel to the log, so completion is one grep.
# It also unsets WIKANTIK_* env vars in the child (WikiTest counts them).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/.build-logs"
mkdir -p "$DIR"

usage() { grep '^#   ' "$0" | sed 's/^#   //'; exit 2; }

name_ok() { [[ "$1" =~ ^[a-zA-Z0-9._-]+$ ]]; }

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
    LOG="$log" setsid bash -c '
      for v in $(compgen -v | grep "^WIKANTIK_" || true); do unset "$v"; done
      "$@" >> "$LOG" 2>&1
      echo "EXIT=$?" >> "$LOG"
    ' _ "$@" < /dev/null >/dev/null 2>&1 &
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
      echo "RUNNING pid=$(cat "$pidf") elapsed=$(( $(date +%s) - $(stat -c %Y "$pidf") ))s log_bytes=$(wc -c < "$log") log=$log"
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
      line="$("$0" status "$name" | head -1)"
      case "$line" in
        SUCCESS*)         echo "$line"; exit 0 ;;
        FAILED*|KILLED*)  "$0" status "$name"; exit 1 ;;
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

#!/usr/bin/env python3
"""Reduce a JFR recording to the tables a CPU-optimisation iteration needs.

`jfr summary` tells you how many events there were; it does not tell you where
the cycles went. This produces four views from one recording:

  cpu-leaf.txt      leaf frame per ExecutionSample — literally where the CPU is
  cpu-wikantik.txt  DEEPEST com.wikantik frame per sample. This is the view
                    that drives action: a leaf of java.lang.String.hashCode is
                    true but useless, whereas "this sample was inside
                    DefaultLuceneSearcher.findPages" is a place to go and look.
  alloc.txt         allocation sites (GC pressure root cause)
  monitor.txt       contended monitors (jdk.JavaMonitorEnter), by blocking site

Usage: jfr-cpu-report.py <recording.jfr> <out_dir>
"""
import collections
import pathlib
import re
import subprocess
import sys

# A stack line from `jfr print` looks like:
#   com.wikantik.search.Foo.bar(String, int) line: 42
# Capture the fully-qualified method, dropping the argument list and line no.
FRAME_RE = re.compile(r'^\s+([a-zA-Z_$][\w$.]*\.[\w$<>]+)\(')

APP_PREFIX = 'com.wikantik.'


def run_jfr(jfr_bin, path, event, depth):
    """Return `jfr print` stdout for one event type, or '' if it fails."""
    try:
        proc = subprocess.run(
            [jfr_bin, 'print', '--events', event, '--stack-depth', str(depth), str(path)],
            capture_output=True, text=True, timeout=900)
    except (OSError, subprocess.TimeoutExpired) as exc:
        print(f"  WARN: jfr print {event} failed: {exc}", file=sys.stderr)
        return ''
    if proc.returncode != 0:
        # Not fatal: a recording legitimately may not contain this event type.
        print(f"  WARN: jfr print {event} exit={proc.returncode}: "
              f"{proc.stderr.strip()[:200]}", file=sys.stderr)
        return ''
    return proc.stdout


def split_samples(text):
    """Yield the ordered frame list for each event in `jfr print` output.

    Events are separated by a line starting with the event name and '{'. Frames
    are the indented lines inside the stackTrace block, outermost-first as JFR
    prints them (leaf first).
    """
    frames = []
    in_stack = False
    for line in text.splitlines():
        if line.startswith(('jdk.', 'stackTrace')) and '{' in line:
            if line.startswith('jdk.') and frames:
                yield frames
                frames = []
            in_stack = 'stackTrace' in line
            continue
        if in_stack:
            m = FRAME_RE.match(line)
            if m:
                frames.append(m.group(1))
            elif line.strip() in ('}', ']') or line.startswith('}'):
                in_stack = False
    if frames:
        yield frames


def tally(text):
    """Return (leaf_counter, app_counter, total_samples)."""
    leaf = collections.Counter()
    app = collections.Counter()
    total = 0
    for frames in split_samples(text):
        if not frames:
            continue
        total += 1
        leaf[frames[0]] += 1
        # jfr prints leaf-first, so the FIRST com.wikantik frame encountered is
        # the deepest application frame on the stack — the one to attribute to.
        for f in frames:
            if f.startswith(APP_PREFIX):
                app[f] += 1
                break
        else:
            app['<no application frame (JDK/container/library only)>'] += 1
    return leaf, app, total


def write_table(path, title, counter, total, limit=60):
    lines = [f"# {title}", f"# total samples: {total}", ""]
    if total:
        for name, n in counter.most_common(limit):
            lines.append(f"{100.0 * n / total:6.2f}%  {n:7d}  {name}")
    else:
        lines.append("(no samples)")
    path.write_text("\n".join(lines) + "\n")


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    jfr_path = pathlib.Path(sys.argv[1])
    out_dir = pathlib.Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    jfr_bin = 'jfr'

    # --- CPU -----------------------------------------------------------------
    text = run_jfr(jfr_bin, jfr_path, 'jdk.ExecutionSample', 40)
    leaf, app, total = tally(text)
    write_table(out_dir / 'cpu-leaf.txt',
                'CPU by LEAF frame (where cycles are spent)', leaf, total)
    write_table(out_dir / 'cpu-wikantik.txt',
                'CPU by deepest com.wikantik frame (what to actually fix)', app, total)
    print(f"  ExecutionSample: {total} samples")

    # --- allocation ----------------------------------------------------------
    alloc_text = run_jfr(jfr_bin, jfr_path, 'jdk.ObjectAllocationSample', 40)
    a_leaf, a_app, a_total = tally(alloc_text)
    lines = ["# Allocation by deepest com.wikantik frame",
             f"# total samples: {a_total}", ""]
    if a_total:
        for name, n in a_app.most_common(40):
            lines.append(f"{100.0 * n / a_total:6.2f}%  {n:7d}  {name}")
        lines += ["", "# Allocation by leaf frame", ""]
        for name, n in a_leaf.most_common(30):
            lines.append(f"{100.0 * n / a_total:6.2f}%  {n:7d}  {name}")
    else:
        lines.append("(no samples)")
    (out_dir / 'alloc.txt').write_text("\n".join(lines) + "\n")
    print(f"  ObjectAllocationSample: {a_total} samples")

    # --- monitor contention --------------------------------------------------
    mon_text = run_jfr(jfr_bin, jfr_path, 'jdk.JavaMonitorEnter', 20)
    m_leaf, m_app, m_total = tally(mon_text)
    lines = ["# Contended monitor ENTER by blocking application frame",
             f"# total events: {m_total}",
             "# (event count, not time — a few long blocks can matter more"
             "  than many short ones)", ""]
    if m_total:
        for name, n in m_app.most_common(40):
            lines.append(f"{100.0 * n / m_total:6.2f}%  {n:7d}  {name}")
    else:
        lines.append("(no contended monitor events — good)")
    (out_dir / 'monitor.txt').write_text("\n".join(lines) + "\n")
    print(f"  JavaMonitorEnter: {m_total} events")

    return 0


if __name__ == '__main__':
    sys.exit(main())

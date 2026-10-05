#!/usr/bin/env python3
"""Fail on broken relative links in tracked Markdown files.

Usage: bin/check-md-links.py [--all] [--root DIR]

Default scope is the maintained documentation: every tracked *.md except the
wiki corpus, historical/design records and generated or third-party trees.
--all widens to every tracked *.md except the wiki corpus and only reports.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

EXCLUDE_ALWAYS = ("docs/wikantik-pages/",)
EXCLUDE_DEFAULT = ("docs/superpowers/", "docs/archive/", "docs/clusters/", "eval/", "marketing/", "src/site/")
EXCLUDE_DEFAULT_FILES = {"CHANGELOG.md"}

INLINE = re.compile(r"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
REFDEF = re.compile(r"^\s{0,3}\[(?!\^)[^\]]+\]:\s*<?(\S+?)>?(?:\s+\"[^\"]*\")?\s*$")
FENCE = re.compile(r"^\s{0,3}(```|~~~)")
INLINE_CODE = re.compile(r"`[^`]*`")


def in_scope(rel: str, widen: bool) -> bool:
    if any(rel.startswith(p) for p in EXCLUDE_ALWAYS):
        return False
    if widen:
        return True
    return rel not in EXCLUDE_DEFAULT_FILES and not any(rel.startswith(p) for p in EXCLUDE_DEFAULT)


def is_external(target: str) -> bool:
    return target.startswith("#") or re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target) is not None


def targets(text: str):
    in_fence = False
    for lineno, line in enumerate(text.splitlines(), start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        line = INLINE_CODE.sub("", line)
        for m in INLINE.finditer(line):
            yield lineno, m.group(1)
        m = REFDEF.match(line)
        if m:
            yield lineno, m.group(1)


def check_file(path: Path, root: Path) -> list:
    problems = []
    rel = path.relative_to(root).as_posix()
    for lineno, target in targets(path.read_text(encoding="utf-8", errors="replace")):
        if is_external(target):
            continue
        clean = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not clean:
            continue
        resolved = (root / clean.lstrip("/")) if clean.startswith("/") else (path.parent / clean)
        if not resolved.exists():
            problems.append(f"{rel}:{lineno}: broken link -> {target}")
    return problems


def tracked_markdown(root: Path) -> list:
    out = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", "*.md"],
                         check=True, capture_output=True).stdout.decode()
    return [p for p in out.split("\0") if p]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--all", action="store_true", help="every tracked *.md except the wiki corpus; report only")
    ap.add_argument("--root", default=None, help="repository root (default: git toplevel)")
    args = ap.parse_args()
    root = Path(args.root) if args.root else Path(subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True).stdout.strip())
    problems = []
    for rel in tracked_markdown(root):
        if in_scope(rel, args.all) and (root / rel).is_file():
            problems.extend(check_file(root / rel, root))
    for p in problems:
        print(p)
    if args.all:
        print(f"{len(problems)} broken link(s) (report only)", file=sys.stderr)
        return 0
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

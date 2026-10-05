#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
if ! python3 -c 'import pytest' 2>/dev/null; then
  python3 -m pip install --quiet --user pytest
fi
exec python3 -m pytest -q bin/tests/test_wiki2markdown.py

#!/usr/bin/env bash
# bin/tests/test-check-md-links.sh — exercises bin/check-md-links.py against a throwaway git repo.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${HERE}/check-md-links.py"
fail=0
pass() { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fail=1; }

repo="$(mktemp -d)"; trap 'rm -rf "${repo}"' EXIT
git -C "${repo}" init -q
mkdir -p "${repo}/docs/admin" "${repo}/docs/archive" "${repo}/bin"
echo '#!/bin/sh' > "${repo}/bin/tool.sh"
cat > "${repo}/README.md" <<'EOF'
[ok](docs/admin/Guide.md) [ok-anchor](docs/admin/Guide.md#setup) [web](https://example.com/x.md)
[mail](mailto:a@b.c) [frag](#local) [dir](bin/) ![img](docs/admin/missing.png)
```
[ignored-in-fence](nope.md)
```
`[ignored-inline](nope2.md)`
EOF
cat > "${repo}/docs/admin/Guide.md" <<'EOF'
[up](../../bin/tool.sh) [broken](../user/Nope.md)
[ref]: ../../README.md
EOF
echo '[stale](../../gone.md)' > "${repo}/docs/archive/Old.md"
git -C "${repo}" add -A >/dev/null

out="$(python3 "${CHECKER}" --root "${repo}" || true)"
grep -q 'docs/admin/Guide.md:1: broken link -> ../user/Nope.md' <<<"${out}" && pass "reports broken relative link" || bad "missed broken link: ${out}"
grep -q 'README.md:2: broken link -> docs/admin/missing.png' <<<"${out}" && pass "checks image links" || bad "missed broken image: ${out}"
grep -q 'nope.md\|nope2.md' <<<"${out}" && bad "checked links inside code" || pass "ignores code fences and inline code"
grep -q 'example.com\|mailto\|#local' <<<"${out}" && bad "checked external/anchor-only link" || pass "skips external and anchor-only links"
grep -q 'archive/Old.md' <<<"${out}" && bad "default scope included docs/archive" || pass "default scope excludes docs/archive"
python3 "${CHECKER}" --root "${repo}" >/dev/null && bad "exit 0 despite broken links" || pass "exit 1 on broken links"

all="$(python3 "${CHECKER}" --root "${repo}" --all)"; rc=$?
grep -q 'docs/archive/Old.md:1: broken link -> ../../gone.md' <<<"${all}" && pass "--all reports archive" || bad "--all missed archive: ${all}"
[ "${rc}" -eq 0 ] && pass "--all is report-only" || bad "--all exited ${rc}"

sed -i 's#\[broken\](../user/Nope.md)##' "${repo}/docs/admin/Guide.md"
sed -i 's#!\[img\](docs/admin/missing.png)##' "${repo}/README.md"
python3 "${CHECKER}" --root "${repo}" >/dev/null && pass "exit 0 when clean" || bad "non-zero exit on a clean tree"
exit "${fail}"

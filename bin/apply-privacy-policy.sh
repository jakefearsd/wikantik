#!/usr/bin/env bash
# Apply a drafted PrivacyPolicy wiki page to the repo mirror and to prod.
#
# Usage: bin/apply-privacy-policy.sh [--dry-run] [--yes] DRAFT.md [CONTACT_EMAIL]
#   --yes  skip the confirmation (needed when run without a terminal, e.g. via `!`)
#
# DRAFT.md is the full prod page (frontmatter + body + Cluster Hub footer) with
# {{CONTACT_EMAIL}} placeholders and an optional leading <!-- ... --> note line.
# CONTACT_EMAIL defaults to the mailto: address already in the repo copy, so the
# address never has to be typed into the draft.
#
# Steps: 1. back up the live prod page and show its diff against the new text;
#        2. confirm; 3. pages-push the new page (CRLF, like every prod page);
#        4. rewrite docs/wikantik-pages/PrivacyPolicy.md (repo frontmatter kept,
#        date bumped, body replaced, no prod-only footer, CRLF); 5. verify.
# PrivacyPolicy is a shipped system page, so the admin MCP refuses to edit it.
set -euo pipefail

cd "$(dirname "$0")/.."
DRY=""; YES=""
while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --dry-run) DRY="--dry-run" ;;
    --yes) YES=1 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
  shift
done
DRAFT="${1:?usage: bin/apply-privacy-policy.sh [--dry-run] [--yes] DRAFT.md [CONTACT_EMAIL]}"
REPO_PAGE="docs/wikantik-pages/PrivacyPolicy.md"
[[ -f "$DRAFT" ]] || { echo "no such draft: $DRAFT" >&2; exit 2; }

EMAIL="${2:-$(tr -d '\r' < "$REPO_PAGE" | sed -n 's/.*(mailto:\([^)]*\)).*/\1/p' | head -1)}"
[[ "$EMAIL" == *@* ]] || { echo "could not determine the contact email; pass it as the 2nd argument" >&2; exit 2; }

# shellcheck disable=SC1091
set -a; source remote.env; set +a
TARGET="${REMOTE_USER}@${REMOTE_HOST}"
PROD_FILE="${REMOTE_PAGES_DIR}/PrivacyPolicy.md"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/push"

# New prod text: drop a leading HTML-comment note, fill the email, CRLF.
sed '1{/^<!--.*-->$/d}' "$DRAFT" \
  | sed "s/{{CONTACT_EMAIL}}/${EMAIL}/g" \
  | sed 's/\r$//; s/$/\r/' > "$STAGE/push/PrivacyPolicy.md"
if grep -q '{{' "$STAGE/push/PrivacyPolicy.md"; then
  echo "draft still has {{...}} placeholders:" >&2; grep -n '{{' "$STAGE/push/PrivacyPolicy.md" >&2; exit 1
fi
head -1 "$STAGE/push/PrivacyPolicy.md" | grep -q '^---' || { echo "draft must start with frontmatter" >&2; exit 1; }

# 1. Back up live prod copy, show the change.
BACKUP="${TMPDIR:-/tmp}/PrivacyPolicy.prod-backup.$(date +%Y%m%d%H%M%S).md"
ssh "$TARGET" cat "$PROD_FILE" > "$BACKUP"
echo "Prod copy backed up to $BACKUP"
diff <(tr -d '\r' < "$BACKUP") <(tr -d '\r' < "$STAGE/push/PrivacyPolicy.md") || true
echo

# 2. Confirm.
if [[ -z "$YES" ]]; then
  if ! read -r -p "Push this to ${REMOTE_HOST} and update ${REPO_PAGE}? [y/N] " ans; then
    echo "no terminal to confirm on; review the diff above and re-run with --yes" >&2; exit 1
  fi
  [[ "$ans" == [yY]* ]] || { echo "aborted"; exit 1; }
fi

# 3. Push (touch so rsync --update sends it).
touch "$STAGE/push/PrivacyPolicy.md"
bin/remote.sh $DRY pages-push "$STAGE/push"

# 4. Repo mirror: repo frontmatter with the draft's date, draft body minus footer.
if [[ -n "$DRY" ]]; then echo "(dry run: ${REPO_PAGE} left unchanged)"; exit 0; fi
python3 - "$REPO_PAGE" "$STAGE/push/PrivacyPolicy.md" <<'PY'
import re, sys
repo_path, new_path = sys.argv[1], sys.argv[2]
def split(text):
    m = re.match(r'---\n(.*?)\n---\n(.*)', text, re.S)
    if not m: sys.exit(f"no frontmatter in {repo_path if text is None else 'input'}")
    return m.group(1), m.group(2)
repo_fm, _ = split(open(repo_path, newline='').read().replace('\r\n', '\n'))
new_fm, new_body = split(open(new_path, newline='').read().replace('\r\n', '\n'))
date = re.search(r"^date: .*$", new_fm, re.M)
if date: repo_fm = re.sub(r"^date: .*$", date.group(0), repo_fm, flags=re.M)
new_body = re.sub(r"\n+---\n+\*\*Cluster Hub:\*\*.*\Z", "\n", new_body, flags=re.S)
out = f"---\n{repo_fm}\n---\n{new_body}"
open(repo_path, 'w', newline='').write(out.replace('\n', '\r\n'))
PY
echo "Updated $REPO_PAGE (uncommitted)."

# 5. Verify.
if [[ -z "$DRY" ]]; then
  if ssh "$TARGET" cat "$PROD_FILE" | cmp -s - "$STAGE/push/PrivacyPolicy.md"; then
    echo "OK: prod file matches the new text."
  else
    echo "WARNING: prod file differs from what was pushed" >&2; exit 1
  fi
  echo "Served (may be cached up to ~1h):"
  curl -fsS "http://${REMOTE_HOST}:8080/wiki/PrivacyPolicy?format=md" | grep -m3 -n -E 'Effective|Reader analytics' || true
fi

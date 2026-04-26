#!/bin/bash
# bin/full-rebuild.sh — orchestrate the full content + KG rebuild pipeline.
#
# Phases (each polls to completion before the next starts):
#   1. POST /admin/content/rebuild-indexes  — wipe + re-chunk + Lucene
#   2. POST /admin/content/reindex-embeddings — re-embed every chunk
#   3. (optional, --reset-kg) DELETE pending proposals + ai-inferred nodes
#   4. bin/runextractor.sh ... — re-extract mentions and proposals
#
# Each phase can be skipped (--skip-chunks / --skip-embeddings / --skip-extract)
# so you can resume mid-pipeline. Dry-run mode (--dry-run) prints the plan.
#
# Args after `--` (or unrecognised args) are forwarded to bin/runextractor.sh:
#
#   bin/full-rebuild.sh --reset-kg -- \
#       --ollama-model qwen2.5:1.5b-instruct \
#       --concurrency 6 --prefilter --force
#
# Pre-flight requirements:
#   - Tomcat running on $TOMCAT_URL (default http://localhost:8080)
#   - psql + java + curl + jq on PATH
#   - test.properties present with admin testbot creds
#   - tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml deployed
#
# Documentation: docs/wikantik-pages/SearchIndexAndKnowledgeGraphRebuild.md

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONTEXT_XML="${ROOT_DIR}/tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"
TEST_PROPS="${ROOT_DIR}/test.properties"
TOMCAT_URL="${TOMCAT_URL:-http://localhost:8080}"
POLL_SECONDS="${POLL_SECONDS:-10}"

if [[ -t 1 ]]; then
    GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'
    BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
else
    GREEN=''; YELLOW=''; RED=''; BOLD=''; DIM=''; NC=''
fi
info()  { echo -e "${GREEN}[full-rebuild]${NC} $*"; }
phase() { echo; echo -e "${BOLD}== $* ==${NC}"; }
warn()  { echo -e "${YELLOW}[full-rebuild]${NC} $*" >&2; }
die()   { echo -e "${RED}[full-rebuild]${NC} $*" >&2; exit 1; }

# ---- Args ----

RESET_KG=0
SKIP_CHUNKS=0
SKIP_EMBEDDINGS=0
SKIP_EXTRACT=0
DRY_RUN=0
ASSUME_YES=0
EXTRACTOR_ARGS=()

usage() {
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# \?//' | head -n -2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reset-kg)         RESET_KG=1 ;;
        --skip-chunks)      SKIP_CHUNKS=1 ;;
        --skip-embeddings)  SKIP_EMBEDDINGS=1 ;;
        --skip-extract)     SKIP_EXTRACT=1 ;;
        --dry-run)          DRY_RUN=1 ;;
        --yes|-y)           ASSUME_YES=1 ;;
        -h|--help)          usage; exit 0 ;;
        --)                 shift; EXTRACTOR_ARGS+=("$@"); break ;;
        *)                  EXTRACTOR_ARGS+=("$1") ;;
    esac
    shift
done

# ---- Pre-flight ----

for cmd in curl jq java psql; do
    command -v "$cmd" >/dev/null 2>&1 || die "$cmd is not on PATH"
done
[[ -f "$CONTEXT_XML" ]] || die "$CONTEXT_XML missing — first-time deploy required"
[[ -f "$TEST_PROPS"  ]] || die "$TEST_PROPS missing — see CLAUDE.md (Manual Testing Credentials)"

PG_PASSWORD=$(grep -oE 'password="[^"]+"' "$CONTEXT_XML" | head -1 | sed -E 's/password="([^"]+)"/\1/')
PG_USER=$(grep -oE 'username="[^"]+"' "$CONTEXT_XML" | head -1 | sed -E 's/username="([^"]+)"/\1/')
PG_DB=$(grep -oE 'url="[^"]+"' "$CONTEXT_XML" | head -1 \
        | sed -E 's|.*postgresql://[^/]+/([^"?]+).*|\1|')
ADMIN_LOGIN=$(grep '^test.user.login'    "$TEST_PROPS" | cut -d= -f2)
ADMIN_PASSWORD=$(grep '^test.user.password' "$TEST_PROPS" | cut -d= -f2)

[[ -n "$PG_PASSWORD"    ]] || die "could not extract PostgreSQL password from ROOT.xml"
[[ -n "$ADMIN_LOGIN"    ]] || die "test.user.login missing in test.properties"
[[ -n "$ADMIN_PASSWORD" ]] || die "test.user.password missing in test.properties"

PSQL=( env "PGPASSWORD=$PG_PASSWORD" psql -h localhost -U "$PG_USER" "$PG_DB" )
CURL_AUTH=( -u "${ADMIN_LOGIN}:${ADMIN_PASSWORD}" )

# Check Tomcat reachability up front so we don't fail mid-pipeline.
if ! curl -fsS -m 5 "${CURL_AUTH[@]}" "$TOMCAT_URL/admin/content/index-status" >/dev/null 2>&1; then
    die "$TOMCAT_URL/admin/content/index-status unreachable — is Tomcat running and credentials valid?"
fi

# ---- Plan ----

phase "Plan"
info "Tomcat:        $TOMCAT_URL"
info "Postgres:      $PG_USER@$PG_DB"
info "Phases:"
[[ $SKIP_CHUNKS     -eq 0 ]] && info "  1. chunk + Lucene rebuild" \
                              || warn "  1. chunk + Lucene rebuild — SKIPPED"
[[ $SKIP_EMBEDDINGS -eq 0 ]] && info "  2. chunk embedding reindex" \
                              || warn "  2. chunk embedding reindex — SKIPPED"
[[ $RESET_KG        -eq 1 ]] && warn "  3. KG reset (DELETE pending proposals + ai-inferred nodes)" \
                              || info "  3. KG reset — DISABLED (--reset-kg to enable)"
[[ $SKIP_EXTRACT    -eq 0 ]] && info "  4. entity extraction: ${EXTRACTOR_ARGS[*]:-(no extra args)}" \
                              || warn "  4. entity extraction — SKIPPED"

if [[ $DRY_RUN -eq 1 ]]; then
    warn "DRY RUN — exiting without executing."
    exit 0
fi

if [[ $RESET_KG -eq 1 && $ASSUME_YES -eq 0 ]]; then
    echo
    echo -e "${YELLOW}--reset-kg will DELETE all pending proposals AND every kg_node with"
    echo -e "provenance='ai-inferred' (cascading to their edges). Human-authored and"
    echo -e "AI-reviewed nodes are preserved.${NC}"
    read -r -p "Proceed? [y/N] " yn
    [[ "$yn" =~ ^[Yy]$ ]] || die "aborted by operator"
fi

# ---- Wall-clock summary on exit ----

SECONDS=0
trap '
    rc=$?
    h=$(( SECONDS / 3600 )); m=$(( (SECONDS%3600)/60 )); s=$(( SECONDS%60 ))
    if [[ $rc -eq 0 ]]; then
        info "Done in $(printf %dh%02dm%02ds $h $m $s) (exit 0)."
    else
        warn "Exited with status $rc after $(printf %dh%02dm%02ds $h $m $s)."
    fi
' EXIT

# ---- Polling helpers ----
#
# The chunk rebuild service returns to state=IDLE when a run finishes (it's
# meant to be re-triggered). The embedding bootstrap latches into a terminal
# state (COMPLETED / FAILED / SKIPPED_*) and stays there. Two helpers, one
# per shape.

wait_for_chunk_rebuild() {
    local prev_state=""
    while true; do
        local body state pages_iter pages_total
        body=$(curl -fsS "${CURL_AUTH[@]}" "$TOMCAT_URL/admin/content/index-status") \
            || die "chunk rebuild: status fetch failed"
        state=$(jq -r '.rebuild.state' <<<"$body")
        pages_iter=$(jq -r '.rebuild.pages_iterated // 0' <<<"$body")
        pages_total=$(jq -r '.rebuild.pages_total // 0' <<<"$body")
        if [[ "$state" != "$prev_state" ]]; then
            info "chunk rebuild: state=$state (pages $pages_iter / $pages_total)"
            prev_state="$state"
        fi
        case "$state" in
            IDLE)  return 0 ;;
            ERROR) warn "rebuild snapshot: $body"; return 1 ;;
        esac
        sleep "$POLL_SECONDS"
    done
}

wait_for_embedding_bootstrap() {
    local prev_state=""
    while true; do
        local body state processed total
        body=$(curl -fsS "${CURL_AUTH[@]}" "$TOMCAT_URL/admin/content/index-status") \
            || die "embedding reindex: status fetch failed"
        state=$(jq -r '.embeddings.bootstrap.state' <<<"$body")
        processed=$(jq -r '.embeddings.bootstrap.chunks_processed // 0' <<<"$body")
        total=$(jq -r '.embeddings.bootstrap.chunks_total // 0' <<<"$body")
        if [[ "$state" != "$prev_state" ]]; then
            info "embedding reindex: state=$state (chunks $processed / $total)"
            prev_state="$state"
        fi
        case "$state" in
            COMPLETED|SKIPPED_ALREADY_POPULATED|SKIPPED_NO_CHUNKS) return 0 ;;
            FAILED|DISABLED) warn "embedding snapshot: $body"; return 1 ;;
        esac
        sleep "$POLL_SECONDS"
    done
}

# ---- Phase 1: chunk + Lucene rebuild ----

if [[ $SKIP_CHUNKS -eq 0 ]]; then
    phase "Phase 1 — chunk + Lucene rebuild"
    code=$(curl -s -o /tmp/full-rebuild-resp -w "%{http_code}" \
                "${CURL_AUTH[@]}" -X POST "$TOMCAT_URL/admin/content/rebuild-indexes")
    case "$code" in
        202) info "Triggered. Polling /admin/content/index-status…" ;;
        409) warn "Rebuild already in flight; will wait for it to finish." ;;
        *)   die  "rebuild-indexes returned HTTP $code: $(cat /tmp/full-rebuild-resp)" ;;
    esac
    wait_for_chunk_rebuild || die "chunk rebuild failed; see /admin/content/index-status"
fi

# ---- Phase 2: chunk embedding reindex ----

if [[ $SKIP_EMBEDDINGS -eq 0 ]]; then
    phase "Phase 2 — chunk embedding reindex"
    code=$(curl -s -o /tmp/full-rebuild-resp -w "%{http_code}" \
                "${CURL_AUTH[@]}" -X POST "$TOMCAT_URL/admin/content/reindex-embeddings")
    case "$code" in
        202) info "Triggered. Polling embeddings.bootstrap…" ;;
        409) warn "Embedding reindex already in flight; will wait." ;;
        503) warn "Hybrid retrieval disabled (HTTP 503) — skipping phase 2."
             SKIP_EMBEDDINGS=1
             ;;
        *)   die  "reindex-embeddings returned HTTP $code: $(cat /tmp/full-rebuild-resp)" ;;
    esac
    if [[ $SKIP_EMBEDDINGS -eq 0 ]]; then
        wait_for_embedding_bootstrap \
            || die "embedding reindex failed; see /admin/content/index-status"
    fi
fi

# ---- Phase 3 (optional): KG reset ----

if [[ $RESET_KG -eq 1 ]]; then
    phase "Phase 3 — KG reset (ai-inferred prune)"

    info "Before:"
    "${PSQL[@]}" -c "SELECT provenance, COUNT(*) FROM kg_nodes GROUP BY provenance ORDER BY 1;" || true
    "${PSQL[@]}" -c "SELECT status, COUNT(*) FROM kg_proposals GROUP BY status ORDER BY 1;" || true

    info "Deleting pending proposals…"
    "${PSQL[@]}" -c "DELETE FROM kg_proposals WHERE status='pending';"
    info "Deleting ai-inferred nodes (cascades to mentions and edges)…"
    "${PSQL[@]}" -c "DELETE FROM kg_nodes WHERE provenance='ai-inferred';"

    info "After:"
    "${PSQL[@]}" -c "SELECT provenance, COUNT(*) FROM kg_nodes GROUP BY provenance ORDER BY 1;" || true
    "${PSQL[@]}" -c "SELECT status, COUNT(*) FROM kg_proposals GROUP BY status ORDER BY 1;" || true
fi

# ---- Phase 4: entity extraction ----

if [[ $SKIP_EXTRACT -eq 0 ]]; then
    phase "Phase 4 — entity extraction"
    if [[ ${#EXTRACTOR_ARGS[@]} -eq 0 ]]; then
        info "No extra extractor args supplied — running with CLI defaults"
        info "(tip: pass after --, e.g. --reset-kg -- --ollama-model qwen2.5:1.5b-instruct --concurrency 6 --prefilter)"
        "$SCRIPT_DIR/runextractor.sh"
    else
        info "Forwarding to bin/runextractor.sh ${EXTRACTOR_ARGS[*]}"
        "$SCRIPT_DIR/runextractor.sh" "${EXTRACTOR_ARGS[@]}"
    fi
fi

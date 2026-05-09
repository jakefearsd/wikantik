#!/bin/bash
#
# run-embedding-experiment.sh — end-to-end driver for the retrieval
# experimentation harness in com.wikantik.search.embedding.experiment.
#
# Workflow:
#   1. Apply eval/experiment-embeddings.sql (idempotent)
#   2. For each model: ExperimentIndexer (embeds kg_content_chunks rows)
#   3. For each model: ExperimentEvaluator (scores BM25/dense/hybrid)
#   4. ExperimentCompare (side-by-side of the three reports)
#
# Prerequisites:
#   - kg_content_chunks is populated — run bin/trigger-rebuild-indexes.sh
#     and wait for /admin/content/index-status to finish
#   - Wiki running at $WIKI_URL (default http://localhost:8080)
#   - Ollama reachable at the URL in ini/wikantik.properties
#
# See docs/wikantik-pages/RetrievalExperimentHarness.md for the full playbook.
#
# Required env:
#   DB_PASSWORD       — PostgreSQL password for jspwiki user
#   WIKI_USER         — admin login for /api/search (from test.properties)
#   WIKI_PASSWORD     — admin password for /api/search
#
# Optional env:
#   MODELS            — space-separated list; default all three candidates
#   DB_HOST/DB_NAME/DB_USER — defaults localhost/jspwiki/jspwiki
#   WIKI_URL          — default http://localhost:8080
#   OUTPUT_DIR        — where reports land; default eval
#   SKIP_DDL=1        — skip step 1
#   SKIP_INDEX=1      — skip step 2 (re-evaluate existing embeddings)
#   MVN_QUIET=1       — suppress Maven chatter (passes -q)
#
# Usage:
#   DB_PASSWORD=... WIKI_USER=testbot WIKI_PASSWORD=... \
#     bin/run-embedding-experiment.sh

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

: "${DB_PASSWORD:?set DB_PASSWORD to the jspwiki DB user password}"
: "${WIKI_USER:?set WIKI_USER (e.g. from test.properties test.user.login)}"
: "${WIKI_PASSWORD:?set WIKI_PASSWORD (from test.properties test.user.password)}"

DB_HOST="${DB_HOST:-localhost}"
DB_NAME="${DB_NAME:-jspwiki}"
DB_USER="${DB_USER:-jspwiki}"
WIKI_URL="${WIKI_URL:-http://localhost:8080}"
OUTPUT_DIR="${OUTPUT_DIR:-eval}"
MODELS="${MODELS:-nomic-embed-v1.5 bge-m3 qwen3-embedding-0.6b}"

QUIET_FLAG=()
[[ "${MVN_QUIET:-}" == "1" ]] && QUIET_FLAG+=( -q )

JDBC_URL="jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}"

EMBED_TIMEOUT_MS="${EMBED_TIMEOUT_MS:-120000}"
EMBED_BATCH_SIZE="${EMBED_BATCH_SIZE:-16}"

SYSPROPS=(
    "-Dwikantik.experiment.db.url=${JDBC_URL}"
    "-Dwikantik.experiment.db.user=${DB_USER}"
    "-Dwikantik.experiment.db.password=${DB_PASSWORD}"
    "-Dwikantik.experiment.wiki.base-url=${WIKI_URL}"
    "-Dwikantik.experiment.wiki.user=${WIKI_USER}"
    "-Dwikantik.experiment.wiki.password=${WIKI_PASSWORD}"
    "-Dwikantik.search.embedding.timeout-ms=${EMBED_TIMEOUT_MS}"
    "-Dwikantik.search.embedding.batch-size=${EMBED_BATCH_SIZE}"
)

mkdir -p "${OUTPUT_DIR}"

# exec:java attaches to every project selected by -pl/-am, so it would try to
# run the main class against the root reactor first. Root exec:java against
# wikantik-main only (its deps must already be installed in the local repo
# from a prior `mvn install`).
run_main() {
    local main_class="$1"; shift
    local exec_args="$*"
    mvn -f wikantik-main/pom.xml "${QUIET_FLAG[@]}" exec:java \
        -Dexec.mainClass="${main_class}" \
        -Dexec.classpathScope=compile \
        -Dexec.args="${exec_args}" \
        "${SYSPROPS[@]}"
}

step=1

if [[ "${SKIP_DDL:-}" != "1" ]]; then
    echo "== [${step}] apply sandbox DDL"; step=$((step+1))
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -U "${DB_USER}" -d "${DB_NAME}" \
        -v ON_ERROR_STOP=1 -f eval/experiment-embeddings.sql
fi

if [[ "${SKIP_INDEX:-}" != "1" ]]; then
    for m in ${MODELS}; do
        echo "== [${step}] indexer: ${m}"; step=$((step+1))
        run_main com.wikantik.search.embedding.experiment.ExperimentIndexer "${m}"
    done
fi

declare -a REPORTS
for m in ${MODELS}; do
    report="${OUTPUT_DIR}/report-${m}.txt"
    echo "== [${step}] evaluator: ${m} → ${report}"; step=$((step+1))
    run_main com.wikantik.search.embedding.experiment.ExperimentEvaluator \
        "${m} eval/retrieval-queries.csv ${report}"
    REPORTS+=( "${report}" )
done

echo "== [${step}] compare"
run_main com.wikantik.search.embedding.experiment.ExperimentCompare "${REPORTS[*]}"

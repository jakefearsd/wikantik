#!/bin/bash
#
# 2026-05-20: One-shot backfill — copies content_chunk_embeddings.vec (BYTEA)
# into the embedding vector(1024) column added by V032. Idempotent — re-running
# without --force is a no-op once the backfill has completed for a given model.
#
# Reads DB connection from the standard PG env vars (PGHOST/PGPORT/PGUSER/
# PGPASSWORD/DB_NAME), matching bin/db/migrate.sh's convention. The Java CLI
# (PgVectorBackfillCli) handles the BYTEA-to-pgvector codec; this wrapper just
# parses flags and finds the jar.
#
# Run BEFORE flipping wikantik.search.dense.backend to pgvector so the HNSW
# index has data to retrieve.
#
# Usage:
#   bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh [--force] [--model NAME]
#
# Options:
#   --force         Overwrite existing embedding values (default: skip non-null).
#   --model NAME    Model code to backfill (default: bge-m3 — set explicitly if
#                   your wikantik.search.embedding.model is different).
#   -h, --help      Show this help.
#
# Environment:
#   PGHOST     (default: localhost)
#   PGPORT     (default: 5432)
#   DB_NAME    (default: wikantik)
#   PGUSER     (default: jspwiki)
#   PGPASSWORD (required)
#
# Idempotent — re-running without --force is a no-op once the backfill has
# completed for a given model. Run once per model code in use.
#
# Examples:
#   PGPASSWORD=... bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh
#   PGPASSWORD=... bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh --force
#   PGPASSWORD=... bin/db/one-shots/2026-05-20-backfill-chunk-embeddings.sh --model qwen3-embedding-0.6b

set -euo pipefail

show_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

MODEL="bge-m3"
FORCE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)    FORCE="--force"; shift ;;
        --model)    MODEL="$2"; shift 2 ;;
        -h|--help)  show_help; exit 0 ;;
        *)          echo "unknown arg: $1" >&2; show_help; exit 2 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Check Java availability
command -v java >/dev/null 2>&1 || {
    echo "java not found on PATH" >&2
    exit 1
}

# Check Maven availability
command -v mvn >/dev/null 2>&1 || {
    echo "mvn not found on PATH (needed to run the CLI)" >&2
    exit 1
}

echo "Running PgVectorBackfillCli (model=${MODEL}, force=${FORCE:-no})..."
( cd "$REPO_ROOT" && \
  mvn -pl wikantik-main -q exec:java \
      -Dexec.mainClass=com.wikantik.search.embedding.PgVectorBackfillCli \
      -Dexec.args="${MODEL}${FORCE:+ --force}" )

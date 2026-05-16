# Database Updates

The Wikantik schema is managed by the migration runner at
[`bin/db/migrate.sh`](../bin/db/migrate.sh) — all DDL lives in
[`bin/db/migrations/`](../bin/db/migrations/) and is applied
idempotently in version order. The legacy DDL scripts under
`wikantik-war/src/main/config/db/` (`postgresql.ddl`,
`postgresql-knowledge.ddl`, `postgresql-permissions.ddl`,
`postgresql-hub.ddl`) are historical reference only and should not be
applied directly to a new install.

`bin/db/install-fresh.sh` does the right thing for a fresh database
(creates database, app role, pgvector extension, then runs every
migration in order).

## Migration timeline

The migration directory is the source of truth. As of the latest
release, schema state runs from V001 through V030:

| Migration | What it adds |
|-----------|--------------|
| V001 | `schema_migrations` ledger table — bootstraps the migrate runner itself |
| V002 | Core auth: `users`, `roles`, `groups`, `group_members` |
| V003 | `policy_grants` — database-backed authorisation (groups, roles, principals → permissions) |
| V004 | Knowledge Graph baseline: `kg_nodes`, `kg_edges`, `kg_proposals`, `kg_rejections`; installs the `vector` extension |
| V005 | `hub_centroids`, `hub_proposals` — hub overview support |
| V006, V007 | `hub_discovery_proposals` + status tracking |
| V008 | `kg_content_chunks` — page-passage chunking for retrieval |
| V009 | `content_chunk_embeddings` — Ollama-backed dense embeddings (BYTEA float32) |
| V010 | `api_keys` — bearer-token auth for MCP servers |
| V011 | `chunk_entity_mentions` — joins KG nodes to chunks (used to derive node centroids) |
| V012 | Retire the legacy graph projector — replaced by direct `kg_edges` writes |
| V013 | `page_canonical_ids`, `page_slug_history` — rename-stable identifiers |
| V014 | `page_verification` + runbook tables — Agent-Grade Content metadata |
| V015 | Deduplicate user profiles (one-time data fix) |
| V016, V017 | `retrieval_runs`, `retrieval_query_set` — nightly retrieval-quality CI |
| V018 | `kg_cluster_policy`, `kg_policy_audit`, `kg_excluded_pages` — KG inclusion / exclusion policy |
| V019 | Drop the legacy `kg_embeddings` and `kg_content_embeddings` tables (superseded by the unified Ollama-backed stack) |
| V020 | `kg_proposals.signature` — dedupe column for the entity extractor |
| V021, V022 | `kg_node_embeddings` + `model_code` — KG-node-level vectors (centroid of mention chunks) |
| V023 | **Drop `page_relations`** — typed relations frontmatter (`links_to`, `mentions`, `part_of`) was retired 2026-05-02 in favour of structural-spine `canonical_id` + cluster + tags |
| V024 | KG staged validation tables — pre-promotion checks before pending proposals become live nodes |
| V025 | KG judge timeout tracking — record cron-cycle abstain reasons so the operator queue distinguishes "judge timed out" from "judge said no" |
| V026 | Seed additional `retrieval_query_sets` / `retrieval_queries` rows — expands the retrieval-quality CI query set |
| V027 | `kg_edges_relationship_type_check` — CHECK constraint restricting `relationship_type` to the allowed vocabulary |
| V028 | `kg_edge_audit` — audit trail for KG edge create / update / delete |
| V029 | Extend the `kg_edge_audit` action CHECK to add the `CONFIRM` action |
| V030 | Broaden the `kg_edges` relationship-type CHECK to allow `generalizes` |

Two follow-up migration policies:

- **Schema additions are versioned** — every commit that changes
  schema adds the next `V<NNN>__<desc>.sql`.
- **Data backfills are NOT versioned** — one-shots live under
  `bin/db/one-shots/` (`reconcile_page_canonical_ids.sh`,
  `reset_judge_timeout_abstains.sh`, `reset_node_judge_verdicts.sh`).
  See `bin/db/migrations/README.md` for the full rule.

## Where the schema is defined now

| Surface | Source of truth |
|---------|------------------|
| Active migrations | `bin/db/migrations/V001..V025` |
| Migration runner | `bin/db/migrate.sh` (idempotent, single-tx per migration) |
| Fresh install | `bin/db/install-fresh.sh` (creates DB + role + pgvector + runs migrations) |
| Legacy reference DDL | `bin/db/postgresql*.ddl` (do not apply directly) |
| Migration conventions | [`bin/db/migrations/README.md`](../bin/db/migrations/README.md) |

## Index notes (Knowledge Graph)

A few indexes from V004 are currently unused by application queries:

- `idx_kg_nodes_properties` — GIN index on `kg_nodes.properties`. The
  current `searchNodes` query casts properties to text and uses `LIKE`,
  bypassing the index. To activate it, the query would need to use
  JSONB containment operators (`@>`) instead. Until then, the index
  costs writes for no reads.
- `idx_kg_edges_type` and `idx_kg_edges_provenance` — no current code
  path filters edges by relationship type or provenance in isolation.
  Useful when those filters land; safe to drop if write performance
  on `kg_edges` becomes a concern.

These have been left in place because dropping them is a (small) data
migration and they're cheap relative to the KG-node embedding overhead
introduced by V021.

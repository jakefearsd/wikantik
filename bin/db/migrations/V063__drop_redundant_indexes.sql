-- V063: drop indexes that are redundant (their columns lead another index or the PK) or that no
-- query can use, found by the 2026-10-22 DB/disk profiling run (15 days of prod pg_stat data).
-- Each one only costs write amplification and disk.
--
-- Covered by the leading column(s) of a unique constraint / PK / other index:
--   idx_kg_content_chunks_page_name        UNIQUE kg_content_chunks_page_index_uniq (page_name, chunk_index)
--   kg_nodes_tier_idx                      kg_nodes_tier_name_idx (tier, name)
--   idx_svs_date                           PK (snapshot_date, engine, site_host, page_path, query_text)
--   idx_chunk_entity_mentions_chunk        PK (chunk_id, node_id)
--   idx_kg_edges_source                    UNIQUE (source_id, target_id, relationship_type)
--   idx_hub_proposals_hub                  UNIQUE (hub_name, page_name)
--   ix_retrieval_runs_mode                 ix_retrieval_runs_mode_started (mode, started_at DESC) from V062
--   idx_citations_source                   UNIQUE uq_citation (source_canonical_id, ...)
--   idx_audit_log_seq                      PK (seq, created_at) -- audit_log is partitioned; dropping the
--                                          parent index drops the partitions' copies. The hash-chain head
--                                          (ORDER BY seq DESC LIMIT 1) is served by the PK.
--   idx_comment_mentions_comment_id        UNIQUE uq_comment_mentions (comment_id, mentioned_login)
--   idx_connector_synced_item_connector    PK (connector_id, source_uri)
--
-- Unusable by any query in the codebase (0 scans in prod, and no production SQL filters on the column
-- in a way the index could serve):
--   idx_kg_nodes_properties                GIN on kg_nodes.properties; code only does properties->>'status' = ?
--                                          and LOWER(properties::text) LIKE ?, neither of which a GIN can serve
--   idx_kg_content_chunks_content_hash     content_hash is only ever read, never filtered on
--   ix_canonical_ids_type                  page_canonical_ids is only queried by canonical_id / current_slug
--   ix_canonical_ids_cluster               (same)
--   kg_judge_timeouts_content_sha_idx      kg_judge_timeouts is only queried by proposal_id / ordered by count
--
-- Idempotent: DROP INDEX IF EXISTS. DDL-only.

DROP INDEX IF EXISTS idx_kg_content_chunks_page_name;
DROP INDEX IF EXISTS kg_nodes_tier_idx;
DROP INDEX IF EXISTS idx_svs_date;
DROP INDEX IF EXISTS idx_chunk_entity_mentions_chunk;
DROP INDEX IF EXISTS idx_kg_edges_source;
DROP INDEX IF EXISTS idx_hub_proposals_hub;
DROP INDEX IF EXISTS ix_retrieval_runs_mode;
DROP INDEX IF EXISTS idx_citations_source;
DROP INDEX IF EXISTS idx_audit_log_seq;
DROP INDEX IF EXISTS idx_comment_mentions_comment_id;
DROP INDEX IF EXISTS idx_connector_synced_item_connector;

DROP INDEX IF EXISTS idx_kg_nodes_properties;
DROP INDEX IF EXISTS idx_kg_content_chunks_content_hash;
DROP INDEX IF EXISTS ix_canonical_ids_type;
DROP INDEX IF EXISTS ix_canonical_ids_cluster;
DROP INDEX IF EXISTS kg_judge_timeouts_content_sha_idx;

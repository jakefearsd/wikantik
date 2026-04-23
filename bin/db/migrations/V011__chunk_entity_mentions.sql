-- V011: chunk_entity_mentions — bridge between kg_content_chunks and kg_nodes.
-- Every row records that the given chunk mentions the given KG node (i.e. the
-- entity appears in the chunk's text), with a confidence score and the name of
-- the extractor that produced the mention. This is the table the unified
-- (Ollama) embedding stack uses to derive per-node vectors as the centroid of
-- its mention-chunk vectors — see com.wikantik.knowledge.embedding.NodeMentionSimilarity.
--
-- Populated by Phase 2's pluggable EntityExtractor pipeline. Phase 1 introduces
-- the table only; readers tolerate an empty table by returning empty results.

CREATE TABLE IF NOT EXISTS chunk_entity_mentions (
    chunk_id     UUID        NOT NULL REFERENCES kg_content_chunks(id) ON DELETE CASCADE,
    node_id      UUID        NOT NULL REFERENCES kg_nodes(id)           ON DELETE CASCADE,
    confidence   REAL        NOT NULL DEFAULT 1.0,
    extractor    TEXT        NOT NULL,
    extracted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (chunk_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_chunk_entity_mentions_node
    ON chunk_entity_mentions (node_id);
CREATE INDEX IF NOT EXISTS idx_chunk_entity_mentions_chunk
    ON chunk_entity_mentions (chunk_id);
CREATE INDEX IF NOT EXISTS idx_chunk_entity_mentions_extractor
    ON chunk_entity_mentions (extractor);

GRANT SELECT, INSERT, UPDATE, DELETE ON chunk_entity_mentions TO :app_user;

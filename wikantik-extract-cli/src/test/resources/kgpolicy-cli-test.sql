-- KG inclusion policy (V018) plus minimal kg_nodes / kg_edges / kg_content_chunks
-- so purge SQL can compile against the schema.

CREATE TABLE IF NOT EXISTS kg_cluster_policy (
    cluster      TEXT PRIMARY KEY,
    action       TEXT NOT NULL CHECK (action IN ('include','exclude')),
    reason       TEXT,
    set_by       TEXT NOT NULL,
    set_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS kg_policy_audit (
    id          BIGSERIAL PRIMARY KEY,
    cluster     TEXT NOT NULL,
    old_action  TEXT,
    new_action  TEXT NOT NULL,
    reason      TEXT,
    actor       TEXT NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kg_excluded_pages (
    page_name   TEXT PRIMARY KEY,
    reason      TEXT NOT NULL CHECK (reason IN
                  ('system_page','cluster_policy','page_override')),
    excluded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Minimal stubs to satisfy purge SQL
CREATE TABLE IF NOT EXISTS kg_nodes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT         NOT NULL,
    source_page TEXT
);
CREATE TABLE IF NOT EXISTS kg_edges (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL,
    target_id UUID NOT NULL
);
CREATE TABLE IF NOT EXISTS kg_content_chunks (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_name TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS chunk_entity_mentions (
    id        BIGSERIAL PRIMARY KEY,
    chunk_id  UUID NOT NULL
);

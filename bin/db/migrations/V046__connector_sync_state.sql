-- External source connector sync state (ConnectorFramework Phase 1, 2026-07-11).
-- connector_sync_state: one row per connector holding its opaque cursor/checkpoint.
-- connector_synced_item: per-item state for hash-dedup, tombstone detection, and
-- (carried, unenforced in Phase 1) source ACL references.
CREATE TABLE IF NOT EXISTS connector_sync_state (
    connector_id TEXT PRIMARY KEY,
    cursor       TEXT,
    last_run     TIMESTAMPTZ,
    status       TEXT
);
CREATE TABLE IF NOT EXISTS connector_synced_item (
    connector_id TEXT NOT NULL,
    source_uri   TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    page_name    TEXT NOT NULL,
    acl_refs     TEXT NOT NULL DEFAULT '[]',
    first_synced TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, source_uri)
);
CREATE INDEX IF NOT EXISTS idx_connector_synced_item_connector ON connector_synced_item (connector_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_sync_state  TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_synced_item TO :app_user;

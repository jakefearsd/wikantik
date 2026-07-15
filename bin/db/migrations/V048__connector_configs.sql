-- Admin-managed connector definitions (Connector Admin UI P2.4, 2026-07-15).
-- Non-secret, type-specific settings live in config (JSON); secrets stay in
-- connector_credentials (V047). sync_interval_hours 0 = manual-only.
CREATE TABLE IF NOT EXISTS connector_configs (
    connector_id        TEXT PRIMARY KEY,
    connector_type      TEXT NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    sync_interval_hours INTEGER NOT NULL DEFAULT 0,
    config              TEXT NOT NULL,
    cluster             TEXT,
    default_tags        TEXT,
    page_prefix         TEXT,
    created             TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified            TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_configs TO :app_user;

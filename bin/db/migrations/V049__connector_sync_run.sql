-- Per-run connector sync history (Connector Admin UI P2.4, 2026-07-15).
-- One row per SyncOrchestrator drain; status: running | ok | failed. A row
-- stuck in 'running' means the JVM died mid-sync (rendered as interrupted).
CREATE TABLE IF NOT EXISTS connector_sync_run (
    run_id       BIGSERIAL PRIMARY KEY,
    connector_id TEXT NOT NULL,
    trigger_kind TEXT NOT NULL,
    started      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished     TIMESTAMPTZ,
    status       TEXT NOT NULL DEFAULT 'running',
    created      INTEGER NOT NULL DEFAULT 0,
    updated      INTEGER NOT NULL DEFAULT 0,
    unchanged    INTEGER NOT NULL DEFAULT 0,
    deleted      INTEGER NOT NULL DEFAULT 0,
    failed       INTEGER NOT NULL DEFAULT 0,
    error        TEXT
);
CREATE INDEX IF NOT EXISTS idx_sync_run_connector ON connector_sync_run (connector_id, started DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_sync_run TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE connector_sync_run_run_id_seq TO :app_user;

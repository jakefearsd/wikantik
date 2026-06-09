-- V038: drift dashboard aggregate snapshots (drift_sweeps + drift_snapshot_counts).
-- Column is triggered_by, not "trigger" — TRIGGER is a keyword in H2 (unit tests).

CREATE TABLE IF NOT EXISTS drift_sweeps (
    id            BIGSERIAL PRIMARY KEY,
    swept_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    pages_scanned INT         NOT NULL,
    duration_ms   BIGINT      NOT NULL,
    triggered_by  TEXT        NOT NULL,
    shacl_checked BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS drift_snapshot_counts (
    sweep_id  BIGINT NOT NULL REFERENCES drift_sweeps(id) ON DELETE CASCADE,
    family    TEXT   NOT NULL,
    code      TEXT   NOT NULL,
    severity  TEXT   NOT NULL,
    count     INT    NOT NULL,
    PRIMARY KEY (sweep_id, family, code, severity)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON drift_sweeps, drift_snapshot_counts TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE drift_sweeps_id_seq TO :app_user;

-- Briefing telemetry (S3 instrumentation, design 2026-07-05).
-- surface ∈ 'api_briefing' | 'mcp_get_briefing'
CREATE TABLE IF NOT EXISTS briefing_log (
    id               BIGSERIAL   PRIMARY KEY,
    pins             TEXT,
    clusters         TEXT,
    prompt_present   BOOLEAN     NOT NULL DEFAULT FALSE,
    budget_requested INTEGER     NOT NULL,
    budget_used      INTEGER     NOT NULL,
    section_count    INTEGER     NOT NULL,
    pin_count        INTEGER     NOT NULL,
    pointer_count    INTEGER     NOT NULL,
    surface          TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_briefing_log_created_at ON briefing_log (created_at);
GRANT SELECT, INSERT ON briefing_log TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE briefing_log_id_seq TO :app_user;

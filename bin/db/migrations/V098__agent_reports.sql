-- V098: agent field reports (agent activity transparency).
--
-- Agents file one structured report per piece of work through the admin MCP tool. The store is
-- append-only: the application role is granted SELECT and INSERT and nothing else, so there is no
-- update or delete path. client, session_hash and filed_by are filled by the server, never taken from
-- the agent. Idempotent / DDL-only; the CHECKs are part of CREATE TABLE so a re-run changes nothing.

CREATE TABLE IF NOT EXISTS agent_report (
    id             BIGSERIAL   PRIMARY KEY,
    filed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    client         TEXT        NOT NULL,
    session_hash   TEXT        NOT NULL,
    filed_by       TEXT        NOT NULL,
    workspace      TEXT,
    kind_of_work   TEXT        NOT NULL
        CONSTRAINT agent_report_kind_of_work_chk
        CHECK (kind_of_work IN ('operations','content','build','research','other')),
    products       TEXT[]      NOT NULL,
    skills         TEXT[]      NOT NULL,
    window_start   TIMESTAMPTZ,
    window_end     TIMESTAMPTZ,
    task           TEXT        NOT NULL,
    outcome        TEXT        NOT NULL
        CONSTRAINT agent_report_outcome_chk
        CHECK (outcome IN ('completed','partial','abandoned')),
    context_missing TEXT,
    notes          TEXT
);

CREATE INDEX IF NOT EXISTS idx_agent_report_filed_at     ON agent_report (filed_at);
CREATE INDEX IF NOT EXISTS idx_agent_report_session_hash ON agent_report (session_hash);
CREATE INDEX IF NOT EXISTS idx_agent_report_kind_of_work ON agent_report (kind_of_work);

CREATE TABLE IF NOT EXISTS agent_report_context (
    report_id  BIGINT  NOT NULL REFERENCES agent_report (id),
    ordinal    INTEGER NOT NULL,
    source     TEXT    NOT NULL
        CONSTRAINT agent_report_context_source_chk CHECK (source IN ('wiki','blog','repo','web')),
    ref        TEXT    NOT NULL,
    helped     TEXT    NOT NULL
        CONSTRAINT agent_report_context_helped_chk CHECK (helped IN ('yes','partly','no')),
    PRIMARY KEY (report_id, ordinal)
);

CREATE TABLE IF NOT EXISTS agent_report_friction (
    report_id  BIGINT  NOT NULL REFERENCES agent_report (id),
    ordinal    INTEGER NOT NULL,
    product    TEXT    NOT NULL,
    tool       TEXT,
    what       TEXT    NOT NULL,
    blocking   BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (report_id, ordinal)
);

GRANT SELECT, INSERT ON agent_report TO :app_user;
GRANT SELECT, INSERT ON agent_report_context TO :app_user;
GRANT SELECT, INSERT ON agent_report_friction TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE agent_report_id_seq TO :app_user;

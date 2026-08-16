-- V053: content_opportunity_snooze — declined-suggestion tracking for the opportunity engine.
--
-- A (opportunity_type, target) pair under an unexpired snooze is filtered out of the backlog
-- before scoring (content-intelligence design §7.3 cross-rule constraints). `reason` is
-- NOT NULL deliberately: a declined suggestion with no recorded reason is indistinguishable
-- from a bug, and six months later nobody remembers which it was. Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS content_opportunity_snooze (
    opportunity_type TEXT        NOT NULL,
    target           TEXT        NOT NULL,   -- page_path or query_text
    snoozed_until    DATE        NOT NULL,
    reason           TEXT        NOT NULL,
    snoozed_by       TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (opportunity_type, target)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON content_opportunity_snooze TO :app_user;

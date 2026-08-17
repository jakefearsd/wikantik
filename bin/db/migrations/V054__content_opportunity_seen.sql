-- V054: provenance ledger for the opportunity backlog.
--
-- The backlog is computed on demand (data volumes are small and a scheduler would only add
-- staleness), which leaves first_seen with nothing to derive from -- every evaluation would
-- report "today" for an opportunity that has been open for months. That is a fabricated value
-- in a field an autonomous agent is told to trust, so it is observed and stored instead.
--
-- Written on the READ path: every evaluation upserts the (type, target) pairs it produced.
-- The write is idempotent, one row per live opportunity, and fail-open -- a failed upsert logs
-- and still returns the backlog. last_seen additionally gives the admin panel opportunity age
-- and lets a resolved opportunity be recognised by its absence rather than by a delete.
--
-- Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS content_opportunity_seen (
    opportunity_type TEXT NOT NULL,
    target           TEXT NOT NULL,
    first_seen       DATE NOT NULL,
    last_seen        DATE NOT NULL,
    PRIMARY KEY (opportunity_type, target)
);

CREATE INDEX IF NOT EXISTS idx_cos_last_seen ON content_opportunity_seen (last_seen);

GRANT SELECT, INSERT, UPDATE, DELETE ON content_opportunity_seen TO :app_user;

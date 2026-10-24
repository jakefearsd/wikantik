-- V064: record the project-context tiers and scope mode on each briefing (project-context
-- separation, Phase 3). cited_count / elsewhere_count are the numbers of 'cited' (shared pages the
-- project's pages link to) and 'elsewhere' (out-of-scope pointer) items in the returned briefing;
-- pointer_count now means budget-trimmed 'pin'/'cluster' items only. scope_mode is the effective
-- mode in wire form ('prefer' | 'strict'); NULL for rows logged before this migration.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS. DDL-only (no backfill). The table-level
-- GRANT SELECT, INSERT from V044 already covers the new columns.

ALTER TABLE briefing_log
    ADD COLUMN IF NOT EXISTS cited_count      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS elsewhere_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS scope_mode       TEXT;

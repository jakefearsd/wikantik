-- V067: per-snapshot draft contributors (docs/MultiEdit.MD section 7.3; Phase 3 plan decision 5).
-- collab_snapshot.contributors holds who contributed to the draft since the last publish, as of the snapshot:
-- a JSON array in first-contribution order (JSONB does not keep object key order), each element
-- {"a": "<authorKey>", "v": [firstVersion, lastVersion], "m": [firstMetaSeq, lastMetaSeq]}, where [0, 0] means
-- "none of this kind". A reload takes the snapshot's list and replays the retained log, so pushes add no writes.
-- Rows written before this column existed read as the empty list.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS. DDL-only (no backfill). No new grant: the column is covered by the existing
-- table grant to :app_user from V066.

ALTER TABLE collab_snapshot ADD COLUMN IF NOT EXISTS contributors JSONB NOT NULL DEFAULT '[]'::jsonb;

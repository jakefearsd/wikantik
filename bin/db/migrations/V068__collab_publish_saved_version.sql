-- V068: the page version a pending collaborative publish's save wrote (docs/MultiEdit.MD section 7.3, A2/E39).
-- collab_session.pending_publish_saved_version is stored as soon as the save returns (or reports the version with
-- its failure), and pending_publish_canonical_id is the canonical_id that save carried (the key of the publish's
-- page_version_contributor rows). Every later claim of the publish (its retry, the reload's pending-publish rule)
-- matches (saved version, pending_publish_author) instead of the content hash of S_V, which a save filter's rewrite
-- breaks; an unclaimed publish's early ledger rows are found by the pair. Both NULL while no save has returned, and
-- cleared with the rest of the marker.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS. DDL-only (no backfill). No new grant: the columns are covered by the existing
-- table grant to :app_user from V066.

ALTER TABLE collab_session ADD COLUMN IF NOT EXISTS pending_publish_saved_version INTEGER;
ALTER TABLE collab_session ADD COLUMN IF NOT EXISTS pending_publish_canonical_id TEXT;

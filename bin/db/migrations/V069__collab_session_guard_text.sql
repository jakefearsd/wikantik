-- V069: the guard of a collaborative draft whose page was deleted (docs/MultiEdit.MD, final review ruling F2).
-- collab_session.guard_text is the deleted page's full text (frontmatter included), set when the draft is first frozen
-- and kept through the page's recreation and every merge until the draft is published again: while it is set, a join
-- by anyone but the draft's participants and administrators must pass view and edit under that text's access rules, so
-- a recreated page never hands its recreator the old unpublished text. NULL when the draft is not guarded.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS. DDL-only (no backfill). No new grant: the column is covered by the existing
-- table grant to :app_user from V066.

ALTER TABLE collab_session ADD COLUMN IF NOT EXISTS guard_text TEXT;

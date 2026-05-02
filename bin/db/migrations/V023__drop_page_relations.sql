-- Drop the page_relations table.
--
-- The frontmatter `relations:` mechanism is removed in this work
-- (see docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md).
-- Page Graph edges are now strictly real wikilinks. The page_relations
-- table contained only data derived from frontmatter, with frontmatter
-- as the source of truth — safe to drop, no backup needed.
--
-- Idempotent: re-applying is a no-op once the table is gone.

DROP TABLE IF EXISTS page_relations;
DROP INDEX IF EXISTS ix_page_relations_target;
DROP INDEX IF EXISTS ix_page_relations_source_type;

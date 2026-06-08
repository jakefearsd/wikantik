-- Operator script: one-shot normalization of kg_nodes.node_type onto the
-- standardized 9-class entity vocabulary used by the Wikantik ontology
-- (wikantik-ontology/src/main/resources/ontology/wikantik.ttl).
--
-- Per feedback_no_data_backfill_in_migrations: this is a DATA FIXUP, NOT a
-- versioned migration. Idempotent + safe to re-run. Runs in ONE transaction
-- and ends with COMMIT; swap COMMIT -> ROLLBACK at the bottom for a dry run.
--
-- Database name: LOCAL dev = jspwiki (see tomcat ROOT.xml); PROD (docker1) = wikantik.
--
-- Usage (dry run first! — pipe a ROLLBACK variant so the file is never edited):
--   1. DRY RUN:
--        sed 's/^COMMIT;/ROLLBACK;/' bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql \
--          | PGPASSWORD=... psql -h localhost -U jspwiki -d jspwiki -v ON_ERROR_STOP=1 -f -
--   2. Inspect the BEFORE / UNMAPPED / PAGE-typed / AFTER diagnostics.
--   3. APPLY (real, commits): run the file directly with -f (no sed):
--        PGPASSWORD=... psql -h localhost -U jspwiki -d jspwiki -v ON_ERROR_STOP=1 \
--          -f bin/db/one-shots/2026-06-08-normalize-kg-node-types.sql
--
-- Canonical entity node_type values (lowercase):
--   person organization place event product technology concept project version

\echo '=== BEFORE: distinct node_type counts ==='
SELECT node_type, COUNT(*) AS n FROM kg_nodes GROUP BY node_type ORDER BY n DESC;

BEGIN;

-- Synonym / variant -> canonical map. Key is the normalized input:
-- LOWER(REGEXP_REPLACE(node_type, '[\s\-]+', '_', 'g')).
CREATE TEMP TABLE _nt_map ( norm_in TEXT PRIMARY KEY, canonical TEXT NOT NULL );
INSERT INTO _nt_map(norm_in, canonical) VALUES
  ('person','person'), ('people','person'), ('individual','person'),
  ('organization','organization'), ('organisation','organization'),
  ('org','organization'), ('company','organization'), ('institution','organization'),
  ('place','place'), ('location','place'), ('geo','place'),
  ('event','event'),
  ('product','product'),
  ('technology','technology'), ('tech','technology'), ('tool','technology'),
  ('framework','technology'), ('library','technology'),
  ('concept','concept'), ('idea','concept'), ('topic','concept'), ('theory','concept'),
  ('project','project'),
  ('version','version'), ('release','version')
;

-- Page-typed nodes are NOT entities; they project as content classes off the
-- page (Phase 1), not off node_type. Leave them untouched; report separately.
CREATE TEMP TABLE _page_types ( t TEXT PRIMARY KEY );
INSERT INTO _page_types VALUES
  ('article'),('hub'),('reference'),('runbook'),('design'),('design_doc'),
  ('implementation_plan');

-- Apply the mapping. The `<>` guard makes already-canonical rows a no-op,
-- which is what makes re-running idempotent.
UPDATE kg_nodes n
SET node_type = m.canonical
FROM _nt_map m
WHERE LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) = m.norm_in
  AND n.node_type <> m.canonical;

\echo '=== UNMAPPED non-page node_types (operator must decide before Phase 5) ==='
SELECT n.node_type, COUNT(*) AS cnt
FROM kg_nodes n
WHERE LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) NOT IN (SELECT norm_in FROM _nt_map)
  AND LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) NOT IN (SELECT t FROM _page_types)
GROUP BY n.node_type ORDER BY cnt DESC;

\echo '=== PAGE-typed nodes (left untouched by design) ==='
SELECT n.node_type, COUNT(*) AS cnt
FROM kg_nodes n
WHERE LOWER(REGEXP_REPLACE(n.node_type, '[\s\-]+', '_', 'g')) IN (SELECT t FROM _page_types)
GROUP BY n.node_type ORDER BY cnt DESC;

\echo '=== AFTER: distinct node_type counts ==='
SELECT node_type, COUNT(*) AS n FROM kg_nodes GROUP BY node_type ORDER BY n DESC;

COMMIT;   -- change to ROLLBACK; for a dry run

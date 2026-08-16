-- V050: queryable search-visibility facts. One row per (snapshot, engine, site, page, query).
--
-- Each row is a TRAILING WINDOW AGGREGATE (window_days, normally 28) stamped with the window's
-- END date -- NOT a per-day grain. The upstream source is jakemon's visibility snapshot tree
-- (/data/<engine>/<site>/snapshot-YYYY-MM-DD.json), shipped by jakemon's bin/ship-visibility.py
-- and upserted through POST /admin/insights/ingest.
--
-- query_text = '' marks the page-level rollup row. That row is authoritative for page totals:
-- every engine omits low-volume queries for privacy, so summing the query rows undercounts.
-- Rules that threshold on impressions must read the rollup row, not a SUM over query rows.
--
-- The primary key makes re-shipping a window idempotent -- backfill and the nightly run are the
-- same code path, and a re-sent snapshot converges instead of duplicating.
--
-- Rows are retained for every site the collector polls, not only the Wikantik ones: history
-- cannot be created retroactively, and the schema carries no Wikantik-specific assumptions.
-- Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS search_visibility_snapshot (
    snapshot_date DATE        NOT NULL,
    window_days   SMALLINT    NOT NULL,
    engine        TEXT        NOT NULL,
    site_host     TEXT        NOT NULL,
    page_path     TEXT        NOT NULL,
    query_text    TEXT        NOT NULL,
    impressions   INTEGER     NOT NULL,
    clicks        INTEGER     NOT NULL,
    position      NUMERIC(6,2),
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (snapshot_date, engine, site_host, page_path, query_text)
);

CREATE INDEX IF NOT EXISTS idx_svs_date   ON search_visibility_snapshot (snapshot_date);
CREATE INDEX IF NOT EXISTS idx_svs_engine ON search_visibility_snapshot (engine, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_svs_page   ON search_visibility_snapshot (site_host, page_path, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_svs_query  ON search_visibility_snapshot (query_text, snapshot_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON search_visibility_snapshot TO :app_user;

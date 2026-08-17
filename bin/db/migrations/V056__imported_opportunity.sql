-- V056: the imported half of the content-opportunity backlog (content-intelligence design §7.3
-- "Imported from jakemon", §12.1 item J3). One row per (as_of, engine, site, detector type,
-- target) as shipped by jakemon's five detectors -- striking_distance, ctr_gap, content_gap,
-- cannibalization, decay -- in the same POST /admin/insights/ingest body that already carries
-- search_visibility_snapshot rows (V050).
--
-- `target` holds the QUERY for four of the five detector types, not a page path -- it is
-- deliberately not named page_path. jakemon's opportunities are keyed on query: target_page is
-- the empty string for striking_distance, ctr_gap, content_gap and decay, and only
-- cannibalization populates target_pages (a list, carried in `evidence`, not this column). This
-- mirrors the store's own Rule 2 grain correction (V050's header, and design §7.3 rule 2): the
-- wiki has no query-to-page attribution yet (§12.1 item J1), so a single scalar `target` that is
-- "whatever identifies this opportunity" -- query when present, else target_page -- is the only
-- key that is actually populated across all five types today.
--
-- expected_uplift is jakemon's own unit (expected incremental clicks), carried through unchanged
-- rather than re-derived -- see §7.3 "Ranking across types": re-deriving it in Java would create
-- a second implementation of the same arithmetic, guaranteed to drift from jakemon's EXPECTED_CTR
-- curve. confidence is jakemon's per-type detector confidence, also carried through unchanged.
--
-- evidence is detector-specific and deliberately NOT modelled as columns: the five detectors
-- carry different extra keys (e.g. cannibalization's target_pages, decay's prior-window position),
-- and enumerating them here would make this table's shape track jakemon's detector internals.
-- Everything the row parser (ImportedOpportunityParser) does not consume into a typed column
-- lands in this JSONB blob and is surfaced as opportunity evidence unchanged.
--
-- The primary key makes re-shipping a day's detector run idempotent, matching V050's own
-- upsert-on-reingest contract -- a re-sent day converges instead of duplicating.
--
-- Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS imported_opportunity (
    as_of            DATE           NOT NULL,
    engine           TEXT           NOT NULL,
    site_host        TEXT           NOT NULL,
    opportunity_type TEXT           NOT NULL,
    target           TEXT           NOT NULL,
    expected_uplift  NUMERIC(10,2)  NOT NULL,
    confidence       NUMERIC(4,3),
    evidence         JSONB,
    PRIMARY KEY (as_of, engine, site_host, opportunity_type, target)
);

-- Backs latestImported()'s "most recent as_of for this site" resolution -- the read the backlog
-- assembler runs on every request.
CREATE INDEX IF NOT EXISTS idx_io_site_asof ON imported_opportunity (site_host, as_of);

GRANT SELECT, INSERT, UPDATE, DELETE ON imported_opportunity TO :app_user;

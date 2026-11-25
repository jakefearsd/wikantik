-- V097: reader-engagement rollups computed from Umami's store (reader analytics, Project 1).
--
-- Two daily aggregate tables, written by the analytics read-back job and never by readers. Each row
-- is one day for one site and one page: canonical_id is the page's rename-stable id, and
-- canonical_id = '' is the site row, used for paths that are not pages.
--
-- Null means "no data", never zero: the percentile and web-vitals columns stay NULL when Umami has
-- no samples for that day, so a quiet day is not reported as a fast or a slow one.
--
-- Rows are refreshed in place by the read-back, so the primary keys make a re-run converge rather
-- than duplicate. Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS reader_engagement_daily (
    day                DATE        NOT NULL,
    site_host          TEXT        NOT NULL,
    canonical_id       TEXT        NOT NULL,
    views              INTEGER     NOT NULL DEFAULT 0,
    visitors           INTEGER     NOT NULL DEFAULT 0,
    exits              INTEGER     NOT NULL DEFAULT 0,
    active_median_s    NUMERIC,
    active_p90_s       NUMERIC,
    exits_scroll75     INTEGER     NOT NULL DEFAULT 0,
    code_copies        INTEGER     NOT NULL DEFAULT 0,
    wikilink_clicks    INTEGER     NOT NULL DEFAULT 0,
    outbound_clicks    INTEGER     NOT NULL DEFAULT 0,
    diagram_interacts  INTEGER     NOT NULL DEFAULT 0,
    toc_clicks         INTEGER     NOT NULL DEFAULT 0,
    sign_in_clicks     INTEGER     NOT NULL DEFAULT 0,
    lcp_p75_ms         NUMERIC,
    inp_p75_ms         NUMERIC,
    cls_p75            NUMERIC,
    refreshed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (day, site_host, canonical_id)
);

-- One row per day, site, page and acquisition channel/source. The channel and source are part of the
-- key, so the same page can be credited to several sources on the same day.
CREATE TABLE IF NOT EXISTS reader_acquisition_daily (
    day           DATE        NOT NULL,
    site_host     TEXT        NOT NULL,
    canonical_id  TEXT        NOT NULL,
    channel       TEXT        NOT NULL,
    source        TEXT        NOT NULL,
    landings      INTEGER     NOT NULL DEFAULT 0,
    continued     INTEGER     NOT NULL DEFAULT 0,
    returning_landings INTEGER NOT NULL DEFAULT 0,
    refreshed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (day, site_host, canonical_id, channel, source)
);

CREATE INDEX IF NOT EXISTS idx_reader_acq_site_day ON reader_acquisition_daily (site_host, day);

GRANT SELECT, INSERT, UPDATE, DELETE ON reader_engagement_daily TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON reader_acquisition_daily TO :app_user;

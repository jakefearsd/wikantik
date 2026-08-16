-- V051: retrieval_query_log demand signals — content-intelligence design §6.2 (Phase 1).
--
-- Three additive columns close the gaps the AGENT_GAP / VOCABULARY_GAP rules need. Today
-- AGENT_GAP is the only rule in the design with a live denominator (real MCP traffic); this
-- migration is what gives it something to fire on.
--
-- session_hash -- computed per D8 (HMAC-SHA256 of client_ip + user_agent + utc_date, truncated,
-- keyed by a server-held secret; raw IP/UA are never persisted). Enables same-session
-- reformulation pairing (rule VOCABULARY_GAP case b). Nullable: agent surfaces (MCP/tools calls)
-- have no browser session to hash.
--
-- clicked_rank -- 1-based rank of the result the caller actually opened, NULL if nothing was
-- clicked. This is the single most valuable missing field: it is what separates "search returned
-- 20 results" from "search returned 20 results and none of them were the answer." Populated by a
-- later click-capture write, not at query-log-insert time -- hence a separate UPDATE path rather
-- than an INSERT column filled up front.
--
-- coverage -- bundle coverage confidence (strong|partial|weak|unknown, see BundleCoverage) for
-- bundle/briefing surfaces. A non-empty bundle with weak coverage is a content gap that a raw
-- result count completely hides -- this is the datum AGENT_GAP triggers on.
--
-- Idempotent / additive-only DDL; no data backfill (see migrations/README.md).

ALTER TABLE retrieval_query_log ADD COLUMN IF NOT EXISTS session_hash VARCHAR(16);
ALTER TABLE retrieval_query_log ADD COLUMN IF NOT EXISTS clicked_rank INTEGER;
ALTER TABLE retrieval_query_log ADD COLUMN IF NOT EXISTS coverage     TEXT;

CREATE INDEX IF NOT EXISTS idx_rql_session ON retrieval_query_log (session_hash, created_at)
    WHERE session_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rql_coverage ON retrieval_query_log (coverage, created_at)
    WHERE coverage IS NOT NULL;

-- V041 granted only SELECT, INSERT (write-once at query time). recordClick() now UPDATEs
-- clicked_rank on an existing row, so the app role needs UPDATE too. GRANT is naturally
-- idempotent -- re-granting an already-held privilege is a no-op.
GRANT UPDATE ON retrieval_query_log TO :app_user;

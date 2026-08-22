-- V058: api_keys.scope CHECK constraint admits every ApiKeyService.Scope wire value.
--
-- V010 created api_keys_scope_chk as CHECK (scope IN ('mcp', 'tools', 'all')). 2.4.18 split the
-- MCP scope into 'mcp' (admin) and 'mcp_read' (read-only, /knowledge-mcp only) — see
-- com.wikantik.auth.apikeys.ApiKeyService.Scope — but no migration widened the constraint, so
-- minting an mcp_read key failed the INSERT on a real PostgreSQL database. The defect stayed
-- hidden because the unit tests ran against a hand-written H2 schema without this CHECK.
--
-- Idempotent: drop-if-exists + re-add. Safe to re-run.

ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS api_keys_scope_chk;
ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_scope_chk CHECK (scope IN ('mcp', 'mcp_read', 'tools', 'all'));

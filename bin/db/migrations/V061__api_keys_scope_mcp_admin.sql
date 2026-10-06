-- V061: api_keys.scope admits 'mcp_admin', the 2.4.54 name of the full admin MCP scope.
--
-- New admin keys are stored as 'mcp_admin'. 'mcp' stays allowed: keys minted before 2.4.54 keep
-- that value and ApiKeyService.Scope reads it as an alias of 'mcp_admin' (each use is reported at
-- WARN by LegacyScopeUseLog). Existing rows are not rewritten.
--
-- Idempotent: drop-if-exists + re-add. Safe to re-run.

ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS api_keys_scope_chk;
ALTER TABLE api_keys
    ADD CONSTRAINT api_keys_scope_chk CHECK (scope IN ('mcp', 'mcp_admin', 'mcp_read', 'tools', 'all'));

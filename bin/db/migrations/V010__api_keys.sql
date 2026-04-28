-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: unified API keys for MCP and OpenAPI tool servers
--
-- Stores hashed API keys bound to a Wikantik principal (login_name). Both
-- McpAccessFilter and ToolsAccessFilter resolve the Bearer token against
-- this table and, on match, install the principal so JAAS/ACL checks apply
-- exactly as they would for that user's interactive session.
--
-- Keys are stored SHA-256 hashed. The plaintext token is shown to the
-- operator once at generation time and never persisted.
--
-- Fully idempotent: CREATE TABLE IF NOT EXISTS, CREATE INDEX IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS api_keys (
    id              SERIAL       PRIMARY KEY,
    key_hash        VARCHAR(64)  NOT NULL UNIQUE,
    principal_login VARCHAR(100) NOT NULL,
    label           VARCHAR(200),
    scope           VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100),
    last_used_at    TIMESTAMP,
    revoked_at      TIMESTAMP,
    revoked_by      VARCHAR(100),
    CONSTRAINT api_keys_scope_chk CHECK (scope IN ('mcp', 'tools', 'all'))
);

CREATE INDEX IF NOT EXISTS api_keys_principal_idx ON api_keys (principal_login);
CREATE INDEX IF NOT EXISTS api_keys_active_idx    ON api_keys (revoked_at) WHERE revoked_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON api_keys          TO :app_user;
GRANT USAGE, SELECT                  ON SEQUENCE api_keys_id_seq TO :app_user;

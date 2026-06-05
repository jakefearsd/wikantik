# API Keys

Wikantik issues database-backed API keys for programmatic access to the MCP and
OpenAPI tool surfaces. Keys are SHA-256 hashed at generation time; only the hash
is persisted. The plaintext token is displayed exactly once in the admin UI and
never stored.

The implementation lives in:
- `AdminApiKeysResource` — REST resource at `/admin/apikeys`
- `ApiKeyService` — generation, verification, and revocation logic
- `V010__api_keys.sql` — the `api_keys` table migration
- `McpAccessFilter` / `ToolsAccessFilter` — enforce key auth on the respective endpoints

## Prerequisites

Migration `V010__api_keys.sql` must have been applied (it is idempotent and runs
automatically on every deploy via `bin/deploy-local.sh` → `bin/db/migrate.sh`).

The `api_keys` table:

```sql
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
```

## Admin UI

Navigate to **Admin → API Keys** (`/admin/apikeys` in the React SPA). The page
requires the `Admin` role (all `/admin/*` routes are protected by
`AdminAuthFilter`).

### Generating a key

Click **+ Generate Key**. A modal prompts for:

| Field | Required | Notes |
|---|---|---|
| Principal (login) | Yes | The Wikantik login name the key runs as. Tool calls inherit this identity — page ACLs and JAAS permissions apply exactly as they would for an interactive session. The principal must exist in the user database; unknown logins are rejected with HTTP 400. |
| Label | No | Free-form note identifying where the key is used (e.g. "OpenWebUI production"). |
| Scope | Yes | `tools` (OpenAPI only), `mcp` (MCP endpoints only), or `all` (both). |

After clicking **Generate**, a second modal displays the **plaintext token**
once. Copy it now — after closing this dialog only the 12-character fingerprint
(the first 12 hex characters of the SHA-256 hash) remains visible in the table.
There is no way to recover the plaintext from the stored hash.

### Revoking a key

From the key table, click **Revoke** in the row action menu (active keys only).
A confirmation dialog appears before the call is made. Alternatively, select
multiple keys with the checkboxes and use the bulk **Revoke** action.

Revocation is a soft-delete: `revoked_at` and `revoked_by` are stamped; the row
is retained for audit. Revoked keys are hidden by default; tick **Show revoked**
to include them in the table view.

A `revoked_at` key is rejected by the access filters immediately (no token
cache; the filter calls `ApiKeyService.verify()` which excludes revoked rows).

Key issuance is recorded in the tamper-evident audit log (see [AuditLog.md](AuditLog.md))
under category `ADMIN`, event type `apikey.issue`.

## Scope enforcement

Each key is scoped at creation time. The access filter on each endpoint enforces
the scope:

| Endpoint | Required scope |
|---|---|
| `/wikantik-admin-mcp` | `mcp` or `all` |
| `/knowledge-mcp` | `mcp` or `all` |
| `/tools/*` | `tools` or `all` |

A key with the wrong scope for the endpoint receives HTTP 403 "Key not
authorized for MCP" (or the tools-equivalent). The `/api/*` REST surface uses
standard session/JAAS authentication and does not accept API keys.

## How keys authenticate requests

The Bearer token is sent in the `Authorization` header:

```
Authorization: Bearer <plaintext-token>
```

The filter SHA-256 hashes the incoming token and looks it up in `api_keys`
(active rows only). On a match the filter wraps the request with the
`principal_login` as the request principal, so downstream permission checks see
that user's identity. `last_used_at` is updated asynchronously (approximately
once per cache TTL period) so authentication is low-latency on repeated calls.

Both `McpAccessFilter` (used by `/wikantik-admin-mcp` and `/knowledge-mcp`) and
`ToolsAccessFilter` (used by `/tools/*`) apply this logic. Either filter also
accepts legacy static keys (configured via `mcp.access.keys` /
`tools.access.keys` in `wikantik-custom.properties`) or CIDR allowlist entries
(`mcp.access.allowedCidrs` / `tools.access.allowedCidrs`) as fallback auth
methods. DB-backed API keys are the preferred approach for new integrations.

## REST endpoint reference

All `/admin/apikeys` endpoints require the `Admin` role (enforced by
`AdminAuthFilter`). Cross-origin requests are not allowed (`isCrossOriginAllowed`
returns `false`).

### `GET /admin/apikeys`

Returns all keys (active and revoked), newest-first.

```json
{
  "keys": [
    {
      "id": 1,
      "principalLogin": "testbot",
      "label": "OpenWebUI production",
      "scope": "tools",
      "fingerprint": "a3f8c12d9e4b",
      "createdAt": "2026-06-01T10:00:00Z",
      "createdBy": "admin",
      "lastUsedAt": "2026-06-05T08:30:00Z",
      "revokedAt": null,
      "revokedBy": null,
      "active": true
    }
  ]
}
```

The `fingerprint` field is the first 12 hex characters of the stored SHA-256
hash — sufficient to identify a key without being reversible.

### `POST /admin/apikeys`

Generate a new key. Request body:

```json
{
  "principalLogin": "testbot",
  "label": "OpenWebUI production",
  "scope": "tools"
}
```

Response (HTTP 201) includes the transient `token` field — this is the only
time it appears:

```json
{
  "id": 1,
  "principalLogin": "testbot",
  "label": "OpenWebUI production",
  "scope": "tools",
  "fingerprint": "a3f8c12d9e4b",
  "createdAt": "2026-06-05T10:00:00Z",
  "createdBy": "admin",
  "lastUsedAt": null,
  "revokedAt": null,
  "revokedBy": null,
  "active": true,
  "token": "wkntk_<plaintext-secret>"
}
```

Error responses:
- `400` — `principalLogin` missing or unknown user; invalid `scope`.
- `503` — no datasource configured.

### `DELETE /admin/apikeys/{id}`

Soft-revokes the key with the given numeric `id`. Returns:

```json
{ "success": true, "id": 1 }
```

- `404` — key not found or already revoked.

### `POST /admin/apikeys/bulk-action`

Revokes multiple keys in one call. Continues past individual failures.

```json
{ "action": "revoke", "ids": ["1", "2", "3"] }
```

Response:

```json
{
  "succeeded": ["1", "3"],
  "failed": [{ "id": "2", "error": "Key not found or already revoked" }],
  "status": "completed",
  "message": "2 of 3 keys revoked"
}
```

## Example: calling an MCP endpoint with a key

```bash
TOKEN="wkntk_<your-plaintext-token>"

# List available MCP tools
curl -s -X POST https://wiki.example.com/wikantik-admin-mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# Call a Knowledge MCP tool
curl -s -X POST https://wiki.example.com/knowledge-mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_wiki","arguments":{"query":"example"}}}'

# Call an OpenAPI tool
curl -s https://wiki.example.com/tools/search_wiki \
  -H "Authorization: Bearer $TOKEN" \
  -G --data-urlencode "q=example"
```

## Troubleshooting

**HTTP 503 "API key service unavailable — no datasource configured"**

The `wikantik.datasource` JNDI datasource is not configured. API keys require a
database. Check `ROOT.xml` and Tomcat logs.

**POST /admin/apikeys returns 400 "Unknown principalLogin"**

The `principalLogin` you supplied does not exist in the user database. Create
the user first via the admin UI or SCIM (see [ScimProvisioning.md](ScimProvisioning.md)).

**Endpoint returns 403 "Key not authorized for MCP"**

The key's scope (`tools`) does not cover the MCP endpoint. Generate a new key
with scope `mcp` or `all`.

**Token stops working after revocation**

Expected. The access filter checks `api_keys.revoked_at IS NULL` on every
request (the service has an in-memory cache, but revocation invalidates the
cached entry immediately).

## Related

- [ScimProvisioning.md](ScimProvisioning.md) — creating the principal accounts that keys run as
- [AuditLog.md](AuditLog.md) — key issuance is recorded as an `apikey.issue` audit event

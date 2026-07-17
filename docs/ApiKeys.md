# API Keys

Wikantik issues database-backed API keys for programmatic access to the MCP and
OpenAPI tool surfaces. Keys are SHA-256 hashed at generation time; only the hash
is persisted. The plaintext token is displayed exactly once in the admin UI and
never stored.

There are two issuance surfaces over the same `api_keys` table and `ApiKeyService`:
an **admin** surface (any key, any principal — this document's main focus) and a
**self-service** surface (a logged-in user managing only their own keys — see
[Self-service keys](#self-service-keys) below).

The implementation lives in:
- `AdminApiKeysResource` — REST resource at `/admin/apikeys`
- `SelfApiKeysResource` — REST resource at `/api/self/apikeys`
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

## Self-service keys

Alongside the admin-issued surface above, any logged-in user can manage API keys
bound to their **own** principal — no `Admin` role required. This is the surface
for a user who wants a personal key for `search_wiki` / an MCP client without
asking an admin to generate one on their behalf.

### SPA: API Keys panel in user preferences

Navigate to **Preferences** (`/preferences` in the React SPA) and scroll to the
**API Keys** section (`MyApiKeys.jsx`). It lists the caller's own active keys
(label, scope, created, last used), with **+ New key** to generate one and
per-row **Rotate** / **Revoke** actions. Revoked keys drop out of the list
entirely rather than being shown with a "revoked" badge — there is no
"show revoked" toggle on this surface (unlike the admin table).

Generating or rotating opens the same one-time reveal modal as the admin flow:
copy the plaintext token now, because it is never shown again.

### `SelfApiKeysResource` — REST endpoint reference

Rides the `/api/*` filter chain (standard session/JAAS auth, not API-key auth —
you need to already be logged in to mint a key for yourself). Every operation is
scoped to the caller's own login; ownership is enforced server-side, and a
key id that exists but belongs to someone else resolves to `404` (not `403`) so
the endpoint gives no oracle for enumerating other users' key ids.

**`GET /api/self/apikeys`** — the caller's own active keys, metadata only:

```json
{
  "keys": [
    { "id": 7, "label": "laptop", "scope": "tools",
      "createdAt": "2026-06-05T10:00:00Z", "lastUsedAt": "2026-06-06T08:30:00Z" }
  ]
}
```

Note the shape is intentionally thinner than the admin listing — no
`principalLogin` (always the caller), no `fingerprint`, and no revoked rows (only
active keys are returned, so there is no `revokedAt`/`revokedBy`/`active` field
to show).

**`POST /api/self/apikeys`** — generate a key. Body: `{"label": "...", "scope":
"tools"}` (`scope` is one of `mcp`, `tools`, `all`; omitted defaults to `all`).
Response (`201`) includes the transient `token` field, shown exactly once:

```json
{ "id": 7, "label": "laptop", "scope": "tools",
  "createdAt": "2026-06-05T10:00:00Z", "lastUsedAt": null,
  "token": "wkntk_<plaintext-secret>" }
```

**`POST /api/self/apikeys/{id}/rotate`** — revoke-and-reissue: the old key is
revoked and a new key with the same `label`/`scope` is generated in one call.
Response shape matches the generate response (new `id`, fresh `token`). `404` if
`{id}` does not exist, is not owned by the caller, or is already revoked.

**`DELETE /api/self/apikeys/{id}`** — revoke. Response:
`{"success": true, "id": 7}`. Same `404`-for-unknown/not-owned/already-revoked
ownership gate as rotate.

Every self-service issue/rotate/revoke is recorded in the audit log (see
[AuditLog.md](AuditLog.md)) under category `ADMIN`, event types `apikey.issue` /
`apikey.rotate` / `apikey.revoke` — same event types as the admin surface, actor
is the caller's own login rather than an admin's.

Error responses:
- `401` — not authenticated.
- `503` — `"API key service unavailable — no datasource configured"`.
- `400` — invalid `scope` on generate.

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

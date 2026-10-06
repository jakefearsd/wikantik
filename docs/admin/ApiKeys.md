# API Keys

Wikantik issues database-backed API keys for programmatic access to the MCP and
OpenAPI tool surfaces. Keys are SHA-256 hashed at generation time; only the hash
is persisted. The plaintext token is displayed exactly once in the admin UI and
never stored.

There are two issuance surfaces over the same `api_keys` table and `ApiKeyService`:
an **admin** surface (any key, any principal — this document's main focus) and a
**self-service** surface (a logged-in user managing only their own keys — see
[Self-service keys](#self-service-keys) below).

This page is for administrators who issue, scope and revoke those keys, and for
users who mint their own. For the tools each scope can reach, see [McpAgents.md](McpAgents.md).

The implementation lives in:
- `AdminApiKeysResource` — REST resource at `/admin/apikeys`
- `SelfApiKeysResource` — REST resource at `/api/self/apikeys`
- `ApiKeyService` — generation, verification, and revocation logic
- `V010__api_keys.sql` — the `api_keys` table migration; `V058__api_keys_scope_chk_mcp_read.sql` widens its scope CHECK
- `McpAccessFilter` / `ToolsAccessFilter` — enforce key auth on the respective endpoints

## Prerequisites

Migrations `V010__api_keys.sql` and `V058__api_keys_scope_chk_mcp_read.sql` must
have been applied. They are idempotent and run automatically on every deploy via
`bin/deploy-local.sh` and `bin/redeploy.sh` (both call `bin/db/migrate.sh`).

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
    CONSTRAINT api_keys_scope_chk CHECK (scope IN ('mcp', 'mcp_admin', 'mcp_read', 'tools', 'all'))
);
```

The `CHECK` list above is the current form: `V010` created it as
`('mcp', 'tools', 'all')`, `V058` replaced it so `mcp_read` keys can be stored, and `V061`
widened it again to admit `mcp_admin` (existing rows are not rewritten; see
[Legacy `mcp` scope name](#legacy-mcp-scope-name)).

## Admin UI

Navigate to **Admin → API Keys** (`/admin/apikeys` in the React SPA). The page
requires the `Admin` role (all `/admin/*` routes are protected by
`AdminAuthFilter`).

### Generating a key

Click **+ Generate Key**. A modal prompts for:

| Field | Required | Notes |
|---|---|---|
| Principal (login) | Yes | The Wikantik login name the key is bound to. It is used for attribution and the audit trail; it does **not** limit what the key can do (see [Scope enforcement](#scope-enforcement)). The principal must exist in the user database; unknown logins are rejected with HTTP 400. |
| Label | No | Free-form note identifying where the key is used (e.g. "OpenWebUI production"). |
| Scope | Yes | `tools` (OpenAPI `/tools/*` only), `mcp_read` (read-only, `/knowledge-mcp` only), `mcp_admin` (full admin — covers both MCP endpoints), or `all` (everything). See [Scope enforcement](#scope-enforcement) for the hierarchy. |

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

A revoked key is rejected on the next request: `ApiKeyService.revoke()` evicts
that key from the in-process verify cache (60-second TTL) and `verify()` excludes
revoked rows. The eviction is per JVM, so in a multi-node deployment another node
can keep accepting the key for up to 60 seconds.

Key issuance is recorded in the tamper-evident audit log (see [AuditLog.md](AuditLog.md))
under category `ADMIN`, event type `apikey.issue`.

## Scope enforcement

Each key is scoped at creation time. `ApiKeyService.Scope` is
`MCP_READ` / `MCP_ADMIN` / `TOOLS` / `ALL` (wire values `mcp_read` / `mcp_admin` /
`tools` / `all`). The **MCP family is a rank hierarchy** — `MCP_READ ⊂ MCP_ADMIN` — so a
higher-privilege key satisfies a lower-privilege requirement, but not the
reverse: a `mcp_admin` (or `all`) key can call `/knowledge-mcp` too, but an
`mcp_read` key cannot reach `/wikantik-admin-mcp`. `TOOLS` is orthogonal to
the MCP family (matches only itself); `ALL` covers everything.

| Endpoint | Required scope | Keys that satisfy it |
|---|---|---|
| `/wikantik-admin-mcp` | `mcp_admin` | `mcp_admin`, `all` |
| `/knowledge-mcp` | `mcp_read` | `mcp_read`, `mcp_admin`, `all` |
| `/tools/*` | `tools` | `tools`, `all` |

`mcp_read` was added in **2.4.18** as a narrower, read-only scope confined to
`/knowledge-mcp`, for integrations that should never reach the admin write surface
(tool lists: [McpAgents.md](McpAgents.md)). The full-admin scope was called `mcp` until
2.4.54, which renamed it `mcp_admin`; the old name still works (see
[Legacy `mcp` scope name](#legacy-mcp-scope-name)).

### Legacy `mcp` scope name

Before 2.4.54 the full-admin MCP scope was named `mcp`. It is now `mcp_admin`, and `mcp`
remains accepted as an alias:

- **Minting.** `POST /admin/apikeys` or `POST /api/self/apikeys` with `"scope": "mcp"` mints
  an `mcp_admin` key. The mint is logged at WARN (endpoint, caller login, IP, User-Agent).
- **Stored keys.** Keys already stored as `mcp` keep full admin access. Migration
  `V061__api_keys_scope_mcp_admin.sql` widens the table `CHECK` to admit `mcp_admin`; it does
  not rewrite existing rows. A legacy key is listed with scope `mcp_admin` and
  `"legacyScope": true`, and the admin and My API keys pages show a "legacy 'mcp'" marker.
- **Finding clients to migrate.** Every use of a key stored as `mcp` logs a WARN from
  `com.wikantik.auth.apikeys.LegacyScopeUseLog`, naming the surface, key id, label,
  principal, client IP and User-Agent (at most once per hour per key and client address).
  Search the log for that logger, mint a replacement `mcp_admin` key, update the client,
  and revoke the old key.

### Issue a key for each scope

Use **Admin → API Keys → + Generate Key** (admin) and pick the scope in the dialog, or
send `scope` in the `POST` body. A non-administrator can mint only `tools` and `mcp_read`
keys through **Preferences → API Keys** (see [Self-service keys](#self-service-keys));
`mcp_admin` and `all` keys come from an administrator through `/admin/apikeys`. Choose the
narrowest scope that does the job:

| You want a key that… | Scope | Issue it with |
|---|---|---|
| Reads and searches content over `/knowledge-mcp` only (a read-only knowledge agent) | `mcp_read` | `{"principalLogin": "agent", "scope": "mcp_read"}` |
| Uses the full admin MCP surface (`/wikantik-admin-mcp`, and `/knowledge-mcp` too) | `mcp_admin` | `{"principalLogin": "curator", "scope": "mcp_admin"}` |
| Calls only the OpenAPI `/tools/*` endpoints (for example OpenWebUI) | `tools` | `{"principalLogin": "owui", "scope": "tools"}` |
| Reaches every key-protected surface | `all` | `{"principalLogin": "ops", "scope": "all"}` |

If `scope` is omitted from an admin `POST`, the key is created with scope `all`. (A
non-admin self-service `POST` that omits it gets `mcp_read`.)
The principal you name is recorded for attribution and auditing only. It does not
narrow the key: authorisation on these surfaces is the scope check in the access
filter, and the admin MCP tools perform no per-user permission checks. An `mcp_admin` or
`all` key can read and write every non-system page and curate the Knowledge Graph,
whichever account it is bound to, so treat those keys as admin credentials. Use
`mcp_read` for read-only agents. A narrower `mcp_content` tier (page and Knowledge
Graph read/write without admin tools) is planned in
[GitHub issue #62](https://github.com/jakefearsd/wikantik/issues/62) but is not shipped.

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
`principal_login` as the request principal, which identifies the caller for the filter
and the audit trail; the access filter's scope check is the authorisation. `last_used_at` is updated asynchronously (approximately
once per 60-second verify-cache TTL, on a cache miss) so authentication is
low-latency on repeated calls.

Both `McpAccessFilter` (used by `/wikantik-admin-mcp` and `/knowledge-mcp`) and
`ToolsAccessFilter` (used by `/tools/*`) apply this logic. Either filter also
accepts a source IP inside a configured CIDR allowlist entry
(`mcp.access.allowedCidrs` in `wikantik-mcp.properties` / `tools.access.allowedCidrs`
in `wikantik-tools.properties`) as a fallback to a DB-backed key. There is no
property-file bearer-token list — DB-backed API keys are the only bearer-token
mechanism.

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
      "active": true,
      "legacyScope": false
    }
  ]
}
```

The `fingerprint` field is the first 12 hex characters of the stored SHA-256
hash — sufficient to identify a key without being reversible. `legacyScope` is `true`
for a key stored under the pre-2.4.54 `mcp` name; such a key is listed with scope
`mcp_admin` (see [Legacy `mcp` scope name](#legacy-mcp-scope-name)). `GET /api/self/apikeys`
carries the same field.

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
  "token": "wkk_<plaintext-secret>"
}
```

Error responses:
- `400` — `principalLogin` missing or unknown user; invalid `scope` (the error
  text lists every valid scope, built from `ApiKeyService.Scope.validWireNames()`:
  `mcp_read, mcp_admin, tools, all`).
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

## Example: calling an endpoint with a key

```bash
TOKEN="wkk_<your-plaintext-token>"

# Open an MCP session on the Knowledge endpoint (Streamable HTTP). The reply
# carries an Mcp-Session-Id header that every later call must send, after a
# notifications/initialized message.
curl -si -X POST https://wiki.example.com/knowledge-mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

# Call an OpenAPI tool (scope tools or all)
curl -s -X POST https://wiki.example.com/tools/search_wiki \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"example","maxResults":5}'
```

## Self-service keys

Alongside the admin-issued surface above, any logged-in user can manage API keys
bound to their **own** principal — no `Admin` role required. This is the surface
for a user who wants a personal key for `search_wiki` / a read-only MCP client without
asking an admin to generate one on their behalf. A non-admin can self-mint only the
`tools` and `mcp_read` scopes; `mcp_admin` and `all` keys must be issued by an administrator
at `/admin/apikeys`, because they carry full admin privilege.

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
"tools"}` (a non-admin may choose `tools` or `mcp_read`; omitted defaults to `mcp_read`.
An administrator (a caller holding `AllPermission`) may choose any scope, `mcp_admin` and
`all` included, and defaults to `all`). A non-admin asking for `mcp_admin` (or the legacy
`mcp`) or `all` gets `403`
("Scope 'mcp_admin' requires administrator rights ... you may create keys with scope
mcp_read, tools"); an unknown scope gets `400` with the list of valid scopes.
Response (`201`) includes the transient `token` field, shown exactly once:

```json
{ "id": 7, "label": "laptop", "scope": "tools",
  "createdAt": "2026-06-05T10:00:00Z", "lastUsedAt": null,
  "token": "wkk_<plaintext-secret>" }
```

**`POST /api/self/apikeys/{id}/rotate`** — revoke-and-reissue: the old key is
revoked and a new key with the same `label`/`scope` is generated in one call. A non-admin cannot
rotate a key whose scope they could not mint (`mcp_admin` or `all`): the call returns `403`, and
the existing key is neither revoked nor reissued.
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

The key's scope does not cover the MCP endpoint you called. For
`/wikantik-admin-mcp` you need scope `mcp_admin` or `all` — `mcp_read` is not
enough. For `/knowledge-mcp`, `mcp_read`, `mcp_admin`, or `all` all work. Generate
a new key with a covering scope (see [Scope enforcement](#scope-enforcement)).

**Token stops working after revocation**

Expected. Revocation evicts the key from the verify cache on the node that
handled the revoke; other nodes honour a cached key for up to 60 seconds.

## Related

- [ScimProvisioning.md](ScimProvisioning.md) — creating the accounts that keys are bound to (the account is for attribution and audit only)
- [AuditLog.md](AuditLog.md) — key issuance is recorded as an `apikey.issue` audit event

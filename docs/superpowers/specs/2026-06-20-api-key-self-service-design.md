# API-Key Self-Service Design

**Date:** 2026-06-20
**Status:** Approved (design); implementation pending
**Author:** Jake Fear (with Claude Code)

## Goal

Let a logged-in user surface a **usable** API key for their own account, without an
admin, through a gated self-service page. "Recovery" is by **secure reissue**: the user
sees their keys' metadata and can generate or rotate a key, viewing the new secret once.

## Context & Hard Constraint

API keys are stored **SHA-256 hashed**; the plaintext is shown once at creation and
**never persisted** (`ApiKeyService`, `api_keys` table — only `key_hash` + metadata:
`label`, `scope`, `created_at`, `last_used_at`, `revoked_at`, no prefix/last-4). The
literal secret of an *existing* key is therefore cryptographically **unrecoverable**.

This design keeps storage hashed-only (no security regression). The chosen model —
selected over recoverable/encrypted storage — is **self-service reissue**: a user who
lost a key rotates it to a fresh one rather than reading the old one back. This mirrors
GitHub/AWS/Stripe.

Today, keys are minted only by admins (`POST /admin/apikeys`, `AdminAuthFilter`-gated,
for any principal). This feature adds a **user-scoped, ownership-enforced** surface.

## Architecture

### Surface & placement

A new **"API Keys"** section inside the existing `UserPreferencesPage` (`/preferences`
route — already the account hub for Profile / Change Password / Delete Account).

Reusing `/preferences` deliberately avoids creating a new SPA route, which would require
the web.xml + `SpaRoutingFilter.SPA_EXACT` dual-registration (a documented footgun where
either alone silently 404s).

Mockup:

```
Preferences ▸ API Keys

Your API keys are bound to your account and act with YOUR permissions.
Secrets are shown once — store them somewhere safe.
                                                   [ + Generate key ]
Label        Scope   Created       Last used
mcp-agent    mcp     2026-06-12    2026-06-19     [ Rotate ] [ Revoke ]
tools-bot    tools   2026-06-01    never          [ Rotate ] [ Revoke ]

— Generate / Rotate opens a one-time reveal modal —
   wk_live_8f3c……d21a   [ Copy ]   ⚠ won't be shown again
```

### Backend — `SelfApiKeysResource` at `/api/self/apikeys`

Rides the normal `/api/*` filter chain (auth, remember-me re-auth, CSRF). Every operation
is scoped server-side to the caller's login; the client supplies no principal.

| Method | Path | Behavior |
|--------|------|----------|
| `GET` | `/api/self/apikeys` | Caller's **active** (non-revoked) keys, **metadata only** — id, label, scope, created_at, last_used_at. No `key_hash`, no plaintext. Revoked keys are excluded (a revoked key is dead; no value listing it). |
| `POST` | `/api/self/apikeys` | Body `{label, scope}`. Generates a key bound to the caller (`principal_login = created_by = caller login`). Response carries the plaintext `token` **once** plus the new row's metadata. |
| `POST` | `/api/self/apikeys/{id}/rotate` | Ownership-checked. Revokes the old key and issues a new one with the **same label + scope**; returns the new `token` once. The row id changes; the label stays stable. |
| `DELETE` | `/api/self/apikeys/{id}` | Ownership-checked revoke. |

`scope` is one of `mcp` / `tools` / `all` (existing `ApiKeyService.Scope`). Invalid scope
→ 400. Missing/blank label is allowed (admin path allows null label).

### `ApiKeyService` additions

Two read helpers; reuse existing `generate` / `revoke`:

- `List<Record> listByPrincipal(String principalLogin)` — DB-level
  `WHERE principal_login = ? AND revoked_at IS NULL` (active-only; uses the existing
  `api_keys_principal_idx`).
- `Optional<Record> findById(int id)` — for the ownership gate before rotate/revoke.

No schema change: `api_keys` already carries `principal_login`. No new migration.

### Data flow

1. User opens `/preferences` → API Keys section → `GET /api/self/apikeys` lists their keys.
2. **Generate**: `POST` → one-time reveal modal shows `token` → Copy.
3. The key authenticates against `/knowledge-mcp`, `/wikantik-admin-mcp` (if scope allows),
   and `/tools/*` at **the caller's own permission level** (the filters install the key's
   principal, exactly as for an interactive session).
4. **Rotate**: `POST /{id}/rotate` → old key stops working immediately, new secret revealed once.
5. **Revoke**: `DELETE /{id}` → key dies.

## Security

- **Gate:** authenticated *real* account only. Anonymous / guest / unauthenticated → 401.
  (A self-minted key carries only the caller's own ACL/permissions, so enabling all real
  accounts introduces no privilege escalation — an admin self-minting gets an admin-level
  key, identical to today's admin mint.)
- **Ownership enforced on every op** server-side: list is filtered by principal; rotate and
  revoke load the record via `findById` and reject (404, not 403 — don't confirm the id
  exists) when `principal_login != caller`. A user can never enumerate, read, rotate, or
  revoke another user's key, even by guessing an id.
- **Secret shown once**, never returned again by `GET`. `GET` and the list never include
  `key_hash`.
- **Audited:** generate / rotate / revoke each emit an audit entry with the acting principal
  (reuse the audit hook `AdminApiKeysResource` already uses).
- CSRF: writes ride the existing synchronizer-token filter on `/api/*`.

## Error handling

- Unauthenticated → 401.
- Rotate/revoke of an id not owned by the caller (or nonexistent) → 404 (uniform; no
  existence oracle).
- Invalid `scope` → 400. Malformed JSON body → 400.
- `ApiKeyService`/DB failure → 500 with a logged `LOG.error` (never a swallowed exception).

## Testing

- **Unit (`SelfApiKeysResourceTest`, Mockito):** list returns only the caller's keys and no
  hash/plaintext; generate binds the row to the caller; rotate revokes the old id and issues
  a new one for the caller; rotate/revoke of another user's id → 404; anonymous caller → 401;
  invalid scope → 400.
- **Unit (`ApiKeyServiceTest`):** `listByPrincipal` returns only the principal's **active**
  rows (a revoked row and another principal's row are both excluded); `findById` present/absent.
- **Wire-level IT:** a logged-in user mints a key via `POST /api/self/apikeys`, then that key
  successfully authenticates a `tools/list` call against `/knowledge-mcp` — proving the full
  issue→use loop. (Security-surface changes ship with a unit test *and* a Cargo-launched IT.)
- **Frontend (vitest):** the API Keys section lists keys, shows the one-time reveal on
  generate, and exercises rotate + revoke against a mocked client.

## Out of Scope (explicitly)

- **Recoverable / encrypted-at-rest key storage** (the "reveal the actual existing secret"
  model). Rejected for now to preserve the hashed-only guarantee; could be a future
  flagged phase if reissue proves insufficient.
- Admin-side key management changes (`/admin/apikeys` unchanged).
- Per-key expiry / TTL, usage quotas, IP binding — not requested.

## Notes

The user flagged this may be removed later ("for now this is essential"). The design keeps
the blast radius small: one new resource, two read helpers on `ApiKeyService`, one new
frontend section — all additive, no schema or storage-model change, trivially revertible.

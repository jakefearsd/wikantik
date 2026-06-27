# Page Ownership

Every wiki page tracked by the structural index has a single designated owner — the
`login_name` of the user responsible for it. Ownership is stored in the `page_owners`
table (V034) and is independent of the page ACL: it records accountability, not
read/write permission.

## Table of contents

1. [The ownership model](#the-ownership-model)
2. [The `agents` service account](#the-agents-service-account)
3. [How ownership is assigned](#how-ownership-is-assigned)
4. [Configuration](#configuration)
5. [Admin UI walkthrough](#admin-ui-walkthrough)
6. [REST endpoint reference](#rest-endpoint-reference)
7. [Auth model](#auth-model)
8. [Ownership vs ACLs / permissions](#ownership-vs-acls--permissions)
9. [Troubleshooting](#troubleshooting)
10. [Cross-links](#cross-links)

---

## The ownership model

The `page_owners` table schema (added in V034):

```sql
CREATE TABLE IF NOT EXISTS page_owners (
    canonical_id TEXT PRIMARY KEY,
    owner_login  TEXT,                       -- NULL = orphaned
    assigned_by  TEXT NOT NULL,
    assigned_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Key invariants:

- **Keyed on `canonical_id`**, not on the mutable page slug. A page rename does not
  change ownership.
- **`owner_login IS NULL` means orphaned.** The admin UI surfaces these in the
  "Orphaned" tab.
- **Read-time fallback.** When `PageOwnerService.getOwner()` resolves an owner, it
  applies an `admin` fallback: if the stored `owner_login` is `NULL`, blank, or no
  longer a real user, the returned value is `admin`. This means callers always get a
  non-null string; the raw `NULL` is only visible through the admin REST endpoints.

## The `agents` service account

Pages authored by AI agents typically carry a `author:` frontmatter field that is not
a real login name. Rather than leaving those pages orphaned, `PageOwnerService`
bootstraps them onto a dedicated service account.

V035 seeds this account:

| Field        | Value                             |
|--------------|-----------------------------------|
| `login_name` | `agents`                          |
| `wiki_name`  | `Agents`                          |
| `full_name`  | `AI Agents (service account)`     |
| `email`      | `agents@localhost`                |
| Roles        | none (non-loginable)              |

Because agent-authored pages default to this owner, **the Orphaned tab is empty by
design** on a well-configured deployment — there are no truly ownerless pages; they
belong to `agents`.

The property that names this default:

```properties
wikantik.page_ownership.default_owner = agents
```

If the `agents` account does not exist in the database (e.g. after a hard DB reset
without re-running V035), `PageOwnerService` falls back safely to `NULL` (orphaned)
rather than persisting a dangling `owner_login`.

The one-time backfill of pre-existing pages onto `agents` is not part of V035 (no
data fixups in versioned migrations). If you need it, run:

```bash
psql -h localhost -U wikantik -d wikantik \
  -f bin/db/one-shots/backfill-agent-default-owner.sql
```

## How ownership is assigned

### Bootstrap (find-or-create at save time)

`PageOwnershipSaveFilter` runs as a post-save `PageFilter`. After every successful
page save it calls `PageOwnerService.getOwner(canonicalId)`, which is a find-or-create:

1. If a `page_owners` row already exists for the canonical_id, it is returned as-is.
2. If no row exists, the service reads the page's `author` frontmatter field and
   checks whether that value is a real `login_name`.
   - If yes, that login becomes the owner, recorded with `assigned_by =
     system:bootstrap`.
   - If no (e.g. an AI-agent authored the page), the configured `default_owner`
     (`agents`) is used, provided it is itself a real user.
   - If neither resolves, the row is inserted with `owner_login = NULL` (orphaned).

This filter is gated by:

```properties
wikantik.page_ownership.enforcement.enabled = true
```

Setting this to `false` disables bootstrap; no `page_owners` rows are written on save.

### Admin reassignment

Admins can reassign ownership at any time via the admin UI or REST API (see below).
Reassignment stamps `assigned_by` with the login of the acting admin and updates
`assigned_at`.

## Configuration

| Property | Default | Effect |
|----------|---------|--------|
| `wikantik.page_ownership.enforcement.enabled` | `true` | Enables post-save bootstrap via `PageOwnershipSaveFilter`. |
| `wikantik.page_ownership.default_owner` | `agents` | Login used when the frontmatter `author` cannot be resolved to a real user. Must be a real user in the database to take effect. |

Properties go in `wikantik-custom.properties` (in `tomcat/tomcat-11/lib/`).

## Admin UI walkthrough

**Route:** `/admin/page-ownership`

The page has two tabs and a bulk form at the top.

### Bulk reassign form

Located above the tabs. Transfers all pages from one owner to another in a single
request.

- **From owner** — login or the literal string `<orphaned>` (to adopt all orphaned
  pages).
- **To owner** — login or `<orphaned>` (to orphan all pages currently owned by
  "From owner").

### Orphaned tab (default)

Lists pages where `owner_login IS NULL`. Columns: Page name, Canonical ID, Owner,
Assigned by, Assigned at, Actions.

An empty Orphaned tab is the expected steady state when the `agents` account is
properly seeded.

### By Owner tab

Enter a login (or `<orphaned>`) in the search box and press Search. Results show
every page owned by that login.

### Per-row reassignment

Click **Reassign** on any row to open an inline modal. Enter the new owner login (or
`<orphaned>` to orphan the page) and confirm.

## REST endpoint reference

All endpoints are under `/admin/page-ownership/*` and are pre-authorized by
`AdminAuthFilter` (requires `AllPermission` — admin role). CORS is disabled on this
resource.

### `GET /admin/page-ownership`

Query parameters:

| Parameter | Required | Values | Notes |
|-----------|----------|--------|-------|
| `filter`  | yes      | `orphaned` \| `by-owner` | `orphaned` lists pages with `owner_login IS NULL`. |
| `owner`   | when `filter=by-owner` | login or `<orphaned>` | `<orphaned>` is equivalent to `filter=orphaned`. |
| `limit`   | no       | integer, 1–500 | Default 50. |
| `offset`  | no       | integer ≥ 0 | Default 0. |

Response:

```json
{
  "pages": [
    {
      "canonicalId": "01KQEDYJR57WYQCV645PKSDBMQ",
      "pageName": "KgInclusionPolicy",
      "ownerLogin": "jsmith",
      "assignedBy": "system:bootstrap",
      "assignedAt": "2026-04-27T10:00:00Z"
    }
  ],
  "total": 1
}
```

`pageName` resolves the canonical_id through the structural index at response time; it
is `null` when the structural index is unavailable or has no entry for that id.

### `POST /admin/page-ownership/reassign`

Per-page reassignment. Validates that `newOwner` exists before writing.

Request body:

```json
{
  "pages": ["01KQEDYJR57WYQCV645PKSDBMQ", "01KQABC..."],
  "newOwner": "jsmith"
}
```

Use `"newOwner": "<orphaned>"` to set `owner_login = NULL`.

Response: `{ "updated": 2 }`

### `POST /admin/page-ownership/reassign-by-user`

Bulk transfer — moves every page from one owner to another in a single SQL UPDATE.
Validates `toOwner` exists (unless it is `<orphaned>`).

Request body:

```json
{
  "fromOwner": "jsmith",
  "toOwner": "agarcia"
}
```

Sentinel values:

| `fromOwner` | `toOwner` | Effect |
|-------------|-----------|--------|
| `<orphaned>` | a login | Adopt all currently-orphaned pages. |
| a login | `<orphaned>` | Orphan all pages owned by that login. |
| `<orphaned>` | `<orphaned>` | No-op (returns `{ "updated": 0 }`). |

Response: `{ "updated": 47 }`

## Auth model

All `/admin/page-ownership/*` endpoints are protected by `AdminAuthFilter`, which
requires the `AllPermission` security permission — effectively the admin role. There
is no finer-grained access: any admin can reassign any page.

The acting admin's login is recorded in `assigned_by` for every write.

## Ownership vs ACLs / permissions

Ownership is an accountability record, not an access-control gate. It does not grant
or restrict read, write, or delete on the page. Permission is still governed by:

- Inline page ACLs (`[{ALLOW view Admin}]` syntax in page content).
- Database policy grants in `policy_grants` (managed at `/admin/security`).
- Group membership in `groups` + `group_members`.

Ownership informs the comments/mentions system: the page owner is included in
`is_owner_mention` rows when someone comments on a page (see `comment_mentions` table,
also added in V034). See [CommentsAndMentions.md](CommentsAndMentions.md) for details.

## Troubleshooting

**Orphaned tab is unexpectedly full**

The `agents` service account was probably not seeded (V035 may not have run, or the
DB was reset). Check:

```bash
psql -h localhost -U wikantik -d wikantik -c "SELECT login_name FROM users WHERE login_name = 'agents';"
```

If the account is absent, re-run the migration:

```bash
bin/db/migrate.sh   # idempotent — V035 uses ON CONFLICT DO NOTHING
```

**Owner shown as `admin` but the page has a different author**

`PageOwnerService.getOwner()` applies an `admin` fallback when the stored
`owner_login` is `NULL` or the user no longer exists. Use the By Owner tab to
search for `<orphaned>` or the former login, then reassign.

**`pageName` is `null` in the API response**

The structural index is unavailable or has not indexed the page yet. The page is
still reachable by `canonicalId`. Start Tomcat and wait for the index to populate;
custom-jdbc provider lag can be up to ~20 seconds after startup.

**Enforcement filter not running**

Check that `wikantik.page_ownership.enforcement.enabled` is not `false` in
`wikantik-custom.properties`, and that the Knowledge Graph subsystem initialized
successfully (look for `PageOwnershipSaveFilter: enforcement enabled` in
`catalina.out`).

## Cross-links

- [CommentsAndMentions.md](CommentsAndMentions.md) — the `comment_mentions` table
  (also added in V034) uses `is_owner_mention` to notify page owners.
- [ApiKeys.md](ApiKeys.md) — API keys used by agent service accounts.
- [AuditLog.md](AuditLog.md) — admin audit log for security-sensitive operations.
- `bin/db/migrations/V034__page_owners_and_mentions.sql` — DDL.
- `bin/db/migrations/V035__seed_agents_service_account.sql` — `agents` account seed.

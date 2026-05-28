# Comment Mentions & Page Ownership Design

**Date:** 2026-05-28
**Status:** Approved — ready for implementation planning
**Builds on:** [2026-05-26 Anchored Comments design](2026-05-26-anchored-comments-design.md) (now shipped).

## Summary

Three coupled additions to the live comment system:

1. **@-mentions** inside comment bodies (opening comments + replies), with an inline autocomplete picker, side-table indexing, and rendered chips linking to a user's wiki page.
2. **A per-user "My mentions" feed** with unread tracking, accessible from the main navigation with an unread badge.
3. **DB-backed page ownership** — every page has an owner; new comment threads auto-mention the owner; admins can reassign ownership; deleting a user orphans their owned pages (lazy-fallback to `admin`).

Pure in-app — no email in v0.

## Goals & non-goals

**Goals**
- Mention any logged-in user via `@<login>` in any comment body; mentioned users see their mention in a per-user feed.
- Page-owner auto-mention on new comment threads, so owners learn about discussion on their pages without polling.
- Admins can list orphaned pages and reassign ownership (per-page or bulk by source-user).
- Robust to user deletion: orphaned pages route comment notifications to `admin` until reassigned.
- Match the look and feel of the existing comment system (theme tokens, in-app dialogs, no native chrome).

**Non-goals**
- Email or push notifications. v0 is in-app only; the user comes to the app to check.
- Per-thread assignment (Jira-style "assigned to" column). Owner-mention is interpretation (a) only.
- A dedicated user profile route. Mention chips link to `/wiki/Users/<Login>` and rely on the normal wiki page-not-found handling; users create their own page if they want one.
- Real-time updates (WebSocket). The unread-count badge polls on visibility-change.
- Mentioning groups / aliases / @everyone. Single login_name only.
- Renaming a user's login. Out of scope for this spec.

## Collaboration model recap

- **Mention syntax:** `@<token>` where `token = [A-Za-z0-9._-]+`. The token must resolve to a real `users.login_name` (case-sensitive match against the existing user database). Unknown tokens render as plain text — they were never inserted by the picker.
- **Mentions in replies count too:** the same code path handles opening comments and replies.
- **Auto-owner-mention:** new thread creation always inserts a mention for the page owner, *unless* the page owner equals the comment author. Marked `is_owner_mention=true` so the feed can label it differently from a direct `@`-mention.
- **Fallback owner:** any owner lookup that misses (no row, or row points to a now-deleted user) returns the literal login `admin`.

## Data model

New PostgreSQL migration `bin/db/migrations/V034__page_owners_and_mentions.sql`. **DDL only** (per the repo's "no data in versioned migrations" rule). Initial population is lazy (see §"Bootstrap").

### `page_owners`
Rename-stable; keyed by `canonical_id`.

| Column | Type | Notes |
|---|---|---|
| `canonical_id` | TEXT PRIMARY KEY | no FK — synthesized IDs aren't always persisted in `page_canonical_ids` (same rationale documented in `V033`) |
| `owner_login` | TEXT NULL | NULL ⇒ orphaned ⇒ admin-fallback at read time |
| `assigned_by` | TEXT NOT NULL | login that wrote this row (the bootstrap writer's id, e.g. `"system:bootstrap"`, the page author, or the admin who reassigned) |
| `assigned_at` | TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP | |

Indexes: `(owner_login)` for "by-owner" admin queries; partial index `WHERE owner_login IS NULL` for orphan listing.

### `comment_mentions`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PRIMARY KEY | |
| `comment_id` | UUID NOT NULL REFERENCES `comments(id)` ON DELETE CASCADE | mentions vanish with the comment |
| `mentioned_login` | TEXT NOT NULL | indexed |
| `mentioning_login` | TEXT NOT NULL | comment author at the time of save (snapshot) |
| `is_owner_mention` | BOOLEAN NOT NULL DEFAULT FALSE | true when the row was auto-created for the page owner |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| `read_at` | TIMESTAMPTZ NULL | NULL ⇒ unread |

Indexes: `(mentioned_login, read_at, created_at DESC)` for feed queries; `(comment_id)` (implicit via FK is sufficient for cascade, but add an explicit index for edit-diff lookups).

Uniqueness: `UNIQUE (comment_id, mentioned_login)` — one mention per (comment, user) pair. The edit-diff path uses this to avoid duplicates.

### Bootstrap (no migration data)

`PageOwnerService.getOwner(canonicalId)` is **find-or-create**:

1. `SELECT owner_login FROM page_owners WHERE canonical_id = ?`.
2. If row exists: return `owner_login` if non-NULL, else `"admin"`.
3. If no row: load the page's frontmatter `author`; if that user exists in `users.login_name`, INSERT `(canonical_id, owner_login=author, assigned_by="system:bootstrap")`; otherwise INSERT `(canonical_id, owner_login=NULL, ...)`. Return resolved owner (admin-fallback applies).

A small `PageOwnershipSaveFilter` (priority sibling to `StructuralSpinePageFilter`) calls `getOwner(canonicalId)` after a successful save, guaranteeing every saved page has a row. New pages thus get the saving user as owner. Toggle: `wikantik.page_ownership.enforcement.enabled` (default `true`).

## Mention extraction & lifecycle

`MentionExtractor` (in `wikantik-main/com.wikantik.comments.mentions`):

```text
parse(body) → Set<String> tokens               // regex [A-Za-z0-9._-]+ after '@'
resolve(tokens, userDb) → Set<String> logins   // filters to real users.login_name
```

`MentionService.recordCreate(comment_id, mentioning_login, body, canonical_id)`:

1. `directMentions = resolve(parse(body))` — minus the author (don't notify yourself).
2. For each in `directMentions`: INSERT `comment_mentions(comment_id, mentioned_login, mentioning_login, is_owner_mention=false)` (ON CONFLICT DO NOTHING via the unique constraint).
3. If this is the *opening* comment of a thread (caller signals): `owner = PageOwnerService.getOwner(canonical_id)`. If `owner != mentioning_login` and `owner not in directMentions`: INSERT one row with `is_owner_mention=true`.

`MentionService.recordEdit(comment_id, mentioning_login, oldBody, newBody)`:

1. `oldSet = resolve(parse(oldBody))`, `newSet = resolve(parse(newBody))` (both minus the author).
2. DELETE rows where `comment_id = ? AND mentioned_login IN (oldSet \ newSet) AND is_owner_mention = false` (don't disturb the owner-mention if present).
3. INSERT rows for `newSet \ oldSet`, `is_owner_mention=false`. Existing rows' `read_at` is preserved.

`MentionService.recordReply(comment_id, mentioning_login, body)`: identical to `recordCreate` minus the owner-mention step.

Comment delete and thread delete: `comment_mentions` rows vanish via `ON DELETE CASCADE` (transitively through `comments`).

## Backend wiring

### `CommentThreadResource` (extended)

The existing endpoints gain mention-recording side effects, **inside the same transaction as the body insert/update where practical**:

- `POST /api/comment-threads?page={name}` → create thread. After inserting thread + first comment, call `MentionService.recordCreate(commentId, currentUser, text, canonicalId)`.
- `POST /api/comment-threads/{threadId}/comments` → reply. After insert, `recordReply(...)`.
- `PATCH /api/comment-threads/{threadId}/comments/{commentId}` → edit. Fetch existing body before update; after update, `recordEdit(commentId, currentUser, oldBody, newBody)`.
- Delete paths: no explicit call — the FK cascade does the work.

For atomicity: `MentionService` accepts an injected `Connection` so callers can run the inserts inside their own transaction. `CommentStore.createThread` is already transactional (autoCommit off); extend its signature to call `MentionService` inside that block. Same for `addComment`.

### New REST endpoints

#### `MentionableUsersResource` → `/api/users/mentionable/*`
- `GET /api/users/mentionable?q=<prefix>&limit=<N≤10>` → `{ users: [{ loginName, fullName }] }`. Logged-in users only. Prefix-match against `login_name` AND `full_name` (LIKE q% on both, OR'd). Excludes locked / soft-deleted accounts. Default `limit=8`.

#### `MyMentionsResource` → `/api/me/mentions/*`
- `GET /api/me/mentions?status=unread|all&limit=<N≤50>&before=<ISO-instant>` → cursor-paginated, newest-first.
  Item: `{id, threadId, commentId, pageName, canonicalId, snippet, mentionedAt, mentionedBy, isOwnerMention, readAt}`.
  Snippet = first 160 chars of the comment body with `@<login>` tokens left intact.
- `GET /api/me/mentions/unread-count` → `{count: N}`.
- `POST /api/me/mentions/{id}/read` → marks `read_at = NOW()` *if and only if* `mentioned_login = currentUser`; else 403.
- `POST /api/me/mentions/mark-all-read` → updates all unread rows for `currentUser`; returns `{updated: N}`.

#### `AdminPageOwnershipResource` → `/admin/page-ownership/*`

Permission: admin (`AllPermission`) — automatic via `AdminAuthFilter` which covers `/admin/*`. No additional per-endpoint check needed.

- `GET /admin/page-ownership?filter=orphaned&limit=&offset=` — pages with `owner_login IS NULL`. Returns `{pages: [{canonicalId, pageName, lastModified}], total}`.
- `GET /admin/page-ownership?filter=by-owner&owner=<login>&limit=&offset=` — pages owned by a specific user (including `admin`). The special value `owner=<orphaned>` is equivalent to `filter=orphaned`.
- `POST /admin/page-ownership/reassign` — body `{pages: [canonicalId,...], newOwner: "login"}`. Validates `newOwner` exists. Writes `assigned_by=currentUser`, `assigned_at=NOW()`. Returns `{updated: N}`.
- `POST /admin/page-ownership/reassign-by-user` — body `{fromOwner: "alice", toOwner: "bob"}`. Bulk-moves every page where `owner_login=fromOwner`. Special value `fromOwner="<orphaned>"` matches NULL.

### Delete-user integration

Augment `AdminUserResource.handleDeleteUser(loginName)` (and the matching code path in `tryDeleteUser` used by `POST /admin/users/bulk-action`):

**Before** calling `UserDatabase.deleteByLoginName(...)`, orphan their owned pages:

```sql
UPDATE page_owners
SET owner_login = NULL, assigned_by = ?, assigned_at = NOW()
WHERE owner_login = ?
```

Where the first `?` is `"system:user-deleted:" + deleted_login` so the audit trail is preserved. No change to `comment_mentions` — historical rows referencing the deleted user persist (mentioning_login as snapshot, mentioned_login still navigable in the feed). The mention-chip rendered in the comment body links to a now-404 user page, which is fine.

### `PageOwnerService` shape (DAO)

```text
Optional<String> getOwnerRaw(canonicalId)      // null vs "admin"-fallback distinguished
String           getOwner(canonicalId)         // applies admin fallback
boolean          setOwner(canonicalId, owner, assignedBy)
int              bulkReassign(fromOwner, toOwner, assignedBy)
int              orphanByOwner(owner, assignedBy)   // delete-user hook
List<PageOwnership> listOrphaned(limit, offset)
List<PageOwnership> listByOwner(login, limit, offset)
int              countOrphaned()
int              countByOwner(login)
```

Where `PageOwnership = {canonicalId, ownerLogin, assignedBy, assignedAt}`. Title/last-modified for the admin list view is composed by the REST layer joining against the structural index (in-memory), not the DAO.

## Frontend wiring

### `MentionPicker` component

A small popover used inside both `CommentComposer` (PageView) and `CommentsDrawer`'s reply textarea.

Behavior:
- The textarea's `onChange` watches the substring between the last `@` and the caret. If the segment is non-empty word characters and contiguous (no whitespace), open the picker; else close it.
- Picker fetches `api.listMentionableUsers(q)` with a 150ms debounce; renders ≤8 items, each `<button>` showing `@<login> — <fullName>`.
- Arrow up/down navigate; Enter / Tab / click selects; Esc closes.
- Selecting inserts `@<login>` (trailing space) at the caret, replacing the in-flight `@<query>` token. Caret moves to end of the inserted text.
- Closes on caret leaving the active token, on blur, on Esc.

State lives in a `useMentionPicker(textareaRef, onTextChange)` hook so the same logic powers both call sites. The hook returns `{open, position, candidates, selectedIndex, accept(login), onKeyDown, onSelectionChange}` and is purely presentation-agnostic; the rendering component (anchored absolutely below the caret line) is shared.

### `CommentBody` component

Replaces the current inline `<span className="comment-body">{c.body}</span>` in `CommentsDrawer`. Walks the body, splits on `/@([A-Za-z0-9._-]+)/`, renders each match as `<a className="comment-mention-chip" href="/wiki/Users/<login>">@login</a>`. Non-match text renders verbatim. No XSS surface (text only; `href` uses `encodeURIComponent` on the login).

### Mentions feed

- Route `/me/mentions` (`MentionsPage.jsx`) under the authenticated routes. Lists items newest-first with:
  - Filter chips: Unread / All (default Unread).
  - Row: `[•] @{mentioningLogin} on {pageName} — "{snippet}" — {when}` with a chevron link "View in context".
  - `is_owner_mention=true` rows render an explicit "(your page)" tag and use a slightly muted color.
  - Per-row dismiss (×) marks read; clicking the "View in context" link also marks read.
  - Toolbar: "Mark all read" button (right-aligned). Live unread count.
- "View in context" deep-links to `/wiki/<pageName>?thread=<threadId>&comment=<commentId>`. `PageView` reads these query params on mount and, after threads load, opens the drawer with `statusFilter='all'` and calls `focusThread(threadId)`. The query params are then stripped from the URL via `history.replaceState` so refreshes don't re-focus.

### `useUnreadMentions` hook + nav badge

- Hook fetches `/api/me/mentions/unread-count` on mount, on `document.visibilitychange` (back-to-foreground), and on a manual refresh signal (e.g., after navigating to `/me/mentions`).
- Returns `{count, refresh}`.
- `Sidebar.jsx` authenticated section gets a new link "My mentions" (between "+ New Article" and the existing primary nav). The badge appears only when `count > 0`, styled like the existing admin sidebar badges.

### `MentionableUsers` API client method

Add to `client.js`:

```js
listMentionableUsers: (q, limit = 8) =>
  request(`/api/users/mentionable?q=${encodeURIComponent(q)}&limit=${limit}`),
listMyMentions: ({ status = 'unread', limit = 25, before } = {}) =>
  request(`/api/me/mentions?status=${status}&limit=${limit}` + (before ? `&before=${encodeURIComponent(before)}` : '')),
getMyMentionsUnreadCount: () =>
  request('/api/me/mentions/unread-count'),
markMentionRead: (id) =>
  request(`/api/me/mentions/${encodeURIComponent(id)}/read`, { method: 'POST' }),
markAllMentionsRead: () =>
  request('/api/me/mentions/mark-all-read', { method: 'POST' }),
```

`api.admin.pageOwnership.*` namespace mirroring the established `api.admin.kgPolicy.*` shape.

### Admin page

`AdminPageOwnershipPage.jsx` follows the `AdminKgPolicyPage` pattern: `<AdminPage>` wrapper + `<PageHeader>` + filter chip group (Orphaned | By Owner) + `<AdminTable>` rows. Per-row "Reassign" action opens an in-app modal with a `MentionableUsers`-backed user picker (reusing the picker hook from §"MentionPicker" without the `@` trigger logic — just a search-as-you-type input). Above the table, a small "Reassign by user" form (two user-pickers + submit) handles the bulk case.

Add an entry to `AdminSidebar.jsx` under the existing Content/Pages group: `{to: '/admin/page-ownership', label: 'Page Ownership'}`.

## Edge cases & decisions

- **Owner mentions and resolution:** when the owner is currently NULL, the resolved owner is `admin` (fallback). So a comment thread on an orphaned page auto-mentions `admin`. Once an admin reassigns ownership, *future* threads notify the new owner; existing rows stay (they're snapshots, not links).
- **Same person mentioned multiple times in one comment:** unique constraint dedupes — one row.
- **Mentioning yourself:** filtered out at extract-time.
- **Edits that introduce a mention long after creation:** insert happens at edit time with `created_at=NOW()`. The mention shows up in the recipient's feed as a fresh notification. Acceptable v0 behavior.
- **A user mentioned in an opening comment AND auto-owner-mention is the same user:** `directMentions` is checked first; the owner-mention insert is skipped (we don't want two rows for the same person on the same comment).
- **A comment with mentions whose thread is later resolved or detached:** mentions remain visible in the recipient's feed. The "View in context" link still works (the drawer can show resolved/detached threads).
- **Race on "mark-all-read":** uses a single UPDATE bounded by `mentioned_login = currentUser AND read_at IS NULL`; any concurrent inserts after the UPDATE are simply still-unread, which is correct.
- **Deleted user mentioned somewhere:** chip renders normally; clicking goes to `/wiki/Users/<login>` (which 404s as a wiki page). No crash, no special-case rendering in v0.
- **Soft-delete of comments:** not introduced. Hard-delete with cascade as today.
- **Permission to read mentions:** strictly self. Admins do not have a "view another user's mentions" endpoint. Out of scope for v0.

## Testing (TDD)

Tests precede implementation where they can demonstrate the defect first.

**Backend (unit):**
- `MentionExtractorTest` — token regex; word boundaries; punctuation-trailing (`Hi @alice.`); UTF-8 surrounding; consecutive mentions; unknown users dropped at resolution.
- `PageOwnerServiceTest` (H2) — find-or-create from frontmatter author; admin fallback for missing user; bulk reassign; orphan-by-owner; orphaned/by-owner listings.
- `MentionServiceTest` (H2) — recordCreate inserts dedup'd rows; owner-mention rule (skip when author == owner, skip when owner already in directMentions); recordEdit diffs old/new and preserves read_at on survivors; recordReply has no owner-mention.
- `MentionableUsersResourceTest` (Mockito + H2 user db) — prefix matches login + fullName; locked excluded; limit cap; logged-in required.
- `MyMentionsResourceTest` — feed listing for the right user only; status filter; cursor pagination; mark-one (403 on other user's id); mark-all returns updated count.
- `AdminPageOwnershipResourceTest` — orphan list; by-owner list; reassign (validates newOwner exists; 400 if not); reassign-by-user (bulk); the page-`delete` permission gate; `<orphaned>` sentinel for `fromOwner`.
- Extend `CommentThreadResourceTest` — thread create writes direct mentions + owner-mention; reply writes direct only; edit diffs; mentions cascade with comment/thread delete.
- Extend `AdminUserResourceTest` — delete-user orphans the user's owned pages first.

**Backend (wire-level IT):**
- Extend `CommentThreadIT`: create a thread on the seeded page with `@admin` in the body; GET `/api/me/mentions` (as admin) and assert the new mention shows up with `isOwnerMention=true` OR `false` (whichever applies). Bonus: test mark-read flips `read_at` and the unread-count endpoint reflects it.

**Frontend (Vitest):**
- `MentionPicker.test.jsx` — opens on `@`, debounce fetch, keyboard navigation, accept inserts and advances caret, closes on Esc/blur/whitespace.
- `useMentionPicker.test.js` — pure-hook tests for the substring detection (last `@…` token under caret).
- `CommentBody.test.jsx` — splits on `@<login>` tokens; renders chips with correct href; ignores unknown punctuation patterns.
- `MentionsPage.test.jsx` — list rendering; filter Unread/All; mark-one and mark-all interactions update local state + api calls; deep-link navigation.
- `Sidebar.test.jsx` — unread badge appears when count > 0; hidden when 0; refresh on visibility-change.
- `AdminPageOwnershipPage.test.jsx` — orphan filter loads orphans; by-owner filter posts a query; per-row reassign opens picker → submit calls API → reload; bulk reassign-by-user form.
- Update `CommentsDrawer.test.jsx` + `PageView.test.jsx` for the new `CommentBody` rendering of chips and the `useMentionPicker` integration in the reply textarea / composer.

**Stability:** the existing PageView interaction tests are stable across the parallel worker pool. The picker tests use light mocks (fake `api.listMentionableUsers`) and don't depend on heavy rendering, so they should slot in without disturbing the harness.

## Out of scope (future)

- Email / push notifications and digests.
- Group mentions, `@everyone`, role-based mentions.
- Dedicated user profile route + page generator.
- Per-thread "assigned to" reassignment (Jira-style).
- Real-time mention badge via WebSocket.
- Login rename / merge tooling.
- Audit log surface for admin reassignments (the `assigned_by` / `assigned_at` columns capture the data; no UI yet).

# Anchored Comments Design

**Date:** 2026-05-26
**Status:** Approved — ready for implementation planning

## Summary

Replace the existing page-level, markdown-appended comment feature with a
Google-Docs-style **anchored comment** system: a reader selects text on a page,
attaches a comment thread to that selection, and others reply and resolve the
thread. Threads are stored in PostgreSQL, anchored to text via a W3C
`TextQuoteSelector`, and re-located into the rendered HTML on each page load.

This is a full replacement of `CommentResource` and `CommentsPanel.jsx`.

## Goals & non-goals

**Goals**
- Anchored comment threads with replies and resolve/reopen (full Google Docs model).
- Anchors survive re-rendering and moderate page edits; gracefully "detach"
  (orphan) when their text is gone.
- Reuse the existing `comment` page permission and visibility (visible to anyone
  who can view the page).
- Side-drawer UI that degrades cleanly on narrow screens.

**Non-goals**
- Page-level (un-anchored) commenting — everything is anchored. Detached threads
  are the only un-anchored state, and they arise from edits, not by design.
- Real-time collaboration / live presence. Load-on-open with optimistic updates.
- Comment versioning or comments tied to a specific page version. Comments
  attach to the live page.
- Suggestions / track-changes (Google Docs "Suggesting" mode).

## Collaboration model (decided)

- **Full Google Docs model:** anchored threads, replies, resolve/reopen.
- **Audience:** reuse the `comment` page permission. Commenters can also resolve
  and reopen (as in Google Docs). Comments are visible to anyone who can view the
  page.

## Data model

New PostgreSQL migration `bin/db/migrations/V033__comment_threads.sql`. Keyed by
`canonical_id` (the rename-stable identifier guaranteed by save-time enforcement),
so comments survive page renames. Idempotent DDL only — no data in the migration.

### `comment_threads`
A thread *is* one anchor.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `canonical_id` | TEXT | indexed; the page the thread belongs to |
| `anchor_exact` | TEXT | the exact selected text |
| `anchor_prefix` | TEXT | ≤32 chars of context before the selection |
| `anchor_suffix` | TEXT | ≤32 chars of context after the selection |
| `status` | TEXT | `open` \| `resolved` |
| `created_by` | TEXT | login name |
| `created_at` | TIMESTAMPTZ | |
| `resolved_by` | TEXT | nullable |
| `resolved_at` | TIMESTAMPTZ | nullable |

Index on `canonical_id`.

### `comments`
The opening comment is the first row; replies are subsequent rows.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID | PK |
| `thread_id` | UUID | FK → `comment_threads(id)` ON DELETE CASCADE |
| `author` | TEXT | login name |
| `body` | TEXT | comment text |
| `created_at` | TIMESTAMPTZ | |
| `edited_at` | TIMESTAMPTZ | nullable |

Index on `thread_id`.

DDL uses `CREATE TABLE IF NOT EXISTS` and grants via the `:app_user` psql
variable, and must be a verified no-op on re-apply.

## Anchoring

Anchors use the W3C Web Annotation `TextQuoteSelector` shape:
`{ exact, prefix, suffix }`.

- **Capture (on selection):** the frontend reads `window.getSelection()` over the
  rendered article DOM and derives `exact`, plus up to 32 characters of `prefix`
  and `suffix` context. A selection that begins or ends inside a KaTeX-rendered
  span is rejected at capture time with an inline "can't comment on math" hint.
- **Re-anchoring (on load):** a pure function maps each thread's selector back to
  a DOM `Range`:
  1. Exact text match against the article's text content (whitespace-normalized).
  2. If multiple matches, disambiguate using `prefix`/`suffix`.
  3. If no match, the thread is **detached** (orphaned).
  Matched ranges are wrapped in `<mark class="comment-highlight" data-thread-id="…">`.
- **Persistence:** the selector is stored once at creation and never rewritten.
  Each load re-matches against the current rendered HTML, which is what makes the
  system tolerant of re-rendering and moderate edits.
- **Library:** use Hypothesis's MIT-licensed `dom-anchor-text-quote` and
  `dom-anchor-text-position` for matching and Range construction rather than a
  hand-rolled matcher. They are the de-facto standard for this problem. (npm
  dependencies; not in Apache RAT source scope.)

## Backend (`wikantik-rest` + `wikantik-main`)

### `CommentStore` DAO
- Package `com.wikantik.comments` in `wikantik-main`.
- Constructed with a `javax.sql.DataSource`, mirroring `PageVerificationDao`
  (`com.wikantik.pagegraph.spine`): direct JDBC, `ds.getConnection()` per query,
  `LOG.warn` with context on `SQLException` (never an empty catch).
- Methods: list threads + comments by `canonical_id` (optionally filtered by
  status); create thread with first comment; add reply; edit comment; delete
  comment; delete thread; resolve; reopen.

### `CommentThreadResource`
Replaces `CommentResource` entirely. Extends `RestServletBase`. Resolves page
name → `canonical_id` via `DefaultStructuralIndexService.resolveCanonicalIdFromSlug()`
before touching the store.

> **URL deviation (locked at planning time):** the resource is mounted at
> `/api/comment-threads/*`, not `/api/pages/{name}/comment-threads`, because
> `PageResource` already owns the `/api/pages/*` servlet mapping and a Servlet
> URL pattern cannot nest a second servlet under it. The page is passed as a
> `?page=` query parameter on list/create; thread-scoped operations carry the
> `threadId` in the path and resolve the page server-side from the stored
> `canonical_id`. (Same class of constraint already documented for `/for-agent`.)

Endpoints under `/api/comment-threads`:

| Method | Path | Action | Permission |
|--------|------|--------|------------|
| GET | `/api/comment-threads?page={name}&status=open\|resolved\|all` | list threads + comments | `view` |
| POST | `/api/comment-threads?page={name}` | create thread (selector + first body) | `comment` |
| POST | `/api/comment-threads/{threadId}/comments` | reply | `comment` |
| PATCH | `/api/comment-threads/{threadId}/comments/{commentId}` | edit body | author only |
| DELETE | `/api/comment-threads/{threadId}/comments/{commentId}` | delete comment | author, or page `delete`/admin |
| POST | `/api/comment-threads/{threadId}/resolve` | mark resolved | `comment` |
| POST | `/api/comment-threads/{threadId}/reopen` | mark open | `comment` |

Default `status` for GET is `all` (drawer filters client-side, but the query
param lets callers narrow). Permission denials send HTTP 403 and the JSON body
cites the reason explicitly.

`CommentResource` and its `/api/comments/{PageName}` routes are removed, along
with the corresponding `web.xml` mapping if one exists.

## Frontend (`wikantik-frontend`)

Delete `CommentsPanel.jsx`. Add:

- **Selection layer** in `PageView`: on a text selection inside `articleRef`, show
  a floating "💬 Comment" button positioned near the selection. Clicking opens a
  composer that POSTs a new thread with the captured selector and first comment.
- **Re-anchoring / highlight module** (pure, unit-tested): given the thread list
  and the article DOM, wrap matched ranges in `<mark class="comment-highlight"
  data-thread-id>`. Runs *after* HTML injection and `renderMath()` so it never
  fights KaTeX. Returns the set of detached thread IDs.
- **`CommentsDrawer`** — a right-side slide-over toggled by a "💬 Comments (N)"
  button added to the existing top-right action bar in `PageView` (beside
  Edit/Rename/Delete). The drawer lists threads with an **Open / Resolved / All**
  filter, supports reply and resolve/reopen, and shows detached threads in a
  "Detached" group at the bottom.
- **Cross-focus:** clicking a `comment-highlight` opens the drawer and
  scrolls/pulses its thread; clicking a thread in the drawer pulses its highlight.
- New `api/client.js` methods for each endpoint above.

## Edge cases & decisions

- **KaTeX / math:** matcher walks text nodes and treats KaTeX spans as atomic;
  selections touching rendered math are rejected at capture time. Highlighting
  runs after `renderMath`.
- **Old page versions:** comments attach to the live page only. Viewing a prior
  version shows no highlights and no drawer affordance to add.
- **Concurrency:** load-on-open plus optimistic update on post. No polling or
  realtime. Acceptable for wiki usage patterns.
- **Legacy comments:** before removing `CommentResource`, run a one-time operator
  export of existing markdown-appended comments to `backups/legacy_comments_<date>.md`
  (a documented one-shot, not part of the migration). The live system then starts
  clean — anchored-only.

## Testing (TDD)

Tests precede implementation, demonstrating the defect first where practical.

- **Re-anchoring matcher** (highest risk) — Vitest unit tests: exact match;
  multi-match disambiguation by prefix/suffix; whitespace normalization;
  no-match → detached.
- **`CommentStore`** — unit tests against H2; plus a migration idempotency test
  (apply twice, assert no-op).
- **`CommentThreadResource`** — Mockito unit tests covering every permission
  branch, asserting refusal payloads cite the reason; plus a Cargo-launched
  wire-level IT exercising the full create → reply → resolve → reopen cycle
  (per the REST/MCP write-surface convention: unit + wire-level IT together).
- **`CommentsDrawer`** — testing-library component test: status filter, reply,
  resolve/reopen, detached group rendering.
- Gate the prod-code commit on the full IT reactor:
  `mvn clean install -Pintegration-tests -fae`.

## Out of scope (future)

- Suggestion/track-changes mode.
- Real-time presence and live updating of threads.
- Per-version comment history.
- Email/notification on reply or mention.

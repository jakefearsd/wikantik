# Comments and Mentions

Wikantik supports Google-Docs-style anchored comment threads on any wiki page,
plus an @-mention system that lets commenters notify colleagues by name. Mentions
land in a per-user inbox reachable at `/me/mentions`.

## What a comment thread is

A comment thread is anchored to a specific passage of text on a page. When you
open the Comments drawer on a page you see all threads for that page. Each thread
consists of the root comment (which identifies the anchor) and any replies. A
thread can be open (active discussion) or resolved (closed). Threads are keyed by
the page's `canonical_id` so they survive a page rename.

The backing tables are `comment_threads` and `comments` (migration
`V033__comment_threads.sql`). Mention records live in `comment_mentions`
(migration `V034__page_owners_and_mentions.sql`).

## Commenting on a page

1. Open any wiki page (`/wiki/{PageName}`).
2. Select a passage of text with your mouse or keyboard. The selection is used as
   the anchor (`exact`) for the thread; the surrounding text (`prefix`, `suffix`)
   is stored for re-location after edits.
3. A floating **Comment** button appears next to the selection. Click it to open
   the new-thread composer (`CommentComposer` in `PageView.jsx`). Press **Esc**
   to cancel.
4. Type your comment in the **Add a comment** box. You can @-mention any wiki user
   by typing `@` followed by the start of their login name (see @-mention
   autocomplete below).
5. Press **Cmd+Enter** (or **Ctrl+Enter**) or click the **Comment** button to post.
   The Comments drawer opens with your new thread.

You cannot anchor a comment to a math expression. Selecting one shows "Can't
comment on math expressions — select plain text."

The page must be indexed (have a `canonical_id`). Pages that have never been
saved or that the structural index has not yet processed return a 404 on the
comments endpoint. Reload the page and try again if you see an error immediately
after creating a new page.

## The Comments drawer

Once a page has at least one thread, a **Comments (N)** button appears in the page
header (N is the number of open threads). Click it to toggle the `CommentsDrawer`
sidebar. A page with no threads has no such button; you open the drawer by posting
the first comment, or by following a mention link. The drawer shows:

- A status-filter selector: **Open**, **Resolved**, **All**. Defaults to Open.
- Each thread card showing the anchor excerpt in quotes, all comments in the
  thread with author names and body text, and a reply textarea.
- A **Resolve** button on open threads. Resolved threads show a **Reopen** button
  instead.
- Threads whose anchor text no longer appears in the page are grouped under a
  **Detached** label at the bottom of the drawer. A detached thread has no reply
  box and no Resolve button; a resolved detached thread can still be reopened, and
  users with the `delete` permission can delete it.

### Replying to a thread

1. Click anywhere on a thread card to focus it.
2. Type in the **Reply…** textarea at the bottom of the card.
3. Press **Cmd+Enter** (or **Ctrl+Enter**) or click **Reply**.

### Resolving and reopening

- Any user with the page `comment` permission can click **Resolve**.
- Any user with the page `comment` permission can click **Reopen** on a resolved
  thread.

### Deleting a thread

Only users with the page `delete` permission (moderators / admins) see the
**Delete** button on a thread card. Clicking it asks "Delete this thread
permanently?" inside the card — no native dialog. Deleting a thread cascades to all its comments via `ON DELETE
CASCADE`.

## @-mention autocomplete

In any comment or reply textarea, type `@` immediately followed by the start of a
login name. A popover (`MentionPicker`) appears at the caret showing up to 8
matching users. Mentions work only in comment and reply boxes, not in the page
editor. Each row shows `@loginName` and, if different, the user's full
name.

Navigate the popover with the keyboard:
- **Up/Down arrows** — move selection.
- **Enter** or **Tab** — accept the highlighted candidate.
- **Escape** — dismiss.

Click any candidate with the mouse (uses `onMouseDown` so the textarea does not
lose focus first).

Accepted mentions are inserted as `@loginName` in the comment body. `CommentBody`
renders them as clickable chips that link to `/wiki/Users/{loginName}`.

The autocomplete calls `GET /api/users/mentionable?q={prefix}&limit=8` to fetch
candidates (the server default is 8 and the cap is 10).

## The unread mentions inbox (`/me/mentions`)

Route: `/me/mentions` — rendered by `MentionsPage`.

When someone @-mentions you in a comment, or posts the first comment on a page
you own, a mention record is written to `comment_mentions`. The inbox shows all
mentions for the logged-in user.

The **Personal Zone** sidebar shows a notification badge (count of unread
mentions) on the "Me" collapsible section. The count is fetched on mount and
refreshed whenever the browser tab comes back to the foreground (`visibilitychange`
event), via `useUnreadMentions`.

### Using the inbox

- Filter between **Unread** and **All** using the tab buttons at the top of the
  page.
- Each row shows who mentioned you, which page the comment is on (a link that
  opens the page with `?thread=...&comment=...` query params to scroll the
  comments drawer to the specific thread), whether the mention was on a page you
  own ("your page" tag), and a relative timestamp.
- Click **✕** on an unread row to mark that one mention read.
- Click **Mark all read** to mark the entire list read.

Marking is optimistic — the UI updates immediately and reverts with a toast error
if the server call fails.

### Owner mentions

When someone starts the first thread on a page, the page owner is automatically
notified as an `isOwnerMention` record (flagged "your page" in the inbox). This
fires only if the commenter is not the owner themselves.

## Permissions

| Action | Required permission |
|--------|---------------------|
| View threads on a page | `view` on the page |
| Post a new thread | `comment` on the page |
| Reply to a thread | `comment` on the page |
| Resolve or reopen a thread | `comment` on the page |
| Edit your own comment (API only — the drawer has no edit control) | Author of that comment |
| Delete your own comment (API only — the drawer has no per-comment delete) | Author of that comment |
| Delete any comment on a page | `delete` on the page (moderators, admins) |
| Delete an entire thread | `delete` on the page |
| Read your mention inbox | Authenticated (any role) |

Anonymous users (unauthenticated) cannot comment. The REST layer enforces
permissions via `RestServletBase.checkPagePermission()`. Default role permissions
are configured via the admin security panel at `/admin/security`.

## Admin and moderation controls

- **Thread deletion**: any user holding the page `delete` permission can delete
  any thread or any individual comment on that page. This includes wiki
  administrators.
- **Status management**: moderators can resolve threads to mark a discussion
  closed without deleting it.
- There is no separate "comments enabled/disabled" flag per page — commenting is
  controlled entirely by the `comment` ACL permission on the page or the global
  role grants.
- To disable comments wiki-wide, revoke the `comment` grant from all roles at
  `/admin/security`.

## REST/endpoint reference

All endpoints are under `/api/comment-threads`. All mutating routes require an
authenticated session.

### Threads

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/comment-threads?page={slug}[&status=open|resolved|all]` | List threads for a page. Default status: all. |
| `POST` | `/api/comment-threads?page={slug}` | Create a new thread. Body: `{exact, text, prefix?, suffix?}` |
| `DELETE` | `/api/comment-threads/{threadId}` | Delete a thread and all its comments. Requires page `delete` permission. |

### Comments within a thread

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/comment-threads/{threadId}/comments` | Add a reply. Body: `{text}` |
| `PATCH` | `/api/comment-threads/{threadId}/comments/{commentId}` | Edit a comment (author only). Body: `{text}` |
| `DELETE` | `/api/comment-threads/{threadId}/comments/{commentId}` | Delete a comment (author or page moderator). |

### Thread lifecycle

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/comment-threads/{threadId}/resolve` | Resolve a thread. |
| `POST` | `/api/comment-threads/{threadId}/reopen` | Reopen a resolved thread. |

### Mention feed

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/me/mentions[?status=unread|all&limit=N&before=ISO8601]` | Fetch mention feed. Default limit: 25, max: 50. |
| `GET` | `/api/me/mentions/unread-count` | Returns `{count: N}`. |
| `POST` | `/api/me/mentions/{id}/read` | Mark one mention read (addressee only). |
| `POST` | `/api/me/mentions/mark-all-read` | Mark all unread mentions read. Returns `{updated: N}`. |

All mention routes require authentication; anonymous callers receive 401.

## Troubleshooting

**"Page not found or not indexed"** when trying to comment — the page lacks a
`canonical_id` in the structural index. Save the page once (so the
`StructuralSpinePageFilter` assigns a `canonical_id`) and reload.

**Comments drawer is empty even though threads exist** — check the status filter.
Flip it to "All" to confirm threads exist in resolved state.

**@-mention popover does not appear** — the autocomplete triggers only when `@`
is typed immediately before a run of `[A-Za-z0-9._-]` characters. A leading space
before `@` is fine; what matters is that the pattern `@<word>` appears at the
caret position.

**Notification badge does not update** — the badge refreshes on mount and on
`visibilitychange`. Switch away from the tab and back to force a refresh. If the
count stays wrong, check that `/api/me/mentions/unread-count` returns a valid
`{count: N}` JSON object.

## Cross-links

- [docs/admin/PageOwnership.md](../admin/PageOwnership.md) — page ownership and how it ties into owner mentions
- [docs/admin/AuditLog.md](../admin/AuditLog.md) — audit trail for comment moderation actions
- [docs/admin/ApiKeys.md](../admin/ApiKeys.md) — API key auth for programmatic access to comment endpoints

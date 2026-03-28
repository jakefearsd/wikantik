# JSP to React Migration: Complete UI Feature Parity

## Problem

The wiki has two parallel UIs: a legacy JSP template system (68 JSP files, 70 custom tags, 42 UI/forms classes) and a React SPA at `/app/`. The React SPA covers core workflows (page view/edit, search, admin panel) but has 8 functionality gaps that prevent removing the JSP UI. Until these gaps are closed, both UIs must be maintained — doubling the surface area for bugs, security audits, and testing.

## Goal

Implement 8 missing React features to achieve full UI parity with the JSP pages, then identify and catalog all JSP dead code for removal. The context path change (removing the `/app/` prefix) and actual JSP removal are separate follow-ups after parity is achieved.

## Scope

**In scope:**
- 8 React features to close the JSP functionality gap
- New REST endpoints where needed
- Identification of ~180 files (~1.8 MB) of dead code to remove after migration
- Testing for all new features

**Out of scope:**
- Actually deleting the JSP code (that's a follow-up after parallel testing)
- Context path change (`/app/` → `/`) — separate spec
- Install wizard (`Install.jsp`) — not needed for an always-database-backed deployment

## Features

### Feature 1: Page Delete

**Gap:** No delete button in the React SPA. The REST endpoint `DELETE /api/pages/{name}` already exists with permission enforcement.

**React changes:**
- Add a "Delete" button to `PageView.jsx`, gated by `page.permissions.delete`
- Confirmation modal (same pattern as admin panel delete confirmations)
- On success, redirect to `/wiki/Main`
- Button styled as `btn btn-ghost btn-danger btn-sm`

**No backend changes needed.** The API and permission check are already implemented.

### Feature 2: Page Rename

**Gap:** No REST endpoint for renaming. Only the JSP `Rename.jsp` triggers the rename flow.

**Backend — new REST endpoint:**
- `POST /api/pages/{name}/rename` with JSON body `{"newName": "NewPageName"}`
- Calls `PageManager.renamePage(oldName, newName)` (which already handles reference updates, search re-indexing, and event firing)
- Permission check: requires `rename` permission on the source page
- Returns `{"success": true, "oldName": "...", "newName": "..."}`
- Returns 400 if new name is blank, 409 if target page already exists

**React changes:**
- "Rename" button on `PageView.jsx`, gated by `page.permissions.rename`
- Modal with text input for new name
- On success, navigate to the renamed page

### Feature 3: Diff / Version Comparison Viewer

**Gap:** No visual diff viewer in React. The REST endpoints already exist: `GET /api/history/{name}` and `GET /api/diff/{name}?from=N&to=M`.

**React changes:**
- New component `DiffViewer.jsx` at route `/app/diff/:name`
- Fetches version history, allows selecting two versions to compare
- Displays the diff as HTML (the API returns pre-rendered diff HTML)
- "Compare versions" button in the existing `ChangeNotesPanel.jsx`
- Version selector dropdowns for "from" and "to"

**No backend changes needed.** Both API endpoints exist.

### Feature 4: Comments

**Gap:** No comment system in React. JSP uses `Comment.jsp` which appends comments as wiki page text.

**Backend — new REST endpoints:**
- `GET /api/pages/{name}/comments` — returns comments for a page
- `POST /api/pages/{name}/comments` — adds a comment (body: `{"text": "..."}`

**Comment storage:** The existing wiki convention stores comments as appended page sections with author/timestamp metadata. The new endpoints parse and return these as structured JSON rather than raw page text.

**Permission:** Gated by `comment` permission (already in the permission hierarchy — `modify` implies `comment`).

**React changes:**
- New `CommentsPanel.jsx` component below page content in `PageView.jsx`
- Shows existing comments with author, timestamp, and text
- "Add Comment" form with textarea, gated by `permissions.comment`
- Comment form submits to the POST endpoint

### Feature 5: User Preferences

**Gap:** No self-service profile editing in React. JSP `UserPreferences.jsp` allows users to change their display name, email, and password.

**Backend — new REST endpoint:**
- `GET /api/auth/profile` — returns the current user's profile (fullName, email, wikiName)
- `PUT /api/auth/profile` — updates the current user's profile (fullName, email, optional password)
- These are self-service (no admin permission needed — the user edits their own profile)
- Password change requires the current password for verification
- New password validated with NIST rules (existing `PasswordValidator`)

**React changes:**
- New route `/app/preferences` → `UserPreferencesPage.jsx`
- Form fields: Full Name, Email, Wiki Name (read-only), Password Change (current + new + confirm)
- Link in the `UserBadge.jsx` component (where the current user's name is shown)

### Feature 6: Lost Password / Recovery

**Gap:** No password recovery in React. JSP `LostPassword.jsp` sends a random new password via email.

**Backend — new REST endpoint:**
- `POST /api/auth/reset-password` with body `{"email": "user@example.com"}`
- Looks up user by email in `UserDatabase`
- Generates a random password via `TextUtil.generateRandomPassword()`
- Sends it via `MailUtil.sendMessage()` (SMTP already configured)
- Saves the new password hash
- Returns generic success message (doesn't reveal whether email exists — prevents enumeration)
- Rate-limited: max 3 attempts per email per hour

**React changes:**
- New route `/app/reset-password` → `ResetPasswordPage.jsx`
- Simple form: email input + submit button
- Link from the login form: "Forgot your password?"
- Success message: "If an account exists with that email, a new password has been sent."

### Feature 7: Group Creation Form

**Status: ALREADY COMPLETE.**

The admin Security tab has a "Create Group" button that opens `GroupFormModal` in create mode. The `PUT /admin/groups/{name}` endpoint handles group creation. No additional work needed.

### Feature 8: Page Conflict Resolution

**Gap:** When two users edit simultaneously, the second save gets a 409 Conflict from the REST API (via `expectedVersion` or `expectedContentHash`), but the React editor just shows a generic error.

**React changes to `PageEditor.jsx`:**
- Detect 409 status in the save error handler
- Show a conflict resolution modal with:
  - "Your version" (the text the user was editing)
  - "Server version" (fetch the current page content)
  - Options: "Overwrite" (force save), "Discard my changes" (reload), or "Copy to clipboard" (save text externally and reload)
- The "Overwrite" option re-saves without `expectedVersion`/`expectedContentHash`

**No backend changes needed.** The 409 response is already implemented.

### Feature 9: JSP Dead Code Catalog

After features 1-8 are implemented and tested, the following code becomes dead:

**Files to delete (~180 files, ~1.8 MB):**

| Category | Files | Size | Package/Path |
|----------|-------|------|-------------|
| JSP pages | 68 | 968 KB | `wikantik-war/src/main/webapp/*.jsp`, `templates/**/*.jsp` |
| Custom tags | 70 | 388 KB | `com.wikantik.tags.*` |
| UI classes | ~25 | 312 KB | `com.wikantik.ui.*` (except WikiServletFilter, SitemapServlet) |
| Form classes | 10 | 72 KB | `com.wikantik.forms.*` |
| Legacy JS | 1 | 40 KB | `scripts/mootools.js` |

**web.xml changes:**
- Remove `WikiJSPFilter` definition and mapping
- Remove `<jsp-config>` section
- Remove welcome file pointing to `Wiki.jsp`
- Remove container-managed auth section (commented out but still present)

**Classes that MUST be kept:**
- `WikiServletFilter` — used by REST API for auth/session setup
- `SitemapServlet` — generates sitemap.xml
- `AtomFeedServlet` — generates Atom feed
- `RecentArticlesServlet` — serves recent articles JSON
- `AttachmentServlet` — legacy attachment downloads (keep until REST attachment endpoint fully replaces it)

## REST API Additions Summary

| Method | Path | Feature |
|--------|------|---------|
| POST | `/api/pages/{name}/rename` | Page rename |
| GET | `/api/pages/{name}/comments` | List comments |
| POST | `/api/pages/{name}/comments` | Add comment |
| GET | `/api/auth/profile` | Get own profile |
| PUT | `/api/auth/profile` | Update own profile |
| POST | `/api/auth/reset-password` | Password recovery |

## React Component Additions Summary

| Component | Route | Feature |
|-----------|-------|---------|
| Delete button + modal | (in PageView.jsx) | Page delete |
| Rename modal | (in PageView.jsx) | Page rename |
| `DiffViewer.jsx` | `/app/diff/:name` | Version comparison |
| `CommentsPanel.jsx` | (in PageView.jsx) | Comments |
| `UserPreferencesPage.jsx` | `/app/preferences` | User preferences |
| `ResetPasswordPage.jsx` | `/app/reset-password` | Password recovery |
| Conflict resolution modal | (in PageEditor.jsx) | Conflict resolution |

## Testing

Each feature gets:
- REST endpoint tests (in `wikantik-rest` module) for new endpoints
- React component verification via manual testing after deploy
- Existing test suite must continue to pass (`mvn clean test -T 1C -DskipITs`)

## Implementation Order

Features can be implemented in any order, but this sequence minimizes dependencies:

1. Page Delete (simplest — no backend changes)
2. Page Conflict Resolution (no backend changes)
3. Diff/Version Viewer (no backend changes)
4. Page Rename (new endpoint + React)
5. Comments (new endpoints + React)
6. User Preferences (new endpoint + React)
7. Lost Password (new endpoint + React)
8. JSP Dead Code Catalog to prepare for removal (don't delete the JSP code yet, we'll follow up for that)

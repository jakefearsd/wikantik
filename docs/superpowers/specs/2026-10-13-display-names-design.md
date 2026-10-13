# Display Names Instead of Login Names — Design

**Date:** 2026-10-13 · **Status:** approved design, awaiting spec review · **Target:** 2.4.58

## Intent

Users are shown to each other by a **display name** they choose. A user's **login name** — which is
their email address when they sign in with Google (`wikantik.sso.claimMapping.loginName=email` on
prod) — is visible only to that user and to administrators. Success: no API, page, feed, export or
tool reachable by a non-admin returns another user's login name or email; users set their display
name in Preferences; @-mentions work by display name.

Decisions taken with the owner:

1. Display names are **unique**, case-insensitive (prevents impersonation in comments and history).
2. Page **access-rule directives are hidden from non-editors** (ACL text names users by login).
3. **Resolve at read time**: storage keeps the stable login as the key; one identity service turns it
   into the current display name on every non-admin output, so renames apply everywhere, including
   old history.

## Current state (2.4.57)

- `users.full_name` is user-editable (Preferences, `PUT /api/auth/profile`; admin edit), non-unique,
  may be blank (then authorship falls back to the login). `wiki_name` is derived from it and unique
  (`JohnSmith2` numbering). `ProfileNameRules` / `ReservedProfileNames` reject reserved words and
  another account's login.
- Google SSO provisioning (`SSOAutoProvisionService`) sets `full_name` from the IdP `name` claim at
  first login, falling back to the login (the email) when the claim is missing or unusable; it never
  re-syncs. Auto-provisioning is on by default, so any Google account can become a signed-in user.
- Page version authorship stores `WikiSession.getUserPrincipal()` — the full name — in
  `page.properties` (`VersioningFileProvider`) and the Lucene index. MCP writes store the client name.
- Comments (`comment_threads.created_by`, `resolved_by`, `comments.author`), mentions
  (`comment_mentions`) and `page_owners` store **logins**. `GET /api/comment-threads` returns them to
  anonymous readers. The mention picker inserts the literal `@<login>` into comment text, and
  `GET /api/users/mentionable` returns `loginName` to any signed-in user.
- `MentionExtractor`'s pattern excludes `@`, so mentioning an email login never resolves.
- Page ACLs (`[{ALLOW edit alice}]`) match login names only; the directive text stays in the page
  body and is returned raw by the page API, `?format=md`, history/diff, chunks (search, bundle), MCP
  reads and exports. Rendered HTML already hides it.
- A profile save writes the DB directly (`db.save()`), so the live session keeps the old name.
- `MyPagesResource` matches authors by full name or login, so users who share a name see each other's
  pages. `PageOwnerService` bootstrap matches the version author (a full name) against logins, so it
  almost never resolves.
- Prod data (2026-10-13): 10 accounts, 5 with email logins; no blank or email-shaped full names;
  one duplicate full-name group ("Jake Fear", 4 accounts); 0 comments and 0 mentions.

## Design

### 1. Identity model

- **Display name = `users.full_name`**, relabelled "Display name" in the UI and API docs. No new column.
- **Rules** (one place: `ProfileNameRules`), applied on every write path — self profile update,
  admin user edit, SSO provisioning, SCIM (`name.formatted`), and the legacy `UserManager` path:
  - required; trimmed; 2–64 characters; no control characters; no `@`;
  - not a reserved name (`ReservedProfileNames`); not equal (case-insensitively) to any account's login;
  - **unique, case-insensitive and trimmed**, among all accounts other than the one being saved.
  Violations are reported as a field error (REST `400`/`409` with the reason; SCIM `409 uniqueness`).
  Uniqueness is enforced in the application, not by a database index: migrations run automatically
  at container start, and an index would fail there on the existing duplicates.
- **Login name** stays the permanent identifier for sign-in, ACLs, groups, ownership and storage.
- **SSO first login:** display name from the IdP `name` claim when it satisfies the rules; if taken,
  append ` 2`, ` 3`, …; if absent or unusable, a neutral placeholder `Member N` (smallest free N).
  Never the email; never re-synced on later logins (unchanged).
- **Session refresh:** saving a profile (self or admin) updates the live session's principals for that
  user, so the new name applies immediately (via the existing `WikiSession` principal-injection path).
- `wiki_name` keeps its current derivation and is not exposed by this work.

### 2. Storage keys and the identity service

- **Authorship stores the login** from now on, through one helper used by every writer: page saves
  (`PageResource` PUT/PATCH, `DefaultPageLifecycle` default), derived ingest, Obsidian import, admin
  cluster/derived writers, renames (`DefaultPageRenamer`), attachments (`AttachmentServlet`), group
  creator/modifier (`DefaultGroupManager`). Comments, mentions and owners already store logins.
  MCP writes keep storing the MCP client name (an agent label, not a person).
- **`PublicIdentity` service** (interface in `wikantik-api`, implementation in `wikantik-main`,
  exposed on the engine like other managers):
  - `String displayName(String storedPrincipal)` — a login → that account's current display name;
    anything else (legacy full names, `unknown`, `Guest`, agent labels) → returned unchanged, **except**
    a value that looks like an email → `unknown contributor`; null/blank → `unknown`.
  - `Optional<String> userIdFor(String login)` / `Optional<String> loginForUserId(String uid)` — the
    opaque `users.uid` used by mentions and the picker.
  - `Optional<String> loginForDisplayName(String name)` — for author filters (case-insensitive).
  - Backed by a small in-memory map, invalidated on profile save/delete events and otherwise refreshed
    after 60 seconds, so a rename made on another node or directly in the database shows within a minute.
- **Who sees logins:** only the `/admin/*` REST surface and the admin MCP server (both
  admin-equivalent). All other surfaces show display names to everyone, administrators included.

### 3. Surfaces that switch to display names

Each resolves stored principals through `PublicIdentity` before responding:

| Surface | Field(s) |
|---|---|
| `GET /api/pages/{name}`, `GET /api/pages` | `author` |
| `GET /api/history/{name}` | per-version `author` |
| `GET /api/recent-changes` | `author` |
| `GET /api/search` + SPA search facet | `author`; the Lucene author field indexes the resolved display name (after a rename, a page's indexed author updates when the page is next reindexed; the displayed `author` is always current) |
| `GET /api/pages/for-agent/{id}` | `recent_changes[].author`; `verified_by` when it is a login |
| `GET /api/comment-threads` | `createdBy`, `resolvedBy`, `comments[].author` → display names, plus an opaque `authorId` for client keying |
| `GET /api/me/mentions` | `mentionedBy` → display name |
| `GET /api/users/mentionable` | returns `{id: uid, displayName}`; no `loginName` |
| `/feed.xml`, `/api/recent-articles`, `RecentChangesPlugin`, `RecentArticles` plugin | author |
| Ontology `dct:creator` (`PageRecordBuilder` → `PageProjector`) | frontmatter `author` when it is a login |
| Knowledge MCP (`get_page`, `retrieve_context`, `list_pages`, `get_page_for_agent`) and `/tools/*` (`get_page`, `search_wiki`) | `author`, `recent_changes`, `verified_by`; `author` filters accept a display name |
| `MyPagesResource` | matches the stored login exactly (fixes the shared-name mix-up) |
| `PageOwnerService` bootstrap | the version author is now a login, so it resolves |

`OpenApiDocument`'s "Last author login" description becomes "display name of the last author".

Fixed while here, because they are leaks on the same surfaces:
- `DefaultRecentArticlesManager` (feeds, `/api/recent-articles`) applies no view-ACL filter, so
  restricted pages' titles, excerpts and authors can appear; filter by anonymous view permission.
- `MyMentionsResource` returns a comment snippet without checking that the recipient can view the
  page; omit the snippet when they cannot.

### 4. Mentions

- The picker inserts `@[Display Name](user:<uid>)`; the display text is a snapshot, the uid is the key.
- `MentionExtractor` parses `user:<uid>` tokens, resolves each to a login through `PublicIdentity`,
  and records `comment_mentions` rows as today (login keys). Legacy `@login` text keeps parsing for
  notifications only (there is none on prod).
- `CommentBody.jsx` renders a mention chip with the **current** display name (the comment-threads
  response carries a `mentions: {uid: displayName}` map); the dead `/wiki/Users/<login>` link goes.

### 5. Access-rule directives hidden from non-editors

- One utility (consolidating the existing stripping in `SummaryExtractor` / `PageExcerpts`) removes
  `[{ALLOW …}]` / `[{DENY …}]` directives outside code spans and blocks.
- Applied to raw page text for any caller **without `edit` permission** on the page: `GET /api/pages`
  `content`, `?format=md|json` (`WikiPageFormatFilter`), history/version text and diff, knowledge MCP
  `read_pages` / `get_page`, `/tools/get_page`, and the Obsidian export.
- Applied unconditionally at index time: chunking (bundle, retrieval, dense embeddings) and the Lucene
  content field — so search snippets and bundles never carry them. One index rebuild after deploy.
- ACL evaluation is unchanged (still login-based); editors and admins see the directives as today.
  Docs recommend naming groups rather than people in ACLs.

### 6. Frontend

- Preferences: the field is labelled "Display name", required, with the server's validation message
  shown inline. Login name stays visible to its owner, read-only.
- Admin → Users: shows login and display name; display-name edits obey the same rules.
- Comments drawer, mentions page, mention picker, history, search results and page meta show
  display names only. The picker shows display names and inserts the token from §4.

## Data cleanup and rollout

1. **One-time script** `bin/db/one-shots/dedupe_display_names.sh` (outside `bin/db/migrations/`, per
   the no-data-backfill rule), dry-run by default, `--apply` to write: for each case-insensitive
   duplicate group keep the earliest-created account's name and rename the others `<name> 2`,
   `<name> 3`, …; replace any email-shaped or blank display name with `Member N`. Prints what it
   changes (display names only). On prod: one group, three renames.
2. Release and deploy (2.4.58). Existing page-history author strings are not rewritten; the
   identity service resolves the ones that are logins and masks any email-shaped ones.
3. Run the one-time script on prod, then rebuild the indexes (chunks + Lucene) so chunk text loses
   ACL directives and the search author field carries display names.
4. Update the wiki pages that describe profiles, mentions and ACLs (TextFormattingRules, LoginHelp,
   WikantikSecurityGuide, PrivacyPolicy's "Your names are not secret" paragraph, the comments docs).

## Error handling

- Rule violations return the field and reason; the SPA shows them inline. SSO provisioning never fails
  on a name conflict (it suffixes or uses a placeholder) and logs at INFO what it chose.
- `PublicIdentity` lookups never throw to callers: a database failure logs a WARN with context and
  returns `unknown` for that principal, so a page still renders.

## Testing

- Unit: `ProfileNameRules` (every rule, the uniqueness check excluding the account being saved, case and
  whitespace), SSO name selection (claim, suffix, placeholder), `PublicIdentity` (login, legacy name,
  email-shaped, blank, rename invalidation), mention token parse/render, ACL-directive stripping
  (including code spans/blocks).
- REST/DB tests per changed resource, asserting display names for non-admins and logins on `/admin/*`.
- **Leak-probe integration test** (rest IT module): seeds a user whose login is
  `leak-probe-<random>@example.test` with display name `Leak Probe`; that user edits a page, comments,
  is mentioned, and is named in an ACL on a public page. Then, as anonymous and as a second signed-in
  non-admin, it calls every public surface — page/list/history/recent-changes/search/for-agent APIs,
  comment threads, mentions, mentionable, `?format=md`, `/feed.xml`, `/api/recent-articles`, `/sparql`
  and `/export/graph.nt`, knowledge MCP tools and `/tools/*` — and asserts the login string never
  appears, while an admin still sees it under `/admin/users`.
- Frontend: vitest for Preferences validation, comment/mention rendering and the picker.

## Out of scope

- Email: messages go only to the account's own address or the configured admin address.
- Server and audit logs (operator-only).
- The admin MCP server (its keys are admin-equivalent).
- Free-text frontmatter such as `author:` that an editor typed: it is shown as written (resolved only
  when it happens to be a login).
- Changing a login name.

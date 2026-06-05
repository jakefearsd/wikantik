# Blogs

Every authenticated Wikantik user can create a personal blog. Blog entries are
full Markdown documents, stored as ordinary wiki pages under the path
`blog/{username}/` and surfaced through a dedicated set of SPA routes.

## What a blog is

A blog is a subdirectory of real wiki pages owned by one user. It consists of:

- **Blog home page** — a wiki page at `blog/{username}/Blog` (the `Blog.md` file
  under `pageDir/blog/{username}/`). This is where you write an intro, list your
  recent posts, or embed any wiki plugins.
- **Entries** — individual pages at `blog/{username}/{EntryName}`. Entry names
  are derived from the topic title you supply when creating an entry: the title is
  converted to a CamelCase slug (via `titleToSlug`).

Blogs appear on the **Blog discovery** page (`/blog`) which lists all blogs
across the wiki. Each blog entry supports standard Markdown (GFM), math
notation, and file attachments — the same editor used for regular wiki pages.

## Creating a blog

1. Go to `/blog`. If you are logged in, a **Create My Blog** button appears in
   the top-right corner.
2. Click **Create My Blog** — this navigates to `/blog/create`.
3. Click **Create Blog**. The API call (`POST /api/blog`) creates the blog home
   page at `blog/{username}/Blog` and redirects you to your new blog home at
   `/blog/{username}/Blog`.

If you already have a blog the server returns 409 Conflict and an error message
is shown on the page.

You must be logged in to create a blog. Unauthenticated visitors who navigate
directly to `/blog/create` see a "You must be logged in" message.

## Writing a blog entry

From your blog home (`/blog/{username}/Blog`):

1. Click **New Entry** (visible only to the blog owner).
2. You are taken to `/blog/{username}/new`.
3. Enter a title in the large centred input. The title is converted to a
   CamelCase slug for the page name. At least one letter or digit is required.
4. Write the entry body in the left pane. The right pane shows a live Markdown
   preview.
5. Click **Create Entry**. The API call (`POST /api/blog/{username}/entries`) with
   body `{topic, content}` creates the page and redirects you to the new entry at
   `/blog/{username}/{EntryName}`.

The entry's frontmatter (title, date, synopsis, author) can be added in YAML at
the top of the body. The `date` field is displayed below the entry title. A
`synopsis` field, if present, is used as the excerpt on the blog home listing;
otherwise the first 200 characters of the body are used.

## Editing entries and the blog home

From the blog home or any entry page:

- Click **Edit Blog Page** (on the blog home) or **Edit Entry** (on an entry
  page). Both links lead to `/edit/blog/{username}/{pageName}`.
- The `BlogEditor` component provides a split-pane editor with a live Markdown
  preview, an optional change note field, and an **Attach** button to manage file
  attachments.
- Autosave: drafts are written to local storage 800 ms after you stop typing.
  If you reload or navigate away and return, the editor prompts to restore or
  discard the unsaved draft.
- Click **Save** to persist. The raw content is sent via `PUT
  /api/blog/{username}` (for the blog home) or `PUT
  /api/blog/{username}/entries/{name}` (for an entry). After saving you are
  redirected back to the reader view.

## Blog discovery (`/blog`)

The `/blog` route renders `BlogDiscovery`, which calls `GET /api/blog` to list
all blogs. Each card shows:

- The blog title (from the `title` frontmatter field of `Blog.md`), falling back
  to `{username}'s Blog`.
- The author's full name (if set in their profile).
- The blog description (from the `description` frontmatter field of `Blog.md`).
- The entry count.

Clicking a card navigates to `/blog/{username}/Blog`.

## Routes

| Route | Component | Description |
|-------|-----------|-------------|
| `/blog` | `BlogDiscovery` | Lists all blogs on the wiki |
| `/blog/create` | `CreateBlog` | Create your personal blog (requires auth) |
| `/blog/{username}/Blog` | `BlogHome` | Blog home page for a user |
| `/blog/{username}/new` | `NewBlogEntry` | Create a new entry (owner only) |
| `/blog/{username}/{entryName}` | `BlogEntry` | Read a single entry |
| `/edit/blog/{username}/{pageName}` | `BlogEditor` | Edit blog home or an entry |

## Permissions

| Action | Who can perform it |
|--------|-------------------|
| View any blog or entry | Anyone (same ACL as regular page view) |
| Create a blog | Any authenticated user (one blog per user) |
| Write a new entry | Blog owner only (the component blocks others) |
| Edit the blog home or an entry | Blog owner only (enforced by auth check in `BlogEditor`) |
| Delete an entry | Blog owner or any user with the Admin role |
| Delete an entire blog | Blog owner or any user with the Admin role |

All write operations require authentication (`requireAuthenticated` in
`BlogResource`). The API sends 401 for unauthenticated requests and 403 (via
`WikiException`) for ownership violations.

## Deleting entries and blogs

**Delete an entry** — on the entry reader page, click **Delete** (owner or admin
only). A confirmation modal appears. Confirming calls `DELETE
/api/blog/{username}/entries/{name}` and redirects back to the blog home.

**Delete a blog** — on the blog home, click **Delete Blog** (owner or admin
only). A confirmation modal shows a warning that the action cannot be undone and
deletes all entries. Confirming calls `DELETE /api/blog/{username}` and redirects
to `/blog`.

## REST/endpoint reference

All endpoints are under `/api/blog`. All mutating routes require authentication.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/blog` | List all blogs. Returns array of `{username, title, description, entryCount, authorFullName}`. |
| `POST` | `/api/blog` | Create blog for the current user. Returns 201 on success, 409 if already exists. |
| `GET` | `/api/blog/{username}` | Get blog metadata + home page content. Add `?render=true` for rendered HTML. |
| `PUT` | `/api/blog/{username}` | Update blog home page content. Body: `{content}`. |
| `DELETE` | `/api/blog/{username}` | Delete blog and all entries. Owner or admin only. |
| `GET` | `/api/blog/{username}/entries` | List all entries as `[{name, title, date, author, excerpt}]`. |
| `POST` | `/api/blog/{username}/entries` | Create an entry. Body: `{topic, content?}`. Returns 201. |
| `GET` | `/api/blog/{username}/entries/{name}` | Get entry content + metadata. Add `?render=true` for HTML. |
| `PUT` | `/api/blog/{username}/entries/{name}` | Update entry content. Body: `{content}`. |
| `DELETE` | `/api/blog/{username}/entries/{name}` | Delete an entry. Owner or admin only. |

Entry slugs are CamelCase names derived from the topic title. The `name` in list
and single-entry responses is the part after the last `/` in the internal wiki
page path.

## Personal Zone sidebar

The Personal Zone sidebar (`PersonalZone`) includes a **My blog** section that
lists your recent entries with direct links, and a **Blog home** link to
`/blog/{login}/Blog`. This section is visible only when logged in.

## Troubleshooting

**"Blog not found for user"** — the blog has not been created yet. Navigate to
`/blog/create` to set one up.

**Entry creation fails with "topic is required"** — the topic field is blank or
contains only non-alphanumeric characters. The title must contain at least one
letter or digit after slug conversion.

**409 Conflict on blog creation** — you already have a blog. Navigate directly to
`/blog/{yourUsername}/Blog`.

**Edit link takes me to a 404** — the blog home page (`Blog.md`) may have been
deleted or the username in the URL does not match the stored page path. Check
that `blog/{username}/Blog` exists in the page directory.

## Cross-links

- [docs/CommentsAndMentions.md](CommentsAndMentions.md) — adding comments and @-mentions to blog entries
- [docs/PersonalZone.md](PersonalZone.md) — the Personal Zone sidebar and profile settings
- [docs/PageOwnership.md](PageOwnership.md) — blog pages are owned by the blog author

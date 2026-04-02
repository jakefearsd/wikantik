# Blog Feature Design

## Context

Wikantik currently stores all wiki pages flat in a single directory with URL-encoded filenames.
Users have requested the ability to maintain personal blogs within the wiki. This design introduces
directory-based page storage for blogs, a blog lifecycle manager, composable blog plugins, and a
dedicated REST API — all built on top of the existing wiki page infrastructure so that blog entries
are full wiki pages with versioning, search, frontmatter, and permissions.

**Scope**: v1 — one blog per user, on-demand creation, YYYYMMDD-prefixed entries, three composable
plugins, and a dedicated REST API surface.

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Storage location | Subdirectories inside existing page directory | Blog pages are first-class wiki pages |
| Blogs per user (v1) | One | Simplicity; multi-blog deferred to v2 |
| Date format in filenames | YYYYMMDD (ISO) | Sorts chronologically by filename |
| Blog creation | On-demand explicit action | Avoids empty blog directories |
| Blog homepage | Editable Markdown with composable plugins | Owner controls layout; plugins provide dynamic content |
| Blog entries | Full wiki pages | Versioning, search, frontmatter, plugins all work |
| Entry creation | REST API enforces naming | Consistent YYYYMMDD prefix; correct directory placement |
| Blog API surface | Self-contained at /api/blog/ | Blog is a cohesive API; entry content also served from blog API |
| Blog deletion | Included in v1 | Owner or Admin can remove a blog and all entries |

---

## 1. Page Provider Extension

### Name Resolution

Page names starting with `blog/` map to real subdirectories instead of being URL-encoded flat:

| Page name | File path |
|-----------|-----------|
| `blog/jake/Blog` | `pageDir/blog/jake/Blog.md` |
| `blog/jake/20260402MyFirstPost` | `pageDir/blog/jake/20260402MyFirstPost.md` |
| `HomePage` | `pageDir/HomePage.md` (unchanged) |

### Changes to AbstractFileProvider

- **`mangleName(String pagename)`** — if name starts with `blog/`, preserve directory separators
  instead of encoding slashes. The username segment is lowercased. Non-blog pages unchanged.
- **`unmangleName(String filename)`** — inverse: reconstruct `blog/username/PageName` from
  directory-relative path.
- **`findPage(String page)`** — for blog pages, look in the subdirectory.
- **`getAllPages()`** — recurse into `blog/*/` subdirectories to discover blog pages.

### Changes to VersioningFileProvider

Version history for blog pages lives inside the blog directory:

```
pageDir/blog/jake/
├── Blog.md
├── 20260402MyFirstPost.md
├── 20260402MyFirstPost.properties
└── OLD/
    └── 20260402MyFirstPost/
        ├── 1.md
        ├── 2.md
        └── page.properties
```

The `OLD/` directory is per-blog-directory, not the top-level `OLD/`.

### What the provider does NOT do

- Does not create blog directories (that's BlogManager's job)
- If a blog page is saved and the directory doesn't exist, the save fails
- Non-blog pages are completely unaffected

---

## 2. BlogManager

New manager interface in `wikantik-api`, default implementation in `wikantik-main`.

### Interface

```
BlogManager
├── createBlog(Session session) → Page
├── deleteBlog(Session session, String username) → void
├── createEntry(Session session, String topicName) → Page
├── getBlog(String username) → Page | null
├── listEntries(String username) → List<Page>
├── blogExists(String username) → boolean
└── listBlogs() → List<BlogInfo>
```

### Behaviors

**`createBlog(Session)`**:
- Derives username from session, lowercases it
- Creates directory: `pageDir/blog/<username>/`
- If directory already exists → throws `BlogAlreadyExistsException`
- Seeds `Blog.md` from classpath template with `{username}` placeholder replaced
- Returns the Blog.md page

**`deleteBlog(Session, String username)`**:
- Validates session user matches username, or session user has Admin role
- Removes all entry pages (with version history)
- Removes the blog directory
- Fires wiki events for each deleted page

**`createEntry(Session, String topicName)`**:
- Validates user owns the blog
- Prepends today's date as YYYYMMDD to topicName
- Creates the entry file with default frontmatter (title, date, author)
- Returns the new entry page

**`listEntries(String username)`**:
- Returns all pages in the blog directory except Blog.md
- Sorted by date descending (newest first, parsed from YYYYMMDD prefix)

**`listBlogs()`**:
- Scans `pageDir/blog/*/Blog.md`
- Returns metadata for each blog (username, title from frontmatter, entry count)

**Directory creation** is atomic — `Files.createDirectory()` throws `FileAlreadyExistsException`,
wrapped as `BlogAlreadyExistsException`.

---

## 3. REST API — BlogResource

Self-contained API at `/api/blog/`. All blog operations go through this endpoint.

### Endpoints

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/api/blog` | Create current user's blog | Authenticated |
| `GET` | `/api/blog` | List all blogs | Authenticated |
| `GET` | `/api/blog/{username}` | Get blog metadata | Authenticated |
| `DELETE` | `/api/blog/{username}` | Delete blog | Owner or Admin |
| `POST` | `/api/blog/{username}/entries` | Create entry (`{"topic":"..."}`) | Owner |
| `GET` | `/api/blog/{username}/entries` | List entries (metadata only) | Authenticated |
| `GET` | `/api/blog/{username}/entries/{name}` | Get entry content + metadata | Authenticated |
| `PUT` | `/api/blog/{username}/entries/{name}` | Update entry content | Owner |
| `DELETE` | `/api/blog/{username}/entries/{name}` | Delete entry | Owner or Admin |

### Response Formats

**Blog metadata** (`GET /api/blog/{username}`):
```json
{
  "username": "jake",
  "title": "Jake's Blog",
  "description": "Welcome to my blog",
  "entryCount": 5,
  "latestEntry": "20260402MyFirstPost",
  "created": "2026-04-03T..."
}
```

**Entry list** (`GET /api/blog/{username}/entries`):
```json
[{
  "name": "20260402MyFirstPost",
  "title": "My First Post",
  "date": "2026-04-02",
  "author": "jake",
  "excerpt": "Today I started..."
}]
```

**Entry content** (`GET /api/blog/{username}/entries/{name}`):
```json
{
  "name": "20260402MyFirstPost",
  "content": "---\ntitle: My First Post\n...",
  "contentHtml": "<h1>My First Post</h1>...",
  "metadata": {"title": "My First Post", "date": "2026-04-02"},
  "version": 3,
  "author": "jake",
  "lastModified": "2026-04-02T..."
}
```

---

## 4. Plugins

Three plugins in `com.wikantik.plugin`, all extending `AbstractReferralPlugin`.

### BlogListing — Discover blogs across the wiki

**Usage**: `[{BlogListing}]` on any page (Main, sidebar, dedicated discovery page).

**Parameters**:
- `include` / `exclude` — regex on username
- `count` — max blogs to show (default: all)
- `sortOrder` — alphabetical, newest first

**Behavior**: Calls `BlogManager.listBlogs()`, filters, renders as a list of links with blog
title and owner name.

### LatestArticle — Show newest blog entry

**Usage**: `[{LatestArticle}]` on Blog.md (or any page with `user` param).

**Parameters**:
- `user` — blog owner username (defaults to current page's blog context)
- `excerpt` — true/false (default: true)
- `excerptLength` — characters (default: 200)

**Behavior**: Calls `BlogManager.listEntries(username)`, takes the first (newest) entry,
renders its content or excerpt through the wiki rendering pipeline.

### ArticleListing — List blog entries

**Usage**: `[{ArticleListing}]` on Blog.md.

**Parameters**:
- `user` — blog owner username (defaults to current page's blog context)
- `count` — max entries (default: 10)
- `excerpt` — true/false (default: true)
- `excerptLength` — characters (default: 200)
- `sortOrder` — date descending (default) or ascending

**Behavior**: Calls `BlogManager.listEntries(username)`, renders as a list with date, title
(from frontmatter), and optional excerpt.

---

## 5. Default Blog.md Template

Stored as classpath resource: `com/wikantik/blog/BlogTemplate.md`

```markdown
---
title: "{username}'s Blog"
description: "Welcome to my blog"
---

# Welcome to my blog

[{LatestArticle excerpt=true}]

## All Posts

[{ArticleListing count=10 excerpt=true}]
```

The `{username}` placeholder is replaced at blog creation time. After creation, the owner can
edit Blog.md freely — rearrange plugins, add prose, remove sections, etc.

### Default Entry Frontmatter

When `BlogManager.createEntry()` seeds a new entry:

```markdown
---
title: "<TopicName with spaces inserted before capitals>"
date: <YYYY-MM-DD>
author: <username>
---

```

---

## 6. SPA Routing & Frontend

### Server-side routing

- Add `/blog/` to `SpaRoutingFilter.SPA_PREFIXES` so browser navigation forwards to React SPA
- The blog REST API at `/api/blog/` passes through the filter (JSON Accept header)

### React Router

New routes:
- `/blog` — blog discovery (lists all blogs via BlogListing or a React component)
- `/blog/:username` — redirect to `/blog/:username/Blog`
- `/blog/:username/Blog` — blog homepage (renders Blog.md)
- `/blog/:username/:entryName` — individual blog entry

### React Components

- **BlogDiscovery** — lists all blogs, links to each
- **BlogHome** — renders Blog.md (which contains plugins for LatestArticle, ArticleListing)
- **BlogEntry** — renders individual entry page
- **CreateBlog** — form/button to create user's blog (calls `POST /api/blog`)
- **NewBlogEntry** — form for topic name, calls `POST /api/blog/{username}/entries`

---

## 7. Permissions

Blog permissions build on the existing wiki permission model:

- **Blog creation**: Any authenticated user can create their own blog
- **Entry creation/editing**: Only the blog owner can create and edit entries in their blog
- **Viewing**: All authenticated users can view all blogs and entries
- **Deletion**: Blog owner can delete their own blog/entries; Admins can delete any blog
- **Blog.md editing**: Only the blog owner can edit their Blog.md homepage

Permission checks are enforced in `BlogResource` by comparing the session user against the
blog's username. Admin role overrides for deletion.

Page-level ACLs (`[{ALLOW edit username}]`) can be used in individual blog entries for
finer-grained control if needed, but are not part of the default template.

---

## 8. Testing Strategy

### Unit Tests

- **BlogManager**: create/delete blog, create entry, naming validation, duplicate directory
  detection, entry listing and sort order
- **Provider extension**: name mangling for blog paths, file resolution, getAllPages including
  blog subdirectories, versioning within blog directories
- **Plugins**: BlogListing, LatestArticle, ArticleListing with mock PageManager/BlogManager
- **BlogResource**: endpoint routing, permission checks, request/response serialization

### Integration Tests

- End-to-end blog lifecycle: create blog → create entries → list entries → view entry → delete
- Permission enforcement: non-owner cannot create entries or delete blog
- SPA routing: browser navigation to `/blog/jake/Blog` serves React app
- Plugin rendering: Blog.md with plugins renders correctly

---

## Files to Create or Modify

### New Files

| File | Module | Purpose |
|------|--------|---------|
| `BlogManager.java` | wikantik-api | Manager interface |
| `BlogInfo.java` | wikantik-api | Blog metadata record |
| `BlogAlreadyExistsException.java` | wikantik-api | Exception for duplicate blog |
| `DefaultBlogManager.java` | wikantik-main | Manager implementation |
| `BlogListing.java` | wikantik-main | Blog discovery plugin |
| `LatestArticle.java` | wikantik-main | Latest entry plugin |
| `ArticleListing.java` | wikantik-main | Entry listing plugin |
| `BlogTemplate.md` | wikantik-main/resources | Default Blog.md template |
| `BlogResource.java` | wikantik-rest | REST API endpoints |
| `BlogManagerTest.java` | wikantik-main/test | Unit tests |
| `BlogPluginTest.java` | wikantik-main/test | Plugin unit tests |
| `BlogResourceTest.java` | wikantik-rest/test | REST API tests |

### Modified Files

| File | Module | Change |
|------|--------|--------|
| `AbstractFileProvider.java` | wikantik-main | Blog-aware name mangling and file resolution |
| `VersioningFileProvider.java` | wikantik-main | Blog-directory-local OLD/ versioning |
| `SpaRoutingFilter.java` | wikantik-rest | Add `/blog/` to SPA prefixes |
| `wikantik_module.xml` | wikantik-main | Register new plugins |
| React router config | wikantik-war | Add blog routes |
| `web.xml` | wikantik-war | Register BlogResource servlet |

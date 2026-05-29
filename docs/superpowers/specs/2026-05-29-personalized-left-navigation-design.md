# Personalized Left Navigation — Design

**Date:** 2026-05-29
**Status:** Approved, pending implementation plan
**Subsystem:** React frontend (`wikantik-frontend`) + one new REST resource (`wikantik-rest`)

## Problem

The left sidebar (`wikantik-frontend/src/components/Sidebar.jsx`, ~221 lines)
treats logged-in and anonymous users almost identically. A logged-in user gets
only a "+ New Article" button and a "My mentions" link bolted onto otherwise
wiki-wide navigation. There is no clear, grouped access to the things that are
*about the user* — their profile, their mentions, the pages they own, what they
were just reading, their blog, and (today, not at all) any in-progress edits.

Goal: give logged-in users a clearly-separated, personalized zone at the top of
the sidebar that follows sound UX principles, without taxing the anonymous
reader hot path.

## UX principles

- **Grouping by proximity** — the personal content lives in one visually
  distinct block at the top of the sidebar (subtle background tint, a `ME`
  label, and a divider separating it from wiki-wide nav).
- **Progressive disclosure** — collapsible sections each show the 3 most-recent
  items inline with an expand-to-more affordance; collapse state is persisted.
- **Recognition over recall** — every row carries an icon + label (emoji icon
  convention, expected to spread to the rest of the app over time).
- **No overload / graceful empty states** — every section renders a friendly
  empty state rather than disappearing silently or showing a broken list.
- **Don't tax the reader path** — personal data is fetched only for
  authenticated users, lazily after mount. Anonymous and hot-path reader
  renders are unchanged.
- **Accessibility** — collapsible section headers are real `<button>`s with
  `aria-expanded`, fully keyboard-operable, with `focus-visible` styling.

## The "me" zone (top of sidebar, authenticated users only)

Rendered above the existing wiki-wide navigation, inside a new `PersonalZone`
component. Order:

1. **Identity header** — avatar initials, display name, role, `Profile`
   (→ `/preferences`) and `Sign out`.
2. **+ New Article** — existing button, moved into the zone.
3. **🔔 My mentions** — existing link with unread badge
   (`useUnreadMentions`, already built).
4. **📄 My pages** *(collapsible)* — 3 most-recent owned pages inline; expand to
   ~15; count in header.
5. **🕘 Recently viewed** *(collapsible)* — 3 inline; expand to ~15.
6. **✍️ My blog** — header links to blog home; 3 recent entries inline; a
   "New entry" affordance.
7. **📝 Drafts / Resume editing** *(collapsible)* — pages with unsaved local
   edits, each linking to `/edit/:name`, with a per-item discard control.
   Hidden entirely when there are no drafts.

Below a divider: the **existing** wiki-wide navigation (Main, About, News,
Recent Changes, Wiki Tools, Recently Modified, Admin, Clusters) — unchanged.

### "View all" behavior

"View all" **expands inline** (show up to ~15 items) rather than navigating to
new `/me/*` history routes. This keeps scope down and avoids new routes/web.xml
registration. The blog section is the exception: its header links to the
existing blog home (`/blog/{login}/Blog`).

## Components & architecture

- **`PersonalZone`** — new component extracted from `Sidebar.jsx`. `Sidebar`
  renders `<PersonalZone>` when authenticated, then the existing wiki nav. This
  also relieves `Sidebar.jsx`, which is already doing too much.
- **`CollapsibleSection`** — reusable: header button with `aria-expanded`, body,
  and open/closed state persisted in `localStorage`.
- Hooks:
  - existing `useUnreadMentions`
  - new `useMyPages` — calls the new `/api/me/pages` endpoint
  - new `useRecentlyViewed` — reads/writes the recently-viewed ring buffer
  - new `useMyBlog` — wraps `api.blog.listEntries(login)`
  - new `useDrafts` — enumerates local draft keys
  - new `useDraft(pageKey)` — editor-side autosave (see below)

## Data sources

| Section | Source | New backend? |
|---|---|---|
| Mentions | `GET /api/me/mentions/unread-count` | none (exists) |
| My pages | **new** `GET /api/me/pages?limit=` → `PageOwnerService.listByOwner(login, …)` | new REST resource only; **no DB migration** (table `page_owners` already exists) |
| Recently viewed | `localStorage`, namespaced `wikantik.recent.<login>` (ring buffer ~20); recorded in `PageView` on mount | none |
| My blog | `GET /api/blog/{login}/entries` (`api.blog.listEntries`) | none (exists) |
| Drafts | `localStorage` keys `wikantik.draft.<login>.<page>` | none |

### localStorage namespacing

Recently-viewed and draft keys are **namespaced by the user's login** so a
shared browser does not leak one user's recents/drafts to another. Identity for
ownership match and blog path uses the user's **login principal**
(`page_owners.owner_login`, blog routes are `/blog/:username`).

## Autosave (new editor capability)

There is currently **no** autosave anywhere in the frontend (verified: only
`localStorage` users are dark-mode and the SSO cache). This design adds it.

- **`useDraft(pageKey)`** — used by `PageEditor` and `BlogEditor`. Debounced
  write of `{content, changeNote, title, savedAt}` to
  `wikantik.draft.<login>.<page>`; cleared on a successful save.
- **Restore-on-return** — when an editor mounts and a newer draft exists than
  the loaded version, show a **non-destructive banner**: *"Unsaved changes from
  {time} — Restore / Discard."* Restore loads the draft into the editor;
  Discard removes the key.
- **`useDrafts()`** — enumerates `wikantik.draft.<login>.*` keys to populate the
  me-zone Drafts section.

## Backend endpoint

`GET /api/me/pages?limit=15`

- New `MyPagesResource`, registered alongside `MyMentionsResource`
  (`wikantik-rest`, `/api/me/*`).
- Authenticated-only; resolves the current login from the session.
- Returns `[{canonicalId, title, slug, updatedAt}]` sorted by recency.
- Backed by the existing `PageOwnerService.listByOwner(login, limit, 0)`.
- **No schema change / no migration.**

## Testing

- **Backend**
  - `MyPagesResource` unit test (Mockito), mirroring `MyMentionsResource`.
  - Wire-level IT for `/api/me/pages`: requires auth; returns only the caller's
    owned pages.
- **Frontend (vitest)**
  - `useRecentlyViewed` — ring-buffer cap + login namespacing.
  - `useDraft` — autosave (debounced), restore, clear-on-save.
  - `useDrafts` — enumeration of namespaced keys.
  - `CollapsibleSection` — `aria-expanded` toggling + persistence.
  - `PersonalZone` — render, empty states, unauthenticated hiding,
    mentions badge.

## Out of scope

- Server-side blog drafts (publish/unpublish lifecycle) — explicitly deferred;
  drafts here are client-side autosaves only.
- Bookmarks / favorites — not requested.
- New `/me/*` history routes — replaced by inline expand.
- Tabbed or popover sidebar structures — rejected in favor of the top "me" zone.

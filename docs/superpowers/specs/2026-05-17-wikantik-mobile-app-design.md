# Wikantik Mobile App — Design (v1)

**Date:** 2026-05-17
**Status:** Approved design — ready for implementation planning
**Scope:** v1 = a polished, end-user-facing **reader** app for Android (iOS to follow), published to the Google Play Store. Admin features are explicitly excluded — the app never implements them.

## 1. Purpose and v1 scope

Wikantik currently has a React SPA web frontend and a REST API. This project adds a
native mobile app so readers can browse the wiki on the go.

The long-term goal is full non-admin parity (reading, editing, comments, attachments).
**v1 deliberately narrows to reading** — the lowest-risk slice and a sensible first
mobile project. Editing and comments are planned later phases (§7).

**In scope for v1:**

- Browse the wiki starting from the generated Main hub page.
- Full-text / hybrid **search**.
- Read any page with a beautiful, native reading experience.
- Follow wikilinks between pages.
- Anonymous reading of public pages; **optional** login that unlocks ACL-protected
  pages and lays groundwork for later editing.
- On-this-page heading outline; share / open-in-browser.
- Light/dark theme.

**Out of scope for v1** (see §7 roadmap): editing, comments, attachments upload,
recent-changes feed, browse-by-cluster/tag, backlinks footer, verification badge,
offline caching, push notifications, configurable server URL, admin surfaces.

## 2. Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Build approach | **React Native + Expo** (managed workflow, TypeScript) | Reuses the team's React skills; one codebase for Android + iOS; genuine native UI; excludes admin surface by simply not implementing it; Expo lowers the barrier for a mobile newcomer (EAS Build/Submit, OTA updates). |
| v1 priority | **Reading on the go** | Lowest risk, fastest to a store-quality release. |
| Offline | **Online-only** | No offline feature in v1; caching deferred to Phase 3. |
| Server scope | **Hardwired to one wiki**, designed so a server picker can be added later without rework | Cleanest storefront UX now; keep server URL in one config module. |
| Auth | **Anonymous read, optional login** | Public pages need no account; login is offered, not required. |
| Discovery | **Search + Home/hub page** | Recent-changes and browse-by-cluster deferred. |
| Navigation | **Content-first stack** | Home is the root; search in the header; Account behind an avatar. Minimal, reading-focused. |
| Content rendering | **Hybrid** | Native Markdown render for prose; server-rendered HTML fragments for plugin/math blocks. |

## 3. Architecture

React Native via Expo (managed workflow), TypeScript. Expo provides EAS Build/Submit
for store distribution and over-the-air updates for non-native fixes.

The app is a **pure client of the existing public REST surface**. It touches no
`/admin/*` endpoint. v1 requires **essentially zero backend work**.

Endpoints consumed:

- `GET /api/search` — discovery / search results.
- `GET /wiki/{slug}?format=md` — raw Markdown page body for native rendering.
- `GET /wiki/{slug}?format=json` — page metadata (title, slug, updated time).
- `POST /api/auth/login`, `GET /api/auth/user`, `POST /api/auth/logout` — optional
  login. v1 holds the returned **session cookie** in `expo-secure-store` and replays
  it on requests. (Token-based auth is a clean Phase 2 backend addition once the app
  starts writing — not needed for a read-only v1.)

Module structure:

- `config/` — the hardwired server base URL (single source of truth; the future
  server picker replaces only this module).
- `api/` — typed client, one function per REST call; tested against mocked `fetch`.
- `render/` — hybrid page renderer (§5).
- `screens/` — Home, Search, Page, Settings.
- `navigation/` — content-first stack navigator + the wikilink tap handler.
- `theme/` — typography, colors, light/dark.

## 4. Navigation model — content-first stack

- **Home is the root** of a stack navigator.
- A **search bar lives in the Home header**; tapping it pushes the Search screen.
- An **avatar/account icon** in the header opens Settings/Account.
- The **Page reader** pushes onto the stack; back returns to the previous page,
  giving natural wikilink-trail navigation.

No bottom tab bar. Rationale: a reader app benefits from a minimal, content-first
chrome; the three destinations (Home, Search, Account) are reachable from the Home
header without permanent tab real estate.

## 5. Screens

### 5.1 Home

Opens to the **generated Main hub page**: cluster chips and curated pins. Header
contains the app title, the search bar, and the account avatar. Tapping a cluster
chip or pin pushes a Page reader.

### 5.2 Search

Full-screen screen pushed from the Home header search bar. Query field, results list
(title + snippet), tap a result to open the Page reader. Backed by `/api/search`.

### 5.3 Page reader (centerpiece)

**Fixed core:**

- Header: back control, page title, overflow menu.
- Hybrid-rendered body (§5.5).
- Tappable wikilinks — push a new Page reader for the target page.
- Native text selection.
- Pull-to-refresh.

**v1 optional extras (selected):**

- **On-this-page outline** — a collapsible heading list for long pages; tapping a
  heading scrolls to that section.
- **Share / open in browser** — overflow menu offering: share a deep link to the
  page, or open the full page in the device browser.

**Deferred to roadmap** (not in v1): freshness/verification badge, "linked from"
backlinks footer.

### 5.4 Settings / Account

Light/dark theme toggle, login/logout, About + app version.

### 5.5 Hybrid content rendering

- Fetch raw Markdown via `?format=md`.
- Render prose into native components — native typography, theming, tap-to-follow
  wikilinks, native text selection.
- Detect wiki-plugin (`[{Plugin}]`) and math blocks; render **those blocks** from
  server-rendered HTML fragments (small inline WebView or fetched fragment) so
  plugin/math-heavy pages never break.
- The bulk of reading is fully native and beautiful; correctness is preserved on
  every page.

## 6. Error handling

- Network/HTTP failures surface a non-blocking, retryable error state on the
  affected screen (never a blank screen, never a silent failure).
- A page that 404s or is ACL-denied shows an explicit message; ACL-denied pages
  prompt the user to log in.
- Login failures show a clear, specific message; credentials are never logged.
- Plugin/math fragment failures degrade to a visible "couldn't render this block"
  notice rather than failing the whole page.

## 7. Roadmap beyond v1

Each phase is a separate spec → plan → implementation cycle.

- **Phase 2 — Contribution.** Token-based auth (the one real backend addition);
  reading and posting comments; page editing with a mobile Markdown editor.
- **Phase 3 — Depth.** Recent-changes feed; browse-by-cluster/tag; "linked from"
  backlinks; verification/freshness badge; offline caching (recent + pinned pages);
  push notifications for watched pages; configurable server URL.

## 8. Testing and release

**Testing:**

- Jest + React Native Testing Library for component behavior.
- API client tested against mocked `fetch`.
- Manual device testing through Expo Go during development.

**Release (Android first):**

- EAS Build + EAS Submit.
- Google Play Console developer account ($25 one-time).
- App icon, screenshots, store listing.
- A **privacy policy is mandatory** — the app handles login credentials.
- Ship to a Play **internal-testing track** before production.
- **Android App Links** so shared `/wiki/{slug}` URLs deep-link into the app.
- iOS later: Apple Developer account ($99/yr), EAS Submit to App Store Connect.

## 9. Open items for the implementation plan

- Exact shape of `/api/search` and `?format=json` responses — confirm fields the
  app needs (title, slug, snippet, updated time) before finalizing the `api/` types.
- Confirm cookie-based auth replays correctly from a React Native client against
  `/api/auth/*` (cookie jar behavior); if not, Phase 2 token auth moves earlier.
- Choice of React Native Markdown rendering library.

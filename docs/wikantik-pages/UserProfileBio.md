---
title: User Profile Bio
date: '2026-05-04'
cluster: wikantik-development
status: active
verified_at: '2026-05-04T21:10:44.598011331Z'
type: article
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD4FHACTTQ9QSQ699X37ZM
summary: 'Optional plain-text user bio (max 1000 characters): where it is stored,
  how users and admins edit it, and which endpoints carry it.'
tags:
- user-profile
- feature
- wikantik-development
---
# User Profile Bio

Introduced on **April 3, 2026**, the User Profile Bio is an optional free-text self-description stored on a user's profile.

## Behavior

- **Plain text, at most 1000 characters.** The server rejects a longer value with HTTP 400 ("Bio must be 1000 characters or fewer"). The text is stored and returned as typed; it is not parsed as Markdown.
- **Users edit their own bio** on the **Preferences** page (`/preferences`): a "Bio" text area with a live `n / 1000` counter, saved together with full name and email.
- **Administrators** can set or change any user's bio in the create/edit dialog on **Admin → Users** (`/admin/users`).
- **Visibility today:** only the account owner and administrators see a bio. The reader UI does not display it: there is no public profile page, no author hover-card and no Markdown rendering of bios.

## Implementation Detail

### Storage
The bio is the `bio` column of the `users` table in PostgreSQL, declared `VARCHAR(1000)` in `bin/db/migrations/V002__core_users_groups.sql`. In Java it is `UserProfile.getBio()` / `setBio()` (`DefaultUserProfile`).

### API Access
- `GET /api/auth/profile`: returns the signed-in user's own profile, including `bio` (`AuthResource`).
- `PUT /api/auth/profile`: updates the caller's own `fullName`, `email` and `bio` (and, when requested, the password).
- `/admin/users` and `/admin/users/{login}` accept and return `bio` for administrators (`AdminUserResource`).

No other endpoint returns a user's bio to anyone but its owner or an administrator.

### UI Integration
`UserPreferencesPage.jsx` renders the owner's form and `admin/UserFormModal.jsx` the administrator's, both in the React SPA (`wikantik-frontend`).

## See Also
- [Relational User Database](RelationalUserDatabase) — The underlying PostgreSQL schema for users and groups.
- [Wikantik Development](WikantikDevelopment) — The feature timeline for the platform.

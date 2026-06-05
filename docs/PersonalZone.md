# Personal Zone and Preferences

The **Personal Zone** is the authenticated-user sidebar panel that surfaces quick
links to your pages, blog, drafts, recently-viewed pages, and your mention
notification count. The **Preferences** page (`/preferences`) lets you edit your
profile and change your password.

## Personal Zone sidebar

The Personal Zone (`PersonalZone` component) is rendered in the sidebar for every
authenticated user. It is not shown to anonymous visitors.

### What it shows

**Identity block** — your initials avatar, your username as a link to
`/preferences`, your role (Admin prefix if applicable), links to **Profile** and
**Sign out**.

**+ New Article** — a prominent button that opens the new-page composer.

**Me collapsible section** — collapsed by default. A notification badge on the
section header shows the count of unread @-mentions; this badge is visible even
when the section is collapsed. Inside:

- **My mentions** — link to `/me/mentions` with a badge of the unread count.
  Fetched by `useUnreadMentions`; the count refreshes on mount and whenever the
  browser tab returns to the foreground.
- **My pages** — pages owned by you. Shows up to 3 inline; a "View all N" button
  expands to 15.
- **Recently viewed** — pages you have opened recently.
- **My blog** — a link to your blog home (`/blog/{login}/Blog`) and up to 3
  recent entries. Expands to 15.
- **Resume editing** — shown only when you have local autosave drafts. Each
  draft row links to the editor for that page and has a discard (✕) button.

All subsections are individually collapsible. State is tracked per-section by id
(`me-zone`, `my-pages`, `recent`, `my-blog`, `drafts`).

## Preferences page (`/preferences`)

Route: `/preferences` — rendered by `UserPreferencesPage`.

Requires authentication. Unauthenticated users are redirected to `/wiki/Main`.

### Profile information

The top fieldset shows the following fields:

| Field | Editable | Notes |
|-------|----------|-------|
| Login Name | Read-only | Your account's login identifier. Cannot be changed by self-service. |
| Wiki Name | Read-only | CamelCase wiki identifier derived from your full name at registration. |
| Full Name | Editable | Displayed in your profile, blog author lines, and search results. |
| Email | Editable | Used for password reset. |
| Bio | Editable | Up to 1000 characters. Shown on your user profile page. |

Click **Save Changes** to submit. The form calls `PUT /api/auth/profile` with
body `{fullName, email, bio}`. On success the page shows "Profile updated
successfully".

### Changing your password

The **Change Password** fieldset is shown for all users. Leave all three fields
blank to save the profile without changing the password.

To change your password:
1. Enter your **Current Password**.
2. Enter and confirm your **New Password**.
3. Click **Save Changes**.

The server verifies the current password before accepting the new one. The new
password is validated against the NIST 800-63B rules configured for the wiki
(length, common-password blocklist). If validation fails, the error message lists
what the new password must satisfy.

**SSO-provisioned accounts** — accounts created via Google OIDC or another SSO
provider and never given a local password do not have a current password to
verify. If you sign in exclusively through SSO and attempt to set a new password
without a valid current password, the server will reject the request with a 403.
SSO users who want a local password should contact an administrator.

The password reset flow (`/reset-password`) provides a separate path for
forgotten passwords: enter your email address and the server generates a new
random password and emails it to you. This route is accessible without being
logged in.

### Account deletion (Danger Zone)

At the bottom of the preferences page, below a red horizontal rule, is the
**Danger Zone** section.

1. Click **Delete my account** to reveal the confirmation form.
2. Type your exact login name in the confirmation field.
3. Click **Permanently delete my account** (the button activates only when the
   typed name matches your login name exactly).

Account deletion:
- Is permanent and cannot be undone.
- Calls `DELETE /api/auth/profile` with body `{confirmLoginName}`.
- Removes your user record from the database.
- Revokes all your API keys.
- Removes you from all wiki groups.
- Logs you out and redirects to `/wiki/Main`.
- Does **not** remove your past page contributions — those remain attributed to
  your username.

**Admins cannot self-delete.** If you hold the Admin role the button is accepted
but the server will reject the request. You must have another administrator remove
your account, or relinquish the Admin role first.

## REST/endpoint reference

Profile operations are handled by `AuthResource` at `/api/auth/*`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/auth/profile` | Returns `{loginName, fullName, email, bio, wikiName, created, lastModified}`. Requires authentication. |
| `PUT` | `/api/auth/profile` | Update profile fields and/or password. Body: `{fullName?, email?, bio?, currentPassword?, newPassword?}`. Requires authentication. |
| `DELETE` | `/api/auth/profile` | Delete account. Body: `{confirmLoginName}`. Requires authentication. Admin accounts cannot self-delete. |
| `POST` | `/api/auth/reset-password` | Send a new random password to the email address. Body: `{email}`. Unauthenticated. Rate-limited to 3 requests per email per hour. |

### Mention inbox API

See [docs/CommentsAndMentions.md](CommentsAndMentions.md) for the full mention
API; the relevant count endpoint is:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/me/mentions/unread-count` | Returns `{count: N}`. Requires authentication. |

## Password reset (`/reset-password`)

The route `/reset-password` renders `ResetPasswordPage` and is accessible without
being logged in (useful when you cannot remember your password).

1. Enter your email address.
2. Click **Send New Password**.
3. If an account with that email exists, a new random password is sent to the
   address and saved on the account. Sign in with that password and then change
   it immediately at `/preferences`.

The response is always the generic "Check your email" confirmation — the page
never reveals whether a given email address exists in the system (enumeration
prevention). The endpoint is rate-limited to 3 requests per email per hour.

SSO users typically do not have an email address registered under a local account;
the reset will have no effect if the email does not match a local record.

## Troubleshooting

**Preferences page redirects immediately** — you are not logged in. Sign in at
`/login` first.

**"Current password is incorrect"** — the password you typed in the Current
Password field does not match the stored password. If you have forgotten it, log
out and use `/reset-password`.

**"Bio must be 1000 characters or fewer"** — trim your bio to at most 1000
characters.

**Password reset email never arrives** — check your spam folder. If the email
address is not associated with a local account (e.g. you always sign in via SSO),
no email is sent; contact an administrator.

**Delete button stays disabled** — the confirmation field must contain your login
name (case-sensitive exact match).

**Admin cannot delete their own account** — this is intentional. Have another
administrator use the admin user management panel at `/admin/users` to remove
the account.

## Cross-links

- [docs/CommentsAndMentions.md](CommentsAndMentions.md) — @-mention inbox reachable from the Personal Zone
- [docs/Blog.md](Blog.md) — personal blog surfaced in the Personal Zone sidebar
- [docs/PageOwnership.md](PageOwnership.md) — page ownership tied to the author profile
- [docs/ApiKeys.md](ApiKeys.md) — API keys revoked on account deletion
- [docs/ScimProvisioning.md](ScimProvisioning.md) — IdP-driven account lifecycle management
- [docs/SingleSignOn.md](SingleSignOn.md) — SSO accounts and the local password interaction

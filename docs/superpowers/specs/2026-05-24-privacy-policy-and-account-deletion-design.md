# Privacy Policy Page + Self-Service Account Deletion — Design

**Date:** 2026-05-24
**Status:** Approved, pre-implementation
**Subsystems:** Static web assets (`wikantik-war`), self-profile API (`wikantik-rest` `AuthResource`), auth/user store (`wikantik-main`), frontend (`UserPreferencesPage`)

## Problem & motivation

Jacob Fear is applying for Google and Facebook social-login (SSO) for the
Wikantik wiki. Both require a **publicly reachable privacy-policy URL**. The wiki
has none. Separately, the policy will state that users can delete their data —
but today account deletion is **admin-only** (`AdminUserResource`); there is no
self-service path. This effort delivers both, coupled because the policy
documents the deletion behavior.

## Goals

1. A static, public `privacy-policy.html` served by the app at a stable URL.
2. Self-service account deletion: an authenticated user can delete **their own**
   account; their past page contributions remain attributed in page history.
3. A privacy policy whose text accurately reflects the shipped behavior.

## Non-goals

- Deleting another user's account via the self-service path (that stays
  admin-only in `AdminUserResource`).
- Scrubbing a deleted user's prior page edits / inline ACL mentions (they remain
  by design — page authorship is a string in the page provider, no FK to users).
- A "tombstone" that prevents SSO re-provisioning (explicitly rejected — see
  below).
- GDPR/CCPA-specific legal machinery; the policy is US-targeted but generally
  open, with a plain access/deletion-on-request-or-self-service statement.

## Decisions (from brainstorming)

- **SSO re-provisioning:** accepted. Deletion removes the account now; if the
  user signs in again via Google/Facebook, auto-provisioning creates a fresh
  account. The policy states this plainly. No tombstone state.
- **Confirmation UX:** the user must type their own login name to confirm. Works
  for SSO users (no local password) and password users alike.
- **Last-admin safeguard:** self-deletion is refused if the caller holds the
  Admin role and is the only remaining admin, to prevent an unrecoverable admin
  lockout. The refusal explains why.

## Part A — Privacy policy static page

### Mechanism

A self-contained static `wikantik-war/src/main/webapp/privacy-policy.html`,
served by Tomcat's default servlet exactly like the existing `robots.txt` and
`error/Forbidden.html`. Verified properties of this path:

- Publicly reachable at `/<context>/privacy-policy.html`; none of the auth
  filters apply (`BasicAuthFilter`/`AdminAuthFilter` are mapped only to
  `/api/*` and `/admin/*`).
- `SpaRoutingFilter` is mapped to specific route patterns, not arbitrary
  `.html`, so it does not intercept the file.
- The whole-site filters it does pass through (`RequestCorrelationFilter`,
  `BackpressureFilter`, `CacheHeaderFilter`, `COEPFilter`) are non-blocking.

No external assets (no CDN fonts/scripts) so the page renders standalone and
contains no third-party requests.

### Content (grounded in verified data practices)

Plain semantic HTML, sections:

1. **Who we are** — Wikantik, operated by Jacob Fear. Contact:
   `jakefear@gmail.com`. Effective date: 2026-05-24.
2. **What we collect** — via Google/Facebook sign-in: your account identifier,
   name, and email; stored as a user profile (login name, full name, email).
   Essential cookies only: a session cookie (`JSESSIONID`) and an optional
   "remember me" authentication cookie. No tracking or advertising cookies.
3. **How we use it** — to authenticate you and create/maintain your wiki
   account. Pages you edit are attributed to your username.
4. **What we don't do** — no selling or sharing of personal data; no third-party
   analytics or advertising.
5. **Data sharing** — none beyond the identity provider you chose to sign in
   with.
6. **Retention & deletion** — you can delete your account yourself from your
   user preferences; deletion removes your profile, group memberships, and any
   API keys you own, and signs you out. Your past page contributions remain in
   the wiki's page history attributed to your username. If you sign in again via
   SSO, a new account is created. You may also email `jakefear@gmail.com` for
   help.
7. **Children**, **Changes to this policy**, **Contact**.

## Part B — Self-service account deletion

### API

`DELETE /api/auth/profile` (new action on the existing `AuthResource`, which
already serves `GET`/`PUT /api/auth/profile`).

- **Authentication:** requires an authenticated session. The account deleted is
  **always** the session's own principal — the login name is read from the
  authenticated `WikiSession`/principal, never from a client-supplied target.
- **Confirmation:** the request body carries `{ "confirmLoginName": "<name>" }`.
  The server deletes only if `confirmLoginName` exactly equals the authenticated
  principal's login name; otherwise `400` with a clear message. This makes the
  destructive intent explicit and prevents a stray call from deleting an
  account.
- **Last-admin guard:** if the caller holds the Admin role and is the only
  remaining admin, return `409 Conflict` with a message explaining an admin
  cannot delete the last admin account. (The exact admin-enumeration mechanism —
  roles table vs. authorizer `isUserInRole` over all users — is located during
  planning; the behavior is: refuse when no other admin exists.)
- **On success:** `200`/`204`. Then the session is invalidated (the user is
  logged out) and the "remember me" cookie cleared.

### Deletion scope (what the server removes)

In one server-side operation, in this order, each step logged:

1. Revoke API keys owned by the user (`api_keys` rows for that login name).
2. Remove the user from all groups (`group_members`).
3. Delete the user profile (`getUserDatabase().deleteByLoginName(loginName)`).
4. Invalidate the HTTP session and clear the auth cookie.

**Left intact (by design):** page edits/version authorship (a string in the page
provider, no FK) and any inline page ACL mentions of the username. Role-based
`policy_grants` are per-role, not per-user, so they are untouched.

Each removal step catches and logs failures with context (per the repo's
no-silent-failure rule) and continues; the profile deletion (step 3) is the
authoritative one — if it fails, the endpoint returns `500` and reports the
error rather than a false success.

### Frontend

A "Delete account" section at the bottom of `UserPreferencesPage`, visually
separated (danger styling). Clicking "Delete my account" opens a confirmation
dialog that requires typing the exact login name to enable the destructive
button. On confirm, it calls a new `api.deleteAccount(confirmLoginName)` →
`DELETE /api/auth/profile`; on success it clears client auth state and redirects
to the home page (now anonymous). Server errors (e.g. last-admin `409`) surface
in the dialog.

## Testing

Per repo convention, this is a write-surface security change to `/api/*`, so it
ships with both unit and wire-level integration coverage.

- **Unit/component (`wikantik-rest` / `wikantik-main`):** `AuthResource`
  self-delete — happy path deletes own profile + removes group memberships +
  revokes owned API keys; `confirmLoginName` mismatch → 400 and **no** deletion;
  last-admin → 409 and no deletion; a non-admin / non-last admin deletes
  successfully; deletion never targets a login name other than the session
  principal even if the body names someone else.
- **Integration (REST IT module):** an authenticated user calls
  `DELETE /api/auth/profile`, the profile is gone (`GET` afterward fails / 401),
  and the session is invalidated; plus a public-reachability check that
  `GET /privacy-policy.html` returns `200` **anonymously** and contains the
  contact email (guards against the policy URL being accidentally auth-gated,
  which would fail Google/Facebook review).
- **Frontend:** `UserPreferencesPage` test — the destructive button is disabled
  until the typed name matches, and confirming calls `api.deleteAccount`.

## Risks

- **Admin enumeration for the last-admin guard** must be correct; a wrong check
  could either block legitimate deletions or allow an admin lockout. Pin it with
  explicit unit tests for "other admin exists → allowed" and "sole admin →
  refused".
- **Self-only enforcement** is security-critical: the endpoint must derive the
  target from the session, never the request body. Covered by a dedicated test
  asserting a body naming another user cannot delete that user.
- **SSO re-provisioning** means deletion is not permanent for an SSO user who
  signs in again — intended and disclosed in the policy, not a defect.

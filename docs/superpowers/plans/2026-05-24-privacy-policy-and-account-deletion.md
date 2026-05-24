# Privacy Policy Page + Self-Service Account Deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve a public static privacy policy and let an authenticated user delete their own account (profile + group memberships + owned API keys), keeping their past page edits.

**Architecture:** A static `privacy-policy.html` in the WAR webapp root (served like `robots.txt`). A new `DELETE /api/auth/profile` action on the existing `AuthResource` deletes only the session's own principal, guarded by a typed-login-name confirmation and a lockout-safe admin check, then invalidates the session. A "Delete account" section on `UserPreferencesPage` drives it.

**Tech Stack:** Java 21, Jakarta Servlets, JUnit 5 + Mockito, Gson (`parseJsonBody`/`getJsonString` already in `RestServletBase`), Selenide/Cargo ITs, React + Vitest.

**Spec:** `docs/superpowers/specs/2026-05-24-privacy-policy-and-account-deletion-design.md`

---

## Key facts for the implementer (read first)

- **`AuthResource`** (`wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java`) extends `RestServletBase`, is mapped to `/api/auth/*` (web.xml), and already has `doGet`/`doPost`/`doPut` dispatching on `extractPathParam(request)` (the trailing path segment, e.g. `"profile"`). It has **no `doDelete` yet**.
- Inside handlers, the established idioms are:
  - `final Engine engine = getEngine();`
  - `final Session session = Wiki.session().find( engine, request );`
  - `session.isAuthenticated()`, `session.getLoginPrincipal().getName()` (the **login name**), `session.getRoles()` (a `Principal[]`; an admin holds a role principal named `"Admin"` — see `DefaultBlogManager` line ~386 `"Admin".equals(role.getName())`).
  - `final UserDatabase db = getSubsystems().auth().users().getUserDatabase();`
  - `final JsonObject body = parseJsonBody( request, response );` → returns `null` and already writes an error response if the body is bad, so callers do `if ( body == null ) return;`
  - `getJsonString( body, "key" )` → String or null.
  - `sendJson( response, map )`, `sendError( response, int status, String msg )`, `sendNotFound( response, msg )`.
- **`UserDatabase.deleteByLoginName(loginName)`** removes the profile; on the JDBC backend it also runs `DELETE FROM roles WHERE login_name=?` (`JDBCUserDatabase` `DELETE_ROLES`), so role rows are cleaned up by this call — **do not** hand-delete roles.
- **Admins come from two sources:** the `roles` table (`role='Admin'`) and the DB-backed `Admin` group (`groups`/`group_members`). `AdminAuthFilter`'s Javadoc confirms both.
- **GroupManager** (`com.wikantik.auth.authorize.GroupManager`, an interface) has `getGroup(String name)` (throws `NoSuchPrincipalException`) and `setGroup(Session, Group)`. `AdminUserResource.tryAddToGroup` shows the membership idiom: build `new WikiPrincipal(loginName, WikiPrincipal.LOGIN_NAME)`, `group.add(principal)` / `group.remove(principal)`, then `getGroupManager().setGroup(session, group)`.
- **ApiKeyService** (`com.wikantik.auth.apikeys.ApiKeyService`, obtained via `ApiKeyServiceHolder`) persists keys with a `principalLogin`. You will LOCATE its list-by-principal and revoke methods (Task 3).
- Build/test one module: `mvn test -pl wikantik-rest -Dtest=ClassName -q`. `mvn test-compile` after signature changes. ITs only under `-Pintegration-tests`, sequential, Docker required.

---

## Phase A — Privacy policy

### Task 1: Static privacy policy page + public-reachability IT

**Files:**
- Create: `wikantik-war/src/main/webapp/privacy-policy.html`
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/.../PrivacyPolicyReachabilityIT.java` (mirror an existing anonymous-GET IT in that module)

- [ ] **Step 1: Write the page.** Create `wikantik-war/src/main/webapp/privacy-policy.html` — a complete, standalone HTML5 document (no external CSS/JS/fonts) with `<title>Privacy Policy — Wikantik</title>` and the seven sections from the spec. Use this exact content for the identity and deletion-bearing parts (other sections in your own clear prose):

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="index,follow">
  <title>Privacy Policy — Wikantik</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 46rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.6; color: #222; }
    h1 { font-size: 1.8rem; } h2 { font-size: 1.2rem; margin-top: 2rem; }
    a { color: #15803d; }
  </style>
</head>
<body>
  <h1>Privacy Policy</h1>
  <p><strong>Wikantik</strong>, operated by Jacob Fear. Effective 2026-05-24.
     Questions or requests: <a href="mailto:jakefear@gmail.com">jakefear@gmail.com</a>.</p>

  <h2>What we collect</h2>
  <p>When you sign in with Google or Facebook, we receive your account identifier,
     name, and email address from that provider, and store a user profile (login
     name, full name, email). We set only essential cookies: a session cookie and
     an optional "remember me" sign-in cookie. We do not use tracking or
     advertising cookies.</p>

  <h2>How we use your information</h2>
  <p>Solely to sign you in and to create and maintain your wiki account. Pages you
     edit are attributed to your username in the wiki's page history.</p>

  <h2>What we do not do</h2>
  <p>We do not sell or share your personal information, and we use no third-party
     analytics or advertising.</p>

  <h2>Data sharing</h2>
  <p>We share no personal data beyond the identity provider you choose to sign in
     with.</p>

  <h2>Retention and deleting your account</h2>
  <p>You can delete your account at any time from your user preferences. Deletion
     removes your profile, your group memberships, and any API keys you own, and
     signs you out. Your past page contributions remain in the wiki's page history,
     attributed to your username. If you sign in again with Google or Facebook, a
     new account is created. You may also email
     <a href="mailto:jakefear@gmail.com">jakefear@gmail.com</a> for help with your
     data.</p>

  <h2>Children</h2>
  <p>This site is not directed to children under 13, and we do not knowingly
     collect their information.</p>

  <h2>Changes to this policy</h2>
  <p>We may update this policy; material changes will be reflected by a new
     effective date above.</p>

  <h2>Contact</h2>
  <p>Jacob Fear — <a href="mailto:jakefear@gmail.com">jakefear@gmail.com</a>.</p>
</body>
</html>
```

- [ ] **Step 2: Find an anonymous-GET IT to mirror.** Run `grep -rln "TestEngine\|RestSeedHelper\|HttpURLConnection\|given()\|baseUrl" wikantik-it-tests/wikantik-it-test-rest/src/test/java` and open one test that performs an unauthenticated HTTP GET against the Cargo-deployed app, to copy its base-URL accessor and HTTP-call style.

- [ ] **Step 3: Write the reachability IT.** Create `PrivacyPolicyReachabilityIT.java` mirroring that style. It must perform an **unauthenticated** GET of `<baseUrl>/privacy-policy.html` and assert: HTTP status `200`, and the response body contains `jakefear@gmail.com`. (This guards against the page being accidentally auth-gated, which would fail Google/Facebook review.) Use the same package/base-class as the mirrored test.

- [ ] **Step 4: Run the IT.** `mvn verify -pl wikantik-it-tests/wikantik-it-test-rest -Pintegration-tests -fae -Dit.test=PrivacyPolicyReachabilityIT` (NO `-T`). Expected: 1 test, pass. Quote the failsafe summary. If the static file 404s, confirm it is under `wikantik-war/src/main/webapp/` (not WEB-INF) and that the IT base URL includes the context path.

- [ ] **Step 5: Commit.**
```bash
git add wikantik-war/src/main/webapp/privacy-policy.html wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/PrivacyPolicyReachabilityIT.java
git commit -m "feat: add public privacy-policy.html for Google/Facebook SSO onboarding

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```
(Adjust the test path to the actual package directory you used.)

---

## Phase B — Self-service deletion backend

### Task 2: `isLastAdmin` guard helper (lockout-safe)

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceDeleteTest.java` (create)

**Goal:** a helper that answers "would deleting this admin remove the last admin?" Admins exist in two sources (the `roles` table and the `Admin` group). A precise cross-source count is backend-fragile, so implement the cleanest reliable signal and fall back conservatively.

- [ ] **Step 1: Locate the admin signals.** Run:
  - `grep -rn "getGroup\|members\|Admin" wikantik-main/src/main/java/com/wikantik/auth/authorize/Group.java | head` (does `Group` expose `members()` / `Principal[] members()`?)
  - `grep -rn "FIND_ROLES\|getWikiNames\|role" wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java | head`
  Decide which of these gives a reliable list of admins.

- [ ] **Step 2: Write the failing tests.** In `AuthResourceDeleteTest.java` (package `com.wikantik.rest`, ASF header), test the helper through whatever seam you expose it on. Define the helper as a package-private static method `boolean isLastAdmin( Engine engine, String loginName )` on `AuthResource` so it is unit-testable with a `TestEngine`. Tests:
  - With a single admin account present (the TestEngine bootstrap admin), `isLastAdmin(engine, "<that admin login>")` returns `true`.
  - With two admin accounts, `isLastAdmin(engine, "<one of them>")` returns `false`.
  - For a non-admin login, `isLastAdmin(engine, "<non-admin>")` returns `false`.
  Use `TestEngine.getTestProperties()` and the group/role APIs located in Step 1 to set up admins. If wiring two real admins in a unit test proves infeasible, note it and cover the branches you can, deferring the rest to the Task 5 handler tests.

- [ ] **Step 3: Run; verify FAIL** (`isLastAdmin` undefined). `mvn test -pl wikantik-rest -Dtest=AuthResourceDeleteTest -q`.

- [ ] **Step 4: Implement `isLastAdmin`.** Implement using the located signals: count distinct admin login names across the `Admin` group members and the roles source; return `true` iff `loginName` is an admin and the distinct admin count is `<= 1`.
  **Conservative fallback (lockout-safety first):** if you cannot reliably enumerate admins across both sources, implement `isLastAdmin` to return `true` for ANY caller that currently holds the `Admin` role (i.e. an admin cannot self-delete via this path at all), and add a `// DEVIATION:` comment + report it in your status so the controller can confirm. Never implement a version that could under-count and allow the last admin through.

- [ ] **Step 5: Run; verify PASS.** Same command. Quote results.

- [ ] **Step 6: Commit.**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceDeleteTest.java
git commit -m "feat(auth): add lockout-safe isLastAdmin guard for self-deletion

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

### Task 3: `revokeApiKeysFor(loginName)` helper

**Files:**
- Modify: `AuthResource.java` (+ possibly `wikantik-main/.../auth/apikeys/ApiKeyService.java`)
- Test: extend `AuthResourceDeleteTest.java`

- [ ] **Step 1: Locate the API.** Run `grep -nE "public |List|revoke|principalLogin|listFor|delete" wikantik-main/src/main/java/com/wikantik/auth/apikeys/ApiKeyService.java`. Identify how to (a) list keys for a principal login and (b) revoke/delete a key. If a "revoke all for principal" or "list by principal" method is missing, add a `void revokeAllForPrincipal( String principalLogin )` method to `ApiKeyService` that deletes/marks-revoked all `api_keys` rows for that login (mirror the existing JDBC update idiom in that class), with its own unit test in the apikeys test package.

- [ ] **Step 2: Write the failing test.** In `AuthResourceDeleteTest`, add a test that after `revokeApiKeysFor(engine, loginName)` the user has no active API keys. Note: if `ApiKeyService` requires a real `DataSource` (JDBC) not present in a pure `TestEngine`, instead unit-test the new `ApiKeyService.revokeAllForPrincipal` directly in `wikantik-main` against the test datasource the other apikeys tests use, and treat `revokeApiKeysFor` in `AuthResource` as a thin delegating wrapper covered by the Task 6 IT. State which path you took.

- [ ] **Step 3: Run; verify FAIL.**

- [ ] **Step 4: Implement.** Add a package-private `void revokeApiKeysFor( Engine engine, String loginName )` on `AuthResource` that resolves the `ApiKeyService` via `ApiKeyServiceHolder` and calls the located revoke/list+revoke method. Catch and `LOG.warn` failures with context (do not abort the whole deletion — but never swallow silently).

- [ ] **Step 5: Run; verify PASS.** Quote results.

- [ ] **Step 6: Commit.**
```bash
git add -A wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceDeleteTest.java
# include ApiKeyService.java + its test if you added a method
git commit -m "feat(auth): revoke a user's API keys on self-deletion

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

### Task 4: `removeFromAllGroups(session, loginName)` helper

**Files:**
- Modify: `AuthResource.java`
- Test: extend `AuthResourceDeleteTest.java`

- [ ] **Step 1: Locate group enumeration.** Run `grep -nE "Group\[\]|getRoles|Collection<Group>|getGroups" wikantik-main/src/main/java/com/wikantik/auth/authorize/GroupManager.java`. `GroupManager extends Authorizer`; `Authorizer.getRoles()` returns the known roles (groups). Confirm how to list all groups and read a group's members.

- [ ] **Step 2: Write the failing test.** In `AuthResourceDeleteTest`: create a group via `GroupManager`, add login `"groupuser"` to it (mirror `AdminUserResource.tryAddToGroup`: `new WikiPrincipal("groupuser", WikiPrincipal.LOGIN_NAME)`, `group.add(p)`, `getGroupManager().setGroup(session, group)`), then call `removeFromAllGroups(engine, session, "groupuser")` and assert the group no longer lists that principal. Build the `Session` with the TestEngine admin (see how other rest tests obtain an authenticated `Session`).

- [ ] **Step 3: Run; verify FAIL.**

- [ ] **Step 4: Implement.** Add package-private `void removeFromAllGroups( Engine engine, Session session, String loginName )`: enumerate groups via `GroupManager`, and for each group whose members include `new WikiPrincipal(loginName, WikiPrincipal.LOGIN_NAME)`, call `group.remove(principal)` then `getGroupManager().setGroup(session, group)`. `LOG.warn` per-group failures with context; continue.

- [ ] **Step 5: Run; verify PASS.** Quote results.

- [ ] **Step 6: Commit.**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceDeleteTest.java
git commit -m "feat(auth): remove a user from all groups on self-deletion

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

### Task 5: `DELETE /api/auth/profile` handler (wires it together)

**Files:**
- Modify: `AuthResource.java`
- Test: extend `AuthResourceDeleteTest.java`

- [ ] **Step 1: Write the failing handler tests.** Add to `AuthResourceDeleteTest` (use Mockito to mock `HttpServletRequest`/`HttpServletResponse`, mirror how other `wikantik-rest` servlet tests drive a handler; build request bodies as JSON strings via a `BufferedReader` stub). Cover:
  - **mismatch → 400, no deletion:** authenticated as `"alice"`, body `{"confirmLoginName":"bob"}` → responds `400`, and `db.findByLoginName("alice")` still succeeds.
  - **self-only:** authenticated as `"alice"`, body `{"confirmLoginName":"alice"}` but the body ALSO carries `{"loginName":"bob"}` → only `"alice"` is ever passed to deletion (assert `"bob"` is untouched). The handler must derive the target from the session, never the body's `loginName`.
  - **last admin → 409, no deletion:** authenticated as the sole admin, matching confirm → `409`, profile still present.
  - **happy path → 200 + session invalidated:** authenticated as a non-admin `"alice"` with matching confirm → `200`; `db.findByLoginName("alice")` now throws `NoSuchPrincipalException`; the session is invalidated (assert `request.getSession(false)` was invalidated / `session.logout`-equivalent was invoked — assert against whatever teardown the handler calls).

- [ ] **Step 2: Run; verify FAIL** (no `doDelete`). `mvn test -pl wikantik-rest -Dtest=AuthResourceDeleteTest -q`.

- [ ] **Step 3: Implement `doDelete` + `handleDeleteProfile`.** Add to `AuthResource`:

```java
    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "profile".equals( action ) ) {
            handleDeleteProfile( request, response );
        } else {
            sendNotFound( response, "Unknown auth endpoint: " + action );
        }
    }

    /**
     * Self-service account deletion. Deletes ONLY the authenticated session's own
     * account. Requires a confirmLoginName in the body matching the session
     * principal, and refuses if the caller is the last remaining admin. Removes
     * owned API keys and group memberships, deletes the profile, then invalidates
     * the session. Past page edits remain (authorship is not stored as a user FK).
     */
    private void handleDeleteProfile( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        if ( !session.isAuthenticated() ) {
            sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required" );
            return;
        }

        // Target is ALWAYS the session's own login — never taken from the body.
        final String loginName = session.getLoginPrincipal().getName();

        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;
        final String confirm = getJsonString( body, "confirmLoginName" );
        if ( confirm == null || !confirm.equals( loginName ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "confirmLoginName must match your own login name to delete your account" );
            return;
        }

        if ( isLastAdmin( engine, loginName ) ) {
            sendError( response, HttpServletResponse.SC_CONFLICT,
                    "You are the only administrator; another admin must exist before you can delete this account." );
            return;
        }

        try {
            revokeApiKeysFor( engine, loginName );
            removeFromAllGroups( engine, session, loginName );
            getSubsystems().auth().users().getUserDatabase().deleteByLoginName( loginName );
        } catch ( final NoSuchPrincipalException e ) {
            sendNotFound( response, "Profile not found" );
            return;
        } catch ( final WikiSecurityException e ) {
            LOG.error( "Self-deletion failed for {}: {}", loginName, e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Account deletion failed" );
            return;
        }

        LOG.info( "User {} deleted their own account", loginName );

        // Invalidate the session so the now-deleted user is logged out.
        session.logout();
        final var httpSession = request.getSession( false );
        if ( httpSession != null ) {
            httpSession.invalidate();
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "deleted", true );
        result.put( "loginName", loginName );
        sendJson( response, result );
    }
```

  Verify imports already present in the file: `Session`, `Wiki`, `Engine`, `JsonObject`, `NoSuchPrincipalException`, `WikiSecurityException`, `Map`, `LinkedHashMap`. Add any that are missing. Confirm `session.logout()` exists on the `Session` type (grep `Session.java`); if the teardown method differs, use the same logout call `handleLogout` uses.

- [ ] **Step 4: Run; verify PASS.** Quote results.

- [ ] **Step 5: `mvn test-compile -pl wikantik-rest -q`** — BUILD SUCCESS.

- [ ] **Step 6: Commit.**
```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceDeleteTest.java
git commit -m "feat(auth): DELETE /api/auth/profile self-service account deletion

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

### Task 6: Wire-level deletion IT

**Files:**
- Test: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/.../SelfDeleteAccountIT.java` (create)

- [ ] **Step 1: Find the REST IT auth helper.** Run `grep -rln "performLogin\|RestSeedHelper\|login\|Cookie\|authenticated" wikantik-it-tests/wikantik-it-test-rest/src/test/java` and open a test that logs in over HTTP and makes an authenticated call, to copy the login + cookie/session handling. (See memory: JDBC IT first-call race — if you hit it, use `RestSeedHelper.awaitAdminReady` as other tests do.)

- [ ] **Step 2: Write the IT.** Create `SelfDeleteAccountIT.java`: create a throwaway non-admin user (via the admin API or seed helper the other tests use), log in AS that user over HTTP, `DELETE /api/auth/profile` with body `{"confirmLoginName":"<that user>"}` and the user's session cookie, assert `200`. Then assert the account is gone: a subsequent `GET /api/auth/profile` with the same (now-invalidated) session is `401`, and an admin `GET /admin/users/<thatuser>` reports it absent. Keep the user name unique per run.

- [ ] **Step 3: Run.** `mvn verify -pl wikantik-it-tests/wikantik-it-test-rest -Pintegration-tests -fae -Dit.test=SelfDeleteAccountIT`. Quote the summary. (Allow time for the dependency build; if needed `mvn install -DskipTests -DskipITs -q -pl wikantik-war -am` first.)

- [ ] **Step 4: Commit.**
```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/SelfDeleteAccountIT.java
git commit -m "test(auth): wire-level IT for self-service account deletion

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase C — Frontend

### Task 7: "Delete account" UI on UserPreferencesPage

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`
- Modify: `wikantik-frontend/src/components/UserPreferencesPage.jsx`
- Test: `wikantik-frontend/src/components/UserPreferencesPage.test.jsx` (create or extend)

- [ ] **Step 1: Add the API client method.** In `wikantik-frontend/src/api/client.js`, next to `updateProfile` (which does `request('/api/auth/profile', { method: 'PUT', ... })`), add:
```js
  deleteAccount: (confirmLoginName) =>
    request('/api/auth/profile', { method: 'DELETE', body: JSON.stringify({ confirmLoginName }) }),
```

- [ ] **Step 2: Write the failing component test.** Create/extend `UserPreferencesPage.test.jsx` (mirror an existing `*.test.jsx` for render + `useAuth` mocking). Assert: a "Delete my account" control renders; clicking it reveals a confirmation input; the destructive confirm button is **disabled** until the typed value equals the current user's login name; once it matches, clicking it calls `api.deleteAccount` with that login name. Mock `api.deleteAccount`.

- [ ] **Step 3: Run; verify FAIL.** `cd wikantik-frontend && npm test -- --run UserPreferencesPage`.

- [ ] **Step 4: Implement the UI.** At the bottom of `UserPreferencesPage.jsx`, add a visually separated "Delete account" section (danger styling consistent with the file's inline-style approach). State: a boolean to reveal the confirm box and a `confirmName` string. Render an `error-banner` for server errors. Behavior: the confirm button is `disabled={confirmName !== user.login}` (use whatever field holds the current login name — check how the component reads the user; it uses `useAuth()`/profile). On click: `await api.deleteAccount(confirmName)`, then clear client auth state the same way logout does in this app (find the logout call in `useAuth`/UserBadge and reuse it) and redirect to `/wiki/Main`. On error, show `err.body?.message`. Add `data-testid` attributes (`delete-account-button`, `delete-confirm-input`, `delete-confirm-button`) for the test.

- [ ] **Step 5: Run; verify PASS.** Quote results.

- [ ] **Step 6: Commit.**
```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/components/UserPreferencesPage.jsx wikantik-frontend/src/components/UserPreferencesPage.test.jsx
git commit -m "feat(ui): self-service delete-account section on preferences page

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase D — Final verification

### Task 8: Full reactor gates

- [ ] **Step 1: `mvn test-compile -pl wikantik-rest -q`** — confirms no signature drift across the helper tasks.
- [ ] **Step 2: Full unit reactor.** `mvn clean install -T 1C -DskipITs` — BUILD SUCCESS.
- [ ] **Step 3: Full integration reactor.** `mvn clean install -Pintegration-tests -fae` — all IT modules pass, including the new `PrivacyPolicyReachabilityIT` and `SelfDeleteAccountIT`. Docker required. Quote the Reactor Summary.
- [ ] **Step 4: Commit** any incidental fixes from Steps 1–3 (stage by name).

---

## Self-review notes

- **Spec coverage:** Part A → Task 1. Part B API + confirm + self-only + last-admin + cleanup + session invalidation → Tasks 2–5; wire-level IT → Task 6. Frontend → Task 7. Policy text references deletion → Task 1 content. Testing (unit + IT + frontend) → Tasks 2–7. Reachability guard → Task 1. Final reactor → Task 8.
- **Conservative-fallback flag:** Task 2's `isLastAdmin` may land on the "refuse all admin self-deletes" fallback if cross-source enumeration is unclean; the implementer must flag this for controller confirmation (it slightly narrows the approved "block last admin only" behavior in favor of guaranteed lockout-safety).
- **Type consistency:** helper names `isLastAdmin(Engine,String)`, `revokeApiKeysFor(Engine,String)`, `removeFromAllGroups(Engine,Session,String)`, handler `handleDeleteProfile`, body field `confirmLoginName`, client `deleteAccount(confirmLoginName)`, testids `delete-account-button`/`delete-confirm-input`/`delete-confirm-button` are used consistently across tasks.
- **Locate-steps** (admin signals, ApiKeyService revoke, group enumeration, REST IT auth helper, logout call) are explicit research actions, not placeholders — each is followed by concrete implementation using the located API, with the desired behavior pinned by a test.

# Display Names Instead of Login Names — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users are shown to each other by a unique, user-settable display name; a user's login name (an email address for Google sign-ins) reaches nobody but that user and administrators.

**Architecture:** Storage keeps the stable login as the key (page versions, comments, mentions, owners). A single `PublicIdentity` service (interface in `wikantik-api`, `DefaultPublicIdentity` in `wikantik-main`, exposed on `AuthSubsystem.Services`) turns a stored login into the person's current display name on every non-admin surface — REST, feeds, plugins, ontology, knowledge MCP, `/tools`. Mentions are stored as `@[snapshot](user:<uid>)` tokens. Page access-rule directives are stripped from raw text for callers who cannot edit the page and from all indexed text.

**Tech Stack:** Java 25, Jakarta Servlet 6.1 / Tomcat 11, PostgreSQL 18 + pgvector (tests: `@RequiresPostgres` + `PostgresTestDb`), JUnit 5, Mockito, Apache Lucene, Apache Jena; React 19 + Vite 8 + vitest 4.

**Spec:** `docs/superpowers/specs/2026-10-13-display-names-design.md` — executors read it alongside this plan.

## Global Constraints

- Display name = `users.full_name`; required, trimmed, **2–64** characters (`ProfileNameRules.DISPLAY_NAME_MIN = 2`, `DISPLAY_NAME_MAX = 64`), no control characters, no invisible formatting characters (see Review Focus 1), no `@`, not a reserved name, not equal case-insensitively to another account's login, **unique case-insensitively** after trimming and collapsing runs of whitespace.
- Status codes for name violations: **400** = invalid (`NameViolation.Kind.INVALID`), **409** = already taken (`Kind.TAKEN`); SCIM: `409 uniqueness` for taken, `400 invalidValue` for invalid. Body is the standard `{"error":true,"status":…,"message":"…"}`.
- Uniqueness is enforced in the application (`ProfileNameRules`), **not** by a database index — migrations run at container start and an index would fail on existing duplicates. Unchanged names are never re-validated.
- Google sign-in: display name from the IdP `name` claim, else `<name> 2`, `<name> 3`, …, else `Member N`; **never the email**; never re-synced on later logins.
- Only `/admin/*` and the admin MCP server show logins. Every other surface — for everyone, administrators included — shows `PublicIdentity.displayName(stored)`.
- `PublicIdentity` access: REST `getSubsystems().auth().publicIdentity()`; non-REST code `AuthSubsystemBridge.fromLegacyEngine(engine).publicIdentity()` or the never-null `PublicIdentities.forEngine(engine)` (Task 5); factories that already receive `AuthSubsystem.Services` use it directly. **No new `WikiEngine#getManager` callers** (DecompositionArchTest R-2/R-4; restore `wikantik-main/src/test/resources/archunit_store` from git if a red run rewrites it).
- Every profile-writing path calls `ProfileChangeNotifier.profileSaved(engine, login)` / `profileDeleted(engine, login)` (Task 4/5) — never `PublicIdentity.invalidate` directly.
- Mention token: `@[<display name snapshot>](user:<uid>)`, regex `@\[([^\]\n]{1,64})\]\(user:([A-Za-z0-9-]{1,64})\)`.
- JSON: Gson omits nulls — a contract "null" (`userId` when anonymous, `authorId` for a non-account author, a withheld `snippet`) means **the key is absent**.
- Admin surfaces are unchanged. Free-text frontmatter (`author:`, `verified_by:`) is shown as written unless it is exactly an existing login.
- Never swallow exceptions; no unjustified `LOG.error` (`LogLevelEnforcementTest`); PMD complexity gate must pass with no new baseline entries; stage files by name; each task ends in its own commit whose message ends with the `Co-Authored-By:` line **your own session** specifies.
- ACL test fixtures: a non-empty ACL is deny-by-default and `edit` implies `view`. A "public page that names a user in an ACL" must carry `[{ALLOW view All}]` **and** `[{ALLOW edit Admin,<login>}]`; comment-thread fixtures need a page whose ACL also allows `comment`.

## Review Focus

Failure modes the spec implies that no drafted test covered, most likely first. Each has a pinning test below, added to the owning task.

1. **Look-alike display names** — `Jake Fear` with a zero-width space, a right-to-left override, or extra internal spaces must not count as a different name from `Jake Fear` (impersonation). Expected: invisible formatting characters are rejected; uniqueness compares NFKC-normalised, whitespace-collapsed, lower-cased names. (Task 1, Task 21)
2. **Forged mention tokens** — a commenter hand-types `@[Jake Fear](user:<made-up uid>)`. Expected: a known uid always renders as a chip with the account's *current* name (never the snapshot text); an unknown uid renders as plain text `@Jake Fear` with no chip, and the server records no mention. (Task 16, Task 14)
3. **Deleted accounts in old history** — a stored login whose account no longer exists. Expected: an email-shaped value is masked as `unknown contributor`; a mention token for a deleted uid renders as plain text. (Task 5, Task 16)
4. **Legacy strings that collide with a login** — history stored a legacy full name `fred` while an account's login is `Fred`. Expected: only an exact (case-sensitive) login match resolves; `fred` is shown unchanged. (Task 5)
5. **Old page versions** — a non-editor fetches `GET /api/pages/{name}?version=N` of a version whose text contains `[{ALLOW …}]`. Expected: the directive is stripped exactly as for the current version. (Task 18)

### Review-focus tests (add to the owning task: tests in Step 1, code in Step 3)

**Task 1** — append to `ProfileNameRulesTest` (uses that class's `displayError(login, candidate)` helper and its fixture account whose display name is `The Boss`):

```java
    @Test
    void displayNameCannotContainInvisibleFormattingCharacters() {
        for ( final String name : new String[] { "Jake​Fear", "Jake‮Fear", "⁠Jake Fear", "Jake﻿Fear" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "invisible" ), name );
        }
    }

    @Test
    void uniquenessIgnoresCaseAndRunsOfWhitespace() {
        assertTrue( displayError( "mallory", "the   boss" ).isPresent(), "collapsed whitespace + case must still collide" );
        assertTrue( displayError( "mallory", "THE BOSS" ).isPresent() );
    }
```

Step 3 additions in `ProfileNameRules`: a rule rejecting any code point whose `Character.getType` is `FORMAT`, `PRIVATE_USE` or `UNASSIGNED`, message `"A display name cannot contain invisible formatting characters"` (Kind `INVALID`); and one comparison key used by both the interface default and the JDBC override:

```java
    /** Comparison key for display-name uniqueness: NFKC, trimmed, whitespace runs collapsed, lower-cased. */
    public static String displayNameKey( final String name ) {
        return name == null ? null
            : java.text.Normalizer.normalize( name.trim(), java.text.Normalizer.Form.NFKC )
                .replaceAll( "\\s+", " " ).toLowerCase( java.util.Locale.ROOT );
    }
```

`JDBCUserDatabase#isFullNameTakenIgnoreCase` binds `displayNameKey(fullName)` against
`lower(regexp_replace(normalize(btrim(full_name), NFKC), '\s+', ' ', 'g'))`; the `UserDatabase` default compares `displayNameKey` of each profile.

**Task 5** — append to `DefaultPublicIdentityTest` (uses its `account(...)`, `noAccount(...)` and `identity` fixtures):

```java
    @Test
    void onlyAnExactCaseLoginMatchResolves() throws Exception {
        account( "Fred", "Fred Flintstone", "u-fred" );
        noAccount( "fred" );
        assertEquals( "fred", identity.displayName( "fred" ), "a case variant of a login is not that account" );
        assertEquals( "Fred Flintstone", identity.displayName( "Fred" ) );
    }

    @Test
    void aDeletedAccountsEmailLoginIsMasked() throws Exception {
        noAccount( "gone@example.test" );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "gone@example.test" ) );
    }
```

**Task 14** — append to the `MentionService` DB test from Task 14 (uses its `newComment()`, `ds` and `svc` fixtures; `u-bob` is the account whose display name is `Bobby`):

```java
    @Test
    void a_forged_token_naming_a_real_member_records_nothing() throws Exception {
        final UUID cid = newComment();
        try ( Connection c = ds.getConnection() ) {
            svc.recordCreate( c, cid, "alice", "hi @[Bobby](user:u-forged)", Optional.empty() );
        }
        assertTrue( svc.findByComment( cid ).isEmpty(), "the snapshot text must never select the recipient" );
    }
```

**Task 16** — append to `wikantik-frontend/src/utils/mentionTokens.test.js` and implement in `CommentBody.jsx` (Step 3: render `segment.type === 'mention'` as a chip **only when** `mentions[segment.uid]` exists, using that name; otherwise render the text `@` + snapshot as plain text):

```js
import { render, screen } from '@testing-library/react';
import CommentBody from '../components/CommentBody';

describe('forged or stale mention tokens', () => {
  it('renders a known uid with the current name, never the snapshot', () => {
    render(<CommentBody body="hi @[Jake Fear](user:u-1)" mentions={{ 'u-1': 'Mallory' }} />);
    expect(screen.getByText('@Mallory')).toBeInTheDocument();
    expect(screen.queryByText(/Jake Fear/)).not.toBeInTheDocument();
  });
  it('renders an unknown uid as plain text, not a chip', () => {
    const { container } = render(<CommentBody body="hi @[Jake Fear](user:nope)" mentions={{}} />);
    expect(container.querySelector('.mention-chip')).toBeNull();
    expect(container.textContent).toContain('@Jake Fear');
  });
});
```

(Use the chip class name Task 16 introduces if it differs from `.mention-chip`, and the prop names `CommentBody` actually takes — Task 16 defines both.)

**Task 18** — append to Task 18's `PageResource` reader/editor test (same fixtures): save a page whose **first** version contains `[{ALLOW view All}]` and `[{ALLOW edit Admin,alice@example.test}]`, save a second version, then as a non-editor `GET /api/pages/<page>?version=1` and assert the `content` contains no `alice@example.test` and no `ALLOW`, while an editor's response for `?version=1` still contains both.

**Task 21** — append to `DedupeDisplayNamesSqlTest` (uses `conn`, `insertUser(...)`, `runScript()`), and make the script's grouping key and its NULL-uid backfill match:

```java
    @Test
    void whitespaceAndCaseVariantsAreOneGroupAndBlankUidsGetAStableId() throws Exception {
        insertUser( "a", "Jake Fear", "JakeFear", "2026-01-01" );
        insertUser( "b", "jake   FEAR", "JakeFear2", "2026-02-01" );
        try ( Statement s = conn.createStatement() ) {
            s.executeUpdate( "UPDATE users SET uid = NULL WHERE login_name = 'b'" );
        }
        runScript();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery( "SELECT login_name, full_name, uid FROM users ORDER BY login_name" ) ) {
            rs.next(); assertEquals( "Jake Fear", rs.getString( 2 ) );
            rs.next(); assertEquals( "Jake Fear 2", rs.getString( 2 ) );
            final String uid = rs.getString( 3 );
            assertNotNull( uid );
            assertTrue( uid.matches( "[A-Za-z0-9-]{1,64}" ) );
        }
        runScript();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery( "SELECT uid FROM users WHERE login_name = 'b'" ) ) {
            rs.next();
            assertNotNull( rs.getString( 1 ), "a second run keeps the assigned uid" );
        }
    }
```

Script additions: group duplicates by `lower(regexp_replace(normalize(btrim(full_name), NFKC), '\s+', ' ', 'g'))`, and `UPDATE users SET uid = gen_random_uuid()::text WHERE uid IS NULL OR btrim(uid) = ''` (reporting only the count). Prod had 0 NULL uids on 2026-10-13; this closes the `JdbcUserProfileRowMapper` path that invents a fresh UUID on every read of a NULL uid.

## Integration notes (reconciled across sections)

- **Order:** Tasks 1 → 2 → 3 → 4 → 5 → 6, then 7–12, 13–16, 17–19 and 25 (these four groups only touch disjoint files except where noted), then 20, 21, 22 (the acceptance test for 5–19 and 25), 23, 24.
- **Shared-file overlaps (rebase by hand, small hunks):** `PageResource` (Task 6 authorship lines ~466/718; Task 7 `author` ~298; Task 18 `doGet` ~273/304/324); `DefaultLuceneIndexer` (Task 8 author field; Task 19 content field ~539); `GetPageTool` / `DefaultForAgentProjectionService` (Task 9 and Task 18 — the for-agent test lives in a separate class); `SearchWikiTool` (Task 9 and Task 25); `RecentChangesPlugin` (Task 10 author link; Task 25 view filter); `WikiEngine` `initKnowledgeGraph` / `PersistenceSubsystem.Deps` (Task 14 wires `MentionService`).
- **Tests that need real names** construct `new DefaultPublicIdentity( userDatabase, Clock.systemUTC() )` and pass it as the 9th `AuthSubsystem.Services` component; `WikiSubsystemsTestFactory.mockRecord(...)` fills `publicIdentity` with a Mockito mock whose `displayName` returns `null` (Task 5).
- **Resolve once:** author strings are resolved in `DefaultContextRetrievalService`/`RetrievedPage` (Task 9) and `DefaultRecentArticlesManager` (Task 10); surfaces that serialise those records (REST search, `/tools/search_wiki`, knowledge MCP, Atom feed, recent-articles servlet and plugin) must not resolve again — re-resolving a display name that happens to equal another login would swap people.
- `GET /api/users/mentionable` still enumerates accounts through `getSubsystems().auth().users().getUserDatabase().findAllProfiles()` (Task 15) — `PublicIdentity` has no "list all".
- **Rollout (Task 24):** deploy, run the Task 21 script (dry-run, then `--apply`), then rebuild indexes (Lucene + chunks; this also re-embeds — dense retrieval degrades until it finishes) and the ontology (`dct:creator` is materialised in TDB2). Rolling back to 2.4.57 after anyone saves a page would show that person's login in history again; roll forward instead.
- **Behaviour changes to record in the CHANGELOG (Task 23):** `POST /admin/users` without a display name is now rejected (400 "A display name is required" — Task 1 updates the ~16 `AdminUserResourceTest` cases that omitted one) while SCIM create without `name.formatted` gets `Member N`; SCIM malformed names return `400 invalidValue` (was 409); SSO accounts without a usable name get `Member N` (was the login); "My pages" lists pages whose latest stored author is the caller's login (pages last saved under a legacy full name reappear when next saved); the RecentChanges plugin no longer links author names; the Atom feed / recent articles omit pages anonymous visitors cannot view; `/tools/search_wiki` and the RecentChanges plugin filter by view permission (Task 25).

- **Not in this plan (follow-up issue):** six plugins built on `AbstractReferralPlugin` (`ReferringPagesPlugin`, `IndexPlugin`, `UnusedPagesPlugin`, `UndefinedPagesPlugin`, `ReferringUndefinedPagesPlugin`, `HubSetPlugin`) list page titles without a view check; and `/tools/*` API-key requests run as the anonymous guest, which `OpenApiDocument` (lines 85-87) describes differently — Task 23 corrects that text only.

## Task index

| # | Task | Section |
|---|---|---|
| 1 | Display-name rules, uniqueness query, REST 400/409 | A |
| 2 | SSO provisioning names | A |
| 3 | SCIM names | A |
| 4 | Profile save refreshes the live session (`ProfileChangeNotifier`) | A |
| 5 | `PublicIdentity` + `DefaultPublicIdentity` + wiring | A |
| 6 | `AuthorKeys` + every authorship writer stores the login | A |
| 7 | Page / list / history / recent-changes APIs | B |
| 8 | Lucene author field indexes the display name | B |
| 9 | Agent projection, knowledge MCP, `/tools` | B |
| 10 | Feeds + plugins (+ view filter in recent articles) | B |
| 11 | Ontology `dct:creator` | B |
| 12 | `MyPagesResource` + `PageOwnerService` | B |
| 13 | Comment-threads API + `/api/auth/user` `userId` | C |
| 14 | Mention tokens (parse + resolve) | C |
| 15 | Mentionable users + my-mentions | C |
| 16 | Frontend comments and mentions | C |
| 17 | `AclDirectives` utility | D |
| 18 | Strip directives from raw text for non-editors | D |
| 19 | Strip directives at index time | D |
| 25 | View filters for `/tools/search_wiki` and `RecentChangesPlugin` | (added) |
| 20 | Preferences + Admin → Users display-name UI | E |
| 21 | One-time dedupe script | E |
| 22 | Leak-probe integration test | E |
| 23 | Docs, help pages, privacy policy, CHANGELOG | E |
| 24 | Release 2.4.58 and roll out | E |

Each section ends with the drafter's "Notes" — read them; they record verified line numbers, ordering decisions and real-code findings.

---

# Section A — Identity core (Tasks 1–6)

# Section A — Identity core (Tasks 1–6)

Spec: `docs/superpowers/specs/2026-10-13-display-names-design.md` §1 (identity model), §2 (storage keys + identity service).

## Ordering and dependencies inside this section

Execute strictly in order **1 → 2 → 3 → 4 → 5 → 6**. Each task leaves the build green and is independently committable.

| Task | Needs | Notes |
|---|---|---|
| 1 display-name rules | – | Adds `ProfileNameRules` API, `UserDatabase#isFullNameTakenIgnoreCase`, REST 400/409. Reverses the existing "two accounts may share a full name" behaviour on the REST paths — the tests that asserted it are rewritten in this task (SSO's and SCIM's are rewritten in Tasks 2 and 3). |
| 2 SSO provisioning names | 1 | Uses `ProfileNameRules.availableDisplayName`. |
| 3 SCIM names | 1 | Maps `ProfileNameRules.NameViolation` to SCIM 400 `invalidValue` / 409 `uniqueness`. |
| 4 session refresh | 1–3 (touches the same call sites) | **Contains no `PublicIdentity` code.** Creates `ProfileChangeNotifier.profileSaved(engine, login)` (live-session refresh only) and calls it at every name-affecting write site. |
| 5 `PublicIdentity` | 4 | Creates the type + wiring, **and adds the `PublicIdentity.invalidate(login)` call inside `ProfileChangeNotifier`** (one place — no call-site churn) plus `profileDeleted` and its three delete call sites. This is the resolution of the "Task 4 needs Task 5's type" question: the invalidate wiring lives in Task 5. |
| 6 `AuthorKeys` | – (independent; placed last) | Switches every authorship writer from the session's *full-name* principal to the *login* principal. |

Shared command conventions (from the contract): backend `mvn -B -o test -pl <module> -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`. A task that edits `wikantik-main` and then runs `wikantik-rest`/`wikantik-scim` tests must first install the changed upstream module (the rest/scim tests resolve `wikantik-main` and its `tests` test-jar from `~/.m2`, never from the reactor):

```bash
bin/agent-build.sh start inst-main -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait inst-main 540        # exit 0 = installed
```

If the offline resolver reports a missing `2.4.58-SNAPSHOT` artifact for any module, run `bin/agent-build.sh start deps -- mvn -B -o install -pl <module> -am -DskipTests` and `bin/agent-build.sh wait deps 540` once.

`@RequiresPostgres` tests (`JDBCUserDatabaseTest`, `ScimSharedNameJdbcTest`) skip locally with a visible reason when Docker is absent; CI fails an absent daemon. Run them with Docker up before declaring a task done.

**Line numbers.** Every "Modify" line range was read from the tree at HEAD `bf12845fff` *before* any edit of this section. Within one file apply the edits bottom-to-top (or locate each by the quoted code); later tasks locate their insertion points by code, because earlier tasks shift the lines.

Never use `LOG.error` in new production code in these tasks (`LogLevelEnforcementTest` fails on an unjustified `LOG.error`); every `catch` logs at least `LOG.warn` with context.

---

### Task 1: Display-name rules (`ProfileNameRules`), uniqueness query, REST 400/409

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/ProfileNameRules.java` (replace whole file, 183 lines → below)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java` (imports lines 21-33; new default method after `findByFullName`, line 133)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java` (two SQL constants after line 111; two overrides after `findByFullName`, lines 172-178)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/UserProfileValidator.java` (`validate`, lines 100-111; new method after `validateReservedNames`, line 131)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/RestJson.java` (new `sendNameViolation` after `sendNotFound`, line ~104)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` (`handleUpdateProfile`, lines 373-386)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java` (`handleCreateUser` lines 512-521; `handleUpdateUser` lines 564-572)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/ProfileNameRulesTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/UserManagerTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java`

**Interfaces:**
- Consumes: `ReservedProfileNames.isReserved(Engine,String)`, `UserDatabase#findAllProfiles()/findByLoginName`, existing `ProfileNameRules.isAnotherAccountsLogin/derivedWikiName/availableWikiName`.
- Produces (contract names, plus the two additions marked *new*):
  - `ProfileNameRules.DISPLAY_NAME_MIN = 2`, `DISPLAY_NAME_MAX = 64`
  - `Optional<String> ProfileNameRules.displayNameError(Engine, UserDatabase, String login, String candidate)`
  - `String ProfileNameRules.availableDisplayName(Engine, UserDatabase, String login, String preferred)`
  - `Optional<String> fullNameChangeError(Engine, UserDatabase, UserProfile, String)` (signature unchanged, now applies `displayNameError` to a changed name); `nameChangeError(...)` likewise (unchanged signature) — SCIM calls it.
  - `default boolean UserDatabase#isFullNameTakenIgnoreCase(String fullName, String excludingLogin) throws WikiSecurityException`; `JDBCUserDatabase` override.
  - *new* `default boolean UserDatabase#isLoginNameTakenIgnoreCase(String loginName, String excludingLogin) throws WikiSecurityException` (+ `JDBCUserDatabase` override) — the case-insensitive login-collision probe.
  - *new* `ProfileNameRules.NameViolation(Kind kind, String message)` record with `Kind {INVALID, TAKEN}` and `isConflict()`; `Optional<NameViolation> displayNameViolation(...)`, `nameChangeViolation(...)`, `fullNameChangeViolation(...)` — the `*Error` methods are thin `.map(NameViolation::message)` wrappers. `TAKEN` = collides with another account's login name or display name (REST 409, SCIM `uniqueness`, SSO/SCIM suffixing); `INVALID` = malformed/reserved (REST 400, SCIM `invalidValue`, SSO placeholder).
  - *new* `RestJson.sendNameViolation(HttpServletResponse, ProfileNameRules.NameViolation)` → 409 for `TAKEN`, 400 otherwise, standard `{error,status,message}` body.

Behaviour decisions (from reading the code):
- `nameChangeError` is what the admin editor and SCIM call directly, so the display-name rules are applied **inside `nameChangeViolation`** (to the full-name pair, only when the trimmed name changed and a new name was supplied); `fullNameChangeError` goes through it. An **unchanged** name is never re-validated, so the prod duplicate group ("Jake Fear" ×4) does not block unrelated saves.
- Admin *create* **requires** a display name: `handleCreateUser` (which used to call `nameChangeError(…, null, null, fullName, …)` directly, where a null name counted as "unchanged") now calls `displayNameViolation` unconditionally, so a missing or blank `fullName` is `400` "A display name is required". Status codes are pinned: **400** for an invalid/reserved/missing name, **409** for a name already taken (another account's display name or login). The 16 existing `AdminUserResourceTest` tests that POST a user without a `fullName` get one added (step (f) below). Update with a blank name is likewise rejected. (SSO and SCIM provisioning, by contrast, never fail on a missing name — they assign `Member N`.)
- The "another account's login" rule is case-insensitive: the exact lookup first (cheap, cached in `JDBCUserDatabase`; it is what mocked databases in the REST tests answer), then a new `UserDatabase#isLoginNameTakenIgnoreCase` probe (one SQL query on JDBC — deliberately *not* a `findAllProfiles()` scan, which deserializes every row's attribute blob and fails on any corrupt row; the interface default scans for the in-memory test database). The whitespace-stripped (wiki-name) form is checked too, preserving the old derived-wiki-name check.
- If the uniqueness query itself fails, `displayNameViolation` logs a WARN and fails **open** (the save that follows hits the same store and will fail loudly) — same posture as the existing `otherAccount` helper.
- `applyFullName` now stores the **trimmed** name.

- [ ] **Step 1: Write the failing tests**

(a) `ProfileNameRulesTest.java` — replace the test `newNameSharedWithAnotherUsersFullOrWikiNameIsAllowed` (lines 71-76, including its `@Test`) with the version below, and append the new tests before the final `}` of the class. Add `import static org.junit.jupiter.api.Assertions.assertFalse;` (the file already imports `assertEquals`, `assertNull`, `assertThrows`, `assertTrue`).

```java
    @Test
    void newNameSharedWithAnotherUsersFullNameIsRefusedButASharedWikiNameIsAllowed() {
        // Display names are unique (case-insensitive); only the *derived wiki name* may still be shared, and the
        // write paths number it (availableWikiName).
        final ProfileNameRules.NameViolation clash = ProfileNameRules
                .nameChangeViolation( engine, db, "mallory", "Mallory", "Mallory", "The Boss", "TheBoss" ).orElseThrow();
        assertTrue( clash.isConflict(), "a duplicate display name is a conflict, not malformed input" );
        assertTrue( change( "Mallory", "Mallory", "Mallory", "TheBoss" ).isEmpty(), "shared wiki name alone is fine" );
    }

    // ---- display-name rules ----

    private Optional< String > displayError( final String login, final String candidate ) {
        return ProfileNameRules.displayNameError( engine, db, login, candidate );
    }

    @Test
    void displayNameIsRequired() {
        assertTrue( displayError( "mallory", null ).orElseThrow().contains( "required" ) );
        assertTrue( displayError( "mallory", "   " ).orElseThrow().contains( "required" ) );
    }

    @Test
    void displayNameLengthIsBoundedAfterTrimming() {
        assertTrue( displayError( "mallory", "A" ).orElseThrow().contains( "2 to 64" ) );
        assertTrue( displayError( "mallory", " A " ).isPresent(), "the trimmed length counts" );
        assertTrue( displayError( "mallory", "x".repeat( ProfileNameRules.DISPLAY_NAME_MAX + 1 ) ).orElseThrow().contains( "2 to 64" ) );
        assertTrue( displayError( "mallory", "Al" ).isEmpty(), "two characters is the minimum" );
        assertTrue( displayError( "mallory", "y".repeat( ProfileNameRules.DISPLAY_NAME_MAX ) ).isEmpty(), "64 is the maximum" );
        assertEquals( 2, ProfileNameRules.DISPLAY_NAME_MIN );
        assertEquals( 64, ProfileNameRules.DISPLAY_NAME_MAX );
    }

    @Test
    void displayNameCannotContainControlCharacters() {
        for ( final String name : new String[] { "Bad\tName", "Bad\nName", "Bad\u0007Name", "Bad\u007fName" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "control" ), name );
        }
    }

    @Test
    void displayNameCannotContainAnAtSign() {
        for ( final String name : new String[] { "jane@example.com", "@jane", "jane@" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "'@'" ), name );
        }
    }

    @Test
    void reservedDisplayNamesAreRefused() {
        for ( final String name : new String[] { "Admin", "A dmin", "authenticated" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "reserved" ), name );
        }
        assertFalse( ProfileNameRules.displayNameViolation( engine, db, "mallory", "Admin" ).orElseThrow().isConflict(),
                "reserved is malformed input, not a collision" );
    }

    @Test
    void anotherAccountsLoginIsRefusedCaseInsensitively() {
        for ( final String name : new String[] { "boss", "BOSS", " Boss ", "bOb" } ) {   // "Bob" is a seeded login
            final ProfileNameRules.NameViolation v = ProfileNameRules.displayNameViolation( engine, db, "mallory", name ).orElseThrow();
            assertTrue( v.message().contains( "login name" ), name + ": " + v.message() );
            assertTrue( v.isConflict(), name );
        }
        assertTrue( displayError( "boss", "BOSS" ).isEmpty(), "an account's own login is not 'another account's'" );
    }

    @Test
    void duplicateDisplayNameIsRefusedIgnoringCaseAndSurroundingWhitespace() {
        for ( final String name : new String[] { "The Boss", "the boss", "  THE BOSS  " } ) {
            final ProfileNameRules.NameViolation v = ProfileNameRules.displayNameViolation( engine, db, "mallory", name ).orElseThrow();
            assertTrue( v.message().contains( "already in use" ), name + ": " + v.message() );
            assertTrue( v.isConflict(), name );
        }
        assertTrue( displayError( "boss", "THE BOSS" ).isEmpty(), "the owner may re-case their own name" );
        assertTrue( displayError( "mallory", "Mallory Jones" ).isEmpty() );
    }

    @Test
    void fullNameChangeAppliesTheRulesOnlyWhenTheNameChanges() throws Exception {
        final UserProfile bossProfile = db.findByLoginName( "boss" );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "The Boss" ).isEmpty(), "unchanged" );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "x" ).orElseThrow().contains( "2 to 64" ) );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "" ).orElseThrow().contains( "required" ) );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "Bob" ).orElseThrow().contains( "login name" ) );

        // A pre-existing duplicate (production had one) must not lock its owner out of unrelated saves.
        saveAccount( "boss-twin", "The Boss" );
        try {
            final UserProfile stored = db.findByLoginName( "boss-twin" );
            assertTrue( ProfileNameRules.fullNameChangeError( engine, db, stored, "The Boss" ).isEmpty(),
                    "an unchanged duplicate is not re-validated" );
            assertTrue( ProfileNameRules.fullNameChangeError( engine, db, stored, "the boss" ).isPresent(),
                    "a changed spelling of the duplicate is validated and refused" );
        } finally {
            db.deleteByLoginName( "boss-twin" );
        }
    }

    @Test
    void nullNewFullNameMeansNoChangeRequested() {
        assertTrue( change( "Old Name", "OldName", null, null ).isEmpty() );
    }

    // ---- availableDisplayName ----

    @Test
    void availableDisplayNameKeepsAFreePreferredNameTrimmed() {
        assertEquals( "Mallory Jones", ProfileNameRules.availableDisplayName( engine, db, "mallory", "  Mallory Jones " ) );
        assertEquals( "The Boss", ProfileNameRules.availableDisplayName( engine, db, "boss", "The Boss" ),
                "an account's own current name is available to it" );
    }

    @Test
    void availableDisplayNameNumbersATakenNameFromTwo() throws Exception {
        assertEquals( "The Boss 2", ProfileNameRules.availableDisplayName( engine, db, "mallory", "The Boss" ) );
        saveAccount( "boss-2", "The Boss 2" );
        try {
            assertEquals( "The Boss 3", ProfileNameRules.availableDisplayName( engine, db, "mallory", "The Boss" ) );
            assertEquals( "the boss 3", ProfileNameRules.availableDisplayName( engine, db, "mallory", "the boss" ),
                    "the clash is case-insensitive; the claim keeps its own spelling in the numbered form" );
        } finally {
            db.deleteByLoginName( "boss-2" );
        }
    }

    @Test
    void availableDisplayNameSuffixesANameEqualToAnotherLogin() {
        assertEquals( "BOSS 2", ProfileNameRules.availableDisplayName( engine, db, "mallory", "BOSS" ) );
    }

    @Test
    void availableDisplayNameFallsBackToAPlaceholderNeverTheEmail() throws Exception {
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", null ) );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "   " ) );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "jane@example.com" ),
                "an email-shaped claim is unusable" );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "Admin" ),
                "a reserved claim is unusable" );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "x" ),
                "a too-short claim is unusable" );
        saveAccount( "member-one", "Member 1" );
        try {
            assertEquals( "Member 2", ProfileNameRules.availableDisplayName( engine, db, "mallory", null ) );
        } finally {
            db.deleteByLoginName( "member-one" );
        }
    }

    @Test
    void availableDisplayNameKeepsTheSuffixedResultWithinTheLengthLimit() throws Exception {
        final String longName = "z".repeat( ProfileNameRules.DISPLAY_NAME_MAX );
        saveAccount( "long-owner", longName );
        try {
            final String chosen = ProfileNameRules.availableDisplayName( engine, db, "mallory", longName );
            assertTrue( chosen.length() <= ProfileNameRules.DISPLAY_NAME_MAX, chosen );
            assertTrue( chosen.endsWith( " 2" ), chosen );
            assertTrue( displayError( "mallory", chosen ).isEmpty(), "the result must itself satisfy the rules" );
        } finally {
            db.deleteByLoginName( "long-owner" );
        }
    }

    /** Saves an account the way the write paths do (a free wiki name is derived), bypassing the display-name rules. */
    private void saveAccount( final String login, final String fullName ) throws Exception {
        final UserProfile p = db.newProfile();
        p.setLoginName( login );
        ProfileNameRules.applyFullName( engine, db, p, fullName );
        p.setEmail( login + "@example.test" );
        p.setPassword( "correct-horse-battery-staple-5" );
        db.save( p );
    }
```

(b) `JDBCUserDatabaseTest.java` — add after `testFindByFullName` (ends line 258):

```java
    @Test
    public void testIsFullNameTakenIgnoreCase() throws Exception {
        // janne ("Janne Jalkanen") is seeded in setUp; "user" has no full name.
        Assertions.assertTrue( m_db.isFullNameTakenIgnoreCase( "Janne Jalkanen", null ) );
        Assertions.assertTrue( m_db.isFullNameTakenIgnoreCase( "janne jalkanen", null ), "case-insensitive" );
        Assertions.assertTrue( m_db.isFullNameTakenIgnoreCase( "  JANNE JALKANEN  ", null ), "surrounding whitespace is trimmed" );
        Assertions.assertTrue( m_db.isFullNameTakenIgnoreCase( "Janne Jalkanen", "user" ), "held by a different account" );
        Assertions.assertFalse( m_db.isFullNameTakenIgnoreCase( "Janne Jalkanen", "janne" ),
                "an account's own name is not a clash with itself" );
        Assertions.assertFalse( m_db.isFullNameTakenIgnoreCase( "Nobody Atall", null ) );
        Assertions.assertFalse( m_db.isFullNameTakenIgnoreCase( "   ", null ), "blank is never 'taken'" );
        Assertions.assertFalse( m_db.isFullNameTakenIgnoreCase( null, null ) );
    }

    @Test
    public void testIsLoginNameTakenIgnoreCase() throws Exception {
        // logins "janne" and "user" are seeded in setUp
        Assertions.assertTrue( m_db.isLoginNameTakenIgnoreCase( "janne", null ) );
        Assertions.assertTrue( m_db.isLoginNameTakenIgnoreCase( "JANNE", null ), "case-insensitive" );
        Assertions.assertTrue( m_db.isLoginNameTakenIgnoreCase( "  Janne ", "user" ), "trimmed; held by a different account" );
        Assertions.assertFalse( m_db.isLoginNameTakenIgnoreCase( "JANNE", "janne" ), "an account's own login is not a clash" );
        Assertions.assertFalse( m_db.isLoginNameTakenIgnoreCase( "nobody", null ) );
        Assertions.assertFalse( m_db.isLoginNameTakenIgnoreCase( "  ", null ) );
        Assertions.assertFalse( m_db.isLoginNameTakenIgnoreCase( null, null ) );
    }

    @Test
    public void testIsFullNameTakenIgnoreCaseSeesADuplicateOwnedByAnotherAccountOnly() throws Exception {
        final UserProfile twin = m_db.newProfile();
        twin.setLoginName( "janne-twin" );
        twin.setFullname( "Janne Jalkanen 2" );
        twin.setPassword( Users.ALICE_PASS );
        m_db.save( twin );
        Assertions.assertTrue( m_db.isFullNameTakenIgnoreCase( "janne jalkanen 2", "janne" ) );
        Assertions.assertFalse( m_db.isFullNameTakenIgnoreCase( "janne jalkanen 2", "janne-twin" ) );
    }
```

(c) `UserManagerTest.java` — add after `testValidateProfileAcceptsOrdinaryName` (ends line 400):

```java
    /** The legacy validate path applies the display-name shape rules to a changed full name. */
    @Test
    void testValidateProfileRejectsMalformedDisplayName() throws Exception {
        for ( final String name : new String[] { "x", "a@b.example", "Control\u0007Char" } ) {
            final Context context = Wiki.context().create( m_engine, HttpMockFactory.createHttpRequest(), "" );
            final UserProfile profile = m_db.newProfile();
            profile.setLoginName( "malformedtest" );
            profile.setFullname( name );
            profile.setEmail( "malformed@example.com" );
            profile.setPassword( Users.ADMIN_PASS );
            m_mgr.validateProfile( context, profile );

            final String[] messages = context.getWikiSession().getMessages( SESSION_MESSAGES );
            Assertions.assertTrue( java.util.Arrays.stream( messages ).anyMatch( m -> m.contains( "display name" ) ),
                    "Expected a display-name error for '" + name + "', got " + java.util.Arrays.toString( messages ) );
        }
    }
```

(d) `AuthResourceTest.java` — change `handleUpdateProfile_rejectsFullNameUsedByAnotherAccount` (line 776-797): replace `assertEquals( 400, obj.get( "status" ).getAsInt() );` with `assertEquals( 409, obj.get( "status" ).getAsInt() );`. Replace `handleUpdateProfile_allowsFullNameSharedWithAnotherAccount` (lines 798-820) with the four tests below, and add them to the same class:

```java
    @Test
    void handleUpdateProfile_rejectsADuplicateDisplayNameWith409() throws Exception {
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        Mockito.when( db.isFullNameTakenIgnoreCase( "Alice Smith", "alice" ) ).thenReturn( true );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Alice Smith" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
            assertEquals( 409, obj.get( "status" ).getAsInt() );
            assertTrue( obj.get( "message" ).getAsString().contains( "already in use" ), obj.toString() );
        }
        Mockito.verify( profile, Mockito.never() ).setFullname( any() );
        Mockito.verify( db, Mockito.never() ).save( any() );
    }

    @Test
    void handleUpdateProfile_rejectsMalformedDisplayNamesWith400() throws Exception {
        final String[][] cases = {
            { "A", "2 to 64" }, { "x".repeat( 65 ), "2 to 64" }, { "   ", "required" },
            { "alice@example.com", "'@'" }, { "Bad\u0007Name", "control" } };
        for ( final String[] c : cases ) {
            final UserProfile profile = profileFor( "alice", "a@x" );
            final UserManager um = Mockito.mock( UserManager.class );
            final UserDatabase db = Mockito.mock( UserDatabase.class );
            Mockito.when( um.getUserDatabase() ).thenReturn( db );
            Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
            ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

            final JsonObject body = new JsonObject();
            body.addProperty( "fullName", c[ 0 ] );
            try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
                final JsonObject obj = gson.fromJson( doPut( "profile", body ), JsonObject.class );
                assertEquals( 400, obj.get( "status" ).getAsInt(), c[ 0 ] );
                assertTrue( obj.get( "message" ).getAsString().contains( c[ 1 ] ), obj.toString() );
            }
            Mockito.verify( profile, Mockito.never() ).setFullname( any() );
            Mockito.verify( db, Mockito.never() ).save( any() );
        }
    }

    @Test
    void handleUpdateProfile_numbersTheWikiNameWhenOnlyTheWikiNameCollides() throws Exception {
        // Display names are unique, but two different display names can still derive the same wiki name
        // ("Alice Smith" vs a stored "AliceSmith"); the wiki name is numbered, the save succeeds.
        final UserProfile profile = profileFor( "alice", "a@x" );
        final UserProfile other = profileFor( "asmith", "s@x" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        Mockito.when( db.findByWikiName( "AliceSmith" ) ).thenReturn( other );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", " Alice Smith " );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            doPut( "profile", body );
        }
        Mockito.verify( profile ).setFullname( "Alice Smith" );
        Mockito.verify( profile ).setWikiName( "AliceSmith2" );
        Mockito.verify( db ).save( profile );
    }

    @Test
    void handleUpdateProfile_doesNotRevalidateAnUnchangedDuplicateName() throws Exception {
        // Prod has accounts that already share a name; an unchanged name must not block saving the rest.
        final UserProfile profile = profileFor( "alice", "a@x" );
        Mockito.when( profile.getFullname() ).thenReturn( "Jake Fear" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        Mockito.when( db.isFullNameTakenIgnoreCase( "Jake Fear", "alice" ) ).thenReturn( true );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Jake Fear" );
        body.addProperty( "bio", "still me" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) ) ) {
            doPut( "profile", body );
        }
        Mockito.verify( profile ).setBio( "still me" );
        Mockito.verify( db ).save( profile );
    }
```

(e) `AdminUserResourceTest.java` — replace `testUpdateUserFullNameUsedByAnotherAccountIsRejected` (lines 742-776, including its `@Test`) with the following, and add the four new tests after it (`testCreateAndRename…`, `testCreateUserRequiresADisplayName`, `testCreateUserRejectsAnExplicitInvalidDisplayName`, `testUpdateUserRejectsBlankDisplayName`), then apply step (f):

```java
    @Test
    void testUpdateUserFullNameUsedByAnotherAccountIsRejected() throws Exception {
        final JsonObject a = new JsonObject();
        a.addProperty( "loginName", "nameOwner" );
        a.addProperty( "fullName", "Name Owner" );
        a.addProperty( "password", "StrongPassword123!" );
        doPost( null, a );
        final JsonObject b = new JsonObject();
        b.addProperty( "loginName", "nameTaker" );
        b.addProperty( "fullName", "Name Taker" );
        b.addProperty( "password", "StrongPassword123!" );
        doPost( null, b );

        // equal to another account's login name -> 409 (a collision, not malformed input)
        final JsonObject update = new JsonObject();
        update.addProperty( "fullName", "nameOwner" );
        final JsonObject obj = gson.fromJson( doPut( "nameTaker", update ), JsonObject.class );
        assertTrue( obj.has( "error" ), "a full name equal to another account's login must be rejected: " + obj );
        assertEquals( 409, obj.get( "status" ).getAsInt() );

        // re-sending the account's own (unchanged) name is fine
        final JsonObject same = new JsonObject();
        same.addProperty( "fullName", "Name Taker" );
        final JsonObject kept = gson.fromJson( doPut( "nameTaker", same ), JsonObject.class );
        assertFalse( kept.has( "error" ), kept.toString() );
    }

    @Test
    void testCreateAndRenameRejectADuplicateDisplayNameIgnoringCaseAndWhitespace() throws Exception {
        final JsonObject holder = new JsonObject();
        holder.addProperty( "loginName", "dupHolder" );
        holder.addProperty( "fullName", "Duplicate Holder Person" );
        holder.addProperty( "password", "StrongPassword123!" );
        doPost( null, holder );
        final JsonObject other = new JsonObject();
        other.addProperty( "loginName", "dupOther" );
        other.addProperty( "fullName", "Some Other Person" );
        other.addProperty( "password", "StrongPassword123!" );
        doPost( null, other );

        // another account's display name, with different case/whitespace, on create -> 409
        final JsonObject create = new JsonObject();
        create.addProperty( "loginName", "dupClaimer" );
        create.addProperty( "fullName", "  duplicate HOLDER person " );
        create.addProperty( "password", "StrongPassword123!" );
        final JsonObject created = gson.fromJson( doPost( null, create ), JsonObject.class );
        assertEquals( 409, created.get( "status" ).getAsInt(), "display names are unique: " + created );
        assertTrue( created.get( "message" ).getAsString().contains( "already in use" ), created.toString() );

        // ... and on rename -> 409
        final JsonObject rename = new JsonObject();
        rename.addProperty( "fullName", "Duplicate Holder Person" );
        final JsonObject renamed = gson.fromJson( doPut( "dupOther", rename ), JsonObject.class );
        assertEquals( 409, renamed.get( "status" ).getAsInt(), renamed.toString() );
        assertTrue( renamed.get( "message" ).getAsString().contains( "already in use" ), renamed.toString() );
    }

    @Test
    void testCreateUserRequiresADisplayName() throws Exception {
        final JsonObject missing = new JsonObject();
        missing.addProperty( "loginName", "noNameUser" );
        missing.addProperty( "password", "StrongPassword123!" );
        final JsonObject obj = gson.fromJson( doPost( null, missing ), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt(), obj.toString() );
        assertTrue( obj.get( "message" ).getAsString().contains( "required" ), obj.toString() );

        final JsonObject blank = new JsonObject();
        blank.addProperty( "loginName", "noNameUser" );
        blank.addProperty( "fullName", "   " );
        blank.addProperty( "password", "StrongPassword123!" );
        assertEquals( 400, gson.fromJson( doPost( null, blank ), JsonObject.class ).get( "status" ).getAsInt() );
    }

    @Test
    void testCreateUserRejectsAnExplicitInvalidDisplayName() throws Exception {
        for ( final String name : new String[] { "x", "mallory@example.com", "Admin" } ) {
            final JsonObject body = new JsonObject();
            body.addProperty( "loginName", "invalidNameUser" );
            body.addProperty( "fullName", name );
            body.addProperty( "password", "StrongPassword123!" );
            final JsonObject obj = gson.fromJson( doPost( null, body ), JsonObject.class );
            assertEquals( 400, obj.get( "status" ).getAsInt(), name + ": " + obj );
        }
    }

    @Test
    void testUpdateUserRejectsBlankDisplayName() throws Exception {
        final JsonObject create = new JsonObject();
        create.addProperty( "loginName", "blankNameUser" );
        create.addProperty( "fullName", "Blank Name User" );
        create.addProperty( "password", "StrongPassword123!" );
        doPost( null, create );

        final JsonObject update = new JsonObject();
        update.addProperty( "fullName", "   " );
        final JsonObject obj = gson.fromJson( doPut( "blankNameUser", update ), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt(), obj.toString() );
        assertTrue( obj.get( "message" ).getAsString().contains( "required" ), obj.toString() );
    }
```

(f) `AdminUserResourceTest.java` — because a display name is now required on create, every existing test that POSTs a user without a `fullName` needs one. Insert the quoted line directly after the `loginName` line of each of these bodies (line numbers are the unedited file's; go bottom-to-top). Tests that stop at the password check (lines 113, 127) and the second, intentionally nameless `duplicateUser` POST (line 233, which only asserts "profile or error") need nothing.

| after line | variable | insert |
|---|---|---|
| 1144 | `createBody` | `createBody.addProperty( "fullName", "Plain User" );` |
| 1069 | `createBody` | `createBody.addProperty( "fullName", "Bulk Partial Delete User" );` |
| 1043 | `createBody` | `createBody.addProperty( "fullName", "Bulk Partial Lock User" );` |
| 919 | `createBody` | `createBody.addProperty( "fullName", "Self Delete Attempt" );` |
| 896 | `createBody` | `createBody.addProperty( "fullName", "Bulk Delete Target" );` |
| 869 | `createBody` | `createBody.addProperty( "fullName", "Bulk Unlock Target" );` |
| 842 | `createBody` | `createBody.addProperty( "fullName", "Bulk Lock Target" );` |
| 686 | `createBody` | `createBody.addProperty( "fullName", "Locked View User" );` |
| 487 | `createBody` | `createBody.addProperty( "fullName", "Orphan Fail User" );` |
| 457 | `createBody` | `createBody.addProperty( "fullName", "Orphan Test User" );` |
| 434 | `createBody` | `createBody.addProperty( "fullName", "Deletable User" );` |
| 400 | `createBody` | `createBody.addProperty( "fullName", "Unlockable User" );` |
| 377 | `createBody` | `createBody.addProperty( "fullName", "Bad Date Lock User" );` |
| 359 | `createBody` | `createBody.addProperty( "fullName", "Indefinite Lock User" );` |
| 339 | `createBody` | `createBody.addProperty( "fullName", "Lockable User" );` |
| 296 | `createBody` | `createBody.addProperty( "fullName", "Weak Pwd Update" );` |

(Each login's whitespace-stripped display name equals its own login ignoring case, which is allowed — an account never clashes with itself.) Any other module that creates accounts through `POST /admin/users` was checked: the REST ITs (`AdminUserBulkActionIT`, `SelfDeleteAccountIT`, `ScopedAdminGrantIT`) already send a distinct `fullName`.

- [ ] **Step 2: Run them to verify they fail**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='ProfileNameRulesTest,JDBCUserDatabaseTest,UserManagerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` in test sources — `cannot find symbol` for `ProfileNameRules.displayNameError`, `ProfileNameRules.NameViolation`, `ProfileNameRules.DISPLAY_NAME_MAX`, `ProfileNameRules.nameChangeViolation`, `availableDisplayName` and `UserDatabase.isFullNameTakenIgnoreCase` (JDBCUserDatabaseTest). The rest-module tests fail only after Step 3's install (they currently assert 400 where 409 is now expected and compile against the old jar).

- [ ] **Step 3: Implement**

**3a. `ProfileNameRules.java` — replace the entire file** (keep the Apache licence header lines 1-18 unchanged; the rules proper now live in the two package-private classes 3b and 3c, which keeps every class under the PMD complexity gate):

```java
package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Rules for a user's display name (stored as {@code users.full_name}) and wiki name when they are set or changed through
 * any write path: self-service profile, admin user editor, SCIM, SSO provisioning and the legacy {@code UserManager} path.
 *
 * <p>A display name must be present, {@value #DISPLAY_NAME_MIN}-{@value #DISPLAY_NAME_MAX} characters after trimming,
 * free of control characters and of {@code '@'} (so it can never pass for an email address), not reserved (see
 * {@link ReservedProfileNames}), not equal — ignoring case — to any <em>other</em> account's login name, and unique,
 * ignoring case and surrounding whitespace, among all other accounts' display names. Uniqueness is enforced here, in the
 * application, not by a database index: migrations run at container start and an index would fail on existing duplicates.</p>
 *
 * <p>A name that is <em>unchanged</em> is never re-checked, so a name that became reserved or duplicated after its owner
 * chose it cannot lock the owner out of saving the rest of their profile.</p>
 *
 * <p>Wiki names stay unique (the {@code users.wiki_name} column is UNIQUE); a write path that derives one passes it through
 * {@link #availableWikiName} so two different display names that derive the same wiki name still save.</p>
 */
public final class ProfileNameRules {

    private static final Logger LOG = LogManager.getLogger( ProfileNameRules.class );

    /** Shortest accepted display name, in characters after trimming. */
    public static final int DISPLAY_NAME_MIN = 2;

    /** Longest accepted display name, in characters after trimming. */
    public static final int DISPLAY_NAME_MAX = 64;

    /** Upper bound on numeric suffixes tried for a wiki name before giving up and keeping the preferred one. */
    private static final int MAX_WIKI_NAME_SUFFIX = 1000;

    private ProfileNameRules() { }

    /**
     * How a name broke the rules. {@link #INVALID} is malformed or reserved input (REST 400, SCIM {@code invalidValue}, and an
     * unusable IdP claim). {@link #TAKEN} is a collision with another account's login name or display name (REST 409, SCIM
     * {@code uniqueness}, and a claim that is worth numbering).
     */
    public enum Kind { INVALID, TAKEN }

    /** The first display-name rule a candidate broke, with a user-facing message. */
    public record NameViolation( Kind kind, String message ) {
        /** True for a collision with another account rather than malformed input. */
        public boolean isConflict() {
            return kind == Kind.TAKEN;
        }
    }

    static Optional< NameViolation > invalid( final String message ) {
        return Optional.of( new NameViolation( Kind.INVALID, message ) );
    }

    static Optional< NameViolation > taken( final String message ) {
        return Optional.of( new NameViolation( Kind.TAKEN, message ) );
    }

    // ------------------------------------------------------------------ display-name rules

    /**
     * Checks {@code candidate} against every display-name rule for the account {@code login}.
     *
     * @param login     the account being saved ({@code null} for a new account); its own login and name never clash
     * @param candidate the proposed display name
     * @return the first rule broken, else empty
     */
    public static Optional< NameViolation > displayNameViolation( final Engine engine, final UserDatabase db,
            final String login, final String candidate ) {
        return DisplayNameRules.violation( engine, db, login, candidate );
    }

    /** {@link #displayNameViolation} as a bare user-facing message. */
    public static Optional< String > displayNameError( final Engine engine, final UserDatabase db, final String login,
            final String candidate ) {
        return displayNameViolation( engine, db, login, candidate ).map( NameViolation::message );
    }

    /**
     * A display name for the account {@code login} that satisfies every rule, for SSO and SCIM provisioning where a conflict
     * must never fail the sign-in. {@code preferred} (trimmed) when it passes; if it is merely <em>taken</em> (another
     * account's display name or login) the lowest {@code preferred + " " + n}, n &ge; 2, that passes; if it is
     * null/blank/email-shaped/reserved/malformed, the lowest {@code "Member " + n}, n &ge; 1, that passes. Never the login or
     * email.
     */
    public static String availableDisplayName( final Engine engine, final UserDatabase db, final String login,
            final String preferred ) {
        return DisplayNameAllocator.allocate( engine, db, login, preferred );
    }

    // ------------------------------------------------------------------ change validation

    /**
     * Validates a change of display names for the account {@code login}: a full name that changed (and was supplied) must
     * satisfy {@link #displayNameViolation}; a wiki name that changed must not be reserved or be another account's login.
     */
    public static Optional< NameViolation > nameChangeViolation( final Engine engine, final UserDatabase db,
            final String login, final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        return DisplayNameRules.changeViolation( engine, db, login, oldFull, oldWiki, newFull, newWiki );
    }

    /** {@link #nameChangeViolation} as a bare user-facing message (signature kept for existing callers). */
    public static Optional< String > nameChangeError( final Engine engine, final UserDatabase db, final String login,
            final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        return nameChangeViolation( engine, db, login, oldFull, oldWiki, newFull, newWiki ).map( NameViolation::message );
    }

    /**
     * Validates a full-name change through the self-service or admin profile editor. The wiki name is derived from the full
     * name with whitespace removed, so it is checked too.
     */
    public static Optional< NameViolation > fullNameChangeViolation( final Engine engine, final UserDatabase db,
            final UserProfile current, final String newFull ) {
        return nameChangeViolation( engine, db, current.getLoginName(), current.getFullname(), current.getWikiName(),
                newFull, derivedWikiName( newFull ) );
    }

    /** {@link #fullNameChangeViolation} as a bare user-facing message (signature kept for existing callers). */
    public static Optional< String > fullNameChangeError( final Engine engine, final UserDatabase db,
            final UserProfile current, final String newFull ) {
        return fullNameChangeViolation( engine, db, current, newFull ).map( NameViolation::message );
    }

    // ------------------------------------------------------------------ wiki names (unchanged behaviour)

    /**
     * A wiki name for the account {@code login} that no other account holds as its wiki or login
     * name and that is not reserved. An unchanged wiki name ({@code preferred} equals
     * {@code current}) is never re-checked. Returns {@code preferred} when it is free; otherwise keeps
     * {@code current} when it is a numbered form of {@code preferred} ("JohnSmith2"), so an
     * identity provider that re-sends the same name on every sync does not change it; otherwise
     * appends the lowest free number from 2.
     *
     * @param preferred the wiki name the write would set (derived from the full name, or given)
     * @param current   the account's stored wiki name, or {@code null} for a new account
     * @return a free wiki name, or {@code preferred} unchanged when it is {@code null} or blank
     */
    public static String availableWikiName( final Engine engine, final UserDatabase db, final String login,
            final String preferred, final String current ) {
        if ( db == null || preferred == null || preferred.isBlank() || preferred.equals( current )
                || wikiNameFree( engine, db, login, preferred ) ) {
            return preferred;
        }
        if ( current != null && current.length() > preferred.length() && current.startsWith( preferred )
                && current.substring( preferred.length() ).chars().allMatch( Character::isDigit )
                && wikiNameFree( engine, db, login, current ) ) {
            return current;
        }
        for ( int suffix = 2; suffix <= MAX_WIKI_NAME_SUFFIX; suffix++ ) {
            final String candidate = preferred + suffix;
            if ( wikiNameFree( engine, db, login, candidate ) ) {
                return candidate;
            }
        }
        LOG.warn( "No free wiki name found for account '{}' after {} numbered forms of '{}'; keeping it as given",
                login, MAX_WIKI_NAME_SUFFIX, preferred );
        return preferred;
    }

    /**
     * Sets {@code fullName} (trimmed) on {@code profile} and gives it the wiki name derived from it, numbered
     * when another account already holds that wiki name (see {@link #availableWikiName}). Call it
     * after {@link #nameChangeError} has accepted the name.
     */
    public static void applyFullName( final Engine engine, final UserDatabase db, final UserProfile profile,
            final String fullName ) {
        final String previousWiki = profile.getWikiName();
        profile.setFullname( fullName == null ? null : fullName.trim() );
        final String derived = derivedWikiName( fullName );
        final String wiki = availableWikiName( engine, db, profile.getLoginName(), derived, previousWiki );
        if ( !Objects.equals( wiki, derived ) ) {
            profile.setWikiName( wiki );
        }
    }

    /** The wiki name a profile derives from {@code fullName}: the full name with whitespace removed. */
    public static String derivedWikiName( final String fullName ) {
        return fullName == null ? null : fullName.replaceAll( "\\s", "" );
    }

    private static boolean wikiNameFree( final Engine engine, final UserDatabase db, final String login, final String name ) {
        return !ReservedProfileNames.isReserved( engine, name )
                && !otherAccount( db, login, () -> db.findByWikiName( name ) )
                && !isAnotherAccountsLogin( db, login, name );
    }

    /** True if {@code name} is the login name of an account other than {@code login}. */
    public static boolean isAnotherAccountsLogin( final UserDatabase db, final String login, final String name ) {
        if ( db == null || name == null || name.isBlank() ) {
            return false;
        }
        return otherAccount( db, login, () -> db.findByLoginName( name ) );
    }

    /**
     * True if {@code name} is the login, full or wiki name of an account other than {@code login}.
     * Used for group names, which must not be confused with any account; profile names use the
     * narrower {@link #nameChangeError}.
     */
    public static boolean usedByAnotherAccount( final UserDatabase db, final String login, final String name ) {
        if ( db == null || name == null || name.isBlank() ) {
            return false;
        }
        return otherAccount( db, login, () -> db.findByLoginName( name ) )
                || otherAccount( db, login, () -> db.findByFullName( name ) )
                || otherAccount( db, login, () -> db.findByWikiName( name ) );
    }

    @FunctionalInterface
    private interface Lookup {
        UserProfile find() throws NoSuchPrincipalException;
    }

    private static boolean otherAccount( final UserDatabase db, final String login, final Lookup lookup ) {
        try {
            final UserProfile found = lookup.find();
            return found != null && !Objects.equals( found.getLoginName(), login );
        } catch ( final NoSuchPrincipalException e ) {
            // Expected: no account carries this name.
            LOG.debug( "No account matches the proposed name: {}", e.getMessage() );
            return false;
        }
    }
}
```

**3b. New `wikantik-main/src/main/java/com/wikantik/auth/DisplayNameRules.java`** (package-private; licence header as in sibling files):

```java
package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.ProfileNameRules.NameViolation;
import com.wikantik.auth.user.UserDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * The display-name rules themselves. {@link ProfileNameRules} is the public face and delegates here; the split keeps both
 * classes under the PMD complexity gate.
 */
final class DisplayNameRules {

    private static final Logger LOG = LogManager.getLogger( DisplayNameRules.class );

    private DisplayNameRules() { }

    /** See {@link ProfileNameRules#displayNameViolation}. */
    static Optional< NameViolation > violation( final Engine engine, final UserDatabase db, final String login,
            final String candidate ) {
        final Optional< NameViolation > shape = shape( candidate );
        if ( shape.isPresent() ) {
            return shape;
        }
        final String name = candidate.trim();
        if ( ReservedProfileNames.isReserved( engine, name ) ) {
            return ProfileNameRules.invalid( "The name '" + name + "' is reserved for a role or group; choose a different name" );
        }
        if ( isLoginOfAnotherAccountIgnoreCase( db, login, name ) ) {
            return ProfileNameRules.taken( "The name '" + name + "' is another account's login name; choose a different name" );
        }
        if ( isInUse( db, login, name ) ) {
            return ProfileNameRules.taken( "The display name '" + name + "' is already in use; choose a different name" );
        }
        return Optional.empty();
    }

    /** See {@link ProfileNameRules#nameChangeViolation}. */
    static Optional< NameViolation > changeViolation( final Engine engine, final UserDatabase db, final String login,
            final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        if ( newFull != null && !sameName( oldFull, newFull ) ) {
            final Optional< NameViolation > violation = violation( engine, db, login, newFull );
            if ( violation.isPresent() ) {
                return violation;
            }
        }
        if ( newWiki == null || newWiki.isBlank() || sameName( oldWiki, newWiki ) ) {
            return Optional.empty();
        }
        final String wiki = newWiki.trim();
        if ( ReservedProfileNames.isReserved( engine, wiki ) ) {
            return ProfileNameRules.invalid( "The name '" + wiki + "' is reserved for a role or group; choose a different name" );
        }
        if ( ProfileNameRules.isAnotherAccountsLogin( db, login, wiki ) ) {
            return ProfileNameRules.taken( "The name '" + wiki + "' is another account's login name; choose a different name" );
        }
        return Optional.empty();
    }

    /** True when both names are equal after trimming, treating null and blank as the same "no name". */
    private static boolean sameName( final String a, final String b ) {
        return Objects.equals( blankToNull( trim( a ) ), blankToNull( trim( b ) ) );
    }

    private static String blankToNull( final String s ) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String trim( final String s ) {
        return s == null ? null : s.trim();
    }

    private static Optional< NameViolation > shape( final String candidate ) {
        if ( candidate == null || candidate.isBlank() ) {
            return ProfileNameRules.invalid( "A display name is required" );
        }
        final String name = candidate.trim();
        if ( name.length() < ProfileNameRules.DISPLAY_NAME_MIN || name.length() > ProfileNameRules.DISPLAY_NAME_MAX ) {
            return ProfileNameRules.invalid( "A display name must be " + ProfileNameRules.DISPLAY_NAME_MIN + " to "
                    + ProfileNameRules.DISPLAY_NAME_MAX + " characters long" );
        }
        if ( name.codePoints().anyMatch( Character::isISOControl ) ) {
            return ProfileNameRules.invalid( "A display name cannot contain control characters" );
        }
        if ( name.indexOf( '@' ) >= 0 ) {
            return ProfileNameRules.invalid( "A display name cannot contain '@'; it would look like an email address" );
        }
        return Optional.empty();
    }

    /**
     * True if {@code name} (or its whitespace-stripped, wiki-name form) equals, ignoring case, the login name of an account
     * other than {@code login}. An exact lookup runs first (cheap; cached by the JDBC store), then the store's
     * case-insensitive probe.
     */
    private static boolean isLoginOfAnotherAccountIgnoreCase( final UserDatabase db, final String login, final String name ) {
        if ( db == null ) {
            return false;
        }
        final String stripped = ProfileNameRules.derivedWikiName( name );
        if ( ProfileNameRules.isAnotherAccountsLogin( db, login, name )
                || ProfileNameRules.isAnotherAccountsLogin( db, login, stripped ) ) {
            return true;
        }
        try {
            return db.isLoginNameTakenIgnoreCase( name, login ) || db.isLoginNameTakenIgnoreCase( stripped, login );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Could not check display name '{}' against login names: {}", name, e.getMessage(), e );
            return false;
        }
    }

    /**
     * True if another account already holds {@code name} as its display name (case-insensitive, trimmed). A failing store
     * logs a WARN and answers {@code false}: the save that follows hits the same store and fails loudly.
     */
    private static boolean isInUse( final UserDatabase db, final String login, final String name ) {
        if ( db == null ) {
            return false;
        }
        try {
            return db.isFullNameTakenIgnoreCase( name, login );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Could not check display name '{}' for uniqueness: {}", name, e.getMessage(), e );
            return false;
        }
    }
}
```

**3c. New `wikantik-main/src/main/java/com/wikantik/auth/DisplayNameAllocator.java`** (package-private; licence header as in sibling files):

```java
package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.ProfileNameRules.NameViolation;
import com.wikantik.auth.user.UserDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Picks a display name that satisfies {@link DisplayNameRules} for SSO and SCIM provisioning, where a conflict must never fail
 * the sign-in. {@link ProfileNameRules#availableDisplayName} is the public face.
 */
final class DisplayNameAllocator {

    private static final Logger LOG = LogManager.getLogger( DisplayNameAllocator.class );

    /** Upper bound on numeric suffixes tried before falling back to a random placeholder. */
    private static final int MAX_SUFFIX = 1000;

    private DisplayNameAllocator() { }

    /** See {@link ProfileNameRules#availableDisplayName}. */
    static String allocate( final Engine engine, final UserDatabase db, final String login, final String preferred ) {
        final String base = preferred == null ? "" : preferred.trim();
        if ( !base.isEmpty() ) {
            final Optional< NameViolation > first = DisplayNameRules.violation( engine, db, login, base );
            if ( first.isEmpty() ) {
                return base;
            }
            if ( first.get().isConflict() ) {
                final String numbered = firstFreeNumbered( engine, db, login, base, 2 );
                if ( numbered != null ) {
                    return numbered;
                }
            }
        }
        final String placeholder = firstFreeNumbered( engine, db, login, "Member", 1 );
        if ( placeholder != null ) {
            return placeholder;
        }
        final String lastResort = "Member " + UUID.randomUUID().toString().substring( 0, 8 );
        LOG.warn( "No free numbered display name found for account '{}' after {} tries; using '{}'",
                login, MAX_SUFFIX, lastResort );
        return lastResort;
    }

    /** {@code stem + " " + n} for the lowest n &ge; {@code from} that passes the rules, the stem shortened to fit the limit. */
    private static String firstFreeNumbered( final Engine engine, final UserDatabase db, final String login,
            final String stem, final int from ) {
        for ( int n = from; n < from + MAX_SUFFIX; n++ ) {
            final String suffix = " " + n;
            final String head = stem.length() + suffix.length() > ProfileNameRules.DISPLAY_NAME_MAX
                    ? stem.substring( 0, ProfileNameRules.DISPLAY_NAME_MAX - suffix.length() ).stripTrailing()
                    : stem;
            final String candidate = head + suffix;
            if ( DisplayNameRules.violation( engine, db, login, candidate ).isEmpty() ) {
                return candidate;
            }
        }
        return null;
    }
}
```

**3d. `UserDatabase.java`** — add imports `java.util.Locale;` and `java.util.Objects;` (alphabetical, after `java.util.List`), then insert after the `findByFullName` declaration (line 133):

```java
    /**
     * True if an account <em>other than</em> {@code excludingLogin} already holds {@code fullName} as its full (display) name,
     * comparing case-insensitively and ignoring surrounding whitespace. A {@code null}/blank {@code fullName} is never taken.
     * Pass {@code excludingLogin = null} to ask about every account.
     *
     * <p>This default implementation scans {@link #findAllProfiles()}. A relational store should override it with a single
     * indexed query ({@code JDBCUserDatabase} does).</p>
     *
     * @throws WikiSecurityException if the profiles cannot be enumerated
     */
    default boolean isFullNameTakenIgnoreCase( final String fullName, final String excludingLogin ) throws WikiSecurityException {
        if ( fullName == null || fullName.isBlank() ) {
            return false;
        }
        final String wanted = fullName.trim().toLowerCase( Locale.ROOT );
        for ( final UserProfile profile : findAllProfiles() ) {
            final String other = profile.getFullname();
            if ( other != null && !Objects.equals( profile.getLoginName(), excludingLogin )
                    && other.trim().toLowerCase( Locale.ROOT ).equals( wanted ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if an account <em>other than</em> {@code excludingLogin} has {@code loginName} as its login name, comparing
     * case-insensitively (surrounding whitespace ignored). A {@code null}/blank {@code loginName} is never taken. Pass
     * {@code excludingLogin = null} to ask about every account.
     *
     * <p>This default implementation scans {@link #findAllProfiles()}; a relational store should override it with a single
     * query ({@code JDBCUserDatabase} does).</p>
     *
     * @throws WikiSecurityException if the profiles cannot be enumerated
     */
    default boolean isLoginNameTakenIgnoreCase( final String loginName, final String excludingLogin ) throws WikiSecurityException {
        if ( loginName == null || loginName.isBlank() ) {
            return false;
        }
        final String wanted = loginName.trim();
        for ( final UserProfile profile : findAllProfiles() ) {
            final String other = profile.getLoginName();
            if ( other != null && !other.equals( excludingLogin ) && other.equalsIgnoreCase( wanted ) ) {
                return true;
            }
        }
        return false;
    }
```

**3e. `JDBCUserDatabase.java`** — after the `RENAME_ROLES` constant (line 111) add:

```java
    private static final String FULL_NAME_TAKEN =
            "SELECT 1 FROM users WHERE lower(trim(full_name)) = lower(trim(?)) AND login_name <> ? LIMIT 1";
    private static final String LOGIN_NAME_TAKEN =
            "SELECT 1 FROM users WHERE lower(login_name) = lower(?) AND login_name <> ? LIMIT 1";
```
and after `findByFullName` (after line 178) add:

```java
    /**
     * {@inheritDoc}
     * <p>One indexed-style probe instead of the interface default's full scan. {@code login_name} is {@code NOT NULL}, so a
     * {@code null} exclusion binds as the empty string and excludes nobody.</p>
     */
    @Override
    public boolean isFullNameTakenIgnoreCase( final String fullName, final String excludingLogin ) throws WikiSecurityException {
        if ( fullName == null || fullName.isBlank() ) {
            return false;
        }
        try {
            return jdbc.queryOne( FULL_NAME_TAKEN, ps -> {
                ps.setString( 1, fullName );
                ps.setString( 2, excludingLogin == null ? "" : excludingLogin );
            }, rs -> rs.getInt( 1 ) ).isPresent();
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not check whether a display name is taken: " + e.getMessage(), e );
        }
    }

    /** {@inheritDoc} One query; the interface default would deserialize every account row. */
    @Override
    public boolean isLoginNameTakenIgnoreCase( final String loginName, final String excludingLogin ) throws WikiSecurityException {
        if ( loginName == null || loginName.isBlank() ) {
            return false;
        }
        try {
            return jdbc.queryOne( LOGIN_NAME_TAKEN, ps -> {
                ps.setString( 1, loginName.trim() );
                ps.setString( 2, excludingLogin == null ? "" : excludingLogin );
            }, rs -> rs.getInt( 1 ) ).isPresent();
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not check whether a login name is taken: " + e.getMessage(), e );
        }
    }
```

**3f. `UserProfileValidator.java`** (legacy `UserManager` path) — in `validate(...)` add a call after `validateReservedNames( session, profile, rb );`:

```java
        validateDisplayName( session, profile );
```
and add after `validateReservedNames`:

```java
    /**
     * Applies the display-name rules (length, control characters, {@code '@'}, login/duplicate collisions — see
     * {@link ProfileNameRules#displayNameViolation}) to a full name that differs from the stored one. A reserved name is
     * already reported by {@link #validateReservedNames}, and a missing one by {@link #validateRequiredFields}, so both are
     * skipped here to avoid a second message.
     */
    private void validateDisplayName( final Session session, final UserProfile profile ) {
        final String name = profile.getFullname();
        if ( name == null || name.isBlank() || ReservedProfileNames.isReserved( engine, name ) ) {
            return;
        }
        final UserProfile stored = storedProfile( profile );
        if ( stored != null && stored.getFullname() != null && name.trim().equals( stored.getFullname().trim() ) ) {
            return;
        }
        ProfileNameRules.displayNameViolation( engine, userDatabase.get(), profile.getLoginName(), name )
                .ifPresent( violation -> session.addMessage( SESSION_MESSAGES, violation.message() ) );
    }
```

**3g. `RestJson.java`** — add after `sendNotFound` (line ~104):

```java
    /**
     * Sends the error for a refused display name: {@code 409} when it collides with another account (a login or display
     * name already in use), {@code 400} for malformed or reserved input.
     */
    public static void sendNameViolation( final HttpServletResponse response,
            final com.wikantik.auth.ProfileNameRules.NameViolation violation ) throws IOException {
        sendError( response, violation.isConflict() ? HttpServletResponse.SC_CONFLICT : HttpServletResponse.SC_BAD_REQUEST,
                violation.message() );
    }
```

**3h. `AuthResource.handleUpdateProfile`** — replace lines 373-386:

```java
            // Update fullName (the display name) if provided
            final String fullName = getJsonString( body, "fullName" );
            if ( fullName != null ) {
                // A changed display name must satisfy the display-name rules (length, characters, not reserved, not another
                // account's login, unique ignoring case) and the wiki name derived from it must not be reserved or another
                // login; an unchanged name is not re-checked. 409 = collision, 400 = malformed.
                final java.util.Optional< ProfileNameRules.NameViolation > nameViolation =
                        ProfileNameRules.fullNameChangeViolation( engine, db, profile, fullName );
                if ( nameViolation.isPresent() ) {
                    RestJson.sendNameViolation( response, nameViolation.get() );
                    return;
                }
                ProfileNameRules.applyFullName( engine, db, profile, fullName );
            }
```

**3i. `AdminUserResource.handleCreateUser`** — replace lines 512-521 (`final UserDatabase db = getUserDatabase();` through `if ( fullName != null ) ProfileNameRules.applyFullName( getEngine(), db, profile, fullName );`):

```java
            final UserDatabase db = getUserDatabase();
            // The display name is required on create: a missing/blank one is a 400, a taken one a 409 (a null name used to
            // count as "unchanged" and slipped through). The rules also cover the wiki name derived from it.
            final java.util.Optional< ProfileNameRules.NameViolation > nameViolation =
                    ProfileNameRules.displayNameViolation( getEngine(), db, loginName, fullName );
            if ( nameViolation.isPresent() ) {
                RestJson.sendNameViolation( response, nameViolation.get() );
                return;
            }
            final UserProfile profile = db.newProfile();
            profile.setLoginName( loginName );
            ProfileNameRules.applyFullName( getEngine(), db, profile, fullName );
```

**3j. `AdminUserResource.handleUpdateUser`** — replace the block at lines 564-572:

```java
            if ( fullName != null ) {
                final java.util.Optional< ProfileNameRules.NameViolation > nameViolation =
                        ProfileNameRules.fullNameChangeViolation( getEngine(), db, profile, fullName );
                if ( nameViolation.isPresent() ) {
                    RestJson.sendNameViolation( response, nameViolation.get() );
                    return;
                }
                ProfileNameRules.applyFullName( getEngine(), db, profile, fullName );
            }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main \
  -Dtest='ProfileNameRulesTest,JDBCUserDatabaseTest,UserManagerTest,SharedWikiNameJdbcTest,AclUserEntryJdbcTest,DefaultUserManager*Test*,SSOAutoProvisionService*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: all pass (the SSO tests still pass at this point: `SSOAutoProvisionService` has not changed yet; Task 2 changes them). `JDBCUserDatabaseTest` needs Docker.

Install the changed module and run the REST tests:

```bash
bin/agent-build.sh start inst-main -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait inst-main 540
mvn -B -o test -pl wikantik-rest -Dtest='AuthResourceTest,AdminUserResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest
```
Expected: pass; PMD gate reports no *new* violation (if `ProfileNameRules` or `UserProfileValidator` trips a complexity rule, extract the offending block into a private method rather than raising the baseline).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/ProfileNameRules.java \
  wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java \
  wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
  wikantik-main/src/main/java/com/wikantik/auth/UserProfileValidator.java \
  wikantik-rest/src/main/java/com/wikantik/rest/RestJson.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java \
  wikantik-main/src/test/java/com/wikantik/auth/ProfileNameRulesTest.java \
  wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java \
  wikantik-main/src/test/java/com/wikantik/auth/UserManagerTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java
git commit -m "feat(auth): unique, validated display names on every profile write path (REST 400/409)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: SSO provisioning — display name from the IdP claim, never the email

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java` (import line 27; `provisionIfNeeded` line 97; remove `displayName`/`MAX_NAME_SUFFIX`/`usableName`, lines 131-165; new `describeChoice`)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOAutoProvisionServiceTest.java`

**Interfaces:**
- Consumes (Task 1): `ProfileNameRules.availableDisplayName(Engine, UserDatabase, String login, String preferred)`.
- Produces: `static String SSOAutoProvisionService.describeChoice(String claimed, String chosen)` (package-private, for the INFO line and its test). Behaviour change: the provisioned `full_name` is now (a) the IdP name claim when it satisfies the rules, (b) `claim + " " + n` (n ≥ 2) when the claim is only *taken* (another account's display name or login, ignoring case), (c) `Member N` (smallest free N) when the claim is absent, email-shaped, reserved or malformed. It is never the login/email and is never re-synced on later logins (unchanged: `provisionIfNeeded` returns early when the profile exists).

- [ ] **Step 1: Write the failing tests**

In `SSOAutoProvisionServiceTest.java` first add shared helpers (after `setUp`) and the imports `org.junit.jupiter.api.Assertions` is already imported; no new imports are needed beyond what the file has (`Properties`, `CommonProfile`, `TestEngine`, `UserManager`, `UserDatabase`, `UserProfile`):

```java
    private SSOAutoProvisionService service() {
        final Properties props = new Properties();
        props.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        props.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        return new SSOAutoProvisionService( engine, new SSOConfig( props, "http://localhost:8080/JSPWiki/sso/callback" ) );
    }

    private UserDatabase userDb() {
        return engine.getManager( UserManager.class ).getUserDatabase();
    }

    /** Provisions {@code login} with the given IdP name claim (may be null) and returns the stored display name. */
    private String provisioned( final String login, final String nameClaim ) throws Exception {
        final CommonProfile profile = new CommonProfile();
        profile.setId( login );
        if ( nameClaim != null ) {
            profile.addAttribute( "name", nameClaim );
        }
        service().provisionIfNeeded( login, login, profile );
        return userDb().findByLoginName( login ).getFullname();
    }

    private void saveLocalAccount( final String login, final String fullName ) throws Exception {
        final UserProfile p = userDb().newProfile();
        p.setLoginName( login );
        com.wikantik.auth.ProfileNameRules.applyFullName( engine, userDb(), p, fullName );
        p.setEmail( login + "@example.test" );
        userDb().save( p );
    }
```

Update the four existing tests whose expectations change and add the new ones (each `TestEngine` is fresh per test, so `Member 1` is deterministic):

```java
    // was testProvisionWithMissingFullNameUsesLoginName — the login (an email on prod) must never become the name
    @Test
    void testProvisionWithMissingNameClaimGetsAPlaceholder() throws Exception {
        Assertions.assertEquals( "Member 1", provisioned( "sso-noname", null ) );
        Assertions.assertEquals( "Member 2", provisioned( "sso-noname-2", null ), "smallest free N" );
    }

    // was testProvisionReplacesReservedDisplayName — a reserved claim is unusable; it no longer falls back to the login
    @Test
    void testProvisionReplacesReservedDisplayNameWithAPlaceholder() throws Exception {
        Assertions.assertEquals( "Member 1", provisioned( "sso-reserved", "Admin" ) );
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> userDb().findByWikiName( "Admin" ) );
    }

    @Test
    void testProvisionClaimUsedAsIsWhenFree() throws Exception {
        Assertions.assertEquals( "Jane Q. Public", provisioned( "sso-free", "  Jane Q. Public " ), "trimmed claim" );
    }

    @Test
    void testProvisionTakenClaimIsNumberedIgnoringCaseAndWhitespace() throws Exception {
        saveLocalAccount( "jf-local", "Jake Fear" );
        Assertions.assertEquals( "Jake Fear 2", provisioned( "sso-jf2", "Jake Fear" ) );
        Assertions.assertEquals( "Jake Fear 3", provisioned( "sso-jf3", "Jake Fear" ) );
        Assertions.assertEquals( "jake fear 4", provisioned( "sso-jf4", " jake fear " ),
                "case-insensitive clash; the claim keeps its own spelling in the numbered form" );
    }

    @Test
    void testProvisionEmailShapedClaimIsUnusable() throws Exception {
        final String name = provisioned( "sso-email-claim", "jane.doe@example.com" );
        Assertions.assertEquals( "Member 1", name );
        Assertions.assertFalse( name.contains( "@" ) );
    }

    @Test
    void testProvisionClaimEqualToAnotherLoginIsNumberedCaseInsensitively() throws Exception {
        saveLocalAccount( "ssoboss", "Sso Boss" );
        Assertions.assertEquals( "SSOBoss 2", provisioned( "sso-claimer", "SSOBoss" ) );
    }

    @Test
    void testProvisionNeverUsesAnEmailLoginAsTheDisplayName() throws Exception {
        final String name = provisioned( "someone@example.com", null );
        Assertions.assertEquals( "Member 1", name );
        Assertions.assertNotEquals( "someone@example.com", name );
    }

    // was testProvisionDeduplicatesCollidingWikiName — two accounts may no longer share a name; the second is numbered
    @Test
    void testProvisionDeduplicatesCollidingDisplayNameAndWikiName() throws Exception {
        saveLocalAccount( "jakefear-local", "Jake Fear" );
        Assertions.assertEquals( "JakeFear", userDb().findByLoginName( "jakefear-local" ).getWikiName() );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "114291954688138286475" );
        profile.addAttribute( "email", "jakefear@simpleagility.com" );
        profile.addAttribute( "name", "Jake Fear" );
        service().provisionIfNeeded( "jakefear@simpleagility.com", "114291954688138286475", profile );

        final UserProfile created = userDb().findByLoginName( "jakefear@simpleagility.com" );
        Assertions.assertNotNull( created, "SSO profile must be persisted despite the name clash." );
        Assertions.assertEquals( "Jake Fear 2", created.getFullname() );
        Assertions.assertEquals( "jakefear@simpleagility.com", userDb().findByWikiName( "JakeFear2" ).getLoginName(),
                "the numbered display name derives a free wiki name" );
        Assertions.assertEquals( "jakefear-local", userDb().findByWikiName( "JakeFear" ).getLoginName() );
    }

    @Test
    void testDescribeChoiceExplainsEachBranch() {
        Assertions.assertEquals( "no name claim from the identity provider; placeholder",
                SSOAutoProvisionService.describeChoice( null, "Member 1" ) );
        Assertions.assertEquals( "no name claim from the identity provider; placeholder",
                SSOAutoProvisionService.describeChoice( "   ", "Member 1" ) );
        Assertions.assertEquals( "name claim used as-is",
                SSOAutoProvisionService.describeChoice( " Jane Doe ", "Jane Doe" ) );
        Assertions.assertEquals( "name claim was taken; numbered",
                SSOAutoProvisionService.describeChoice( "Jane Doe", "Jane Doe 2" ) );
        Assertions.assertEquals( "name claim unusable (reserved, email-shaped or malformed); placeholder",
                SSOAutoProvisionService.describeChoice( "Admin", "Member 1" ) );
    }
```

Delete the old `testProvisionWithMissingFullNameUsesLoginName`, `testProvisionReplacesReservedDisplayName` and `testProvisionDeduplicatesCollidingWikiName` (replaced above). Keep `testProvisionNewUser`, `testProvisionExistingUserDoesNotOverwrite`, `testProvisionDisabledDoesNothing`, `testProvisionNameClaimEqualToAnotherAccountIsSuffixed` (still "ssoboss 2") and `testProvisionReservedLoginNameGetsNonReservedDisplayName` unchanged — they remain correct.

- [ ] **Step 2: Run them to verify they fail**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='SSOAutoProvisionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: compile error on `SSOAutoProvisionService.describeChoice`; once that exists, `testProvisionWithMissingNameClaimGetsAPlaceholder` fails with `expected: <Member 1> but was: <sso-noname>` and `testProvisionDeduplicatesCollidingDisplayNameAndWikiName` with `expected: <Jake Fear 2> but was: <Jake Fear>`.

- [ ] **Step 3: Implement**

In `SSOAutoProvisionService.java`:

1. Remove the now-unused import `com.wikantik.auth.ReservedProfileNames` (line 27). `ProfileNameRules` stays imported.

2. Replace line 97 (the single statement `profile.setFullname( displayName( userDb, loginName, resolveAttribute( ssoProfile, ssoConfig.getClaimFullName() ) ) );`) with:

```java
            final String claimed = resolveAttribute( ssoProfile, ssoConfig.getClaimFullName() );
            final String displayName = ProfileNameRules.availableDisplayName( engine, userDb, loginName, claimed );
            profile.setFullname( displayName );
            LOG.info( "SSO account {}: display name '{}' ({})", loginName, displayName, describeChoice( claimed, displayName ) );
```

3. Delete lines 131-165 — the Javadoc and private method `displayName(...)`, the constant `MAX_NAME_SUFFIX` with its comment, and `usableName(...)` — and add in their place:

```java
    /**
     * Why {@code chosen} was picked, for the provisioning INFO line (names only — never a secret). Package-private for tests.
     *
     * @param claimed the raw IdP name claim, possibly {@code null}
     * @param chosen  the display name {@link ProfileNameRules#availableDisplayName} returned
     */
    static String describeChoice( final String claimed, final String chosen ) {
        final String base = claimed == null ? "" : claimed.trim();
        if ( base.isEmpty() ) {
            return "no name claim from the identity provider; placeholder";
        }
        if ( chosen.equals( base ) ) {
            return "name claim used as-is";
        }
        if ( chosen.startsWith( "Member " ) ) {
            return "name claim unusable (reserved, email-shaped or malformed); placeholder";
        }
        return "name claim was taken; numbered";
    }
```

(The existing wiki-name line `profile.setWikiName( ProfileNameRules.availableWikiName( ... ) )` stays — the numbered display name usually derives a free wiki name, and the call still guards the rare collision.)

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='SSOAutoProvisionService*Test,SSOLoginModule*Test,LogLevelEnforcementTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main
```
Expected: pass (`LogLevelEnforcementTest` confirms no unjustified `LOG.error` was added).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java \
  wikantik-main/src/test/java/com/wikantik/auth/sso/SSOAutoProvisionServiceTest.java
git commit -m "feat(sso): provision a unique display name from the IdP claim, never the email

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: SCIM — `name.formatted` through the display-name rules

**Files:**
- Modify: `wikantik-scim/src/main/java/com/wikantik/scim/ScimUserFields.java` (`applyCommonCreateFields` line 49; `applyPatchAttributes` line 69; `createNameError`/`patchNameError`/`nameError`, lines 76-105; new `withProvisionedName` and `sendNameViolation`; two imports)
- Modify: `wikantik-scim/src/main/java/com/wikantik/scim/ScimUserResource.java` (`handleCreate` lines 108-145; `handleReplace` lines 272-277; `handlePatch` lines 334-339; import `com.wikantik.auth.ProfileNameRules`)
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimUserResourceTest.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimSharedNameJdbcTest.java`

**Interfaces:**
- Consumes (Task 1): `ProfileNameRules.nameChangeViolation(...)`, `ProfileNameRules.NameViolation` (`isConflict()`, `message()`), `ProfileNameRules.availableDisplayName(...)`.
- Produces: SCIM error mapping — a *collision* (another account's login or display name, case-insensitive) is `409` / `scimType: uniqueness`; a *malformed or reserved* `name.formatted` is `400` / `scimType: invalidValue` (previously every name problem was 409). A create with no (or blank) name gets the `Member N` placeholder (like SSO) so no SCIM-provisioned account has a blank display name. Unchanged names re-sent by an IdP sync (PUT/PATCH) are not re-validated. `name.formatted` is stored trimmed.

SCIM `displayName` (which maps to the *wiki name*, not the display name) keeps its existing reserved/other-login checks through the wiki-name half of `nameChangeViolation`.

- [ ] **Step 1: Write the failing tests**

In `ScimUserResourceTest.java` add the imports `org.junit.jupiter.params.ParameterizedTest` and `org.junit.jupiter.params.provider.ValueSource`. Rewrite the two tests whose premise flipped and add the new ones.

Replace `createSecondUserWithSameFullName_returns201WithFreeWikiName` (lines 1008-1038) with:

```java
    @Test
    void createSecondUserWithSameFullName_returns409Uniqueness() throws Exception {
        // Display names are unique (case-insensitive); the IdP must send a distinct name.
        doThrow( new NoSuchPrincipalException( "jsmith2" ) ).when( mockDb ).findByLoginName( "jsmith2" );
        when( mockDb.isFullNameTakenIgnoreCase( "John Smith", "jsmith2" ) ).thenReturn( true );

        final String body = "{\"userName\":\"jsmith2\",\"name\":{\"formatted\":\"John Smith\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        assertTrue( sw.toString().contains( "uniqueness" ), sw.toString() );
        assertTrue( sw.toString().contains( "already in use" ), sw.toString() );
        verify( mockDb, never() ).save( any() );
    }

    @Test
    void createSecondUserWhoseFullNameOnlyDerivesTheSameWikiName_returns201WithFreeWikiName() throws Exception {
        // "John  Smith" (two spaces) is a different display name from "John Smith" but derives the same wiki name:
        // the wiki name is numbered, the account is created.
        final UserProfile first = mock( UserProfile.class );
        when( first.getLoginName() ).thenReturn( "jsmith" );
        when( mockDb.findByWikiName( "JohnSmith" ) ).thenReturn( first );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-jsmith2" );
        when( saved.getLoginName() ).thenReturn( "jsmith2" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        doThrow( new NoSuchPrincipalException( "jsmith2" ) ).doReturn( saved ).when( mockDb ).findByLoginName( "jsmith2" );

        final String body = "{\"userName\":\"jsmith2\",\"name\":{\"formatted\":\"John  Smith\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        verify( newProfile ).setFullname( "John  Smith" );
        verify( newProfile ).setWikiName( "JohnSmith2" );
        verify( mockDb ).save( newProfile );
    }
```

Replace `patchFullNameSharedWithAnotherAccount_isSaved` (lines 1070-1096) with:

```java
    @Test
    void patchFullNameTakenByAnotherAccount_returns409AndDoesNotSave() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-helen" );
        when( mockDb.isFullNameTakenIgnoreCase( "Helen Smith", "helen" ) ).thenReturn( true );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "helen" );
        when( p.getFullname() ).thenReturn( "Helen" );
        when( p.getWikiName() ).thenReturn( "Helen" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-helen" ) ).thenReturn( p );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"formatted\":\"Helen Smith\"}}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.service( req, resp );

        verify( resp ).setStatus( 409 );
        assertTrue( sw.toString().contains( "uniqueness" ), sw.toString() );
        verify( p, never() ).setFullname( any() );
        verify( mockDb, never() ).save( any() );
    }
```

In `putResendingSharedDisplayName_keepsSuffixedWikiName` (line ~1043) add, right after `when( mockDb.findByFullName( "John Smith" ) ).thenReturn( first );`, the line
`when( mockDb.isFullNameTakenIgnoreCase( "John Smith", "jsmith2" ) ).thenReturn( true );` — the stored name is unchanged, so an IdP re-sync must still succeed even though the name is (legacy-)shared. The assertions are unchanged.

Add the new tests:

```java
    @ParameterizedTest
    @ValueSource( strings = { "x", "mallory@example.com" } )
    void createWithMalformedName_returns400InvalidValue( final String formatted ) throws Exception {
        doThrow( new NoSuchPrincipalException( "malformed" ) ).when( mockDb ).findByLoginName( "malformed" );

        final String body = "{\"userName\":\"malformed\",\"name\":{\"formatted\":\"" + formatted + "\"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "invalidValue" ), sw.toString() );
        verify( mockDb, never() ).save( any() );
    }

    @Test
    void createWithoutAName_getsTheMemberPlaceholder() throws Exception {
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.newProfile() ).thenReturn( newProfile );
        final UserProfile saved = mock( UserProfile.class );
        when( saved.getUid() ).thenReturn( "uid-nameless" );
        when( saved.getLoginName() ).thenReturn( "nameless" );
        when( saved.getAttributes() ).thenReturn( new HashMap<>() );
        doThrow( new NoSuchPrincipalException( "nameless" ) ).doReturn( saved ).when( mockDb ).findByLoginName( "nameless" );

        final String body = "{\"userName\":\"nameless\",\"emails\":[{\"value\":\"nameless@example.test\"}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 201 );
        verify( newProfile ).setFullname( "Member 1" );
    }

    @Test
    void putBlankNameWhenOneIsStored_returns400Required() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/uid-ivan" );
        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ivan" );
        when( p.getFullname() ).thenReturn( "Ivan Real" );
        when( p.getWikiName() ).thenReturn( "IvanReal" );
        when( p.isLocked() ).thenReturn( false );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-ivan" ) ).thenReturn( p );

        final String body = "{\"userName\":\"ivan\",\"name\":{\"formatted\":\"   \"}}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( sw.toString().contains( "required" ), sw.toString() );
        verify( mockDb, never() ).save( any() );
    }
```

In `ScimSharedNameJdbcTest.java` (real PostgreSQL) replace `secondJohnSmithIsCreatedAndKeepsItsNumberedWikiNameAcrossResync` (lines 123-146) with the version below (the "two people called John Smith" premise is gone; wiki-name numbering is still exercised through a name that differs only in internal spacing). Add `import static org.junit.jupiter.api.Assertions.assertTrue;`.

```java
    @Test
    void sameFullNameIsRefusedButASpacingVariantKeepsItsNumberedWikiNameAcrossResync() throws Exception {
        create( PREFIX + "js1", "{\"userName\":\"" + PREFIX + "js1\",\"name\":{\"formatted\":\"John Smith\"}}" );

        // The same display name (any case, any surrounding whitespace) is a uniqueness conflict.
        final Reply clash = call( "POST", null,
                "{\"userName\":\"" + PREFIX + "js0\",\"name\":{\"formatted\":\"  john SMITH \"}}" );
        verify( clash.resp() ).setStatus( 409 );
        assertTrue( clash.body().toString().contains( "uniqueness" ), clash.body().toString() );

        // A different display name (two spaces) that derives the same wiki name is created; the wiki name is numbered.
        final Reply second = call( "POST", null,
                "{\"userName\":\"" + PREFIX + "js2\",\"name\":{\"formatted\":\"John  Smith\"}}" );
        verify( second.resp() ).setStatus( 201 );
        assertEquals( "John  Smith", second.body().getAsJsonObject( "name" ).get( "formatted" ).getAsString() );
        assertEquals( "JohnSmith2", second.body().get( "displayName" ).getAsString(),
                "the 201 reports the stored, numbered wiki name" );
        final String id = second.body().get( "id" ).getAsString();

        // The IdP re-sends the same name on its next sync: unchanged, so neither re-validated nor re-numbered.
        final Reply put = call( "PUT", "/" + id, "{\"userName\":\"" + PREFIX + "js2\",\"name\":{\"formatted\":\"John  Smith\"},"
                + "\"emails\":[{\"value\":\"js2@example.test\",\"primary\":true}]}" );
        assertEquals( "JohnSmith2", put.body().get( "displayName" ).getAsString(), put.body().toString() );
        assertEquals( "JohnSmith2", db.findByLoginName( PREFIX + "js2" ).getWikiName() );
        assertEquals( PREFIX + "js1", db.findByWikiName( "JohnSmith" ).getLoginName() );

        final Reply patch = call( "PATCH", "/" + id,
                "{\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"formatted\":\"John  Smith\"}}]}" );
        assertEquals( "JohnSmith2", patch.body().get( "displayName" ).getAsString(), patch.body().toString() );
    }
```

(`sharedDisplayNameIsNumberedAndKeptOnResend`, which exercises SCIM `displayName` = wiki name with no `name`, stays as-is: the placeholder full name does not affect the wiki name because the explicit `displayName` overrides the derived one.)

- [ ] **Step 2: Run them to verify they fail**

```bash
cd /home/jakefear/source/jspwiki
bin/agent-build.sh start inst-main -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait inst-main 540
mvn -B -o test -pl wikantik-scim -Dtest='ScimUserResourceTest,ScimSharedNameJdbcTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: three red tests. `createWithMalformedName_returns400InvalidValue` (both cases: status `409` instead of `400` — every name problem is still mapped to `uniqueness`), `createWithoutAName_getsTheMemberPlaceholder` (`setFullname("Member 1")` is never invoked; the profile is created with no full name) and `putBlankNameWhenOneIsStored_returns400Required` (status `409` instead of `400`). The other new tests (`createSecondUserWithSameFullName_returns409Uniqueness`, the spacing-variant create, `patchFullNameTakenByAnotherAccount_returns409AndDoesNotSave`, the JDBC clash) already pass at this point because Task 1 changed the shared `nameChangeError` rule that the unchanged SCIM code still calls — they pin the behaviour so the refactor below cannot lose it.

- [ ] **Step 3: Implement**

**3a. `ScimUserFields.java`**

Trim stored names — in `applyCommonCreateFields` change the first line to

```java
        if ( f.fullName() != null ) p.setFullname( f.fullName().trim() );
```
and in `applyPatchAttributes` change the `name.formatted` assignment to

```java
        dirty |= assign( stringOrNull( objectOrNull( attrs, "name" ), "formatted" ), v -> p.setFullname( v.trim() ) );
```

Add `import jakarta.servlet.http.HttpServletResponse;` and `import java.io.IOException;` to `ScimUserFields`. Replace `createNameError`, `patchNameError` and the private `nameError` (lines 76-105, from the `createNameError` Javadoc through the closing brace of `nameError`) with:

```java
    /**
     * Name check for a create or replace: a changed full name ({@code name.formatted}) must satisfy the display-name rules
     * and a changed wiki name ({@code displayName}) must not be reserved or be another account's login name. An unchanged
     * name is not re-checked, so an identity provider that re-sends the same name on every sync is never refused. Another
     * account may still share the <em>wiki</em> name; see {@link #keepWikiNameUnique}.
     *
     * @param current the existing profile, or {@code null} on create
     */
    static Optional< ProfileNameRules.NameViolation > createNameViolation( final Engine engine, final UserDatabase db,
            final String login, final UserProfile current, final ScimUserMapper.CreateFields f ) {
        return nameViolation( engine, db, login, current, f.fullName(), f.displayName() );
    }

    /** Name check for a PATCH; see {@link #createNameViolation}. */
    static Optional< ProfileNameRules.NameViolation > patchNameViolation( final Engine engine, final UserDatabase db,
            final UserProfile current, final JsonObject attrs ) {
        return nameViolation( engine, db, current.getLoginName(), current,
                stringOrNull( objectOrNull( attrs, "name" ), "formatted" ), stringOrNull( attrs, "displayName" ) );
    }

    private static Optional< ProfileNameRules.NameViolation > nameViolation( final Engine engine, final UserDatabase db,
            final String login, final UserProfile current, final String newFull, final String newDisplay ) {
        final String oldFull = current == null ? null : current.getFullname();
        final String oldWiki = current == null ? null : current.getWikiName();
        // Mirrors the profile: setting the full name derives the wiki name, and an explicit
        // displayName (applied after it) overrides that derived value.
        final String proposed = proposedWiki( newFull, newDisplay );
        final String proposedWiki = proposed != null ? proposed : oldWiki;
        final String proposedFull = newFull != null ? newFull : oldFull;
        return ProfileNameRules.nameChangeViolation( engine, db, login, oldFull, oldWiki, proposedFull, proposedWiki );
    }

    /**
     * On create, an absent or blank {@code name.formatted} gets the neutral {@code Member N} display name (never the
     * {@code userName}, which is usually an email), so no SCIM-provisioned account is left without one. Any supplied name
     * is returned untouched and validated by {@link #createNameViolation}.
     */
    static ScimUserMapper.CreateFields withProvisionedName( final Engine engine, final UserDatabase db, final String login,
            final ScimUserMapper.CreateFields f ) {
        if ( f.fullName() != null && !f.fullName().isBlank() ) {
            return f;
        }
        return new ScimUserMapper.CreateFields( f.userName(), f.externalId(),
                ProfileNameRules.availableDisplayName( engine, db, login, null ),
                f.email(), f.displayName(), f.active(), f.password() );
    }

    /**
     * A refused name: a collision with another account (login or display name) is SCIM {@code uniqueness} (409); a
     * malformed or reserved one is {@code invalidValue} (400).
     */
    static void sendNameViolation( final HttpServletResponse resp, final ProfileNameRules.NameViolation violation )
            throws IOException {
        if ( violation.isConflict() ) {
            AbstractScimServlet.sendError( resp, 409, "uniqueness", violation.message() );
        } else {
            AbstractScimServlet.sendError( resp, 400, "invalidValue", violation.message() );
        }
    }
```

**3b. `ScimUserResource.java`**

Add `import com.wikantik.auth.ProfileNameRules;` with the other `com.wikantik.auth` imports. (The `sendNameViolation` helper lives in `ScimUserFields` — see 3a — because adding a method to `ScimUserResource` pushes it over the PMD `GodClass` threshold; its three call sites below are `ScimUserFields.sendNameViolation( resp, … )`.)

In `handleCreate` replace lines 108-145 (from `final ScimUserMapper.CreateFields f = ScimUserMapper.readCreate( body );` through the closing brace of the `createNameError` block) with — the raw parse is renamed `requested` so the provisioned copy can be the final `f` used by the rest of the method:

```java
        final ScimUserMapper.CreateFields requested = ScimUserMapper.readCreate( body );
        if ( requested.userName() == null || requested.userName().isBlank() ) {
            sendError( resp, 400, "invalidValue", "userName is required" );
            return;
        }

        final UserDatabase db = getUserDatabase();
        if ( db == null ) {
            sendError( resp, 503, null, "user database unavailable" );
            return;
        }

        // SSO fail-closed: if a profile exists for this userName AND it lacks sso.subject → 409
        try {
            final UserProfile existing = db.findByLoginName( requested.userName() );
            final Object linked = existing.getAttributes() == null ? null
                    : existing.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT );
            if ( linked == null ) {
                sendError( resp, 409, "uniqueness",
                        "A local account with userName '" + requested.userName()
                                + "' already exists and is not SSO-linked; SCIM cannot claim it." );
                return;
            }
            // Already exists and is SSO-linked — treat as idempotent conflict too (caller should PUT/PATCH)
            sendError( resp, 409, "uniqueness",
                    "User '" + requested.userName() + "' already exists." );
            return;
        } catch ( final NoSuchPrincipalException ignored ) {
            LOG.debug( "No existing account for '{}'; proceeding to create it: {}", requested.userName(), ignored.getMessage() );
            // Expected — no existing account; proceed to create
        }

        // An absent/blank name gets the "Member N" placeholder; a supplied one is validated below.
        final ScimUserMapper.CreateFields f = ScimUserFields.withProvisionedName( engine, db, requested.userName(), requested );
        final java.util.Optional< ProfileNameRules.NameViolation > createNameViolation =
                ScimUserFields.createNameViolation( engine, db, f.userName(), null, f );
        if ( createNameViolation.isPresent() ) {
            ScimUserFields.sendNameViolation( resp, createNameViolation.get() );
            return;
        }
```

In `handleReplace` replace lines 272-277 (the `readCreate` line 271 stays):

```java
        final java.util.Optional< ProfileNameRules.NameViolation > replaceNameViolation =
                ScimUserFields.createNameViolation( engine, db, p.getLoginName(), p, f );
        if ( replaceNameViolation.isPresent() ) {
            ScimUserFields.sendNameViolation( resp, replaceNameViolation.get() );
            return;
        }
```
In `handlePatch` replace lines 334-339:

```java
            final java.util.Optional< ProfileNameRules.NameViolation > patchNameViolation =
                    ScimUserFields.patchNameViolation( engine, db, p, patch.attributes() );
            if ( patchNameViolation.isPresent() ) {
                ScimUserFields.sendNameViolation( resp, patchNameViolation.get() );
                return;
            }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-scim -Dtest='Scim*Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-scim
```
Expected: pass (`ScimSharedNameJdbcTest` needs Docker). Also confirm the SCIM ITs still use distinct `name.formatted` values (`ScimUsersIT`: "SCIM IT User", `ScimDeactivationAuthIT`: "SCIM Deactivation User", `ScimGroupsIT`: the userName) — a leftover account from an aborted earlier IT run would now 409; the ITs' teardown deletes their users.

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimUserFields.java \
  wikantik-scim/src/main/java/com/wikantik/scim/ScimUserResource.java \
  wikantik-scim/src/test/java/com/wikantik/scim/ScimUserResourceTest.java \
  wikantik-scim/src/test/java/com/wikantik/scim/ScimSharedNameJdbcTest.java
git commit -m "feat(scim): name.formatted obeys the display-name rules (409 uniqueness / 400 invalidValue)

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: A profile save refreshes the user's live session

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/auth/ProfileChangeNotifier.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiSession.java` (new `refreshUserProfile()` after `injectUserProfilePrincipals`, line ~451)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/SessionMonitor.java` (new `refreshProfile(String)` after `userPrincipals()`, line ~211; import `com.wikantik.WikiSession`)
- Modify (call sites, one line each, immediately after the successful `save`):
  - `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` (`handleUpdateProfile`, after `db.save( profile )` ~line 411)
  - `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java` (`handleCreateUser` after `db.save( profile )`; `handleUpdateUser` after `db.save( profile )`)
  - `wikantik-scim/src/main/java/com/wikantik/scim/ScimUserResource.java` (`handleCreate` after `db.save( p )`; `handleReplace` after the active-transition `if/else`; `handlePatch` inside `if ( dirty )`)
  - `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java` (after `userDb.save( profile )`)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/ProfileSessionRefreshTest.java` (new)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java`, `AuthResourceTest.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimUserResourceTest.java`

**Interfaces:**
- Consumes: `WikiSession#injectUserProfilePrincipals()` (existing, `protected`), `SessionMonitor` (per-engine map of live `Session`s keyed by HTTP session id).
- Produces:
  - `public void WikiSession.refreshUserProfile()` — for an authenticated session: drops the stale `FULL_NAME`/`WIKI_NAME` `WikiPrincipal`s, resets the user principal to the login principal, re-reads the profile and re-injects (so `getUserPrincipal()` becomes the *new* full name). Login principal, roles and group principals are untouched. No-op when not authenticated.
  - `public final int SessionMonitor.refreshProfile(String loginName)` — refreshes every live authenticated `WikiSession` whose login principal is `loginName`; returns how many.
  - `public static void ProfileChangeNotifier.profileSaved(Engine, String loginName)` — the **single call** every profile-writing path makes; never throws (logs WARN). **Task 5 adds the `PublicIdentity.invalidate` call inside this method** and a sibling `profileDeleted`, so no call site is touched again.

Why a notifier and not an event: REST/SCIM/SSO write the profile through `db.save(...)` directly, so `DefaultUserManager`'s `PROFILE_SAVE`/`PROFILE_NAME_CHANGED` events never fire for them (they exist only for the legacy `setUserProfile` path). The live session therefore kept the old name until re-login.

- [ ] **Step 1: Write the failing tests**

(a) New `wikantik-main/src/test/java/com/wikantik/auth/ProfileSessionRefreshTest.java`:

```java
package com.wikantik.auth;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A profile saved outside the user's own session must show up in that user's live session at once. */
class ProfileSessionRefreshTest {

    private TestEngine engine;
    private SessionMonitor monitor;
    private UserDatabase db;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        monitor = SessionMonitor.getInstance( engine );
        db = engine.getManager( UserManager.class ).getUserDatabase();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private Session login( final String sessionId, final String login, final String password ) throws Exception {
        final Session session = monitor.find( sessionId );
        engine.getManager( AuthenticationManager.class ).login( session, HttpMockFactory.createHttpRequest(), login, password );
        assertTrue( session.isAuthenticated(), login + " must be authenticated" );
        return session;
    }

    private void rename( final String login, final String newFullName ) throws Exception {
        final UserProfile p = db.findByLoginName( login );
        p.setFullname( newFullName );
        db.save( p );
    }

    private static Set< String > names( final Session session ) {
        return Arrays.stream( session.getPrincipals() ).map( Principal::getName ).collect( Collectors.toSet() );
    }

    @Test
    void refreshProfileUpdatesThatUsersLivePrincipalsOnly() throws Exception {
        final Session fred = login( "refresh-fred", Users.FRED, Users.FRED_PASS );   // "Fred Flintstone" / FredFlintstone
        final Session bob = login( "refresh-bob", Users.BOB, Users.BOB_PASS );
        assertEquals( "Fred Flintstone", fred.getUserPrincipal().getName() );

        rename( Users.FRED, "Fred Astaire" );
        rename( Users.BOB, "Bobby Tables" );
        assertEquals( "Fred Flintstone", fred.getUserPrincipal().getName(), "stale until refreshed" );

        assertEquals( 1, monitor.refreshProfile( Users.FRED ) );

        assertEquals( "Fred Astaire", fred.getUserPrincipal().getName(), "the new display name applies immediately" );
        assertEquals( Users.FRED, fred.getLoginPrincipal().getName(), "the login principal never changes" );
        assertTrue( fred.isAuthenticated() );
        assertTrue( names( fred ).containsAll( Set.of( "Fred Astaire", "FredAstaire", Users.FRED ) ), names( fred ).toString() );
        assertFalse( names( fred ).contains( "Fred Flintstone" ) || names( fred ).contains( "FredFlintstone" ),
                "the stale full/wiki name principals must be gone: " + names( fred ) );
        assertEquals( "Bob", bob.getUserPrincipal().getName(), "another user's session is not touched" );
    }

    @Test
    void refreshProfileIgnoresGuestsAndUnknownLogins() throws Exception {
        monitor.find( "refresh-guest" );
        login( "refresh-fred", Users.FRED, Users.FRED_PASS );
        assertEquals( 0, monitor.refreshProfile( "nobody" ) );
        assertEquals( 0, monitor.refreshProfile( null ) );
        assertEquals( 1, monitor.refreshProfile( Users.FRED ), "only the authenticated session counts" );
    }

    @Test
    void aBlankDisplayNameFallsBackToTheLoginPrincipal() throws Exception {
        final Session fred = login( "refresh-fred", Users.FRED, Users.FRED_PASS );
        rename( Users.FRED, "" );
        monitor.refreshProfile( Users.FRED );
        assertEquals( Users.FRED, fred.getUserPrincipal().getName() );
    }

    @Test
    void profileSavedRefreshesAndNeverThrows() throws Exception {
        final Session fred = login( "refresh-fred", Users.FRED, Users.FRED_PASS );
        rename( Users.FRED, "Fred Astaire" );

        ProfileChangeNotifier.profileSaved( engine, Users.FRED );
        assertEquals( "Fred Astaire", fred.getUserPrincipal().getName() );

        assertDoesNotThrow( () -> ProfileChangeNotifier.profileSaved( null, Users.FRED ) );
        assertDoesNotThrow( () -> ProfileChangeNotifier.profileSaved( engine, null ) );
        assertDoesNotThrow( () -> ProfileChangeNotifier.profileSaved( engine, "  " ) );
    }
}
```

(b) `AdminUserResourceTest.java` — add (needs `import com.wikantik.WikiSession;`, `import com.wikantik.auth.AuthenticationManager;`, `import com.wikantik.auth.SessionMonitor;`, `import com.wikantik.auth.Users;`):

```java
    @Test
    void testAdminEditOfADisplayNameRefreshesTheUsersLiveSession() throws Exception {
        final HttpServletRequest loginRequest = HttpMockFactory.createHttpRequest();
        final com.wikantik.api.core.Session fred = WikiSession.getWikiSession( engine, loginRequest );
        engine.getManager( AuthenticationManager.class ).login( fred, loginRequest, Users.FRED, Users.FRED_PASS );
        try {
            assertEquals( "Fred Flintstone", fred.getUserPrincipal().getName() );
            final JsonObject update = new JsonObject();
            update.addProperty( "fullName", "Fred Astaire" );
            final JsonObject obj = gson.fromJson( doPut( Users.FRED, update ), JsonObject.class );
            assertFalse( obj.has( "error" ), obj.toString() );
            assertEquals( "Fred Astaire", fred.getUserPrincipal().getName(), "applies immediately, no re-login" );
            assertEquals( Users.FRED, fred.getLoginPrincipal().getName() );
        } finally {
            final JsonObject back = new JsonObject();
            back.addProperty( "fullName", "Fred Flintstone" );
            doPut( Users.FRED, back );
            SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );   // later tests start anonymous
        }
    }
```

(c) `AuthResourceTest.java` — add (uses the existing `profileFor`, `authedSession`, `stubWikiSession`, `doPut`):

```java
    @Test
    void handleUpdateProfile_refreshesTheCallersLiveSessions() throws Exception {
        final UserProfile profile = profileFor( "alice", "alice@x.com" );
        final UserManager um = Mockito.mock( UserManager.class );
        final UserDatabase db = Mockito.mock( UserDatabase.class );
        Mockito.when( um.getUserDatabase() ).thenReturn( db );
        Mockito.when( db.findByLoginName( "alice" ) ).thenReturn( profile );
        ( (com.wikantik.WikiEngine) engine ).setManager( UserManager.class, um );

        final JsonObject body = new JsonObject();
        body.addProperty( "fullName", "Alice Smith" );

        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "alice" ) );
              MockedStatic< com.wikantik.auth.ProfileChangeNotifier > notifier =
                      Mockito.mockStatic( com.wikantik.auth.ProfileChangeNotifier.class ) ) {
            doPut( "profile", body );
            notifier.verify( () -> com.wikantik.auth.ProfileChangeNotifier.profileSaved( any(), Mockito.eq( "alice" ) ) );
        }
    }
```

(d) `ScimUserResourceTest.java` — add `import org.mockito.MockedStatic;` and `import com.wikantik.auth.ProfileChangeNotifier;`:

```java
    @Test
    void patchName_refreshesTheUsersLiveSessions() throws Exception {
        when( req.getMethod() ).thenReturn( "PATCH" );
        when( req.getPathInfo() ).thenReturn( "/uid-ivy" );

        final UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "ivy" );
        when( p.getAttributes() ).thenReturn( new HashMap<>() );
        when( mockDb.findByUid( "uid-ivy" ) ).thenReturn( p );
        doNothing().when( mockDb ).save( any() );

        final String body = "{\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"formatted\":\"Ivy Jones\"}}]}";
        when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( body ) ) );

        try ( MockedStatic< ProfileChangeNotifier > notifier = mockStatic( ProfileChangeNotifier.class ) ) {
            resource.service( req, resp );
            notifier.verify( () -> ProfileChangeNotifier.profileSaved( any(), eq( "ivy" ) ) );
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='ProfileSessionRefreshTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` — `cannot find symbol: method refreshProfile(String)` and `class ProfileChangeNotifier`. (The REST/SCIM tests fail the same way after the install in Step 4.)

- [ ] **Step 3: Implement**

**3a. `WikiSession.java`** — add after `injectUserProfilePrincipals()` (ends line ~450):

```java
    /**
     * Re-reads this authenticated session's full-name and wiki-name principals after the user's profile was saved
     * <em>outside</em> this session (self-service REST edit, admin edit, SCIM): the stale name principals are dropped, the user
     * principal is reset to the login principal and the profile is re-injected, so {@link #getUserPrincipal()} reports the new
     * display name at once. The login principal, roles and group principals are untouched. No-op unless authenticated.
     */
    public void refreshUserProfile() {
        if ( !isAuthenticated() || loginPrincipal == null ) {
            return;
        }
        subject.getPrincipals().removeIf( p -> p instanceof WikiPrincipal wp
                && ( WikiPrincipal.FULL_NAME.equals( wp.getType() ) || WikiPrincipal.WIKI_NAME.equals( wp.getType() ) ) );
        userPrincipal = loginPrincipal;
        injectUserProfilePrincipals();
    }
```

**3b. `SessionMonitor.java`** — add `import com.wikantik.WikiSession;` and, after `userPrincipals()` (ends line ~211):

```java
    /**
     * Re-reads the profile-derived principals (full and wiki name) of every live authenticated session that belongs to
     * {@code loginName}, after that profile was saved outside those sessions. One failing session never stops the others.
     *
     * @param loginName the account whose profile changed
     * @return how many sessions were refreshed
     */
    public final int refreshProfile( final String loginName ) {
        if ( loginName == null ) {
            return 0;
        }
        int refreshed = 0;
        for ( final Session s : sessions.values() ) {
            try {
                if ( s instanceof WikiSession ws && ws.isAuthenticated()
                        && ws.getLoginPrincipal() != null && loginName.equals( ws.getLoginPrincipal().getName() ) ) {
                    ws.refreshUserProfile();
                    refreshed++;
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "Could not refresh the profile principals of one live session of '{}': {}", loginName, e.getMessage(), e );
            }
        }
        return refreshed;
    }
```

**3c. New `ProfileChangeNotifier.java`** (licence header as in sibling files):

```java
package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The one place that reacts to a user profile having been written outside the user's own session. Every path that writes
 * a profile through {@code UserDatabase#save} directly — the self-service and admin REST endpoints, SCIM and SSO
 * provisioning — calls {@link #profileSaved} afterwards (the {@code PROFILE_SAVE} events only exist for the legacy
 * {@code UserManager#setUserProfile} path).
 *
 * <p>The reactions are best-effort: the profile is already stored, so a failure here is logged at WARN and never fails the
 * request that made the change.</p>
 */
public final class ProfileChangeNotifier {

    private static final Logger LOG = LogManager.getLogger( ProfileChangeNotifier.class );

    private ProfileChangeNotifier() { }

    /**
     * Call after any write that may have changed a profile's display (full) or wiki name: refreshes the live sessions of
     * {@code loginName} so the new name applies immediately.
     *
     * @param engine    the wiki engine; {@code null} is ignored
     * @param loginName the account whose profile was saved; {@code null}/blank is ignored
     */
    public static void profileSaved( final Engine engine, final String loginName ) {
        if ( engine == null || loginName == null || loginName.isBlank() ) {
            return;
        }
        refreshLiveSessions( engine, loginName );
    }

    private static void refreshLiveSessions( final Engine engine, final String loginName ) {
        try {
            final int refreshed = SessionMonitor.getInstance( engine ).refreshProfile( loginName );
            LOG.debug( "Refreshed {} live session(s) after a profile change for '{}'", refreshed, loginName );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not refresh the live sessions of '{}' after a profile change; they pick up the new name at next "
                    + "login: {}", loginName, e.getMessage(), e );
        }
    }
}
```

**3d. Call sites** (add `import com.wikantik.auth.ProfileChangeNotifier;` to each file):

- `AuthResource.handleUpdateProfile`, immediately after `db.save( profile );` (the one followed by `if ( newPassword != null ...`):
  `ProfileChangeNotifier.profileSaved( engine, loginName );`
- `AdminUserResource.handleCreateUser`, after `db.save( profile );`: `ProfileChangeNotifier.profileSaved( getEngine(), loginName );`
- `AdminUserResource.handleUpdateUser`, after `db.save( profile );` (the one before `LOG.info( "Updated user: {}", loginName )`): `ProfileChangeNotifier.profileSaved( getEngine(), loginName );`
- `ScimUserResource.handleCreate`, after `db.save( p );`: `ProfileChangeNotifier.profileSaved( engine, f.userName() );`
- `ScimUserResource.handleReplace`, right after the closing brace of the `if ( f.active() != null ) { ... } else { db.save( p ); }` block and before `// Reload after potential lifecycle ops`: `ProfileChangeNotifier.profileSaved( engine, p.getLoginName() );`
- `ScimUserResource.handlePatch`, inside `if ( dirty ) {` after `db.save( p );`: `ProfileChangeNotifier.profileSaved( engine, p.getLoginName() );`
- `SSOAutoProvisionService.provisionIfNeeded`, right after `userDb.save( profile );`: `ProfileChangeNotifier.profileSaved( engine, loginName );` (a first-login account has no live session yet; the call keeps the single-notifier rule so Task 5's identity invalidation also covers a brand-new account).

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='ProfileSessionRefreshTest,SessionMonitor*Test,WikiSessionTest,SSOAutoProvisionService*Test' -Dsurefire.failIfNoSpecifiedTests=false
bin/agent-build.sh start inst-main -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait inst-main 540
mvn -B -o test -pl wikantik-rest -Dtest='AuthResourceTest,AdminUserResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-scim -Dtest='Scim*Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest,wikantik-scim
```
Expected: all pass; PMD gate clean (`SessionMonitor` and `WikiSession` each gain one small method — if the gate flags `WikiSession` for a new cognitive/complexity violation, move `refreshUserProfile`'s `removeIf` predicate into a private static helper).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/ProfileChangeNotifier.java \
  wikantik-main/src/main/java/com/wikantik/WikiSession.java \
  wikantik-main/src/main/java/com/wikantik/auth/SessionMonitor.java \
  wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java \
  wikantik-scim/src/main/java/com/wikantik/scim/ScimUserResource.java \
  wikantik-main/src/test/java/com/wikantik/auth/ProfileSessionRefreshTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java \
  wikantik-scim/src/test/java/com/wikantik/scim/ScimUserResourceTest.java
git commit -m "feat(auth): a profile save refreshes the user's live session principals

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `PublicIdentity` service, its wiring on `AuthSubsystem.Services`, and cache invalidation

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/identity/PublicIdentity.java`
- Create: `wikantik-main/src/main/java/com/wikantik/auth/identity/DefaultPublicIdentity.java`
- Create: `wikantik-main/src/main/java/com/wikantik/auth/identity/PublicIdentities.java`
- Create: `wikantik-main/src/main/java/com/wikantik/auth/identity/AccountSnapshot.java`, `wikantik-main/src/main/java/com/wikantik/auth/user/AccountSummary.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java` (new default `accountSummaries()`), `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java` (constant + override)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystem.java` (import; from the Javadoc `securityVerifier` bullet through the end of the `Services` record, lines 83-100)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystemFactory.java` (imports; lines 79-91)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystemBridge.java` (lines 45-46)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/ProfileChangeNotifier.java` (invalidate + `profileDeleted`)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java` (`handleDeleteUser`, `tryDeleteUser`), `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` (self-delete, line ~222)
- Modify (add the 9th `null` argument to every 8-arg `new AuthSubsystem.Services(...)`):
  - `wikantik-main/src/test/java/com/wikantik/pagegraph/DefaultPageGraphServiceTest.java` lines 253, 292, 315
  - `wikantik-rest/src/test/java/com/wikantik/rest/RememberMeAuthFilterTest.java` lines 75, 225
  - `wikantik-rest/src/test/java/com/wikantik/rest/AdminApiKeysResourceTest.java` lines 573, 590, 609
  - `wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceFullTest.java` lines 215, 333, 354 (the argument list is on the following line)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/identity/DefaultPublicIdentityTest.java` (new), `PublicIdentitiesTest.java` (new)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/subsystem/AuthSubsystemFactoryTest.java`, `wikantik-main/src/test/java/com/wikantik/auth/ProfileSessionRefreshTest.java`, `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java`

**Interfaces:**
- Consumes: `UserDatabase#findByLoginName/findByUid/findAllProfiles`, `ProfileChangeNotifier` (Task 4), `WikiSecurityEvent.PROFILE_SAVE/PROFILE_NAME_CHANGED`.
- Produces (exactly the contract):
  - `com.wikantik.api.identity.PublicIdentity` with `UNKNOWN`, `UNKNOWN_CONTRIBUTOR`, `displayName`, `userIdForLogin`, `loginForUserId`, `loginForDisplayName`, `invalidate`.
  - `com.wikantik.auth.identity.DefaultPublicIdentity(UserDatabase db, java.time.Clock clock)` — an in-memory snapshot of the account table (login → account, stored uid → login, normalised display name → login), rebuilt at most once per 60 s on the injected clock, on `invalidate`, and after a failed load per a 5 s backoff. **Every lookup is O(1) with no database access** (callers resolve one value per result row), including "not an account" answers. `userIdForLogin` returns the *stored* `users.uid` and is empty when the column is NULL — `JdbcUserProfileRowMapper` invents a fresh random uid on every read of such a row, so the snapshot is built from the new narrow `UserDatabase#accountSummaries()` query instead of `findByLoginName`/`findAllProfiles`. A failed reload keeps serving the previous snapshot.
  - *new* `default Collection<AccountSummary> UserDatabase#accountSummaries()` (+ `JDBCUserDatabase` override: `SELECT login_name, full_name, uid FROM users`) and the record `com.wikantik.auth.user.AccountSummary(login, fullName, uid)`.
  - `AuthSubsystem.Services` gains the **last** component `PublicIdentity publicIdentity` (9 components now).
  - `static PublicIdentity com.wikantik.auth.identity.PublicIdentities.forEngine(Engine)` — never-null accessor (see "How to obtain" below).
  - `ProfileChangeNotifier.profileSaved` now also calls `PublicIdentity.invalidate(login)`; new `ProfileChangeNotifier.profileDeleted(Engine, String)` (invalidate only).

#### How consumers obtain `PublicIdentity` (no new `getManager` callers — DecompositionArchTest R-2/R-4)

`PublicIdentity` is **not** registered as a manager; `engine.getManager(PublicIdentity.class)` does not exist and must never be added. It travels on the typed `AuthSubsystem.Services` record, built once in `AuthSubsystemFactory` (an approved `*SubsystemFactory`, the only class here that touches the engine registry — and it already did). Pick the first row that applies:

| Consumer | How |
|---|---|
| REST servlets (`RestServletBase` subclasses) | `getSubsystems().auth().publicIdentity()` |
| A class built by a `*SubsystemFactory` / `*WiringHelper` that already receives `AuthSubsystem.Services` (e.g. `PageSubsystemFactory.create(Deps)` has `deps.auth()`) — only subsystems built *after* auth | take `deps.auth().publicIdentity()` and **constructor-inject** the `PublicIdentity` into the collaborator |
| Anything else that only has an `Engine` (plugins, filters, servlets in `wikantik-main`, MCP/`/tools` initializers) | `AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity()` (equivalently `((WikiEngine) engine).getAuthSubsystem().publicIdentity()` — `WikiEngine#getAuthSubsystem()` already exists) — the `*SubsystemBridge` suffix is the approved reader of the typed snapshot; it is a field read, not a `WikiEngine#getManager` call. **Read it lazily, at call time, never in a constructor**: the Lucene indexer, the recent-articles manager and other early managers are constructed *before* `AuthSubsystemFactory` runs (it is built after the four auth managers), so at their construction `getAuthSubsystem()` is still `null` |
| Code that must not NPE on bare/mocked engines, or that runs before the auth subsystem exists (anything unit-tested against `mock(Engine.class)`, or an engine whose auth snapshot is sparse) | `PublicIdentities.forEngine( engine )` — reads the typed snapshot on each call; returns the wired service, else a database-less instance that still masks email-shaped values and passes other values through |

In tests, `mockRecord(AuthSubsystem.Services.class)` (WikiSubsystemsTestFactory) fills `publicIdentity` with a Mockito mock whose `displayName` returns `null`; a test that needs real behaviour builds `new DefaultPublicIdentity( userDatabase, Clock.systemUTC() )` (a Mockito `UserDatabase` stubbing `findByLoginName` is enough) and passes it in the `Services` constructor (9th argument).

- [ ] **Step 1: Write the failing tests**

(a) New `wikantik-main/src/test/java/com/wikantik/auth/identity/DefaultPublicIdentityTest.java`:

```java
package com.wikantik.auth.identity;

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.AccountSummary;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DefaultPublicIdentityTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse( "2026-10-13T00:00:00Z" );

        void advance( final Duration d ) {
            now = now.plus( d );
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone( final ZoneId zone ) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private UserDatabase db;
    private TestClock clock;
    private DefaultPublicIdentity identity;
    private final List< AccountSummary > accounts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = Mockito.mock( UserDatabase.class );
        clock = new TestClock();
        identity = new DefaultPublicIdentity( db, clock );
        publish();
    }

    /** Makes the mock database report the current {@code accounts}. */
    private void publish() throws Exception {
        doReturn( List.copyOf( accounts ) ).when( db ).accountSummaries();
    }

    private void account( final String login, final String fullName, final String uid ) throws Exception {
        accounts.removeIf( a -> a.login().equals( login ) );
        accounts.add( new AccountSummary( login, fullName, uid ) );
        publish();
    }

    // ---- displayName ----

    @Test
    void aLoginResolvesToTheCurrentFullName() throws Exception {
        account( "jane@example.com", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane@example.com" ) );
        assertEquals( "Jane Doe", identity.displayName( "  jane@example.com " ), "the stored value is trimmed" );
    }

    @Test
    void legacyFullNamesAndAgentLabelsPassThroughUnchanged() {
        for ( final String stored : new String[] { "Jake Fear", "Guest", "unknown", "Claude (agent)", "Knowledge Admin" } ) {
            assertEquals( stored, identity.displayName( stored ) );
        }
    }

    @Test
    void anEmailShapedValueThatIsNotAnAccountIsMasked() {
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "ghost@example.com" ) );
    }

    @Test
    void anAccountWhoseStoredNameIsBlankOrEmailShapedIsMasked() throws Exception {
        account( "a@b.example", "a@b.example", "u1" );
        account( "blankname", "   ", "u2" );
        account( "nullname", null, "u3" );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "a@b.example" ) );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "blankname" ),
                "an account with no display name must not fall back to its login" );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "nullname" ) );
    }

    @Test
    void nullAndBlankAreUnknown() {
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( null ) );
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( "" ) );
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( "   " ) );
    }

    // ---- snapshot lifetime ----

    @Test
    void oneQueryServesEveryLookupIncludingMissesForSixtySeconds() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        for ( int i = 0; i < 50; i++ ) {
            identity.displayName( "jane" );
            identity.displayName( "Jake Fear" );   // not an account: a negative answer, also from memory
            identity.userIdForLogin( "jane" );
            identity.loginForDisplayName( "jane doe" );
        }
        verify( db, times( 1 ) ).accountSummaries();
    }

    @Test
    void theSnapshotExpiresAfterSixtySecondsOnTheInjectedClock() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane" ) );
        doReturn( List.of( new AccountSummary( "jane", "Jane Roe", "u1" ) ) ).when( db ).accountSummaries();

        clock.advance( Duration.ofSeconds( 59 ) );
        assertEquals( "Jane Doe", identity.displayName( "jane" ), "still cached at 59 s" );
        verify( db, times( 1 ) ).accountSummaries();

        clock.advance( Duration.ofSeconds( 1 ) );
        assertEquals( "Jane Roe", identity.displayName( "jane" ), "expired at 60 s: the rename is seen" );
        verify( db, times( 2 ) ).accountSummaries();
    }

    @Test
    void invalidateDropsTheSnapshotForOneLoginOrEverything() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        identity.displayName( "jane" );
        doReturn( List.of( new AccountSummary( "jane", "Jane Roe", "u1" ) ) ).when( db ).accountSummaries();

        identity.invalidate( "jane" );
        assertEquals( "Jane Roe", identity.displayName( "jane" ) );
        identity.invalidate( null );
        identity.displayName( "jane" );
        verify( db, times( 3 ) ).accountSummaries();
    }

    // ---- uid / login / display-name lookups ----

    @Test
    void userIdForLoginReadsTheStoredUidAndIsEmptyWhenItIsNull() throws Exception {
        account( "jane", "Jane Doe", "uid-1" );
        account( "nouid", "No Uid", null );   // users.uid NULL: findByLoginName would invent a random one — never exposed here
        assertEquals( Optional.of( "uid-1" ), identity.userIdForLogin( "jane" ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( "nouid" ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( "ghost" ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( null ) );
        assertEquals( Optional.empty(), identity.userIdForLogin( "  " ) );
    }

    @Test
    void loginForUserId() throws Exception {
        account( "jane", "Jane Doe", "uid-1" );
        assertEquals( Optional.of( "jane" ), identity.loginForUserId( "uid-1" ) );
        assertEquals( Optional.of( "jane" ), identity.loginForUserId( " uid-1 " ) );
        assertEquals( Optional.empty(), identity.loginForUserId( "nope" ) );
        assertEquals( Optional.empty(), identity.loginForUserId( null ) );
    }

    @Test
    void loginForDisplayNameIsCaseInsensitiveAndTrimmed() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        account( "bob", "Bob Roe", "u2" );
        assertEquals( Optional.of( "jane" ), identity.loginForDisplayName( "  jane DOE " ) );
        assertEquals( Optional.of( "jane" ), identity.loginForDisplayName( "Jane Doe" ) );
        assertEquals( Optional.empty(), identity.loginForDisplayName( "Nobody Atall" ) );
        assertEquals( Optional.empty(), identity.loginForDisplayName( "  " ) );
        assertEquals( Optional.empty(), identity.loginForDisplayName( null ) );
    }

    @Test
    void anAmbiguousDisplayNameResolvesToNobody() throws Exception {
        account( "jane", "Same Name", "u1" );
        account( "bob", "same name", "u2" );
        assertEquals( Optional.empty(), identity.loginForDisplayName( "Same Name" ), "refuse to guess between two accounts" );
    }

    // ---- failures ----

    @Test
    void aDatabaseFailureFallsBackWithoutThrowingAndRetriesAfterAShortBackoff() throws Exception {
        doThrow( new WikiSecurityException( "connection refused" ) ).when( db ).accountSummaries();

        assertEquals( PublicIdentity.UNKNOWN, assertDoesNotThrow( () -> identity.displayName( "jane" ) ) );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, assertDoesNotThrow( () -> identity.displayName( "jane@example.com" ) ),
                "an email-shaped value is masked even when the load fails" );
        assertEquals( Optional.empty(), assertDoesNotThrow( () -> identity.userIdForLogin( "jane" ) ) );
        assertEquals( Optional.empty(), assertDoesNotThrow( () -> identity.loginForDisplayName( "Jane Doe" ) ) );
        verify( db, times( 1 ) ).accountSummaries();   // the backoff spares the database (and the log)

        account( "jane", "Jane Doe", "u1" );   // database recovers
        assertEquals( PublicIdentity.UNKNOWN, identity.displayName( "jane" ), "still inside the 5 s backoff" );
        clock.advance( DefaultPublicIdentity.RETRY_AFTER_FAILURE );
        assertEquals( "Jane Doe", identity.displayName( "jane" ), "a failure is not cached beyond the backoff" );
    }

    @Test
    void aFailedReloadKeepsServingThePreviousSnapshot() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        assertEquals( "Jane Doe", identity.displayName( "jane" ) );

        doThrow( new IllegalStateException( "pool exhausted" ) ).when( db ).accountSummaries();
        clock.advance( Duration.ofSeconds( 61 ) );

        assertEquals( "Jane Doe", assertDoesNotThrow( () -> identity.displayName( "jane" ) ), "stale beats unknown" );
    }

    @Test
    void withoutADatabaseNoAccountIsKnownButEmailsAreStillMasked() {
        final DefaultPublicIdentity bare = new DefaultPublicIdentity( null, clock );
        assertEquals( "Jake Fear", bare.displayName( "Jake Fear" ) );
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, bare.displayName( "x@y.example" ) );
        assertEquals( Optional.empty(), bare.userIdForLogin( "jane" ) );
        assertEquals( Optional.empty(), bare.loginForUserId( "u1" ) );
        assertEquals( Optional.empty(), bare.loginForDisplayName( "Jane Doe" ) );
        assertDoesNotThrow( () -> bare.invalidate( "jane" ) );
    }

    // ---- profile events ----

    private static UserProfile profile( final String login ) {
        final UserProfile p = Mockito.mock( UserProfile.class );
        Mockito.when( p.getLoginName() ).thenReturn( login );
        return p;
    }

    @Test
    void profileSaveAndNameChangeEventsInvalidateButOtherEventsDoNot() throws Exception {
        account( "jane", "Jane Doe", "u1" );
        identity.displayName( "jane" );

        identity.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.PROFILE_SAVE, profile( "jane" ) ) );
        identity.displayName( "jane" );
        verify( db, times( 2 ) ).accountSummaries();

        identity.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.PROFILE_NAME_CHANGED,
                new UserProfile[] { profile( "jane" ), profile( "jane" ) } ) );
        identity.displayName( "jane" );
        verify( db, times( 3 ) ).accountSummaries();

        identity.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.LOGIN_AUTHENTICATED, profile( "jane" ) ) );
        identity.displayName( "jane" );
        verify( db, times( 3 ) ).accountSummaries();   // an unrelated event changed nothing
    }
}
```

(b) New `wikantik-main/src/test/java/com/wikantik/auth/identity/PublicIdentitiesTest.java`:

```java
package com.wikantik.auth.identity;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.Users;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PublicIdentitiesTest {

    @Test
    void aNullOrNonWikiEngineGetsTheDatabaseLessFallback() {
        for ( final Engine engine : new Engine[] { null, Mockito.mock( Engine.class ) } ) {
            final PublicIdentity identity = PublicIdentities.forEngine( engine );
            assertNotNull( identity );
            assertEquals( "Jake Fear", identity.displayName( "Jake Fear" ) );
            assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, identity.displayName( "x@y.example" ) );
        }
    }

    @Test
    void aRealEngineGetsTheWiredService() {
        final TestEngine engine = TestEngine.build();
        try {
            final PublicIdentity wired = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
            assertNotNull( wired, "AuthSubsystemFactory wires one" );
            assertSame( wired, PublicIdentities.forEngine( engine ) );
            assertEquals( "Fred Flintstone", wired.displayName( Users.FRED ), "login -> the seeded account's full name" );
        } finally {
            engine.stop();
        }
    }
}
```

(c) `AuthSubsystemFactoryTest.createWiresFourCoreManagersFromEngineRegistry` — add `import static org.mockito.ArgumentMatchers.any;` and `import static org.mockito.Mockito.verify;`, and after `assertSame( aclManager, services.aclManager() );`:

```java
        assertNotNull( services.publicIdentity(), "wired whenever a user manager is registered (database-less if it has none)" );
        verify( users ).addWikiEventListener( any( com.wikantik.auth.identity.DefaultPublicIdentity.class ) );
```

and add this test to the same class (also import `static org.junit.jupiter.api.Assertions.assertNull`):

```java
    @Test
    void createToleratesAMissingUserManager() {
        final WikiEngine engine = mock( WikiEngine.class );   // no managers registered
        final CoreSubsystem.Services core = CoreSubsystemFactory.create( new CoreSubsystem.Deps(
            new Properties(), null, new SimpleMeterRegistry(),
            mock( SystemPageRegistry.class ),
            mock( RecentArticlesManager.class ),
            engine ) );

        final AuthSubsystem.Services services = AuthSubsystemFactory.create(
            new AuthSubsystem.Deps( core, /*persistence=*/ null, /*servletContext=*/ null, engine ) );

        assertNull( services.users() );
        assertNull( services.publicIdentity(), "no user manager -> no identity service; consumers use PublicIdentities.forEngine" );
    }
```

(d) `ProfileSessionRefreshTest.java` — append two tests (add imports `com.wikantik.api.identity.PublicIdentity` and `com.wikantik.auth.subsystem.AuthSubsystemBridge`):

```java
    @Test
    void profileSavedAlsoInvalidatesThePublicIdentityCache() throws Exception {
        final PublicIdentity identity = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
        assertEquals( "Fred Flintstone", identity.displayName( Users.FRED ) );
        rename( Users.FRED, "Fred Astaire" );
        assertEquals( "Fred Flintstone", identity.displayName( Users.FRED ), "still served from the 60 s snapshot" );

        ProfileChangeNotifier.profileSaved( engine, Users.FRED );

        assertEquals( "Fred Astaire", identity.displayName( Users.FRED ), "a profile save must not wait for the TTL" );
    }

    @Test
    void profileDeletedInvalidatesThePublicIdentityCache() throws Exception {
        final UserProfile p = db.newProfile();
        p.setLoginName( "gone-user" );
        p.setFullname( "Gone User" );
        p.setPassword( "correct-horse-battery-staple-6" );
        db.save( p );
        final PublicIdentity identity = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
        assertEquals( "Gone User", identity.displayName( "gone-user" ) );

        db.deleteByLoginName( "gone-user" );
        ProfileChangeNotifier.profileDeleted( engine, "gone-user" );

        assertEquals( "gone-user", identity.displayName( "gone-user" ),
                "no longer an account: a non-email value is an ordinary string and passes through" );
    }
```

(e) `AdminUserResourceTest.java` — add:

```java
    @Test
    void testRenameAndDeleteInvalidateThePublicIdentityImmediately() throws Exception {
        final com.wikantik.api.identity.PublicIdentity identity = com.wikantik.auth.identity.PublicIdentities.forEngine( engine );
        final JsonObject create = new JsonObject();
        create.addProperty( "loginName", "identityUser" );
        create.addProperty( "fullName", "Identity User One" );
        create.addProperty( "password", "StrongPassword123!" );
        doPost( null, create );
        assertEquals( "Identity User One", identity.displayName( "identityUser" ) );

        final JsonObject rename = new JsonObject();
        rename.addProperty( "fullName", "Identity User Two" );
        doPut( "identityUser", rename );
        assertEquals( "Identity User Two", identity.displayName( "identityUser" ), "an admin edit must not wait for the TTL" );

        doDelete( "identityUser" );
        assertEquals( "identityUser", identity.displayName( "identityUser" ), "a deleted account no longer resolves" );
    }
```

(f) `JDBCUserDatabaseTest.java` — add next to `testIsLoginNameTakenIgnoreCase`:

```java
    @Test
    public void testAccountSummariesReportTheStoredUidAndNullWhenTheColumnIsNull() throws Exception {
        try ( final Connection conn = m_ds.getConnection(); final Statement stmt = conn.createStatement() ) {
            stmt.executeUpdate( "INSERT INTO users (email,login_name,password,created,full_name,wiki_name) VALUES ("
                    + "'nouid@mailinator.com','nouid-user','{SHA-256}x','" + new Timestamp( System.currentTimeMillis() )
                    + "','No Uid User','NoUidUser')" );
        }
        final java.util.Map< String, com.wikantik.auth.user.AccountSummary > byLogin = new java.util.HashMap<>();
        for ( final com.wikantik.auth.user.AccountSummary a : m_db.accountSummaries() ) {
            byLogin.put( a.login(), a );
        }
        Assertions.assertEquals( "-7739839977499061014", byLogin.get( "janne" ).uid() );
        Assertions.assertEquals( "Janne Jalkanen", byLogin.get( "janne" ).fullName() );
        Assertions.assertNull( byLogin.get( "nouid-user" ).uid(), "a NULL uid is reported as null, never an invented one" );
        Assertions.assertNotNull( m_db.findByLoginName( "nouid-user" ).getUid(),
                "documents why accountSummaries exists: findByLoginName invents a uid for such a row" );
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='DefaultPublicIdentityTest,PublicIdentitiesTest,AuthSubsystemFactoryTest,ProfileSessionRefreshTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` — package `com.wikantik.api.identity` does not exist, `DefaultPublicIdentity`, `PublicIdentities`, `AuthSubsystem.Services.publicIdentity()` and `ProfileChangeNotifier.profileDeleted` cannot be found.

- [ ] **Step 3: Implement**

**3a. `PublicIdentity.java`** (new, `wikantik-api`, Apache header as in the sibling `com/wikantik/api/comments/Comment.java`):

```java
package com.wikantik.api.identity;

import java.util.Optional;

/**
 * Turns the principals Wikantik <em>stores</em> (logins, legacy full names, agent labels) into the names it may <em>show</em>.
 *
 * <p>Storage keys are logins — page-version authors, comment authors, mention keys, owners. A login is an email address for
 * SSO users, so no non-admin surface may show a raw stored principal; it resolves through this service instead. A rename
 * therefore applies everywhere, including old history. Admin surfaces ({@code /admin/*}, the admin MCP server) are exempt
 * and keep showing logins.</p>
 *
 * <p>Implementations never throw to callers and never return {@code null}. Obtain the service from
 * {@code AuthSubsystem.Services#publicIdentity()} (REST: {@code getSubsystems().auth().publicIdentity()}); other code uses
 * {@code AuthSubsystemBridge.fromLegacyEngine(engine).publicIdentity()} or, when it must not see {@code null}, the
 * {@code PublicIdentities.forEngine(engine)} accessor in wikantik-main.</p>
 */
public interface PublicIdentity {

    /** Shown when a stored principal is null or blank, or cannot be resolved because the user database failed. */
    String UNKNOWN = "unknown";

    /** Shown in place of an email-shaped value (or an account that has no display name) so a login can never leak. */
    String UNKNOWN_CONTRIBUTOR = "unknown contributor";

    /**
     * Stored principal (a login, a legacy full name, an agent label) → the name shown to non-admins.
     * A login → that account's current display name (full name); any other value unchanged, except an email-shaped value
     * (contains {@code '@'}) → {@link #UNKNOWN_CONTRIBUTOR}; null/blank → {@link #UNKNOWN}. Never null, never throws.
     */
    String displayName( String storedPrincipal );

    /** The opaque {@code users.uid} of the account with this login, if any (used by mention tokens and the picker). */
    Optional< String > userIdForLogin( String login );

    /** The login of the account with this {@code users.uid}, if any. */
    Optional< String > loginForUserId( String uid );

    /** The login of the account whose display name is this, comparing case-insensitively and trimmed; empty if none or ambiguous. */
    Optional< String > loginForDisplayName( String displayName );

    /** Drops cached entries for {@code login}; {@code null} drops everything. */
    void invalidate( String login );
}
```

**3b. `DefaultPublicIdentity.java`** (new, `wikantik-main/src/main/java/com/wikantik/auth/identity/`, licence header) — an O(1)-per-lookup in-memory snapshot of the account table (callers resolve one value per result row, so no per-key query); the snapshot is rebuilt at most once per 60 s, on `invalidate`, or after a failed load per the backoff:

```java
package com.wikantik.auth.identity;

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.AccountSummary;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PublicIdentity} backed by an in-memory snapshot of the account table.
 *
 * <p>One cheap query ({@link UserDatabase#accountSummaries()}: login, display name, stored uid) fills three hash maps, so every
 * lookup afterwards — including "this author is not an account" — is O(1) with no database access; that matters because
 * callers resolve one value per result row. The snapshot is trusted for {@link #TTL} on the injected {@link Clock}, so a rename
 * made on another node or directly in the database shows within a minute; {@link #invalidate} and the profile events
 * registered by {@code AuthSubsystemFactory} drop it at once on this node.</p>
 *
 * <p>If a reload fails the previous snapshot keeps being served (retry in {@link #RETRY_AFTER_FAILURE}); with no snapshot at
 * all the failure is logged at WARN and answered with the neutral fallback ({@link #UNKNOWN} for a non-email value,
 * {@link #UNKNOWN_CONTRIBUTOR} for an email-shaped one). With a {@code null} database no account is known: values pass
 * through, email-shaped ones are still masked.</p>
 */
public final class DefaultPublicIdentity implements PublicIdentity, WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( DefaultPublicIdentity.class );

    /** How long a loaded snapshot is trusted. */
    static final Duration TTL = Duration.ofSeconds( 60 );

    /** After a failed load: how long before the database is asked again. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds( 5 );

    private final UserDatabase db;
    private final Clock clock;
    private volatile AccountSnapshot snapshot;
    private volatile Instant retryAt;

    /**
     * @param db    the user database; {@code null} means "no accounts are known"
     * @param clock the clock the 60 s snapshot lifetime is measured on
     */
    public DefaultPublicIdentity( final UserDatabase db, final Clock clock ) {
        this.db = db;
        this.clock = Objects.requireNonNull( clock, "clock" );
    }

    @Override
    public String displayName( final String storedPrincipal ) {
        if ( storedPrincipal == null || storedPrincipal.isBlank() ) {
            return UNKNOWN;
        }
        final String stored = storedPrincipal.trim();
        final boolean emailShaped = stored.indexOf( '@' ) >= 0;
        final AccountSnapshot current = current( stored );
        if ( current == null ) {
            return emailShaped ? UNKNOWN_CONTRIBUTOR : UNKNOWN;
        }
        final AccountSummary account = current.byLogin().get( stored );
        if ( account == null ) {
            return emailShaped ? UNKNOWN_CONTRIBUTOR : stored;
        }
        final String name = account.fullName() == null ? "" : account.fullName().trim();
        return name.isEmpty() || name.indexOf( '@' ) >= 0 ? UNKNOWN_CONTRIBUTOR : name;
    }

    @Override
    public Optional< String > userIdForLogin( final String login ) {
        if ( login == null || login.isBlank() ) {
            return Optional.empty();
        }
        final AccountSnapshot current = current( login );
        final AccountSummary account = current == null ? null : current.byLogin().get( login.trim() );
        return account == null ? Optional.empty() : Optional.ofNullable( account.uid() );
    }

    @Override
    public Optional< String > loginForUserId( final String uid ) {
        if ( uid == null || uid.isBlank() ) {
            return Optional.empty();
        }
        final AccountSnapshot current = current( uid );
        return current == null ? Optional.empty() : Optional.ofNullable( current.loginByUid().get( uid.trim() ) );
    }

    @Override
    public Optional< String > loginForDisplayName( final String displayName ) {
        if ( displayName == null || displayName.isBlank() ) {
            return Optional.empty();
        }
        final AccountSnapshot current = current( displayName );
        return current == null ? Optional.empty()
                : Optional.ofNullable( current.loginByName().get( displayName.trim().toLowerCase( Locale.ROOT ) ) );
    }

    @Override
    public void invalidate( final String login ) {
        // Cheap to refill (one query), and a rename changes name->login and uid->login answers for other keys too.
        snapshot = null;
        retryAt = null;
    }

    /** Invalidates on {@code PROFILE_SAVE} and {@code PROFILE_NAME_CHANGED} (the legacy {@code UserManager} path). */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiSecurityEvent securityEvent
                && ( securityEvent.getType() == WikiSecurityEvent.PROFILE_SAVE
                     || securityEvent.getType() == WikiSecurityEvent.PROFILE_NAME_CHANGED ) ) {
            invalidate( null );
        }
    }

    // ------------------------------------------------------------------ snapshot

    /** The snapshot to answer {@code key} from, reloading it when expired; {@code null} when none could be loaded. */
    private AccountSnapshot current( final String key ) {
        final Instant now = clock.instant();
        final AccountSnapshot held = snapshot;
        if ( held != null && now.isBefore( held.expires() ) ) {
            return held;
        }
        final Instant notBefore = retryAt;
        if ( notBefore != null && now.isBefore( notBefore ) ) {
            return held;   // a recent load failed: keep serving the stale snapshot (or none) without asking again
        }
        return reload( key, now );
    }

    private synchronized AccountSnapshot reload( final String key, final Instant now ) {
        final AccountSnapshot held = snapshot;
        if ( held != null && now.isBefore( held.expires() ) ) {
            return held;   // another thread reloaded while we waited
        }
        try {
            final AccountSnapshot fresh = AccountSnapshot.build( now.plus( TTL ),
                    db == null ? List.of() : db.accountSummaries() );
            snapshot = fresh;
            retryAt = null;
            return fresh;
        } catch ( final WikiSecurityException | RuntimeException e ) {
            retryAt = now.plus( RETRY_AFTER_FAILURE );
            LOG.warn( "Could not load the account snapshot (resolving '{}'); {}: {}", key,
                    held == null ? "showing neutral labels" : "serving the previous snapshot", e.getMessage(), e );
            return held;
        }
    }
}
```

**3b-2. New `AccountSnapshot.java`** (same package, package-private; licence header):

```java
package com.wikantik.auth.identity;

import com.wikantik.auth.user.AccountSummary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, in-memory index of the account table: login -> account, stored uid -> login, and normalised display name ->
 * login (a name held by two accounts is left out, so it resolves to nobody).
 */
record AccountSnapshot( Instant expires, Map< String, AccountSummary > byLogin, Map< String, String > loginByUid,
        Map< String, String > loginByName ) {

    private static final Logger LOG = LogManager.getLogger( AccountSnapshot.class );

    static AccountSnapshot build( final Instant expires, final Collection< AccountSummary > accounts ) {
        final Map< String, AccountSummary > byLogin = new HashMap<>();
        final Map< String, String > loginByUid = new HashMap<>();
        final Map< String, String > loginByName = new HashMap<>();
        final Set< String > ambiguousNames = new HashSet<>();
        for ( final AccountSummary account : accounts ) {
            if ( account.login() == null ) {
                continue;
            }
            byLogin.put( account.login(), account );
            if ( account.uid() != null ) {
                loginByUid.put( account.uid(), account.login() );
            }
            indexName( loginByName, ambiguousNames, account );
        }
        ambiguousNames.forEach( name -> {
            LOG.warn( "Display name '{}' is held by more than one account; it will not resolve to a login", name );
            loginByName.remove( name );
        } );
        return new AccountSnapshot( expires, Map.copyOf( byLogin ), Map.copyOf( loginByUid ), Map.copyOf( loginByName ) );
    }

    private static void indexName( final Map< String, String > loginByName, final Set< String > ambiguousNames,
            final AccountSummary account ) {
        if ( account.fullName() == null || account.fullName().isBlank() ) {
            return;
        }
        final String key = account.fullName().trim().toLowerCase( Locale.ROOT );
        if ( loginByName.putIfAbsent( key, account.login() ) != null ) {
            ambiguousNames.add( key );
        }
    }
}
```

**3b-3. New `wikantik-main/src/main/java/com/wikantik/auth/user/AccountSummary.java`** (licence header):

```java
package com.wikantik.auth.user;

/**
 * The three columns of a {@code users} row that identity resolution needs — no password hash, no attribute blob.
 *
 * @param login    {@code login_name}
 * @param fullName {@code full_name} (the display name); may be {@code null} or blank
 * @param uid      the <em>stored</em> {@code uid}; {@code null} when the column is NULL or blank. {@code findByLoginName}
 *                 invents a fresh random uid on every read of such a row, so it must never be used as an identifier
 */
public record AccountSummary( String login, String fullName, String uid ) { }
```

**3b-4. `UserDatabase.java`** — add after `isLoginNameTakenIgnoreCase` (the `Collection`, `List`, `ArrayList` imports already exist):

```java
    /**
     * Every account's login, display name and <em>stored</em> uid, without the password hash or attribute blob — what identity
     * resolution needs, in one pass. A NULL/blank stored uid is reported as {@code null} (never an invented one).
     *
     * <p>This default implementation maps {@link #findAllProfiles()}; a relational store should override it with one narrow
     * query ({@code JDBCUserDatabase} does).</p>
     *
     * @throws WikiSecurityException if the accounts cannot be enumerated
     */
    default Collection< AccountSummary > accountSummaries() throws WikiSecurityException {
        final List< AccountSummary > summaries = new ArrayList<>();
        for ( final UserProfile profile : findAllProfiles() ) {
            final String uid = profile.getUid();
            summaries.add( new AccountSummary( profile.getLoginName(), profile.getFullname(),
                    uid == null || uid.isBlank() ? null : uid ) );
        }
        return summaries;
    }
```

**3b-5. `JDBCUserDatabase.java`** — add the constant next to the other SQL constants and the override next to `isLoginNameTakenIgnoreCase`:

```java
    private static final String ACCOUNT_SUMMARIES = "SELECT login_name, full_name, uid FROM users";
```

```java
    /** {@inheritDoc} One narrow query: no attribute blobs, no invented uids, immune to a corrupt attributes column. */
    @Override
    public Collection< AccountSummary > accountSummaries() throws WikiSecurityException {
        try {
            return jdbc.query( ACCOUNT_SUMMARIES, SqlBinder.NONE, rs -> {
                final String uid = rs.getString( "uid" );
                return new AccountSummary( rs.getString( "login_name" ), rs.getString( "full_name" ),
                        uid == null || uid.isBlank() ? null : uid );
            } );
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not list accounts: " + e.getMessage(), e );
        }
    }
```

**3c. `PublicIdentities.java`** (new, same package):

```java
package com.wikantik.auth.identity;

import com.wikantik.api.core.Engine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;

/**
 * Never-null access to the engine's {@link PublicIdentity} for code that has only an {@link Engine} and may run against a bare
 * or mocked one. Reading the typed snapshot through {@link AuthSubsystemBridge} is not a {@code WikiEngine#getManager} call
 * (DecompositionArchTest R-2/R-4 are unaffected).
 */
public final class PublicIdentities {

    private static final Logger LOG = LogManager.getLogger( PublicIdentities.class );

    /** Knows no accounts: values pass through, email-shaped ones are masked. */
    private static final PublicIdentity NO_ACCOUNTS = new DefaultPublicIdentity( null, Clock.systemUTC() );

    private PublicIdentities() { }

    /** The wired service for {@code engine}, else a database-less fallback that still never leaks an email-shaped value. */
    public static PublicIdentity forEngine( final Engine engine ) {
        if ( engine != null ) {
            try {
                final PublicIdentity wired = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
                if ( wired != null ) {
                    return wired;
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "Could not reach the PublicIdentity service; falling back to masking only: {}", e.getMessage(), e );
            }
        }
        return NO_ACCOUNTS;
    }
}
```

**3d. `AuthSubsystem.java`** — add `import com.wikantik.api.identity.PublicIdentity;` (before `com.wikantik.auth...` imports) and replace lines 83-100 (the Javadoc `securityVerifier` bullet through the record's closing `) {}`):

```java
     *   <li>{@code securityVerifier} is always {@code null} — it is
     *       session-scoped (a fresh {@link SecurityVerifier} per admin
     *       request) and does not fit the engine-scoped typed-services
     *       bundle. Callers must instantiate {@link SecurityVerifier}
     *       directly as before. This field is retained so that Checkpoint 5
     *       can remove it cleanly if it remains unused.</li>
     *   <li>{@code publicIdentity} is the stored-principal → display-name resolver
     *       ({@link PublicIdentity}); non-null from {@link AuthSubsystemFactory#create}
     *       (database-less when the user manager has none) but {@code null} when no
     *       {@link UserManager} is registered, in the sparse records built by
     *       {@link AuthSubsystemBridge} for non-{@code WikiEngine} engines, and in tests — use {@code PublicIdentities.forEngine(engine)} when a
     *       non-null handle is needed.</li>
     * </ul>
     */
    public record Services(
        AuthenticationManager authentication,
        AuthorizationManager  authorization,
        UserManager           users,
        GroupManager          groups,
        Authorizer            webAuthorizer,
        ApiKeyService         apiKeys,
        SecurityVerifier      securityVerifier,
        AclManager            aclManager,
        PublicIdentity        publicIdentity
    ) {}
```

**3e. `AuthSubsystemFactory.java`** — imports `com.wikantik.api.identity.PublicIdentity`, `com.wikantik.auth.identity.DefaultPublicIdentity`, `java.time.Clock`; replace lines 79-91 (from `final AclManager aclManager = ...` through the closing brace of `create`):

```java
        final AclManager aclManager = engine.getManager( AclManager.class );

        return new AuthSubsystem.Services(
            authentication,
            authorization,
            users,
            groups,
            webAuthorizer,
            apiKeys,
            /* securityVerifier — wired in Ckpt 3 */ null,
            aclManager,
            users == null ? null : createPublicIdentity( users )
        );
    }

    /**
     * One {@link PublicIdentity} per engine, bound to the user manager's database (none when no user manager is registered —
     * bare or mocked engines). It also listens to the user manager's
     * profile events (legacy {@code setUserProfile} path); the REST/SCIM/SSO write paths invalidate it through
     * {@code ProfileChangeNotifier}. The listener registry holds it weakly — the {@code Services} record keeps it alive.
     */
    private static PublicIdentity createPublicIdentity( final UserManager users ) {
        final DefaultPublicIdentity identity = new DefaultPublicIdentity( users.getUserDatabase(), Clock.systemUTC() );
        users.addWikiEventListener( identity );
        return identity;
    }
```

**3f. `AuthSubsystemBridge.java`** lines 45-46: `null, null, null, null, null, null, null, null );` → add one more `null` (nine in total):

```java
            return new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null, null );
```

**3g. `ProfileChangeNotifier.java`** — add imports `com.wikantik.api.identity.PublicIdentity` and `com.wikantik.auth.subsystem.AuthSubsystemBridge`; replace `profileSaved` and add `profileDeleted` + the helper:

```java
    public static void profileSaved( final Engine engine, final String loginName ) {
        if ( engine == null || loginName == null || loginName.isBlank() ) {
            return;
        }
        refreshLiveSessions( engine, loginName );
        invalidateIdentity( engine, loginName );
    }

    /**
     * Call after an account is deleted: drops its cached display name so it stops resolving immediately. (No session
     * refresh — the profile no longer exists.)
     */
    public static void profileDeleted( final Engine engine, final String loginName ) {
        if ( engine == null || loginName == null || loginName.isBlank() ) {
            return;
        }
        invalidateIdentity( engine, loginName );
    }

    private static void invalidateIdentity( final Engine engine, final String loginName ) {
        try {
            final PublicIdentity identity = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
            if ( identity != null ) {
                identity.invalidate( loginName );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not invalidate the cached display name of '{}'; it refreshes within 60 s anyway: {}",
                    loginName, e.getMessage(), e );
        }
    }
```
(and update the class Javadoc sentence "Both reactions…" is already generic.)

**3h. Delete call sites** (add `import com.wikantik.auth.ProfileChangeNotifier;` where missing):
- `AdminUserResource.handleDeleteUser`: after `getUserDatabase().deleteByLoginName( loginName );` add `ProfileChangeNotifier.profileDeleted( getEngine(), loginName );`
- `AdminUserResource.tryDeleteUser`: same line after its `deleteByLoginName`.
- `AuthResource` self-delete (inside the `try`, after `...getUserDatabase().deleteByLoginName( loginName );`): `ProfileChangeNotifier.profileDeleted( engine, loginName );`

**3i. Existing 8-argument constructions** — add the 9th `null` (compile fixes):
- `DefaultPageGraphServiceTest.java:253,292,315`: `new AuthSubsystem.Services( null, authMgr, null, null, null, null, null, null )` → `new AuthSubsystem.Services( null, authMgr, null, null, null, null, null, null, null )`
- `RememberMeAuthFilterTest.java:75`: `( authMgr, null, null, null, null, null, null, null )` → `( authMgr, null, null, null, null, null, null, null, null )`; `:225`: eight `null`s → nine.
- `AdminApiKeysResourceTest.java:573`: eight `null`s → nine; `:590`: `( null, null, userManagerNullDb, null, null, null, null, null )` → add a trailing `, null`; `:609`: `( null, null, userManager, null, null, null, null, null )` → add a trailing `, null`.
- `AdminOverviewResourceFullTest.java:216, 334, 355` (the argument line after `new …AuthSubsystem.Services(`): `null, null, userManager, null, null, null, null, null ),` → `null, null, userManager, null, null, null, null, null, null ),`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/jakefear/source/jspwiki
bin/agent-build.sh start inst-api -- mvn -B -o install -pl wikantik-api -DskipTests
bin/agent-build.sh wait inst-api 540
mvn -B -o test -pl wikantik-main \
  -Dtest='DefaultPublicIdentityTest,PublicIdentitiesTest,AuthSubsystemFactoryTest,AuthSubsystemBridgeTest,ProfileSessionRefreshTest,JDBCUserDatabaseTest,DefaultPageGraphServiceTest,DecompositionArchTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
bin/agent-build.sh start inst-main -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait inst-main 540
mvn -B -o test -pl wikantik-rest -Dtest='AdminUserResourceTest,AuthResourceTest,RememberMeAuthFilterTest,AdminApiKeysResourceTest,AdminOverviewResourceFullTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-scim -Dtest='Scim*Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-api,wikantik-main,wikantik-rest
```
Expected: pass. `DecompositionArchTest` must stay green with **no** change to `archunit_store` (if `git status` shows `wikantik-main/src/test/resources/archunit_store` modified, restore it with `git checkout -- wikantik-main/src/test/resources/archunit_store` and find the stray `getManager` caller — there must be none).

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/identity/PublicIdentity.java \
  wikantik-main/src/main/java/com/wikantik/auth/identity/DefaultPublicIdentity.java \
  wikantik-main/src/main/java/com/wikantik/auth/identity/PublicIdentities.java \
  wikantik-main/src/main/java/com/wikantik/auth/identity/AccountSnapshot.java \
  wikantik-main/src/main/java/com/wikantik/auth/user/AccountSummary.java \
  wikantik-main/src/main/java/com/wikantik/auth/user/UserDatabase.java \
  wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
  wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java \
  wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystem.java \
  wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystemFactory.java \
  wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystemBridge.java \
  wikantik-main/src/main/java/com/wikantik/auth/ProfileChangeNotifier.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java \
  wikantik-main/src/test/java/com/wikantik/auth/identity/DefaultPublicIdentityTest.java \
  wikantik-main/src/test/java/com/wikantik/auth/identity/PublicIdentitiesTest.java \
  wikantik-main/src/test/java/com/wikantik/auth/subsystem/AuthSubsystemFactoryTest.java \
  wikantik-main/src/test/java/com/wikantik/auth/ProfileSessionRefreshTest.java \
  wikantik-main/src/test/java/com/wikantik/pagegraph/DefaultPageGraphServiceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/RememberMeAuthFilterTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminApiKeysResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceFullTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminUserResourceTest.java
git commit -m "feat(identity): PublicIdentity service on AuthSubsystem.Services with 60s cache and profile invalidation

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `AuthorKeys` — every authorship writer stores the login

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/auth/AuthorKeys.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/page/subsystem/lifecycle/DefaultPageLifecycle.java` (lines 85-89)
- Modify: `wikantik-main/src/main/java/com/wikantik/content/DefaultPageRenamer.java` (lines 123 and 200)
- Modify: `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java` (line 515, lines 539-541, line 555; drop `java.security.Principal` import, line 28)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/authorize/DefaultGroupManager.java` (line 310; new private helper)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java` (lines 462-469 PUT, lines 712-722 PATCH)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/DerivedIngestResource.java` (line 111)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/ObsidianImportResource.java` (line 210)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminClusterResource.java` (line 154)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminDerivedResource.java` (line 202)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/AuthorKeysTest.java` (new), `wikantik-main/src/test/java/com/wikantik/auth/AuthorKeyWritersTest.java` (new), `wikantik-main/src/test/java/com/wikantik/attachment/AttachmentServletTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java`, `DerivedIngestResourceTest.java`, `ObsidianImportResourceTest.java`, `AdminAuthorResolutionTest.java` (new)

**Interfaces:**
- Consumes: `Session#isAuthenticated/getLoginPrincipal/getUserPrincipal`.
- Produces: `public static String com.wikantik.auth.AuthorKeys.authorKeyFor(Session)` — the login principal's name when the session is authenticated; otherwise the session's user principal name (anonymous/asserted behaviour is unchanged); `"Guest"` for a `null` session; `null` only when a session has no principals at all. Every writer below uses it, so from this task on new page versions, attachments, renames and group creator/modifier columns carry the **login**; `PublicIdentity.displayName(...)` (Task 5) turns it into the current display name when shown. MCP writers keep storing the MCP client name.

Verified writer inventory (all `Session.getUserPrincipal().getName()` / `Context#getCurrentUser().getName()` as authorship — the session user principal is the profile **full name**):

| Writer | Where | Before | After |
|---|---|---|---|
| `PageResource` PUT | `PageResource.java:465-469` | `getUserPrincipal().getName()` | `AuthorKeys.authorKeyFor(session)` |
| `PageResource` PATCH | `PageResource.java:716-722` | same | same |
| default page author | `DefaultPageLifecycle.java:86-89` (`context.getCurrentUser()`) | full name | login |
| derived ingest | `DerivedIngestResource.java:111` | full name | login |
| Obsidian import | `ObsidianImportResource.java:210` (`author`; `owner` already the login) | full name | login |
| admin cluster rename | `AdminClusterResource.java:154` (feeds `ClusterRenameService` `.author(actor)`) | full name | login |
| admin derived reflow | `AdminDerivedResource.java:202` | full name | login |
| page rename + referrer rewrite | `DefaultPageRenamer.java:123, 200` | full name | login |
| attachment upload | `AttachmentServlet.java:515, 539-541` | full name | login |
| group creator/modifier | `DefaultGroupManager.java:310` (`groupDatabase.save(group, principal)`) | full name | login |

Deliberately **not** changed: the page-read audit actor (`PageResource.java:251`, an audit-log row — operator-only), `KnowledgeGraphResource.principalName` (a log line), `AuthResource` `fullName`/`username` in `GET /api/auth/user` (the caller's own session), comments/mentions/owners (already logins), `KgNodeAdminHandlers`/`DefaultKgCurationOps` (constant label `"Knowledge Admin"`), MCP tools (client names). The REST `AttachmentResource` (`PUT /api/attachments`) never sets an author, so it leaks nothing.

- [ ] **Step 1: Write the failing tests**

(a) New `wikantik-main/src/test/java/com/wikantik/auth/AuthorKeysTest.java`:

```java
package com.wikantik.auth;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthorKeysTest {

    private static Session session( final boolean authenticated, final String login, final String user ) {
        final Session s = Mockito.mock( Session.class );
        Mockito.when( s.isAuthenticated() ).thenReturn( authenticated );
        Mockito.when( s.getLoginPrincipal() ).thenReturn( login == null ? null : new WikiPrincipal( login ) );
        Mockito.when( s.getUserPrincipal() ).thenReturn( user == null ? null : new WikiPrincipal( user ) );
        return s;
    }

    @Test
    void anAuthenticatedSessionYieldsTheLoginNotTheFullName() {
        assertEquals( "jane@example.com", AuthorKeys.authorKeyFor( session( true, "jane@example.com", "Jane Doe" ) ) );
    }

    @Test
    void anAuthenticatedSessionWithoutAUsableLoginFallsBackToTheUserPrincipal() {
        assertEquals( "Jane Doe", AuthorKeys.authorKeyFor( session( true, null, "Jane Doe" ) ) );
        assertEquals( "Jane Doe", AuthorKeys.authorKeyFor( session( true, "  ", "Jane Doe" ) ) );
    }

    @Test
    void anonymousAndAssertedSessionsKeepTheirUserPrincipal() {
        assertEquals( "192.0.2.7", AuthorKeys.authorKeyFor( session( false, "Guest", "192.0.2.7" ) ) );
        assertEquals( "Asserted Alice", AuthorKeys.authorKeyFor( session( false, "Asserted Alice", "Asserted Alice" ) ) );
    }

    @Test
    void aNullSessionIsGuestAndAPrincipalLessSessionIsNull() {
        assertEquals( "Guest", AuthorKeys.authorKeyFor( null ) );
        assertNull( AuthorKeys.authorKeyFor( session( false, null, null ) ) );
    }

    @Test
    void aRealLoggedInSessionYieldsTheLoginWhileItsUserPrincipalIsTheFullName() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            final Session admin = engine.adminSession();
            assertEquals( "Administrator", admin.getUserPrincipal().getName(), "the seeded admin's full name" );
            assertEquals( Users.ADMIN, AuthorKeys.authorKeyFor( admin ) );
        } finally {
            engine.stop();
        }
    }
}
```

(b) New `wikantik-main/src/test/java/com/wikantik/auth/AuthorKeyWritersTest.java`:

```java
package com.wikantik.auth;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.content.PageRenamer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The main-module authorship writers record the login (admin's full name is "Administrator"). */
class AuthorKeyWritersTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @AfterEach
    void cleanup() {
        engine.deleteQuietly( "AuthorKeyPage", "AuthorKeyRenameFrom", "AuthorKeyRenameTo" );
    }

    private PageManager pages() {
        return engine.getManager( PageManager.class );
    }

    /** Logs the shared mock session in as the given account (re-logging whoever was on it) and returns a context over {@code page}. */
    private Context contextAs( final String login, final String password, final Page page ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = SessionMonitor.getInstance( engine ).find( request.getSession() );
        engine.getManager( AuthenticationManager.class ).login( session, request, login, password );
        return Wiki.context().create( engine, request, page );
    }

    @Test
    void aSavedPageDefaultsItsAuthorToTheSavingUsersLogin() throws Exception {
        engine.saveText( "AuthorKeyPage", "hello" );   // logs the shared session in as admin
        assertEquals( Users.ADMIN, pages().getPage( "AuthorKeyPage" ).getAuthor() );
    }

    @Test
    void renameRecordsTheRenamersLogin() throws Exception {
        engine.saveTextAsJanne( "AuthorKeyRenameFrom", "body" );   // the previous version's author is "janne"
        final Context context = contextAs( Users.ADMIN, Users.ADMIN_PASS, pages().getPage( "AuthorKeyRenameFrom" ) );

        engine.getManager( PageRenamer.class ).renamePage( context, "AuthorKeyRenameFrom", "AuthorKeyRenameTo", false );

        assertEquals( Users.ADMIN, pages().getPage( "AuthorKeyRenameTo" ).getAuthor() );
    }

    @Test
    void aGroupRecordsItsCreatorAndModifierLogin() throws Exception {
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group group = groups.parseGroup( "AuthorKeyGroup", "janne", true );
        groups.setGroup( WikiSessionTest.adminSession( engine ), group );
        try {
            assertEquals( Users.ADMIN, groups.getGroup( "AuthorKeyGroup" ).getCreator() );
            assertEquals( Users.ADMIN, groups.getGroup( "AuthorKeyGroup" ).getModifier() );
        } finally {
            groups.removeGroup( "AuthorKeyGroup" );
        }
    }
}
```

(c) `AttachmentServletTest.java` — add imports `org.mockito.ArgumentCaptor`, `com.wikantik.auth.WikiPrincipal`, and `static org.junit.jupiter.api.Assertions.assertEquals` if missing; add after `testExecuteUploadExistingAttachmentUpdatesVersion`:

```java
    @Test
    void testExecuteUploadRecordsTheLoginNotTheFullName() throws Exception {
        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );
        when( session.isAuthenticated() ).thenReturn( true );
        when( session.getLoginPrincipal() ).thenReturn( new WikiPrincipal( "jane@example.com" ) );
        when( session.getUserPrincipal() ).thenReturn( new WikiPrincipal( "Jane Doe" ) );
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.executeUpload( context, data, "test.txt", "http://localhost:8080/error", "TestPage", null, 9 );

        final ArgumentCaptor< Attachment > stored = ArgumentCaptor.forClass( Attachment.class );
        verify( attachmentManager ).storeAttachment( stored.capture(), eq( data ) );
        assertEquals( "jane@example.com", stored.getValue().getAuthor() );
    }
```

(d) `PageResourceTest.java` (imports `Users`, `PageManager` already present; add the helper and the tests; add `import com.wikantik.auth.AuthenticationManager;`, `import com.wikantik.auth.SessionMonitor;`, `import com.wikantik.api.core.Session;`). Replace `testPutPage_authenticatedSession_bodyAuthorIgnored` (lines 703-739) with:

```java
    /** Logs the shared mock session in as the given account, re-logging whoever was on it (TestEngine#adminSession is one-shot). */
    private void loginAs( final String login, final String password ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = SessionMonitor.getInstance( engine ).find( request.getSession() );
        engine.getManager( AuthenticationManager.class ).login( session, request, login, password );
    }

    /**
     * Security: when an authenticated session is active, the body {@code author} field must be ignored in favour of the
     * session — and what is recorded is the session's LOGIN ("admin"), not its full name ("Administrator").
     */
    @Test
    void testPutPage_authenticatedSession_bodyAuthorIgnored() throws Exception {
        engine.saveTextAsJanne( "RestPutPage", "first version" );      // the previous version's author is "janne"
        loginAs( Users.ADMIN, Users.ADMIN_PASS );

        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Spoofed author page content" );
        body.addProperty( "author", "evil" );  // caller attempts to spoof author

        final JsonObject obj = gson.fromJson( doPut( "RestPutPage", body ), JsonObject.class );
        assertTrue( obj.get( "success" ).getAsBoolean(), "PUT should succeed: " + obj );

        assertEquals( Users.ADMIN, engine.getManager( PageManager.class ).getPage( "RestPutPage" ).getAuthor(),
                "the stored author is the login, never the caller-supplied 'evil' nor the full name" );
    }

    @Test
    void testPatchPage_authenticatedSession_recordsTheLogin() throws Exception {
        engine.saveTextAsJanne( "RestPatchMergePage", "---\ntype: article\n---\nBody." );
        loginAs( Users.ADMIN, Users.ADMIN_PASS );

        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "category", "authorship" );
        patchBody.add( "metadata", meta );
        patchBody.addProperty( "action", "merge" );

        final JsonObject obj = gson.fromJson( doPatch( "RestPatchMergePage", patchBody ), JsonObject.class );
        assertTrue( obj.get( "success" ).getAsBoolean(), "PATCH should succeed: " + obj );

        assertEquals( Users.ADMIN, engine.getManager( PageManager.class ).getPage( "RestPatchMergePage" ).getAuthor() );
    }
```
(An anonymous PUT keeping the body author is already covered by the existing `testPutPageWithAuthor` and by `AuthorKeysTest`; do not add a test that removes the shared mock session here — `TestEngine#adminSession()` caches its session object, and removing it from the monitor would silently turn the other admin tests anonymous under shuffled method order.)

(e) `DerivedIngestResourceTest.java` — in `doPost_callerSuppliedAuthorIgnored_sessionPrincipalUsed` replace the comment and the last two assertions (lines 391-395) with:

```java
        // What is recorded is the session's LOGIN ("admin"), not its full name ("Administrator"), and never the spoofed "evil".
        assertEquals( "admin", capturedOpts.get().author(),
                "Author must be the login; got: " + capturedOpts.get().author() );
```

(f) `ObsidianImportResourceTest.java` — add after `applyStartsJobAnd202`:

```java
    @Test
    void applyRecordsTheCallersLoginAsTheAuthorOfImportedPages() throws Exception {
        loginAdmin();   // login "admin"; the session's user principal is the full name "Administrator"
        final java.util.concurrent.atomic.AtomicReference< String > recordedAuthor = new java.util.concurrent.atomic.AtomicReference<>();
        final com.wikantik.importer.ImportPageSink sink = new com.wikantik.importer.ImportPageSink() {
            @Override public boolean pageExists( final String name ) {
                return false;
            }
            @Override public java.util.List< String > savePage( final String name, final String body,
                    final Map< String, Object > metadata, final String author, final String changeNote ) {
                recordedAuthor.set( author );
                return java.util.List.of();
            }
            @Override public void storeAttachment( final String page, final String fileName,
                    final java.io.InputStream in, final String author ) {
                // this vault has no attachments
            }
        };
        final ObsidianImportResource s = new ObsidianImportResource() {
            @Override protected ImportJobRegistry newRegistry( final ImportLimits l ) {
                return new ImportJobRegistry( l.maxConcurrent(), Clock.systemUTC(), Runnable::run );   // run the job inline
            }
            @Override protected com.wikantik.importer.VaultImportService importService() {
                final ImportLimits limits = ImportLimits.defaults();
                return new com.wikantik.importer.VaultImportService( limits,
                    new com.wikantik.importer.VaultImportPlanner( com.wikantik.api.frontmatter.schema.FrontmatterSchema.defaultSchema(),
                        com.wikantik.importer.AttachmentGate.fromProperties( new java.util.Properties() ), limits.maxPages() ),
                    () -> com.wikantik.importer.FakeWikiSnapshot.EMPTY, sink );
            }
            @Override protected boolean canCreatePages( final Session ss ) { return true; }
            @Override protected boolean isAdmin( final Session ss ) { return true; }
            @Override protected Path spoolDir() { return spool; }
        };
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( engine.getServletContext() ).when( cfg ).getServletContext();
        s.init( cfg );

        final byte[] zip = oneNote();
        final String hash = planHash( s, zip );
        s.doPost( post( "/apply", zip, zip.length, Map.of( "planHash", hash ) ), resp().mock() );

        assertEquals( Users.ADMIN, recordedAuthor.get() );
    }
```

(g) New `wikantik-rest/src/test/java/com/wikantik/rest/AdminAuthorResolutionTest.java`:

```java
package com.wikantik.rest;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The admin writers record the admin's login as the revision author, never the full name ("Administrator"). */
class AdminAuthorResolutionTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @BeforeEach
    void anonymous() {
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
    }

    private static void loginAdmin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = WikiSession.getWikiSession( engine, request );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
    }

    private static < T extends GenericServlet > T initialised( final T servlet ) throws Exception {
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
        return servlet;
    }

    @Test
    void adminClusterRenameAuthorIsTheAdminsLogin() throws Exception {
        loginAdmin();
        assertEquals( Users.ADMIN, initialised( new AdminClusterResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
    }

    @Test
    void adminDerivedReflowAuthorIsTheAdminsLogin() throws Exception {
        loginAdmin();
        assertEquals( Users.ADMIN, initialised( new AdminDerivedResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
    }

    @Test
    void anUnauthenticatedCallerStillFallsBackToSystem() throws Exception {
        assertEquals( "system", initialised( new AdminClusterResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
        assertEquals( "system", initialised( new AdminDerivedResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main -Dtest='AuthorKeysTest,AuthorKeyWritersTest,AttachmentServletTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` (`cannot find symbol: class AuthorKeys`). After adding only the empty class in Step 3 the writer tests fail with e.g. `expected: <admin> but was: <Administrator>` (default author), `expected: <admin> but was: <Administrator>` (rename) and `expected: <admin> but was: <Administrator>` (group creator) — that is the defect being fixed.

- [ ] **Step 3: Implement**

**3a. New `AuthorKeys.java`** (licence header as in sibling files):

```java
package com.wikantik.auth;

import com.wikantik.api.core.Session;

import java.security.Principal;

/**
 * The storage key for "who did this": an account's <em>login</em>, not its display name.
 *
 * <p>{@link Session#getUserPrincipal()} is the profile's full name, which is user-editable and (before display names became
 * unique) not even distinct, so authorship stored from it could be reassigned by a rename and could not be told apart for two
 * people sharing a name. Every authorship writer — page saves, renames, attachments, group creator/modifier — stores this key
 * instead; {@code PublicIdentity#displayName} resolves it to the current display name when it is shown.</p>
 *
 * <p>Anonymous and asserted sessions have no login: their user principal (an IP address or the asserted name) is returned
 * unchanged, exactly as before.</p>
 */
public final class AuthorKeys {

    private AuthorKeys() { }

    /**
     * @return the login principal's name when {@code session} is authenticated; otherwise the user principal's name;
     *         {@code "Guest"} for a {@code null} session; {@code null} only for a session with no principals at all
     */
    public static String authorKeyFor( final Session session ) {
        if ( session == null ) {
            return WikiPrincipal.GUEST.getName();
        }
        if ( session.isAuthenticated() ) {
            final Principal login = session.getLoginPrincipal();
            if ( login != null && login.getName() != null && !login.getName().isBlank() ) {
                return login.getName();
            }
        }
        final Principal user = session.getUserPrincipal();
        return user == null ? null : user.getName();
    }
}
```

**3b. `DefaultPageLifecycle.java`** — add `import com.wikantik.auth.AuthorKeys;`; replace lines 85-89:

```java
        // Set the page author: the login (the stable storage key), never the session's full name; PublicIdentity turns it
        // into the current display name when it is shown.
        final Page page2 = context.getPage();
        final String authorKey = AuthorKeys.authorKeyFor( context.getWikiSession() );
        if ( page2.getAuthor() == null && authorKey != null ) {
            page2.setAuthor( authorKey );
        }
```

**3c. `DefaultPageRenamer.java`** — add `import com.wikantik.auth.AuthorKeys;`; line 123: `toPage.setAuthor( AuthorKeys.authorKeyFor( context.getWikiSession() ) );`; line 200: `referrerPage.setAuthor( AuthorKeys.authorKeyFor( context.getWikiSession() ) );`.

**3d. `AttachmentServlet.java`** — add `import com.wikantik.auth.AuthorKeys;`, remove `import java.security.Principal;` (its only use is line 515). Line 515 becomes `final String author = AuthorKeys.authorKeyFor( context.getWikiSession() );`; lines 539-541:

```java
            if( author != null ) {
                att.setAuthor( author );
            }
```
and line 555 `LOG.info( "User {} uploaded attachment to {} called {}, size {}", author, parentPage, filename, att.getSize() );`.

**3e. `DefaultGroupManager.java`** — add `import com.wikantik.auth.AuthorKeys;`; line 310 becomes `groupDatabase.save( group, groupModifier( session ) );` and add the helper beside the other private methods:

```java
    /** The principal recorded as the group's creator/modifier: the saving user's login (see {@link AuthorKeys}). */
    private static Principal groupModifier( final Session session ) {
        final String key = AuthorKeys.authorKeyFor( session );
        return key == null ? session.getUserPrincipal() : new WikiPrincipal( key );
    }
```
(`Principal`, `WikiPrincipal` and `Session` are already imported in this file.)

**3f. `PageResource.java`** — add `import com.wikantik.auth.AuthorKeys;`. PUT (lines 462-469):

```java
            // Resolve the author: when the session is authenticated, the session's LOGIN is authoritative (ignore any
            // caller-supplied body author to prevent spoofing). Fall back to the body author only for the
            // unauthenticated/headless case.
            final Session wikiSession = Wiki.session().find( engine, request );
            final String effectiveAuthor = wikiSession.isAuthenticated()
                    ? AuthorKeys.authorKeyFor( wikiSession )
                    : author;
            optionsBuilder.author( effectiveAuthor );
```
PATCH (lines 712-722): change `effectiveAuthor = wikiSession.getUserPrincipal().getName();` to `effectiveAuthor = AuthorKeys.authorKeyFor( wikiSession );` and update the comment to say "the login".

**3g. REST writers** (add `import com.wikantik.auth.AuthorKeys;` to each):
- `DerivedIngestResource.java:111`: `final String  author  = session.isAuthenticated() ? AuthorKeys.authorKeyFor( session ) : null;`
- `ObsidianImportResource.java:210`: `final String author = AuthorKeys.authorKeyFor( session );`
- `AdminClusterResource.java:154` and `AdminDerivedResource.java:202`: `return session.isAuthenticated() ? AuthorKeys.authorKeyFor( session ) : "system";`

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/jakefear/source/jspwiki
mvn -B -o test -pl wikantik-main \
  -Dtest='AuthorKeysTest,AuthorKeyWritersTest,AttachmentServlet*Test,PageRenamerTest,DefaultPageRenamer*Test,DefaultGroupManager*Test,GroupManagerTest,VersioningFileProviderTest,PageSaveHelperTest,LogLevelEnforcementTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
bin/agent-build.sh start inst-main -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait inst-main 540
mvn -B -o test -pl wikantik-rest \
  -Dtest='PageResourceTest,DerivedIngestResourceTest,ObsidianImportResourceTest,AdminClusterResourceTest,AdminDerivedResourceTest,AdminAuthorResolutionTest,HistoryResourceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest
```
Expected: pass. Because new page versions now carry the login, grep the module tests once for assertions that expected the full name as author: `grep -rn '"Administrator"' wikantik-*/src/test --include=*.java | grep -v InMemoryUserDatabase` should list only comments/fixtures (the two comments this task rewrote were the only ones).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/AuthorKeys.java \
  wikantik-main/src/main/java/com/wikantik/page/subsystem/lifecycle/DefaultPageLifecycle.java \
  wikantik-main/src/main/java/com/wikantik/content/DefaultPageRenamer.java \
  wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java \
  wikantik-main/src/main/java/com/wikantik/auth/authorize/DefaultGroupManager.java \
  wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/DerivedIngestResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/ObsidianImportResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AdminClusterResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/AdminDerivedResource.java \
  wikantik-main/src/test/java/com/wikantik/auth/AuthorKeysTest.java \
  wikantik-main/src/test/java/com/wikantik/auth/AuthorKeyWritersTest.java \
  wikantik-main/src/test/java/com/wikantik/attachment/AttachmentServletTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/DerivedIngestResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/ObsidianImportResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/AdminAuthorResolutionTest.java
git commit -m "feat(identity): authorship writers store the login, not the profile full name

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Section notes

1. **Commit trailer.** The contract's template says `Claude Opus 5.5`; per the coordinator's instruction the plan uses the trailer this session specifies, `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
2. **Task 4 ↔ Task 5 ordering (decision).** Task 4 contains no `PublicIdentity` code. It creates `ProfileChangeNotifier.profileSaved(Engine, login)` — the single call every profile-writing path makes — and Task 5 adds the `PublicIdentity.invalidate` call *inside* that method plus `profileDeleted` and its three delete call sites. Later sections must call `ProfileChangeNotifier.profileSaved/profileDeleted` (not `PublicIdentity.invalidate` directly) from any new profile-writing path.
3. **Contract additions (all additive).** `ProfileNameRules.NameViolation` / `Kind` and `displayNameViolation`/`nameChangeViolation`/`fullNameChangeViolation` (the contract's `*Error` methods are thin wrappers); `RestJson.sendNameViolation`; `ProfileChangeNotifier`; `WikiSession#refreshUserProfile`; `SessionMonitor#refreshProfile`; `PublicIdentities.forEngine(Engine)` (never-null accessor — recommend Section B/C tasks use it, or `AuthSubsystemBridge.fromLegacyEngine(engine).publicIdentity()` plus a null check, wherever tests use a bare/mocked engine).
4. **`getManager` rule satisfied.** `PublicIdentity` is not an engine manager. Access paths (documented in Task 5): REST `getSubsystems().auth().publicIdentity()`; factories/wiring receiving `AuthSubsystem.Services` constructor-inject it (e.g. `PageSubsystemFactory.create(Deps)` already has `deps.auth()`); everything else `AuthSubsystemBridge.fromLegacyEngine(engine).publicIdentity()` (a `*SubsystemBridge`, allowed) or `PublicIdentities.forEngine(engine)`. No new `WikiEngine#getManager` caller was added, so `archunit_store` must not change.
5. **Mockito-built `AuthSubsystem.Services`.** `WikiSubsystemsTestFactory.mockRecord(AuthSubsystem.Services.class)` now fills `publicIdentity` with a Mockito mock whose `displayName(...)` returns `null`. Section B/C REST tests that assert real display names must construct a real `new DefaultPublicIdentity( userDatabase, Clock.systemUTC() )` (a Mockito `UserDatabase` stubbing `findByLoginName` is enough; `loginForDisplayName` needs `findAllProfiles`) and pass it as the 9th `Services` argument. Eight existing 8-argument `new AuthSubsystem.Services(...)` call sites are fixed in Task 5 (listed there).
6. **`DefaultPublicIdentity` semantics the later sections should know:** it holds an in-memory snapshot of the account table (login, display name, *stored* uid) so every call — including "not an account" — is O(1) with no database access; the snapshot is rebuilt at most every 60 s, on `invalidate`, and after a failed load (5 s backoff; the previous snapshot keeps being served). An *account whose display name is blank* resolves to `unknown contributor` (never its login); a stored value that is not an account passes through unless it contains `@`; `userIdForLogin` is empty when `users.uid` is NULL (`findByLoginName` would invent a random uid on every read, so the snapshot uses the new `UserDatabase#accountSummaries()` query); `loginForDisplayName` is empty when two accounts share the (legacy) name. With no snapshot and a failing database it logs WARN and returns `unknown` / `unknown contributor`. A rename made directly in the database shows within 60 s; on this node, through any REST/SCIM/SSO write path, it shows at once.
7. **Behaviour flips beyond the spec text (call out in the docs/CHANGELOG task):** (a) admin `POST /admin/users` now *requires* `fullName` (missing/blank → 400, taken → 409); 16 existing tests are updated and the admin form (Task 20) must send one. (b) SCIM create without `name.formatted` also gets `Member N`. (c) SCIM name errors split: collision → 409 `uniqueness` (as before), malformed/reserved → 400 `invalidValue` (was 409). (d) SSO: a reserved/absent/email-shaped claim now yields `Member N` instead of the login/`User <login>`. (e) `applyFullName` stores the trimmed name.
8. **Other write sites found while verifying the Task 6 inventory:** none missed. Left alone on purpose and listed in Task 6: the page-read audit actor (`PageResource.java:251`), `KnowledgeGraphResource.principalName` (log line), constant `"Knowledge Admin"` author labels, MCP client names, REST `AttachmentResource` (never sets an author). `GroupManager.parseGroup(Context, boolean)` still adds the creator's **user principal** (full name) as the default *member* of a new group — membership is evaluated against the login (`WikiSession.isInGroup` uses the login principal), so that default member is already inert; it is not an authorship field and is out of this plan's scope, but worth a follow-up ticket.
9. **Prod data / ordering.** The one-time dedupe script (Task 21) must run *before* the rules go live for the prod duplicate group ("Jake Fear" ×4) only if those four accounts need to *change* names; because unchanged names are never re-validated, deploying first and deduping after is safe. Existing history rows that hold full names or email-shaped strings are handled by `PublicIdentity` (legacy name passes through; email-shaped masks).
10. **ITs to re-check in the Section E verification run:** SCIM ITs (`ScimUsersIT`, `ScimDeactivationAuthIT`, `ScimGroupsIT`) use fixed or userName-derived `name.formatted` values — a leftover account from an aborted earlier run would now 409; the IT users created via `POST /admin/users` use distinct `fullName`s (`IT Bulk Test <login>`, `IT SelfDelete <login>`, `Scoped … Test Account`) and still satisfy the 2–64/no-`@` rules as long as the generated logins contain no `@`.
11. **PMD/ArchUnit.** Every task ends with `mvn -B -o pmd:check -Pcomplexity-gate -pl <modules>`; `ProfileNameRules` and `DefaultPublicIdentity` were written with small private methods to stay under the ratchet, but the baseline file `build-support/pmd-complexity-baseline.properties` was not inspected — if the gate flags a *new* violation, split the method rather than touching the baseline.
12. **Test-order hazards (surefire 3.6 shuffles methods).** `TestEngine#adminSession()` is one-shot and caches its session object; the new tests therefore log in explicitly on the shared mock session (`loginAs`/`contextAs`) and never remove the shared session id in `PageResourceTest`. `ProfileSessionRefreshTest` and `AuthorKeysTest` build their own `TestEngine`.
13. **Contract items that turned out imprecise against the real code.** (a) The contract says `fullNameChangeError` applies `displayNameError`; but the admin editor's create path and SCIM call `nameChangeError` directly, so the rules were put inside `nameChangeViolation` (which both wrappers use). (b) The contract text says `Services` "gains a LAST record component" — correct, but eight existing 8-argument constructions in tests break at compile time; Task 5 lists and fixes them. (c) Contract/spec say SCIM conflicts are `409 uniqueness`; the plan keeps that for collisions and uses `400 invalidValue` for malformed names (previously everything was 409). (d) Contract line numbers: `AttachmentServlet` is lines 515 / 539-541 / 555 (not ~540); `DefaultPageLifecycle` 87-88, `DefaultPageRenamer` 123/200, `DefaultGroupManager` 310, `PageResource` 466-469 / 718, `DerivedIngestResource` 111, `ObsidianImportResource` 210, `AdminClusterResource` 154, `AdminDerivedResource` 202 were all verified exact.
14. **No "list all accounts" on `PublicIdentity`.** Task 15's `GET /api/users/mentionable` (`{id: uid, displayName}`) cannot be built from `PublicIdentity` alone; it must keep enumerating accounts through `getSubsystems().auth().users().getUserDatabase().findAllProfiles()` (as the endpoint does today) and emit `getUid()` / `getFullname()` — skipping blank display names — instead of `getLoginName()`.
15. **Error shape for the frontend (Task 20).** Profile/admin rule violations are the standard REST error body `{"error":true,"status":400|409,"message":"<reason>"}` (no per-field key); the SPA shows `message` inline next to the display-name field. Messages: "A display name is required", "A display name must be 2 to 64 characters long", "A display name cannot contain control characters", "A display name cannot contain '@'; it would look like an email address", "The name 'X' is reserved for a role or group; choose a different name", "The name 'X' is another account's login name; choose a different name", "The display name 'X' is already in use; choose a different name". `POST /admin/users` now rejects a missing/blank `fullName` with 400 ("A display name is required"), so the admin create form must send one.
16. **Dedupe script (Task 21) must emit rule-valid names.** Renames like `<name> 2` must stay within 2–64 characters (truncate the stem like `ProfileNameRules.availableDisplayName` does) and `Member N` replacements for email-shaped/blank names must skip numbers already in use, case-insensitively; running it before or after the deploy is safe because unchanged names are never re-validated.
17. **Other cross-section hooks.** Task 12 (`MyPagesResource`) should match `AuthorKeys.authorKeyFor(session)` (equivalently the session's login principal) exactly against the stored author; Task 13's `GET /api/auth/user` `userId` is `publicIdentity.userIdForLogin(session.getLoginPrincipal().getName())`; Task 14's `MentionService` resolves `user:<uid>` via `PublicIdentity.loginForUserId`; any new profile-writing path added by a later section must call `ProfileChangeNotifier.profileSaved` (and `profileDeleted` on delete). New production code in every section must avoid unjustified `LOG.error` (`LogLevelEnforcementTest`) and any `WikiEngine#getManager` call (`DecompositionArchTest`; restore `wikantik-main/src/test/resources/archunit_store` from git if a red run rewrites it).
18. **Additional `UserDatabase` methods (all `default`, so no other implementation changes):** `isFullNameTakenIgnoreCase` (contract), plus `isLoginNameTakenIgnoreCase` and `accountSummaries()`; `JDBCUserDatabase` overrides all three with single narrow queries (a `findAllProfiles()` scan deserializes every row's attribute blob and throws on any corrupt row — observed while verifying this plan).
19. **`AuthSubsystem.Services` 8-argument constructions.** Verified by grep: `AuthSubsystemFactory`, `AuthSubsystemBridge`, `DefaultPageGraphServiceTest` (×3), `RememberMeAuthFilterTest` (×2), `AdminApiKeysResourceTest` (×**3**: lines 573, 590, 609, not 2) and `AdminOverviewResourceFullTest` (×3: lines 215, 333, 354) — Task 5 lists them all. `AuthSubsystemFactory.create` yields `publicIdentity == null` when no `UserManager` is registered (bare/mocked engine); consumers that must not see `null` use `PublicIdentities.forEngine(engine)`.
20. **How this plan was checked.** Every main-source edit and every new/changed test in Tasks 1–6 was applied to a scratch copy of the tree (following the instructions and line ranges as written), compiled with `javac` against the existing build output, and the touched test classes were run with a JUnit launcher (including the `@RequiresPostgres` ones against a real pgvector container): all green. The PMD complexity ruleset (`build-support/pmd-complexity-ruleset.xml`, PMD 7.17) was run over the changed sources: `ProfileNameRules` was split into `ProfileNameRules` + `DisplayNameRules` + `DisplayNameAllocator`, `DefaultPublicIdentity` into `DefaultPublicIdentity` + `AccountSnapshot`, and `ScimUserResource`'s new helper moved to `ScimUserFields` so that no class gains a violation outside `build-support/pmd-complexity-baseline.properties`. Not run here: the Maven build itself, `DecompositionArchTest`, the Selenide/REST ITs, and re-verification of the final text after the last edits (the two splits and the snapshot rewrite were re-compiled and re-tested in isolation).

---

# Section B — Read surfaces (Tasks 7–12)

# Section B — Read surfaces (Tasks 7–12)

Preconditions: Task 5 (`com.wikantik.api.identity.PublicIdentity`, `AuthSubsystem.Services#publicIdentity()`, `DefaultPublicIdentity` wired by `AuthSubsystemFactory`) and Task 6 (`com.wikantik.auth.AuthorKeys`, writers store the login) are committed. Every task below is independently committable and ordered 7 → 12.

## Conventions for this section

- **License header.** Every new `.java` file begins with the 17-line Apache header: copy lines 1–17 of `wikantik-rest/src/main/java/com/wikantik/rest/RecentChangesResource.java` verbatim.
- **Environment.** Run Maven from a shell with no `WIKANTIK_*` variables (`unset $(env | grep -o '^WIKANTIK_[A-Za-z_]*')`).
- **Refreshing upstream modules.** `wikantik-rest`, `wikantik-knowledge` and `wikantik-tools` resolve `wikantik-api`/`wikantik-main` (and main's **test-jar**) from `~/.m2`. After you change either upstream module, refresh it before running a downstream module:
  ```bash
  bin/agent-build.sh start b-upstream -- mvn -B -o install -pl wikantik-api,wikantik-main -DskipTests
  bin/agent-build.sh wait b-upstream 540      # 0 = SUCCESS; re-run wait while it exits 2
  ```
  (`-DskipTests` still builds the test-jar; `-Dmaven.test.skip` would not.) When only `wikantik-api` changed, `mvn -B -o install -pl wikantik-api -DskipTests` is enough.
- **Where names are resolved (design decisions, used throughout).**
  1. *One resolution per datum.* A stored principal is turned into a display name exactly once, at the place that turns it into public output. Re-resolving an already-resolved name is **wrong** (a display name can equal another account's login in legacy data — seeded `Fred` has display name `Fred Flintstone`, so resolving the string `Fred` twice would swap people).
  2. REST resources and plugins resolve at the surface: `getSubsystems().auth().publicIdentity()` (REST) or the engine's `AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity()` (non-REST, evaluated lazily at call time because `AuthSubsystem` is built *after* the search/recent-article managers initialise).
  3. Where one producer fans out to many surfaces, the **producer** resolves: `DefaultContextRetrievalService` (REST search, `/tools` search_wiki, MCP `retrieve_context`/`get_page`/`list_pages` all serialise its `RetrievedPage`) and `DefaultRecentArticlesManager` (Atom feed, `/api/recent-articles`, both plugins). `SearchResource`, `SearchWikiTool`, `AtomFeedServlet`, `RecentArticlesServlet`, `RecentArticles` and the three MCP retrieval tools therefore need **no code change**.
  4. The for-agent projection is cached by `DefaultForAgentProjectionService`, so it is resolved **after** the cache, at the two serving surfaces, via `ForAgentProjection#withDisplayNames`.
  5. *Frontmatter* `author:`/`verified_by:` are editor-typed text: they are replaced only when the value is an existing login (`PublicIdentity#userIdForLogin` present); anything else — `claude-code-researcher`, an editor-typed email — is shown as written (spec §"Out of scope").
  6. Version authors use `PublicIdentity#displayName` (legacy full names unchanged, email-shaped unknowns → `unknown contributor`); a `null` author stays `null` (the field is omitted exactly as today).
- **Commit trailer.** Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 7: Page / list / history / recent-changes APIs

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/identity/IdentityDisplay.java`
- Create: `wikantik-api/src/test/java/com/wikantik/api/identity/IdentityDisplayTest.java`
- Create: `wikantik-main/src/test/java/com/wikantik/AuthoredPages.java` (test-jar helper reused by Tasks 7, 8, 10, 12)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java` (imports; `doGet` ~296–298; `doPatch` ~730–731)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java` (`doGet` ~156; `toEntry` ~197–209)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/HistoryResource.java` (~44, 83, 92)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/RecentChangesResource.java` (~61–78)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java`, `PageListResourceTest.java`, `HistoryResourceTest.java`, `RecentChangesResourceTest.java`

**Interfaces:**
- Consumes: `PublicIdentity` (`displayName`, `userIdForLogin`, constants `UNKNOWN_CONTRIBUTOR`) from Task 5; `getSubsystems().auth().publicIdentity()`; stored authorship = login (Task 6). In `TestEngine` the seeded account `janne` has full name `Janne Jalkanen` and `Administrator` is `admin`'s.
- Produces:
  - `IdentityDisplay.ofAuthor( PublicIdentity, String )` — `displayName` of a stored author; `null` stays `null`; a `null` identity returns the stored value (unit tests only).
  - `IdentityDisplay.ofLoginOrText( PublicIdentity, String )` — display name when the value is an existing login, otherwise the value unchanged.
  - `IdentityDisplay.frontmatter( PublicIdentity, Map<String,Object> )` — copy of a frontmatter map with `author` and `verified_by` passed through `ofLoginOrText` (same instance when nothing changes).
  - `com.wikantik.AuthoredPages.save( TestEngine, String pageName, String content, String storedAuthor )` — saves a page recording exactly `storedAuthor` as the version author, through an isolated mock session (never touches the shared `mock-session`).

- [ ] **Step 1: Write the failing tests**

`wikantik-api/src/test/java/com/wikantik/api/identity/IdentityDisplayTest.java`:

```java
package com.wikantik.api.identity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class IdentityDisplayTest {

    /** One known account: login {@code janne} → {@code Janne Jalkanen}; behaves like the real service otherwise. */
    private static final class FakeIdentity implements PublicIdentity {
        private final Map< String, String > namesByLogin = Map.of( "janne", "Janne Jalkanen" );

        @Override public String displayName( final String stored ) {
            if ( stored == null || stored.isBlank() ) {
                return UNKNOWN;
            }
            if ( namesByLogin.containsKey( stored ) ) {
                return namesByLogin.get( stored );
            }
            return stored.indexOf( '@' ) >= 0 ? UNKNOWN_CONTRIBUTOR : stored;
        }
        @Override public Optional< String > userIdForLogin( final String login ) {
            return namesByLogin.containsKey( login ) ? Optional.of( "uid-" + login ) : Optional.empty();
        }
        @Override public Optional< String > loginForUserId( final String uid ) { return Optional.empty(); }
        @Override public Optional< String > loginForDisplayName( final String displayName ) { return Optional.empty(); }
        @Override public void invalidate( final String login ) { }
    }

    private final PublicIdentity identity = new FakeIdentity();

    @Test
    void ofAuthor_resolvesALoginToItsDisplayName() {
        assertEquals( "Janne Jalkanen", IdentityDisplay.ofAuthor( identity, "janne" ) );
    }

    @Test
    void ofAuthor_leavesLegacyFullNamesAndAgentLabelsAlone() {
        assertEquals( "Legacy Person", IdentityDisplay.ofAuthor( identity, "Legacy Person" ) );
        assertEquals( "claude-code-researcher", IdentityDisplay.ofAuthor( identity, "claude-code-researcher" ) );
    }

    @Test
    void ofAuthor_masksAnEmailShapedValueThatIsNoAccount() {
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, IdentityDisplay.ofAuthor( identity, "gone.user@example.test" ) );
    }

    @Test
    void ofAuthor_keepsAnAbsentAuthorAbsent() {
        assertNull( IdentityDisplay.ofAuthor( identity, null ) );
    }

    @Test
    void ofAuthor_withoutAnIdentityServiceReturnsTheStoredValue() {
        assertEquals( "janne", IdentityDisplay.ofAuthor( null, "janne" ) );
    }

    @Test
    void ofLoginOrText_resolvesOnlyExistingLogins() {
        assertEquals( "Janne Jalkanen", IdentityDisplay.ofLoginOrText( identity, "janne" ) );
        assertEquals( "claude-code-researcher", IdentityDisplay.ofLoginOrText( identity, "claude-code-researcher" ) );
    }

    @Test
    void ofLoginOrText_showsTypedTextAsWrittenEvenWhenEmailShaped() {
        assertEquals( "someone@company.test", IdentityDisplay.ofLoginOrText( identity, "someone@company.test" ),
                "editor-typed text is not an account, so it is not rewritten" );
        assertEquals( "  ", IdentityDisplay.ofLoginOrText( identity, "  " ) );
        assertNull( IdentityDisplay.ofLoginOrText( identity, null ) );
    }

    @Test
    void frontmatter_resolvesAuthorAndVerifiedByWhenTheyAreLoginsAndNothingElse() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "article" );
        metadata.put( "author", "janne" );
        metadata.put( "verified_by", "ops-team" );
        metadata.put( "reviewer", "janne" );
        metadata.put( "tags", List.of( "janne" ) );

        final Map< String, Object > shown = IdentityDisplay.frontmatter( identity, metadata );

        assertEquals( "Janne Jalkanen", shown.get( "author" ) );
        assertEquals( "ops-team", shown.get( "verified_by" ) );
        assertEquals( "janne", shown.get( "reviewer" ), "only author and verified_by name a principal" );
        assertEquals( List.of( "janne" ), shown.get( "tags" ) );
        assertEquals( List.of( "type", "author", "verified_by", "reviewer", "tags" ), List.copyOf( shown.keySet() ),
                "key order is preserved" );
        assertEquals( "janne", metadata.get( "author" ), "the caller's map is never mutated" );
    }

    @Test
    void frontmatter_returnsTheSameInstanceWhenNothingChanges() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "author", "claude-code-researcher" );
        metadata.put( "verified_by", List.of( "not", "a", "string" ) );

        assertSame( metadata, IdentityDisplay.frontmatter( identity, metadata ) );
        assertSame( metadata, IdentityDisplay.frontmatter( null, metadata ) );
        assertNull( IdentityDisplay.frontmatter( identity, null ) );
    }
}
```

`wikantik-main/src/test/java/com/wikantik/AuthoredPages.java`:

```java
package com.wikantik;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Test helper: saves a page whose version author is exactly the string given, the way a legacy writer
 * (a full name), another account (a login) or an MCP client (an agent label) would have recorded it.
 *
 * <p>Unlike {@code TestEngine#saveText} this authenticates a throw-away <em>isolated</em> mock session, so it
 * never changes who the shared {@code HttpMockFactory.SHARED_SESSION_ID} session is logged in as.</p>
 */
public final class AuthoredPages {

    private AuthoredPages() {
    }

    public static void save( final TestEngine engine, final String pageName, final String content,
                             final String storedAuthor ) throws WikiException {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/" + pageName );
        final Session session = SessionMonitor.getInstance( engine ).find( request.getSession() );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );

        final Page page = Wiki.contents().page( engine, pageName );
        page.setAuthor( storedAuthor );
        final Context context = Wiki.context().create( engine, request, page );
        engine.getManager( PageManager.class ).saveText( context, content );
    }
}
```

`PageResourceTest` — (a) in `tearDown()` extend the `deleteQuietly` list with `"RestAuthorLoginPage", "RestAuthorLegacyPage", "RestAuthorEmailPage", "RestAuthorFrontmatterPage", "RestAuthorPatchPage"`; (b) add the import `com.wikantik.AuthoredPages;`; (c) add before the `// ----- Helper methods -----` comment:

```java
    // ----- Display names instead of logins -----

    @Test
    void testGetPage_authorStoredAsLoginIsReturnedAsDisplayName() throws Exception {
        AuthoredPages.save( engine, "RestAuthorLoginPage", "Body.", "janne" );

        final JsonObject obj = gson.fromJson( doGet( "RestAuthorLoginPage" ), JsonObject.class );

        assertEquals( "Janne Jalkanen", obj.get( "author" ).getAsString() );
    }

    @Test
    void testGetPage_legacyAuthorStringPassesThroughUnchanged() throws Exception {
        AuthoredPages.save( engine, "RestAuthorLegacyPage", "Body.", "Legacy Person" );

        final JsonObject obj = gson.fromJson( doGet( "RestAuthorLegacyPage" ), JsonObject.class );

        assertEquals( "Legacy Person", obj.get( "author" ).getAsString() );
    }

    @Test
    void testGetPage_emailShapedAuthorOfNoAccountIsMasked() throws Exception {
        AuthoredPages.save( engine, "RestAuthorEmailPage", "Body.", "gone.user@example.test" );

        final String json = doGet( "RestAuthorEmailPage" );

        assertEquals( "unknown contributor",
                gson.fromJson( json, JsonObject.class ).get( "author" ).getAsString() );
        assertFalse( json.contains( "gone.user@example.test" ), "an email-shaped author must never be served" );
    }

    @Test
    void testGetPage_frontmatterAuthorAndVerifiedByResolveOnlyWhenTheyAreLogins() throws Exception {
        engine.saveText( "RestAuthorFrontmatterPage",
                "---\nauthor: janne\nverified_by: janne\nreviewer: janne\n---\nBody." );

        final JsonObject meta = gson.fromJson( doGet( "RestAuthorFrontmatterPage" ), JsonObject.class )
                .getAsJsonObject( "metadata" );

        assertEquals( "Janne Jalkanen", meta.get( "author" ).getAsString() );
        assertEquals( "Janne Jalkanen", meta.get( "verified_by" ).getAsString() );
        assertEquals( "janne", meta.get( "reviewer" ).getAsString(), "only author and verified_by name a principal" );
    }

    @Test
    void testGetPage_frontmatterFreeTextAuthorIsShownAsWritten() throws Exception {
        engine.saveText( "RestAuthorFrontmatterPage", "---\nauthor: claude-code-researcher\n---\nBody." );

        final JsonObject meta = gson.fromJson( doGet( "RestAuthorFrontmatterPage" ), JsonObject.class )
                .getAsJsonObject( "metadata" );

        assertEquals( "claude-code-researcher", meta.get( "author" ).getAsString() );
    }

    @Test
    void testPatchPage_responseAuthorIsADisplayName() throws Exception {
        engine.adminSession();   // authenticates the shared mock session as login "admin" (display name "Administrator")
        engine.saveText( "RestAuthorPatchPage", "---\ntype: article\n---\nBody." );

        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "category", "new-category" );
        patchBody.add( "metadata", meta );
        patchBody.addProperty( "action", "merge" );

        final JsonObject obj = gson.fromJson( doPatch( "RestAuthorPatchPage", patchBody ), JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( "Administrator", obj.get( "author" ).getAsString() );
    }
```

`PageListResourceTest` — extend the `tearDown()` `deleteQuietly` with `"RestListByLogin", "RestListByLegacy"`, add `import com.wikantik.AuthoredPages;` and add before `// ----- Helper methods -----`:

```java
    @Test
    void testListPages_authorsAreDisplayNames() throws Exception {
        AuthoredPages.save( engine, "RestListByLogin", "Body.", "janne" );
        AuthoredPages.save( engine, "RestListByLegacy", "Body.", "Legacy Person" );

        final JsonArray pages = gson.fromJson( doGetParams( java.util.Map.of( "prefix", "RestListBy" ) ),
                JsonObject.class ).getAsJsonArray( "pages" );

        assertEquals( 2, pages.size() );
        // alphabetical: RestListByLegacy, RestListByLogin
        assertEquals( "Legacy Person", pages.get( 0 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( "Janne Jalkanen", pages.get( 1 ).getAsJsonObject().get( "author" ).getAsString() );
    }
```

`HistoryResourceTest` — add `import com.wikantik.AuthoredPages;` and before `// ----- Helper methods -----`:

```java
    @Test
    void testVersionAuthorsAreDisplayNamesAndLegacyStringsPassThrough() throws Exception {
        AuthoredPages.save( engine, "RestHistoryAuthors", "First version.", "Legacy Person" );
        AuthoredPages.save( engine, "RestHistoryAuthors", "Second version.", "janne" );
        try {
            final JsonArray versions = gson.fromJson( doGet( "RestHistoryAuthors" ), JsonObject.class )
                    .getAsJsonArray( "versions" );
            final java.util.Set< String > authors = new java.util.HashSet<>();
            versions.forEach( v -> authors.add( v.getAsJsonObject().get( "author" ).getAsString() ) );

            assertEquals( java.util.Set.of( "Legacy Person", "Janne Jalkanen" ), authors );
        } finally {
            engine.deleteQuietly( "RestHistoryAuthors" );
        }
    }
```

`RecentChangesResourceTest` — add `import com.wikantik.AuthoredPages;` and before `// ----- Helper methods -----`:

```java
    @Test
    void testRecentChangesAuthorsAreDisplayNames() throws Exception {
        AuthoredPages.save( engine, "RestRecentByLogin", "Body.", "janne" );
        AuthoredPages.save( engine, "RestRecentByLegacy", "Body.", "Legacy Person" );
        try {
            final JsonArray changes = gson.fromJson( doGet( null ), JsonObject.class ).getAsJsonArray( "changes" );

            assertEquals( "Janne Jalkanen", authorOf( changes, "RestRecentByLogin" ) );
            assertEquals( "Legacy Person", authorOf( changes, "RestRecentByLegacy" ) );
        } finally {
            engine.deleteQuietly( "RestRecentByLogin", "RestRecentByLegacy" );
        }
    }

    private static String authorOf( final JsonArray changes, final String pageName ) {
        for ( final com.google.gson.JsonElement e : changes ) {
            if ( pageName.equals( e.getAsJsonObject().get( "name" ).getAsString() ) ) {
                return e.getAsJsonObject().get( "author" ).getAsString();
            }
        }
        return fail( pageName + " is missing from the recent changes: " + changes );
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-api -Dtest='IdentityDisplayTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR … cannot find symbol: class IdentityDisplay`.

Publish the new test helper and run the REST tests:

```bash
bin/agent-build.sh start b-upstream -- mvn -B -o install -pl wikantik-api,wikantik-main -DskipTests
bin/agent-build.sh wait b-upstream 540
mvn -B -o test -pl wikantik-rest \
  -Dtest='PageResourceTest,PageListResourceTest,HistoryResourceTest,RecentChangesResourceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: the new tests fail with `expected: <Janne Jalkanen> but was: <janne>` (and `expected: <unknown contributor> but was: <gone.user@example.test>`, `expected: <Administrator> but was: <admin>`); all pre-existing tests still pass.

- [ ] **Step 3: Implement**

`wikantik-api/src/main/java/com/wikantik/api/identity/IdentityDisplay.java`:

```java
package com.wikantik.api.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presentation helpers for surfaces that show who wrote something: they turn the principal a page version,
 * verification stamp or frontmatter field carries into the name shown to readers, through
 * {@link PublicIdentity}. Pure functions; a {@code null} identity (unit-test wiring only) means "no account is
 * known", so values pass through unchanged.
 */
public final class IdentityDisplay {

    /** Frontmatter keys whose value names a principal rather than carrying prose. */
    public static final List< String > PRINCIPAL_KEYS = List.of( "author", "verified_by" );

    private IdentityDisplay() {
    }

    /**
     * The display form of a <em>stored</em> author (a version author, a recent-change author).
     * {@code null} stays {@code null} so an absent author stays absent from the JSON; every other value goes
     * through {@link PublicIdentity#displayName}: a login becomes that account's display name, a legacy full name
     * or agent label is unchanged, an email-shaped value that is no account becomes
     * {@link PublicIdentity#UNKNOWN_CONTRIBUTOR}.
     */
    public static String ofAuthor( final PublicIdentity identity, final String stored ) {
        if ( identity == null || stored == null ) {
            return stored;
        }
        return identity.displayName( stored );
    }

    /**
     * The display form of a value an editor <em>typed</em> (frontmatter {@code author:} / {@code verified_by:}):
     * replaced by the account's display name only when the text is an existing login, otherwise shown exactly
     * as written.
     */
    public static String ofLoginOrText( final PublicIdentity identity, final String value ) {
        if ( identity == null || value == null || value.isBlank() ) {
            return value;
        }
        return identity.userIdForLogin( value ).isPresent() ? identity.displayName( value ) : value;
    }

    /**
     * A copy of {@code metadata} whose {@link #PRINCIPAL_KEYS} string values went through
     * {@link #ofLoginOrText}; the same instance when nothing changed. Key order is preserved and the argument is
     * never mutated.
     */
    public static Map< String, Object > frontmatter( final PublicIdentity identity, final Map< String, Object > metadata ) {
        if ( identity == null || metadata == null || metadata.isEmpty() ) {
            return metadata;
        }
        Map< String, Object > shown = null;
        for ( final String key : PRINCIPAL_KEYS ) {
            if ( metadata.get( key ) instanceof String text ) {
                final String resolved = ofLoginOrText( identity, text );
                if ( !resolved.equals( text ) ) {
                    if ( shown == null ) {
                        shown = new LinkedHashMap<>( metadata );
                    }
                    shown.put( key, resolved );
                }
            }
        }
        return shown == null ? metadata : shown;
    }
}
```

`PageResource.java` — add after `import com.wikantik.api.frontmatter.ParsedPage;`:

```java
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
```

In `doGet`, replace

```java
        result.put( "metadata", parsed.metadata() );
        result.put( "version", Math.max( page.getVersion(), 1 ) );
        result.put( "author", page.getAuthor() );
```

with

```java
        // Readers see display names, never stored logins. Frontmatter author/verified_by are editor-typed text:
        // they are replaced only when they happen to be a login.
        final PublicIdentity identity = getSubsystems().auth().publicIdentity();
        result.put( "metadata", IdentityDisplay.frontmatter( identity, parsed.metadata() ) );
        result.put( "version", Math.max( page.getVersion(), 1 ) );
        result.put( "author", IdentityDisplay.ofAuthor( identity, page.getAuthor() ) );
```

In `doPatch`, replace

```java
            result.put( "author", saved.getAuthor() ); // D7
            result.put( "metadata", newMetadata );
```

with

```java
            final PublicIdentity identity = getSubsystems().auth().publicIdentity();
            result.put( "author", IdentityDisplay.ofAuthor( identity, saved.getAuthor() ) ); // D7
            result.put( "metadata", IdentityDisplay.frontmatter( identity, newMetadata ) );
```

`PageListResource.java` — imports (next to the other `com.wikantik.api` imports):

```java
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
```

In `doGet`, directly after `final PageManager pm = getSubsystems().page().pages();` add:

```java
        final PublicIdentity identity = getSubsystems().auth().publicIdentity();
```

Replace `.map( page -> toEntry( page, spineMeta ) )` with `.map( page -> toEntry( page, spineMeta, identity ) )`, and replace the method head and author line of `toEntry`:

```java
    private static Map< String, Object > toEntry( final Page page, final SpineMeta spineMeta,
                                                  final PublicIdentity identity ) {
        final Map< String, Object > entry = new LinkedHashMap<>();
        entry.put( "name", page.getName() );
        entry.put( "lastModified", page.getLastModified() );
        entry.put( "version", Math.max( page.getVersion(), 1 ) );
        entry.put( "author", IdentityDisplay.ofAuthor( identity, page.getAuthor() ) );
```

`HistoryResource.java` — imports:

```java
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
```

After `final PageManager pm = getSubsystems().page().pages();` add `final PublicIdentity identity = getSubsystems().auth().publicIdentity();`. Replace `entry.put( "author", page.getAuthor() );` with `entry.put( "author", IdentityDisplay.ofAuthor( identity, page.getAuthor() ) );` and `entry.put( "author", v.getAuthor() );` with `entry.put( "author", IdentityDisplay.ofAuthor( identity, v.getAuthor() ) );`. Change notes (`changeNote`) are left exactly as written.

`RecentChangesResource.java` — imports `com.wikantik.api.identity.IdentityDisplay` and `com.wikantik.api.identity.PublicIdentity`; after `final PageManager pm = getSubsystems().page().pages();` add `final PublicIdentity identity = getSubsystems().auth().publicIdentity();`; replace `entry.put( "author", page.getAuthor() );` with `entry.put( "author", IdentityDisplay.ofAuthor( identity, page.getAuthor() ) );`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -B -o install -pl wikantik-api -DskipTests
mvn -B -o test -pl wikantik-api -Dtest='IdentityDisplayTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-rest \
  -Dtest='PageResourceTest,PageResourceUnitTest,PageResourceSaveContractTest,PageListResourceTest,HistoryResourceTest,RecentChangesResourceTest,RestAuthorizationSecurityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-api,wikantik-rest
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/identity/IdentityDisplay.java \
        wikantik-api/src/test/java/com/wikantik/api/identity/IdentityDisplayTest.java \
        wikantik-main/src/test/java/com/wikantik/AuthoredPages.java \
        wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/HistoryResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/RecentChangesResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/PageListResourceTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/HistoryResourceTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/RecentChangesResourceTest.java
git commit -m "feat(rest): page, list, history and recent-changes APIs serve display names

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Search API + Lucene author field

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexer.java` (field + setter; author block ~560–563; imports)
- Modify: `wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java` (`indexer()` ~249–258; new `publicIdentity()`; imports)
- Test: `wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexerTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderCITest.java` (fixture + new test)
- Test: `wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/SearchResourceTest.java` (single-resolution guard)
- **No change:** `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` (~156) and `DefaultLuceneSearcher.java` (~254).

**Interfaces:**
- Consumes: `PublicIdentity#displayName`; `AuthSubsystem.Services#publicIdentity()` read lazily via `WikiEngine#getAuthSubsystem()`.
- Produces: `DefaultLuceneIndexer#setPublicIdentitySource( Supplier< PublicIdentity > )` (a setter, not a constructor argument: an 8th constructor parameter would trip the `ExcessiveParameterList` complexity gate); package-private `LuceneSearchProvider#publicIdentity()` (overridable by tests).

Why `SearchResource` is unchanged: its `author` is `RetrievedPage#author`, produced by `DefaultContextRetrievalService`, which Task 9 makes the single place that resolves it. Resolving again in the resource would be the double-resolution described in the conventions. The guard test below pins that. The Lucene `author` field is the other thing a user can type into search (`MultiFieldQueryParser` includes it, and the SPA's author chip links to `/search?q=<author>`), so it must index the display name — never the login, or searching an email would reveal who edited what.

- [ ] **Step 1: Write the failing tests**

`DefaultLuceneIndexerTest` — add `import com.wikantik.api.identity.PublicIdentity;` and, after the existing `luceneIndexPage_logsDebugWhenFrontmatterHandlingThrows` test:

```java
    @Test
    void luceneIndexPage_indexesTheAuthorsDisplayNameInsteadOfTheStoredLogin() throws Exception {
        final Page page = mockPage( "AuthoredByLogin" );
        when( page.getAuthor() ).thenReturn( "janne" );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );

        try ( Directory dir = LuceneDirectoryFactory.open( tmp, false );
              IndexWriter writer = new IndexWriter( dir, new IndexWriterConfig( new StandardAnalyzer() ) ) ) {
            final DefaultLuceneIndexer indexer = newIndexer();
            indexer.setPublicIdentitySource( () -> identity );

            final Document doc = indexer.luceneIndexPage( page, "Body content.", writer );

            assertEquals( "Janne Jalkanen", doc.get( DefaultLuceneIndexer.LUCENE_AUTHOR ) );
        }
    }

    @Test
    void luceneIndexPage_leavesTheAuthorOutWhenNoIdentityServiceIsAvailable() throws Exception {
        final Page page = mockPage( "AuthoredBeforeBoot" );
        when( page.getAuthor() ).thenReturn( "janne" );

        try ( Directory dir = LuceneDirectoryFactory.open( tmp, false );
              IndexWriter writer = new IndexWriter( dir, new IndexWriterConfig( new StandardAnalyzer() ) ) ) {
            final Document doc = newIndexer().luceneIndexPage( page, "Body content.", writer );

            assertEquals( null, doc.get( DefaultLuceneIndexer.LUCENE_AUTHOR ),
                "a stored login must never be written to the index just because the resolver is not up yet" );
        }
    }
```

`LuceneSearchProviderCITest` — add `import com.wikantik.api.identity.PublicIdentity;`, the field `private final PublicIdentity identity = mock( PublicIdentity.class );` next to `private Engine engine;`, and replace the first line of `setUp()`, `provider = new LuceneSearchProvider( pageManager, attachmentManager);`, with:

```java
        // In production the identity service is looked up lazily from the WikiEngine; this fixture has no
        // WikiEngine, so substitute the lookup. Stored values pass through except one login.
        when( identity.displayName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        when( identity.displayName( "zq7login" ) ).thenReturn( "Janne Jalkanen" );
        provider = new LuceneSearchProvider( pageManager, attachmentManager ) {
            @Override
            PublicIdentity publicIdentity() {
                return identity;
            }
        };
```

and add, after `testFindPagesWithResults`:

```java
    @Test
    void testFindPagesMatchesTheAuthorsDisplayNameButNotTheStoredLogin() throws Exception {
        final Page page = mockPage( "AuthorSearchPage" );
        when( page.getAuthor() ).thenReturn( "zq7login" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( page, "plain body text" );
        when( pageManager.getPage( "AuthorSearchPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        stubAuthToAllow();

        assertEquals( 1, provider.findPages( "Jalkanen", wikiContext ).size(),
            "the author's display name is searchable" );
        assertTrue( provider.findPages( "zq7login", wikiContext ).isEmpty(),
            "the stored login must not be searchable" );
    }
```

`LuceneSearchProviderTest` — add (engine-level proof that the production supplier is wired; the display name `Jalkanen` appears nowhere in the login `janne`, the page name or the body):

```java
    @Test
    void testAuthorFieldCarriesTheDisplayNameOfTheStoredLogin() throws Exception {
        m_engine.saveTextAsJanne( "AuthorFieldPage", "quokkaauthorbody" );   // stored author: login "janne"

        final Collection<SearchResult> byDisplayName = new ArrayList<>();
        Awaitility.await( "page searchable by the author's display name" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( findsResultsFor( byDisplayName, "Jalkanen" ) );

        Assertions.assertEquals( "AuthorFieldPage", byDisplayName.iterator().next().getPage().getName() );
        m_engine.deleteTestPage( "AuthorFieldPage" );
    }
```

`SearchResourceTest` — add after `crsReturnsOrderedResultsAsJsonArray`:

```java
    @Test
    void authorFromTheRetrievalServiceIsServedAsIs_notResolvedASecondTime() throws Exception {
        // The retrieval service owns turning a stored principal into a display name. The seeded account "Fred"
        // has the display name "Fred Flintstone": a resource that resolved the already-resolved string "Fred"
        // again would silently swap one person for another.
        final ContextRetrievalService mockCrs = Mockito.mock( ContextRetrievalService.class );
        final RetrievedPage alpha = RetrievedPage.builder( "RestSearchAlpha", 1.0 )
            .summary( "" )
            .author( "Fred" )
            .build();
        Mockito.doReturn( new RetrievalResult( "Alpha", List.of( alpha ), 1 ) )
               .when( mockCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, mockCrs );

        final JsonObject entry = gson.fromJson( doSearch( "Alpha", null ), JsonObject.class )
            .getAsJsonArray( "results" ).get( 0 ).getAsJsonObject();

        assertEquals( "Fred", entry.get( "author" ).getAsString() );
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-main \
  -Dtest='DefaultLuceneIndexerTest,LuceneSearchProviderCITest,LuceneSearchProviderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` — `cannot find symbol: method setPublicIdentitySource` and `method does not override or implement a method from a supertype` (`publicIdentity()`). (`authorFromTheRetrievalServiceIsServedAsIs_notResolvedASecondTime` already passes; it is a regression guard for the design, not a fix.)

- [ ] **Step 3: Implement**

`DefaultLuceneIndexer.java` — add `import com.wikantik.api.identity.PublicIdentity;` (with the other `com.wikantik.api` imports; `java.util.function.Supplier` is already imported). Add the field after `private final SystemPageRegistry systemPageRegistry;`:

```java
    /**
     * Where the identity service comes from. A supplier because the Auth subsystem is built after the search
     * manager initialises; {@code () -> null} until {@link #setPublicIdentitySource} is called.
     */
    private volatile Supplier< PublicIdentity > publicIdentitySource = () -> null;
```

Add after the six-argument convenience constructor:

```java
    /**
     * Supplies the identity service used to index each author's <em>display name</em>. Set once by
     * {@code LuceneSearchProvider}; never needed by the read path.
     */
    public void setPublicIdentitySource( final Supplier< PublicIdentity > source ) {
        this.publicIdentitySource = source == null ? () -> null : source;
    }

    /**
     * The author value to index: the display name for a stored login (legacy names unchanged), or {@code null}
     * — leaving the field out — when the identity service is not available yet, so a login is never written to
     * the index (searching for an email would otherwise reveal who edited what).
     */
    private String indexedAuthor( final Page page ) {
        final String stored = page.getAuthor();
        if ( stored == null ) {
            return null;
        }
        final PublicIdentity identity = publicIdentitySource.get();
        if ( identity == null ) {
            LOG.debug( "No identity service yet; author of '{}' is left out of the search index", page.getName() );
            return null;
        }
        return identity.displayName( stored );
    }
```

Replace

```java
        if ( page.getAuthor() != null ) {
            field = new Field( LUCENE_AUTHOR, page.getAuthor(), TextField.TYPE_STORED );
            doc.add( field );
        }
```

with

```java
        final String author = indexedAuthor( page );
        if ( author != null ) {
            field = new Field( LUCENE_AUTHOR, author, TextField.TYPE_STORED );
            doc.add( field );
        }
```

`LuceneSearchProvider.java` — add `import com.wikantik.api.identity.PublicIdentity;`. Replace `indexer()` with:

```java
    private DefaultLuceneIndexer indexer() {
        if ( indexer == null ) {
            synchronized ( this ) {
                if ( indexer == null ) {
                    final DefaultLuceneIndexer created = new DefaultLuceneIndexer(
                            () -> luceneDirectory,
                            lifecycle(),
                            pageManager,
                            attachmentManager,
                            systemPageRegistry,
                            updates, // share the facade's updates list
                            useMMap );
                    created.setPublicIdentitySource( this::publicIdentity );
                    indexer = created;
                }
            }
        }
        return indexer;
    }

    /**
     * The identity service, looked up lazily: the Auth subsystem is built after this provider initialises, so it
     * does not exist yet while the engine is booting ({@code null}, and the same for any {@code Engine} that is
     * not a {@link com.wikantik.WikiEngine}). Package-private so tests can substitute it.
     */
    PublicIdentity publicIdentity() {
        if ( engine instanceof com.wikantik.WikiEngine wikiEngine ) {
            final com.wikantik.auth.subsystem.AuthSubsystem.Services auth = wikiEngine.getAuthSubsystem();
            return auth == null ? null : auth.publicIdentity();
        }
        return null;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -B -o test -pl wikantik-main \
  -Dtest='DefaultLuceneIndexerTest,DefaultLuceneIndexerLiveDocsTest,LuceneSearchProviderCITest,LuceneSearchProviderTest,LuceneSearchProviderIndexOpsTest,LuceneSearchProviderQueueTest,LuceneSearchProviderSystemPageFilterTest,SearchManagerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
bin/agent-build.sh start b-upstream -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait b-upstream 540
mvn -B -o test -pl wikantik-rest -Dtest='SearchResourceTest,SearchResourceQueryLogTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main
```
Expected: all green. If the PMD gate flags `DefaultLuceneIndexer` (it is baselined for `CyclomaticComplexity` only), move `indexedAuthor` into a package-private helper class in the same package rather than adding a baseline entry.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexer.java \
        wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java \
        wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexerTest.java \
        wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderCITest.java \
        wikantik-main/src/test/java/com/wikantik/search/LuceneSearchProviderTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/SearchResourceTest.java
git commit -m "feat(search): the Lucene author field indexes the display name, never the login

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Agent projection, knowledge MCP, `/tools`

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java` (new `withDisplayNames`; imports)
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java` (Javadoc only)
- Create: `wikantik-api/src/test/java/com/wikantik/api/agent/ForAgentProjectionDisplayNamesTest.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageForAgentResource.java` (~97–107)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` (ctor ~81–109, `fromEngine` ~117–140, `buildRetrievedPage` ~330)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/retrieval/PageListEngine.java` (ctor, `authorMatches` ~127–129)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageForAgentTool.java` (ctors ~81–90, `doExecute` ~173)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeToolDeps.java`, `KnowledgeMcpInitializer.java` (~104, ~165–180, ~272)
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListPagesTool.java` (~75–77), `RetrieveContextTool.java` (~113), `GetPageTool.java` (~78), `GetPageForAgentTool.java` (~119) — documentation strings only
- Modify: `wikantik-tools/src/main/java/com/wikantik/tools/GetPageTool.java` (~108–110, new `publicIdentity()`), `OpenApiDocument.java` (~136, ~150, ~286)
- Test: `wikantik-rest/.../PageForAgentResourceTest.java`; `wikantik-knowledge/.../DefaultContextRetrievalServiceTest.java`, `retrieval/PageListEngineTest.java`, `mcp/GetPageForAgentToolTest.java`, `mcp/KnowledgeMcpToolAssemblyTest.java`, `testfakes/FakeDeps.java`; `wikantik-tools/.../GetPageToolTest.java`, `OpenApiDocumentTest.java`
- **No change:** `SearchWikiTool.java` (~125), `RetrieveContextTool`/`GetPageTool`(knowledge)/`ListPagesTool` logic, `RecentChangesAdapter.java` (~77): the first three serialise `RetrievedPage` (resolved by `DefaultContextRetrievalService`); the adapter feeds the *cached* projection, which is resolved at serving time.

**Interfaces:**
- Consumes: `PublicIdentity` (`displayName`, `userIdForLogin`, `loginForDisplayName`); `IdentityDisplay` (Task 7); `AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity()`.
- Produces:
  - `ForAgentProjection#withDisplayNames( PublicIdentity )` — copy with `verifiedBy` (when a login) and every `recentChanges[].author` resolved; `this` for a `null` identity.
  - `DefaultContextRetrievalService( …11 existing…, Supplier< PublicIdentity > publicIdentity )` (the 11-argument constructor stays and passes `() -> null`).
  - `PageListEngine( pm, fmCache, pageBuilder, Supplier< PublicIdentity > )` (3-argument constructor stays): the `author` filter matches a display name; a stored login only matches through its display name; legacy strings still match as written.
  - `GetPageForAgentTool( service, viewGate, Supplier< PublicIdentity > )`; `KnowledgeToolDeps.Builder#publicIdentity( Supplier< PublicIdentity > )`.
  - `RetrievedPage#author` documented as "already a display name".

- [ ] **Step 1: Write the failing tests**

`wikantik-api/src/test/java/com/wikantik/api/agent/ForAgentProjectionDisplayNamesTest.java`:

```java
package com.wikantik.api.agent;

import com.wikantik.api.identity.PublicIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForAgentProjectionDisplayNamesTest {

    private static ForAgentProjection projection( final String verifiedBy, final List< RecentChange > changes ) {
        return new ForAgentProjection( "01ABC", "Slug", "Title", "article", null, null, null,
                null, verifiedBy, null, "s", null, null, changes, null, null, null, false,
                "/f", "/r", false, List.of(), List.of() );
    }

    private static PublicIdentity identity() {
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "janne" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );
        when( identity.displayName( "Legacy Person" ) ).thenReturn( "Legacy Person" );
        return identity;
    }

    @Test
    void verifiedByAndRecentChangeAuthorsBecomeDisplayNames() {
        final ForAgentProjection p = projection( "janne", List.of(
                new RecentChange( 2, Instant.parse( "2026-04-22T11:10:00Z" ), "janne", "tidy" ),
                new RecentChange( 1, Instant.parse( "2026-04-21T11:10:00Z" ), "Legacy Person", null ) ) );

        final ForAgentProjection shown = p.withDisplayNames( identity() );

        assertEquals( "Janne Jalkanen", shown.verifiedBy() );
        assertEquals( "Janne Jalkanen", shown.recentChanges().get( 0 ).author() );
        assertEquals( "tidy", shown.recentChanges().get( 0 ).summary() );
        assertEquals( "Legacy Person", shown.recentChanges().get( 1 ).author() );
        assertEquals( "janne", p.verifiedBy(), "the cached projection itself is never mutated" );
    }

    @Test
    void aVerifierThatIsNotALoginIsKeptAsWritten() {
        assertEquals( "ops-team", projection( "ops-team", List.of() ).withDisplayNames( identity() ).verifiedBy() );
    }

    @Test
    void anAbsentAuthorStaysAbsent() {
        final ForAgentProjection p = projection( null,
                List.of( new RecentChange( 1, Instant.EPOCH, null, "note" ) ) );

        assertNull( p.withDisplayNames( identity() ).recentChanges().get( 0 ).author() );
    }

    @Test
    void withoutAnIdentityServiceTheProjectionIsReturnedAsIs() {
        final ForAgentProjection p = projection( "janne", List.of() );

        assertSame( p, p.withDisplayNames( null ) );
    }

    @Test
    void everyOtherFieldIsCarriedOver() {
        final ForAgentProjection p = projection( null, List.of() );

        assertEquals( p, p.withDisplayNames( identity() ) );
    }
}
```

`PageForAgentResourceTest` — add `import com.wikantik.api.agent.RecentChange;` and this test before `anonReq`:

```java
    @Test
    void verified_by_and_recent_change_authors_are_display_names() throws Exception {
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", "article",
                "wikantik-development",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                Instant.parse( "2026-04-20T00:00:00Z" ), "janne",
                Instant.parse( "2026-04-22T11:10:00Z" ),
                "Operator reference for hybrid retrieval.",
                List.of(), List.of(),
                List.of( new RecentChange( 2, Instant.parse( "2026-04-22T11:10:00Z" ), "janne", "tidy" ),
                         new RecentChange( 1, Instant.parse( "2026-04-21T11:10:00Z" ), "Legacy Person", null ) ),
                List.of(),
                null,
                null, false,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final HttpServletRequest req = anonReq( "/01ABC" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        final JsonObject data = JsonParser.parseString( sw.toString() ).getAsJsonObject().getAsJsonObject( "data" );
        assertEquals( "Janne Jalkanen", data.get( "verified_by" ).getAsString() );
        final JsonArray changes = data.getAsJsonArray( "recent_changes" );
        assertEquals( "Janne Jalkanen", changes.get( 0 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( "Legacy Person", changes.get( 1 ).getAsJsonObject().get( "author" ).getAsString() );
        assertFalse( sw.toString().contains( "\"janne\"" ), "the stored login must not appear anywhere" );
    }
```

`FakeDeps.java` — three edits. Add the field after `private com.wikantik.search.FrontmatterMetadataCache fmCache = null;`:

```java
    private java.util.function.Supplier< com.wikantik.api.identity.PublicIdentity > publicIdentity = () -> null;
```

add the builder method after `fmCache( … )`:

```java
    public FakeDeps publicIdentity( final java.util.function.Supplier< com.wikantik.api.identity.PublicIdentity > p ) {
        this.publicIdentity = p;
        return this;
    }
```

and replace `build()` with:

```java
    public DefaultContextRetrievalService build() {
        return new DefaultContextRetrievalService(
            engine, search, null, null, null, null, null, pageManager, fmCache, baseUrl, ontologyQuery,
            publicIdentity );
    }
```

`DefaultContextRetrievalServiceTest` — add `import com.wikantik.api.knowledge.PageListFilter;`, `import com.wikantik.api.identity.PublicIdentity;`, `import java.util.Optional;` and:

```java
    @Test
    void getPage_servesTheAuthorsDisplayNameNotTheStoredLogin() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "ByLogin", "body", "janne", new Date() );
        pm.addPage( "ByLegacyName", "body", "Legacy Person", new Date() );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );
        when( identity.displayName( "Legacy Person" ) ).thenReturn( "Legacy Person" );
        final DefaultContextRetrievalService svc =
            FakeDeps.minimal().pageManager( pm ).publicIdentity( () -> identity ).build();

        assertEquals( "Janne Jalkanen", svc.getPage( "ByLogin" ).author() );
        assertEquals( "Legacy Person", svc.getPage( "ByLegacyName" ).author() );
    }

    @Test
    void getPage_keepsAnAbsentAuthorAbsent() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "NoAuthor", "body", null, new Date() );
        final PublicIdentity identity = mock( PublicIdentity.class );
        final DefaultContextRetrievalService svc =
            FakeDeps.minimal().pageManager( pm ).publicIdentity( () -> identity ).build();

        assertNull( svc.getPage( "NoAuthor" ).author() );
    }

    @Test
    void listPages_authorFilterTakesADisplayNameAndNeverAStoredLogin() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "ByLogin", "body", "janne", new Date() );
        pm.addPage( "ByLegacySameName", "body", "Janne Jalkanen", new Date() );
        pm.addPage( "Other", "body", "bob", new Date() );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "janne" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.userIdForLogin( "bob" ) ).thenReturn( Optional.of( "uid-2" ) );
        when( identity.loginForDisplayName( "Janne Jalkanen" ) ).thenReturn( Optional.of( "janne" ) );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );
        when( identity.displayName( "Janne Jalkanen" ) ).thenReturn( "Janne Jalkanen" );
        final DefaultContextRetrievalService svc =
            FakeDeps.minimal().pageManager( pm ).publicIdentity( () -> identity ).build();

        final var byDisplayName = svc.listPages(
            new PageListFilter( null, null, null, "Janne Jalkanen", null, null, 50, 0 ) );
        assertEquals( java.util.Set.of( "ByLogin", "ByLegacySameName" ),
            byDisplayName.pages().stream().map( RetrievedPage::name ).collect( java.util.stream.Collectors.toSet() ) );
        assertTrue( byDisplayName.pages().stream().allMatch( p -> "Janne Jalkanen".equals( p.author() ) ) );

        final var byLogin = svc.listPages( new PageListFilter( null, null, null, "janne", null, null, 50, 0 ) );
        assertEquals( 0, byLogin.totalMatched(), "a login is not a searchable author name" );
    }
```

`PageListEngineTest` — add `import com.wikantik.api.identity.PublicIdentity;`, `import java.util.Optional;` and:

```java
    @Test
    void authorFilter_matchesALoginOnlyThroughItsDisplayName_andLegacyStringsAsWritten() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "ByLogin", "body", "janne", new Date() );
        pm.addPage( "ByLegacy", "body", "Janne Jalkanen", new Date() );
        pm.addPage( "Other", "body", "bob", new Date() );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "janne" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.userIdForLogin( "bob" ) ).thenReturn( Optional.of( "uid-2" ) );
        when( identity.loginForDisplayName( "Janne Jalkanen" ) ).thenReturn( Optional.of( "janne" ) );
        final PageListEngine engine =
            new PageListEngine( pm, null, PageListEngineTest::toRetrievedPage, () -> identity );

        final PageList byName = engine.listPages(
            new PageListFilter( null, null, null, "Janne Jalkanen", null, null, 50, 0 ) );
        assertEquals( java.util.Set.of( "ByLogin", "ByLegacy" ),
            byName.pages().stream().map( RetrievedPage::name ).collect( java.util.stream.Collectors.toSet() ) );

        final PageList byLogin = engine.listPages(
            new PageListFilter( null, null, null, "janne", null, null, 50, 0 ) );
        assertEquals( 0, byLogin.totalMatched(), "typing a login must not confirm that account's authorship" );
    }

    @Test
    void authorFilter_withoutAnIdentityServiceKeepsExactMatching() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "ByLogin", "body", "janne", new Date() );
        final PageListEngine engine = new PageListEngine( pm, null, PageListEngineTest::toRetrievedPage );

        assertEquals( 1, engine.listPages(
            new PageListFilter( null, null, null, "janne", null, null, 50, 0 ) ).totalMatched() );
    }
```

`GetPageForAgentToolTest` — add `import com.wikantik.api.agent.RecentChange;`, `import com.wikantik.api.identity.PublicIdentity;`, `import java.time.Instant;` and:

```java
    @Test
    void verified_by_and_recent_change_authors_are_display_names() {
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "janne" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );
        final GetPageForAgentTool named = new GetPageForAgentTool( svc, PageViewGate.ALLOW_ALL, () -> identity );
        final ForAgentProjection p = new ForAgentProjection(
                "01ABC", "HybridRetrieval", "Hybrid Retrieval", "article",
                "wikantik-development",
                Audience.HUMANS_AND_AGENTS, Confidence.AUTHORITATIVE,
                Instant.parse( "2026-04-20T00:00:00Z" ), "janne", null,
                "Operator reference for hybrid retrieval.",
                List.of(), List.of(),
                List.of( new RecentChange( 1, Instant.parse( "2026-04-22T11:10:00Z" ), "janne", "tidy" ) ),
                List.of(),
                null,
                null, false,
                "/api/pages/HybridRetrieval", "/wiki/HybridRetrieval?format=md",
                false, List.of(), List.of() );
        when( svc.project( "01ABC" ) ).thenReturn( Optional.of( p ) );

        final McpSchema.CallToolResult result = named.execute( Map.of( "canonical_id", "01ABC" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        final JsonObject body = JsonParser.parseString( text ).getAsJsonObject();
        assertEquals( "Janne Jalkanen", body.get( "verified_by" ).getAsString() );
        assertEquals( "Janne Jalkanen",
                body.getAsJsonArray( "recent_changes" ).get( 0 ).getAsJsonObject().get( "author" ).getAsString() );
        assertFalse( text.contains( "\"janne\"" ), "the stored login must not appear anywhere" );
    }
```

`KnowledgeMcpToolAssemblyTest` — add imports `com.wikantik.api.agent.ForAgentProjection`, `com.wikantik.api.identity.PublicIdentity`, `io.modelcontextprotocol.spec.McpSchema`, `java.util.List`, `java.util.Map`, `java.util.Optional`, static `org.mockito.Mockito.when`, and (pins that the production wiring path passes the identity to the tool):

```java
    @Test
    void getPageForAgentIsWiredWithThePublicIdentityService() {
        final ForAgentProjectionService forAgent = mock( ForAgentProjectionService.class );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "janne" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );
        when( forAgent.project( "01ABC" ) ).thenReturn( Optional.of( new ForAgentProjection(
                "01ABC", "Slug", "T", "article", null, null, null, null, "janne", null, "s",
                null, null, null, null, null, null, false, "/f", "/r", false, List.of(), List.of() ) ) );

        final McpTool tool = KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps.builder()
                .forAgent( forAgent )
                .publicIdentity( () -> identity )
                .viewGate( PageViewGate.ALLOW_ALL )
                .build() )
            .stream().filter( t -> "get_page_for_agent".equals( t.name() ) ).findFirst().orElseThrow();

        final String text = ( (McpSchema.TextContent) tool.execute( Map.of( "canonical_id", "01ABC" ) )
                .content().get( 0 ) ).text();
        assertTrue( text.contains( "Janne Jalkanen" ), text );
        assertFalse( text.contains( "\"janne\"" ), text );
    }
```

`wikantik-tools` `GetPageToolTest` — add `import com.wikantik.api.identity.PublicIdentity;`, the field `@Mock PublicIdentity identity;` next to the other `@Mock`s, make `allowingTool` also override the lookup (so no engine wiring is needed):

```java
    private GetPageTool allowingTool( final WikiEngine engine, final ToolsConfig config ) {
        return new GetPageTool( engine, config ) {
            @Override
            boolean canView( final HttpServletRequest request, final String pageName ) {
                return true;
            }

            @Override
            PublicIdentity publicIdentity() {
                return identity;
            }
        };
    }
```

then in `includesLastModifiedAndAuthorWhenPresent` add `when( identity.displayName( "alice" ) ).thenReturn( "alice" );` after the `page.getAuthor()` stub, and add:

```java
    @Test
    void authorStoredAsALoginIsReturnedAsTheDisplayName() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "Main" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "Main" );
        when( pageManager.getPureText( "Main", -1 ) ).thenReturn( "Body content" );
        when( page.getAuthor() ).thenReturn( "janne" );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );

        final Map< String, Object > out = allowingTool( engine, new ToolsConfig( new Properties() ) )
                .execute( "Main", 0, request );

        assertEquals( "Janne Jalkanen", out.get( "author" ) );
    }
```

`OpenApiDocumentTest` — add:

```java
    @Test
    void authorIsDocumentedAsADisplayNameNotALogin() {
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );

        assertFalse( json.contains( "Last author login" ), json );
        assertTrue( json.contains( "Display name of the last author, when recorded" ), json );
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-api -Dtest='ForAgentProjectionDisplayNamesTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR … cannot find symbol: method withDisplayNames(PublicIdentity)`. The knowledge/rest/tools tests above fail to compile the same way (`FakeDeps.publicIdentity`, 12-argument `DefaultContextRetrievalService`, 4-argument `PageListEngine`, 3-argument `GetPageForAgentTool`, `KnowledgeToolDeps.Builder#publicIdentity`, `GetPageTool#publicIdentity`) until Step 3.

- [ ] **Step 3: Implement**

**`ForAgentProjection.java`** — add `import com.wikantik.api.identity.IdentityDisplay;` and `import com.wikantik.api.identity.PublicIdentity;` and, after the compact constructor:

```java
    /**
     * A copy whose {@code verifiedBy} (when it is an existing login) and every recent-change author are display
     * names. The projection is cached by {@code DefaultForAgentProjectionService} holding stored principals, so
     * the two serving surfaces call this on every response; that is also why a rename shows up without the cache
     * being evicted. Returns {@code this} when {@code identity} is {@code null}.
     */
    public ForAgentProjection withDisplayNames( final PublicIdentity identity ) {
        if ( identity == null ) {
            return this;
        }
        final List< RecentChange > shownChanges = recentChanges.stream()
                .map( c -> c.author() == null
                        ? c
                        : new RecentChange( c.version(), c.at(), identity.displayName( c.author() ), c.summary() ) )
                .toList();
        return new ForAgentProjection( canonicalId, slug, title, type, cluster, audience, confidence,
                verifiedAt, IdentityDisplay.ofLoginOrText( identity, verifiedBy ), updated, summary,
                keyFacts, headingsOutline, shownChanges, mcpToolHints, runbook, agentHints,
                summarySynthesized, fullBodyUrl, rawMarkdownUrl, degraded, missingFields, staleCitations );
    }
```

**`RetrievedPage.java`** — extend the record Javadoc with a paragraph:

```java
 *
 * <p>{@code author} is already the author's <b>display name</b> (resolved by the service that built the page
 * through {@code PublicIdentity}), never a stored login. Surfaces serve it as-is: resolving it a second time
 * could swap one person for another when a legacy display name equals a different account's login.</p>
```

**`PageForAgentResource.java`** — replace `envelope.add( "data", toJson( p ) );` with:

```java
        // The service caches stored principals; show readers display names (verified_by, recent_changes[].author).
        envelope.add( "data", toJson( p.withDisplayNames( getSubsystems().auth().publicIdentity() ) ) );
```

**`DefaultContextRetrievalService.java`** — add imports `com.wikantik.api.identity.IdentityDisplay`, `com.wikantik.api.identity.PublicIdentity`, `com.wikantik.auth.subsystem.AuthSubsystemBridge`, `java.util.function.Supplier`. Add the field `private final Supplier< PublicIdentity > publicIdentity;` after `contributingChunkAssembler`. Replace the constructor (lines 81–109) with the delegating 11-argument constructor plus the full one:

```java
    public DefaultContextRetrievalService(
            final Engine engine,
            final SearchManager searchManager,
            final HybridSearchService hybridSearch,
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final NodeMentionSimilarity similarity,
            final MentionIndex mentionIndex,
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final String publicBaseUrl,
            final com.wikantik.api.ontology.OntologyQueryService ontologyQuery ) {
        this( engine, searchManager, hybridSearch, chunkIndex, chunkRepo, similarity, mentionIndex,
              pageManager, fmCache, publicBaseUrl, ontologyQuery, () -> null );
    }

    /**
     * @param publicIdentity resolves a page's stored author to its display name at call time (a supplier because
     *        the Auth subsystem is built after this service's collaborators); {@code () -> null} leaves authors
     *        exactly as stored and is meant only for tests that do not exercise names
     */
    public DefaultContextRetrievalService(
            final Engine engine,
            final SearchManager searchManager,
            final HybridSearchService hybridSearch,
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final NodeMentionSimilarity similarity,
            final MentionIndex mentionIndex,
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final String publicBaseUrl,
            final com.wikantik.api.ontology.OntologyQueryService ontologyQuery,
            final Supplier< PublicIdentity > publicIdentity ) {
        if ( engine == null ) throw new IllegalArgumentException( "engine required" );
        if ( searchManager == null ) throw new IllegalArgumentException( "searchManager required" );
        if ( pageManager == null ) throw new IllegalArgumentException( "pageManager required" );
        // hybridSearch, chunkIndex, chunkRepo, similarity, mentionIndex, fmCache
        // all nullable: the service degrades rather than failing when optional deps are absent.
        this.engine = engine;
        this.searchManager = searchManager;
        this.hybridSearch = hybridSearch;
        this.pageManager = pageManager;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
        // Optional: when present (and the flag enabled at the wiring site), the query is
        // expanded with ontology-derived terms before search. Null = no expansion.
        this.ontologyQuery = ontologyQuery;
        this.publicIdentity = publicIdentity == null ? () -> null : publicIdentity;
        this.relatedPagesFinder = new RelatedPagesFinder( mentionIndex );
        this.pageListEngine = new PageListEngine( pageManager, fmCache,
            ( page, meta ) -> buildRetrievedPage( page, meta, 0.0, List.of(), List.of() ), this.publicIdentity );
        this.contributingChunkAssembler = new ContributingChunkAssembler( chunkIndex, chunkRepo, hybridSearch );
    }
```

In `fromEngine`, change the `return new DefaultContextRetrievalService( … ontologyQueryFor( engine ) );` to pass the supplier as the 12th argument:

```java
            engine.getBaseURL(),
            ontologyQueryFor( engine ),
            () -> AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity() );
```

In `buildRetrievedPage`, replace the line `            page.getAuthor(),` with:

```java
            IdentityDisplay.ofAuthor( publicIdentity.get(), page.getAuthor() ),
```

**`PageListEngine.java`** — add `import com.wikantik.api.identity.PublicIdentity;` and `import java.util.function.Supplier;`. Add the field after `pageBuilder`; keep the existing 3-argument constructor *with its existing Javadoc* but make its body delegate; add the 4-argument constructor right after it; and replace `authorMatches`:

```java
    private final Supplier< PublicIdentity > publicIdentity;

    // existing constructor, existing Javadoc kept above it; only the body changes
    public PageListEngine(
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final BiFunction< Page, Map< String, Object >, RetrievedPage > pageBuilder ) {
        this( pageManager, fmCache, pageBuilder, () -> null );
    }

    /**
     * @param publicIdentity lets the {@code author} filter take the display name readers see; {@code () -> null}
     *        keeps exact matching on the stored string (tests, and engines without an identity service)
     */
    public PageListEngine(
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final BiFunction< Page, Map< String, Object >, RetrievedPage > pageBuilder,
            final Supplier< PublicIdentity > publicIdentity ) {
        this.pageManager = pageManager;
        this.fmCache = fmCache;
        this.pageBuilder = pageBuilder;
        this.publicIdentity = publicIdentity == null ? () -> null : publicIdentity;
    }
```

and the new `authorMatches` (replace the old `private static boolean authorMatches` method):

```java
    /**
     * The filter value is a display name, as returned in {@code author}. A page whose stored author is a login
     * matches only through that account's display name (so typing a login can never confirm an account's
     * authorship); a stored legacy full name or agent label still matches exactly as written.
     */
    private boolean authorMatches( final Page page, final PageListFilter f ) {
        final String wanted = f.author();
        if ( wanted == null ) {
            return true;
        }
        final String stored = page.getAuthor();
        if ( stored == null ) {
            return false;
        }
        final PublicIdentity identity = publicIdentity.get();
        if ( identity == null ) {
            return wanted.equals( stored );
        }
        if ( wanted.equals( stored ) && identity.userIdForLogin( stored ).isEmpty() ) {
            return true;
        }
        return identity.loginForDisplayName( wanted ).filter( stored::equals ).isPresent();
    }
```

**`GetPageForAgentTool.java`** — imports `com.wikantik.api.identity.PublicIdentity`, `java.util.function.Supplier`; field `private final Supplier< PublicIdentity > publicIdentity;`; replace the two constructors with:

```java
    public GetPageForAgentTool( final ForAgentProjectionService service ) {
        this( service, PageViewGate.ALLOW_ALL );
    }

    public GetPageForAgentTool( final ForAgentProjectionService service, final PageViewGate viewGate ) {
        this( service, viewGate, () -> null );
    }

    /**
     * @param publicIdentity resolves verifier and recent-change authors to display names per call;
     *        {@code () -> null} leaves them as stored (tests only)
     */
    public GetPageForAgentTool( final ForAgentProjectionService service, final PageViewGate viewGate,
                                final Supplier< PublicIdentity > publicIdentity ) {
        this.service = service;
        this.viewGate = viewGate == null ? PageViewGate.ALLOW_ALL : viewGate;
        this.publicIdentity = publicIdentity == null ? () -> null : publicIdentity;
    }
```

and in `doExecute` replace the last line with:

```java
        return McpToolUtils.jsonResult( PROJECTION_GSON, maybe.get().withDisplayNames( publicIdentity.get() ) );
```

**`KnowledgeToolDeps.java`** — add `import com.wikantik.api.identity.PublicIdentity;`, then four additions (field + constructor line in the outer class, accessor, builder field + setter):

```java
    // outer class, next to the other final fields
    private final Supplier< PublicIdentity > publicIdentity;

    // in private KnowledgeToolDeps( final Builder b ), after the viewGate line
        this.publicIdentity = b.publicIdentity != null ? b.publicIdentity : () -> null;

    // accessors, after viewGate()
    Supplier< PublicIdentity > publicIdentity() { return publicIdentity; }

    // in static final class Builder, next to the other fields and setters
        private Supplier< PublicIdentity > publicIdentity;
        Builder publicIdentity( final Supplier< PublicIdentity > v ) { this.publicIdentity = v; return this; }
```

**`KnowledgeMcpInitializer.java`** — add `import com.wikantik.auth.subsystem.AuthSubsystemBridge;`. In the builder chain (after `.viewGate( viewGate )`) add:

```java
                .publicIdentity( () -> AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity() )
```

and in `addProjectionAndBundleTools` replace `new GetPageForAgentTool( deps.forAgent(), viewGate )` with `new GetPageForAgentTool( deps.forAgent(), viewGate, deps.publicIdentity() )`.

**Tool documentation strings** (display names, not logins): `ListPagesTool` — `"description", "Filter by page author's display name, as shown in the author field.", "examples", List.of( "Jake Fear" )`; `RetrieveContextTool` example `"author", "jakefear"` → `"author", "Jake Fear"`, and in its `filters` description change `cluster, tags, type, modifiedAfter (ISO-8601)` to `cluster, tags, type, author (display name), modifiedAfter (ISO-8601)`; `GetPageTool` (knowledge) `"author", "jakefear"` → `"author", "Jake Fear"`; `GetPageForAgentTool` `"verified_by", "jakefear"` → `"verified_by", "Jake Fear"`.

**`wikantik-tools/.../GetPageTool.java`** — imports `com.wikantik.api.identity.PublicIdentity`, `com.wikantik.auth.subsystem.AuthSubsystemBridge`. Replace

```java
        if ( page.getAuthor() != null ) {
            out.put( "author", page.getAuthor() );
        }
```

with

```java
        if ( page.getAuthor() != null ) {
            out.put( "author", publicIdentity().displayName( page.getAuthor() ) );
        }
```

and add next to `canView`:

```java
    /**
     * The identity service that turns a stored author into the display name shown to callers.
     * Package-visible so tests can substitute it; evaluated only when a page has an author.
     */
    PublicIdentity publicIdentity() {
        return AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
    }
```

**`OpenApiDocument.java`** — replace both occurrences (lines ~136 and ~150) of `prop( "author", "string", "Last author login, when recorded" )` with `prop( "author", "string", "Display name of the last author, when recorded" )`, and the example `"author", "jakefear"` (~286) with `"author", "Jake Fear"`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -B -o install -pl wikantik-api -DskipTests
mvn -B -o test -pl wikantik-api -Dtest='ForAgentProjectionDisplayNamesTest,ProjectionRecordsTest,IdentityDisplayTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-knowledge \
  -Dtest='DefaultContextRetrievalServiceTest,PageListEngineTest,GetPageForAgentToolTest,KnowledgeMcpToolAssemblyTest,ListPagesToolTest,RetrieveContextToolTest,GetPageToolTest,ContextRetrievalServiceInitializerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-rest -Dtest='PageForAgentResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-tools -Dtest='GetPageToolTest,SearchWikiToolTest,OpenApiDocumentTest,ToolsOpenApiServletTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-api,wikantik-knowledge,wikantik-rest,wikantik-tools
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/agent/ForAgentProjection.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/RetrievedPage.java \
        wikantik-api/src/test/java/com/wikantik/api/agent/ForAgentProjectionDisplayNamesTest.java \
        wikantik-rest/src/main/java/com/wikantik/rest/PageForAgentResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/PageForAgentResourceTest.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/retrieval/PageListEngine.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageForAgentTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeToolDeps.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ListPagesTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/RetrieveContextTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/GetPageTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/retrieval/PageListEngineTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/GetPageForAgentToolTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/KnowledgeMcpToolAssemblyTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/testfakes/FakeDeps.java \
        wikantik-tools/src/main/java/com/wikantik/tools/GetPageTool.java \
        wikantik-tools/src/main/java/com/wikantik/tools/OpenApiDocument.java \
        wikantik-tools/src/test/java/com/wikantik/tools/GetPageToolTest.java \
        wikantik-tools/src/test/java/com/wikantik/tools/OpenApiDocumentTest.java
git commit -m "feat(agents): for-agent projection, knowledge MCP and /tools serve display names

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Feeds + plugins (and the missing view-ACL filter in recent articles)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/content/DefaultRecentArticlesManager.java` (imports; fields ~95–96; constructors ~114–130; `buildRecentArticles` ~345–392; `buildArticleSummary` ~436)
- Modify: `wikantik-main/src/main/java/com/wikantik/plugin/RecentChangesPlugin.java` (~88, ~182–203)
- Modify: `wikantik-main/src/main/java/com/wikantik/content/RecentArticlesServlet.java` (Javadoc example only, ~70)
- Test: `wikantik-main/src/test/java/com/wikantik/content/DefaultRecentArticlesManagerCITest.java`, `DefaultRecentArticlesManagerTest.java`, `AtomFeedServletTest.java`, `RecentArticlesServletTest.java`; `wikantik-main/src/test/java/com/wikantik/plugin/RecentChangesPluginTest.java`, `RecentChangesPluginCITest.java`, `RecentArticlesTest.java`
- **No change:** `AtomFeedServlet.java` (~211–212), `RecentArticlesServlet` logic, `plugin/RecentArticles.java` (~208–209): all render `ArticleSummary`, whose author the manager now resolves, and whose list the manager now filters.

**Interfaces:**
- Consumes: `IdentityDisplay.ofAuthor` (Task 7); `PublicIdentity`; `WikiSession.guestSession( engine )` + `PermissionFilter#canAccessQuietly( guest, page, "view" )` (the same request-free guest check `OntologyWiringHelper` and `KnowledgeMcpInitializer` use); `com.wikantik.AuthoredPages` (Task 7).
- Produces: package-private `DefaultRecentArticlesManager( Engine, PageManager, RenderingManager, SystemPageRegistry, Predicate< String > guestCanView, Supplier< PublicIdentity > publicIdentity )` — a test seam (the 4-argument constructor and no-argument SPI constructor are unchanged and use the engine-backed defaults).

Design notes. (1) The manager's result cache is keyed by query only and shared by every caller (feed, API, plugin), so the filter must be caller-independent: **anonymous** view, applied before a summary (title, excerpt, author) is built and before the `count` budget is spent. (2) A view check that throws hides the page and logs a WARN — it never fails the whole list and never leaks. (3) `RecentChangesPlugin` renders the resolved name as plain text and no longer links to a wiki page named after the author: for pages saved since 2.4.58 the stored value is a login, and the `href` would hand it to every reader; a link to a page that is named like a person's full name was practically never useful, and dropping it also removes a `wikiPageExists` lookup per row.

- [ ] **Step 1: Write the failing tests**

`DefaultRecentArticlesManagerCITest` — add imports `com.wikantik.api.identity.PublicIdentity`, `java.util.HashSet`; add fields next to the others:

```java
    private PublicIdentity identity;
    private final Set< String > hiddenFromGuests = new HashSet<>();
```

and in `setUp()` replace the last two statements (`mgr = new DefaultRecentArticlesManager( engine, pageManager, renderingManager, systemPageRegistry ); mgr.initialize( engine, new Properties() );`) with:

```java
        identity = mock( PublicIdentity.class );
        hiddenFromGuests.clear();
        // The engine-backed anonymous view check and identity lookup need a real WikiEngine; this fixture is
        // all mocks, so use the package-private seam.
        mgr = new DefaultRecentArticlesManager( engine, pageManager, renderingManager, systemPageRegistry,
                slug -> !hiddenFromGuests.contains( slug ), () -> identity );
        mgr.initialize( engine, new Properties() );
```

Add before the `// ==================== Null / empty input` section:

```java
    // ==================== Display names and anonymous visibility ====================

    @Test
    void testPagesAnAnonymousVisitorCannotViewAreLeftOut() {
        final Page open = mockPage( "OpenPage" );
        final Page secret = mockPage( "SecretPage" );
        when( pageManager.getPureText( open ) ).thenReturn( "# Open\nBody." );
        when( pageManager.getPureText( secret ) ).thenReturn( "# Secret\nBody." );
        setupRecentChanges( secret, open );
        hiddenFromGuests.add( "SecretPage" );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( List.of( "OpenPage" ), articles.stream().map( ArticleSummary::getName ).toList() );
    }

    @Test
    void testHiddenPagesDoNotUseUpTheCountBudget() {
        final Page secret = mockPage( "SecretPage" );
        final Page open = mockPage( "OpenPage" );
        when( pageManager.getPureText( secret ) ).thenReturn( "# Secret\nBody." );
        when( pageManager.getPureText( open ) ).thenReturn( "# Open\nBody." );
        setupRecentChanges( secret, open );
        hiddenFromGuests.add( "SecretPage" );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 1 ) );

        assertEquals( List.of( "OpenPage" ), articles.stream().map( ArticleSummary::getName ).toList() );
    }

    @Test
    void testAViewCheckThatThrowsHidesThePageInsteadOfFailingTheList() {
        final DefaultRecentArticlesManager flaky = new DefaultRecentArticlesManager( engine, pageManager,
                renderingManager, systemPageRegistry,
                name -> {
                    if ( "BoomPage".equals( name ) ) {
                        throw new IllegalStateException( "acl backend down" );
                    }
                    return true;
                }, () -> identity );
        flaky.initialize( engine, new Properties() );
        final Page boom = mockPage( "BoomPage" );
        final Page fine = mockPage( "FinePage" );
        when( pageManager.getPureText( boom ) ).thenReturn( "# Boom\nBody." );
        when( pageManager.getPureText( fine ) ).thenReturn( "# Fine\nBody." );
        setupRecentChanges( boom, fine );

        final List<ArticleSummary> articles = flaky.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( List.of( "FinePage" ), articles.stream().map( ArticleSummary::getName ).toList() );
    }

    @Test
    void testAuthorsAreResolvedToDisplayNames() {
        final Page page = mockPage( "AuthoredPage" );
        when( page.getAuthor() ).thenReturn( "janne" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Authored\nBody." );
        when( identity.displayName( "janne" ) ).thenReturn( "Janne Jalkanen" );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( "Janne Jalkanen", articles.get( 0 ).getAuthor() );
    }
```

`DefaultRecentArticlesManagerTest` (real `TestEngine`) — add `import com.wikantik.AuthoredPages;` and:

```java
    @Test
    public void testRestrictedPagesAreNeverListedAndAuthorsAreDisplayNames() throws Exception {
        m_engine.saveText( "RecentRestrictedPage", "[{ALLOW view Admin}]\n# Restricted Title\nOnly admins read this body." );
        AuthoredPages.save( m_engine, "RecentByLoginPage", "# By Login\nBody text of the login-authored page.", "janne" );
        m_manager.clearCache();

        final List<ArticleSummary> articles =
            m_manager.getRecentArticles( createContext(), new RecentArticlesQuery().count( 50 ) );

        final List<String> names = articles.stream().map( ArticleSummary::getName ).toList();
        Assertions.assertFalse( names.contains( "RecentRestrictedPage" ), names.toString() );
        Assertions.assertTrue( names.contains( "RecentByLoginPage" ), names.toString() );
        final ArticleSummary byLogin = articles.stream()
            .filter( a -> a.getName().equals( "RecentByLoginPage" ) ).findFirst().orElseThrow();
        Assertions.assertEquals( "Janne Jalkanen", byLogin.getAuthor() );
    }
```

`AtomFeedServletTest` — add `import com.wikantik.AuthoredPages;` and `import com.wikantik.content.RecentArticlesManager;` is same package (no import). In `tearDown()` add `engine.deleteQuietly( "FeedTestRestricted", "FeedTestByLogin" );` before `engine.stop();`. Add:

```java
    @Test
    void testFeedOmitsRestrictedPagesAndNamesAuthorsByDisplayName() throws Exception {
        engine.saveText( "FeedTestRestricted", "[{ALLOW view Admin}]\n# Hidden Heading\nRestricted body." );
        AuthoredPages.save( engine, "FeedTestByLogin", "# By Login\nBody.", "janne" );
        engine.getManager( RecentArticlesManager.class ).clearCache();

        final String feed = doGetFeed( null, null );

        assertFalse( feed.contains( "FeedTestRestricted" ), "a page anonymous visitors cannot view must not be in the public feed" );
        assertFalse( feed.contains( "Hidden Heading" ) );
        assertTrue( feed.contains( "<author><name>Janne Jalkanen</name></author>" ), feed );
        assertFalse( feed.contains( "<name>janne</name>" ), "the stored login must not be published" );
    }
```

`RecentArticlesServletTest` — add `import com.wikantik.AuthoredPages;` and

```java
    @SuppressWarnings( "unchecked" )
    @Test
    void testAuthorsAreDisplayNamesAndRestrictedPagesAreNotListed() throws Exception {
        engine.saveText( "RecentRestrictedPage", "[{ALLOW view Admin}]\n# Hidden\nOnly admins read this." );
        AuthoredPages.save( engine, "RecentByLoginPage", "# By Login\nBody.", "janne" );
        engine.getManager( RecentArticlesManager.class ).clearCache();

        final Map< String, Object > data = doGet( HttpMockFactory.createHttpRequest() );

        final List< Map< String, Object > > articles = ( List< Map< String, Object > > ) data.get( "articles" );
        assertFalse( articles.stream().anyMatch( a -> "RecentRestrictedPage".equals( a.get( "name" ) ) ),
            "restricted pages must not be listed in the public API" );
        final Map< String, Object > byLogin = articles.stream()
            .filter( a -> "RecentByLoginPage".equals( a.get( "name" ) ) ).findFirst().orElseThrow();
        assertEquals( "Janne Jalkanen", byLogin.get( "author" ) );
    }
```

`RecentArticlesTest` (plugin) — add `import com.wikantik.AuthoredPages;` and:

```java
    @Test
    public void testAuthorsAreDisplayNamesAndRestrictedPagesAreNotListed() throws Exception {
        AuthoredPages.save( m_engine, "PluginAuthoredPage", "# Authored\nBody by a login.", "janne" );
        m_engine.saveText( "PluginRestrictedPage", "[{ALLOW view Admin}]\n# Hidden Title\nRestricted." );
        clearManagerCache();

        final String result = m_plugin.execute( createContext(), new HashMap<>() );

        Assertions.assertTrue( result.contains( "<span class=\"author\">Janne Jalkanen</span>" ), result );
        Assertions.assertFalse( result.contains( ">janne<" ), result );
        Assertions.assertFalse( result.contains( "PluginRestrictedPage" ), result );
        Assertions.assertFalse( result.contains( "Hidden Title" ), result );
    }
```

`RecentChangesPluginTest` — add `import java.util.regex.Pattern;` and:

```java
    @Test
    public void testAuthorIsShownAsDisplayNameNotLogin() throws Exception {
        testEngine.saveTextAsJanne( "TestPage05", "Authored by janne" );   // stored author: login "janne"
        try {
            context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "TestPage05" ) );

            final String res = manager.execute( context, "{INSERT com.wikantik.plugin.RecentChangesPlugin}" );

            Assertions.assertTrue( Pattern.compile( "<td class=\"author\">\\s*Janne Jalkanen\\s*</td>" ).matcher( res ).find(), res );
            Assertions.assertFalse( Pattern.compile( "<td class=\"author\">\\s*janne\\s*</td>" ).matcher( res ).find(), res );
        } finally {
            testEngine.deleteTestPage( "TestPage05" );
        }
    }
```

`RecentChangesPluginCITest` — replace `testAuthorWhoIsAWikiPage` (and its Javadoc) with (add `import java.util.regex.Pattern;`):

```java
    /**
     * The author is plain text even when a wiki page with that name exists: the stored value may be a login, and
     * a link built from it would hand the login to every reader.
     */
    @Test
    public void testAuthorIsPlainTextEvenWhenAPageWithThatNameExists() throws Exception {
        testEngine.saveText( "admin", "The admin user's page" );
        try {
            context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, "RcPage01" ) );

            final String res = manager.execute( context, "{INSERT com.wikantik.plugin.RecentChangesPlugin}" );

            Assertions.assertTrue( Pattern.compile( "<td class=\"author\">\\s*Administrator\\s*</td>" ).matcher( res ).find(), res );
        } finally {
            testEngine.deleteTestPage( "admin" );
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-main \
  -Dtest='DefaultRecentArticlesManagerCITest,DefaultRecentArticlesManagerTest,AtomFeedServletTest,RecentArticlesServletTest,RecentArticlesTest,RecentChangesPluginTest,RecentChangesPluginCITest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` for the 6-argument `DefaultRecentArticlesManager` constructor. With only that constructor added (no filter, no resolution yet) the behavioural tests fail as intended: the restricted page is present in the feed, the JSON API, the plugin and the manager's list, and the author reads `janne`, not `Janne Jalkanen`.

- [ ] **Step 3: Implement**

**`DefaultRecentArticlesManager.java`** — add imports:

```java
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
```
and `java.util.function.Predicate` / `java.util.function.Supplier`. Replace lines 95–96

```java
    @SuppressWarnings( "PMD.UnusedPrivateField" ) // Held for late-bound manager lookups; not yet consumed on the read path.
    private Engine engine;
```

with

```java
    private Engine engine;
```

and add after the `excludePatterns` field:

```java
    /** Test seam: replaces the engine-backed anonymous view check. */
    private Predicate< String > guestCanViewOverride;
    /** Test seam: replaces the engine-backed identity lookup. */
    private Supplier< PublicIdentity > publicIdentityOverride;
```

Add after the 4-argument constructor:

```java
    /**
     * Test seam: supplies the anonymous view check and the identity lookup instead of deriving them from the
     * engine (a Mockito {@code WikiEngine} cannot build a guest session). Not used in production, where the
     * no-argument SPI constructor plus {@link #initialize} give the engine-backed defaults.
     */
    DefaultRecentArticlesManager( final Engine engine,
                                  final PageManager pageManager,
                                  final RenderingManager renderingManager,
                                  final SystemPageRegistry systemPageRegistry,
                                  final Predicate< String > guestCanView,
                                  final Supplier< PublicIdentity > publicIdentity ) {
        this( engine, pageManager, renderingManager, systemPageRegistry );
        this.guestCanViewOverride = guestCanView;
        this.publicIdentityOverride = publicIdentity;
    }
```

In `buildRecentArticles`, declare the check before the loop (after `final Set<Page> recentChanges = …;`) and apply it after the exclusion check:

```java
        // Results are cached once and shared by every caller (Atom feed, /api/recent-articles, both plugins), so
        // they must be safe for the least-privileged reader: only pages an anonymous visitor may view.
        final Predicate< String > guestCanView = guestCanView();
```

```java
            // Skip excluded pages
            if ( isExcluded( pageName, includePattern, excludePattern ) ) {
                continue;
            }

            // Skip pages an anonymous visitor cannot view (before any title, excerpt or author is built)
            if ( !guestMayView( guestCanView, pageName ) ) {
                continue;
            }
```

Add the helpers (next to `buildArticleSummary`):

```java
    /** The anonymous view check: a request-free guest session, as the public ontology and knowledge-MCP surfaces use. */
    private Predicate< String > guestCanView() {
        if ( guestCanViewOverride != null ) {
            return guestCanViewOverride;
        }
        final Session guest = WikiSession.guestSession( engine );
        final PermissionFilter filter = new PermissionFilter( engine );
        return pageName -> filter.canAccessQuietly( guest, pageName, "view" );
    }

    /** A check that fails hides the page (and is logged); it never fails the list and never leaks. */
    private static boolean guestMayView( final Predicate< String > guestCanView, final String pageName ) {
        try {
            return guestCanView.test( pageName );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Anonymous view check failed for '{}'; leaving it out of recent articles: {}",
                      pageName, e.getMessage(), e );
            return false;
        }
    }

    /** Resolved per call (the Auth subsystem is built after this manager initialises). */
    private PublicIdentity publicIdentity() {
        return publicIdentityOverride != null
                ? publicIdentityOverride.get()
                : AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
    }
```

and in `buildArticleSummary` replace `.author( page.getAuthor() )` with `.author( IdentityDisplay.ofAuthor( publicIdentity(), page.getAuthor() ) )`. (The Atom feed `<author>`, the JSON `author`, the plugin `<span class="author">` and the template `%%AUTHOR%%` all read this one field.)

**`RecentChangesPlugin.java`** — imports `com.wikantik.api.identity.IdentityDisplay`, `com.wikantik.api.identity.PublicIdentity`, `com.wikantik.auth.subsystem.AuthSubsystemBridge`. After `final Engine engine = context.getEngine();` add:

```java
        final PublicIdentity identity = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
```

and replace the block

```java
                    if( author != null ) {
                        if( PageSubsystemBridge.fromLegacyEngine( engine ).pages().wikiPageExists( author ) ) {
                            authorinfo.addContent( XhtmlUtil.link( context.getURL( WikiContext.VIEW, author ), author ) );
                        } else {
                            authorinfo.addContent( author );
                        }
                    } else {
```

with

```java
                    if( author != null ) {
                        // The display name as plain text, never a link: the stored value is a login for pages saved
                        // since 2.4.58 and a href built from it would hand it to every reader.
                        authorinfo.addContent( IdentityDisplay.ofAuthor( identity, author ) );
                    } else {
```

**`RecentArticlesServlet.java`** — Javadoc example `"author": "username",` → `"author": "Jane Doe",`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -B -o test -pl wikantik-main \
  -Dtest='DefaultRecentArticlesManagerCITest,DefaultRecentArticlesManagerTest,AtomFeedServletTest,RecentArticlesServletTest,RecentArticlesTest,RecentArticlesCITest,RecentChangesPluginTest,RecentChangesPluginCITest,RecentArticlesQueryTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/content/DefaultRecentArticlesManager.java \
        wikantik-main/src/main/java/com/wikantik/content/RecentArticlesServlet.java \
        wikantik-main/src/main/java/com/wikantik/plugin/RecentChangesPlugin.java \
        wikantik-main/src/test/java/com/wikantik/content/DefaultRecentArticlesManagerCITest.java \
        wikantik-main/src/test/java/com/wikantik/content/DefaultRecentArticlesManagerTest.java \
        wikantik-main/src/test/java/com/wikantik/content/AtomFeedServletTest.java \
        wikantik-main/src/test/java/com/wikantik/content/RecentArticlesServletTest.java \
        wikantik-main/src/test/java/com/wikantik/plugin/RecentArticlesTest.java \
        wikantik-main/src/test/java/com/wikantik/plugin/RecentChangesPluginTest.java \
        wikantik-main/src/test/java/com/wikantik/plugin/RecentChangesPluginCITest.java
git commit -m "fix(feeds): recent articles hide restricted pages from the public and show display names

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Ontology `dct:creator`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/PageRecordBuilder.java` (ctor ~45–51; `build` ~53–59; `fromRow` ~62–76)
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyPageSync.java` (ctor ~40–48; `onPageSaved` ~56–68)
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java` (~71, ~104)
- Test: `wikantik-main/src/test/java/com/wikantik/ontology/runtime/PageRecordBuilderTest.java`, `OntologyPageSyncTest.java`
- **No change:** `wikantik-ontology/.../PageProjector.java` (~55–57): it writes whatever `PageRecord.author()` holds; the record is built in `wikantik-main`, where the identity service lives (`wikantik-ontology` depends only on `wikantik-api` and Jena).

**Interfaces:**
- Consumes: `IdentityDisplay.ofLoginOrText` (Task 7); `PublicIdentity`; `WikiEngine#getAuthSubsystem()` (the Auth subsystem is built before `initKnowledgeGraph`, which calls `OntologyWiringHelper.wireOntology`).
- Produces: `PageRecordBuilder( PageManager, Supplier< List< Row > >, PublicIdentity )` and `PageRecordBuilder.fromRow( Row, PageManager, PublicIdentity )` (the 2-argument forms stay and pass `null` = no account is known); `OntologyPageSync( manager, dao, pageManager, isPublic, PublicIdentity )` (the 4-argument form stays).

`dct:creator` is the page's *frontmatter* `author:` (editor-typed text such as `claude-code-researcher`), not the version author, so it follows the frontmatter rule: replaced by the display name only when the value is an existing login. The value is baked into the materialised dataset at projection time; a rename reaches `/sparql` and `/export/graph.nt` on the page's next save or the nightly reconciliation rebuild — Task 24 includes an ontology rebuild after deploy.

- [ ] **Step 1: Write the failing tests**

`PageRecordBuilderTest` — add `import com.wikantik.api.identity.PublicIdentity;`, `import java.util.Optional;` and:

```java
    @Test
    void frontmatterAuthorThatIsALoginIsProjectedAsTheDisplayName() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000003", "Authored", "Authored", "article", null, Instant.EPOCH, Instant.EPOCH );
        when( pageManager.getPureText( "Authored", -1 ) ).thenReturn( "---\nauthor: jakefear\n---\nBody." );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "jakefear" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( "jakefear" ) ).thenReturn( "Jake Fear" );

        final PageRecordBuilder builder = new PageRecordBuilder( pageManager, () -> List.of( row ), identity );

        assertEquals( "Jake Fear", builder.build().get( 0 ).author() );
    }

    @Test
    void frontmatterAuthorThatIsNotALoginIsProjectedAsWritten() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000004", "Agent", "Agent", "article", null, Instant.EPOCH, Instant.EPOCH );
        when( pageManager.getPureText( "Agent", -1 ) ).thenReturn( "---\nauthor: claude-code-researcher\n---\nBody." );
        final PublicIdentity identity = mock( PublicIdentity.class );   // knows no logins

        final PageRecord record = PageRecordBuilder.fromRow( row, pageManager, identity );

        assertEquals( "claude-code-researcher", record.author() );
    }
```

Add `import static org.mockito.Mockito.mock;` to that file. `OntologyPageSyncTest` — add imports `com.wikantik.api.identity.PublicIdentity`, `org.apache.jena.rdf.model.Model`, `java.util.List`, `static org.junit.jupiter.api.Assertions.assertEquals`, `static org.mockito.Mockito.mock`, and:

```java
    private static List< String > creators( final OntologyModelManager mgr ) {
        final Model graph = mgr.namedGraphSnapshot( Iris.page( CID ) );
        return graph.listObjectsOfProperty( graph.createProperty( "http://purl.org/dc/terms/creator" ) )
                    .mapWith( node -> node.asLiteral().getString() ).toList();
    }

    @Test
    void onPageSavedProjectsTheFrontmatterAuthorAsADisplayNameWhenItIsALogin() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "jakefear" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( "jakefear" ) ).thenReturn( "Jake Fear" );
        when( dao.findBySlug( "Solo" ) ).thenReturn( Optional.of( row( "Solo" ) ) );
        when( pageManager.getPureText( "Solo", -1 ) ).thenReturn( "---\nauthor: jakefear\n---\nbody" );

        new OntologyPageSync( mgr, dao, pageManager, slug -> true, identity ).onPageSaved( "Solo" );

        assertEquals( List.of( "Jake Fear" ), creators( mgr ) );
    }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-main -Dtest='PageRecordBuilderTest,OntologyPageSyncTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `COMPILATION ERROR` — the 3-argument `PageRecordBuilder` constructor, `fromRow( row, pageManager, identity )` and the 5-argument `OntologyPageSync` constructor do not exist.

- [ ] **Step 3: Implement**

**`PageRecordBuilder.java`** — add imports `com.wikantik.api.identity.IdentityDisplay` and `com.wikantik.api.identity.PublicIdentity`. Replace everything from the `pageManager` field down to and including the head of `fromRow` (the old one-line `/** Turns one canonical-id row … */` Javadoc above `fromRow` goes too — two stacked Javadoc blocks would silently drop the first):

```java
    private final PageManager pageManager;
    private final Supplier< List< PageCanonicalIdsDao.Row > > rowSource;
    /** {@code null} = no identity service: frontmatter authors are projected exactly as written. */
    private final PublicIdentity publicIdentity;

    public PageRecordBuilder( final PageManager pageManager,
                              final Supplier< List< PageCanonicalIdsDao.Row > > rowSource ) {
        this( pageManager, rowSource, null );
    }

    public PageRecordBuilder( final PageManager pageManager,
                              final Supplier< List< PageCanonicalIdsDao.Row > > rowSource,
                              final PublicIdentity publicIdentity ) {
        this.pageManager = pageManager;
        this.rowSource = rowSource;
        this.publicIdentity = publicIdentity;
    }

    public List< PageRecord > build() {
        final List< PageRecord > out = new ArrayList<>();
        for ( final PageCanonicalIdsDao.Row row : rowSource.get() ) {
            out.add( fromRow( row, pageManager, publicIdentity ) );
        }
        return out;
    }

    /** As {@link #fromRow(PageCanonicalIdsDao.Row, PageManager, PublicIdentity)} with no identity service. */
    public static PageRecord fromRow( final PageCanonicalIdsDao.Row row, final PageManager pageManager ) {
        return fromRow( row, pageManager, null );
    }

    /**
     * Turns one canonical-id row into a PageRecord, enriching tags/summary/date/author from frontmatter. The
     * frontmatter {@code author:} is editor-typed text: it becomes the account's display name only when it is an
     * existing login (the public dataset must not publish logins), otherwise it is projected as written.
     */
    public static PageRecord fromRow( final PageCanonicalIdsDao.Row row, final PageManager pageManager,
                                      final PublicIdentity publicIdentity ) {
```

and in the `new PageRecord( … )` call replace the last argument `str( md.get( "author" ) ) );` with `IdentityDisplay.ofLoginOrText( publicIdentity, str( md.get( "author" ) ) ) );`.

**`OntologyPageSync.java`** — import `com.wikantik.api.identity.PublicIdentity`; add the field `private final PublicIdentity publicIdentity;`, keep the 4-argument constructor delegating, add the 5-argument one, and change the `fromRow` call:

```java
    public OntologyPageSync( final OntologyModelManager manager, final PageCanonicalIdsDao dao,
                             final PageManager pageManager, final java.util.function.Predicate< String > isPublic ) {
        this( manager, dao, pageManager, isPublic, null );
    }

    public OntologyPageSync( final OntologyModelManager manager, final PageCanonicalIdsDao dao,
                             final PageManager pageManager, final java.util.function.Predicate< String > isPublic,
                             final PublicIdentity publicIdentity ) {
        this.manager = manager;
        this.dao = dao;
        this.pageManager = pageManager;
        this.isPublic = isPublic;
        this.publicIdentity = publicIdentity;
    }
```

```java
        final PageRecord record = PageRecordBuilder.fromRow( row.get(), pageManager, publicIdentity );
```

**`OntologyWiringHelper.java`** — replace

```java
        final PageRecordBuilder pageBuilder = new PageRecordBuilder( pageManager, pageDao::findAll );
```

with

```java
        // Frontmatter authors that are logins are published as display names (public dataset).
        final com.wikantik.api.identity.PublicIdentity publicIdentity = publicIdentityOf( engine );
        final PageRecordBuilder pageBuilder = new PageRecordBuilder( pageManager, pageDao::findAll, publicIdentity );
```

replace `new OntologyPageSync( mgr, pageDao, pageManager, isPublic )` with `new OntologyPageSync( mgr, pageDao, pageManager, isPublic, publicIdentity )`, and add the helper at the bottom of the class:

```java
    /** The Auth subsystem is built before the knowledge graph (and so this wiring); a missing one is logged loudly. */
    private static com.wikantik.api.identity.PublicIdentity publicIdentityOf( final WikiEngine engine ) {
        final com.wikantik.auth.subsystem.AuthSubsystem.Services auth = engine.getAuthSubsystem();
        if ( auth == null || auth.publicIdentity() == null ) {
            LOG.warn( "No PublicIdentity at ontology wiring time; frontmatter authors will be projected as written" );
            return null;
        }
        return auth.publicIdentity();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -B -o test -pl wikantik-main \
  -Dtest='PageRecordBuilderTest,OntologyPageSyncTest,OntologyWiringHelperTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main
```
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/ontology/runtime/PageRecordBuilder.java \
        wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyPageSync.java \
        wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/PageRecordBuilderTest.java \
        wikantik-main/src/test/java/com/wikantik/ontology/runtime/OntologyPageSyncTest.java
git commit -m "feat(ontology): dct:creator publishes a login-valued frontmatter author as the display name

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `MyPagesResource` + `PageOwnerService`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/MyPagesResource.java` (Javadoc ~51–56; seam ~74–90; match ~112–113; imports)
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceSessionTest.java`
- Create: `wikantik-main/src/test/java/com/wikantik/comments/PageOwnerServiceAuthorKeyTest.java`
- **No change:** `wikantik-main/src/main/java/com/wikantik/comments/PageOwnerService.java` (~98–99): it already does `authorResolver.apply( canonicalId ).filter( userExists )` and the engine's `pageAuthorLookup` returns `page.getAuthor()` — once Task 6 stores the login, that value is a login and resolves. The test below pins that cross-task contract.

**Interfaces:**
- Consumes: `com.wikantik.auth.AuthorKeys.authorKeyFor( Session )` (Task 6) — the one definition of "the string a writer records for this session"; `com.wikantik.AuthoredPages` (Task 7).
- Produces: `GET /api/me/pages` matches `Page#getAuthor()` **exactly** against the caller's login.

Decision: legacy full-name matching is **dropped**. A full name is not an identity — names are re-takeable after a rename, and duplicates existed before the uniqueness rules (the "Jake Fear" ×4 group) — and matching them is precisely the shared-name mix-up this fixes. Pages whose version author is a legacy string stay reachable through search, history and the page list; they rejoin "My pages" the next time the user saves them. The same reasoning leaves `PageOwnerService` without a legacy-name fallback (ownership grants moderation rights, so a name must never confer it): a page whose latest author is a legacy full name keeps bootstrapping to the configured default owner. Task 23 records the user-visible consequence ("My pages lists pages saved since 2.4.58") in the CHANGELOG.

- [ ] **Step 1: Write the failing tests**

`wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceSessionTest.java`:

```java
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.AuthoredPages;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /api/me/pages} against a real session: it matches the stored author exactly against the caller's
 * <em>login</em>. Full names (legacy authorship) and other spellings of the login do not count.
 */
class MyPagesResourceSessionTest {

    private static TestEngine engine;
    private static MyPagesResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( MyPagesResource::new, engine );
        // Stored the way legacy writers (a full name) and other accounts were recorded.
        AuthoredPages.save( engine, "MyPagesLegacyName", "Saved under a full name.", "Janne Jalkanen" );
        AuthoredPages.save( engine, "MyPagesOtherCase", "Saved under another spelling.", "JANNE" );
        AuthoredPages.save( engine, "MyPagesSomeoneElse", "Saved by another account.", "Fred" );
        // Last: leaves the shared mock HTTP session logged in as janne for the request below.
        engine.saveTextAsJanne( "MyPagesOwn", "Saved under janne's login." );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void listsOnlyPagesWhoseStoredAuthorIsTheCallersLogin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/me/pages" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final List< String > slugs = new ArrayList<>();
        gson.fromJson( sw.toString(), JsonObject.class ).getAsJsonArray( "pages" )
            .forEach( p -> slugs.add( p.getAsJsonObject().get( "slug" ).getAsString() ) );
        assertEquals( List.of( "MyPagesOwn" ), slugs,
            "a full name or another spelling of the login must not claim authorship" );
    }
}
```

`wikantik-main/src/test/java/com/wikantik/comments/PageOwnerServiceAuthorKeyTest.java`:

```java
package com.wikantik.comments;

import com.wikantik.AuthoredPages;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the cross-task contract between authorship and ownership: {@code WikiEngine}'s {@code pageAuthorLookup}
 * hands {@code PageOwnerService} the page's <em>version author</em>, and the service accepts it only when it is an
 * existing login. Since authorship stores the login, a page saved by an account bootstraps to that account;
 * a legacy full-name author still does not (a name never confers ownership).
 */
@RequiresPostgres
class PageOwnerServiceAuthorKeyTest {

    private static final Logger LOG = LogManager.getLogger( PageOwnerServiceAuthorKeyTest.class );

    private static TestEngine engine;
    private DataSource ds;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "page_owners" );
    }

    /** Mirrors {@code WikiEngine.initKnowledgeGraph}'s userExistsLookup. */
    private static Predicate< String > accountExists() {
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        return login -> {
            try {
                db.findByLoginName( login );
                return true;
            } catch ( final NoSuchPrincipalException e ) {
                LOG.debug( "No account '{}' in the test user database: {}", login, e.getMessage() );
                return false;
            }
        };
    }

    /** Mirrors {@code WikiEngine.initKnowledgeGraph}'s pageAuthorLookup (here the "canonical id" is the page name). */
    private static Function< String, Optional< String > > versionAuthorOf() {
        final PageManager pm = engine.getManager( PageManager.class );
        return pageName -> Optional.ofNullable( pm.getPage( pageName ) )
                .map( Page::getAuthor )
                .filter( author -> !author.isBlank() );
    }

    @Test
    void aPageSavedByAnAuthenticatedAccountBootstrapsToThatAccountsLogin() throws Exception {
        engine.saveText( "OwnerAuthorKeyPage", "Saved by the admin account." );   // author = session's login principal
        final PageOwnerService svc = new PageOwnerService( ds, accountExists(), versionAuthorOf() );

        svc.getOwner( "OwnerAuthorKeyPage" );

        final var row = svc.findRaw( "OwnerAuthorKeyPage" ).orElseThrow();
        assertEquals( "admin", row.ownerLogin(), "the stored author is the login, so the owner resolves" );
        assertEquals( PageOwnerService.BOOTSTRAP_ASSIGNER, row.assignedBy() );
    }

    @Test
    void aLegacyFullNameAuthorIsNotMappedToAnAccount() throws Exception {
        AuthoredPages.save( engine, "OwnerLegacyNamePage", "Saved under a full name.", "Administrator" );
        final PageOwnerService svc = new PageOwnerService( ds, accountExists(), versionAuthorOf() );

        svc.getOwner( "OwnerLegacyNamePage" );

        assertNull( svc.findRaw( "OwnerLegacyNamePage" ).orElseThrow().ownerLogin(),
            "a display name must never confer ownership; the page stays orphaned until assigned" );
    }
}
```

- [ ] **Step 2: Run them to verify they fail / pin the contract**

```bash
bin/agent-build.sh start b-upstream -- mvn -B -o install -pl wikantik-main -DskipTests
bin/agent-build.sh wait b-upstream 540
mvn -B -o test -pl wikantik-rest -Dtest='MyPagesResourceSessionTest,MyPagesResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-main -Dtest='PageOwnerServiceAuthorKeyTest,PageOwnerServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `MyPagesResourceSessionTest` **fails** — `expected: <[MyPagesOwn]> but was: <[MyPagesOwn, MyPagesLegacyName, MyPagesOtherCase]>` (today the caller's identities include the full name `Janne Jalkanen` and the match is case-insensitive). `PageOwnerServiceAuthorKeyTest` needs Docker; it **passes** as soon as Task 6 has landed (`expected: <admin> but was: <null>` is what it reports on a tree where writers still store the full name `Administrator`) — this task ships it as a regression pin, with no production change to `PageOwnerService`.

- [ ] **Step 3: Implement**

`MyPagesResource.java` — add `import com.wikantik.auth.AuthorKeys;`, drop the now unused `java.util.HashSet` import, and update the class Javadoc paragraph that begins `"Authored" means …`:

```java
 * <p>"Authored" means {@link Page#getAuthor()} <em>equals</em> the caller's login — the value every writer
 * records via {@link AuthorKeys#authorKeyFor}. Full names (legacy authorship) and other spellings of the login
 * never match, so users who share a display name no longer see each other's pages. This is the author of the
 * current version (the last person to save), so a page someone else last edited will not appear; full version
 * history is not walked. Backed by {@link PageManager#getRecentChanges()}, which already returns pages ordered
 * most-recently-modified-first and carries author + last-modified on each {@link Page} (the same scan that
 * powers {@code /api/recent-changes}).</p>
```

Replace the `currentUserIdentities` seam — its Javadoc and its body, lines ~72–90 — with:

```java
    /**
     * Seam — the stored author strings that count as "me", overridable for unit tests: exactly the caller's
     * login, i.e. what {@link AuthorKeys#authorKeyFor} records when this caller saves a page.
     */
    protected Set< String > currentUserIdentities( final HttpServletRequest request ) {
        final Session s = Wiki.session().find( getEngine(), request );
        if ( s == null || !s.isAuthenticated() ) {
            return Set.of();
        }
        final String key = AuthorKeys.authorKeyFor( s );
        return key == null ? Set.of() : Set.of( key );
    }
```

and in `doGet` replace the match

```java
            if ( author != null && me.stream().anyMatch( author::equalsIgnoreCase ) ) {
```

with

```java
            if ( author != null && me.contains( author ) ) {
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -B -o test -pl wikantik-rest -Dtest='MyPagesResourceSessionTest,MyPagesResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-main -Dtest='PageOwnerServiceAuthorKeyTest,PageOwnerServiceTest,PageOwnerServiceErrorBranchTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-rest
```
Expected: all green (the Docker-backed owner tests skip with a visible reason when no daemon is present; CI runs them with `-Dtests.requireDocker=true`).

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/MyPagesResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MyPagesResourceSessionTest.java \
        wikantik-main/src/test/java/com/wikantik/comments/PageOwnerServiceAuthorKeyTest.java
git commit -m "fix(rest): My pages matches the stored login exactly; pin login-author page ownership

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Section notes

**Contract issues found in the real code**

1. **`SearchResource` and `SearchWikiTool` do not change, by design (contract/lead listed both).** `author` reaches them as `RetrievedPage#author`, built by `DefaultContextRetrievalService`; the same record is serialised whole by the three knowledge-MCP tools (`retrieve_context`, `get_page`, `list_pages`). Resolving in `DefaultContextRetrievalService` (Task 9) covers all five consumers with one change. Resolving *again* in `SearchResource`/`SearchWikiTool` would be unsafe: in legacy data a display name can equal another account's login (seeded `Fred` → `Fred Flintstone`), so `displayName( displayName( x ) )` can swap people. Task 8 adds a guard test (`authorFromTheRetrievalServiceIsServedAsIs_notResolvedASecondTime`) and Task 9 documents the rule on `RetrievedPage`. Task 8 is therefore Lucene-only on the production side.
2. **`AtomFeedServlet`, `RecentArticlesServlet`, `RecentArticles` plugin need no code change** — they render `ArticleSummary`, so the fix lives in `DefaultRecentArticlesManager` (resolve the author *and* add the anonymous-view filter). That manager's cache is shared and caller-independent, hence the filter is anonymous-only. Note the old `RecentArticlesServlet` sent `Cache-Control: public, max-age=60` over unfiltered restricted content; Task 10 closes that.
3. **Component initialisation order matters for non-REST wiring.** `SearchManager` (Lucene) and `RecentArticlesManager` are initialised in `WikiEngine.initManagers`, *before* `AuthSubsystemFactory.create` (line ~703). Both therefore take the identity lazily (`WikiEngine#getAuthSubsystem()` / `AuthSubsystemBridge.fromLegacyEngine(...)` at call time), never at `initialize`. For Lucene the author field is **omitted** while no identity is available (fail-closed) rather than indexing a login.
4. **`for-agent` projection is cached** (`DefaultForAgentProjectionService`, key `canonicalId:updatedAtMillis`), so resolving inside the service would freeze names until the page changes. Task 9 adds `ForAgentProjection#withDisplayNames` and calls it in the two serving surfaces (`PageForAgentResource`, `GetPageForAgentTool`).
5. **`wikantik-ontology` cannot see the identity service** (depends only on `wikantik-api` + Jena), so `dct:creator` resolution is in `wikantik-main`'s `PageRecordBuilder`/`OntologyPageSync`. The value is baked into TDB2 at projection time → Task 24 must trigger `/admin/ontology/rebuild` after deploy (existing graphs may carry login-valued frontmatter authors), in addition to the chunk + Lucene rebuild.
6. **`PageOwnerService` needs no production change** — its resolver already receives `page.getAuthor()` (the *version* author, not frontmatter) and filters by `userExists`; Task 6 makes that a login. Task 12 only adds a regression pin (+ a test that a legacy full name does *not* map to an account). I deliberately did **not** add a `loginForDisplayName` fallback for legacy authors (ownership grants moderation rights).
7. **`MyPagesResource` behaviour change to record in Task 23 (CHANGELOG/docs):** "My pages" now lists only pages whose latest version was saved under the caller's login, i.e. saved since 2.4.58; pages whose latest author is a legacy full name drop out until re-saved. This is the spec's "matches the stored login exactly", with legacy matching dropped on purpose (names are re-takeable; duplicates existed).
8. **`RecentChangesPlugin` no longer links the author** (the old code linked to a wiki page named after the stored author — a login `href` for new pages). Existing test `RecentChangesPluginCITest.testAuthorWhoIsAWikiPage` is replaced.
9. **Frontmatter rule:** `author:`/`verified_by:` are replaced only when `PublicIdentity#userIdForLogin` is present; editor-typed text (including an editor-typed email) is shown as written, per the spec's out-of-scope note. If you want email-shaped frontmatter masked too, change `IdentityDisplay#ofLoginOrText` (one method, one test) — nothing else depends on it.

**Requirements this section places on Section A (Task 5 `DefaultPublicIdentity` / factory)**

- `userIdForLogin` and `displayName` are called per row on list endpoints (1000 pages) and per article/chunk — they must be O(1) from the 60 s in-memory snapshot, **negatively cached** (legacy full names and agent labels are looked up constantly and are not logins), and never hit the DB per call. `loginForDisplayName` is called once per candidate page in the knowledge `author` filter: also an O(1) map lookup (case-insensitive, trimmed). On duplicate display names (pre-dedupe) it should return one deterministic login (e.g. earliest-created), never throw.
- `AuthSubsystemFactory.create` should tolerate `users == null` / a bare mock engine (publicIdentity `null`, no exception). This section's tests substitute the lookup wherever the engine is a Mockito double, but `AuthSubsystemBridge.fromLegacyEngine( mockWikiEngine )` calls `rebuildFromManagers` → the factory, and other modules' mock-based tests can reach it. Non-`WikiEngine` engines keep returning an all-`null` `Services` — the record now has 9 components, so update the `new AuthSubsystem.Services( null × 8 )` in `AuthSubsystemBridge`.
- **Engine-side accessor used here.** Non-REST code reads the service as `AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity()` (request-time, lazily) or `WikiEngine#getAuthSubsystem().publicIdentity()` (Lucene and ontology wiring, where the bridge's rebuild-from-managers fallback must be avoided). If Task 5 documents a different accessor, change exactly these sites: `LuceneSearchProvider#publicIdentity`, `OntologyWiringHelper#publicIdentityOf`, `DefaultRecentArticlesManager#publicIdentity`, `RecentChangesPlugin#execute`, `DefaultContextRetrievalService#fromEngine`, `KnowledgeMcpInitializer` (builder chain), tools `GetPageTool#publicIdentity`. REST code uses `getSubsystems().auth().publicIdentity()` as the contract says.
- `PublicIdentity` must expose exactly the five methods + two constants in the contract; the tests in this section implement/mock them.
- Section B tests rely on the engine-wired `DefaultPublicIdentity` resolving `TestEngine`'s seeded `InMemoryUserDatabase` accounts: `janne` → `Janne Jalkanen`, `admin` → `Administrator`, `Fred` → `Fred Flintstone`, and `userIdForLogin` being present for them.

**Cross-section dependencies / sequencing**

- **Task 6 must set the default author from `AuthorKeys.authorKeyFor( session )`** in `DefaultPageLifecycle` (so `TestEngine#saveText` stores `admin`, and `PageOwnerServiceAuthorKeyTest` / `testPatchPage_responseAuthorIsADisplayName` mean something). Task 6 also edits `PageResource` ~466 and ~723 (the author *writers*); Task 7 edits `PageResource` ~296–298 and ~730–731 (the *readers*) — different hunks, but expect a trivial rebase if they land out of order. Task 6 will change what `engine.saveText(...)` records (login instead of full name): other modules' tests that assert the full name `Administrator` as a stored author will need updating there.
- **Task 18 (Section D)** also edits `PageResource.doGet` (~289–296, `content` stripping) next to my metadata/author hunk; apply sequentially.
- **Task 7 creates `com.wikantik.AuthoredPages`** (wikantik-main test-jar) and `IdentityDisplay` (wikantik-api); Tasks 8–12 consume them. Sections C/D/E may reuse `AuthoredPages` for "page whose stored author is X" fixtures.
- **Task 22 (leak probe)** should additionally assert, for the surfaces in this section: `/feed.xml` and `/api/recent-articles` omit a restricted page; `/api/search` `author`, `/api/pages/{n}` `metadata.author`, `/api/pages/for-agent/{id}` `recent_changes[].author`, `/tools/get_page` `author`, and the Lucene hit for a query equal to the leak-probe *login* (must return nothing) are display names; `/sparql` `dct:creator` for a page whose frontmatter `author:` is the probe login.
- **Task 24 rollout:** rebuild Lucene (author field) *and* chunks (ACL strip, Task 19) *and* the ontology (`dct:creator`), after the Task 21 dedupe script has run (`loginForDisplayName` and the knowledge `author` filter assume unique names).

**Out-of-scope observations (pre-existing, not fixed here — decide whether to file)**

- `SearchWikiTool` (`/tools/search_wiki`) applies **no view-ACL filter** to retrieval results (`SearchResource` and the knowledge-MCP tools do), so API-key callers can receive restricted pages' names, summaries and chunk text.
- `RecentChangesPlugin` lists recently changed pages (names + change notes) without a view-permission filter for the viewing user.
- Admin MCP `WikiResources` (`wikantik-admin-mcp`, `author` at ~160/196/225) keeps showing stored authors — correct per "admin surfaces unchanged".
- The MCP tool descriptors' example values used logins (`jakefear`); Task 9 changes the examples to display names only (strings, no behaviour).

---

# Section C — Comments and mentions (Tasks 13–16)

# Section C — Comments & mentions (Tasks 13–16)

Spec: `docs/superpowers/specs/2026-10-13-display-names-design.md` §3 (comment/mention rows), §4 (mentions), §6 (frontend).
Contract: `plan-contract.md` (fixed names, JSON shapes, `PublicIdentity` API).

Everything in this section was drafted against the tree at `bf12845fff` **and executed in a scratch copy** (the diffs below were
compiled and their tests run green: 195 backend tests across the touched classes against a real pgvector container, and the full
2 864-test vitest suite + ESLint for the frontend; the red states quoted in Step 2 are real output). `PublicIdentity` and
`AuthSubsystem.Services#publicIdentity()` (Task 5) did not exist in the tree, so the scratch run used the contract's exact interface
plus a throw-away implementation — the executor's first compile after Task 5 is the real confirmation.

## Read this first

**Order and independence.** Execute strictly 13 → 14 → 15 → 16; each task compiles, passes its own tests and is committable on its own.

- **Task 13 does not need Task 14.** The `mentions` map must list the uids named by `@[Name](user:<uid>)` tokens, so Task 13 carries a
  4-line private parser (`MENTION_TOKEN` + `mentionUserIds`) inside `CommentThreadResource`. **Task 14 Step 3 deletes it** and calls
  `MentionExtractor.extract( body ).userIds()` instead, so the token regex has exactly one definition afterwards. Task 13's tests are
  unchanged by that swap and keep passing. (If the coordinator prefers to land 14 first, skip the private parser in Task 13 and use
  `extract` directly — nothing else changes.)
- **Task 15** is independent of 14 at compile level; only its wire-level IT (a token mention reaching the recipient's feed) needs 13
  (`userId`) and 14 (token → login).
- **Task 16** is mocked against the JSON shapes of 13 and 15; it is the same WAR, so client and server ship together.

**Gson drops nulls.** `RestServletBase.GSON` does not `serializeNulls`, so every "null" in the contract (`userId` for anonymous,
`authorId` for a non-member, `resolvedBy*` on an open thread, `snippet` when hidden) means **the key is absent**. Server tests assert
`!has(..) || isJsonNull()`; clients must treat `undefined` as null (`currentUserId && c.authorId === currentUserId`).

**Logins stay the storage key.** In this section nothing about *storage* changes: `comment_threads.created_by/resolved_by`,
`comments.author`, `comment_mentions.{mentioned,mentioning}_login` and `page_owners` keep holding logins, and every authorisation check
(comment edit/delete author test, owner-mention comparison) keeps comparing **logins** via `currentUser( request )`
(`getLoginPrincipal().getName()`). Only *responses* are translated, through `PublicIdentity`. Display names are unique but mutable, so
they are never an access-control key — there are regression tests for exactly that.

**Prerequisites and commands.** Docker must be running (`CommentThreadResourceTest`, `MentionServiceTest`, `PersistenceSubsystemFactoryTest`
use `PostgresTestDb`). `wikantik-rest` compiles against the *installed* `wikantik-main` jar, so install main after Task 5 lands and again
after Task 14 changes it (long build → `bin/agent-build.sh`, never a bare foreground `mvn`):

```bash
bin/agent-build.sh start c-main -- mvn -B -o install -pl wikantik-main -am -DskipTests
bin/agent-build.sh wait c-main 540        # exit 0 = success, 2 = still running (call again), 1 = failed (read: bin/agent-build.sh tail c-main 40)
```

**Applying the diffs.** Each ```` ```diff ```` block is a unified diff against the repo root. Apply it by pasting it on stdin to
`patch -p1 --no-backup-if-mismatch` (tolerates line offsets if an earlier task shifted the surrounding code), or edit by hand — the `+`
lines are the exact final text. Files shown whole are written whole.

---

### Task 13: Comment-threads API returns display names; `/api/auth/user` gains `userId`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java` — imports 21-40; class Javadoc 42-46; new `publicIdentity()` seam after `pageOwnerService()` (~58-60); `doGet` 105-111; `createThread` 200-202; `addReply` 216-218; `editComment` 254-255; `threadToMap`/`commentToMap` 335-363 (+ private helpers)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java:259-270` (`handleGetUser`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java`

**Interfaces:**
- Consumes (Task 5): `com.wikantik.api.identity.PublicIdentity` — `displayName(String)`, `userIdForLogin(String)`, `loginForUserId(String)`; reached as
  `getSubsystems().auth().publicIdentity()`.
- Produces: `protected PublicIdentity CommentThreadResource#publicIdentity()` (test seam); the contract's JSON — `createdBy`, `resolvedBy`,
  `comments[].author` as display names; `createdById`, `resolvedById`, `comments[].authorId` as uids (absent for non-members / unset);
  top-level `mentions: { "<uid>": "<current display name>" }` on `GET` (unknown uids omitted so clients fall back to the snapshot text; always
  present, `{}` when there are no tokens); `GET /api/auth/user` field `userId` (the caller's own uid, absent when anonymous). Private
  `MENTION_TOKEN` / `mentionUserIds` — **deleted by Task 14**.

What this task audited (every use of the caller identity in `CommentThreadResource`, all stay logins): `currentUser` (lines 70-73) feeds
`createThread` (`owner.equals( user )` owner-mention test and `createThread(.. user ..)` storage, 196-201), `addReply` (217), `setStatus`
(`resolve( .., user )`, 226), the author checks in `editComment` (239) and `deleteComment` (262), and `editComment` storage (252). None of
them is touched; the new `new_comments_and_resolutions_are_stored_under_the_login` and
`edit_and_delete_authorisation_still_compare_logins_not_display_names` tests pin that.

- [ ] **Step 1: Write the failing tests**

New tests: display names + uids on `GET` (thread, resolver, every comment) and that the login string appears nowhere in the JSON; a
non-member author keeps its stored label and has no `authorId`; an open thread has no `resolvedBy*`; create/reply responses use display names
too; the `mentions` map covers tokens from the opening comment *and* replies, uses the current name not the snapshot, omits unknown uids, is
present-and-empty with no tokens, and ignores legacy `@login` and malformed tokens (the identity mock is verified `never()` consulted); two
accounts sharing a display name cannot edit/delete each other's comments; storage still receives the login; the new seam delegates to
`getSubsystems().auth()`; `GET /api/auth/user` carries the caller's own `userId` (real engine, real user database) and none for anonymous.
`setUp` now installs a `PublicIdentity` mock whose defaults are "show the stored value as-is, nobody has a uid", so all pre-existing tests
are unaffected.

```diff
--- a/wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java
+++ b/wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java
@@ -23,6 +23,8 @@
 import com.google.gson.JsonObject;
 import com.wikantik.HttpMockFactory;
 import com.wikantik.WikiSubsystems;
+import com.wikantik.api.identity.PublicIdentity;
+import com.wikantik.auth.subsystem.AuthSubsystem;
 import com.wikantik.comments.CommentStore;
 import com.wikantik.comments.PageOwnerService;
 import com.wikantik.comments.mentions.MentionService;
@@ -55,6 +57,7 @@
     private CommentStore store;
     MentionService mentionService;
     private PageOwnerService pageOwnerSvc;
+    private PublicIdentity identity;
     private CommentThreadResource servlet;
 
     @BeforeEach
@@ -78,6 +81,168 @@
         Mockito.doReturn( true ).when( servlet ).checkPagePermission(
                 Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );
         Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
+
+        // Identity defaults: a stored principal is shown as itself and nobody has a uid. Individual tests
+        // override the logins they care about (a later stubbing wins).
+        this.identity = Mockito.mock( PublicIdentity.class );
+        Mockito.when( identity.displayName( Mockito.any() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
+        Mockito.when( identity.userIdForLogin( Mockito.any() ) ).thenReturn( Optional.empty() );
+        Mockito.when( identity.loginForUserId( Mockito.any() ) ).thenReturn( Optional.empty() );
+        Mockito.doReturn( identity ).when( servlet ).publicIdentity();
+    }
+
+    // ---- display names on the wire (logins stay in storage) ----
+
+    /** alice's login is "alice"; everyone else sees "Alice A" and the opaque uid "uid-0001". */
+    private void aliceIsShownAsAliceA() {
+        Mockito.when( identity.displayName( "alice" ) ).thenReturn( "Alice A" );
+        Mockito.when( identity.userIdForLogin( "alice" ) ).thenReturn( Optional.of( "uid-0001" ) );
+    }
+
+    @Test
+    void get_returns_display_names_and_uids_never_the_login() throws Exception {
+        aliceIsShownAsAliceA();
+        final String threadId = createThread();
+        post( null, "/" + threadId + "/comments", textBody( "a reply" ) );
+        post( null, "/" + threadId + "/resolve", new JsonObject() );
+
+        final JsonObject list = get( "?page=PageOne&status=all" );
+        final JsonObject thread = list.getAsJsonArray( "threads" ).get( 0 ).getAsJsonObject();
+        assertEquals( "Alice A", thread.get( "createdBy" ).getAsString() );
+        assertEquals( "uid-0001", thread.get( "createdById" ).getAsString() );
+        assertEquals( "Alice A", thread.get( "resolvedBy" ).getAsString() );
+        assertEquals( "uid-0001", thread.get( "resolvedById" ).getAsString() );
+        final JsonArray comments = thread.getAsJsonArray( "comments" );
+        assertEquals( 2, comments.size() );
+        for ( int i = 0; i < comments.size(); i++ ) {
+            final JsonObject c = comments.get( i ).getAsJsonObject();
+            assertEquals( "Alice A", c.get( "author" ).getAsString() );
+            assertEquals( "uid-0001", c.get( "authorId" ).getAsString() );
+        }
+        assertFalse( list.toString().contains( "\"alice\"" ), "the login must not appear anywhere: " + list );
+    }
+
+    @Test
+    void an_author_who_is_not_a_member_has_no_authorId_and_keeps_the_stored_label() throws Exception {
+        // A legacy comment whose author was a plain name rather than a login.
+        store.createThread( "CID1", new com.wikantik.api.comments.TextQuoteSelector( "hello", null, null ),
+                "Legacy Name", "old comment", mentionService, Optional.empty() );
+
+        final JsonObject c = get( "?page=PageOne&status=all" ).getAsJsonArray( "threads" ).get( 0 )
+                .getAsJsonObject().getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject();
+        assertEquals( "Legacy Name", c.get( "author" ).getAsString() );
+        assertFalse( c.has( "authorId" ), "no uid for a non-member: " + c );
+    }
+
+    @Test
+    void an_unresolved_thread_has_no_resolvedBy_fields() throws Exception {
+        aliceIsShownAsAliceA();
+        createThread();
+        final JsonObject thread = get( "?page=PageOne&status=all" ).getAsJsonArray( "threads" ).get( 0 )
+                .getAsJsonObject();
+        assertFalse( thread.has( "resolvedBy" ), thread.toString() );
+        assertFalse( thread.has( "resolvedById" ), thread.toString() );
+    }
+
+    @Test
+    void mutation_responses_use_display_names_too() throws Exception {
+        aliceIsShownAsAliceA();
+        final JsonObject created = post( "?page=PageOne", null, createBody( "first" ) );
+        assertEquals( "Alice A", created.get( "createdBy" ).getAsString() );
+        assertEquals( "uid-0001", created.get( "createdById" ).getAsString() );
+        assertEquals( "Alice A", created.getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject()
+                .get( "author" ).getAsString() );
+
+        final JsonObject reply = post( null, "/" + created.get( "id" ).getAsString() + "/comments",
+                textBody( "a reply" ) );
+        assertEquals( "Alice A", reply.get( "author" ).getAsString() );
+        assertEquals( "uid-0001", reply.get( "authorId" ).getAsString() );
+    }
+
+    @Test
+    void get_carries_a_mentions_map_covering_every_token_in_the_returned_bodies() throws Exception {
+        Mockito.when( identity.loginForUserId( "uid-bob" ) ).thenReturn( Optional.of( "bob" ) );
+        Mockito.when( identity.displayName( "bob" ) ).thenReturn( "Bob B" );
+        Mockito.when( identity.loginForUserId( "uid-carol" ) ).thenReturn( Optional.of( "carol" ) );
+        Mockito.when( identity.displayName( "carol" ) ).thenReturn( "Carol C" );
+
+        final String threadId = post( "?page=PageOne", null,
+                createBody( "ping @[Bobby](user:uid-bob) and @[Ghost](user:uid-gone)" ) ).get( "id" ).getAsString();
+        assertFalse( post( null, "/" + threadId + "/comments", textBody( "cc @[Carol](user:uid-carol)" ) )
+                .has( "error" ) );
+
+        final JsonObject mentions = get( "?page=PageOne&status=all" ).getAsJsonObject( "mentions" );
+        assertEquals( "Bob B", mentions.get( "uid-bob" ).getAsString(), "current name, not the 'Bobby' snapshot" );
+        assertEquals( "Carol C", mentions.get( "uid-carol" ).getAsString() );
+        assertFalse( mentions.has( "uid-gone" ), "an unknown uid is left out; the client falls back to the snapshot" );
+        assertEquals( 2, mentions.size() );
+    }
+
+    @Test
+    void mentions_map_is_present_and_empty_when_no_body_has_a_token() throws Exception {
+        createThread();
+        final JsonObject res = get( "?page=PageOne&status=all" );
+        assertTrue( res.has( "mentions" ), res.toString() );
+        assertEquals( 0, res.getAsJsonObject( "mentions" ).size() );
+    }
+
+    @Test
+    void mentions_map_ignores_legacy_at_logins_and_malformed_tokens() throws Exception {
+        Mockito.when( identity.loginForUserId( Mockito.any() ) ).thenReturn( Optional.of( "bob" ) );
+        post( "?page=PageOne", null, createBody( "legacy @bob and malformed @[Bob](user:) and @[](user:u1)" ) );
+        assertEquals( 0, get( "?page=PageOne&status=all" ).getAsJsonObject( "mentions" ).size() );
+        Mockito.verify( identity, Mockito.never() ).loginForUserId( Mockito.any() );
+    }
+
+    @Test
+    void edit_and_delete_authorisation_still_compare_logins_not_display_names() throws Exception {
+        // mallory and alice share the display name "Alice A" (legacy duplicates exist). Authorship is
+        // decided on the stored login, so mallory must be refused although the names match.
+        Mockito.when( identity.displayName( "alice" ) ).thenReturn( "Alice A" );
+        Mockito.when( identity.displayName( "mallory" ) ).thenReturn( "Alice A" );
+        final String threadId = createThread();
+        final String commentId = store.findThread( java.util.UUID.fromString( threadId ) )
+                .orElseThrow().comments().get( 0 ).id().toString();
+        Mockito.doReturn( "mallory" ).when( servlet ).currentUser( Mockito.any() );
+        Mockito.doReturn( false ).when( servlet ).hasPagePermission(
+                Mockito.any(), Mockito.anyString(), Mockito.anyString() );
+
+        final JsonObject edit = patch( "/" + threadId + "/comments/" + commentId, textBody( "hijack" ) );
+        assertEquals( 403, edit.get( "status" ).getAsInt(), edit.toString() );
+        final JsonObject delete = del( "/" + threadId + "/comments/" + commentId );
+        assertEquals( 403, delete.get( "status" ).getAsInt(), delete.toString() );
+        assertTrue( store.findComment( java.util.UUID.fromString( commentId ) ).isPresent() );
+
+        // The real author, still identified by login, may edit.
+        Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
+        final JsonObject ok = patch( "/" + threadId + "/comments/" + commentId, textBody( "corrected" ) );
+        assertFalse( ok.has( "error" ), ok.toString() );
+        assertEquals( "alice", store.findComment( java.util.UUID.fromString( commentId ) ).orElseThrow().author(),
+                "storage keeps the login" );
+    }
+
+    @Test
+    void new_comments_and_resolutions_are_stored_under_the_login() throws Exception {
+        aliceIsShownAsAliceA();
+        final String threadId = createThread();
+        post( null, "/" + threadId + "/resolve", new JsonObject() );
+        final var stored = store.findThread( java.util.UUID.fromString( threadId ) ).orElseThrow();
+        assertEquals( "alice", stored.createdBy() );
+        assertEquals( "alice", stored.resolvedBy() );
+        assertEquals( "alice", stored.comments().get( 0 ).author() );
+    }
+
+    private static JsonObject textBody( final String text ) {
+        final JsonObject body = new JsonObject();
+        body.addProperty( "text", text );
+        return body;
+    }
+
+    private static JsonObject createBody( final String text ) {
+        final JsonObject body = new JsonObject();
+        body.addProperty( "exact", "hello" );
+        body.addProperty( "text", text );
+        return body;
     }
 
     @Test
@@ -592,6 +757,22 @@
     }
 
     @Test
+    void publicIdentityDelegatesToAuthSubsystem() {
+        final PublicIdentity expected = Mockito.mock( PublicIdentity.class );
+        final AuthSubsystem.Services auth = Mockito.mock( AuthSubsystem.Services.class );
+        Mockito.when( auth.publicIdentity() ).thenReturn( expected );
+        final CommentThreadResource plain = new CommentThreadResource() {
+            @Override protected WikiSubsystems getSubsystems() {
+                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
+                Mockito.when( subs.auth() ).thenReturn( auth );
+                return subs;
+            }
+        };
+
+        assertSame( expected, plain.publicIdentity() );
+    }
+
+    @Test
     void pageOwnerServiceDelegatesToPersistenceSubsystem() {
         final PageOwnerService expected = Mockito.mock( PageOwnerService.class );
         final PersistenceSubsystem.Services persistence = Mockito.mock( PersistenceSubsystem.Services.class );
```

```diff
--- a/wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java
+++ b/wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java
@@ -111,6 +111,33 @@
     }
 
     @Test
+    void testGetUserAnonymousHasNoUserId() throws Exception {
+        // Gson omits nulls, so "null for anonymous" means the key is absent (or an explicit JSON null).
+        final JsonObject obj = gson.fromJson( doGetUser(), JsonObject.class );
+        assertTrue( !obj.has( "userId" ) || obj.get( "userId" ).isJsonNull(),
+                "an anonymous caller has no uid: " + obj );
+    }
+
+    @Test
+    void testGetUserCarriesTheCallersOwnUserId() throws Exception {
+        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
+        final UserProfile profile = db.newProfile();
+        profile.setLoginName( "useridgetuser" );
+        profile.setFullname( "User Id Get User" );
+        profile.setEmail( "useridget@example.com" );
+        profile.setPassword( "Xk3-Valid-Pass-77!" );
+        db.save( profile );
+        final String expectedUid = db.findByLoginName( "useridgetuser" ).getUid();
+        assertNotNull( expectedUid, "the saved account must have a uid" );
+
+        try ( MockedStatic< Wiki > w = stubWikiSession( authedSession( "useridgetuser" ) ) ) {
+            final JsonObject obj = gson.fromJson( doGetUser(), JsonObject.class );
+            assertEquals( expectedUid, obj.get( "userId" ).getAsString(),
+                    "GET /api/auth/user must carry the caller's own uid" );
+        }
+    }
+
+    @Test
     void testGetUserHasLoginPrincipal() throws Exception {
         final String json = doGetUser();
         final JsonObject obj = gson.fromJson( json, JsonObject.class );
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-rest -Dtest='CommentThreadResourceTest,AuthResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `COMPILATION ERROR … CommentThreadResourceTest.java … cannot find symbol: method publicIdentity()` (the seam does not exist).
To see the behavioural reds instead, temporarily add only the one-line seam
`protected PublicIdentity publicIdentity() { return getSubsystems().auth().publicIdentity(); }` and re-run: five `CommentThreadResourceTest`
methods fail (`get_returns_display_names_and_uids_never_the_login` and `mutation_responses_use_display_names_too`:
`expected: <Alice A> but was: <alice>`; `get_carries_a_mentions_map_…`, `mentions_map_is_present_and_empty_…`,
`mentions_map_ignores_legacy_…`: no `mentions` key), plus
`AuthResourceTest#testGetUserCarriesTheCallersOwnUserId` (`Cannot invoke "com.google.gson.JsonElement.getAsString()" because the return
value of "com.google.gson.JsonObject.get(String)" is null`).

- [ ] **Step 3: Implement**

`CommentThreadResource` resolves every response field through the seam; `threadToMap`/`commentToMap` take the identity as a parameter
(they stay `static`); `shownName`/`uidOf` guard null/blank so an open thread has no `resolvedBy*` and a legacy author label passes through
unchanged (`PublicIdentity.displayName` already masks email-shaped values). `AuthResource` adds the caller's own uid.

```diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java
@@ -23,6 +23,7 @@
 import com.wikantik.api.comments.CommentThread;
 import com.wikantik.api.comments.TextQuoteSelector;
 import com.wikantik.api.core.Engine;
+import com.wikantik.api.identity.PublicIdentity;
 import com.wikantik.api.spi.Wiki;
 import com.wikantik.comments.CommentStore;
 import com.wikantik.comments.PageOwnerService;
@@ -34,19 +35,37 @@
 import java.io.IOException;
 import java.util.ArrayList;
 import java.util.LinkedHashMap;
+import java.util.LinkedHashSet;
 import java.util.List;
 import java.util.Map;
 import java.util.Optional;
+import java.util.Set;
 import java.util.UUID;
+import java.util.regex.Matcher;
+import java.util.regex.Pattern;
 
 /**
  * REST servlet for anchored comment threads. Mapped to {@code /api/comment-threads/*}.
  * Replaces the legacy {@code CommentResource}.
+ *
+ * <p><b>Names on the wire.</b> Comments, threads and mentions are <em>stored</em> against the
+ * author's login (it is the stable key, and under SSO it is an email address), but this
+ * resource is readable by anyone who may view the page, so every author field in a response is
+ * the member's current display name, resolved through {@link PublicIdentity}. The opaque
+ * {@code users.uid} rides along as {@code createdById} / {@code resolvedById} /
+ * {@code comments[].authorId} so a client can recognise its own comments
+ * ({@code authorId === user.userId}) without ever seeing a login. Authorisation in this class
+ * (who may edit or delete a comment) keeps comparing <em>logins</em> — display names are unique
+ * but mutable, so they are never an access-control key.</p>
  */
 public class CommentThreadResource extends RestServletBase {
 
     private static final long serialVersionUID = 1L;
 
+    // @[Display Name](user:<uid>) — keep in step with MentionExtractor's token pattern.
+    private static final Pattern MENTION_TOKEN =
+            Pattern.compile( "@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)" );
+
     protected CommentStore commentStore() {
         return getSubsystems().persistence().comments();
     }
@@ -59,6 +78,11 @@
         return getSubsystems().persistence().pageOwners();
     }
 
+    /** Seam — turns stored logins into display names and uids; overridable for unit tests. */
+    protected PublicIdentity publicIdentity() {
+        return getSubsystems().auth().publicIdentity();
+    }
+
     protected Optional< String > resolveCanonicalId( final String slug ) {
         return getSubsystems().pageGraph().structuralIndexService().resolveCanonicalIdFromSlug( slug );
     }
@@ -104,10 +128,12 @@
         }
         final String status = normalizeStatus( request.getParameter( "status" ) );
         final List< CommentThread > threads = commentStore().listByCanonicalId( canonicalId.get(), status );
+        final PublicIdentity identity = publicIdentity();
 
         final Map< String, Object > result = new LinkedHashMap<>();
         result.put( "page", pageName );
-        result.put( "threads", threads.stream().map( CommentThreadResource::threadToMap ).toList() );
+        result.put( "threads", threads.stream().map( t -> threadToMap( t, identity ) ).toList() );
+        result.put( "mentions", mentionNames( threads, identity ) );
         sendJson( response, result );
     }
 
@@ -199,7 +225,7 @@
                 owner.equals( user ) ? Optional.empty() : Optional.of( owner );
         final CommentThread t = commentStore().createThread(
                 canonical, anchor, user, text, mentionService(), ownerForMention );
-        sendJson( response, threadToMap( t ) );
+        sendJson( response, threadToMap( t, publicIdentity() ) );
     }
 
     private void addReply( final HttpServletRequest request, final HttpServletResponse response,
@@ -215,7 +241,7 @@
         }
         final Comment c = commentStore().addComment(
                 ctx.threadId, currentUser( request ), text, mentionService() );
-        sendJson( response, commentToMap( c ) );
+        sendJson( response, commentToMap( c, publicIdentity() ) );
     }
 
     private void setStatus( final HttpServletRequest request, final HttpServletResponse response,
@@ -252,7 +278,7 @@
                 cc.commentId(), cc.comment().body(), text, currentUser( request ), mentionService() )
                 .orElse( null );
         if ( updated == null ) { sendNotFound( response, "Comment not found" ); return; }
-        sendJson( response, commentToMap( updated ) );
+        sendJson( response, commentToMap( updated, publicIdentity() ) );
     }
 
     private void deleteComment( final HttpServletRequest request, final HttpServletResponse response,
@@ -332,7 +358,48 @@
         return trimmed.split( "/" );
     }
 
-    private static Map< String, Object > threadToMap( final CommentThread t ) {
+    /**
+     * {@code uid -> current display name} for every mention token in the returned comment bodies,
+     * so a client can show the member's present name rather than the snapshot typed into the
+     * comment. A uid that no longer resolves is left out (the client falls back to the snapshot).
+     */
+    private static Map< String, String > mentionNames( final List< CommentThread > threads,
+                                                       final PublicIdentity identity ) {
+        final Map< String, String > names = new LinkedHashMap<>();
+        final Set< String > seen = new LinkedHashSet<>();
+        for ( final CommentThread t : threads ) {
+            for ( final Comment c : t.comments() ) {
+                for ( final String uid : mentionUserIds( c.body() ) ) {
+                    if ( !seen.add( uid ) ) continue;
+                    identity.loginForUserId( uid )
+                            .ifPresent( login -> names.put( uid, identity.displayName( login ) ) );
+                }
+            }
+        }
+        return names;
+    }
+
+    /** Distinct uids named by {@code @[..](user:<uid>)} tokens in {@code body}. */
+    private static Set< String > mentionUserIds( final String body ) {
+        final Set< String > uids = new LinkedHashSet<>();
+        if ( body == null || body.isEmpty() ) return uids;
+        final Matcher m = MENTION_TOKEN.matcher( body );
+        while ( m.find() ) uids.add( m.group( 2 ) );
+        return uids;
+    }
+
+    /** Display name for a stored principal; {@code null} when the field is unset (e.g. {@code resolvedBy} on an open thread). */
+    private static String shownName( final PublicIdentity identity, final String storedPrincipal ) {
+        return storedPrincipal == null ? null : identity.displayName( storedPrincipal );
+    }
+
+    /** The member's uid, or {@code null} for a stored principal that is not a member (legacy name, agent label). */
+    private static String uidOf( final PublicIdentity identity, final String storedPrincipal ) {
+        if ( storedPrincipal == null || storedPrincipal.isBlank() ) return null;
+        return identity.userIdForLogin( storedPrincipal ).orElse( null );
+    }
+
+    private static Map< String, Object > threadToMap( final CommentThread t, final PublicIdentity identity ) {
         final Map< String, Object > m = new LinkedHashMap<>();
         m.put( "id", t.id().toString() );
         m.put( "status", t.status() );
@@ -341,21 +408,24 @@
         anchor.put( "prefix", t.anchor().prefix() );
         anchor.put( "suffix", t.anchor().suffix() );
         m.put( "anchor", anchor );
-        m.put( "createdBy", t.createdBy() );
+        m.put( "createdBy", shownName( identity, t.createdBy() ) );
+        m.put( "createdById", uidOf( identity, t.createdBy() ) );
         m.put( "createdAt", t.createdAt() == null ? null : t.createdAt().toString() );
-        m.put( "resolvedBy", t.resolvedBy() );
+        m.put( "resolvedBy", shownName( identity, t.resolvedBy() ) );
+        m.put( "resolvedById", uidOf( identity, t.resolvedBy() ) );
         m.put( "resolvedAt", t.resolvedAt() == null ? null : t.resolvedAt().toString() );
         final List< Map< String, Object > > comments = new ArrayList<>();
-        for ( final Comment c : t.comments() ) comments.add( commentToMap( c ) );
+        for ( final Comment c : t.comments() ) comments.add( commentToMap( c, identity ) );
         m.put( "comments", comments );
         return m;
     }
 
-    private static Map< String, Object > commentToMap( final Comment c ) {
+    private static Map< String, Object > commentToMap( final Comment c, final PublicIdentity identity ) {
         final Map< String, Object > m = new LinkedHashMap<>();
         m.put( "id", c.id().toString() );
         m.put( "threadId", c.threadId().toString() );
-        m.put( "author", c.author() );
+        m.put( "author", shownName( identity, c.author() ) );
+        m.put( "authorId", uidOf( identity, c.author() ) );
         m.put( "body", c.body() );
         m.put( "createdAt", c.createdAt() == null ? null : c.createdAt().toString() );
         m.put( "editedAt", c.editedAt() == null ? null : c.editedAt().toString() );
```

```diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java
@@ -259,11 +259,16 @@
         if ( session.isAuthenticated() ) {
             result.put( "username", session.getUserPrincipal().getName() );
             result.put( "loginPrincipal", session.getLoginPrincipal().getName() );
+            // The caller's own opaque uid. Clients compare it with the authorId on shared content
+            // (comments, ...) to recognise "mine" without any login name being published.
+            result.put( "userId", getSubsystems().auth().publicIdentity()
+                    .userIdForLogin( session.getLoginPrincipal().getName() ).orElse( null ) );
             result.put( "mustChangePassword",
                     PasswordChangeGate.mustChangePassword( engine, request, session.getLoginPrincipal().getName() ) );
         } else {
             result.put( "username", "anonymous" );
             result.put( "loginPrincipal", session.getLoginPrincipal().getName() );
+            result.put( "userId", null );
         }
 
         final List< String > roles = Arrays.stream( session.getRoles() )
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -B -o test -pl wikantik-rest -Dtest='CommentThreadResourceTest,AuthResourceTest,AuthResourceDeleteTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test-compile -pl wikantik-rest       # sources and test sources both compile
```

Expected: `BUILD SUCCESS`, 0 failures (in the scratch run `CommentThreadResourceTest` = 49 tests, `AuthResourceTest` = 61).

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/CommentThreadResourceTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AuthResourceTest.java
git commit -m "feat(rest): comment threads return display names + uids and a mentions map; /api/auth/user returns userId

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Mention tokens — parse (`MentionExtractor`) and resolve (`MentionService`)

**Files:**
- Modify (rewrite whole file): `wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionExtractor.java` (pattern was lines 36-37; now `TOKEN` + `PATTERN`, `Mentions`, `extract`)
- Modify: `wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionService.java` — imports 32-38; class doc 40-43; fields/ctor 47-53; `directMentionsFor` 152-156
- Modify: `wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java:75-100` (`Deps` record + Javadoc)
- Modify: `wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java:78-85,111` (new `loginForUserId` default; `new MentionService(..)`)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:968-1016` (`initKnowledgeGraph`)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java` (swap Task 13's private parser for `extract`)
- Test: `wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionExtractorTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionServiceTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactoryTest.java`

**Interfaces:**
- Consumes (Task 5): `AuthSubsystem.Services#publicIdentity()` and `PublicIdentity#loginForUserId(String)` — used only in the `WikiEngine` closure.
- Produces:
  - `MentionExtractor.Mentions( Set<String> userIds, Set<String> legacyLogins )` (immutable; `Mentions.NONE`, `isEmpty()`), and
    `static MentionExtractor.Mentions extract( String body )`. Token regex (the contract's): `@\[([^\]\n]{1,64})\]\(user:([A-Za-z0-9-]{1,64})\)`.
    `parse(String)` (legacy `@login` only) and `resolve(..)` keep their signatures and behaviour; `parse` does **not** see tokens.
  - `new MentionService( DataSource, Predicate<String> userExists, Function<String, Optional<String>> loginForUserId )`; the 2-argument
    constructor stays (tokens ignored) so every existing caller and test compiles unchanged.
  - `PersistenceSubsystem.Deps` gains a 5th component `Function<String, Optional<String>> loginForUserIdLookup` (null → tokens ignored); the
    4-argument and 2-argument constructors stay. **Design note:** the contract says "resolves userIds → logins through `PublicIdentity.loginForUserId`";
    that is exactly what is wired (`auth.publicIdentity().loginForUserId( uid )` in `WikiEngine`), but through a `Function` seam so
    `MentionService` needs no dependency on the auth module and is testable with a lambda. Same observable behaviour.

Behaviour decisions, all pinned by tests: a uid that resolves to no member is ignored; a failing lookup is logged at WARN with the uid and
treated as unknown (one bad lookup must not abort the comment write); a token and a legacy `@login` for the same member produce **one** row;
the owner-mention rules are unchanged (the owner is skipped when already mentioned directly — by token or by login — and still gets the
owner row when only *other* members are mentioned); an edit diffs by **login set**, so renaming a member between the original and the edited
text is "no change" and preserves `read_at`; tokens inside backticks count (comment bodies are plain text, not Markdown); `@` inside a
token's display snapshot is not a legacy mention; an email-shaped login — impossible to mention with `@login` because the `@` needs a
non-word character before it — works through a token.

- [ ] **Step 1: Write the failing tests**

```diff
--- a/wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionExtractorTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionExtractorTest.java
@@ -76,6 +76,105 @@
         assertEquals( Set.of( "bob" ), got );
     }
 
+    // ---- extract(): tokens + legacy ------------------------------------------------------
+
+    @Test
+    void extract_empty_or_null_body_has_no_mentions() {
+        assertTrue( MentionExtractor.extract( null ).isEmpty() );
+        assertTrue( MentionExtractor.extract( "" ).isEmpty() );
+        assertTrue( MentionExtractor.extract( "no mentions here" ).isEmpty() );
+    }
+
+    @Test
+    void extract_reads_the_uid_from_a_token() {
+        final MentionExtractor.Mentions m = MentionExtractor.extract( "hi @[Alice A](user:u-alice) please look" );
+        assertEquals( Set.of( "u-alice" ), m.userIds() );
+        assertTrue( m.legacyLogins().isEmpty() );
+    }
+
+    @Test
+    void extract_finds_several_distinct_tokens_and_dedupes_repeats() {
+        final MentionExtractor.Mentions m = MentionExtractor.extract(
+                "@[A](user:1) and @[B](user:2) and @[A again](user:1)@[C](user:3)" );
+        assertEquals( Set.of( "1", "2", "3" ), m.userIds() );
+    }
+
+    @Test
+    void extract_accepts_every_uid_shape_in_the_users_table() {
+        // UUIDs, the legacy negative-number uids seeded by V002, and slug uids like the agents account.
+        final MentionExtractor.Mentions m = MentionExtractor.extract(
+                "@[U](user:7f3c2d1e-0a4b-4c5d-8e9f-001122334455) @[Admin](user:-6852820166199419346) "
+                + "@[Agents](user:agents-service-account)" );
+        assertEquals( Set.of( "7f3c2d1e-0a4b-4c5d-8e9f-001122334455", "-6852820166199419346",
+                "agents-service-account" ), m.userIds() );
+    }
+
+    @Test
+    void extract_accepts_the_64_character_bounds_and_rejects_65() {
+        final String name64 = "n".repeat( 64 );
+        final String uid64 = "u".repeat( 64 );
+        assertEquals( Set.of( uid64 ),
+                MentionExtractor.extract( "@[" + name64 + "](user:" + uid64 + ")" ).userIds() );
+        assertTrue( MentionExtractor.extract( "@[" + "n".repeat( 65 ) + "](user:u1)" ).userIds().isEmpty(),
+                "a 65-character display name is not a token" );
+        assertTrue( MentionExtractor.extract( "@[Alice](user:" + "u".repeat( 65 ) + ")" ).userIds().isEmpty(),
+                "a 65-character uid is not a token" );
+    }
+
+    @Test
+    void extract_ignores_malformed_tokens() {
+        for ( final String bad : new String[] {
+                "@[](user:u1)",                  // empty display text
+                "@[Alice](user:)",               // empty uid
+                "@[Alice](user:bad uid)",        // space in uid
+                "@[Alice](user:u_1)",            // underscore is not a uid character
+                "@[Alice](user:u1",              // unterminated
+                "@[Alice](usr:u1)",              // wrong scheme
+                "@[Alice] (user:u1)",            // gap between ] and (
+                "@[Ali\nce](user:u1)",           // newline inside the display text
+                "@Alice(user:u1)",               // no brackets
+                "[Alice](user:u1)" } ) {         // no @
+            assertTrue( MentionExtractor.extract( bad ).userIds().isEmpty(), "must not parse: " + bad );
+        }
+    }
+
+    @Test
+    void extract_display_text_may_hold_parentheses_colons_and_unicode() {
+        assertEquals( Set.of( "u1" ), MentionExtractor.extract( "@[Zo\u00eb (Ng): QA](user:u1)" ).userIds() );
+    }
+
+    @Test
+    void extract_counts_a_token_inside_backticks_because_comments_are_plain_text() {
+        assertEquals( Set.of( "u1" ), MentionExtractor.extract( "see `@[Alice](user:u1)` here" ).userIds() );
+    }
+
+    @Test
+    void extract_still_reads_legacy_at_logins() {
+        final MentionExtractor.Mentions m = MentionExtractor.extract( "ping @bob, and @[Alice](user:u1)" );
+        assertEquals( Set.of( "u1" ), m.userIds() );
+        assertEquals( Set.of( "bob" ), m.legacyLogins() );
+    }
+
+    @Test
+    void extract_does_not_read_an_at_inside_a_token_snapshot_as_a_legacy_login() {
+        final MentionExtractor.Mentions m = MentionExtractor.extract( "x @[Bob @carol](user:u2) y" );
+        assertEquals( Set.of( "u2" ), m.userIds() );
+        assertTrue( m.legacyLogins().isEmpty(), "@carol sits inside the snapshot, not in the text" );
+    }
+
+    @Test
+    void extract_an_email_login_is_reachable_only_through_a_token() {
+        // alice@example.com can never match the legacy form (the '@' has a word-char prefix)...
+        assertTrue( MentionExtractor.extract( "cc alice@example.com" ).isEmpty() );
+        // ...but the same member is addressable by uid.
+        assertEquals( Set.of( "u-email" ), MentionExtractor.extract( "cc @[Alice A](user:u-email)" ).userIds() );
+    }
+
+    @Test
+    void parse_does_not_recognise_tokens() {
+        assertTrue( MentionExtractor.parse( "@[Alice](user:u1)" ).isEmpty() );
+    }
+
     @Test
     void resolve_filters_to_existing_logins_only() {
         final Set< String > tokens = Set.of( "alice", "ghost", "bob" );
```

```diff
--- a/wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionServiceTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionServiceTest.java
@@ -27,8 +27,10 @@
 import javax.sql.DataSource;
 import java.sql.Connection;
 import java.sql.Statement;
+import java.util.HashMap;
 import java.util.HashSet;
 import java.util.List;
+import java.util.Map;
 import java.util.Optional;
 import java.util.Set;
 import java.util.UUID;
@@ -40,6 +42,7 @@
 
     private DataSource ds;
     private Set< String > users;
+    private Map< String, String > loginByUid;
     private MentionService svc;
     private UUID threadId;
 
@@ -48,7 +51,12 @@
         this.ds = PostgresTestDb.createDataSource();
         PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
         this.users = new HashSet<>( List.of( "alice", "bob", "carol", "admin" ) );
-        this.svc = new MentionService( ds, users::contains );
+        this.loginByUid = new HashMap<>( Map.of(
+                "u-alice", "alice",
+                "u-bob", "bob",
+                "u-carol", "carol",
+                "u-email", "leak-probe@example.test" ) );
+        this.svc = new MentionService( ds, users::contains, uid -> Optional.ofNullable( loginByUid.get( uid ) ) );
         this.threadId = seedThread();
     }
 
@@ -182,6 +190,150 @@
         assertEquals( 1, svc.findByComment( cid ).size() );
     }
 
+    // ---- @[Display Name](user:<uid>) tokens -------------------------------------------------
+
+    @Test
+    void recordCreate_resolves_a_token_uid_to_the_members_login() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "hello @[Bobby](user:u-bob)", Optional.empty() );
+        }
+        final List< Mention > rows = svc.findByComment( cid );
+        assertEquals( 1, rows.size() );
+        assertEquals( "bob", rows.get( 0 ).mentionedLogin(), "rows stay keyed by login, never by the snapshot text" );
+        assertEquals( "alice", rows.get( 0 ).mentioningLogin() );
+        assertFalse( rows.get( 0 ).isOwnerMention() );
+    }
+
+    @Test
+    void a_member_whose_login_is_an_email_is_mentionable_through_a_token() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordReply( c, cid, "alice", "cc @[Leak Probe](user:u-email)" );
+        }
+        final List< Mention > rows = svc.findByComment( cid );
+        assertEquals( 1, rows.size() );
+        assertEquals( "leak-probe@example.test", rows.get( 0 ).mentionedLogin() );
+        assertEquals( 1, svc.unreadCount( "leak-probe@example.test" ) );
+    }
+
+    @Test
+    void an_unknown_uid_is_ignored() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "hello @[Ghost](user:u-nope)", Optional.empty() );
+        }
+        assertTrue( svc.findByComment( cid ).isEmpty() );
+    }
+
+    @Test
+    void a_token_and_a_legacy_login_for_the_same_member_make_one_row() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "@[Bobby](user:u-bob) and again @bob", Optional.empty() );
+        }
+        assertEquals( 1, svc.findByComment( cid ).size() );
+    }
+
+    @Test
+    void a_legacy_at_login_still_works_next_to_a_token() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "@[Bobby](user:u-bob) and @carol", Optional.empty() );
+        }
+        final Set< String > mentioned = new HashSet<>();
+        svc.findByComment( cid ).forEach( m -> mentioned.add( m.mentionedLogin() ) );
+        assertEquals( Set.of( "bob", "carol" ), mentioned );
+    }
+
+    @Test
+    void owner_mention_is_unchanged_and_skipped_when_the_owner_is_mentioned_by_token() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "@[Carol](user:u-carol) ping", Optional.of( "carol" ) );
+        }
+        final List< Mention > rows = svc.findByComment( cid );
+        assertEquals( 1, rows.size() );
+        assertEquals( "carol", rows.get( 0 ).mentionedLogin() );
+        assertFalse( rows.get( 0 ).isOwnerMention(), "the direct token mention wins; no separate owner row" );
+    }
+
+    @Test
+    void owner_mention_is_still_written_when_the_body_has_only_unrelated_tokens() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "fyi @[Bobby](user:u-bob)", Optional.of( "carol" ) );
+        }
+        final List< Mention > rows = svc.findByComment( cid );
+        assertEquals( 2, rows.size() );
+        assertTrue( rows.stream().anyMatch( m -> m.mentionedLogin().equals( "carol" ) && m.isOwnerMention() ) );
+    }
+
+    @Test
+    void recordEdit_diffs_token_mentions_and_preserves_read_state() throws Exception {
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "@[Bobby](user:u-bob) @[Carol](user:u-carol)", Optional.empty() );
+        }
+        final Mention bob = svc.findByComment( cid ).stream()
+                .filter( m -> m.mentionedLogin().equals( "bob" ) ).findFirst().orElseThrow();
+        svc.markRead( bob.id(), "bob" );
+
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordEdit( c, cid, "alice",
+                    "@[Bobby](user:u-bob) @[Carol](user:u-carol)", "@[Bobby](user:u-bob) only" );
+        }
+        final List< Mention > rows = svc.findByComment( cid );
+        assertEquals( 1, rows.size(), "carol dropped, bob remains" );
+        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
+        assertNotNull( rows.get( 0 ).readAt(), "bob's read_at preserved across the edit" );
+    }
+
+    @Test
+    void a_member_renamed_between_write_and_edit_is_still_the_same_mention() throws Exception {
+        // The snapshot text differs between the old and the new body; the uid is the key, so the
+        // edit must see "no change" rather than "dropped one mention, added another".
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordCreate( c, cid, "alice", "@[Old Name](user:u-bob)", Optional.empty() );
+        }
+        final Mention before = svc.findByComment( cid ).get( 0 );
+        svc.markRead( before.id(), "bob" );
+        try ( Connection c = ds.getConnection() ) {
+            svc.recordEdit( c, cid, "alice", "@[Old Name](user:u-bob)", "@[New Name](user:u-bob) thanks" );
+        }
+        final List< Mention > rows = svc.findByComment( cid );
+        assertEquals( 1, rows.size() );
+        assertEquals( before.id(), rows.get( 0 ).id(), "the row survives untouched" );
+        assertNotNull( rows.get( 0 ).readAt() );
+    }
+
+    @Test
+    void a_failing_uid_lookup_is_treated_as_unknown_and_does_not_abort_the_write() throws Exception {
+        final MentionService flaky = new MentionService( ds, users::contains, uid -> {
+            throw new IllegalStateException( "identity cache exploded" );
+        } );
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            flaky.recordCreate( c, cid, "alice", "@[Bobby](user:u-bob) and @carol", Optional.empty() );
+        }
+        final List< Mention > rows = flaky.findByComment( cid );
+        assertEquals( 1, rows.size(), "the legacy @carol is still recorded" );
+        assertEquals( "carol", rows.get( 0 ).mentionedLogin() );
+    }
+
+    @Test
+    void the_two_argument_constructor_ignores_tokens_but_keeps_legacy_logins() throws Exception {
+        final MentionService legacyOnly = new MentionService( ds, users::contains );
+        final UUID cid = newComment();
+        try ( Connection c = ds.getConnection() ) {
+            legacyOnly.recordCreate( c, cid, "alice", "@[Bobby](user:u-bob) and @carol", Optional.empty() );
+        }
+        final List< Mention > rows = legacyOnly.findByComment( cid );
+        assertEquals( 1, rows.size() );
+        assertEquals( "carol", rows.get( 0 ).mentionedLogin() );
+    }
+
     @Test
     void recordCreate_self_mention_in_body_does_NOT_duplicate_when_owner_equals_author() throws Exception {
         // Author mentions self on own page → ONE direct row (the self-mention),
```

```diff
--- a/wikantik-main/src/test/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactoryTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactoryTest.java
@@ -24,10 +24,16 @@
 import org.junit.jupiter.api.Test;
 
 import javax.sql.DataSource;
+import java.sql.Connection;
+import java.sql.Statement;
+import java.util.Optional;
 import java.util.Properties;
+import java.util.UUID;
 
+import static org.junit.jupiter.api.Assertions.assertEquals;
 import static org.junit.jupiter.api.Assertions.assertNotNull;
 import static org.junit.jupiter.api.Assertions.assertThrows;
+import static org.junit.jupiter.api.Assertions.assertTrue;
 
 /**
  * Phase 3 subsystem-isolation test for {@link PersistenceSubsystemFactory}.
@@ -70,6 +76,57 @@
     }
 
     @Test
+    void mentionServiceResolvesTokenUidsThroughTheSuppliedLookup() throws Exception {
+        final DataSource dataSource = PostgresTestDb.createDataSource();
+        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
+        final PersistenceSubsystem.Services services = PersistenceSubsystemFactory.create(
+            new PersistenceSubsystem.Deps( dataSource, new DefaultWikiProperties( new Properties() ),
+                login -> false, canonicalId -> Optional.empty(),
+                uid -> "u-bob".equals( uid ) ? Optional.of( "bob" ) : Optional.empty() ) );
+        final UUID commentId = seedComment( dataSource );
+
+        try ( Connection c = dataSource.getConnection() ) {
+            services.mentions().recordReply( c, commentId, "alice", "cc @[Bobby](user:u-bob) @[Ghost](user:u-nope)" );
+        }
+
+        final var rows = services.mentions().findByComment( commentId );
+        assertEquals( 1, rows.size(), "only the resolvable uid is recorded" );
+        assertEquals( "bob", rows.get( 0 ).mentionedLogin() );
+    }
+
+    @Test
+    void mentionServiceIgnoresTokensWhenNoLookupIsSupplied() throws Exception {
+        final DataSource dataSource = PostgresTestDb.createDataSource();
+        PostgresTestDb.truncate( "comment_mentions", "comments", "comment_threads" );
+        final PersistenceSubsystem.Services services = PersistenceSubsystemFactory.create(
+            new PersistenceSubsystem.Deps( dataSource, new DefaultWikiProperties( new Properties() ),
+                login -> "carol".equals( login ), canonicalId -> Optional.empty() ) );
+        final UUID commentId = seedComment( dataSource );
+
+        try ( Connection c = dataSource.getConnection() ) {
+            services.mentions().recordReply( c, commentId, "alice", "@[Bobby](user:u-bob) and @carol" );
+        }
+
+        final var rows = services.mentions().findByComment( commentId );
+        assertEquals( 1, rows.size() );
+        assertEquals( "carol", rows.get( 0 ).mentionedLogin() );
+        assertTrue( rows.stream().noneMatch( m -> "bob".equals( m.mentionedLogin() ) ) );
+    }
+
+    /** comment_mentions.comment_id is FK'd to comments(id), which needs a thread. */
+    private static UUID seedComment( final DataSource dataSource ) throws Exception {
+        final UUID threadId = UUID.randomUUID();
+        final UUID commentId = UUID.randomUUID();
+        try ( Connection c = dataSource.getConnection(); Statement s = c.createStatement() ) {
+            s.executeUpdate( "INSERT INTO comment_threads (id, canonical_id, anchor_exact, status, created_by) "
+                    + "VALUES ('" + threadId + "', 'CID-PSF', 'seed', 'open', 'alice')" );
+            s.executeUpdate( "INSERT INTO comments (id, thread_id, author, body) VALUES ('" + commentId + "', '"
+                    + threadId + "', 'alice', 'seed')" );
+        }
+        return commentId;
+    }
+
+    @Test
     void createRejectsMissingDeps() {
         assertThrows( NullPointerException.class, () -> PersistenceSubsystemFactory.create( null ) );
         assertThrows( NullPointerException.class, () -> PersistenceSubsystemFactory.create(
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-main -Dtest='MentionExtractorTest,MentionServiceTest,PersistenceSubsystemFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `COMPILATION ERROR` — `cannot find symbol: method extract(String)` / `class MentionExtractor.Mentions`, `constructor MentionService in class
MentionService cannot be applied to given types` (3 args), `constructor Deps in record Deps cannot be applied to given types` (5 args).

- [ ] **Step 3: Implement**

`MentionExtractor` (whole file; the license header is unchanged):

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */

package com.wikantik.comments.mentions;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses mentions out of a comment body.
 *
 *  <p>Two forms are recognised:</p>
 *  <ul>
 *    <li><b>Token</b> — {@code @[Display Name](user:<uid>)}, written by the mention picker.
 *        The display text is a snapshot taken when the comment was written; the {@code uid}
 *        ({@code users.uid}) is the key, and is what {@link #extract} returns.</li>
 *    <li><b>Legacy</b> — {@code @<login>}. The regex requires a non-word character before the
 *        {@code @} (so {@code alice@example.com} doesn't mention {@code alice}). A login that is an
 *        email address can never match this form; it is mentionable only through a token.</li>
 *  </ul>
 *  Comment bodies are plain text, not Markdown, so a token inside backticks still counts. */
public final class MentionExtractor {

    // @[            — literal opener
    // ([^\]\n]{1,64}) — display-name snapshot: 1-64 chars, no ']' and no newline
    // ](user:       — literal separator
    // ([A-Za-z0-9-]{1,64}) — users.uid (UUIDs, legacy negative numbers and slugs like
    //                 "agents-service-account" all fit)
    // )             — literal closer
    private static final Pattern TOKEN =
            Pattern.compile( "@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)" );

    // (?<=^|\W)  — preceded by start-of-string or a non-word character
    // @          — literal at-sign
    // ([A-Za-z0-9._-]+) — captured login (allows letters, digits, '.', '_', '-')
    private static final Pattern PATTERN =
            Pattern.compile( "(?<=^|\\W)@([A-Za-z0-9._-]+)" );

    private MentionExtractor() {}

    /** What a comment body mentions: {@code userIds} from tokens, {@code legacyLogins} from
     *  {@code @login} text. Neither is checked against the user database. */
    public record Mentions( Set< String > userIds, Set< String > legacyLogins ) {

        /** No mentions at all. */
        public static final Mentions NONE = new Mentions( Set.of(), Set.of() );

        public Mentions {
            userIds = Set.copyOf( userIds );
            legacyLogins = Set.copyOf( legacyLogins );
        }

        public boolean isEmpty() {
            return userIds.isEmpty() && legacyLogins.isEmpty();
        }
    }

    /** Every mention in {@code body}: token uids and legacy {@code @login} names. The legacy scan
     *  runs on the body with the tokens removed, so a snapshot such as {@code @[Bob @carol](user:u2)}
     *  does not mention {@code carol}. */
    public static Mentions extract( final String body ) {
        if ( body == null || body.isEmpty() ) return Mentions.NONE;
        final Set< String > userIds = new LinkedHashSet<>();
        final Matcher tokens = TOKEN.matcher( body );
        while ( tokens.find() ) userIds.add( tokens.group( 2 ) );
        final String withoutTokens = userIds.isEmpty() ? body : TOKEN.matcher( body ).replaceAll( " " );
        return new Mentions( userIds, parse( withoutTokens ) );
    }

    /** Distinct candidate logins from legacy {@code @login} text in {@code body} (tokens are not
     *  recognised here — use {@link #extract}). */
    public static Set< String > parse( final String body ) {
        if ( body == null || body.isEmpty() ) return Set.of();
        final Set< String > out = new HashSet<>();
        final Matcher m = PATTERN.matcher( body );
        while ( m.find() ) {
            String login = m.group( 1 );
            // Strip trailing punctuation (.!?,;: etc)
            login = login.replaceAll( "[.!?,;:]+$", "" );
            if ( !login.isEmpty() ) out.add( login );
        }
        return out;
    }

    /** Intersection of {@code candidates} with users that the predicate
     *  considers existing. */
    public static Set< String > resolve( final Set< String > candidates,
                                         final Predicate< String > userExists ) {
        final Set< String > out = new HashSet<>();
        for ( final String c : candidates ) if ( userExists.test( c ) ) out.add( c );
        return out;
    }
}
```

`MentionService` — a second constructor, and `directMentionsFor` resolves tokens through it and legacy logins through `userExists`:

```diff
--- a/wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionService.java
+++ b/wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionService.java
@@ -35,21 +35,40 @@
 import java.util.Optional;
 import java.util.Set;
 import java.util.UUID;
+import java.util.function.Function;
 import java.util.function.Predicate;
 
 /** Writes/reads {@code comment_mentions}. Write methods accept a Connection so
  *  they can run inside the caller's transaction (CommentStore opens one for
- *  thread creation; we participate in it). */
+ *  thread creation; we participate in it).
+ *
+ *  <p>Rows are keyed by <em>login</em>. A comment names a member either by a
+ *  {@code @[Display Name](user:<uid>)} token — the uid is resolved to the member's login
+ *  through {@code loginForUserId}, so a member whose login is an email address is mentionable
+ *  — or by a legacy {@code @login}, accepted when the login exists. */
 public class MentionService {
 
     private static final Logger LOG = LogManager.getLogger( MentionService.class );
 
     private final Jdbc jdbc;
     private final Predicate< String > userExists;
+    private final Function< String, Optional< String > > loginForUserId;
 
+    /** Legacy {@code @login} mentions only: every {@code user:<uid>} token is ignored. */
     public MentionService( final DataSource ds, final Predicate< String > userExists ) {
+        this( ds, userExists, uid -> Optional.empty() );
+    }
+
+    /**
+     * @param userExists     does a member with this exact login exist (legacy {@code @login} text)
+     * @param loginForUserId {@code users.uid} to that member's login, empty when the uid is unknown;
+     *                       production wiring passes {@code PublicIdentity::loginForUserId}
+     */
+    public MentionService( final DataSource ds, final Predicate< String > userExists,
+                           final Function< String, Optional< String > > loginForUserId ) {
         this.jdbc = new Jdbc( ds );
         this.userExists = userExists;
+        this.loginForUserId = loginForUserId;
     }
 
     /** First comment of a thread. Writes direct mentions (including self-mentions);
@@ -146,13 +165,29 @@
         }
     }
 
-    /** Resolved (existing-user-only) direct mentions in {@code body}.
+    /** Logins of the existing members directly mentioned in {@code body}, by token or by legacy
+     *  {@code @login}. A uid that no longer resolves is ignored.
      *  Self-mentions are kept — users can intentionally @-mention themselves
      *  (e.g., as a personal todo marker). */
     private Set< String > directMentionsFor( final String body, final String author ) {
-        final Set< String > tokens = MentionExtractor.parse( body );
-        final Set< String > resolved = MentionExtractor.resolve( tokens, userExists );
-        return resolved;
+        final MentionExtractor.Mentions found = MentionExtractor.extract( body );
+        final Set< String > logins = new HashSet<>( MentionExtractor.resolve( found.legacyLogins(), userExists ) );
+        for ( final String uid : found.userIds() ) {
+            loginFor( uid ).ifPresent( logins::add );
+        }
+        return logins;
+    }
+
+    /** The login behind {@code uid}; empty when unknown. A failing lookup is logged and treated as unknown
+     *  so one bad lookup cannot abort the comment write. */
+    private Optional< String > loginFor( final String uid ) {
+        try {
+            final Optional< String > login = loginForUserId.apply( uid );
+            return login == null ? Optional.empty() : login.filter( l -> !l.isBlank() );
+        } catch ( final RuntimeException e ) {
+            LOG.warn( "Mention uid '{}' could not be resolved to a login: {}", uid, e.getMessage(), e );
+            return Optional.empty();
+        }
     }
 
     /** Insert one row; no-op if the (comment_id, mentioned_login) pair already
```

Wiring: `Deps` gains the lookup, the factory defaults it to "unknown", and `WikiEngine` passes a closure that reads `this.authSubsystem`
lazily at call time (the Auth bundle is rebuilt on manager hot-swaps, so a captured reference could go stale — same reason the neighbouring
`userExistsLookup` is lazy):

```diff
--- a/wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java
+++ b/wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java
@@ -83,18 +83,31 @@
      * comments DAOs): the factory substitutes a default that returns
      * {@code false} / {@link Optional#empty()}, which causes
      * {@link PageOwnerService} to fall back to the admin owner.</p>
+     *
+     * <p>{@code loginForUserIdLookup} maps a {@code users.uid} (carried by an
+     * {@code @[Display Name](user:<uid>)} mention token) to that member's login; the engine passes
+     * {@code PublicIdentity::loginForUserId}. {@code null} makes {@link MentionService} ignore
+     * tokens and keep only legacy {@code @login} mentions.</p>
      */
     public record Deps(
         DataSource dataSource,
         WikiProperties properties,
         Predicate< String > userExistsLookup,
-        Function< String, Optional< String > > pageAuthorLookup
+        Function< String, Optional< String > > pageAuthorLookup,
+        Function< String, Optional< String > > loginForUserIdLookup
     ) {
 
+        /** Four-closure form kept for callers that predate mention tokens: tokens are ignored. */
+        public Deps( final DataSource dataSource, final WikiProperties properties,
+                     final Predicate< String > userExistsLookup,
+                     final Function< String, Optional< String > > pageAuthorLookup ) {
+            this( dataSource, properties, userExistsLookup, pageAuthorLookup, null );
+        }
+
         /** Convenience for callers (e.g. subsystem-isolation tests) that don't
          *  exercise comments DAOs. The factory will install no-op closures. */
         public Deps( final DataSource dataSource, final WikiProperties properties ) {
-            this( dataSource, properties, null, null );
+            this( dataSource, properties, null, null, null );
         }
     }
 
```

```diff
--- a/wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java
+++ b/wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java
@@ -81,6 +81,10 @@
         final Function< String, Optional< String > > authorResolver = deps.pageAuthorLookup() != null
                 ? deps.pageAuthorLookup()
                 : canonicalId -> Optional.empty();
+        // A mention token carries users.uid; without a resolver every token is ignored.
+        final Function< String, Optional< String > > loginForUserId = deps.loginForUserIdLookup() != null
+                ? deps.loginForUserIdLookup()
+                : uid -> Optional.empty();
 
         // Default owner for agent/unresolvable-author pages (e.g. the `agents`
         // service account). Stored at bootstrap only when it is itself a real
@@ -108,7 +112,7 @@
             new TrustedAuthorsDao( ds ),
             new CommentStore( ds ),
             new PageOwnerService( ds, userExists, authorResolver, defaultPageOwner ),
-            new MentionService( ds, userExists ),
+            new MentionService( ds, userExists, loginForUserId ),
             new MentionFeedDao( ds )
         );
     }
```

```diff
--- a/wikantik-main/src/main/java/com/wikantik/WikiEngine.java
+++ b/wikantik-main/src/main/java/com/wikantik/WikiEngine.java
@@ -968,12 +968,13 @@
             // Phase 3: Persistence subsystem.
             //
             // The anchored-comments DAOs (PageOwnerService, MentionService)
-            // need to ask "does this login exist?" and "who is the frontmatter
-            // author of this canonical_id?" — those answers live in the Auth
-            // subsystem (built before initKnowledgeGraph) and the structural
-            // index (built later in this method by PageGraphWiringHelper),
-            // respectively. We thread both through Deps as closures that
-            // capture `this` and resolve lazily at call time.
+            // need to ask "does this login exist?", "who is the frontmatter
+            // author of this canonical_id?" and "which login owns this
+            // users.uid?" — the first and third live in the Auth subsystem
+            // (built before initKnowledgeGraph), the second in the structural
+            // index (built later in this method by PageGraphWiringHelper).
+            // We thread all three through Deps as closures that capture `this`
+            // and resolve lazily at call time.
             final java.util.function.Predicate< String > userExistsLookup = login -> {
                 if ( login == null || login.isBlank() ) return false;
                 final com.wikantik.auth.subsystem.AuthSubsystem.Services auth = this.authSubsystem;
@@ -1011,9 +1012,17 @@
                 }
             };
 
+            // A mention token carries users.uid; the identity service turns it into the member's login.
+            // Resolved lazily (like the closures above) because the Auth bundle is rebuilt on manager swaps.
+            final java.util.function.Function< String, java.util.Optional< String > > loginForUserIdLookup = uid -> {
+                final com.wikantik.auth.subsystem.AuthSubsystem.Services auth = this.authSubsystem;
+                if ( auth == null || auth.publicIdentity() == null ) return java.util.Optional.empty();
+                return auth.publicIdentity().loginForUserId( uid );
+            };
+
             this.persistenceSubsystem = com.wikantik.persistence.subsystem.PersistenceSubsystemFactory.create(
                 new com.wikantik.persistence.subsystem.PersistenceSubsystem.Deps(
-                    ds, coreSubsystem.properties(), userExistsLookup, pageAuthorLookup ) );
+                    ds, coreSubsystem.properties(), userExistsLookup, pageAuthorLookup, loginForUserIdLookup ) );
 
             // Resolve the Lucene MoreLikeThis seam.
             HubOverviewService.LuceneMlt luceneMlt = null;
```

Finally delete Task 13's private parser so there is one token definition:

```diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java
@@ -27,6 +27,7 @@
 import com.wikantik.api.spi.Wiki;
 import com.wikantik.comments.CommentStore;
 import com.wikantik.comments.PageOwnerService;
+import com.wikantik.comments.mentions.MentionExtractor;
 import com.wikantik.comments.mentions.MentionService;
 import jakarta.servlet.ServletException;
 import jakarta.servlet.http.HttpServletRequest;
@@ -41,8 +42,6 @@
 import java.util.Optional;
 import java.util.Set;
 import java.util.UUID;
-import java.util.regex.Matcher;
-import java.util.regex.Pattern;
 
 /**
  * REST servlet for anchored comment threads. Mapped to {@code /api/comment-threads/*}.
@@ -62,10 +61,6 @@
 
     private static final long serialVersionUID = 1L;
 
-    // @[Display Name](user:<uid>) — keep in step with MentionExtractor's token pattern.
-    private static final Pattern MENTION_TOKEN =
-            Pattern.compile( "@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)" );
-
     protected CommentStore commentStore() {
         return getSubsystems().persistence().comments();
     }
@@ -369,7 +364,7 @@
         final Set< String > seen = new LinkedHashSet<>();
         for ( final CommentThread t : threads ) {
             for ( final Comment c : t.comments() ) {
-                for ( final String uid : mentionUserIds( c.body() ) ) {
+                for ( final String uid : MentionExtractor.extract( c.body() ).userIds() ) {
                     if ( !seen.add( uid ) ) continue;
                     identity.loginForUserId( uid )
                             .ifPresent( login -> names.put( uid, identity.displayName( login ) ) );
@@ -379,15 +374,6 @@
         return names;
     }
 
-    /** Distinct uids named by {@code @[..](user:<uid>)} tokens in {@code body}. */
-    private static Set< String > mentionUserIds( final String body ) {
-        final Set< String > uids = new LinkedHashSet<>();
-        if ( body == null || body.isEmpty() ) return uids;
-        final Matcher m = MENTION_TOKEN.matcher( body );
-        while ( m.find() ) uids.add( m.group( 2 ) );
-        return uids;
-    }
-
     /** Display name for a stored principal; {@code null} when the field is unset (e.g. {@code resolvedBy} on an open thread). */
     private static String shownName( final PublicIdentity identity, final String storedPrincipal ) {
         return storedPrincipal == null ? null : identity.displayName( storedPrincipal );
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -B -o test -pl wikantik-main -Dtest='MentionExtractorTest,MentionServiceTest,MentionServiceErrorBranchTest,PersistenceSubsystemFactoryTest,CommentStoreTest,CommentStoreTransactionTest,CommentStoreErrorBranchTest,KnowledgeSubsystemFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test-compile -pl wikantik-main        # record-signature change: catch any other Deps/MentionService caller
bin/agent-build.sh start c-main -- mvn -B -o install -pl wikantik-main -DskipTests && bin/agent-build.sh wait c-main 540
mvn -B -o test -pl wikantik-rest -Dtest='CommentThreadResourceTest' -Dsurefire.failIfNoSpecifiedTests=false   # Task 13's tests, now on MentionExtractor
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest
```

Expected: all green, PMD gate clean. In the scratch run: `MentionExtractorTest` 20, `MentionServiceTest` 21 (11 new), `PersistenceSubsystemFactoryTest` 4,
`CommentThreadResourceTest` 49. The `WikiEngine` closure has no unit seam (it needs a booted engine); it is exercised end-to-end by the
wire-level IT added in Task 15 Step 1 and by the leak-probe IT (Task 22) — do not skip those.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionExtractor.java \
        wikantik-main/src/main/java/com/wikantik/comments/mentions/MentionService.java \
        wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystem.java \
        wikantik-main/src/main/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactory.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java \
        wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionExtractorTest.java \
        wikantik-main/src/test/java/com/wikantik/comments/mentions/MentionServiceTest.java \
        wikantik-main/src/test/java/com/wikantik/persistence/subsystem/PersistenceSubsystemFactoryTest.java
git commit -m "feat(comments): @[Name](user:<uid>) mention tokens parsed and resolved to logins via PublicIdentity

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Mentionable-users endpoint and My-mentions feed use display names

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/MentionableUsersResource.java` — class Javadoc 41-60; auth-gate comment 82-83; row loop 105-114
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/MyMentionsResource.java` — import 21-25; class Javadoc 180-192; new `publicIdentity()` seam after `mentionService()` (~206-209); `list` 265-292 (rows 274-288); new private `canViewPage`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/MentionableUsersResourceTest.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/MyMentionsResourceTest.java`
- Test (wire-level): `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CommentThreadIT.java`

**Interfaces:**
- Consumes: Task 5 `PublicIdentity#displayName` / `AuthSubsystem.Services#publicIdentity()`; `RestServletBase#hasPagePermission( request, pageName, "view" )`
  (the existing silent capability query — no 403, no `access.denied` audit row); Tasks 13 + 14 for the IT only.
- Produces: `GET /api/users/mentionable` → `{ "users": [ { "id": "<users.uid>", "displayName": "<name>" } ] }` — exactly those two keys, matched on
  display-name substring only; `GET /api/me/mentions` → `mentionedBy` = display name, `snippet` absent when the recipient cannot view the page;
  `protected PublicIdentity MyMentionsResource#publicIdentity()` seam.

Decisions: **the login is never consulted** for matching (the old `login.startsWith( q )` let any signed-in member probe which logins exist:
`?q=zed` returned a row for `zed@example.test` — the new `a_login_is_never_matched_…` test reproduces that against the old code), and is
never used as a fallback label — an account with no usable display name or no uid is skipped, not shown by login. The row's `id`/`displayName`
come straight from the `UserProfile` already loaded for the match (the same row `PublicIdentity` reads), so no extra lookups per candidate. The
feed checks view permission **per row** with the page's resolved slug; a page that no longer resolves cannot be checked, so its snippet is
withheld too (the existing `pageName` fallback to the canonical id is unchanged). The feed row stays — the recipient still learns they were
mentioned and by whom — only the discussion text is withheld.

- [ ] **Step 1: Write the failing tests**

`MentionableUsersResourceTest`: fixtures now carry a uid that does *not* contain the login (so a leaked login is detectable) and a member whose
login is an email address with an unrelated display name; new tests — exact key set `{id, displayName}`, no login anywhere in the body, a login
fragment/prefix/email finds nobody while the display name finds the same member, blank-name/no-uid/all-null profiles are skipped without a
login fallback. `MyMentionsResourceTest`: `mentionedBy` is the display name (and no email login in the body); snippet present when viewable,
omitted when not (row kept), per-row decisions, withheld when the page no longer resolves; the seam delegates to `getSubsystems().auth()`.
`setUp` stubs `publicIdentity()` (identity default) and `hasPagePermission(.., "view")` → true so the pre-existing tests are unaffected.

```diff
--- a/wikantik-rest/src/test/java/com/wikantik/rest/MentionableUsersResourceTest.java
+++ b/wikantik-rest/src/test/java/com/wikantik/rest/MentionableUsersResourceTest.java
@@ -53,18 +53,22 @@
     void setUp() throws Exception {
         db = Mockito.mock( UserDatabase.class );
         final Map< String, UserProfile > byWikiName = new HashMap<>();
-        byWikiName.put( "alice",  profile( "alice",  "Alice Anderson",   false ) );
-        byWikiName.put( "alicia", profile( "alicia", "Alicia Aces",      false ) );
-        byWikiName.put( "bob",    profile( "bob",    "Bob Builder",      false ) );
-        byWikiName.put( "carol",  profile( "carol",  "Carol Carmichael", false ) );
-        byWikiName.put( "locked", profile( "locked", "Locked Account",   true  ) );
+        // uids deliberately do not contain the login, so a leaked login is detectable in the JSON.
+        byWikiName.put( "alice",  profile( "uid-0001", "alice",  "Alice Anderson",   false ) );
+        byWikiName.put( "alicia", profile( "uid-0002", "alicia", "Alicia Aces",      false ) );
+        byWikiName.put( "bob",    profile( "uid-0003", "bob",    "Bob Builder",      false ) );
+        byWikiName.put( "carol",  profile( "uid-0004", "carol",  "Carol Carmichael", false ) );
+        byWikiName.put( "locked", profile( "uid-0005", "locked", "Locked Account",   true  ) );
+        // A login that is an email address, with an unrelated display name.
+        byWikiName.put( "zed",    profile( "uid-0006", "zed@example.test", "Quentin Quill", false ) );
 
         final Principal[] wikiNames = new Principal[] {
                 new WikiPrincipal( "alice",  WikiPrincipal.WIKI_NAME ),
                 new WikiPrincipal( "alicia", WikiPrincipal.WIKI_NAME ),
                 new WikiPrincipal( "bob",    WikiPrincipal.WIKI_NAME ),
                 new WikiPrincipal( "carol",  WikiPrincipal.WIKI_NAME ),
-                new WikiPrincipal( "locked", WikiPrincipal.WIKI_NAME )
+                new WikiPrincipal( "locked", WikiPrincipal.WIKI_NAME ),
+                new WikiPrincipal( "zed",    WikiPrincipal.WIKI_NAME )
         };
         Mockito.when( db.getWikiNames() ).thenReturn( wikiNames );
         for ( final Map.Entry< String, UserProfile > e : byWikiName.entrySet() ) {
@@ -77,8 +81,10 @@
         Mockito.doReturn( true ).when( servlet ).isAuthenticated( Mockito.any() );
     }
 
-    private static UserProfile profile( final String login, final String full, final boolean locked ) {
+    private static UserProfile profile( final String uid, final String login, final String full,
+                                        final boolean locked ) {
         final UserProfile p = Mockito.mock( UserProfile.class );
+        Mockito.when( p.getUid() ).thenReturn( uid );
         Mockito.when( p.getLoginName() ).thenReturn( login );
         Mockito.when( p.getFullname() ).thenReturn( full );
         Mockito.when( p.isLocked() ).thenReturn( locked );
@@ -86,14 +92,53 @@
     }
 
     @Test
-    void authenticated_access_returns_users() throws Exception {
+    void authenticated_access_returns_id_and_display_name() throws Exception {
         final JsonObject res = invoke( "?q=ali" );
         assertFalse( res.has( "error" ), "signed-in access should succeed: " + res );
         assertTrue( res.has( "users" ) );
         assertTrue( res.getAsJsonArray( "users" ).size() >= 1 );
         final JsonObject first = res.getAsJsonArray( "users" ).get( 0 ).getAsJsonObject();
-        assertEquals( "alice", first.get( "loginName" ).getAsString() );
-        assertEquals( "Alice Anderson", first.get( "fullName" ).getAsString() );
+        assertEquals( "uid-0001", first.get( "id" ).getAsString() );
+        assertEquals( "Alice Anderson", first.get( "displayName" ).getAsString() );
+    }
+
+    @Test
+    void a_row_carries_exactly_id_and_displayName_no_login_and_no_full_name() throws Exception {
+        final JsonObject res = invoke( "" );
+        res.getAsJsonArray( "users" ).forEach( u ->
+                assertEquals( java.util.Set.of( "id", "displayName" ), u.getAsJsonObject().keySet() ) );
+    }
+
+    @Test
+    void no_login_name_appears_anywhere_in_the_response() throws Exception {
+        final String body = invoke( "" ).toString();
+        for ( final String login : new String[] { "\"alice\"", "\"alicia\"", "\"bob\"", "\"carol\"",
+                "zed@example.test", "example.test" } ) {
+            assertFalse( body.contains( login ), "login leaked (" + login + "): " + body );
+        }
+    }
+
+    @Test
+    void a_login_is_never_matched_so_a_member_cannot_probe_which_logins_exist() throws Exception {
+        // zed@example.test is a real login; its display name is "Quentin Quill".
+        assertEquals( 0, invoke( "?q=zed" ).getAsJsonArray( "users" ).size(), "login prefix must not match" );
+        assertEquals( 0, invoke( "?q=zed@example.test" ).getAsJsonArray( "users" ).size() );
+        assertEquals( 0, invoke( "?q=example" ).getAsJsonArray( "users" ).size() );
+        // ...while the display name finds the same member.
+        final JsonArray hit = invoke( "?q=quill" ).getAsJsonArray( "users" );
+        assertEquals( 1, hit.size() );
+        assertEquals( "uid-0006", hit.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
+    }
+
+    @Test
+    void a_login_fragment_that_is_in_no_display_name_finds_nobody() throws Exception {
+        // This member's login "mxyz" shares nothing with the display name "Dana Doe".
+        final UserProfile loginOnly = profile( "uid-0007", "mxyz", "Dana Doe", false );
+        Mockito.when( db.findByWikiName( "mxyz" ) ).thenReturn( loginOnly );
+        Mockito.when( db.getWikiNames() ).thenReturn( new Principal[] {
+                new WikiPrincipal( "mxyz", WikiPrincipal.WIKI_NAME ) } );
+        assertEquals( 0, invoke( "?q=mxy" ).getAsJsonArray( "users" ).size() );
+        assertEquals( 1, invoke( "?q=dana" ).getAsJsonArray( "users" ).size() );
     }
 
     @Test
@@ -112,23 +157,18 @@
     }
 
     @Test
-    void prefix_matches_login() throws Exception {
-        final JsonObject res = invoke( "?q=ali" );
+    void substring_matches_display_name_case_insensitive() throws Exception {
+        final JsonObject res = invoke( "?q=BUILDER" );
         final JsonArray users = res.getAsJsonArray( "users" );
-        // alice + alicia match by login prefix
-        assertTrue( users.size() >= 2 );
-        users.forEach( u -> {
-            final String login = u.getAsJsonObject().get( "loginName" ).getAsString();
-            assertTrue( login.startsWith( "ali" ) );
-        } );
+        assertEquals( 1, users.size() );
+        assertEquals( "uid-0003", users.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
+        assertEquals( "Bob Builder", users.get( 0 ).getAsJsonObject().get( "displayName" ).getAsString() );
     }
 
     @Test
-    void substring_matches_full_name_case_insensitive() throws Exception {
-        final JsonObject res = invoke( "?q=builder" );
-        final JsonArray users = res.getAsJsonArray( "users" );
-        assertEquals( 1, users.size() );
-        assertEquals( "bob", users.get( 0 ).getAsJsonObject().get( "loginName" ).getAsString() );
+    void a_display_name_prefix_finds_every_member_whose_name_contains_it() throws Exception {
+        final JsonArray users = invoke( "?q=ali" ).getAsJsonArray( "users" );
+        assertEquals( 2, users.size(), "Alice Anderson and Alicia Aces" );
     }
 
     @Test
@@ -166,7 +206,7 @@
         // alicia still matches; alice does not.
         final JsonArray users = res.getAsJsonArray( "users" );
         users.forEach( u -> assertFalse(
-                "alice".equals( u.getAsJsonObject().get( "loginName" ).getAsString() ),
+                "uid-0001".equals( u.getAsJsonObject().get( "id" ).getAsString() ),
                 "alice's vanished profile must not appear in results" ) );
     }
 
@@ -180,25 +220,31 @@
     }
 
     @Test
-    void profile_with_null_login_or_full_name_is_handled() throws Exception {
-        // Cover the `login == null ? ""` and `full == null ? ""` defensive branches.
-        // An empty-string match always succeeds when q is empty, so the row appears.
+    void a_member_with_no_display_name_or_no_uid_is_skipped_and_never_shown_by_login() throws Exception {
+        // Nothing safe to show (no display name) or to key a mention on (no uid): skipped, not a 500,
+        // and the login is not used as a fallback label.
+        final UserProfile blankName = profile( "uid-8", "blankname@example.test", "   ", false );
+        final UserProfile noUid = profile( null, "nouid@example.test", "No Uid", false );
         final UserProfile sparse = Mockito.mock( UserProfile.class );
         Mockito.when( sparse.getLoginName() ).thenReturn( null );
         Mockito.when( sparse.getFullname() ).thenReturn( null );
+        Mockito.when( sparse.getUid() ).thenReturn( null );
         Mockito.when( sparse.isLocked() ).thenReturn( false );
+        Mockito.when( db.findByWikiName( "blankname" ) ).thenReturn( blankName );
+        Mockito.when( db.findByWikiName( "nouid" ) ).thenReturn( noUid );
         Mockito.when( db.findByWikiName( "sparse" ) ).thenReturn( sparse );
-        // Add the wikiName to the enumeration set.
-        final Principal[] expanded = new Principal[] {
-                new WikiPrincipal( "alice",  WikiPrincipal.WIKI_NAME ),
-                new WikiPrincipal( "sparse", WikiPrincipal.WIKI_NAME )
-        };
-        Mockito.when( db.getWikiNames() ).thenReturn( expanded );
+        Mockito.when( db.getWikiNames() ).thenReturn( new Principal[] {
+                new WikiPrincipal( "alice",     WikiPrincipal.WIKI_NAME ),
+                new WikiPrincipal( "blankname", WikiPrincipal.WIKI_NAME ),
+                new WikiPrincipal( "nouid",     WikiPrincipal.WIKI_NAME ),
+                new WikiPrincipal( "sparse",    WikiPrincipal.WIKI_NAME ) } );
 
         final JsonObject res = invoke( "" );
-        assertFalse( res.has( "error" ), "null login/full name must not blow up: " + res );
-        // Both rows present (q is empty so everything matches).
-        assertTrue( res.getAsJsonArray( "users" ).size() >= 2 );
+        assertFalse( res.has( "error" ), "unusable profiles must not blow up: " + res );
+        final JsonArray users = res.getAsJsonArray( "users" );
+        assertEquals( 1, users.size(), "only alice is usable: " + res );
+        assertEquals( "uid-0001", users.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
+        assertFalse( res.toString().contains( "example.test" ), "no login fallback: " + res );
     }
 
     // ---- helpers ----
```

```diff
--- a/wikantik-rest/src/test/java/com/wikantik/rest/MyMentionsResourceTest.java
+++ b/wikantik-rest/src/test/java/com/wikantik/rest/MyMentionsResourceTest.java
@@ -22,7 +22,10 @@
 import com.google.gson.JsonArray;
 import com.google.gson.JsonObject;
 import com.wikantik.HttpMockFactory;
+import com.wikantik.WikiSubsystems;
 import com.wikantik.api.comments.MentionFeedItem;
+import com.wikantik.api.identity.PublicIdentity;
+import com.wikantik.auth.subsystem.AuthSubsystem;
 import com.wikantik.comments.mentions.MentionFeedDao;
 import com.wikantik.comments.mentions.MentionService;
 import jakarta.servlet.http.HttpServletRequest;
@@ -42,6 +45,7 @@
 import static org.junit.jupiter.api.Assertions.assertEquals;
 import static org.junit.jupiter.api.Assertions.assertFalse;
 import static org.junit.jupiter.api.Assertions.assertNotNull;
+import static org.junit.jupiter.api.Assertions.assertSame;
 import static org.junit.jupiter.api.Assertions.assertTrue;
 
 class MyMentionsResourceTest {
@@ -49,6 +53,7 @@
     private final Gson gson = new Gson();
     private MentionFeedDao feed;
     private MentionService svc;
+    private PublicIdentity identity;
     private MyMentionsResource servlet;
 
     @BeforeEach
@@ -62,6 +67,89 @@
         Mockito.doReturn( true ).when( servlet ).isAuthenticated( Mockito.any() );
         Mockito.doReturn( "alice" ).when( servlet ).currentUser( Mockito.any() );
         Mockito.doReturn( Optional.of( "FooPage" ) ).when( servlet ).resolveSlug( Mockito.anyString() );
+
+        // A stored principal is shown as itself unless a test says otherwise; every page is viewable.
+        identity = Mockito.mock( PublicIdentity.class );
+        Mockito.when( identity.displayName( Mockito.any() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
+        Mockito.doReturn( identity ).when( servlet ).publicIdentity();
+        Mockito.doReturn( true ).when( servlet ).hasPagePermission(
+                Mockito.any(), Mockito.anyString(), Mockito.anyString() );
+    }
+
+    // ---- display names + snippet visibility ----
+
+    private MentionFeedItem item( final String canonicalId, final String snippet, final String mentionedBy ) {
+        return new MentionFeedItem( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), canonicalId, null,
+                snippet, mentionedBy, false, Instant.parse( "2026-05-01T12:00:00Z" ), null );
+    }
+
+    private JsonArray feedOf( final MentionFeedItem... items ) throws Exception {
+        Mockito.when( feed.list( Mockito.eq( "alice" ), Mockito.any(), Mockito.anyInt(), Mockito.any() ) )
+                .thenReturn( List.of( items ) );
+        return invokeGet( null, null ).getAsJsonArray( "mentions" );
+    }
+
+    @Test
+    void mentionedBy_is_the_display_name_never_the_stored_login() throws Exception {
+        Mockito.when( identity.displayName( "leak-probe@example.test" ) ).thenReturn( "Leak Probe" );
+        final JsonArray mentions = feedOf( item( "CID-1", "hello", "leak-probe@example.test" ) );
+        assertEquals( "Leak Probe", mentions.get( 0 ).getAsJsonObject().get( "mentionedBy" ).getAsString() );
+        assertFalse( mentions.toString().contains( "example.test" ), "the login must not appear: " + mentions );
+    }
+
+    @Test
+    void snippet_is_included_when_the_recipient_can_view_the_page() throws Exception {
+        final JsonArray mentions = feedOf( item( "CID-1", "see this", "bob" ) );
+        assertEquals( "see this", mentions.get( 0 ).getAsJsonObject().get( "snippet" ).getAsString() );
+        Mockito.verify( servlet ).hasPagePermission( Mockito.any(), Mockito.eq( "FooPage" ), Mockito.eq( "view" ) );
+    }
+
+    @Test
+    void snippet_is_omitted_when_the_recipient_cannot_view_the_page() throws Exception {
+        Mockito.doReturn( false ).when( servlet ).hasPagePermission(
+                Mockito.any(), Mockito.eq( "FooPage" ), Mockito.eq( "view" ) );
+        final JsonArray mentions = feedOf( item( "CID-1", "a restricted discussion", "bob" ) );
+        final JsonObject m = mentions.get( 0 ).getAsJsonObject();
+        assertFalse( m.has( "snippet" ), "snippet must be omitted: " + m );
+        assertFalse( mentions.toString().contains( "restricted discussion" ), mentions.toString() );
+        // The row itself stays, so the recipient still sees that they were mentioned and by whom.
+        assertEquals( "bob", m.get( "mentionedBy" ).getAsString() );
+        assertEquals( "FooPage", m.get( "pageName" ).getAsString() );
+    }
+
+    @Test
+    void view_permission_is_checked_per_row() throws Exception {
+        Mockito.doReturn( Optional.of( "OpenPage" ) ).when( servlet ).resolveSlug( "CID-OPEN" );
+        Mockito.doReturn( Optional.of( "SecretPage" ) ).when( servlet ).resolveSlug( "CID-SECRET" );
+        Mockito.doReturn( false ).when( servlet ).hasPagePermission(
+                Mockito.any(), Mockito.eq( "SecretPage" ), Mockito.anyString() );
+        final JsonArray mentions = feedOf(
+                item( "CID-OPEN", "open words", "bob" ), item( "CID-SECRET", "secret words", "bob" ) );
+        assertEquals( "open words", mentions.get( 0 ).getAsJsonObject().get( "snippet" ).getAsString() );
+        assertFalse( mentions.get( 1 ).getAsJsonObject().has( "snippet" ) );
+    }
+
+    @Test
+    void snippet_is_withheld_when_the_page_no_longer_resolves() throws Exception {
+        Mockito.doReturn( Optional.empty() ).when( servlet ).resolveSlug( "CID-GONE" );
+        final JsonObject m = feedOf( item( "CID-GONE", "orphaned words", "bob" ) ).get( 0 ).getAsJsonObject();
+        assertFalse( m.has( "snippet" ), "an unresolvable page cannot be permission-checked: " + m );
+        assertEquals( "CID-GONE", m.get( "pageName" ).getAsString(), "existing fallback to the canonical id" );
+    }
+
+    @Test
+    void publicIdentityDelegatesToAuthSubsystem() {
+        final PublicIdentity expected = Mockito.mock( PublicIdentity.class );
+        final AuthSubsystem.Services auth = Mockito.mock( AuthSubsystem.Services.class );
+        Mockito.when( auth.publicIdentity() ).thenReturn( expected );
+        final MyMentionsResource plain = new MyMentionsResource() {
+            @Override protected WikiSubsystems getSubsystems() {
+                final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
+                Mockito.when( subs.auth() ).thenReturn( auth );
+                return subs;
+            }
+        };
+        assertSame( expected, plain.publicIdentity() );
     }
 
     // ---- 1, 2: auth gating ----
@@ -106,7 +194,7 @@
         assertEquals( "CID-123", m.get( "canonicalId" ).getAsString() );
         assertEquals( "FooPage", m.get( "pageName" ).getAsString() );
         assertEquals( "hello @alice", m.get( "snippet" ).getAsString() );
-        assertEquals( "bob", m.get( "mentionedBy" ).getAsString() );
+        assertEquals( "bob", m.get( "mentionedBy" ).getAsString(), "shown as-is while identity maps it to itself" );
         assertFalse( m.get( "isOwnerMention" ).getAsBoolean() );
         assertEquals( "2026-05-01T12:00:00Z", m.get( "mentionedAt" ).getAsString() );
         // Gson omits nulls by default; readAt is null in our fixture so either
```

The wire-level IT (new `@Order( 4 )` method; the legacy `@Alice` test above it is untouched and must keep passing — legacy `@login` still
works): Alice reads her own `userId` from `GET /api/auth/user`, janne mentions her with the stored token, and Alice's feed names janne by
display name, carries the snippet, and never contains `"janne"`. Alice is seeded by `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql`
(uid `it-seed-user-alice`, password `password`); janne's seeded display name is `Janne Jalkanen` (login `janne`).

```diff
--- a/wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CommentThreadIT.java
+++ b/wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CommentThreadIT.java
@@ -40,6 +40,8 @@
 import java.util.Map;
 
 import static org.junit.jupiter.api.Assertions.assertEquals;
+import static org.junit.jupiter.api.Assertions.assertFalse;
+import static org.junit.jupiter.api.Assertions.assertNotEquals;
 import static org.junit.jupiter.api.Assertions.assertNotNull;
 import static org.junit.jupiter.api.Assertions.assertTrue;
 
@@ -407,4 +409,78 @@
         assertTrue( count.body().matches( "(?s).*\"count\"\\s*:\\s*[1-9][0-9]*.*" ),
                 "unread-count should be a positive integer: " + count.body() );
     }
+    /**
+     * Wire-level check of the display-name mention flow (the legacy {@code @login} flow is covered by
+     * {@link #mention_in_comment_appears_in_my_mentions_feed()}).
+     *
+     * <ol>
+     *   <li>Alice signs in on her own client and reads her opaque uid from {@code GET /api/auth/user}
+     *       ({@code userId}) — the same value the mention picker receives as {@code id}.</li>
+     *   <li>janne comments with the stored token {@code @[Alice](user:<uid>)}.</li>
+     *   <li>Alice's feed holds that mention, names janne by <em>display name</em>, and never contains
+     *       janne's login — proving the server resolved the token's uid to a login for storage and the
+     *       stored author login back to a display name for the response.</li>
+     * </ol>
+     */
+    @Test
+    @Order( 4 )
+    @SuppressWarnings( "unchecked" )
+    void token_mention_reaches_the_mentioned_members_feed_under_display_names() throws Exception {
+        // (1) Alice's uid, read by Alice.
+        final HttpClient aliceClient = HttpClient.newBuilder()
+                .followRedirects( HttpClient.Redirect.NORMAL )
+                .cookieHandler( secureCookieOverHttp() )
+                .build();
+        final HttpResponse< String > aliceLogin = aliceClient.send(
+                HttpRequest.newBuilder()
+                        .uri( URI.create( baseUrl + "/api/auth/login" ) )
+                        .header( "Content-Type", "application/json" )
+                        .header( "Accept", "application/json" )
+                        .POST( HttpRequest.BodyPublishers.ofString(
+                                GSON.toJson( Map.of( "username", "Alice", "password", "password" ) ) ) )
+                        .build(),
+                HttpResponse.BodyHandlers.ofString() );
+        assertEquals( 200, aliceLogin.statusCode(), "Login as Alice should succeed: " + aliceLogin.body() );
+        final HttpResponse< String > aliceMe = aliceClient.send(
+                HttpRequest.newBuilder().uri( URI.create( baseUrl + "/api/auth/user" ) )
+                        .header( "Accept", "application/json" ).GET().build(),
+                HttpResponse.BodyHandlers.ofString() );
+        final String aliceUid = ( String ) parseJson( aliceMe.body() ).get( "userId" );
+        assertNotNull( aliceUid, "GET /api/auth/user must carry the caller's own userId: " + aliceMe.body() );
+        assertFalse( aliceUid.isBlank(), aliceMe.body() );
+
+        // (2) janne seeds a page and mentions Alice by token.
+        loginAsAdmin();
+        final String janneDisplayName = ( String ) parseJson( get( "/api/auth/user" ).body() ).get( "username" );
+        assertNotEquals( "janne", janneDisplayName,
+                "the IT seed gives janne a display name that differs from his login" );
+        final String tokenPage = "TokenMentionITPage";
+        final String anchor = "the lazy dog";
+        final HttpResponse< String > putResp = put( "/api/pages/" + tokenPage,
+                GSON.toJson( Map.of(
+                        "content", "Body containing " + anchor + " for anchoring a token mention.",
+                        "changeNote", "seed for token_mention_reaches_the_mentioned_members_feed_under_display_names" ) ) );
+        assertEquals( 200, putResp.statusCode(), "Seeding the page should return 200: " + putResp.body() );
+        waitForStructuralIndexUp();
+        final String commentText = "@[Alice](user:" + aliceUid + ") please review";
+        final HttpResponse< String > createResp = post( "/api/comment-threads?page=" + tokenPage,
+                GSON.toJson( Map.of( "exact", anchor, "prefix", "", "suffix", "", "text", commentText ) ) );
+        assertEquals( 200, createResp.statusCode(), "POST create thread should return 200: " + createResp.body() );
+
+        // (3) Alice's feed.
+        final HttpResponse< String > feed = aliceClient.send(
+                HttpRequest.newBuilder().uri( URI.create( baseUrl + "/api/me/mentions?status=unread" ) )
+                        .header( "Accept", "application/json" ).GET().build(),
+                HttpResponse.BodyHandlers.ofString() );
+        assertEquals( 200, feed.statusCode(), "GET /api/me/mentions should return 200: " + feed.body() );
+        final List< Map< String, Object > > mentions =
+                ( List< Map< String, Object > > ) parseJson( feed.body() ).get( "mentions" );
+        final Map< String, Object > row = mentions.stream()
+                .filter( m -> tokenPage.equals( m.get( "pageName" ) ) )
+                .findFirst()
+                .orElseThrow( () -> new AssertionError( "the token mention must reach Alice's feed: " + feed.body() ) );
+        assertEquals( janneDisplayName, row.get( "mentionedBy" ), "the author is named by display name" );
+        assertEquals( commentText, row.get( "snippet" ), "Alice can view the page, so the snippet is present" );
+        assertFalse( feed.body().contains( "\"janne\"" ), "janne's login must not appear: " + feed.body() );
+    }
 }
```

- [ ] **Step 2: Run them to verify they fail**

```bash
mvn -B -o test -pl wikantik-rest -Dtest='MentionableUsersResourceTest,MyMentionsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `COMPILATION ERROR … MyMentionsResourceTest.java … cannot find symbol: method publicIdentity()` (one module, so it blocks both classes).
`MentionableUsersResourceTest` has no new seam; compiled on its own against the old resource, 8 of its 14 tests fail: `authenticated_access_returns_id_and_display_name`, `substring_matches_display_name_case_insensitive`,
`vanished_profile_is_skipped_…` (`JsonObject.get(String)` is null — no `id`), `a_row_carries_exactly_id_and_displayName_…`
(`expected: <[id, displayName]> but was: <[loginName, fullName]>`), `no_login_name_appears_anywhere_in_the_response`
(`login leaked ("alice")`), `a_login_is_never_matched_so_a_member_cannot_probe_which_logins_exist` (`login prefix must not match ==> expected: <0> but was: <1>`),
`a_login_fragment_that_is_in_no_display_name_finds_nobody`, `a_member_with_no_display_name_or_no_uid_is_skipped_…` (`expected: <1> but was: <4>`).

- [ ] **Step 3: Implement**

```diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/MentionableUsersResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/MentionableUsersResource.java
@@ -43,17 +43,21 @@
  * composer. Mapped to {@code /api/users/mentionable}.
  * <p>
  * Requires a signed-in (authenticated) session; anonymous and cookie-asserted
- * callers get a {@code 401}. The response carries login names, and under SSO a
- * login name is the user's email address, so the endpoint must not be a public
- * directory. Locked accounts are filtered out so muted users cannot be summoned.
+ * callers get a {@code 401}. Locked accounts are filtered out so muted users
+ * cannot be summoned.
+ * <p>
+ * Members are shown to each other by <em>display name</em> only. Each row is
+ * {@code {"id": "<users.uid>", "displayName": "<name>"}}: no login name (under
+ * SSO it is the member's email address) and no other profile field. The
+ * picker inserts {@code @[<displayName>](user:<id>)}; the id is the key.
+ * <p>
+ * Matching rule: case-insensitive <em>substring</em> match on the display name
+ * (so a last-name fragment still finds the member) — and on nothing else.
+ * Matching on the login would let any signed-in member probe which logins
+ * exist ({@code q=jake@} returning a row confirms an address), so the login is
+ * never consulted. A member with no display name or no uid cannot be shown and
+ * is skipped.
  * <p>
- * Matching rules:
- * <ul>
- *   <li>{@code login_name} — case-insensitive <em>prefix</em> match (the
- *       typical "I typed @al, find me Alice" pattern).</li>
- *   <li>{@code full_name} — case-insensitive <em>substring</em> match (so a
- *       last-name fragment still finds the user).</li>
- * </ul>
  * Default page size {@value #DEFAULT_LIMIT}; hard cap {@value #MAX_LIMIT} —
  * the picker is a typeahead, not a directory browser, so unbounded responses
  * would just be wasted bytes.
@@ -79,8 +83,8 @@
     @Override
     protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
             throws ServletException, IOException {
-        // Gate before touching the user database: login names are email addresses under
-        // SSO, so an unauthenticated caller must not be able to enumerate them.
+        // Gate before touching the user database: the member directory is for signed-in
+        // members only, so an unauthenticated caller must not be able to enumerate it.
         if ( !isAuthenticated( request ) ) {
             sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Login required" );
             return;
@@ -103,12 +107,17 @@
                     continue;
                 }
                 if ( p == null || p.isLocked() ) continue;
-                final String login = p.getLoginName() == null ? "" : p.getLoginName().toLowerCase( Locale.ROOT );
-                final String full  = p.getFullname()  == null ? "" : p.getFullname().toLowerCase( Locale.ROOT );
-                if ( q.isEmpty() || login.startsWith( q ) || full.contains( q ) ) {
+                final String uid = p.getUid();
+                final String displayName = p.getFullname() == null ? "" : p.getFullname().trim();
+                if ( uid == null || uid.isBlank() || displayName.isEmpty() ) {
+                    // Nothing safe to show or key a mention on; never fall back to the login.
+                    LOG.debug( "mentionable: skipping an account with no uid or no display name" );
+                    continue;
+                }
+                if ( q.isEmpty() || displayName.toLowerCase( Locale.ROOT ).contains( q ) ) {
                     final Map< String, Object > row = new LinkedHashMap<>();
-                    row.put( "loginName", p.getLoginName() );
-                    row.put( "fullName",  p.getFullname() );
+                    row.put( "id", uid );
+                    row.put( "displayName", displayName );
                     out.add( row );
                     if ( out.size() >= limit ) break;
                 }
```

```diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/MyMentionsResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/MyMentionsResource.java
@@ -21,6 +21,7 @@
 import com.wikantik.api.comments.MentionFeedItem;
 import com.wikantik.api.core.Engine;
 import com.wikantik.api.core.Session;
+import com.wikantik.api.identity.PublicIdentity;
 import com.wikantik.api.spi.Wiki;
 import com.wikantik.comments.mentions.MentionFeedDao;
 import com.wikantik.comments.mentions.MentionService;
@@ -44,6 +45,11 @@
  * REST servlet backing the per-user mention feed and unread tracking.
  * Mapped to {@code /api/me/mentions/*}.
  *
+ * <p>Rows are stored against logins, but {@code mentionedBy} goes out as the author's current
+ * display name. The comment {@code snippet} is only included when the caller can still view the
+ * page the comment is on: the feed outlives page ACL changes, and a recipient who lost access
+ * must not keep reading the discussion through it.</p>
+ *
  * <ul>
  *   <li>{@code GET /api/me/mentions} — feed list, newest-first, cursor pagination via {@code before}.</li>
  *   <li>{@code GET /api/me/mentions/unread-count} — {@code {count: N}}.</li>
@@ -71,6 +77,11 @@
         return getSubsystems().persistence().mentions();
     }
 
+    /** Seam — turns the stored author login into a display name; overridable for unit tests. */
+    protected PublicIdentity publicIdentity() {
+        return getSubsystems().auth().publicIdentity();
+    }
+
     /** Seam — slug resolution from canonical id, overridable for unit tests. */
     protected Optional< String > resolveSlug( final String canonicalId ) {
         return getSubsystems().pageGraph().structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
@@ -133,17 +144,19 @@
         final Optional< Instant > before = parseInstant( request.getParameter( "before" ) );
 
         final List< MentionFeedItem > rows = mentionFeed().list( me, status, limit, before );
+        final PublicIdentity identity = publicIdentity();
         final List< Map< String, Object > > out = new ArrayList<>();
         for ( final MentionFeedItem r : rows ) {
-            final String slug = resolveSlug( r.canonicalId() ).orElse( r.canonicalId() );
+            final Optional< String > resolved = resolveSlug( r.canonicalId() );
+            final String slug = resolved.orElse( r.canonicalId() );
             final Map< String, Object > m = new LinkedHashMap<>();
             m.put( "id", r.id().toString() );
             m.put( "threadId", r.threadId().toString() );
             m.put( "commentId", r.commentId().toString() );
             m.put( "canonicalId", r.canonicalId() );
             m.put( "pageName", slug );
-            m.put( "snippet", r.snippet() );
-            m.put( "mentionedBy", r.mentionedBy() );
+            m.put( "snippet", canViewPage( request, resolved ) ? r.snippet() : null );
+            m.put( "mentionedBy", identity.displayName( r.mentionedBy() ) );
             m.put( "isOwnerMention", r.isOwnerMention() );
             m.put( "mentionedAt", r.mentionedAt() == null ? null : r.mentionedAt().toString() );
             m.put( "readAt", r.readAt() == null ? null : r.readAt().toString() );
@@ -154,6 +167,16 @@
         sendJson( response, body );
     }
 
+    /**
+     * True when the caller may view the page the mention's comment is on. A page that no longer
+     * resolves cannot be checked, so its snippet is withheld too. Uses the silent capability query:
+     * hiding a snippet is a visibility decision, not an enforcement denial, so it writes no
+     * {@code access.denied} audit row.
+     */
+    private boolean canViewPage( final HttpServletRequest request, final Optional< String > slug ) {
+        return slug.isPresent() && hasPagePermission( request, slug.get(), "view" );
+    }
+
     private void unreadCount( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
         final Map< String, Object > body = new LinkedHashMap<>();
         body.put( "count", mentionService().unreadCount( currentUser( request ) ) );
```

- [ ] **Step 4: Run the tests to verify they pass, then the wire-level IT**

```bash
mvn -B -o test -pl wikantik-rest -Dtest='MentionableUsersResourceTest,MyMentionsResourceTest,CommentThreadResourceTest,AuthResourceTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test-compile -pl wikantik-rest
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-rest
# IT: needs the WAR rebuilt with Tasks 13-15 (clean, or the IT's war overlay is stale; this also compiles the IT sources),
# then the rest IT module (Docker).
bin/agent-build.sh start c-war -- mvn -B -o clean install -DskipTests -T 1C
bin/agent-build.sh wait c-war 540
bin/agent-build.sh start c-it -- bin/run-tests.sh --module rest
bin/agent-build.sh wait c-it 540      # re-run `wait` until it stops returning 2; then: bin/agent-build.sh tail c-it 60
```

Expected: unit tests green (scratch run: `MentionableUsersResourceTest` 14, `MyMentionsResourceTest` 22); IT module green including
`CommentThreadIT#token_mention_reaches_the_mentioned_members_feed_under_display_names` and the pre-existing
`mention_in_comment_appears_in_my_mentions_feed`. If the first `mvn clean install` already ran in an earlier task's verification, a plain
`install` is not enough — keep the `clean`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/MentionableUsersResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/MyMentionsResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MentionableUsersResourceTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/MyMentionsResourceTest.java \
        wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/CommentThreadIT.java
git commit -m "feat(rest): mentionable users matched and returned by display name only; my-mentions names the author and hides snippets the recipient cannot view

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Frontend — display names in comments and mentions; tokens in the picker

**Files:**
- Create: `wikantik-frontend/src/utils/mentionTokens.js`, `wikantik-frontend/src/utils/mentionTokens.test.js`
- Modify: `wikantik-frontend/src/components/CommentBody.jsx` (whole file, 1-29) + `CommentBody.test.jsx`
- Modify: `wikantik-frontend/src/components/MentionPicker.jsx:3-43` (rows 27-37) + `MentionPicker.test.jsx`
- Modify: `wikantik-frontend/src/hooks/useMentionPicker.js` — import 2; contract doc 11-24; `TOKEN_RE` 106; `accept` 220-234 — + `useMentionPicker.test.js`
- Modify: `wikantik-frontend/src/components/CommentsDrawer.jsx` — `ThreadCard` 9-12, 36-45, 65-70, 105, 121; `CommentsDrawer` 137-160 — + `CommentsDrawer.test.jsx`
- Modify: `wikantik-frontend/src/components/MentionsPage.jsx` — import 6; author 126; snippet 144 — + `MentionsPage.test.jsx`
- Modify: `wikantik-frontend/src/components/PageView.jsx` — composer `acceptLogin` 60-70, 96, 118; thread state 158; `loadThreads` 166-173; load effect 180-183; `<CommentsDrawer>` props 620-627 — + `PageView.test.jsx`
- Modify: `wikantik-frontend/src/styles/article.css:246,455-470`
- Modify (comment only): `wikantik-frontend/src/api/client.js:231` — `listMentionableUsers` / `listMyMentions` / `listCommentThreads` are pass-throughs, so **no code change**; the comment pins the new response shapes where the next reader will look.

**Interfaces:**
- Consumes (Tasks 13 + 15 wire shapes): `GET /api/comment-threads` → `threads[].comments[].author` (display name) / `.authorId`, `mentions: {uid: name}`;
  `GET /api/auth/user` → `userId`; `GET /api/users/mentionable` → `users[] = {id, displayName}`; `GET /api/me/mentions` → `mentionedBy`, optional `snippet`.
- Produces: `utils/mentionTokens.js` — `splitMentions(body) -> [{type:'text',text} | {type:'mention',uid,snapshot}]`,
  `buildMentionToken(uid, displayName) -> '@[<sanitised name>](user:<uid>)'`, `flattenMentions(text, names?) -> string`.
  Comments written by the SPA now store `@[Display Name](user:<uid>)`; `useMentionPicker.accept( candidate )` and `MentionPicker onSelect( candidate )` take the
  whole `{id, displayName}` candidate; `CommentBody` takes `mentionNames`; `CommentsDrawer` takes `mentionNames` and `currentUserId`.

Findings that shaped the code:
- **There is no own-comment action in the UI today.** The server enforces author-only edit/delete (`PATCH`/`DELETE …/comments/{id}`, compared on
  logins) but `CommentsDrawer` renders no edit/delete control — only thread-level moderator delete (`canModerate`). So "mine" has nothing to
  *gate* yet; `authorId === user.userId` is wired so the drawer marks the viewer's own comments (`.comment-item.mine`, a "(you)" tag) and any
  future edit/delete control must key off the same comparison (the server stays authoritative). Anonymous viewers and legacy authors without an
  `authorId` are never "mine"; a different member with the same display name is not "mine" (tests).
- Mention tokens in a `My mentions` snippet: the server truncates the snippet at 160 characters, which can cut a token in half. `flattenMentions` renders
  complete tokens as `@Name` and replaces a trailing partial token with `…`, so the page never shows raw `user:` text.
- The picker's query character class was `[A-Za-z0-9._-]`; display names are any script, so it is now `[\p{L}\p{N}._'’-]` (brackets/parens/colon excluded so a caret inside an
  inserted token closes the picker). The query is still the single word after the `@` (the walk-back stops at whitespace); matching is a substring on the display name,
  so a first- or last-name fragment finds the member.
- `buildMentionToken` strips `[`/`]`/control characters and truncates the *snapshot* to 64 UTF-16 units without splitting a surrogate pair, so whatever the member's current name
  is, the inserted token always satisfies the server pattern. The snapshot is only a fallback; the uid is the key.
- A legacy `@login` is plain text now (no chip, no dead `/wiki/Users/<login>` link). The chip is a `<span>`, not a link, so the `:hover` rule goes.

- [ ] **Step 1: Write the failing tests**

New util tests (token parse/bounds/uid shapes/legacy, token build/sanitise/truncate/surrogates, snippet flattening):

```js
import { describe, it, expect } from 'vitest';
import { splitMentions, buildMentionToken, flattenMentions } from './mentionTokens';

describe('splitMentions', () => {
  it('returns no segments for an empty body', () => {
    expect(splitMentions('')).toEqual([]);
    expect(splitMentions(null)).toEqual([]);
    expect(splitMentions(undefined)).toEqual([]);
  });

  it('returns one text segment when there is no token', () => {
    expect(splitMentions('just words')).toEqual([{ type: 'text', text: 'just words' }]);
  });

  it('splits text around a token', () => {
    expect(splitMentions('hi @[Alice A](user:u-1) please look')).toEqual([
      { type: 'text', text: 'hi ' },
      { type: 'mention', uid: 'u-1', snapshot: 'Alice A' },
      { type: 'text', text: ' please look' },
    ]);
  });

  it('finds several tokens, including adjacent ones', () => {
    const segs = splitMentions('@[A](user:1)@[B](user:2)');
    expect(segs.map((s) => s.type)).toEqual(['mention', 'mention']);
    expect(segs.map((s) => s.uid)).toEqual(['1', '2']);
  });

  it('leaves a legacy @login inside the text segment', () => {
    expect(splitMentions('ping @alice now')).toEqual([{ type: 'text', text: 'ping @alice now' }]);
  });

  it('does not treat malformed tokens as mentions', () => {
    for (const bad of [
      '@[Alice](user:)',
      '@[](user:u1)',
      '@[Alice](user:bad uid)',
      '@[Alice](user:u1',
      '@[Ali\nce](user:u1)',
      `@[${'x'.repeat(65)}](user:u1)`,
      `@[Alice](user:${'u'.repeat(65)})`,
    ]) {
      expect(splitMentions(bad).every((s) => s.type === 'text')).toBe(true);
    }
  });

  it('accepts the 64-character bounds', () => {
    const name = 'n'.repeat(64);
    const uid = 'u'.repeat(64);
    expect(splitMentions(`@[${name}](user:${uid})`)).toEqual([{ type: 'mention', uid, snapshot: name }]);
  });

  it('accepts negative numeric and hyphenated uids', () => {
    expect(splitMentions('@[Admin](user:-6852820166199419346)')[0].uid).toBe('-6852820166199419346');
    expect(splitMentions('@[Agents](user:agents-service-account)')[0].uid).toBe('agents-service-account');
  });
});

describe('buildMentionToken', () => {
  it('builds the stored token form', () => {
    expect(buildMentionToken('u-1', 'Alice A')).toBe('@[Alice A](user:u-1)');
  });

  it('round-trips through splitMentions', () => {
    const seg = splitMentions(buildMentionToken('u-9', 'Zoë Ng'))[0];
    expect(seg).toEqual({ type: 'mention', uid: 'u-9', snapshot: 'Zoë Ng' });
  });

  it('strips brackets and control characters from the snapshot', () => {
    expect(buildMentionToken('u-1', 'A[li]ce\nB')).toBe('@[AliceB](user:u-1)');
  });

  it('truncates the snapshot to 64 characters', () => {
    const token = buildMentionToken('u-1', 'x'.repeat(80));
    expect(token).toBe(`@[${'x'.repeat(64)}](user:u-1)`);
    expect(splitMentions(token)[0].type).toBe('mention');
  });

  it('never splits a surrogate pair when truncating', () => {
    const token = buildMentionToken('u-1', '😀'.repeat(40));
    const seg = splitMentions(token)[0];
    expect(seg.type).toBe('mention');
    expect(seg.snapshot.length).toBeLessThanOrEqual(64);
    expect(seg.snapshot).toBe('😀'.repeat(32));
  });

  it('falls back to a neutral snapshot when nothing printable is left', () => {
    expect(buildMentionToken('u-1', '[]')).toBe('@[user](user:u-1)');
    expect(buildMentionToken('u-1', null)).toBe('@[user](user:u-1)');
  });
});

describe('flattenMentions', () => {
  it('returns an empty string for empty input', () => {
    expect(flattenMentions('')).toBe('');
    expect(flattenMentions(null)).toBe('');
  });

  it('shows the snapshot when no current name is known', () => {
    expect(flattenMentions('hi @[Old Name](user:u-1)!')).toBe('hi @Old Name!');
  });

  it('prefers the current name from the map', () => {
    expect(flattenMentions('hi @[Old Name](user:u-1)!', { 'u-1': 'New Name' })).toBe('hi @New Name!');
  });

  it('leaves legacy @login text alone', () => {
    expect(flattenMentions('hi @alice')).toBe('hi @alice');
  });

  it('drops a token that the snippet length cut in half', () => {
    expect(flattenMentions('see @[Alice A](user:u-1) and @[Bob B](user:u-')).toBe('see @Alice A and …');
    expect(flattenMentions('see @[Bob B')).toBe('see …');
    expect(flattenMentions('see @[Bob B](')).toBe('see …');
  });
});
```

`CommentBody` (replaces the file: the legacy-chip assertions are inverted):

```jsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import CommentBody from './CommentBody';

describe('CommentBody', () => {
  it('renders plain text unchanged when no mentions', () => {
    render(<CommentBody body="hello world" />);
    expect(screen.getByText('hello world')).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a mention token as a chip with the snapshot name when no current name is known', () => {
    const { container } = render(<CommentBody body="hi @[Alice A](user:u-1) please look" />);
    const chip = container.querySelector('.comment-mention-chip');
    expect(chip.textContent).toBe('@Alice A');
    expect(chip.getAttribute('data-user-id')).toBe('u-1');
    expect(container.querySelector('.comment-body').textContent).toBe('hi @Alice A please look');
  });

  it('shows the CURRENT display name from the mentions map, not the snapshot', () => {
    const { container } = render(
      <CommentBody body="ping @[Old Name](user:u-1)" mentionNames={{ 'u-1': 'New Name' }} />
    );
    expect(container.querySelector('.comment-mention-chip').textContent).toBe('@New Name');
    expect(container.textContent).not.toContain('Old Name');
  });

  it('falls back to the snapshot for a uid missing from the map', () => {
    const { container } = render(
      <CommentBody body="@[Gone Away](user:u-9)" mentionNames={{ 'u-1': 'Someone Else' }} />
    );
    expect(container.querySelector('.comment-mention-chip').textContent).toBe('@Gone Away');
  });

  it('renders several chips in one body', () => {
    const { container } = render(<CommentBody body="@[A](user:1) and @[B](user:2) both" />);
    const chips = container.querySelectorAll('.comment-mention-chip');
    expect(chips).toHaveLength(2);
    expect(chips[0].textContent).toBe('@A');
    expect(chips[1].textContent).toBe('@B');
  });

  it('never renders a chip as a link', () => {
    render(<CommentBody body="@[Alice A](user:u-1)" />);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('renders a legacy @login as plain text without the dead /wiki/Users link', () => {
    const { container } = render(<CommentBody body="hi @alice please look" />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.querySelector('.comment-body').textContent).toBe('hi @alice please look');
  });

  it('leaves a malformed token as text', () => {
    const { container } = render(<CommentBody body="see @[Alice](user:) there" />);
    expect(container.querySelector('.comment-mention-chip')).toBeNull();
    expect(container.textContent).toBe('see @[Alice](user:) there');
  });

  it('null/empty body renders nothing', () => {
    const { container: c1 } = render(<CommentBody body="" />);
    expect(c1.textContent).toBe('');
    const { container: c2 } = render(<CommentBody body={null} />);
    expect(c2.textContent).toBe('');
  });
});
```

The remaining test files are edits (candidates become `{id, displayName}`; `accept` takes a candidate; drawer/page tests for names, "mine", the
`mentions` map and the token insert; mentions-page tests for display name, flattening, partial token and the hidden-snippet notice):

```diff
--- a/wikantik-frontend/src/components/MentionPicker.test.jsx
+++ b/wikantik-frontend/src/components/MentionPicker.test.jsx
@@ -3,8 +3,8 @@
 import MentionPicker from './MentionPicker';
 
 const CANDS = [
-  { loginName: 'alice', fullName: 'Alice A' },
-  { loginName: 'alicia', fullName: 'Alicia B' },
+  { id: 'u-alice', displayName: 'Alice A' },
+  { id: 'u-alicia', displayName: 'Alicia B' },
 ];
 
 describe('MentionPicker', () => {
@@ -33,25 +33,34 @@
     expect(items[1].getAttribute('aria-selected')).toBe('true');
   });
 
-  it('fires onSelect with loginName when clicked', () => {
+  it('shows display names only', () => {
+    render(
+      <MentionPicker open={true} candidates={CANDS} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 100, left: 50 }} />
+    );
+    const items = screen.getAllByRole('option');
+    expect(items[0].textContent).toBe('@Alice A');
+    expect(items[1].textContent).toBe('@Alicia B');
+  });
+
+  it('fires onSelect with the whole candidate (id + displayName) when clicked', () => {
     const onSelect = vi.fn();
     render(
       <MentionPicker open={true} candidates={CANDS} selectedIndex={0} onSelect={onSelect} anchorPos={{ top: 100, left: 50 }} />
     );
     fireEvent.mouseDown(screen.getAllByRole('option')[1]);
-    expect(onSelect).toHaveBeenCalledWith('alicia');
+    expect(onSelect).toHaveBeenCalledWith({ id: 'u-alicia', displayName: 'Alicia B' });
   });
 
   it('renders nothing when anchorPos is null (no caret coords yet)', () => {
     const { container } = render(
-      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={null} />
+      <MentionPicker open={true} candidates={[{ id: 'u-a', displayName: 'A' }]} selectedIndex={0} onSelect={() => {}} anchorPos={null} />
     );
     expect(container.firstChild).toBeNull();
   });
 
   it('renders at the supplied anchorPos via position: fixed', () => {
     render(
-      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 222, left: 333 }} />
+      <MentionPicker open={true} candidates={[{ id: 'u-a', displayName: 'A' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 222, left: 333 }} />
     );
     const list = screen.getByRole('listbox');
     expect(list.style.position).toBe('fixed');
@@ -61,11 +70,11 @@
 
   it('moves when anchorPos changes (caret moves)', () => {
     const { rerender } = render(
-      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 100, left: 50 }} />
+      <MentionPicker open={true} candidates={[{ id: 'u-a', displayName: 'A' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 100, left: 50 }} />
     );
     expect(screen.getByRole('listbox').style.top).toBe('100px');
     rerender(
-      <MentionPicker open={true} candidates={[{ loginName: 'a', fullName: '' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 200, left: 75 }} />
+      <MentionPicker open={true} candidates={[{ id: 'u-a', displayName: 'A' }]} selectedIndex={0} onSelect={() => {}} anchorPos={{ top: 200, left: 75 }} />
     );
     expect(screen.getByRole('listbox').style.top).toBe('200px');
   });
```

```diff
--- a/wikantik-frontend/src/hooks/useMentionPicker.test.js
+++ b/wikantik-frontend/src/hooks/useMentionPicker.test.js
@@ -21,9 +21,9 @@
 
   beforeEach(() => {
     fetchCandidates = vi.fn(async (q) => [
-      { loginName: 'alice', fullName: 'Alice A' },
-      { loginName: 'alicia', fullName: 'Alicia B' },
-    ].filter((u) => u.loginName.startsWith(q)));
+      { id: 'u-alice', displayName: 'Alice A' },
+      { id: 'u-alicia', displayName: 'Alicia B' },
+    ].filter((u) => u.displayName.toLowerCase().includes(q.toLowerCase())));
     textareaRef = { current: null };
     vi.useFakeTimers();
   });
@@ -92,18 +92,45 @@
     expect(result.current.open).toBe(false);
   });
 
-  it('accept(login) replaces the in-flight token with @login + space', async () => {
+  it('accept(candidate) replaces the in-flight token with @[Display Name](user:<id>) + space', async () => {
     textareaRef.current = makeTextarea('hi @al ', 6);
     const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
     // Drive the picker via onChange so tokenStart/tokenEnd are tracked.
     await act(async () => { result.current.onChange({ target: textareaRef.current }); });
     await act(async () => { await vi.runAllTimersAsync(); });
     // Caret is at position 6 (right after 'al', before the space).
-    const out = result.current.accept('alice');
-    // Original "hi @al ", token "@al" (indices 3..6) replaced with "@alice " → "hi @alice  ".
-    expect(out.replacement).toBe('hi @alice  ');
+    const out = result.current.accept({ id: 'u-alice', displayName: 'Alice A' });
+    // Original "hi @al ", token "@al" (indices 3..6) replaced with the stored token + space.
+    expect(out.replacement).toBe('hi @[Alice A](user:u-alice)  ');
     // Caret position right after the inserted trailing space.
-    expect(out.selectionStart).toBe('hi @alice '.length);
+    expect(out.selectionStart).toBe('hi @[Alice A](user:u-alice) '.length);
+    expect(out.selectionEnd).toBe(out.selectionStart);
+  });
+
+  it('accept() never writes a login name — only the display-name snapshot and the id', async () => {
+    textareaRef.current = makeTextarea('@z', 2);
+    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
+    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
+    const out = result.current.accept({ id: '7f3c', displayName: 'Zoë [Ng]' });
+    expect(out.replacement).toBe('@[Zoë Ng](user:7f3c) ');
+  });
+
+  it('opens for a non-ASCII display-name fragment', async () => {
+    textareaRef.current = makeTextarea('hi @Zoë', 7);
+    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
+    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
+    await act(async () => { await vi.runAllTimersAsync(); });
+    expect(result.current.open).toBe(true);
+    expect(result.current.query).toBe('Zoë');
+  });
+
+  it('closes when the caret rests inside an already-inserted token', async () => {
+    const value = 'hi @[Alice A](user:u-alice)';
+    textareaRef.current = makeTextarea(value, value.length);
+    const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
+    await act(async () => { result.current.onChange({ target: textareaRef.current }); });
+    await act(async () => { await vi.runAllTimersAsync(); });
+    expect(result.current.open).toBe(false);
   });
 
   it('onKeyDown returns false when picker is closed', () => {
@@ -159,7 +186,7 @@
   it('accept() returns empty replacement when textareaRef.current is null', () => {
     textareaRef.current = null;
     const { result } = renderHook(() => useMentionPicker({ textareaRef, fetchCandidates }));
-    const out = result.current.accept('alice');
+    const out = result.current.accept({ id: 'u-alice', displayName: 'Alice A' });
     expect(out).toEqual({ replacement: '', selectionStart: 0, selectionEnd: 0 });
   });
 });
```

```diff
--- a/wikantik-frontend/src/components/CommentsDrawer.test.jsx
+++ b/wikantik-frontend/src/components/CommentsDrawer.test.jsx
@@ -8,11 +8,12 @@
 }));
 vi.mock('../utils/caretCoords', () => ({ getCaretCoordinates: vi.fn(() => ({ top: 1, left: 2 })) }));
 
+// The API returns display names (`author`, `createdBy`) and opaque uids (`authorId`, `createdById`).
 const threads = [
-  { id: 'T1', status: 'open', anchor: { exact: 'alpha' }, createdBy: 'alice',
-    comments: [{ id: 'C1', author: 'alice', body: 'first note' }] },
-  { id: 'T2', status: 'resolved', anchor: { exact: 'beta' }, createdBy: 'bob',
-    comments: [{ id: 'C2', author: 'bob', body: 'done note' }] },
+  { id: 'T1', status: 'open', anchor: { exact: 'alpha' }, createdBy: 'Alice A', createdById: 'u-alice',
+    comments: [{ id: 'C1', author: 'Alice A', authorId: 'u-alice', body: 'first note' }] },
+  { id: 'T2', status: 'resolved', anchor: { exact: 'beta' }, createdBy: 'Bob B', createdById: 'u-bob',
+    comments: [{ id: 'C2', author: 'Bob B', authorId: 'u-bob', body: 'done note' }] },
 ];
 
 function setup(extra = {}) {
@@ -128,6 +129,51 @@
     expect(screen.getByText('No comments yet.')).toBeTruthy();
   });
 
+  describe('display names and "mine"', () => {
+    it('shows the display name of each comment author', () => {
+      setup({ statusFilter: 'all' });
+      expect(screen.getByText('Alice A')).toBeTruthy();
+      expect(screen.getByText('Bob B')).toBeTruthy();
+    });
+
+    it('marks the viewer\'s own comment when authorId matches user.userId', () => {
+      const { container } = setup({ statusFilter: 'all', currentUserId: 'u-alice' });
+      const mine = container.querySelectorAll('.comment-item.mine');
+      expect(mine).toHaveLength(1);
+      expect(mine[0].textContent).toContain('first note');
+      expect(mine[0].querySelector('.comment-author-you').textContent).toBe('(you)');
+    });
+
+    it('marks nothing as mine for an anonymous viewer', () => {
+      const { container } = setup({ statusFilter: 'all', currentUserId: null });
+      expect(container.querySelector('.comment-item.mine')).toBeNull();
+      expect(screen.queryByText('(you)')).toBeNull();
+    });
+
+    it('compares ids, not names: a different member with the same display name is not mine', () => {
+      const lookalike = [{ id: 'T9', status: 'open', anchor: { exact: 'x' },
+        comments: [{ id: 'C9', author: 'Alice A', authorId: 'u-impostor', body: 'hello' }] }];
+      const { container } = setup({ statusFilter: 'open', threads: lookalike, currentUserId: 'u-alice' });
+      expect(container.querySelector('.comment-item.mine')).toBeNull();
+    });
+
+    it('a comment without an authorId (legacy author) is never mine', () => {
+      const legacy = [{ id: 'T8', status: 'open', anchor: { exact: 'x' },
+        comments: [{ id: 'C8', author: 'Old Name', body: 'legacy' }] }];
+      const { container } = setup({ statusFilter: 'open', threads: legacy, currentUserId: 'u-alice' });
+      expect(container.querySelector('.comment-item.mine')).toBeNull();
+    });
+
+    it('renders mention chips with the current name from mentionNames', () => {
+      const withMention = [{ id: 'T7', status: 'open', anchor: { exact: 'x' },
+        comments: [{ id: 'C7', author: 'Bob B', authorId: 'u-bob', body: 'cc @[Old Name](user:u-carol)' }] }];
+      const { container } = setup({
+        statusFilter: 'open', threads: withMention, mentionNames: { 'u-carol': 'Carol C' },
+      });
+      expect(container.querySelector('.comment-mention-chip').textContent).toBe('@Carol C');
+    });
+  });
+
   describe('@-mention autocomplete (signed-in only)', () => {
     afterEach(() => {
       vi.useRealTimers();
@@ -138,7 +184,7 @@
     // picker's 150 ms debounce elapse so any candidate fetch would have fired.
     async function typeMention() {
       vi.useFakeTimers();
-      api.listMentionableUsers.mockResolvedValue({ users: [{ loginName: 'alice', fullName: 'Alice A' }] });
+      api.listMentionableUsers.mockResolvedValue({ users: [{ id: 'u-alice', displayName: 'Alice A' }] });
       const reply = screen.getByPlaceholderText(/reply/i);
       fireEvent.change(reply, { target: { value: 'hi @al', selectionStart: 6, selectionEnd: 6 } });
       await act(async () => { await vi.advanceTimersByTimeAsync(400); });
@@ -150,6 +196,15 @@
       expect(api.listMentionableUsers).toHaveBeenCalledWith('al');
     });
 
+    it('picking a candidate with Enter inserts the stored token, not a login name', async () => {
+      setup({ statusFilter: 'open', canMention: true });
+      await typeMention();
+      const reply = screen.getByPlaceholderText(/reply/i);
+      fireEvent.keyDown(reply, { key: 'Enter' });
+      await act(async () => { await vi.advanceTimersByTimeAsync(50); });
+      expect(reply.value).toBe('hi @[Alice A](user:u-alice) ');
+    });
+
     it('never calls the mentionable-users endpoint for an anonymous viewer', async () => {
       // The endpoint answers 401 to anonymous callers (login names are email addresses
       // under SSO); a 401 would also fire the global wikantik:auth-required re-auth event.
```

```diff
--- a/wikantik-frontend/src/components/MentionsPage.test.jsx
+++ b/wikantik-frontend/src/components/MentionsPage.test.jsx
@@ -22,8 +22,8 @@
   {
     id: 'm1', threadId: 't1', commentId: 'c1',
     canonicalId: 'CID1', pageName: 'Foo',
-    snippet: 'hi @alice please',
-    mentionedBy: 'bob',
+    snippet: 'hi @[Alice A](user:u-alice) please',
+    mentionedBy: 'Bob B',
     isOwnerMention: false,
     mentionedAt: '2026-05-28T00:00:00Z',
     readAt: null,
@@ -32,7 +32,7 @@
     id: 'm2', threadId: 't2', commentId: 'c2',
     canonicalId: 'CID2', pageName: 'Bar',
     snippet: 'thx',
-    mentionedBy: 'carol',
+    mentionedBy: 'Carol C',
     isOwnerMention: true,
     mentionedAt: '2026-05-27T00:00:00Z',
     readAt: null,
@@ -59,12 +59,41 @@
   it('renders mentions list on mount with default unread filter', async () => {
     renderPage();
     await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledWith({ status: 'unread', limit: 50 }));
-    expect(screen.getByText(/hi @alice please/)).toBeTruthy();
+    expect(screen.getByText(/hi @Alice A please/)).toBeTruthy();
     expect(screen.getByText(/thx/)).toBeTruthy();
     // Owner mention has "(your page)" tag.
     expect(screen.getByText('(your page)')).toBeTruthy();
   });
 
+  it('shows the display name of who mentioned you, without a login-style @ prefix', async () => {
+    renderPage();
+    expect(await screen.findByText('Bob B')).toBeTruthy();
+    expect(screen.getByText('Carol C')).toBeTruthy();
+    expect(screen.queryByText('@Bob B')).toBeNull();
+  });
+
+  it('renders mention tokens in the snippet as plain @Display Name text', async () => {
+    renderPage();
+    const snippet = await screen.findByText(/hi @Alice A please/);
+    expect(snippet.textContent).not.toContain('user:');
+    expect(snippet.textContent).not.toContain('[');
+  });
+
+  it('drops a token cut off by the server snippet length', async () => {
+    api.listMyMentions.mockResolvedValue({ mentions: [{
+      ...SAMPLE[0], snippet: 'see @[Alice A](user:u-alice) and @[Bob B](user:u-',
+    }] });
+    renderPage();
+    expect(await screen.findByText(/see @Alice A and …/)).toBeTruthy();
+  });
+
+  it('shows a notice instead of a snippet when the server omitted it', async () => {
+    api.listMyMentions.mockResolvedValue({ mentions: [{ ...SAMPLE[0], snippet: undefined }] });
+    renderPage();
+    expect(await screen.findByText('You can no longer view this comment.')).toBeTruthy();
+    expect(screen.queryByText(/hi @/)).toBeNull();
+  });
+
   it('filter toggle re-fetches with the chosen status', async () => {
     renderPage();
     await waitFor(() => expect(api.listMyMentions).toHaveBeenCalledTimes(1));
@@ -74,7 +103,7 @@
 
   it('dismiss (X) on a row calls markMentionRead and reloads', async () => {
     renderPage();
-    expect(await screen.findByText(/hi @alice please/)).toBeTruthy();
+    expect(await screen.findByText(/hi @Alice A please/)).toBeTruthy();
     const dismissButtons = screen.getAllByTitle('Mark read');
     fireEvent.click(dismissButtons[0]);
     await waitFor(() => expect(api.markMentionRead).toHaveBeenCalledWith('m1'));
@@ -106,7 +135,7 @@
 
   it('view-in-context link goes to /wiki/<page>?thread=...&comment=...', async () => {
     renderPage();
-    expect(await screen.findByText(/hi @alice please/)).toBeTruthy();
+    expect(await screen.findByText(/hi @Alice A please/)).toBeTruthy();
     const links = screen.getAllByRole('link');
     const fooLink = links.find((l) => l.textContent === 'Foo');
     expect(fooLink.getAttribute('href')).toContain('/wiki/Foo?thread=t1&comment=c1');
@@ -119,7 +148,7 @@
       api.markMentionRead.mockReturnValue(new Promise(res => { resolveMarkOne = res; }));
 
       renderPage();
-      expect(await screen.findByText(/hi @alice please/)).toBeTruthy();
+      expect(await screen.findByText(/hi @Alice A please/)).toBeTruthy();
 
       // Item m1 is unread — dismiss button visible
       const dismissButtons = screen.getAllByTitle('Mark read');
@@ -148,7 +177,7 @@
       api.markMentionRead.mockRejectedValue(new Error('network error'));
 
       renderPage();
-      expect(await screen.findByText(/hi @alice please/)).toBeTruthy();
+      expect(await screen.findByText(/hi @Alice A please/)).toBeTruthy();
 
       const dismissButtons = screen.getAllByTitle('Mark read');
       const initialCount = dismissButtons.length;
```

```diff
--- a/wikantik-frontend/src/components/PageView.test.jsx
+++ b/wikantik-frontend/src/components/PageView.test.jsx
@@ -880,6 +880,16 @@
     expect(api.listMentionableUsers).toHaveBeenCalledWith('al');
   }, TEST_TIMEOUT);
 
+  it('picking a candidate inserts the stored display-name token into the composer', async () => {
+    api.listMentionableUsers.mockResolvedValue({ users: [{ id: 'u-alice', displayName: 'Alice A' }] });
+    const { container } = await mountAndSettle();
+    await typeMentionInComposer(container);
+    const textarea = screen.getByPlaceholderText('Add a comment');
+    fireEvent.keyDown(textarea, { key: 'Enter' });
+    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 50)); });
+    expect(textarea.value).toBe('hi @[Alice A](user:u-alice) ');
+  }, TEST_TIMEOUT);
+
   it('anonymous viewer: typing @al never queries the mentionable-users endpoint', async () => {
     // /api/users/mentionable answers 401 to anonymous callers (login names are email
     // addresses under SSO), and a 401 would fire the global re-auth event on every keystroke.
@@ -893,3 +903,47 @@
     expect(api.listMentionableUsers).not.toHaveBeenCalled();
   }, TEST_TIMEOUT);
 });
+
+describe('PageView display names in comments', () => {
+  const MENTION_THREAD = {
+    id: 'T5',
+    status: 'open',
+    anchor: { exact: 'quick brown', prefix: 'The ', suffix: ' fox' },
+    comments: [
+      { id: 'C5', author: 'Bob B', authorId: 'u-bob', body: 'cc @[Old Name](user:u-carol)', createdAt: '2026-01-01T00:00:00Z' },
+      { id: 'C6', author: 'Alice A', authorId: 'u-alice', body: 'mine', createdAt: '2026-01-02T00:00:00Z' },
+    ],
+  };
+
+  it('passes the mentions map to the drawer so chips show the current display name', async () => {
+    api.listCommentThreads.mockResolvedValue({ threads: [MENTION_THREAD], mentions: { 'u-carol': 'Carol C' } });
+    const { container } = await mountAndSettle();
+    fireEvent.click(screen.getByTestId('comments-toggle-button'));
+    await waitFor(() => expect(container.querySelector('.comment-mention-chip')?.textContent).toBe('@Carol C'));
+  }, TEST_TIMEOUT);
+
+  it('falls back to the snapshot name when the response carries no mentions map', async () => {
+    api.listCommentThreads.mockResolvedValue({ threads: [MENTION_THREAD] });
+    const { container } = await mountAndSettle();
+    fireEvent.click(screen.getByTestId('comments-toggle-button'));
+    await waitFor(() => expect(container.querySelector('.comment-mention-chip')?.textContent).toBe('@Old Name'));
+  }, TEST_TIMEOUT);
+
+  it('marks the signed-in member\'s own comment using user.userId from /api/auth/user', async () => {
+    api.getUser.mockResolvedValue({ authenticated: true, username: 'Alice A', userId: 'u-alice', roles: [] });
+    api.listCommentThreads.mockResolvedValue({ threads: [MENTION_THREAD], mentions: {} });
+    const { container } = await mountAndSettle();
+    fireEvent.click(screen.getByTestId('comments-toggle-button'));
+    await waitFor(() => expect(container.querySelectorAll('.comment-item.mine')).toHaveLength(1));
+    expect(container.querySelector('.comment-item.mine').textContent).toContain('mine');
+  }, TEST_TIMEOUT);
+
+  it('an anonymous viewer never has a comment marked as theirs', async () => {
+    api.getUser.mockResolvedValue({ authenticated: false, roles: [] });
+    api.listCommentThreads.mockResolvedValue({ threads: [MENTION_THREAD], mentions: {} });
+    const { container } = renderPageView();
+    fireEvent.click(await screen.findByTestId('comments-toggle-button'));
+    await waitFor(() => expect(container.querySelectorAll('.comment-item').length).toBeGreaterThan(0));
+    expect(container.querySelector('.comment-item.mine')).toBeNull();
+  }, TEST_TIMEOUT);
+});
```

- [ ] **Step 2: Run them to verify they fail**

```bash
cd wikantik-frontend
npx vitest run src/utils/mentionTokens.test.js src/components/CommentBody.test.jsx src/components/MentionPicker.test.jsx \
  src/hooks/useMentionPicker.test.js src/components/CommentsDrawer.test.jsx src/components/MentionsPage.test.jsx src/components/PageView.test.jsx
```

Expected (measured against the unchanged components): `Test Files 7 failed`, `Tests 26 failed | 89 passed (115)` — `mentionTokens.test.js` fails to import
(`./mentionTokens` missing); `CommentBody` (chip text/legacy), `MentionPicker` (shows display names / `onSelect` payload), `useMentionPicker`
(`accept(candidate)`, non-ASCII query), `CommentsDrawer` (`.mine`, `mentionNames`, token insert), `MentionsPage` (display name, flattening, notice —
and, because the fixtures now hold tokens, the pre-existing rows too), `PageView` (mentions map, `userId`, composer insert).

- [ ] **Step 3: Implement**

Shared token helpers (new file):

```js
// Mention tokens are stored in comment text as `@[Display Name](user:<uid>)`.
// The display text is a snapshot taken when the comment was written; the uid is
// the key. Keep the pattern in step with the server's MentionExtractor
// (wikantik-main, com.wikantik.comments.mentions.MentionExtractor.TOKEN).
const TOKEN_SOURCE = '@\\[([^\\]\\n]{1,64})\\]\\(user:([A-Za-z0-9-]{1,64})\\)';

const SNAPSHOT_MAX = 64;

/**
 * Splits a comment body into ordered segments:
 * `{ type: 'text', text }` and `{ type: 'mention', uid, snapshot }`.
 * A legacy `@login` is not a token and stays inside the surrounding text segment.
 */
export function splitMentions(body) {
  if (!body) return [];
  const segments = [];
  let last = 0;
  // A fresh RegExp per call: matchAll on a shared /g regex would share lastIndex.
  for (const match of body.matchAll(new RegExp(TOKEN_SOURCE, 'g'))) {
    if (match.index > last) segments.push({ type: 'text', text: body.substring(last, match.index) });
    segments.push({ type: 'mention', uid: match[2], snapshot: match[1] });
    last = match.index + match[0].length;
  }
  if (last < body.length) segments.push({ type: 'text', text: body.substring(last) });
  return segments;
}

/**
 * Builds the token for a picker candidate. The snapshot is cleaned so it always
 * fits the server pattern: no brackets, no control characters, at most 64 chars.
 */
export function buildMentionToken(uid, displayName) {
  let snapshot = '';
  for (const ch of String(displayName ?? '')) {
    if (ch === '[' || ch === ']' || /[\p{Cc}]/u.test(ch)) continue;
    if (snapshot.length + ch.length > SNAPSHOT_MAX) break;
    snapshot += ch;
  }
  snapshot = snapshot.trim() || 'user';
  return `@[${snapshot}](user:${uid})`;
}

// A token the server's snippet length cut off: `@[Na`, `@[Name]`, `@[Name](`, `@[Name](user:u-`.
const PARTIAL_TAIL = /@\[[^\]\n]*(?:\](?:\([A-Za-z0-9:-]*)?)?$/;

/**
 * Plain-text form of a comment fragment, for one-line previews such as the "My
 * mentions" snippet: complete tokens become `@<current name or snapshot>`, and a
 * token cut off at the end of the fragment is replaced by an ellipsis.
 */
export function flattenMentions(text, names = {}) {
  if (!text) return '';
  return splitMentions(text.replace(PARTIAL_TAIL, '…'))
    .map((s) => (s.type === 'text' ? s.text : `@${names[s.uid] || s.snapshot}`))
    .join('');
}
```

`CommentBody` (whole file):

```jsx
import { splitMentions } from '../utils/mentionTokens';

/**
 * Renders a comment body. Mention tokens (`@[Display Name](user:<uid>)`) become
 * chips showing the member's CURRENT display name from `mentionNames` (the
 * comment-threads response carries `mentions: {uid: displayName}`), falling back
 * to the name captured when the comment was written. Anything else — including a
 * legacy `@login` — is plain text; there is no profile page to link to.
 */
export default function CommentBody({ body, mentionNames = {} }) {
  if (!body) return null;
  const parts = splitMentions(body).map((segment, i) => {
    if (segment.type === 'text') return segment.text;
    return (
      <span key={`m-${i}-${segment.uid}`} className="comment-mention-chip" data-user-id={segment.uid}>
        @{mentionNames[segment.uid] || segment.snapshot}
      </span>
    );
  });
  return <span className="comment-body">{parts}</span>;
}
```

`MentionPicker` (whole file — rows show the display name only; `onSelect` receives the candidate):

```jsx
import { useEffect, useRef } from 'react';

/** Dumb popover for @-mention autocomplete. Driven entirely by props from
 *  the useMentionPicker hook. Candidates are `{ id, displayName }`: members are
 *  shown to each other by display name only — never by login name. */
export default function MentionPicker({ open, candidates, selectedIndex, onSelect, anchorPos }) {
  const listRef = useRef(null);

  // Scroll the active candidate into view if the keyboard navigation moves
  // selectedIndex outside the visible window.
  useEffect(() => {
    if (!open || !listRef.current) return;
    const active = listRef.current.querySelector('.mention-picker-item.active');
    if (active && typeof active.scrollIntoView === 'function') {
      active.scrollIntoView({ block: 'nearest' });
    }
  }, [open, selectedIndex]);

  if (!open || !candidates || candidates.length === 0 || !anchorPos) return null;

  // anchorPos is { top, left } relative to viewport (fixed).
  const style = { position: 'fixed', top: anchorPos.top, left: anchorPos.left };

  return (
    <div className="mention-picker" style={style} ref={listRef} role="listbox" aria-label="User mentions">
      {candidates.map((c, idx) => (
        <button
          key={c.id}
          type="button"
          role="option"
          aria-selected={idx === selectedIndex}
          className={`mention-picker-item ${idx === selectedIndex ? 'active' : ''}`}
          // onMouseDown (not onClick) so the textarea doesn't lose focus first.
          onMouseDown={(e) => { e.preventDefault(); onSelect(c); }}
        >
          <span className="mention-picker-name">@{c.displayName}</span>
        </button>
      ))}
    </div>
  );
}
```

The hook inserts the stored token and accepts non-ASCII fragments:

```diff
--- a/wikantik-frontend/src/hooks/useMentionPicker.js
+++ b/wikantik-frontend/src/hooks/useMentionPicker.js
@@ -1,5 +1,6 @@
 import { useCallback, useEffect, useRef, useState } from 'react';
 import { getCaretCoordinates } from '../utils/caretCoords';
+import { buildMentionToken } from '../utils/mentionTokens';
 
 /**
  * Pure-logic hook for tracking an in-flight `@<token>` mention under a
@@ -10,21 +11,25 @@
  *   const picker = useMentionPicker({ textareaRef, fetchCandidates });
  *
  *   textareaRef:      React ref to the host textarea element.
- *   fetchCandidates:  (query: string) => Promise<[{loginName, fullName}]>
+ *   fetchCandidates:  (query: string) => Promise<[{id, displayName}]>
  *
  * Returns:
  *   open:           boolean — whether to render a popover
- *   candidates:     [{loginName, fullName}] — current candidates
+ *   candidates:     [{id, displayName}] — current candidates
  *   selectedIndex:  number — highlighted candidate in the list
- *   query:          string — substring being matched (the chars after the last '@')
+ *   query:          string — fragment of a display name being matched (the chars after the last '@')
  *   anchorPos:      { top, left } | null — caret position for popover anchoring
  *   onKeyDown(ev):  boolean — handles keyboard nav; returns true if consumed
  *   onChange(ev):   void — call from textarea's onChange to update picker state
- *   accept(login):  { replacement, selectionStart, selectionEnd } — apply selection
+ *   accept(candidate): { replacement, selectionStart, selectionEnd } — apply selection;
+ *                   inserts the stored token `@[Display Name](user:<id>) `
  *   close():        void — manually close (e.g., on blur)
  */
 
-const TOKEN_RE = /^[A-Za-z0-9._-]*$/;
+// What a member may have typed after the '@': part of a display name. Letters and digits of
+// any script, plus the punctuation names commonly carry. Brackets, parentheses and colons are
+// excluded so a caret resting inside an already-inserted `@[Name](user:id)` token closes the picker.
+const TOKEN_RE = /^[\p{L}\p{N}._'’-]*$/u;
 
 export function useMentionPicker({ textareaRef, fetchCandidates }) {
   const [state, setState] = useState({
@@ -133,12 +138,12 @@
     }
     if (event.key === 'Enter' || event.key === 'Tab') {
       event.preventDefault();
-      return true; // caller handles via accept(selectedLogin)
+      return true; // caller handles via accept(selectedCandidate)
     }
     return false;
   }, [state.open, close]);
 
-  const accept = useCallback((login) => {
+  const accept = useCallback((candidate) => {
     const ta = textareaRef.current;
     if (!ta) {
       return { replacement: '', selectionStart: 0, selectionEnd: 0 };
@@ -147,7 +152,7 @@
     const value = ta.value;
     const before = tokenStart >= 0 ? value.substring(0, tokenStart) : value;
     const after = tokenEnd >= 0 ? value.substring(tokenEnd) : '';
-    const inserted = `@${login} `;
+    const inserted = `${buildMentionToken(candidate.id, candidate.displayName)} `;
     const replacement = before + inserted + after;
     const caretAfter = (before + inserted).length;
     close();
```

The drawer shows display names, marks the viewer's comments, and renders chips with current names; `PageView` keeps the `mentions` map from each
`listCommentThreads` response and hands the drawer `mentionNames` + `currentUserId` (`user.userId`, only when authenticated):

```diff
--- a/wikantik-frontend/src/components/CommentsDrawer.jsx
+++ b/wikantik-frontend/src/components/CommentsDrawer.jsx
@@ -7,7 +7,7 @@
 import Icon from './ui/Icon';
 
 function ThreadCard({
-  thread, detached, canModerate, canMention, focusedThreadId,
+  thread, detached, canModerate, canMention, focusedThreadId, mentionNames, currentUserId,
   onReply, onResolve, onReopen, onDeleteThread, onFocusThread,
 }) {
   const [reply, setReply] = useState('');
@@ -36,8 +36,8 @@
       catch { return []; }
     },
   });
-  const acceptLogin = (login) => {
-    const { replacement, selectionStart } = picker.accept(login);
+  const acceptCandidate = (candidate) => {
+    const { replacement, selectionStart } = picker.accept(candidate);
     setReply(replacement);
     setTimeout(() => {
       const ta = replyRef.current;
@@ -62,12 +62,18 @@
   return (
     <div className={`comment-thread${isFocused ? ' focused' : ''}`} onClick={() => onFocusThread(thread.id)}>
       <div className="comment-thread-anchor">“{thread.anchor?.exact}”</div>
-      {thread.comments.map((c) => (
-        <div key={c.id} className="comment-item">
-          <span className="comment-author">{c.author}</span>
-          <CommentBody body={c.body} />
-        </div>
-      ))}
+      {thread.comments.map((c) => {
+        // `author` is a display name; `authorId` is the opaque uid the viewer's own
+        // `user.userId` is compared with. Anonymous viewers have no id, so nothing is "mine".
+        const mine = !!currentUserId && c.authorId === currentUserId;
+        return (
+          <div key={c.id} className={`comment-item${mine ? ' mine' : ''}`}>
+            <span className="comment-author">{c.author}</span>
+            {mine && <span className="comment-author-you">(you)</span>}
+            <CommentBody body={c.body} mentionNames={mentionNames} />
+          </div>
+        );
+      })}
       {confirmingDelete ? (
         <div className="comment-thread-confirm" onClick={(e) => e.stopPropagation()}>
           <span className="comment-thread-confirm-text">Delete this thread permanently?</span>
@@ -102,7 +108,7 @@
               if (picker.onKeyDown(e)) {
                 if (e.key === 'Enter' || e.key === 'Tab') {
                   const sel = picker.candidates[picker.selectedIndex];
-                  if (sel) acceptLogin(sel.loginName);
+                  if (sel) acceptCandidate(sel);
                 }
                 return;
               }
@@ -118,7 +124,7 @@
             open={picker.open}
             candidates={picker.candidates}
             selectedIndex={picker.selectedIndex}
-            onSelect={acceptLogin}
+            onSelect={acceptCandidate}
             anchorPos={picker.anchorPos}
           />
         </div>
@@ -136,7 +142,7 @@
 
 export default function CommentsDrawer({
   open, threads, detachedIds = [], statusFilter, canModerate = false,
-  canMention = false, focusedThreadId = null,
+  canMention = false, focusedThreadId = null, mentionNames = {}, currentUserId = null,
   onStatusFilter, onReply, onResolve, onReopen, onDeleteThread = () => {},
   onFocusThread, onClose,
 }) {
@@ -150,7 +156,8 @@
   const attached = visible.filter((t) => !isDetached(t.id));
   const detached = visible.filter((t) => isDetached(t.id));
   const cardProps = {
-    canModerate, canMention, focusedThreadId, onReply, onResolve, onReopen, onDeleteThread, onFocusThread,
+    canModerate, canMention, focusedThreadId, mentionNames, currentUserId,
+    onReply, onResolve, onReopen, onDeleteThread, onFocusThread,
   };
 
   return (
```

```diff
--- a/wikantik-frontend/src/components/PageView.jsx
+++ b/wikantik-frontend/src/components/PageView.jsx
@@ -57,8 +57,8 @@
       catch { return []; }
     },
   });
-  const acceptLogin = (login) => {
-    const { replacement, selectionStart } = picker.accept(login);
+  const acceptCandidate = (candidate) => {
+    const { replacement, selectionStart } = picker.accept(candidate);
     setText(replacement);
     // After React renders, restore caret + auto-grow.
     setTimeout(() => {
@@ -93,7 +93,7 @@
           if (picker.onKeyDown(e)) {
             if (e.key === 'Enter' || e.key === 'Tab') {
               const sel = picker.candidates[picker.selectedIndex];
-              if (sel) acceptLogin(sel.loginName);
+              if (sel) acceptCandidate(sel);
             }
             return;
           }
@@ -115,7 +115,7 @@
         open={picker.open}
         candidates={picker.candidates}
         selectedIndex={picker.selectedIndex}
-        onSelect={acceptLogin}
+        onSelect={acceptCandidate}
         anchorPos={picker.anchorPos}
       />
     </div>
@@ -156,6 +156,8 @@
   const articleRef = useRef(null);
 
   const [threads, setThreads] = useState([]);
+  // uid -> current display name for every @mention token in the loaded comments.
+  const [mentionNames, setMentionNames] = useState({});
   const [detachedIds, setDetachedIds] = useState([]);
   const [drawerOpen, setDrawerOpen] = useState(false);
   const [statusFilter, setStatusFilter] = useState('open');
@@ -167,6 +169,7 @@
     try {
       const res = await api.listCommentThreads(name, 'all');
       setThreads(res.threads || []);
+      setMentionNames(res.mentions || {});
     } catch (e) {
       console.warn('Failed to load comment threads', e);
     }
@@ -179,7 +182,11 @@
   useEffect(() => {
     let ignore = false;
     api.listCommentThreads(name, 'all')
-      .then((res) => { if (!ignore) setThreads(res.threads || []); })
+      .then((res) => {
+        if (ignore) return;
+        setThreads(res.threads || []);
+        setMentionNames(res.mentions || {});
+      })
       .catch((e) => { if (!ignore) console.warn('Failed to load comment threads', e); });
     return () => { ignore = true; };
   }, [name]);
@@ -624,6 +631,8 @@
         statusFilter={statusFilter}
         canModerate={!!page.permissions?.delete}
         canMention={!!user?.authenticated}
+        currentUserId={user?.authenticated ? (user.userId ?? null) : null}
+        mentionNames={mentionNames}
         focusedThreadId={focusedThreadId}
         onStatusFilter={setStatusFilter}
         onReply={async (threadId, text) => {
```

The mentions page names the author without a login-style `@`, flattens tokens in the snippet, and explains a withheld snippet:

```diff
--- a/wikantik-frontend/src/components/MentionsPage.jsx
+++ b/wikantik-frontend/src/components/MentionsPage.jsx
@@ -4,6 +4,7 @@
 import { useToast } from '../hooks/useToast';
 import { useDocumentTitle } from '../hooks/useDocumentTitle';
 import { formatRelative } from '../utils/datetime';
+import { flattenMentions } from '../utils/mentionTokens';
 import EmptyState from './ui/EmptyState';
 import Icon from './ui/Icon';
 
@@ -123,7 +124,7 @@
             style={{ animationDelay: i < 10 ? `${i * 40}ms` : '400ms' }}
           >
             <div className="mentions-item-meta">
-              <span className="mentions-item-author">@{m.mentionedBy}</span>
+              <span className="mentions-item-author">{m.mentionedBy}</span>
               <span className="mentions-item-context">
                 on <Link
                   to={`/wiki/${encodeURIComponent(m.pageName)}?thread=${encodeURIComponent(m.threadId)}&comment=${encodeURIComponent(m.commentId)}`}
@@ -141,7 +142,14 @@
                 >✕</button>
               )}
             </div>
-            <div className="mentions-item-snippet">&quot;{m.snippet}&quot;</div>
+            {m.snippet ? (
+              <div className="mentions-item-snippet">&quot;{flattenMentions(m.snippet)}&quot;</div>
+            ) : (
+              // The server omits the snippet when the viewer can no longer see the page.
+              <div className="mentions-item-snippet">
+                You can no longer view this comment.
+              </div>
+            )}
           </li>
         ))}
       </ul>
```

Styles (the picker rows lose the login/full-name split; the chip stops pretending to be a link; the "(you)" tag) and the response-shape comment:

```diff
--- a/wikantik-frontend/src/styles/article.css
+++ b/wikantik-frontend/src/styles/article.css
@@ -244,6 +244,7 @@
 .comment-thread-anchor { font-size: 12px; color: #64748b; font-style: italic; margin-bottom: 6px; }
 .comment-item { font-size: 13px; margin-bottom: 4px; }
 .comment-author { font-weight: 600; margin-right: 6px; }
+.comment-author-you { color: var(--text-secondary); font-size: 12px; margin-right: 6px; }
 .comment-reply-row { display: flex; gap: 6px; margin-top: 6px; }
 
 /* Multi-line auto-growing reply: same behaviour as the comment composer
@@ -452,10 +453,9 @@
 }
 .mention-picker-item.active,
 .mention-picker-item:hover { background: var(--sage-light); }
-.mention-picker-login { font-weight: 600; }
-.mention-picker-fullname { color: var(--text-secondary); }
+.mention-picker-name { font-weight: 600; }
 
-/* Comment @-mention chip linking to /wiki/Users/<login>. */
+/* Comment @-mention chip: the member's display name. Not a link — members have no profile page. */
 .comment-mention-chip {
   display: inline-block;
   padding: 0 6px;
@@ -463,10 +463,8 @@
   border-radius: 4px;
   background: var(--sage-light);
   color: var(--text);
-  text-decoration: none;
   font-weight: 600;
 }
-.comment-mention-chip:hover { background: var(--border-strong); }
 
 /* ── Code-copy button ─────────────────────────────────────────────── */
 .article-prose pre { position: relative; }
```

```diff
--- a/wikantik-frontend/src/api/client.js
+++ b/wikantik-frontend/src/api/client.js
@@ -228,7 +228,10 @@
   deleteCommentThread: (threadId) =>
     request(`/api/comment-threads/${encodeURIComponent(threadId)}`, { method: 'DELETE' }),
 
-  // Mentions + mentionable users
+  // Mentions + mentionable users. Members are identified by display name and an opaque uid, never by login:
+  //   listMentionableUsers -> { users: [{ id, displayName }] }, matched on display name only
+  //   listMyMentions       -> { mentions: [{ ..., mentionedBy: <display name>, snippet: <absent when the page is no longer viewable> }] }
+  //   listCommentThreads   -> { threads: [...], mentions: { <uid>: <current display name> } }
   listMentionableUsers: (q, limit = 8) =>
     request(`/api/users/mentionable?q=${encodeURIComponent(q)}&limit=${limit}`),
 
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd wikantik-frontend
npx vitest run src/utils/mentionTokens.test.js src/components/CommentBody.test.jsx src/components/MentionPicker.test.jsx \
  src/hooks/useMentionPicker.test.js src/components/CommentsDrawer.test.jsx src/components/MentionsPage.test.jsx \
  src/components/PageView.test.jsx src/api/client.test.js
npx eslint src/utils/mentionTokens.js src/utils/mentionTokens.test.js src/components/CommentBody.jsx src/components/CommentBody.test.jsx \
  src/components/MentionPicker.jsx src/components/MentionPicker.test.jsx src/hooks/useMentionPicker.js src/hooks/useMentionPicker.test.js \
  src/components/CommentsDrawer.jsx src/components/CommentsDrawer.test.jsx src/components/MentionsPage.jsx src/components/MentionsPage.test.jsx \
  src/components/PageView.jsx src/components/PageView.test.jsx src/api/client.js
npx vitest run          # whole suite: a flake in an unrelated file under load -> re-run that file alone (known concurrency flake)
```

Expected: all pass, ESLint clean — zero errors *and* zero warnings, like the rest of the tree (the new PageView test uses `findByTestId` because `testing-library/prefer-find-by` warns on `waitFor` + `getByTestId`).
In the scratch run the whole suite was 249 files / 2 864 tests green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/mentionTokens.js wikantik-frontend/src/utils/mentionTokens.test.js \
        wikantik-frontend/src/components/CommentBody.jsx wikantik-frontend/src/components/CommentBody.test.jsx \
        wikantik-frontend/src/components/MentionPicker.jsx wikantik-frontend/src/components/MentionPicker.test.jsx \
        wikantik-frontend/src/hooks/useMentionPicker.js wikantik-frontend/src/hooks/useMentionPicker.test.js \
        wikantik-frontend/src/components/CommentsDrawer.jsx wikantik-frontend/src/components/CommentsDrawer.test.jsx \
        wikantik-frontend/src/components/MentionsPage.jsx wikantik-frontend/src/components/MentionsPage.test.jsx \
        wikantik-frontend/src/components/PageView.jsx wikantik-frontend/src/components/PageView.test.jsx \
        wikantik-frontend/src/styles/article.css wikantik-frontend/src/api/client.js
git commit -m "feat(frontend): comments and mentions show display names; picker inserts @[Name](user:uid) tokens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Section notes

**Contract issues found in the real code**

1. **Gson omits nulls** (`RestServletBase.GSON` has no `serializeNulls`). The contract's "null" for `userId` (anonymous), `authorId` (non-user), `snippet` (hidden) is therefore "key absent". The server code still `put( .., null )` for documentation, and tests/clients accept both. If another section's tests assert `isJsonNull()` on such a field they will fail — assert `!has() || isJsonNull()`.
2. **`users.uid` shapes and NULLs.** `users.uid` is `VARCHAR(100)`, nullable, and the data holds UUIDs, **negative numerics** (`-6852820166199419346`, the V002 admin seed / janne in the IT seed) and slugs (`agents-service-account`, `it-seed-user-alice`). The contract's token regex `[A-Za-z0-9-]{1,64}` covers all of them, but a uid > 64 chars or with another character would be unmentionable — **Task 21** (or its dry-run) should assert every `users.uid` matches `^[A-Za-z0-9-]{1,64}$`. Worse: `JdbcUserProfileRowMapper.mapProfileRow` replaces a **NULL uid with a freshly generated UUID on every load (the read does not persist it)**, so `findByLoginName(..).getUid()` is different each call and `findByUid` never finds it. **Task 5**'s `DefaultPublicIdentity` must not surface that transient value from `userIdForLogin` (treat NULL-uid rows as "no uid") — otherwise `GET /api/auth/user` would return a different `userId` on every poll, "mine" would never match, and the mentionable picker would hand out unresolvable ids. The cleanest fix is for Task 21's one-shot script to backfill missing uids (check prod first: `SELECT login_name FROM users WHERE uid IS NULL OR uid !~ '^[A-Za-z0-9-]{1,64}$'` — the V035 `agents` row has the slug uid `agents-service-account`, which is fine). `MentionableUsersResource` reads `p.getUid()` straight from the profile and has the same exposure for NULL-uid rows.
3. **`AuthSubsystem.Services` gets a 9th component in Task 5.** Existing code constructing it with 8 args must be updated by Task 5, not by me: `AuthSubsystemFactory.create`, `AuthSubsystemBridge.fromLegacyEngine` (the all-null record), `DefaultPageGraphServiceTest` (×3), `RememberMeAuthFilterTest` (×2), `AdminApiKeysResourceTest` (×2), and the Javadoc example in `WikiSubsystemsTestFactory`. My new tests only `Mockito.mock( AuthSubsystem.Services.class )`, so they compile either way once the component exists. The `WikiEngine` mention closure reads `this.authSubsystem` lazily and null-guards `publicIdentity()`.
4. **`PersistenceSubsystem.Deps` is edited here** (5th component `loginForUserIdLookup`, with the old 4- and 2-arg constructors kept). If Task 12 (`PageOwnerService`) or Task 5 also touches `Deps` or `WikiEngine.initKnowledgeGraph` lines 968-1016, merge by hand — keep the extra closure and the 5-arg `new Deps(...)` call.
5. **No own-comment UI exists** in `CommentsDrawer`/`PageView` (server-side author-only `PATCH`/`DELETE …/comments/{id}` have no client). `authorId === user.userId` is implemented as a "(you)" marker + `.comment-item.mine` class; nothing else decides own-comment actions today. If the spec intended edit/delete controls, that is a new feature, not part of this work.
6. **The IT seed, not XML, defines Alice.** `CommentThreadIT`'s Javadoc still describes an XML user database; the user actually comes from `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql` (uid `it-seed-user-alice`). The legacy `@Alice` IT keeps passing (legacy parsing is retained); my new IT covers the token path. Task 22's leak-probe should likewise create its mention **by token** (`@[Leak Probe](user:<uid>)`, uid from `/api/auth/user`) — an email login cannot be mentioned any other way.
7. **`GET /api/auth/me|session|status`** (the D24 alias) returns `login` and `fullName` but no `userId`. Not required by the contract and left alone (own login only); add `userId` there only if an agent client needs it.
8. **`/api/auth/user` still returns `loginPrincipal`** (the caller's own login; `UserBadge` renders it as `data-login-name`, `PersonalZone`/`QuickOverlay`/`PageEditor` use it to key per-user local state). That is within the spec ("visible to that user"); I did not touch it. Task 22 must not assert its absence.
9. **Mention picker matches the first word only** (the caret walk-back stops at whitespace), so `@Jake Fe` cannot narrow further; substring matching on the display name mitigates it. Deliberate; noted in case the UX review wants multi-word search.
10. **Docs to update in Task 23**: `docs/user/CommentsAndMentions.md` (line 29 "start of their login name", 81-99 picker rows / `@loginName` insertion / "clickable chips that link to /wiki/Users/{loginName}", 199 the `/api/users/mentionable` table row with `{loginName, fullName}` and "login name starts with", 223 the `@<word>` pattern description) and `docs/user/PersonalZone.md` (mentions).
11. **Stored comment bodies keep whatever was typed.** A legacy `@someone@example.com`/pasted address in a comment body is shown verbatim by `GET /api/comment-threads` — out of scope per the spec (free text), but the leak-probe IT should put its mention in a *token*, not paste the login as text.
12. **60-second identity cache** (`DefaultPublicIdentity`): after a rename, `mentions` map and author names in comment responses update within a minute (or immediately on the same node via `invalidate`), which is what Task 4/5 promise.

**Cross-section dependencies**

- **Needs from Section A:** Task 5 (`PublicIdentity`, `AuthSubsystem.Services#publicIdentity()` as the LAST component, populated by `AuthSubsystemFactory` **and** `AuthSubsystemBridge`, and `WikiEngine.authSubsystem` rebuilt coherently); Task 1 (unique display names — the uniqueness is what makes `mentionedBy`/chips unambiguous).
- **Provides to Section E:** `userId` on `GET /api/auth/user` (Task 20's Preferences page may display/compare it; the SPA `user.userId`); the token flow and `mentions` map for Task 22's leak probe; a uid-shape/NULL-uid check request for Task 21 (item 2).
- **Touches files other sections may also edit:** `WikiEngine.java` (Task 5 accessor; merge by hand), `PersistenceSubsystem.java` (`Deps`; Task 12), `CommentThreadResource.java` (only here), `PageView.jsx`/`PageView.test.jsx` (only here), `AuthResource.java` (Tasks 1/4 edit `handleUpdateProfile` far from `handleGetUser` — `patch` offsets are fine), `AuthResourceTest.java` (Task 4 likely adds profile tests — different region).
- **Build ordering:** after Task 14 changes `wikantik-main`, `wikantik-rest` tests need the re-installed main jar (`bin/agent-build.sh start c-main -- mvn -B -o install -pl wikantik-main -DskipTests`); the IT in Task 15 needs `clean install` of the WAR (stale overlay otherwise).

**Process note for coordination:** drafters share the session scratchpad; another section used `scratchpad/fe` and overwrote my first scratch frontend copy mid-run (I rebuilt in a private `scratchpad/partC/` and re-verified everything there). Use unique directory names in any further scratch work.

---

# Section D — Access-rule directives (Tasks 17–19)

# Section D — Tasks 17–19: access-rule directives hidden from non-editors

Spec §5. A page body can carry `[{ALLOW edit alice@example.test}]`; ACL evaluation is login-based, so the directive text names people by login (on prod, an email address). Task 17 builds the one utility that removes that text, Task 18 applies it to raw page text for callers who cannot edit the page, Task 19 applies it where text is indexed (chunks and the Lucene content field). ACL evaluation itself is untouched.

The code, tests and commands below were run against a scratch copy of HEAD (`bf12845fff`): each new test failed first for the reason stated and then passed, the neighbouring suites stayed green, and the PMD complexity gate was green. (The two compile-error reds — `AclDirectivesTest` before the class exists, `/tools` `GetPageToolTest` before `canEdit` exists — are reasoned, not run.) Run Maven with the `WIKANTIK_*` environment variables unset (`unset ${!WIKANTIK_@}`); several suites read them.

## Verified facts the three tasks rest on

**What counts as a directive today**

- `DefaultAclManager` (`wikantik-main/src/main/java/com/wikantik/auth/acl/DefaultAclManager.java`): `ACL_REGEX` (:81) is `\[\{\s*ALLOW\s+(comment|delete|edit|modify|rename|upload|view)\s*(.*?)\s*\}\]` — `ALLOW` only, case-sensitive, lazy to the **first** `}]`; because `\s*` crosses newlines the principal list may start on the next line. `aclDirectives(String)` (:256-269) is the enforcement scanner: it skips matches that start inside a flexmark `Code`, `FencedCodeBlock` or `IndentedCodeBlock` node (:279-289), and `extractAclFromPageText` (:200-222) feeds the rest to `parseAcl`. `setPermissions` (:303-316) just `replaceAll`s the pattern.
- Renderer path: `LinkParsingOperations.isAccessRule` (`wikantik-main/.../parser/LinkParsingOperations.java:77`) accepts a link starting `{ALLOW` **or `{DENY`**; `AccessRuleLinkNodePostProcessorState.process` (`.../markdown/extensions/wikilinks/postprocessor/AccessRuleLinkNodePostProcessorState.java:64-73`) strips the braces and calls `parseAcl`, which ignores the first token. So `DENY` is a recognised spelling that `DefaultAclManager.aclDirectives` never returns; `AclDirectives.strip` removes both.
- Stored pages contain two spellings: `[{ALLOW view All}]` and the plugin-normalised `[{ALLOW view All}]()` (`docs/wikantik-pages/RejectedMessage.md:9`; glued form `[{ALLOW edit Admin}]()[{ALLOW view All}]()` in `docs/wikantik-pages/OLD/CSSThemeDark/1.txt:165`).
- Existing ad-hoc stripping, and the two defects it has: `PageExcerpts.PLUGIN` (`wikantik-main/.../preview/PageExcerpts.java:42`, `\[\{.*?}]` DOTALL) leaves the `()` of a normalised directive behind (excerpt reads `() Hello there.`); `SummaryExtractor.PLUGIN_CALL` (`wikantik-main/.../knowledge/SummaryExtractor.java:38`, `\[\{[^}]*\}\]…`) stops at the first `}`, so `[{ALLOW edit a}x}]` — a rule the ACL manager does enforce — survives into the auto-generated frontmatter `summary`.
- Module reach: `wikantik-util` has no Markdown parser (no flexmark), so `AclDirectives` carries its own small code scanner — see Task 17 for the fail-closed policy and the parity test that compares it with `DefaultAclManager.aclDirectives`. Every consumer gets `wikantik-util` transitively (`wikantik-api` depends on it, `wikantik-main` declares it); `wikantik-rest` already imports `com.wikantik.util.*`, and `wikantik-knowledge` and `wikantik-tools` compiled against it with **no pom change**. This section edits no pom.

**ACL semantics the test fixtures must respect** (an early draft of these tests got this wrong)

- A page with a non-empty ACL is deny-by-default for everything the ACL does not grant: `DefaultAuthorizationManager.decide` (`wikantik-main/.../auth/DefaultAuthorizationManager.java:216-253`) → `decideByAcl` (:276-293) answers `acl-denied` when no principal listed for the requested permission matches the caller, and `edit` implies `comment` implies `view` (`PagePermission` :362-378). So `[{ALLOW edit alice@example.test}]` **alone** makes the page unreadable to everyone but alice and admins. A *public* page that names a login in an ACL needs `[{ALLOW view All}]` as well — every fixture here uses `[{ALLOW view All}]` + `[{ALLOW edit Admin,<login>}]`. Admins hold `AllPermission` and bypass the ACL.
- The wikantik-rest test policy grants anonymous *edit* on ACL-less pages (javadoc of `RestAuthorizationSecurityTest`, :57-62), so a "reader who cannot edit" needs the ACL too.
- `TestEngine.saveText` logs the shared mock session (`HttpMockFactory.SHARED_SESSION_ID`) in as admin: afterwards `HttpMockFactory.createHttpRequest(path)` is an editor and `HttpMockFactory.createIsolatedHttpRequest(path)` is a fresh anonymous reader.

**Surfaces that carry raw page text** — walked from every `getPureText` caller outside `/admin` code. The contract's list is covered; four more leaked and are included; two listed ones carry no text:

| Surface | Code | Rule |
|---|---|---|
| `GET /api/pages/{name}` (also `?version=N`): `content`, `contentHash` | `PageResource.doGet` | strip unless `edit` |
| `GET /api/diff/{name}` (structured and `format=html`) | `DiffResource.doGet` | strip unless `edit` |
| `/wiki/{name}?format=md\|json`, incl. the derived `summary` | `WikiPageFormatFilter.doFilter` | strip unless `edit` |
| **`/wiki/{name}` HTML**: JSON data island `content`, no-JS fallback, head meta (not in the contract list) | `SpaRoutingFilter.injectSemantic` | strip always |
| knowledge MCP `read_pages` | `ReadPagesTool` — request-free guest (`PageViewGate`) | strip always |
| `/tools` `get_page` `text` | `wikantik-tools` `GetPageTool` | strip unless `edit` |
| Obsidian export | `ExportZipBuilder.convertOrFallback` | strip unless `edit` |
| **`/api/briefing`, `get_briefing`**: pinned and cluster-member bodies (not in the list) | `ItemAssembler.bodyOf` | strip always |
| **`/api/pages/for-agent/{id}`, `get_page_for_agent`**: `key_facts` is cut from the body's first paragraphs, which for a page that opens with directives *are* the directives (not in the list) | `DefaultForAgentProjectionService.loadBodyAndFrontmatter` | strip always |
| `GET /api/history/{name}` | returns versions, authors, change notes — **no page text**; the version-text path is `?version=N` above | nothing to do |
| knowledge MCP `get_page` | returns frontmatter summary/tags/cluster — **no body** | nothing to do |

### Task 17: AclDirectives utility

**Files:**
- Create: `wikantik-util/src/main/java/com/wikantik/util/AclDirectives.java`
- Create: `wikantik-util/src/test/java/com/wikantik/util/AclDirectivesTest.java`
- Create: `wikantik-main/src/test/java/com/wikantik/auth/acl/AclDirectivesParityTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java:21,62`
- Modify: `wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java:31,54-55`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java`, `wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java`

**Interfaces:**
- Consumes: `DefaultAclManager.aclDirectives(String)` — package-private static, used **only** by the parity test (same package `com.wikantik.auth.acl`).
- Produces (contract signature unchanged, one overload added):
  - `public static String AclDirectives.strip( String markdown )` — `null` in, `null` out; the *same instance* back when there is nothing to remove.
  - `public static String AclDirectives.strip( String markdown, String replacement )` — each removed directive is replaced by `replacement` instead of `""`. `PageExcerpts` and `SummaryExtractor` pass `" "` so their output stays word-separated exactly as before.

**Behaviour (what the tests pin down)**
- Removed: `[{ALLOW <permission> …}]` and `[{DENY <permission> …}]`, plus an immediately following empty `()` (the plugin-normalised spelling). `<permission>` is one of `comment|delete|edit|modify|rename|upload|view`. The tail of the pattern (`\s*(.*?)\s*\}\]`) is deliberately identical to `DefaultAclManager`'s, so both sides agree where a directive ends — at the first `}]`, and it may break across a line after the permission. Several per line, at the very start or end, any whitespace variant.
- Kept, byte for byte: everything else — other `[{Plugin}]` spans (`[{TableOfContents}]`, `[{SET …}]`, `[{Image …}]`, `[{$pagename}]`), CRLF, the whitespace around a removed directive (a directive on its own line leaves an empty line), malformed brackets (`[{ALLOW edit x`, `[{ALLOW}]`, `[{ALLOW fly x}]`, lower-case `allow`), and directives shown as documentation inside a **closed fenced block** (at most three spaces of indent; a run of three or more backticks or tildes, closed by a run of the same character at least as long) or a **one-line inline code span**.
- **Fail-closed bias.** `wikantik-util` cannot use flexmark, so the code scanner only trusts what it can be sure of. Whatever it does not recognise — an unclosed fence, a span that wraps onto a second line, an indented code block, a fence nested deeper in a list — is treated as text, and a directive there is removed. That can hide a documentation example from a reader; it can never leave a rule the ACL manager enforces visible. (Recognising a multi-line span or an unclosed fence would let one stray backtick — say, in an indented code line — swallow a *real* directive further down the page; `backticksInAnIndentedCodeLineCannotPairWithALaterLineAndHideADirective` pins that case.)
- Removal can splice the text around it into a fresh directive (`[{ALLOW vi[{ALLOW view a}]ew a}]`), so the pass repeats until nothing matches (bounded at 8; real pages need one).
- The parity test (`wikantik-main`) asserts the one-directional invariant that matters: for ~25 hand-written bodies **and every page in `docs/wikantik-pages/*.md`**, `DefaultAclManager.aclDirectives(AclDirectives.strip(body))` is empty and none of the directives the ACL manager reported survives. It documents the single deliberate divergence (an unclosed fence: the renderer treats the rest as code and the ACL manager enforces nothing, `strip` removes anyway).

- [ ] **Step 1: Write the failing tests**

**`wikantik-util/src/test/java/com/wikantik/util/AclDirectivesTest.java`** (new):

``````java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AclDirectives#strip(String)} removes exactly the access-rule directives
 * {@code DefaultAclManager} would enforce (plus {@code DENY}), outside code, and nothing else.
 * The cross-check against the real {@code DefaultAclManager} lives in wikantik-main
 * ({@code AclDirectivesParityTest}), which this module cannot depend on.
 */
class AclDirectivesTest {

    private static final String LOGIN = "alice@example.test";

    // ---- degenerate input ----

    @Test
    void nullIsReturnedAsNull() {
        assertNull( AclDirectives.strip( null ) );
        assertNull( AclDirectives.strip( null, " " ) );
    }

    @Test
    void emptyIsReturnedAsEmpty() {
        assertEquals( "", AclDirectives.strip( "" ) );
    }

    @Test
    void textWithoutDirectivesIsReturnedUnchangedAndSameInstance() {
        final String text = "# Title\n\nJust prose with a [link](x), `code` and [{TableOfContents}].\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void nullReplacementIsRejected() {
        assertThrows( NullPointerException.class, () -> AclDirectives.strip( "x", null ) );
    }

    // ---- the forms that count as a directive ----

    @Test
    void allowDirectiveIsRemoved() {
        assertEquals( "\nBody.", AclDirectives.strip( "[{ALLOW edit " + LOGIN + "}]\nBody." ) );
    }

    @Test
    void denyDirectiveIsRemoved() {
        assertEquals( "Body.", AclDirectives.strip( "[{DENY view " + LOGIN + "}]Body." ) );
    }

    @Test
    void normalisedFormWithEmptyParenthesesIsRemovedWithItsParentheses() {
        assertEquals( "\nBody.", AclDirectives.strip( "[{ALLOW view All}]()\nBody." ) );
    }

    @Test
    void glueTogetherNormalisedDirectivesAreAllRemoved() {
        // Shape found in docs/wikantik-pages/OLD/CSSThemeDark/1.txt
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit Admin}]()[{ALLOW view All}]()" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "comment", "delete", "edit", "modify", "rename", "upload", "view" } )
    void everyPagePermissionIsRecognised( final String permission ) {
        assertEquals( "x", AclDirectives.strip( "x[{ALLOW " + permission + " " + LOGIN + "}]" ) );
        assertEquals( "x", AclDirectives.strip( "x[{DENY " + permission + " " + LOGIN + "}]" ) );
    }

    @Test
    void multiplePrincipalsAreRemovedAsOneDirective() {
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit Admin," + LOGIN + ",Editors}]" ) );
    }

    @Test
    void whitespaceVariantsAreRemoved() {
        assertEquals( "x  y", AclDirectives.strip( "x [{  ALLOW   edit   " + LOGIN + "  }] y" ) );
    }

    @Test
    void aDirectiveMayBreakAcrossLinesAfterThePermissionJustLikeTheAclManagerReadsIt() {
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit\n" + LOGIN + "}]" ) );
    }

    @Test
    void aClosingBraceInsidePrincipalsDoesNotEndTheDirectiveEarly() {
        // DefaultAclManager's lazy (.*?) runs to the first "}]", so the principal is "a}b".
        assertEquals( "", AclDirectives.strip( "[{ALLOW edit a}b}]" ) );
    }

    // ---- position and multiplicity ----

    @Test
    void severalDirectivesOnOneLineAreAllRemoved() {
        assertEquals( "a  b  c",
                AclDirectives.strip( "a [{ALLOW view All}] b [{ALLOW edit Admin," + LOGIN + "}] c" ) );
    }

    @Test
    void directiveAtTheStartAndAtTheEndIsRemoved() {
        assertEquals( "middle", AclDirectives.strip( "[{ALLOW view All}]middle[{ALLOW edit " + LOGIN + "}]" ) );
    }

    @Test
    void aDirectiveOnItsOwnLineLeavesAnEmptyLineAndNothingElseChanges() {
        assertEquals( "---\ntitle: T\n---\n\n[{TableOfContents}]\n\n# H\r\n",
                AclDirectives.strip( "---\ntitle: T\n---\n[{ALLOW edit " + LOGIN + "}]\n[{TableOfContents}]\n\n# H\r\n" ) );
    }

    @Test
    void windowsLineEndingsAroundADirectiveSurvive() {
        assertEquals( "\r\n# Title\r\n", AclDirectives.strip( "[{ALLOW view All}]\r\n# Title\r\n" ) );
    }

    @Test
    void otherPluginSpansAreLeftByteIdentical() {
        final String text = "[{TableOfContents}]\n[{SET foo='bar'}]\n[{Image src='x.png' caption='a'}]\n"
                + "[{INSERT Foo}]\n[{$pagename}]\n";
        assertSame( text, AclDirectives.strip( text ) );
        assertEquals( "[{TableOfContents}]\n\n[{Image src='x.png'}]",
                AclDirectives.strip( "[{TableOfContents}]\n[{ALLOW edit " + LOGIN + "}]\n[{Image src='x.png'}]" ) );
    }

    @Test
    void strippingIsIdempotent() {
        final String once = AclDirectives.strip( "[{ALLOW view All}]() x [{DENY edit " + LOGIN + "}] `[{ALLOW edit a}]`" );
        assertEquals( once, AclDirectives.strip( once ) );
    }

    @Test
    void removalCannotSpliceTheSurroundingTextIntoAFreshDirective() {
        // Removing the inner directive joins "[{ALLOW vi" and "ew a}]" into another one.
        assertEquals( "", AclDirectives.strip( "[{ALLOW vi[{ALLOW view a}]ew a}]" ) );
    }

    // ---- text that is not a directive stays ----

    @ParameterizedTest
    @ValueSource( strings = {
            "[{ALLOW edit alice@example.test",       // never closed
            "[{ALLOW edit alice@example.test}",      // single closing brace
            "[ALLOW edit alice@example.test]",       // not a plugin span
            "{ALLOW edit alice@example.test}",       // no square brackets
            "[{ALLOW}]",                              // no permission
            "[{ALLOW fly alice@example.test}]",      // not a page permission
            "[{allow edit alice@example.test}]",     // directives are case-sensitive
            "[{ ALLOWED edit alice@example.test}]",  // a different word
    } )
    void malformedOrForeignBracketsAreKept( final String text ) {
        assertSame( text, AclDirectives.strip( text ) );
    }

    // ---- fenced code blocks ----

    @Test
    void directiveInsideABacktickFenceIsKept() {
        final String text = "Example:\n\n```\n[{ALLOW edit " + LOGIN + "}]\n```\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void directiveInsideATildeFenceIsKept() {
        final String text = "~~~\n[{ALLOW edit " + LOGIN + "}]\n~~~\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void fenceWithAnInfoStringAndLongerClosingRunIsHonoured() {
        final String text = "````markdown\n```\n[{ALLOW edit " + LOGIN + "}]\n```\n````\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void aShorterRunDoesNotCloseAFenceSoTheFenceIsNeverClosedAndIsNotTrusted() {
        assertEquals( "````\n```\n\n",
                AclDirectives.strip( "````\n```\n[{ALLOW edit " + LOGIN + "}]\n" ) );
    }

    @Test
    void anUnclosedFenceIsNotTrustedAsCode() {
        // The renderer would treat the rest of the page as code; we hide the directive regardless (fail closed).
        assertEquals( "```\ncode\n\nmore", AclDirectives.strip( "```\ncode\n[{ALLOW edit " + LOGIN + "}]\nmore" ) );
    }

    @Test
    void fenceIndentedByThreeSpacesIsAFence() {
        final String text = "   ```\n[{ALLOW edit " + LOGIN + "}]\n   ```\n";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void fenceIndentedByFourSpacesIsNotAFence() {
        assertEquals( "    ```\n\n    ```\n",
                AclDirectives.strip( "    ```\n[{ALLOW edit " + LOGIN + "}]\n    ```\n" ) );
    }

    @Test
    void directiveAfterAClosedFenceIsRemovedAndTheFenceIsUntouched() {
        assertEquals( "```\n[{ALLOW edit a}]\n```\n\nbody",
                AclDirectives.strip( "```\n[{ALLOW edit a}]\n```\n[{ALLOW edit " + LOGIN + "}]\nbody" ) );
    }

    @Test
    void directiveBeforeAFenceIsRemoved() {
        assertEquals( "\n```\ncode\n```\n",
                AclDirectives.strip( "[{ALLOW edit " + LOGIN + "}]\n```\ncode\n```\n" ) );
    }

    // ---- inline code spans ----

    @Test
    void directiveInsideAnInlineCodeSpanIsKept() {
        final String text = "Restrict editing with `[{ALLOW edit " + LOGIN + "}]`.";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void directiveInsideADoubleBacktickSpanIsKept() {
        final String text = "Code: ``[{ALLOW view Admin}]`` end.";
        assertSame( text, AclDirectives.strip( text ) );
    }

    @Test
    void aRealDirectiveBesideACodeExampleIsRemovedAndTheExampleKept() {
        assertEquals( " syntax is `[{ALLOW edit Bob}]`.",
                AclDirectives.strip( "[{ALLOW view Admin}] syntax is `[{ALLOW edit Bob}]`." ) );
    }

    @Test
    void directiveBetweenTwoCodeSpansIsRemoved() {
        assertEquals( "`a`  `c`", AclDirectives.strip( "`a` [{ALLOW edit Bob}] `c`" ) );
    }

    @Test
    void directiveJustAfterACodeSpanIsRemoved() {
        assertEquals( "The `pattern` then .", AclDirectives.strip( "The `pattern` then [{ALLOW edit Admin}]." ) );
    }

    @Test
    void aBacktickSpanThatWrapsOntoAnotherLineIsNotTrustedAsCode() {
        assertEquals( "see `first\n` end", AclDirectives.strip( "see `first\n[{ALLOW edit " + LOGIN + "}]` end" ) );
    }

    @Test
    void backticksInAnIndentedCodeLineCannotPairWithALaterLineAndHideADirective() {
        assertEquals( "    echo `date\n and `x`",
                AclDirectives.strip( "    echo `date\n[{ALLOW edit " + LOGIN + "}] and `x`" ) );
    }

    @Test
    void aBacktickSpanDoesNotCrossABlankLine() {
        // The lone backticks are literal text; the directive between them is a real one.
        assertEquals( "a ` b\n\n\n\nc ` d",
                AclDirectives.strip( "a ` b\n\n[{ALLOW edit " + LOGIN + "}]\n\nc ` d" ) );
    }

    @Test
    void anUnmatchedBacktickRunHidesNothing() {
        assertEquals( "a ` b  c", AclDirectives.strip( "a ` b [{ALLOW edit " + LOGIN + "}] c" ) );
    }

    @Test
    void runsOfDifferentLengthDoNotPairUp() {
        // "``" opens, the single "`" does not close it, so the directive is outside code.
        assertEquals( "`` ` ", AclDirectives.strip( "`` ` [{ALLOW edit " + LOGIN + "}]" ) );
    }

    @Test
    void escapedBackticksAreLiteralAndDoNotFormASpan() {
        assertEquals( "\\`\\`", AclDirectives.strip( "\\`[{ALLOW edit " + LOGIN + "}]\\`" ) );
    }

    // ---- documented limitation: indented code blocks fail closed ----

    @Test
    void directiveInAnIndentedCodeBlockIsRemovedBecauseTheScannerErrsOnTheSideOfHiding() {
        assertEquals( "Before.\n\n    \n\nAfter.\n",
                AclDirectives.strip( "Before.\n\n    [{ALLOW view Admin}]\n\nAfter.\n" ) );
    }

    // ---- replacement overload ----

    @Test
    void replacementTakesTheDirectivesPlaceAndKeepsNeighbouringWordsApart() {
        assertEquals( "a b", AclDirectives.strip( "a[{ALLOW view All}]b", " " ) );
        assertEquals( "a b", AclDirectives.strip( "a[{ALLOW view All}]()b", " " ) );
    }

    @Test
    void replacementLeavesCodeAlone() {
        final String text = "`[{ALLOW view All}]`";
        assertSame( text, AclDirectives.strip( text, " " ) );
    }
}
``````

**Regression tests for the two delegates** — both fail today for real reasons (`SummaryExtractor`'s pattern stops at the first `}`; `PageExcerpts` leaves the `()` of a normalised directive behind):

``````diff
--- a/wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java
@@ -77,4 +77,17 @@
         assertFalse( result.contains( "TableOfContents" ) );
         assertTrue( result.contains( "plugin system" ) );
     }
+
+    /**
+     * DefaultAclManager reads a rule whose principal list contains a closing brace ("[{ALLOW edit a}x}]") as one rule,
+     * because its pattern ends at the first closing brace-bracket pair; the plugin pattern here stopped at the first
+     * closing brace, so the rule used to survive into the summary.
+     */
+    @Test
+    void stripsAnAccessRuleWhosePrincipalListContainsABrace() {
+        final String body = "[{ALLOW edit alice@example.test}x}]\nThis page explains how the plugin system works in detail.";
+        final String result = SummaryExtractor.extract( body );
+        assertFalse( result.contains( "alice" ), result );
+        assertTrue( result.contains( "plugin system" ) );
+    }
 }
``````

``````diff
--- a/wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java
@@ -61,4 +61,9 @@
         assertEquals( "See the target, T > H done.",
                 PageExcerpts.excerpt( "See [[Target|the target]], [[T#H]] ![[Embedded]] done.", 280 ) );
     }
+
+    /** Plugin normalisation writes a directive as "[{ALLOW view Admin}]()"; the empty parentheses go with it. */
+    @Test void dropsTheEmptyParenthesesOfANormalisedAccessRule() {
+        assertEquals( "Hello there.", PageExcerpts.excerpt( "[{ALLOW view Admin}]()\n\nHello there.\n", 280 ) );
+    }
 }
``````

**`wikantik-main/src/test/java/com/wikantik/auth/acl/AclDirectivesParityTest.java`** (new) — a cross-check between the two definitions of "access rule": what the ACL manager enforces versus what `strip` removes:

``````java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.auth.acl;

import com.wikantik.util.AclDirectives;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety net between two implementations of "what is an access rule": {@link DefaultAclManager} decides
 * what the wiki <em>enforces</em>, {@link AclDirectives} (wikantik-util, which cannot depend on it)
 * decides what is <em>hidden</em> from readers who may not edit. The invariant that matters for
 * disclosure is one-directional — nothing the ACL manager enforces may survive {@code strip} — so that is
 * what every test here asserts. {@code strip} may remove more (it also removes {@code DENY}, and it does
 * not trust code it cannot be certain about); it must never remove less.
 */
class AclDirectivesParityTest {

    /** Stands for the name a leak must never reveal. */
    private static final String LOGIN = "leak-probe@example.test";

    private static final Pattern TRAILING_EMPTY_PARENS = Pattern.compile( "^\\(\\)" );

    /** Bodies in which the ACL manager enforces at least one directive, in many placements and spellings. */
    static Stream< String > bodiesWithEnforcedDirectives() {
        return Stream.of(
                "[{ALLOW view Admin}]\nSecret content.",
                "Intro. [{ALLOW view Admin}] more text.",
                "[{ALLOW view Admin}] then [{ALLOW edit " + LOGIN + "}]",
                "x [{  ALLOW  view  Admin  }] y [{ALLOW edit " + LOGIN + "}]",
                "[{ALLOW view Admin," + LOGIN + "}]",
                "The `pattern` then [{ALLOW edit " + LOGIN + "}].",
                "`a` [{ALLOW edit " + LOGIN + "}] `c`",
                "[{ALLOW view Admin}] syntax is `[{ALLOW edit Bob}]`.",
                "Example `[{ALLOW view Admin}]` but really [{ALLOW edit " + LOGIN + "}].",
                "``[{ALLOW view Admin}]`` and then [{ALLOW edit " + LOGIN + "}]",
                "```\n[{ALLOW view Admin}]\n```\n[{ALLOW edit " + LOGIN + "}]\n",
                "~~~\n[{ALLOW view Admin}]\n~~~\n[{ALLOW edit " + LOGIN + "}]\n",
                "[{ALLOW edit " + LOGIN + "}]\n```\n[{ALLOW view Admin}]\n```\n",
                "[{ALLOW edit " + LOGIN + "}]()\n[{ALLOW view All}]()\n# Title\n",
                "[{ALLOW edit Admin}]()[{ALLOW view All}]()[{ALLOW edit " + LOGIN + "}]()",
                "---\ntitle: T\ncluster: c\n---\n[{ALLOW view All}]\n[{ALLOW edit " + LOGIN + "}]\n\n# T\n\nBody.\n",
                "[{ALLOW edit\n" + LOGIN + "}]",
                "[{ALLOW edit a}b" + LOGIN + "}]",
                // Lookalikes of code that is NOT code: the directive is live and must go.
                "    echo `date\n[{ALLOW edit " + LOGIN + "}] and `x`",
                "    ```\n[{ALLOW edit " + LOGIN + "}]\n    ```\n",
                "a ` b\n\n[{ALLOW edit " + LOGIN + "}]\n\nc ` d",
                "\\`[{ALLOW edit " + LOGIN + "}]\\`",
                "[{ALLOW edit " + LOGIN + "}]\r\n# Title\r\nBody.\r\n" );
    }

    @ParameterizedTest
    @MethodSource( "bodiesWithEnforcedDirectives" )
    void nothingTheAclManagerEnforcesSurvivesStripping( final String body ) {
        final List< String > enforced = DefaultAclManager.aclDirectives( body );
        assertFalse( enforced.isEmpty(), "fixture is vacuous: the ACL manager enforces nothing in: " + body );

        final String stripped = AclDirectives.strip( body );

        for ( final String directive : enforced ) {
            assertFalse( stripped.contains( directive ), "directive survived: " + directive + " in " + stripped );
        }
        assertEquals( List.of(), DefaultAclManager.aclDirectives( stripped ),
                "the ACL manager still finds a directive after stripping: " + stripped );
        assertFalse( stripped.contains( LOGIN ), "the login survived: " + stripped );
    }

    @ParameterizedTest
    @MethodSource( "bodiesWithEnforcedDirectives" )
    void strippingTouchesNothingExceptDirectives( final String body ) {
        // Rebuild the expected text by deleting only the directives the ACL manager reported (plus an
        // immediately trailing "()"); every fixture gives its directives distinct text from any code
        // sample beside them, so the first occurrence is the enforced one.
        String expected = body;
        for ( final String directive : DefaultAclManager.aclDirectives( body ) ) {
            final int at = expected.indexOf( directive );
            final int end = at + directive.length();
            final int withParens = TRAILING_EMPTY_PARENS.matcher( expected.substring( end ) ).find() ? end + 2 : end;
            expected = expected.substring( 0, at ) + expected.substring( withParens );
        }
        assertEquals( expected, AclDirectives.strip( body ) );
    }

    /**
     * The one deliberate divergence in the cautious direction: an unclosed fence makes the renderer treat
     * the rest of the page as code, so the ACL manager enforces nothing there, but {@code strip} does not
     * trust a fence that never closes and removes the directive anyway.
     */
    @Test
    void anUnclosedFenceIsHiddenEvenThoughTheAclManagerIgnoresIt() {
        final String body = "```\ncode\n[{ALLOW edit " + LOGIN + "}]\nan unclosed fence";
        assertEquals( List.of(), DefaultAclManager.aclDirectives( body ) );
        assertEquals( "```\ncode\n\nan unclosed fence", AclDirectives.strip( body ) );
    }

    /** Removing the inner directive joins "[{ALLOW vi" and "ew a}]" into another one, which must go too. */
    @Test
    void removalCannotSpliceAFreshDirectiveTogether() {
        final String body = "[{ALLOW vi[{ALLOW view " + LOGIN + "}]ew a}]";
        assertEquals( List.of( "[{ALLOW view " + LOGIN + "}]" ), DefaultAclManager.aclDirectives( body ) );
        final String stripped = AclDirectives.strip( body );
        assertEquals( "", stripped );
        assertEquals( List.of(), DefaultAclManager.aclDirectives( stripped ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "comment", "delete", "edit", "modify", "rename", "upload", "view" } )
    void everyPermissionTheAclManagerAcceptsIsStripped( final String action ) {
        final String body = "[{ALLOW " + action + " " + LOGIN + "}]";
        assertEquals( List.of( body ), DefaultAclManager.aclDirectives( body ) );
        assertEquals( "", AclDirectives.strip( body ) );
    }

    /** Documentation the ACL manager ignores is documentation {@code strip} keeps, unchanged. */
    @ParameterizedTest
    @ValueSource( strings = {
            "Use `[{ALLOW view Admin}]` to restrict.",
            "Code: ``[{ALLOW view Admin}]`` end.",
            "- ... enforced across REST, MCP, and the UI; inline `[{ALLOW view Admin}]` ACLs in page bodies.",
            "```\n[{ALLOW view Admin}]\n```\n",
            "~~~\n[{ALLOW view Admin}]\n[{ALLOW edit Bob}]\n~~~\n",
            "```\n[{ALLOW view Admin}]\n[{ALLOW edit Bob}]\n```",
            "Just prose with a [link](x) and `code`, no rules.",
    } )
    void codeSamplesTheAclManagerIgnoresAreKept( final String body ) {
        assertEquals( List.of(), DefaultAclManager.aclDirectives( body ), "fixture must be ignored by the ACL manager" );
        assertEquals( body, AclDirectives.strip( body ) );
    }

    /**
     * The same two properties over the real shipped corpus — about twelve hundred pages, several of which
     * document or use access rules. Skipped when the corpus is not on disk (a module built outside the repo).
     */
    @Test
    void shippedCorpusHonoursTheInvariant() throws IOException {
        final Path corpus = Path.of( "..", "docs", "wikantik-pages" );
        Assumptions.assumeTrue( Files.isDirectory( corpus ), "corpus not found at " + corpus.toAbsolutePath() );

        int withDirectives = 0;
        try ( Stream< Path > files = Files.list( corpus ) ) {
            for ( final Path file : files.filter( f -> f.toString().endsWith( ".md" ) ).sorted().toList() ) {
                final String body = Files.readString( file, StandardCharsets.UTF_8 );
                final List< String > enforced = DefaultAclManager.aclDirectives( body );
                final String stripped = AclDirectives.strip( body );
                assertEquals( List.of(), DefaultAclManager.aclDirectives( stripped ),
                        "directive survived stripping in " + file.getFileName() );
                if ( !enforced.isEmpty() ) {
                    withDirectives++;
                    for ( final String directive : enforced ) {
                        assertFalse( stripped.contains( directive ), file.getFileName() + " still has " + directive );
                    }
                }
            }
        }
        assertTrue( withDirectives >= 1, "expected at least one shipped page with a live directive (RejectedMessage)" );
    }
}
``````

**Placeholder so the tests compile** — `wikantik-util/src/main/java/com/wikantik/util/AclDirectives.java` (new; replaced wholesale in Step 3). It makes the red a behavioural one (and lets `-am` builds get past `wikantik-util`'s test-compile):

```java
package com.wikantik.util;

public final class AclDirectives {

    private AclDirectives() {
    }

    public static String strip( final String markdown ) {
        return markdown;
    }

    public static String strip( final String markdown, final String replacement ) {
        return markdown;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
unset ${!WIKANTIK_@}
mvn -B -o test -pl wikantik-util -Dtest='AclDirectivesTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: **38 of 57** fail — every test that expects a directive to be removed or replaced, plus `nullReplacementIsRejected`; the 19 "kept byte for byte" tests pass against the do-nothing placeholder, which is what they should do.

```bash
mvn -B -o test -pl wikantik-main -am -Dtest='SummaryExtractorTest,PageExcerptsTest,AclDirectivesParityTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```
Expected: 58 failures, all behavioural —
- `SummaryExtractorTest.stripsAnAccessRuleWhosePrincipalListContainsABrace`: `expected: <false> but was: <true>` (the summary still holds `[{ALLOW edit alice@example.test}x}] This page explains…`);
- `PageExcerptsTest.dropsTheEmptyParenthesesOfANormalisedAccessRule`: `expected: <Hello there.> but was: <() Hello there.>`;
- `AclDirectivesParityTest`: 56 of its 63 invocations (`directive survived: …`) — only the seven "documentation the ACL manager ignores is kept" cases pass against a do-nothing `strip`.

(`-am` matters: `-pl wikantik-main` alone resolves `wikantik-util` from `~/.m2`, which has no `AclDirectives` until you run `mvn -B -o install -pl wikantik-util -DskipTests`. The same applies to every downstream run in Tasks 18 and 19.)

- [ ] **Step 3: Implement**

Replace the placeholder with the real class.

**`wikantik-util/src/main/java/com/wikantik/util/AclDirectives.java`**:

``````java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes page access-rule directives ({@code [{ALLOW view Alice}]}, {@code [{DENY edit Bob}]}) from
 * raw page text, so the people an access rule names are not disclosed to readers who may not edit the
 * page, and are not carried into search snippets, retrieval chunks or embeddings.
 *
 * <p>What counts as a directive mirrors {@code DefaultAclManager} in {@code wikantik-main}, which this
 * module cannot depend on: the {@code [{ALLOW <permission> <principals>}]} span, where
 * {@code <permission>} is one of the seven page permissions. The scan is code-aware in the same way —
 * the identical text inside a fenced code block or an inline code span is documentation, not a rule,
 * and is kept. {@code DefaultAclManager} (and the renderer) also recognise the {@code [{DENY …}]} form
 * as an access rule, so it is removed too, and the spelling that plugin normalisation produces
 * ({@code [{ALLOW view All}]()}) is removed together with its empty {@code ()}.
 *
 * <p>Everything else is returned byte-for-byte: other {@code [{Plugin}]} spans, line endings,
 * whitespace around a removed directive. Only the directive itself (plus an immediately trailing
 * {@code ()}) is removed — a directive on its own line leaves an empty line behind.
 *
 * <p><b>Fail-closed bias.</b> Code detection is a small purpose-built scanner (this module carries no
 * Markdown parser) that only trusts what it can be certain of: a <em>closed</em> fenced block whose
 * fences have at most three spaces of indent, and an inline backtick span that opens and closes on one
 * line. Anything else — an unclosed fence, a span that wraps onto another line, an indented code
 * block, a fence nested deeper in a list — is treated as ordinary text, so a directive there is
 * removed. That can only hide a documentation example from a reader; it can never expose a real rule.
 *
 * <p>Removing a directive can in principle splice the text either side of it into a new one
 * ({@code [{ALLOW vi[{ALLOW view a}]ew a}]}), so the scan repeats until nothing more matches (bounded).
 */
public final class AclDirectives {

    /** The page permissions an access rule can name — the action constants of {@code PagePermission}. */
    private static final String PERMISSIONS = "(?:comment|delete|edit|modify|rename|upload|view)";

    /**
     * {@code DefaultAclManager.ACL_PATTERN} with {@code DENY} added and an optional trailing {@code ()}.
     * The part after the permission (optional whitespace, a lazy run of anything, optional whitespace,
     * then the closing brace and bracket) is deliberately identical, so both sides agree on where a
     * directive ends.
     */
    private static final Pattern DIRECTIVE = Pattern.compile(
            "\\[\\{\\s*(?:ALLOW|DENY)\\s+" + PERMISSIONS + "\\s*(?:.*?)\\s*\\}\\](?:\\(\\))?" );

    /** Upper bound on re-scans after a removal; real pages converge in one. */
    private static final int MAX_PASSES = 8;

    private AclDirectives() {
    }

    /**
     * Removes every access-rule directive outside code from {@code markdown}.
     *
     * @param markdown raw page text (frontmatter included is fine), may be {@code null}
     * @return the text without directives; the same instance when there was nothing to remove;
     *         {@code null} when {@code markdown} is {@code null}
     */
    public static String strip( final String markdown ) {
        return strip( markdown, "" );
    }

    /**
     * Like {@link #strip(String)}, but each removed directive is replaced by {@code replacement}
     * (for callers such as excerpt builders that must keep the words either side apart).
     *
     * @param markdown    raw page text, may be {@code null}
     * @param replacement text put in place of each directive; must be shorter than the shortest
     *                    directive and must not itself contain an opening <code>[&#123;</code>
     * @return the text with directives replaced; the same instance when there was nothing to replace
     */
    public static String strip( final String markdown, final String replacement ) {
        Objects.requireNonNull( replacement, "replacement" );
        if ( markdown == null || markdown.indexOf( "[{" ) < 0 ) {
            return markdown;
        }
        String current = markdown;
        for ( int pass = 0; pass < MAX_PASSES; pass++ ) {
            final String next = stripOnce( current, replacement );
            if ( next == null ) {
                break;
            }
            current = next;
        }
        return current;
    }

    /** One left-to-right pass; {@code null} when no directive outside code was found. */
    private static String stripOnce( final String text, final String replacement ) {
        final Matcher matcher = DIRECTIVE.matcher( text );
        if ( !matcher.find() ) {
            return null;
        }
        final List< int[] > code = codeRegions( text );
        final StringBuilder out = new StringBuilder( text.length() );
        int copiedTo = 0;
        boolean changed = false;
        do {
            if ( !insideCode( code, matcher.start() ) ) {
                out.append( text, copiedTo, matcher.start() ).append( replacement );
                copiedTo = matcher.end();
                changed = true;
            }
        } while ( matcher.find() );
        if ( !changed ) {
            return null;
        }
        return out.append( text, copiedTo, text.length() ).toString();
    }

    // ---------------------------------------------------------------- code regions

    /** {@code [start, end)} offsets of every closed fenced code block and one-line inline code span. */
    private static List< int[] > codeRegions( final String text ) {
        final List< int[] > regions = fencedBlocks( text );
        int lineStart = 0;
        while ( lineStart < text.length() ) {
            final int newline = text.indexOf( '\n', lineStart );
            final int lineEnd = newline < 0 ? text.length() : newline;
            spansInLine( text, lineStart, lineEnd, regions );
            lineStart = lineEnd + 1;
        }
        return regions;
    }

    private static boolean insideCode( final List< int[] > regions, final int offset ) {
        for ( final int[] region : regions ) {
            if ( offset >= region[ 0 ] && offset < region[ 1 ] ) {
                return true;
            }
        }
        return false;
    }

    /** A run of three or more {@code `} or {@code ~} that starts a line, and where it ends. */
    private record Marker( char ch, int length, int end ) {
    }

    /**
     * Fenced code blocks, CommonMark-style: an opening run of 3+ {@code `} or {@code ~} (at most three
     * spaces of indent; a backtick fence's info string may not contain a backtick) closed by a run of
     * the same character at least as long with nothing after it. An unclosed fence is not reported (see the
     * class comment). Each block spans from the start of its opening line to the end of its closing line.
     */
    private static List< int[] > fencedBlocks( final String text ) {
        final List< int[] > blocks = new ArrayList<>();
        final int length = text.length();
        Marker open = null;
        int openStart = 0;
        int lineStart = 0;
        while ( lineStart < length ) {
            final int newline = text.indexOf( '\n', lineStart );
            final int lineEnd = newline < 0 ? length : newline;
            final Marker marker = markerAt( text, lineStart, lineEnd );
            if ( open == null ) {
                if ( marker != null && opensFence( text, marker, lineEnd ) ) {
                    open = marker;
                    openStart = lineStart;
                }
            } else if ( marker != null && closesFence( text, open, marker, lineEnd ) ) {
                blocks.add( new int[]{ openStart, lineEnd } );
                open = null;
            }
            lineStart = lineEnd + 1;
        }
        return blocks;
    }

    private static Marker markerAt( final String text, final int lineStart, final int lineEnd ) {
        int i = lineStart;
        while ( i < lineEnd && i - lineStart < 4 && text.charAt( i ) == ' ' ) {
            i++;
        }
        if ( i - lineStart > 3 || i >= lineEnd ) {
            return null;
        }
        final char ch = text.charAt( i );
        if ( ch != '`' && ch != '~' ) {
            return null;
        }
        final int end = skipRun( text, i, lineEnd, ch );
        return end - i >= 3 ? new Marker( ch, end - i, end ) : null;
    }

    private static boolean opensFence( final String text, final Marker marker, final int lineEnd ) {
        if ( marker.ch() == '~' ) {
            return true;
        }
        final int backtick = text.indexOf( '`', marker.end() );
        return backtick < 0 || backtick >= lineEnd;
    }

    private static boolean closesFence( final String text, final Marker open, final Marker marker,
                                        final int lineEnd ) {
        return marker.ch() == open.ch()
                && marker.length() >= open.length()
                && text.substring( marker.end(), lineEnd ).isBlank();
    }

    /**
     * Inline code spans within the line {@code [from, to)}: a backtick run closed by the next run of
     * exactly the same length on the same line. A backslash makes the backtick after it literal. An
     * unmatched run is just text.
     */
    private static void spansInLine( final String text, final int from, final int to, final List< int[] > out ) {
        int i = from;
        while ( i < to ) {
            if ( text.charAt( i ) != '`' || isEscaped( text, i, from ) ) {
                i++;
                continue;
            }
            final int runEnd = skipRun( text, i, to, '`' );
            final int run = runEnd - i;
            final int close = closingRun( text, runEnd, to, run );
            if ( close < 0 ) {
                i = runEnd;
            } else {
                out.add( new int[]{ i, close + run } );
                i = close + run;
            }
        }
    }

    /** Start of the next backtick run of exactly {@code run} characters within {@code [from, to)}, else -1. */
    private static int closingRun( final String text, final int from, final int to, final int run ) {
        int j = from;
        while ( j < to ) {
            if ( text.charAt( j ) != '`' ) {
                j++;
                continue;
            }
            final int end = skipRun( text, j, to, '`' );
            if ( end - j == run ) {
                return j;
            }
            j = end;
        }
        return -1;
    }

    /** True when an odd number of backslashes (not reaching before {@code floor}) precede {@code index}. */
    private static boolean isEscaped( final String text, final int index, final int floor ) {
        int backslashes = 0;
        for ( int k = index - 1; k >= floor && text.charAt( k ) == '\\'; k-- ) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** Index just past the run of {@code ch} that starts at {@code start}, not going beyond {@code limit}. */
    private static int skipRun( final String text, final int start, final int limit, final char ch ) {
        int end = start;
        while ( end < limit && text.charAt( end ) == ch ) {
            end++;
        }
        return end;
    }
}
``````

**Delegation** — `SummaryExtractor` and `PageExcerpts` hand the ACL part to the utility (they keep their own pattern for every other `[{Plugin}]` span; the `" "` replacement keeps their output identical):

``````diff
--- a/wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java
+++ b/wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java
@@ -18,6 +18,8 @@
  */
 package com.wikantik.knowledge;
 
+import com.wikantik.util.AclDirectives;
+
 import java.util.regex.Pattern;
 
 /**
@@ -58,8 +60,9 @@
     public static String extract( final String body ) {
         if( body == null || body.isBlank() ) return "";
 
-        // Strip plugin calls first, before markdown stripping
-        String text = PLUGIN_CALL.matcher( body ).replaceAll( " " );
+        // Access-rule directives first, through the one place that knows their syntax (and keeps the words
+        // either side of one apart), then every other plugin call, before markdown stripping
+        String text = PLUGIN_CALL.matcher( AclDirectives.strip( body, " " ) ).replaceAll( " " );
 
         // Strip markdown (also strips frontmatter, headings, links, emphasis, etc.)
         text = NodeTextAssembler.stripMarkdown( text );
``````

``````diff
--- a/wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java
+++ b/wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java
@@ -29,6 +29,7 @@
 import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
 
 import com.wikantik.api.parser.WikiLinkSyntax;
+import com.wikantik.util.AclDirectives;
 
 import java.util.ArrayList;
 import java.util.List;
@@ -51,8 +52,10 @@
         if ( markdownBody == null || markdownBody.isBlank() ) {
             return "";
         }
+        // Access rules go first, through the one place that knows their syntax; PLUGIN then drops every other span.
+        final String withoutRules = AclDirectives.strip( markdownBody, " " );
         final String masked = WikiLinkSyntax.toPlainText(
-                CALLOUT_MARKER.matcher( PLUGIN.matcher( markdownBody ).replaceAll( " " ) ).replaceAll( "$1" ) );
+                CALLOUT_MARKER.matcher( PLUGIN.matcher( withoutRules ).replaceAll( " " ) ).replaceAll( "$1" ) );
         final StringBuilder text = new StringBuilder();
         for ( final Node block : PARSER.parse( masked ).getChildren() ) {
             if ( block instanceof Heading || block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock
``````

- [ ] **Step 4: Run the tests to verify they pass**

```bash
unset ${!WIKANTIK_@}
mvn -B -o test -pl wikantik-util -Dtest='AclDirectivesTest' -Dsurefire.failIfNoSpecifiedTests=false   # 57 tests
mvn -B -o test -pl wikantik-main -am -Dtest='SummaryExtractorTest,PageExcerptsTest,AclDirectivesParityTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DskipITs                                                 # 8 + 9 + 63 tests
```
`AclDirectivesParityTest.shippedCorpusHonoursTheInvariant` reads `../docs/wikantik-pages` relative to the module directory and is skipped by an `Assumption` if that directory is absent. Then the module suite and the complexity gate:

```bash
mvn -B -o test -pl wikantik-util                                 # whole util suite: ~408 tests (the 57 above included), 1 pre-existing skip
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-util         # BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add wikantik-util/src/main/java/com/wikantik/util/AclDirectives.java \
        wikantik-util/src/test/java/com/wikantik/util/AclDirectivesTest.java \
        wikantik-main/src/test/java/com/wikantik/auth/acl/AclDirectivesParityTest.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/SummaryExtractor.java \
        wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/SummaryExtractorTest.java \
        wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java
git commit -m "feat(util): AclDirectives.strip, the one place that removes [{ALLOW}]/[{DENY}] text

SummaryExtractor and PageExcerpts delegate to it; both had gaps (a brace in a principal list, the () of a normalised directive).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 18: Strip directives from raw page text for callers who cannot edit

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java:52,273,304-305,324`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/DiffResource.java:26,99-100,~137`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java:35,183,~193`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java:34,536`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java:26,166`
- Modify: `wikantik-tools/src/main/java/com/wikantik/tools/GetPageTool.java:27,96,125-131`
- Modify: `wikantik-main/src/main/java/com/wikantik/export/ExportZipBuilder.java:50,162`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/briefing/ItemAssembler.java:30,210-212`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java:31,223`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/AclDirectiveVisibilityTest.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionServiceAclDirectiveTest.java`
- Test (modify): `SpaRoutingFilterTest`, `wikantik-knowledge/.../mcp/ReadPagesToolTest`, `wikantik-tools/.../tools/GetPageToolTest`, `wikantik-main/.../export/ExportServiceTest`, `wikantik-main/.../knowledge/briefing/DefaultBriefingAssemblyServiceTest`

**Interfaces:**
- Consumes: `AclDirectives.strip(String)` (Task 17); `RestServletBase.hasPagePermission(request, page, "edit")` (silent, no audit row — wikantik-rest/.../RestServletBase.java:408); `PermissionFilter.canAccessQuietly(session, page, "edit")` (wikantik-main/.../auth/permissions/PermissionFilter.java:105).
- Produces: no new public API. Package-visible seam `boolean GetPageTool.canEdit(HttpServletRequest, String)` in `wikantik-tools`, mirroring `canView`, so unit tests can stub the gate. Behaviour: every surface in the table above returns raw page text without directives to callers who lack `edit`.

**Rule.** A caller holding `edit` on the page gets the text verbatim — the editor round-trips this very field (`PageEditor.jsx:380-385` loads `page.content` from `GET /api/pages/{name}` and saves it back, so a stripped editor payload would silently delete the page's ACL), and an editor can read the directives anyway; admins hold `AllPermission` and are editors everywhere. Everyone else gets `AclDirectives.strip(text)`. Surfaces with no per-caller identity strip unconditionally: the knowledge MCP runs request-free as a guest (`PageViewGate` javadoc), briefings and the agent projection are injected into third-party context, and the SSR document is the first-paint copy of the reader view.

**Why the SSR document strips for everyone.** `window.__WIKANTIK_PAGE__.content` is only a fallback for the reader view — `PageView.jsx:477` renders `page.contentHtml || page.content`, and the renderer already consumes the directives — while the editor re-fetches through `GET /api/pages/{name}`. Stripping unconditionally also keeps the document's ETag (`etagFor`: shell + version + mtime + viewAllowed, `SpaRoutingFilter.java`) valid; a caller-dependent body would need the edit bit in the validator. The head's `description` and the no-JS fallback come from the same parsed body, so they are covered by the one change.

**Why `contentHash` follows the same rule.** For an editor it must stay the hash of the full text (GET → PUT `expectedContentHash`). For a reader the hash of the *full* text would be an oracle: they know every byte of the page except the directives, so they could confirm a guessed `[{ALLOW edit alice@example.test}]` by hashing candidates. They get the hash of what they can see; they cannot PUT.

**Not changed, on purpose.** `HistoryResource` (no text), knowledge `get_page` (no body) — see the table. The Obsidian converter already drops `ALLOW`/`DENY` spans on its happy path (`ObsidianPageConverter.REMOVED_PLUGIN_NAMES`, :61), but with `[^}]*` (narrower than the ACL manager) and `ExportZipBuilder` writes the *raw* text when conversion throws — so the export strips first for non-editors. `PageResource` PATCH/PUT need `edit` and never return text.

- [ ] **Step 1: Write the failing tests**


**`wikantik-rest/src/test/java/com/wikantik/rest/AclDirectiveVisibilityTest.java`** (new) — page GET (current and `?version=`), `contentHash`, diff (structured and html), `?format=md|json`, each as reader and as editor:

``````java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.util.AclDirectives;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Access-rule directives ({@code [{ALLOW edit alice@example.test}]}) name people by login, so raw page
 * text reaches only callers who may edit the page — {@code GET /api/pages}, a diff and
 * {@code /wiki/Page?format=md|json}. Everyone else reads the page without them.
 *
 * <p>Fixture: view is open to {@code All}, edit to {@code Admin} and one named login. An anonymous reader can
 * therefore read but not edit; the admin session that {@code TestEngine.saveText} leaves on the shared mock
 * session id can do both. Requests from {@code HttpMockFactory.createHttpRequest} share that admin session
 * (the editor); {@code createIsolatedHttpRequest} gets a fresh anonymous one (the reader).
 */
class AclDirectiveVisibilityTest {

    private static final String LOGIN = "leak-probe@example.test";
    private static final String RULES = "[{ALLOW view All}]\n[{ALLOW edit Admin," + LOGIN + "}]\n";

    private static TestEngine engine;
    private static PageResource pageResource;
    private static DiffResource diffResource;
    private static WikiPageFormatFilter formatFilter;
    private final Gson gson = new Gson();

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        pageResource = RestTestSupport.initServlet( PageResource::new, engine );
        diffResource = RestTestSupport.initServlet( DiffResource::new, engine );
        formatFilter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        formatFilter.init( config );

        engine.saveText( "AclVisibilityPage",
                "---\ntitle: Acl Visibility\n---\n" + RULES + "# Heading\n\nPublic prose.\n" );
        engine.saveText( "AclDiffPage", RULES + "First revision.\n" );
        engine.saveText( "AclDiffPage",
                "[{ALLOW view All}]\n[{ALLOW edit Admin,other-" + LOGIN + "}]\nSecond revision.\n" );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.deleteQuietly( "AclVisibilityPage", "AclDiffPage" );
            engine.stop();
        }
    }

    private static HttpServletRequest reader( final String path ) {
        return HttpMockFactory.createIsolatedHttpRequest( path );
    }

    private static HttpServletRequest editor( final String path ) {
        return HttpMockFactory.createHttpRequest( path );
    }

    // ---- GET /api/pages/{name}

    private JsonObject getPage( final HttpServletRequest request, final String pageName ) throws Exception {
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        pageResource.doGet( request, response );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }

    @Test
    void readerGetsPageContentWithoutDirectives() throws Exception {
        final JsonObject page = getPage( reader( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" );

        final String content = page.get( "content" ).getAsString();
        assertFalse( content.contains( LOGIN ), content );
        assertFalse( content.contains( "[{ALLOW" ), content );
        assertTrue( content.contains( "Public prose." ), content );
        assertFalse( page.getAsJsonObject( "permissions" ).get( "edit" ).getAsBoolean(),
                "fixture is vacuous: the reader can edit the page" );
        assertTrue( page.getAsJsonObject( "metadata" ).get( "title" ).getAsString().contains( "Acl Visibility" ),
                "frontmatter is untouched" );
    }

    @Test
    void editorGetsPageContentWithDirectives() throws Exception {
        final JsonObject page = getPage( editor( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" );

        final String content = page.get( "content" ).getAsString();
        assertTrue( content.contains( "[{ALLOW edit Admin," + LOGIN + "}]" ),
                "an editor round-trips this text, so it must be verbatim: " + content );
        assertTrue( page.getAsJsonObject( "permissions" ).get( "edit" ).getAsBoolean() );
    }

    /**
     * The content hash is how an editor round-trips GET to PUT. A reader never PUTs, and a hash of the full text
     * would let them confirm a guess at a hidden directive by hashing candidate texts against it.
     */
    @Test
    void aReadersContentHashIsNotAnOracleForTheHiddenDirectives() throws Exception {
        final String raw = engine.getManager( PageManager.class )
                .getPureText( "AclVisibilityPage", PageProvider.LATEST_VERSION );
        final String readerHash = getPage( reader( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" )
                .get( "contentHash" ).getAsString();
        final String editorHash = getPage( editor( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" )
                .get( "contentHash" ).getAsString();

        assertEquals( PageSaveHelper.computeContentHash( raw ), editorHash, "an editor gets the hash of the full text" );
        assertEquals( PageSaveHelper.computeContentHash( AclDirectives.strip( raw ) ), readerHash,
                "a reader gets the hash of what they can see" );
        assertNotEquals( editorHash, readerHash );
    }

    @Test
    void readerGetsAnOldVersionWithoutDirectives() throws Exception {
        final HttpServletRequest request = reader( "/api/pages/AclDiffPage" );
        Mockito.doReturn( "1" ).when( request ).getParameter( "version" );

        final JsonObject page = getPage( request, "AclDiffPage" );

        final String content = page.get( "content" ).getAsString();
        assertFalse( content.contains( LOGIN ), content );
        assertTrue( content.contains( "First revision." ), content );
    }

    // ---- GET /api/diff/{name}

    private String getDiff( final HttpServletRequest request, final String format ) throws Exception {
        Mockito.doReturn( "/AclDiffPage" ).when( request ).getPathInfo();
        Mockito.doReturn( "1" ).when( request ).getParameter( "from" );
        Mockito.doReturn( "2" ).when( request ).getParameter( "to" );
        if ( format != null ) {
            Mockito.doReturn( format ).when( request ).getParameter( "format" );
        }
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        diffResource.doGet( request, response );
        return sw.toString();
    }

    @Test
    void readerDiffCarriesNoDirectives() throws Exception {
        final String structured = getDiff( reader( "/api/diff/AclDiffPage" ), null );
        assertFalse( structured.contains( LOGIN ), structured );
        assertTrue( structured.contains( "Second revision." ), structured );

        final String html = getDiff( reader( "/api/diff/AclDiffPage" ), "html" );
        assertFalse( html.contains( LOGIN ), html );
    }

    @Test
    void editorDiffShowsTheDirectiveThatChanged() throws Exception {
        final String structured = getDiff( editor( "/api/diff/AclDiffPage" ), null );
        assertTrue( structured.contains( LOGIN ), structured );
    }

    // ---- /wiki/{name}?format=md|json

    private String getFormat( final HttpServletRequest request, final String pageName, final String format )
            throws Exception {
        Mockito.doReturn( format ).when( request ).getParameter( "format" );
        Mockito.doReturn( "/wiki/" + pageName ).when( request ).getRequestURI();
        Mockito.doReturn( "" ).when( request ).getContextPath();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Mockito.doReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { bytes.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) { }
        } ).when( response ).getOutputStream();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        formatFilter.doFilter( request, response, Mockito.mock( FilterChain.class ) );
        return bytes.toString( StandardCharsets.UTF_8 ) + sw;
    }

    @Test
    void readerRawMarkdownCarriesNoDirectives() throws Exception {
        final String md = getFormat( reader( "/wiki/AclVisibilityPage" ), "AclVisibilityPage", "md" );
        assertTrue( md.contains( "Public prose." ), md );
        assertFalse( md.contains( LOGIN ), md );
    }

    @Test
    void editorRawMarkdownKeepsDirectives() throws Exception {
        final String md = getFormat( editor( "/wiki/AclVisibilityPage" ), "AclVisibilityPage", "md" );
        assertTrue( md.contains( LOGIN ), md );
    }

    @Test
    void readerJsonCarriesNoDirectivesInContentOrInTheDerivedSummary() throws Exception {
        // No frontmatter summary on this page, so the summary is the body's first paragraph — which,
        // with the directives still in it, is the directives.
        final JsonObject json = gson.fromJson(
                getFormat( reader( "/wiki/AclVisibilityPage" ), "AclVisibilityPage", "json" ), JsonObject.class );
        assertFalse( json.get( "content" ).getAsString().contains( LOGIN ), json.toString() );
        assertFalse( json.get( "summary" ).getAsString().contains( LOGIN ), json.toString() );
        assertTrue( json.get( "summary" ).getAsString().contains( "Public prose." ), json.toString() );
    }
}
``````

**`SpaRoutingFilterTest`** — the server-rendered `/wiki/Page` document (head, no-JS fallback, `window.__WIKANTIK_PAGE__` island):

``````diff
--- a/wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java
+++ b/wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java
@@ -835,6 +835,31 @@
     }
 
     /**
+     * The server-rendered document carries page text in three places besides the rendered body — the semantic head,
+     * the no-JS fallback and the {@code window.__WIKANTIK_PAGE__} data island, whose {@code content} is the raw body.
+     * Access-rule directives name people by login and must be in none of them.
+     */
+    @Test
+    void ssrCarriesNoAccessRuleDirectives() throws Exception {
+        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
+        try {
+            engine.saveText( "SpaRuledPage",
+                    "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\nPUBLIC_RULED_MARKER is freely readable." );
+            filter.setEngineForTest( engine );
+
+            filter.doFilter( mockRequest( "/wiki/SpaRuledPage" ), response, chain );
+
+            final String html = capturedOutput.asString();
+            assertTrue( html.contains( "PUBLIC_RULED_MARKER" ), "the page is still server-rendered" );
+            assertTrue( html.contains( "__WIKANTIK_PAGE__" ), "the data island is present" );
+            assertFalse( html.contains( "leak-probe@example.test" ),
+                    "no login may appear in the head, the fallback body or the data island" );
+        } finally {
+            engine.stop();
+        }
+    }
+
+    /**
      * Guards the fix from over-correcting into "never server-render": an unrestricted
      * page must still get its body injected, which is the whole point of the SSR path.
      */
``````

**`ReadPagesToolTest`** (knowledge MCP `read_pages`):

``````diff
--- a/wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ReadPagesToolTest.java
+++ b/wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ReadPagesToolTest.java
@@ -167,4 +167,18 @@
         verify( pageManager ).getPageWithoutMetadata( eq( "PageA" ), anyInt() );
         verify( pageManager, never() ).getPage( eq( "PageA" ), anyInt() );
     }
+
+    @Test
+    void accessRuleDirectivesAreNotReturnedToTheGuestCaller() throws Exception {
+        when( pageManager.getPageWithoutMetadata( eq( "RuledPage" ), anyInt() ) ).thenReturn( mock( Page.class ) );
+        when( pageManager.getPureText( eq( "RuledPage" ), anyInt() ) ).thenReturn(
+                "---\ntitle: Ruled\n---\n[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\nBody of ruled page." );
+
+        final var result = tool.execute( Map.of( "slugs", List.of( "RuledPage" ) ) );
+
+        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
+        assertFalse( json.contains( "leak-probe@example.test" ), json );
+        assertTrue( json.contains( "Body of ruled page." ), json );
+        assertTrue( json.contains( "title: Ruled" ), "the frontmatter is still returned" );
+    }
 }
``````

**`GetPageToolTest`** (`/tools` `get_page`) — the `allowingTool` helper gains a `canEdit` override (false), a sibling `editingTool` is added, and two tests use them:

``````diff
--- a/wikantik-tools/src/test/java/com/wikantik/tools/GetPageToolTest.java
+++ b/wikantik-tools/src/test/java/com/wikantik/tools/GetPageToolTest.java
@@ -57,6 +57,26 @@
             boolean canView( final HttpServletRequest request, final String pageName ) {
                 return true;
             }
+
+            @Override
+            boolean canEdit( final HttpServletRequest request, final String pageName ) {
+                return false;
+            }
+        };
+    }
+
+    /** Like {@link #allowingTool}, but the caller may also edit every page. */
+    private GetPageTool editingTool( final WikiEngine engine, final ToolsConfig config ) {
+        return new GetPageTool( engine, config ) {
+            @Override
+            boolean canView( final HttpServletRequest request, final String pageName ) {
+                return true;
+            }
+
+            @Override
+            boolean canEdit( final HttpServletRequest request, final String pageName ) {
+                return true;
+            }
         };
     }
 
@@ -200,4 +220,33 @@
                 "ACL check should run against the resolved page name, not the request alias, "
                         + "so rename aliases can't bypass ACLs." );
     }
+
+    @Test
+    void accessRuleDirectivesAreHiddenFromACallerWhoCannotEdit() {
+        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
+        when( pageManager.getPage( "Ruled" ) ).thenReturn( page );
+        when( page.getName() ).thenReturn( "Ruled" );
+        when( pageManager.getPureText( "Ruled", -1 ) ).thenReturn(
+                "---\nsummary: S\n---\n[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\nRuled body." );
+
+        final Map< String, Object > out = allowingTool( engine, new ToolsConfig( new Properties() ) )
+                .execute( "Ruled", 0, request );
+
+        assertFalse( out.get( "text" ).toString().contains( "leak-probe@example.test" ), out.toString() );
+        assertTrue( out.get( "text" ).toString().contains( "Ruled body." ) );
+    }
+
+    @Test
+    void accessRuleDirectivesAreKeptForACallerWhoCanEdit() {
+        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
+        when( pageManager.getPage( "Ruled" ) ).thenReturn( page );
+        when( page.getName() ).thenReturn( "Ruled" );
+        when( pageManager.getPureText( "Ruled", -1 ) ).thenReturn(
+                "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\nRuled body." );
+
+        final Map< String, Object > out = editingTool( engine, new ToolsConfig( new Properties() ) )
+                .execute( "Ruled", 0, request );
+
+        assertTrue( out.get( "text" ).toString().contains( "leak-probe@example.test" ), out.toString() );
+    }
 }
``````

**`ExportServiceTest`** (Obsidian export; needs Docker like the rest of that class):

``````diff
--- a/wikantik-main/src/test/java/com/wikantik/export/ExportServiceTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/export/ExportServiceTest.java
@@ -169,6 +169,23 @@
         return new String( bytes, StandardCharsets.UTF_8 );
     }
 
+    /**
+     * The converter drops ALLOW/DENY spans with a narrower pattern than the ACL manager's (it stops at the first
+     * closing brace, whereas the manager reads "[{ALLOW edit Admin,x}y}]" as one rule), and its failure fallback
+     * writes raw text. A caller who cannot edit a page therefore never gets the directives to convert at all.
+     */
+    @Test
+    void exportOmitsAccessRuleDirectivesTheConverterDoesNotRecognise() throws Exception {
+        engine.saveText( "FinanceRuled", "---\ntype: article\ncluster: finance\n---\n"
+                + "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}x}]\n# Ruled\n\nOpen prose.\n" );
+        subs.pageGraph().structuralIndexService().rebuild();
+
+        final String page = exportedPage( finance( 0 ), "finance/FinanceRuled.md" );
+
+        assertTrue( page.contains( "Open prose." ), page );
+        assertFalse( page.contains( "leak-probe@example.test" ), page );
+    }
+
     @Test
     void liveUrlUsesPathSegmentEncoding() throws Exception {
         engine.saveText( "FinanceSpaceLink", "---\ntype: article\ncluster: finance\n---\n"
``````

**`DefaultBriefingAssemblyServiceTest`** (`/api/briefing`, `get_briefing`):

``````diff
--- a/wikantik-main/src/test/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyServiceTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyServiceTest.java
@@ -187,6 +187,24 @@
     }
 
     @Test
+    void pinnedBodyCarriesNoAccessRuleDirectives() {
+        final PageManager pm = pageManagerFixture();
+        stub( pm, "RuledPage", "---\ntitle: Ruled\nsummary: Ruled summary.\n---\n"
+            + "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\nBody of the ruled page.\n" );
+        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
+            q -> new ContextBundle( q, List.of() ), new StubIndex(), pm, DEFAULT_BUDGET, MAX_BUDGET );
+
+        final ContextBriefing b = svc.assemble(
+            new BriefingRequest( List.of( "RuledPage" ), null, null, null, null ) );
+
+        final BriefingItem it = itemBySlug( b, "RuledPage" );
+        assertTrue( it != null && it.included(), "the pin is included" );
+        assertFalse( it.content().contains( "leak-probe@example.test" ), it.content() );
+        assertTrue( it.content().contains( "Body of the ruled page." ) );
+        assertEquals( "Ruled", it.title(), "frontmatter is still read" );
+    }
+
+    @Test
     void pinDegradesToPointerWhenBudgetExhausted() {
         final PageManager pm = pageManagerFixture();
         stub( pm, "BigPage", page( "Big Page", "Big summary.", 2000 ) );  // ~500 tokens
``````

**`DefaultForAgentProjectionServiceAclDirectiveTest`** (new; `/api/pages/for-agent/{id}`, `get_page_for_agent`) — a separate class so it cannot collide with the author/`recent_changes` edits Task 9 makes to `DefaultForAgentProjectionServiceTest`:

``````java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code key_facts} are cut from the body's first paragraphs when the page authors none, and the first paragraph of
 * a page that opens with access-rule directives is the directives plus the line after them. The projection is served
 * to anonymous agents, and a directive names people by login.
 */
class DefaultForAgentProjectionServiceAclDirectiveTest {

    @Test
    void bodyDerivedKeyFactsCarryNoAccessRuleDirectives() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        final PageManager pm = mock( PageManager.class );
        final CachingManager cache = mock( CachingManager.class );
        when( cache.enabled( anyString() ) ).thenReturn( false );
        final DefaultForAgentProjectionService svc =
                new DefaultForAgentProjectionService( idx, pm, cache, new ForAgentMetrics(), null, null, null );

        final PageDescriptor d = new PageDescriptor( "01RULED", "RuledOps", "Ruled Ops", PageType.ARTICLE,
                "ops", List.of( "ops" ), "Operator notes.",
                Instant.parse( "2026-04-22T11:10:00Z" ), Optional.empty(), false );
        when( idx.getByCanonicalId( "01RULED" ) ).thenReturn( Optional.of( d ) );
        when( pm.getPureText( "RuledOps", -1 ) ).thenReturn(
                "---\ntitle: Ruled Ops\nsummary: Operator notes.\ntags: [ops]\n---\n"
                + "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\n"
                + "The service restarts nightly at 02:00 and retains 7 backups.\n\n## Steps\n\nRun the restart script.\n" );

        final ForAgentProjection p = svc.project( "01RULED" ).orElseThrow();

        assertFalse( p.keyFacts().isEmpty(), "the body still yields key facts" );
        p.keyFacts().forEach( f -> assertFalse( f.text().contains( "leak-probe@example.test" ), f.text() ) );
        assertEquals( "The service restarts nightly at 02:00 and retains 7 backups.", p.keyFacts().get( 0 ).text() );
        assertTrue( p.headingsOutline().stream().anyMatch( h -> "Steps".equals( h.text() ) ), "headings are untouched" );
    }
}
``````

- [ ] **Step 2: Run them to verify they fail**

```bash
unset ${!WIKANTIK_@}
mvn -B -o test -pl wikantik-main,wikantik-rest,wikantik-knowledge,wikantik-tools -am \
  -Dtest='AclDirectiveVisibilityTest,SpaRoutingFilterTest,ReadPagesToolTest,GetPageToolTest,ExportServiceTest,DefaultBriefingAssemblyServiceTest,DefaultForAgentProjectionServiceAclDirectiveTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipITs -fae -Dmaven.test.failure.ignore=true
```
Expected (each is a real defect today):
- `AclDirectiveVisibilityTest`: **6 of 9** fail — `readerGetsPageContentWithoutDirectives`, `readerGetsAnOldVersionWithoutDirectives`, `aReadersContentHashIsNotAnOracleForTheHiddenDirectives`, `readerDiffCarriesNoDirectives`, `readerRawMarkdownCarriesNoDirectives`, `readerJsonCarriesNoDirectivesInContentOrInTheDerivedSummary` (the JSON `summary` is literally `[{ALLOW view All}] [{ALLOW edit Admin,leak-probe@example.test}]`). The three `editor…` tests pass already — they are the guard against over-stripping.
- `SpaRoutingFilterTest.ssrCarriesNoAccessRuleDirectives`, `ReadPagesToolTest.accessRuleDirectivesAreNotReturnedToTheGuestCaller`, `DefaultBriefingAssemblyServiceTest.pinnedBodyCarriesNoAccessRuleDirectives`: `expected: <false> but was: <true>` on the login.
- `DefaultForAgentProjectionServiceAclDirectiveTest.bodyDerivedKeyFactsCarryNoAccessRuleDirectives`: the first key fact starts with the two directive lines and carries on into `The service restarts nightly…`.
- `ExportServiceTest.exportOmitsAccessRuleDirectivesTheConverterDoesNotRecognise` fails (needs Docker — without it the whole class is skipped with a visible reason locally and fails under `-Dtests.requireDocker=true`).
- `wikantik-tools`: the test module does not compile — `GetPageToolTest` overrides `canEdit`, which does not exist yet. That is this task's red for `/tools`.

- [ ] **Step 3: Implement**

Each hunk is shown as a diff against HEAD (line numbers in the `@@` headers are HEAD's). One decision runs through all of them: the stripped text is computed **once, at the point the raw text is read**, so nothing downstream (summary derivation, hash, diff, island) can see the directives.


**`PageResource.doGet`** — `content` and `contentHash` follow `canEdit`; the `permissions.edit` flag reuses the same value (one permission lookup, as before):

``````diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java
@@ -49,6 +49,7 @@
 import com.wikantik.content.PageRenamer;
 import com.wikantik.export.HeadingSlugs;
 import com.wikantik.preview.PageExcerpts;
+import com.wikantik.util.AclDirectives;
 import com.wikantik.util.TextUtil;
 import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
 import com.wikantik.content.WikiToMarkdownConverter;
@@ -268,9 +269,13 @@
 
         final ParsedPage parsed = FrontmatterParser.parse( rawText );
 
+        // Access-rule directives ([{ALLOW edit alice@…}]) name people by login. Whoever may edit the page
+        // gets its text verbatim — the editor round-trips this very field — everyone else reads it without them.
+        final boolean canEdit = hasPagePermission( request, pageName, "edit" );
+
         final Map< String, Object > result = new LinkedHashMap<>();
         result.put( "name", page.getName() );
-        result.put( "content", parsed.body() );
+        result.put( "content", canEdit ? parsed.body() : AclDirectives.strip( parsed.body() ) );
 
         // Rendered HTML option
         if ( render ) {
@@ -301,8 +306,11 @@
 
         // D19: surface the content hash so clients can round-trip GET → PUT with
         // expectedContentHash without computing the digest themselves.
-        final String contentHash =
-                com.wikantik.api.pages.PageSaveHelper.computeContentHash( rawText );
+        // An editor round-trips GET → PUT with the hash of the full text. A reader never PUTs, and the hash of the
+        // full text would let them confirm a guess at a hidden directive by hashing candidate texts — so they get
+        // the hash of what they can see.
+        final String contentHash = com.wikantik.api.pages.PageSaveHelper.computeContentHash(
+                canEdit ? rawText : AclDirectives.strip( rawText ) );
         result.put( "contentHash", contentHash );
 
         // Include markup syntax — "wiki" for legacy .txt pages, "markdown" for .md pages,
@@ -321,7 +329,7 @@
 
         // Include the current user's effective permissions for this page
         final Map< String, Boolean > permissions = new LinkedHashMap<>();
-        permissions.put( "edit", hasPagePermission( request, pageName, "edit" ) );
+        permissions.put( "edit", canEdit );
         permissions.put( "comment", hasPagePermission( request, pageName, "comment" ) );
         permissions.put( "upload", hasPagePermission( request, pageName, "upload" ) );
         permissions.put( "rename", hasPagePermission( request, pageName, "rename" ) );
``````

**`DiffResource`** — both versions go through `forReader` before the structured or html diff is computed:

``````diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/DiffResource.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/DiffResource.java
@@ -24,6 +24,7 @@
 import com.wikantik.api.managers.PageManager;
 import com.wikantik.api.spi.Wiki;
 import com.wikantik.diff.DifferenceManager;
+import com.wikantik.util.AclDirectives;
 
 import jakarta.servlet.ServletException;
 import jakarta.servlet.http.HttpServletRequest;
@@ -96,8 +97,10 @@
         }
 
         try {
-            final String fromText = pm.getPureText( pageName, fromVersion );
-            final String toText = pm.getPureText( pageName, toVersion );
+            // Access-rule directives name people by login: only someone who may edit the page sees them in a diff.
+            final boolean canEdit = hasPagePermission( request, pageName, "edit" );
+            final String fromText = forReader( canEdit, pm.getPureText( pageName, fromVersion ) );
+            final String toText = forReader( canEdit, pm.getPureText( pageName, toVersion ) );
 
             // D31: structured diff format. The legacy DifferenceManager renders an HTML
             // table which is hostile to JSON consumers. Default to the structured form;
@@ -133,6 +136,11 @@
         }
     }
 
+    /** Page text as the caller may see it: with access-rule directives (people, by login) only if they can edit. */
+    private static String forReader( final boolean canEdit, final String text ) {
+        return canEdit ? text : AclDirectives.strip( text );
+    }
+
     /**
      * D31: produces a JSON-friendly structured diff: line-level lists of added and
      * removed strings. This is intentionally simple — it does not compute longest
``````

**`WikiPageFormatFilter`** — `?format=md|json`; stripping the body also fixes the derived `summary` (the first paragraph of a page that opens with directives *is* the directives). The logic is in a helper because `doFilter` sits exactly at the PMD cognitive-complexity ceiling (20):

``````diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java
@@ -32,6 +32,7 @@
 import com.wikantik.api.managers.PageManager;
 import com.wikantik.api.spi.Wiki;
 import com.wikantik.page.subsystem.PageSubsystemBridge;
+import com.wikantik.util.AclDirectives;
 import com.wikantik.util.BaseUrlResolver;
 
 import jakarta.servlet.Filter;
@@ -180,7 +181,8 @@
         firePageRequested( eng, page.getName() );
 
         final String rawText = pm.getPureText( page );
-        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
+        final ParsedPage parsed = readableBy( eng, session, pageName,
+                FrontmatterParser.parse( rawText == null ? "" : rawText ) );
         final String baseUrl = BaseUrlResolver.resolve( eng, req, null );
 
         if ( "md".equalsIgnoreCase( format ) ) {
@@ -191,6 +193,18 @@
     }
 
     /**
+     * The page as this caller may read it. Access-rule directives name people by login, so only a caller who may
+     * edit the page gets them in the raw text — the summary derived from the body included.
+     */
+    private static ParsedPage readableBy( final Engine eng, final com.wikantik.api.core.Session session,
+                                          final String pageName, final ParsedPage full ) {
+        if ( new com.wikantik.auth.permissions.PermissionFilter( eng ).canAccessQuietly( session, pageName, "edit" ) ) {
+            return full;
+        }
+        return new ParsedPage( full.metadata(), AclDirectives.strip( full.body() ) );
+    }
+
+    /**
      * Mark a raw-format (md/json) response as {@code noindex} and point crawlers
      * at the canonical {@code /wiki/} HTML page via a {@code Link} header, so the
      * raw representations (intended for RAG/agents) are not indexed as duplicate
``````

**`SpaRoutingFilter.injectSemantic`** — strips for everyone (no editor can need them from this document, see the task text):

``````diff
--- a/wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java
+++ b/wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java
@@ -31,6 +31,7 @@
 import com.wikantik.render.RenderingManager;
 import com.wikantik.render.subsystem.RenderingSubsystemBridge;
 import com.wikantik.ui.SemanticHeadRenderer;
+import com.wikantik.util.AclDirectives;
 import com.wikantik.util.BaseUrlResolver;
 import com.wikantik.api.pagegraph.PageType;
 import jakarta.servlet.Filter;
@@ -533,7 +534,12 @@
         }
         // Parsed once and threaded through the head, body-fallback, and data-island
         // derivations below — those used to each re-parse the same frontmatter.
-        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
+        final ParsedPage fullPage = FrontmatterParser.parse( rawText == null ? "" : rawText );
+        // The head, the no-JS body fallback and the JSON data island all carry page text, and access-rule
+        // directives name people by login. None of them needs the directives (the SPA's editor re-fetches the
+        // raw text through GET /api/pages, which keeps them for editors), so none gets them. The render below
+        // still starts from rawText: the renderer consumes the directives itself.
+        final ParsedPage parsed = new ParsedPage( fullPage.metadata(), AclDirectives.strip( fullPage.body() ) );
         final String baseUrl = BaseUrlResolver.resolve( eng, req, null );
         final String appName = eng.getApplicationName();
         final String head = SemanticHeadRenderer.renderHead( pageName, parsed, baseUrl, appName, modified,
``````

**`ReadPagesTool`** — every caller is a guest, so always strip:

``````diff
--- a/wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java
+++ b/wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java
@@ -24,6 +24,7 @@
 import com.google.gson.Gson;
 import com.wikantik.mcp.AbstractMcpTool;
 import com.wikantik.mcp.tools.McpToolUtils;
+import com.wikantik.util.AclDirectives;
 import io.modelcontextprotocol.spec.McpSchema;
 import org.apache.logging.log4j.LogManager;
 import org.apache.logging.log4j.Logger;
@@ -163,7 +164,9 @@
                     entry.put( "error", "not_found" );
                     if ( metrics != null ) metrics.recordPartialFailure( "not_found" );
                 } else {
-                    final String text = pageManager.getPureText( slug, PageProvider.LATEST_VERSION );
+                    // Every caller here is a guest (see PageViewGate), so access-rule directives — which name people
+                    // by login — are never returned.
+                    final String text = AclDirectives.strip( pageManager.getPureText( slug, PageProvider.LATEST_VERSION ) );
                     entry.put( "content", text == null ? "" : text );
                 }
             } catch ( final Exception e ) {
``````

**`wikantik-tools` `GetPageTool`** — caller-aware, with a package-visible `canEdit` seam next to `canView`:

``````diff
--- a/wikantik-tools/src/main/java/com/wikantik/tools/GetPageTool.java
+++ b/wikantik-tools/src/main/java/com/wikantik/tools/GetPageTool.java
@@ -25,6 +25,7 @@
 import com.wikantik.page.subsystem.PageSubsystemBridge;
 import com.wikantik.api.spi.Wiki;
 import com.wikantik.auth.permissions.PermissionFilter;
+import com.wikantik.util.AclDirectives;
 
 import jakarta.servlet.http.HttpServletRequest;
 
@@ -93,7 +94,8 @@
             return null;
         }
 
-        final String body = ResultShaper.bodyOnly( raw );
+        // Access-rule directives name people by login: only a caller who may edit the page gets them.
+        final String body = ResultShaper.bodyOnly( canEdit( request, resolvedName ) ? raw : AclDirectives.strip( raw ) );
         final int limit = resolveLimit( maxChars );
         final boolean truncated = ResultShaper.wasTruncated( body, limit );
         final String shown = ResultShaper.truncateBody( body, limit );
@@ -127,6 +129,15 @@
         return new PermissionFilter( engine ).canAccess( session, pageName, "view" );
     }
 
+    /**
+     * Whether the principal bound to {@code request} may {@code edit} {@code pageName}. Package-visible so tests
+     * can stub it, like {@link #canView}.
+     */
+    boolean canEdit( final HttpServletRequest request, final String pageName ) {
+        final Session session = Wiki.session().find( engine, request );
+        return new PermissionFilter( engine ).canAccessQuietly( session, pageName, "edit" );
+    }
+
     private static int resolveLimit( final int requested ) {
         if ( requested <= 0 ) {
             return ResultShaper.defaultBodyTruncation();
``````

**`ExportZipBuilder.convertOrFallback`** — `raw` is only assigned after the strip, so an exception in the permission check leaves it `null` and the fallback writes an empty placeholder rather than unstripped text:

``````diff
--- a/wikantik-main/src/main/java/com/wikantik/export/ExportZipBuilder.java
+++ b/wikantik-main/src/main/java/com/wikantik/export/ExportZipBuilder.java
@@ -48,6 +48,7 @@
 import com.wikantik.api.pagegraph.PageDescriptor;
 import com.wikantik.api.pagegraph.StructuralIndexService;
 import com.wikantik.auth.permissions.PermissionFilter;
+import com.wikantik.util.AclDirectives;
 
 /**
  * Does the actual work of {@link ExportService#stream}: plans the vault layout, converts every
@@ -159,7 +160,11 @@
                                               final ExportLinkContext ctx, final List< String > warnings ) {
         String raw = null;
         try {
-            raw = pages.getPureText( page );
+            // The converter drops [{ALLOW}]/[{DENY}] spans itself, but its pattern is narrower than the ACL manager's
+            // and the fallback below writes the text it was given — so a caller who may not edit the page never has
+            // the directives in hand at all.
+            final String fetched = pages.getPureText( page );
+            raw = permissions.canAccessQuietly( session, d.slug(), "edit" ) ? fetched : AclDirectives.strip( fetched );
             final Map< String, Object > extra = extraFrontmatter( d, page, raw, layout );
             return CONVERTER.convert( d.slug(), raw, extra, ctx );
         } catch ( final RuntimeException e ) {
``````

**`ItemAssembler.bodyOf`** — pinned and cluster-member bodies injected into an agent's context:

``````diff
--- a/wikantik-main/src/main/java/com/wikantik/knowledge/briefing/ItemAssembler.java
+++ b/wikantik-main/src/main/java/com/wikantik/knowledge/briefing/ItemAssembler.java
@@ -27,6 +27,7 @@
 import com.wikantik.api.pagegraph.PageDescriptor;
 import com.wikantik.api.pagegraph.StructuralIndexService;
 import com.wikantik.api.providers.PageProvider;
+import com.wikantik.util.AclDirectives;
 import com.wikantik.util.TokenEstimator;
 
 import org.apache.logging.log4j.Logger;
@@ -207,8 +208,9 @@
         if ( fits ) ledger.used += est;
     }
 
+    /** Page text for injection into an agent's context — access-rule directives (people, by login) are left out. */
     private String bodyOf( final String slug ) {
-        return pageManager.getPureText( slug, PageProvider.LATEST_VERSION );
+        return AclDirectives.strip( pageManager.getPureText( slug, PageProvider.LATEST_VERSION ) );
     }
 
     private String canonicalIdOf( final String slug ) {
``````

**`DefaultForAgentProjectionService.loadBodyAndFrontmatter`**:

``````diff
--- a/wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java
+++ b/wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java
@@ -29,6 +29,7 @@
 import com.wikantik.api.citation.CitationStatus;
 import com.wikantik.api.frontmatter.FrontmatterParser;
 import com.wikantik.api.frontmatter.ParsedPage;
+import com.wikantik.util.AclDirectives;
 import com.wikantik.api.managers.PageManager;
 import com.wikantik.api.providers.WikiProvider;
 import com.wikantik.api.pagegraph.PageDescriptor;
@@ -220,7 +221,9 @@
             if ( raw != null && !raw.isEmpty() ) {
                 final ParsedPage parsed = FrontmatterParser.parse( raw );
                 frontmatter = parsed.metadata();
-                body = parsed.body();
+                // key_facts and the heading outline are derived from the body's first paragraphs, so a directive
+                // on its first lines would surface in them: access rules (people, by login) never enter the body.
+                body = AclDirectives.strip( parsed.body() );
             }
         } catch ( final Exception e ) {
             LOG.warn( "for-agent: getPureText({}) failed — body & key_facts & headings will degrade: {}",
``````

- [ ] **Step 4: Run the tests to verify they pass**

```bash
unset ${!WIKANTIK_@}
mvn -B -o test -pl wikantik-main,wikantik-rest,wikantik-knowledge,wikantik-tools -am \
  -Dtest='AclDirectiveVisibilityTest,SpaRoutingFilterTest,ReadPagesToolTest,GetPageToolTest,ExportServiceTest,DefaultBriefingAssemblyServiceTest,DefaultForAgentProjectionServiceAclDirectiveTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```
Expected: `BUILD SUCCESS` (`AclDirectiveVisibilityTest` 9, `SpaRoutingFilterTest` 74, `ReadPagesToolTest` 12, wikantik-tools `GetPageToolTest` 11, `ExportServiceTest` 14, `DefaultBriefingAssemblyServiceTest` 18, the new for-agent class 1).

Regression over every touched area — the existing suites that exercise these classes (a few minutes):

```bash
mvn -B -o test -pl wikantik-main,wikantik-rest,wikantik-knowledge,wikantik-tools -am \
  -Dtest='com.wikantik.rest.*Test,com.wikantik.knowledge.mcp.*Test,com.wikantik.tools.*Test,com.wikantik.export.*Test,com.wikantik.knowledge.briefing.*Test,com.wikantik.knowledge.agent.*Test,com.wikantik.auth.acl.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```

Complexity gate. `WikiPageFormatFilter.doFilter` was exactly at the cognitive-complexity ceiling (20); an inline ternary pushed it over and the gate named it, which is why that hunk uses a `readableBy` helper:

```bash
mvn -B -o test-compile pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest,wikantik-knowledge,wikantik-tools -am -DskipTests
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/DiffResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java \
        wikantik-rest/src/main/java/com/wikantik/rest/SpaRoutingFilter.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/ReadPagesTool.java \
        wikantik-tools/src/main/java/com/wikantik/tools/GetPageTool.java \
        wikantik-main/src/main/java/com/wikantik/export/ExportZipBuilder.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/briefing/ItemAssembler.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionService.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AclDirectiveVisibilityTest.java \
        wikantik-rest/src/test/java/com/wikantik/rest/SpaRoutingFilterTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/ReadPagesToolTest.java \
        wikantik-tools/src/test/java/com/wikantik/tools/GetPageToolTest.java \
        wikantik-main/src/test/java/com/wikantik/export/ExportServiceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/briefing/DefaultBriefingAssemblyServiceTest.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/agent/DefaultForAgentProjectionServiceAclDirectiveTest.java
git commit -m "fix(acl): raw page text omits access-rule directives unless the caller can edit

Page GET/diff/?format=, the SSR document, read_pages, /tools get_page, the Obsidian export, briefings and key_facts.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 19: Strip directives at index time (chunks and the Lucene content field)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java:27,103`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexer.java:48,539`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java`
- Test: `wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexerTest.java`

**Interfaces:**
- Consumes: `AclDirectives.strip(String)` (Task 17).
- Produces: no API change. Behaviour: `ContentChunker.chunk(String, ParsedPage)` never emits directive text in any `Chunk.text()`; the Lucene `contents` field (and so its highlighted snippets) never carries it. Directives inside code spans or closed fences are page content and stay.

**Why these two points are enough.**
- `ContentChunker.chunk` is the single producer of chunk text: save time (`ChunkProjector.java:190`), `ContentIndexRebuildService.java:354`, `ChunkerStatsCli`. `kg_content_chunks.text` is what dense embeddings (`EmbeddingTextBuilder`), the BM25 chunk stores (`Bm25ChunkStore`, `LuceneBm25ChunkIndex`), `assemble_bundle` / `retrieve_context` / briefing sections, and the entity-extraction prompts are all built from — so stripping before chunking removes the directives from every one of them.
- `DefaultLuceneIndexer.luceneIndexPage` is the only place the `contents` field is built (`LuceneSearchProvider.luceneIndexPage` delegates to it), and the stored `contents` is what search snippets are highlighted from. The frontmatter parse a few lines below still reads the original `text`, so tags/cluster/summary are unaffected.

**A one-time index rebuild is required after deploy — Task 24 runs it.** Chunks and Lucene documents already in production still contain directives until the pages are re-indexed, so search snippets and bundles keep showing them until then. Trigger `POST /admin/content/rebuild-indexes` (`AdminContentResource.java:129`; wrapper `bin/trigger-rebuild-indexes.sh`). The rebuild re-chunks every page and `ChunkDiff.compute` rewrites only the chunks whose content hash changed (pages that actually contain directives), then re-embeds those; the Lucene index is rebuilt wholesale. With `wikantik.search.dense.backend` = `lucene-hnsw` or `inmemory` the dense index reloads only when the run ends **SUCCESS** (see the HybridRetrieval note in `CLAUDE.md`), so confirm the final state.

- [ ] **Step 1: Write the failing tests**


``````diff
--- a/wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java
@@ -373,4 +373,28 @@
                 "overlap=0 must not prepend any tail");
         }
     }
+
+    @Test
+    void accessRuleDirectivesNeverReachChunkText() {
+        final String body = "[{ALLOW view All}]\n[{ALLOW edit leak-probe@example.test}]\n\n# Title\n\n"
+                + "Public prose about the topic.\n\n## Notes\n\nUse `[{ALLOW edit Bob}]` to restrict a page.\n";
+        final List<Chunk> chunks = chunker.chunk("AclPage", new ParsedPage(Map.of(), body));
+
+        assertFalse(chunks.isEmpty());
+        for (final Chunk c : chunks) {
+            assertFalse(c.text().contains("leak-probe@example.test"), c.text());
+            assertFalse(c.text().contains("[{ALLOW view All}]"), c.text());
+        }
+        assertTrue(chunks.stream().anyMatch(c -> c.text().contains("Public prose")));
+        assertTrue(chunks.stream().anyMatch(c -> c.text().contains("`[{ALLOW edit Bob}]`")),
+                "a documentation example inside a code span is page content and stays");
+    }
+
+    @Test
+    void leadingDirectivesLeaveTheChunksExactlyAsIfTheyWereNeverThere() {
+        final String clean = "# Title\n\nPublic prose about the topic.\n\n## Notes\n\nMore prose.\n";
+        final String withRules = "[{ALLOW view All}]()\n[{ALLOW edit leak-probe@example.test}]\n\n" + clean;
+        assertEquals(chunker.chunk("P", new ParsedPage(Map.of(), clean)),
+                     chunker.chunk("P", new ParsedPage(Map.of(), withRules)));
+    }
 }
``````

``````diff
--- a/wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexerTest.java
+++ b/wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexerTest.java
@@ -46,6 +46,7 @@
 import static org.junit.jupiter.api.Assertions.assertEquals;
 import static org.junit.jupiter.api.Assertions.assertFalse;
 import static org.junit.jupiter.api.Assertions.assertThrows;
+import static org.junit.jupiter.api.Assertions.assertTrue;
 import static org.mockito.ArgumentMatchers.any;
 import static org.mockito.ArgumentMatchers.anyInt;
 import static org.mockito.ArgumentMatchers.eq;
@@ -294,6 +295,25 @@
         }
     }
 
+    @Test
+    void luceneIndexPage_indexesPageTextWithoutAccessRuleDirectives() throws Exception {
+        final String text = "[{ALLOW view All}]()\n[{ALLOW edit leak-probe@example.test}]\n"
+                + "Public prose. Example: `[{ALLOW edit Bob}]`.";
+        final Page page = mockPage( "AclIndexedPage" );
+
+        try ( Directory dir = LuceneDirectoryFactory.open( tmp, false );
+              IndexWriter writer = new IndexWriter( dir, new IndexWriterConfig( new StandardAnalyzer() ) ) ) {
+            final Document doc = newIndexer().luceneIndexPage( page, text, writer );
+
+            final String contents = doc.get( DefaultLuceneIndexer.LUCENE_PAGE_CONTENTS );
+            assertFalse( contents.contains( "leak-probe@example.test" ), contents );
+            assertFalse( contents.contains( "[{ALLOW view All}]" ), contents );
+            assertTrue( contents.contains( "Public prose." ), contents );
+            assertTrue( contents.contains( "`[{ALLOW edit Bob}]`" ),
+                "a documentation example in a code span stays: " + contents );
+        }
+    }
+
     // ---- drainUpdateQueue ----
 
     @Test
``````

- [ ] **Step 2: Run them to verify they fail**

```bash
unset ${!WIKANTIK_@}
mvn -B -o test -pl wikantik-main -am -Dtest='ContentChunkerTest,DefaultLuceneIndexerTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```
Expected: three failures —
- `ContentChunkerTest.accessRuleDirectivesNeverReachChunkText` (the first chunk is `[{ALLOW view All}]\n[{ALLOW edit leak-probe@example.test}]` with an empty heading path);
- `ContentChunkerTest.leadingDirectivesLeaveTheChunksExactlyAsIfTheyWereNeverThere` (an extra leading chunk with `headingPath=[]`);
- `DefaultLuceneIndexerTest.luceneIndexPage_indexesPageTextWithoutAccessRuleDirectives` (`contents` still holds both directives).

- [ ] **Step 3: Implement**


``````diff
--- a/wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java
+++ b/wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java
@@ -25,6 +25,7 @@
 import com.vladsch.flexmark.parser.Parser;
 import com.vladsch.flexmark.util.ast.Node;
 import com.wikantik.api.frontmatter.ParsedPage;
+import com.wikantik.util.AclDirectives;
 import org.apache.logging.log4j.LogManager;
 import org.apache.logging.log4j.Logger;
 
@@ -100,7 +101,10 @@
     public Config config() { return config; }
 
     public List<Chunk> chunk(String pageName, ParsedPage page) {
-        String body = page.body() == null ? "" : page.body().strip();
+        // Access-rule directives name people by login and are not content: they never reach a chunk, so
+        // never a search hit, a bundle section, an embedding or an extraction prompt.
+        final String withoutRules = AclDirectives.strip(page.body());
+        String body = withoutRules == null ? "" : withoutRules.strip();
         List<Chunk> out = new ArrayList<>();
         if (body.isEmpty()) {
             return out;
``````

``````diff
--- a/wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexer.java
+++ b/wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexer.java
@@ -45,6 +45,7 @@
 import com.wikantik.api.managers.AttachmentManager;
 import com.wikantik.api.managers.PageManager;
 import com.wikantik.api.managers.SystemPageRegistry;
+import com.wikantik.util.AclDirectives;
 import com.wikantik.util.FileUtil;
 import com.wikantik.util.TextUtil;
 
@@ -536,7 +537,11 @@
             return doc;
         }
 
-        final String indexedText = text.replace( "__", " " ); // be nice to Language Analyzers - cfr. JSPWIKI-893
+        // Access-rule directives name people by login and are not searchable content; indexing the text
+        // without them keeps them out of hits and highlighted snippets. The frontmatter parse below still
+        // reads the original text.
+        final String indexedText = AclDirectives.strip( text )
+                .replace( "__", " " ); // be nice to Language Analyzers - cfr. JSPWIKI-893
 
         Field field = new Field( LUCENE_ID, page.getName(), StringField.TYPE_STORED );
         doc.add( field );
``````

- [ ] **Step 4: Run the tests to verify they pass**

```bash
unset ${!WIKANTIK_@}
mvn -B -o test -pl wikantik-main -am -Dtest='ContentChunkerTest,DefaultLuceneIndexerTest' \
    -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```
Expected: `ContentChunkerTest` 20 and `DefaultLuceneIndexerTest` 17 pass. Then the neighbours that build on these classes (search, chunking, rebuild service, briefing, agent projection) and the complexity gate:

```bash
mvn -B -o test -pl wikantik-main -am \
  -Dtest='com.wikantik.search.**.*Test,com.wikantik.knowledge.chunking.*Test,com.wikantik.admin.*Test,com.wikantik.knowledge.briefing.*Test,com.wikantik.knowledge.agent.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
mvn -B -o test-compile pmd:check -Pcomplexity-gate -pl wikantik-main -am -DskipTests    # BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/chunking/ContentChunker.java \
        wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexer.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/chunking/ContentChunkerTest.java \
        wikantik-main/src/test/java/com/wikantik/search/subsystem/lucene/DefaultLuceneIndexerTest.java
git commit -m "fix(acl): chunks and the Lucene content field are indexed without access-rule directives

Existing documents keep them until the post-deploy index rebuild (Task 24).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```


### Section notes

**Contract points that did not survive contact with the code**

1. **Test fixtures in other sections must not use a lone `[{ALLOW edit <login>}]`.** A non-empty ACL is deny-by-default and `edit` implies `view`, so that line alone makes the page unreadable to everyone but that login and admins (`DefaultAuthorizationManager.decideByAcl`, :276-293). Task 22's leak probe ("named in an ACL on a public page") needs `[{ALLOW view All}]` **and** `[{ALLOW edit Admin,leak-probe-…@example.test}]` — the form every test here uses. Also: the page's *first paragraph* is what leaks through `key_facts`, `?format=json` `summary` and the SSR head, so the probe page should open with the directive lines.
2. **`AclDirectives` gained an overload**, `strip(String, String)` — needed so `PageExcerpts`/`SummaryExtractor` keep their word-separating `" "`. `strip(String)` is exactly the contract's signature.
3. **`wikantik-util` cannot reuse `DefaultAclManager`'s flexmark code scanner** (no flexmark in that module; `wikantik-api` depends on `wikantik-util`, so adding it there would push flexmark into the API's transitive closure). `AclDirectives` therefore has a small hand-written code scanner with a deliberately conservative, fail-closed policy (documented on the class and pinned by tests), and `AclDirectivesParityTest` in `wikantik-main` is the guard against the two drifting: it runs both over hand-written bodies and all ~1,180 shipped pages. If someone later adds an action to `PagePermission`, `AclDirectives.PERMISSIONS` must follow — `everyPermissionTheAclManagerAcceptsIsStripped` lists the seven today.
3b. **`DENY`**: `DefaultAclManager` only ever enforces `ALLOW`; `DENY` is recognised only by the renderer's `isAccessRule`, whose `parseAcl` ignores the first token — so by my reading of `parseAcl` a rendered `[{DENY view bob}]` becomes an *allow* entry on the in-memory `Page` ACL (not exercised here). Pre-existing, unrelated to this section and left alone; `AclDirectives` removes `DENY` regardless because it names a login.
4. **Surfaces beyond the contract's list that leaked and are fixed in Task 18:** the SSR document's JSON data island / head / no-JS fallback (`SpaRoutingFilter`), briefings (`ItemAssembler`), `key_facts` in the agent projection, and the `contentHash` oracle. **Listed but nothing to strip:** `HistoryResource` (no text; version text is `PageResource ?version=`), knowledge `get_page` (frontmatter metadata only). The Obsidian export already dropped `ALLOW`/`DENY` on its happy path; Task 18 closes the narrower-regex and raw-fallback gaps.
5. Module dependencies: nothing to add. `wikantik-rest`, `wikantik-knowledge` and `wikantik-tools` all compiled against `com.wikantik.util.AclDirectives` through the existing transitive `wikantik-util` dependency. Downstream `-pl` test runs need `-am` (or `mvn -B -o install -pl wikantik-util -DskipTests` first) because `~/.m2` has no `AclDirectives` until then.
6. `/wiki/{slug}?format=md|json` now varies by caller (editor vs reader) and sets no `Cache-Control`. Today nothing caches it (no extension, dynamic), but if an edge rule ever caches `?format=` responses it must key on the session or exclude authenticated requests.
7. PMD: `WikiPageFormatFilter.doFilter` sat at the cognitive-complexity ceiling (20 = the failing threshold), so the strip lives in a `readableBy` helper there. Any section that adds a branch to that method will trip `-Pcomplexity-gate` — extract rather than inline.

**Cross-section dependencies / ordering**

- **Task 8 (search API + Lucene author field) also edits `DefaultLuceneIndexer.luceneIndexPage`** — the author field (`page.getAuthor()` at HEAD :560-561) and, presumably, the constructor (to receive `PublicIdentity`). Task 19 touches the `indexedText` line (HEAD :539) and one import (:48): different hunks, no logical overlap, but both land in `DefaultLuceneIndexer.java`/`DefaultLuceneIndexerTest.java`. My new test builds its indexer through the file's own `newIndexer()` helper, so it adapts to a changed constructor. Apply in either order; re-read the file before the second edit.
- **Task 7 (page/list/history APIs) edits `PageResource.doGet`** (`result.put( "author", page.getAuthor() )`, HEAD :298) and possibly `HistoryResource`. My hunks in that method are at :273 (`content`), :304-305 (`contentHash`) and :324 (`permissions.edit`) plus one local, `canEdit`, declared just above `result` — close to, but not overlapping, the author line. Either order works; re-read the file before the second edit.
- **Task 9 (agent projection, knowledge MCP, `/tools`)** touches `DefaultForAgentProjectionService` (`recent_changes[].author`, `verified_by`), the tools `GetPageTool` (`author`, :108-109) and its test helper `allowingTool`. My hunks there are `loadBodyAndFrontmatter` (:223), the `body` line (:96) plus a new `canEdit` method, and the same `allowingTool` helper (gains a `canEdit` override) — merge by hand. The new for-agent test is a separate class (`…AclDirectiveTest`) precisely to avoid a collision in `DefaultForAgentProjectionServiceTest`.
- **Task 10 (feeds / recent-articles ACL filter)**: unaffected — recent articles take the excerpt from the *rendered* HTML and the title from an H1 regex, neither of which sees a directive.
- **Task 22 (leak-probe IT)** should add to its probe list, beyond the contract's: `GET /wiki/<page>` HTML (data island), `?format=json` `summary`, `/api/briefing?pins=<page>`, `/api/pages/for-agent/<id>` `key_facts`, `/api/diff/<page>`, `/api/export` — each with a probe page that opens with its directive lines and has at least two versions where the second changes the named login.
- **Task 24 (rollout)** must run the index rebuild after deploy (`POST /admin/content/rebuild-indexes`, status via `bin/trigger-rebuild-indexes.sh status`) and confirm it ends SUCCESS; until then search snippets, bundles and embeddings still carry old directive text. No schema change, so no migration.
- **Task 23 (docs)**: say that directives are hidden from readers who cannot edit, that editors/admins still see them, and (spec §5) recommend naming groups rather than people in ACLs. `TextFormattingRules.md:87` currently says "Name individual people by their login name."

---

# Added — View filters (Task 25)

### Task 25: View-permission filters for `/tools/search_wiki` and `RecentChangesPlugin`

Two independent confidentiality defects, one task, two commits. Neither depends on the display-name work in Tasks 1-24 (they are not about names), so this task can land at any point in the plan.

**Defects, verified in the code before planning**

1. **`/tools/search_wiki` returns restricted pages.** `SearchWikiTool.execute` (`wikantik-tools/.../SearchWikiTool.java:70-98`) shapes every page that `ContextRetrievalService.retrieve` returns. `DefaultContextRetrievalService` applies no view check (it contains no ACL/permission code), so titles, summaries, snippets, `relatedPages` references and authors of `[{ALLOW view Admin}]` pages reach any holder of a `tools` API key. `GetPageTool` (the sibling `/tools/page/{name}`) already enforces view: it resolves the session with `Wiki.session().find( engine, request )` and asks `new PermissionFilter( engine ).canAccess( session, name, "view" )`. The knowledge MCP's `retrieve_context` does the equivalent with a guest `PageViewGate`. Only `search_wiki` was left unguarded.
2. **`RecentChangesPlugin` lists restricted pages to every viewer of the hosting page.** `execute` (`wikantik-main/.../RecentChangesPlugin.java:84-216`) takes `PageManager.getRecentChanges( since )` (pages **and attachments**), runs it through `filterWikiPageCollection` (include/exclude patterns and system-page filtering only — `AbstractReferralPlugin.filterCollection` has no permission check) and renders each row (title link, author, change note) without a view check. Note: contrary to the task brief, `ReferringPagesPlugin`/`AbstractReferralPlugin` contain **no** view check to copy (grep for `Permission` in `ReferringPagesPlugin`, `IndexPlugin`, `PageViewPlugin`, `AbstractReferralPlugin` is empty); the only plugin-side precedent is `InsertPage.hasViewPermission`. This plan uses `PermissionFilter.filterViewableQuietly` instead — the batch, silent (no `access.denied` audit rows) twin that `RestServletBase.filterViewable` and `SearchResource` already use for bulk listings.

**Facts that shape the design (all checked in code)**

- For a stateless API-key request, `WikiSession.getWikiSession` returns the transient **anonymous guest** session (`needsPersistentSession` treats `ApiKeyPrincipalRequest.AUTH_TYPE` as "no session"), so on `/tools/*` the "caller's view permission" is the anonymous view permission, exactly as for `get_page`. A session-bearing request (for example a CIDR-allowlisted caller that carries a login cookie) is evaluated as that user. The tests therefore prove both: the API-key shape is hidden from restricted pages, and a logged-in admin sees them.
- `PluginContent.invoke` (`parser/PluginContent.java:170`) already sets `Context.VAR_VIEWER_SENSITIVE` before executing **any** plugin, so the render-cache hole is already closed on the parse path. The plugin sets the flag itself as defence in depth (the output now genuinely depends on the viewer) and a render-level test pins "admin render is not served to a guest".
- Attachments need no special case: `PageRepository.getPageWithoutMetadata` falls back to `AttachmentManager.getAttachmentInfo`, and `DefaultAclManager.getPermissions` takes an attachment's ACL from its parent page. A test pins it.
- A non-empty ACL is deny-by-default and `edit` implies `view`; fixtures use `[{ALLOW view Admin}]` and `[{ALLOW edit Admin}]` (both hidden from non-admins, both visible to Admin).

**Files:**
- Create: `wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolViewPermissionTest.java`
- Create: `wikantik-main/src/test/java/com/wikantik/plugin/RecentChangesPluginViewPermissionTest.java`
- Modify: `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java:21-41` (imports), `:43-50` (class Javadoc), `:70-98` (`execute`), `:100` (insert new methods above `errorResult`), `:110-127` (`buildEntry`), `:152-163` (`appendRelatedPages`)
- Modify: `wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java:42-46` (imports), `:62` (helper after the `@Mock` fields), 11 `new SearchWikiTool( engine, … )` call sites, and append two tests at the end of the class
- Modify: `wikantik-main/src/main/java/com/wikantik/plugin/RecentChangesPlugin.java:32` (import), `:49-50` (imports), `:55-63` (Javadoc), `:92` (flag), `:111` (filter call), `:226` (new private method above `isSameDay`)
- Test: the two new test classes above, plus the existing `SearchWikiToolTest`, `GetPageToolTest`, `ToolsOpenApiServletTest`, `RecentChangesPluginTest`, `RecentChangesPluginCITest`, `InsertPageTest`

**Interfaces:**
- Consumes (all already in the tree, nothing from Tasks 1-24): `com.wikantik.auth.permissions.PermissionFilter#filterViewableQuietly( Session, Collection<String> ) : Set<String>`; `Wiki.session().find( Engine, HttpServletRequest )`; `Context#getWikiSession()`; `Context.VAR_VIEWER_SENSITIVE`; test helpers `TestEngine` (reachable from `wikantik-tools` through the `wikantik-main` test-jar that its pom already declares), `HttpMockFactory.createIsolatedHttpRequest( String )`, `TestEngine#addAttachment`, `TestEngine.deleteAttachments`, `ApiKeyPrincipalRequest`.
- Produces: no wire/JSON shape change. New package-private seam `SearchWikiTool#viewableNames( HttpServletRequest, Collection<String> ) : Set<String>` (overridable by tests, mirrors `GetPageTool#canView`). New private `RecentChangesPlugin.filterViewable( Context, Collection<Page> ) : List<Page>` (a later cleanup could hoist it to `AbstractReferralPlugin`; see the coordinator notes). Behavioural contract: `search_wiki` results and `relatedPages` entries the caller cannot view are dropped (order preserved, dropped **before** the `maxResults` cap and before `total`, so neither reveals hidden matches; a search whose hits are all hidden is indistinguishable from a no-match search; a failure of the permission check fails the request instead of returning unfiltered results). `RecentChangesPlugin` lists only pages/attachments the render's viewer may view and always flags `VAR_VIEWER_SENSITIVE`.
- Merge notes with other sections: **Task 9** also edits `SearchWikiTool` (author display name; it may add a `PublicIdentity` constructor argument) and `DefaultContextRetrievalService`. This task never touches the `author` line in `buildEntry`, the constructor, or `DefaultContextRetrievalService`; its hunks are the imports, class Javadoc, the loop in `execute`, three new methods, the `buildEntry` signature/call and `appendRelatedPages`. Expect at most a trivial manual merge in the import block and in the `buildEntry` signature line (keep both parameters). If Task 9 changes the `SearchWikiTool` constructor, update `new SearchWikiTool( engine, config )` in the `allowingTool` helper and in the two new `SearchWikiToolTest` tests, and `new SearchWikiTool( testEngine, … )` in `SearchWikiToolViewPermissionTest#setUp`, to the new signature. **Task 10** edits `RecentChangesPlugin` to stop linking the author — that hunk is the `showAuthor` block (currently `:184-203`), far from this task's hunks; the only shared region is the `java.util` import block. Task 10's separate "view-ACL filter in recent articles" is `DefaultRecentArticlesManager` (an anonymous-view filter for feeds); it does not overlap this task's per-viewer plugin filter. Land this task before or after either; re-run Step 4 after any merge.

- [ ] **Step 1: Write the failing tests**

**Part A — `search_wiki`.** Create `wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolViewPermissionTest.java`. It runs against a real `TestEngine` with real inline ACLs and real sessions (guest, logged-in admin, stateless API-key request); only the retrieval service is stubbed, because ranking is not under test. It compiles against the unchanged code, so Step 2 shows behavioural failures, not compile errors.

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.tools;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code search_wiki} must apply the same view-permission rule {@code get_page} applies: a page the
 * caller cannot view is dropped from the results (titles, summaries, snippets and related-page
 * references alike). Runs against a real engine with real inline ACLs and real sessions; only the
 * retrieval service is stubbed, because the ranking is not under test.
 *
 * <p>Fixture notes: a non-empty ACL is deny-by-default, and {@code edit} implies {@code view}, so
 * {@code [{ALLOW view Admin}]} hides a page from every non-admin and {@code [{ALLOW edit Admin}]}
 * does the same while still letting an admin see it.</p>
 */
class SearchWikiToolViewPermissionTest {

    private static TestEngine testEngine;

    private ContextRetrievalService ctxService;
    private SearchWikiTool tool;

    @BeforeAll
    static void startEngine() {
        testEngine = TestEngine.build();
    }

    @AfterAll
    static void cleanWorkDir() {
        TestEngine.emptyWorkDir();
    }

    @BeforeEach
    void setUp() throws Exception {
        testEngine.saveText( "VpPublicOne", "open page one" );
        testEngine.saveText( "VpPublicTwo", "open page two" );
        testEngine.saveText( "VpSecret", "[{ALLOW view Admin}]\n\nclassified body" );
        testEngine.saveText( "VpEditLocked", "[{ALLOW edit Admin}]\n\nedit-locked body" );

        ctxService = mock( ContextRetrievalService.class );
        testEngine.setManager( ContextRetrievalService.class, ctxService );
        tool = new SearchWikiTool( testEngine, new ToolsConfig( new Properties() ) );
    }

    @AfterEach
    void tearDown() {
        testEngine.deleteTestPage( "VpPublicOne" );
        testEngine.deleteTestPage( "VpPublicTwo" );
        testEngine.deleteTestPage( "VpSecret" );
        testEngine.deleteTestPage( "VpEditLocked" );
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static RetrievedPage hit( final String name, final RelatedPage... related ) {
        return RetrievedPage.builder( name, 1.0 )
                .url( "" )
                .summary( "summary of " + name )
                .tags( List.of() )
                .contributingChunks( List.of( new RetrievedChunk( List.of( name ), "snippet of " + name, 0.9, List.of() ) ) )
                .relatedPages( List.of( related ) )
                .build();
    }

    private void retrievalReturns( final RetrievedPage... pages ) {
        when( ctxService.retrieve( any( ContextQuery.class ) ) )
                .thenReturn( new RetrievalResult( "q", List.of( pages ), pages.length ) );
    }

    /** A request carrying its own HttpSession, so it resolves to a fresh anonymous WikiSession. */
    private static HttpServletRequest anonymousRequest() {
        return HttpMockFactory.createIsolatedHttpRequest( "/tools/search_wiki" );
    }

    private static HttpServletRequest adminRequest() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/tools/search_wiki" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return request;
    }

    /** Mirrors production: ToolsAccessFilter hands the servlet a stateless API-key request. */
    private static HttpServletRequest apiKeyRequest() {
        final HttpServletRequest bare = mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( bare ).getSession( false );
        Mockito.doReturn( null ).when( bare ).getCookies();
        Mockito.doReturn( Locale.ROOT ).when( bare ).getLocale();
        Mockito.doReturn( "127.0.0.1" ).when( bare ).getRemoteAddr();
        return new ApiKeyPrincipalRequest( bare, "keyowner" );
    }

    @SuppressWarnings( "unchecked" )
    private static List< Map< String, Object > > results( final Map< String, Object > out ) {
        return ( List< Map< String, Object > > ) out.get( "results" );
    }

    private static List< Object > names( final Map< String, Object > out ) {
        return results( out ).stream().map( r -> r.get( "name" ) ).toList();
    }

    // ---- tests ----------------------------------------------------------------------------

    @Test
    void restrictedPageIsAbsentForAnonymousCallerAndOrderIsPreserved() {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpSecret" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        assertEquals( List.of( "VpPublicOne", "VpPublicTwo" ), names( out ) );
        assertEquals( 2, out.get( "total" ), "total counts only what the caller may see" );
        final String serialized = out.toString();
        assertFalse( serialized.contains( "VpSecret" ), "a restricted title must not appear anywhere: " + serialized );
        assertFalse( serialized.contains( "summary of VpSecret" ) );
        assertFalse( serialized.contains( "snippet of VpSecret" ) );
    }

    @Test
    void restrictedPageIsPresentForAdministrator() throws Exception {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpSecret" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 10, adminRequest() );

        assertEquals( List.of( "VpPublicOne", "VpSecret", "VpPublicTwo" ), names( out ) );
        assertEquals( 3, out.get( "total" ) );
    }

    @Test
    void editRestrictedPageFollowsTheSameRule() throws Exception {
        retrievalReturns( hit( "VpEditLocked" ), hit( "VpPublicOne" ) );

        assertEquals( List.of( "VpPublicOne" ), names( tool.execute( "q", 10, anonymousRequest() ) ) );
        assertEquals( List.of( "VpEditLocked", "VpPublicOne" ), names( tool.execute( "q", 10, adminRequest() ) ) );
    }

    @Test
    void unrestrictedPagesAreUnaffected() {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        assertEquals( List.of( "VpPublicOne", "VpPublicTwo" ), names( out ) );
        assertEquals( 2, out.get( "total" ) );
        assertEquals( "snippet of VpPublicOne", results( out ).get( 0 ).get( "snippet" ) );
        assertNull( out.get( "error" ) );
    }

    @Test
    void everyResultRestrictedLooksLikeAnEmptySearch() {
        retrievalReturns( hit( "VpSecret" ), hit( "VpEditLocked" ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        assertEquals( List.of(), results( out ) );
        assertEquals( 0, out.get( "total" ) );
        assertNull( out.get( "error" ), "must be indistinguishable from a query with no matches" );
    }

    @Test
    void hiddenResultsDoNotConsumeTheResultCap() {
        retrievalReturns( hit( "VpSecret" ), hit( "VpPublicOne" ), hit( "VpEditLocked" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 2, anonymousRequest() );

        assertEquals( List.of( "VpPublicOne", "VpPublicTwo" ), names( out ) );
    }

    @Test
    void restrictedRelatedPagesAreNotRevealedOnVisibleResults() {
        retrievalReturns( hit( "VpPublicOne",
                new RelatedPage( "VpSecret", "shared entities: classified" ),
                new RelatedPage( "VpPublicTwo", "shared entities: open" ) ),
                hit( "VpPublicTwo", new RelatedPage( "VpSecret", "shared entities: classified" ) ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        final Map< String, Object > first = results( out ).get( 0 );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > related = ( List< Map< String, Object > > ) first.get( "relatedPages" );
        assertEquals( 1, related.size() );
        assertEquals( "VpPublicTwo", related.get( 0 ).get( "name" ) );
        assertFalse( results( out ).get( 1 ).containsKey( "relatedPages" ),
                "a result whose only related page is restricted carries no relatedPages key" );
        assertFalse( out.toString().contains( "VpSecret" ) );
        assertFalse( out.toString().contains( "classified" ) );
    }

    @Test
    void restrictedPageIsAbsentForAStatelessApiKeyRequest() {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpSecret" ) );

        final Map< String, Object > out = tool.execute( "q", 10, apiKeyRequest() );

        assertEquals( List.of( "VpPublicOne" ), names( out ) );
        assertFalse( out.toString().contains( "VpSecret" ) );
    }
}
```

**Part B — `RecentChangesPlugin`.** Create `wikantik-main/src/test/java/com/wikantik/plugin/RecentChangesPluginViewPermissionTest.java`. It also compiles against the unchanged code. Caching is deliberately enabled in this class so the render-sharing test is meaningful.

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.plugin;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.render.RenderingManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecentChangesPlugin} lists pages for whoever views the page that hosts it, so it must list
 * only the pages that viewer may view, and must flag its render viewer-sensitive so the shared,
 * principal-less render caches never hand one viewer's listing to another.
 *
 * <p>Fixture notes: a non-empty ACL is deny-by-default, and {@code edit} implies {@code view}, so
 * {@code [{ALLOW view Admin}]} hides a page from every non-admin and {@code [{ALLOW edit Admin}]}
 * does the same while still letting an admin see it.</p>
 */
public class RecentChangesPluginViewPermissionTest {

    // Caching is deliberately ON: the render-sharing test below only means something with caches live.
    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "true" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    private static final String PLUGIN = "{INSERT com.wikantik.plugin.RecentChangesPlugin}";

    @BeforeEach
    public void setUp() throws Exception {
        testEngine.saveText( "RcvHost", "[{RecentChangesPlugin}]" );
        testEngine.saveText( "RcvOpen", "open page" );
        testEngine.saveText( "RcvSecret", "[{ALLOW view Admin}]\n\nclassified" );
        testEngine.saveText( "RcvEditLocked", "[{ALLOW edit Admin}]\n\nedit-locked" );
        testEngine.addAttachment( "RcvSecret", "hidden-file.txt", "attachment of a classified page".getBytes() );
        testEngine.addAttachment( "RcvOpen", "shown-file.txt", "attachment of an open page".getBytes() );
    }

    @AfterEach
    public void tearDown() {
        for ( final String name : new String[] { "RcvHost", "RcvOpen", "RcvSecret", "RcvEditLocked" } ) {
            testEngine.deleteTestPage( name );
            TestEngine.deleteAttachments( name );
        }
        TestEngine.emptyWorkDir();
    }

    private Context guestContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/RcvHost" );
        final Context ctx = Wiki.context().create( testEngine, request, host() );
        assertTrue( ctx.getWikiSession().isAnonymous(), "fixture: guest context must be anonymous" );
        return ctx;
    }

    private Context adminContext() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/RcvHost" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return Wiki.context().create( testEngine, request, host() );
    }

    private Page host() {
        return testEngine.getManager( PageManager.class ).getPage( "RcvHost" );
    }

    @Test
    public void restrictedPagesAreAbsentForAnonymousViewer() throws Exception {
        final String res = manager.execute( guestContext(), PLUGIN );

        assertTrue( res.contains( "<a href=\"/test/wiki/RcvOpen\">Rcv Open</a>" ), res );
        assertFalse( res.contains( "RcvSecret" ), "restricted page name leaked: " + res );
        assertFalse( res.contains( "Rcv Secret" ), "restricted page title leaked: " + res );
        assertFalse( res.contains( "RcvEditLocked" ), "edit-restricted page name leaked: " + res );
        assertFalse( res.contains( "Rcv Edit Locked" ), "edit-restricted page title leaked: " + res );
    }

    @Test
    public void restrictedPagesArePresentForAdministrator() throws Exception {
        final String res = manager.execute( adminContext(), PLUGIN );

        assertTrue( res.contains( "<a href=\"/test/wiki/RcvOpen\">Rcv Open</a>" ), res );
        assertTrue( res.contains( "<a href=\"/test/wiki/RcvSecret\">Rcv Secret</a>" ), res );
        assertTrue( res.contains( "<a href=\"/test/wiki/RcvEditLocked\">Rcv Edit Locked</a>" ), res );
    }

    @Test
    public void unrestrictedPagesAreUnaffected() throws Exception {
        final String res = manager.execute( guestContext(), PLUGIN );

        assertTrue( res.contains( "<a href=\"/test/wiki/RcvHost\">Rcv Host</a>" ), res );
        assertTrue( res.contains( "<a href=\"/test/wiki/RcvOpen\">Rcv Open</a>" ), res );
    }

    @Test
    public void includeParameterCannotBypassTheViewCheck() throws Exception {
        final String res = manager.execute( guestContext(),
                "{INSERT com.wikantik.plugin.RecentChangesPlugin include='RcvSecret*'}" );

        assertEquals( "<table class=\"recentchanges\" cellpadding=\"4\"></table>", res );
    }

    @Test
    public void attachmentsOfRestrictedPagesAreHidden() throws Exception {
        final String guest = manager.execute( guestContext(), PLUGIN );
        assertTrue( guest.contains( "shown-file.txt" ), "attachment of an open page should be listed: " + guest );
        assertFalse( guest.contains( "hidden-file.txt" ), "attachment of a restricted page leaked: " + guest );

        final String admin = manager.execute( adminContext(), PLUGIN );
        assertTrue( admin.contains( "hidden-file.txt" ), admin );
    }

    @Test
    public void executionFlagsTheRenderViewerSensitive() throws Exception {
        final Context ctx = guestContext();

        manager.execute( ctx, PLUGIN );

        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ),
                "the listing depends on the viewer, so it must never enter the shared render caches" );
    }

    @Test
    public void renderedHostPageIsNotSharedBetweenViewers() throws Exception {
        final RenderingManager renderer = testEngine.getManager( RenderingManager.class );
        final String source = testEngine.getManager( PageManager.class ).getPureText( "RcvHost", -1 );

        final String asAdmin = renderer.textToHTML( adminContext(), source );
        final String asGuest = renderer.textToHTML( guestContext(), source );
        final String asAdminAgain = renderer.textToHTML( adminContext(), source );

        assertTrue( asAdmin.contains( "RcvSecret" ), "admin render should list the restricted page: " + asAdmin );
        assertFalse( asGuest.contains( "RcvSecret" ), "guest render must not reuse the admin listing: " + asGuest );
        assertTrue( asAdminAgain.contains( "RcvSecret" ) );
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

If the `2.4.58-SNAPSHOT` dependency jars are missing from `~/.m2`, build them first (`wikantik-tools` needs `wikantik-main` and its test-jar):

```bash
bin/agent-build.sh start t25-deps -- mvn -B -o install -pl wikantik-tools -am -DskipTests
bin/agent-build.sh wait t25-deps 540     # exit 0 = built; 2 = still running, call again
```

Part A:

```bash
mvn -B -o test -pl wikantik-tools -Dtest='SearchWikiToolViewPermissionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 8, Failures: 6`. These fail with a restricted page in the output (for example `expected: <[VpPublicOne, VpPublicTwo]> but was: <[VpPublicOne, VpSecret, VpPublicTwo]>`): `restrictedPageIsAbsentForAnonymousCallerAndOrderIsPreserved`, `editRestrictedPageFollowsTheSameRule`, `everyResultRestrictedLooksLikeAnEmptySearch`, `hiddenResultsDoNotConsumeTheResultCap`, `restrictedRelatedPagesAreNotRevealedOnVisibleResults`, `restrictedPageIsAbsentForAStatelessApiKeyRequest`. The two control tests, `restrictedPageIsPresentForAdministrator` and `unrestrictedPagesAreUnaffected`, pass (they must keep passing after the fix).

Part B:

```bash
mvn -B -o test -pl wikantik-main -Dtest='RecentChangesPluginViewPermissionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 7, Failures: 5`: `restrictedPagesAreAbsentForAnonymousViewer` (the listing contains `Rcv Secret`), `includeParameterCannotBypassTheViewCheck`, `attachmentsOfRestrictedPagesAreHidden`, `renderedHostPageIsNotSharedBetweenViewers`, and `executionFlagsTheRenderViewerSensitive` (`expected: <true> but was: <null>`). The controls `restrictedPagesArePresentForAdministrator` and `unrestrictedPagesAreUnaffected` pass.

- [ ] **Step 3: Implement**

**Part A — `SearchWikiTool` (`wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java`).** Nine edits; each "replace exactly" text occurs once in the current file. Apply in any order.

**Edit 1 — imports: add `Session`.** Replace exactly:

```java
import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.ContextQuery;
```

with:

```java
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.ContextQuery;
```


**Edit 2 — imports: add `Wiki` and `PermissionFilter`.** Replace exactly:

```java
import com.wikantik.api.querylog.SourceSurface;

import jakarta.servlet.http.HttpServletRequest;
```

with:

```java
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.permissions.PermissionFilter;

import jakarta.servlet.http.HttpServletRequest;
```


**Edit 3 — imports: add `Collection`, `LinkedHashSet`, `Set`.** Replace exactly:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
```

with:

```java
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
```


**Edit 4 — class Javadoc: document the view filtering.** Replace exactly:

```java
 * <p>Delegates hybrid rerank, dense retrieval, and BM25 fallback to the
 * service, keeping this class focused on response shaping.</p>
 */
```

with:

```java
 * <p>Delegates hybrid rerank, dense retrieval, and BM25 fallback to the
 * service, keeping this class focused on response shaping.</p>
 *
 * <p>The retrieval service applies no view-ACL filtering of its own, so this class drops every
 * result (and every {@code relatedPages} reference) the caller may not view, using the same
 * {@link PermissionFilter} rule {@link GetPageTool} enforces for {@code get_page}. Hidden results
 * are removed before the result cap and {@code total} are computed, so neither the response size
 * nor {@code total} reveals how many restricted pages matched.</p>
 */
```


**Edit 5 — `execute`: compute the viewable set once and skip hidden results before the result cap is applied.** Replace exactly:

```java
        final String publicBaseUrl = config.publicBaseUrl();
        final List< Map< String, Object > > shaped = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            if ( shaped.size() >= clamped ) break;
            shaped.add( buildEntry( p, request, publicBaseUrl ) );
        }
```

with:

```java
        // No catch: if the permission check itself fails the request fails (the servlet answers 500)
        // rather than falling back to unfiltered results.
        final Set< String > viewable = viewableNames( request, candidateNames( retrieval.pages() ) );

        final String publicBaseUrl = config.publicBaseUrl();
        final List< Map< String, Object > > shaped = new ArrayList<>();
        for ( final RetrievedPage p : retrieval.pages() ) {
            if ( shaped.size() >= clamped ) break;
            if ( !viewable.contains( p.name() ) ) continue;
            shaped.add( buildEntry( p, request, publicBaseUrl, viewable ) );
        }
```


**Edit 6 — new methods `viewableNames` (the real gate and the unit-test seam), `candidateNames`, `addName` — inserted immediately above `errorResult`.** Replace exactly:

```java
    /** Builds the fixed-error-shape response body shared by both {@link #execute} failure paths. */
```

with:

```java
    /**
     * Returns the subset of {@code pageNames} the caller bound to {@code request} may view. Silent
     * (no {@code access.denied} audit rows): hiding restricted hits from a bulk result is a
     * visibility concern, not an enforcement denial. Package-visible so unit tests can substitute a
     * stub gate; production resolves the wiki session exactly as {@link GetPageTool#canView} does
     * (a stateless API-key request resolves to the anonymous guest session).
     */
    Set< String > viewableNames( final HttpServletRequest request, final Collection< String > pageNames ) {
        final Session session = Wiki.session().find( engine, request );
        return new PermissionFilter( engine ).filterViewableQuietly( session, pageNames );
    }

    /** Every page name the response could mention: each result plus each of its related pages. */
    private static Set< String > candidateNames( final List< RetrievedPage > pages ) {
        final Set< String > names = new LinkedHashSet<>();
        for ( final RetrievedPage p : pages ) {
            addName( names, p.name() );
            for ( final RelatedPage r : p.relatedPages() ) {
                addName( names, r.name() );
            }
        }
        return names;
    }

    private static void addName( final Set< String > names, final String name ) {
        if ( name != null && !name.isBlank() ) {
            names.add( name );
        }
    }

    /** Builds the fixed-error-shape response body shared by both {@link #execute} failure paths. */
```


**Edit 7 — `buildEntry` signature: accept the viewable set.** Replace exactly:

```java
    private static Map< String, Object > buildEntry( final RetrievedPage p, final HttpServletRequest request,
                                                      final String publicBaseUrl ) {
```

with:

```java
    private static Map< String, Object > buildEntry( final RetrievedPage p, final HttpServletRequest request,
                                                      final String publicBaseUrl, final Set< String > viewable ) {
```


**Edit 8 — `buildEntry` body: pass it on to `appendRelatedPages`.** Replace exactly:

```java
        appendRelatedPages( entry, p.relatedPages() );
```

with:

```java
        appendRelatedPages( entry, p.relatedPages(), viewable );
```


**Edit 9 — `appendRelatedPages`: list only viewable related pages; emit no key when none remain.** Replace exactly:

```java
    /** No-op when {@code related} is empty; otherwise adds the {@code relatedPages} array. */
    private static void appendRelatedPages( final Map< String, Object > entry, final List< RelatedPage > related ) {
        if ( related.isEmpty() ) return;
        final List< Map< String, Object > > relatedOut = new ArrayList<>( related.size() );
        for ( final RelatedPage r : related ) {
            final Map< String, Object > rEntry = ResultShaper.orderedMap();
            rEntry.put( "name", r.name() );
            rEntry.put( "reason", r.reason() );
            relatedOut.add( rEntry );
        }
        entry.put( "relatedPages", relatedOut );
    }
```

with:

```java
    /** Adds the {@code relatedPages} array, listing only pages the caller may view; no-op when none remain. */
    private static void appendRelatedPages( final Map< String, Object > entry, final List< RelatedPage > related,
                                            final Set< String > viewable ) {
        final List< Map< String, Object > > relatedOut = new ArrayList<>( related.size() );
        for ( final RelatedPage r : related ) {
            if ( !viewable.contains( r.name() ) ) continue;
            final Map< String, Object > rEntry = ResultShaper.orderedMap();
            rEntry.put( "name", r.name() );
            rEntry.put( "reason", r.reason() );
            relatedOut.add( rEntry );
        }
        if ( relatedOut.isEmpty() ) return;
        entry.put( "relatedPages", relatedOut );
    }
```


**Part A (continued) — keep the existing `SearchWikiToolTest` green.** Its `engine` is a Mockito mock, so the real gate cannot run there; give it a permissive gate exactly as `GetPageToolTest#allowingTool` does, and add two tests for the seam itself. Do these four steps in this order (the `sed` must run before the helper and the new tests are added, because the new anonymous-class tests also contain `new SearchWikiTool( engine, new ToolsConfig( new Properties() ) )`):

1. Redirect the 11 existing construction sites to the helper:

```bash
sed -i 's/new SearchWikiTool( engine, new ToolsConfig( \(new Properties()\|props\) ) )/allowingTool( new ToolsConfig( \1 ) )/g' \
  wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java
grep -c 'allowingTool( new ToolsConfig' wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java   # expect 11
grep -c 'new SearchWikiTool' wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java             # expect 0
```

2. Replace the import block exactly:

```java
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
```

with:

```java
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
```

3. Add the helper. Replace exactly:

```java
    @Mock HttpServletRequest request;

    @Test
    void returnsErrorPayloadWhenServiceNotConfigured() {
```

with:

```java
    @Mock HttpServletRequest request;

    /**
     * Builds a tool whose view gate admits every page, so the shaping assertions below need no
     * real engine wiring (mirrors {@code GetPageToolTest#allowingTool}). The real gate is covered
     * end to end, against real ACLs and sessions, by {@link SearchWikiToolViewPermissionTest}.
     */
    private SearchWikiTool allowingTool( final ToolsConfig config ) {
        return new SearchWikiTool( engine, config ) {
            @Override
            Set< String > viewableNames( final HttpServletRequest req, final Collection< String > pageNames ) {
                return new HashSet<>( pageNames );
            }
        };
    }

    @Test
    void returnsErrorPayloadWhenServiceNotConfigured() {
```

4. Append these two tests immediately before the final `}` of the class:

```java
    @Test
    void viewGateFailureFailsTheRequestInsteadOfReturningUnfilteredResults() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "q", List.of( RetrievedPage.builder( "Secret", 1.0 )
                .url( "" )
                .summary( "classified" )
                .tags( List.of() )
                .contributingChunks( List.of() )
                .relatedPages( List.of() )
                .build() ), 1 ) );
        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) ) {
            @Override
            Set< String > viewableNames( final HttpServletRequest req, final Collection< String > pageNames ) {
                throw new IllegalStateException( "authorization subsystem unavailable" );
            }
        };

        // The servlet turns this into a 500; the point is that nothing unfiltered is returned.
        assertThrows( IllegalStateException.class, () -> tool.execute( "q", 10, request ) );
    }

    @Test
    void viewGateIsAskedAboutTheCallersRequestAndEveryNameTheResponseCouldMention() {
        when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctxService );
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "q", List.of(
                RetrievedPage.builder( "Alpha", 2.0 )
                    .url( "" ).summary( "" ).tags( List.of() ).contributingChunks( List.of() )
                    .relatedPages( List.of( new RelatedPage( "Beta", "shared entities: x" ) ) )
                    .build(),
                RetrievedPage.builder( "Gamma", 1.0 )
                    .url( "" ).summary( "" ).tags( List.of() ).contributingChunks( List.of() )
                    .relatedPages( List.of() )
                    .build() ), 2 ) );
        final AtomicReference< HttpServletRequest > seenRequest = new AtomicReference<>();
        final AtomicReference< Collection< String > > seenNames = new AtomicReference<>();
        final SearchWikiTool tool = new SearchWikiTool( engine, new ToolsConfig( new Properties() ) ) {
            @Override
            Set< String > viewableNames( final HttpServletRequest req, final Collection< String > pageNames ) {
                seenRequest.set( req );
                seenNames.set( pageNames );
                return new HashSet<>( pageNames );
            }
        };

        tool.execute( "q", 10, request );

        assertSame( request, seenRequest.get(), "the gate must evaluate the caller's own request" );
        assertEquals( Set.of( "Alpha", "Beta", "Gamma" ), new HashSet<>( seenNames.get() ),
            "result names and related-page names are all checked, once each" );
    }
```

**Part B — `RecentChangesPlugin` (`wikantik-main/src/main/java/com/wikantik/plugin/RecentChangesPlugin.java`).** Six edits; each "replace exactly" text occurs once in the current file (the file mixes tabs and spaces in places — the anchors below are on space-indented lines only).

**Edit 1 — imports: add `PermissionFilter`.** Replace exactly:

```java
import com.wikantik.api.managers.SystemPageRegistry;
```

with:

```java
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.auth.permissions.PermissionFilter;
```


**Edit 2 — imports: add `List` and `Set`.** Replace exactly:

```java
import java.util.GregorianCalendar;
import java.util.Map;
```

with:

```java
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
```


**Edit 3 — class Javadoc: document the filtering and the flag.** Replace exactly:

```java
 *  <li><b>dateFormat</b> - the date format to use, the default is "dd.MM.yyyy"</li>
 *  </ul>
 */
```

with:

```java
 *  <li><b>dateFormat</b> - the date format to use, the default is "dd.MM.yyyy"</li>
 *  </ul>
 *
 *  <p>Only pages the viewer of the hosting page may <em>view</em> are listed (an attachment follows its parent page's ACL).
 *  Because the listing therefore depends on who is looking, the render is flagged
 *  {@link Context#VAR_VIEWER_SENSITIVE} and is never stored in the shared render caches.</p>
 */
```


**Edit 4 — `execute`: flag the render viewer-sensitive, straight after the `engine` local.** Replace exactly:

```java
        final Engine engine = context.getEngine();
```

with:

```java
        final Engine engine = context.getEngine();

        // The listing is filtered by the viewer's view permission, so it differs per viewer; keep it out of the
        // shared, principal-less render caches (PluginContent also flags every executed plugin; this keeps the
        // guarantee local to the plugin for callers that reach it by another route).
        context.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
```


**Edit 5 — `execute`: filter the collected changes by view permission.** Replace exactly:

```java
        changes = filterWikiPageCollection( changes );
```

with:

```java
        changes = filterWikiPageCollection( changes );
        changes = filterViewable( context, changes );
```


**Edit 6 — new private method `filterViewable`, inserted immediately above `isSameDay`.** Replace exactly:

```java
    private boolean isSameDay( final Date a, final Date b ) {
```

with:

```java
    /**
     *  Drops every entry the viewer of this render may not view. The check is silent (no {@code access.denied}
     *  audit rows): hiding restricted entries from a bulk listing is a visibility concern, not an enforcement
     *  denial. Attachments need no special case: the permission check resolves an attachment name to the
     *  attachment and takes its ACL from the parent page.
     */
    private static List< Page > filterViewable( final Context context, final Collection< Page > pages ) {
        final Set< String > viewable = new PermissionFilter( context.getEngine() ).filterViewableQuietly(
                context.getWikiSession(), pages.stream().map( Page::getName ).toList() );
        return pages.stream().filter( p -> viewable.contains( p.getName() ) ).toList();
    }

    private boolean isSameDay( final Date a, final Date b ) {
```


- [ ] **Step 4: Run the tests to verify they pass**

```bash
# Part A: the new test, the edited test, and the whole tools module (expect 104 tests, 0 failures)
mvn -B -o test -pl wikantik-tools -Dtest='SearchWikiToolViewPermissionTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -o test -pl wikantik-tools

# Part B: the new test, the two existing RecentChanges classes, the other viewer-sensitive plugin, and the arch freeze test
mvn -B -o test -pl wikantik-main -Dtest='RecentChangesPlugin*Test,InsertPageTest,DecompositionArchTest' -Dsurefire.failIfNoSpecifiedTests=false

# Complexity ratchet (no new violation expected: the only added branch is one `continue` in SearchWikiTool.execute)
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-tools
mvn -B -o pmd:check -Pcomplexity-gate -pl wikantik-main
```

Expected: every command ends in `BUILD SUCCESS`; `SearchWikiToolViewPermissionTest` 8/8, `RecentChangesPluginViewPermissionTest` 7/7, `RecentChangesPluginTest` 5/5, `RecentChangesPluginCITest` 8/8. `DecompositionArchTest` can rewrite its freeze store even when red; if `git status` shows changes under `wikantik-main/src/test/resources/archunit_store/`, run `git checkout -- wikantik-main/src/test/resources/archunit_store` instead of committing them (this task adds no `getManager` callers, so the store should be untouched). The full `bin/run-tests.sh --parallel 4` gate belongs at the end of the whole plan (Tasks 22/24), not here.

- [ ] **Step 5: Commit** (two commits, one per defect; stage by name, never `git add -A`; work on `main`)

Use the Co-Authored-By trailer your own session specifies (the line below is the one this draft was written under).

```bash
git add wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java \
        wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolTest.java \
        wikantik-tools/src/test/java/com/wikantik/tools/SearchWikiToolViewPermissionTest.java
git commit -F - <<'EOF'
fix(tools): search_wiki drops results and related pages the caller cannot view

Retrieval applies no view ACL; filter with PermissionFilter before the cap, as get_page does.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
EOF

git add wikantik-main/src/main/java/com/wikantik/plugin/RecentChangesPlugin.java \
        wikantik-main/src/test/java/com/wikantik/plugin/RecentChangesPluginViewPermissionTest.java
git commit -F - <<'EOF'
fix(plugin): RecentChangesPlugin lists only pages the viewer may view

Filter by the render's view permission and flag the render viewer-sensitive.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
EOF
```

#### Notes for the coordinator (Task 25)

1. **Tools identity is anonymous-guest, and `OpenApiDocument` says otherwise.** `OpenApiDocument.java:85-87` tells clients the tool server "runs requests under that principal so page ACLs … apply exactly as they would for that user's interactive session". In fact an API-key request resolves to the transient anonymous guest session (`WikiSession.needsPersistentSession`, `ApiKeyPrincipalRequest` Javadoc), so `get_page` and now `search_wiki` enforce the **anonymous** view permission; an admin-owned key cannot read restricted pages through `/tools`. That is the safe direction and matches the knowledge-MCP guest gate (`docs/admin/McpAgents.md:85`), so I left behaviour and text alone. Task 23 (docs) should either correct that description or the owner should decide whether keys should carry their owner's session; either is outside this task. Consequence for the requested "absent for non-admin, present for admin" tests: the admin case is a logged-in admin request, not an admin-owned API key.
2. **Scope additions beyond the brief, both deliberate:** (a) `relatedPages` entries on visible results are filtered too (they name other pages plus an entity-sharing reason, a second leak path); (b) the plugin check treats attachments through the normal path (no special case needed, pinned by a test).
3. **No over-fetch.** `execute` still asks the service for `min(maxResults, 20)` pages, so hidden hits can make a response shorter than requested (same as the knowledge MCP's `retrieve_context`). Over-fetching to `ContextQuery.MAX_PAGES_CAP` and trimming after the filter would improve recall for restricted-heavy corpora but changes retrieval cost; not done. The query-log `result_count` stays the pre-filter retrieved count (it measures corpus coverage, not what one caller saw).
4. **Same leak class remains in sibling plugins (out of scope; no task in the contract's numbering covers them):** `ReferringPagesPlugin`, `IndexPlugin`, `UnusedPagesPlugin`, `UndefinedPagesPlugin`, `ReferringUndefinedPagesPlugin` and `HubSetPlugin` all extend `AbstractReferralPlugin`, whose `filterCollection` has no view check. By inspection they list page titles of restricted pages. The new private `RecentChangesPlugin.filterViewable` is the shape for a follow-up that hoists a `protected` filter into `AbstractReferralPlugin.filterCollection` (it would need the context's session captured in `initialize`). Suggest filing an issue.
5. **Render cache.** The brief's cache concern is already handled upstream: `PluginContent.invoke` flags every plugin execution viewer-sensitive. The plugin-local flag plus `renderedHostPageIsNotSharedBetweenViewers` keep that guarantee from regressing; the real defect was only the missing filter.
6. **Task 22 (leak probe IT)** can add a `/tools/search_wiki` probe (restricted title absent from a BM25-answerable query). Prove it on `/tools/*`, not MCP: the IT sets `mcp.access.allowUnrestricted=true`, which makes MCP bearer checks vacuous. Optional; not required by this task.
7. **Security release.** Both fixes close confidentiality leaks; Task 23's CHANGELOG should list them under a Security heading and Task 24 should treat them as patch-release material.
8. **Verification performed while drafting** (in a private scratch copy under `/tmp/.../scratchpad/task25`, version-retargeted to `2.4.57-SNAPSHOT` because `2.4.58-SNAPSHOT` jars are not in `~/.m2`; the repository itself was not modified): the Step 1 tests failed 6/8 and 5/7 before the change with the failure texts quoted in Step 2; after the change the whole `wikantik-tools` module (104 tests), `RecentChangesPlugin*Test` (20), `InsertPageTest` (8) and `DecompositionArchTest` (5) pass; `pmd:check -Pcomplexity-gate` passes for `wikantik-tools`; for `wikantik-main` it could not run in the scratch copy (`skin cannot be null`, an artefact of the trimmed scratch reactor), so run it in the real tree. A mutation check (checking attachments by their own name instead of relying on the ACL fallback) did not fail the attachment test, which is why no attachment special case is in the plugin. The Step 3 "replace exactly" blocks were machine-applied to pristine copies of the three production/test files and produced byte-identical results to the validated scratch versions.

---

# Section E — UI, data, verification, docs, rollout (Tasks 20–24)

# Section E — Tasks 20–24 (profile and admin UI, data cleanup, leak probe, docs, release)

Conventions: commands run from the repository root `/home/jakefear/source/jspwiki` unless they start with `cd`.
Unified diffs are written against the tree as it is today (HEAD `bf12845fff`, 2.4.58-SNAPSHOT); apply one with
`git apply <file>`, or make the edit by hand (the `+` lines are the exact new text). Commit footers use the
contract's `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` line.

Verification done while drafting, so the code below is known to work and not merely plausible: the Task 20 changes
were applied to a scratch copy of the frontend and the whole vitest suite (250 files, 2,878 tests) plus `eslint` on
every changed file passed; the Task 21 SQL, wrapper (dry run, `--apply`, second run, failure path) and JUnit test ran
against a throwaway `pgvector/pgvector:pg18` container (8 of 8 JUnit tests green, and 5 of 8 red under a deliberately
broken script); the Task 21 shell guard passes under `shellcheck`; the Task 22 integration test compiles against the
real MCP SDK 2.0.1, Gson and JUnit APIs (it could not be run here: it needs the Cargo-launched WAR from Tasks 1–19).

Dependency order inside this section: Task 20 and Task 21 are independent of each other and of Sections B–D.
Task 22 needs every read surface switched (Tasks 5–19) and is the acceptance test for them. Task 23 states the
final behaviour of Tasks 1–19. Task 24 needs 20–23 committed.

---

### Task 20: Display name in Preferences and Admin → Users

**Files:**
- Create: `wikantik-frontend/src/utils/displayName.js`
- Create: `wikantik-frontend/src/utils/displayName.test.js`
- Modify: `wikantik-frontend/src/components/UserPreferencesPage.jsx` (imports line 5; `useAuth` line 9; state line 22; `handleSubmit` lines 53-84; helper styles after line 136; Login Name and Full Name fields lines 195-224)
- Modify: `wikantik-frontend/src/components/UserPreferencesPage.test.jsx` (`mockLogout` line 23 and `beforeEach` lines 42-51; new `describe` appended)
- Modify: `wikantik-frontend/src/components/admin/UserFormModal.jsx` (state, submit handler and the Login/Full Name fields; the modal that backs both Create User and Edit User)
- Modify: `wikantik-frontend/src/components/admin/UserFormModal.test.jsx` (two existing tests must now fill the display name; new `describe` appended)
- Modify: `wikantik-frontend/src/components/admin/AdminUsersPage.jsx:11` (column label "Full Name" becomes "Display name"; the table already shows the login in its own column)
- Modify: `wikantik-frontend/src/components/admin/AdminUsersPage.test.jsx` (two new tests)

**Interfaces:**
- Consumes: `PUT /api/auth/profile` (body `{fullName, email, bio, currentPassword?, newPassword?}`) and `POST /admin/users` / `PUT /admin/users/{login}`, which refuse a rule violation with `400` or `409` and `{error, status, message}`; `api/client.js` already throws `Object.assign(new Error(body.message), { status, body })`, so `err.status` and `err.body.message` are what the components read. The server rules are Task 1's `ProfileNameRules.displayNameError` (required; 2–64 characters; no control characters; no `@`; reserved; another account's login; already taken) and apply only when the name *changes* (`fullNameChangeError`), which the UI mirrors. `useAuth().refresh` (already exists) re-reads `/api/auth/user` so the sidebar shows the new name; that only reflects the change because Task 4 refreshes the live session on profile save.
- Produces: `src/utils/displayName.js` exports `DISPLAY_NAME_MIN` (2), `DISPLAY_NAME_MAX` (64), `displayNameError(value) → string | null`, `displayNameChanged(current, next) → boolean`, `isDisplayNameRejection(err, nameIsOnlyChange) → boolean`. Nothing in Sections A–D imports it.

Design notes. The browser checks only what it can: required, length 2–64 after trimming, no control character, no `@`.
Reserved names, another account's login and "already taken" stay server-only and arrive as `400`/`409`; both
components show that message under the Display name field (a `409` always, a `400` only when the name was the only
thing the request tried to change, so a password-policy `400` is never mislabelled) and keep the red banner for
everything else. Like the server, the UI does not re-validate an unchanged name, so a legacy name that breaks the
rules never blocks saving the bio or email. The login name stays visible to its owner, read-only, with a line saying who can see it.

- [ ] **Step 1: Write the failing tests**

Create `wikantik-frontend/src/utils/displayName.test.js`:

```js
import { describe, it, expect } from 'vitest';
import {
  DISPLAY_NAME_MAX,
  DISPLAY_NAME_MIN,
  displayNameChanged,
  displayNameError,
  isDisplayNameRejection,
} from './displayName';

describe('displayNameError', () => {
  it('accepts an ordinary name and the exact length bounds', () => {
    expect(displayNameError('Test User')).toBeNull();
    expect(displayNameError('Al')).toBeNull();
    expect(displayNameError('a'.repeat(DISPLAY_NAME_MAX))).toBeNull();
  });

  it('requires a non-blank name', () => {
    expect(displayNameError('')).toBe('Display name is required');
    expect(displayNameError('   ')).toBe('Display name is required');
    expect(displayNameError(null)).toBe('Display name is required');
    expect(displayNameError(undefined)).toBe('Display name is required');
  });

  it('measures length after trimming', () => {
    expect(displayNameError(' A ')).toBe(`Display name must be at least ${DISPLAY_NAME_MIN} characters`);
    expect(displayNameError(`  ${'a'.repeat(DISPLAY_NAME_MAX)}  `)).toBeNull();
    expect(displayNameError('a'.repeat(DISPLAY_NAME_MAX + 1)))
      .toBe(`Display name must be ${DISPLAY_NAME_MAX} characters or fewer`);
  });

  it("rejects '@', so an email address cannot be a display name", () => {
    expect(displayNameError('someone@example.com')).toBe("Display name cannot contain '@'");
    expect(displayNameError('Jo @ Home')).toBe("Display name cannot contain '@'");
  });

  it('rejects control characters inside the name', () => {
    expect(displayNameError('Jo\u0000hn')).toBe('Display name cannot contain control characters');
    expect(displayNameError('Jo\u0085hn')).toBe('Display name cannot contain control characters');
    expect(displayNameError('Jo\u007Fhn')).toBe('Display name cannot contain control characters');
  });

  it('reports the length rule before the character rules', () => {
    expect(displayNameError('@')).toBe(`Display name must be at least ${DISPLAY_NAME_MIN} characters`);
  });
});

describe('displayNameChanged', () => {
  it('compares trimmed values and treats absent as empty', () => {
    expect(displayNameChanged('Test User', ' Test User ')).toBe(false);
    expect(displayNameChanged('Test User', 'Test  User')).toBe(true);
    expect(displayNameChanged(undefined, '')).toBe(false);
    expect(displayNameChanged(null, 'New')).toBe(true);
  });
});

describe('isDisplayNameRejection', () => {
  it('attributes every 409 to the display name', () => {
    expect(isDisplayNameRejection({ status: 409 }, false)).toBe(true);
  });

  it('attributes a 400 only when the name was the only change', () => {
    expect(isDisplayNameRejection({ status: 400 }, true)).toBe(true);
    expect(isDisplayNameRejection({ status: 400 }, false)).toBe(false);
  });

  it('never attributes other statuses or a missing error', () => {
    expect(isDisplayNameRejection({ status: 500 }, true)).toBe(false);
    expect(isDisplayNameRejection({ status: 403 }, true)).toBe(false);
    expect(isDisplayNameRejection(undefined, true)).toBe(false);
  });
});
```

Apply this diff to `wikantik-frontend/src/components/UserPreferencesPage.test.jsx` (the `beforeEach` now supplies
`refresh`, then a new `describe` is appended):

```diff
--- a/wikantik-frontend/src/components/UserPreferencesPage.test.jsx
+++ b/wikantik-frontend/src/components/UserPreferencesPage.test.jsx
@@ -21,6 +21,7 @@
 }));
 
 const mockLogout = vi.fn();
+const mockRefresh = vi.fn();
 vi.mock('../hooks/useAuth', () => ({
   useAuth: vi.fn(),
 }));
@@ -47,7 +48,8 @@
   api.deleteAccount.mockResolvedValue(null);
   api.logout.mockResolvedValue(null);
   mockLogout.mockResolvedValue(undefined);
-  useAuth.mockReturnValue({ user: FAKE_USER, logout: mockLogout });
+  mockRefresh.mockResolvedValue(undefined);
+  useAuth.mockReturnValue({ user: FAKE_USER, logout: mockLogout, refresh: mockRefresh });
 });
 
 function renderPage() {
@@ -349,3 +351,128 @@
     expect(screen.getByTestId('delete-confirm-input')).toHaveValue('');
   });
 });
+
+describe('UserPreferencesPage — display name', () => {
+  const clickSave = () => fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+  const rejection = (status, message) =>
+    Object.assign(new Error('generic'), { status, body: { message } });
+
+  it('labels the editable field "Display name" and shows the login name read-only', async () => {
+    renderPage();
+    const display = await screen.findByLabelText('Display name');
+    expect(display).toHaveValue('Test User');
+    expect(display).not.toHaveAttribute('readonly');
+    expect(display).toHaveAttribute('aria-required', 'true');
+    const login = screen.getByLabelText('Login name');
+    expect(login).toHaveValue('testuser');
+    expect(login).toHaveAttribute('readonly');
+    expect(screen.queryByText('Full Name')).not.toBeInTheDocument();
+  });
+
+  it.each([
+    ['', 'Display name is required'],
+    ['   ', 'Display name is required'],
+    ['x', 'Display name must be at least 2 characters'],
+    ['a'.repeat(65), 'Display name must be 64 characters or fewer'],
+    ['me@example.com', "Display name cannot contain '@'"],
+  ])('rejects %j on save without calling the API', async (value, message) => {
+    renderPage();
+    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value } });
+    clickSave();
+
+    expect(await screen.findByTestId('display-name-error')).toHaveTextContent(message);
+    expect(screen.getByLabelText('Display name')).toHaveAttribute('aria-invalid', 'true');
+    expect(api.updateProfile).not.toHaveBeenCalled();
+  });
+
+  it('shows a client-side rule as soon as the field is left, before any save', async () => {
+    renderPage();
+    const display = await screen.findByLabelText('Display name');
+    fireEvent.change(display, { target: { value: 'x' } });
+    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
+
+    fireEvent.blur(display);
+
+    expect(screen.getByTestId('display-name-error'))
+      .toHaveTextContent('Display name must be at least 2 characters');
+  });
+
+  it('does not re-validate an unchanged legacy name, so it never blocks saving the bio', async () => {
+    api.getProfile.mockResolvedValue({ ...FAKE_PROFILE, fullName: 'legacy@example.com' });
+    api.updateProfile.mockResolvedValue({ ...FAKE_PROFILE, fullName: 'legacy@example.com', bio: 'hi' });
+    renderPage();
+    await screen.findByDisplayValue('legacy@example.com');
+    fireEvent.change(screen.getByPlaceholderText('Tell others about yourself...'), { target: { value: 'hi' } });
+    clickSave();
+
+    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith({
+      fullName: 'legacy@example.com', email: 'test@example.com', bio: 'hi',
+    }));
+    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
+  });
+
+  it('saves the trimmed name, shows the stored one and refreshes the session user', async () => {
+    api.updateProfile.mockResolvedValue({ ...FAKE_PROFILE, fullName: 'New Name' });
+    renderPage();
+    const display = await screen.findByLabelText('Display name');
+    fireEvent.change(display, { target: { value: '  New Name  ' } });
+    clickSave();
+
+    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith({
+      fullName: 'New Name', email: 'test@example.com', bio: '',
+    }));
+    expect(await screen.findByText('Profile updated successfully')).toBeInTheDocument();
+    expect(display).toHaveValue('New Name');
+    expect(mockRefresh).toHaveBeenCalledTimes(1);
+  });
+
+  it('puts the server 409 message under the field, not in the page banner', async () => {
+    api.updateProfile.mockRejectedValue(rejection(409, 'That display name is already used by another account'));
+    renderPage();
+    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Taken Name' } });
+    clickSave();
+
+    expect(await screen.findByTestId('display-name-error'))
+      .toHaveTextContent('That display name is already used by another account');
+    expect(document.querySelector('.error-banner')).toBeNull();
+    expect(mockRefresh).not.toHaveBeenCalled();
+  });
+
+  it('puts a server 400 under the field when the display name is the only change', async () => {
+    api.updateProfile.mockRejectedValue(rejection(400, "The name 'Admin' is reserved for a role or group"));
+    renderPage();
+    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Admin' } });
+    clickSave();
+
+    expect(await screen.findByTestId('display-name-error'))
+      .toHaveTextContent("The name 'Admin' is reserved for a role or group");
+    expect(document.querySelector('.error-banner')).toBeNull();
+  });
+
+  it('keeps a server 400 in the banner when a password change was part of the request', async () => {
+    api.updateProfile.mockRejectedValue(rejection(400, 'Password is too common'));
+    renderPage();
+    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'New Name' } });
+    const { current, newPw, confirmPw } = getPasswordInputs();
+    fireEvent.change(current, { target: { value: 'oldpass' } });
+    fireEvent.change(newPw, { target: { value: 'newpass1' } });
+    fireEvent.change(confirmPw, { target: { value: 'newpass1' } });
+    clickSave();
+
+    expect(await screen.findByText('Password is too common')).toBeInTheDocument();
+    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
+  });
+
+  it('clears the server message once the name is edited again', async () => {
+    api.updateProfile.mockRejectedValue(rejection(409, 'That display name is already used by another account'));
+    renderPage();
+    const display = await screen.findByLabelText('Display name');
+    fireEvent.change(display, { target: { value: 'Taken Name' } });
+    clickSave();
+    await screen.findByTestId('display-name-error');
+
+    fireEvent.change(display, { target: { value: 'Another Name' } });
+
+    expect(screen.queryByTestId('display-name-error')).not.toBeInTheDocument();
+  });
+});
```

Apply this diff to `wikantik-frontend/src/components/admin/UserFormModal.test.jsx` (the two existing
"error" tests fill the display name first, because an empty one is now refused in the browser; a new `describe` is appended):

```diff
--- a/wikantik-frontend/src/components/admin/UserFormModal.test.jsx
+++ b/wikantik-frontend/src/components/admin/UserFormModal.test.jsx
@@ -116,6 +116,7 @@
     const { container } = render(<UserFormModal isOpen onClose={onClose} onSave={onSave} />);
     const f = fields(container);
     fireEvent.change(f.login, { target: { value: 'u' } });
+    fireEvent.change(f.fullName, { target: { value: 'Valid Name' } });
     fireEvent.change(f.password, { target: { value: 'longenough' } });
     fireEvent.click(screen.getByRole('button', { name: 'Create User' }));
     await screen.findByText('email taken');
@@ -129,8 +130,115 @@
     const { container } = render(<UserFormModal isOpen onClose={vi.fn()} onSave={onSave} />);
     const f = fields(container);
     fireEvent.change(f.login, { target: { value: 'u' } });
+    fireEvent.change(f.fullName, { target: { value: 'Valid Name' } });
     fireEvent.change(f.password, { target: { value: 'longenough' } });
     fireEvent.click(screen.getByRole('button', { name: 'Create User' }));
     await screen.findByText(/Session expired or not authorized/);
   });
 });
+
+describe('UserFormModal — display name', () => {
+  const USER = { loginName: 'alice', fullName: 'Alice A', email: 'alice@x.com', bio: '' };
+  const rejection = (status, message) => Object.assign(new Error(message), { status });
+
+  it('labels the fields "Login name" and "Display name"', () => {
+    render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={vi.fn()} />);
+    expect(screen.getByLabelText('Login name')).toBeDisabled();
+    expect(screen.getByLabelText('Display name')).toHaveValue('Alice A');
+    expect(screen.queryByText('Full Name')).toBeNull();
+  });
+
+  it.each([
+    ['', 'Display name is required'],
+    ['x', 'Display name must be at least 2 characters'],
+    ['a'.repeat(65), 'Display name must be 64 characters or fewer'],
+    ['a@b.example', "Display name cannot contain '@'"],
+  ])('blocks creating an account with display name %j', async (value, message) => {
+    const onSave = vi.fn();
+    render(<UserFormModal isOpen onClose={vi.fn()} onSave={onSave} />);
+    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'newuser' } });
+    fireEvent.change(screen.getByLabelText('Display name'), { target: { value } });
+    fireEvent.change(document.querySelector('input[type="password"]'), { target: { value: 'longenough' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Create User' }));
+
+    expect(await screen.findByTestId('display-name-error')).toHaveTextContent(message);
+    expect(onSave).not.toHaveBeenCalled();
+  });
+
+  it('does not re-validate an unchanged legacy display name when editing', async () => {
+    const onSave = vi.fn().mockResolvedValue();
+    const legacy = { ...USER, fullName: 'alice@x.com' };
+    render(<UserFormModal isOpen user={legacy} onClose={vi.fn()} onSave={onSave} />);
+    fireEvent.change(document.querySelector('textarea'), { target: { value: 'new bio' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    await waitFor(() => expect(onSave).toHaveBeenCalledWith(
+      expect.objectContaining({ loginName: 'alice', fullName: 'alice@x.com', bio: 'new bio' })));
+  });
+
+  it('validates a changed display name when editing', async () => {
+    const onSave = vi.fn();
+    render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={onSave} />);
+    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'bob@x.com' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    expect(await screen.findByTestId('display-name-error')).toHaveTextContent("cannot contain '@'");
+    expect(onSave).not.toHaveBeenCalled();
+  });
+
+  it('saves the trimmed display name', async () => {
+    const onSave = vi.fn().mockResolvedValue();
+    render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={onSave} />);
+    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: '  Alice B  ' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ fullName: 'Alice B' })));
+  });
+
+  it('shows the server 409 message under the field and stays open', async () => {
+    const onSave = vi.fn().mockRejectedValue(rejection(409, 'That display name is already used by another account'));
+    const onClose = vi.fn();
+    render(<UserFormModal isOpen user={USER} onClose={onClose} onSave={onSave} />);
+    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Bob Builder' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    expect(await screen.findByTestId('display-name-error'))
+      .toHaveTextContent('That display name is already used by another account');
+    expect(document.querySelector('.error-banner')).toBeNull();
+    expect(onClose).not.toHaveBeenCalled();
+  });
+
+  it('shows a server 400 under the field when only the display name changed', async () => {
+    const onSave = vi.fn().mockRejectedValue(rejection(400, "The name 'Admin' is reserved for a role or group"));
+    render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={onSave} />);
+    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Admin' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    expect(await screen.findByTestId('display-name-error'))
+      .toHaveTextContent("The name 'Admin' is reserved for a role or group");
+  });
+
+  it('keeps a server 400 in the banner when a new password was submitted with it', async () => {
+    const onSave = vi.fn().mockRejectedValue(rejection(400, 'Password is too common'));
+    render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={onSave} />);
+    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Alice B' } });
+    fireEvent.change(document.querySelector('input[type="password"]'), { target: { value: 'password1' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    expect(await screen.findByText('Password is too common')).toBeInTheDocument();
+    expect(screen.queryByTestId('display-name-error')).toBeNull();
+  });
+
+  it('clears the server message when the name is edited again', async () => {
+    const onSave = vi.fn().mockRejectedValue(rejection(409, 'taken'));
+    render(<UserFormModal isOpen user={USER} onClose={vi.fn()} onSave={onSave} />);
+    const input = screen.getByLabelText('Display name');
+    fireEvent.change(input, { target: { value: 'Bob Builder' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+    await screen.findByTestId('display-name-error');
+
+    fireEvent.change(input, { target: { value: 'Robert Builder' } });
+
+    expect(screen.queryByTestId('display-name-error')).toBeNull();
+  });
+});
```

Apply this diff to `wikantik-frontend/src/components/admin/AdminUsersPage.test.jsx`:

```diff
--- a/wikantik-frontend/src/components/admin/AdminUsersPage.test.jsx
+++ b/wikantik-frontend/src/components/admin/AdminUsersPage.test.jsx
@@ -78,6 +78,33 @@
     expect(screen.getByText('charlie')).toBeTruthy();
   });
 
+  it('shows both the login and the display name, labelled as such', async () => {
+    render(<AdminUsersPage />);
+    await screen.findByText('alice');
+
+    expect(screen.getByRole('columnheader', { name: /^Login/ })).toBeInTheDocument();
+    expect(screen.getByRole('columnheader', { name: /^Display name/ })).toBeInTheDocument();
+    expect(screen.queryByText('Full Name')).toBeNull();
+    const aliceRow = screen.getByText('alice').closest('tr');
+    expect(within(aliceRow).getByText('Alice Admin')).toBeInTheDocument();
+  });
+
+  it('surfaces the server message when an admin edit renames to a taken display name', async () => {
+    api.admin.updateUser.mockRejectedValue(
+      Object.assign(new Error('That display name is already used by another account'), { status: 409 }));
+    render(<AdminUsersPage />);
+    await screen.findByText('alice');
+
+    const aliceRow = screen.getByText('alice').closest('tr');
+    fireEvent.click(within(aliceRow).getByRole('button', { name: 'Edit' }));
+    fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Bob Builder' } });
+    fireEvent.click(screen.getByRole('button', { name: 'Save Changes' }));
+
+    expect(await screen.findByTestId('display-name-error'))
+      .toHaveTextContent('That display name is already used by another account');
+    expect(api.admin.updateUser).toHaveBeenCalledWith('alice', { fullName: 'Bob Builder', email: 'alice@example.com' });
+  });
+
   it('shows Create User button', async () => {
     render(<AdminUsersPage />);
     await screen.findByText('alice');
```

- [ ] **Step 2: Run them to verify they fail**

```bash
cd wikantik-frontend && npx vitest run src/utils/displayName.test.js src/components/UserPreferencesPage.test.jsx src/components/admin/UserFormModal.test.jsx src/components/admin/AdminUsersPage.test.jsx
```

Expected: `src/utils/displayName.test.js` fails to load (`Failed to resolve import "./displayName"`); 12 failures in
`UserPreferencesPage.test.jsx` (`Unable to find a label with the text of: Display name`, and the inline-error
tests); 13 failures across `UserFormModal.test.jsx` and `AdminUsersPage.test.jsx` (no `Display name` label or
`columnheader`, no `display-name-error` element). Every previously existing test still passes.

- [ ] **Step 3: Implement**

Create `wikantik-frontend/src/utils/displayName.js`:

```js
// Client-side mirror of the display-name rules in com.wikantik.auth.ProfileNameRules
// (displayNameError). The server is authoritative: it also rejects reserved names, names equal
// to another account's login and names already taken, and its 400/409 message is shown to the
// user as-is. These checks only save a round trip for the rules the browser can evaluate.

export const DISPLAY_NAME_MIN = 2;
export const DISPLAY_NAME_MAX = 64;

// ISO control characters: C0 (U+0000-U+001F) and DEL + C1 (U+007F-U+009F). A loop rather than a
// regex keeps eslint's no-control-regex quiet.
function hasControlCharacter(text) {
  for (const ch of text) {
    const code = ch.codePointAt(0);
    if (code < 0x20 || (code >= 0x7f && code <= 0x9f)) return true;
  }
  return false;
}

/** The first failing rule as a user-facing message, or null when the name is acceptable. */
export function displayNameError(value) {
  const name = (value ?? '').trim();
  if (!name) return 'Display name is required';
  if (name.length < DISPLAY_NAME_MIN) {
    return `Display name must be at least ${DISPLAY_NAME_MIN} characters`;
  }
  if (name.length > DISPLAY_NAME_MAX) {
    return `Display name must be ${DISPLAY_NAME_MAX} characters or fewer`;
  }
  if (hasControlCharacter(name)) return 'Display name cannot contain control characters';
  if (name.includes('@')) return "Display name cannot contain '@'";
  return null;
}

/** True when {@code next} differs from {@code current} once both are trimmed (the server's own test). */
export function displayNameChanged(current, next) {
  return (next ?? '').trim() !== (current ?? '').trim();
}

/**
 * Whether a failed save should put its message under the Display name field rather than in the
 * page banner. A 409 is always a name conflict (the only conflict a profile save reports). A 400
 * is attributed to the name only when the name is the sole thing the request tried to change, so
 * a password-policy 400 is never mislabelled.
 */
export function isDisplayNameRejection(err, nameIsOnlyChange) {
  return err?.status === 409 || (nameIsOnlyChange && err?.status === 400);
}
```

Apply this diff to `wikantik-frontend/src/components/UserPreferencesPage.jsx`:

```diff
--- a/wikantik-frontend/src/components/UserPreferencesPage.jsx
+++ b/wikantik-frontend/src/components/UserPreferencesPage.jsx
@@ -3,10 +3,11 @@
 import { api } from '../api/client';
 import { useNavigate } from 'react-router-dom';
 import MyApiKeys from './MyApiKeys';
+import { displayNameChanged, displayNameError, isDisplayNameRejection } from '../utils/displayName';
 
 
 export default function UserPreferencesPage() {
-  const { user, logout } = useAuth();
+  const { user, logout, refresh } = useAuth();
   const navigate = useNavigate();
 
   const [profile, setProfile] = useState(null);
@@ -21,6 +22,10 @@
   const [saving, setSaving] = useState(false);
   const [message, setMessage] = useState(null);
   const [error, setError] = useState(null);
+  // Display name feedback: the server's 400/409 message, and whether the field has been left once
+  // (so client-side rules are not shown while the user is still typing the first character).
+  const [nameError, setNameError] = useState(null);
+  const [nameTouched, setNameTouched] = useState(false);
 
   // Delete account state
   const [showDeleteSection, setShowDeleteSection] = useState(false);
@@ -50,10 +55,22 @@
     return () => { ignore = true; };
   }, [user, navigate]);
 
+  // The server re-checks a display name only when it changes, so the browser does the same: a
+  // legacy name that no longer satisfies the rules never blocks saving the bio or the email.
+  const nameChanged = displayNameChanged(profile?.fullName, fullName);
+  const clientNameError = nameChanged ? displayNameError(fullName) : null;
+  const shownNameError = nameError || (nameTouched ? clientNameError : null);
+
   const handleSubmit = async (e) => {
     e.preventDefault();
     setError(null);
     setMessage(null);
+    setNameError(null);
+
+    if (clientNameError) {
+      setNameTouched(true);
+      return;
+    }
 
     if (newPassword && newPassword !== confirmPassword) {
       setError('New passwords do not match');
@@ -62,19 +79,27 @@
 
     setSaving(true);
     try {
-      const data = { fullName, email, bio };
+      const data = { fullName: fullName.trim(), email, bio };
       if (newPassword) {
         data.currentPassword = currentPassword;
         data.newPassword = newPassword;
       }
       const updated = await api.updateProfile(data);
       setProfile(updated);
+      setFullName(updated.fullName ?? data.fullName);
       setCurrentPassword('');
       setNewPassword('');
       setConfirmPassword('');
       setMessage('Profile updated successfully');
+      // The live session now carries the new name; re-read it so the sidebar shows it too.
+      await refresh();
     } catch (err) {
-      setError(err.body?.message || err.message || 'Failed to update profile');
+      const message = err.body?.message || err.message || 'Failed to update profile';
+      if (isDisplayNameRejection(err, nameChanged && !newPassword)) {
+        setNameError(message);
+      } else {
+        setError(message);
+      }
     } finally {
       setSaving(false);
     }
@@ -135,6 +160,18 @@
     marginBottom: 'var(--space-md)',
   };
 
+  const helpStyle = {
+    margin: 'var(--space-xs) 0 0',
+    fontSize: '0.75rem',
+    color: 'var(--text-muted)',
+    fontFamily: 'var(--font-ui)',
+  };
+
+  const fieldErrorStyle = {
+    ...helpStyle,
+    color: 'var(--color-danger, #ef4444)',
+  };
+
   return (
     <div style={{ maxWidth: '560px', margin: '0 auto', padding: 'var(--space-xl)' }}>
       <h1 style={{
@@ -193,13 +230,18 @@
           </legend>
 
           <div style={fieldStyle}>
-            <label style={labelStyle}>Login Name</label>
+            <label htmlFor="pref-login-name" style={labelStyle}>Login name</label>
             <input
+              id="pref-login-name"
               type="text"
               value={profile?.loginName || ''}
               readOnly
+              aria-describedby="pref-login-name-help"
               style={readOnlyStyle}
             />
+            <p id="pref-login-name-help" style={helpStyle}>
+              You sign in with this. Only you and administrators can see it; everyone else sees your display name.
+            </p>
           </div>
 
           <div style={fieldStyle}>
@@ -213,13 +255,36 @@
           </div>
 
           <div style={fieldStyle}>
-            <label style={labelStyle}>Full Name</label>
+            <label htmlFor="pref-display-name" style={labelStyle}>Display name</label>
             <input
+              id="pref-display-name"
               type="text"
               value={fullName}
-              onChange={e => setFullName(e.target.value)}
-              style={inputStyle}
+              onChange={e => { setFullName(e.target.value); setNameError(null); }}
+              onBlur={() => setNameTouched(true)}
+              autoComplete="nickname"
+              aria-required="true"
+              aria-invalid={shownNameError ? 'true' : undefined}
+              aria-describedby={shownNameError ? 'pref-display-name-error' : 'pref-display-name-help'}
+              style={shownNameError
+                ? { ...inputStyle, borderColor: 'var(--color-danger, #ef4444)' }
+                : inputStyle}
             />
+            {shownNameError ? (
+              <div
+                id="pref-display-name-error"
+                role="alert"
+                data-testid="display-name-error"
+                style={fieldErrorStyle}
+              >
+                {shownNameError}
+              </div>
+            ) : (
+              <p id="pref-display-name-help" style={helpStyle}>
+                The name other people see on your edits, comments and mentions. Required; 2 to 64
+                characters; no &ldquo;@&rdquo;; and not already used by someone else.
+              </p>
+            )}
           </div>
 
           <div style={fieldStyle}>
```

Apply this diff to `wikantik-frontend/src/components/admin/UserFormModal.jsx`:

```diff
--- a/wikantik-frontend/src/components/admin/UserFormModal.jsx
+++ b/wikantik-frontend/src/components/admin/UserFormModal.jsx
@@ -1,4 +1,5 @@
 import { useState } from 'react';
+import { displayNameChanged, displayNameError, isDisplayNameRejection } from '../../utils/displayName';
 
 // Derives the form's field values from a user record (edit mode) or the blank
 // defaults (create mode). Shared by the initial-mount lazy initializer and the
@@ -14,6 +15,9 @@
   const [form, setForm] = useState(() => buildForm(user));
   const [saving, setSaving] = useState(false);
   const [error, setError] = useState(null);
+  // The server's 400/409 message for the display name, and whether the field has been left once.
+  const [nameError, setNameError] = useState(null);
+  const [nameTouched, setNameTouched] = useState(false);
 
   const [prevUser, setPrevUser] = useState(user);
   const [prevIsOpen, setPrevIsOpen] = useState(isOpen);
@@ -22,20 +26,35 @@
     setPrevIsOpen(isOpen);
     setForm(buildForm(user));
     setError(null);
+    setNameError(null);
+    setNameTouched(false);
   }
 
   if (!isOpen) return null;
 
+  // A new account must have a valid display name. For an existing account the server re-checks it
+  // only when it changes, so a legacy name that breaks the rules does not block other edits.
+  const nameChanged = !isEdit || displayNameChanged(user.fullName, form.fullName);
+  const clientNameError = nameChanged ? displayNameError(form.fullName) : null;
+  const shownNameError = nameError || (nameTouched ? clientNameError : null);
+
   const handleSubmit = async (e) => {
     e.preventDefault();
-    setSaving(true);
     setError(null);
+    setNameError(null);
+    if (clientNameError) {
+      setNameTouched(true);
+      return;
+    }
+    setSaving(true);
     try {
-      await onSave(form);
+      await onSave({ ...form, fullName: form.fullName.trim() });
       onClose();
     } catch (err) {
       if (err.status === 403) {
         setError('Session expired or not authorized. Please refresh the page and log in again.');
+      } else if (isDisplayNameRejection(err, isEdit && nameChanged && !form.password)) {
+        setNameError(err.message || 'That display name is not allowed');
       } else {
         setError(err.message || 'Failed to save user');
       }
@@ -45,6 +64,7 @@
   };
 
   const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));
+  const setName = (e) => { set('fullName')(e); setNameError(null); };
 
   return (
     <div className="modal-overlay" onClick={onClose}>
@@ -57,8 +77,9 @@
 
         <form onSubmit={handleSubmit}>
           <div className="form-field">
-            <label>Login Name</label>
+            <label htmlFor="user-form-login-name">Login name</label>
             <input
+              id="user-form-login-name"
               type="text"
               value={form.loginName}
               onChange={set('loginName')}
@@ -66,10 +87,37 @@
               required
               autoFocus={!isEdit}
             />
+            <small style={{ color: 'var(--text-secondary)', display: 'block', marginTop: '0.25rem' }}>
+              Used to sign in and in access rules. Only administrators and the account owner see it.
+            </small>
           </div>
           <div className="form-field">
-            <label>Full Name</label>
-            <input type="text" value={form.fullName} onChange={set('fullName')} autoFocus={isEdit} />
+            <label htmlFor="user-form-display-name">Display name</label>
+            <input
+              id="user-form-display-name"
+              type="text"
+              value={form.fullName}
+              onChange={setName}
+              onBlur={() => setNameTouched(true)}
+              autoFocus={isEdit}
+              aria-required="true"
+              aria-invalid={shownNameError ? 'true' : undefined}
+              aria-describedby={shownNameError ? 'user-form-display-name-error' : undefined}
+            />
+            {shownNameError ? (
+              <small
+                id="user-form-display-name-error"
+                role="alert"
+                data-testid="display-name-error"
+                style={{ color: 'var(--color-danger, #ef4444)', display: 'block', marginTop: '0.25rem' }}
+              >
+                {shownNameError}
+              </small>
+            ) : (
+              <small style={{ color: 'var(--text-secondary)', display: 'block', marginTop: '0.25rem' }}>
+                What other people see. Required, 2 to 64 characters, no &ldquo;@&rdquo;, unique.
+              </small>
+            )}
           </div>
           <div className="form-field">
             <label>Email</label>
```

Apply this diff to `wikantik-frontend/src/components/admin/AdminUsersPage.jsx`:

```diff
--- a/wikantik-frontend/src/components/admin/AdminUsersPage.jsx
+++ b/wikantik-frontend/src/components/admin/AdminUsersPage.jsx
@@ -8,7 +8,7 @@
 
 const COLUMNS = [
   { id: 'loginName', label: 'Login', sortable: true },
-  { id: 'fullName', label: 'Full Name', render: (u) => u.fullName || '—', sortable: true },
+  { id: 'fullName', label: 'Display name', render: (u) => u.fullName || '—', sortable: true },
   { id: 'email', label: 'Email', render: (u) => u.email || '—' },
   {
     id: 'created',
```

- [ ] **Step 4: Run the tests, the linter and the whole frontend suite**

```bash
cd wikantik-frontend
npx vitest run src/utils/displayName.test.js src/components/UserPreferencesPage.test.jsx src/components/admin/UserFormModal.test.jsx src/components/admin/AdminUsersPage.test.jsx
npx eslint src/utils/displayName.js src/utils/displayName.test.js src/components/UserPreferencesPage.jsx src/components/UserPreferencesPage.test.jsx src/components/admin/UserFormModal.jsx src/components/admin/UserFormModal.test.jsx src/components/admin/AdminUsersPage.jsx src/components/admin/AdminUsersPage.test.jsx
npx vitest run
```

Expected: 86 tests pass in the four files; `eslint` prints nothing; the full run ends with
`Test Files  250 passed` and `Tests  2878 passed` (counts will be a little higher once Task 16's tests land).
If a test in another file fails with "Unable to find ... Full Name", that file asserts the old label; change it to
`Display name` (none did when this was drafted).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/displayName.js wikantik-frontend/src/utils/displayName.test.js \
        wikantik-frontend/src/components/UserPreferencesPage.jsx wikantik-frontend/src/components/UserPreferencesPage.test.jsx \
        wikantik-frontend/src/components/admin/UserFormModal.jsx wikantik-frontend/src/components/admin/UserFormModal.test.jsx \
        wikantik-frontend/src/components/admin/AdminUsersPage.jsx wikantik-frontend/src/components/admin/AdminUsersPage.test.jsx
git commit -m "feat(ui): Display name in Preferences and Admin → Users, with the server's reason shown inline" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 21: One-time script that makes display names unique

**Files:**
- Create: `bin/db/one-shots/dedupe_display_names.sql`
- Create: `bin/db/one-shots/dedupe_display_names.sh` (mode 755, like its siblings)
- Create: `wikantik-main/src/test/java/com/wikantik/auth/user/DedupeDisplayNamesSqlTest.java`
- Create: `bin/tests/test-dedupe-display-names.sh` (mode 755; CI's `shell-tests` job runs every `bin/tests/test-*.sh`)
- Modify: `docs/admin/WikantikOperations.md:539` (the `bin/db/one-shots/` row) and the row above `bin/tests/test-audit-retention.sh`

**Interfaces:**
- Consumes: the `users` (`login_name`, `full_name`, `created`, `modified`) and `groups` (`name`) tables from `V002__core_users_groups.sql`; no Java from other tasks. `PostgresTestDb`, `MigrationApplier.findRepoRoot()` and `@RequiresPostgres` from the `wikantik-jdbc` test-jar (the same ones `KgResetMachineTierSqlTest` uses).
- Produces: `bin/db/one-shots/dedupe_display_names.sh [--apply]` (dry run by default; `PGHOST/PGPORT/PGUSER/PGPASSWORD/DB_NAME` like the other one-shots, plus the `PSQL_COMMAND` hook that Task 24 uses to reach the production container: `PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh`).

Design notes (read before changing anything):
- **Dry run is a rehearsal, not a second code path.** The `.sh` runs the same SQL inside `BEGIN; SET LOCAL wikantik.dedupe_apply = 'on'; <sql>; SELECT <report>; ROLLBACK` (dry run) or `COMMIT` (`--apply`), so what it prints is exactly what `--apply` will do, including any constraint error. A failing statement stops `psql` (`ON_ERROR_STOP`) before `COMMIT`, and the server rolls the open transaction back when the connection closes.
- **The SQL refuses to run on its own.** It raises unless `wikantik.dedupe_apply` is `on`, so a stray `psql -f` cannot change data.
- **The SQL file is one `DO` block with only full-line comments.** The `.sh` strips comment lines and folds newlines so the statement travels as a single `psql -c` argument. That is what lets it pass through `bin/remote.sh psql`, whose ssh/`printf %q` quoting is fragile for multi-line arguments (and the proven prod path is `remote.sh psql -- -c …`). The script refuses to run if a `--` survives the stripping.
- **The report prints display names only.** A blank or `@`-containing old name is shown as `(blank)` / `(email-shaped)`, never the value, because with Google sign-in that value is an email address. The report has no login column.
- **Candidates are skipped when taken** by another account's display name or login name (ignoring case) or by a group name. `'<name> N'` is cut to fit 64 characters. `users.wiki_name` is deliberately left alone; `users.modified` is bumped for renamed rows.
- **Why a script and not a migration or an index:** migrations run on every container start and must stay DDL-only (see `docs/admin/DatabaseMigrations.md`: "Data backfills are not versioned migrations"); a unique index would fail at startup on the existing duplicates, so uniqueness is enforced in the application (Task 1) and this script repairs the legacy rows once.

- [ ] **Step 1: Write the failing tests**

Create `wikantik-main/src/test/java/com/wikantik/auth/user/DedupeDisplayNamesSqlTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.auth.user;

import com.wikantik.jdbc.testing.MigrationApplier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code bin/db/one-shots/dedupe_display_names.sql} against a real PostgreSQL carrying every
 * migration. Each test runs inside one transaction that {@link #rollback()} discards, after
 * emptying {@code users} inside it, so the numbers the script picks ("Member 1", "Jake Fear 2")
 * depend only on the rows the test seeds and nothing leaks into other test classes sharing the
 * container.
 */
@RequiresPostgres
class DedupeDisplayNamesSqlTest {

    private Connection conn;

    @BeforeEach
    void begin() throws SQLException {
        conn = PostgresTestDb.createDataSource().getConnection();
        conn.setAutoCommit( false );
        try ( Statement s = conn.createStatement() ) {
            s.execute( "DELETE FROM users" );
        }
    }

    @AfterEach
    void rollback() throws SQLException {
        try {
            conn.rollback();
        } finally {
            conn.close();
        }
    }

    private void insertUser( final String login, final String fullName, final String wikiName, final String created )
            throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (uid, email, full_name, login_name, wiki_name, created) VALUES (?, ?, ?, ?, ?, ?)" ) ) {
            ps.setString( 1, "uid-" + login );
            ps.setString( 2, login + "@example.test" );
            ps.setString( 3, fullName );
            ps.setString( 4, login );
            ps.setString( 5, wikiName );
            ps.setTimestamp( 6, Timestamp.valueOf( created + " 00:00:00" ) );
            ps.executeUpdate();
        }
    }

    private void insertGroup( final String name ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO groups (name, created, modified) VALUES (?, now(), now())" ) ) {
            ps.setString( 1, name );
            ps.executeUpdate();
        }
    }

    private String script() {
        try {
            return Files.readString( MigrationApplier.findRepoRoot().resolve( "bin/db/one-shots/dedupe_display_names.sql" ) );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "cannot read dedupe_display_names.sql", e );
        }
    }

    /** Runs the script the way dedupe_display_names.sh does: with the apply setting, inside the open transaction. */
    private void runScript() throws SQLException {
        try ( Statement s = conn.createStatement() ) {
            s.execute( "SET LOCAL wikantik.dedupe_apply = 'on'" );
            s.execute( script() );
        }
    }

    private Map< String, String > displayNames() throws SQLException {
        final Map< String, String > out = new LinkedHashMap<>();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery( "SELECT login_name, full_name FROM users ORDER BY login_name" ) ) {
            while ( rs.next() ) {
                out.put( rs.getString( 1 ), rs.getString( 2 ) );
            }
        }
        return out;
    }

    private String scalar( final String sql ) throws SQLException {
        try ( Statement s = conn.createStatement(); ResultSet rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    /** The report as "reason|old|new" lines, in the order the script made the changes. */
    private List< String > report() throws SQLException {
        final List< String > out = new ArrayList<>();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery(
                  "SELECT reason, old_display, new_display FROM dedupe_display_names_report ORDER BY seq" ) ) {
            while ( rs.next() ) {
                out.add( rs.getString( 1 ) + "|" + rs.getString( 2 ) + "|" + rs.getString( 3 ) );
            }
        }
        return out;
    }

    @Test
    void earliestAccountKeepsItsNameAndTheOthersAreNumberedSkippingTakenNumbers() throws SQLException {
        insertUser( "dup-a", "Jake Fear", "JakeFear", "2026-01-01" );
        insertUser( "dup-b", "jake fear", "JakeFear2", "2026-02-01" );
        insertUser( "dup-c", "  JAKE FEAR  ", "JakeFear3", "2026-03-01" );
        insertUser( "dup-d", "Jake Fear", "JakeFear4", "2026-04-01" );
        insertUser( "dup-e", "Jake Fear 3", "JakeFear33", "2026-05-01" );
        insertUser( "dup-f", "Alice", "Alice", "2026-06-01" );

        runScript();

        assertEquals( Map.of(
                "dup-a", "Jake Fear",
                "dup-b", "jake fear 2",
                "dup-c", "JAKE FEAR 4",
                "dup-d", "Jake Fear 5",
                "dup-e", "Jake Fear 3",
                "dup-f", "Alice" ), displayNames(),
            "the earliest keeps its name; later duplicates take the lowest free number (3 is held by dup-e)" );
        assertEquals( List.of(
                "duplicate|jake fear|jake fear 2",
                "duplicate|JAKE FEAR|JAKE FEAR 4",
                "duplicate|Jake Fear|Jake Fear 5" ), report() );
    }

    @Test
    void leavesWikiNamesAndUntouchedRowsAlone() throws SQLException {
        insertUser( "keep-a", "Sam Doe", "SamDoe", "2026-01-01" );
        insertUser( "keep-b", "Sam Doe", "SamDoe2", "2026-02-01" );
        insertUser( "keep-c", "Pat Roe", "PatRoe", "2026-03-01" );

        runScript();

        assertEquals( "SamDoe2", scalar( "SELECT wiki_name FROM users WHERE login_name = 'keep-b'" ),
            "wiki_name is not touched" );
        assertEquals( "Sam Doe 2", scalar( "SELECT full_name FROM users WHERE login_name = 'keep-b'" ) );
        assertNull( scalar( "SELECT modified FROM users WHERE login_name = 'keep-c'" ),
            "an account that needed no change keeps its modified timestamp (NULL in this fixture)" );
        assertNotNull( scalar( "SELECT modified FROM users WHERE login_name = 'keep-b'" ),
            "a renamed account's modified timestamp is bumped" );
    }

    @Test
    void blankNullAndEmailShapedNamesBecomeMemberNSkippingTakenNumbers() throws SQLException {
        insertUser( "blank-w", "Member 1", "Member1", "2026-01-01" );
        insertUser( "blank-x", "", "BlankX", "2026-02-01" );
        insertUser( "blank-y", null, "BlankY", "2026-03-01" );
        insertUser( "blank-z", "someone@example.test", "SomeoneAtExample", "2026-04-01" );
        insertUser( "blank-v", "   ", "BlankV", "2026-05-01" );

        runScript();

        assertEquals( Map.of(
                "blank-w", "Member 1",
                "blank-x", "Member 2",
                "blank-y", "Member 3",
                "blank-z", "Member 4",
                "blank-v", "Member 5" ), displayNames() );
        assertEquals( List.of(
                "blank|(blank)|Member 2",
                "blank|(blank)|Member 3",
                "email-shaped|(email-shaped)|Member 4",
                "blank|(blank)|Member 5" ), report() );
    }

    @Test
    void theReportCarriesDisplayNamesOnlyNeverLoginsOrEmails() throws SQLException {
        insertUser( "leaky@example.test", "leaky@example.test", "Leaky", "2026-01-01" );
        insertUser( "twin1@example.test", "Twin Name", "TwinName", "2026-02-01" );
        insertUser( "twin2@example.test", "Twin Name", "TwinName2", "2026-03-01" );

        runScript();

        final String everything = String.join( "\n", report() );
        assertFalse( everything.contains( "example.test" ), "no login or email in the report: " + everything );
        assertFalse( everything.contains( "leaky" ), "no login or email in the report: " + everything );
        assertEquals( List.of( "email-shaped|(email-shaped)|Member 1", "duplicate|Twin Name|Twin Name 2" ), report() );
    }

    @Test
    void aSecondRunChangesNothing() throws SQLException {
        insertUser( "idem-a", "Same Name", "SameName", "2026-01-01" );
        insertUser( "idem-b", "Same Name", "SameName2", "2026-02-01" );
        insertUser( "idem-c", "", "IdemC", "2026-03-01" );
        runScript();
        final Map< String, String > afterFirst = displayNames();
        assertFalse( report().isEmpty(), "fixture sanity: the first run changed something" );

        runScript();

        assertEquals( afterFirst, displayNames() );
        assertEquals( List.of(), report(), "nothing left to change" );
    }

    @Test
    void candidatesEqualToAnotherAccountsLoginOrAGroupNameAreSkipped() throws SQLException {
        insertUser( "skip-a", "Sam Doe", "SamDoe", "2026-01-01" );
        insertUser( "skip-b", "Sam Doe", "SamDoe2", "2026-02-01" );
        insertUser( "Sam Doe 2", "Someone Else", "SomeoneElse", "2026-03-01" );
        insertGroup( "sam doe 3" );

        runScript();

        assertEquals( "Sam Doe 4", scalar( "SELECT full_name FROM users WHERE login_name = 'skip-b'" ),
            "'Sam Doe 2' is another account's login and 'Sam Doe 3' is a group name" );
    }

    @Test
    void theSuffixIsAppendedWithinTheSixtyFourCharacterLimit() throws SQLException {
        final String longName = "b".repeat( 64 );
        insertUser( "long-a", longName, "LongA", "2026-01-01" );
        insertUser( "long-b", longName, "LongB", "2026-02-01" );

        runScript();

        final String renamed = scalar( "SELECT full_name FROM users WHERE login_name = 'long-b'" );
        assertEquals( 64, renamed.length() );
        assertEquals( "b".repeat( 62 ) + " 2", renamed );
    }

    @Test
    void refusesToRunWithoutTheApplySetting() throws SQLException {
        insertUser( "refuse-a", "Dup Name", "DupName", "2026-01-01" );
        insertUser( "refuse-b", "Dup Name", "DupName2", "2026-02-01" );
        final Savepoint before = conn.setSavepoint();

        final SQLException refused = assertThrows( SQLException.class, () -> {
            try ( Statement s = conn.createStatement() ) {
                s.execute( script() );
            }
        } );

        assertTrue( refused.getMessage().contains( "dedupe_display_names.sh" ), refused.getMessage() );
        conn.rollback( before );
        assertEquals( "Dup Name", displayNames().get( "refuse-b" ), "nothing was changed" );
    }
}
```

Create `bin/tests/test-dedupe-display-names.sh` (then `chmod +x bin/tests/test-dedupe-display-names.sh`):

```bash
#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# Offline regression guard for bin/db/one-shots/dedupe_display_names.sh (+ .sql). No database: psql is
# a stub on PATH that records its arguments. It pins what is easy to break silently:
#   * the dry run ends in ROLLBACK and never COMMITs; --apply COMMITs and never ROLLBACKs;
#   * the data-changing SQL cannot run before the dedupe_apply setting is switched on;
#   * the SQL collapses to one line (no trailing "--" comment) and its report never selects a login
#     name or an email address;
#   * PSQL_COMMAND routes psql through a wrapper (the production path is `bin/remote.sh psql --`).
# The behavioural proof against a real PostgreSQL is wikantik-main DedupeDisplayNamesSqlTest.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SH="${REPO_ROOT}/bin/db/one-shots/dedupe_display_names.sh"
SQL="${REPO_ROOT}/bin/db/one-shots/dedupe_display_names.sql"

TMPS=()
cleanup() { local d; for d in "${TMPS[@]+"${TMPS[@]}"}"; do [[ -d "$d" ]] && rm -rf "$d"; done; return 0; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# A sandbox mirroring bin/db/one-shots/ (so the script's ROOT.xml fallback finds nothing) with a stub
# psql on PATH. Each psql call appends one "CALL" block of its arguments, one per line, to $CMDLOG.
make_sandbox() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("$tmp")
    mkdir -p "${tmp}/bin/db/one-shots" "${tmp}/stubs"
    cp "$SH" "$SQL" "${tmp}/bin/db/one-shots/"
    cat > "${tmp}/stubs/psql" <<'STUB'
#!/usr/bin/env bash
{ echo "CALL"; for a in "$@"; do printf 'ARG:%s\n' "$a"; done; } >> "$CMDLOG"
exit "${PSQL_RC:-0}"
STUB
    cat > "${tmp}/stubs/fake-remote" <<'STUB'
#!/usr/bin/env bash
{ echo "REMOTE"; for a in "$@"; do printf 'ARG:%s\n' "$a"; done; } >> "$CMDLOG"
exit 0
STUB
    chmod +x "${tmp}/stubs/psql" "${tmp}/stubs/fake-remote"
    echo "$tmp"
}

# run_script SANDBOX [args...] -- runs the sandboxed script with the stub on PATH, a fresh CMDLOG.
run_script() {
    local sb="$1"; shift
    export CMDLOG="${sb}/cmdlog"; : > "$CMDLOG"
    PATH="${sb}/stubs:${PATH}" PGPASSWORD=secret "${sb}/bin/db/one-shots/dedupe_display_names.sh" "$@"
}

# The SQL without full-line comments, one line.
sql_statements() { sed -e '/^[[:space:]]*--/d' "$SQL" | tr '\n' ' ' | tr -s '[:space:]' ' '; }

test_sql_is_guarded_and_collapsible() {
    [[ -f "$SQL" ]] || fail "${SQL} is missing"
    local sql; sql="$(sql_statements)"
    [[ "$sql" == *"wikantik.dedupe_apply"* ]] || fail "the SQL no longer checks wikantik.dedupe_apply"
    [[ "$sql" == *"RAISE EXCEPTION"* ]] || fail "the SQL no longer raises when the apply setting is off"
    [[ "$sql" != *--* ]] || fail "the SQL has a trailing '--' comment; the .sh would comment out the rest of the statement"
    ok "SQL is apply-guarded and survives being collapsed to one line"
}

test_report_never_selects_logins_or_emails() {
    local inserts; inserts="$(sql_statements | grep -oE 'INSERT INTO dedupe_display_names_report[^;]*;' || true)"
    [[ -n "$inserts" ]] || fail "no INSERT INTO dedupe_display_names_report found"
    # String literals ('email-shaped') are labels, not columns, so drop them before looking.
    local columns; columns="$(sed -E "s/'[^']*'//g" <<<"$inserts")"
    if grep -qiE 'login_name|email' <<<"$columns"; then
        fail "a report INSERT references login_name/email: ${inserts}"
    fi
    ok "report rows are built from display names only"
}

test_dry_run_rolls_back() {
    local sb; sb="$(make_sandbox)"
    run_script "$sb" > "${sb}/out"
    grep -q '^ARG:ROLLBACK$' "${sb}/cmdlog" || fail "dry run did not ROLLBACK"
    if grep -q '^ARG:COMMIT$' "${sb}/cmdlog"; then fail "dry run COMMITs"; fi
    grep -q 'DRY-RUN' "${sb}/out" || fail "dry run does not say so"
    ok "default is a dry run that ends in ROLLBACK"
}

test_apply_commits() {
    local sb; sb="$(make_sandbox)"
    run_script "$sb" --apply > "${sb}/out"
    grep -q '^ARG:COMMIT$' "${sb}/cmdlog" || fail "--apply did not COMMIT"
    if grep -q '^ARG:ROLLBACK$' "${sb}/cmdlog"; then fail "--apply ROLLBACKs"; fi
    ok "--apply ends in COMMIT"
}

test_setting_is_switched_on_before_the_sql() {
    local sb; sb="$(make_sandbox)"
    run_script "$sb" > /dev/null
    local begin set_on sql
    begin="$(grep -n '^ARG:BEGIN$' "${sb}/cmdlog" | head -1 | cut -d: -f1 || true)"
    set_on="$(grep -n "^ARG:SET LOCAL wikantik.dedupe_apply = 'on'$" "${sb}/cmdlog" | head -1 | cut -d: -f1 || true)"
    # shellcheck disable=SC2016
    sql="$(grep -n '^ARG: *DO \$dedupe\$' "${sb}/cmdlog" | head -1 | cut -d: -f1 || true)"
    [[ -n "$begin" && -n "$set_on" && -n "$sql" ]] || fail "BEGIN / SET LOCAL / DO not all issued (${begin}/${set_on}/${sql})"
    [[ "$begin" -lt "$set_on" && "$set_on" -lt "$sql" ]] || fail "order must be BEGIN, SET LOCAL, DO (${begin}/${set_on}/${sql})"
    ok "BEGIN, then the apply setting, then the SQL"
}

test_psql_failure_fails_the_script() {
    local sb; sb="$(make_sandbox)"
    if PSQL_RC=3 run_script "$sb" --apply > "${sb}/out" 2>&1; then
        fail "a failing psql must fail the script"
    fi
    ok "a psql failure fails the script"
}

test_psql_command_wrapper_is_used() {
    local sb; sb="$(make_sandbox)"
    PSQL_COMMAND="${sb}/stubs/fake-remote psql --" run_script "$sb" > "${sb}/out"
    [[ "$(sed -n 1p "${sb}/cmdlog")" == "REMOTE" ]] || fail "PSQL_COMMAND wrapper was not used"
    [[ "$(sed -n 2p "${sb}/cmdlog")" == "ARG:psql" && "$(sed -n 3p "${sb}/cmdlog")" == "ARG:--" ]] \
        || fail "wrapper must receive its own leading arguments first: $(sed -n 1,4p "${sb}/cmdlog" | tr '\n' ' ')"
    if grep -q '^CALL$' "${sb}/cmdlog"; then fail "psql was called directly despite PSQL_COMMAND"; fi
    ok "PSQL_COMMAND routes psql through the wrapper"
}

test_unknown_argument_and_help() {
    local sb; sb="$(make_sandbox)"
    local rc=0
    run_script "$sb" --bogus > "${sb}/out" 2>&1 || rc=$?
    [[ "$rc" -eq 2 ]] || fail "unknown argument must exit 2 (got ${rc})"
    run_script "$sb" --help | grep -q -- '--apply' || fail "--help does not describe --apply"
    ok "unknown arguments are refused; --help works"
}

test_missing_password_is_refused_without_calling_psql() {
    local sb; sb="$(make_sandbox)"
    export CMDLOG="${sb}/cmdlog"; : > "$CMDLOG"
    local rc=0
    PATH="${sb}/stubs:${PATH}" PGPASSWORD='' "${sb}/bin/db/one-shots/dedupe_display_names.sh" > "${sb}/out" 2>&1 || rc=$?
    [[ "$rc" -eq 1 ]] || fail "a missing password must exit 1 (got ${rc})"
    [[ ! -s "$CMDLOG" ]] || fail "psql was called without a password"
    ok "no password, no psql"
}

test_sql_is_guarded_and_collapsible
test_report_never_selects_logins_or_emails
test_dry_run_rolls_back
test_apply_commits
test_setting_is_switched_on_before_the_sql
test_psql_failure_fails_the_script
test_psql_command_wrapper_is_used
test_unknown_argument_and_help
test_missing_password_is_refused_without_calling_psql

echo "PASS: test-dedupe-display-names.sh (9 cases)"
```

- [ ] **Step 2: Run them to verify they fail**

```bash
docker ps >/dev/null && echo "docker ok"          # the JUnit test needs a Docker daemon; -Dtests.requireDocker=true makes its absence a failure, not a skip
mvn -B -o test -pl wikantik-main -Dtest='DedupeDisplayNamesSqlTest' -Dsurefire.failIfNoSpecifiedTests=false -Dtests.requireDocker=true
bash bin/tests/test-dedupe-display-names.sh
```

Expected: the Maven run reports 8 errors, each `UncheckedIOException: cannot read dedupe_display_names.sql` (the
`refusesToRunWithoutTheApplySetting` test fails with an unexpected exception type for the same reason). The shell
guard prints `FAIL: .../bin/db/one-shots/dedupe_display_names.sql is missing`. If Maven cannot resolve
`2.4.58-SNAPSHOT` dependencies, first run
`bin/agent-build.sh start install-main -- mvn -B -o install -pl wikantik-main -am -DskipTests` and poll it with
`bin/agent-build.sh wait install-main 540`.

- [ ] **Step 3: Implement**

Create `bin/db/one-shots/dedupe_display_names.sql`:

```sql
-- dedupe_display_names.sql -- one-time data repair (NOT a migration) for the display-name rules.
--
-- Display names (users.full_name) are now unique, case-insensitively and trimmed, and must not be
-- blank or contain '@'. This script brings existing rows in line:
--   * a blank, NULL or '@'-containing display name becomes 'Member N' (smallest free N from 1);
--   * in each group of accounts whose trimmed display names match ignoring case, the EARLIEST
--     created account (ties: login name) keeps its name and the others become '<name> 2',
--     '<name> 3', ... using the lowest number not already taken.
-- A candidate is taken when another account already has it as a display name or as a login name
-- (ignoring case), or when a group has that name. users.wiki_name is left alone. Sessions of a
-- renamed account carry the old name until the next sign-in; read-time identity resolution picks
-- the new one up within a minute.
--
-- IT WRITES. It refuses to run unless wikantik.dedupe_apply = 'on' is set, so a stray
-- 'psql -f' cannot change data. Run it through bin/db/one-shots/dedupe_display_names.sh, which
-- wraps it in BEGIN ... ROLLBACK (the default dry run) or BEGIN ... COMMIT (--apply) and prints the
-- report below. Idempotent: once the data conforms it changes nothing.
--
-- The report is the temp table dedupe_display_names_report(seq, reason, old_display, new_display).
-- It holds display names only; a blank or '@'-containing old name is shown as '(blank)' or
-- '(email-shaped)' so an email address never reaches the terminal.
--
-- Keep this file free of trailing '--' comments: the .sh collapses it to one line to pass it as a
-- single psql -c argument, which also lets it travel through 'bin/remote.sh psql'.

DO $dedupe$
DECLARE
    r        record;
    n        integer;
    v_cand   text;
    v_suffix text;
BEGIN
    IF COALESCE( current_setting( 'wikantik.dedupe_apply', true ), '' ) <> 'on' THEN
        RAISE EXCEPTION 'dedupe_display_names.sql changes data: run bin/db/one-shots/dedupe_display_names.sh, or SET wikantik.dedupe_apply = on inside a transaction you control';
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS dedupe_display_names_report (
        seq         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        reason      text NOT NULL,
        old_display text NOT NULL,
        new_display text NOT NULL
    );
    TRUNCATE pg_temp.dedupe_display_names_report RESTART IDENTITY;

    FOR r IN
        SELECT login_name, full_name
          FROM users
         WHERE btrim( COALESCE( full_name, '' ) ) = '' OR full_name LIKE '%@%'
         ORDER BY created NULLS LAST, login_name
    LOOP
        n := 1;
        LOOP
            v_cand := 'Member ' || n;
            EXIT WHEN NOT EXISTS ( SELECT 1 FROM users u
                                    WHERE u.login_name <> r.login_name
                                      AND ( lower( btrim( COALESCE( u.full_name, '' ) ) ) = lower( v_cand )
                                         OR lower( u.login_name ) = lower( v_cand ) ) )
                  AND NOT EXISTS ( SELECT 1 FROM groups g WHERE lower( g.name ) = lower( v_cand ) );
            n := n + 1;
        END LOOP;
        UPDATE users SET full_name = v_cand, modified = now() WHERE login_name = r.login_name;
        INSERT INTO dedupe_display_names_report ( reason, old_display, new_display )
        VALUES ( CASE WHEN btrim( COALESCE( r.full_name, '' ) ) = '' THEN 'blank' ELSE 'email-shaped' END,
                 CASE WHEN btrim( COALESCE( r.full_name, '' ) ) = '' THEN '(blank)' ELSE '(email-shaped)' END,
                 v_cand );
    END LOOP;

    FOR r IN
        SELECT login_name, base
          FROM ( SELECT login_name,
                        btrim( full_name ) AS base,
                        lower( btrim( full_name ) ) AS grp,
                        row_number() OVER ( PARTITION BY lower( btrim( full_name ) )
                                            ORDER BY created NULLS LAST, login_name ) AS rn
                   FROM users ) ranked
         WHERE rn > 1
         ORDER BY grp, rn
    LOOP
        n := 2;
        LOOP
            v_suffix := ' ' || n;
            v_cand := left( r.base, 64 - length( v_suffix ) ) || v_suffix;
            EXIT WHEN NOT EXISTS ( SELECT 1 FROM users u
                                    WHERE u.login_name <> r.login_name
                                      AND ( lower( btrim( COALESCE( u.full_name, '' ) ) ) = lower( v_cand )
                                         OR lower( u.login_name ) = lower( v_cand ) ) )
                  AND NOT EXISTS ( SELECT 1 FROM groups g WHERE lower( g.name ) = lower( v_cand ) );
            n := n + 1;
        END LOOP;
        UPDATE users SET full_name = v_cand, modified = now() WHERE login_name = r.login_name;
        INSERT INTO dedupe_display_names_report ( reason, old_display, new_display )
        VALUES ( 'duplicate', r.base, v_cand );
    END LOOP;
END
$dedupe$;
```

Create `bin/db/one-shots/dedupe_display_names.sh` (then `chmod +x bin/db/one-shots/dedupe_display_names.sh`):

```bash
#!/bin/bash
# One-shot data repair: make every display name (users.full_name) unique and presentable.
#
# Background: display names are now what other users see instead of login names, so they must be
# unique (ignoring case and surrounding whitespace), non-blank, and free of '@'. Accounts created
# before that rule can share a name ("Jake Fear" on several accounts) or carry an email address.
#
# What it does (the logic is in dedupe_display_names.sql, next to this script):
#   * In each group of accounts whose display names match, the earliest-created account keeps its
#     name and the others become "<name> 2", "<name> 3", ... (the lowest numbers not already taken).
#   * A blank or '@'-containing display name becomes "Member N" (the lowest free N).
#   * Prints each change as old -> new DISPLAY names only: never a login name or an email address
#     (a blank or email-shaped old name is shown as "(blank)" / "(email-shaped)").
#   * Leaves users.wiki_name alone.
#
# Dry run by default: the changes are made inside a transaction that is ROLLED BACK, so the report
# is exactly what --apply will do (including any constraint error). --apply commits.
# Idempotent: once the data conforms, a run reports zero changes.
#
# Usage:
#   bash bin/db/one-shots/dedupe_display_names.sh           # dry-run
#   bash bin/db/one-shots/dedupe_display_names.sh --apply   # commit
#
# Environment variables (defaults match bin/db/migrate.sh and the other one-shots):
#   PGHOST        localhost
#   PGPORT        5432
#   PGUSER        jspwiki
#   PGPASSWORD    (required - or read from tomcat ROOT.xml, as the other one-shots do)
#   DB_NAME       wikantik
#   PSQL_COMMAND  optional. When set, psql is run as "$PSQL_COMMAND <psql args>" and the PG* variables
#                 are ignored. For the production container run it through the remote wrapper:
#                   PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh
set -euo pipefail

APPLY=0
for arg in "$@"; do
    case "$arg" in
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0
            ;;
        --apply) APPLY=1 ;;
        *)
            echo "ERROR: unknown argument: $arg (use --apply or --help)" >&2
            exit 2
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/dedupe_display_names.sql"
if [ ! -f "$SQL_FILE" ]; then
    echo "ERROR: ${SQL_FILE} is missing." >&2
    exit 1
fi

# The SQL as one line: full-line comments dropped, newlines folded to spaces, so it can be passed as a
# single psql -c argument (which also survives bin/remote.sh's ssh quoting). A "--" left after the
# comment lines are removed would comment out the rest of the statement, so refuse rather than guess.
SQL_ONE_LINE="$(sed -e '/^[[:space:]]*--/d' "$SQL_FILE" | tr '\n' ' ')"
if [[ "$SQL_ONE_LINE" == *--* ]]; then
    echo "ERROR: ${SQL_FILE} has a trailing '--' comment; keep comments on their own lines." >&2
    exit 1
fi

PSQL_COMMAND="${PSQL_COMMAND:-}"
if [ -n "$PSQL_COMMAND" ]; then
    TARGET="via: ${PSQL_COMMAND}"
    run_psql() {
        # Word-splitting of PSQL_COMMAND is intended (it is a command plus its leading arguments).
        # shellcheck disable=SC2086
        $PSQL_COMMAND -X -q -v ON_ERROR_STOP=1 "$@"
    }
else
    PGHOST="${PGHOST:-localhost}"
    PGPORT="${PGPORT:-5432}"
    PGUSER="${PGUSER:-jspwiki}"
    DB_NAME="${DB_NAME:-wikantik}"

    if [ -z "${PGPASSWORD:-}" ]; then
        PGPASSWORD="$(grep -oE 'password="[^"]+"' \
            "$(dirname "$0")/../../../tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml" \
            2>/dev/null | head -1 | sed 's/password="//;s/"$//')" || true
    fi
    if [ -z "${PGPASSWORD:-}" ]; then
        echo "ERROR: PGPASSWORD is not set and could not be read from ROOT.xml." >&2
        exit 1
    fi
    export PGPASSWORD PGHOST PGPORT PGUSER
    TARGET="${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
    run_psql() {
        psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -X -q -v ON_ERROR_STOP=1 "$@"
    }
fi

if [ "$APPLY" -eq 1 ]; then
    END_TRANSACTION="COMMIT"
    MODE="APPLY (committed)"
else
    END_TRANSACTION="ROLLBACK"
    MODE="DRY-RUN (rolled back, no writes)"
fi

echo "=== dedupe_display_names.sh ==="
echo "Target: ${TARGET}"
echo "Mode: ${MODE}"
echo ""
echo "--- Display name changes (old -> new):"

# One psql session so the transaction, the SET LOCAL and the temp report table all share a connection.
# ON_ERROR_STOP ends psql before END_TRANSACTION on any error, and the server then rolls back.
run_psql \
    -c "BEGIN" \
    -c "SET LOCAL wikantik.dedupe_apply = 'on'" \
    -c "$SQL_ONE_LINE" \
    -c "SELECT reason, old_display AS \"old display name\", new_display AS \"new display name\" FROM dedupe_display_names_report ORDER BY seq" \
    -c "$END_TRANSACTION"

echo ""
if [ "$APPLY" -eq 1 ]; then
    echo "=== Done. Run again without --apply to confirm idempotency (it should report 0 rows). ==="
else
    echo "=== Dry-run only. Re-run with --apply to commit. ==="
fi
```

Document it in the operations guide (diff for `docs/admin/WikantikOperations.md`):

```diff
--- a/docs/admin/WikantikOperations.md
+++ b/docs/admin/WikantikOperations.md
@@ -536,7 +536,8 @@
 | `bin/deploy-marketing.sh [--dry-run] [--marketing-only] [--site-only] [--build-site]` | Publish the static marketing site (`marketing/`) and, if it has been built by `bin/site.sh`, the generated code-health site (under `/site`) to the nginx docroot on the `cloudflare` host. `bin/deploy-site.sh` is a thin shim for `--site-only`. Prompts for the sudo password interactively. | Requires manual confirmation for the privileged copy step; `--dry-run` changes nothing. |
 | `bin/db/audit-retention.sh [--status\|--dry-run]` | Enforce `audit_log` retention: pre-create upcoming monthly partitions; archive-then-drop partitions older than `AUDIT_RETENTION_MONTHS` (default 84 = 7 years). | `--dry-run` touches nothing. The drop phase requires `AUDIT_ARCHIVE_DIR` to be set. |
 | `bin/db/audit-retention-install-timer.sh` | Install and enable the `wikantik-audit-retention.timer` systemd timer (monthly). | Idempotent; prompts for sudo. |
-| `bin/db/one-shots/` | Environment-specific one-off data fixups. Each file is a standalone script or SQL file intended to be run once per environment (not migrations). Current scripts: `2026-05-20-backfill-chunk-embeddings.sh` (backfill `content_chunk_embeddings`), `2026-06-08-normalize-kg-node-types.sql` (normalize `kg_nodes.node_type` onto the 9-class entity vocabulary), `reconcile_page_canonical_ids.sh` (reconcile `page_canonical_ids`), `reset_judge_timeout_abstains.sh`, `reset_node_judge_verdicts.sh`, `backfill-agent-default-owner.sql`, `null_out_empty_user_attributes.sh` (null out `users.attributes` where it is the empty string). | Review individually before running; most are idempotent but data-modifying. |
+| `bin/db/one-shots/` | Environment-specific one-off data fixups. Each file is a standalone script or SQL file intended to be run once per environment (not migrations). Current scripts: `2026-05-20-backfill-chunk-embeddings.sh` (backfill `content_chunk_embeddings`), `2026-06-08-normalize-kg-node-types.sql` (normalize `kg_nodes.node_type` onto the 9-class entity vocabulary), `reconcile_page_canonical_ids.sh` (reconcile `page_canonical_ids`), `reset_judge_timeout_abstains.sh`, `reset_node_judge_verdicts.sh`, `backfill-agent-default-owner.sql`, `null_out_empty_user_attributes.sh` (null out `users.attributes` where it is the empty string), `dedupe_display_names.sh` (make display names unique and presentable; dry run by default, `--apply` to commit; `PSQL_COMMAND='bin/remote.sh psql --'` runs it against the production container). | Review individually before running; most are idempotent but data-modifying. |
+| `bin/tests/test-dedupe-display-names.sh` | Offline guard for `bin/db/one-shots/dedupe_display_names.sh`: a stub `psql` proves the dry run rolls back, `--apply` commits, the apply setting is switched on before the SQL, and the report selects no login or email column. | Read-only test harness. |
 | `bin/tests/test-audit-retention.sh` | Pure-filesystem unit tests for `audit-retention.sh` using stubbed `psql`/`pg_dump`/`pg_restore`. No real PostgreSQL required. | Read-only test harness. |
 | `bin/tests/test-backup.sh` | Tests for `backup.sh` and `nas-pull.sh` manifest + metrics emission. Stubbed `pg_dump`/`psql`/`rsync`/`curl` — no real PG or ssh. | Read-only test harness. |
 | `bin/tests/test-remote.sh` | Smoke tests for `bin/remote.sh` in `--dry-run` mode with a fake `remote.env`. No real ssh or docker. | Read-only test harness. |
```

- [ ] **Step 4: Run the tests, then rehearse on a throwaway database**

```bash
mvn -B -o test -pl wikantik-main -Dtest='DedupeDisplayNamesSqlTest' -Dsurefire.failIfNoSpecifiedTests=false -Dtests.requireDocker=true
bash bin/tests/test-dedupe-display-names.sh
shellcheck bin/db/one-shots/dedupe_display_names.sh bin/tests/test-dedupe-display-names.sh   # if installed; silent when clean
```

Expected: `Tests run: 8, Failures: 0, Errors: 0`; the guard ends `PASS: test-dedupe-display-names.sh (9 cases)`.

Then run the real script end to end against a disposable database (this is the check that proves the `.sh`
and the SQL work together under a real `psql`; nothing here touches your dev or production databases):

```bash
docker run -d --rm --name dd-rehearsal -e POSTGRES_PASSWORD=pw -p 55999:5432 pgvector/pgvector:pg18 && sleep 6
export PGPASSWORD=pw PGHOST=localhost PGPORT=55999 PGUSER=postgres DB_NAME=postgres
psql -X -q -d "$DB_NAME" -v ON_ERROR_STOP=1 -v app_user=postgres -f bin/db/migrations/V002__core_users_groups.sql 2>/dev/null
psql -X -q -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL'
UPDATE users SET created = '2026-01-01' WHERE login_name = 'admin';
INSERT INTO users (uid, email, full_name, login_name, wiki_name, created) VALUES
 ('u1','a@x','Jake Fear','jakefear@gmail.com','JakeFear','2026-03-01'),
 ('u2','b@x','Jake Fear','jake2@gmail.com','JakeFear2','2026-04-01'),
 ('u3','c@x',' jake fear ','jake3@gmail.com','JakeFear3','2026-05-01'),
 ('u4','d@x','Jake Fear','jake4@gmail.com','JakeFear4','2026-06-01'),
 ('u5','e@x','Jake Fear 2','taken2','JakeFear22','2026-07-01'),
 ('u6','f@x','','blank1','B1','2026-08-01'),
 ('u7','g@x',NULL,'blank2','B2','2026-08-02'),
 ('u8','h@x','someone@gmail.com','emailname','E1','2026-08-03'),
 ('u9','i@x','Member 1','m1','M1','2026-09-01'),
 ('u10','j@x','Alice','alice','Alice','2026-09-02');
SQL
bin/db/one-shots/dedupe_display_names.sh            # dry run
bin/db/one-shots/dedupe_display_names.sh --apply    # commit
bin/db/one-shots/dedupe_display_names.sh            # idempotent: 0 rows
docker stop dd-rehearsal
```

Expected dry-run and `--apply` report (identical), then `(0 rows)` on the third run:

```text
    reason    | old display name | new display name
--------------+------------------+------------------
 blank        | (blank)          | Member 2
 blank        | (blank)          | Member 3
 email-shaped | (email-shaped)   | Member 4
 duplicate    | Jake Fear        | Jake Fear 3
 duplicate    | jake fear        | jake fear 4
 duplicate    | Jake Fear        | Jake Fear 5
(6 rows)
```

(`Jake Fear 2` already belongs to `taken2`, `Member 1` to `m1`, so the numbers skip them; the earliest "Jake Fear" keeps its name.)

- [ ] **Step 5: Commit**

```bash
chmod +x bin/db/one-shots/dedupe_display_names.sh bin/tests/test-dedupe-display-names.sh
git add bin/db/one-shots/dedupe_display_names.sql bin/db/one-shots/dedupe_display_names.sh \
        bin/tests/test-dedupe-display-names.sh docs/admin/WikantikOperations.md \
        wikantik-main/src/test/java/com/wikantik/auth/user/DedupeDisplayNamesSqlTest.java
git commit -m "feat(db): one-shot script that makes display names unique (dry run by default)" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 22: Leak-probe integration test

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/DisplayNameLeakProbeIT.java`

**Interfaces:**
- Consumes (so schedule this task last among the code tasks): Task 1 (`POST /admin/users` accepts the display name; admin-created accounts still start with `password_must_change`), Tasks 5–6 (`PublicIdentity`, authorship stored as the login), Task 7 (page/list/history/recent-changes), Task 8 (search), Task 9 (for-agent, knowledge MCP, `/tools/*`), Task 10 (feeds, recent articles), Task 11 (`dct:creator`), Task 13 (`createdById`/`authorId`/`mentions`, `userId` on `GET /api/auth/user`), Task 14–15 (mention token, `GET /api/users/mentionable` → `{users:[{id, displayName}]}`, `GET /api/me/mentions`), Tasks 17–19 (access-rule directives). It also uses what already exists: `janne`/`myP@5sw0rd` (Admin) in this module, `POST /admin/users`, `PUT /api/auth/profile` (forced password change), `POST /api/self/apikeys` (non-admins may mint `tools` and `mcp_read`), `POST /admin/ontology/rebuild`, the `/knowledge-mcp` endpoint with `mcp.access.allowUnrestricted=true`, and the `/tools/*` filter that demands a key in this module.
- Produces: `DisplayNameLeakProbeIT` — 53 results (22 surfaces × anonymous and × a second signed-in user as `@TestFactory` dynamic tests, plus 9 named tests). A best-effort surface whose marker never appears is reported **skipped with its reason**, never as a silent pass.

What it proves, in the owner's terms: seed an account with login `leak-probe-<random>@example.test` and display name
`Leak Probe <random>` (email = login, the worst case under Google sign-in). That account edits three pages, starts a
comment thread that mentions a second user, is mentioned back by that user through the `@[Name](user:<uid>)` token,
and is named in the access rule of a public page. Then every public read surface is called as an anonymous visitor
and as the second signed-in non-admin, and the login (matched as `leak-probe-<random>`, case-insensitively, which
also catches the `%40`-encoded, JSON and XML spellings) must appear in none of them, while the display name must be
present wherever authorship is shown. The last tests prove the other direction: `/admin/users` still shows the login,
and editors still see the access rule. `theLeakDetectorFiresOnAnAdminResponse` is the built-in negative control: it
asserts the detector really does fire on a real response that contains the login, so the absence checks cannot all be vacuous.

Fixture facts worth knowing (each one was a trap while drafting):
- **`[{ALLOW edit X}]` alone hides the page from everyone else**, because `edit` implies `view` in `PagePermission` and `AclImpl.findPrincipals` therefore lists only X for `view`. The access-rule page therefore carries `[{ALLOW view All}]` too, which keeps it public while only the probe (and administrators) can edit it. Anonymous and the second user then have no `edit` permission, which is what makes the directive-stripping assertions meaningful.
- **Text an editor types is shown as written.** A frontmatter `author:` line is page text, so `?format=md` of a page that has one contains the login by design (the spec's "Out of scope"). The sweeps therefore use two pages with no such line; a third page (`ontoPage`) carries `author: <login>` and is used only for the ontology test, where `dct:creator` must come out as the display name.
- **Comments need a different page from the access-rule page**, because the ACL page denies `comment` to everyone but the probe.
- **Admin-created accounts must change their password before any other call** (`MustChangePasswordFilter`), so each account does the `PUT /api/auth/profile` password change first, as `SelfDeleteAccountIT` does.
- **The tools server and the knowledge MCP run as the second user** by minting that user's own `tools` and `mcp_read` keys (the only scopes a non-admin may self-mint). The MCP endpoint is `allowUnrestricted` in this module, so a bearer key may or may not change the identity it runs as; both clients must pass either way.

- [ ] **Step 1: Write the test**

Create `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/DisplayNameLeakProbeIT.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.its.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Leak probe for the display-name work: no surface a visitor or an ordinary signed-in user can reach
 * may return another user's <em>login name</em>. With Google sign-in a login name is the user's email
 * address, so a leak here is a leak of personal data.
 *
 * <p>The fixture is an account whose login looks like the worst case, {@code leak-probe-<random>@example.test},
 * with a different display name, {@code Leak Probe <random>}. That account edits two pages, starts a
 * comment thread that mentions a second user, is mentioned back by that user, and is named in the access
 * rule of a public page. Then every public read surface is called as an anonymous visitor and as the second
 * signed-in non-admin, and the login must appear in none of the responses. Where a surface shows authorship
 * the display name must be there, so a surface that returns nothing cannot pass vacuously. A final test
 * proves the other direction: administrators, and editors of the page, still see the login.</p>
 *
 * <p>Pages: {@code editedPage} (two versions by the probe, no frontmatter author), {@code aclPage}
 * (version 1 plain, version 2 adds {@code [{ALLOW view All}]} and {@code [{ALLOW edit <login>}]}) and
 * {@code ontoPage} (frontmatter {@code author:} set to the login, to exercise the ontology's
 * {@code dct:creator}). Free text typed into a page body or frontmatter is shown as written (see the
 * design's "Out of scope"), so the sweeps use only the first two pages.</p>
 *
 * <p>Settling: search, feeds, recent articles and the ontology are filled asynchronously. A surface that
 * must be present is polled until its marker appears and then fails if it never does; a best-effort
 * surface is reported as skipped (never as a silent pass) when its marker never appears.</p>
 */
public class DisplayNameLeakProbeIT {

    private static final Gson GSON = new Gson();
    private static final Duration SETTLE = Duration.ofSeconds( 45 );

    private static final String ADMIN_USER = "janne";
    private static final String ADMIN_PASSWORD = "myP@5sw0rd";
    private static final String PASSWORD_INITIAL = "ItLeakProbe1!";
    private static final String PASSWORD_CHANGED = "ItLeakProbe2!";

    private static String baseUrl;

    /** Random per run, so reruns against a long-lived database never collide. */
    private static String rand;
    private static String probeLogin;
    private static String probeDisplay;
    private static String viewerLogin;
    private static String viewerDisplay;
    /** Lower-cased login local part: matches the raw login, its %40-encoded form and its JSON/XML forms. */
    private static String needle;
    private static String marker;
    private static String editedPage;
    private static String aclPage;
    private static String ontoPage;
    private static String editedId;
    private static String aclId;
    private static String ontoId;
    private static String probeUid;
    private static String viewerUid;
    private static String viewerToolsKey;
    private static String viewerMcpKey;

    private static Client admin;
    private static Client probe;
    private static Client viewer;

    // ------------------------------------------------------------------ seeding

    @BeforeAll
    static void seed() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        rand = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 10 );
        probeLogin = "leak-probe-" + rand + "@example.test";
        probeDisplay = "Leak Probe " + rand;
        viewerLogin = "leak-viewer-" + rand + "@example.test";
        viewerDisplay = "Leak Viewer " + rand;
        needle = ( "leak-probe-" + rand ).toLowerCase( Locale.ROOT );
        marker = "lpword" + rand;
        editedPage = "LeakProbeEdited" + rand;
        aclPage = "LeakProbeAcl" + rand;
        ontoPage = "LeakProbeOnto" + rand;

        admin = new Client().login( ADMIN_USER, ADMIN_PASSWORD );
        createAccount( probeLogin, probeDisplay );
        createAccount( viewerLogin, viewerDisplay );
        probe = signInAndChangePassword( probeLogin );
        viewer = signInAndChangePassword( viewerLogin );

        // editedPage: two versions by the probe. The quick brown fox is the anchor for the comment thread.
        savePage( probe, editedPage, "# Leak probe\n\nThe " + marker + " marker word. The quick brown fox jumps over the lazy dog.\n",
                "first version" );
        savePage( probe, editedPage, "# Leak probe\n\nThe " + marker + " marker word. The quick brown fox jumps over the lazy dog.\n\nSecond revision.\n",
                "second version" );

        // aclPage: version 2 names the probe in an access rule on a page everybody can still read.
        final String aclBody = "The " + marker + " access-rule fixture page.\n";
        savePage( probe, aclPage, aclBody, "first version" );
        savePage( probe, aclPage, "[{ALLOW view All}]\n[{ALLOW edit " + probeLogin + "}]\n\n" + aclBody, "adds an access rule" );

        // ontoPage: the editor typed the login as the frontmatter author.
        savePage( probe, ontoPage, "---\ntype: article\nauthor: " + probeLogin + "\n---\nOntology fixture " + marker + ".\n",
                "frontmatter author" );

        editedId = awaitCanonicalId( editedPage );
        aclId = awaitCanonicalId( aclPage );
        ontoId = awaitCanonicalId( ontoPage );

        probeUid = mentionableId( viewer, probeDisplay );
        viewerUid = mentionableId( probe, viewerDisplay );

        // The probe starts a thread mentioning the viewer; the viewer replies, mentioning the probe.
        final HttpResponse< String > thread = probe.post( "/api/comment-threads?page=" + editedPage, GSON.toJson( Map.of(
                "exact", "quick brown fox", "prefix", "", "suffix", "",
                "text", "Please review @[" + viewerDisplay + "](user:" + viewerUid + ")" ) ) );
        assertEquals( 200, thread.statusCode(), "probe starts a thread: " + thread.body() );
        final String threadId = JsonParser.parseString( thread.body() ).getAsJsonObject().get( "id" ).getAsString();
        final HttpResponse< String > reply = viewer.post( "/api/comment-threads/" + threadId + "/comments", GSON.toJson( Map.of(
                "text", "Thanks @[" + probeDisplay + "](user:" + probeUid + ")" ) ) );
        assertEquals( 200, reply.statusCode(), "viewer replies: " + reply.body() );

        viewerToolsKey = mintKey( viewer, "tools" );
        viewerMcpKey = mintKey( viewer, "mcp_read" );
    }

    @AfterAll
    static void cleanUp() {
        // Best effort: a failure here must not mask a test failure, but it is never swallowed silently.
        if ( admin == null ) {
            return;
        }
        for ( final String page : new String[] { editedPage, aclPage, ontoPage } ) {
            quietly( "delete page " + page, () -> admin.delete( "/api/pages/" + page ) );
        }
        for ( final String login : new String[] { probeLogin, viewerLogin } ) {
            quietly( "delete account " + login.substring( 0, login.indexOf( '-', 5 ) ),
                    () -> admin.delete( "/admin/users/" + URLEncoder.encode( login, StandardCharsets.UTF_8 ) ) );
        }
    }

    private interface Call {
        HttpResponse< String > run() throws Exception;
    }

    private static void quietly( final String what, final Call call ) {
        try {
            final HttpResponse< String > resp = call.run();
            if ( resp.statusCode() >= 400 ) {
                System.err.println( "DisplayNameLeakProbeIT cleanup: " + what + " returned " + resp.statusCode() );
            }
        } catch ( final Exception e ) {
            System.err.println( "DisplayNameLeakProbeIT cleanup: " + what + " failed: " + e );
        }
    }

    private static void createAccount( final String login, final String display ) throws Exception {
        final HttpResponse< String > resp = admin.post( "/admin/users", GSON.toJson( Map.of(
                "loginName", login, "fullName", display, "email", login, "password", PASSWORD_INITIAL ) ) );
        assertEquals( 201, resp.statusCode(), "create account (display name '" + display + "'): " + resp.body() );
    }

    /** Admin-created accounts must change their password before any other call; do it as the real flow does. */
    private static Client signInAndChangePassword( final String login ) throws Exception {
        final Client client = new Client().login( login, PASSWORD_INITIAL );
        final HttpResponse< String > changed = client.put( "/api/auth/profile", GSON.toJson( Map.of(
                "currentPassword", PASSWORD_INITIAL, "newPassword", PASSWORD_CHANGED ) ) );
        assertEquals( 200, changed.statusCode(), "forced password change: " + changed.body() );
        return client;
    }

    private static void savePage( final Client as, final String page, final String content, final String note )
            throws Exception {
        final HttpResponse< String > resp = as.put( "/api/pages/" + page,
                GSON.toJson( Map.of( "content", content, "changeNote", note ) ) );
        assertEquals( 200, resp.statusCode(), "save " + page + " (" + note + "): " + resp.body() );
    }

    /** Looks a user up the way the mention picker does, as {@code as}, and returns the opaque id. */
    private static String mentionableId( final Client as, final String displayName ) throws Exception {
        final HttpResponse< String > resp = as.get( "/api/users/mentionable?q=" + enc( displayName ), "application/json" );
        assertEquals( 200, resp.statusCode(), "mentionable lookup of '" + displayName + "': " + resp.body() );
        final JsonArray users = JsonParser.parseString( resp.body() ).getAsJsonObject().getAsJsonArray( "users" );
        for ( final JsonElement e : users ) {
            final JsonObject u = e.getAsJsonObject();
            if ( displayName.equals( u.get( "displayName" ).getAsString() ) ) {
                assertFalse( u.has( "loginName" ) || u.has( "fullName" ),
                        "a mentionable entry carries only {id, displayName}: " + u );
                return u.get( "id" ).getAsString();
            }
        }
        throw new AssertionError( "'" + displayName + "' not offered by the mention picker: " + resp.body() );
    }

    private static String mintKey( final Client as, final String scope ) throws Exception {
        final HttpResponse< String > resp = as.post( "/api/self/apikeys",
                GSON.toJson( Map.of( "label", "it-display-name-probe", "scope", scope ) ) );
        assertEquals( 201, resp.statusCode(), "mint a " + scope + " key: " + resp.body() );
        return JsonParser.parseString( resp.body() ).getAsJsonObject().get( "token" ).getAsString();
    }

    /** Waits for the structural index to know {@code slug} (it fills asynchronously) and returns its canonical id. */
    private static String awaitCanonicalId( final String slug ) throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > resp = admin.get( "/api/structure/sitemap", "application/json" );
            if ( resp.statusCode() == 200 ) {
                final JsonObject root = JsonParser.parseString( resp.body() ).getAsJsonObject();
                final JsonObject data = root.has( "data" ) ? root.getAsJsonObject( "data" ) : root;
                for ( final JsonElement e : data.getAsJsonArray( "pages" ) ) {
                    final JsonObject page = e.getAsJsonObject();
                    if ( slug.equals( page.get( "slug" ).getAsString() ) ) {
                        return page.get( "id" ).getAsString();
                    }
                }
            }
            Thread.sleep( 500 );
        }
        throw new AssertionError( "page '" + slug + "' never reached the structural index" );
    }

    private static String enc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 );
    }

    // ------------------------------------------------------------------ HTTP plumbing

    /** One cookie jar = one browser session. The web.xml marks JSESSIONID Secure, so cookies are replayed as https. */
    private static final class Client {
        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();

        Client login( final String user, final String password ) throws Exception {
            final HttpResponse< String > resp = post( "/api/auth/login",
                    GSON.toJson( Map.of( "username", user, "password", password ) ) );
            assertEquals( 200, resp.statusCode(), "sign in: " + resp.body() );
            return this;
        }

        HttpResponse< String > get( final String path, final String accept ) throws Exception {
            return send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).header( "Accept", accept ).GET() );
        }

        HttpResponse< String > post( final String path, final String json ) throws Exception {
            return send( json( path ).POST( HttpRequest.BodyPublishers.ofString( json ) ) );
        }

        HttpResponse< String > put( final String path, final String json ) throws Exception {
            return send( json( path ).PUT( HttpRequest.BodyPublishers.ofString( json ) ) );
        }

        HttpResponse< String > delete( final String path ) throws Exception {
            return send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Accept", "application/json" ).DELETE() );
        }

        private HttpRequest.Builder json( final String path ) {
            return HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Content-Type", "application/json" ).header( "Accept", "application/json" );
        }

        private HttpResponse< String > send( final HttpRequest.Builder request ) throws Exception {
            return http.send( request.build(), HttpResponse.BodyHandlers.ofString() );
        }
    }

    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > h )
                    throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }

    // ------------------------------------------------------------------ assertions

    private static void assertNoLogin( final String where, final String body ) {
        final String haystack = body.toLowerCase( Locale.ROOT );
        final int at = haystack.indexOf( needle );
        if ( at >= 0 ) {
            final int from = Math.max( 0, at - 60 );
            final int to = Math.min( body.length(), at + needle.length() + 60 );
            fail( "the login name leaked through " + where + ": ..." + body.substring( from, to ).replace( '\n', ' ' ) + "..." );
        }
    }

    /**
     * Polls {@code path} until it answers 200 with {@code marker} in the body. Returns the last response seen,
     * which the caller inspects: it may be a non-200 or lack the marker when the surface never settled.
     */
    private static HttpResponse< String > awaitMarker( final Client who, final String path, final String accept,
            final String wanted ) throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        HttpResponse< String > last = who.get( path, accept );
        while ( !( last.statusCode() == 200 && last.body().contains( wanted ) ) && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 500 );
            last = who.get( path, accept );
        }
        return last;
    }

    // ------------------------------------------------------------------ the HTTP sweep

    /**
     * @param needsLogin   the surface is for signed-in users; an anonymous caller must get 401
     * @param marker       text that proves the response is the real thing and not an empty list
     * @param showsAuthor  the surface shows authorship, so the display name must be in the body
     * @param mustSettle   fail (rather than skip) when the marker never appears
     */
    private record Surface( String label, String path, String accept, boolean needsLogin, String marker,
                            boolean showsAuthor, boolean mustSettle ) {
    }

    private static List< Surface > httpSurfaces() {
        final String json = "application/json";
        return List.of(
            new Surface( "GET /api/pages/{edited}", "/api/pages/" + editedPage, json, false, editedPage, true, true ),
            new Surface( "GET /api/pages/{edited}?version=1", "/api/pages/" + editedPage + "?version=1", json, false, editedPage, true, true ),
            new Surface( "GET /api/pages?names=", "/api/pages?names=" + editedPage + "," + aclPage, json, false, editedPage, true, true ),
            new Surface( "GET /api/history/{edited}", "/api/history/" + editedPage, json, false, probeDisplay, true, true ),
            new Surface( "GET /api/recent-changes", "/api/recent-changes", json, false, editedPage, true, true ),
            new Surface( "GET /api/search", "/api/search?q=" + marker, json, false, editedPage, true, false ),
            new Surface( "GET /api/pages/for-agent/{id}", "/api/pages/for-agent/" + editedId, json, false, editedPage, true, true ),
            new Surface( "GET /api/comment-threads", "/api/comment-threads?page=" + editedPage + "&status=all", json, false, editedPage, true, true ),
            new Surface( "GET /api/users/mentionable", "/api/users/mentionable?q=" + enc( "Leak Probe" ), json, true, probeDisplay, true, true ),
            new Surface( "GET /api/me/mentions", "/api/me/mentions?status=all", json, true, probeDisplay, true, true ),
            new Surface( "GET /wiki/{edited}?format=md", "/wiki/" + editedPage + "?format=md", "text/markdown", false, marker, false, true ),
            new Surface( "GET /wiki/{edited}?format=json", "/wiki/" + editedPage + "?format=json", json, false, marker, false, true ),
            new Surface( "GET /feed.xml", "/feed.xml", "application/atom+xml", false, editedPage, true, false ),
            new Surface( "GET /api/recent-articles", "/api/recent-articles", json, false, editedPage, true, false ),
            // The page that names the probe in its access rule: the rule must be invisible to non-editors.
            new Surface( "GET /api/pages/{acl}", "/api/pages/" + aclPage, json, false, aclPage, true, true ),
            new Surface( "GET /api/pages/{acl}?version=2", "/api/pages/" + aclPage + "?version=2", json, false, aclPage, true, true ),
            new Surface( "GET /wiki/{acl}?format=md", "/wiki/" + aclPage + "?format=md", "text/markdown", false, marker, false, true ),
            new Surface( "GET /wiki/{acl}?format=json", "/wiki/" + aclPage + "?format=json", json, false, marker, false, true ),
            new Surface( "GET /api/history/{acl}", "/api/history/" + aclPage, json, false, probeDisplay, true, true ),
            new Surface( "GET /api/diff/{acl}?from=1&to=2", "/api/diff/" + aclPage + "?from=1&to=2", json, false, aclPage, false, true ),
            new Surface( "GET /api/pages/for-agent/{acl-id}", "/api/pages/for-agent/" + aclId, json, false, aclPage, false, true ),
            new Surface( "GET /api/search (access-rule page)", "/api/search?q=" + marker, json, false, aclPage, false, false ) );
    }

    @TestFactory
    Stream< DynamicTest > anonymousVisitorNeverSeesTheLogin() {
        final Client anonymous = new Client();
        return httpSurfaces().stream().map( s -> dynamicTest( "anonymous: " + s.label(), () -> probe( anonymous, false, s ) ) );
    }

    @TestFactory
    Stream< DynamicTest > secondSignedInUserNeverSeesTheLogin() {
        return httpSurfaces().stream().map( s -> dynamicTest( "signed-in non-admin: " + s.label(), () -> probe( viewer, true, s ) ) );
    }

    private void probe( final Client who, final boolean signedIn, final Surface s ) throws Exception {
        if ( s.needsLogin() && !signedIn ) {
            final HttpResponse< String > refused = who.get( s.path(), s.accept() );
            assertEquals( 401, refused.statusCode(), s.label() + " must demand a sign-in: " + refused.body() );
            assertNoLogin( s.label(), refused.body() );
            return;
        }
        final HttpResponse< String > resp = awaitMarker( who, s.path(), s.accept(), s.marker() );
        final boolean settled = resp.statusCode() == 200 && resp.body().contains( s.marker() );
        if ( !settled && !s.mustSettle() ) {
            // Whatever did come back still must not leak; then report the gap as a skip, not a pass.
            assertNoLogin( s.label(), resp.body() );
            assumeTrue( false, s.label() + " never showed '" + s.marker() + "' within " + SETTLE.toSeconds()
                    + "s (status " + resp.statusCode() + "); the absence check above was vacuous" );
        }
        assertEquals( 200, resp.statusCode(), s.label() + ": " + resp.body() );
        assertTrue( resp.body().contains( s.marker() ), s.label() + " should contain '" + s.marker() + "': " + resp.body() );
        assertNoLogin( s.label(), resp.body() );
        if ( s.showsAuthor() ) {
            assertTrue( resp.body().contains( probeDisplay ),
                    s.label() + " shows authorship, so it must show the display name '" + probeDisplay + "': " + resp.body() );
        }
    }

    // ------------------------------------------------------------------ comment threads and mentions in detail

    @Test
    void commentThreadsResolveAuthorsAndMentionsToDisplayNames() throws Exception {
        final HttpResponse< String > resp = new Client().get(
                "/api/comment-threads?page=" + editedPage + "&status=all", "application/json" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject root = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonObject thread = root.getAsJsonArray( "threads" ).get( 0 ).getAsJsonObject();

        assertEquals( probeDisplay, thread.get( "createdBy" ).getAsString() );
        assertEquals( probeUid, thread.get( "createdById" ).getAsString() );
        final JsonArray comments = thread.getAsJsonArray( "comments" );
        assertEquals( probeDisplay, comments.get( 0 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( viewerDisplay, comments.get( 1 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( viewerUid, comments.get( 1 ).getAsJsonObject().get( "authorId" ).getAsString() );

        final JsonObject mentions = root.getAsJsonObject( "mentions" );
        assertEquals( probeDisplay, mentions.get( probeUid ).getAsString(), "the mention map resolves the probe" );
        assertEquals( viewerDisplay, mentions.get( viewerUid ).getAsString(), "the mention map resolves the viewer" );
        assertNoLogin( "comment threads", resp.body() );
    }

    @Test
    void theCallersOwnUserIdMatchesTheIdThePickerOffers() throws Exception {
        final HttpResponse< String > me = probe.get( "/api/auth/user", "application/json" );
        assertEquals( 200, me.statusCode(), me.body() );
        assertEquals( probeUid, JsonParser.parseString( me.body() ).getAsJsonObject().get( "userId" ).getAsString(),
                "clients compare comments[].authorId with /api/auth/user userId to find their own comments" );

        final JsonObject anonymous = JsonParser.parseString( new Client().get( "/api/auth/user", "application/json" ).body() )
                .getAsJsonObject();
        assertTrue( !anonymous.has( "userId" ) || anonymous.get( "userId" ).isJsonNull(),
                "an anonymous caller has no userId: " + anonymous );
    }

    // ------------------------------------------------------------------ knowledge MCP and /tools

    private static McpSyncClient mcpClient( final String bearerOrNull ) {
        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport.Builder transport = HttpClientStreamableHttpTransport.builder( prefix )
                .endpoint( "knowledge-mcp" )
                .connectTimeout( Duration.ofSeconds( 15 ) );
        if ( bearerOrNull != null ) {
            transport.httpRequestCustomizer( ( rb, method, uri, body, context ) ->
                    rb.header( "Authorization", "Bearer " + bearerOrNull ) );
        }
        final McpSyncClient client = McpClient.sync( transport.build() )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-display-name-probe", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        client.initialize();
        return client;
    }

    private static String callTool( final McpSyncClient mcp, final String tool, final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = mcp.callTool( new McpSchema.CallToolRequest( tool, args ) );
        final String text = result.content() == null || result.content().isEmpty()
                ? "" : ( result.content().get( 0 ) instanceof McpSchema.TextContent tc ? tc.text() : "" );
        assertFalse( Boolean.TRUE.equals( result.isError() ), tool + " returned an error: " + text );
        return text;
    }

    /** The IT runs /knowledge-mcp with mcp.access.allowUnrestricted=true; a bearer key adds the viewer's identity where honoured. */
    private void assertMcpHidesTheLogin( final McpSyncClient mcp, final String who ) {
        final Set< String > tools = mcp.listTools().tools().stream().map( McpSchema.Tool::name ).collect( Collectors.toSet() );
        for ( final String required : List.of( "get_page", "read_pages", "get_page_for_agent" ) ) {
            assertTrue( tools.contains( required ), "knowledge MCP lacks " + required + ": " + tools );
        }

        final String page = callTool( mcp, "get_page", Map.of( "slug", editedPage ) );
        assertTrue( page.contains( editedPage ), who + " get_page: " + page );
        assertTrue( page.contains( probeDisplay ), who + " get_page shows the author's display name: " + page );
        assertNoLogin( who + " get_page", page );

        final String batch = callTool( mcp, "read_pages", Map.of( "slugs", List.of( editedPage, aclPage ) ) );
        assertTrue( batch.contains( aclPage ), who + " read_pages: " + batch );
        assertNoLogin( who + " read_pages (access-rule page included)", batch );

        final String aclOnly = callTool( mcp, "get_page", Map.of( "slug", aclPage ) );
        assertTrue( aclOnly.contains( aclPage ), who + " get_page(acl): " + aclOnly );
        assertNoLogin( who + " get_page of the access-rule page", aclOnly );

        final String projection = callTool( mcp, "get_page_for_agent", Map.of( "canonical_id", editedId ) );
        assertTrue( projection.contains( probeDisplay ), who + " get_page_for_agent shows recent_changes authors by display name: " + projection );
        assertNoLogin( who + " get_page_for_agent", projection );
        assertNoLogin( who + " get_page_for_agent (access-rule page)", callTool( mcp, "get_page_for_agent", Map.of( "canonical_id", aclId ) ) );
    }

    @Test
    void knowledgeMcpHidesTheLoginFromAnUnauthenticatedClient() {
        final McpSyncClient mcp = mcpClient( null );
        try {
            assertMcpHidesTheLogin( mcp, "anonymous MCP" );
        } finally {
            mcp.close();
        }
    }

    @Test
    void knowledgeMcpHidesTheLoginFromTheSecondUsersKey() {
        final McpSyncClient mcp = mcpClient( viewerMcpKey );
        try {
            assertMcpHidesTheLogin( mcp, "viewer MCP" );
        } finally {
            mcp.close();
        }
    }

    @Test
    void retrieveContextHidesTheLoginWhenRetrievalIsWired() throws Exception {
        final McpSyncClient mcp = mcpClient( null );
        try {
            final Set< String > tools = mcp.listTools().tools().stream().map( McpSchema.Tool::name ).collect( Collectors.toSet() );
            assumeTrue( tools.contains( "retrieve_context" ),
                    "retrieve_context is not registered in this module (hybrid retrieval is not wired here); "
                            + "cover it in wikantik-it-test-dense instead" );
            final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
            String text = callTool( mcp, "retrieve_context", Map.of( "query", marker ) );
            while ( !text.contains( aclPage ) && System.currentTimeMillis() < deadline ) {
                Thread.sleep( 1000 );
                text = callTool( mcp, "retrieve_context", Map.of( "query", marker ) );
            }
            assumeTrue( text.contains( aclPage ),
                    "retrieve_context never returned the fixture pages (chunks not indexed in this module); "
                            + "the absence check would be vacuous" );
            assertNoLogin( "retrieve_context", text );
        } finally {
            mcp.close();
        }
    }

    @Test
    void toolsServerHidesTheLoginAndStaysClosedToAnonymousCallers() throws Exception {
        final HttpClient plain = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
        final HttpResponse< String > anonymous = plain.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/tools/page/" + editedPage ) ).GET().build(), HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, anonymous.statusCode(), "the tools server demands a key in this module: " + anonymous.body() );
        assertNoLogin( "/tools anonymous refusal", anonymous.body() );

        final HttpResponse< String > page = bearer( plain, "GET", "/tools/page/" + editedPage, null );
        assertEquals( 200, page.statusCode(), page.body() );
        assertTrue( page.body().contains( editedPage ) && page.body().contains( probeDisplay ),
                "get_page shows the page and its author's display name: " + page.body() );
        assertNoLogin( "/tools/page/{edited}", page.body() );

        final HttpResponse< String > aclPageBody = bearer( plain, "GET", "/tools/page/" + aclPage, null );
        assertEquals( 200, aclPageBody.statusCode(), aclPageBody.body() );
        assertTrue( aclPageBody.body().contains( aclPage ), aclPageBody.body() );
        assertNoLogin( "/tools/page/{acl}", aclPageBody.body() );

        HttpResponse< String > search = bearer( plain, "POST", "/tools/search_wiki", GSON.toJson( Map.of( "query", marker ) ) );
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        while ( !( search.statusCode() == 200 && search.body().contains( editedPage ) ) && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 500 );
            search = bearer( plain, "POST", "/tools/search_wiki", GSON.toJson( Map.of( "query", marker ) ) );
        }
        assertEquals( 200, search.statusCode(), search.body() );
        assertTrue( search.body().contains( editedPage ), "search_wiki finds the fixture page: " + search.body() );
        assertNoLogin( "/tools/search_wiki", search.body() );
    }

    private static HttpResponse< String > bearer( final HttpClient http, final String method, final String path, final String json )
            throws Exception {
        final HttpRequest.Builder rb = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" ).header( "Authorization", "Bearer " + viewerToolsKey );
        if ( json == null ) {
            rb.GET();
        } else {
            rb.header( "Content-Type", "application/json" ).method( method, HttpRequest.BodyPublishers.ofString( json ) );
        }
        return http.send( rb.build(), HttpResponse.BodyHandlers.ofString() );
    }

    // ------------------------------------------------------------------ ontology

    @Test
    void ontologyEndpointsExposeTheDisplayNameNotTheLogin() throws Exception {
        rebuildOntologyAndAwaitIdle();
        final Client anonymous = new Client();

        final String query = enc( "SELECT ?s ?c WHERE { ?s <http://purl.org/dc/terms/creator> ?c }" );
        final HttpResponse< String > select = awaitMarker( anonymous, "/sparql?query=" + query, "application/sparql-results+json", probeDisplay );
        assertEquals( 200, select.statusCode(), select.body() );
        assertTrue( select.body().contains( probeDisplay ),
                "dct:creator of the fixture page is the display name (the frontmatter author was the login): " + select.body() );
        assertNoLogin( "/sparql dct:creator", select.body() );

        final HttpResponse< String > dump = awaitMarker( anonymous, "/export/graph.nt", "application/n-triples", probeDisplay );
        assertEquals( 200, dump.statusCode(), "dump status" );
        assertTrue( dump.body().contains( probeDisplay ), "the dump carries the creator's display name" );
        assertNoLogin( "/export/graph.nt", dump.body() );

        final HttpResponse< String > resource = anonymous.get( "/id/page/" + ontoId, "text/turtle" );
        assumeTrue( resource.statusCode() == 200 && resource.body().contains( probeDisplay ),
                "/id/page/{id} did not serve the fixture page (status " + resource.statusCode() + ")" );
        assertNoLogin( "/id/page/{id}", resource.body() );
    }

    private void rebuildOntologyAndAwaitIdle() throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis() + 15_000;
        HttpResponse< String > trigger = null;
        while ( System.currentTimeMillis() < deadline ) {
            awaitOntologyIdle();
            trigger = admin.post( "/admin/ontology/rebuild", "{}" );
            if ( trigger.statusCode() == 202 ) {
                break;
            }
            // 409: another IT's rebuild is still running; wait for it and trigger our own so it sees our pages.
            assertEquals( 409, trigger.statusCode(), "ontology rebuild trigger: " + trigger.body() );
            Thread.sleep( 250 );
        }
        assertTrue( trigger != null && trigger.statusCode() == 202, "no ontology rebuild was accepted in time" );
        awaitOntologyIdle();
    }

    private void awaitOntologyIdle() throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        String state = "(never read)";
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > status = admin.get( "/admin/ontology/status", "application/json" );
            state = JsonParser.parseString( status.body() ).getAsJsonObject().get( "state" ).getAsString();
            if ( "IDLE".equals( state ) ) {
                return;
            }
            Thread.sleep( 250 );
        }
        fail( "ontology rebuild did not reach IDLE; last state=" + state );
    }

    // ------------------------------------------------------------------ the other direction

    @Test
    void theLeakDetectorFiresOnAnAdminResponse() throws Exception {
        final HttpResponse< String > adminView = admin.get( "/admin/users/" + enc( probeLogin ), "application/json" );
        assertEquals( 200, adminView.statusCode(), adminView.body() );
        assertThrows( AssertionError.class, () -> assertNoLogin( "admin control", adminView.body() ),
                "the needle must match a real response that contains the login, or every absence check here is vacuous" );
    }

    @Test
    void administratorsAndEditorsStillSeeTheLogin() throws Exception {
        final HttpResponse< String > one = admin.get( "/admin/users/" + enc( probeLogin ), "application/json" );
        assertEquals( 200, one.statusCode(), one.body() );
        assertTrue( one.body().contains( probeLogin ), "/admin/users/{login} shows the login: " + one.body() );
        assertTrue( one.body().contains( probeDisplay ), "and the display name: " + one.body() );

        final HttpResponse< String > all = admin.get( "/admin/users", "application/json" );
        assertEquals( 200, all.statusCode(), all.body() );
        assertTrue( all.body().contains( probeLogin ), "/admin/users lists the login" );

        // The access rule is hidden from people who cannot edit the page, and plain to those who can.
        final String directive = "[{ALLOW edit " + probeLogin + "}]";
        for ( final Client editor : List.of( admin, probe ) ) {
            final HttpResponse< String > raw = editor.get( "/wiki/" + aclPage + "?format=md", "text/markdown" );
            assertEquals( 200, raw.statusCode(), raw.body() );
            assertTrue( raw.body().contains( directive ), "an editor sees the access rule as written: " + raw.body() );
        }
        final HttpResponse< String > nonEditor = viewer.get( "/wiki/" + aclPage + "?format=md", "text/markdown" );
        assertEquals( 200, nonEditor.statusCode(), nonEditor.body() );
        assertTrue( nonEditor.body().contains( marker ), "the page itself is still readable: " + nonEditor.body() );
        assertFalse( nonEditor.body().contains( "[{ALLOW" ), "no access rule text for a non-editor: " + nonEditor.body() );
    }
}
```

- [ ] **Step 2: Compile it, then run it**

```bash
mvn -B -o test-compile -pl wikantik-it-tests/wikantik-it-test-rest          # compiles the IT sources only
bin/agent-build.sh start war -- mvn -B -o clean install -DskipTests -T 1C     # builds and installs the WAR the IT module overlays
bin/agent-build.sh wait war 540                                               # repeat until it exits 0
bin/agent-build.sh start leak-it -- mvn -B -o clean install -Pintegration-tests \
    -pl wikantik-it-tests/wikantik-it-test-rest -Dit.test=DisplayNameLeakProbeIT -Dsurefire.failIfNoSpecifiedTests=false
bin/agent-build.sh wait leak-it 540
```

(`clean` matters: without it the IT module's WAR overlay can be stale and every request 404s. If the install step
fails on missing `2.4.58-SNAPSHOT` artifacts, run it without `-o` once.)

Expected with Tasks 1–19 complete: `BUILD SUCCESS`, failsafe `Tests run: 53, Failures: 0, Errors: 0`. Look at the skips:

```bash
grep -c '<skipped' wikantik-it-tests/wikantik-it-test-rest/target/failsafe-reports/TEST-com.wikantik.its.rest.DisplayNameLeakProbeIT.xml
grep -B2 '<skipped' wikantik-it-tests/wikantik-it-test-rest/target/failsafe-reports/TEST-com.wikantik.its.rest.DisplayNameLeakProbeIT.xml | grep -E 'testcase|skipped' | cut -c1-220
```

Only these may be skipped, each printing its reason: the best-effort surfaces (`/feed.xml`, `/api/recent-articles`, the
two `/api/search` rows), `retrieve_context` when hybrid retrieval is not wired in this module, and `/id/page/{id}`. A skip of any other
surface means its marker never appeared: that is a bug in the surface or the fixture, not an acceptable skip.

- [ ] **Step 3: Fix whatever it finds, in the task that owns the surface**

A failure names the surface and shows 120 characters around the leaked login. Map it back:

| Failing surface | Fix in |
|---|---|
| account creation, profile password change, `login` | Task 1 / Task 4 |
| `GET /api/pages/{p}`, `/api/pages`, `/api/history`, `/api/recent-changes` (`author`) | Task 7 |
| `GET /api/search`, the Lucene `author` field | Task 8 |
| `GET /api/pages/for-agent/{id}`, knowledge MCP `get_page`/`read_pages`/`get_page_for_agent`/`retrieve_context`, `/tools/*` | Task 9 (+ Task 18 for directives in their text) |
| `/feed.xml`, `/api/recent-articles` | Task 10 |
| `/sparql`, `/export/graph.nt`, `/id/page/{id}` (`dct:creator`) | Task 11 |
| `GET /api/comment-threads`, `userId` on `GET /api/auth/user` | Task 13 |
| mention token resolution, `GET /api/me/mentions` | Task 14 / Task 15 |
| `GET /api/users/mentionable` | Task 15 |
| `[{ALLOW …}]` visible in `/api/pages/{p}`, `?version=`, `?format=`, `/api/diff`, MCP, `/tools` | Task 18 |
| `[{ALLOW …}]` visible in search snippets or retrieval | Task 19 |
| the login stored as author is the full name instead | Task 6 |

Optional but recommended once, to see the probe go red: in `DefaultPublicIdentity.displayName` temporarily `return storedPrincipal;`,
rebuild `wikantik-main` and the WAR, rerun the command above, and expect failures on every `showsAuthor` surface; then revert the line.

Surfaces this module cannot reach, and where they are covered instead:

| Surface | Why not here | Covered by |
|---|---|---|
| `retrieve_context`, `GET /api/bundle`, dense chunk text | hybrid retrieval is wired only in `wikantik-it-test-dense` (needs the embedder); here `retrieve_context` is skipped when absent | Task 19's chunker unit tests; extend `DenseBundleIT` with one extra page that carries an `[{ALLOW edit leak-…}]` line and assert `/api/bundle?q=` never returns it, if you want a wire-level proof |
| SSO first-login naming (`Member N`, suffixes) | needs Keycloak | Task 2's unit tests and `wikantik-it-test-sso` |
| SCIM `name.formatted` rules and `409` | this module has `ScimUsersIT`, but the rules are Task 3's contract | Task 3's tests (add a duplicate-name `409` case to `ScimUsersIT` there) |
| `GET /api/export` (Obsidian zip, stripped directives) | binary body | `ExportIT` (Task 18's export test) |
| Email, server logs, audit log | operator-only by design | out of scope in the spec |

- [ ] **Step 4: Re-run until green, then run the neighbouring suites it shares a webapp with**

```bash
bin/agent-build.sh start rest-it -- bin/run-tests.sh --module rest
bin/agent-build.sh wait rest-it 540
```

Expected: `RESULT: ALL PASSED` in `.test-suite-logs/report.txt` for `IT: wikantik-it-tests/wikantik-it-test-rest`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/DisplayNameLeakProbeIT.java
git commit -m "test(it): leak probe — no public surface returns a login name" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 23: Documentation, shipped help pages, privacy policy and CHANGELOG

**Files:**
- Modify: `docs/admin/Security.md` (after line 191; lines 208-222; before line 224; lines 454-455)
- Modify: `docs/user/CommentsAndMentions.md` (lines 28-29, 50-51, 78-103, 120, the Threads/Comments tables, 199, 205-210, 221-224)
- Modify: `docs/admin/SingleSignOn.md` (line 119; after line 138)
- Modify: `docs/admin/ScimProvisioning.md` (lines 97-106, 283-285)
- Modify: `docs/user/AccountAndLogin.md` (lines 31-32, 36), `docs/user/PersonalZone.md` (lines 57-59, 63-64, 124-125), `docs/admin/AdminPanel.md` (line 41)
- Modify: `wikantik-war/src/main/webapp/privacy-policy.html` (lines 21, 42, 44, 80, 108, 119) and its wiki mirror `docs/wikantik-pages/PrivacyPolicy.md` (lines 18, 38, 40, 61, 88, 101)
- Modify: `wikantik-wikipages/en/src/main/resources/TextFormattingRules.md:48` and `.../LoginHelp.md:25` (the pages shipped in the image), and their mirrors `docs/wikantik-pages/TextFormattingRules.md:87`, `docs/wikantik-pages/LoginHelp.md:29`
- Modify: `CLAUDE.md` (Security Model, after the "Pluggable authentication" bullet)
- Modify: `CHANGELOG.md` (`## [Unreleased]`)
- Test: the stale-claims grep and `bin/check-md-links.py` below; `ShippedPagesMathHealthTest`; `PrivacyPolicyReachabilityIT` (unchanged, still requires the contact address)

**Interfaces:**
- Consumes: the behaviour Tasks 1–19 settled. Where the text commits to something Section A may still decide, it is listed in "Notes for the coordinator" at the end of this file: the `400` versus `409` mapping for a taken name, the SCIM placeholder when `name.formatted` is omitted, the `displayName` SCIM attribute staying the wiki name, and what a deleted account's old attribution shows.
- Produces: nothing in code. Task 24 reuses the new `PrivacyPolicy`, `TextFormattingRules` and `LoginHelp` wording and the `Security.md` / `SingleSignOn.md` / `ScimProvisioning.md` sections when it updates the production wiki pages.

Not a documentation change: the numbers `docs/admin/McpAgents.md` and `CLAUDE.md` quote (29 admin tools, 21 knowledge tools, 36 REST servlets) do not move with this release, so they are untouched.

- [ ] **Step 1: Write the failing check (a stale-claims grep) and capture the baseline**

```bash
grep -rnaE 'Two accounts may share a full name|Two users may share a name|merely shares is not refused|comments show your login name|Your names are not secret|Name people by their login name|Name individual people by their login name|stay attributed to your username|under the name they were made with|edit your full name|Each row shows `@loginName`|\{loginName, fullName\}|Accepted mentions are inserted as `@loginName`|login name starts with, or full name contains' docs/admin docs/user docs/wikantik-pages/PrivacyPolicy.md docs/wikantik-pages/TextFormattingRules.md docs/wikantik-pages/LoginHelp.md wikantik-war/src/main/webapp/privacy-policy.html wikantik-wikipages/en/src/main/resources | cut -c1-120
python3 bin/check-md-links.py && echo "links ok"
```

Expected before the edits: 16 hits across `ScimProvisioning.md`, `CommentsAndMentions.md`, `Security.md`, `AccountAndLogin.md`, `privacy-policy.html`, `PrivacyPolicy.md`,
`TextFormattingRules.md` (both copies) and `LoginHelp.md` (both copies), and `links ok`. Each hit is a sentence that is false once Tasks 1–19 land.
(Use `grep -a`: `grep` here is `ugrep -I`, which silently skips files it thinks are binary or ISO-8859-1.)

- [ ] **Step 2: Run it to confirm the baseline** — the command above, now, before editing.

- [ ] **Step 3: Apply the edits**

Save each diff block below to a file and apply it (`git apply --check x.diff && git apply x.diff`), or make the edit by hand. The
`+` lines are the exact text to add. Files under `docs/wikantik-pages/` are CRLF and the diffs already respect that.

The reference docs:


`docs/admin/Security.md` — the rewritten display-name bullet, the new "Who can see login names" section, the ACL-visibility paragraph and the ontology sentence:

```diff
--- a/docs/admin/Security.md
+++ b/docs/admin/Security.md
@@ -190,6 +190,15 @@
 ACLs through `RestServletBase.checkPagePermission()`, and `/wiki/{slug}?format=md|json`
 hides restricted pages with a 404.
 
+The directives name people by login, so their text is visible only to those who may change
+the page. The page API (`GET /api/pages/{name}`, old versions included), `?format=md|json`,
+history and diff, the Obsidian export, the knowledge MCP (`get_page`, `read_pages`) and
+`/tools/get_page` remove every `[{ALLOW ...}]` and `[{DENY ...}]` directive from the text they
+return to a caller without `edit` on the page (`AclDirectives.strip`). Chunking and the Lucene
+content field never contain them, so search results, bundles and retrieval never quote them.
+Editors and administrators see the directives as written, and ACL evaluation is unchanged.
+Name groups rather than people in ACLs where you can.
+
 ### Use login names in groups and ACLs
 
 Identify people by **login name** everywhere access is decided.
@@ -205,21 +214,52 @@
   could take the name and gain the entry. A name that no account has as its login still
   matches a user your web container authenticates under exactly that login without a wiki
   profile. Roles and groups are matched by name as before.
-- **Display names cannot impersonate a login, role or group.** A full name or wiki name
-  that is reserved (it equals a built-in role, `Admin`, a role or group named in a policy
-  grant, or an existing group) or that equals another account's login name is refused when
-  it is set or changed: `PUT /api/auth/profile` and `/admin/users` return `400`, and SCIM
-  `POST`, `PUT` and `PATCH` on `/scim/v2/Users` return `409` with `scimType` `uniqueness`
-  without saving. Two accounts may share a full name ("John Smith"), because access is
-  decided by login name only. Wiki names stay unique, so the second account gets a
-  numbered wiki name (`JohnSmith2`), which is stored and read back as is, and kept when an
-  identity provider re-sends the same name. A save that would still collide with another
-  account's login or wiki name (for example two concurrent writes) is refused with a generic
-  message: `409` `uniqueness` on SCIM; `400` on admin create/update (`/admin/users`) and self-service (`PUT /api/auth/profile`). SCIM `POST /Groups` returns `409` for a group name that
-  is an account's login, full or wiki name. SSO auto-provisioning falls back to the login
-  name for a reserved name claim and adds a numeric suffix ("jdoe 2") to a claim that
-  equals another account's login. Names are re-checked only when they change, so an
-  existing account is never blocked by a later collision.
+- **Display names are unique and cannot impersonate a login, role or group.** A display
+  name (`users.full_name`) is required, 2 to 64 characters long, and has no control
+  characters and no `@`. It must not be reserved (it equals a built-in role, `Admin`, a role
+  or group named in a policy grant, or an existing group), must not equal another account's
+  login name, and must not already be another account's display name, ignoring case and
+  surrounding whitespace. The rules run when a name is set or changed: `PUT
+  /api/auth/profile` and `/admin/users` refuse the request with `400` or `409` and the
+  reason, and SCIM `POST`, `PUT` and `PATCH` on `/scim/v2/Users` return `409` with
+  `scimType` `uniqueness`, without saving. The application checks uniqueness; there is no
+  database index (migrations run on every container start, and an index would fail on
+  existing duplicates), so two saves racing for the same name can both succeed. Wiki names
+  stay unique too: when the wiki name derived from a display name is already held, the
+  account gets a numbered one (`JohnSmith2`), which is stored and read back as is, and kept
+  when an identity provider re-sends the same name. A save that still collides with another
+  account's login or wiki name is refused with a generic message: `409` `uniqueness` on
+  SCIM; `400` on admin create/update (`/admin/users`) and self-service
+  (`PUT /api/auth/profile`). SCIM `POST /Groups` returns `409` for a group name that is an
+  account's login, full or wiki name. SSO auto-provisioning never fails on a name and never
+  uses the email address as one: it takes the IdP's name claim when it passes the rules,
+  adds a number ("Jane Doe 2") when that name is taken, and falls back to "Member N" when
+  there is no usable claim (see [SingleSignOn.md](SingleSignOn.md)). A name is re-checked
+  only when it changes, so an account that predates these rules is never blocked from saving
+  the rest of its profile; `bin/db/one-shots/dedupe_display_names.sh` renames older
+  duplicates once.
+
+### Who can see login names
+
+People are shown to each other by **display name**. A login name is private: with Google
+sign-in it is the user's email address. Only three things see one: the account's owner (on
+the Preferences page), administrators on `/admin/*`, and the admin MCP server, which is
+administrator-equivalent.
+
+- **Stored by login, shown by name.** Version authors, comments, mentions, page owners,
+  group members and ACLs are stored by login, which never changes. Every other surface
+  resolves the stored value through `PublicIdentity` as it answers: the page, list, history,
+  recent-changes and search APIs, the for-agent projection, comment threads, mentions and the
+  mention picker, `/feed.xml` and `/api/recent-articles`, the knowledge MCP, `/tools/*`, and
+  the public ontology (`dct:creator`). A rename therefore applies to every attribution at
+  once, within a minute (`PublicIdentity` caches for 60 seconds). A stored value that is not
+  a login (a name recorded before 2.4.58, an agent label) is shown as written, except an
+  email address, which is shown as "unknown contributor".
+- **Page access rules** are removed from text returned to anyone who cannot edit the page,
+  and from everything indexed; see [Page ACLs](#page-acls).
+- **Free text is shown as written.** An editor who types an email address into a page body
+  or an `author:` frontmatter line has published it. Only a value that happens to be a login
+  is resolved.
 
 ### Page ownership and the audit trail
 
@@ -451,7 +491,8 @@
 about cross-origin access. They are safe to leave public because of a public/restricted
 split: `PublicProjectionFilter` keeps pages, entities and edges that an anonymous guest
 cannot view out of the materialised dataset entirely, so restricted content cannot be
-queried. `/sparql` rejects update operations and caps results and run time. See
+queried. `/sparql` rejects update operations and caps results and run time. A page's
+`dct:creator` is its author's display name, never a login. See
 [OntologyManagement.md](OntologyManagement.md).
 
 ## Reduce risk from risky features
```

`docs/user/CommentsAndMentions.md` — the mention token, display names in the picker, inbox and REST tables:

```diff
--- a/docs/user/CommentsAndMentions.md
+++ b/docs/user/CommentsAndMentions.md
@@ -26,7 +26,7 @@
    the new-thread composer (`CommentComposer` in `PageView.jsx`). Press **Esc**
    to cancel.
 4. Type your comment in the **Add a comment** box. You can @-mention any wiki user
-   by typing `@` followed by the start of their login name (see @-mention
+   by typing `@` followed by the start of their display name (see @-mention
    autocomplete below).
 5. Press **Cmd+Enter** (or **Ctrl+Enter**) or click the **Comment** button to post.
    The Comments drawer opens with your new thread.
@@ -48,7 +48,7 @@
 
 - A status-filter selector: **Open**, **Resolved**, **All**. Defaults to Open.
 - Each thread card showing the anchor excerpt in quotes, all comments in the
-  thread with author names and body text, and a reply textarea.
+  thread with each author's display name and the body text, and a reply textarea.
 - A **Resolve** button on open threads. Resolved threads show a **Reopen** button
   instead.
 - Threads whose anchor text no longer appears in the page are grouped under a
@@ -77,11 +77,10 @@
 
 ## @-mention autocomplete
 
-In any comment or reply textarea, type `@` immediately followed by the start of a
-login name. A popover (`MentionPicker`) appears at the caret showing up to 8
-matching users. Mentions work only in comment and reply boxes, not in the page
-editor. Each row shows `@loginName` and, if different, the user's full
-name.
+In any comment or reply textarea, type `@` followed by the first letters of a person's
+display name. A popover (`MentionPicker`) appears at the caret showing up to 8 matching
+users. Mentions work only in comment and reply boxes, not in the page editor. Each row shows
+the person's display name; login names and email addresses are never shown.
 
 Navigate the popover with the keyboard:
 - **Up/Down arrows** — move selection.
@@ -91,14 +90,20 @@
 Click any candidate with the mouse (uses `onMouseDown` so the textarea does not
 lose focus first).
 
-Accepted mentions are inserted as `@loginName` in the comment body. `CommentBody`
-renders them as clickable chips that link to `/wiki/Users/{loginName}`.
+Accepting a candidate inserts a mention token, `@[Display Name](user:<id>)`, into the comment
+body. The text in the brackets is the name at the time of writing; `<id>` is the account's
+opaque user id (not its login name), so the mention keeps pointing at the same person if they
+later change their display name. `CommentBody` renders each token as a chip showing the
+person's *current* display name (the comment-threads response carries a `mentions` map from
+id to display name). A plain `@login` typed by hand, or left by an older version, is still
+recognised for notifications but is shown exactly as typed, so do not type other people's
+login names.
 
 The autocomplete calls `GET /api/users/mentionable?q={prefix}&limit=8` to fetch
-candidates (the server default is 8 and the cap is 10). The endpoint requires a
-signed-in session: anonymous callers receive `401`, because under SSO a login
-name is the user's email address and the endpoint must not be a public directory.
-The comment boxes simply offer no suggestions while signed out.
+candidates (the server default is 8 and the cap is 10). The endpoint returns only each
+person's id and display name. It requires a signed-in session: anonymous callers receive
+`401`, because the endpoint must not be a public directory. The comment boxes simply offer no
+suggestions while signed out.
 
 ## The unread mentions inbox (`/me/mentions`)
 
@@ -117,7 +122,7 @@
 
 - Filter between **Unread** and **All** using the tab buttons at the top of the
   page.
-- Each row shows who mentioned you, which page the comment is on (a link that
+- Each row shows who mentioned you (by display name), which page the comment is on (a link that
   opens the page with `?thread=...&comment=...` query params to scroll the
   comments drawer to the specific thread), whether the mention was on a page you
   own ("your page" tag), and a relative timestamp.
@@ -177,6 +182,12 @@
 | `POST` | `/api/comment-threads?page={slug}` | Create a new thread. Body: `{exact, text, prefix?, suffix?}` |
 | `DELETE` | `/api/comment-threads/{threadId}` | Delete a thread and all its comments. Requires page `delete` permission. |
 
+Thread responses carry display names, never logins. `createdBy`, `resolvedBy` and
+`comments[].author` are display names. `createdById`, `resolvedById` and `comments[].authorId`
+are opaque user ids (null for an author with no account); a client compares them with `userId`
+from `GET /api/auth/user` to find the caller's own comments. The top-level `mentions` object
+maps every user id mentioned in the returned bodies to that person's current display name.
+
 ### Comments within a thread
 
 | Method | Path | Description |
@@ -196,13 +207,13 @@
 
 | Method | Path | Description |
 |--------|------|-------------|
-| `GET` | `/api/users/mentionable?q={prefix}[&limit=N]` | Up to `limit` (default 8, max 10) unlocked users whose login name starts with, or full name contains, `q`. Returns `{users: [{loginName, fullName}]}`. Requires an authenticated session; anonymous callers receive 401. |
+| `GET` | `/api/users/mentionable?q={prefix}[&limit=N]` | Up to `limit` (default 8, max 10) unlocked users whose display name matches `q`. Returns `{users: [{id, displayName}]}`; no login name or email. Requires an authenticated session; anonymous callers receive 401. |
 
 ### Mention feed
 
 | Method | Path | Description |
 |--------|------|-------------|
-| `GET` | `/api/me/mentions[?status=unread|all&limit=N&before=ISO8601]` | Fetch mention feed. Default limit: 25, max: 50. |
+| `GET` | `/api/me/mentions[?status=unread|all&limit=N&before=ISO8601]` | Fetch mention feed. Default limit: 25, max: 50. `mentionedBy` is the mentioner's display name; the comment snippet is omitted when you cannot view the page. |
 | `GET` | `/api/me/mentions/unread-count` | Returns `{count: N}`. |
 | `POST` | `/api/me/mentions/{id}/read` | Mark one mention read (addressee only). |
 | `POST` | `/api/me/mentions/mark-all-read` | Mark all unread mentions read. Returns `{updated: N}`. |
@@ -218,10 +229,9 @@
 **Comments drawer is empty even though threads exist** — check the status filter.
 Flip it to "All" to confirm threads exist in resolved state.
 
-**@-mention popover does not appear** — the autocomplete triggers only when `@`
-is typed immediately before a run of `[A-Za-z0-9._-]` characters. A leading space
-before `@` is fine; what matters is that the pattern `@<word>` appears at the
-caret position.
+**@-mention popover does not appear** — you must be signed in (the lookup returns `401`
+otherwise), and the text after `@` must match the start of a display name. Type the
+person's name, not their login.
 
 **Notification badge does not update** — the badge refreshes on mount and on
 `visibilitychange`. Switch away from the tab and back to force a refresh. If the
```

`docs/admin/SingleSignOn.md` — display name selection at first sign-in:

```diff
--- a/docs/admin/SingleSignOn.md
+++ b/docs/admin/SingleSignOn.md
@@ -116,7 +116,7 @@
 | Property | Default | Maps to |
 |----------|---------|---------|
 | `wikantik.sso.claimMapping.loginName` | `preferred_username` | Wiki login name |
-| `wikantik.sso.claimMapping.fullName` | `name` | Display name |
+| `wikantik.sso.claimMapping.fullName` | `name` | Display name (first sign-in only) |
 | `wikantik.sso.claimMapping.email` | `email` | Email |
 
 `SSOLoginModule` does not read `wikantik.sso.*` itself. It reads JAAS options
@@ -137,6 +137,19 @@
 If the mapped claim is absent, the login name falls back to the profile's username
 and then its id (for Google, the numeric `sub`).
 
+### Display name at first sign-in
+
+People are shown to each other by display name, never by login (with
+`claimMapping.loginName = email` the login is an email address). When SSO creates an account,
+its display name comes from the `fullName` claim (default `name`) if it passes the
+display-name rules: 2 to 64 characters, no `@`, not reserved, not another account's login, and
+not already another account's display name (ignoring case). If another account already has
+the name, a number is added: `Jane Doe 2`, `Jane Doe 3`. If the claim is missing or unusable
+(for example it is the email address) the account gets the placeholder `Member N`, the lowest
+free N. The login or email is never used. SSO provisioning does not fail on a name conflict;
+it logs the choice at INFO. The name is not refreshed from the IdP on later sign-ins, so users
+change it themselves under **Preferences**.
+
 ## Identity binding and account linking
 
 SSO links an IdP identity to a local account on the claim named by
```

`docs/admin/ScimProvisioning.md` — `name.formatted` rules and the `409`:

```diff
--- a/docs/admin/ScimProvisioning.md
+++ b/docs/admin/ScimProvisioning.md
@@ -94,11 +94,16 @@
 - **SSO fail-closed**: if an account with the given `userName` already exists
   and does not carry an `sso.subject` marker, SCIM returns HTTP 409 with
   `scimType: uniqueness`. SCIM cannot claim a pre-existing local account.
-- **Names**: a `name.formatted` or `displayName` that is reserved (a built-in role,
-  `Admin`, or a role or group name) or that equals another account's login name is
-  refused with HTTP 409 and `scimType: uniqueness`, on `POST`, `PUT` and `PATCH`. Two
-  users may share a name: the second "John Smith" is created normally. Wiki names stay
-  unique, so the second account gets a numbered wiki name: `JohnSmith2` when the name
+- **Names**: `name.formatted` becomes the account's display name, which other users see in
+  place of the login, so it must satisfy the display-name rules: 2 to 64 characters, no `@`
+  and no control characters; not reserved (a built-in role, `Admin`, or a role or group
+  name); not another account's login name; and not already another account's display name
+  (ignoring case and surrounding whitespace). A violation is refused with HTTP 409 and
+  `scimType: uniqueness`, on `POST`, `PUT` and `PATCH`, and the `detail` names the rule. A
+  `POST` without a usable `name.formatted` creates the account with the placeholder display
+  name `Member N` (the lowest free N); a login is never used as a display name. A
+  `displayName` that is reserved or equals another account's login name is refused the same
+  way. Wiki names stay unique, so a clashing one gets a number: `JohnSmith2` when the name
   comes from `name.formatted`, `John Smith2` when it comes from `displayName`. The `201`
   response's `displayName` is that stored wiki name, and it is kept when the identity
   provider re-sends the same name in a later `PUT` or `PATCH`. A save that still collides
@@ -280,9 +285,11 @@
 account without an SSO link, SCIM cannot claim it — create the account via the
 admin UI instead, or remove it and let SCIM re-provision it.
 
-The same status is returned when `name.formatted` or `displayName` is a reserved name or
-another account's login name; the response `detail` names the value. Change the name in
-the identity provider. A name that another user merely shares is not refused.
+The same status is returned when `name.formatted` breaks a display-name rule, including
+being another account's display name (compared ignoring case), and when `name.formatted` or
+`displayName` is a reserved name or another account's login name; the response `detail` names
+the value. Change the name in the identity provider. Two users can no longer share a display
+name.
 
 **POST /Groups returns 400 invalidValue "No user found for member uid"**
 
```

`docs/user/AccountAndLogin.md` — the profile table:

```diff
--- a/docs/user/AccountAndLogin.md
+++ b/docs/user/AccountAndLogin.md
@@ -28,12 +28,13 @@
 
 | Field | Notes |
 |---|---|
-| Login Name, Wiki Name | Read-only. |
-| Full Name, Email | Editable. The email is where password resets are sent. |
+| Login name, Wiki Name | Read-only. Only you and administrators can see your login name. |
+| Display name | Editable and required. This is the name other people see on your edits, comments and mentions. 2 to 64 characters, no `@`, and not already used by someone else (case is ignored). The page tells you why a name is refused. |
+| Email | Editable. The email is where password resets are sent. |
 | Bio | Editable, up to 1000 characters. |
 | Current / New / Confirm New Password | Leave blank to keep your current password. |
 
-At the bottom, the **Danger Zone** deletes your account. It is permanent, and you confirm by typing your login name. Pages you contributed stay on the wiki and stay attributed to your username.
+At the bottom, the **Danger Zone** deletes your account. It is permanent, and you confirm by typing your login name. Pages you contributed stay on the wiki. Once the account is gone they are shown under the name stored with them, or as "unknown contributor" when that was an email address.
 
 ## Create a personal API key
 
```

`docs/user/PersonalZone.md` — the profile table and API rows:

```diff
--- a/docs/user/PersonalZone.md
+++ b/docs/user/PersonalZone.md
@@ -54,15 +54,15 @@
 
 | Field | Editable | Notes |
 |-------|----------|-------|
-| Login Name | Read-only | Your account's login identifier. Cannot be changed by self-service. |
-| Wiki Name | Read-only | CamelCase wiki identifier derived from your full name at registration. |
-| Full Name | Editable | Displayed in your profile and search results. |
+| Login name | Read-only | Your account's login identifier. Cannot be changed by self-service. Only you and administrators can see it. |
+| Wiki Name | Read-only | CamelCase wiki identifier derived from your display name at registration. |
+| Display name | Editable, required | The name other people see on your edits, comments, mentions and in search results. 2 to 64 characters, no `@`, unique (case is ignored). A refused name is explained under the field. |
 | Email | Editable | Used for password reset. |
 | Bio | Editable | Up to 1000 characters. Shown on your user profile page. |
 
 Click **Save Changes** to submit. The form calls `PUT /api/auth/profile` with
-body `{fullName, email, bio}`. On success the page shows "Profile updated
-successfully".
+body `{fullName, email, bio}`; `fullName` is the display name. On success the page shows
+"Profile updated successfully" and the sidebar picks up the new name at once.
 
 ### Changing your password
 
@@ -121,8 +121,8 @@
 
 | Method | Path | Description |
 |--------|------|-------------|
-| `GET` | `/api/auth/profile` | Returns `{loginName, fullName, email, bio, wikiName, created, lastModified}`. Requires authentication. |
-| `PUT` | `/api/auth/profile` | Update profile fields and/or password. Body: `{fullName?, email?, bio?, currentPassword?, newPassword?}`. Requires authentication. |
+| `GET` | `/api/auth/profile` | Returns `{loginName, fullName, email, bio, wikiName, created, lastModified}`; `fullName` is the display name. Requires authentication. |
+| `PUT` | `/api/auth/profile` | Update profile fields and/or password. Body: `{fullName?, email?, bio?, currentPassword?, newPassword?}`. A changed `fullName` must satisfy the display-name rules; a violation returns `400` or `409` with the reason. Requires authentication. |
 | `DELETE` | `/api/auth/profile` | Delete account. Body: `{confirmLoginName}`. Requires authentication. Admin accounts cannot self-delete. |
 | `POST` | `/api/auth/reset-password` | Send a new random password to the email address. Body: `{email}`. Unauthenticated. Rate-limited to 3 requests per email per hour. |
 
```

`docs/admin/AdminPanel.md` — the Users page note:

```diff
--- a/docs/admin/AdminPanel.md
+++ b/docs/admin/AdminPanel.md
@@ -39,7 +39,10 @@
 - Backed by: `/admin/users` and `/admin/users/{login}`, with `/lock`, `/unlock` and
   `/admin/users/bulk-action` (`AdminUserResource`).
 - Source: `AdminUsersPage.jsx`.
-- Notes: a user created here is flagged to change their password at first login.
+- Notes: a user created here is flagged to change their password at first login. The table
+  shows both the login and the display name; the display name obeys the same rules as on the
+  Preferences page and a refused name is explained under the field. Only `/admin/*` shows
+  logins.
   See [Security.md](Security.md#authenticate-users).
 
 ### Security
```

The shipped help pages and their repository mirrors (the first two ship in the image; the mirrors are what `docs/wikantik-pages/` keeps in step with production):


Shipped `TextFormattingRules`:

```diff
--- a/wikantik-wikipages/en/src/main/resources/TextFormattingRules.md
+++ b/wikantik-wikipages/en/src/main/resources/TextFormattingRules.md
@@ -45,7 +45,7 @@
 ## Plugins and page rules
 
 * `[{TableOfContents}]()` and other plugins use the `[{Plugin}]()` form. A plugin is named by its class, for example `[{RecentChangesPlugin}]()` or `[{ReferringPagesPlugin}]()`; [Linking](https://github.com/jakefearsd/wikantik/blob/main/docs/user/Linking.md) lists the ones that ship. [PageIndex](PageIndex) and [RecentChanges](RecentChanges) are built this way.
-* `[{ALLOW view Admin}]()` restricts who may view a page, and `[{ALLOW edit alice,bob}]()` who may edit it. Name people by their login name.
+* `[{ALLOW view Admin}]()` restricts who may view a page, and `[{ALLOW edit alice,bob}]()` who may edit it. Prefer naming a group. A person is named by login name, and these lines are shown only to people who can edit the page: everyone else gets the page without them.
 * Raw HTML is rendered after it is sanitised: scripts, frames, forms, event-handler attributes and unsafe link schemes are removed. Prefer Markdown and plugins.
 
 ## Metadata
```

Shipped `LoginHelp`:

```diff
--- a/wikantik-wikipages/en/src/main/resources/LoginHelp.md
+++ b/wikantik-wikipages/en/src/main/resources/LoginHelp.md
@@ -22,7 +22,9 @@
 
 ## Your profile and API keys
 
-Choose **Profile** in the sidebar (or open `/preferences`) to edit your full name, email and bio, change your password, and delete your account.
+Choose **Profile** in the sidebar (or open `/preferences`) to edit your display name, email and bio, change your password, and delete your account.
+
+Your **display name** is what other people see on your edits, comments and mentions. It must be unique (case is ignored), 2 to 64 characters, and cannot contain `@`. Your login name stays private: only you and administrators can see it.
 
 The **API Keys** section creates keys for scripts and AI clients. A key acts with your own permissions. A regular account can create keys with the `tools` scope (the `/tools/*` endpoints) or the `mcp_read` scope (read-only knowledge MCP access); keys with wider scope are created by an administrator. The secret is shown once, so copy it when you create it.
 
```

Mirror `LoginHelp`:

```diff
--- a/docs/wikantik-pages/LoginHelp.md
+++ b/docs/wikantik-pages/LoginHelp.md
@@ -26,7 +26,9 @@
 
 ## Your profile and API keys
 
-Choose **Profile** in the sidebar (or open `/preferences`) to edit your full name, email and bio, change your password, and delete your account.
+Choose **Profile** in the sidebar (or open `/preferences`) to edit your display name, email and bio, change your password, and delete your account.
+
+Your **display name** is what other people see on your edits, comments and mentions. It must be unique (case is ignored), 2 to 64 characters, and cannot contain `@`. Your login name stays private: only you and administrators can see it.
 
 The **API Keys** section creates keys for scripts and AI clients. A key acts with your own permissions. A regular account can create keys with the `tools` scope (the `/tools/*` endpoints) or the `mcp_read` scope (read-only knowledge MCP access); keys with wider scope are created by an administrator. The secret is shown once, so copy it when you create it.
 
```

The privacy policy exists three times: the static page that ships in the WAR (`/privacy-policy.html`, which the sign-in providers fetch), its repository
mirror, and the production wiki page (Task 24 updates that one). All three say the same thing. The static page:

```diff
--- a/wikantik-war/src/main/webapp/privacy-policy.html
+++ b/wikantik-war/src/main/webapp/privacy-policy.html
@@ -18,7 +18,7 @@
 </head>
 <body>
   <h1>Privacy Policy</h1>
-  <p><strong>Effective:</strong> 2026-10-13</p>
+  <p><strong>Effective:</strong> 2026-10-14</p>
   <p>This is the same policy as the <a href="/wiki/PrivacyPolicy">wiki version</a>.</p>
 
   <p>This is the privacy policy for <strong>wiki.wikantik.com</strong>, the public Wikantik wiki. I run it: Jacob Fear, <a href="mailto:jakefear@gmail.com">jakefear@gmail.com</a>. Wikantik is the wiki software; this policy covers this site and the contact form on the www.wikantik.com marketing site. If you run your own copy of Wikantik, this policy says nothing about your copy.</p>
@@ -39,9 +39,9 @@
 
   <p><strong>An account.</strong> The wiki stores your login name, email address, display (full) name, a wiki name, when the account was created and last changed, and when you last signed in. You can add a short bio (up to 1,000 characters), which only you and administrators can see. A password account stores a bcrypt hash of the password, never the password itself. An account created through Google sign-in has no password. If you create an API key, the wiki stores only a hash of the key, plus its label and the dates it was created and last used.</p>
 
-  <p><strong>What you write.</strong> Page edits, comments and file attachments are stored with the name they were made under: page history shows your display name, and comments show your login name. Most pages are public, so anyone can read them and their history. Comments and attachments are visible to whoever can see the page.</p>
+  <p><strong>What you write.</strong> Page edits, comments and file attachments are stored under your account, and everyone else sees them under your display name: page history, comments and mentions show the name you chose, never your login name. Most pages are public, so anyone can read them and their history. Comments and attachments are visible to whoever can see the page. Comments, and edits made after 2026-10-14, follow your display name if you change it; older history entries keep the name they were made under. If a page's access rule names you, that line is shown only to people who can edit the page.</p>
 
-  <p><strong>Your names are not secret.</strong> Signed-in users can look up active accounts by login name or display name through the @mention box in comments; that lookup is not available to visitors who are not signed in. Your names also stay visible wherever your content is attributed, as described under &ldquo;What you write&rdquo;. If you sign in with Google, your login name is your Google email address, so your email address can appear in comments and in that lookup. If that is a problem for you, write to me before you create an account. Reading the wiki never needs one.</p>
+  <p><strong>Your login name is private.</strong> Other people see your display name, never your login name. If you sign in with Google, your login name is your Google email address, and it is not shown in page history, comments, mentions, search results, feeds, the public data exports or the @mention box. Only you (on your preferences page) and the site administrator can see it. Signed-in users can look up other accounts by display name through the @mention box in comments; that lookup returns display names only and is not available to visitors who are not signed in. Display names are public and unique, so choose one you are happy to see next to your edits. Anything you type into a page yourself, such as an author line or an email address in the body, is shown as written.</p>
 
   <p><strong>An audit log.</strong> The wiki keeps an append-only record of security-relevant events: sign-ins and sign-outs (successful and failed), access denied, page saves, deletes and renames, group and profile changes, and API key events. Each entry records who did it, what happened, the IP address and the browser's user-agent. Page reads are not recorded unless a page is specifically flagged for it. Entries are not edited or removed when an account is deleted.</p>
 
@@ -77,7 +77,7 @@
 
   <p><strong>Google Fonts.</strong> The page design uses fonts that your browser downloads from Google (fonts.googleapis.com and fonts.gstatic.com) after the page has loaded. Google receives your IP address, your browser details and the address of the page you are on. If you block those requests, the wiki still works, with your browser's fallback fonts. See <a href="https://policies.google.com/privacy">Google's privacy policy</a>.</p>
 
-  <p><strong>Google sign-in (only if you choose it).</strong> The wiki asks Google for your basic profile and email address. It reads three things from the answer: your Google account's unique ID (to recognise you next time), your email address and your name. It creates your account from them: login name and email are your email address, and display name is your Google name. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run your account. Google learns that you signed in to this wiki. If you never choose Google sign-in, nothing is sent to Google except the fonts above.</p>
+  <p><strong>Google sign-in (only if you choose it).</strong> The wiki asks Google for your basic profile and email address. It reads three things from the answer: your Google account's unique ID (to recognise you next time), your email address and your name. It creates your account from them: login name and email are your email address. Your display name starts as your Google name; if another account already uses that name a number is added, and if Google gives no usable name the wiki uses a neutral one such as &ldquo;Member 3&rdquo;. You can change it at any time on your preferences page. The wiki never uses your email address as a display name. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run your account. Google learns that you signed in to this wiki. If you never choose Google sign-in, nothing is sent to Google except the fonts above.</p>
 
   <p><strong>Email.</strong> The only email the wiki can send is a password-reset message to the address on the account. The production wiki currently has no outgoing mail service configured, so it sends no email at all. If I turn that on, mail will go through an outside mail provider and I will name it here.</p>
 
@@ -105,7 +105,7 @@
   <ul>
     <li><strong>Change your details.</strong> Sign in and open <a href="https://wiki.wikantik.com/preferences">your preferences</a> to change your display name, email address, bio or password. Login name and wiki name are fixed once the account exists.</li>
     <li><strong>Delete your account.</strong> The Danger Zone at the bottom of the same page deletes it after you type your login name to confirm. That removes your profile (login name, email, names, bio and password hash) and your group memberships, revokes your API keys and signs you out. If you later sign in with Google again, a new account is created.</li>
-    <li><strong>What deletion does not remove.</strong> Your past edits and comments stay in the wiki, under the name they were made with. Audit log entries stay. Backups keep the old data until they age out, and anything search engines or others have already copied stays copied.</li>
+    <li><strong>What deletion does not remove.</strong> Your past edits and comments stay in the wiki. Once the account is gone they are shown under the name stored with them, or as &ldquo;unknown contributor&rdquo; when that was an email address. Audit log entries stay. Backups keep the old data until they age out, and anything search engines or others have already copied stays copied.</li>
     <li><strong>Ask me.</strong> Write to me for a copy of what your account holds, to correct something, or to ask about removing your name from old contributions. I will look at each request and tell you what can be done.</li>
   </ul>
 
@@ -116,7 +116,7 @@
   <p>This site is not directed at children under 13, and I do not knowingly collect their information. If you think a child has an account, write to me and I will remove it.</p>
 
   <h2>Changes</h2>
-  <p>When I change this policy I will update the effective date at the top, and the page history is public. 2026-10-13: rewritten from a generic template to describe what the wiki actually does.</p>
+  <p>When I change this policy I will update the effective date at the top, and the page history is public. 2026-10-13: rewritten from a generic template to describe what the wiki actually does. 2026-10-14: other people now see your display name instead of your login name, and display names are unique.</p>
 
   <h2>Contact</h2>
   <p>Jacob Fear, <a href="mailto:jakefear@gmail.com">jakefear@gmail.com</a>.</p>
```

The two mirrors under `docs/wikantik-pages/` (`PrivacyPolicy.md`, `TextFormattingRules.md`) use CRLF line endings, which a pasted diff would lose, so
edit them with this script instead: it replaces whole lines and keeps every line ending. The `new` strings are the exact text to add.

```python
#!/usr/bin/env python3
"""Whole-line edits for the two CRLF wiki-page mirrors. Run from the repository root; each old line must match exactly once."""
EDITS = [
    ('docs/wikantik-pages/PrivacyPolicy.md',
     '**Effective:** 2026-10-13',
     '**Effective:** 2026-10-14'),
    ('docs/wikantik-pages/PrivacyPolicy.md',
     '**What you write.** Page edits, comments and file attachments are stored with the name they were made under: page history shows your display name, and comments show your login name. Most pages are public, so anyone can read them and their history. Comments and attachments are visible to whoever can see the page.',
     "**What you write.** Page edits, comments and file attachments are stored under your account, and everyone else sees them under your display name: page history, comments and mentions show the name you chose, never your login name. Most pages are public, so anyone can read them and their history. Comments and attachments are visible to whoever can see the page. Comments, and edits made after 2026-10-14, follow your display name if you change it; older history entries keep the name they were made under. If a page's access rule names you, that line is shown only to people who can edit the page."),
    ('docs/wikantik-pages/PrivacyPolicy.md',
     '**Your names are not secret.** Signed-in users can look up active accounts by login name or display name through the @mention box in comments; that lookup is not available to visitors who are not signed in. Your names also stay visible wherever your content is attributed, as described under "What you write". If you sign in with Google, your login name is your Google email address, so your email address can appear in comments and in that lookup. If that is a problem for you, write to me before you create an account. Reading the wiki never needs one.',
     '**Your login name is private.** Other people see your display name, never your login name. If you sign in with Google, your login name is your Google email address, and it is not shown in page history, comments, mentions, search results, feeds, the public data exports or the @mention box. Only you (on your preferences page) and the site administrator can see it. Signed-in users can look up other accounts by display name through the @mention box in comments; that lookup returns display names only and is not available to visitors who are not signed in. Display names are public and unique, so choose one you are happy to see next to your edits. Anything you type into a page yourself, such as an author line or an email address in the body, is shown as written.'),
    ('docs/wikantik-pages/PrivacyPolicy.md',
     "**Google sign-in (only if you choose it).** The wiki asks Google for your basic profile and email address. It reads three things from the answer: your Google account's unique ID (to recognise you next time), your email address and your name. It creates your account from them: login name and email are your email address, and display name is your Google name. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run your account. Google learns that you signed in to this wiki. If you never choose Google sign-in, nothing is sent to Google except the fonts above.",
     '**Google sign-in (only if you choose it).** The wiki asks Google for your basic profile and email address. It reads three things from the answer: your Google account\'s unique ID (to recognise you next time), your email address and your name. It creates your account from them: login name and email are your email address. Your display name starts as your Google name; if another account already uses that name a number is added, and if Google gives no usable name the wiki uses a neutral one such as "Member 3". You can change it at any time on your preferences page. The wiki never uses your email address as a display name. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run your account. Google learns that you signed in to this wiki. If you never choose Google sign-in, nothing is sent to Google except the fonts above.'),
    ('docs/wikantik-pages/PrivacyPolicy.md',
     '- **What deletion does not remove.** Your past edits and comments stay in the wiki, under the name they were made with. Audit log entries stay. Backups keep the old data until they age out, and anything search engines or others have already copied stays copied.',
     '- **What deletion does not remove.** Your past edits and comments stay in the wiki. Once the account is gone they are shown under the name stored with them, or as "unknown contributor" when that was an email address. Audit log entries stay. Backups keep the old data until they age out, and anything search engines or others have already copied stays copied.'),
    ('docs/wikantik-pages/PrivacyPolicy.md',
     'When I change this policy I will update the effective date at the top, and the page history is public. 2026-10-13: rewritten from a generic template to describe what the wiki actually does.',
     'When I change this policy I will update the effective date at the top, and the page history is public. 2026-10-13: rewritten from a generic template to describe what the wiki actually does. 2026-10-14: other people now see your display name instead of your login name, and display names are unique.'),
    ('docs/wikantik-pages/TextFormattingRules.md',
     '*   **Example:** `[{ALLOW edit Admin,Editors}]`. Name individual people by their login name.',
     '*   **Example:** `[{ALLOW edit Admin,Editors}]`. Prefer groups. Name an individual by login name; ACL lines are shown only to people who can edit the page, and are removed from the page text, search index and exports that everyone else sees.'),
]
for rel in sorted({e[0] for e in EDITS}):
    with open(rel, encoding='utf-8', newline='') as f:
        text = f.read()
    for path, old, new in EDITS:
        if path != rel:
            continue
        assert text.count(old + '\r\n') == 1, (rel, old[:60])
        text = text.replace(old + '\r\n', new + '\r\n')
    with open(rel, 'w', encoding='utf-8', newline='') as f:
        f.write(text)
    print('edited', rel)
```

The diffs and the script use `2026-10-14` as the effective date. Re-stamp it with the day you actually deploy (Task 24 deploys on the release day):

```bash
TODAY=$(date +%F)
if [ "$TODAY" != "2026-10-14" ]; then
  sed -i "s/2026-10-14/$TODAY/g" wikantik-war/src/main/webapp/privacy-policy.html docs/wikantik-pages/PrivacyPolicy.md
fi
git diff --stat -- wikantik-war/src/main/webapp/privacy-policy.html docs/wikantik-pages/PrivacyPolicy.md   # only the intended lines changed; CRLF kept
```

`CLAUDE.md` — one line, in the Security Model list:

```diff
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -459,6 +459,7 @@
 - Deserialization filtering — `ObjectInputFilter` whitelists on all `ObjectInputStream` usage
 - MCP API-key scopes are rank-based (`ApiKeyService.Scope`): `mcp_read` (rank 1, read-only, confined to `/knowledge-mcp`) and `mcp_admin` (rank 3, full admin `/wikantik-admin-mcp` surface) — `mcp_read ⊂ mcp_admin`. Split in 2.4.18 so a leaked "read-only knowledge agent" token is no longer also a content-write + KG-curation credential. `mcp_admin` was called `mcp` until 2.4.54: `"mcp"` stays accepted as an alias (mint requests and stored rows — V061 keeps it in the CHECK, rows are not rewritten), and every use of a legacy key is WARNed by `LegacyScopeUseLog` (key id/label/principal/IP/User-Agent, throttled hourly per key+IP) so harnesses can be found and re-keyed. Admin MCP keys stay fully privileged by decision (#62, an intermediate `mcp_content` tier, closed won't-fix).
 - Pluggable authentication (LDAP, database, container, SSO via pac4j)
+- Public identity is the **display name** (`users.full_name`: required, unique, rules in `ProfileNameRules`); login names (emails under Google SSO) are admin-only — every non-admin surface resolves stored logins through `PublicIdentity`, authorship is stored as the login (`AuthorKeys`), and `[{ALLOW …}]` directives are stripped (`AclDirectives`) for callers without `edit` and at index time.
 - SSO identity binding keys on `wikantik.sso.identityClaim` (default `sub`); set to `preferred_username` to trust a mutable claim. SSO never adopts a pre-existing non-SSO-linked local account of the same name (auto-provisioned profiles carry an `sso.subject` marker; a name collision without a matching marker fails closed). Multi-valued IdP claims are normalised to their first scalar; the SAML HTTP-POST `/sso/callback` is exempt from the CSRF synchronizer-token filter (the IdP-signed assertion is its own CSRF defense), and a successful SSO login rotates the HTTP session (fixation defense). SSO failures redirect to the `/login` SPA route with an `?error=` code surfaced by `LoginPage` — `/login` is dual-registered (web.xml + `SpaRoutingFilter.SPA_EXACT`).
 
 ### Important Configuration
```

`CHANGELOG.md` — the `[Unreleased]` entry. `bin/cut-release.sh` renames the heading to `[2.4.58] - <date>` and opens a fresh empty `[Unreleased]`, so it must be committed before the release is cut:

```diff
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -6,6 +6,67 @@
 
 ## [Unreleased]
 
+### Upgrade notes
+- People are now shown to each other by **display name** instead of login name (with Google sign-in a
+  login is an email address). After deploying, in this order:
+  1. Run the one-time cleanup that brings existing display names in line with the new uniqueness rule.
+     `bin/db/one-shots/dedupe_display_names.sh` is a dry run; add `--apply` to commit. In each group of
+     accounts whose display names match (ignoring case) the earliest-created account keeps its name and the
+     others become `<name> 2`, `<name> 3`, ...; a blank or `@`-containing display name becomes `Member N`.
+     It prints display names only. Against the production container:
+     `PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh`.
+  2. Rebuild the indexes (**Admin → Content → Index Status → Rebuild Indexes**, or
+     `POST /admin/content/rebuild-indexes`) so chunk text and the Lucene content field lose access-rule
+     directives and the search author field carries display names. Search is empty and the embeddings are
+     rebuilt while it runs.
+- Page history written before this release is not rewritten. Authorship is stored as the login from now on and
+  resolved to the account's current display name when read. Older entries hold the name their author had then
+  and are shown as written, except an email address, which is shown as "unknown contributor".
+- Display-name uniqueness is checked by the application, not by a database index, so there is no migration.
+- Client-visible API changes: `GET /api/users/mentionable` returns `{users: [{id, displayName}]}` (no
+  `loginName` or `fullName`); `GET /api/comment-threads` returns display names and adds `createdById`,
+  `resolvedById`, `comments[].authorId` and a `mentions` map from user id to display name; `GET /api/auth/user`
+  adds `userId`; `mentionedBy` on `GET /api/me/mentions` is a display name. Mentions are stored as
+  `@[Display Name](user:<id>)`; a hand-typed `@login` still notifies but is shown as typed.
+
+### Added
+- Display-name rules (`ProfileNameRules`): required, 2 to 64 characters, no `@` or control characters, not
+  reserved, not another account's login, and unique ignoring case and surrounding whitespace. They apply to
+  Preferences, Admin → Users, SSO provisioning and SCIM `name.formatted` (SCIM answers `409` `uniqueness`).
+- `PublicIdentity`, the one service that turns a stored login into the account's current display name for every
+  non-admin surface (cached for 60 seconds, never throws).
+- Preferences and Admin → Users label the field "Display name", check the rules in the browser and show the
+  server's reason under the field. The login name stays visible to its owner, read-only.
+- `bin/db/one-shots/dedupe_display_names.sh` and its offline guard `bin/tests/test-dedupe-display-names.sh`.
+- `DisplayNameLeakProbeIT`: an account whose login looks like an email address edits, comments, is mentioned
+  and is named in an access rule; every public surface is then asserted not to return the login.
+
+### Changed
+- SSO first login takes the display name from the IdP `name` claim when it passes the rules, adds ` 2`, ` 3`,
+  ... when it is taken, and otherwise uses `Member N`. It never uses the email or login.
+- Saving a profile (your own or an administrator's edit) updates the live session's name at once.
+- The mention picker lists display names and inserts `@[Display Name](user:<id>)`; comment chips show the
+  person's current display name.
+
+### Security
+- Login names (email addresses under Google sign-in) are no longer exposed to anonymous visitors or other
+  signed-in users. The page, list, history and recent-changes APIs, search, the for-agent projection, comment
+  threads, mentions and the mention picker, `/feed.xml`, `/api/recent-articles`, the knowledge MCP, `/tools/*`
+  and the public ontology (`dct:creator`) now return display names. Only the account's owner, `/admin/*` and
+  the admin MCP server see logins.
+- `[{ALLOW ...}]` and `[{DENY ...}]` directives, which name people by login, are removed from page text returned
+  to callers without `edit` on the page (page API, `?format=md|json`, history and diff, knowledge MCP,
+  `/tools/get_page`, Obsidian export) and from chunks and the Lucene index for everyone.
+- The recent-articles manager behind the feeds and `GET /api/recent-articles` no longer lists pages an
+  anonymous visitor cannot view, and `GET /api/me/mentions` omits the comment snippet when the recipient
+  cannot view the page.
+
+### Fixed
+- The "My pages" list matched authors by full name as well as login, so accounts sharing a name saw each
+  other's pages; it now matches the stored login exactly.
+- The page-owner bootstrap compared the version author (a full name) with logins and almost never resolved
+  an owner; the author is now a login.
+
 ## [2.4.57] - 2026-10-13
 
 ### Upgrade notes
```

- [ ] **Step 4: Re-run the checks**

```bash
grep -rnaE 'Two accounts may share a full name|Two users may share a name|merely shares is not refused|comments show your login name|Your names are not secret|Name people by their login name|Name individual people by their login name|stay attributed to your username|under the name they were made with|edit your full name|Each row shows `@loginName`|\{loginName, fullName\}|Accepted mentions are inserted as `@loginName`|login name starts with, or full name contains' docs/admin docs/user docs/wikantik-pages/PrivacyPolicy.md docs/wikantik-pages/TextFormattingRules.md docs/wikantik-pages/LoginHelp.md wikantik-war/src/main/webapp/privacy-policy.html wikantik-wikipages/en/src/main/resources | cut -c1-120      # expect no output
python3 bin/check-md-links.py && echo "links ok"
mvn -B -o test -pl wikantik-main -Dtest='ShippedPagesMathHealthTest' -Dsurefire.failIfNoSpecifiedTests=false   # the mirrors under docs/wikantik-pages are scanned for math defects
git diff --stat | tail -3
```

Expected: the grep prints nothing (`CHANGELOG.md` is outside its scope on purpose: its old entries are history), `links ok`, the Maven test passes,
and `git diff --stat` lists only the files above. `PrivacyPolicyReachabilityIT` only checks status 200 and the contact address, which are unchanged.

- [ ] **Step 5: Commit**

```bash
git add docs/admin/Security.md docs/admin/SingleSignOn.md docs/admin/ScimProvisioning.md docs/admin/AdminPanel.md \
        docs/user/CommentsAndMentions.md docs/user/AccountAndLogin.md docs/user/PersonalZone.md \
        wikantik-war/src/main/webapp/privacy-policy.html docs/wikantik-pages/PrivacyPolicy.md \
        wikantik-wikipages/en/src/main/resources/TextFormattingRules.md wikantik-wikipages/en/src/main/resources/LoginHelp.md \
        docs/wikantik-pages/TextFormattingRules.md docs/wikantik-pages/LoginHelp.md CLAUDE.md CHANGELOG.md
git commit -m "docs: display names — identity and visibility, mentions, SSO, SCIM, profile, privacy policy, 2.4.58 notes" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 24: Release 2.4.58 and roll it out

**Files:**
- Modify (by `bin/cut-release.sh`): every `pom.xml` (2.4.58 and then 2.4.59-SNAPSHOT) and `CHANGELOG.md` (`[Unreleased]` becomes `[2.4.58] - <today>`)
- No other repository files. Throwaway helper scripts live in `/tmp/wiki-2458/`, outside the tree.
- Test: the gates in Steps 2–3 before the tag; the post-deploy checks in Step 8 and the leak spot-checks in Steps 8, 10 and 12 after it.

**Interfaces:**
- Consumes: Tasks 1–23 committed on `main`; `bin/cut-release.sh`, `bin/deploy-release.sh`, `bin/remote.sh` (`psql`, `logs`, `status`, `backup-trigger`, `pages-push`, `rollback`), `bin/smoke-wiki.sh`, `remote.env` (`REMOTE_HOST=docker1`, `REMOTE_REPO_DIR=/home/jakefear/wikantik/repo`, pages in `/home/jakefear/wikantik/pages`, `HEALTH_URL=http://docker1:8080/api/health`), the gitignored `.env.prod`, and the admin MCP tools `read_page` / `update_page` (registered in this session against production).
- Produces: `ghcr.io/jakefearsd/wikantik:2.4.58` running on docker1; production display names unique; indexes rebuilt; the five affected wiki pages updated.

Conventions that matter here:
- **Steps marked PROD WRITE change shared production state** (deploy, the data cleanup, the index rebuild, page pushes). Run them only with the owner's go-ahead, and do not retry a failed one blindly. `bin/remote.sh` and `deploy-release.sh` need the LAN: if `docker1` does not resolve, a VPN is hijacking DNS (disconnect it).
- **Long Maven or test runs go through `bin/agent-build.sh`** (`start`, then repeated `wait <name> 540` until exit 0 or 1; never end the turn to "wait for a notification").
- **Never print secret values.** The environment comparison below compares key names and hashes only.

- [ ] **Step 1: Preconditions**

```bash
git status --porcelain                          # empty
git rev-parse --abbrev-ref HEAD                 # main
git worktree list                               # only this checkout; remove strays: git worktree remove -f -f <path>
git log --oneline origin/main..HEAD             # exactly the commits of Tasks 1-23
grep -n '^## \[Unreleased\]' -A4 CHANGELOG.md   # the Task 23 entry is there
test -f .env.prod && echo ".env.prod present" || echo "STOP: .env.prod missing"
```

- [ ] **Step 2: The static gates that `bin/run-tests.sh --all` does not run**

`run-tests.sh` runs no PMD ratchet and no OSV scan, and the tag build in CI skips tests, so a release can tag green locally and turn Quality Gates red on push.

```bash
bin/agent-build.sh start build -- mvn -B -T 1C clean install -DskipTests
bin/agent-build.sh wait build 540                                   # until exit 0
bin/agent-build.sh start pmd -- mvn -B -fn pmd:check -Pcomplexity-gate
bin/agent-build.sh wait pmd 540
bin/agent-build.sh tail pmd 3000 | grep -E 'BUILD SUCCESS|PMD Failure'    # exactly one BUILD SUCCESS and no "PMD Failure" lines
( cd wikantik-frontend && npm ci --no-audit --no-fund --ignore-scripts && npm run lint && npm audit signatures \
    && npm audit && npm run test:coverage && git diff --exit-code -- package-lock.json )
for s in $(ls bin/tests/test-*.sh | sort); do bash "$s" > /tmp/shell-test.log 2>&1 && echo "ok   $s" || { echo "FAIL $s"; tail -20 /tmp/shell-test.log; break; }; done
python3 bin/check-md-links.py && echo "links ok"
# optional, mirrors CI's osv-scan job when osv-scanner is installed:
#   mvn -B -q -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json -DincludeTestScope=true
#   osv-scanner scan source --config osv-scanner.toml -L target/bom.json -L wikantik-frontend/package-lock.json
bin/agent-build.sh start cov -- mvn -B clean install -Pcoverage -DskipITs      # the weekly CI job: per-module line-coverage floors
bin/agent-build.sh wait cov 540
```

A red PMD line is a new complexity violation: decompose the class (do not add it to `build-support/pmd-complexity-baseline.properties`). A red coverage floor means a module's new code is under-tested: add tests; never lower a floor. A new `npm audit` advisory is fixed forward before tagging.

- [ ] **Step 3: The full gate**

```bash
bin/agent-build.sh start gate -- bin/run-tests.sh --all
bin/agent-build.sh wait gate 540            # repeat until it exits 0 (success) or 1 (failure)
```

Then prove the result is this run's and real (stale logs and the last-line summary have both misled before):

```bash
head -3 .build-logs/gate.log                                       # the command line and start time are this run's
grep -E 'RESULT:|TOTAL RUNTIME' .test-suite-logs/report.txt        # RESULT: ALL PASSED
grep -E 'Tests run:' .test-suite-logs/phase1-unit.log | grep -v 'Time elapsed' \
  | grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' \
  | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6; s+=$8} END{printf "Tests=%d Failures=%d Errors=%d Skipped=%d\n",t,f,e,s}'     # ~9,700+ tests, 0 failures, 0 errors
R=wikantik-it-tests/wikantik-it-test-rest/target/failsafe-reports/TEST-com.wikantik.its.rest.DisplayNameLeakProbeIT.xml
grep -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' "$R"                       # the leak probe ran: tests="53", errors="0", failures="0"
grep -B2 '<skipped' "$R" | grep -E 'testcase|skipped' | cut -c1-200                                       # only the best-effort surfaces named in Task 22
```

Do not continue past a red gate, and do not call a failure pre-existing: fix it in this session.

- [ ] **Step 4: Cut the release locally**

```bash
printf 'n\n' | bin/cut-release.sh 2.4.58       # answers "n" at the push prompt, so the push below is explicit
git log --oneline -3                           # "build: bump main to 2.4.59-SNAPSHOT post-release", "release: 2.4.58", then the Task 23 commit
git tag -n1 v2.4.58                            # Wikantik 2.4.58
grep -n '^## \[' CHANGELOG.md | head -3        # [Unreleased], [2.4.58] - <today>, [2.4.57]
git show --stat HEAD~1 | tail -5               # poms and CHANGELOG.md only
```

- [ ] **Step 5: Push (this publishes) and watch CI**

```bash
git push origin main v2.4.58
RUN=$(gh run list --workflow=release.yml -L1 --json databaseId -q '.[0].databaseId'); gh run watch "$RUN" --exit-status
gh run list --workflow=quality-gates.yml -L1            # the push run: static-gates, shell-tests, osv-scan must all go green
docker manifest inspect ghcr.io/jakefearsd/wikantik:2.4.58 > /dev/null && echo "image published"
```

`gh run watch` stops after about five minutes in some harnesses: re-run it on the same `$RUN`. A zero-step cancelled job is GitHub infrastructure ("hosted runner not acquired"): `gh run rerun "$RUN" --failed`. A real red job is fixed forward on `main`; the tag stays.

- [ ] **Step 6: Compare the environment file that the deploy will ship (never print values)**

`remote.sh deploy` overwrites production's `.env` with the local `.env.prod`, with no diff and no backup.

```bash
REMOTE_DIR=/home/jakefear/wikantik/repo
keys() { sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' | sort -u; }
diff <(keys < .env.prod) <(ssh docker1 "cat $REMOTE_DIR/.env" | keys) && echo "env key sets identical"
[ "$(sha256sum < .env.prod)" = "$(ssh docker1 "cat $REMOTE_DIR/.env" | sha256sum)" ] && echo "env files byte-identical" || echo "env VALUES differ"
```

Proceed only if the key sets are identical. If values differ, decide which side is right *before* deploying (if production was edited on the box, copy it down to `.env.prod` first); this release adds no new environment key.

- [ ] **Step 7 (PROD WRITE): Deploy**

```bash
bin/agent-build.sh start deploy -- bin/deploy-release.sh 2.4.58
bin/agent-build.sh wait deploy 540
```

It pulls the GHCR image, tags it, ships it, recreates the container (the entrypoint applies pending migrations; there are none in this release), polls `/api/health` and rolls back by itself on failure. Expect its last lines to say the running container is the deployed image and health is UP.
Rollback, if needed: `bin/remote.sh rollback` (one step back) or `bin/remote.sh rollback --to 2.4.57`. Be aware: pages saved under 2.4.58 carry the **login** as their author, and 2.4.57 shows stored authors as written, so a rollback after anyone has saved a page re-exposes that person's login in history. Keep the window short and tell the owner.

- [ ] **Step 8: Post-deploy checks**

```bash
bin/remote.sh status
ssh docker1 "docker image ls wikantik --format '{{.Repository}}:{{.Tag}} {{.ID}}'" | head -8      # wikantik:2.4.58 and wikantik:latest share one ID (a `26.04` tag instead of `2.4.58` would mean a locally built image; `deploy-release.sh` avoids that by pulling the CI image)
CN=$(ssh docker1 "docker ps --filter ancestor=wikantik:latest --format '{{.Names}}'" | head -1); echo "container: $CN"
ssh docker1 "docker inspect '$CN' --format '{{index .Config.Labels \"org.opencontainers.image.version\"}}  started {{.State.StartedAt}}'"   # 2.4.58
curl -fsS http://docker1:8080/api/health ; echo
curl -s --retry 20 --retry-delay 5 --retry-all-errors -o /dev/null http://docker1:8080/wiki/Main?format=md       # warm the cold paths first
bin/remote.sh logs --since=20m wikantik 2>&1 | grep -E ' ERROR ' | head -20                                    # expect none
bin/smoke-wiki.sh http://docker1:8080 > /tmp/smoke.log 2>&1; echo "smoke exit=$?"                                # a first-request timeout is a cold-cache false alarm: rerun once
```

Leak spot-check as an anonymous caller. The list of email-shaped logins is read from the database into a file and **not printed**; the loop prints only counts:

```bash
bin/remote.sh psql -- -At -c "SELECT lower(login_name) FROM users WHERE login_name LIKE '%@%'" | tr -d '\r' | grep -v '^$' > /tmp/prod-email-logins.txt
echo "logins to look for: $(wc -l < /tmp/prod-email-logins.txt)"            # 5 on 2026-10-13
BASE=http://docker1:8080
for path in '/api/pages?limit=1000' '/api/history/Main' '/api/recent-changes' '/api/search?q=wiki' '/wiki/Main?format=md' '/wiki/Main?format=json' \
            '/feed.xml' '/api/recent-articles' '/api/comment-threads?page=Main' '/export/graph.nt' \
            '/sparql?query=SELECT%20%3Fs%20%3Fc%20WHERE%20%7B%20%3Fs%20%3Chttp%3A%2F%2Fpurl.org%2Fdc%2Fterms%2Fcreator%3E%20%3Fc%20%7D'; do
  code=$(curl -s -o /tmp/leak.body -w '%{http_code}' -H 'Accept: */*' "$BASE$path")
  hits=$(grep -ciFf /tmp/prod-email-logins.txt /tmp/leak.body || true)
  printf '%-70s http=%s login-hits=%s\n' "${path:0:70}" "$code" "$hits"
done
curl -s -o /dev/null -w 'mentionable anonymous -> %{http_code} (want 401)\n' "$BASE/api/users/mentionable?q=Jake"
```

Every `login-hits` must be 0. Before the index rebuild (Step 10) the search and bundle surfaces may still carry old chunk text; the rows above do not depend on it. A non-zero count: stop, find the surface in Task 22's table, and fix forward (or roll back, see Step 7).

- [ ] **Step 9 (PROD WRITE): Make production display names unique**

```bash
bin/remote.sh backup-trigger daily                                                     # a fresh backup first; cheap insurance
PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh | tee /tmp/dedupe-dry.txt
```

Expected, matching the 2026-10-13 snapshot (10 accounts, one duplicate group of four): three `duplicate` rows `Jake Fear` → `Jake Fear 2`, `Jake Fear 3`, `Jake Fear 4` (in that order of creation) and no `blank` or `email-shaped` row. **If the plan shows anything else, stop and read it before applying.** Then:

```bash
PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh --apply | tee /tmp/dedupe-apply.txt
PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh | tail -4          # (0 rows)
bin/remote.sh psql -- -At -c "SELECT count(*) FROM (SELECT lower(btrim(full_name)) FROM users GROUP BY 1 HAVING count(*) > 1) d"                  # 0
bin/remote.sh psql -- -At -c "SELECT count(*) FROM users WHERE full_name LIKE '%@%' OR btrim(coalesce(full_name, '')) = ''"                      # 0
```

`/tmp/dedupe-apply.txt` is the undo record and holds display names only. The new names show on the site within a minute (the identity cache lives 60 seconds); the affected users' open sessions carry the old name until they sign in again.

- [ ] **Step 10 (PROD WRITE, needs a human): Rebuild the indexes**

This makes chunk text and the Lucene content field lose access-rule directives and puts display names in the search author field. It also re-embeds the corpus, and search returns nothing while it runs.

*Option A, preferred:* signed in as an administrator at `https://wiki.wikantik.com/admin/content`, open the **Index Status** tab, choose **Rebuild Indexes** and confirm. *Option B* (only if you have a password-based admin account; type the password when asked, it is never written down):

```bash
read -r -p 'admin login: ' U; read -r -s -p 'password: ' P; echo
J=$(mktemp); curl -s -c "$J" -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"$P\"}" http://docker1:8080/api/auth/login -o /dev/null -w 'login %{http_code}\n'
curl -s -b "$J" -X POST http://docker1:8080/admin/content/rebuild-indexes -w '\nHTTP %{http_code}\n'     # 202; 409 means one is already running
curl -s -b "$J" http://docker1:8080/admin/content/index-status | python3 -m json.tool | head -40            # poll until idle and the embedding bootstrap is COMPLETED
unset P; rm -f "$J"
```

Single-sign-on-only administrators have no password: use Option A. When the status is idle and the embedding bootstrap `COMPLETED` (a `FAILED` bootstrap leaves hybrid search on BM25 only until the next successful run):

```bash
curl -s 'http://docker1:8080/api/search?q=wiki' | python3 -c "import json,sys; print(json.load(sys.stdin)['total'], 'search results')"           # > 0
curl -s 'http://docker1:8080/api/bundle?q=how+does+hybrid+retrieval+work' | python3 -c "import json,sys; print(json.load(sys.stdin).get('coverage'))"   # topSimilarity must not be -1.0
# any page that carries an access rule must serve none of it to an anonymous visitor
for P in $(ssh docker1 "grep -rlaF '[{ALLOW' /home/jakefear/wikantik/pages | xargs -n1 basename | sed 's/\.md$//'"); do
  code=$(curl -s -o /tmp/acl.body -w '%{http_code}' "http://docker1:8080/wiki/$P?format=md")
  printf '%-40s http=%s directive-lines=%s\n' "$P" "$code" "$(grep -ac '\[{ALLOW\|\[{DENY' /tmp/acl.body)"
done                                                                  # 404 (restricted) or directive-lines=0
```

- [ ] **Step 11 (PROD WRITE): Update the wiki pages that describe profiles, mentions and access rules**

Create the helper scripts (outside the repository):

```bash
mkdir -p /tmp/wiki-2458 && cd /tmp/wiki-2458
```

Save this as `/tmp/wiki-2458/wiki_edits.py` (it splices the committed Task 23 text into a production page body; every edit asserts its anchor occurs exactly once and keeps each place's own line ending):

```python
#!/usr/bin/env python3
"""Splice the 2.4.58 documentation changes into the body of a production wiki page.

Usage: wiki_edits.py PAGE IN OUT
  PAGE  WikantikSecurityGuide | WikantikSingleSignOn | WikantikScimProvisioning | PrivacyPolicy
  IN    a file holding the content the admin MCP read_page returned (frontmatter included)
  OUT   the content to send back with update_page

The new text is read from the repository docs as Task 23 committed them, so nothing is retyped. Every
edit asserts that its anchor occurs exactly once, and keeps the line ending of the place it edits
(production pages mix CRLF bodies with LF frontmatter, and a page edited through update_page must not
have all its line endings rewritten).
"""
import os
import re
import sys

REPO = os.environ.get('WIKI_REPO', '/home/jakefear/source/jspwiki')
page, src, dst = sys.argv[1:4]

WIKI_LINKS = {
    '[SingleSignOn.md](SingleSignOn.md)': '[Single Sign-On guide](WikantikSingleSignOn)',
    '[OntologyManagement.md](OntologyManagement.md)': '[Ontology Management guide](WikantikOntologyManagement)',
}


def repo(path):
    with open(f'{REPO}/{path}', encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


def wikify(text):
    for old, new in WIKI_LINKS.items():
        text = text.replace(old, new)
    return text


def once(text, anchor):
    assert text.count(anchor) == 1, f'{page}: expected exactly one occurrence of {anchor[:70]!r}, found {text.count(anchor)}'
    return text.index(anchor)


def eol_near(text, i, j):
    return '\r\n' if '\r\n' in text[max(0, i - 4):j + 4] else '\n'


def extract(doc, start, end, include_end=True):
    """The slice of repository doc `doc` from `start` through `end` (or up to it)."""
    body = repo(doc)
    i = body.index(start)
    j = body.index(end, i)
    return body[i:j + (len(end) if include_end else 0)]


def replace_between(text, start, end, new, include_end=True):
    i = once(text, start)
    j = text.index(end, i) + (len(end) if include_end else 0)
    return text[:i] + new.replace('\n', eol_near(text, i, j)) + text[j:]


def insert_before(text, marker, new):
    i = once(text, marker)
    return text[:i] + new.replace('\n', eol_near(text, i, i)) + text[i:]


def replace_once(text, old, new):
    once(text, old)
    return text.replace(old, new)


def replace_line(text, starts_with, new):
    lines = text.split('\n')
    hits = [k for k, line in enumerate(lines) if line.rstrip('\r').startswith(starts_with)]
    assert len(hits) == 1, f'{page}: expected one line starting {starts_with[:60]!r}, found {len(hits)}'
    k = hits[0]
    lines[k] = new + ('\r' if lines[k].endswith('\r') else '')
    return '\n'.join(lines)


def stamp(text, old_release):
    return replace_once(text, f'current as of release {old_release}.', 'current as of release 2.4.58.')


with open(src, encoding='utf-8', newline='') as f:
    text = f.read()

if page == 'WikantikSecurityGuide':
    sec = 'docs/admin/Security.md'
    text = stamp(text, '2.4.57')
    bullet = wikify(extract(sec, '- **Display names are unique and cannot impersonate a login, role or group.**',
                            'duplicates once.'))
    text = replace_between(text, '- **Display names cannot impersonate a login, role or group.**',
                           'existing account is never blocked by a later collision.', bullet)
    acl = extract(sec, 'The directives name people by login', 'Name groups rather than people in ACLs where you can.')
    k = once(text, 'hides restricted pages with a 404.')
    k = text.index('\n', k) + 1
    text = text[:k] + ('\n' + acl + '\n').replace('\n', eol_near(text, k, k)) + text[k:]
    section = extract(sec, '### Who can see login names', 'is resolved.')
    text = insert_before(text, '### Page ownership and the audit trail', section + '\n\n')
    text, n = re.subn(r'run time\. See(\r?\n)\[Ontology Management guide\]',
                      lambda m: "run time. A page's" + m.group(1) + "`dct:creator` is its author's display name, never a login. See"
                      + m.group(1) + '[Ontology Management guide]', text)
    assert n == 1, f'{page}: ontology paragraph not found exactly once'
elif page == 'WikantikSingleSignOn':
    sso = 'docs/admin/SingleSignOn.md'
    text = stamp(text, '2.4.56')
    text = replace_once(text, '| `wikantik.sso.claimMapping.fullName` | `name` | Display name |',
                        '| `wikantik.sso.claimMapping.fullName` | `name` | Display name (first sign-in only) |')
    section = extract(sso, '### Display name at first sign-in', 'change it themselves under **Preferences**.')
    text = insert_before(text, '## Identity binding and account linking', section + '\n\n')
elif page == 'WikantikScimProvisioning':
    scim = 'docs/admin/ScimProvisioning.md'
    text = stamp(text, '2.4.57')
    names = extract(scim, '- **Names**: `name.formatted` becomes', '- If `active: false` is included in the create body',
                    include_end=False)
    text = replace_between(text, '- **Names**: a `name.formatted` or `displayName` that is reserved',
                           '- If `active: false` is included in the create body', names + '- If `active: false` is included in the create body')
    para = extract(scim, 'The same status is returned when `name.formatted` breaks a display-name rule',
                   'Two users can no longer share a display\nname.')
    text = replace_between(text, 'The same status is returned when `name.formatted` or `displayName` is a reserved name or',
                           'A name that another user merely shares is not refused.', para)
elif page == 'PrivacyPolicy':
    mirror = repo('docs/wikantik-pages/PrivacyPolicy.md').split('\n')

    def mirror_line(starts_with):
        hits = [line for line in mirror if line.startswith(starts_with)]
        assert len(hits) == 1, f'mirror: expected one line starting {starts_with[:60]!r}'
        return hits[0]

    for old_start, new_start in [
        ('**Effective:**', '**Effective:**'),
        ('**What you write.**', '**What you write.**'),
        ('**Your names are not secret.**', '**Your login name is private.**'),
        ('**Google sign-in (only if you choose it).**', '**Google sign-in (only if you choose it).**'),
        ('- **What deletion does not remove.**', '- **What deletion does not remove.**'),
        ('When I change this policy I will update the effective date', 'When I change this policy I will update the effective date'),
    ]:
        text = replace_line(text, old_start, mirror_line(new_start))
else:
    sys.exit(f'unknown page {page}')

with open(dst, 'w', encoding='utf-8', newline='') as f:
    f.write(text)
print(f'{page}: wrote {dst}')
```

Save this as `/tmp/wiki-2458/shipped_pages_edit.py` (for the two shipped help pages):

```python
#!/usr/bin/env python3
"""Edit a production copy of a shipped (system) help page: TextFormattingRules or LoginHelp.

Usage: shipped_pages_edit.py PAGE IN OUT
  IN   the production file (e.g. fetched with: ssh docker1 cat /home/jakefear/wikantik/pages/PAGE.md)
  OUT  the edited file to stage for `bin/remote.sh pages-push`

Production's frontmatter is kept as it is; only the sentence about ACL names / the profile fields changes.
The new wording is read from the repository copies Task 23 committed, so nothing is retyped. Whichever
of the two known bodies production has (the long mirror or the short shipped one) is recognised; an
unknown body stops the script.
"""
import os
import sys

REPO = os.environ.get('WIKI_REPO', '/home/jakefear/source/jspwiki')
page, src, dst = sys.argv[1:4]


def repo(path):
    with open(f'{REPO}/{path}', encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


def line_starting(doc, prefix):
    hits = [line for line in repo(doc).split('\n') if line.startswith(prefix)]
    assert len(hits) == 1, f'{doc}: expected one line starting {prefix!r}, found {len(hits)}'
    return hits[0]


with open(src, encoding='utf-8', newline='') as f:
    text = f.read()
crlf = '\r\n' in text
body = text.replace('\r\n', '\n')

if page == 'TextFormattingRules':
    variants = [
        # (old line as it stands in production today, repository file holding the new line, prefix of that line)
        ('*   **Example:** `[{ALLOW edit Admin,Editors}]`. Name individual people by their login name.',
         'docs/wikantik-pages/TextFormattingRules.md', '*   **Example:** `[{ALLOW edit Admin,Editors}]`.'),
        ('* `[{ALLOW view Admin}]()` restricts who may view a page, and `[{ALLOW edit alice,bob}]()` who may edit it. Name people by their login name.',
         'wikantik-wikipages/en/src/main/resources/TextFormattingRules.md', '* `[{ALLOW view Admin}]()` restricts who may view a page'),
    ]
    for old, doc, prefix in variants:
        if body.count(old) == 1:
            body = body.replace(old, line_starting(doc, prefix))
            break
    else:
        sys.exit('TextFormattingRules: production body matches neither known variant; edit it by hand')
elif page == 'LoginHelp':
    old = 'Choose **Profile** in the sidebar (or open `/preferences`) to edit your full name, email and bio, change your password, and delete your account.'
    assert body.count(old) == 1, 'LoginHelp: the profile sentence was not found exactly once'
    new_doc = repo('docs/wikantik-pages/LoginHelp.md')
    start = new_doc.index('Choose **Profile** in the sidebar')
    end = new_doc.index('only you and administrators can see it.', start) + len('only you and administrators can see it.')
    body = body.replace(old, new_doc[start:end])
else:
    sys.exit(f'unknown page {page}')

with open(dst, 'w', encoding='utf-8', newline='') as f:
    f.write(body.replace('\n', '\r\n') if crlf else body)
print(f'{page}: wrote {dst}')
```

**11a. The shipped help pages `TextFormattingRules` and `LoginHelp`** are protected from MCP writes, so they go through `pages-push` with production's own frontmatter kept:

```bash
STAGE=$(mktemp -d)
for P in TextFormattingRules LoginHelp; do
  ssh docker1 cat /home/jakefear/wikantik/pages/$P.md > "$STAGE/$P.prod"
  python3 /tmp/wiki-2458/shipped_pages_edit.py $P "$STAGE/$P.prod" "$STAGE/$P.md"
  diff <(tr -d '\r' < "$STAGE/$P.prod") <(tr -d '\r' < "$STAGE/$P.md")      # only the changed sentence(s)
  rm "$STAGE/$P.prod"
done
touch "$STAGE"/*.md                                   # rsync --update only sends files newer than the remote copy
bin/remote.sh --dry-run pages-push "$STAGE"
bin/remote.sh pages-push "$STAGE"
for P in TextFormattingRules LoginHelp; do ssh docker1 cat /home/jakefear/wikantik/pages/$P.md | cmp - "$STAGE/$P.md" && echo "$P on prod = staged"; done
```

A warm instance may serve the old text from its page cache for up to an hour: flush it (**Admin → Content → Dashboard → Flush cache**, or `POST /admin/content/cache/flush` as an administrator), then check `curl -s 'http://docker1:8080/wiki/TextFormattingRules?format=md' | grep -c 'Prefer'` (1 or more) and `curl -s 'http://docker1:8080/wiki/LoginHelp?format=md' | grep -c 'display name'`.

**11b. `WikantikSecurityGuide`, `WikantikSingleSignOn`, `WikantikScimProvisioning` and `PrivacyPolicy`** are ordinary pages: edit them with the admin MCP tools. For each page `P`:

1. Call `read_page` with `slug: P`. Write the returned `content` byte for byte to `/tmp/wiki-2458/P.in` and note the returned `contentHash`.
2. `python3 /tmp/wiki-2458/wiki_edits.py P /tmp/wiki-2458/P.in /tmp/wiki-2458/P.out` (it stops with a message naming the anchor if a page differs from what Task 23 expects).
3. `diff <(tr -d '\r' < /tmp/wiki-2458/P.in) <(tr -d '\r' < /tmp/wiki-2458/P.out)`: only the intended paragraphs differ.
4. Call `update_page` with `pageName: P`, `content:` the contents of `/tmp/wiki-2458/P.out`, and `expectedContentHash:` the hash from step 1. (`canonical_id` is preserved; the frontmatter is re-emitted cleanly.)
5. Call `read_page` again and confirm the new text is there; confirm `curl -s "http://docker1:8080/wiki/P?format=md" | grep -c 'display name'` is positive.

`PrivacyPolicy` takes its `Effective:` date from the repository mirror, which Task 23 stamped with the release day. The static `/privacy-policy.html` shipped in the WAR already says the same (`curl -s http://docker1:8080/privacy-policy.html | grep -c 'login name is private'` is 1).

There is no wiki page for the comments guide, so nothing else to update. Finally, make sure the repository mirrors did not drift from what is now live: `diff <(ssh docker1 cat /home/jakefear/wikantik/pages/PrivacyPolicy.md | tr -d '\r' | sed '1,/^---$/d') <(tr -d '\r' < docs/wikantik-pages/PrivacyPolicy.md | sed '1,/^---$/d')` should be empty (repeat for the two help pages). If the live pages gained edits during the rollout, sync the mirrors in a follow-up commit named `docs(wiki): mirrors synced to prod after the 2.4.58 page updates`.

- [ ] **Step 12: Final verification**

Repeat the anonymous leak loop from Step 8 (every `login-hits` 0, `mentionable anonymous -> 401`), then:

```bash
bin/remote.sh logs --since=30m wikantik 2>&1 | grep -E ' ERROR ' | head -20       # none
git status --porcelain && git log --oneline origin/main..HEAD                     # clean and nothing unpushed
```

Done when: `/api/health` is UP on 2.4.58; the dedupe report was applied and a second dry run is empty; the index rebuild finished with embeddings `COMPLETED`; every spot-check row shows `login-hits=0`; and the four ordinary pages plus the two help pages show the new wording.

---

### Section notes

### Contract issues found in the real code

1. **Admin user create bypasses the rules unless Task 1 covers it.** `AdminUserResource.handleCreateUser` (lines 513-521) calls `ProfileNameRules.nameChangeError(…, null, null, fullName, derivedWikiName(fullName))` directly and applies the name only `if ( fullName != null )`; the contract says `fullNameChangeError` keeps its signature and applies `displayNameError`, which does not reach that call. Task 1 must route `handleCreateUser`, `handleUpdateUser` (line 566) and `AuthResource.handleUpdateProfile` (line 380) through `displayNameError`, and make the name **required on create** (Task 20's form and Task 22's fixture both send one). The SCIM create path needs the same.
2. **HTTP status for a rule violation is not fixed by the contract.** Today every name error from `AuthResource`/`AdminUserResource` is `400` (`sendError(…, SC_BAD_REQUEST, …)`); the spec says "`400`/`409`". Task 20's UI accepts either (a `409` is always shown under the field; a `400` only when the name was the sole change in the request) and Task 23's docs say "`400` or `409`". Please pin it in Task 1 (suggestion: `409` for "already taken", `400` for format, reserved and login clash) and then tighten the docs. `RestJson.sendError` cannot carry extra fields, so the UI has to infer the field; adding `"field":"fullName"` to those error bodies would let it stop guessing.
3. **`[{ALLOW edit X}]` on its own makes a page private to X.** `PagePermission` says `edit` implies `view`, and `AclImpl.findPrincipals(VIEW)` then lists only X, so `DefaultAuthorizationManager.decideByAcl` denies everyone else `view` (and `comment`). The brief's "appear in an ACL on a public page (`[{ALLOW edit leak-probe-…}]`)" would therefore produce a page the probes cannot even read, making every absence check vacuous. Task 22 uses `[{ALLOW view All}]` plus `[{ALLOW edit <login>}]` and puts comment threads on a different page. Tasks 17–19 and Section B/D fixtures that want a public page naming a user must do the same.
4. **A frontmatter `author:` typed by an editor is page text.** `?format=md` of such a page contains the login by design (spec "Out of scope"). Task 22's sweeps avoid it; the page that carries it is used only for `dct:creator`. Task 11's test should also use a frontmatter author that is a login (the projector reads `PageRecordBuilder` → `md.get("author")`).
5. **The brief's "`/api/me/mentions` (as the second user)"** only shows the probe's *display name* if the probe is the *mentioner* (`mentionedBy`). Task 22 arranges that: the probe mentions the second user; the second user mentions the probe back (which exercises the `mentions` map in comment threads).
6. **Deleted accounts.** `PublicIdentity.displayName` returns "any other value unchanged", so after an account is deleted its *non-email* login is shown to everyone as the author string, and an email login becomes "unknown contributor". The privacy policy and `AccountAndLogin.md` (Task 23) describe exactly that. If the owner would rather mask every orphaned login, Task 5 needs a rule for "stored value is a login-shaped string with no account".
7. **Rollback re-exposes logins.** Pages saved under 2.4.58 store the login as author; 2.4.57 displays stored authors as written. Task 24 says so in the rollback note.
8. **`POST /admin/content/rebuild-indexes` also re-embeds the corpus** (it deletes the model's rows first; `IndexRebuild.md`), so dense retrieval is degraded until the bootstrap finishes. The spec said "chunks + Lucene"; Task 23's CHANGELOG and Task 24 state the real behaviour.
9. **SCIM wording rests on two assumptions to confirm against Task 3:** `POST` without a usable `name.formatted` yields the placeholder `Member N` (the contract's `availableDisplayName` suggests it), and the SCIM `displayName` attribute stays the *wiki name* (today's behaviour, documented in `ScimProvisioning.md`). Adjust `docs/admin/ScimProvisioning.md` if Task 3 decides otherwise.
10. **The spec's rollout step 4 omits two production pages that would go stale:** `WikantikSingleSignOn` and `WikantikScimProvisioning` (production mirrors of the two admin guides; there is no wiki page for the comments guide). Task 24 updates them with `WikantikSecurityGuide`, `PrivacyPolicy`, `TextFormattingRules` and `LoginHelp`.
11. **Footer mismatch.** The contract's commit footer names "Claude Opus 5.5"; this session's attribution rule names "Claude Sonnet 5.5". Section E follows the contract; make all sections consistent with whichever the owner wants.
12. **`docs/wikantik-pages/*.md` mirrors are CRLF**, so unified diffs for them cannot be pasted safely; Task 23 uses a line-replacing script for `PrivacyPolicy.md` and `TextFormattingRules.md`. Any other task that edits those files should do the same.
13. **Privacy-policy text is legal-adjacent** and was written from the spec's behaviour; the owner should read the three changed paragraphs before the release is cut (Task 23 diff).

### Cross-section dependencies

- **Task 20** needs Task 1's rule messages only for wording parity (the UI works with any message) and Task 4 for `refresh()` to show the new name; it adds no API. It shares no file with Task 16 (`useMentionPicker`, `MentionPicker`, `CommentBody`, `CommentsDrawer`), so the two can run in parallel.
- **Task 21** is independent of all code tasks; Task 24 runs it on production (`PSQL_COMMAND='bin/remote.sh psql --'`).
- **Task 22** is the acceptance test for Tasks 5–19 and must run last among them; it reads Task 13's `userId`/`createdById`/`authorId`/`mentions`, Task 15's `{id, displayName}` shape, Task 9's `author` on `get_page` and `/tools/page`, and Task 11's `dct:creator`. Tasks 18–19 also need their own focused tests; this IT does not replace them.
- **Task 23** states the final behaviour of Tasks 1, 2, 3, 5, 13–16, 18 and 19; re-read its diffs after any of those changes its decisions. It is the only task that edits `CHANGELOG.md`, `CLAUDE.md`, `docs/user/CommentsAndMentions.md`, `docs/admin/Security.md`, `privacy-policy.html` and the two shipped help pages (keep other tasks out of them to avoid conflicts).
- **Task 24** needs Tasks 20–23 committed and Tasks 1–19 merged; its production steps (deploy, cleanup, rebuild, page pushes) are the owner's to approve.

---

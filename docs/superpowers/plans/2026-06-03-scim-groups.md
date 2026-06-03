# SCIM Groups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/scim/v2/Groups` so an IdP can sync group membership, completing the SCIM IdP story — without ever granting the Wikantik Admin role.

**Architecture:** Reuse the `wikantik-scim` module, bearer filter, `ScimError`, `ListResponse`, and `ScimFilterParser`. New `ScimGroupMapper` (Group↔SCIM JSON, member uid↔loginName resolution), `ScimGroupPatchApplier` (member add/remove/replace incl. `members[value eq "x"]` value-path), and `ScimGroupResource` servlet. Member changes recompute the member set and re-save via the audited, rollback-safe `GroupManager.parseGroup`+`setGroup` path. Hard delete; externalId dropped (key on displayName).

**Tech Stack:** Java 21, Servlet `HttpServlet`, Gson, JUnit 5 + Mockito, `GroupManager`/`UserDatabase`, `AuditService`, Cargo+REST IT harness.

**Spec:** `docs/superpowers/specs/2026-06-03-scim-groups-design.md`

**Verified API anchors:**
- `GroupManager` (get via `engine.getManager(com.wikantik.auth.authorize.GroupManager.class)`): `Group getGroup(String) throws NoSuchPrincipalException`, `Group parseGroup(String name, String memberLine, boolean create) throws WikiSecurityException` (memberLine = **newline-separated** member names), `void setGroup(Session, Group) throws WikiException`, `void removeGroup(String) throws WikiSecurityException`.
- `Group`: `String getName()`, `Principal[] members()`.
- System session for setGroup/removeGroup: `com.wikantik.WikiSession.getWikiSession( engine, null )` returns a guest session.
- `UserDatabase` (via `engine.getManager(UserManager.class).getUserDatabase()`): `UserProfile findByUid(String) throws NoSuchPrincipalException`, `UserProfile findByLoginName(String) throws NoSuchPrincipalException`; `UserProfile.getUid()`, `getLoginName()`.
- Engine + audit in the servlet: mirror `ScimUserResource` — `engine = Wiki.engine().find(config)` in `init`; `getAuditService()` = `engine instanceof WikiEngine we ? we.getAuditService() : null`.
- Audit event: `AuditEntry.builder().eventTime(Instant.now()).category(ADMIN).eventType("scim.group.create"|"…update"|"…delete").outcome(SUCCESS).actorPrincipal("scim").actorType("system").targetType("group").targetId(name).targetLabel(name).build()`, guarded in try/catch.

**No DB migration** (groups use the existing `group_members`; externalId not persisted).

**Build/verify note:** long builds need a subagent or two-halves split (see memory `reference_full_it_reactor_execution`).

---

## Task 1: `ScimGroupMapper`

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimGroupMapper.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimGroupMapperTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimGroupMapperTest {

    private Principal principal( String name ) {
        Principal p = mock( Principal.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    @Test
    void toScimEmitsIdDisplayNameAndMembersWithUid() {
        com.wikantik.auth.authorize.Group g = mock( com.wikantik.auth.authorize.Group.class );
        when( g.getName() ).thenReturn( "Engineers" );
        when( g.members() ).thenReturn( new Principal[]{ principal( "alice" ), principal( "bob" ) } );
        Map<String,String> loginToUid = Map.of( "alice", "u-1", "bob", "u-2" );

        JsonObject o = ScimGroupMapper.toScim( g, "https://h/scim/v2/Users", "https://h/scim/v2/Groups",
                loginToUid::get );

        assertTrue( o.getAsJsonArray( "schemas" ).toString().contains(
                "urn:ietf:params:scim:schemas:core:2.0:Group" ) );
        assertEquals( "Engineers", o.get( "id" ).getAsString() );
        assertEquals( "Engineers", o.get( "displayName" ).getAsString() );
        assertEquals( 2, o.getAsJsonArray( "members" ).size() );
        JsonObject m0 = o.getAsJsonArray( "members" ).get( 0 ).getAsJsonObject();
        assertEquals( "u-1", m0.get( "value" ).getAsString() );
        assertEquals( "alice", m0.get( "display" ).getAsString() );
        assertTrue( m0.get( "$ref" ).getAsString().endsWith( "/u-1" ) );
        assertTrue( o.getAsJsonObject( "meta" ).get( "location" ).getAsString().endsWith( "/Engineers" ) );
    }

    @Test
    void toScimSkipsUnresolvableMembers() {
        com.wikantik.auth.authorize.Group g = mock( com.wikantik.auth.authorize.Group.class );
        when( g.getName() ).thenReturn( "G" );
        when( g.members() ).thenReturn( new Principal[]{ principal( "ghost" ) } );
        JsonObject o = ScimGroupMapper.toScim( g, "u", "grp", login -> null );
        assertEquals( 0, o.getAsJsonArray( "members" ).size() );
    }

    @Test
    void readMemberUidsAndDisplayName() {
        JsonObject in = JsonParser.parseString(
            "{\"displayName\":\"Engineers\",\"members\":[{\"value\":\"u-1\"},{\"value\":\"u-2\"}]}" )
            .getAsJsonObject();
        assertEquals( "Engineers", ScimGroupMapper.readDisplayName( in ) );
        assertEquals( List.of( "u-1", "u-2" ), ScimGroupMapper.readMemberUids( in ) );
    }

    @Test
    void nestedGroupMemberRejected() {
        JsonObject in = JsonParser.parseString(
            "{\"displayName\":\"X\",\"members\":[{\"value\":\"g-9\",\"type\":\"Group\"}]}" )
            .getAsJsonObject();
        assertThrows( ScimGroupMapper.NestedGroupUnsupportedException.class,
                () -> ScimGroupMapper.readMemberUids( in ) );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl wikantik-scim -am test-compile`
Expected: FAIL — `ScimGroupMapper` does not exist. (Confirm `Group.members()` returns `Principal[]` and `Group.getName()` exist — they do.)

- [ ] **Step 3: Write `ScimGroupMapper`**

```java
package com.wikantik.scim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.auth.authorize.Group;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Maps a {@link Group} to/from the SCIM 2.0 core Group schema (Gson). */
public final class ScimGroupMapper {

    public static final String SCHEMA_GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private ScimGroupMapper() {}

    /** Group → SCIM Group JSON. {@code loginToUid} resolves a member loginName to its
     *  SCIM uid; members that do not resolve are omitted from the SCIM view. */
    public static JsonObject toScim( final Group group, final String usersBaseUrl,
                                     final String groupsBaseUrl, final Function<String, String> loginToUid ) {
        final JsonObject o = new JsonObject();
        final JsonArray schemas = new JsonArray();
        schemas.add( SCHEMA_GROUP );
        o.add( "schemas", schemas );
        o.addProperty( "id", group.getName() );
        o.addProperty( "displayName", group.getName() );
        final JsonArray members = new JsonArray();
        for ( final Principal p : group.members() ) {
            final String login = p.getName();
            final String uid = loginToUid.apply( login );
            if ( uid == null ) continue;
            final JsonObject m = new JsonObject();
            m.addProperty( "value", uid );
            m.addProperty( "display", login );
            m.addProperty( "$ref", usersBaseUrl + "/" + uid );
            members.add( m );
        }
        o.add( "members", members );
        final JsonObject meta = new JsonObject();
        meta.addProperty( "resourceType", "Group" );
        meta.addProperty( "location", groupsBaseUrl + "/" + group.getName() );
        o.add( "meta", meta );
        return o;
    }

    public static String readDisplayName( final JsonObject in ) {
        return ( in.has( "displayName" ) && !in.get( "displayName" ).isJsonNull() )
                ? in.get( "displayName" ).getAsString() : null;
    }

    /** Member uids referenced in an inbound SCIM Group body. Rejects Group-typed
     *  members (nested groups — out of scope). */
    public static List<String> readMemberUids( final JsonObject in ) {
        final List<String> uids = new ArrayList<>();
        if ( in.has( "members" ) && in.get( "members" ).isJsonArray() ) {
            for ( final JsonElement el : in.getAsJsonArray( "members" ) ) {
                final JsonObject m = el.getAsJsonObject();
                if ( m.has( "type" ) && "Group".equalsIgnoreCase( m.get( "type" ).getAsString() ) ) {
                    throw new NestedGroupUnsupportedException( "Group-typed members are not supported" );
                }
                if ( m.has( "value" ) ) uids.add( m.get( "value" ).getAsString() );
            }
        }
        return uids;
    }

    public static final class NestedGroupUnsupportedException extends RuntimeException {
        public NestedGroupUnsupportedException( final String m ) { super( m ); }
    }
}
```

Add the ASF license header (copy from another `wikantik-scim` source).

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl wikantik-scim -am test -Dtest=ScimGroupMapperTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimGroupMapper.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimGroupMapperTest.java
git commit -m "feat(scim): Group<->SCIM Group mapper"
```

---

## Task 2: `ScimGroupPatchApplier`

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimGroupPatchApplier.java`
- Test: `wikantik-scim/src/test/java/com/wikantik/scim/ScimGroupPatchApplierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ScimGroupPatchApplierTest {

    private JsonObject patch( String ops ) {
        return JsonParser.parseString(
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + ops + "}"
        ).getAsJsonObject();
    }

    @Test
    void addMembers() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1" ),
            patch( "[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"u-2\"}]}]" ) );
        assertEquals( Set.of( "u-1", "u-2" ), r );
    }

    @Test
    void removeByValuePath() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1", "u-2" ),
            patch( "[{\"op\":\"remove\",\"path\":\"members[value eq \\\"u-1\\\"]\"}]" ) );
        assertEquals( Set.of( "u-2" ), r );
    }

    @Test
    void replaceAll() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1", "u-2" ),
            patch( "[{\"op\":\"replace\",\"path\":\"members\",\"value\":[{\"value\":\"u-9\"}]}]" ) );
        assertEquals( Set.of( "u-9" ), r );
    }

    @Test
    void removeAllMembers() {
        Set<String> r = ScimGroupPatchApplier.apply( List.of( "u-1", "u-2" ),
            patch( "[{\"op\":\"remove\",\"path\":\"members\"}]" ) );
        assertTrue( r.isEmpty() );
    }

    @Test
    void entraPathlessValueObject() {
        // Entra: {op:add, value:{members:[{value:..}]}}
        Set<String> r = ScimGroupPatchApplier.apply( List.of(),
            patch( "[{\"op\":\"add\",\"value\":{\"members\":[{\"value\":\"u-5\"}]}}]" ) );
        assertEquals( Set.of( "u-5" ), r );
    }

    @Test
    void nonMembersValuePathRejected() {
        assertThrows( ScimGroupPatchApplier.UnsupportedGroupPatchException.class,
            () -> ScimGroupPatchApplier.apply( List.of(),
                patch( "[{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"x\"}]" ) ) );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q -pl wikantik-scim -am test-compile`
Expected: FAIL — `ScimGroupPatchApplier` does not exist.

- [ ] **Step 3: Write `ScimGroupPatchApplier`**

```java
package com.wikantik.scim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets a SCIM Group PatchOp's member operations (RFC 7644 §3.5.2), supporting
 * the subset IdPs send: add / remove / replace on {@code members}, including the
 * {@code members[value eq "<uid>"]} value-path remove and Entra's path-less
 * {@code value} object. Returns the resulting member-uid set.
 */
public final class ScimGroupPatchApplier {

    public static final class UnsupportedGroupPatchException extends RuntimeException {
        public UnsupportedGroupPatchException( final String m ) { super( m ); }
    }

    private static final Pattern MEMBER_VALUE_PATH =
            Pattern.compile( "members\\[\\s*value\\s+eq\\s+\"([^\"]+)\"\\s*\\]" );

    private ScimGroupPatchApplier() {}

    public static LinkedHashSet<String> apply( final Collection<String> currentMemberUids,
                                               final JsonObject patchOp ) {
        final LinkedHashSet<String> members = new LinkedHashSet<>( currentMemberUids );
        if ( !patchOp.has( "Operations" ) || !patchOp.get( "Operations" ).isJsonArray() ) {
            throw new UnsupportedGroupPatchException( "PatchOp missing Operations array" );
        }
        for ( final JsonElement opEl : patchOp.getAsJsonArray( "Operations" ) ) {
            final JsonObject op = opEl.getAsJsonObject();
            final String operation = op.has( "op" ) ? op.get( "op" ).getAsString().toLowerCase() : "";
            final String path = ( op.has( "path" ) && !op.get( "path" ).isJsonNull() )
                    ? op.get( "path" ).getAsString() : null;
            switch ( operation ) {
                case "add" -> members.addAll( extractMemberValues( op, path ) );
                case "replace" -> {
                    if ( path == null || "members".equals( path ) ) {
                        members.clear();
                        members.addAll( extractMemberValues( op, path ) );
                    } else {
                        throw new UnsupportedGroupPatchException( "replace path not supported: " + path );
                    }
                }
                case "remove" -> {
                    if ( "members".equals( path ) ) {
                        members.clear();
                    } else if ( path != null ) {
                        final Matcher mt = MEMBER_VALUE_PATH.matcher( path );
                        if ( mt.matches() ) members.remove( mt.group( 1 ) );
                        else throw new UnsupportedGroupPatchException( "remove path not supported: " + path );
                    } else {
                        throw new UnsupportedGroupPatchException( "remove requires a path" );
                    }
                }
                default -> throw new UnsupportedGroupPatchException( "Unsupported op: " + operation );
            }
        }
        return members;
    }

    /** Member uids from an op's value: an array of {value:…}, or a path-less value
     *  object carrying {members:[…]} (Entra), or a bare {value:…}. */
    private static List<String> extractMemberValues( final JsonObject op, final String path ) {
        final List<String> out = new ArrayList<>();
        final JsonElement value = op.get( "value" );
        if ( value == null || value.isJsonNull() ) return out;
        if ( value.isJsonArray() ) {
            for ( final JsonElement el : value.getAsJsonArray() ) out.add( memberValue( el ) );
        } else if ( value.isJsonObject() ) {
            final JsonObject vo = value.getAsJsonObject();
            if ( vo.has( "members" ) && vo.get( "members" ).isJsonArray() ) {
                for ( final JsonElement el : vo.getAsJsonArray( "members" ) ) out.add( memberValue( el ) );
            } else if ( vo.has( "value" ) ) {
                out.add( vo.get( "value" ).getAsString() );
            } else {
                throw new UnsupportedGroupPatchException( "Unsupported value object for path: " + path );
            }
        }
        return out;
    }

    private static String memberValue( final JsonElement el ) {
        if ( el.isJsonObject() && el.getAsJsonObject().has( "value" ) ) {
            return el.getAsJsonObject().get( "value" ).getAsString();
        }
        return el.getAsString();
    }
}
```

Add the ASF license header.

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl wikantik-scim -am test -Dtest=ScimGroupPatchApplierTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimGroupPatchApplier.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimGroupPatchApplierTest.java
git commit -m "feat(scim): Group membership PATCH applier (value-path + Entra idioms)"
```

---

## Task 3: `ScimGroupResource` + discovery + web.xml

**Files:**
- Create: `wikantik-scim/src/main/java/com/wikantik/scim/ScimGroupResource.java` (HttpServlet)
- Modify: `wikantik-scim/src/main/java/com/wikantik/scim/ScimDiscoveryResource.java` (add Group resource-type + schema)
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (register `ScimGroupResource` on `/scim/v2/Groups` + `/scim/v2/Groups/*` — the existing `ScimAccessFilter` on `/scim/v2/*` already covers it)

Read `ScimUserResource.java` first and mirror its structure: lazy `engine` in `init`, the `service()` override for PATCH, the `application/scim+json` responses, the `ScimError` usage, the audit helper, and the `usersBaseUrl`-from-request pattern.

- [ ] **Step 1: Implement `ScimGroupResource`**

Resolve managers from the engine: `GroupManager gm = we.getManager(GroupManager.class)`, `UserDatabase db = we.getManager(UserManager.class).getUserDatabase()`, `AuditService audit = we.getAuditService()`. System session for writes: `Session sysSession = com.wikantik.WikiSession.getWikiSession( engine, null )`.

Helpers:
- `uidToLogin(uid)` → `db.findByUid(uid).getLoginName()`; on `NoSuchPrincipalException` → throw a 400-mapped `invalidValue`.
- `loginToUid(login)` → `db.findByLoginName(login).getUid()`; unresolved → null (mapper skips it).
- `saveGroupWithMembers(name, memberUids)`: map each uid→login (fail 400 on any unresolved), build a newline-separated member line, `Group g = gm.parseGroup(name, memberLine, true)`, `gm.setGroup(sysSession, g)`.

Dispatch (path-info after `/Groups`):
- `POST /Groups`: read `displayName` (required → 400 if missing) + member uids (`ScimGroupMapper.readMemberUids`, may throw `NestedGroupUnsupportedException` → 400 `invalidValue`). If `gm.getGroup(displayName)` succeeds (group exists) → `409 uniqueness`; else `saveGroupWithMembers(displayName, uids)`, audit `scim.group.create`, respond `201` + `Location` + `ScimGroupMapper.toScim(gm.getGroup(displayName), usersBaseUrl, groupsBaseUrl, this::loginToUid)`.
- `GET /Groups/{name}`: `gm.getGroup(name)` → 200 + toScim / `NoSuchPrincipalException` → 404.
- `GET /Groups?filter=&startIndex=&count=`: `ScimFilterParser.parse`; if a `displayName eq` filter → look up that one group (or empty); no filter → list all groups via `gm.getRoles()` (returns `Principal[]` of group principals — despite the name; this is exactly what `AdminGroupResource.handleListGroups` uses) then `gm.getGroup(p.getName())` per principal. Wrap in a `ListResponse` (`schemas:["urn:ietf:params:scim:api:messages:2.0:ListResponse"]`, `totalResults`, `startIndex`, `itemsPerPage`, `Resources`). Unsupported filter → 400 `invalidFilter`. NOTE: `ScimFilterParser` currently only allows `userName`/`externalId`; extend it to also allow `displayName` (add `displayName` to its supported set) OR parse the displayName filter locally in this resource. Prefer extending `ScimFilterParser`'s supported set to include `displayName` (one-line change) and add a parser test for it.
- `PUT /Groups/{name}`: replace — read displayName + member uids, `saveGroupWithMembers(name, uids)`, audit `scim.group.update`, 200 + toScim.
- `PATCH /Groups/{name}` (via `service()` override): read current members = uids of `gm.getGroup(name).members()` mapped login→uid; `Set<String> newUids = ScimGroupPatchApplier.apply(currentUids, body)`; `saveGroupWithMembers(name, newUids)`; audit `scim.group.update`; 200 + toScim. `UnsupportedGroupPatchException` → 400 `invalidPath`.
- `DELETE /Groups/{name}`: `gm.removeGroup(name)`; audit `scim.group.delete`; `204`. `NoSuchPrincipalException` → 404.

**Admin-role invariant:** this resource MUST only ever call `GroupManager`/`UserDatabase` — never any role-management API. Do not import or call anything that writes the `roles` table. (The IT asserts the invariant end-to-end.)

All audit `record(...)` calls wrapped in try/catch (`LOG.warn`) so auditing never breaks the SCIM op. Never swallow other exceptions silently.

- [ ] **Step 2: Extend `ScimDiscoveryResource`**

Add to `/ResourceTypes` a `Group` resource-type entry, and to `/Schemas` the `urn:ietf:params:scim:schemas:core:2.0:Group` schema (minimal valid descriptor: `displayName`, `members`). Static JSON is fine; mirror the existing User entries.

- [ ] **Step 3: Register in web.xml**

Add `<servlet>` + `<servlet-mapping>` for `com.wikantik.scim.ScimGroupResource` on `/scim/v2/Groups` and `/scim/v2/Groups/*`, mirroring the existing `ScimUserResource` blocks. The `/scim/v2/*` filter already covers these paths — do not add a new filter.

- [ ] **Step 4: Compile + the filter-parser test (if extended)**

Run: `mvn -q -pl wikantik-scim -am test -Dtest=ScimFilterParserTest` (add a `displayName eq` case if you extended it) then `mvn -q -pl wikantik-scim -am compile`
Expected: BUILD SUCCESS. (Resource behavior is covered by the Task 4 IT.)

- [ ] **Step 5: Commit**

```bash
git add wikantik-scim/src/main/java/com/wikantik/scim/ScimGroupResource.java \
        wikantik-scim/src/main/java/com/wikantik/scim/ScimDiscoveryResource.java \
        wikantik-scim/src/main/java/com/wikantik/scim/ScimFilterParser.java \
        wikantik-scim/src/test/java/com/wikantik/scim/ScimFilterParserTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(scim): Groups resource (CRUD, membership PATCH, hard delete) + discovery + web.xml"
```

---

## Task 4: Integration test — full Group lifecycle + admin-role invariant

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimGroupsIT.java`

Mirror `ScimUsersIT.java` for the HttpClient + bearer (`Authorization: Bearer it-scim-token`, already wired into the Cargo JVM) + admin-login (`janne`/`myP@5sw0rd`) harness. Content type `application/scim+json`.

- [ ] **Step 1: Write the IT**

Steps:
1. Auth: a `/scim/v2/Groups` request with no/bad token → `401`.
2. Provision two users via `POST /scim/v2/Users` (reuse the SCIM Users path); capture their `id`s (uids).
3. `POST /scim/v2/Groups` `{displayName:"ITEngineers", members:[{value:<uid1>}]}` → `201`; body `id`/`displayName` = `ITEngineers`, members has uid1.
4. `GET /Groups/ITEngineers` → 200, member uid1 present.
5. `PATCH /Groups/ITEngineers` add uid2 (`{op:add,path:members,value:[{value:<uid2>}]}`) → 200; `GET` shows both.
6. `PATCH` remove uid1 via value-path (`{op:remove,path:"members[value eq \"<uid1>\"]"}`) → 200; `GET` shows only uid2.
7. `PUT /Groups/ITEngineers` replace members with `[{value:<uid1>}]` → 200; `GET` shows only uid1.
8. `GET /Groups?filter=displayName eq "ITEngineers"` → 1 result.
9. **Admin-role invariant:** `POST /scim/v2/Groups` `{displayName:"Admin",members:[{value:<uid1>}]}` (or "ScimAdminTest" if "Admin" is a reserved name → then assert the 400/409 reserved-name rejection instead). Then assert the member user did NOT gain admin: an admin-only endpoint (e.g. `GET /admin/users` with that user's credentials) is forbidden, OR query that no role row was created. Document which check you used. The point: SCIM group membership grants no role.
10. `DELETE /Groups/ITEngineers` → `204`; subsequent `GET /Groups/ITEngineers` → `404`.
11. Assert (admin-authed `GET /admin/audit?eventType=scim.group.create`) a `scim.group.*` audit row exists.

Use unique group/user names (suffix a marker) if the IT DB persists across runs.

- [ ] **Step 2: Run the IT module**

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected: BUILD SUCCESS; `ScimGroupsIT` green (and `ScimUsersIT`/`AuditLogIT` still green). Long — re-run if a background run is wall-killed; standard zombie-cleanup if `clean` fails (never kill `java -jar app.jar` or a `tomcat/tomcat-11` process).

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimGroupsIT.java
git commit -m "test(scim): wire-level Group lifecycle IT + admin-role invariant"
```

---

## Task 5: Docs + final build

**Files:**
- Modify: `docs/wikantik-pages/ScimProvisioningDesign.md` (move Groups from "deferred fast-follow" to shipped — add a short Groups section / update scope + open items)
- Modify: `CHANGELOG.md` (Unreleased → Added: SCIM Groups)
- Modify: `CLAUDE.md` (agent-surface `/scim/v2/*` row → note Users **and** Groups)

- [ ] **Step 1: Update docs**

In `ScimProvisioningDesign.md`, update the "Out of scope (deferred)" Groups bullet and "Open items" to reflect Groups shipped (membership sync, hard delete, externalId-by-displayName, admin-role invariant). Add a CHANGELOG "Added" entry: `feat: SCIM 2.0 Groups (/scim/v2/Groups) — IdP-driven membership sync; never grants the Admin role`. Update the CLAUDE.md `/scim/v2/*` row to mention `Users` + `Groups`.

- [ ] **Step 2: Full unit build**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS (all modules).

- [ ] **Step 3: IT gate (rest module carries the SCIM ITs)**

Run (via subagent if it exceeds the tool budget): `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-it-test-rest -am`
Expected: BUILD SUCCESS; `ScimGroupsIT` + `ScimUsersIT` + `AuditLogIT` green.

- [ ] **Step 4: Commit**

```bash
git add docs/wikantik-pages/ScimProvisioningDesign.md CHANGELOG.md CLAUDE.md
git commit -m "docs(scim): Groups shipped — changelog, agent-surface, design update"
```

---

## Self-review

- **Spec coverage:** mapper (id=name, members uid↔login, nested-group reject) → T1; member PATCH subset incl. value-path + Entra idiom → T2; resource CRUD + member-rebuild-via-setGroup + hard delete + 409 dedup + displayName filter + discovery + web.xml + audit `scim.group.*` → T3; full lifecycle IT + admin-role invariant + audit assertion → T4; docs → T5. Out-of-scope (externalId persistence, nested groups, role mapping) correctly absent / rejected.
- **Placeholder scan:** T1/T2 full code; T3/T4 give exact endpoints/behaviors + the concrete `GroupManager`/`UserDatabase`/session APIs to call (incl. the verified `gm.getRoles()` list method) and the file to mirror (`ScimUserResource`), not hand-waves. No TODO/TBD.
- **Type consistency:** `ScimGroupMapper.toScim(group, usersBaseUrl, groupsBaseUrl, loginToUid)` / `readMemberUids` / `readDisplayName` / `NestedGroupUnsupportedException`; `ScimGroupPatchApplier.apply(currentUids, patchOp) → LinkedHashSet<String>` / `UnsupportedGroupPatchException`; `saveGroupWithMembers(name, uids)`; audit types `scim.group.create|update|delete`; `gm.parseGroup/setGroup/removeGroup/getGroup` signatures match the verified anchors. `ScimFilterParser` extended to accept `displayName` (T3) with a test.

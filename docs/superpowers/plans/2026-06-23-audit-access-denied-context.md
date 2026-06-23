# Audit `access.denied` Context + Speculative-Check Suppression — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop speculative permission checks from writing audit rows, and enrich the remaining enforced `access.denied` records with a deny reason + auth status + roles, surfaced through a clickable record-detail modal.

**Architecture:** Split the authorization decision (`DefaultAuthorizationManager.decide`) from event firing: `checkPermission` fires events (audited); a new pure `isPermitted` fires nothing. Speculative callers move to `isPermitted`/`canAccessQuietly`. Deny events carry a `{reason, authStatus, roles}` attribute map (new optional field on `WikiSecurityEvent`) that `AuditEventListener` merges into the existing `detail` JSON — **no DB migration**. The frontend gains a record-detail modal rendering every already-serialized field.

**Tech Stack:** Java 21, Maven (multi-module reactor), JUnit 5 + Mockito, Gson, React + Vite + Vitest, Testing Library.

## Global Constraints

- **No database migration / no new audit columns.** Enrichment rides in the existing `detail` JSON (already part of `AuditEntry.canonical()` and already returned by `AdminAuditResource.toJson`). Old rows must still verify against the hash chain.
- **Fail safe:** when classifying a call site, only an enforcement→speculative mistake loses audit coverage. Enforcement sites (deny → 403/redirect/404/blocked action) keep `checkPermission`/`canAccess`. Speculative sites (filter/visibility/capability-hint) use `isPermitted`/`canAccessQuietly`.
- **Never swallow exceptions with empty catch blocks** — log at least `LOG.warn()` with context (project rule).
- Build single modules with `mvn ... -pl <module> -am -q`; do **not** use `-T 1C` for `wikantik-main` (known parallel flakiness). One full `mvn clean install -DskipITs` + one sequential IT run (`-Pintegration-tests -fae`) at the end.
- `WikiSecurityEvent` changes must be **additive** — existing constructor signatures stay valid.

---

### Task 1: `WikiSecurityEvent` optional attributes map

**Files:**
- Modify: `wikantik-event/src/main/java/com/wikantik/event/WikiSecurityEvent.java`
- Test: `wikantik-event/src/test/java/com/wikantik/event/WikiSecurityEventTest.java`

**Interfaces:**
- Produces: `WikiSecurityEvent(Object src, int type, Principal principal, Object target, Map<String,String> attributes)` constructor and `Map<String,String> getAttributes()` (never null; empty map when unset).

- [ ] **Step 1: Write the failing test** — append to `WikiSecurityEventTest`:

```java
    @org.junit.jupiter.api.Test
    void attributesAreCarriedAndDefaultToEmpty() {
        final java.security.Principal alice = () -> "alice";
        final WikiSecurityEvent withAttrs = new WikiSecurityEvent(
            this, WikiSecurityEvent.ACCESS_DENIED, alice, "target",
            java.util.Map.of( "reason", "acl-denied", "authStatus", "authenticated" ) );
        org.junit.jupiter.api.Assertions.assertEquals( "acl-denied", withAttrs.getAttributes().get( "reason" ) );
        org.junit.jupiter.api.Assertions.assertEquals( "authenticated", withAttrs.getAttributes().get( "authStatus" ) );

        // The pre-existing 4-arg constructor yields an empty (non-null) attribute map.
        final WikiSecurityEvent noAttrs = new WikiSecurityEvent(
            this, WikiSecurityEvent.LOGOUT, alice, null );
        org.junit.jupiter.api.Assertions.assertTrue( noAttrs.getAttributes().isEmpty() );
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-event -Dtest=WikiSecurityEventTest#attributesAreCarriedAndDefaultToEmpty -q`
Expected: COMPILE FAILURE — `getAttributes()` / 5-arg constructor not defined.

- [ ] **Step 3: Implement** — in `WikiSecurityEvent.java`, add the field after `private final Object target;`:

```java
    private final transient java.util.Map<String, String> attributes;
```

Replace the existing 4-arg constructor body so it delegates to a new canonical 5-arg constructor (preserves the logging side effect exactly once):

```java
    public WikiSecurityEvent( final Object src, final int type, final Principal principal, final Object target ) {
        this( src, type, principal, target, java.util.Map.of() );
    }

    /**
     * Canonical constructor. {@code attributes} carries optional structured metadata
     * (e.g. an access-denial {@code reason}/{@code authStatus}/{@code roles}); a null
     * map is normalised to empty. Defensively copied to an immutable map.
     */
    public WikiSecurityEvent( final Object src, final int type, final Principal principal,
                              final Object target, final java.util.Map<String, String> attributes ) {
        super( src, type );
        if( src == null ) {
            throw new IllegalArgumentException( "Argument(s) cannot be null." );
        }
        this.principal = principal;
        this.target = target;
        this.attributes = ( attributes == null ) ? java.util.Map.of() : java.util.Map.copyOf( attributes );
        if( LOG.isEnabled( Level.ERROR ) && ArrayUtils.contains( ERROR_EVENTS, type ) ) {
            LOG.error( this );
        } else if( LOG.isEnabled( Level.WARN ) && ArrayUtils.contains( WARN_EVENTS, type ) ) {
            LOG.warn( this );
        } else if( LOG.isEnabled( Level.INFO ) && ArrayUtils.contains( INFO_EVENTS, type ) ) {
            LOG.info( this );
        }
        LOG.debug( this );
    }
```

Add the getter near `getTarget()`:

```java
    /**
     * Returns optional structured event metadata (never {@code null}; empty when unset).
     * Used by the audit listener to enrich {@code access.denied} records.
     *
     * @return an immutable attribute map
     */
    public java.util.Map<String, String> getAttributes() {
        return attributes;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-event -Dtest=WikiSecurityEventTest -q`
Expected: PASS (all `WikiSecurityEventTest` methods green).

- [ ] **Step 5: Install the module so downstream modules see the new API**

Run: `mvn install -pl wikantik-event -DskipTests -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-event/src/main/java/com/wikantik/event/WikiSecurityEvent.java wikantik-event/src/test/java/com/wikantik/event/WikiSecurityEventTest.java
git commit -m "feat(event): WikiSecurityEvent optional attributes map

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `AuditEventListener` merges deny attributes into `detail`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/audit/AuditEventListener.java`
- Test: `wikantik-main/src/test/java/com/wikantik/audit/AuditEventListenerTest.java`

**Interfaces:**
- Consumes: `WikiSecurityEvent.getAttributes()` (Task 1).
- Produces: for `ACCESS_DENIED`, a `detail` JSON object containing `permission` (when target is a `Permission`), `uri`/`method` (when on a request thread), and every entry of the event's attribute map (`reason`, `authStatus`, `roles`). Returns `null` detail only when nothing is available (preserves the bare-event behaviour).

- [ ] **Step 1: Write the failing test** — append to `AuditEventListenerTest`:

```java
    @Test
    void accessDeniedMergesAttributesIntoDetail() {
        final PagePermission perm = new PagePermission( "*:SecretPage", "edit" );
        listener.actionPerformed( new WikiSecurityEvent(
            this, WikiSecurityEvent.ACCESS_DENIED, named( "alice" ), perm,
            new java.util.LinkedHashMap<>( java.util.Map.of(
                "reason", "acl-denied", "authStatus", "authenticated", "roles", "Authenticated,All" ) ) ) );

        final AuditEntry e = onlyEntry();
        assertTrue( e.detail().contains( "\"permission\":\"*:SecretPage\"" ), e.detail() );
        assertTrue( e.detail().contains( "\"reason\":\"acl-denied\"" ), e.detail() );
        assertTrue( e.detail().contains( "\"authStatus\":\"authenticated\"" ), e.detail() );
        assertTrue( e.detail().contains( "\"roles\":\"Authenticated,All\"" ), e.detail() );
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -am -Dtest=AuditEventListenerTest#accessDeniedMergesAttributesIntoDetail -q`
Expected: FAIL — `detail` lacks the `reason`/`authStatus`/`roles` keys (attributes are ignored today).

- [ ] **Step 3: Implement** — in `AuditEventListener.java`:

In `mapSecurity`, replace the `ACCESS_DENIED` block:

```java
        if ( se.getType() == WikiSecurityEvent.ACCESS_DENIED ) {
            applyPermissionTarget( b, se.getTarget() );
            final String permName = ( se.getTarget() instanceof Permission p ) ? p.getName() : null;
            final String detail = deniedDetail( permName, se.getAttributes() );
            if ( detail != null ) b.detail( detail );
        }
```

In `applyPermissionTarget`, delete the trailing `.detail( deniedDetail( perm.getName() ) )` so it only sets target columns. The final lines of that method become:

```java
        b.targetType( type ).targetId( id ).targetLabel( label );
    }
```

Replace `deniedDetail(String)` with an attribute-aware builder plus a small append helper:

```java
    /**
     * Builds the access.denied detail JSON from the (optional) permission name, the
     * request-thread uri/method, and every entry of the event attribute map
     * ({@code reason}/{@code authStatus}/{@code roles}). Returns {@code null} when
     * nothing is available (e.g. an off-thread, attribute-less, null-permission deny).
     */
    private static String deniedDetail( final String permissionName, final Map< String, String > attributes ) {
        final StringBuilder sb = new StringBuilder( "{" );
        boolean first = true;
        if ( permissionName != null ) first = appendField( sb, first, "permission", permissionName );
        final String uri = AuditRequestContext.uri();
        final String method = AuditRequestContext.method();
        if ( uri != null )    first = appendField( sb, first, "uri", uri );
        if ( method != null ) first = appendField( sb, first, "method", method );
        if ( attributes != null ) {
            for ( final Map.Entry< String, String > en : attributes.entrySet() ) {
                if ( en.getValue() != null ) first = appendField( sb, first, en.getKey(), en.getValue() );
            }
        }
        if ( first ) return null;
        return sb.append( '}' ).toString();
    }

    /** Appends a JSON {@code "key":"value"} pair, prefixing a comma when not the first field. */
    private static boolean appendField( final StringBuilder sb, final boolean first,
                                        final String key, final String value ) {
        if ( !first ) sb.append( ',' );
        sb.append( '"' ).append( escape( key ) ).append( "\":\"" ).append( escape( value ) ).append( '"' );
        return false;
    }
```

- [ ] **Step 4: Run the full listener test to verify pass + no regression**

Run: `mvn test -pl wikantik-main -am -Dtest=AuditEventListenerTest -q`
Expected: PASS — including the pre-existing `accessDeniedWithPagePermissionMapsPageTarget`, `accessDeniedWithAllPermissionMapsAllTarget`, and `accessDeniedWithNullPermissionDoesNotThrowAndStillRecords` (bare event → empty attrs + null target → `detail` stays null).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/audit/AuditEventListener.java wikantik-main/src/test/java/com/wikantik/audit/AuditEventListenerTest.java
git commit -m "feat(audit): merge access.denied event attributes into detail JSON

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `decide()` + `isPermitted()` + enriched deny event

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/AuthorizationManager.java` (add `isPermitted` to interface + a 4-attr `fireEvent` default overload)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/AuthorizationManagerTest.java`

**Interfaces:**
- Consumes: `WikiSecurityEvent(..., attributes)` (Task 1).
- Produces: `boolean AuthorizationManager.isPermitted(Session session, Permission permission)` — same allow/deny result as `checkPermission` but fires **no** `WikiSecurityEvent`. `checkPermission` deny now fires `ACCESS_DENIED` carrying `{reason, authStatus, roles}`.

- [ ] **Step 0: Confirm the interface has a single implementation**

Run: `grep -rn "implements AuthorizationManager" --include=*.java wikantik-main/src/main`
Expected: only `DefaultAuthorizationManager`. (If others appear, each needs `isPermitted` — implement it the same way: `return decide(...).allowed();`.)

- [ ] **Step 1: Write the failing tests** — append to `AuthorizationManagerTest`:

```java
    private java.util.List<com.wikantik.event.WikiSecurityEvent> captureEvents() {
        final java.util.List<com.wikantik.event.WikiSecurityEvent> events = new java.util.ArrayList<>();
        m_auth.addWikiEventListener( e -> {
            if ( e instanceof com.wikantik.event.WikiSecurityEvent se ) events.add( se );
        } );
        return events;
    }

    @Test
    public void isPermittedMatchesCheckPermissionButFiresNoEvents() throws Exception {
        m_engine.saveText( "PlainPage", "Foo" );
        final Permission edit = PermissionFactory.getPagePermission( "*:PlainPage", "edit" );
        final Session anon = WikiSessionTest.anonymousSession( m_engine );

        final java.util.List<com.wikantik.event.WikiSecurityEvent> events = captureEvents();

        // Same boolean as checkPermission (anonymous edit is denied by the default policy).
        final boolean enforced = m_auth.checkPermission( anon, edit );
        Assertions.assertEquals( enforced, m_auth.isPermitted( anon, edit ), "isPermitted must match checkPermission" );

        // checkPermission fired exactly one ACCESS_DENIED; isPermitted fired nothing.
        final long denied = events.stream()
            .filter( e -> e.getType() == com.wikantik.event.WikiSecurityEvent.ACCESS_DENIED ).count();
        Assertions.assertEquals( 1, denied, "exactly one ACCESS_DENIED from the single checkPermission call" );
    }

    @Test
    public void deniedEventCarriesReasonStatusAndRoles() throws Exception {
        m_engine.saveText( "PlainPage2", "Foo" );
        final Permission edit = PermissionFactory.getPagePermission( "*:PlainPage2", "edit" );
        final Session anon = WikiSessionTest.anonymousSession( m_engine );

        final java.util.List<com.wikantik.event.WikiSecurityEvent> events = captureEvents();
        Assertions.assertFalse( m_auth.checkPermission( anon, edit ), "anonymous edit denied" );

        final com.wikantik.event.WikiSecurityEvent denied = events.stream()
            .filter( e -> e.getType() == com.wikantik.event.WikiSecurityEvent.ACCESS_DENIED )
            .findFirst().orElseThrow();
        Assertions.assertEquals( "policy-denied", denied.getAttributes().get( "reason" ) );
        Assertions.assertEquals( "anonymous", denied.getAttributes().get( "authStatus" ) );
        Assertions.assertNotNull( denied.getAttributes().get( "roles" ) );
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -am -Dtest=AuthorizationManagerTest#isPermittedMatchesCheckPermissionButFiresNoEvents+deniedEventCarriesReasonStatusAndRoles -q`
Expected: COMPILE FAILURE — `isPermitted` not defined; (after adding) `getAttributes()` empty.

- [ ] **Step 3a: Add interface members** — in `AuthorizationManager.java`, add next to `checkPermission`:

```java
    /**
     * Evaluates whether {@code session} holds {@code permission}, identically to
     * {@link #checkPermission(Session, Permission)} but <em>without</em> firing any
     * {@code WikiSecurityEvent} (so no audit row, no security-log line). Use for
     * speculative checks — search/sitemap/graph filtering, UI capability hints —
     * that are not access attempts.
     *
     * @param session the current wiki session
     * @param permission the Permission being queried
     * @return true if permitted
     */
    boolean isPermitted( Session session, Permission permission );
```

And add the attribute-carrying `fireEvent` overload beside the existing default `fireEvent` (around line 265):

```java
    /**
     * Fires a WikiSecurityEvent carrying structured {@code attributes} (e.g. an
     * access-denial reason/status/roles) to all registered listeners.
     */
    default void fireEvent( final int type, final Principal user, final Object permission,
                            final java.util.Map<String, String> attributes ) {
        if( WikiEventManager.isListening( this ) ) {
            new com.wikantik.core.subsystem.DefaultWikiEventBus()
                .fireEvent( this, new WikiSecurityEvent( this, type, user, permission, attributes ) );
        }
    }
```

- [ ] **Step 3b: Refactor `DefaultAuthorizationManager`** — replace the body of `checkPermission` (lines ~140–218) with the decide/fire split, and add the helpers. Add imports: `java.util.LinkedHashMap`, `java.util.Map`, `java.util.stream.Collectors` (note `java.util.Arrays` is already imported).

```java
    /** Outcome of a pure (event-free) authorization evaluation. */
    private record Decision( boolean allowed, String reason ) {}

    /** {@inheritDoc} */
    @Override
    public boolean checkPermission( final Session session, final Permission permission ) {
        final Decision d = decide( session, permission );
        final Principal user = ( session == null ) ? null : session.getLoginPrincipal();
        if ( d.allowed() ) {
            fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, user, permission );
        } else {
            fireEvent( WikiSecurityEvent.ACCESS_DENIED, user, permission, deniedAttributes( session, d.reason() ) );
        }
        return d.allowed();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPermitted( final Session session, final Permission permission ) {
        return decide( session, permission ).allowed();
    }

    /**
     * Pure authorization evaluation — no events fired. Single source of truth for both
     * {@link #checkPermission} (which fires + audits) and {@link #isPermitted} (silent).
     * Resolving unresolved ACL principals here is an intentional, idempotent side effect
     * preserved from the original checkPermission logic.
     */
    private Decision decide( final Session session, final Permission permission ) {
        if( session == null || permission == null ) {
            return new Decision( false, "no-session" );
        }
        if ( bootstrapAdmin != null && clock.getAsLong() < bootstrapExpiresAt ) {
            for ( final Principal p : session.getPrincipals() ) {
                if ( bootstrapAdmin.equals( p.getName() ) ) {
                    return new Decision( true, null );
                }
            }
        }
        final Permission allPermission = new AllPermission( engine.getApplicationName() );
        if( checkStaticPermission( session, allPermission ) ) {
            return new Decision( true, null );
        }
        if( !checkStaticPermission( session, permission ) ) {
            return new Decision( false, "policy-denied" );
        }
        if( !( permission instanceof PagePermission pagePerm ) ) {
            return new Decision( true, null );
        }
        final String pageName = pagePerm.getPage();
        final Page page = pageManager().getPage( pageName );
        final Acl acl = ( page == null ) ? null : aclManager().getPermissions( page );
        if( page == null || acl == null || acl.isEmpty() ) {
            return new Decision( true, null );
        }
        final Principal[] aclPrincipals = acl.findPrincipals( permission );
        for( Principal aclPrincipal : aclPrincipals ) {
            if ( aclPrincipal instanceof UnresolvedPrincipal unresolvedPrincipal ) {
                final AclEntry aclEntry = acl.getAclEntry( aclPrincipal );
                aclPrincipal = resolvePrincipal( unresolvedPrincipal.getName() );
                if ( aclEntry != null && !( aclPrincipal instanceof UnresolvedPrincipal ) ) {
                    aclEntry.setPrincipal( aclPrincipal );
                }
            }
            if ( hasRoleOrPrincipal( session, aclPrincipal ) ) {
                return new Decision( true, null );
            }
        }
        return new Decision( false, "acl-denied" );
    }

    /** Builds the {reason, authStatus, roles} attribute map attached to a denied event. */
    private static Map<String, String> deniedAttributes( final Session session, final String reason ) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put( "reason", reason );
        if ( session == null ) {
            m.put( "authStatus", "none" );
            m.put( "roles", "" );
        } else {
            m.put( "authStatus", session.getStatus() );
            m.put( "roles", Arrays.stream( session.getRoles() )
                .map( Principal::getName ).collect( Collectors.joining( "," ) ) );
        }
        return m;
    }
```

- [ ] **Step 4: Run tests to verify they pass + no authz regression**

Run: `mvn test -pl wikantik-main -am -Dtest=AuthorizationManagerTest -q`
Expected: PASS (new tests + all pre-existing permission tests — same allow/deny outcomes).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/AuthorizationManager.java wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java wikantik-main/src/test/java/com/wikantik/auth/AuthorizationManagerTest.java
git commit -m "feat(auth): pure isPermitted() + enriched access.denied (reason/status/roles)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `PermissionFilter.canAccessQuietly`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/permissions/PermissionFilter.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/permissions/PermissionFilterTest.java`

**Interfaces:**
- Consumes: `AuthorizationManager.isPermitted` (Task 3).
- Produces: `boolean PermissionFilter.canAccessQuietly(Session session, String pageName, String action)` — same result as `canAccess` but routes through `isPermitted` (no event).

- [ ] **Step 1: Write the failing test** — append to `PermissionFilterTest` (match its existing engine/session setup; use the same `TestEngine`/`WikiSessionTest` helpers it already uses):

```java
    @org.junit.jupiter.api.Test
    void canAccessQuietlyMatchesCanAccessWithoutFiringEvents() throws Exception {
        m_engine.saveText( "QuietPage", "Body" );
        final com.wikantik.api.core.Session anon =
            com.wikantik.WikiSessionTest.anonymousSession( m_engine );
        final PermissionFilter filter = new PermissionFilter( m_engine );

        final java.util.List<com.wikantik.event.WikiSecurityEvent> events = new java.util.ArrayList<>();
        m_engine.getManager( com.wikantik.auth.AuthorizationManager.class ).addWikiEventListener( e -> {
            if ( e instanceof com.wikantik.event.WikiSecurityEvent se ) events.add( se );
        } );

        final boolean quiet = filter.canAccessQuietly( anon, "QuietPage", "edit" );
        org.junit.jupiter.api.Assertions.assertEquals(
            filter.canAccess( anon, "QuietPage", "edit" ), quiet, "quiet result must match canAccess" );
        org.junit.jupiter.api.Assertions.assertTrue(
            events.stream().noneMatch( e -> e.getType() == com.wikantik.event.WikiSecurityEvent.ACCESS_DENIED
                                         && events.indexOf( e ) == 0 ),
            "the quiet call (made first) fired no ACCESS_DENIED before canAccess" );
    }
```

> Note: if `PermissionFilterTest` does not already declare `m_engine`, copy its existing `@BeforeEach` engine setup pattern (it constructs a `TestEngine` as the sibling `AuthorizationManagerTest` does). Verify field name before running.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -am -Dtest=PermissionFilterTest#canAccessQuietlyMatchesCanAccessWithoutFiringEvents -q`
Expected: COMPILE FAILURE — `canAccessQuietly` not defined.

- [ ] **Step 3: Implement** — refactor `PermissionFilter.java` to share the permission-building logic:

```java
    /** Builds the PagePermission for a page+action, honouring inline ACLs when the page exists. */
    private Permission permissionFor( final String pageName, final String action ) {
        final Page page = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPage( pageName );
        return ( page != null )
                ? PermissionFactory.getPagePermission( page, action )
                : new PagePermission( engine.getApplicationName() + ":" + pageName, action );
    }

    public boolean canAccess( final Session session, final String pageName, final String action ) {
        return AuthSubsystemBridge.fromLegacyEngine( engine ).authorization()
                .checkPermission( session, permissionFor( pageName, action ) );
    }

    /**
     * Like {@link #canAccess} but evaluates silently via
     * {@link com.wikantik.auth.AuthorizationManager#isPermitted} — no audit row, no
     * security-log line. For speculative callers (visibility filters, capability hints).
     */
    public boolean canAccessQuietly( final Session session, final String pageName, final String action ) {
        return AuthSubsystemBridge.fromLegacyEngine( engine ).authorization()
                .isPermitted( session, permissionFor( pageName, action ) );
    }
```

(`filterAccessible` keeps calling `canAccess` — it is used by retrieval visibility filtering; see Task 6 for whether its callers should switch.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -am -Dtest=PermissionFilterTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/permissions/PermissionFilter.java wikantik-main/src/test/java/com/wikantik/auth/permissions/PermissionFilterTest.java
git commit -m "feat(auth): PermissionFilter.canAccessQuietly (silent isPermitted path)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Split REST page-permission wrappers by intent

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/RestServletBase.java:428-445`

**Interfaces:**
- Consumes: `PermissionFilter.canAccess` (audited) + `canAccessQuietly` (silent) (Task 4).
- Produces: `hasPagePermission(...)` is now **silent** (capability query); `checkPagePermission(...)` still **audits** on the 403 path.

- [ ] **Step 1: Implement** — replace the two methods:

```java
    /**
     * Returns {@code true} if the current user has the specified permission for the given page.
     * This is a silent capability query (no 403, no audit row) — used to build UI affordance
     * maps. The enforcing variant is {@link #checkPagePermission}.
     */
    protected boolean hasPagePermission( final HttpServletRequest request,
                                          final String pageName,
                                          final String action ) {
        final Engine eng = getEngine();
        final Session session = Wiki.session().find( eng, request );
        return new PermissionFilter( eng ).canAccessQuietly( session, pageName, action );
    }

    /**
     * Enforces the permission: if denied, sends a 403 and returns {@code false}. The denial is
     * audited (routes through the event-firing {@link PermissionFilter#canAccess}).
     */
    protected boolean checkPagePermission( final HttpServletRequest request,
                                            final HttpServletResponse response,
                                            final String pageName,
                                            final String action ) throws IOException {
        final Engine eng = getEngine();
        final Session session = Wiki.session().find( eng, request );
        if ( new PermissionFilter( eng ).canAccess( session, pageName, action ) ) {
            return true;
        }
        sendError( response, HttpServletResponse.SC_FORBIDDEN, "Forbidden" );
        return false;
    }
```

- [ ] **Step 2: Compile the REST module**

Run: `mvn test-compile -pl wikantik-rest -am -q`
Expected: BUILD SUCCESS. (`Engine`, `Session`, `Wiki`, `PermissionFilter` are already imported/used in this file.)

- [ ] **Step 3: Run the REST resource unit tests touching these wrappers**

Run: `mvn test -pl wikantik-rest -am -Dtest=PageResourceTest -q`
Expected: PASS (capability map still returns correct booleans; 403 path unchanged). If `PageResourceTest` does not exist, run `mvn test -pl wikantik-rest -q` and confirm green.

- [ ] **Step 4: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/RestServletBase.java
git commit -m "refactor(rest): hasPagePermission silent, checkPagePermission audits the 403

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Migrate the remaining speculative call sites

**Files (one-line call swaps, each `checkPermission`→`isPermitted` or `canAccess`→`canAccessQuietly`):**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiContext.java:798` (`hasAdminPermissions`)
- Modify: `wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneSearcher.java:319`
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/SitemapServlet.java:177`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgGraphSnapshotBuilder.java:162`
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/DefaultPageGraphService.java:275`
- Modify: `wikantik-main/src/main/java/com/wikantik/plugin/InsertPage.java:139`
- Modify: `wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java:78`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/CommentThreadResource.java:282` — already routes through `hasPagePermission`, now silent via Task 5; **no change needed** (verify).

**Interfaces:** Consumes `isPermitted` (Task 3) / `canAccessQuietly` (Task 4). No new API.

- [ ] **Step 1: `WikiContext.hasAdminPermissions`** — replace `.checkPermission(` with `.isPermitted(`:

```java
    @Override
    public boolean hasAdminPermissions() {
        return AuthSubsystemBridge.fromLegacyEngine( engine ).authorization().isPermitted( getWikiSession(), new AllPermission( engine.getApplicationName() ) );
    }
```

- [ ] **Step 2: The four `mgr.checkPermission( ... )` filter sites** — at `DefaultLuceneSearcher:319`, `SitemapServlet:177`, `KgGraphSnapshotBuilder:162`, `DefaultPageGraphService:275`, change the method name `checkPermission` → `isPermitted` on that call (the receiver/args are unchanged). Open each at the cited line and edit the single call.

- [ ] **Step 3: `InsertPage:139`** — change `mgr.checkPermission( context.getWikiSession(), PermissionFactory.getPagePermission( page, "view" ) )` → `mgr.isPermitted( context.getWikiSession(), PermissionFactory.getPagePermission( page, "view" ) )`.

- [ ] **Step 4: `OntologyWiringHelper:78`** — change `permFilter.canAccess( guest, slug, "view" )` → `permFilter.canAccessQuietly( guest, slug, "view" )`.

- [ ] **Step 5: Verify no stray speculative `checkPermission`/`canAccess` remain unclassified**

Run: `grep -rn "\.checkPermission(\|\.canAccess(" --include=*.java wikantik-main/src/main wikantik-rest/src/main | grep -v "isPermitted\|canAccessQuietly\|DefaultAuthorizationManager.java\|// enforce"`
Expected: only the **enforcement** sites remain — `AdminAuthFilter`, `AttachmentServlet` (view/upload gates), `DerivedIngestResource`, `DefaultUserManager`, `WikiPageFormatFilter`, `RestServletBase.checkPagePermission`, `PermissionFilter.canAccess`, `DefaultAuthorizationManager.hasAccess`. Resolve `AttachmentServlet:607` here against the principle (it gates a served/uploaded attachment → enforcement → leave as `checkPermission`).

- [ ] **Step 6: Compile both modules**

Run: `mvn test-compile -pl wikantik-rest -am -q`
Expected: BUILD SUCCESS (also compiles `wikantik-main`).

- [ ] **Step 7: Run the affected subsystems' unit tests**

Run: `mvn test -pl wikantik-main -Dtest=InsertPageTest,DefaultPageGraphServiceTest,SitemapServletTest -q`
Expected: PASS for whichever of these exist (skip-not-found is fine with `-Dsurefire.failIfNoSpecifiedTests=false`). At minimum confirm no behavioural regression in page-graph/sitemap visibility.

- [ ] **Step 8: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiContext.java wikantik-main/src/main/java/com/wikantik/search/subsystem/lucene/DefaultLuceneSearcher.java wikantik-main/src/main/java/com/wikantik/ui/SitemapServlet.java wikantik-main/src/main/java/com/wikantik/knowledge/KgGraphSnapshotBuilder.java wikantik-main/src/main/java/com/wikantik/pagegraph/DefaultPageGraphService.java wikantik-main/src/main/java/com/wikantik/plugin/InsertPage.java wikantik-main/src/main/java/com/wikantik/ontology/runtime/OntologyWiringHelper.java
git commit -m "refactor(auth): route speculative permission checks through silent isPermitted

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Clickable rows → audit record detail modal (frontend)

**Files:**
- Create: `wikantik-frontend/src/components/admin/AuditRecordModal.jsx`
- Create: `wikantik-frontend/src/components/admin/AuditRecordModal.test.jsx`
- Modify: `wikantik-frontend/src/components/admin/AdminAuditPage.jsx`

**Interfaces:**
- Consumes: the row object from `api.admin.listAuditLog` (already includes `detail`, `sourceIp`, `userAgent`, `correlationId`, `targetLabel`, `rowHash`, `prevHash`, …) and the shared `Modal` (`src/components/ui/Modal.jsx`, props `{ isOpen, onClose, labelledBy, children }`).
- Produces: `<AuditRecordModal record={row} onClose={fn} />` and a clickable row wired via `AdminTable`'s existing `onRowClick` prop.

- [ ] **Step 1: Write the failing test** — `AuditRecordModal.test.jsx`:

```jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AuditRecordModal from './AuditRecordModal';

const ROW = {
  seq: 42,
  eventTime: '2026-06-23T10:00:00Z',
  category: 'AUTHZ',
  eventType: 'access.denied',
  outcome: 'DENIED',
  actorPrincipal: 'alice',
  actorType: 'user',
  targetType: 'page',
  targetId: 'SecretPage',
  targetLabel: 'edit → SecretPage',
  sourceIp: '203.0.113.7',
  userAgent: 'curl/8.4.0',
  correlationId: 'req-xyz',
  rowHash: 'abc',
  prevHash: 'def',
  detail: '{"permission":"*:SecretPage","uri":"/api/pages/SecretPage","method":"POST","reason":"acl-denied","authStatus":"authenticated","roles":"Authenticated,All"}',
};

describe('AuditRecordModal', () => {
  it('renders the parsed detail fields and core record data', () => {
    render(<AuditRecordModal record={ROW} onClose={() => {}} />);
    expect(screen.getByText('acl-denied')).toBeInTheDocument();      // reason
    expect(screen.getByText('authenticated')).toBeInTheDocument();   // authStatus
    expect(screen.getByText('curl/8.4.0')).toBeInTheDocument();      // userAgent
    expect(screen.getByText('edit → SecretPage')).toBeInTheDocument(); // targetLabel
    expect(screen.getByText('POST')).toBeInTheDocument();            // method
  });

  it('calls onClose when the close button is clicked', () => {
    const onClose = vi.fn();
    render(<AuditRecordModal record={ROW} onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: /close/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders raw detail text when detail is not valid JSON', () => {
    render(<AuditRecordModal record={{ ...ROW, detail: 'not-json' }} onClose={() => {}} />);
    expect(screen.getByText('not-json')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AuditRecordModal.test.jsx`
Expected: FAIL — module `./AuditRecordModal` not found.

- [ ] **Step 3: Implement `AuditRecordModal.jsx`**

```jsx
// AuditRecordModal.jsx
// Read-only detail view of a single audit record, shown when a row is clicked.
// Renders every field the REST API returns; parses the `detail` JSON defensively.
import Modal from '../ui/Modal';

function parseDetail(detail) {
  if (!detail) return null;
  try {
    return JSON.parse(detail);
  } catch {
    return { _raw: detail };
  }
}

function Row({ label, value }) {
  if (value === null || value === undefined || value === '') return null;
  return (
    <div className="audit-detail-row" style={{ display: 'flex', gap: 'var(--space-md)', padding: '2px 0' }}>
      <span style={{ minWidth: '140px', color: 'var(--color-text-muted)' }}>{label}</span>
      <span style={{ wordBreak: 'break-word' }}>{String(value)}</span>
    </div>
  );
}

function Section({ title, children }) {
  return (
    <section style={{ marginBottom: 'var(--space-md)' }}>
      <h3 style={{ fontSize: '0.85rem', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--color-text-muted)' }}>{title}</h3>
      {children}
    </section>
  );
}

export default function AuditRecordModal({ record, onClose }) {
  if (!record) return null;
  const detail = parseDetail(record.detail);

  return (
    <Modal isOpen onClose={onClose} labelledBy="audit-record-title" testId="audit-record-modal">
      <div style={{ maxWidth: '640px' }}>
        <h2 id="audit-record-title" style={{ marginTop: 0 }}>
          Audit record #{record.seq}
        </h2>

        <Section title="Event">
          <Row label="Time" value={record.eventTime} />
          <Row label="Category" value={record.category} />
          <Row label="Event" value={record.eventType} />
          <Row label="Outcome" value={record.outcome} />
        </Section>

        <Section title="Actor">
          <Row label="Principal" value={record.actorPrincipal} />
          <Row label="Type" value={record.actorType} />
          <Row label="Id" value={record.actorId} />
        </Section>

        <Section title="Target">
          <Row label="Type" value={record.targetType} />
          <Row label="Id" value={record.targetId} />
          <Row label="Label" value={record.targetLabel} />
        </Section>

        <Section title="Request / why">
          {detail && detail._raw === undefined && (
            <>
              <Row label="Permission" value={detail.permission} />
              <Row label="Method" value={detail.method} />
              <Row label="URI" value={detail.uri} />
              <Row label="Reason" value={detail.reason} />
              <Row label="Auth status" value={detail.authStatus} />
              <Row label="Roles" value={detail.roles} />
            </>
          )}
          {detail && detail._raw !== undefined && <Row label="Detail" value={detail._raw} />}
          <Row label="Source IP" value={record.sourceIp} />
          <Row label="User agent" value={record.userAgent} />
          <Row label="Correlation id" value={record.correlationId} />
        </Section>

        <Section title="Integrity">
          <Row label="Row hash" value={record.rowHash} />
          <Row label="Prev hash" value={record.prevHash} />
        </Section>

        <div style={{ textAlign: 'right', marginTop: 'var(--space-md)' }}>
          <button type="button" className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>
      </div>
    </Modal>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AuditRecordModal.test.jsx`
Expected: PASS (3 tests). If a test flakes, re-run the file alone before investigating (known vitest concurrency false-failures).

- [ ] **Step 5: Wire it into `AdminAuditPage.jsx`**

Add the import and state, pass `onRowClick` to `AdminTable`, and render the modal. Concretely:

```jsx
// at top with other imports
import AuditRecordModal from './AuditRecordModal';
```

```jsx
// inside AdminAuditPage(), with the other useState hooks
const [selectedRecord, setSelectedRecord] = useState(null);
```

```jsx
// the results table — add onRowClick
{fetched && (
  <AdminTable
    rows={rows}
    getRowKey={(r) => String(r.seq)}
    columns={COLUMNS}
    onRowClick={(r) => setSelectedRecord(r)}
    emptyMessage="No audit entries matched the filter."
    initialSort={{ columnId: 'seq', direction: 'desc' }}
  />
)}

{selectedRecord && (
  <AuditRecordModal record={selectedRecord} onClose={() => setSelectedRecord(null)} />
)}
```

- [ ] **Step 6: Run the admin frontend test suite to confirm no regression**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-frontend/src/components/admin/AuditRecordModal.jsx wikantik-frontend/src/components/admin/AuditRecordModal.test.jsx wikantik-frontend/src/components/admin/AdminAuditPage.jsx
git commit -m "feat(admin-ui): clickable audit rows open a record-detail modal

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Integration test + final verification

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AuditLogIT.java`

**Interfaces:** Consumes `GET /admin/audit` (filtered list) — the enriched `detail` on enforced denials, and the *absence* of rows for speculative checks.

- [ ] **Step 1: Add an IT method** proving (a) an enforced page denial is audited with the new fields, and (b) a search over a restricted page produces no new `access.denied` rows. Follow the file's existing admin-auth + REST-helper patterns (use `RestSeedHelper.awaitAdminReady` before the first admin call — known login race). Sketch to adapt to the file's helpers:

```java
    @org.junit.jupiter.api.Test
    void enforcedDenialIsEnrichedAndSpeculativeChecksAreSilent() throws Exception {
        // 1. As an anonymous client, request a restricted page edit via the REST enforcement path
        //    (expects 403). Then as admin, GET /admin/audit?eventType=access.denied and assert the
        //    newest row's `detail` contains "reason", "authStatus", and "roles".
        // 2. Capture the access.denied row count, perform an anonymous /api/search across a corpus
        //    that includes a restricted page, re-query the count, and assert it did NOT increase.
        // Use the existing RestSeedHelper / admin-auth fixtures already imported in this file.
    }
```

Implement against the file's actual helpers (HTTP client, base URL, admin creds, JSON parsing) — do not invent new infrastructure.

- [ ] **Step 2: Run the single IT (sequential — no `-T`)**

Run: `mvn clean install -pl wikantik-it-tests/wikantik-it-test-rest -am -Pintegration-tests -Dit.test=AuditLogIT -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -fae`
Expected: PASS. (The `ZZZ_NoUnitTests` + `failIfNoSpecifiedTests=false` combo skips `wikantik-main`'s 4000 serial unit tests in the reactor.)

- [ ] **Step 3: Commit the IT**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/AuditLogIT.java
git commit -m "test(it): enforced access.denied enriched + speculative checks produce no rows

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: Full unit build (no parallel flag for wikantik-main)**

Run: `mvn clean install -DskipITs`
Expected: BUILD SUCCESS. If a `wikantik-main` test flakes, re-run that class in isolation before treating it as a regression (known parallel/file-handle flakes).

- [ ] **Step 5: Full sequential integration run**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS across all IT submodules. (`EditIT#createPageAndTestEditPermissions` is a known CodeMirror flake — re-run isolated if it reds.)

- [ ] **Step 6: Final commit if anything was touched during verification**

```bash
git status   # commit only intentional changes, by name
```

---

## Self-Review

**Spec coverage:**
- Goal "stop speculative checks producing rows" → Tasks 3 (`isPermitted`), 4 (`canAccessQuietly`), 5 (REST split), 6 (call-site migration). ✅
- Goal "enrich enforced denials (reason/authStatus/roles)" → Tasks 1 (transport), 2 (merge into detail), 3 (compute at deny). ✅
- Goal "clickable rows → record-detail modal, all recorded fields" → Task 7. ✅
- Non-goal "no migration" → enrichment in `detail` JSON only; no migration task. ✅
- Non-goal "no ACCESS_ALLOWED change" → `decide()` still fires `ACCESS_ALLOWED` exactly as before; listener unchanged for it. ✅
- Risk "fail-safe / verify enforcement sites" → Task 6 Step 5 grep gate + `AttachmentServlet:607` resolution. ✅
- Verification rules (full unit build, sequential ITs, test-compile after signature change) → Task 8 + per-task `test-compile`/`-am`. ✅

**Placeholder scan:** No "TBD/TODO/handle edge cases". Task 8's IT body is intentionally adapted to the existing file's helpers (the only spot without verbatim code, because it depends on that file's private test infrastructure); its required assertions are spelled out.

**Type consistency:** `isPermitted(Session, Permission)`, `canAccessQuietly(Session, String, String)`, `fireEvent(int, Principal, Object, Map<String,String>)`, `WikiSecurityEvent(..., Map<String,String>)`, `getAttributes(): Map<String,String>`, `deniedDetail(String, Map<String,String>)` — names/signatures consistent across Tasks 1–6. Frontend `AuditRecordModal({ record, onClose })` consistent between Task 7 Steps 1, 3, 5.

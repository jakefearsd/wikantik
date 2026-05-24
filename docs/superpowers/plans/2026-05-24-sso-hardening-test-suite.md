# SSO Hardening Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the SSO test gaps (SAML, identity binding, claim handling, provisioning, callback security) with a test pyramid and fix every production defect a test proves, test-first.

**Architecture:** Unit/component tests in `wikantik-main` pin the deterministic logic edges and drive production fixes red→green. The existing `wikantik-it-test-sso` module gains an OIDC callback-security IT. A new `wikantik-it-test-sso-saml` module drives a full SAML browser login against a containerised SimpleSAMLphp IdP.

**Tech Stack:** Java 21, JUnit 5, Mockito, pac4j (OIDC + SAML), `TestEngine`/`HttpMockFactory` test harness, Selenide + Cargo + `docker-maven-plugin` for ITs.

**Spec:** `docs/superpowers/specs/2026-05-24-sso-hardening-test-suite-design.md`

---

## Key facts for the implementer (read first)

- **Build a single module fast:** `mvn test -pl wikantik-main -Dtest=ClassName` (unit) — do NOT run a full reactor between every step.
- **Run signature-affecting checks with** `mvn test-compile -pl wikantik-main -q` (plain `compile` skips test sources).
- **Integration tests** run only under `-Pintegration-tests`, **sequentially** (never `-T`), and need a docker daemon. Always `-fae`.
- **Test harness primitives** (already used by existing SSO tests):
  - `new TestEngine( TestEngine.getTestProperties() )` — a live engine with an `XMLUserDatabase`.
  - `engine.getManager( UserManager.class ).getUserDatabase()` → `UserDatabase` with `newProfile()`, `save(profile)`, `findByLoginName(name)` (throws `NoSuchPrincipalException` when absent), `deleteByLoginName(name)`.
  - `UserProfile.getAttributes()` → mutable `Map<String,Serializable>` (used for the SSO-link marker).
  - `HttpMockFactory.createHttpRequest()` / `createHttpSession()` (Mockito-backed).
  - `new WebContainerCallbackHandler( engine, request )` — the callback handler `SSOLoginModule` expects.
  - pac4j `CommonProfile`: `setId(..)`, `addAttribute(key, value)`, `getAttribute(key)`, `getUsername()`.
  - pac4j profiles live in the session under `org.pac4j.core.util.Pac4jConstants.USER_PROFILES` as a `LinkedHashMap<String,UserProfile>`.
  - `SSOLoginModule.OPTION_CLAIM_LOGIN_NAME` = `"sso.claimLoginName"`.
- **Production files in play:**
  - `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOConfig.java`
  - `.../sso/SSOLoginModule.java`
  - `.../sso/SSOAutoProvisionService.java`
  - `.../sso/SSORedirectServlet.java`
- **TDD rule:** every defect test must be **run and observed to FAIL** before the production edit. If a catalogued test passes as-is, keep it as a regression guard and skip that task's production edit (note it in the commit message).

---

## Phase 1 — Claim handling & safety (unit, red→green)

### Task 1: Normalise multi-valued claims (#3)

**Files:**
- Test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java` (create)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOLoginModule.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java`

- [ ] **Step 1: Write the failing test**

Create `SSOLoginModuleEdgeCasesTest.java`:

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
package com.wikantik.auth.sso;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pac4j.core.profile.CommonProfile;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class SSOLoginModuleEdgeCasesTest {

    private SSOLoginModule moduleWithClaim( final String loginClaim ) {
        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( new Subject(), Mockito.mock( CallbackHandler.class ), new HashMap<>(),
                Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, loginClaim ) );
        return module;
    }

    @Test
    void resolvesFirstElementOfMultiValuedClaim() {
        final CommonProfile profile = new CommonProfile();
        profile.addAttribute( "sub", List.of( "alice", "alice-alt" ) );

        Assertions.assertEquals( "alice", moduleWithClaim( "sub" ).resolveLoginName( profile ),
            "A list-valued claim must resolve to its first element, not its toString()." );
    }
}
```

- [ ] **Step 2: Run it; verify it FAILS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest#resolvesFirstElementOfMultiValuedClaim -q`
Expected: FAIL — actual value is `"[alice, alice-alt]"`.

- [ ] **Step 3: Add a shared claim-normaliser and use it in `SSOLoginModule`**

In `SSOLoginModule.java`, add imports `java.util.Collection` and replace the body of `resolveLoginName` so the configured-claim branch normalises:

```java
    String resolveLoginName( final UserProfile profile ) {
        final String claimName = getOption( OPTION_CLAIM_LOGIN_NAME, DEFAULT_CLAIM_LOGIN );

        final String claimValue = firstScalar( profile.getAttribute( claimName ) );
        if( claimValue != null && !claimValue.isBlank() ) {
            return claimValue;
        }

        final String username = profile.getUsername();
        if( username != null && !username.isBlank() ) {
            return username;
        }

        return profile.getId();
    }

    /**
     * Normalises a pac4j attribute to a single scalar string. SAML (and some
     * OIDC) attributes arrive as a {@link Collection}; taking {@code toString()}
     * of the collection yields "[value]" and corrupts the identity. Returns the
     * first element's string form, or the value's string form when scalar.
     */
    static String firstScalar( final Object value ) {
        if( value == null ) {
            return null;
        }
        if( value instanceof Collection< ? > c ) {
            return c.isEmpty() ? null : String.valueOf( c.iterator().next() );
        }
        return value.toString();
    }
```

- [ ] **Step 4: Reuse the normaliser in `SSOAutoProvisionService`**

In `SSOAutoProvisionService.java`, replace `resolveAttribute`'s body:

```java
    private String resolveAttribute( final org.pac4j.core.profile.UserProfile profile, final String claimName ) {
        return SSOLoginModule.firstScalar( profile.getAttribute( claimName ) );
    }
```

- [ ] **Step 5: Add the provisioning-side test**

Append to `SSOLoginModuleEdgeCasesTest.java` (it exercises the shared normaliser via the service path) — actually verify directly:

```java
    @Test
    void firstScalarUnwrapsCollections() {
        Assertions.assertEquals( "a@b.com", SSOLoginModule.firstScalar( List.of( "a@b.com" ) ) );
        Assertions.assertEquals( "scalar", SSOLoginModule.firstScalar( "scalar" ) );
        Assertions.assertNull( SSOLoginModule.firstScalar( List.of() ) );
        Assertions.assertNull( SSOLoginModule.firstScalar( null ) );
    }
```

- [ ] **Step 6: Run both tests; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest -q`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOLoginModule.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java
git commit -m "fix(sso): normalise multi-valued IdP claims to first scalar element

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Reject unsafe resolved login names (#4)

**Files:**
- Modify test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOLoginModule.java`

- [ ] **Step 1: Write the failing test**

Add to `SSOLoginModuleEdgeCasesTest.java` (add imports
`com.wikantik.HttpMockFactory`, `com.wikantik.TestEngine`,
`com.wikantik.auth.login.WebContainerCallbackHandler`,
`jakarta.servlet.http.HttpServletRequest`, `jakarta.servlet.http.HttpSession`,
`org.pac4j.core.util.Pac4jConstants`, `javax.security.auth.login.LoginException`,
`java.util.LinkedHashMap`, `org.junit.jupiter.params.ParameterizedTest`,
`org.junit.jupiter.params.provider.ValueSource`):

```java
    private HttpServletRequest requestWithProfile( final CommonProfile profile ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpSession session = request.getSession();
        final LinkedHashMap< String, org.pac4j.core.profile.UserProfile > profiles = new LinkedHashMap<>();
        profiles.put( "OidcClient#" + profile.getId(), profile );
        Mockito.doReturn( profiles ).when( session ).getAttribute( Pac4jConstants.USER_PROFILES );
        return request;
    }

    @ParameterizedTest
    @ValueSource( strings = { "   ", "bad\tname", "name\nwith-newline", "a b" } )
    void rejectsUnsafeLoginNames( final String hostile ) throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final CommonProfile profile = new CommonProfile();
        profile.setId( "id-1" );
        profile.addAttribute( "sub", hostile );

        final SSOLoginModule module = new SSOLoginModule();
        module.initialize( new Subject(), new WebContainerCallbackHandler( engine, requestWithProfile( profile ) ),
                new HashMap<>(), Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "sub" ) );

        Assertions.assertThrows( LoginException.class, module::login,
            "Whitespace/control-character login names must be rejected, not bound to a principal." );
    }
```

- [ ] **Step 2: Run it; verify it FAILS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest#rejectsUnsafeLoginNames -q`
Expected: FAIL — login succeeds today (no validation), so no exception is thrown.

- [ ] **Step 3: Add validation in `SSOLoginModule.login()`**

In `SSOLoginModule.java`, replace the existing blank-check block:

```java
            final String loginName = resolveLoginName( profile );
            if( loginName == null || loginName.isBlank() ) {
                throw new FailedLoginException( "SSO profile has no login name." );
            }
```

with:

```java
            final String loginName = resolveLoginName( profile );
            if( !isSafeLoginName( loginName ) ) {
                throw new FailedLoginException( "SSO profile login name is missing or unsafe." );
            }
```

and add this method (add import `java.util.Objects` is not needed):

```java
    /** Maximum accepted login-name length; matches the user store's column width. */
    private static final int MAX_LOGIN_NAME_LENGTH = 100;

    /**
     * A resolved login name is safe only if it is non-blank, within the store's
     * length bound, and free of whitespace and ISO control characters. Rejecting
     * here stops a hostile IdP claim from minting a malformed {@link WikiPrincipal}.
     */
    static boolean isSafeLoginName( final String loginName ) {
        if( loginName == null || loginName.isBlank() || loginName.length() > MAX_LOGIN_NAME_LENGTH ) {
            return false;
        }
        return loginName.chars().noneMatch( c -> Character.isWhitespace( c ) || Character.isISOControl( c ) );
    }
```

- [ ] **Step 4: Run it; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest#rejectsUnsafeLoginNames -q`
Expected: PASS (all four hostile values rejected).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOLoginModule.java
git commit -m "fix(sso): reject blank/whitespace/control-char login names from IdP claims

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 2 — Identity binding (headline fix, #1/#2)

The model: identity is keyed on the **immutable subject** (`identityClaim`,
default `sub`). Auto-provisioned profiles are stamped with an
`sso.subject` attribute. At login, a pre-existing local profile whose name
collides with the resolved identity is only adopted when its `sso.subject`
marker matches; otherwise login fails closed (no account hijack). A documented
opt-in (`wikantik.sso.identityClaim = preferred_username`) restores
trust-the-username behaviour.

### Task 3: `SSOConfig.identityClaim` property (default `sub`)

**Files:**
- Test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOConfigIdentityClaimTest.java` (create)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOConfig.java`

- [ ] **Step 1: Write the failing test**

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
package com.wikantik.auth.sso;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

class SSOConfigIdentityClaimTest {

    @Test
    void identityClaimDefaultsToSub() {
        final SSOConfig cfg = new SSOConfig( new Properties(), "http://localhost/sso/callback" );
        Assertions.assertEquals( "sub", cfg.getIdentityClaim() );
    }

    @Test
    void identityClaimIsConfigurable() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_IDENTITY_CLAIM, "preferred_username" );
        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );
        Assertions.assertEquals( "preferred_username", cfg.getIdentityClaim() );
    }
}
```

- [ ] **Step 2: Run it; verify it FAILS**

Run: `mvn test -pl wikantik-main -Dtest=SSOConfigIdentityClaimTest -q`
Expected: FAIL — `PROP_SSO_IDENTITY_CLAIM` / `getIdentityClaim()` do not compile yet.

- [ ] **Step 3: Add the property to `SSOConfig`**

In `SSOConfig.java`, add a constant near the other `PROP_*` declarations:

```java
    /** IdP claim treated as the immutable identity (account link key). */
    public static final String PROP_SSO_IDENTITY_CLAIM = "wikantik.sso.identityClaim";
```

Add a field and initialise it in the constructor (next to `claimEmail`):

```java
    private final String identityClaim;
```
```java
        this.identityClaim = props.getProperty( PROP_SSO_IDENTITY_CLAIM, "sub" );
```

Add the getter:

```java
    /** Returns the IdP claim used as the immutable account-link identity. */
    public String getIdentityClaim() {
        return identityClaim;
    }
```

- [ ] **Step 4: Run it; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOConfigIdentityClaimTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOConfigIdentityClaimTest.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOConfig.java
git commit -m "feat(sso): add wikantik.sso.identityClaim config (default sub)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Stamp provisioned profiles with the SSO subject

**Files:**
- Test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOAutoProvisionServiceEdgeCasesTest.java` (create)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java`

- [ ] **Step 1: Write the failing test**

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
package com.wikantik.auth.sso;

import com.wikantik.TestEngine;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pac4j.core.profile.CommonProfile;

import java.util.Properties;

class SSOAutoProvisionServiceEdgeCasesTest {

    private TestEngine engine;
    private UserDatabase userDb;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        userDb = engine.getManager( UserManager.class ).getUserDatabase();
    }

    @AfterEach
    void tearDown() {
        try { userDb.deleteByLoginName( "subject-123" ); } catch( final Exception ignored ) { }
    }

    private SSOConfig enabledConfig() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        return new SSOConfig( p, "http://localhost/sso/callback" );
    }

    @Test
    void provisionStampsSsoSubjectMarker() throws Exception {
        final CommonProfile profile = new CommonProfile();
        profile.setId( "subject-123" );
        new SSOAutoProvisionService( engine, enabledConfig() ).provisionIfNeeded( "subject-123", profile );

        final UserProfile created = userDb.findByLoginName( "subject-123" );
        Assertions.assertEquals( "subject-123",
            created.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT ),
            "Auto-provisioned profiles must record the SSO subject for later verified-link checks." );
    }
}
```

- [ ] **Step 2: Run it; verify it FAILS**

Run: `mvn test -pl wikantik-main -Dtest=SSOAutoProvisionServiceEdgeCasesTest#provisionStampsSsoSubjectMarker -q`
Expected: FAIL — `ATTR_SSO_SUBJECT` does not compile yet.

- [ ] **Step 3: Stamp the marker during provisioning**

In `SSOAutoProvisionService.java`, add the constant:

```java
    /** Profile attribute recording the IdP subject that owns this account. */
    public static final String ATTR_SSO_SUBJECT = "sso.subject";
```

Change `provisionIfNeeded` to accept the subject and stamp it. Replace the
method signature and the save block. New signature:

```java
    public void provisionIfNeeded( final String loginName, final String subject,
                                   final org.pac4j.core.profile.UserProfile ssoProfile ) {
```

Immediately before `userDb.save( profile );` add:

```java
            if( subject != null && !subject.isBlank() ) {
                profile.getAttributes().put( ATTR_SSO_SUBJECT, subject );
            }
```

Keep a backward-compatible two-arg overload so existing callers/tests still
compile:

```java
    /** @deprecated use {@link #provisionIfNeeded(String,String,org.pac4j.core.profile.UserProfile)}. */
    @Deprecated
    public void provisionIfNeeded( final String loginName, final org.pac4j.core.profile.UserProfile ssoProfile ) {
        provisionIfNeeded( loginName, loginName, ssoProfile );
    }
```

- [ ] **Step 4: Run it; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOAutoProvisionServiceEdgeCasesTest#provisionStampsSsoSubjectMarker -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOAutoProvisionServiceEdgeCasesTest.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java
git commit -m "feat(sso): stamp auto-provisioned profiles with sso.subject marker

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Fail closed on unverified account collision (#1)

**Files:**
- Modify test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOLoginModule.java`

- [ ] **Step 1: Write the failing test**

Add to `SSOLoginModuleEdgeCasesTest.java` (add imports
`com.wikantik.auth.UserManager`, `com.wikantik.auth.user.UserDatabase`,
`com.wikantik.auth.user.UserProfile`, `com.wikantik.auth.WikiPrincipal`,
`java.security.Principal`):

```java
    @Test
    void refusesToBindToPreexistingNonSsoAccount() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final UserDatabase userDb = engine.getManager( UserManager.class ).getUserDatabase();

        // A locally-created admin account that was NEVER linked to SSO.
        final UserProfile local = userDb.newProfile();
        local.setLoginName( "admin" );
        local.setFullname( "Local Admin" );
        userDb.save( local );
        try {
            // A hostile IdP asserts identity "admin".
            final CommonProfile profile = new CommonProfile();
            profile.setId( "admin" );
            profile.addAttribute( "sub", "admin" );

            final SSOLoginModule module = new SSOLoginModule();
            final Subject subject = new Subject();
            module.initialize( subject, new WebContainerCallbackHandler( engine, requestWithProfile( profile ) ),
                    new HashMap<>(), Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "sub" ) );

            Assertions.assertThrows( LoginException.class, module::login,
                "SSO must not adopt a pre-existing local account that has no matching sso.subject." );
            Assertions.assertTrue( subject.getPrincipals( WikiPrincipal.class ).isEmpty(),
                "No principal may be bound when the collision check fails." );
        } finally {
            userDb.deleteByLoginName( "admin" );
        }
    }
```

- [ ] **Step 2: Run it; verify it FAILS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest#refusesToBindToPreexistingNonSsoAccount -q`
Expected: FAIL — today the module binds `WikiPrincipal("admin")` and returns true.

- [ ] **Step 3: Add the verified-link guard before binding the principal**

In `SSOLoginModule.java`, add imports:

```java
import com.wikantik.api.core.Engine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;
```

In `login()`, after the `isSafeLoginName` check and BEFORE
`principals.add( new WikiPrincipal( ... ) )`, resolve the engine first (it is
currently resolved lower down — move it up) and insert the guard:

```java
            final Engine engine = ecb.getEngine();
            if( !isLinkAllowed( engine, loginName, resolveSubject( profile ) ) ) {
                throw new FailedLoginException(
                    "SSO identity '" + loginName + "' collides with an existing non-SSO account." );
            }

            LOG.debug( "SSO login succeeded for user: {}", loginName );
            principals.add( new WikiPrincipal( loginName, WikiPrincipal.LOGIN_NAME ) );

            if( engine != null ) {
                final SSOConfig ssoConfig = SSOConfigHolder.getConfig( engine );
                if( ssoConfig != null ) {
                    new SSOAutoProvisionService( engine, ssoConfig )
                        .provisionIfNeeded( loginName, resolveSubject( profile ), profile );
                }
            }

            return true;
```

(Delete the now-duplicated `final Engine engine = ecb.getEngine();` and the old
provisioning block below.)

Add the two helper methods:

```java
    /** Resolves the immutable subject claim used to verify account ownership. */
    private String resolveSubject( final UserProfile profile ) {
        final String subject = firstScalar( profile.getAttribute( getOption( "sso.identityClaim", "sub" ) ) );
        return subject != null ? subject : resolveLoginName( profile );
    }

    /**
     * A login may bind to {@code loginName} only when no local profile exists
     * yet, or the existing profile is SSO-linked to the same {@code subject}.
     * This stops a hostile IdP from asserting a name that matches a local
     * account and inheriting its identity.
     */
    private boolean isLinkAllowed( final Engine engine, final String loginName, final String subject ) {
        if( engine == null ) {
            return true; // unit contexts with no engine: nothing to collide with
        }
        final UserDatabase userDb = AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase();
        if( userDb == null ) {
            return true;
        }
        try {
            final UserProfile existing = userDb.findByLoginName( loginName );
            final Object linked = existing.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT );
            return subject != null && subject.equals( linked );
        } catch( final NoSuchPrincipalException e ) {
            return true; // no collision — fresh SSO identity
        }
    }
```

Add the LoginModule option key so `sso.identityClaim` can be passed through JAAS
options (used by `resolveSubject`); near `OPTION_CLAIM_LOGIN_NAME`:

```java
    /** LoginModule option key for the immutable identity claim. */
    static final String OPTION_IDENTITY_CLAIM = "sso.identityClaim";
```

and change `resolveSubject` to use it: replace `getOption( "sso.identityClaim", "sub" )`
with `getOption( OPTION_IDENTITY_CLAIM, "sub" )`.

- [ ] **Step 4: Run the full edge-case + existing module tests; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest,SSOLoginModuleTest,SSOAutoProvisionServiceTest,SSOAutoProvisionServiceEdgeCasesTest -q`
Expected: PASS. (The existing `SSOLoginModuleTest` has no local collision, so the guard returns true and behaviour is unchanged.)

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOLoginModule.java
git commit -m "fix(sso): fail closed when SSO identity collides with a non-SSO account

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Wire `identityClaim` through the callback servlet's LoginModule options

**Files:**
- Test: extend `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOConfigIdentityClaimTest.java`
- Verify wiring: `grep` for where `sso.claimLoginName` JAAS options are populated.

- [ ] **Step 1: Locate where LoginModule SSO options are set**

Run: `grep -rn "sso.claimLoginName\|loginModule.options.sso" wikantik-main/src/main/java`
Expected: find the code that copies `wikantik.loginModule.options.sso.*` into JAAS options. Confirm `sso.identityClaim` flows the same way (it is a `wikantik.loginModule.options.sso.identityClaim` passthrough — no code change if the prefix is copied generically).

- [ ] **Step 2: Add a documentation test asserting the option key constant**

Append to `SSOConfigIdentityClaimTest.java`:

```java
    @Test
    void loginModuleOptionKeyMatchesConfigSuffix() {
        // The JAAS option suffix must match the config property's tail so the
        // generic loginModule.options.sso.* passthrough wires identityClaim.
        Assertions.assertEquals( "sso.identityClaim", SSOLoginModule.OPTION_IDENTITY_CLAIM );
    }
```

- [ ] **Step 3: Run it; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOConfigIdentityClaimTest -q`
Expected: PASS.

- [ ] **Step 4: If the passthrough is NOT generic**, add `sso.identityClaim` to the explicit option list you found in Step 1 (mirror the line that sets `sso.claimLoginName`). Then re-run Step 3.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOConfigIdentityClaimTest.java
# include the wiring file from Step 4 only if you edited it
git commit -m "test(sso): pin identityClaim JAAS option wiring

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 3 — Provisioning race & config robustness (unit)

### Task 7: Tolerate concurrent first-login provisioning (#5)

**Files:**
- Modify test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOAutoProvisionServiceEdgeCasesTest.java`
- Modify (only if test fails): `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java`

- [ ] **Step 1: Write the test**

Add to `SSOAutoProvisionServiceEdgeCasesTest.java` (add imports
`java.util.concurrent.CountDownLatch`, `java.util.concurrent.ExecutorService`,
`java.util.concurrent.Executors`, `java.util.concurrent.TimeUnit`):

```java
    @Test
    void concurrentFirstLoginsProvisionExactlyOnce() throws Exception {
        final SSOConfig cfg = enabledConfig();
        final int threads = 8;
        final CountDownLatch start = new CountDownLatch( 1 );
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        try {
            for( int i = 0; i < threads; i++ ) {
                pool.submit( () -> {
                    final CommonProfile p = new CommonProfile();
                    p.setId( "subject-123" );
                    p.addAttribute( "name", "Race User" );
                    try {
                        start.await();
                        new SSOAutoProvisionService( engine, cfg )
                            .provisionIfNeeded( "subject-123", "subject-123", p );
                    } catch( final InterruptedException ignored ) {
                        Thread.currentThread().interrupt();
                    }
                } );
            }
            start.countDown();
            pool.shutdown();
            Assertions.assertTrue( pool.awaitTermination( 30, TimeUnit.SECONDS ) );
        } finally {
            pool.shutdownNow();
        }

        // Must resolve to a single, intact profile — no duplicate, no corruption.
        final UserProfile created = userDb.findByLoginName( "subject-123" );
        Assertions.assertNotNull( created );
        Assertions.assertEquals( "subject-123", created.getLoginName() );
    }
```

- [ ] **Step 2: Run it**

Run: `mvn test -pl wikantik-main -Dtest=SSOAutoProvisionServiceEdgeCasesTest#concurrentFirstLoginsProvisionExactlyOnce -q`
Expected: PASS or FAIL. If it FAILS (duplicate-save exception surfaces or the lookup throws), proceed to Step 3. If it PASSES, the store already serialises — keep the test as a regression guard and skip Step 3 (note "already safe" in the commit).

- [ ] **Step 3: Make provisioning idempotent under races**

In `SSOAutoProvisionService.java`, wrap the save so a concurrent create is
treated as success. Replace the `try { ... userDb.save( profile ); ... }` block's
`catch` clause with:

```java
        } catch( final WikiSecurityException e ) {
            // A concurrent first-login may have created the row between our
            // existence check and save. Re-check; if it now exists, the race
            // resolved correctly and this is not an error.
            try {
                userDb.findByLoginName( loginName );
                LOG.debug( "SSO user {} was provisioned concurrently; treating as success.", loginName );
            } catch( final NoSuchPrincipalException stillMissing ) {
                LOG.error( "Failed to auto-provision user profile for SSO user: {}", loginName, e );
            }
        }
```

- [ ] **Step 4: Run it; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOAutoProvisionServiceEdgeCasesTest -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOAutoProvisionServiceEdgeCasesTest.java \
        wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java
git commit -m "fix(sso): treat concurrent provisioning create as success, not error

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: SAML config construction & `both`-mode client ordering (#7, #8)

**Files:**
- Test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOConfigSamlTest.java` (create)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSORedirectServletClientSelectionTest.java` (create)

These confirm existing behaviour and pin it; no production change expected
unless an assertion fails.

- [ ] **Step 1: Write `SSOConfigSamlTest`**

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
package com.wikantik.auth.sso;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pac4j.core.client.Client;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

class SSOConfigSamlTest {

    @Test
    void samlMissingRequiredPropsProducesNoClient() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "saml" );
        // No idp metadata / SP entity id -> client build short-circuits.
        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );
        Assertions.assertTrue( cfg.getPac4jConfig().getClients().findAllClients().isEmpty(),
            "SAML with missing required props must yield zero clients (visible misconfig)." );
    }

    @Test
    void bothModeOrdersOidcBeforeSaml( @TempDir final Path tmp ) {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_SSO_TYPE, "both" );
        p.setProperty( SSOConfig.PROP_OIDC_DISCOVERY_URI, "http://localhost:8088/default/.well-known/openid-configuration" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_ID, "id" );
        p.setProperty( SSOConfig.PROP_OIDC_CLIENT_SECRET, "secret" );
        p.setProperty( SSOConfig.PROP_SAML_IDP_METADATA, tmp.resolve( "idp-metadata.xml" ).toString() );
        p.setProperty( SSOConfig.PROP_SAML_SP_ENTITY_ID, "wikantik-sp" );
        p.setProperty( SSOConfig.PROP_SAML_KEYSTORE_PATH, tmp.resolve( "sp-keystore.jks" ).toString() );
        p.setProperty( SSOConfig.PROP_SAML_KEYSTORE_PASSWORD, "changeit" );
        p.setProperty( SSOConfig.PROP_SAML_PRIVATE_KEY_PASSWORD, "changeit" );

        final SSOConfig cfg = new SSOConfig( p, "http://localhost/sso/callback" );
        final List< Client > clients = cfg.getPac4jConfig().getClients().findAllClients();
        // The OIDC client (no network needed to construct) must be first so the
        // default /sso/login (no client_name) picks it deterministically.
        Assertions.assertEquals( "OidcClient", clients.get( 0 ).getName() );
    }
}
```

Note: OIDC client construction does not contact the network (discovery is lazy).
SAML client construction with an auto-generated keystore writes to `tmp`. If
SAML construction proves to need a reachable IdP metadata file even to build,
relax the second test to assert only that `OidcClient` is present and first
among the successfully-built clients.

- [ ] **Step 2: Write `SSORedirectServletClientSelectionTest`**

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
package com.wikantik.auth.sso;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;

import java.util.Optional;

class SSORedirectServletClientSelectionTest {

    @Test
    void explicitClientNameSelectsThatClient() {
        final Clients clients = new Clients( "http://localhost/sso/callback" );
        // findClient resolves by name; verify the lookup the servlet relies on.
        final Config cfg = new Config( clients );
        cfg.getClients().setClients( java.util.List.of( namedClient( "OidcClient" ), namedClient( "SAML2Client" ) ) );

        final Optional< Client > selected = cfg.getClients().findClient( "SAML2Client" );
        Assertions.assertTrue( selected.isPresent() );
        Assertions.assertEquals( "SAML2Client", selected.get().getName() );
    }

    @Test
    void noClientNameFallsBackToFirst() {
        final Clients clients = new Clients( "http://localhost/sso/callback" );
        final Config cfg = new Config( clients );
        cfg.getClients().setClients( java.util.List.of( namedClient( "OidcClient" ), namedClient( "SAML2Client" ) ) );

        final Optional< Client > first = cfg.getClients().findAllClients().stream().findFirst();
        Assertions.assertTrue( first.isPresent() );
        Assertions.assertEquals( "OidcClient", first.get().getName() );
    }

    /** Minimal named pac4j client stub for selection-logic assertions. */
    private static Client namedClient( final String name ) {
        final org.pac4j.core.client.IndirectClient c = new org.pac4j.core.client.IndirectClient() {
            @Override protected void internalInit( final boolean forceReinit ) { /* no-op stub */ }
        };
        c.setName( name );
        c.setCallbackUrl( "http://localhost/sso/callback" );
        return c;
    }
}
```

If the anonymous `IndirectClient` subclass requires more abstract methods to
compile, satisfy them with no-op/`null`-returning bodies — these stubs exist
only to carry a name through `Clients` selection.

- [ ] **Step 3: Run both; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOConfigSamlTest,SSORedirectServletClientSelectionTest -q`
Expected: PASS. If `SSOConfigSamlTest#samlMissingRequiredPropsProducesNoClient` instead shows SAML silently succeeding, that is a defect — make `buildSamlClient` log at WARN and confirm zero clients, then re-run.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOConfigSamlTest.java \
        wikantik-main/src/test/java/com/wikantik/auth/sso/SSORedirectServletClientSelectionTest.java
git commit -m "test(sso): pin SAML config degradation and client_name selection

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: Characterise disabled-provision half-state (#6)

**Files:**
- Modify test: `wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java`

- [ ] **Step 1: Add the characterisation test**

This records the agreed behaviour: with auto-provision OFF and no local
profile, login still authenticates the principal (no local row created). This
is intended (SSO is the source of truth), so the test asserts it explicitly to
prevent silent drift.

Add to `SSOLoginModuleEdgeCasesTest.java`:

```java
    @Test
    void disabledProvisionStillAuthenticatesWithoutLocalProfile() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        // Register an SSOConfig with auto-provision disabled.
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_AUTO_PROVISION, "false" );
        SSOConfigHolder.setConfig( engine, new SSOConfig( p, "http://localhost/sso/callback" ) );

        final CommonProfile profile = new CommonProfile();
        profile.setId( "ephemeral-user" );
        profile.addAttribute( "sub", "ephemeral-user" );

        final SSOLoginModule module = new SSOLoginModule();
        final Subject subject = new Subject();
        module.initialize( subject, new WebContainerCallbackHandler( engine, requestWithProfile( profile ) ),
                new HashMap<>(), Map.of( SSOLoginModule.OPTION_CLAIM_LOGIN_NAME, "sub" ) );

        Assertions.assertTrue( module.login(), "SSO authenticates even when provisioning is disabled." );
        Assertions.assertThrows( com.wikantik.auth.NoSuchPrincipalException.class,
            () -> engine.getManager( com.wikantik.auth.UserManager.class ).getUserDatabase()
                       .findByLoginName( "ephemeral-user" ),
            "Disabled provisioning must not create a local profile." );
    }
```

Confirm `SSOConfigHolder.setConfig(engine, cfg)` exists (it is used in the
existing servlet tests). If the setter has a different name, run
`grep -n "setConfig\|public static" wikantik-main/src/main/java/com/wikantik/auth/sso/SSOConfigHolder.java` and use the actual signature.

- [ ] **Step 2: Run it; verify PASS**

Run: `mvn test -pl wikantik-main -Dtest=SSOLoginModuleEdgeCasesTest#disabledProvisionStillAuthenticatesWithoutLocalProfile -q`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/java/com/wikantik/auth/sso/SSOLoginModuleEdgeCasesTest.java
git commit -m "test(sso): characterise disabled-provision authenticated half-state

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: Full unit-layer verification

- [ ] **Step 1: Compile test sources (catches signature drift from Tasks 4/5)**

Run: `mvn test-compile -pl wikantik-main -q`
Expected: BUILD SUCCESS. If the `provisionIfNeeded` signature change broke any
caller, fix the caller to pass the subject (or rely on the deprecated overload).

- [ ] **Step 2: Run the whole SSO unit package**

Run: `mvn test -pl wikantik-main -Dtest='com.wikantik.auth.sso.*' -q`
Expected: PASS — all existing + new SSO unit tests green.

- [ ] **Step 3: Commit (only if Step 1 required a caller fix)**

```bash
git add -p wikantik-main/src/main/java/com/wikantik/auth/sso
git commit -m "fix(sso): update provisionIfNeeded callers for subject parameter

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 4 — OIDC callback-security integration tests

### Task 11: `SSOEdgeCaseIT` in the existing OIDC module

**Files:**
- Test: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/SSOEdgeCaseIT.java` (create)
- Modify: `wikantik-it-tests/wikantik-it-test-sso/pom.xml` (failsafe include)

- [ ] **Step 1: Broaden the failsafe include**

In `wikantik-it-test-sso/pom.xml`, change the `<includes>` block:

```xml
          <includes>
            <include>**/SSOLoginIT.java</include>
            <include>**/SSOEdgeCaseIT.java</include>
          </includes>
```

- [ ] **Step 2: Write `SSOEdgeCaseIT`**

Covers #9 (forged state, replayed/garbage code), #10 (session-fixation
JSESSIONID rotation), #1 (end-to-end account-collision refusal), and re-login
idempotency.

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
package com.wikantik.its.sso;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Callback-security and session edges for the OIDC SSO flow. Shares the
 * module's mock-oauth2-server container and SSO config with {@link SSOLoginIT}.
 */
public class SSOEdgeCaseIT extends WithIntegrationTestSetup {

    private static String baseUrl() {
        return Env.TESTS_BASE_URL;
    }

    /** #9 — a forged state parameter must fail closed to the login error page, never 500. */
    @Test
    void forgedStateFailsClosed() {
        Selenide.open( baseUrl() + "/sso/callback?code=bogus&state=forged-" + System.nanoTime() );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( d -> d.getCurrentUrl().contains( "/login" ) || d.getCurrentUrl().contains( "error=" ) );
        Assertions.assertFalse( WebDriverRunner.getWebDriver().getPageSource().contains( "HTTP Status 500" ),
            "Forged callback state must not surface a 500." );
    }

    /** #9 — a syntactically valid but unredeemable code must also fail closed. */
    @Test
    void garbageCodeFailsClosed() {
        Selenide.open( baseUrl() + "/sso/callback?code=" + "x".repeat( 40 ) + "&state=nope" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( d -> d.getCurrentUrl().contains( "/login" ) || d.getCurrentUrl().contains( "error=" ) );
    }
}
```

Note on #10 (session-fixation) and end-to-end collision: capturing the
`JSESSIONID` before/after the browser flow requires reading cookies via
`WebDriverRunner.getWebDriver().manage().getCookieNamed("JSESSIONID")`. Add a
test `sessionIdRotatesAcrossLogin` that: opens `/Main` (records the cookie),
runs the `MockOAuth2LoginPage` flow as in `SSOLoginIT`, then asserts the
post-login `JSESSIONID` value differs. If wikantik does not currently rotate
the session id, this test FAILS and exposes a fixation defect — fix by
invalidating+recreating the session in `SSOCallbackServlet` after a successful
`callbackLogic.perform(...)` and before the `login(request)` call, then
re-run. Implement this test and fix in the same task, committing the fix
separately.

- [ ] **Step 3: Run the OIDC IT module**

Run: `mvn clean install -pl wikantik-it-tests/wikantik-it-test-sso -am -Pintegration-tests -fae`
Expected: `SSOLoginIT` + `SSOEdgeCaseIT` pass. Requires a docker daemon.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/SSOEdgeCaseIT.java \
        wikantik-it-tests/wikantik-it-test-sso/pom.xml
# include SSOCallbackServlet.java too if the session-fixation fix was needed
git commit -m "test(sso): OIDC callback-security + session-fixation integration coverage

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 5 — Full SAML browser integration test (new module)

> **Risk:** this is the most flake-prone piece (SP/IdP metadata trust, clock
> skew). If, after a genuine effort, the IdP container handshake cannot be made
> reliable, STOP and report — the agreed fallback is a component-level SAML test
> using pac4j fixtures, but that is a downgrade to raise with the user, not take
> silently.

### Task 12: Scaffold the `wikantik-it-test-sso-saml` module

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-sso-saml/pom.xml`
- Create: `wikantik-it-tests/wikantik-it-test-sso-saml/src/main/resources/wikantik-custom.properties`
- Modify: `wikantik-it-tests/pom.xml` (register module)

- [ ] **Step 1: Register the module**

In `wikantik-it-tests/pom.xml`, add to `<modules>` after `wikantik-it-test-sso`:

```xml
    <module>wikantik-it-test-sso-saml</module>
```

- [ ] **Step 2: Create the module pom**

Copy `wikantik-it-test-sso/pom.xml` to the new path and change:
- `<artifactId>` → `wikantik-it-test-sso-saml`
- `<description>` → "SSO (SAML) end-to-end test against a disposable SimpleSAMLphp IdP container"
- the failsafe `<includes>` → `<include>**/SAMLLoginIT.java</include>`
- replace the `mock-oauth2-server` image block with a SAML IdP container:

```xml
            <image>
              <alias>saml-idp</alias>
              <name>kristophjunge/test-saml-idp:1.15</name>
              <run>
                <ports>
                  <port>${it-wikantik.saml-idp.port}:8080</port>
                </ports>
                <env>
                  <SIMPLESAMLPHP_SP_ENTITY_ID>wikantik-sp-it</SIMPLESAMLPHP_SP_ENTITY_ID>
                  <SIMPLESAMLPHP_SP_ASSERTION_CONSUMER_SERVICE>http://localhost:${it.wikantik.servlet.port}/${it-wikantik.context}/sso/callback</SIMPLESAMLPHP_SP_ASSERTION_CONSUMER_SERVICE>
                </env>
                <wait>
                  <http>
                    <url>http://localhost:${it-wikantik.saml-idp.port}/simplesaml/saml2/idp/metadata.php</url>
                    <method>GET</method>
                    <status>200</status>
                  </http>
                  <time>40000</time>
                </wait>
              </run>
            </image>
```

- add the port property in `<properties>`:

```xml
    <it-wikantik.saml-idp.port>8089</it-wikantik.saml-idp.port>
```

- in failsafe `<systemPropertyVariables>`, replace the mock-oauth line with:

```xml
            <it-wikantik.saml-idp.metadata>http://localhost:${it-wikantik.saml-idp.port}/simplesaml/saml2/idp/metadata.php</it-wikantik.saml-idp.metadata>
```

- [ ] **Step 3: Create the SAML `wikantik-custom.properties`**

Copy `wikantik-it-test-sso/src/main/resources/wikantik-custom.properties` and
replace the entire `--- SSO / OIDC overrides ---` section with SAML config:

```properties
wikantik.baseURL = http://localhost:${it.wikantik.servlet.port}/${it-wikantik.context}

wikantik.sso.enabled        = true
wikantik.sso.type           = saml
wikantik.sso.autoProvision  = true

# SimpleSAMLphp test IdP. pac4j fetches IdP metadata from this URL and
# auto-generates the SP keystore at the path below on first boot.
wikantik.sso.saml.identityProviderMetadataPath = http://localhost:8089/simplesaml/saml2/idp/metadata.php
wikantik.sso.saml.serviceProviderEntityId      = wikantik-sp-it
wikantik.sso.saml.keystorePath                 = ${project.basedir}/target/test-classes/sp-keystore.jks
wikantik.sso.saml.keystorePassword             = changeit
wikantik.sso.saml.privateKeyPassword           = changeit

# kristophjunge/test-saml-idp issues these attributes; map them to wiki fields.
# uid is single-valued; email arrives as a list (exercises the multi-valued fix).
wikantik.sso.identityClaim          = uid
wikantik.sso.claimMapping.loginName = uid
wikantik.sso.claimMapping.fullName  = cn
wikantik.sso.claimMapping.email     = email

wikantik.loginModule.options.sso.identityClaim  = uid
wikantik.loginModule.options.sso.claimLoginName = uid
wikantik.loginModule.options.sso.claimFullName  = cn
wikantik.loginModule.options.sso.claimEmail     = email

wikantik.urlConstructor = ShortViewURLConstructor
wikantik.shortURLConstructor.prefix = wiki/

wikantik.datasource = jdbc/WikiDatabase
```

(Keep the page-provider, log4j, and datasource lines copied from the OIDC file
above this block.)

- [ ] **Step 4: Verify the module builds (without ITs)**

Run: `mvn clean install -pl wikantik-it-tests/wikantik-it-test-sso-saml -am -DskipTests -DskipITs -q`
Expected: BUILD SUCCESS — the WAR assembles with the SAML config.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/pom.xml \
        wikantik-it-tests/wikantik-it-test-sso-saml/pom.xml \
        wikantik-it-tests/wikantik-it-test-sso-saml/src/main/resources/wikantik-custom.properties
git commit -m "test(sso): scaffold SAML end-to-end IT module with SimpleSAMLphp IdP

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 13: `SAMLLoginIT` + `SimpleSamlLoginPage`

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-sso-saml/src/test/java/com/wikantik/its/sso/SimpleSamlLoginPage.java`
- Create: `wikantik-it-tests/wikantik-it-test-sso-saml/src/test/java/com/wikantik/its/sso/SAMLLoginIT.java`

- [ ] **Step 1: Write the IdP login page object**

`kristophjunge/test-saml-idp` renders the standard SimpleSAMLphp login form
(`input[name=username]`, `input[name=password]`); built-in user
`user1` / `user1pass` carries `uid=user1`, `email=user1@example.com`,
`cn=User One` (email is multi-valued).

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
package com.wikantik.its.sso;

import com.codeborne.selenide.Condition;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/** Page object for the SimpleSAMLphp username/password login form. */
class SimpleSamlLoginPage {

    private static final Duration FORM_WAIT = Duration.ofSeconds( 15 );

    void login( final String username, final String password ) {
        $( "input[name=username]" ).shouldBe( Condition.visible, FORM_WAIT ).setValue( username );
        $( "input[name=password]" ).shouldBe( Condition.visible, FORM_WAIT ).setValue( password );
        $( "button[type=submit], input[type=submit]" ).shouldBe( Condition.visible, FORM_WAIT ).click();
    }
}
```

- [ ] **Step 2: Write `SAMLLoginIT`**

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
package com.wikantik.its.sso;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * End-to-end SAML login against a disposable SimpleSAMLphp IdP
 * (kristophjunge/test-saml-idp) started by docker-maven-plugin.
 */
public class SAMLLoginIT extends WithIntegrationTestSetup {

    private static final String SAML_IDP_BASE_URL =
        "http://localhost:" + System.getProperty( "it-wikantik.saml-idp.port", "8089" );

    private static String baseUrl() {
        return Env.TESTS_BASE_URL;
    }

    @Test
    void samlLoginAutoProvisionsAndAuthenticates() {
        final ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals( "G’day (anonymous guest)", main.authenticatedText(),
            "Baseline: anonymous session before SAML login." );

        Selenide.open( baseUrl() + "/sso/login?client_name=SAML2Client" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( d -> d.getCurrentUrl().contains( "/simplesaml/" ) );

        new SimpleSamlLoginPage().login( "user1", "user1pass" );

        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 20 ) )
            .until( d -> d.getCurrentUrl().startsWith( baseUrl() ) && !d.getCurrentUrl().contains( "/sso/" ) );

        final ViewWikiPage authed = ViewWikiPage.open( "Main" );
        $( "[data-testid=user-badge][data-authenticated=true]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        Assertions.assertEquals( "G’day, user1 (authenticated)", authed.authenticatedText(),
            "SAML uid claim must resolve to the wiki login name 'user1' (not '[user1]')." );

        authed.clickOnLogout();
        Assertions.assertEquals( "G’day (anonymous guest)", authed.authenticatedText(),
            "Logout clears the SAML-provisioned session." );
    }
}
```

This single test proves the SAML flow end-to-end AND the multi-valued-claim fix
(`email` arrives as a list; if `firstScalar` regressed, the provisioned profile
would carry `[user1@example.com]`, and `uid` resolution proves scalars survive).

- [ ] **Step 3: Run the SAML IT module**

Run: `mvn clean install -pl wikantik-it-tests/wikantik-it-test-sso-saml -am -Pintegration-tests -fae`
Expected: `SAMLLoginIT` passes. If the IdP handshake is flaky, capture the
failure (pac4j debug logging is already enabled in the copied properties),
diagnose metadata/clock-skew, and iterate. If irreducibly flaky, STOP and
report per the Phase 5 risk note.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso-saml/src/test/java/com/wikantik/its/sso/SimpleSamlLoginPage.java \
        wikantik-it-tests/wikantik-it-test-sso-saml/src/test/java/com/wikantik/its/sso/SAMLLoginIT.java
git commit -m "test(sso): full SAML browser login + auto-provision IT

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 6 — Documentation & final verification

### Task 14: Document the identity-binding config and final reactor build

**Files:**
- Modify: `CLAUDE.md` (Security Model section) — only if the team documents config knobs there; otherwise skip.

- [ ] **Step 1: Document the new config knob**

Add to the Security Model section of `CLAUDE.md` a one-line note:

```markdown
- SSO identity binding keys on `wikantik.sso.identityClaim` (default `sub`); set to `preferred_username` to trust a mutable claim. SSO never adopts a pre-existing non-SSO-linked local account of the same name.
```

- [ ] **Step 2: Full unit reactor (parallel OK, no ITs)**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Full integration reactor (sequential, fail-at-end)**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: all IT modules pass, including `wikantik-it-test-sso` and
`wikantik-it-test-sso-saml`. Requires a docker daemon. Per repo convention,
this is the pre-commit gate for production-code changes.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: note SSO identityClaim binding and collision behaviour

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** Catalog #1 (Tasks 4,5,11), #2 (Task 3 identityClaim), #3 (Task 1, proven again in Task 13), #4 (Task 2), #5 (Task 7), #6 (Task 9), #7 (Task 8), #8 (Task 8), #9 (Task 11), #10 (Task 11). SAML IT module (Tasks 12,13). All spec sections map to a task.
- **Signature change risk:** `provisionIfNeeded` gains a 3-arg form; the 2-arg deprecated overload + Task 10 Step 1 `test-compile` guard the existing callers/tests (`SSOAutoProvisionServiceTest`).
- **Type consistency:** `firstScalar`, `isSafeLoginName`, `ATTR_SSO_SUBJECT`, `OPTION_IDENTITY_CLAIM`, `PROP_SSO_IDENTITY_CLAIM`, `getIdentityClaim()` are defined once and referenced consistently.
- **Conditional-fix tasks** (7, 8, 11 session-fixation) explicitly instruct: observe failure first; if already correct, keep the test as a regression guard.

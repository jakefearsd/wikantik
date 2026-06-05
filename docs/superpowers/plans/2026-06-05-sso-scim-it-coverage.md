# SSO + SCIM Integration-Test Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the federated-identity IT gaps — prove deactivated SCIM users cannot authenticate and that real Okta/Entra payloads are accepted; replace both SSO protocol mocks with one Keycloak; add an opt-in Authentik full-loop SCIM test; and extend `bin/run-tests.sh` with output routing and the new module/profile.

**Architecture:** All work lives under `wikantik-it-tests/` plus `bin/run-tests.sh`. Phase 1 extends the existing REST IT module (no new module). Phases 2–3 swap the SSO module's IdP container to Keycloak under a green-before-delete sequence. Phase 4 extends the test runner. Phase 5 adds a new opt-in IT module driven by a real Authentik IdP. Every new container follows the repo's `docker-maven-plugin` + `build-helper` reserved-port + per-module-container-name + stale-cleanup pattern (NOT Testcontainers).

**Tech Stack:** Java 21, JUnit 5, `java.net.http.HttpClient` (existing SCIM IT harness), Selenide (SSO browser flows), Maven failsafe + cargo-maven3 + docker-maven-plugin (io.fabric8) + build-helper, Keycloak, Authentik, PostgreSQL/pgvector, Bash.

**Spec:** `docs/superpowers/specs/2026-06-05-sso-scim-it-coverage-design.md`

---

## Reference facts (read once before starting)

- IT modules live in `wikantik-it-tests/`; registered in `wikantik-it-tests/pom.xml` `<modules>` (currently: `wikantik-selenide-tests`, `wikantik-it-test-custom-jdbc`, `wikantik-it-test-rest`, `wikantik-it-test-sso`, `wikantik-it-test-sso-saml`).
- Each IT module activates its container/cargo wiring inside a `<profile><id>integration-tests</id>` block (see `wikantik-it-test-sso/pom.xml` for the full pattern: `build-helper` reserves a host port at `generate-resources`, `docker-maven-plugin` starts pgvector + the IdP at `pre-integration-test`, failsafe lists the IT classes, cargo boots the WAR).
- The SCIM bearer token is injected into the Cargo JVM via `-Dwikantik.scim.token=it-scim-token` (the IT token value used everywhere is `it-scim-token`). The existing `ScimUsersIT` hardcodes `SCIM_TOKEN = "it-scim-token"`.
- `ScimUserResource` accepts an optional `password` on create (`wikantik-scim/.../ScimUserResource.java:200-202`); when present it is set on the principal, otherwise a random UUID password is generated. So a SCIM-created user **can** be given a known password and then used to log in.
- The login endpoint is `POST /api/auth/login` with JSON body `{"username":"<login>","password":"<pw>"}`; 200 on success. Admin seed user is `janne` / `myP@5sw0rd`.
- Failsafe in `wikantik-it-test-rest` already discovers `**/*IT.java`, so new `*IT.java` classes auto-run — no pom change needed for Phase 1.
- Run one IT module locally: `bin/run-tests.sh --unit && bin/run-tests.sh --module rest` (unit phase installs the WAR; module phase runs that module's ITs). Requires a docker daemon.

---

## File Structure

**Phase 1 (Approach A) — `wikantik-it-test-rest`:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimDeactivationAuthIT.java` — deactivated user cannot authenticate.
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimVendorPayloadIT.java` — replays real Okta/Entra payloads.
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/resources/scim-samples/{okta-create-user.json, okta-deactivate-patch.json, entra-create-user.json}`.

**Phase 2–3 (Approach 2) — `wikantik-it-test-sso`:**
- Create: `wikantik-it-tests/wikantik-it-test-sso/src/test/resources/keycloak/wikantik-realm.json` — realm export (OIDC client, later SAML client, test user, multi-valued attribute mapper).
- Modify: `wikantik-it-tests/wikantik-it-test-sso/pom.xml` — swap mock image → Keycloak; rename port property; mount realm import.
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties` — point OIDC/SAML config at Keycloak.
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/MockOAuth2LoginPage.java` → replace with a Keycloak login page object.
- Move into `-sso`: `SAMLLoginIT.java` + `SimpleSamlLoginPage.java` (ported to Keycloak SAML) from `wikantik-it-test-sso-saml`.
- Delete: module `wikantik-it-test-sso-saml` (after SAML is green under Keycloak), and its registration in `wikantik-it-tests/pom.xml`.

**Phase 4 — runner:**
- Modify: `bin/run-tests.sh`.

**Phase 5 (Approach B) — new module:**
- Create: `wikantik-it-tests/wikantik-it-test-scim-fullloop/` (pom + IT + Authentik bootstrap config).
- Modify: `wikantik-it-tests/pom.xml` — register the module only under a new `scim-fullloop` profile.

---

# Phase 1 — Approach A: extend the SCIM ITs (default gate)

## Task 1: Deactivated SCIM user cannot authenticate

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimDeactivationAuthIT.java`

- [ ] **Step 1: Write the failing test**

Self-contained (no shared ordered state). Creates a SCIM user *with a known password*, proves it can log in, deactivates it via SCIM, then proves login is rejected. Mirrors the bearer-token + secure-cookie harness already used by `ScimUsersIT`.

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.google.gson.JsonParser.parseString;

/**
 * Proves the enterprise account-disabling contract end-to-end: a SCIM user
 * deactivated via {@code PATCH active:false} can no longer authenticate.
 * The existing {@link ScimUsersIT} asserts the {@code active} flag flips and an
 * audit row is written; this test closes the loop by attempting an actual login.
 */
public class ScimDeactivationAuthIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final String SCIM_CONTENT_TYPE = "application/scim+json";
    private static final String USER_NAME = "scim-deact-user";
    // Must satisfy NIST 800-63B validation and not be on the common-password
    // blocklist. If create returns 400 on the password, swap for another strong
    // non-dictionary value.
    private static final String PASSWORD = "Wk-Sc1m-9173x!";
    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
    }

    private HttpResponse<String> scimPost( final String path, final String body )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", SCIM_CONTENT_TYPE )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimPatch( final String path, final String body )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", SCIM_CONTENT_TYPE )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .method( "PATCH", HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimGet( final String path )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .GET().build(), HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimDelete( final String path )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .DELETE().build(), HttpResponse.BodyHandlers.ofString() );
    }

    /** Fresh client each time — login must not piggyback an earlier session. */
    private int loginStatus( final String user, final String pw )
            throws IOException, InterruptedException {
        final HttpClient fresh = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL ).build();
        final String body = GSON.toJson( Map.of( "username", user, "password", pw ) );
        return fresh.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() ).statusCode();
    }

    private void deleteIfExists() throws IOException, InterruptedException {
        final HttpResponse<String> existing = scimGet( "/scim/v2/Users?filter="
                + URLEncoder.encode( "userName eq \"" + USER_NAME + "\"", StandardCharsets.UTF_8 ) );
        if ( existing.statusCode() == 200 ) {
            final var arr = parseString( existing.body() ).getAsJsonObject().getAsJsonArray( "Resources" );
            if ( arr != null ) {
                for ( final var el : arr ) {
                    scimDelete( "/scim/v2/Users/" + el.getAsJsonObject().get( "id" ).getAsString() );
                }
            }
        }
    }

    @Test
    void deactivated_user_cannot_authenticate() throws Exception {
        deleteIfExists();

        // 1. Provision a SCIM user WITH a known password.
        final String createBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"" + USER_NAME + "\","
                + "\"name\":{\"formatted\":\"SCIM Deactivation User\"},"
                + "\"emails\":[{\"value\":\"scim-deact@example.com\",\"primary\":true}],"
                + "\"password\":\"" + PASSWORD + "\","
                + "\"active\":true"
                + "}";
        final HttpResponse<String> create = scimPost( "/scim/v2/Users", createBody );
        assertEquals( 201, create.statusCode(), "create should be 201: " + create.body() );
        final String id = parseString( create.body() ).getAsJsonObject().get( "id" ).getAsString();

        // 2. Baseline: the active user CAN authenticate.
        assertEquals( 200, loginStatus( USER_NAME, PASSWORD ),
                "active SCIM user must be able to log in" );

        // 3. Deactivate via SCIM.
        final String patch = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}";
        assertEquals( 200, scimPatch( "/scim/v2/Users/" + id, patch ).statusCode(),
                "deactivate PATCH should be 200" );

        // 4. The deactivated user must NOT be able to authenticate.
        final int after = loginStatus( USER_NAME, PASSWORD );
        assertNotEquals( 200, after, "deactivated user must not be able to log in (got 200)" );
        assertTrue( after == 401 || after == 403,
                "deactivated login should be 401/403, got: " + after );

        // Cleanup.
        scimDelete( "/scim/v2/Users/" + id );
    }
}
```

- [ ] **Step 2: Run it to verify it fails first**

The wiring (`baseUrl`, container) only exists when the module's IT profile runs. Run:

```bash
bin/run-tests.sh --unit && bin/run-tests.sh --module rest
```

Expected on first run: `ScimDeactivationAuthIT` is discovered and runs. If the wiki does NOT currently block deactivated logins, Step 4 fails (`after == 200`) — that is a **real production bug surfaced by the test**; stop and report it per the spec ("bugs found are filed/fixed separately"). If it already blocks, the test passes immediately, which is acceptable for a coverage-closing test (note it in the commit). Either way, confirm the test compiles and executes.

- [ ] **Step 3: If the test surfaces a production bug, STOP and report**

Do not paper over a failing deactivation check inside the test. Report: "deactivated SCIM user can still authenticate — login returned 200 after `active:false`." Await direction. If it passes, continue.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimDeactivationAuthIT.java
git commit -m "test(scim-it): assert deactivated SCIM user cannot authenticate

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 2: Vendor sample-payload fixtures

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/resources/scim-samples/okta-create-user.json`
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/resources/scim-samples/okta-deactivate-patch.json`
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/resources/scim-samples/entra-create-user.json`

- [ ] **Step 1: Add the Okta create-user payload**

Real Okta SCIM create shape (givenName/familyName split, `type:"work"` email, no password — Okta is SSO-only so the wiki generates a random password):

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
  "userName": "okta.sample@example.com",
  "name": { "givenName": "Okta", "familyName": "Sample" },
  "emails": [{ "primary": true, "value": "okta.sample@example.com", "type": "work" }],
  "displayName": "Okta Sample",
  "locale": "en-US",
  "externalId": "00uOktaSample001",
  "groups": [],
  "active": true
}
```

- [ ] **Step 2: Add the Okta deactivate PATCH payload**

Okta's historical no-`path`, `value`-object replace shape — the highest-value real-world divergence from a hand-rolled `path:"active"` PATCH:

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [{ "op": "replace", "value": { "active": false } }]
}
```

- [ ] **Step 3: Add the Entra create-user payload (enterprise extension)**

Real Microsoft Entra shape, including the enterprise extension schema:

```json
{
  "schemas": [
    "urn:ietf:params:scim:schemas:core:2.0:User",
    "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"
  ],
  "userName": "entra.sample@example.com",
  "name": { "givenName": "Entra", "familyName": "Sample" },
  "active": true,
  "emails": [{ "primary": true, "type": "work", "value": "entra.sample@example.com" }],
  "externalId": "8f5e2c1a-entra-sample",
  "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {
    "employeeNumber": "701984",
    "department": "Engineering"
  }
}
```

- [ ] **Step 4: Commit the fixtures**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/resources/scim-samples/
git commit -m "test(scim-it): add real Okta/Entra SCIM sample payloads

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 3: Replay vendor payloads against the live endpoint

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimVendorPayloadIT.java`

- [ ] **Step 1: Write the failing test**

Loads each create fixture from the classpath, replays it, asserts 201 + `active:true`; then for the Okta user applies the Okta no-`path` deactivate PATCH and asserts 200 + `active:false`. Proves the wiki accepts real vendor shapes (Okta no-path replace, Entra enterprise-extension schema).

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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static com.google.gson.JsonParser.parseString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays real Okta / Microsoft Entra SCIM request bodies (committed under
 * {@code src/test/resources/scim-samples/}) against the live {@code /scim/v2}
 * endpoint, proving the service-provider accepts genuine vendor shapes — Okta's
 * no-{@code path} {@code replace} PATCH and Entra's enterprise-extension create —
 * not merely idealized hand-rolled payloads.
 */
public class ScimVendorPayloadIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final String CT = "application/scim+json";
    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
    }

    private String fixture( final String name ) throws IOException {
        try ( var in = getClass().getResourceAsStream( "/scim-samples/" + name ) ) {
            if ( in == null ) throw new IOException( "missing fixture: " + name );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    private HttpResponse<String> send( final String method, final String path, final String body )
            throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", CT )
                .header( "Authorization", "Bearer " + SCIM_TOKEN );
        if ( body != null ) {
            b.header( "Content-Type", CT );
            b.method( method, HttpRequest.BodyPublishers.ofString( body ) );
        } else {
            b.method( method, HttpRequest.BodyPublishers.noBody() );
        }
        return client.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private void deleteByUserName( final String userName ) throws IOException, InterruptedException {
        final HttpResponse<String> existing = send( "GET", "/scim/v2/Users?filter="
                + URLEncoder.encode( "userName eq \"" + userName + "\"", StandardCharsets.UTF_8 ), null );
        if ( existing.statusCode() == 200 ) {
            final var arr = parseString( existing.body() ).getAsJsonObject().getAsJsonArray( "Resources" );
            if ( arr != null ) {
                for ( final var el : arr ) {
                    send( "DELETE", "/scim/v2/Users/" + el.getAsJsonObject().get( "id" ).getAsString(), null );
                }
            }
        }
    }

    private String createAndAssert( final String fixtureFile, final String userName ) throws Exception {
        deleteByUserName( userName );
        final HttpResponse<String> resp = send( "POST", "/scim/v2/Users", fixture( fixtureFile ) );
        assertEquals( 201, resp.statusCode(),
                fixtureFile + " should create (201): " + resp.body() );
        final var body = parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.get( "active" ).getAsBoolean(), fixtureFile + " active must be true" );
        return body.get( "id" ).getAsString();
    }

    @Test
    void okta_create_then_no_path_deactivate_patch() throws Exception {
        final String id = createAndAssert( "okta-create-user.json", "okta.sample@example.com" );
        // Okta's no-`path` replace shape must deactivate the user.
        final HttpResponse<String> patch = send( "PATCH", "/scim/v2/Users/" + id,
                fixture( "okta-deactivate-patch.json" ) );
        assertEquals( 200, patch.statusCode(), "okta deactivate PATCH should be 200: " + patch.body() );
        assertFalse( parseString( patch.body() ).getAsJsonObject().get( "active" ).getAsBoolean(),
                "okta no-path PATCH must set active:false" );
        send( "DELETE", "/scim/v2/Users/" + id, null );
    }

    @Test
    void entra_enterprise_extension_create() throws Exception {
        final String id = createAndAssert( "entra-create-user.json", "entra.sample@example.com" );
        send( "DELETE", "/scim/v2/Users/" + id, null );
    }
}
```

- [ ] **Step 2: Run to verify**

```bash
bin/run-tests.sh --unit && bin/run-tests.sh --module rest
```

Expected: both methods PASS. If `okta_create_then_no_path_deactivate_patch` fails at the PATCH (the no-`path` replace isn't handled), that is a real `ScimPatchApplier` gap — STOP and report, do not loosen the assertion. If `entra_enterprise_extension_create` fails because the extension schema is rejected, likewise report.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ScimVendorPayloadIT.java
git commit -m "test(scim-it): replay real Okta/Entra SCIM payloads against /scim/v2

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: Phase-1 gate — full rest module green**

```bash
bin/run-tests.sh --unit && bin/run-tests.sh --module rest
```

Expected: BUILD SUCCESS, all rest ITs (existing + 3 new methods across 2 new classes) pass.

---

# Phase 2 — Approach 2: Keycloak replaces the OIDC mock

> Green-before-delete: do not remove `mock-oauth2-server` until the OIDC ITs pass against Keycloak.

## Task 4: Add a Keycloak realm export with an OIDC client

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-sso/src/test/resources/keycloak/wikantik-realm.json`

- [ ] **Step 1: Author the realm export**

Create a realm `wikantik-it` containing: a confidential OIDC client `wikantik-oidc` with a fixed `clientId`/`clientSecret`, redirect URI `http://localhost:*/wikantik-it-test-sso/sso/callback`, standard flow enabled; and one test user `oidc-testuser` with a password and email. Keep it minimal — Keycloak fills defaults on import.

```json
{
  "realm": "wikantik-it",
  "enabled": true,
  "sslRequired": "none",
  "clients": [
    {
      "clientId": "wikantik-oidc",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "secret": "wikantik-oidc-secret",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "redirectUris": ["http://localhost:*/wikantik-it-test-sso/*"],
      "webOrigins": ["+"]
    }
  ],
  "users": [
    {
      "username": "oidc-testuser",
      "enabled": true,
      "email": "oidc-testuser@example.com",
      "emailVerified": true,
      "firstName": "OIDC",
      "lastName": "Tester",
      "credentials": [{ "type": "password", "value": "testpass", "temporary": false }]
    }
  ]
}
```

> Note: the realm/client/redirect values here are the **contract** the test relies on. They are verified by the IT going green in Task 6; if Keycloak rejects any field on import, adjust here (Keycloak's import is strict about a few fields) and re-run — do not change the contract the test asserts (realm name, clientId, secret, username/password) without updating both sides together.

- [ ] **Step 2: Commit the realm export**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/src/test/resources/keycloak/wikantik-realm.json
git commit -m "test(sso-it): add Keycloak realm export (OIDC client + test user)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 5: Wire the Keycloak container into the SSO module pom

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/pom.xml`

- [ ] **Step 1: Replace the mock image property with a Keycloak image**

In `<properties>`, replace the `it-wikantik.mock-oauth.image` line with:

```xml
    <!-- Keycloak host port reserved dynamically by build-helper
         (reserve-keycloak-port); NO static default (would shadow the
         reservation in the effective POM, breaking parallel runs). -->
    <it-wikantik.keycloak.image>quay.io/keycloak/keycloak:26.0</it-wikantik.keycloak.image>
```

- [ ] **Step 2: Rename the reserved-port execution**

In the `build-helper-maven-plugin` execution, change `<id>reserve-mock-oauth-port</id>` to `<id>reserve-keycloak-port</id>` and the `<portName>` from `it-wikantik.mock-oauth.port` to `it-wikantik.keycloak.port`.

- [ ] **Step 3: Replace the mock image block with a Keycloak image block**

In `docker-maven-plugin` `<images>`, replace the `mock-oauth2-server` `<image>` element with a Keycloak image that imports the realm and waits on its health endpoint:

```xml
            <image>
              <alias>keycloak</alias>
              <name>${it-wikantik.keycloak.image}</name>
              <run>
                <cmd>start-dev --import-realm --http-port=8080</cmd>
                <env>
                  <KEYCLOAK_ADMIN>admin</KEYCLOAK_ADMIN>
                  <KEYCLOAK_ADMIN_PASSWORD>admin</KEYCLOAK_ADMIN_PASSWORD>
                  <KC_HEALTH_ENABLED>true</KC_HEALTH_ENABLED>
                </env>
                <ports>
                  <port>${it-wikantik.keycloak.port}:8080</port>
                </ports>
                <volumes>
                  <bind>
                    <volume>${project.basedir}/src/test/resources/keycloak:/opt/keycloak/data/import:ro</volume>
                  </bind>
                </volumes>
                <wait>
                  <http>
                    <url>http://localhost:${it-wikantik.keycloak.port}/realms/wikantik-it/.well-known/openid-configuration</url>
                    <method>GET</method>
                    <status>200</status>
                  </http>
                  <time>90000</time>
                </wait>
              </run>
            </image>
```

> Keycloak's first-boot + realm import is slower than the mock; the 90s wait reflects that. The OIDC discovery URL becoming 200 is the readiness signal the WAR also depends on.

- [ ] **Step 4: Update the failsafe system property**

Change the `it-wikantik.mock-oauth.base-url` system property to:

```xml
            <it-wikantik.oidc.issuer>http://localhost:${it-wikantik.keycloak.port}/realms/wikantik-it</it-wikantik.oidc.issuer>
```

- [ ] **Step 5: Update the stale-container cleanup exec (if present in this module)**

If this module has an `exec-maven-plugin` cleanup step targeting the mock image/port (mirror of the SAML module), repoint its `ancestor=`/`publish=` filters to `${it-wikantik.keycloak.image}` / `${it-wikantik.keycloak.port}`. If the module relies only on the inherited cleanup, skip.

- [ ] **Step 6: Verify the pom parses**

```bash
mvn -q -pl wikantik-it-tests/wikantik-it-test-sso help:effective-pom -Pintegration-tests > /dev/null && echo POM-OK
```

Expected: `POM-OK` (no XML/profile errors). This does not start containers.

- [ ] **Step 7: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/pom.xml
git commit -m "test(sso-it): boot Keycloak (OIDC) container in place of mock-oauth2-server

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 6: Point the WAR's OIDC config at Keycloak and update the login page object

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties`
- Create: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/KeycloakLoginPage.java`
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/SSOLoginIT.java`

- [ ] **Step 1: Repoint OIDC properties at the Keycloak realm**

In `wikantik-custom.properties`, set the SSO OIDC properties to the Keycloak realm. The discovery URI and client must match the realm export (Task 4). The port is filtered in from `${it-wikantik.keycloak.port}` (Maven resource filtering is already enabled for this module's resources):

```properties
wikantik.sso.enabled = true
wikantik.sso.type = oidc
wikantik.sso.oidc.discoveryUri = http://localhost:${it-wikantik.keycloak.port}/realms/wikantik-it/.well-known/openid-configuration
wikantik.sso.oidc.clientId = wikantik-oidc
wikantik.sso.oidc.clientSecret = wikantik-oidc-secret
wikantik.sso.oidc.scope = openid profile email
wikantik.sso.autoProvision = true
wikantik.sso.claimMapping.loginName = preferred_username
wikantik.sso.claimMapping.fullName = name
wikantik.sso.claimMapping.email = email
```

> Keep whatever non-SSO properties the file already had. Only add/replace the `wikantik.sso.*` block. If the file previously pointed at the mock with `sub`-based identity, switching `loginName` to `preferred_username` means the provisioned login name becomes `oidc-testuser` (the Keycloak username) — Step 3 updates the assertion to match.

- [ ] **Step 2: Write the Keycloak login page object**

Replace the mock debugger-form interaction with Keycloak's login form (`#username`, `#password`, `#kc-login`):

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

import static com.codeborne.selenide.Selenide.$;

/** Drives the Keycloak login form during the OIDC IT. */
public class KeycloakLoginPage {

    /** Fills Keycloak's username/password form and submits. */
    public void submit( final String username, final String password ) {
        $( "#username" ).shouldBe( Condition.visible ).setValue( username );
        $( "#password" ).shouldBe( Condition.visible ).setValue( password );
        $( "#kc-login" ).click();
    }
}
```

- [ ] **Step 3: Update `SSOLoginIT` to use the Keycloak page + username identity**

In `SSOLoginIT.java`: change `SSO_SUBJECT` to the Keycloak username `oidc-testuser`, replace `new MockOAuth2LoginPage().submit( SSO_SUBJECT )` with `new KeycloakLoginPage().submit( "oidc-testuser", "testpass" )`, replace the `MOCK_IDP_BASE_URL` system-property read with `it-wikantik.oidc.issuer`, and keep the post-login assertions (the greeted name becomes `oidc-testuser`). Concretely:

- Replace:
  ```java
  private static final String MOCK_IDP_BASE_URL =
      System.getProperty( "it-wikantik.mock-oauth.base-url", "http://localhost:8088" );
  ```
  with:
  ```java
  private static final String IDP_ISSUER =
      System.getProperty( "it-wikantik.oidc.issuer", "http://localhost:8088/realms/wikantik-it" );
  ```
- Replace the two `MOCK_IDP_BASE_URL` uses in the redirect-wait with `IDP_ISSUER`.
- Replace `new MockOAuth2LoginPage().submit( SSO_SUBJECT );` with `new KeycloakLoginPage().submit( SSO_SUBJECT, "testpass" );` and set `SSO_SUBJECT = "oidc-testuser"`.

- [ ] **Step 4: Run the OIDC ITs against Keycloak**

```bash
bin/run-tests.sh --unit && bin/run-tests.sh --module sso
```

Expected: `SSOLoginIT` + `SSOEdgeCaseIT` PASS against Keycloak. Iterate on the realm export / login page selectors until green (this is the expected container-iteration loop). The edge-case tests (`forgedState`, `garbageCode`, no-`code`, session rotation) should pass unchanged since they don't touch the IdP form.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties \
        wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/KeycloakLoginPage.java \
        wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/SSOLoginIT.java
git commit -m "test(sso-it): drive OIDC flow against Keycloak

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 7: Delete the OIDC mock now that Keycloak is green

**Files:**
- Delete: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/MockOAuth2LoginPage.java`

- [ ] **Step 1: Remove the mock page object**

```bash
git rm wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/MockOAuth2LoginPage.java
```

- [ ] **Step 2: Confirm no stale references to the mock remain**

```bash
grep -rn "mock-oauth\|MockOAuth2\|navikt" wikantik-it-tests/wikantik-it-test-sso
```

Expected: no output.

- [ ] **Step 3: Re-run the module + commit**

```bash
bin/run-tests.sh --module sso
git commit -am "test(sso-it): drop mock-oauth2-server (replaced by Keycloak)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

# Phase 3 — Approach 2: Keycloak SAML, retire SimpleSAMLphp

> Green-before-delete again: SAML ITs pass against Keycloak before deleting the `-sso-saml` module.

## Task 8: Add a SAML client + multi-valued attribute to the realm

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/test/resources/keycloak/wikantik-realm.json`

- [ ] **Step 1: Add a SAML client and a multi-valued `uid` attribute mapper**

Append to `clients`: a SAML client `wikantik-saml` (entityId matching the WAR's SP entity id) with an attribute-mapper that emits a **multi-valued** `uid` attribute, so the `firstScalar()` unwrap is exercised exactly as SimpleSAMLphp did (`uid=['1']`). Add the `uid` values to the `oidc-testuser` (or a dedicated `saml-testuser`) user attributes.

```json
{
  "clientId": "https://localhost/wikantik-it-test-sso",
  "protocol": "saml",
  "enabled": true,
  "fullScopeAllowed": true,
  "redirectUris": ["http://localhost:*/wikantik-it-test-sso/*"],
  "attributes": {
    "saml.assertion.signature": "true",
    "saml.client.signature": "false",
    "saml_name_id_format": "username"
  },
  "protocolMappers": [
    {
      "name": "uid-multivalued",
      "protocol": "saml",
      "protocolMapper": "saml-user-attribute-mapper",
      "config": {
        "attribute.name": "uid",
        "user.attribute": "uid",
        "attribute.nameformat": "Basic",
        "aggregate.attrs": "true"
      }
    }
  ]
}
```

And give the test user multi-valued `uid`:

```json
"attributes": { "uid": ["1", "extra"] }
```

> Contract: the SAML assertion must deliver `uid` as a multi-valued attribute whose first scalar is `"1"`, so the existing `firstScalar()` assertion (provisioned login name == `"1"`) holds. `aggregate.attrs:true` is what makes Keycloak emit all values. Verify by Task 10 going green; adjust mapper config if the assertion only carries one value.

- [ ] **Step 2: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/src/test/resources/keycloak/wikantik-realm.json
git commit -m "test(sso-it): add Keycloak SAML client + multi-valued uid mapper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 9: Configure the WAR for SAML against Keycloak and move the SAML IT in

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties`
- Modify: `wikantik-it-tests/wikantik-it-test-sso/pom.xml`
- Create: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/SAMLLoginIT.java` (ported from `-sso-saml`)
- Create: `wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/KeycloakSamlLoginPage.java`

- [ ] **Step 1: Set SSO type to `both` and add SAML metadata config**

Change `wikantik.sso.type = oidc` → `wikantik.sso.type = both`, and add the SAML properties pointing at Keycloak's SAML IdP descriptor (Keycloak serves it at `/realms/wikantik-it/protocol/saml/descriptor`). Provide the SP entity id matching the SAML client's `clientId` and a keystore path (reuse the existing SAML keystore from the `-sso-saml` module — copy it into this module's resources):

```properties
wikantik.sso.type = both
wikantik.sso.saml.identityProviderMetadataPath = http://localhost:${it-wikantik.keycloak.port}/realms/wikantik-it/protocol/saml/descriptor
wikantik.sso.saml.serviceProviderEntityId = https://localhost/wikantik-it-test-sso
wikantik.sso.saml.keystorePath = ${project.basedir}/src/test/resources/saml/samlKeystore.jks
wikantik.sso.saml.keystorePassword = changeit
wikantik.sso.saml.privateKeyPassword = changeit
wikantik.sso.claimMapping.loginName = uid
```

> Copy `samlKeystore.jks` from `wikantik-it-test-sso-saml/src/test/resources/saml/` (or wherever it currently lives) into `wikantik-it-test-sso/src/test/resources/saml/`. Find it with: `find wikantik-it-tests/wikantik-it-test-sso-saml -name '*.jks'`.

- [ ] **Step 2: Add the SAML IT to the failsafe includes**

In `pom.xml` failsafe config, add `<include>**/SAMLLoginIT.java</include>` alongside the existing OIDC includes.

- [ ] **Step 3: Port `SAMLLoginIT` + login page object**

Copy `SAMLLoginIT.java` from `-sso-saml` into the `-sso` module's `com.wikantik.its.sso` package. Replace its SimpleSAMLphp page object usage with a `KeycloakSamlLoginPage` (same `#username`/`#password`/`#kc-login` selectors as `KeycloakLoginPage` — Keycloak's SAML login form is identical). Keep the load-bearing assertion intact: provisioned login name equals the first scalar of the multi-valued `uid` (`"1"`), with no brackets.

```java
/* (ASF header — copy verbatim from KeycloakLoginPage.java) */
package com.wikantik.its.sso;

import com.codeborne.selenide.Condition;
import static com.codeborne.selenide.Selenide.$;

/** Keycloak's SAML login form is the same login theme as OIDC. */
public class KeycloakSamlLoginPage {
    public void submit( final String username, final String password ) {
        $( "#username" ).shouldBe( Condition.visible ).setValue( username );
        $( "#password" ).shouldBe( Condition.visible ).setValue( password );
        $( "#kc-login" ).click();
    }
}
```

> In the ported `SAMLLoginIT`, the user logs in with the Keycloak username/password (`oidc-testuser`/`testpass` or the dedicated SAML user) and the assertion checks the provisioned login name == `"1"` (first scalar of `uid`). Keep the original brackets-free assertion comments.

- [ ] **Step 4: Run OIDC + SAML ITs against Keycloak**

```bash
bin/run-tests.sh --unit && bin/run-tests.sh --module sso
```

Expected: `SSOLoginIT`, `SSOEdgeCaseIT`, `SAMLLoginIT` all PASS against the single Keycloak. Iterate on the SAML mapper/keystore/entityId until the `uid` firstScalar assertion is green. **If the multi-valued SAML attribute proves intractable**, invoke the documented fallback: keep `-sso-saml` (SimpleSAMLphp) as-is, skip Tasks 9–11's SAML deletion, and stop after Phase 2 for the SSO side. Report which path was taken.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties \
        wikantik-it-tests/wikantik-it-test-sso/pom.xml \
        wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/SAMLLoginIT.java \
        wikantik-it-tests/wikantik-it-test-sso/src/test/java/com/wikantik/its/sso/KeycloakSamlLoginPage.java \
        wikantik-it-tests/wikantik-it-test-sso/src/test/resources/saml/
git commit -m "test(sso-it): drive SAML flow against Keycloak (multi-valued uid preserved)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 10: Delete the `-sso-saml` module

**Files:**
- Delete: `wikantik-it-tests/wikantik-it-test-sso-saml/` (whole module)
- Modify: `wikantik-it-tests/pom.xml`

- [ ] **Step 1: Remove the module dir and its registration**

```bash
git rm -r wikantik-it-tests/wikantik-it-test-sso-saml
```

Then edit `wikantik-it-tests/pom.xml` `<modules>` and delete the line `<module>wikantik-it-test-sso-saml</module>`.

- [ ] **Step 2: Confirm nothing else references it**

```bash
grep -rn "sso-saml\|SimpleSaml\|kristophjunge" wikantik-it-tests bin/run-tests.sh
```

Expected: only `bin/run-tests.sh` may still list it (fixed in Phase 4) — no pom or Java references.

- [ ] **Step 3: Verify the reactor still resolves + SSO module green**

```bash
mvn -q -pl wikantik-it-tests help:effective-pom > /dev/null && echo REACTOR-OK
bin/run-tests.sh --module sso
```

Expected: `REACTOR-OK` and SSO module BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git commit -am "test(sso-it): remove wikantik-it-test-sso-saml (folded into -sso via Keycloak)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

# Phase 4 — `bin/run-tests.sh`: output routing, default-list update, --fullloop/--list

## Task 11: Update the default module list and module-name validation

**Files:**
- Modify: `bin/run-tests.sh`

- [ ] **Step 1: Drop `sso-saml` from the default IT list**

Edit the `IT_MODULES` array — remove the `wikantik-it-tests/wikantik-it-test-sso-saml` line so it reads:

```bash
IT_MODULES=(
  "wikantik-it-tests/wikantik-it-test-rest"
  "wikantik-it-tests/wikantik-it-test-sso"
  "wikantik-it-tests/wikantik-it-test-custom-jdbc"
)
```

- [ ] **Step 2: Update the `--module` help text reference**

In the `--module` arg-parse error message, change `rest|sso|sso-saml|custom-jdbc` to `rest|sso|custom-jdbc|scim-fullloop`.

- [ ] **Step 3: Verify the script still parses**

```bash
bash -n bin/run-tests.sh && echo SYNTAX-OK
```

Expected: `SYNTAX-OK`.

- [ ] **Step 4: Commit**

```bash
git add bin/run-tests.sh
git commit -m "chore(test-runner): drop sso-saml from default IT list

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 12: Add `--output file|console|both` routing

**Files:**
- Modify: `bin/run-tests.sh`

- [ ] **Step 1: Add the `OUTPUT_MODE` variable + arg parse**

Near the other defaults (e.g. after `IT_PARALLELISM` is set), add:

```bash
# Build-output routing: file (log only, default) | console (stdout/err only) | both (tee).
OUTPUT_MODE="${OUTPUT_MODE:-file}"
```

In the arg `while`/`case` loop, add a case:

```bash
    --output|-o)
               OUTPUT_MODE="${2:-}"; shift
               case "$OUTPUT_MODE" in
                 file|console|both) ;;
                 *) echo "--output needs one of: file | console | both" >&2; exit 2 ;;
               esac ;;
```

- [ ] **Step 2: Route output in `run_step` by mode**

Replace the body of `run_step` that runs `mvn "$@" > "$log" 2>&1` with a mode-aware dispatch. Under `console`/`both`, read `mvn`'s status from `${PIPESTATUS[0]}` (not `tee`). Replace the `if mvn "$@" > "$log" 2>&1; then ... else ... fi` block with:

```bash
  local rc
  case "$OUTPUT_MODE" in
    file)
      mvn "$@" > "$log" 2>&1
      rc=$?
      ;;
    console)
      mvn "$@" 2>&1
      rc=$?
      ;;
    both)
      mvn "$@" 2>&1 | tee "$log"
      rc=${PIPESTATUS[0]}
      ;;
  esac
  if [ "$rc" -eq 0 ]; then
    dur="$(fmt_dur $(( $(date +%s) - t0 )) )"
    local summary
    summary="$(grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+' "$log" 2>/dev/null | tail -1)"
    echo "PASS  ${label}  [${dur}]   ${summary}" | tee -a "$REPORT"
  else
    overall_rc=1
    dur="$(fmt_dur $(( $(date +%s) - t0 )) )"
    local fails
    fails="$(grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])|BUILD FAILURE' "$log" 2>/dev/null | head -5)"
    echo "FAIL  ${label}  [${dur}]" | tee -a "$REPORT"
    [ -n "$fails" ] && echo "${fails}" | sed 's/^/        /' | tee -a "$REPORT"
    echo "        (log: ${log})" | tee -a "$REPORT"
  fi
```

> Under `console` mode no log file is written, so the `grep "$log"` calls are guarded with `2>/dev/null` and simply yield empty summaries — the PASS/FAIL line still prints. Remove the old `local t0 dur; t0=...` line only if you re-declare `t0` here; keep a single `t0` assignment before the `case`.

- [ ] **Step 2b: Ensure `t0` is set before the case**

Confirm `local t0 dur; t0="$(date +%s)"` remains *above* the new `case` (it currently sits just after `clean_zombies`). The `case` references `t0`; do not move it below.

- [ ] **Step 3: Smoke-test the new flag with a fast step**

```bash
bash -n bin/run-tests.sh && echo SYNTAX-OK
bin/run-tests.sh --unit -o both | tee /tmp/rt-both.log >/dev/null; echo "exit=$?"
grep -c "PASS\|FAIL" .test-suite-logs/report.txt
```

Expected: `SYNTAX-OK`; the unit phase streams to console AND writes `.test-suite-logs/phase1-unit.log`; exit code reflects the unit build.

- [ ] **Step 4: Commit**

```bash
git add bin/run-tests.sh
git commit -m "feat(test-runner): add --output file|console|both routing

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 13: Add `--fullloop`, `--list`, and a real `usage()`

**Files:**
- Modify: `bin/run-tests.sh`

- [ ] **Step 1: Add a `usage()` function**

Add near the top (after `cd "$REPO_DIR"`), replacing the `--help` `sed` hack:

```bash
usage() {
  cat <<'EOF'
Usage: bin/run-tests.sh [MODE] [OPTIONS]

MODES (default: full suite = unit, then default-gate IT modules)
  --unit                 Unit reactor only (Phase 1)
  --it                   Default-gate IT modules only (assumes --unit ran)
  --module <name>        One IT module: rest|sso|custom-jdbc|scim-fullloop
  --fullloop             Opt-in Authentik SCIM full-loop (-Pscim-fullloop)
  --list                 Show modules and their gate, then exit

OPTIONS
  --parallel N, -p N     Run default-gate IT modules in one -T N reactor
  --output MODE, -o MODE Build output routing: file (default) | console | both
  --help, -h             This help

ENVIRONMENT
  UNIT_PARALLELISM=1C    Unit-phase -T value
  IT_PARALLELISM=1       Fallback for --parallel (flag wins)

EXAMPLES
  bin/run-tests.sh                      # full deterministic gate (unit + rest,sso,custom-jdbc)
  bin/run-tests.sh --unit               # fast unit-only pass
  bin/run-tests.sh --module rest        # REST + SCIM IT only
  bin/run-tests.sh --it --parallel 4    # default IT modules, 4-way
  bin/run-tests.sh --module sso -o both # Keycloak SSO IT, output to log AND console
  bin/run-tests.sh --fullloop           # opt-in Authentik full-loop (heavy)

EXIT: 0 only if every phase/module that ran reached BUILD SUCCESS.
EOF
}
```

- [ ] **Step 2: Add a `list_modules()` function**

```bash
list_modules() {
  echo "Default gate (run by --it / no args):"
  echo "  rest         wikantik-it-tests/wikantik-it-test-rest        (REST API + SCIM)"
  echo "  sso          wikantik-it-tests/wikantik-it-test-sso         (Keycloak OIDC + SAML)"
  echo "  custom-jdbc  wikantik-it-tests/wikantik-it-test-custom-jdbc (Selenide browser suite)"
  echo
  echo "Opt-in (run only via --fullloop / --module scim-fullloop):"
  echo "  scim-fullloop  wikantik-it-tests/wikantik-it-test-scim-fullloop (Authentik SCIM full-loop)"
}
```

- [ ] **Step 3: Wire the new flags into the arg loop**

Replace the `--help` case and add `--list` / `--fullloop`. The `--help` case becomes `--help|-h) usage; exit 0 ;;`. Add:

```bash
    --list)    list_modules; exit 0 ;;
    --fullloop) RUN_UNIT=0; RUN_IT=0; ONE_MODULE="scim-fullloop" ;;
```

- [ ] **Step 4: Special-case the `scim-fullloop` module run**

The default `--module` path runs `install -Pintegration-tests -fae -pl <module>`. `scim-fullloop` additionally needs the `scim-fullloop` profile. In the `elif [ -n "$ONE_MODULE" ]` block, branch on the name:

```bash
elif [ -n "$ONE_MODULE" ]; then
  mod="wikantik-it-tests/wikantik-it-test-${ONE_MODULE}"
  [ -d "$mod" ] || { echo "no such IT module: $mod" >&2; exit 2; }
  if [ "$ONE_MODULE" = "scim-fullloop" ]; then
    run_step "IT: ${mod} (full-loop)" "${LOG_DIR}/it-${ONE_MODULE}.log" \
      install -Pintegration-tests,scim-fullloop -fae -pl "$mod"
  else
    run_step "IT: ${mod}" "${LOG_DIR}/it-${ONE_MODULE}.log" \
      install -Pintegration-tests -fae -pl "$mod"
  fi
fi
```

> The module dir won't exist until Phase 5 Task 14. Until then `--fullloop` correctly errors with "no such IT module" — acceptable. Re-verify after Phase 5.

- [ ] **Step 5: Verify help/list output**

```bash
bash -n bin/run-tests.sh && echo SYNTAX-OK
bin/run-tests.sh --help | head -5
bin/run-tests.sh --list
```

Expected: `SYNTAX-OK`; `--help` prints the structured usage; `--list` prints the gate breakdown.

- [ ] **Step 6: Commit**

```bash
git add bin/run-tests.sh
git commit -m "feat(test-runner): real --help, --list, and --fullloop (scim-fullloop profile)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

# Phase 5 — Approach B: Authentik full-loop SCIM (opt-in profile)

> This phase is the heaviest and most iterative (multi-container Authentik + async SCIM push). It runs ONLY under `-Pscim-fullloop` and is NOT part of the green gate. Build it last.

## Task 14: Scaffold the opt-in module + profile registration

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-scim-fullloop/pom.xml`
- Modify: `wikantik-it-tests/pom.xml`

- [ ] **Step 1: Register the module only under a new `scim-fullloop` profile**

In `wikantik-it-tests/pom.xml`, do NOT add the module to the always-on `<modules>`. Instead add a profile after the `integration-tests` profile:

```xml
    <profile>
      <id>scim-fullloop</id>
      <modules>
        <module>wikantik-it-test-scim-fullloop</module>
      </modules>
    </profile>
```

> A profile-scoped `<module>` means the module is only part of the reactor when `-Pscim-fullloop` is active, so the default gate never builds it.

- [ ] **Step 2: Create the module pom (templated from `-sso`)**

Copy `wikantik-it-test-sso/pom.xml` to `wikantik-it-test-scim-fullloop/pom.xml` and adapt: set `<artifactId>wikantik-it-test-scim-fullloop</artifactId>`, description "Authentik → wiki SCIM full-loop provisioning IT"; reserve ports for the Authentik stack (`it-wikantik.authentik.port`) instead of Keycloak; declare the Authentik + Redis + Authentik-Postgres images in `docker-maven-plugin` (in addition to the wiki's pgvector); set failsafe `<include>**/AuthentikScimFullLoopIT.java</include>`; pass `-Dwikantik.scim.token=it-scim-token` so the wiki accepts Authentik's pushes (cargo jvmargs already carry it via the parent). The Authentik images:

```xml
            <image>
              <alias>authentik-redis</alias>
              <name>docker.io/library/redis:7-alpine</name>
              <run><ports><port>${it-wikantik.authentik-redis.port}:6379</port></ports></run>
            </image>
            <image>
              <alias>authentik-db</alias>
              <name>${pgvector.image}</name>
              <run>
                <ports><port>${it-wikantik.authentik-db.port}:5432</port></ports>
                <env>
                  <POSTGRES_USER>authentik</POSTGRES_USER>
                  <POSTGRES_PASSWORD>authentik</POSTGRES_PASSWORD>
                  <POSTGRES_DB>authentik</POSTGRES_DB>
                </env>
                <wait><log>database system is ready to accept connections</log><time>30000</time></wait>
              </run>
            </image>
            <image>
              <alias>authentik-server</alias>
              <name>ghcr.io/goauthentik/server:2024.10</name>
              <run>
                <cmd>server</cmd>
                <ports><port>${it-wikantik.authentik.port}:9000</port></ports>
                <env>
                  <AUTHENTIK_SECRET_KEY>it-authentik-secret-key-please-change</AUTHENTIK_SECRET_KEY>
                  <AUTHENTIK_REDIS__HOST>authentik-redis</AUTHENTIK_REDIS__HOST>
                  <AUTHENTIK_POSTGRESQL__HOST>authentik-db</AUTHENTIK_POSTGRESQL__HOST>
                  <AUTHENTIK_POSTGRESQL__USER>authentik</AUTHENTIK_POSTGRESQL__USER>
                  <AUTHENTIK_POSTGRESQL__PASSWORD>authentik</AUTHENTIK_POSTGRESQL__PASSWORD>
                  <AUTHENTIK_BOOTSTRAP_TOKEN>it-authentik-api-token</AUTHENTIK_BOOTSTRAP_TOKEN>
                  <AUTHENTIK_BOOTSTRAP_PASSWORD>it-authentik-admin-pw</AUTHENTIK_BOOTSTRAP_PASSWORD>
                </env>
                <links><link>authentik-redis</link><link>authentik-db</link></links>
                <wait>
                  <http>
                    <url>http://localhost:${it-wikantik.authentik.port}/-/health/ready/</url>
                    <method>GET</method><status>200..299</status>
                  </http>
                  <time>180000</time>
                </wait>
              </run>
            </image>
```

> Authentik needs a worker too for some tasks; the `server` container can run migrations on first boot via the bootstrap env. The `AUTHENTIK_BOOTSTRAP_TOKEN` gives the IT a known API token (`it-authentik-api-token`) to script provisioning without a login flow. The 180s wait reflects Authentik's slow first-boot migrations. This config is the **starting contract** — expect to iterate (worker container, healthcheck path, version pin) until the health endpoint goes 200.

- [ ] **Step 3: Verify the profile + module resolve**

```bash
mvn -q -pl wikantik-it-tests -Pscim-fullloop help:effective-pom > /dev/null && echo FULLLOOP-POM-OK
```

Expected: `FULLLOOP-POM-OK`.

- [ ] **Step 4: Commit the scaffold**

```bash
git add wikantik-it-tests/pom.xml wikantik-it-tests/wikantik-it-test-scim-fullloop/pom.xml
git commit -m "test(scim-fullloop): scaffold opt-in Authentik IT module + profile

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 15: Author the Authentik SCIM provider bootstrap

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-scim-fullloop/src/test/java/com/wikantik/its/scim/AuthentikClient.java`

- [ ] **Step 1: Write a thin Authentik REST client**

A test helper that uses the bootstrap API token to: create a SCIM provider pointing at the wiki's `/scim/v2` with bearer `it-scim-token`; create an application bound to it; create a user; trigger a sync; toggle a user `is_active`; and create/assign a group. Authentik's API is under `/api/v3/`. This is a helper, no assertions:

```java
/* (ASF header — copy verbatim from an existing IT file) */
package com.wikantik.its.scim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal Authentik API client (token-authed) used to drive the SCIM full-loop
 * IT: create a SCIM provider targeting the wiki, provision/disable users, and
 * trigger syncs. Endpoints live under {@code /api/v3/}.
 */
public class AuthentikClient {
    private final String base;       // http://localhost:<port>
    private final String token;      // it-authentik-api-token
    private final HttpClient http = HttpClient.newHttpClient();

    public AuthentikClient( final String base, final String token ) {
        this.base = base; this.token = token;
    }

    public HttpResponse<String> post( final String path, final String json )
            throws Exception {
        return http.send( HttpRequest.newBuilder()
                .uri( URI.create( base + path ) )
                .header( "Authorization", "Bearer " + token )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    public HttpResponse<String> patch( final String path, final String json )
            throws Exception {
        return http.send( HttpRequest.newBuilder()
                .uri( URI.create( base + path ) )
                .header( "Authorization", "Bearer " + token )
                .header( "Content-Type", "application/json" )
                .method( "PATCH", HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    public HttpResponse<String> get( final String path ) throws Exception {
        return http.send( HttpRequest.newBuilder()
                .uri( URI.create( base + path ) )
                .header( "Authorization", "Bearer " + token )
                .GET().build(), HttpResponse.BodyHandlers.ofString() );
    }
}
```

> The exact Authentik API paths/payloads (`/api/v3/providers/scim/`, `/api/v3/core/users/`, sync trigger endpoint) are version-specific and authored against the running container in Task 16's iteration loop. This client is the transport; the IT supplies the concrete bodies once the live API shape is confirmed.

- [ ] **Step 2: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-scim-fullloop/src/test/java/com/wikantik/its/scim/AuthentikClient.java
git commit -m "test(scim-fullloop): add Authentik API client helper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 16: Write the full-loop IT (create → disable → assert)

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-scim-fullloop/src/test/java/com/wikantik/its/scim/AuthentikScimFullLoopIT.java`

- [ ] **Step 1: Write the failing test (structure + polling contract)**

The test: configures the Authentik SCIM provider (via `AuthentikClient`), creates a user in Authentik, triggers a sync, polls the wiki's `/scim/v2/Users?filter=userName eq "..."` (bearer `it-scim-token`) until the user appears, then disables the user in Authentik, polls until the wiki shows `active:false`, and asserts the user cannot authenticate. Polling absorbs async propagation (modeled on `RestSeedHelper.awaitAdminReady`).

```java
/* (ASF header — copy verbatim) */
package com.wikantik.its.scim;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static com.google.gson.JsonParser.parseString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end SCIM full loop: Authentik (real IdP, SCIM client) provisions and
 * then disables a user, and the wiki's SCIM service-provider reflects each step.
 * Opt-in only (-Pscim-fullloop); never on the per-commit gate.
 */
public class AuthentikScimFullLoopIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final long POLL_MS = 60_000L, INTERVAL_MS = 1_000L;
    private static String wikiBase, authentikBase;
    private static AuthentikClient authentik;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() {
        wikiBase = System.getProperty( "it-wikantik.base.url" );
        authentikBase = System.getProperty( "it-wikantik.authentik.base-url" );
        authentik = new AuthentikClient( authentikBase, "it-authentik-api-token" );
    }

    /** Polls the wiki SCIM endpoint until predicate matches the named user, or fails. */
    private void pollWikiUser( final String userName, final java.util.function.Predicate<String> bodyOk,
                               final String what ) throws Exception {
        final long deadline = System.currentTimeMillis() + POLL_MS;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> r = http.send( HttpRequest.newBuilder()
                    .uri( URI.create( wikiBase + "/scim/v2/Users?filter="
                        + URLEncoder.encode( "userName eq \"" + userName + "\"", StandardCharsets.UTF_8 ) ) )
                    .header( "Authorization", "Bearer " + SCIM_TOKEN )
                    .header( "Accept", "application/scim+json" ).GET().build(),
                    HttpResponse.BodyHandlers.ofString() );
            if ( r.statusCode() == 200 && bodyOk.test( r.body() ) ) return;
            Thread.sleep( INTERVAL_MS );
        }
        fail( "Timed out waiting for: " + what );
    }

    @Test
    void authentik_provisions_then_disables_user_in_wiki() throws Exception {
        final String userName = "authentik-loop-user";

        // 1. Configure SCIM provider + create user in Authentik (concrete payloads
        //    authored against the live API in the iteration loop below).
        // configureScimProvider(); createUser(userName); triggerSync();

        // 2. Wiki reflects provisioning.
        pollWikiUser( userName,
            body -> parseString( body ).getAsJsonObject().getAsJsonArray( "Resources" ).size() > 0,
            "user provisioned into wiki via Authentik SCIM" );

        // 3. Disable in Authentik, sync.
        // disableUser(userName); triggerSync();

        // 4. Wiki reflects deactivation.
        pollWikiUser( userName, body -> {
            final var res = parseString( body ).getAsJsonObject().getAsJsonArray( "Resources" );
            return res.size() > 0
                && !res.get( 0 ).getAsJsonObject().get( "active" ).getAsBoolean();
        }, "user deactivated in wiki via Authentik SCIM" );

        assertTrue( true, "full loop: provision + disable propagated" );
    }
}
```

- [ ] **Step 2: Fill in the Authentik provisioning calls against the live container**

Run the module to bring Authentik up, then author the concrete `configureScimProvider/createUser/triggerSync/disableUser` bodies against the live `/api/v3/` API (the `AuthentikClient` is the transport). Iterate:

```bash
bin/run-tests.sh --unit && bin/run-tests.sh --fullloop
```

Expected after iteration: the full-loop test PASSES — Authentik provisions the user into the wiki and the deactivation propagates. Until the Authentik payloads are filled, the test fails at step 2's poll timeout (expected during development).

- [ ] **Step 3: Add the Authentik base-url system property**

Ensure the module pom's failsafe `systemPropertyVariables` includes `it-wikantik.authentik.base-url` = `http://localhost:${it-wikantik.authentik.port}` (mirror the `it-wikantik.base.url` line). Add if missing.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-scim-fullloop/
git commit -m "test(scim-fullloop): Authentik provision+disable full-loop IT

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Task 17: Verify `--fullloop` wiring end-to-end

- [ ] **Step 1: Run the opt-in loop and confirm it's excluded from the gate**

```bash
bin/run-tests.sh --list                 # scim-fullloop shown under opt-in
bin/run-tests.sh --unit && bin/run-tests.sh --it   # default gate: rest, sso, custom-jdbc — NO authentik
bin/run-tests.sh --fullloop             # heavy Authentik loop runs
```

Expected: the default `--it` run never starts Authentik containers; `--fullloop` runs the new module under `-Pscim-fullloop`.

- [ ] **Step 2: No commit (verification only).**

---

# Final verification

## Task 18: Full green gate + spec coverage check

- [ ] **Step 1: Run the full default gate**

```bash
bin/run-tests.sh
```

Expected: `RESULT: ALL PASSED` — unit phase + `rest` (incl. new SCIM tests), `sso` (Keycloak OIDC+SAML), `custom-jdbc`. Authentik is NOT in this run.

- [ ] **Step 2: Confirm no stale references remain**

```bash
grep -rn "mock-oauth\|sso-saml\|kristophjunge\|navikt\|MockOAuth2\|SimpleSaml" bin/run-tests.sh wikantik-it-tests --include=*.java --include=*.xml --include=*.sh
```

Expected: no output (all retired, except possibly historical comments — review any hits).

- [ ] **Step 3: Spec coverage tick-off**

Confirm each spec requirement maps to a task: Approach A gaps (Tasks 1–3) · Keycloak OIDC (4–7) · Keycloak SAML + retire SimpleSAMLphp (8–10) · run-tests.sh output/list/fullloop/help (11–13) · Authentik opt-in (14–17). If any spec line lacks a task, add it before closing.

---

## Notes for the implementer

- **Container iteration is expected** in Phases 2/3/5. The realm/provider config blocks are starting contracts; verify by the IT going green and adjust config (not test assertions) when the live container disagrees. The load-bearing assertions (deactivated-cannot-auth, admin-never-granted, `uid` firstScalar == `"1"`) must NOT be loosened to make a build pass — a failure there is a finding to report.
- **Green-before-delete** is non-negotiable: never `git rm` a mock/module until its replacement's ITs are green.
- **Do not run integration tests with `-T`** outside `bin/run-tests.sh --parallel` (port collisions). Use the runner.
- After any test-signature change, run `mvn test-compile` on the touched module before the full build (per repo convention).

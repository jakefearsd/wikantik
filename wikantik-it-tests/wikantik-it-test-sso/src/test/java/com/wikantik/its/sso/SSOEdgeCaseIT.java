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
import org.openqa.selenium.Cookie;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * OIDC callback-security integration tests — guards against session fixation
 * and verifies that the callback servlet fails closed on malformed inputs.
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>{@link #forgedStateFailsClosed} — a bogus {@code state} param must not
 *       produce an HTTP 500; the browser must land on a login or error page.</li>
 *   <li>{@link #garbageCodeFailsClosed} — an oversized garbage {@code code}
 *       with a nonsense {@code state} must also fail closed.</li>
 *   <li>{@link #sessionIdRotatesAcrossLogin} — the JSESSIONID cookie value
 *       must change across a successful SSO login, proving pac4j's
 *       {@code renewSession=true} rotates the session to prevent fixation.</li>
 * </ol>
 */
public class SSOEdgeCaseIT extends WithIntegrationTestSetup {

    private static final String MOCK_IDP_BASE_URL =
        System.getProperty( "it-wikantik.mock-oauth.base-url", "http://localhost:8088" );

    /** Subject used for the session-fixation regression test. */
    private static final String FIXATION_TEST_SUBJECT = "fixation-test-user";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String baseUrl() {
        return Env.TESTS_BASE_URL;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * A forged (random) {@code state} parameter must cause the callback to fail
     * closed — browser ends up on a login page or an error-flagged URL, never
     * on an HTTP 500.
     */
    @Test
    void forgedStateFailsClosed() {
        final String forgedState = "forged-" + System.nanoTime();
        Selenide.open( baseUrl() + "/sso/callback?code=bogus&state=" + forgedState );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().contains( "/login" )
                           || driver.getCurrentUrl().contains( "error=" ) );

        final String pageSource = WebDriverRunner.getWebDriver().getPageSource();
        Assertions.assertFalse(
            pageSource.contains( "HTTP Status 500" ),
            "Forged state must not produce an HTTP 500 error page." );
    }

    /**
     * Garbage {@code code} (40-char string) with a nonsense {@code state} must
     * also cause the callback to fail closed, redirecting to the login flow.
     */
    @Test
    void garbageCodeFailsClosed() {
        final String garbageCode = "x".repeat( 40 );
        Selenide.open( baseUrl() + "/sso/callback?code=" + garbageCode + "&state=nope" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().contains( "/login" )
                           || driver.getCurrentUrl().contains( "error=" ) );
    }

    /**
     * Regression test for session fixation (CVE-class: CWE-384).
     *
     * <p>Strategy: track a SINGLE browser session all the way through the
     * OIDC login flow WITHOUT clearing cookies between phases. The session
     * that carries the OIDC state before the callback is the same session
     * that the callback servlet must rotate. If {@code renewSession=true}
     * in {@link com.wikantik.auth.sso.SSOCallbackServlet} were reverted to
     * {@code false}, the pre-auth JSESSIONID would survive the callback
     * unchanged — and this test would fail.
     *
     * <p>Steps:
     * <ol>
     *   <li>Clear cookies; open {@code /sso/login} so pac4j creates the
     *       OIDC-state session and the browser lands on the mock IdP.</li>
     *   <li>Navigate to the wiki domain (same tab, cookies retained) to
     *       read the pre-auth JSESSIONID that {@code /sso/login} stamped.</li>
     *   <li>Return to the mock IdP URL (cookies still retained) and submit
     *       the login form to complete the OIDC handshake on that same
     *       session.</li>
     *   <li>Wait until the browser is back on the wiki domain and no longer
     *       on an {@code /sso/*} path (callback finished).</li>
     *   <li>Capture the post-auth JSESSIONID and assert it differs from the
     *       pre-auth value — proving pac4j rotated the session.</li>
     * </ol>
     */
    @Test
    void sessionIdRotatesAcrossLogin() {
        // Phase 1 — initiate SSO login; this forces pac4j to create the OIDC-state
        // session (storing nonce + state in it) and redirect to the mock IdP.
        // Close any WebDriver left by a previous test in this class (e.g.
        // forgedStateFailsClosed) so we start with an empty cookie jar.
        // Selenide.open() lazily creates a fresh driver.
        Selenide.closeWebDriver();
        Selenide.open( baseUrl() + "/sso/login" );

        // Wait until the browser has landed on the mock IdP.
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( MOCK_IDP_BASE_URL ) );

        // Remember the mock IdP URL so we can return to it after reading the cookie.
        final String idpUrl = WebDriverRunner.getWebDriver().getCurrentUrl();

        // Navigate back to the wiki domain (SAME session — no cookie clear) to read
        // the pre-auth JSESSIONID. The /sso/login redirect created the session on
        // the wiki domain; reading any wiki URL makes the browser send the cookie.
        Selenide.open( baseUrl() + "/wiki/Main" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().contains( baseUrl() ) );

        final Cookie preCookie =
            WebDriverRunner.getWebDriver().manage().getCookieNamed( "JSESSIONID" );
        Assertions.assertNotNull(
            preCookie,
            "Expected a JSESSIONID cookie after /sso/login created the OIDC-state session. " +
            "If anonymous requests no longer create sessions, adjust this test to use " +
            "a path that does, or report as DONE_WITH_CONCERNS." );
        final String preLoginSessionId = preCookie.getValue();
        System.out.println( "[SSO-Edge] pre-auth JSESSIONID: " + preLoginSessionId );

        // Phase 2 — complete authentication on the SAME session (no cookie clear).
        // Return to the mock IdP URL we captured above so the OIDC code→token exchange
        // uses the same state/nonce that is already bound to our pre-auth session.
        Selenide.open( idpUrl );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( MOCK_IDP_BASE_URL ) );

        // Submit the mock IdP form; this causes mock-oauth2-server to 302 the browser
        // back to /sso/callback with a valid code+state pair. SSOCallbackServlet
        // then calls callbackLogic.perform(..., renewSession=true, ...) which rotates
        // the session.
        new MockOAuth2LoginPage().submit( FIXATION_TEST_SUBJECT );

        // Wait for the callback to finish and the browser to settle on a wiki page.
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() )
                           && !driver.getCurrentUrl().contains( "/sso/" ) );

        // Phase 3 — capture and assert. We are on the wiki domain now; read the
        // post-auth JSESSIONID from the browser's current cookie jar.
        final Cookie postCookie =
            WebDriverRunner.getWebDriver().manage().getCookieNamed( "JSESSIONID" );
        Assertions.assertNotNull(
            postCookie,
            "Expected a JSESSIONID cookie after SSO login completed." );
        final String postLoginSessionId = postCookie.getValue();
        System.out.println( "[SSO-Edge] post-auth JSESSIONID: " + postLoginSessionId );

        // The session ID MUST differ: renewSession=true in SSOCallbackServlet
        // causes pac4j to invalidate the pre-auth session and issue a fresh
        // JSESSIONID while migrating the stored profile, which is the standard
        // defence against session-fixation attacks. If these values are equal the
        // fix has been reverted and the test must fail loudly.
        Assertions.assertNotEquals(
            preLoginSessionId,
            postLoginSessionId,
            "JSESSIONID must rotate across SSO login to prevent session fixation " +
            "(CWE-384). renewSession=true in SSOCallbackServlet enforces this. " +
            "pre=" + preLoginSessionId + " post=" + postLoginSessionId );
    }
}

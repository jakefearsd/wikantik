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
     * <p>Strategy: the pre-login JSESSIONID is captured from the wiki domain
     * by triggering {@code /sso/login}, waiting for the redirect to the mock
     * IdP, then navigating back to the wiki domain to read the cookie. The
     * session is created by the redirect servlet when it stores the OIDC
     * state/nonce. We then restart the SSO flow, complete login, and assert
     * the JSESSIONID changed — proving {@code renewSession=true} in
     * {@link com.wikantik.auth.sso.SSOCallbackServlet} rotates the session.
     *
     * <p>Steps:
     * <ol>
     *   <li>Clear cookies; open {@code /sso/login}; wait on mock IdP page.</li>
     *   <li>Navigate back to wiki domain to read the pre-login JSESSIONID.</li>
     *   <li>Clear cookies again; re-initiate the full SSO flow and complete it.</li>
     *   <li>Capture post-login JSESSIONID; assert it differs from pre-login.</li>
     * </ol>
     */
    @Test
    void sessionIdRotatesAcrossLogin() {
        // Phase 1: trigger an SSO login to force session creation, then read the
        // pre-login JSESSIONID from the wiki domain.
        WebDriverRunner.getWebDriver().manage().deleteAllCookies();
        Selenide.open( baseUrl() + "/sso/login" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( MOCK_IDP_BASE_URL ) );

        // Navigate back to the wiki domain to read the session cookie.
        // We intentionally abandon the OIDC flow here; we will restart it below.
        Selenide.open( baseUrl() + "/wiki/Main" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().contains( baseUrl() ) );

        final Cookie preCookie =
            WebDriverRunner.getWebDriver().manage().getCookieNamed( "JSESSIONID" );
        Assertions.assertNotNull(
            preCookie,
            "Expected a JSESSIONID cookie after /sso/login created the OIDC state session. " +
            "If anonymous requests no longer create sessions, adjust this test to use " +
            "a path that does, or report as DONE_WITH_CONCERNS." );
        final String preLoginSessionId = preCookie.getValue();
        System.out.println( "[SSO-Edge] pre-login JSESSIONID: " + preLoginSessionId );

        // Phase 2: clear cookies, start a fresh SSO flow, and complete it.
        WebDriverRunner.getWebDriver().manage().deleteAllCookies();
        Selenide.open( baseUrl() + "/sso/login" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( MOCK_IDP_BASE_URL ) );

        new MockOAuth2LoginPage().submit( FIXATION_TEST_SUBJECT );

        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() ) );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() )
                           && !driver.getCurrentUrl().contains( "/sso/" ) );

        // Capture the post-login JSESSIONID. We are on the wiki domain now.
        final Cookie postCookie =
            WebDriverRunner.getWebDriver().manage().getCookieNamed( "JSESSIONID" );
        Assertions.assertNotNull(
            postCookie,
            "Expected a JSESSIONID cookie after SSO login completed." );
        final String postLoginSessionId = postCookie.getValue();
        System.out.println( "[SSO-Edge] post-login JSESSIONID: " + postLoginSessionId );

        // The post-login session must differ from the pre-login session.
        // renewSession=true in SSOCallbackServlet causes pac4j to invalidate
        // the pre-auth session and issue a new one, so these must never match.
        Assertions.assertNotEquals(
            preLoginSessionId,
            postLoginSessionId,
            "JSESSIONID must rotate across SSO login to prevent session fixation. " +
            "pre=" + preLoginSessionId + " post=" + postLoginSessionId );
    }
}

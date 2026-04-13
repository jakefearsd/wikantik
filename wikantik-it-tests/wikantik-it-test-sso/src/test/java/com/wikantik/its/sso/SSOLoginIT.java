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
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * End-to-end test of the OIDC SSO flow against a disposable
 * <a href="https://github.com/navikt/mock-oauth2-server">mock-oauth2-server</a>
 * container (started by docker-maven-plugin during pre-integration-test).
 *
 * <p>The test drives the full happy path:
 * <ol>
 *   <li>Browser opens {@code /sso/login} on the wiki.</li>
 *   <li>{@code SSORedirectServlet} 302s to the mock IdP's
 *       {@code /default/authorize} endpoint.</li>
 *   <li>Mock IdP shows its debugger form; the test submits a subject.</li>
 *   <li>Mock IdP 302s to {@code /sso/callback?code=…&state=…}.</li>
 *   <li>{@code SSOCallbackServlet} + pac4j exchange the code for an ID token
 *       (calls {@code /default/token}, {@code /default/jwks}).</li>
 *   <li>Browser lands on the wiki root; {@code SSOLoginModule} provisions the
 *       user via the {@code sub} claim and establishes the wiki session.</li>
 * </ol>
 *
 * <p>Runs only under the {@code sso-it} Maven profile (see the module's
 * parent pom) so that routine CI doesn't need a docker daemon.
 */
public class SSOLoginIT extends WithIntegrationTestSetup {

    /**
     * Subject (mapped to the OIDC {@code sub} claim) that becomes the
     * provisioned wiki login name. Fresh identity per test to keep the XML
     * user database from carrying forward state between runs.
     */
    private static final String SSO_SUBJECT = "oidc-testuser";

    /**
     * Base URL of the mock IdP, injected by failsafe (see pom.xml). Stays in
     * sync with the port the docker-maven-plugin binds.
     */
    private static final String MOCK_IDP_BASE_URL =
        System.getProperty( "it-wikantik.mock-oauth.base-url", "http://localhost:8088" );

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void oidcLoginAutoProvisionsAndAuthenticates() {
        // Prime the Main page so the post-SSO redirect has a known landing
        // point and we can assert on the React UserBadge state.
        final ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals(
            "G\u2019day (anonymous guest)", main.authenticatedText(),
            "Baseline: test starts with an anonymous session." );

        // Kick off the SSO flow. wikantik redirects to the mock IdP.
        Selenide.open( baseUrl() + "/sso/login" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( MOCK_IDP_BASE_URL ) );
        System.out.println( "[SSO-IT] authorize URL: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        // Authenticate against the mock IdP.
        new MockOAuth2LoginPage().submit( SSO_SUBJECT );

        // Capture the first URL we see once the browser leaves the IdP,
        // before it follows any further redirects.
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() ) );
        System.out.println( "[SSO-IT] first-wiki URL after IdP: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() )
                          && !driver.getCurrentUrl().contains( "/sso/" ) );
        System.out.println( "[SSO-IT] post-callback URL: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        // Reload onto the Main page so the UserBadge reflects the now-active
        // session (the root redirect lands on an SPA route that we don't
        // otherwise assert against).
        final ViewWikiPage authedMain = ViewWikiPage.open( "Main" );
        $( "[data-testid=user-badge][data-authenticated=true]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );

        Assertions.assertEquals(
            "G\u2019day, " + SSO_SUBJECT + " (authenticated)",
            authedMain.authenticatedText(),
            "After SSO, UserBadge should greet the sub-claim value." );

        // And logout should drop us back to an anonymous session — proving
        // that the SSO-provisioned session uses the same session tear-down
        // path as password logins.
        authedMain.clickOnLogout();
        Assertions.assertEquals(
            "G\u2019day (anonymous guest)", authedMain.authenticatedText(),
            "Logout should clear the SSO-provisioned session." );
    }

    /**
     * Direct hit on the callback servlet with no {@code code} parameter must
     * fail gracefully — not with a 500 — and redirect back to the login page
     * with an error marker. Guards against regressions in the servlet's
     * catch-all error branch.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void callbackWithoutCodeRedirectsToLoginError() {
        Selenide.open( baseUrl() + "/sso/callback" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> driver.getCurrentUrl().contains( "/login" )
                          || driver.getCurrentUrl().contains( "error=" ) );
    }

    private static String baseUrl() {
        // Env.TESTS_BASE_URL is populated from -Dit-wikantik.base.url, which
        // the failsafe config in this module's pom sets to the Cargo context.
        return Env.TESTS_BASE_URL;
    }
}

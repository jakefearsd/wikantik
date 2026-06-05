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
 * End-to-end test of the SAML 2.0 SSO flow against a disposable Keycloak
 * container (started by docker-maven-plugin during pre-integration-test). The
 * module runs Keycloak in {@code type=saml} (single pac4j client), so the SAML
 * callback resolves unambiguously.
 *
 * <p>The test drives the full SAML browser-POST happy path:
 * <ol>
 *   <li>Browser opens {@code /sso/login?client_name=SAML2Client} on the wiki.</li>
 *   <li>{@code SSORedirectServlet} builds a SAML AuthnRequest and (HTTP-Redirect
 *       binding) 302s the browser to Keycloak's SAML SSO endpoint
 *       ({@code /realms/wikantik-it/protocol/saml}).</li>
 *   <li>Keycloak shows its login form; the test submits {@code saml-testuser}
 *       / {@code testpass}.</li>
 *   <li>Keycloak issues a SAML response (HTTP-POST binding) back to
 *       {@code /sso/callback}.</li>
 *   <li>{@code SSOCallbackServlet} + pac4j validate the assertion and store the
 *       SAML2Profile in the HTTP session.</li>
 *   <li>{@code SSOLoginModule} extracts the {@code uid} attribute (a
 *       single-element list {@code ['1']}) via {@code firstScalar()}, producing
 *       login name {@code "1"}. Without firstScalar(), the login name would be
 *       the stringified list {@code "[1]"}.</li>
 *   <li>The user is auto-provisioned and the browser lands on the wiki root.</li>
 * </ol>
 *
 * <p>The clean-scalar assertion is the key regression guard for the
 * multi-valued-claim bug: {@code assertFalse(loginName.contains("["))} fails
 * immediately if {@code firstScalar()} is removed or regressed.
 */
public class SAMLLoginIT extends WithIntegrationTestSetup {

    /** Keycloak realm user; its {@code uid} attribute is {@code ["1"]}. */
    private static final String IDP_USERNAME = "saml-testuser";
    private static final String IDP_PASSWORD = "testpass";

    /**
     * Expected wiki login name after SAML auto-provisioning. Keycloak delivers
     * {@code uid} as a SAML attribute carrying the single value {@code "1"};
     * pac4j represents it as a list {@code ['1']} and
     * {@code SSOLoginModule.firstScalar()} unwraps it to {@code "1"}. A
     * regression in firstScalar (reverting to list toString "[1]") is caught by
     * both the equality assertion and the bracket-content check below.
     */
    private static final String EXPECTED_LOGIN_NAME = "1";

    /**
     * Issuer/base of the Keycloak realm, injected by failsafe (see pom.xml). The
     * SAML SSO endpoint and the login form both live under this path, so the
     * browser landing on the IdP is detected by a startsWith match.
     */
    private static final String IDP_ISSUER =
        System.getProperty( "it-wikantik.keycloak.issuer", "http://localhost:8089/realms/wikantik-it" );

    @Test
    void samlLoginAutoProvisionsAndAuthenticates() {
        // 1. Anonymous baseline on Main.
        final ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals(
            "G’day (anonymous guest)", main.authenticatedText(),
            "Baseline: test must start with an anonymous session." );

        // 2. Kick off the SAML SSO flow. client_name=SAML2Client selects the SAML
        //    client explicitly (this module configures only the SAML client).
        Selenide.open( baseUrl() + "/sso/login?client_name=SAML2Client" );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( IDP_ISSUER ) );
        System.out.println( "[SAML-IT] IdP URL: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        // 3. Authenticate against the Keycloak login form.
        new KeycloakSamlLoginPage().login( IDP_USERNAME, IDP_PASSWORD );

        // 4. Wait until the SAML POST-binding callback completes and the browser
        //    settles back on the wiki (no longer on an /sso/* path).
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 30 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() ) );
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 30 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() )
                          && !driver.getCurrentUrl().contains( "/sso/" ) );
        System.out.println( "[SAML-IT] post-callback URL: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        // 5. Open Main and assert the authenticated UserBadge state.
        ViewWikiPage.open( "Main" );
        $( "[data-testid=user-badge][data-authenticated=true]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );

        // 6. Key assertions: login name is a clean scalar "1" (no brackets),
        //    proving firstScalar() unwrapped uid=['1'].
        final String badgeText = $( "[data-testid=user-badge]" ).getAttribute( "data-login-name" );
        System.out.println( "[SAML-IT] provisioned login name (data-login-name): " + badgeText );

        Assertions.assertNotNull( badgeText, "data-login-name attribute must be present after SAML login." );
        Assertions.assertFalse( badgeText.isBlank(), "Provisioned login name must not be blank." );
        Assertions.assertFalse(
            badgeText.contains( "[" ) || badgeText.contains( "]" ),
            "Provisioned login name must be a clean scalar, not a stringified list like '[1]'. " +
            "Actual: '" + badgeText + "'. This exercises the multi-valued-claim fix in " +
            "SSOLoginModule.firstScalar()." );
        Assertions.assertEquals(
            EXPECTED_LOGIN_NAME, badgeText,
            "Login name must equal the firstScalar of uid=['1'] from the SAML assertion. " +
            "Actual: '" + badgeText + "'." );

        final ViewWikiPage authedMain = ViewWikiPage.open( "Main" );
        Assertions.assertEquals(
            "G’day, " + EXPECTED_LOGIN_NAME + " (authenticated)",
            authedMain.authenticatedText(),
            "After SAML login, UserBadge should greet the uid-claim scalar value." );

        // 7. Logout must return to anonymous.
        authedMain.clickOnLogout();
        Assertions.assertEquals(
            "G’day (anonymous guest)", authedMain.authenticatedText(),
            "Logout must clear the SAML-provisioned session." );

        System.out.println( "[SAML-IT] PASSED — login name '" + EXPECTED_LOGIN_NAME +
                            "' is a clean scalar (no brackets), auto-provisioned, and logout works." );
    }

    private static String baseUrl() {
        return Env.TESTS_BASE_URL;
    }
}

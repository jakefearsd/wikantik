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
 * End-to-end test of the SAML 2.0 SSO flow against a disposable
 * <a href="https://github.com/kristophjunge/docker-test-saml-idp">SimpleSAMLphp IdP</a>
 * container (started by docker-maven-plugin during pre-integration-test).
 *
 * <p>The test drives the full SAML browser-POST happy path:
 * <ol>
 *   <li>Browser opens {@code /sso/login?client_name=SAML2Client} on the wiki.</li>
 *   <li>{@code SSORedirectServlet} builds a SAML AuthnRequest and 302s the
 *       browser to the SimpleSAMLphp {@code /simplesaml/saml2/idp/SSOService.php}.</li>
 *   <li>SimpleSAMLphp shows its username/password form; the test submits
 *       {@code user1} / {@code user1pass}.</li>
 *   <li>SimpleSAMLphp issues a SAML response (HTTP-POST binding) back to
 *       {@code /sso/callback}.</li>
 *   <li>{@code SSOCallbackServlet} + pac4j validate the assertion and store
 *       the SAML2Profile in the HTTP session.</li>
 *   <li>{@code SSOLoginModule} extracts the {@code uid} attribute (a
 *       single-element list: {@code ['1']}) via {@code firstScalar()}, producing
 *       login name {@code "1"}. This exercises the multi-valued-claim fix:
 *       without it, the login name would be {@code "[1]"} (the list toString).</li>
 *   <li>The user is auto-provisioned and the browser lands on the wiki root.</li>
 * </ol>
 *
 * <p>The clean-scalar assertion is the key regression guard for the
 * multi-valued-claim bug: {@code assertFalse(loginName.contains("["))} fails
 * immediately if {@code firstScalar()} is removed or regressed.
 *
 * <p>Runs only under the {@code integration-tests} Maven profile (see the
 * module's pom.xml) so that routine CI doesn't need a Docker daemon.
 */
public class SAMLLoginIT extends WithIntegrationTestSetup {

    /**
     * The IdP username to authenticate as. The kristophjunge/test-saml-idp image
     * has {@code user1} with {@code uid=['1']} — so the provisioned wiki login
     * name will be {@code "1"} (the firstScalar of the uid list).
     */
    private static final String IDP_USERNAME = "user1";
    private static final String IDP_PASSWORD = "user1pass";

    /**
     * Expected wiki login name after SAML auto-provisioning.
     *
     * <p>SimpleSAMLphp delivers {@code uid} as a single-element list
     * {@code ['1']} for user1. {@code SSOLoginModule.firstScalar()} extracts
     * {@code "1"} from that list. This constant pins the expected value so that
     * a regression in firstScalar (reverting to list toString "[1]") is caught
     * by the equality assertion as well as the bracket-content check.
     */
    private static final String EXPECTED_LOGIN_NAME = "1";

    /**
     * Base URL of the SimpleSAMLphp IdP, injected by failsafe (see pom.xml).
     * Stays in sync with the port the docker-maven-plugin binds.
     */
    private static final String SAML_IDP_BASE_URL =
        System.getProperty( "it-wikantik.saml-idp.base-url", "http://localhost:8089" );

    @Test
    void samlLoginAutoProvisionsAndAuthenticates() {
        // -----------------------------------------------------------------
        // 1. Assert anonymous baseline on Main.
        // -----------------------------------------------------------------
        final ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals(
            "G’day (anonymous guest)", main.authenticatedText(),
            "Baseline: test must start with an anonymous session." );

        // -----------------------------------------------------------------
        // 2. Kick off the SAML SSO flow. wikantik redirects to the SimpleSAMLphp IdP.
        //    We specify client_name=SAML2Client so the redirect servlet picks
        //    the SAML client explicitly (no ambiguity if 'both' mode were used).
        // -----------------------------------------------------------------
        Selenide.open( baseUrl() + "/sso/login?client_name=SAML2Client" );

        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 15 ) )
            .until( driver -> driver.getCurrentUrl().contains( "/simplesaml/" ) );
        System.out.println( "[SAML-IT] IdP URL: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        // -----------------------------------------------------------------
        // 3. Authenticate against the SimpleSAMLphp IdP form.
        // -----------------------------------------------------------------
        new SimpleSamlLoginPage().login( IDP_USERNAME, IDP_PASSWORD );

        // -----------------------------------------------------------------
        // 4. Wait until the browser completes the SAML POST-binding callback
        //    and lands back on the wiki (no longer on an /sso/* path).
        // -----------------------------------------------------------------
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 30 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() ) );
        System.out.println( "[SAML-IT] first-wiki URL after IdP: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 30 ) )
            .until( driver -> driver.getCurrentUrl().startsWith( baseUrl() )
                          && !driver.getCurrentUrl().contains( "/sso/" ) );
        System.out.println( "[SAML-IT] post-callback URL: " + WebDriverRunner.getWebDriver().getCurrentUrl() );

        // -----------------------------------------------------------------
        // 5. Open Main and assert the UserBadge shows the authenticated state.
        // -----------------------------------------------------------------
        ViewWikiPage.open( "Main" );
        $( "[data-testid=user-badge][data-authenticated=true]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );

        // -----------------------------------------------------------------
        // 6. Key assertions:
        //    (a) Badge text is non-blank.
        //    (b) Login name does NOT contain '[' or ']' — proves firstScalar()
        //        correctly unwrapped the uid=['1'] list instead of turning it
        //        into the string "[1]". This is the critical multi-valued-claim
        //        regression guard.
        //    (c) Login name equals the expected scalar "1".
        // -----------------------------------------------------------------
        final String badgeText = $( "[data-testid=user-badge]" ).getAttribute( "data-login-name" );
        System.out.println( "[SAML-IT] provisioned login name (data-login-name): " + badgeText );

        Assertions.assertNotNull( badgeText, "data-login-name attribute must be present after SAML login." );
        Assertions.assertFalse( badgeText.isBlank(), "Provisioned login name must not be blank." );

        // The critical multi-valued-claim regression check:
        // If firstScalar() is missing or broken, uid=['1'] becomes "[1]" and this fails.
        Assertions.assertFalse(
            badgeText.contains( "[" ) || badgeText.contains( "]" ),
            "Provisioned login name must be a clean scalar, not a stringified list like '[1]'. " +
            "Actual: '" + badgeText + "'. This exercises the multi-valued-claim fix in " +
            "SSOLoginModule.firstScalar()." );

        Assertions.assertEquals(
            EXPECTED_LOGIN_NAME, badgeText,
            "Login name must equal the firstScalar of uid=['1'] from the SAML assertion. " +
            "Actual: '" + badgeText + "'." );

        // Sanity check on the full authenticated greeting text
        final ViewWikiPage authedMain = ViewWikiPage.open( "Main" );
        Assertions.assertEquals(
            "G’day, " + EXPECTED_LOGIN_NAME + " (authenticated)",
            authedMain.authenticatedText(),
            "After SAML login, UserBadge should greet the uid-claim scalar value." );

        // -----------------------------------------------------------------
        // 7. Logout must return to anonymous — proves the SSO-provisioned
        //    session uses the same session tear-down path as password logins.
        // -----------------------------------------------------------------
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

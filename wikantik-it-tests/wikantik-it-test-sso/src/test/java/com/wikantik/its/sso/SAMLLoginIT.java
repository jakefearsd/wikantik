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
 * End-to-end SAML 2.0 SSO test running against the SAME Keycloak realm as the
 * OIDC test, with the wiki configured {@code wikantik.sso.type=both}. This is the
 * regression guard for the multi-client SAML callback fix in
 * {@code SSOConfig.buildSamlClient}: with two pac4j clients registered, the SAML
 * ACS carries {@code ?client_name=SAML2Client} so the shared {@code /sso/callback}
 * resolves the SAML client instead of failing with
 * "unable to find one indirect client for the callback".
 *
 * <p>The realm's SAML client emits the user's {@code uid} attribute (=['1']); the
 * shared {@code claimMapping.loginName=uid} resolves to the scalar {@code "1"} via
 * {@code firstScalar()} — the multi-valued-claim guard. (The OIDC client maps uid
 * &lt;- username, so OIDC logins resolve to "oidc-testuser"; same realm, same user.)
 */
public class SAMLLoginIT extends WithIntegrationTestSetup {

    /** The shared realm user; its {@code uid} attribute is {@code ["1"]}. */
    private static final String IDP_USERNAME = "oidc-testuser";
    private static final String IDP_PASSWORD = "testpass";

    /** Expected wiki login name after SAML auto-provisioning: firstScalar(uid=['1']). */
    private static final String EXPECTED_LOGIN_NAME = "1";

    /** Issuer/base of the Keycloak realm (the SAML SSO endpoint + login form live under it). */
    private static final String IDP_ISSUER =
        System.getProperty( "it-wikantik.oidc.issuer", "http://localhost:8088/realms/wikantik-it" );

    @Test
    void samlLoginAutoProvisionsAndAuthenticates() {
        // 1. Anonymous baseline on Main.
        final ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals(
            "G’day (anonymous guest)", main.authenticatedText(),
            "Baseline: test must start with an anonymous session." );

        // 2. Kick off the SAML flow. client_name=SAML2Client selects the SAML client
        //    (type=both also registers the OIDC client).
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

        // 5. Open Main and assert authenticated UserBadge state.
        ViewWikiPage.open( "Main" );
        $( "[data-testid=user-badge][data-authenticated=true]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 15 ) );

        // 6. Login name is a clean scalar "1" (no brackets) — proves firstScalar()
        //    unwrapped uid=['1'] AND that the type=both SAML callback resolved.
        final String badgeText = $( "[data-testid=user-badge]" ).getAttribute( "data-login-name" );
        System.out.println( "[SAML-IT] provisioned login name (data-login-name): " + badgeText );

        Assertions.assertNotNull( badgeText, "data-login-name attribute must be present after SAML login." );
        Assertions.assertFalse( badgeText.isBlank(), "Provisioned login name must not be blank." );
        Assertions.assertFalse(
            badgeText.contains( "[" ) || badgeText.contains( "]" ),
            "Provisioned login name must be a clean scalar, not a stringified list like '[1]'. " +
            "Actual: '" + badgeText + "'." );
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

        System.out.println( "[SAML-IT] PASSED (type=both) — login name '" + EXPECTED_LOGIN_NAME +
                            "' is a clean scalar, auto-provisioned, and logout works." );
    }

    private static String baseUrl() {
        return Env.TESTS_BASE_URL;
    }
}

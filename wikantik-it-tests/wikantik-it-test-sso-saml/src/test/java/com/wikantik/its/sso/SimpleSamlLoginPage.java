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
import com.codeborne.selenide.SelenideElement;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * Page object for the HTML username/password login form rendered by
 * <a href="https://github.com/kristophjunge/docker-test-saml-idp">kristophjunge/test-saml-idp</a>
 * (SimpleSAMLphp) on {@code /simplesaml/module.php/core/loginuserpass.php}
 * after a SAML AuthnRequest redirects the browser to the IdP.
 *
 * <p>The form has an {@code input[name=username]} field, an
 * {@code input[name=password]} field, and a submit button. Filling both and
 * clicking submit causes SimpleSAMLphp to issue a SAML response with the
 * user's attributes and 302-redirect the browser back to the SP's Assertion
 * Consumer Service (i.e. wikantik's {@code /sso/callback}).
 *
 * <p>Users available in the image:
 * <ul>
 *   <li>{@code user1} / {@code user1pass} — uid=1, email=user1@example.com</li>
 *   <li>{@code user2} / {@code user2pass} — uid=2, email=user2@example.com</li>
 * </ul>
 */
class SimpleSamlLoginPage {

    /** Max wait for the SimpleSAMLphp login form to appear after the wiki redirect. */
    private static final Duration FORM_WAIT = Duration.ofSeconds( 15 );

    /**
     * Submits the SimpleSAMLphp username/password form.
     *
     * <p>After submit, SimpleSAMLphp issues a SAML response and 302-redirects
     * the browser back to wikantik's {@code /sso/callback}, which completes
     * pac4j's SAML token exchange and stores the SAML2Profile in the session.
     *
     * @param username the IdP username (e.g. {@code "user1"})
     * @param password the IdP password (e.g. {@code "user1pass"})
     */
    void login( final String username, final String password ) {
        final SelenideElement usernameInput = $( "input[name=username]" )
            .shouldBe( Condition.visible, FORM_WAIT );
        usernameInput.setValue( username );

        final SelenideElement passwordInput = $( "input[name=password]" )
            .shouldBe( Condition.visible, FORM_WAIT );
        passwordInput.setValue( password );

        // kristophjunge/test-saml-idp (SimpleSAMLphp 1.15) renders the submit
        // button as <button class="btn"> without an explicit type attribute, so
        // "button[type=submit]" doesn't match (CSS attribute selectors require the
        // attribute to be present in the DOM). Use "form button" as a robust
        // catch-all that also covers <input type=submit> via the input fallbacks.
        final SelenideElement submit = $( "form button, input[name=submit], input[type=submit]" )
            .shouldBe( Condition.visible, FORM_WAIT );
        submit.click();
    }
}

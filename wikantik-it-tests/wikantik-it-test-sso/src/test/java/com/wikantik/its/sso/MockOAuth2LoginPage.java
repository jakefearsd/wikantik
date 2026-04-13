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
 * Page object for the HTML "debugger" form that
 * <a href="https://github.com/navikt/mock-oauth2-server">navikt/mock-oauth2-server</a>
 * renders on {@code /default/authorize} when no login hint is provided.
 *
 * <p>The form has a {@code username} input and a submit button. Filling it and
 * submitting produces an ID token whose {@code sub} claim is the supplied
 * username, then 302-redirects back to the {@code redirect_uri} that pac4j
 * sent in the authorize request.
 *
 * <p>The exact markup of the debugger page isn't part of mock-oauth2-server's
 * public API, so we target it by the {@code name} attribute of the input
 * (stable since the project's inception) rather than by CSS class.
 */
class MockOAuth2LoginPage {

    /** Max wait for the mock IdP form to appear after the wikantik redirect. */
    private static final Duration FORM_WAIT = Duration.ofSeconds( 10 );

    /**
     * Submits the debugger form with the given subject. After submit,
     * mock-oauth2-server 302s the browser back to wikantik's
     * {@code /sso/callback}, which completes the pac4j token exchange.
     *
     * @param subject value to use for the {@code sub} claim on the issued token.
     */
    void submit( final String subject ) {
        final SelenideElement usernameInput = $( "input[name=username]" )
            .shouldBe( Condition.visible, FORM_WAIT );
        usernameInput.setValue( subject );

        // The debugger page ships both <button type=submit> and an <input
        // type=submit> depending on server version; select whichever is
        // present.
        final SelenideElement submit = $( "button[type=submit], input[type=submit]" )
            .shouldBe( Condition.visible, FORM_WAIT );
        submit.click();
    }
}

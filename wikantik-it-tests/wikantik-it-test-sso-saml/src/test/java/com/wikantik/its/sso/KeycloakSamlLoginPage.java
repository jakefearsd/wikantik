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

/**
 * Page object for Keycloak's login form as rendered during the SAML browser
 * flow. Keycloak serves the same login theme (`#username` / `#password` /
 * `#kc-login`) for SAML as for OIDC. After submit, Keycloak issues a SAML
 * response (HTTP-POST binding) and redirects the browser back to the SP's
 * Assertion Consumer Service (wikantik's {@code /sso/callback}).
 */
class KeycloakSamlLoginPage {

    private static final Duration FORM_WAIT = Duration.ofSeconds( 15 );

    /** Fills Keycloak's username/password form and submits. */
    void login( final String username, final String password ) {
        $( "#username" ).shouldBe( Condition.visible, FORM_WAIT ).setValue( username );
        $( "#password" ).shouldBe( Condition.visible, FORM_WAIT ).setValue( password );
        $( "#kc-login" ).shouldBe( Condition.visible, FORM_WAIT ).click();
    }
}

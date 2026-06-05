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

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
package com.wikantik.its;

import com.wikantik.pages.haddock.LoginPage;
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;


/**
 * Login-related tests for Apache JSPWiki.
 *
 * <p>The React SPA shows login as a modal overlaid on the current page rather
 * than a dedicated {@code /Login.jsp} route, so the legacy assertions that
 * checked {@code title() == "Wikantik: Login"} and
 * {@code wikiTitle() == "Login"} were dropped. The underlying page remains
 * visible behind the modal, so the page name reported by {@link
 * LoginPage#wikiTitle()} is the page you were on when you opened the modal.
 */
public class LoginIT extends WithIntegrationTestSetup {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void loginAndLogout() {
        ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals( "Wikantik: Main", main.title() );
        Assertions.assertEquals( "Main", main.wikiTitle() );
        Assertions.assertEquals( "G\u2019day (anonymous guest)", main.authenticatedText() );

        final LoginPage login = main.clickOnLogin();
        // Modal is overlaid on the Main page — wikiTitle still reports "Main".
        Assertions.assertEquals( "Main", login.wikiTitle() );

        main = login.performLogin();
        Assertions.assertEquals( "Wikantik: Main", main.title() );
        Assertions.assertEquals( "G\u2019day, janne (authenticated)", main.authenticatedText() );

        main.clickOnLogout();
        Assertions.assertEquals( "G\u2019day (anonymous guest)", main.authenticatedText() );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void loginKO() {
        ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals( "Wikantik: Main", main.title() );
        Assertions.assertEquals( "Main", main.wikiTitle() );
        Assertions.assertEquals( "G\u2019day (anonymous guest)", main.authenticatedText() );

        final LoginPage login = main.clickOnLogin();
        main = login.performLogin( "perry", "mason" );
        // Failed login leaves us on the Main page with an anonymous badge.
        Assertions.assertEquals( "Wikantik: Main", main.title() );
        Assertions.assertEquals( "G\u2019day (anonymous guest)", main.authenticatedText() );
    }

}

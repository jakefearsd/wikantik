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
package com.wikantik.pages.haddock;

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.environment.Env;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Actions available on the Login page.
 */
public class LoginPage implements HaddockPage {

    /**
     * Logs in using Janne username and password.
     *
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage performLogin() {
        return performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    /**
     * Logs in using the supplied username and password.
     *
     * @param login user login.
     * @param password user password.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage performLogin(final String login, final String password ) {
        final String currentUrl = WebDriverRunner.url();

        Selenide.$( By.id( "j_username" ) ).val( login );
        Selenide.$( By.id( "j_password" ) ).val( password );
        Selenide.$( By.name( "submitlogin" ) ).click();

        // Wait for the page to stabilize after login attempt
        // This handles both successful redirects and failed logins that stay on the page
        Selenide.$( By.className( "page-content" ) ).shouldBe( Condition.visible, Duration.ofSeconds( 6 ) );

        // Additional wait for page URL or content to change/stabilize
        // For failed logins, we stay on Login page; for successful logins, we redirect
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 6 ) )
            .until( driver -> {
                // Wait until either URL changes (successful login) or login error message appears (failed login)
                // Use "alert-danger" for login-specific errors, not generic "error" class which includes missing pages
                final String newUrl = driver.getCurrentUrl();
                final boolean urlChanged = !newUrl.equals( currentUrl );
                final boolean hasLoginError = !driver.findElements( By.cssSelector( "#login .alert-danger" ) ).isEmpty();
                final boolean pageLoaded = !driver.findElements( By.className( "page-content" ) ).isEmpty();
                return ( urlChanged || hasLoginError ) && pageLoaded;
            } );

        return new ViewWikiPage();
    }

}

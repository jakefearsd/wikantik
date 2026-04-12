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
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.environment.Env;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * Page object for the React SPA login modal (the {@code LoginForm} component).
 *
 * <p>Unlike the legacy haddock template which navigated to a dedicated
 * {@code /Login.jsp} URL, the React SPA shows login as a modal overlaid on
 * top of the current page. The underlying page never navigates away, so
 * assertions that used to check {@code title() == "Wikantik: Login"} or
 * {@code wikiTitle() == "Login"} must be dropped from callers.
 */
public class LoginPage implements HaddockPage {

    /**
     * Logs in using Janne's default credentials.
     *
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage performLogin() {
        return performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    /**
     * Logs in using the supplied username and password.
     *
     * <p>Fills in the modal form, submits it, and waits for either a user
     * badge in the authenticated state (success) or a visible login error
     * banner (failure). In either case the modal will either close or
     * display an error; both paths return a {@link ViewWikiPage} pointing at
     * whichever view is currently rendered behind the modal.
     *
     * @param login user login.
     * @param password user password.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage performLogin( final String login, final String password ) {
        final SelenideElement usernameInput = $( "[data-testid=login-username]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
        final SelenideElement passwordInput = $( "[data-testid=login-password]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
        ReactInputs.setInputValue( usernameInput, login );
        ReactInputs.setInputValue( passwordInput, password );
        $( "[data-testid=login-submit]" ).click();

        // Wait for either the modal to close (successful login — badge flips
        // to authenticated) or a visible login error banner (failed login).
        new WebDriverWait( WebDriverRunner.getWebDriver(), Duration.ofSeconds( 10 ) )
            .until( driver -> !driver.findElements( org.openqa.selenium.By.cssSelector( "[data-testid=user-badge][data-authenticated=true]" ) ).isEmpty()
                 || !driver.findElements( org.openqa.selenium.By.cssSelector( "[data-testid=login-error]" ) ).isEmpty() );

        return new ViewWikiPage();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The login modal is overlaid on top of whatever page the user was
     * previously on, so {@code wikiTitle()} still returns the underlying
     * page-view's name rather than a synthetic "Login" value.
     */
    @Override
    public String wikiTitle() {
        // Fall back to the underlying PageView's data-page-name. If the modal
        // is the only thing on screen (e.g. tests opened the login modal
        // before navigating anywhere), return an empty string.
        if ( Selenide.$( "[data-testid=page-view]" ).exists() ) {
            return HaddockPage.super.wikiTitle();
        }
        return "";
    }

}

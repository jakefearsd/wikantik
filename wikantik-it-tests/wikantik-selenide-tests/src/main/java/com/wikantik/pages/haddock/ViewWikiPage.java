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
import com.wikantik.pages.Page;
import org.openqa.selenium.By;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.codeborne.selenide.Selenide.$;

/**
 * Page object for the React SPA article view (the {@code PageView} component).
 *
 * <p>All element lookups are done through {@code data-testid} attributes so
 * selectors survive CSS refactors. The React UserBadge is always visible
 * (there is no hover dropdown as in the legacy haddock template), so
 * {@link #hoverLoginArea()} is retained as a no-op for backward compatibility
 * with existing call sites.
 */
public class ViewWikiPage implements HaddockPage {

    /**
     * Open a given page for view.
     *
     * @param pageName Wiki page name to view.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public static ViewWikiPage open( final String pageName ) {
        final ViewWikiPage page = Page.withUrl( Page.baseUrl() + "/wiki/" + pageName ).openAs( new ViewWikiPage() );
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return page;
    }

    /**
     * Returns a human-readable description of the current authentication state.
     * Retained for backward compatibility with the legacy haddock assertions:
     * returns {@code "G'day (anonymous guest)"} when no user is signed in and
     * {@code "G'day, <username> (authenticated)"} otherwise.
     *
     * @return the authentication state text.
     */
    public String authenticatedText() {
        final SelenideElement badge = $( "[data-testid=user-badge]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
        final String authenticated = badge.getAttribute( "data-authenticated" );
        if ( "true".equals( authenticated ) ) {
            // Use the login name (not the user-principal / wiki name) to keep
            // the assertion format aligned with the legacy haddock template's
            // greeting, which echoed the login id the user typed. The React
            // UserBadge exposes this via data-login-name alongside
            // data-username (the latter being the display/wiki name).
            final String loginName = badge.getAttribute( "data-login-name" );
            return "G\u2019day, " + loginName + " (authenticated)";
        }
        return "G\u2019day (anonymous guest)";
    }

    /**
     * Clicks on the Sign in button in the sidebar's UserBadge and waits for
     * the login modal to appear.
     *
     * @return {@link LoginPage} instance, to allow chaining of actions.
     */
    public LoginPage clickOnLogin() {
        $( "[data-testid=user-badge-signin]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
            .click();
        $( "[data-testid=login-modal]" ).shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
        return new LoginPage();
    }

    /**
     * No-op in the React SPA — the UserBadge is always visible. Retained for
     * backward compatibility with call sites that chain {@code hoverLoginArea()
     * .authenticatedText()}.
     *
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage hoverLoginArea() {
        $( "[data-testid=user-badge]" ).shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
        return this;
    }

    /**
     * Clicks the edit link in the page header and waits for the editor to mount.
     *
     * @return {@link EditWikiPage} instance, to allow chaining of actions.
     */
    public EditWikiPage editPage() {
        $( "[data-testid=edit-page-link]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
            .click();
        $( "[data-testid=page-editor]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return new EditWikiPage();
    }

    /**
     * Navigates to the search results page for a given query.
     *
     * <p>Uses direct URL navigation to {@code /search?q=...} instead of
     * opening the search overlay and typing, which gives deterministic
     * results regardless of debounce timing or focus state.
     *
     * @param text text to search for.
     * @return {@link SearchResultsPage} instance, to allow chaining of actions.
     */
    public SearchResultsPage searchFor( final String text ) {
        final String encoded = URLEncoder.encode( text, StandardCharsets.UTF_8 );
        Selenide.open( Page.baseUrl() + "/search?q=" + encoded );
        $( "[data-testid=search-results-page]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return new SearchResultsPage();
    }

    /**
     * Logs the current user out by clicking the logout button in the UserBadge.
     *
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage clickOnLogout() {
        $( "[data-testid=user-badge-logout]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
            .click();
        // Wait for the badge to flip back to anonymous state.
        $( "[data-testid=user-badge-signin]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    /**
     * Navigates to a given view page by clicking a link with the given text
     * inside the article content area. The React SPA also renders a sidebar
     * brand link with the text "Wikantik" and a "Recently Modified" list, so
     * the search is scoped to {@code .article-prose} to avoid matching those.
     *
     * @param wikiPageName wikipage name to navigate to.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage navigateTo( final String wikiPageName ) {
        $( ".article-prose" )
            .$( By.linkText( wikiPageName ) )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
            .click();
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return this;
    }

    /**
     * Returns the sidebar element.
     *
     * @return the sidebar element.
     */
    public SelenideElement sidebar() {
        return $( ".app-sidebar" );
    }

}

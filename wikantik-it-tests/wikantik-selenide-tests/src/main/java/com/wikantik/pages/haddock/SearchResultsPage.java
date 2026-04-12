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

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Condition;
import com.codeborne.selenide.ElementsCollection;
import com.codeborne.selenide.Selenide;
import com.wikantik.pages.Page;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * Page object for the React SPA search results page.
 *
 * <p>Results are rendered as {@code [data-testid=search-result-card]} articles,
 * each containing a {@code [data-testid=search-result-link]} anchor. The card
 * exposes its page name through the {@code data-page-name} attribute so tests
 * can assert result membership without relying on DOM text which may include
 * snippet highlights.
 */
public class SearchResultsPage implements HaddockPage {

    /**
     * Open the search results page with a given query text.
     *
     * @param pageName Wiki page name (or free-text query) to search for.
     * @return {@link SearchResultsPage} instance, to allow chaining of actions.
     */
    public static SearchResultsPage open( final String pageName ) {
        final String encoded = URLEncoder.encode( pageName, StandardCharsets.UTF_8 );
        final SearchResultsPage page = Page.withUrl( Page.baseUrl() + "/search?q=" + encoded ).openAs( new SearchResultsPage() );
        $( "[data-testid=search-results-page]" ).shouldBe( Condition.visible, Duration.ofSeconds( 10 ) );
        return page;
    }

    /**
     * Returns the list of page names present in the search results.
     *
     * @return the list of page names in result order.
     */
    public List< String > pagesFound() {
        final ElementsCollection cards = $$( "[data-testid=search-result-card]" );
        return cards.asDynamicIterable()
                    .stream()
                    .map( c -> c.getAttribute( "data-page-name" ) )
                    .collect( Collectors.toList() );
    }

    /**
     * Ensures that the given page names are present in the search results.
     * Waits up to 5 seconds for each expected name to appear so transient
     * index-refresh races don't cause flakes.
     *
     * @param pageNames page names to look for.
     * @return {@link SearchResultsPage} instance, to allow chaining of actions.
     */
    public SearchResultsPage shouldContain( final String... pageNames ) {
        final ElementsCollection cards = $$( "[data-testid=search-result-card]" );
        cards.shouldHave( CollectionCondition.sizeGreaterThan( 0 ), Duration.ofSeconds( 5 ) );
        for ( final String pageName : pageNames ) {
            $( "[data-testid=search-result-card][data-page-name=\"" + pageName + "\"]" )
                .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) );
        }
        return this;
    }

    /**
     * Navigates to a view page from the search results by clicking its card.
     *
     * @param result wikipage name to navigate to.
     * @return {@link ViewWikiPage} instance, to allow chaining of actions.
     */
    public ViewWikiPage navigateTo( final String result ) {
        $( "[data-testid=search-result-card][data-page-name=\"" + result + "\"] [data-testid=search-result-link]" )
            .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
            .click();
        $( "[data-testid=page-view]" ).shouldBe( Condition.visible, DEFAULT_WAIT );
        return new ViewWikiPage();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The search results route has no single "page name" — override to
     * return the current query string so callers that ask for {@code title()}
     * still get something meaningful.
     */
    @Override
    public String wikiTitle() {
        return Selenide.$( "[data-testid=search-results-page]" )
                       .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
                       .getAttribute( "data-query" );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the rendered search results heading (e.g. "3 results for X")
     * so content assertions can match on the heading copy.
     */
    @Override
    public String wikiPageContent() {
        return Selenide.$( "[data-testid=search-results-heading]" )
                       .shouldBe( Condition.visible, Duration.ofSeconds( 5 ) )
                       .text();
    }

}

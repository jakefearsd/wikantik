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
import com.wikantik.pages.Page;

import java.time.Duration;

/**
 * Base page trait for the React SPA.
 *
 * <p>Historically named {@code HaddockPage} after the legacy JSP template; the
 * name is retained to minimise churn in the test module while the underlying
 * DOM selectors target React components that expose {@code data-testid}
 * attributes. All selectors in this hierarchy must remain resilient to CSS
 * refactors by preferring {@code data-testid} over class or id lookups.
 */
public interface HaddockPage extends Page {

    /** Max time to wait for the React SPA to mount a page view. */
    Duration DEFAULT_WAIT = Duration.ofSeconds( 10 );

    /**
     * {@inheritDoc}
     *
     * <p>Reads the active page name from the {@code data-page-name} attribute
     * exposed by {@code PageView}. This is independent of the rendered
     * markdown heading so assertions remain stable across content edits.
     */
    @Override
    default String wikiTitle() {
        return Selenide.$( "[data-testid=page-view]" )
                       .shouldBe( Condition.visible, DEFAULT_WAIT )
                       .getAttribute( "data-page-name" );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the rendered article body — the markdown-derived HTML that
     * sits inside {@code PageView}'s {@code <article class="article-prose">}.
     */
    @Override
    default String wikiPageContent() {
        return Selenide.$( "[data-testid=page-view] article.article-prose" )
                       .shouldBe( Condition.visible, DEFAULT_WAIT )
                       .text();
    }

}

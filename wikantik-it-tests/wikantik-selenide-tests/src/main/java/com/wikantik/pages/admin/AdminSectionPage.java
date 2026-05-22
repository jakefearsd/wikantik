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
package com.wikantik.pages.admin;

import com.codeborne.selenide.Selenide;
import com.wikantik.pages.Page;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.matchText;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Selenide page object for a generic admin section route (e.g.
 * {@code /admin/users}, {@code /admin/security}). Every admin section page
 * renders the shared {@code PageHeader}, whose title carries the
 * {@code .page-header-title} class, and surfaces load failures through an
 * {@code .admin-error} (or {@code .error-banner}) element. This object backs a
 * broad smoke check that each route loads behind its lazy chunk and renders a
 * non-empty header without crashing.
 */
public class AdminSectionPage implements Page {

    /** Opens an absolute admin route path (e.g. {@code "/admin/users"}). */
    public AdminSectionPage open( final String routePath ) {
        Selenide.open( Page.baseUrl() + routePath );
        return this;
    }

    /**
     * Asserts the shared {@code PageHeader} title rendered and is non-empty.
     * {@code matchText("\\S")} requires at least one non-whitespace character,
     * which catches a header that mounts but never receives its title prop.
     */
    public AdminSectionPage assertHeaderTitleVisibleAndNonEmpty() {
        $( ".page-header-title" )
            .shouldBe( visible, Duration.ofSeconds( 15 ) )
            .shouldHave( matchText( "\\S" ) );
        return this;
    }

    /**
     * Asserts no error/crash banner is present. Admin section pages render
     * their load-failure state as {@code .admin-error}; the wider SPA uses
     * {@code .error-banner}. A clean render shows neither.
     */
    public AdminSectionPage assertNoErrorBanner() {
        $( ".error-banner" ).shouldNotBe( exist );
        $( ".admin-error" ).shouldNotBe( exist );
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The admin SPA has no wiki title — return an empty default to satisfy
     * the {@link Page} contract.
     */
    @Override
    public String wikiTitle() {
        return "";
    }

    /**
     * {@inheritDoc}
     *
     * <p>See {@link #wikiTitle()}.
     */
    @Override
    public String wikiPageContent() {
        return "";
    }
}

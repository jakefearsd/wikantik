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

import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.Page;

import java.net.URI;
import java.time.Duration;

import static com.codeborne.selenide.Condition.cssClass;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Selenide page object for the admin rail (the {@code AdminSidebar} React
 * component rendered in place of the reader {@code Sidebar} while on
 * {@code /admin/*} — the "context swap").
 *
 * <p>The rail carries {@code data-testid="admin-sidebar"}; each nav link is
 * {@code data-testid="admin-nav-<slug>"} (slugs: overview, users, security,
 * apikeys, content, knowledge-graph, kg-policy, retrieval-quality); the door
 * out is {@code data-testid="admin-back-to-wiki"} (→ {@code /wiki/Main}). The
 * active link's class contains {@code active}.
 */
public class AdminSidebarPage implements Page {

    /** Asserts the admin sidebar is visible. */
    public AdminSidebarPage assertSidebarVisible() {
        $( "[data-testid=admin-sidebar]" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        return this;
    }

    /**
     * Asserts the reader Sidebar is NOT present. The reader sidebar renders a
     * {@code data-testid="sidebar-search-trigger"} button that the admin rail
     * never renders, so its absence proves the context swap happened.
     */
    public AdminSidebarPage assertReaderSidebarAbsent() {
        $( "[data-testid=sidebar-search-trigger]" ).shouldNotBe( exist );
        return this;
    }

    /** Returns the nav link element for the given slug. */
    public SelenideElement navLink( final String slug ) {
        return $( "[data-testid=admin-nav-" + slug + "]" );
    }

    /** Clicks the nav link for the given slug. */
    public AdminSidebarPage clickNav( final String slug ) {
        navLink( slug ).shouldBe( visible, Duration.ofSeconds( 10 ) ).click();
        return this;
    }

    /** Asserts the nav link for the given slug carries the {@code active} class. */
    public AdminSidebarPage assertNavActive( final String slug ) {
        navLink( slug ).shouldHave( cssClass( "active" ), Duration.ofSeconds( 10 ) );
        return this;
    }

    /** Asserts the nav link for the given slug is visible. */
    public AdminSidebarPage assertNavVisible( final String slug ) {
        navLink( slug ).shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    /** Clicks the "← Back to wiki" link out of the admin shell. */
    public AdminSidebarPage clickBackToWiki() {
        $( "[data-testid=admin-back-to-wiki]" ).shouldBe( visible, Duration.ofSeconds( 10 ) ).click();
        return this;
    }

    /** Returns the path component of the current browser URL. */
    public String currentPath() {
        return URI.create( url() ).getPath();
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

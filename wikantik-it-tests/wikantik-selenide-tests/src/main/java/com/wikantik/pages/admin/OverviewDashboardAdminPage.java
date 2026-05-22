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
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.Page;

import java.net.URI;
import java.time.Duration;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Selenide page object for the admin Overview dashboard (the {@code /admin}
 * landing). Locators use {@code data-testid} attributes baked into the React
 * {@code OverviewDashboard} component so they survive CSS/class renames.
 *
 * <p>The dashboard root carries {@code data-testid="admin-overview"}; each
 * metric cell is {@code data-testid="metric-card-<key>"}. Cards that link into
 * a section (e.g. {@code users} → {@code /admin/users}) wrap an inner
 * {@code <a>}.
 */
public class OverviewDashboardAdminPage implements Page {

    /**
     * Opens {@code /admin} and waits for the dashboard root to render.
     *
     * <p>Uses the full {@link Page#baseUrl()} so the context-path prefix isn't
     * stripped when the IT suite runs against a non-root-context WAR.
     */
    public OverviewDashboardAdminPage open() {
        Selenide.open( Page.baseUrl() + "/admin" );
        $( "[data-testid=admin-overview]" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        return this;
    }

    /** Asserts the dashboard root is visible. */
    public OverviewDashboardAdminPage assertDashboardVisible() {
        $( "[data-testid=admin-overview]" ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        return this;
    }

    /** Returns the metric-card cell for the given key. */
    public SelenideElement metricCard( final String key ) {
        return $( "[data-testid=metric-card-" + key + "]" );
    }

    /** Asserts a metric card cell is present and visible. */
    public OverviewDashboardAdminPage assertMetricCardVisible( final String key ) {
        metricCard( key ).shouldBe( visible, Duration.ofSeconds( 15 ) );
        return this;
    }

    /**
     * Clicks the inner link of a metric card cell (the {@code <a>} a linking
     * card wraps). Waits for the link to be visible before clicking.
     */
    public OverviewDashboardAdminPage clickCardLink( final String key ) {
        metricCard( key ).find( "a" ).shouldBe( visible, Duration.ofSeconds( 15 ) ).click();
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

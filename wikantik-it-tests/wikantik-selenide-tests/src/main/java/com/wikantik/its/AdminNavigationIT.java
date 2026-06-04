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

import com.codeborne.selenide.Selenide;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.admin.AdminSectionPage;
import com.wikantik.pages.admin.AdminSidebarPage;
import com.wikantik.pages.admin.OverviewDashboardAdminPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the admin sidebar navigation and the reader→admin
 * "context swap". Also covers:
 * <ul>
 *   <li>Overview dashboard: metric cards visible, card link navigates to section.</li>
 *   <li>Smoke-loads every admin section route asserting a non-empty header and
 *       no error banner (previously {@code AdminSectionSmokeIT}).</li>
 * </ul>
 *
 * <p>Confirms that on {@code /admin/*} the admin rail replaces the reader
 * Sidebar (the reader-only {@code sidebar-search-trigger} is absent), then
 * walks every {@code admin-nav-*} link asserting the route changes to the
 * expected path and the clicked link becomes {@code active}, and finally that
 * "← Back to wiki" exits the admin shell to a {@code /wiki/} route.
 */
@Execution( ExecutionMode.CONCURRENT )
public class AdminNavigationIT extends WithIntegrationTestSetup {

    /**
     * Nav slug → expected route suffix. Order mirrors the rail: Overview first,
     * then the People/Content/Knowledge groups. Paths are asserted with
     * {@code endsWith} so the test is agnostic to the IT WAR's context-path
     * prefix.
     */
    private static final List< Map.Entry< String, String > > NAV = List.of(
        Map.entry( "users",             "/admin/users" ),
        Map.entry( "security",          "/admin/security" ),
        Map.entry( "apikeys",           "/admin/apikeys" ),
        Map.entry( "content",           "/admin/content" ),
        Map.entry( "knowledge-graph",   "/admin/knowledge-graph" ),
        Map.entry( "kg-policy",         "/admin/kg-policy" ),
        Map.entry( "retrieval-quality", "/admin/retrieval-quality" ) );

    /** Admin section routes to smoke-load (mirrors the admin rail order). */
    private static final List< String > SMOKE_ROUTES = List.of(
        "/admin/users",
        "/admin/security",
        "/admin/apikeys",
        "/admin/content",
        "/admin/retrieval-quality",
        "/admin/kg-policy" );

    @BeforeEach
    void login() {
        // Fresh browser session per test so each starts anonymous — see the
        // rationale in HubDiscoveryAdminIT.login().
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        // Guard the post-login session-principal binding race before hitting /admin.
        RestSeedHelper.awaitAdminReady();
    }

    @Test
    void contextSwap_thenNavigateEachLink_thenBackToWiki() {
        // --- Overview dashboard: metric cards + card-link navigation (AdminOverviewIT) ---
        final OverviewDashboardAdminPage dashboard = new OverviewDashboardAdminPage().open();
        dashboard.assertDashboardVisible()
            .assertMetricCardVisible( "users" )
            .assertMetricCardVisible( "load" );

        // The Users card wraps a link into /admin/users; clicking it swaps route.
        dashboard.clickCardLink( "users" );
        final String afterCardClick = new AdminSidebarPage().currentPath();
        assertTrue( afterCardClick.endsWith( "/admin/users" ),
            "After clicking the Users card the path should end with /admin/users, was: " + afterCardClick );

        // --- Context swap: admin rail present, reader sidebar gone (AdminNavigationIT) ---
        new OverviewDashboardAdminPage().open();
        final AdminSidebarPage sidebar = new AdminSidebarPage();

        sidebar.assertSidebarVisible()
            .assertReaderSidebarAbsent()
            .assertNavVisible( "overview" );

        // Walk each nav link: route changes to the expected path and the link
        // gets the active class. Also assert no error banner on each section
        // (AdminSectionSmokeIT).
        final AdminSectionPage section = new AdminSectionPage();
        for ( final Map.Entry< String, String > entry : NAV ) {
            final String slug = entry.getKey();
            final String expectedSuffix = entry.getValue();
            sidebar.clickNav( slug ).assertNavActive( slug );
            final String path = sidebar.currentPath();
            assertTrue( path.endsWith( expectedSuffix ),
                "Nav '" + slug + "' should land on a path ending with " + expectedSuffix
                + ", was: " + path );
            // Smoke-check: header non-empty and no error banner on this section.
            section.assertHeaderTitleVisibleAndNonEmpty().assertNoErrorBanner();
        }

        // Smoke any routes not already visited by the nav walk.
        for ( final String route : SMOKE_ROUTES ) {
            final boolean alreadyCovered = NAV.stream()
                .anyMatch( e -> route.endsWith( e.getValue() ) );
            if ( !alreadyCovered ) {
                section.open( route )
                    .assertHeaderTitleVisibleAndNonEmpty()
                    .assertNoErrorBanner();
            }
        }

        // Door out: back to the reader wiki.
        new OverviewDashboardAdminPage().open();
        new AdminSidebarPage().clickBackToWiki();
        final String backPath = new AdminSidebarPage().currentPath();
        assertTrue( backPath.contains( "/wiki/" ),
            "After Back to wiki the path should contain /wiki/, was: " + backPath );
    }
}

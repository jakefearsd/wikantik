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
import com.wikantik.pages.admin.AdminSidebarPage;
import com.wikantik.pages.admin.OverviewDashboardAdminPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the admin sidebar navigation and the reader→admin
 * "context swap".
 *
 * <p>Confirms that on {@code /admin/*} the admin rail replaces the reader
 * Sidebar (the reader-only {@code sidebar-search-trigger} is absent), then
 * walks every {@code admin-nav-*} link asserting the route changes to the
 * expected path and the clicked link becomes {@code active}, and finally that
 * "← Back to wiki" exits the admin shell to a {@code /wiki/} route.
 */
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
        new OverviewDashboardAdminPage().open();
        final AdminSidebarPage sidebar = new AdminSidebarPage();

        // Context swap: admin rail present, reader sidebar gone.
        sidebar.assertSidebarVisible()
            .assertReaderSidebarAbsent()
            .assertNavVisible( "overview" );

        // Walk each nav link: route changes to the expected path and the link
        // gets the active class.
        for ( final Map.Entry< String, String > entry : NAV ) {
            final String slug = entry.getKey();
            final String expectedSuffix = entry.getValue();
            sidebar.clickNav( slug ).assertNavActive( slug );
            final String path = sidebar.currentPath();
            assertTrue( path.endsWith( expectedSuffix ),
                "Nav '" + slug + "' should land on a path ending with " + expectedSuffix
                + ", was: " + path );
        }

        // Door out: back to the reader wiki.
        sidebar.clickBackToWiki();
        final String backPath = sidebar.currentPath();
        assertTrue( backPath.contains( "/wiki/" ),
            "After Back to wiki the path should contain /wiki/, was: " + backPath );
    }
}

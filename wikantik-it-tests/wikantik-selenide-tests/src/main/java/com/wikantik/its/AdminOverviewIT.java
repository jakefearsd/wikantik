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
import com.wikantik.pages.admin.OverviewDashboardAdminPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the admin Overview dashboard landing ({@code /admin}).
 *
 * <p>Verifies the dashboard root renders, that at least the {@code users} and
 * {@code load} metric cards are present, and that clicking a linking card
 * (Users → {@code /admin/users}) navigates the SPA to the expected route.
 */
public class AdminOverviewIT extends WithIntegrationTestSetup {

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
    void overviewDashboard_landsAndCardLinkNavigates() {
        final OverviewDashboardAdminPage dashboard = new OverviewDashboardAdminPage().open();

        dashboard.assertDashboardVisible()
            .assertMetricCardVisible( "users" )
            .assertMetricCardVisible( "load" );

        // The Users card wraps a link into /admin/users; clicking it swaps route.
        dashboard.clickCardLink( "users" );

        final String path = dashboard.currentPath();
        assertTrue( path.endsWith( "/admin/users" ),
            "After clicking the Users card the path should end with /admin/users, was: " + path );
    }
}

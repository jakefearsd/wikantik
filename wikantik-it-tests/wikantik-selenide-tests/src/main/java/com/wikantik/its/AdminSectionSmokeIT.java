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
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Broad smoke test for the admin section routes: loads each in the browser and
 * asserts it renders without error. Each section page mounts behind its own
 * lazy chunk; this test proves they all load and render the shared
 * {@code PageHeader} (a non-empty {@code .page-header-title}) without surfacing
 * a load-failure banner.
 *
 * <p>Deliberately content-agnostic — the per-section behaviour is covered by
 * dedicated ITs. This guards against a route that 404s its chunk, throws on
 * mount, or wires up the wrong element.
 */
public class AdminSectionSmokeIT extends WithIntegrationTestSetup {

    /** Admin section routes to smoke-load. Order mirrors the admin rail. */
    private static final List< String > ROUTES = List.of(
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
    void eachSectionRoute_rendersHeaderWithoutError() {
        final AdminSectionPage page = new AdminSectionPage();
        for ( final String route : ROUTES ) {
            page.open( route )
                .assertHeaderTitleVisibleAndNonEmpty()
                .assertNoErrorBanner();
        }
    }
}

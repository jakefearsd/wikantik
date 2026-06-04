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
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pages.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.net.URI;
import java.time.Duration;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the admin-route auth guard on an <em>anonymous</em>
 * session.
 *
 * <p>{@code AdminLayout} renders {@code <Navigate to="/wiki/Main" replace />}
 * for any visitor who is not an authenticated Admin, so an anonymous hit on
 * {@code /admin} must end up on the reader (a {@code /wiki/} path) with the
 * admin shell — specifically the admin rail
 * ({@code data-testid="admin-sidebar"}) — never mounted.
 *
 * <p>This test never logs in. It opens a fresh browser session so no leaked
 * auth state can mask the guard.
 */
@Execution( ExecutionMode.CONCURRENT )
public class AdminAuthRedirectIT extends WithIntegrationTestSetup {

    @BeforeEach
    void anonymousSession() {
        // Clean session: no cookies / localStorage / React auth state. We do
        // NOT log in — the whole point is to exercise the anonymous guard.
        Selenide.closeWebDriver();
    }

    @Test
    void anonymousHittingAdmin_redirectsToReader_andAdminShellAbsent() {
        Selenide.open( Page.baseUrl() + "/admin" );

        // The guard navigates to /wiki/Main; the reader article view mounts.
        $( "[data-testid=page-view]" ).shouldBe(
            com.codeborne.selenide.Condition.visible, Duration.ofSeconds( 15 ) );

        // End state is the reader, not the admin panel.
        final String path = URI.create( WebDriverRunner.url() ).getPath();
        assertTrue( path.contains( "/wiki/" ),
            "Anonymous /admin should redirect to a /wiki/ path, was: " + path );

        // The admin rail must never have mounted for an anonymous visitor.
        $( "[data-testid=admin-sidebar]" ).shouldNotBe( exist );
    }
}

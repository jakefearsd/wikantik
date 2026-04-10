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

import com.codeborne.selenide.Condition;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.Page;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Selenide page object for the Existing Hubs panel that lives inside the Hub
 * Discovery admin tab. Locators use {@code data-testid} attributes baked into
 * the React components.
 */
public class HubOverviewAdminPage implements Page {

    public HubOverviewAdminPage expandPanel() {
        $( "[data-testid='existing-hubs-toggle']" ).shouldBe( visible ).click();
        $( "[data-testid='existing-hubs-panel']" )
            .shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public SelenideElement hubRow( final String hubName ) {
        return $( "[data-testid='existing-hub-row-" + hubName + "']" );
    }

    public HubOverviewAdminPage clickHubRow( final String hubName ) {
        hubRow( hubName ).shouldBe( visible ).click();
        $( "[data-testid='existing-hub-drilldown-" + hubName + "']" )
            .shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public SelenideElement memberRow( final String hubName, final String memberName ) {
        return $( "[data-testid='existing-hub-member-" + hubName + "-" + memberName + "']" );
    }

    public HubOverviewAdminPage clickRemoveMember( final String hubName, final String memberName ) {
        $( "[data-testid='existing-hub-member-remove-" + hubName + "-" + memberName + "']" )
            .shouldBe( visible ).click();
        $( "[data-testid='existing-hub-member-remove-confirm-modal']" )
            .shouldBe( visible, Duration.ofSeconds( 5 ) );
        return this;
    }

    public HubOverviewAdminPage confirmRemoveMember() {
        $( "[data-testid='existing-hub-member-remove-confirm-ok']" )
            .shouldBe( visible ).click();
        return this;
    }

    public HubOverviewAdminPage cancelRemoveMember() {
        $( "[data-testid='existing-hub-member-remove-confirm-cancel']" )
            .shouldBe( visible ).click();
        return this;
    }

    public HubOverviewAdminPage assertMemberAbsent( final String hubName, final String memberName ) {
        memberRow( hubName, memberName )
            .shouldNotBe( exist, Duration.ofSeconds( 10 ) );
        return this;
    }

    public HubOverviewAdminPage assertMemberPresent( final String hubName, final String memberName ) {
        memberRow( hubName, memberName ).shouldBe( visible );
        return this;
    }

    public HubOverviewAdminPage assertHubRowPresent( final String hubName ) {
        hubRow( hubName ).shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public HubOverviewAdminPage assertNearMissSectionPresent( final String hubName ) {
        $( "[data-testid='existing-hub-nearmiss-" + hubName + "']" )
            .shouldBe( Condition.exist );
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The admin React SPA has no wiki title — provide an empty default so tests
     * that use only the admin-specific helpers on this page object still satisfy the
     * {@link Page} contract. Mirrors {@link HubDiscoveryAdminPage#wikiTitle()}.
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

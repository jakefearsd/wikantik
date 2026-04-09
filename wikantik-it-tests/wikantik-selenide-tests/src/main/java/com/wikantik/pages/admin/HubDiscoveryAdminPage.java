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
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pages.Page;

import java.time.Duration;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Selenide page object for the hub-discovery admin tab. Locators use {@code data-testid}
 * attributes baked into the React components so they are resilient to CSS/class renames.
 */
public class HubDiscoveryAdminPage implements Page {

    public HubDiscoveryAdminPage open() {
        Selenide.open( "/admin/knowledge" );
        // Activate the Hub Discovery tab. The React component renders the tab panel wrapper
        // with data-testid="hub-discovery-tab"; clicking the tab button with label "Hub Discovery"
        // switches the active tab.
        $( ".admin-tab" ).shouldBe( visible );
        $$( ".admin-tab" ).findBy( Condition.text( "Hub Discovery" ) ).click();
        $( "[data-testid='hub-discovery-tab']" ).shouldBe( visible );
        return this;
    }

    public HubDiscoveryAdminPage clickRunDiscovery() {
        $( "[data-testid='hub-discovery-run']" ).shouldBe( visible ).click();
        return this;
    }

    public HubDiscoveryAdminPage waitForSuccessToast() {
        $( "[data-testid='hub-discovery-toast-success']" )
            .shouldBe( visible, Duration.ofSeconds( 30 ) );
        return this;
    }

    public HubDiscoveryAdminPage waitForErrorToast() {
        $( "[data-testid='hub-discovery-toast-error']" )
            .shouldBe( visible, Duration.ofSeconds( 10 ) );
        return this;
    }

    public SelenideElement card( final int proposalId ) {
        return $( "[data-testid='hub-discovery-card-" + proposalId + "']" );
    }

    public HubDiscoveryAdminPage setName( final int proposalId, final String name ) {
        final SelenideElement input = $( "[data-testid='hub-discovery-name-" + proposalId + "']" );
        input.shouldBe( visible );
        input.clear();
        input.sendKeys( name );
        return this;
    }

    public HubDiscoveryAdminPage clickAccept( final int proposalId ) {
        $( "[data-testid='hub-discovery-accept-" + proposalId + "']" ).shouldBe( visible ).click();
        return this;
    }

    public HubDiscoveryAdminPage clickDismiss( final int proposalId ) {
        $( "[data-testid='hub-discovery-dismiss-" + proposalId + "']" ).shouldBe( visible ).click();
        return this;
    }

    public HubDiscoveryAdminPage assertCardDisappears( final int proposalId ) {
        $( "[data-testid='hub-discovery-card-" + proposalId + "']" )
            .shouldNotBe( exist, Duration.ofSeconds( 10 ) );
        return this;
    }

    public HubDiscoveryAdminPage assertCardStillPresent( final int proposalId ) {
        $( "[data-testid='hub-discovery-card-" + proposalId + "']" )
            .shouldBe( Condition.visible );
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The admin React SPA has no wiki title — provide an empty default so tests
     * that use only the admin-specific helpers on this page object still satisfy the
     * {@link Page} contract.
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

    // Selenide's collection shortcut used above.
    private static com.codeborne.selenide.ElementsCollection $$( final String selector ) {
        return com.codeborne.selenide.Selenide.$$( selector );
    }
}

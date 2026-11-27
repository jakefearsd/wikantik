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
package com.wikantik.its.work;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.google.gson.JsonObject;
import com.wikantik.its.RestSeedHelper;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.Page;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.codeborne.selenide.CollectionCondition.sizeGreaterThanOrEqual;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A change and a deployment in a real browser: on an item's page a person opens a change, asks for review, approves
 * it and records the merge; on the Deployments page they request a deployment carrying the item, then approve it,
 * mark it running, are stopped from verifying it with no evidence, and verify it with evidence. The item's page then
 * says what it shipped in. The project, product and item are seeded over REST; every step under test is a click.
 */
public class WorkChangeDeploymentIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 20 );

    private final String suffix = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );
    private final String projectKey = "UC" + suffix.substring( 0, 6 ).toUpperCase( Locale.ROOT );
    private final String productKey = "uic-" + suffix;
    private final String productName = "Change product " + suffix;
    private final String version = "9.9." + suffix;

    @BeforeEach
    void freshBrowser() {
        Selenide.closeWebDriver();
    }

    @AfterEach
    void closeBrowser() {
        Selenide.closeWebDriver();
    }

    @Test
    void aChangeIsReviewedAndMergedAndADeploymentCarriesTheItemToVerified() throws Exception {
        final WorkIt.Caller admin = WorkIt.admin();
        final String itemKey = seedItem( admin );
        final String branch = "work/" + itemKey + "-ui";
        final String evidence = "health 200 " + suffix + "\nsmoke ok";

        signIn( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();

        // ---- the change, on the item's page
        open( "/work/item/" + itemKey );
        final SelenideElement changes = $( "section[aria-label=Changes]" ).shouldBe( visible, WAIT );
        $( "[data-testid=policy-note]" ).shouldHave( text( "Effective level:" ), WAIT );
        changes.$$( "button" ).findBy( exactText( "Open a change" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-change-branch" ).shouldBe( visible, WAIT ), branch );
        type( $( "#work-change-head" ), "abc1234def" );
        changes.$$( "button" ).findBy( exactText( "Open change" ) ).click();
        final SelenideElement row = changes.$$( "li.work-change" ).findBy( text( branch ) ).shouldBe( visible, WAIT );
        row.$( ".work-chip" ).shouldHave( exactText( "open" ), WAIT );
        row.shouldHave( text( "head abc1234d" ), WAIT );

        row.$$( "button" ).findBy( exactText( "Ask for review" ) ).shouldBe( visible, WAIT ).click();
        row.$( ".work-chip" ).shouldHave( exactText( "in review" ), WAIT );
        row.$$( "button" ).findBy( exactText( "Approve" ) ).shouldBe( visible, WAIT ).click();
        row.shouldHave( text( "approved by" ), WAIT );

        // The merged commit is offered as the approved one. A squash or merge commit also counts as reviewed
        // while the approval stands on the change's head.
        row.$( "input[aria-label='Merged commit for " + branch + "']" ).shouldHave( value( "abc1234def" ), WAIT );
        row.$$( "button" ).findBy( exactText( "Record merge" ) ).click();
        row.$( ".work-chip" ).shouldHave( exactText( "merged after review" ), WAIT );
        row.shouldHave( text( "merged by" ), WAIT );
        row.shouldHave( text( "merged by" ), WAIT ).shouldHave( text( "· abc1234d" ), WAIT );
        // A merged change offers nothing more.
        row.$$( "button" ).findBy( exactText( "Record merge" ) ).shouldNot( exist );
        assertEquals( "merged", onlyChange( admin, itemKey ).get( "state" ).getAsString(), "the server agrees" );

        // ---- the deployment
        open( "/work/deployments" );
        $( "h1.work-heading" ).shouldHave( text( "Deployments" ), WAIT );
        $( "select[aria-label=Product]" ).shouldBe( visible, WAIT ).selectOptionByValue( productKey );
        $$( "button" ).findBy( exactText( "Request a deployment" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-dep-version" ).shouldBe( visible, WAIT ), version );
        type( $( "#work-dep-env" ), "production" );
        type( $( "#work-dep-items" ), itemKey );
        $$( "button" ).findBy( exactText( "Request deployment" ) ).click();
        final SelenideElement listed = $$( "li.work-deployment" ).findBy( text( version ) ).shouldBe( visible, WAIT );
        listed.$( ".work-chip" ).shouldHave( exactText( "waiting for approval" ), WAIT );
        listed.shouldHave( text( "1 item" ), WAIT );
        listed.$( "a" ).click();

        final SelenideElement heading = $( "h1.work-heading" ).shouldHave( text( productKey + " " + version ), WAIT );
        heading.$( ".work-chip" ).shouldHave( exactText( "waiting for approval" ), WAIT );
        $$( "article li a" ).findBy( exactText( itemKey ) ).shouldBe( visible, WAIT );

        $$( "button" ).findBy( exactText( "Approve" ) ).shouldBe( visible, WAIT ).click();
        heading.$( ".work-chip" ).shouldHave( exactText( "approved" ), WAIT );
        $$( "button" ).findBy( exactText( "Mark as running" ) ).shouldBe( visible, WAIT ).click();
        heading.$( ".work-chip" ).shouldHave( exactText( "running" ), WAIT );

        // No evidence, no verification: the page says why and the server hears nothing.
        final SelenideElement verify = $$( "button" ).findBy( exactText( "Mark as verified" ) ).shouldBe( visible, WAIT );
        verify.click();
        $( "[role=alert]" ).shouldHave( text( "Attach the health and smoke-test output before marking this verified." ), WAIT );
        assertEquals( "running", deploymentState( admin, itemKey ), "nothing was sent" );

        type( $( "#work-dep-evidence" ), evidence );
        verify.click();
        heading.$( ".work-chip" ).shouldHave( exactText( "verified" ), WAIT );
        $( "section[aria-label=Evidence] pre" ).shouldHave( text( "health 200 " + suffix ), WAIT );
        // requested, approved, running, verified
        $$( "ul.work-timeline li" ).shouldHave( sizeGreaterThanOrEqual( 4 ), WAIT );
        $$( "ul.work-timeline li" ).findBy( text( "moved it to verified" ) ).shouldBe( visible, WAIT );
        $$( "button" ).findBy( exactText( "Mark as rolled back" ) ).shouldBe( visible, WAIT );
        assertEquals( "verified", deploymentState( admin, itemKey ), "the server agrees" );

        // ---- back on the item: it says what it shipped in
        open( "/work/item/" + itemKey );
        $( "[data-testid=shipped-in]" ).shouldHave( text( "Shipped in " + productKey + " " + version ), WAIT )
            .shouldHave( text( "(production)" ), WAIT );
    }

    /** A project, a product and a story that targets it, in progress; returns the story's key. */
    private String seedItem( final WorkIt.Caller admin ) throws Exception {
        assertEquals( 201, admin.send( "POST", "/api/work/projects",
            Map.of( "key", projectKey, "name", "Change flow " + suffix ) ).statusCode(), "create the project" );
        assertEquals( 201, admin.send( "POST", "/api/work/products",
            Map.of( "key", productKey, "name", productName, "defaultBranch", "main" ) ).statusCode(), "create the product" );
        final HttpResponse< String > created = admin.send( "POST", "/api/work/items", Map.of( "project", projectKey,
            "level", "story", "title", "Ships with a change " + suffix, "productKey", productKey ) );
        assertEquals( 201, created.statusCode(), "create the story: " + created.body() );
        final String key = itemOf( WorkIt.json( created ).getAsJsonObject() ).get( "key" ).getAsString();
        for ( final String status : new String[] { "ready", "in_progress" } ) {
            final int itemVersion = itemOf( WorkIt.json( admin.get( "/api/work/items/" + key ) ).getAsJsonObject() )
                .get( "version" ).getAsInt();
            final HttpResponse< String > moved = admin.send( "PATCH", "/api/work/items/" + key,
                Map.of( "expectedVersion", itemVersion, "changes", Map.of( "status", status ) ) );
            assertEquals( 200, moved.statusCode(), "move the story to " + status + ": " + moved.body() );
        }
        return key;
    }

    private static JsonObject itemOf( final JsonObject view ) {
        return view.has( "item" ) ? view.getAsJsonObject( "item" ) : view;
    }

    private static JsonObject onlyChange( final WorkIt.Caller admin, final String itemKey ) throws Exception {
        final HttpResponse< String > r = admin.get( "/api/work/items/" + itemKey + "/changes" );
        assertEquals( 200, r.statusCode(), WorkIt.status( r ) );
        return WorkIt.json( r ).getAsJsonObject().getAsJsonArray( "changes" ).get( 0 ).getAsJsonObject();
    }

    /** The state of the one deployment of this test's product, as the server reports it. */
    private String deploymentState( final WorkIt.Caller admin, final String itemKey ) throws Exception {
        final HttpResponse< String > r = admin.get( "/api/work/deployments?product=" + productKey );
        assertEquals( 200, r.statusCode(), "deployments of the product carrying " + itemKey + ": " + WorkIt.status( r ) );
        return WorkIt.json( r ).getAsJsonObject().getAsJsonArray( "deployments" ).get( 0 ).getAsJsonObject()
            .get( "state" ).getAsString();
    }

    // ---- helpers ----

    private static void signIn( final String login, final String password ) {
        ViewWikiPage.open( "Main" ).clickOnLogin().performLogin( login, password );
        $( "[data-testid=user-badge][data-authenticated=true]" ).shouldBe( exist, WAIT );
    }

    private static void open( final String path ) {
        Selenide.open( Page.baseUrl() + path );
    }

    /** Sets a React-controlled input or textarea so React's onChange fires (sendKeys can bypass its value tracker). */
    private static void type( final SelenideElement element, final String value ) {
        element.shouldBe( visible, WAIT );
        Selenide.executeJavaScript(
            "var el = arguments[0];"
            + " var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;"
            + " Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, arguments[1]);"
            + " el.dispatchEvent(new Event('input', { bubbles: true }));",
            element, value );
    }
}

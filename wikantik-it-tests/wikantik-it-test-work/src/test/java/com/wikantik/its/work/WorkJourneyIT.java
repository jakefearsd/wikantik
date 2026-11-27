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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.work.WorkIt.Caller;
import com.wikantik.pages.Page;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The everyday path through work management in a real browser, one person after another: a reporter files a request
 * on the intake form; the lead hears of it in the inbox, labels it (typing the labels and pressing Save, never Enter),
 * assigns it and makes it ready; the member finds it under "Waiting on you" with the lead's name, sees no Settings
 * tab, and answers; the reporter reads the answer and the status on their own reduced page. Each step asserts what a
 * person reads on the page, so a change that breaks the page (not just the API) fails here.
 */
public class WorkJourneyIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 20 );

    private final String suffix = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 6 ).toUpperCase( Locale.ROOT );
    private final String low = suffix.toLowerCase( Locale.ROOT );

    @BeforeEach
    void freshBrowser() {
        Selenide.closeWebDriver();
    }

    @AfterEach
    void closeBrowser() {
        Selenide.closeWebDriver();
    }

    @Test
    void aRequestGoesFromTheReporterThroughTheLeadToTheMemberAndBack() throws Exception {
        final String project = "JR" + suffix;
        final String title = "Export fails " + suffix;
        final People p = people( project );

        // The reporter files it on the intake form and lands on their own page for it.
        signIn( p.reporterLogin );
        open( "/work/submit" );
        $( "#work-submit-project" ).shouldBe( visible, WAIT ).selectOptionByValue( project );
        type( $( "#work-submit-title" ), title );
        type( $( "#work-submit-body" ), "The export stops at row 100." );
        $$( "button" ).findBy( text( "File request" ) ).click();
        $( "article.work-item-reported h1" ).shouldHave( text( title ), WAIT );
        final String key = $( "article.work-item-reported .work-item-key" ).getText().trim();
        $( "section[aria-label='History']" ).shouldHave( text( "filed this" ) );

        // The lead hears of it, as a request.
        WorkIt.await( "the lead's inbox", p.lead, "/api/work/inbox", b -> b.contains( key ) );
        Selenide.closeWebDriver();
        signIn( p.leadLogin );
        open( "/work/inbox" );
        final SelenideElement request = $$( "li.work-notice" ).findBy( text( key ) ).shouldBe( visible, WAIT );
        request.$( ".work-chip" ).shouldHave( text( "Request" ) );

        // Triage on the item page: two labels typed and saved without Enter, an assignee, a status.
        open( "/work/item/" + key );
        $( "button[aria-label='Edit Labels']" ).shouldBe( visible, WAIT ).click();
        type( $( "input[aria-label='Add a label']" ), "export, urgent" );
        $( "button[aria-label='Save Labels']" ).click();
        field( "Labels" ).shouldHave( text( "export" ), WAIT ).shouldHave( text( "urgent" ) );
        assertEquals( List.of( "export", "urgent" ), labels( p.lead, key ), "both typed labels were saved" );

        $( "button[aria-label='Edit Assignee']" ).shouldBe( visible, WAIT ).click();
        $( "select[aria-label='Assignee']" ).shouldBe( visible, WAIT ).selectOptionContainingText( p.memberName );
        $( "button[aria-label='Save Assignee']" ).click();
        field( "Assignee" ).shouldHave( text( p.memberName ), WAIT );

        $( "button[aria-label='Edit Status']" ).shouldBe( visible, WAIT ).click();
        $( "select[aria-label='Status']" ).shouldBe( visible, WAIT ).selectOption( "Ready" );
        $( "button[aria-label='Save Status']" ).click();
        field( "Status" ).shouldHave( text( "Ready" ), WAIT );

        // The lead has a Settings tab; the board shows the card.
        open( "/work/" + project );
        $( "article.work-card[data-item-key='" + key + "']" ).shouldBe( visible, WAIT );
        $$( "[role=tab]" ).findBy( text( "Settings" ) ).shouldBe( visible );

        // The member: named hand-over on the home page, an "Assigned" notice, no Settings tab, and an answer.
        WorkIt.await( "the member's inbox", p.member, "/api/work/inbox", b -> b.contains( key ) );
        Selenide.closeWebDriver();
        signIn( p.memberLogin );
        open( "/work" );
        final SelenideElement waiting = $( "[data-testid=waiting-lane]" ).shouldBe( visible, WAIT );
        waiting.shouldHave( text( key ) ).shouldHave( text( "Assigned by " + p.leadName ) );
        open( "/work/inbox" );
        $$( "li.work-notice" ).filterBy( text( key ) ).findBy( text( "assigned this to" ) ).shouldBe( visible, WAIT )
            .$( ".work-chip" ).shouldHave( text( "Assigned" ) );
        open( "/work/" + project );
        $$( "[role=tab]" ).findBy( text( "Board" ) ).shouldBe( visible, WAIT );
        $$( "[role=tab]" ).findBy( text( "Settings" ) ).shouldNot( exist );
        open( "/work/item/" + key );
        type( $( "textarea[aria-label='Add a comment']" ), "Fixed in the next build." );
        $$( ".work-comment-new button" ).findBy( text( "Comment" ) ).click();
        $( "section[aria-label='Discussion']" ).shouldHave( text( "Fixed in the next build." ), WAIT );

        // The reporter reads the answer and the new status, and nothing about labels or the assignee.
        WorkIt.await( "the reporter's inbox", p.reporter, "/api/work/inbox", b -> b.contains( "commented" ) );
        Selenide.closeWebDriver();
        signIn( p.reporterLogin );
        open( "/work/inbox" );
        $$( "li.work-notice" ).findBy( text( key ) ).shouldBe( visible, WAIT );
        open( "/work/item/" + key );
        final SelenideElement page = $( "article.work-item-reported" ).shouldBe( visible, WAIT );
        page.shouldHave( text( "Fixed in the next build." ) ).shouldHave( text( "moved this to ready" ) );
        page.shouldNotHave( text( "urgent" ) ).shouldNotHave( text( "Assignee" ) );
    }

    @Test
    void aNewItemKeepsALabelTypedWithoutEnter() throws Exception {
        final String project = "JN" + suffix;
        final String title = "Labelled at birth " + suffix;
        final People p = people( project );

        signIn( p.leadLogin );
        open( "/work/" + project );
        $( "button.work-new-item" ).shouldBe( visible, WAIT ).click();
        type( $x( "Title" ), title );
        type( $( "input[aria-label='Add a label']" ), "fresh" );
        $$( "button" ).findBy( text( "Create item" ) ).click();
        $$( "article.work-card" ).findBy( text( title ) ).shouldBe( visible, WAIT );

        final JsonArray items = WorkIt.json( p.lead.get( "/api/work/items?project=" + project ) ).getAsJsonArray();
        assertEquals( 1, items.size() );
        final String key = items.get( 0 ).getAsJsonObject().get( "key" ).getAsString();
        assertEquals( List.of( "fresh" ), labels( p.lead, key ), "the label typed but not added was saved" );
    }

    // ---- helpers ----

    private record People( Caller lead, Caller member, Caller reporter, String leadLogin, String memberLogin,
                           String reporterLogin, String leadName, String memberName ) {
    }

    /** A project with a lead, a member and a person who only reports, each a fresh account. */
    private People people( final String project ) throws Exception {
        final Caller admin = WorkIt.admin();
        final String leadLogin = "jlead" + low;
        final String memberLogin = "jmember" + low;
        final String reporterLogin = "jreport" + low;
        final String leadName = "Journey Lead " + suffix;
        final String memberName = "Journey Member " + suffix;
        final Caller lead = WorkIt.account( admin, leadLogin, leadName );
        final Caller member = WorkIt.account( admin, memberLogin, memberName );
        final Caller reporter = WorkIt.account( admin, reporterLogin, "Journey Reporter " + suffix );
        final HttpResponse< String > made = admin.send( "POST", "/api/work/projects", Map.of( "key", project,
            "name", "Journey " + project, "description", "d" ) );
        assertEquals( 201, made.statusCode(), made.body() );
        for ( final String[] row : new String[][] { { leadLogin, "lead" }, { memberLogin, "member" }, { reporterLogin, "reporter" } } ) {
            assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + project + "/members", Map.of( "principal", row[ 0 ],
                "principalKind", "user", "role", row[ 1 ] ) ).statusCode(), row[ 0 ] );
        }
        return new People( lead, member, reporter, leadLogin, memberLogin, reporterLogin, leadName, memberName );
    }

    private static List< String > labels( final Caller who, final String key ) throws Exception {
        final JsonObject item = WorkIt.json( who.get( "/api/work/items/" + key ) ).getAsJsonObject().getAsJsonObject( "item" );
        final List< String > out = new ArrayList<>();
        item.getAsJsonArray( "labels" ).forEach( l -> out.add( l.getAsString() ) );
        return out;
    }

    /** The row of the Details panel for one field. */
    private static SelenideElement field( final String label ) {
        return $$( ".work-field" ).findBy( text( label ) );
    }

    /** A form control by its label text. */
    private static SelenideElement $x( final String label ) {
        return Selenide.$x( "//label[normalize-space()='" + label + "']/following-sibling::*[self::input or self::textarea][1]" );
    }

    private static void signIn( final String login ) {
        ViewWikiPage.open( "Main" ).clickOnLogin().performLogin( login, WorkIt.PASSWORD );
        $( "[data-testid=user-badge][data-authenticated=true]" ).shouldBe( exist, WAIT );
    }

    private static void open( final String path ) {
        Selenide.open( Page.baseUrl() + path );
    }

    /** Sets a React-controlled input so React's onChange fires (sendKeys can bypass its value tracker). */
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

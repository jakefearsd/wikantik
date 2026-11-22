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
package com.wikantik.its.rest;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.its.RestSeedHelper;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.its.rest.WorkIt.Caller;
import com.wikantik.pages.Page;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprints in a real browser: a lead plans two tasks into a sprint, estimates them, starts it, finishes one on the
 * board and closes the sprint, and the report shows what was completed. A member sees the Sprints tab but no Start or
 * Close; a user who is not a member sees only not found.
 */
public class WorkSprintsIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 20 );
    private static final String MEMBER = "Alice";
    private static final String MEMBER_PASSWORD = "password";
    private static final String OUTSIDER = "Bob";
    private static final String OUTSIDER_PASSWORD = "password";

    private final String suffix = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 6 ).toUpperCase( java.util.Locale.ROOT );

    @BeforeEach
    void freshBrowser() {
        Selenide.closeWebDriver();
    }

    @AfterEach
    void closeBrowser() {
        Selenide.closeWebDriver();
    }

    @Test
    void aLeadRunsASprintAndTheReportShowsWhatWasCompleted() throws Exception {
        final String project = "SP" + suffix;
        final Caller admin = WorkIt.admin();
        final String first = seedProject( admin, project, "First task " + suffix );
        final String second = seedItem( admin, project, "Second task " + suffix );

        signIn( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();

        // Create "Sprint 1" from the Sprints tab.
        open( "/work/" + project + "/sprints" );
        $$( "button" ).findBy( text( "New sprint" ) ).shouldBe( visible, WAIT ).click();
        type( $x( "Name" ), "Sprint 1" );
        type( $x( "Starts on" ), LocalDate.now().toString() );
        type( $x( "Ends on" ), LocalDate.now().plusDays( 4 ).toString() );
        $$( "button" ).findBy( text( "Create sprint" ) ).click();
        $( "section[aria-label='Sprint 1']" ).shouldBe( visible, WAIT );

        // Plan both tasks into it.
        for ( final String key : new String[] { first, second } ) {
            $( "select[aria-label='Plan " + key + " into']" ).shouldBe( visible, WAIT ).selectOption( "Sprint 1" );
            $( "section[aria-label='Sprint 1'] li[data-item-key='" + key + "']" ).shouldBe( visible, WAIT );
        }

        // Estimate 3 and 5 on the item pages.
        estimate( first, "3" );
        estimate( second, "5" );

        // Start it.
        open( "/work/" + project + "/sprints" );
        $$( "section[aria-label='Sprint 1'] button" ).findBy( text( "Start" ) ).shouldBe( visible, WAIT ).click();
        $( "section[aria-label='Sprint 1']" ).shouldHave( text( "active" ), WAIT );

        // Finish the first task on the board (which defaults to the active sprint).
        open( "/work/" + project );
        $( "article.work-card[data-item-key='" + first + "'] select[aria-label^='Move ']" ).shouldBe( visible, WAIT )
            .selectOption( "Done" );
        awaitServerStatus( first, "done" );

        // Close the sprint, moving unfinished work to the Backlog (the dialog's default).
        open( "/work/" + project + "/sprints" );
        $$( "section[aria-label='Sprint 1'] button" ).findBy( text( "Close sprint" ) ).shouldBe( visible, WAIT ).click();
        $$( ".modal-actions button" ).findBy( text( "Close sprint" ) ).shouldBe( visible, WAIT ).click();
        $( "section[aria-label='Closed sprints']" ).shouldBe( visible, WAIT );

        // The report.
        final long sprintId = sprintId( admin, project );
        open( "/work/" + project + "/sprints/" + sprintId );
        $( ".work-sprint-completed" ).shouldHave( text( "Completed 1 of 2 items, 3 of 8 points" ), WAIT );
        assertEquals( 1, $$( "svg.work-burndown circle[data-series=remaining]" ).size(),
            "one burndown point for the one elapsed day" );

        // The second task is back in the backlog.
        open( "/work/" + project + "/sprints" );
        $( "section[aria-label='Backlog'] li[data-item-key='" + second + "']" ).shouldBe( visible, WAIT );
    }

    @Test
    void aMemberSeesNoSprintControlsAndAStrangerSeesNotFound() throws Exception {
        final String project = "SM" + suffix;
        final Caller admin = WorkIt.admin();
        seedProject( admin, project, "Task " + suffix );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/" + project + "/members", Map.of( "principal", MEMBER,
            "principalKind", "user", "role", "member" ) ).statusCode() );
        final HttpResponse< String > sprint = admin.send( "POST", "/api/work/projects/" + project + "/sprints", Map.of(
            "name", "Planned", "startsOn", LocalDate.now().toString(), "endsOn", LocalDate.now().plusDays(4).toString() ) );
        assertEquals( 201, sprint.statusCode(), sprint.body() );
        final long id = WorkIt.json( sprint ).getAsJsonObject().get( "id" ).getAsLong();

        signIn( MEMBER, MEMBER_PASSWORD );
        open( "/work/" + project );
        $$( "[role=tab]" ).findBy( text( "Sprints" ) ).shouldBe( visible, WAIT ).click();
        $( "section[aria-label='Planned']" ).shouldBe( visible, WAIT );
        $$( "button" ).findBy( text( "Start" ) ).shouldNot( exist );
        $$( "button" ).findBy( text( "Close sprint" ) ).shouldNot( exist );
        $$( "button" ).findBy( text( "New sprint" ) ).shouldNot( exist );

        Selenide.closeWebDriver();
        signIn( OUTSIDER, OUTSIDER_PASSWORD );
        open( "/work/" + project + "/sprints/" + id );
        $( ".admin-empty-message" ).shouldHave( text( "Not found" ), WAIT );
    }

    // ---- helpers ----

    private String seedProject( final Caller admin, final String project, final String taskTitle ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/projects", Map.of( "key", project,
            "name", "Sprint IT " + project, "description", "d" ) );
        assertEquals( 201, r.statusCode(), r.body() );
        return seedItem( admin, project, taskTitle );
    }

    private String seedItem( final Caller admin, final String project, final String title ) throws Exception {
        final HttpResponse< String > r = admin.send( "POST", "/api/work/items", Map.of( "project", project,
            "level", "task", "title", title ) );
        assertEquals( 201, r.statusCode(), r.body() );
        return WorkIt.json( r ).getAsJsonObject().getAsJsonObject( "item" ).get( "key" ).getAsString();
    }

    private static long sprintId( final Caller admin, final String project ) throws Exception {
        final JsonArray sprints = WorkIt.json( admin.get( "/api/work/projects/" + project + "/sprints" ) ).getAsJsonArray();
        assertEquals( 1, sprints.size() );
        final JsonObject s = sprints.get( 0 ).getAsJsonObject();
        assertEquals( "closed", s.get( "state" ).getAsString() );
        return s.get( "id" ).getAsLong();
    }

    private static void estimate( final String key, final String points ) {
        open( "/work/item/" + key );
        $( "button[aria-label='Edit Estimate']" ).shouldBe( visible, WAIT ).click();
        type( $( "input[aria-label='Estimate']" ), points );
        $( "button[aria-label='Save Estimate']" ).click();
        $( ".work-field-value" ).shouldBe( visible, WAIT );
        $$( ".work-field" ).findBy( text( "Estimate" ) ).shouldHave( text( points + " pts" ), WAIT );
    }

    /** A form control by its label text. */
    private static SelenideElement $x( final String label ) {
        return Selenide.$x( "//label[normalize-space()='" + label + "']/following-sibling::*[self::input or self::textarea][1]" );
    }

    private static void signIn( final String login, final String password ) {
        ViewWikiPage.open( "Main" ).clickOnLogin().performLogin( login, password );
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

    /** Polls the item through the REST API until the server reports {@code status}. */
    private static void awaitServerStatus( final String itemKey, final String status ) {
        final long deadline = System.currentTimeMillis() + WAIT.toMillis();
        String last = "";
        while ( System.currentTimeMillis() < deadline ) {
            try {
                last = RestSeedHelper.get( "/api/work/items/" + itemKey );
                if ( last.matches( "(?s).*\"status\"\\s*:\\s*\"" + status + "\".*" ) ) {
                    return;
                }
            } catch ( final Exception e ) {
                last = e.toString();
            }
            Selenide.sleep( 250 );
        }
        throw new AssertionError( "the server never reported " + itemKey + " as " + status + "; last: " + last );
    }
}

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

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.its.RestSeedHelper;
import com.wikantik.its.WithIntegrationTestSetup;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.Page;
import com.wikantik.pages.spa.SearchResultsPage;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.$x;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work management in a real browser (the feature flag is on in this module's cascade): an admin creates a project
 * and a product, adds a member, creates a story from the board, moves it with the keyboard "Move to…" menu, edits
 * its text and comments on it, and the timeline shows what happened. The member then sees the board and the item;
 * a user who is not a member sees only the neutral not-found state, no "Work" navigation entry, and cannot find the
 * item through the wiki's own search.
 *
 * <p>The agent flow ({@link #agentClaimsAndHandsOffAndEveryStepIsVisible()}): an admin creates a queue and registers an
 * agent, whose key the page shows once; a story goes into the queue and to Ready; the agent claims it and hands it to the
 * admin over REST; the board card, the item's claim panel, "Waiting on you" and the project's "Assignments and claims"
 * page each show what happened, naming the agent.
 */
public class WorkBoardIT extends WithIntegrationTestSetup {

    private static final Duration WAIT = Duration.ofSeconds( 20 );
    private static final String MEMBER = "Alice";
    private static final String MEMBER_PASSWORD = "password";
    private static final String OUTSIDER = "Bob";
    private static final String OUTSIDER_PASSWORD = "password";

    private final String suffix = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );
    private final String workToken = "zqwork" + suffix;
    private final String controlToken = "zqcontrol" + suffix;
    private final String controlPage = "WorkBoardITControl" + suffix;
    private final String title = "Story " + workToken;

    @BeforeEach
    void freshBrowser() {
        Selenide.closeWebDriver();
    }

    @AfterEach
    void cleanUp() {
        try {
            RestSeedHelper.deletePageQuietly( controlPage );
        } finally {
            Selenide.closeWebDriver();
        }
    }

    @Test
    void adminMemberAndOutsiderSeeWhatTheirAccessAllows() throws Exception {
        final String itemKey = adminBuildsTheProjectAndWorksTheStory();
        memberSeesTheBoardAndTheItem( itemKey );
        outsiderSeesNothing( itemKey );
    }

    // ---- admin ----

    private String adminBuildsTheProjectAndWorksTheStory() {
        signIn( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();

        // Project.
        open( "/work" );
        $( "h1.work-heading" ).shouldHave( text( "Work" ), WAIT );
        $$( "button" ).findBy( text( "New project" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-new-key" ).shouldBe( visible, WAIT ), "UIT" );
        type( $( "#work-new-name" ), "UI test project" );
        $$( "button" ).findBy( text( "Create project" ) ).click();
        $( "h1.work-heading" ).shouldHave( text( "UIT UI test project" ), WAIT );

        // Product.
        open( "/work/products" );
        $$( "button" ).findBy( text( "New product" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-prod-key" ).shouldBe( visible, WAIT ), "uit-product" );
        type( $( "#work-prod-name" ), "UIT Product" );
        $$( "button" ).findBy( text( "Create product" ) ).click();
        $$( ".work-product-row" ).findBy( text( "uit-product" ) ).shouldBe( visible, WAIT );

        // Member.
        open( "/work/UIT/settings" );
        type( $( "#work-add-principal" ).shouldBe( visible, WAIT ), MEMBER );
        $$( "button" ).findBy( text( "Add member" ) ).click();
        $$( ".work-member-row" ).findBy( text( MEMBER ) ).shouldBe( visible, WAIT );

        // A story from the board.
        open( "/work/UIT" );
        $$( "label.work-toggle" ).findBy( text( "Show draft and cancelled" ) ).shouldBe( visible, WAIT ).$( "input" ).click();
        $( "[data-testid=column-draft]" ).shouldBe( visible, WAIT );
        $$( "button" ).findBy( text( "New item" ) ).shouldBe( visible, WAIT ).click();
        $$( "label.work-form-radio" ).findBy( text( "Story" ) ).shouldBe( visible, WAIT ).$( "input" ).click();
        type( $x( "//label[normalize-space()='Title']/following-sibling::input[1]" ).shouldBe( visible, WAIT ), title );
        type( $x( "//label[normalize-space()='Description']/following-sibling::textarea[1]" ),
            "First draft of the text " + workToken );
        $( "select[aria-label='Product']" ).selectOptionByValue( "uit-product" );
        submitItemForm();
        final SelenideElement draftCard = $( "[data-testid=column-draft]" ).$$( "article.work-card" )
            .findBy( text( title ) ).shouldBe( visible, WAIT );
        final String itemKey = draftCard.getAttribute( "data-item-key" );
        assertTrue( itemKey != null && itemKey.startsWith( "UIT-" ), "the story gets a UIT key, was: " + itemKey );

        // Move to Ready with the keyboard menu; wait until the server has it, then reload the page.
        draftCard.$( "select[aria-label^='Move ']" ).selectOption( "Ready" );
        awaitServerStatus( itemKey, "ready" );
        Selenide.refresh();
        $( "[data-testid=column-ready]" ).shouldBe( visible, WAIT ).$$( "article.work-card" )
            .findBy( text( title ) ).shouldBe( visible, WAIT );

        // Open the item, edit the text, comment.
        $( "[data-testid=column-ready] article.work-card[data-item-key='" + itemKey + "'] a.work-card-key" ).click();
        $( "article.work-item h1.work-heading" ).shouldHave( text( title ), WAIT );
        $( "button[aria-label='Edit text']" ).shouldBe( visible, WAIT ).click();
        type( $( "textarea[aria-label='Item text']" ).shouldBe( visible, WAIT ), "Edited text " + workToken );
        $( "button[aria-label='Save text']" ).click();
        $( ".work-body" ).shouldHave( text( "Edited text " + workToken ), WAIT );
        type( $( "textarea[aria-label='Add a comment']" ).shouldBe( visible, WAIT ), "A first comment " + workToken );
        $( ".work-comment-new" ).$$( "button" ).findBy( text( "Comment" ) ).click();
        $( ".work-comment-list" ).shouldHave( text( "A first comment " + workToken ), WAIT );

        // The timeline: created, status, edited, commented.
        final var events = $$( ".work-timeline .work-event-text" );
        events.findBy( text( "created this item" ) ).shouldBe( exist, WAIT );
        events.findBy( text( "status draft → ready" ) ).shouldBe( exist, WAIT );
        events.findBy( text( "edited the title or text" ) ).shouldBe( exist, WAIT );
        events.findBy( text( "commented" ) ).shouldBe( exist, WAIT );
        return itemKey;
    }

    // ---- member ----

    private void memberSeesTheBoardAndTheItem( final String itemKey ) {
        Selenide.closeWebDriver();
        signIn( MEMBER, MEMBER_PASSWORD );
        open( "/work/UIT" );
        $( "[data-testid=column-ready]" ).shouldBe( visible, WAIT ).$$( "article.work-card" )
            .findBy( text( title ) ).shouldBe( visible, WAIT );
        // The positive control for the outsider's missing entry: a member does get "Work" in the navigation.
        $$( "a.sidebar-link" ).findBy( exactText( "Work" ) ).shouldBe( exist, WAIT );
        open( "/work/item/" + itemKey );
        $( "article.work-item h1.work-heading" ).shouldHave( text( title ), WAIT );
        $( ".work-body" ).shouldHave( text( "Edited text " + workToken ), WAIT );
        $$( ".work-timeline .work-event-text" ).findBy( text( "created this item" ) ).shouldBe( exist, WAIT );
    }

    // ---- someone who is not a member ----

    private void outsiderSeesNothing( final String itemKey ) throws Exception {
        Selenide.closeWebDriver();
        // A control page proves the wiki's search works, so "no result" for the work token means something.
        RestSeedHelper.writePage( controlPage, "Control page for WorkBoardIT " + controlToken + "\n" );
        signIn( OUTSIDER, OUTSIDER_PASSWORD );

        open( "/work/UIT" );
        $( ".admin-empty-message" ).shouldHave( text( "Not found" ), WAIT );
        open( "/work/item/" + itemKey );
        $( ".admin-empty-message" ).shouldHave( text( "No such item" ), WAIT );

        // The navigation has rendered (Recent Changes is there), and "Work" is not.
        $$( "a.sidebar-link" ).findBy( text( "Recent Changes" ) ).shouldBe( exist, WAIT );
        $$( "a.sidebar-link" ).findBy( exactText( "Work" ) ).shouldNot( exist );

        // Search finds the control page, and nothing for the work token.
        new ViewWikiPage().searchForUntilFound( controlToken, controlPage );
        final SearchResultsPage none = new ViewWikiPage().searchFor( workToken );
        $( "[data-testid=search-results-page]" ).shouldHave( attribute( "data-query", workToken ), WAIT );
        $( ".admin-empty-message" ).shouldHave( text( "No results for" ), WAIT );
        assertEquals( 0, none.pagesFound().size(), "work items are not searchable: " + none.pagesFound() );
    }

    // ---- agents: queue, key, claim, hand-off, and where each of them shows ----

    @Test
    void agentClaimsAndHandsOffAndEveryStepIsVisible() throws Exception {
        final String queueKey = "uiq-" + suffix;
        final String queueName = "Implementer " + suffix;
        final String agentName = "codex-" + suffix + "@ui";
        final String projectKey = "UA" + suffix.substring( 0, 6 ).toUpperCase( java.util.Locale.ROOT );
        final String productKey = "uia-" + suffix;
        final String note = "Built it, please look " + workToken;
        final String adminName = adminDisplayName();

        signIn( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
        RestSeedHelper.awaitAdminReady();

        // A queue, then an agent serving it. The page shows the agent's key once.
        open( "/work/agents" );
        $( "h1.work-heading" ).shouldHave( text( "Agents & queues" ), WAIT );
        $$( "button" ).findBy( exactText( "New queue" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-q-key" ).shouldBe( visible, WAIT ), queueKey );
        type( $( "#work-q-name" ), queueName );
        $$( "button" ).findBy( exactText( "Create queue" ) ).click();
        $$( "[data-testid=queue-row]" ).findBy( text( queueName ) ).shouldBe( visible, WAIT );
        $$( "button" ).findBy( exactText( "Register agent" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-a-name" ).shouldBe( visible, WAIT ), agentName );
        $$( "label.work-check" ).findBy( text( queueName ) ).shouldBe( visible, WAIT ).$( "input" ).click();
        $$( "button" ).findBy( exactText( "Register" ) ).click();
        final SelenideElement tokenValue = $( ".work-token-value" ).shouldBe( visible, WAIT );
        final String token = tokenValue.getText();
        assertTrue( token != null && token.length() >= 16, "the key panel shows a key" );
        $( "section.work-token-panel" ).shouldHave( text( "API key for " + agentName ), WAIT );
        $$( "[data-testid=agent-row]" ).findBy( text( agentName ) ).shouldBe( visible, WAIT );
        assertTrue( WebDriverRunner.source().contains( token ),
            "positive control: the key is on the page while the panel is open" );
        // After "Done" the key text is gone from the page (the panel was there a moment ago, and the agent still is).
        $$( "section.work-token-panel button" ).findBy( exactText( "Done" ) ).click();
        $( ".work-token-value" ).shouldNot( exist );
        $$( "[data-testid=agent-row]" ).findBy( text( agentName ) ).shouldBe( visible );
        assertFalse( WebDriverRunner.source().contains( token ), "the key is gone from the page" );

        // A project and a product, with the agent admitted as a member (over REST).
        open( "/work" );
        $( "h1.work-heading" ).shouldHave( text( "Work" ), WAIT );
        $$( "button" ).findBy( text( "New project" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-new-key" ).shouldBe( visible, WAIT ), projectKey );
        type( $( "#work-new-name" ), "Agent flow " + suffix );
        $$( "button" ).findBy( text( "Create project" ) ).click();
        $( "h1.work-heading" ).shouldHave( text( projectKey ), WAIT );
        open( "/work/products" );
        $$( "button" ).findBy( text( "New product" ) ).shouldBe( visible, WAIT ).click();
        type( $( "#work-prod-key" ).shouldBe( visible, WAIT ), productKey );
        type( $( "#work-prod-name" ), "Agent product " + suffix );
        $$( "button" ).findBy( text( "Create product" ) ).click();
        $$( ".work-product-row" ).findBy( text( productKey ) ).shouldBe( visible, WAIT );
        final WorkIt.Caller admin = WorkIt.admin();
        final HttpResponse< String > admitted = admin.send( "PUT", "/api/work/projects/" + projectKey + "/members",
            Map.of( "principal", agentName, "principalKind", "agent", "role", "member" ) );
        assertEquals( 204, admitted.statusCode(), "admit the agent: " + admitted.body() );
        // A hand-off goes to a member: the admin joins the project as a member too, so there is someone to hand to.
        final HttpResponse< String > joined = admin.send( "PUT", "/api/work/projects/" + projectKey + "/members",
            Map.of( "principal", adminName, "principalKind", "user", "role", "member" ) );
        assertEquals( 204, joined.statusCode(), "admit the admin: " + joined.body() );

        // A story, created into the queue, then moved to Ready by a person (an agent cannot move a draft forward).
        open( "/work/" + projectKey );
        $$( "label.work-toggle" ).findBy( text( "Show draft and cancelled" ) ).shouldBe( visible, WAIT ).$( "input" ).click();
        $( "[data-testid=column-draft]" ).shouldBe( visible, WAIT );
        $$( "button" ).findBy( text( "New item" ) ).shouldBe( visible, WAIT ).click();
        $$( "label.work-form-radio" ).findBy( text( "Story" ) ).shouldBe( visible, WAIT ).$( "input" ).click();
        type( $x( "//label[normalize-space()='Title']/following-sibling::input[1]" ).shouldBe( visible, WAIT ), title );
        type( $x( "//label[normalize-space()='Description']/following-sibling::textarea[1]" ), "For the agent " + workToken );
        $( "select[aria-label='Product']" ).selectOptionByValue( productKey );
        $( "select[id$='-assignee']" ).shouldBe( visible, WAIT ).selectOptionByValue( "queue:" + queueKey );
        submitItemForm();
        final SelenideElement draftCard = $( "[data-testid=column-draft]" ).$$( "article.work-card" )
            .findBy( text( title ) ).shouldBe( visible, WAIT );
        final String itemKey = draftCard.getAttribute( "data-item-key" );
        assertTrue( itemKey != null && itemKey.startsWith( projectKey + "-" ), "the story gets a key in the project: " + itemKey );
        draftCard.$( "select[aria-label^='Move ']" ).selectOption( "Ready" );
        awaitServerStatus( itemKey, "ready" );
        Selenide.refresh();
        final SelenideElement readyCard = $( "[data-testid=column-ready] article.work-card[data-item-key='" + itemKey + "']" )
            .shouldBe( visible, WAIT );
        readyCard.$( ".work-card-queue" ).shouldHave( text( queueName ), WAIT );
        readyCard.$( ".work-card-available" ).shouldHave( text( "Available to agents" ), WAIT );

        // The agent claims it over REST (key from the page); the card moves to In progress and says who and until when.
        final HttpResponse< String > claimed = WorkIt.bearer( token, "POST", "/api/work/items/" + itemKey + "/claim", null );
        assertEquals( 200, claimed.statusCode(), "the agent claims: " + WorkIt.status( claimed ) );
        final com.google.gson.JsonObject claimedView = WorkIt.json( claimed ).getAsJsonObject();
        final int version = ( claimedView.has( "item" ) ? claimedView.getAsJsonObject( "item" ) : claimedView ).get( "version" ).getAsInt();
        Selenide.refresh();
        final SelenideElement claimedCard = $( "[data-testid=column-in_progress] article.work-card[data-item-key='" + itemKey + "']" )
            .shouldBe( visible, WAIT );
        claimedCard.$( ".work-card-claim" ).shouldHave( text( agentName + " · until" ), WAIT );
        assertEquals( 0, $$( "[data-testid=column-ready] article.work-card[data-item-key='" + itemKey + "']" ).size(),
            "the card left Ready" );

        // The item page's claim panel names the agent and the times; a person may release it.
        claimedCard.$( "a.work-card-key" ).click();
        final SelenideElement panel = $( "section.work-claim" ).shouldBe( visible, WAIT );
        panel.shouldHave( text( "Claimed by " + agentName ), WAIT );
        panel.shouldHave( text( "until" ), WAIT );
        panel.$$( "button" ).findBy( exactText( "Release claim" ) ).shouldBe( visible, WAIT );

        // The agent hands it to the admin with a note (REST, as the agent).
        final HttpResponse< String > handed = WorkIt.bearer( token, "POST", "/api/work/items/" + itemKey + "/handoff",
            WorkIt.GSON.toJson( Map.of( "expectedVersion", version, "status", "in_progress", "assignee", adminName,
                "note", note ) ) );
        assertEquals( 200, handed.statusCode(), "the agent hands off: " + WorkIt.status( handed ) );

        // "Waiting on you" on the Work home: the item, the agent that handed it over, and the note.
        open( "/work" );
        final SelenideElement lane = $( "[data-testid=waiting-lane]" ).shouldBe( visible, WAIT );
        lane.shouldHave( text( itemKey ), WAIT );
        lane.shouldHave( text( agentName ), WAIT );
        lane.shouldHave( text( "(agent)" ), WAIT );
        lane.$( "blockquote.work-waiting-note" ).shouldHave( text( note ), WAIT );

        // The project's "Assignments and claims": assignment to the queue, claim, hand-off, release; newest first.
        open( "/work/" + projectKey + "/log" );
        $$( "li.work-log-row" ).filter( text( itemKey ) ).shouldHave( CollectionCondition.size( 5 ), WAIT );
        final List< String > rows = new ArrayList<>( $$( "li.work-log-row" ).filter( text( itemKey ) ).texts() );
        final int assigned = indexOf( rows, "assigned this to Queue · " + queueName );
        final int claim = indexOf( rows, agentName + " agent claimed this" );
        final int handoff = indexOf( rows, agentName + " agent handed this to " + adminName );
        final int released = indexOf( rows, agentName + " agent handed this item on, ending its own claim" );
        assertTrue( released < handoff && handoff < claim && claim < assigned,
            "the log runs release, hand-off, claim, assignment from the newest: " + rows );
        assertTrue( rows.get( handoff ).contains( note ), "the hand-off row carries its note: " + rows.get( handoff ) );
        // The agent's rows wear an agent badge; the assignment (made by a person) does not.
        final var itemRows = $$( "li.work-log-row" ).filter( text( itemKey ) );
        itemRows.get( claim ).$( ".work-agent-badge" ).shouldBe( visible );
        itemRows.get( released ).$( ".work-agent-badge" ).shouldBe( visible );
        assertEquals( 0, itemRows.get( assigned ).$$( ".work-agent-badge" ).size(), "the person's assignment has no agent badge" );
    }

    /**
     * Presses "Create item". On a short window the dialog's last row sits just below the fold, where a text search
     * (Selenium's {@code getText} of a button the window does not show) finds nothing; scrolling it into view first is
     * what a person does.
     */
    private static void submitItemForm() {
        final SelenideElement submit = $( "form.work-form button[type=submit]" ).shouldBe( exist, WAIT );
        Selenide.executeJavaScript( "arguments[0].scrollIntoView({ block: 'center' });", submit );
        submit.shouldHave( exactText( "Create item" ) ).click();
    }

    private static int indexOf( final List< String > rows, final String fragment ) {
        for ( int i = 0; i < rows.size(); i++ ) {
            if ( rows.get( i ).replace( '\n', ' ' ).toLowerCase( java.util.Locale.ROOT ).contains( fragment.toLowerCase( java.util.Locale.ROOT ) ) ) {
                return i;
            }
        }
        throw new AssertionError( "no log row contains \"" + fragment + "\": " + rows );
    }

    /** The admin's display name, as the wiki reports it (the name people see, never the login). */
    private static String adminDisplayName() throws Exception {
        final HttpResponse< String > r = WorkIt.admin().get( "/api/auth/me" );
        assertEquals( 200, r.statusCode(), "who am I: " + r.statusCode() );
        final String name = WorkIt.json( r ).getAsJsonObject().get( "fullName" ).getAsString();
        assertTrue( !name.isBlank(), "the admin has a display name" );
        return name;
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

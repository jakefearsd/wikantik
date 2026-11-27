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
package com.wikantik.work;

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delivery over a scripted outbound port and controlled times: order, failure, switch-off, standing. */
@RequiresPostgres
class WorkWebhookDeliveryPostgresTest {

    private static final Actor ROOT = NotifyServiceFixture.ROOT;
    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;
    private static final Instant T0 = Instant.parse( "2026-03-02T12:00:00Z" );
    private static final String URL = "https://hooks.example.com/services/T0/B0/XYZSECRET";

    private RecordingOutbound outbound;
    private NotifyServiceFixture fx;
    private WorkWebhooksService hooks;
    private WebhookCreated made;

    @BeforeEach
    void setUp() throws SQLException {
        outbound = new RecordingOutbound();
        fx = new NotifyServiceFixture( WorkWebhooksServicePostgresTest.ports( outbound ) );
        hooks = fx.service.features().webhooks();
        fx.plant();
        made = hooks.add( LENA, "WIKI", new WebhookDraft( URL, "generic", List.of() ) );
    }

    private Webhook state() throws SQLException {
        return fx.store.notifyStores().webhooks().byId( made.webhook().id() ).orElseThrow();
    }

    private int deliver( final Instant at ) throws SQLException {
        fx.awaitWindow();
        return hooks.deliver( at );
    }

    private List< Long > deliveredIds() {
        final List< Long > ids = new ArrayList<>();
        outbound.sent.forEach( s -> ids.add( Long.valueOf( s.headers().get( "X-Wikantik-Delivery" ) ) ) );
        return ids;
    }

    private List< String > types() {
        return outbound.sent.stream().map( s -> s.headers().get( "X-Wikantik-Event" ) ).toList();
    }

    /** One item with a created and two later events: three item events in all. */
    private Item threeEvents() throws SQLException {
        final Item item = fx.newItem( LENA );
        fx.patch( LENA, item, "title", "second" );
        fx.patch( LENA, item, "title", "third" );
        return item;
    }

    @Test
    void eventsArriveInOrderWithAValidSignature() throws SQLException {
        threeEvents();
        assertEquals( 3, deliver( T0 ) );
        final List< Long > ids = deliveredIds();
        assertEquals( ids.stream().sorted().toList(), ids );
        assertEquals( List.of( "created", "edited", "edited" ), types() );
        for ( final RecordingOutbound.Sent s : outbound.sent ) {
            assertEquals( "application/json", s.contentType() );
            assertEquals( WebhookPayloads.signature( made.secret(), s.body() ), s.headers().get( "X-Wikantik-Signature" ) );
            assertTrue( s.body().contains( "\"url\":\"https://wiki.example.com/work/item/WIKI-1\"" ), s.body() );
            assertTrue( s.body().contains( "\"type\":\"feature\"" ) || s.body().contains( "\"type\":\"task\"" ), s.body() );
        }
        assertEquals( T0, state().lastDeliveredAt() );
        assertEquals( 0, deliver( T0.plusSeconds( 1 ) ), "nothing is sent twice" );
    }

    @Test
    void aFailureStopsAndTheRetryResumesInOrder() throws SQLException {
        threeEvents();
        outbound.script.add( RecordingOutbound.Answer.status( 500 ) );
        assertEquals( 0, deliver( T0 ) );
        assertEquals( 1, outbound.sent.size() );
        assertEquals( "HTTP 500", state().lastError() );
        assertEquals( T0, state().failingSince() );
        assertEquals( made.webhook().id(), fx.store.notifyStores().webhooks().enabled().get( 0 ).id() );
        assertEquals( 0, deliver( T0.plusSeconds( 10 ) ), "backing off" );
        assertEquals( 1, outbound.sent.size() );
        assertEquals( 3, deliver( T0.plus( Duration.ofMinutes( 5 ) ) ) );
        final List< Long > ids = deliveredIds();
        assertEquals( 4, ids.size() );
        assertEquals( ids.get( 0 ), ids.get( 1 ), "the failed event is sent again first" );
        assertEquals( ids.subList( 1, 4 ).stream().sorted().toList(), ids.subList( 1, 4 ) );
        assertNull( state().failingSince() );
    }

    @Test
    void aFailureInTheMiddleKeepsWhatWasDelivered() throws SQLException {
        threeEvents();
        outbound.script.add( RecordingOutbound.Answer.status( 204 ) );
        outbound.script.add( RecordingOutbound.Answer.status( 503 ) );
        assertEquals( 1, deliver( T0 ) );
        final long second = deliveredIds().get( 1 );
        assertEquals( "HTTP 503", state().lastError() );
        assertEquals( 2, deliver( T0.plus( Duration.ofMinutes( 5 ) ) ) );
        assertEquals( second, deliveredIds().get( 2 ), "resumes at the event that failed" );
        assertEquals( 4, outbound.sent.size() );
    }

    @Test
    void eventsNotSelectedAreSkippedAndPassed() throws SQLException {
        hooks.update( LENA, "WIKI", made.webhook().id(), new WebhookPatch( null, List.of( "status" ), null ) );
        final Item item = threeEvents();
        fx.patch( LENA, item, "status", "in_progress" );
        assertEquals( 1, deliver( T0 ) );
        assertEquals( List.of( "status" ), types() );
        assertTrue( outbound.sent.get( 0 ).body().contains( "\"to\":\"in_progress\"" ) );
        assertEquals( 0, deliver( T0.plusSeconds( 1 ) ) );
        assertEquals( 1, outbound.sent.size(), "the others are passed over, not retried" );
    }

    @Test
    void projectLevelEventsAreNeverSent() throws SQLException {
        hooks.update( LENA, "WIKI", made.webhook().id(), new WebhookPatch( null, List.of( "status" ), null ) );
        hooks.add( LENA, "WIKI", new WebhookDraft( "https://two.example.com/x", "slack", null ) );
        fx.store.putMember( fx.project.id(), "vera", "user", WorkRole.MEMBER, ROOT );
        assertEquals( 0, deliver( T0 ) );
        assertTrue( outbound.sent.isEmpty() );
        fx.patch( LENA, fx.newItem( LENA ), "status", "in_progress" );
        assertEquals( 3, deliver( T0.plusSeconds( 1 ) ), "slack: created and status; generic: status only" );
    }

    @Test
    void sprintEventsAreNeverSent() throws SQLException {
        final java.time.LocalDate day = java.time.LocalDate.of( 2026, 3, 2 );
        final WorkSprintsService sprints = fx.service.features().sprints();
        final long id = sprints.create( LENA, "WIKI", new SprintDraft( "S1", "", day, day.plusDays( 13 ) ) ).sprint().id();
        sprints.update( LENA, id, 1, new SprintDraft( "S1b", "", day, day.plusDays( 13 ) ) );
        final Sprint s = fx.store.sprints().byId( id ).orElseThrow();
        sprints.start( LENA, id, s.version() );
        assertEquals( 0, deliver( T0 ) );
        assertTrue( outbound.sent.isEmpty(), "no sprint event leaves the wiki" );
        assertEquals( 3, WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event WHERE type LIKE 'sprint_%'" ), "created, updated, started" );
        assertFalse( WebhookPayloads.SENDABLE.stream().anyMatch( t -> t.startsWith( "sprint_" ) ) );
    }

    @Test
    void aFieldEventIsSentWithTheNeutralText() throws SQLException {
        final Item item = fx.newItem( LENA );
        fx.patch( LENA, item, "estimate", 3 );
        deliver( T0 );
        final RecordingOutbound.Sent field = outbound.sent.stream()
            .filter( s -> "field".equals( s.headers().get( "X-Wikantik-Event" ) ) ).findFirst().orElseThrow();
        assertTrue( field.body().contains( "\"text\":\"lena updated this\"" ), field.body() );
        assertFalse( field.body().contains( "estimate" ) || field.body().contains( "\"from\"" ), field.body() );
    }

    @Test
    void twentyFourHoursOfFailureSwitchesItOffAndNotifiesLeadsOnce() throws SQLException {
        threeEvents();
        outbound.defaultStatus = 500;
        assertEquals( 0, deliver( T0 ) );
        assertEquals( 0, deliver( T0.plus( Duration.ofHours( 24 ) ) ) );
        assertEquals( T0.plus( Duration.ofHours( 24 ) ), state().disabledAt() );
        assertEquals( 1, failedEvents() );
        assertTrue( fx.notified.contains( "webhook/failed" ) );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "reason = 'webhook_disabled' AND recipient = 'lena'" ) );
        assertEquals( 1, fx.rows( "reason = 'webhook_disabled'" ), "leads only: otto is a member" );
        final int sentBefore = outbound.sent.size();
        assertEquals( 0, deliver( T0.plus( Duration.ofHours( 30 ) ) ) );
        assertEquals( sentBefore, outbound.sent.size(), "a switched-off webhook is not tried" );
        assertEquals( 1, failedEvents() );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "reason = 'webhook_disabled'" ) );
    }

    @Test
    void justUnderADayOfFailureLeavesItOn() throws SQLException {
        fx.newItem( LENA );
        outbound.defaultStatus = 500;
        deliver( T0 );
        deliver( T0.plus( Duration.ofHours( 23 ).plusMinutes( 59 ) ) );
        assertNull( state().disabledAt() );
        assertEquals( 0, failedEvents() );
    }

    private long failedEvents() throws SQLException {
        return WorkAgentFixtures.scalar( fx.ds, "SELECT COUNT(*) FROM work_event WHERE note = 'webhook' AND to_value = 'failed' "
            + "AND actor = 'lena' AND from_value = 'hooks.example.com'" );
    }

    @Test
    void aSuccessResetsTheFailureClock() throws SQLException {
        fx.newItem( LENA );
        outbound.script.add( RecordingOutbound.Answer.status( 500 ) );
        deliver( T0 );
        assertEquals( T0, state().failingSince() );
        assertEquals( 1, deliver( T0.plus( Duration.ofHours( 1 ) ) ) );
        assertNull( state().failingSince() );
        assertNull( state().lastError() );
        assertEquals( T0.plus( Duration.ofHours( 1 ) ), state().lastDeliveredAt() );
        fx.newItem( LENA );
        outbound.script.add( RecordingOutbound.Answer.status( 500 ) );
        deliver( T0.plus( Duration.ofHours( 30 ) ) );
        assertEquals( T0.plus( Duration.ofHours( 30 ) ), state().failingSince() );
        assertNull( state().disabledAt(), "the old failure does not count" );
    }

    @Test
    void aWebhookWhoseOwnerIsNoLongerALeadSendsNothing() throws SQLException {
        fx.newItem( LENA );
        fx.store.putMember( fx.project.id(), "otto", "user", WorkRole.LEAD, ROOT );
        fx.store.putMember( fx.project.id(), "lena", "user", WorkRole.MEMBER, ROOT );
        final String cursorBefore = state().cursorTx() + ":" + state().cursorEventId();
        assertEquals( 0, deliver( T0 ) );
        assertTrue( outbound.sent.isEmpty() );
        assertNotEquals( cursorBefore, state().cursorTx() + ":" + state().cursorEventId(),
            "the cursor skips to the newest event while paused (M-1: no backlog on resume)" );
        assertNull( state().failingSince(), "paused is not failing" );
        hooks.update( OTTO, "WIKI", made.webhook().id(), new WebhookPatch( null, null, null ) );
        assertEquals( 0, deliver( T0.plusSeconds( 1 ) ), "the paused period is not replayed (fix round 1, M-1)" );
        fx.newItem( OTTO );
        assertEquals( 1, deliver( T0.plusSeconds( 2 ) ) );
    }

    @Test
    void anOwnerWhoIsAWikiAdminKeepsSending() throws SQLException {
        fx.accounts.add( "root" );
        fx.admins.add( "root" );
        final WebhookCreated byAdmin = hooks.add( ROOT, "WIKI", new WebhookDraft( "https://adm.example.com/x", "slack", null ) );
        assertFalse( byAdmin.webhook().paused() );
    }

    @Test
    void aRefusedRedirectCountsAsAFailure() throws SQLException {
        fx.newItem( LENA );
        outbound.script.add( RecordingOutbound.Answer.fail( new WorkOutbound.Refused(
            "target host resolves to a non-routable/internal address: evil.example.com" ) ) );
        assertEquals( 0, deliver( T0 ) );
        assertEquals( "target host resolves to a non-routable/internal address: evil.example.com", state().lastError() );
        assertNotNull( state().failingSince() );
        assertEquals( 1, outbound.sent.size() );
    }

    @Test
    void webhooksOffDeliverNothing() throws SQLException {
        final RecordingOutbound quiet = new RecordingOutbound();
        final NotifyServiceFixture off = new NotifyServiceFixture( new WorkNotifyPorts( WorkMailer.NONE, quiet,
            NotifySettings.OFF ) );
        off.plant();
        off.store.notifyStores().webhooks().add( off.project, java.net.URI.create( URL ), WebhookFormat.GENERIC, "s", List.of(), LENA );
        off.newItem( LENA );
        off.awaitWindow();
        assertEquals( 0, off.service.features().webhooks().deliver( T0 ) );
        assertTrue( quiet.sent.isEmpty() );
    }

    @Test
    void theLastErrorNeverHoldsThePath() throws SQLException {
        fx.newItem( LENA );
        outbound.script.add( RecordingOutbound.Answer.fail( new ConnectException( "connect to " + URL + " refused" ) ) );
        deliver( T0 );
        assertEquals( "ConnectException", state().lastError() );
        outbound.script.add( RecordingOutbound.Answer.fail( new WorkOutbound.Refused( "blocked " + URL + " at /services/T0/B0/XYZSECRET" ) ) );
        deliver( T0.plus( Duration.ofHours( 1 ) ) );
        assertFalse( state().lastError().contains( "XYZSECRET" ), state().lastError() );
        outbound.script.add( RecordingOutbound.Answer.fail( new IOException( "x" ) ) );
        final WebhookView view = hooks.list( LENA, "WIKI" ).get( 0 );
        assertFalse( view.toString().contains( "XYZSECRET" ) );
    }

    @Test
    void oneWebhooksFailureDoesNotBlockAnother() throws SQLException {
        hooks.add( LENA, "WIKI", new WebhookDraft( "https://two.example.com/x", "slack", null ) );
        fx.newItem( LENA );
        outbound.script.add( RecordingOutbound.Answer.status( 500 ) );
        assertEquals( 1, deliver( T0 ) );
        assertEquals( "HTTP 500", state().lastError() );
        assertEquals( "two.example.com", outbound.sent.get( 1 ).url().getHost() );
        assertTrue( outbound.sent.get( 1 ).body().startsWith( "{\"text\":" ) );
        assertFalse( outbound.sent.get( 1 ).headers().containsKey( "X-Wikantik-Signature" ), "Slack is not signed" );
    }

    // ---- fix round 1: failures, time budget, no backlog on resume

    @Test
    void anExceptionFromThePortIsAFailureLikeAnyOther() throws SQLException {
        hooks.add( LENA, "WIKI", new WebhookDraft( "https://two.example.com/x", "slack", null ) );
        threeEvents();
        outbound.thrown = new IllegalStateException( "boom " + URL );
        assertEquals( 0, deliver( T0 ) );
        assertEquals( "IllegalStateException", state().lastError(), "the class only, never a message" );
        assertEquals( T0, state().failingSince() );
        assertEquals( 2, outbound.sent.size(), "one attempt per webhook, the second still tried" );
        assertEquals( 0, deliver( T0.plusSeconds( 10 ) ) );
        assertEquals( 2, outbound.sent.size(), "backing off, not retried every cycle" );
        assertEquals( 0, deliver( T0.plus( Duration.ofHours( 24 ) ) ) );
        assertEquals( T0.plus( Duration.ofHours( 24 ) ), state().disabledAt(), "switched off after a day of failing" );
        assertEquals( 1, failedEvents() );
    }

    @Test
    void anExceptionBuildingTheBodyIsAFailureToo() throws SQLException {
        fx.newItem( LENA );
        fx.displayNameFails = true;
        assertEquals( 0, deliver( T0 ) );
        assertEquals( "IllegalStateException", state().lastError() );
        assertEquals( T0, state().failingSince() );
        assertTrue( outbound.sent.isEmpty() );
        assertEquals( 0, deliver( T0.plusSeconds( 10 ) ), "backing off" );
        fx.displayNameFails = false;
        assertEquals( 1, deliver( T0.plus( Duration.ofMinutes( 5 ) ) ), "recovers once it can be built" );
    }

    @Test
    void aFailureStopsThatWebhookForTheRestOfTheCycle() throws SQLException {
        threeEvents();
        outbound.defaultStatus = 500;
        deliver( T0 );
        assertEquals( 1, outbound.sent.size(), "the other two events wait for the next cycle" );
    }

    private AdjustableClock slowSends( final String slowHost ) {
        outbound.onPost = () -> {
            if ( slowHost.equals( outbound.sent.get( outbound.sent.size() - 1 ).url().getHost() ) ) {
                fx.clock.advance( Duration.ofSeconds( 20 ) );
            }
        };
        return fx.clock;
    }

    private long sentTo( final String host ) {
        return outbound.sent.stream().filter( s -> host.equals( s.url().getHost() ) ).count();
    }

    @Test
    void aSlowWebhookCannotHoldTheCycleAndTheNextOneGoesFirstNextTime() throws SQLException {
        hooks.add( LENA, "WIKI", new WebhookDraft( "https://two.example.com/x", "slack", null ) );
        threeEvents();
        slowSends( "hooks.example.com" );
        assertEquals( 2, deliver( T0 ), "two sends of 20 s spend the 30 s budget" );
        assertEquals( 2, sentTo( "hooks.example.com" ) );
        assertEquals( 0, sentTo( "two.example.com" ), "the budget was spent before the second webhook began" );
        deliver( T0.plusSeconds( 5 ) );
        assertEquals( 3, sentTo( "two.example.com" ), "the starved webhook goes first in the next cycle" );
        assertEquals( 3, sentTo( "hooks.example.com" ), "and the slow one resumes where its cursor stopped" );
        assertNull( state().lastError(), "running out of time is not a failure" );
    }

    @Test
    void theBudgetIsPerCycle() throws SQLException {
        threeEvents();
        slowSends( "hooks.example.com" );
        deliver( T0 );
        assertEquals( 2, outbound.sent.size() );
        assertEquals( 1, deliver( T0.plusSeconds( 1 ) ) );
        assertEquals( 3, outbound.sent.size() );
    }

    private WorkWebhooksService offService() {
        return fx.serviceWith( new WorkNotifyPorts( WorkMailer.NONE, outbound, NotifySettings.OFF ) ).features().webhooks();
    }

    @Test
    void nothingFromAGloballyOffPeriodIsSentWhenWebhooksComeBack() throws SQLException {
        fx.newItem( LENA );
        fx.awaitWindow();
        final WorkWebhooksService off = offService();
        assertEquals( 0, off.deliver( T0 ) );
        fx.newItem( LENA );
        fx.awaitWindow();
        assertEquals( 0, off.deliver( T0.plusSeconds( 5 ) ) );
        assertTrue( outbound.sent.isEmpty() );
        assertEquals( 0, deliver( T0.plusSeconds( 10 ) ), "the flag is back on: the off period is not replayed" );
        assertTrue( outbound.sent.isEmpty() );
        fx.newItem( LENA );
        assertEquals( 1, deliver( T0.plusSeconds( 15 ) ), "the first event after the resume is sent" );
        assertEquals( 1, outbound.sent.size() );
    }

    @Test
    void nothingFromADisabledPeriodIsSentOnReEnable() throws SQLException {
        hooks.update( LENA, "WIKI", made.webhook().id(), new WebhookPatch( null, null, false ) );
        fx.newItem( LENA );
        assertEquals( 0, deliver( T0 ) );
        hooks.update( LENA, "WIKI", made.webhook().id(), new WebhookPatch( null, null, true ) );
        assertEquals( 0, deliver( T0.plusSeconds( 5 ) ) );
        fx.newItem( LENA );
        assertEquals( 1, deliver( T0.plusSeconds( 10 ) ) );
    }

    @Test
    void nothingFromAPausedPeriodIsSentWhenTheOwnerIsALeadAgain() throws SQLException {
        fx.store.putMember( fx.project.id(), "lena", "user", WorkRole.MEMBER, ROOT );
        fx.newItem( LENA );
        assertEquals( 0, deliver( T0 ) );
        fx.newItem( LENA );
        assertEquals( 0, deliver( T0.plusSeconds( 5 ) ) );
        fx.store.putMember( fx.project.id(), "lena", "user", WorkRole.LEAD, ROOT );
        assertEquals( 0, deliver( T0.plusSeconds( 10 ) ), "back to lead: the paused period is not replayed" );
        assertTrue( outbound.sent.isEmpty() );
        fx.newItem( LENA );
        assertEquals( 1, deliver( T0.plusSeconds( 15 ) ) );
    }

    @Test
    void nothingFromAPausedPeriodIsSentWhenAnotherLeadTakesOver() throws SQLException {
        fx.store.putMember( fx.project.id(), "otto", "user", WorkRole.LEAD, ROOT );
        fx.store.putMember( fx.project.id(), "lena", "user", WorkRole.MEMBER, ROOT );
        fx.newItem( LENA );
        assertEquals( 0, deliver( T0 ) );
        fx.newItem( LENA );
        assertEquals( 0, deliver( T0.plusSeconds( 5 ) ) );
        hooks.update( OTTO, "WIKI", made.webhook().id(), new WebhookPatch( null, null, null ) );
        assertEquals( 0, deliver( T0.plusSeconds( 10 ) ) );
        assertTrue( outbound.sent.isEmpty() );
        fx.newItem( OTTO );
        assertEquals( 1, deliver( T0.plusSeconds( 15 ) ) );
    }

    // ---- fix round 1: webhook settings events are for leads and admins only (P3-10)

    private List< WorkEvent > webhookEvents( final List< WorkEvent > feed ) {
        return feed.stream().filter( e -> WorkEvent.SETTINGS.equals( e.type() ) && "webhook".equals( e.note() ) ).toList();
    }

    @Test
    void onlyLeadsAndAdminsSeeWebhookEventsInTheFeedAndPagingStaysFull() throws SQLException {
        threeEvents();
        hooks.update( LENA, "WIKI", made.webhook().id(), new WebhookPatch( null, List.of( "status" ), null ) );
        hooks.update( LENA, "WIKI", made.webhook().id(), new WebhookPatch( null, null, false ) );
        final long agentId = WorkAgentFixtures.agent( fx.ds, "bot@x" );
        fx.store.putMember( fx.project.id(), String.valueOf( agentId ), "agent", WorkRole.MEMBER, ROOT );
        final Actor agent = Actor.agent( agentId, "bot@x", LENA );
        for ( final Actor who : new Actor[] { OTTO, NotifyServiceFixture.VERA, agent } ) {
            final List< WorkEvent > feed = fx.service.activity( who, "WIKI", null, 200 );
            assertTrue( webhookEvents( feed ).isEmpty(), who.login() + " must not see webhook events" );
            assertFalse( feed.isEmpty() );
            assertEquals( 2, fx.service.activity( who, "WIKI", null, 2 ).size(),
                "the two newest are webhook events: a member's page is still full" );
        }
        final List< WorkEvent > lead = fx.service.activity( LENA, "WIKI", null, 200 );
        assertEquals( 3, webhookEvents( lead ).size(), "added, changed, disabled" );
        assertEquals( 3, webhookEvents( fx.service.activity( ROOT, "WIKI", null, 200 ) ).size() );
        assertEquals( lead.size() - 3, fx.service.activity( OTTO, "WIKI", null, 200 ).size() );
    }

    @Test
    void theSwitchedOffNoticeInTheInboxGoesToLeadsOnly() throws SQLException {
        threeEvents();
        outbound.defaultStatus = 500;
        deliver( T0 );
        deliver( T0.plus( Duration.ofHours( 24 ) ) );
        assertEquals( 1, failedEvents() );
        fx.dispatchAll();
        assertEquals( 1, fx.rows( "reason = 'webhook_disabled' AND recipient = 'lena'" ) );
        assertEquals( 0, fx.rows( "reason = 'webhook_disabled' AND recipient <> 'lena'" ) );
        assertTrue( webhookEvents( fx.service.activity( OTTO, "WIKI", null, 200 ) ).isEmpty(), "nor in a member's feed" );
    }

    // ---- gaps a mutation run found

    @Test
    void theRetryPauseDoublesFromAMinuteAndStopsGrowingAtThirtyMinutes() throws SQLException {
        fx.newItem( LENA );
        outbound.defaultStatus = 500;
        final long[] pauseMinutes = { 1, 2, 4, 8, 16, 30, 30 };
        Instant at = T0;
        deliver( at );
        int sends = outbound.sent.size();
        assertEquals( 1, sends );
        for ( int k = 0; k < pauseMinutes.length; k++ ) {
            final Duration pause = Duration.ofMinutes( pauseMinutes[ k ] );
            deliver( at.plus( pause ).minusSeconds( 1 ) );
            assertEquals( sends, outbound.sent.size(), "failure " + ( k + 1 ) + ": still backing off one second early" );
            at = at.plus( pause );
            deliver( at );
            sends++;
            assertEquals( sends, outbound.sent.size(), "failure " + ( k + 1 ) + ": retried when the pause ended" );
        }
    }

    @Test
    void onlyA2xxAnswerIsADelivery() throws SQLException {
        fx.newItem( LENA );
        Instant at = T0;
        for ( final int status : new int[] { 199, 300, 301, 404, 500 } ) {
            outbound.script.add( RecordingOutbound.Answer.status( status ) );
            at = at.plus( Duration.ofHours( 1 ) );
            assertEquals( 0, deliver( at ), "status " + status );
            assertEquals( "HTTP " + status, state().lastError() );
        }
        outbound.script.add( RecordingOutbound.Answer.status( 299 ) );
        assertEquals( 1, deliver( at.plus( Duration.ofHours( 1 ) ) ), "299 is still a success" );
        assertNull( state().lastError() );
    }

    @Test
    void aRefusalReasonIsCutToTwoHundredCharactersAndAnEmptyOneIsNamed() throws SQLException {
        fx.newItem( LENA );
        outbound.script.add( RecordingOutbound.Answer.fail( new WorkOutbound.Refused( "x".repeat( 200 ) ) ) );
        deliver( T0 );
        assertEquals( "x".repeat( 200 ), state().lastError(), "exactly the limit is kept whole" );
        outbound.script.add( RecordingOutbound.Answer.fail( new WorkOutbound.Refused( "y".repeat( 201 ) ) ) );
        deliver( T0.plus( Duration.ofHours( 1 ) ) );
        assertEquals( "y".repeat( 200 ), state().lastError() );
        outbound.script.add( RecordingOutbound.Answer.fail( new WorkOutbound.Refused( null ) ) );
        deliver( T0.plus( Duration.ofHours( 2 ) ) );
        assertEquals( "refused", state().lastError() );
    }
}

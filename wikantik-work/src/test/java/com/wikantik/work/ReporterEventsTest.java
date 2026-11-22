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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one reshaping of an event for a person who only reports (Task 8.2c, choice 16(b)), field by field. */
class ReporterEventsTest {

    private static final Instant AT = Instant.parse( "2026-03-02T10:00:00Z" );
    private static final String NOTE = "internal reason";

    private static WorkEvent event( final String type, final String from, final String to, final String note ) {
        return new WorkEvent( 7L, 3L, 11L, "WIKI-1", AT, "codex@docker2", Actor.Kind.AGENT, 42L, type, from, to, note );
    }

    private static WorkEvent shaped( final WorkEvent e ) {
        return ReporterEvents.reshape( e ).orElseThrow( () -> new AssertionError( "not shown: " + e ) );
    }

    /** Everything a reshaped event keeps of its source, and the three things it never keeps. */
    private static void keepsOnlyTheSafeFields( final WorkEvent shaped ) {
        assertEquals( 7L, shaped.id() );
        assertEquals( 3L, shaped.projectId() );
        assertEquals( 11L, shaped.itemId() );
        assertEquals( "WIKI-1", shaped.itemKey() );
        assertEquals( AT, shaped.at() );
        assertEquals( "codex@docker2", shaped.actor() );
        assertEquals( Actor.Kind.AGENT, shaped.actorKind() );
        assertNull( shaped.agentId() );
        assertNull( shaped.note() );
    }

    @Test
    void aPlainStatusChangeKeepsItsTwoStatusWords() {
        final WorkEvent s = shaped( event( WorkEvent.STATUS, "ready", "done", NOTE ) );
        keepsOnlyTheSafeFields( s );
        assertEquals( WorkEvent.STATUS, s.type() );
        assertEquals( "ready", s.fromValue() );
        assertEquals( "done", s.toValue() );
    }

    @Test
    void aHandoffThatMovesTheStatusBecomesAStatusEventWithNoTarget() {
        final WorkEvent s = shaped( event( WorkEvent.HANDOFF, "review|queue:implementer", "done|otto", NOTE ) );
        keepsOnlyTheSafeFields( s );
        assertEquals( WorkEvent.STATUS, s.type() );
        assertEquals( "review", s.fromValue() );
        assertEquals( "done", s.toValue() );
        assertFalse( s.toString().contains( "otto" ) || s.toString().contains( "queue" ) );
    }

    @Test
    void aClaimOrALapseThatMovesTheItemIsAStatusEvent() {
        final WorkEvent claim = shaped( event( WorkEvent.CLAIMED, "ready", "in_progress", "2026-03-02T11:00:00Z" ) );
        keepsOnlyTheSafeFields( claim );
        assertEquals( WorkEvent.STATUS, claim.type() );
        assertEquals( "in_progress", claim.toValue() );
        final WorkEvent lapse = shaped( event( WorkEvent.CLAIM_EXPIRED, "in_progress", "ready", null ) );
        assertEquals( WorkEvent.STATUS, lapse.type() );
        assertEquals( "in_progress", lapse.fromValue() );
        assertEquals( "ready", lapse.toValue() );
    }

    @Test
    void aReleaseBackToReadyIsAStatusEventWithoutTheHolder() {
        final WorkEvent s = shaped( event( WorkEvent.RELEASED, "agent:42", "ready", "sponsor access removed" ) );
        keepsOnlyTheSafeFields( s );
        assertEquals( WorkEvent.STATUS, s.type() );
        assertEquals( "in_progress", s.fromValue() );
        assertEquals( "ready", s.toValue() );
    }

    @Test
    void aCommentKeepsOnlyItsCommentId() {
        final WorkEvent c = shaped( event( WorkEvent.COMMENTED, "x", "99", NOTE ) );
        keepsOnlyTheSafeFields( c );
        assertEquals( WorkEvent.COMMENTED, c.type() );
        assertNull( c.fromValue() );
        assertEquals( "99", c.toValue() );
    }

    @Test
    void theTimelineAlsoShowsTheFilingAndItsFiles() {
        final WorkEvent created = shaped( event( WorkEvent.CREATED, "x", "y", NOTE ) );
        keepsOnlyTheSafeFields( created );
        assertNull( created.fromValue() );
        assertNull( created.toValue() );
        final WorkEvent attached = shaped( event( WorkEvent.ATTACHED, "attachment:5", "shot.png", "comment:9" ) );
        keepsOnlyTheSafeFields( attached );
        assertEquals( "attachment:5", attached.fromValue() );
        assertEquals( "shot.png", attached.toValue() );
        assertFalse( ReporterEvents.notifies( created ), "the filing notifies nobody" );
        assertFalse( ReporterEvents.notifies( attached ), "an attachment notifies nobody (choice 18)" );
    }

    @Test
    void aMoveThatMovesNothingOrNamesNoStatusIsNotShown() {
        for ( final WorkEvent e : new WorkEvent[] {
            event( WorkEvent.HANDOFF, "ready|otto", "ready|queue:implementer", NOTE ),
            event( WorkEvent.CLAIMED, "review", "review", null ),
            event( WorkEvent.RELEASED, "agent:1", "ready", "moved" ),
            event( WorkEvent.RELEASED, "agent:1", "ready", "reassigned" ),
            event( WorkEvent.RELEASED, "agent:1", "review", "released" ),
            event( WorkEvent.STATUS, "ready", "otto", null ),
            event( WorkEvent.STATUS, "ready", null, null ),
            new WorkEvent( 1L, 3L, null, null, AT, "lena", Actor.Kind.HUMAN, null, WorkEvent.STATUS, "ready", "done", null ) } ) {
            assertEquals( Optional.empty(), ReporterEvents.reshape( e ), e.toString() );
            assertFalse( ReporterEvents.notifies( e ), e.toString() );
        }
    }

    @Test
    void everythingElseIsNeitherShownNorTold() {
        for ( final String type : Set.of( WorkEvent.ASSIGNED, WorkEvent.FIELD, WorkEvent.EDITED, WorkEvent.LINKED,
                WorkEvent.CHANGE_MERGED, WorkEvent.SHIPPED, WorkEvent.SPRINT_STARTED, WorkEvent.COMMENT_EDITED,
                WorkEvent.CLAIM_RENEWED, WorkEvent.ATTACHMENT_REMOVED ) ) {
            assertEquals( Optional.empty(), ReporterEvents.reshape( event( type, "ready", "done", NOTE ) ), type );
        }
    }

    @Test
    void aReportersNoticeIsWatchingWhateverTheStoredReason() {
        final NoticeView v = new NoticeView( 5L, NoticeReason.CLAIM_EXPIRED, AT, null,
            event( WorkEvent.CLAIM_EXPIRED, "in_progress", "ready", null ), "WIKI", "t" );
        final NoticeView shaped = ReporterEvents.reshape( v ).orElseThrow();
        assertEquals( NoticeReason.WATCHING, shaped.reason() );
        assertEquals( WorkEvent.STATUS, shaped.event().type() );
        assertTrue( ReporterEvents.reshape( new NoticeView( 5L, NoticeReason.WATCHING, AT, null,
            event( WorkEvent.CREATED, null, null, null ), "WIKI", "t" ) ).isEmpty(), "a filing is shown, never told" );
    }

    @Test
    void theReporterLineSaysOnlyWhoAndTheNewStatus() {
        final java.util.function.Function< String, String > names = login -> "Name of " + login;
        assertEquals( "codex@docker2 moved this to in progress",
            NoticeText.reporterLine( shaped( event( WorkEvent.CLAIMED, "ready", "in_progress", null ) ), names ) );
        final WorkEvent byPerson = new WorkEvent( 7L, 3L, 11L, "WIKI-1", AT, "otto", Actor.Kind.HUMAN, null,
            WorkEvent.HANDOFF, "ready|otto", "done|vera", NOTE );
        assertEquals( "Name of otto moved this to done", NoticeText.reporterLine( shaped( byPerson ), names ) );
        assertEquals( "codex@docker2 commented",
            NoticeText.reporterLine( shaped( event( WorkEvent.COMMENTED, null, "9", null ) ), names ) );
    }

    @Test
    void aLapsedClaimIsNotAttributedToTheAgentThatHeldIt() {
        final java.util.function.Function< String, String > names = login -> "Name of " + login;
        final WorkEvent lapse = shaped( event( WorkEvent.CLAIM_EXPIRED, "in_progress", "ready", null ) );
        assertNull( lapse.actor(), "the holder is dropped" );
        assertEquals( Actor.Kind.SYSTEM, lapse.actorKind() );
        assertNull( lapse.agentId() );
        assertEquals( "This moved to ready", NoticeText.reporterLine( lapse, names ) );
        final WorkEvent release = shaped( event( WorkEvent.RELEASED, "agent:42", "ready", "sponsor access removed" ) );
        assertEquals( "codex@docker2 moved this to ready", NoticeText.reporterLine( release, names ),
            "a release by a person's action keeps its actor" );
    }

    @Test
    void everyEventTypeNotOnTheTimelineAllowListIsDropped() throws IllegalAccessException {
        final Set< String > shown = Set.of( WorkEvent.STATUS, WorkEvent.HANDOFF, WorkEvent.CLAIMED,
            WorkEvent.CLAIM_EXPIRED, WorkEvent.RELEASED, WorkEvent.COMMENTED, WorkEvent.CREATED, WorkEvent.ATTACHED );
        assertEquals( shown, ReporterEvents.SHOWN );
        int seen = 0;
        for ( final java.lang.reflect.Field f : WorkEvent.class.getDeclaredFields() ) {
            if ( !java.lang.reflect.Modifier.isStatic( f.getModifiers() ) || f.getType() != String.class ) {
                continue;
            }
            seen++;
            final String type = (String) f.get( null );
            if ( !shown.contains( type ) ) {
                assertEquals( Optional.empty(), ReporterEvents.reshape( event( type, "ready", "done", NOTE ) ),
                    type + " must be default-denied" );
                assertFalse( ReporterEvents.notifies( event( type, "ready", "done", NOTE ) ), type );
            }
        }
        assertTrue( seen > 30, "the reflection found the WorkEvent constants: " + seen );
    }
}

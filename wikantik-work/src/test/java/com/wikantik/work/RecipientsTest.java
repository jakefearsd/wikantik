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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipientsTest {

    private static final Set< String > NONE = Set.of();

    static WorkEvent ev( final String type, final String actor, final Actor.Kind kind, final String from,
                         final String to, final String note ) {
        return new WorkEvent( 1, 1, 1L, "WIKI-1", Instant.parse( "2026-01-01T00:00:00Z" ), actor, kind,
            kind == Actor.Kind.AGENT ? 7L : null, type, from, to, note );
    }

    static WorkEvent byAlice( final String type, final String to ) {
        return ev( type, "alice", Actor.Kind.HUMAN, null, to, null );
    }

    static EventFacts facts( final Set< String > watchers, final Set< String > mentioned, final Set< String > leads,
                             final boolean ack ) {
        return new EventFacts( "rita", watchers, mentioned, leads, NONE, ack, false );
    }

    private static final EventFacts WATCHED = facts( Set.of( "bob", "carl" ), NONE, Set.of( "lena" ), false );

    @Test
    void anAssignmentReachesTheNewAssigneeOnly() {
        assertEquals( Map.of( "bob", NoticeReason.ASSIGNED ), Recipients.of( byAlice( WorkEvent.ASSIGNED, "bob" ), WATCHED ) );
    }

    @Test
    void anAssignmentToAQueueReachesNobody() {
        assertTrue( Recipients.of( byAlice( WorkEvent.ASSIGNED, "queue:ops" ), WATCHED ).isEmpty() );
        assertTrue( Recipients.of( byAlice( WorkEvent.ASSIGNED, null ), WATCHED ).isEmpty() );
    }

    @Test
    void aHandoffReachesTheNewAssigneeAndWatchers() {
        assertEquals( Map.of( "dora", NoticeReason.ASSIGNED, "bob", NoticeReason.WATCHING, "carl", NoticeReason.WATCHING ),
            Recipients.of( byAlice( WorkEvent.HANDOFF, "in_review|dora" ), WATCHED ) );
        assertEquals( Map.of( "bob", NoticeReason.WATCHING, "carl", NoticeReason.WATCHING ),
            Recipients.of( byAlice( WorkEvent.HANDOFF, "in_review|queue:ops" ), WATCHED ) );
    }

    @Test
    void aCommentReachesTheMentionedAndWatchers() {
        final EventFacts f = facts( Set.of( "bob" ), Set.of( "carl" ), NONE, false );
        assertEquals( Map.of( "carl", NoticeReason.MENTIONED, "bob", NoticeReason.WATCHING ),
            Recipients.of( byAlice( WorkEvent.COMMENTED, "5" ), f ) );
    }

    @Test
    void aRemovedCommentReachesNobody() {
        final EventFacts f = new EventFacts( "rita", Set.of( "bob" ), Set.of( "carl" ), NONE, NONE, false, true );
        assertTrue( Recipients.of( byAlice( WorkEvent.COMMENTED, "5" ), f ).isEmpty() );
        assertTrue( Recipients.of( byAlice( WorkEvent.COMMENT_EDITED, "5" ), f ).isEmpty() );
    }

    @Test
    void anEditedCommentReachesOnlyNewlyMentioned() {
        final EventFacts f = facts( Set.of( "bob" ), Set.of( "carl" ), NONE, false );
        assertEquals( Map.of( "carl", NoticeReason.MENTIONED ), Recipients.of( byAlice( WorkEvent.COMMENT_EDITED, "5" ), f ) );
    }

    @Test
    void aStatusChangeReachesWatchers() {
        assertEquals( Map.of( "bob", NoticeReason.WATCHING, "carl", NoticeReason.WATCHING ),
            Recipients.of( byAlice( WorkEvent.STATUS, "in_progress" ), WATCHED ) );
    }

    @Test
    void anAgentsCloseNeedingAcknowledgementReachesLeads() {
        final EventFacts f = facts( Set.of( "bob" ), NONE, Set.of( "lena" ), true );
        final WorkEvent done = ev( WorkEvent.STATUS, "codex", Actor.Kind.AGENT, "in_progress", "done", null );
        assertEquals( Map.of( "bob", NoticeReason.WATCHING, "lena", NoticeReason.ACKNOWLEDGE ), Recipients.of( done, f ) );
        final WorkEvent handoffDone = ev( WorkEvent.HANDOFF, "codex", Actor.Kind.AGENT, null, "done|", null );
        assertEquals( Map.of( "bob", NoticeReason.WATCHING, "lena", NoticeReason.ACKNOWLEDGE ), Recipients.of( handoffDone, f ) );
    }

    @Test
    void aPersonsCloseReachesNoLeads() {
        final EventFacts f = facts( NONE, NONE, Set.of( "lena" ), true );
        assertTrue( Recipients.of( byAlice( WorkEvent.STATUS, "done" ), f ).isEmpty() );
    }

    @Test
    void anAcknowledgedCloseReachesNoLeads() {
        final EventFacts f = facts( NONE, NONE, Set.of( "lena" ), false );
        assertTrue( Recipients.of( ev( WorkEvent.STATUS, "codex", Actor.Kind.AGENT, null, "done", null ), f ).isEmpty() );
    }

    @Test
    void changeAndDeploymentEventsReachWatchers() {
        for ( final String type : List.of( WorkEvent.REVIEW_REQUESTED, WorkEvent.CHANGE_MERGED, WorkEvent.SHIPPED,
            WorkEvent.ROLLED_BACK ) ) {
            assertEquals( Map.of( "bob", NoticeReason.WATCHING, "carl", NoticeReason.WATCHING ),
                Recipients.of( byAlice( type, null ), WATCHED ), type );
        }
    }

    @Test
    void aLapsedClaimReachesThePersonWhoReportedIt() {
        final WorkEvent e = ev( WorkEvent.CLAIM_EXPIRED, "codex", Actor.Kind.AGENT, null, null, null );
        assertEquals( Map.of( "rita", NoticeReason.CLAIM_EXPIRED ), Recipients.of( e, WATCHED ) );
    }

    @Test
    void aLapsedClaimOnAnAgentsItemReachesNobody() {
        final WorkEvent e = ev( WorkEvent.CLAIM_EXPIRED, "codex", Actor.Kind.AGENT, null, null, null );
        final EventFacts f = new EventFacts( null, NONE, NONE, NONE, NONE, false, false );
        assertTrue( Recipients.of( e, f ).isEmpty() );
    }

    @Test
    void aWebhookSwitchedOffReachesEveryLeadIncludingItsOwner() {
        final WorkEvent e = ev( WorkEvent.SETTINGS, "lena", Actor.Kind.HUMAN, "hooks.example.com", "failed", "webhook" );
        final EventFacts f = facts( NONE, NONE, Set.of( "lena", "marc" ), false );
        assertEquals( Map.of( "lena", NoticeReason.WEBHOOK_DISABLED, "marc", NoticeReason.WEBHOOK_DISABLED ),
            Recipients.of( e, f ) );
        assertTrue( Recipients.of( ev( WorkEvent.SETTINGS, "lena", Actor.Kind.HUMAN, null, "x", "name" ), f ).isEmpty() );
    }

    /** Event types that notify somebody; every other type in {@link WorkEvent} must reach nobody. */
    private static final Set< String > NOTIFYING = Set.of( WorkEvent.STATUS, WorkEvent.ASSIGNED, WorkEvent.HANDOFF,
        WorkEvent.COMMENTED, WorkEvent.COMMENT_EDITED, WorkEvent.SETTINGS, WorkEvent.REVIEW_REQUESTED,
        WorkEvent.CHANGE_MERGED, WorkEvent.SHIPPED, WorkEvent.ROLLED_BACK, WorkEvent.CLAIM_EXPIRED,
        WorkEvent.CHANGE_FLAGGED );

    private static Set< String > allEventTypes() {
        final Set< String > out = new java.util.TreeSet<>();
        for ( final java.lang.reflect.Field f : WorkEvent.class.getDeclaredFields() ) {
            final int m = f.getModifiers();
            if ( java.lang.reflect.Modifier.isStatic( m ) && java.lang.reflect.Modifier.isFinal( m )
                    && f.getType() == String.class ) {
                try {
                    out.add( (String) f.get( null ) );
                } catch ( final IllegalAccessException e ) {
                    throw new IllegalStateException( "cannot read WorkEvent." + f.getName(), e );
                }
            }
        }
        return out;
    }

    @Test
    void otherTypesReachNobody() {
        final EventFacts f = facts( Set.of( "bob" ), Set.of( "carl" ), Set.of( "lena" ), true );
        final Set< String > all = allEventTypes();
        assertTrue( all.size() > 25, "the reflection should find every type constant: " + all );
        assertTrue( all.containsAll( NOTIFYING ), "NOTIFYING names a type that does not exist" );
        for ( final String type : all ) {
            if ( !NOTIFYING.contains( type ) ) {
                assertTrue( Recipients.of( byAlice( type, "x" ), f ).isEmpty(),
                    type + " notifies somebody: add it to NOTIFYING with a test of who and why" );
            }
        }
    }

    @Test
    void sprintEventsReachNobody() {
        final EventFacts f = facts( Set.of( "bob" ), Set.of( "carl" ), Set.of( "lena" ), true );
        for ( final String type : List.of( WorkEvent.SPRINT_CREATED, WorkEvent.SPRINT_UPDATED, WorkEvent.SPRINT_STARTED,
            WorkEvent.SPRINT_CLOSED ) ) {
            final WorkEvent e = new WorkEvent( 1, 1, null, null, Instant.parse( "2026-01-01T00:00:00Z" ), "lena",
                Actor.Kind.HUMAN, null, type, null, "3", "Sprint 1" );
            assertTrue( Recipients.of( e, f ).isEmpty(), type );
        }
    }

    @Test
    void theActorNeverAppears() {
        final EventFacts f = facts( Set.of( "alice", "bob" ), Set.of( "alice" ), NONE, false );
        assertEquals( Map.of( "bob", NoticeReason.WATCHING ), Recipients.of( byAlice( WorkEvent.COMMENTED, "5" ), f ) );
        assertTrue( Recipients.of( byAlice( WorkEvent.ASSIGNED, "alice" ), f ).isEmpty() );
    }

    @Test
    void mentionedAndWatchingGetsMentionedOnce() {
        final EventFacts f = facts( Set.of( "carl" ), Set.of( "carl" ), NONE, false );
        assertEquals( Map.of( "carl", NoticeReason.MENTIONED ), Recipients.of( byAlice( WorkEvent.COMMENTED, "5" ), f ) );
    }

    @Test
    void groupLeadsJoinTheLeads() {
        final EventFacts f = new EventFacts( "rita", NONE, NONE, Set.of( "lena" ), Set.of( "ops" ), false, false )
            .withGroupLeads( Set.of( "marc" ) );
        assertEquals( Set.of( "lena", "marc" ), f.leads() );
        assertTrue( f.leadGroups().isEmpty() );
    }

    @Test
    void attachmentEventsReachNobodyYet() {
        final EventFacts f = facts( Set.of( "bob" ), Set.of( "carl" ), Set.of( "lena" ), true );
        for ( final String type : List.of( WorkEvent.ATTACHED, WorkEvent.ATTACHMENT_REMOVED ) ) {
            final WorkEvent e = new WorkEvent( 1, 1, 1L, "PLAT-1", Instant.parse( "2026-01-01T00:00:00Z" ), "lena",
                Actor.Kind.HUMAN, null, type, "attachment:3", "shot.png", null );
            assertTrue( Recipients.of( e, f ).isEmpty(), type );
        }
    }

    @Test
    void aFlaggedMergeReachesTheLeads() {
        final WorkEvent flag = ev( WorkEvent.CHANGE_FLAGGED, "codehost:github", Actor.Kind.SYSTEM, null, null, null );
        final EventFacts f = facts( Set.of( "ana" ), NONE, Set.of( "lena", "leo" ), false );
        assertEquals( Map.of( "lena", NoticeReason.ACKNOWLEDGE, "leo", NoticeReason.ACKNOWLEDGE ), Recipients.of( flag, f ) );
        for ( final String quiet : List.of( WorkEvent.CHANGE_SUPERSEDED, WorkEvent.CHANGE_FLAG_ACKNOWLEDGED ) ) {
            assertTrue( Recipients.of( ev( quiet, "codehost:github", Actor.Kind.SYSTEM, null, null, null ), f ).isEmpty(), quiet );
        }
    }

    // ---- a request from a person who only reports (the planner decides who that is)

    @Test
    void aReportersFilingReachesEveryLead() {
        final EventFacts f = facts( NONE, NONE, Set.of( "lena", "marc" ), false );
        final WorkEvent filed = ev( WorkEvent.CREATED, "rita", Actor.Kind.HUMAN, null, null, null );
        assertEquals( Set.of( "lena", "marc" ), Recipients.forRequest( filed, f, false ) );
    }

    @Test
    void aReportersCommentReachesTheLeadsOnlyWhileNobodyHoldsTheItem() {
        final EventFacts f = facts( NONE, NONE, Set.of( "lena" ), false );
        final WorkEvent said = ev( WorkEvent.COMMENTED, "rita", Actor.Kind.HUMAN, null, "5", null );
        assertEquals( Set.of( "lena" ), Recipients.forRequest( said, f, false ) );
        assertTrue( Recipients.forRequest( said, f, true ).isEmpty(), "an assigned item has its own listeners" );
    }

    @Test
    void aRemovedCommentAndEveryOtherTypeAreNoRequest() {
        final EventFacts removed = new EventFacts( "rita", NONE, NONE, Set.of( "lena" ), NONE, false, true );
        assertTrue( Recipients.forRequest( ev( WorkEvent.COMMENTED, "rita", Actor.Kind.HUMAN, null, "5", null ),
            removed, false ).isEmpty() );
        final EventFacts f = facts( NONE, NONE, Set.of( "lena" ), false );
        for ( final String type : allEventTypes() ) {
            if ( !WorkEvent.CREATED.equals( type ) && !WorkEvent.COMMENTED.equals( type ) ) {
                assertTrue( Recipients.forRequest( ev( type, "rita", Actor.Kind.HUMAN, null, null, null ), f, false )
                    .isEmpty(), type );
            }
        }
    }

    @Test
    void aLeadWhoFilesTheirOwnRequestIsNotTold() {
        final EventFacts f = facts( NONE, NONE, Set.of( "lena", "rita" ), false );
        assertEquals( Set.of( "lena" ),
            Recipients.forRequest( ev( WorkEvent.CREATED, "rita", Actor.Kind.HUMAN, null, null, null ), f, false ) );
    }
}

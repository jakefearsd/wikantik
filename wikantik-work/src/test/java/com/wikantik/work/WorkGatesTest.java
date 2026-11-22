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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Set;

import static com.wikantik.work.PolicyItems.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may pass each gate at each level. A person always may; the table is about agents. */
class WorkGatesTest {

    private static final long OPENER = 7;
    private static final long REVIEWER = 8;
    private static final long THIRD = 9;

    private static final Actor PERSON = Actor.human( "alice", Set.of(), false );

    private static Actor who( final String name ) {
        return switch ( name ) {
            case "person" -> PERSON;
            case "opener" -> Actor.agent( OPENER, "author-bot" );
            case "reviewer" -> Actor.agent( REVIEWER, "review-bot" );
            case "third" -> Actor.agent( THIRD, "other-bot" );
            default -> throw new IllegalArgumentException( name );
        };
    }

    /** A change agent 7 opened and agent 8 approved at the given head; the change's own head is {@code head}. */
    private static Change change( final String head, final String approvedHead ) {
        return new Change( 1, 1, 1, "work/PLAT-1-x", "main", head, null, ChangeState.IN_REVIEW, null, null,
            "author-bot", OPENER, approvedHead == null ? null : "review-bot", approvedHead == null ? null : REVIEWER,
            approvedHead, approvedHead == null ? null : Instant.EPOCH, null, null, Instant.EPOCH, null, null, null, "reported", null, null, null, null, null, null, null, null );
    }

    private static String outcome( final Decision d ) {
        return d.allowed() ? "ok" : d.reason();
    }

    @ParameterizedTest( name = "{0} {1} {2} -> {3}" )
    @CsvSource( delimiter = '|', value = {
        "supervised|person|approve|ok",
        "supervised|opener|approve|A person approves work as ready at this autonomy level.",
        "delegated|opener|approve|A person approves work as ready at this autonomy level.",
        "autonomous|opener|approve|ok",
        "autonomous|person|approve|ok",
        "supervised|person|close|ok",
        "supervised|opener|close|A person closes work at the supervised level.",
        "delegated|opener|close|ok",
        "autonomous|reviewer|close|ok",
        "supervised|person|review|ok",
        "supervised|opener|review|An agent may not review its own change.",
        "delegated|opener|review|An agent may not review its own change.",
        "autonomous|opener|review|An agent may not review its own change.",
        "supervised|reviewer|review|ok",
        "delegated|reviewer|review|ok",
        "delegated|third|review|ok",
        "autonomous|third|review|ok",
        "supervised|person|merge|ok",
        "delegated|person|merge|ok",
        "supervised|opener|merge|An agent may not merge its own change.",
        "delegated|opener|merge|An agent may not merge its own change.",
        "autonomous|opener|merge|An agent may not merge its own change.",
        "supervised|reviewer|merge|A person merges at the supervised level.",
        "supervised|third|merge|A person merges at the supervised level.",
        "delegated|reviewer|merge|ok",
        "autonomous|reviewer|merge|ok",
        "delegated|third|merge|The agent that approved this change merges it.",
        "autonomous|third|merge|The agent that approved this change merges it.",
        "supervised|person|direct|ok",
        "supervised|opener|direct|Direct commits by an agent need a lead to allow them on this item or its epic.",
        "delegated|opener|direct|Direct commits by an agent need a lead to allow them on this item or its epic.",
        "autonomous|opener|direct|Direct commits by an agent need a lead to allow them on this item or its epic."
    } )
    void gateTable( final String level, final String who, final String gate, final String expected ) {
        final EffectivePolicy p = policy( Autonomy.fromWire( level ), false );
        final Actor a = who( who );
        final Change c = change( "aaa1111", "aaa1111" );
        final Decision d = switch ( gate ) {
            case "approve" -> WorkGates.mayApprove( a, p );
            case "close" -> WorkGates.mayClose( a, p );
            case "review" -> WorkGates.mayReview( a, c );
            case "merge" -> WorkGates.mayMerge( a, c, p );
            case "direct" -> WorkGates.mayOpenDirect( a, p );
            default -> throw new IllegalArgumentException( gate );
        };
        assertEquals( expected, outcome( d ) );
    }

    @Test
    void anAgentCommitsDirectlyOnlyWhereALeadAllowedItAndNotWhenSupervised() {
        final Actor agent = who( "opener" );
        assertTrue( WorkGates.mayOpenDirect( agent, policy( Autonomy.DELEGATED, true ) ).allowed() );
        assertTrue( WorkGates.mayOpenDirect( agent, policy( Autonomy.AUTONOMOUS, true ) ).allowed() );
        assertEquals( "Direct commits by an agent need a lead to allow them on this item or its epic.",
            WorkGates.mayOpenDirect( agent, policy( Autonomy.SUPERVISED, true ) ).reason(),
            "at supervised a person merges, so the flag gives an agent nothing" );
    }

    @Test
    void aChangeWithNoApprovalForItsCommitIsNotAQuestionOfWhoAsks() {
        final Change moved = change( "bbb2222", "aaa1111" );
        assertFalse( moved.approvedForHead() );
        assertTrue( WorkGates.mayMerge( who( "reviewer" ), moved, policy( Autonomy.DELEGATED, false ) ).allowed(),
            "the gate passes; the service answers not_approved" );
        assertFalse( change( "aaa1111", null ).approvedForHead() );
        assertFalse( change( null, null ).approvedForHead(), "no commit, nothing approved" );
    }

    @Test
    void anAgentThatPutACommitOnAChangeIsOneOfItsAuthors() {
        final Change c = new Change( 1, 1, 1, "b", "main", "aaa1111", null, ChangeState.IN_REVIEW, null, null,
            "author-bot", OPENER, "other-bot", THIRD, "aaa1111", Instant.EPOCH, null, null, Instant.EPOCH, null,
            java.util.List.of( REVIEWER ), null, "reported", null, null, null, null, null, null, null, null );
        assertEquals( "An agent may not review its own change.", WorkGates.mayReview( who( "reviewer" ), c ).reason() );
        assertEquals( "An agent may not merge its own change.",
            WorkGates.mayMerge( who( "reviewer" ), c, policy( Autonomy.DELEGATED, false ) ).reason() );
        assertTrue( WorkGates.mayMerge( who( "third" ), c, policy( Autonomy.DELEGATED, false ) ).allowed() );
    }

    @Test
    void aChangeAPersonOpenedMayBeReviewedByAnyAgent() {
        final Change byPerson = new Change( 1, 1, 1, "b", "main", "aaa1111", null, ChangeState.IN_REVIEW, null, null,
            "alice", null, null, null, null, null, null, null, Instant.EPOCH, null, null, null, "reported", null, null, null, null, null, null, null, null );
        assertTrue( WorkGates.mayReview( who( "opener" ), byPerson ).allowed() );
    }

    @Test
    void aPersonIsNeverRefused() {
        final Change own = new Change( 1, 1, 1, "b", "main", "aaa1111", null, ChangeState.OPEN, null, null,
            "alice", null, null, null, null, null, null, null, Instant.EPOCH, null, null, null, "reported", null, null, null, null, null, null, null, null );
        for ( final Autonomy level : Autonomy.values() ) {
            final EffectivePolicy p = policy( level, false );
            assertTrue( WorkGates.mayApprove( PERSON, p ).allowed() );
            assertTrue( WorkGates.mayClose( PERSON, p ).allowed() );
            assertTrue( WorkGates.mayReview( PERSON, own ).allowed() );
            assertTrue( WorkGates.mayMerge( PERSON, own, p ).allowed(), "a person merges their own unreviewed change" );
            assertTrue( WorkGates.mayOpenDirect( PERSON, p ).allowed() );
            assertFalse( WorkGates.closesWithoutAPerson( PERSON, p ) );
        }
    }

    @Test
    void onlyAnAutonomousAgentCloseIsWithoutAPerson() {
        final Actor agent = who( "third" );
        assertTrue( WorkGates.closesWithoutAPerson( agent, policy( Autonomy.AUTONOMOUS, false ) ) );
        assertFalse( WorkGates.closesWithoutAPerson( agent, policy( Autonomy.DELEGATED, false ) ) );
    }

    @Test
    void wireValuesRoundTrip() {
        for ( final ChangeState s : ChangeState.values() ) {
            assertEquals( s, ChangeState.fromWire( s.wire() ) );
        }
        for ( final MergeKind k : MergeKind.values() ) {
            assertEquals( k, MergeKind.fromWire( k.wire() ) );
        }
        assertEquals( "in_review", ChangeState.IN_REVIEW.wire() );
        assertEquals( "unreviewed", MergeKind.UNREVIEWED.wire() );
    }
}

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

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An item an agent closes with no person in its path waits until a person acknowledges it. */
@RequiresPostgres
class WorkAcknowledgePostgresTest {

    private GateFixture f;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
    }

    /** A task at {@code level} that the author agent takes from the queue and closes. */
    private Item closedByAgent( final Autonomy level ) throws SQLException {
        final Item d = f.task();
        f.level( d, level );
        f.claimedBy( f.author, d );
        return f.status( f.author, d, "done" );
    }

    private Ack ack( final Item i ) throws SQLException {
        return f.svc.policyOf( f.lena, i.key() ).ack();
    }

    private List< String > lane( final Actor a ) throws SQLException {
        return f.svc.waitingOn( a ).stream().filter( w -> WaitingItem.ACKNOWLEDGE.equals( w.reason() ) )
            .map( w -> w.item().key() ).toList();
    }

    @Test
    void thePolicySaysWhatTheLevelWasBeforeTheProductsCeiling() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.AUTONOMOUS );
        assertEquals( Autonomy.AUTONOMOUS, f.svc.policyOf( f.lena, d.key() ).uncapped() );
        assertEquals( f.svc.policyOf( f.lena, d.key() ).effective().level(), f.svc.policyOf( f.lena, d.key() ).uncapped(),
            "no ceiling below it: both are the same" );
        f.ceiling( Autonomy.SUPERVISED );
        assertEquals( Autonomy.SUPERVISED, f.svc.policyOf( f.lena, d.key() ).effective().level() );
        assertEquals( Autonomy.AUTONOMOUS, f.svc.policyOf( f.lena, d.key() ).uncapped(), "what it would be without the product" );
    }

    @Test
    void anAutonomousAgentCloseIsFlagged() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        assertTrue( ack( d ).required() );
        assertNull( ack( d ).acknowledgedBy() );
        assertEquals( Autonomy.AUTONOMOUS, f.svc.policyOf( f.lena, d.key() ).effective().level() );
    }

    @Test
    void anAutonomousAgentCloseByHandoffIsFlaggedToo() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.AUTONOMOUS );
        f.claimedBy( f.author, d );
        f.handoff( f.author, d, WorkStatus.DONE, "lena", null );
        assertTrue( ack( d ).required() );
    }

    @Test
    void anAutonomousCloseByWayOfCancelledIsFlaggedToo() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.AUTONOMOUS );
        f.claimedBy( f.author, d );
        f.status( f.author, d, "cancelled" );
        f.status( f.author, d, "done" );
        assertTrue( ack( d ).required(), "cancelled then done is still a close with no person in the path" );
        f.status( f.lena, d, "cancelled" );
        assertFalse( ack( d ).required(), "leaving done clears it" );
    }

    @Test
    void aDelegatedAgentCloseIsNot() throws SQLException {
        assertFalse( ack( closedByAgent( Autonomy.DELEGATED ) ).required() );
    }

    @Test
    void aPersonsCloseIsNot() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.AUTONOMOUS );
        f.status( f.mia, d, "done" );
        assertFalse( ack( d ).required() );
        assertTrue( lane( f.lena ).isEmpty() );
    }

    @Test
    void acknowledgingClearsTheLaneAndWritesOneEvent() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        assertEquals( List.of( d.key() ), lane( f.lena ) );
        f.svc.acknowledge( f.mia, d.key() );
        assertTrue( lane( f.lena ).isEmpty() );
        assertEquals( "mia", ack( d ).acknowledgedBy() );
        assertNotNull( ack( d ).acknowledgedAt() );
        assertTrue( ack( d ).required(), "the record that it was closed without a person stays" );
        f.svc.acknowledge( f.lena, d.key() );
        assertEquals( "mia", ack( d ).acknowledgedBy(), "a second acknowledgement changes nothing" );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'acknowledged'" ) );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'acknowledged' AND actor = 'mia' "
            + "AND actor_kind = 'user'" ) );
    }

    @Test
    void acknowledgingAnItemThatNeedsNoneIsANoOp() throws SQLException {
        final Item d = closedByAgent( Autonomy.DELEGATED );
        f.svc.acknowledge( f.mia, d.key() );
        assertNull( ack( d ).acknowledgedBy() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE type = 'acknowledged'" ) );
    }

    @Test
    void anAgentCannotAcknowledge() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        assertEquals( "A person acknowledges work closed without one.",
            assertThrows( WorkDeniedException.class, () -> f.svc.acknowledge( f.reviewer, d.key() ) ).reason() );
        assertNull( ack( d ).acknowledgedBy() );
    }

    @Test
    void aViewerCannotAcknowledge() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        f.svc.putMember( f.admin, "PLAT", "vera", "user", WorkRole.VIEWER );
        assertThrows( WorkDeniedException.class,
            () -> f.svc.acknowledge( Actor.human( "vera", Set.of(), false ), d.key() ) );
    }

    @Test
    void aNonMemberGetsNotFound() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        final Actor stranger = Actor.human( "sam", Set.of(), false );
        assertThrows( WorkNotFoundException.class, () -> f.svc.acknowledge( stranger, d.key() ) );
        assertThrows( WorkNotFoundException.class, () -> f.svc.policyOf( stranger, d.key() ) );
        assertTrue( lane( stranger ).isEmpty() );
    }

    @Test
    void reopeningAnItemClearsTheFlag() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        f.svc.acknowledge( f.mia, d.key() );
        f.status( f.lena, d, "review" );
        assertFalse( ack( d ).required() );
        assertNull( ack( d ).acknowledgedBy() );
        assertTrue( lane( f.lena ).isEmpty() );
        f.status( f.lena, d, "done" );
        assertFalse( ack( d ).required(), "a person closed it this time" );
    }

    @Test
    void theLaneIsShownToLeadsAndAdminsOnly() throws SQLException {
        final Item d = closedByAgent( Autonomy.AUTONOMOUS );
        assertEquals( List.of( d.key() ), lane( f.lena ) );
        assertEquals( List.of( d.key() ), lane( f.admin ) );
        assertTrue( lane( f.mia ).isEmpty() );
        assertTrue( lane( f.author ).isEmpty() );
    }

    @Test
    void anArchivedProjectsClosesDoNotStayInTheLane() throws SQLException {
        final Item closed = closedByAgent( Autonomy.AUTONOMOUS );
        assertEquals( List.of( closed.key() ), lane( f.lena ) );
        f.svc.archiveProject( f.admin, "PLAT" );
        assertEquals( List.of(), lane( f.lena ), "it cannot be acknowledged there, so it is not asked for" );
        assertEquals( List.of(), lane( f.admin ) );
        assertTrue( ack( closed ).required(), "the record of how it was closed is kept" );
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The approve and close gates on items, through both paths that change a status: update and hand-off. */
@RequiresPostgres
class WorkItemGatesPostgresTest {

    private static final String DRAFT_RULE = "A person moves a draft forward.";
    private static final String APPROVE = "A person approves work as ready at this autonomy level.";
    private static final String CLOSE = "A person closes work at the supervised level.";

    private GateFixture f;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
    }

    private String denied( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkDeniedException.class, call ).reason();
    }

    @Test
    void anAgentMovesADraftToReadyOnlyWhenAutonomous() throws SQLException {
        final Item d = f.task();
        assertEquals( DRAFT_RULE, denied( () -> f.status( f.author, d, "ready" ) ) );
        assertEquals( DRAFT_RULE, denied( () -> f.handoff( f.author, d, WorkStatus.READY, "lena", null ) ) );
        f.level( d, Autonomy.AUTONOMOUS );
        assertEquals( WorkStatus.READY, f.status( f.author, d, "ready" ).status() );
    }

    @Test
    void anAgentMovesRefiningToReadyOnlyWhenAutonomous() throws SQLException {
        final Item d = f.task();
        f.status( f.lena, d, "refining" );
        assertEquals( APPROVE, denied( () -> f.status( f.author, d, "ready" ) ) );
        assertEquals( APPROVE, denied( () -> f.handoff( f.author, d, WorkStatus.READY, "lena", null ) ) );
        assertEquals( WorkStatus.DRAFT, f.status( f.author, d, "draft" ).status(), "back to draft is no approval" );
        f.status( f.lena, d, "refining" );
        f.level( d, Autonomy.AUTONOMOUS );
        assertEquals( WorkStatus.READY, f.handoff( f.author, d, WorkStatus.READY, "lena", null ).status() );
    }

    @Test
    void cancellingDoesNotGetAroundTheApproval() throws SQLException {
        final Item d = f.task();
        f.status( f.lena, d, "refining" );
        assertEquals( WorkStatus.CANCELLED, f.status( f.author, d, "cancelled" ).status(), "an agent may cancel" );
        assertEquals( APPROVE, denied( () -> f.status( f.author, d, "ready" ) ), "and may not revive it as ready" );
        assertEquals( APPROVE, denied( () -> f.status( f.author, d, "done" ) ) );
        assertEquals( APPROVE, denied( () -> f.handoff( f.author, d, WorkStatus.READY, "lena", null ) ) );
        assertEquals( WorkStatus.REFINING, f.status( f.author, d, "refining" ).status(), "back to refining approves nothing" );
        f.status( f.lena, d, "cancelled" );
        f.level( d, Autonomy.AUTONOMOUS );
        assertEquals( WorkStatus.READY, f.status( f.author, d, "ready" ).status() );
    }

    @Test
    void aHolderHandingAClaimedItemToAnotherQueueAsReadyIsNotAnApproval() throws SQLException {
        final Item d = f.task();
        f.claimedBy( f.author, d );
        final Item handed = f.handoff( f.author, d, WorkStatus.READY, null, "reviewer" );
        assertEquals( WorkStatus.READY, handed.status() );
        assertEquals( "reviewer", handed.queue() );
    }

    @Test
    void anAgentCannotCloseWhenSupervised() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.SUPERVISED );
        f.claimedBy( f.author, d );
        assertEquals( CLOSE, denied( () -> f.status( f.author, d, "done" ) ) );
        assertEquals( CLOSE, denied( () -> f.handoff( f.author, d, WorkStatus.DONE, "lena", null ) ) );
        assertEquals( WorkStatus.IN_PROGRESS, f.fresh( d ).status() );
        assertEquals( WorkStatus.REVIEW, f.status( f.author, d, "review" ).status(), "other moves stay open to it" );
    }

    @Test
    void anAgentClosesWhenDelegated() throws SQLException {
        final Item d = f.task();
        f.claimedBy( f.author, d );
        assertEquals( WorkStatus.DONE, f.status( f.author, d, "done" ).status() );
    }

    @Test
    void loweringTheEpicLevelRefusesTheNextClose() throws SQLException {
        final Item epic = f.draft( WorkLevel.EPIC, null );
        final Item d = f.draft( WorkLevel.STORY, epic.key() );
        f.claimedBy( f.author, d );
        f.level( epic, Autonomy.SUPERVISED );
        assertEquals( CLOSE, denied( () -> f.status( f.author, d, "done" ) ) );
        f.level( epic, null );
        assertEquals( WorkStatus.DONE, f.status( f.author, d, "done" ).status() );
    }

    @Test
    void theProductCeilingCapsAnAutonomousEpic() throws SQLException {
        final Item epic = f.draft( WorkLevel.EPIC, null );
        f.level( epic, Autonomy.AUTONOMOUS );
        final Item d = f.draft( WorkLevel.STORY, epic.key() );
        assertEquals( WorkStatus.READY, f.status( f.author, d, "ready" ).status(), "autonomous under the epic" );
        final Item second = f.draft( WorkLevel.STORY, epic.key() );
        f.ceiling( Autonomy.DELEGATED );
        assertEquals( DRAFT_RULE, denied( () -> f.status( f.author, second, "ready" ) ) );
        f.ceiling( Autonomy.SUPERVISED );
        f.claimedBy( f.author, second );
        assertEquals( CLOSE, denied( () -> f.status( f.author, second, "done" ) ) );
    }

    @Test
    void aPersonIsNeverGated() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.SUPERVISED );
        f.status( f.mia, d, "refining" );
        f.status( f.mia, d, "ready" );
        assertEquals( WorkStatus.DONE, f.status( f.mia, d, "done" ).status() );
    }

    @Test
    void aRefusedGateWritesNothing() throws SQLException {
        final Item d = f.task();
        f.level( d, Autonomy.SUPERVISED );
        f.claimedBy( f.author, d );
        final long before = f.count( "SELECT count(*) FROM work_event" );
        denied( () -> f.status( f.author, d, "done" ) );
        assertEquals( before, f.count( "SELECT count(*) FROM work_event" ) );
    }
}

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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two writers on one change, and a change racing the close of its item; and each write is all or nothing. */
@RequiresPostgres
class WorkChangeRacePostgresTest {

    private static final String A = "aaaaaaa1";
    private static final String B = "bbbbbbb2";

    private final ExecutorService pool = Executors.newFixedThreadPool( 2 );
    private GateFixture f;
    private Item item;
    private Change change;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        item = f.status( f.lena, f.task(), "ready" );
        change = f.svc.changes().open( f.author, item.key(), new ChangeDraft( "work/PLAT-1-x", null, A, null, null ) );
        f.svc.changes().requestReview( f.author, change.id() );
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private long count( final String type ) throws SQLException {
        return f.count( "SELECT count(*) FROM work_event WHERE type = '" + type + "'" );
    }

    @Test
    void twoMergesOfOneChangeProduceOneEvent() throws Exception {
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Change > first = pool.submit( () -> new JdbcWorkStore( gate ).changes().merge( item,
            change.id(), A, locked -> MergeKind.UNREVIEWED, f.mia ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Change > second = pool.submit( () -> f.svc.changes().merge( f.lena, change.id(), A ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        assertEquals( ChangeState.MERGED, first.get( 30, TimeUnit.SECONDS ).state() );
        final ExecutionException e = assertThrows( ExecutionException.class, () -> second.get( 30, TimeUnit.SECONDS ) );
        assertEquals( WorkConflictException.CHANGE_CLOSED,
            assertInstanceOf( WorkConflictException.class, e.getCause() ).reason() );
        assertEquals( 1, count( "change_merged" ) );
        assertEquals( "mia", f.svc.changes().list( f.lena, item.key() ).get( 0 ).mergedBy() );
    }

    @Test
    void aMergeWaitingOnANewCommitIsRefused() throws Exception {
        f.svc.changes().review( f.reviewer, change.id(), true, null );
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Change > push = pool.submit( () -> new JdbcWorkStore( gate ).changes().update( item,
            change.id(), B, null, null, locked -> { }, f.author ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Change > merge = pool.submit( () -> f.svc.changes().merge( f.reviewer, change.id(), A ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        push.get( 30, TimeUnit.SECONDS );
        final ExecutionException e = assertThrows( ExecutionException.class, () -> merge.get( 30, TimeUnit.SECONDS ) );
        assertEquals( WorkConflictException.NOT_APPROVED,
            assertInstanceOf( WorkConflictException.class, e.getCause() ).reason(),
            "the approval was for the older commit: decided on the row under lock, not on the earlier read" );
        assertEquals( 0, count( "change_merged" ) );
    }

    @Test
    void aCloseWaitingOnAnOpeningChangeIsRefused() throws Exception {
        f.svc.changes().abandon( f.author, change.id(), null );
        final CommitGate gate = new CommitGate( f.ds );
        final Future< Change > opening = pool.submit( () -> new JdbcWorkStore( gate ).changes().open( item,
            change.productId(), new ChangeDraft( "work/PLAT-1-y", "main", A, null, null ), f.mia ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Item > closing = pool.submit( () -> f.status( f.lena, item, "done" ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        opening.get( 30, TimeUnit.SECONDS );
        final ExecutionException e = assertThrows( ExecutionException.class, () -> closing.get( 30, TimeUnit.SECONDS ) );
        assertEquals( WorkConflictException.OPEN_CHANGES,
            assertInstanceOf( WorkConflictException.class, e.getCause() ).reason() );
        assertEquals( WorkStatus.READY, f.fresh( item ).status() );
    }

    // ---- all or nothing

    private JdbcWorkStore failingOn( final int statement ) {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( f.ds );
        faulty.failOn( statement, new RuntimeException( "boom: event insert" ) );
        return new JdbcWorkStore( faulty );
    }

    @Test
    void aFailedOpenLeavesNoChange() throws SQLException {
        // statements: 1 the product lock, 2 lock the item, 3 the branch check, 4 insert the change, 5 insert the event
        assertThrows( RuntimeException.class, () -> failingOn( 5 ).changes().open( item, change.productId(),
            new ChangeDraft( "work/PLAT-1-z", "main", A, null, null ), f.mia ) );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_change" ) );
        assertEquals( 1, count( "change_opened" ) );
    }

    @Test
    void aFailedReviewLeavesNoApproval() throws SQLException {
        // statements: 1 lock the change, 2 update it, 3 insert the event
        assertThrows( RuntimeException.class, () -> failingOn( 3 ).changes().review( item, change.id(), true, null,
            locked -> { }, f.mia ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change WHERE reviewed_head_sha IS NOT NULL" ) );
        assertEquals( 0, count( "change_reviewed" ) );
    }

    @Test
    void aFailedMergeLeavesTheChangeLive() throws SQLException {
        assertThrows( RuntimeException.class, () -> failingOn( 3 ).changes().merge( item, change.id(), A,
            locked -> MergeKind.UNREVIEWED, f.mia ) );
        assertEquals( ChangeState.IN_REVIEW, f.svc.changes().list( f.lena, item.key() ).get( 0 ).state() );
        assertEquals( 0, count( "change_merged" ) );
    }

    @Test
    void aRefusalUnderLockWritesNothing() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> f.svc.changes().review( f.author, change.id(), true, null ) );
        assertEquals( 0, count( "change_reviewed" ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_change WHERE reviewed_head_sha IS NOT NULL" ) );
    }

    @Test
    void aChangeWriteWaitingOnAnotherAgentsClaimIsDenied() throws Exception {
        final CommitGate gate = new CommitGate( f.ds );
        final Future< ? > claiming = pool.submit( () -> new JdbcWorkStore( gate ).claims().claim( f.fresh( item ),
            f.reviewer, java.time.Duration.ofMinutes( 30 ) ) );
        assertTrue( gate.reached.await( 30, TimeUnit.SECONDS ) );
        final Future< Change > push = pool.submit( () -> f.svc.changes().update( f.author, change.id(), B, null ) );
        WorkClaimFixtures.awaitBlockedBy( f.ds, gate.pid );
        gate.open();
        claiming.get( 30, TimeUnit.SECONDS );
        final ExecutionException e = assertThrows( ExecutionException.class, () -> push.get( 30, TimeUnit.SECONDS ) );
        assertEquals( "This item is claimed by another agent.",
            assertInstanceOf( WorkDeniedException.class, e.getCause() ).reason(),
            "the claim landed after the service read the item: caught on the locked row" );
        assertEquals( A, f.svc.changes().list( f.lena, item.key() ).get( 0 ).headSha() );
    }
}

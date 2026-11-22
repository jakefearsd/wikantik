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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every code-host write is one transaction: a fault at any statement leaves rows and events as they were. */
@RequiresPostgres
class JdbcWorkCodeHostRollbackTest {

    private static final int MAX_POINTS = 40;

    private CodeHostFixture f;
    private FaultInjectingDataSource faulty;
    private WorkCodeHostStore store;
    private Item item;
    private String branch;
    private final CodeHostWho ana = new CodeHostWho( "ana", "anagh" );

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        faulty = new FaultInjectingDataSource( f.ds );
        store = new JdbcWorkStore( faulty ).notifyStores().codeHost();
        item = f.task();
        branch = "work/" + item.key() + "-fix";
    }

    @Test
    void aFaultOnTheOpenLeavesNoChangeAndNoEvent() throws SQLException {
        final WorkCodeHostStore.BranchFacts facts = new WorkCodeHostStore.BranchFacts( branch, "main", CodeEvents.A,
            Instant.parse( "2026-03-01T09:00:00Z" ), ana, Instant.parse( "2026-03-01T11:00:00Z" ) );
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                assertEquals( CodeHostOutcome.APPLIED,
                    store.branchPush( f.product.id(), item, facts, f.github ).outcome() );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ), "change rows after failing at " + n );
                assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE actor_kind = 'system'" ),
                    "events after failing at " + n );
                continue;
            }
            assertTrue( exercised >= 4, "failure points exercised: " + exercised );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_change" ) );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_opened'" ) );
            return;
        }
        throw new AssertionError( "still failing after " + MAX_POINTS + " statements" );
    }

    @Test
    void aFaultOnTheApprovalLeavesNoApproval() throws SQLException {
        final WorkCodeHostStore.ReviewFacts open = new WorkCodeHostStore.ReviewFacts( "12", "https://github.com/a/b/pull/12",
            branch, "main", CodeEvents.A, Instant.parse( "2026-03-01T11:30:00Z" ), ana );
        new JdbcWorkStore( f.ds ).notifyStores().codeHost().review( f.product.id(), item, open,
            WorkCodeHostStore.ReviewStep.OPENED, CodeHostWho.UNKNOWN, f.github );
        final long events = f.count( "SELECT count(*) FROM work_event" );
        final WorkCodeHostStore.ReviewFacts approve = new WorkCodeHostStore.ReviewFacts( "12", null, branch, "main",
            CodeEvents.A, Instant.parse( "2026-03-01T11:45:00Z" ), ana );
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                assertEquals( CodeHostOutcome.APPLIED, store.review( f.product.id(), item, approve,
                    WorkCodeHostStore.ReviewStep.APPROVED, new CodeHostWho( "mia", "miagh" ), f.github ).outcome() );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 0, f.count( "SELECT count(*) FROM work_change WHERE reviewed_head_sha IS NOT NULL" ),
                    "approval after failing at " + n );
                assertEquals( events, f.count( "SELECT count(*) FROM work_event" ), "events after failing at " + n );
                continue;
            }
            assertTrue( exercised >= 3, "failure points exercised: " + exercised );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE reviewed_head_sha IS NOT NULL" ) );
            return;
        }
        throw new AssertionError( "still failing after " + MAX_POINTS + " statements" );
    }

    @Test
    void aFaultOnTheCloseLeavesTheChangeLive() throws SQLException {
        final WorkCodeHostStore.ReviewFacts open = new WorkCodeHostStore.ReviewFacts( "12", null, branch, "main",
            CodeEvents.A, Instant.parse( "2026-03-01T11:30:00Z" ), ana );
        new JdbcWorkStore( f.ds ).notifyStores().codeHost().review( f.product.id(), item, open,
            WorkCodeHostStore.ReviewStep.OPENED, CodeHostWho.UNKNOWN, f.github );
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                store.review( f.product.id(), item, open, WorkCodeHostStore.ReviewStep.CLOSED, CodeHostWho.UNKNOWN,
                    f.github );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE state = 'in_review'" ),
                    "state after failing at " + n );
                assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_abandoned'" ) );
                continue;
            }
            assertTrue( exercised >= 3, "failure points exercised: " + exercised );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE state = 'abandoned'" ) );
            return;
        }
        throw new AssertionError( "still failing after " + MAX_POINTS + " statements" );
    }

    @Test
    void aFaultOnTheFlagEventLeavesTheChangeLiveAndUnflagged() throws SQLException {
        final WorkCodeHostStore.ReviewFacts open = new WorkCodeHostStore.ReviewFacts( "12", null, branch, "main",
            CodeEvents.A, Instant.parse( "2026-03-01T11:30:00Z" ), ana );
        new JdbcWorkStore( f.ds ).notifyStores().codeHost().review( f.product.id(), item, open,
            WorkCodeHostStore.ReviewStep.OPENED, CodeHostWho.UNKNOWN, f.github );
        final long events = f.count( "SELECT count(*) FROM work_event" );
        final EffectivePolicy strict = new EffectivePolicy( Autonomy.DELEGATED, false, DoneMeans.MERGED );
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                assertEquals( CodeHostOutcome.APPLIED, store.merge( f.product.id(), item, open, CodeEvents.M,
                    ana, strict, f.github ).outcome() );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE state = 'in_review' "
                    + "AND policy_flag IS NULL AND merged_sha IS NULL" ), "change after failing at " + n );
                assertEquals( events, f.count( "SELECT count(*) FROM work_event" ), "events after failing at " + n );
                continue;
            }
            assertTrue( exercised >= 4, "failure points exercised: " + exercised );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE state = 'merged' AND policy_flag = 'unreviewed'" ) );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_flagged'" ) );
            return;
        }
        throw new AssertionError( "still failing after " + MAX_POINTS + " statements" );
    }

    @Test
    void aFaultOnTheDirectChangeEventLeavesNoChange() throws SQLException {
        final WorkCodeHostStore.DirectFacts facts = new WorkCodeHostStore.DirectFacts( "main", CodeEvents.M,
            CodeEvents.M, Instant.parse( "2026-03-01T09:00:00Z" ), java.util.Set.of( CodeEvents.M ), java.util.Set.of(),
            ana, Instant.parse( "2026-03-01T12:00:00Z" ) );
        int exercised = 0;
        for ( int n = 1; n <= MAX_POINTS; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                assertEquals( CodeHostOutcome.APPLIED,
                    store.direct( f.product.id(), item, facts, "direct", f.github ).outcome() );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 0, f.count( "SELECT count(*) FROM work_change" ), "change rows after failing at " + n );
                assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE actor_kind = 'system'" ),
                    "events after failing at " + n );
                continue;
            }
            assertTrue( exercised >= 4, "failure points exercised: " + exercised );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_flagged'" ) );
            return;
        }
        throw new AssertionError( "still failing after " + MAX_POINTS + " statements" );
    }

    @Test
    void theStoreRefusesAnyActorButTheSystem() {
        final WorkCodeHostStore.BranchFacts facts = new WorkCodeHostStore.BranchFacts( branch, "main", CodeEvents.A, null, ana,
            Instant.parse( "2026-03-01T11:00:00Z" ) );
        for ( final Actor who : new Actor[] { f.admin, f.lena, f.author } ) {
            org.junit.jupiter.api.Assertions.assertThrows( IllegalArgumentException.class,
                () -> store.branchPush( f.product.id(), item, facts, who ), who.login() );
        }
    }
}

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
import java.util.List;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A merge outside policy waits for a lead; acknowledging it records who and when and never clears the flag. */
@RequiresPostgres
class WorkChangeFlagsPostgresTest {

    private CodeHostFixture f;
    private Item item;
    private Change flagged;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        item = f.task();
        final String branch = "work/" + item.key() + "-fix";
        final HookDelivery d = f.delivery( "github" );
        f.hooks.apply( d, List.of( CodeEvents.opened( branch, "12", A, at( "11:00" ) ),
            CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) ) );
        flagged = f.only( item );
    }

    private List< String > waitingFor( final Actor who ) throws SQLException {
        return f.svc.waitingOn( who ).stream().filter( w -> w.reason().equals( WaitingItem.CHANGE_OUTSIDE_POLICY ) )
            .map( w -> w.item().key() ).toList();
    }

    private WorkConflictException conflict( final Actor who, final long id ) {
        return assertThrows( WorkConflictException.class, () -> f.svc.changes().acknowledgeFlag( who, id ) );
    }

    @Test
    void aLeadSeesAFlaggedMergeWaitingAndAMemberDoesNot() throws SQLException {
        assertEquals( "unreviewed", flagged.policyFlag() );
        assertEquals( List.of( item.key() ), waitingFor( f.lena ) );
        assertEquals( List.of(), waitingFor( f.mia ) );
        assertEquals( List.of(), waitingFor( f.ana ) );
    }

    @Test
    void anAdminSeesItToo() throws SQLException {
        assertEquals( List.of( item.key() ), waitingFor( f.admin ) );
    }

    @Test
    void aLeadAcknowledgesOnceAndTheLaneEmpties() throws SQLException {
        final Change done = f.svc.changes().acknowledgeFlag( f.lena, flagged.id() );
        assertEquals( "lena", done.flagAcknowledgedBy() );
        assertNotNull( done.flagAcknowledgedAt() );
        assertEquals( "unreviewed", done.policyFlag(), "the flag stays as the record" );
        assertEquals( List.of(), waitingFor( f.lena ) );
        assertEquals( WorkConflictException.ALREADY_ACKNOWLEDGED, conflict( f.lena, flagged.id() ).reason() );
        assertEquals( "unreviewed", f.only( item ).policyFlag() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_flag_acknowledged' "
            + "AND from_value = 'change:" + flagged.id() + "' AND to_value = 'unreviewed' AND actor = 'lena'" ) );
    }

    @Test
    void aMemberAndAnAgentCannotAcknowledge() {
        assertThrows( WorkDeniedException.class, () -> f.svc.changes().acknowledgeFlag( f.mia, flagged.id() ) );
        assertThrows( WorkDeniedException.class, () -> f.svc.changes().acknowledgeFlag( f.author, flagged.id() ) );
        assertThrows( WorkNotFoundException.class, () -> f.svc.changes().acknowledgeFlag( f.github, flagged.id() ),
            "the system actor has no standing: it is a stranger" );
        assertNull( assertDoesNotThrowOnRead().flagAcknowledgedAt() );
    }

    private Change assertDoesNotThrowOnRead() {
        try {
            return f.only( item );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( e );
        }
    }

    @Test
    void aStrangerGetsNotFound() throws SQLException {
        final Actor stranger = Actor.human( "mallory", java.util.Set.of(), false );
        assertThrows( WorkNotFoundException.class, () -> f.svc.changes().acknowledgeFlag( stranger, flagged.id() ) );
        assertThrows( WorkNotFoundException.class, () -> f.svc.changes().acknowledgeFlag( f.lena, 999_999L ) );
    }

    @Test
    void anUnflaggedChangeIsNotFlagged() throws SQLException {
        final Item other = f.task();
        final Change plain = f.svc.changes().open( f.author, other.key(),
            new ChangeDraft( "work/" + other.key() + "-x", null, A, null, null ) );
        assertEquals( WorkConflictException.NOT_FLAGGED, conflict( f.lena, plain.id() ).reason() );
    }

    @Test
    void anArchivedProjectRefuses() throws SQLException {
        f.svc.archiveProject( f.admin, "PLAT" );
        assertEquals( WorkConflictException.ARCHIVED, conflict( f.lena, flagged.id() ).reason() );
        assertEquals( List.of(), waitingFor( f.lena ), "nothing in an archived project can be acknowledged" );
    }

    @Test
    void aSupersededFlagNeverWaits() throws SQLException {
        final Item other = f.task();
        final String branch = "work/" + other.key() + "-fix";
        final HookDelivery d = f.delivery( "github" );
        f.hooks.apply( d, List.of( CodeEvents.opened( branch, "13", A, at( "11:00" ) ),
            CodeEvents.push( "main", "dddddd4", at( "11:21" ), commit( "dddddd4", other.key() + " fix login", at( "11:20" ),
                true, ANA ) ) ) );
        assertTrue( waitingFor( f.lena ).contains( other.key() ), "the direct push is flagged and waits" );
        f.hooks.apply( d, List.of( CodeEvents.merged( branch, "13", A, "dddddd4", at( "11:25" ), ANA ) ) );
        assertTrue( waitingFor( f.lena ).contains( other.key() ), "the merge itself (no approval) waits" );
        f.exec( "UPDATE work_change SET flag_acknowledged_at = NOW(), flag_acknowledged_by = 'lena' WHERE item_id = "
            + other.id() + " AND superseded_by IS NULL" );
        assertEquals( List.of( item.key() ), waitingFor( f.lena ),
            "the superseded direct row's flag does not bring the item back" );
    }

    @Test
    void aFaultOnTheEventLeavesTheFlagUnacknowledged() throws SQLException {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( f.ds );
        final WorkCodeHostStore store = new JdbcWorkStore( faulty ).notifyStores().codeHost();
        int exercised = 0;
        for ( int n = 1; n <= 20; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                store.acknowledgeFlag( item, flagged.id(), f.lena );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 0, f.count( "SELECT count(*) FROM work_change WHERE flag_acknowledged_at IS NOT NULL" ),
                    "acknowledged after failing at " + n );
                assertEquals( 0, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_flag_acknowledged'" ) );
                continue;
            }
            assertTrue( exercised >= 2, "failure points exercised: " + exercised );
            assertEquals( 1, f.count( "SELECT count(*) FROM work_change WHERE flag_acknowledged_at IS NOT NULL" ) );
            return;
        }
        throw new AssertionError( "still failing after 20 statements" );
    }
}

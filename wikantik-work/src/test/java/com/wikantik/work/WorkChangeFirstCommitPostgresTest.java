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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A first commit time reported by hand on a change: it only ever moves earlier, never into the future. */
@RequiresPostgres
class WorkChangeFirstCommitPostgresTest {

    private static final Instant NOW = Instant.parse( "2026-03-01T12:00:00Z" );

    private GateFixture f;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture( null, Clock.fixed( NOW, ZoneOffset.UTC ) );
        item = f.task();
        f.status( f.lena, item, "ready" );
    }

    private static ChangeDraft draft( final String first ) {
        return new ChangeDraft( "work/PLAT-1-x", null, "aaaaaaa1", null, null, first );
    }

    private long updateEvents() throws SQLException {
        return f.count( "SELECT count(*) FROM work_event WHERE type = 'change_updated'" );
    }

    @Test
    void aReportedFirstCommitTimeOnlyMovesEarlier() throws SQLException {
        final Change c = f.svc.changes().open( f.author, item.key(), draft( "2026-03-01T10:00:00Z" ) );
        assertEquals( Instant.parse( "2026-03-01T10:00:00Z" ), c.firstCommitAt() );
        final long id = c.id();
        assertEquals( Instant.parse( "2026-03-01T10:00:00Z" ),
            f.svc.changes().update( f.author, id, null, null, "2026-03-01T11:00:00Z" ).firstCommitAt() );
        assertEquals( 0, updateEvents(), "a later time moves nothing and writes nothing" );
        assertEquals( Instant.parse( "2026-03-01T09:00:00Z" ),
            f.svc.changes().update( f.author, id, null, null, "2026-03-01T09:00:00Z" ).firstCommitAt() );
        assertEquals( 1, updateEvents() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_updated' AND to_value IS NULL "
            + "AND note = 'first commit' AND from_value = 'change:" + id + "'" ) );
    }

    @Test
    void aFirstCommitTimeIsStoredWhenNoneWasThere() throws SQLException {
        final Change c = f.svc.changes().open( f.author, item.key(), draft( null ) );
        assertNull( c.firstCommitAt() );
        assertEquals( Instant.parse( "2026-03-01T08:00:00Z" ),
            f.svc.changes().update( f.author, c.id(), null, null, "2026-03-01T08:00:00Z" ).firstCommitAt() );
    }

    @Test
    void aFutureFirstCommitIsRefused() throws SQLException {
        assertEquals( "firstCommitAt", assertThrows( WorkValidationException.class,
            () -> f.svc.changes().open( f.author, item.key(), draft( "2026-03-01T12:00:01Z" ) ) ).field() );
        final Change c = f.svc.changes().open( f.author, item.key(), draft( null ) );
        assertEquals( "firstCommitAt", assertThrows( WorkValidationException.class,
            () -> f.svc.changes().update( f.author, c.id(), null, null, "2027-01-01T00:00:00Z" ) ).field() );
        assertEquals( "firstCommitAt", assertThrows( WorkValidationException.class,
            () -> f.svc.changes().update( f.author, c.id(), null, null, "yesterday" ) ).field() );
    }

    @Test
    void aFirstCommitOnAMergedChangeIsRefusedAsClosed() throws SQLException {
        final Change c = f.svc.changes().open( f.author, item.key(), draft( null ) );
        WorkAgentFixtures.exec( f.ds, "UPDATE work_change SET state = 'merged', merge_kind = 'reviewed', "
            + "merged_sha = 'aaaaaaa1', closed_at = '2026-02-01T00:00:00Z' WHERE id = " + c.id() );
        assertEquals( WorkConflictException.CHANGE_CLOSED, assertThrows( WorkConflictException.class,
            () -> f.svc.changes().update( f.author, c.id(), null, null, "2026-02-15T00:00:00Z" ) ).reason() );
    }
}

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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cards in a column are ordered by {@code (rank, id)}. A client that drops a card between two cards of equal rank
 * computes the same rank for the dropped card, so the asked-for order is lost on reload unless the server renumbers
 * the column. Renumbering changes rank only: no version bump, no event.
 */
@RequiresPostgres
class WorkBoardOrderPostgresTest {

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private DataSource ds;
    private WorkService svc;
    private Item a;
    private Item b;
    private Item c;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) { return Optional.empty(); }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, null );
        svc.createProject( admin, "PLAT", "Platform", "" );
        svc.putMember( admin, "PLAT", "lena", "user", WorkRole.LEAD );
        a = card( "A" );
        b = card( "B" );
        c = card( "C" );
    }

    private Item card( final String title ) throws SQLException {
        return svc.createItem( admin, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, title, "",
            null, null, null, null, null, null ) ).item();
    }

    private void setRank( final Item item, final double rank ) throws SQLException {
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET rank = " + rank + " WHERE id = " + item.id() );
    }

    /** Titles of the ready column (where a person's new cards start) in board order, read straight from the table. */
    private List< String > order() throws SQLException {
        final List< String > titles = new ArrayList<>();
        try ( Connection conn = ds.getConnection(); Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery( "SELECT title FROM work_item WHERE status = 'ready' ORDER BY rank, id" ) ) {
            while ( rs.next() ) {
                titles.add( rs.getString( 1 ) );
            }
        }
        return titles;
    }

    private double rankOf( final Item item ) throws SQLException {
        return svc.item( admin, item.key() ).item().rank();
    }

    private int versionOf( final Item item ) throws SQLException {
        return svc.item( admin, item.key() ).item().version();
    }

    /** A and B tied at 1024, C at 3072. */
    private void tieAandB() throws SQLException {
        setRank( a, 1024.0 );
        setRank( b, 1024.0 );
        setRank( c, 3072.0 );
        a = svc.item( admin, a.key() ).item();
        b = svc.item( admin, b.key() ).item();
        c = svc.item( admin, c.key() ).item();
    }

    private Item move( final Actor who, final Item item, final Map< String, Object > changes ) throws SQLException {
        return svc.updateItem( who, item.key(), new ItemPatch( svc.item( admin, item.key() ).item().version(),
            changes, null ) ).item();
    }

    @Test
    void aMoveBetweenTwoTiedCardsKeepsTheAskedOrderAfterReload() throws SQLException {
        tieAandB();
        final Item moved = move( admin, c, Map.of( "rank", 1024.0, "before", b.key() ) );
        assertEquals( List.of( "A", "C", "B" ), order() );
        assertNotEquals( 1024.0, moved.rank(), "the server renumbered, so the client can tell" );
    }

    @Test
    void aDropBeforeTheFirstTiedCardHolds() throws SQLException {
        tieAandB();
        move( admin, c, Map.of( "rank", 512.0, "before", a.key() ) );
        assertEquals( List.of( "C", "A", "B" ), order() );
    }

    @Test
    void aDropAtTheEndOfATiedColumnHolds() throws SQLException {
        tieAandB();
        setRank( c, 500.0 );
        move( admin, a, Map.of( "rank", 1024.0 ) );
        assertEquals( List.of( "C", "B", "A" ), order() );
        assertEquals( 3, new java.util.HashSet<>( List.of( rankOf( a ), rankOf( b ), rankOf( c ) ) ).size(),
            "every rank in the column is distinct" );
    }

    @Test
    void renumberingBumpsNoOtherVersionAndWritesNoEvent() throws SQLException {
        tieAandB();
        final int bVersion = versionOf( b );
        final int aVersion = versionOf( a );
        final long othersBefore = WorkAgentFixtures.scalar( ds,
            "SELECT count(*) FROM work_event WHERE item_id <> " + c.id() );
        move( admin, c, Map.of( "rank", 1024.0, "before", b.key() ) );
        assertEquals( bVersion, versionOf( b ) );
        assertEquals( aVersion, versionOf( a ) );
        assertEquals( othersBefore, WorkAgentFixtures.scalar( ds,
            "SELECT count(*) FROM work_event WHERE item_id <> " + c.id() ), "no event for a renumbered card" );
        final Item second = svc.updateItem( lena, b.key(), new ItemPatch( bVersion,
            Map.of( "rank", 100.0, "before", a.key() ), null ) ).item();
        assertEquals( bVersion + 1, second.version(), "B's old expectedVersion still works for a second member's move" );
        assertEquals( List.of( "B", "A", "C" ), order() );
    }

    @Test
    void aBeforeKeyOutsideTheTargetColumnIsRefused() throws SQLException {
        tieAandB();
        final Item moved = move( admin, card( "R" ), Map.of( "status", "draft" ) );
        final WorkValidationException other = assertThrows( WorkValidationException.class,
            () -> move( admin, c, Map.of( "rank", 10.0, "before", moved.key() ) ) );
        assertEquals( "before", other.field() );
        svc.createProject( admin, "OTHR", "Other", "" );
        final Item foreign = svc.createItem( admin, new ItemDraft( "OTHR", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "F", "", null, null, null, null, null, null ) ).item();
        final WorkValidationException cross = assertThrows( WorkValidationException.class,
            () -> move( admin, c, Map.of( "rank", 10.0, "before", foreign.key() ) ) );
        assertEquals( "before", cross.field() );
        assertEquals( other.getMessage(), cross.getMessage() );
        final WorkValidationException missing = assertThrows( WorkValidationException.class,
            () -> move( admin, c, Map.of( "rank", 10.0, "before", "PLAT-999" ) ) );
        assertEquals( "before", missing.field() );
        final WorkValidationException self = assertThrows( WorkValidationException.class,
            () -> move( admin, c, Map.of( "rank", 10.0, "before", c.key() ) ) );
        assertEquals( "before", self.field() );
        assertThrows( WorkValidationException.class, () -> move( admin, c, Map.of( "rank", 10.0, "before", " " ) ) );
    }

    @Test
    void aMoveWithRoomBetweenNeighboursRenumbersNothing() throws SQLException {
        final double ra = rankOf( a );
        final double rb = rankOf( b );
        final Item moved = move( admin, c, Map.of( "rank", ( ra + rb ) / 2, "before", b.key() ) );
        assertEquals( ( ra + rb ) / 2, moved.rank() );
        assertEquals( ra, rankOf( a ) );
        assertEquals( rb, rankOf( b ) );
        assertEquals( List.of( "A", "C", "B" ), order() );
        assertTrue( moved.rank() > ra && moved.rank() < rb );
    }

    @Test
    void aBeforeCardThatLeftTheColumnFallsBackToTheRankPosition() throws SQLException {
        tieAandB();
        final Item moved = move( admin, card( "R" ), Map.of( "status", "draft" ) );
        // B is dropped at rank 1024 (tied with A) naming a card that is no longer in the column: it keeps its
        // rank-based place after A, not the end of the column
        assertTrue( new JdbcWorkStore( ds ).renumberColumn( a.projectId(), WorkStatus.READY, b.id(), moved.id() ) );
        assertEquals( List.of( "A", "B", "C" ), order() );
    }

    @Test
    void aFailedRenumberStillAnswersTheSavedMove() throws SQLException {
        tieAandB();
        final JdbcWorkStore spy = org.mockito.Mockito.spy( new JdbcWorkStore( ds ) );
        org.mockito.Mockito.doThrow( new SQLException( "boom: renumber" ) ).when( spy )
            .renumberColumn( org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any() );
        final WorkService failing = new WorkService( spy, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) { return Optional.empty(); }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, null );
        final Item moved = failing.updateItem( admin, c.key(), new ItemPatch( c.version(),
            Map.of( "rank", 1024.0, "before", b.key() ), null ) ).item();
        assertEquals( 1024.0, moved.rank(), "the move was saved and is returned" );
        assertEquals( c.version() + 1, versionOf( c ) );
    }
}

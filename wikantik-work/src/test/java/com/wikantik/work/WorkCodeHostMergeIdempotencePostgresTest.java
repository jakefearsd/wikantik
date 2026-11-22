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

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.MIA;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The merge and direct-push half of idempotence: a delivery applied twice writes nothing the first did not. */
@RequiresPostgres
class WorkCodeHostMergeIdempotencePostgresTest {

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;
    private String branch;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        f.emails.put( "mia@example.com", "mia" );
        d = f.delivery( "github" );
        item = f.task();
        branch = "work/" + item.key() + "-fix";
    }

    private String snapshot() throws SQLException {
        try ( var c = f.ds.getConnection(); var s = c.createStatement();
              var rs = s.executeQuery( "SELECT COALESCE( ( SELECT string_agg( row_to_json( c )::text, '|' ORDER BY id ) "
                  + "FROM work_change c ), '' ) || '#' || ( SELECT count(*) FROM work_event )" ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    @Test
    void everyMergeAndDirectEventTwiceChangesNothing() throws SQLException {
        final Item other = f.task();
        final List< CodeEvent > all = List.of(
            CodeEvents.opened( branch, "12", A, at( "11:00" ) ), CodeEvents.approved( branch, "12", A, at( "11:10" ), MIA ),
            CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ),
            CodeEvents.push( "main", "ccccccc3", at( "12:00" ),
                commit( "ccccccc3", other.key() + " hotfix", at( "11:50" ), true, ANA ) ) );
        for ( final CodeEvent e : all ) {
            f.hooks.apply( d, List.of( e ) );
            final String once = snapshot();
            assertEquals( new WorkCodeHostService.Applied( 0, 1, 0 ), f.hooks.apply( d, List.of( e ) ), e.kind().toString() );
            assertEquals( once, snapshot(), "the second delivery of " + e.kind() + " changed something" );
        }
    }

    @Test
    void aDirectPushRedeliveredMakesNoSecondChange() throws SQLException {
        final CodeEvent push = CodeEvents.push( "main", M, at( "12:00" ),
            commit( M, item.key() + " fix", at( "11:50" ), true, ANA ) );
        f.hooks.apply( d, List.of( push ) );
        for ( int i = 0; i < 3; i++ ) {
            assertEquals( 0, f.hooks.apply( d, List.of( push ) ).applied() );
        }
        assertEquals( 1, f.changes( item ).size() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_event WHERE type = 'change_merged'" ) );
    }

    @Test
    void theSameMergedShaFromTwoPushesIsOneDirectChange() throws SQLException {
        final CodeEvent first = CodeEvents.push( "main", M, at( "12:00" ),
            commit( M, item.key() + " fix", at( "11:50" ), true, ANA ) );
        final CodeEvent second = CodeEvents.push( "main", M, at( "12:01" ),
            commit( M, item.key() + " fix again", at( "11:51" ), true, ANA ) );
        f.hooks.apply( d, List.of( first ) );
        f.hooks.apply( d, List.of( second ) );
        assertEquals( 1, f.changes( item ).size() );
    }

    @Test
    void aMergeRedeliveredAfterTheSupersedingChangedNothing() throws SQLException {
        f.hooks.apply( d, List.of( CodeEvents.opened( branch, "12", A, at( "11:00" ) ),
            CodeEvents.push( "main", M, at( "11:21" ), commit( M, item.key() + " fix login", at( "11:20" ), true, ANA ) ),
            CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ) ) );
        final String once = snapshot();
        f.hooks.apply( d, List.of( CodeEvents.merged( branch, "12", A, M, at( "11:20" ), ANA ),
            CodeEvents.push( "main", M, at( "11:21" ), commit( M, item.key() + " fix login", at( "11:20" ), true, ANA ) ) ) );
        assertEquals( once, snapshot() );
    }
}

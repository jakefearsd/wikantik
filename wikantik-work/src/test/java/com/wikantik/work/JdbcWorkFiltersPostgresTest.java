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

import java.sql.SQLException;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The saved-filter store over real PostgreSQL. */
@RequiresPostgres
class JdbcWorkFiltersPostgresTest {

    private WorkFilterStore filters;
    private Project plat;
    private Project ops;

    @BeforeEach
    void setUp() throws SQLException {
        final JdbcWorkStore store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        filters = store.notifyStores().filters();
        plat = store.createProject( "PLAT", "Platform", "" );
        ops = store.createProject( "OPS", "Operations", "" );
    }

    private static TreeMap< String, String > map( final String... kv ) {
        final TreeMap< String, String > m = new TreeMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    @Test
    void insertThenReadBack() throws SQLException {
        final TreeMap< String, String > q = map( "status", "ready,review", "q", "50% \"quoted\"" );
        final SavedFilter saved = filters.insert( "lena", "Ready", plat.id(), true, q );
        final SavedFilter read = filters.byId( saved.id() ).orElseThrow();
        assertEquals( q, read.filter() );
        assertEquals( "PLAT", read.projectKey() );
        assertEquals( plat.id(), read.projectId() );
        assertTrue( read.shared() );
        assertEquals( "lena", read.owner() );
        assertTrue( filters.insert( "lena", "Mine", null, false, map() ).projectKey() == null );
    }

    @Test
    void aDuplicateNameIsExists() throws SQLException {
        filters.insert( "lena", "Same", null, false, map() );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> filters.insert( "lena", "Same", plat.id(), true, map() ) );
        assertEquals( WorkConflictException.EXISTS, e.reason() );
        filters.insert( "bob", "Same", null, false, map() );
    }

    @Test
    void visibleToListsOwnAndSharedOfTheGivenProjects() throws SQLException {
        filters.insert( "lena", "b own private", null, false, map() );
        filters.insert( "lena", "a own shared", plat.id(), true, map() );
        filters.insert( "bob", "c bobs shared plat", plat.id(), true, map() );
        filters.insert( "bob", "d bobs shared ops", ops.id(), true, map() );
        filters.insert( "bob", "e bobs private", null, false, map() );
        assertEquals( List.of( "a own shared", "b own private", "c bobs shared plat" ),
            filters.visibleTo( "lena", List.of( plat.id() ), 50 ).stream().map( SavedFilter::name ).toList() );
        assertEquals( 2, filters.visibleTo( "lena", List.of( plat.id() ), 2 ).size(), "limited" );
        assertEquals( List.of( "b own private" ),
            filters.visibleTo( "lena", List.of(), 50 ).stream().map( SavedFilter::name ).toList() );
    }

    @Test
    void theLimitCountsOnlyRowsTheCallerMayList() throws SQLException {
        for ( int i = 0; i < 5; i++ ) {
            filters.insert( "lena", "gone " + i, ops.id(), false, map() );
        }
        filters.insert( "lena", "kept", plat.id(), false, map() );
        filters.insert( "lena", "own", null, false, map() );
        assertEquals( List.of( "kept", "own" ),
            filters.visibleTo( "lena", List.of( plat.id() ), 2 ).stream().map( SavedFilter::name ).toList(),
            "rows of a project that is not listed take no slot" );
        assertEquals( 7, filters.countOwned( "lena" ) );
        assertEquals( 0, filters.countOwned( "nobody" ) );
    }

    @Test
    void renameToATakenNameIsExists() throws SQLException {
        filters.insert( "lena", "One", null, false, map() );
        final SavedFilter two = filters.insert( "lena", "Two", null, false, map() );
        final WorkConflictException e = assertThrows( WorkConflictException.class, () -> filters.rename( two.id(), "One" ) );
        assertEquals( WorkConflictException.EXISTS, e.reason() );
        assertEquals( "23505", ( (SQLException) e.getCause() ).getSQLState() );
        assertEquals( "Three", filters.rename( two.id(), "Three" ).name() );
        assertThrows( WorkNotFoundException.class, () -> filters.rename( 999_999, "X" ) );
    }

    @Test
    void deleteRemovesTheRow() throws SQLException {
        final SavedFilter f = filters.insert( "lena", "Gone", null, false, map() );
        assertTrue( filters.delete( f.id() ) );
        assertTrue( filters.byId( f.id() ).isEmpty() );
    }

    @Test
    void deleteOfAnUnknownIdIsFalse() throws SQLException {
        assertFalse( filters.delete( 999_999 ) );
    }
}

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
package com.wikantik.auth.user;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link JdbcUserProfileRowMapper}'s row-mapping behaviour — extracted out of
 * {@link JDBCUserDatabase} for the PMD complexity-gate burn-down. The malformed-attributes
 * logging behaviour (log level, and that the ERROR names the affected login) stays covered
 * end-to-end against a real database by {@code JDBCUserDatabaseTest}.
 */
class JdbcUserProfileRowMapperTest {

    private final UserDatabase owningDatabase = new InMemoryUserDatabase();
    private final JdbcUserProfileRowMapper mapper = new JdbcUserProfileRowMapper( owningDatabase );

    /** Stubs a {@code users} row's columns exactly as {@link JdbcUserProfileRowMapper} reads them. */
    private static ResultSet rowFor( final String uid, final String loginName, final String wikiName,
                                      final String attributes ) throws SQLException {
        final ResultSet rs = mock( ResultSet.class );
        when( rs.getString( "uid" ) ).thenReturn( uid );
        when( rs.getTimestamp( "created" ) ).thenReturn( new Timestamp( 0L ) );
        when( rs.getString( "email" ) ).thenReturn( "someone@example.com" );
        when( rs.getString( "full_name" ) ).thenReturn( "Someone" );
        when( rs.getTimestamp( "modified" ) ).thenReturn( new Timestamp( 0L ) );
        when( rs.getTimestamp( "last_login" ) ).thenReturn( null );
        when( rs.getDate( "lock_expiry" ) ).thenReturn( null );
        when( rs.wasNull() ).thenReturn( true );
        when( rs.getString( "login_name" ) ).thenReturn( loginName );
        when( rs.getString( "password" ) ).thenReturn( "{bcrypt}stub" );
        when( rs.getString( "bio" ) ).thenReturn( null );
        when( rs.getBoolean( "password_must_change" ) ).thenReturn( false );
        when( rs.getString( "attributes" ) ).thenReturn( attributes );
        when( rs.getString( "wiki_name" ) ).thenReturn( wikiName );
        return rs;
    }

    @Test
    void mapProfileRowGeneratesUidWhenColumnIsNull() throws SQLException {
        final UserProfile profile = mapper.mapProfileRow( rowFor( null, "someone", "SomeoneWiki", null ) );

        assertNotNull( profile.getUid(), "a null uid column must be backfilled with a generated one" );
        assertEquals( "someone", profile.getLoginName() );
        assertEquals( "{bcrypt}stub", profile.getPassword() );
    }

    @Test
    void mapProfileRowKeepsTheExplicitUidColumn() throws SQLException {
        final UserProfile profile = mapper.mapProfileRow( rowFor( "fixed-uid", "someone", "SomeoneWiki", null ) );

        assertEquals( "fixed-uid", profile.getUid() );
    }

    @Test
    void mapProfileRowSkipsBlankAttributesWithoutError() throws SQLException {
        final UserProfile profile = mapper.mapProfileRow( rowFor( "uid-1", "someone", "SomeoneWiki", "" ) );

        assertTrue( profile.getAttributes().isEmpty(), "an empty attributes column is not a parse failure" );
    }

    @Test
    void mapWikiNameOrWarnAndSkipReturnsNullForEmptyWikiName() throws SQLException {
        assertNull( mapper.mapWikiNameOrWarnAndSkip( rowFor( "uid-1", "someone", "", null ) ) );
    }

    @Test
    void mapWikiNameOrWarnAndSkipReturnsTheValueWhenPresent() throws SQLException {
        assertEquals( "SomeoneWiki", mapper.mapWikiNameOrWarnAndSkip( rowFor( "uid-1", "someone", "SomeoneWiki", null ) ) );
    }

    @Test
    void mapProfileRowOrSkipEmptyWikiNameSkipsRowsWithoutAWikiName() throws SQLException {
        assertNull( mapper.mapProfileRowOrSkipEmptyWikiName( rowFor( "uid-1", "someone", null, null ) ) );
    }

    @Test
    void mapProfileRowOrSkipEmptyWikiNameMapsRowsThatHaveAWikiName() throws SQLException {
        final UserProfile profile = mapper.mapProfileRowOrSkipEmptyWikiName( rowFor( "uid-1", "someone", "SomeoneWiki", null ) );

        assertNotNull( profile );
        assertEquals( "someone", profile.getLoginName() );
    }
}

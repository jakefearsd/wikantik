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
package com.wikantik.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NullableColumnsTest {

    private final PreparedStatement ps = mock( PreparedStatement.class );
    private final ResultSet rs = mock( ResultSet.class );

    @Test
    void setStringBindsValueOrNull() throws SQLException {
        NullableColumns.setNullableString( ps, 1, "a" );
        NullableColumns.setNullableString( ps, 2, null );
        verify( ps ).setString( 1, "a" );
        verify( ps ).setNull( 2, Types.VARCHAR );
    }

    @Test
    void setDoubleBindsValueOrNull() throws SQLException {
        NullableColumns.setNullableDouble( ps, 1, 1.5 );
        NullableColumns.setNullableDouble( ps, 2, null );
        verify( ps ).setDouble( 1, 1.5 );
        verify( ps ).setNull( 2, Types.NUMERIC );
    }

    @Test
    void setLongBindsValueOrNull() throws SQLException {
        NullableColumns.setNullableLong( ps, 1, 7L );
        NullableColumns.setNullableLong( ps, 2, null );
        verify( ps ).setLong( 1, 7L );
        verify( ps ).setNull( 2, Types.BIGINT );
    }

    @Test
    void setBooleanBindsValueOrNull() throws SQLException {
        NullableColumns.setNullableBoolean( ps, 1, true );
        NullableColumns.setNullableBoolean( ps, 2, null );
        verify( ps ).setBoolean( 1, true );
        verify( ps ).setNull( 2, Types.BOOLEAN );
    }

    @Test
    void setDateBindsValueOrNull() throws SQLException {
        final LocalDate d = LocalDate.of( 2026, 11, 20 );
        NullableColumns.setNullableDate( ps, 1, d );
        NullableColumns.setNullableDate( ps, 2, null );
        verify( ps ).setDate( 1, Date.valueOf( d ) );
        verify( ps ).setNull( 2, Types.DATE );
    }

    @Test
    void getDoubleMapsSqlNull() throws SQLException {
        when( rs.getDouble( "v" ) ).thenReturn( 2.5, 0.0 );
        when( rs.wasNull() ).thenReturn( false, true );
        assertEquals( 2.5, NullableColumns.getNullableDouble( rs, "v" ) );
        assertNull( NullableColumns.getNullableDouble( rs, "v" ) );
    }

    @Test
    void getLongMapsSqlNull() throws SQLException {
        when( rs.getLong( "v" ) ).thenReturn( 9L, 0L );
        when( rs.wasNull() ).thenReturn( false, true );
        assertEquals( 9L, NullableColumns.getNullableLong( rs, "v" ) );
        assertNull( NullableColumns.getNullableLong( rs, "v" ) );
    }

    @Test
    void getInstantMapsNullTimestamp() throws SQLException {
        final Instant at = Instant.parse( "2026-11-20T10:00:00Z" );
        when( rs.getTimestamp( "t" ) ).thenReturn( Timestamp.from( at ), (Timestamp) null );
        assertEquals( at, NullableColumns.getInstant( rs, "t" ) );
        assertNull( NullableColumns.getInstant( rs, "t" ) );
    }

    @Test
    void getLocalDateMapsNullDate() throws SQLException {
        final LocalDate d = LocalDate.of( 2026, 1, 2 );
        when( rs.getDate( "d" ) ).thenReturn( Date.valueOf( d ), (Date) null );
        assertEquals( d, NullableColumns.getLocalDate( rs, "d" ) );
        assertNull( NullableColumns.getLocalDate( rs, "d" ) );
    }
}

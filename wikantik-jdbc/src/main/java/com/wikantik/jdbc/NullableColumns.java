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

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Nullable column helpers: binds that write a typed SQL NULL for a {@code null} Java value, and reads that map
 * SQL NULL back to {@code null} (a primitive getter alone would yield 0).
 */
public final class NullableColumns {

    private NullableColumns() {
    }

    /** Binds {@code value}, or SQL NULL typed VARCHAR when it is {@code null}. */
    public static void setNullableString( final PreparedStatement ps, final int index, final String value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.VARCHAR );
        } else {
            ps.setString( index, value );
        }
    }

    /** Binds {@code value}, or SQL NULL typed NUMERIC when it is {@code null}. */
    public static void setNullableDouble( final PreparedStatement ps, final int index, final Double value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.NUMERIC );
        } else {
            ps.setDouble( index, value );
        }
    }

    /** Binds {@code value}, or SQL NULL typed BIGINT when it is {@code null}. */
    public static void setNullableLong( final PreparedStatement ps, final int index, final Long value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.BIGINT );
        } else {
            ps.setLong( index, value );
        }
    }

    /** Binds {@code value}, or SQL NULL typed BOOLEAN when it is {@code null}. */
    public static void setNullableBoolean( final PreparedStatement ps, final int index, final Boolean value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.BOOLEAN );
        } else {
            ps.setBoolean( index, value );
        }
    }

    /** Binds {@code value}, or SQL NULL typed DATE when it is {@code null}. */
    public static void setNullableDate( final PreparedStatement ps, final int index, final LocalDate value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.DATE );
        } else {
            ps.setDate( index, Date.valueOf( value ) );
        }
    }

    /** Reads a numeric column, mapping SQL NULL to {@code null}. */
    public static Double getNullableDouble( final ResultSet rs, final String column ) throws SQLException {
        final double value = rs.getDouble( column );
        return rs.wasNull() ? null : value;
    }

    /** Reads an integer column, mapping SQL NULL to {@code null}. */
    public static Long getNullableLong( final ResultSet rs, final String column ) throws SQLException {
        final long value = rs.getLong( column );
        return rs.wasNull() ? null : value;
    }

    /** Reads a timestamp column as an {@link Instant}, mapping SQL NULL to {@code null}. */
    public static Instant getInstant( final ResultSet rs, final String column ) throws SQLException {
        final Timestamp ts = rs.getTimestamp( column );
        return ts == null ? null : ts.toInstant();
    }

    /** Reads a date column as a {@link LocalDate}, mapping SQL NULL to {@code null}. */
    public static LocalDate getLocalDate( final ResultSet rs, final String column ) throws SQLException {
        final Date date = rs.getDate( column );
        return date == null ? null : date.toLocalDate();
    }
}

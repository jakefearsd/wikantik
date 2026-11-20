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
package com.wikantik.insights;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Column-level helpers shared by the per-aggregate JDBC stores: nullable binds, nullable reads, batch totals. */
final class JdbcColumns {

    private JdbcColumns() {
    }

    /** Binds {@code value}, or SQL NULL typed VARCHAR when it is {@code null}. */
    static void setNullableString( final PreparedStatement ps, final int index, final String value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.VARCHAR );
        } else {
            ps.setString( index, value );
        }
    }

    /** Binds {@code value}, or SQL NULL typed NUMERIC when it is {@code null}. */
    static void setNullableDouble( final PreparedStatement ps, final int index, final Double value )
            throws SQLException {
        if ( value == null ) {
            ps.setNull( index, Types.NUMERIC );
        } else {
            ps.setDouble( index, value );
        }
    }

    /** Reads a numeric column, mapping SQL NULL to {@code null}. */
    static Double getNullableDouble( final ResultSet rs, final String column ) throws SQLException {
        final double value = rs.getDouble( column );
        return rs.wasNull() ? null : value;
    }

    /** Runs {@code sql} once per binder as a JDBC batch and returns the total rows affected (negative counts as 0). */
    static int batchAffected( final Jdbc jdbc, final String sql, final List<SqlBinder> binders )
            throws SQLException {
        final int[] results = jdbc.withConnection( conn -> jdbc.batch( conn, sql, binders ) );
        int total = 0;
        for ( final int result : results ) {
            total += Math.max( result, 0 );
        }
        return total;
    }
}

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

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;

/** Column and parameter conversions shared by the JDBC work stores. */
final class SqlValues {

    private SqlValues() {
    }

    static Instant instant( final ResultSet rs, final String column ) throws SQLException {
        final Timestamp ts = rs.getTimestamp( column );
        return ts == null ? null : ts.toInstant();
    }

    static Long nullableLong( final ResultSet rs, final String column ) throws SQLException {
        final long v = rs.getLong( column );
        return rs.wasNull() ? null : v;
    }

    static LocalDate localDate( final ResultSet rs, final String column ) throws SQLException {
        final Date d = rs.getDate( column );
        return d == null ? null : d.toLocalDate();
    }

    static Timestamp timestamp( final Instant at ) {
        return Timestamp.from( at );
    }

    static boolean isClosed( final WorkStatus s ) {
        return s == WorkStatus.DONE || s == WorkStatus.CANCELLED;
    }

    static String autonomyWire( final Autonomy a ) {
        return a == null ? null : a.wire();
    }

    static String doneMeansWire( final DoneMeans d ) {
        return d == null ? null : d.wire();
    }

    static void setNullableLong( final PreparedStatement ps, final int idx, final Long v )
            throws SQLException {
        if ( v == null ) {
            ps.setNull( idx, Types.BIGINT );
        } else {
            ps.setLong( idx, v );
        }
    }

    static void setNullableBoolean( final PreparedStatement ps, final int idx, final Boolean v )
            throws SQLException {
        if ( v == null ) {
            ps.setNull( idx, Types.BOOLEAN );
        } else {
            ps.setBoolean( idx, v );
        }
    }

    static void setNullableDate( final PreparedStatement ps, final int idx, final LocalDate v )
            throws SQLException {
        if ( v == null ) {
            ps.setNull( idx, Types.DATE );
        } else {
            ps.setDate( idx, Date.valueOf( v ) );
        }
    }

    static String actorKindWire( final Actor.Kind kind ) {
        return kind == Actor.Kind.AGENT ? "agent" : "user";
    }

    static String text( final Object o ) {
        return o == null ? null : o.toString();
    }
}

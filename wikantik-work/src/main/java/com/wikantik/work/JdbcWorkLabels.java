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

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import java.sql.SQLException;
import java.util.List;

/** Label reads over {@code work_item.labels}; split from {@link JdbcWorkStore} to keep it under the complexity limits. */
final class JdbcWorkLabels {

    private static final String COUNTS = "SELECT l.name, COUNT(*) AS items FROM work_item i "
        + "CROSS JOIN LATERAL unnest( i.labels ) AS l(name) "
        + "WHERE i.project_id = ? AND i.status <> 'cancelled' "
        + "GROUP BY l.name ORDER BY items DESC, l.name LIMIT 200";

    private final Jdbc jdbc;

    /** The labels column as a list; a SQL NULL is no labels. */
    static List< String > fromArray( final java.sql.Array a ) throws SQLException {
        return a == null ? List.of() : List.of( (String[]) a.getArray() );
    }

    JdbcWorkLabels( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    /** The labels on a project's non-cancelled items, most used first, then by name; at most 200. */
    List< LabelCount > counts( final long projectId ) throws SQLException {
        return jdbc.query( COUNTS, SqlBinder.positional( List.of( projectId ) ),
            rs -> new LabelCount( rs.getString( 1 ), rs.getInt( 2 ) ) );
    }
}

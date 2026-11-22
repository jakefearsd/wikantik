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

/** The PostgreSQL {@link WorkFindStore}: one statement over every named project. */
final class JdbcWorkFind implements WorkFindStore {

    private final Jdbc jdbc;

    JdbcWorkFind( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public List< Item > find( final FindSpec spec ) throws SQLException {
        if ( spec.projectIds().isEmpty() || spec.fetch() <= 0 ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> {
            final FindQuery.Built q = FindQuery.build( spec, conn );
            return jdbc.query( conn, q.sql(), SqlBinder.positional( q.params() ), JdbcWorkStore.ITEM_ROW );
        } );
    }
}

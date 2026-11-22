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

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** The PostgreSQL {@link WorkIntakeStore}. */
final class JdbcWorkIntake implements WorkIntakeStore {

    private static final String REPORTABLE_SQL = "SELECT " + JdbcWorkCatalog.PROJECT_COLUMNS + """
         FROM work_project p
        WHERE p.archived_at IS NULL
          AND EXISTS ( SELECT 1 FROM work_member m
                       WHERE m.project_id = p.id
                         AND ( ( m.principal_kind = 'user' AND m.principal = ? )
                            OR ( m.principal_kind = 'group' AND m.principal = ANY( ? ) ) ) )
        ORDER BY key
        """;

    private final Jdbc jdbc;

    JdbcWorkIntake( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public List< Project > reportableProjects( final Actor actor ) throws SQLException {
        if ( actor.kind() != Actor.Kind.HUMAN ) {
            return List.of();
        }
        if ( actor.instanceAdmin() ) {
            return jdbc.query( "SELECT " + JdbcWorkCatalog.PROJECT_COLUMNS
                + " FROM work_project WHERE archived_at IS NULL ORDER BY key", ps -> { }, JdbcWorkCatalog.PROJECT_ROW );
        }
        final String[] groups = JdbcWorkMembers.groupArray( actor );
        return jdbc.withConnection( conn -> jdbc.query( conn, REPORTABLE_SQL, ps -> {
            ps.setString( 1, JdbcWorkMembers.userPrincipal( actor ) );
            ps.setArray( 2, conn.createArrayOf( "text", groups ) );
        }, JdbcWorkCatalog.PROJECT_ROW ) );
    }

    @Override
    public boolean hasReporters( final long projectId ) throws SQLException {
        return jdbc.queryOne( "SELECT 1 FROM work_member WHERE project_id = ? AND role = 'reporter' LIMIT 1",
            ps -> ps.setLong( 1, projectId ), rs -> 1 ).isPresent();
    }

    @Override
    public List< Item > reportedBy( final String login, final Set< Long > projectIds, final int limit )
            throws SQLException {
        if ( login == null || projectIds == null || projectIds.isEmpty() || limit <= 0 ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, JdbcWorkStore.ITEM_SELECT
                + " WHERE i.reporter = ? AND i.reporter_agent_id IS NULL AND i.project_id = ANY( ? )"
                + " ORDER BY i.updated_at DESC, i.id DESC LIMIT ?",
            ps -> {
                ps.setString( 1, login );
                ps.setArray( 2, conn.createArrayOf( "bigint", projectIds.toArray( new Long[ 0 ] ) ) );
                ps.setInt( 3, limit );
            }, JdbcWorkStore.ITEM_ROW ) );
    }
}

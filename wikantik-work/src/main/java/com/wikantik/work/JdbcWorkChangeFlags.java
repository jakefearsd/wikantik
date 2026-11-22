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

/** Flagged merges: the lane of those a lead has yet to acknowledge, and the acknowledgement itself. */
final class JdbcWorkChangeFlags {

    private static final String WAITING = "c.policy_flag IS NOT NULL AND c.flag_acknowledged_at IS NULL "
        + "AND c.superseded_by IS NULL";

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkChangeFlags( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    List< Item > flagged( final Set< Long > projectIds ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, JdbcWorkStore.ITEM_SELECT
                + " WHERE i.project_id = ANY( ? ) AND EXISTS ( SELECT 1 FROM work_change c WHERE c.item_id = i.id AND "
                + WAITING + " ) ORDER BY ( SELECT max( c.id ) FROM work_change c WHERE c.item_id = i.id AND "
                + WAITING + " ) DESC, i.id DESC",
            ps -> ps.setArray( 1, conn.createArrayOf( "bigint", projectIds.toArray( new Long[ 0 ] ) ) ),
            JdbcWorkStore.ITEM_ROW ) );
    }

    Change acknowledge( final Item item, final long changeId, final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final Change locked = jdbc.queryOne( conn, JdbcWorkChanges.SELECT + " WHERE id = ? AND item_id = ? FOR UPDATE",
                ps -> {
                    ps.setLong( 1, changeId );
                    ps.setLong( 2, item.id() );
                }, JdbcWorkChanges.ROW ).orElseThrow( () -> new SQLException( "work_change " + changeId + " not found" ) );
            if ( locked.policyFlag() == null ) {
                throw new WorkConflictException( "This change was not merged outside policy.",
                    WorkConflictException.NOT_FLAGGED );
            }
            if ( locked.flagAcknowledgedAt() != null ) {
                throw new WorkConflictException( "A lead already acknowledged this merge.",
                    WorkConflictException.ALREADY_ACKNOWLEDGED );
            }
            jdbc.update( conn, "UPDATE work_change SET flag_acknowledged_by = ?, flag_acknowledged_at = NOW() WHERE id = ?",
                ps -> {
                    ps.setString( 1, by.login() );
                    ps.setLong( 2, changeId );
                } );
            catalog.appendEvent( conn, new WorkEvent( 0, item.projectId(), item.id(), null, null, by.login(), by.kind(),
                by.agentId(), WorkEvent.CHANGE_FLAG_ACKNOWLEDGED, "change:" + changeId, locked.policyFlag(), null ) );
            return jdbc.queryOne( conn, JdbcWorkChanges.SELECT + " WHERE id = ?", ps -> ps.setLong( 1, changeId ),
                JdbcWorkChanges.ROW ).orElseThrow( () -> new SQLException( "work_change " + changeId + " not found" ) );
        } );
    }
}

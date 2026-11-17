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

/** The PostgreSQL {@link WorkAckStore}. The flag itself is set and cleared by the item write that closes or reopens. */
final class JdbcWorkAcks implements WorkAckStore {

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkAcks( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override
    public Ack state( final long itemId ) throws SQLException {
        return jdbc.queryOne( "SELECT ack_required, acknowledged_by, acknowledged_at FROM work_item WHERE id = ?",
            ps -> ps.setLong( 1, itemId ), rs -> new Ack( rs.getBoolean( "ack_required" ),
                rs.getString( "acknowledged_by" ), SqlValues.instant( rs, "acknowledged_at" ) ) )
            .orElse( Ack.NONE );
    }

    @Override
    public boolean acknowledge( final Item item, final Actor by ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final int rows = jdbc.update( conn, "UPDATE work_item SET acknowledged_by = ?, acknowledged_at = NOW() "
                    + "WHERE id = ? AND ack_required AND acknowledged_at IS NULL",
                ps -> {
                    ps.setString( 1, by.login() );
                    ps.setLong( 2, item.id() );
                } );
            if ( rows == 0 ) {
                return false;
            }
            catalog.appendEvent( conn, new WorkEvent( 0, item.projectId(), item.id(), null, null, by.login(),
                by.kind(), by.agentId(), WorkEvent.ACKNOWLEDGED, null, null, null ) );
            return true;
        } );
    }

    @Override
    public List< Item > awaiting( final Set< Long > projectIds ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, JdbcWorkStore.ITEM_SELECT
                + " WHERE i.project_id = ANY( ? ) AND i.ack_required AND i.acknowledged_at IS NULL"
                + " ORDER BY i.closed_at DESC NULLS LAST, i.id DESC",
            ps -> ps.setArray( 1, conn.createArrayOf( "bigint", projectIds.toArray( new Long[ 0 ] ) ) ),
            JdbcWorkStore.ITEM_ROW ) );
    }
}

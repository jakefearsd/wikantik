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

import static com.wikantik.jdbc.NullableColumns.setNullableBoolean;
import static com.wikantik.jdbc.NullableColumns.setNullableDate;
import static com.wikantik.jdbc.NullableColumns.setNullableLong;
import static com.wikantik.work.SqlValues.autonomyWire;
import static com.wikantik.work.SqlValues.doneMeansWire;
import static com.wikantik.work.SqlValues.isClosed;

/**
 * The one transaction that creates an item: allocates the next key, inserts the row (rank = last in its status),
 * revision 1 and a {@code created} event. It exists apart from {@link JdbcWorkStore} (which is at its complexity
 * limit) so that a caller can run an {@link ItemGuard} first, in the same transaction.
 */
final class JdbcItemCreate {

    private static final double RANK_STEP = 1024.0;

    private final Jdbc jdbc;
    private final JdbcWorkStore store;

    JdbcItemCreate( final Jdbc jdbc, final JdbcWorkStore store ) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** Creates the item; {@code guard} runs first inside the transaction and may refuse by throwing. */
    Item create( final Project project, final Item draft, final Actor by, final ItemGuard guard ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        JdbcWorkCatalog.requireOneTarget( draft );
        final WorkStatus status = draft.status() == null ? WorkStatus.DRAFT : draft.status();
        final String body = draft.body() == null ? "" : draft.body();
        return jdbc.inTransaction( conn -> {
            guard.check( conn );
            final int seq = jdbc.queryOne( conn,
                "UPDATE work_project SET next_seq = next_seq + 1 WHERE id = ? RETURNING next_seq - 1",
                ps -> ps.setLong( 1, project.id() ), rs -> rs.getInt( 1 ) )
                .orElseThrow( () -> new SQLException( "work_project " + project.id() + " not found" ) );
            final double rank = jdbc.queryOne( conn,
                "SELECT COALESCE( MAX( rank ), 0 ) FROM work_item WHERE project_id = ? AND status = ?",
                ps -> { ps.setLong( 1, project.id() ); ps.setString( 2, status.wire() ); },
                rs -> rs.getDouble( 1 ) ).orElse( 0.0 ) + RANK_STEP;
            final long id = jdbc.queryOne( conn, """
                INSERT INTO work_item (project_id, seq, level, parent_id, product_id, kind, title, body, status,
                    priority, rank, due_on, assignee, reporter, autonomy, done_means, allow_direct, closed_at,
                    assignee_queue, reporter_agent_id, type, labels)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? THEN NOW() END, ?, ?, ?, ?)
                RETURNING id
                """,
                ps -> {
                    ps.setLong( 1, project.id() );
                    ps.setInt( 2, seq );
                    ps.setString( 3, draft.level().wire() );
                    setNullableLong( ps, 4, draft.parentId() );
                    setNullableLong( ps, 5, draft.productId() );
                    ps.setString( 6, draft.kind().wire() );
                    ps.setString( 7, draft.title() );
                    ps.setString( 8, body );
                    ps.setString( 9, status.wire() );
                    ps.setInt( 10, draft.priority() );
                    ps.setDouble( 11, rank );
                    setNullableDate( ps, 12, draft.dueOn() );
                    ps.setString( 13, draft.assignee() );
                    ps.setString( 14, by.login() );
                    ps.setString( 15, autonomyWire( draft.autonomy() ) );
                    ps.setString( 16, doneMeansWire( draft.doneMeans() ) );
                    setNullableBoolean( ps, 17, draft.allowDirect() );
                    ps.setBoolean( 18, isClosed( status ) );
                    ps.setString( 19, draft.queue() );
                    setNullableLong( ps, 20, by.agentId() );
                    ps.setString( 21, draft.type().wire() );
                    ps.setArray( 22, conn.createArrayOf( "text", draft.labels().toArray( new String[ 0 ] ) ) );
                }, rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new SQLException( "INSERT work_item returned no row" ) );
            store.insertRevision( conn, id, 1, draft.title(), body, by );
            store.appendEvent( conn, new WorkEvent( 0, project.id(), id, null, null, by.login(), by.kind(),
                by.agentId(), WorkEvent.CREATED, null, null, null ) );
            final String assignedTo = draft.queue() != null ? "queue:" + draft.queue() : draft.assignee();
            if ( assignedTo != null ) {
                // An item created with a person or queue is assigned from that moment: the log must say so.
                store.appendEvent( conn, new WorkEvent( 0, project.id(), id, null, null, by.login(), by.kind(),
                    by.agentId(), WorkEvent.ASSIGNED, null, assignedTo, null ) );
            }
            return store.itemById( conn, id );
        } );
    }
}

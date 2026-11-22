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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.wikantik.work.SqlValues.timestamp;

/**
 * The event queries behind {@link JdbcWorkStore#projectEvents} and {@link JdbcWorkStore#eventsAcross}: one statement,
 * newest first, on the compound {@code (at, id)} cursor, so a page boundary inside events that share a timestamp
 * (everything one transaction writes does) loses and repeats nothing.
 */
final class JdbcWorkEventLog {

    private static final int DEFAULT_LIMIT = 200;
    private static final String AGENT_VALUE = "agent:";

    private final Jdbc jdbc;

    JdbcWorkEventLog( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    List< WorkEvent > projectEvents( final long projectId, final Instant before, final Long beforeId,
                                     final Long itemId, final Set< String > types, final boolean withWebhookEvents,
                                     final int limit ) throws SQLException {
        return jdbc.withConnection( conn -> {
            final Query q = new Query( List.of( projectId ), before, beforeId, limit );
            if ( !withWebhookEvents ) {
                q.and( " AND NOT ( e.type = '" + WorkEvent.SETTINGS + "' AND e.note = 'webhook' )" );
            }
            if ( itemId != null ) {
                q.and( " AND e.item_id = ?", itemId );
            }
            if ( types != null && !types.isEmpty() ) {
                q.and( " AND e.type = ANY( ? )", typeArray( conn, types ) );
            }
            return run( conn, q, JdbcWorkStore.EVENT_ROW );
        } );
    }

    /**
     * The log: {@code types} defaults to {@link WorkEvent#ASSIGNMENT_LOG_TYPES}; a {@code member} event is kept only
     * when one of its values names an agent. {@code actorKind} keeps what people (or agents) did, and every
     * {@code claim_expired} whatever the kind, since an expiry is the system's doing. {@code agentId} keeps what the
     * agent did and the release of its claims.
     */
    List< LogEntry > eventsAcross( final Collection< Long > projectIds, final Instant before, final Long beforeId,
                                   final Set< String > types, final Actor.Kind actorKind, final Long agentId,
                                   final int limit ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> {
            final Query q = new Query( projectIds, before, beforeId, limit );
            q.and( " AND e.type = ANY( ? )", typeArray( conn,
                types == null || types.isEmpty() ? WorkEvent.ASSIGNMENT_LOG_TYPES : types ) );
            q.and( " AND ( e.type <> 'member' OR e.from_value LIKE 'agent:%' OR e.to_value LIKE 'agent:%' )" );
            if ( actorKind != null ) {
                // An expiry is a system event (attributed to the agent whose claim lapsed): shown under every "who".
                q.and( " AND ( e.actor_kind = ? OR e.type = '" + WorkEvent.CLAIM_EXPIRED + "' )",
                    SqlValues.actorKindWire( actorKind ) );
            }
            if ( agentId != null ) {
                final String held = AGENT_VALUE + agentId;
                q.and( " AND ( e.agent_id = ? OR e.from_value = ? OR e.from_value LIKE ? OR e.to_value LIKE ? )",
                    agentId, held, held + ":%", held + ":%" );
            }
            return run( conn, q, rs -> new LogEntry( JdbcWorkStore.EVENT_ROW.map( rs ), rs.getString( "item_title" ) ) );
        } );
    }

    private < T > List< T > run( final Connection conn, final Query q, final com.wikantik.jdbc.RowMapper< T > row )
            throws SQLException {
        return jdbc.query( conn, "SELECT " + JdbcWorkStore.EVENT_COLUMNS + ", i.title AS item_title"
                + JdbcWorkStore.EVENT_FROM + " WHERE e.project_id = ANY( ? )" + q.sql + " ORDER BY e.at DESC, e.id DESC LIMIT ?",
            ps -> {
                ps.setArray( 1, conn.createArrayOf( "bigint", q.projectIds.toArray( new Long[ 0 ] ) ) );
                for ( int i = 0; i < q.params.size(); i++ ) {
                    ps.setObject( i + 2, q.params.get( i ) );
                }
                ps.setInt( q.params.size() + 2, q.limit > 0 ? q.limit : DEFAULT_LIMIT );
            }, row );
    }

    private static java.sql.Array typeArray( final Connection conn, final Set< String > types ) throws SQLException {
        return conn.createArrayOf( "text", types.toArray( new String[ 0 ] ) );
    }

    /** The WHERE tail and its parameters; the cursor part is shared by both queries. */
    private static final class Query {
        final Set< Long > projectIds;
        final int limit;
        final StringBuilder sql = new StringBuilder();
        final List< Object > params = new ArrayList<>();

        Query( final Collection< Long > projectIds, final Instant before, final Long beforeId, final int limit ) {
            this.projectIds = new HashSet<>( projectIds );
            this.limit = limit;
            if ( before != null && beforeId != null ) {
                and( " AND ( e.at, e.id ) < ( ?, ? )", timestamp( before ), beforeId );
            } else if ( before != null ) {
                and( " AND e.at < ?", timestamp( before ) );
            }
        }

        void and( final String clause, final Object... values ) {
            sql.append( clause );
            params.addAll( List.of( values ) );
        }
    }
}

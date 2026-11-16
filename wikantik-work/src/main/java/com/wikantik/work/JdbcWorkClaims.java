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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.wikantik.work.JdbcWorkStore.ITEM_ROW;
import static com.wikantik.work.JdbcWorkStore.ITEM_SELECT;

/**
 * Claims behind {@link JdbcWorkStore}. A claim is one {@code UPDATE ... RETURNING}: under READ COMMITTED a second
 * claimer waits on the row lock and then re-checks the {@code WHERE}, so exactly one wins and nothing else is
 * needed. Endings (release, expiry, loss of access, retirement) lock the rows they end in the same statement, name
 * the holder from the locked row and write their event on the same connection.
 */
final class JdbcWorkClaims implements WorkClaimStore {

    /** Note on a release caused by an agent losing membership or being demoted to viewer. */
    static final String ACCESS_REMOVED = "access removed";
    /** Note on a release caused by retiring the agent. */
    static final String AGENT_RETIRED = "agent retired";

    private static final int DEFAULT_LIMIT = 200;

    /** The blocked rule of phase 1 ({@code isBlocked}, {@code itemExtras}) verbatim, with {@code i} the item. */
    private static final String NOT_BLOCKED = """
        NOT EXISTS ( SELECT 1
                       FROM work_link l
                       JOIN work_item b ON b.project_id = i.project_id AND b.status NOT IN ('done','cancelled')
                       JOIN work_project bp ON bp.id = b.project_id
                      WHERE l.item_id = i.id AND l.kind = 'blocked_by'
                        AND UPPER( TRIM( l.target ) ) = UPPER( bp.key || '-' || b.seq ) )
        """;

    private static final String CLAIM_SQL = """
        UPDATE work_item AS i
           SET claimed_by_agent = ?,
               claimed_at       = CASE WHEN i.claimed_by_agent = ? THEN i.claimed_at ELSE now() END,
               claim_expires_at = now() + make_interval( secs => ? ),
               status           = CASE WHEN i.status = 'ready' THEN 'in_progress' ELSE i.status END,
               version          = i.version + CASE WHEN i.status = 'ready' THEN 1 ELSE 0 END,
               updated_at       = now()
         WHERE i.id = ?
           AND EXISTS ( SELECT 1 FROM work_agent_queue aq
                         WHERE aq.agent_id = ? AND aq.queue_key = i.assignee_queue )
           AND (    ( i.claimed_by_agent IS NULL AND i.status IN ('ready', 'review') )
                 OR   i.claimed_by_agent = ? )
           AND\s""" + NOT_BLOCKED + """
        RETURNING i.id, ( i.claimed_at = now() ) AS taken, i.status
        """;

    /** Locks the rows to end in the CTE ({@code %s} is the condition on {@code w}, {@code %s} the tail of it). */
    private static final String ENDING_SQL = """
        WITH ended AS (
            SELECT w.id, w.claimed_by_agent AS agent_id, a.name AS agent_name, w.status AS from_status
              FROM work_item w JOIN work_agent a ON a.id = w.claimed_by_agent
             WHERE w.claimed_by_agent IS NOT NULL AND %s
             ORDER BY w.id %s)
        UPDATE work_item i
           SET claimed_by_agent = NULL, claimed_at = NULL, claim_expires_at = NULL,
               status     = CASE WHEN i.status = 'in_progress' THEN 'ready' ELSE i.status END,
               version    = i.version + CASE WHEN i.status = 'in_progress' THEN 1 ELSE 0 END,
               updated_at = now()
          FROM ended e WHERE i.id = e.id
        RETURNING i.id, i.project_id, e.agent_id, e.agent_name, e.from_status, i.status
        """;

    /** One claim ended by the statement above. */
    private record Ended( long itemId, long projectId, long agentId, String agentName, String fromStatus,
                          String status ) {
    }

    private record Taken( long itemId, boolean taken, String status ) {
    }

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkClaims( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    private static long requireAgent( final Actor agent ) {
        if ( agent.kind() != Actor.Kind.AGENT || agent.agentId() == null ) {
            throw new IllegalArgumentException( "a claim is taken by an agent actor carrying its work_agent id" );
        }
        return agent.agentId();
    }

    @Override
    public Optional< Item > claim( final Item item, final Actor agent, final Duration expiry ) throws SQLException {
        final long agentId = requireAgent( agent );
        final double seconds = expiry.toMillis() / 1000.0;
        return jdbc.inTransaction( conn -> {
            final Optional< Taken > won = jdbc.queryOne( conn, CLAIM_SQL, ps -> {
                ps.setLong( 1, agentId );
                ps.setLong( 2, agentId );
                ps.setDouble( 3, seconds );
                ps.setLong( 4, item.id() );
                ps.setLong( 5, agentId );
                ps.setLong( 6, agentId );
            }, rs -> new Taken( rs.getLong( "id" ), rs.getBoolean( "taken" ), rs.getString( "status" ) ) );
            if ( won.isEmpty() ) {
                return Optional.< Item >empty();
            }
            final Item after = itemById( conn, item.id() );
            final String expires = String.valueOf( after.claimExpiresAt() );
            if ( won.get().taken() ) {
                // A take moves a ready item to in progress and leaves a review item where it is.
                final String from = "in_progress".equals( won.get().status() ) ? "ready" : won.get().status();
                catalog.appendEvent( conn, event( item.projectId(), item.id(), agent, WorkEvent.CLAIMED, from,
                    won.get().status(), expires ) );
            } else {
                catalog.appendEvent( conn, event( item.projectId(), item.id(), agent, WorkEvent.CLAIM_RENEWED, null,
                    expires, null ) );
            }
            return Optional.of( after );
        } );
    }

    @Override
    public Optional< Item > release( final Item item, final Actor by, final String note ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        if ( item.claimedByAgent() == null ) {
            return Optional.empty();
        }
        final String reason = note == null || note.isBlank() ? "released" : note;
        return jdbc.inTransaction( conn -> {
            final List< Ended > ended = end( conn, "w.id = ? AND w.claimed_by_agent = ?", "FOR UPDATE OF w",
                ps -> { ps.setLong( 1, item.id() ); ps.setLong( 2, item.claimedByAgent() ); } );
            if ( ended.isEmpty() ) {
                return Optional.< Item >empty();
            }
            recordReleases( conn, ended, by, reason );
            return Optional.of( itemById( conn, item.id() ) );
        } );
    }

    @Override
    public List< Item > sweepExpiredClaims( final int max ) throws SQLException {
        if ( max <= 0 ) {
            return List.of();
        }
        return jdbc.inTransaction( conn -> {
            final List< Ended > ended = end( conn, "w.claim_expires_at <= now()", "LIMIT ? FOR UPDATE OF w SKIP LOCKED",
                ps -> ps.setInt( 1, max ) );
            for ( final Ended e : ended ) {
                catalog.appendEvent( conn, event( e.projectId(), e.itemId(), Actor.agent( e.agentId(), e.agentName() ),
                    WorkEvent.CLAIM_EXPIRED, e.fromStatus(), e.status(), null ) );
            }
            return items( conn, ended );
        } );
    }

    @Override
    public List< Item > claimable( final Long agentId, final Set< Long > projectIds, final String queue,
                                   final int limit ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        final StringBuilder sql = new StringBuilder( ITEM_SELECT ).append( """
             WHERE i.project_id = ANY( ? ) AND i.claimed_by_agent IS NULL AND i.status IN ('ready', 'review')
               AND i.assignee_queue IS NOT NULL AND i.assignee IS NULL AND\s""" ).append( NOT_BLOCKED );
        final List< Object > extra = new ArrayList<>();
        if ( agentId != null ) {
            sql.append( " AND EXISTS ( SELECT 1 FROM work_agent_queue aq WHERE aq.agent_id = ? "
                + "AND aq.queue_key = i.assignee_queue )" );
            extra.add( agentId );
        }
        if ( queue != null ) {
            sql.append( " AND i.assignee_queue = ?" );
            extra.add( queue );
        }
        sql.append( " ORDER BY i.priority ASC, i.due_on ASC NULLS LAST, i.id ASC LIMIT ?" );
        extra.add( limit > 0 ? limit : DEFAULT_LIMIT );
        return jdbc.withConnection( conn -> jdbc.query( conn, sql.toString(), ps -> {
            ps.setArray( 1, conn.createArrayOf( "bigint", projectIds.toArray() ) );
            for ( int i = 0; i < extra.size(); i++ ) {
                ps.setObject( i + 2, extra.get( i ) );
            }
        }, ITEM_ROW ) );
    }

    @Override
    public List< Item > claimsHeldBy( final long agentId, final Set< Long > projectIds ) throws SQLException {
        if ( projectIds == null || projectIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, ITEM_SELECT
                + " WHERE i.claimed_by_agent = ? AND i.project_id = ANY( ? ) ORDER BY i.claim_expires_at, i.id",
            ps -> {
                ps.setLong( 1, agentId );
                ps.setArray( 2, conn.createArrayOf( "bigint", projectIds.toArray() ) );
            }, ITEM_ROW ) );
    }

    @Override
    public long activeClaimCount() throws SQLException {
        return jdbc.queryOne( "SELECT COUNT(*) FROM work_item WHERE claimed_by_agent IS NOT NULL", ps -> { },
            rs -> rs.getLong( 1 ) ).orElse( 0L );
    }

    @Override
    public void retireAgent( final long agentId, final Instant at, final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        jdbc.inTransaction( conn -> {
            jdbc.update( conn, "UPDATE work_agent SET retired_at = ? WHERE id = ? AND retired_at IS NULL",
                ps -> { ps.setTimestamp( 1, SqlValues.timestamp( at ) ); ps.setLong( 2, agentId ); } );
            recordReleases( conn, end( conn, "w.claimed_by_agent = ?", "FOR UPDATE OF w",
                ps -> ps.setLong( 1, agentId ) ), by, AGENT_RETIRED );
            return null;
        } );
    }

    /**
     * Releases the agent's claims in one project on the caller's connection, inside the member transaction that
     * removed or demoted it. {@code agentPrincipal} is the text of an {@code agent} member row (the agent id).
     */
    void releaseOnAccessLoss( final Connection conn, final long projectId, final String agentPrincipal,
                              final Actor by, final String note ) throws SQLException {
        final long agentId;
        try {
            agentId = Long.parseLong( agentPrincipal );
        } catch ( final NumberFormatException e ) {
            return;   // not an agent id, so it cannot hold a claim
        }
        recordReleases( conn, end( conn, "w.claimed_by_agent = ? AND w.project_id = ?", "FOR UPDATE OF w",
            ps -> { ps.setLong( 1, agentId ); ps.setLong( 2, projectId ); } ), by, note );
    }

    // ---- internals

    private List< Ended > end( final Connection conn, final String condition, final String tail,
                               final SqlBinder binder ) throws SQLException {
        return jdbc.query( conn, ENDING_SQL.formatted( condition, tail ), binder, rs -> new Ended( rs.getLong( 1 ),
            rs.getLong( 2 ), rs.getLong( 3 ), rs.getString( 4 ), rs.getString( 5 ), rs.getString( 6 ) ) );
    }

    /** One {@code released} event per ended claim: the actor is who caused it, {@code from} names the holder. */
    private void recordReleases( final Connection conn, final List< Ended > ended, final Actor by,
                                 final String note ) throws SQLException {
        for ( final Ended e : ended ) {
            catalog.appendEvent( conn, event( e.projectId(), e.itemId(), by, WorkEvent.RELEASED,
                "agent:" + e.agentId(), e.status(), note ) );
        }
    }

    private Item itemById( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ), ITEM_ROW )
            .orElseThrow( () -> new SQLException( "work_item " + id + " not found" ) );
    }

    private List< Item > items( final Connection conn, final List< Ended > ended ) throws SQLException {
        if ( ended.isEmpty() ) {
            return List.of();
        }
        final Object[] ids = ended.stream().map( Ended::itemId ).toArray();
        return jdbc.query( conn, ITEM_SELECT + " WHERE i.id = ANY( ? ) ORDER BY i.id",
            ps -> ps.setArray( 1, conn.createArrayOf( "bigint", ids ) ), ITEM_ROW );
    }

    private static WorkEvent event( final long projectId, final long itemId, final Actor by, final String type,
                                    final String from, final String to, final String note ) {
        return new WorkEvent( 0, projectId, itemId, null, null, by.login(), by.kind(), by.agentId(), type, from, to,
            note );
    }
}

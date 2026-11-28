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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.wikantik.work.JdbcWorkStore.ITEM_ROW;
import static com.wikantik.work.JdbcWorkStore.ITEM_SELECT;

/**
 * Claims behind {@link JdbcWorkStore}. A claim is one {@code UPDATE ... RETURNING}: under READ COMMITTED a second
 * claimer waits on the row lock and then re-checks the {@code WHERE}, so exactly one wins and nothing else is
 * needed. A claim first takes shared locks on the agent row (still naming the sponsor the actor was resolved with),
 * the agent's member row, the sponsor's member rows that give at least {@code member} (skipped for a wiki-admin
 * sponsor) and the (unarchived) project row, so a retirement, removal, demotion, sponsor change or archive that is in
 * flight is waited for (and the claim then finds no row) rather than raced. Lock order is always agent row, the
 * agent's member row, the sponsor's member rows, project row, item row; retirement locks agent
 * then items, removal and demotion lock the member row then items, archiving locks the project row then items, and
 * none takes the others' rows, so they cannot form a cycle.
 * Endings (release, expiry, loss of access, retirement) lock the rows they end in the same statement, name the
 * holder from the locked row and write their event on the same connection.
 */
final class JdbcWorkClaims implements WorkClaimStore {

    /** Note on a release caused by an agent losing membership or being demoted to viewer. */
    static final String ACCESS_REMOVED = "access removed";
    /** Note on a release caused by retiring the agent. */
    static final String AGENT_RETIRED = "agent retired";
    /** Note on a release caused by archiving the item's project. */
    static final String PROJECT_ARCHIVED = "project archived";
    /** The sponsor of the holding agent no longer has the standing to lend it (membership, takeover, release). */
    static final String SPONSOR_ACCESS_REMOVED = "sponsor access removed";

    private static final Logger LOG = LogManager.getLogger( JdbcWorkClaims.class );
    private static final Pattern AGENT_ID = Pattern.compile( "\\d{1,18}" );
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

    /**
     * Fresh takes need an unheld ready or review item in a served queue that is not blocked; the holder's renewal
     * needs only that it is still the holder (access is checked by the row locks taken before this statement).
     */
    private static final String CLAIM_SQL = """
        UPDATE work_item AS i
           SET claimed_by_agent = ?,
               claimed_at       = CASE WHEN i.claimed_by_agent = ? THEN i.claimed_at ELSE now() END,
               claim_expires_at = now() + make_interval( secs => ? ),
               status           = CASE WHEN i.status = 'ready' THEN 'in_progress' ELSE i.status END,
               version          = i.version + CASE WHEN i.status = 'ready' THEN 1 ELSE 0 END,
               updated_at       = now()
         WHERE i.id = ? AND i.project_id = ?
           AND (   i.claimed_by_agent = ?
                OR (     i.claimed_by_agent IS NULL AND i.status IN ('ready', 'review')
                     AND EXISTS ( SELECT 1 FROM work_agent_queue aq
                                   WHERE aq.agent_id = ? AND aq.queue_key = i.assignee_queue )
                     AND\s""" + NOT_BLOCKED + """
                   ) )
        RETURNING i.id, i.project_id, ( i.claimed_at = now() ) AS taken, i.status
        """;

    /** Shared lock on the agent row: a retirement in flight is waited for, and a retired agent finds no row. */
    private static final String AGENT_LOCK_SQL =
        "SELECT 1 FROM work_agent WHERE id = ? AND retired_at IS NULL FOR SHARE";

    /**
     * The agent lock for an actor resolved with a sponsor: the row must still name that sponsor. A change of sponsor
     * updates the row, so it is waited for, and a claim resolved under the old sponsor then finds no row.
     */
    private static final String SPONSORED_AGENT_LOCK_SQL =
        "SELECT 1 FROM work_agent WHERE id = ? AND retired_at IS NULL AND sponsor = ? FOR SHARE";

    /**
     * Shared lock on every member row that gives the sponsor at least {@code member} in the project (their own row
     * or a group's). No row means the sponsor has no standing to lend, and the agent cannot claim; a removal or
     * demotion of one of those rows in flight is waited for.
     */
    private static final String SPONSOR_LOCK_SQL = "SELECT 1 FROM work_member WHERE project_id = ? "
        + "AND ( ( principal_kind = 'user' AND principal = ? ) OR ( principal_kind = 'group' AND principal = ANY( ? ) ) ) "
        + "AND role IN ( 'member', 'lead' ) FOR SHARE";

    /** Shared lock on the agent's member row: a removal or demotion in flight is waited for. */
    private static final String MEMBER_LOCK_SQL = "SELECT 1 FROM work_member WHERE project_id = ? "
        + "AND principal_kind = 'agent' AND principal = ? AND role = 'member' FOR SHARE";

    /**
     * Shared lock on the project row while it is not archived: an archive in flight (which updates the project row,
     * then ends the claims in it) is waited for, and a claim then finds the project archived instead of re-taking an
     * item the archive just released.
     */
    private static final String PROJECT_LOCK_SQL =
        "SELECT 1 FROM work_project WHERE id = ? AND archived_at IS NULL FOR SHARE";

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

    private record Taken( long itemId, long projectId, boolean taken, String status ) {
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
    public Optional< Claimed > claimWithOutcome( final Item item, final Actor agent, final Duration expiry )
            throws SQLException {
        final long agentId = requireAgent( agent );
        if ( expiry == null || expiry.isZero() || expiry.isNegative() ) {
            throw new IllegalArgumentException( "a claim expires after a positive duration" );
        }
        final double seconds = expiry.toMillis() / 1000.0;
        return jdbc.inTransaction( conn -> {
            // Lock order is always agent row, the agent's member row, the sponsor's member rows, project row, item
            // row; see the class comment on why.
            if ( !holdsAgentRow( conn, agentId, agent.sponsor() )
                    || !holdsRow( conn, MEMBER_LOCK_SQL, ps -> {
                        ps.setLong( 1, item.projectId() );
                        ps.setString( 2, String.valueOf( agentId ) );
                    } )
                    || !sponsorStands( conn, item.projectId(), agent.sponsor() ) ) {
                return Optional.< Claimed >empty();
            }
            if ( !holdsRow( conn, PROJECT_LOCK_SQL, ps -> ps.setLong( 1, item.projectId() ) ) ) {
                throw new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED );
            }
            final Optional< Taken > won = jdbc.queryOne( conn, CLAIM_SQL, ps -> {
                ps.setLong( 1, agentId );
                ps.setLong( 2, agentId );
                ps.setDouble( 3, seconds );
                ps.setLong( 4, item.id() );
                ps.setLong( 5, item.projectId() );
                ps.setLong( 6, agentId );
                ps.setLong( 7, agentId );
            }, rs -> new Taken( rs.getLong( "id" ), rs.getLong( "project_id" ), rs.getBoolean( "taken" ),
                rs.getString( "status" ) ) );
            if ( won.isEmpty() ) {
                return Optional.< Claimed >empty();
            }
            final Taken t = won.get();
            final Item after = itemById( conn, item.id() );
            final String expires = String.valueOf( after.claimExpiresAt() );
            if ( t.taken() ) {
                // A take moves a ready item to in progress and leaves a review item where it is.
                final String from = "in_progress".equals( t.status() ) ? "ready" : t.status();
                catalog.appendEvent( conn, event( t.projectId(), item.id(), agent, WorkEvent.CLAIMED, from,
                    t.status(), expires ) );
            } else {
                catalog.appendEvent( conn, event( t.projectId(), item.id(), agent, WorkEvent.CLAIM_RENEWED, null,
                    expires, null ) );
            }
            return Optional.of( new Claimed( after, !t.taken() ) );
        } );
    }

    private boolean holdsRow( final Connection conn, final String lockSql, final SqlBinder binder )
            throws SQLException {
        return !jdbc.query( conn, lockSql, binder, rs -> 1 ).isEmpty();
    }

    /** The agent row lock; an actor resolved with a sponsor must still have that sponsor on its row. */
    private boolean holdsAgentRow( final Connection conn, final long agentId, final Actor sponsor )
            throws SQLException {
        if ( sponsor == null ) {
            return holdsRow( conn, AGENT_LOCK_SQL, ps -> ps.setLong( 1, agentId ) );
        }
        return holdsRow( conn, SPONSORED_AGENT_LOCK_SQL, ps -> {
            ps.setLong( 1, agentId );
            ps.setString( 2, sponsor.login() );
        } );
    }

    /**
     * Whether the sponsor can lend the agent {@code member} standing in the project, share-locking the rows that say
     * so. A wiki admin sponsor needs none. An actor with no sponsor resolved is not checked here: the service gives
     * such an actor no standing at all, and only store-level callers build one.
     */
    private boolean sponsorStands( final Connection conn, final long projectId, final Actor sponsor )
            throws SQLException {
        if ( sponsor == null || sponsor.instanceAdmin() ) {
            return true;
        }
        final String[] groups = JdbcWorkMembers.groupArray( sponsor );
        return holdsRow( conn, SPONSOR_LOCK_SQL, ps -> {
            ps.setLong( 1, projectId );
            ps.setString( 2, JdbcWorkMembers.userPrincipal( sponsor ) );
            ps.setArray( 3, conn.createArrayOf( "text", groups ) );
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
            final List< Ended > ended = end( conn, "w.id = ? AND w.claimed_by_agent = ?", "FOR NO KEY UPDATE OF w",
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
            final List< Ended > ended = end( conn, "w.claim_expires_at <= now()", "LIMIT ? FOR NO KEY UPDATE OF w SKIP LOCKED",
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
        sql.append( " ORDER BY ( i.sprint_id IS NOT NULL AND EXISTS ( SELECT 1 FROM work_sprint s WHERE s.id = i.sprint_id"
        + " AND s.state = 'active' ) ) DESC, i.priority ASC, i.due_on ASC NULLS LAST, i.id ASC LIMIT ?" );
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
    public int retireAgent( final long agentId, final Instant at, final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            jdbc.update( conn, "UPDATE work_agent SET retired_at = ? WHERE id = ? AND retired_at IS NULL",
                ps -> { ps.setTimestamp( 1, SqlValues.timestamp( at ) ); ps.setLong( 2, agentId ); } );
            return recordReleases( conn, end( conn, "w.claimed_by_agent = ?", "FOR NO KEY UPDATE OF w",
                ps -> ps.setLong( 1, agentId ) ), by, AGENT_RETIRED );
        } );
    }

    /**
     * Releases the agent's claims in one project on the caller's connection, inside the member transaction that
     * removed or demoted it. {@code agentPrincipal} is the text of an {@code agent} member row (the agent id).
     */
    int releaseOnAccessLoss( final Connection conn, final long projectId, final String agentPrincipal,
                             final Actor by, final String note ) throws SQLException {
        if ( agentPrincipal == null || !AGENT_ID.matcher( agentPrincipal ).matches() ) {
            LOG.warn( "Skipped the claim release for an agent member row of project {}: principal '{}' is not an "
                + "agent id", projectId, agentPrincipal );
            return 0;
        }
        final long agentId = Long.parseLong( agentPrincipal );
        return recordReleases( conn, end( conn, "w.claimed_by_agent = ? AND w.project_id = ?", "FOR NO KEY UPDATE OF w",
            ps -> { ps.setLong( 1, agentId ); ps.setLong( 2, projectId ); } ), by, note );
    }

    /**
     * After a member row of the project changed, on that transaction's connection: each listed sponsor who is not a
     * wiki admin and whose role there is now below {@code member} loses, for the agents they sponsor, every claim in
     * the project.
     */
    int releaseForSponsors( final Connection conn, final long projectId, final java.util.Collection< Actor > sponsors,
                            final Actor by ) throws SQLException {
        int released = 0;
        for ( final Actor sponsor : sponsors ) {
            if ( sponsor == null || sponsor.instanceAdmin() || stands( conn, projectId, sponsor ) ) {
                continue;
            }
            released += recordReleases( conn, end( conn, "w.project_id = ? AND w.claimed_by_agent IN "
                    + "( SELECT s.id FROM work_agent s WHERE s.sponsor = ? )", "FOR NO KEY UPDATE OF w",
                ps -> { ps.setLong( 1, projectId ); ps.setString( 2, sponsor.login() ); } ), by,
                SPONSOR_ACCESS_REMOVED );
        }
        return released;
    }

    private boolean stands( final Connection conn, final long projectId, final Actor sponsor ) throws SQLException {
        final WorkRole role = catalog.roleOn( conn, projectId, sponsor ).orElse( null );
        return role != null && role.atLeast( WorkRole.MEMBER );
    }

    @Override
    public List< Holder > claimHolders( final Long projectIdOrNull ) throws SQLException {
        return jdbc.query( "SELECT DISTINCT w.claimed_by_agent, a.name, a.sponsor, w.project_id FROM work_item w "
                + "JOIN work_agent a ON a.id = w.claimed_by_agent WHERE w.claimed_by_agent IS NOT NULL "
                + "AND ( ?::bigint IS NULL OR w.project_id = ? ) ORDER BY w.project_id, w.claimed_by_agent",
            ps -> {
                ps.setObject( 1, projectIdOrNull, java.sql.Types.BIGINT );
                ps.setObject( 2, projectIdOrNull, java.sql.Types.BIGINT );
            },
            rs -> new Holder( rs.getLong( 1 ), rs.getString( 2 ), rs.getString( 3 ), rs.getLong( 4 ) ) );
    }

    @Override
    public int releaseForStanding( final long projectId, final java.util.Collection< Long > agentIds, final Actor by,
                                   final String note ) throws SQLException {
        if ( agentIds.isEmpty() ) {
            return 0;
        }
        final Object[] ids = agentIds.toArray();
        return jdbc.inTransaction( conn -> recordReleases( conn, end( conn,
            "w.project_id = ? AND w.claimed_by_agent = ANY( ? )", "FOR NO KEY UPDATE OF w",
            ps -> { ps.setLong( 1, projectId ); ps.setArray( 2, conn.createArrayOf( "bigint", ids ) ); } ), by, note ) );
    }

    @Override
    public int releaseForStanding( final long projectId, final long agentId, final String judgedSponsor, final Actor by,
                                   final String note ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            // Share-lock the agent row first, as a claim does: a takeover either finished before this (and the
            // sponsor no longer matches) or waits until the release is done.
            final boolean asJudged = jdbc.queryOne( conn, "SELECT 1 FROM work_agent WHERE id = ? "
                    + "AND sponsor IS NOT DISTINCT FROM ? FOR SHARE",
                ps -> { ps.setLong( 1, agentId ); ps.setString( 2, judgedSponsor ); }, rs -> Boolean.TRUE ).isPresent();
            if ( !asJudged ) {
                return 0;
            }
            return recordReleases( conn, end( conn, "w.project_id = ? AND w.claimed_by_agent = ?",
                "FOR NO KEY UPDATE OF w", ps -> { ps.setLong( 1, projectId ); ps.setLong( 2, agentId ); } ), by, note );
        } );
    }

    @Override
    public int setSponsorAndRelease( final long agentId, final String expectedSponsor, final Actor newSponsorOrNull,
                                     final String changedBy, final Actor by ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            // The caller decided on the sponsor it read; the row lock makes that the sponsor it replaces.
            final boolean asRead = jdbc.queryOne( conn, "SELECT 1 FROM work_agent WHERE id = ? "
                    + "AND sponsor IS NOT DISTINCT FROM ? FOR UPDATE",
                ps -> { ps.setLong( 1, agentId ); ps.setString( 2, expectedSponsor ); }, rs -> Boolean.TRUE ).isPresent();
            if ( !asRead ) {
                throw new WorkConflictException( "This agent's sponsor has just changed; read it again." );
            }
            catalog.setSponsor( conn, agentId, newSponsorOrNull == null ? null : newSponsorOrNull.login(), changedBy );
            if ( newSponsorOrNull != null && newSponsorOrNull.instanceAdmin() ) {
                return 0;
            }
            int released = 0;
            final List< Long > projects = jdbc.query( conn, "SELECT DISTINCT project_id FROM work_item "
                + "WHERE claimed_by_agent = ? ORDER BY project_id", ps -> ps.setLong( 1, agentId ), rs -> rs.getLong( 1 ) );
            for ( final long projectId : projects ) {
                if ( newSponsorOrNull == null || !stands( conn, projectId, newSponsorOrNull ) ) {
                    released += recordReleases( conn, end( conn, "w.claimed_by_agent = ? AND w.project_id = ?",
                        "FOR NO KEY UPDATE OF w", ps -> { ps.setLong( 1, agentId ); ps.setLong( 2, projectId ); } ), by,
                        SPONSOR_ACCESS_REMOVED );
                }
            }
            return released;
        } );
    }

    /** Releases every claim in the project on the caller's connection, inside the archive transaction. */
    int releaseInProject( final Connection conn, final long projectId, final Actor by ) throws SQLException {
        return recordReleases( conn, end( conn, "w.project_id = ?", "FOR NO KEY UPDATE OF w",
            ps -> ps.setLong( 1, projectId ) ), by, PROJECT_ARCHIVED );
    }

    // ---- internals

    private List< Ended > end( final Connection conn, final String condition, final String tail,
                               final SqlBinder binder ) throws SQLException {
        return jdbc.query( conn, ENDING_SQL.formatted( condition, tail ), binder, rs -> new Ended( rs.getLong( 1 ),
            rs.getLong( 2 ), rs.getLong( 3 ), rs.getString( 4 ), rs.getString( 5 ), rs.getString( 6 ) ) );
    }

    /** One {@code released} event per ended claim: the actor is who caused it, {@code from} names the holder. */
    private int recordReleases( final Connection conn, final List< Ended > ended, final Actor by,
                                final String note ) throws SQLException {
        for ( final Ended e : ended ) {
            catalog.appendEvent( conn, event( e.projectId(), e.itemId(), by, WorkEvent.RELEASED,
                "agent:" + e.agentId(), e.status(), note ) );
        }
        return ended.size();
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

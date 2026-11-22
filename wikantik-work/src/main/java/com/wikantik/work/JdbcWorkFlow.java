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
import com.wikantik.jdbc.RowMapper;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.getNullableLong;

/**
 * The PostgreSQL {@link WorkFlowStore}. Read only: every figure is computed from the event log and the tables as
 * they are now, and nothing is stored (WorkFlowNoStoreRuleTest). Times arrive as instants computed in Java; the
 * zone never reaches SQL. The item filter is appended through {@link ItemQuery#conditions}, exactly as the board
 * does, and the project it names is bound as a parameter.
 */
final class JdbcWorkFlow implements WorkFlowStore {

    private static final String FIRST_START = "( SELECT MIN( s.at ) FROM work_event s WHERE s.item_id = i.id "
        + "AND s.type = 'status' AND s.to_value = 'in_progress' )";

    /** An item counts at its LAST move to done, and only while it is still done. */
    private static final String CLOSED = """
        SELECT i.id, p.key || '-' || i.seq AS item_key, i.created_at, i.estimate, d.closed_at,
               %s AS first_in_progress_at
          FROM work_item i
          JOIN work_project p ON p.id = i.project_id
          JOIN LATERAL ( SELECT MAX( e.at ) AS closed_at FROM work_event e
                          WHERE e.item_id = i.id AND e.type = 'status' AND e.to_value = 'done' ) d ON TRUE
         WHERE i.status = 'done' AND i.level <> 'epic'
           AND d.closed_at >= ? AND d.closed_at < ?
        """.formatted( FIRST_START );

    private static final String OPEN = """
        SELECT p.key || '-' || i.seq AS item_key, i.title, i.status, i.created_at,
               %s AS first_in_progress_at
          FROM work_item i
          JOIN work_project p ON p.id = i.project_id
         WHERE i.status IN ( 'in_progress', 'review' ) AND i.level <> 'epic'
        """.formatted( FIRST_START );

    private static final RowMapper< ClosedItem > CLOSED_ROW = rs -> new ClosedItem( rs.getLong( "id" ),
        rs.getString( "item_key" ), getInstant( rs, "created_at" ), getInstant( rs, "first_in_progress_at" ),
        getInstant( rs, "closed_at" ), rs.getBigDecimal( "estimate" ) );

    private static final RowMapper< OpenItem > OPEN_ROW = rs -> new OpenItem( rs.getString( "item_key" ),
        rs.getString( "title" ), WorkStatus.fromWire( rs.getString( "status" ) ), getInstant( rs, "created_at" ),
        getInstant( rs, "first_in_progress_at" ) );

    private static final String DEPLOYMENTS = """
        SELECT d.id, d.product_id, d.state, d.started_at, d.verified_at, d.remediates_id,
               ( SELECT MIN( e.at ) FROM work_deployment_event e
                  WHERE e.deployment_id = d.id AND e.to_state = 'failed' ) AS failed_at,
               ( SELECT MIN( e.at ) FROM work_deployment_event e
                  WHERE e.deployment_id = d.id AND e.to_state = 'rolled_back' ) AS rolled_back_at,
               EXISTS ( SELECT 1 FROM work_deployment r
                         WHERE r.remediates_id = d.id AND r.state <> 'rejected' ) AS remediated
          FROM work_deployment d
         WHERE d.environment = 'production'
           AND ( ( d.started_at >= ? AND d.started_at < ? ) OR ( d.verified_at >= ? AND d.verified_at < ? ) )
        """;

    private static final String PROJECT_CARRIES = """
           AND EXISTS ( SELECT 1 FROM work_deployment_item di JOIN work_item i ON i.id = di.item_id
                         WHERE di.deployment_id = d.id AND i.project_id = ? )
        """;

    private static final String RECOVERY_ENDS = """
        SELECT d.id, d.product_id, d.verified_at, d.remediates_id
          FROM work_deployment d
         WHERE d.environment = 'production' AND d.state = 'verified' AND d.verified_at IS NOT NULL
           AND d.verified_at >= ? AND d.product_id = ANY( ? )
         ORDER BY d.verified_at, d.id
        """;

    private static final String SHIPPED = """
        SELECT x.id, x.first_commit_at, x.opened_at, x.merged_at, x.shipped_at FROM (
          SELECT c.id, c.first_commit_at, c.opened_at, c.closed_at AS merged_at,
                 ( SELECT MIN( d.verified_at )
                     FROM work_deployment_item di JOIN work_deployment d ON d.id = di.deployment_id
                    WHERE di.item_id = c.item_id AND d.product_id = c.product_id
                      AND d.environment = 'production' AND d.state = 'verified'
                      AND d.verified_at >= c.closed_at ) AS shipped_at
            FROM work_change c JOIN work_item i ON i.id = c.item_id
           WHERE c.state = 'merged' AND c.superseded_by IS NULL AND c.closed_at < ? AND c.closed_at >= ?
        """;

    private static final String SHIPPED_TAIL = """
        ) x
         WHERE x.shipped_at >= ? AND x.shipped_at < ?
         ORDER BY x.shipped_at, x.id
        """;

    private static final String MERGES = """
        SELECT COUNT(*) FILTER ( WHERE c.merge_kind = 'reviewed' )   AS reviewed,
               COUNT(*) FILTER ( WHERE c.merge_kind = 'unreviewed' ) AS unreviewed,
               COUNT(*) FILTER ( WHERE c.merge_kind = 'direct' )     AS direct,
               COUNT(*) FILTER ( WHERE c.policy_flag IS NOT NULL )   AS flagged,
               COUNT(*) FILTER ( WHERE c.policy_flag IS NOT NULL AND c.flag_acknowledged_at IS NULL ) AS flagged_open
          FROM work_change c JOIN work_item i ON i.id = c.item_id
         WHERE c.state = 'merged' AND c.superseded_by IS NULL AND c.closed_at >= ? AND c.closed_at < ?
        """;

    private static final RowMapper< DeploymentFact > DEPLOYMENT_ROW = rs -> new DeploymentFact( rs.getLong( "id" ),
        rs.getLong( "product_id" ), DeploymentState.fromWire( rs.getString( "state" ) ),
        getInstant( rs, "started_at" ), getInstant( rs, "verified_at" ), getInstant( rs, "failed_at" ),
        getInstant( rs, "rolled_back_at" ), getNullableLong( rs, "remediates_id" ), rs.getBoolean( "remediated" ) );

    private static final RowMapper< RecoveryEnd > END_ROW = rs -> new RecoveryEnd( rs.getLong( "id" ),
        rs.getLong( "product_id" ), getInstant( rs, "verified_at" ), getNullableLong( rs, "remediates_id" ) );

    private static final RowMapper< ChangeFact > CHANGE_ROW = rs -> new ChangeFact( rs.getLong( "id" ),
        getInstant( rs, "first_commit_at" ), getInstant( rs, "opened_at" ), getInstant( rs, "merged_at" ),
        getInstant( rs, "shipped_at" ) );

    private static final RowMapper< DoraReport.MergeMix > MIX_ROW = rs -> new DoraReport.MergeMix(
        rs.getInt( "reviewed" ), rs.getInt( "unreviewed" ), rs.getInt( "direct" ), rs.getInt( "flagged" ),
        rs.getInt( "flagged_open" ) );

    private final Jdbc jdbc;

    JdbcWorkFlow( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public List< ClosedItem > closedItems( final ItemFilter pinned, final Instant from, final Instant to )
            throws SQLException {
        final StringBuilder where = new StringBuilder();
        final List< Object > params = new ArrayList<>( List.of( Timestamp.from( from ), Timestamp.from( to ) ) );
        ItemQuery.conditions( pinned, false, where, params );
        return jdbc.query( CLOSED + where + " ORDER BY d.closed_at, i.id", SqlBinder.positional( params ),
            CLOSED_ROW );
    }

    @Override
    public List< DeploymentFact > deployments( final Long projectId, final Long productId, final Instant from,
                                               final Instant to ) throws SQLException {
        requireOne( projectId, productId );
        final StringBuilder sql = new StringBuilder( DEPLOYMENTS );
        final List< Object > params = new ArrayList<>( List.of( Timestamp.from( from ), Timestamp.from( to ),
            Timestamp.from( from ), Timestamp.from( to ) ) );
        if ( productId != null ) {
            sql.append( " AND d.product_id = ?" );
            params.add( productId );
        }
        if ( projectId != null ) {
            sql.append( PROJECT_CARRIES );
            params.add( projectId );
        }
        sql.append( " ORDER BY d.started_at, d.id" );
        return jdbc.query( sql.toString(), SqlBinder.positional( params ), DEPLOYMENT_ROW );
    }

    @Override
    public List< RecoveryEnd > recoveryEnds( final Set< Long > productIds, final Instant since ) throws SQLException {
        if ( productIds.isEmpty() ) {
            return List.of();
        }
        return jdbc.withConnection( conn -> jdbc.query( conn, RECOVERY_ENDS, ps -> {
            ps.setTimestamp( 1, Timestamp.from( since ) );
            ps.setArray( 2, conn.createArrayOf( "bigint", productIds.toArray( Long[]::new ) ) );
        }, END_ROW ) );
    }

    @Override
    public List< ChangeFact > shippedChanges( final Long projectId, final Long productId, final Instant from,
                                              final Instant to ) throws SQLException {
        requireOne( projectId, productId );
        final StringBuilder sql = new StringBuilder( SHIPPED );
        final List< Object > params = new ArrayList<>( List.of( Timestamp.from( to ),
            Timestamp.from( from.minus( LOOKBACK ) ) ) );
        scope( sql, params, projectId, productId );
        sql.append( SHIPPED_TAIL );
        params.add( Timestamp.from( from ) );
        params.add( Timestamp.from( to ) );
        return jdbc.query( sql.toString(), SqlBinder.positional( params ), CHANGE_ROW );
    }

    @Override
    public DoraReport.MergeMix merges( final Long projectId, final Long productId, final Instant from,
                                       final Instant to ) throws SQLException {
        requireOne( projectId, productId );
        final StringBuilder sql = new StringBuilder( MERGES );
        final List< Object > params = new ArrayList<>( List.of( Timestamp.from( from ), Timestamp.from( to ) ) );
        scope( sql, params, projectId, productId );
        return jdbc.queryOne( sql.toString(), SqlBinder.positional( params ), MIX_ROW )
            .orElseThrow( () -> new SQLException( "an aggregate returned no row" ) );
    }

    private static void scope( final StringBuilder sql, final List< Object > params, final Long projectId,
                               final Long productId ) {
        if ( projectId != null ) {
            sql.append( " AND i.project_id = ?" );
            params.add( projectId );
        }
        if ( productId != null ) {
            sql.append( " AND c.product_id = ?" );
            params.add( productId );
        }
    }

    private static void requireOne( final Long projectId, final Long productId ) {
        if ( projectId == null && productId == null ) {
            throw new IllegalArgumentException( "A figure is for a project or a product; neither was given." );
        }
    }

    @Override
    public List< OpenItem > openItems( final ItemFilter pinned ) throws SQLException {
        final StringBuilder where = new StringBuilder();
        final List< Object > params = new ArrayList<>();
        ItemQuery.conditions( pinned, false, where, params );
        return jdbc.query( OPEN + where + " ORDER BY i.id", SqlBinder.positional( params ), OPEN_ROW );
    }
}

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
import com.wikantik.jdbc.RowMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.wikantik.work.SqlValues.actorKindWire;
import static com.wikantik.work.SqlValues.instant;
import static com.wikantik.work.SqlValues.nullableLong;
import static com.wikantik.work.SqlValues.setNullableLong;

/** The PostgreSQL {@link WorkDeploymentStore}. */
final class JdbcWorkDeployments implements WorkDeploymentStore {

    private static final String SELECT = """
        SELECT d.id, d.product_id, d.environment, d.version, d.state, d.requested_by, d.requested_by_agent_id,
               d.approved_by, d.approved_by_agent_id, d.requested_at, d.approved_at, d.started_at, d.verified_at,
               d.closed_at, d.evidence,
               ARRAY( SELECT di.item_id FROM work_deployment_item di WHERE di.deployment_id = d.id
                       ORDER BY di.item_id ) AS item_ids
          FROM work_deployment d
        """;

    private static final String NEWEST_FIRST = " ORDER BY d.requested_at DESC, d.id DESC";

    private static final RowMapper< Deployment > ROW = rs -> new Deployment( rs.getLong( "id" ),
        rs.getLong( "product_id" ), rs.getString( "environment" ), rs.getString( "version" ),
        DeploymentState.fromWire( rs.getString( "state" ) ), rs.getString( "requested_by" ),
        nullableLong( rs, "requested_by_agent_id" ), rs.getString( "approved_by" ),
        nullableLong( rs, "approved_by_agent_id" ), instant( rs, "requested_at" ), instant( rs, "approved_at" ),
        instant( rs, "started_at" ), instant( rs, "verified_at" ), instant( rs, "closed_at" ),
        rs.getString( "evidence" ), ids( rs.getArray( "item_ids" ) ) );

    private static final RowMapper< DeploymentEvent > EVENT_ROW = rs -> new DeploymentEvent( rs.getLong( "id" ),
        rs.getLong( "deployment_id" ), instant( rs, "at" ), rs.getString( "actor" ),
        "agent".equals( rs.getString( "actor_kind" ) ) ? Actor.Kind.AGENT : Actor.Kind.HUMAN,
        nullableLong( rs, "agent_id" ),
        rs.getString( "from_state" ) == null ? null : DeploymentState.fromWire( rs.getString( "from_state" ) ),
        DeploymentState.fromWire( rs.getString( "to_state" ) ), rs.getString( "note" ) );

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkDeployments( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    private static List< Long > ids( final java.sql.Array array ) throws SQLException {
        final List< Long > out = new ArrayList<>();
        if ( array != null ) {
            for ( final Object id : ( Object[] ) array.getArray() ) {
                out.add( ( ( Number ) id ).longValue() );
            }
        }
        return out;
    }

    /** Whether a verified deployment carries the item, on the caller's connection (the item write holds its row). */
    static boolean shippedVerified( final Jdbc jdbc, final Connection conn, final long itemId ) throws SQLException {
        return jdbc.queryOne( conn, "SELECT 1 FROM work_deployment_item di JOIN work_deployment d "
                + "ON d.id = di.deployment_id WHERE di.item_id = ? AND d.state = 'verified' LIMIT 1",
            ps -> ps.setLong( 1, itemId ), rs -> Boolean.TRUE ).isPresent();
    }

    @Override
    public boolean shippedVerified( final long itemId ) throws SQLException {
        return jdbc.withConnection( conn -> shippedVerified( jdbc, conn, itemId ) );
    }

    @Override
    public Deployment request( final Product product, final String environment, final String version,
                               final List< Item > items, final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final long id = jdbc.queryOne( conn, "INSERT INTO work_deployment (product_id, environment, version, "
                    + "requested_by, requested_by_agent_id) VALUES (?,?,?,?,?) RETURNING id",
                ps -> {
                    ps.setLong( 1, product.id() );
                    ps.setString( 2, environment );
                    ps.setString( 3, version );
                    ps.setString( 4, by.login() );
                    setNullableLong( ps, 5, by.agentId() );
                }, rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new SQLException( "INSERT work_deployment returned no row" ) );
            jdbc.update( conn, "INSERT INTO work_deployment_item (deployment_id, item_id) "
                    + "SELECT ?, unnest( ?::bigint[] )",
                ps -> {
                    ps.setLong( 1, id );
                    ps.setArray( 2, conn.createArrayOf( "bigint", items.stream().map( Item::id ).toArray() ) );
                } );
            history( conn, id, by, null, DeploymentState.REQUESTED, null );
            for ( final Item item : items ) {
                itemEvent( conn, item.projectId(), item.id(), id, by, WorkEvent.DEPLOYMENT_REQUESTED, version,
                    environment );
            }
            return byId( conn, id );
        } );
    }

    @Override
    public Optional< Deployment > deployment( final long id ) throws SQLException {
        return jdbc.queryOne( SELECT + " WHERE d.id = ?", ps -> ps.setLong( 1, id ), ROW );
    }

    @Override
    public List< Deployment > forProduct( final long productId, final int limit ) throws SQLException {
        return jdbc.query( SELECT + " WHERE d.product_id = ?" + NEWEST_FIRST + " LIMIT ?", ps -> {
            ps.setLong( 1, productId );
            ps.setInt( 2, limit );
        }, ROW );
    }

    @Override
    public List< Deployment > carrying( final long itemId ) throws SQLException {
        return jdbc.query( SELECT + " WHERE d.id IN ( SELECT deployment_id FROM work_deployment_item WHERE item_id = ? )"
            + NEWEST_FIRST, ps -> ps.setLong( 1, itemId ), ROW );
    }

    @Override
    public List< Deployment > awaitingApproval() throws SQLException {
        return jdbc.query( SELECT + " WHERE d.state = 'requested' ORDER BY d.requested_at, d.id", ps -> { }, ROW );
    }

    @Override
    public List< DeploymentEvent > history( final long id ) throws SQLException {
        return jdbc.query( "SELECT id, deployment_id, at, actor, actor_kind, agent_id, from_state, to_state, note "
            + "FROM work_deployment_event WHERE deployment_id = ? ORDER BY at, id", ps -> ps.setLong( 1, id ),
            EVENT_ROW );
    }

    @Override
    public Deployment move( final long id, final DeploymentState target, final String evidence, final String note,
                            final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final Deployment locked = jdbc.queryOne( conn, SELECT + " WHERE d.id = ? FOR UPDATE OF d",
                ps -> ps.setLong( 1, id ), ROW )
                .orElseThrow( () -> new SQLException( "work_deployment " + id + " not found" ) );
            if ( !locked.state().mayBecome( target ) ) {
                throw new WorkConflictException( "A deployment that is " + words( locked.state() )
                    + " cannot become " + words( target ) + ".", WorkConflictException.BAD_TRANSITION );
            }
            update( conn, id, target, evidence, by );
            history( conn, id, by, locked.state(), target, note );
            final String itemEvent = target == DeploymentState.VERIFIED ? WorkEvent.SHIPPED
                : target == DeploymentState.ROLLED_BACK ? WorkEvent.ROLLED_BACK : null;
            if ( itemEvent != null ) {
                for ( final long[] carried : carried( conn, id ) ) {
                    itemEvent( conn, carried[ 1 ], carried[ 0 ], id, by, itemEvent, locked.version(),
                        locked.environment() );
                }
            }
            return byId( conn, id );
        } );
    }

    private static String words( final DeploymentState s ) {
        return s.wire().replace( '_', ' ' );
    }

    private void update( final Connection conn, final long id, final DeploymentState target, final String evidence,
                         final Actor by ) throws SQLException {
        final boolean approving = target == DeploymentState.APPROVED;
        final boolean finished = target == DeploymentState.VERIFIED || target == DeploymentState.FAILED;
        jdbc.update( conn, """
            UPDATE work_deployment SET state = ?,
                approved_by = CASE WHEN ? THEN ? ELSE approved_by END,
                approved_by_agent_id = CASE WHEN ? THEN ?::bigint ELSE approved_by_agent_id END,
                approved_at = CASE WHEN ? THEN NOW() ELSE approved_at END,
                started_at = CASE WHEN ? THEN NOW() ELSE started_at END,
                verified_at = CASE WHEN ? THEN NOW() ELSE verified_at END,
                evidence = CASE WHEN ? THEN ? ELSE evidence END,
                closed_at = CASE WHEN ? THEN NOW() ELSE closed_at END
             WHERE id = ?
            """, ps -> {
                ps.setString( 1, target.wire() );
                ps.setBoolean( 2, approving );
                ps.setString( 3, by.login() );
                ps.setBoolean( 4, approving );
                setNullableLong( ps, 5, by.agentId() );
                ps.setBoolean( 6, approving );
                ps.setBoolean( 7, target == DeploymentState.RUNNING );
                ps.setBoolean( 8, target == DeploymentState.VERIFIED );
                ps.setBoolean( 9, target == DeploymentState.VERIFIED );
                ps.setString( 10, evidence );
                ps.setBoolean( 11, finished );
                ps.setLong( 12, id );
            } );
    }

    /** The carried items as {@code {itemId, projectId}}. */
    private List< long[] > carried( final Connection conn, final long id ) throws SQLException {
        return jdbc.query( conn, "SELECT i.id, i.project_id FROM work_deployment_item di JOIN work_item i "
                + "ON i.id = di.item_id WHERE di.deployment_id = ? ORDER BY i.id", ps -> ps.setLong( 1, id ),
            rs -> new long[] { rs.getLong( 1 ), rs.getLong( 2 ) } );
    }

    private Deployment byId( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, SELECT + " WHERE d.id = ?", ps -> ps.setLong( 1, id ), ROW )
            .orElseThrow( () -> new SQLException( "work_deployment " + id + " not found" ) );
    }

    private void history( final Connection conn, final long id, final Actor by, final DeploymentState from,
                          final DeploymentState to, final String note ) throws SQLException {
        jdbc.update( conn, "INSERT INTO work_deployment_event (deployment_id, actor, actor_kind, agent_id, "
                + "from_state, to_state, note) VALUES (?,?,?,?,?,?,?)",
            ps -> {
                ps.setLong( 1, id );
                ps.setString( 2, by.login() );
                ps.setString( 3, actorKindWire( by.kind() ) );
                setNullableLong( ps, 4, by.agentId() );
                ps.setString( 5, from == null ? null : from.wire() );
                ps.setString( 6, to.wire() );
                ps.setString( 7, note );
            } );
    }

    /** One event on a carried item: {@code from_value} names the deployment, {@code to_value} the version. */
    private void itemEvent( final Connection conn, final long projectId, final long itemId, final long deploymentId,
                            final Actor by, final String type, final String version, final String environment )
            throws SQLException {
        catalog.appendEvent( conn, new WorkEvent( 0, projectId, itemId, null, null, by.login(), by.kind(),
            by.agentId(), type, "deployment:" + deploymentId, version, environment ) );
    }
}

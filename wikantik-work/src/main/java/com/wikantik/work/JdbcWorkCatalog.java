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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.wikantik.work.SqlValues.actorKindWire;
import static com.wikantik.work.SqlValues.instant;
import static com.wikantik.work.SqlValues.setNullableLong;
import static com.wikantik.work.SqlValues.timestamp;

/** The project, membership and product half of the PostgreSQL {@link WorkStore}; {@link JdbcWorkStore} adds the items. */
abstract class JdbcWorkCatalog extends JdbcWorkRegistry implements WorkCatalogStore {

    private static final String PROJECT_COLUMNS =
        "id, key, name, description, default_autonomy, created_at, archived_at";

    private static final String PRODUCT_COLUMNS =
        "id, key, name, repo_url, default_branch, wiki_cluster, autonomy_ceiling, deploy_gate, archived_at";

    private static final RowMapper< Project > PROJECT_ROW = rs -> new Project(
        rs.getLong( "id" ), rs.getString( "key" ), rs.getString( "name" ), rs.getString( "description" ),
        Autonomy.fromWire( rs.getString( "default_autonomy" ) ),
        instant( rs, "created_at" ), instant( rs, "archived_at" ) );

    private static final RowMapper< Member > MEMBER_ROW = rs -> new Member(
        rs.getLong( "project_id" ), rs.getString( "principal" ), rs.getString( "principal_kind" ),
        WorkRole.fromWire( rs.getString( "role" ) ) );

    private static final RowMapper< Product > PRODUCT_ROW = rs -> new Product(
        rs.getLong( "id" ), rs.getString( "key" ), rs.getString( "name" ), rs.getString( "repo_url" ),
        rs.getString( "default_branch" ), rs.getString( "wiki_cluster" ),
        Autonomy.fromWire( rs.getString( "autonomy_ceiling" ) ),
        DeployGate.fromWire( rs.getString( "deploy_gate" ) ), instant( rs, "archived_at" ) );

    private static final String ROLES_FOR_SQL = """
        SELECT role FROM work_member
        WHERE project_id = ?
          AND ( ( principal_kind = 'user' AND principal = ? )
             OR ( principal_kind = 'group' AND principal = ANY( ? ) )
             OR ( principal_kind = 'agent' AND principal = ? ) )
        """;

    private static final String VISIBLE_PROJECTS_SQL = "SELECT " + PROJECT_COLUMNS + """
         FROM work_project p
        WHERE EXISTS ( SELECT 1 FROM work_member m
                       WHERE m.project_id = p.id
                         AND ( ( m.principal_kind = 'user' AND m.principal = ? )
                            OR ( m.principal_kind = 'group' AND m.principal = ANY( ? ) )
                            OR ( m.principal_kind = 'agent' AND m.principal = ? ) ) )
        ORDER BY key
        """;

    /** The claim half of the store; a member losing access releases its claims inside the member transaction. */
    private final JdbcWorkClaims claims;
    private final JdbcWorkCredentials credentials;
    private final JdbcWorkMembers members;

    JdbcWorkCatalog( final Jdbc jdbc ) {
        super( jdbc );
        this.claims = new JdbcWorkClaims( jdbc, this );
        this.credentials = new JdbcWorkCredentials( jdbc );
        this.members = new JdbcWorkMembers( jdbc, this, claims );
    }

    /** The claim half of the store; declared here so {@link JdbcWorkStore} inherits it as {@code WorkStore.claims()}. */
    public WorkClaimStore claims() {
        return claims;
    }

    /** Agents' own credentials; declared here so {@link JdbcWorkStore} inherits it. */
    public WorkCredentialStore credentials() {
        return credentials;
    }

    // ---- projects

    @Override
    public Project createProject( final String key, final String name, final String description )
            throws SQLException {
        return jdbc.queryOne( "INSERT INTO work_project (key, name, description) VALUES (?,?,?) RETURNING "
                + PROJECT_COLUMNS,
            ps -> { ps.setString( 1, key ); ps.setString( 2, name ); ps.setString( 3, description ); },
            PROJECT_ROW ).orElseThrow( () -> new SQLException( "INSERT work_project returned no row" ) );
    }

    @Override
    public Optional< Project > project( final String key ) throws SQLException {
        return jdbc.queryOne( "SELECT " + PROJECT_COLUMNS + " FROM work_project WHERE key = ?",
            ps -> ps.setString( 1, key ), PROJECT_ROW );
    }

    @Override
    public List< Project > projects() throws SQLException {
        return jdbc.query( "SELECT " + PROJECT_COLUMNS + " FROM work_project ORDER BY key",
            ps -> { }, PROJECT_ROW );
    }

    /**
     * Applies the new name, description and default autonomy and writes one {@code settings} event per changed field
     * (the field in the note, old and new value in from/to), in one transaction. Nothing changed, nothing written.
     */
    @Override
    public void updateProject( final long id, final String name, final String description,
                               final Autonomy defaultAutonomy, final Actor by )
            throws SQLException {
        requireAttributed( by.kind(), by.agentId() );
        jdbc.inTransaction( conn -> {
            final Project current = jdbc.queryOne( conn, "SELECT " + PROJECT_COLUMNS
                    + " FROM work_project WHERE id = ? FOR UPDATE", ps -> ps.setLong( 1, id ), PROJECT_ROW )
                .orElseThrow( () -> new SQLException( "work_project " + id + " not found" ) );
            final List< String[] > changed = new ArrayList<>();
            addIfChanged( changed, "name", current.name(), name );
            addIfChanged( changed, "description", current.description(), description );
            addIfChanged( changed, "default_autonomy", current.defaultAutonomy().wire(), defaultAutonomy.wire() );
            if ( changed.isEmpty() ) {
                return null;
            }
            jdbc.update( conn, "UPDATE work_project SET name = ?, description = ?, default_autonomy = ? WHERE id = ?",
                ps -> {
                    ps.setString( 1, name );
                    ps.setString( 2, description );
                    ps.setString( 3, defaultAutonomy.wire() );
                    ps.setLong( 4, id );
                } );
            for ( final String[] c : changed ) {
                appendEvent( conn, projectEvent( id, by, WorkEvent.SETTINGS, c[ 1 ], c[ 2 ], c[ 0 ] ) );
            }
            return null;
        } );
    }

    private static void addIfChanged( final List< String[] > out, final String field, final String from,
                                      final String to ) {
        if ( !Objects.equals( from, to ) ) {
            out.add( new String[] { field, from, to } );
        }
    }

    /**
     * Sets {@code archived_at}, writes a {@code settings} event noted {@code archived} and releases every claim in the
     * project, in one transaction. Lock order: this takes the project row (its UPDATE) and then the items it ends; a
     * claim share-locks agent, member, then project, then its item ({@link JdbcWorkClaims}). Both take the project
     * before any item, so they cannot deadlock: a claim either lands first and is released here, or waits and finds
     * the project archived. Do not lock a project row after an item row anywhere.
     */
    @Override
    public int archiveProject( final long id, final Instant at, final Actor by )
            throws SQLException {
        requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final int rows = jdbc.update( conn, "UPDATE work_project SET archived_at = ? WHERE id = ? "
                    + "AND archived_at IS NULL",
                ps -> { ps.setTimestamp( 1, timestamp( at ) ); ps.setLong( 2, id ); } );
            if ( rows == 0 ) {
                return 0;
            }
            appendEvent( conn, projectEvent( id, by, WorkEvent.SETTINGS, null, at.toString(), "archived" ) );
            return claims.releaseInProject( conn, id, by );
        } );
    }

    // ---- membership

    /** The value a {@code member} event carries: {@code kind:principal:role}. */
    static String memberValue( final String principalKind, final String principal, final WorkRole role ) {
        return principalKind + ":" + principal + ":" + role.wire();
    }

    /**
     * Upserts the grant and records the {@code member} event in one transaction; an agent demoted to viewer loses
     * its claims in the project in the same transaction ({@link JdbcWorkMembers}).
     */
    @Override
    public int putMember( final long projectId, final String principal, final String principalKind,
                           final WorkRole role, final Actor by ) throws SQLException {
        return members.put( projectId, principal, principalKind, role, by, List.of() );
    }

    @Override
    public int putMember( final long projectId, final String principal, final String principalKind,
                           final WorkRole role, final Actor by, final java.util.Collection< Actor > sponsorsToRecheck )
            throws SQLException {
        return members.put( projectId, principal, principalKind, role, by, sponsorsToRecheck );
    }

    @Override
    public int removeMember( final long projectId, final String principal, final String principalKind,
                              final Actor by ) throws SQLException {
        return members.remove( projectId, principal, principalKind, by, List.of() );
    }

    @Override
    public int removeMember( final long projectId, final String principal, final String principalKind,
                              final Actor by, final java.util.Collection< Actor > sponsorsToRecheck )
            throws SQLException {
        return members.remove( projectId, principal, principalKind, by, sponsorsToRecheck );
    }

    static WorkEvent projectEvent( final long projectId, final Actor by, final String type,
                                           final String from, final String to, final String note ) {
        return new WorkEvent( 0, projectId, null, null, null, by.login(), by.kind(), by.agentId(), type, from, to,
            note );
    }

    static final String ONE_TARGET = "Assign a person or a queue, not both.";

    /** An item is assigned to a person or a queue, never both; refused before any SQL. */
    static void requireOneTarget( final Item item ) {
        if ( item.assignee() != null && item.queue() != null ) {
            throw new WorkValidationException( "queue", ONE_TARGET );
        }
    }

    /** An agent write without the agent's registry id is a programming error: refuse it, never record it. */
    static void requireAttributed( final Actor.Kind kind, final Long agentId ) {
        if ( kind == Actor.Kind.AGENT && agentId == null ) {
            throw new IllegalArgumentException( "an agent write must carry its work_agent id" );
        }
        if ( kind == Actor.Kind.HUMAN && agentId != null ) {
            throw new IllegalArgumentException( "a person's write carries no work_agent id" );
        }
    }

    /** Appends one event on the caller's connection; the event's id, itemKey and timestamp are assigned by the store. */
    void appendEvent( final Connection conn, final WorkEvent e ) throws SQLException {
        requireAttributed( e.actorKind(), e.agentId() );
        jdbc.update( conn, "INSERT INTO work_event (project_id, item_id, actor, actor_kind, agent_id, type, "
                + "from_value, to_value, note) VALUES (?,?,?,?,?,?,?,?,?)",
            ps -> {
                ps.setLong( 1, e.projectId() );
                setNullableLong( ps, 2, e.itemId() );
                ps.setString( 3, e.actor() );
                ps.setString( 4, actorKindWire( e.actorKind() ) );
                setNullableLong( ps, 5, e.agentId() );
                ps.setString( 6, e.type() );
                ps.setString( 7, e.fromValue() );
                ps.setString( 8, e.toValue() );
                ps.setString( 9, e.note() );
            } );
    }

    @Override
    public List< Member > members( final long projectId ) throws SQLException {
        return jdbc.query( "SELECT project_id, principal_kind, principal, role FROM work_member "
                + "WHERE project_id = ? ORDER BY principal_kind, principal",
            ps -> ps.setLong( 1, projectId ), MEMBER_ROW );
    }

    @Override
    public Optional< WorkRole > roleFor( final long projectId, final Actor actor ) throws SQLException {
        return jdbc.withConnection( conn -> roleOn( conn, projectId, actor ) );
    }

    /** {@link #roleFor} on the caller's connection: the role as this transaction sees the member rows. */
    Optional< WorkRole > roleOn( final Connection conn, final long projectId, final Actor actor )
            throws SQLException {
        final String[] groups = JdbcWorkMembers.groupArray( actor );
        final String agent = JdbcWorkMembers.agentPrincipal( actor );
        final List< WorkRole > roles = jdbc.query( conn, ROLES_FOR_SQL, ps -> {
            ps.setLong( 1, projectId );
            ps.setString( 2, JdbcWorkMembers.userPrincipal( actor ) );
            ps.setArray( 3, conn.createArrayOf( "text", groups ) );
            ps.setString( 4, agent );
        }, rs -> WorkRole.fromWire( rs.getString( 1 ) ) );
        return Optional.ofNullable( WorkRole.highest( roles ) );
    }

    @Override
    public List< Project > visibleProjects( final Actor actor ) throws SQLException {
        if ( actor.instanceAdmin() ) {
            return projects();
        }
        final String[] groups = JdbcWorkMembers.groupArray( actor );
        final String agent = JdbcWorkMembers.agentPrincipal( actor );
        return jdbc.withConnection( conn -> jdbc.query( conn, VISIBLE_PROJECTS_SQL, ps -> {
            ps.setString( 1, JdbcWorkMembers.userPrincipal( actor ) );
            ps.setArray( 2, conn.createArrayOf( "text", groups ) );
            ps.setString( 3, agent );
        }, PROJECT_ROW ) );
    }

    // ---- products

    @Override
    public Product createProduct( final Product d ) throws SQLException {
        return jdbc.queryOne( "INSERT INTO work_product (key, name, repo_url, default_branch, wiki_cluster, "
                + "autonomy_ceiling, deploy_gate) VALUES (?,?,?,?,?,?,?) RETURNING " + PRODUCT_COLUMNS,
            ps -> {
                ps.setString( 1, d.key() );
                ps.setString( 2, d.name() );
                ps.setString( 3, d.repoUrl() );
                ps.setString( 4, d.defaultBranch() );
                ps.setString( 5, d.wikiCluster() );
                ps.setString( 6, d.autonomyCeiling().wire() );
                ps.setString( 7, d.deployGate().wire() );
            }, PRODUCT_ROW ).orElseThrow( () -> new SQLException( "INSERT work_product returned no row" ) );
    }

    @Override
    public Optional< Product > product( final String key ) throws SQLException {
        return jdbc.queryOne( "SELECT " + PRODUCT_COLUMNS + " FROM work_product WHERE key = ?",
            ps -> ps.setString( 1, key ), PRODUCT_ROW );
    }

    @Override
    public List< Product > products() throws SQLException {
        return new ArrayList<>( jdbc.query( "SELECT " + PRODUCT_COLUMNS + " FROM work_product ORDER BY key",
            ps -> { }, PRODUCT_ROW ) );
    }

    @Override
    public void updateProduct( final Product p ) throws SQLException {
        jdbc.update( "UPDATE work_product SET name = ?, repo_url = ?, default_branch = ?, wiki_cluster = ?, "
                + "autonomy_ceiling = ?, deploy_gate = ? WHERE id = ?",
            ps -> {
                ps.setString( 1, p.name() );
                ps.setString( 2, p.repoUrl() );
                ps.setString( 3, p.defaultBranch() );
                ps.setString( 4, p.wikiCluster() );
                ps.setString( 5, p.autonomyCeiling().wire() );
                ps.setString( 6, p.deployGate().wire() );
                ps.setLong( 7, p.id() );
            } );
    }

    @Override
    public void archiveProduct( final long id, final Instant at ) throws SQLException {
        jdbc.update( "UPDATE work_product SET archived_at = ? WHERE id = ?",
            ps -> { ps.setTimestamp( 1, timestamp( at ) ); ps.setLong( 2, id ); } );
    }
}

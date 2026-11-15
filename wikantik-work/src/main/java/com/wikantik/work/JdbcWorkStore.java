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

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** PostgreSQL implementation of {@link WorkStore}; all access goes through {@link Jdbc}. */
public class JdbcWorkStore implements WorkStore {

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

    private final Jdbc jdbc;

    public JdbcWorkStore( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    private static Instant instant( final ResultSet rs, final String column ) throws SQLException {
        final Timestamp ts = rs.getTimestamp( column );
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp( final Instant at ) {
        return Timestamp.from( at );
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

    @Override
    public void updateProject( final long id, final String name, final String description,
                               final Autonomy defaultAutonomy ) throws SQLException {
        jdbc.update( "UPDATE work_project SET name = ?, description = ?, default_autonomy = ? WHERE id = ?",
            ps -> {
                ps.setString( 1, name );
                ps.setString( 2, description );
                ps.setString( 3, defaultAutonomy.wire() );
                ps.setLong( 4, id );
            } );
    }

    @Override
    public void archiveProject( final long id, final Instant at ) throws SQLException {
        jdbc.update( "UPDATE work_project SET archived_at = ? WHERE id = ?",
            ps -> { ps.setTimestamp( 1, timestamp( at ) ); ps.setLong( 2, id ); } );
    }

    // ---- membership

    @Override
    public void putMember( final long projectId, final String principal, final String principalKind,
                           final WorkRole role ) throws SQLException {
        jdbc.update( """
            INSERT INTO work_member (project_id, principal_kind, principal, role) VALUES (?,?,?,?)
            ON CONFLICT (project_id, principal_kind, principal) DO UPDATE SET role = EXCLUDED.role
            """,
            ps -> {
                ps.setLong( 1, projectId );
                ps.setString( 2, principalKind );
                ps.setString( 3, principal );
                ps.setString( 4, role.wire() );
            } );
    }

    @Override
    public void removeMember( final long projectId, final String principal, final String principalKind )
            throws SQLException {
        jdbc.update( "DELETE FROM work_member WHERE project_id = ? AND principal_kind = ? AND principal = ?",
            ps -> { ps.setLong( 1, projectId ); ps.setString( 2, principalKind ); ps.setString( 3, principal ); } );
    }

    @Override
    public List< Member > members( final long projectId ) throws SQLException {
        return jdbc.query( "SELECT project_id, principal_kind, principal, role FROM work_member "
                + "WHERE project_id = ? ORDER BY principal_kind, principal",
            ps -> ps.setLong( 1, projectId ), MEMBER_ROW );
    }

    /** The agent id as the text stored in an {@code agent} member row, or a never-matching empty string. */
    private static String agentPrincipal( final Actor actor ) {
        return actor.kind() == Actor.Kind.AGENT && actor.agentId() != null ? String.valueOf( actor.agentId() ) : "";
    }

    private static String[] groupArray( final Actor actor ) {
        return actor.groups() == null ? new String[ 0 ] : actor.groups().toArray( new String[ 0 ] );
    }

    @Override
    public Optional< WorkRole > roleFor( final long projectId, final Actor actor ) throws SQLException {
        final String[] groups = groupArray( actor );
        final String agent = agentPrincipal( actor );
        final List< WorkRole > roles = jdbc.withConnection( conn -> jdbc.query( conn, ROLES_FOR_SQL, ps -> {
            ps.setLong( 1, projectId );
            ps.setString( 2, actor.login() );
            ps.setArray( 3, conn.createArrayOf( "text", groups ) );
            ps.setString( 4, agent );
        }, rs -> WorkRole.fromWire( rs.getString( 1 ) ) ) );
        return Optional.ofNullable( WorkRole.highest( roles ) );
    }

    @Override
    public List< Project > visibleProjects( final Actor actor ) throws SQLException {
        if ( actor.admin() ) {
            return projects();
        }
        final String[] groups = groupArray( actor );
        final String agent = agentPrincipal( actor );
        return jdbc.withConnection( conn -> jdbc.query( conn, VISIBLE_PROJECTS_SQL, ps -> {
            ps.setString( 1, actor.login() );
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

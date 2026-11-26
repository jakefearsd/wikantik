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
import java.util.Collection;
import java.util.Optional;

/**
 * The membership writes of the catalog store, split out to keep {@link JdbcWorkCatalog} under the complexity
 * limits. Each is one transaction: the grant, its {@code member} event, and the end of every claim the change
 * takes the ground from: an agent's own, when its row goes or drops to viewer, and those of the agents sponsored
 * by anyone in {@code sponsorsToRecheck} whose standing in the project is now below {@code member}.
 */
final class JdbcWorkMembers {

    /** The principal kind of a person's grant. */
    private static final String USER = "user";

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;
    private final JdbcWorkClaims claims;

    JdbcWorkMembers( final Jdbc jdbc, final JdbcWorkCatalog catalog, final JdbcWorkClaims claims ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.claims = claims;
    }

    /** Upserts the grant; setting the role a principal already has writes nothing. Returns the claims released. */
    int put( final long projectId, final String principal, final String principalKind, final WorkRole role,
             final Actor by, final Collection< Actor > sponsorsToRecheck ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        if ( principal == null || principal.isBlank() ) {
            throw new WorkValidationException( "principal", "A member needs a name." );
        }
        if ( "agent".equals( principalKind ) ) {
            WorkInputs.requireAgentRole( role );
        }
        return jdbc.inTransaction( conn -> {
            final WorkRole before = jdbc.queryOne( conn, "SELECT role FROM work_member WHERE project_id = ? "
                    + "AND principal_kind = ? AND principal = ? FOR UPDATE",
                ps -> { ps.setLong( 1, projectId ); ps.setString( 2, principalKind ); ps.setString( 3, principal ); },
                rs -> WorkRole.fromWire( rs.getString( 1 ) ) ).orElse( null );
            if ( before == role ) {
                return 0;
            }
            jdbc.update( conn, """
                INSERT INTO work_member (project_id, principal_kind, principal, role) VALUES (?,?,?,?)
                ON CONFLICT (project_id, principal_kind, principal) DO UPDATE SET role = EXCLUDED.role
                """,
                ps -> {
                    ps.setLong( 1, projectId );
                    ps.setString( 2, principalKind );
                    ps.setString( 3, principal );
                    ps.setString( 4, role.wire() );
                } );
            catalog.appendEvent( conn, JdbcWorkCatalog.projectEvent( projectId, by, WorkEvent.MEMBER,
                before == null ? null : JdbcWorkCatalog.memberValue( principalKind, principal, before ),
                JdbcWorkCatalog.memberValue( principalKind, principal, role ), null ) );
            final int own = role == WorkRole.VIEWER && "agent".equals( principalKind )
                ? claims.releaseOnAccessLoss( conn, projectId, principal, by, JdbcWorkClaims.ACCESS_REMOVED ) : 0;
            return own + claims.releaseForSponsors( conn, projectId, sponsorsToRecheck, by );
        } );
    }

    /**
     * Makes a new project's creator its first lead, on the creation transaction's connection: the grant and its
     * {@code member} event. The project is new, so there is no earlier grant and no claim to end.
     */
    void addCreator( final Connection conn, final long projectId, final String login, final Actor by )
            throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        jdbc.update( conn, "INSERT INTO work_member (project_id, principal_kind, principal, role) VALUES (?,?,?,?)",
            ps -> {
                ps.setLong( 1, projectId );
                ps.setString( 2, USER );
                ps.setString( 3, login );
                ps.setString( 4, WorkRole.LEAD.wire() );
            } );
        catalog.appendEvent( conn, JdbcWorkCatalog.projectEvent( projectId, by, WorkEvent.MEMBER, null,
            JdbcWorkCatalog.memberValue( USER, login, WorkRole.LEAD ), null ) );
    }

    /**
     * Deletes the grant (membership is state; the {@code member} event, from the old grant to null, is the
     * history). Removing someone who is not a member writes nothing. Returns the claims released.
     */
    int remove( final long projectId, final String principal, final String principalKind, final Actor by,
                final Collection< Actor > sponsorsToRecheck ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final Optional< WorkRole > removed = jdbc.queryOne( conn, "DELETE FROM work_member WHERE project_id = ? "
                    + "AND principal_kind = ? AND principal = ? RETURNING role",
                ps -> { ps.setLong( 1, projectId ); ps.setString( 2, principalKind ); ps.setString( 3, principal ); },
                rs -> WorkRole.fromWire( rs.getString( 1 ) ) );
            if ( removed.isPresent() ) {
                catalog.appendEvent( conn, JdbcWorkCatalog.projectEvent( projectId, by, WorkEvent.MEMBER,
                    JdbcWorkCatalog.memberValue( principalKind, principal, removed.get() ), null, null ) );
                final int own = "agent".equals( principalKind )
                    ? claims.releaseOnAccessLoss( conn, projectId, principal, by, JdbcWorkClaims.ACCESS_REMOVED ) : 0;
                return own + claims.releaseForSponsors( conn, projectId, sponsorsToRecheck, by );
            }
            return 0;
        } );
    }

    /**
     * The agent id as the text stored in an {@code agent} member row, or {@code null}, which binds as SQL NULL and so
     * equals nothing. Never an empty string: a stored blank principal (the table refuses one since V095) would match it.
     */
    static String agentPrincipal( final Actor actor ) {
        return actor.kind() == Actor.Kind.AGENT && actor.agentId() != null ? String.valueOf( actor.agentId() ) : null;
    }

    /** Groups only count for people: an agent never inherits a person's or a group's access. Blank names are dropped. */
    static String[] groupArray( final Actor actor ) {
        return actor.kind() != Actor.Kind.HUMAN || actor.groups() == null
            ? new String[ 0 ] : actor.groups().stream().filter( g -> g != null && !g.isBlank() ).toArray( String[]::new );
    }

    /** The login as matched against {@code user} rows: people only; {@code null} (matches nothing) otherwise or when blank. */
    static String userPrincipal( final Actor actor ) {
        return actor.kind() == Actor.Kind.HUMAN && actor.login() != null && !actor.login().isBlank()
            ? actor.login() : null;
    }
}

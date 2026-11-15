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
import com.wikantik.jdbc.SqlBinder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    static final String ITEM_SELECT = """
        SELECT i.id, i.project_id, p.key || '-' || i.seq AS item_key, i.level, i.parent_id, i.product_id, i.kind,
               i.title, i.body, i.status, i.priority, i.rank, i.due_on, i.assignee, i.reporter, i.autonomy,
               i.done_means, i.allow_direct, i.version, i.created_at, i.updated_at, i.closed_at
          FROM work_item i JOIN work_project p ON p.id = i.project_id
        """;

    static final RowMapper< Item > ITEM_ROW = rs -> new Item(
        rs.getLong( "id" ), rs.getLong( "project_id" ), rs.getString( "item_key" ),
        WorkLevel.fromWire( rs.getString( "level" ) ), nullableLong( rs, "parent_id" ),
        nullableLong( rs, "product_id" ), WorkKind.fromWire( rs.getString( "kind" ) ), rs.getString( "title" ),
        rs.getString( "body" ), WorkStatus.fromWire( rs.getString( "status" ) ), rs.getInt( "priority" ),
        rs.getDouble( "rank" ), localDate( rs, "due_on" ), rs.getString( "assignee" ), rs.getString( "reporter" ),
        rs.getString( "autonomy" ) == null ? null : Autonomy.fromWire( rs.getString( "autonomy" ) ),
        rs.getString( "done_means" ) == null ? null : DoneMeans.fromWire( rs.getString( "done_means" ) ),
        (Boolean) rs.getObject( "allow_direct" ), rs.getInt( "version" ),
        instant( rs, "created_at" ), instant( rs, "updated_at" ), instant( rs, "closed_at" ) );

    private static final String EVENT_SELECT = """
        SELECT e.id, e.project_id, e.item_id,
               CASE WHEN i.id IS NULL THEN NULL ELSE p.key || '-' || i.seq END AS item_key,
               e.at, e.actor, e.actor_kind, e.agent_id, e.type, e.from_value, e.to_value, e.note
          FROM work_event e
          JOIN work_project p ON p.id = e.project_id
          LEFT JOIN work_item i ON i.id = e.item_id
        """;

    private static final RowMapper< WorkEvent > EVENT_ROW = rs -> new WorkEvent(
        rs.getLong( "id" ), rs.getLong( "project_id" ), nullableLong( rs, "item_id" ), rs.getString( "item_key" ),
        instant( rs, "at" ), rs.getString( "actor" ), "agent".equals( rs.getString( "actor_kind" ) )
            ? Actor.Kind.AGENT : Actor.Kind.HUMAN,
        nullableLong( rs, "agent_id" ), rs.getString( "type" ), rs.getString( "from_value" ),
        rs.getString( "to_value" ), rs.getString( "note" ) );

    private static final Pattern ITEM_KEY = Pattern.compile( "^([A-Za-z][A-Za-z0-9]{1,9})-(\\d{1,9})$" );

    private static final int DEFAULT_ITEM_LIMIT = 200;
    private static final int CLOSED_COLUMN_CAP = 50;
    private static final double RANK_STEP = 1024.0;

    private final Jdbc jdbc;
    private final JdbcWorkDiscussion discussion;

    public JdbcWorkStore( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
        this.discussion = new JdbcWorkDiscussion( jdbc, this );
    }

    private static Instant instant( final ResultSet rs, final String column ) throws SQLException {
        final Timestamp ts = rs.getTimestamp( column );
        return ts == null ? null : ts.toInstant();
    }

    private static Long nullableLong( final ResultSet rs, final String column ) throws SQLException {
        final long v = rs.getLong( column );
        return rs.wasNull() ? null : v;
    }

    private static LocalDate localDate( final ResultSet rs, final String column ) throws SQLException {
        final Date d = rs.getDate( column );
        return d == null ? null : d.toLocalDate();
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

    /** Groups only count for people: an agent never inherits a person's or a group's access. */
    private static String[] groupArray( final Actor actor ) {
        return actor.kind() != Actor.Kind.HUMAN || actor.groups() == null
            ? new String[ 0 ] : actor.groups().toArray( new String[ 0 ] );
    }

    /** The login as matched against {@code user} rows: people only, a never-matching empty string for agents. */
    private static String userPrincipal( final Actor actor ) {
        return actor.kind() == Actor.Kind.HUMAN && actor.login() != null ? actor.login() : "";
    }

    @Override
    public Optional< WorkRole > roleFor( final long projectId, final Actor actor ) throws SQLException {
        final String[] groups = groupArray( actor );
        final String agent = agentPrincipal( actor );
        final List< WorkRole > roles = jdbc.withConnection( conn -> jdbc.query( conn, ROLES_FOR_SQL, ps -> {
            ps.setLong( 1, projectId );
            ps.setString( 2, userPrincipal( actor ) );
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
            ps.setString( 1, userPrincipal( actor ) );
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

    // ---- items

    private static boolean isClosed( final WorkStatus s ) {
        return s == WorkStatus.DONE || s == WorkStatus.CANCELLED;
    }

    private static String autonomyWire( final Autonomy a ) {
        return a == null ? null : a.wire();
    }

    private static String doneMeansWire( final DoneMeans d ) {
        return d == null ? null : d.wire();
    }

    private static void setNullableLong( final java.sql.PreparedStatement ps, final int idx, final Long v )
            throws SQLException {
        if ( v == null ) {
            ps.setNull( idx, java.sql.Types.BIGINT );
        } else {
            ps.setLong( idx, v );
        }
    }

    private static void setNullableBoolean( final java.sql.PreparedStatement ps, final int idx, final Boolean v )
            throws SQLException {
        if ( v == null ) {
            ps.setNull( idx, java.sql.Types.BOOLEAN );
        } else {
            ps.setBoolean( idx, v );
        }
    }

    private static void setNullableDate( final java.sql.PreparedStatement ps, final int idx, final LocalDate v )
            throws SQLException {
        if ( v == null ) {
            ps.setNull( idx, java.sql.Types.DATE );
        } else {
            ps.setDate( idx, Date.valueOf( v ) );
        }
    }

    private static String actorKindWire( final Actor.Kind kind ) {
        return kind == Actor.Kind.AGENT ? "agent" : "user";
    }

    @Override
    public Item createItem( final Project project, final Item draft, final String actor, final Actor.Kind kind )
            throws SQLException {
        final WorkStatus status = draft.status() == null ? WorkStatus.DRAFT : draft.status();
        final String body = draft.body() == null ? "" : draft.body();
        return jdbc.inTransaction( conn -> {
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
                    priority, rank, due_on, assignee, reporter, autonomy, done_means, allow_direct, closed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CASE WHEN ? THEN NOW() END)
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
                    ps.setString( 14, actor );
                    ps.setString( 15, autonomyWire( draft.autonomy() ) );
                    ps.setString( 16, doneMeansWire( draft.doneMeans() ) );
                    setNullableBoolean( ps, 17, draft.allowDirect() );
                    ps.setBoolean( 18, isClosed( status ) );
                }, rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new SQLException( "INSERT work_item returned no row" ) );
            insertRevision( conn, id, 1, draft.title(), body, actor );
            appendEvent( conn, new WorkEvent( 0, project.id(), id, null, null, actor, kind, null,
                WorkEvent.CREATED, null, null, null ) );
            return itemById( conn, id );
        } );
    }

    Item itemById( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ), ITEM_ROW )
            .orElseThrow( () -> new SQLException( "work_item " + id + " not found" ) );
    }

    private void insertRevision( final Connection conn, final long itemId, final int version, final String title,
                                 final String body, final String editedBy ) throws SQLException {
        jdbc.update( conn, "INSERT INTO work_item_revision (item_id, version, title, body, edited_by) "
                + "VALUES (?,?,?,?,?)",
            ps -> {
                ps.setLong( 1, itemId );
                ps.setInt( 2, version );
                ps.setString( 3, title );
                ps.setString( 4, body );
                ps.setString( 5, editedBy );
            } );
    }

    /** Appends one event on the caller's connection; the event's id, itemKey and timestamp are assigned by the store. */
    void appendEvent( final Connection conn, final WorkEvent e ) throws SQLException {
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
    public Optional< Item > itemById( final long id ) throws SQLException {
        return jdbc.queryOne( ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ), ITEM_ROW );
    }

    @Override
    public double maxRank( final long projectId, final WorkStatus status ) throws SQLException {
        return jdbc.queryOne( "SELECT COALESCE( MAX( rank ), 0 ) FROM work_item WHERE project_id = ? AND status = ?",
            ps -> { ps.setLong( 1, projectId ); ps.setString( 2, status.wire() ); },
            rs -> rs.getDouble( 1 ) ).orElse( 0.0 );
    }

    @Override
    public Optional< Item > item( final String key ) throws SQLException {
        if ( key == null ) {
            return Optional.empty();
        }
        final Matcher m = ITEM_KEY.matcher( key.trim() );
        if ( !m.matches() ) {
            return Optional.empty();
        }
        return jdbc.queryOne( ITEM_SELECT + " WHERE p.key = ? AND i.seq = ?", ps -> {
            ps.setString( 1, m.group( 1 ).toUpperCase( java.util.Locale.ROOT ) );
            ps.setInt( 2, Integer.parseInt( m.group( 2 ) ) );
        }, ITEM_ROW );
    }

    @Override
    public Item updateItem( final Item current, final Item next, final String actor, final Actor.Kind kind,
                            final String note ) throws SQLException {
        final boolean textChanged = !Objects.equals( current.title(), next.title() )
            || !Objects.equals( current.body(), next.body() );
        final boolean closing = !isClosed( current.status() ) && isClosed( next.status() );
        final boolean reopening = isClosed( current.status() ) && !isClosed( next.status() );
        final int version = current.version() + 1;
        final String body = next.body() == null ? "" : next.body();
        return jdbc.inTransaction( conn -> {
            final int rows = jdbc.update( conn, """
                UPDATE work_item SET parent_id = ?, product_id = ?, kind = ?, title = ?, body = ?, status = ?,
                    priority = ?, rank = ?, due_on = ?, assignee = ?, autonomy = ?, done_means = ?,
                    allow_direct = ?, version = version + 1, updated_at = NOW(),
                    closed_at = CASE WHEN ? THEN NOW() WHEN ? THEN NULL ELSE closed_at END
                 WHERE id = ? AND version = ?
                """,
                ps -> {
                    setNullableLong( ps, 1, next.parentId() );
                    setNullableLong( ps, 2, next.productId() );
                    ps.setString( 3, next.kind().wire() );
                    ps.setString( 4, next.title() );
                    ps.setString( 5, body );
                    ps.setString( 6, next.status().wire() );
                    ps.setInt( 7, next.priority() );
                    ps.setDouble( 8, next.rank() );
                    setNullableDate( ps, 9, next.dueOn() );
                    ps.setString( 10, next.assignee() );
                    ps.setString( 11, autonomyWire( next.autonomy() ) );
                    ps.setString( 12, doneMeansWire( next.doneMeans() ) );
                    setNullableBoolean( ps, 13, next.allowDirect() );
                    ps.setBoolean( 14, closing );
                    ps.setBoolean( 15, reopening );
                    ps.setLong( 16, current.id() );
                    ps.setInt( 17, current.version() );
                } );
            if ( rows == 0 ) {
                throw new WorkConflictException( "Item " + current.key() + " changed since version "
                    + current.version() );
            }
            if ( textChanged ) {
                insertRevision( conn, current.id(), version, next.title(), body, actor );
            }
            for ( final WorkEvent e : changeEvents( current, next, actor, kind, note ) ) {
                appendEvent( conn, e );
            }
            return itemById( conn, current.id() );
        } );
    }

    /**
     * The events for the differences between two states of one item. A status and an assignee change together,
     * with a non-blank note, collapse into one {@code handoff} event whose values are {@code status|assignee}.
     * A {@code field} event names the field in its note.
     */
    private static List< WorkEvent > changeEvents( final Item cur, final Item nxt, final String actor,
                                                   final Actor.Kind kind, final String note ) {
        final List< WorkEvent > out = new ArrayList<>();
        final String blankSafeNote = note == null || note.isBlank() ? null : note;
        if ( !Objects.equals( cur.title(), nxt.title() ) || !Objects.equals( cur.body(), nxt.body() ) ) {
            out.add( event( cur, actor, kind, WorkEvent.EDITED, null, null, null ) );
        }
        final boolean statusChanged = cur.status() != nxt.status();
        final boolean assigneeChanged = !Objects.equals( cur.assignee(), nxt.assignee() );
        if ( statusChanged && assigneeChanged && blankSafeNote != null ) {
            out.add( event( cur, actor, kind, WorkEvent.HANDOFF,
                cur.status().wire() + "|" + Objects.toString( cur.assignee(), "" ),
                nxt.status().wire() + "|" + Objects.toString( nxt.assignee(), "" ), blankSafeNote ) );
        } else {
            if ( statusChanged ) {
                out.add( event( cur, actor, kind, WorkEvent.STATUS, cur.status().wire(), nxt.status().wire(),
                    blankSafeNote ) );
            }
            if ( assigneeChanged ) {
                out.add( event( cur, actor, kind, WorkEvent.ASSIGNED, cur.assignee(), nxt.assignee(), blankSafeNote ) );
            }
        }
        fieldEvent( out, cur, actor, kind, "priority", String.valueOf( cur.priority() ), String.valueOf( nxt.priority() ) );
        fieldEvent( out, cur, actor, kind, "due_on", text( cur.dueOn() ), text( nxt.dueOn() ) );
        fieldEvent( out, cur, actor, kind, "parent_id", text( cur.parentId() ), text( nxt.parentId() ) );
        fieldEvent( out, cur, actor, kind, "product_id", text( cur.productId() ), text( nxt.productId() ) );
        fieldEvent( out, cur, actor, kind, "kind", cur.kind().wire(), nxt.kind().wire() );
        fieldEvent( out, cur, actor, kind, "autonomy", autonomyWire( cur.autonomy() ), autonomyWire( nxt.autonomy() ) );
        fieldEvent( out, cur, actor, kind, "done_means", doneMeansWire( cur.doneMeans() ),
            doneMeansWire( nxt.doneMeans() ) );
        fieldEvent( out, cur, actor, kind, "allow_direct", text( cur.allowDirect() ), text( nxt.allowDirect() ) );
        return out;
    }

    private static String text( final Object o ) {
        return o == null ? null : o.toString();
    }

    private static void fieldEvent( final List< WorkEvent > out, final Item cur, final String actor,
                                    final Actor.Kind kind, final String field, final String from, final String to ) {
        if ( !Objects.equals( from, to ) ) {
            out.add( event( cur, actor, kind, WorkEvent.FIELD, from, to, field ) );
        }
    }

    private static WorkEvent event( final Item item, final String actor, final Actor.Kind kind, final String type,
                                    final String from, final String to, final String note ) {
        return new WorkEvent( 0, item.projectId(), item.id(), item.key(), null, actor, kind, null, type, from, to, note );
    }

    // ---- queries

    /** Appends the WHERE conditions of {@code f} (and their parameters) to the builders. */
    private static void conditions( final ItemFilter f, final boolean withStatuses, final StringBuilder where,
                                    final List< Object > params ) {
        if ( f.projectId() != null ) {
            where.append( " AND i.project_id = ?" );
            params.add( f.projectId() );
        }
        if ( withStatuses && f.statuses() != null && !f.statuses().isEmpty() ) {
            where.append( " AND i.status IN (" + String.join( ",", java.util.Collections.nCopies(
                f.statuses().size(), "?" ) ) + ")" );
            f.statuses().stream().map( WorkStatus::wire ).sorted().forEach( params::add );
        }
        if ( f.assignee() != null ) {
            where.append( " AND i.assignee = ?" );
            params.add( f.assignee() );
        }
        if ( f.productId() != null ) {
            where.append( " AND i.product_id = ?" );
            params.add( f.productId() );
        }
        if ( f.level() != null ) {
            where.append( " AND i.level = ?" );
            params.add( f.level().wire() );
        }
        if ( f.parentId() != null ) {
            where.append( " AND i.parent_id = ?" );
            params.add( f.parentId() );
        }
        if ( f.text() != null && !f.text().isBlank() ) {
            where.append( " AND ( i.title ILIKE ? ESCAPE '\\' OR i.body ILIKE ? ESCAPE '\\' )" );
            final String like = "%" + f.text().replace( "\\", "\\\\" ).replace( "%", "\\%" )
                .replace( "_", "\\_" ) + "%";
            params.add( like );
            params.add( like );
        }
        if ( f.dueBefore() != null ) {
            where.append( " AND i.due_on < ?" );
            params.add( Date.valueOf( f.dueBefore() ) );
        }
    }

    @Override
    public List< Item > items( final ItemFilter f ) throws SQLException {
        final StringBuilder sql = new StringBuilder( ITEM_SELECT ).append( " WHERE TRUE" );
        final List< Object > params = new ArrayList<>();
        conditions( f, true, sql, params );
        sql.append( " ORDER BY p.key, i.seq LIMIT ?" );
        params.add( f.limit() > 0 ? f.limit() : DEFAULT_ITEM_LIMIT );
        return jdbc.query( sql.toString(), SqlBinder.positional( params ), ITEM_ROW );
    }

    @Override
    public List< BoardColumn > board( final long projectId, final ItemFilter f ) throws SQLException {
        final ItemFilter scoped = new ItemFilter( projectId, null, f.assignee(), f.productId(), f.level(),
            f.parentId(), f.text(), f.dueBefore(), 0 );
        final List< BoardColumn > columns = new ArrayList<>();
        for ( final WorkStatus status : WorkStatus.values() ) {
            final StringBuilder where = new StringBuilder( " WHERE i.status = ?" );
            final List< Object > params = new ArrayList<>();
            params.add( status.wire() );
            conditions( scoped, false, where, params );
            final int total = jdbc.queryOne( "SELECT COUNT(*) FROM work_item i" + where,
                SqlBinder.positional( params ), rs -> rs.getInt( 1 ) ).orElse( 0 );
            final boolean closed = isClosed( status );
            final String order = closed ? " ORDER BY i.closed_at DESC NULLS LAST, i.id DESC LIMIT " + CLOSED_COLUMN_CAP
                : " ORDER BY i.rank, i.id";
            columns.add( new BoardColumn( status, total, jdbc.query( ITEM_SELECT + where + order,
                SqlBinder.positional( params ), ITEM_ROW ) ) );
        }
        return columns;
    }

    @Override
    public List< ItemRevision > revisions( final long itemId ) throws SQLException {
        return jdbc.query( "SELECT item_id, version, title, body, edited_by, edited_at FROM work_item_revision "
                + "WHERE item_id = ? ORDER BY version",
            ps -> ps.setLong( 1, itemId ), rs -> new ItemRevision( rs.getLong( "item_id" ), rs.getInt( "version" ),
                rs.getString( "title" ), rs.getString( "body" ), rs.getString( "edited_by" ),
                instant( rs, "edited_at" ) ) );
    }

    @Override
    public List< WorkEvent > itemEvents( final long itemId ) throws SQLException {
        return jdbc.query( EVENT_SELECT + " WHERE e.item_id = ? ORDER BY e.at, e.id",
            ps -> ps.setLong( 1, itemId ), EVENT_ROW );
    }

    @Override
    public List< WorkEvent > projectEvents( final long projectId, final Instant before, final Long beforeId,
                                            final int limit ) throws SQLException {
        final Instant cutoff = before == null ? Instant.now().plusSeconds( 1 ) : before;
        final boolean compound = before != null && beforeId != null;
        final String cursor = compound ? "(e.at, e.id) < (?, ?)" : "e.at < ?";
        return jdbc.query( EVENT_SELECT + " WHERE e.project_id = ? AND " + cursor + " ORDER BY e.at DESC, e.id DESC LIMIT ?",
            ps -> {
                ps.setLong( 1, projectId );
                ps.setTimestamp( 2, timestamp( cutoff ) );
                int next = 3;
                if ( compound ) {
                    ps.setLong( next++, beforeId );
                }
                ps.setInt( next, limit > 0 ? limit : DEFAULT_ITEM_LIMIT );
            }, EVENT_ROW );
    }

    @Override
    public Set< Long > productIdsUnder( final long epicId ) throws SQLException {
        return new LinkedHashSet<>( jdbc.query( """
            WITH RECURSIVE descendants(id, product_id) AS (
                SELECT id, product_id FROM work_item WHERE parent_id = ?
                UNION
                SELECT c.id, c.product_id FROM work_item c JOIN descendants d ON c.parent_id = d.id )
            SELECT DISTINCT product_id FROM descendants WHERE product_id IS NOT NULL ORDER BY product_id
            """, ps -> ps.setLong( 1, epicId ), rs -> rs.getLong( 1 ) ) );
    }

    // ---- discussion (see JdbcWorkDiscussion)

    @Override
    public Comment addComment( final Item item, final String author, final Actor.Kind kind, final String body,
                               final List< String > mentioned ) throws SQLException {
        return discussion.addComment( item, author, kind, body, mentioned );
    }

    @Override
    public Comment editComment( final long commentId, final String body, final String actor ) throws SQLException {
        return discussion.editComment( commentId, body, actor );
    }

    @Override
    public void removeComment( final long commentId, final String actor ) throws SQLException {
        discussion.removeComment( commentId, actor );
    }

    @Override
    public Optional< Comment > comment( final long id ) throws SQLException {
        return discussion.comment( id );
    }

    @Override
    public List< Comment > comments( final long itemId ) throws SQLException {
        return discussion.comments( itemId );
    }

    @Override
    public Link addLink( final Item item, final String kind, final String target, final String actor )
            throws SQLException {
        return discussion.addLink( item, kind, target, actor );
    }

    @Override
    public void removeLink( final long linkId, final String actor ) throws SQLException {
        discussion.removeLink( linkId, actor );
    }

    @Override
    public Optional< Link > link( final long id ) throws SQLException {
        return discussion.link( id );
    }

    @Override
    public List< Link > links( final long itemId ) throws SQLException {
        return discussion.links( itemId );
    }

    @Override
    public boolean isBlocked( final long itemId ) throws SQLException {
        return discussion.isBlocked( itemId );
    }

    @Override
    public java.util.Map< Long, ItemExtras > itemExtras( final long projectId,
                                                         final java.util.Collection< Long > itemIds )
            throws SQLException {
        return discussion.itemExtras( projectId, itemIds );
    }

    @Override
    public List< Item > mentioning( final String login, final Set< Long > projectIds ) throws SQLException {
        return discussion.mentioning( login, projectIds );
    }

    @Override
    public List< Item > assignedOpen( final String login, final Set< Long > projectIds ) throws SQLException {
        return discussion.assignedOpen( login, projectIds );
    }

    @Override
    public List< ItemCount > itemCounts() throws SQLException {
        return jdbc.query( "SELECT status, level, kind, COUNT(*) AS n FROM work_item GROUP BY status, level, kind",
            ps -> { }, rs -> new ItemCount( WorkStatus.fromWire( rs.getString( "status" ) ),
                WorkLevel.fromWire( rs.getString( "level" ) ), WorkKind.fromWire( rs.getString( "kind" ) ),
                rs.getLong( "n" ) ) );
    }
}

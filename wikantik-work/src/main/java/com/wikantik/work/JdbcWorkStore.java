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

import com.wikantik.jdbc.NullableColumns;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.RowMapper;
import com.wikantik.jdbc.SqlBinder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.work.SqlValues.isClosed;
import static com.wikantik.jdbc.NullableColumns.getLocalDate;
import static com.wikantik.jdbc.NullableColumns.getNullableLong;
import static com.wikantik.jdbc.NullableColumns.setNullableLong;

/** PostgreSQL implementation of {@link WorkStore}; all access goes through {@link Jdbc}. */
public class JdbcWorkStore extends JdbcWorkCatalog implements WorkStore {

    static final String ITEM_SELECT = """
        SELECT i.id, i.project_id, p.key || '-' || i.seq AS item_key, i.level, i.parent_id, i.product_id, i.kind,
               i.title, i.body, i.status, i.priority, i.rank, i.due_on, i.assignee, i.reporter, i.autonomy,
               i.done_means, i.allow_direct, i.version, i.created_at, i.updated_at, i.closed_at,
               i.assignee_queue, i.claimed_by_agent, i.claimed_at, i.claim_expires_at, i.reporter_agent_id,
               i.type, i.labels, i.sprint_id, i.estimate
          FROM work_item i JOIN work_project p ON p.id = i.project_id
        """;

    static final RowMapper< Item > ITEM_ROW = rs -> new Item(
        rs.getLong( "id" ), rs.getLong( "project_id" ), rs.getString( "item_key" ),
        WorkLevel.fromWire( rs.getString( "level" ) ), getNullableLong( rs, "parent_id" ),
        getNullableLong( rs, "product_id" ), WorkKind.fromWire( rs.getString( "kind" ) ), rs.getString( "title" ),
        rs.getString( "body" ), WorkStatus.fromWire( rs.getString( "status" ) ), rs.getInt( "priority" ),
        rs.getDouble( "rank" ), getLocalDate( rs, "due_on" ), rs.getString( "assignee" ), rs.getString( "reporter" ),
        rs.getString( "autonomy" ) == null ? null : Autonomy.fromWire( rs.getString( "autonomy" ) ),
        rs.getString( "done_means" ) == null ? null : DoneMeans.fromWire( rs.getString( "done_means" ) ),
        (Boolean) rs.getObject( "allow_direct" ), rs.getInt( "version" ),
        getInstant( rs, "created_at" ), getInstant( rs, "updated_at" ), getInstant( rs, "closed_at" ),
        rs.getString( "assignee_queue" ), getNullableLong( rs, "claimed_by_agent" ), getInstant( rs, "claimed_at" ),
        getInstant( rs, "claim_expires_at" ), getNullableLong( rs, "reporter_agent_id" ),
        WorkType.fromWire( rs.getString( "type" ) ), JdbcWorkLabels.fromArray( rs.getArray( "labels" ) ),
        getNullableLong( rs, "sprint_id" ), rs.getBigDecimal( "estimate" ) );

    static final String EVENT_COLUMNS = """
        e.id, e.project_id, e.item_id,
               CASE WHEN i.id IS NULL THEN NULL ELSE p.key || '-' || i.seq END AS item_key,
               e.at, e.actor, e.actor_kind, e.agent_id, e.type, e.from_value, e.to_value, e.note
        """;

    static final String EVENT_FROM = """
          FROM work_event e
          JOIN work_project p ON p.id = e.project_id
          LEFT JOIN work_item i ON i.id = e.item_id
        """;

    private static final String EVENT_SELECT = "SELECT " + EVENT_COLUMNS + EVENT_FROM;

    static final RowMapper< WorkEvent > EVENT_ROW = rs -> new WorkEvent(
        rs.getLong( "id" ), rs.getLong( "project_id" ), getNullableLong( rs, "item_id" ), rs.getString( "item_key" ),
        getInstant( rs, "at" ), rs.getString( "actor" ), SqlValues.actorKind( rs.getString( "actor_kind" ) ),
        getNullableLong( rs, "agent_id" ), rs.getString( "type" ), rs.getString( "from_value" ),
        rs.getString( "to_value" ), rs.getString( "note" ) );

    private static final int DEFAULT_ITEM_LIMIT = 200;
    private static final int CLOSED_COLUMN_CAP = 50;

    private final JdbcWorkDiscussion discussion;
    private final JdbcWorkEventLog eventLog;
    private final JdbcWorkAcks acks;
    private final JdbcWorkItemWrites writes;
    private final JdbcWorkLabels labels;
    private final JdbcWorkChanges changes;
    private final JdbcWorkDeployments deployments;
    private final JdbcWorkSprints sprints;

    public JdbcWorkStore( final DataSource dataSource ) {
        super( new Jdbc( dataSource ) );
        this.discussion = new JdbcWorkDiscussion( jdbc, this );
        this.eventLog = new JdbcWorkEventLog( jdbc );
        this.acks = new JdbcWorkAcks( jdbc, this );
        this.writes = new JdbcWorkItemWrites( jdbc, this );
        this.labels = new JdbcWorkLabels( jdbc );
        this.changes = new JdbcWorkChanges( jdbc, this );
        this.deployments = new JdbcWorkDeployments( jdbc, this );
        this.sprints = new JdbcWorkSprints( jdbc, this );
    }

    @Override
    public WorkSprintStore sprints() {
        return sprints;
    }

    @Override
    public WorkDeploymentStore deployments() {
        return deployments;
    }

    @Override
    public WorkChangeStore changes() {
        return changes;
    }

    @Override
    public WorkAckStore acks() {
        return acks;
    }

    // ---- items

    @Override
    public Item createItem( final Project project, final Item draft, final Actor by ) throws SQLException {
        return new JdbcItemCreate( jdbc, this ).create( project, draft, by, ItemGuard.NONE );
    }


    Item itemById( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ), ITEM_ROW )
            .orElseThrow( () -> new SQLException( "work_item " + id + " not found" ) );
    }

    void insertRevision( final Connection conn, final long itemId, final int version, final String title,
                                 final String body, final Actor by ) throws SQLException {
        jdbc.update( conn, "INSERT INTO work_item_revision (item_id, version, title, body, edited_by, "
                + "edited_by_agent_id) VALUES (?,?,?,?,?,?)",
            ps -> {
                ps.setLong( 1, itemId );
                ps.setInt( 2, version );
                ps.setString( 3, title );
                ps.setString( 4, body );
                ps.setString( 5, by.login() );
                setNullableLong( ps, 6, by.agentId() );
            } );
    }

    @Override
    public PolicyChain policyChain( final long itemId ) throws SQLException {
        return PolicyChains.load( jdbc, itemId );
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
        final Matcher m = ItemKeys.PATTERN.matcher( key.trim() );
        if ( !m.matches() ) {
            return Optional.empty();
        }
        return jdbc.queryOne( ITEM_SELECT + " WHERE p.key = ? AND i.seq = ?", ps -> {
            ps.setString( 1, m.group( 1 ).toUpperCase( java.util.Locale.ROOT ) );
            ps.setInt( 2, Integer.parseInt( m.group( 2 ) ) );
        }, ITEM_ROW );
    }

    @Override
    public ItemWrite writeItem( final Item current, final Item next, final Actor by, final String note,
                                final boolean asHandoff ) throws SQLException {
        return writes.writeItem( current, next, by, note, asHandoff, CloseRules.NONE );
    }

    @Override
    public ItemWrite writeItem( final Item current, final Item next, final Actor by, final String note,
                                final boolean asHandoff, final CloseRules rules ) throws SQLException {
        return writes.writeItem( current, next, by, note, asHandoff, rules );
    }

    @Override
    public ItemWrite writeItem( final Item current, final Item next, final Actor by, final String note,
                                final boolean asHandoff, final boolean ackRequired ) throws SQLException {
        return writes.writeItem( current, next, by, note, asHandoff, new CloseRules( ackRequired, false ) );
    }

    @Override
    public boolean renumberColumn( final long projectId, final WorkStatus status, final long movedItemId,
                                   final Long beforeItemId ) throws SQLException {
        return writes.renumberColumn( projectId, status, movedItemId, beforeItemId );
    }

    // ---- queries

    @Override
    public List< Item > items( final ItemFilter f ) throws SQLException {
        final StringBuilder sql = new StringBuilder( ITEM_SELECT ).append( " WHERE TRUE" );
        final List< Object > params = new ArrayList<>();
        ItemQuery.conditions( f, true, sql, params );
        sql.append( f.order() == ItemFilter.Order.DUE ? " ORDER BY i.due_on ASC NULLS LAST, p.key, i.seq LIMIT ?"
            : " ORDER BY p.key, i.seq LIMIT ?" );
        params.add( f.limit() > 0 ? f.limit() : DEFAULT_ITEM_LIMIT );
        return jdbc.query( sql.toString(), SqlBinder.positional( params ), ITEM_ROW );
    }

    @Override
    public List< LabelCount > labelCounts( final long projectId ) throws SQLException {
        return labels.counts( projectId );
    }

    @Override
    public List< BoardColumn > board( final long projectId, final ItemFilter f ) throws SQLException {
        final ItemFilter scoped = f.forBoard( projectId );
        final List< BoardColumn > columns = new ArrayList<>();
        for ( final WorkStatus status : WorkStatus.values() ) {
            final StringBuilder where = new StringBuilder( " WHERE i.status = ?" );
            final List< Object > params = new ArrayList<>();
            params.add( status.wire() );
            ItemQuery.conditions( scoped, false, where, params );
            final int total = jdbc.queryOne( "SELECT COUNT(*) FROM work_item i" + where,
                SqlBinder.positional( params ), rs -> rs.getInt( 1 ) ).orElse( 0 );
            final String order = ItemQuery.boardOrder( isClosed( status ), CLOSED_COLUMN_CAP );
            columns.add( new BoardColumn( status, total, jdbc.query( ITEM_SELECT + where + order,
                SqlBinder.positional( params ), ITEM_ROW ) ) );
        }
        return columns;
    }

    @Override
    public List< ItemRevision > revisions( final long itemId ) throws SQLException {
        return jdbc.query( "SELECT item_id, version, title, body, edited_by, edited_at, edited_by_agent_id FROM work_item_revision "
                + "WHERE item_id = ? ORDER BY version",
            ps -> ps.setLong( 1, itemId ), rs -> new ItemRevision( rs.getLong( "item_id" ), rs.getInt( "version" ),
                rs.getString( "title" ), rs.getString( "body" ), rs.getString( "edited_by" ),
                getInstant( rs, "edited_at" ), getNullableLong( rs, "edited_by_agent_id" ) ) );
    }

    @Override
    public List< WorkEvent > itemEvents( final long itemId ) throws SQLException {
        return jdbc.query( EVENT_SELECT + " WHERE e.item_id = ? ORDER BY e.at, e.id",
            ps -> ps.setLong( 1, itemId ), EVENT_ROW );
    }

    @Override
    public List< WorkEvent > projectEvents( final long projectId, final Instant before, final Long beforeId,
                                            final Long itemId, final Set< String > types,
                                            final boolean withWebhookEvents, final int limit ) throws SQLException {
        return eventLog.projectEvents( projectId, before, beforeId, itemId, types, withWebhookEvents, limit );
    }

    @Override
    public List< LogEntry > eventsAcross( final java.util.Collection< Long > projectIds, final Instant before,
                                          final Long beforeId, final Set< String > types, final Actor.Kind actorKind,
                                          final Long agentId, final int limit ) throws SQLException {
        return eventLog.eventsAcross( projectIds, before, beforeId, types, actorKind, agentId, limit );
    }

    @Override
    public java.util.Map< Long, WorkEvent > lastHandoffs( final java.util.Collection< Long > itemIds )
            throws SQLException {
        return discussion.lastHandoffs( itemIds );
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
    public Comment addComment( final Item item, final Actor by, final String body,
                               final List< String > mentioned ) throws SQLException {
        return discussion.addComment( item, by, body, mentioned );
    }

    @Override
    public Comment editComment( final long commentId, final String body, final Actor by,
                                final List< String > mentioned ) throws SQLException {
        return discussion.editComment( commentId, body, by, mentioned );
    }

    @Override
    public void removeComment( final long commentId, final Actor by ) throws SQLException {
        discussion.removeComment( commentId, by );
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
    public Link addLink( final Item item, final String kind, final String target, final Actor by )
            throws SQLException {
        return discussion.addLink( item, kind, target, by );
    }

    @Override
    public void removeLink( final long linkId, final Actor by ) throws SQLException {
        discussion.removeLink( linkId, by );
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

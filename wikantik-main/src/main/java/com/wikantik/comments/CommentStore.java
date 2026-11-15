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
package com.wikantik.comments;

import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.jdbc.Jdbc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC gateway for {@code comment_threads} and {@code comments}. */
public class CommentStore {

    private static final Logger LOG = LogManager.getLogger( CommentStore.class );

    private final Jdbc jdbc;

    public CommentStore( final DataSource ds ) {
        this.jdbc = new Jdbc( ds );
    }

    public CommentThread createThread( final String canonicalId, final TextQuoteSelector anchor,
                                       final String author, final String body,
                                       final MentionService mentionSvc,
                                       final Optional< String > ownerForMention ) {
        return createThread( canonicalId, anchor, author, body, mentionSvc, ownerForMention, true, false );
    }

    public CommentThread createThread( final String canonicalId, final TextQuoteSelector anchor,
                                       final String author, final String body,
                                       final MentionService mentionSvc,
                                       final Optional< String > ownerForMention,
                                       final boolean agentAvailable, final boolean authorCouldEdit ) {
        final UUID threadId = UUID.randomUUID();
        final UUID commentId = UUID.randomUUID();
        try {
            jdbc.inTransaction( c -> {
                jdbc.update( c, "INSERT INTO comment_threads (id, canonical_id, anchor_exact, anchor_prefix, " +
                        "anchor_suffix, status, created_by, agent_available) VALUES (?, ?, ?, ?, ?, 'open', ?, ?)",
                        ps -> {
                            ps.setObject( 1, threadId );
                            ps.setString( 2, canonicalId );
                            ps.setString( 3, anchor.exact() );
                            ps.setString( 4, anchor.prefix() );
                            ps.setString( 5, anchor.suffix() );
                            ps.setString( 6, author );
                            ps.setBoolean( 7, agentAvailable );
                        } );
                insertComment( c, commentId, threadId, author, body, authorCouldEdit );
                mentionSvc.recordCreate( c, commentId, author, body, ownerForMention );
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "comment thread create (canonicalId={}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "comment thread create failed", e );
        }
        return findThread( threadId ).orElseThrow(
                () -> new RuntimeException( "thread vanished after insert: " + threadId ) );
    }

    public Comment addComment( final UUID threadId, final String author, final String body,
                               final MentionService mentionSvc ) {
        return addComment( threadId, author, body, mentionSvc, false );
    }

    public Comment addComment( final UUID threadId, final String author, final String body,
                               final MentionService mentionSvc, final boolean authorCouldEdit ) {
        final UUID commentId = UUID.randomUUID();
        try {
            jdbc.inTransaction( c -> {
                insertComment( c, commentId, threadId, author, body, authorCouldEdit );
                mentionSvc.recordReply( c, commentId, author, body );
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "comment add (threadId={}) failed: {}", threadId, e.getMessage(), e );
            throw new RuntimeException( "comment add failed", e );
        }
        return findComment( commentId ).orElseThrow(
                () -> new RuntimeException( "comment vanished after insert: " + commentId ) );
    }

    private void insertComment( final Connection c, final UUID id, final UUID threadId,
                                final String author, final String body,
                                final boolean authorCouldEdit ) throws SQLException {
        jdbc.update( c, "INSERT INTO comments (id, thread_id, author, body, author_could_edit) " +
                "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setObject( 1, id );
                    ps.setObject( 2, threadId );
                    ps.setString( 3, author );
                    ps.setString( 4, body );
                    ps.setBoolean( 5, authorCouldEdit );
                } );
    }

    public Optional< Comment > editComment( final UUID commentId, final String oldBody,
                                            final String newBody, final String mentioningLogin,
                                            final MentionService mentionSvc ) {
        return editComment( commentId, oldBody, newBody, mentioningLogin, mentionSvc, null );
    }

    /** Edits a comment; a non-null {@code authorCouldEdit} also refreshes the trust snapshot. */
    public Optional< Comment > editComment( final UUID commentId, final String oldBody,
                                            final String newBody, final String mentioningLogin,
                                            final MentionService mentionSvc,
                                            final Boolean authorCouldEdit ) {
        // A miss (0 rows updated) commits an empty transaction rather than rolling back —
        // identical in effect, since nothing was written, and it keeps the abort path out
        // of the shared primitive.
        final Boolean applied;
        try {
            applied = jdbc.inTransaction( c -> {
                final int rows = jdbc.update( c,
                        "UPDATE comments SET body = ?, edited_at = CURRENT_TIMESTAMP, " +
                        "author_could_edit = COALESCE( ?, author_could_edit ) WHERE id = ?",
                        ps -> {
                            ps.setString( 1, newBody );
                            ps.setObject( 2, authorCouldEdit, java.sql.Types.BOOLEAN );
                            ps.setObject( 3, commentId );
                        } );
                if ( rows == 0 ) {
                    return Boolean.FALSE;
                }
                mentionSvc.recordEdit( c, commentId, mentioningLogin, oldBody, newBody );
                return Boolean.TRUE;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "comment edit (id={}) failed: {}", commentId, e.getMessage(), e );
            throw new RuntimeException( "comment edit failed", e );
        }
        return Boolean.TRUE.equals( applied ) ? findComment( commentId ) : Optional.empty();
    }

    public boolean deleteComment( final UUID commentId ) {
        return executeUpdate( "DELETE FROM comments WHERE id = ?", commentId, "deleteComment" );
    }

    public boolean deleteThread( final UUID threadId ) {
        return executeUpdate( "DELETE FROM comment_threads WHERE id = ?", threadId, "deleteThread" );
    }

    /**
     * Drops every thread of a page whose canonical_id was retired by a true delete; the
     * threads' comments and mentions go with them ({@code ON DELETE CASCADE}).
     *
     * @return threads removed
     */
    public int deleteThreadsForPage( final String canonicalId ) {
        try {
            return jdbc.update( "DELETE FROM comment_threads WHERE canonical_id = ?",
                    ps -> ps.setString( 1, canonicalId ) );
        } catch ( final SQLException e ) {
            LOG.warn( "deleteThreadsForPage(canonicalId={}) failed: {}", canonicalId, e.getMessage(), e );
            throw new RuntimeException( "deleteThreadsForPage failed", e );
        }
    }

    public boolean resolve( final UUID threadId, final String resolvedBy ) {
        try {
            return jdbc.update( "UPDATE comment_threads SET status = 'resolved', resolved_by = ?, " +
                    "resolved_at = CURRENT_TIMESTAMP WHERE id = ?",
                    ps -> {
                        ps.setString( 1, resolvedBy );
                        ps.setObject( 2, threadId );
                    } ) > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "resolve(threadId={}) failed: {}", threadId, e.getMessage(), e );
            throw new RuntimeException( "thread resolve failed", e );
        }
    }

    public boolean reopen( final UUID threadId ) {
        try {
            return jdbc.update( "UPDATE comment_threads SET status = 'open', resolved_by = NULL, " +
                    "resolved_at = NULL WHERE id = ?",
                    ps -> ps.setObject( 1, threadId ) ) > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "reopen(threadId={}) failed: {}", threadId, e.getMessage(), e );
            throw new RuntimeException( "thread reopen failed", e );
        }
    }

    private boolean executeUpdate( final String sql, final UUID id, final String op ) {
        try {
            return jdbc.update( sql, ps -> ps.setObject( 1, id ) ) > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "{}(id={}) failed: {}", op, id, e.getMessage(), e );
            throw new RuntimeException( op + " failed", e );
        }
    }

    public List< CommentThread > listByCanonicalId( final String canonicalId, final String statusFilter ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT id, canonical_id, anchor_exact, anchor_prefix, anchor_suffix, status, " +
                "created_by, created_at, resolved_by, resolved_at, agent_available FROM comment_threads WHERE canonical_id = ?" );
        final boolean byStatus = CommentThread.OPEN.equals( statusFilter ) || CommentThread.RESOLVED.equals( statusFilter )
                || CommentThread.DISMISSED.equals( statusFilter );
        if ( byStatus ) sql.append( " AND status = ?" );
        sql.append( " ORDER BY created_at" );

        final List< CommentThread > threads = new ArrayList<>();
        try {
            jdbc.inTransaction( c -> {
                final List< ThreadRow > rows = jdbc.query( c, sql.toString(),
                        ps -> {
                            ps.setString( 1, canonicalId );
                            if ( byStatus ) ps.setString( 2, statusFilter );
                        }, CommentStore::readThreadRow );
                // Rows are collected before issuing per-thread comment queries on the same connection.
                for ( final ThreadRow row : rows ) {
                    threads.add( toThread( row, loadComments( c, row.id() ) ) );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "listByCanonicalId({}) failed: {}", canonicalId, e.getMessage(), e );
        }
        return threads;
    }

    /** What an agent may do to a thread. */
    public enum AgentAction { REPLY, RESOLVE, DISMISS }

    /** Result of {@link #respondAsAgent}; everything but {@code OK} is a refusal that wrote nothing. */
    public enum AgentOutcome { OK, NOT_FOUND, NOT_OPEN, NOT_AGENT_AVAILABLE, NOT_ACTIONABLE }

    /** {@code comment} is null unless {@code outcome} is {@code OK}; {@code status} is the thread's status afterwards. */
    public record AgentResponse( AgentOutcome outcome, Comment comment, String status ) {}

    private record LockedThread( String status, boolean agentAvailable, String createdBy ) {}

    private static final int INBOX_COMMENT_CAP = 50;

    /**
     * Posts an agent comment and optionally closes the thread, atomically. The thread row is locked
     * for the duration, so two concurrent responses cannot both close it.
     */
    public AgentResponse respondAsAgent( final UUID threadId, final AgentAction action,
                                         final String agentAuthor, final String body,
                                         final MentionService mentionSvc ) {
        final UUID commentId = UUID.randomUUID();
        try {
            return jdbc.inTransaction( c -> {
                final Optional< LockedThread > locked = jdbc.queryOne( c,
                        "SELECT status, agent_available, created_by FROM comment_threads WHERE id = ? FOR UPDATE",
                        ps -> ps.setObject( 1, threadId ),
                        rs -> new LockedThread( rs.getString( "status" ), rs.getBoolean( "agent_available" ),
                                rs.getString( "created_by" ) ) );
                if ( locked.isEmpty() ) {
                    return new AgentResponse( AgentOutcome.NOT_FOUND, null, null );
                }
                final LockedThread t = locked.get();
                if ( !t.agentAvailable() ) {
                    return new AgentResponse( AgentOutcome.NOT_AGENT_AVAILABLE, null, t.status() );
                }
                if ( !CommentThread.OPEN.equals( t.status() ) ) {
                    return new AgentResponse( AgentOutcome.NOT_OPEN, null, t.status() );
                }
                if ( action == AgentAction.RESOLVE && !hasTrustedComment( c, threadId ) ) {
                    return new AgentResponse( AgentOutcome.NOT_ACTIONABLE, null, t.status() );
                }
                jdbc.update( c, "INSERT INTO comments (id, thread_id, author, body, via_agent, author_could_edit) " +
                        "VALUES (?, ?, ?, ?, TRUE, FALSE)",
                        ps -> {
                            ps.setObject( 1, commentId );
                            ps.setObject( 2, threadId );
                            ps.setString( 3, agentAuthor );
                            ps.setString( 4, body );
                        } );
                mentionSvc.recordAgentResponse( c, commentId, agentAuthor, t.createdBy() );
                String status = CommentThread.OPEN;
                if ( action != AgentAction.REPLY ) {
                    status = action == AgentAction.RESOLVE ? CommentThread.RESOLVED : CommentThread.DISMISSED;
                    final String closed = status;
                    jdbc.update( c, "UPDATE comment_threads SET status = ?, resolved_by = ?, " +
                            "resolved_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'open'",
                            ps -> {
                                ps.setString( 1, closed );
                                ps.setString( 2, agentAuthor );
                                ps.setObject( 3, threadId );
                            } );
                }
                final Comment saved = jdbc.queryOne( c,
                        "SELECT id, thread_id, author, body, created_at, edited_at, via_agent, author_could_edit " +
                        "FROM comments WHERE id = ?",
                        ps -> ps.setObject( 1, commentId ), CommentStore::readComment ).orElseThrow(
                        () -> new IllegalStateException( "agent comment vanished after insert: " + commentId ) );
                return new AgentResponse( AgentOutcome.OK, saved, status );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "respondAsAgent(threadId={}, action={}) failed: {}", threadId, action, e.getMessage(), e );
            throw new RuntimeException( "agent response failed", e );
        }
    }

    private boolean hasTrustedComment( final Connection c, final UUID threadId ) throws SQLException {
        return jdbc.queryOne( c, "SELECT EXISTS ( SELECT 1 FROM comments WHERE thread_id = ? " +
                "AND NOT via_agent AND author_could_edit )",
                ps -> ps.setObject( 1, threadId ), rs -> rs.getBoolean( 1 ) ).orElse( false );
    }

    /**
     * Agent-available threads in {@code status} (anything but resolved/dismissed counts as open), oldest
     * first, each with its {@value #INBOX_COMMENT_CAP} most recent comments in chronological order.
     *
     * @param canonicalIds pages to include; null means every page, empty means none
     */
    public List< CommentThread > listAgentInbox( final String status, final Collection< String > canonicalIds,
                                                 final int limit ) {
        final List< CommentThread > threads = new ArrayList<>();
        if ( canonicalIds != null && canonicalIds.isEmpty() ) {
            return threads;
        }
        final String wanted = CommentThread.RESOLVED.equals( status ) || CommentThread.DISMISSED.equals( status )
                ? status : CommentThread.OPEN;
        final StringBuilder sql = new StringBuilder(
                "SELECT id, canonical_id, anchor_exact, anchor_prefix, anchor_suffix, status, " +
                "created_by, created_at, resolved_by, resolved_at, agent_available FROM comment_threads " +
                "WHERE agent_available AND status = ?" );
        if ( canonicalIds != null ) sql.append( " AND canonical_id = ANY( ? )" );
        sql.append( " ORDER BY created_at, id LIMIT ?" );
        try {
            jdbc.inTransaction( c -> {
                final List< ThreadRow > rows = jdbc.query( c, sql.toString(),
                        ps -> {
                            int i = 1;
                            ps.setString( i++, wanted );
                            if ( canonicalIds != null ) {
                                ps.setArray( i++, c.createArrayOf( "text", canonicalIds.toArray( new String[ 0 ] ) ) );
                            }
                            ps.setInt( i, limit );
                        }, CommentStore::readThreadRow );
                for ( final ThreadRow row : rows ) {
                    final List< Comment > recent = jdbc.query( c,
                            "SELECT id, thread_id, author, body, created_at, edited_at, via_agent, author_could_edit " +
                            "FROM comments WHERE thread_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                            ps -> {
                                ps.setObject( 1, row.id() );
                                ps.setInt( 2, INBOX_COMMENT_CAP );
                            }, CommentStore::readComment );
                    final List< Comment > chronological = new ArrayList<>( recent );
                    java.util.Collections.reverse( chronological );
                    threads.add( toThread( row, chronological ) );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "listAgentInbox(status={}) failed: {}", wanted, e.getMessage(), e );
        }
        return threads;
    }

    public Optional< CommentThread > findThread( final UUID threadId ) {
        try {
            return jdbc.inTransaction( c -> {
                final Optional< ThreadRow > row = jdbc.queryOne( c,
                        "SELECT id, canonical_id, anchor_exact, anchor_prefix, anchor_suffix, status, " +
                        "created_by, created_at, resolved_by, resolved_at, agent_available FROM comment_threads WHERE id = ?",
                        ps -> ps.setObject( 1, threadId ), CommentStore::readThreadRow );
                if ( row.isEmpty() ) return Optional.< CommentThread >empty();
                return Optional.of( toThread( row.get(), loadComments( c, threadId ) ) );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "findThread({}) failed: {}", threadId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    public Optional< Comment > findComment( final UUID commentId ) {
        try {
            return jdbc.queryOne( "SELECT id, thread_id, author, body, created_at, edited_at, via_agent, author_could_edit " +
                    "FROM comments WHERE id = ?",
                    ps -> ps.setObject( 1, commentId ), CommentStore::readComment );
        } catch ( final SQLException e ) {
            LOG.warn( "findComment({}) failed: {}", commentId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    private List< Comment > loadComments( final Connection c, final UUID threadId ) throws SQLException {
        return jdbc.query( c, "SELECT id, thread_id, author, body, created_at, edited_at, via_agent, author_could_edit " +
                "FROM comments WHERE thread_id = ? ORDER BY created_at",
                ps -> ps.setObject( 1, threadId ), CommentStore::readComment );
    }

    private record ThreadRow( UUID id, String canonicalId, TextQuoteSelector anchor, String status,
                              String createdBy, Instant createdAt, String resolvedBy, Instant resolvedAt,
                              boolean agentAvailable ) {}

    private static ThreadRow readThreadRow( final ResultSet rs ) throws SQLException {
        return new ThreadRow(
                (UUID) rs.getObject( "id" ),
                rs.getString( "canonical_id" ),
                new TextQuoteSelector( rs.getString( "anchor_exact" ),
                        rs.getString( "anchor_prefix" ), rs.getString( "anchor_suffix" ) ),
                rs.getString( "status" ),
                rs.getString( "created_by" ),
                toInstant( rs.getTimestamp( "created_at" ) ),
                rs.getString( "resolved_by" ),
                toInstant( rs.getTimestamp( "resolved_at" ) ),
                rs.getBoolean( "agent_available" ) );
    }

    private static CommentThread toThread( final ThreadRow r, final List< Comment > comments ) {
        return new CommentThread( r.id(), r.canonicalId(), r.anchor(), r.status(),
                r.createdBy(), r.createdAt(), r.resolvedBy(), r.resolvedAt(), comments,
                r.agentAvailable() );
    }

    private static Comment readComment( final ResultSet rs ) throws SQLException {
        return new Comment(
                (UUID) rs.getObject( "id" ),
                (UUID) rs.getObject( "thread_id" ),
                rs.getString( "author" ),
                rs.getString( "body" ),
                toInstant( rs.getTimestamp( "created_at" ) ),
                toInstant( rs.getTimestamp( "edited_at" ) ),
                rs.getBoolean( "via_agent" ),
                rs.getBoolean( "author_could_edit" ) );
    }

    private static Instant toInstant( final Timestamp ts ) {
        return ts == null ? null : ts.toInstant();
    }
}

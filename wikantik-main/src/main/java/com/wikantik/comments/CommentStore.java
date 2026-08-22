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
        final UUID threadId = UUID.randomUUID();
        final UUID commentId = UUID.randomUUID();
        try {
            jdbc.inTransaction( c -> {
                jdbc.update( c, "INSERT INTO comment_threads (id, canonical_id, anchor_exact, anchor_prefix, " +
                        "anchor_suffix, status, created_by) VALUES (?, ?, ?, ?, ?, 'open', ?)",
                        ps -> {
                            ps.setObject( 1, threadId );
                            ps.setString( 2, canonicalId );
                            ps.setString( 3, anchor.exact() );
                            ps.setString( 4, anchor.prefix() );
                            ps.setString( 5, anchor.suffix() );
                            ps.setString( 6, author );
                        } );
                insertComment( c, commentId, threadId, author, body );
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
        final UUID commentId = UUID.randomUUID();
        try {
            jdbc.inTransaction( c -> {
                insertComment( c, commentId, threadId, author, body );
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
                                final String author, final String body ) throws SQLException {
        jdbc.update( c, "INSERT INTO comments (id, thread_id, author, body) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setObject( 1, id );
                    ps.setObject( 2, threadId );
                    ps.setString( 3, author );
                    ps.setString( 4, body );
                } );
    }

    public Optional< Comment > editComment( final UUID commentId, final String oldBody,
                                            final String newBody, final String mentioningLogin,
                                            final MentionService mentionSvc ) {
        // A miss (0 rows updated) commits an empty transaction rather than rolling back —
        // identical in effect, since nothing was written, and it keeps the abort path out
        // of the shared primitive.
        final Boolean applied;
        try {
            applied = jdbc.inTransaction( c -> {
                final int rows = jdbc.update( c,
                        "UPDATE comments SET body = ?, edited_at = CURRENT_TIMESTAMP WHERE id = ?",
                        ps -> {
                            ps.setString( 1, newBody );
                            ps.setObject( 2, commentId );
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
                "created_by, created_at, resolved_by, resolved_at FROM comment_threads WHERE canonical_id = ?" );
        final boolean byStatus = "open".equals( statusFilter ) || "resolved".equals( statusFilter );
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

    public Optional< CommentThread > findThread( final UUID threadId ) {
        try {
            return jdbc.inTransaction( c -> {
                final Optional< ThreadRow > row = jdbc.queryOne( c,
                        "SELECT id, canonical_id, anchor_exact, anchor_prefix, anchor_suffix, status, " +
                        "created_by, created_at, resolved_by, resolved_at FROM comment_threads WHERE id = ?",
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
            return jdbc.queryOne( "SELECT id, thread_id, author, body, created_at, edited_at " +
                    "FROM comments WHERE id = ?",
                    ps -> ps.setObject( 1, commentId ), CommentStore::readComment );
        } catch ( final SQLException e ) {
            LOG.warn( "findComment({}) failed: {}", commentId, e.getMessage(), e );
            return Optional.empty();
        }
    }

    private List< Comment > loadComments( final Connection c, final UUID threadId ) throws SQLException {
        return jdbc.query( c, "SELECT id, thread_id, author, body, created_at, edited_at " +
                "FROM comments WHERE thread_id = ? ORDER BY created_at",
                ps -> ps.setObject( 1, threadId ), CommentStore::readComment );
    }

    private record ThreadRow( UUID id, String canonicalId, TextQuoteSelector anchor, String status,
                              String createdBy, Instant createdAt, String resolvedBy, Instant resolvedAt ) {}

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
                toInstant( rs.getTimestamp( "resolved_at" ) ) );
    }

    private static CommentThread toThread( final ThreadRow r, final List< Comment > comments ) {
        return new CommentThread( r.id(), r.canonicalId(), r.anchor(), r.status(),
                r.createdBy(), r.createdAt(), r.resolvedBy(), r.resolvedAt(), comments );
    }

    private static Comment readComment( final ResultSet rs ) throws SQLException {
        return new Comment(
                (UUID) rs.getObject( "id" ),
                (UUID) rs.getObject( "thread_id" ),
                rs.getString( "author" ),
                rs.getString( "body" ),
                toInstant( rs.getTimestamp( "created_at" ) ),
                toInstant( rs.getTimestamp( "edited_at" ) ) );
    }

    private static Instant toInstant( final Timestamp ts ) {
        return ts == null ? null : ts.toInstant();
    }
}

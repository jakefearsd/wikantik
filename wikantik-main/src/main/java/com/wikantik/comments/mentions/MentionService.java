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
package com.wikantik.comments.mentions;

import com.wikantik.api.comments.Mention;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Writes/reads {@code comment_mentions}. Write methods accept a Connection so
 *  they can run inside the caller's transaction (CommentStore opens one for
 *  thread creation; we participate in it). */
public class MentionService {

    private static final Logger LOG = LogManager.getLogger( MentionService.class );

    private final DataSource ds;
    private final Predicate< String > userExists;

    public MentionService( final DataSource ds, final Predicate< String > userExists ) {
        this.ds = ds;
        this.userExists = userExists;
    }

    /** First comment of a thread. Writes direct mentions (including self-mentions);
     *  if {@code owner} is present, distinct from the author, and not already
     *  in the direct set, also writes an owner-mention row. */
    public void recordCreate( final Connection c, final UUID commentId,
                              final String mentioningLogin, final String body,
                              final Optional< String > owner ) throws SQLException {
        final Set< String > direct = directMentionsFor( body, mentioningLogin );
        for ( final String m : direct ) insertIfAbsent( c, commentId, m, mentioningLogin, false );
        if ( owner.isPresent() ) {
            final String o = owner.get();
            if ( !o.equals( mentioningLogin ) && !direct.contains( o ) ) {
                insertIfAbsent( c, commentId, o, mentioningLogin, true );
            }
        }
    }

    /** Reply or any non-opening comment. Direct mentions only. */
    public void recordReply( final Connection c, final UUID commentId,
                             final String mentioningLogin, final String body ) throws SQLException {
        for ( final String m : directMentionsFor( body, mentioningLogin ) ) {
            insertIfAbsent( c, commentId, m, mentioningLogin, false );
        }
    }

    /** Apply an edit by diffing old vs new directly-mentioned sets. Owner
     *  rows are NOT touched (the page owner doesn't depend on the body). */
    public void recordEdit( final Connection c, final UUID commentId,
                            final String mentioningLogin,
                            final String oldBody, final String newBody ) throws SQLException {
        final Set< String > oldSet = directMentionsFor( oldBody, mentioningLogin );
        final Set< String > newSet = directMentionsFor( newBody, mentioningLogin );
        final Set< String > added = new HashSet<>( newSet ); added.removeAll( oldSet );
        final Set< String > removed = new HashSet<>( oldSet ); removed.removeAll( newSet );
        for ( final String m : added ) insertIfAbsent( c, commentId, m, mentioningLogin, false );
        if ( !removed.isEmpty() ) {
            try ( PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM comment_mentions WHERE comment_id = ? AND mentioned_login = ? " +
                    "AND is_owner_mention = FALSE" ) ) {
                for ( final String m : removed ) {
                    ps.setObject( 1, commentId );
                    ps.setString( 2, m );
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    public List< Mention > findByComment( final UUID commentId ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT id, comment_id, mentioned_login, mentioning_login, is_owner_mention, " +
                      "created_at, read_at FROM comment_mentions WHERE comment_id = ? " +
                      "ORDER BY created_at" ) ) {
            ps.setObject( 1, commentId );
            return readRows( ps );
        } catch ( final SQLException e ) {
            LOG.warn( "findByComment({}) failed: {}", commentId, e.getMessage(), e );
            return List.of();
        }
    }

    public boolean markRead( final UUID mentionId, final String requester ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comment_mentions SET read_at = CURRENT_TIMESTAMP " +
                      "WHERE id = ? AND mentioned_login = ? AND read_at IS NULL" ) ) {
            ps.setObject( 1, mentionId );
            ps.setString( 2, requester );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "markRead({}) failed: {}", mentionId, e.getMessage(), e );
            return false;
        }
    }

    public int markAllRead( final String requester ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "UPDATE comment_mentions SET read_at = CURRENT_TIMESTAMP " +
                      "WHERE mentioned_login = ? AND read_at IS NULL" ) ) {
            ps.setString( 1, requester );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            LOG.warn( "markAllRead({}) failed: {}", requester, e.getMessage(), e );
            return 0;
        }
    }

    public int unreadCount( final String mentionedLogin ) {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                      "SELECT COUNT(*) FROM comment_mentions WHERE mentioned_login = ? AND read_at IS NULL" ) ) {
            ps.setString( 1, mentionedLogin );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "unreadCount({}) failed: {}", mentionedLogin, e.getMessage(), e );
            return 0;
        }
    }

    /** Resolved (existing-user-only) direct mentions in {@code body}.
     *  Self-mentions are kept — users can intentionally @-mention themselves
     *  (e.g., as a personal todo marker). */
    private Set< String > directMentionsFor( final String body, final String author ) {
        final Set< String > tokens = MentionExtractor.parse( body );
        final Set< String > resolved = MentionExtractor.resolve( tokens, userExists );
        return resolved;
    }

    /** Insert one row; no-op if the (comment_id, mentioned_login) pair already
     *  exists. Uses an explicit SELECT-then-INSERT branch (portable across H2
     *  PostgreSQL mode and PostgreSQL — no MERGE, no ON CONFLICT). */
    private static void insertIfAbsent( final Connection c, final UUID commentId,
                                        final String mentionedLogin, final String mentioningLogin,
                                        final boolean isOwnerMention ) throws SQLException {
        final boolean exists;
        try ( PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM comment_mentions WHERE comment_id = ? AND mentioned_login = ?" ) ) {
            ps.setObject( 1, commentId );
            ps.setString( 2, mentionedLogin );
            try ( ResultSet rs = ps.executeQuery() ) {
                exists = rs.next();
            }
        }
        if ( exists ) return;
        try ( PreparedStatement ps = c.prepareStatement(
                "INSERT INTO comment_mentions (id, comment_id, mentioned_login, mentioning_login, is_owner_mention) " +
                "VALUES (?, ?, ?, ?, ?)" ) ) {
            ps.setObject( 1, UUID.randomUUID() );
            ps.setObject( 2, commentId );
            ps.setString( 3, mentionedLogin );
            ps.setString( 4, mentioningLogin );
            ps.setBoolean( 5, isOwnerMention );
            ps.executeUpdate();
        }
    }

    private static List< Mention > readRows( final PreparedStatement ps ) throws SQLException {
        final List< Mention > out = new ArrayList<>();
        try ( ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                final Timestamp createdAt = rs.getTimestamp( "created_at" );
                final Timestamp readAt    = rs.getTimestamp( "read_at" );
                out.add( new Mention(
                        (UUID) rs.getObject( "id" ),
                        (UUID) rs.getObject( "comment_id" ),
                        rs.getString( "mentioned_login" ),
                        rs.getString( "mentioning_login" ),
                        rs.getBoolean( "is_owner_mention" ),
                        createdAt == null ? null : createdAt.toInstant(),
                        readAt    == null ? null : readAt.toInstant() ) );
            }
        }
        return out;
    }
}

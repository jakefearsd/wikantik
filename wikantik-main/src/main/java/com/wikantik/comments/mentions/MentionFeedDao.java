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

import com.wikantik.api.comments.MentionFeedItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-side for the per-user "My mentions" feed. Joins comment_mentions
 *  with comments + comment_threads to produce the page-aware feed item.
 *  Page-name resolution from canonical_id is left to the REST caller — the
 *  records returned here have {@code pageName = null}. */
public class MentionFeedDao {

    public enum Status { UNREAD, ALL }

    private static final int SNIPPET_MAX = 160;
    private static final Logger LOG = LogManager.getLogger( MentionFeedDao.class );

    private final DataSource ds;

    public MentionFeedDao( final DataSource ds ) {
        this.ds = ds;
    }

    public List< MentionFeedItem > list( final String mentionedLogin, final Status status,
                                         final int limit, final Optional< Instant > before ) {
        final StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.created_at AS mentioned_at, m.read_at, m.mentioning_login, " +
                "       m.is_owner_mention, c.id AS comment_id, c.thread_id, c.body, t.canonical_id " +
                "FROM comment_mentions m " +
                "JOIN comments c ON c.id = m.comment_id " +
                "JOIN comment_threads t ON t.id = c.thread_id " +
                "WHERE m.mentioned_login = ?" );
        if ( status == Status.UNREAD ) sql.append( " AND m.read_at IS NULL" );
        if ( before.isPresent() )      sql.append( " AND m.created_at < ?" );
        sql.append( " ORDER BY m.created_at DESC LIMIT ?" );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( sql.toString() ) ) {
            int idx = 1;
            ps.setString( idx++, mentionedLogin );
            if ( before.isPresent() ) ps.setTimestamp( idx++, Timestamp.from( before.get() ) );
            ps.setInt( idx, limit );

            final List< MentionFeedItem > out = new ArrayList<>();
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    final Timestamp at = rs.getTimestamp( "mentioned_at" );
                    final Timestamp ra = rs.getTimestamp( "read_at" );
                    final String body = rs.getString( "body" );
                    final String snippet = body == null ? ""
                            : ( body.length() <= SNIPPET_MAX ? body : body.substring( 0, SNIPPET_MAX ) );
                    out.add( new MentionFeedItem(
                            (UUID) rs.getObject( "id" ),
                            (UUID) rs.getObject( "thread_id" ),
                            (UUID) rs.getObject( "comment_id" ),
                            rs.getString( "canonical_id" ),
                            null,
                            snippet,
                            rs.getString( "mentioning_login" ),
                            rs.getBoolean( "is_owner_mention" ),
                            at == null ? null : at.toInstant(),
                            ra == null ? null : ra.toInstant() ) );
                }
            }
            return out;
        } catch ( final SQLException e ) {
            LOG.warn( "MentionFeedDao.list({}) failed: {}", mentionedLogin, e.getMessage(), e );
            return List.of();
        }
    }
}

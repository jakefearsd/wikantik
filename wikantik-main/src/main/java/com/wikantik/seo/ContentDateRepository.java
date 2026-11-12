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
package com.wikantik.seo;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.SqlBinder;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** JDBC store for {@code page_content_dates} (V072). Failures surface as {@link IllegalStateException}. */
class ContentDateRepository {

    private final Jdbc jdbc;

    ContentDateRepository( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    Optional< ContentDate > find( final String page ) {
        try {
            return jdbc.queryOne(
                "SELECT page_name, content_changed_at, baseline_version FROM page_content_dates WHERE page_name = ?",
                ps -> ps.setString( 1, page ),
                rs -> new ContentDate( rs.getString( 1 ), rs.getTimestamp( 2 ).toInstant(), rs.getInt( 3 ) ) );
        } catch( final SQLException e ) {
            throw new IllegalStateException( "content date lookup failed for " + page, e );
        }
    }

    Map< String, java.time.Instant > findAll() {
        try {
            final Map< String, java.time.Instant > out = new HashMap<>();
            jdbc.query( "SELECT page_name, content_changed_at FROM page_content_dates", SqlBinder.NONE,
                        rs -> out.put( rs.getString( 1 ), rs.getTimestamp( 2 ).toInstant() ) );
            return out;
        } catch( final SQLException e ) {
            throw new IllegalStateException( "content date listing failed", e );
        }
    }

    void upsert( final ContentDate d ) {
        try {
            jdbc.update(
                "INSERT INTO page_content_dates (page_name, content_changed_at, baseline_version) VALUES (?, ?, ?) "
                + "ON CONFLICT (page_name) DO UPDATE SET content_changed_at = EXCLUDED.content_changed_at, "
                + "baseline_version = EXCLUDED.baseline_version, updated_at = NOW()",
                ps -> {
                    ps.setString( 1, d.pageName() );
                    ps.setTimestamp( 2, Timestamp.from( d.contentChangedAt() ) );
                    ps.setInt( 3, d.baselineVersion() );
                } );
        } catch( final SQLException e ) {
            throw new IllegalStateException( "content date upsert failed for " + d.pageName(), e );
        }
    }

    void delete( final String page ) {
        try {
            jdbc.update( "DELETE FROM page_content_dates WHERE page_name = ?", ps -> ps.setString( 1, page ) );
        } catch( final SQLException e ) {
            throw new IllegalStateException( "content date delete failed for " + page, e );
        }
    }

    /** Moves {@code from}'s row to {@code to}, replacing any row already there. One transaction. */
    void rename( final String from, final String to ) {
        try {
            jdbc.inTransaction( conn -> {
                jdbc.update( conn, "DELETE FROM page_content_dates WHERE page_name = ?", ps -> ps.setString( 1, to ) );
                jdbc.update( conn, "UPDATE page_content_dates SET page_name = ?, updated_at = NOW() WHERE page_name = ?",
                             ps -> {
                                 ps.setString( 1, to );
                                 ps.setString( 2, from );
                             } );
                return null;
            } );
        } catch( final SQLException e ) {
            throw new IllegalStateException( "content date rename failed " + from + " -> " + to, e );
        }
    }
}

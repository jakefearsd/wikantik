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
package com.wikantik.collab.adapter;

import com.wikantik.jdbc.Jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The page-version contributor ledger ({@code page_version_contributor}, V066) behind
 * {@link com.wikantik.collab.CollabStore#recordContributors} and
 * {@link com.wikantik.collab.CollabStore#contributorsByVersion}. Keyed by {@code canonical_id}, not by session: the
 * rows outlive the draft.
 */
final class ContributorLedger {

    private final Jdbc jdbc;

    ContributorLedger( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    /**
     * Validates the arguments, then in one transaction: with {@code created} delete every row of the canonical id,
     * else the version's rows; then one insert per author key, in sorted order.
     */
    void record( final String canonicalId, final int pageVersion, final Set< String > authorKeys,
                 final boolean created ) throws SQLException {
        final List< String > keys = checkedKeys( canonicalId, pageVersion, authorKeys );
        jdbc.inTransaction( conn -> {
            if( created ) {
                jdbc.update( conn, "DELETE FROM page_version_contributor WHERE canonical_id = ?",
                    ps -> ps.setString( 1, canonicalId ) );
            } else {
                jdbc.update( conn, "DELETE FROM page_version_contributor WHERE canonical_id = ? AND page_version = ?",
                    ps -> {
                        ps.setString( 1, canonicalId );
                        ps.setInt( 2, pageVersion );
                    } );
            }
            for( final String key : keys ) {
                jdbc.update( conn, "INSERT INTO page_version_contributor ( canonical_id, page_version, author_key )"
                    + " VALUES ( ?, ?, ? )", ps -> {
                        ps.setString( 1, canonicalId );
                        ps.setInt( 2, pageVersion );
                        ps.setString( 3, key );
                    } );
            }
            return null;
        } );
    }

    /** The argument rules of {@code recordContributors}; returns the author keys, sorted. */
    private static List< String > checkedKeys( final String canonicalId, final int pageVersion,
                                               final Set< String > authorKeys ) {
        if( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId must not be blank" );
        }
        if( pageVersion < 1 ) {
            throw new IllegalArgumentException( "pageVersion must be >= 1: " + pageVersion );
        }
        Objects.requireNonNull( authorKeys, "authorKeys" );
        if( authorKeys.stream().anyMatch( k -> k == null || k.isBlank() ) ) {
            throw new IllegalArgumentException( "author keys must not be blank: " + authorKeys );
        }
        return authorKeys.stream().sorted().toList();
    }

    /** Version (ascending) to author keys, each list sorted by {@link String#compareTo}; empty when there are none. */
    Map< Integer, List< String > > byVersion( final String canonicalId ) throws SQLException {
        final List< Map.Entry< Integer, String > > rows = jdbc.query( "SELECT page_version, author_key"
            + " FROM page_version_contributor WHERE canonical_id = ?", ps -> ps.setString( 1, canonicalId ),
            rs -> Map.entry( rs.getInt( 1 ), rs.getString( 2 ) ) );
        final Map< Integer, List< String > > byVersion = new TreeMap<>();
        rows.forEach( r -> byVersion.computeIfAbsent( r.getKey(), v -> new ArrayList<>() ).add( r.getValue() ) );
        // sorted here, not by ORDER BY: String.compareTo is the contract's order whatever the database collation
        byVersion.replaceAll( ( v, keys ) -> keys.stream().sorted().toList() );
        return Collections.unmodifiableMap( byVersion );
    }
}

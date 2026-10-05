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
package com.wikantik.search.hybrid;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.search.hybrid.LuceneBm25ChunkIndex.IndexedChunk;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Database read side of {@link LuceneBm25ChunkIndex}: loads chunk projections from
 * {@code kg_content_chunks}. Extracted so the index class holds only Lucene concerns.
 */
final class Bm25ChunkStore {

    private static final String LOAD_ALL_SQL =
        "select id, page_name, text from kg_content_chunks";
    private static final String PAGES_FOR_IDS_SQL =
        "select distinct page_name from kg_content_chunks where id = any (?)";
    private static final String LOAD_BY_PAGES_SQL =
        "select id, page_name, text from kg_content_chunks where page_name = any (?)";

    private final Jdbc jdbc;

    Bm25ChunkStore( final DataSource dataSource ) {
        this.jdbc = new Jdbc( dataSource );
    }

    List< IndexedChunk > loadAll() {
        try {
            return jdbc.query( LOAD_ALL_SQL, ps -> { }, Bm25ChunkStore::readChunk );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Failed to load chunks for BM25 index", e );
        }
    }

    Set< String > pagesFor( final Set< UUID > ids ) throws SQLException {
        final UUID[] idArr = ids.toArray( new UUID[ 0 ] );
        return jdbc.withConnection( conn -> {
            final List< String > pages = jdbc.query( conn, PAGES_FOR_IDS_SQL,
                ps -> ps.setArray( 1, conn.createArrayOf( "uuid", idArr ) ),
                rs -> rs.getString( 1 ) );
            return new LinkedHashSet<>( pages );
        } );
    }

    List< IndexedChunk > loadByPages( final Set< String > pages ) throws SQLException {
        final String[] pageArr = pages.toArray( new String[ 0 ] );
        return jdbc.withConnection( conn -> jdbc.query( conn, LOAD_BY_PAGES_SQL,
            ps -> ps.setArray( 1, conn.createArrayOf( "text", pageArr ) ),
            Bm25ChunkStore::readChunk ) );
    }

    private static IndexedChunk readChunk( final ResultSet rs ) throws SQLException {
        return new IndexedChunk(
            UUID.fromString( rs.getString( "id" ) ), rs.getString( "page_name" ), rs.getString( "text" ) );
    }
}

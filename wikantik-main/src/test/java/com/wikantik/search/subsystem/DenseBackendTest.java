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
package com.wikantik.search.subsystem;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.RefreshableChunkVectorIndex;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class DenseBackendTest {

    private static final String EF_SEARCH_KEY = "wikantik.search.dense.pgvector.ef_search";

    @Test
    void parse_acceptsEveryWireValueCaseInsensitively() {
        for ( final DenseBackend backend : DenseBackend.values() ) {
            assertEquals( backend, DenseBackend.parse( backend.wire() ) );
            assertEquals( backend, DenseBackend.parse( backend.wire().toUpperCase( java.util.Locale.ROOT ) ) );
        }
    }

    @Test
    void wire_valuesAreTheDocumentedPropertyValues() {
        assertEquals( "inmemory", DenseBackend.INMEMORY.wire() );
        assertEquals( "pgvector", DenseBackend.PGVECTOR.wire() );
        assertEquals( "lucene-hnsw", DenseBackend.LUCENE_HNSW.wire() );
    }

    @Test
    void parse_rejectsUnknownValueWithTheMessageNamingEveryBackend() {
        final IllegalArgumentException e =
            assertThrows( IllegalArgumentException.class, () -> DenseBackend.parse( "bogus" ) );
        assertEquals( "wikantik.search.dense.backend must be one of "
            + "'inmemory', 'pgvector', 'lucene-hnsw', got: 'bogus'", e.getMessage() );
    }

    @Test
    void pgvector_readsEfSearchFromProperties_defaultingToOneHundred() throws Exception {
        final DataSource ds = mock( DataSource.class );
        assertEquals( 100, efSearchOf( DenseBackend.PGVECTOR.build( ds, "m", new Properties() ) ) );
        final Properties props = new Properties();
        props.setProperty( EF_SEARCH_KEY, "37" );
        assertEquals( 37, efSearchOf( DenseBackend.PGVECTOR.build( ds, "m", props ) ) );
    }

    @Test
    @RequiresPostgres
    void inmemoryAndLuceneHnsw_buildRefreshableIndexes() throws Exception {
        // Both indexes load their snapshot at construction, so they need a real database.
        final DataSource ds = PostgresTestDb.createDataSource();
        assertInstanceOf( RefreshableChunkVectorIndex.class, DenseBackend.INMEMORY.build( ds, "m", new Properties() ) );
        assertInstanceOf( RefreshableChunkVectorIndex.class, DenseBackend.LUCENE_HNSW.build( ds, "m", new Properties() ) );
    }

    @Test
    void pgvector_buildsANonRefreshableIndex() {
        final ChunkVectorIndex index = DenseBackend.PGVECTOR.build( mock( DataSource.class ), "m", new Properties() );
        assertFalse( index instanceof RefreshableChunkVectorIndex );
        assertNotNull( index );
    }

    private static int efSearchOf( final ChunkVectorIndex index ) throws Exception {
        final Field f = index.getClass().getDeclaredField( "efSearch" );
        f.setAccessible( true );
        return f.getInt( index );
    }
}

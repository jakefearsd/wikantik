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
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class DriveSourceConnectorTest {

    private static final String DOC = "application/vnd.google-apps.document";
    private static final String FOLDER = "application/vnd.google-apps.folder";

    /** In-memory Drive: folderId -> children; fileId -> bytes. */
    static final class FakeApi implements DriveApi {
        final Map<String,List<DriveFile>> tree = new HashMap<>();
        final Map<String,byte[]> media = new HashMap<>();
        boolean fail = false;
        public List<DriveFile> listFolder( String id ) throws IOException {
            if ( fail ) throw new IOException( "boom" );
            return tree.getOrDefault( id, List.of() );
        }
        public byte[] export( String id, String mime ) { return ( "MD:" + id ).getBytes( StandardCharsets.UTF_8 ); }
        public byte[] getMedia( String id ) { return media.getOrDefault( id, new byte[0] ); }
    }
    static DriveConfig cfg( List<String> folders, int max ) {
        return new DriveConfig( folders, max, "cid", "csecret", "https://w/cb", "text/markdown" );
    }
    static Supplier<Optional<String>> token( String t ) { return () -> Optional.ofNullable( t ); }

    @Test void fullArticleModeExportsDocsAndFetchesNativeTextSkipsOther() {
        FakeApi api = new FakeApi();
        api.tree.put( "root", List.of(
            new DriveFile( "d1", "Doc", DOC, "t", "l" ),
            new DriveFile( "m1", "note.md", "text/markdown", "t", "l" ),
            new DriveFile( "p1", "pic.png", "image/png", "t", "l" ) ) );
        api.media.put( "m1", "# note".getBytes( StandardCharsets.UTF_8 ) );
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( "rt" ), ( a, b, r ) -> api );

        SyncBatch batch = c.poll( null );
        assertEquals( 2, batch.items().size() );                       // png skipped
        assertTrue( batch.complete() );
        assertTrue( batch.tombstonedUris().isEmpty() );
        assertEquals( "gdrive://d1", batch.items().get( 0 ).sourceUri() );
        assertEquals( "text/markdown", batch.items().get( 0 ).contentType() );
        assertArrayEquals( "MD:d1".getBytes( StandardCharsets.UTF_8 ), batch.items().get( 0 ).content() );
        assertEquals( "text/markdown", batch.items().get( 1 ).contentType() );
        assertArrayEquals( "# note".getBytes( StandardCharsets.UTF_8 ), batch.items().get( 1 ).content() );
    }

    @Test void recursesSubfoldersAndHonorsMaxFiles() {
        FakeApi api = new FakeApi();
        api.tree.put( "root", List.of( new DriveFile( "sub", "Sub", FOLDER, "t", "l" ),
                                       new DriveFile( "d1", "A", DOC, "t", "l" ) ) );
        api.tree.put( "sub", List.of( new DriveFile( "d2", "B", DOC, "t", "l" ),
                                      new DriveFile( "d3", "C", DOC, "t", "l" ) ) );
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 2 ), token( "rt" ), ( a, b, r ) -> api );
        SyncBatch batch = c.poll( null );
        assertEquals( 2, batch.items().size() );   // capped at max_files=2, subfolder recursed
    }

    @Test void emptyRefreshTokenReturnsEmptyBatchAndNeverCallsFactory() {
        boolean[] built = { false };
        DriveApiFactory f = ( a, b, r ) -> { built[0] = true; return new FakeApi(); };
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( null ), f );
        SyncBatch batch = c.poll( null );
        assertTrue( batch.items().isEmpty() );
        assertTrue( batch.complete() );
        assertFalse( built[0], "factory must not be called without a refresh token" );
    }

    @Test void driveErrorDegradesToEmptyBatchNoThrow() {
        FakeApi api = new FakeApi();
        api.fail = true;
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( "rt" ), ( a, b, r ) -> api );
        SyncBatch batch = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( batch.items().isEmpty() );
        assertTrue( batch.complete() );
    }

    @Test void reflectsFullCorpusIsTrue() {
        DriveSourceConnector c = new DriveSourceConnector( "gd", cfg( List.of( "root" ), 500 ), token( "rt" ), ( a, b, r ) -> new FakeApi() );
        assertTrue( c.reflectsFullCorpus() );
    }
}

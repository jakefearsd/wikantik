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
package com.wikantik.connectors.filesystem;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FilesystemSourceConnectorTest {

    @Test void pollEmitsOneItemPerFileWithUriHashAndAcl( @TempDir Path root ) throws Exception {
        Files.createDirectories( root.resolve( "docs" ) );
        Files.writeString( root.resolve( "docs/a.md" ), "alpha" );
        Files.writeString( root.resolve( "b.md" ), "bravo" );

        SyncBatch batch = new FilesystemSourceConnector( "fs-test", root ).poll( null );

        assertTrue( batch.complete() );
        List< String > uris = batch.items().stream().map( SourceItem::sourceUri ).sorted().toList();
        assertEquals( List.of( "file:b.md", "file:docs/a.md" ), uris );
        SourceItem a = batch.items().stream().filter( i -> i.sourceUri().equals( "file:docs/a.md" ) ).findFirst().orElseThrow();
        assertEquals( List.of( "docs" ), a.aclRefs() );               // parent-dir name
        assertEquals( 64, a.contentHash().length() );                 // sha256 hex
        assertEquals( "docs/a.md", a.metadata().get( "path" ) );
        assertEquals( "alpha", new String( a.content() ) );
    }

    @Test void tombstonesAreEmptyConnectorSideAndCursorIsSet( @TempDir Path root ) throws Exception {
        Files.writeString( root.resolve( "x.md" ), "x" );
        SyncBatch b = new FilesystemSourceConnector( "fs", root ).poll( null );
        assertTrue( b.tombstonedUris().isEmpty() );                   // orchestrator derives deletions
        assertNotNull( b.nextCursor().value() );
    }
}

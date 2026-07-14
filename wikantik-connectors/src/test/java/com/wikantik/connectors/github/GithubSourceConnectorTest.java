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
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class GithubSourceConnectorTest {

    /** In-memory GitHub: fixed tree + path→bytes contents. */
    static final class FakeApi implements GithubApi {
        List< GithubFile > files = new ArrayList<>();
        Map< String, byte[] > content = new HashMap<>();
        boolean truncated = false;
        boolean failTree = false;
        String defaultBranch = "main";
        String lastBranch;
        public String defaultBranch() { return defaultBranch; }
        public TreeListing listTree( String branch ) throws IOException {
            lastBranch = branch;
            if ( failTree ) throw new IOException( "boom" );
            return new TreeListing( files, truncated );
        }
        public Optional< byte[] > rawContent( String path, String branch ) {
            return Optional.ofNullable( content.get( path ) );   // absent = 404
        }
        FakeApi with( String path, String body ) {
            files.add( new GithubFile( path, "sha-" + path, body.length() ) );
            content.put( path, body.getBytes( StandardCharsets.UTF_8 ) );
            return this;
        }
    }
    static GithubConfig cfg( String branch, String prefix, int max ) {
        return new GithubConfig( "acme/handbook", branch, prefix, max );
    }
    static Supplier< Optional< String > > token( String t ) { return () -> Optional.ofNullable( t ); }
    static GithubSourceConnector conn( GithubConfig c, Supplier< Optional< String > > t, GithubApi api ) {
        return new GithubSourceConnector( "gh", c, t, ( repo, tok ) -> api );
    }

    @Test void emitsMarkdownFilesOnlyRespectingPrefix() {
        FakeApi api = new FakeApi()
            .with( "README.md", "# readme" )
            .with( "docs/a.md", "# a" )
            .with( "docs/img.png", "binary" )
            .with( "src/Main.java", "code" );
        SyncBatch b = conn( cfg( "main", "docs/", 500 ), token( "t" ), api ).poll( null );
        Set< String > uris = new HashSet<>();
        for ( SourceItem i : b.items() ) uris.add( i.sourceUri() );
        assertEquals( Set.of( "github://acme/handbook/docs/a.md" ), uris );   // prefix + .md filter
        assertTrue( b.complete() );
    }

    @Test void noPrefixTakesAllMarkdownCaseInsensitive() {
        FakeApi api = new FakeApi().with( "README.MD", "# r" ).with( "b.md", "# b" );
        SyncBatch b = conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( 2, b.items().size() );
    }

    @Test void usesConfiguredBranchOrDefaultBranch() throws IOException {
        FakeApi api = new FakeApi().with( "a.md", "# a" );
        conn( cfg( "release", null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( "release", api.lastBranch );
        api.defaultBranch = "trunk";
        conn( cfg( null, null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( "trunk", api.lastBranch );
    }

    @Test void emptyTokenReturnsIncompleteEmptyBatchAndNeverCallsFactory() {
        boolean[] built = { false };
        GithubApiFactory f = ( repo, tok ) -> { built[0] = true; return new FakeApi(); };
        GithubSourceConnector c = new GithubSourceConnector( "gh", cfg( "main", null, 500 ), token( null ), f );
        SyncBatch b = c.poll( null );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete(), "couldn't enumerate must never read as empty source" );
        assertFalse( built[0], "factory must not be called without a token" );
    }

    @Test void apiErrorDegradesToEmptyIncompleteBatchNoThrow() {
        FakeApi api = new FakeApi();
        api.failTree = true;
        SyncBatch b = assertDoesNotThrow( () -> conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( null ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
        assertNull( b.nextCursor(), "failure returns the input cursor verbatim (null on first sync)" );
    }

    @Test void factoryThrowingDegradesToEmptyIncompleteBatchNoThrow() {
        GithubApiFactory throwing = ( repo, tok ) -> { throw new IllegalStateException( "bad" ); };
        GithubSourceConnector c = new GithubSourceConnector( "gh", cfg( "main", null, 500 ), token( "t" ), throwing );
        SyncBatch b = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
    }

    @Test void truncatedTreeDeliversItemsButMarksIncomplete() {
        FakeApi api = new FakeApi().with( "a.md", "# a" );
        api.truncated = true;
        SyncCursor in = new SyncCursor( "prev" );
        SyncBatch b = conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( in );
        assertEquals( 1, b.items().size(), "fetched items still delivered" );
        assertFalse( b.complete(), "truncated listing is not a trustworthy full snapshot" );
        assertEquals( in, b.nextCursor() );
    }

    @Test void perFile404IsSkippedWithoutTaint() {
        FakeApi api = new FakeApi().with( "a.md", "# a" );
        api.files.add( new GithubFile( "gone.md", "sha-gone", 3 ) );   // listed but no content → 404
        SyncBatch b = conn( cfg( "main", null, 500 ), token( "t" ), api ).poll( null );
        assertEquals( 1, b.items().size() );
        assertTrue( b.complete(), "404 = authoritative absence, batch stays trusted" );
    }

    @Test void honorsMaxFilesAndStaysComplete() {
        FakeApi api = new FakeApi().with( "a.md", "1" ).with( "b.md", "2" ).with( "c.md", "3" );
        SyncBatch b = conn( cfg( "main", null, 2 ), token( "t" ), api ).poll( null );
        assertEquals( 2, b.items().size() );
        assertTrue( b.complete(), "cap-hit is deliberate truncation (matches Drive)" );
    }

    @Test void reflectsFullCorpusIsTrue() {
        assertTrue( conn( cfg( "main", null, 500 ), token( "t" ), new FakeApi() ).reflectsFullCorpus() );
    }
}

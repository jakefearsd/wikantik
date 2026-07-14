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
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ConfluenceSourceConnectorTest {

    static final class FakeApi implements ConfluenceApi {
        List< ConfluencePage > pages = new ArrayList<>();
        boolean fail = false;
        int lastMax = -1;
        int skippedMalformed = 0;
        public PageListing listPages( int maxPages ) throws IOException {
            lastMax = maxPages;
            if ( fail ) throw new IOException( "boom" );
            List< ConfluencePage > capped = pages.size() > maxPages ? pages.subList( 0, maxPages ) : pages;
            return new PageListing( capped, skippedMalformed );
        }
    }
    static ConfluenceConfig cfg( int maxPages ) {
        return new ConfluenceConfig( "https://acme.atlassian.net", "ENG", "bot@acme.com", maxPages );
    }
    static Supplier< Optional< String > > token( String t ) { return () -> Optional.ofNullable( t ); }
    static ConfluenceSourceConnector conn( ConfluenceConfig c, Supplier< Optional< String > > t, ConfluenceApi api ) {
        return new ConfluenceSourceConnector( "cf", c, t, ( b, s, e, k ) -> api );
    }

    @Test void emitsOneItemPerPage() {
        FakeApi api = new FakeApi();
        api.pages.add( new ConfluencePage( "1", "A", 1, "/spaces/ENG/pages/1/A", "<p>a</p>" ) );
        api.pages.add( new ConfluencePage( "2", "B", 3, "/spaces/ENG/pages/2/B", "<p>b</p>" ) );
        SyncBatch b = conn( cfg( 500 ), token( "t" ), api ).poll( null );
        assertEquals( 2, b.items().size() );
        assertTrue( b.complete() );
        assertEquals( "confluence://ENG/1", b.items().get( 0 ).sourceUri() );
        assertEquals( "text/html", b.items().get( 0 ).contentType() );
        assertEquals( 500, api.lastMax, "maxPages is delegated to the api listing" );
    }

    @Test void emptyTokenReturnsIncompleteEmptyBatchAndNeverCallsFactory() {
        boolean[] built = { false };
        ConfluenceApiFactory f = ( b, s, e, k ) -> { built[0] = true; return new FakeApi(); };
        ConfluenceSourceConnector c = new ConfluenceSourceConnector( "cf", cfg( 500 ), token( null ), f );
        SyncBatch batch = c.poll( null );
        assertTrue( batch.items().isEmpty() );
        assertFalse( batch.complete() );
        assertFalse( built[0], "factory must not be called without a token" );
    }

    @Test void apiErrorDegradesToEmptyIncompleteBatchNoThrow() {
        FakeApi api = new FakeApi();
        api.fail = true;
        SyncCursor in = new SyncCursor( "prev" );
        SyncBatch b = assertDoesNotThrow( () -> conn( cfg( 500 ), token( "t" ), api ).poll( in ) );
        assertTrue( b.items().isEmpty() );
        assertFalse( b.complete() );
        assertEquals( in, b.nextCursor(), "failure returns the input cursor verbatim" );
    }

    @Test void factoryThrowingDegradesToEmptyIncompleteBatchNoThrow() {
        ConfluenceApiFactory throwing = ( b, s, e, k ) -> { throw new IllegalStateException( "bad" ); };
        ConfluenceSourceConnector c = new ConfluenceSourceConnector( "cf", cfg( 500 ), token( "t" ), throwing );
        SyncBatch batch = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( batch.items().isEmpty() );
        assertFalse( batch.complete() );
    }

    @Test void tokenSupplierThrowingDegradesToEmptyIncompleteBatchNoThrow() {
        Supplier< Optional< String > > failing = () -> { throw new IllegalStateException( "store down" ); };
        ConfluenceSourceConnector c = new ConfluenceSourceConnector( "cf", cfg( 500 ), failing, ( b, s, e, k ) -> new FakeApi() );
        SyncBatch batch = assertDoesNotThrow( () -> c.poll( null ) );
        assertTrue( batch.items().isEmpty() );
        assertFalse( batch.complete() );
    }

    @Test void malformedPagesInListingTaintBatch() {
        FakeApi api = new FakeApi();
        api.pages.add( new ConfluencePage( "1", "A", 1, "/spaces/ENG/pages/1/A", "<p>a</p>" ) );
        api.skippedMalformed = 1;
        SyncCursor in = new SyncCursor( "prev" );
        SyncBatch b = conn( cfg( 500 ), token( "t" ), api ).poll( in );
        assertEquals( 1, b.items().size() );
        assertFalse( b.complete() );
        assertEquals( in, b.nextCursor(), "taint returns the input cursor verbatim, no tombstones this cycle" );
    }

    @Test void reflectsFullCorpusIsTrue() {
        assertTrue( conn( cfg( 500 ), token( "t" ), new FakeApi() ).reflectsFullCorpus() );
    }
}

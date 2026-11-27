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
package com.wikantik.connectors.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import com.wikantik.connectors.http.EgressGuard;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Manual redirect following: bounded by {@link EgressGuard#MAX_REDIRECTS}, every hop validated. */
class HttpPageFetcherRedirectTest {

    private HttpServer server;
    private String base;
    private final HttpPageFetcher fetcher = new HttpPageFetcher( "T/1.0", Duration.ofSeconds( 5 ) );

    @BeforeEach
    void start() throws IOException {
        System.setProperty( EgressGuard.PROP_ALLOW_PRIVATE, "true" );   // the test server is on loopback
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        // /hop/N redirects to /hop/N-1 until /hop/0, which answers 200
        server.createContext( "/hop/", ex -> {
            final int n = Integer.parseInt( ex.getRequestURI().getPath().substring( "/hop/".length() ) );
            if ( n == 0 ) {
                final byte[] body = "done".getBytes();
                ex.sendResponseHeaders( 200, body.length );
                try ( OutputStream os = ex.getResponseBody() ) { os.write( body ); }
            } else {
                ex.getResponseHeaders().add( "Location", "/hop/" + ( n - 1 ) );   // relative, resolved by the guard
                ex.sendResponseHeaders( 302, -1 );
                ex.close();
            }
        } );
        server.createContext( "/nolocation", ex -> {
            ex.sendResponseHeaders( 302, -1 );
            ex.close();
        } );
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        System.clearProperty( EgressGuard.PROP_ALLOW_PRIVATE );
        server.stop( 0 );
    }

    @Test
    void followsExactlyMaxRedirectsAndReturnsTheFinalPage() {
        final FetchResult r = fetcher.fetch( base + "/hop/" + EgressGuard.MAX_REDIRECTS );
        assertEquals( 200, r.status() );
        assertEquals( "done", new String( r.body() ) );
        assertEquals( base + "/hop/0", r.finalUrl() );
    }

    @Test
    void oneRedirectBeyondTheCapIsReturnedAsTheRedirectItself() {
        final FetchResult r = fetcher.fetch( base + "/hop/" + ( EgressGuard.MAX_REDIRECTS + 1 ) );
        assertEquals( 302, r.status() );
        assertEquals( base + "/hop/1", r.finalUrl() );
    }

    @Test
    void redirectWithoutLocationFailsClosed() {
        assertEquals( 0, fetcher.fetch( base + "/nolocation" ).status() );
    }
}

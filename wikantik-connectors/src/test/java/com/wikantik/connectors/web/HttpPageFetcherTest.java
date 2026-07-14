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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fail-closed contract of {@link HttpPageFetcher#fetch}: a malformed/non-http URL must NOT throw
 *  (it would otherwise escape the crawler's poll() → 500 on the manual admin sync trigger),
 *  and an oversized response body must be dropped (status 0), never buffered whole (OOM defense). */
class HttpPageFetcherTest {

    private final HttpPageFetcher fetcher = new HttpPageFetcher( "WikantikCrawler/1.0", Duration.ofSeconds( 5 ) );

    private static HttpServer server;
    private static String base;

    @BeforeAll static void startServer() throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/small", ex -> respond( ex, new byte[512] ) );
        server.createContext( "/big", ex -> respond( ex, new byte[64 * 1024] ) );
        server.createContext( "/big-chunked", ex -> {   // length 0 → chunked: no Content-Length to pre-check
            ex.getResponseHeaders().add( "Content-Type", "text/html" );
            ex.sendResponseHeaders( 200, 0 );
            try ( OutputStream os = ex.getResponseBody() ) { os.write( new byte[64 * 1024] ); }
        } );
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterAll static void stopServer() { server.stop( 0 ); }

    private static void respond( final com.sun.net.httpserver.HttpExchange ex, final byte[] body ) throws java.io.IOException {
        ex.getResponseHeaders().add( "Content-Type", "text/html" );
        ex.sendResponseHeaders( 200, body.length );
        try ( OutputStream os = ex.getResponseBody() ) { os.write( body ); }
    }

    @Test void malformedUrlReturnsStatusZeroNotThrow() {
        assertEquals( 0, fetcher.fetch( "not a url" ).status() );        // URI.create → IllegalArgumentException
    }

    @Test void nonHttpSchemeReturnsStatusZeroNotThrow() {
        assertEquals( 0, fetcher.fetch( "ftp://host/file" ).status() );  // HttpRequest.newBuilder rejects non-http
    }

    @Test void schemeLessUrlReturnsStatusZeroNotThrow() {
        assertEquals( 0, fetcher.fetch( "example.com/page" ).status() );  // no scheme → rejected, fail-closed
    }

    @Test void bodyUnderCapIsReturnedIntact() {
        final HttpPageFetcher capped = new HttpPageFetcher( "T/1.0", Duration.ofSeconds( 5 ), 1024 );
        final FetchResult r = capped.fetch( base + "/small" );
        assertEquals( 200, r.status() );
        assertArrayEquals( new byte[512], r.body() );
    }

    @Test void bodyOverCapFailsClosedStatusZero() {
        final HttpPageFetcher capped = new HttpPageFetcher( "T/1.0", Duration.ofSeconds( 5 ), 1024 );
        assertEquals( 0, capped.fetch( base + "/big" ).status(), "oversized body must fail closed, not OOM" );
    }

    @Test void chunkedBodyOverCapFailsClosedStatusZero() {
        // no Content-Length header — the cap must be enforced while streaming, not from headers
        final HttpPageFetcher capped = new HttpPageFetcher( "T/1.0", Duration.ofSeconds( 5 ), 1024 );
        assertEquals( 0, capped.fetch( base + "/big-chunked" ).status() );
    }
}

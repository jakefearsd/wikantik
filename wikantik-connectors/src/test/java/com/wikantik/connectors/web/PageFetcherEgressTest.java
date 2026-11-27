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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SSRF policy must be applied by every connector HTTP client, not just exist: a live loopback
 * server stands in for "an internal service" and must receive no request at all.
 */
class PageFetcherEgressTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private String base;

    @BeforeEach
    void start() throws IOException {
        System.clearProperty( EgressGuard.PROP_ALLOW_PRIVATE );
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/", ex -> {
            hits.incrementAndGet();
            final byte[] body = "{}".getBytes();
            ex.sendResponseHeaders( 200, body.length );
            try ( OutputStream os = ex.getResponseBody() ) { os.write( body ); }
        } );
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop( 0 );
    }

    @Test
    void pageFetcherNeverContactsALoopbackTarget() {
        final FetchResult r = new HttpPageFetcher( "T/1.0", Duration.ofSeconds( 5 ) ).fetch( base + "/page" );
        assertEquals( 0, r.status() );
        assertEquals( 0, hits.get(), "the internal server must not have been contacted" );
    }
}

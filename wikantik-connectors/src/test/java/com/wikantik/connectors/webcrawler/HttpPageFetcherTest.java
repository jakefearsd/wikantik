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
package com.wikantik.connectors.webcrawler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fail-closed contract of {@link HttpPageFetcher#fetch}: a malformed/non-http URL must NOT throw
 *  (it would otherwise escape the crawler's poll() → 500 on the manual admin sync trigger).
 *  These cases fail before any network I/O, so the test needs no network. */
class HttpPageFetcherTest {

    private final HttpPageFetcher fetcher = new HttpPageFetcher( "WikantikCrawler/1.0", Duration.ofSeconds( 5 ) );

    @Test void malformedUrlReturnsStatusZeroNotThrow() {
        assertEquals( 0, fetcher.fetch( "not a url" ).status() );        // URI.create → IllegalArgumentException
    }

    @Test void nonHttpSchemeReturnsStatusZeroNotThrow() {
        assertEquals( 0, fetcher.fetch( "ftp://host/file" ).status() );  // HttpRequest.newBuilder rejects non-http
    }

    @Test void schemeLessUrlReturnsStatusZeroNotThrow() {
        assertEquals( 0, fetcher.fetch( "example.com/page" ).status() );  // no scheme → rejected, fail-closed
    }
}

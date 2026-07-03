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
package com.wikantik.its.rest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level proof of the {@code SpaRoutingFilter} conditional-GET path for
 * {@code /wiki/*} SSR pages: the first navigation returns a weak {@code ETag}
 * with {@code Cache-Control: private, no-cache}, and a second request that
 * echoes the {@code ETag} back as {@code If-None-Match} gets a bodyless 304
 * instead of a full re-render.
 */
public class WikiSsrConditionalGetIT {

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newHttpClient();
    }

    @Test
    void secondNavigationRevalidatesWith304() throws Exception {
        final URI uri = URI.create( baseUrl + "/wiki/Main" );

        final HttpResponse< String > first = client.send(
                HttpRequest.newBuilder( uri ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, first.statusCode() );
        final String etag = first.headers().firstValue( "ETag" ).orElseThrow(
                () -> new AssertionError( "First /wiki/Main response must carry an ETag" ) );
        assertTrue( first.headers().firstValue( "Cache-Control" ).orElse( "" ).contains( "no-cache" ),
                "First /wiki/Main response must carry Cache-Control: private, no-cache" );

        final HttpResponse< String > second = client.send(
                HttpRequest.newBuilder( uri ).header( "If-None-Match", etag ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 304, second.statusCode() );
        assertTrue( second.body().isEmpty(), "304 response must not carry a body" );
    }
}

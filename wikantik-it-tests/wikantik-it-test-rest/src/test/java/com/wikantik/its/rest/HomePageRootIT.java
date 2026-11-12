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
 * The site root must be server-rendered with the front page's content and a canonical link to
 * {@code {base}/}. Regression guard: the container's welcome-file handling used to hand {@code /}
 * to the default servlet as a static {@code /index.html}, bypassing {@code SpaRoutingFilter}, so
 * crawlers saw a bare loading shell with no canonical.
 */
public class HomePageRootIT {

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).build();
    }

    private HttpResponse<String> get( final String path ) throws Exception {
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "*/*" ).GET().build(), HttpResponse.BodyHandlers.ofString() );
    }

    @Test
    void root_isServerRenderedAndCanonical() throws Exception {
        final HttpResponse<String> resp = get( "/" );
        assertEquals( 200, resp.statusCode(), "GET / must serve the home page directly: " + resp.body() );
        final String body = resp.body();
        assertTrue( body.contains( "<link rel=\"canonical\" href=\"" + baseUrl + "/\"" ),
                "canonical must be the site root: " + body );
        assertTrue( body.contains( "<title>" ) && body.contains( "- Wikantik</title>" ),
                "server-rendered title expected: " + body );
        assertTrue( body.contains( "application/ld+json" ), "semantic head expected: " + body );
    }

    @Test
    void indexHtml_behavesLikeRoot() throws Exception {
        final HttpResponse<String> resp = get( "/index.html" );
        assertEquals( 200, resp.statusCode() );
        assertTrue( resp.body().contains( "<link rel=\"canonical\" href=\"" + baseUrl + "/\"" ),
                "canonical must be the site root: " + resp.body() );
    }

    @Test
    void wikiSlash_redirectsPermanentlyToRoot() throws Exception {
        final HttpResponse<String> resp = get( "/wiki/" );
        assertEquals( 301, resp.statusCode() );
        assertTrue( resp.headers().firstValue( "Location" ).orElse( "" ).endsWith( "/" ) );
    }
}

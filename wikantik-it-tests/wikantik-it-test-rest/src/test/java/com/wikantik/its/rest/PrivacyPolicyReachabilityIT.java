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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@code /privacy-policy.html} against being accidentally auth-gated.
 *
 * <p>Google and Facebook both perform an unauthenticated HTTP GET of the privacy
 * policy URL during OAuth app review. If a security filter or servlet mapping
 * ever inadvertently requires authentication for that path, app review will fail
 * and SSO sign-in will be blocked. This test catches that regression early.</p>
 */
public class PrivacyPolicyReachabilityIT {

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
    }

    private HttpResponse<String> get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    @Test
    void privacyPolicy_isPubliclyReachable() throws Exception {
        final HttpResponse<String> resp = get( "/privacy-policy.html" );
        assertEquals( 200, resp.statusCode(),
                "GET /privacy-policy.html must be 200 without authentication: " + resp.body() );
        assertTrue( resp.body().contains( "jakefear@gmail.com" ),
                "Privacy policy body must contain the contact email address: " + resp.body() );
    }
}

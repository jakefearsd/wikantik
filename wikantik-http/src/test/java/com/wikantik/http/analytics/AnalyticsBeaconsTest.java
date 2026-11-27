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
package com.wikantik.http.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsBeaconsTest {

    private static byte[] bytes( final String s ) {
        return s.getBytes( StandardCharsets.UTF_8 );
    }

    @Test
    void acceptsOnlyAnObjectWhosePayloadWebsiteIsExactlyTheConfiguredId() {
        assertTrue( AnalyticsBeacons.carriesWebsite( bytes( "{\"payload\":{\"website\":\"w1\"}}" ), "w1" ) );
        assertFalse( AnalyticsBeacons.carriesWebsite( bytes( "{\"payload\":{\"website\":\"W1\"}}" ), "w1" ) );
        assertFalse( AnalyticsBeacons.carriesWebsite( bytes( "{\"payload\":{\"website\":\"w1 \"}}" ), "w1" ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[1]", "\"w1\"", "null", "{}", "{\"payload\":5}", "{\"payload\":[]}",
            "{\"payload\":{}}", "{\"payload\":{\"website\":null}}", "{\"payload\":{\"website\":{}}}",
            "{\"payload\":{\"website\":[\"w1\"]}}", "{\"website\":\"w1\"}", "", "{broken" } )
    void refusesEverythingElse( final String body ) {
        assertFalse( AnalyticsBeacons.carriesWebsite( bytes( body ), "w1" ), body );
    }

    private static HttpServletRequest req( final String contentType, final String userAgent, final String cache ) {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( contentType ).when( req ).getContentType();
        Mockito.doReturn( "203.0.113.7" ).when( req ).getRemoteAddr();
        Mockito.doReturn( "9.9.9.9" ).when( req ).getHeader( "X-Forwarded-For" );
        Mockito.doReturn( userAgent ).when( req ).getHeader( "User-Agent" );
        Mockito.doReturn( cache ).when( req ).getHeader( "x-umami-cache" );
        return req;
    }

    private static HttpRequest build( final HttpServletRequest req ) {
        return AnalyticsBeacons.sendRequest( URI.create( "http://umami.test/api/send" ), Duration.ofSeconds( 3 ), req,
                bytes( "{}" ) );
    }

    @Test
    void upstreamRequestRelaysTheReadersAddressNotTheIncomingForwardedFor() {
        final HttpRequest r = build( req( "application/json", "UA/1", null ) );
        assertEquals( "POST", r.method() );
        assertEquals( URI.create( "http://umami.test/api/send" ), r.uri() );
        assertEquals( Duration.ofSeconds( 3 ), r.timeout().orElseThrow() );
        assertEquals( java.util.List.of( "203.0.113.7" ), r.headers().allValues( "X-Forwarded-For" ) );
        assertEquals( java.util.List.of( "UA/1" ), r.headers().allValues( "User-Agent" ) );
    }

    @Test
    void contentTypeIsForwardedVerbatimAndDefaultsToJsonWhenAbsent() {
        assertEquals( "text/plain;charset=UTF-8",
                build( req( "text/plain;charset=UTF-8", null, null ) ).headers().firstValue( "Content-Type" ).orElseThrow() );
        assertEquals( "application/json",
                build( req( null, null, null ) ).headers().firstValue( "Content-Type" ).orElseThrow() );
    }

    @Test
    void userAgentHeaderIsOmittedWhenTheReaderSentNone() {
        assertTrue( build( req( "application/json", null, null ) ).headers().firstValue( "User-Agent" ).isEmpty() );
    }

    @Test
    void umamiCacheTokenIsForwardedUpToTheLimitAndDroppedBeyondItOrWhenEmpty() {
        final String atLimit = "x".repeat( 4096 );
        assertEquals( atLimit, build( req( "application/json", null, atLimit ) ).headers()
                .firstValue( "x-umami-cache" ).orElseThrow() );
        assertTrue( build( req( "application/json", null, atLimit + "x" ) ).headers().firstValue( "x-umami-cache" ).isEmpty() );
        assertTrue( build( req( "application/json", null, "" ) ).headers().firstValue( "x-umami-cache" ).isEmpty() );
        assertTrue( build( req( "application/json", null, null ) ).headers().firstValue( "x-umami-cache" ).isEmpty() );
    }
}

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
package com.wikantik.work.runtime;

import com.wikantik.work.WorkOutbound;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The webhook port over the egress guard. IP literals only, so nothing here resolves a name or touches a network. */
class WikiWorkOutboundTest {

    private static final URI PUBLIC = URI.create( "https://93.184.215.14/hook/SECRETPATH" );

    private final List< URI > hopped = new ArrayList<>();
    private final List< Map< String, String > > headersSeen = new ArrayList<>();
    private final List< String > bodies = new ArrayList<>();

    private WikiWorkOutbound with( final WikiWorkOutbound.HopResult... answers ) {
        final int[] next = { 0 };
        return new WikiWorkOutbound( ( url, body, headers ) -> {
            hopped.add( url );
            bodies.add( body );
            headersSeen.add( headers );
            return answers[ Math.min( next[ 0 ]++, answers.length - 1 ) ];
        } );
    }

    private static WikiWorkOutbound.HopResult status( final int s ) {
        return new WikiWorkOutbound.HopResult( s, null );
    }

    @Test
    void loopbackIsRefusedAtCheck() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> with( status( 200 ) ).check( URI.create( "https://127.0.0.1/x" ) ) );
        assertEquals( "url", e.field() );
        assertFalse( e.getMessage().contains( "/x" ), "the host, never the path" );
    }

    @Test
    void aPrivateRangeIsRefusedAtCheck() {
        for ( final String host : new String[] { "10.0.0.5", "192.168.1.1", "172.16.0.9", "169.254.169.254", "[::1]", "[fd00::1]" } ) {
            assertThrows( WorkValidationException.class, () -> with( status( 200 ) ).check( URI.create( "https://" + host + "/x" ) ), host );
        }
    }

    @Test
    void aPublicAddressPasses() {
        assertDoesNotThrow( () -> with( status( 200 ) ).check( PUBLIC ) );
    }

    @Test
    void aSuccessfulPostCarriesTheBodyAndHeaders() throws IOException {
        assertEquals( 204, with( status( 204 ) ).post( PUBLIC, "application/json", "{\"a\":1}", Map.of( "X-Wikantik-Event", "status" ) ) );
        assertEquals( List.of( PUBLIC ), hopped );
        assertEquals( "application/json", headersSeen.get( 0 ).get( "Content-Type" ) );
        assertEquals( "status", headersSeen.get( 0 ).get( "X-Wikantik-Event" ) );
        assertEquals( "{\"a\":1}", bodies.get( 0 ) );
    }

    @Test
    void aRedirectToLoopbackIsRefusedAfterOneHop() {
        final WikiWorkOutbound out = with( new WikiWorkOutbound.HopResult( 307, "https://127.0.0.1/" ) );
        final WorkOutbound.Refused e = assertThrows( WorkOutbound.Refused.class,
            () -> out.post( PUBLIC, "application/json", "b", Map.of() ) );
        assertEquals( 1, hopped.size(), "only the first hop was sent" );
        assertFalse( e.getMessage().contains( "SECRETPATH" ) );
    }

    @Test
    void aSafeRedirectIsFollowedWithTheSameBody() throws IOException {
        final WikiWorkOutbound out = with( new WikiWorkOutbound.HopResult( 308, "https://93.184.215.15/next" ), status( 200 ) );
        assertEquals( 200, out.post( PUBLIC, "application/json", "same", Map.of() ) );
        assertEquals( List.of( PUBLIC, URI.create( "https://93.184.215.15/next" ) ), hopped );
        assertEquals( List.of( "same", "same" ), bodies );
    }

    @Test
    void aBlockedFirstHopSendsNothing() {
        assertThrows( WorkOutbound.Refused.class,
            () -> with( status( 200 ) ).post( URI.create( "https://10.0.0.5/x" ), "application/json", "b", Map.of() ) );
        assertTrue( hopped.isEmpty() );
    }

    @Test
    void a302IsReturnedAsAFailureNotFollowed() throws IOException {
        assertEquals( 302, with( new WikiWorkOutbound.HopResult( 302, "https://93.184.215.15/next" ) )
            .post( PUBLIC, "application/json", "b", Map.of() ) );
        assertEquals( 1, hopped.size() );
    }

    @Test
    void redirectsStopAtTheLimit() throws IOException {
        final int status = with( new WikiWorkOutbound.HopResult( 307, "/again" ) ).post( PUBLIC, "application/json", "b", Map.of() );
        assertEquals( 307, status, "the answer past the limit is a failure status" );
        assertEquals( com.wikantik.connectors.http.EgressGuard.MAX_REDIRECTS + 1, hopped.size() );
    }

    @Test
    void aRedirectWithoutALocationIsRefused() {
        assertThrows( WorkOutbound.Refused.class,
            () -> with( new WikiWorkOutbound.HopResult( 307, null ) ).post( PUBLIC, "application/json", "b", Map.of() ) );
    }

    @Test
    void aRedirectFromHttpsToHttpIsRefusedBeforeItIsSent() {
        final WikiWorkOutbound out = with( new WikiWorkOutbound.HopResult( 307, "http://93.184.215.15/h" ), status( 200 ) );
        final WorkOutbound.Refused e = assertThrows( WorkOutbound.Refused.class,
            () -> out.post( PUBLIC, "application/json", "signed-body", Map.of() ) );
        assertEquals( List.of( PUBLIC ), hopped, "the cleartext hop was never sent" );
        assertFalse( e.getMessage().contains( "93.184.215.15" ), "no address in the message" );
    }

    @Test
    void aPlainHttpFirstHopIsRefusedAtDelivery() {
        assertThrows( WorkOutbound.Refused.class,
            () -> with( status( 200 ) ).post( URI.create( "http://93.184.215.14/x" ), "application/json", "b", Map.of() ) );
        assertTrue( hopped.isEmpty() );
    }

    @Test
    void anIpv4MappedIpv6AddressIsRefusedAtCheckAndAtDelivery() {
        for ( final String host : new String[] { "[::ffff:10.0.0.1]", "[::ffff:127.0.0.1]", "[::ffff:169.254.169.254]" } ) {
            final URI uri = URI.create( "https://" + host + "/x" );
            assertThrows( WorkValidationException.class, () -> with( status( 200 ) ).check( uri ), host );
            assertThrows( WorkOutbound.Refused.class, () -> with( status( 200 ) ).post( uri, "application/json", "b", Map.of() ), host );
        }
        assertTrue( hopped.isEmpty() );
    }

    @Test
    void aRedirectToAnIpv4MappedLoopbackIsRefused() {
        final WikiWorkOutbound out = with( new WikiWorkOutbound.HopResult( 307, "https://[::ffff:127.0.0.1]/" ) );
        assertThrows( WorkOutbound.Refused.class, () -> out.post( PUBLIC, "application/json", "b", Map.of() ) );
        assertEquals( 1, hopped.size() );
    }

    @Test
    void aPostThatHasTakenTooLongStartsNoFurtherHop() {
        final long[] clock = { 0 };
        final List< URI > seen = new ArrayList<>();
        final WikiWorkOutbound out = new WikiWorkOutbound( ( url, body, headers ) -> {
            seen.add( url );
            clock[ 0 ] += WikiWorkOutbound.POST_DEADLINE.toNanos();
            return new WikiWorkOutbound.HopResult( 307, "https://93.184.215.15/next" );
        }, () -> clock[ 0 ] );
        assertThrows( WorkOutbound.Refused.class, () -> out.post( PUBLIC, "application/json", "b", Map.of() ) );
        assertEquals( 1, seen.size() );
    }

    @Test
    void theRealHopGivesUpOnAnEndpointThatDripsItsBody() throws Exception {
        final com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress( java.net.InetAddress.getLoopbackAddress(), 0 ), 0 );
        server.createContext( "/", exchange -> {
            exchange.sendResponseHeaders( 200, 0 );
            try ( java.io.OutputStream o = exchange.getResponseBody() ) {
                for ( int i = 0; i < 30; i++ ) {
                    o.write( 'x' );
                    o.flush();
                    Thread.sleep( 200 );
                }
            } catch ( final IOException | InterruptedException e ) {
                // the client hung up, which is what the test wants
                Thread.currentThread().interrupt();
            }
        } );
        server.start();
        try {
            final WikiWorkOutbound.HttpHop real = new WikiWorkOutbound.HttpHop( java.time.Duration.ofMillis( 700 ) );
            final long t0 = System.nanoTime();
            assertThrows( IOException.class, () -> real.send(
                URI.create( "http://127.0.0.1:" + server.getAddress().getPort() + "/" ), "{}", Map.of() ) );
            assertTrue( ( System.nanoTime() - t0 ) / 1_000_000 < 4_000, "returned long before the 6 s the body takes" );
        } finally {
            server.stop( 0 );
        }
    }

    @Test
    void theRealHopReturnsTheStatusOfAFastEndpoint() throws Exception {
        final com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress( java.net.InetAddress.getLoopbackAddress(), 0 ), 0 );
        server.createContext( "/", exchange -> {
            exchange.getResponseHeaders().add( "Location", "https://example.invalid/n" );
            exchange.sendResponseHeaders( 307, -1 );
            exchange.close();
        } );
        server.start();
        try {
            final WikiWorkOutbound.HopResult r = new WikiWorkOutbound.HttpHop( java.time.Duration.ofSeconds( 5 ) ).send(
                URI.create( "http://127.0.0.1:" + server.getAddress().getPort() + "/" ), "{}", Map.of( "X-A", "b" ) );
            assertEquals( 307, r.status() );
            assertEquals( "https://example.invalid/n", r.location() );
        } finally {
            server.stop( 0 );
        }
    }
}

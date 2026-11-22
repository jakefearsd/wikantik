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

import com.wikantik.connectors.http.EgressGuard;
import com.wikantik.connectors.http.EgressGuard.EgressBlockedException;
import com.wikantik.work.WorkOutbound;
import com.wikantik.work.WorkValidationException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The webhook port, over the connectors' SSRF egress policy ({@link EgressGuard}). The address is checked at save and
 * again before every send (DNS can change) and at every redirect hop. Redirects are never followed by the HTTP client:
 * a 307 or 308 (which keep the method and body) is followed here, one hop at a time, each re-validated, at most
 * {@link EgressGuard#MAX_REDIRECTS} times; any other 3xx is returned as the status, which the caller counts as a
 * failure. The response body is discarded. Messages name the host, never the path.
 *
 * <p>Every hop must be https, the first and each redirect target (a 307 to an http address would send the signed body
 * in the clear), so an http hop is a refused delivery. Time is bounded twice: one exchange has an overall deadline
 * that covers reading the response, not only the headers ({@link #HOP_DEADLINE}), and one post, redirects included,
 * starts no further hop once {@link #POST_DEADLINE} has passed.
 *
 * <p>Known limit: {@link EgressGuard} resolves the host to validate it and the HTTP client resolves it again to
 * connect, so a DNS server that answers differently the second time (rebinding, with a zero TTL) can steer a send to
 * an address the guard would have refused. The connector fetches share this posture; the JDK client offers no hook
 * to pin the validated address.
 */
public final class WikiWorkOutbound implements WorkOutbound {

    private static final Duration TIMEOUT = Duration.ofSeconds( 10 );
    /** The longest one exchange may take, headers and body together. */
    static final Duration HOP_DEADLINE = Duration.ofSeconds( 15 );
    /** After this, a post starts no further redirect hop. */
    static final Duration POST_DEADLINE = Duration.ofSeconds( 30 );
    private static final int TEMPORARY_REDIRECT = 307;
    private static final int PERMANENT_REDIRECT = 308;

    /** What one request answered: the status and the {@code Location} header, if any. */
    record HopResult( int status, String location ) {
    }

    /** One HTTP exchange with no redirect handling; replaced in tests so they need no network. */
    @FunctionalInterface
    interface Hop {
        HopResult send( URI url, String body, Map< String, String > headers ) throws IOException;
    }

    private final Hop hop;
    private final LongSupplier nanos;

    public WikiWorkOutbound() {
        this( new HttpHop( HOP_DEADLINE ) );
    }

    WikiWorkOutbound( final Hop hop ) {
        this( hop, System::nanoTime );
    }

    WikiWorkOutbound( final Hop hop, final LongSupplier nanos ) {
        this.hop = hop;
        this.nanos = nanos;
    }

    @Override
    public void check( final URI url ) {
        try {
            EgressGuard.check( url );
        } catch ( final EgressBlockedException e ) {
            throw new WorkValidationException( "url", e.getMessage() );
        }
    }

    @Override
    public int post( final URI url, final String contentType, final String body, final Map< String, String > headers )
            throws IOException {
        final Map< String, String > all = new LinkedHashMap<>( headers );
        all.put( "Content-Type", contentType );
        URI current = url;
        final long started = nanos.getAsLong();
        try {
            requireHttps( current );
            EgressGuard.check( current );
            for ( int hops = 0; ; hops++ ) {
                final HopResult result = hop.send( current, body, all );
                final boolean follows = ( result.status() == TEMPORARY_REDIRECT || result.status() == PERMANENT_REDIRECT )
                    && hops < EgressGuard.MAX_REDIRECTS;
                if ( !follows ) {
                    return result.status();
                }
                if ( nanos.getAsLong() - started >= POST_DEADLINE.toNanos() ) {
                    throw new Refused( "the endpoint took too long to answer" );
                }
                current = EgressGuard.followsSafely( current, result.location() );
                requireHttps( current );
            }
        } catch ( final EgressBlockedException e ) {
            throw new Refused( e.getMessage() );
        }
    }

    private static void requireHttps( final URI uri ) throws Refused {
        if ( !"https".equalsIgnoreCase( uri.getScheme() ) ) {
            throw new Refused( "a webhook is only sent over https" );
        }
    }

    /** The real exchange: a client that never follows redirects, with bounded timeouts and an overall deadline. */
    static final class HttpHop implements Hop {

        private final HttpClient client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER )
            .connectTimeout( TIMEOUT ).build();
        private final Duration deadline;

        HttpHop( final Duration deadline ) {
            this.deadline = deadline;
        }

        @Override
        public HopResult send( final URI url, final String body, final Map< String, String > headers )
                throws IOException {
            final HttpRequest.Builder request = HttpRequest.newBuilder( url ).timeout( TIMEOUT )
                .POST( HttpRequest.BodyPublishers.ofString( body ) );
            headers.forEach( request::header );
            final CompletableFuture< HttpResponse< Void > > pending =
                client.sendAsync( request.build(), HttpResponse.BodyHandlers.discarding() );
            try {
                final HttpResponse< Void > response = pending.get( deadline.toNanos(), TimeUnit.NANOSECONDS );
                return new HopResult( response.statusCode(), response.headers().firstValue( "Location" ).orElse( null ) );
            } catch ( final InterruptedException e ) {
                pending.cancel( true );
                Thread.currentThread().interrupt();
                throw new IOException( "interrupted while sending", e );
            } catch ( final TimeoutException e ) {
                pending.cancel( true );
                throw new IOException( "no complete answer within the deadline", e );
            } catch ( final ExecutionException e ) {
                throw new IOException( e.getCause() == null ? "send failed" : e.getCause().getClass().getSimpleName(), e );
            }
        }
    }
}

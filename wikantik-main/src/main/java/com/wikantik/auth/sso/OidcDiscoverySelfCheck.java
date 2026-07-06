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
package com.wikantik.auth.sso;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Startup reachability probe for the OIDC provider's discovery document.
 * <p>
 * pac4j fetches the discovery document (e.g.
 * {@code https://accounts.google.com/.well-known/openid-configuration})
 * <em>lazily</em>, on the first {@code /sso/login} request after boot — so when
 * this host cannot reach the identity provider, SSO fails only when a user tries
 * to log in, and the cause (a connect timeout deep inside pac4j) is buried in a
 * generic {@code sso_redirect_failed} redirect. That is exactly how a loss of
 * outbound network egress went unnoticed until a redeploy wiped pac4j's cached
 * metadata.
 * <p>
 * This probe runs the same fetch once at startup ({@link #checkAsync}, on a
 * daemon thread so it never blocks or breaks boot) and logs a single, explicit
 * line: {@code OK} when the provider is reachable and returns a valid discovery
 * document, or a loud {@code ERROR} naming the failure (unreachable / HTTP error
 * / not-a-discovery-document) so the operator sees it in the boot log instead of
 * discovering it via failed logins.
 */
public final class OidcDiscoverySelfCheck {

    private static final Logger LOG = LogManager.getLogger( OidcDiscoverySelfCheck.class );

    /** A minimally-valid OIDC discovery document must advertise this field. */
    private static final String REQUIRED_FIELD = "authorization_endpoint";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds( 10 );

    /** Result of a discovery-document reachability probe. */
    public enum Outcome {
        /** Reachable and returned a valid discovery document. */
        OK,
        /** Reachable but returned a non-2xx status. */
        HTTP_ERROR,
        /** Reachable and 2xx, but the body is not an OIDC discovery document. */
        INVALID_PAYLOAD,
        /** Could not be reached at all (DNS / connect / TLS / timeout). */
        UNREACHABLE
    }

    /** The status + body of a discovery fetch. */
    public record FetchResult( int status, String body ) { }

    /** Performs the discovery fetch; throws on any transport-level failure. */
    @FunctionalInterface
    public interface DiscoveryFetcher {
        FetchResult fetch( String discoveryUri ) throws Exception;
    }

    /**
     * Runs the probe with an injected fetcher, logs a single explicit result,
     * and returns the outcome. Never throws — a diagnostic must not itself break
     * anything.
     *
     * @param discoveryUri the OIDC discovery document URL
     * @param fetcher      how to fetch it (injected for testing)
     * @return the classified {@link Outcome}
     */
    public Outcome check( final String discoveryUri, final DiscoveryFetcher fetcher ) {
        try {
            final FetchResult r = fetcher.fetch( discoveryUri );
            if( r.status() < 200 || r.status() >= 300 ) {
                // LOG.error justified: fail-loud startup self-check; a bad discovery HTTP status breaks all SSO logins until an operator fixes it.
                LOG.error( "OIDC discovery self-check FAILED for {}: HTTP {} — the identity provider is reachable but did "
                        + "not return its discovery document. SSO login will not work until this is resolved.",
                        discoveryUri, r.status() );
                return Outcome.HTTP_ERROR;
            }
            if( r.body() == null || !r.body().contains( REQUIRED_FIELD ) ) {
                // LOG.error justified: fail-loud startup self-check; an invalid discovery payload breaks all SSO logins until an operator fixes it.
                LOG.error( "OIDC discovery self-check FAILED for {}: HTTP {} but the response is not a valid OIDC discovery "
                        + "document (no '{}'). SSO login will not work until this is resolved.",
                        discoveryUri, r.status(), REQUIRED_FIELD );
                return Outcome.INVALID_PAYLOAD;
            }
            LOG.info( "OIDC discovery self-check OK: {} is reachable and returned a valid discovery document.", discoveryUri );
            return Outcome.OK;
        } catch( final Exception e ) {
            // LOG.error justified: fail-loud startup self-check; an unreachable identity provider breaks all SSO logins until an operator fixes egress/DNS/TLS.
            LOG.error( "OIDC discovery self-check FAILED for {}: {} — the identity provider is UNREACHABLE from this host "
                    + "(check outbound network egress / DNS / TLS). SSO login will not work until this is resolved.",
                    discoveryUri, e.toString(), e );
            return Outcome.UNREACHABLE;
        }
    }

    /**
     * Runs {@link #check} against the live provider on a short-lived daemon
     * thread, so a slow or hung fetch never delays or breaks application
     * startup.
     *
     * @param discoveryUri the OIDC discovery document URL
     */
    public void checkAsync( final String discoveryUri ) {
        if( discoveryUri == null || discoveryUri.isBlank() ) {
            return;
        }
        final Thread t = new Thread( () -> check( discoveryUri, httpFetcher( DEFAULT_TIMEOUT ) ),
                "oidc-discovery-selfcheck" );
        t.setDaemon( true );
        t.start();
    }

    /**
     * The production fetcher: a bounded-timeout HTTPS GET via the JDK HTTP
     * client. Connect and request timeouts are both capped so a black-holed
     * network (the production failure mode) fails fast rather than hanging.
     *
     * @param timeout connect + request timeout
     * @return a fetcher backed by {@link HttpClient}
     */
    public static DiscoveryFetcher httpFetcher( final Duration timeout ) {
        return discoveryUri -> {
            final HttpClient client = HttpClient.newBuilder()
                    .connectTimeout( timeout )
                    .followRedirects( HttpClient.Redirect.NORMAL )
                    .build();
            final HttpRequest req = HttpRequest.newBuilder( URI.create( discoveryUri ) )
                    .timeout( timeout )
                    .GET()
                    .build();
            final HttpResponse< String > resp = client.send( req, HttpResponse.BodyHandlers.ofString() );
            return new FetchResult( resp.statusCode(), resp.body() );
        };
    }
}

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

    /**
     * {@link #checkAsync} production retry policy. A cold-start network race (DNS / route
     * not yet up when this host's own boot sequence reaches the probe) can fail a single
     * attempt and then work fine moments later — production lost that race on 2 of 5
     * restarts. A bounded retry absorbs that without masking a genuinely unreachable
     * provider: only failure across every attempt is reported as an error.
     */
    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds( 2 );

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

    /** The outcome and human-readable detail of a single fetch attempt. */
    private record Attempt( Outcome outcome, String detail, Throwable cause ) { }

    /**
     * Runs the probe with an injected fetcher, logs a single explicit result,
     * and returns the outcome. Never throws — a diagnostic must not itself break
     * anything. Single attempt, no retry — see the {@link #check(String, DiscoveryFetcher, int, Duration)}
     * overload for the retrying production policy used by {@link #checkAsync}.
     *
     * @param discoveryUri the OIDC discovery document URL
     * @param fetcher      how to fetch it (injected for testing)
     * @return the classified {@link Outcome}
     */
    public Outcome check( final String discoveryUri, final DiscoveryFetcher fetcher ) {
        return check( discoveryUri, fetcher, 1, Duration.ZERO );
    }

    /**
     * Runs the probe with an injected fetcher, retrying up to {@code maxAttempts} times
     * (sleeping {@code retryDelay} between each) before logging a failure. Never throws.
     * <p>
     * A single failed attempt at boot is not proof the provider is unreachable — it can be
     * a cold-start network race that resolves within seconds — so only a failure that
     * persists across every attempt is reported, and the resulting message says exactly how
     * many attempts were made so the log line reflects what was actually observed rather
     * than declaring an outage from one data point.
     *
     * @param discoveryUri the OIDC discovery document URL
     * @param fetcher      how to fetch it (injected for testing)
     * @param maxAttempts  total attempts to make (must be {@code >= 1}); 1 means no retry
     * @param retryDelay   delay between attempts (ignored when {@code maxAttempts <= 1})
     * @return the classified {@link Outcome} of the last attempt
     */
    public Outcome check( final String discoveryUri, final DiscoveryFetcher fetcher,
                           final int maxAttempts, final Duration retryDelay ) {
        if( maxAttempts < 1 ) {
            throw new IllegalArgumentException( "maxAttempts must be >= 1" );
        }
        Attempt last = null;
        for( int attempt = 1; attempt <= maxAttempts; attempt++ ) {
            last = attemptOnce( discoveryUri, fetcher );
            if( last.outcome() == Outcome.OK ) {
                if( attempt == 1 ) {
                    LOG.info( "OIDC discovery self-check OK: {} is reachable and returned a valid discovery document.",
                            discoveryUri );
                } else {
                    LOG.info( "OIDC discovery self-check OK: {} is reachable and returned a valid discovery document "
                            + "(attempt {} of {}, after {} earlier failed attempt(s) — likely a transient cold-start race).",
                            discoveryUri, attempt, maxAttempts, attempt - 1 );
                }
                return Outcome.OK;
            }
            if( attempt < maxAttempts ) {
                LOG.debug( "OIDC discovery self-check attempt {} of {} failed for {} ({}); retrying in {}.",
                        attempt, maxAttempts, discoveryUri, last.detail(), retryDelay );
                if( !sleepQuietly( retryDelay ) ) {
                    break;
                }
            }
        }
        logFailure( discoveryUri, maxAttempts, last );
        return last.outcome();
    }

    /** Performs exactly one fetch + classification; never throws. */
    private Attempt attemptOnce( final String discoveryUri, final DiscoveryFetcher fetcher ) {
        try {
            final FetchResult r = fetcher.fetch( discoveryUri );
            if( r.status() < 200 || r.status() >= 300 ) {
                return new Attempt( Outcome.HTTP_ERROR, "HTTP " + r.status(), null );
            }
            if( r.body() == null || !r.body().contains( REQUIRED_FIELD ) ) {
                return new Attempt( Outcome.INVALID_PAYLOAD,
                        "HTTP " + r.status() + " but response missing '" + REQUIRED_FIELD + "'", null );
            }
            return new Attempt( Outcome.OK, null, null );
        } catch( final Exception e ) {
            return new Attempt( Outcome.UNREACHABLE, e.toString(), e );
        }
    }

    /** Logs the final, honest failure after every attempt has been exhausted. */
    private void logFailure( final String discoveryUri, final int attemptsMade, final Attempt last ) {
        final String attemptsPhrase = attemptsMade == 1 ? "1 attempt" : attemptsMade + " attempts";
        switch( last.outcome() ) {
            case HTTP_ERROR ->
                // LOG.error justified: fail-loud startup self-check; a bad discovery HTTP status on every attempt breaks all SSO logins until an operator fixes it.
                LOG.error( "OIDC discovery self-check FAILED for {} after {}: {} — the identity provider was reachable "
                        + "but did not return its discovery document on any attempt. SSO login will not work until this is resolved.",
                        discoveryUri, attemptsPhrase, last.detail() );
            case INVALID_PAYLOAD ->
                // LOG.error justified: fail-loud startup self-check; an invalid discovery payload on every attempt breaks all SSO logins until an operator fixes it.
                LOG.error( "OIDC discovery self-check FAILED for {} after {}: {} — the response was not a valid OIDC "
                        + "discovery document on any attempt. SSO login will not work until this is resolved.",
                        discoveryUri, attemptsPhrase, last.detail() );
            case UNREACHABLE ->
                // LOG.error justified: fail-loud startup self-check; a still-unreachable identity provider after every retry breaks all SSO logins until an operator fixes egress/DNS/TLS.
                LOG.error( "OIDC discovery self-check FAILED for {} after {}: {} — the identity provider was UNREACHABLE "
                        + "from this host on every attempt (check outbound network egress / DNS / TLS); a single failed "
                        + "attempt at boot can be a transient cold-start race, but {} did not recover. SSO login will not "
                        + "work until this is resolved.",
                        discoveryUri, attemptsPhrase, attemptsPhrase, last.cause() );
            case OK -> { /* unreachable: OK returns before this is called */ }
        }
    }

    /**
     * Sleeps for {@code delay}, returning {@code false} (and restoring the interrupt flag)
     * if interrupted, so the caller can stop retrying instead of looping through a shutdown.
     */
    private static boolean sleepQuietly( final Duration delay ) {
        if( delay.isZero() || delay.isNegative() ) {
            return true;
        }
        try {
            Thread.sleep( delay.toMillis() );
            return true;
        } catch( final InterruptedException e ) {
            LOG.warn( "OIDC discovery self-check retry wait interrupted; giving up on remaining attempts.", e );
            Thread.currentThread().interrupt();
            return false;
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
        final Thread t = new Thread(
                () -> check( discoveryUri, httpFetcher( DEFAULT_TIMEOUT ), DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY ),
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

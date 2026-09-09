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

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Classification tests for {@link OidcDiscoverySelfCheck}. The fetcher is
 * injected so these never touch the network; they pin the mapping from a fetch
 * result (or failure) to the reported outcome, including the case that actually
 * bit production — a connect timeout to the identity provider.
 */
class OidcDiscoverySelfCheckTest {

    private static final String URI = "https://accounts.google.com/.well-known/openid-configuration";
    private static final String VALID_BODY =
            "{\"issuer\":\"https://accounts.google.com\",\"authorization_endpoint\":\"https://accounts.google.com/o/oauth2/v2/auth\"}";

    private final OidcDiscoverySelfCheck check = new OidcDiscoverySelfCheck();

    @Test
    void reachableAndValid_isOk() {
        assertEquals( OidcDiscoverySelfCheck.Outcome.OK,
                check.check( URI, uri -> new OidcDiscoverySelfCheck.FetchResult( 200, VALID_BODY ) ) );
    }

    @Test
    void non2xx_isHttpError() {
        assertEquals( OidcDiscoverySelfCheck.Outcome.HTTP_ERROR,
                check.check( URI, uri -> new OidcDiscoverySelfCheck.FetchResult( 503, "upstream down" ) ) );
    }

    @Test
    void okStatusButNotDiscoveryDocument_isInvalidPayload() {
        assertEquals( OidcDiscoverySelfCheck.Outcome.INVALID_PAYLOAD,
                check.check( URI, uri -> new OidcDiscoverySelfCheck.FetchResult( 200, "<html>captive portal</html>" ) ) );
    }

    @Test
    void connectTimeout_isUnreachable_andDoesNotThrow() {
        // This is the exact production failure: java.net.SocketTimeoutException: Connect timed out.
        assertEquals( OidcDiscoverySelfCheck.Outcome.UNREACHABLE,
                check.check( URI, uri -> { throw new SocketTimeoutException( "Connect timed out" ); } ) );
    }

    @Test
    void nullBodyWith200_isInvalidPayload() {
        assertEquals( OidcDiscoverySelfCheck.Outcome.INVALID_PAYLOAD,
                check.check( URI, uri -> new OidcDiscoverySelfCheck.FetchResult( 200, null ) ) );
    }

    // --- Bounded retry (P7): a lost cold-start network race must not be reported as a
    // startup outage when a later attempt succeeds. ---

    @Test
    void transientFailureThenSuccess_retriesAndReturnsOk() {
        // Exactly the production incident: the first attempt loses a cold-start network
        // race (connect timeout), a later attempt succeeds.
        final AtomicInteger calls = new AtomicInteger();
        final OidcDiscoverySelfCheck.Outcome outcome = check.check( URI, uri -> {
            if( calls.incrementAndGet() == 1 ) {
                throw new SocketTimeoutException( "Connect timed out" );
            }
            return new OidcDiscoverySelfCheck.FetchResult( 200, VALID_BODY );
        }, 3, Duration.ofMillis( 1 ) );

        assertEquals( OidcDiscoverySelfCheck.Outcome.OK, outcome );
        assertEquals( 2, calls.get(), "must retry after the first transient failure and succeed on the second" );
    }

    @Test
    void persistentFailure_exhaustsEveryConfiguredAttempt() {
        final AtomicInteger calls = new AtomicInteger();
        final OidcDiscoverySelfCheck.Outcome outcome = check.check( URI, uri -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException( "Connect timed out" );
        }, 3, Duration.ofMillis( 1 ) );

        assertEquals( OidcDiscoverySelfCheck.Outcome.UNREACHABLE, outcome );
        assertEquals( 3, calls.get(), "a genuinely unreachable provider must still surface clearly after every attempt fails" );
    }

    @Test
    void singleAttemptOverload_doesNotRetry() {
        // The original two-arg check() used by every test above (and by any other existing
        // caller) stays single-attempt with no retry delay — only checkAsync's production
        // path opts into the bounded retry.
        final AtomicInteger calls = new AtomicInteger();
        final OidcDiscoverySelfCheck.Outcome outcome = check.check( URI, uri -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException( "Connect timed out" );
        } );

        assertEquals( OidcDiscoverySelfCheck.Outcome.UNREACHABLE, outcome );
        assertEquals( 1, calls.get() );
    }

    @Test
    void maxAttemptsBelowOne_throws() {
        org.junit.jupiter.api.Assertions.assertThrows( IllegalArgumentException.class,
                () -> check.check( URI, uri -> new OidcDiscoverySelfCheck.FetchResult( 200, VALID_BODY ), 0, Duration.ZERO ) );
    }
}

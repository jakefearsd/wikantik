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
package com.wikantik.its.environment;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Starts the Selenide browser, absorbing the transient start-up failures that the
 * parallel IT gate produces.
 *
 * <p>Under {@code bin/run-tests.sh --parallel 4} four IT modules share one machine —
 * four Tomcats and (in the sso module) a Keycloak, alongside every container in the
 * Docker VM. Chrome start-up occasionally stalls long enough for Selenium's HTTP read
 * timeout to fire, which surfaces as
 * {@code SessionNotCreatedException: ... request timed out} and kills the whole test
 * class. The stall is transient — a second attempt normally starts in seconds — so it
 * is retried here rather than being allowed to fail the build.
 *
 * <p>Only {@link WebDriverException} is retried. An ordinary bug in a test must fail
 * on its first attempt rather than being run three times and reported confusingly.
 */
public final class BrowserStartup {

    private static final Logger LOG = LoggerFactory.getLogger( BrowserStartup.class );

    private BrowserStartup() {
    }

    /**
     * Ensures a live browser exists, retrying a stalled start-up.
     *
     * <p>A no-op when the current driver is healthy, so it is cheap to call before
     * every navigation. That is deliberately where it is called from
     * ({@code PageBuilder.openAs}) rather than from a {@code @BeforeAll}: nine IT
     * classes close the driver in their own {@code @BeforeEach} and re-open it per
     * test method, so a per-class hook would both miss those creations and waste a
     * browser start on each of them.
     *
     * <p>Residual gap: a test that closes the driver mid-method and then calls
     * {@code Selenide.open(...)} directly (rather than through a page object) still
     * creates its driver outside this guard. {@code SSOEdgeCaseIT} does this
     * deliberately; it has not been a flake source.
     */
    public static void ensureBrowserStarted() {
        withRetry( Env.TESTS_CONFIG_BROWSER_START_ATTEMPTS,
                   Duration.ofMillis( Env.TESTS_CONFIG_BROWSER_START_BACKOFF_MS ),
                   WebDriverRunner::getAndCheckWebDriver,
                   Selenide::closeWebDriver );
    }

    /**
     * Runs {@code create}, retrying it on browser start-up failure.
     *
     * @param maxAttempts total attempts allowed, including the first.
     * @param backoff     pause between attempts; the machine is contended, so this
     *                    gives whatever is hogging it a moment to finish.
     * @param create      starts the browser and returns it.
     * @param cleanup     discards a half-built driver before the next attempt. Without
     *                    it Selenide can hold a broken session and the retry inherits it.
     * @return whatever {@code create} returned on its first successful attempt.
     * @throws WebDriverException if every attempt failed; the last real failure is the cause.
     */
    public static <T> T withRetry( final int maxAttempts,
                                   final Duration backoff,
                                   final Supplier<T> create,
                                   final Runnable cleanup ) {
        WebDriverException lastFailure = null;

        for ( int attempt = 1; attempt <= maxAttempts; attempt++ ) {
            try {
                return create.get();
            } catch ( final WebDriverException e ) {
                lastFailure = e;
                final String summary = firstLine( e.getMessage() );
                if ( attempt == maxAttempts ) {
                    LOG.warn( "Browser start-up failed on attempt {}/{}; giving up: {}",
                              attempt, maxAttempts, summary );
                    break;
                }
                LOG.warn( "Browser start-up failed on attempt {}/{}, retrying in {} ms: {}",
                          attempt, maxAttempts, backoff.toMillis(), summary );
                discardHalfBuiltDriver( cleanup );
                pause( backoff );
            }
        }

        throw new WebDriverException(
            "Browser failed to start after " + maxAttempts + " attempts. On a contended machine "
            + "this usually means the IT gate is oversubscribed; see CLAUDE.md on --parallel.",
            lastFailure );
    }

    /**
     * Cleanup runs on a driver that is already broken, so it may well throw. That must
     * not mask the start-up failure we are actually retrying.
     */
    private static void discardHalfBuiltDriver( final Runnable cleanup ) {
        try {
            cleanup.run();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Ignoring failure while discarding the half-built driver: {}", e.toString() );
        }
    }

    private static void pause( final Duration backoff ) {
        if ( backoff.isZero() || backoff.isNegative() ) {
            return;
        }
        try {
            Thread.sleep( backoff.toMillis() );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new WebDriverException( "Interrupted while waiting to retry the browser start-up.", e );
        }
    }

    /** Selenium start-up messages carry a full host/stack dump; one line is enough for a log. */
    private static String firstLine( final String message ) {
        if ( message == null ) {
            return "(no message)";
        }
        final int newline = message.indexOf( '\n' );
        return newline < 0 ? message.trim() : message.substring( 0, newline ).trim();
    }
}

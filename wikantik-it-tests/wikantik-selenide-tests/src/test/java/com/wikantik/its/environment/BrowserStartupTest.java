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

import org.junit.jupiter.api.Test;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the IT-gate flake in which a transient browser start-up
 * failure aborted an entire test class.
 *
 * <p>Observed 2026-08-20 under {@code bin/run-tests.sh --parallel 4}: while four
 * IT modules contended for the machine, {@code SAMLLoginIT}'s driver creation hung
 * until Selenium's 180 s HTTP read timeout fired and the class died with
 * {@code SessionNotCreatedException: ... request timed out}. The two browser classes
 * either side of it in the same module started fine, so the stall was transient —
 * exactly the shape a retry absorbs. Before this guard there was no retry anywhere
 * on driver creation, so a single stall was a hard build failure.
 */
class BrowserStartupTest {

    /** The exact exception Selenium raises when a session cannot be established. */
    private static SessionNotCreatedException sessionTimeout() {
        return new SessionNotCreatedException( "Could not start a new session. request timed out" );
    }

    @Test
    void succeedsOnFirstAttemptWithoutCleaningUp() {
        final WebDriver driver = mock( WebDriver.class );
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger cleanups = new AtomicInteger();

        final WebDriver result = BrowserStartup.withRetry( 3, Duration.ZERO,
            () -> { creates.incrementAndGet(); return driver; },
            cleanups::incrementAndGet );

        assertSame( driver, result, "The created driver must be returned unchanged." );
        assertEquals( 1, creates.get(), "A healthy start must not be retried." );
        assertEquals( 0, cleanups.get(), "Cleanup must not run when the first attempt succeeds." );
    }

    /**
     * The defect this class exists for: one transient {@code SessionNotCreatedException}
     * must be absorbed, not propagated to the test class.
     */
    @Test
    void retriesAndSucceedsAfterATransientSessionFailure() {
        final WebDriver driver = mock( WebDriver.class );
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger cleanups = new AtomicInteger();

        final Supplier<WebDriver> flaky = () -> {
            if ( creates.incrementAndGet() == 1 ) {
                throw sessionTimeout();
            }
            return driver;
        };

        final WebDriver result = BrowserStartup.withRetry( 3, Duration.ZERO, flaky, cleanups::incrementAndGet );

        assertSame( driver, result, "The second attempt's driver must be returned." );
        assertEquals( 2, creates.get(), "Exactly one retry was needed." );
        assertEquals( 1, cleanups.get(), "The half-built driver must be cleaned up before retrying." );
    }

    @Test
    void givesUpAfterMaxAttemptsAndKeepsTheUnderlyingCause() {
        final AtomicInteger creates = new AtomicInteger();
        final SessionNotCreatedException last = sessionTimeout();

        final WebDriverException thrown = assertThrows( WebDriverException.class, () ->
            BrowserStartup.withRetry( 3, Duration.ZERO,
                () -> { creates.incrementAndGet(); throw last; },
                () -> { } ) );

        assertEquals( 3, creates.get(), "Every permitted attempt must be used before giving up." );
        assertSame( last, thrown.getCause(), "The real Selenium failure must remain the cause." );
        assertTrue( thrown.getMessage().contains( "3" ),
            "The message must say how many attempts were made, so the log explains itself: " + thrown.getMessage() );
    }

    /**
     * A retry loop that swallows ordinary bugs would turn a NullPointerException into
     * three NullPointerExceptions and a confusing report. Only browser start-up
     * failures are transient; everything else must surface immediately.
     */
    @Test
    void doesNotRetryNonBrowserFailures() {
        final AtomicInteger creates = new AtomicInteger();

        assertThrows( IllegalStateException.class, () ->
            BrowserStartup.withRetry( 3, Duration.ZERO,
                () -> { creates.incrementAndGet(); throw new IllegalStateException( "a real bug" ); },
                () -> { } ) );

        assertEquals( 1, creates.get(), "A non-browser failure must not be retried." );
    }
}

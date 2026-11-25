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
package com.wikantik.insights.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderAnalyticsSchedulerTest {

    private static boolean threadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch( t -> t.getName().equals( ReaderAnalyticsScheduler.THREAD_NAME ) && t.isAlive() );
    }

    @Test
    void aTickThatThrowsDoesNotKillTheSchedule() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch twice = new CountDownLatch( 2 );
        final ReaderAnalyticsScheduler s = new ReaderAnalyticsScheduler( () -> {
            calls.incrementAndGet();
            twice.countDown();
            throw new IllegalStateException( "umami down" );
        }, 10, 20, TimeUnit.MILLISECONDS );
        try {
            s.start();
            assertTrue( twice.await( 5, TimeUnit.SECONDS ), "second tick must still run" );
        } finally {
            s.stop();
        }
        assertTrue( calls.get() >= 2 );
    }

    @Test
    void stopEndsTheThread() throws Exception {
        final ReaderAnalyticsScheduler s = new ReaderAnalyticsScheduler( () -> { }, 10, 20, TimeUnit.MILLISECONDS );
        s.start();
        assertTrue( threadAlive() );
        s.stop();
        final long deadline = System.currentTimeMillis() + 5000;
        while ( threadAlive() && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 20 );
        }
        assertFalse( threadAlive() );
    }

    @Test
    void firstRunIsFiveMinutesOutAndIntervalIsInHours() {
        assertEquals( 5, ReaderAnalyticsScheduler.INITIAL_DELAY_MINUTES );
    }

    @Test
    void startTwiceDoesNotLeakASecondThread() {
        final ReaderAnalyticsScheduler s = new ReaderAnalyticsScheduler( () -> { }, 1, 1, TimeUnit.HOURS );
        try {
            s.start();
            s.start();
            final long n = Thread.getAllStackTraces().keySet().stream()
                .filter( t -> t.getName().equals( ReaderAnalyticsScheduler.THREAD_NAME ) && t.isAlive() ).count();
            assertEquals( 1, n );
        } finally {
            s.stop();
        }
    }
}

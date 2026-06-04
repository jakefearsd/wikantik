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
package com.wikantik.providers;

import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency contract for the post-RWLock {@link VersioningFileProvider}.
 *
 * <p>32 readers + 1 writer at high churn rate. Without the RWLock these tests
 * would race on the in-place file write and occasionally return torn content;
 * with the lock, readers see only valid values that were actually written.</p>
 *
 * <p>The previous {@code synchronized} on {@code getPageText} blocked all
 * concurrent readers — these tests would still pass under that regime (just
 * slowly). The cases that previously FAILED were the three writers
 * ({@code deletePage}, {@code deleteVersion}, {@code movePage}) that were
 * unlocked entirely; those races are also closed by VP-T7.</p>
 *
 * <p>Construction follows the same pattern as {@link VersioningFileProviderCITest}:
 * a {@link TestEngine} built from {@code /wikantik-vers-custom.properties}, with
 * page access via {@code engine.getManager(PageManager.class).getProvider()}.</p>
 */
class VersioningFileProviderConcurrencyTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    /**
     * 32 threads each read the same page 200 times. Every read must return
     * exactly the seeded text (modulo the trailing {@code \r\n} the provider
     * appends). No deadlock — the test is bounded by a 30-second timeout.
     */
    @Test
    @Timeout( 60 )
    void thirtyTwoConcurrentReaders_seeConsistentText() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        engine = TestEngine.build( props );

        final String seedText = "expected body content for concurrent read test";
        engine.saveText( "ReadTestPage", seedText );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();

        final int threads = 32;
        final int iterations = 200;
        final ExecutorService exec = Executors.newFixedThreadPool( threads );
        final CountDownLatch start = new CountDownLatch( 1 );
        final List< Future< Boolean > > futures = new ArrayList<>();

        for ( int t = 0; t < threads; t++ ) {
            futures.add( exec.submit( () -> {
                start.await();
                for ( int i = 0; i < iterations; i++ ) {
                    final String text = provider.getPageText( "ReadTestPage",
                        PageProvider.LATEST_VERSION );
                    // Provider may append \r\n; use startsWith to tolerate that
                    if ( text == null || !text.startsWith( seedText ) ) {
                        return false;
                    }
                }
                return true;
            } ) );
        }

        start.countDown();

        for ( final Future< Boolean > f : futures ) {
            assertTrue( f.get( 30, TimeUnit.SECONDS ),
                "A reader saw text other than the seeded value" );
        }

        exec.shutdown();
        assertTrue( exec.awaitTermination( 5, TimeUnit.SECONDS ),
            "Reader pool did not shut down — possible deadlock" );
    }

    /**
     * 16 reader threads run concurrently with 1 writer that does 20 sequential
     * saves. Every read must return text that begins with one of the values
     * actually written — never a partial / torn payload.
     */
    @Test
    @Timeout( 120 )
    void readDuringWrite_neverSeesTornContent() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        engine = TestEngine.build( props );

        final String v1 = "v1";
        engine.saveText( "ChurnPage", v1 );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();

        final int readers = 16;
        final int writes = 20;
        final Set< String > validPrefixes = ConcurrentHashMap.newKeySet();
        validPrefixes.add( v1 );

        final ExecutorService exec = Executors.newFixedThreadPool( readers + 1 );
        final CountDownLatch start = new CountDownLatch( 1 );
        final AtomicReference< String > sawInvalid = new AtomicReference<>();
        final AtomicBoolean writerDone = new AtomicBoolean( false );

        // Writer thread — 20 sequential saves; each value is registered in
        // validPrefixes BEFORE the save so readers can never observe a value
        // that has not yet been added to the valid set.
        exec.submit( () -> {
            start.await();
            try {
                for ( int w = 0; w < writes; w++ ) {
                    final String value = "v" + ( w + 2 );
                    validPrefixes.add( value );
                    engine.saveText( "ChurnPage", value );
                    Thread.sleep( 2 );
                }
            } finally {
                writerDone.set( true );
            }
            return null;
        } );

        // Reader threads — spin until the writer is done, checking every read
        for ( int r = 0; r < readers; r++ ) {
            exec.submit( () -> {
                start.await();
                while ( !writerDone.get() ) {
                    final String got = provider.getPageText( "ChurnPage",
                        PageProvider.LATEST_VERSION );
                    if ( got != null ) {
                        // Provider may append \r\n; strip it for prefix matching
                        final String trimmed = got.trim();
                        final boolean valid = validPrefixes.stream()
                            .anyMatch( trimmed::startsWith );
                        if ( !valid ) {
                            sawInvalid.compareAndSet( null, got );
                            return null;
                        }
                    }
                }
                return null;
            } );
        }

        start.countDown();
        exec.shutdown();
        assertTrue( exec.awaitTermination( 90, TimeUnit.SECONDS ),
            "Test did not complete — possible deadlock" );

        final String invalid = sawInvalid.get();
        assertNull( invalid, "A reader saw text never written: [" + invalid + "]" );
    }
}

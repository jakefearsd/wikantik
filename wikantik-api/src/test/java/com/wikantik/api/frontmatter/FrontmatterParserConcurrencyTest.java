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
package com.wikantik.api.frontmatter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the per-thread reuse of the hardened SnakeYAML parser. The parser is
 * built once per thread (a {@link ThreadLocal}) rather than per call, to remove
 * the dominant frontmatter-parse allocation/CPU cost on the retrieval path. A
 * SnakeYAML {@code Yaml} is NOT thread-safe, so this test fails if a single
 * shared instance is reused across threads (the naive way to avoid per-call
 * construction) — distinct frontmatter parsed concurrently must each round-trip
 * exactly.
 */
class FrontmatterParserConcurrencyTest {

    @Test
    void concurrent_parse_of_distinct_frontmatter_is_correct() throws Exception {
        final int threads = 16;
        final int iterations = 500;
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        try {
            final CountDownLatch start = new CountDownLatch( 1 );
            final AtomicInteger failures = new AtomicInteger();
            final List< Future< ? > > futures = new ArrayList<>();

            for ( int t = 0; t < threads; t++ ) {
                final int id = t;
                futures.add( pool.submit( () -> {
                    try {
                        start.await();
                        for ( int i = 0; i < iterations; i++ ) {
                            final String title = "T" + id + "_" + i;
                            final String text = "---\n"
                                + "title: " + title + "\n"
                                + "type: report\n"
                                + "count: " + i + "\n"
                                + "---\nbody-" + id + "-" + i + "\n";
                            final ParsedPage p = FrontmatterParser.parse( text );
                            if ( !title.equals( p.metadata().get( "title" ) )
                                || !"report".equals( p.metadata().get( "type" ) )
                                || !Integer.valueOf( i ).equals( p.metadata().get( "count" ) )
                                || !( "body-" + id + "-" + i ).equals( p.body().strip() ) ) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch ( final Throwable e ) {
                        // A shared, non-thread-safe Yaml throws ConcurrentModification /
                        // composer-state errors under load — count them as failures.
                        failures.incrementAndGet();
                    }
                } ) );
            }

            start.countDown();
            for ( final Future< ? > f : futures ) {
                f.get( 60, TimeUnit.SECONDS );
            }
            assertEquals( 0, failures.get(),
                "every concurrent frontmatter parse must round-trip exactly; "
                + "a shared mutable Yaml corrupts results across threads" );
        } finally {
            pool.shutdownNow();
        }
    }
}

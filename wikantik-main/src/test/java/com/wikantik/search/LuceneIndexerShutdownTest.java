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
package com.wikantik.search;

import com.wikantik.TestEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Regression test for the background-indexer thread leak: {@code engine.stop()}
 * sets the {@code WikiBackgroundThread} kill flag, but the Lucene updater's
 * {@code startupTask()} used to sit in a monolithic 60-second initial sleep that
 * never checked it — so every stopped engine leaked a non-daemon
 * "JSPWiki Lucene Indexer" thread which later ran a full reindex against a dead
 * engine. Across a ~5k-test suite that is hundreds of leaked threads doing
 * surprise background work — a prime carrier for cross-test flakiness.
 */
class LuceneIndexerShutdownTest {

    private static Set< Thread > indexerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter( t -> t.isAlive() && "JSPWiki Lucene Indexer".equals( t.getName() ) )
                .collect( Collectors.toSet() );
    }

    @Test
    void stoppedEngineDoesNotLeakIndexerThread() throws Exception {
        final Set< Thread > preExisting = indexerThreads();

        final TestEngine engine = TestEngine.build();
        engine.saveText( "IndexerShutdownProbe", "content" );
        engine.stop();

        // The polling fix bounds shutdown latency to ~1 second; allow 5 for slow CI.
        final long deadline = System.currentTimeMillis() + 5000;
        List< Thread > leaked;
        do {
            leaked = indexerThreads().stream()
                    .filter( t -> !preExisting.contains( t ) )
                    .collect( Collectors.toList() );
            if ( leaked.isEmpty() ) {
                break;
            }
            Thread.sleep( 100 );
        } while ( System.currentTimeMillis() < deadline );

        Assertions.assertTrue( leaked.isEmpty(),
                "engine.stop() must terminate the Lucene indexer thread; still alive: " + leaked );
    }
}

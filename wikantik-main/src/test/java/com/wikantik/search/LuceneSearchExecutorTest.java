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

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class LuceneSearchExecutorTest {

    /**
     * Nothing shuts this pool down, so a non-daemon idle worker kept a Tomcat stopped with
     * shutdown.sh alive for up to its one-minute keep-alive after the port was released — long
     * enough for bin/redeploy.sh to start the next JVM while the old one still held the ontology
     * TDB2 lock.
     */
    @Test
    void searchThreadsAreNamedDaemonsSoTheyNeverHoldTheJvmOpen() throws Exception {
        final ExecutorService executor = LuceneSearchProvider.newSearchExecutor();
        try {
            final Thread worker = executor.submit( Thread::currentThread ).get();
            assertTrue( worker.isDaemon(), "search worker must not keep the JVM alive" );
            assertTrue( worker.getName().startsWith( "lucene-search-" ), worker.getName() );
        } finally {
            executor.shutdownNow();
        }
    }
}

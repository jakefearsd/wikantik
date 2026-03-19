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
package com.wikantik.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Concurrency integration tests exercising parallel MCP access.
 */
public class McpConcurrencyIT extends WithMcpTestSetup {

    @Test
    public void parallelReadsDoNotInterfere() throws Exception {
        final String pageName = uniquePageName( "ConcRead" );
        mcp.writePage( pageName, "Concurrent read content" );

        final int threads = 5;
        final int readsPerThread = 10;
        final ExecutorService executor = Executors.newFixedThreadPool( threads );
        final List< Future< Boolean > > futures = new ArrayList<>();

        for ( int t = 0; t < threads; t++ ) {
            futures.add( executor.submit( () -> {
                for ( int r = 0; r < readsPerThread; r++ ) {
                    final Map< String, Object > result = mcp.readPage( pageName );
                    if ( !Boolean.TRUE.equals( result.get( "exists" ) ) ) {
                        return false;
                    }
                    final String content = McpTestClient.normalizeCrlf( ( String ) result.get( "content" ) );
                    if ( !content.contains( "Concurrent read content" ) ) {
                        return false;
                    }
                }
                return true;
            } ) );
        }

        executor.shutdown();
        Assertions.assertTrue( executor.awaitTermination( 60, TimeUnit.SECONDS ) );

        for ( final Future< Boolean > f : futures ) {
            Assertions.assertTrue( f.get(), "All parallel reads should succeed with correct content" );
        }
    }

    @Test
    public void parallelWritesToDifferentPages() throws Exception {
        final int threads = 5;
        final ExecutorService executor = Executors.newFixedThreadPool( threads );
        final List< Future< String > > futures = new ArrayList<>();

        for ( int t = 0; t < threads; t++ ) {
            final int threadIdx = t;
            futures.add( executor.submit( () -> {
                final String pageName = uniquePageName( "ConcWrite" + threadIdx );
                final String content = "Thread " + threadIdx + " content";
                mcp.writePage( pageName, content );
                return pageName;
            } ) );
        }

        executor.shutdown();
        Assertions.assertTrue( executor.awaitTermination( 60, TimeUnit.SECONDS ) );

        for ( int t = 0; t < threads; t++ ) {
            final String pageName = futures.get( t ).get();
            final Map< String, Object > result = mcp.readPage( pageName );
            Assertions.assertEquals( true, result.get( "exists" ), "Page " + pageName + " should exist" );
            final String content = McpTestClient.normalizeCrlf( ( String ) result.get( "content" ) );
            Assertions.assertTrue( content.contains( "Thread " + t + " content" ),
                    "Page should have correct thread-specific content" );
        }
    }

    @Test
    public void multipleClientsCanCoexist() throws Exception {
        final int clientCount = 3;
        final ExecutorService executor = Executors.newFixedThreadPool( clientCount );
        final List< Future< Boolean > > futures = new ArrayList<>();

        for ( int c = 0; c < clientCount; c++ ) {
            final int clientIdx = c;
            futures.add( executor.submit( () -> {
                try ( final McpTestClient client = McpTestClient.create() ) {
                    final String pageName = uniquePageName( "MultiClient" + clientIdx );
                    client.writePage( pageName, "Client " + clientIdx );
                    final Map< String, Object > result = client.readPage( pageName );
                    return Boolean.TRUE.equals( result.get( "exists" ) );
                }
            } ) );
        }

        executor.shutdown();
        Assertions.assertTrue( executor.awaitTermination( 60, TimeUnit.SECONDS ) );

        for ( final Future< Boolean > f : futures ) {
            Assertions.assertTrue( f.get(), "Each independent client session should work correctly" );
        }
    }
}

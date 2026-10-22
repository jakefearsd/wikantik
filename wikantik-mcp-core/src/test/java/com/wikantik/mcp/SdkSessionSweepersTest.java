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
package com.wikantik.mcp;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SdkSessionSweepersTest {

    private static final String SWEEPER_THREAD = "streamable-http-server-transport-session-sweeper";

    private static List< Thread > liveSweeperThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter( t -> t.isAlive() && t.getName().startsWith( SWEEPER_THREAD ) )
                .toList();
    }

    private static void awaitNoSweeperThreads() throws InterruptedException {
        for ( int i = 0; i < 50 && !liveSweeperThreads().isEmpty(); i++ ) {
            Thread.sleep( 100 );
        }
    }

    /**
     * A Tomcat stopped with shutdown.sh cannot exit while a non-daemon thread lives, and the next
     * start then fails to take the ontology TDB2 lock. The MCP SDK's idle-session sweeper runs on
     * such a thread and closing the transport never disposes its scheduler.
     */
    @Test
    void disposingTheTrackedSweepersEndsTheThreadThatClosingTheTransportLeavesRunning() throws Exception {
        SdkSessionSweepers.track();
        final HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder().mcpEndpoint( "/sweeper-test" ).build();

        assertFalse( liveSweeperThreads().isEmpty(), "the SDK starts an idle-session sweeper thread" );
        assertTrue( liveSweeperThreads().stream().noneMatch( Thread::isDaemon ),
                "the sweeper thread is non-daemon, so it keeps the JVM alive" );

        transport.closeGracefully().block();
        Thread.sleep( 300 );
        assertFalse( liveSweeperThreads().isEmpty(),
                "the SDK still leaves its sweeper running after close; if this fails, the SDK fixed it "
                        + "and SdkSessionSweepers can be removed" );

        SdkSessionSweepers.disposeAll();
        awaitNoSweeperThreads();

        assertEquals( List.of(), liveSweeperThreads() );
    }

    @Test
    void disposeAllUninstallsTheDecoratorSoAStoppedWebappLeavesNothingInstalled() {
        SdkSessionSweepers.track();
        SdkSessionSweepers.disposeAll();

        // addExecutorServiceDecorator answers whether the key was free.
        assertTrue( Schedulers.addExecutorServiceDecorator( SdkSessionSweepers.DECORATOR_KEY, ( scheduler, executor ) -> executor ) );
        Schedulers.removeExecutorServiceDecorator( SdkSessionSweepers.DECORATOR_KEY );
    }

    @Test
    void trackingIsIdempotentAcrossTheTwoMcpEndpoints() {
        SdkSessionSweepers.track();
        SdkSessionSweepers.track();
        assertDoesNotThrow( SdkSessionSweepers::disposeAll );
        assertDoesNotThrow( SdkSessionSweepers::disposeAll );
    }
}

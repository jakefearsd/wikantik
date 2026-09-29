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
package com.wikantik.bootstrap;

import com.wikantik.HttpMockFactory;
import com.wikantik.MockEngineBuilder;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WikiBootstrapServletContextListener}.
 *
 * <p>Two production behaviours in this class have JVM-wide side effects that must
 * never actually run inside this test process:
 * <ul>
 *   <li>{@code initWikiLoggingFramework} reconfigures the real Log4j2 {@link LoggerContext}
 *       when invoked with a "false" external-logconfig value — the happy-path test
 *       restores the original configuration afterward via {@code ctx.reconfigure()}.</li>
 *   <li>{@code contextDestroyed}'s final step is {@code LogManager.shutdown()}, which would
 *       kill logging for every other test class sharing this JVM fork — {@link TestableListener}
 *       overrides the extracted {@code shutdownLogging()} seam to a no-op instead.</li>
 * </ul>
 */
class WikiBootstrapServletContextListenerTest {

    private final WikiBootstrapServletContextListener listener = new WikiBootstrapServletContextListener();

    // -----------------------------------------------------------------------
    // initWikiSPIs / contextInitialized
    // -----------------------------------------------------------------------

    @Test
    void testInitWikiSPIs_returnsPropertiesFromWikiInit() {
        final ServletContext ctx = Mockito.mock( ServletContext.class );
        final ServletContextEvent sce = new ServletContextEvent( ctx );

        final Properties props = listener.initWikiSPIs( sce );

        Assertions.assertNotNull( props );
        Assertions.assertTrue( props.containsKey( "wikantik.workDir" ), "loadWebAppProps should always set wikantik.workDir" );
    }

    @Test
    void testContextInitialized_delegatesToSpisAndLoggingFramework() {
        // A spy stubbing initWikiSPIs() so this test controls the properties fed into
        // initWikiLoggingFramework() directly, rather than going through the real
        // Wiki.init()/PropertyReader cascade — the test fixtures' wikantik-custom.properties
        // and wikantik-vers-custom.properties both declare appender.rolling.fileName without
        // a name/type, which is fine for their own narrowly-scoped tests but produces an
        // invalid Log4j2 configuration once merged and actually built (a fixture gap, not
        // a defect in this class — nothing previously exercised this code path end to end).
        final WikiBootstrapServletContextListener spyListener = Mockito.spy( new WikiBootstrapServletContextListener() );
        final ServletContext ctx = Mockito.mock( ServletContext.class );
        final ServletContextEvent sce = new ServletContextEvent( ctx );

        final Properties controlledProps = new Properties();
        controlledProps.setProperty( "status", "warn" );
        controlledProps.setProperty( "appender.console.type", "Console" );
        controlledProps.setProperty( "appender.console.name", "STDOUT" );
        controlledProps.setProperty( "rootLogger.level", "info" );
        controlledProps.setProperty( "rootLogger.appenderRef.console.ref", "STDOUT" );
        doReturn( controlledProps ).when( spyListener ).initWikiSPIs( sce );

        final LoggerContext logCtx = ( LoggerContext ) LogManager.getContext( false );
        try {
            Assertions.assertDoesNotThrow( () -> spyListener.contextInitialized( sce ) );
        } finally {
            // Restore whatever configuration Log4j2 would normally discover, undoing the
            // in-memory reconfiguration that contextInitialized() just performed.
            logCtx.reconfigure();
        }

        verify( spyListener ).initWikiSPIs( sce );
    }

    // -----------------------------------------------------------------------
    // initWikiLoggingFramework / createConfigurationSource
    // -----------------------------------------------------------------------

    @Test
    void testInitWikiLoggingFramework_externalConfig_skipsReconfigurationAndReturnsFalse() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.use.external.logconfig", "true" );

        // No LoggerContext restore needed: the external-config branch never touches Log4j2.
        final boolean readFromWikiProperties = listener.initWikiLoggingFramework( props );

        Assertions.assertFalse( readFromWikiProperties );
    }

    @Test
    void testInitWikiLoggingFramework_internalConfig_reconfiguresAndReturnsTrue() {
        final Properties props = new Properties();
        props.setProperty( "status", "warn" );
        props.setProperty( "appender.console.type", "Console" );
        props.setProperty( "appender.console.name", "STDOUT" );
        props.setProperty( "rootLogger.level", "info" );
        props.setProperty( "rootLogger.appenderRef.console.ref", "STDOUT" );
        // Not a log4j2-namespaced key — must be filtered out by createConfigurationSource.
        props.setProperty( "wikantik.some.unrelated.setting", "value" );

        final LoggerContext logCtx = ( LoggerContext ) LogManager.getContext( false );
        try {
            final boolean readFromWikiProperties = listener.initWikiLoggingFramework( props );
            Assertions.assertTrue( readFromWikiProperties );
        } finally {
            logCtx.reconfigure();
        }
    }

    @Test
    void testCreateConfigurationSource_filtersToLog4jNamespacedKeysOnly() throws Exception {
        final Properties props = new Properties();
        props.setProperty( "rootLogger.level", "info" );
        props.setProperty( "wikantik.some.unrelated.setting", "value" );

        final org.apache.logging.log4j.core.config.ConfigurationSource source =
            listener.createConfigurationSource( props );

        Assertions.assertNotNull( source );
        final Properties written = new Properties();
        written.load( source.getInputStream() );
        Assertions.assertTrue( written.containsKey( "rootLogger.level" ) );
        Assertions.assertFalse( written.containsKey( "wikantik.some.unrelated.setting" ) );
    }

    // -----------------------------------------------------------------------
    // lookupEngine
    // -----------------------------------------------------------------------

    @Test
    void testLookupEngine_nullEvent_returnsNull() {
        Assertions.assertNull( listener.lookupEngine( null ) );
    }

    @Test
    void testLookupEngine_nullServletContext_returnsNull() {
        final ServletContextEvent sce = Mockito.mock( ServletContextEvent.class );
        when( sce.getServletContext() ).thenReturn( null );

        Assertions.assertNull( listener.lookupEngine( sce ) );
    }

    @Test
    void testLookupEngine_engineRegistered_returnsIt() {
        final ServletContext ctx = HttpMockFactory.createServletContext( "lookup-engine-success" );
        final WikiEngine engine = MockEngineBuilder.engine().build();
        ctx.setAttribute( WikiBootstrapServletContextListener.ATTR_WIKIENGINE, engine );

        final ServletContextEvent sce = new ServletContextEvent( ctx );

        Assertions.assertSame( engine, listener.lookupEngine( sce ) );
    }

    @Test
    void testLookupEngine_attributeAccessThrows_returnsNullInsteadOfPropagating() {
        final ServletContext ctx = Mockito.mock( ServletContext.class );
        when( ctx.getAttribute( anyString() ) ).thenThrow( new RuntimeException( "boom" ) );
        final ServletContextEvent sce = new ServletContextEvent( ctx );

        Assertions.assertDoesNotThrow( () -> Assertions.assertNull( listener.lookupEngine( sce ) ) );
    }

    // -----------------------------------------------------------------------
    // contextDestroyed — via a subclass that no-ops the JVM-wide Log4j2 shutdown
    // -----------------------------------------------------------------------

    /** Overrides the extracted seam so the real test process's logging survives. */
    private static final class TestableListener extends WikiBootstrapServletContextListener {
        final AtomicBoolean shutdownLoggingCalled = new AtomicBoolean( false );

        @Override
        void shutdownLogging() {
            shutdownLoggingCalled.set( true );
        }
    }

    @Test
    void testContextDestroyed_nullEvent_runsCleanupWithoutThrowing() {
        final TestableListener testable = new TestableListener();

        Assertions.assertDoesNotThrow( () -> testable.contextDestroyed( null ) );
        Assertions.assertTrue( testable.shutdownLoggingCalled.get(), "shutdownLogging() must always run, even with no engine" );
    }

    @Test
    void testContextDestroyed_engineShutdownThrows_isLoggedAndSwallowed() {
        final TestableListener testable = new TestableListener();
        final ServletContext ctx = HttpMockFactory.createServletContext( "context-destroyed-throwing" );
        final WikiEngine engine = MockEngineBuilder.engine().build();
        doThrow( new RuntimeException( "shutdown boom" ) ).when( engine ).shutdown();
        ctx.setAttribute( WikiBootstrapServletContextListener.ATTR_WIKIENGINE, engine );

        final ServletContextEvent sce = new ServletContextEvent( ctx );

        Assertions.assertDoesNotThrow( () -> testable.contextDestroyed( sce ) );
        verify( engine ).shutdown();
        Assertions.assertTrue( testable.shutdownLoggingCalled.get() );
    }

    // -----------------------------------------------------------------------
    // interruptAndJoinKnownBackgroundThreads / isKnownBackgroundThread
    // -----------------------------------------------------------------------

    @Test
    void testInterruptAndJoinKnownBackgroundThreads_interruptsAndJoinsResponsiveThread() throws InterruptedException {
        final AtomicBoolean interrupted = new AtomicBoolean( false );
        final Thread responsive = new Thread( () -> {
            try {
                Thread.sleep( 60_000 );
            } catch ( final InterruptedException ie ) {
                interrupted.set( true );
                Thread.currentThread().interrupt();
            }
        }, "WatchDog for 'responsive-test-thread'" );
        responsive.setDaemon( true );
        responsive.start();

        try {
            Assertions.assertDoesNotThrow( WikiBootstrapServletContextListener::interruptAndJoinKnownBackgroundThreads );
            responsive.join( 2_000 );
            Assertions.assertFalse( responsive.isAlive(), "a responsive matching thread must be joined promptly" );
            Assertions.assertTrue( interrupted.get() );
        } finally {
            responsive.interrupt();
        }
    }

    @Test
    void testInterruptAndJoinKnownBackgroundThreads_forceInterruptsUnresponsiveThread() throws InterruptedException {
        // Ignores the first interrupt for longer than THREAD_JOIN_TIMEOUT_MS (3s), forcing
        // interruptAndJoinKnownBackgroundThreads() into its "still alive after join" branch,
        // then exits promptly on the second interrupt so the thread never leaks past this test.
        final java.util.concurrent.atomic.AtomicInteger interruptCount = new java.util.concurrent.atomic.AtomicInteger( 0 );
        final Thread stubborn = new Thread( () -> {
            final long deadline = System.currentTimeMillis() + 5_000;
            while ( System.currentTimeMillis() < deadline ) {
                try {
                    Thread.sleep( 4_000 );
                } catch ( final InterruptedException ie ) {
                    if ( interruptCount.incrementAndGet() >= 2 ) {
                        return;
                    }
                    // swallow the first interrupt to simulate a slow/unresponsive shutdown
                }
            }
        }, "kg-judge-runner-test-thread" );
        stubborn.setDaemon( true );
        stubborn.start();

        try {
            Assertions.assertDoesNotThrow( WikiBootstrapServletContextListener::interruptAndJoinKnownBackgroundThreads );
            stubborn.join( 4_000 );
            Assertions.assertTrue( interruptCount.get() >= 1 );
        } finally {
            // Belt-and-braces: make sure the thread is really gone before the next test runs.
            for ( int i = 0; i < 3 && stubborn.isAlive(); i++ ) {
                stubborn.interrupt();
                stubborn.join( 1_000 );
            }
        }
    }

    @AfterEach
    void tearDown() {
        // No shared state to reset — each test builds its own ServletContext/engine.
    }
}

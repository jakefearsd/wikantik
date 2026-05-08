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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.util.TextUtil;


import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;


public class WikiBootstrapServletContextListener implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( WikiBootstrapServletContextListener.class );
    private static final String[] LOG4J_CONF = { "appender", "logger", "rootLogger", "filter", "status", "dest", "name", "properties", "property", "log4j2" };

    /** {@inheritDoc} */
    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final Properties properties = initWikiSPIs( sce );
        initWikiLoggingFramework( properties );
    }

    /**
     * Locate and init JSPWiki SPIs' implementations
     *
     * @param sce associated servlet context.
     * @return JSPWiki configuration properties.
     */
    Properties initWikiSPIs( final ServletContextEvent sce ) {
        return Wiki.init( sce.getServletContext() );
    }

    /**
     * Initialize the logging framework(s). By default, we try to load the log config statements from wikantik.properties,
     * unless the property wikantik.use.external.logconfig=true, in that case we let the logging framework figure out the
     * logging configuration.
     *
     * @param properties JSPWiki configuration properties.
     * @return {@code true} if configuration was read from wikantik.properties, {@code false} otherwise.
     */
    @SuppressWarnings( "PMD.CloseResource" ) // LoggerContext is container-managed; closing it would tear down Log4j2 for the whole container.
    boolean initWikiLoggingFramework( final Properties properties ) {
        final String useExternalLogConfig = TextUtil.getStringProperty( properties, "wikantik.use.external.logconfig", "false" );
        if ( "false".equals( useExternalLogConfig ) ) {
            final ConfigurationSource source = createConfigurationSource( properties );
            if( source != null ) {
                final PropertiesConfigurationFactory factory = new PropertiesConfigurationFactory();
                final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( this.getClass().getClassLoader(), false );
                final Configuration conf = factory.getConfiguration( ctx, source );
                conf.initialize();
                ctx.setConfiguration( conf );
                LOG.info( "Log configuration reloaded from Wiki properties" );
            }
        }
        return "false".equals( useExternalLogConfig );
    }

    ConfigurationSource createConfigurationSource( final Properties properties ) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            final Properties log4JProperties = new Properties();
            properties.forEach( ( k, v ) -> {
                for( final String log4JNsProp : LOG4J_CONF ) {
                    if( k.toString().startsWith( log4JNsProp ) ) {
                        log4JProperties.put( k, v );
                    }
                }
            } );
            log4JProperties.store( out, null );
            final InputStream in = new ByteArrayInputStream( out.toByteArray() );
            return new ConfigurationSource( in );
        } catch( final IOException ioe ) {
            // LOG.error justified: logging framework misconfiguration is a startup-critical issue that should be visible to operators
            LOG.error( "Unable to load the properties file into Log4j2, default Log4J2 configuration will be applied.", ioe );
            return null;
        }
    }

    /** Servlet-context attribute name under which the WikiEngine is stashed. */
    static final String ATTR_WIKIENGINE = "com.wikantik.WikiEngine";

    /**
     * Shuts down all background subsystems cleanly so Tomcat does not report
     * thread / ThreadLocal leaks after webapp undeploy.
     *
     * <p>Shutdown order:
     * <ol>
     *   <li>Mark stop on {@link JudgeRunner} and {@link DefaultRetrievalQualityRunner}
     *       so no new work starts after this point.</li>
     *   <li>Call {@code engine.shutdown()} — fires {@code WikiEngineEvent.SHUTDOWN},
     *       which sets {@code killMe=true} on all {@code WikiBackgroundThread}
     *       instances (LuceneUpdater, PageDirectoryWatcher, NewsPageGenerator,
     *       WatchDogThread).</li>
     *   <li>Interrupt all non-daemon threads whose names match the known set so
     *       they wake up immediately instead of sleeping through their full interval.</li>
     *   <li>Await thread termination with a short bound (3 s); force-interrupt
     *       stragglers.</li>
     *   <li>Close {@link JudgeRunner} (shuts down its ScheduledExecutorService).</li>
     *   <li>Close {@link DefaultRetrievalQualityRunner}.</li>
     *   <li>Clear the WikiSession guest-session ThreadLocal for the context thread.</li>
     *   <li>Shut down Log4j2 last — keeps logging available for all earlier steps.</li>
     * </ol>
     *
     * <p>Every step is guarded by a try/catch so one failure cannot prevent the
     * others from running.
     */
    @Override
    public void contextDestroyed( final ServletContextEvent sce ) {
        final Engine engine = lookupEngine( sce );

        // 1. Resolve runner managers; engine == null means the engine never started.
        // Phase 1 of the wikantik-main subsystem decomposition: JudgeRunner
        // comes from the typed KnowledgeSubsystem.Services bundle. The bridge
        // resolves to the engine's manager registry under the hood.
        @SuppressWarnings( "PMD.CloseResource" ) // closed in lines 164-165 via null-checks
        final JudgeRunner judgeRunner = engine == null ? null
            : runQuietly( "lookup JudgeRunner",
                () -> com.wikantik.knowledge.subsystem.KnowledgeSubsystemBridge
                    .fromLegacyEngine( engine ).judgeRunner() );
        @SuppressWarnings( "PMD.CloseResource" ) // closed in lines 164-165 via null-checks
        final DefaultRetrievalQualityRunner rqRunner = engine == null ? null
            : runQuietly( "lookup RetrievalQualityRunner",
                () -> com.wikantik.knowledge.subsystem.KnowledgeSubsystemBridge
                    .fromLegacyEngine( engine ).retrievalQualityRunner()
                    instanceof DefaultRetrievalQualityRunner d ? d : null );

        // 2. Close the runner schedulers BEFORE firing SHUTDOWN — gives their
        //    ScheduledExecutorServices a true interrupt rather than a 3 s wait.
        if ( judgeRunner != null ) runQuietly( "JudgeRunner.close()", judgeRunner::close );
        if ( rqRunner    != null ) runQuietly( "RetrievalQualityRunner.close()", rqRunner::close );

        // 3. Fire WikiEngineEvent.SHUTDOWN — marks WikiBackgroundThreads killMe=true,
        //    shuts down CachingManager, FilterManager, hybrid-index listeners, etc.
        if ( engine != null ) runQuietly( "engine.shutdown()", engine::shutdown );

        // 4. Interrupt known background threads and wait briefly for them to exit.
        interruptAndJoinKnownBackgroundThreads();

        // 5. Clear the WikiSession guest-session ThreadLocal for the context thread.
        runQuietly( "WikiSession.removeCurrentGuestSession()",
            WikiSession::removeCurrentGuestSession );

        // 6. Shut down Log4j2 last (keeps logging available for all prior steps).
        LogManager.shutdown();
    }

    /**
     * Runs a defensive shutdown step. Logs a WARN with stack trace on failure
     * so one bad subsystem cannot block the rest of the shutdown sequence.
     */
    private static void runQuietly( final String step, final Runnable op ) {
        try {
            op.run();
        } catch ( final RuntimeException e ) {
            LOG.warn( "contextDestroyed: {} failed: {}", step, e.getMessage(), e );
        }
    }

    /**
     * Variant that returns a value; same recovery semantics. Returns {@code null}
     * on failure so the caller decides whether the missing value matters.
     */
    private static < T > T runQuietly( final String step, final java.util.concurrent.Callable< T > op ) {
        try {
            return op.call();
        } catch ( final Exception e ) {
            LOG.warn( "contextDestroyed: {} failed: {}", step, e.getMessage(), e );
            return null;
        }
    }

    /**
     * Returns the {@link Engine} stored in the servlet context, or {@code null}
     * when the context is unavailable or no engine was ever started.
     */
    Engine lookupEngine( final ServletContextEvent sce ) {
        if ( sce == null ) {
            return null;
        }
        final ServletContext ctx = sce.getServletContext();
        if ( ctx == null ) {
            return null;
        }
        return runQuietly( "lookup WikiEngine from context",
            () -> ( Engine ) ctx.getAttribute( ATTR_WIKIENGINE ) );
    }

    /** Join timeout per background thread (milliseconds). */
    private static final int THREAD_JOIN_TIMEOUT_MS = 3_000;

    /**
     * Interrupts all live background threads whose names match the known set of
     * JSPWiki background threads (both daemon and non-daemon), then waits up to
     * {@value #THREAD_JOIN_TIMEOUT_MS} ms per thread for it to exit.
     *
     * <p>This causes threads sleeping in {@code WikiBackgroundThread.run()} to
     * wake up immediately rather than waiting out their full sleep interval.
     * Force-interrupting is repeated after the join timeout for any thread that
     * did not exit cleanly.
     */
    static void interruptAndJoinKnownBackgroundThreads() {
        final java.util.List< Thread > targets = new java.util.ArrayList<>();
        for ( final Thread t : Thread.getAllStackTraces().keySet() ) {
            if ( isKnownBackgroundThread( t ) && t.isAlive() ) {
                LOG.info( "contextDestroyed: interrupting background thread '{}' for fast shutdown",
                    t.getName() );
                t.interrupt();
                targets.add( t );
            }
        }
        for ( final Thread t : targets ) {
            if ( t.isAlive() ) {
                try {
                    t.join( THREAD_JOIN_TIMEOUT_MS );
                } catch ( final InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                    LOG.warn( "contextDestroyed: interrupted while joining thread '{}'", t.getName() );
                }
                if ( t.isAlive() ) {
                    LOG.warn( "contextDestroyed: thread '{}' still alive after {}ms — interrupting again",
                        t.getName(), THREAD_JOIN_TIMEOUT_MS );
                    t.interrupt();
                }
            }
        }
    }

    private static boolean isKnownBackgroundThread( final Thread t ) {
        final String name = t.getName();
        return name != null && (
            name.startsWith( "JSPWiki Lucene Indexer" )
            || name.startsWith( "JSPWiki Page Directory Watcher" )
            || name.startsWith( "JSPWiki News Page Generator" )
            || name.startsWith( "WatchDog for '" )
            || name.startsWith( "kg-judge-runner" )
            || name.startsWith( "retrieval-quality-runner" )
        );
    }

}

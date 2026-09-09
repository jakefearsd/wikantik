/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.search;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.search.subsystem.lucene.DefaultLuceneIndexer;
import com.wikantik.test.StubSystemPageRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup missing-page sweep must say what it compared.
 *
 * <p>Production symptom that motivated this: a 33 KB page served fine at {@code /wiki/} but was
 * absent from the search index — a term appearing only in that page returned zero BM25 matches.
 * The sweep is supposed to catch exactly that, and the boot log dutifully said "Lucene index
 * exists, checking for missing pages...", but nothing followed. {@code indexMissingPages()} logs
 * only inside {@code if (!missingPages.isEmpty())}, so "swept and found nothing" and "compared an
 * empty page list against the index and therefore could not find anything" produce identical
 * output: silence. With no counts in the log there is no way, after the fact, to tell a healthy
 * sweep from a vacuous one — which is why the production case could not be diagnosed from logs.</p>
 */
class LuceneMissingPageSweepObservabilityTest {

    private static void setField( final Object target, final String fieldName, final Object value ) {
        try {
            final Field field = target.getClass().getDeclaredField( fieldName );
            field.setAccessible( true );
            field.set( target, value );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Failed to set field " + fieldName, e );
        }
    }

    /** Seeds one document so the index directory is non-empty, then returns the provider. */
    private static LuceneSearchProvider providerWithSeededIndex( final PageManager pm,
                                                                 final AttachmentManager am,
                                                                 final File luceneDir )
            throws ProviderException {
        final LuceneSearchProvider provider =
            new LuceneSearchProvider( pm, am, new StubSystemPageRegistry() );
        setField( provider, "luceneDirectory", luceneDir.getAbsolutePath() );
        setField( provider, "analyzer", new ClassicAnalyzer() );
        setField( provider, "searchExecutor", Executors.newCachedThreadPool() );

        final Page seed = Mockito.mock( Page.class );
        Mockito.when( seed.getName() ).thenReturn( "SeedPage" );
        Mockito.when( am.listAttachments( Mockito.any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( seed, "seed content" );
        return provider;
    }

    private static List< LogEvent > captureIndexerLogs() {
        final List< LogEvent > captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final AbstractAppender appender = new AbstractAppender(
                "SweepCapture-" + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        appender.start();
        config.addAppender( appender );
        final String loggerName = DefaultLuceneIndexer.class.getName();
        LoggerConfig loggerConfig = config.getLoggerConfig( loggerName );
        if ( !loggerConfig.getName().equals( loggerName ) ) {
            loggerConfig = LoggerConfig.createLogger( false, Level.INFO, loggerName,
                    "true", new AppenderRef[ 0 ], null, config, null );
            config.addLogger( loggerName, loggerConfig );
        }
        // The suite's log4j config runs this logger above INFO, which would filter the healthy-path
        // summary before it ever reaches the appender; lower it for the duration of the test.
        loggerConfig.setLevel( Level.INFO );
        loggerConfig.addAppender( appender, Level.INFO, null );
        ctx.updateLoggers();
        return captured;
    }

    /**
     * The degenerate case: the index holds documents but the page list comes back empty, so the
     * sweep cannot possibly find anything missing. That is not a clean bill of health and must not
     * be reported as one.
     */
    @Test
    void sweepOverAnEmptyPageListIsReportedRatherThanPassingSilently( @TempDir final File tempDir )
            throws ProviderException {
        final File luceneDir = new File( tempDir, "lucene" );
        assertTrue( luceneDir.mkdirs() );

        final PageManager pm = Mockito.mock( PageManager.class );
        final AttachmentManager am = Mockito.mock( AttachmentManager.class );
        final LuceneSearchProvider provider = providerWithSeededIndex( pm, am, luceneDir );

        // The provider enumerates nothing — an unwarmed page cache, a provider hiccup, whatever.
        Mockito.when( pm.getAllPages() ).thenReturn( Collections.emptyList() );
        Mockito.when( am.getAllAttachments() ).thenReturn( Collections.emptyList() );

        final List< LogEvent > logs = captureIndexerLogs();
        provider.indexMissingPages();

        final boolean reported = logs.stream()
                .anyMatch( e -> e.getLevel().isMoreSpecificThan( Level.WARN )
                        && e.getMessage().getFormattedMessage().toLowerCase()
                            .contains( "sweep" ) );
        assertTrue( reported,
                "A sweep that compared an EMPTY page list against a non-empty index proves nothing "
              + "and must be surfaced, not reported as a clean run. Captured: "
              + logs.stream().map( e -> e.getLevel() + " " + e.getMessage().getFormattedMessage() ).toList() );
    }

    /**
     * The healthy case still has to say what it compared, so an operator reading the boot log can
     * tell "checked 1,376 pages against 1,376 documents, none missing" from silence.
     */
    @Test
    void sweepAlwaysReportsTheCountsItCompared( @TempDir final File tempDir ) throws ProviderException {
        final File luceneDir = new File( tempDir, "lucene" );
        assertTrue( luceneDir.mkdirs() );

        final PageManager pm = Mockito.mock( PageManager.class );
        final AttachmentManager am = Mockito.mock( AttachmentManager.class );
        final LuceneSearchProvider provider = providerWithSeededIndex( pm, am, luceneDir );

        final Page seed = Mockito.mock( Page.class );
        Mockito.when( seed.getName() ).thenReturn( "SeedPage" );
        Mockito.when( pm.getAllPages() ).thenReturn( List.of( seed ) );
        Mockito.when( am.getAllAttachments() ).thenReturn( Collections.emptyList() );

        final List< LogEvent > logs = captureIndexerLogs();
        provider.indexMissingPages();

        final boolean reported = logs.stream()
                .anyMatch( e -> e.getMessage().getFormattedMessage().toLowerCase().contains( "sweep" ) );
        assertTrue( reported,
                "The sweep must log the comparison it performed even when nothing is missing. Captured: "
              + logs.stream().map( e -> e.getMessage().getFormattedMessage() ).toList() );
    }
}

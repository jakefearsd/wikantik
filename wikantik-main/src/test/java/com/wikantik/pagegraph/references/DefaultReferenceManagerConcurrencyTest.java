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
package com.wikantik.pagegraph.references;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reference maps hold plain {@code TreeSet}s that every save, delete and the native-wikilink rescan (which runs on
 * the structural-index bootstrap thread) mutate. Concurrent unsynchronised mutation corrupted them: the perf harness
 * caught the bootstrap rescan racing a second rescan with {@code NullPointerException: Cannot read field "left"}
 * inside {@code TreeMap}, and a corrupted tree can also spin forever. Updates and the readers that walk those sets
 * must be serialised.
 */
class DefaultReferenceManagerConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PAGES_PER_THREAD = 400;
    private static final List< String > TARGETS = List.of( "T0", "T1", "T2", "T3", "T4" );

    @Test
    void concurrentUpdatesAndReadsKeepTheMapsConsistent( @TempDir final File workDir ) throws Exception {
        final DefaultReferenceManager mgr = manager( workDir );
        mgr.initialize( List.of() );
        final List< Throwable > failures = new CopyOnWriteArrayList<>();
        final ExecutorService pool = Executors.newFixedThreadPool( THREADS + 2 );
        final CountDownLatch start = new CountDownLatch( 1 );
        final CountDownLatch writersDone = new CountDownLatch( THREADS );
        for ( int t = 0; t < THREADS; t++ ) {
            final int thread = t;
            pool.execute( () -> {
                try {
                    start.await();
                    for ( int i = 0; i < PAGES_PER_THREAD; i++ ) {
                        final String page = "P" + thread + "_" + i;
                        mgr.updateReferences( page, List.of( "T0", "T1" ) );   // first version of the page
                        mgr.updateReferences( page, TARGETS );                 // re-saved: old refs cleaned, new added
                    }
                } catch ( final Throwable e ) { // NOPMD - collected and asserted below
                    failures.add( e );
                } finally {
                    writersDone.countDown();
                }
            } );
        }
        for ( int r = 0; r < 2; r++ ) {
            pool.execute( () -> {
                try {
                    start.await();
                    while ( writersDone.getCount() > 0 ) {
                        for ( final String target : TARGETS ) {
                            for ( final String referrer : mgr.findReferrers( target ) ) {
                                assertTrue( referrer.startsWith( "P" ) );
                            }
                        }
                    }
                } catch ( final Throwable e ) { // NOPMD - collected and asserted below
                    failures.add( e );
                }
            } );
        }
        start.countDown();
        pool.shutdown();
        assertTrue( pool.awaitTermination( 60, TimeUnit.SECONDS ), "workers must finish (a corrupted TreeMap can spin)" );

        assertEquals( List.of(), failures.stream().map( Throwable::toString ).toList(), "no worker may fail" );
        for ( final String target : TARGETS ) {
            assertEquals( THREADS * PAGES_PER_THREAD, mgr.findReferrers( target ).size(),
                    "every page must be recorded as a referrer of " + target );
        }
    }

    @Test
    void findReferrersReturnsASnapshotNotTheLiveSet( @TempDir final File workDir ) throws Exception {
        final DefaultReferenceManager mgr = manager( workDir );
        mgr.initialize( List.of() );
        mgr.updateReferences( "A", List.of( "Target" ) );
        final java.util.Set< String > seen = mgr.findReferrers( "Target" );
        mgr.updateReferences( "B", List.of( "Target" ) );
        assertEquals( new ArrayList<>( List.of( "A" ) ), new ArrayList<>( seen ),
                "a caller iterating the result must not observe (or race) later updates" );
    }

    /**
     * The native rescan checks that a page is unchanged and then writes its references; a save landing between the
     * check and the write was overwritten with the older scan. The check and the write must be one atomic step.
     */
    @Test
    void aSaveRacingTheRescanIsNeverOverwrittenByTheOlderScan( @TempDir final File workDir ) throws Exception {
        final PageManager pm = mock( PageManager.class );
        final DefaultReferenceManager mgr = manager( workDir, pm );
        mgr.setWikiLinkResolver( new com.wikantik.wikilink.WikiLinkResolver( n -> n, java.util.Optional::empty ) );
        mgr.initialize( List.of() );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Racy" );
        final java.util.Date modified = new java.util.Date( 1_000L );
        when( page.getLastModified() ).thenReturn( modified );
        when( pm.getAllPages() ).thenReturn( List.of( page ) );
        when( pm.getPureText( "Racy", com.wikantik.api.providers.PageProvider.LATEST_VERSION ) ).thenReturn( "old [[OldTarget]]" );
        final Thread[] saver = new Thread[ 1 ];
        when( pm.getPage( "Racy" ) ).thenAnswer( inv -> {
            // a concurrent save's post-save reference update, landing right after the rescan's freshness check
            saver[ 0 ] = new Thread( () -> mgr.updateReferences( "Racy", List.of( "NewTarget" ) ) );
            saver[ 0 ].start();
            saver[ 0 ].join( 300 );
            return page;
        } );

        mgr.rescanNativeWikiLinks();
        saver[ 0 ].join( 5_000 );

        assertEquals( List.of( "NewTarget" ), new ArrayList<>( mgr.findRefersTo( "Racy" ) ),
                "the save's newer references must win over the rescan's older scan" );
    }

    private static DefaultReferenceManager manager( final File workDir ) throws Exception {
        return manager( workDir, mock( PageManager.class ) );
    }

    private static DefaultReferenceManager manager( final File workDir, final PageManager pm ) throws Exception {
        final AttachmentManager am = mock( AttachmentManager.class );
        when( am.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        when( pm.getPageText( anyString(), anyInt() ) ).thenReturn( "no links" );
        when( pm.wikiPageExists( anyString() ) ).thenReturn( true );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pm )
                .with( AttachmentManager.class, am )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        return new DefaultReferenceManager( engine, pm, am );
    }
}

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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole reference database ({@code refmgr.ser}) used to be re-serialised on every page save, rename and delete,
 * which made bulk writes (vault import, renames with many referrers) quadratic in the size of the wiki. Runtime
 * writes are now coalesced: at most one per interval, the rest flushed by the next write after the interval or when
 * the filter is destroyed. A snapshot that lags the live maps is safe because a warm start re-scans every page
 * modified after the snapshot and drops the entries of pages that no longer exist.
 */
class DefaultReferenceManagerSerializationCoalescingTest {

    private static final Date OLD = new Date( System.currentTimeMillis() - 600_000L );

    @Test
    void aBurstOfUpdatesWritesTheDatabaseOnceNotOncePerUpdate( @TempDir final File workDir ) throws Exception {
        final DefaultReferenceManager mgr = manager( workDir, mock( PageManager.class ) );
        mgr.initialize( List.of() );
        final int afterInit = mgr.serializationCount();

        for ( int i = 0; i < 50; i++ ) {
            mgr.updateReferences( "Page" + i, List.of( "Target" ) );
        }

        assertTrue( mgr.serializationCount() - afterInit <= 1,
                "50 updates inside one interval must not write the whole database 50 times: "
                        + ( mgr.serializationCount() - afterInit ) );
        assertEquals( 50, mgr.findReferrers( "Target" ).size(), "the live maps are always current" );
    }

    @Test
    void anUpdateAfterTheIntervalWritesImmediately( @TempDir final File workDir ) throws Exception {
        final DefaultReferenceManager mgr = manager( workDir, mock( PageManager.class ) );
        mgr.setSerializeIntervalMillis( 0L );
        mgr.initialize( List.of() );
        final int afterInit = mgr.serializationCount();

        mgr.updateReferences( "A", List.of( "B" ) );
        mgr.updateReferences( "C", List.of( "B" ) );

        assertEquals( afterInit + 2, mgr.serializationCount() );
    }

    @Test
    void destroyFlushesAPendingWriteSoAWarmStartSeesTheLatestReferences( @TempDir final File workDir ) throws Exception {
        final PageManager pm1 = mock( PageManager.class );
        final DefaultReferenceManager mgr = manager( workDir, pm1 );
        mgr.initialize( List.of() );
        mgr.updateReferences( "First", List.of( "Target" ) );   // may write (first runtime write)
        mgr.updateReferences( "Second", List.of( "Target" ) );  // pending inside the interval
        final int beforeDestroy = mgr.serializationCount();

        mgr.destroy( null );

        assertEquals( beforeDestroy + 1, mgr.serializationCount(), "destroy writes the pending changes" );
        mgr.destroy( null );
        assertEquals( beforeDestroy + 1, mgr.serializationCount(), "nothing pending: no second write" );

        final PageManager pm2 = mock( PageManager.class );
        final Page first = page( "First", pm2 );
        final Page second = page( "Second", pm2 );
        final DefaultReferenceManager warm = manager( workDir, pm2 );
        warm.initialize( List.of( first, second ) );
        assertEquals( Set.of( "First", "Second" ), warm.findReferrers( "Target" ),
                "the snapshot read back on a warm start holds the update that was pending at shutdown" );
    }

    @Test
    void aWarmStartDropsPagesThatNoLongerExist( @TempDir final File workDir ) throws Exception {
        // A snapshot written before "Gone" was deleted (the delete's write was still pending when the JVM died).
        final DefaultReferenceManager writer = manager( workDir, mock( PageManager.class ) );
        writer.initialize( List.of() );
        writer.setSerializeIntervalMillis( 0L );
        writer.updateReferences( "Gone", List.of( "Alpha" ) );
        writer.updateReferences( "Stays", List.of( "Alpha" ) );

        final PageManager pm = mock( PageManager.class );
        final Page alpha = page( "Alpha", pm );
        final Page stays = page( "Stays", pm );
        final DefaultReferenceManager warm = manager( workDir, pm );
        warm.initialize( List.of( alpha, stays ) );

        assertEquals( Set.of( "Stays" ), warm.findReferrers( "Alpha" ), "a deleted page is no longer a referrer" );
        assertFalse( warm.findCreated().contains( "Gone" ), "a deleted page is not listed as existing" );
        assertTrue( warm.findRefersTo( "Gone" ) == null || warm.findRefersTo( "Gone" ).isEmpty() );
    }

    private static Page page( final String name, final PageManager pm ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( OLD );
        when( p.getAttributes() ).thenReturn( Map.of() );
        when( pm.getPage( name ) ).thenReturn( p );
        return p;
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

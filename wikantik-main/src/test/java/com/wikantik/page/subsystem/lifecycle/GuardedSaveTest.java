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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveGuard;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.SaveOutcome;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GuardedSaveTest {

    private TestEngine engine;
    private PageManager pm;
    private PageSaveHelper helper;
    /** Held strongly: WikiEventManager keeps listeners by weak reference. */
    private com.wikantik.event.WikiEventListener listener;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        pm = engine.getManager( PageManager.class );
        helper = new PageSaveHelper( engine, pm );
    }

    @AfterEach
    void tearDown() {
        for( final String n : new String[]{ "GuardRace", "GuardOk", "GuardWrote", "GuardSame", "GuardRe" } ) {
            engine.deleteTestPage( n );
        }
        engine.stop();
    }

    private SaveOutcome save( final String name, final String text, final SaveGuard guard ) throws Exception {
        final Page page = Wiki.contents().page( engine, name );
        final Context ctx = Wiki.context().create( engine, page );
        return pm.saveText( ctx, text, guard );
    }

    private void holdFilter( final String pageName, final CountDownLatch entered, final CountDownLatch release )
            throws Exception {
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preSave( final Context context, final String content ) {
                if( pageName.equals( context.getPage().getName() ) && content.contains( "HOLD" ) ) {
                    entered.countDown();
                    try {
                        release.await( 20, TimeUnit.SECONDS );
                    } catch( final InterruptedException e ) {
                        Thread.currentThread().interrupt();
                    }
                }
                return content;
            }
        }, 100 );
    }

    @Test
    void aStrictSaveOvertakenWhileInItsFiltersIsRefused() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        holdFilter( "GuardRace", entered, release );
        save( "GuardRace", "seed", SaveGuard.NONE );

        final ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            final Future< SaveOutcome > a = ex.submit( () ->
                    helper.saveTracked( "GuardRace", "a HOLD", SaveOptions.builder().author( "A" ).expectedVersion( 1 ).build() ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ) );
            assertEquals( 2, save( "GuardRace", "b", SaveGuard.NONE ).version() );
            release.countDown();

            final var thrown = assertThrows( java.util.concurrent.ExecutionException.class, () -> a.get( 20, TimeUnit.SECONDS ) );
            assertInstanceOf( VersionConflictException.class, thrown.getCause() );
            assertEquals( "b", pm.getPureText( "GuardRace", -1 ).trim() );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void anUncontendedStrictSaveSucceeds() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        release.countDown();
        holdFilter( "GuardOk", entered, release );
        save( "GuardOk", "seed", SaveGuard.NONE );

        final SaveOutcome out = helper.saveTracked( "GuardOk", "a HOLD",
                SaveOptions.builder().author( "A" ).expectedVersion( 1 ).build() );
        assertEquals( 2, out.version() );
        assertFalse( out.unchanged() );
        assertEquals( "a HOLD", pm.getPureText( "GuardOk", -1 ).trim() );
    }

    @Test
    void theOutcomeIsTheVersionThisCallWrote() throws Exception {
        save( "GuardWrote", "one", SaveGuard.NONE );
        final AtomicBoolean fired = new AtomicBoolean();
        listener = event -> {
            if( event instanceof WikiPageEvent e && e.getType() == WikiPageEvent.POST_SAVE_END
                    && "GuardWrote".equals( e.getPageName() ) && fired.compareAndSet( false, true ) ) {
                try {
                    save( "GuardWrote", "three", SaveGuard.NONE );
                } catch( final Exception ex ) {
                    throw new IllegalStateException( ex );
                }
            }
        };
        WikiEventManager.addWikiEventListener( engine.getManager( FilterManager.class ), listener );
        final SaveOutcome out = save( "GuardWrote", "two", SaveGuard.NONE );
        assertTrue( fired.get() );
        assertEquals( 2, out.version() );
        assertEquals( 2, out.page().getVersion() );
        assertEquals( 3, pm.getPage( "GuardWrote" ).getVersion() );
    }

    @Test
    void anUnchangedTextReportsUnchangedAndWritesNothing() throws Exception {
        save( "GuardSame", "same", SaveGuard.NONE );
        final int before = pm.getPage( "GuardSame" ).getVersion();
        final SaveOutcome out = save( "GuardSame", "same", SaveGuard.NONE );
        assertTrue( out.unchanged() );
        assertEquals( before, out.version() );
        assertEquals( before, pm.getPage( "GuardSame" ).getVersion() );
    }

    @Test
    void aReentrantSaveOfTheSamePageDoesNotDeadlock() {
        final AtomicBoolean fired = new AtomicBoolean();
        listener = event -> {
            if( event instanceof WikiPageEvent e && e.getType() == WikiPageEvent.POST_SAVE_END
                    && "GuardRe".equals( e.getPageName() ) && fired.compareAndSet( false, true ) ) {
                try {
                    save( "GuardRe", "inner", SaveGuard.NONE );
                } catch( final Exception ex ) {
                    throw new IllegalStateException( ex );
                }
            }
        };
        WikiEventManager.addWikiEventListener( engine.getManager( FilterManager.class ), listener );
        assertTimeoutPreemptively( Duration.ofSeconds( 10 ), () -> save( "GuardRe", "outer", SaveGuard.NONE ) );
        assertTrue( fired.get() );
    }
}

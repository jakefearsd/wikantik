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
package com.wikantik.render;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reproduces the intermittent NPE seen in production-path concurrent renders of
 * the same page ("Failed to render page Main (durationMs=0): null" —
 * AnonymousViewIT flake, 2026-07-20): many threads calling
 * {@link RenderingManager#textToHTML} for one page must all succeed and produce
 * non-empty, identical-quality HTML.
 */
class ConcurrentRenderTest {

    @Test
    void concurrentRendersOfSamePageAllSucceed() throws Exception {
        final TestEngine engine = TestEngine.build();
        engine.saveText( "ConcurrentRenderPage",
                "## Congratulations!\n\nYou have successfully installed [Wikantik](About).\n\n"
                + "* For testing things, try the [SandBox](SandBox).\n" );
        final PageManager pm = engine.getManager( PageManager.class );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final Page page = pm.getPage( "ConcurrentRenderPage" );
        final String raw = pm.getText( "ConcurrentRenderPage" );

        final int threads = 16;
        final int iterations = 50;
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        final CountDownLatch start = new CountDownLatch( 1 );
        final List< Throwable > failures = new CopyOnWriteArrayList<>();
        try {
            final List< Future< ? > > futures = new java.util.ArrayList<>();
            for ( int t = 0; t < threads; t++ ) {
                futures.add( pool.submit( ( Callable< Void > ) () -> {
                    start.await();
                    for ( int i = 0; i < iterations; i++ ) {
                        try {
                            // Fresh context per call — same as one HTTP request per render.
                            final Context ctx = Wiki.context().create( engine, page );
                            final String html = rm.textToHTML( ctx, raw );
                            Assertions.assertNotNull( html );
                            Assertions.assertTrue( html.contains( "Congratulations" ),
                                    "render lost content: " + html );
                        } catch ( final Throwable e ) {
                            failures.add( e );
                        }
                    }
                    return null;
                } ) );
            }
            start.countDown();
            for ( final Future< ? > f : futures ) {
                f.get( 120, TimeUnit.SECONDS );
            }
        } finally {
            pool.shutdownNow();
            engine.stop();
        }

        if ( !failures.isEmpty() ) {
            failures.get( 0 ).printStackTrace();
        }
        Assertions.assertTrue( failures.isEmpty(),
                () -> "concurrent renders failed " + failures.size() + "x — first: " + failures.get( 0 ) );
    }
}

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

import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontmatterMetadataCacheTest {

    private static final String PAGE = "PageA";
    private static final String YAML_BODY = "---\n"
        + "summary: alpha summary\n"
        + "tags: [search, hybrid]\n"
        + "cluster: search\n"
        + "---\n"
        + "Body text.\n";

    @Test
    void getReturnsParsedMetadataAndCachesByLastModified() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final AtomicInteger calls = new AtomicInteger();
        when( pm.getPureText( eq( PAGE ), anyInt() ) ).thenAnswer( inv -> {
            calls.incrementAndGet();
            return YAML_BODY;
        } );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        final Date ts = new Date( 1_000L );
        final Map< String, Object > first = cache.get( PAGE, ts );
        assertEquals( "alpha summary", first.get( "summary" ) );
        assertEquals( "search", first.get( "cluster" ) );
        assertTrue( first.get( "tags" ) instanceof java.util.List );

        // Same (pageName, lastModified) — must hit cache, no extra getPureText call.
        cache.get( PAGE, ts );
        cache.get( PAGE, ts );
        assertEquals( 1, calls.get(),
            "second/third gets at the same lastModified must be cache hits" );
    }

    @Test
    void changedLastModifiedInvalidatesEntry() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final AtomicInteger calls = new AtomicInteger();
        when( pm.getPureText( eq( PAGE ), anyInt() ) ).thenAnswer( inv -> {
            calls.incrementAndGet();
            return YAML_BODY;
        } );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        cache.get( PAGE, new Date( 1_000L ) );
        cache.get( PAGE, new Date( 2_000L ) );
        assertEquals( 2, calls.get(),
            "different lastModified must produce a fresh parse (page edited)" );
    }

    @Test
    void nullLastModifiedSkipsCache() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final AtomicInteger calls = new AtomicInteger();
        when( pm.getPureText( eq( PAGE ), anyInt() ) ).thenAnswer( inv -> {
            calls.incrementAndGet();
            return YAML_BODY;
        } );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        // Without a stable lastModified key we cannot safely cache; verify each call parses fresh.
        cache.get( PAGE, null );
        cache.get( PAGE, null );
        assertEquals( 2, calls.get() );
    }

    @Test
    void pageManagerExceptionReturnsEmptyMap() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( PAGE ), anyInt() ) )
            .thenThrow( new RuntimeException( "boom" ) );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        final Map< String, Object > out = cache.get( PAGE, new Date( 1L ) );
        assertEquals( Map.of(), out );
    }

    @Test
    void emptyBodyReturnsEmptyMap() throws Exception {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( PAGE ), anyInt() ) ).thenReturn( "" );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        assertEquals( Map.of(), cache.get( PAGE, new Date( 1L ) ) );
    }

    @Test
    void nullPageNameReturnsEmptyMap() {
        final PageManager pm = mock( PageManager.class );
        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        assertEquals( Map.of(), cache.get( null, new Date( 1L ) ) );
    }

    @Test
    void invalidateRemovesEntry() throws Exception {
        final PageManager pm = mock( PageManager.class );
        final AtomicInteger calls = new AtomicInteger();
        when( pm.getPureText( eq( PAGE ), anyInt() ) ).thenAnswer( inv -> {
            calls.incrementAndGet();
            return YAML_BODY;
        } );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        final Date ts = new Date( 1L );
        cache.get( PAGE, ts );
        cache.invalidate( PAGE );
        cache.get( PAGE, ts );
        assertEquals( 2, calls.get(), "invalidated entry must re-parse on next get" );
    }

    @Test
    void noFrontmatterReturnsEmptyMap() throws Exception {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( PAGE ), anyInt() ) ).thenReturn( "Plain markdown body, no frontmatter\n" );

        final FrontmatterMetadataCache cache = new FrontmatterMetadataCache( pm, 100 );
        final Map< String, Object > out = cache.get( PAGE, new Date( 1L ) );
        // Pages without frontmatter parse cleanly into an empty metadata map.
        assertNull( out.get( "summary" ) );
        assertEquals( Map.of(), out );
    }
}

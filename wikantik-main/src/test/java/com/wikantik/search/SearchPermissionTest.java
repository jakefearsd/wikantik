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

import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that ACL-restricted pages are correctly filtered from search results
 * for unauthorized users. Validates the fast-path optimization in
 * {@link LuceneSearchProvider#findPages} that skips full {@code checkPermission()}
 * for pages without ACLs while still enforcing ACL restrictions.
 */
class SearchPermissionTest {

    private TestEngine engine;
    private SearchManager mgr;

    @BeforeEach
    void setUp() {
        final Properties props = TestEngine.getTestProperties();
        final String workDir = props.getProperty( "wikantik.workDir" );
        final String workRepo = props.getProperty( "wikantik.fileSystemProvider.pageDir" );

        final long timestamp = System.currentTimeMillis();
        props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
        props.setProperty( "wikantik.lucene.indexdelay", "0" );
        props.setProperty( "wikantik.lucene.initialdelay", "0" );
        props.setProperty( "wikantik.workDir", workDir + timestamp );
        props.setProperty( "wikantik.fileSystemProvider.pageDir", workRepo + timestamp );

        engine = TestEngine.build( props );
        mgr = engine.getManager( SearchManager.class );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            try { engine.deleteTestPage( "PublicSearchPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { engine.deleteTestPage( "RestrictedSearchPage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    /**
     * Verifies that after a page is updated with an ACL restricting view to
     * Authenticated users only, anonymous searches no longer return that page.
     * This mirrors {@code SearchManagerTest.testSimpleSearch4()} exactly,
     * validating the fast-path permission optimization in
     * {@link LuceneSearchProvider#findPages}.
     */
    @Test
    void testSearchExcludesAclRestrictedPages() throws Exception {
        final String txt = "It was the xyzzy9876 age of mankind.";
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        request.getParameterMap().put( "page", new String[]{ "RestrictedSearchPage" } );
        final Context ctx = Wiki.context().create( engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
        engine.getManager( com.wikantik.pages.PageManager.class ).saveText( ctx, txt );

        // Wait for it to become searchable
        Awaitility.await( "waiting for page to be indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( findsResultsFor( "xyzzy9876" ) );

        // Update the page with ACL restricting view to Authenticated only
        engine.getManager( com.wikantik.pages.PageManager.class ).saveText( ctx,
                "[{ALLOW view Authenticated}] It was the xyzzy9876 age of mankind... page is blocked" );

        // Search again -- should NOT find the page since session is not authenticated
        final Collection< SearchResult > results = mgr.findPages( "xyzzy9876", ctx );
        assertNotNull( results, "Result should not be null" );
        assertEquals( 0, results.size(),
                "ACL-restricted page should NOT appear in anonymous search results" );

        engine.deleteTestPage( "RestrictedSearchPage" );
    }

    /**
     * Helper that waits until search results appear for the given query.
     */
    private java.util.concurrent.Callable< Boolean > findsResultsFor( final String text ) {
        return () -> {
            final HttpServletRequest req = HttpMockFactory.createHttpRequest();
            final Context c = Wiki.context().create( engine, req, ContextEnum.PAGE_EDIT.getRequestContext() );
            final Collection< SearchResult > search = mgr.findPages( text, c );
            return search != null && !search.isEmpty();
        };
    }
}

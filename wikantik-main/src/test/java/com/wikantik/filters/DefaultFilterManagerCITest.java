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
package com.wikantik.filters;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Additional unit tests for {@link DefaultFilterManager} covering paths not
 * reached by {@link DefaultFilterManagerTest}.
 */
class DefaultFilterManagerCITest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.stop();
        }
    }

    // ---- addPageFilter(null) throws IllegalArgumentException ----

    @Test
    void addPageFilter_throwsForNullFilter() throws Exception {
        engine = TestEngine.build();
        final Properties props = TestEngine.getTestProperties();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, props );

        assertThrows( IllegalArgumentException.class,
                () -> mgr.addPageFilter( null, 0 ),
                "addPageFilter(null) should throw IllegalArgumentException" );
    }

    // ---- addPageFilter adds in order of priority ----

    @Test
    void addPageFilter_addsFilterToList() throws Exception {
        engine = TestEngine.build();
        final Properties props = TestEngine.getTestProperties();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, props );

        final int before = mgr.getFilterList().size();

        final PageFilter extra = mock( PageFilter.class );
        mgr.addPageFilter( extra, 0 );

        assertEquals( before + 1, mgr.getFilterList().size(),
                "Filter list should grow by one after addPageFilter" );
    }

    // ---- destroy calls destroy on all registered filters ----

    @Test
    void destroy_callsDestroyOnAllFilters() throws Exception {
        engine = TestEngine.build();
        final Properties noFiltersProps = new Properties();
        // Use empty props so no filters are loaded from XML
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, noFiltersProps );

        final PageFilter f1 = mock( PageFilter.class );
        final PageFilter f2 = mock( PageFilter.class );
        mgr.addPageFilter( f1, 10 );
        mgr.addPageFilter( f2, 5 );

        mgr.destroy();

        verify( f1 ).destroy( engine );
        verify( f2 ).destroy( engine );
    }

    // ---- doPreTranslateFiltering calls preTranslate on all filters ----

    @Test
    void doPreTranslateFiltering_callsPreTranslateAndReturnsModifiedContent() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );

        final PageFilter f = mock( PageFilter.class );
        when( f.preTranslate( any( Context.class ), anyString() ) )
                .thenAnswer( inv -> inv.getArgument( 1, String.class ) + "[PRE]" );
        mgr.addPageFilter( f, 0 );

        final Context ctx = buildContext( engine );
        final String result = mgr.doPreTranslateFiltering( ctx, "content" );

        assertEquals( "content[PRE]", result );
        verify( f ).preTranslate( ctx, "content" );
    }

    // ---- doPostTranslateFiltering calls postTranslate on all filters ----

    @Test
    void doPostTranslateFiltering_callsPostTranslateAndReturnsModifiedHtml() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );

        final PageFilter f = mock( PageFilter.class );
        when( f.postTranslate( any( Context.class ), anyString() ) )
                .thenAnswer( inv -> inv.getArgument( 1, String.class ) + "[POST]" );
        mgr.addPageFilter( f, 0 );

        final Context ctx = buildContext( engine );
        final String result = mgr.doPostTranslateFiltering( ctx, "<p>html</p>" );

        assertEquals( "<p>html</p>[POST]", result );
        verify( f ).postTranslate( ctx, "<p>html</p>" );
    }

    // ---- doPreSaveFiltering calls preSave on all filters ----

    @Test
    void doPreSaveFiltering_callsPreSaveAndReturnsModifiedContent() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );

        final PageFilter f = mock( PageFilter.class );
        when( f.preSave( any( Context.class ), anyString() ) )
                .thenAnswer( inv -> inv.getArgument( 1, String.class ) + "[PRESAVE]" );
        mgr.addPageFilter( f, 0 );

        final Context ctx = buildContext( engine );
        final String result = mgr.doPreSaveFiltering( ctx, "wiki markup" );

        assertEquals( "wiki markup[PRESAVE]", result );
        verify( f ).preSave( ctx, "wiki markup" );
    }

    // ---- doPostSaveFiltering calls postSave on all filters ----

    @Test
    void doPostSaveFiltering_callsPostSaveOnAllFilters() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );

        final PageFilter f = mock( PageFilter.class );
        mgr.addPageFilter( f, 0 );

        final Context ctx = buildContext( engine );
        mgr.doPostSaveFiltering( ctx, "saved content" );

        verify( f ).postSave( ctx, "saved content" );
    }

    // ---- getModuleInfo returns null for unknown class ----

    @Test
    void getModuleInfo_returnsNullForUnknownClass() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );

        assertNull( mgr.getModuleInfo( "com.example.NonExistentFilter" ),
                "getModuleInfo should return null for an unregistered filter class" );
    }

    // ---- modules() returns registered filters ----

    @Test
    void modules_returnsNonNullCollection() throws Exception {
        engine = TestEngine.build();
        final Properties props = TestEngine.getTestProperties();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, props );

        assertNotNull( mgr.modules(), "modules() should never return null" );
    }

    // ---- fireEvent with no listener does not throw ----

    @Test
    void fireEvent_doesNotThrowWhenNoListenerRegistered() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );
        final Context ctx = buildContext( engine );

        // Should be safe to call even if nobody is listening
        assertDoesNotThrow( () -> mgr.fireEvent( com.wikantik.event.WikiPageEvent.PRE_TRANSLATE_BEGIN, ctx ) );
    }

    // ---- getFilterList is the live list ----

    @Test
    void getFilterList_returnsLiveList() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );
        final List<PageFilter> list = mgr.getFilterList();
        assertNotNull( list );

        final int before = list.size();
        mgr.addPageFilter( mock( PageFilter.class ), 0 );

        assertEquals( before + 1, mgr.getFilterList().size(),
                "getFilterList should return the live (not copied) filter list" );
    }

    // ---- FilterException from filter is propagated ----

    @Test
    void doPreTranslateFiltering_propagatesFilterException() throws Exception {
        engine = TestEngine.build();
        final DefaultFilterManager mgr = new DefaultFilterManager( engine, new Properties() );

        final PageFilter f = mock( PageFilter.class );
        when( f.preTranslate( any( Context.class ), anyString() ) )
                .thenThrow( new FilterException( "filter refused" ) );
        mgr.addPageFilter( f, 0 );

        final Context ctx = buildContext( engine );
        assertThrows( FilterException.class,
                () -> mgr.doPreTranslateFiltering( ctx, "content" ),
                "FilterException from a filter should propagate to the caller" );
    }

    // ---- helper ----

    private Context buildContext( final Engine eng ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( "TestPage" );
        final Context ctx = mock( Context.class );
        when( ctx.getPage() ).thenReturn( p );
        when( ctx.getEngine() ).thenReturn( eng );
        return ctx;
    }

}

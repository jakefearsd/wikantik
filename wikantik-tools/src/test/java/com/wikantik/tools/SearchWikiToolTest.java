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
package com.wikantik.tools;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.search.SearchResult;
import com.wikantik.search.SearchManager;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class SearchWikiToolTest {

    @Mock Engine engine;
    @Mock PageManager pageManager;
    @Mock SearchManager searchManager;
    @Mock HttpServletRequest request;
    @Mock Context stubContext;

    private SearchWikiTool newTool( final Engine engine, final ToolsConfig config ) {
        return new SearchWikiTool( engine, config ) {
            @Override
            Context createContext() {
                return stubContext;
            }
        };
    }

    @Test
    void returnsErrorPayloadWhenSearchThrows() throws Exception {
        when( engine.getManager( SearchManager.class ) ).thenReturn( searchManager );
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( searchManager.findPages( any(), any() ) ).thenThrow( new RuntimeException( "boom" ) );

        final SearchWikiTool tool = newTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > result = tool.execute( "hello", 10, request );

        assertEquals( "hello", result.get( "query" ) );
        assertEquals( 0, result.get( "total" ) );
        assertTrue( result.get( "error" ).toString().contains( "boom" ) );
    }

    @Test
    void shapesResultsWithCitationAndSnippet() throws Exception {
        when( engine.getManager( SearchManager.class ) ).thenReturn( searchManager );
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "OnePage" );
        final SearchResult sr = mock( SearchResult.class );
        when( sr.getPage() ).thenReturn( page );
        when( sr.getScore() ).thenReturn( 42 );
        when( sr.getContexts() ).thenReturn( new String[] { "matching <em>excerpt</em>" } );

        when( searchManager.findPages( any(), any() ) ).thenReturn( List.of( sr ) );
        when( pageManager.getPureText( "OnePage", -1 ) ).thenReturn(
                "---\nsummary: A great page\n---\nFull body here" );

        final Properties props = new Properties();
        props.setProperty( "wikantik.public.baseURL", "https://wiki.example.com" );
        final SearchWikiTool tool = newTool( engine, new ToolsConfig( props ) );

        final Map< String, Object > out = tool.execute( "hello", 5, request );

        assertEquals( "hello", out.get( "query" ) );
        assertEquals( 1, out.get( "total" ) );
        final List< ? > results = ( List< ? > ) out.get( "results" );
        assertEquals( 1, results.size() );
        final Map< ?, ? > first = ( Map< ?, ? > ) results.get( 0 );
        assertEquals( "OnePage", first.get( "name" ) );
        assertEquals( "https://wiki.example.com/wiki/OnePage", first.get( "url" ) );
        assertEquals( 42, first.get( "score" ) );
        assertEquals( "A great page", first.get( "summary" ) );
        assertEquals( "matching <em>excerpt</em>", first.get( "snippet" ) );
    }

    @Test
    void clampsMaxResults() throws Exception {
        when( engine.getManager( SearchManager.class ) ).thenReturn( searchManager );
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );

        final List< SearchResult > many = new java.util.ArrayList<>();
        for ( int i = 0; i < 40; i++ ) {
            final Page p = mock( Page.class );
            lenient().when( p.getName() ).thenReturn( "P" + i );
            final SearchResult sr = mock( SearchResult.class );
            lenient().when( sr.getPage() ).thenReturn( p );
            lenient().when( sr.getContexts() ).thenReturn( new String[ 0 ] );
            many.add( sr );
        }
        when( searchManager.findPages( any(), any() ) ).thenReturn( many );
        lenient().when( pageManager.getPureText( any(), anyInt() ) ).thenReturn( "" );

        final SearchWikiTool tool = newTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > out = tool.execute( "q", 100, request );

        assertEquals( 25, out.get( "total" ) );
    }
}
